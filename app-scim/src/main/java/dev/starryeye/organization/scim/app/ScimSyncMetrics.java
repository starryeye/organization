package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.usecase.ArchiveDriftObserver;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase.DriftObserver;
import dev.starryeye.organization.core.usecase.LockObserver;
import dev.starryeye.organization.core.usecase.RevocationObserver;
import dev.starryeye.organization.scim.IgnoredAttributeObserver;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 설계 §7 의 지표 넷과 받아서 버린 속성(설계 2026-10-06 §3.3), 아카이빙 어긋남(설계 2026-10-20 §6.4), 커밋된 회수(설계 2026-10-27 §8)를 Micrometer 로 낸다.
 *
 * <table>
 *   <caption>설계 §7</caption>
 *   <tr><td>{@code scim.drift.detected}</td><td>Counter</td><td>{@code kind} = extra / missing</td></tr>
 *   <tr><td>{@code scim.lock.wait}</td><td>Timer</td><td>–</td></tr>
 *   <tr><td>{@code scim.lock.contended}</td><td>Counter</td><td>–</td></tr>
 *   <tr><td>{@code scim.lock.lease_lost}</td><td>Counter</td><td>–</td></tr>
 *   <tr><td>{@code scim.patch.ignored}</td><td>Counter</td><td>{@code attribute} = RFC 정규 이름 / other</td></tr>
 *   <tr><td>{@code scim.archive.drift}</td><td>Gauge</td><td>{@code kind} = extra / missing</td></tr>
 *   <tr><td>{@code scim.archive.drift.observed}</td><td>Gauge</td><td>–</td></tr>
 *   <tr><td>{@code scim.revocations}</td><td>Counter</td><td>{@code kind} = deactivate / delete_user / remove_member / delete_group</td></tr>
 * </table>
 *
 * <p><b>어긋남.</b> Check 기준선을 넣으면 "있어야 했던 것"과 "진짜 있는 것"을 둘 다 갖게 된다.
 * 둘이 다르면 그것이 곧 어긋남이다 — 별도 스캔 없이 쓰기 경로가 지나가면서 알려준다. 이 값이
 * 지속적으로 오르면 재적재 시점을 판단할 근거가 된다.
 *
 * <p><b>아카이빙 어긋남은 게이지다(설계 2026-10-20 §6.4).</b> 하루 한 번 상태 전체를 비교한 마지막 결과를 쥔다 — 쌓지 않는다. 남은 줄이 지워지면 다음 아카이빙에서
 * 0 으로 내려오고, 지우지 못했거나 빠진 채면 계속 남는다. {@code extra > 0} 이 이어지면 끝나지 않은 회수다. 시작 값은 0 이다.
 * {@code scim.drift.detected} 는 쓰기 요청이 지나가다 본 것을 쌓는 카운터라 뜻이 다르다. 아카이빙의 남은 줄 복구는 그 카운터를 올리지 않는다 —
 * 같은 줄을 이 게이지가 센다.
 *
 * <p><b>이 값은 인스턴스마다 따로다.</b> 하루 아카이빙은 클러스터에서 한 인스턴스만 돌리므로({@code DailyOnce}) 다른 인스턴스의 게이지는 낡은 값을 쥔다.
 * 그래서 {@code scim.archive.drift.observed}(이 인스턴스가 마지막으로 비교한 시각, epoch 초)를 함께 낸다 — 같은 때에 값을 둔다. 관찰한 적이 없으면
 * NaN 이라 "관찰했고 0건"(시각이 있고 어긋남이 0)과 구별된다. 경보는 이 시각이 최근 32시간 안인 계열만 봐야 한다(하루 창 03~08시의 재시도와 기한으로 두 날의 비교 간격이 최대 약 29.5시간 — 셈은 README).
 *
 * <p><b>리스 상실.</b> 리스를 잃는 갈래(작업·쓰기 도중 갱신 실패, 쓰기 직전·커밋 직전 재확인 실패, 반납 실패 —
 * {@link LockObserver})는 응답에 흔적이 없거나(반납 실패) 503 뿐이다. 이 카운터가 없으면 로그를 사람이 읽을 때까지 아무도 모른다.
 *
 * <p><b>회수.</b> SCIM 쓰기가 커밋한 회수를 종류별로 센다({@link RevocationObserver}, 설계 2026-10-27 §8). 대량 회수를 막지 않고 보이게만 한다(§17.4) —
 * 1시간 증가량이 평소보다 크게 늘면 경보를 울린다. 카운터 넷은 기동 때 0 으로 만들어 둔다 — Prometheus {@code increase()} 는 새 계열의 첫 값을 증가로
 * 세지 않으므로, 첫 회수 때 만들면 기동 직후의 첫 대량 회수가 증가량 0 으로 보인다.
 */
