package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchResult;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPResult;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 페이징 검색의 응답에 페이징 컨트롤(RFC 2696 의 응답 쪽)이 없을 때.
 *
 * <p>Spring 의 {@code PagedResultsDirContextProcessor} 는 응답 컨트롤을 받아야만 {@code hasMore()} 를 거짓으로 바꾼다. 컨트롤이 없으면 처음 값(참)과
 * 지난 쿠키가 그대로 남아, 같은 요청을 끝없이 다시 보내고 같은 엔트리를 끝없이 쌓았다 — 회차는 기한(30분)까지 돌다 메모리가 먼저 바닥날 수 있다.
 * 우리는 페이징 요청을 critical 로 보내므로(Spring 기본값) 표준을 지키는 서버는 컨트롤을 붙이거나 오류로 답한다(RFC 4511 §4.1.11). 컨트롤 없는 응답은
 * 서버(또는 사이의 프록시)의 표준 위반이고, 받은 목록이 전부인지 알 수 없으므로 회차를 데이터 오류로 실패시킨다.
 */
class PagedControlMissingTest extends EmbeddedLdapSupport {

    /** 서버가 끝낸 검색 응답(SearchResultDone) 수 — 페이지 하나가 응답 하나다. 같은 요청을 되풀이하면 기대보다 늘어난다 */
    private final AtomicInteger 응답수 = new AtomicInteger();

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

                dn: uid=u1,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u1
                cn: U1
                sn: U

                dn: uid=u2,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u2
                cn: U2
                sn: U

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                member: uid=u1,ou=people,dc=example,dc=com
                member: uid=u2,ou=people,dc=example,dc=com
                """;
    }

    /** 검색 응답마다 응답 컨트롤을 떼어 낸다 — 페이징 컨트롤을 무시하는 서버·프록시를 흉내 낸다 */
    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchResult(InMemoryInterceptedSearchResult result) {
                응답수.incrementAndGet();
                LDAPResult 원래 = result.getResult();
                result.setResult(new LDAPResult(원래.getMessageID(), 원래.getResultCode(), 원래.getDiagnosticMessage(),
                        원래.getMatchedDN(), 원래.getReferralURLs(), new Control[0]));
            }
        });
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("페이징 응답 컨트롤이 없는 쪽을 받으면 같은 요청을 되풀이하지 않고 데이터 오류로 회차를 실패시킨다(RFC 2696·RFC 4511 §4.1.11)")
    void 페이징_컨트롤이_없으면_실패한다() {
        // given — 페이징(기본 500)으로 읽는다
        var properties = 이름기반();

        // when, then — 고치기 전에는 같은 요청을 끝없이 되풀이해 이 테스트가 10초 한도에 걸렸다
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("페이징 응답 컨트롤");
        assertThat(응답수).as("첫 응답에서 멈춘다 — 같은 요청을 다시 보내지 않는다").hasValue(1);
    }
}
