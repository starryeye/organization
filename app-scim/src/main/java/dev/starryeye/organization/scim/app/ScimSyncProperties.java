package dev.starryeye.organization.scim.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app-scim 의 {@code sync.*} 설정. {@code ScimUseCaseConfig#scimSyncProperties} 가 빈 이름 {@code scimSyncProperties} 로 올리고,
 * {@code ArchiveScheduler} 의 {@code @Scheduled(cron = "#{@scimSyncProperties.archiveCron}")} 와 {@code ScimUseCaseConfig#syncJobs} 가 이 빈을
 * 읽는다(설계 2026-10-27 §5). 그래서 기본값의 출처는 이 클래스 하나이고 {@code application.yml} 은 덮어쓸 때만 쓴다 — 예전에는 {@code @Scheduled} 와
 * {@code @Value} 의 자리표시자가 같은 기본값을 따로 들고 있었다. cron 에 {@code "-"} 를 주면 그 작업이 꺼진다(스프링 {@code @Scheduled} 의 약속).
 */
@Getter
@Setter
@ConfigurationProperties("sync")
public class ScimSyncProperties {

    /**
     * 하루 아카이빙. 하루 창(03~08시) 안에서 매시 시도한다(설계 2026-10-27 §9). 하루 1회 표지({@code scim-archive})를 잡은 인스턴스만 돌고, SUCCEEDED 가
     * 아니면(FAILED·재적재 중이라 ABORTED) 표지를 풀어 다음 정각에 다시 돈다. 성공한 뒤의 시도는 표지를 보고 건너뛴다.
     *
     * <p><b>창이 UTC 자정을 넘지 않아야 한다.</b> 표지의 날짜는 UTC 라, 그래야 한 창이 한 날로 묶인다. 기본 창은 서버 시간대가 UTC 든 KST 든 이것을 지킨다
     * (KST 08시는 UTC 23시). 다른 시간대에서 창을 바꿀 때 이 조건을 지킨다. 어겨도 정확성은 깨지지 않고 창이 두 날로 갈려 하루에 두 번 돌 수 있을 뿐이다
     * (스냅샷 하나와 BatchCheck 를 더 쓴다).
     */
    private String archiveCron = "0 0 3-8 * * *";

    /**
     * 만료 스냅샷 정리. 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 보존 기간이 지난 스냅샷을 지우는 유일한 경로다. {@code "-"} 로 끄면 스냅샷(10만 명 규모에서 한
     * 벌에 압축 묶음 열몇 개·약 6MB)이 끝없이 쌓인다. 하루 창(04~08시) 안에서 매시 시도한다 — 표지({@code scim-purge})·UTC 자정 조건은 {@link #archiveCron} 과 같다(정리는 멱등).
     */
    private String purgeCron = "0 0 4-8 * * *";

    /**
     * 재적재·아카이빙 한 번의 전체 기한(설계 2026-09-29 §5, 2026-10-27 §9). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — 30분")로 기록한다(재적재는 락도
     * 푼다). 하루 1회 표지의 임대는 잡은 때 + 이 기한 + 5분이다.
     */
    private Duration jobTimeout = Duration.ofMinutes(30);
}
