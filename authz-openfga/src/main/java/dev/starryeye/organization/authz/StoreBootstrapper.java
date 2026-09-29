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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * store 이름으로 storeId 를 해석하고 인가 모델을 등록한다.
 *
 * <p>앱의 어느 곳도 storeId 나 modelId 를 알지 못한다. 설정에는 store-name 만 있고,
 * write 호출에는 authorization_model_id 를 넘기지 않아 서버가 최신 모델을 쓴다.
 *
 * <p><b>store 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).</b> 재적재도 store 를 지우고 다시 만들지 않는다 — 장부 안에서
 * 청소한다. 이 클래스가 store 를 지우는 경우는 하나뿐이다: 동시에 처음 뜬 인스턴스끼리 같은 이름 store 를 둘 만들었을 때 늦게 만든
 * 쪽이 자기 것을 지운다({@link #convergeAfterCreate}).
 */
@Slf4j
public class StoreBootstrapper {

    private static final String MODEL_RESOURCE = "authorization-model.json";

    /** 같은 이름 store 가 여럿일 때 이기는 쪽: 가장 먼저 만들어진 것, 같으면 id 사전순. */
    private static final Comparator<Store> 먼저_만든_순 = Comparator
            .comparing(Store::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Store::getId);

    private final OpenFgaProperties properties;
    private final AtomicReference<OpenFgaClient> clientRef = new AtomicReference<>();

    /**
     * storeId 가 없는 클라이언트. store 를 찾거나 만들 때만 쓴다.
     *
     * <p>전에는 부를 때마다 새로 만들었다. store 목록 조회는 재귀 페이징이라 <b>페이지마다</b> 하나씩 생겼고,
     * 첫 부트스트랩 한 번에 여러 개가 만들어졌다. 이 클라이언트는 어떤 store 에도 묶여 있지 않아 상태가 없으므로
     * 재사용해도 안전하다.
     */
    private final AtomicReference<OpenFgaClient> storelessClientRef = new AtomicReference<>();
    private final AtomicReference<String> storeIdRef = new AtomicReference<>();

    /** {@link #clientFor(String)} 이 돌려주는 client 를 storeId 별로 재사용한다. {@code clientRef} 와는 별개의 캐시다. */
    private final ConcurrentMap<String, OpenFgaClient> readOnlyClients = new ConcurrentHashMap<>();

    /**
     * 진행 중인 해석을 공유하기 위한 in-flight Mono. resolveStore() 를 동시에 여러 곳에서
     * 호출해도 실제 찾기 → 만들기 → 수렴 → 모델 등록 파이프라인은 한 번만 구성/구독되고, 모든 호출자가 같은 결과를 공유한다.
     *
     * <p>성공하면 storeIdRef 가 채워져 이후 호출은 이 필드를 아예 거치지 않는다(빠른 경로).
     * 실패하면 doFinally 에서 이 필드를 비워, 다음 호출이 캐시된 에러를 영원히 받는 대신
     * 새로 시도할 수 있게 한다.
     */
    private final AtomicReference<Mono<String>> resolutionRef = new AtomicReference<>();

    public StoreBootstrapper(OpenFgaProperties properties) {
        this.properties = properties;
    }

    /**
     * 이미 해석했으면 캐시된 storeId 를 준다. 없으면 찾고, 그래도 없으면 만든다. 쓰기·Check·장부 훑기가 모두 이것을 탄다 —
     * 시작 때 OpenFGA 가 안 닿았어도 첫 요청 때 닿으면 그때 준비된다(점검 M6).
     */
    public Mono<String> resolveStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.defer(this::sharedResolution);
    }

    /**
     * 헬스체크 전용 read-only 조회. 캐시된 storeId 가 있으면 그것을 쓰고, 없으면 이름으로 store 존재 여부만 확인한다 —
     * {@link #resolveStore()} 와 달리 store 를 만들거나 인가 모델을 쓰지 않는다.
     *
     * <p>헬스 프로브는 관찰만 해야지 인프라를 만들면 안 된다. 인증 없는
     * {@code GET /actuator/health} 는 k8s 프로브·로드밸런서·오타난 {@code openfga.store-name}
     * 설정 등 무엇이든 호출할 수 있는데, 이 경로가 {@link #resolveStore()} 를 타면 오타난 이름으로 빈 store 를 새로 만든 채
     * 조용히 UP 을 보고하게 된다. store 가 없으면 {@link Mono#empty()} 를 그대로 돌려주고, DOWN 으로의 번역은 호출자(헬스 인디케이터)
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
     *
     * <p>이것은 <b>한 프로세스 안</b>의 동시 호출을 하나로 묶는다. 프로세스(인스턴스)끼리의 경주는 {@link #convergeAfterCreate} 가 푼다.
     */
    private Mono<String> sharedResolution() {
        Mono<String> existing = resolutionRef.get();
        if (existing != null) {
            return existing;
        }

        Mono<String> created = findStoreIdByName()
                .switchIfEmpty(Mono.defer(() -> createStore().flatMap(this::convergeAfterCreate)))
                .flatMap(this::attachAndWriteModel)
                .doFinally(signal -> resolutionRef.set(null))
                .cache();

        if (resolutionRef.compareAndSet(null, created)) {
            return created;
        }
        return resolutionRef.get();
    }

    /**
     * 방금 store 를 만든 뒤 같은 이름 목록을 다시 본다(점검 M17). 빈 OpenFGA 에 인스턴스 둘이 동시에 뜨면 둘 다 "없다"를 보고 각자
     * 만든다 — OpenFGA 는 이름 유일성을 강제하지 않는다. 그래서 만든 쪽마다 목록을 다시 보고, 모두가 <b>가장 먼저 만들어진 것</b>
     * (createdAt, 같으면 id 사전순)을 쓴다. 그것이 내 것이 아니면 방금 만든 내 store 를 지운다. 남이 만든 store 는 지우지 않는다 —
     * 그쪽도 같은 규칙으로 스스로 물러난다. 동시에 떠도 결국 하나로 모인다.
     *
     * <p>목록이 생성 직후 바로 보인다는 전제다 — 같은 OpenFGA 서버라 성립한다고 본다(설계 §11).
     *
     * <p>패키지 전용 — 경주의 한 순간(남이 먼저 만든 뒤 내가 만든 상태)을 테스트가 직접 만들어 본다.
     */
    Mono<String> convergeAfterCreate(String createdId) {
        return listStoresNamed().flatMap(stores -> {
            Store winner = stores.stream().min(먼저_만든_순).orElse(null);
            if (winner == null || winner.getId().equals(createdId)) {
                return Mono.just(createdId);
            }
            log.warn("같은 이름 store '{}' 를 다른 인스턴스가 먼저 만들었다. 내가 만든 {} 를 지우고 {} 를 쓴다",
                    properties.getStoreName(), createdId, winner.getId());
            return deleteStoreById(createdId).thenReturn(winner.getId());
        });
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
     * storeId 에 묶인 client 를 준다. {@code clientRef}/{@code storeIdRef} 캐시를 읽지도 쓰지도 않는다.
     *
     * <p>{@link #findExistingStore()} 는 store 존재만 확인하고 storeId 를 돌려줄 뿐 {@code clientRef} 를 채우지 않는다 — 그래서 보기만
     * 하는 쪽은 {@link #client()} 대신 이것을 쓴다. Check·장부 훑기도 {@link #resolveStore()} 가 준 storeId 로 이것을 쓴다.
     *
     * <p><b>storeId 별로 client 를 재사용한다.</b> 전에는 호출마다 새로 만들었는데, Check 는 <b>응답 한 줄당 한 번</b> 돈다 — 경로
     * 200개짜리 직원 상세 하나가 인증 없는 GET 한 번에 커넥션 풀과 셀렉터 스레드를 200벌 만든다. store 번호는 바뀌지 않으므로
     * 엔트리는 사실상 하나다.
     */
    public OpenFgaClient clientFor(String storeId) {
        return readOnlyClients.computeIfAbsent(storeId, this::newClient);
    }

    private Mono<String> findStoreIdByName() {
        return listStoresNamed().flatMap(this::resolveUniqueMatch);
    }

    /** 이 이름의 store 전부. */
    private Mono<List<Store>> listStoresNamed() {
        return listAllStores(null, new ArrayList<>())
                .map(all -> all.stream()
                        .filter(store -> properties.getStoreName().equals(store.getName()))
                        .toList());
    }

    /**
     * store 목록을 continuation token 이 소진될 때까지 전부 순회한다.
     *
     * <p>{@code listStores()} 는 한 페이지(OpenFGA 기본 50개)만 반환한다. 공유 OpenFGA
     * 서버에 store 가 그보다 많으면, 첫 페이지에 없다고 곧장 {@code createStore} 로
     * 넘어가는 것은 위험하다 — 실제로는 다음 페이지에 이름이 이미 존재하는 store 가 있는데
     * 못 찾은 것뿐이고, OpenFGA 는 이름 유일성을 강제하지 않으므로 조용히 두 번째
     * store 가 만들어진다. 이후 이 앱은 새로 만든 빈 store 에 튜플을 쓰고, 기존 소비자는
     * 여전히 첫 번째 store 를 조회하는 완전한 인가 실패로 이어진다.
     */
    private Mono<List<Store>> listAllStores(String continuationToken, List<Store> accumulated) {
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
                        return listAllStores(next, accumulated);
                    }
                    return Mono.just(accumulated);
                });
    }

    /**
     * 찾기 단계에서 같은 이름의 store 가 둘 이상이면 임의로 하나를 골라 쓰는 대신 에러로 멈춘다 — 오래전부터 둘이었다는 뜻이고,
     * 어느 쪽에 진짜 데이터가 있는지 모른다. 사람이 개입해 정리해야 한다. (방금 만든 뒤의 중복은 {@link #convergeAfterCreate} 가 푼다.)
     */
    private Mono<String> resolveUniqueMatch(List<Store> matches) {
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

    /**
     * 인가 모델을 쓴 <b>뒤에만</b> storeId 를 기억한다(점검 M6). 먼저 기억하면 모델 쓰기가 실패해도 캐시가 남아, 다음
     * {@link #resolveStore()} 가 캐시를 보고 다시 시도하지 않는다 — 이 프로세스는 재시작 전까지 모델 없는 store 에 쓰고 묻는다.
     */
    private Mono<String> attachAndWriteModel(String storeId) {
        return Mono.fromCallable(() -> newClient(storeId))
                .flatMap(client -> Mono.fromFuture(() -> {
                            try {
                                return client.writeAuthorizationModel(authorizationModel());
                            } catch (Exception e) {
                                throw new IllegalStateException("인가 모델 등록 실패", e);
                            }
                        })
                        .doOnNext(response -> {
                            log.info("OpenFGA 인가 모델을 등록했다");
                            clientRef.set(client);
                            storeIdRef.set(storeId);
                        }))
                .thenReturn(storeId);
    }

    /** 등록할 인가 모델. 패키지 전용 — 모델 등록이 실패하는 경로를 테스트가 흉내 낸다. */
    WriteAuthorizationModelRequest authorizationModel() {
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
