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
}
