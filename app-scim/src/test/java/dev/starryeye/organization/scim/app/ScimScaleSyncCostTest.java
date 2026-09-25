package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.scim.fixture.ScimRequest;
import dev.starryeye.organization.scim.fixture.ScimRequestRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 최초 싱크 <b>한 번</b>의 실비를 잰다.
 *
 * <p>답을 얻으려는 질문: 시나리오 19건을 이 위에 쌓을 수 있는가. LDAP 은 동기화 호출 한 번이라
 * 부담이 없는데, SCIM 최초 싱크는 HTTP 요청이 수천 건이고 건건이 DynamoDB + OpenFGA 쓰기를
 * 탄다. 이게 몇 분이면 시나리오마다 처음부터 다시 채울 수 없고, 최초 싱크를 한 번만 만들고
 * 재사용하는 구조로 가야 한다.
 *
 * <p><b>처리량 측정이 아니다.</b> 로컬 컨테이너의 절대 수치는 운영과 무관하다. 재는 것은
 * 오직 "시나리오 설계를 어느 쪽으로 할 것인가" 하나다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimScaleSyncCostTest {

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

    @Test
    @DisplayName("조직도 전체 최초 싱크가 실제로 끝나고, 하네스 검증까지 통과한다")
    void 최초싱크_실비를_잰다() {
        // given
        OrgChart chart = OrgChartFixture.오천명();
        List<ScimRequest> requests = ScimRequestRenderer.최초싱크(chart);

        // when
        long t0 = System.currentTimeMillis();
        int 실패 = 요청을_전부_보낸다(requests);
        long 싱크 = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        var 검증 = new SyncVerifier(state, checker).검증한다(chart).block(Duration.ofMinutes(10));
        long 검증시간 = System.currentTimeMillis() - t1;

        System.out.printf("%n=== SCIM 최초 싱크 실비 ===%n"
                        + "요청 %d건 / 싱크 %.1f초 (건당 %.1fms) / 검증 %.1f초%n",
                requests.size(), 싱크 / 1000.0,
                (double) 싱크 / requests.size(), 검증시간 / 1000.0);

        // then
        assertThat(실패).as("실패한 요청이 있으면 실비가 아니라 결함을 잰 것이다").isZero();
        assertThat(검증).isNotNull();
        assertThat(검증.어긋났는가()).as(검증 == null ? "" : 검증.요약()).isFalse();
    }

    /**
     * 순차로 보낸다. IdP 의 실제 프로비저닝이 그렇고, 동시에 쏘면 이 테스트가 재려던 것 대신
     * 동시성 처리를 재게 된다 — 그것은 별도 시나리오(S15~S19)의 몫이다.
     */
    private int 요청을_전부_보낸다(List<ScimRequest> requests) {
        int 실패 = 0;
        // 기본 5초로는 부족하다 — 5,000명 조직도에 쓰는 요청은 부하가 걸린 머신에서 5초를 넘기고,
        // 그러면 기준 상태 적재가 끊겨 뒤의 시나리오가 전부 실패한다. 다른 스케일 테스트와 같은 2분이다
        WebTestClient 느긋한 = client.mutate().responseTimeout(Duration.ofMinutes(2)).build();
        for (ScimRequest request : requests) {
            var spec = 느긋한.post().uri(request.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.body());
            int status = spec.exchange().returnResult(Void.class)
                    .getStatus().value();
            if (status != 201) {
                실패++;
                if (실패 <= 5) {
                    System.out.println("실패: " + request.설명() + " → " + status);
                }
            }
        }
        return 실패;
    }
}
