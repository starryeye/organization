package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * recreateStore() 는 store 삭제 → storeIdRef/clientRef 초기화 → 재생성 사이에
     * 두 캐시가 모두 비는 창을 연다. 이 창으로 겹쳐 들어온 resolveStore() 호출(예:
     * 헬스체크)이 "아직 해석 안 됐다"고 오판해 자기 것대로 store 를 하나 더 만들면
     * OpenFGA 가 이름 유일성을 강제하지 않으므로 같은 이름의 store 가 조용히 두 개가
     * 된다. recreateStore() 가 파괴 작업 전에 자신의 in-flight Mono 를 resolutionRef 에
     * 먼저 게시해 이 창을 막았는지를 검증한다.
     */
    @Test
    @DisplayName("recreateStore 도중 겹치는 resolveStore 호출은 재구성에 합류하고 같은 이름의 store 는 하나만 남는다")
    void 재구성_도중_겹치는_resolveStore_는_합류한다() {
        // given — OpenFgaTestSupport 가 이미 최초 해석을 마쳐 둔 bootstrapper.
        // 파괴 창이 몇 ms 밖에 안 열려 있을 수 있어 한 번의 시도로는 맞힌다는 보장이
        // 없다. 재구성을 여러 번 반복해 창을 여러 번 노출시켜 우연히 비껴가는 것을 막는다.
        for (int attempt = 1; attempt <= 5; attempt++) {
            // when — recreateStore() 가 끝날 때까지 resolveStore() 를 계속 쏴서, 정확한
            // 타이밍을 몰라도 창과 겹치는 호출이 반드시 섞이게 한다.
            AtomicBoolean recreating = new AtomicBoolean(true);

            Mono<String> recreate = bootstrapper.recreateStore()
                    .doFinally(signal -> recreating.set(false));

            // 12개의 워커가 recreating 이 꺼질 때까지 resolveStore() 를 쉬지 않고 반복
            // 호출한다. 각 호출이 완료된 직후 바로 재구독하므로(자기 페이스) 타이머 기반
            // backpressure 문제 없이 재구성이 진행되는 내내 호출이 끊이지 않는다.
            Flux<String> pollingResolves = Flux.range(0, 12)
                    .flatMap(worker -> Mono.defer(bootstrapper::resolveStore)
                            .repeat(recreating::get)
                            .subscribeOn(Schedulers.parallel()));

            List<String> results = Flux.merge(recreate, pollingResolves)
                    .collectList()
                    .block();

            // then — 모든 호출이 성공했고, 창을 파고든 호출이 있었더라도 store 는 하나뿐이다
            assertThat(results).isNotEmpty();
            long matching = countStoresNamed(properties.getStoreName());
            assertThat(matching)
                    .as("%d번째 재구성 창으로 겹쳐 들어온 resolveStore() 가 store 를 추가로 만들면 안 된다", attempt)
                    .isEqualTo(1);
        }
    }

    /**
     * recreateStore() 가 자신의 "현재 store 찾기" 단계로 공개 resolveStore() 를 타면,
     * cold 상태(storeIdRef == null)에서는 그 단계가 sharedResolution() 으로 넘어간다.
     * 그런데 recreateStore() 가 파괴 작업 전에 이미 자기 자신을 resolutionRef 에
     * 게시해 뒀다면, sharedResolution() 은 "진행 중인 해석이 있다"며 그 게시물(자기
     * 자신)을 그대로 돌려준다 — recreateStore() 가 자기 자신의 완료를 기다리는 자기
     * 참조가 되어 영원히 끝나지 않는다.
     *
     * <p>storeIdRef == null 은 갓 띄운 프로세스의 정상 상태다(시작 시점에 해석해
     * 두는 훅이 없다면). 그러니 배포 직후 처음 하는 동작이 STORE 모드 재적재라면
     * (재해복구, 최초 프로비저닝) 이 데드락을 그대로 밟는다.
     *
     * <p>OpenFgaTestSupport 의 @BeforeEach 가 모든 테스트에서 resolveStore() 를
     * 먼저 호출해 두므로, 이 시나리오를 재현하려면 별도의 새 bootstrapper 가 필요하다.
     */
    @Test
    @Timeout(10)
    @DisplayName("resolveStore 를 먼저 호출하지 않은 cold 상태에서도 recreateStore 는 끝난다")
    void cold_상태에서도_recreateStore_는_끝난다() {
        // given — resolveStore() 를 단 한 번도 호출하지 않은 새 bootstrapper
        OpenFgaProperties coldProperties = new OpenFgaProperties();
        coldProperties.setApiUrl(properties.getApiUrl());
        coldProperties.setStoreName("cold-" + UUID.randomUUID());
        coldProperties.setWriteBatchSize(properties.getWriteBatchSize());
        coldProperties.setMaxRetries(properties.getMaxRetries());
        StoreBootstrapper coldBootstrapper = new StoreBootstrapper(coldProperties);

        // when — resolveStore() 없이 곧바로 recreateStore() 를 호출한다.
        // 자기 참조 데드락이 있다면 이 block(Duration) 이 타임아웃으로 실패한다
        // (@Timeout 이 그보다 늦게 걸려도 테스트 전체를 강제 종료해 대비한다).
        String storeId = coldBootstrapper.recreateStore().block(Duration.ofSeconds(5));

        // then — store 가 존재하지 않았을 때도 삭제 단계를 건너뛰고 새로 만든다
        assertThat(storeId).isNotNull();
        assertThat(countStoresNamed(coldProperties.getStoreName()))
                .as("cold 상태에서 recreateStore() 는 store 를 정확히 하나 만들어야 한다")
                .isEqualTo(1);
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
