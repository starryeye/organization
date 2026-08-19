package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 인가 판정을 묻는다. 열거 API 는 쓰지 않는다 — {@code Check} 는 점 조회다.
 *
 * <p><b>{@code findExistingStore()} 를 쓴다.</b> {@code resolveStore()} 는 store 가 없으면
 * 만들고 인가 모델을 쓴다. 조회 경로가 인프라를 프로비저닝하면 안 된다. store 가 없으면
 * Check 가 성립할 수 없으므로 에러로 끝내고, 호출자가 그것을 "판정 보류" 로 옮긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaRelationTupleChecker implements RelationTupleChecker {

    private final StoreBootstrapper bootstrapper;

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return bootstrapper.findExistingStore()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "OpenFGA store 가 아직 없어 Check 를 할 수 없다")))
                .flatMap(storeId -> Mono.fromFuture(() -> {
                            try {
                                return bootstrapper.client().check(new ClientCheckRequest()
                                        .user(tuple.user())
                                        .relation(tuple.relation())
                                        ._object(tuple.object()));
                            } catch (Exception e) {
                                throw new IllegalStateException("OpenFGA check 호출 실패", e);
                            }
                        })
                        .map(response -> Boolean.TRUE.equals(response.getAllowed())));
    }
}
