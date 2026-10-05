package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.configuration.ClientBatchCheckOptions;
import dev.openfga.sdk.api.configuration.ClientCheckOptions;
import dev.openfga.sdk.api.model.CheckError;
import dev.openfga.sdk.api.model.ConsistencyPreference;
import dev.openfga.sdk.api.model.ErrorCode;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OpenFGA 에 인가 판정을 묻는다. 열거 API 는 쓰지 않는다 — {@code Check} 는 점 조회다.
 *
 * <p><b>쓰기와 같은 준비 과정({@code resolveStore()})을 탄다(점검 M6).</b> store 가 없으면 만들고 인가 모델을 등록한다. 시작 때
 * OpenFGA 가 안 닿아 준비하지 못했어도 첫 요청 때 닿으면 그때 준비되고, 안 닿으면 그 요청만 실패하고 다음 요청이 다시 시도한다
 * (실패는 캐시하지 않는다). 전에는 {@code findExistingStore()} 를 써서 store 가 없으면 재시작 전까지 멤버 추가가 전부 500 이었다.
 * 헬스 체크만 여전히 {@code findExistingStore()}(보기만)다 — 오타 난 이름으로 빈 store 를 만들지 않는다.
 */
@Slf4j
public class OpenFgaRelationTupleChecker implements RelationTupleChecker {

    /**
     * 배치 상한만큼 잘라 물어본다. OpenFGA 서버 기본값이 요청당 50건이라 그보다 크게 보내면
     * 통째로 거절당한다 — 조직 하나가 50명을 넘는 것은 평범하므로 반드시 나눠야 한다.
     */
    private static final int BATCH_SIZE = 50;

    /**
     * 캐시가 아니라 지금 실제로 있는 것을 묻는다(점검 C8). 쓰기 경로는 Check 결과를 "지금 있는 것"으로 보고 무엇을 쓰고 지울지 정한다 —
     * OpenFGA Check 캐시를 켜면 기본(MINIMIZE_LATENCY)은 캐시된 답을 줘, 넣고 곧바로 뺀 멤버의 튜플이 안 지워진다. 관리 조회·아카이빙도
     * "실제"를 보여 주는 자리라 같은 규칙을 쓴다. 권한을 묻는 다른 앱은 계속 캐시를 쓸 수 있다.
     */
    private static final ConsistencyPreference 실제_값 = ConsistencyPreference.HIGHER_CONSISTENCY;

    private final StoreBootstrapper bootstrapper;
    /** 동시에 보내는 묶음 수(설계 2026-10-02 §4.3). */
    private final int 동시;

    /** 묶음을 하나씩 묻는다 — 테스트용. 운영 결선은 {@code openfga.request-concurrency} 를 넘긴다. */
    public OpenFgaRelationTupleChecker(StoreBootstrapper bootstrapper) {
        this(bootstrapper, 1);
    }

