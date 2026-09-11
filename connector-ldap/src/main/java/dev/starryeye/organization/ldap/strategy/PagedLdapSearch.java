package dev.starryeye.organization.ldap.strategy;

import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.LdapTemplate;
import dev.starryeye.organization.ldap.LdapTemplates;
import org.springframework.ldap.query.LdapQuery;

import javax.naming.directory.SearchControls;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@code LdapTemplate}에는 {@code LdapQuery} 기반 검색에 paged results control(RFC 2696)을
 * 걸 수 있는 오버로드가 없다. 그래서 {@code LdapQuery}에서 base/filter/scope 를 뽑아
 * {@code SearchControls} 기반 오버로드로 넘기고, {@link PagedResultsDirContextProcessor}로
 * 쿠키를 이어가며 서버가 "더 있음"을 알리는 동안 반복한다.
 *
 * <p>디렉터리가 {@code ldap.page-size}(기본 500)보다 많은 엔트리를 갖고 있을 때, 서버가
 * 한 페이지만 반환하고 침묵하는(예: Active Directory의 {@code MaxPageSize}=1000) 상황을 막는다.
 * 이 처리가 없으면 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 삭제해 버릴 수 있다.
 *
 * <p><b>페이징 전체가 커넥션 하나 안에서 돈다({@link LdapTemplates#한_커넥션에서}).</b> paged results
 * 쿠키는 <b>커넥션에 묶인 상태</b>다. {@code LdapTemplate} 은 검색 한 번마다 {@code DirContext}
 * 를 새로 얻었다 반납하므로, 그대로 두면 두 번째 페이지가 <b>다른 커넥션</b>에서 나가고 서버는
 * {@code "paged results cookie is invalid"} 로 거절한다. 임베디드 UnboundID 서버는 이것을
 * 봐주지만 실제 OpenLDAP 은 거절한다 — 한 페이지를 넘는 디렉터리에서 전체 동기화가 통째로
 * 실패하는데, 임베디드 서버로만 검증하면 그 사실이 드러나지 않는다.
 *
 * <p>{@code pageSize}가 0 이하면 페이징을 걸지 않고 단일 검색으로 처리한다.
 *
 * <p><b>{@code hasMore()}는 반드시 방금 검색을 수행한 processor 인스턴스에서 확인해야 한다.</b>
 * 응답의 쿠키는 {@code handleResponse()}로 그 인스턴스에 기록되기 때문이다. 새로 생성한
 * (아직 검색을 수행하지 않은) processor 는 생성자에 어떤 쿠키를 넘기든 {@code hasMore()}가
 * 항상 {@code true}를 반환하므로, 다음 페이지용 processor 를 먼저 만들고 그걸 검사하면
 * 무한 루프에 빠진다 — 매 반복 새 커넥션을 열다 로컬 포트가 고갈되어서야 멈춘다.
 */
final class PagedLdapSearch {

    private PagedLdapSearch() {
    }

    static <T> List<T> search(LdapTemplate template, LdapQuery query, int pageSize, AttributesMapper<T> mapper) {
        if (pageSize <= 0) {
            return template.search(query, mapper);
        }
        return 한_커넥션에서_페이징한다(template, query, pageSize,
                (paged) -> (base, filter, controls, processor) ->
                        paged.search(base, filter, controls, mapper, processor));
    }

    static <T> List<T> search(LdapTemplate template, LdapQuery query, int pageSize, ContextMapper<T> mapper) {
        if (pageSize <= 0) {
            return template.search(query, mapper);
        }
        return 한_커넥션에서_페이징한다(template, query, pageSize,
                (paged) -> (base, filter, controls, processor) ->
                        paged.search(base, filter, controls, mapper, processor));
    }

    /** 한 페이지를 읽어오는 호출. 매퍼 종류만 다르고 나머지 루프는 같다. */
    @FunctionalInterface
    private interface 페이지읽기<T> {
        List<T> read(String base, String filter, SearchControls controls,
                     PagedResultsDirContextProcessor processor);
    }

    private static <T> List<T> 한_커넥션에서_페이징한다(LdapTemplate template, LdapQuery query,
                                              int pageSize,
                                              Function<LdapTemplate, 페이지읽기<T>> 읽기를_만든다) {
        String base = query.base().toString();
        String filter = query.filter().encode();
        SearchControls controls = controlsOf(query);

        return LdapTemplates.한_커넥션에서(template, paged -> {
            페이지읽기<T> 읽는다 = 읽기를_만든다.apply(paged);

            List<T> results = new ArrayList<>();
            PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(pageSize);
            boolean hasMore;
            do {
                results.addAll(읽는다.read(base, filter, controls, processor));
                hasMore = processor.hasMore();
                if (hasMore) {
                    processor = new PagedResultsDirContextProcessor(pageSize, processor.getCookie());
                }
            } while (hasMore);
            return results;
        });
    }

    private static SearchControls controlsOf(LdapQuery query) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(query.searchScope() == null
                ? SearchControls.SUBTREE_SCOPE
                : query.searchScope().getId());
        return controls;
    }
}
