package dev.starryeye.organization.security;

import dev.starryeye.organization.security.fixture.TestTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 시험 토큰 도우미 자체 — 앱 시험 여럿이 함께 쓰므로 한 시험이 다른 시험의 전제를 바꿀 수 없어야 한다. */
class TestTokensTest {

    @Test
    @DisplayName("모든 범위 목록은 여섯 범위이고 바꿀 수 없다 — 한 시험이 고쳐 다른 시험의 기본 토큰을 망가뜨리지 못한다")
    void 모든_범위는_바꿀_수_없다() {
        // when, then
        assertThat(TestTokens.ALL_SCOPES).containsExactly(
                Scopes.SCIM, Scopes.ADMIN_READ, Scopes.ADMIN_SYNC, Scopes.ADMIN_DESTRUCTIVE, Scopes.DIRECTORY_LOOKUP, Scopes.ACTUATOR);
        assertThatThrownBy(() -> TestTokens.ALL_SCOPES.set(0, Scopes.ACTUATOR))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
