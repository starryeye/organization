package dev.starryeye.organization.core.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageTest {

    @Test
    @DisplayName("nextCursor 가 null 이면 마지막 페이지다")
    void 커서가_null이면_마지막_페이지다() {
        // given
        Page<String> page = new Page<>(List.of("a", "b"), null);

        // when, then
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("nextCursor 가 있으면 다음 페이지가 있다")
    void 커서가_있으면_다음_페이지가_있다() {
        // given
        Page<String> page = new Page<>(List.of("a"), "eyJQSyI6...");

        // when, then
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("빈 페이지도 만들 수 있고 항목이 비어 있다")
    void 빈_페이지() {
        // given
        Page<String> page = Page.empty();

        // when, then
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("items 는 방어적으로 복사되어 밖에서 바꿔도 페이지가 안 바뀐다")
    void items는_방어적으로_복사된다() {
        // given
        List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(mutable, null);

        // when
        mutable.add("b");

        // then
        assertThat(page.items()).containsExactly("a");
    }
}
