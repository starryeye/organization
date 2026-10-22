package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.port.SyncRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 디렉터리 그림을 받아들여 반영까지 마친 마지막 LDAP 회차의 끝난 시각을 게이지 {@code sync.last.success}(epoch 초, 태그 {@code source=LDAP})로 낸다
 * (설계 2026-10-20 §8). 회수가 얼마나 낡았는지 보는 경보의 재료다 — 예: "지금 − 값 &gt; 2시간".
 *
 * <p><b>SUCCEEDED 와 PARTIAL 만 센다.</b> 영구히 거절되는 줄 하나가 있으면 매 회차가 PARTIAL 이지만 회수는 계속 나간다. 그것을 "멈춤"으로 울리면 경보가
 * 늘 울린다. ABORTED(가드가 멈춘 회차, 일부 반영 포함)와 FAILED 는 세지 않는다 — 그 동안이 바로 경보가 울려야 하는 때다.
 *
 * <p><b>값은 실행 이력(DynamoDB)에서 읽는다.</b> 이 인스턴스가 돌린 회차만 보면 여러 인스턴스 중 락을 못 잡은 쪽은 영영 낡은 값이고, 재시작하면 값이 사라진다. 이력을
 * 읽으면 어느 인스턴스를 보든·재시작을 넘어서도 같은 값이다. 1분마다 읽어 그 사이는 쥐고 있는다(캐시).
 *
 * <p><b>값이 없으면 NaN 이다.</b> 성공·부분 회차가 이력에 없을 때(처음 뜬 직후, 보관 기간을 지났을 때)와 이력을 읽지 못했을 때다. 읽지 못했는데 지난 값을 그대로 쥐고 있으면
 * 이력이 끊긴 동안 경보가 "방금 성공"으로 보이거나 낡은 시각으로 거짓 안심을 준다. 읽기 실패는 WARN 한 줄을 남긴다.
 */
@Slf4j
@Component
public class LastSuccessGauge {

    /** 이력 읽기 한 번의 상한. 넘으면 읽지 못한 것으로 본다 — 다음 분의 갱신이 겹쳐 쌓이지 않게 한다. */
    private static final Duration 읽기_상한 = Duration.ofSeconds(30);

    private final SyncRunRepository runs;

    /** 마지막 성공 회차의 끝난 시각(epoch 초). 게이지가 약한 참조로 읽으므로 여기서 쥔다. 없으면 비어 있고 게이지는 NaN 이다. */
    private final AtomicReference<Long> 마지막_성공 = new AtomicReference<>();

    public LastSuccessGauge(SyncRunRepository runs, MeterRegistry registry) {
        this.runs = runs;
        Gauge.builder("sync.last.success", 마지막_성공, 초 -> {
                    Long 값 = 초.get();
                    return 값 == null ? Double.NaN : 값;
                })
                .tag("source", SyncSource.LDAP.name())
                .description("디렉터리 그림을 받아들여 반영까지 마친(SUCCEEDED·PARTIAL) 마지막 LDAP 회차의 끝난 시각(epoch 초). 없거나 읽지 못하면 값이 없다")
                .register(registry);
    }

    /** 1분마다 실행 이력에서 값을 다시 읽는다. 시작할 때도 한 번 바로 돈다. */
    @Scheduled(fixedDelay = 60_000)
    public void 주기적으로_갱신한다() {
        갱신한다().subscribe();
    }

    /**
     * 실행 이력의 최신 기록부터 훑어 처음 만나는 SUCCEEDED·PARTIAL 회차로 값을 바꾼다. 찾으면 거기서 읽기를 멈춘다. 없거나 읽기가 실패하면 값을 비운다.
     * 오류는 밖으로 내보내지 않는다 — 지표 갱신이 스케줄러를 흔들지 않는다.
     */
    Mono<Void> 갱신한다() {
        return Flux.defer(() -> runs.findRecent(Integer.MAX_VALUE))
                .filter(run -> 센다(run.status()) && run.finishedAt() != null)
                .next()
                .map(run -> run.finishedAt().getEpochSecond())
                .timeout(읽기_상한)
                .onErrorResume(error -> {
                    log.warn("마지막 성공 시각을 실행 이력에서 읽지 못해 sync.last.success 를 비운다: {}", error.toString());
                    return Mono.empty();
                })
                // 찾지 못했거나 읽지 못한 채 끝나면 값 없이(null) 불린다 — 그때 게이지가 NaN 으로 돌아간다
                .doOnSuccess(마지막_성공::set)
                .then();
    }

    private static boolean 센다(SyncStatus status) {
        return status == SyncStatus.SUCCEEDED || status == SyncStatus.PARTIAL;
    }
}
