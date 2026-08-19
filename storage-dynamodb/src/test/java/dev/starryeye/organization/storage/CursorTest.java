package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    private static final String SCOPE = Keys.GSI1 + "/" + Keys.USER_INDEX;
    private static final String OTHER_SCOPE = Keys.GSI2 + "/" + Keys.USER_INDEX;

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
        Map<String, AttributeValue> restored = Cursor.decode(SCOPE, Cursor.encode(SCOPE, key));

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
        assertThat(Cursor.decode(SCOPE, Cursor.encode(SCOPE, key))).isEqualTo(key);
    }

    @Test
    @DisplayName("null 키는 null 커서가 된다 — 마지막 페이지")
    void null_키는_null_커서다() {
        // when, then
        assertThat(Cursor.encode(SCOPE, null)).isNull();
        assertThat(Cursor.encode(SCOPE, Map.of())).isNull();
    }

    @Test
    @DisplayName("다른 검색이 발급한 커서는 저장소까지 내려가기 전에 거절된다")
    void 다른_검색의_커서는_거절한다() {
        // given — GSI1 검색이 발급한, 형식은 멀쩡한 커서
        String gsi1Cursor = Cursor.encode(SCOPE, Map.of(
                Keys.PK, Attrs.s("USER#gd.hong"),
                Keys.SK, Attrs.s("META"),
                Keys.GSI1PK, Attrs.s("USER_INDEX"),
                Keys.GSI1SK, Attrs.s("gd.hong")));

        // when, then — 그대로 통과시키면 DynamoDB 가 exclusiveStartKey 를 거절해 500 이 된다.
        // 설계 §9 는 손상된 커서를 400 으로 규정한다.
        assertThatThrownBy(() -> Cursor.decode(OTHER_SCOPE, gsi1Cursor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("손상된 커서는 IllegalArgumentException 이다")
    void 손상된_커서는_거절한다() {
        // when, then — 호출부가 이걸 400 으로 옮긴다
        assertThatThrownBy(() -> Cursor.decode(SCOPE, "!!not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
