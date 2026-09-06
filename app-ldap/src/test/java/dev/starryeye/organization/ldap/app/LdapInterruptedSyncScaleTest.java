package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.ldap.fixture.LdifRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 L15 — 동기화가 상태 저장 직전에 끊긴 뒤, 다음 회차가 스스로 수렴하는가.
 *
 * <p>쓰기 순서는 OpenFGA → 스냅샷 → 상태다. 그래서 상태 저장 직전에 죽으면 <b>OpenFGA 는
 * 반영됐고 상태만 낡은</b> 어긋난 지점이 남는다. 설계 §1.1 은 이것을 별도 보정 장치 없이
 * <b>다음 회차의 재시도가 수렴시킨다</b>고 전제하는데, 그 전제를 5,000 규모에서 실증한다.
 *
 * <p>수렴이 성립하는 이유는 <b>빈 델타 경로도 {@code state.replaceWith} 를 부르기</b>
 * 때문이다. 다음 회차는 스냅샷이 이미 갱신돼 있어 델타가 비는데, 그때 아무것도 안 하고
 * 끝내면 상태는 영원히 낡은 채로 남는다.
 *
 * <p>프로세스를 실제로 죽이는 대신 {@code replaceWith} 만 한 번 실패시킨다. 재려는 것은
 * 프로세스 종료가 아니라 <b>그 지점에서 끊겼을 때 다음 회차가 메우는가</b> 이고, 그것은
 * 이 방법으로 정확히 같은 상태가 된다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LdapInterruptedSyncScaleTest {

    private static final String BASE_DN = "dc=example,dc=com";
    private static final OrgChart 기대 = OrgChartFixture.오천명();

    /** 다음 {@code replaceWith} 를 터뜨릴지. 첫 회차만 켠다. */
    static final AtomicBoolean 상태저장을_끊는다 = new AtomicBoolean(true);

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

    static InMemoryDirectoryServer LDAP;

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials("cn=admin," + BASE_DN, "adminpassword");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("interrupted", 0));
        config.setSchema(null);

        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(
                new LdifRenderer(BASE_DN).render(기대).getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();

        registry.add("ldap.url", () -> "ldap://localhost:" + LDAP.getListenPort());
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @TestConfiguration
    static class 상태저장을_한_번_끊는_설정 {

        @Bean
        @Primary
        DirectoryStateRepository 끊기는_상태저장소(DirectoryStateRepository 실제) {
            return new 한번만_실패하는_상태저장소(실제);
        }
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;

    @Test
    @Order(1)
    @DisplayName("L15-a. 상태 저장 직전에 끊기면 OpenFGA 만 반영되고 상태는 낡는다")
    void L15a_상태저장_직전에_끊긴다() {
        // when
        동기화한다().jsonPath("$.status").isEqualTo("FAILED");

        // then — OpenFGA 는 이미 써졌다
        assertThat(성립하는가(RelationTuple.member(
                기대.landmarks().L6직속직원(), 기대.landmarks().회사())))
                .as("쓰기 순서상 OpenFGA 는 상태보다 먼저 반영된다").isTrue();

        // 상태만 비어 있다 — 이것이 재시도가 메워야 할 어긋난 지점이다
        var 상태 = state.loadAll().block(Duration.ofMinutes(1));
        assertThat(상태).isNotNull();
        assertThat(상태.users()).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("L15-b. 다음 회차가 빈 델타로도 상태를 다시 써서 수렴한다")
    void L15b_다음_회차가_수렴시킨다() {
        // given
        상태저장을_끊는다.set(false);

        // when — 스냅샷은 이미 갱신돼 있으므로 델타가 빈다
        동기화한다()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");

        // then — 빈 델타 경로가 상태를 다시 썼다. 별도 보정 장치 없이 수렴했다
        검증한다();
    }

    // ---------- 거들기 ----------

    private WebTestClient.BodyContentSpec 동기화한다() {
        return client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                .post().uri("/admin/sync/full").exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private void 검증한다() {
        var 결과 = new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10));
        assertThat(결과).isNotNull();
        assertThat(결과.어긋났는가()).as(결과 == null ? "" : 결과.요약()).isFalse();
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }

    /** {@code replaceWith} 만 골라 끊는다. 나머지는 전부 실제 저장소로 흘려보낸다. */
    private record 한번만_실패하는_상태저장소(DirectoryStateRepository 실제)
            implements DirectoryStateRepository {

        @Override
        public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
            if (상태저장을_끊는다.get()) {
                return Mono.error(new IllegalStateException("상태 저장 직전에 프로세스가 죽었다"));
            }
            return 실제.replaceWith(snapshot);
        }

        @Override public Mono<DirectoryUser> findUser(String userId) { return 실제.findUser(userId); }
        @Override public Flux<String> findUserIdsByUserName(String userName) {
            return 실제.findUserIdsByUserName(userName);
        }
        @Override public Mono<DirectoryGroup> findGroup(String groupId) { return 실제.findGroup(groupId); }
        @Override public Mono<Void> saveUser(DirectoryUser user) { return 실제.saveUser(user); }
        @Override public Mono<Void> saveGroup(DirectoryGroup group) { return 실제.saveGroup(group); }
        @Override public Mono<Void> deleteUser(String userId) { return 실제.deleteUser(userId); }
        @Override public Mono<Void> deleteGroup(String groupId) { return 실제.deleteGroup(groupId); }
        @Override public Flux<String> findGroupIdsContaining(MemberRef ref) {
            return 실제.findGroupIdsContaining(ref);
        }
        @Override public Mono<DirectorySnapshot> loadAll() { return 실제.loadAll(); }
    }
}
