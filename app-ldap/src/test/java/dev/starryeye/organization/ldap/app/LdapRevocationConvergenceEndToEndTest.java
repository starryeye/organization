package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldif.LDIFReader;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회수 수렴(설계 2026-10-20)의 LDAP 끝단 — 기준선 없는 첫 동기화의 장부 맞추기(§4)와 긴 {@code description}(§7)를 실제 컨테이너 위에서 본다.
 *
 * <p>순서에 기댄다({@link Order}). 첫 시험은 <b>이 앱의 첫 동기화</b>여야 한다 — 새 테이블이라 스냅샷 포인터가 없고, 그 상태가 시험의 전제다.
 * 둘째 시험은 그 위에서 상태를 두 번 갈아 끼운다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapRevocationConvergenceEndToEndTest {

    /** 설계 §4 의 회차 메모 — 한 글자도 다르지 않게 쓴다. */
    private static final String 기준선_없음 = "기준선 없음(첫 회차 또는 새 테이블) — 장부를 훑어 맞춤";

    /** 400자지만 1200바이트 — 색인 정렬키 한도(1024)를 넘는다. */
    private static final String 긴_설명 = "가".repeat(400);

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
        config.setListenerConfigs(EmbeddedLdapListener.리스너("revocation-convergence"));
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

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired TupleSnapshotRepository snapshots;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 목표 밖의 튜플을 OpenFGA 에 직접 심는다. 동기화 경로를 거치지 않으므로 스냅샷에도 상태에도 없다. */
    private void 잔여튜플을_심는다(String user, String relation, String object) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                    new ClientTupleKey().user(user).relation(relation)._object(object)))).get();
        } catch (Exception e) {
            throw new IllegalStateException("잔여 튜플 심기 실패", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("빈 테이블과 기존 store 의 첫 동기화가 목표 밖의 줄을 지운다")
    void 빈_테이블과_기존_store_의_첫_동기화가_목표_밖의_줄을_지운다() {
        // given — 새 테이블(스냅샷 포인터 없음)과 이미 줄이 들어 있는 store. 목표에 없는 줄이 하나 있다
        assertThat(snapshots.findLatest().block()).as("이 시험의 전제 — 아직 어떤 회차도 돌지 않았다").isNull();
        잔여튜플을_심는다("user:ghost", "direct_member", "group:DEV001");
        assertThat(check("user:ghost", "member", "group:DEV001")).isTrue();

        // when
        SyncJobClient.끝까지(client, "/admin/sync/full")
                // then — 회차는 성공하고, 기준선이 없어 장부를 훑어 맞췄다는 메모가 남는다
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("MANUAL")
                .jsonPath("$.writtenCount").isEqualTo(3)
                .jsonPath("$.deletedCount").isEqualTo(1)
                .jsonPath("$.message").value(message -> assertThat((String) message).contains(기준선_없음));

        // then — 목표 밖의 줄은 사라지고, 목표 안의 줄은 성립한다
        assertThat(check("user:ghost", "member", "group:DEV001")).isFalse();
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
        // 개수까지 본다 — 조직도가 요구하는 튜플은 정확히 3개다
        assertThat(snapshots.findLatest().block().tuples()).hasSize(3);

        // then — 회차 종류는 동기화 그대로라, 첫 적재의 부여와 지운 줄이 직원별 이력에 남는다
        client.get().uri("/admin/employees/kim/history?limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.kind").isEqualTo("SYNC")
                .jsonPath("$.items[0].granted[0]").isEqualTo("user:kim direct_member group:DEV002");
        client.get().uri("/admin/employees/ghost/history").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.kind").isEqualTo("SYNC")
                .jsonPath("$.items[0].revoked[0]").isEqualTo("user:ghost direct_member group:DEV001");
    }

    @Test
    @Order(2)
    @DisplayName("긴 description 조직도 동기화가 두 번 모두 성공한다")
    void 긴_description_조직도_동기화가_두_번_모두_성공한다() throws Exception {
        // given — description(= 조직 표시명)이 1200바이트인 조직을 LDAP 에 더한다
        assertThat(긴_설명.getBytes(StandardCharsets.UTF_8)).hasSize(1200);
        LDAP.add(new Entry("cn=LONGDESC,ou=groups,dc=example,dc=com",
                new Attribute("objectClass", "groupOfNames"),
                new Attribute("cn", "LONGDESC"),
                new Attribute("description", 긴_설명),
                new Attribute("member", "uid=kim,ou=people,dc=example,dc=com")));

        // when, then — 첫 회차는 현재상태를 갈아 끼운다. 정렬키를 자르지 않았다면 여기서 상태 교체가 실패하던 자리다
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // when, then — 변경이 없는 다음 회차도 성공한다. 매 회차 같은 긴 값을 다시 비교한다
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 조직 상세가 200 이고 표시명은 잘리지 않은 원래 값이다
        client.get().uri("/admin/organizations/LONGDESC").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orgCode").isEqualTo("LONGDESC")
                .jsonPath("$.displayName").isEqualTo(긴_설명);
        assertThat(check("user:kim", "member", "group:LONGDESC")).isTrue();
    }
}
