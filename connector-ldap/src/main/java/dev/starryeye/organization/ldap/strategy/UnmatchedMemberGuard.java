package dev.starryeye.organization.ldap.strategy;

/**
 * 멤버 대조가 <b>전부</b> 실패한 회차를 중단시킨다.
 *
 * <p><b>왜 이 가드가 있는가 — 2026-09-11 코드리뷰 H.</b> 예전 전략은 대조에 쓸 DN 을 직접
 * 조립했다(검색 베이스 바로 아래 + RDN 이 식별 속성). 실제 Active Directory 처럼 사용자가
 * 조직 OU 아래 있고 RDN 이 {@code cn} 인 트리에서는 서버가 준 {@code member} 값과 하나도
 * 맞지 않는다. 증상은 <b>"모든 그룹이 멤버 0명"</b> 이다.
 *
 * <p><b>조용히 지나가는 것이 문제다.</b> 첫 적재라면 지울 것이 없어 삭제 가드(30%)도 걸리지
 * 않는다. 아무도 권한을 받지 못한 채 동기화는 성공으로 끝난다. 이미 적재된 뒤라면 삭제
 * 가드가 멈출 가능성이 높지만, 그 메시지는 "임계치 초과" 라 원인을 말해 주지 않는다.
 *
 * <p><b>부분 불일치는 막지 않는다.</b> 연락처나 컴퓨터 계정처럼 우리가 읽지 않는 엔트리가
 * 그룹에 섞여 있는 것은 정상이다 — 그쪽은 경고를 남기고 건너뛴다.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-09-24-real-dn-matching-design.md} §6.
 */
final class UnmatchedMemberGuard {

    private UnmatchedMemberGuard() {
    }

    /**
     * 조직이 하나라도 있고 {@code member} 값이 하나 이상인데 대조된 것이 0 이면
     * {@link MemberMatchingFailedException} 을 던진다.
     */
    static void 확인한다(int 조직수, int 멤버값수, int 대조된수) {
        if (조직수 == 0 || 멤버값수 == 0 || 대조된수 > 0) {
            return;
        }
        throw new MemberMatchingFailedException(
                "조직 " + 조직수 + "개의 member 값 " + 멤버값수 + "개가 하나도 대조되지 않았습니다."
                        + " 사용자 검색 베이스와 그룹 member 값의 DN 형태가 어긋났는지"
                        + " 확인하십시오.");
    }
}
