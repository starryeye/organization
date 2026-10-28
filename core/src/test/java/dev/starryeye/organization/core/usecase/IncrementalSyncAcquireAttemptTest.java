package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 쓰기의 락 획득 시도 한도와 조건부 반납(설계 2026-10-27 §6). 시도마다 {@code min(남은 획득 한도, 1초)}(하한 200ms)로 끊고, 끊긴 시도는 줄 서기 한 번에 만든
 * 같은 토큰으로 곧바로 다시 시도한다. 결과를 모르는 시도(끊김·그 밖의 오류)를 남긴 채 포기하면 그 토큰으로 반납을 한 번 보낸다 — 서버에서 성공한 획득이 TTL(30초)
 * 동안 모든 쓰기를 막지 않게 한다. 획득 한도는 3초(운영 기본값)이고 시간은 가상 시간이다.
 */
class IncrementalSyncAcquireAttemptTest {

    private static final Duration 획득_한도 = Duration.ofSeconds(3);

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private VirtualTimeScheduler 시간;

    @BeforeEach
    void 준비한다() {
        시간 = VirtualTimeScheduler.getOrSet();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        state.users.put("kim", 직원("kim", true));
        state.users.put("lee", 직원("lee", true));
    }

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    private IncrementalSyncUseCase 유스케이스() {
        return new IncrementalSyncUseCase(state, writer, checker, lock, 획득_한도,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("매달린 시도는 1초에 끊고 같은 토큰으로 다시 시도해 잡는다 — 서버에서 성공한 획득을 그대로 다시 얻는다")
    void 매달린_시도는_끊고_같은_토큰으로_다시_시도해_잡는다() {
        // given — 첫 시도는 락을 잡았는데 응답을 잃는다
        lock.응답을_잃는_시도.set(1);
        AtomicReference<IncrementalSyncResult> 결과 = new AtomicReference<>();

        // when
        유스케이스().upsertUser(직원("kim", false)).subscribe(결과::set);
        시간.advanceTimeBy(Duration.ofMillis(999));
        IncrementalSyncResult 끊기기_전 = 결과.get();
        시간.advanceTimeBy(Duration.ofMillis(1));

        // then — 1초에 끊기고 곧바로 다시 시도해 잡는다
        assertThat(끊기기_전).as("시도 한도 1초 전에는 아직 기다린다").isNull();
        assertThat(결과.get()).isNotNull();
        assertThat(결과.get().fullyApplied()).isTrue();
        assertThat(lock.시도한_토큰).hasSize(2);
        assertThat(lock.시도한_토큰.get(1)).as("다시 시도도 같은 토큰이다").isEqualTo(lock.시도한_토큰.get(0));
        assertThat(lock.시도_한도.get(0)).as("min(남은 3초, 1초)").isEqualTo(Duration.ofSeconds(1));
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    @DisplayName("한도까지 결과를 모르는 시도만 이어지면 503 이고, 그 토큰으로 반납을 한 번 보내 서버에서 잡힌 락을 푼다")
    void 한도까지_결과를_모르면_503이고_같은_토큰으로_반납을_한_번_보낸다() {
        // given — 모든 시도가 락을 잡고 응답을 잃는다
        lock.응답을_잃는_시도.set(Integer.MAX_VALUE);
        AtomicReference<Throwable> 실패 = new AtomicReference<>();

        // when
        유스케이스().upsertUser(직원("kim", false)).subscribe(결과 -> { }, 실패::set);
        시간.advanceTimeBy(획득_한도);

        // then
        assertThat(실패.get()).isInstanceOf(LockUnavailableException.class);
        assertThat(lock.시도한_토큰).hasSize(3).containsOnly(lock.시도한_토큰.get(0));
        assertThat(lock.반납한_토큰).containsExactly(lock.시도한_토큰.get(0));
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    @DisplayName("조건 실패만 있었으면 반납하지 않는다 — 남이 쥔 락이고, 우리 시도는 서버에서 잡지 못했다")
    void 조건_실패만_있었으면_반납하지_않는다() {
        // given — 다른 쓰기가 쥐고 있다
        lock.acquireOnce(MutationLock.LockPurpose.WRITE).block();
        AtomicReference<Throwable> 실패 = new AtomicReference<>();

        // when
        유스케이스().upsertUser(직원("kim", false)).subscribe(결과 -> { }, 실패::set);
        시간.advanceTimeBy(획득_한도);

        // then
        assertThat(실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(lock.반납한_토큰).isEmpty();
        assertThat(lock.isHeld()).as("남의 락은 그대로다").isTrue();
    }

    @Test
    @DisplayName("그 밖의 오류로 멈추면 곧바로 503 이고, 응답을 잃었을 수 있으므로 결과를 모르는 시도로 보고 그 토큰으로 반납한다")
    void 그_밖의_오류로_멈추면_결과를_모르는_시도로_보고_반납한다() {
        // given — SDK 재시도를 다 쓴 스로틀
        IllegalStateException 스로틀 = new IllegalStateException("스로틀");
        lock.acquireError = 스로틀;
        AtomicReference<Throwable> 실패 = new AtomicReference<>();

        // when
        유스케이스().upsertUser(직원("kim", false)).subscribe(결과 -> { }, 실패::set);
        시간.advanceTimeBy(Duration.ZERO);

        // then
        assertThat(실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e).hasCause(스로틀));
        assertThat(lock.시도한_토큰).hasSize(1);
        assertThat(lock.반납한_토큰).containsExactly(lock.시도한_토큰.get(0));
    }

    @Test
    @DisplayName("스로틀로 시도가 이어 끊겨도 획득 한도(3초) 안에 503 이고, 토큰 반납으로 락이 풀려 다음 쓰기는 TTL(30초)을 기다리지 않는다")
    void 스로틀이_이어져도_획득_한도_안에_503이고_다음_쓰기는_TTL을_기다리지_않는다() {
        // given — 시도 셋이 서버에서는 락을 잡고 응답을 잃는다(0·1·2초에 시작해 1초씩)
        lock.응답을_잃는_시도.set(3);
        IncrementalSyncUseCase 유스케이스 = 유스케이스();
        AtomicReference<Throwable> 첫_실패 = new AtomicReference<>();
        AtomicReference<IncrementalSyncResult> 둘째 = new AtomicReference<>();

        // when — 첫 쓰기
        유스케이스.upsertUser(직원("kim", false)).subscribe(결과 -> { }, 첫_실패::set);
        시간.advanceTimeBy(Duration.ofMillis(2999));
        Throwable 한도_전 = 첫_실패.get();
        시간.advanceTimeBy(Duration.ofMillis(1));

        // then — 3초에 503 이고, 락은 토큰 반납으로 풀렸다
        assertThat(한도_전).as("한도 전에는 아직 다시 시도한다").isNull();
        assertThat(첫_실패.get()).isInstanceOf(LockUnavailableException.class);
        assertThat(lock.isHeld()).as("토큰 반납으로 풀렸다 — TTL 을 기다리지 않는다").isFalse();

        // when — 스로틀이 걷힌 뒤 둘째 쓰기
        lock.응답을_잃는_시도.set(0);
        유스케이스.upsertUser(직원("lee", false)).subscribe(둘째::set);
        시간.advanceTimeBy(Duration.ofSeconds(1));

        // then
        assertThat(둘째.get()).isNotNull();
        assertThat(둘째.get().fullyApplied()).isTrue();
    }
}
