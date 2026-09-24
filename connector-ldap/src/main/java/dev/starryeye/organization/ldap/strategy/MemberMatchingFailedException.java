package dev.starryeye.organization.ldap.strategy;

/**
 * 그룹의 {@code member} 값 중 <b>사람이 하나도</b> 우리가 읽은 직원과 대조되지 않았다.
 * {@link UnmatchedMemberGuard} 가 던진다 — 그 회차는 아무것도 쓰지 않고 실패로 끝난다.
 *
 * <p><b>대개 설정 문제다.</b> 사용자 검색 베이스가 틀렸거나, 서버가 주는 DN 의 모양이 우리가
 * 읽은 직원의 DN 과 어긋난다. 그대로 진행하면 모든 그룹이 멤버 0명으로 적재되고, 첫 적재라면
 * 삭제 가드(30%)도 걸리지 않아 아무도 권한을 받지 못한 채 성공으로 끝난다
 * (2026-09-11 코드리뷰 H).
 *
 * <p><b>재시도하지 않는다.</b> 같은 설정으로 다시 읽어도 같은 결과다
 * ({@code LdapDirectorySnapshotSource} 가 재시도에서 뺀다).
 */
public class MemberMatchingFailedException extends RuntimeException {

    public MemberMatchingFailedException(String message) {
        super(message);
    }
}
