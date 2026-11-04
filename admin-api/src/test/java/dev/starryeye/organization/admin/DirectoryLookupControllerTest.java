package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeSearchRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.TemporaryFailureClassifier;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import dev.starryeye.organization.core.usecase.DirectoryLookupUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryLookupControllerTest {

    private final FakeStateRepository state = new FakeStateRepository();
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        var lookup = new DirectoryLookupUseCase(state, new FakeQueryRepository(state));
        // 상세 조회 컨트롤러도 함께 묶는다 — 정확 조회 경로가 /employees/{employeeId} 에 먹히지 않는지 본다
        var query = new AdminQueryController(
                new AdminQueryUseCase(state, new FakeSearchRepository(state), new FakeTupleChecker()),
                new AdminQueryMetrics(new SimpleMeterRegistry()));
        client = WebTestClient.bindToController(new DirectoryLookupController(lookup), query)
                .controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))
                .build();
    }

    private void 직원을_넣는다(String id, String externalId, String userName, boolean active) {
        state.users.put(id, new DirectoryUser(id, externalId, userName, userName + "-이름", null, active));
    }

    private void 조직을_넣는다(String code, String externalId, String displayName) {
        state.groups.put(code, new DirectoryGroup(code, externalId, displayName, Set.of()));
    }

    @Test
    @DisplayName("한 명이면 200 이고 UserSummary 를 준다 — userName 으로도 externalId 로도")
    void 한_명이면_200() {
        // given
        직원을_넣는다("u-1", "okta-00u1", "kim", true);
        직원을_넣는다("u-2", "okta-00u2", "lee", true);

        // when, then — userName 은 대소문자를 무시한다
        client.get().uri("/admin/employees/lookup?userName=KIM")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.employeeId").isEqualTo("u-1")
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.displayName").isEqualTo("kim-이름")
                .jsonPath("$.active").isEqualTo(true)
                .jsonPath("$.externalId").isEqualTo("okta-00u1");
        client.get().uri("/admin/employees/lookup?externalId=okta-00u2")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.employeeId").isEqualTo("u-2")
                .jsonPath("$.userName").isEqualTo("lee");
    }

    @Test
    @DisplayName("비활성 직원도 200 이고 active 가 false 다")
    void 비활성_직원도_200() {
        // given
        직원을_넣는다("u-1", "okta-00u1", "kim", false);

        // when, then
        client.get().uri("/admin/employees/lookup?userName=kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.employeeId").isEqualTo("u-1")
                .jsonPath("$.active").isEqualTo(false);
    }

    @Test
    @DisplayName("일치하는 직원이 없으면 404 problem details 다")
    void 없으면_404_problem() {
        // given
        직원을_넣는다("u-1", "okta-00u1", "kim", true);

        // when, then
        client.get().uri("/admin/employees/lookup?userName=ki")
                .exchange().expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("일치하는 직원이 없다");
        client.get().uri("/admin/employees/lookup?externalId=okta-없음")
                .exchange().expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("일치하는 직원이 없다");
    }

    @Test
    @DisplayName("여럿이면 409 problem details 이고 직원 목록이 없다")
    void 여럿이면_409_problem_이고_목록이_없다() {
        // given — 다른 OU 에 같은 uid 가 있다
        직원을_넣는다("u-1", "uid=kim,ou=a,dc=x", "kim", true);
        직원을_넣는다("u-2", "uid=kim,ou=b,dc=x", "kim", true);

        // when, then
        client.get().uri("/admin/employees/lookup?userName=kim")
                .exchange().expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo("2명이 일치한다 — externalId 로 찾는다")
                .jsonPath("$.employeeId").doesNotExist()
                .consumeWith(응답 -> assertThat(new String(응답.getResponseBody()))
                        .doesNotContain("employeeId", "u-1", "u-2", "uid=kim"));
    }

    @Test
    @DisplayName("userName·externalId 를 둘 다 주거나 둘 다 없거나 비어 있으면 400 이다")
    void 둘_다_주거나_둘_다_없으면_400() {
        // when, then
        for (String 경로 : new String[]{
                "/admin/employees/lookup?userName=kim&externalId=okta-00u1",
                "/admin/employees/lookup",
                "/admin/employees/lookup?userName="}) {
            client.get().uri(경로)
                    .exchange().expectStatus().isBadRequest()
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.detail").isEqualTo("userName·externalId 중 정확히 하나를 지정해야 한다");
        }
        // 공백뿐인 값도 없는 것으로 센다 — 템플릿 변수라 스프링이 공백을 인코딩한다
        client.get().uri("/admin/employees/lookup?userName={값}", "  ")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("조직 조회가 하나이면 200 이고 orgCode·displayName·externalId 를 준다")
    void 조직_조회_200() {
        // given
        조직을_넣는다("g-1", "okta-00g1", "개발");
        조직을_넣는다("g-2", "okta-00g2", "영업");

        // when, then
        client.get().uri("/admin/organizations/lookup?externalId=okta-00g1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orgCode").isEqualTo("g-1")
                .jsonPath("$.displayName").isEqualTo("개발")
                .jsonPath("$.externalId").isEqualTo("okta-00g1");
    }

    @Test
    @DisplayName("조직 조회가 없으면 404 problem details 다")
    void 조직_조회_404() {
        // given
        조직을_넣는다("g-1", "okta-00g1", "개발");

        // when, then
        client.get().uri("/admin/organizations/lookup?externalId=okta-없음")
                .exchange().expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.detail").isEqualTo("일치하는 조직이 없다");
    }

    @Test
    @DisplayName("조직 조회가 여럿이면 409 problem details 이고 조직 목록이 없다")
    void 조직_조회_409() {
        // given — LDAP 에서만 난다
        조직을_넣는다("g-1", "dn-같음", "개발");
        조직을_넣는다("g-2", "dn-같음", "영업");

        // when, then
        client.get().uri("/admin/organizations/lookup?externalId=dn-같음")
                .exchange().expectStatus().isEqualTo(409)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.detail").isEqualTo("2개가 일치한다")
                .jsonPath("$.orgCode").doesNotExist()
                .consumeWith(응답 -> assertThat(new String(응답.getResponseBody()))
                        .doesNotContain("orgCode", "g-1", "g-2", "개발", "영업"));
    }

    @Test
    @DisplayName("조직 조회에 externalId 가 없거나 비어 있으면 400 이다")
    void 조직_조회_externalId_가_없으면_400() {
        // when, then
        for (String 경로 : new String[]{
                "/admin/organizations/lookup",
                "/admin/organizations/lookup?externalId="}) {
            client.get().uri(경로)
                    .exchange().expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.detail").isEqualTo("externalId 를 지정해야 한다");
        }
        client.get().uri("/admin/organizations/lookup?externalId={값}", " ")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("lookup 경로가 직원 상세·조직 상세보다 먼저 잡힌다")
    void lookup_경로가_직원_상세보다_먼저다() {
        // given
        직원을_넣는다("u-1", "okta-00u1", "kim", true);
        조직을_넣는다("g-1", "okta-00g1", "개발");

        // when, then — 상세로 갔다면 employeeId "lookup" 이라는 직원이 없어 404 이고, 응답에 소속 경로(paths)가 있었을 것이다
        client.get().uri("/admin/employees/lookup?userName=kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.employeeId").isEqualTo("u-1")
                .jsonPath("$.paths").doesNotExist();
        client.get().uri("/admin/organizations/lookup?externalId=okta-00g1")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orgCode").isEqualTo("g-1")
                .jsonPath("$.ancestors").doesNotExist();
        // 상세는 그대로 잡힌다
        client.get().uri("/admin/employees/u-1")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.paths").exists();
    }
}
