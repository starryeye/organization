package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimPatchApplierTest {

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
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(멤버("kim", "User"))));

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
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(멤버("DEV003", "Group"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
    }

    @Test
    @DisplayName("path 필터로 지정한 멤버 하나만 제거된다")
    void 특정_멤버만_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq \"kim\"]", null));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("작은따옴표로 감싼 필터 값도 인식한다")
    void 작은따옴표_필터도_인식한다() {
        // given — IdP 에 따라 작은따옴표를 쓴다
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq 'kim']", null));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("필터 없는 remove members 는 멤버를 전부 비운다")
    void 멤버를_전부_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.group("DEV003"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("remove", "members", null));

        // then
        assertThat(after.members()).isEmpty();
    }

    @Test
    @DisplayName("replace members 는 기존 멤버를 버리고 새 목록으로 갈아끼운다")
    void 멤버를_교체한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", "members", List.of(멤버("park", "User"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("조직명을 바꿔도 조직코드와 멤버십은 그대로 유지된다")
    void 조직명만_바꾼다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", "displayName", "플랫폼팀"));

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
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", null, Map.of("displayName", "플랫폼팀")));

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
        var after = ScimPatchApplier.applyToGroup(before, patch);

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
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before,
                패치("replace", "emails[type eq \"work\"].value", "x@example.com")))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("emails");
    }

    @Test
    @DisplayName("알 수 없는 op 는 invalidSyntax 로 거절한다")
    void 알_수_없는_op는_거절한다() {
        // given
        var before = 조직();

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before,
                패치("frobnicate", "members", List.of())))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    @DisplayName("멤버의 type 이 없으면 User 로 간주한다")
    void type이_없으면_User로_본다() {
        // given — SCIM 에서 type 은 선택 필드다
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(Map.of("value", "kim"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }
}
