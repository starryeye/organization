package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.authz.fixture.ScaleVerification;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.scim.fixture.ScimIdBook;
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
 * TTL 을 재적재 소요보다 짧게 만들어야 <b>갱신이 실제로 일을 한다.</b> 재적재가 TTL 보다 오래 걸린다는 전제가 재적재 속도에
 * 기대지 않도록, 재적재의 읽기 단계(락 안)도 스파이로 TTL 보다 길게 늦춘다({@link #읽기_지연}).
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimRebuildLockScaleTest {

    private static final OrgChart 기대 = OrgChartFixture.오천명();

    /** 서버가 발급한 id 와 조직도 아이디의 대응. 요청은 보낼 때, 기대값·Check 는 볼 때 번역한다. */
    private static final ScimIdBook 번역부 = new ScimIdBook();

    private static final Duration 리스_TTL = Duration.ofSeconds(2);
    private static final Duration 갱신_주기 = Duration.ofMillis(500);

    /**
     * 재적재의 읽기 단계(락 안)를 이만큼 늦춘다 — 재적재가 리스 TTL 보다 오래 락을 쥐어야 갱신이 실제로 일을 한다.
     * 재적재 속도에 기대지 않는다: 직원을 BatchGet 으로 읽게 된 뒤(설계 2026-10-09 §4) 5천 명 재적재가 1.7초로 TTL(2초)보다 짧아졌다.
     */
    private static final Duration 읽기_지연 = 리스_TTL.plusSeconds(1);

    /**
     * 재적재 도중 쓰기를 두드리는 횟수의 안전 상한 — 헛도는 반복을 끊을 뿐이다. 반복은 재적재가 끝나서 끝나야 한다.
     * 읽기 지연({@link #읽기_지연}) 동안 쓰기 시도가 100번 넘게 쓰이므로(503 은 24ms 안팎), 상한이 작으면 장부를 고치는 뒷부분을
     * 두드리기 전에 시도를 멈춰 "기존 권한은 내내 참" 의 확인이 장부가 바뀌는 구간에서 빠진다.
     */
    private static final int 최대_시도 = 2_000;

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
        // 재적재가 쥐고 있으면 이 한도와 상관없이 프로브 쓰기는 첫 시도에서 바로 503(60초)이다 — 기다리지 않는다(설계 2026-10-07 §3.3).
        // 이 한도는 쥔 쪽이 SCIM 쓰기일 때(서버 안 줄·백오프)만 걸린다. 값은 그대로 둔다 — 기준선 적재의 503 재시도(보낸다)가 이 짧은 한도를 전제한다.
        registry.add("dynamodb.lock-acquire-timeout", () -> "500ms");
    }

    @Autowired WebTestClient client;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    /**
     * 실제 락을 감싼 스파이. 동작은 그대로다(callRealMethod) — "리스를 갱신했다" 를 직접 확인하려고 쓴다.
     * 운영 코드에 지표를 더하지 않는다.
     */
    @MockitoSpyBean MutationLock lock;

    /**
     * 실제 상태 저장소를 감싼 스파이. 동작은 그대로다(callRealMethod) — S18-b 에서만 {@code loadAll} 을 늦춘다({@link #읽기_지연}).
     * 스파이의 스텁은 테스트가 끝나면 풀리므로 다른 테스트는 늦춤 없이 돈다.
     */
    @MockitoSpyBean DirectoryStateRepository state;

    @Test
    @Order(1)
    @DisplayName("조직도 전체를 적재해 기준 상태를 만든다")
    void 기준_상태를_만든다() {
        ScimRequestRenderer.최초싱크(기대).forEach(request -> 보낸다(request, 201));
        검증한다();
    }

    @Test
    @Order(2)
    @DisplayName("S18-b. 재적재 도중 들어온 SCIM 쓰기는 503 이고, 기존 권한은 내내 참이며, 재적재는 리스를 지켜 완주한다")
    void S18b_재적재_중_쓰기와_리스() {
        // given — 앞선 기준 적재에서 쓰기가 renew 를 불렀을 수 있다. 이 시나리오의 호출만 센다
        clearInvocations(lock);
        // 재적재가 리스 TTL 보다 오래 락을 쥐게 읽기 단계를 늦춘다 — 실제 읽기는 그대로 한다
        doAnswer(invocation -> ((Mono<?>) invocation.callRealMethod()).delayElement(읽기_지연))
                .when(state).loadAll();
        RelationTuple 기존권한 = 번역부.번역한다(
                RelationTuple.member(기대.landmarks().L6직속직원(), 기대.landmarks().회사()));

        // when — 재적재를 건다. 202 는 락을 잡은 뒤에만 오므로 이 뒤의 쓰기는 재적재와 겹친다
        long t0 = System.currentTimeMillis();
        String runId = SyncJobClient.건다(client, "/admin/sync/rebuild?mode=tuples").runId();

        // 도는 동안 SCIM 쓰기를 두드리고, 기존 권한이 내내 참인지 본다 — 장부를 비우는 순간이 없어야 한다(설계 2026-09-29 §3.1)
        List<Integer> 응답들 = new ArrayList<>();
        List<Boolean> 권한들 = new ArrayList<>();
        while (도는_중이다(runId) && 응답들.size() < 최대_시도) {
            응답들.add(쓰기를_시도한다());
            권한들.add(ScaleVerification.성립하는가(checker, 기존권한));
        }
        SyncRunResponse 끝난것 = SyncJobClient.기다린다(client, runId, Duration.ofMinutes(20));
        long 소요 = System.currentTimeMillis() - t0;

        System.out.printf("%n=== S18-b. 재적재 %.1f초 / 그동안 쓰기 %d건 시도%n",
                소요 / 1000.0, 응답들.size());
        System.out.println("    응답 분포: " + 응답들.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        code -> code, java.util.TreeMap::new, java.util.stream.Collectors.counting())));

        // then — 재적재가 완주했다
        assertThat(끝난것.status()).as("재적재가 실패했다 — 리스를 잃었을 수 있다: " + 끝난것.message())
                .isEqualTo("SUCCEEDED");
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

        // 재적재 도중 기존 권한이 한 번이라도 거짓이면 인가 공백이 있다
        assertThat(권한들).isNotEmpty().doesNotContain(false);

        // 재적재가 끝난 상태는 정합이다
        검증한다();
    }

    private boolean 도는_중이다(String runId) {
        return "RUNNING".equals(SyncJobClient.조회한다(client, runId).status());
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
        ScimRequest request = 번역부.번역한다(ScimRequestRenderer.직원활성(기대.landmarks().L6직속직원()));
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchange()
                .returnResult(Void.class)
                .getStatus().value();
    }

    /**
     * 조직도 아이디로 만든 요청을 서버 id 로 번역해 보낸다. 생성(POST)이 201 이면 응답의 id 를 그 조직도 아이디에 묶어
     * 둔다 — 뒤 요청의 경로·멤버 값과 기대값·Check 가 이것으로 서버 id 가 된다.
     */
    private void 보낸다(ScimRequest 원래요청, int 기대상태) {
        ScimRequest request = 번역부.번역한다(원래요청);
        // 기준선 적재 전용 503 재시도. 이 구간(기준_상태를_만든다)은 아직 락 경합이 시작되기
        // 전이라 503 이 나온다면 그건 경합이 아니라 DynamoDB Local 의 순간 지연이 500ms 락
        // 타임아웃을 넘긴 것뿐이다 — 실제 SCIM IdP 도 503 을 "나중에 다시" 신호로 보고 재시도
        // 하도록 설계돼 있다(§1.1). S18-b 의 쓰기(쓰기를_시도한다)는 이 재시도를 타지 않는다 —
        // 거기서는 재적재 중 503 이 나오는 것 자체가 검증 대상이라 재시도하면 그 단언이 무너진다.
        int 최대시도 = 5;
        int 상태 = -1;
        String 응답 = null;
        for (int 시도 = 1; 시도 <= 최대시도; 시도++) {
            var 결과 = client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                    .post().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.body())
                    .exchange()
                    .expectBody(String.class)
                    .returnResult();
            상태 = 결과.getStatus().value();
            응답 = 결과.getResponseBody();
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
        번역부.기록한다(request, 상태, 응답);
    }

    private void 검증한다() {
        ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 번역부.번역한다(기대));
    }
}
