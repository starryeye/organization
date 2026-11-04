package dev.starryeye.organization.core.fixture;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트가 띄우는 OpenFGA·DynamoDB 컨테이너. 모든 모듈의 테스트가 이 정의 하나를 쓴다.
 *
 * <p><b>인스턴스는 호출마다 새로 만든다.</b> 클래스마다 {@code @Container static final} 필드에 담는다 — 공유하면 한 클래스가
 * 남긴 상태가 다음 클래스의 기준선이 된다.
 *
 * <p><b>"그 서비스가 답하는지" 확인하고, 아니면 새 포트로 다시 띄운다.</b> Docker Desktop(macOS)은 호스트 포트를 리눅스 VM 안에서
 * 고른다. 그래서 IntelliJ 처럼 {@code 127.0.0.1} 에만 묶은 macOS 프로그램이 이미 쓰는 번호를 줄 수 있고, 그때 {@code localhost}
 * (= {@code 127.0.0.1}) 로 가는 요청은 운영체제가 더 구체적인 쪽 — 그 프로그램 — 에 준다. 포트가 열렸는지만 보면 가짜에 속아
 * 테스트가 엉뚱한 응답(예: DynamoDB 가 HTTP 999)을 받는다. 대기 조건이 그 서비스만 주는 응답을 확인하고, 아니면
 * {@link #시작_시도} 번까지 컨테이너를 새로 띄운다(새 포트).
 */
public final class Containers {

    /** 가짜가 답하면 컨테이너를 새로 띄워 새 포트를 받는다. */
    public static final int 시작_시도 = 3;

    /** 한 번의 시도가 기다리는 최대 시간. 가짜에 걸렸을 때 다음 시도로 빨리 넘어가게 짧게 둔다. */
    public static final java.time.Duration 시작_대기 = java.time.Duration.ofSeconds(30);

    /**
     * 시험 OpenFGA 가 받는 사전 공유 키(설계 2026-10-28 §7). 서버가 이 키 없는 호출을 401 로 막으므로, OpenFGA 에 닿는 클라이언트는 앱 시험이든
     * 직접 만든 클라이언트든 모두 이 키를 단다 — 앱 시험은 각 앱의 {@code application-test.yml} 이 {@code openfga.api-token} 으로 준다.
     */
    public static final String OPENFGA_KEY = "test-openfga-key";

    private Containers() {
    }

    /**
     * OpenFGA v1.10.2(메모리 저장, 사전 공유 키 {@link #OPENFGA_KEY}). {@code /healthz} 가 {@code SERVING} 을 답할 때까지 기다린다 —
     * 사전 공유 키 모드에서도 {@code /healthz} 는 키 없이 열려 있다(v1.10.2 에서 확인), 그래서 대기 조건에 키를 싣지 않는다.
     */
    public static GenericContainer<?> openFga() {
        return new GenericContainer<>(DockerImageName.parse("openfga/openfga:v1.10.2"))
                .withCommand("run")
                .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
                .withEnv("OPENFGA_AUTHN_METHOD", "preshared")
                .withEnv("OPENFGA_AUTHN_PRESHARED_KEYS", OPENFGA_KEY)
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200)
                        .forResponsePredicate(body -> body.contains("SERVING")))
                .withStartupTimeout(시작_대기)
                .withStartupAttempts(시작_시도);
    }

    /**
     * DynamoDB Local 2.5.3(메모리, 공유 DB). 서명 없는 요청에 DynamoDB 만 주는 오류({@code MissingAuthenticationToken})가
     * 올 때까지 기다린다 — 다른 프로그램의 400 과 구별된다.
     */
    public static GenericContainer<?> dynamoDb() {
        return new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
                .withExposedPorts(8000)
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                .waitingFor(Wait.forHttp("/").forPort(8000).forStatusCode(400)
                        .forResponsePredicate(body -> body.contains("MissingAuthenticationToken")))
                .withStartupTimeout(시작_대기)
                .withStartupAttempts(시작_시도);
    }
}
