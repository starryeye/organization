package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * app-ldap 전체 재적재 (설계 2026-09-29 §3). LDAP 을 다시 읽어 장부(OpenFGA store)와 현재상태를 그것에 맞춘다.
 *
 * <p><b>장부를 버리지 않는다.</b> 먼저 LDAP 을 끝까지 읽고(실패하면 장부에 아무것도 하지 않는다), 있어야 할 줄을 전부 쓴 뒤, 장부를
 * 훑어 없어야 할 줄만 지운다({@link TupleReconciler}). 직전 스냅샷에 기대지 않으므로 기준선 스냅샷이 깨졌거나 스냅샷에 없는 찌꺼기가
 * 있어도 고친다 — 옛 {@code snapshot}(스냅샷에 없는 줄은 못 지움)·{@code store}(장부를 지우고 다시 만들어 인가 공백·번호 바뀜) 두 모드의
 * 장점만 가진다.
 *
 * <p>삭제 가드는 적용하지 않는다. 사람이 "지금 LDAP 이 진실"이라고 판단해 거는 작업이다.
 */
@Slf4j
@RequiredArgsConstructor
public class RebuildUseCase {

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final SyncJobs jobs;
    private final Clock clock;

    /**
     * 실행 기록(RUNNING)을 열고 재적재를 요청과 떼어 띄운다. 겹침 검사(실행 가드)는 호출자가 하고 그 반납 수단을 넘긴다 —
     * 재적재가 어떻게 끝나든 한 번 불린다. {@code onFinished} 는 끝난 기록으로 불린다(지표).
     */
    public Mono<SyncRun> start(Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return jobs.start(SyncSource.LDAP, SyncTrigger.REBUILD, Mono.defer(this::rebuild), release, onFinished);
    }

    private Mono<SyncOutcome> rebuild() {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples())
                    .flatMap(reconciliation -> commit(directory, reconciliation));
        });
    }

    /**
     * 스냅샷에는 장부에 실제로 있다고 볼 줄을 담는다(설계 §3.1 4단계). 그다음 현재상태를 LDAP 대로 바꾼다. 지우기가 차단기로 멈췄어도
     * 같다 — 기록 규칙을 지킨 뒤 FAILED 로 남긴다(설계 §5).
     */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger());

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(reconciliation.outcome(snapshot.id()));
    }
}
