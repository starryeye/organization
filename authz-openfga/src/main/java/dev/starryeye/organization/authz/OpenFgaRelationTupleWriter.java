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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
                .then(보내되_연속_실패면_멈춘다(batches, this::applyBatch));
    }

    /**
     * 배치를 차례로 보낸다. {@value #연속_실패_한도}개가 연달아 실패하면 남은 배치를 보내지 않고 {@link TupleWriteAbortedException} 으로
     * 끝낸다(점검 C7, 설계 §5). 그 {@code partial} 은 멈추기 전까지의 결과(멈추게 한 배치까지)에 보내지 않은 배치를 실패로 더한 것이다 —
     * 호출자가 이미 나간 쓰기로 기록 규칙을 지킨다.
     *
     * <p>OpenFGA 가 느리거나 죽으면 배치마다 재시도를 거친 뒤 실패로 넘어가는데, 10만 명 재적재는 배치가 약 1,100개라 전부 그렇게 돌면
     * 수십 분 동안 락을 쥔다. 연달아 실패하는 것은 대개 한 배치가 아니라 OpenFGA 의 문제다. 드문 실패 한두 건은 지금처럼 결과의
     * {@code failures} 로 넘겨 PARTIAL 이 되게 둔다. 배치가 셋보다 적은 쓰기는 해당이 없다 — SCIM 요청 한 건은 대개 여기에 든다.
     * 멤버가 200명 넘게 바뀌는 조직 PATCH·PUT 은 예외로, 멈추면 5xx 가 되고 IdP 재시도가 Check 기준선으로 수렴한다.
     *
     * <p>패키지 전용 — 멈추는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     */
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send) {
        return Mono.defer(() -> {
            AtomicInteger 연속_실패 = new AtomicInteger();
            AtomicReference<TupleWriteResult> 지금까지 = new AtomicReference<>(TupleWriteResult.empty());
            return Flux.fromIterable(batches)
                    .index()
                    .concatMap(indexed -> send.apply(indexed.getT2()).flatMap(result -> {
                        TupleWriteResult 누적 = 지금까지.accumulateAndGet(result, OpenFgaRelationTupleWriter::merge);
                        if (!result.hasFailure()) {
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
                    }))
                    .last(TupleWriteResult.empty());
        });
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
        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.client().write(toRequest(batch), writeOptions());
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA write 호출 실패", e);
                    }
                })
                .retryWhen(Retry.backoff(properties.getMaxRetries(), Duration.ofMillis(200)))
                .thenReturn(batch.succeeded())
                .onErrorResume(error -> {
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
    }
}
