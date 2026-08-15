package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * store 해석(resolveStore)이 되는지만 확인한다. storeId 는 authz-openfga 안에만
 * 머물러야 하므로 응답에 담지 않는다.
 */
@Component("openFga")
@RequiredArgsConstructor
public class OpenFgaHealthIndicator implements ReactiveHealthIndicator {

    private final StoreBootstrapper bootstrapper;
    private final OpenFgaProperties properties;

    @Override
    public Mono<Health> health() {
        // storeId 는 밖으로 내보내지 않는다. 해석이 되는지만 확인한다
        return bootstrapper.resolveStore()
                .map(storeId -> Health.up()
                        .withDetail("storeName", properties.getStoreName())
                        .withDetail("apiUrl", properties.getApiUrl())
                        .build())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}
