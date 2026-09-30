package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * app-ldap 전체 재적재 (설계 2026-09-29 §3). LDAP 을 다시 읽어 장부(OpenFGA store)와 현재상태를 그것에 맞춘다.
 *
 * <p><b>장부를 버리지 않는다.</b> 먼저 LDAP 을 끝까지 읽고(실패하면 장부에 아무것도 하지 않는다), 있어야 할 줄을 전부 쓴 뒤, 장부를
 * 훑어 없어야 할 줄만 지운다({@link LedgerAlignment}). 직전 스냅샷에 기대지 않으므로 기준선 스냅샷이 깨졌거나 스냅샷에 없는 찌꺼기가
 * 있어도 고친다.
 *
 * <p><b>삭제 가드를 지킨다(설계 2026-09-30 §4.3).</b> LDAP 이 설정 실수로 0명을 돌려주면 장부 전체를 지우게 된다 — 지울 줄이 훑은 장부의
 * 임계치를 넘으면 지우지 않고 ABORTED 다. 사람이 확인한 뒤 {@code force} 로 넘긴다.
 */
@Slf4j
public class RebuildUseCase {

    private final DirectorySnapshotSource source;
    private final SyncJobs jobs;
    private final LedgerAlignment alignment;

    public RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                          DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                          DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.jobs = jobs;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);
    }

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 재적재를 요청과 떼어 띄운다. 못 잡으면 {@link LockUnavailableException}.
     *
     * @param force      true 면 삭제 가드를 건너뛴다 — ABORTED 뒤 사람이 확인하고 넘기는 통로다
     * @param onFinished 끝난 기록으로 불린다(지표)
     */
    public Mono<SyncRun> start(boolean force, Consumer<SyncRun> onFinished) {
        return jobs.startLocked(SyncSource.LDAP, SyncTrigger.REBUILD, MutationLock.LockPurpose.REBUILD,
                Mono.defer(() -> rebuild(force)), onFinished);
    }

    private Mono<SyncOutcome> rebuild(boolean force) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            return alignment.align(directory, mapping.tuples(), force);
        });
    }
}
