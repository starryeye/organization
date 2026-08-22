package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.SyncSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class SnapshotIds {

    /**
     * <b>밀리초까지 담는다.</b> 초 단위였을 때는 같은 초에 시작한 두 실행이 같은 id 를 받아
     * 한 스냅샷 파티션에 두 튜플 집합의 <b>합집합</b>을 만들었다. 그 합집합은 어느 실행의
     * 결과도 아니어서, 그것을 기준선으로 삼는 다음 diff 가 통째로 어긋난다.
     *
     * <p>{@code SyncExecutionGuard} 가 겹치기 실행을 막고 있지만, 그것은 인스턴스 하나
     * 안에서만 유효하다(follow-ups §6). 실행이 초 단위로 끝나지도 않으므로 밀리초면
     * 현실적인 충돌 창은 닫힌다. 고정 너비·0 패딩이라 사전순 정렬은 그대로 시간순이다.
     */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    private SnapshotIds() {
    }

    public static String generate(Instant at, SyncSource source) {
        return FORMAT.format(at) + "-" + source.name();
    }
}
