package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 키에 들어갈 값의 쓰기 한도(설계 2026-10-17 §3) — 한도 바이트는 통과하고 한 바이트 넘으면 그 속성을 가리킨다. */
class KeyValueLimitsTest {

    private static final int 한도 = 1024;

    private static DirectoryUser 직원(String userName, String displayName, String externalId) {
        return new DirectoryUser("u-1", externalId, userName, displayName, null, true);
    }

    private static GroupHeader 조직(String displayName, String externalId) {
        return new GroupHeader("g-1", externalId, displayName);
    }

    @Test
    @DisplayName("한도 바이트까지는 넘지 않는다")
    void 한도_바이트까지는_넘지_않는다() {
        // given
        String 한도값 = "a".repeat(한도);

        // when
        var 직원_결과 = KeyValueLimits.over(직원(한도값, 한도값, 한도값));
        var 조직_결과 = KeyValueLimits.over(조직(한도값, 한도값));
        var 멤버_결과 = KeyValueLimits.overMembers(Set.of(MemberRef.user("u".repeat(500))));

        // then
        assertThat(직원_결과).isEmpty();
        assertThat(조직_결과).isEmpty();
        assertThat(멤버_결과).isEmpty();
    }

    @Test
    @DisplayName("한 바이트 넘으면 그 속성을 준다")
    void 한_바이트_넘으면_그_속성을_준다() {
        // given
        String 넘침 = "a".repeat(한도 + 1);

        // when, then
        assertThat(KeyValueLimits.over(직원("kim", 넘침, "emp-1")))
                .contains(new KeyValueLimits.Over("displayName", 한도));
        assertThat(KeyValueLimits.over(직원("kim", "김", 넘침)))
                .contains(new KeyValueLimits.Over("externalId", 한도));
        assertThat(KeyValueLimits.over(조직(넘침, "ext-1")))
                .contains(new KeyValueLimits.Over("displayName", 한도));
        assertThat(KeyValueLimits.over(조직("개발", 넘침)))
                .contains(new KeyValueLimits.Over("externalId", 한도));
        assertThat(KeyValueLimits.overMembers(Set.of(MemberRef.user("u".repeat(501)))))
                .contains(new KeyValueLimits.Over("members", 500));
    }

    @Test
    @DisplayName("여럿이 넘으면 userName, displayName, externalId 순으로 먼저 넘는 하나를 준다")
    void 여럿이_넘으면_순서대로_하나를_준다() {
        // given
        String 넘침 = "a".repeat(한도 + 1);

        // when, then
        assertThat(KeyValueLimits.over(직원(넘침, 넘침, 넘침))).contains(new KeyValueLimits.Over("userName", 한도));
        assertThat(KeyValueLimits.over(직원("kim", 넘침, 넘침))).contains(new KeyValueLimits.Over("displayName", 한도));
        assertThat(KeyValueLimits.over(조직(넘침, 넘침))).contains(new KeyValueLimits.Over("displayName", 한도));
    }

    @Test
    @DisplayName("멤버 가운데 하나라도 아이디가 500바이트를 넘으면 members 를 준다")
    void 멤버_하나라도_넘으면_members_를_준다() {
        // when
        var 결과 = KeyValueLimits.overMembers(Set.of(
                MemberRef.user("kim"), MemberRef.group("g".repeat(501))));

        // then
        assertThat(결과).contains(new KeyValueLimits.Over("members", 500));
    }

    @Test
    @DisplayName("소문자로 길어지는 값도 잰다")
    void 소문자로_길어지는_값도_잰다() {
        // given — U+0130 은 UTF-8 2바이트이고 Locale.ROOT 소문자는 3바이트(i + U+0307)다. 원문은 정확히 1024바이트다
        String 길어지는값 = "İ".repeat(512);

        // when, then
        assertThat(KeyValueLimits.over(직원(길어지는값, "김", "emp-1")))
                .contains(new KeyValueLimits.Over("userName", 한도));
        assertThat(KeyValueLimits.over(직원("kim", 길어지는값, "emp-1")))
                .contains(new KeyValueLimits.Over("displayName", 한도));
        assertThat(KeyValueLimits.over(조직(길어지는값, "ext-1")))
                .contains(new KeyValueLimits.Over("displayName", 한도));
    }

    @Test
    @DisplayName("externalId 는 소문자로 바꾸지 않고 그대로 잰다")
    void externalId_는_그대로_잰다() {
        // given — 소문자로 바꾸면 1536바이트가 되지만 externalId 는 값 그대로 키가 된다
        String 값 = "İ".repeat(512);

        // when, then
        assertThat(KeyValueLimits.over(직원("kim", "김", 값))).isEmpty();
        assertThat(KeyValueLimits.over(조직("개발", 값))).isEmpty();
    }

    @Test
    @DisplayName("공백뿐인 externalId 도 잰다")
    void 공백뿐인_externalId_도_잰다() {
        // given
        String 공백 = " ".repeat(한도 + 1);

        // when, then
        assertThat(KeyValueLimits.over(직원("kim", "김", 공백))).contains(new KeyValueLimits.Over("externalId", 한도));
        assertThat(KeyValueLimits.over(조직("개발", 공백))).contains(new KeyValueLimits.Over("externalId", 한도));
    }

    @Test
    @DisplayName("없는 값은 넘지 않는다")
    void 없는_값은_넘지_않는다() {
        // when, then
        assertThat(KeyValueLimits.over(직원("kim", null, null))).isEmpty();
        assertThat(KeyValueLimits.over(조직(null, null))).isEmpty();
        assertThat(KeyValueLimits.overMembers(Set.of())).isEmpty();
    }
}
