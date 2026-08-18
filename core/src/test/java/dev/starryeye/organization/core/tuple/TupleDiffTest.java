package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TupleDiffTest {

    private static RelationTuple 소속(String userId, String groupId) {
        return RelationTuple.directMember(userId, groupId);
    }

    @Test
    @DisplayName("직전 스냅샷에 없던 튜플은 생성 대상으로 분류된다")
    void 신규_튜플은_생성_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactly(소속("lee", "DEV002"));
        assertThat(delta.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("목표에서 사라진 튜플은 삭제 대상으로 분류된다")
    void 사라진_튜플은_삭제_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).containsExactly(소속("lee", "DEV002"));
    }

    @Test
    @DisplayName("양쪽에 모두 있는 튜플은 어느 쪽에도 분류되지 않아 불필요한 쓰기가 발생하지 않는다")
    void 변경이_없으면_빈_델타가_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("직전 스냅샷이 비어 있으면 목표 전체가 생성 대상이 된다")
    void 최초_동기화는_전체가_생성_대상이_된다() {
        // given
        var 직전 = Set.<RelationTuple>of();
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactlyInAnyOrderElementsOf(목표);
        assertThat(delta.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("LDAP이 0건을 반환하면 직전 스냅샷 전체가 삭제 대상이 되어 가드가 판단할 수 있게 한다")
    void 목표가_비면_전체가_삭제_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.<RelationTuple>of();

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).containsExactlyInAnyOrderElementsOf(직전);
    }

    @Test
    @DisplayName("생성과 삭제가 동시에 있는 경우를 한 번에 계산한다")
    void 생성과_삭제를_동시에_계산한다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("park", "DEV001"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactly(소속("park", "DEV001"));
        assertThat(delta.toDelete()).containsExactly(소속("lee", "DEV002"));
    }
}
