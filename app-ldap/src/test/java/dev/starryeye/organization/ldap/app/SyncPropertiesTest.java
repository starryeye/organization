package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    @Test
    @DisplayName("재활성 가드의 기본값은 켬·30%·기준 10명·상한 100명이다(설계 2026-11-24 R3)")
    void 재활성_가드의_기본값은_넷이다() {
        // given, when
        var config = new SyncProperties().getReactivationGuard();

        // then
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getThresholdRatio()).isEqualTo(0.3);
        assertThat(config.getMinBaseline()).isEqualTo(10);
        assertThat(config.getMaxReactivations()).isEqualTo(100);
    }

    private static SyncProperties 재활성_가드_설정(Map<String, String> 값) {
        var properties = new SyncProperties();
        new Binder(new MapConfigurationPropertySource(값)).bind("sync", Bindable.ofInstance(properties));
        return properties;
    }

    // 깨뜨릴 변경: UseCaseConfig.reactivationGuard 가 상한·켬·기준 수·임계치 중 하나를 넘기지 않거나 기본값으로 바꿔 넘김
    @Test
    @DisplayName("sync.reactivation-guard.* 네 값이 모두 판정까지 닿는다 — 상한·임계치·기준 수는 경계에서, 끄면 보지 않는다")
    void 재활성_가드_설정을_바인딩하면_가드가_그_값을_쓴다() {
        // given — 임계치 0.5, 기준 수 20. 상한은 두 가지로 본다
        var 상한_7 = new UseCaseConfig().reactivationGuard(재활성_가드_설정(Map.of(
                "sync.reactivation-guard.threshold-ratio", "0.5",
                "sync.reactivation-guard.min-baseline", "20",
                "sync.reactivation-guard.max-reactivations", "7")));
        var 상한_100 = new UseCaseConfig().reactivationGuard(재활성_가드_설정(Map.of(
                "sync.reactivation-guard.threshold-ratio", "0.5",
                "sync.reactivation-guard.min-baseline", "20",
                "sync.reactivation-guard.max-reactivations", "100")));

        // when, then — 상한: 기준 8명은 기준 수(20) 미만이라 비율은 안 보고 상한 7 만 본다(기본 상한 100 이면 8 이 안 걸려 어긋난다)
        assertThat(상한_7.check(이전(8), 그림(8, 7)).held()).isFalse();
        assertThat(상한_7.check(이전(8), 그림(8, 8)).held()).isTrue();

        // then — 임계치: 20명 중 10명은 정확히 50% 라 통과한다(기본 임계치 0.3 이면 걸려 어긋난다). 11명은 걸린다
        assertThat(상한_100.check(이전(20), 그림(20, 10)).held()).isFalse();
        assertThat(상한_100.check(이전(20), 그림(20, 11)).held()).isTrue();

        // then — 기준 수: 19명 전원이 재활성이어도 기준 19 는 20 미만이라 비율을 안 본다(기본 기준 수 10 이면 걸려 어긋난다)
        assertThat(상한_100.check(이전(19), 그림(19, 19)).held()).isFalse();

        // then — 켬: 끄면 같은 그림도 보지 않는다(기본 켬이면 걸려 어긋난다)
        var 끔 = new UseCaseConfig().reactivationGuard(재활성_가드_설정(Map.of(
                "sync.reactivation-guard.enabled", "false",
                "sync.reactivation-guard.max-reactivations", "7")));
        assertThat(끔.check(이전(8), 그림(8, 8)).held()).isFalse();
    }

    private static Set<RelationTuple> 이전(int 기준) {
        Set<RelationTuple> i = new HashSet<>();
        for (int n = 1; n <= 기준; n++) {
            i.add(RelationTuple.directMember("E" + n, "G"));
        }
        return i;
    }

    /** E1..E{기준} 가운데 앞 {활성}명은 활성인 그림. */
    private static DirectorySnapshot 그림(int 기준, int 활성) {
        Map<String, DirectoryUser> users = new HashMap<>();
        for (int n = 1; n <= 기준; n++) {
            users.put("E" + n, new DirectoryUser("E" + n, "dn" + n, "u" + n, "이름" + n, null, n <= 활성));
        }
        return new DirectorySnapshot(users, Map.of("G", new DirectoryGroup("G", "g", "조직", Set.of())), Set.of());
    }

    @Test
    @DisplayName("application.yml 의 재활성 가드 값은 속성 기본값과 같다")
    void yml_의_재활성_가드_값은_속성_기본값과_같다() {
        // given
        var 기본 = new SyncProperties().getReactivationGuard();
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var 값 = yaml.getObject();

        // when, then
        assertThat(값.getProperty("sync.reactivation-guard.enabled")).isEqualTo(String.valueOf(기본.isEnabled()));
        assertThat(값.getProperty("sync.reactivation-guard.threshold-ratio")).isEqualTo(String.valueOf(기본.getThresholdRatio()));
        assertThat(값.getProperty("sync.reactivation-guard.min-baseline")).isEqualTo(String.valueOf(기본.getMinBaseline()));
        assertThat(값.getProperty("sync.reactivation-guard.max-reactivations")).isEqualTo(String.valueOf(기본.getMaxReactivations()));
    }
}
