package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.OpenFgaProbe;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartEditor;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.RollupSampling;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 S17·S19·S18 — 한계·아카이빙·재적재 (시나리오 문서 §4.4).
 *
 * <p>셋이 한 클래스인 것은 <b>서로 이어지기 때문</b>이다. 고아 튜플을 심고(S17) → 아카이빙이
 * 그것을 담지 않는 것을 보고(S19) → 재적재가 그것을 지우는 것을 본다(S18). 따로 떼면 각각
 * 5,376건 적재를 다시 해야 하고, 무엇보다 <b>"무엇으로는 안 지워지고 무엇으로는 지워지는가"</b>
 * 라는 하나의 이야기가 세 조각으로 흩어진다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// Spring Boot 는 테스트에서 메트릭 익스포트를 기본으로 끈다 — 이것이 없으면 설정에
// prometheus 를 노출해 뒀어도 /actuator/prometheus 가 404 다. 실제 앱은 200 을 준다.
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimLimitsAndRecoveryScaleTest {

    private static final OrgChart 기대 = OrgChartFixture.오천명();

    /** 멤버십이 아예 없는 고아 튜플. 동기화로는 만들 수도 지울 수도 없는 상태다. */
    private static final RelationTuple 고아 =
            RelationTuple.directMember("ghost.user", "DEV5_0");

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
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired SnapshotArchiveUseCase archive;

    @Test
    @Order(1)
    @DisplayName("5,376건을 적재해 기준 상태를 만든다")
    void 기준_상태를_만든다() {
        // when
        ScimRequestRenderer.최초싱크(기대).forEach(this::보낸다);

        // then
        검증한다();
    }

    // ---------- S17: 한계 ----------

    @Test
    @Order(2)
    @DisplayName("S17-a. 멤버십 없는 고아 튜플은 어떤 SCIM 쓰기로도 지워지지 않는다")
    void S17a_고아_튜플은_안_지워진다() {
        // given — 동기화가 만들 수 없는 상태를 일부러 만든다.
        // ghost.user 는 DynamoDB 에 없고 어느 조직의 멤버도 아니다.
        새_프로브().직접_심는다(고아);
        assertThat(성립하는가(고아)).as("전제: 고아 튜플이 심어졌다").isTrue();

        // when — 그 조직을 실제로 건드리는 SCIM 쓰기를 여러 번 한다
        String 조직 = 고아.object().substring("group:".length());
        보낸다(ScimRequestRenderer.멤버추가(조직,
                dev.starryeye.organization.core.model.MemberRef.user(
                        기대.landmarks().L2직속직원())), 200);
        보낸다(ScimRequestRenderer.멤버제거(조직, 기대.landmarks().L2직속직원()), 200);

        // then — 그대로 남는다. 후보 집합이 멤버십에서 나오므로 아예 물어보지도 않는다
        assertThat(성립하는가(고아))
                .as("SCIM 쓰기가 고아 튜플을 건드릴 수 있게 됐다면 좋은 소식이다 — "
                        + "설계 §5.4 의 한계가 해소된 것이니 문서를 고쳐라")
                .isTrue();

        // 그리고 하네스도 이것을 못 잡는다. 사각지대를 통과 케이스로 고정한다
        검증한다();
    }

    // ---------- S19: 아카이빙 ----------

    @Test
    @Order(3)
    @DisplayName("S19. 아카이빙은 의도한 것이 아니라 관찰한 실제 튜플을 저장한다")
    void S19_아카이빙() {
        // given — 어긋남을 하나 심는다. 있어야 할 튜플을 직접 지운다
        String 직원 = 기대.landmarks().L5직속직원();
        RelationTuple 지운것 = RelationTuple.directMember(직원, 기대.직속조직(직원));
        새_프로브().직접_지운다(지운것);
        assertThat(성립하는가(지운것)).as("전제: 어긋남이 만들어졌다").isFalse();

        // when
        long t0 = System.currentTimeMillis();
        var outcome = archive.execute().block(Duration.ofMinutes(10));
        System.out.printf("%n=== S19. 아카이빙 5,000 규모: %.1f초%n",
                (System.currentTimeMillis() - t0) / 1000.0);

        // then
        assertThat(outcome).isNotNull();
        var snapshot = snapshots.findLatest().block(Duration.ofMinutes(1));
        assertThat(snapshot).isNotNull();

        // 관찰한 것을 담는다 — 직접 지운 튜플은 스냅샷에 없어야 한다.
        // 의도한 것(멤버십에서 유도한 것)을 담았다면 여기 들어가 있을 것이다.
        assertThat(snapshot.tuples())
                .as("아카이빙이 실제 상태가 아니라 의도한 상태를 담았다")
                .doesNotContain(지운것);

        // 고아 튜플도 없다 — 후보가 멤버십에서 나오므로 물어보지 않았기 때문이다
        assertThat(snapshot.tuples())
                .as("고아 튜플이 후보에 없으므로 스냅샷에도 없어야 한다")
                .doesNotContain(고아);

        // 지운 것 하나만 빠진 나머지는 그대로 담겼다
        assertThat(snapshot.tuples().size())
                .isEqualTo(TupleMapper.toTuples(기대.snapshot()).tuples().size() - 1);
    }

    // ---------- S18: 재적재 ----------

    @Test
    @Order(4)
    @DisplayName("S18. mode=tuples 재적재가 어긋남을 메우고 고아 튜플을 지운다")
    void S18_재적재() {
        // given — S19 가 남긴 어긋남(지워진 튜플)과 S17 의 고아 튜플이 그대로 있다
        String 직원 = 기대.landmarks().L5직속직원();
        RelationTuple 지웠던것 = RelationTuple.directMember(직원, 기대.직속조직(직원));
        assertThat(성립하는가(지웠던것)).isFalse();
        assertThat(성립하는가(고아)).isTrue();

        // when
        long t0 = System.currentTimeMillis();
        client.mutate().responseTimeout(Duration.ofMinutes(20)).build()
                .post().uri("/admin/sync/rebuild?mode=tuples").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
        System.out.printf("=== S18. mode=tuples 재적재 5,541 튜플: %.1f초%n",
                (System.currentTimeMillis() - t0) / 1000.0);

        // then — 어긋남이 메워지고
        assertThat(성립하는가(지웠던것)).as("재적재가 빠진 튜플을 다시 쓰지 않았다").isTrue();

        // 고아 튜플은 사라진다. 재적재는 이전 스냅샷을 지우고 다시 쓰므로,
        // 멤버십에서 유도되지 않는 것은 남을 자리가 없다.
        assertThat(성립하는가(고아))
                .as("재적재로도 안 지워지면 설계 §5.4 의 복구 수단이 없는 것이다")
                .isFalse();

        검증한다();
    }

    @Test
    @Order(5)
    @DisplayName("이력과 메트릭이 5,000 규모에서 제대로 남는다")
    void 이력과_메트릭이_남는다() {
        // when
        JsonNode runs = client.get().uri("/admin/sync/runs?limit=10").exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        // then — 재적재가 이력에 남아 있고 실제로 쓴 건수가 기록됐다
        assertThat(runs).isNotEmpty();
        JsonNode 최근 = runs.get(0);
        assertThat(최근.get("source").asText()).isEqualTo("SCIM");
        assertThat(최근.get("trigger").asText()).isEqualTo("REBUILD");
        assertThat(최근.get("writtenCount").asInt()).isGreaterThan(5_000);

        String metrics = client.get().uri("/actuator/prometheus").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(metrics).isNotNull();
        System.out.println("=== SCIM 메트릭 ===");
        metrics.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith("sync_") || line.startsWith("scim_")
                        || line.startsWith("authz_"))
                .forEach(System.out::println);

        // SCIM 이 실제로 내보내는 이름으로 본다. LDAP 의 sync_tuples_written_total 은
        // 전체 동기화 경로의 것이라 SCIM 에는 없다 — 이름을 베껴 쓰면 "메트릭이 있다" 를
        // 검증한다고 믿으면서 실제로는 없는 이름을 확인하게 된다.
        assertThat(metrics)
                .as("락 대기 메트릭이 없으면 경합을 관측할 수단이 사라진다")
                .contains("scim_lock_wait_seconds_count");
        assertThat(metrics)
                .as("Check 호출과 어긋남 카운터는 운영이 드리프트를 보는 유일한 창이다")
                .contains("authz_checks_total")
                .contains("authz_drift_detected_total");
    }

    // ---------- S20: 순서 뒤집힘 ----------

    @Test
    @Order(6)
    @DisplayName("S20. push 순서가 뒤집히면 IdP 의 의도와 반대 상태가 되고, 우리는 그것을 못 잡는다")
    void S20_순서가_뒤집히면_못_잡는다() {
        // given — IdP 의 의도는 "넣었다가 뺀다" 이므로 최종적으로 이 직원은 팀에 없어야 한다.
        //
        //   IdP 의도:  add(kim→TEAM)  →  remove(kim→TEAM)   최종: 팀에 없음
        //
        // 그런데 add 가 503 으로 밀렸다가 remove 뒤에 재시도되면 도착 순서가 뒤집힌다.
        // SCIM 명세에는 요청 간 순서 보장이 없고, 우리에게는 IdP 의 의도 순서를 알 방법이 없다.
        String 팀 = 기대.landmarks().대상팀();
        String 직원 = 기대.landmarks().L2직속직원();
        assertThat(기대.직속조직들(직원)).as("전제: 이 직원은 그 팀 소속이 아니다").doesNotContain(팀);

        // when — 뒤집힌 순서로 도착한다: remove 가 먼저, add 가 나중
        보낸다(ScimRequestRenderer.멤버제거(팀, 직원), 200);
        보낸다(ScimRequestRenderer.멤버추가(팀,
                dev.starryeye.organization.core.model.MemberRef.user(직원)), 200);

        // then — IdP 가 마지막으로 원한 것은 "빠짐" 인데 실제로는 "들어감" 이다
        assertThat(성립하는가(RelationTuple.member(직원, 팀)))
                .as("순서가 뒤집혀도 IdP 의 최신 의도가 지켜졌다면 좋은 소식이다 — "
                        + "순서 보장이 생긴 것이니 설계 문서를 고쳐라")
                .isTrue();

        // 그리고 <b>하네스는 이것을 못 잡는다.</b> DynamoDB 와 OpenFGA 가 서로 완벽히
        // 일치하기 때문이다 — 고아 튜플도 없고 드리프트도 없다. 어긋난 것은
        // "우리 상태 ↔ IdP 의 의도" 이고, 우리는 IdP 를 읽을 수단이 없다(SCIM 은 push 전용).
        //
        // 이것이 S17 의 고아 튜플보다 더 안 보이는 이유다. 고아 튜플은 최소한 재적재로
        // 지워지기라도 하는데, 이것은 재적재해도 우리 DynamoDB 기준으로 다시 쓰므로
        // 틀린 채로 굳는다.
        var 뒤집힌결과 = OrgChartEditor.편집한다(기대).겸직을_더한다(직원, 팀).완성();
        var 하네스 = new SyncVerifier(state, checker).검증한다(뒤집힌결과).block(Duration.ofMinutes(10));
        assertThat(하네스).isNotNull();
        assertThat(하네스.어긋났는가())
                .as("하네스가 순서 뒤집힘을 잡게 됐다면 무엇이 바뀐 것인지 확인하라: "
                        + (하네스 == null ? "" : 하네스.요약()))
                .isFalse();

        // 원래대로 돌려놓는다 — 뒤 시나리오가 이 상태를 물려받지 않도록
        보낸다(ScimRequestRenderer.멤버제거(팀, 직원), 200);
        검증한다();
    }

    // ---------- 거들기 ----------

    private OpenFgaProbe 새_프로브() {
        return new OpenFgaProbe(bootstrapper);
    }

    private void 보낸다(ScimRequest request) {
        보낸다(request, 201);
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

    private void 검증한다() {
        var 하네스 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(하네스).isNotNull();
        assertThat(하네스.어긋났는가()).as(하네스 == null ? "" : 하네스.요약()).isFalse();

        var 직접 = 새_프로브().직접_대조한다(기대, RollupSampling.기본값().표본을_고른다(기대));
        assertThat(직접.어긋났는가()).as(직접.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }
}
