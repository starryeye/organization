package dev.starryeye.organization.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 컨트롤러가 쿼리 값을 본 뒤에 하는 범위 판정(설계 2026-10-28 §4.3, A4). 403 은 관리 API 처리기가 problem details 로 바꾸고, 머리는 그 예외의
 * {@code getHeaders()} 로 건넨다.
 */
class CallersTest {

    @Test
    @DisplayName("그 범위의 권한이 없으면 403 이다 — 사유는 '<무엇>은 <범위> 범위가 필요하다', 머리는 WWW-Authenticate insufficient_scope")
    void 범위가_없으면_403_이다() {
        // given — admin.sync 만 가진 운영 도구
        var 호출자 = new TestingAuthenticationToken("ops", null, "SCOPE_admin.sync");

        // when
        var 거절 = catchThrowableOfType(InsufficientScopeException.class,
                () -> Callers.요구한다(호출자, Scopes.ADMIN_DESTRUCTIVE, "force=true"));

        // then
        assertThat(거절.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(거절.getReason()).isEqualTo("force=true은 admin.destructive 범위가 필요하다");
        assertThat(거절.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE)).containsExactly("Bearer error=\"insufficient_scope\"");
    }

    @Test
    @DisplayName("호출자가 없어도(null) 403 이다 — 판정을 건너뛰지 않는다")
    void 호출자가_없어도_403_이다() {
        // when
        var 거절 = catchThrowableOfType(InsufficientScopeException.class,
                () -> Callers.요구한다(null, Scopes.ADMIN_DESTRUCTIVE, "mode=wipe"));

        // then
        assertThat(거절.getReason()).isEqualTo("mode=wipe은 admin.destructive 범위가 필요하다");
    }

    @Test
    @DisplayName("그 범위의 권한(SCOPE_<범위>)이 있으면 지나간다")
    void 범위가_있으면_지나간다() {
        // given
        var 호출자 = new TestingAuthenticationToken("ops-break-glass", null, "SCOPE_admin.sync", "SCOPE_admin.destructive");

        // when, then
        assertThatCode(() -> Callers.요구한다(호출자, Scopes.ADMIN_DESTRUCTIVE, "force=true")).doesNotThrowAnyException();
    }
}
