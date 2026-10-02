package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
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
    private FakeMutationLock lock;
    private SyncJobs jobs;
    private AtomicInteger 반납;
    private Supplier<Mono<Void>> 반납수단;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        runs = new FakeSyncRunRepository(지금);
        jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));
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
        jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMillis(200));
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
    @DisplayName("메시지 없는 오류로 끝나도 이유를 비우지 않는다 — 오류 이름으로 FAILED 를 기록한다")
    void 메시지_없는_오류는_이름으로_기록한다() {
        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(Mono.error(new IllegalStateException())).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("서버가 내려가면 도는 작업을 멈추고 FAILED(서버 종료로 중단)로 기록한 뒤 반납한다")
    void 서버가_내려가면_멈춘다() {
        // given — 일이 실제로 도는 중에 내려간다. RUNNING 을 받은 직후엔 아직 일을 구독하기 전일 수 있다.
        // 반납은 늦게 끝난다(락 반납은 DynamoDB 호출이다) — 종료가 기다리지 않으면 아래 단언이 반납·기록보다 먼저 돈다
        AtomicBoolean 시작됨 = new AtomicBoolean();
        AtomicBoolean 취소됨 = new AtomicBoolean();
        Supplier<Mono<Void>> 늦은_반납 = () -> Mono.delay(Duration.ofMillis(300))
                .then(Mono.fromRunnable(반납::incrementAndGet));
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, Mono.<SyncOutcome>never()
                .doOnSubscribe(subscription -> 시작됨.set(true))
                .doOnCancel(() -> 취소됨.set(true)), 늦은_반납).block();
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
    @DisplayName("실행 기록을 여는 호출이 곧바로 던져도 작업을 시작하지 않고, 한 번 반납한 뒤 그 오류로 끝난다")
    void 기록_열기가_곧바로_던져도_반납한다() {
        // given
        jobs = 기록을_이렇게_여는(() -> {
            throw new IllegalStateException("DynamoDB 클라이언트 고장");
        });
        AtomicBoolean 시작됨 = new AtomicBoolean();

        // when, then — 반납하지 않으면 app-scim 은 하트비트가 락을 계속 갱신해 재시작 전까지 모든 SCIM 쓰기가 503 이다
        assertThatThrownBy(() -> jobs.start(SyncSource.SCIM, SyncTrigger.REBUILD, 시작하면_표시한다(시작됨), 반납수단)
                .block(Duration.ofSeconds(5)))
                .hasMessageContaining("DynamoDB 클라이언트 고장");
        assertThat(시작됨).isFalse();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("실행 기록을 여는 호출이 빈 응답이어도 요청이 매달리지 않는다 — 한 번 반납한 뒤 오류로 끝난다")
    void 기록_열기가_비어도_매달리지_않는다() {
        // given
        jobs = 기록을_이렇게_여는(Mono::empty);
        AtomicBoolean 시작됨 = new AtomicBoolean();

        // when, then — 기다림에 한도를 둔다. 매달리면 기한 초과로 실패한다
        assertThatThrownBy(() -> jobs.start(SyncSource.SCIM, SyncTrigger.REBUILD, 시작하면_표시한다(시작됨), 반납수단)
                .block(Duration.ofSeconds(5)))
                .hasMessageContaining("실행 기록을 열지 못했다");
        assertThat(시작됨).isFalse();
        assertThat(반납).hasValue(1);
    }

    /** 실행 기록을 여는 자리만 바꾼 저장소로 돌린다. 나머지는 가짜 그대로다. */
    private SyncJobs 기록을_이렇게_여는(Supplier<Mono<SyncRun>> 열기) {
        return new SyncJobs(new FakeSyncRunRepository(지금) {
            @Override
            public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
                return 열기.get();
            }
        }, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));
    }

    private static Mono<SyncOutcome> 시작하면_표시한다(AtomicBoolean 시작됨) {
        return Mono.fromCallable(() -> {
            시작됨.set(true);
            return SyncOutcome.noChange();
        });
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

    // ---------- 락을 잡고 띄우기 (설계 2026-09-30 §3) ----------

    private SyncRun 락을_잡고_건다(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work) {
        return jobs.startLocked(source, trigger, MutationLock.LockPurpose.SYNC, work, run -> {
        }).block();
    }

    @Test
    @DisplayName("락을 잡고 작업을 띄우며, 끝나면 반납한다 — 도는 동안에는 그 목적으로 쥐고 있다")
    void 락을_잡고_띄우고_반납한다() {
        // given
        AtomicReference<MutationLock.LockPurpose> 도는_동안 = new AtomicReference<>();
        Mono<SyncOutcome> 작업 = lock.peek().map(purpose -> {
            도는_동안.set(purpose);
            return SyncOutcome.noChange();
        });

        // when
        SyncRun 끝난것 = runs.awaitFinished(락을_잡고_건다(SyncSource.LDAP, SyncTrigger.MANUAL, 작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(도는_동안.get()).isEqualTo(MutationLock.LockPurpose.SYNC);
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 락을 쥐고 있으면 곧바로 LockUnavailableException 이고, 기록을 열지도 일을 시작하지도 않는다")
    void 락을_못_잡으면_시작하지_않는다() {
        // given
        lock.failAcquire = true;
        AtomicBoolean 시작됨 = new AtomicBoolean();

        // when, then
        assertThatThrownBy(() -> 락을_잡고_건다(SyncSource.LDAP, SyncTrigger.SCHEDULED, 시작하면_표시한다(시작됨)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(시작됨).isFalse();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("두 인스턴스가 같은 락으로 동시에 걸면 한쪽만 돈다 — 같은 초에 도는 정기 동기화")
    void 두_인스턴스는_한_번에_하나만_돈다() {
        // given — 같은 락(같은 테이블)을 쓰는 두 인스턴스
        SyncJobs 가 = jobs;
        SyncJobs 나 = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));
        Sinks.One<SyncOutcome> 가의_작업 = Sinks.one();

        // when
        SyncRun 가의_기록 = 가.startLocked(SyncSource.LDAP, SyncTrigger.SCHEDULED, MutationLock.LockPurpose.SYNC,
                가의_작업.asMono(), run -> {
                }).block();

        // then — 가가 도는 동안 나는 못 건다
        assertThatThrownBy(() -> 나.startLocked(SyncSource.LDAP, SyncTrigger.SCHEDULED, MutationLock.LockPurpose.SYNC,
                Mono.just(SyncOutcome.noChange()), run -> {
                }).block())
                .isInstanceOf(LockUnavailableException.class);

        // when — 가가 끝나면
        가의_작업.tryEmitValue(SyncOutcome.noChange());
        runs.awaitFinished(가의_기록.runId());

        // then — 나도 걸 수 있다
        SyncRun 나의_기록 = 나.startLocked(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                Mono.just(SyncOutcome.noChange()), run -> {
                }).block();
        assertThat(runs.awaitFinished(나의_기록.runId()).status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("작업 도중 리스를 잃으면(남이 가져갔으면) 멈추고 FAILED 로 기록한다")
    void 리스를_잃으면_멈춘다() {
        // given
        jobs = new SyncJobs(runs, lock, Duration.ofMillis(50), LockObserver.NOOP, Duration.ofMinutes(1));
        lock.failRenew = true;
        AtomicBoolean 취소됨 = new AtomicBoolean();

        // when
        SyncRun 끝난것 = runs.awaitFinished(락을_잡고_건다(SyncSource.LDAP, SyncTrigger.MANUAL,
                Mono.<SyncOutcome>never().doOnCancel(() -> 취소됨.set(true))).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).contains("리스");
        assertThat(취소됨).as("남이 가져간 뒤에도 계속 쓰면 두 인스턴스가 겹친다").isTrue();
    }

    @Test
    @DisplayName("남은 기록 정리가 오래 걸려도 그동안 리스를 갱신한다")
    void 남은_기록_정리가_오래_걸려도_리스를_갱신한다() {
        // given — findRecent(남은 기록 정리가 쓰는 조회)가 느린 저장소. 하트비트가 work 에만 걸리면 이 구간은 갱신 없이 지나간다
        FakeSyncRunRepository 느린_정리_저장소 = new FakeSyncRunRepository(지금) {
            @Override
            public Flux<SyncRun> findRecent(int limit) {
                return super.findRecent(limit).delaySubscription(Duration.ofMillis(300));
            }
        };
        jobs = new SyncJobs(느린_정리_저장소, lock, Duration.ofMillis(50), LockObserver.NOOP, Duration.ofMinutes(1));

        // when
        SyncRun 끝난것 = 느린_정리_저장소.awaitFinished(jobs.startLocked(SyncSource.LDAP, SyncTrigger.MANUAL,
                MutationLock.LockPurpose.SYNC, Mono.just(SyncOutcome.noChange()), run -> {
                }).block().runId());

        // then
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(3);
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("락을 잡은 작업은 시작할 때 같은 앱의 끝나지 못한 락 작업 기록을 '비정상 종료로 중단'으로 닫는다 — 아카이빙 기록과 자기 기록은 두고")
    void 끝나지_못한_기록을_닫는다() {
        // given — 죽은 인스턴스가 남긴 SCIM 재적재 기록, 도는 중인 아카이빙, 다른 앱(LDAP)의 기록
        runs.seed(SyncRun.started("죽은-재적재", SyncSource.SCIM, SyncTrigger.REBUILD, 지금.minusSeconds(300)));
        runs.seed(SyncRun.started("도는-아카이빙", SyncSource.SCIM, SyncTrigger.ARCHIVE, 지금.minusSeconds(60)));
        runs.seed(SyncRun.started("다른-앱", SyncSource.LDAP, SyncTrigger.SCHEDULED, 지금.minusSeconds(600)));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLocked(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, Mono.just(SyncOutcome.noChange()), run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).as("자기 기록은 닫지 않는다").isEqualTo(SyncStatus.SUCCEEDED);
        SyncRun 죽은것 = runs.findById("죽은-재적재").block();
        assertThat(죽은것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(죽은것.message()).isEqualTo("비정상 종료로 중단");
        assertThat(runs.findById("도는-아카이빙").block().status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.findById("다른-앱").block().status()).isEqualTo(SyncStatus.RUNNING);
    }

    @Test
    @DisplayName("막 끝난 기록이 오래된 목록에 RUNNING 으로 보여도 덮어쓰지 않는다 — 반납과 기록 사이의 틈")
    void 막_끝난_기록은_덮어쓰지_않는다() {
        // given — 저장된 기록은 이미 SUCCEEDED 인데, findRecent 가(최종 일관성 때문에) 그 앞에 낡은 RUNNING 사본을 얹어 준다
        SyncRun 앞_작업_RUNNING = SyncRun.started("앞-작업", SyncSource.LDAP, SyncTrigger.SCHEDULED, 지금.minusSeconds(60));
        FakeSyncRunRepository 낡은_목록_저장소 = new FakeSyncRunRepository(지금) {
            @Override
            public Flux<SyncRun> findRecent(int limit) {
                return Flux.concat(Mono.just(앞_작업_RUNNING), super.findRecent(limit));
            }
        };
        낡은_목록_저장소.seed(앞_작업_RUNNING);
        낡은_목록_저장소.finish(앞_작업_RUNNING, SyncOutcome.noChange()).block();
        SyncJobs 잡스 = new SyncJobs(낡은_목록_저장소, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));

        // when
        SyncRun 끝난것 = 낡은_목록_저장소.awaitFinished(잡스.startLocked(SyncSource.LDAP, SyncTrigger.MANUAL,
                MutationLock.LockPurpose.SYNC, Mono.just(SyncOutcome.noChange()), run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(낡은_목록_저장소.findById("앞-작업").block().status())
                .as("반납과 기록 사이의 틈에 낡은 RUNNING 사본을 보고 방금 끝난 결과를 덮어쓰면 안 된다")
                .isEqualTo(SyncStatus.SUCCEEDED);
    }

    // ---------- 반납 뒤 할 일 (설계 2026-10-02 §5) ----------

    @Test
    @DisplayName("반납 뒤 할 일은 락을 반납한 뒤 돌고, 그 결론이 기록된다")
    void 반납_뒤_할_일은_락_밖에서_돈다() {
        // given
        AtomicReference<Boolean> 그때_락 = new AtomicReference<>();
        Mono<Mono<SyncOutcome>> 작업 = Mono.just(Mono.fromSupplier(() -> {
            그때_락.set(lock.isHeld());
            return SyncOutcome.noChange();
        }));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLockedThen(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, 작업, run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(그때_락.get()).as("반납 뒤에 돈다").isFalse();
    }

    @Test
    @DisplayName("반납 뒤 할 일이 실패하면 그 사유로 FAILED 를 기록한다")
    void 반납_뒤_할_일이_실패하면_FAILED() {
        // given
        Mono<Mono<SyncOutcome>> 작업 = Mono.just(Mono.error(new IllegalStateException("뒤에서 실패")));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLockedThen(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, 작업, run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("뒤에서 실패");
        assertThat(lock.released).hasValue(1);
    }
}
