package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimPatchApplierTest {

    /** 대부분의 케이스는 type 이 명시돼 있어 resolver 를 타지 않는다. 타면 모두 User 로 답한다. */
    private static final MemberTypeResolver USER_ONLY = ids -> Mono.just(
            ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.USER)));

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

    private static ScimPatchOp 패치(ScimOperation... operations) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(operations));
    }

    private static Map<String, Object> 멤버(String value, String type) {
        return Map.of("value", value, "type", type);
    }

    /** 모호하지 않으면 부르지 않아야 한다. */
    private static final Predicate<String> 부르면_안된다 = id -> {
        throw new AssertionError("모호하지 않은데 종류를 물었다: " + id);
    };

    /** 조직 PATCH 를 변경으로 정리해 before 에 적용한 결과. 멤버십을 보는 판단(종류 모르는 빼기)은 {@code 조직이면} 이 한다. */
    private static DirectoryGroup 적용한다(DirectoryGroup before, ScimPatchOp patch, MemberTypeResolver resolver,
                                      Predicate<String> 조직이면) {
        return ScimPatchApplier.toGroupChange(patch, resolver).block().applyTo(before, 조직이면);
    }

    private static DirectoryGroup 적용한다(DirectoryGroup before, ScimPatchOp patch, MemberTypeResolver resolver) {
        return 적용한다(before, patch, resolver, 부르면_안된다);
    }

    @Test
    @DisplayName("add members 는 기존 멤버를 유지한 채 새 멤버를 더한다")
    void 멤버를_추가한다() {
        // given
        var before = 조직(MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("add", "members", List.of(멤버("kim", "User"))), USER_ONLY);

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
        var after = 적용한다(before, 패치("add", "members", List.of(멤버("DEV003", "Group"))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
    }

    @Test
    @DisplayName("path 필터로 지정한 멤버 하나만 제거된다")
    void 특정_멤버만_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("remove", "members[value eq \"kim\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("작은따옴표로 감싼 필터 값은 400 invalidFilter 다 — RFC 7644 는 큰따옴표 JSON 문자열만 정한다")
    void 작은따옴표_필터는_거절한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when, then
        assertThatThrownBy(() -> 적용한다(before, 패치("remove", "members[value eq 'kim']", null), USER_ONLY))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidFilter");
                });
    }

    @Test
    @DisplayName("대괄호 안 공백은 한 칸만 받는다 — RFC 7644 ABNF 는 SP 하나만 정한다(가드)")
    void 대괄호_안_공백은_한_칸만_받는다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when, then — 대괄호 안 앞뒤 공백, 토큰 사이 두 칸 공백 모두 거절한다
        for (String path : List.of(
                "members[ value eq \"kim\" ]",
                "members[value  eq \"kim\"]")) {
            assertThatThrownBy(() -> 적용한다(before, 패치("remove", path, null), USER_ONLY))
                    .as(path)
                    .isInstanceOfSatisfying(ScimException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getScimType()).isEqualTo("invalidFilter");
                    });
        }
    }

    @Test
    @DisplayName("아이디에 작은따옴표가 든 멤버(o'brien)도 필터 remove 로 빠진다")
    void 작은따옴표가_든_아이디를_뺀다() {
        // given — SCIM 아이디는 서버가 발급한 UUID 라 실제로는 ' 가 들 일이 없지만, 큰따옴표 값 안의 ' 를 파서가 그대로 다루는지는 계속 본다
        var before = 조직(MemberRef.user("o'brien@corp.com"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("remove", "members[value eq \"o'brien@corp.com\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("필터 값의 JSON 이스케이프를 푼다 — \\\" 는 큰따옴표 한 글자다")
    void 이스케이프를_푼다() {
        // given
        var before = 조직(MemberRef.user("a\"b"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("remove", "members[value eq \"a\\\"b\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("속성 이름의 대소문자는 가리지 않고, 값 안의 ] 도 값이다")
    void 대소문자와_값_안의_괄호() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("x]y"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("remove", "Members[Value eq \"kim\"]", null),
                new ScimOperation("remove", "members[value eq \"x]y\"]", null))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("value eq \"…\" 한 항이 아니거나 값이 비면 400 invalidPath 다 — 500 으로 새지 않는다")
    void 한_항이_아니면_거절한다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when, then
        for (String path : List.of(
                "members[value eq \"kim\" and value eq \"lee\"]",
                "members[display eq \"kim\"]",
                "members[value eq true]",
                "members[value eq \"\"]",
                "members[value eq \"   \"]")) {
            assertThatThrownBy(() -> 적용한다(before, 패치("remove", path, null), USER_ONLY))
                    .as(path)
                    .isInstanceOfSatisfying(ScimException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getScimType()).isEqualTo("invalidPath");
                    });
        }
    }

    @Test
    @DisplayName("필터 없는 remove members 는 멤버를 전부 비운다")
    void 멤버를_전부_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.group("DEV003"));

        // when
        var after = 적용한다(before, 패치("remove", "members", null), USER_ONLY);

        // then
        assertThat(after.members()).isEmpty();
    }

    @Test
    @DisplayName("replace members 는 기존 멤버를 버리고 새 목록으로 갈아끼운다")
    void 멤버를_교체한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before, 패치("replace", "members", List.of(멤버("park", "User"))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("조직명을 바꿔도 조직코드와 멤버십은 그대로 유지된다")
    void 조직명만_바꾼다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = 적용한다(before, 패치("replace", "displayName", "플랫폼팀"), USER_ONLY);

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
        var after = 적용한다(before, 패치("replace", null, Map.of("displayName", "플랫폼팀")), USER_ONLY);

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
        var after = 적용한다(before, patch, USER_ONLY);

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
        assertThatThrownBy(() -> 적용한다(before, 패치("replace", "emails[type eq \"work\"].value", "x@example.com"), USER_ONLY))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("emails");
    }

    @Test
    @DisplayName("조직 PATCH path externalId — add·replace 는 그 값으로, remove 는 비운다(설계 2026-10-06 §4.1)")
    void 조직_path_externalId() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var 바꿈 = ScimPatchApplier.toGroupChange(패치("replace", "externalId", "EXT-9"), USER_ONLY).block();
        var 비움 = ScimPatchApplier.toGroupChange(패치("remove", "externalId", null), USER_ONLY).block();

        // then
        assertThat(바꿈.reidentifies()).isTrue();
        assertThat(바꿈.externalId()).isEqualTo("EXT-9");
        assertThat(비움.reidentifies()).isTrue();
        assertThat(비움.externalId()).isNull();
        assertThat(적용한다(before, 패치("replace", "externalId", "EXT-9"), USER_ONLY))
                .satisfies(after -> {
                    assertThat(after.externalId()).isEqualTo("EXT-9");
                    assertThat(after.members()).containsExactly(MemberRef.user("kim"));
                });
    }

    @Test
    @DisplayName("경로 없는 값의 externalId 키도 path 와 같은 규칙이다 — 조용히 무시하지 않는다(④-1 이월)")
    void 조직_경로_없는_값의_externalId() {
        // given
        var patch = 패치("replace", null, Map.of("externalId", "EXT-9", "displayName", "플랫폼팀"));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();

        // then
        assertThat(change.reidentifies()).isTrue();
        assertThat(change.externalId()).isEqualTo("EXT-9");
        assertThat(change.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("Okta 가 경로 없는 값에 싣는 id 키는 지금처럼 무시한다 — externalId 를 건드리지 않는다")
    void 조직_경로_없는_값의_id_는_무시한다() {
        // given
        var patch = 패치("replace", null, Map.of("id", "DEV002", "displayName", "플랫폼팀"));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();

        // then
        assertThat(change.reidentifies()).isFalse();
        assertThat(change.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("조직 PATCH externalId 의 모르는 op 는 400 invalidSyntax 다")
    void 조직_externalId_모르는_op() {
        // given
        var patch = 패치("move", "externalId", "EXT-9");

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(patch, USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("invalidSyntax"));
    }

    @Test
    @DisplayName("조직 PATCH externalId 에 객체·배열을 주면 400 invalidValue 다 — path 와 경로 없는 값 모두, toString 으로 저장하지 않는다(PUT 은 Jackson 이 400)")
    void 조직_externalId_객체_배열_값은_거절한다() {
        // given — 거절하지 않으면 {value=x} 라는 문자열이 externalId 로 저장되고 중복 판정까지 그 문자열로 돈다
        var 요청들 = List.of(
                패치("replace", "externalId", Map.of("value", "x")),
                패치("replace", "externalId", List.of("x")),
                패치("replace", null, Map.of("externalId", Map.of("value", "x"))),
                패치("replace", null, Map.of("externalId", List.of("x"))));

        // when, then
        for (ScimPatchOp patch : 요청들) {
            assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(patch, USER_ONLY).block())
                    .isInstanceOfSatisfying(ScimException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getScimType()).isEqualTo("invalidValue");
                    });
        }
    }

    @Test
    @DisplayName("조직 PATCH 도 코어 Group URN 접두를 대소문자 없이 뗀다 — path 와 경로 없는 값의 키(설계 2026-10-06 §4.2, 점검 S9)")
    void 조직_Group_URN_접두를_뗀다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var path형 = 적용한다(before,
                패치("replace", "urn:ietf:params:scim:schemas:core:2.0:Group:displayName", "플랫폼팀"), USER_ONLY);
        var 값형 = 적용한다(before,
                패치("replace", null, Map.of("URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:GROUP:displayName", "플랫폼팀")), USER_ONLY);

        // then
        assertThat(path형.displayName()).isEqualTo("플랫폼팀");
        assertThat(값형.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("경로 없는 값의 URN 접두 붙은 members 도 type 없는 멤버를 현재상태로 판정한다 — 모으는 쪽과 적용하는 쪽이 같은 이름을 본다")
    void URN_접두_members_의_type_없는_멤버를_판정한다() {
        // given
        MemberTypeResolver 하위조직이다 = ids -> Mono.just(
                ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.GROUP)));

        // when
        var after = 적용한다(조직(), 패치("add", null, Map.of(
                "urn:ietf:params:scim:schemas:core:2.0:Group:members", List.of(Map.of("value", "SUB1")))), 하위조직이다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("SUB1"));
    }

    @Test
    @DisplayName("path 에 URN 접두가 붙은 members 도 type 없는 멤버를 현재상태로 판정한다 — 모으는 쪽도 path 의 접두를 뗀다")
    void path_URN_접두_members_의_type_없는_멤버를_판정한다() {
        // given
        MemberTypeResolver 하위조직이다 = ids -> Mono.just(
                ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.GROUP)));

        // when
        var after = 적용한다(조직(), 패치("add", "urn:ietf:params:scim:schemas:core:2.0:Group:members",
                List.of(Map.of("value", "SUB1"))), 하위조직이다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("SUB1"));
    }

    @Test
    @DisplayName("path 에 URN 접두가 붙은 externalId 도 바꾼다")
    void path_URN_접두_externalId_를_바꾼다() {
        // given
        var patch = 패치("replace", "urn:ietf:params:scim:schemas:core:2.0:Group:externalId", "EXT-9");

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();

        // then
        assertThat(change.reidentifies()).isTrue();
        assertThat(change.externalId()).isEqualTo("EXT-9");
    }

    @Test
    @DisplayName("알 수 없는 op 는 invalidSyntax 로 거절한다")
    void 알_수_없는_op는_거절한다() {
        // given
        var before = 조직();

        // when, then
        assertThatThrownBy(() -> 적용한다(before, 패치("frobnicate", "members", List.of()), USER_ONLY))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    @DisplayName("멤버의 type 이 없고 현재상태에도 없으면 User 로 둔다")
    void type이_없고_아무것도_없으면_User로_둔다() {
        // given — SCIM 에서 type 은 선택 필드다
        var before = 조직();

        // when
        var after = 적용한다(before, 패치("add", "members", List.of(Map.of("value", "kim"))), USER_ONLY);

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
        var after = 적용한다(before,
                패치("add", "members", List.of(Map.of("value", "DEV003"))), resolver);

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
        assertThat(state.findGroupCalls).as("존재만 보면 되므로 조직 파티션을 통째로 읽지 않는다").isEmpty();
    }

    @Test
    @DisplayName("여러 operation 에 걸친 type 없는 멤버를 한 번에 판정한다 — type 있는 멤버는 묻지 않는다")
    void 요청당_한_번_판정한다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var patch = 패치(
                new ScimOperation("add", "members", List.of(Map.of("value", "a"), Map.of("value", "b"))),
                new ScimOperation("add", null, Map.of("members", List.of(Map.of("value", "c"), Map.of("value", "d", "type", "User")))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("a", "b", "c"));
        assertThat(change.applyTo(조직(), id -> false).members()).containsExactlyInAnyOrder(
                MemberRef.user("a"), MemberRef.user("b"), MemberRef.user("c"), MemberRef.user("d"));
    }

    @Test
    @DisplayName("같은 아이디가 여러 operation 에 나와도 한 번만 묻고 멤버도 한 번만 들어간다")
    void 같은_아이디는_한_번만_묻는다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var patch = 패치(
                new ScimOperation("add", "members", List.of(Map.of("value", "a"))),
                new ScimOperation("add", "members", List.of(Map.of("value", "a"))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("a"));
        assertThat(change.applyTo(조직(), id -> false).members()).containsExactly(MemberRef.user("a"));
    }

    @Test
    @DisplayName("대소문자·공백이 달라도 type 없는 멤버를 모아 묻는다 — 모으는 쪽과 적용하는 쪽이 같은 모양을 읽는다")
    void 모양이_달라도_type_없는_멤버를_빠뜨리지_않는다() {
        // given — 모으는 쪽이 한 모양을 놓치면 그 아이디는 묻지 않고 직원으로 떨어져, 조직이 경고 없이 직원이 된다
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver 모두_조직 = ids -> {
            물은것.add(Set.copyOf(ids));
            return Mono.just(ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.GROUP)));
        };
        var patch = 패치(
                new ScimOperation(" ADD ", "MEMBERS", List.of(Map.of("VALUE", "g1"))),
                new ScimOperation("Add", null, Map.of("Members", List.of(Map.of("value", "g2", "TYPE", " ")))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, 모두_조직).block();

        // then
        assertThat(물은것).containsExactly(Set.of("g1", "g2"));
        assertThat(change.applyTo(조직(), id -> false).members())
                .containsExactlyInAnyOrder(MemberRef.group("g1"), MemberRef.group("g2"));
    }

    @Test
    @DisplayName("members 의 value 도 userName·externalId 과 같은 규칙으로 정규화된다")
    void 멤버_value가_정규화된다() {
        // given — 정규화하지 않으면 저장·응답은 되지만 튜플은 하나도 만들어지지 않는다
        var before = 조직();

        // when
        var after = 적용한다(before,
                패치("add", "members", List.of(멤버("kim chul:soo", "User"))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim_chul_soo"));
    }

    @Test
    @DisplayName("필터 remove 의 value 도 정규화해 비교한다")
    void 필터_remove의_value도_정규화된다() {
        // given
        var before = 조직(MemberRef.user("kim_chul_soo"), MemberRef.user("lee"));

        // when
        var after = 적용한다(before,
                패치("remove", "members[value eq \"kim chul:soo\"]", null), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("직원과 하위 조직이 같은 id 를 쓰면 필터 remove 가 한쪽만 지운다 — 종류는 적용할 때 현재상태로 고른다")
    void 필터_remove는_종류를_구분한다() {
        // given — 조직코드와 직원 아이디는 서로 다른 네임스페이스라 겹칠 수 있다
        var before = 조직(MemberRef.user("X"), MemberRef.group("X"));

        // when — 현재상태에 조직 X 가 있다(유스케이스는 락 안에서 findGroupHeader 로 판정한다)
        var after = 적용한다(before, 패치("remove", "members[value eq \"X\"]", null), USER_ONLY, id -> true);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("X"));
    }

    @Test
    @DisplayName("path 없는 remove 는 400 noTarget 이다 — RFC 7644 §3.5.2.2(점검 S11)")
    void path_없는_remove는_noTarget이다_group() {
        // given — 무엇을 지울지 가리키지 않았다
        var before = 조직(MemberRef.user("kim"));

        // when, then
        assertThatThrownBy(() -> 적용한다(before, 패치("remove", null, null), USER_ONLY))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("noTarget");
                });
    }

    @Test
    @DisplayName("직원에서도 path 없는 remove 는 400 noTarget 이다 — RFC 7644 §3.5.2.2(점검 S11)")
    void path_없는_remove는_noTarget이다_user() {
        // given — 무엇을 지울지 가리키지 않았다
        var before = 직원(true);

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(before, 패치("remove", null, null)))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("noTarget");
                });
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
    @DisplayName("RFC 가 정의했지만 저장하지 않는 속성은 path 로 와도 받아서 버린다 — 직원은 그대로다(설계 2026-10-06 §3.1, 점검 M5)")
    void 저장하지_않는_RFC_속성은_받아서_버린다() {
        // given
        var before = 이름있는_직원();
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(before, 패치(
                new ScimOperation("replace", "emails[type eq \"home\"].value", "a@x.com"),
                new ScimOperation("replace", "title", "과장"),
                new ScimOperation("replace", "phoneNumbers[type eq \"mobile\"].value", "010"),
                new ScimOperation("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department", "개발")),
                버린것::add);

        // then
        assertThat(after).isEqualTo(before);
        assertThat(버린것).containsExactly("emails", "title", "phoneNumbers", "department");
    }

    @Test
    @DisplayName("필터 없는 emails.value 는 적용하지 못하는 emails 모양이라 받아서 버린다")
    void 필터_없는_emails_value_는_받아서_버린다() {
        // given
        var before = 이름있는_직원();
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(before, 패치("replace", "emails.value", "x@y.com"), 버린것::add);

        // then
        assertThat(after).isEqualTo(before);
        assertThat(버린것).containsExactly("emails");
    }

    @Test
    @DisplayName("emails 의 값이 배열이 아니면 받아서 버리지 않고 400 invalidSyntax 다 — 저장하는 속성은 엄격하게 적용한다")
    void 배열이_아닌_emails_값은_invalidSyntax() {
        // when, then
        거절한다(패치("replace", "emails", "a@x.com"), "invalidSyntax");
    }

    @Test
    @DisplayName("점검 M5 의 Entra 요청 — 전화번호 path 연산과 경로 없는 active=false 가 한 요청에 오면 비활성화가 반영된다")
    void 전화번호_path_가_섞여도_비활성화된다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("replace", "phoneNumbers[type eq \"work\"].value", "010-1234-5678"),
                new ScimOperation("replace", null, Map.of("active", false))), 버린것::add);

        // then
        assertThat(after.active()).isFalse();
        assertThat(버린것).containsExactly("phoneNumbers");
    }

    @Test
    @DisplayName("한 요청에 섞인 연산 — 저장하는 path 는 모두 반영되고 저장하지 않는 것만 버린다")
    void 섞인_연산은_저장하는_것만_반영한다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("replace", "addresses[type eq \"work\"].streetAddress", "판교로 1"),
                new ScimOperation("replace", "displayName", "김철수(개명)"),
                new ScimOperation("replace", null, Map.of("active", false, "nickName", "철이"))), 버린것::add);

        // then
        assertThat(after.displayName()).isEqualTo("김철수(개명)");
        assertThat(after.active()).isFalse();
        assertThat(버린것).containsExactly("addresses", "nickName");
    }

    @Test
    @DisplayName("저장하는 work 이메일의 다른 하위 속성(.display)은 버리고 이메일은 그대로다")
    void work_이메일의_display_는_버린다() {
        // given
        var before = 이름있는_직원();
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(before,
                패치("replace", "emails[type eq \"work\"].display", "회사 메일"), 버린것::add);

        // then
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(버린것).containsExactly("emails");
    }

    @Test
    @DisplayName("경로 없는 값의 모르는 키는 지금처럼 무시하되, 표에 없는 키의 이름은 other 로 알린다 — 요청 문자열을 그대로 넘기지 않는다")
    void 경로_없는_값의_표_밖_키는_other() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null, Map.of(
                "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter", "C1")), 버린것::add);

        // then
        assertThat(after).isEqualTo(이름있는_직원());
        assertThat(버린것).containsExactly("other");
    }

    @Test
    @DisplayName("RFC 에 없는 path 는 지금처럼 400 invalidPath 다 — 오타·커스텀 확장·URN 없는 enterprise 이름·공통 속성 id")
    void RFC_밖의_path는_invalidPath() {
        // when, then
        거절한다(패치("replace", "name.givenNmae", "철수"), "invalidPath");
        거절한다(패치("replace", "phoneNumber", "010"), "invalidPath");
        거절한다(패치("replace", "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter", "C1"), "invalidPath");
        거절한다(패치("replace", "department", "개발"), "invalidPath");
        거절한다(패치("replace", "id", "other-id"), "invalidPath");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "True", "TRUE"})
    @DisplayName("active 문자열 \"true\" 는 대소문자 없이 참이다 — Entra 는 문자열로 보낸다")
    void active_문자열_참(String 값) {
        // when
        var after = ScimPatchApplier.applyToUser(직원(false), 패치("replace", "active", 값));

        // then
        assertThat(after.active()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "False"})
    @DisplayName("active 문자열 \"false\" 는 대소문자 없이 거짓이다")
    void active_문자열_거짓(String 값) {
        // when
        var after = ScimPatchApplier.applyToUser(직원(true), 패치("replace", "active", 값));

        // then
        assertThat(after.active()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", " true", ""})
    @DisplayName("active 의 그 밖의 문자열은 조용히 비활성화하지 않고 400 invalidValue 다(설계 2026-10-06 §5.2, 점검 S8)")
    void active_의_그_밖_문자열은_invalidValue(String 값) {
        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(직원(true), 패치("replace", "active", 값)))
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getScimType()).isEqualTo("invalidValue");
                });
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
        var 추가 = 적용한다(before,
                패치("add", "Members", List.of(멤버("park", "User"))), USER_ONLY);
        var 제거 = 적용한다(before,
                패치("remove", "MEMBERS[VALUE EQ \"lee\"]", null), USER_ONLY);
        var 이름 = 적용한다(before, 패치("replace", "DisplayName", "새 팀"), USER_ONLY);

        // then
        assertThat(추가.members()).contains(MemberRef.user("park"));
        assertThat(제거.members()).containsExactly(MemberRef.user("kim"));
        assertThat(이름.displayName()).isEqualTo("새 팀");
    }

    // ---------- F1: 경로 없는 값 키도 path 와 같은 해석기로 푼다 ----------

    @Test
    @DisplayName("Entra 표준 호환 모드(aadOptscim062020) — 경로 없는 값의 점 표기 키도 path 와 같은 해석기로 푼다")
    void Entra_표준_호환_모드_경로_없는_값() {
        // given — 이름 변경을 경로 없이 점 표기 키로 보낸다. employeeNumber 는 우리가 저장하지 않는 속성이다.
        Map<String, Object> 값 = new LinkedHashMap<>();
        값.put("displayName", "Bjfe");
        값.put("name.givenName", "Kkom");
        값.put("name.familyName", "Unua");
        값.put("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber", "Aklq");

        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null, 값));

        // then — employeeNumber 는 조용히 무시되고 예외도 없다
        assertThat(after.displayName()).isEqualTo("Bjfe");
        assertThat(after.name().givenName()).isEqualTo("Kkom");
        assertThat(after.name().familyName()).isEqualTo("Unua");
    }

    @Test
    @DisplayName("경로 없는 값의 emails[type eq \"work\"].value 키도 반영된다")
    void 경로_없는_값의_이메일_필터_키() {
        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", null, Map.of("emails[type eq \"work\"].value", "a@x")));

        // then
        assertThat(after.email()).isEqualTo("a@x");
    }

    @Test
    @DisplayName("경로 없는 값의 모르는 name 하위 키(name.nickName)는 무시하고 나머지 키는 반영한다")
    void 경로_없는_값의_모르는_name_하위_키는_무시한다() {
        // given
        Map<String, Object> 값 = new LinkedHashMap<>();
        값.put("name.nickName", "x");
        값.put("displayName", "새이름");

        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null, 값));

        // then
        assertThat(after.displayName()).isEqualTo("새이름");
        assertThat(after.name()).isEqualTo(홍길동);
    }

    @Test
    @DisplayName("path 의 코어 스키마 URN 접두는 대소문자 없이 떼고 해석한다")
    void path_코어_URN_접두를_뗀다() {
        // when
        DirectoryUser after = ScimPatchApplier.applyToUser(이름있는_직원(),
                패치("replace", "URN:ietf:params:scim:schemas:Core:2.0:User:name.givenName", "길순"));

        // then
        assertThat(after.name().givenName()).isEqualTo("길순");
    }

    @Test
    @DisplayName("enterprise 확장의 manager·employeeNumber path 는 받아서 버린다 — Entra 참조 실패로 세지지 않는다(점검 M18)")
    void enterprise_속성은_받아서_버린다() {
        // given
        List<String> 버린것 = new ArrayList<>();

        // when
        var after = ScimPatchApplier.applyToUser(이름있는_직원(), 패치(
                new ScimOperation("add", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager", "boss-1"),
                new ScimOperation("replace", "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber", "Aklq")),
                버린것::add);

        // then
        assertThat(after).isEqualTo(이름있는_직원());
        assertThat(버린것).containsExactly("manager", "employeeNumber");
    }

    // ---------- 저장하는 속성에 닿는가 (설계 2026-10-07 §4) ----------

    private static final String MANAGER = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager";

    @Test
    @DisplayName("버리는 속성만 있는 PATCH 는 저장하는 속성에 닿지 않는다 — 버린 이름은 알린다(설계 2026-10-07 §4)")
    void 버리는_속성만이면_닿지_않는다() {
        // given
        List<String> 버린것 = new ArrayList<>();
        var 패치 = 패치(
                new ScimOperation("replace", MANAGER, Map.of("value", "lee")),
                new ScimOperation("replace", "phoneNumbers[type eq \"work\"].value", "010-1234-5678"),
                new ScimOperation("replace", "emails[type eq \"home\"].value", "kim@home.example"));

        // when
        boolean 닿지_않는다 = ScimPatchApplier.touchesNoStoredUserAttribute(패치, 버린것::add);

        // then
        assertThat(닿지_않는다).isTrue();
        assertThat(버린것).containsExactly("manager", "phoneNumbers", "emails");
    }

    @Test
    @DisplayName("경로 없는 값의 키가 모두 저장하지 않는 것이어도 닿지 않는다 — 모르는 키는 other 다")
    void 경로_없는_값의_키가_모두_버리는_것이면_닿지_않는다() {
        // given
        List<String> 버린것 = new ArrayList<>();
        var 패치 = 패치("replace", null, Map.of("nickName", "k"));

        // when, then
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치, 버린것::add)).isTrue();
        assertThat(버린것).containsExactly("nickName");
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(
                패치("replace", null, Map.of("x-custom", 1)), 버린것::add)).isTrue();
        assertThat(버린것).containsExactly("nickName", "other");
    }

    @Test
    @DisplayName("저장하는 속성이 하나라도 섞이면 닿는다 — 같은 값을 다시 보낸 것도 락 안으로 간다")
    void 저장하는_속성이_섞이면_닿는다() {
        // when, then
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(
                패치(new ScimOperation("replace", MANAGER, Map.of("value", "lee")),
                        new ScimOperation("replace", "displayName", "김철수")), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "name.givenName", "철수"), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "emails[type eq \"work\"].value", "a@b.c"), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", null, Map.of("nickName", "k", "active", false)), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "urn:ietf:params:scim:schemas:core:2.0:User:active", true), 이름 -> { })).isFalse();
    }

    @Test
    @DisplayName("판정도 적용과 같은 모양 검사를 한다 — 모르는 path·경로 없는 remove·객체가 아닌 값은 400 이다")
    void 판정도_모양을_검사한다() {
        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "fooBar", "x"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("invalidPath"));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("remove", null, null), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("noTarget"));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", null, "문자열"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("frobnicate", "displayName", "x"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ---------- F3: 빈 userName ----------

    @Test
    @DisplayName("빈 userName 으로 add/replace 하면 400 invalidValue 다 — path·경로 없는 값 모두")
    void 빈_userName은_invalidValue() {
        // path 형식
        거절한다(패치("replace", "userName", ""), "invalidValue");

        // 경로 없는 값 형식 — Map.of 는 null 값을 못 담아 HashMap 을 쓴다
        Map<String, Object> 값 = new HashMap<>();
        값.put("userName", null);
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(이름있는_직원(), 패치("replace", null, 값)))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    // ---------- F4: 멤버 객체 키도 대소문자 무시 ----------

    @Test
    @DisplayName("멤버 객체의 키(value·type)도 대소문자를 가리지 않는다")
    void 멤버_객체_키도_대소문자를_가리지_않는다() {
        // given
        var before = 조직();

        // when
        var after = 적용한다(before,
                패치("add", "members", List.of(Map.of("VALUE", "u1", "Type", "User"))), USER_ONLY);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("u1"));
    }

    // ---------- F8: 테스트 빈틈 ----------

    @Test
    @DisplayName("맨 emails[type eq \"work\"] 필터(.value 없이)도 이메일이 없으면 replace 가 400 noTarget 이다")
    void 맨_이메일_필터도_이메일_없으면_noTarget() {
        // given
        DirectoryUser 메일없음 = 이름있는_직원().withEmail(null);

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToUser(메일없음,
                패치("replace", "emails[type eq \"work\"]", Map.of("value", "a@x.com", "type", "work"))))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("noTarget"));
    }

    // ---------- 조직 멤버 PATCH (설계 §4·§7) ----------

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기(값 붙은 remove members)는 400 invalidValue 로 거절하고 필터와 옵션을 안내한다")
    void 값_붙은_remove는_거절한다() {
        // given — MS 호환성 문서의 기본 모드 예시 그대로
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("Remove", "members", List.of(Map.of("value", "u1091")))));

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(patch, USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getScimType()).isEqualTo("invalidValue");
                    assertThat(e.getMessage()).isEqualTo(ScimPatchApplier.REMOVE_WITH_VALUE)
                            .contains("members[value eq \"<id>\"]").contains("?aadOptscim062020");
                });
    }

    @Test
    @DisplayName("값이 빈 목록이어도 거절한다 — 아무도 안 빼는지 다 빼는지 뜻이 갈린다")
    void 빈_목록_값도_거절한다() {
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(패치("remove", "members", List.of()), USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("값이 null 이면 표준대로 전원 빼기다")
    void 값이_null이면_전원_빼기다() {
        // given — "value": null 은 DTO 에서 null 이라 값 없음과 같다(Keycloak 커뮤니티 플러그인이 조직을 비울 때 보낸다)

        // when
        var change = ScimPatchApplier.toGroupChange(패치("remove", "members", null), USER_ONLY).block();

        // then
        assertThat(change.replacesMembers()).isTrue();
        assertThat(change.base()).isEmpty();
    }

    @Test
    @DisplayName("Entra 옵션 모드(aadOptscim062020)의 멤버 빼기는 id 빼기다")
    void Entra_옵션_모드_빼기() {
        // given — MS 호환성 문서의 옵션 모드 예시 그대로
        var patch = 패치("remove", "members[value eq \"7f4bc1a3-285e-48ae-8202-5accb43efb0e\"]", null);

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();

        // then
        assertThat(change.replacesMembers()).isFalse();
        assertThat(change.ops()).containsExactly(new GroupChange.RemoveId("7f4bc1a3-285e-48ae-8202-5accb43efb0e"));
    }

    @Test
    @DisplayName("Okta 의 add·필터 remove·replace 는 증분 추가·id 빼기·전체 교체다")
    void Okta_모양() {
        // given — Okta SCIM 2.0 문서 예시 모양(add 에는 type 없이 display 가 온다)
        var 추가 = 패치("add", "members", List.of(Map.of("value", "23a35c27", "display", "test.user@okta.local")));
        var 빼기 = 패치("remove", "members[value eq \"89bb1940\"]", null);
        var 교체 = 패치("replace", "members", List.of(Map.of("value", "23a35c27"), Map.of("value", "89bb1940")));

        // when
        var 추가변경 = ScimPatchApplier.toGroupChange(추가, USER_ONLY).block();
        var 빼기변경 = ScimPatchApplier.toGroupChange(빼기, USER_ONLY).block();
        var 교체변경 = ScimPatchApplier.toGroupChange(교체, USER_ONLY).block();

        // then
        assertThat(추가변경.ops()).containsExactly(new GroupChange.Add(MemberRef.user("23a35c27")));
        assertThat(빼기변경.ops()).containsExactly(new GroupChange.RemoveId("89bb1940"));
        assertThat(교체변경.base()).containsExactlyInAnyOrder(MemberRef.user("23a35c27"), MemberRef.user("89bb1940"));
    }

    @Test
    @DisplayName("경로 없는 add 의 members 는 증분 추가다 — RFC 7644 §3.5.2.1")
    void 경로_없는_add의_members는_증분_추가다() {
        // given
        var before = 조직(MemberRef.user("lee"));
        var patch = 패치("add", null, Map.of("members", List.of(멤버("kim", "User"))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();
        var after = 적용한다(before, patch, USER_ONLY);

        // then
        assertThat(change.replacesMembers()).isFalse();
        assertThat(change.ops()).containsExactly(new GroupChange.Add(MemberRef.user("kim")));
        assertThat(after.members()).containsExactlyInAnyOrder(MemberRef.user("lee"), MemberRef.user("kim"));
    }

    @Test
    @DisplayName("경로 없는 replace 의 members 는 지금처럼 전체 교체다")
    void 경로_없는_replace의_members는_전체_교체다() {
        // given
        var before = 조직(MemberRef.user("lee"));
        var patch = 패치("replace", null, Map.of("members", List.of(멤버("kim", "User"))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();
        var after = 적용한다(before, patch, USER_ONLY);

        // then
        assertThat(change.replacesMembers()).isTrue();
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("PATCH 정리는 멤버십을 읽지 않는다 — 조직이 없어도 변경이 만들어진다")
    void 정리는_멤버십을_읽지_않는다() {
        // when
        var change = ScimPatchApplier.toGroupChange(
                패치("add", "members", List.of(멤버("kim", "User"))), USER_ONLY).block();

        // then
        assertThat(change).isEqualTo(GroupChange.delta().adding(java.util.Set.of(MemberRef.user("kim"))));
    }
}
