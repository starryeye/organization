package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.OpenFgaProbe;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartEditor;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.RollupSampling;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.scim.fixture.ScimRequest;
import dev.starryeye.organization.scim.fixture.ScimRequestRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 규모 시나리오 (시나리오 문서 §4).
 *
 * <p><b>최초 싱크를 한 번만 만들고 그 위에 순차로 쌓는다.</b> 시나리오마다 전체를 다시
 * 쏘면 SCIM 쪽만 십수 분이다. 실제 운영도 최초 싱크는 한 번뿐이므로 이어 붙이는 쪽이 더
 * 실제에 가깝기도 하다.
 *
 * <p>모든 단계 끝에서 <b>세 가지로</b> 확인한다 — 하네스(포트 경유), OpenFGA 직접 질의,
 * 그리고 필요한 곳에서 admin 조회. 어댑터에 결함이 있으면 하네스는 그 결함에 같이 속는다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimScaleScenarioTest {

    private static OrgChart 기대 = OrgChartFixture.오천명();

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    // ---------- S2: 최초 적재 ----------

    @Test
    @Order(1)
    @DisplayName("S2. 대량 프로비저닝 — 직원 먼저, 그다음 조직")
    void S2_대량_프로비저닝() {
        // given
        List<ScimRequest> requests = ScimRequestRenderer.최초싱크(기대);

        // when
        long t0 = System.currentTimeMillis();
        requests.forEach(this::성공을_기대하며_보낸다);
        long 소요 = System.currentTimeMillis() - t0;
        System.out.printf("%n=== SCIM 최초 싱크: %d건 / %.1f초 (건당 %.1fms)%n",
                requests.size(), 소요 / 1000.0, (double) 소요 / requests.size());

        // then
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("admin 조회가 SCIM 으로 적재된 조직도를 그대로 보여준다")
    void admin조회가_적재를_보여준다() {
        // given — 겸직 직원. 소속이 둘인 사람이 화면에 어떻게 보이는지가 가장 헷갈리는 자리다
        String 겸직 = 기대.landmarks().겸직직원();

        // when
        JsonNode detail = 조회한다("/admin/employees/" + 겸직);

        // then — 경로가 두 갈래, 각 경로의 Check 가 전부 true
        Set<String> 경로조직 = new LinkedHashSet<>();
        detail.get("paths").forEach(path -> {
            경로조직.add(path.get("orgCode").asText());
            assertThat(path.hasNonNull("openFgaCheck")).isTrue();
            assertThat(path.get("openFgaCheck").asBoolean()).isTrue();
        });
        assertThat(경로조직).isEqualTo(기대.기대소속(겸직));

        // 대형 조직 멤버를 커서로 끝까지 — 한 명도 빠지거나 겹치면 안 된다
        String 대형조직 = 기대.landmarks().대형조직();
        assertThat(멤버를_끝까지_읽는다(대형조직)).isEqualTo(직속직원들(대형조직));
    }

    @Test
    @Order(3)
    @DisplayName("S10. 조직 멤버 전체 교체 PUT — 요청에 없는 '빠진 사람'을 이전 목록에서 찾아낸다")
    void S10_PUT_전체_교체() {
        // given — 12명 중 4명을 빼고 3명을 새로 넣는다.
        // 요청 본문에는 <b>빠진 4명이 안 적혀 있다</b> — 이전 멤버 목록을 읽어 계산하는
        // 경로가 여기다(설계 §5.5). 요청 데이터만으로는 무엇을 지울지 알 수 없다.
        String 팀 = 기대.landmarks().이동할팀();
        List<String> 현재멤버 = 직속직원들(팀).stream().sorted().toList();
        assertThat(현재멤버).as("빼고 넣을 여유가 있어야 한다").hasSizeGreaterThan(4);

        List<String> 뺄사람 = 현재멤버.subList(0, 4);
        List<String> 남길사람 = 현재멤버.subList(4, 현재멤버.size());
        List<String> 새사람 = List.of("put.a", "put.b", "put.c");
        새사람.forEach(id -> 성공을_기대하며_보낸다(ScimRequestRenderer.직원생성(
                new dev.starryeye.organization.core.model.DirectoryUser(
                        id, null, id, "신입 " + id, id + "@example.com", true))));

        // 하위 조직 참조는 PUT 본문에도 그대로 실어야 한다 — 빠뜨리면 계층이 끊긴다
        List<MemberRef> 새목록 = new ArrayList<>();
        기대.자식조직들(팀).forEach(자식 -> 새목록.add(MemberRef.group(자식)));
        남길사람.forEach(id -> 새목록.add(MemberRef.user(id)));
        새사람.forEach(id -> 새목록.add(MemberRef.user(id)));

        var 새조직 = new dev.starryeye.organization.core.model.DirectoryGroup(
                팀, 팀, 기대.snapshot().groups().get(팀).displayName(),
                new LinkedHashSet<>(새목록));

        // when
        보낸다(ScimRequestRenderer.조직교체(새조직), 200);
        var editor = OrgChartEditor.편집한다(기대);
        뺄사람.forEach(id -> editor.겸직을_푼다(id, 팀));
        새사람.forEach(id -> editor.직원을_넣는다(팀, id, "신입 " + id, id + "@example.com"));
        기대 = editor.완성();

        // then — 나머지 8명은 손대지 않는다
        검증한다();
        뺄사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 팀)))
                .as("빠졌어야 할 %s 가 남아 있다", id).isFalse());
        남길사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 팀)))
                .as("유지됐어야 할 %s 가 사라졌다", id).isTrue());
        새사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 팀)))
                .as("새로 들어온 %s 가 없다", id).isTrue());
    }

    // ---------- S4~S9: 직원 변경 ----------

    @Test
    @Order(4)
    @DisplayName("S4. 직원 추가 후 조직 편입 — POST 직후에는 튜플이 없다")
    void S4_추가_후_편입() {
        // given
        String 팀 = 기대.landmarks().대상팀();
        String 신규 = "scim.new";

        // when — POST 만
        성공을_기대하며_보낸다(ScimRequestRenderer.직원생성(
                new dev.starryeye.organization.core.model.DirectoryUser(
                        신규, null, 신규, "신입 " + 신규, 신규 + "@example.com", true)));

        // then — 어느 조직에도 없으므로 튜플이 없다
        assertThat(성립하는가(RelationTuple.member(신규, 팀))).isFalse();

        // when — 조직에 편입
        보낸다(ScimRequestRenderer.멤버추가(팀, MemberRef.user(신규)), 200);
        기대 = OrgChartEditor.편집한다(기대)
                .직원을_넣는다(팀, 신규, "신입 " + 신규, 신규 + "@example.com")
                .완성();

        // then — dm 1개 + 조상 체인 롤업
        검증한다();
        기대.기대소속(신규).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(신규, org)))
                        .as("조상 %s 로 롤업돼야 한다", org).isTrue());
    }

    @Test
    @Order(5)
    @DisplayName("S5. 비활성화 — 멤버십은 남고 권한만 사라진다 (겸직 직원)")
    void S5_비활성화() {
        // given — 겸직 직원으로 돌린다. 소속이 여럿일 때 전부 지워지는지가 요점이다
        String 겸직 = 기대.landmarks().겸직직원();
        Set<String> 소속들 = 기대.직속조직들(겸직);
        assertThat(소속들).hasSize(2);

        // when
        보낸다(ScimRequestRenderer.직원비활성(겸직), 200);
        기대 = 활성을_바꾼다(기대, 겸직, false);

        // then — 멤버십은 상태에 그대로 남아 있고(하네스 ①이 본다), 튜플만 사라진다
        검증한다();
        소속들.forEach(org -> assertThat(성립하는가(RelationTuple.member(겸직, org)))
                .as("비활성인데 %s 에서 권한이 남아 있다", org).isFalse());
        기대.기대소속(겸직).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(겸직, org)))
                        .as("조상 %s 에서도 끊겨야 한다", org).isFalse());
    }

    @Test
    @Order(6)
    @DisplayName("S6. 재활성화 — 멤버십을 안 지웠기 때문에 권한이 복원된다")
    void S6_재활성화() {
        // given
        String 겸직 = 기대.landmarks().겸직직원();

        // when
        보낸다(ScimRequestRenderer.직원활성(겸직), 200);
        기대 = 활성을_바꾼다(기대, 겸직, true);

        // then — 소속 조직 전부에 dm 이 복원된다
        검증한다();
        기대.기대소속(겸직).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(겸직, org)))
                        .as("%s 로 복원돼야 한다", org).isTrue());
    }

    @Test
    @Order(7)
    @DisplayName("S8. 직원 속성만 변경 — 튜플은 하나도 안 바뀐다")
    void S8_속성만_변경() {
        // given
        String 직원 = 기대.landmarks().L5직속직원();

        // when
        보낸다(ScimRequestRenderer.직원표시명변경(직원, "개명한 이름"), 200);
        기대 = OrgChartEditor.편집한다(기대)
                .직원속성을_바꾼다(직원, "개명한 이름",
                        기대.snapshot().users().get(직원).email())
                .완성();

        // then — 튜플 식별자는 아이디와 조직코드뿐이다
        검증한다();
        assertThat(조회한다("/admin/employees/" + 직원).get("displayName").asText())
                .isEqualTo("개명한 이름");
    }

    @Test
    @Order(8)
    @DisplayName("S9. userName 중복은 409 로 거부되고 상태를 건드리지 않는다")
    void S9_중복_거부() {
        // given
        String 이미있는사람 = 기대.landmarks().L4직속직원();

        // when
        client.post().uri(ScimRequestRenderer.USERS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ScimRequestRenderer.직원생성(
                        new dev.starryeye.organization.core.model.DirectoryUser(
                                이미있는사람, null, 이미있는사람, "중복", null, true)).body())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");

        // then — 거부된 요청이 상태를 흔들지 않았다
        검증한다();
    }

    @Test
    @Order(9)
    @DisplayName("S7. 직원 삭제 — 모든 조직 멤버 목록과 모든 튜플에서 사라진다")
    void S7_직원_삭제() {
        // given — 겸직 직원. 한쪽만 지워지면 잔여 튜플이 남는다
        String 겸직 = 기대.landmarks().겸직직원();
        Set<String> 소속들 = 기대.직속조직들(겸직);

        // when
        보낸다(ScimRequestRenderer.직원삭제(겸직), 204);
        기대 = OrgChartEditor.편집한다(기대).직원을_지운다(겸직).완성();

        // then
        검증한다();
        소속들.forEach(org -> assertThat(성립하는가(RelationTuple.member(겸직, org))).isFalse());
        client.get().uri("/admin/employees/" + 겸직).exchange().expectStatus().isNotFound();
    }

    // ---------- S10~S16: 조직 변경 ----------

    @Test
    @Order(10)
    @DisplayName("S12. 비활성 직원을 조직에 넣으면 멤버십은 생기고 튜플은 안 생긴다")
    void S12_비활성_직원_편입() {
        // given
        String 팀 = 기대.landmarks().대상파트();
        String 비활성 = "scim.inactive";
        성공을_기대하며_보낸다(ScimRequestRenderer.직원생성(
                new dev.starryeye.organization.core.model.DirectoryUser(
                        비활성, null, 비활성, "비활성 직원", null, false)));

        // when
        보낸다(ScimRequestRenderer.멤버추가(팀, MemberRef.user(비활성)), 200);
        기대 = 비활성_멤버를_더한다(기대, 팀, 비활성);

        // then — "멤버지만 권한 없음". 이 상태가 음성 후보 집합의 존재 이유다
        검증한다();
        assertThat(성립하는가(RelationTuple.member(비활성, 팀))).isFalse();
        assertThat(멤버를_끝까지_읽는다(팀)).contains(비활성);
    }

    @Test
    @Order(11)
    @DisplayName("S11. PATCH remove 는 필터가 있으면 한 명만, 없으면 전원을 지운다")
    void S11_PATCH_세_연산() {
        // given
        String 팀 = 기대.landmarks().대상팀();
        String 뺄사람 = 직속직원들(팀).stream().sorted().findFirst().orElseThrow();

        // when — 필터 있는 remove
        보낸다(ScimRequestRenderer.멤버제거(팀, 뺄사람), 200);
        기대 = OrgChartEditor.편집한다(기대).겸직을_푼다(뺄사람, 팀).완성();

        // then — 그 한 명만
        검증한다();
        assertThat(성립하는가(RelationTuple.member(뺄사람, 팀))).isFalse();
        assertThat(직속직원들(팀)).isNotEmpty();

        // when — 필터 없는 remove 는 전원을 지운다.
        // 직원뿐 아니라 하위 조직 참조까지 사라진다 — 이 팀은 하위 파트를 갖고 있다
        Set<String> 남은사람 = 직속직원들(팀);
        Set<String> 하위조직 = 기대.자식조직들(팀);
        assertThat(하위조직).as("하위 조직도 함께 사라지는지 보려면 자식이 있어야 한다").isNotEmpty();

        보낸다(ScimRequestRenderer.멤버전체제거(팀), 200);
        기대 = OrgChartEditor.편집한다(기대).멤버를_모두_비운다(팀).완성();

        // then — 12명짜리 팀이면 dm 12개, 그리고 child 간선도 함께 사라진다
        검증한다();
        하위조직.forEach(자식 -> assertThat(성립하는가(RelationTuple.child(자식, 팀)))
                .as("필터 없는 remove 는 하위 조직 참조도 지운다: %s", 자식).isFalse());
        남은사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 팀)))
                .as("전체 제거인데 %s 가 남았다", id).isFalse());
    }

    @Test
    @Order(12)
    @DisplayName("S13. 조직 계층 변경 — dm 은 그대로인데 직원들의 권한 범위가 통째로 바뀐다")
    void S13_계층_변경() {
        // given
        String 팀 = 기대.landmarks().이동할팀();
        String 옛실 = 기대.부모(팀);
        String 새실 = 기대.landmarks().이동목적지실();
        List<String> 소속직원 = 직속직원들(팀).stream().sorted().toList();
        var 옛조상들 = 기대.조상들(팀);

        // when — 부모 쪽 PATCH 두 번
        보낸다(ScimRequestRenderer.멤버제거(옛실, 팀), 200);
        보낸다(ScimRequestRenderer.멤버추가(새실, MemberRef.group(팀)), 200);
        기대 = OrgChartEditor.편집한다(기대).조직을_옮긴다(팀, 옛실, 새실).완성();

        // then
        검증한다();
        assertThat(소속직원).isNotEmpty();
        String 대표 = 소속직원.get(0);
        옛조상들.stream().filter(org -> !기대.기대소속(대표).contains(org))
                .forEach(org -> assertThat(성립하는가(RelationTuple.member(대표, org)))
                        .as("옛 조상 %s 에서 끊겨야 한다", org).isFalse());
        assertThat(성립하는가(RelationTuple.member(대표, 새실))).isTrue();
    }

    @Test
    @Order(13)
    @DisplayName("S14. 조직 삭제 — 조직은 사라지고 직원 레코드는 남는다")
    void S14_조직_삭제() {
        // given
        String 팀 = 기대.landmarks().대상파트();
        List<String> 소속직원 = 직속직원들(팀).stream().sorted().toList();
        assertThat(소속직원).isNotEmpty();

        // when
        보낸다(ScimRequestRenderer.조직삭제(팀), 204);
        기대 = OrgChartEditor.편집한다(기대).조직을_지운다(팀).완성();

        // then — 조직이 없어진 것이지 사람이 나간 게 아니다
        검증한다();
        소속직원.forEach(id -> {
            assertThat(성립하는가(RelationTuple.member(id, 팀))).isFalse();
            client.get().uri("/admin/employees/" + id).exchange().expectStatus().isOk();
        });
        client.get().uri("/admin/organizations/" + 팀).exchange().expectStatus().isNotFound();
    }

    @Test
    @Order(14)
    @DisplayName("S15. 대형 조직 멤버 교체 — 락을 쥔 채 BatchCheck 를 도는 구간")
    void S15_대형조직_교체() {
        // given
        String 대형조직 = 기대.landmarks().대형조직();
        List<String> 현재멤버 = 직속직원들(대형조직).stream().sorted().toList();
        List<MemberRef> 남길사람 = 현재멤버.subList(0, 현재멤버.size() - 20).stream()
                .map(MemberRef::user)
                .toList();

        // when
        long t0 = System.currentTimeMillis();
        보낸다(ScimRequestRenderer.멤버전체교체(대형조직, 남길사람), 200);
        long 소요 = System.currentTimeMillis() - t0;
        System.out.printf("=== S15. 대형 조직(%d명) 멤버 교체: %.1f초%n",
                현재멤버.size(), 소요 / 1000.0);

        var editor = OrgChartEditor.편집한다(기대);
        현재멤버.subList(현재멤버.size() - 20, 현재멤버.size())
                .forEach(id -> editor.겸직을_푼다(id, 대형조직));
        기대 = editor.완성();

        // then
        검증한다();
        assertThat(멤버를_끝까지_읽는다(대형조직)).hasSize(현재멤버.size() - 20);
    }

    @Test
    @Order(15)
    @DisplayName("S3. 동시 쓰기 경합 — 못 잡으면 503 이고, 500 은 하나도 없어야 한다")
    void S3_락_경합() throws Exception {
        // given — 이미 활성인 직원에게 active:true 를 보낸다.
        // 어느 요청이 성공하든 <b>최종 상태가 안 바뀌는</b> 연산이라, 경합 결과가
        // 기대 조직도를 흔들지 않는다. 재려는 것은 상태 변화가 아니라 거절 방식이다.
        List<String> 대상 = 직속직원들(기대.landmarks().대형조직()).stream()
                .sorted().limit(200).toList();
        assertThat(대상).isNotEmpty();

        // when — 동시에 쏜다
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
            대상.forEach(id -> futures.add(pool.submit(() -> 상태코드를_받는다(
                    ScimRequestRenderer.직원활성(id)))));

            var 집계 = new java.util.TreeMap<Integer, Integer>();
            for (var future : futures) {
                집계.merge(future.get(2, java.util.concurrent.TimeUnit.MINUTES), 1, Integer::sum);
            }
            System.out.println("=== S3. 동시 16스레드 × " + 대상.size() + "건 응답: " + 집계);

            // then — 락을 못 잡은 요청은 503 이다. IdP 는 503 을 재시도 신호로 보므로
            // 프로비저닝이 유실되지 않는다. 500 이나 400 으로 뭉개면 IdP 가 영구 실패로
            // 판단해 포기하거나 무한히 재시도한다.
            assertThat(집계.keySet())
                    .as("200 과 503 이외의 응답이 나왔다")
                    .isSubsetOf(200, 503);
            assertThat(집계.getOrDefault(200, 0)).as("전부 거절되면 안 된다").isPositive();
        } finally {
            pool.shutdown();
        }

        // 경합이 있었어도 최종 상태는 정합이다
        검증한다();
    }

    @Test
    @Order(16)
    @DisplayName("S16. 순환 조직 참조 — 요청은 성공하고 순환을 닫는 간선만 빠진다")
    void S16_순환_참조() {
        // given — 조상을 자기 자손의 멤버로 넣는다
        String 조상 = 기대.landmarks().개발부문();
        String 자손 = 기대.자손들(조상).stream().sorted()
                .filter(org -> 기대.조상들(org).size() >= 3)
                .findFirst().orElseThrow();
        String 순환밖직원 = 기대.landmarks().L3직속직원();

        // when
        보낸다(ScimRequestRenderer.멤버추가(자손, MemberRef.group(조상)), 200);

        // then — 순환은 그 가지 안에서만 문제여야 한다
        assertThat(성립하는가(RelationTuple.member(순환밖직원, 기대.landmarks().회사()))).isTrue();
    }

    // ---------- 거들기 ----------

    private void 성공을_기대하며_보낸다(ScimRequest request) {
        보낸다(request, 201);
    }

    /** 상태코드만 받는다 — 경합에서는 실패도 정상 응답이라 단정하지 않는다. */
    private int 상태코드를_받는다(ScimRequest request) {
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchange()
                .returnResult(Void.class)
                .getStatus().value();
    }

    private void 보낸다(ScimRequest request, int 기대상태) {
        var spec = switch (request.method()) {
            case "POST" -> client.post().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body());
            case "PUT" -> client.put().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body());
            case "PATCH" -> client.patch().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body());
            case "DELETE" -> client.delete().uri(request.path());
            default -> throw new IllegalArgumentException("알 수 없는 메서드: " + request.method());
        };
        spec.exchange().expectStatus().isEqualTo(기대상태);
    }

    /**
     * 하네스와 OpenFGA 직접 질의 <b>둘 다</b> 돈다. 어댑터에 결함이 있으면 하네스는 그 결함에
     * 같이 속으므로, 같은 사실을 서로 다른 경로로 물어야 갈림을 볼 수 있다.
     */
    private void 검증한다() {
        var 하네스 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(하네스).isNotNull();
        assertThat(하네스.어긋났는가()).as(하네스 == null ? "" : 하네스.요약()).isFalse();

        var 직접 = new OpenFgaProbe(bootstrapper)
                .직접_대조한다(기대, RollupSampling.기본값().표본을_고른다(기대));
        assertThat(직접.어긋났는가()).as(직접.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }

    private Set<String> 직속직원들(String orgCode) {
        Set<String> ids = new LinkedHashSet<>();
        기대.snapshot().groups().get(orgCode).members().stream()
                .filter(member -> member.type() == MemberType.USER)
                .forEach(member -> ids.add(member.id()));
        return ids;
    }

    private Set<String> 멤버를_끝까지_읽는다(String orgCode) {
        Set<String> 멤버 = new LinkedHashSet<>();
        String cursor = null;
        int 페이지수 = 0;
        do {
            JsonNode page = cursor == null
                    ? 조회한다("/admin/organizations/" + orgCode + "/members?limit=100")
                    : 조회한다("/admin/organizations/" + orgCode + "/members?limit=100&cursor={c}",
                            cursor);
            page.get("items").forEach(item -> 멤버.add(item.get("employeeId").asText()));
            cursor = page.hasNonNull("nextCursor") ? page.get("nextCursor").asText() : null;
            페이지수++;
        } while (cursor != null && 페이지수 < 50);
        return 멤버;
    }

    private JsonNode 조회한다(String uriTemplate, Object... values) {
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .get().uri(uriTemplate, values).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    private static OrgChart 활성을_바꾼다(OrgChart chart, String userId, boolean active) {
        var users = new java.util.LinkedHashMap<>(chart.snapshot().users());
        var 원본 = users.get(userId);
        users.put(userId, new dev.starryeye.organization.core.model.DirectoryUser(
                원본.id(), 원본.externalId(), 원본.userName(), 원본.displayName(), 원본.email(), active));
        return new OrgChart(new dev.starryeye.organization.core.model.DirectorySnapshot(
                users, chart.snapshot().groups()), chart.landmarks());
    }

    private static OrgChart 비활성_멤버를_더한다(OrgChart chart, String orgCode, String userId) {
        var users = new java.util.LinkedHashMap<>(chart.snapshot().users());
        users.put(userId, new dev.starryeye.organization.core.model.DirectoryUser(
                userId, null, userId, "비활성 직원", null, false));
        var groups = new java.util.LinkedHashMap<>(chart.snapshot().groups());
        var 원본 = groups.get(orgCode);
        var members = new ArrayList<>(원본.members());
        members.add(MemberRef.user(userId));
        groups.put(orgCode, new dev.starryeye.organization.core.model.DirectoryGroup(
                원본.id(), 원본.externalId(), 원본.displayName(), new LinkedHashSet<>(members)));
        return new OrgChart(new dev.starryeye.organization.core.model.DirectorySnapshot(
                users, groups), chart.landmarks());
    }
}
