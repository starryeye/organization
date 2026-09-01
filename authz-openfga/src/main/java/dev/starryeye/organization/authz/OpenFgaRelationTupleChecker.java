package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
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
                .flatMapIterable(response -> toFound(response, byCorrelationId));
    }

    /**
     * 응답 중 하나라도 개별 오류({@code getError() != null})를 실었으면 그 항목을
     * "없음" 으로 넘기지 않고 전체를 실패시킨다.
     *
     * <p>서버가 배치 자체는 받아들이고도 특정 항목의 판정에는 실패할 수 있다. 그 항목은
     * {@code isAllowed()} 필터를 그냥 통과해 "확인했고, 없다" 와 구별되지 않게 된다 — 이는
     * 설계 §6 이 금지하는 상태 기준선 폴백과 같은 결이다: diff 의 기준선이 되는 이 결과에서
     * 조용히 "없음" 으로 내려가면, 실제로 있는 튜플을 다시 쓰거나(무해) 실제로 지워야 할
     * 튜플의 삭제를 건너뛰는(유해) 쪽으로 이어질 수 있다. 그래서 폴백하지 않고 예외로 멈춘다.
     */
    private static List<RelationTuple> toFound(
            ClientBatchCheckResponse response, Map<String, RelationTuple> byCorrelationId) {
        List<ClientBatchCheckSingleResponse> results = response.getResult();

        List<ClientBatchCheckSingleResponse> errored = results.stream()
                .filter(single -> single.getError() != null)
                .toList();
        if (!errored.isEmpty()) {
            String firstMessage = errored.get(0).getError().getMessage();
            log.error("OpenFGA batchCheck 중 {}건이 개별 오류로 끝났다 (예: correlationId={}, message={})",
                    errored.size(), errored.get(0).getCorrelationId(), firstMessage);
            throw new IllegalStateException(
                    "OpenFGA batchCheck 중 %d건이 개별 오류로 끝났다(예: %s) — 상태 기준선으로 폴백하지 않는다"
                            .formatted(errored.size(), firstMessage));
        }

        return results.stream()
                .filter(ClientBatchCheckSingleResponse::isAllowed)
                .map(single -> byCorrelationId.get(single.getCorrelationId()))
                .filter(Objects::nonNull)
                .toList();
    }
}
