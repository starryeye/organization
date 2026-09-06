package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하네스가 <b>실제로 무엇을 잡는지</b> 확인한다.
 *
 * <p>올바른 상태에서 통과하는 것만 보면 아무것도 검증하지 않는 하네스도 똑같이 통과한다.
 * 그래서 매 항목마다 <b>일부러 깨뜨리고</b> 그 항목이 잡히는지를 본다 — 이 테스트의 본체는
 * 통과 케이스가 아니라 실패 케이스들이다.
 */
class SyncVerifierTest {

    private final OrgChart chart = OrgChartFixture.오천명();
    private FakeStateRepository state;
    private FakeTupleChecker checker;
    private SyncVerifier verifier;

    @BeforeEach
    void 완전히_맞는_상태를_만든다() {
        state = new FakeStateRepository();
        state.users.putAll(chart.snapshot().users());
        state.groups.putAll(chart.snapshot().groups());

        checker = new FakeTupleChecker();
        checker.allowed.addAll(TupleMapper.toTuples(chart.snapshot()).tuples());
        checker.allowed.addAll(롤업까지_펼친다());

        verifier = new SyncVerifier(state, checker);
    }

    /** OpenFGA 가 member 를 해석해 주는 것을 흉내 낸다 — 직속 + 모든 조상. */
    private Set<RelationTuple> 롤업까지_펼친다() {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        chart.snapshot().users().keySet().forEach(userId ->
                chart.기대소속(userId).forEach(org ->
                        tuples.add(RelationTuple.member(userId, org))));
        return tuples;
    }

