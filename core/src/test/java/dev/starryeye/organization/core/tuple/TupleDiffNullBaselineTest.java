package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TupleDiff.between} 의 null 폴백.
 *
 * <p>이 분기는 <b>첫 동기화</b>에서 실제로 탄다 — 스냅샷이 아직 없으므로 기준선이 없다.
 * 그런데 지금까지 어떤 테스트도 여기를 지나지 않았다. 폴백이 잘못 놓이면 첫 동기화가
 * NPE 로 죽거나, 더 나쁘게는 <b>전체를 삭제 대상으로 계산</b>한다.
 */
class TupleDiffNullBaselineTest {

    private static final RelationTuple KIM = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple PARK = RelationTuple.directMember("park", "DEV002");

    @Test
    @DisplayName("기준선이 없으면(첫 동기화) 목표 전체가 쓰기 대상이고 삭제는 없다")
    void 기준선이_null이면_전부_쓰기다() {
        // when
        var delta = TupleDiff.between(null, Set.of(KIM, PARK));

        // then — 삭제가 하나라도 나오면 첫 동기화가 있지도 않은 튜플을 지우려 든다
        assertThat(delta.toWrite()).containsExactlyInAnyOrder(KIM, PARK);
        assertThat(delta.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("목표가 없으면 기준선 전체가 삭제 대상이고 쓰기는 없다")
    void 목표가_null이면_전부_삭제다() {
        // when
        var delta = TupleDiff.between(Set.of(KIM), null);

        // then — 이 경우가 바로 삭제 가드가 막아야 하는 모양이다
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).containsExactly(KIM);
    }

    @Test
    @DisplayName("둘 다 없으면 아무것도 하지 않는다")
    void 둘_다_null이면_변경이_없다() {
        // when
        var delta = TupleDiff.between(null, null);

        // then
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).isEmpty();
    }
}
