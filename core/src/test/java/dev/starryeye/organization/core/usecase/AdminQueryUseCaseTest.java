package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSearchRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.query.AccessPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminQueryUseCaseTest {

    private FakeStateRepository state;
    private FakeSearchRepository search;
    private FakeTupleChecker checker;
    private AdminQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        search = new FakeSearchRepository();
        checker = new FakeTupleChecker();
        useCase = new AdminQueryUseCase(state, search, checker);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id + "-이름", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, code + "-조직", Set.of(members));
    }

    @Test
    @DisplayName("직속 소속과 그 상위 계층 전부가 경로로 나온다")
    void 직속과_상위계층이_경로가_된다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ kim
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV001", "ROOT");
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV002"))
                .extracting(AccessPath::via).containsExactly(AccessPath.DIRECT);
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("ROOT"))
                .extracting(AccessPath::via).containsExactly(AccessPath.ROLLUP);
        assertThat(detail.truncated()).isFalse();
    }

    @Test
    @DisplayName("비활성 직원은 모든 경로의 shouldHaveAccess 가 false 다")
    void 비활성_직원은_전부_false다() {
        // given — 소속은 그대로 있지만 비활성이다
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 경로는 보이되 권한은 없어야 한다. 이걸 빠뜨리면 퇴사자 화면이 전부 어긋남으로 보인다
        assertThat(detail.paths()).hasSize(2);
        assertThat(detail.paths()).allMatch(p -> !p.shouldHaveAccess());
        assertThat(detail.paths()).noneMatch(AccessPath::drifted);
    }

    @Test
    @DisplayName("OpenFGA 에 튜플이 없으면 파생값과 갈려 drifted 로 잡힌다")
    void 튜플이_없으면_드리프트다() {
        // given — 상태는 소속을 말하는데 OpenFGA 에는 아무 튜플도 없다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        var path = detail.paths().get(0);
        assertThat(path.shouldHaveAccess()).isTrue();
        assertThat(path.openFgaCheck()).isFalse();
        assertThat(path.drifted()).isTrue();
    }

    @Test
    @DisplayName("Check 가 실패하면 그 항목만 null 이 되고 조회는 성공한다")
    void Check_실패는_null로_흐른다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));
        checker.failFor(tuple -> tuple.object().equals("group:DEV001"));

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 요청 자체는 성공하고, 실패한 칸만 null 이다
        assertThat(detail.paths()).hasSize(2);
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV001"))
                .extracting(AccessPath::openFgaCheck).containsOnlyNulls();
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV002"))
                .extracting(AccessPath::openFgaCheck).containsExactly(true);
    }

    @Test
    @DisplayName("Check 를 못 한 항목은 drifted 로 세지 않는다")
    void Check를_못하면_드리프트로_안_센다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        checker.failFor(tuple -> true);

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 모른다는 것과 어긋났다는 것은 다르다
        assertThat(detail.paths().get(0).openFgaCheck()).isNull();
        assertThat(detail.paths().get(0).drifted()).isFalse();
    }

    @Test
    @DisplayName("상위 계층에 순환이 있으면 표시하고 순회를 멈춘다")
    void 순환은_표시하고_멈춘다() {
        // given — DEV001 ⊇ DEV002 이고 DEV002 ⊇ DEV001 (순환)
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.group("DEV001"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 무한 루프에 빠지지 않고, 다시 닿은 지점을 드러낸다
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV001");
        assertThat(detail.paths()).anyMatch(AccessPath::cycle);
    }

    @Test
    @DisplayName("어느 조직에도 속하지 않은 직원은 빈 경로를 돌려준다")
    void 소속이_없으면_빈_경로다() {
        // given
        state.saveUser(직원("kim", true)).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 404 가 아니다. 직원은 존재한다
        assertThat(detail.employeeId()).isEqualTo("kim");
        assertThat(detail.paths()).isEmpty();
    }

    @Test
    @DisplayName("없는 직원은 빈 Mono 다")
    void 없는_직원은_빈_Mono다() {
        // when, then
        assertThat(useCase.employeeDetail("nobody").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 조직을 참조하는 소속은 건너뛴다")
    void 없는_조직_참조는_건너뛴다() {
        // given — DEV002 가 kim 을 갖지만 DEV002 레코드 자체는 없다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.groups.remove("DEV002");

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        assertThat(detail.paths()).isEmpty();
    }

    @Test
    @DisplayName("조직 상세는 상위 전체와 직속 하위 1 depth 만 담는다")
    void 조직_상세는_상위_전체와_하위_한칸이다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ {kim, DEV003}, DEV003 ⊇ lee
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV003", MemberRef.user("lee"))).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.group("DEV003"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when
        var detail = useCase.organizationDetail("DEV002", 20).block();

        // then
        assertThat(detail.ancestors()).extracting("orgCode").containsExactly("DEV001", "ROOT");
        assertThat(detail.childOrganizations()).extracting("orgCode").containsExactly("DEV003");
        // 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다
        assertThat(detail.childOrganizations()).extracting("displayName").containsExactly("DEV003-조직");
        // 하위의 하위(lee)는 담기지 않는다
        assertThat(detail.members().items()).extracting("employeeId").containsExactly("kim");
        assertThat(detail.members().items()).extracting("openFgaCheck").containsExactly(true);
    }

    @Test
    @DisplayName("최상위 조직은 상위 계층이 비어 있다")
    void 최상위_조직은_상위가_없다() {
        // given
        state.saveGroup(조직("ROOT")).block();

        // when
        var detail = useCase.organizationDetail("ROOT", 20).block();

        // then
        assertThat(detail.ancestors()).isEmpty();
        assertThat(detail.childOrganizations()).isEmpty();
        assertThat(detail.members().items()).isEmpty();
    }

    @Test
    @DisplayName("없는 조직은 빈 Mono 다")
    void 없는_조직은_빈_Mono다() {
        // when, then
        assertThat(useCase.organizationDetail("NOPE", 20).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("경로가 상한을 넘으면 잘라내고 truncated 를 세운다")
    void 상한을_넘으면_자른다() {
        // given — kim 이 직속으로 속한 조직을 상한보다 많이 만든다
        state.saveUser(직원("kim", true)).block();
        for (int i = 0; i < AdminQueryUseCase.MAX_PATHS + 10; i++) {
            state.saveGroup(조직("G" + i, MemberRef.user("kim"))).block();
        }

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 상한 없이 훑는 대신 그 사실을 드러낸다
        assertThat(detail.paths()).hasSizeLessThanOrEqualTo(AdminQueryUseCase.MAX_PATHS);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("검색은 저장소에 그대로 위임한다")
    void 검색은_그대로_위임한다() {
        // given
        search.users.add(new dev.starryeye.organization.core.query.UserSummary(
                "gd.hong", "gd.hong", "홍길동", true));

        // when
        var byName = useCase.searchEmployeesByDisplayName("홍", null, 20).block();
        var byAccount = useCase.searchEmployeesByUserName("gd", null, 20).block();

        // then
        assertThat(byName.items()).extracting("employeeId").containsExactly("gd.hong");
        assertThat(byAccount.items()).extracting("employeeId").containsExactly("gd.hong");
    }
}
