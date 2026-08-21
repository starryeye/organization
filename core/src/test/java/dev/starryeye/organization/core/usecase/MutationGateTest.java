package dev.starryeye.organization.core.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MutationGateTest {

    private MutationGate gate;

    @BeforeEach
    void setUp() {
        gate = new MutationGate();
    }

    @Test
    @DisplayName("평소에는 변경을 막지 않는다")
    void 평소에는_통과시킨다() {
        // when, then
        assertThat(gate.isSuspended()).isFalse();
        assertThat(gate.requireOpen().block()).isNull();
    }

    @Test
    @DisplayName("닫혀 있는 동안의 변경은 MutationsSuspendedException 으로 거절된다")
    void 닫히면_거절한다() {
        // given
        gate.acquire();

        // when, then
        assertThat(gate.isSuspended()).isTrue();
        assertThatThrownBy(() -> gate.requireOpen().block())
                .isInstanceOf(MutationsSuspendedException.class);
    }

    @Test
    @DisplayName("반납하면 다시 통과시킨다")
    void 반납하면_다시_열린다() {
        // given
        gate.acquire();

        // when
        gate.release();

        // then
        assertThat(gate.isSuspended()).isFalse();
        assertThat(gate.requireOpen().block()).isNull();
    }

    @Test
    @DisplayName("이미 닫혀 있으면 두 번째 획득은 실패한다 — 재적재끼리 겹치지 않는다")
    void 중복_획득은_실패한다() {
        // given
        assertThat(gate.acquire()).isTrue();

        // when, then
        assertThat(gate.acquire()).isFalse();
    }

    @Test
    @DisplayName("작업이 실패로 끝나도 게이트는 반납된다")
    void 실패해도_반납한다() {
        // given
        gate.acquire();

        // when — doFinally 로 감싸는 실제 사용 모양을 그대로 흉내낸다
        Mono<Void> work = Mono.<Void>error(new IllegalStateException("터짐"))
                .doFinally(signal -> gate.release());

        // then
        assertThatThrownBy(work::block).isInstanceOf(IllegalStateException.class);
        assertThat(gate.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("작업이 취소돼도 게이트는 반납된다")
    void 취소돼도_반납한다() {
        // given — 클라이언트가 연결을 끊는 경우다. 여기서 새면 영원히 닫힌 채로 남는다
        gate.acquire();

        // when
        Mono<Void> work = Mono.<Void>never().doFinally(signal -> gate.release());
        work.subscribe().dispose();

        // then
        assertThat(gate.isSuspended()).isFalse();
    }

    @Test
    @DisplayName("requireOpen 은 구독 시점에 판단한다")
    void 구독_시점에_판단한다() {
        // given — Mono 를 만들 때는 열려 있었지만 구독 전에 닫힌다
        Mono<Void> deferred = gate.requireOpen();
        gate.acquire();

        // when, then — 조립 시점의 상태를 붙들고 있으면 안 된다
        assertThatThrownBy(deferred::block)
                .isInstanceOf(MutationsSuspendedException.class);
    }
}
