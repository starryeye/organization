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
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SearchResultReference;
import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한 사람 읽기가 referral 을 만났을 때(설계 2026-11-28 V3·§5.2). 한 사람 검색에서 엔트리 0개는 정상이므로 id 검색의 참조는 오류가 아니고 DN 다시 읽기로 간다.
 * DN 다시 읽기가 엔트리 없이 참조만 받으면 그 엔트리는 다른 서버에 있으므로 없음이 아니다. 로그·예외 문구에는 직원 id·DN 이 없다.
 */
class ReadAccountReferralTest extends EmbeddedLdapSupport {

    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    /** 다른 문구에 우연히 들어 있지 않을 직원 id */
    private static final String ID = "zq7x4711";

    /** id 검색(하위 범위)에 엔트리 없이 참조를 보낸다 */
    private final AtomicBoolean id검색에_참조 = new AtomicBoolean();
    /** DN 다시 읽기(base 범위)가 참조(결과 코드 10)로 끝난다 */
    private final AtomicBoolean DN읽기에_참조 = new AtomicBoolean();
    private ListAppender<ILoggingEvent> 로그;
    private Level 이전_수준;

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

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: uid=other,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: other
                cn: other
                sn: other

                dn: uid=other,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: other
                cn: other
                sn: other
                """;
    }

    @Override
    protected void 서버설정을_고친다(InMemoryDirectoryServerConfig config) {
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
                if (request.getRequest().getScope() == SearchScope.BASE) {
                    if (DN읽기에_참조.get()) {
                        throw new LDAPException(ResultCode.REFERRAL, "이 서버의 이름 공간 밖이다", null,
                                new String[]{"ldap://other-dc.example.com/ou=people,dc=example,dc=com"});
                    }
                } else if (id검색에_참조.get()) {
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
        Logger logger = (Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap");
        이전_수준 = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(로그);
    }

    @AfterEach
    void 로그를_놓는다() {
        Logger logger = (Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap");
        logger.detachAppender(로그);
        logger.setLevel(이전_수준);
    }

    private LdapProperties 설정() {
        var properties = 이름기반();
        properties.getGroupOfNames().setUserSearchBase("ou=people");
        properties.getDit().setRootDn("ou=company");
        return properties;
    }

    private void 로그에_직원_값이_없다() {
        assertThat(로그.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(m -> m.contains(ID));
    }

    @Test
    @DisplayName("groupOfNames — id 검색이 엔트리 없이 참조만 받아도 오류가 아니다 — DN 다시 읽기(32)로 가 ABSENT 다")
    // 깨뜨릴 변경: id 검색에 PagedLdapSearch 를 다시 쓰면 0건 + referral 이 DirectoryDataException 이 된다
    void groupOfNames_id_검색의_참조는_DN_다시_읽기로_간다() {
        // given
        id검색에_참조.set(true);
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, ID, "uid=" + ID + ",ou=people," + BASE_DN);

        // then
        assertThat(상태).isEqualTo(AccountState.ABSENT);
        로그에_직원_값이_없다();
    }

    @Test
    @DisplayName("DIT — id 검색이 엔트리 없이 참조만 받아도 오류가 아니다 — DN 다시 읽기(32)로 가 ABSENT 다")
    // 깨뜨릴 변경: id 검색에 PagedLdapSearch 를 다시 쓰면 0건 + referral 이 DirectoryDataException 이 된다
    void DIT_id_검색의_참조는_DN_다시_읽기로_간다() {
        // given
        id검색에_참조.set(true);
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, ID, "uid=" + ID + ",ou=company," + BASE_DN);

        // then
        assertThat(상태).isEqualTo(AccountState.ABSENT);
        로그에_직원_값이_없다();
    }

    @Test
    @DisplayName("groupOfNames — DN 다시 읽기가 엔트리 없이 참조만 받으면 없음이 아니라 데이터 오류다")
    // 깨뜨릴 변경: DN 다시 읽기의 참조를 무시하고 빈 결과로 보면 ABSENT 가 돼 다른 서버에 있는 활성 직원을 거둔다
    void groupOfNames_DN_다시_읽기의_참조는_없음이_아니다() {
        // given
        DN읽기에_참조.set(true);
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);
        String dn = "uid=" + ID + ",ou=people," + BASE_DN;

        // when, then
        assertThatThrownBy(() -> strategy.readAccount(ldapTemplate, ID, dn))
                .isExactlyInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("referral")
                .hasMessageNotContaining(ID);
        로그에_직원_값이_없다();
    }

    @Test
    @DisplayName("DIT — DN 다시 읽기가 엔트리 없이 참조만 받으면 없음이 아니라 데이터 오류다")
    // 깨뜨릴 변경: DN 다시 읽기의 참조를 무시하고 빈 결과로 보면 ABSENT 가 돼 다른 서버에 있는 활성 직원을 거둔다
    void DIT_DN_다시_읽기의_참조는_없음이_아니다() {
        // given
        DN읽기에_참조.set(true);
        var strategy = new DitStrategy(설정(), 고정시계);
        String dn = "uid=" + ID + ",ou=company," + BASE_DN;

        // when, then
        assertThatThrownBy(() -> strategy.readAccount(ldapTemplate, ID, dn))
                .isExactlyInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("referral")
                .hasMessageNotContaining(ID);
        로그에_직원_값이_없다();
    }

    @Test
    @DisplayName("엔트리를 받은 검색에 참조가 섞여도 받은 엔트리로 판정한다")
    // 깨뜨릴 변경: PartialResultException 이 올라올 때 이미 받은 엔트리를 버리면 ACTIVE 가 ABSENT 가 된다
    void 참조가_섞여도_받은_엔트리로_판정한다() {
        // given
        id검색에_참조.set(true);
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var 상태 = strategy.readAccount(ldapTemplate, "other", null);

        // then
        assertThat(상태).isEqualTo(AccountState.ACTIVE);
        로그에_직원_값이_없다();
    }
}
