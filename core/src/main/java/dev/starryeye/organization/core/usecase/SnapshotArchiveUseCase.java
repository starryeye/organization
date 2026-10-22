package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 *
 * <p><b>어긋남을 수로 내고, 남은 회수는 지운다(설계 2026-10-20 §6.4).</b> 어긋남의 수는 {@link ArchiveDriftObserver} 로 알린다. 장부에 상태가 요구하지 않는
 * 직원 줄({@code direct_member})이 남았으면 그 직원마다 {@code 남은_회수를_지운다}(운영 결선은 {@link IncrementalSyncUseCase#revokeLeftovers})로
 * 락 안에서 차례로 지운다. <b>지우기만 한다</b> — 비교는 락 밖에서 낡을 수 있는 상태로 했으므로, 낡은 상태로 쓰면 지운 직원의 권한이 되살아난다. 상태가 요구하는데
 * 빠진 줄은 수로만 내고 IdP 재시도·다음 쓰기·재적재가 채운다. 조직 연결 줄({@code child})의 어긋남도 수로만 낸다. 설계 2026-09-01 §1.4 는 5분마다 전체를
 * 다시 계산하는 "주기 리컨실러"를 비용 때문에 버렸다 — 이것은 하루 한 번 이미 하는 비교의 결과에서 어긋난 직원만 맞추고, OpenFGA Read 를 쓰지 않는다. 그 문서의
 * 전제("수렴 기제는 IdP 재시도")가 IdP 의 동작에 따라 성립하지 않을 수 있어, 비교를 복구로 잇는다.
 */
@Slf4j
@RequiredArgsConstructor
public class SnapshotArchiveUseCase {

    private final DirectoryStateRepository state;
    private final RelationTupleChecker checker;
    private final TupleSnapshotRepository snapshots;
    private final SyncRunRepository runs;
    private final MutationLock lock;
    private final Clock clock;
    private final ArchiveDriftObserver driftObserver;
    /** 직원 한 명의 남은 회수를 락 안에서 지운다. 빈 결과는 그 직원이 그새 지워졌다는 뜻이다. */
    private final Function<String, Mono<IncrementalSyncResult>> 남은_회수를_지운다;

    static final String 재적재_중 = "재적재 중이라 건너뜀";

    /**
     * 재적재가 작업 락을 쥐고 있으면 오늘은 건너뛴다(설계 2026-09-30 §5.2) — 재적재의 쓰기와 지우기 사이에 잠깐 어긋난 장부를 "실제"로 찍으면
     * 거짓 어긋남 경고가 감사 기록에 남는다. 락은 들여다보기만 한다 — 잡으면 몇 분 동안 SCIM 쓰기가 전부 503 이다.
     */
    public Mono<SyncRun> execute() {
        return 재적재_중인가().flatMap(rebuilding -> rebuilding ? 건너뛴다() : 아카이빙한다());
    }

    /** 들여다보지 못하면 재적재가 아니라고 보고 진행한다 — 아카이빙을 통째로 잃는 것보다 낫다. */
    private Mono<Boolean> 재적재_중인가() {
        return lock.peek()
                .map(purpose -> purpose == MutationLock.LockPurpose.REBUILD)
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    log.warn("변경 락을 들여다보지 못했다 — 재적재가 아니라고 보고 아카이빙한다", error);
                    return Mono.just(false);
                });
    }

    private Mono<SyncRun> 건너뛴다() {
        log.warn("재적재 중이라 오늘 아카이빙을 건너뛴다");
        return runs.start(SyncSource.SCIM, SyncTrigger.ARCHIVE)
                .flatMap(run -> runs.finish(run, SyncOutcome.aborted(재적재_중)));
    }

    private Mono<SyncRun> 아카이빙한다() {
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
     *
     * <p><b>있어야 할 튜플은 저장된 보류 목록을 따라 정한다(설계 2026-10-03 §4.6).</b> 목록 없이 계산하면 순증이 순환을 닫는 쪽을 보류하고 다른 쪽을 썼을 때
     * 정렬순 DFS 가 반대쪽을 골라 둘 다 어긋남으로 센다. 다만 목록의 줄 중 튜플이 OpenFGA 에 있는 것은 튜플 그래프에 있는 연결이다(§4.1) — 쓴 뒤 목록에서
     * 빼기 전에 멈춘 낡은 줄이라, 그대로 빼면 있는 튜플이 있어선 안 될 것으로 세어진다. 어차피 하는 Check 의 결과로 그런 줄을 걷고 계산한다(추가 Check 없음).
     */
    private Mono<SyncOutcome> archive() {
        return Mono.zip(state.loadAll(), state.findCutEdges().collect(Collectors.toSet())).flatMap(both -> {
            DirectorySnapshot directory = both.getT1();
            Set<GroupEdge> 보류 = both.getT2();

            Set<RelationTuple> candidates = TupleMapper.candidateTuples(directory);
            return checker.existing(candidates).flatMap(actual -> {
                Set<GroupEdge> 아직_보류 = 보류.stream()
                        .filter(edge -> !actual.contains(edge.tuple()))
                        .collect(Collectors.toSet());
                var mapping = TupleMapper.toTuples(directory, 아직_보류);
                mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
                Set<RelationTuple> 있어야할것 = mapping.tuples();
                Set<RelationTuple> 여분 = actual.stream()
                        .filter(tuple -> !있어야할것.contains(tuple))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                long 빠짐 = 있어야할것.stream().filter(tuple -> !actual.contains(tuple)).count();
                어긋남을_남긴다(여분.size(), 빠짐);

                Instant now = clock.instant();
                TupleSnapshot snapshot = new TupleSnapshot(
                        SnapshotIds.generate(now, SyncSource.SCIM),
                        now,
                        SyncSource.SCIM,
                        actual);

                // 스냅샷은 복구 전에 본 실제다 — 지우기 전의 모습이 기록에 남는다
                return snapshots.save(snapshot)
                        .then(Mono.fromRunnable(() -> 수를_알린다(여분.size(), 빠짐)))
                        .then(Mono.defer(() -> 차례로_지운다(남은_회수의_직원들(여분))))
                        .map(복구 -> new SyncOutcome(SyncStatus.SUCCEEDED,
                                actual.size(), 0, 0, snapshot.id(), 복구.메시지()));
            });
        });
    }

    /**
     * 있어야 할 것과 실제가 다르면 남긴다. 쓰기 경로의 {@code scim.drift.detected}(설계 §7)와
     * 같은 성질을 <b>아무도 만지지 않은 튜플까지 포함해</b> 하루 한 번 본다 — 쓰기 경로는
     * 그 요청이 건드리는 범위만 보므로 방치된 어긋남은 이 자리에서만 드러난다.
     */
    private void 어긋남을_남긴다(long 여분, long 빠짐) {
        if (여분 > 0 || 빠짐 > 0) {
            log.warn("아카이빙 중 OpenFGA 어긋남 발견: 있어선 안 될 튜플 {}건, 빠진 튜플 {}건",
                    여분, 빠짐);
        }
    }

    /** 어긋남이 없어도 알린다(0) — 받는 쪽이 마지막 값을 쥐므로 고쳐진 뒤 내려와야 한다. 받는 쪽의 오류로 아카이빙을 잃지 않는다. */
    private void 수를_알린다(long 여분, long 빠짐) {
        try {
            driftObserver.observed(여분, 빠짐);
        } catch (RuntimeException e) {
            log.warn("아카이빙 어긋남 수를 알리지 못했다 — 아카이빙은 계속한다", e);
        }
    }

    /** 남은 줄({@code direct_member})의 직원 — 조직 연결 줄은 직원 회수가 아니라 여기 들지 않는다. 순서가 일정하도록 아이디순이다. */
    private static List<String> 남은_회수의_직원들(Set<RelationTuple> 여분) {
        return 여분.stream()
                .flatMap(tuple -> tuple.directMemberUserId().stream())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 직원마다 차례로 지운다(동시에 락을 다투지 않는다). 한 명이 실패해도 나머지는 이어서 한다 — 실패한 직원은 줄이 남아 다음 아카이빙에 다시 잡힌다.
     * 결과의 분류:
     * <ul>
     *   <li>빈 결과 — 비교 뒤 그 직원이 지워졌다. 건너뛰고 세지 않는다.</li>
     *   <li>일부만 반영, 또는 오류 — 실패로 센다.</li>
     * </ul>
     */
    private Mono<복구결과> 차례로_지운다(List<String> 직원들) {
        if (직원들.isEmpty()) {
            return Mono.just(복구결과.없음);
        }
        return Flux.fromIterable(직원들)
                .concatMap(직원 -> Mono.defer(() -> 남은_회수를_지운다.apply(직원))
                        .map(결과 -> 결과.fullyApplied() ? 한명.지움 : 한명.실패)
                        .onErrorResume(error -> {
                            log.warn("직원 '{}' 의 남은 회수를 지우지 못했다 — 다음 아카이빙이 다시 잡는다", 직원, error);
                            return Mono.just(한명.실패);
                        })
                        .defaultIfEmpty(한명.건너뜀))
                .collectList()
                .map(결과들 -> {
                    long 건너뜀 = 결과들.stream().filter(결과 -> 결과 == 한명.건너뜀).count();
                    long 실패 = 결과들.stream().filter(결과 -> 결과 == 한명.실패).count();
                    return new 복구결과(결과들.size(), 결과들.size() - 건너뜀, 실패);
                });
    }

    private enum 한명 {
        지움, 실패, 건너뜀
    }

    /**
     * @param 대상 비교에서 남은 줄이 잡힌 직원 수
     * @param 맞춘직원 그 가운데 다시 맞추려 한 직원 수 — 그새 지워져 건너뛴 직원은 세지 않는다
     * @param 실패 맞추려 한 직원 가운데 다 지우지 못한 직원 수
     */
    private record 복구결과(long 대상, long 맞춘직원, long 실패) {

        static final 복구결과 없음 = new 복구결과(0, 0, 0);

        /** 남은 줄이 잡힌 직원이 없었으면 메시지도 없다 — 평소의 아카이빙 기록은 지금처럼 조용하다. */
        String 메시지() {
            return 대상 == 0 ? null : String.format("어긋난 직원 %d명 다시 맞춤(실패 %d명)", 맞춘직원, 실패);
        }
    }
}
