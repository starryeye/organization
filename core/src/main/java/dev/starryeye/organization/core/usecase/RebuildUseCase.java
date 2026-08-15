package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * 전체 재적재.
 *
 * <p><b>SNAPSHOT 모드의 순서가 뒤집혀 있는 것이 핵심이다.</b> 스냅샷을 먼저 지우면
 * "이제는 없어야 할 튜플"을 지울 근거가 사라지고, read API 를 쓰지 않으므로 되찾을 수 없다.
 * 그래서 직전 스냅샷으로 먼저 전부 삭제한 다음에 스냅샷을 버린다.
 *
 * <p>삭제 가드는 적용하지 않는다. 전체 삭제가 의도된 동작이기 때문이다.
 */
@Slf4j
@RequiredArgsConstructor
public class RebuildUseCase {

    /** 직전 스냅샷이 없을 때 findLatest()의 빈 결과 대신 쓰는 자리표시자 — 삭제 단계를 건너뛰게 한다. */
    private static final TupleSnapshot EMPTY_SNAPSHOT =
            new TupleSnapshot("", Instant.EPOCH, SyncSource.LDAP, Set.of());

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final SyncRunRepository runs;
    private final Clock clock;

    public Mono<SyncRun> execute(RebuildMode mode) {
        return runs.start(SyncSource.LDAP, SyncTrigger.REBUILD)
                .doOnNext(run -> log.info("[{}] 전체 재적재 시작: mode={}", run.runId(), mode))
                .flatMap(run -> rebuild(mode)
                        .onErrorResume(error -> {
                            log.error("[{}] 전체 재적재 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    private Mono<SyncOutcome> rebuild(RebuildMode mode) {
        Mono<Void> clear = mode == RebuildMode.STORE ? clearByStoreReset() : clearBySnapshot();
        return clear.then(Mono.defer(this::reload));
    }

    /** store 를 통째로 재생성한다. 재적재까지 모든 인가 질의가 실패하는 공백이 생긴다. */
    private Mono<Void> clearByStoreReset() {
        log.warn("store 재생성 방식으로 재적재한다. 재적재가 끝날 때까지 인가 질의가 실패한다");
        return writer.resetStore().then(Mono.defer(snapshots::reset));
    }

    /**
     * 직전 스냅샷을 근거로 먼저 전부 지우고, 그 다음에 스냅샷을 버린다.
     * 직전 스냅샷이 없으면(또는 빈 스냅샷이면) 삭제 단계를 건너뛴다.
     * 삭제가 하나라도 실패하면 에러로 체인을 끊어, {@code snapshots.reset()} 이 실행되지 않게 한다.
     *
     * <p>이때 스냅샷을 원래 그대로 보존하면 안 된다. 실패해서 지워지지 않은 튜플과 이미 지워진
     * 튜플이 뒤섞여 있으므로, 스냅샷을 {@code T_old − result.deleted()} 로 갱신해 둬야 다음
     * 정기 동기화가 정확한 기준({@code toWrite = T_new − T_old})에서 출발한다. 그대로 두면
     * 이미 지워진 튜플이 여전히 스냅샷에 남아 다음 diff의 {@code toWrite}에서 영구히 빠지고,
     * 해당 직원은 접근 권한을 조용히 잃는다.
     *
     * <p>{@code snapshots.reset()} 을 {@link Mono#defer(java.util.function.Supplier)} 로 감싸는 것이
     * 중요하다. 감싸지 않으면 그 호출이 체인 조립 시점에 즉시 실행돼, 삭제가 실패해도 reset 이
     * 이미 일어난 뒤가 된다.
     */
    private Mono<Void> clearBySnapshot() {
        return snapshots.findLatest()
                .defaultIfEmpty(EMPTY_SNAPSHOT)
                .flatMap(this::clearPreviousSnapshot);
    }

    private Mono<Void> clearPreviousSnapshot(TupleSnapshot previousSnapshot) {
        Set<RelationTuple> previous = previousSnapshot.tuples();
        if (previous.isEmpty()) {
            return snapshots.reset();
        }
        return writer.apply(TupleDelta.deleteOnly(previous))
                .flatMap(result -> requireNoFailure(previous, result))
                .then(Mono.defer(snapshots::reset));
    }

    /**
     * 삭제가 실패하면, 에러로 체인을 끊기 전에 살아남은 튜플({@code previous − result.deleted()})만
     * 담은 스냅샷을 먼저 저장한다. 그래야 다음 정기 동기화가 이 스냅샷을 기준으로 실패분을 자동으로
     * 다시 잡는다 (design §8.2).
     */
    private Mono<TupleWriteResult> requireNoFailure(Set<RelationTuple> previous, TupleWriteResult result) {
        if (result.hasFailure()) {
            Set<RelationTuple> surviving = new HashSet<>(previous);
            surviving.removeAll(result.deleted());

            Instant now = clock.instant();
            TupleSnapshot survivingSnapshot = new TupleSnapshot(
                    SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, surviving);

            return snapshots.save(survivingSnapshot)
                    .then(Mono.<TupleWriteResult>error(new IllegalStateException(
                            "직전 스냅샷 삭제 중 %d건이 실패해 재적재를 중단합니다. 스냅샷을 남은 %d건으로 갱신했습니다"
                                    .formatted(result.failures().size(), surviving.size()))));
        }
        return Mono.just(result);
    }

    private Mono<SyncOutcome> reload() {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return writer.apply(TupleDelta.writeOnly(mapping.tuples()))
                    .flatMap(result -> commit(directory, result));
        });
    }

    private Mono<SyncOutcome> commit(DirectorySnapshot directory, TupleWriteResult result) {
        Set<RelationTuple> committed = result.written();

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed);

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }
}
