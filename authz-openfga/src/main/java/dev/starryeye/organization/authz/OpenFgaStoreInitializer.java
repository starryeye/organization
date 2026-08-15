package dev.starryeye.organization.authz;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.publisher.Mono;

/**
 * 앱 시작 시점에 store 를 미리 해석해 둔다.
 *
 * <p>이게 없으면 배포 직후 storeIdRef 를 처음 채우는 호출자가 누구든 될 수 있는데,
 * 그게 헬스체크(readiness probe)라면 상태를 관찰만 해야 할 모니터링 호출이 store 를
 * 만들고 인가 모델을 쓰는 부작용을 갖게 된다. {@link OpenFgaHealthIndicator} 는 이제
 * {@link StoreBootstrapper#resolveStore()} 동시 호출에 안전하지만("안전하게 만든다"와
 * "애초에 헬스체크가 인프라를 만들지 않는다"는 다른 문제다), 정상적으로 뜬 인스턴스라면
 * 헬스체크가 도착했을 때 storeIdRef 가 이미 채워져 있어야 한다.
 *
 * <p>{@link dev.starryeye.organization.storage.TableInitializer} 와 정신은 같지만
 * (시작 시점에 인프라를 미리 준비해 둔다) 실패 시 동작은 다르다: TableInitializer 는
 * 실패하면 앱 시작을 막는다 — DynamoDB 는 스케줄러, 관리 API, 동기화 이력 등 앱의
 * 거의 모든 기능이 의존하는 인프라라 그게 없으면 애초에 뜰 이유가 없다. 반면 OpenFGA
 * 는 동기화의 "쓰기 대상"일 뿐이라, 배포 순서가 겹쳐(예: docker-compose/k8s 에서
 * app 과 openfga 가 동시에 뜨는 경우) 시작 시점에 일시적으로 닿지 않아도 앱 자체는
 * 정상 기동해야 DynamoDB 기반 관리 API 와 스케줄러가 계속 동작한다. resolveStore()
 * 의 실패는 캐시되지 않으므로 이후의 동기화나 헬스체크가 자연스럽게 재시도한다.
 * 그래서 여기서는 실패를 경고 로그로만 남기고 앱 시작을 막지 않는다 — 그 사이
 * {@link OpenFgaHealthIndicator} 는 DOWN 을 보고해 운영자가 알 수 있게 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaStoreInitializer implements InitializingBean {

    private final StoreBootstrapper bootstrapper;

    @Override
    public void afterPropertiesSet() {
        bootstrapper.resolveStore()
                .doOnError(error -> log.warn(
                        "시작 시점에 OpenFGA store 를 준비하지 못했다. "
                                + "앱은 계속 기동하며, 헬스체크가 DOWN 을 보고하는 동안 이후 호출이 재시도한다",
                        error))
                .onErrorResume(error -> Mono.empty())
                .block();
    }
}
