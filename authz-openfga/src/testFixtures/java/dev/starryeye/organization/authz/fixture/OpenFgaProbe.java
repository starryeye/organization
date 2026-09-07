package dev.starryeye.organization.authz.fixture;

import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
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
        try {
            var response = bootstrapper.client()
                    .batchCheck(new ClientBatchCheckRequest().checks(items)).get();
            Map<RelationTuple, Boolean> 결과 = new LinkedHashMap<>();
            response.getResult().forEach(single -> {
                RelationTuple tuple = byCorrelation.get(single.getCorrelationId());
                if (tuple != null) {
                    결과.put(tuple, Boolean.TRUE.equals(single.isAllowed()));
                }
            });
            조각.forEach(tuple -> 결과.putIfAbsent(tuple, false));
            return 결과;
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA batchCheck 실패", e);
        }
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
