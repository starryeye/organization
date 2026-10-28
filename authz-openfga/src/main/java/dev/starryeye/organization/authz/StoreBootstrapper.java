package dev.starryeye.organization.authz;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.RelationReference;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.Userset;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * store 이름으로 storeId 를 해석하고 인가 모델을 등록한다.
 *
 * <p>앱의 어느 곳도 storeId 나 modelId 를 알지 못한다. 설정에는 store-name 만 있고,
 * write 호출에는 authorization_model_id 를 넘기지 않아 서버가 최신 모델을 쓴다.
 *
 * <p>store 는 {@code openfga.create-store} 가 켜져 있을 때만 만든다(설계 2026-10-27 §4). 꺼져 있는데 그 이름의 store 가 없으면
 * {@link StoreNotFoundException} 이다 — 이름 오타가 빈 store 를 새로 만들어 쓰기가 엉뚱한 곳으로 가지 않는다. 인가 모델은 최신과
 * 내용이 다를 때만 쓴다 — 기동마다 같은 모델 버전이 쌓이지 않는다.
 *
 * <p><b>store 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).</b> 재적재도 store 를 지우고 다시 만들지 않는다 — 장부 안에서
 * 청소한다. 이 클래스가 store 를 지우는 경우는 하나뿐이다: 동시에 처음 뜬 인스턴스끼리 같은 이름 store 를 둘 만들었을 때 늦게 만든
 * 쪽이 자기 것을 지운다({@link #convergeAfterCreate}).
 */
@Slf4j
public class StoreBootstrapper {

    private static final String MODEL_RESOURCE = "authorization-model.json";

    /**
     * 모델 비교용. 서버가 돌려준 모델과 우리 요청을 같은 매퍼로 JSON 트리로 바꿔 견준다 — 빈 목록·빈 맵과 없는 칸이 같아진다.
     *
     * <p><b>빈 객체 표지는 지우지 않는다.</b> {@code NON_EMPTY} 는 빈 맵도 지우는데, SDK 모델에서 값이 {@code Object} 이고 의미 있는 값이
     * 빈 객체 {@code {}} 하나뿐인 칸 — {@code Userset.this}(직접 관계)와 {@code RelationReference.wildcard}({@code user:*}) —
     * 은 있고 없고가 곧 내용이다. 그대로 두면 {@code [user]} 를 {@code [user:*]} 로 바꾼 모델이 같다고 읽혀 쓰이지 않는다. 그래서 이 두 칸만
     * {@code NON_NULL} 로 둔다. (모델에 닿는 SDK 클래스 중 {@code Object} 칸은 이 둘뿐이다. 나머지는 요청 맥락의 {@code context} 다.)
     * 비교가 틀리면 지금처럼 쓰게 되도록, 헷갈리는 칸은 다르다고 읽는 쪽이 안전하다(설계 2026-10-27 §4).
     */
    private static final ObjectMapper 모델_비교용 = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
            .addMixIn(Userset.class, 직접_관계_표지.class)
            .addMixIn(RelationReference.class, 와일드카드_표지.class);

    /** {@link Userset} 의 {@code this} 를 빈 객체여도 남기는 믹스인. */
    private abstract static class 직접_관계_표지 {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public abstract Object getThis();
    }

    /** {@link RelationReference} 의 {@code wildcard} 를 빈 객체여도 남기는 믹스인. */
    private abstract static class 와일드카드_표지 {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public abstract Object getWildcard();
    }

    /** 모델에서 견주는 칸. {@code id} 는 서버가 매기므로 견주지 않는다. */
    private static final List<String> 견주는_칸 = List.of("schema_version", "type_definitions", "conditions");

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

    /** {@link #clientFor(String)} 이 돌려주는 client 를 storeId 별로 재사용한다. {@code clientRef} 는 모델 등록 뒤 여기의 것을 가리킨다. */
    private final ConcurrentMap<String, OpenFgaClient> readOnlyClients = new ConcurrentHashMap<>();

    /**
     * 진행 중인 해석을 공유하기 위한 in-flight Mono. resolveStore() 를 동시에 여러 곳에서
     * 호출해도 실제 찾기 → (생성이 켜져 있으면) 만들기 → 수렴 → 모델 등록 파이프라인은 한 번만 구성/구독되고, 모든 호출자가 같은 결과를 공유한다.
     *
     * <p>성공하면 storeIdRef 가 채워져 이후 호출은 이 필드를 아예 거치지 않는다(빠른 경로).
     * 실패하면 doFinally 에서 이 필드를 비워, 다음 호출이 캐시된 에러를 영원히 받는 대신
     * 새로 시도할 수 있게 한다.
     */
    private final AtomicReference<Mono<String>> resolutionRef = new AtomicReference<>();

    /** @throws IllegalStateException {@code openfga.store-name} 이 비어 있을 때 — 이름 기본값이 없다 */
    public StoreBootstrapper(OpenFgaProperties properties) {
        String name = properties.getStoreName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("openfga.store-name 이 비어 있다 — 설정에 store 이름을 적는다");
        }
        this.properties = properties;
    }

    /**
     * 이미 해석했으면 캐시된 storeId 를 준다. 없으면 찾고, 그래도 없으면 {@code openfga.create-store} 가 켜져 있을 때만 만든다 —
     * 꺼져 있으면 {@link StoreNotFoundException} 이다. 쓰기·Check·장부 훑기가 모두 이것을 탄다 —
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
     * 헬스체크 전용 read-only 조회. 캐시한 storeId 가 있으면 {@code GetStore} 로 <b>실제로 묻고</b>, 없으면 이름으로 store 존재 여부만
     * 확인한다 — {@link #resolveStore()} 와 달리 store 를 만들거나 인가 모델을 쓰지 않는다(설계 2026-10-27 §7).
     *
     * <p>캐시한 번호를 묻지 않고 돌려주면 OpenFGA 가 죽거나 store 가 지워진 뒤에도 헬스가 UP 이다. 그래서 캐시가 있어도 묻는다.
     * {@code GetStore} 가 404({@code FgaApiNotFoundError}) 면 store 가 없는 것이라 {@link Mono#empty()} 이고, 그 밖의 오류는 오류로 그대로
     * 낸다 — 닿지 않는 것을 "없다"로 읽지 않는다. ({@code GetStore} 는 이 프로젝트가 제한하는 튜플 Read 가 아니다.)
     *
     * <p>헬스 프로브는 관찰만 해야지 인프라를 만들면 안 된다. 인증 없는
     * {@code GET /actuator/health} 는 k8s 프로브·로드밸런서·오타난 {@code openfga.store-name}
     * 설정 등 무엇이든 호출할 수 있는데, 이 경로가 {@link #resolveStore()} 를 타면 오타난 이름으로 빈 store 를 새로 만든 채
     * 조용히 UP 을 보고하게 된다. store 가 없으면 {@link Mono#empty()} 를 그대로 돌려주고, DOWN 으로의 번역과 2초 상한은 호출자(헬스
     * 인디케이터) 몫이다.
     */
    public Mono<String> findExistingStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return 캐시한_store_를_묻는다(cached);
        }
        return findStoreIdByName();
    }

    /** {@code GetStore} 로 그 번호의 store 가 아직 있는지 묻는다. 404 면 빈 Mono, 그 밖의 오류는 오류다. */
    private Mono<String> 캐시한_store_를_묻는다(String storeId) {
        return Mono.fromFuture(() -> {
                    try {
                        return clientFor(storeId).getStore();
                    } catch (Exception e) {
                        throw new IllegalStateException("store 조회 실패", e);
                    }
                })
                .thenReturn(storeId)
                .onErrorResume(OpenFgaErrors::없는_store_인가, error -> Mono.empty());
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
                .switchIfEmpty(Mono.defer(this::없으면_만들거나_멈춘다))
                .flatMap(this::attachAndWriteModel)
                .doFinally(signal -> resolutionRef.set(null))
                .cache();

        if (resolutionRef.compareAndSet(null, created)) {
            return created;
        }
        return resolutionRef.get();
    }

    /**
     * 찾기가 비었을 때. 생성이 켜져 있으면 만들고, 꺼져 있으면 {@link StoreNotFoundException} 으로 멈춘다 — 목록 조회가 성공했는데
     * 그 이름이 없다는 뜻이라 일시 장애가 아니고 설정 오류다(이름 오타이거나 처음 설치).
     */
    private Mono<String> 없으면_만들거나_멈춘다() {
        if (!properties.isCreateStore()) {
            return Mono.error(new StoreNotFoundException(
                    "OpenFGA store %s 이 없다 — 처음 설치라면 openfga.create-store=true 로 한 번 띄운다"
                            .formatted(properties.getStoreName())));
        }
        return createStore().flatMap(this::convergeAfterCreate);
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

    /** 이 이름의 store 전부. 패키지 전용 — 시험이 덮어 첫 목록 조회를 실패시킨다. */
    Mono<List<Store>> listStoresNamed() {
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
     * 인가 모델을 맞춘 <b>뒤에만</b> storeId 를 기억한다(점검 M6). 먼저 기억하면 모델 쓰기가 실패해도 캐시가 남아, 다음
     * {@link #resolveStore()} 가 캐시를 보고 다시 시도하지 않는다 — 이 프로세스는 재시작 전까지 모델 없는 store 에 쓰고 묻는다.
     *
     * <p><b>모델은 최신과 내용이 다를 때만 쓴다</b>(설계 2026-10-27 §4). 모델은 쓸 때마다 새 버전이 되고 지워지지 않아, 내용이 같아도
     * 기동마다 쓰면 버전이 끝없이 쌓인다. 최신 모델을 읽어 견주고, 없거나 읽기가 오류거나 다르면 쓴다. 쓰든 안 쓰든 끝나면 번호를
     * 기억한다. ({@code ReadLatestAuthorizationModel} 은 이 프로젝트가 제한하는 튜플 Read 가 아니다.)
     *
     * <p>client 는 {@link #clientFor} 의 것을 쓴다. Check 도 요청마다 이 준비 과정을 타므로, 모델 쓰기가 계속 실패하는 동안 시도마다
     * 새로 만들면 HTTP client 가 시도 수만큼 쌓인다.
     */
    private Mono<String> attachAndWriteModel(String storeId) {
        return Mono.fromCallable(() -> clientFor(storeId))
                .flatMap(client -> 모델을_맞춘다(client)
                        .then(Mono.fromRunnable(() -> {
                            clientRef.set(client);
                            storeIdRef.set(storeId);
                        })))
                .thenReturn(storeId);
    }

    /** 최신 모델이 우리 모델과 같으면 그대로 두고, 아니면 쓴다. */
    private Mono<Void> 모델을_맞춘다(OpenFgaClient client) {
        WriteAuthorizationModelRequest 우리 = authorizationModel();
        return 써야_하는가(client, 우리).flatMap(써야 -> {
            if (써야) {
                return 모델을_쓴다(client, 우리);
            }
            log.info("OpenFGA 인가 모델이 최신과 같아 쓰지 않았다");
            return Mono.empty();
        });
    }

    /** 최신 모델을 읽어 우리 모델과 견준다. 모델이 없거나 읽기가 오류면 쓴다 — 견주지 못했으니 지금처럼 쓰는 쪽이 안전하다. */
    private Mono<Boolean> 써야_하는가(OpenFgaClient client, WriteAuthorizationModelRequest 우리) {
        return Mono.fromFuture(() -> {
                    try {
                        return client.readLatestAuthorizationModel();
                    } catch (Exception e) {
                        throw new IllegalStateException("최신 인가 모델 조회 실패", e);
                    }
                })
                .map(response -> !같은_모델인가(response.getAuthorizationModel(), 우리))
                // 응답 없이 끝나도 견주지 못한 것이다 — 건너뛰고 번호를 기억하는 쪽으로 틀리지 않고 쓴다
                .defaultIfEmpty(true)
                .onErrorResume(error -> {
                    log.warn("최신 인가 모델을 읽지 못해 견주지 않고 쓴다", error);
                    return Mono.just(true);
                });
    }

    private Mono<Void> 모델을_쓴다(OpenFgaClient client, WriteAuthorizationModelRequest 우리) {
        return Mono.fromFuture(() -> {
                    try {
                        return client.writeAuthorizationModel(우리);
                    } catch (Exception e) {
                        throw new IllegalStateException("인가 모델 등록 실패", e);
                    }
                })
                .doOnNext(response -> log.info("OpenFGA 인가 모델을 등록했다"))
                .then();
    }

    /**
     * 서버의 최신 모델과 우리 요청이 {@code schema_version}·{@code type_definitions}·{@code conditions} 세 칸에서 같은가.
     * 둘을 같은 매퍼로 JSON 트리로 바꿔 견주므로 없는 칸은 빈 칸과 같다. 모델이 없으면 다르다.
     *
     * <p>패키지 전용 — 견주는 규칙을 시험이 직접 본다.
     */
    static boolean 같은_모델인가(AuthorizationModel 최신, WriteAuthorizationModelRequest 우리) {
        if (최신 == null) {
            return false;
        }
        JsonNode 최신_트리 = 모델_비교용.valueToTree(최신);
        JsonNode 우리_트리 = 모델_비교용.valueToTree(우리);
        return 견주는_칸.stream().allMatch(칸 -> Objects.equals(최신_트리.get(칸), 우리_트리.get(칸)));
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
