package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimMapperTest {

    @Test
    @DisplayName("직원 아이디는 userName 에서 오고 표시명은 displayName 을 우선한다")
    void 유저를_도메인으로_변환한다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, "emp-1001", "kim",
                new ScimName("김철수", null, null), "철수",
                List.of(new ScimEmail("kim@example.com", "work", true)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.id()).isEqualTo("kim");
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
                new ScimName("김철수", null, null), null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

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
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

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
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.email()).isEqualTo("a@example.com");
    }

    @Test
    @DisplayName("조직코드는 externalId 에서 오고 조직명은 displayName 에서 온다")
    void 그룹을_도메인으로_변환한다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("DEV002", "Group", null),
                        new ScimMember("park", "User", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("DEV001");
        assertThat(group.displayName()).isEqualTo("개발본부");
        assertThat(group.members())
                .containsExactlyInAnyOrder(MemberRef.group("DEV002"), MemberRef.user("park"));
    }

    @Test
    @DisplayName("externalId 가 없으면 id 를 조직코드로 쓴다")
    void externalId가_없으면_id를_쓴다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), "DEV009", null, "운영팀",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("DEV009");
    }

    @Test
    @DisplayName("조직코드가 아예 없으면 UUID 를 발급한다")
    void 조직코드가_없으면_UUID를_발급한다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, null, "임시팀", List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isNotBlank().hasSize(36);
        assertThat(group.displayName()).isEqualTo("임시팀");
    }

    @Test
    @DisplayName("한글 조직코드는 정규화를 거쳐도 보존된다")
    void 한글_조직코드가_보존된다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "개발본부", "개발본부",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("개발본부");
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
}
