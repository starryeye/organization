package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.starryeye.organization.core.fixture.Containers;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

/**
 * OpenFGA 컨테이너를 띄운다. v1.10.0 이상이어야 on_duplicate / on_missing 이 동작한다.
 * 테스트마다 store 이름을 새로 만들어 서로 간섭하지 않게 한다.
 */
@Testcontainers
public abstract class OpenFgaTestSupport {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    protected OpenFgaProperties properties;
    protected StoreBootstrapper bootstrapper;

    @BeforeEach
    void OpenFGA를_준비한다() {
        properties = new OpenFgaProperties();
        properties.setApiUrl("http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        properties.setStoreName("test-" + UUID.randomUUID());
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);

        bootstrapper = new StoreBootstrapper(properties);
        bootstrapper.resolveStore().block();
    }

    /** 이 테스트만의 새 store 이름을 가진 설정. 부트스트래퍼끼리의 경주나 store 가 없는 상태를 볼 때 쓴다. */
    protected OpenFgaProperties 새_속성(String 접두사) {
        OpenFgaProperties fresh = new OpenFgaProperties();
        fresh.setApiUrl(properties.getApiUrl());
        fresh.setStoreName(접두사 + UUID.randomUUID());
        fresh.setWriteBatchSize(properties.getWriteBatchSize());
        fresh.setMaxRetries(properties.getMaxRetries());
        return fresh;
    }

    /** 부트스트래퍼를 거치지 않고 store 를 만든다 — 다른 인스턴스가 만든 store 를 흉내 낸다. */
    protected String createStoreDirectly(OpenFgaClient client, String name) {
        try {
            return client.createStore(new CreateStoreRequest().name(name)).get().getId();
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 store 생성 실패", e);
        }
    }

    /**
     * 이름이 같은 store 수. 검증용 도우미도 페이지를 끝까지 넘긴다 — 여러 테스트가 한 페이지보다 많은 store 를 만들어 두므로,
     * 첫 페이지만 보면 뒤쪽 store 를 세지 못해 정상 동작을 실패로 본다.
     */
    protected long countStoresNamed(String name) {
        try {
            OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
            long count = 0;
            String continuationToken = null;
            do {
                ClientListStoresOptions options = new ClientListStoresOptions();
                if (continuationToken != null && !continuationToken.isBlank()) {
                    options.continuationToken(continuationToken);
                }
                var response = client.listStores(options).get();
                count += response.getStores().stream().filter(store -> name.equals(store.getName())).count();
                continuationToken = response.getContinuationToken();
            } while (continuationToken != null && !continuationToken.isBlank());
            return count;
        } catch (Exception e) {
            throw new IllegalStateException("store 목록 조회 실패", e);
        }
    }
}
