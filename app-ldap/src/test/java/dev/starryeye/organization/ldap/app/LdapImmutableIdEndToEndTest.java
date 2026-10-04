package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 식별 속성({@code entryUUID})으로 groupOfNames 디렉터리를 읽는 전 구간 — 직원·조직 id 가 서버가 만든 {@code entryUUID} 이고,
 * 개명이 같은 id 의 이름 변경이라 삭제 가드에 걸리지 않는다(설계 2026-10-04 §4.1·§8).
 *
 * <p>테스트 프로필({@code application-test.yml})은 기존 e2e·규모 테스트가 이름을 id 로 기대해서 식별 속성을 {@code uid}/{@code cn} 으로
 * 못박아 둔다. 이 클래스는 그것을 {@code entryUUID} 로 되돌린다 — 운영 기본값과 같은 설정으로 도는 유일한 app-ldap e2e 다. DIT 전략은
 * 전략이 컨텍스트 시작 때 정해져서 {@link LdapDitImmutableIdEndToEndTest} 가 따로 본다.
 *
 * <p>순서에 의존한다({@link Order}). 첫 동기화가 기준선(튜플 스냅샷)을 만들고, 개명은 그 위에서 일어난다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapImmutableIdEndToEndTest {

    private static final String BASE_DN = "dc=example,dc=com";

    private static final String 본부 = "DEV001";
    private static final String 백엔드팀 = "DEV002";
    private static final String 프론트팀 = "DEV003";

    private static final String 본부_직원 = "kim";
    /** 개명할 조직 DEV002 의 직속 직원들. 기준선 튜플 14건 중 8건이 이 조직 것이라 지우면 가드(30%)를 넘는다. */
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
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("immutable", 0));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(ldif().getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        // 프로필이 못박은 이름 기반 id 를 운영 기본값으로 되돌린다
        registry.add("ldap.group-of-names.user-id-attribute", () -> "entryUUID");
        registry.add("ldap.group-of-names.group-id-attribute", () -> "entryUUID");
        registry.add("ldap.group-of-names.user-login-attribute", () -> "uid");
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired RelationTupleChecker checker;
    /** 앱이 쓰는 그 가드다 — 임계 비율·최소 기준선은 테스트 프로필(30%, 10건)에서 온다. */
    @Autowired DeletionGuard deletionGuard;

    /**
     * 본부(DEV001) 아래 백엔드팀(DEV002, 직원 8명)과 프론트팀(DEV003, 직원 3명), 본부 직속 1명. 튜플은 child 2 + direct_member 12 = 14건이다.
     * DEV002 는 {@code description} 이 없어 표시명이 DN 의 첫 RDN 값이다.
     */
    private static String ldif() {
        StringBuilder sb = new StringBuilder("""
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

                """);
        Stream.of(List.of(본부_직원), 백엔드팀_직원들, 프론트팀_직원들).flatMap(List::stream).forEach(uid -> sb.append("""
                dn: %s
                objectClass: inetOrgPerson
                uid: %s
                cn: Person %s
                sn: %s
                displayName: 직원 %s
                mail: %s@example.com

                """.formatted(직원DN(uid), uid, uid, uid, uid, uid)));
        sb.append("""
                dn: %s
                objectClass: groupOfNames
                cn: %s
                description: 개발본부
                member: %s
                member: %s
                member: %s

                """.formatted(조직DN(본부), 본부, 조직DN(백엔드팀), 조직DN(프론트팀), 직원DN(본부_직원)));
        sb.append("""
                dn: %s
                objectClass: groupOfNames
                cn: %s
                %s

                """.formatted(조직DN(백엔드팀), 백엔드팀, 멤버들(백엔드팀_직원들)));
        sb.append("""
                dn: %s
                objectClass: groupOfNames
                cn: %s
                description: 프론트팀
                %s

                """.formatted(조직DN(프론트팀), 프론트팀, 멤버들(프론트팀_직원들)));
        return sb.toString();
    }

    private static String 멤버들(List<String> 직원들) {
        return 직원들.stream().map(uid -> "member: " + 직원DN(uid)).collect(Collectors.joining("\n"));
    }

    private static String 직원DN(String uid) {
        return "uid=" + uid + ",ou=people," + BASE_DN;
    }

    private static String 조직DN(String cn) {
        return "cn=" + cn + ",ou=groups," + BASE_DN;
    }

    /** 서버가 엔트리마다 만들어 개명에도 유지하는 운영 속성. */
    private static String entryUUID(String dn) {
        try {
            var entry = LDAP.getEntry(dn, "entryUUID");
            assertThat(entry).as("엔트리 %s", dn).isNotNull();
            String 값 = entry.getAttributeValue("entryUUID");
            assertThat(값).as("%s 의 entryUUID", dn).isNotBlank();
            return 값;
        } catch (LDAPException e) {
            throw new IllegalStateException("entryUUID 조회 실패: " + dn, e);
        }
    }

    /**
     * ModifyDN 으로 개명하고, 그 DN 을 가리키던 {@code member} 값을 새 DN 으로 옮긴다. 임베디드 서버는 참조를 따라 고치지 않는다 —
     * 실제 디렉터리가 참조 무결성 기능(OpenLDAP refint 등)으로 하는 일을 여기서 한다. 새 DN 을 돌려준다.
     */
    private static String 개명한다(String 옛DN, String 새RDN) throws LDAPException {
        LDAP.modifyDN(옛DN, 새RDN, true);
        String 새DN = 새RDN + "," + 옛DN.substring(옛DN.indexOf(',') + 1);
        for (SearchResultEntry 참조 : LDAP.search(BASE_DN, SearchScope.SUB,
                Filter.createEqualityFilter("member", 옛DN)).getSearchEntries()) {
            LDAP.modify(참조.getDN(),
                    new Modification(ModificationType.DELETE, "member", 옛DN),
                    new Modification(ModificationType.ADD, "member", 새DN));
        }
        return 새DN;
    }

    private boolean 소속인가(String userId, String groupId) {
        return Boolean.TRUE.equals(checker.check(RelationTuple.member(userId, groupId)).block(Duration.ofSeconds(30)));
    }

    @Test
    @Order(1)
    @DisplayName("기본값(entryUUID)으로 전체 동기화하면 직원·조직 id 가 entryUUID 이고 userName 은 uid 다")
    void entryUUID_로_동기화한다() {
        // given — 서버가 엔트리마다 만든 id
        String 본부_id = entryUUID(조직DN(본부));
        String 백엔드팀_id = entryUUID(조직DN(백엔드팀));
        String 프론트팀_id = entryUUID(조직DN(프론트팀));
        String 김_id = entryUUID(직원DN(본부_직원));
        String 박_id = entryUUID(직원DN("park"));
        Set<String> 직원_ids = Stream.of(List.of(본부_직원), 백엔드팀_직원들, 프론트팀_직원들).flatMap(List::stream)
                .map(uid -> entryUUID(직원DN(uid))).collect(Collectors.toSet());

        // when
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(14);

        // then — 상태의 키는 entryUUID 뿐이고 이름(uid/cn)은 키로 남지 않는다
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.users()).containsOnlyKeys(직원_ids);
        assertThat(상태.groups()).containsOnlyKeys(본부_id, 백엔드팀_id, 프론트팀_id);
        // userName 은 로그인 속성(uid) 원본이고 id 가 아니다
        assertThat(상태.users().get(김_id).userName()).isEqualTo(본부_직원);
        assertThat(상태.users().get(김_id).displayName()).isEqualTo("직원 kim");
        assertThat(상태.users().get(박_id).userName()).isEqualTo("park");
        // 표시명: description 이 있으면 그것, 없으면 DN 의 첫 RDN 값
        assertThat(상태.groups().get(본부_id).displayName()).isEqualTo("개발본부");
        assertThat(상태.groups().get(백엔드팀_id).displayName()).isEqualTo(백엔드팀);

        // then — 권한은 entryUUID 로 성립하고, 롤업이 이어진다
        assertThat(소속인가(박_id, 백엔드팀_id)).isTrue();
        assertThat(소속인가(박_id, 본부_id)).isTrue();
        assertThat(소속인가(박_id, 프론트팀_id)).isFalse();
        assertThat(소속인가(김_id, 백엔드팀_id)).isFalse();
        // 이름은 id 가 아니므로 이름으로 묻는 권한은 없다
        assertThat(소속인가("park", 백엔드팀)).isFalse();
        assertThat(snapshots.findLatest().block(Duration.ofSeconds(30)).tuples()).hasSize(14);
    }

    @Test
    @Order(2)
    @DisplayName("OU·그룹·uid 개명 뒤 동기화는 같은 id 의 이름 변경이다 — 삭제 가드에 걸리지 않고 권한이 그대로다")
    void 개명은_삭제_가드에_걸리지_않는다() throws Exception {
        // given — 첫 동기화가 남긴 기준선과 id
        String 백엔드팀_id = entryUUID(조직DN(백엔드팀));
        String 박_id = entryUUID(직원DN("park"));
        String 림_id = entryUUID(직원DN("lim"));
        String 본부_id = entryUUID(조직DN(본부));
        String 프론트팀_id = entryUUID(조직DN(프론트팀));
        var 개명_전 = state.loadAll().block(Duration.ofSeconds(30));
        Set<RelationTuple> 기준선 = snapshots.findLatest().block(Duration.ofSeconds(30)).tuples();

        // given — 이름 기반 id 였다면 이 개명(조직 DEV002 와 직원 lim)은 그 id 를 가진 튜플을 모두 지우고 새로 쓰는 일이다.
        // 지울 튜플이 기준선의 임계 비율을 넘어야 이 픽스처가 가드를 시험한다: 앱의 가드가 그 삭제를 중단시키는지 직접 묻는다
        long 이름_기반이면_지울_수 = 기준선.stream()
                .filter(tuple -> tuple.mentions(RelationTuple.groupRef(백엔드팀_id)) || tuple.mentions(RelationTuple.userRef(림_id)))
                .count();
        assertThat(deletionGuard.evaluate((int) 이름_기반이면_지울_수, 기준선.size(), "기준 스냅샷").aborted())
                .as("이름 기반이면 지울 튜플 %d건 / 기준선 %d건 — 가드(임계 30%%, 최소 기준선 10건)를 넘는 픽스처여야 한다",
                        이름_기반이면_지울_수, 기준선.size())
                .isTrue();

        // when — 조직 cn 과 직원 uid 를 ModifyDN 으로 개명하고 동기화한다
        String 새조직DN = 개명한다(조직DN(백엔드팀), "cn=BACKEND");
        String 새직원DN = 개명한다(직원DN("lim"), "uid=lim.renamed");
        var 동기화 = SyncJobClient.끝까지(client, "/admin/sync/full");

        // then — 가드에 걸리지 않고, 튜플은 하나도 쓰거나 지우지 않는다
        동기화.jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");

        // then — 서버는 개명에서 entryUUID 를 유지했고, 조직·직원 id 는 그대로다(새 id 도, 사라진 id 도 없다)
        assertThat(entryUUID(새조직DN)).isEqualTo(백엔드팀_id);
        assertThat(entryUUID(새직원DN)).isEqualTo(림_id);
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.groups()).containsOnlyKeys(개명_전.groups().keySet());
        assertThat(상태.users()).containsOnlyKeys(개명_전.users().keySet());

        // then — 바뀐 것은 이름뿐이다: 표시명(RDN 값)·externalId(DN)·userName
        var 조직 = 상태.groups().get(백엔드팀_id);
        assertThat(조직.displayName()).isEqualTo("BACKEND");
        assertThat(조직.externalId()).isEqualToIgnoringCase(새조직DN);
        var 직원 = 상태.users().get(림_id);
        assertThat(직원.userName()).isEqualTo("lim.renamed");
        assertThat(직원.externalId()).isEqualToIgnoringCase(새직원DN);
        assertThat(직원.displayName()).isEqualTo("직원 lim");

        // then — 권한이 그대로다: 직속 직원의 member Check 와 롤업, 그리고 튜플 스냅샷
        assertThat(소속인가(박_id, 백엔드팀_id)).isTrue();
        assertThat(소속인가(박_id, 본부_id)).isTrue();
        assertThat(소속인가(림_id, 프론트팀_id)).isTrue();
        assertThat(소속인가(림_id, 본부_id)).isTrue();
        assertThat(소속인가(박_id, 프론트팀_id)).isFalse();
        assertThat(snapshots.findLatest().block(Duration.ofSeconds(30)).tuples()).isEqualTo(기준선);
    }
}
