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
        checker.allowed.addAll(롤업까지_펼친다(chart));

        verifier = new SyncVerifier(state, checker);
    }

    /** OpenFGA 가 member 를 해석해 주는 것을 흉내 낸다 — 활성 직원의 직속 + 모든 조상. */
    private Set<RelationTuple> 롤업까지_펼친다(OrgChart 조직도) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        조직도.snapshot().users().values().stream()
                .filter(DirectoryUser::active)
                .forEach(user -> 조직도.기대소속(user.id()).forEach(org ->
                        tuples.add(RelationTuple.member(user.id(), org))));
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
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();
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
    @DisplayName("③ 옮긴 직원의 옛 조직 튜플이 남아 있으면 잡는다 — 지운 멤버십을 기억하므로")
    void 옮긴_직원의_잔여튜플을_잡는다() {
        // given — 앱이 새 조직 튜플은 썼는데 옛 조직 튜플 삭제를 잊은 모양
        String 직원 = chart.landmarks().L5직속직원();
        String 옛조직 = chart.직속조직(직원);
        String 새조직 = chart.landmarks().대상팀();
        assertThat(새조직).as("전제: 다른 조직으로 옮겨야 한다").isNotEqualTo(옛조직);
        OrgChart 이동후 = OrgChartEditor.편집한다(chart).직원을_옮긴다(직원, 옛조직, 새조직).완성();

        state.groups.put(옛조직, 이동후.snapshot().groups().get(옛조직));
        state.groups.put(새조직, 이동후.snapshot().groups().get(새조직));
        checker.allowed.add(RelationTuple.directMember(직원, 새조직));
        assertThat(checker.allowed).as("전제: 옛 튜플이 남아 있다")
                .contains(RelationTuple.directMember(직원, 옛조직));

        // when
        var result = verifier.검증한다(이동후).block();

        // then — 옛 멤버십은 조직도에서 사라졌지만 기억에 남아 여전히 묻는다
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("③ 남아 있으면 안 되는 튜플")
                        && message.contains(직원) && message.contains(옛조직));
    }

    @Test
    @DisplayName("비활성 직원이 섞인 조직도도 올바른 앱이면 통과한다 — 운영 매핑에 속지 않는지 보는 자리")
    void 비활성이_섞여도_올바른_앱이면_통과한다() {
        // given — 앱은 운영의 TupleMapper 로 쓴다. 하네스는 그것과 따로 계산한다.
        // TupleMapper 가 비활성을 거르지 못하면 이 테스트가 깨져야 한다 — 하네스가 운영에게
        // 정답을 묻던 시절에는 같이 틀려서 통과했다 (스펙 §7 변이 #1).
        String 퇴사자 = chart.landmarks().L4직속직원();
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();
        state.users.put(퇴사자, 비활성된조직도.snapshot().users().get(퇴사자));
        checker.allowed.clear();
        checker.allowed.addAll(TupleMapper.toTuples(비활성된조직도.snapshot()).tuples());
        checker.allowed.addAll(롤업까지_펼친다(비활성된조직도));

        // when
        var result = verifier.검증한다(비활성된조직도).block();

        // then
        assertThat(result).isNotNull();
        assertThat(result.어긋났는가()).as(result == null ? "" : result.요약()).isFalse();
    }

    @Test
    @DisplayName("③ 은 조직도에 한 번도 없었던 튜플까지는 못 잡는다 — 알려진 한계를 못박는다")
    void 멤버십이_사라진_튜플은_못_잡는다() {
        // given — 조직도에 한 번도 없었던, 완전히 떠 있는 튜플. 지운 멤버십은 기억하지만
        // 한 번도 없던 것은 후보에 들어갈 길이 없어 하네스가 아예 물어보지 않는다(스펙 §11).
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
    @DisplayName("④ 비활성 직원은 소속이 그대로여도 member 가 아니어야 한다")
    void 비활성직원의_롤업은_끊겨야_한다() {
        // given — 비활성이 됐지만 멤버십은 그대로인 직원. 비활성의 정의가 그것이다.
        // 하네스가 active 를 안 보면 "소속이 있으니 member 여야 한다" 고 기대해
        // 올바른 구현을 결함으로 신고한다 — 실제로 그렇게 신고했다.
        String 퇴사자 = chart.landmarks().L4직속직원();
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();

        state.users.put(퇴사자, 비활성된조직도.snapshot().users().get(퇴사자));
        // 구현이 올바르게 동작한 상태를 만든다 — dm 도 member 도 지워졌다
        chart.기대소속(퇴사자).forEach(org ->
                checker.allowed.remove(RelationTuple.member(퇴사자, org)));
        chart.직속조직들(퇴사자).forEach(org ->
                checker.allowed.remove(RelationTuple.directMember(퇴사자, org)));

        // when
        var result = verifier.검증한다(비활성된조직도).block();

        // then — 올바른 상태이므로 통과해야 한다
        assertThat(result).isNotNull();
        assertThat(result.어긋났는가()).as(result == null ? "" : result.요약()).isFalse();
    }

    @Test
    @DisplayName("④ 비활성 직원의 롤업이 살아 있으면 잡는다")
    void 비활성인데_롤업이_남으면_잡는다() {
        // given — dm 은 지웠는데 member 해석이 남아 있는 모양
        String 퇴사자 = chart.landmarks().L4직속직원();
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();
        state.users.put(퇴사자, 비활성된조직도.snapshot().users().get(퇴사자));
        chart.직속조직들(퇴사자).forEach(org ->
                checker.allowed.remove(RelationTuple.directMember(퇴사자, org)));
        // member 는 일부러 남겨 둔다

        // when
        var result = verifier.검증한다(비활성된조직도).block();

        // then
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("④ 권한이 아래로 샌다") || message.contains(퇴사자));
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
