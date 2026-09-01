package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase.DriftObserver;
import dev.starryeye.organization.core.usecase.LockObserver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 설계 §7 의 지표 넷을 Micrometer 로 낸다.
 *
 * <table>
 *   <caption>설계 §7</caption>
 *   <tr><td>{@code scim.drift.detected}</td><td>Counter</td><td>{@code kind} = extra / missing</td></tr>
 *   <tr><td>{@code scim.lock.wait}</td><td>Timer</td><td>–</td></tr>
 *   <tr><td>{@code scim.lock.contended}</td><td>Counter</td><td>–</td></tr>
 *   <tr><td>{@code scim.lock.lease_lost}</td><td>Counter</td><td>–</td></tr>
 * </table>
 *
 * <p><b>어긋남.</b> Check 기준선을 넣으면 "있어야 했던 것"과 "진짜 있는 것"을 둘 다 갖게 된다.
 * 둘이 다르면 그것이 곧 어긋남이다 — 별도 스캔 없이 쓰기 경로가 지나가면서 알려준다. 이 값이
 * 지속적으로 오르면 재적재 시점을 판단할 근거가 된다.
 *
 * <p><b>리스 상실.</b> 리스를 잃는 세 갈래(재적재 중 상실, 반납 실패, 획득 도중 취소)는 어느
 * 것도 응답에 나타나지 않는다. 이 카운터가 없으면 로그를 사람이 읽을 때까지 아무도 모른다.
 */
@Slf4j
@RequiredArgsConstructor
public class ScimSyncMetrics implements DriftObserver, LockObserver {

    private final MeterRegistry registry;

    @Override
    public void observed(int extra, int missing) {
        if (extra > 0) {
            registry.counter("scim.drift.detected", "kind", "extra").increment(extra);
        }
        if (missing > 0) {
            registry.counter("scim.drift.detected", "kind", "missing").increment(missing);
        }
    }

    @Override
    public void acquireFinished(Duration waited, boolean contended) {
        registry.timer("scim.lock.wait").record(waited);
        if (contended) {
            registry.counter("scim.lock.contended").increment();
        }
    }

    @Override
    public void leaseLost(String reason) {
        log.warn("변경 락 리스를 잃었다: {}", reason);
        registry.counter("scim.lock.lease_lost").increment();
    }
}
