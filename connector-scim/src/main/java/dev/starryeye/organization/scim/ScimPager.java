package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.PageBookmark;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * 페이지 자르기 (S-1 설계 §4.4). 필터 없는 목록은 책갈피로 이어 읽고, 필터 결과는 메모리에서 자른다.
 */
@Slf4j
final class ScimPager {

    record Slice<T>(List<T> items, long totalResults) {
    }

    private ScimPager() {
    }

    /**
     * <pre>
     * startIndex=1           → 세기 + 처음부터 count 건 → 책갈피(1+받은 수)
     * startIndex=N, 책갈피 있음 → 책갈피 자리부터 count 건, 전체 수는 책갈피 값 → 책갈피(N+받은 수)
     * startIndex=N, 책갈피 없음 → 세기 + N-1 건 건너뛰기 + count 건 → 책갈피
     * </pre>
     * 다음 위치가 없으면(끝) 책갈피를 남기지 않는다.
     */
    static <T> Mono<Slice<T>> unfiltered(ListingKind kind, ScimQuery query, PageBookmarkRepository bookmarks,
                                         Supplier<Mono<Long>> count,
                                         LongFunction<Mono<String>> skip,
                                         BiFunction<String, Integer, Mono<Page<T>>> list) {
        if (query.count() == 0) {
            return count.get().map(total -> new Slice<>(List.of(), total));
        }
        long start = query.startIndex();
        boolean descending = query.descending();
        Mono<PageBookmark> bookmark = start == 1 ? Mono.empty() : bookmarks.find(kind, descending, start)
                .onErrorResume(error -> {
                    log.warn("책갈피 조회 실패 — 건너뛰기로 대신한다 (kind={}, startIndex={})", kind, start, error);
                    return Mono.empty();
                });
        Mono<Tuple2<Optional<String>, Long>> origin = bookmark
                .map(found -> Tuples.of(Optional.of(found.position()), found.totalResults()))
                .switchIfEmpty(Mono.defer(() -> Mono.zip(
                        skip.apply(start - 1).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        count.get())));
        return origin.flatMap(from -> {
            long total = from.getT2();
            return list.apply(from.getT1().orElse(null), query.count())
                    .flatMap(page -> {
                        Slice<T> slice = new Slice<>(page.items(), total);
                        if (!page.hasNext()) {
                            return Mono.just(slice);
                        }
                        long next = start + page.items().size();
                        return bookmarks.save(kind, descending, next, new PageBookmark(page.nextCursor(), total))
                                .onErrorResume(error -> {
                                    log.warn("책갈피 저장 실패 — 이번 페이지는 그대로 돌려주고, 다음 페이지는 건너뛰기로 떨어진다"
                                                    + " (kind={}, startIndex={})", kind, next, error);
                                    return Mono.empty();
                                })
                                .thenReturn(slice);
                    });
        });
    }

    /** 이미 정렬된 필터 결과(몇 건)를 자른다. */
    static <T> Slice<T> filtered(List<T> sorted, ScimQuery query) {
        long total = sorted.size();
        if (query.count() == 0 || query.startIndex() > total) {
            return new Slice<>(List.of(), total);
        }
        int from = (int) (query.startIndex() - 1);
        int to = (int) Math.min(total, from + (long) query.count());
        return new Slice<>(sorted.subList(from, to), total);
    }
}
