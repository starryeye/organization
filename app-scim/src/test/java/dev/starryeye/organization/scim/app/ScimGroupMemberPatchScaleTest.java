package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 멤버 10만 명 조직의 멤버 변경 (조직 멤버 PATCH 설계 §8.5).
 *
 * <p>한 명을 넣고 빼는 PATCH 가 조직 파티션을 훑지 않는다는 것을 <b>읽은 양</b>으로 단정한다 — DynamoDB Local 의 속도는 AWS 와 달라
 * 시간으로는 아무것도 증명하지 못한다. 조직은 저장소에 직접 심는다 — 멤버 줄만 있으면 된다. 바뀌지 않는 멤버의 직원 레코드와
 * 튜플은 이 경로가 보지 않으므로 심지 않는다. 10만 명 전체 교체는 HTTP 본문 한도(256KB)를 넘으므로 유스케이스를 직접 부른다.
 * ③-2 의 읽는 양(점검 P1·P2, 들어오는 멤버, POST 상위 조직)도 같은 10만 명 조직으로 잰다.
 * 조직 삭제(점검 C6)도 같은 조직으로 잰다 — 마지막 순서다. 리스 TTL 을 15초로 줄여 삭제가 갱신 없이는 끝나지 못하게 한다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({DynamoDbReadCounter.class, TupleCheckCounter.class})
@ScaleTest
class ScimGroupMemberPatchScaleTest {

