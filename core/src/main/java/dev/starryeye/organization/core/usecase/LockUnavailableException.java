package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.TemporaryFailureException;

import java.time.Duration;

/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다 — 일시 장애다(설계 2026-10-05 §3.1). 다른 쪽이 락을 쥐어 조건이 깨졌으면 {@link #잡혀_있다} 로 쥔 쪽의 용도에 맞는 시간을 싣고,
 * 그 밖(락 저장소 오류·리스 상실·갱신 실패)은 {@link TemporaryFailureException#기본_대기} 다.
 */
public class LockUnavailableException extends TemporaryFailureException {

    /** 다른 SCIM 쓰기가 쥐고 있다 — 밀리초 단위로 쥐는 락이다 */
    public static final Duration 쓰기_경합_대기 = Duration.ofSeconds(2);
    /** 재적재·전체 동기화가 쥐고 있다 — 수 분 걸린다 */
    public static final Duration 긴_작업_대기 = Duration.ofSeconds(60);

    public LockUnavailableException(String message) {
        super(message, 기본_대기);
    }

    /**
     * 락 자체의 문제가 아니라 저장소 장애로 획득에 실패했을 때 쓴다.
     * 원인을 물고 가야 한다 — 여기서 끊으면 로그에 "락을 얻는 중 오류" 만 남고 DynamoDB 가
     * 무엇을 던졌는지가 사라진다.
     */
    public LockUnavailableException(String message, Throwable cause) {
        super(message, 기본_대기, cause);
    }

    public LockUnavailableException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }

    /** 다른 쪽이 락을 쥐고 있어 획득 조건이 깨졌다. 쥔 쪽의 용도를 모르면(null) 흔한 경우인 쓰기 경합으로 본다. */
    public static LockUnavailableException 잡혀_있다(MutationLock.LockPurpose 쥔_용도) {
        Duration 대기 = 쥔_용도 == MutationLock.LockPurpose.REBUILD || 쥔_용도 == MutationLock.LockPurpose.SYNC
                ? 긴_작업_대기 : 쓰기_경합_대기;
        return new LockUnavailableException("다른 작업이 변경 락을 쥐고 있습니다(" + (쥔_용도 == null ? "용도 모름" : 쥔_용도) + ")", 대기);
    }
}
