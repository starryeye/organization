package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
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
import java.util.function.Supplier;

/**
 * 동기화·재적재 작업을 요청과 떼어 돌린다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 *
 * <p><b>왜 떼는가.</b> 전에는 작업이 HTTP 요청의 구독 안에서 돌았다. 앞단 프록시가 60초에 연결을 끊으면 구독이 취소돼 작업이
 * 중간에 멈췄고, 실행 기록은 영원히 RUNNING 으로, 락은 풀린 채 남았다. 이제 작업은 자기 구독으로 돌고, 요청은 실행 기록(RUNNING)만
 * 받아 간다.
 *
 * <p>한 작업이 지키는 규칙:
 * <ol>
 *   <li>실행 기록을 연 뒤에만 일을 시작한다. 기록을 열지 못하면 반납하고 그 오류로 끝난다.</li>
 *   <li>일은 서버 종료·기한({@code timeout})과 경주한다. 먼저 온 쪽이 이기고 진 쪽은 취소된다 — 종료면 FAILED("서버 종료로 중단"),
 *       기한이면 FAILED("기한 초과 — N분"). 종료가 이미 시작됐으면 일을 시작하지도 않는다.</li>
 *   <li>어떻게 끝나든 <b>먼저 반납하고 그다음 기록한다.</b> 기록에서 "끝남"을 본 운영자가 곧바로 다시 걸면 받아져야 한다.</li>
 *   <li>요청의 Reactor Context 를 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다.</li>
 * </ol>
 *
 * <p><b>멈춤은 되돌리기가 아니다.</b> 이미 나간 쓰기는 무르지 않는다. 기한·종료로 멈추면 멈췄다는 사실을 FAILED 로 남길 뿐이고, 이미 나간
 * 쓰기를 모을 곳이 없어 새 스냅샷을 만들지 않는다 — 직전 스냅샷이 다음 회차의 기준으로 남는다. 그 사이 조직도가 바뀌면 기준선이 장부와
 * 어긋날 수 있어 재적재로 맞춘다(설계 §11). 연속 실패 차단기로 멈춘 쓰기는 다르다 — 유스케이스가 나간 것으로 기록 규칙을 지킨다(§5).
 */
@Slf4j
public class SyncJobs {

    static final String 종료_사유 = "서버 종료로 중단";

    /** 종료 때 작업이 반납·기록을 마치기를 기다리는 한도. 그 뒤에는 DynamoDB 클라이언트가 닫힌다. */
    private static final Duration 종료_대기 = Duration.ofSeconds(10);

    private final SyncRunRepository runs;
    private final Duration timeout;
    private final Sinks.Empty<Void> 종료 = Sinks.empty();
    private final Set<Mono<Void>> 도는_작업 = ConcurrentHashMap.newKeySet();

    public SyncJobs(SyncRunRepository runs, Duration timeout) {
        this.runs = runs;
        this.timeout = timeout;
    }

    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger,
                               Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        return start(source, trigger, work, release, run -> {
        });
    }

    /**
     * 실행 기록을 열고 {@code work} 를 요청과 떼어 띄운 뒤, 연 기록(RUNNING)을 준다. 구독할 때마다 작업 하나가 뜬다.
     *
     * @param release    겹침 검사(락·가드)를 푸는 수단. 작업이 어떻게 끝나든 정확히 한 번 부른다
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work,
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
                        return 끝까지_돌린다(run, work, release);
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
     * 종료 신호를 <b>먼저</b> 구독한다. 종료가 이미 시작됐으면 그 자리에서 이겨 {@code work} 를 구독하지도 않는다.
     */
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        Mono<SyncOutcome> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        return Mono.firstWithSignal(종료되면, work)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> {
                    // 메시지 없는 오류도 있다 — 이유를 비워 두면 기록만 보고는 무엇이 터졌는지 모른다
                    String 사유 = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                    log.error("[{}] 작업 실패: {}", run.runId(), 사유, error);
                    return Mono.just(SyncOutcome.failed(사유));
                })
                .flatMap(outcome -> 반납한다(release).thenReturn(outcome))
                .flatMap(outcome -> runs.finish(run, outcome))
                .doOnNext(finished -> log.info("[{}] 작업 끝: status={} written={} deleted={} failed={}",
                        finished.runId(), finished.status(), finished.writtenCount(),
                        finished.deletedCount(), finished.failureCount()))
                .onErrorResume(error -> {
                    log.error("[{}] 작업 결과를 기록하지 못했다 — 기록이 RUNNING 으로 남는다", run.runId(), error);
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

    private static String 사람말로(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + "분" : duration.toMillis() + "ms";
    }
}
