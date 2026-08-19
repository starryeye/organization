package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * store 가 이미 존재하는지만 read-only 로 확인한다. storeId 는 authz-openfga 안에만
 * 머물러야 하므로 응답에 담지 않는다.
 *
 * <p><b>{@link StoreBootstrapper#resolveStore()} 대신 {@link StoreBootstrapper#findExistingStore()}
 * 를 쓴다.</b> {@code resolveStore()} 는 store 가 없으면 만들고 인가 모델을 쓴다 — 헬스
 * 프로브가 관찰이 아니라 인프라 프로비저닝을 해버리는 것이다. 인증 없는
 * {@code GET /actuator/health} 는 k8s 프로브·로드밸런서·오타난 {@code openfga.store-name}
 * 설정 등 무엇이든 호출할 수 있어, 예열({@code OpenFgaStoreInitializer})이 실패했거나
 * 아직 안 끝난 cold 인스턴스에서 이 경로를 타면 잘못된 이름의 빈 store 가 조용히
 * 만들어지고 헬스체크는 그 위에서 UP 을 보고한다. {@code findExistingStore()} 는 store 가
 * 없으면 아무것도 만들지 않고 빈 결과를 주므로, 여기서는 그 경우를 DOWN 으로 번역한다.
 */
@Component("openFga")
@RequiredArgsConstructor
public class OpenFgaHealthIndicator implements ReactiveHealthIndicator {

    private final StoreBootstrapper bootstrapper;
    private final OpenFgaProperties properties;

    @Override
    public Mono<Health> health() {
        // storeId 는 밖으로 내보내지 않는다. store 가 존재하는지만 확인한다
        return bootstrapper.findExistingStore()
                .map(storeId -> Health.up()
                        .withDetail("storeName", properties.getStoreName())
                        .withDetail("apiUrl", properties.getApiUrl())
                        .build())
                .switchIfEmpty(Mono.just(Health.down()
                        .withDetail("storeName", properties.getStoreName())
                        .withDetail("apiUrl", properties.getApiUrl())
                        .withDetail("reason", "store 가 아직 존재하지 않는다")
                        .build()))
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
