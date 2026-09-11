package dev.starryeye.organization.core.model;

/**
 * 멤버 없이 조직의 식별·표시 정보만 담는다. <b>{@link DirectoryGroup} 을 멤버 없이 쓰지
 * 않는 이유가 있다</b> — 이 코드베이스에서 "멤버 0개" 는 이미 다른 뜻으로 쓰인다
 * ({@code IncrementalSyncUseCase.expandWithReferencedGroups} 가 "존재만 확인하고 튜플은
 * 만들지 마라" 는 표시로 빈 멤버를 쓴다). 거기에 "진짜 멤버가 없는 조직" 과 "헤더만 읽은
 * 조직" 까지 겹치면 한 값이 세 가지 뜻을 갖고, 누군가 헤더를 스냅샷에 그대로 넣고
 * "멤버 0명이네" 로 읽는 사고가 난다.
 *
 * <p>별도 타입이면 스냅샷에 넣으려면 {@code new DirectoryGroup(id, ..., 멤버)} 를 손으로
 * 만들어야 하고, 그 순간 "누구를 넣을지" 를 명시하게 된다.
 *
 * @param id 조직코드. 튜플에 쓰이는 안정 식별자
 * @param displayName 조직명. 튜플에 절대 쓰지 않는다
 */
public record GroupHeader(
        String id,
        String externalId,
        String displayName
) {
}
