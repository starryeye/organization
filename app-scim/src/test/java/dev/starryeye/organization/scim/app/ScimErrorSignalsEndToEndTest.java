package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.errors.ApiException;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.TemporaryFailureRecognizer;
import dev.starryeye.organization.scim.TemporaryFailureClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 오류 신호가 실제 컨텍스트와 인프라 위에서 IdP 에게 닿는지 본다(설계 2026-10-05).
 *
 * <p>단위 테스트는 각 조각을 따로 본다 — 라우터의 번역, 락의 용도별 대기 시간, 분류기와 인식기. 여기서 확인하는 것은 다음이다.
 * <ul>
 *   <li>락을 쥔 쪽의 용도가 실제 DynamoDB 의 조건 실패 응답을 거쳐 {@code Retry-After} 가 된다(재적재 60초).</li>
 *   <li>인식기 빈 둘이 앱에 실린다(빈 존재). 그 둘로 조립한 분류기가 일시 장애와 버그를 가른다(조립 분류기).</li>
 *   <li>413 문구의 한도가 기본값 262144 와 같다. 이 앱은 {@code spring.codec.max-in-memory-size} 를 정하지 않아 두 값이 같으므로,
 *       한도를 설정에서 읽는지까지는 가르지 못한다.</li>
 *   <li>Bulk 는 501 이다.</li>
 * </ul>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimErrorSignalsEndToEndTest {

    private static final MediaType SCIM_JSON = MediaType.valueOf("application/scim+json");

    /** app-scim 은 {@code spring.codec.max-in-memory-size} 를 정하지 않는다 — 스프링 부트 기본 256KB */
    private static final int 본문_한도 = 256 * 1024;

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired MutationLock lock;
    @Autowired ApplicationContext context;

    private static String 직원_본문(String userName) {
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","displayName":"%s","active":true}""".formatted(userName, userName);
    }

    @Test
    @DisplayName("재적재가 변경 락을 쥐고 있으면 SCIM 쓰기는 503 이고 Retry-After 가 60초다(점검 M4·S2)")
    void 재적재_중의_쓰기는_60초_뒤에_다시() {
        // given — 쓰기는 락을 얻으려고 3초 기다려 본 뒤에 답한다. 기본 응답 제한(5초)에 빠듯하게 기대지 않는다
        // (락을 쥐기 전에 만든다 — 만드는 데 실패해도 리스가 새지 않는다)
        var 느긋한 = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        // 재적재 용도로 락을 쥔다
        var lease = lock.acquire(MutationLock.LockPurpose.REBUILD).block(Duration.ofSeconds(10));

        try {
            // when, then — 쓰기는 기다려 본 뒤 503 이다. 마지막 실패가 쥔 쪽의 용도를 알려 주므로 쓰기 경합의 2초가 아니라 60초다
            느긋한.post().uri("/scim/v2/Users").contentType(SCIM_JSON).bodyValue(직원_본문("kim"))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                    .expectHeader().contentType(SCIM_JSON)
                    .expectBody()
                    .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                    .jsonPath("$.status").isEqualTo("503")
                    .jsonPath("$.detail").isEqualTo("일시적으로 처리할 수 없습니다 — 60초 뒤 다시 보내 주세요");
        } finally {
            lock.release(lease).block(Duration.ofSeconds(10));
        }

        // when, then — 503 은 재시도 신호다. 락이 풀린 뒤 같은 요청은 성공한다
        client.post().uri("/scim/v2/Users").contentType(SCIM_JSON).bodyValue(직원_본문("kim"))
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    @DisplayName("한도를 넘는 PATCH 본문은 413 이고 한도를 알려 준다(점검 M3)")
    void 큰_본문은_413() {
        // given — 멤버 값 6,000개(한 줄 49바이트, 합쳐 약 294KB 로 256KB 를 넘는다).
        // 핸들러는 조직을 찾기 전에 본문부터 읽으므로 없는 조직 id 로 보내도 404 가 아니라 413 에 닿는다
        String 멤버들 = IntStream.range(0, 6_000)
                .mapToObj(i -> "{\"value\":\"00000000-0000-4000-8000-%012d\"}".formatted(i))
                .collect(Collectors.joining(","));
        String 본문 = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[%s]}]}""".formatted(멤버들);
        assertThat(본문.getBytes(StandardCharsets.UTF_8).length)
                .as("본문이 한도를 넘지 않으면 이 테스트는 413 을 시험하지 못한다").isGreaterThan(본문_한도);

        // when, then
        client.patch().uri("/scim/v2/Groups/any").contentType(SCIM_JSON).bodyValue(본문)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectHeader().contentType(SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("413")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .as("설정에서 읽은 한도와 나눠 보낼 방법을 알려 준다")
                        .contains(String.valueOf(본문_한도)).contains("PATCH"));
    }

    @Test
    @DisplayName("Bulk 는 501 이다 — 지원하지 않음을 SCIM Error 로 알린다(점검 S7)")
    void Bulk_는_501() {
        // when, then
        client.post().uri("/scim/v2/Bulk").contentType(SCIM_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectHeader().contentType(SCIM_JSON)
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("501");
    }

    @Test
    @DisplayName("앱은 두 어댑터의 일시 장애 인식기를 모두 싣는다")
    void 인식기_둘이_실린다() {
        // when
        var 인식기들 = context.getBeansOfType(TemporaryFailureRecognizer.class);

        // then
        assertThat(인식기들).containsKeys("dynamoDbTemporaryFailures", "openFgaTemporaryFailures");
    }

    @Test
    @DisplayName("앱이 실은 두 인식기로 조립한 분류기가 일시 장애와 버그를 가른다")
    void 조립한_분류기가_일시_장애와_버그를_가른다() throws Exception {
        // given — 앱 컨텍스트의 인식기 빈 전부로 분류기를 조립한다
        var 분류기 = new TemporaryFailureClassifier(
                List.copyOf(context.getBeansOfType(TemporaryFailureRecognizer.class).values()));
        // SDK 가 2xx 본문 해석에 실패하면 JacksonException 을 ApiException 으로 감싼다 — 그 실제 실패
        JsonProcessingException 해석실패 = null;
        try {
            new ObjectMapper().readTree("{");
        } catch (JsonProcessingException e) {
            해석실패 = e;
        }
        assertThat(해석실패).as("깨진 JSON 은 해석에 실패해야 한다").isNotNull();

        // when
        var 연결실패 = 분류기.재시도_대기(new ApiException(new ConnectException("refused")));
        var 해석실패_감싼것 = 분류기.재시도_대기(new ApiException(해석실패));
        var DynamoDB_서버오류 = 분류기.재시도_대기(DynamoDbException.builder().statusCode(500).message("x").build());
        var DynamoDB_검증오류 = 분류기.재시도_대기(
                DynamoDbException.builder().statusCode(400).message("ValidationException").build());

        // then — 네트워크 실패와 서버 오류는 일시 장애(10초), 응답 해석 실패와 검증 오류는 버그다
        assertThat(연결실패).contains(Duration.ofSeconds(10));
        assertThat(해석실패_감싼것).isEmpty();
        assertThat(DynamoDB_서버오류).contains(Duration.ofSeconds(10));
        assertThat(DynamoDB_검증오류).isEmpty();
    }
}
