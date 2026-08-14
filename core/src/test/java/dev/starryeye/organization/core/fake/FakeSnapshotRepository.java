package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeSnapshotRepository implements TupleSnapshotRepository {

    public final List<TupleSnapshot> saved = new ArrayList<>();
    public final AtomicInteger resetCount = new AtomicInteger();

    @Override
    public Mono<TupleSnapshot> findLatest() {
        return saved.isEmpty() ? Mono.empty() : Mono.just(saved.get(saved.size() - 1));
    }

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        saved.add(snapshot);
        return Mono.empty();
    }

    @Override
    public Flux<SnapshotMeta> listRecent(int days) {
        return Flux.fromIterable(saved).map(TupleSnapshot::meta);
    }

    @Override
    public Mono<TupleSnapshot> findById(String snapshotId) {
        return Flux.fromIterable(saved).filter(s -> s.id().equals(snapshotId)).next();
    }

    @Override
    public Mono<Void> reset() {
        resetCount.incrementAndGet();
        saved.clear();
        return Mono.empty();
    }

    @Override
    public Mono<Integer> purgeExpired() {
        return Mono.just(0);
    }
}
