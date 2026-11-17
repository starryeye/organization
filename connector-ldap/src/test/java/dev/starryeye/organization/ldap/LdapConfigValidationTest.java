package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;

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

    private static final String 주소_모양_문구 =
            "ldap.urls 의 주소는 ldap://호스트[:포트] 또는 ldaps://호스트[:포트] 다 — 검색 베이스는 ldap.base-dn 에 준다(받은 값: %s)";

    @Test
    @DisplayName("주소 목록이 비었거나 빈 항목이 있으면 멈춘다")
    void 주소_목록이_비었거나_빈_항목이_있으면_멈춘다() {
        var 빈_목록 = 설정();
        빈_목록.setUrls(List.of());
        var 빈_항목 = 설정();
        빈_항목.setUrls(List.of("ldap://dc1:389", " "));

        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(빈_목록))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ldap.urls 가 비었다 — LDAP 주소를 하나 이상 준다");
        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(빈_항목))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ldap.urls 의 주소는 ldap://호스트[:포트] 또는 ldaps://호스트[:포트] 다 — 검색 베이스는 ldap.base-dn 에 준다(받은 값:  )");
    }

    @Test
    @DisplayName("주소 모양이 틀리면 멈춘다 — 스킴·호스트·경로·질의")
    void 주소_모양이_틀리면_멈춘다() {
        // 깨뜨릴 변경: 모양 검사를 빼거나 첫 주소만 보면 이 시험이 깨진다(틀린 주소를 둘째 자리에도 둔다)
        for (String 틀린 : List.of("http://dc1:389", "ldap://", "ldap://dc1:389/dc=example,dc=com", "ldap://dc1:389?x", "dc1:389")) {
            var 첫째 = 설정();
            첫째.setUrls(List.of(틀린));
            var 둘째 = 설정();
            둘째.setUrls(List.of("ldap://dc1:389", 틀린));

            assertThatThrownBy(() -> LdapConfig.설정을_검사한다(첫째))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(주소_모양_문구.formatted(틀린));
            assertThatThrownBy(() -> LdapConfig.설정을_검사한다(둘째))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(주소_모양_문구.formatted(틀린));
        }
    }

    @Test
    @DisplayName("주소에 사용자 정보(비밀번호)가 있으면 멈추고, 문구에는 그것을 *** 로 가려 싣는다")
    void 주소에_사용자_정보가_있으면_멈추고_가려_싣는다() {
        // 밑줄 호스트는 URI 가 권한부를 서버 형태로 읽지 못해 getRawUserInfo() 가 null 이다 — 그래도 잡는다
        var 틀린_가린 = new java.util.LinkedHashMap<String, String>();
        틀린_가린.put("ldaps://svc:pw@dc1", "ldaps://***@dc1");
        틀린_가린.put("ldap://u:pw@dc1/x", "ldap://***@dc1/x");
        틀린_가린.put("ldap://svc:pw@dc_1.corp:389", "ldap://***@dc_1.corp:389");
        틀린_가린.forEach((틀린, 가린) -> {
            var properties = 설정();
            properties.setUrls(List.of("ldap://dc1:389", 틀린));

            assertThatThrownBy(() -> LdapConfig.설정을_검사한다(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(주소_모양_문구.formatted(가린))
                    .message().doesNotContain("pw");
        });
    }

    @Test
    @DisplayName("포트가 숫자가 아니거나 1~65535 밖이면 멈춘다")
    void 포트가_숫자가_아니거나_범위_밖이면_멈춘다() {
        for (String 틀린 : List.of("ldap://dc1:abc", "ldap://dc1:0", "ldap://dc1:70000", "ldap://dc_1.corp:abc", "ldap://[::1]:0")) {
            var properties = 설정();
            properties.setUrls(List.of(틀린));

            assertThatThrownBy(() -> LdapConfig.설정을_검사한다(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(주소_모양_문구.formatted(틀린));
        }
    }

    @Test
    @DisplayName("주소 모양이 맞으면 받는다 — 포트 없음·끝 슬래시·대문자 스킴·IPv6·밑줄 호스트")
    void 주소_모양이_맞으면_받는다() {
        var properties = 설정();
        properties.setUrls(List.of("ldap://dc1", "ldap://dc1:389", "ldap://dc1:65535", "ldap://dc1:389/", "LDAPS://DC1:636",
                "ldap://[::1]:389", "ldap://dc_1.corp:389"));

        assertThatCode(() -> LdapConfig.설정을_검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하면 모든 주소가 ldaps:// 여야 한다 — StartTLS 는 지원하지 않는다")
    void TLS_를_요구하면_모든_주소가_ldaps_여야_한다() {
        // 깨뜨릴 변경: 첫 주소만 보면 둘째의 평문 주소를 놓친다
        var 평문_섞임 = 설정();
        평문_섞임.setRequireTls(true);
        평문_섞임.setUrls(List.of("ldaps://dc1:636", "ldap://dc2:389"));
        var 암호화 = 설정();
        암호화.setRequireTls(true);
        암호화.setUrls(List.of("ldaps://dc1:636", "LDAPS://dc2:636"));

        assertThatThrownBy(() -> LdapConfig.설정을_검사한다(평문_섞임))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ldap.require-tls 인데 ldap.urls 에 ldaps:// 가 아닌 주소가 있다 — StartTLS 는 지원하지 않는다(받은 값: ldap://dc2:389)");
        assertThatCode(() -> LdapConfig.설정을_검사한다(암호화)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하지 않으면(기본값) ldap:// 도 받는다 — 로컬 개발")
    void TLS_요구는_기본이_꺼져_있다() {
        // given
        var properties = 설정();
        properties.setUrls(List.of("ldap://localhost:1389"));

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
