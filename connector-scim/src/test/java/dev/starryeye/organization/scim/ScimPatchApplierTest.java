package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimPatchApplierTest {

    /** 대부분의 케이스는 type 이 명시돼 있어 resolver 를 타지 않는다. 타면 User 로 답한다. */
    private static final MemberTypeResolver USER_ONLY = id -> Mono.just(MemberType.USER);

    private static DirectoryGroup 조직(MemberRef... members) {
        return new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(members));
    }

    private static DirectoryUser 직원(boolean active) {
        return new DirectoryUser("kim", "emp-1001", "kim", "김철수", "kim@example.com", active);
    }

    private static ScimPatchOp 패치(String op, String path, Object value) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP),
                List.of(new ScimOperation(op, path, value)));
    }

    private static Map<String, Object> 멤버(String value, String type) {
        return Map.of("value", value, "type", type);
    }

    @Test
    @DisplayName("add members 는 기존 멤버를 유지한 채 새 멤버를 더한다")
    void 멤버를_추가한다() {
        // given
        var before = 조직(MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("add", "members", List.of(멤버("kim", "User"))), USER_ONLY).block();

        // then
        assertThat(after.members())
                .containsExactlyInAnyOrder(MemberRef.user("lee"), MemberRef.user("kim"));
        assertThat(after.id()).isEqualTo("DEV002");
        assertThat(after.displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("add members 의 type 이 Group 이면 하위 조직 멤버로 추가된다")
    void 하위조직을_추가한다() {
        // given
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("add", "members", List.of(멤버("DEV003", "Group"))), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
    }

    @Test
    @DisplayName("path 필터로 지정한 멤버 하나만 제거된다")
    void 특정_멤버만_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("remove", "members[value eq \"kim\"]", null), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("작은따옴표로 감싼 필터 값도 인식한다")
    void 작은따옴표_필터도_인식한다() {
        // given — IdP 에 따라 작은따옴표를 쓴다
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("remove", "members[value eq 'kim']", null), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("필터 없는 remove members 는 멤버를 전부 비운다")
    void 멤버를_전부_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.group("DEV003"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("remove", "members", null), USER_ONLY).block();

        // then
        assertThat(after.members()).isEmpty();
    }

    @Test
    @DisplayName("replace members 는 기존 멤버를 버리고 새 목록으로 갈아끼운다")
    void 멤버를_교체한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("replace", "members", List.of(멤버("park", "User"))), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("조직명을 바꿔도 조직코드와 멤버십은 그대로 유지된다")
    void 조직명만_바꾼다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("replace", "displayName", "플랫폼팀"), USER_ONLY).block();

        // then
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
        assertThat(after.id()).isEqualTo("DEV002");
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("path 가 없으면 본문을 부분 리소스로 보고 있는 속성만 병합한다")
    void path_없는_연산은_속성을_병합한다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("replace", null, Map.of("displayName", "플랫폼팀")), USER_ONLY).block();

        // then
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("연산이 여러 개면 순서대로 누적 적용된다")
    void 여러_연산을_순서대로_적용한다() {
        // given
        var before = 조직(MemberRef.user("lee"));
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("add", "members", List.of(멤버("kim", "User"))),
                new ScimOperation("remove", "members[value eq \"lee\"]", null),
                new ScimOperation("replace", "displayName", "플랫폼팀")));

        // when
        var after = ScimPatchApplier.applyToGroup(before, patch, USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("직원의 active 를 false 로 바꾸면 반영된다")
    void 직원을_비활성화한다() {
        // given
        var before = 직원(true);

        // when
        var after = ScimPatchApplier.applyToUser(before, 패치("replace", "active", false));

        // then
        assertThat(after.active()).isFalse();
        assertThat(after.id()).isEqualTo("kim");
        assertThat(after.email()).isEqualTo("kim@example.com");
    }

    @Test
    @DisplayName("지원하지 않는 path 는 조용히 무시하지 않고 invalidPath 로 거절한다")
    void 지원하지_않는_path는_거절한다() {
        // given — 무시하면 IdP 는 반영된 줄 알고 다시 보내지 않는다
        var before = 조직(MemberRef.user("kim"));

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before, 패치("replace", "emails[type eq \"work\"].value", "x@example.com"), USER_ONLY).block())
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("emails");
    }

    @Test
    @DisplayName("알 수 없는 op 는 invalidSyntax 로 거절한다")
    void 알_수_없는_op는_거절한다() {
        // given
        var before = 조직();

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before, 패치("frobnicate", "members", List.of()), USER_ONLY).block())
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    @DisplayName("멤버의 type 이 없고 현재상태에도 없으면 User 로 둔다")
    void type이_없고_아무것도_없으면_User로_둔다() {
        // given — SCIM 에서 type 은 선택 필드다
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("add", "members", List.of(Map.of("value", "kim"))), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("멤버의 type 이 없으면 추측하지 않고 현재상태로 하위 조직인지 판정한다")
    void type이_없으면_현재상태로_판정한다() {
        // given — DEV003 은 실제로 조직이다. type 이 없다고 User 로 단정하면
        // IdP 가 중첩하려던 조직이 엉뚱한 직원 소속 튜플로 바뀐다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("DEV003", "DEV003", "플랫폼팀", Set.of())).block();
        var resolver = new StateMemberTypeResolver(state);
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(Map.of("value", "DEV003"))), resolver).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
    }

    @Test
    @DisplayName("members 의 value 도 userName·externalId 과 같은 규칙으로 정규화된다")
    void 멤버_value가_정규화된다() {
        // given — 정규화하지 않으면 저장·응답은 되지만 튜플은 하나도 만들어지지 않는다
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(멤버("kim chul:soo", "User"))), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim_chul_soo"));
    }

    @Test
    @DisplayName("필터 remove 의 value 도 정규화해 비교한다")
    void 필터_remove의_value도_정규화된다() {
        // given
        var before = 조직(MemberRef.user("kim_chul_soo"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq \"kim chul:soo\"]", null), USER_ONLY).block();

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("직원과 하위 조직이 같은 id 를 쓰면 필터 remove 가 한쪽만 지운다")
    void 필터_remove는_종류를_구분한다() {
        // given — 조직코드와 직원 아이디는 서로 다른 네임스페이스라 겹칠 수 있다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("X", "X", "엑스팀", Set.of())).block();
        var resolver = new StateMemberTypeResolver(state);
        var before = 조직(MemberRef.user("X"), MemberRef.group("X"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq \"X\"]", null), resolver).block();

        // then — 현재상태에 조직 X 가 있으므로 하위 조직 쪽만 지운다
        assertThat(after.members()).containsExactly(MemberRef.user("X"));
    }

    @Test
    @DisplayName("path 없는 remove 는 invalidSyntax 로 거절한다")
    void path_없는_remove는_거절한다_group() {
        // given — RFC 7644 §3.5.2.1 에서 path 는 remove 에 필수다
        var before = 조직(MemberRef.user("kim"));

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before, 패치("remove", null, Map.of("displayName", "플랫폼팀")), USER_ONLY).block())
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("replace");
    }

    @Test
    @DisplayName("직원에서도 path 없는 remove 는 invalidSyntax 로 거절한다")
    void path_없는_remove는_거절한다_user() {
        // given — RFC 7644 §3.5.2.1 에서 path 는 remove 에 필수다
        var before = 직원(true);

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(before,
                패치("remove", null, Map.of("active", false))))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("replace");
    }
}
