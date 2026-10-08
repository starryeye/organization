package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSearchRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.query.AccessPath;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminQueryUseCaseTest {

    private FakeStateRepository state;
    private FakeSearchRepository search;
    private FakeTupleChecker checker;
    private AdminQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        search = new FakeSearchRepository(state);
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
    @DisplayName("두 하위 조직이 같은 상위를 공유해도 순환으로 오판하지 않는다")
    void 다이아몬드는_순환이_아니다() {
        // given — kim 은 DEV002 와 DEV003 양쪽의 직속 멤버이고, 둘 다 DEV001 의 하위다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV003", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"), MemberRef.group("DEV003"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — DEV001 은 두 갈래에서 모두 닿지만 한 번만 나오고 순환 표시가 없다
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV003", "DEV001");
        assertThat(detail.paths()).noneMatch(AccessPath::cycle);
    }

    @Test
    @DisplayName("직원이 조직과 그 상위 조직 양쪽에 직속으로 속해도 순환으로 오판하지 않는다")
    void 직속_조직과_그_상위에_동시에_속해도_순환이_아니다() {
        // given — kim 은 DEV002 와 그 상위인 DEV001 양쪽의 직속 멤버다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.user("kim"), MemberRef.group("DEV002"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — DEV001 은 직속으로 이미 잡혔고, DEV002 를 통해 다시 닿아도 순환이 아니다
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV001");
        assertThat(detail.paths()).noneMatch(AccessPath::cycle);
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
        // orgCode/displayName/externalId 가 뒤바뀌면(둘 다 연속된 String 필드다) 여기서 잡힌다
        assertThat(detail.orgCode()).isEqualTo("DEV002");
        assertThat(detail.displayName()).isEqualTo("DEV002-조직");
        assertThat(detail.externalId()).isEqualTo("DEV002");
        assertThat(detail.ancestors()).extracting("orgCode").containsExactly("DEV001", "ROOT");
        assertThat(detail.childOrganizations()).extracting("orgCode").containsExactly("DEV003");
        // 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다
        assertThat(detail.childOrganizations()).extracting("displayName").containsExactly("DEV003-조직");
        // 하위의 하위(lee)는 담기지 않는다
        assertThat(detail.members().items()).extracting("employeeId").containsExactly("kim");
        assertThat(detail.members().items()).extracting("openFgaCheck").containsExactly(true);
    }

    @Test
    @DisplayName("계층 순회는 이름표만 읽고 다른 조직의 멤버 목록은 읽지 않는다")
    void 순회는_멤버_목록을_읽지_않는다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ {kim, DEV003}
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV003")).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.group("DEV003"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        state.findGroupCalls.clear();

        // when
        var detail = useCase.organizationDetail("DEV002", 20).block();

        // then — 이름 칸을 채우려고 파티션 전체(META + 멤버십 전부)를 읽던 자리다.
        // 조회 대상 조직 자신도 파티션 전체를 읽지 않는다 — 헤더·하위 조직·멤버 한 쪽만(설계 2026-10-06 §3.2)
        assertThat(detail.childOrganizations()).extracting("displayName").containsExactly("DEV003-조직");
        assertThat(detail.ancestors()).extracting("orgCode").containsExactly("DEV001", "ROOT");
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(search.findGroupSummaryCalls).containsExactlyInAnyOrder("DEV003", "DEV001", "ROOT");
    }

    @Test
    @DisplayName("직원 상세의 계층 순회도 조직 멤버 목록을 읽지 않는다")
    void 직원_상세_순회도_멤버_목록을_읽지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.findGroupCalls.clear();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 두 엔드포인트 모두 인증이 없어 증폭을 익명 호출자가 조종할 수 있었다
        assertThat(detail.paths()).hasSize(2);
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(search.findGroupSummaryCalls).containsExactlyInAnyOrder("DEV002", "DEV001");
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
    @DisplayName("직속 소속만으로 상한을 넘으면 정확히 상한만큼만 남기고 truncated 를 세운다")
    void 상한을_넘으면_자른다() {
        // given — kim 이 직속으로 속한 조직을 상한보다 많이 만든다
        state.saveUser(직원("kim", true)).block();
        for (int i = 0; i < AdminQueryUseCase.MAX_PATHS + 10; i++) {
            state.saveGroup(조직("G" + i, MemberRef.user("kim"))).block();
        }

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 상한 없이 훑는 대신 그 사실을 드러낸다. 0개짜리 구현도 통과하면 안 되므로 정확히 센다
        assertThat(detail.paths()).hasSize(AdminQueryUseCase.MAX_PATHS);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("상위 계층 사슬만으로 상한을 넘어도 정확히 상한만큼만 남기고 truncated 를 세운다")
    void 상위_사슬이_상한을_넘으면_자른다() {
        // given — kim 은 G0 하나에만 직속이고, G0 위로 상한보다 많은 조상이 한 줄로 이어진다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("G0", MemberRef.user("kim"))).block();
        for (int i = 1; i <= AdminQueryUseCase.MAX_PATHS + 10; i++) {
            state.saveGroup(조직("G" + i, MemberRef.group("G" + (i - 1)))).block();
        }

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 직속 단계가 아니라 상위 확장 도중에 상한에 걸리는 경우도 같게 잘린다
        assertThat(detail.paths()).hasSize(AdminQueryUseCase.MAX_PATHS);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("상한을 넘긴 직원의 참조가 일부 끊어져 있어도 truncated 를 세운다")
    void 끊어진_참조가_섞여도_truncated를_세운다() {
        // given — 소속은 상한보다 많고, 그중 일부는 조직 레코드가 사라진 상태다
        state.saveUser(직원("kim", true)).block();
        for (int i = 0; i < AdminQueryUseCase.MAX_PATHS + 10; i++) {
            state.saveGroup(조직("G" + i, MemberRef.user("kim"))).block();
        }
        for (int i = 0; i < 30; i++) {
            search.missingGroups.add("G" + i);
        }

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 자른 사실은 자를 때 알 수 있는 것이지, 적재된 개수를 세어 알 수 있는 것이
        // 아니다. 적재 결과로 세면 여기서 조용히 false 가 되어 "전부 보여줬다" 는 거짓말이 된다.
        assertThat(detail.paths()).hasSizeLessThan(AdminQueryUseCase.MAX_PATHS);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("한 단계의 상위 조직이 아무리 많아도 상한만큼만 읽는다")
    void 상위_확장은_한_단계에서도_상한을_지킨다() {
        // given — G0 하나에 상위 조직이 상한의 다섯 배 달려 있다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("G0", MemberRef.user("kim"))).block();
        int 상위_개수 = AdminQueryUseCase.MAX_PATHS * 5;
        for (int i = 1; i <= 상위_개수; i++) {
            state.saveGroup(조직("P" + i, MemberRef.group("G0"))).block();
        }
        search.findGroupSummaryCalls.clear();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — truncated 검사는 '다음' 단계만 막는다. 자르지 않으면 이 한 단계 안에서
        // 상위 조직 전부를 읽고 나서야 잘린다.
        assertThat(detail.truncated()).isTrue();
        assertThat(detail.paths()).hasSize(AdminQueryUseCase.MAX_PATHS);
        assertThat(search.findGroupSummaryCalls).hasSizeLessThanOrEqualTo(AdminQueryUseCase.MAX_PATHS + 2);
    }

    @Test
    @DisplayName("직속 하위 조직이 상한을 넘으면 상한만큼만 읽고 자른다")
    void 직속_하위_조직도_상한을_지킨다() {
        // given — 하위 조직이 상한보다 많은 조직
        MemberRef[] children = new MemberRef[AdminQueryUseCase.MAX_PATHS + 10];
        for (int i = 0; i < children.length; i++) {
            state.saveGroup(조직("C" + i)).block();
            children[i] = MemberRef.group("C" + i);
        }
        state.saveGroup(조직("BIG", children)).block();
        search.findGroupSummaryCalls.clear();

        // when
        var detail = useCase.organizationDetail("BIG", 20).block();

        // then — 인증이 없는 엔드포인트라 이 팬아웃을 익명 호출자가 조종할 수 있다
        assertThat(detail.childOrganizations()).hasSize(AdminQueryUseCase.MAX_PATHS);
        assertThat(search.findGroupSummaryCalls).hasSize(AdminQueryUseCase.MAX_PATHS);
    }

    @Test
    @DisplayName("조직 상세는 하위 조직 이름표를 동시에 8개까지 읽고, 먼저 끝난 읽기가 있어도 순서는 그대로다(점검 S27)")
    void 하위_조직_이름표를_병렬로_읽는다() {
        // given — 하위 조직 20개. 앞쪽일수록 오래 걸려(C01 200ms … C20 10ms) 완료 순서가 소스 순서와 거꾸로다
        MemberRef[] 하위 = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> MemberRef.group("C%02d".formatted(i))).toArray(MemberRef[]::new);
        for (int i = 1; i <= 20; i++) {
            state.saveGroup(조직("C%02d".formatted(i))).block();
            search.summaryDelayById.put("C%02d".formatted(i), Duration.ofMillis(10L * (21 - i)));
        }
        state.saveGroup(조직("P", 하위)).block();
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        try {
            AtomicReference<OrganizationDetail> 결과 = new AtomicReference<>();

            // when — 맨 앞 C01 이 풀려야 다음 읽기가 나가므로 세 번에 360ms 다. 하나씩이면 2.1초다
            useCase.organizationDetail("P", 20).subscribe(결과::set);
            시간.advanceTimeBy(Duration.ofMillis(400));

            // then — 완료 순서가 아니라 소스 순서다. 앞쪽이 더 느려 flatMap 이면 순서가 깨진다
            assertThat(결과.get()).as("400ms 안에 끝난다").isNotNull();
            assertThat(결과.get().childOrganizations()).extracting("orgCode")
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).mapToObj("C%02d"::formatted).toList());
            assertThat(search.summaryInFlightMax).hasValue(8);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("조직 상세는 상위 조직 이름표도 동시에 읽고, 먼저 끝난 읽기가 있어도 순서는 그대로다(점검 S27)")
    void 상위_조직_이름표를_병렬로_읽는다() {
        // given — X 를 담은 상위 조직 10개. 앞쪽일수록 오래 걸려(P01 100ms … P10 10ms) 완료 순서가 소스 순서와 거꾸로다
        state.saveGroup(조직("X")).block();
        for (int i = 1; i <= 10; i++) {
            state.saveGroup(조직("P%02d".formatted(i), MemberRef.group("X"))).block();
            search.summaryDelayById.put("P%02d".formatted(i), Duration.ofMillis(10L * (11 - i)));
        }
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        try {
            AtomicReference<OrganizationDetail> 결과 = new AtomicReference<>();

            // when — 두 번에 120ms 다. 하나씩이면 550ms 다
            useCase.organizationDetail("X", 20).subscribe(결과::set);
            시간.advanceTimeBy(Duration.ofMillis(200));

            // then — 상태 저장소가 돌려주는 순서(P01..P10)가 그대로다. 앞쪽이 더 느려 flatMap 이면 순서가 깨진다
            assertThat(결과.get()).as("200ms 안에 끝난다").isNotNull();
            assertThat(결과.get().ancestors()).extracting("orgCode")
                    .containsExactly("P01", "P02", "P03", "P04", "P05", "P06", "P07", "P08", "P09", "P10");
            assertThat(search.summaryInFlightMax).hasValue(8);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("조직 멤버 목록은 커서로 다음 페이지를 이어간다")
    void 조직_멤버는_커서로_다음_페이지를_잇는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveUser(직원("park", true)).block();
        state.saveGroup(조직("DEV002",
                MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.user("park"))).block();

        // when
        var firstPage = useCase.organizationMembers("DEV002", null, 2).block();
        var secondPage = useCase.organizationMembers("DEV002", firstPage.nextCursor(), 2).block();

        // then
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    @DisplayName("멤버 목록의 Check 는 병렬로 나가되 페이지 순서는 지켜진다")
    void 멤버_Check는_병렬이고_순서는_지켜진다() {
        // given — 응답을 늦춰야 직렬/병렬이 구분된다. 늦게 답할수록 앞선 직원이 되게 해서
        // 완료 순서와 요청 순서를 반대로 만든다.
        List<MemberRef> members = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            state.saveUser(직원("u" + i, true)).block();
            members.add(MemberRef.user("u" + i));
        }
        state.saveGroup(조직("DEV002", members.toArray(new MemberRef[0]))).block();
        checker.delayBy(tuple -> {
            int index = Integer.parseInt(tuple.user().substring("user:u".length()));
            return Duration.ofMillis(30L * (8 - index));
        });

        // when
        var page = useCase.organizationMembers("DEV002", null, 8).block();

        // then — 설계 §8.2 는 동시성 제한을 걸어 병렬로 내라고 한다. 직렬이면 여기가 1 이다.
        assertThat(checker.maxInFlight.get()).isGreaterThan(1);
        // 병렬로 내되 페이지 순서는 완료 순서가 아니라 소스 순서를 따라야 한다
        assertThat(page.items()).extracting("employeeId")
                .containsExactly("u0", "u1", "u2", "u3", "u4", "u5", "u6", "u7");
    }

    @Test
    @DisplayName("직원 상세의 경로는 완료 순서가 아니라 직속-상위 순서로 나온다")
    void 경로는_직속_상위_순서다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ kim. 위로 갈수록 Check 가 빨리 답하게 해서
        // 완료 순서를 순회 순서의 정확히 반대로 만든다.
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        checker.delayBy(tuple -> switch (tuple.object()) {
            case "group:DEV002" -> Duration.ofMillis(150);
            case "group:DEV001" -> Duration.ofMillis(80);
            default -> Duration.ofMillis(10);
        });

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 설계 §7.2 예시와 README 가 보여주는 순서다. 지키는 데 드는 비용이 없다
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactly("DEV002", "DEV001", "ROOT");
    }

    @Test
    @DisplayName("멤버 목록은 조직 파티션 전체를 읽지 않고 검색 포트의 한 쪽과 그 커서를 그대로 쓴다(설계 2026-10-06 §3.1)")
    void 멤버_목록은_한_쪽만_읽는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveUser(직원("park", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.user("park"))).block();
        state.findGroupCalls.clear();

        // when
        var 첫_쪽 = useCase.organizationMembers("DEV002", null, 2).block();
        var 둘째_쪽 = useCase.organizationMembers("DEV002", 첫_쪽.nextCursor(), 2).block();

        // then
        assertThat(첫_쪽.items()).extracting("employeeId").containsExactly("kim", "lee");
        assertThat(둘째_쪽.items()).extracting("employeeId").containsExactly("park");
        assertThat(둘째_쪽.nextCursor()).isNull();
        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("검색 포트가 커서를 거절하면(IllegalArgumentException) 그대로 흘려 관리 API 가 400 으로 바꾼다")
    void 거절된_커서는_그대로_흐른다() {
        // given
        state.saveGroup(조직("DEV002")).block();
        search.failWith = new IllegalArgumentException("이 조직의 멤버 목록 커서가 아니다");

        // when, then
        assertThatThrownBy(() -> useCase.organizationMembers("DEV002", "x", 20).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("존재하지 않는 조직의 멤버 조회는 빈 Mono 다")
    void 없는_조직의_멤버_조회는_빈_Mono다() {
        // when, then
        assertThat(useCase.organizationMembers("NOPE", null, 20).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 직원을 참조하는 멤버십은 건너뛴다")
    void 없는_직원_참조는_건너뛴다() {
        // given — DEV002 가 kim 을 멤버로 갖지만 kim 의 직원 레코드 자체는 없다
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var page = useCase.organizationMembers("DEV002", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("Check 에 쓰는 튜플은 RelationTuple 의 접두사 규칙을 따른다")
    void Check_튜플은_접두사_규칙을_따른다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        useCase.employeeDetail("kim").block();

        // then — 조회 경로가 "user:"/"group:" 를 직접 이어 붙이면 규칙이 두 곳에 살게 된다
        assertThat(checker.checked).containsExactly(RelationTuple.member("kim", "DEV002"));
        assertThat(RelationTuple.member("kim", "DEV002"))
                .isEqualTo(new RelationTuple("user:kim", "member", "group:DEV002"));
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

    @Test
    @DisplayName("externalId 로 직원을 정확히 찾는다 — IdP 의 사용자 id 로 우리 id 를 얻는 길")
    void externalId_로_직원을_찾는다() {
        // given
        state.users.put("u-1", new DirectoryUser("u-1", "okta-00u1", "kim", "김", null, true));
        state.users.put("u-2", new DirectoryUser("u-2", "okta-00u2", "lee", "이", null, true));

        // when
        var page = useCase.findEmployeesByExternalId("okta-00u1").block();

        // then
        assertThat(page.items()).extracting(UserSummary::employeeId).containsExactly("u-1");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("externalId 로 조직을 정확히 찾는다")
    void externalId_로_조직을_찾는다() {
        // given
        state.groups.put("g-1", new DirectoryGroup("g-1", "okta-00g1", "개발", Set.of()));
        state.groups.put("g-2", new DirectoryGroup("g-2", "okta-00g2", "영업", Set.of()));

        // when
        var page = useCase.findOrganizationsByExternalId("okta-00g1").block();

        // then
        assertThat(page.items()).extracting(GroupSummary::orgCode).containsExactly("g-1");
        assertThat(page.items()).extracting(GroupSummary::displayName).containsExactly("개발");
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("externalId 가 같은 직원이 없으면 빈 한 페이지를 준다")
    void externalId_가_없으면_빈_페이지다() {
        // given
        state.users.put("u-1", new DirectoryUser("u-1", "okta-00u1", "kim", "김", null, true));
        state.groups.put("g-1", new DirectoryGroup("g-1", "okta-00g1", "개발", Set.of()));

        // when
        var employees = useCase.findEmployeesByExternalId("okta-없음").block();
        var organizations = useCase.findOrganizationsByExternalId("okta-없음").block();

        // then
        assertThat(employees.items()).isEmpty();
        assertThat(employees.nextCursor()).isNull();
        assertThat(organizations.items()).isEmpty();
        assertThat(organizations.nextCursor()).isNull();
    }

    @Test
    @DisplayName("externalId 는 대소문자를 가린다 — 후보로 올라온 다른 대소문자의 직원도 본 테이블 재확인에서 걸러진다")
    void externalId_는_대소문자를_가린다() {
        // given — 후보 목록이 대소문자만 다른 u-1 을 돌려준다(GSI 가 대소문자를 가리지 않는다고 가정한 최악의 경우).
        // 가짜 저장소가 알아서 걸러 주면 이 테스트는 유스케이스가 아니라 가짜를 보게 된다
        var 후보가_넓은 = new FakeStateRepository() {
            @Override
            public Flux<String> findUserIdsByExternalId(String externalId) {
                return Flux.just("u-1");
            }
        };
        후보가_넓은.users.put("u-1", new DirectoryUser("u-1", "Okta-00U1", "kim", "김", null, true));
        var useCase = new AdminQueryUseCase(후보가_넓은, new FakeSearchRepository(후보가_넓은), checker);

        // when
        var page = useCase.findEmployeesByExternalId("okta-00u1").block();

        // then — 후보에는 올라왔지만 externalId 가 정확히 같지 않아 결과에서 빠진다
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("GSI3 가 돌려준 옛 후보(지워졌거나 externalId 를 바꾼 직원)는 본 테이블로 다시 확인해 걸러낸다")
    void 직원_조회는_GSI3_옛_후보를_걸러낸다() {
        // given — GSI3 가 "okta-00u1" 로 u-1(맞음), u-2(그 뒤 externalId 를 바꿈), ghost(그 뒤 지워짐)를 돌려준다
        var stale = new FakeStateRepository() {
            @Override
            public Flux<String> findUserIdsByExternalId(String externalId) {
                return Flux.just("u-1", "u-2", "ghost");
            }
        };
        stale.users.put("u-1", new DirectoryUser("u-1", "okta-00u1", "kim", "김", null, true));
        stale.users.put("u-2", new DirectoryUser("u-2", "okta-바뀜", "lee", "이", null, true));
        var useCase = new AdminQueryUseCase(stale, new FakeSearchRepository(stale), checker);

        // when
        var page = useCase.findEmployeesByExternalId("okta-00u1").block();

        // then
        assertThat(page.items()).extracting(UserSummary::employeeId).containsExactly("u-1");
    }

    @Test
    @DisplayName("GSI3 가 돌려준 옛 후보(지워졌거나 externalId 를 바꾼 조직)는 본 테이블로 다시 확인해 걸러낸다")
    void 조직_조회는_GSI3_옛_후보를_걸러낸다() {
        // given — GSI3 가 "okta-00g1" 로 g-1(맞음), g-2(그 뒤 externalId 를 바꿈), ghost(그 뒤 지워짐)를 돌려준다
        var stale = new FakeStateRepository() {
            @Override
            public Flux<String> findGroupIdsByExternalId(String externalId) {
                return Flux.just("g-1", "g-2", "ghost");
            }
        };
        stale.groups.put("g-1", new DirectoryGroup("g-1", "okta-00g1", "개발", Set.of()));
        stale.groups.put("g-2", new DirectoryGroup("g-2", "okta-바뀜", "영업", Set.of()));
        var useCase = new AdminQueryUseCase(stale, new FakeSearchRepository(stale), checker);

        // when
        var page = useCase.findOrganizationsByExternalId("okta-00g1").block();

        // then
        assertThat(page.items()).extracting(GroupSummary::orgCode).containsExactly("g-1");
    }
}
