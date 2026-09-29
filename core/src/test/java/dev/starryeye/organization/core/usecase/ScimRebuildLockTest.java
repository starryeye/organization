package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 재적재가 SCIM 쓰기와 <b>같은</b> 분산 락을 잡는다 (설계 §4.5).
 *
 * <p>전에는 인메모리 {@code MutationGate} 였다(이제는 삭제됐다). 인스턴스가 둘이면 재적재가
 * 도는 사실 자체를 다른 인스턴스가 몰라 쓰기가 그대로 통과했다 — 막고 있다고 믿지만 안 막혔다.
 */
class ScimRebuildLockTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    private FakeMutationLock lock;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        useCase = new ScimRebuildUseCase(
                new FakeStateRepository(),
                writer,
                new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                lock,
                Duration.ofSeconds(10),
                LockObserver.NOOP,
                new SyncJobs(runs, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("재적재는 락을 잡고 끝나면 반납한다")
    void 락을_잡고_반납한다() {
        // when
        runs.awaitFinished(useCase.start(ScimRebuildMode.TUPLES).block().runId());

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 쥐고 있으면 재적재가 시작되지 않는다 — 기록도 열지 않는다")
    void 락이_없으면_시작하지_않는다() {
        // given — 다른 인스턴스의 쓰기나 재적재가 쥐고 있는 상황
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> useCase.start(ScimRebuildMode.TUPLES).block())
                .isInstanceOf(LockUnavailableException.class);

        // 거절은 장부에 아무 일도 일어나지 않았다는 뜻이어야 한다
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("요청이 곧바로 끊겨도 재적재는 끝까지 가고 락을 반납한다 — 앞단 프록시가 연결을 끊은 경우")
    void 요청이_곧바로_끊겨도_끝까지_가고_반납한다() {
        // when — 요청이 붙자마자 끊긴다
        useCase.start(ScimRebuildMode.TUPLES).subscribe().dispose();

        // then — 전에는 여기서 재적재가 취소돼 장부가 반쯤 빈 채로, 기록은 RUNNING 으로 남았다(점검 C3)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.finished).hasSize(1));
        assertThat(runs.finished.get(0).status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(lock.released).hasValue(1);
    }
}
