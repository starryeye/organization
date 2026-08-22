package dev.starryeye.organization.scim.app;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 로그에 traceId 가 실제로 붙는지 못박는다.
 *
 * <p><b>왜 이 테스트가 있어야 하는가.</b> 이 기능은 자바 코드가 아니라 설정 한 줄
 * ({@code spring.reactor.context-propagation: auto})에 걸려 있다. 그 줄이 없으면
 * 컴파일도 되고 테스트도 다 통과하는데 <b>로그의 traceId 자리만 조용히 빈칸</b>이 된다.
 * 사람이 알아채는 시점은 장애 나서 로그를 뒤질 때다. 그래서 자동으로 잡아야 한다.
 *
 * <p>두 경로를 각각 본다. 원리가 다르고, 실제로 하나가 되는데 다른 하나가 안 됐다.
 * <ul>
 *   <li><b>HTTP</b> — 들어오는 요청이 trace 를 시작한다. 설정만으로 해결된다.
 *   <li><b>예약 작업</b> — 시작해줄 요청이 없다. {@code SyncScheduler}/{@code ArchiveScheduler}
 *       가 직접 관측을 열어 Reactor Context 에 실어야 한다.
 * </ul>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceCorrelationTest {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired ArchiveScheduler scheduler;

    /** 애플리케이션 로그를 통째로 가로챈다. 어느 클래스가 찍든 MDC 를 볼 수 있어야 한다. */
    private static final String 앱_로거 = "dev.starryeye.organization";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void 로그를_가로챈다() {
        logger = (Logger) LoggerFactory.getLogger(앱_로거);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void 원복한다() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /** 이벤트 루프가 아닌 스레드에서 찍힌 로그만 고른다 — 스레드를 갈아탄 뒤에도 살아있는지가 핵심이다. */
    private Optional<ILoggingEvent> 다른_스레드에서_찍힌_로그(List<ILoggingEvent> events, String 요청스레드) {
        return events.stream()
                .filter(event -> !event.getThreadName().equals(요청스레드))
                .findFirst();
    }

    @Test
    @DisplayName("SCIM 요청 처리 중 스레드가 바뀌어도 모든 로그에 같은 traceId 가 붙는다")
    void HTTP_경로는_스레드를_넘어_traceId를_유지한다() {
        // given — type 없는 멤버를 넣으면 StateMemberTypeResolver 가 경고를 남긴다.
        // 그 경고는 DynamoDB 조회 뒤에 찍히므로 AWS SDK 응답 스레드에서 나온다 —
        // 즉 요청 스레드가 아닌 곳이고, 전파가 안 되면 여기서 traceId 가 사라진다.
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "displayName":"추적팀",
                 "members":[{"value":"trace.member"}]}
                """;

        // when
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.valueOf("application/scim+json"))
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        // then
        assertThat(appender.list).as("요청 처리 중 로그가 하나도 없으면 이 테스트는 아무것도 검증하지 못한다")
                .isNotEmpty();

        var traceIds = appender.list.stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .distinct()
                .toList();

        assertThat(traceIds)
                .as("모든 로그가 같은 traceId 하나를 달고 있어야 한다. null 이 섞이면 전파가 끊긴 것이다")
                .doesNotContainNull()
                .hasSize(1);
        assertThat(traceIds.get(0)).isNotBlank();
    }

    @Test
    @DisplayName("예약 작업 로그에도 traceId 가 붙는다 — 시작해줄 HTTP 요청이 없어도")
    void 예약_작업도_traceId를_가진다() {
        // when — 스케줄러를 직접 호출한다. cron 을 기다릴 이유가 없고,
        // 검증 대상은 '요청 없이 시작된 작업' 이라는 성질 자체다.
        scheduler.만료스냅샷정리();

        // then — 던져놓고 반환하는 구조라 로그가 뜰 때까지 기다린다
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var 정리로그 = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("만료 스냅샷 정리 완료"))
                    .findFirst();
            assertThat(정리로그).as("예약 작업이 완료 로그를 남겨야 한다").isPresent();
            assertThat(정리로그.get().getMDCPropertyMap().get("traceId"))
                    .as("예약 작업 로그의 traceId. 비어 있으면 스케줄러가 관측을 열지 않은 것이다")
                    .isNotNull()
                    .isNotBlank();
        });
    }
}
