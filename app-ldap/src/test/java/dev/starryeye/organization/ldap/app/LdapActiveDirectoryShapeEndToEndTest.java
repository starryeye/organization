package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.ActiveDirectorySids;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Base64;

import static dev.starryeye.organization.ldap.app.ImmutableIdEndToEndSupport.소속인가;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Active Directory 모양의 디렉터리를 groupOfNames 전략으로 읽는 전 구간(설계 2026-10-05 §3). 세 가지를 함께 본다.
 * <ul>
 *   <li>사람 필터({@code (&(objectCategory=person)(objectClass=user))})가 컴퓨터 계정을 직원에서 뺀다(점검 M12) — AD 에서 컴퓨터는
 *       {@code objectClass=user} 이기도 해서 {@code objectClass} 만으로는 사람과 갈리지 않는다.
 *   <li>기본 그룹 소속이 권한이 된다(점검 M10) — kim 은 {@code member} 에 없고 {@code primaryGroupID} 가 DEV 그룹의 RID 일 뿐이다.
 *   <li>{@code member} 에 컴퓨터가 있어도 직원이 되지 않는다 — 사람도 그룹도 아닌 값이라 건너뛴다.
 * </ul>
 *
 * <p>테스트 프로필의 이름 기반 id({@code uid}/{@code cn})를 그대로 쓰고, 두 필터만 AD 모양으로 바꾼다. 그룹의 {@code objectSid} 는
 * 픽스처({@link ActiveDirectorySids})가 만든 이진 값을 LDIF 의 base64 로 싣는다 — 앱의 컨텍스트 소스가 이진으로 읽는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapActiveDirectoryShapeEndToEndTest {

    private static final String BASE_DN = "dc=example,dc=com";

    private static final String 그룹 = "DEV";
    /** DEV 그룹의 RID. kim 의 {@code primaryGroupID} 가 이것이다. */
    private static final long 그룹_RID = 1105;
    /** 어느 그룹도 읽지 않은 RID — AD 의 Domain Users(513). lee 의 기본 그룹이지만 읽은 그룹이 아니어서 소속을 더하지 않는다. */
    private static final long 읽지_않은_그룹_RID = 513;

    private static final String 김 = "kim";
    private static final String 이 = "lee";
    private static final String 컴퓨터 = "DEV-PC01$";

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    static InMemoryDirectoryServer LDAP;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("active-directory-shape", 0));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(ldif().getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        // AD 의 사람 필터와 그룹 필터 — 프로필은 inetOrgPerson/groupOfNames 라서 이 디렉터리를 읽지 못한다
        registry.add("ldap.group-of-names.user-filter", () -> "(&(objectCategory=person)(objectClass=user))");
        registry.add("ldap.group-of-names.group-filter", () -> "(objectClass=group)");
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;

    /**
     * 사람 둘(kim, lee)과 컴퓨터 하나, 그룹 DEV 하나. 컴퓨터는 사람과 같은 OU 에 있어 검색 범위 밖이라 빠지는 것이 아니고 {@code objectClass} 도
     * {@code user} 다 — 필터의 {@code objectCategory} 만이 가른다. DEV 의 {@code member} 는 lee 와 컴퓨터뿐이고, kim 은 {@code primaryGroupID} 로만 속한다.
     *
     * <p>임베디드 서버는 스키마를 읽지 않아 {@code objectCategory} 를 적힌 그대로 비교한다. 실제 AD 는 이 값을 스키마 클래스의 DN 으로 저장하고
     * 필터의 짧은 이름({@code person})을 풀어 맞춘다 — 여기서는 짧은 이름을 그대로 저장해 같은 필터가 같은 결과를 내게 한다.
     */
    private static String ldif() {
        String 그룹_SID = Base64.getEncoder().encodeToString(ActiveDirectorySids.도메인_RID(그룹_RID));
        return """
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

                %s
                %s
                dn: %s
                objectClass: top
                objectClass: person
                objectClass: organizationalPerson
                objectClass: user
                objectClass: computer
                objectCategory: computer
                uid: %s
                cn: DEV-PC01
                sn: DEV-PC01

                dn: %s
                objectClass: top
                objectClass: group
                cn: %s
                objectSid:: %s
                member: %s
                member: %s

                """.formatted(
                사람(김, 그룹_RID), 사람(이, 읽지_않은_그룹_RID),
                직원DN(컴퓨터), 컴퓨터,
                그룹DN(그룹), 그룹, 그룹_SID, 직원DN(이), 직원DN(컴퓨터));
    }

    /** AD 의 사용자 엔트리 모양 — {@code objectClass=user}, {@code objectCategory=person}, {@code primaryGroupID}. 뒤에 빈 줄이 붙는다. */
    private static String 사람(String uid, long 기본그룹_RID) {
        return """
                dn: %s
                objectClass: top
                objectClass: person
                objectClass: organizationalPerson
                objectClass: user
                objectCategory: person
                uid: %s
                cn: Person %s
                sn: %s
                displayName: 직원 %s
                mail: %s@example.com
                primaryGroupID: %d
                """.formatted(직원DN(uid), uid, uid, uid, uid, uid, 기본그룹_RID);
    }

    private static String 직원DN(String uid) {
        return "uid=" + uid + ",ou=people," + BASE_DN;
    }

    private static String 그룹DN(String cn) {
        return "cn=" + cn + ",ou=groups," + BASE_DN;
    }

    @Test
    @DisplayName("AD 모양 디렉터리: 사람 필터로 컴퓨터 계정을 빼고, 기본 그룹 소속을 권한으로 반영한다(점검 M12·M10)")
    void AD_모양_디렉터리를_동기화한다() {
        // given — 위 픽스처: member 는 lee 와 컴퓨터, kim 은 primaryGroupID 로만 DEV 에 속한다

        // when
        var 동기화 = SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 직원은 사람 둘뿐이다: 컴퓨터는 objectClass=user 여도 objectCategory 가 computer 라 뺀다(M12)
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.users()).containsOnlyKeys(김, 이);
        assertThat(상태.groups()).containsOnlyKeys(그룹);

        // then — DEV 의 멤버는 member 의 lee 와 기본 그룹의 kim 이다(M10). member 의 컴퓨터는 직원이 아니라 건너뛴다
        assertThat(상태.groups().get(그룹).members())
                .containsExactlyInAnyOrder(MemberRef.user(김), MemberRef.user(이));

        // then — 권한이 된다: member 로 속한 lee 와 기본 그룹으로만 속한 kim 모두
        assertThat(소속인가(checker, 김, 그룹)).isTrue();
        assertThat(소속인가(checker, 이, 그룹)).isTrue();

        // then — 쓴 튜플은 DEV 의 직속 직원 둘뿐이다
        동기화.jsonPath("$.writtenCount").isEqualTo(2);
    }
}
