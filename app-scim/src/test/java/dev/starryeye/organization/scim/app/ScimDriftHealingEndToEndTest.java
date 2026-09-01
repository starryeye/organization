package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어긋난 튜플이 다음 터치에 걷어내지는지 실제 컨테이너 위에서 확인한다 (설계 §8.3).
 *
 * <p>경합을 재현하는 대신 <b>경합이 남겼을 결과를 직접 심는다</b>. 타이밍에 기대지 않아
 * 흔들리지 않으면서, 설계가 막으려는 위험(퇴사자 권한 생존)을 그대로 못박는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimDriftHealingEndToEndTest {

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
    @Autowired StoreBootstrapper bootstrapper;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 스냅샷에 없는 튜플을 OpenFGA 에 직접 심는다. 동기화 경로를 거치지 않으므로 상태에도 없다. */
    private void 잔여튜플을_심는다(String user, String relation, String object) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                    new ClientTupleKey().user(user).relation(relation)._object(object)))).get();
        } catch (Exception e) {
            throw new IllegalStateException("튜플 심기 실패", e);
        }
    }

    @Test
    @DisplayName("경합이 남긴 퇴사자 튜플을 다음 SCIM 쓰기가 걷어낸다")
    void 어긋난_튜플이_치유된다() {
        // given — kim 을 만들고 DEV001 에 넣은 뒤 비활성으로 바꾼다
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":true}
                        """)
                .exchange().expectStatus().isCreated();

        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().isCreated();

        client.put().uri("/scim/v2/Users/kim").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":false}
                        """)
                .exchange().expectStatus().isOk();

        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();

        // given — 경합이 남겼을 튜플을 직접 심는다.
        // DynamoDB 에는 kim 이 DEV001 멤버로 남아 있고(비활성), OpenFGA 에만 튜플이 산다.
        잔여튜플을_심는다("user:kim", "direct_member", "group:DEV001");
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();

        // when — DEV001 을 아무렇게나 한 번 건드린다
        client.put().uri("/scim/v2/Groups/DEV001").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().isOk();

        // then — 상태 기준선이었다면 델타가 비어 그대로 남는다
        assertThat(check("user:kim", "member", "group:DEV001"))
                .as("비활성 직원의 잘못 남은 권한은 다음 터치에 사라져야 한다")
                .isFalse();
    }
}
