package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.naming.directory.BasicAttributes;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD 기본 그룹(점검 M10)의 두 값을 읽는 규칙. 기대값은 MS-DTYP §2.4.2.2 의 바이트 배치에서 직접 적은 16진 리터럴이다.
 */
class ActiveDirectoryPrimaryGroupTest {

    @Test
    @DisplayName("SID 의 마지막 하위 권한이 RID 다 — S-1-5-32-544(BUILTIN\\Administrators) 는 544")
    void SID_의_마지막_하위_권한이_RID_다() {
        // given — 개정 1, 하위 권한 2개, 식별 권한 5(NT), 하위 권한 32·544(little-endian)
        byte[] sid = HexFormat.of().parseHex("0102000000000005" + "20000000" + "20020000");

        // when, then
        assertThat(ActiveDirectoryPrimaryGroup.RID(sid, "cn=Administrators")).isEqualTo(544L);
    }

    @Test
    @DisplayName("RID 는 부호 없는 32비트다 — 0xFFFFFFFF 는 4294967295")
    void RID_는_부호_없다() {
        // given
        byte[] sid = HexFormat.of().parseHex("0101000000000005" + "FFFFFFFF");

        // when, then
        assertThat(ActiveDirectoryPrimaryGroup.RID(sid, "cn=x")).isEqualTo(4294967295L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "01020000000000",                                     // 7바이트 — 헤더(8바이트)보다 짧다
            "0201000000000005" + "20000000",                      // 개정이 2
            "0103000000000005" + "20000000" + "20020000"          // 하위 권한 수는 3 인데 둘만 있다
    })
    @DisplayName("SID 형식이 아니면 데이터 오류다 — 짧음, 개정이 1 이 아님, 하위 권한 수와 길이가 어긋남")
    void SID_형식이_아니면_데이터_오류다(String hex) {
        // given
        byte[] sid = HexFormat.of().parseHex(hex);

        // when, then
        assertThatThrownBy(() -> ActiveDirectoryPrimaryGroup.RID(sid, "cn=x"))
                .isInstanceOf(DirectoryDataException.class).hasMessageContaining("cn=x");
    }

    @Test
    @DisplayName("primaryGroupID 가 정수가 아니면 데이터 오류다, 없으면 null 이다")
    void primaryGroupID_를_읽는다() {
        // given
        var 정수아님 = new BasicAttributes(true);
        정수아님.put("primaryGroupID", "abc");

        // when, then
        assertThatThrownBy(() -> ActiveDirectoryPrimaryGroup.기본그룹_RID(정수아님, "uid=kim"))
                .isInstanceOf(DirectoryDataException.class).hasMessageContaining("uid=kim");
        assertThat(ActiveDirectoryPrimaryGroup.기본그룹_RID(new BasicAttributes(true), "uid=kim")).isNull();
    }
}
