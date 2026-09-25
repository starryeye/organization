package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import reactor.core.publisher.Mono;

/**
 * SCIM 목록 페이지의 책갈피 (S-1 설계 §5.3).
 *
 * <p>IdP 는 가져오기를 {@code startIndex=1, 101, 201…} 처럼 순서대로 부른다. 페이지를 줄 때 다음
 * {@code startIndex} 에서 이어 읽을 위치를 여기 두면, 다음 요청은 앞을 건너뛰지 않고 그 자리부터 읽는다.
 * 앱이 여러 대여도 이어지도록 공유 저장소에 둔다. 수명이 짧아(15분) 디렉터리 읽기와 포트를 나눈다.
 */
public interface PageBookmarkRepository {

    /** 없거나 만료됐으면 빈 Mono. */
    Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex);

    /** 같은 키에 이미 있으면 덮어쓴다 — 동시에 도는 두 가져오기의 책갈피는 둘 다 올바른 위치다. */
    Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark);
}
