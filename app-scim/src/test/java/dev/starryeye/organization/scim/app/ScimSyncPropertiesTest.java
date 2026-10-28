package dev.starryeye.organization.scim.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 1회 작업의 cron 기본값은 하루 창 안에서 매시 시도한다(설계 2026-10-27 §9). 실패·성공 아님이면 표지를 풀어 다음 정각에 다시 돌고, 성공하면 그날의 나머지
 * 시도는 표지(DONE)를 보고 건너뛴다.
 */
class ScimSyncPropertiesTest {

    @Test
    @DisplayName("아카이빙은 하루 창(03~08시) 안에서 매시 시도한다")
    void 아카이빙은_하루_창_안에서_매시_시도한다() {
        // given, when
        var cron = new ScimSyncProperties().getArchiveCron();

        // then
        assertThat(cron).isEqualTo("0 0 3-8 * * *");
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 3, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 4, 0, 0));
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 8, 0, 0)))
                .as("창의 끝 뒤에는 다음 날 창의 처음이다")
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 3, 0, 0));
    }

    @Test
    @DisplayName("만료 스냅샷 정리는 하루 창(04~08시) 안에서 매시 시도한다")
    void 정리는_하루_창_안에서_매시_시도한다() {
        // given, when
        var cron = new ScimSyncProperties().getPurgeCron();

        // then
        assertThat(cron).isEqualTo("0 0 4-8 * * *");
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 4, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 5, 0, 0));
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 8, 0, 0)))
                .as("창의 끝 뒤에는 다음 날 창의 처음이다")
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 4, 0, 0));
    }
}
