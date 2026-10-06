package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멤버 10만 명 조직의 읽기 (읽기 경로 설계 §3.4·§4·§7).
 *
 * <p>관리 API 의 멤버 목록 첫 쪽과 조직 상세가 조직 파티션을 훑지 않는다는 것을 <b>읽은 양</b>으로 단정한다 — DynamoDB Local 의
 * 속도는 AWS 와 달라 시간으로는 아무것도 증명하지 못한다. SCIM 조직 GET 은 10만 명을 모두 싣되 흘려 쓰므로 응답에
 * {@code Content-Length} 가 없다는 것과 멤버 100,000개가 빠짐없이 실린다는 것을 단정하고, 걸린 시간은 기록만 한다.
 * 조직은 저장소에 직접 심는다 — 멤버 줄이 있고 첫 쪽 직원 20명만 레코드를 둔다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({DynamoDbReadCounter.class, TupleCheckCounter.class})
@ScaleTest
class ScimLargeGroupReadScaleTest {

    private static final int 전체 = 100_000;
    private static final String 조직 = "ALL";

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
    @Autowired DynamoDbReadCounter counter;

    @Test
    @Order(1)
    @DisplayName("멤버 10만 명 조직과 첫 쪽 직원 20명을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();
        Set<MemberRef> 전원 = new LinkedHashSet<>();
        for (int i = 0; i < 전체; i++) {
            전원.add(MemberRef.user("m%06d".formatted(i)));
        }

        // when — 첫 쪽(아이디 순 앞 20명)은 직원 레코드도 둔다. 관리 API 는 레코드가 없는 멤버를 건너뛴다
        for (int i = 0; i < 20; i++) {
            String id = "m%06d".formatted(i);
            state.saveUser(new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true)).block();
        }
        state.saveGroup(new DirectoryGroup(조직, "ext-" + 조직, "전 직원", 전원)).block(Duration.ofMinutes(30));

        // then
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isEqualTo((long) 전체);
        System.out.printf("심기: 멤버 %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("관리 API 멤버 목록 첫 쪽은 조직 파티션을 훑지 않는다 — 훑은 아이템이 쪽 크기 수준이다(점검 P4)")
    void 관리_API_첫_쪽은_쪽_크기만큼_읽는다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.get().uri("/admin/organizations/" + 조직 + "/members?limit=20").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(20)
                .jsonPath("$.nextCursor").isNotEmpty();

        // then
        System.out.printf("관리 API 멤버 첫 쪽: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번%n",
                System.currentTimeMillis() - 시작, counter.queries.get(), counter.scannedItems.get(), counter.getItems.get());
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(20);
    }

    @Test
    @Order(3)
    @DisplayName("관리 API 조직 상세도 조직 파티션을 훑지 않는다 — 하위 조직 접두와 멤버 첫 쪽만(점검 P4)")
    void 관리_API_상세도_파티션을_훑지_않는다() {
        // given
        counter.reset();

        // when
        client.get().uri("/admin/organizations/" + 조직).exchange().expectStatus().isOk();

        // then
        System.out.printf("관리 API 조직 상세: Query %,d번, 훑은 아이템 %,d%n", counter.queries.get(), counter.scannedItems.get());
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(20 + 200);
    }

    @Test
    @Order(4)
    @DisplayName("멤버 10만 명 조직 GET 은 흘려 쓴다 — 멤버 100,000개, Content-Length 없음, 걸린 시간 기록(점검 P5)")
    void SCIM_조직_GET_은_흘려_쓴다() throws Exception {
        // given
        long 시작 = System.currentTimeMillis();

        // when — 테스트 클라이언트가 응답 전체(약 3.4MB)를 받으므로 메모리 한도(기본 256KB)를 올린다. 서버 쪽 한도가 아니다
        var result = client.mutate().responseTimeout(Duration.ofMinutes(5))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)).build()
                .get().uri("/scim/v2/Groups/" + 조직).exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody().returnResult();

        // then
        JsonNode body = new ObjectMapper().readTree(result.getResponseBody());
        System.out.printf("SCIM 조직 GET(멤버 %,d명): %,dms, 응답 %,d바이트%n",
                body.get("members").size(), System.currentTimeMillis() - 시작, result.getResponseBody().length);
        assertThat(body.get("members")).hasSize(전체);
    }
}
