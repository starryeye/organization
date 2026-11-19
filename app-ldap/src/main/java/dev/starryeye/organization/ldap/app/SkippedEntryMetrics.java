package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.ldap.strategy.SkippedEntryObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * LDAP 읽기에서 건너뛴 엔트리 수를 카운터 {@code sync.entries.skipped} 로 낸다(설계 2026-11-19 S6). 태그 {@code search} 는 직원 {@code user}·조직
 * {@code group}·{@code member} 값 {@code member}, {@code reason} 은 {@code missing_id}·{@code duplicate_id}·{@code over_limit}·
 * {@code parent_not_found}·{@code unmatched_member} 다 — 열거 값마다 태그 문자열을 한 곳에서 못박는다.
 *
 * <p>일어날 수 있는 여덟 계열({@code user}×넷, {@code group}×셋, {@code member}×{@code unmatched_member})은 기동 때 0 으로 만들어 둔다 —
 * {@code ScimSyncMetrics} 의 {@code scim.writes.unsourced} 와 같은 까닭이다. Prometheus {@code increase()} 는 새 계열의 첫 값을 증가로 세지 않으므로,
 * 첫 건너뜀 때 계열을 만들면 기동 직후 첫 읽기의 건너뜀이 증가량 0 으로 보인다. 읽기마다 센다 — 같은 엔트리는 다음 읽기에도 다시 센다.
 */
@Component
public class SkippedEntryMetrics implements SkippedEntryObserver {

    private static final String 이름 = "sync.entries.skipped";
    private static final String 설명 = "LDAP 읽기에서 건너뛴 엔트리(member 는 값) 수 — 경고 \"… 건너뛰었다\" 와 같은 수. 읽기마다 센다";

    private final MeterRegistry registry;

    public SkippedEntryMetrics(MeterRegistry registry) {
        this.registry = registry;
        등록한다(검색.직원, 사유.식별_속성_없음, 사유.아이디_겹침, 사유.한도_넘음, 사유.부모_조직_없음);
        등록한다(검색.조직, 사유.식별_속성_없음, 사유.아이디_겹침, 사유.한도_넘음);
        등록한다(검색.member값, 사유.읽지_않은_member);
    }

    private void 등록한다(검색 search, 사유... reasons) {
        for (사유 reason : reasons) {
            카운터(search, reason);
        }
    }

    // 등록은 멱등이다 — 미리 만든 여덟이 아닌 조합도 처음 불릴 때 만들어 센다
    private Counter 카운터(검색 search, 사유 reason) {
        return Counter.builder(이름).description(설명)
                .tag("search", 태그(search)).tag("reason", 태그(reason)).register(registry);
    }

    @Override
    public void skipped(검색 search, 사유 reason, int count) {
        카운터(search, reason).increment(count);
    }

    // 열거에 값이 늘면 컴파일이 멈춘다 — 태그를 정하지 않은 채 지나가지 못한다
    private static String 태그(검색 search) {
        return switch (search) {
            case 직원 -> "user";
            case 조직 -> "group";
            case member값 -> "member";
        };
    }

    private static String 태그(사유 reason) {
        return switch (reason) {
            case 식별_속성_없음 -> "missing_id";
            case 아이디_겹침 -> "duplicate_id";
            case 한도_넘음 -> "over_limit";
            case 부모_조직_없음 -> "parent_not_found";
            case 읽지_않은_member -> "unmatched_member";
        };
    }
}
