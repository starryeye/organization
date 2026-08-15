package dev.starryeye.organization.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;

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
}
