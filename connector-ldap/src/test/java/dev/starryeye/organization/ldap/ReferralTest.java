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
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultReference;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검색이 referral(검색 결과 참조, RFC 4511 §4.5.3)을 만났을 때(점검 S21, 설계 2026-10-05 §4.2).
 *
 * <p>따라가지 않는다 — 다른 DC 의 주소·자격 증명·DNS 가 필요하다. 다만 Spring 이 {@code PartialResultException} 을 DEBUG 로 삼키면
 * 검색 범위가 위임 서브트리·자식 도메인을 걸칠 때 그 부분이 늘 빠지는데 운영자는 모른다. 경고 한 줄을 남기고, 이미 받은 엔트리와 다음 페이지는 잃지 않는다.
 *
 * <p>UnboundID 인터셉터는 <b>검색 요청 단계</b>에서만 참조를 보낼 수 있다. 페이지 하나가 요청 하나이므로, 직원 검색의 <b>요청마다</b> 참조를 끼운다 —
 * 첫 페이지·가운데·마지막 페이지 모두 참조를 만난다. 그래야 검색당 한 줄(검색 하나의 모든 페이지가 한 줄)과 "페이지를 다시 받지 않음"을 단정할 수 있다.
 * 직원 검색은 필터로 알아본다 — 두 전략의 직원 검색은 베이스가 달라도 필터가 같다.
 */
class ReferralTest extends EmbeddedLdapSupport {

    /** 서버가 받은 직원 검색 요청 수 — 페이지 하나가 요청 하나다. 페이지를 다시 받으면 기대보다 늘어난다 */
    private final AtomicInteger 직원검색수 = new AtomicInteger();
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

                dn: ou=elsewhere,dc=example,dc=com
                objectClass: organizationalUnit
                ou: elsewhere

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: ou=DEV001,ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: DEV001

                dn: uid=d1,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: d1
                cn: D1
                sn: D
                displayName: 부서직원1
                mail: d1@example.com

                dn: uid=d2,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: d2
                cn: D2
                sn: D
                displayName: 부서직원2
                mail: d2@example.com

                dn: uid=d3,ou=DEV001,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: d3
                cn: D3
                sn: D
                displayName: 부서직원3
                mail: d3@example.com
                """;
    }

    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
                if (request.getRequest().getBaseDN().startsWith("ou=outside")) {
                    // 검색 베이스 자체가 참조다 — 실제 서버는 엔트리 없이 결과 코드 10(referral)으로 답한다(RFC 4511 §4.1.10)
                    throw new LDAPException(ResultCode.REFERRAL, "검색 베이스가 이 서버의 이름 공간 밖이다", null,
                            new String[]{"ldap://other-dc.example.com/ou=outside,dc=example,dc=com"});
                }
                if (request.getRequest().getFilter().toString().contains("inetOrgPerson")) {
                    직원검색수.incrementAndGet();
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
        assertThat(직원검색수).hasValue(1);
        assertThat(참조_경고()).hasSize(1).first().asString().contains("ou=people");
    }

    @Test
    @DisplayName("모든 페이지 요청에 referral 이 와도 엔트리를 잃지 않고, 페이지를 다시 받지 않으며, 경고는 검색당 한 줄이다")
    void 모든_페이지의_참조에도_엔트리를_잃지_않고_경고는_검색당_한_줄이다() {
        // given — 두 명씩 세 페이지(2·2·1), 세 요청 모두에 참조
        var properties = 이름기반();
        properties.setPageSize(2);

        // when
        var snapshot = new GroupOfNamesStrategy(properties).read(ldapTemplate);

        // then — 요청이 정확히 셋이다: 쿠키를 잃어 페이지를 다시 받았다면 늘어난다(users 는 같은 키로 덮여 그것을 숨긴다)
        assertThat(snapshot.users()).containsOnlyKeys("u1", "u2", "u3", "u4", "u5");
        assertThat(직원검색수).hasValue(3);
        assertThat(참조_경고()).hasSize(1).first().asString().contains("ou=people");
    }

    @Test
    @DisplayName("DIT 전략의 검색도 같은 길을 탄다 — 모든 페이지에 referral 이 와도 엔트리를 잃지 않고 경고는 한 줄이다")
    void DIT도_참조를_만나도_엔트리를_잃지_않는다() {
        // given — 두 명씩 두 페이지(2·1), 두 요청 모두에 참조
        var properties = 이름기반();
        properties.setPageSize(2);

        // when
        var snapshot = new DitStrategy(properties).read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("d1", "d2", "d3");
        assertThat(직원검색수).hasValue(2);
        assertThat(참조_경고()).hasSize(1).first().asString().contains("ou=company");
    }

    @Test
    @DisplayName("검색이 referral 만 받고 엔트리를 하나도 못 받으면 데이터 오류로 실패한다 — 빈 결과로 읽으면 전원이 빠진 회차가 된다")
    void 참조만_받고_엔트리가_없으면_실패한다() {
        // given — 직원 검색 베이스 아래에는 직원이 없고 참조만 온다
        var properties = 이름기반();
        properties.getGroupOfNames().setUserSearchBase("ou=elsewhere");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isExactlyInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("ou=elsewhere")
                .hasMessageContaining("(objectClass=inetOrgPerson)")
                .hasMessageContaining("referral")
                .hasMessageContaining("Unprocessed Continuation Reference");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("검색 베이스 자체가 referral(결과 코드 10)이면 페이징 검색도 한 번 묻고 데이터 오류로 실패한다 — 이 DC 의 이름 공간 밖이다")
    void 베이스가_참조면_페이징_검색도_실패한다() {
        // given — 페이징(기본 500). 응답에 페이징 컨트롤이 없다
        var properties = 이름기반();
        properties.getGroupOfNames().setUserSearchBase("ou=outside");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isExactlyInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("ou=outside")
                .hasMessageContaining("referral");
        assertThat(참조_경고()).isEmpty();
    }

    @Test
    @DisplayName("검색 베이스 자체가 referral 이면 페이징 없는 검색도 데이터 오류로 실패한다")
    void 베이스가_참조면_페이징_없는_검색도_실패한다() {
        // given
        var properties = 이름기반();
        properties.setPageSize(0);
        properties.getGroupOfNames().setUserSearchBase("ou=outside");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .isExactlyInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("ou=outside")
                .hasMessageContaining("referral");
    }
}
