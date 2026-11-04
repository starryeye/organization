package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.admin.AdminExceptionHandler;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.usecase.GroupGraphTooLargeException;
import dev.starryeye.organization.core.usecase.HierarchyRejectedException;
import dev.starryeye.organization.core.usecase.HierarchyRejectedException.Reason;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.mockito.Mockito.when;

/**
 * 지정 API({@code PUT}·{@code DELETE /admin/organizations/{orgCode}/parent}, 설계 2026-11-04 §3)가 유스케이스의 결과·오류를 응답으로 옮기는 표를 본다.
 * 유스케이스는 흉내고(모양은 core 의 시험이 본다), 오류 응답은 모두 {@code application/problem+json} 이다.
 *
 * <p>보안 없는 슬라이스다 — 이 두 경로가 {@code admin.hierarchy} 를 요구하는 것은 web-security 의 범위 행렬이 본다.
 */
class OrganizationHierarchyControllerTest {

    private static final String 대상 = "TEAM";
    private static final String 상위 = "DIV";
    private static final String 지정_경로 = "/admin/organizations/" + 대상 + "/parent";
    private static final String 지정_본문 = "{\"parentOrgCode\":\"" + 상위 + "\"}";
    private static final String 본문_오류_문구 = "parentOrgCode 가 필요하다";

