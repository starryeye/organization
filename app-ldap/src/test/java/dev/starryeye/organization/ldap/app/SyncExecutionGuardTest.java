package dev.starryeye.organization.ldap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncExecutionGuardTest {

    @Test
    @DisplayName("동기화가 실행 중이 아니면 획득에 성공한다")
    void 유휴상태면_획득한다() {
        // given
        var guard = new SyncExecutionGuard();

        // when
        boolean acquired = guard.tryAcquire();

        // then
        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("이미 실행 중이면 획득에 실패해 중복 실행을 막는다")
    void 실행중이면_획득에_실패한다() {
        // given
        var guard = new SyncExecutionGuard();
        guard.tryAcquire();

        // when
        boolean second = guard.tryAcquire();

        // then
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("반납하면 다시 획득할 수 있다")
    void 반납하면_다시_획득한다() {
        // given
        var guard = new SyncExecutionGuard();
        guard.tryAcquire();

        // when
        guard.release();
        boolean again = guard.tryAcquire();

        // then
        assertThat(again).isTrue();
    }

    @Test
    @DisplayName("반납 수단은 몇 번 불려도 한 번만 푼다 — 그사이 남이 잡은 점유를 풀지 않는다")
    void 반납은_한_번만_푼다() {
        // given
        var guard = new SyncExecutionGuard();
        guard.tryAcquire();
        var 반납 = guard.releaseOnce();

        // when — 작업이 반납하고, 다른 작업이 잡은 뒤, 요청 쪽이 다시 반납을 부른다
        반납.get().block();
        assertThat(guard.tryAcquire()).as("반납했으면 다시 잡힌다").isTrue();
        반납.get().block();

        // then — 두 번째 반납이 남의 점유를 풀면 동기화 둘이 겹친다
        assertThat(guard.tryAcquire()).isFalse();
    }
}
