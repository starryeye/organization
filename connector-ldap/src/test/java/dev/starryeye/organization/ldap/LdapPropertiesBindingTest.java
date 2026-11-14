package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ldap.urls} 바인딩(설계 2026-11-14 F1) — 환경 변수의 쉼표 문자열과 yml 목록이 같은 주소 목록이 된다. */
class LdapPropertiesBindingTest {

    private static LdapProperties 바인딩한다(Map<String, Object> 값) {
        LdapProperties properties = new LdapProperties();
        new Binder(new MapConfigurationPropertySource(값)).bind("ldap", Bindable.ofInstance(properties));
        return properties;
    }

    @Test
    @DisplayName("쉼표로 이은 문자열과 목록이 같은 주소 목록이 된다")
    void 쉼표로_이은_문자열과_목록이_같은_주소_목록이_된다() {
        var 문자열 = 바인딩한다(Map.of("ldap.urls", "ldaps://a:636, ldaps://b:636"));
        var 목록 = 바인딩한다(Map.of("ldap.urls[0]", "ldaps://a:636", "ldap.urls[1]", "ldaps://b:636"));

        assertThat(문자열.getUrls()).containsExactly("ldaps://a:636", "ldaps://b:636");
        assertThat(목록.getUrls()).isEqualTo(문자열.getUrls());
    }

    @Test
    @DisplayName("주소를 주지 않으면 로컬 기본값이다")
    void 주소를_주지_않으면_로컬_기본값이다() {
        assertThat(바인딩한다(Map.of()).getUrls()).isEqualTo(List.of("ldap://localhost:1389"));
    }
}
