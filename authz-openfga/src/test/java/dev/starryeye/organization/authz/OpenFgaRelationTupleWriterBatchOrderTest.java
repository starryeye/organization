package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply() 가 실제로 OpenFGA 를 호출하기 전, 배치 순서 자체를 고정한다.
 *
 * <p>컨테이너 없이 순수 단위 테스트로 검증한다. 삭제와 생성이 서로 무관한 튜플을
 * 다루는 델타는 최종 상태만 보면 순서가 뒤집혀도 같은 결과를 내므로, end-to-end
 * Check 로는 순서 회귀를 잡을 수 없다 — batchesFor() 가 반환하는 리스트를 직접 검사한다.
 */
class OpenFgaRelationTupleWriterBatchOrderTest {

    private final OpenFgaProperties properties = new OpenFgaProperties();
    private final OpenFgaRelationTupleWriter writer =
            new OpenFgaRelationTupleWriter(new StoreBootstrapper(properties), properties);

    @Test
    @DisplayName("삭제 배치는 항상 쓰기 배치보다 앞에 온다")
    void 삭제_배치가_쓰기_배치보다_항상_앞에_온다() {
        // given — 서로 무관한 삭제 대상과 쓰기 대상. 최종 상태만으로는 순서를 구분할 수 없다.
        var 쓰기대상 = RelationTuple.directMember("park", "DEV002");
        var 삭제대상 = RelationTuple.directMember("lee", "DEV002");
        var delta = new TupleDelta(Set.of(쓰기대상), Set.of(삭제대상));

        // when
        List<OpenFgaRelationTupleWriter.Batch> batches = writer.batchesFor(delta);

        // then
        assertThat(batches).isNotEmpty();
        int lastDeleteIndex = -1;
        int firstWriteIndex = -1;
        for (int i = 0; i < batches.size(); i++) {
            OpenFgaRelationTupleWriter.Batch batch = batches.get(i);
            if (batch.delete()) {
                lastDeleteIndex = i;
            } else if (firstWriteIndex == -1) {
                firstWriteIndex = i;
            }
        }

        assertThat(lastDeleteIndex).as("삭제 배치가 존재해야 한다").isGreaterThanOrEqualTo(0);
        assertThat(firstWriteIndex).as("쓰기 배치가 존재해야 한다").isGreaterThanOrEqualTo(0);
        assertThat(lastDeleteIndex).as("모든 삭제 배치는 모든 쓰기 배치보다 앞에 와야 한다")
                .isLessThan(firstWriteIndex);
    }
}
