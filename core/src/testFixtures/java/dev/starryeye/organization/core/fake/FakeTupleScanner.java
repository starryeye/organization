package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜 장부 훑기. {@link FakeTupleWriter#stored} — 쓰면 들어가고 지우면 빠지는 가짜 장부 — 를 그대로 흘린다.
 */
public class FakeTupleScanner implements RelationTupleScanner {

    public final AtomicInteger scanCount = new AtomicInteger();

    private final FakeTupleWriter writer;
    private RuntimeException failure;

    public FakeTupleScanner(FakeTupleWriter writer) {
        this.writer = writer;
    }

    /** 설정하면 훑기가 이 예외로 실패한다. */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public Flux<RelationTuple> scanAll() {
        return Flux.defer(() -> {
            scanCount.incrementAndGet();
            if (failure != null) {
                return Flux.error(failure);
            }
            List<RelationTuple> 사본;
            synchronized (writer.stored) {
                사본 = new ArrayList<>(writer.stored);
            }
            return Flux.fromIterable(사본);
        });
    }
}
