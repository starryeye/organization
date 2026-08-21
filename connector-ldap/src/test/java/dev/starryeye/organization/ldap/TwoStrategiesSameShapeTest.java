package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 두 매핑 전략이 <b>같은 조직도를 서로 다른 방식으로 표현했을 때</b> 같은 튜플을 만들어내는지
 * 확인한다. 이것이 커넥터 추상화의 존재 이유다 — 이 불변식이 깨지면 동기화 파이프라인 후반부가
 * 전략을 구분해야 한다.
 *
 * <p>하나의 LDAP 서버에 같은 조직도를 두 모양으로 심는다. {@code ou=dit} 아래에는 트리 위치가
 * 소속을 뜻하는 DIT 구조를, {@code ou=gon-groups}/{@code ou=gon-people} 아래에는 {@code member}
 * 속성이 소속을 뜻하는 groupOfNames 구조를 둔다. 조직코드와 직원 아이디는 양쪽이 같은 값을 쓴다.
 *
 * <p><b>비교 대상은 {@link TupleMapper} 의 출력이지 원본 레코드가 아니다.</b> {@code externalId}
 * 는 DIT 가 상대 DN, groupOfNames 가 절대 DN 이라 값이 다르다 — 알려진 불일치이며 아무도 읽지
 * 않는다. 정말로 지켜져야 하는 계약은 "이후 로직이 전략을 구분하지 않아도 된다"이고, 그것을
 * 결정하는 것은 튜플이다.
 *
 * <p>DIT 는 트리 위치가 곧 소속이라 직원이 한 조직에만 속할 수 있다. 그래서 이 픽스처는 DIT 로
 * 표현 가능한 조직도만 담는다 — 겸직이 있으면 애초에 두 전략이 같은 것을 표현할 수 없다.
 */
class TwoStrategiesSameShapeTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=dit,dc=example,dc=com
                objectClass: organizationalUnit
                ou: dit

                dn: ou=company,ou=dit,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company
                description: 전사

                dn: ou=DEV001,ou=company,ou=dit,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001
                description: 개발본부

                dn: ou=DEV002,ou=DEV001,ou=company,ou=dit,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV002
                description: 백엔드팀

                dn: uid=choi,ou=DEV002,ou=DEV001,ou=company,ou=dit,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: choi
                cn: Choi Jiwoo
                sn: Choi
                displayName: 최지우
                mail: choi@example.com

                dn: uid=park,ou=DEV001,ou=company,ou=dit,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: park
                cn: Park Minsu
                sn: Park
                displayName: 박민수
                mail: park@example.com

                dn: ou=gon-people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: gon-people

                dn: ou=gon-groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: gon-groups

                dn: uid=choi,ou=gon-people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: choi
                cn: Choi Jiwoo
                sn: Choi
                displayName: 최지우
                mail: choi@example.com

                dn: uid=park,ou=gon-people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: park
                cn: Park Minsu
                sn: Park
                displayName: 박민수
                mail: park@example.com

                dn: cn=company,ou=gon-groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: company
                description: 전사
                member: cn=DEV001,ou=gon-groups,dc=example,dc=com

                dn: cn=DEV001,ou=gon-groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: cn=DEV002,ou=gon-groups,dc=example,dc=com
                member: uid=park,ou=gon-people,dc=example,dc=com

                dn: cn=DEV002,ou=gon-groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV002
                description: 백엔드팀
                member: uid=choi,ou=gon-people,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("같은 조직도를 DIT 와 groupOfNames 로 표현하면 완전히 같은 튜플이 나온다")
    void 두_전략은_같은_튜플을_만든다() {
        // given
        DirectorySnapshot dit = new DitStrategy(dit설정()).read(ldapTemplate);
        DirectorySnapshot gon = new GroupOfNamesStrategy(groupOfNames설정()).read(ldapTemplate);

        // when
        var ditTuples = TupleMapper.toTuples(dit).tuples();
        var gonTuples = TupleMapper.toTuples(gon).tuples();

        // then — 이후 로직이 전략을 구분하지 않아도 되는지가 여기서 결정된다
        assertThat(ditTuples).containsExactlyInAnyOrderElementsOf(gonTuples);
    }

    @Test
    @DisplayName("두 전략이 같은 조직코드와 직원 아이디를 만든다")
    void 두_전략은_같은_식별자를_만든다() {
        // given
        DirectorySnapshot dit = new DitStrategy(dit설정()).read(ldapTemplate);
        DirectorySnapshot gon = new GroupOfNamesStrategy(groupOfNames설정()).read(ldapTemplate);

        // when, then — 식별자가 갈리면 같은 사람·조직이 두 벌로 저장된다
        assertThat(dit.users().keySet()).isEqualTo(gon.users().keySet());
        assertThat(dit.groups().keySet()).isEqualTo(gon.groups().keySet());
    }

    @Test
    @DisplayName("두 전략이 같은 표시명을 만든다 — 조회 화면에 그대로 보이는 값이다")
    void 두_전략은_같은_표시명을_만든다() {
        // given
        DirectorySnapshot dit = new DitStrategy(dit설정()).read(ldapTemplate);
        DirectorySnapshot gon = new GroupOfNamesStrategy(groupOfNames설정()).read(ldapTemplate);

        // when, then
        dit.users().forEach((id, user) ->
                assertThat(user.displayName()).isEqualTo(gon.users().get(id).displayName()));
        dit.groups().forEach((code, group) ->
                assertThat(group.displayName()).isEqualTo(gon.groups().get(code).displayName()));
    }

    private LdapProperties dit설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company,ou=dit");
        d.setOrgUnitObjectClass("organizationalUnit");
        d.setGroupIdAttribute("ou");
        d.setGroupNameAttribute("description");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        d.setUserNameAttribute("displayName");
        d.setUserMailAttribute("mail");
        return properties;
    }

    private LdapProperties groupOfNames설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=gon-people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=gon-groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }
}
