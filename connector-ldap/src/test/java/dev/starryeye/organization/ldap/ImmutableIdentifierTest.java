package dev.starryeye.organization.ldap;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapIdentifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 불변 식별자(설계 2026-10-04 §4.1·§4.2) — 식별 속성을 지정하지 않으면 {@code entryUUID} 를 id 로 읽는다. 서버가 엔트리마다 만들어
 * 이름이 바뀌어도 유지하는 값이라, 개명이 삭제 + 생성이 아니라 같은 id 의 이름 변경이 된다. 사람이 읽는 값({@code userName}, 조직 표시명)은
 * id 와 따로 읽는다.
 *
 * <p>공유 LDIF 에 groupOfNames 모양({@code ou=people}·{@code ou=groups})과 DIT 모양({@code ou=company})을 함께 둔다. 서로 다른
 * 검색 베이스 아래라 두 전략이 서로의 엔트리를 읽지 않는다.
 */
class ImmutableIdentifierTest extends EmbeddedLdapSupport {

    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String 김 = "uid=kim,ou=people," + BASE_DN;
    private static final String 홍 = "uid=hong gd,ou=people," + BASE_DN;
    private static final String 로그인_없는_직원 = "cn=Nologin Person,ou=people," + BASE_DN;
    private static final String 개발2팀 = "cn=DEV002,ou=groups," + BASE_DN;
    private static final String 이름_없는_그룹 = "cn=Platform Team,ou=groups," + BASE_DN;

    private static final String 전사 = "ou=company," + BASE_DN;
    private static final String 개발본부 = "ou=DEV001,ou=company," + BASE_DN;
    private static final String 운영본부 = "ou=OPS001,ou=company," + BASE_DN;
    private static final String 이름_없는_OU = "ou=Sales Team,ou=company," + BASE_DN;
    private static final String 최 = "uid=choi,ou=DEV001,ou=company," + BASE_DN;
    private static final String 한 = "uid=han gil,ou=DEV001,ou=company," + BASE_DN;

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

                dn: uid=hong gd,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: hong gd
                cn: Hong Gildong
                sn: Hong
                displayName: 홍길동

                dn: cn=Nologin Person,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: Nologin Person
                sn: Person
                displayName: 로그인 없음

                dn: cn=DEV002,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV002
                description: 백엔드팀
                member: uid=kim,ou=people,dc=example,dc=com
                member: uid=hong gd,ou=people,dc=example,dc=com

                dn: cn=Platform Team,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: Platform Team
                member: uid=kim,ou=people,dc=example,dc=com

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company
                description: 전사

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001
                description: 개발본부

                dn: uid=choi,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: choi
                cn: Choi Jiwoo
                sn: Choi
                displayName: 최지우

                dn: uid=han gil,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: han gil
                cn: Han Gil
                sn: Han
                displayName: 한길

                dn: ou=OPS001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: OPS001
                description: 운영본부

