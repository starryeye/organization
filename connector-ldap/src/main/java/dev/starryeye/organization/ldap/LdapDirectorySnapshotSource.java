package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.LdapTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * LDAP 은 블로킹 프로토콜이므로 boundedElastic 으로 격리한다.
 * 이벤트 루프에서 직접 호출하면 전체 애플리케이션이 멈춘다.
 */
@Slf4j
@RequiredArgsConstructor
public class LdapDirectorySnapshotSource implements DirectorySnapshotSource {

    /** 첫 재시도까지의 대기. OpenFGA 어댑터와 같은 값을 쓴다. */
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);

    private final LdapTemplate template;
    private final LdapMappingStrategy strategy;
    private final LdapProperties properties;

    /**
     * 실패하면 지수 백오프로 다시 읽는다 (설계 §9). 파이프라인의 다른 외부 호출은 이미
     * 보호돼 있다 — OpenFGA 는 배치 단위 재시도, DynamoDB 는 AWS SDK 내장. LDAP 만
     * 빠져 있으면 한 번의 일시적 장애가 하루치 동기화를 통째로 날린다(스케줄이 하루 1회다).
     *
     * <p><b>{@code retryWhen} 이 {@code subscribeOn} 위에 있다.</b> 재시도는 상위 구독을 다시
     * 여는 것이라, 이 순서여야 재시도도 boundedElastic 에서 돈다.
     *
     * <p><b>매 시도가 읽기를 처음부터 다시 한다.</b> {@code Mono.fromCallable} 이 {@code read} 를
     * 새로 부르므로, 페이징 도중 끊겨도 앞 시도의 부분 결과가 섞이지 않는다.
     *
     * <p><b>재시도를 다 쓰면 마지막 실패를 그대로 던진다.</b> 기본값은 {@code
     * RetryExhaustedException} 인데, 그 메시지가 {@code "Retries exhausted: 3/3"} 이라
     * 동기화 이력에 그 문장만 남는다. 실제로 벌어진 일이 <b>디렉터리가 잘린 목록을 줬다</b>
     * ({@code SizeLimitExceededException}) 여도 이력만 보는 운영자는 알 수 없다 — 이력은
     * 무엇이 잘못됐는지 말해야 하는 자리다.
     *
     * <p><b>{@link DirectoryDataException} 은 재시도하지 않는다.</b> 서버 장애나 네트워크 끊김 같은 일시적
     * 문제가 아니라 디렉터리의 데이터나 설정이 어긋난 것이다 — 정수가 아닌 계정 상태 값, 없는 필수 속성,
     * 해석할 수 없는 DN, 멤버가 하나도 대조되지 않는 설정({@link MemberMatchingFailedException}). 같은
     * 데이터를 다시 읽으면 같은 결과다. 그런데도 재시도에 맡기면 큰 디렉터리를 {@code maxRetries + 1} 번
     * 통째로 다시 읽고(대조 실패라면 매 회차 member 값마다 {@code log.warn} 을 수십만 줄 찍는다), 그 끝에 나온
     * 실패를 "일시적 장애" 로 보이게 한다 — 이력을 보는 운영자가 데이터나 설정을 고쳐야 할 문제를 재시도가 알아서
     * 해결해 줄 문제로 오인하게 만든다.
     */
    @Override
    public Mono<DirectorySnapshot> fetchAll() {
        return Mono.fromCallable(() -> strategy.read(template))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(Retry.backoff(properties.getMaxRetries(), RETRY_BACKOFF)
                        .filter(throwable -> !(throwable instanceof DirectoryDataException))
                        .doBeforeRetry(signal -> log.warn("LDAP 읽기 실패, 재시도 {}회차",
                                signal.totalRetries() + 1, signal.failure()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnNext(snapshot -> log.info("LDAP 에서 직원 {}명, 조직 {}개를 읽었다",
                        snapshot.users().size(), snapshot.groups().size()));
    }
}
