package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.LdapTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LDAP 은 블로킹 프로토콜이므로 boundedElastic 으로 격리한다.
 * 이벤트 루프에서 직접 호출하면 전체 애플리케이션이 멈춘다.
 *
 * <p>재시도는 다음 주소부터 그림 전체를 읽는다 — JNDI 는 바인드 뒤의 실패를 다음 주소로 넘기지 않으므로, 바인드는 받고 검색에서 실패하는 DC 를 두 번째
 * 시도가 건너뛴다(설계 2026-11-14 F6). 재시도 k 번째는 {@code 시작_주소별_템플릿[k % n]} 을 쓴다.
 */
@Slf4j
public class LdapDirectorySnapshotSource implements DirectorySnapshotSource {

    /** 재시도 k 번째가 쓸 템플릿들 — k 번째는 주소 목록을 k 번째부터 돌린 컨텍스트 소스의 것이다. 하나면 돌리지 않는다. */
    private final List<LdapTemplate> 시작_주소별_템플릿;
    private final LdapMappingStrategy strategy;
    private final LdapProperties properties;

    public LdapDirectorySnapshotSource(
            List<LdapTemplate> 시작_주소별_템플릿, LdapMappingStrategy strategy, LdapProperties properties) {
        if (시작_주소별_템플릿.isEmpty()) {
            throw new IllegalArgumentException("시작 주소별 템플릿이 비었다");
        }
        this.시작_주소별_템플릿 = Collections.unmodifiableList(new ArrayList<>(시작_주소별_템플릿));
        this.strategy = strategy;
        this.properties = properties;
    }

    public LdapDirectorySnapshotSource(LdapTemplate template, LdapMappingStrategy strategy, LdapProperties properties) {
        // 템플릿을 쓰지 않는 단위 시험은 null 을 넘기므로 List.of 를 쓰지 않는다
        this(Collections.singletonList(template), strategy, properties);
    }

