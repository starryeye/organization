package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartEditor;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.LdapDirectory;
import dev.starryeye.organization.ldap.fixture.LdifRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP 규모 시나리오 L1~L11, L16 (시나리오 문서 §3).
 *
 * <p><b>각 시나리오는 직전 시나리오의 결과 위에서 이어진다.</b> 실제 운영이 그렇기 때문이다 —
 * 매번 비우고 시작하면 "어제 상태에서 오늘 델타" 라는 이 설계의 본체를 한 번도 안 밟는다.
 * 그래서 {@link Order} 에 의존하고, {@link #기대} 는 시나리오마다 편집돼 이어진다.
 *
 * <p>편집은 <b>기대 조직도와 실제 LDAP 양쪽에</b> 나란히 가한다. 한쪽만 바꾸고 다른 쪽을
 * 손으로 맞추면, 검증이 실패했을 때 구현이 틀린 건지 기대값이 틀린 건지 알 수 없다.
 *
 * <p>L12~L15, L17 은 설정이나 서버 자체를 손봐야 해서 별도 클래스에 있다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapScaleScenarioTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart 최초 = OrgChartFixture.오천명();

    /** 시나리오가 이어지면서 편집되는 기대값. */
    private static OrgChart 기대 = 최초;

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

    static InMemoryDirectoryServer LDAP;
    static LdapDirectory 디렉터리;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("scenario", 0));
        // 빈 조직은 groupOfNames 의 member 필수 제약에 걸린다. 그 형태를 일부러 살린다.
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(최초).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();
        디렉터리 = new LdapDirectory(LDAP, BASE_DN);

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;

    // ---------- L1~L2: 최초와 무변경 ----------

    @Test
    @Order(1)
    @DisplayName("L1. 최초 전체 동기화 — 빈 상태에서 5,541 튜플을 만든다")
    void L1_최초_전체_동기화() {
        // when
        var 결과 = 동기화한다();

        // then
        결과.jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(5_541)
                .jsonPath("$.deletedCount").isEqualTo(0);
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("L2. 무변경 재동기화 — 1년에 364번 도는 정상 케이스")
    void L2_무변경_재동기화() {
        // when — LDAP 을 그대로 두고 다시 돌린다
        var 결과 = 동기화한다();

        // then — 델타가 비면 OpenFGA 를 아예 호출하지 않는다.
        // noChange() 는 별도 상태값이 아니라 SUCCEEDED + 메시지다.
        결과.jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
        검증한다();
    }

    // ---------- L3~L7: 직원 ----------

    @Test
    @Order(3)
    @DisplayName("L3. 직원 추가 — 델타가 정확히 3건인가")
    void L3_직원_추가() {
        // given
        String 팀 = 기대.landmarks().대상팀();
        List<String> 신규 = List.of("new.a", "new.b", "new.c");

        // when
        var editor = OrgChartEditor.편집한다(기대);
        신규.forEach(id -> {
            editor.직원을_넣는다(팀, id, "신입 " + id, id + "@example.com");
            디렉터리.직원을_넣는다(팀, id, "신입 " + id, id + "@example.com");
        });
        기대 = editor.완성();

        // then — 전체 재계산인데도 변경분만 쓰는지가 이 설계의 전제다
        동기화한다().jsonPath("$.writtenCount").isEqualTo(3)
                .jsonPath("$.deletedCount").isEqualTo(0);
        검증한다();
        신규.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 기대.landmarks().회사())))
                .as("신규 직원 %s 가 회사까지 롤업돼야 한다", id).isTrue());
    }

    @Test
    @Order(4)
    @DisplayName("L4. 직원 삭제 — 음성 검증이 잔여 튜플을 잡는 자리")
    void L4_직원_삭제() {
        // given
        String 파트 = 기대.landmarks().대상파트();
        List<String> 지울사람 = 기대.snapshot().groups().get(파트).members().stream()
                .filter(member -> member.type() == dev.starryeye.organization.core.model.MemberType.USER)
                .map(MemberRef::id)
                .limit(2)
                .toList();

        // when
        var editor = OrgChartEditor.편집한다(기대);
        지울사람.forEach(id -> {
            디렉터리.직원을_지운다(id, 파트);
            editor.직원을_지운다(id);
        });
        기대 = editor.완성();

        // then
        동기화한다().jsonPath("$.deletedCount").isEqualTo(2);
        검증한다();
        지울사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 파트)))
                .as("삭제된 직원 %s 는 어느 조직에도 member 가 아니어야 한다", id).isFalse());
    }

    @Test
    @Order(5)
    @DisplayName("L5. 직원 속성 변경 — 튜플은 하나도 안 바뀌어야 한다")
    void L5_직원_속성_변경() {
        // given
        String 직원 = 기대.landmarks().L6직속직원();

        // when
        디렉터리.직원속성을_바꾼다(직원, "개명한 이름", "renamed@example.com");
        기대 = OrgChartEditor.편집한다(기대)
                .직원속성을_바꾼다(직원, "개명한 이름", "renamed@example.com")
                .완성();

        // then — 튜플 식별자는 직원 아이디와 조직코드뿐이다.
        // 이름이 바뀌었다고 튜플이 다시 쓰이면 설계가 깨진 것이다.
        동기화한다().jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
        검증한다();
    }

    @Test
    @Order(6)
    @DisplayName("L6. 직원 소속 이동 — 옛 조상 체인이 전부 끊긴다")
    void L6_직원_소속_이동() {
        // given — 깊이가 다른 부문으로 옮긴다. 체인 길이가 바뀌는지 본다
        String 직원 = 기대.landmarks().L5직속직원();
        String 옛조직 = 기대.직속조직(직원);
        String 새조직 = 기대.landmarks().대상파트();
        var 옛조상들 = 기대.조상들(옛조직);

        // when
        디렉터리.직원을_옮긴다(직원, 옛조직, 새조직);
        기대 = OrgChartEditor.편집한다(기대).직원을_옮긴다(직원, 옛조직, 새조직).완성();

        // then
        동기화한다().jsonPath("$.writtenCount").isEqualTo(1)
                .jsonPath("$.deletedCount").isEqualTo(1);
        검증한다();

        assertThat(성립하는가(RelationTuple.member(직원, 옛조직))).isFalse();
        옛조상들.stream()
                .filter(org -> !기대.기대소속(직원).contains(org))
                .forEach(org -> assertThat(성립하는가(RelationTuple.member(직원, org)))
                        .as("옛 조상 %s 에서 끊겨야 한다", org).isFalse());
        기대.기대소속(직원).forEach(org -> assertThat(성립하는가(RelationTuple.member(직원, org)))
                .as("새 조상 %s 로 이어져야 한다", org).isTrue());
    }

    @Test
    @Order(7)
    @DisplayName("L7. 겸직 해제 — 남은 쪽이 같이 지워지지 않는다")
    void L7_겸직_추가와_해제() {
        // given
        String 직원 = 기대.landmarks().L4직속직원();
        String 원소속 = 기대.직속조직(직원);
        String 겸직조직 = 기대.landmarks().대상파트();

        // when — 겸직을 더한다
        디렉터리.겸직을_더한다(직원, 겸직조직);
        기대 = OrgChartEditor.편집한다(기대).겸직을_더한다(직원, 겸직조직).완성();
        동기화한다().jsonPath("$.writtenCount").isEqualTo(1);
        검증한다();

        // then — 두 경로가 동시에 성립한다
        assertThat(성립하는가(RelationTuple.member(직원, 원소속))).isTrue();
        assertThat(성립하는가(RelationTuple.member(직원, 겸직조직))).isTrue();

        // when — 겸직만 푼다
        디렉터리.겸직을_푼다(직원, 겸직조직);
        기대 = OrgChartEditor.편집한다(기대).겸직을_푼다(직원, 겸직조직).완성();
        동기화한다().jsonPath("$.deletedCount").isEqualTo(1);
        검증한다();

        // then — 이 시나리오의 요점: 한쪽만 사라진다
        assertThat(성립하는가(RelationTuple.member(직원, 겸직조직))).isFalse();
        assertThat(성립하는가(RelationTuple.member(직원, 원소속)))
                .as("겸직을 풀었다고 원소속까지 지워지면 안 된다").isTrue();
    }

    // ---------- L8~L11: 조직 ----------

    @Test
    @Order(8)
    @DisplayName("L8. 조직 추가 — child 1개 + dm 5개")
    void L8_조직_추가() {
        // given
        String 부모실 = 기대.landmarks().이동목적지실();
        String 새팀 = "NEW_TEAM";

        // when
        디렉터리.조직을_넣는다(새팀, "신설팀", 부모실);
        var editor = OrgChartEditor.편집한다(기대).조직을_넣는다(새팀, "신설팀", 부모실);
        for (int i = 0; i < 5; i++) {
            String id = "newteam.u" + i;
            디렉터리.직원을_넣는다(새팀, id, "신설팀 " + id, id + "@example.com");
            editor.직원을_넣는다(새팀, id, "신설팀 " + id, id + "@example.com");
        }
        기대 = editor.완성();

        // then
        동기화한다().jsonPath("$.writtenCount").isEqualTo(6)
                .jsonPath("$.deletedCount").isEqualTo(0);
        검증한다();
        assertThat(성립하는가(RelationTuple.member("newteam.u0", 기대.landmarks().회사()))).isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("L9. 조직 이동 — 직원을 하나도 안 건드렸는데 권한 범위가 통째로 바뀐다")
    void L9_조직_이동() {
        // given
        String 팀 = 기대.landmarks().이동할팀();
        String 옛실 = 기대.부모(팀);
        String 새실 = 기대.landmarks().이동목적지실();
        var 소속직원 = 기대.snapshot().groups().get(팀).members().stream()
                .filter(member -> member.type() == dev.starryeye.organization.core.model.MemberType.USER)
                .map(MemberRef::id)
                .toList();
        var 옛조상들 = 기대.조상들(팀);

        // when
        디렉터리.조직을_옮긴다(팀, 옛실, 새실);
        기대 = OrgChartEditor.편집한다(기대).조직을_옮긴다(팀, 옛실, 새실).완성();

        // then — child 엣지 하나만 갈린다. dm 은 하나도 안 바뀐다
        동기화한다().jsonPath("$.writtenCount").isEqualTo(1)
                .jsonPath("$.deletedCount").isEqualTo(1);
        검증한다();

        assertThat(소속직원).isNotEmpty();
        String 대표 = 소속직원.get(0);
        옛조상들.stream()
                .filter(org -> !기대.기대소속(대표).contains(org))
                .forEach(org -> assertThat(성립하는가(RelationTuple.member(대표, org)))
                        .as("옛 조상 %s 에서 끊겨야 한다", org).isFalse());
        assertThat(성립하는가(RelationTuple.member(대표, 새실)))
                .as("새 부모로 이어져야 한다").isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("L10. 중간 조직 삭제 — 계층이 끊겨 하위 팀이 고아가 된다")
    void L10_중간_조직_삭제() {
        // given
        String 삭제할실 = 기대.landmarks().삭제할실();
        String 부모 = 기대.부모(삭제할실);
        var 고아될팀들 = 기대.자식조직들(삭제할실);
        var 고아팀_직원 = 고아될팀들.stream()
                .flatMap(team -> 기대.snapshot().groups().get(team).members().stream())
                .filter(member -> member.type() == dev.starryeye.organization.core.model.MemberType.USER)
                .map(MemberRef::id)
                .findFirst().orElseThrow();
        String 고아팀 = 기대.직속조직(고아팀_직원);

        // when — 실 엔트리만 지운다. 하위 팀들은 LDAP 에 그대로 남는다
        디렉터리.조직을_지운다(삭제할실, 부모);
        기대 = OrgChartEditor.편집한다(기대).조직을_지운다(삭제할실).완성();

        // then
        동기화한다().jsonPath("$.status").isEqualTo("SUCCEEDED");
        검증한다();

        // 고아가 된 팀의 직원은 자기 팀에는 member 지만 옛 조상 체인에서는 끊긴다
        assertThat(성립하는가(RelationTuple.member(고아팀_직원, 고아팀))).isTrue();
        assertThat(성립하는가(RelationTuple.member(고아팀_직원, 삭제할실))).isFalse();
        assertThat(성립하는가(RelationTuple.member(고아팀_직원, 기대.landmarks().회사())))
                .as("계층이 끊겼으므로 회사까지 닿지 않아야 한다").isFalse();
    }

    @Test
    @Order(11)
    @DisplayName("L11. 조직명 변경 — 코드가 그대로면 델타 0건")
    void L11_조직명_변경() {
        // given
        String 조직 = 기대.landmarks().대상팀();

        // when
        디렉터리.조직명을_바꾼다(조직, "개편된 팀 이름");
        기대 = OrgChartEditor.편집한다(기대).조직명을_바꾼다(조직, "개편된 팀 이름").완성();

        // then
        동기화한다().jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
        검증한다();
    }

    // ---------- L16: 순환 ----------

    @Test
    @Order(16)
    @DisplayName("L16. 순환 참조 유입 — 완주하고, 순환을 닫는 간선만 빠진다")
    void L16_순환_참조() {
        // given — 조상을 자기 자손의 하위로 붙인다 (A→B→C→A)
        String 조상 = 기대.landmarks().개발부문();
        String 자손 = 기대.자손들(조상).stream()
                .filter(org -> 기대.조상들(org).size() >= 3)
                .findFirst().orElseThrow();
        String 순환밖직원 = 기대.landmarks().L3직속직원();

        // when
        디렉터리.하위조직으로_붙인다(자손, 조상);

        // then — 동기화는 완주한다. 순환 때문에 멈추면 그날 전체가 반영되지 않는다
        동기화한다().jsonPath("$.status").isEqualTo("SUCCEEDED");

        // 순환 밖 직원의 권한은 그대로다
        assertThat(성립하는가(RelationTuple.member(순환밖직원, 기대.landmarks().회사())))
                .as("순환은 그 가지 안에서만 문제여야 한다").isTrue();
    }

    // ---------- 거들기 ----------

    private WebTestClient.BodyContentSpec 동기화한다() {
        return client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private void 검증한다() {
        var 결과 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(결과).isNotNull();
        assertThat(결과.어긋났는가()).as(결과 == null ? "" : 결과.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }
}
