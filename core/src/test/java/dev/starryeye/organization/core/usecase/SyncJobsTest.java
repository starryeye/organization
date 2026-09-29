package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 동기화·재적재 작업이 요청과 떨어져 돈다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 */
class SyncJobsTest {

    private static final Instant 지금 = Instant.parse("2026-09-29T03:00:00Z");

    private FakeSyncRunRepository runs;
    private SyncJobs jobs;
    private AtomicInteger 반납;
    private Supplier<Mono<Void>> 반납수단;

    @BeforeEach
    void 준비한다() {
        runs = new FakeSyncRunRepository(지금);
        jobs = new SyncJobs(runs, Duration.ofMinutes(1));
        반납 = new AtomicInteger();
        반납수단 = () -> Mono.fromRunnable(반납::incrementAndGet);
    }

    private SyncRun 건다(Mono<SyncOutcome> work) {
        return jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, work, 반납수단).block();
    }

    @Test
    @DisplayName("실행 기록을 열어 RUNNING 으로 곧바로 돌려주고, 작업은 따로 끝까지 가서 결과를 기록한다")
    void RUNNING을_곧바로_돌려주고_작업은_따로_끝난다() {
        // given — 테스트가 끝을 쥐고 있는 작업
        Sinks.One<SyncOutcome> 작업 = Sinks.one();

        // when
        SyncRun 열린것 = 건다(작업.asMono());

        // then — 작업이 끝나지 않았는데도 기록이 왔다
        assertThat(열린것.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.finished).isEmpty();

        // when — 작업이 끝나면
        작업.tryEmitValue(SyncOutcome.noChange());

        // then
        SyncRun 끝난것 = runs.awaitFinished(열린것.runId());
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("요청 구독이 끊겨도 작업은 끝까지 가서 결과를 기록하고 반납한다 — 앞단 프록시가 연결을 끊은 경우")
    void 요청이_끊겨도_작업은_끝까지_간다() {
        // given
        Sinks.One<SyncOutcome> 작업 = Sinks.one();

        // when — 요청이 붙자마자 끊긴다
        jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, 작업.asMono(), 반납수단).subscribe().dispose();
        작업.tryEmitValue(SyncOutcome.noChange());

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.finished).hasSize(1));
        assertThat(runs.finished.get(0).status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("작업이 오류로 끝나면 그 메시지로 FAILED 를 기록한다")
    void 오류는_FAILED로_기록한다() {
        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(Mono.error(new IllegalStateException("LDAP 연결 실패"))).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("LDAP 연결 실패");
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("작업이 결과 없이 끝나도 RUNNING 으로 남기지 않고 FAILED 로 기록한다")
    void 결과_없이_끝나도_FAILED다() {
        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(Mono.empty()).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).contains("결과 없이");
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("기한을 넘기면 남은 일을 멈추고 FAILED(기한 초과)로 기록한 뒤 반납한다")
    void 기한을_넘기면_멈춘다() {
        // given — 끝나지 않는 작업. OpenFGA 가 죽은 채로 배치마다 재시도하는 재적재다
        jobs = new SyncJobs(runs, Duration.ofMillis(200));
        AtomicBoolean 취소됨 = new AtomicBoolean();
        Mono<SyncOutcome> 끝나지_않는_작업 = Mono.<SyncOutcome>never().doOnCancel(() -> 취소됨.set(true));

        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(끝나지_않는_작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).startsWith("기한 초과");
        assertThat(취소됨).as("기한이 지나도 남은 일이 계속 돌면 락을 푼 뒤에 쓴다").isTrue();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("서버가 내려가면 도는 작업을 멈추고 FAILED(서버 종료로 중단)로 기록한 뒤 반납한다")
    void 서버가_내려가면_멈춘다() {
        // given — 일이 실제로 도는 중에 내려간다. RUNNING 을 받은 직후엔 아직 일을 구독하기 전일 수 있다
        AtomicBoolean 시작됨 = new AtomicBoolean();
        AtomicBoolean 취소됨 = new AtomicBoolean();
        SyncRun 열린것 = 건다(Mono.<SyncOutcome>never()
                .doOnSubscribe(subscription -> 시작됨.set(true))
                .doOnCancel(() -> 취소됨.set(true)));
        await().atMost(Duration.ofSeconds(5)).untilTrue(시작됨);

        // when — 종료는 기록·반납이 끝날 때까지 기다린 뒤 돌아온다
        jobs.shutdown();

        // then — 기다렸으므로 곧바로 보인다
        assertThat(runs.finished).hasSize(1);
        SyncRun 끝난것 = runs.finished.get(0);
        assertThat(끝난것.runId()).isEqualTo(열린것.runId());
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("서버 종료로 중단");
        assertThat(취소됨).isTrue();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("종료가 시작된 뒤에 걸린 작업은 일하지 않고 곧바로 FAILED(서버 종료로 중단)로 끝난다")
    void 종료_뒤의_작업은_곧바로_끝난다() {
        // given
        jobs.shutdown();
        AtomicBoolean 시작됨 = new AtomicBoolean();
        Mono<SyncOutcome> 작업 = Mono.fromCallable(() -> {
            시작됨.set(true);
            return SyncOutcome.noChange();
        });

        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("서버 종료로 중단");
        assertThat(시작됨).as("내려가는 서버에서 새 작업이 장부에 쓰기 시작하면 안 된다").isFalse();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("실행 기록을 열지 못하면 작업을 시작하지 않고, 반납한 뒤 그 오류로 끝난다")
    void 기록을_못_열면_시작하지_않는다() {
        // given
        runs.failStart(new IllegalStateException("DynamoDB 장애"));
        AtomicBoolean 시작됨 = new AtomicBoolean();
        Mono<SyncOutcome> 작업 = Mono.fromCallable(() -> {
            시작됨.set(true);
            return SyncOutcome.noChange();
        });

        // when, then
        assertThatThrownBy(() -> 건다(작업)).hasMessageContaining("DynamoDB 장애");
        assertThat(시작됨).isFalse();
        assertThat(반납).as("반납한 뒤에 오류를 알린다").hasValue(1);
    }

    @Test
    @DisplayName("반납이 기록보다 먼저다 — 끝남을 본 운영자가 곧바로 다시 걸면 받아진다")
    void 반납이_기록보다_먼저다() {
        // given
        AtomicInteger 반납할때_끝난기록 = new AtomicInteger(-1);
        Supplier<Mono<Void>> 기록을_보는_반납 = () -> Mono.fromRunnable(() -> 반납할때_끝난기록.set(runs.finished.size()));

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL,
                Mono.just(SyncOutcome.noChange()), 기록을_보는_반납).block();
        runs.awaitFinished(열린것.runId());

        // then
        assertThat(반납할때_끝난기록).hasValue(0);
    }

    @Test
    @DisplayName("끝난 기록으로 onFinished 를 부른다 — 지표·로그를 거는 자리다")
    void 끝난_기록으로_onFinished를_부른다() {
        // given
        AtomicReference<SyncRun> 받은것 = new AtomicReference<>();

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.SCHEDULED,
                Mono.just(SyncOutcome.noChange()), 반납수단, 받은것::set).block();
        SyncRun 끝난것 = runs.awaitFinished(열린것.runId());

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(받은것.get()).isEqualTo(끝난것));
    }

    @Test
    @DisplayName("요청의 Reactor Context 를 작업이 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다")
    void 요청의_컨텍스트를_이어받는다() {
        // given
        AtomicReference<String> 본값 = new AtomicReference<>();
        Mono<SyncOutcome> 작업 = Mono.deferContextual(context -> {
            본값.set(context.getOrDefault("traceId", "없음"));
            return Mono.just(SyncOutcome.noChange());
        });

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, 작업, 반납수단)
                .contextWrite(Context.of("traceId", "요청의-trace"))
                .block();
        runs.awaitFinished(열린것.runId());

        // then
        assertThat(본값.get()).isEqualTo("요청의-trace");
    }
}
