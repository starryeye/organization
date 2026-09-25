package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.PersonName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimAttributeProjectionTest {

    private static ObjectNode 김철수() {
        return ScimJson.tree(ScimMapper.toScimUser(
                new DirectoryUser("kim", "emp-1", "kim", "김철수", "kim@example.com", true)));
    }

    private static List<String> 필드(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ScimAttributeProjection 선택(List<String> attributes, List<String> excluded) {
        return ScimAttributeProjection.of(ScimResourceType.USER, attributes, excluded);
    }

    @Test
    @DisplayName("attributes 는 고른 속성만 남기되 id 와 schemas 는 항상 남긴다")
    void attributes_는_고른_것만_남긴다() {
        // when
        ObjectNode node = 선택(List.of("userName"), List.of()).apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "userName");
    }

    @Test
    @DisplayName("하위 속성을 고르면 그 복합 속성 안에서도 그것만 남긴다")
    void 하위_속성만_남긴다() {
        // when
        ObjectNode node = 선택(List.of("emails.value"), List.of()).apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "emails");
        assertThat(필드((ObjectNode) node.get("emails").get(0))).containsExactly("value");
    }

    @Test
    @DisplayName("excludedAttributes 는 뺀 속성만 지우고 id 는 빼지 못한다")
    void excludedAttributes_는_뺀_것만_지운다() {
        // when
        ObjectNode node = 선택(List.of(), List.of("emails", "meta", "id")).apply(김철수());

        // then
        assertThat(필드(node)).contains("id", "userName", "displayName").doesNotContain("emails", "meta");
    }

    @Test
    @DisplayName("하위 속성을 빼면 그 하위 속성만 지운다")
    void 하위_속성을_뺀다() {
        // when
        ObjectNode node = 선택(List.of(), List.of("emails.type")).apply(김철수());

        // then
        assertThat(필드((ObjectNode) node.get("emails").get(0))).contains("value").doesNotContain("type");
    }

    @Test
    @DisplayName("name 의 하위 속성도 골라 남기거나 뺄 수 있다")
    void name_하위_속성도_고르거나_뺀다() {
        // given
        ObjectNode 홍길동 = ScimJson.tree(ScimMapper.toScimUser(new DirectoryUser("hong", null, "hong", "홍길동", null,
                true, new PersonName("홍길동", "홍", "길동", "철", "Mr.", "Jr."))));

        // when
        ObjectNode 골라남김 = 선택(List.of("name.middleName"), List.of()).apply(홍길동.deepCopy());
        ObjectNode 빼기 = 선택(List.of(), List.of("name.honorificSuffix")).apply(홍길동.deepCopy());

        // then
        assertThat(필드(골라남김)).containsExactlyInAnyOrder("schemas", "id", "name");
        assertThat(필드((ObjectNode) 골라남김.get("name"))).containsExactly("middleName");
        assertThat(필드((ObjectNode) 빼기.get("name"))).contains("familyName", "givenName", "middleName", "honorificPrefix")
                .doesNotContain("honorificSuffix");
    }

    @Test
    @DisplayName("속성 이름은 대소문자를 가리지 않고 URN 이 붙어도 된다")
    void 이름은_대소문자와_URN_을_가리지_않는다() {
        // when
        ObjectNode node = 선택(List.of("urn:ietf:params:scim:schemas:core:2.0:User:USERNAME"), List.of())
                .apply(김철수());

        // then
        assertThat(필드(node)).containsExactlyInAnyOrder("schemas", "id", "userName");
    }

    @Test
    @DisplayName("조직의 members 가 응답에 남는지 알려 준다 — 대소문자가 달라도 같다")
    void members_가_남는지_알려_준다() {
        // when, then
        assertThat(ScimAttributeProjection.all().includes("members")).isTrue();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of(), List.of("MEMBERS"))
                .includes("members")).isFalse();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of("displayName"), List.of())
                .includes("members")).isFalse();
        assertThat(ScimAttributeProjection.of(ScimResourceType.GROUP, List.of("members.value"), List.of())
                .includes("members")).isTrue();
    }

    @Test
    @DisplayName("둘을 함께 주거나 모르는 속성 이름을 주면 invalidValue 다")
    void 함께_주거나_모르는_이름이면_거절한다() {
        assertThatThrownBy(() -> 선택(List.of("userName"), List.of("emails")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
        assertThatThrownBy(() -> 선택(List.of("nickName"), List.of()))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
        assertThatThrownBy(() -> 선택(List.of(), List.of("members")))
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("URL 의 쉼표 목록을 나눈다")
    void 쉼표_목록을_나눈다() {
        assertThat(ScimAttributeProjection.split("userName, emails.value,,")).containsExactly("userName", "emails.value");
        assertThat(ScimAttributeProjection.split(null)).isEmpty();
    }
}
