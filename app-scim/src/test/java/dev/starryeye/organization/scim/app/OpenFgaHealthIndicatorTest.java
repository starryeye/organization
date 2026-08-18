package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
 * <p>app-ldap 의 {@code OpenFgaHealthIndicator} 는 아직 이 수정 전의 형태(직접
 * {@code resolveStore()} 호출)를 그대로 쓴다 — {@code docs/superpowers/plans/2026-08-15-follow-ups.md}
 * 에 추적 중이며 이 계획의 범위 밖이다.
 */
@Testcontainers
class OpenFgaHealthIndicatorTest {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

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

        // then — 실제로 이 이름의 store 를 만들지 않았다
        assertThat(countStoresNamed(apiUrl, storeName))
                .as("헬스체크가 store 를 프로비저닝하면 안 된다")
                .isZero();
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
