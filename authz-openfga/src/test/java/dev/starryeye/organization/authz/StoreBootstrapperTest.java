package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreBootstrapper 의 최초 resolveStore() 동시 호출 안전성을 검증한다.
 *
 * <p>OpenFGA 는 store 이름 유일성을 강제하지 않으므로, check-then-act 로 구현하면
 * 동시에 도착한 첫 호출들이 각자 store 를 만들어 이름이 같은 store 가 여러 개
 * 생기고 호출자마다 다른 storeId 에 바인딩될 수 있다. 이 테스트는 그런 회귀가
 * 생기면 실패한다.
 *
 * <p>Check 를 쓰지 않으므로 no-read 규칙과 무관하지만, listStores() 로 store 개수를
 * 세는 것은 테스트 전용 검증이며 프로덕션 코드는 이 메서드를 호출하지 않는다.
 */
class StoreBootstrapperTest extends OpenFgaTestSupport {

    @Test
    @DisplayName("동시에 처음 resolveStore 를 호출해도 같은 이름의 store 는 하나만 생긴다")
    void 동시_최초_해석은_store_를_하나만_만든다() {
        // given — 아직 아무도 해석하지 않은 새 이름의 store 를 노리는 여러 호출자
        OpenFgaProperties freshProperties = new OpenFgaProperties();
        freshProperties.setApiUrl(properties.getApiUrl());
        freshProperties.setStoreName("concurrent-" + UUID.randomUUID());
        freshProperties.setWriteBatchSize(properties.getWriteBatchSize());
        freshProperties.setMaxRetries(properties.getMaxRetries());
        StoreBootstrapper freshBootstrapper = new StoreBootstrapper(freshProperties);

        // when — 20개의 구독자가 동시에 최초 resolveStore() 를 호출한다
        List<String> storeIds = Flux.range(0, 20)
                .flatMap(i -> freshBootstrapper.resolveStore().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        // then — 모두 같은 storeId 를 받았고, 실제로 그 이름의 store 는 하나뿐이다
        assertThat(storeIds).isNotNull().hasSize(20);
        assertThat(new HashSet<>(storeIds)).as("모든 호출자가 같은 storeId 를 공유해야 한다").hasSize(1);

        long matching = countStoresNamed(freshProperties.getStoreName());
        assertThat(matching).as("같은 이름의 store 가 정확히 하나만 존재해야 한다").isEqualTo(1);
    }

    private long countStoresNamed(String name) {
        try {
            OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
            return client.listStores().get().getStores().stream()
                    .filter(store -> name.equals(store.getName()))
                    .count();
        } catch (Exception e) {
            throw new IllegalStateException("store 목록 조회 실패", e);
        }
    }
}
