package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.RunAudit;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryAccountSource;
import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.RevocationMarksFullException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository.BaselineState;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 한 사람 즉시 회수(설계 2026-11-28). 디렉터리에서 막혔거나 없는 직원의 장부 줄만 다음 정각 회차를 기다리지 않고 바로 지운다.
 *
 * <p><b>락 밖(V2).</b> 현재상태에 그 직원이 없으면 {@link UnknownEmployeeException}, 디렉터리를 읽어 활성이면
 * {@link StillActiveInDirectoryException}, 읽기가 실패하면 포트의 예외를 그대로 올린다. 이 길에서는 락도 실행 기록도 열지 않는다.
 *
 * <p><b>락 안(§5.3).</b> 막혔거나 없으면 {@code SyncJobs.startLockedThen} 으로 작업을 띄운다(V4) — ① 거둘 줄 E = 현재상태 소속의 후보 가운데 장부에
 * 실제로 있는 것(V5), 비면 쓰지도 표시하지도 않고 끝난다. ② 기준선이 PRESENT 면 E 를 포인터의 {@code unknown} 칸에 적는다(V6·V9) — 실패하면 지우지 않는다.
 * ③ E 만 든 삭제 델타를 쓴다(V7). ④ PRESENT 였으면 지운 줄은 {@code gone}, 확정 실패는 {@code unknown} 에서 뺀다(V8) — 결과를 모르는 줄은 {@code unknown}
 * 에 남는다. ⑤ 결론을 정한다(V11). 락 안이어야 정각 회차가 포인터를 다시 써 표시를 지우는 일이 없다(§5.4).
 *
 * <p>로그·실행 기록 메시지에 직원 id 를 싣지 않는다(V11).
 */
@Slf4j
public class ImmediateRevocationUseCase {

    private static final String 막힘 = "즉시 회수 — 디렉터리에서 막힘";
    private static final String 없음 = "즉시 회수 — 디렉터리에 없음";
    private static final String 거둘_줄_없음 = "즉시 회수 — 장부에 거둘 줄이 없습니다";
    private static final String 정리_실패_꼬리 = " — 즉시 회수 표시를 정리하지 못했습니다. 다음 동기화가 그 줄을 다시 지웁니다";
    private static final String 표시_실패 = "즉시 회수 표시를 남기지 못해 거두지 않았습니다 — ";

    private final DirectoryStateRepository state;
    private final DirectoryAccountSource accounts;
    private final RelationTupleChecker checker;
    private final RelationTupleWriter writer;
    private final TupleSnapshotRepository snapshots;
    private final SyncJobs jobs;

    public ImmediateRevocationUseCase(DirectoryStateRepository state, DirectoryAccountSource accounts,
                                      RelationTupleChecker checker, RelationTupleWriter writer,
                                      TupleSnapshotRepository snapshots, SyncJobs jobs) {
        this.state = Objects.requireNonNull(state, "state");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    /**
     * 락 밖에서 현재상태와 디렉터리를 본 뒤 작업을 띄우고 연 실행 기록(RUNNING)을 준다. 락 경합은 {@link LockUnavailableException} 이다.
     *
     * @param onFinished 끝난 기록으로 부른다(지표·로그)
     */
    public Mono<SyncRun> start(String employeeId, Consumer<SyncRun> onFinished) {
        return Mono.defer(() -> state.findUser(employeeId)
                .switchIfEmpty(Mono.error(UnknownEmployeeException::new))
                .flatMap(user -> accounts.accountState(employeeId, user.externalId()))
                .flatMap(account -> account == AccountState.ACTIVE
                        ? Mono.<SyncRun>error(new StillActiveInDirectoryException())
                        : 띄운다(employeeId, account == AccountState.BLOCKED ? 막힘 : 없음, onFinished)));
    }

    private Mono<SyncRun> 띄운다(String employeeId, String 앞머리, Consumer<SyncRun> onFinished) {
        return jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.REVOCATION, MutationLock.LockPurpose.REVOCATION,
                AuditKind.REVOCATION, (run, 기록) -> 거둔다(employeeId, 앞머리, 기록), onFinished);
    }

    /** 거둘 줄을 정하고, 있으면 표시 → 지우기 → 정리 순서로 거둔다. 반납 뒤 할 일은 없어 결론을 {@code Mono.just} 로 감싼다. */
    private Mono<Mono<SyncOutcome>> 거둔다(String employeeId, String 앞머리, RunAudit 기록) {
        return 거둘_줄을_정한다(employeeId)
                .flatMap(거둘_줄 -> 거둘_줄.isEmpty()
                        ? Mono.just(new SyncOutcome(SyncStatus.SUCCEEDED, 0, 0, 0, null, 거둘_줄_없음))
                        : 표시하고_지운다(거둘_줄, 앞머리, 기록))
                .map(Mono::just);
    }

