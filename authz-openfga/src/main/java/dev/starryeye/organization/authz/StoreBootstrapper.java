package dev.starryeye.organization.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.io.InputStream;
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
    private final AtomicReference<String> storeIdRef = new AtomicReference<>();

    public StoreBootstrapper(OpenFgaProperties properties) {
        this.properties = properties;
    }

    /** 이미 해석했으면 캐시된 storeId 를 준다. 없으면 찾고, 그래도 없으면 만든다. */
    public Mono<String> resolveStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return findStoreIdByName()
                .switchIfEmpty(Mono.defer(this::createStore))
                .flatMap(this::attachAndWriteModel);
    }

    /** rebuild(store 모드) 전용. store 를 지우고 같은 이름으로 다시 만든다. */
    public Mono<String> recreateStore() {
        return resolveStore()
                .flatMap(storeId -> Mono.fromFuture(() -> {
                    try {
                        return client().deleteStore();
                    } catch (Exception e) {
                        throw new IllegalStateException("store 삭제 실패", e);
                    }
                }).then(Mono.fromRunnable(() -> {
                    storeIdRef.set(null);
                    clientRef.set(null);
                })))
                .then(Mono.defer(this::createStore))
                .flatMap(this::attachAndWriteModel);
    }

    public OpenFgaClient client() {
        OpenFgaClient client = clientRef.get();
        if (client == null) {
            throw new IllegalStateException("store 가 아직 해석되지 않았다. resolveStore() 를 먼저 호출하라");
        }
        return client;
    }

    private Mono<String> findStoreIdByName() {
        return Mono.fromCallable(() -> newClient(null))
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.listStores();
                    } catch (Exception e) {
                        throw new IllegalStateException("store 목록 조회 실패", e);
                    }
                }))
                .flatMap(response -> response.getStores().stream()
                        .filter(store -> properties.getStoreName().equals(store.getName()))
                        .findFirst()
                        .map(store -> Mono.just(store.getId()))
                        .orElseGet(Mono::empty));
    }

    private Mono<String> createStore() {
        log.info("OpenFGA store '{}' 을 생성한다", properties.getStoreName());
        return Mono.fromCallable(() -> newClient(null))
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
