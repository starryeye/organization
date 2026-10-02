package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.WriteRequestDeletes;
import dev.openfga.sdk.api.model.WriteRequestWrites;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 델타를 OpenFGA 에 반영한다. 쓰기 전용 어댑터라 조회는 하지 않는다 — 인가 판정이
 * 필요하면 {@code RelationTupleChecker} 를 쓴다.
 *
 * <p>멱등 옵션(on_duplicate / on_missing = IGNORE)을 항상 켜므로 중복 write 나
 * 없는 튜플 delete 로 배치가 통째로 실패하지 않는다. 튜플 단위 보상 로직이 필요 없는 이유다.
 * 이 옵션은 OpenFGA 서버 v1.10.0 이상에서만 동작한다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaRelationTupleWriter implements RelationTupleWriter {

    private final StoreBootstrapper bootstrapper;
    private final OpenFgaProperties properties;

    /**
     * 이만큼 연달아 실패하면(배치마다 재시도한 뒤에도) 남은 배치를 보내지 않는다(점검 C7). 설정으로 두지 않는다 — 운영에서 바꿀
     * 이유가 보이면 그때 올린다.
     */
    static final int 연속_실패_한도 = 3;

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        if (delta.isEmpty()) {
            return Mono.just(TupleWriteResult.empty());
        }

        List<Batch> batches = batchesFor(delta);

        return bootstrapper.resolveStore()
                .then(보내되_연속_실패면_멈춘다(batches, this::applyBatch, properties.getRequestConcurrency()));
    }

    /**
     * 같은 종류(지우기·쓰기) 안에서 {@code 동시}개까지 동시에 보내고, 결과는 보낸 순서대로 센다(설계 2026-10-02 §4.3). 지우기 묶음을
     * 다 보낸 뒤 쓰기 묶음을 보낸다. {@value #연속_실패_한도}개가 연달아 실패하면 남은 배치를 보내지 않고 {@link TupleWriteAbortedException} 으로
     * 끝낸다(점검 C7, 설계 §5). 그 {@code partial} 은 멈추기 전까지의 결과(멈추게 한 배치까지)에 보내지 않은 배치를 실패로 더한 것이다 —
     * 호출자가 이미 나간 쓰기로 기록 규칙을 지킨다.
     *
     * <p>멈추면 이미 나간 묶음(최대 동시 수 − 1)은 결과를 기다리지 않고 "보내지 않음"으로 센다 — 실제로 반영됐더라도 쓰기·지우기가
     * 멱등이라 다음 회차(LDAP)·다음 같은 대상 쓰기(SCIM)가 같은 결과로 맞춘다.
     *
     * <p>OpenFGA 가 느리거나 죽으면 배치마다 재시도를 거친 뒤 실패로 넘어가는데, 10만 명 재적재는 배치가 약 1,100개라 전부 그렇게 돌면
     * 수십 분 동안 락을 쥔다. 연달아 실패하는 것은 대개 한 배치가 아니라 OpenFGA 의 문제다. 드문 실패 한두 건은 지금처럼 결과의
     * {@code failures} 로 넘겨 PARTIAL 이 되게 둔다. 배치가 셋보다 적은 쓰기는 해당이 없다 — SCIM 요청 한 건은 대개 여기에 든다.
     * 멤버가 200명 넘게 바뀌는 조직 PATCH·PUT 은 예외로, 멈추면 5xx 가 되고 IdP 재시도가 Check 기준선으로 수렴한다.
     *
     * <p>패키지 전용 — 멈추는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     *
     * <p><b>세는 것은 "한 줄도 못 살린 묶음"뿐이다(설계 2026-09-30 §6.3).</b> 거절된 묶음은 쪼개 보내므로 일부라도 반영됐으면 OpenFGA 가 죽은 것이
     * 아니다 — 셈을 처음부터 다시 한다. 인가 모델이 통째로 없어 모든 줄이 거절되면 세 묶음 뒤에 멈춘다.
     */
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send) {
        return 보내되_연속_실패면_멈춘다(batches, send, 1);
    }

    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send, int 동시) {
        int 동시_수 = Math.max(1, 동시);
        return Mono.defer(() -> {
            AtomicInteger 연속_실패 = new AtomicInteger();
            AtomicReference<TupleWriteResult> 지금까지 = new AtomicReference<>(TupleWriteResult.empty());
            return Flux.fromIterable(batches)
                    .index()
                    // 지우기 묶음을 다 보낸 뒤 쓰기 묶음을 보낸다(batchesFor 순서) — 같은 종류 안에서만 동시에 보낸다
                    .windowUntilChanged(indexed -> indexed.getT2().delete())
                    .concatMap(같은_종류 -> 같은_종류.flatMapSequential(
                            indexed -> send.apply(indexed.getT2()).map(result -> Tuples.of(indexed.getT1(), result)),
                            동시_수))
                    .concatMap(indexed -> {
                        TupleWriteResult result = indexed.getT2();
                        TupleWriteResult 누적 = 지금까지.accumulateAndGet(result, OpenFgaRelationTupleWriter::merge);
                        if (!한_줄도_못_살렸다(result)) {
                            연속_실패.set(0);
                            return Mono.just(누적);
                        }
                        if (연속_실패.incrementAndGet() < 연속_실패_한도) {
                            return Mono.just(누적);
                        }
                        List<Batch> 남은_배치 = batches.subList(Math.toIntExact(indexed.getT1()) + 1, batches.size());
                        TupleWriteResult partial = 남은_배치.stream()
                                .map(batch -> batch.failed("연속 실패로 보내지 않음"))
                                .reduce(누적, OpenFgaRelationTupleWriter::merge);
                        return Mono.<TupleWriteResult>error(new TupleWriteAbortedException(
                                "OpenFGA 쓰기 배치가 %d번 연달아 실패해 남은 %d개 배치를 보내지 않고 멈췄다 — 마지막 오류: %s"
                                        .formatted(연속_실패_한도, 남은_배치.size(), result.failures().get(0).reason()),
                                partial));
                    })
                    .last(TupleWriteResult.empty());
        });
    }

    /** 묶음에서 한 줄도 반영되지 못했다 — 일시 오류로 다 실패했거나, 쪼개도 전부 거절됐다. OpenFGA 가 죽었다는 신호다. */
    private static boolean 한_줄도_못_살렸다(TupleWriteResult result) {
        return result.hasFailure() && result.written().isEmpty() && result.deleted().isEmpty();
    }

    /**
     * 델타를 배치 리스트로 나눈다. 삭제 배치가 항상 쓰기 배치보다 앞에 온다.
     * 같은 델타에 삭제와 생성이 섞였을 때 순서가 뒤집히면 결과가 달라지기 때문이다.
     *
     * <p>패키지 전용으로 열어 둔 것은 이 순서를 단위 테스트로 직접 고정하기 위해서다.
     */
    List<Batch> batchesFor(TupleDelta delta) {
        List<Batch> batches = new ArrayList<>();
        partition(List.copyOf(delta.toDelete())).forEach(chunk -> batches.add(Batch.deletes(chunk)));
        partition(List.copyOf(delta.toWrite())).forEach(chunk -> batches.add(Batch.writes(chunk)));
        return batches;
    }

    private List<List<RelationTuple>> partition(List<RelationTuple> tuples) {
        List<List<RelationTuple>> chunks = new ArrayList<>();
        int size = properties.getWriteBatchSize();
        for (int i = 0; i < tuples.size(); i += size) {
            chunks.add(tuples.subList(i, Math.min(i + size, tuples.size())));
        }
        return chunks;
    }

    private Mono<TupleWriteResult> applyBatch(Batch batch) {
        return 쪼개며_보낸다(batch, this::보낸다, OpenFgaErrors::거절인가);
    }

    /** 한 배치를 보낸다. 일시 오류만 다시 시도한다 — 거절(400)은 다시 보내도 같다. */
    private Mono<Void> 보낸다(Batch batch) {
        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.client().write(toRequest(batch), writeOptions());
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA write 호출 실패", e);
                    }
                })
                .retryWhen(OpenFgaErrors.일시_오류만_다시(properties.getMaxRetries()))
                .then();
    }

    /**
     * 배치를 보내고, OpenFGA 가 거절하면(400) 반으로 나눠 다시 보낸다 — 한 줄까지 좁혀도 거절되면 그 줄만 실패로 남긴다(점검 M16,
     * 설계 2026-09-30 §6.2). 요청 하나가 원자적이라 줄 하나가 걸리면 같은 배치 100줄이 함께 실패했고, 다시 돌려도 같은 99명이 빠졌다.
     * 일시 오류(재시도 뒤에도 실패)는 쪼개지 않는다 — 쪼개도 같이 실패한다.
     *
     * <p>패키지 전용 — 쪼개는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     */
    static Mono<TupleWriteResult> 쪼개며_보낸다(Batch batch, Function<Batch, Mono<Void>> send,
                                            Predicate<Throwable> 거절인가) {
        return send.apply(batch)
                .thenReturn(batch.succeeded())
                .onErrorResume(error -> {
                    if (거절인가.test(error) && batch.tuples().size() > 1) {
                        log.warn("OpenFGA 가 배치 {}건을 거절했다 — 반으로 나눠 다시 보낸다: {}",
                                batch.tuples().size(), rootMessage(error));
                        return Flux.fromIterable(batch.halves())
                                .concatMap(half -> 쪼개며_보낸다(half, send, 거절인가))
                                .reduce(TupleWriteResult.empty(), OpenFgaRelationTupleWriter::merge);
                    }
                    if (거절인가.test(error)) {
                        // 한 줄까지 좁혔는데도 거절됐다 — 이 줄만의 문제이지 OpenFGA 가 죽은 게 아니므로 스택 트레이스 없이 남긴다
                        log.warn("OpenFGA 가 한 줄을 거절했다 — 실패로 남긴다: {} ({})",
                                batch.tuples().get(0), rootMessage(error));
                        return Mono.just(batch.failed(rootMessage(error)));
                    }
                    log.error("배치 {}건 적용 실패", batch.tuples().size(), error);
                    return Mono.just(batch.failed(rootMessage(error)));
                });
    }

    private ClientWriteRequest toRequest(Batch batch) {
        ClientWriteRequest request = new ClientWriteRequest();
        if (batch.delete()) {
            request.deletes(batch.tuples().stream()
                    .map(tuple -> new ClientTupleKeyWithoutCondition()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object()))
                    .toList());
        } else {
            request.writes(batch.tuples().stream()
                    .map(tuple -> new ClientTupleKey()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object()))
                    .toList());
        }
        return request;
    }

    /** 멱등 옵션. 이것이 없으면 rebuild 와 재실행이 배치 단위로 통째로 실패한다. */
    private ClientWriteOptions writeOptions() {
        return new ClientWriteOptions()
                .onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
                .onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static TupleWriteResult merge(TupleWriteResult a, TupleWriteResult b) {
        Set<RelationTuple> written = new HashSet<>(a.written());
        written.addAll(b.written());
        Set<RelationTuple> deleted = new HashSet<>(a.deleted());
        deleted.addAll(b.deleted());
        List<TupleFailure> failures = new ArrayList<>(a.failures());
        failures.addAll(b.failures());
        return new TupleWriteResult(written, deleted, failures);
    }

    /** 패키지 전용. 배치 순서를 검증하는 테스트가 delete() 플래그를 직접 확인한다. */
    record Batch(List<RelationTuple> tuples, boolean delete) {

        static Batch writes(List<RelationTuple> tuples) {
            return new Batch(tuples, false);
        }

        static Batch deletes(List<RelationTuple> tuples) {
            return new Batch(tuples, true);
        }

        TupleWriteResult succeeded() {
            return delete
                    ? new TupleWriteResult(Set.of(), Set.copyOf(tuples), List.of())
                    : new TupleWriteResult(Set.copyOf(tuples), Set.of(), List.of());
        }

        TupleWriteResult failed(String reason) {
            return new TupleWriteResult(Set.of(), Set.of(),
                    tuples.stream().map(tuple -> new TupleFailure(tuple, reason)).toList());
        }

        /** 반으로 나눈다 — 거절된 배치에서 나쁜 줄을 찾아 좁힐 때 쓴다(설계 2026-09-30 §6.2). */
        List<Batch> halves() {
            int mid = tuples.size() / 2;
            return List.of(new Batch(tuples.subList(0, mid), delete),
                    new Batch(tuples.subList(mid, tuples.size()), delete));
        }
    }
}
