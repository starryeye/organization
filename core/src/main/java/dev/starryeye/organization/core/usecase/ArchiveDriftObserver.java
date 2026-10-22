package dev.starryeye.organization.core.usecase;

/**
 * 하루 아카이빙이 본 어긋남의 수를 받는다(설계 2026-10-20 §6.4). 아카이빙이 비교를 마칠 때마다 <b>어긋남이 없어도</b> 부른다 — 받는 쪽이 게이지처럼 마지막
 * 값을 쥐면 고쳐진 뒤 0 으로 내려와야 하기 때문이다. 비교를 마치지 못한 아카이빙(Check 실패)은 부르지 않는다.
 *
 * <p>{@code IncrementalSyncUseCase.DriftObserver} 와 다르다 — 그쪽은 쓰기 요청이 지나가다 본 어긋남을 쌓지만, 이쪽은 상태 전체를 하루에 한 번 비교한 결과다.
 * {@code core} 가 Micrometer 를 알지 않게 콜백으로 받는다.
 */
public interface ArchiveDriftObserver {

    /**
     * @param extra   장부에 있지만 상태가 요구하지 않는 줄({@code direct_member}·{@code child} 모두) — 끝나지 않은 회수
     * @param missing 상태가 요구하지만 장부에 없는 줄
     */
    void observed(long extra, long missing);

    ArchiveDriftObserver NOOP = (extra, missing) -> {
    };
}
