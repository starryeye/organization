package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

public class FakeSnapshotSource implements DirectorySnapshotSource {

    private DirectorySnapshot snapshot = DirectorySnapshot.empty();
    private RuntimeException failure;
    public final AtomicInteger fetchCount = new AtomicInteger();

    public void willReturn(DirectorySnapshot snapshot) {
        this.snapshot = snapshot;
        this.failure = null;
    }

    public void willFail(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public Mono<DirectorySnapshot> fetchAll() {
        fetchCount.incrementAndGet();
        return failure != null ? Mono.error(failure) : Mono.just(snapshot);
    }
}
