package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 락이 진짜 락의 계약을 흉내내는지 확인한다. 유스케이스 테스트가 이것에 기대므로
 * 여기가 틀리면 그 위의 테스트가 통째로 헛돈다.
 */
class FakeMutationLockTest {

    @Test
    @DisplayName("획득하면 리스를 주고, 반납하면 다시 잡을 수 있다")
    void 획득과_반납이_짝을_이룬다() {
        // given
        var lock = new FakeMutationLock();

        // when
        var lease = lock.acquire(LockPurpose.WRITE).block();

        // then
        assertThat(lease).isNotNull();
        assertThat(lease.token()).isNotBlank();
        assertThat(lock.acquired).hasValue(1);

        // when
        lock.release(lease).block();

        // then
        assertThat(lock.released).hasValue(1);
        assertThat(lock.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("쥐고 있는 동안에는 두 번째 획득이 실패한다")
    void 쥐고_있으면_두_번째는_실패한다() {
        // given
        var lock = new FakeMutationLock();
        lock.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> lock.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("failAcquire 를 켜면 획득이 항상 실패한다 — 503 경로를 재현한다")
    void 실패를_강제할_수_있다() {
        // given
        var lock = new FakeMutationLock();
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> lock.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("반납한 리스를 갱신하려 하면 실패한다 — 리스 상실을 재현한다")
    void 잃은_리스는_갱신되지_않는다() {
        // given
        var lock = new FakeMutationLock();
        var lease = lock.acquire(LockPurpose.WRITE).block();
        lock.release(lease).block();

        // when, then
        assertThatThrownBy(() -> lock.renew(lease).block())
                .isInstanceOf(LockUnavailableException.class);
    }
}
