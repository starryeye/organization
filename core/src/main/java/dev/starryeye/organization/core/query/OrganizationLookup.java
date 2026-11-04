package dev.starryeye.organization.core.query;

/**
 * 조직 정확 조회의 응답. 권한을 묻는 앱이 {@code externalId} 로 {@code orgCode} 를 찾아 {@code Check} 에 쓴다.
 *
 * @param orgCode     조직코드. 튜플에 실리는 값 — SCIM 배포는 서버가 발급한 아이디라 초기화(wipe) 뒤에 바뀐다
 * @param displayName 조직명
 * @param externalId  물은 값과 같은, 본 테이블에서 다시 읽은 값
 */
public record OrganizationLookup(String orgCode, String displayName, String externalId) {
}
