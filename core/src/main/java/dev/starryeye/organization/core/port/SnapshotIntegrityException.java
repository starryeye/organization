package dev.starryeye.organization.core.port;

/**
 * 튜플 스냅샷을 온전히 읽지 못했다 — 메타가 없거나 읽은 튜플 수가 메타와 다르다. 최신 포인터가 가리키는 것이면
 * 기준선이 깨진 것이다(아이디로 읽은 스냅샷, {@code findById} 에도 던진다).
 *
 * <p>빈 기준선으로 넘어가면 그 회차의 삭제를 하나도 하지 않고 SUCCEEDED 가 된다(점검 C1). 그래서 빈 결과가 아니라
 * 이 예외로 회차를 멈추고, 메시지로 복구 방법을 알린다.
 *
 * <p>계약: 가져온 아이템을 푸는 동안의 실패는 이 예외로 보고하고, 가져오는 중의 실패(저장소·SDK 오류)는 절대 이 예외로 보고하지 않는다.
 * 기록 중 회차와 재적재는 재활성 가드를 이 예외일 때만 건너뛰고 그 밖의 오류는 회차를 실패시킨다(설계 2026-11-25 E1·E3).
 */
public class SnapshotIntegrityException extends RuntimeException {

    public SnapshotIntegrityException(String message) {
        super(message);
    }
}
