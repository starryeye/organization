package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleWriteResult;

/**
 * 쓰기가 도중에 멈췄다 — 연속 실패 차단기(점검 C7, 설계 2026-09-29 §5)가 남은 배치를 보내지 않았다.
 *
 * <p>{@link #partial()} 은 멈추기 전까지 <b>실제로 반영된 것</b>이고, 보내지 않은 줄은 전부 실패로 들어 있다. 호출자는 이것으로
 * 기록 규칙(설계 §3.1 4단계)을 지킨 뒤 FAILED 로 기록한다 — 이미 나간 쓰기를 모른 척하면 기준선(스냅샷)이 장부와 어긋난다.
 *
 * <p>OpenFGA 가 연달아 실패한 것이라 일시 장애다(SCIM 은 503, 설계 2026-10-05 §3.1).
 */
public class TupleWriteAbortedException extends TemporaryFailureException {

    private final TupleWriteResult partial;

    public TupleWriteAbortedException(String message, TupleWriteResult partial) {
        super(message, 기본_대기);
        this.partial = partial;
    }

    /** 멈추기 전까지 실제로 반영된 것. 보내지 않은 줄은 실패로 들어 있다. */
    public TupleWriteResult partial() {
        return partial;
    }
}
