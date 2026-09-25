package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

    private static final PersonName 홍길동 = new PersonName("홍길동", "홍", "길동", null, null, null);

    private static DirectoryUser 이름있는_직원() {
        return new DirectoryUser("hong", "emp-1", "hong", "홍길동", "hong@example.com", true, 홍길동);
    }

    private static ScimPatchOp 연산들(ScimOperation... operations) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(operations));
    }

    private static void 거절한다(ScimPatchOp patch, String scimType) {
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(이름있는_직원(), patch))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo(scimType);
                });
    }

    @Test
    @DisplayName("Entra 문서의 PATCH 예시 — 이메일과 성을 한 요청에서 바꾼다")
    void Entra_의_이메일과_성_PATCH() {
        // given — learn.microsoft.com "Develop a SCIM endpoint" 의 Update User [Multi-valued properties] 예시
        ScimPatchOp patch = 연산들(
                new ScimOperation("Replace", "emails[type eq \"work\"].value", "updatedEmail@microsoft.com"),
                new ScimOperation("Replace", "name.familyName", "updatedFamilyName"));

        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(), patch);

        // then
        assertThat(after.email()).isEqualTo("updatedEmail@microsoft.com");
        assertThat(after.name()).isEqualTo(홍길동.withFamilyName("updatedFamilyName"));
    }

    @Test
    @DisplayName("name 은 준 하위 속성만 바꾸고 나머지는 둔다, remove 는 전부 비운다")
    void name_은_하위_속성만_바꾼다() {
        // when
        DirectoryUser 병합 = ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "name", Map.of("givenName", "길순", "middleName", "가")));
        DirectoryUser 비움 = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "name", null));

        // then
        assertThat(병합.name()).isEqualTo(new PersonName("홍길동", "홍", "길순", "가", null, null));
        assertThat(비움.name()).isEqualTo(PersonName.EMPTY);
    }

    @Test
    @DisplayName("name.* 여섯 칸은 설정과 remove 를 받는다")
    void name_하위_경로() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("add", "name.honorificPrefix", "Mr."))
                .name().honorificPrefix()).isEqualTo("Mr.");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "name.givenName", null))
                .name().givenName()).isNull();
        거절한다(패치("replace", "name.nickName", "x"), "invalidPath");
    }

    @Test
    @DisplayName("externalId·displayName 은 설정과 remove, active 의 remove 는 활성이다")
    void externalId_displayName_active() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", "externalId", "emp-2"))
                .externalId()).isEqualTo("emp-2");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "externalId", null))
                .externalId()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "displayName", null))
                .displayName()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원().withActive(false), 패치("remove", "active", null))
                .active()).isTrue();
    }

    @Test
    @DisplayName("userName 은 필수라 remove 하면 400 mutability 다")
    void userName_remove_는_mutability() {
        거절한다(패치("remove", "userName", null), "mutability");
    }

    @Test
    @DisplayName("emails 는 primary(없으면 첫째)를 담고, work 필터는 add·replace·remove 를 받는다")
    void 이메일_경로() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", "emails", List.of(
                Map.of("value", "a@x.com"), Map.of("value", "b@x.com", "primary", true)))).email())
                .isEqualTo("b@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("remove", "emails", null)).email()).isNull();
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "emails[type eq \"work\"]", Map.of("value", "c@x.com", "type", "work"))).email())
                .isEqualTo("c@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("remove", "emails[type eq \"work\"].value", null)).email()).isNull();
    }

    @Test
    @DisplayName("이메일이 없을 때 work 필터 replace 는 400 noTarget, add 는 설정한다")
    void 이메일이_없으면_replace_는_noTarget() {
        // given
        DirectoryUser 메일없음 = 이름있는_직원().withEmail(null);

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(메일없음,
                패치("replace", "emails[type eq \"work\"].value", "a@x.com")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("noTarget"));
        assertThat(ScimPatchApplier.applyToUser(메일없음,
                패치("add", "emails[type eq \"work\"].value", "a@x.com")).email()).isEqualTo("a@x.com");
    }

    @Test
    @DisplayName("work 가 아닌 이메일 필터와 저장하지 않는 속성은 400 invalidPath 다")
    void 저장하지_않는_경로는_invalidPath() {
        거절한다(패치("replace", "emails[type eq \"home\"].value", "a@x.com"), "invalidPath");
        거절한다(패치("replace", "title", "과장"), "invalidPath");
        거절한다(패치("replace", "phoneNumbers[type eq \"mobile\"].value", "010"), "invalidPath");
        거절한다(패치("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department", "개발"),
                "invalidPath");
    }

    @Test
    @DisplayName("경로와 값 객체의 속성 이름은 대소문자를 가리지 않는다")
    void 속성_이름은_대소문자를_가리지_않는다() {
        // when, then
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("Replace", "Name.GivenName", "길순"))
                .name().givenName()).isEqualTo("길순");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "EMAILS[TYPE EQ \"Work\"].VALUE", "d@x.com")).email()).isEqualTo("d@x.com");
        assertThat(ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null,
                Map.of("DisplayName", "홍", "ExternalId", "e9", "Name", Map.of("FamilyName", "洪")))))
                .satisfies(user -> {
                    assertThat(user.displayName()).isEqualTo("홍");
                    assertThat(user.externalId()).isEqualTo("e9");
                    assertThat(user.name().familyName()).isEqualTo("洪");
                    assertThat(user.name().givenName()).isEqualTo("길동");
                });
    }

    @Test
    @DisplayName("조직 PATCH 경로도 대소문자를 가리지 않는다")
    void 조직_경로도_대소문자를_가리지_않는다() {
        // given
        var before = 조직(MemberRef.user("lee"), MemberRef.user("kim"));

        // when
        var 추가 = ScimPatchApplier.applyToGroup(before,
                패치("add", "Members", List.of(멤버("park", "User"))), USER_ONLY).block();
        var 제거 = ScimPatchApplier.applyToGroup(before,
                패치("remove", "MEMBERS[VALUE EQ \"lee\"]", null), USER_ONLY).block();
        var 이름 = ScimPatchApplier.applyToGroup(before, 패치("replace", "DisplayName", "새 팀"), USER_ONLY).block();

        // then
        assertThat(추가.members()).contains(MemberRef.user("park"));
        assertThat(제거.members()).containsExactly(MemberRef.user("kim"));
        assertThat(이름.displayName()).isEqualTo("새 팀");
    }
}
