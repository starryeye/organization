package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.fake.FakeSearchRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.query.UserSummary;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminQueryControllerTest {

    private final FakeStateRepository state = new FakeStateRepository();
    private final FakeSearchRepository search = new FakeSearchRepository(state);
    private final FakeTupleChecker checker = new FakeTupleChecker();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        var useCase = new AdminQueryUseCase(state, search, checker);
        var metrics = new AdminQueryMetrics(registry);
        client = WebTestClient.bindToController(new AdminQueryController(useCase, metrics)).build();
    }

    @Test
    @DisplayName("표시명으로 직원을 검색한다")
    void 표시명으로_검색한다() {
        // given
        search.users.add(new UserSummary("gd.hong", "gd.hong", "홍길동", true));

        // when, then
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].employeeId").isEqualTo("gd.hong")
                .jsonPath("$.items[0].displayName").isEqualTo("홍길동")
                .jsonPath("$.nextCursor").doesNotExist();
    }

    @Test
    @DisplayName("검색 파라미터가 없으면 400 이다")
    void 검색_파라미터가_없으면_400이다() {
        // when, then — 빈 접두사는 전체 열거가 되므로 입구에서 막는다
        client.get().uri("/admin/employees")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("검색 파라미터를 둘 다 주면 400 이다")
    void 파라미터가_둘이면_400이다() {
        // when, then — 어느 인덱스를 탈지 모호하다
        client.get().uri("/admin/employees?userName=gd&displayName=홍")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("limit 이 범위를 벗어나면 400 이다")
    void limit_범위를_벗어나면_400이다() {
        // when, then
        client.get().uri("/admin/employees?displayName=홍&limit=0")
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/admin/employees?displayName=홍&limit=101")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("저장소의 IllegalArgumentException 은 400 으로 옮겨진다")
    void 저장소의_IllegalArgumentException_은_400이_된다() {
        // given — 이 페이크는 커서를 보지 않고 무조건 실패하므로, 이 테스트가 증명하는 것은
        // "손상된 커서가 예외가 된다" 가 아니라 "그 예외가 400 이 된다" 뿐이다.
        // 어떤 커서가 실제로 예외를 부르는지는 CursorTest 와 저장소 테스트가 못박는다.
        search.failWith = new IllegalArgumentException("커서를 해석할 수 없다");

        // when, then
        client.get().uri("/admin/employees?displayName=홍&cursor=!!broken!!")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("없는 직원은 404 다")
    void 없는_직원은_404다() {
        // when, then
        client.get().uri("/admin/employees/nobody")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("직원 상세는 경로와 Check 결과를 함께 준다")
    void 직원_상세는_경로와_Check를_준다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when, then
        client.get().uri("/admin/employees/kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].orgCode").isEqualTo("DEV002")
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @DisplayName("Check 가 실패해도 200 이고 그 칸만 null 이다")
    void Check_실패해도_200이다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failFor(tuple -> true);

        // when, then — 조회 API 가 인가 서버 장애에 끌려 내려가면 안 된다
        client.get().uri("/admin/employees/kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].openFgaCheck").isEmpty();
    }

    @Test
    @DisplayName("어긋남을 만나면 드리프트 카운터가 올라간다")
    void 드리프트_카운터가_올라간다() {
        // given — 상태는 소속을 말하는데 OpenFGA 에는 튜플이 없다
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — follow-ups §6 에서 감지 장치를 안 두기로 했으므로 이게 유일한 신호다
        assertThat(registry.counter("authz_drift_detected").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("순환으로 설명되는 어긋남은 드리프트가 아니라 순환 카운터로 센다")
    void 순환은_드리프트로_안_센다() {
        // given — DEV001 ⊇ DEV002 이고 DEV002 ⊇ DEV001 (순환), OpenFGA 에는 튜플이 없다.
        // 두 줄 모두 어긋나지만 그중 DEV002 줄에만 cycle 표시가 붙는다.
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.group("DEV001")))).block();
        state.saveGroup(new DirectoryGroup("DEV001", "y", "플랫폼개발본부",
                Set.of(MemberRef.group("DEV002")))).block();

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — 순환 줄은 재적재해도 같은 간선이 또 버려져 영영 안 사라진다. 그걸 재적재 신호에
        // 섞으면 카운터가 0 으로 돌아오지 않아 알람이 소음이 된다. 숨기지는 않고 이름만 나눈다.
        assertThat(registry.counter("authz_cycle_divergence").count()).isEqualTo(1.0);
        assertThat(registry.counter("authz_drift_detected").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Check 호출 수를 세어 실패율의 분모를 만든다")
    void Check_호출_수를_센다() {
        // given — 경로 두 줄 중 하나만 Check 가 실패한다
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("DEV001", "y", "플랫폼개발본부",
                Set.of(MemberRef.group("DEV002")))).block();
        checker.failFor(tuple -> tuple.object().equals("group:DEV001"));

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — 설계 §10 이 요구하는 실패율은 분모가 있어야 계산된다
        assertThat(registry.counter("authz_checks_total").count()).isEqualTo(2.0);
        assertThat(registry.counter("authz_check_failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Check 를 못 한 것은 드리프트가 아니라 보류로 센다")
    void 보류는_드리프트로_안_센다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failFor(tuple -> true);

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — 모른다는 것과 어긋났다는 것은 다르다
        assertThat(registry.counter("authz_drift_detected").count()).isZero();
        assertThat(registry.counter("authz_check_failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("없는 조직은 404 다")
    void 없는_조직은_404다() {
        // when, then
        client.get().uri("/admin/organizations/NOPE")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("조직 상세는 상위 계층과 직속 하위를 준다")
    void 조직_상세를_준다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀", Set.of())).block();
        state.saveGroup(new DirectoryGroup("DEV001", "y", "플랫폼개발본부",
                Set.of(MemberRef.group("DEV002")))).block();

        // when, then
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orgCode").isEqualTo("DEV002")
                .jsonPath("$.ancestors[0].orgCode").isEqualTo("DEV001");
    }

    @Test
    @DisplayName("조직 멤버 목록을 조회한다")
    void 조직_멤버_목록을_조회한다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when, then
        client.get().uri("/admin/organizations/DEV002/members")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].employeeId").isEqualTo("kim")
                .jsonPath("$.items[0].displayName").isEqualTo("김철수")
                .jsonPath("$.items[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @DisplayName("없는 조직의 멤버 목록은 404 다")
    void 없는_조직의_멤버_목록은_404다() {
        // when, then
        client.get().uri("/admin/organizations/NOPE/members")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("멤버 목록에서 어긋남을 만나면 드리프트 카운터가 올라간다")
    void 멤버_목록_드리프트_카운터가_올라간다() {
        // given — 상태는 소속을 말하는데 OpenFGA 에는 튜플이 없다
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when
        client.get().uri("/admin/organizations/DEV002/members").exchange().expectStatus().isOk();

        // then — employeeDetail 경로와 같은 신호가 멤버 목록 경로에서도 나와야 한다
        assertThat(registry.counter("authz_drift_detected").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("멤버 목록에서 Check 를 못 한 것은 드리프트가 아니라 보류로 센다")
    void 멤버_목록_보류는_드리프트로_안_센다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failFor(tuple -> true);

        // when
        client.get().uri("/admin/organizations/DEV002/members").exchange().expectStatus().isOk();

        // then — 모른다는 것과 어긋났다는 것은 다르다
        assertThat(registry.counter("authz_drift_detected").count()).isZero();
        assertThat(registry.counter("authz_check_failed").count()).isEqualTo(1.0);
    }
}