    private static final int 전체 = 100_000;
    private static final String 조직 = "ALL";
    private static final int 시드_직원 = 7_000;

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);

        // 조직 삭제(약 27초)보다 짧은 리스. 갱신이 없으면 삭제가 TTL 보다 오래 걸려 리스를 잃고 실패한다(점검 C6).
        // 갱신 한 번의 시간 상한이 주기와 같으므로, 묶음 쓰기로 바쁜 DynamoDB Local 에서도 넉넉하게 5초를 준다.
        registry.add("dynamodb.lock-ttl", () -> "15s");
        registry.add("dynamodb.lock-renew-interval", () -> "5s");
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired IncrementalSyncUseCase sync;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired DynamoDbReadCounter counter;
    @Autowired TupleCheckCounter checks;
    @Autowired MutationLock lock;

    private static String 멤버(int i) {
        return "m%06d".formatted(i);
    }

    private static String 시드(int i) {
        return "s%05d".formatted(i);
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    private void 보낸다(String operations) {
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/" + 조직).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange().expectStatus().isNoContent();
    }

    private void 읽은양을_찍는다(String 이름, long 시작) {
        System.out.printf("%s: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번, BatchGet 키 %,d, Check 튜플 %,d%n",
                이름, System.currentTimeMillis() - 시작, counter.queries.get(), counter.scannedItems.get(),
                counter.getItems.get(), counter.batchGetKeys.get(), checks.checkedTuples.get());
    }

    @Test
    @Order(1)
    @DisplayName("멤버 10만 명 조직과 새 직원 둘을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();
        Set<MemberRef> 전원 = new LinkedHashSet<>();
        for (int i = 0; i < 전체; i++) {
            전원.add(MemberRef.user(멤버(i)));
        }

        // when
        state.saveUser(new DirectoryUser("newbie1", "ext-newbie1", "newbie1", "신입 1", null, true)).block();
        state.saveUser(new DirectoryUser("newbie2", "ext-newbie2", "newbie2", "신입 2", null, true)).block();
        state.saveGroup(new DirectoryGroup(조직, "ext-" + 조직, "전 직원", 전원)).block(Duration.ofMinutes(30));

        // then
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isEqualTo((long) 전체);
        System.out.printf("심기: 멤버 %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("한 명을 넣는 PATCH 는 조직 파티션을 훑지 않는다 — Query 0번, 읽기 몇 건")
    void 한명을_넣는다() {
        // given
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        보낸다("""
                [{"op":"add","path":"members","value":[{"value":"newbie1","type":"User"}]}]
                """);

        // then
        읽은양을_찍는다("한 명 넣기", 시작);
        assertThat(counter.queries.get()).as("조직 파티션을 훑지 않는다").isZero();
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(10);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(2);
        assertThat(checks.checkedTuples.get()).as("바뀌는 한 명만 Check 한다").isLessThanOrEqualTo(2);
        assertThat(check("user:newbie1", "member", "group:" + 조직)).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("한 명을 빼는 PATCH 도 조직 파티션을 훑지 않는다")
    void 한명을_뺀다() {
        // given
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        보낸다("""
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(멤버(1)));

        // then
        읽은양을_찍는다("한 명 빼기", 시작);
        assertThat(counter.queries.get()).isZero();
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(10);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(2);
        assertThat(checks.checkedTuples.get()).as("바뀌는 한 명만 Check 한다").isLessThanOrEqualTo(2);
        assertThat(state.findMembers(조직, Set.of(MemberRef.user(멤버(1)))).block()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("전체 교체는 멤버 키를 한 번 훑고 바뀐 멤버만 읽는다")
    void 전체_교체는_멤버_키만_훑는다() {
        // given — 지금 목록에서 한 명 빠지고 한 명 들어온 목록
        Set<MemberRef> 목표 = new LinkedHashSet<>(state.findMemberRefs(조직).collectList().block(Duration.ofMinutes(5)));
        목표.remove(MemberRef.user(멤버(2)));
        목표.add(MemberRef.user("newbie2"));
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when — 10만 명 본문은 HTTP 한도(256KB)를 넘으므로 유스케이스를 직접 부른다
        var result = sync.changeGroup(조직, GroupChange.replacement("ext-" + 조직, "전 직원", 목표))
                .block(Duration.ofMinutes(5));

        // then
        읽은양을_찍는다("전체 교체", 시작);
        assertThat(result.fullyApplied()).isTrue();
        assertThat(counter.scannedItems.get()).as("멤버 줄을 한 번만 훑는다").isLessThanOrEqualTo(전체 + 10);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(counter.batchGetKeys.get()).as("목록 전체를 BatchGet 으로 읽지 않는다").isLessThanOrEqualTo(10);
        assertThat(checks.checkedTuples.get()).isLessThanOrEqualTo(4);
        assertThat(check("user:newbie2", "member", "group:" + 조직)).isTrue();
        assertThat(state.findMembers(조직, Set.of(MemberRef.user(멤버(2)))).block()).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("10만 명 조직에 속한 직원 한 명을 지워도 조직 파티션을 훑지 않는다")
    void 소속_직원_삭제는_조직을_훑지_않는다() {
        // given — leaver 를 ALL 에 넣는다
        state.saveUser(new DirectoryUser("leaver", "ext-leaver", "leaver", "퇴사자", null, true)).block();
        보낸다("""
                [{"op":"add","path":"members","value":[{"value":"leaver","type":"User"}]}]
                """);
        assertThat(check("user:leaver", "member", "group:" + 조직)).isTrue();
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .delete().uri("/scim/v2/Users/leaver").exchange().expectStatus().isNoContent();

        // then — 직원 파티션만 읽는다(소속 줄 찾기, 삭제). 조직 파티션 10만 줄은 읽지 않는다
        읽은양을_찍는다("소속 직원 삭제", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(2);
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(10);
        assertThat(checks.checkedTuples.get()).isLessThanOrEqualTo(1);
        assertThat(state.findMembers(조직, Set.of(MemberRef.user("leaver"))).block()).isEmpty();
        assertThat(check("user:leaver", "member", "group:" + 조직)).isFalse();
    }

    @Test
    @Order(6)
    @DisplayName("type 없는 멤버 1,000명 추가는 판정·직원 읽기를 묶어서 한다(점검 P1 — 전: GetItem 4,002)")
    void type_없는_멤버를_묶어서_판정한다() {
        // given — 직원 7,000명(다음 테스트도 쓴다), 빈 조직 TYPELESS
        Flux.range(0, 시드_직원)
                .flatMap(i -> state.saveUser(new DirectoryUser(시드(i), "ext-" + 시드(i), 시드(i), "시드 " + i, null, true)), 16)
                .blockLast(Duration.ofMinutes(10));
        state.saveGroup(new DirectoryGroup("TYPELESS", "ext-TYPELESS", "type 없는 조직", Set.of())).block();
        String 멤버들 = IntStream.range(0, 1_000).mapToObj(i -> "{\"value\":\"%s\"}".formatted(시드(i)))
                .collect(Collectors.joining(","));
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/TYPELESS").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[%s]}]}
                        """.formatted(멤버들))
                .exchange().expectStatus().isNoContent();

        // then — 판정 키 2,000 + 멤버 확인 1,000 + 직원 1,000. GetItem 은 헤더·락 몇 번뿐
        읽은양을_찍는다("type 없는 1,000명 추가", 시작);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(4_100);
        assertThat(check("user:" + 시드(0), "member", "group:TYPELESS")).isTrue();
        assertThat(check("user:" + 시드(999), "member", "group:TYPELESS")).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("7,000명 전체 교체는 들어오는 직원을 묶어 읽는다")
    void 큰_교체는_직원을_묶어_읽는다() {
        // given
        state.saveGroup(new DirectoryGroup("BIGPUT", "ext-BIGPUT", "큰 교체", Set.of())).block();
        Set<MemberRef> 목표 = new LinkedHashSet<>();
        for (int i = 0; i < 시드_직원; i++) {
            목표.add(MemberRef.user(시드(i)));
        }
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when — 7,000명 본문은 HTTP 한도 근처라 유스케이스를 직접 부른다
        var result = sync.changeGroup("BIGPUT", GroupChange.replacement("ext-BIGPUT", "큰 교체", 목표)).block(Duration.ofMinutes(5));

        // then
        읽은양을_찍는다("7,000명 교체", 시작);
        assertThat(result.fullyApplied()).isTrue();
        assertThat(counter.getItems.get()).as("직원을 한 명씩 읽지 않는다").isLessThanOrEqualTo(20);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(시드_직원 + 100);
        assertThat(check("user:" + 시드(6_999), "member", "group:BIGPUT")).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("10만 명 조직이 먼저 적어 둔 조직을 POST 해도 상위 조직을 통째로 읽지 않는다")
    void 큰_상위_조직_밑의_POST는_헤더만_읽는다() {
        // given — ALL(10만 명)이 아직 없는 LATE 를 하위 조직으로 적어 두었다
        GroupHeader 전사 = state.findGroupHeader(조직).block();
        state.saveGroupChange(전사, 전사, Set.of(MemberRef.group("LATE")), Set.of()).block();
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"LATE","displayName":"늦게 온 조직"}
                        """)
                .exchange().expectStatus().isCreated();

        // then
        읽은양을_찍는다("10만 명 상위 조직 밑 POST", 시작);
        assertThat(counter.scannedItems.get()).as("상위 조직 파티션을 훑지 않는다").isLessThanOrEqualTo(50);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(check("group:LATE", "child", "group:" + 조직)).isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("자손 2,000개 본부를 새 상위 밑에 붙여도 순환 검사는 위로만 올라간다(점검 P2)")
    void 큰_본부를_붙여도_자손을_읽지_않는다() {
        // given — HQ ⊃ D0..D39 ⊃ 각 50팀(자손 2,040개), 새 상위 NEWTOP
        Set<MemberRef> 부서 = new LinkedHashSet<>();
        for (int d = 0; d < 40; d++) {
            Set<MemberRef> 팀 = new LinkedHashSet<>();
            for (int t = 0; t < 50; t++) {
                String 팀코드 = "T%02d_%02d".formatted(d, t);
                state.saveGroup(new DirectoryGroup(팀코드, "ext-" + 팀코드, 팀코드, Set.of())).block();
                팀.add(MemberRef.group(팀코드));
            }
            String 부서코드 = "D%02d".formatted(d);
            state.saveGroup(new DirectoryGroup(부서코드, "ext-" + 부서코드, 부서코드, 팀)).block();
            부서.add(MemberRef.group(부서코드));
        }
        state.saveGroup(new DirectoryGroup("HQ", "ext-HQ", "본부", 부서)).block();
        state.saveGroup(new DirectoryGroup("NEWTOP", "ext-NEWTOP", "새 상위", Set.of())).block();
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/NEWTOP").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"HQ","type":"Group"}]}]}
                        """)
                .exchange().expectStatus().isNoContent();

        // then — 옛 검사는 자손 2,040개를 Query 했다
        읽은양을_찍는다("자손 2,000개 본부 붙이기", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(10);
        assertThat(check("group:HQ", "child", "group:NEWTOP")).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("맨 위 조직을 마지막에 POST 해도 조직도를 훑지 않는다")
    void 맨_위_조직을_마지막에_만든다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when — 본부 HQ 를 하위로 둔 최상위 ROOT 를 만든다
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"ROOT","displayName":"최상위",
                         "members":[{"value":"HQ","type":"Group"}]}
                        """)
                .exchange().expectStatus().isCreated();

        // then
        읽은양을_찍는다("맨 위 조직 POST", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(10);
        assertThat(check("group:HQ", "child", "group:ROOT")).isTrue();
    }

    private void 직원을_만든다(String userName, HttpStatus 기대) {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s","active":true}
                        """.formatted(userName))
                .exchange().expectStatus().isEqualTo(기대);
    }

    @Test
    @Order(11)
    @DisplayName("10만 명 조직 삭제는 직원을 읽지 않고 Check 하지 않는다. 그동안 다른 쓰기는 503 이고, 끝나면 받아진다(점검 C6)")
    void 큰_조직을_지운다() throws Exception {
        // given
        counter.reset();
        checks.reset();
        AtomicInteger 들여다봄 = new AtomicInteger();
        long 시작 = System.currentTimeMillis();

        // when — 삭제를 따로 걸고, 락이 잡힌 동안 다른 쓰기를 보낸다
        CompletableFuture<Void> 삭제 = CompletableFuture.runAsync(() ->
                client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                        .delete().uri("/scim/v2/Groups/" + 조직).exchange().expectStatus().isNoContent());
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            들여다봄.incrementAndGet();
            return lock.peek().blockOptional().isPresent();
        });
        직원을_만든다("during-delete", HttpStatus.SERVICE_UNAVAILABLE);
        삭제.get(10, TimeUnit.MINUTES);

        // then
        읽은양을_찍는다("큰 조직 삭제", 시작);
        assertThat(counter.getItems.get() - 들여다봄.get()).as("멤버 직원을 읽지 않는다").isLessThanOrEqualTo(20);
        assertThat(checks.checkedTuples.get()).as("Check 없이 지운다").isZero();
        assertThat(counter.scannedItems.get()).as("조직 파티션을 한 번만 훑는다").isLessThanOrEqualTo(전체 + 20);
        assertThat(state.findGroupHeader(조직).blockOptional()).isEmpty();
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isZero();
        assertThat(state.findGroupIdsContaining(MemberRef.user(멤버(5))).collectList().block()).isEmpty();
        assertThat(check("user:newbie1", "member", "group:" + 조직)).isFalse();

        // and — 끝난 뒤에는 쓰기가 받아진다
        직원을_만든다("after-delete", HttpStatus.CREATED);
    }
}
