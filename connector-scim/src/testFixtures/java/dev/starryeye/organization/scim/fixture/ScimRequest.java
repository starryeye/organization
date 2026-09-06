package dev.starryeye.organization.scim.fixture;

/**
 * SCIM 요청 하나. 하네스가 이것을 그대로 쏘고 응답을 확인한다.
 *
 * <p>{@code body} 를 직렬화된 문자열이 아니라 DTO 그대로 들고 다니는 이유: 문자열로 굳히면
 * 필드가 바뀌었을 때 픽스처가 조용히 낡는다. DTO 로 두면 컴파일이 먼저 깨진다.
 *
 * @param method HTTP 메서드. {@code HttpMethod} 대신 문자열인 것은 이 픽스처가 웹 스택에
 *               의존하지 않게 하려는 것이다 — 하네스가 자기 클라이언트 타입으로 변환한다
 * @param 설명 실패했을 때 어느 요청이 깨졌는지 알아보기 위한 것. 5천 건 중 하나를 찾는 데
 *           인덱스 번호만으로는 부족하다
 */
public record ScimRequest(String method, String path, Object body, String 설명) {

    public static ScimRequest post(String path, Object body, String 설명) {
        return new ScimRequest("POST", path, body, 설명);
    }

    public static ScimRequest put(String path, Object body, String 설명) {
        return new ScimRequest("PUT", path, body, 설명);
    }

    public static ScimRequest patch(String path, Object body, String 설명) {
        return new ScimRequest("PATCH", path, body, 설명);
    }

    public static ScimRequest delete(String path, String 설명) {
        return new ScimRequest("DELETE", path, null, 설명);
    }
}
