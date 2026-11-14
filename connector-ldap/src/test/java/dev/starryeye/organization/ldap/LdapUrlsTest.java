package dev.starryeye.organization.ldap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.lang.reflect.Proxy;
import java.util.Hashtable;

import static org.assertj.core.api.Assertions.assertThat;

/** 붙은 주소 읽기(설계 2026-11-14 F7). */
class LdapUrlsTest {

    @Test
    @DisplayName("주소 부분은 스킴과 권한만 소문자로 남긴다")
    void 주소_부분은_스킴과_권한만_소문자로_남긴다() {
        assertThat(LdapUrls.주소_부분("ldap://127.0.0.1:1234/dc=example,dc=com")).contains("ldap://127.0.0.1:1234");
        assertThat(LdapUrls.주소_부분("LDAPS://DC1:636/")).contains("ldaps://dc1:636");
        assertThat(LdapUrls.주소_부분("ldap://[::1]:389")).contains("ldap://[::1]:389");
    }

    @Test
    @DisplayName("읽을 수 없는 주소는 던지지 않고 비어 있다 — 진단이 읽기를 실패시키거나 커넥션을 새게 하지 않는다")
    void 읽을_수_없는_주소는_던지지_않고_비어_있다() {
        assertThat(LdapUrls.주소_부분("ldap://bad host:389")).isEmpty();
        assertThat(LdapUrls.주소_부분("dc1")).isEmpty();
        assertThat(LdapUrls.붙은_주소(환경이_있는_컨텍스트("ldap://bad host:389"))).isEmpty();

        var source = new org.springframework.ldap.core.support.LdapContextSource();
        source.setUrls(new String[]{"ldap://bad host:389"});
        assertThat(LdapUrls.첫_주소(source)).isEmpty();
    }

    @Test
    @DisplayName("붙은 주소는 덮어쓰인 주소 하나일 때만 준다")
    void 붙은_주소는_덮어쓰인_주소_하나일_때만_준다() {
        assertThat(LdapUrls.붙은_주소(환경이_있는_컨텍스트("ldap://dc2:389/dc=example,dc=com"))).contains("ldap://dc2:389");
        assertThat(LdapUrls.붙은_주소(환경이_있는_컨텍스트("ldap://a:389/x ldap://b:389/x"))).isEmpty();
        assertThat(LdapUrls.붙은_주소(환경이_있는_컨텍스트(null))).isEmpty();
        assertThat(LdapUrls.붙은_주소(환경이_깨지는_컨텍스트())).isEmpty();
    }

    private static DirContext 환경이_있는_컨텍스트(String providerUrl) {
        var env = new Hashtable<String, Object>();
        if (providerUrl != null) {
            env.put(Context.PROVIDER_URL, providerUrl);
        }
        return 프록시(env, null);
    }

    private static DirContext 환경이_깨지는_컨텍스트() {
        return 프록시(null, new NamingException("테스트"));
    }

    private static DirContext 프록시(Hashtable<?, ?> env, NamingException 오류) {
        return (DirContext) Proxy.newProxyInstance(
                LdapUrlsTest.class.getClassLoader(),
                new Class<?>[]{DirContext.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getEnvironment")) {
                        return null;
                    }
                    if (오류 != null) {
                        throw 오류;
                    }
                    return env;
                });
    }
}
