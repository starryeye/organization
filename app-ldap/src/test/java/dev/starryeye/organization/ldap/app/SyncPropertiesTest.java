package dev.starryeye.organization.ldap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.Map;

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
    @DisplayName("만료 스냅샷 정리는 하루 창(04~08시) 안에서 매시 시도한다 — 실패하면 표지를 풀어 다음 정각에 다시 돈다(설계 2026-10-27 §9)")
    void 정리는_하루_창_안에서_매시_시도한다() {
        // given, when
        var cron = new SyncProperties().getPurgeCron();

        // then
        assertThat(cron).isEqualTo("0 0 4-8 * * *");
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 4, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 23, 5, 0, 0));
        assertThat(CronExpression.parse(cron).next(LocalDateTime.of(2026, 10, 23, 8, 0, 0)))
                .as("창의 끝 뒤에는 다음 날 창의 처음이다")
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 4, 0, 0));
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

    // 깨뜨릴 변경: UseCaseConfig.deletionGuard 가 maxDeletes 를 넘기지 않음
    @Test
    @DisplayName("sync.deletion-guard.max-deletes 를 주면 가드가 그 값을 쓴다")
    void 가드_상한을_설정하면_가드가_그_값을_쓴다() {
        // given
        var properties = new SyncProperties();
        new Binder(new MapConfigurationPropertySource(Map.of("sync.deletion-guard.max-deletes", "3")))
                .bind("sync", Bindable.ofInstance(properties));
        var guard = new UseCaseConfig().deletionGuard(properties);

        // when, then — 기준 9 는 min-baseline 미만이라 비율은 보지 않는다. 상한만 본다
        assertThat(guard.evaluate(3, 9, "기준 스냅샷").aborted()).isFalse();
        assertThat(guard.evaluate(4, 9, "기준 스냅샷").aborted()).isTrue();
    }

    // 깨뜨릴 변경: 기본값을 0 으로 둠
    @Test
    @DisplayName("가드 상한의 기본값은 5000줄이다(설계 2026-11-13 G1)")
    void 가드_상한의_기본값은_5000줄이다() {
        // given
        var properties = new SyncProperties();
        var guard = new UseCaseConfig().deletionGuard(properties);

        // when, then
        assertThat(properties.getDeletionGuard().getMaxDeletes()).isEqualTo(5000);
        assertThat(guard.evaluate(5000, 200_000, "기준 스냅샷").aborted()).isFalse();
        assertThat(guard.evaluate(5001, 200_000, "기준 스냅샷").aborted()).isTrue();
    }

    // 깨뜨릴 변경: 한쪽 yml 에서 키를 빼거나 다른 값을 적음
    @Test
    @DisplayName("두 yml 의 가드 상한은 속성 기본값과 같다")
    void 두_yml_의_가드_상한은_속성_기본값과_같다() {
        // given
        String 기본값 = String.valueOf(new SyncProperties().getDeletionGuard().getMaxDeletes());

        // when, then
        for (String 파일 : new String[]{"application.yml", "application-test.yml"}) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource(파일));
            assertThat(yaml.getObject().getProperty("sync.deletion-guard.max-deletes"))
                    .as(파일).isEqualTo(기본값);
        }
    }
}
