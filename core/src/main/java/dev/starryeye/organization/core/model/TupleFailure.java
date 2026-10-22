package dev.starryeye.organization.core.model;

/**
 * 반영하지 못한 튜플 한 줄. {@code uncertain} 이 실패의 종류를 가른다(설계 2026-10-20 §3.1).
 * <ul>
 *   <li><b>확정</b>({@code false}) — 반영되지 않았다는 것을 안다. OpenFGA 가 묶음을 거절했거나(400, 한 줄까지 좁힌 뒤), 아예 보내지 않았다
 *       (연속 실패 차단기, 앞 단계 실패로 보류).</li>
 *   <li><b>불확실</b>({@code true}) — 보냈는데 일시 오류가 재시도 뒤에도 남아 결과를 모른다. 서버가 받았는데 응답만 잃었을 수 있다.</li>
 * </ul>
 * 두 인자 생성자는 확정이다.
 */
public record TupleFailure(RelationTuple tuple, String reason, boolean uncertain) {

    public TupleFailure(RelationTuple tuple, String reason) {
        this(tuple, reason, false);
    }

    /** 보냈는데 결과를 모르는 줄 — 반영됐는지 알 수 없다 */
    public static TupleFailure uncertain(RelationTuple tuple, String reason) {
        return new TupleFailure(tuple, reason, true);
    }
}
