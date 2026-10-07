package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 쓰기 경합 (쓰기 길 설계 2026-10-07 §3.5·§4·§8). 서버 한 대에 동시 40개가 와도 서버 안 줄이 온 순서대로 차례를 줘 503 이 거의 없고,
 * 버리는 속성만 있는 PATCH(Entra 의 manager)는 락을 잡지 않는다. 시간은 기록만 한다 — DynamoDB Local 은 AWS 와 왕복 시간이 다르다.
 * 직원은 저장소에 직접 심는다(소속·튜플 없음) — 락을 쥐는 시간은 직원 하나를 읽고 저장하는 만큼이다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimWriteContentionScaleTest {

    private static final int 직원_수 = 400;
    private static final int 동시_수 = 40;
    private static final int 버리는_PATCH_수 = 1_000;
    private static final String MANAGER = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager";

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @LocalServerPort int port;
    @Autowired DirectoryStateRepository state;
    @Autowired MeterRegistry registry;

    private WebClient 웹() {
        return WebClient.create("http://127.0.0.1:" + port);
    }

    private static String 아이디(int i) {
        return "w%04d".formatted(i);
    }

    private long 락_대기_수() {
        Timer 대기 = registry.find("scim.lock.wait").timer();
        return 대기 == null ? 0 : 대기.count();
    }

    @Test
    @Order(1)
    @DisplayName("직원 400명을 저장소에 직접 심는다")
    void 심는다() {
        // given — 방금 띄운 저장소에는 직원이 없다

        // when
        Flux.range(0, 직원_수)
                .concatMap(i -> state.saveUser(new DirectoryUser(아이디(i), "ext-" + 아이디(i), 아이디(i), "직원 " + i, null, true)))
                .blockLast(Duration.ofMinutes(5));

        // then
        assertThat(state.findUser(아이디(직원_수 - 1)).block()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("서버 한 대에 동시 40개로 400건 — 서버 안 줄이 온 순서대로 차례를 줘 503 이 1% 이하다(점검 P3, 설계 2026-10-07 §3.5)")
    void 동시_40개() {
        // given
        WebClient 웹 = 웹();
        long 시작 = System.currentTimeMillis();

        // when — 직원마다 표시명을 바꾼다. 락 안에서 직원을 읽고 저장한다
        Map<Integer, Integer> 응답 = Flux.range(0, 직원_수)
                .flatMap(i -> 웹.patch().uri("/scim/v2/Users/" + 아이디(i))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"replace","path":"displayName","value":"바뀐 %d"}]}
                                """.formatted(i))
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                        .map(상태 -> Map.entry(i, 상태)), 동시_수)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block(Duration.ofMinutes(5));
        long 걸린_ms = System.currentTimeMillis() - 시작;

        // then
        Map<Integer, Integer> 집계 = new TreeMap<>();
        응답.values().forEach(상태 -> 집계.merge(상태, 1, Integer::sum));
        Timer 대기 = registry.find("scim.lock.wait").timer();
        System.out.printf("동시 %d × %d건: %s, %,dms(%.1f건/초), 락 대기 평균 %.0fms·최대 %.0fms%n",
                동시_수, 직원_수, 집계, 걸린_ms, 직원_수 * 1000.0 / 걸린_ms,
                대기.mean(TimeUnit.MILLISECONDS), 대기.max(TimeUnit.MILLISECONDS));
        assertThat(집계.keySet()).as("200 과 503 이외의 응답이 나왔다").isSubsetOf(200, 503);
        assertThat(집계.getOrDefault(503, 0)).as("503 은 1% 이하다").isLessThanOrEqualTo(직원_수 / 100);
        응답.forEach((i, 상태) -> {
            if (상태 == 200) {
                assertThat(state.findUser(아이디(i)).block().displayName()).isEqualTo("바뀐 " + i);
            }
        });
    }

    @Test
    @Order(3)
    @DisplayName("버리는 속성(Entra 의 manager)만 있는 PATCH 1,000건은 락을 잡지 않는다 — 모두 200 이고 락 대기 기록이 늘지 않는다(설계 2026-10-07 §4)")
    void 버리는_속성만_있는_PATCH_는_락을_잡지_않는다() {
        // given
        WebClient 웹 = 웹();
        long 전 = 락_대기_수();
        long 시작 = System.currentTimeMillis();

        // when
        Map<Integer, Long> 집계 = Flux.range(0, 버리는_PATCH_수)
                .flatMap(i -> 웹.patch().uri("/scim/v2/Users/" + 아이디(i % 직원_수))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"replace","path":"%s","value":{"value":"%s"}}]}
                                """.formatted(MANAGER, 아이디((i + 1) % 직원_수)))
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())), 16)
                .collect(Collectors.groupingBy(상태 -> 상태, TreeMap::new, Collectors.counting()))
                .block(Duration.ofMinutes(5));
        long 걸린_ms = System.currentTimeMillis() - 시작;

        // then
        System.out.printf("버리는 속성만 있는 PATCH %,d건(동시 16): %s, %,dms%n", 버리는_PATCH_수, 집계, 걸린_ms);
        assertThat(집계).containsOnlyKeys(200);
        assertThat(락_대기_수()).as("락을 잡지 않았다").isEqualTo(전);
    }
}
