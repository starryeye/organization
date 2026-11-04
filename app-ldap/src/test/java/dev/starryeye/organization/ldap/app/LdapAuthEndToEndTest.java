package dev.starryeye.organization.ldap.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import dev.starryeye.organization.security.Scopes;
import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app-ldap 이 리소스 서버로 묶였는지 실제 컨테이너 위에서 본다(설계 2026-10-28 §4, A2·A5·A8). 관리 API 의 401 은 problem details 이고,
 * 관리 API 로 건 회차에는 요청한 클라이언트가 남는다. 다른 시험은 모든 범위를 가진 기본 머리({@code test-client})로 지나간다.
 *
 * <p>헬스 세부는 이 클래스만 운영 값({@code when-authorized})으로 띄워 본다. 두 앱의 시험 yml 은 {@code always} 라 세부가 늘 보인다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=when-authorized")
class LdapAuthEndToEndTest {

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

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpassword");
        config.setListenerConfigs(EmbeddedLdapListener.리스너("auth"));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> EmbeddedLdapListener.접속_주소(LDAP));
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    /** 모든 범위를 가진 기본 머리({@code test-client})를 싣는 클라이언트. */
    @Autowired WebTestClient client;

    /** 기본 머리를 뺀 클라이언트 — 요청마다 머리를 지우는 것으로는 기본 머리가 빠지지 않는다. */
    private WebTestClient 토큰_없는;

    @BeforeEach
    void 토큰_없는_클라이언트를_만든다() {
        토큰_없는 = client.mutate().defaultHeaders(머리 -> 머리.remove(HttpHeaders.AUTHORIZATION)).build();
    }

    private int 회차_수() {
        JsonNode 목록 = client.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        return 목록.size();
    }

    @Test
    @DisplayName("토큰 없는 관리 API 는 problem details 401 이고, 회차를 만들지 않는다")
    void 토큰_없는_관리_API_는_problem_401_이다() {
        // given
        int 전 = 회차_수();

        // when
        토큰_없는.post().uri("/admin/sync/full").exchange()

                // then
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isEqualTo("인증이 필요하다");
        토큰_없는.get().uri("/admin/sync/runs").exchange()
                .expectStatus().isUnauthorized();
        assertThat(회차_수()).as("거절한 요청은 회차를 만들지 않는다").isEqualTo(전);
    }

    @Test
    @DisplayName("관리 API 로 건 회차의 요청자가 남는다 — 토큰의 sub 가 client:<sub> 로")
    void 관리_API_로_건_회차의_요청자가_남는다() {
        // when
        var 끝난_기록 = SyncJobClient.끝까지(client, "/admin/sync/full");

        // then
        끝난_기록.jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.requestedBy").isEqualTo("client:test-client");
    }

    @Test
    @DisplayName("헬스 세부는 actuator 범위가 있을 때만 보인다 — 토큰 없는 프로브와 다른 범위의 토큰은 상태만")
    void 헬스_세부는_actuator_범위가_있을_때만() {
        // given
        WebTestClient 읽기만 = client.mutate().defaultHeaders(머리 -> 머리.setBearerAuth(TestTokens.발급한다("limited-client", Scopes.ADMIN_READ))).build();
        WebTestClient 운영 = client.mutate().defaultHeaders(머리 -> 머리.setBearerAuth(TestTokens.발급한다("limited-client", Scopes.ACTUATOR))).build();

        // when, then — 토큰 없는 프로브와 인증은 됐지만 actuator 범위가 없는 토큰은 상태만
        for (WebTestClient 호출자 : new WebTestClient[]{토큰_없는, 읽기만}) {
            호출자.get().uri("/actuator/health").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("UP")
                    .jsonPath("$.components").doesNotExist();
        }
        // when, then — actuator 범위가 있으면 세부까지
        운영.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.openFga").exists();
    }
}
