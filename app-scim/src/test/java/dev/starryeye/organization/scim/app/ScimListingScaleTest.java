package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직원 10만 명에서의 SCIM 목록·필터 조회 (S-1 설계 §8.4).
 *
 * <p>실제 운영 규모가 10만 명 이상이라 5천 명 픽스처로는 목록 방식의 비용을 말할 수 없다. 목록은 읽기만
 * 검증하므로 SCIM API 를 거치지 않고 저장소에 직접 심는다 — 5천 명 조직도(조직·멤버십 포함)에 직원만 9만 5천
 * 명을 더한다. 비용은 {@link DynamoDbReadCounter} 로 <b>읽은 아이템 수</b>를 단정한다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DynamoDbReadCounter.class)
@ScaleTest
class ScimListingScaleTest {

    private static final OrgChart 조직도 = OrgChartFixture.오천명();
    private static final int 전체 = 100_000;
    private static final int 추가 = 전체 - 조직도.snapshot().users().size();

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
    @Autowired DirectoryQueryRepository query;
    @Autowired DynamoDbReadCounter counter;

    private static String 추가아이디(int i) {
        return "extra-%06d".formatted(i);
    }

    private JsonNode 조회한다(String uriTemplate, Object... values) {
        return client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .get().uri(uriTemplate, values).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    @Test
    @Order(1)
    @DisplayName("직원 10만 명과 5천 명 조직도의 조직을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();

        // when
        state.replaceWith(조직도.snapshot()).block(Duration.ofMinutes(15));
        Flux.range(0, 추가)
                .map(i -> new DirectoryUser(추가아이디(i), "ext-" + 추가아이디(i), 추가아이디(i),
                        "추가 직원 " + i, null, true))
                .flatMap(state::saveUser, 64)
                .blockLast(Duration.ofMinutes(30));

        // then
        assertThat(query.countUsers().block()).isEqualTo((long) 전체);
        System.out.printf("심기: %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("Okta 처럼 100명씩 끝까지 가져오면 전원이 한 번씩 나오고, 읽는 양은 인원에 선형이다")
    void 전원을_가져온다() {
        // given
        counter.reset();
        Set<String> 본것 = new HashSet<>();
        long totalResults = -1;
        long 시작 = System.currentTimeMillis();

        // when
        for (long startIndex = 1; totalResults < 0 || startIndex <= totalResults; startIndex += 100) {
            JsonNode page = 조회한다("/scim/v2/Users?startIndex={s}&count=100", startIndex);
            totalResults = page.get("totalResults").asLong();
            page.get("Resources").forEach(user -> assertThat(본것.add(user.get("id").asText())).isTrue());
        }

        // then — 첫 페이지의 세기가 N, 페이지들이 N. 다 읽고 자르기였다면 N²/100 = 1억이다
        assertThat(totalResults).isEqualTo(전체);
        assertThat(본것).hasSize(전체);
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(2L * 전체 + 1_000);
        System.out.printf("가져오기: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번%n",
                System.currentTimeMillis() - 시작, counter.queries.get(),
                counter.scannedItems.get(), counter.getItems.get());
    }

    @Test
    @Order(3)
    @DisplayName("조직을 멤버까지 끝까지 가져오면 조직도와 같다")
    void 조직을_멤버까지_가져온다() {
        // given
        Map<String, Set<String>> 받은것 = new HashMap<>();
        long totalResults = -1;

        // when
        for (long startIndex = 1; totalResults < 0 || startIndex <= totalResults; startIndex += 100) {
            JsonNode page = 조회한다("/scim/v2/Groups?startIndex={s}&count=100", startIndex);
            totalResults = page.get("totalResults").asLong();
            page.get("Resources").forEach(group -> {
                Set<String> 멤버 = new HashSet<>();
                if (group.has("members")) {
                    group.get("members").forEach(member ->
                            멤버.add(member.get("type").asText() + ":" + member.get("value").asText()));
                }
                받은것.put(group.get("id").asText(), 멤버);
            });
        }

        // then
        Map<String, Set<String>> 기대 = 조직도.snapshot().groups().values().stream()
                .collect(Collectors.toMap(DirectoryGroup::id, group -> group.members().stream()
                        .map(member -> (member.type() == MemberType.GROUP ? "Group" : "User") + ":" + member.id())
                        .collect(Collectors.toSet())));
        assertThat(받은것).isEqualTo(기대);
    }

    @Test
    @Order(4)
    @DisplayName("무작위 100명을 userName(대문자로)·externalId 로 찾으면 조회마다 몇 건만 읽는다")
    void 필터_조회는_몇_건만_읽는다() {
        // given
        Random random = new Random(42);

        for (int n = 0; n < 100; n++) {
            String id = 추가아이디(random.nextInt(추가));
            counter.reset();

            // when
            JsonNode byName = 조회한다("/scim/v2/Users?filter={f}",
                    "userName eq \"" + id.toUpperCase(Locale.ROOT) + "\"");
            JsonNode byExternal = 조회한다("/scim/v2/Users?filter={f}", "externalId eq \"ext-" + id + "\"");

            // then — userName: GSI1 1건, externalId: GSI3 1건 + GetItem 1번
            assertThat(byName.get("totalResults").asLong()).isEqualTo(1);
            assertThat(byName.get("Resources").get(0).get("id").asText()).isEqualTo(id);
            assertThat(byExternal.get("Resources").get(0).get("id").asText()).isEqualTo(id);
            assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(2);
            assertThat(counter.getItems.get()).isLessThanOrEqualTo(1);
        }
    }
}
