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
 * 여기서 확인할 유일한 것은 같은 공유 모듈의 빈들이 app-ldap 컨텍스트에도 실제로 올라와
 * 쓸 수 있는 상태인지다 — 즉 이 모듈이 app-ldap 에도 장착돼 있다는 것.
 *
 * <p><b>이 테스트는 자동설정(AutoConfiguration.imports)과 컴포넌트 스캔을 구분하지 못한다.</b>
 * 두 앱 모두 {@code scanBasePackages = "dev.starryeye.organization"} 이라 이미
 * {@code dev.starryeye.organization.admin} 패키지까지 스캔 범위에 든다. 그래서
 * {@code AutoConfiguration.imports} 에 {@code AdminQueryConfig} 항목이 없거나 잘못돼 있어도,
 * 스캔이 {@code @Configuration}({@code AdminQueryConfig})과 {@code @RestController}
 * ({@code AdminQueryController})를 그대로 찾아내 이 테스트의 두 {@code getBean(...)} 은
 * 동일하게 통과한다 — imports 파일의 기여가 스캔에 가려져 보이지 않는다. 훗날
 * {@code scanBasePackages} 를 좁혀 {@code admin} 패키지가 스캔 범위 밖으로 나가면 그때
 * imports 파일이 다시 유일한 경로가 되고, 그게 깨지면 이 테스트가 비로소 실패로 드러낸다.
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
@DisplayName("app-ldap 컨텍스트에도 admin-api 의 빈이 올라와 쓸 수 있다")
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
    @DisplayName("조회 컨트롤러와 유스케이스 빈이 컨텍스트에 존재한다")
    void 빈이_존재한다() {
        // when, then — 자동설정이든 컴포넌트 스캔이든, 결과로 이 빈들이 app-ldap 컨텍스트에
        // 있어 쓸 수 있는지가 여기서 확인할 전부다(클래스 주석 참고 — 경로 자체는 구분 못 한다)
        assertThat(context.getBean(AdminQueryController.class)).isNotNull();
        assertThat(context.getBean(AdminQueryUseCase.class)).isNotNull();
    }
}
