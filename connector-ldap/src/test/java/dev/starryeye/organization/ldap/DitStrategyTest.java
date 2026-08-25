package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DitStrategyTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company
                description: 전사

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001
                description: 개발본부

                dn: ou=DEV002,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV002
                description: 백엔드팀

                dn: ou=OPS001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: OPS001

                dn: uid=choi,ou=DEV002,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: choi
                cn: Choi Jiwoo
                sn: Choi
                displayName: 최지우
                mail: choi@example.com

                dn: uid=park,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: park
                cn: Park Minsu
                sn: Park
                displayName: 박민수
                mail: park@example.com
                """;
    }

    private LdapProperties 기본설정() {
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
    @DisplayName("루트 아래의 ou 트리를 모두 조직으로 읽는다")
    void ou_트리를_조직으로_읽는다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups()).containsOnlyKeys("company", "DEV001", "DEV002", "OPS001");
    }

    @Test
    @DisplayName("dn 경로에서 상위 조직을 도출해 하위 조직 멤버로 등록한다")
    void dn_경로에서_조직_계층을_도출한다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("company").members())
                .contains(MemberRef.group("DEV001"), MemberRef.group("OPS001"));
        assertThat(snapshot.groups().get("DEV001").members())
                .contains(MemberRef.group("DEV002"));
    }

    @Test
    @DisplayName("직원은 dn 상의 부모 조직 하나에만 속한다")
    void 직원은_부모_조직_하나에만_속한다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("DEV002").members()).contains(MemberRef.user("choi"));
        assertThat(snapshot.groups().get("DEV001").members())
                .contains(MemberRef.user("park"))
                .doesNotContain(MemberRef.user("choi"));
    }

    @Test
    @DisplayName("조직명은 description 에서 읽고 없으면 조직코드로 대체한다")
    void 조직명이_없으면_조직코드로_대체한다() {
        // given — OPS001 에는 description 이 없다
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.groups().get("DEV001").displayName()).isEqualTo("개발본부");
        assertThat(snapshot.groups().get("OPS001").displayName()).isEqualTo("OPS001");
    }

    @Test
    @DisplayName("직원 정보는 groupOfNames 전략과 같은 형태로 채워진다")
    void 직원_정보를_읽는다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("choi", "park");
        assertThat(snapshot.users().get("choi").displayName()).isEqualTo("최지우");
        assertThat(snapshot.users().get("choi").email()).isEqualTo("choi@example.com");
        assertThat(snapshot.users().get("choi").active()).isTrue();
    }

    @Test
    @DisplayName("페이지 크기보다 엔트리가 많아도 페이징으로 전부 읽는다")
    void 페이지_크기보다_많은_엔트리도_전부_읽는다() {
        // given — 페이지 크기를 1로 좁혀 조직 4개, 유저 2명 모두 여러 페이지로 나뉘게 한다
        var properties = 기본설정();
        properties.setPageSize(1);
        var strategy = new DitStrategy(properties);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 한 페이지만 읽었다면 일부만 남아 잘렸을 것이다
        assertThat(snapshot.groups()).containsOnlyKeys("company", "DEV001", "DEV002", "OPS001");
        assertThat(snapshot.users()).containsOnlyKeys("choi", "park");
    }

    /**
     * 두 전략이 정말로 같은 튜플을 만드는지는 {@link TwoStrategiesSameShapeTest} 가 확인한다 —
     * 이 테스트는 DIT 하나만 실행하므로 그 비교를 할 수 없다.
     */
    @Test
    @DisplayName("DIT 가 만든 스냅샷을 TupleMapper 가 그대로 소화한다")
    void DIT_스냅샷은_TupleMapper가_소화한다() {
        // given
        var strategy = new DitStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 트리 위치로 읽은 소속이 튜플의 방향(하위 -> 상위)으로 옮겨졌는지 본다
        var result = TupleMapper.toTuples(snapshot);
        assertThat(result.tuples()).contains(
                RelationTuple.directMember("choi", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"),
                RelationTuple.child("DEV001", "company"));
    }
}
