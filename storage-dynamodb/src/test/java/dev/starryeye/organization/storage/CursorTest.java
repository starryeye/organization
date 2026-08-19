package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    @Test
    @DisplayName("키를 감쌌다 풀면 원래 값이 그대로 나온다")
    void 왕복하면_원래_값이다() {
        // given
        Map<String, AttributeValue> key = Map.of(
                Keys.PK, Attrs.s("USER#gd.hong"),
                Keys.SK, Attrs.s("META"),
                Keys.GSI1PK, Attrs.s("USER_INDEX"),
                Keys.GSI1SK, Attrs.s("gd.hong"));

        // when
        Map<String, AttributeValue> restored = Cursor.decode(Cursor.encode(key));

        // then
        assertThat(restored).isEqualTo(key);
    }

    @Test
    @DisplayName("구분자나 한글이 값에 들어 있어도 왕복한다")
    void 특수문자와_한글도_왕복한다() {
        // given — 값에 구분자가 그대로 들어 있어도 base64 로 감싸므로 깨지지 않는다
        Map<String, AttributeValue> key = Map.of(
                Keys.PK, Attrs.s("USER#a|b:c"),
                Keys.GSI1SK, Attrs.s("홍길동"));

        // when, then
        assertThat(Cursor.decode(Cursor.encode(key))).isEqualTo(key);
    }

    @Test
    @DisplayName("null 키는 null 커서가 된다 — 마지막 페이지")
    void null_키는_null_커서다() {
        // when, then
        assertThat(Cursor.encode(null)).isNull();
        assertThat(Cursor.encode(Map.of())).isNull();
    }

    @Test
    @DisplayName("손상된 커서는 IllegalArgumentException 이다")
    void 손상된_커서는_거절한다() {
        // when, then — 호출부가 이걸 400 으로 옮긴다
        assertThatThrownBy(() -> Cursor.decode("!!not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
