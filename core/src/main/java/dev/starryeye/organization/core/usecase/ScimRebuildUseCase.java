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
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * SCIM 인스턴스의 수동 재적재. 관리자가 어긋났다고 판단했을 때 실행한다.
 *
 * <p><b>LDAP 판과 무엇이 다른가.</b> {@link RebuildUseCase} 는 {@code source.fetchAll()} 로
 * LDAP 을 다시 읽어와 DynamoDB 까지 덮어쓴다. SCIM 은 push 모델이라 "전체를 다시 달라"고
 * 말할 상대가 없다 — 그래서 <b>현재상태 자체가 진실</b>이고, {@link ScimRebuildMode#TUPLES}
 * 는 그 상태에서 튜플만 다시 만든다.
 *
 * <p><b>순서가 중요하다: OpenFGA 를 먼저 지운다.</b> 중간에 실패했을 때 남는 상태가 갈린다 —
 * 이 순서면 조직도는 온전하고 권한만 없어서 {@code TUPLES} 한 번으로 복구된다. 뒤집으면
 * 조직도가 사라진 채 <b>낡은 권한만 살아남는다</b>. 지워진 사람들의 권한만 남는 셈이라 최악이다.
 *
 * <p><b>감사 이력은 지우지 않는다.</b> {@code WIPE} 도 스냅샷과 실행 이력은 남긴다. 사고 뒤에
 * "무슨 일이 있었나"를 볼 유일한 기록인데 그것까지 지우면 조사할 수단이 사라진다.
 */
@Slf4j
@RequiredArgsConstructor
public class ScimRebuildUseCase {

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final TupleSnapshotRepository snapshots;
    private final SyncRunRepository runs;
    private final MutationLock lock;
    private final Duration renewInterval;
    private final Clock clock;

    /**
     * {@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미
     * 확인됐다는 뜻이므로 값 자체는 받지 않는다 — 안 쓸 값을 받으면 다음 사람이 이 유스케이스가
     * 검증도 한다고 믿는다.
     */
    public Mono<SyncRun> execute(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);

        return lock.acquire(MutationLock.LockPurpose.REBUILD)
                .flatMap(lease -> {
                    Disposable heartbeat = 리스를_갱신한다(lease);
                    return runs.start(SyncSource.SCIM, triggerFor(mode))
                            .flatMap(run -> rebuild(mode)
                                    .onErrorResume(error -> {
                                        log.error("SCIM 재적재 실패: mode={}", mode, error);
                                        return Mono.just(SyncOutcome.failed(error.getMessage()));
                                    })
                                    .flatMap(outcome -> runs.finish(run, outcome)))
                            .doFinally(signal -> {
                                heartbeat.dispose();
                                lock.release(lease).subscribe();
                            });
                });
    }

    private static SyncTrigger triggerFor(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? SyncTrigger.RESET : SyncTrigger.REBUILD;
    }

    /**
     * 재적재가 도는 동안 리스를 계속 미룬다 (설계 §4.4).
     *
     * <p>TTL 은 30초인데 재적재는 몇 분 걸린다. 갱신하지 않으면 도중에 리스를 잃고, 그 순간
     * 다른 인스턴스의 쓰기가 <b>반쯤 재적재된 OpenFGA</b> 위로 들어온다.
     *
     * <p>갱신이 실패하면 이미 리스를 잃은 것이다. 재적재를 되돌릴 방법이 없으므로 경고만
     * 남기고 하던 일을 마친다 — 그 상태는 재적재가 실패했을 때와 같아
     * {@code mode=tuples} 를 한 번 더 실행하면 복구된다.
     */
    private Disposable 리스를_갱신한다(LockLease lease) {
        return Flux.interval(renewInterval, renewInterval)
                .concatMap(tick -> lock.renew(lease)
                        .doOnError(error -> log.error(
                                "재적재 도중 변경 락 리스를 잃었다. 다른 인스턴스의 쓰기가 들어올 수 있다", error))
                        .onErrorResume(error -> Mono.empty()))
                .subscribe();
    }

    /**
     * 초기화가 성공한 뒤에만 다음 단계로 간다. {@link Mono#defer} 로 감싸는 것이 핵심이다 —
     * 인자로 바로 넘기면 초기화가 실패해도 다음 단계가 이미 조립되면서 부수효과를 낸다.
     */
    private Mono<SyncOutcome> rebuild(ScimRebuildMode mode) {
        return writer.resetStore()
                .then(Mono.defer(() -> mode == ScimRebuildMode.WIPE ? wipeState() : reloadTuples()));
    }

    // ---------- TUPLES ----------

    private Mono<SyncOutcome> reloadTuples() {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return writer.apply(TupleDelta.writeOnly(mapping.tuples()))
                    .flatMap(this::commitTuples);
        });
    }

    /**
     * 스냅샷에는 <b>실제로 쓴 것</b>만 담는다. 의도한 것을 담으면 부분 실패 뒤 스냅샷이
     * OpenFGA 보다 앞서게 되고, 그 기록을 믿는 다음 판단이 전부 어긋난다.
     */
    private Mono<SyncOutcome> commitTuples(TupleWriteResult result) {
        Set<RelationTuple> committed = result.written();

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, committed);

        return snapshots.save(snapshot)
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }

    // ---------- WIPE ----------

    /**
     * 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다 — 남길 것이 없으므로
     * {@code replaceWith} 가 기존 항목을 모두 삭제하고 아무것도 채우지 않는다.
     */
    private Mono<SyncOutcome> wipeState() {
        return state.replaceWith(DirectorySnapshot.empty())
                .thenReturn(SyncOutcome.succeeded(TupleWriteResult.empty(), null))
                .doOnSuccess(outcome -> log.warn(
                        "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"));
    }
}
