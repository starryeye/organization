package dev.starryeye.organization.authz;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * OpenFGA 컨테이너를 띄운다. v1.10.0 이상이어야 on_duplicate / on_missing 이 동작한다.
 * 테스트마다 store 이름을 새로 만들어 서로 간섭하지 않게 한다.
 */
@Testcontainers
public abstract class OpenFgaTestSupport {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    protected OpenFgaProperties properties;
    protected StoreBootstrapper bootstrapper;

    @BeforeEach
    void OpenFGA를_준비한다() {
        properties = new OpenFgaProperties();
        properties.setApiUrl("http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        properties.setStoreName("test-" + UUID.randomUUID());
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);

        bootstrapper = new StoreBootstrapper(properties);
        bootstrapper.resolveStore().block();
    }
}
