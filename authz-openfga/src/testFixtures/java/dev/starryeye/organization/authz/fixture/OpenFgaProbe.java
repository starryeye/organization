package dev.starryeye.organization.authz.fixture;

import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.VerificationResult;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.tuple.TupleMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OpenFGA 를 <b>우리 어댑터를 거치지 않고</b> 직접 두드린다.
 *
 * <p>{@code SyncVerifier} 는 {@code RelationTupleChecker} 포트로 묻는다. 그 구현
 * ({@code OpenFgaRelationTupleChecker})에 결함이 있으면 — 예를 들어 BatchCheck 응답을
 * correlationId 로 맞추지 않고 순서로 맞춘다면 — <b>검증이 그 결함에 같이 속는다.</b>
 * 어댑터가 "있다"고 말한 것을 어댑터로 확인하는 셈이기 때문이다.
 *
 * <p>그래서 이 도구는 OpenFGA SDK 를 그대로 쓴다. 같은 사실을 서로 다른 경로로 두 번 물어
 * 답이 갈리면, 갈렸다는 것 자체가 결함이다.
 *
 * <p>열거 API({@code Read}/{@code ListObjects})는 여기서도 쓰지 않는다. 운영 환경이 그것을
 * 허용하지 않으므로, 검증만 열거에 기대면 로컬에서만 성립하는 검증이 된다.
 */
public final class OpenFgaProbe {

    /** OpenFGA 서버 기본 상한. 넘겨 보내면 요청이 통째로 거절된다. */
    private static final int BATCH_SIZE = 50;

    private final StoreBootstrapper bootstrapper;

    public OpenFgaProbe(StoreBootstrapper bootstrapper) {
        this.bootstrapper = bootstrapper;
    }

