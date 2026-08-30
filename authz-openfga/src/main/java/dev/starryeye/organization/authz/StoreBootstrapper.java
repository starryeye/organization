package dev.starryeye.organization.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * store 이름으로 storeId 를 해석하고 인가 모델을 등록한다.
 *
 * <p>앱의 어느 곳도 storeId 나 modelId 를 알지 못한다. 설정에는 store-name 만 있고,
 * write 호출에는 authorization_model_id 를 넘기지 않아 서버가 최신 모델을 쓴다.
 */
@Slf4j
public class StoreBootstrapper {

    private static final String MODEL_RESOURCE = "authorization-model.json";

    private final OpenFgaProperties properties;
    private final AtomicReference<OpenFgaClient> clientRef = new AtomicReference<>();

    /**
     * storeId 가 없는 클라이언트. store 를 찾거나 만들 때만 쓴다.
     *
     * <p>전에는 부를 때마다 새로 만들었다. {@code findStoreIdByName} 은 재귀 페이징이라
     * <b>페이지마다</b> 하나씩 생겼고, 첫 부트스트랩 한 번에 여러 개가 만들어졌다.
     * 이 클라이언트는 어떤 store 에도 묶여 있지 않아 상태가 없으므로 재사용해도 안전하고,
     * {@code recreateStore()} 가 무효화할 이유도 없다 —
     * 무효화 대상은 storeId 에 묶인 {@code clientRef} 쪽이다.
     */
    private final AtomicReference<OpenFgaClient> storelessClientRef = new AtomicReference<>();
    private final AtomicReference<String> storeIdRef = new AtomicReference<>();

    /**
     * {@link #clientFor(String)} 이 돌려주는 읽기 전용 client 를 storeId 별로 재사용한다.
     * {@code clientRef} 와는 완전히 별개의 캐시이며 {@code recreateStore()} 가 건드리지 않는다.
     */
    private final ConcurrentMap<String, OpenFgaClient> readOnlyClients = new ConcurrentHashMap<>();

    /**
     * 진행 중인 해석을 공유하기 위한 in-flight Mono. resolveStore() 를 동시에 여러 곳에서
     * 호출해도 실제 findStoreIdByName → createStore → attachAndWriteModel 파이프라인은
     * 한 번만 구성/구독되고, 모든 호출자가 같은 결과를 공유한다.
     *
     * <p>성공하면 storeIdRef 가 채워져 이후 호출은 이 필드를 아예 거치지 않는다(빠른 경로).
     * 실패하면 doFinally 에서 이 필드를 비워, 다음 호출이 캐시된 에러를 영원히 받는 대신
     * 새로 시도할 수 있게 한다.
     */
    private final AtomicReference<Mono<String>> resolutionRef = new AtomicReference<>();

    public StoreBootstrapper(OpenFgaProperties properties) {
        this.properties = properties;
    }

