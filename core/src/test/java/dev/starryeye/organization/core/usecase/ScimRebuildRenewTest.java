package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 재적재는 몇 분을 쥐지만 TTL 은 30초다. 갱신하지 않으면 <b>도중에 리스를 잃고</b>
 * 다른 인스턴스의 쓰기가 반쯤 재적재된 OpenFGA 위로 들어온다 (설계 §4.4).
 */
class ScimRebuildRenewTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    @Test
    @DisplayName("재적재가 오래 걸리면 리스를 주기적으로 갱신한다")
    void 오래_걸리면_갱신한다() {
        // given — 느린 쓰기로 긴 재적재를 흉내낸다
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(600);
        var useCase = new ScimRebuildUseCase(
                new FakeStateRepository(), writer,
                new FakeSnapshotRepository(), new FakeSyncRunRepository(NOW),
                lock, Duration.ofMillis(100),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then — 100ms 주기로 600ms 를 덮으려면 여러 번 갱신돼야 한다
        assertThat(lock.renewed.get())
                .as("갱신이 없으면 TTL 안에 끝나지 않는 재적재가 리스를 잃는다")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("재적재가 끝나면 갱신도 멈춘다")
    void 끝나면_갱신도_멈춘다() {
        // given
        var lock = new FakeMutationLock();
        var useCase = new ScimRebuildUseCase(
                new FakeStateRepository(), new FakeTupleWriter(),
                new FakeSnapshotRepository(), new FakeSyncRunRepository(NOW),
                lock, Duration.ofMillis(50),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();
        int 끝난직후 = lock.renewed.get();

        // then — 갱신이 계속 돌면 반납된 락을 갱신하려 들어 로그가 오염된다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lock.renewed.get()).isEqualTo(끝난직후));
    }
}
