package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SyncSchedulerTest {

    private static final Clock 오늘 = Clock.fixed(Instant.parse("2026-09-30T19:00:00Z"), ZoneOffset.UTC);

    /** start() 가 정해 둔 Mono 를 준다. 동기 예외 테스트가 익명 하위 클래스로 덮으므로 final 이 아니다. */
    private static class 정해둔_동기화 extends FullSyncUseCase {

        private final Mono<SyncRun> 결과;
        final AtomicInteger 건_횟수 = new AtomicInteger();

        정해둔_동기화(Mono<SyncRun> 결과) {
            super(null, null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), null, Clock.systemUTC());
            this.결과 = 결과;
        }

        @Override
        public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
            건_횟수.incrementAndGet();
            return 결과;
        }
    }

    private static SyncScheduler 스케줄러(FullSyncUseCase fullSync, FakeSnapshotRepository snapshots,
                                     FakeDailyJobClaims claims) {
        return new SyncScheduler(fullSync, snapshots, new DailyOnce(claims, 오늘),
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);
    }

    @Test
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 정기 동기화는 조용히 건너뛴다 — 같은 초에 도는 두 대")
    void 락을_못_잡으면_조용히_건너뛴다() {
        // given
        var 동기화 = new 정해둔_동기화(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));
        var scheduler = 스케줄러(동기화, new FakeSnapshotRepository(), new FakeDailyJobClaims());

        // when, then
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();
        assertThat(동기화.건_횟수).hasValue(1);
    }

    @Test
    @DisplayName("동기화가 Mono 생성 전에 동기적으로 예외를 던져도 스케줄러 밖으로 새지 않는다")
    void 동기_예외가_새지_않는다() {
        // given
        var 던지는_동기화 = new 정해둔_동기화(null) {
            @Override
            public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
                throw new IllegalStateException("Mono 구성 전 동기 예외");
            }
        };
        var scheduler = 스케줄러(던지는_동기화, new FakeSnapshotRepository(), new FakeDailyJobClaims());

        // when, then
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("만료 스냅샷 정리는 하루 1회 표지를 잡은 인스턴스만 돈다")
    void 정리는_하루_한_번이다() {
        // given — 표지 저장소와 스냅샷 저장소를 함께 쓰는 두 인스턴스
        var claims = new FakeDailyJobClaims();
        var snapshots = new FakeSnapshotRepository();
        var 가 = 스케줄러(new 정해둔_동기화(Mono.empty()), snapshots, claims);
        var 나 = 스케줄러(new 정해둔_동기화(Mono.empty()), snapshots, claims);

        // when
        가.만료스냅샷정리();
        나.만료스냅샷정리();

        // then
        assertThat(snapshots.purgeCalls).hasValue(1);
    }
}