    /** 후보 = 현재상태 소속마다 {@code (user:u, direct_member, group:g)}. 후보가 비면 Check 를 부르지 않는다. */
    private Mono<Set<RelationTuple>> 거둘_줄을_정한다(String employeeId) {
        return state.findGroupIdsContaining(MemberRef.user(employeeId))
                .map(groupId -> RelationTuple.directMember(employeeId, groupId))
                .collect(Collectors.toUnmodifiableSet())
                .flatMap(후보 -> 후보.isEmpty() ? Mono.just(Set.<RelationTuple>of()) : checker.existing(후보));
    }

    private Mono<SyncOutcome> 표시하고_지운다(Set<RelationTuple> 거둘_줄, String 앞머리, RunAudit 기록) {
        return snapshots.baselinePointer().flatMap(pointer -> {
            // 표시는 기준선이 있을 때만 적는다 — 없거나 기록 중이면 다음 회차가 장부를 훑어 맞춘다(V6)
            Optional<String> 표시한_번호 = pointer.state() == BaselineState.PRESENT
                    ? Optional.of(pointer.snapshotId()) : Optional.empty();
            Mono<Void> 표시 = 표시한_번호
                    .map(번호 -> snapshots.markRevoking(번호, 거둘_줄).onErrorMap(this::표시_오류))
                    .orElse(Mono.empty());
            return 표시.then(Mono.defer(() -> 지운다(거둘_줄, 앞머리, 표시한_번호, 기록)));
        });
    }

    private Mono<SyncOutcome> 지운다(Set<RelationTuple> 거둘_줄, String 앞머리, Optional<String> 표시한_번호, RunAudit 기록) {
        // 차단기가 아닌 오류는 정리하지 않는다 — 표시가 모름(unknown)으로 남아 다음 회차가 안전하게 다룬다
        return writer.apply(TupleDelta.deleteOnly(거둘_줄), 기록.changes()::tuples)
                .flatMap(result -> 정리한다(표시한_번호, result)
                        .map(꼬리 -> 결론(result, 앞머리).withSuffix(꼬리)))
                .onErrorResume(TupleWriteAbortedException.class, stopped -> 정리한다(표시한_번호, stopped.partial())
                        .map(꼬리 -> SyncOutcome.stopped(stopped.partial(), null, stopped.getMessage())
                                .withNote(앞머리).withSuffix(꼬리)));
    }

    private static SyncOutcome 결론(TupleWriteResult result, String 앞머리) {
        return result.hasFailure()
                ? SyncOutcome.partial(result, null).withNote(앞머리)
                : new SyncOutcome(SyncStatus.SUCCEEDED, 0, result.deleted().size(), 0, null, 앞머리);
    }

    /**
     * 지운 줄은 {@code gone}, 확정 실패는 {@code unknown} 에서 뺀다 — 결과를 모르는 줄은 남는다(V8). 표시하지 않았으면 할 일이 없다.
     * 정리가 실패해도 회차는 그대로이고 꼬리를 돌려준다 — 다음 회차가 그 줄을 다시 지운다. WARN 에는 줄 수만 싣는다.
     */
    private Mono<String> 정리한다(Optional<String> 표시한_번호, TupleWriteResult result) {
        if (표시한_번호.isEmpty()) {
            return Mono.just("");
        }
        Set<RelationTuple> 지운_줄 = result.deleted();
        Set<RelationTuple> 빠진_줄 = result.failures().stream()
                .filter(failure -> !failure.uncertain())
                .map(TupleFailure::tuple)
                .collect(Collectors.toUnmodifiableSet());
        return snapshots.settleRevocation(표시한_번호.get(), 지운_줄, 빠진_줄)
                .thenReturn("")
                .onErrorResume(error -> {
                    log.warn("즉시 회수 표시를 정리하지 못했습니다 — 지운 줄 {}건, 확정 실패 {}건", 지운_줄.size(), 빠진_줄.size());
                    return Mono.just(정리_실패_꼬리);
                });
    }

    /** 한도 예외는 그대로(문구가 실행 기록에 남는다), 그 밖은 "거두지 않았다" 로 감싼다(계획 P3). */
    private Throwable 표시_오류(Throwable error) {
        if (error instanceof RevocationMarksFullException) {
            return error;
        }
        String 원인 = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return new IllegalStateException(표시_실패 + 원인, error);
    }
}
