package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.MemberType;

/**
 * <b>사람 소속이 하나도 없는</b> 디렉터리 그림을 데이터 오류로 멈춘다 — 두 LDAP 전략({@link GroupOfNamesStrategy}·{@link DitStrategy})이 그림을 만든 직후
 * 같은 한 곳에서 부른다(설계 2026-10-27 §13, D11).
 *
 * <p><b>규칙은 하나다.</b> 사람 소속은 조직의 멤버 가운데 {@link MemberType#USER} 이고 그 id 가 그림의 {@code users()} 에 있는 것이다. 이 수가 0 이면
 * {@link DirectoryDataException} 이다. 직원이 활성인지는 보지 않는다 — 시계 오류로 전원이 비활성으로 읽히는 경우는 지금처럼 따로 본다(계정 상태 판정은
 * 소속을 지우지 않고 권한 튜플만 없앤다).
 *
 * <p><b>왜 데이터 오류인가.</b> RFC 4511 에서 엔트리를 하나도 못 찾은 검색은 오류가 아니라 <b>성공</b>이다. 검색 베이스·필터가 틀려도, 서비스 계정이
 * 읽을 권한이 없어 엔트리가 보이지 않아도 서버는 0건과 성공 코드를 돌려준다 — 표준이 주는 신호로는 "비어 있다" 를 오류와 구별할 수 없다. 그렇다고 빈
 * 그림을 성공으로 받으면 장부(현재상태)에 있는 모든 사람이 "디렉터리에서 사라진" 것이 되어, 삭제 가드가 전부 보류로 멈춘 뒤 {@code force} 로 넘기면
 * 장부가 통째로 지워진다. 그래서 표준 신호가 아니라 <b>우리 쪽 한 규칙</b>으로 판정한다 — SCIM {@code mode=tuples} 의 빈 조직도 규칙(조직도가 비었는데
 * 장부에 줄이 있으면 지우지 않고 FAILED)과 같은 모양이다. 결과는 FAILED 회차이고, {@code LdapDirectorySnapshotSource} 가 {@link DirectoryDataException} 을 다시 읽지 않으며, {@code force} 는
 * 이 오류를 넘기지 못한다. 동기화와 재적재가 같은 {@code fetchAll} 을 타므로 둘 다 같다. 빈 그림이 장부 맞추기(OpenFGA Read)까지 가지 않으니 Read 도 준다.
 *
 * <p><b>{@link UnmatchedMemberGuard} 는 그대로 둔다 — 이 규칙이 대신하지 못한다.</b> 그 가드는 AD 기본 그룹({@code primaryGroupID} 의 RID) 소속을
 * 일부러 셈에서 뺀다. 그래서 {@code member} 의 DN 대조가 전부 어긋났는데 RID 소속이 하나라도 있으면, 이 규칙만으로는 RID 소속 하나에 통과해 버린다
 * (2026-09-11 코드리뷰 H 의 회귀). groupOfNames 는 가드를 먼저 부르고, 이 규칙은 <b>가드가 보지 않는 모양</b>을 더 덮는다 — 직원 0명, 조직 0개,
 * {@code member} 값이 하나도 없는 그림이다. 가드의 메시지(DN 형태 힌트)와 이 규칙의 메시지(어느 검색이 비었는지)는 서로의 자리를 차지하지 않는다.
 * DIT 는 부모 OU 로 소속을 정하므로 DN 대조 가드가 없고, 예전의 "직원은 있는데 소속이 0" 검사를 이 규칙이 대신한다.
 */
final class EmptyMembershipGuard {

    private EmptyMembershipGuard() {
    }

    /**
     * 그림에 사람 소속이 하나도 없으면 {@link DirectoryDataException} 을 던진다. 첫 소속을 찾는 즉시 멈추므로 10만 명 규모에서도 비용은 소속 수가 아니라
     * 첫 소속까지의 거리다.
     *
     * @param 그림 전략이 방금 만든 디렉터리 그림
     * @param 세부 전략마다 다른 설명 — 메시지의 괄호 끝에 실린다(groupOfNames 는 member 값 수, DIT 는 부모 조직을 못 찾은 사유)
     */
    static void 확인한다(DirectorySnapshot 그림, String 세부) {
        boolean 사람_소속이_있다 = 그림.groups().values().stream()
                .flatMap(조직 -> 조직.members().stream())
                .anyMatch(멤버 -> 멤버.type() == MemberType.USER && 그림.users().containsKey(멤버.id()));
        if (사람_소속이_있다) {
            return;
        }
        throw new DirectoryDataException(
                "디렉터리 그림에 사람 소속이 하나도 없다(사용자 %d명, 조직 %d개, %s) — 비어 있는 검색의 베이스·필터를 확인하라"
                        .formatted(그림.users().size(), 그림.groups().size(), 세부));
    }
}
