package dev.starryeye.organization.authz.fixture;

import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.model.CheckError;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code OpenFgaProbe#toAnswers} — BatchCheck 응답을 요청과 맞춰보는 부분만 떼어 본다.
 * 컨테이너를 띄우지 않는다 — 실서버는 언제나 물어본 만큼, 오류 없이 답해서 여기서 잡으려는
 * 상황(개별 오류, 응답 누락, correlationId 불일치)을 통합 테스트로는 만들 수 없기 때문이다.
 *
 * <p>{@code OpenFgaRelationTupleCheckerTest} 옆의 {@code OpenFgaBatchCheckResponseTest} 와
 * 같은 이유·같은 모양으로 짰다 — 다만 이 프로브는 답을 못 받은 항목을 <b>false 로 채우면
 * 특히 위험하다.</b> 롤업 교차검증({@code OpenFgaProbe#직접_대조한다})은 한 직원에 대해
 * ~250건을 물어 전부 false 여야 통과하므로, false 가 이 프로브가 절대다수 항목에서 기대하는
 * "정상" 값이다. 답을 못 받은 항목이 조용히 false 가 되면 기대값과 우연히 일치해, 실제로
 * 새는 권한이 있어도 "이상 없음"으로 보고된다 — 아래 각 테스트가 그 경로를 하나씩 막는다.
 */
class OpenFgaProbeBatchCheckResponseTest {

    private static final RelationTuple KIM = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple PARK = RelationTuple.directMember("park", "DEV001");

    private static Map<String, RelationTuple> 요청(RelationTuple... tuples) {
        Map<String, RelationTuple> byCorrelation = new LinkedHashMap<>();
        for (int i = 0; i < tuples.length; i++) {
            byCorrelation.put("c" + i, tuples[i]);
        }
        return byCorrelation;
    }

    private static ClientBatchCheckSingleResponse 답(String correlationId, boolean allowed) {
        return new ClientBatchCheckSingleResponse(allowed, null, correlationId, null);
    }

    private static ClientBatchCheckSingleResponse 오류_답(String correlationId, String message) {
        return new ClientBatchCheckSingleResponse(
                false, null, correlationId, new CheckError().message(message));
    }

    @Test
    @DisplayName("항목 하나라도 개별 오류로 답하면 false 로 채우지 않고 던진다")
    void 개별_오류가_있으면_던진다() {
        // given — park 항목이 서버 내부 오류로 답을 못 받았다. 예전 코드는 getError() 를
        // 아예 보지 않고 이걸 그냥 false 로 채웠다.
        var response = new ClientBatchCheckResponse(List.of(
                답("c0", true), 오류_답("c1", "internal error")));

        // when, then
        assertThatThrownBy(() -> OpenFgaProbe.toAnswers(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1건")
                .hasMessageContaining("internal error");
    }

    @Test
    @DisplayName("물어본 것보다 적게 답하면 빠진 항목을 false 로 채우지 않고 던진다")
    void 적게_답하면_던진다() {
        // given — park 의 답이 아예 없다. 예전 코드의 putIfAbsent(tuple, false) 가
        // 바로 이 빈자리를 false 로 채우던 지점이다.
        var response = new ClientBatchCheckResponse(List.of(답("c0", true)));

        // when, then
        assertThatThrownBy(() -> OpenFgaProbe.toAnswers(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2건을 물었는데 1건만");
    }

    @Test
    @DisplayName("모르는 correlationId 가 오면 조용히 버리지 않고 던진다")
    void 모르는_correlationId는_던진다() {
        // given — 개수는 맞지만(2) c9 는 우리가 보낸 적이 없다. 예전 코드는 byCorrelation
        // 에 없으면 그냥 무시(forEach 안의 if(tuple != null))하고 지나가, park 는 결국
        // putIfAbsent 로 false 가 됐다.
        var response = new ClientBatchCheckResponse(List.of(답("c0", true), 답("c9", true)));

        // when, then
        assertThatThrownBy(() -> OpenFgaProbe.toAnswers(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("c9");
    }

    @Test
    @DisplayName("같은 correlationId 가 두 번 오면 개수가 맞아도 던진다")
    void 중복된_correlationId는_던진다() {
        // given — 개수(2)도 맞고 c0 는 요청에 있는 id 다. 그런데 park(c1) 는 답을 받지
        // 못했고, 개수 검사만으로는 이것이 통과해 park 가 조용히 false 로 남는다.
        var response = new ClientBatchCheckResponse(List.of(답("c0", true), 답("c0", true)));

        // when, then
        assertThatThrownBy(() -> OpenFgaProbe.toAnswers(response, 요청(KIM, PARK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("두 번");
    }

    @Test
    @DisplayName("빠짐없이 오류 없이 답하면 true/false 를 그대로 매핑한다")
    void 정상_응답은_그대로_매핑된다() {
        // given
        var response = new ClientBatchCheckResponse(List.of(답("c0", true), 답("c1", false)));

        // when
        Map<RelationTuple, Boolean> 결과 = OpenFgaProbe.toAnswers(response, 요청(KIM, PARK));

        // then
        assertThat(결과).containsEntry(KIM, true).containsEntry(PARK, false);
    }
}
