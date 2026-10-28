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

    /** 하루 아카이빙(새벽 3시). 하루 1회 표지를 먼저 잡은 인스턴스만 돈다. */
    private String archiveCron = "0 0 3 * * *";

    /**
     * 만료 스냅샷 정리(새벽 4시). 스냅샷은 테이블 TTL 을 쓰지 않으므로 이 정리가 보존 기간이 지난 스냅샷을 지우는 유일한 경로다. {@code "-"} 로 끄면
     * 스냅샷(각 약 10만 아이템)이 끝없이 쌓인다.
     */
    private String purgeCron = "0 0 4 * * *";

    /**
     * 재적재 한 번의 전체 기한(설계 2026-09-29 §5). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — 30분")로 기록한 뒤 락을 푼다.
     */
    private Duration jobTimeout = Duration.ofMinutes(30);
}