                dn: ou=Sales Team,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: Sales Team
                """;
    }

    /** 식별 속성·로그인 속성을 지정하지 않은 설정 — groupOfNames. */
    private LdapProperties 기본값_그대로() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        return properties;
    }

    /** 식별 속성·로그인 속성을 지정하지 않은 설정 — DIT. */
    private LdapProperties DIT_기본값_그대로() {
        var properties = 기본값_그대로();
        properties.setStrategy("dit");
        return properties;
    }

    /** 서버가 유지하는 운영 속성 {@code entryUUID}. */
    private String entryUUID(String dn) {
        try {
            var entry = server.getEntry(dn, "entryUUID");
            assertThat(entry).as("엔트리 %s", dn).isNotNull();
            String 값 = entry.getAttributeValue("entryUUID");
            assertThat(값).as("%s 의 entryUUID", dn).isNotBlank();
            return 값;
        } catch (LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("기본 설정에서 직원·조직 id 는 entryUUID 다 — groupOfNames")
    void 기본_id_는_entryUUID_다() {
        // given — 기본값(식별 속성을 지정하지 않음)
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 서버가 유지하는 entryUUID 와 같다
        String kim = entryUUID(김);
        assertThat(snapshot.users()).containsKey(kim);
        assertThat(snapshot.users().get(kim).id()).isEqualTo(kim);
        assertThat(snapshot.users().get(kim).userName()).isEqualTo("kim");
        assertThat(snapshot.users().get(kim).externalId()).isEqualTo(김);
        assertThat(snapshot.users()).doesNotContainKey("kim");

        String 개발2 = entryUUID(개발2팀);
        assertThat(snapshot.groups()).containsKey(개발2);
        assertThat(snapshot.groups().get(개발2).externalId()).isEqualTo(개발2팀);
        assertThat(snapshot.groups().get(개발2).members())
                .containsExactlyInAnyOrder(MemberRef.user(kim), MemberRef.user(entryUUID(홍)));
    }

    @Test
    @DisplayName("ou·cn·uid 를 바꿔도(ModifyDN) 같은 id 다 — 이름 변경이지 삭제+생성이 아니다")
    void 개명은_같은_id_다() throws LDAPException {
        // given
        String 전 = entryUUID(개발2팀);

        // when
        server.modifyDN(개발2팀, "cn=PLATFORM", true);
        var snapshot = new GroupOfNamesStrategy(기본값_그대로(), 고정시계).read(ldapTemplate);

        // then — 임베디드 서버가 개명에서 entryUUID 를 유지하고, 우리는 그 id 로 같은 조직을 본다
        assertThat(entryUUID("cn=PLATFORM,ou=groups," + BASE_DN))
                .as("서버는 개명에서 entryUUID 를 유지한다").isEqualTo(전);
        assertThat(snapshot.groups()).containsKey(전);
        assertThat(snapshot.groups().get(전).externalId()).startsWith("cn=PLATFORM");
        assertThat(snapshot.groups().get(전).displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("직원의 uid 를 바꿔도 같은 id 다 — userName 만 새 uid 가 된다")
    void 직원_개명은_같은_id_다() throws LDAPException {
        // given
        String 로그인_없는_직원_id = entryUUID(로그인_없는_직원);
        String 홍_전 = entryUUID(홍);

        // when
        server.modifyDN(홍, "uid=hong.gd", true);
        var snapshot = new GroupOfNamesStrategy(기본값_그대로(), 고정시계).read(ldapTemplate);

        // then — uid 가 바뀌었을 뿐 다른 사람이 되지 않았다
        assertThat(entryUUID("uid=hong.gd,ou=people," + BASE_DN)).isEqualTo(홍_전);
        assertThat(snapshot.users()).containsKey(홍_전).containsKey(로그인_없는_직원_id);
        assertThat(snapshot.users().get(홍_전).userName()).isEqualTo("hong.gd");
        assertThat(snapshot.users().get(홍_전).displayName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("직원 userName 은 로그인 속성의 원본 값이다 — 정규화하지 않는다(점검 S25)")
    void userName_은_원본이다() {
        // given — uid: "hong gd" 인 직원. 정규화하면 "hong_gd" 가 된다
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — userName 은 "hong gd", id 는 entryUUID
        var 홍길동 = snapshot.users().get(entryUUID(홍));
        assertThat(홍길동).isNotNull();
        assertThat(홍길동.userName()).isEqualTo("hong gd");
        assertThat(홍길동.id()).isEqualTo(entryUUID(홍)).doesNotContain("hong");
    }

    @Test
    @DisplayName("로그인 속성이 없으면 userName 을 식별 값으로 대신한다")
    void 로그인_속성이_없으면_식별값으로_대신한다() {
        // given — uid 가 없는 직원
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        String id = entryUUID(로그인_없는_직원);
        assertThat(snapshot.users()).containsKey(id);
        assertThat(snapshot.users().get(id).userName()).isEqualTo(id);
        assertThat(snapshot.users().get(id).displayName()).isEqualTo("로그인 없음");
    }

    @Test
    @DisplayName("로그인 속성을 바꾸면 그 속성의 원본 값이 userName 이 된다")
    void 로그인_속성을_바꿀_수_있다() {
        // given — 로그인 속성을 cn 으로
        var properties = 기본값_그대로();
        properties.getGroupOfNames().setUserLoginAttribute("cn");
        var strategy = new GroupOfNamesStrategy(properties, 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get(entryUUID(김)).userName()).isEqualTo("Kim Chulsoo");
        assertThat(snapshot.users().get(entryUUID(로그인_없는_직원)).userName()).isEqualTo("Nologin Person");
    }

    @Test
    @DisplayName("조직 이름 속성이 없으면 표시명은 DN 의 첫 RDN 값이다")
    void 조직명이_없으면_RDN_값이다() {
        // given — description 이 없는 그룹 cn=Platform Team
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — id(entryUUID)도 정규화된 값("Platform_Team")도 아닌 RDN 값 그대로다
        var 그룹 = snapshot.groups().get(entryUUID(이름_없는_그룹));
        assertThat(그룹).isNotNull();
        assertThat(그룹.displayName()).isEqualTo("Platform Team");
    }

    @Test
    @DisplayName("DIT 기본 설정에서도 OU·직원 id 는 entryUUID 이고, 계층·소속은 그 id 로 이어진다")
    void DIT_도_entryUUID_다() {
        // given
        var strategy = new DitStrategy(DIT_기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        String 개발본부_id = entryUUID(개발본부);
        String 최_id = entryUUID(최);
        assertThat(snapshot.groups()).containsKeys(entryUUID(전사), 개발본부_id, entryUUID(운영본부));
        assertThat(snapshot.groups()).doesNotContainKeys("company", "DEV001", "OPS001");
        assertThat(snapshot.groups().get(entryUUID(전사)).members())
                .contains(MemberRef.group(개발본부_id), MemberRef.group(entryUUID(운영본부)));
        assertThat(snapshot.groups().get(개발본부_id).members()).contains(MemberRef.user(최_id));
        assertThat(snapshot.users().get(최_id).userName()).isEqualTo("choi");
        assertThat(snapshot.users()).doesNotContainKey("choi");
    }

    @Test
    @DisplayName("DIT 에서 OU 를 개명해도 같은 id 다 — 계층은 새 이름 위에서도 이어진다")
    void DIT_OU_개명은_같은_id_다() throws LDAPException {
        // given
        String 전 = entryUUID(운영본부);

        // when
        server.modifyDN(운영본부, "ou=Operations", true);
        var snapshot = new DitStrategy(DIT_기본값_그대로(), 고정시계).read(ldapTemplate);

        // then
        assertThat(entryUUID("ou=Operations,ou=company," + BASE_DN)).as("서버는 개명에서 entryUUID 를 유지한다").isEqualTo(전);
        assertThat(snapshot.groups()).containsKey(전);
        assertThat(snapshot.groups().get(전).externalId()).startsWith("ou=Operations");
        assertThat(snapshot.groups().get(entryUUID(전사)).members()).contains(MemberRef.group(전));
    }

    @Test
    @DisplayName("DIT 에서 직원이 든 OU 를 개명해도 OU 와 직원 모두 같은 id 이고 소속이 이어진다")
    void DIT_직원이_든_OU_개명도_같은_id_다() throws LDAPException {
        // given
        String 조직_전 = entryUUID(개발본부);
        String 직원_전 = entryUUID(최);

        // when
        server.modifyDN(개발본부, "ou=PLATFORM", true);
        var snapshot = new DitStrategy(DIT_기본값_그대로(), 고정시계).read(ldapTemplate);

        // then — 하위 엔트리의 DN 도 바뀌었지만 id 는 그대로이고, 새 이름 위에서 소속이 이어진다
        assertThat(snapshot.groups().get(조직_전).externalId()).startsWith("ou=PLATFORM");
        assertThat(snapshot.users().get(직원_전).externalId()).startsWith("uid=choi,ou=PLATFORM");
        assertThat(snapshot.groups().get(조직_전).members()).contains(MemberRef.user(직원_전));
        assertThat(snapshot.groups().get(entryUUID(전사)).members()).contains(MemberRef.group(조직_전));
    }

    @Test
    @DisplayName("DIT 에서 다른 부모 아래의 같은 이름 OU 는 서로 다른 id 라 충돌하지 않는다")
    void DIT_같은_이름의_OU_는_서로_다른_id_다() throws Exception {
        // given — 서로 다른 부모 아래의 ou=support 두 개. 이름 기반 id 라면 코드 "support" 로 충돌하는 모양이다
        server.add("dn: ou=support,ou=DEV001,ou=company," + BASE_DN, "objectClass: organizationalUnit", "ou: support",
                "description: 개발지원팀");
        server.add("dn: ou=support,ou=OPS001,ou=company," + BASE_DN, "objectClass: organizationalUnit", "ou: support",
                "description: 운영지원팀");
        var strategy = new DitStrategy(DIT_기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 둘 다 살아 있고, 각자의 부모 아래에 있다
        String 개발지원 = entryUUID("ou=support,ou=DEV001,ou=company," + BASE_DN);
        String 운영지원 = entryUUID("ou=support,ou=OPS001,ou=company," + BASE_DN);
        assertThat(개발지원).isNotEqualTo(운영지원);
        assertThat(snapshot.groups().get(개발지원).displayName()).isEqualTo("개발지원팀");
        assertThat(snapshot.groups().get(운영지원).displayName()).isEqualTo("운영지원팀");
        assertThat(snapshot.groups().get(entryUUID(개발본부)).members()).contains(MemberRef.group(개발지원));
        assertThat(snapshot.groups().get(entryUUID(운영본부)).members()).contains(MemberRef.group(운영지원));
    }

    @Test
    @DisplayName("DIT 직원 userName 도 로그인 속성의 원본 값이다 — 정규화하지 않는다(점검 S25)")
    void DIT_userName_은_원본이다() {
        // given — uid: "han gil" 인 직원
        var strategy = new DitStrategy(DIT_기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        var 한길 = snapshot.users().get(entryUUID(한));
        assertThat(한길).isNotNull();
        assertThat(한길.userName()).isEqualTo("han gil");
    }

    @Test
    @DisplayName("DIT 도 조직 이름 속성이 없으면 표시명은 DN 의 첫 RDN 값이다")
    void DIT_조직명이_없으면_RDN_값이다() {
        // given — description 이 없는 ou=Sales Team
        var strategy = new DitStrategy(DIT_기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        var 영업 = snapshot.groups().get(entryUUID(이름_없는_OU));
        assertThat(영업).isNotNull();
        assertThat(영업.displayName()).isEqualTo("Sales Team");
    }

    @Test
    @DisplayName("직원에게 표시명·cn 이 없으면 표시명은 userName 이다 — id(UUID)가 표시명으로 보이면 안 된다 — groupOfNames")
    void 표시명의_마지막_대체는_userName_이다() throws Exception {
        // given — uid 만 있는 직원(스키마가 없어 허용된다). 기본 설정이라 id 는 entryUUID 다
        server.add("dn: uid=bare,ou=people," + BASE_DN, "objectClass: inetOrgPerson", "uid: bare");
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 표시명은 uid 원본이고 entryUUID 가 아니다
        String id = entryUUID("uid=bare,ou=people," + BASE_DN);
        var 직원 = snapshot.users().get(id);
        assertThat(직원).isNotNull();
        assertThat(직원.displayName()).isEqualTo("bare").isNotEqualTo(id);
    }

    @Test
    @DisplayName("DIT 도 직원에게 표시명·cn 이 없으면 표시명은 userName 이다 — id(UUID)가 표시명으로 보이면 안 된다")
    void DIT_표시명의_마지막_대체는_userName_이다() throws Exception {
        // given — uid 만 있는 직원. 기본 설정이라 id 는 entryUUID 다
        server.add("dn: uid=bare,ou=DEV001,ou=company," + BASE_DN, "objectClass: inetOrgPerson", "uid: bare");
        var strategy = new DitStrategy(DIT_기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        String id = entryUUID("uid=bare,ou=DEV001,ou=company," + BASE_DN);
        var 직원 = snapshot.users().get(id);
        assertThat(직원).isNotNull();
        assertThat(직원.displayName()).isEqualTo("bare").isNotEqualTo(id);
    }

    @Test
    @DisplayName("DIT 의 root-dn 이 비어 있어도(컨텍스트 베이스가 루트 OU) 이름 속성이 없는 루트 OU 의 표시명은 그 OU 의 RDN 값이다")
    void DIT_루트_OU_의_표시명도_RDN_값이다() throws Exception {
        // given — 컨텍스트 베이스가 description 없는 ou=Sales Team 이고 root-dn 이 "" 라, 루트 OU 의 상대 DN 이 빈 문자열이다
        var properties = DIT_기본값_그대로();
        properties.setBaseDn(이름_없는_OU);
        properties.getDit().setRootDn("");
        var strategy = new DitStrategy(properties, 고정시계);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then — 상대 DN 이 비어도 표시명이 null 이 되지 않는다
        var 루트 = snapshot.groups().get(entryUUID(이름_없는_OU));
        assertThat(루트).isNotNull();
        assertThat(루트.displayName()).isEqualTo("Sales Team");
    }

    @Test
    @DisplayName("다른 조직을 멤버로 가진 조직은 하위 조직을 그 조직의 entryUUID 로 가리킨다")
    void 하위_조직은_조직_id_로_이어진다() throws Exception {
        // given — 그룹 DEV002 와 직원 kim 을 멤버로 가진 상위 그룹
        server.add("dn: cn=Division,ou=groups," + BASE_DN, "objectClass: groupOfNames", "cn: Division",
                "description: 본부", "member: " + 개발2팀, "member: " + 김);
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 하위 조직의 id 는 entryUUID 이고, 상위 조직의 멤버에 조직 멤버로 들어 있다
        String 하위 = entryUUID(개발2팀);
        String 상위 = entryUUID("cn=Division,ou=groups," + BASE_DN);
        assertThat(snapshot.groups()).containsKeys(상위, 하위);
        assertThat(snapshot.groups().get(상위).members())
                .containsExactlyInAnyOrder(MemberRef.group(하위), MemberRef.user(entryUUID(김)));
    }

    @Test
    @DisplayName("식별 속성을 objectGUID 로 두면 이진 값을 GUID 문자열 id 로 읽는다")
    void objectGUID_로_읽는다() throws Exception {
        // given — 스키마 없는 임베디드 서버에 objectGUID 이진 값을 단 직원·그룹. 컨텍스트 소스는 LdapConfig 로 만든다(이진 선언이 들어가게)
        byte[] 김_바이트 = HexFormat.of().parseHex("d4c3b2a1f6e51807292a3b4c5d6e7f80");
        objectGUID를_단다(김, 김_바이트);
        objectGUID를_단다(홍, 바이트(2));
        objectGUID를_단다(로그인_없는_직원, 바이트(3));
        byte[] 그룹_바이트 = 바이트(4);
        objectGUID를_단다(개발2팀, 그룹_바이트);
        objectGUID를_단다(이름_없는_그룹, 바이트(5));
        var properties = objectGUID_설정();
        var strategy = new GroupOfNamesStrategy(properties, 고정시계);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then — id 는 AD 도구가 보여 주는 GUID 문자열이다
        String 김_id = LdapIdentifiers.guid(김_바이트);
        assertThat(김_id).isEqualTo("a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80");
        assertThat(snapshot.users()).containsOnlyKeys(
                김_id, LdapIdentifiers.guid(바이트(2)), LdapIdentifiers.guid(바이트(3)));
        assertThat(snapshot.users().get(김_id).userName()).isEqualTo("kim");
        String 그룹_id = LdapIdentifiers.guid(그룹_바이트);
        assertThat(snapshot.groups()).containsOnlyKeys(그룹_id, LdapIdentifiers.guid(바이트(5)));
        assertThat(snapshot.groups().get(그룹_id).members())
                .containsExactlyInAnyOrder(MemberRef.user(김_id), MemberRef.user(LdapIdentifiers.guid(바이트(2))));
    }

    @Test
    @DisplayName("DIT 도 식별 속성이 objectGUID 면 이진 값을 GUID 문자열 id 로 읽는다")
    void DIT_도_objectGUID_로_읽는다() throws Exception {
        // given — OU·직원 모두에 objectGUID 를 단다
        int 번호 = 10;
        for (String dn : new String[]{전사, 개발본부, 운영본부, 이름_없는_OU, 최, 한}) {
            objectGUID를_단다(dn, 바이트(번호++));
        }
        var properties = DIT_기본값_그대로();
        properties.getDit().setGroupIdAttribute("objectGUID");
        properties.getDit().setUserIdAttribute("objectGUID");
        var strategy = new DitStrategy(properties, 고정시계);

        // when
        var snapshot = strategy.read(LdapConfig로_만든_템플릿(properties));

        // then — 전사(10)·개발본부(11)·최(14)
        String 전사_id = LdapIdentifiers.guid(바이트(10));
        String 개발본부_id = LdapIdentifiers.guid(바이트(11));
        String 최_id = LdapIdentifiers.guid(바이트(14));
        assertThat(snapshot.groups()).containsKeys(전사_id, 개발본부_id);
        assertThat(snapshot.groups().get(전사_id).members()).contains(MemberRef.group(개발본부_id));
        assertThat(snapshot.groups().get(개발본부_id).members()).contains(MemberRef.user(최_id));
        assertThat(snapshot.users().get(최_id).userName()).isEqualTo("choi");
    }

    @Test
    @DisplayName("이진 선언 없이 읽은 objectGUID 는 데이터 오류다 — LdapConfig 의 선언이 실제로 필요하다")
    void objectGUID_는_이진_선언_없이는_읽지_못한다() throws Exception {
        // given — EmbeddedLdapSupport 의 템플릿에는 JNDI 이진 선언이 없다
        objectGUID를_단다(김, 바이트(1));
        objectGUID를_단다(홍, 바이트(2));
        objectGUID를_단다(로그인_없는_직원, 바이트(3));
        var strategy = new GroupOfNamesStrategy(objectGUID_설정(), 고정시계);

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("objectGUID")
                .hasMessageContaining("이진");
    }

    @Test
    @DisplayName("UUID·GUID 문자열은 IdNormalizer 를 거쳐도 그대로다 — 전략이 정규화를 계속 걸어도 id 가 바뀌지 않는다")
    void UUID_와_GUID_는_정규화에_변하지_않는다() {
        // given
        String uuid = entryUUID(김);
        String guid = LdapIdentifiers.guid(바이트(7));

        // when, then
        assertThat(IdNormalizer.normalize(uuid)).isEqualTo(uuid);
        assertThat(IdNormalizer.normalize(guid)).isEqualTo(guid);
    }

    private LdapProperties objectGUID_설정() {
        var properties = 기본값_그대로();
        properties.getGroupOfNames().setUserIdAttribute("objectGUID");
        properties.getGroupOfNames().setGroupIdAttribute("objectGUID");
        return properties;
    }

    /** 운영과 같은 컨텍스트 소스({@link LdapConfig#ldapContextSource}) — 식별 속성이 objectGUID 면 이진 선언이 환경에 들어간다. */
    private LdapTemplate LdapConfig로_만든_템플릿(LdapProperties properties) throws Exception {
        properties.setUrl("ldap://localhost:" + server.getListenPort());
        properties.setBindDn(BIND_DN);
        properties.setBindPassword(BIND_PASSWORD);
        LdapContextSource contextSource = new LdapConfig().ldapContextSource(properties);
        contextSource.afterPropertiesSet();
        return LdapTemplates.configured(contextSource);
    }

    private void objectGUID를_단다(String dn, byte[] 바이트) throws LDAPException {
        server.modify(dn, new Modification(ModificationType.ADD, "objectGUID", 바이트));
    }

    /** 번호마다 다른 16바이트. */
    private static byte[] 바이트(int 번호) {
        byte[] 바이트 = new byte[16];
        for (int i = 0; i < 바이트.length; i++) {
            바이트[i] = (byte) (번호 * 16 + i);
        }
        return 바이트;
    }
}
