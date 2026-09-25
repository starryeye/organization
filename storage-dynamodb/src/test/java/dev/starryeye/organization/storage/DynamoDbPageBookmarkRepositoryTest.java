package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbPageBookmarkRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-09-25T00:00:00Z");

    private DynamoDbPageBookmarkRepository 시각(Instant at) {
        return new DynamoDbPageBookmarkRepository(client, properties, Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("저장한 책갈피를 읽는다")
    void 저장한_책갈피를_읽는다() {
        // given
        var bookmarks = 시각(지금);
        bookmarks.save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when
        PageBookmark found = bookmarks.find(ListingKind.USER, false, 101).block();

        // then
        assertThat(found).isEqualTo(new PageBookmark("pos-101", 250));
    }

    @Test
    @DisplayName("종류·방향·위치가 다르면 다른 책갈피다")
    void 종류_방향_위치가_다르면_다르다() {
        // given
        var bookmarks = 시각(지금);
        bookmarks.save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when, then
        assertThat(bookmarks.find(ListingKind.GROUP, false, 101).blockOptional()).isEmpty();
        assertThat(bookmarks.find(ListingKind.USER, true, 101).blockOptional()).isEmpty();
        assertThat(bookmarks.find(ListingKind.USER, false, 201).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("15분이 지난 책갈피는 아직 지워지지 않았어도 없는 것이다")
    void 만료된_책갈피는_없는_것이다() {
        // given — TTL 삭제는 늦게 일어나므로 읽는 쪽이 만료를 판단해야 한다
        시각(지금).save(ListingKind.USER, false, 101, new PageBookmark("pos-101", 250)).block();

        // when, then
        assertThat(시각(지금.plus(Duration.ofMinutes(15)).minusSeconds(1))
                .find(ListingKind.USER, false, 101).blockOptional()).isPresent();
        assertThat(시각(지금.plus(Duration.ofMinutes(15)))
                .find(ListingKind.USER, false, 101).blockOptional()).isEmpty();
    }
}