    /** 이미 해석했으면 캐시된 storeId 를 준다. 없으면 찾고, 그래도 없으면 만든다. */
    public Mono<String> resolveStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.defer(this::sharedResolution);
    }

    /**
     * 헬스체크 전용 read-only 조회. 캐시된 storeId 가 있으면 그것을 쓰고, 없으면
     * {@link #findStoreIdByName()} 으로 store 존재 여부만 확인한다 — {@link #resolveStore()}
     * 와 달리 store 를 만들거나 인가 모델을 쓰지 않는다.
     *
     * <p>헬스 프로브는 관찰만 해야지 인프라를 만들면 안 된다. 인증 없는
     * {@code GET /actuator/health} 는 k8s 프로브·로드밸런서·오타난 {@code openfga.store-name}
     * 설정 등 무엇이든 호출할 수 있는데, 이 경로가 {@link #resolveStore()} 를 타면 예열이
     * 아직 안 됐거나 실패한 cold 인스턴스에서 store 를 만들고 인가 모델을 써버린다 —
     * 오타난 이름은 빈 store 를 새로 만든 채 조용히 UP 을 보고하게 된다. store 가 없으면
     * {@link Mono#empty()} 를 그대로 돌려주고, DOWN 으로의 번역은 호출자(헬스 인디케이터)
     * 몫이다.
     */
    public Mono<String> findExistingStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return findStoreIdByName();
    }

    /**
     * 진행 중인 해석이 있으면 그것을 공유하고, 없으면 하나만 새로 만들어 등록한다.
     * compareAndSet 으로 등록 경쟁의 승자만 실제 파이프라인을 구독하게 하고,
     * 패자는 승자가 등록한 Mono 를 그대로 반환해 같은 storeId 를 받는다.
     */
    private Mono<String> sharedResolution() {
        Mono<String> existing = resolutionRef.get();
        if (existing != null) {
            return existing;
        }

        Mono<String> created = findStoreIdByName()
                .switchIfEmpty(Mono.defer(this::createStore))
                .flatMap(this::attachAndWriteModel)
                .doFinally(signal -> resolutionRef.set(null))
                .cache();

        if (resolutionRef.compareAndSet(null, created)) {
            return created;
        }
        return resolutionRef.get();
    }

    /**
     * rebuild(store 모드) 전용. store 를 지우고 같은 이름으로 다시 만든다.
     *
     * <p>store 를 지우는 순간부터 새로 다 만들어질 때까지는 storeIdRef 와 clientRef 가
     * 모두 비어 있는 창이 생긴다. 이 메서드가 등장하기 전에는 그 창으로 동시에 들어온
     * {@link #resolveStore()} 호출이 "아직 해석 안 됐다"고 오판해 <b>자기 것대로 또
     * store 를 하나 더 만들어버렸다</b> — OpenFGA 가 이름 유일성을 강제하지 않기 때문에
     * 조용히 같은 이름의 store 가 두 개가 된다.
     *
     * <p>{@link #sharedResolution()} 이 최초 동시 호출을 다루는 것과 같은 메커니즘
     * ({@code resolutionRef} 에 진행 중인 Mono 를 게시해 뒤따르는 호출이 합류하게 하는 것)
     * 을 파괴 작업이 시작되기 <b>전에</b> 적용한다. 그러면 storeIdRef 가 null 이 되는
     * 순간과 겹치는 {@code resolveStore()} 호출도 {@code resolutionRef} 에서 이 재구성
     * Mono 를 그대로 보고 합류하지, 자기 것을 새로 만들지 않는다.
     *
     * <p><b>"현재 store 찾기" 단계는 반드시 {@link #findStoreIdByName()} 을 직접 써야
     * 한다 — {@link #resolveStore()} 를 타면 안 된다.</b> cold 상태(storeIdRef 가
     * 아직 null 인, 배포 직후 아무도 resolveStore() 를 먼저 부르지 않은 상태)에서
     * resolveStore() 를 부르면 {@link #sharedResolution()} 으로 넘어가는데, 그 시점엔
     * 이미 이 메서드가 자기 자신(recreation)을 resolutionRef 에 게시해 둔 뒤다.
     * sharedResolution() 은 "진행 중인 해석이 있다"며 그 게시물을 그대로 돌려주므로
     * recreation 은 자기 자신의 완료를 기다리는 자기 참조가 되어 영원히 끝나지 않는다.
     * findStoreIdByName() 은 resolutionRef 를 전혀 건드리지 않으므로 이 순환이 없다.
     * store 가 아직 없으면(cold) 빈 Mono 를 내며, 그 경우 삭제 단계를 건너뛴다.
     *
     * <p>{@code resolutionRef} 게시는 {@link #sharedResolution()} 과 대칭으로
     * compareAndSet 을 쓴다. 동시에 들어온 두 번째 recreateStore() 호출은 자기 것을
     * 새로 시작하는 대신 먼저 게시된 재구성에 합류한다 — 이 메서드는 호출자가 직렬화를
     * 보장한다는 가정(현재는 {@code SyncExecutionGuard})에만 기대지 않는다.
     *
     * <p>실패해도 {@code doFinally} 가 {@code resolutionRef} 를 비워 다음 시도가 캐시된
     * 에러 대신 새로 재구성을 시도할 수 있다 — {@link #sharedResolution()} 과 동일한
     * "실패는 영구히 캐시되지 않는다" 성질을 유지한다.
     */
    public Mono<String> recreateStore() {
        Mono<String> recreation = findStoreIdByName()
                .flatMap(this::deleteStoreById)
                .then(Mono.fromRunnable(() -> {
                    storeIdRef.set(null);
                    clientRef.set(null);
                }))
                .then(Mono.defer(this::createStore))
                .flatMap(this::attachAndWriteModel)
                .doFinally(signal -> resolutionRef.set(null))
                .cache();

        // 파괴 작업(store 삭제 → 캐시 무효화)이 시작되기 전에 먼저 게시해야 한다.
        // 그래야 그 창으로 겹쳐 들어오는 resolveStore() 가 이 Mono 에 합류한다.
        // compareAndSet 은 동시에 들어온 두 번째 recreateStore() 호출도 같은 방식으로
        // 합류시킨다.
        if (resolutionRef.compareAndSet(null, recreation)) {
            return recreation;
        }
        return resolutionRef.get();
    }

    /** storeId 로 직접 client 를 만들어 지운다. clientRef 캐시 상태에 기대지 않는다. */
    private Mono<Void> deleteStoreById(String storeId) {
        return Mono.fromCallable(() -> newClient(storeId))
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.deleteStore();
                    } catch (Exception e) {
                        throw new IllegalStateException("store 삭제 실패", e);
                    }
                }))
                .then();
    }

    public OpenFgaClient client() {
        OpenFgaClient client = clientRef.get();
        if (client == null) {
            throw new IllegalStateException("store 가 아직 해석되지 않았다. resolveStore() 를 먼저 호출하라");
        }
        return client;
    }

    /**
     * storeId 에 묶인 읽기 전용 client 를 준다. {@code clientRef}/{@code storeIdRef} 캐시를
     * 읽지도 쓰지도 않는다.
     *
     * <p>{@link #findExistingStore()} 는 store 존재만 확인하고 storeId 를 돌려줄 뿐,
     * {@code clientRef} 를 채우지 않는다({@link #client()} 는 {@code resolveStore()}/
     * {@code recreateStore()} 가 인가 모델까지 써야만 채워지는 캐시에 기댄다). 그래서
     * "store 는 있지만 이 프로세스가 아직 resolveStore() 를 부른 적 없는" 상태에서
     * {@code findExistingStore()} 뒤에 {@code client()} 를 쓰면 실제로는 store 가 있는데도
     * "아직 해석되지 않았다" 로 잘못 실패한다.
     *
     * <p>이 메서드는 {@code clientRef}/{@code storeIdRef} 를 전혀 건드리지 않으므로
     * {@code recreateStore()} 가 진행하는 "캐시 비우기 → 새 client 로 교체" 와 절대 경합하지
     * 않는다 — 읽기 전용 조회가 그 캐시를 갱신하거나, 캐시 교체 도중의 값을 관찰해 오래된
     * client 를 붙들 수 있는 경로 자체가 없다.
     *
     * <p><b>storeId 별로 client 를 재사용한다.</b> 전에는 호출마다 새로 만들었는데, 그 근거로
     * 든 {@link #findStoreIdByName()} · {@link #deleteStoreById(String)} 은 동기화나 재적재당
     * 한 번 도는 경로다. 이쪽은 <b>응답 한 줄당 한 번</b> 돈다 — 경로 200개짜리 직원 상세
     * 하나가 인증 없는 GET 한 번에 커넥션 풀과 셀렉터 스레드를 200벌 만든다.
     *
     * <p>캐시를 storeId 로 키잡는 것이 위의 격리 성질을 그대로 지킨다. {@code recreateStore()}
     * 는 새 store 를 새 id 로 만들므로 그 뒤의 조회는 다른 키를 찾고, 이전 id 에 붙어 있던
     * client 를 실수로 물려받을 수 없다. 엔트리는 이 프로세스가 본 서로 다른 storeId 마다
     * 하나이며 — 즉 재적재 횟수만큼 — 사실상 손에 꼽는다.
     */
    public OpenFgaClient clientFor(String storeId) {
        return readOnlyClients.computeIfAbsent(storeId, this::newClient);
    }

    /**
     * store 목록을 continuation token 이 소진될 때까지 전부 순회한 뒤에 이름을 찾는다.
     *
     * <p>{@code listStores()} 는 한 페이지(OpenFGA 기본 50개)만 반환한다. 공유 OpenFGA
     * 서버에 store 가 그보다 많으면, 첫 페이지에 없다고 곧장 {@code createStore} 로
     * 넘어가는 것은 위험하다 — 실제로는 다음 페이지에 이름이 이미 존재하는 store 가 있는데
     * 못 찾은 것뿐이고, OpenFGA 는 이름 유일성을 강제하지 않으므로 조용히 두 번째
     * store 가 만들어진다. 이후 이 앱은 새로 만든 빈 store 에 튜플을 쓰고, 기존 소비자는
     * 여전히 첫 번째 store 를 조회하는 완전한 인가 실패로 이어진다.
     */
    private Mono<String> findStoreIdByName() {
        return findStoreIdByName(null, new ArrayList<>());
    }

    private Mono<String> findStoreIdByName(String continuationToken, List<Store> accumulated) {
        return Mono.fromCallable(this::storelessClient)
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        ClientListStoresOptions options = new ClientListStoresOptions();
                        if (continuationToken != null && !continuationToken.isBlank()) {
                            options.continuationToken(continuationToken);
                        }
                        return client.listStores(options);
                    } catch (Exception e) {
                        throw new IllegalStateException("store 목록 조회 실패", e);
                    }
                }))
                .flatMap(response -> {
                    accumulated.addAll(response.getStores());
                    String next = response.getContinuationToken();
                    if (next != null && !next.isBlank()) {
                        return findStoreIdByName(next, accumulated);
                    }
                    return resolveUniqueMatch(accumulated);
                });
    }

    /**
     * 페이지를 전부 모은 뒤에야 판단한다. 같은 이름의 store 가 둘 이상이면 임의로 하나를
     * 골라 쓰는 대신 에러로 멈춘다 — 이미 이 문제(경쟁으로 인한 중복 store 생성)를 한 번
     * 겪었다는 신호이므로, 아무거나 골라 쓰면 상황을 더 악화시킬 뿐이다. 사람이 개입해
     * 정리해야 한다.
     */
    private Mono<String> resolveUniqueMatch(List<Store> allStores) {
        List<Store> matches = allStores.stream()
                .filter(store -> properties.getStoreName().equals(store.getName()))
                .toList();

        if (matches.size() > 1) {
            return Mono.error(new IllegalStateException(
                    "OpenFGA 에 이름이 '%s' 인 store 가 %d개 있다. 이름 유일성이 깨진 상태이므로 "
                            .formatted(properties.getStoreName(), matches.size())
                            + "임의로 하나를 고르는 대신 멈춘다. 수동으로 정리해야 한다."));
        }
        if (matches.isEmpty()) {
            return Mono.empty();
        }
        return Mono.just(matches.get(0).getId());
    }

    private Mono<String> createStore() {
        log.info("OpenFGA store '{}' 을 생성한다", properties.getStoreName());
        return Mono.fromCallable(this::storelessClient)
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.createStore(new CreateStoreRequest().name(properties.getStoreName()));
                    } catch (Exception e) {
                        throw new IllegalStateException("store 생성 실패", e);
                    }
                }))
                .map(response -> response.getId());
    }

    private Mono<String> attachAndWriteModel(String storeId) {
        return Mono.fromCallable(() -> {
                    OpenFgaClient client = newClient(storeId);
                    clientRef.set(client);
                    storeIdRef.set(storeId);
                    return client;
                })
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.writeAuthorizationModel(readModel());
                    } catch (Exception e) {
                        throw new IllegalStateException("인가 모델 등록 실패", e);
                    }
                }))
                .doOnNext(response -> log.info("OpenFGA 인가 모델을 등록했다"))
                .thenReturn(storeId);
    }

    private WriteAuthorizationModelRequest readModel() {
        try (InputStream in = new ClassPathResource(MODEL_RESOURCE).getInputStream()) {
            return new ObjectMapper().readValue(in, WriteAuthorizationModelRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(MODEL_RESOURCE + " 를 읽을 수 없다", e);
        }
    }

    /** 없으면 만들고, 있으면 그대로 쓴다. 경합해서 둘이 만들어져도 한쪽만 남고 나머지는 버려진다. */
    private OpenFgaClient storelessClient() {
        OpenFgaClient existing = storelessClientRef.get();
        if (existing != null) {
            return existing;
        }
        OpenFgaClient created = newClient(null);
        return storelessClientRef.compareAndSet(null, created) ? created : storelessClientRef.get();
    }

    private OpenFgaClient newClient(String storeId) {
        try {
            ClientConfiguration configuration = new ClientConfiguration().apiUrl(properties.getApiUrl());
            if (storeId != null) {
                configuration.storeId(storeId);
            }
            return new OpenFgaClient(configuration);
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA 클라이언트 생성 실패", e);
        }
    }
}
