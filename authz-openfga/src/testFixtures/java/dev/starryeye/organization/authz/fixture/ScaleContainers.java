package dev.starryeye.organization.authz.fixture;

import dev.starryeye.organization.core.fixture.Containers;
import org.testcontainers.containers.GenericContainer;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 규모 테스트가 띄우는 컨테이너. 정의는 모든 테스트가 쓰는 {@link Containers} 하나다 — 여기서는 규모 테스트가 쓰는 이름과
 * 주소 등록만 둔다.
 *
 * <p><b>인스턴스는 호출마다 새로 만든다 — 클래스끼리 공유하지 않는다.</b> DynamoDB 테이블
 * 이름과 OpenFGA store 이름이 고정이라, 공유하면 한 클래스가 남긴 상태가 다음 클래스의
 * 기준선이 된다. 클래스마다 {@code @Container static final} 필드에 이 팩토리의 결과를 담는다.
 */
public final class ScaleContainers {

    private ScaleContainers() {
    }

    public static GenericContainer<?> openFga() {
        return Containers.openFga();
    }

    public static GenericContainer<?> dynamoDb() {
        return Containers.dynamoDb();
    }

    /**
     * 두 컨테이너의 주소를 스프링 설정으로 넘긴다. {@code DynamicPropertyRegistry::add} 를
     * 그대로 넘긴다 — 이 모듈이 spring-test 에 묶이지 않게 함수로 받는다.
     */
    public static void 주소를_등록한다(BiConsumer<String, Supplier<Object>> 등록,
                                GenericContainer<?> openFga, GenericContainer<?> dynamoDb) {
        등록.accept("openfga.api-url",
                () -> "http://" + openFga.getHost() + ":" + openFga.getMappedPort(8080));
        등록.accept("dynamodb.endpoint",
                () -> "http://" + dynamoDb.getHost() + ":" + dynamoDb.getMappedPort(8000));
    }
}