    private IncrementalSyncUseCase useCase;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        useCase = Mockito.mock(IncrementalSyncUseCase.class);
        client = WebTestClient.bindToController(new OrganizationHierarchyController(useCase))
                .controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))
                .build();
    }

    private WebTestClient.ResponseSpec 지정한다(String 본문) {
        return client.put().uri(지정_경로).contentType(MediaType.APPLICATION_JSON).bodyValue(본문).exchange();
    }

    private static void 문제_응답이다(WebTestClient.ResponseSpec 응답, HttpStatus 상태, String detail) {
        응답.expectStatus().isEqualTo(상태)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(상태.value())
                .jsonPath("$.detail").isEqualTo(detail);
    }

    @Test
    @DisplayName("지정하면 204 이고 본문이 없다 — 유스케이스에 대상과 상위를 그대로 넘긴다")
    void 지정하면_204() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.just(IncrementalSyncResult.noChange()));

        // when, then
        지정한다(지정_본문).expectStatus().isNoContent()
                .expectBody().isEmpty();
        Mockito.verify(useCase).assignParent(대상, 상위);
    }

    @Test
    @DisplayName("본문이 없으면 400 이고 유스케이스를 부르지 않는다 — 본문 없이 보내거나 빈 JSON 본문을 보내도 같다")
    void 본문이_없으면_400() {
        // when, then — 내용 형식 없이 본문도 없다
        문제_응답이다(client.put().uri(지정_경로).exchange(), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        // when, then — JSON 이라고 하고 본문이 비었다
        문제_응답이다(client.put().uri(지정_경로).contentType(MediaType.APPLICATION_JSON).exchange(),
                HttpStatus.BAD_REQUEST, 본문_오류_문구);
        Mockito.verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("parentOrgCode 칸이 없거나 객체가 아닌 JSON 이면 400 이고 유스케이스를 부르지 않는다")
    void parentOrgCode_가_없으면_400() {
        // when, then
        문제_응답이다(지정한다("{}"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        문제_응답이다(지정한다("{\"parentOrgCode\":null}"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        문제_응답이다(지정한다("[\"" + 상위 + "\"]"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        Mockito.verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("parentOrgCode 가 숫자면 400 이다 — 텍스트만 받는다")
    void parentOrgCode_가_숫자면_400() {
        // when, then
        문제_응답이다(지정한다("{\"parentOrgCode\":123}"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        Mockito.verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("parentOrgCode 가 공백뿐이면 400 이다")
    void 공백이면_400() {
        // when, then
        문제_응답이다(지정한다("{\"parentOrgCode\":\"   \"}"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        문제_응답이다(지정한다("{\"parentOrgCode\":\"\"}"), HttpStatus.BAD_REQUEST, 본문_오류_문구);
        Mockito.verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 400 이고 유스케이스를 부르지 않는다 — 처리기가 스프링의 입력 오류를 그대로 옮긴다")
    void JSON_이_아니면_400() {
        // when
        var 응답 = 지정한다("{parentOrgCode");

        // then
        응답.expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Mockito.verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("대상 조직이 없으면(빈 Mono) 404 이고 대상 코드를 싣는다")
    void 대상이_없으면_404() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.empty());

        // when, then
        문제_응답이다(지정한다(지정_본문), HttpStatus.NOT_FOUND, "조직이 없다: " + 대상);
    }

    @Test
    @DisplayName("상위 조직이 없으면 404 이고 상위 코드를 싣는다")
    void 상위가_없으면_404() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(
                new HierarchyRejectedException(Reason.PARENT_NOT_FOUND, 대상, 상위)));

        // when, then
        문제_응답이다(지정한다(지정_본문), HttpStatus.NOT_FOUND, "상위 조직이 없다: " + 상위);
    }

    @Test
    @DisplayName("지정이 순환을 닫으면 409 이고 설계가 정한 문구를 싣는다")
    void 순환이면_409() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(
                new HierarchyRejectedException(Reason.CYCLE, 대상, 상위)));

        // when, then
        문제_응답이다(지정한다(지정_본문), HttpStatus.CONFLICT, 상위 + " 를 " + 대상 + " 의 상위로 두면 순환이 된다");
    }

    @Test
    @DisplayName("걷기 예산을 넘으면 400 이고 그 메시지를 싣는다 — 다시 보내도 같다")
    void 걷기_예산을_넘으면_400() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(
                new GroupGraphTooLargeException("조직 계층이 순환 검사 한도(100000)를 넘었다")));

        // when, then
        문제_응답이다(지정한다(지정_본문), HttpStatus.BAD_REQUEST, "조직 계층이 순환 검사 한도(100000)를 넘었다");
    }

    @Test
    @DisplayName("락을 못 잡으면 409 가 아니라 503 이고 Retry-After 는 예외가 든 대기를 올림한 초다 — 409 는 순환이 쓴다")
    void 락을_못_잡으면_503_과_Retry_After() {
        // given — 2.3초는 3초로 올린다
        LockUnavailableException 못_잡았다 = new LockUnavailableException("다른 작업이 변경 락을 쥐고 있습니다", Duration.ofMillis(2300));
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(못_잡았다));

        // when
        var 응답 = 지정한다(지정_본문);

        // then — detail 은 일시 장애의 정한 문구가 아니라 락 경합의 사유 그대로다
        문제_응답이다(응답, HttpStatus.SERVICE_UNAVAILABLE, 못_잡았다.getMessage());
        응답.expectHeader().valueEquals("Retry-After", "3");
    }

    @Test
    @DisplayName("락 경합을 원인으로 품은 예외도 503 이고 Retry-After 는 안쪽 예외의 값이다 — 처리기가 사슬에서 찾아 409 로 내지 않는다")
    void 락_경합을_품은_예외도_503_과_Retry_After() {
        // given — 저장소 계층이 락 경합을 다른 예외로 감싸 던진 꼴. 안쪽 예외의 대기는 2.3초(3초로 올림)이고 바깥 예외는 대기를 모른다
        LockUnavailableException 못_잡았다 = new LockUnavailableException("다른 작업이 변경 락을 쥐고 있습니다", Duration.ofMillis(2300));
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(new RuntimeException("x", 못_잡았다)));

        // when
        var 응답 = 지정한다(지정_본문);

        // then — 409 는 순환이 쓴다. detail 은 안쪽 락 경합의 사유다
        문제_응답이다(응답, HttpStatus.SERVICE_UNAVAILABLE, 못_잡았다.getMessage());
        응답.expectHeader().valueEquals("Retry-After", "3");
    }

    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 Retry-After 는 쥔 쪽의 긴 작업 대기(60)다")
    void 재적재가_쥐고_있으면_Retry_After_60() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(LockUnavailableException.잡혀_있다(LockPurpose.REBUILD)));

        // when, then
        지정한다(지정_본문).expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "60");
    }

    @Test
    @DisplayName("튜플 반영이 일부 실패하면 503 이고 Retry-After 는 10 이다 — 다시 보내면 남은 것만 반영한다")
    void 일부_실패면_503_과_Retry_After_10() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.just(new IncrementalSyncResult(false, TupleWriteResult.empty())));

        // when
        var 응답 = 지정한다(지정_본문);

        // then
        문제_응답이다(응답, HttpStatus.SERVICE_UNAVAILABLE, "일부 튜플 적용에 실패했다 — 잠시 뒤 다시 보낸다: " + 대상);
        응답.expectHeader().valueEquals("Retry-After", "10");
    }

    @Test
    @DisplayName("표에 없는 오류는 컨트롤러가 바꾸지 않는다 — 처리기가 일시 장애는 503 으로, 모르는 예외는 내부 문구 없이 500 으로 낸다")
    void 그_밖의_오류는_처리기가_낸다() {
        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(new TemporaryFailureException("저장소가 잠시 막혔다", Duration.ofSeconds(7))));

        // when, then — 처리기의 정한 문구다
        var 일시_장애 = 지정한다(지정_본문);
        문제_응답이다(일시_장애, HttpStatus.SERVICE_UNAVAILABLE, "일시 장애 — 잠시 뒤 다시 시도한다");
        일시_장애.expectHeader().valueEquals("Retry-After", "7");

        // given
        when(useCase.assignParent(대상, 상위)).thenReturn(Mono.error(new IllegalStateException("버그")));

        // when, then
        문제_응답이다(지정한다(지정_본문), HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류");
    }

    @Test
    @DisplayName("없애면 204 이고 본문이 없다")
    void 없애면_204() {
        // given
        when(useCase.clearParent(대상)).thenReturn(Mono.just(IncrementalSyncResult.noChange()));

        // when, then
        client.delete().uri(지정_경로).exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
        Mockito.verify(useCase).clearParent(대상);
    }

    @Test
    @DisplayName("없앨 대상 조직이 없으면 404 이다")
    void 없앨_대상이_없으면_404() {
        // given
        when(useCase.clearParent(대상)).thenReturn(Mono.empty());

        // when, then
        문제_응답이다(client.delete().uri(지정_경로).exchange(), HttpStatus.NOT_FOUND, "조직이 없다: " + 대상);
    }

    @Test
    @DisplayName("없앨 때도 락을 못 잡거나 일부 실패하면 503 과 Retry-After 다")
    void 없앨_때의_503() {
        // given
        when(useCase.clearParent(대상)).thenReturn(Mono.error(new LockUnavailableException("쥐고 있다", Duration.ofSeconds(2))));

        // when, then
        client.delete().uri(지정_경로).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "2");

        // given
        when(useCase.clearParent(대상)).thenReturn(Mono.just(new IncrementalSyncResult(false, TupleWriteResult.empty())));

        // when, then
        client.delete().uri(지정_경로).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals("Retry-After", "10");
    }
}
