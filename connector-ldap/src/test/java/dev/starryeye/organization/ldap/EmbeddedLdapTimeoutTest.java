package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테스트의 LDAP 템플릿도 운영({@link LdapConfig})과 같은 접속·읽기 한도를 쓴다.
 *
 * <p>전에는 테스트 컨텍스트 소스에 한도가 없었다. 응답이 오지 않는 읽기 하나가 테스트를 끝없이 붙잡았고, 2026-10-08 전체 test 가 connector-ldap 에서
 * 한 시간 멈춘 적이 있다(재현 안 됨). 한도가 있으면 같은 일이 "read timed out" 실패로 남아 어디서 멈췄는지 보인다.
 * 여기서는 한도를 1초로 줄이고 서버가 3초 늦게 답하게 한다.
 */
class EmbeddedLdapTimeoutTest extends EmbeddedLdapSupport {

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
                """;
    }

    @Override
    protected LdapProperties 접속_한도() {
        var 한도 = new LdapProperties();
        한도.setReadTimeout(Duration.ofSeconds(1));
        return 한도;
    }

    /** 검색에 3초 늦게 답한다 — 읽기 한도(1초)를 넘긴다 */
    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("테스트 템플릿도 읽기 한도를 지킨다 — 늦은 응답은 끝없는 대기가 아니라 read timed out 실패다")
    void 늦은_응답은_읽기_한도에서_끊긴다() {
        // given
        var properties = 이름기반();

        // when, then — 한도가 없으면 3초를 기다린 뒤 정상으로 끝나 예외가 없다
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .hasStackTraceContaining("timed out");
    }
}
