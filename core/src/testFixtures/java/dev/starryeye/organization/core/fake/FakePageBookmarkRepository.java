package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** 만료가 없는 책갈피. 저장된 것을 {@link #saved} 로 들여다본다. */
public class FakePageBookmarkRepository implements PageBookmarkRepository {

    public final Map<String, PageBookmark> saved = new LinkedHashMap<>();

    public static String key(ListingKind kind, boolean descending, long startIndex) {
        return kind + (descending ? ":DESC:" : ":ASC:") + startIndex;
    }

    @Override
    public Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex) {
        return Mono.justOrEmpty(saved.get(key(kind, descending, startIndex)));
    }

    @Override
    public Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark) {
        saved.put(key(kind, descending, startIndex), bookmark);
        return Mono.empty();
    }
}
