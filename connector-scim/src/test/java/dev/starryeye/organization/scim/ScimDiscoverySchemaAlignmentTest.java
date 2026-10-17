package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스키마가 알리는 속성이 응답 DTO 가 내보내는 속성과 같은지 맞대 본다(설계 2026-10-17 §5). 한쪽에만 속성을 더하면
 * 이 시험이 실패한다 — 돌려주지 않는 속성을 알리거나, 돌려주는 속성을 빠뜨리는 어긋남을 막는다.
 */
class ScimDiscoverySchemaAlignmentTest {

    /** 스키마에 넣지 않는 공통 속성 — RFC 7643 의 예처럼 스키마에 두지 않는다 */
    private static final Set<String> 공통_속성 = Set.of("schemas", "id", "externalId", "meta");

    @Test
    @DisplayName("직원 스키마는 응답 DTO 와 같은 속성이다 — name·emails 의 하위 속성도 같다")
    void 직원_스키마는_응답_DTO_와_같은_속성이다() {
        // given
        JsonNode schema = ScimDiscovery.userSchema();

        // when
        Set<String> 스키마_속성 = 속성_이름(schema.get("attributes"));

        // then
        assertThat(스키마_속성).isEqualTo(칸(ScimUser.class, 공통_속성));
        assertThat(하위_속성_이름(schema, "name")).isEqualTo(칸(ScimName.class, Set.of()));
        assertThat(하위_속성_이름(schema, "emails")).isEqualTo(칸(ScimEmail.class, Set.of()));
    }

    @Test
    @DisplayName("조직 스키마는 응답 DTO 와 같은 속성이다 — members 의 하위 속성은 display 만 빠진다")
    void 조직_스키마는_응답_DTO_와_같은_속성이다() {
        // given
        JsonNode schema = ScimDiscovery.groupSchema();

        // when
        Set<String> 스키마_속성 = 속성_이름(schema.get("attributes"));

        // then
        assertThat(스키마_속성).isEqualTo(칸(ScimGroup.class, 공통_속성));
        // display 는 매퍼가 늘 null 로 두어 @JsonInclude(NON_NULL) 때문에 응답에 나가지 않는다 — 돌려주지 않는 속성은 알리지 않는다
        assertThat(하위_속성_이름(schema, "members")).isEqualTo(칸(ScimMember.class, Set.of("display")));
    }

    private static Set<String> 칸(Class<? extends Record> dto, Set<String> 뺄것) {
        return Arrays.stream(dto.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(이름 -> !뺄것.contains(이름))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> 하위_속성_이름(JsonNode schema, String 속성) {
        return StreamSupport.stream(schema.get("attributes").spliterator(), false)
                .filter(attribute -> 속성.equals(attribute.get("name").asText()))
                .findFirst()
                .map(attribute -> 속성_이름(attribute.get("subAttributes")))
                .orElseThrow(() -> new AssertionError("스키마에 속성이 없다: " + 속성));
    }

    private static Set<String> 속성_이름(JsonNode attributes) {
        return StreamSupport.stream(attributes.spliterator(), false)
                .map(attribute -> attribute.get("name").asText())
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
