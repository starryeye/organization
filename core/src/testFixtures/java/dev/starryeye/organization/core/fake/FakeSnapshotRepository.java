package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeSnapshotRepository implements TupleSnapshotRepository {

    public final List<TupleSnapshot> saved = new ArrayList<>();

    /** "기록 중" 표시 — markWriting 이 켜고 save 가 끈다(설계 2026-09-30 §4.1). 테스트가 직접 켜 "지난 회차가 기록 전에 멈춤"을 흉내 낸다. */
    public final AtomicBoolean writing = new AtomicBoolean();

    /** purgeExpired 가 불린 횟수. 하루 1회 작업이 한 번만 도는지 보는 데 쓴다. */
    public final AtomicInteger purgeCalls = new AtomicInteger();

    private RuntimeException findLatestError;
    private RuntimeException saveError;
    private RuntimeException markWritingError;

    /** 기준선이 깨진 저장소를 흉내 낸다 — findLatest 가 이 오류로 끝난다. */
    public void failFindLatest(RuntimeException error) {
        this.findLatestError = error;
    }

    /** 설정하면 save 가 이 오류로 끝난다 — 쓰기 뒤·스냅샷 저장 전에 멈춘 회차를 흉내 낸다. */
    public void failSave(RuntimeException error) {
        this.saveError = error;
    }

    /** 설정하면 markWriting 이 이 오류로 끝난다. */
    public void failMarkWriting(RuntimeException error) {
        this.markWritingError = error;
    }

    @Override
    public Mono<TupleSnapshot> findLatest() {
        if (findLatestError != null) {
            return Mono.error(findLatestError);
        }
        return saved.isEmpty() ? Mono.empty() : Mono.just(saved.get(saved.size() - 1));
    }

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return Mono.defer(() -> {
            if (saveError != null) {
                return Mono.error(saveError);
            }
            saved.add(snapshot);
            writing.set(false);
            return Mono.empty();
        });
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
    public Mono<Void> markWriting() {
        return Mono.defer(() -> {
            if (markWritingError != null) {
                return Mono.error(markWritingError);
            }
            writing.set(true);
            return Mono.empty();
        });
    }

    @Override
    public Mono<Boolean> isWriting() {
        return Mono.fromSupplier(writing::get);
    }

    @Override
    public Mono<Integer> purgeExpired() {
        return Mono.fromSupplier(() -> {
            purgeCalls.incrementAndGet();
            return 0;
        });
    }
}
