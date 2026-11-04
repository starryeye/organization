package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveSchedulerTest {

    private static final Instant 지금 = Instant.parse("2026-10-29T03:00:00Z");

    /** 아카이빙을 걸 때 받은 Context 의 행위자를 적어 두는 가짜. */
    private static class 행위자를_적는_아카이빙 extends SnapshotArchiveUseCase {

        final AtomicReference<Object> 받은_행위자 = new AtomicReference<>();

        행위자를_적는_아카이빙() {
            super(null, null, null, null, null, Clock.systemUTC(), null, null, Duration.ofMinutes(30));
        }

        @Override
        public Mono<SyncRun> execute() {
            return Mono.deferContextual(context -> {
                받은_행위자.set(context.getOrDefault(Actor.KEY, null));
                return Mono.just(SyncRun.started("run-1", SyncSource.SCIM, SyncTrigger.ARCHIVE, 지금, Actor.ARCHIVE)
                        .finished(SyncOutcome.noChange(), 지금));
            });
        }
    }

    @Test
    @DisplayName("예약 아카이빙의 행위자는 archive 다 — 아카이빙을 거는 구독의 Context 에 싣는다")
    void 예약_아카이빙의_행위자는_archive_다() {
        // given
        var 아카이빙 = new 행위자를_적는_아카이빙();
        var scheduler = new ArchiveScheduler(아카이빙, new FakeSnapshotRepository(), ObservationRegistry.NOOP,
                new DailyOnce(new FakeDailyJobClaims(), Clock.fixed(지금, ZoneOffset.UTC), Duration.ofMinutes(30)));

        // when
        scheduler.스냅샷아카이빙();

        // then
        assertThat(아카이빙.받은_행위자.get()).isEqualTo("system:archive");
    }
}
