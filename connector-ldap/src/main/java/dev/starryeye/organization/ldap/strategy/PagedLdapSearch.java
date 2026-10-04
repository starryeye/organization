package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.ldap.LdapTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.PartialResultException;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.ContextMapperCallbackHandler;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;

import javax.naming.directory.SearchControls;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code LdapTemplate}에는 {@code LdapQuery} 기반 검색에 paged results control(RFC 2696)을
 * 걸 수 있는 오버로드가 없다. 그래서 {@code LdapQuery}에서 base/filter/scope/attributes 를 뽑아
 * {@code SearchControls} 기반 오버로드로 넘기고, {@link PagedResultsDirContextProcessor}로
 * 쿠키를 이어가며 서버가 "더 있음"을 알리는 동안 반복한다.
 *
 * <p>디렉터리가 {@code ldap.page-size}(기본 500)보다 많은 엔트리를 갖고 있을 때, 서버가
 * 한 페이지만 반환하고 침묵하는(예: Active Directory의 {@code MaxPageSize}=1000) 상황을 막는다.
 * 이 처리가 없으면 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 삭제해 버릴 수 있다.
 *
 * <p><b>페이징 전체가 커넥션 하나 안에서 돈다({@link dev.starryeye.organization.ldap.LdapTemplates#한_커넥션에서}).</b> paged results
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
 *
 * <p><b>referral(검색 결과 참조, RFC 4511 §4.5.3)은 따라가지 않되 검색마다 경고 한 줄을 남긴다(점검 S21).</b> JNDI 는 참조를 만나면
 * 열거 끝에 {@code PartialResultException} 을 던지고, Spring 은 기본으로 그것을 DEBUG 로 삼킨다({@link LdapTemplates#configured} 가 끈다).
 * 그래서 결과를 {@code ContextMapperCallbackHandler} 하나에 직접 모은다 — 예외가 나도 그 전까지 받은 엔트리는 핸들러에 담겨 있고, 페이징
 * 쿠키는 Spring 이 {@code finally} 에서 읽으므로 다음 페이지도 이어진다.
 */
@Slf4j
final class PagedLdapSearch {

    private PagedLdapSearch() {
    }

    // 이 전략(GroupOfNames·DIT)의 매퍼는 전부 ContextMapper 다 — DN 이 필요하기 때문이다
    // (AttributesMapper 에는 DN 이 오지 않는다). 이전에는 AttributesMapper 오버로드도
    // 있었지만 마지막 호출자가 사라져 지웠다; 되살릴 때는 DN 없는 매퍼를 다시 불러들이는
    // 문이 된다는 점을 염두에 둔다.
    static <T> List<T> search(LdapTemplate template, LdapQuery query, int pageSize, ContextMapper<T> mapper) {
        String base = query.base().toString();
        String filter = query.filter().encode();
        SearchControls controls = controlsOf(query);
        ContextMapperCallbackHandler<T> handler = new ContextMapperCallbackHandler<>(mapper);
        검색의_참조 참조 = new 검색의_참조(base, filter);
        if (pageSize <= 0) {
            참조.검색하되_참조는_경고한다(() -> template.search(base, filter, controls, handler));
            return handler.getList();
        }
        return LdapTemplates.한_커넥션에서(template, paged -> {
            PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(pageSize);
            boolean hasMore;
            do {
                PagedResultsDirContextProcessor 이번 = processor;
                참조.검색하되_참조는_경고한다(() -> paged.search(base, filter, controls, handler, 이번));
                hasMore = processor.hasMore();
                if (hasMore) {
                    processor = new PagedResultsDirContextProcessor(pageSize, processor.getCookie());
                }
            } while (hasMore);
            return handler.getList();
        });
    }

    /**
     * 검색 하나에서 만난 referral. 따라가지 않는다(설계 2026-10-05 §4.2) — 다른 DC 의 주소·자격 증명·DNS 가 필요하고 AD 도메인 루트에서는
     * DNS 파티션까지 읽는다. 대신 검색마다 경고 한 줄을 남긴다(페이지마다 참조가 와도 한 줄). 실패로 만들지 않는다 — AD 에서 도메인 루트를
     * 검색 베이스로 쓰면 참조가 늘 온다.
     */
    private static final class 검색의_참조 {
        private final String base;
        private final String filter;
        private boolean 알렸다;

        검색의_참조(String base, String filter) {
            this.base = base;
            this.filter = filter;
        }

        void 검색하되_참조는_경고한다(Runnable 검색) {
            try {
                검색.run();
            } catch (PartialResultException e) {
                // Spring 이 JNDI 의 javax.naming.PartialResultException 을 옮긴 예외다(ignorePartialResultException=false 일 때) — javax.naming 쪽이 아니다
                if (!알렸다) {
                    알렸다 = true;
                    log.warn("LDAP 검색이 referral 을 만나 그 부분을 읽지 않았다 — 따라가지 않는다. 검색 범위가 다른 도메인·위임 서브트리를 걸치는지 확인하라: base={}, filter={}, {}",
                            base, filter, e.getMessage());
                }
            }
        }
    }

    /**
     * 검색에 달 속성 이름 목록을 만든다. 설정 값에서 만들므로 비어 있거나(null·공백) 겹칠 수 있다 — 빈 이름은 빼고,
     * 대소문자만 다른 이름은 처음 것만 남긴다(LDAP 속성 이름은 대소문자를 가리지 않는다). 순서는 처음 나온 대로다.
     */
    static String[] 속성목록(String... 이름들) {
        Map<String, String> 처음나온것 = new LinkedHashMap<>();
        for (String 이름 : 이름들) {
            if (이름 != null && !이름.isBlank()) {
                처음나온것.putIfAbsent(이름.toLowerCase(Locale.ROOT), 이름);
            }
        }
        return 처음나온것.values().toArray(String[]::new);
    }

    private static SearchControls controlsOf(LdapQuery query) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(query.searchScope() == null
                ? SearchControls.SUBTREE_SCOPE
                : query.searchScope().getId());
        // 쓰는 속성만 요청한다 — 비워 두면 서버가 모든 사용자 속성을 준다(AD 10만 명이면 회차마다 1~2GB, 설계 2026-10-04 §4.3).
        // 운영 속성(entryUUID)은 이름을 대야만 온다
        controls.setReturningAttributes(query.attributes());
        // ContextMapper 오버로드는 Spring 이 이것을 켜 주지만 핸들러 오버로드는 켜지 않는다 — 없으면 매퍼가 DirContextAdapter 를 못 받는다
        controls.setReturningObjFlag(true);
        return controls;
    }
}
