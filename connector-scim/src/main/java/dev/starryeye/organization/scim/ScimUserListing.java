package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryUser;
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
import java.util.Map;

/**
 * {@code GET /Users} 의 조회 실행 (S-1 설계 §4.2~4.4).
 *
 * <p>필터가 있으면 인덱스로 찾을 조건 하나({@code id} → {@code userName} → {@code externalId})로 후보를 찾고,
 * {@code and} 로 붙은 나머지 조건은 후보 위에서 확인한다. 전원을 훑는 필터는 받지 않는다.
 */
@RequiredArgsConstructor
public class ScimUserListing {

    /** 필터에 쓸 수 있는 속성과 값의 타입. 이름은 소문자. */
    private static final Map<String, Class<?>> ATTRIBUTES = Map.of(
            "id", String.class,
            "externalid", String.class,
            "username", String.class,
            "displayname", String.class,
            "active", Boolean.class);

    /** 인덱스로 찾을 수 있는 속성, 먼저 고르는 순서대로. */
    private static final List<String> INDEXED = List.of("id", "username", "externalid");

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;
    private final PageBookmarkRepository bookmarks;

    public Mono<ScimListResponse> list(ScimQuery request) {
        Mono<ScimPager.Slice<DirectoryUser>> slice = request.filter() == null
                ? ScimPager.unfiltered(ListingKind.USER, request, bookmarks, query::countUsers,
                        n -> query.skipUsers(n, request.descending()),
                        (from, limit) -> query.listUsers(from, limit, request.descending()))
                : filtered(request);
        return slice.map(page -> ScimListResponse.of(request, page.totalResults(), page.items().stream()
                .map(user -> request.projection().apply(ScimJson.tree(ScimMapper.toScimUser(user))))
                .toList()));
    }

    private Mono<ScimPager.Slice<DirectoryUser>> filtered(ScimQuery request) {
        List<ScimFilter.Term> terms = request.filter().terms();
        terms.forEach(ScimUserListing::check);
        ScimFilter.Term driver = driver(terms);
        return candidates(driver)
                .filter(user -> terms.stream().allMatch(term -> matches(user, term)))
                .collectList()
                .map(users -> ScimPager.filtered(sort(users, request.descending()), request));
    }

    private static void check(ScimFilter.Term term) {
        Class<?> type = ATTRIBUTES.get(term.attribute());
        if (type == null) {
            throw ScimException.invalidFilter("필터할 수 없는 속성입니다: " + term.attribute());
        }
        if (!type.isInstance(term.value())) {
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
        throw ScimException.invalidFilter(
                "id·userName·externalId 중 하나의 eq 가 있어야 합니다 — 전원을 훑는 필터는 받지 않습니다");
    }

    private Flux<DirectoryUser> candidates(ScimFilter.Term driver) {
        String value = (String) driver.value();
        return switch (driver.attribute()) {
            case "id" -> state.findUser(value).flux();
            case "username" -> query.findUsersByUserName(value);
            default -> query.findUsersByExternalId(value);
        };
    }

    private static boolean matches(DirectoryUser user, ScimFilter.Term term) {
        return switch (term.attribute()) {
            case "id" -> term.value().equals(user.id());
            case "externalid" -> term.value().equals(user.externalId());
            case "username" -> ScimText.sameIgnoringCase(user.userName(), (String) term.value());
            case "displayname" -> ScimText.sameIgnoringCase(user.displayName(), (String) term.value());
            case "active" -> term.value().equals(user.active());
            default -> false;
        };
    }

    /** 인덱스와 같은 순서 — userName 소문자, 같으면 id. */
    private static List<DirectoryUser> sort(List<DirectoryUser> users, boolean descending) {
        Comparator<DirectoryUser> order = Comparator
                .comparing((DirectoryUser user) -> ScimText.lower(user.userName() == null ? user.id() : user.userName()))
                .thenComparing(DirectoryUser::id);
        return users.stream().sorted(descending ? order.reversed() : order).toList();
    }
}
