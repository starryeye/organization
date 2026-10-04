package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import dev.starryeye.organization.scim.fixture.ScimIdBook;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * SCIM 요청 → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간을 실제 컨테이너 위에서 확인한다.
 *
 * <p>테스트는 순서에 의존한다. 앞선 테스트가 만든 상태 위에서 다음 테스트가 변경을 가한다 —
 * SCIM 이 push 모델이라는 사실 자체가 그런 순차성을 전제하기 때문이다.
 *
 * <p>아이디는 서버가 정한다(설계 2026-10-04 §3.1). 만들 때 응답의 id 를 {@link #아이디들} 에 조직도 이름(kim, DEV002 …)으로 적어 두고,
 * 뒤 테스트의 경로·멤버 값·Check 는 거기서 꺼낸다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimEndToEndTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    /** 테스트 사이에 이어지는 조직도 이름 → 서버 id. 테스트 인스턴스는 메서드마다 새로 만들어지므로 정적이다. */
    private static final ScimIdBook 아이디들 = new ScimIdBook();

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired DirectoryStateRepository state;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired SnapshotArchiveUseCase archive;
    @Autowired SyncRunRepository runs;
    @Autowired ArchiveScheduler scheduler;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 만들고, 응답의 서버 id 를 조직도 이름으로 적어 둔다. */
    private void 만든다(String 이름, String uri, String body) {
        아이디들.기록한다(이름, 만든_아이디(client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()));
    }

    private String 서버(String 이름) {
        return 아이디들.서버(이름);
    }

    @Test
    @Order(1)
    @DisplayName("직원과 조직을 만들고 멤버로 넣으면 OpenFGA 에 소속이 반영된다")
    void 직원과_조직을_만들고_연결한다() {
        // given, when — 직원 둘
        만든다("kim", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"kim","displayName":"김철수","active":true}
                """);
        만든다("park", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"park","displayName":"박민수","active":true}
                """);

        // when — 조직 둘, 하위 조직과 직원을 멤버로. 멤버 값은 IdP 가 받은 id 다
        만든다("DEV002", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV002","displayName":"백엔드팀",
                 "members":[{"value":"%s","type":"User"}]}
                """.formatted(서버("kim")));
        만든다("DEV001", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV001","displayName":"개발본부",
                 "members":[{"value":"%s","type":"Group"},
                            {"value":"%s","type":"User"}]}
                """.formatted(서버("DEV002"), 서버("park")));

        // then — 직속 소속
        assertThat(check("user:" + 서버("kim"), "member", "group:" + 서버("DEV002"))).isTrue();
        // then — 하위 조직을 통한 롤업. 이것이 인가 모델의 존재 이유다
        assertThat(check("user:" + 서버("kim"), "member", "group:" + 서버("DEV001"))).isTrue();
        // then — 상속은 상위로만 향한다. 상위 직속인 park 은 하위 조직의 멤버가 아니다
        assertThat(check("user:" + 서버("park"), "member", "group:" + 서버("DEV002"))).isFalse();

        // then — 아이디는 서버가 발급한 UUID 이고 userName 은 받은 그대로 속성에 남는다
        var loaded = state.loadAll().block();
        assertThat(loaded.users()).containsOnlyKeys(서버("kim"), 서버("park"));
        assertThat(UUID.fromString(서버("kim"))).hasToString(서버("kim"));
        assertThat(loaded.users().get(서버("kim")).userName()).isEqualTo("kim");
        assertThat(loaded.groups().get(서버("DEV001")).displayName()).isEqualTo("개발본부");
    }

    @Test
    @Order(2)
    @DisplayName("PATCH 로 멤버를 빼면 그 소속만 사라지고 나머지는 남는다")
    void PATCH로_멤버를_뺀다() {
        // given, when
        client.patch().uri("/scim/v2/Groups/" + 서버("DEV002")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"remove","path":"members[value eq \\"%s\\"]"}]}
                        """.formatted(서버("kim")))
                .exchange().expectStatus().isNoContent();

        // then
        assertThat(check("user:" + 서버("kim"), "member", "group:" + 서버("DEV002"))).isFalse();
        assertThat(check("user:" + 서버("kim"), "member", "group:" + 서버("DEV001"))).isFalse();
        assertThat(check("user:" + 서버("park"), "member", "group:" + 서버("DEV001"))).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("직원을 비활성화하면 남은 소속의 튜플도 사라진다")
    void 비활성화가_소속을_지운다() {
        // given, when
        client.patch().uri("/scim/v2/Users/" + 서버("park")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);

        // then — 비활성 직원에게 권한이 남지 않는다
        assertThat(check("user:" + 서버("park"), "member", "group:" + 서버("DEV001"))).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("직원을 다시 활성화하면 소속이 되살아난다")
    void 재활성화가_소속을_되살린다() {
        // given, when
        client.patch().uri("/scim/v2/Users/" + 서버("park")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":true}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(check("user:" + 서버("park"), "member", "group:" + 서버("DEV001"))).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("조직을 삭제하면 상위 조직에서의 연결도 함께 끊긴다")
    void 조직을_삭제한다() {
        // given — DEV002 는 DEV001 의 하위 조직이다
        assertThat(state.loadAll().block().groups().get(서버("DEV001")).members())
                .anyMatch(member -> member.id().equals(서버("DEV002")));

        // when
        client.delete().uri("/scim/v2/Groups/" + 서버("DEV002"))
                .exchange().expectStatus().isNoContent();

        // then
        assertThat(state.loadAll().block().groups()).doesNotContainKey(서버("DEV002"));
        assertThat(state.loadAll().block().groups().get(서버("DEV001")).members())
                .noneMatch(member -> member.id().equals(서버("DEV002")));
    }

    @Test
    @Order(6)
    @DisplayName("없는 리소스를 조회하면 SCIM Error 스키마로 404 가 돌아온다")
    void 없는_리소스는_SCIM_에러다() {
        // given, when, then
        client.get().uri("/scim/v2/Groups/DEV999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("404");
    }

    @Test
    @Order(7)
    @DisplayName("아카이빙은 현재상태를 SCIM 소스 스냅샷으로 남긴다")
    void 아카이빙이_스냅샷을_남긴다() {
        // given — 앞선 테스트들이 만든 상태가 남아 있다
        // when
        var run = archive.execute().block();

        // then
        assertThat(run.status().name()).isEqualTo("SUCCEEDED");
        var snapshot = snapshots.findLatest().block();
        assertThat(snapshot.source().name()).isEqualTo("SCIM");
        assertThat(snapshot.id()).endsWith("-SCIM");
        assertThat(snapshot.tuples())
                .anyMatch(tuple -> tuple.object().equals("group:" + 서버("DEV001")));

        // then — 이 계획의 핵심 불변식: SCIM push 요청은 SyncRun 에 기록되지 않는다.
        // 지금까지 순서 1~5 에서 8건의 변경 요청(User 생성 2, Group 생성 2, PATCH 3,
        // DELETE 1)이 있었지만, 이 아카이빙 배치 하나만 SyncRun 으로 남아야 한다 —
        // push 경로가 SyncRunRepository 를 부르기 시작하는 회귀가 있었다면 여기서 잡힌다
        var recentRuns = runs.findRecent(50).collectList().block();
        assertThat(recentRuns).hasSize(1);
        assertThat(recentRuns.get(0).trigger().name()).isEqualTo("ARCHIVE");
    }

    @Test
    @Order(8)
    @DisplayName("헬스체크가 DynamoDB 와 OpenFGA 연결을 모두 UP 으로 보고한다")
    void 헬스체크가_UP이다() {
        // given, when, then
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.dynamoDb.status").isEqualTo("UP")
                .jsonPath("$.components.openFga.status").isEqualTo("UP");
    }

    @Test
    @Order(9)
    @DisplayName("상위 조직을 먼저 만들고 하위 조직을 나중에 만들어도 롤업이 성립한다")
    void 상위조직을_먼저_만들어도_롤업이_성립한다() {
        // given — 순서 1 은 하위(DEV002) 를 먼저 만들었다. 여기서는 그 거울상, 즉 상위 조직을 먼저 만든다.
        // 서버가 아이디를 정하므로 아직 없는 조직을 멤버로 적을 수 없다 — IdP 도 하위 조직을 만든 뒤에야 그 id 로 상위에 넣는다
        만든다("choi", "/scim/v2/Users", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"choi","displayName":"최지훈","active":true}
                """);
        만든다("QA001", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"QA001","displayName":"품질본부"}
                """);

        // when — 하위 조직이 뒤늦게 도착하고, 그 id 로 상위 조직에 들어간다
        만든다("QA002", "/scim/v2/Groups", """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"QA002","displayName":"테스트팀",
                 "members":[{"value":"%s","type":"User"}]}
                """.formatted(서버("choi")));
        client.patch().uri("/scim/v2/Groups/" + 서버("QA001")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"%s","type":"Group"}]}]}
                        """.formatted(서버("QA002")))
                .exchange().expectStatus().isNoContent();

        // then — 직속 소속
        assertThat(check("user:" + 서버("choi"), "member", "group:" + 서버("QA002"))).isTrue();
        // then — 상위 조직이 먼저 있었어도 하위 조직이 들어오는 때 child 엣지가 만들어져야 롤업이 성립한다.
        // 최소 스냅샷이 상위 조직을 못 보면 이 엣지는 영영 생기지 않고, 아무도 그것을 고쳐 주지 않는다
        assertThat(check("user:" + 서버("choi"), "member", "group:" + 서버("QA001"))).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("예약 아카이빙은 하루 한 번만 돈다 — 여러 대가 같은 시각에 불러도 기록과 스냅샷은 하나다(점검 M15)")
    void 예약_아카이빙은_하루_한_번이다() {
        // given
        long 전 = 아카이빙_기록_수();

        // when — 두 인스턴스의 스케줄러가 같은 날 부른 것과 같다(표지 저장소가 같은 테이블이다)
        scheduler.스냅샷아카이빙();
        scheduler.스냅샷아카이빙();

        // then — 하나는 돌고, 하나는 건너뛴다
        await().atMost(Duration.ofSeconds(30)).until(() -> 아카이빙_기록_수() == 전 + 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(아카이빙_기록_수()).isEqualTo(전 + 1));
    }

    private long 아카이빙_기록_수() {
        return runs.findRecent(100).collectList().block(Duration.ofSeconds(10)).stream()
                .filter(run -> run.trigger() == dev.starryeye.organization.core.model.SyncTrigger.ARCHIVE)
                .count();
    }
}
