package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /Groups} 의 조회 실행 (S-1 설계 §4.2~4.5).
 *
 * <p>조직은 이름표(META)로 찾고 자른다. <b>{@code members} 가 응답에 남을 때만</b> 페이지에 든 조직의
 * 멤버 줄을 읽는다 — Entra 는 조직을 조회할 때마다 {@code excludedAttributes=members} 를 붙인다.
 */
public class ScimGroupListing {

    private static final Set<String> ATTRIBUTES = Set.of("id", "externalid", "displayname");
    private static final List<String> INDEXED = List.of("id", "displayname", "externalid");

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;
    private final ScimGroupStream stream;

    public ScimGroupListing(DirectoryStateRepository state, DirectoryQueryRepository query, PageBookmarkRepository bookmarks) {
        this.state = state;
        this.query = query;
        this.bookmarks = bookmarks;
        this.stream = new ScimGroupStream(state);
    }

    /** 이름표만 싣는 목록을 한 번에 만든다. 멤버를 싣는 목록은 {@link #streamed} 다. */
    public Mono<ScimListResponse> list(ScimQuery request) {
        return slice(request).map(page -> ScimListResponse.of(request, page.totalResults(), page.items().stream()
                .map(header -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(header))))
                .toList()));
    }

    /** 멤버를 싣는 목록 — 헤더만 자르고 조직은 흘려 쓴다(설계 2026-10-06 §4.3). */
    Mono<Flux<DataBuffer>> streamed(ScimQuery request) {
        return slice(request).map(page -> stream.list(request, page.totalResults(), page.items()));
    }

    /** 조회가 가리키는 쪽의 조직 이름표와 전체 수. 멤버는 읽지 않는다. */
    Mono<ScimPager.Slice<GroupHeader>> slice(ScimQuery request) {
        return request.filter() == null
                ? ScimPager.unfiltered(ListingKind.GROUP, request, bookmarks, query::countGroups,
                        n -> query.skipGroups(n, request.descending()),
                        (from, limit) -> query.listGroupHeaders(from, limit, request.descending()))
                : filtered(request);
    }

    private Mono<ScimPager.Slice<GroupHeader>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimGroupListing::check);
        ScimFilter.Term driver = request.filter().first(INDEXED).orElseThrow(() ->
                ScimException.invalidFilter("id·displayName·externalId 중 하나의 eq 가 있어야 합니다"));
        return candidates(driver)
                .filter(group -> terms.stream().allMatch(term -> matches(group, term)))
                .collectList()
                .map(groups -> ScimPager.filtered(sort(groups, request.descending()), request));
    }

    private static void check(ScimFilter.Term term) {
        if (!ATTRIBUTES.contains(term.attribute())) {
            throw ScimException.invalidFilter("필터할 수 없는 속성입니다: " + term.attribute());
        }
        if (!(term.value() instanceof String)) {
            throw ScimException.invalidFilter("속성 '" + term.attribute() + "' 에 맞지 않는 값입니다: " + term.value());
        }
    }

    private Flux<GroupHeader> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> state.findGroupHeader(value).flux();
            case "displayname" -> query.findGroupHeadersByDisplayName(value);
            default -> query.findGroupHeadersByExternalId(value);
        };
    }

    private static boolean matches(GroupHeader group, ScimFilter.Term term) {
        return switch (term.attribute()) {
            case "id" -> term.value().equals(group.id());
            case "externalid" -> term.value().equals(group.externalId());
            case "displayname" -> ScimText.sameIgnoringCase(group.displayName(), (String) term.value());
            default -> false;
        };
    }

    private static List<GroupHeader> sort(List<GroupHeader> groups, boolean descending) {
        Comparator<GroupHeader> order = Comparator
                .comparing((GroupHeader group) -> ScimText.lower(group.displayName() == null ? group.id() : group.displayName()))
                .thenComparing(GroupHeader::id);
        return groups.stream().sorted(descending ? order.reversed() : order).toList();
    }
}
