package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.OpenFgaProbe;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.RollupSampling;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.scim.fixture.ScimRequest;
import dev.starryeye.organization.scim.fixture.ScimRequestRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 S1 — 대량 프로비저닝을 <b>조직 먼저</b> 돌린다.
 *
 * <p>{@code ScimScaleScenarioTest} 의 S2 는 직원 먼저다. 순서를 뒤집으면 조직이 <b>아직
 * 도착하지 않은 직원</b>을 멤버로 참조하는 상태가 생긴다. {@code TupleMapper} 는 그런 멤버를
 * "스냅샷에 없어 건너뜁니다" 로 미뤄 두는데, <b>그 직원이 나중에 도착할 때 튜플이 만들어지는지</b>
 * 가 이 시나리오의 요점이다. 5,000 규모로 그 경로를 탄다.
 *
 * <p>그리고 <b>순서가 결과를 바꾸면 안 된다.</b> 두 순서 중 하나만 테스트하면 그걸 못 본다 —
 * 최종 상태는 S2 와 같아야 하고, 같은 하네스로 잰다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimProvisioningOrderScaleTest {

    private static final OrgChart 기대 = OrgChartFixture.오천명();

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

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    @Test
    @Order(1)
    @DisplayName("S1-a. 조직을 먼저 만들면 아직 없는 직원을 참조하는 상태가 생긴다")
    void S1a_조직만_먼저() {
        // given — 깊은 곳부터. 하위 조직은 이미 있고 직원만 없는 상태를 만든다
        List<ScimRequest> 조직요청 = 조직요청들();

        // when
        조직요청.forEach(request -> 보낸다(request, 201));

        // then — 조직은 다 만들어졌지만 직원이 없으므로 그 멤버십의 튜플은 아직 없다.
        // TupleMapper 가 "스냅샷에 없어 건너뜁니다" 로 미뤄 둔 상태다.
        String 대표직원 = 기대.landmarks().L6직속직원();
        assertThat(성립하는가(RelationTuple.member(대표직원, 기대.직속조직(대표직원))))
                .as("직원이 아직 없는데 튜플이 생겼다").isFalse();

        // child 간선은 조직끼리라 이미 성립한다 — 조직은 둘 다 도착했기 때문이다
        String 팀 = 기대.landmarks().이동할팀();
        assertThat(성립하는가(RelationTuple.child(팀, 기대.부모(팀))))
                .as("조직끼리의 계층은 직원과 무관하게 성립해야 한다").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("S1-b. 늦게 도착한 직원의 튜플이 그때 만들어진다")
    void S1b_늦게_온_직원() {
        // given
        List<ScimRequest> 직원요청 = 직원요청들();

        // when
        long t0 = System.currentTimeMillis();
        직원요청.forEach(request -> 보낸다(request, 201));
        System.out.printf("%n=== S1. 조직 먼저 순서 — 직원 %d명 도착: %.1f초%n",
                직원요청.size(), (System.currentTimeMillis() - t0) / 1000.0);

        // then — 미뤄져 있던 멤버십이 전부 튜플이 됐다
        검증한다();
    }

    @Test
    @Order(3)
    @DisplayName("S1-c. 최종 상태가 S2(직원 먼저)와 같다 — 순서가 결과를 바꾸면 안 된다")
    void S1c_순서와_무관하다() {
        // given, when — 하네스가 같은 기대 조직도로 통과한다는 것이 곧 동일성이다.
        // S2 도 같은 조직도, 같은 하네스로 통과하므로 두 순서의 도달점이 같다.
        검증한다();

        // then — 깊이별 대표 직원의 롤업이 전부 성립한다
        var l = 기대.landmarks();
        List.of(l.L2직속직원(), l.L3직속직원(), l.L4직속직원(), l.L5직속직원(), l.L6직속직원())
                .forEach(직원 -> 기대.기대소속(직원).forEach(org ->
                        assertThat(성립하는가(RelationTuple.member(직원, org)))
                                .as("%s 의 %s 롤업이 끊겼다", 직원, org).isTrue()));

        // 겸직 직원의 두 갈래도
        String 겸직 = l.겸직직원();
        assertThat(기대.직속조직들(겸직)).hasSize(2);
        기대.기대소속(겸직).forEach(org ->
                assertThat(성립하는가(RelationTuple.member(겸직, org))).isTrue());
    }

    // ---------- 거들기 ----------

    /** 조직만, 깊은 곳부터. 부모보다 자식이 먼저 만들어져야 참조가 성립한다. */
    private List<ScimRequest> 조직요청들() {
        List<ScimRequest> requests = new ArrayList<>();
        기대.snapshot().groups().values().stream()
                .sorted(Comparator.comparingInt((DirectoryGroup group) ->
                                기대.조상들(group.id()).size()).reversed()
                        .thenComparing(DirectoryGroup::id))
                .forEach(group -> requests.add(ScimRequestRenderer.조직생성(group)));
        return requests;
    }

    /** 직원만, 아이디 정렬 순. 실행마다 순서가 달라지면 실패가 재현되지 않는다. */
    private List<ScimRequest> 직원요청들() {
        List<ScimRequest> requests = new ArrayList<>();
        기대.snapshot().users().values().stream()
                .sorted(Comparator.comparing(
                        dev.starryeye.organization.core.model.DirectoryUser::id))
                .forEach(user -> requests.add(ScimRequestRenderer.직원생성(user)));
        return requests;
    }

    private void 보낸다(ScimRequest request, int 기대상태) {
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .post().uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchange()
                .expectStatus().isEqualTo(기대상태);
    }

    private void 검증한다() {
        var 하네스 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(하네스).isNotNull();
        assertThat(하네스.어긋났는가()).as(하네스 == null ? "" : 하네스.요약()).isFalse();

        var 직접 = new OpenFgaProbe(bootstrapper)
                .직접_대조한다(기대, RollupSampling.기본값().표본을_고른다(기대));
        assertThat(직접.어긋났는가()).as(직접.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }
}
