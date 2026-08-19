package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import reactor.core.publisher.Mono;

/**
 * 접두사 검색 전용 읽기 포트.
 *
 * <p>{@link DirectoryStateRepository} 에 얹지 않는다. 그쪽은 동기화 쓰기 경로의 심장이라
 * 조회 관심사를 섞으면 조회 코드가 쓰기 메서드까지 전부 보게 된다.
 *
 * <p>필드별로 메서드를 나눈 이유도 같다 — {@code field} 파라미터 하나로 합치면 구현이
 * 인덱스 선택 분기를 안고 가고, 호출부에서 어떤 인덱스를 타는지 보이지 않는다.
 *
 * <p>{@code prefix} 는 비어 있을 수 없다. 빈 접두사는 전체 열거이며 호출자가 막는다.
 * {@code cursor} 가 null 이면 첫 페이지다.
 */
public interface DirectorySearchRepository {

    Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit);

    Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit);

    Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit);
}
