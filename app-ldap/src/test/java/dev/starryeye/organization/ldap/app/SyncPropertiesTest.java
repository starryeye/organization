package dev.starryeye.organization.ldap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPropertiesTest {

    @Test
    @DisplayName("기본 주기는 매시 정각이다 — 하루 한 번이면 퇴사 회수가 최악 하루 늦는다(설계 2026-10-20 §8)")
    void 기본_주기는_매시_정각이다() {
        // given, when
        var cron = new SyncProperties().getCron();

        // then
        assertThat(cron).isEqualTo("0 0 * * * *");
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 10, 20, 30)))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 11, 0, 0));
    }

    @Test
    @DisplayName("application.yml 에는 cron·기한 키가 없다 — 기본값은 속성 클래스에만 있어 yml 과 어긋날 수 없다")
    void 설정_파일에는_cron_키가_없다() {
        // given
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        // when
        var 키들 = yaml.getObject().stringPropertyNames();

        // then
        assertThat(키들).doesNotContain("sync.cron", "sync.purge-cron", "sync.job-timeout");
    }
}
