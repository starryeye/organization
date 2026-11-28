package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldif.LDIFReader;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한 사람 즉시 회수(설계 2026-11-28 §9)의 끝단 — 디렉터리에서 잠근 직원의 줄을 정각 회차를 기다리지 않고 거두고, 잠금을 풀면 다음 전체 동기화가
 * 다시 쓰는 것까지 실제 컨테이너 위에서 본다. 순서에 기댄다({@link Order}) — 한 줄기다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapImmediateRevocationEndToEndTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    static InMemoryDirectoryServer LDAP;

    static final String LDIF = """
            dn: dc=example,dc=com
            objectClass: top
            objectClass: domain
            dc: example

            dn: ou=people,dc=example,dc=com
            objectClass: organizationalUnit
            ou: people

            dn: ou=groups,dc=example,dc=com
            objectClass: organizationalUnit
            ou: groups

            dn: uid=kim,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: kim
            cn: Kim Chulsoo
            sn: Kim
            displayName: 김철수
            mail: kim@example.com

            dn: uid=park,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: park
            cn: Park Minsu
            sn: Park
            displayName: 박민수
            mail: park@example.com

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: cn=DEV002,ou=groups,dc=example,dc=com
            member: uid=park,ou=people,dc=example,dc=com

            dn: cn=DEV002,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV002
            description: 백엔드팀
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpassword");
        config.setListenerConfigs(EmbeddedLdapListener.리스너("immediate-revocation"));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.urls", () -> EmbeddedLdapListener.접속_주소(LDAP));
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

    private WebTestClient.ResponseSpec 즉시_회수를_건다(String employeeId) {
        return client.post().uri("/admin/sync/revocations")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"employeeId\":\"" + employeeId + "\"}")
                .exchange();
    }

    @Test
    @Order(1)
    @DisplayName("전체 동기화가 kim 의 줄을 Check 로 성립시킨다")
    void 전체_동기화가_줄을_성립시킨다() {
        // given, when
        SyncJobClient.끝까지(client, "/admin/sync/full")
                // then
                .jsonPath("$.status").isEqualTo("SUCCEEDED");
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("디렉터리에서 잠근 직원을 즉시 회수하면 SUCCEEDED 로 그 사람의 줄만 거두고 이력에 REVOCATION 으로 남는다")
    void 잠근_직원을_즉시_회수하면_줄이_거둬진다() throws Exception {
        // 깨뜨릴 변경: 끝점 결선·계정 상태 읽기·삭제 델타 중 하나라도 빠지면 Check 가 참으로 남거나 회차가 실패한다
        // given — kim 을 디렉터리에서 잠근다
        LDAP.modify("uid=kim,ou=people,dc=example,dc=com",
                new Modification(ModificationType.REPLACE, "pwdAccountLockedTime", "000001010000Z"));

        // when
        SyncRunResponse 시작 = 즉시_회수를_건다("kim")
                .expectStatus().isAccepted()
                .expectBody(SyncRunResponse.class).returnResult().getResponseBody();
        SyncRunResponse 끝 = SyncJobClient.기다린다(client, 시작.runId(), Duration.ofMinutes(2));

        // then
        assertThat(시작.trigger()).isEqualTo("REVOCATION");
        assertThat(끝.status()).isEqualTo("SUCCEEDED");
        assertThat(끝.trigger()).isEqualTo("REVOCATION");
        assertThat(끝.deletedCount()).as("kim 의 소속 수").isEqualTo(1);
        assertThat(check("user:kim", "member", "group:DEV002")).isFalse();
        assertThat(check("user:park", "member", "group:DEV001")).as("다른 사람의 줄은 그대로").isTrue();
        client.get().uri("/admin/employees/kim/history?limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.kind").isEqualTo("REVOCATION")
                .jsonPath("$.items[0].revoked[0]").isEqualTo("user:kim direct_member group:DEV002");
    }

    @Test
    @Order(3)
    @DisplayName("잠금을 풀고 전체 동기화를 하면 kim 의 줄을 다시 쓴다")
    void 잠금을_풀면_전체_동기화가_다시_쓴다() throws Exception {
        // given
        LDAP.modify("uid=kim,ou=people,dc=example,dc=com",
                new Modification(ModificationType.DELETE, "pwdAccountLockedTime"));

        // when, then
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").value(n -> assertThat((Integer) n).isGreaterThanOrEqualTo(1));
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("디렉터리에서 활성인 직원을 부르면 409 와 활성 문구이고 줄은 그대로다")
    void 활성_직원은_409다() {
        // 깨뜨릴 변경: 활성 검사를 빼면 활성 직원의 줄이 거둬지고 202 가 난다
        // given, when, then
        즉시_회수를_건다("park")
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("디렉터리에서 활성인 직원은 거두지 않습니다 — 디렉터리에서 먼저 막으세요");
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }
}
