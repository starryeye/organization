package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.scim.ScimSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScimSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("SCIM User 는 IdP 가 보내는 본문 형태 그대로 역직렬화된다")
    void 유저_요청_본문을_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "externalId": "emp-1001",
                  "userName": "kim",
                  "name": {"formatted": "김철수"},
                  "displayName": "김철수",
                  "emails": [{"value": "kim@example.com", "primary": true}],
                  "active": true
                }
                """;

        // when
        ScimUser user = mapper.readValue(body, ScimUser.class);

        // then
        assertThat(user.userName()).isEqualTo("kim");
        assertThat(user.externalId()).isEqualTo("emp-1001");
        assertThat(user.displayName()).isEqualTo("김철수");
        assertThat(user.emails()).hasSize(1);
        assertThat(user.emails().get(0).value()).isEqualTo("kim@example.com");
        assertThat(user.emails().get(0).primary()).isTrue();
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 SCIM 속성이 있어도 역직렬화가 실패하지 않는다")
    void 모르는_속성은_무시한다() throws Exception {
        // given — IdP 마다 보내는 확장 속성이 다르다
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "userName": "kim",
                  "nickName": "철수",
                  "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {"employeeNumber": "1001"}
                }
                """;

        // when
        ScimUser user = mapper.readValue(body, ScimUser.class);

        // then
        assertThat(user.userName()).isEqualTo("kim");
    }

    @Test
    @DisplayName("SCIM Group 의 members 는 type 으로 유저와 하위 조직을 구분한다")
    void 그룹_멤버를_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                  "externalId": "DEV001",
                  "displayName": "개발본부",
                  "members": [
                    {"value": "DEV002", "type": "Group"},
                    {"value": "park", "type": "User"}
                  ]
                }
                """;

        // when
        ScimGroup group = mapper.readValue(body, ScimGroup.class);

        // then
        assertThat(group.externalId()).isEqualTo("DEV001");
        assertThat(group.displayName()).isEqualTo("개발본부");
        assertThat(group.members()).extracting(ScimMember::type)
                .containsExactly("Group", "User");
    }

    @Test
    @DisplayName("PATCH 요청의 Operations 는 SCIM 스펙대로 대문자 O 로 온다")
    void 패치_요청을_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                  "Operations": [
                    {"op": "add", "path": "members", "value": [{"value": "kim", "type": "User"}]}
                  ]
                }
                """;

        // when
        ScimPatchOp patch = mapper.readValue(body, ScimPatchOp.class);

        // then
        assertThat(patch.operations()).hasSize(1);
        assertThat(patch.operations().get(0).op()).isEqualTo("add");
        assertThat(patch.operations().get(0).path()).isEqualTo("members");
    }

    @Test
    @DisplayName("에러 응답은 SCIM Error 스키마로 직렬화되고 null 필드는 빠진다")
    void 에러_응답을_직렬화한다() throws Exception {
        // given
        ScimError error = new ScimError(List.of(ScimSchemas.ERROR), "404", null, "Group not found: DEV999");

        // when
        String json = mapper.writeValueAsString(error);

        // then
        assertThat(json).contains("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(json).contains("\"status\":\"404\"");
        assertThat(json).contains("Group not found: DEV999");
        assertThat(json).doesNotContain("scimType");
    }
}
