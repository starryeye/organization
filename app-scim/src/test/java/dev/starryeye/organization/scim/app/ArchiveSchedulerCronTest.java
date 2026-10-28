package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Scheduled} 의 cron 이 속성 클래스({@link ScimSyncProperties})의 값을 읽는지 본다(설계 2026-10-27 §5). 기본값의 출처가 속성 클래스 하나이고, 설정은
 * 덮어쓸 때만 쓴다. 예전에는 {@code ${sync.archive-cron:0 0 3 * * *}} 자리표시자가 기본값을 어노테이션 안에 따로 들고 있었다.
 */
class ArchiveSchedulerCronTest {

    /** 협력자는 한 번도 불리지 않는다 — 이 시험은 등록된 cron 만 본다. */
    private static class 놀고_있는_아카이빙 extends SnapshotArchiveUseCase {

        놀고_있는_아카이빙() {
            super(null, null, null, null, null, Clock.systemUTC(), null, null);
        }

        @Override
        public Mono<SyncRun> execute() {
            return Mono.empty();
        }
    }

    /** 운영 결선({@code ScimUseCaseConfig#scimSyncProperties})과 같은 모양 — 빈 이름 {@code scimSyncProperties} 를 SpEL 이 참조한다. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties
    static class 스케줄링_설정 {

        @Bean
        ScimSyncProperties scimSyncProperties() {
            return new ScimSyncProperties();
        }

        @Bean
        ArchiveScheduler archiveScheduler() {
            return new ArchiveScheduler(new 놀고_있는_아카이빙(), new FakeSnapshotRepository(), ObservationRegistry.NOOP,
                    new DailyOnce(new FakeDailyJobClaims(), Clock.systemUTC()));
        }
    }

    private final ApplicationContextRunner 러너 = new ApplicationContextRunner()
            .withUserConfiguration(스케줄링_설정.class);

    private static List<String> 등록된_cron(AssertableApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(task -> task.getTask())
                .filter(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .toList();
    }

    @Test
    @DisplayName("설정에 키가 없으면 속성 클래스의 cron 으로 등록된다")
    void 키가_없으면_속성_클래스의_cron_으로_등록된다() {
        // given
        var 기본값 = new ScimSyncProperties();

        // when, then
        러너.run(context -> assertThat(등록된_cron(context))
                .containsExactlyInAnyOrder(기본값.getArchiveCron(), 기본값.getPurgeCron()));
    }

    @Test
    @DisplayName("설정으로 덮으면 그 값으로 등록된다")
    void 설정으로_덮으면_그_값이다() {
        // given
        var 기본값 = new ScimSyncProperties();

        // when, then
        러너.withPropertyValues("sync.archive-cron=0 30 2 * * *")
                .run(context -> assertThat(등록된_cron(context))
                        .containsExactlyInAnyOrder("0 30 2 * * *", 기본값.getPurgeCron()));
    }

    @Test
    @DisplayName("'-' 는 그 작업을 끈다 — 정리 cron 이 등록되지 않는다")
    void 빼기는_끈다() {
        // given
        var 기본값 = new ScimSyncProperties();

        // when, then
        러너.withPropertyValues("sync.purge-cron=-")
                .run(context -> assertThat(등록된_cron(context)).containsExactly(기본값.getArchiveCron()));
    }

    @Test
    @DisplayName("application.yml 에는 cron·기한 키가 없다 — 기본값은 속성 클래스에만 있다")
    void 설정_파일에는_cron_키가_없다() {
        // given
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        // when
        var 키들 = yaml.getObject().stringPropertyNames();

        // then
        assertThat(키들).doesNotContain("sync.archive-cron", "sync.purge-cron", "sync.job-timeout");
    }
}
