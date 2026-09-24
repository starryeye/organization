package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD 가 계정을 막았다고 알리는 표준 신호 둘을 읽는다 (스펙 §3).
 *
 * <p>{@code accountExpires} 는 1601-01-01 UTC 부터 100나노초 단위로 센 정수다. 아래 상수는
 * 고정한 "지금"(2026-01-01T00:00:00Z) 앞뒤의 값이다.
 */
class AdAccountStatusTest {

    private static final Instant 지금 = Instant.parse("2026-01-01T00:00:00Z");
    /** 오류 메시지에 실려야 하는 엔트리 — 운영자가 로그만 보고 어느 계정인지 찾게 한다. */
    private static final String DN = "uid=kim,ou=people,dc=example,dc=com";
    /** 2026-01-01T00:00:00Z = (1767225600 + 11644473600) 초 × 10^7 */
    private static final String 지금의_FILETIME = "134116992000000000";
    private static final String 일초_전 = "134116991990000000";
    private static final String 일초_후 = "134116992010000000";

    @Test
    @DisplayName("두 속성이 모두 없으면 막히지 않은 것이다 — OpenLDAP 과 지금의 테스트 서버")
    void 속성이_없으면_막히지_않았다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들(), 지금)).isFalse();
    }

    @Test
    @DisplayName("보통 계정(512)은 막히지 않았다")
    void 보통_계정() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("userAccountControl", "512"), 지금)).isFalse();
    }

    @Test
    @DisplayName("비활성화 비트가 켜진 계정(514)은 막혔다")
    void 비활성화된_계정() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("userAccountControl", "514"), 지금)).isTrue();
    }

    @Test
    @DisplayName("다른 플래그와 더해진 값도 비트로 읽는다 — 66050 은 비활성 + 암호 만료 없음")
    void 플래그가_더해져도_비트로_읽는다() {
        // given — 514 와 같은지 비교하면 이 계정을 놓친다
        // when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("userAccountControl", "66050"), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료일이 지났으면 막혔다")
    void 만료일이_지났다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("accountExpires", 일초_전), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료 시각이 정확히 지금이면 막혔다 — 그 시각에 만료된다")
    void 만료_시각이_지금이다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("accountExpires", 지금의_FILETIME), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료일이 아직 오지 않았으면 막히지 않았다")
    void 만료일이_아직이다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("accountExpires", 일초_후), 지금)).isFalse();
    }

    @Test
    @DisplayName("만료일 0 과 최댓값은 '만료 없음' 이다 — 막힘으로 읽으면 전원이 권한을 잃는다")
    void 만료_없음_두_값() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("accountExpires", "0"), 지금)).isFalse();
        assertThat(AdAccountStatus.막혔는가(DN, 속성들("accountExpires", "9223372036854775807"), 지금)).isFalse();
    }

    @Test
    @DisplayName("둘 중 하나만 막혀도 막혔다")
    void 둘_중_하나만_막혀도_막혔다() {
        // given — 비활성화는 아니지만 만료됐다
        var 만료만 = 속성들("userAccountControl", "512", "accountExpires", 일초_전);
        // 비활성화됐지만 만료 없음
        var 비활성화만 = 속성들("userAccountControl", "514", "accountExpires", "0");

        // when, then
        assertThat(AdAccountStatus.막혔는가(DN, 만료만, 지금)).isTrue();
        assertThat(AdAccountStatus.막혔는가(DN, 비활성화만, 지금)).isTrue();
    }

    @Test
    @DisplayName("정수가 아닌 값은 짐작하지 않고 실패한다 — 표준 밖이다")
    void 정수가_아니면_실패한다() {
        // given, when, then
        assertThatThrownBy(() -> AdAccountStatus.막혔는가(DN, 속성들("userAccountControl", "abc"), 지금))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userAccountControl")
                .hasMessageContaining("abc")
                .as("5,000명 디렉터리에서 어느 엔트리인지 로그만으로 찾을 수 있어야 한다")
                .hasMessageContaining(DN);
        assertThatThrownBy(() -> AdAccountStatus.막혔는가(DN, 속성들("accountExpires", "내일"), 지금))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accountExpires");
    }

    @Test
    @DisplayName("앞뒤에 공백이 섞인 값도 정수가 아니다 — 표준 밖의 값을 다듬어 받아 주지 않는다")
    void 공백이_섞이면_정수가_아니다() {
        // given, when, then
        assertThatThrownBy(() -> AdAccountStatus.막혔는가(DN, 속성들("userAccountControl", " 514"), 지금))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userAccountControl");
    }

    /** 서버가 주는 것처럼 대소문자를 가리지 않는 속성 집합을 만든다. */
    private static Attributes 속성들(String... 이름과_값) {
        Attributes attributes = new BasicAttributes(true);
        for (int i = 0; i < 이름과_값.length; i += 2) {
            attributes.put(이름과_값[i], 이름과_값[i + 1]);
        }
        return attributes;
    }
}
