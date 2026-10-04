package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.authz.fixture.ScaleVerification;
import dev.starryeye.organization.core.fixture.ChartExpectation;
import dev.starryeye.organization.core.fixture.Membership;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 S1 — 대량 프로비저닝을 <b>조직 먼저</b> 돌린다.
 *
 * <p>{@code ScimScaleScenarioTest} 의 S2 는 직원 먼저다. 서버가 id 를 발급하므로(설계 2026-10-04 §3.1) IdP 는 <b>만든
 * 리소스의 id 로만</b> 서로를 참조할 수 있다 — 조직이 아직 도착하지 않은 직원을 멤버로 적어 두는 순서는 SCIM 에서 생기지 않는다.
 * Entra 의 실제 순서는 이렇다: 조직을 <b>멤버 없이</b> 먼저 만들고 → 직원을 만들고 → 조직마다 멤버를 PATCH 로 더한다.
 * 멤버가 더해지는 그때 비로소 튜플이 만들어지는지가 이 시나리오의 요점이다. 전체 조직도 규모로 그 경로를 탄다.
 *
 * <p>그리고 <b>순서가 결과를 바꾸면 안 된다.</b> 두 순서 중 하나만 테스트하면 그걸 못 본다 —
 * 최종 상태는 S2 와 같아야 하고, 같은 하네스로 잰다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimProvisioningOrderScaleTest {

    private static final OrgChart 기대 = OrgChartFixture.오천명();

    /** 서버가 발급한 id 와 조직도 아이디의 대응. 요청은 보낼 때, 기대값·Check 는 볼 때 번역한다. */
    private static final ScimIdBook 번역부 = new ScimIdBook();

    /** 조직 하나에 한 번에 싣는 멤버 수. 1,600명짜리 조직도 본문이 HTTP 한도(256KB)에 닿지 않게 나눈다. */
    private static final int 멤버묶음 = 500;

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired RelationTupleChecker checker;
    @Autowired StoreBootstrapper bootstrapper;

    @Test
    @Order(1)
    @DisplayName("S1-a. 조직을 먼저 멤버 없이 만들면 빈 조직만 생기고 하위 조직 간선도 없다")
    void S1a_조직만_먼저() {
        // given — 깊은 곳부터, 멤버 없이. 직원도 조직 사이의 참조도 아직 없다
        List<ScimRequest> 조직요청 = 조직요청들();

        // when
        조직요청.forEach(request -> 보낸다(request, 201));

        // then — 조직 전부가 멤버 없이 만들어졌고 직원은 없다. 조직 사이의 child 간선은 하나도 없어야 한다 — 조직이 만들어진다고
        // 권한이 생기지는 않는다. 직원 소속 튜플은 여기서 묻지 않는다: 직원이 아직 없어 서버 id 가 없으므로 어떤 답이 와도 의미가 없다(S1-b 가 잰다).
        // 롤업 표본은 직원이 없어 비고, ④ 는 이 단계에서 할 일이 없다.
        검증한다(ChartExpectation.of(번역부.번역한다(조직만_있는_조직도())));
    }

    @Test
    @Order(2)
    @DisplayName("S1-b. 늦게 도착한 직원은 조직에 멤버로 더해질 때 튜플이 만들어진다")
    void S1b_늦게_온_직원() {
        // given — S1-a 의 빈 조직들 위에, 직원 요청과 조직마다의 멤버 추가 PATCH 를 준비한다
        List<ScimRequest> 직원요청 = 직원요청들();
        List<ScimRequest> 멤버요청 = 멤버요청들();

        // when — 직원이 먼저 도착한다. 아직 어느 조직의 멤버도 아니다
        long t0 = System.currentTimeMillis();
        직원요청.forEach(request -> 보낸다(request, 201));

        // then — 직원 전부와 빈 조직들이 있고, 모든 멤버십의 튜플이 없다. 직원과 조직이 모두 서버 id 를 받았으므로
        // "없어야 한다" 는 후보 하나하나가 서버 id 로 묻는 진짜 Check 다
        검증한다(ChartExpectation.of(번역부.번역한다(조직과_직원만_있는_조직도())));

        // when — IdP 가 조직마다 멤버(직원과 하위 조직)를 PATCH 로 더한다
        멤버요청.forEach(request -> 보낸다(request, 204));
        System.out.printf("%n=== S1. 조직 먼저 순서 — 직원 %d명 도착 + 멤버 PATCH %d건: %.1f초%n",
                직원요청.size(), 멤버요청.size(), (System.currentTimeMillis() - t0) / 1000.0);

        // then — 멤버로 더해지는 그때 튜플이 전부 만들어진다
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

    /** 조직만, 멤버 없이. 순서는 실행마다 같도록 깊은 곳부터 아이디 순으로 고정한다. */
    private List<ScimRequest> 조직요청들() {
        List<ScimRequest> requests = new ArrayList<>();
        깊은_곳부터_조직들().forEach(group -> requests.add(ScimRequestRenderer.조직생성(
                new DirectoryGroup(group.id(), group.externalId(), group.displayName(), Set.of()))));
        return requests;
    }

    /** 직원만, 아이디 정렬 순. 실행마다 순서가 달라지면 실패가 재현되지 않는다. */
    private List<ScimRequest> 직원요청들() {
        List<ScimRequest> requests = new ArrayList<>();
        기대.snapshot().users().values().stream()
                .sorted(Comparator.comparing(DirectoryUser::id))
                .forEach(user -> requests.add(ScimRequestRenderer.직원생성(user)));
        return requests;
    }

    /** 조직마다 멤버(직원과 하위 조직)를 {@link #멤버묶음}명씩 더하는 PATCH. 멤버가 없는 조직은 건너뛴다. */
    private List<ScimRequest> 멤버요청들() {
        List<ScimRequest> requests = new ArrayList<>();
        깊은_곳부터_조직들().forEach(group -> {
            List<MemberRef> 멤버 = group.members().stream()
                    .sorted(Comparator.comparing(MemberRef::type).thenComparing(MemberRef::id))
                    .toList();
            for (int 시작 = 0; 시작 < 멤버.size(); 시작 += 멤버묶음) {
                requests.add(ScimRequestRenderer.멤버들추가(group.id(),
                        멤버.subList(시작, Math.min(시작 + 멤버묶음, 멤버.size()))));
            }
        });
        return requests;
    }

    /** 깊이가 깊은 조직부터, 같은 깊이는 아이디 순. */
    private List<DirectoryGroup> 깊은_곳부터_조직들() {
        return 기대.snapshot().groups().values().stream()
                .sorted(Comparator.comparingInt((DirectoryGroup group) ->
                                기대.조상들(group.id()).size()).reversed()
                        .thenComparing(DirectoryGroup::id))
                .toList();
    }

    /**
     * 조직을 만들고 서버 id 로 번역해 보낸다. 생성(POST)이 201 이면 응답의 id 를 그 조직도 아이디에 묶어 둔다 —
     * 뒤 요청의 경로·멤버 값과 기대값·Check 가 이것으로 서버 id 가 된다.
     */
    private void 보낸다(ScimRequest 원래요청, int 기대상태) {
        ScimRequest request = 번역부.번역한다(원래요청);
        WebTestClient 느긋한 = client.mutate().responseTimeout(Duration.ofMinutes(2)).build();
        var spec = switch (request.method()) {
            case "POST" -> 느긋한.post().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body());
            case "PATCH" -> 느긋한.patch().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(request.body());
            default -> throw new IllegalArgumentException("알 수 없는 메서드: " + request.method());
        };
        var 응답 = spec.exchange().expectStatus().isEqualTo(기대상태);
        if (기대상태 == 201) {
            번역부.기록한다(request, 기대상태, 응답.expectBody(String.class).returnResult().getResponseBody());
        }
    }

    private void 검증한다() {
        검증한다(ChartExpectation.of(번역부.번역한다(기대)));
    }

    private void 검증한다(ChartExpectation 기대값) {
        ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 기대값);
    }

    /**
     * 조직만 만들어진 상태 — 모든 조직이 멤버 없이 있고 직원은 없다. 아직 없는 <b>조직 사이의 간선</b>을 "지워진 멤버십" 칸에 넣는다:
     * 하네스는 그 칸의 멤버십을 "튜플이 없어야 한다" 고 묻는다. 직원 소속은 넣지 않는다 — 직원이 아직 없어 서버 id 가 없고,
     * 조직도 아이디 그대로 묻는 Check 는 서버가 무엇을 했든 false 라 아무것도 증명하지 못한다.
     */
    private static OrgChart 조직만_있는_조직도() {
        Set<Membership> 아직_없는_간선 = 기대.멤버십들().stream()
                .filter(멤버십 -> 멤버십.멤버().type() == MemberType.GROUP)
                .collect(Collectors.toSet());
        return new OrgChart(new DirectorySnapshot(Map.of(), 빈조직들()), 기대.landmarks(), 아직_없는_간선);
    }

    /**
     * 직원이 도착했지만 아직 어느 조직에도 안 들어간 상태 — 직원 전부와 빈 조직들. 원래의 모든 멤버십을 "지워진 멤버십" 칸에 넣어
     * "튜플이 없어야 한다" 고 묻는다. 직원과 조직이 모두 만들어져 서버 id 를 받았으므로 후보 전부가 서버 id 로 묻는 진짜 음성이다.
     */
    private static OrgChart 조직과_직원만_있는_조직도() {
        return new OrgChart(new DirectorySnapshot(기대.snapshot().users(), 빈조직들()), 기대.landmarks(), 기대.멤버십들());
    }

    private static Map<String, DirectoryGroup> 빈조직들() {
        Map<String, DirectoryGroup> 빈조직들 = new LinkedHashMap<>();
        기대.snapshot().groups().values().forEach(group -> 빈조직들.put(group.id(),
                new DirectoryGroup(group.id(), group.externalId(), group.displayName(), Set.of())));
        return 빈조직들;
    }

    private boolean 성립하는가(RelationTuple tuple) {
        return ScaleVerification.성립하는가(checker, 번역부.번역한다(tuple));
    }
}
