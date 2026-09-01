package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OpenFGA 에 인가 판정을 묻는다. 열거 API 는 쓰지 않는다 — {@code Check} 는 점 조회다.
 *
 * <p><b>{@code findExistingStore()} 를 쓴다.</b> {@code resolveStore()} 는 store 가 없으면
 * 만들고 인가 모델을 쓴다. 조회 경로가 인프라를 프로비저닝하면 안 된다. store 가 없으면
 * Check 가 성립할 수 없으므로 에러로 끝내고, 호출자가 그것을 "판정 보류" 로 옮긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaRelationTupleChecker implements RelationTupleChecker {

    /**
     * 배치 상한만큼 잘라 물어본다. OpenFGA 서버 기본값이 요청당 50건이라 그보다 크게 보내면
     * 통째로 거절당한다 — 조직 하나가 50명을 넘는 것은 평범하므로 반드시 나눠야 한다.
     */
    private static final int BATCH_SIZE = 50;

    private final StoreBootstrapper bootstrapper;

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return bootstrapper.findExistingStore()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "OpenFGA store 가 아직 없어 Check 를 할 수 없다")))
                .flatMap(storeId -> Mono.fromFuture(() -> {
                            try {
                                return bootstrapper.clientFor(storeId).check(new ClientCheckRequest()
                                        .user(tuple.user())
                                        .relation(tuple.relation())
                                        ._object(tuple.object()));
                            } catch (Exception e) {
                                throw new IllegalStateException("OpenFGA check 호출 실패", e);
                            }
                        })
                        .map(response -> Boolean.TRUE.equals(response.getAllowed())))
                .doOnError(error -> log.debug("OpenFGA check 실패: user={}, relation={}, object={}",
                        tuple.user(), tuple.relation(), tuple.object(), error));
    }

    @Override
    public Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates) {
        if (candidates.isEmpty()) {
            return Mono.just(Set.of());
        }
        return bootstrapper.findExistingStore()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "OpenFGA store 가 아직 없어 BatchCheck 를 할 수 없다")))
                .flatMap(storeId -> Flux.fromIterable(List.copyOf(candidates))
                        .buffer(BATCH_SIZE)
                        .concatMap(chunk -> checkChunk(storeId, chunk))
                        .collect(LinkedHashSet<RelationTuple>::new, Set::add)
                        .map(found -> (Set<RelationTuple>) found));
    }

    /**
     * {@code correlationId} 로 응답과 요청을 잇는다. 응답 순서는 보장되지 않으므로
     * 인덱스로 짝지으면 <b>엉뚱한 튜플이 있다고 판단</b>한다.
     *
     * <p>SDK 0.9.11 의 {@code ClientBatchCheckResponse.getResult()} 는 맵이 아니라
     * {@code List<ClientBatchCheckSingleResponse>} 를 준다. 각 원소가 스스로
     * {@code getCorrelationId()}/{@code isAllowed()} 를 들고 있어, 리스트를 순회하며
     * 요청 시 만든 correlationId → tuple 맵에서 찾아 짝짓는다.
     */
    private Flux<RelationTuple> checkChunk(String storeId, List<RelationTuple> chunk) {
        Map<String, RelationTuple> byCorrelationId = new LinkedHashMap<>();
        List<ClientBatchCheckItem> items = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            String correlationId = "c" + i;
            RelationTuple tuple = chunk.get(i);
            byCorrelationId.put(correlationId, tuple);
            items.add(new ClientBatchCheckItem()
                    .user(tuple.user())
                    .relation(tuple.relation())
                    ._object(tuple.object())
                    .correlationId(correlationId));
        }

        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.clientFor(storeId)
                                .batchCheck(new ClientBatchCheckRequest().checks(items));
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA batchCheck 호출 실패", e);
                    }
                })
                .flatMapIterable(response -> response.getResult().stream()
                        .filter(single -> single.isAllowed())
                        .map(single -> byCorrelationId.get(single.getCorrelationId()))
                        .filter(Objects::nonNull)
                        .toList());
    }
}
