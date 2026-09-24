package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapDnsTest {

    private static final String BASE = "dc=example,dc=com";

    @Test
    @DisplayName("속성 이름 대소문자와 쉼표 주변 공백이 달라도 같은 엔트리로 대조된다")
    void 대소문자와_공백을_흡수한다() {
        // given
        String 서버가준것 = "CN=Hong Gildong, OU=Seoul, DC=example, DC=com";
        String 우리가읽은것 = "cn=hong gildong,ou=seoul,dc=example,dc=com";

        // when, then
        assertThat(LdapDns.대조키(서버가준것)).isEqualTo(LdapDns.대조키(우리가읽은것));
    }

    @Test
    @DisplayName("이스케이프된 쉼표는 값의 일부다 — 표기법이 다양해도 같은 엔트리다")
    void 이스케이프된_쉼표를_값으로_다룬다() {
        // given — 이름에 쉼표가 든 사람을 세 가지 LDAP 표기법으로 나타낸다
        // 모두 같은 엔트리다: CN 속성값이 "Hong, Gildong"
        String 백슬래시이스케이프 = "CN=Hong\\, Gildong,OU=Seoul," + BASE;
        String 따옴표 = "CN=\"Hong, Gildong\",OU=Seoul," + BASE;
        String 헥스이스케이프 = "CN=Hong\\2C Gildong,OU=Seoul," + BASE;

        // 다른 엔트리: 실제로 다른 사람. CN과 OU가 다르다
        String 다른사람 = "CN=Hong,OU=Gildong,OU=Seoul," + BASE;

        // when
        String 키1 = LdapDns.대조키(백슬래시이스케이프);
        String 키2 = LdapDns.대조키(따옴표);
        String 키3 = LdapDns.대조키(헥스이스케이프);
        String 다른키 = LdapDns.대조키(다른사람);

        // then — 표기법이 달라도 같은 엔트리는 같은 키가 된다 (진정한 파싱의 증거)
        assertThat(키1)
                .as("백슬래시 이스케이프와 따옴표 표기법은 같은 엔트리")
                .isEqualTo(키2);
        assertThat(키1)
                .as("백슬래시 이스케이프와 헥스 이스케이프 표기법은 같은 엔트리")
                .isEqualTo(키3);
        assertThat(키2)
                .as("따옴표와 헥스 이스케이프 표기법은 같은 엔트리")
                .isEqualTo(키3);

        // 이스케이프된 쉼표는 RDN 경계가 아니다 — 다른 엔트리와 구분되어야 한다
        assertThat(키1)
                .as("이스케이프된 쉼표 엔트리와 다른 사람은 다른 키")
                .isNotEqualTo(다른키);
    }

    @Test
    @DisplayName("다중값 RDN 은 적힌 순서가 달라도 같은 엔트리로 대조된다")
    void 다중값_RDN의_순서를_흡수한다() {
        // given
        String 이쪽순서 = "CN=hgd+OU=Seoul," + BASE;
        String 저쪽순서 = "OU=Seoul+CN=hgd," + BASE;

        // when, then
        assertThat(LdapDns.대조키(이쪽순서)).isEqualTo(LdapDns.대조키(저쪽순서));
    }

    @Test
    @DisplayName("서로 다른 엔트리는 다른 키가 된다 — 무엇이든 같게 만드는 정규화는 쓸모가 없다")
    void 다른_엔트리는_다른_키다() {
        // given, when, then
        assertThat(LdapDns.대조키("uid=kim,ou=people," + BASE))
                .isNotEqualTo(LdapDns.대조키("uid=lee,ou=people," + BASE));
    }

    @Test
    @DisplayName("베이스 상대 DN 에 베이스를 붙여 절대 DN 을 만든다")
    void 절대DN을_만든다() {
        // given, when
        String 절대 = LdapDns.절대로("cn=Hong Gildong,ou=Seoul,ou=users", BASE);

        // then
        assertThat(LdapDns.대조키(절대))
                .isEqualTo(LdapDns.대조키("cn=Hong Gildong,ou=Seoul,ou=users," + BASE));
    }

    @Test
    @DisplayName("엔트리가 베이스 자신이면 베이스가, 베이스가 비면 상대 DN 이 그대로 절대 DN 이다")
    void 절대DN의_경계를_다룬다() {
        // given, when, then
        assertThat(LdapDns.대조키(LdapDns.절대로("", BASE))).isEqualTo(LdapDns.대조키(BASE));
        assertThat(LdapDns.대조키(LdapDns.절대로("cn=dev,ou=groups", "")))
                .isEqualTo(LdapDns.대조키("cn=dev,ou=groups"));
    }

    @Test
    @DisplayName("절대 DN 에서 베이스를 떼어 낸다 — 범위 재요청은 상대 DN 을 받는다")
    void 상대DN으로_되돌린다() {
        // given, when
        String 상대 = LdapDns.상대로("cn=dev,ou=groups," + BASE, BASE);

        // then
        assertThat(LdapDns.대조키(상대)).isEqualTo(LdapDns.대조키("cn=dev,ou=groups"));
    }

    @Test
    @DisplayName("베이스 아래에 없는 DN 을 상대 DN 으로 바꾸려 하면 조용히 넘어가지 않고 깨진다")
    void 베이스_밖의_DN은_거부한다() {
        // given, when, then
        assertThatThrownBy(() -> LdapDns.상대로("cn=dev,ou=groups,dc=other,dc=com", BASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("베이스");
    }

    @Test
    @DisplayName("해석할 수 없는 DN 은 문자열 비교로 물러나지 않고 예외로 알린다")
    void 해석할_수_없는_DN은_예외다() {
        // given — 대조하는 DN 은 모두 서버가 준 값이다. 해석에 실패했다면 값이 아니라
        // 우리가 DN 을 다루는 방식이 틀린 것이다
        // when, then
        assertThatThrownBy(() -> LdapDns.대조키("이건 DN 이 아니다"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("해석하지 못했습니다");
    }

    @Test
    @DisplayName("부모 DN 은 맨 앞 RDN 하나를 뗀 것이다 — 이스케이프된 쉼표는 경계가 아니다")
    void 부모_DN을_구한다() {
        // given, when, then
        assertThat(LdapDns.대조키(LdapDns.부모("ou=R\\,D,ou=company," + BASE)))
                .isEqualTo(LdapDns.대조키("ou=company," + BASE));
        assertThat(LdapDns.부모("ou=company"))
                .as("최상위면 부모가 없다")
                .isEmpty();
    }
}
