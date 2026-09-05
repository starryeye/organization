package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * SCIM 인스턴스의 하루 1회 스냅샷 아카이빙.
 *
 * <p>SCIM 은 push 라 diff 용 스냅샷이 필요 없다. 이 스냅샷은 감사와 수동 복구용이며,
 * LDAP 인스턴스와 같은 저장 구조를 쓰므로 사고 조사 때 두 인스턴스의 기록을 같은 방식으로 읽는다.
 *
 * <p>SCIM push 요청과 달리 이것은 배치이므로 {@code SyncRun} 을 기록한다 —
 * 새벽에 아카이빙이 실패했다면 그 사실이 남아야 한다.
 *
 * <p><b>기록하는 것은 "실제로 OpenFGA 에 있는 튜플" 이다.</b> 전에는 현재상태로부터 유도한
 * "있어야 하는 튜플" 을 저장했는데, 그것은 상태만 있으면 언제든 다시 계산할 수 있어 기록으로서
 * 값이 없다. 게다가 같은 앱의 {@link ScimRebuildUseCase} 는 <b>실제로 쓴 것</b>을 저장하고
 * 있어서, 같은 테이블에 같은 타입으로 뜻이 다른 두 스냅샷이 쌓이고 있었다.
 *
 * <p>다만 완전한 관찰은 아니다 — 후보를 현재상태의 멤버십에서 뽑으므로 멤버십이 사라진
 * 튜플(설계 §5.4)은 여기에도 잡히지 않는다. "상태가 이름 붙일 수 있는 범위 안에서 관찰한
 * 실제" 다.
 */
@Slf4j
@RequiredArgsConstructor
public class SnapshotArchiveUseCase {

    private final DirectoryStateRepository state;
    private final RelationTupleChecker checker;
    private final TupleSnapshotRepository snapshots;
    private final SyncRunRepository runs;
    private final Clock clock;

    public Mono<SyncRun> execute() {
        return runs.start(SyncSource.SCIM, SyncTrigger.ARCHIVE)
                .doOnNext(run -> log.info("[{}] 스냅샷 아카이빙 시작", run.runId()))
                .flatMap(run -> Mono.defer(this::archive)
                        .onErrorResume(error -> {
                            log.error("[{}] 스냅샷 아카이빙 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    /**
     * {@code toTuples} 는 경고를 남기려고 여전히 부른다 — "직원이 스냅샷에 없어 건너뜁니다"
     * 같은 신호는 아카이빙이 매일 보는 유일한 자리다. 저장하는 것은 그 결과가 아니라
     * BatchCheck 로 관찰한 실제다.
     *
     * <p>BatchCheck 가 실패하면 아카이빙을 실패로 끝낸다. 의도한 튜플로 폴백하면 그 스냅샷이
     * 관찰인지 유도인지 구분되지 않아, 이 변경이 없애려던 혼동이 그대로 돌아온다(설계 §6).
     */
    private Mono<SyncOutcome> archive() {
        return state.loadAll().flatMap(directory -> {
            var mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            Set<RelationTuple> candidates = TupleMapper.candidateTuples(directory);
            return checker.existing(candidates).flatMap(actual -> {
                어긋남을_남긴다(mapping.tuples(), actual);

                Instant now = clock.instant();
                TupleSnapshot snapshot = new TupleSnapshot(
                        SnapshotIds.generate(now, SyncSource.SCIM),
                        now,
                        SyncSource.SCIM,
                        actual);

                return snapshots.save(snapshot)
                        .thenReturn(new SyncOutcome(SyncStatus.SUCCEEDED,
                                actual.size(), 0, 0, snapshot.id(), null));
            });
        });
    }

    /**
     * 있어야 할 것과 실제가 다르면 남긴다. 쓰기 경로의 {@code scim.drift.detected}(설계 §7)와
     * 같은 성질을 <b>아무도 만지지 않은 튜플까지 포함해</b> 하루 한 번 본다 — 쓰기 경로는
     * 그 요청이 건드리는 범위만 보므로 방치된 어긋남은 이 자리에서만 드러난다.
     */
    private void 어긋남을_남긴다(Set<RelationTuple> 있어야할것, Set<RelationTuple> 실제) {
        long 여분 = 실제.stream().filter(tuple -> !있어야할것.contains(tuple)).count();
        long 빠짐 = 있어야할것.stream().filter(tuple -> !실제.contains(tuple)).count();
        if (여분 > 0 || 빠짐 > 0) {
            log.warn("아카이빙 중 OpenFGA 어긋남 발견: 있어선 안 될 튜플 {}건, 빠진 튜플 {}건",
                    여분, 빠짐);
        }
    }
}
