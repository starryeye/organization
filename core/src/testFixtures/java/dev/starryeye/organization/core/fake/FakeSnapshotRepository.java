package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.RevocationMarks;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.BaselineMovedException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private RuntimeException markRevokingError;
    private RuntimeException settleRevocationError;

    /** 포인터 줄의 즉시 회수 표시 두 칸(설계 2026-11-28 V6·V8). save 가 비운다. */
    private final Set<RelationTuple> revokedGone = new HashSet<>();
    private final Set<RelationTuple> revokedUnknown = new HashSet<>();

    /** 지금 남아 있는 즉시 회수 표시. */
    public RevocationMarks marks() {
        return new RevocationMarks(revokedGone, revokedUnknown);
    }

    /** 설정하면 markRevoking 이 이 오류로 끝난다 — 한도 초과 등을 흉내 낸다(가짜는 한도를 세지 않는다). */
    public void failMarkRevoking(RuntimeException error) {
        this.markRevokingError = error;
    }

    /** 설정하면 settleRevocation 이 이 오류로 끝난다. */
    public void failSettleRevocation(RuntimeException error) {
        this.settleRevocationError = error;
    }

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
    public Mono<MarkedSnapshot> findLatestMarked() {
        if (findLatestError != null) {
            return Mono.error(findLatestError);
        }
        return saved.isEmpty() ? Mono.empty()
                : Mono.just(new MarkedSnapshot(saved.get(saved.size() - 1), marks()));
    }

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return Mono.defer(() -> {
            if (saveError != null) {
                return Mono.error(saveError);
            }
            saved.add(snapshot);
            writing.set(false);
            revokedGone.clear();
            revokedUnknown.clear();
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

    /** 기록 중 표시가 있으면 기록 중, 저장한 것이 없으면 없음, 아니면 있음. 번호는 마지막으로 저장한 스냅샷의 것이다. */
    @Override
    public Mono<BaselinePointer> baselinePointer() {
        return Mono.fromSupplier(() -> {
            String id = saved.isEmpty() ? null : saved.get(saved.size() - 1).id();
            BaselineState state = writing.get() ? BaselineState.WRITING
                    : saved.isEmpty() ? BaselineState.NONE
                    : BaselineState.PRESENT;
            return new BaselinePointer(state, id);
        });
    }

    /** 조건: 마지막 저장 번호가 같고 기록 중 표시가 없다. 빈 집합은 조건도 안 본다. 한도는 세지 않고 {@link #failMarkRevoking} 으로 흉내 낸다. */
    @Override
    public Mono<Void> markRevoking(String snapshotId, Set<RelationTuple> tuples) {
        return Mono.defer(() -> {
            if (tuples.isEmpty()) {
                return Mono.empty();
            }
            if (markRevokingError != null) {
                return Mono.error(markRevokingError);
            }
            if (!pointerHolds(snapshotId)) {
                return Mono.error(new BaselineMovedException("기준선이 움직였습니다"));
            }
            revokedUnknown.addAll(tuples);
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> settleRevocation(String snapshotId, Set<RelationTuple> gone, Set<RelationTuple> dropped) {
        return Mono.defer(() -> {
            if (gone.isEmpty() && dropped.isEmpty()) {
                return Mono.empty();
            }
            if (settleRevocationError != null) {
                return Mono.error(settleRevocationError);
            }
            if (!pointerHolds(snapshotId)) {
                return Mono.error(new BaselineMovedException("기준선이 움직였습니다"));
            }
            revokedUnknown.removeAll(gone);
            revokedUnknown.removeAll(dropped);
            revokedGone.addAll(gone);
            return Mono.empty();
        });
    }

    private boolean pointerHolds(String snapshotId) {
        return !writing.get() && !saved.isEmpty() && saved.get(saved.size() - 1).id().equals(snapshotId);
    }

    @Override
    public Mono<Integer> purgeExpired() {
        return Mono.fromSupplier(() -> {
            purgeCalls.incrementAndGet();
            return 0;
        });
    }
}
