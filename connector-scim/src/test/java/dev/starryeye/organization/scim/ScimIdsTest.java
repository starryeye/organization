package dev.starryeye.organization.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScimIdsTest {

    @Test
    @DisplayName("RFC 9562 의 시험 값을 낸다 — DNS 네임스페이스와 www.example.com")
    void RFC_시험_값() {
        // when
        UUID actual = ScimIds.nameBased(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com");

        // then
        assertThat(actual).isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    }

    @Test
    @DisplayName("켜면 같은 externalId 에서 늘 같은 id 가 나온다 — 네임스페이스가 바뀌면 이 값이 깨진다")
    void 고정_값() {
        // given
        var ids = ScimIds.fromExternalId();

        // when
        String user = ids.user("00u1abcd");

        // then
        assertThat(user).isEqualTo("46af28dd-ee3f-5eed-9506-e28af7d370de").isEqualTo(ids.user("00u1abcd"));
        assertThat(ids.group("00u1abcd")).isEqualTo("2481ef44-a4ea-5cfe-95f6-9d2420431168");
        assertThat(ids.user("00U1ABCD")).isEqualTo("8ea0c031-99b6-59cd-976c-78d72b50f428");   // 대소문자를 가린다
        assertThat(ids.user("한글-외부id")).isEqualTo("cb554ee8-d5a6-57fa-89db-4a3c9f9c80a1"); // UTF-8
        UUID 결정적 = UUID.fromString(user);
        assertThat(결정적.version()).isEqualTo(5);
        assertThat(결정적.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("켜도 externalId 가 없거나 비었거나 공백뿐이면 무작위다")
    void 켜도_없으면_무작위() {
        // given
        var ids = ScimIds.fromExternalId();

        // when / then
        for (String 없음 : new String[]{null, "", "  "}) {
            assertThat(UUID.fromString(ids.user(없음)).version()).isEqualTo(4);
            assertThat(ids.user(없음)).isNotEqualTo(ids.user(없음));
            assertThat(UUID.fromString(ids.group(없음)).version()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("끄면 externalId 가 있어도 무작위다")
    void 끄면_무작위() {
        // given
        var ids = ScimIds.random();

        // when / then
        assertThat(UUID.fromString(ids.user("00u1abcd")).version()).isEqualTo(4);
        assertThat(ids.user("00u1abcd")).isNotEqualTo(ids.user("00u1abcd"));
        assertThat(UUID.fromString(ids.group("00u1abcd")).version()).isEqualTo(4);
    }
}
