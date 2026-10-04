package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 재적재가 실제 인프라 위에서 동작하는지 확인한다.
 *
 * <p>이 스위트의 핵심은 {@link #튜플_재적재가_어긋남을_고치고_번호는_그대로다()} 다. 조회 API 가 어긋남을 드러내는 것까지는 이전
 * 사이클에서 확인했고, 여기서는 <b>그걸 실제로 고칠 수 있는지</b>, 그리고 고치는 동안 장부 번호가 바뀌지 않는지를 본다.
 *
 * <p>재적재는 202 로 곧바로 답하고 따로 돈다 — 결과는 {@link SyncJobClient} 로 기다려 본다(설계 2026-09-29 §4).
 *
 * <p>순서에 의존한다({@link Order}) — 앞 테스트가 만든 조직도 위에서 뒤 테스트가 어긋남을
 * 만들고 복구하고, 마지막에 전부 비운다.
 *
 * <p>아이디는 서버가 정한다(설계 2026-10-04 §3.1). 첫 테스트가 조직도를 만들며 받은 id({@link #홍길동}, {@link #백엔드팀})로 관리 API 를 부르고
 * 튜플을 직접 지우거나 심는다. 심은 찌꺼기도 서버 id 의 조직에 붙여야 재적재가 지우는지 시험할 수 있다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimRebuildEndToEndTest {

    /** {@code application-test.yml} 의 {@code dynamodb.table-name} 과 같아야 한다 */
    private static final String TABLE_NAME = "organization-scim-e2e";

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

    /** 첫 테스트가 만든 직원·조직의 서버 id. 테스트 인스턴스는 메서드마다 새로 만들어지므로 정적이다. */
    private static String 홍길동;
    private static String 백엔드팀;

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired OpenFgaProperties openFgaProperties;
    @Autowired MutationLock lock;

    /** 직원 하나와 그 직원이 든 조직 하나를 만들고, 응답의 서버 id 를 {@link #홍길동}·{@link #백엔드팀} 에 받아 둔다. */
    private void 조직도를_만든다() {
        홍길동 = 만든_아이디(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"gd.hong","displayName":"홍길동","active":true}""")
                .exchange());
        백엔드팀 = 만든_아이디(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"%s"}]}""".formatted(홍길동))
                .exchange());
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 이름으로 새로 찾은 장부 번호 — 이 앱이 캐시한 번호가 아니라 OpenFGA store 목록에서 찾는다. 같은 이름이 둘이면 오류다. */
    private String 장부_번호() {
        return new StoreBootstrapper(openFgaProperties).findExistingStore().block(Duration.ofSeconds(10));
    }

    @Test
    @Order(1)
    @DisplayName("튜플 재적재가 직접 지운 튜플을 되살리고 직접 심은 찌꺼기를 지우며, 장부 번호는 그대로다")
    void 튜플_재적재가_어긋남을_고치고_번호는_그대로다() throws Exception {
        // given — 조직도를 만든 뒤 OpenFGA 에서 튜플 하나를 직접 지우고, 누구도 기록하지 않은 줄을 직접 심는다
        조직도를_만든다();
        String 재적재_전_번호 = 장부_번호();
        bootstrapper.client().deleteTuples(List.of(
                new ClientTupleKeyWithoutCondition()
                        .user("user:" + 홍길동).relation("direct_member")._object("group:" + 백엔드팀))).get();
        bootstrapper.client().writeTuples(List.of(
                new ClientTupleKey().user("user:ghost").relation("direct_member")._object("group:" + 백엔드팀))).get();
        assertThat(check("user:ghost", "member", "group:" + 백엔드팀)).as("심은 찌꺼기가 서버 id 의 조직에서 통한다").isTrue();

        client.get().uri("/admin/employees/" + 홍길동)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(false);

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=tuples")
                .jsonPath("$.trigger").isEqualTo("REBUILD")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 어긋남이 사라졌다. 이것이 이 기능의 존재 이유다
        client.get().uri("/admin/employees/" + 홍길동)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
        // 찌꺼기는 지워졌다 — 장부를 훑어 조직도가 요구하지 않는 줄을 지운다(설계 §3.1)
        assertThat(check("user:ghost", "member", "group:" + 백엔드팀)).isFalse();
        // 장부 번호는 그대로다 — 다른 앱이 번호를 적어 둬도 된다(설계 §7)
        assertThat(장부_번호()).isEqualTo(재적재_전_번호);
    }

    @Test
    @Order(2)
    @DisplayName("튜플 재적재는 조직도를 건드리지 않는다")
    void 튜플_재적재는_조직도를_남긴다() {
        // when, then — 상태가 곧 진실이므로 재적재가 그것을 지우면 안 된다
        client.get().uri("/admin/employees/" + 홍길동)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("홍길동");
        client.get().uri("/admin/organizations/" + 백엔드팀)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("백엔드팀");
    }

    @Test
    @Order(3)
    @DisplayName("재적재는 SCIM 이력에 남는다")
    void 이력에_남는다() {
        // when, then
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].source").isEqualTo("SCIM")
                .jsonPath("$[0].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(4)
    @DisplayName("없는 실행 기록 번호는 404 다")
    void 없는_기록은_404다() {
        // when, then
        client.get().uri("/admin/sync/runs/missing-run")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @Order(5)
    @DisplayName("다른 작업이 락을 쥐고 있으면 재적재는 곧바로 409 이고 기록을 남기지 않는다")
    void 다른_작업이_락을_쥐면_409다() {
        // given — 다른 인스턴스의 SCIM 쓰기가 락을 쥔 순간
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block(Duration.ofSeconds(10));

        try {
            // when, then
            client.post().uri("/admin/sync/rebuild?mode=tuples")
                    .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        } finally {
            lock.release(lease).block(Duration.ofSeconds(10));
        }

        // then — 거절은 기록을 열지 않는다
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("wipe 는 confirm 이 테이블명과 다르면 400 이고 아무것도 지우지 않는다")
    void confirm이_틀리면_400이다() {
        // when, then — 불리언 플래그였다면 손가락이 미끄러져 조직도가 날아갔을 자리다
        client.post().uri("/admin/sync/rebuild?mode=wipe")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/sync/rebuild?mode=wipe&confirm=아무거나")
                .exchange().expectStatus().isBadRequest();

        // 조직도는 그대로다
        client.get().uri("/admin/employees/" + 홍길동)
                .exchange().expectStatus().isOk();
    }

    @Test
    @Order(7)
    @DisplayName("알 수 없는 mode 는 400 이다")
    void 알수없는_모드는_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=nuke")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @Order(8)
    @DisplayName("wipe 는 장부와 조직도를 전부 비우고 감사 이력은 남긴다")
    void wipe가_조직도를_비운다() {
        // given
        assertThat(check("user:" + 홍길동, "member", "group:" + 백엔드팀)).isTrue();

        // when — 테이블명을 그대로 적어야만 실행된다
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=wipe&confirm=" + TABLE_NAME)
                .jsonPath("$.trigger").isEqualTo("RESET")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 장부가 비었다
        assertThat(check("user:" + 홍길동, "member", "group:" + 백엔드팀)).isFalse();

        // then — 직원도 조직도 사라졌다
        client.get().uri("/admin/employees/" + 홍길동)
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/organizations/" + 백엔드팀)
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();

        // then — 사고 뒤에 무슨 일이 있었는지 볼 기록은 남아 있다
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].trigger").isEqualTo("RESET")
                .jsonPath("$[1].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(9)
    @DisplayName("wipe 뒤에도 SCIM 쓰기는 열려 있다 — IdP 재푸시를 받아야 하기 때문이다")
    void wipe_뒤에_쓰기가_열려있다() {
        // given, when — IdP 가 재프로비저닝으로 다시 밀어넣는 상황이다
        String 김철수 = 만든_아이디(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"cs.kim","displayName":"김철수","active":true}""")
                .exchange());

        // then — 락이 반납되지 않았다면 여기서 503 이 났을 것이다
        client.get().uri("/admin/employees/" + 김철수)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("김철수");
    }
}
