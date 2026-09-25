package dev.starryeye.organization.ldap.strategy;

/**
 * 디렉터리의 데이터가 우리가 해석할 수 있는 모양이 아니다 — <b>같은 데이터를 다시 읽으면 같은 결과다.</b>
 *
 * <p>정수가 아닌 계정 상태 값, 없는 필수 속성, 해석할 수 없는 DN, 멤버가 하나도 대조되지 않는 설정 오류가
 * 여기 속한다. 서버 장애나 네트워크 끊김과 달리 기다려서 풀리지 않으므로 {@code LdapDirectorySnapshotSource}
 * 가 <b>재시도하지 않는다.</b> 재시도에 맡기면 큰 디렉터리를 통째로 몇 번 더 읽은 끝에, 운영자가 데이터나 설정을
 * 고쳐야 할 문제가 "일시적 장애" 로 보인다.
 *
 * <p>{@link IllegalStateException} 을 잇는 것은 이 오류들이 원래 그것으로 던져졌기 때문이다 — 그 타입을 보던
 * 코드와 테스트는 그대로 동작한다.
 */
public class DirectoryDataException extends IllegalStateException {

    public DirectoryDataException(String message) {
        super(message);
    }

    public DirectoryDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
