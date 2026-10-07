package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * 서버 안 줄과 백오프를 붙인 결선(설계 2026-10-07 §3). 줄·백오프 각각의 규칙은 {@link LocalWriteQueueTest}·{@link AcquireBackoffTest} 가 본다 —
 * 여기서는 차례가 반납 뒤에(실패해도, 획득이 동기로 던져도) 결과를 내기 전에 넘어가는지, 줄에서 한도를 넘기면 DynamoDB 를 부르지 않고 503 인지,
 * 한도를 줄에 선 순간부터 재는지, 재시도가 같은 Mono 를 다시 구독하는지 본다.
 */
class IncrementalSyncLocalQueueTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private 세는_락 lock;
    private 기록하는_관찰자 관찰자;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new 세는_락();
        관찰자 = new 기록하는_관찰자();
        // kim 을 비활성화하면 지울 튜플이 있어 OpenFGA 쓰기(writer.delay)를 탄다. lee 는 소속이 없다
        state.users.put("kim", 직원("kim", true));
        state.users.put("lee", 직원("lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
    }

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    /** acquire 를 부른 수(Mono 를 만든 수 — 토큰이 정해지는 때)와 구독한 수(DynamoDB 에 보낸 수)를 따로 센다. */
    private static final class 세는_락 extends FakeMutationLock {
        final AtomicInteger 만든_수 = new AtomicInteger();
        final AtomicInteger 보낸_수 = new AtomicInteger();

        @Override
        public Mono<LockLease> acquire(LockPurpose purpose) {
            만든_수.incrementAndGet();
            Mono<LockLease> 실제 = super.acquire(purpose);
            return Mono.defer(() -> {
                보낸_수.incrementAndGet();
                return 실제;
            });
        }
    }

    /** 처음 보낸 획득만 되고, 그다음부터는 늘 다른 SCIM 쓰기가 쥐고 있어서 실패한다 — 다른 서버가 쥐고 놓지 않는 상황이다. */
    private static final class 첫_획득만_되는_락 extends FakeMutationLock {
        private final AtomicInteger 보낸_수 = new AtomicInteger();

        @Override
        public Mono<LockLease> acquire(LockPurpose purpose) {
            Mono<LockLease> 실제 = super.acquire(purpose);
            return Mono.defer(() -> 보낸_수.incrementAndGet() == 1
                    ? 실제
                    : Mono.error(LockUnavailableException.잡혀_있다(LockPurpose.WRITE)));
        }
    }

    /** 첫 호출에서만 Mono 를 돌려주지 않고 동기로 던진다 — 포트 계약 밖의 구현이다. 그다음은 {@link FakeMutationLock} 처럼 동작한다. */
    private static final class 처음_한_번_던지는_락 extends FakeMutationLock {
        private final AtomicBoolean 던졌다 = new AtomicBoolean();

        @Override
        public Mono<LockLease> acquire(LockPurpose purpose) {
            if (던졌다.compareAndSet(false, true)) {
                throw new IllegalStateException("획득이 동기로 던졌다(테스트)");
            }
            return super.acquire(purpose);
        }
    }

    private static final class 기록하는_관찰자 implements LockObserver {
        final List<Boolean> 경합 = new ArrayList<>();
        final List<Duration> 대기 = new ArrayList<>();

        @Override
        public void acquireFinished(Duration waited, boolean contended) {
            대기.add(waited);
            경합.add(contended);
        }

        @Override
        public void leaseLost(String reason) {
        }
    }

    private IncrementalSyncUseCase 유스케이스(Duration 한도) {
        return 유스케이스(lock, 한도);
    }

    private IncrementalSyncUseCase 유스케이스(MutationLock 쓸_락, Duration 한도) {
        return new IncrementalSyncUseCase(state, writer, checker, 쓸_락, 한도,
                IncrementalSyncUseCase.DriftObserver.NOOP, 관찰자);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("같은 서버의 쓰기 둘이 겹치면 뒤 요청은 줄에서 기다렸다가, 앞 요청이 반납한 뒤 DynamoDB 시도 한 번으로 잡는다")
    void 뒤_요청은_줄에서_기다린다() {
        // given — 앞 요청(kim 비활성화)은 OpenFGA 쓰기에 1초 걸린다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofSeconds(1);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(3));
        AtomicReference<IncrementalSyncResult> 앞 = new AtomicReference<>();
        AtomicReference<IncrementalSyncResult> 뒤 = new AtomicReference<>();

        // when
        유스케이스.upsertUser(직원("kim", false)).subscribe(앞::set);
        유스케이스.upsertUser(직원("lee", false)).subscribe(뒤::set);
        시간.advanceTimeBy(Duration.ofMillis(999));

        // then — 뒤 요청은 줄에서 기다린다. DynamoDB 에는 앞 요청 하나만 갔다
        assertThat(lock.보낸_수).hasValue(1);
        assertThat(뒤.get()).isNull();

        // when
        시간.advanceTimeBy(Duration.ofSeconds(2));

        // then
        assertThat(앞.get()).isNotNull();
        assertThat(뒤.get()).isNotNull();
        assertThat(lock.보낸_수).as("뒤 요청은 비워진 락을 한 번에 잡는다").hasValue(2);
        assertThat(관찰자.경합).as("뒤 요청은 줄에서 밀렸다").containsExactly(false, true);
    }

    @Test
    @DisplayName("줄에서 한도를 넘기면 DynamoDB 를 부르지 않고 503(2초)이며 경합으로 센다")
    void 줄에서_한도를_넘기면_503이다() {
        // given — 앞 요청이 OpenFGA 쓰기에 5초 걸린다. 한도는 1초다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofSeconds(5);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(1));
        AtomicReference<Throwable> 뒤_실패 = new AtomicReference<>();
        유스케이스.upsertUser(직원("kim", false)).subscribe();

        // when
        유스케이스.upsertUser(직원("lee", false)).subscribe(결과 -> { }, 뒤_실패::set);
        시간.advanceTimeBy(Duration.ofSeconds(1));

        // then
        assertThat(뒤_실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(lock.보낸_수).as("줄에서 빠진 요청은 DynamoDB 를 부르지 않았다").hasValue(1);
        assertThat(관찰자.경합).containsExactly(false, true);
        시간.advanceTimeBy(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("앞 요청이 락 안에서 실패해도 차례를 넘긴다 — 뒤 요청은 줄에서 밀리지 않는다")
    void 락_안에서_실패해도_넘긴다() {
        // given
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(3));

        // when
        Throwable 앞_실패 = catchThrowable(() -> 유스케이스.changeUser("kim", u -> {
            throw new IllegalArgumentException("계산 실패(테스트)");
        }).block(Duration.ofSeconds(5)));
        IncrementalSyncResult 뒤 = 유스케이스.upsertUser(직원("lee", false)).block(Duration.ofSeconds(5));

        // then
        assertThat(앞_실패).isInstanceOf(IllegalArgumentException.class);
        assertThat(뒤).isNotNull();
        assertThat(lock.isHeld()).isFalse();
        assertThat(관찰자.경합).as("앞 요청이 차례를 넘겨 뒤 요청은 바로 받았다").containsExactly(false, false);
    }

    @Test
    @DisplayName("락 획득이 실패해도 차례를 넘긴다 — 한도 0 인 뒤 요청이 줄에서 묶이지 않는다")
    void 획득이_실패해도_넘긴다() {
        // given
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ZERO);
        lock.failAcquire = true;

        // when
        Throwable 앞_실패 = catchThrowable(() -> 유스케이스.upsertUser(직원("kim", false)).block(Duration.ofSeconds(5)));
        lock.failAcquire = false;
        IncrementalSyncResult 뒤 = 유스케이스.upsertUser(직원("lee", false)).block(Duration.ofSeconds(5));

        // then
        assertThat(앞_실패).isInstanceOf(LockUnavailableException.class);
        assertThat(뒤).isNotNull();
    }

    @Test
    @DisplayName("재시도는 acquire 가 만든 같은 Mono 를 다시 구독한다 — 실제 락에서는 같은 토큰이다(점검 S14)")
    void 재시도는_같은_Mono_다() {
        // given — 다른 SCIM 쓰기가 쥐고 있다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        LockLease 남의_리스 = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.만든_수.set(0);
        lock.보낸_수.set(0);
        AtomicReference<IncrementalSyncResult> 결과 = new AtomicReference<>();

        // when — 첫 시도가 막힌 뒤 남이 반납한다
        유스케이스(Duration.ofSeconds(3)).upsertUser(직원("lee", false)).subscribe(결과::set);
        lock.release(남의_리스).block();
        시간.advanceTimeBy(Duration.ofMillis(100));

        // then
        assertThat(결과.get()).isNotNull();
        assertThat(lock.만든_수).as("acquire 는 한 번 불렀다 — 토큰은 그때 정해진다").hasValue(1);
        assertThat(lock.보낸_수).as("처음 한 번 + 다시 구독 한 번").hasValue(2);
    }

    @Test
    @DisplayName("락 획득이 Mono 를 돌려주지 않고 동기로 던져도 503 으로 감싸고 차례를 넘긴다 — 뒤 요청은 줄에서 밀리지 않는다")
    void 획득이_동기로_던져도_넘긴다() {
        // given — 첫 획득만 동기로 던지는 락. 포트 계약은 Mono 지만 던지면 차례가 쥔 채로 남아 줄이 멈춘다
        MutationLock 던지는_락 = new 처음_한_번_던지는_락();
        IncrementalSyncUseCase 유스케이스 = 유스케이스(던지는_락, Duration.ofSeconds(3));
        AtomicReference<IncrementalSyncResult> 뒤 = new AtomicReference<>();

        // when
        Throwable 앞_실패 = catchThrowable(() -> 유스케이스.upsertUser(직원("kim", false)).block(Duration.ofSeconds(5)));
        Throwable 뒤_실패 = catchThrowable(() -> 뒤.set(유스케이스.upsertUser(직원("lee", false)).block(Duration.ofSeconds(5))));

        // then
        assertSoftly(soft -> {
            soft.assertThat(앞_실패).as("던진 예외를 감싸 503 으로 낸다")
                    .isInstanceOfSatisfying(LockUnavailableException.class,
                            e -> soft.assertThat(e.getCause()).isInstanceOf(IllegalStateException.class));
            soft.assertThat(뒤_실패).as("뒤 요청은 줄에서 한도를 넘기지 않는다").isNull();
            soft.assertThat(뒤.get()).isNotNull();
            soft.assertThat(관찰자.경합).as("획득 실패도 한 번 기록하고, 뒤 요청은 줄에서 밀리지 않았다").containsExactly(false, false);
        });
    }

    @Test
    @DisplayName("차례는 결과를 내기 전에 넘긴다 — 결과를 받은 자리에서 보낸 다음 요청이 줄에서 밀리지 않는다")
    void 결과를_내기_전에_차례를_넘긴다() {
        // given — 앞 요청이 OpenFGA 쓰기에 1초 걸린다. 동기로 끝나면 결과를 싱크가 쥐었다 구독이 끝난 뒤에 주므로 차례가 먼저 넘어가 있어 순서를 가르지 못한다 — 끝나는 순간에 닿게 한다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofSeconds(1);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(3));
        AtomicReference<IncrementalSyncResult> 다음 = new AtomicReference<>();

        // when — 앞 요청의 결과 콜백 안에서 IdP 가 다음 요청을 보낸다
        유스케이스.upsertUser(직원("kim", false))
                .subscribe(앞_결과 -> 유스케이스.upsertUser(직원("lee", false)).subscribe(다음::set));
        시간.advanceTimeBy(Duration.ofSeconds(2));

        // then
        assertThat(다음.get()).isNotNull();
        assertThat(관찰자.경합).as("결과를 내는 순간 차례는 이미 넘어가 있다 — 다음 요청은 바로 받는다").containsExactly(false, false);
    }

    @Test
    @DisplayName("남은 한도는 줄에 선 순간부터 잰다 — 줄에서 2.5초를 쓰면 DynamoDB 시도는 남은 0.5초만 하고 503(2초)이다")
    void 남은_한도는_줄에_선_순간부터_잰다() {
        // given — 앞 요청이 OpenFGA 쓰기에 2.5초 걸린다. 한도는 3초다. 락은 첫 획득(앞 요청) 뒤로 늘 다른 쪽이 쥐고 있다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofMillis(2500);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(new 첫_획득만_되는_락(), Duration.ofSeconds(3));
        AtomicReference<IncrementalSyncResult> 앞 = new AtomicReference<>();
        AtomicReference<Throwable> 뒤_실패 = new AtomicReference<>();
        유스케이스.upsertUser(직원("kim", false)).subscribe(앞::set);

        // when — 뒤 요청은 t=0 에 줄을 서서 t=2.5초에 차례를 받는다
        유스케이스.upsertUser(직원("lee", false)).subscribe(결과 -> { }, 뒤_실패::set);
        시간.advanceTimeBy(Duration.ofMillis(3200));

        // then — 한도를 줄 진입부터 재므로 503 은 t≈3.0초에 온다. 남은 한도가 아니라 한도 전체(3초)를 쓰면 t≈5.5초다.
        // 다음 시도 전에 걸린 시간 + 이번 대기(최대 100ms)가 한도에 닿으면 멈추므로 3초보다 최대 0.1초 일찍 끝난다
        assertThat(앞.get()).isNotNull();
        assertThat(뒤_실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(관찰자.대기).hasSize(2);
        assertThat(관찰자.대기.get(1)).as("줄 대기 2.5초가 든 시간").isBetween(Duration.ofMillis(2900), Duration.ofMillis(3200));
    }
}