    public OpenFgaRelationTupleChecker(StoreBootstrapper bootstrapper, int 동시) {
        this.bootstrapper = bootstrapper;
        this.동시 = Math.max(1, 동시);
    }

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return bootstrapper.resolveStore()
                .flatMap(storeId -> Mono.fromFuture(() -> {
                            try {
                                return bootstrapper.clientFor(storeId).check(new ClientCheckRequest()
                                                .user(tuple.user())
                                                .relation(tuple.relation())
                                                ._object(tuple.object()),
                                        new ClientCheckOptions().consistency(실제_값));
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
        return bootstrapper.resolveStore()
                .flatMap(storeId -> Flux.fromIterable(List.copyOf(candidates))
                        .buffer(BATCH_SIZE)
                        .flatMap(chunk -> checkChunk(storeId, chunk), 동시)
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
                                .batchCheck(new ClientBatchCheckRequest().checks(items),
                                        new ClientBatchCheckOptions().consistency(실제_값));
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA batchCheck 호출 실패", e);
                    }
                })
                .flatMapIterable(response -> toFound(response, byCorrelationId));
    }

    /**
     * 응답이 <b>물어본 것을 빠짐없이, 오류 없이</b> 답했을 때만 결과를 돌려준다. 하나라도
     * 어긋나면 청크 전체를 실패시킨다.
     *
     * <p>세 가지를 본다.
     * <ol>
     *   <li><b>개별 오류</b>({@code getError() != null}) — 서버가 배치 자체는 받아들이고도 특정
     *       항목의 판정에는 실패할 수 있다.</li>
     *   <li><b>응답 개수</b> — 물어본 수보다 적으면 빠진 항목은 아무 데도 나타나지 않는다.</li>
     *   <li><b>correlationId 의 일대일 대응</b> — 모르는 id 가 오면 그 항목이 어느 튜플의 답인지
     *       알 수 없고, 같은 id 가 두 번 오면 개수만 맞은 채 다른 튜플 하나가 답 없이 남는다.
     *       그래서 "해석된다" 로는 부족하고 <b>요청 하나가 정확히 한 번씩 소진</b>돼야 한다 —
     *       맵에서 빼면서 읽는 이유다. 전에는 {@code filter(Objects::nonNull)} 로 조용히 버렸다.</li>
     * </ol>
     *
     * <p><b>셋 다 같은 방향으로 틀린다.</b> 답을 못 받은 항목은 {@code isAllowed()} 필터를 그냥
     * 통과해 "확인했고, 없다" 와 구별되지 않는다 — 설계 §6 이 금지하는 상태 기준선 폴백과 같은
     * 결이다. diff 의 기준선이 되는 이 결과에서 조용히 "없음" 으로 내려가면, 실제로 있는 튜플을
     * 다시 쓰거나(무해) <b>실제로 지워야 할 튜플의 삭제를 건너뛴다</b>(유해). 그래서 폴백하지
     * 않고 예외로 멈춘다 — IdP 가 재시도한다. 서버가 답을 못 한 것이라 {@link TemporaryFailureException} 이다. 다만 개별 오류에
     * 서버가 입력 오류로 표시한 것이 하나라도 있으면 다시 물어도 같아 {@code IllegalStateException}(우리 버그)이다.
     *
     * <p>{@code correlationId} 로 짝짓는 것 자체도 여기서 못박힌다. 응답 순서는 보장되지 않아
     * 인덱스로 짝지으면 <b>엉뚱한 튜플이 있다고 판단</b>하는데, 실서버는 대개 순서를 지켜서
     * 통합 테스트만으로는 두 방식이 구별되지 않는다.
     */
    static List<RelationTuple> toFound(
            ClientBatchCheckResponse response, Map<String, RelationTuple> byCorrelationId) {
        List<ClientBatchCheckSingleResponse> results = response.getResult();

        List<ClientBatchCheckSingleResponse> errored = results.stream()
                .filter(single -> single.getError() != null)
                .toList();
        if (!errored.isEmpty()) {
            Optional<ClientBatchCheckSingleResponse> 입력_오류 = errored.stream()
                    .filter(single -> 입력_오류인가(single.getError()))
                    .findFirst();
            // 예로 드는 것은 낫지 않는 쪽(입력 오류)이 있으면 그것이다 — 500 의 원인을 가리지 않는다
            ClientBatchCheckSingleResponse example = 입력_오류.orElse(errored.get(0));
            String exampleMessage = example.getError().getMessage();
            log.error("OpenFGA batchCheck 중 {}건이 개별 오류로 끝났다 (예: correlationId={}, message={})",
                    errored.size(), example.getCorrelationId(), exampleMessage);
            String message = "OpenFGA batchCheck 중 %d건이 개별 오류로 끝났다(예: %s) — 상태 기준선으로 폴백하지 않는다"
                    .formatted(errored.size(), exampleMessage);
            // 입력 오류가 하나라도 있으면 거절이라 다시 물어도 같다 — 우리 버그(500). 내부 오류뿐이면 일시 장애(503)다 (설계 2026-10-05 §3.1)
            if (입력_오류.isPresent()) {
                throw new IllegalStateException(message);
            }
            throw new TemporaryFailureException(message, TemporaryFailureException.기본_대기);
        }

        if (results.size() != byCorrelationId.size()) {
            throw new TemporaryFailureException(
                    "OpenFGA batchCheck 가 %d건을 물었는데 %d건만 답했다 — 빠진 항목을 '없음'으로 격하하지 않는다"
                            .formatted(byCorrelationId.size(), results.size()),
                    TemporaryFailureException.기본_대기);
        }

        // 빼면서 읽는다 — 요청 하나가 정확히 한 번씩 소진돼야 한다. 개수가 같은데 어떤 id 가
        // 두 번 오면, 그만큼 다른 튜플 하나가 답 없이 남아 "확인했고, 없다" 로 격하된다.
        Map<String, RelationTuple> 답을_기다리는것 = new LinkedHashMap<>(byCorrelationId);
        List<RelationTuple> found = new ArrayList<>();
        for (ClientBatchCheckSingleResponse single : results) {
            RelationTuple tuple = 답을_기다리는것.remove(single.getCorrelationId());
            if (tuple == null) {
                throw new TemporaryFailureException(
                        ("OpenFGA batchCheck 응답의 correlationId '%s' 가 요청에 없거나 두 번 왔다 "
                                + "— 어느 튜플의 답인지 알 수 없다").formatted(single.getCorrelationId()),
                        TemporaryFailureException.기본_대기);
            }
            if (single.isAllowed()) {
                found.add(tuple);
            }
        }
        return found;
    }

    /**
     * 서버가 "입력이 틀렸다" 고 표시한 개별 오류인가. {@code inputError} 는 프로토 enum 이라 오류가 아니어도 기본값 {@code NO_ERROR} 가
     * 실려 올 수 있다. 표시가 없는 것(내부 오류뿐이거나 둘 다 없음)은 입력 오류가 아니다.
     */
    private static boolean 입력_오류인가(CheckError error) {
        return error.getInputError() != null && error.getInputError() != ErrorCode.NO_ERROR;
    }
}