@Slf4j
public class ScimSyncMetrics implements DriftObserver, ArchiveDriftObserver, LockObserver, IgnoredAttributeObserver,
        RevocationObserver {

    private static final String DRIFT_DESCRIPTION =
            "마지막 아카이빙의 어긋남 줄 수 — kind=extra 는 장부에 있지만 상태가 요구하지 않는 줄(끝나지 않은 회수), kind=missing 은 상태가 요구하지만 장부에 없는 줄. "
                    + "이 인스턴스가 관찰한 값이다";

    private final MeterRegistry registry;
    private final Clock clock;
    /** 게이지가 약한 참조로 읽으므로 여기서 쥔다 — 놓으면 값이 사라진다. */
    private final AtomicLong archiveExtra = new AtomicLong();
    private final AtomicLong archiveMissing = new AtomicLong();
    /** 이 인스턴스가 아카이빙을 마지막으로 관찰한 시각(epoch 초). 아직 없으면 비어 있고 게이지는 NaN 이다. */
    private final AtomicReference<Long> archiveObservedAt = new AtomicReference<>();

    public ScimSyncMetrics(MeterRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    public ScimSyncMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        Gauge.builder("scim.archive.drift", archiveExtra, AtomicLong::get).tag("kind", "extra")
                .description(DRIFT_DESCRIPTION).register(registry);
        Gauge.builder("scim.archive.drift", archiveMissing, AtomicLong::get).tag("kind", "missing")
                .description(DRIFT_DESCRIPTION).register(registry);
        Gauge.builder("scim.archive.drift.observed", archiveObservedAt, at -> {
            Long at초 = at.get();
            return at초 == null ? Double.NaN : at초;
        })
                .description("이 인스턴스가 아카이빙을 마지막으로 관찰한 시각(epoch 초). 관찰한 적이 없으면 값이 없다 — scim.archive.drift 가 낡았는지 가르는 데 쓴다")
                .register(registry);
        // 회수 카운터 넷은 기동 때 0 으로 만든다 — 첫 회수 때 계열을 만들면 Prometheus increase() 가 그 첫 값을 증가로 세지 않아,
        // 기동 직후의 첫 대량 회수에 경보 식이 조용하다
        for (Kind kind : Kind.values()) {
            registry.counter("scim.revocations", "kind", kind.tag());
        }
    }

    /** 쓰기 요청이 본 어긋남 — 카운터에 쌓는다. 아래 {@code long} 짝({@link ArchiveDriftObserver})과 다르다. */
    @Override
    public void observed(int extra, int missing) {
        if (extra > 0) {
            registry.counter("scim.drift.detected", "kind", "extra").increment(extra);
        }
        if (missing > 0) {
            registry.counter("scim.drift.detected", "kind", "missing").increment(missing);
        }
    }

    /** 하루 아카이빙이 본 어긋남 — 게이지에 마지막 값으로 두고, 관찰한 때도 함께 둔다. */
    @Override
    public void observed(long extra, long missing) {
        archiveExtra.set(extra);
        archiveMissing.set(missing);
        archiveObservedAt.set(clock.instant().getEpochSecond());
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

    /** 태그 값은 {@code ScimRfcAttributes} 의 정규 이름이나 other 뿐이다 — 요청 문자열이 태그가 되지 않는다. */
    @Override
    public void ignored(Set<String> names) {
        names.forEach(name -> registry.counter("scim.patch.ignored", "attribute", name).increment());
    }

    /** 커밋된 회수 — 태그 값은 {@link Kind#tag()} 넷뿐이고 카운터 넷은 생성자가 0 으로 만들어 두었다. 0 은 올리지 않는다. */
    @Override
    public void revoked(Kind kind, int count) {
        if (count > 0) {
            registry.counter("scim.revocations", "kind", kind.tag()).increment(count);
        }
    }
}
