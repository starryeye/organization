package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BatchCheck 응답을 <b>요청과 맞춰보는</b> 부분만 떼어 본다. 컨테이너를 띄우지 않는다 —
 * 실서버는 언제나 물어본 만큼 순서대로 답해서, 여기서 잡으려는 상황을 통합 테스트로는
 * 만들 수 없기 때문이다.
 *
 * <p>답을 못 받은 항목을 조용히 "없음" 으로 격하하면 설계 §6 이 금지하는 상태 기준선 폴백과
 * 같은 결이 된다 — 이 결과가 곧 diff 의 기준선이라, 거짓 음성 하나가 <b>지워야 할 튜플의
 * 삭제를 건너뛰는</b> 방향으로 간다.
 */
class OpenFgaBatchCheckResponseTest {

    private static final RelationTuple KIM = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple PARK = RelationTuple.directMember("park", "DEV001");

    private static Map<String, RelationTuple> 요청(RelationTuple... tuples) {
        Map<String, RelationTuple> byCorrelationId = new LinkedHashMap<>();
        for (int i = 0; i < tuples.length; i++) {
            byCorrelationId.put("c" + i, tuples[i]);
        }
        return byCorrelationId;
    }

    private static ClientBatchCheckSingleResponse 답(String correlationId, boolean allowed) {
        return new ClientBatchCheckSingleResponse(allowed, null, correlationId, null);
    }

    @Test
    @DisplayName("응답을 인덱스가 아니라 correlationId 로 짝짓는다")
    void correlationId로_짝짓는다() {
        // given — 물은 순서(c0=kim, c1=park)와 답한 순서가 뒤집혔고, 있는 것은 park 뿐이다.
        // 인덱스로 짝지으면 "kim 이 있다" 는 정반대 답이 나온다.
        var response = new ClientBatchCheckResponse(List.of(답("c1", true), 답("c0", false)));

        // when
        List<RelationTuple> found = OpenFgaRelationTupleChecker.toFound(response, 요청(KIM, PARK));

        // then
        assertThat(found).containsExactly(PARK);
    }

    @Test
    @DisplayName("물어본 것보다 적게 답하면 청크 전체를 실패시킨다")
    void 적게_답하면_실패시킨다() {
        // given — park 의 답이 아예 없다. 그대로 두면 park 는 "확인했고, 없다" 와 구별되지 않는다.
        var response = new ClientBatchCheckResponse(List.of(답("c0", true)));

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleChecker.toFound(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2건을 물었는데 1건만");
    }

    @Test
    @DisplayName("모르는 correlationId 가 오면 조용히 버리지 않고 실패시킨다")
    void 모르는_correlationId는_실패다() {
        // given — 개수는 맞지만 c9 는 우리가 보낸 적이 없다. 전에는 filter(nonNull) 로 버려서
        // 개수만 맞으면 통과했고, 그만큼의 튜플이 답 없이 "없음" 이 됐다.
        var response = new ClientBatchCheckResponse(List.of(답("c0", true), 답("c9", true)));

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleChecker.toFound(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("c9");
    }

    @Test
    @DisplayName("빠짐없이 답하면 allowed 인 것만 돌려준다")
    void 정상_응답은_allowed만_돌려준다() {
        // given
        var response = new ClientBatchCheckResponse(List.of(답("c0", true), 답("c1", false)));

        // when
        List<RelationTuple> found = OpenFgaRelationTupleChecker.toFound(response, 요청(KIM, PARK));

        // then
        assertThat(found).containsExactly(KIM);
    }
}
