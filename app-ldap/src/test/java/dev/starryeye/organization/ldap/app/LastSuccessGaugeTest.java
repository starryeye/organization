package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마지막 성공 시각 게이지(설계 2026-10-20 §8). 회수가 얼마나 낡았는지 보는 경보의 재료다 — "지금 − 값 > 2.5시간".
 * SUCCEEDED 와 PARTIAL 만 센다: 영구히 거절되는 줄 하나가 있으면 매 회차가 PARTIAL 이지만 회수는 계속 나가므로, 그것을 멈춤으로 울리면 경보가 늘 울린다.
 * ABORTED(가드가 멈춘 회차)와 FAILED 는 세지 않는다 — 그 동안이 경보가 울려야 하는 때다.
 */
class LastSuccessGaugeTest {

    private static final Instant 기준 = Instant.parse("2026-10-23T03:00:00Z");

    /** 읽기를 깨뜨릴 수 있는 가짜 실행 기록 저장소. */
    private static class 깨질_수_있는_기록소 extends FakeSyncRunRepository {

        private volatile boolean 깨졌다;

        깨질_수_있는_기록소(Instant now) {
            super(now);
        }

        void 깨뜨린다() {
            깨졌다 = true;
        }

        @Override
        public Flux<SyncRun> findRecent(int limit) {
            return 깨졌다
                    ? Flux.error(new IllegalStateException("DynamoDB 응답 없음(테스트)"))
                    : super.findRecent(limit);
        }
    }

    private 깨질_수_있는_기록소 runs;
    private MeterRegistry registry;
    private LastSuccessGauge gauge;

    @BeforeEach
    void setUp() {
        runs = new 깨질_수_있는_기록소(기준);
        registry = new SimpleMeterRegistry();
        gauge = new LastSuccessGauge(runs, registry);
    }

    /** 끝난 기록 하나를 심는다. 심은 순서가 곧 시간 순서다(가짜 저장소는 마지막에 심은 것을 가장 최신으로 낸다). */
    private void 끝난_회차를_심는다(String runId, SyncStatus status, String 끝난_시각) {
        Instant 끝 = Instant.parse(끝난_시각);
        runs.seed(SyncRun.builder()
                .runId(runId)
                .source(SyncSource.LDAP)
                .trigger(SyncTrigger.SCHEDULED)
                .startedAt(끝.minusSeconds(30))
                .finishedAt(끝)
                .status(status)
                .build());
    }

    private double 게이지_값() {
        return registry.get("sync.last.success").tag("source", "LDAP").gauge().value();
    }

    private static double 에포크_초(String 시각) {
        return Instant.parse(시각).getEpochSecond();
    }

    @Test
    @DisplayName("가장 최근의 성공 또는 부분 회차의 끝난 시각(epoch 초)을 낸다 — 그보다 새 FAILED 는 건너뛴다")
    void 가장_최근의_성공_또는_부분_회차_시각을_낸다() {
        // given — 오래된 쪽부터 SUCCEEDED, PARTIAL, FAILED. 가장 새 것은 FAILED 다
        끝난_회차를_심는다("run-1", SyncStatus.SUCCEEDED, "2026-10-23T01:00:12Z");
        끝난_회차를_심는다("run-2", SyncStatus.PARTIAL, "2026-10-23T02:00:34Z");
        끝난_회차를_심는다("run-3", SyncStatus.FAILED, "2026-10-23T03:00:56Z");

        // when
        gauge.갱신한다().block();

        // then — PARTIAL 의 끝난 시각이다
        assertThat(게이지_값()).isEqualTo(에포크_초("2026-10-23T02:00:34Z"));
    }

    @Test
    @DisplayName("성공한 회차가 없으면 값이 없다(NaN)")
    void 성공한_회차가_없으면_값이_없다() {
        // given — 기록은 있으나 모두 실패다
        끝난_회차를_심는다("run-1", SyncStatus.FAILED, "2026-10-23T01:00:00Z");

        // when
        gauge.갱신한다().block();

        // then
        assertThat(게이지_값()).isNaN();
    }

    @Test
    @DisplayName("갱신하기 전에도 게이지는 이미 달려 있고 값이 없다 — 시작 직후 경보가 0 을 '방금 성공'으로 읽지 않는다")
    void 갱신하기_전에는_값이_없다() {
        // given, when — 한 번도 갱신하지 않았다

        // then
        assertThat(게이지_값()).isNaN();
    }

    @Test
    @DisplayName("가드로 멈춘 회차(ABORTED)는 세지 않는다")
    void 가드로_멈춘_회차는_세지_않는다() {
        // given — 가드가 멈춘 회차뿐이다
        끝난_회차를_심는다("run-1", SyncStatus.ABORTED, "2026-10-23T02:00:00Z");

        // when
        gauge.갱신한다().block();

        // then
        assertThat(게이지_값()).isNaN();
    }

    @Test
    @DisplayName("끝나지 않은 회차(RUNNING)는 세지 않는다 — 그 앞의 성공을 낸다")
    void 아직_도는_회차는_세지_않는다() {
        // given
        끝난_회차를_심는다("run-1", SyncStatus.SUCCEEDED, "2026-10-23T01:00:00Z");
        runs.seed(SyncRun.started("run-2", SyncSource.LDAP, SyncTrigger.SCHEDULED, Instant.parse("2026-10-23T02:00:00Z")));

        // when
        gauge.갱신한다().block();

        // then
        assertThat(게이지_값()).isEqualTo(에포크_초("2026-10-23T01:00:00Z"));
    }

    @Test
    @DisplayName("새 성공이 생기면 다음 갱신에서 값이 앞으로 간다")
    void 새_성공이_생기면_값이_앞으로_간다() {
        // given
        끝난_회차를_심는다("run-1", SyncStatus.SUCCEEDED, "2026-10-23T01:00:00Z");
        gauge.갱신한다().block();
        끝난_회차를_심는다("run-2", SyncStatus.SUCCEEDED, "2026-10-23T02:00:00Z");

        // when
        gauge.갱신한다().block();

        // then
        assertThat(게이지_값()).isEqualTo(에포크_초("2026-10-23T02:00:00Z"));
    }

    @Test
    @DisplayName("실행 기록을 읽지 못하면 낡은 값을 쥐고 있지 않고 값이 없다(NaN)로 돌아가며, 갱신은 오류를 밖으로 던지지 않는다")
    void 읽지_못하면_값이_없다() {
        // given — 한 번 읽어 값이 있다가, 저장소가 실패한다
        끝난_회차를_심는다("run-1", SyncStatus.SUCCEEDED, "2026-10-23T01:00:00Z");
        gauge.갱신한다().block();
        assertThat(게이지_값()).isEqualTo(에포크_초("2026-10-23T01:00:00Z"));
        runs.깨뜨린다();

        // when — 오류가 새어 나오면 block() 이 던진다
        gauge.갱신한다().block();

        // then
        assertThat(게이지_값()).isNaN();
    }

    @Test
    @DisplayName("1분마다 갱신하도록 예약돼 있다")
    void 일_분마다_갱신하도록_예약돼_있다() throws Exception {
        // given, when
        var 예약 = LastSuccessGauge.class.getMethod("주기적으로_갱신한다").getAnnotation(Scheduled.class);

        // then
        assertThat(예약).isNotNull();
        assertThat(예약.fixedDelay()).isEqualTo(60_000L);
    }
}
