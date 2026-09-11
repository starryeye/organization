package dev.starryeye.organization.ldap.strategy;

/**
 * 다중값 속성을 <b>끝까지 읽지 못했다.</b> 서버가 "더 있다" 고 했는데 우리가 마무리하지
 * 못한 경우다 — 조각이 한도를 넘었거나, 값을 더 주지 않으면서 완료 표시도 안 했거나,
 * 읽는 도중 실패했거나.
 *
 * <p><b>읽은 만큼으로 넘어가면 안 되기 때문에 던진다.</b> 전체 동기화는 델타를 디렉터리
 * 전체를 놓고 계산한다 — 목표 집합에 없는 튜플은 <b>지워진다</b>. 그래서 멤버를 덜 읽은
 * 것이 그대로 "이 조직에서 그 사람들이 빠졌다" 가 되고, 6,000명짜리 그룹을 0명으로 읽으면
 * 그 6,000개의 권한 튜플이 삭제 목록에 오른다. 삭제 가드(30%)가 잡을 수도 있지만 전체가
 * 10만이면 6% 라 그냥 지워진다.
 *
 * <p><b>진짜 삭제는 여기에 걸리지 않는다.</b> 멤버가 실제로 없어지면 서버는 범위 옵션 없이
 * 값 0개를 준다 — "이게 전부다" 이므로 완료로 읽히고 삭제가 정상 전파된다. 못 읽은 경우만
 * 상한이 숫자인 범위 옵션(<code>member;range=0-1499</code>)으로 온다. 둘의 구분은 우리가
 * 짐작하는 것이 아니라 <b>서버가 프로토콜로 알려주는 값</b>이다.
 *
 * <p>이것이 올라가면 그 회차의 동기화가 실패로 끝나고 <b>아무것도 쓰지 않는다.</b> 이전
 * 상태가 그대로 남고 다음 회차가 재시도한다 — LDAP 은 매일 전체 재계산이므로 그렇게
 * 수렴한다(설계 §1.1). 틀린 상태를 쓰는 것보다 이번 회차를 건너뛰는 편이 낫다.
 */
public class IncompleteAttributeReadException extends RuntimeException {

    public IncompleteAttributeReadException(String message) {
        super(message);
    }

    public IncompleteAttributeReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
