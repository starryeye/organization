package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.AdminQueryController;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code admin-api} 는 app-scim 의 {@code AdminQueryEndToEndTest} 에서만 E2E 로 검증한다.
 * 여기서 확인할 유일한 것은 같은 공유 모듈이 app-ldap 컨텍스트에서도 자동설정으로 잡혀
 * 빈이 뜨는지다.
 *
 * <p>{@code TableInitializer}(DynamoDB)와 {@code OpenFgaStoreInitializer}(OpenFGA) 가
 * 모두 {@code InitializingBean} 이라 실제 컨테이너 없이는 컨텍스트 자체가 기동하지 않는다 —
 * 그래서 {@link LdapSyncEndToEndTest} 와 같은 컨테이너 셋업을 그대로 가져온다. LDAP 서버는
 * 이 테스트가 실제 동기화를 수행하지 않으므로(빈 등록만 확인) 띄우지 않는다 — LdapContextSource
 * 는 지연 연결이라 application.yml 의 기본 url 로도 컨텍스트가 뜬다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("app-ldap 에서도 admin-api 자동설정이 잡힌다")
class AdminQuerySmokeTest {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("조회 컨트롤러와 유스케이스 빈이 등록된다")
    void 빈이_등록된다() {
        // when, then — 공유 모듈이 두 앱 모두에서 잡히는지가 여기서 확인할 전부다
        assertThat(context.getBean(AdminQueryController.class)).isNotNull();
        assertThat(context.getBean(AdminQueryUseCase.class)).isNotNull();
    }
}
