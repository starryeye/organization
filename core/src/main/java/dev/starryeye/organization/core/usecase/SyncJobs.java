package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 동기화·재적재 작업을 요청과 떼어 돌린다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 *
 * <p><b>왜 떼는가.</b> 전에는 작업이 HTTP 요청의 구독 안에서 돌았다. 앞단 프록시가 60초에 연결을 끊으면 구독이 취소돼 작업이
 * 중간에 멈췄고, 실행 기록은 영원히 RUNNING 으로, 락은 풀린 채 남았다. 이제 작업은 자기 구독으로 돌고, 요청은 실행 기록(RUNNING)만
 * 받아 간다.
 *
 * <p><b>작업 락(설계 2026-09-30 §3).</b> {@link #startLocked} 가 앱의 작업 락을 잡고, 작업 동안 리스를 갱신하고, 리스를 잃으면(=남이 가져갔으면)
 * 작업을 멈추고({@link LeaseKeeper}), 끝나면 반납한다. 여러 인스턴스가 같은 초에 걸어도 한 번에 하나만 돈다. SCIM 재적재·LDAP 동기화·LDAP 재적재가
 * 모두 이것을 탄다. 하트비트는 남은 기록 정리와 일을 함께 덮는다 — 그 앞에는 리스를 막 잡아 TTL 이 그대로인 채로 도는 실행 기록 PutItem 하나만
 * 있다.
 *
 * <p>한 작업이 지키는 규칙:
 * <ol>
 *   <li>실행 기록을 연 뒤에만 일을 시작한다. 기록을 열지 못하면 반납하고 그 오류로 끝난다.</li>
 *   <li>일을 시작하기 전에, 같은 앱의 락 작업 기록 중 RUNNING 으로 남은 것을 "비정상 종료로 중단"으로 닫는다 — 락을 쥐었으니 그것들은
 *       죽은 작업이다(강제 종료·정전·다른 인스턴스의 비정상 종료). 락을 잡지 않는 아카이빙({@code ARCHIVE}) 기록과 자기 기록은 건드리지 않는다.</li>
 *   <li>일은 서버 종료·기한({@code timeout})·리스 상실과 경주한다. 먼저 온 쪽이 이기고 진 쪽은 취소된다. 종료가 이미 시작됐으면 일을
 *       시작하지도 않는다.</li>
 *   <li>어떻게 끝나든 <b>먼저 반납하고 그다음 기록한다.</b> 기록에서 "끝남"을 본 운영자가 곧바로 다시 걸면 받아져야 한다.</li>
 *   <li>요청의 Reactor Context 를 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다.</li>
 * </ol>
 *
 * <p><b>멈춤은 되돌리기가 아니다.</b> 이미 나간 쓰기는 무르지 않는다. 멈췄다는 사실을 FAILED 로 남기고, 기록 규칙은 각 작업이 지킨다
 * (LDAP 은 "기록 중" 표시로 다음 회차가 맞춘다 — 설계 2026-09-30 §4).
 */
@Slf4j
public class SyncJobs {

    static final String 종료_사유 = "서버 종료로 중단";
    static final String 비정상_종료_사유 = "비정상 종료로 중단";

    /** 종료 때 작업이 반납·기록을 마치기를 기다리는 한도. 그 뒤에는 DynamoDB 클라이언트가 닫힌다. */
    private static final Duration 종료_대기 = Duration.ofSeconds(10);

    /** 끝나지 못한 기록을 찾을 때 볼 최근 기록 수. 죽은 작업의 기록은 가장 최근 것들 사이에 있다. */
    private static final int 살펴볼_기록 = 100;

    private final SyncRunRepository runs;
    private final MutationLock lock;
    private final LeaseKeeper keeper;
    private final LockObserver lockObserver;
    private final Duration timeout;
    private final Sinks.Empty<Void> 종료 = Sinks.empty();
    private final Set<Mono<Void>> 도는_작업 = ConcurrentHashMap.newKeySet();

    public SyncJobs(SyncRunRepository runs, MutationLock lock, Duration renewInterval,
                    LockObserver lockObserver, Duration timeout) {
        this.runs = runs;
        this.lock = lock;
        this.keeper = new LeaseKeeper(lock, renewInterval, lockObserver);
        this.lockObserver = lockObserver;
        this.timeout = timeout;
    }

    /**
     * 앱의 작업 락을 잡고, 실행 기록을 열고, 작업을 요청과 떼어 띄운 뒤 연 기록(RUNNING)을 준다(설계 2026-09-30 §3.2).
     * 못 잡으면 {@link LockUnavailableException} — 재시도하지 않는다. 못 잡았다는 것이 곧 다른 작업이 돈다는 뜻이다.
     *
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    public Mono<SyncRun> startLocked(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                     Mono<SyncOutcome> work, Consumer<SyncRun> onFinished) {
        return startLockedThen(source, trigger, purpose, work.map(Mono::just), onFinished);
    }

    /**
     * {@link #startLocked} 와 같되, 락 안의 일이 <b>"반납 뒤 할 일"</b>을 내놓는다(설계 2026-10-02 §5). 순서: 작업 → 반납 → 반납 뒤 할 일 → 기록.
     * 락이 필요 없는 마무리(SCIM 재적재의 튜플 스냅샷 저장 등)를 락 밖으로 빼 그동안 다른 쓰기를 막지 않는다. 실패하면 그 사유로 FAILED 다.
     *
     * <p>반납 뒤 할 일은 기한·서버 종료 경주 밖에서 돈다 — SCIM 재적재의 스냅샷 저장은 10만 명이면 수십 초~2분이다. {@link #shutdown} 은 최대
     * {@link #종료_대기} 만 기다리므로 그 사이 서버가 내려가면 DynamoDB 클라이언트가 닫히며 실패하고, 기록은 RUNNING 으로 남는다. 다음 락 작업이
     * 그 기록을 "비정상 종료로 중단"으로 닫는다.
     */
    public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                         Mono<Mono<SyncOutcome>> work, Consumer<SyncRun> onFinished) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            return lock.acquire(purpose)
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), false))
                    .flatMap(lease -> 띄운다(source, trigger, work,
                            run -> 남은_기록을_닫는다(source, run.runId()),
                            일 -> keeper.keep(lease, 일, "작업 도중 리스 상실"),
                            () -> lock.release(lease),
                            onFinished));
        });
    }

    /** 패키지 전용 기본 단위 — 겹침 검사는 호출자가 하고 그 반납 수단을 넘긴다. 바깥은 {@link #startLocked} 를 쓴다. */
    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger,
                        Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        return start(source, trigger, work, release, run -> {
        });
    }

    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work,
                        Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return 띄운다(source, trigger, work.map(Mono::just), run -> Mono.empty(), UnaryOperator.identity(),
                release, onFinished);
    }

    /**
     * 실행 기록을 열고 {@code work} 를 요청과 떼어 띄운 뒤, 연 기록(RUNNING)을 준다. 구독할 때마다 작업 하나가 뜬다.
     *
     * @param 먼저       기록을 연 뒤·일을 시작하기 전에 할 일. 실패하면 일을 하지 않고 FAILED 로 끝나므로 오류를 스스로
     *                   삼켜야 한다(남은_기록을_닫는다 가 그렇다)
     * @param 지키기     {@code 먼저} 와 {@code work} 를 합친 것을 감싼다({@link LeaseKeeper#keep} 이 쓴다) — 락을 쥔 채
     *                   돌아야 하는 구간이 {@code work} 만이 아니라 남은 기록 정리까지이기 때문이다. {@code work} 가 내놓는
     *                   "반납 뒤 할 일"은 이 구간 밖이다 — 락을 반납한 뒤에 돈다(설계 2026-10-02 §5)
     * @param release    겹침 검사(락·가드)를 푸는 수단. 작업이 어떻게 끝나든 정확히 한 번 부른다
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    Mono<SyncRun> 띄운다(SyncSource source, SyncTrigger trigger, Mono<Mono<SyncOutcome>> work,
                        Function<SyncRun, Mono<Void>> 먼저, UnaryOperator<Mono<Mono<SyncOutcome>>> 지키기,
                        Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return Mono.deferContextual(context -> {
            Sinks.One<SyncRun> 열림 = Sinks.one();
            Sinks.Empty<Void> 끝남 = Sinks.empty();
            Mono<Void> 끝남신호 = 끝남.asMono();
            도는_작업.add(끝남신호);

            // 여는 호출이 곧바로 던지거나 빈 응답이어도 반납하고 오류로 알린다 — 아니면 요청이 매달리고 락·가드가 풀리지 않는다
            Mono.defer(() -> runs.start(source, trigger))
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("실행 기록을 열지 못했다 — 빈 응답")))
                    .onErrorResume(error -> {
                        log.error("실행 기록을 열지 못했다: source={} trigger={}", source, trigger, error);
                        return 반납한다(release)
                                .then(Mono.fromRunnable(() -> 열림.tryEmitError(error)))
                                .then(Mono.<SyncRun>empty());
                    })
                    .flatMap(run -> {
                        log.info("[{}] 작업 시작: source={} trigger={}", run.runId(), source, trigger);
                        열림.tryEmitValue(run);
                        return 끝까지_돌린다(run, work, 먼저, 지키기, release);
                    })
                    .doOnNext(onFinished)
                    .doFinally(signal -> {
                        도는_작업.remove(끝남신호);
                        끝남.tryEmitEmpty();
                    })
                    .contextWrite(context)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(finished -> {
                    }, error -> log.error("동기화 작업 뒤처리가 예기치 않게 실패했다", error));

            return 열림.asMono();
        });
    }

    /**
     * 앱이 내려갈 때 부른다(설계 §4 "정상 종료"). 도는 작업을 멈춰 FAILED("서버 종료로 중단")로 기록하게 하고, 반납·기록을 마칠 때까지
     * 최대 {@link #종료_대기} 기다린다. 기다리지 않으면 이 뒤에 DynamoDB 클라이언트가 닫혀 기록이 RUNNING 으로 남고 락도 TTL 까지 묶인다.
     * 이 뒤에 걸린 작업은 일을 시작하지 않고 같은 사유로 끝난다.
     */
    public void shutdown() {
        종료.tryEmitEmpty();
        List<Mono<Void>> 남은것 = List.copyOf(도는_작업);
        if (남은것.isEmpty()) {
            return;
        }
        log.warn("서버 종료: 도는 동기화 작업 {}개를 멈추고 FAILED 로 기록한다", 남은것.size());
        try {
            Mono.when(남은것).block(종료_대기);
        } catch (RuntimeException e) {
            log.error("서버 종료: {} 안에 작업 기록을 마치지 못했다 — 기록이 RUNNING 으로 남을 수 있다", 종료_대기, e);
        }
    }

    /**
     * 종료 신호를 <b>먼저</b> 구독한다. 종료가 이미 시작됐으면 그 자리에서 이겨 {@code work} 를 구독하지도 않는다. 종료·기한은
     * 락을 쥔 구간({@code 지키기} 가 감싼 부분)만 경주 상대로 삼는다 — 반납 뒤 할 일은 그 경주 밖에서 돈다(설계 2026-10-02 §5).
     */
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, Mono<Mono<SyncOutcome>> work, Function<SyncRun, Mono<Void>> 먼저,
                                    UnaryOperator<Mono<Mono<SyncOutcome>>> 지키기, Supplier<Mono<Void>> release) {
        Mono<Mono<SyncOutcome>> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        Mono<Mono<SyncOutcome>> 일 = 지키기.apply(Mono.defer(() -> 먼저.apply(run)).then(work));
        return Mono.firstWithSignal(종료되면, 일)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> Mono.just(Mono.just(실패로(run, error))))
                .flatMap(반납_뒤 -> 반납한다(release).thenReturn(반납_뒤))
                // 반납 뒤 할 일(설계 2026-10-02 §5) — 락 없이 돈다
                .flatMap(반납_뒤 -> 반납_뒤
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("반납 뒤 할 일이 결과 없이 끝났다")))
                        .onErrorResume(error -> Mono.just(실패로(run, error))))
                .flatMap(outcome -> runs.finish(run, outcome))
                .doOnNext(finished -> log.info("[{}] 작업 끝: status={} written={} deleted={} failed={}",
                        finished.runId(), finished.status(), finished.writtenCount(),
                        finished.deletedCount(), finished.failureCount()))
                .onErrorResume(error -> {
                    log.error("[{}] 작업 결과를 기록하지 못했다 — 기록이 RUNNING 으로 남는다", run.runId(), error);
                    return Mono.empty();
                });
    }

    /** 메시지 없는 오류도 있다 — 이유를 비워 두면 기록만 보고는 무엇이 터졌는지 모른다. */
    private static SyncOutcome 실패로(SyncRun run, Throwable error) {
        String 사유 = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        log.error("[{}] 작업 실패: {}", run.runId(), 사유, error);
        return SyncOutcome.failed(사유);
    }

    /**
     * 같은 앱의 락 작업 기록 중 RUNNING 으로 남은 것을 닫는다(설계 2026-09-30 §3.3). 락을 쥐었으니 그것들은 죽은 작업이다. 락을 잡지 않는
     * 아카이빙과 방금 연 자기 기록은 건드리지 않는다. 찾거나 닫다 실패해도 작업은 계속한다 — 기록 정리는 부가 일이다.
     *
     * <p>{@link SyncRunRepository#abandon} 은 저장된 기록이 <b>아직 RUNNING 일 때만</b> 닫는다. 앞 작업이 락을 반납한 뒤·자기
     * 기록을 쓰기 전의 틈에 여기까지 왔으면, 방금 SUCCEEDED 로 끝난 기록이 이 목록에는 아직 RUNNING 으로 보일 수 있다(읽기는 최종
     * 일관성) — 조건이 없으면 그 결과·집계값을 지울 뻔한다. {@code abandon} 이 빈 Mono 를 주면 이미 끝나 있었다는 뜻이니 경고할 일이
     * 아니다.
     */
    private Mono<Void> 남은_기록을_닫는다(SyncSource source, String 지금_기록) {
        return runs.findRecent(살펴볼_기록)
                .filter(run -> run.source() == source
                        && run.status() == SyncStatus.RUNNING
                        && run.trigger() != SyncTrigger.ARCHIVE
                        && !run.runId().equals(지금_기록))
                .concatMap(run -> runs.abandon(run, 비정상_종료_사유)
                        .doOnSuccess(closed -> {
                            if (closed != null) {
                                log.warn("[{}] 끝나지 못한 채 남은 실행 기록을 닫았다: {}", closed.runId(), 비정상_종료_사유);
                            } else {
                                log.debug("[{}] 닫으려 했으나 이미 끝나 있었다 — 반납과 기록 사이의 틈", run.runId());
                            }
                        })
                        .onErrorResume(error -> {
                            log.warn("[{}] 끝나지 못한 실행 기록을 닫지 못했다", run.runId(), error);
                            return Mono.empty();
                        }))
                .then()
                .onErrorResume(error -> {
                    log.warn("끝나지 못한 실행 기록을 찾지 못했다 — 작업은 계속한다", error);
                    return Mono.empty();
                });
    }

    /** 반납 실패는 작업을 실패시키지 않는다 — 일은 이미 끝났다. 남기기만 한다. */
    private static Mono<Void> 반납한다(Supplier<Mono<Void>> release) {
        return Mono.defer(release)
                .onErrorResume(error -> {
                    log.warn("작업을 끝내며 락·가드를 반납하지 못했다", error);
                    return Mono.empty();
                });
    }

    private static Duration 경과(long 시작나노) {
        return Duration.ofNanos(System.nanoTime() - 시작나노);
    }

    private static String 사람말로(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + "분" : duration.toMillis() + "ms";
    }
}
