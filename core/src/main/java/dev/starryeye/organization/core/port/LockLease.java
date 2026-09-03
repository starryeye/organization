package dev.starryeye.organization.core.port;

import java.time.Instant;

/**
 * 락을 쥐고 있다는 증거. {@code token} 이 이번 점유를 식별한다.
 *
 * <p>반납·갱신이 이 토큰을 조건으로 건다. 토큰 없이 반납하면 <b>내 리스가 만료돼 남이
 * 가져간 뒤에 남의 락을 풀어버린다</b> (설계 §4.3).
 */
public record LockLease(String token, Instant expiresAt) {
}
