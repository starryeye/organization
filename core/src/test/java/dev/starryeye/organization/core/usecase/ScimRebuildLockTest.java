package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재적재가 SCIM 쓰기와 <b>같은</b> 분산 락을 잡는다 (설계 §4.5).
 *
 * <p>전에는 인메모리 {@code MutationGate} 였다. 인스턴스가 둘이면 재적재가 도는 사실 자체를
 * 다른 인스턴스가 몰라 쓰기가 그대로 통과했다 — 막고 있다고 믿지만 안 막혔다.
 */
class ScimRebuildLockTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    private FakeMutationLock lock;
    private FakeTupleWriter writer;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        writer = new FakeTupleWriter();
        useCase = new ScimRebuildUseCase(
                new FakeStateRepository(),
                writer,
                new FakeSnapshotRepository(),
                new FakeSyncRunRepository(NOW),
                lock,
                Duration.ofSeconds(10),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("재적재는 락을 잡고 끝나면 반납한다")
    void 락을_잡고_반납한다() {
        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 쥐고 있으면 재적재가 시작되지 않는다")
    void 락이_없으면_시작하지_않는다() {
        // given — 다른 인스턴스의 쓰기나 재적재가 쥐고 있는 상황
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> useCase.execute(ScimRebuildMode.TUPLES).block())
                .isInstanceOf(LockUnavailableException.class);

        // 던지고도 store 를 지웠다면 최악이다 — 거절은 파괴적 작업이 전혀 일어나지
        // 않았다는 뜻이어야 한다
        assertThat(writer.resetStoreCount).hasValue(0);
    }
}
