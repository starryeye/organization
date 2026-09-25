package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code GET /Groups} 의 조회 실행 (S-1 설계 §4.2~4.5).
 *
 * <p>조직은 이름표(META)로 찾고 자른다. <b>{@code members} 가 응답에 남을 때만</b> 페이지에 든 조직의
 * 파티션을 읽는다 — Entra 는 조직을 조회할 때마다 {@code excludedAttributes=members} 를 붙인다.
 */
@RequiredArgsConstructor
public class ScimGroupListing {

    private static final Set<String> ATTRIBUTES = Set.of("id", "externalid", "displayname");
    private static final List<String> INDEXED = List.of("id", "displayname", "externalid");
    /** 페이지(최대 100개)의 조직 파티션을 읽는 동시성. 저장소의 다른 읽기와 같은 값이다. */
    private static final int MEMBER_READ_CONCURRENCY = 8;

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;

    public Mono<ScimListResponse> list(ScimQuery request) {
        Mono<ScimPager.Slice<GroupHeader>> slice = request.filter() == null
                ? ScimPager.unfiltered(ListingKind.GROUP, request, bookmarks, query::countGroups,
                        n -> query.skipGroups(n, request.descending()),
                        (from, limit) -> query.listGroupHeaders(from, limit, request.descending()))
                : filtered(request);
        return slice.flatMap(page -> resources(page.items(), request)
                .map(resources -> ScimListResponse.of(request, page.totalResults(), resources)));
    }

    private Mono<List<ObjectNode>> resources(List<GroupHeader> headers, ScimQuery request) {
        if (!request.projection().includes("members")) {
            return Mono.just(headers.stream()
                    .map(header -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(header))))
                    .toList());
        }
        // 페이지 순서를 지킨다. 그사이 지워진 조직은 빈 결과라 빠진다(itemsPerPage 가 실제 수를 말한다)
        return Flux.fromIterable(headers)
                .flatMapSequential(header -> state.findGroup(header.id()), MEMBER_READ_CONCURRENCY)
                .map(group -> request.projection().apply(ScimJson.tree(ScimMapper.toScimGroup(group))))
                .collectList();
    }

    private Mono<ScimPager.Slice<GroupHeader>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimGroupListing::check);
        ScimFilter.Term driver = driver(terms);
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

    private static ScimFilter.Term driver(List<ScimFilter.Term> terms) {
        for (String attribute : INDEXED) {
            for (ScimFilter.Term term : terms) {
                if (term.attribute().equals(attribute)) {
                    return term;
                }
            }
        }
        throw ScimException.invalidFilter("id·displayName·externalId 중 하나의 eq 가 있어야 합니다");
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
