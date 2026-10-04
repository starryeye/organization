package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
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
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.entryUUID;
import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.기준선을_읽는다;
import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.소속인가;
import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.이름_기반이면_삭제_가드에_걸리는_픽스처다;
import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.튜플을_건드리지_않고_성공했다;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 식별 속성({@code entryUUID})으로 DIT 디렉터리를 읽는 전 구간 — OU 를 개명해도 같은 id 라 삭제 가드에 걸리지 않는다
 * (설계 2026-10-04 §4.1·§8). groupOfNames 쪽은 {@link LdapImmutableIdEndToEndTest} 다.
 *
 * <p>전략은 컨텍스트가 시작할 때 정해지므로 클래스가 따로다. 테스트 프로필이 못박은 이름 기반 id({@code ou}/{@code uid})를
 * {@code entryUUID} 로 되돌린다.
 *
 * <p>DIT 은 계층이 DN 경로라 OU 를 개명하면 그 아래 직원의 DN 도 함께 바뀐다 — id 가 이름이었다면 OU 와 아래 직원의 소속이 모두 지워지고
 * 새로 쓰이는 일이다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapDitImmutableIdEndToEndTest {

    private static final String BASE_DN = "dc=example,dc=com";

    private static final String 전사DN = "ou=company," + BASE_DN;
    private static final String 본부DN = "ou=DEV001," + 전사DN;
    private static final String 백엔드팀DN = "ou=DEV002," + 본부DN;
    private static final String 프론트팀DN = "ou=DEV003," + 본부DN;

    private static final String 본부_직원 = "kim";
    /** 개명할 OU 의 직속 직원들. 기준선 튜플 15건 중 9건(child 1 + 직원 8)이 이 OU 것이라, 이름 기반 id 였다면 삭제 가드의 임계를 넘는다. */
    private static final List<String> 백엔드팀_직원들 = List.of("park", "lee", "choi", "jung", "kang", "cho", "yoon", "jang");
    private static final List<String> 프론트팀_직원들 = List.of("lim", "han", "oh");

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    static InMemoryDirectoryServer LDAP;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("immutable-dit", 0));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(ldif().getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("ldap.strategy", () -> "dit");
        // 프로필이 못박은 이름 기반 id 를 운영 기본값으로 되돌린다
        registry.add("ldap.dit.group-id-attribute", () -> "entryUUID");
        registry.add("ldap.dit.user-id-attribute", () -> "entryUUID");
        registry.add("ldap.dit.user-login-attribute", () -> "uid");
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired RelationTupleChecker checker;
    /** 앱이 쓰는 그 가드다 — 임계 비율·최소 기준선은 테스트 프로필 설정에서 온다. */
    @Autowired DeletionGuard deletionGuard;

    /**
     * 전사 아래 본부(DEV001, 직속 직원 1명) 아래 백엔드팀(DEV002, 직원 8명)과 프론트팀(DEV003, 직원 3명). 튜플은 child 3 + direct_member 12
     * = 15건이다. DEV002 는 {@code description} 이 없어 표시명이 DN 의 첫 RDN 값이다.
     */
    private static String ldif() {
        StringBuilder sb = new StringBuilder("""
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: %s
                objectClass: organizationalUnit
                ou: company
                description: 전사

                dn: %s
                objectClass: organizationalUnit
                ou: DEV001
                description: 개발본부

                dn: %s
                objectClass: organizationalUnit
                ou: DEV002

                dn: %s
                objectClass: organizationalUnit
                ou: DEV003
                description: 프론트팀

                """.formatted(전사DN, 본부DN, 백엔드팀DN, 프론트팀DN));
        직원들을_쓴다(sb, List.of(본부_직원), 본부DN);
        직원들을_쓴다(sb, 백엔드팀_직원들, 백엔드팀DN);
        직원들을_쓴다(sb, 프론트팀_직원들, 프론트팀DN);
        return sb.toString();
    }

    private static void 직원들을_쓴다(StringBuilder sb, List<String> 직원들, String 부모DN) {
        직원들.forEach(uid -> sb.append("""
                dn: %s
                objectClass: inetOrgPerson
                uid: %s
                cn: Person %s
                sn: %s
                displayName: 직원 %s
                mail: %s@example.com

                """.formatted(직원DN(uid, 부모DN), uid, uid, uid, uid, uid)));
    }

    private static String 직원DN(String uid, String 부모DN) {
        return "uid=" + uid + "," + 부모DN;
    }

    private static List<String> 직원DN들(List<String> 직원들, String 부모DN) {
        return 직원들.stream().map(uid -> 직원DN(uid, 부모DN)).toList();
    }

    /** ModifyDN 으로 개명한다. DIT 은 소속이 DN 경로라 참조를 고칠 일이 없다. 새 DN 을 돌려준다. */
    private static String 개명한다(String 옛DN, String 새RDN) throws LDAPException {
        LDAP.modifyDN(옛DN, 새RDN, true);
        return 새RDN + "," + 옛DN.substring(옛DN.indexOf(',') + 1);
    }

    @Test
    @Order(1)
    @DisplayName("DIT 도 기본값(entryUUID)으로 전체 동기화하면 OU·직원 id 가 entryUUID 이고 userName 은 uid 다")
    void DIT_가_entryUUID_로_동기화한다() {
        // given — 서버가 엔트리마다 만든 id
        String 전사_id = entryUUID(LDAP, 전사DN);
        String 본부_id = entryUUID(LDAP, 본부DN);
        String 백엔드팀_id = entryUUID(LDAP, 백엔드팀DN);
        String 프론트팀_id = entryUUID(LDAP, 프론트팀DN);
        String 김_id = entryUUID(LDAP, 직원DN(본부_직원, 본부DN));
        String 박_id = entryUUID(LDAP, 직원DN("park", 백엔드팀DN));
        Set<String> 직원_ids = Stream.of(
                        직원DN들(List.of(본부_직원), 본부DN), 직원DN들(백엔드팀_직원들, 백엔드팀DN), 직원DN들(프론트팀_직원들, 프론트팀DN))
                .flatMap(List::stream).map(dn -> entryUUID(LDAP, dn)).collect(Collectors.toSet());

        // when
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(15);

        // then — 상태의 키는 entryUUID 뿐이다
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.users()).containsOnlyKeys(직원_ids);
        assertThat(상태.groups()).containsOnlyKeys(전사_id, 본부_id, 백엔드팀_id, 프론트팀_id);
        assertThat(상태.users().get(박_id).userName()).isEqualTo("park");
        assertThat(상태.groups().get(백엔드팀_id).displayName()).isEqualTo("DEV002");
        // externalId 는 서버가 준 절대 DN 이다 — 루트 OU 도 마찬가지다
        assertThat(상태.groups().get(전사_id).externalId()).isEqualToIgnoringCase(전사DN);
        assertThat(상태.users().get(박_id).externalId()).isEqualToIgnoringCase(직원DN("park", 백엔드팀DN));

        // then — 권한은 entryUUID 로 성립하고, DN 경로로 이어진 계층이 롤업된다
        assertThat(소속인가(checker, 박_id, 백엔드팀_id)).isTrue();
        assertThat(소속인가(checker, 박_id, 본부_id)).isTrue();
        assertThat(소속인가(checker, 박_id, 전사_id)).isTrue();
        assertThat(소속인가(checker, 박_id, 프론트팀_id)).isFalse();
        assertThat(소속인가(checker, 김_id, 백엔드팀_id)).isFalse();
        assertThat(기준선을_읽는다(snapshots)).hasSize(15);
    }

    @Test
    @Order(2)
    @DisplayName("DIT — OU·uid 개명 뒤 동기화는 같은 id 의 이름 변경이다 — 삭제 가드에 걸리지 않고 권한이 그대로다")
    void OU_개명은_삭제_가드에_걸리지_않는다() throws Exception {
        // given — 첫 동기화가 남긴 기준선과 id
        String 백엔드팀_id = entryUUID(LDAP, 백엔드팀DN);
        String 박_id = entryUUID(LDAP, 직원DN("park", 백엔드팀DN));
        String 림_id = entryUUID(LDAP, 직원DN("lim", 프론트팀DN));
        String 전사_id = entryUUID(LDAP, 전사DN);
        String 프론트팀_id = entryUUID(LDAP, 프론트팀DN);
        var 개명_전 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(개명_전).as("상태가 비어 있다 — 첫 동기화(Order 1)가 먼저 성공해야 한다").isNotNull();
        Set<RelationTuple> 기준선 = 기준선을_읽는다(snapshots);

        // given — 이름 기반 id 였다면 이 개명(OU DEV002 와 직원 lim)은 그 id 를 가진 튜플을 모두 지우고 새로 쓰는 일이다.
        // 지울 튜플이 기준선의 임계 비율을 넘어야 이 픽스처가 가드를 시험한다: 앱의 가드가 그 삭제를 중단시키는지 직접 묻는다
        이름_기반이면_삭제_가드에_걸리는_픽스처다(deletionGuard, 기준선, "OU DEV002 와 직원 lim",
                RelationTuple.groupRef(백엔드팀_id), RelationTuple.userRef(림_id));

        // when — OU 의 ou 와 직원 uid 를 ModifyDN 으로 개명하고 동기화한다
        String 새OU_DN = 개명한다(백엔드팀DN, "ou=BACKEND");
        String 새직원DN = 개명한다(직원DN("lim", 프론트팀DN), "uid=lim.renamed");
        var 동기화 = SyncJobClient.끝까지(client, "/admin/sync/full");

        // then — 가드에 걸리지 않고, 튜플은 하나도 쓰거나 지우지 않는다
        튜플을_건드리지_않고_성공했다(동기화);

        // then — 서버는 개명에서 entryUUID 를 유지했고(아래 직원 포함), OU·직원 id 는 그대로다
        assertThat(entryUUID(LDAP, 새OU_DN)).isEqualTo(백엔드팀_id);
        assertThat(entryUUID(LDAP, 직원DN("park", 새OU_DN))).isEqualTo(박_id);
        assertThat(entryUUID(LDAP, 새직원DN)).isEqualTo(림_id);
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.groups()).containsOnlyKeys(개명_전.groups().keySet());
        assertThat(상태.users()).containsOnlyKeys(개명_전.users().keySet());

        // then — 바뀐 것은 이름뿐이다: 표시명(RDN 값)·externalId(DN, 아래 직원 포함)·userName
        var OU = 상태.groups().get(백엔드팀_id);
        assertThat(OU.displayName()).isEqualTo("BACKEND");
        assertThat(OU.externalId()).isEqualToIgnoringCase(새OU_DN);
        assertThat(상태.users().get(박_id).externalId()).isEqualToIgnoringCase(직원DN("park", 새OU_DN));
        var 직원 = 상태.users().get(림_id);
        assertThat(직원.userName()).isEqualTo("lim.renamed");
        assertThat(직원.externalId()).isEqualToIgnoringCase(새직원DN);

        // then — 권한이 그대로다: 개명한 OU 직속 직원의 member Check 와 롤업, 그리고 튜플 스냅샷
        assertThat(소속인가(checker, 박_id, 백엔드팀_id)).isTrue();
        assertThat(소속인가(checker, 박_id, 전사_id)).isTrue();
        assertThat(소속인가(checker, 림_id, 프론트팀_id)).isTrue();
        assertThat(소속인가(checker, 박_id, 프론트팀_id)).isFalse();
        assertThat(기준선을_읽는다(snapshots)).isEqualTo(기준선);
    }
}
