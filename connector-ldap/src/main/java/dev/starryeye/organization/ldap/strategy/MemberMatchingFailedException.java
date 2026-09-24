package dev.starryeye.organization.ldap.strategy;

/**
 * 그룹의 {@code member} 값이 <b>하나도</b> 우리가 읽은 엔트리와 대조되지 않았다.
 * {@link UnmatchedMemberGuard} 가 던진다 — 그 회차는 아무것도 쓰지 않고 실패로 끝난다.
 */
public class MemberMatchingFailedException extends RuntimeException {

    public MemberMatchingFailedException(String message) {
        super(message);
    }
}
