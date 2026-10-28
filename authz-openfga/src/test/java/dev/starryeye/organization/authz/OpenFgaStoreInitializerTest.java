package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.model.Store;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFgaStoreInitializer 가 시작 시점에 store 를 미리 해석해, 이후 헬스체크가
 * store 생성이라는 부작용을 갖지 않게 하는지 검증한다.
 */
class OpenFgaStoreInitializerTest extends OpenFgaTestSupport {

    @Test
    @DisplayName("OpenFGA 가 닿으면 시작 시점에 store 를 해석해 둔다")
    void 도달_가능하면_시작_시점에_store_를_해석해둔다() {
        // given — resolveStore() 를 아직 호출하지 않은 새 bootstrapper
        OpenFgaProperties freshProperties = new OpenFgaProperties();
        freshProperties.setApiUrl(properties.getApiUrl());
        freshProperties.setStoreName("init-" + UUID.randomUUID());
        freshProperties.setCreateStore(true);
        freshProperties.setWriteBatchSize(properties.getWriteBatchSize());
        freshProperties.setMaxRetries(properties.getMaxRetries());
        StoreBootstrapper freshBootstrapper = new StoreBootstrapper(freshProperties);

        // when
        new OpenFgaStoreInitializer(freshBootstrapper).afterPropertiesSet();

        // then — client() 가 예외 없이 캐시된 클라이언트를 돌려준다 = 이미 해석되어 있다
        assertThatCode(freshBootstrapper::client).doesNotThrowAnyException();
    }

    @Test
    @Timeout(10)
    @DisplayName("OpenFGA 에 닿지 않아도 경고만 남기고 앱 시작을 막지 않는다")
    void 도달_불가능해도_시작을_막지_않는다() {
        // given — 아무도 응답하지 않는 주소를 가리키는 bootstrapper
        OpenFgaProperties unreachableProperties = new OpenFgaProperties();
        unreachableProperties.setApiUrl("http://127.0.0.1:1");
        unreachableProperties.setStoreName("unreachable-" + UUID.randomUUID());
        unreachableProperties.setWriteBatchSize(100);
        unreachableProperties.setMaxRetries(1);
        StoreBootstrapper unreachableBootstrapper = new StoreBootstrapper(unreachableProperties);

        // when, then — 예외를 던지지 않고 조용히 돌아온다
        assertThatCode(() -> new OpenFgaStoreInitializer(unreachableBootstrapper).afterPropertiesSet())
                .doesNotThrowAnyException();

        // and — store 가 해석되지 않았으므로 client() 는 여전히 "아직 해석 안 됐다" 예외를 던진다.
        // 실패가 캐시되지 않는다는 것은 resolveStore() 를 나중에 다시 호출하면(예: 다음 헬스체크나
        // 실제 동기화 시도) 재시도할 수 있다는 뜻이지, client() 가 예외 대신 null 을 반환한다는
        // 뜻이 아니다.
        assertThatThrownBy(unreachableBootstrapper::client)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("store 가 없고 생성이 꺼져 있으면 기동을 멈춘다 — 이름 오타인 채로 뜨지 않는다")
    void store_가_없고_생성이_꺼져_있으면_기동을_멈춘다() {
        // given — 이 컨테이너에 없는 이름과 운영 기본값(생성 꺼짐)
        OpenFgaProperties 속성 = 새_속성("init-off-");
        속성.setCreateStore(false);
        OpenFgaStoreInitializer 초기화 = new OpenFgaStoreInitializer(new StoreBootstrapper(속성));

        // when, then — 경고만 남기고 넘어가는 오류가 아니다. 설정 오류라 사람이 고쳐야 한다
        assertThatThrownBy(초기화::afterPropertiesSet).isInstanceOf(StoreNotFoundException.class);
        assertThat(countStoresNamed(속성.getStoreName())).isZero();
    }

    @Test
    @DisplayName("기동 때 OpenFGA 에 닿지 않았으면 뜨고, 닿은 뒤에도 생성이 꺼져 있으면 store 를 만들지 않고 일시 장애도 아닌 오류를 낸다")
    void 기동_때_닿지_않았으면_뜨고_닿은_뒤에도_store_를_만들지_않는다() {
        // given — 첫 목록 조회만 연결 오류로 끝나는 부트스트래퍼. 생성은 꺼져 있고 이름은 이 컨테이너에 없다
        OpenFgaProperties 속성 = 새_속성("init-late-");
        속성.setCreateStore(false);
        AtomicInteger 목록_조회 = new AtomicInteger();
        StoreBootstrapper 부트스트래퍼 = new StoreBootstrapper(속성) {
            @Override
            Mono<List<Store>> listStoresNamed() {
                return 목록_조회.getAndIncrement() == 0
                        ? Mono.error(new IOException("연결 거부"))
                        : super.listStoresNamed();
            }
        };

        // when — 시작 때는 닿지 않았다
        // then — 경고만 남기고 뜬다
        assertThatCode(() -> new OpenFgaStoreInitializer(부트스트래퍼).afterPropertiesSet())
                .doesNotThrowAnyException();

        // when — 이어 닿은 뒤 첫 해석에서 store 가 없다고 판명된다
        // then — 설정 오류다. 일시 장애(503, 재시도)가 아니고, store 도 만들지 않는다
        assertThatThrownBy(() -> 부트스트래퍼.resolveStore().block(Duration.ofSeconds(10)))
                .isInstanceOf(StoreNotFoundException.class)
                .isNotInstanceOf(TemporaryFailureException.class);
        assertThat(목록_조회.get()).isEqualTo(2);
        assertThat(countStoresNamed(속성.getStoreName())).isZero();
    }
}
