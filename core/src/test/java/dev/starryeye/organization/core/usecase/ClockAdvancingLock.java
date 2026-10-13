package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.port.LockLease;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 반납하는 순간 제 시계를 {@link #STEP} 만큼 옮기는 락. 락 안에서 찍은 시각과 반납 뒤에 찍은 시각이 달라진다 — 이력의 시각이 커밋한 때(락 안)에
 * 찍히는지 본다(설계 2026-10-10 §4.4).
 */
final class ClockAdvancingLock extends FakeMutationLock {

    static final Duration STEP = Duration.ofMinutes(1);

    private final SteppingClock clock;

    ClockAdvancingLock(Instant start) {
        this.clock = new SteppingClock(start);
    }

    /** 이 락이 반납할 때마다 가는 시계. */
    Clock clock() {
        return clock;
    }

    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.fromRunnable(() -> clock.advance(STEP)).then(super.release(lease));
    }

    /** 락이 옮길 때만 움직이는 시계. */
    private static final class SteppingClock extends Clock {

        private volatile Instant now;

        SteppingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
