package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeSyncRunRepository implements SyncRunRepository {

    /** 끝난 기록 — 끝난 순서대로. 작업 스레드가 쓰고 테스트 스레드가 읽는다. */
    public final List<SyncRun> finished = new CopyOnWriteArrayList<>();

    /** runId → 지금 기록(RUNNING 이거나 끝난 것). 넣은 순서를 지킨다. */
    private final Map<String, SyncRun> 기록 = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Sinks.One<SyncRun>> 끝남 = new ConcurrentHashMap<>();
    private final Instant now;
    private RuntimeException startFailure;

    public FakeSyncRunRepository(Instant now) {
        this.now = now;
    }

    /** 설정하면 {@link #start} 가 이 예외로 실패한다. 실행 기록을 열지 못하는 경로를 보는 데 쓴다. */
    public void failStart(RuntimeException failure) {
        this.startFailure = failure;
    }

    /** 기록을 직접 심는다. 지난 프로세스가 남긴 RUNNING 기록 같은 것을 흉내 낸다. */
    public void seed(SyncRun run) {
        기록.put(run.runId(), run);
    }

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        return Mono.defer(() -> {
            if (startFailure != null) {
                return Mono.error(startFailure);
            }
            SyncRun run = SyncRun.started(UUID.randomUUID().toString(), source, trigger, now);
            기록.put(run.runId(), run);
            return Mono.just(run);
        });
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        return Mono.fromCallable(() -> {
            SyncRun done = run.finished(outcome, now);
            기록.put(done.runId(), done);
            finished.add(done);
            끝남신호(done.runId()).tryEmitValue(done);
            return done;
        });
    }

    @Override
    public Flux<SyncRun> findRecent(int limit) {
        List<SyncRun> 최신순;
        synchronized (기록) {
            최신순 = new ArrayList<>(기록.values());
        }
        Collections.reverse(최신순);
        return Flux.fromIterable(최신순).take(limit);
    }

    @Override
    public Mono<SyncRun> findById(String runId) {
        return Mono.fromSupplier(() -> 기록.get(runId));
    }

    /**
     * 이 기록이 끝날 때까지 기다린다(최대 10초). 요청과 떼어 도는 작업의 결과를 테스트가 받는 자리다.
     * 10초 안에 안 끝나면 {@code IllegalStateException} 이다.
     */
    public SyncRun awaitFinished(String runId) {
        return 끝남신호(runId).asMono().block(Duration.ofSeconds(10));
    }

    private Sinks.One<SyncRun> 끝남신호(String runId) {
        return 끝남.computeIfAbsent(runId, id -> Sinks.one());
    }
}
