package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.scim.ScimIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static dev.starryeye.organization.scim.app.CreatedIds.만든_아이디;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sync.id-from-external-id=true} 일 때 SCIM POST 의 id 가 {@code externalId} 에서 정해져, wipe 나 기록 유실 뒤 같은 본문을 다시 보내면 같은 id 가
 * 나오는지를 실제 컨테이너 위에서 끝까지 본다(설계 2026-11-05 §3.2·§3.3·§4).
 *
 * <p>순서에 의존한다({@link Order}) — 앞 테스트가 만든 직원·조직 위에서 뒤 테스트가 이어 간다.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureObservability(metrics = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "sync.id-from-external-id=true")
class ScimIdDurabilityEndToEndTest {

    /** {@code application-test.yml} 의 {@code dynamodb.table-name} 과 같아야 한다 */
    private static final String TABLE_NAME = "organization-scim-e2e";

    private static final String KIM_BODY = """
            {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
             "externalId":"00u-kim","userName":"kim@corp.com","displayName":"김철수","active":true}
            """;

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
    @Autowired DirectoryStateRepository state;

    private final String 기대_kim = ScimIds.fromExternalId().user("00u-kim");
    private final String 기대_team = ScimIds.fromExternalId().group("grp-team");

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    private boolean kim이_팀의_멤버다() {
        return check("user:" + 기대_kim, "member", "group:" + 기대_team);
    }

    private WebTestClient.ResponseSpec kim을_보낸다() {
        return client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue(KIM_BODY).exchange();
    }

    private WebTestClient.ResponseSpec team을_보낸다() {
        return client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON).bodyValue("""
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"grp-team","displayName":"전략팀","members":[{"value":"%s","type":"User"}]}
                """.formatted(기대_kim)).exchange();
    }

    @Test
    @Order(1)
    @DisplayName("켜면 externalId 에서 id 가 나온다")
    void 켜면_externalId_에서_id_가_나온다() {
        // given, when
        String kim = 만든_아이디(kim을_보낸다());
        String team = 만든_아이디(team을_보낸다());

        // then
        assertThat(kim).isEqualTo(기대_kim);
        assertThat(team).isEqualTo(기대_team);
        assertThat(kim이_팀의_멤버다()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("wipe 뒤 같은 본문으로 다시 보내면 같은 id 다")
    void wipe_뒤_같은_본문으로_다시_보내면_같은_id_다() {
        // given
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=wipe&confirm=" + TABLE_NAME)
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // when
        String kim = 만든_아이디(kim을_보낸다());
        String team = 만든_아이디(team을_보낸다());

        // then
        assertThat(kim).isEqualTo(기대_kim);
        assertThat(team).isEqualTo(기대_team);
        assertThat(kim이_팀의_멤버다()).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("기록을 잃은 뒤 다시 보내면 같은 id 이고 장부 줄이 이어진다")
    void 기록을_잃은_뒤_다시_보내면_같은_id_이고_장부_줄이_이어진다() {
        // given — 저장된 직원만 지운다. OpenFGA 의 direct_member(user:kim, group:TEAM) 줄은 남는다
        state.deleteUser(기대_kim).block();

        // when
        String kim = 만든_아이디(kim을_보낸다());
        client.patch().uri("/scim/v2/Groups/{id}", 기대_team).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]}
                        """.formatted(기대_kim))
                .exchange().expectStatus().isNoContent();

        // then
        assertThat(kim).isEqualTo(기대_kim);
        assertThat(kim이_팀의_멤버다()).isTrue();

        // when — 장부에서 다시 쌓아도
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=tuples")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then
        assertThat(kim이_팀의_멤버다()).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("같은 externalId 로 이름만 다른 직원은 409 다")
    void 같은_externalId_로_이름만_다른_직원은_409_다() {
        // given, when, then
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "externalId":"00u-kim","userName":"kim2@corp.com","displayName":"김철수","active":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
        client.get().uri("/scim/v2/Users/{id}", 기대_kim).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.userName").isEqualTo("kim@corp.com");
    }
}
