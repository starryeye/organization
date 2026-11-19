package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.strategy.SkippedEntryObserver.검색;
import dev.starryeye.organization.ldap.strategy.SkippedEntryObserver.사유;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 건너뛴 엔트리 지표(설계 2026-11-19 S6) — 기동 때 여덟 계열, 건수만큼 그 계열만, 열거 값마다 못박은 태그. */
class SkippedEntryMetricsTest {

    private static final Set<String> 여덟 = Set.of(
            "user/missing_id", "user/duplicate_id", "user/over_limit", "user/parent_not_found",
            "group/missing_id", "group/duplicate_id", "group/over_limit",
            "member/unmatched_member");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Map<String, Double> 값들() {
        Map<String, Double> 값들 = new LinkedHashMap<>();
        for (Counter c : registry.find("sync.entries.skipped").counters()) {
            값들.put(c.getId().getTag("search") + "/" + c.getId().getTag("reason"), c.count());
        }
        return 값들;
    }

    @Test
    @DisplayName("기동 때 여덟 계열이 0 으로 있다")
    void 기동_때_여덟_계열이_0_으로_있다() {
        // when
        new SkippedEntryMetrics(registry);

        // then
        assertThat(값들().keySet()).isEqualTo(여덟);
        assertThat(값들().values()).containsOnly(0.0);
    }

    @Test
    @DisplayName("받은 건수만큼 그 계열만 오른다")
    void 받은_건수만큼_그_계열만_오른다() {
        // given
        var metrics = new SkippedEntryMetrics(registry);

        // when
        metrics.skipped(검색.직원, 사유.한도_넘음, 3);

        // then
        assertThat(값들().get("user/over_limit")).isEqualTo(3.0);
        assertThat(값들().entrySet().stream().filter(e -> !e.getKey().equals("user/over_limit")).map(Map.Entry::getValue))
                .hasSize(7).containsOnly(0.0);
    }

    @Test
    @DisplayName("태그는 고정한 영문이다 — 여덟 조합을 서로 다른 건수로 불러 각 태그의 값을 본다")
    void 태그는_고정한_영문이다() {
        // given
        var metrics = new SkippedEntryMetrics(registry);

        // when
        metrics.skipped(검색.직원, 사유.식별_속성_없음, 1);
        metrics.skipped(검색.직원, 사유.아이디_겹침, 2);
        metrics.skipped(검색.직원, 사유.한도_넘음, 3);
        metrics.skipped(검색.직원, 사유.부모_조직_없음, 4);
        metrics.skipped(검색.조직, 사유.식별_속성_없음, 5);
        metrics.skipped(검색.조직, 사유.아이디_겹침, 6);
        metrics.skipped(검색.조직, 사유.한도_넘음, 7);
        metrics.skipped(검색.member값, 사유.읽지_않은_member, 8);

        // then
        assertThat(값들()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "user/missing_id", 1.0, "user/duplicate_id", 2.0, "user/over_limit", 3.0, "user/parent_not_found", 4.0,
                "group/missing_id", 5.0, "group/duplicate_id", 6.0, "group/over_limit", 7.0,
                "member/unmatched_member", 8.0));
    }
}
