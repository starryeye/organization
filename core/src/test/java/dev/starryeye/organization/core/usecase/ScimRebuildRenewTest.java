package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 재적재는 몇 분을 쥐지만 TTL 은 30초다. 갱신하지 않으면 <b>도중에 리스를 잃고</b>
 * 다른 인스턴스의 쓰기가 반쯤 맞춘 장부 위로 들어온다 (설계 §4.4).
 */
class ScimRebuildRenewTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    /** 쓸 튜플이 있는 조직도. 비어 있으면 재적재가 쓰기를 건너뛰어 느린 쓰기로 긴 재적재를 흉내 낼 수 없다. */
    private static FakeStateRepository 한명짜리_조직도() {
        var state = new FakeStateRepository();
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "김", "kim@example.com", true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        return state;
    }

    private static ScimRebuildUseCase 재적재(FakeTupleWriter writer, FakeSyncRunRepository runs,
                                          FakeMutationLock lock, Duration 갱신주기) {
        return new ScimRebuildUseCase(한명짜리_조직도(), writer, new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                new SyncJobs(runs, lock, 갱신주기, LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SyncRun 끝까지(ScimRebuildUseCase useCase, FakeSyncRunRepository runs) {
        return runs.awaitFinished(useCase.start(ScimRebuildMode.TUPLES).block().runId());
    }

    @Test
    @DisplayName("재적재가 오래 걸리면 리스를 주기적으로 갱신한다")
    void 오래_걸리면_갱신한다() {
        // given — 느린 쓰기로 긴 재적재를 흉내낸다
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(600);
        var runs = new FakeSyncRunRepository(NOW);

        // when
        끝까지(재적재(writer, runs, lock, Duration.ofMillis(100)), runs);

        // then — 100ms 주기로 600ms 를 덮으려면 여러 번 갱신돼야 한다
        assertThat(lock.renewed.get())
                .as("갱신이 없으면 TTL 안에 끝나지 않는 재적재가 리스를 잃는다")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("재적재가 끝나면 갱신도 멈춘다")
    void 끝나면_갱신도_멈춘다() {
        // given — 갱신 주기보다 오래 걸리게 해 하트비트가 최소 한 번은 돌고 나서 끝나게 한다.
        // 성공 횟수(renewed) 로는 못 본다 — 반납 뒤에는 토큰이 안 맞아 매 tick 이 실패하므로
        // 새는 하트비트도 renewed 를 그대로 두어 "안 도는 것"과 구분이 안 된다. 그래서 시도
        // 자체를 세는 renewAttempted 로 본다.
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(150);
        var runs = new FakeSyncRunRepository(NOW);

        // when — 반납이 기록보다 먼저이므로, 끝난 기록을 받은 시점에 하트비트는 이미 멈췄다
        끝까지(재적재(writer, runs, lock, Duration.ofMillis(50)), runs);
        int 끝난직후 = lock.renewAttempted.get();

        // then — 재적재 도중 최소 한 번은 갱신을 시도했어야 한다. 0 이면 이 뒤의 "더 안 늘어난다"
        // 단언이 트리비얼하게 통과해버려 아무것도 증명하지 못한다.
        assertThat(끝난직후)
                .as("재적재가 갱신 주기보다 짧게 끝나면 이 검증 자체가 무의미해진다")
                .isGreaterThan(0);

        // 갱신이 계속 돌면 반납된 락을 갱신하려 들어 로그가 오염된다 — 시도 횟수가 더 늘지 않아야 한다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lock.renewAttempted.get()).isEqualTo(끝난직후));
    }

    @Test
    @DisplayName("재적재 도중 리스를 잃으면 중단하고 FAILED 로 기록한다")
    void 리스를_잃으면_중단하고_FAILED_다() {
        // given — 갱신이 실패하는 순간 이미 리스는 남의 것이다. 재적재는 몇 분짜리라
        // 그 뒤로도 계속 쓰면 남이 쓰고 있는 장부 위에 겹쳐 쓴다.
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(400);
        var runs = new FakeSyncRunRepository(NOW);
        lock.failRenew = true;

        // when
        var run = 끝까지(재적재(writer, runs, lock, Duration.ofMillis(50)), runs);

        // then — 실행 기록이 운영자가 가진 유일한 신호다. SUCCEEDED 로 남기면
        // "mode=tuples 를 한 번 더 돌려야 한다" 와 "할 일 없다" 가 구별되지 않는다.
        assertThat(run.status())
                .as("반쯤 맞춘 장부 위에 남의 쓰기가 들어왔을 수 있는 실행을 초록으로 남기면 안 된다")
                .isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(runs.finished).hasSize(1);
        assertThat(lock.released).as("중단해도 락은 반납을 시도한다").hasValue(1);
    }
}
