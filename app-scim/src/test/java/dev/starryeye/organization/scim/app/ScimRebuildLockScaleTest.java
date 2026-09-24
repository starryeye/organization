package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.authz.fixture.ScaleVerification;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * 시나리오 S18 의 나머지 — 재적재가 <b>락을 오래 쥐는 동안</b> 무슨 일이 벌어지는가.
 *
 * <p>{@code ScimLimitsAndRecoveryScaleTest} 의 S18 은 재적재가 어긋남을 메우고 고아 튜플을
 * 지우는 것까지 봤다. 여기서 보는 것은 그 <b>도중</b>이다 — 리스가 유지되는가, 그리고 그동안
 * 들어오는 SCIM 쓰기가 어떻게 거절되는가.
 *
 * <p><b>리스 TTL 을 2초로 줄인다.</b> 기본값 30초로는 재적재가 그보다 빨리 끝나
 * 하트비트가 한 번도 필요하지 않다 — 갱신이 통째로 망가져 있어도 테스트가 통과한다.
 * TTL 을 재적재 소요보다 짧게 만들어야 <b>갱신이 실제로 일을 한다.</b>
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimRebuildLockScaleTest {

    private static final OrgChart 기대 = OrgChartFixture.오천명();

    private static final Duration 리스_TTL = Duration.ofSeconds(2);
    private static final Duration 갱신_주기 = Duration.ofMillis(500);

    /**
     * renew 가 이만큼 불렸다면 리스가 제 TTL 을 넘겨 살아있었다는 뜻이다 — TTL 을 갱신 주기로
     * 나눈 몫만큼 갱신 주기가 지나야 TTL 이 넘어가고, 거기에 한 번을 더해야 "넘겼다" 를 증명한다.
     */
    private static final int 최소_갱신_횟수 = (int) (리스_TTL.toMillis() / 갱신_주기.toMillis()) + 1;

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);

        // 재적재보다 짧은 리스. 갱신이 안 돌면 재적재가 리스를 잃고 FAILED 로 끝난다.
        registry.add("dynamodb.lock-ttl", () -> 리스_TTL.toMillis() + "ms");
        registry.add("dynamodb.lock-renew-interval", () -> 갱신_주기.toMillis() + "ms");
        // 짧게 잡는다. 기본 3초로 두면 프로브 쓰기가 3초를 기다렸다가 재적재가 끝난 뒤
        // 성공해 버려서 "재적재 중에는 거절된다" 를 볼 수 없다.
        registry.add("dynamodb.lock-acquire-timeout", () -> "500ms");
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    /**
     * 실제 락을 감싼 스파이. 동작은 그대로다(callRealMethod) — "재적재가 락을 잡았다" 를
     * 기다리고 "리스를 갱신했다" 를 직접 확인하려고 쓴다. 운영 코드에 지표를 더하지 않는다.
     */
    @MockitoSpyBean MutationLock lock;

    @Test
    @Order(1)
    @DisplayName("조직도 전체를 적재해 기준 상태를 만든다")
    void 기준_상태를_만든다() {
        ScimRequestRenderer.최초싱크(기대).forEach(request -> 보낸다(request, 201));
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("S18-b. 재적재 도중 들어온 SCIM 쓰기는 503 이고, 재적재는 리스를 지켜 완주한다")
    void S18b_재적재_중_쓰기와_리스() throws Exception {
        // given — 앞선 기준 적재에서 쓰기가 renew 를 불렀을 수 있다. 이 시나리오의 호출만 센다
        clearInvocations(lock);

        // 재적재가 락을 잡는 순간을 알린다. sleep 으로 "아마 잡았겠지" 를 바라지 않는다 —
        // 곧바로 쓰기를 두드리면 쓰기가 먼저 락을 쥐고 재적재가 409 로 튕긴다
        CountDownLatch 재적재가_락을_잡았다 = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Mono<LockLease> 실제 = (Mono<LockLease>) invocation.callRealMethod();
            return invocation.getArgument(0) == MutationLock.LockPurpose.REBUILD
                    ? 실제.doOnSuccess(lease -> 재적재가_락을_잡았다.countDown())
                    : 실제;
        }).when(lock).acquire(any());

        long t0 = System.currentTimeMillis();
        CompletableFuture<Integer> 재적재 = CompletableFuture.supplyAsync(() ->
                client.mutate().responseTimeout(Duration.ofMinutes(20)).build()
                        .post().uri("/admin/sync/rebuild?mode=tuples").exchange()
                        .returnResult(Void.class).getStatus().value());
        assertThat(재적재가_락을_잡았다.await(1, TimeUnit.MINUTES))
                .as("재적재가 1분 안에 락을 잡지 못했다").isTrue();

        // when — 도는 동안 SCIM 쓰기를 계속 두드린다
        List<Integer> 응답들 = new ArrayList<>();
        while (!재적재.isDone() && 응답들.size() < 200) {
            응답들.add(쓰기를_시도한다());
        }
        int 재적재응답 = 재적재.get(20, java.util.concurrent.TimeUnit.MINUTES);
        long 소요 = System.currentTimeMillis() - t0;

        System.out.printf("%n=== S18-b. 재적재 %.1f초 / 그동안 쓰기 %d건 시도%n",
                소요 / 1000.0, 응답들.size());
        System.out.println("    응답 분포: " + 응답들.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        code -> code, java.util.TreeMap::new, java.util.stream.Collectors.counting())));

        // then — 재적재가 완주했다
        assertThat(재적재응답).as("재적재가 실패했다 — 리스를 잃었을 수 있다").isEqualTo(200);
        // 리스 갱신이 TTL 을 넘길 만큼 여러 번 일했다. 쓰기는 델타가 있을 때만 renew 를
        // 부르고(설계 §4.7) 여기서 두드린 쓰기는 이미 활성인 직원에게 active:true 를 보내
        // 델타가 없다 — 그러므로 이 호출들은 재적재의 하트비트다. 한 번만 불렸다는 것으로는
        // 재적재가 갱신 주기만큼만 돌았다는 것만 보일 뿐 리스가 제 만료를 넘겨 살아남았다는
        // 주장은 못 한다 — 그래서 최소 횟수를 요구한다
        verify(lock, atLeast(최소_갱신_횟수)
                .description("renew 가 " + 최소_갱신_횟수 + "번 미만으로 불렸다 — 재적재가 리스가"
                        + " 만료됐을 시점보다 먼저 끝나 갱신이 필요 없었다는 뜻이라, 이 테스트의 전제"
                        + "(재적재가 리스 TTL 보다 오래 걸린다)가 이 환경에서는 성립하지 않는다"))
                .renew(any());

        // 그동안 들어온 쓰기는 503 이다. IdP 는 503 을 재시도 신호로 보므로 유실되지 않는다
        assertThat(응답들).as("재적재 중에 쓰기를 한 번도 못 시도했다").isNotEmpty();
        assertThat(응답들).as("503 이외의 거절이 있었다 — IdP 가 영구 실패로 볼 수 있다")
                .allMatch(code -> code == 503 || code == 200);
        assertThat(응답들).as("재적재가 락을 쥐고 있는데 쓰기가 한 건도 안 막혔다")
                .contains(503);

        // 재적재가 끝난 상태는 정합이다
        검증한다();
    }

    @Test
    @Order(3)
    @DisplayName("재적재가 끝나면 SCIM 쓰기가 다시 받아들여진다")
    void 재적재_후_쓰기가_재개된다() {
        // when, then — 락이 반납됐으므로 200 이다
        assertThat(쓰기를_시도한다()).isEqualTo(200);
        검증한다();
    }

    // ---------- 거들기 ----------

    /**
     * 최종 상태를 바꾸지 않는 쓰기. 이미 활성인 직원에게 {@code active:true} 를 보낸다 —
     * 성공하든 503 이든 기대 조직도가 흔들리지 않아야 재적재 전후를 같은 잣대로 잰다.
     */
    private int 쓰기를_시도한다() {
        ScimRequest request = ScimRequestRenderer.직원활성(기대.landmarks().L6직속직원());
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchange()
                .returnResult(Void.class)
                .getStatus().value();
    }

    private void 보낸다(ScimRequest request, int 기대상태) {
        // 기준선 적재 전용 503 재시도. 이 구간(기준_상태를_만든다)은 아직 락 경합이 시작되기
        // 전이라 503 이 나온다면 그건 경합이 아니라 DynamoDB Local 의 순간 지연이 500ms 락
        // 타임아웃을 넘긴 것뿐이다 — 실제 SCIM IdP 도 503 을 "나중에 다시" 신호로 보고 재시도
        // 하도록 설계돼 있다(§1.1). S18-b 의 쓰기(쓰기를_시도한다)는 이 재시도를 타지 않는다 —
        // 거기서는 재적재 중 503 이 나오는 것 자체가 검증 대상이라 재시도하면 그 단언이 무너진다.
        int 최대시도 = 5;
        int 상태 = -1;
        for (int 시도 = 1; 시도 <= 최대시도; 시도++) {
            상태 = client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                    .post().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.body())
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus().value();
            if (상태 != 503) {
                break;
            }
            if (시도 < 최대시도) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertThat(상태).as("기준선 적재 중 503 이 반복돼 재시도로도 회복되지 않았다").isEqualTo(기대상태);
    }

    private void 검증한다() {
        ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대);
    }
}
