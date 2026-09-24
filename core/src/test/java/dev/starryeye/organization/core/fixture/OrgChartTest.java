package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgChartTest {

    @Test
    @Timeout(5)
    @DisplayName("순환이 든 조직도에서 조상을 물으면 끝없이 돌지 않고 경로를 담아 즉시 던진다")
    void 순환이면_조상_질의가_즉시_실패한다() {
        // given — A 가 B 를, B 가 A 를 하위로 갖는다. 순환 시나리오(L16/S16)가 만드는 모양이다
        var chart = new OrgChart(new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A"))))), null);

        // when, then — 가드가 없으면 10분 타임아웃이 되고, 원인이 보이지 않는다
        assertThatThrownBy(() -> chart.조상들("A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("순환")
                .hasMessageContaining("A → B → A");
    }
}