    /**
     * 실패하면 지수 백오프로 다시 읽는다 (설계 §9). 파이프라인의 다른 외부 호출은 이미
     * 보호돼 있다 — OpenFGA 는 배치 단위 재시도, DynamoDB 는 AWS SDK 내장. LDAP 만
     * 빠져 있으면 한 번의 일시적 장애가 한 회차의 동기화를 통째로 날린다.
     *
     * <p><b>대기 창은 수 분이다</b>(설계 2026-10-20 §8, {@link #재시도_규칙}). 몇 초짜리 창은 도메인 컨트롤러 재부팅 하나를 못 버텨,
     * 그 회차를 잃으면 퇴사 회수가 다음 주기까지 밀린다.
     *
     * <p><b>{@code retryWhen} 이 {@code subscribeOn} 위에 있다.</b> 재시도는 상위 구독을 다시
     * 여는 것이라, 이 순서여야 재시도도 boundedElastic 에서 돈다.
     *
     * <p><b>매 시도가 읽기를 처음부터 다시 한다.</b> {@code Mono.fromCallable} 이 {@code read} 를
     * 새로 부르므로, 페이징 도중 끊겨도 앞 시도의 부분 결과가 섞이지 않는다.
     *
     * <p><b>재시도를 다 쓰면 마지막 실패를 그대로 던진다.</b> 기본값은 {@code
     * RetryExhaustedException} 인데, 그 메시지가 {@code "Retries exhausted: 3/3"} 이라
     * 동기화 이력에 그 문장만 남는다. 실제로 벌어진 일이 <b>디렉터리가 응답하지 않았다</b>
     * (응답 시간 초과·연결 끊김) 여도 이력만 보는 운영자는 알 수 없다 — 이력은
     * 무엇이 잘못됐는지 말해야 하는 자리다.
     *
     * <p><b>{@link DirectoryDataException} 은 재시도하지 않는다.</b> 서버 장애나 네트워크 끊김 같은 일시적
     * 문제가 아니라 디렉터리의 데이터나 설정이 어긋난 것이다 — 정수가 아닌 계정 상태 값, 엔트리가 하나도 남지 않는 식별 속성,
     * 해석할 수 없는 DN, 멤버가 하나도 대조되지 않는 설정({@link MemberMatchingFailedException}). 같은
     * 데이터를 다시 읽으면 같은 결과다. 그런데도 재시도에 맡기면 큰 디렉터리를 {@code maxRetries + 1} 번
     * 통째로 다시 읽고(건너뛴 엔트리 요약 경고도 시도마다 한 줄씩 다시 남는다), 그 끝에 나온
     * 실패를 "일시적 장애" 로 보이게 한다 — 이력을 보는 운영자가 데이터나 설정을 고쳐야 할 문제를 재시도가 알아서
     * 해결해 줄 문제로 오인하게 만든다. 인증·권한·이름·크기 한도·필터 오류도 같다 — {@link #다시_읽어도_같은가}.
     *
     * <p><b>재시도는 다음 주소부터 그림 전체를 읽는다.</b> JNDI 는 바인드 뒤의 실패를 다음 주소로 넘기지 않는다(설계 2026-11-14 F6) — 바인드는 받고 검색은
     * 실패하는 DC 가 첫 주소면 늘 같은 DC 에서 재시도하게 된다. 그래서 재시도 k 번째는 주소 목록을 k 번째부터 돌린 템플릿으로 읽고, 첫 주소가 아닌 곳에서
     * 시작하면 WARN 을 남긴다(F7). 시도 수는 {@code fetchAll} 을 부를 때마다 새로 센다 — 다음 회차는 설정 순서부터다.
     */
    @Override
    public Mono<DirectorySnapshot> fetchAll() {
        AtomicInteger 시도 = new AtomicInteger();
        return Mono.defer(() -> {
                    int k = 시도.getAndIncrement();
                    LdapTemplate 템플릿 = 시작_주소별_템플릿.get(k % 시작_주소별_템플릿.size());
                    if (k > 0 && 시작_주소별_템플릿.size() > 1) {
                        log.warn("LDAP 읽기를 다음 주소부터 다시 한다 — 시작 주소 {}",
                                LdapUrls.첫_주소(템플릿.getContextSource()).orElse("?"));
                    }
                    return Mono.fromCallable(() -> strategy.read(템플릿));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(재시도_규칙(properties))
                .doOnNext(snapshot -> log.info("LDAP 에서 직원 {}명, 조직 {}개를 읽었다",
                        snapshot.users().size(), snapshot.groups().size()));
    }

    /**
     * 읽기 재시도 규칙. {@code ldap.max-retries} 번, 첫 대기 {@code ldap.retry-min-backoff} 에서 두 배씩 늘려 {@code ldap.retry-max-backoff} 에서
     * 멈춘다(기본 5번·10초~2분 — 대기만 약 2~6분, 지터 때문에 달라진다). {@link #다시_읽어도_같은가} 인 실패는 재시도하지 않고, 다 쓰면 마지막 실패를 그대로 던진다.
     */
    static RetryBackoffSpec 재시도_규칙(LdapProperties properties) {
        return Retry.backoff(properties.getMaxRetries(), properties.getRetryMinBackoff())
                .maxBackoff(properties.getRetryMaxBackoff())
                .filter(throwable -> !다시_읽어도_같은가(throwable))
                .doBeforeRetry(signal -> log.warn("LDAP 읽기 실패, 재시도 {}회차",
                        signal.totalRetries() + 1, signal.failure()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /**
     * 다시 읽어도 같은 실패인가(설계 2026-10-05 §4.1, 점검 S23). 데이터·설정이 어긋났거나({@link DirectoryDataException}), LDAP 표준(RFC 4511) 결과 코드가
     * "요청 자체가 틀렸다" 는 것이다 — 49 인증 실패, 50 권한 없음, 32 없는 이름(검색 베이스 오타), 34 DN 문법, 4 크기 한도, 그리고 보내기 전에 거절된 필터 문법.
     * Spring LDAP 이 결과 코드를 예외 종류로 옮겨 준다. 그 밖은 — 모르는 종류까지 — 일시 장애로 보고 재시도한다: 한 번의 일시 장애로 회차가 실패하면 회수가 다음 주기(기본 매시 정각)까지 늦어지므로, 재시도로 그 회차를 살린다.
     * 재시도 창은 {@code ldap.max-retries}·{@code ldap.retry-min-backoff}·{@code ldap.retry-max-backoff}(기본 5번·10초~2분, 설계 2026-10-20 §8)다.
     */
    static boolean 다시_읽어도_같은가(Throwable 실패) {
        return 실패 instanceof DirectoryDataException
                || 실패 instanceof org.springframework.ldap.AuthenticationException
                || 실패 instanceof org.springframework.ldap.NoPermissionException
                || 실패 instanceof org.springframework.ldap.NameNotFoundException
                || 실패 instanceof org.springframework.ldap.InvalidNameException
                || 실패 instanceof org.springframework.ldap.SizeLimitExceededException
                || 실패 instanceof org.springframework.ldap.InvalidSearchFilterException;
    }
}
