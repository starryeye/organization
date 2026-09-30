package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스케줄 동기화를 <b>걸지 못해도</b> {@link SyncExecutionGuard} 는 반드시 반납되어야 한다.
 *
 * <p>반납되지 않으면 스케줄러가 영구히 멈추고, 이후 모든 야간 동기화가 "이전 동기화가
 * 아직 진행 중" 경고만 남긴 채 조용히 건너뛴다. 아무도 알아채지 못한다.
 *
 * <p>걸고 나서의 반납은 작업({@code SyncJobs})의 몫이다 — 여기서는 걸기 전 두 가지 실패를 본다.
 */
class SyncSchedulerGuardReleaseTest {

    @Test
    @DisplayName("동기화가 Mono 생성 전에 동기적으로 예외를 던져도 가드는 반납된다")
    void 동기적_예외에도_가드가_반납된다() {
        // given
        var guard = new SyncExecutionGuard();
        // 만료 스냅샷 정리 경로는 이 테스트가 다루는 시나리오와 무관하다.
        var scheduler = new SyncScheduler(new 걸지_못하는_동기화(true), null, guard,
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);

        // when
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();

        // then
        assertThat(guard.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("실행 기록을 열지 못해 걸지 못하면 가드는 반납된다")
    void 걸지_못하면_가드가_반납된다() {
        // given
        var guard = new SyncExecutionGuard();
        var scheduler = new SyncScheduler(new 걸지_못하는_동기화(false), null, guard,
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);

        // when
        scheduler.전체동기화();

        // then
        assertThat(guard.tryAcquire()).isTrue();
    }

    /** start() 가 곧바로 던지거나(동기) 오류 Mono 를 준다. */
    private static final class 걸지_못하는_동기화 extends FullSyncUseCase {

        private final boolean 동기로_던진다;

        걸지_못하는_동기화(boolean 동기로_던진다) {
            super(null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), null, Clock.systemUTC());
            this.동기로_던진다 = 동기로_던진다;
        }

        @Override
        public Mono<SyncRun> start(SyncTrigger trigger, Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
            if (동기로_던진다) {
                throw new IllegalStateException("Mono 구성 전 동기 예외");
            }
            return Mono.error(new IllegalStateException("실행 기록을 열지 못했다"));
        }
    }
}
