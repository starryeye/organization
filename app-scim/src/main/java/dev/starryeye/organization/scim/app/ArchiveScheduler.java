package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private final SnapshotArchiveUseCase archive;
    private final TupleSnapshotRepository snapshots;
    private final ObservationRegistry observations;

    /**
     * {@code Mono.defer} 로 감싸는 이유: 유스케이스가 Mono 를 반환하기 전에 동기 예외를 던지면
     * 그것이 스케줄러 메서드 밖으로 새어나가 에러 처리를 건너뛴다. 그러면 실패가 로그에 남지 않는다.
     */
    @Scheduled(cron = "${sync.archive-cron:0 0 3 * * *}")
    public void 스냅샷아카이빙() {
        관측하며실행("sync.scim.archive",
                Mono.defer(archive::execute)
                        .doOnNext(run -> log.info("스냅샷 아카이빙 완료: status={} snapshotId={}",
                                run.status(), run.snapshotId()))
                        .doOnError(error -> log.error("스냅샷 아카이빙이 예기치 않게 실패했다", error)));
    }

    /** DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다. */
    @Scheduled(cron = "${sync.purge-cron:0 0 4 * * *}")
    public void 만료스냅샷정리() {
        관측하며실행("sync.scim.purge",
                Mono.defer(snapshots::purgeExpired)
                        .doOnNext(count -> log.info("만료 스냅샷 정리 완료: {}건", count))
                        .doOnError(error -> log.error("만료 스냅샷 정리에 실패했다", error)));
    }

    /**
     * 예약 작업에 traceId 를 붙인다.
     *
     * <p><b>왜 필요한가.</b> traceId 는 들어오는 HTTP 요청에서 시작된다. 예약 작업에는 그런
     * 요청이 없으므로 아무것도 하지 않으면 이 잡들의 로그는 traceId 가 빈칸이다.
     *
     * <p><b>왜 {@code subscribe} 만으로는 안 되는가.</b> {@code @Scheduled} 메서드는 void 라
     * 구독만 걸고 즉시 반환한다. 관측 스코프는 그때 닫히는데 실제 작업은 그 뒤에 다른
     * 스레드에서 돈다. 그래서 관측을 Reactor Context 에 실어 보내야 한다.
     *
     * <p><b>로깅이 왜 전부 {@code work} 안에 있어야 하는가.</b> {@code contextWrite} 는
     * <em>위쪽</em>(먼저 선언된 연산자)에만 적용된다. 아래에 있는 {@code subscribe} 콜백은
     * 원래 컨텍스트를 보므로 거기서 로그를 찍으면 traceId 가 다시 빈칸이 된다.
     */
    private void 관측하며실행(String name, Mono<?> work) {
        Observation observation = Observation.createNotStarted(name, observations).start();
        work.doOnError(observation::error)
                .doFinally(signal -> observation.stop())
                .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation))
                .subscribe(ignored -> {
                }, error -> {
                    // 로깅은 이미 work 안에서 끝났다. 여기서 다시 찍으면 traceId 없이 중복된다.
                });
    }
}
