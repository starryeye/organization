package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupOfNamesStrategyTest extends EmbeddedLdapSupport {

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
                cn: Kim Chulsoo
                sn: Kim
                displayName: 김철수
                mail: kim@example.com

                dn: uid=lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: lee
                cn: Lee Younghee
                sn: Lee
                displayName: 이영희
                mail: lee@example.com

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
                member: uid=lee,ou=people,dc=example,dc=com
                member: uid=ghost,ou=people,dc=example,dc=com
                """;
    }

    private LdapProperties 기본설정() {
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

    @Test
    @DisplayName("직원 엔트리를 읽어 직원 아이디와 표시명, 이메일을 채운다")
    void 직원을_읽는다() {
        // given
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim", "lee", "park");
        var kim = snapshot.users().get("kim");
        assertThat(kim.displayName()).isEqualTo("김철수");
        assertThat(kim.email()).isEqualTo("kim@example.com");
        assertThat(kim.active()).isTrue();
        assertThat(kim.externalId()).contains("uid=kim");
    }

    @Test
    @DisplayName("조직코드는 cn 에서, 조직명은 description 에서 읽어 서로 분리된다")
    void 조직코드와_조직명을_분리해_읽는다() {
        // given
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups()).containsOnlyKeys("DEV001", "DEV002");
        assertThat(snapshot.groups().get("DEV001").displayName()).isEqualTo("개발본부");
        assertThat(snapshot.groups().get("DEV002").displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("member DN 이 사람이면 직원 멤버로, 그룹이면 하위 조직 멤버로 분류된다")
    void 멤버_DN을_사람과_그룹으로_분류한다() {
        // given
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("DEV001").members())
                .containsExactlyInAnyOrder(MemberRef.group("DEV002"), MemberRef.user("park"));
        assertThat(snapshot.groups().get("DEV002").members())
                .containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.user("lee"));
    }

    @Test
    @DisplayName("사람도 그룹도 아닌 member DN 은 건너뛰고 동기화를 완주한다")
    void 정체불명_멤버는_건너뛴다() {
        // given — DEV002 의 member 중 uid=ghost 는 실제 엔트리가 없다
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("DEV002").members())
                .doesNotContain(MemberRef.user("ghost"))
                .hasSize(2);
    }

    @Test
    @DisplayName("조직명 속성이 비어 있으면 조직코드를 표시명으로 대신 쓴다")
    void 조직명이_없으면_조직코드로_대체한다() {
        // given
        var properties = 기본설정();
        properties.getGroupOfNames().setGroupNameAttribute("businessCategory");
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("DEV001").displayName()).isEqualTo("DEV001");
    }

    @Test
    @DisplayName("페이지 크기보다 엔트리가 많아도 페이징으로 전부 읽는다")
    void 페이지_크기보다_많은_엔트리도_전부_읽는다() {
        // given — 페이지 크기를 1로 좁혀 유저 3명, 그룹 2개 모두 여러 페이지로 나뉘게 한다
        var properties = 기본설정();
        properties.setPageSize(1);
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 한 페이지만 읽었다면 일부만 남아 잘렸을 것이다
        assertThat(snapshot.users()).containsOnlyKeys("kim", "lee", "park");
        assertThat(snapshot.groups()).containsOnlyKeys("DEV001", "DEV002");
        assertThat(snapshot.groups().get("DEV002").members())
                .containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.user("lee"));
    }

    @Test
    @DisplayName("직원 아이디 속성을 사번으로 바꾸면 사번 기준으로 읽는다")
    void 직원_아이디_속성을_바꿀_수_있다() {
        // given — 이 LDIF 에는 employeeNumber 가 없으므로 uid 가 없는 상태를 흉내낸다
        var properties = 기본설정();
        properties.getGroupOfNames().setUserIdAttribute("cn");
        var strategy = new GroupOfNamesStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — cn 값에 공백이 있으므로 정규화되어 밑줄로 바뀐다
        assertThat(snapshot.users()).containsKey("Kim_Chulsoo");
    }
}
