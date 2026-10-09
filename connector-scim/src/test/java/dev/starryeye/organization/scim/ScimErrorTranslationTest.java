package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 예외가 SCIM Error 응답으로 번역되는 규칙만 본다(설계 2026-10-05 §3.4) — 핸들러 대신 예외를 던지는 작은 라우트로.
 */
class ScimErrorTranslationTest {

    private static final long 본문_한도 = 262144;

    private static WebTestClient.ResponseSpec 번역한다(Throwable 예외) {
        return 번역한다(예외, TemporaryFailureClassifier.표지만());
    }

    private static WebTestClient.ResponseSpec 번역한다(Throwable 예외, TemporaryFailureClassifier 분류기) {
        var 라우트 = RouterFunctions.route()
                .GET("/boom", request -> ScimRouter.toScimError(예외, 분류기, 본문_한도))
                .build();
        return WebTestClient.bindToRouterFunction(라우트).build().get().uri("/boom").exchange();
    }

    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 503 이고 Retry-After 는 60초다 — 쥔 쪽의 용도 이름은 응답에 싣지 않는다")
    void 긴_작업이_락을_쥐면_503_60초다() {
        // given
        var 예외 = LockUnavailableException.잡혀_있다(LockPurpose.REBUILD);

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("503")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .contains("60초")
                        .doesNotContain("REBUILD"));
    }

    @Test
    @DisplayName("쓰기 차단기가 멈춘 것도 503 이고 Retry-After 는 10초다")
    void 쓰기_차단기는_503_10초다() {
        // given
        var 예외 = new TupleWriteAbortedException("연속 실패로 멈췄다", TupleWriteResult.empty());

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectBody()
                .jsonPath("$.status").isEqualTo("503")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("10초"));
    }

    @Test
    @DisplayName("일시 장애 표지의 메시지는 응답에 싣지 않는다 — 하위 시스템이 준 문자열이 인증 없는 엔드포인트로 나간다")
    void 표지의_메시지는_응답에_없다() {
        // given
        var 예외 = new TemporaryFailureException("store 01HXSECRET 을 찾지 못함 com.openfga.sdk.Foo", Duration.ofSeconds(10));

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .doesNotContain("01HXSECRET")
                        .doesNotContain("com.openfga"));
    }

    @Test
    @DisplayName("어댑터 인식기가 알아본 라이브러리 예외도 503 이고 그 메시지는 응답에 싣지 않는다")
    void 인식기가_알아본_예외는_503이고_메시지는_없다() {
        // given
        class 라이브러리오류 extends RuntimeException {
            라이브러리오류() {
                super("com.vendor.ThrottledException: 내부 요청 번호 7788");
            }
        }
        var 분류기 = new TemporaryFailureClassifier(List.of(e -> e instanceof 라이브러리오류
                ? Optional.of(Duration.ofSeconds(10)) : Optional.empty()));

        // when, then
        번역한다(new RuntimeException("감쌈", new 라이브러리오류()), 분류기)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .doesNotContain("com.vendor")
                        .doesNotContain("7788"));
    }

    @Test
    @DisplayName("1초보다 짧은 대기는 Retry-After 0 이 아니라 1초다 — 헤더와 문구가 같은 값을 쓴다")
    void 짧은_대기는_1초로_올린다() {
        // given
        var 예외 = new TemporaryFailureException("잠깐", Duration.ofMillis(200));

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("1초 뒤"));
    }

    @Test
    @DisplayName("재시도 초는 올림이고 최소 1이다")
    void 재시도_초는_올림이고_최소_1이다() {
        // when, then
        assertThat(ScimRouter.재시도_초(Duration.ofMillis(1500))).isEqualTo(2);
        assertThat(ScimRouter.재시도_초(Duration.ofMillis(200))).isEqualTo(1);
        assertThat(ScimRouter.재시도_초(Duration.ZERO)).isEqualTo(1);
        assertThat(ScimRouter.재시도_초(Duration.ofSeconds(60))).isEqualTo(60);
    }

    @Test
    @DisplayName("부분 실패(ScimException 503)는 자기 문구에 id 를 담아 돌려주고 Retry-After 를 단다")
    void 부분_실패_예외는_자기_문구로_503이다() {
        // given
        var 예외 = ScimException.temporarilyUnavailable("일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: kim", Duration.ofSeconds(10));

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectBody()
                .jsonPath("$.status").isEqualTo("503")
                .jsonPath("$.detail").isEqualTo("일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: kim");
    }

    @Test
    @DisplayName("모르는 예외는 500 이고 Retry-After 가 없다 — 버그다")
    void 모르는_예외는_500이다() {
        // when, then
        번역한다(new NullPointerException("내부 필드 com.x.Y"))
                .expectStatus().isEqualTo(500)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo("500")
                .jsonPath("$.detail").isEqualTo("내부 오류가 발생했습니다");
    }

    @Test
    @DisplayName("본문이 버퍼 한도를 넘으면 413 이다 — 한도와 PATCH 로 나눠 보내라는 안내를 담고 scimType 은 없다")
    void 큰_본문은_413이다() {
        // given — DecodingException 이 DataBufferLimitException 을 감싸 온다
        var 예외 = new DecodingException("JSON decoding error",
                new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144"));

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(413)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.status").isEqualTo("413")
                .jsonPath("$.scimType").doesNotExist()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .contains("262144")
                        .contains("PATCH"));
    }

    @Test
    @DisplayName("한도 때문이 아닌 해석 실패는 400 invalidSyntax 다 — 깨진 JSON 은 입출력 실패가 아니므로 Retry-After 가 없다")
    void 해석_실패는_400이다() {
        // given — 실제 파서가 던진 JsonParseException(IOException 의 하위 타입)
        Throwable 파싱_실패 = catchThrowable(() -> new ObjectMapper().readValue("{\"userName\":", Map.class));

        // when, then
        번역한다(new DecodingException("JSON decoding error", 파싱_실패))
                .expectStatus().isEqualTo(400)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax")
                .jsonPath("$.detail").isEqualTo("요청 본문을 해석할 수 없습니다");
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 은 415 이고 문구에 자바 클래스 이름이 없다 — 받는 형식을 알려준다")
    void 지원하지_않는_형식은_415다() {
        // given
        var 예외 = new UnsupportedMediaTypeStatusException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(415)
                .expectBody()
                .jsonPath("$.status").isEqualTo("415")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .contains("application/scim+json")
                        .doesNotContain("org.springframework")
                        .doesNotContain("Exception"));
    }

    @Test
    @DisplayName("WebFlux 가 정한 그 밖의 상태는 그대로 쓰되 Spring 의 reason 은 싣지 않는다")
    void 그_밖의_상태는_reason_없이_돌려준다() {
        // given
        var 예외 = new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "내부 이유 com.x.Y");

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(406)
                .expectBody()
                .jsonPath("$.status").isEqualTo("406")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).doesNotContain("com.x.Y"));
    }

    @Test
    @DisplayName("실제 라우트에서 한도를 넘는 본문을 보내면 413 이다 — 한도는 라우트에 주입한 값이다")
    void 라우트에서_큰_본문은_413이다() {
        // given — 코덱 한도 100바이트, 번역 한도도 같은 값
        var state = new FakeStateRepository();
        var client = WebTestClient.bindToRouterFunction(실제_라우트(state, 100))
                .handlerStrategies(HandlerStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(100)).build())
                .build();
        String 큰_본문 = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"kim\",\"displayName\":\""
                + "가".repeat(200) + "\"}";

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(큰_본문)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("100").contains("PATCH"));

        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("실제 라우트에서 깨진 JSON 본문을 보내면 400 invalidSyntax 다 — 503 으로 재시도를 부르지 않는다")
    void 라우트에서_깨진_JSON은_400이다() {
        // given
        var state = new FakeStateRepository();
        var client = WebTestClient.bindToRouterFunction(실제_라우트(state, 본문_한도)).build();

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(ScimRouter.SCIM_JSON).bodyValue("{\"userName\":")
                .exchange()
                .expectStatus().isEqualTo(400)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax")
                .jsonPath("$.status").isEqualTo("400");

        assertThat(state.users).isEmpty();
    }

    private static RouterFunction<ServerResponse> 실제_라우트(FakeStateRepository state, long 한도) {
        var useCase = new IncrementalSyncUseCase(state, new FakeTupleWriter(), new FakeTupleChecker(),
                new FakeMutationLock(), Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        var query = new FakeQueryRepository(state);
        var bookmarks = new FakePageBookmarkRepository();
        return ScimRouter.scimRoutes(new ScimUserHandler(query, useCase),
                new ScimGroupHandler(state, query, useCase, new StateMemberTypeResolver(state)),
                new ScimListHandler(new ScimUserListing(query, bookmarks), new ScimGroupListing(state, query, bookmarks)),
                TemporaryFailureClassifier.표지만(), 한도);
    }
}
