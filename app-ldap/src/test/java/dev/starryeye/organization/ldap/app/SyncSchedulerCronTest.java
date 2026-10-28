package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Scheduled} 의 cron 이 속성 클래스({@link SyncProperties})의 값을 읽는지 본다(설계 2026-10-27 §5). 기본값의 출처가 속성 클래스 하나이고, 설정은 덮어쓸 때만
 * 쓴다. 예전에는 {@code ${sync.cron}} 자리표시자가 yml 의 값을 읽어, 코드 기본값과 yml 이 따로 놀 수 있었다.
 */
class SyncSchedulerCronTest {

    /** 협력자는 한 번도 불리지 않는다 — 이 시험은 등록된 cron 만 본다. */
    private static class 놀고_있는_동기화 extends FullSyncUseCase {

        놀고_있는_동기화() {
            super(null, null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), null, Clock.systemUTC());
        }

        @Override
        public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
            return Mono.empty();
        }
    }

    /** 운영 결선({@code UseCaseConfig#syncProperties})과 같은 모양 — 빈 이름 {@code syncProperties} 를 SpEL 이 참조한다. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties
    static class 스케줄링_설정 {

        @Bean
        SyncProperties syncProperties() {
            return new SyncProperties();
        }

        @Bean
        SyncScheduler syncScheduler() {
            return new SyncScheduler(new 놀고_있는_동기화(), new FakeSnapshotRepository(),
                    new DailyOnce(new FakeDailyJobClaims(), Clock.systemUTC(), Duration.ofMinutes(30)),
                    new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);
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
        var 기본값 = new SyncProperties();

        // when, then
        러너.run(context -> assertThat(등록된_cron(context))
                .containsExactlyInAnyOrder(기본값.getCron(), 기본값.getPurgeCron()));
    }

    @Test
    @DisplayName("설정으로 덮으면 그 값으로 등록된다")
    void 설정으로_덮으면_그_값이다() {
        // given
        var 기본값 = new SyncProperties();

        // when, then
        러너.withPropertyValues("sync.cron=0 30 * * * *")
                .run(context -> assertThat(등록된_cron(context))
                        .containsExactlyInAnyOrder("0 30 * * * *", 기본값.getPurgeCron()));
    }

    @Test
    @DisplayName("'-' 는 그 작업을 끈다 — 정리 cron 이 등록되지 않는다")
    void 빼기는_끈다() {
        // given
        var 기본값 = new SyncProperties();

        // when, then
        러너.withPropertyValues("sync.purge-cron=-")
                .run(context -> assertThat(등록된_cron(context)).containsExactly(기본값.getCron()));
    }
}
