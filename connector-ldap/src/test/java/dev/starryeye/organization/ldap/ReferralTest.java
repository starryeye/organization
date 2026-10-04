package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchResultReference;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색이 referral(검색 결과 참조, RFC 4511 §4.5.3)을 만났을 때(점검 S21, 설계 2026-10-05 §4.2).
 *
 * <p>따라가지 않는다 — 다른 DC 의 주소·자격 증명·DNS 가 필요하다. 다만 Spring 이 {@code PartialResultException} 을 DEBUG 로 삼키면
 * 검색 범위가 위임 서브트리·자식 도메인을 걸칠 때 그 부분이 늘 빠지는데 운영자는 모른다. 경고 한 줄을 남기고, 이미 받은 엔트리와 다음 페이지는 잃지 않는다.
 *
 * <p>UnboundID 인터셉터는 <b>검색 요청 단계</b>에서만 참조를 보낼 수 있다. 페이지 하나가 요청 하나이므로 몇 번째 요청에 끼울지 정한다.
 */
class ReferralTest extends EmbeddedLdapSupport {

    private final AtomicInteger 직원검색수 = new AtomicInteger();
    private volatile int 참조를_보낼_요청 = 1;
    private ListAppender<ILoggingEvent> 로그;

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
                displayName: 직원1
                mail: u1@example.com

                dn: uid=u2,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u2
                cn: U2
                sn: U
                displayName: 직원2
                mail: u2@example.com

                dn: uid=u3,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u3
                cn: U3
                sn: U
                displayName: 직원3
                mail: u3@example.com

                dn: uid=u4,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u4
                cn: U4
                sn: U
                displayName: 직원4
                mail: u4@example.com

                dn: uid=u5,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: u5
                cn: U5
                sn: U
                displayName: 직원5
                mail: u5@example.com

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: uid=u1,ou=people,dc=example,dc=com
                member: uid=u2,ou=people,dc=example,dc=com
                member: uid=u3,ou=people,dc=example,dc=com
                member: uid=u4,ou=people,dc=example,dc=com
                member: uid=u5,ou=people,dc=example,dc=com
                """;
    }

    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
                if (request.getRequest().getBaseDN().startsWith("ou=people")
                        && 직원검색수.incrementAndGet() == 참조를_보낼_요청) {
                    request.sendSearchReference(new SearchResultReference(
                            new String[]{"ldap://child.example.com/ou=people,dc=child,dc=example,dc=com"}, new Control[0]));
                }
            }
        });
    }

    @BeforeEach
    void 로그를_잡는다() {
        로그 = new ListAppender<>();
        로그.start();
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy")).addAppender(로그);
    }

    @AfterEach
    void 로그를_놓는다() {
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy")).detachAppender(로그);
    }

    private List<String> 참조_경고() {
        return 로그.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("LDAP 검색이 referral 을 만나")).toList();
    }

    @Test
    @DisplayName("referral 을 만나면 따라가지 않고 경고 한 줄을 남기며, 받은 엔트리는 잃지 않는다(점검 S21) — 페이징 없음")
    void 페이징_없이_참조를_만나도_엔트리를_잃지_않는다() {
        // given
        var properties = 이름기반();
        properties.setPageSize(0);

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("u1", "u2", "u3", "u4", "u5");
        assertThat(참조_경고()).hasSize(1).first().asString().contains("ou=people");
    }

    @Test
    @DisplayName("뒤 페이지 요청에 referral 이 와도 모든 페이지의 엔트리를 잃지 않고 경고는 검색당 한 줄이다")
    void 뒤_페이지의_참조도_엔트리를_잃지_않는다() {
        // given — 두 명씩 세 페이지, 두 번째 페이지 요청에 참조
        참조를_보낼_요청 = 2;
        var properties = 이름기반();
        properties.setPageSize(2);

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("u1", "u2", "u3", "u4", "u5");
        assertThat(참조_경고()).hasSize(1);
    }
}
