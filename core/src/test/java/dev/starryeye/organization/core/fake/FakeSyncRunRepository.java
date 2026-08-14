package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FakeSyncRunRepository implements SyncRunRepository {

    public final List<SyncRun> finished = new ArrayList<>();
    private final Instant now;

    public FakeSyncRunRepository(Instant now) {
        this.now = now;
    }

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        return Mono.just(SyncRun.started(UUID.randomUUID().toString(), source, trigger, now));
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        SyncRun done = run.finished(outcome, now);
        finished.add(done);
        return Mono.just(done);
    }

    @Override
    public Flux<SyncRun> findRecent(int limit) {
        return Flux.fromIterable(finished).take(limit);
    }
}
