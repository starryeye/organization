package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SCIM 목록·필터 조회 전용 읽기 포트 (S-1 설계 §5.3).
 *
 * <p>{@link DirectorySearchRepository}(admin 용 "접두사 + 커서")와 계약이 달라 따로 둔다 — 이쪽은
 * <b>완전 일치</b>와 <b>인덱스 순서의 위치</b>다. 쓰기 경로의 심장인 {@link DirectoryStateRepository} 에
 * 조회를 얹지 않는다는 원칙도 같다.
 *
 * <p>{@code userName}·{@code displayName} 일치는 대소문자를 가리지 않는다(RFC 7643 {@code caseExact=false}).
 * {@code externalId} 는 가린다.
 *
 * <p>위치({@code from}, {@link Page#nextCursor()}, {@code skip*} 의 결과)는 저장소가 만든 불투명 문자열이다.
 * {@code from} 이 null 이면, 그리고 {@code skip*} 이 빈 {@link Mono} 면 "처음" 이다.
 */
public interface DirectoryQueryRepository {

    Flux<DirectoryUser> findUsersByUserName(String userName);

    Flux<DirectoryUser> findUsersByExternalId(String externalId);

    Flux<GroupHeader> findGroupHeadersByDisplayName(String displayName);

    Flux<GroupHeader> findGroupHeadersByExternalId(String externalId);

    /** {@code userName} 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. */
    Mono<Page<DirectoryUser>> listUsers(String from, int limit, boolean descending);

    /** 조직명 소문자 순(내림차순이면 역순)으로 {@code from} 다음부터 {@code limit} 건. 멤버는 담지 않는다. */
    Mono<Page<GroupHeader>> listGroupHeaders(String from, int limit, boolean descending);

    Mono<Long> countUsers();

    Mono<Long> countGroups();

    /** 앞의 {@code n} 건을 건너뛴 위치. {@code n} 이 0 이하이거나 아무도 없으면 빈 Mono. 전원보다 많으면 끝 위치. */
    Mono<String> skipUsers(long n, boolean descending);

    Mono<String> skipGroups(long n, boolean descending);
}
