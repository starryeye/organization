package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.RunAudit;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.ForceAllowance;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.guard.ReactivationCheck;
import dev.starryeye.organization.core.guard.ReactivationGuard;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.GuardHold;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.RevocationMarks;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LDAP 전체 동기화.
 *
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 반영됐다고 확실한 튜플만 새 스냅샷의 기준선(B)으로 커밋</b>하는 것이다.
 * 거절되거나 보내지 않은 쓰기는 B 에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 *
 * <p><b>결과를 모르는 줄은 불확실 줄(U)로 남는다(설계 2026-10-20 §3).</b> 보냈는데 일시 오류로 결과를 모르는 쓰기·지우기는 서버가 반영하고 응답만 잃었을 수
 * 있다. 결과를 모르는 쓰기를 B 에서 빼 두면 그 줄이 목표에서 빠질 때 지울 기준이 없어 권한이 영원히 남고, 결과를 모르는 지우기를 B 에 두면 목표에 다시 들 때
 * 쓸 줄로 잡히지 않아 권한이 영원히 빠진다. 그래서 B 와 따로 U 에 적고, 다음 회차가 목표에 있으면 쓰고 없으면 지운다 — 쓸 줄 = D − B,
 * 지울 줄 = (B − D) ∪ (U − D). 삭제 가드의 기준 수는 |B| + |U| 다.
 *
 * <p><b>가드가 걸려도 디렉터리가 명시한 회수는 먼저 반영한다(설계 2026-10-20 §5).</b> 지울 줄 전체가 삭제 가드를 넘으면 그 가운데 디렉터리가 막았다고
 * 명시한 직원의 소속 줄({@link TupleMapper#inactiveMemberTuples})만 떼어 같은 가드로 다시 본다. 통과하면 그 지우기만 반영하고 스냅샷을 남긴다 —
 * 부여·나머지 지우기·현재상태 교체는 보류하고 ABORTED 다. 그것마저 넘으면 지금처럼 아무것도 반영하지 않는다. 큰 개편 하나가 퇴사자 회수를 매일 멈추던
 * 것을 막는다.
 *
 * <p><b>강제 회차도 가드를 본다(설계 2026-11-20 F1·F2·F4).</b> 강제({@code FORCED})는 운영자가 멈춤 내역을 보고 정한 허용 수 N 과 함께만 든다
 * ({@link #startForced}). 그 회차는 비율 없이 N 을 상한으로 같은 가드를 비교 경로와 장부 맞추기 모두에서 본다 — 확인한 뒤 디렉터리가 더 망가지면 다시
 * 멈춘다. 결론을 낸 강제 회차는 결론 앞에 허용 수를 적는다.
 *
 * <p><b>재활성 가드(설계 2026-11-24 R4·R8).</b> 막혔던 계정이 한꺼번에 활성으로 읽히면(동기화 계정이 계정 상태 속성을 못 읽게 된 경우 등) 그 권한이
 * 조용히 돌아온다. 그래서 매핑 앞에서 직전 스냅샷의 비활성 소속 줄(I)로 재활성을 판정한다({@link ReactivationGuard}). 걸리면 그 회차의 재활성 전부를
 * 비활성으로 되돌린 그림′ 으로 진행한다 — 매핑·삭제 가드·멈춤 내역·현재상태 교체·새 스냅샷의 I 가 모두 그림′ 을 본다. 나머지 변경은 평소대로 반영하고
 * 회차는 ABORTED 이며 보류 내역({@code reactivationHold})이 남는다. 포인터가 있으면 비교의 기준선과 같은 스냅샷 한 번에서 I 를 읽는다. 기록 중 회차는
 * 최신 스냅샷을 따로 읽어 보고, 없거나 읽지 못하면(WARN) 판정하지 않는다. 포인터가 없는 회차는 판정하지 않는다. 강제 회차는 준 수의 가드만 바꾼다
 * ({@link ForceAllowance}, R9). 장부 맞추기가 삭제 가드로 전부 보류되면 스냅샷이 남지 않아 그 회차에 통과한 재활성이 기억되지 않는다 — 받아들인
 * 한계다({@link LedgerAlignment}, 설계 2026-11-24 §6).
 *
 * <p><b>기록 중 표시(설계 2026-09-30 §4).</b> OpenFGA 에 쓰기 직전 스냅샷 포인터에 "기록 중"을 남기고, 스냅샷 저장이 그것을 지운다. 표시가 남아
 * 있으면 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라 기준선을 믿을 수 없다 — 이번 회차는 비교 대신 장부를 훑어 맞춘다({@link LedgerAlignment}).
 * 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일(점검 M2)이 이것으로 막힌다.
 *
 * <p><b>포인터가 없을 때도 기준선을 믿지 않는다(설계 2026-10-20 §4).</b> 첫 회차이거나 테이블을 새로 만들었다 — 후자는 이미 장부(store)에 줄이 있는데 빈 기준선과
 * 비교해 지울 줄이 없다고 잘못 보게 된다. 그래서 비교 대신 같은 장부 맞추기로 간다. 빈 장부의 첫 설치는 D 를 다 쓰고 장부를 한 번 훑을 뿐이다. 포인터 상태는 셋이다
 * — 없음(장부 맞추기, 이력은 동기화 그대로), 기록 중(장부 맞추기, 이력은 장부 맞추기), 있음(비교).
 *
 * <p><b>즉시 회수 표시를 기준선에 반영한다(설계 2026-11-28 V10).</b> 한 사람 즉시 회수가 스냅샷 포인터에 남긴 표시(gone·unknown)가 있으면 비교의 기준선은
 * B′ = B − gone − unknown, U′ = (U − gone) ∪ unknown 이다 — 델타·삭제 가드의 기준 수·새 스냅샷의 식이 모두 B′·U′ 를 본다. 변경이 없어도 표시가 있었으면
 * 스냅샷을 새로 남겨 표시를 지운다. 기록 중·포인터 없음 회차는 표시를 읽지 않고, 스냅샷 저장이 표시를 지운다.
 *
 * <p><b>이력(설계 2026-10-10 §4, 2026-10-15).</b> 회차가 OpenFGA 에 쓰고 지운 튜플은 묶음마다, 현재상태가 바뀐 것은 직원·조직 하나마다 끝나는 즉시
 * 회차의 변경 모음에 더한다. 회차가 어떻게 끝나든 {@link SyncJobs} 가 락을 반납한 뒤 남긴다 — 스냅샷 저장·현재상태 교체가 실패하거나 리스를 잃어도
 * 이미 한 변경이 남는다. "기록 중" 때문에 장부를 훑어 맞춘 회차는 종류를 장부 맞추기로 바꾸고 지운 줄만 남긴다. 기준선이 없어 훑은 첫 회차는 종류가 동기화 그대로이고
 * 쓴 줄(첫 적재의 부여 이력)도 남긴다.
 */
@Slf4j
public class FullSyncUseCase {

    static final String 기준선_의심 = "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤";
    static final String 기준선_없음 = "기준선 없음(첫 회차 또는 새 테이블) — 장부를 훑어 맞춤";

    /**
     * 차단기로 멈췄지만 현재상태는 디렉터리대로 실제로 교체한 회차의 메시지 끝에 붙인다(설계 2026-10-27 §12). 권한은 틀리지 않는데 FAILED 만 보면
     * 현재상태도 못 바꿨다고 읽히기 때문이다. 교체하지 않은 차단기 경로(비활성 회수만 반영, 장부 맞추기의 일부 반영, 쓰기 단계)에는 붙이지 않는다.
     */
    static final String 현재상태_교체 = " — 현재상태는 디렉터리대로 교체했다";

    /** 재활성을 보류한 회차의 결론 앞 문구(설계 2026-11-24 R6). 뒤에 가드 문구가 바로 잇는다. */
    static final String 재활성_보류 = "재활성 가드 발동 — 직원 %d명의 재활성을 보류(비활성 그대로 둠) / ";

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final DeletionGuard guard;
    private final ReactivationGuard reactivationGuard;
    private final SyncJobs jobs;
    private final Clock clock;
    private final LedgerAlignment alignment;

    /**
     * @param guard             설정의 삭제 가드
     * @param reactivationGuard 설정의 재활성 가드(설계 2026-11-24 R3)
     */
    public FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                           DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                           DeletionGuard guard, ReactivationGuard reactivationGuard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.snapshots = snapshots;
        this.state = state;
        this.writer = writer;
        this.guard = guard;
        this.reactivationGuard = reactivationGuard;
        this.jobs = jobs;
        this.clock = clock;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, clock);
    }

    /**
     * 작업 락(SYNC)을 잡고 실행 기록(RUNNING)을 연 뒤 동기화를 요청과 떼어 띄운다(설계 2026-09-30 §3). 못 잡으면
     * {@link LockUnavailableException} — 다른 인스턴스가 동기화·재적재 중이다. {@code onFinished} 는 끝난 기록으로 불린다(지표·로그).
     *
     * @param trigger {@code SCHEDULED}·{@code MANUAL} 만 받는다. 그 밖(특히 {@code FORCED})이면 회차를 열기 전에 곧바로
     *                {@link IllegalArgumentException} 이다 — 강제는 허용 수와 함께 {@link #startForced} 로만 든다(설계 2026-11-20 F1)
     */
    public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
        if (trigger != SyncTrigger.SCHEDULED && trigger != SyncTrigger.MANUAL) {
            throw new IllegalArgumentException(
                    "동기화의 일반 입구는 SCHEDULED·MANUAL 만 받는다 — 강제는 허용 수와 함께 startForced 로 건다: " + trigger);
        }
        return 띄운다(trigger, null, onFinished);
    }

    /**
     * 허용 수를 받은 강제 동기화(설계 2026-11-20 F1·F2, 2026-11-24 R9). 트리거는 {@code FORCED} 다. 가드를 건너뛰지 않는다 — 준 수의 가드만 비율 없이 그
     * 수를 상한으로 보고, 주지 않은 가드는 설정대로 본다. 비교 경로와 장부 맞추기 모두 같다. 결론을 낸 회차는 결론 앞에 {@link ForceAllowance#note()} 를
     * 적는다(F4). 락·기록은 {@link #start} 와 같다.
     */
    public Mono<SyncRun> startForced(ForceAllowance allowance, Consumer<SyncRun> onFinished) {
        return 띄운다(SyncTrigger.FORCED, Objects.requireNonNull(allowance, "allowance"), onFinished);
    }

    /** 이번 회차의 두 가드를 한 번 고르고 띄운다 — 허용 수에 준 수가 있는 가드는 그 수를 상한으로 한 강제 가드, 아니면 설정의 가드다. */
    private Mono<SyncRun> 띄운다(SyncTrigger trigger, ForceAllowance allowance, Consumer<SyncRun> onFinished) {
        DeletionGuard 이번_가드 = 삭제_가드를_고른다(allowance, guard);
        ReactivationGuard 이번_재활성_가드 = 재활성_가드를_고른다(allowance, reactivationGuard);
        return jobs.startLockedThen(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (run, 기록) -> 허용_수를_적는다(Mono.defer(() -> synchronize(이번_가드, 이번_재활성_가드, 기록)), allowance),
                onFinished);
    }

    /** 이번 회차의 삭제 가드 — {@code maxDeletes} 를 주었으면 그 수를 상한으로 한 강제 가드, 아니면 설정의 가드(설계 2026-11-24 R9). 재적재도 쓴다. */
    static DeletionGuard 삭제_가드를_고른다(ForceAllowance allowance, DeletionGuard 설정) {
        return allowance != null && allowance.maxDeletes() != null ? DeletionGuard.forced(allowance.maxDeletes()) : 설정;
    }

    /** 이번 회차의 재활성 가드 — {@code maxReactivations} 를 주었으면 그 수를 상한으로 한 강제 가드, 아니면 설정의 가드(R9). 재적재도 쓴다. */
    static ReactivationGuard 재활성_가드를_고른다(ForceAllowance allowance, ReactivationGuard 설정) {
        return allowance != null && allowance.maxReactivations() != null
                ? ReactivationGuard.forced(allowance.maxReactivations())
                : 설정;
    }

    /**
     * 강제 회차면 "반납 뒤 할 일"의 결론 앞에 허용 수를 적는다(설계 2026-11-20 F4) — 결론을 다 지은 뒤 가장 바깥에서 한 번 붙이므로 장부 맞추기의
     * 사정({@code 기준선 없음 …}·{@code 기준선 의심 …})보다 앞에 온다. 변경 없음도 붙는다. 예외로 끝난 회차는 결론이 없어 붙지 않는다(설계 §6).
     * 재적재({@link RebuildUseCase})도 이것을 쓴다.
     */
    static Mono<Mono<SyncOutcome>> 허용_수를_적는다(Mono<Mono<SyncOutcome>> 일, ForceAllowance allowance) {
        return allowance == null
                ? 일
                : 일.map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withNote(allowance.note())));
    }

    /**
     * 재활성을 보류한 회차면 "반납 뒤 할 일"의 결론에 보류를 적는다(설계 2026-11-24 R6·R7) — 상태(SUCCEEDED·PARTIAL 은 ABORTED, ABORTED·FAILED 는
     * 그대로), 보류 내역, 앞 문구({@link #재활성_보류} + 가드 문구)를 한 번에 바꾼다. 경로의 결론(장부 맞추기의 사정 문구 포함)을 다 지은 뒤에 붙이고,
     * 강제 회차의 허용 수({@link #허용_수를_적는다})는 이것보다 바깥이다(§3.3). 보류가 아니면 그대로 둔다. 예외로 끝난 회차는 결론이 없어 남지 않는다(§6).
     * 재적재({@link RebuildUseCase})도 이것을 쓴다.
     */
    static Mono<Mono<SyncOutcome>> 재활성_보류를_적는다(Mono<Mono<SyncOutcome>> 일, ReactivationCheck check) {
        if (!check.held()) {
            return 일;
        }
        String 앞_문구 = 재활성_보류.formatted(check.hold().count()) + check.message();
        return 일.map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withReactivationHold(check.hold(), 앞_문구)));
    }

    /** 직전 I 와 이번 그림으로 재활성을 판정한다(설계 2026-11-24 §3.2). 보류면 WARN 을 남긴다 — 가드 문구는 수만 담는다(id 는 싣지 않는다). 재적재도 쓴다. */
    static ReactivationCheck 재활성을_판정한다(ReactivationGuard 가드, Set<RelationTuple> 직전_I, DirectorySnapshot directory) {
        ReactivationCheck check = 가드.check(직전_I, directory);
        if (check.held()) {
            log.warn("재활성 가드 발동: {}", check.message());
        }
        return check;
    }

    /**
     * 기록 중 회차와 재적재가 판정에 쓸 직전 I — 최신 스냅샷의 I 다(설계 2026-11-24 R8, §3.3). 스냅샷이 없으면 빈 집합(판정 없음), 깨졌으면
     * ({@link SnapshotIntegrityException}) WARN 을 남기고 빈 집합이다. 그 밖의 오류는 그대로 올려 회차가 실패한다(설계 2026-11-25 E1) — 깨진
     * 스냅샷은 이 두 길(재적재·장부 맞추기)이 고칠 일이고, 일시 장애는 다음 정각이 다시 본다. 재적재도 쓴다.
     */
    static Mono<Set<RelationTuple>> 직전_I_를_읽어_본다(TupleSnapshotRepository snapshots) {
        return snapshots.findLatest()
                .map(TupleSnapshot::inactive)
                .defaultIfEmpty(Set.of())
                .onErrorResume(SnapshotIntegrityException.class, error -> {
                    log.warn("직전 스냅샷이 온전하지 않아 재활성 가드를 보지 않는다: {}",
                            error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
                    return Mono.just(Set.of());
                });
    }

    /**
     * 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. 두 가드는 이번 회차의 가드다 — 늘 본다.
     *
     * <p>흐름은 설계 2026-11-24 §3.3 이다. LDAP 을 다 읽고 포인터 상태를 본 뒤, 상태마다 직전 I 를 정해 재활성을 판정하고, 판정이 정한 그림(보류면
     * 그림′)으로 매핑해 경로를 탄다. 경로의 결론에 보류를 적는다({@link #재활성_보류를_적는다}).
     */
    private Mono<Mono<SyncOutcome>> synchronize(DeletionGuard 가드, ReactivationGuard 재활성_가드, RunAudit 기록) {
        return source.fetchAll().flatMap(directory -> snapshots.baselineState().flatMap(상태 -> switch (상태) {
            // 포인터가 없다 — 첫 회차이거나 테이블을 새로 만들었다. 빈 기준선과 비교하면 장부의 찌꺼기를 영원히 못 지운다(설계 2026-10-20 §4).
            // 직전 I 도 없으니 재활성을 판정하지 않는다(설계 2026-11-24 R8)
            case NONE -> 기준선_없이_맞춘다(directory, 매핑한다(directory), 가드, 기록);
            // 기준선을 믿지 않는 회차 — 최신 스냅샷을 읽어 보고 그 I 로 판정한다. 없거나 깨졌으면 판정 없이 간다. 그 밖의 읽기 오류는 회차를 실패시킨다
            case WRITING -> 직전_I_를_읽어_본다(snapshots).flatMap(직전_I -> {
                ReactivationCheck check = 재활성을_판정한다(재활성_가드, 직전_I, directory);
                return 재활성_보류를_적는다(훑어_맞춘다(check.picture(), 매핑한다(check.picture()), 가드, 기록), check);
            });
            // 최신 스냅샷을 한 번 읽어 I 로 판정하고, 같은 스냅샷의 B·U 를 비교의 기준선으로 쓴다. 못 읽으면 지금처럼 실패한다
            case PRESENT -> 기준_스냅샷().flatMap(직전 -> {
                ReactivationCheck check = 재활성을_판정한다(재활성_가드, 직전.snapshot().inactive(), directory);
                return 재활성_보류를_적는다(비교해_맞춘다(check.picture(), 매핑한다(check.picture()), 기준선.of(직전), 가드,
                        기록.changes()), check);
            });
        }));
    }

    /** 판정이 정한 그림을 튜플로 바꾼다 — 보류면 그림′ 이라 보류한 직원의 줄은 D 에 없다(설계 2026-11-24 R4). */
    private static Set<RelationTuple> 매핑한다(DirectorySnapshot directory) {
        TupleMappingResult mapping = TupleMapper.toTuples(directory);
        mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
        return mapping.tuples();
    }

    /**
     * 기준선을 믿을 수 없는 회차 — 재적재와 같은 청소로 한 번 맞춘다. 지우기 전에 이번 회차의 가드를 본다 — 강제 회차면 허용 수 N 을 상한으로 한 같은
     * 가드다(설계 2026-11-20 F1·F2). 지난 회차가 쓴 뒤·기록 전에 멈춰 "기록 중" 표시가 남았다. 이력은 장부 맞추기로 바꾸고 지운 줄만 남긴다.
     */
    private Mono<Mono<SyncOutcome>> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               DeletionGuard 가드, RunAudit 기록) {
        log.warn(기준선_의심);
        기록.kind(AuditKind.LEDGER_ALIGNMENT);
        return 장부를_맞춘다(directory, desired, 가드, 기록, 기준선_의심, false);
    }

    /**
     * 기준선이 없는 회차 — 스냅샷 포인터가 아예 없다. 빈 기준선과 비교하는 대신 "기록 중"과 같은 청소로 맞춘다(설계 2026-10-20 §4).
     * 빈 장부의 첫 설치는 D 를 다 쓰고 장부를 한 번 훑을 뿐이라 결과가 같다.
     *
     * <p>이력은 동기화 그대로 두고 쓴 줄도 부여로 남긴다 — 첫 적재의 부여 이력(설계 2026-10-10 §4.2)을 잃지 않는다. 가드가 통째로 막으면 스냅샷이 없고
     * 표시가 남아, 다음 회차는 "기록 중" 길로 다시 장부를 훑는다.
     */
    private Mono<Mono<SyncOutcome>> 기준선_없이_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                                    DeletionGuard 가드, RunAudit 기록) {
        log.warn(기준선_없음);
        return 장부를_맞춘다(directory, desired, 가드, 기록, 기준선_없음, true);
    }

    /**
     * 장부 맞추기로 한 번 맞추고 결론 앞에 사정을 붙인다.
     *
     * <p>청소 자체가 오류로 끝나도(쓰기 단계 차단기, 스냅샷 저장 실패, "기록 중" 표시를 남기지 못함) 기록 메시지에 사정을 남긴다 — 그러지 않으면 FAILED
     * 사유만 보고는 기준선을 믿지 못해 훑었다는 맥락이 사라진다. 취소는 {@code onErrorMap} 이 보지 못하므로 여기서 감싸지 않는다.
     */
    private Mono<Mono<SyncOutcome>> 장부를_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               DeletionGuard 가드, RunAudit 기록, String 사정, boolean 쓴_줄도_남긴다) {
        return alignment.align(directory, desired, 가드, 기록.changes(), 쓴_줄도_남긴다)
                .map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withNote(사정)))
                .onErrorMap(error -> new IllegalStateException(
                        사정 + " / " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                        error));
    }

    /**
     * 기준선과 비교해 맞춘다. {@code directory} 는 재활성 판정이 정한 그림(보류면 그림′)이다 — 삭제 가드의 비활성 회수·멈춤 내역·현재상태 교체·새 스냅샷의
     * I 가 모두 이것을 본다(설계 2026-11-24 R4·R5). {@code baseline} 은 판정에 쓴 바로 그 스냅샷의 B·U 다(§3.3 — 스냅샷을 한 번만 읽는다).
     */
    private Mono<Mono<SyncOutcome>> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired, 기준선 baseline,
                                                DeletionGuard 가드, AuditChanges 기록) {
        TupleDelta delta = 델타를_정한다(baseline, desired);
        if (delta.isEmpty()) {
            // 델타가 비면 U′ 도 비어 있다 — U′ 의 줄은 D 에 있으면 쓸 줄에, 없으면 지울 줄에 반드시 든다
            log.info("변경 없음. OpenFGA 를 호출하지 않는다");
            if (baseline.표시_있음()) {
                // 표시를 반영한 기준선을 새 스냅샷으로 남겨 표시를 지운다 — 안 남기면 다음 회차마다 같은 계산을 되풀이한다(설계 2026-11-28 V10)
                return 스냅샷을_저장한다(directory, baseline, TupleWriteResult.empty())
                        .flatMap(snapshotId -> state.replaceWith(directory, 기록.directoryListener())
                                .thenReturn(Mono.just(SyncOutcome.noChangeWithSnapshot(snapshotId))));
            }
            return state.replaceWith(directory, 기록.directoryListener())
                    .thenReturn(Mono.just(SyncOutcome.noChange()));
        }
        // 가드는 늘 본다 — 강제 회차는 허용 수 N 을 상한으로 한 같은 가드다(설계 2026-11-20 F2)
        GuardDecision decision = 가드.evaluate(delta.toDelete(), TupleMapper.inactiveMemberTuples(directory),
                baseline.크기(), "기준 스냅샷");
        if (decision.aborted()) {
            // 가드가 걸린 회차는 지우려던 전체의 내역을 남긴다 — 일부 반영·차단기 중단 결론에도 붙는다(설계 2026-11-20 §3.1)
            GuardHold hold = GuardHold.of(delta.toDelete(), directory);
            log.warn("삭제 가드 내역: {}", hold.summary());
            if (decision.partial()) {
                return 비활성_회수만_반영한다(directory, baseline, delta, decision, hold, 기록);
            }
            log.warn("삭제 가드 발동: {}", decision.message());
            return Mono.just(Mono.just(SyncOutcome.aborted(decision.message()).withGuardHold(hold)));
        }
        // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11). 쓴·지운 줄은 묶음마다 기록에 더한다
        return snapshots.markWriting()
                .then(Mono.defer(() -> writer.apply(delta, 기록::tuples)))
                .flatMap(result -> commit(directory, baseline, result, 기록))
                .onErrorResume(TupleWriteAbortedException.class, stopped ->
                        saveSnapshotAndState(directory, baseline, stopped.partial(), 기록)
                                .map(snapshotId -> Mono.just(SyncOutcome.stopped(stopped.partial(), snapshotId,
                                        stopped.getMessage() + 현재상태_교체))));
    }

    /**
     * 가드가 걸렸지만 디렉터리가 막았다고 명시한 회수만으로는 가드 안이다(설계 2026-10-20 §5.3) — <b>그 지우기만</b> 반영한다.
     * <ul>
     *   <li>"기록 중" 표시를 남긴 뒤 지우고, §3 의 식으로 스냅샷을 저장해 표시를 지운다. 지운 줄은 묶음마다 이력에 더한다.</li>
     *   <li><b>현재상태는 바꾸지 않는다.</b> 가드가 걸린 회차는 디렉터리 그림을 받아들이지 않은 회차다.</li>
     *   <li><b>부여와 나머지 지우기는 보류한다.</b> 부여만 반영하면 이동자가 옛 소속(지우기 보류)과 새 소속(쓰기 반영)을 함께 갖는다.</li>
     * </ul>
     * 실행 기록은 ABORTED 다. 지우다 차단기로 멈추면 나간 지우기로 스냅샷을 남기고 FAILED 다 — 이때도 현재상태는 바꾸지 않는다. 두 스냅샷의 I 는
     * {@code directory}(보류면 그림′)의 것이다(설계 2026-11-24 R1).
     */
    private Mono<Mono<SyncOutcome>> 비활성_회수만_반영한다(DirectorySnapshot directory, 기준선 baseline, TupleDelta delta,
                                                     GuardDecision decision, GuardHold hold, AuditChanges 기록) {
        Set<RelationTuple> 회수 = decision.revocationsOnly();
        String 결론 = LedgerAlignment.비활성_회수만_반영했다(회수.size(), delta.toDelete().size() - 회수.size(),
                delta.toWrite().size(), decision.message());
        log.warn("{}", 결론);
        return snapshots.markWriting()
                .then(Mono.defer(() -> writer.apply(TupleDelta.deleteOnly(회수), 기록::tuples)))
                .flatMap(result -> 스냅샷을_저장한다(directory, baseline, result)
                        .map(snapshotId -> Mono.just(SyncOutcome.revokedOnly(result, snapshotId, 결론).withGuardHold(hold))))
                .onErrorResume(TupleWriteAbortedException.class, stopped ->
                        스냅샷을_저장한다(directory, baseline, stopped.partial())
                                .map(snapshotId -> Mono.just(SyncOutcome.stopped(stopped.partial(), snapshotId,
                                        결론 + " / " + stopped.getMessage()).withGuardHold(hold))));
    }

    /**
     * 기준선 — 직전 스냅샷의 B(장부에 있다고 확실히 아는 줄)와 U(결과를 몰라 있는지 모르는 줄)에 즉시 회수 표시를 반영한 B′·U′ 다
     * (설계 2026-11-28 V10). B′ = B − gone − unknown, U′ = (U − gone) ∪ unknown. {@code 표시_있음} 은 표시가 비지 않았는지다.
     */
    private record 기준선(Set<RelationTuple> tuples, Set<RelationTuple> uncertain, boolean 표시_있음) {

        static 기준선 of(TupleSnapshotRepository.MarkedSnapshot marked) {
            TupleSnapshot snapshot = marked.snapshot();
            RevocationMarks marks = marked.marks();
            if (marks.isEmpty()) {
                return new 기준선(snapshot.tuples(), snapshot.uncertain(), false);
            }
            Set<RelationTuple> b = new HashSet<>(snapshot.tuples());
            b.removeAll(marks.gone());
            b.removeAll(marks.unknown());
            Set<RelationTuple> u = new HashSet<>(snapshot.uncertain());
            u.removeAll(marks.gone());
            u.addAll(marks.unknown());
            return new 기준선(b, u, true);
        }

        /** 삭제 가드의 기준 수 — |B′| + |U′|. 불확실 줄도 장부에 있을 수 있는 줄이다 */
        int 크기() {
            return tuples.size() + uncertain.size();
        }
    }

    /**
     * 포인터가 "기준선 있음"인 회차의 기준 스냅샷 — 비교의 기준선(B′·U′)과 재활성 판정의 직전 I 를 이 한 번의 읽기에서 함께 쓴다(설계 2026-11-24 §3.3).
     * 즉시 회수 표시도 같은 읽기에서 온다(설계 2026-11-28 V14). 여기서 스냅샷이 비어 돌아오면 빈 기준선과 비교하지 않고 {@link SnapshotIntegrityException} 으로
     * 멈춘다 — 빈 기준선은 지울 줄이 0 이라 장부의 줄을 영원히 못 지운다(설계 2026-10-20 §1.4 의 뒷문). 포인터가 없는 회차는 여기 오지 않고 장부를 훑는다.
     */
    private Mono<TupleSnapshotRepository.MarkedSnapshot> 기준_스냅샷() {
        return snapshots.findLatestMarked()
                .switchIfEmpty(Mono.error(() -> new SnapshotIntegrityException(
                        "스냅샷 포인터는 기준선이 있다고 하는데 최신 스냅샷을 읽지 못했습니다 — POST /admin/sync/rebuild 로 복구하세요")));
    }

    /**
     * 쓸 줄 = D − B, 지울 줄 = (B − D) ∪ (U − D)(설계 2026-10-20 §3.3). U 는 B 에 없으므로 U ∩ D 는 쓸 줄에 저절로 든다. 멱등 옵션(없는 줄 지우기 무시,
     * 있는 줄 쓰기 무시) 덕에 U 의 줄을 다시 보내도 안전하다. 쓸 줄은 D 안, 지울 줄은 D 밖이라 겹치지 않는다.
     */
    private static TupleDelta 델타를_정한다(기준선 baseline, Set<RelationTuple> desired) {
        TupleDelta diff = TupleDiff.between(baseline.tuples(), desired);
        if (baseline.uncertain().isEmpty()) {
            return diff;
        }
        Set<RelationTuple> toDelete = new HashSet<>(diff.toDelete());
        for (RelationTuple tuple : baseline.uncertain()) {
            if (!desired.contains(tuple)) {
                toDelete.add(tuple);
            }
        }
        return new TupleDelta(diff.toWrite(), toDelete);
    }

    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, 기준선 baseline,
                                           TupleWriteResult result, AuditChanges 기록) {
        return saveSnapshotAndState(directory, baseline, result, 기록)
                .map(snapshotId -> Mono.just(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshotId)
                        : SyncOutcome.succeeded(result, snapshotId)));
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 반영됐다고 확실한 줄(B)과 결과를 모르는 줄(U), 현재상태는 LDAP 에서 읽은 사실 그대로다.
     *
     * <p>쓰기가 차단기로 멈췄을 때도 여기를 탄다(설계 2026-09-29 §5) — 이미 나간 쓰기를 스냅샷에 담지 않으면 기준선이 장부와 어긋난다. 나간 쓰기 가운데
     * 결과를 모르는 줄은 확실하다고 할 수 없다(설계 2026-09-30 §4.1 의 "기준선이 정확하다"는 이 줄을 몰랐다) — <b>결과를 모르는 줄은 U 로 남는다</b>.
     * R 을 이번 회차의 결과라 하면(설계 2026-10-20 §3.2):
     * <ul>
     *   <li>U_new = (U − (R.쓴 줄 ∪ R.지운 줄)) ∪ R.불확실 줄 — 이번에 결과가 확정된 줄만 U 에서 나간다. 다시 보냈는데 거절되거나 보내지 않은 줄은 여전히
     *       모르므로 U 에 남는다.</li>
     *   <li>B_new = ((B − R.지운 줄) ∪ R.쓴 줄) − U_new — B 와 U 는 겹치지 않는다.</li>
     * </ul>
     * 스냅샷 저장이 "기록 중" 표시를 지운다. 현재상태가 바뀐 것은 직원·조직 하나마다 {@code 기록} 에 더한다. 저장한
     * 스냅샷 아이디를 준다. {@code directory} 는 재활성 판정이 정한 그림(보류면 그림′)이라 보류한 직원은 현재상태에 비활성 그대로다(설계 2026-11-24 R4).
     */
    private Mono<String> saveSnapshotAndState(DirectorySnapshot directory, 기준선 baseline,
                                              TupleWriteResult result, AuditChanges 기록) {
        return 스냅샷을_저장한다(directory, baseline, result)
                .flatMap(snapshotId -> state.replaceWith(directory, 기록.directoryListener()).thenReturn(snapshotId));
    }

    /**
     * §3 의 식으로 새 스냅샷을 만들어 저장한다 — 저장이 "기록 중" 표시를 지운다. 현재상태는 건드리지 않는다. 저장한 스냅샷 아이디를 준다. 비활성 소속
     * 줄(I)은 {@code directory}(보류면 그림′)에서 만든다 — 보류한 직원이 다음 회차의 판정에 남는다(설계 2026-11-24 R1·R4).
     */
    private Mono<String> 스냅샷을_저장한다(DirectorySnapshot directory, 기준선 baseline, TupleWriteResult result) {
        Set<RelationTuple> uncertain = new HashSet<>(baseline.uncertain());
        uncertain.removeAll(result.written());
        uncertain.removeAll(result.deleted());
        uncertain.addAll(result.uncertain());

        Set<RelationTuple> committed = new HashSet<>(baseline.tuples());
        committed.removeAll(result.deleted());
        committed.addAll(result.written());
        committed.removeAll(uncertain);

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed,
                uncertain,
                TupleMapper.inactiveMemberTuples(directory));

        return snapshots.save(snapshot).thenReturn(snapshot.id());
    }
}
