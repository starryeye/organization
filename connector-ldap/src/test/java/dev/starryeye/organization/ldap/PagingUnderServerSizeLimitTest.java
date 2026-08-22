package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.SizeLimitExceededException;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 페이징이 <b>원래 결함</b>을 닫았는지 확인한다.
 *
 * <p>기존 페이징 테스트는 페이징 기계장치가 도는 것만 보여줬다. 최종 리뷰어가 프로덕션
 * 코드를 수정 전으로 되돌린 뒤 그 테스트를 그대로 돌렸더니 <b>통과했다</b> — 임베디드
 * 서버에 서버측 상한이 없어, 페이징 없는 평범한 검색도 전체를 반환했기 때문이다.
 * 즉 그 테스트는 "조용한 잘림" 이 닫혔음을 증명하지 못했다.
 *
 * <p>여기서는 서버에 상한을 걸어 실제 디렉터리(Active Directory 의 {@code MaxPageSize} 등)를
 * 흉내낸다. 상한 위로는 결과가 잘리므로, 페이징이 없으면 반드시 드러난다.
 */
class PagingUnderServerSizeLimitTest extends EmbeddedLdapSupport {

    private static final int 서버상한 = 20;
    private static final int 직원수 = 50;

    /** 상한(20)보다 많은 직원(50)을 넣는다. 페이징 없이는 절대 다 못 읽는다. */
    @Override
    protected int maxSizeLimit() {
        return 서버상한;
    }

    @Override
    protected String ldif() {
        String 사람들 = IntStream.range(0, 직원수)
                .mapToObj(i -> """
                        dn: uid=user%03d,ou=people,dc=example,dc=com
                        objectClass: inetOrgPerson
                        uid: user%03d
                        cn: User %03d
                        sn: User
                        displayName: 직원%03d
                        mail: user%03d@example.com
                        """.formatted(i, i, i, i, i))
                .collect(Collectors.joining("\n"));

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

                """ + 사람들 + """

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: uid=user000,ou=people,dc=example,dc=com
                """;
    }

    private static LdapProperties 설정(int pageSize) {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setPageSize(pageSize);
        var groupOfNames = properties.getGroupOfNames();
        groupOfNames.setUserSearchBase("ou=people");
        groupOfNames.setUserObjectClass("inetOrgPerson");
        groupOfNames.setUserIdAttribute("uid");
        groupOfNames.setUserNameAttribute("displayName");
        groupOfNames.setUserMailAttribute("mail");
        groupOfNames.setGroupSearchBase("ou=groups");
        groupOfNames.setGroupObjectClass("groupOfNames");
        groupOfNames.setGroupIdAttribute("cn");
        groupOfNames.setGroupNameAttribute("description");
        groupOfNames.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("서버가 결과를 자르는 상한이 있어도 페이징으로 전원을 읽어온다")
    void 서버_상한을_페이징으로_넘는다() {
        // given — 페이지 크기(10)는 상한(20) 아래라 각 페이지는 잘리지 않는다
        var strategy = new GroupOfNamesStrategy(설정(10));

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 50명 전원. 페이징이 없으면 20명에서 잘린다
        assertThat(snapshot.users()).hasSize(직원수);
        assertThat(snapshot.users()).containsKey("user049");
    }

    @Test
    @DisplayName("페이징을 끄면 서버 상한에서 잘린 사실이 예외로 드러난다 — 조용히 넘어가지 않는다")
    void 페이징이_없으면_잘림이_예외로_드러난다() {
        // given — page-size 0 은 페이징 없는 단일 검색이다
        var strategy = new GroupOfNamesStrategy(설정(0));

        // when, then — 이것이 ignoreSizeLimitExceededException(false) 방어선이다.
        // true 였다면 20명짜리 목록이 조용히 돌아오고, 30명이 한꺼번에 퇴사한 것처럼
        // 보여 실제 소속이 지워진다. 지금까지 어떤 테스트도 이 선을 타지 않았다.
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .isInstanceOf(SizeLimitExceededException.class);
    }
}
