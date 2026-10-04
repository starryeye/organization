package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색이 쓰는 속성만 요청한다(설계 2026-10-04 §4.3, 점검 P7). 요청 속성을 비워 두면 서버가 모든 사용자 속성을 보내고,
 * 운영 속성(entryUUID)은 이름을 대야만 온다.
 *
 * <p>서버가 실제로 받은 검색 요청을 인터셉터로 잡아 속성 목록을 본다. 직원 엔트리에는 어느 전략도 쓰지 않는
 * {@code thumbnailPhoto} 를 둬서, "필요한 것만" 이 이름으로 가려지게 한다. AD 범위 읽기({@code member;range=…})는
 * 임베디드 서버가 범위를 자르지 않아 이 시나리오에 나오지 않는다 — 그 요청은 원래 속성 하나만 이름을 대 묻는다.
 */
class ReturningAttributesTest extends EmbeddedLdapSupport {

    private static final List<String> 직원_계정_상태_이름 = List.of(
            "userAccountControl", "accountExpires", "sn", "givenName", "middleName", "generationQualifier");

    private final List<검색요청> 요청 = new CopyOnWriteArrayList<>();

    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                요청.add(new 검색요청(
                        request.getRequest().getFilter().toString(),
                        List.copyOf(request.getRequest().getAttributeList())));
            }
        });
    }

    @Override
    protected String ldif() {
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

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                sAMAccountName: kim.cs
                employeeNumber: 1001
                cn: Kim Chulsoo
                sn: Kim
                displayName: 김철수
                title: 사원
                mail: kim@example.com
                thumbnailPhoto: c2VjcmV0

                dn: uid=park,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: park
                sAMAccountName: park.ms
                employeeNumber: 1002
                cn: Park Minsu
                sn: Park
                displayName: 박민수
                title: 대리
                mail: park@example.com
                thumbnailPhoto: c2VjcmV0

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                businessCategory: 개발
                member: uid=kim,ou=people,dc=example,dc=com
                member: uid=park,ou=people,dc=example,dc=com

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company
                description: 전사
                businessCategory: 본사

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001
                description: 개발본부
                businessCategory: 개발

                dn: uid=choi,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: choi
                sAMAccountName: choi.jw
                employeeNumber: 2001
                cn: Choi Jiwoo
                sn: Choi
                displayName: 최지우
                title: 주임
                mail: choi@example.com
                thumbnailPhoto: c2VjcmV0
                """;
    }

    private LdapProperties groupOfNames설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }

    private LdapProperties dit설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitObjectClass("organizationalUnit");
        d.setGroupIdAttribute("ou");
        d.setGroupNameAttribute("description");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        d.setUserNameAttribute("displayName");
        d.setUserMailAttribute("mail");
        return properties;
    }

    @Test
    @DisplayName("groupOfNames 의 직원·그룹 검색이 쓰는 속성만 요청한다 — 모든 속성(빈 목록)을 요청하지 않는다")
    void groupOfNames_는_필요한_속성만_요청한다() {
        // given
        var strategy = new GroupOfNamesStrategy(groupOfNames설정());

        // when
        strategy.read(ldapTemplate);

        // then
        assertThat(요청).isNotEmpty().allSatisfy(검색 -> assertThat(검색.속성()).isNotEmpty());
        assertThat(요청한_속성("inetOrgPerson"))
                .contains("uid", "displayName", "mail", "cn")
                .containsAll(직원_계정_상태_이름)
                .doesNotContain("thumbnailPhoto");
        assertThat(요청한_속성("groupOfNames"))
                .contains("cn", "description", "member")
                .doesNotContain("thumbnailPhoto");
    }

    @Test
    @DisplayName("DIT 의 OU·직원 검색도 쓰는 속성만 요청한다")
    void DIT_도_필요한_속성만_요청한다() {
        // given
        var strategy = new DitStrategy(dit설정());

        // when
        strategy.read(ldapTemplate);

        // then
        assertThat(요청).isNotEmpty().allSatisfy(검색 -> assertThat(검색.속성()).isNotEmpty());
        assertThat(요청한_속성("inetOrgPerson"))
                .contains("uid", "displayName", "mail", "cn")
                .containsAll(직원_계정_상태_이름)
                .doesNotContain("thumbnailPhoto");
        assertThat(요청한_속성("organizationalUnit"))
                .contains("ou", "description")
                .doesNotContain("mail", "userAccountControl", "thumbnailPhoto");
    }

    @Test
    @DisplayName("groupOfNames 는 설정한 속성 이름으로 요청한다 — uid·displayName·description 을 박아 두지 않는다")
    void groupOfNames_는_설정한_속성_이름으로_요청한다() {
        // given
        var properties = groupOfNames설정();
        properties.getGroupOfNames().setUserIdAttribute("employeeNumber");
        properties.getGroupOfNames().setUserLoginAttribute("sAMAccountName");
        properties.getGroupOfNames().setUserNameAttribute("title");
        properties.getGroupOfNames().setGroupNameAttribute("businessCategory");
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 로그인 속성도 설정한 이름으로 요청하고, 그 값이 userName 이 된다
        assertThat(snapshot.users()).containsOnlyKeys("1001", "1002");
        assertThat(snapshot.users().get("1001").userName()).isEqualTo("kim.cs");
        assertThat(요청한_속성("inetOrgPerson"))
                .contains("employeeNumber", "sAMAccountName", "title", "mail", "cn")
                .doesNotContain("uid", "displayName");
        assertThat(요청한_속성("groupOfNames"))
                .contains("cn", "businessCategory", "member")
                .doesNotContain("description");
    }

    @Test
    @DisplayName("DIT 는 설정한 속성 이름으로 요청한다 — uid·displayName·description 을 박아 두지 않는다")
    void DIT_는_설정한_속성_이름으로_요청한다() {
        // given
        var properties = dit설정();
        properties.getDit().setUserIdAttribute("employeeNumber");
        properties.getDit().setUserLoginAttribute("sAMAccountName");
        properties.getDit().setUserNameAttribute("title");
        properties.getDit().setGroupNameAttribute("businessCategory");
        var strategy = new DitStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 로그인 속성도 설정한 이름으로 요청하고, 그 값이 userName 이 된다
        assertThat(snapshot.users()).containsOnlyKeys("2001");
        assertThat(snapshot.users().get("2001").userName()).isEqualTo("choi.jw");
        assertThat(요청한_속성("inetOrgPerson"))
                .contains("employeeNumber", "sAMAccountName", "title", "mail", "cn")
                .doesNotContain("uid", "displayName");
        assertThat(요청한_속성("organizationalUnit"))
                .contains("ou", "businessCategory")
                .doesNotContain("description");
    }

    @Test
    @DisplayName("groupOfNames 기본 설정은 운영 속성 entryUUID 를 이름으로 요청한다 — 이름을 대야만 온다")
    void groupOfNames_기본값은_entryUUID_를_이름으로_요청한다() {
        // given — 식별 속성을 지정하지 않은 기본 설정
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 직원·그룹 모두 id 가 서버가 만든 entryUUID 라 이름을 대 요청한다
        assertThat(요청한_속성("inetOrgPerson")).contains("entryUUID", "uid").doesNotContain("thumbnailPhoto");
        assertThat(요청한_속성("groupOfNames")).contains("entryUUID", "description", "member").doesNotContain("cn");
        assertThat(snapshot.users()).hasSize(2).allSatisfy((id, 직원) -> assertThat(id).hasSize(36));
    }

    @Test
    @DisplayName("DIT 기본 설정도 운영 속성 entryUUID 를 이름으로 요청한다")
    void DIT_기본값도_entryUUID_를_이름으로_요청한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var strategy = new DitStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(요청한_속성("inetOrgPerson")).contains("entryUUID", "uid").doesNotContain("thumbnailPhoto");
        assertThat(요청한_속성("organizationalUnit")).contains("entryUUID", "description").doesNotContain("ou");
        assertThat(snapshot.users()).hasSize(1).allSatisfy((id, 직원) -> assertThat(id).hasSize(36));
    }

    @Test
    @DisplayName("페이징을 끄고(page-size 0) 단일 검색으로 읽어도 쓰는 속성만 요청한다")
    void 페이징을_꺼도_필요한_속성만_요청한다() {
        // given
        var properties = groupOfNames설정();
        properties.setPageSize(0);
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        strategy.read(ldapTemplate);

        // then
        assertThat(요청).isNotEmpty().allSatisfy(검색 -> assertThat(검색.속성()).isNotEmpty());
        assertThat(요청한_속성("inetOrgPerson")).contains("uid", "displayName", "mail").doesNotContain("thumbnailPhoto");
        assertThat(요청한_속성("groupOfNames")).contains("cn", "description", "member");
    }

    /** 그 objectClass 로 거른 검색 하나가 요청한 속성. 한 페이지에 다 담기는 시나리오라 검색은 하나다. */
    private List<String> 요청한_속성(String 객체클래스) {
        var 찾은것 = 요청.stream()
                .filter(검색 -> 검색.필터().contains("(objectClass=" + 객체클래스 + ")"))
                .toList();
        assertThat(찾은것).as("objectClass=%s 로 거른 검색", 객체클래스).hasSize(1);
        return 찾은것.get(0).속성();
    }

    /** 서버가 받은 검색 요청 하나. */
    private record 검색요청(String 필터, List<String> 속성) {
    }
}
