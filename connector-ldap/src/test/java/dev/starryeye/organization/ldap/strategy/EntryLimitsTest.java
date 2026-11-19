package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class EntryLimitsTest {

    private static final String 짧은_DN = "uid=x,dc=example,dc=com";

    @Test
    @DisplayName("id 가 한도와 같으면 넘지 않는다")
    void 한도와_같으면_넘지_않는다() {
        assertThat(EntryLimits.넘는_값("a".repeat(500), 짧은_DN, 500)).isEmpty();
    }

    @Test
    @DisplayName("id 가 한도를 하나 넘으면 id 문구다")
    void 한도를_하나_넘으면_id_문구다() {
        assertThat(EntryLimits.넘는_값("a".repeat(501), 짧은_DN, 500))
                .contains("dn='uid=x,dc=example,dc=com'(id 501바이트, 한도 500)");
    }

    @Test
    @DisplayName("글자 수가 아니라 UTF-8 바이트로 잰다")
    void 바이트로_잰다() {
        assertThat(EntryLimits.넘는_값("가".repeat(84), 짧은_DN, 250))
                .contains("dn='uid=x,dc=example,dc=com'(id 252바이트, 한도 250)");
    }

    @Test
    @DisplayName("DN 이 넘으면 앞 100 코드 포인트와 말줄임을 싣는다")
    void DN_이_넘으면_앞_100_코드_포인트와_말줄임() {
        String dn = "cn=" + "b".repeat(1100) + ",dc=example,dc=com";
        int 바이트 = dn.getBytes(StandardCharsets.UTF_8).length;

        assertThat(EntryLimits.넘는_값("short", dn, 500))
                .contains("dn='" + dn.substring(0, 100) + "…'(DN " + 바이트 + "바이트, 한도 1024)");
    }

    @Test
    @DisplayName("id 와 DN 이 모두 넘으면 id 를 적고, DN 은 앞 100 코드 포인트로 자른다")
    void 둘_다_넘으면_id_를_적는다() {
        String dn = "cn=" + "b".repeat(1100) + ",dc=example,dc=com";

        assertThat(EntryLimits.넘는_값("a".repeat(501), dn, 500)).hasValueSatisfying(문구 -> {
            assertThat(문구).startsWith("dn='" + dn.substring(0, 100) + "…'(id ");
            assertThat(문구).contains("(id 501바이트, 한도 500)");
        });
    }

    @Test
    @DisplayName("id 가 넘고 DN 이 1024 바이트 이하면 DN 을 통째로 싣는다")
    void id_가_넘고_DN_은_한도_이내면_통째로() {
        assertThat(EntryLimits.넘는_값("a".repeat(501), 짧은_DN, 500))
                .contains("dn='uid=x,dc=example,dc=com'(id 501바이트, 한도 500)");
    }

    @Test
    @DisplayName("절대 DN 이 정확히 1024 바이트면 통과하고 1025 바이트면 건너뛴다")
    void DN_경계() {
        String 앞 = "cn=";
        String 뒤 = ",dc=example,dc=com";
        String 천이십사 = 앞 + "b".repeat(1024 - 앞.length() - 뒤.length()) + 뒤;
        String 천이십오 = 앞 + "b".repeat(1025 - 앞.length() - 뒤.length()) + 뒤;

        assertThat(천이십사.getBytes(StandardCharsets.UTF_8)).hasSize(1024);
        assertThat(EntryLimits.넘는_값("short", 천이십사, 500)).isEmpty();
        assertThat(EntryLimits.넘는_값("short", 천이십오, 500))
                .hasValueSatisfying(문구 -> assertThat(문구).contains("(DN 1025바이트, 한도 1024)"));
    }

    @Test
    @DisplayName("긴 DN 의 예시는 코드 포인트로 잘라 홀로 남은 서로게이트가 없다")
    void 긴_DN_의_예시는_코드_포인트로_자른다() {
        String dn = "cn=" + "😀".repeat(300) + ",dc=example,dc=com";
        int[] 앞코드 = dn.codePoints().limit(EntryLimits.예시_DN_코드_포인트).toArray();
        String 앞 = new String(앞코드, 0, 앞코드.length);

        String 문구 = EntryLimits.넘는_값("short", dn, 500).orElseThrow();

        assertThat(문구).startsWith("dn='" + 앞 + "…'");
        assertThat(문구.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)).isTrue();
    }
}
