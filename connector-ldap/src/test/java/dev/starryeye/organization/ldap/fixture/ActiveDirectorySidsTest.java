package dev.starryeye.organization.ldap.fixture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SID 를 만드는 도우미가 MS-DTYP §2.4.2.2 의 바이트 배치를 지키는지. 이 도우미로 만든 SID 를 읽는 쪽(기본 그룹 RID)의 테스트가 이 도우미를
 * 믿고 서 있으므로, 기대값은 도우미를 거치지 않은 16진 리터럴로 적는다.
 */
class ActiveDirectorySidsTest {

    @Test
    @DisplayName("S-1-5-32-544 는 개정 1 · 하위 권한 2개 · 식별 권한 5 · 하위 권한 32, 544(little-endian) 순으로 만든다")
    void 일반_SID_를_만든다() {
        // given, when
        byte[] sid = ActiveDirectorySids.of(5, 32, 544);

        // then
        assertThat(sid).isEqualTo(HexFormat.of().parseHex("0102000000000005" + "20000000" + "20020000"));
    }

    @Test
    @DisplayName("도메인 SID S-1-5-21-1-2-3 에 RID 를 붙인다 — 부호 없는 32비트 RID 도 담는다")
    void 도메인_RID_로_만든다() {
        // given, when
        byte[] 일반 = ActiveDirectorySids.도메인_RID(1105);
        byte[] 큰값 = ActiveDirectorySids.도메인_RID(4294967295L);

        // then
        assertThat(일반).isEqualTo(HexFormat.of().parseHex(
                "0105000000000005" + "15000000" + "01000000" + "02000000" + "03000000" + "51040000"));
        assertThat(큰값).endsWith(HexFormat.of().parseHex("FFFFFFFF"));
    }

    @Test
    @DisplayName("하위 권한이 부호 없는 32비트를 넘으면 조용히 잘라 담지 않고 깨뜨린다")
    void 범위를_넘는_하위_권한은_깨뜨린다() {
        // given, when, then
        assertThatThrownBy(() -> ActiveDirectorySids.of(5, 4294967296L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActiveDirectorySids.of(5, -1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
