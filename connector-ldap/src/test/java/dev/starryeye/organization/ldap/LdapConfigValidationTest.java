package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기동 때 설정을 검사한다(설계 2026-10-27 §5). 오타 하나가 조용히 다른 길로 가거나(전략), 풀리지 않은 자리표시자가 그대로 비밀번호가 되거나(바인드),
 * 평문 LDAP 으로 자격증명이 오가는 일을 막는다. 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은 자리표시자를 무시하고
 * {@code "${LDAP_BIND_PASSWORD}"} 문자열을 그대로 넣으므로 명시적으로 본다.
 */
class LdapConfigValidationTest {

    private static LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBindDn("cn=admin,dc=example,dc=com");
        properties.setBindPassword("adminpassword");
        return properties;
    }

    @Test
    @DisplayName("전략 이름 오타는 기동을 멈추고 받는 값을 적는다 — 'ditt' 가 조용히 group-of-names 로 읽히지 않는다")
    void 전략_이름_오타는_기동을_멈추고_받는_값을_적는다() {
        // given
        var properties = 설정();
        properties.setStrategy("ditt");

        // when, then
        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group-of-names")
                .hasMessageContaining("dit")
                .hasMessageContaining("ditt");
    }

    @Test
    @DisplayName("전략 이름은 대소문자를 가리지 않는다")
    void 전략_이름은_대소문자를_가리지_않는다() {
        // given
        var properties = 설정();
        properties.setStrategy("DIT");

        // when, then
        assertThatCode(() -> LdapConfig.설정을_검사한다(properties)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("바인드 DN 이 있는데 비밀번호가 null·빈 문자열·빈칸이면 멈춘다")
    void 바인드_DN_이_있는데_비밀번호가_비면_멈춘다(String 비밀번호) {
        // given
        var properties = 설정();
        properties.setBindPassword(비밀번호);

        // when, then
        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ldap.bind-dn 이 있는데 ldap.bind-password 가 비었거나 풀리지 않은 자리표시자다"
                        + " — 환경 변수 LDAP_BIND_PASSWORD 를 준다");
    }

    @Test
    @DisplayName("풀리지 않은 자리표시자는 멈추고, 오류 문구에 그 값을 싣지 않는다")
    void 풀리지_않은_자리표시자는_멈추고_값을_싣지_않는다() {
        // given — ${LDAP_BIND_PASSWORD} 에 환경 변수가 없으면 바인딩이 이 문자열을 그대로 넣는다
        var properties = 설정();
        properties.setBindPassword("${LDAP_BIND_PASSWORD}");

        // when, then
        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ldap.bind-password")
                .message().doesNotContain("${LDAP_BIND_PASSWORD}");
    }

    @Test
    @DisplayName("익명 바인드(바인드 DN 없음)는 비밀번호가 없어도 통과한다")
    void 익명_바인드는_통과한다() {
        // given
        var properties = 설정();
        properties.setBindDn(null);
        properties.setBindPassword(null);

        // when, then
        assertThatCode(() -> LdapConfig.설정을_검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하면 ldaps:// 만 받는다 — StartTLS 는 지원하지 않는다")
    void TLS_를_요구하면_ldaps_만_받는다() {
        // given
        var 평문 = 설정();
        평문.setRequireTls(true);
        평문.setUrl("ldap://ldap.example.com:389");
        var 암호화 = 설정();
        암호화.setRequireTls(true);
        암호화.setUrl("ldaps://ldap.example.com:636");

        // when, then
        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(평문))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ldap.require-tls 인데 ldap.url 이 ldaps:// 가 아니다 — StartTLS 는 지원하지 않는다");
        assertThatCode(() -> LdapConfig.설정을_검사한다(암호화)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하지 않으면(기본값) ldap:// 도 받는다 — 로컬 개발")
    void TLS_요구는_기본이_꺼져_있다() {
        // given
        var properties = 설정();
        properties.setUrl("ldap://localhost:1389");

        // when, then
        assertThatCode(() -> LdapConfig.설정을_검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("컨텍스트 소스는 만들기 전에 설정을 검사한다 — 풀리지 않은 비밀번호로 LDAP 에 붙지 않는다")
    void 컨텍스트_소스는_만들기_전에_설정을_검사한다() {
        // given
        var properties = 설정();
        properties.setBindPassword("${LDAP_BIND_PASSWORD}");

        // when, then
        assertThatThrownBy(() -> new LdapConfig().ldapContextSource(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ldap.bind-password");
    }

    @Test
    @DisplayName("매핑 전략은 만들기 전에 설정을 검사한다 — 오타가 group-of-names 로 흘러가지 않는다")
    void 매핑_전략은_만들기_전에_설정을_검사한다() {
        // given
        var properties = 설정();
        properties.setStrategy("ditt");

        // when, then
        assertThatThrownBy(() -> new LdapConfig().ldapMappingStrategy(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ldap.strategy");
    }
}