    /**
     * 우리 쓰기 경로를 <b>거치지 않고</b> OpenFGA 에 튜플을 직접 심는다.
     *
     * <p>동기화가 만들 수 없는 상태 — 멤버십이 아예 없는 고아 튜플 — 를 일부러 만들기 위한
     * 것이다. 설계 §5.4 가 "이건 우리 검증으로 못 잡는다" 고 적어 둔 한계를 실제로 만들어
     * 놓고, 정말 안 잡히는지 그리고 무엇으로는 지워지는지를 고정한다.
     */
    public void 직접_심는다(RelationTuple tuple) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                    new ClientTupleKey()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object())))).get();
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA 직접 쓰기 실패: " + tuple, e);
        }
    }

    /** 우리 쓰기 경로를 거치지 않고 직접 지운다. 어긋남을 일부러 만들 때 쓴다. */
    public void 직접_지운다(RelationTuple tuple) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().deletes(List.of(
                    new ClientTupleKeyWithoutCondition()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object())))).get();
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA 직접 삭제 실패: " + tuple, e);
        }
    }

    /** 단건 Check. */
    public boolean check(RelationTuple tuple) {
        try {
            return Boolean.TRUE.equals(bootstrapper.client()
                    .check(new ClientCheckRequest()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object()))
                    .get().getAllowed());
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA check 실패: " + tuple, e);
        }
    }

    /**
     * BatchCheck 로 여러 건을 한 번에 묻는다.
     *
     * <p>응답을 <b>{@code correlationId} 로</b> 되맞춘다. 순서로 맞추면 서버가 순서를 바꿔
     * 돌려줄 때 조용히 다른 튜플의 답을 읽는다 — 그러면 이 도구도 어댑터와 같은 함정에 빠져
     * 교차 검증의 의미가 사라진다.
     */
    public Map<RelationTuple, Boolean> batchCheck(Set<RelationTuple> tuples) {
        Map<RelationTuple, Boolean> 결과 = new LinkedHashMap<>();
        List<RelationTuple> 남은것 = new ArrayList<>(tuples);
        for (int i = 0; i < 남은것.size(); i += BATCH_SIZE) {
            List<RelationTuple> 조각 = 남은것.subList(i, Math.min(i + BATCH_SIZE, 남은것.size()));
            결과.putAll(한_묶음(조각));
        }
        return 결과;
    }

    private Map<RelationTuple, Boolean> 한_묶음(List<RelationTuple> 조각) {
        Map<String, RelationTuple> byCorrelation = new LinkedHashMap<>();
        List<ClientBatchCheckItem> items = new ArrayList<>();
        for (RelationTuple tuple : 조각) {
            String correlationId = UUID.randomUUID().toString();
            byCorrelation.put(correlationId, tuple);
            items.add(new ClientBatchCheckItem()
                    .user(tuple.user())
                    .relation(tuple.relation())
                    ._object(tuple.object())
                    .correlationId(correlationId));
        }
        ClientBatchCheckResponse response;
        try {
            response = bootstrapper.client()
                    .batchCheck(new ClientBatchCheckRequest().checks(items)).get();
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA batchCheck 실패", e);
        }
        // 응답 해석(개별 오류/개수/correlationId 대응)은 SDK 호출 실패를 감싸는 위 try 밖에서
        // 한다 — 여기서 던지는 예외가 "OpenFGA batchCheck 실패" 로 다시 뭉뚱그려지면 원인을
        // 구분할 수 없다.
        return toAnswers(response, byCorrelation);
    }

    /**
     * BatchCheck 응답을 요청과 맞춰 {@code true}/{@code false} 로 바꾼다. 답을 못 받은 항목은
     * 이 자리에서 절대 {@code false} 로 채우지 않는다 — 던진다.
     *
     * <p>이 프로브의 롤업 교차검증은 한 직원에 대해 ~250건을 물어 <b>전부 false 여야</b>
     * 통과한다({@code 직접_대조한다} 참고) — 즉 {@code false} 는 이 프로브가 절대다수 항목에서
     * 기대하는 "정상" 값이다. 그래서 오류로 답을 못 받은 항목·응답에 아예 없는 항목·중복 응답으로
     * 밀려난 항목을 예전처럼 {@code putIfAbsent(tuple, false)} 로 채우면, 그 항목은 "확인했고
     * 없다" 와 구별되지 않는 채로 기대값(false)과 우연히 일치해버린다. 청크 하나가 통째로
     * 에러났을 때(스로틀링, 일시적 store 장애, model-id 불일치) 그 청크의 모든 항목이 이렇게
     * false 로 채워지고 기대값과 맞아떨어져, 아래로 새는 권한이 있어도 교차검증이 "이상 없음"
     * 이라고 보고한다 — 아무것도 확인하지 않고도 통과하는 것이다. 그래서 어댑터의
     * {@code OpenFgaRelationTupleChecker#toFound} 와 같은 결로, 답을 못 받은 항목을 상태
     * 기준선(false)으로 폴백시키지 않고 예외로 멈춘다.
     *
     * <p>어댑터 코드를 그대로 불러 쓰지 않고 여기서 따로 짠다 — 이 프로브의 존재 이유가
     * "어댑터와 다른 경로로 같은 사실을 물어, 둘이 갈리면 그 자체가 결함" 이기 때문이다.
     * 어댑터 것을 재사용하면 어댑터에 있는 결함이 이 검사도 함께 속인다.
     */
    static Map<RelationTuple, Boolean> toAnswers(
            ClientBatchCheckResponse response,
            Map<String, RelationTuple> byCorrelation) {
        List<ClientBatchCheckSingleResponse> results = response.getResult();

        List<ClientBatchCheckSingleResponse> 오류난것 = results.stream()
                .filter(single -> single.getError() != null)
                .toList();
        if (!오류난것.isEmpty()) {
            throw new IllegalStateException(
                    "OpenFGA batchCheck 중 %d건이 개별 오류로 끝났다(예: %s) — 상태 기준선(false)으로 폴백하지 않는다"
                            .formatted(오류난것.size(), 오류난것.get(0).getError().getMessage()));
        }

        if (results.size() != byCorrelation.size()) {
            throw new IllegalStateException(
                    "OpenFGA batchCheck 가 %d건을 물었는데 %d건만 답했다 — 빠진 항목을 '없음(false)'으로 격하하지 않는다"
                            .formatted(byCorrelation.size(), results.size()));
        }

        // 빼면서 읽는다 — 요청 하나가 정확히 한 번씩 소진돼야 한다. 개수가 같은데 어떤 id 가
        // 두 번 오면, 그만큼 다른 튜플 하나가 답 없이 남아 조용히 "없음(false)" 으로 격하된다.
        Map<String, RelationTuple> 답을_기다리는것 = new LinkedHashMap<>(byCorrelation);
        Map<RelationTuple, Boolean> 결과 = new LinkedHashMap<>();
        for (ClientBatchCheckSingleResponse single : results) {
            RelationTuple tuple = 답을_기다리는것.remove(single.getCorrelationId());
            if (tuple == null) {
                throw new IllegalStateException(
                        ("OpenFGA batchCheck 응답의 correlationId '%s' 가 요청에 없거나 두 번 왔다 "
                                + "— 어느 튜플의 답인지 알 수 없다").formatted(single.getCorrelationId()));
            }
            결과.put(tuple, Boolean.TRUE.equals(single.isAllowed()));
        }
        return 결과;
    }

    /**
     * 조직도가 요구하는 상태를 OpenFGA 에 직접 물어 대조한다 — 양성·음성·롤업을 한 번에.
     *
     * <p>{@code SyncVerifier} 와 같은 것을 보지만 경로가 다르다. 둘이 같은 답을 내야 하고,
     * 갈리면 어느 한쪽 — 대개 그 사이에 있는 어댑터 — 이 틀린 것이다.
     */
    public VerificationResult 직접_대조한다(OrgChart chart, List<String> 롤업표본) {
        List<String> 어긋남 = new ArrayList<>();

        Set<RelationTuple> 기대튜플 = TupleMapper.toTuples(chart.snapshot()).tuples();
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(chart.snapshot());
        Set<RelationTuple> 물어볼것 = new LinkedHashSet<>(후보);
        물어볼것.addAll(기대튜플);

        Map<RelationTuple, Boolean> 답 = batchCheck(물어볼것);
        답.forEach((tuple, allowed) -> {
            boolean 기대값 = 기대튜플.contains(tuple);
            if (기대값 != allowed) {
                어긋남.add("OpenFGA 직접질의: %s 가 기대=%s 실제=%s"
                        .formatted(읽기쉽게(tuple), 기대값, allowed));
            }
        });

        Set<RelationTuple> 롤업 = new LinkedHashSet<>();
        Map<RelationTuple, Boolean> 롤업기대 = new LinkedHashMap<>();
        for (String userId : 롤업표본) {
            // 비활성 직원은 소속이 그대로여도 권한이 없다 — 멤버십은 남기고 튜플만 지우는
            // 것이 비활성의 정의다(설계 §5.1).
            var user = chart.snapshot().users().get(userId);
            boolean 활성 = user != null && user.active();
            for (String org : chart.기대소속(userId)) {
                RelationTuple tuple = RelationTuple.member(userId, org);
                롤업.add(tuple);
                롤업기대.put(tuple, 활성);
            }
            // 소속이 없는 직원 — 아직 어느 조직에도 안 들어갔거나 방금 조직이 지워진 —
            // 은 "아래로 새는지" 를 물을 기준 조직 자체가 없다. 여기서 터뜨리면 정작
            // 검증하려던 것이 가려진다.
            for (String 직속 : chart.직속조직들(userId)) {
                for (String org : chart.자손들(직속)) {
                    if (chart.기대소속(userId).contains(org)) {
                        continue;
                    }
                    RelationTuple tuple = RelationTuple.member(userId, org);
                    롤업.add(tuple);
                    롤업기대.put(tuple, false);
                }
            }
        }
        batchCheck(롤업).forEach((tuple, allowed) -> {
            if (!롤업기대.get(tuple).equals(allowed)) {
                어긋남.add("OpenFGA 직접질의 롤업: %s 가 기대=%s 실제=%s"
                        .formatted(읽기쉽게(tuple), 롤업기대.get(tuple), allowed));
            }
        });

        return new VerificationResult(어긋남);
    }

    private static String 읽기쉽게(RelationTuple tuple) {
        return "(%s, %s, %s)".formatted(tuple.user(), tuple.relation(), tuple.object());
    }
}
