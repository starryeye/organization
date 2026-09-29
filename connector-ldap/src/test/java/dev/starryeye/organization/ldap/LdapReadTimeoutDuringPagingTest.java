package dev.starryeye.organization.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldif.LDIFReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.LdapContextSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP 읽기 타임아웃(점검 C5) — <b>페이징 도중</b> 응답이 끊기는 경우.
 *
 * <p>{@link LdapTimeoutTest} 의 침묵 서버 시나리오는 bind 응답 대기에서 멈춘다 — 그 대기는
 * {@code connect-timeout} 이 잰다(JDK-8194264). 그러나 C5 가 가리키는 실제 사고는 다르다:
 * bind 는 성공하고, 페이징 첫 페이지도 정상 응답한 뒤, <b>다음 페이지를 기다리는 도중</b>
 * 연결이 조용히 죽는다. 그 대기를 잘라야 하는 것이 {@code read-timeout} 이고, 여기서
 * 그 경로를 고정한다.
 */
class LdapReadTimeoutDuringPagingTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String BIND_DN = "cn=admin," + BASE_DN;
    private static final String BIND_PASSWORD = "adminpassword";

    /** 페이지 크기(1)보다 많아야 다음 페이지가 생긴다. */
    private static final int 직원수 = 3;

    @Test
    @DisplayName("페이징 도중 다음 페이지 응답이 없으면 read-timeout 으로 실패하고, 일시 장애로 보고 다시 읽는다")
    void 페이징_도중_응답이_없으면_읽기_타임아웃으로_끝난다() throws Exception {
        이어받기_요청을_붙잡는_인터셉터 interceptor = new 이어받기_요청을_붙잡는_인터셉터();

        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
        config.setSchema(null);
        config.addInMemoryOperationInterceptor(interceptor);

        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true,
                new LDIFReader(new ByteArrayInputStream(ldif().getBytes(StandardCharsets.UTF_8))));
        server.startListening();

        try {
            LdapProperties properties = new LdapProperties();
            properties.setUrl("ldap://localhost:" + server.getListenPort());
            properties.setBaseDn(BASE_DN);
            properties.setBindDn(BIND_DN);
            properties.setBindPassword(BIND_PASSWORD);
            // 첫 페이지(1건)는 응답하고 다음 페이지에서 멈춘다 — "페이징 도중"을 재현하는 장치.
            properties.setPageSize(1);
            // connect-timeout 은 기본값 그대로 둔다 — bind 는 정상적으로 끝나야 이 시나리오가
            // "페이징 도중"이 된다. read-timeout 만 줄인다.
            properties.setReadTimeout(Duration.ofSeconds(1));
            properties.setMaxRetries(1);

            LdapConfig ldapConfig = new LdapConfig();
            LdapContextSource contextSource = ldapConfig.ldapContextSource(properties);
            contextSource.afterPropertiesSet();
            var source = ldapConfig.ldapDirectorySnapshotSource(
                    ldapConfig.ldapTemplate(contextSource),
                    ldapConfig.ldapMappingStrategy(properties, Clock.systemUTC()),
                    properties);

            try {
                // when, then — read-timeout(1초)이 실제로 끊는다는 것을 메시지의 숫자로 못박는다.
                assertThatThrownBy(() -> source.fetchAll().block(Duration.ofSeconds(20)))
                        .hasStackTraceContaining("timeout used: 1000 ms");

                // 시도마다 새 커넥션이라 쿠키를 이어받지 못하고 첫 페이지부터 다시 청한다 —
                // 최초 시도 1회 + 재시도 1회 = 첫 페이지 요청 두 번. 그 각각의 다음 페이지(이어받기)
                // 요청에서 막히므로 이어받기 요청도 시도마다 한 번, 총 두 번이다.
                assertThat(interceptor.첫페이지_요청수.get()).as("시도마다 첫 페이지 요청 한 번, 총 두 번").isEqualTo(2);
                assertThat(interceptor.이어받기_요청수.get()).as("시도마다 이어받기 요청에서 막힌다, 총 두 번").isEqualTo(2);
            } finally {
                interceptor.놓아준다();
            }
        } finally {
            server.shutDown(true);
        }
    }

    private static String ldif() {
        String 사람들 = IntStream.range(0, 직원수)
                .mapToObj(i -> """
                        dn: uid=user%03d,ou=people,dc=example,dc=com
                        objectClass: inetOrgPerson
                        uid: user%03d
                        cn: User %03d
                        sn: User
                        displayName: 직원%03d
                        mail: user%03d@example.com
                        """.formatted(i, i, i, i, i))
                .collect(Collectors.joining("\n"));

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

                """ + 사람들;
    }

    /**
     * 이어받기(paged-results 쿠키가 비어있지 않은) 검색 요청만 붙잡는다. 첫 페이지(쿠키가
     * 비어있는 요청)는 정상 응답해야 "페이징 도중"이 재현된다 — bind 단계에서 멈추면
     * {@link LdapTimeoutTest} 와 같은 시나리오가 돼 버린다.
     */
    private static final class 이어받기_요청을_붙잡는_인터셉터 extends InMemoryOperationInterceptor {

        private final CountDownLatch 빗장 = new CountDownLatch(1);
        private final AtomicInteger 첫페이지_요청수 = new AtomicInteger();
        private final AtomicInteger 이어받기_요청수 = new AtomicInteger();

        @Override
        public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
            Control raw = request.getRequest().getControl(SimplePagedResultsControl.PAGED_RESULTS_OID);
            if (raw != null && 이어받기_쿠키인가(raw)) {
                이어받기_요청수.incrementAndGet();
                try {
                    빗장.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                첫페이지_요청수.incrementAndGet();
            }
        }

        private static boolean 이어받기_쿠키인가(Control raw) throws LDAPException {
            SimplePagedResultsControl paged = raw instanceof SimplePagedResultsControl 실린_그대로
                    ? 실린_그대로
                    : (SimplePagedResultsControl) Control.decode(raw.getOID(), raw.isCritical(), raw.getValue());
            return paged.getCookie().getValueLength() > 0;
        }

        void 놓아준다() {
            빗장.countDown();
        }
    }
}
