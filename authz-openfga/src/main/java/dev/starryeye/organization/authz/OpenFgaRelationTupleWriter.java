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

/**
 * 델타를 OpenFGA 에 반영한다. 읽기 API 는 호출하지 않는다.
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

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        if (delta.isEmpty()) {
            return Mono.just(TupleWriteResult.empty());
        }

        List<Batch> batches = new ArrayList<>();
        partition(List.copyOf(delta.toDelete())).forEach(chunk -> batches.add(Batch.deletes(chunk)));
        partition(List.copyOf(delta.toWrite())).forEach(chunk -> batches.add(Batch.writes(chunk)));

        return bootstrapper.resolveStore()
                .thenMany(Flux.fromIterable(batches).concatMap(this::applyBatch))
                .reduce(TupleWriteResult.empty(), OpenFgaRelationTupleWriter::merge);
    }

    @Override
    public Mono<Void> resetStore() {
        log.warn("OpenFGA store 를 재생성한다. 재생성이 끝날 때까지 모든 인가 질의가 실패한다");
        return bootstrapper.recreateStore().then();
    }

    /** 삭제를 먼저 처리한다. 같은 델타에 삭제와 생성이 섞였을 때 순서가 뒤집히면 결과가 달라진다. */
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

    private record Batch(List<RelationTuple> tuples, boolean delete) {

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
