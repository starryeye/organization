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

    /**
     * 조직의 이름표 한 줄만 읽는다. 없으면 빈 {@link Mono}.
     *
     * <p><b>왜 {@code DirectoryStateRepository.findGroup} 을 쓰지 않나.</b> 그쪽은
     * {@code GROUP#<code>} 파티션 전체를 훑어 META 와 멤버십 아이템을 <b>전부</b> 읽어
     * {@code DirectoryGroup} 을 조립한다. 쓰기 경로는 멤버 목록이 실제로 필요해서 그렇게
     * 하지만, 조회 경로의 계층 순회는 {@code id} 와 {@code displayName} 두 칸만 쓴다.
     * 하위 조직 30개짜리 조직 하나를 그리려고 각 조직의 멤버 500명씩 15,000 아이템을 읽는
     * 셈이었고, 두 엔드포인트 모두 인증이 없어 그 증폭을 익명 호출자가 조종할 수 있었다.
     *
     * <p>이 포트가 존재하는 이유가 바로 이것이다 — 조회 관심사를 쓰기 경로의 심장인
     * {@code DirectoryStateRepository} 에 얹지 않고도 조회에 맞는 읽기를 정의할 수 있다.
     */
    Mono<GroupSummary> findGroupSummary(String orgCode);
}
