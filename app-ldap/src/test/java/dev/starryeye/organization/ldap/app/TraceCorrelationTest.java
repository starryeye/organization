package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.core.fixture.Containers;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * app-ldap 의 로그 상관관계를 못박는다.
 *
 * <p>app-scim 쪽과 같은 것을 보지만 <b>경로가 다르다</b>. LDAP 읽기는 블로킹이라
 * {@code subscribeOn(Schedulers.boundedElastic())} 으로 격리돼 있다 — SCIM 경로에는
 * 없는 스레드 전환이고, 자동 전파가 여기서도 듣는지는 별도로 확인해야 한다.
 *
 * <p>그리고 이 앱은 <b>통째로 예약 작업</b>이다. 하루 1회 동기화가 존재 이유이므로,
 * 예약 경로의 traceId 가 비면 이 기능은 정작 필요한 곳에서 아무것도 하지 않는 셈이 된다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceCorrelationTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    static InMemoryDirectoryServer LDAP;

    static final String LDIF = """
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

            dn: uid=kim,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: kim
            cn: Kim Chulsoo
            sn: Kim
            displayName: 김철수
            mail: kim@example.com

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("trace", 0));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(
                new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired SyncScheduler scheduler;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void 로그를_가로챈다() {
        logger = (Logger) LoggerFactory.getLogger("dev.starryeye.organization");
        appender = new ListAppender<>();
        // ListAppender 의 기본 목록은 ArrayList 이고 appender 자신의 잠금으로만 쓴다. 목록 자체로 잠그는 목록으로 바꿔야
        // 아래 로그() 의 복사가 작업 스레드의 쓰기와 겹치지 않는다
        appender.list = Collections.synchronizedList(new ArrayList<>());
        appender.start();
        logger.addAppender(appender);
    }

    /** 지금까지 찍힌 로그의 사본. 작업은 요청과 떼어 돌아(SyncJobs) 단언하는 동안에도 로그를 더할 수 있다. */
    private List<ILoggingEvent> 로그() {
        synchronized (appender.list) {
            return new ArrayList<>(appender.list);
        }
    }

    @AfterEach
    void 원복한다() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("LDAP 읽기가 boundedElastic 으로 넘어가도 같은 traceId 가 이어진다")
    void 블로킹_격리를_넘어_traceId가_이어진다() {
        // when — 전체 동기화는 LDAP 읽기(boundedElastic) → 튜플 변환 → OpenFGA/DynamoDB 쓰기까지 탄다.
        // 본체는 요청과 떼어 돌지만(SyncJobs) 요청의 컨텍스트를 이어받으므로 같은 traceId 여야 한다
        SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — LDAP 읽기 완료 로그는 boundedElastic 스레드에서 찍힌다.
        // 이 줄에 traceId 가 있으면 블로킹 격리를 넘어 전파된 것이다.
        var ldap로그 = 로그().stream()
                .filter(event -> event.getFormattedMessage().contains("LDAP 에서 직원"))
                .findFirst();
        assertThat(ldap로그).as("LDAP 읽기 로그를 찾지 못하면 이 테스트는 아무것도 검증하지 못한다")
                .isPresent();
        assertThat(ldap로그.get().getMDCPropertyMap().get("traceId"))
                .as("boundedElastic 으로 넘어간 뒤의 traceId")
                .isNotNull()
                .isNotBlank();

        // then — 요청 전체가 하나의 trace 로 묶인다
        var traceIds = 로그().stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .distinct()
                .toList();
        assertThat(traceIds).doesNotContainNull().hasSize(1);
    }

    @Test
    @DisplayName("예약 동기화 로그에도 traceId 가 붙는다 — 이 앱의 본체가 예약 작업이다")
    void 예약_동기화도_traceId를_가진다() {
        // when
        scheduler.전체동기화();

        // then
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var 완료로그 = 로그().stream()
                    .filter(event -> event.getFormattedMessage().contains("스케줄 동기화 완료"))
                    .findFirst();
            assertThat(완료로그).as("예약 동기화가 완료 로그를 남겨야 한다").isPresent();
            assertThat(완료로그.get().getMDCPropertyMap().get("traceId"))
                    .as("예약 동기화 로그의 traceId. 비어 있으면 스케줄러가 관측을 열지 않은 것이다")
                    .isNotNull()
                    .isNotBlank();
        });
    }
}
