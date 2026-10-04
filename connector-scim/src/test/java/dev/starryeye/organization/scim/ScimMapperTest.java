package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimMapperTest {

    /** 부르는 쪽(핸들러)이 정해 넘기는 id. 매퍼는 이 값을 그대로 쓴다. */
    private static final String ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    /** type 이 명시된 케이스는 이 resolver 를 타지 않는다. 타면 모두 User 로 답한다. */
    private static final MemberTypeResolver USER_ONLY = ids -> Mono.just(
            ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.USER)));

    @Test
    @DisplayName("직원 아이디는 넘겨받은 id 이고 표시명은 displayName 을 우선한다")
    void 유저를_도메인으로_변환한다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, "emp-1001", "kim",
                new ScimName("김철수", null, null, null, null, null), "철수",
                List.of(new ScimEmail("kim@example.com", "work", true)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.userName()).isEqualTo("kim");
        assertThat(user.externalId()).isEqualTo("emp-1001");
        assertThat(user.displayName()).isEqualTo("철수");
        assertThat(user.email()).isEqualTo("kim@example.com");
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("displayName 이 없으면 name.formatted 를 표시명으로 쓴다")
    void 표시명이_없으면_formatted를_쓴다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim",
                new ScimName("김철수", null, null, null, null, null), null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.displayName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("active 가 없으면 활성으로 간주한다")
    void active가_없으면_활성이다() {
        // given — SCIM 에서 active 는 선택 필드다
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim",
                null, null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("primary 표시가 없으면 첫 번째 이메일을 쓴다")
    void primary가_없으면_첫_이메일을_쓴다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim", null, null,
                List.of(new ScimEmail("a@example.com", "home", null),
                        new ScimEmail("b@example.com", "work", null)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.email()).isEqualTo("a@example.com");
    }

    @Test
    @DisplayName("조직 아이디는 넘겨받은 id 이고 externalId 는 속성으로 담기며 조직명은 displayName 에서 온다")
    void 그룹을_도메인으로_변환한다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("DEV002", "Group", null),
                        new ScimMember("park", "User", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block();

        // then
        assertThat(group.id()).isEqualTo(ID);
        assertThat(group.externalId()).isEqualTo("DEV001");
        assertThat(group.displayName()).isEqualTo("개발본부");
        assertThat(group.members())
                .containsExactlyInAnyOrder(MemberRef.group("DEV002"), MemberRef.user("park"));
    }

    @Test
    @DisplayName("본문의 id 는 쓰지 않고 넘겨받은 id 를 쓴다 — 직원")
    void 직원은_본문의_id를_쓰지_않는다() {
        // given — id 는 서버가 정하는 값이다(RFC 7643 §3.1)
        var scim = new ScimUser(List.of(ScimSchemas.USER), "client-chosen", null, "kim",
                null, null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("본문의 id 와 externalId 는 쓰지 않고 넘겨받은 id 를 쓴다 — 조직")
    void 조직은_본문의_id를_쓰지_않는다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), "DEV009", "DEV001", "운영팀",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block();

        // then
        assertThat(group.id()).isEqualTo(ID);
        assertThat(group.externalId()).isEqualTo("DEV001");
    }

    @Test
    @DisplayName("도메인 유저를 SCIM 응답으로 되돌리면 스키마와 필수 필드가 채워진다")
    void 유저를_SCIM_응답으로_변환한다() {
        // given
        var user = new DirectoryUser("kim", "emp-1001", "kim", "김철수", "kim@example.com", true);

        // when
        ScimUser scim = ScimMapper.toScimUser(user);

        // then
        assertThat(scim.schemas()).containsExactly(ScimSchemas.USER);
        assertThat(scim.id()).isEqualTo("kim");
        assertThat(scim.externalId()).isEqualTo("emp-1001");
        assertThat(scim.active()).isTrue();
        assertThat(scim.emails()).hasSize(1);
        assertThat(scim.meta().resourceType()).isEqualTo("User");
    }

    @Test
    @DisplayName("도메인 조직을 SCIM 응답으로 되돌리면 멤버 type 이 복원된다")
    void 그룹을_SCIM_응답으로_변환한다() {
        // given
        var group = new DirectoryGroup("DEV001", "DEV001", "개발본부",
                Set.of(MemberRef.group("DEV002"), MemberRef.user("park")));

        // when
        ScimGroup scim = ScimMapper.toScimGroup(group);

        // then
        assertThat(scim.schemas()).containsExactly(ScimSchemas.GROUP);
        assertThat(scim.id()).isEqualTo("DEV001");
        assertThat(scim.displayName()).isEqualTo("개발본부");
        assertThat(scim.members()).extracting(ScimMember::type)
                .containsExactlyInAnyOrder("Group", "User");
        assertThat(scim.meta().resourceType()).isEqualTo("Group");
    }

    @Test
    @DisplayName("이메일이 없는 직원은 emails 를 비운 채 응답한다")
    void 이메일이_없으면_빈_배열이다() {
        // given
        var user = new DirectoryUser("kim", null, "kim", "김철수", null, true);

        // when
        ScimUser scim = ScimMapper.toScimUser(user);

        // then
        assertThat(scim.emails()).isEmpty();
    }

    @Test
    @DisplayName("userName 에 금지 문자가 있어도 id 는 넘겨받은 값이고 userName 은 그대로 담긴다")
    void userName의_금지_문자는_id에_닿지_않는다() {
        // given — 공백, 콜론, 해시 등 IdNormalizer 가 제거하는 문자
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim lee:admin#1",
                null, null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.id()).isEqualTo(ID);
        assertThat(user.userName()).isEqualTo("kim lee:admin#1");
    }

    @Test
    @DisplayName("externalId 에 금지 문자가 있어도 id 는 넘겨받은 값이고 externalId 는 그대로 담긴다")
    void externalId의_금지_문자는_id에_닿지_않는다() {
        // given — 공백, 콜론 등 IdNormalizer 가 제거하는 문자
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV 001:main", "개발본부",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block();

        // then
        assertThat(group.id()).isEqualTo(ID);
        assertThat(group.externalId()).isEqualTo("DEV 001:main");
    }

    @Test
    @DisplayName("primary 이메일이 첫 번째가 아니면 primary 를 택한다")
    void primary_이메일을_우선한다() {
        // given — 두 이메일 중 두 번째가 primary
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim", null, null,
                List.of(new ScimEmail("a@example.com", "home", false),
                        new ScimEmail("b@example.com", "work", true)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.email()).isEqualTo("b@example.com");
    }

    @Test
    @DisplayName("displayName 과 name.formatted 가 모두 없으면 userName 을 표시명으로 쓴다")
    void 세_번째_fallback_userName이_표시명이_된다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim.lee",
                null, null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);

        // then
        assertThat(user.displayName()).isEqualTo("kim.lee");
    }

    @Test
    @DisplayName("멤버의 type 이 null 이고 현재상태에도 없으면 User 로 둔다")
    void null_type은_현재상태에_없으면_User가_된다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("kim", null, null),
                        new ScimMember("DEV002", "Group", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block();

        // then
        assertThat(group.members())
                .containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.group("DEV002"));
    }

    @Test
    @DisplayName("멤버의 type 이 없으면 추측하지 않고 현재상태로 하위 조직인지 판정한다")
    void type이_없으면_현재상태로_판정한다() {
        // given — RFC 7643 에서 type 은 선택 필드지만 중첩 조직을 표현하는 유일한 수단이기도 하다.
        // 조직코드와 직원 아이디는 네임스페이스가 달라 겹칠 수 있으므로 User 로 단정하면 안 된다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", java.util.Set.of())).block();
        var resolver = new StateMemberTypeResolver(state);
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("DEV002", null, null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, resolver).block();

        // then
        assertThat(group.members()).containsExactly(MemberRef.group("DEV002"));
    }

    @Test
    @DisplayName("POST·PUT 본문의 type 없는 멤버를 한 번에 판정한다")
    void 본문당_한_번_판정한다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("kim", null, null), new ScimMember("lee", null, null), new ScimMember("DEV002", "Group", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("kim", "lee"));
        assertThat(group.members()).containsExactlyInAnyOrder(
                MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("DEV002"));
    }

    @Test
    @DisplayName("members 의 value 도 userName·externalId 과 같은 규칙으로 정규화된다")
    void 멤버_value가_정규화된다() {
        // given — IdP 가 우리가 발급한 id 를 그대로 돌려주지 않는 경우. 정규화하지 않으면
        // 이 멤버는 저장되고 201 본문에도 실리지만 튜플은 하나도 만들어지지 않는다
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("kim chul soo", "User", null),
                        new ScimMember("DEV 002:sub", "Group", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block();

        // then
        assertThat(group.members()).containsExactlyInAnyOrder(
                MemberRef.user("kim_chul_soo"), MemberRef.group("DEV_002_sub"));
    }

    @Test
    @DisplayName("userName 이 없으면 invalidSyntax 예외를 던진다")
    void userName이_없으면_예외() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, null,
                null, null, List.of(), null, null);

        // when & then
        assertThatThrownBy(() -> ScimMapper.toDirectoryUser(scim, ID))
                .isInstanceOf(ScimException.class)
                .hasMessage("userName 은 필수입니다");
    }

    @Test
    @DisplayName("userName 이 빈 문자열이면 invalidSyntax 예외를 던진다")
    void userName이_빈_문자열이면_예외() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "   ",
                null, null, List.of(), null, null);

        // when & then
        assertThatThrownBy(() -> ScimMapper.toDirectoryUser(scim, ID))
                .isInstanceOf(ScimException.class)
                .hasMessage("userName 은 필수입니다");
    }

    @Test
    @DisplayName("멤버의 value 가 없으면 invalidSyntax 예외를 던진다")
    void 멤버_value가_없으면_예외() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember(null, "User", null)), null);

        // when & then
        assertThatThrownBy(() -> ScimMapper.toDirectoryGroup(scim, ID, USER_ONLY).block())
                .isInstanceOf(ScimException.class)
                .hasMessage("members 원소에 value 가 없습니다");
    }

    @Test
    @DisplayName("도메인 유저를 SCIM 으로 변환할 때 id 와 userName 이 구분된다")
    void 유저_roundtrip_id_userName_구분() {
        // given
        var user = new DirectoryUser("kim", "emp-1001", "kim.lee", "김철수", "kim@example.com", true);

        // when
        ScimUser scim = ScimMapper.toScimUser(user);

        // then
        assertThat(scim.id()).isEqualTo("kim");
        assertThat(scim.userName()).isEqualTo("kim.lee");
        assertThat(scim.externalId()).isEqualTo("emp-1001");
    }

    @Test
    @DisplayName("name 여섯 칸을 보낸 그대로 담고 그대로 돌려준다")
    void 이름을_그대로_담고_돌려준다() {
        // given
        ScimUser scim = new ScimUser(List.of(ScimSchemas.USER), null, "e1", "hong",
                new ScimName("홍길동", "홍", "길동", "철", "Mr.", "Jr."), null, null, true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim, ID);
        ScimUser 응답 = ScimMapper.toScimUser(user);

        // then
        assertThat(user.name()).isEqualTo(new PersonName("홍길동", "홍", "길동", "철", "Mr.", "Jr."));
        assertThat(user.displayName()).isEqualTo("홍길동");
        assertThat(응답.name()).isEqualTo(new ScimName("홍길동", "홍", "길동", "철", "Mr.", "Jr."));
    }

    @Test
    @DisplayName("이름이 없으면 응답에 name 을 넣지 않는다 — formatted 를 지어내지 않는다")
    void 이름이_없으면_name_이_없다() {
        // when
        ScimUser 응답 = ScimMapper.toScimUser(new DirectoryUser("kim", null, "kim", "김철수", null, true));

        // then
        assertThat(응답.name()).isNull();
        assertThat(응답.displayName()).isEqualTo("김철수");
    }
}
