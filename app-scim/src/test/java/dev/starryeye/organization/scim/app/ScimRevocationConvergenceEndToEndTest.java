package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.List;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회수 수렴(설계 2026-10-20 §6.4)의 SCIM 끝단 — 하루 아카이빙이 비활성 직원에게 남은 줄을 지운다.
 *
 * <p>경합이 남겼을 결과를 <b>직접 심는다</b>({@link ScimDriftHealingEndToEndTest} 와 같은 방식). 타이밍에 기대지 않으면서, 설계가 막으려는 위험(퇴사자
 * 권한 생존)을 그대로 못박는다. 이 줄은 그 직원을 가리키는 다음 쓰기가 없으면 아무도 지우지 않는다 — 아카이빙이 그 자리를 맡는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimRevocationConvergenceEndToEndTest {

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

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired SnapshotArchiveUseCase archive;
    @Autowired MeterRegistry registry;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** SCIM 을 거치지 않고 OpenFGA 에 직접 쓴다. 상태에는 반영되지 않는다. */
    private void 잔여튜플을_심는다(String user, String relation, String object) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                    new ClientTupleKey().user(user).relation(relation)._object(object)))).get();
        } catch (Exception e) {
            throw new IllegalStateException("튜플 심기 실패", e);
        }
    }

    private double 게이지(String 이름, String... 태그) {
        return registry.get(이름).tags(태그).gauge().value();
    }

    @Test
    @DisplayName("비활성 직원에게 남은 줄을 아카이빙이 지운다")
    void 비활성_직원에게_남은_줄을_아카이빙이_지운다() {
        // given — 직원을 SCIM 으로 만들고 조직에 넣은 뒤 비활성으로 바꾼다. 아이디는 서버가 정하므로 응답에서 받는다
        String kim = 만든_아이디(client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"revoked-kim","displayName":"김철수","active":true}
                        """)
                .exchange());
        String 조직 = 만든_아이디(client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "displayName":"개발본부","members":[{"value":"%s","type":"User"}]}
                        """.formatted(kim))
                .exchange());
        assertThat(check("user:" + kim, "member", "group:" + 조직)).isTrue();

        client.patch().uri("/scim/v2/Users/" + kim).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """)
                .exchange().expectStatus().isOk();
        assertThat(check("user:" + kim, "member", "group:" + 조직)).isFalse();

        // given — 경합이 남겼을 줄을 직접 다시 쓴다. 상태는 비활성이라 이 줄은 있어선 안 된다(끝나지 않은 회수)
        잔여튜플을_심는다("user:" + kim, "direct_member", "group:" + 조직);
        assertThat(check("user:" + kim, "member", "group:" + 조직)).isTrue();
        long 전 = System.currentTimeMillis() / 1000;

        // when — 하루 아카이빙을 실행한다(예약 호출과 같은 빈 호출)
        var run = archive.execute().block();

        // then — 남은 줄이 지워진다. 아카이빙 기록은 성공이고, 어긋난 직원 한 명을 맞췄다고 적는다
        assertThat(check("user:" + kim, "member", "group:" + 조직)).isFalse();
        assertThat(check("user:" + kim, "direct_member", "group:" + 조직)).isFalse();
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("어긋난 직원 1명 다시 맞춤(실패 0명)");

        // then — 게이지는 비교 시점에 본 어긋남을 쥔다: 남은 줄 1건, 빠진 줄 0건. 관찰 시각도 이때다
        assertThat(게이지("scim.archive.drift", "kind", "extra")).isEqualTo(1.0);
        assertThat(게이지("scim.archive.drift", "kind", "missing")).isEqualTo(0.0);
        assertThat(게이지("scim.archive.drift.observed")).isGreaterThanOrEqualTo(전);

        // then — 다음 아카이빙은 어긋남이 없다: 게이지가 0 으로 내려오고 기록도 조용하다
        var 다음 = archive.execute().block();
        assertThat(다음.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(다음.message()).isNull();
        assertThat(게이지("scim.archive.drift", "kind", "extra")).isEqualTo(0.0);
    }
}
