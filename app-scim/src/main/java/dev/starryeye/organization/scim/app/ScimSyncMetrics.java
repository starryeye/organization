package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase.DriftObserver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 어긋남을 지표로 남긴다 (설계 §7).
 *
 * <p>Check 기준선을 넣으면 "있어야 했던 것"과 "진짜 있는 것"을 둘 다 갖게 된다. 둘이 다르면
 * 그것이 곧 어긋남이다 — 별도 스캔 없이 쓰기 경로가 지나가면서 알려준다.
 *
 * <p>이 값이 지속적으로 오르면 재적재 시점을 판단할 근거가 된다.
 */
@RequiredArgsConstructor
public class ScimSyncMetrics implements DriftObserver {

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
}
