package dev.starryeye.organization.core.usecase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 서버 안의 SCIM 쓰기 줄(설계 2026-10-07 §3.1). 차례는 온 순서대로, 끝난 요청이 넘기면 바로 다음 요청에 간다. */
class LocalWriteQueueTest {

    private final LocalWriteQueue 줄 = new LocalWriteQueue();

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    /** 줄에 선 요청 하나 — 차례를 받으면 이름을 남기고 차례를 담는다. 실패하면 실패를 담는다. */
    private static final class 대기자 {
        final AtomicReference<LocalWriteQueue.Turn> 차례 = new AtomicReference<>();
        final AtomicReference<Throwable> 실패 = new AtomicReference<>();
        Disposable 구독;

        static 대기자 세운다(LocalWriteQueue 줄, Duration 한도, List<String> 받은_순서, String 이름) {
            대기자 나 = new 대기자();
            나.구독 = 줄.줄을_선다(한도).subscribe(차례 -> {
                받은_순서.add(이름);
                나.차례.set(차례);
            }, 나.실패::set);
            return 나;
        }
    }

    @Test
    @DisplayName("줄이 비어 있으면 바로 차례를 받고 밀린 것이 아니다")
    void 비어_있으면_바로_받는다() {
        // given
        Duration 한도 = Duration.ofSeconds(3);

        // when
        LocalWriteQueue.Turn 차례 = 줄.줄을_선다(한도).block(Duration.ofSeconds(1));

        // then
        assertThat(차례).isNotNull();
        assertThat(차례.밀렸다()).isFalse();
    }

    @Test
    @DisplayName("앞 요청이 넘기면 온 순서대로 차례를 받는다 — 줄에서 기다린 요청은 밀린 것이다")
    void 온_순서대로_받는다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");

        // when
        첫째.넘긴다();

        // then — 둘째만 받는다. 셋째는 둘째가 넘길 때까지 기다린다
        assertThat(받은_순서).containsExactly("둘째");
        assertThat(둘째.차례.get().밀렸다()).isTrue();

        // when
        둘째.차례.get().넘긴다();

        // then
        assertThat(받은_순서).containsExactly("둘째", "셋째");
    }

    @Test
    @DisplayName("넘긴다를 두 번 불러도 한 번만 넘긴다 — 줄의 요청을 건너뛰지 않는다")
    void 두_번_넘겨도_한_번이다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");

        // when
        첫째.넘긴다();
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("둘째");
    }

    @Test
    @DisplayName("한도 안에 차례가 오지 않으면 줄에서 빠져 503(2초)이다 — 앞에 선 것이 이 서버의 SCIM 쓰기다")
    void 한도가_지나면_빠진다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(1), 받은_순서, "둘째");

        // when
        시간.advanceTimeBy(Duration.ofMillis(999));
        Throwable 한도_전 = 둘째.실패.get();
        시간.advanceTimeBy(Duration.ofMillis(1));

        // then
        assertThat(한도_전).isNull();
        assertThat(둘째.실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(받은_순서).isEmpty();
    }

    @Test
    @DisplayName("한도가 지나 빠진 요청은 건너뛰고 그다음 요청에 차례를 준다")
    void 빠진_요청을_건너뛴다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자.세운다(줄, Duration.ofSeconds(1), 받은_순서, "둘째");
        대기자 셋째 = 대기자.세운다(줄, Duration.ofSeconds(10), 받은_순서, "셋째");
        시간.advanceTimeBy(Duration.ofSeconds(1));

        // when
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("셋째");
        assertThat(셋째.차례.get().밀렸다()).isTrue();
    }

    @Test
    @DisplayName("기다리다 취소한 요청은 줄에서 빠진다 — 다음 요청이 차례를 받고, 줄이 비면 새 요청은 바로 받는다")
    void 취소하면_빠진다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자 셋째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");
        둘째.구독.dispose();

        // when
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("셋째");

        // when
        셋째.차례.get().넘긴다();
        LocalWriteQueue.Turn 새_요청 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));

        // then
        assertThat(새_요청.밀렸다()).as("줄이 비었으니 바로 받는다").isFalse();
    }

    @Test
    @DisplayName("바로 받는 차례도 건네받기 전에 취소하면 다음 요청에 넘어간다 — 줄이 영영 멈추지 않는다")
    void 바로_받는_차례를_요청_전에_취소하면_넘긴다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();

        // when — 줄이 비어 바로 받는 차례를, 요청하기 전에 취소한다
        StepVerifier.create(줄.줄을_선다(Duration.ofSeconds(1)), 0).thenCancel().verify();
        대기자 다음 = 대기자.세운다(줄, Duration.ofSeconds(1), 받은_순서, "다음");

        // then
        assertThat(받은_순서).containsExactly("다음");
        assertThat(다음.차례.get().밀렸다()).as("차례가 넘어가 줄이 비었으니 바로 받는다").isFalse();
    }

    @Test
    @DisplayName("차례를 받고도 건네받기 전에 취소하면 다음 요청에 넘어간다")
    void 줄에서_받은_차례를_요청_전에_취소하면_넘긴다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));

        // when — 둘째는 요청 없이 줄에 서고, 차례가 오고 나서 취소한다
        StepVerifier.create(줄.줄을_선다(Duration.ofSeconds(3)), 0)
                .then(() -> 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째"))
                .then(첫째::넘긴다)
                .thenCancel()
                .verify();

        // then
        assertThat(받은_순서).containsExactly("셋째");
    }

    @Test
    @DisplayName("차례를 받은 뒤 요청하기 전에 한도가 지나도 요청하면 차례를 받는다 — 503 이 아니다")
    void 받은_차례는_한도가_지나도_요청하면_받는다() {
        // given
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));

        // when, then
        StepVerifier.withVirtualTime(() -> 줄.줄을_선다(Duration.ofSeconds(1)), 0)
                .then(첫째::넘긴다)
                .thenAwait(Duration.ofSeconds(1))
                .thenRequest(1)
                .assertNext(차례 -> assertThat(차례.밀렸다()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("차례를 건네받은 뒤의 취소는 차례를 넘기지 않는다 — 쥔 요청이 넘길 때까지 다음 요청은 기다린다")
    void 건네받은_뒤_취소는_넘기지_않는다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        AtomicReference<LocalWriteQueue.Turn> 둘째가_받은_차례 = new AtomicReference<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        줄.줄을_선다(Duration.ofSeconds(3)).subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnNext(LocalWriteQueue.Turn 차례) {
                둘째가_받은_차례.set(차례);
                cancel();
            }
        });
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");

        // when
        첫째.넘긴다();

        // then — 둘째는 받자마자 취소했지만 차례는 쥔 채다. 셋째는 기다린다
        assertThat(둘째가_받은_차례.get()).isNotNull();
        assertThat(받은_순서).isEmpty();

        // when
        둘째가_받은_차례.get().넘긴다();

        // then
        assertThat(받은_순서).containsExactly("셋째");
    }
}
