package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InterruptedRunCleanupTest {

    private static final Instant 지금 = Instant.parse("2026-09-29T03:00:00Z");

    @Test
    @DisplayName("지난 프로세스가 남긴 app-ldap RUNNING 기록을 FAILED(재시작으로 중단)로 닫는다")
    void 남은_RUNNING을_닫는다() {
        // given — 죽은 프로세스의 기록, 다른 앱(SCIM)의 기록, 이미 끝난 기록
        var runs = new FakeSyncRunRepository(지금);
        runs.seed(SyncRun.started("ldap-죽음", SyncSource.LDAP, SyncTrigger.MANUAL, 지금.minusSeconds(60)));
        runs.seed(SyncRun.started("scim-도는중", SyncSource.SCIM, SyncTrigger.REBUILD, 지금.minusSeconds(30)));
        runs.seed(SyncRun.started("ldap-끝남", SyncSource.LDAP, SyncTrigger.SCHEDULED, 지금.minusSeconds(3600))
                .finished(SyncOutcome.noChange(), 지금.minusSeconds(3500)));

        // when
        new InterruptedRunCleanup(runs).afterPropertiesSet();

        // then
        SyncRun 죽은것 = runs.findById("ldap-죽음").block();
        assertThat(죽은것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(죽은것.message()).isEqualTo("재시작으로 중단");
        assertThat(runs.findById("scim-도는중").block().status())
                .as("다른 앱의 기록은 그 앱의 일이다").isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.findById("ldap-끝남").block().status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("기록을 읽지 못해도 앱 시작을 막지 않는다")
    void 읽지_못해도_시작을_막지_않는다() {
        // given
        SyncRunRepository 고장난_저장소 = new SyncRunRepository() {
            @Override
            public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
                return Mono.error(new IllegalStateException("쓰지 않는다"));
            }

            @Override
            public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
                return Mono.error(new IllegalStateException("쓰지 않는다"));
            }

            @Override
            public Flux<SyncRun> findRecent(int limit) {
                return Flux.error(new IllegalStateException("DynamoDB 장애"));
            }

            @Override
            public Mono<SyncRun> findById(String runId) {
                return Mono.empty();
            }
        };

        // when, then
        assertThatCode(() -> new InterruptedRunCleanup(고장난_저장소).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
