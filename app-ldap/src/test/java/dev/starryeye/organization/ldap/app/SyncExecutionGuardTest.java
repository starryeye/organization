package dev.starryeye.organization.ldap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncExecutionGuardTest {

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