    @Test
    @DisplayName("완전히 맞는 상태는 통과한다")
    void 맞으면_통과한다() {
        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result).isNotNull();
        assertThat(result.어긋났는가()).as(result == null ? "" : result.요약()).isFalse();
    }

    @Test
    @DisplayName("① 상태에서 직원이 사라지면 잡는다")
    void 상태에서_직원이_사라지면_잡는다() {
        // given
        String 사라질직원 = chart.landmarks().L6직속직원();
        state.users.remove(사라질직원);

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("① 직원이 상태에 없다: " + 사라질직원));
    }

    @Test
    @DisplayName("① 상태에 지워졌어야 할 직원이 남아 있으면 잡는다")
    void 상태에_잔여직원이_있으면_잡는다() {
        // given
        state.users.put("ghost", new DirectoryUser("ghost", null, "ghost", "유령", null, true));

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.contains("남아 있으면 안 되는 직원: ghost"));
    }

    @Test
    @DisplayName("① 조직 멤버 목록이 다르면 잡는다")
    void 멤버목록이_다르면_잡는다() {
        // given — 한 명만 슬쩍 뺀다
        String 조직 = chart.landmarks().대상팀();
        DirectoryGroup 원본 = chart.snapshot().groups().get(조직);
        Set<MemberRef> 줄인것 = new LinkedHashSet<>(원본.members());
        줄인것.remove(원본.members().iterator().next());
        state.groups.put(조직, new DirectoryGroup(조직, null, 원본.displayName(), 줄인것));

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.contains("① 조직 " + 조직 + " 의 members 가 다르다"));
    }

    @Test
    @DisplayName("① 직원 속성만 달라도 잡는다 — active 가 뒤집힌 경우")
    void active가_뒤집히면_잡는다() {
        // given
        String 직원 = chart.landmarks().L5직속직원();
        DirectoryUser 원본 = chart.snapshot().users().get(직원);
        state.users.put(직원, new DirectoryUser(원본.id(), null, 원본.userName(),
                원본.displayName(), 원본.email(), false));

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.contains("직원 " + 직원 + " 의 active 가 다르다"));
    }

    @Test
    @DisplayName("② 있어야 할 튜플이 OpenFGA 에 없으면 잡는다")
    void 튜플이_빠지면_잡는다() {
        // given
        RelationTuple 지울것 = RelationTuple.directMember(
                chart.landmarks().L6직속직원(), chart.직속조직(chart.landmarks().L6직속직원()));
        assertThat(checker.allowed.remove(지울것)).as("전제: 이 튜플이 원래 있어야 한다").isTrue();

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("② 있어야 할 튜플이 없다") && message.contains(지울것.object()));
    }

    @Test
    @DisplayName("③ 비활성 직원의 튜플이 OpenFGA 에 남아 있으면 잡는다 — 이 검증의 존재 이유")
    void 비활성직원의_잔여튜플을_잡는다() {
        // given — 직원이 비활성이 됐는데 조직 멤버 목록에는 그대로 있는 모양.
        // 퇴사자 권한 생존이 정확히 이 형태다. 기대 튜플에서는 active 필터로 빠지지만
        // 음성 후보는 필터 전 멤버십이라 이 튜플을 여전히 물어본다.
        String 퇴사자 = chart.landmarks().L5직속직원();
        OrgChart 비활성된조직도 = 비활성으로_바꾼다(퇴사자);
        state.users.put(퇴사자, 비활성된조직도.snapshot().users().get(퇴사자));

        RelationTuple 잔여 = RelationTuple.directMember(퇴사자, chart.직속조직(퇴사자));
        assertThat(checker.allowed).as("전제: 활성이던 시절의 튜플이 남아 있다").contains(잔여);

        // when
        var result = verifier.검증한다(비활성된조직도).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("③ 남아 있으면 안 되는 튜플") && message.contains(퇴사자));
    }

    @Test
    @DisplayName("③ 은 멤버십이 아예 사라진 튜플까지는 못 잡는다 — 알려진 한계를 못박는다")
    void 멤버십이_사라진_튜플은_못_잡는다() {
        // given — 조직 멤버 목록에도 없고 조직도에도 없는, 완전히 떠 있는 튜플.
        // 음성 후보가 멤버십에서 나오므로 하네스는 이것을 아예 물어보지 않는다(설계 §5.4).
        String 직원 = chart.landmarks().L3직속직원();
        String 엉뚱한조직 = chart.landmarks().대상파트();
        assertThat(chart.기대소속(직원)).doesNotContain(엉뚱한조직);
        checker.allowed.add(RelationTuple.directMember(직원, 엉뚱한조직));

        // when
        var result = verifier.검증한다(chart).block();

        // then — 통과한다. 이것을 잡으려면 열거가 필요한데 금지돼 있다.
        // 이 테스트는 하네스를 지키는 것이 아니라 하네스의 사각지대를 문서로 못박는 것이다.
        assertThat(result.어긋났는가())
                .as("한계가 사라졌다면 그것대로 좋은 소식이다 — 이 테스트를 지우고 하네스 문서를 고쳐라")
                .isFalse();
    }

    /** {@code userId} 만 비활성으로 바꾼 조직도. 멤버 목록은 그대로 둔다. */
    private OrgChart 비활성으로_바꾼다(String userId) {
        var users = new java.util.LinkedHashMap<>(chart.snapshot().users());
        DirectoryUser 원본 = users.get(userId);
        users.put(userId, new DirectoryUser(원본.id(), 원본.externalId(), 원본.userName(),
                원본.displayName(), 원본.email(), false));
        return new OrgChart(
                new dev.starryeye.organization.core.model.DirectorySnapshot(
                        users, chart.snapshot().groups()),
                chart.landmarks());
    }

    @Test
    @DisplayName("④ 롤업이 위로 안 닿으면 잡는다 — 튜플은 다 맞는데도")
    void 롤업이_안_닿으면_잡는다() {
        // given — direct_member/child 튜플은 그대로 두고 member 해석만 끊는다.
        // ②③ 은 전부 통과하는데 실제 인가는 안 되는, 가장 알아채기 어려운 모양이다.
        String 직원 = chart.landmarks().L6직속직원();
        String 회사 = chart.landmarks().회사();
        assertThat(checker.allowed.remove(RelationTuple.member(직원, 회사)))
                .as("전제: 이 롤업이 원래 성립해야 한다").isTrue();

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("④ 롤업이 위로 안 닿는다") && message.contains(직원));
        assertThat(result.어긋남()).noneMatch(message -> message.startsWith("②"));
        assertThat(result.어긋남()).noneMatch(message -> message.startsWith("③"));
    }

    @Test
    @DisplayName("④ 권한이 아래로 새면 잡는다 — 멤버십은 위로만 흘러야 한다")
    void 아래로_새면_잡는다() {
        // given — 부문 직속 직원이 그 아래 본부의 member 로 성립해 버린 경우
        String 직원 = chart.landmarks().L2직속직원();
        String 아래조직 = chart.자손들(chart.직속조직(직원)).iterator().next();
        assertThat(chart.기대소속(직원)).doesNotContain(아래조직);
        checker.allowed.add(RelationTuple.member(직원, 아래조직));

        // when
        var result = verifier.검증한다(chart).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("④ 권한이 아래로 샌다") && message.contains(아래조직));
    }

    @Test
    @DisplayName("어긋남을 첫 건에서 멈추지 않고 전부 모은다")
    void 어긋남을_전부_모은다() {
        // given
        state.users.remove(chart.landmarks().L6직속직원());
        state.users.remove(chart.landmarks().L5직속직원());
        state.users.put("ghost", new DirectoryUser("ghost", null, "ghost", "유령", null, true));

        // when
        var result = verifier.검증한다(chart).block();

        // then — 한 건씩 고쳐가며 다시 돌리면 한 시나리오에 여러 번을 돌려야 한다
        assertThat(result.어긋남()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.요약()).contains("어긋남");
    }

    @Test
    @DisplayName("롤업 표본에는 깊이별 대표와 겸직이 언제나 들어간다")
    void 표본에_대표가_항상_들어간다() {
        // given
        var l = chart.landmarks();

        // when — 추가 표본을 0 으로 줄여도
        var 표본 = new RollupSampling(0).표본을_고른다(chart);

        // then — 가장 얕은 체인·가장 깊은 체인·다중 경로가 빠지면
        // 이 조직도를 이렇게 만든 이유 전부가 검증에서 사라진다
        assertThat(표본).contains(l.L2직속직원(), l.L3직속직원(), l.L4직속직원(),
                l.L5직속직원(), l.L6직속직원(), l.겸직직원());
    }
}
