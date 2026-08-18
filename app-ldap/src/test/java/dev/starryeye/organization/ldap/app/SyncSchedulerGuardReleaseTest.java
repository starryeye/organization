package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link FullSyncUseCase#execute} 가 Mono 를 만들기도 전에 동기적으로 예외를 던지는
 * 극단적인 경우에도 {@link SyncExecutionGuard} 는 반드시 반납되어야 한다.
 *
 * <p>반납되지 않으면 스케줄러가 영구히 멈추고, 이후 모든 야간 동기화가 "이전 동기화가
 * 아직 진행 중" 경고만 남긴 채 조용히 건너뛴다. 아무도 알아채지 못한다.
 *
 * <p>{@code Mono.defer(() -> fullSync.execute(...))} 로 감싸면 supplier 가 던진 동기
 * 예외도 onError 신호로 바뀌어 {@code doFinally} 가 반드시 실행된다. 이 테스트는
 * 그 보장을 고정한다.
 */
class SyncSchedulerGuardReleaseTest {

    @Test
    @DisplayName("동기화가 Mono 생성 전에 동기적으로 예외를 던져도 가드는 반납된다")
    void 동기적_예외에도_가드가_반납된다() {
        // given
        var guard = new SyncExecutionGuard();
        // 만료 스냅샷 정리 경로는 이 테스트가 다루는 시나리오와 무관하다.
        var scheduler = new SyncScheduler(new ThrowingFullSyncUseCase(), null, guard,
                new SyncMetrics(new SimpleMeterRegistry()));

        // when
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();

        // then
        assertThat(guard.tryAcquire()).isTrue();
    }

    /** execute() 가 Mono 를 반환하기도 전에, 호출한 스레드에서 즉시 던진다. */
    private static final class ThrowingFullSyncUseCase extends FullSyncUseCase {

        ThrowingFullSyncUseCase() {
            super(null, null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), Clock.systemUTC());
        }

        @Override
        public Mono<SyncRun> execute(SyncTrigger trigger) {
            throw new IllegalStateException("Mono 구성 전 동기 예외");
        }
    }
}
