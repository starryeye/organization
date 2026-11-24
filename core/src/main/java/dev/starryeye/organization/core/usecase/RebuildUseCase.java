package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.ForceAllowance;
import dev.starryeye.organization.core.guard.ReactivationCheck;
import dev.starryeye.organization.core.guard.ReactivationGuard;
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
import java.util.Objects;
import java.util.function.Consumer;

/**
 * app-ldap 전체 재적재 (설계 2026-09-29 §3). LDAP 을 다시 읽어 장부(OpenFGA store)와 현재상태를 그것에 맞춘다.
 *
 * <p><b>장부를 버리지 않는다.</b> 먼저 LDAP 을 끝까지 읽고(실패하면 장부에 아무것도 하지 않는다), 있어야 할 줄을 전부 쓴 뒤, 장부를
 * 훑어 없어야 할 줄만 지운다({@link LedgerAlignment}). 직전 스냅샷에 기대지 않으므로 기준선 스냅샷이 깨졌거나 스냅샷에 없는 찌꺼기가
 * 있어도 고친다.
 *
 * <p><b>삭제 가드를 지킨다(설계 2026-09-30 §4.3).</b> LDAP 이 설정 실수로 0명을 돌려주면 장부 전체를 지우게 된다 — 지울 줄이 훑은 장부의
 * 임계치 또는 상한을 넘으면 지우지 않고 ABORTED 다. 다만 디렉터리가 막았다고 명시한 회수만으로는 가드 안이면 그것만 지운다(설계 2026-10-20 §5.4,
 * {@link LedgerAlignment}). 사람이 실행 기록의 멈춤 내역을 확인한 뒤 허용 수를 주어 강제로 건다({@link #startForced}) — 강제 재적재도 가드를 건너뛰지
 * 않고, 비율 없이 허용 수 N 을 상한으로 같은 가드를 본다(설계 2026-11-20 F1·F2).
 *
 * <p><b>재활성 가드(설계 2026-11-24 R4·R8).</b> 장부를 맞추기 전에 최신 스냅샷의 비활성 소속 줄(I)로 재활성을 판정한다 — 동기화의 기록 중 회차와 같은
 * 규칙이다. 걸리면 그 직원들을 비활성으로 되돌린 그림′ 으로 매핑해 맞추고(그들의 줄은 쓰지 않고 현재상태는 비활성 그대로, 새 스냅샷의 I 에 남는다) 회차는
 * ABORTED 이며 보류 내역이 남는다. 스냅샷이 없거나 읽지 못하면(WARN) 판정하지 않는다 — 재적재는 깨진 스냅샷을 고치는 통로라 그것 때문에 실패시키지 않는다.
 * 강제 재적재는 준 수의 가드만 바꾼다({@link ForceAllowance}, R9). 삭제 가드가 전부 보류하면 스냅샷이 남지 않아 그 회차에 통과한 재활성이 기억되지
 * 않는다(설계 2026-11-24 §6).
 *
 * <p><b>이력(설계 2026-10-10 §4.2).</b> 회차 id 로, 현재상태가 바뀐 것과 지운 줄을 남긴다. 끝나는 즉시 회차의 변경 모음에 더하고, 회차가 어떻게 끝나든
 * {@link SyncJobs} 가 락을 반납한 뒤 남긴다(설계 2026-10-15).
 */
@Slf4j
public class RebuildUseCase {

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DeletionGuard guard;
    private final ReactivationGuard reactivationGuard;
    private final SyncJobs jobs;
    private final LedgerAlignment alignment;

    /**
     * @param guard             설정의 삭제 가드
     * @param reactivationGuard 설정의 재활성 가드(설계 2026-11-24 R3)
     */
    public RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                          DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                          DeletionGuard guard, ReactivationGuard reactivationGuard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.snapshots = snapshots;
        this.guard = guard;
        this.reactivationGuard = reactivationGuard;
        this.jobs = jobs;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, clock);
    }

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 재적재를 요청과 떼어 띄운다. 못 잡으면 {@link LockUnavailableException}.
     * 트리거는 {@link SyncTrigger#REBUILD} 이고 설정의 가드를 본다.
     *
     * @param onFinished 끝난 기록으로 불린다(지표)
     */
    public Mono<SyncRun> start(Consumer<SyncRun> onFinished) {
        return 띄운다(SyncTrigger.REBUILD, null, onFinished);
    }

    /**
     * 허용 수를 받은 강제 재적재 — ABORTED 뒤 사람이 멈춤 내역을 확인하고 넘기는 통로다(설계 2026-11-20 F1·F2, 2026-11-24 R9). 가드를 건너뛰지 않는다 —
     * 준 수의 가드만 비율 없이 그 수를 상한으로 보고(지울 줄이 N 을 넘으면 장부의 후보를 지우지 않고, 재활성이 M 을 넘으면 보류한다), 주지 않은 가드는
     * 설정대로 본다. 결론을 낸 회차는 결론 앞에 {@link ForceAllowance#note()} 를 적는다(F4).
     * 실행 기록의 트리거는 {@link SyncTrigger#FORCED_REBUILD} 로 남는다(동기화의 {@code FORCED} 와 같다). 락 용도는 REBUILD 그대로다.
     *
     * @param onFinished 끝난 기록으로 불린다(지표)
     */
    public Mono<SyncRun> startForced(ForceAllowance allowance, Consumer<SyncRun> onFinished) {
        return 띄운다(SyncTrigger.FORCED_REBUILD, Objects.requireNonNull(allowance, "allowance"), onFinished);
    }

    /** 이번 회차의 두 가드를 한 번 고르고 띄운다 — 허용 수에 준 수가 있는 가드는 그 수를 상한으로 한 강제 가드, 아니면 설정의 가드다. */
    private Mono<SyncRun> 띄운다(SyncTrigger trigger, ForceAllowance allowance, Consumer<SyncRun> onFinished) {
        DeletionGuard 이번_가드 = FullSyncUseCase.삭제_가드를_고른다(allowance, guard);
        ReactivationGuard 이번_재활성_가드 = FullSyncUseCase.재활성_가드를_고른다(allowance, reactivationGuard);
        return jobs.startLockedThen(SyncSource.LDAP, trigger, MutationLock.LockPurpose.REBUILD, AuditKind.REBUILD,
                (run, 기록) -> FullSyncUseCase.허용_수를_적는다(
                        Mono.defer(() -> rebuild(이번_가드, 이번_재활성_가드, 기록.changes())), allowance),
                onFinished);
    }

    /**
     * LDAP 을 다 읽고, 최신 스냅샷의 I 로 재활성을 판정한 뒤(없거나 읽지 못하면 판정 없음), 판정이 정한 그림(보류면 그림′)으로 매핑해 장부를 맞춘다
     * (설계 2026-11-24 §3.3). 결론에 보류를 적는다 — 강제 문구는 그보다 바깥이다.
     */
    private Mono<Mono<SyncOutcome>> rebuild(DeletionGuard 가드, ReactivationGuard 재활성_가드, AuditChanges 기록) {
        return source.fetchAll().flatMap(directory -> FullSyncUseCase.직전_I_를_읽어_본다(snapshots).flatMap(직전_I -> {
            ReactivationCheck check = FullSyncUseCase.재활성을_판정한다(재활성_가드, 직전_I, directory);
            TupleMappingResult mapping = TupleMapper.toTuples(check.picture());
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            return FullSyncUseCase.재활성_보류를_적는다(alignment.align(check.picture(), mapping.tuples(), 가드, 기록), check);
        }));
    }
}
