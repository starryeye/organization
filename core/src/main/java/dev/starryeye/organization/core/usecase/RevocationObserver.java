package dev.starryeye.organization.core.usecase;

/**
 * SCIM 쓰기가 <b>커밋한</b> 회수를 지표로 남길 수 있게 알린다 — {@code scim.revocations{kind}}(설계 2026-10-27 §8).
 *
 * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 — {@link IncrementalSyncUseCase.DriftObserver}·{@link LockObserver} 와 같은 이유,
 * 같은 모양이다.
 *
 * <p><b>막지 않고 보이게만 한다(설계 2026-10-27 §17.4).</b> IdP 의 의도된 대량 퇴사를 시간 창으로 막으면 회수가 늦어진다 — "표준이 정한 신호만 받는다"
 * 원칙과도 부딪힌다. 그래서 세기만 하고, 1시간 증가량이 평소보다 크게 늘면 경보를 울리게 둔다. 관찰자가 던져도 쓰기는 그대로 끝난다 — 부르는 쪽이 경고만
 * 남기고 삼킨다.
 *
 * <p><b>무엇을 세나.</b> 상태 쓰기가 성공한 뒤, 이력에 더하는 자리와 같은 자리에서 센다 — 커밋 전에 세지 않는다. 그룹 쪽은 실패하면 의도를 저장하지 않으므로
 * 커밋된 만큼만 세고, IdP 의 재시도는 남은 것만 센다. 그래서 장애 중 재시도가 거짓 경보를 울리지 않는다.
 *
 * <p><b>직원 하나의 회수는 한 번만 센다.</b> 삭제가 일부 실패해 비활성으로 남기면 {@link Kind#DEACTIVATE} 로 세고, 그 뒤 DELETE 재시도가 레코드를 지울 때는
 * 이미 비활성이므로 {@link Kind#DELETE_USER} 로 세지 않는다. 직원을 지우며 함께 빠지는 소속은 {@link Kind#REMOVE_MEMBER} 로 세지 않는다.
 */
public interface RevocationObserver {

    /** 회수의 종류. {@link #tag()} 가 지표의 {@code kind} 태그 값이다. */
    enum Kind {
        /** {@code active} 가 true → false 로 저장됐다(삭제가 일부 실패해 비활성으로 남긴 경우 포함). 1. */
        DEACTIVATE("deactivate"),
        /** 직원 레코드를 지웠다. 그때 활성이던 직원만 센다. 1. */
        DELETE_USER("delete_user"),
        /** 조직에서 직원 멤버를 뺀 것이 커밋됐다(PATCH remove, PUT 으로 빠짐). 커밋된 뺀 직원 수. 하위 조직은 세지 않는다. */
        REMOVE_MEMBER("remove_member"),
        /** 조직 삭제가 커밋됐다. 커밋 때 지운 직원 멤버 수. 하위 조직은 세지 않는다. */
        DELETE_GROUP("delete_group");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * 회수가 커밋됐다.
     *
     * @param count 이번 커밋이 회수한 직원 수. 부르는 쪽은 0 이면 부르지 않는다
     */
    void revoked(Kind kind, int count);

    RevocationObserver NOOP = (kind, count) -> {
    };
}
