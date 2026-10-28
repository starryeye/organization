package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.fixture.Containers;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스체크가 store 를 만들지 않는지 확인한다.
 *
 * <p>{@link OpenFgaHealthIndicator} 가 {@link StoreBootstrapper#resolveStore()} 를 그대로
 * 쓰면, 시작 시점의 예열({@code OpenFgaStoreInitializer})이 실패했거나 아직 끝나지 않은
 * cold 상태에서 인증 없는 {@code GET /actuator/health} 하나가 store 를 만들고 인가 모델을
 * 써버린다 — 헬스 프로브가 관찰이 아니라 인프라 프로비저닝을 하는 셈이고, 오타난
 * {@code openfga.store-name} 은 빈 store 를 새로 만든 채 조용히 UP 을 보고하게 된다.
 *
 * <p>이 테스트는 예열을 거치지 않은(=resolveStore() 를 단 한 번도 호출하지 않은) cold
 * {@link StoreBootstrapper} 로 {@code OpenFgaHealthIndicator} 를 직접 만들어 health() 를
 * 부르고, 결과가 DOWN 이면서 실제로 그 이름의 store 가 생기지 않았는지를 raw 클라이언트로
 * 확인한다.
 *
 * <p>해석을 마친 뒤 그 store 가 지워진 경우도 본다 — 캐시한 번호만 믿으면 지운 store 도 UP 으로 보인다(설계 2026-10-27 §7).
 * 그래서 {@link StoreBootstrapper#findExistingStore()} 는 캐시한 번호가 있어도 {@code GetStore} 로 실제로 묻는다.
 * 두 앱의 {@code OpenFgaHealthIndicator} 는 같은 코드이고, 이 시험은 app-scim 쪽에서 둘의 몫을 함께 본다.
 */
@Testcontainers
class OpenFgaHealthIndicatorTest {

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Test
    @DisplayName("예열되지 않은 상태에서 헬스체크를 해도 store 를 만들지 않고 DOWN 을 보고한다")
    void 헬스체크는_store_를_만들지_않는다() {
        // given — resolveStore() 를 한 번도 호출하지 않은 cold bootstrapper.
        // OpenFgaStoreInitializer 가 시작 시점에 하는 예열이 실패했거나 아직 안 끝난
        // 상태를 흉내낸다.
        String storeName = "health-cold-" + UUID.randomUUID();
        String apiUrl = "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080);
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setApiUrl(apiUrl);
        properties.setStoreName(storeName);
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);
        StoreBootstrapper bootstrapper = new StoreBootstrapper(properties);
        OpenFgaHealthIndicator indicator = new OpenFgaHealthIndicator(bootstrapper, properties);

        // when — 헬스체크를 직접 호출한다 (resolveStore() 는 아직 아무도 부르지 않았다)
        var health = indicator.health().block();

        // then — DOWN 을 보고하고
        assertThat(health).isNotNull();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "store 가 없다(지워졌거나 아직 만들지 않았다)");

        // then — 실제로 이 이름의 store 를 만들지 않았다
        assertThat(countStoresNamed(apiUrl, storeName))
                .as("헬스체크가 store 를 프로비저닝하면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("해석한 store 가 지워지면 헬스체크는 DOWN 이다 — 캐시한 번호만 보고 UP 을 말하지 않는다")
    void 지운_store_는_DOWN_이다() throws Exception {
        // given — 해석을 마쳐 번호를 캐시한 bootstrapper. 헬스는 아직 UP 이다
        String apiUrl = "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080);
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setApiUrl(apiUrl);
        properties.setStoreName("health-deleted-" + UUID.randomUUID());
        properties.setCreateStore(true);
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);
        StoreBootstrapper bootstrapper = new StoreBootstrapper(properties);
        OpenFgaHealthIndicator indicator = new OpenFgaHealthIndicator(bootstrapper, properties);
        String storeId = bootstrapper.resolveStore().block(Duration.ofSeconds(10));
        assertThat(indicator.health().block(Duration.ofSeconds(10)).getStatus().getCode()).isEqualTo("UP");

        // given — 누군가 그 store 를 지웠다(부트스트래퍼를 거치지 않는다)
        new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl).storeId(storeId)).deleteStore().get();

        // when
        var health = indicator.health().block(Duration.ofSeconds(10));

        // then
        assertThat(health).isNotNull();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("reason", "store 가 없다(지워졌거나 아직 만들지 않았다)");
    }

    private long countStoresNamed(String apiUrl, String name) {
        try {
            OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl));
            long count = 0;
            String continuationToken = null;
            do {
                var options = new ClientListStoresOptions();
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
