package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.ldap.LdapTemplates;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.PartialResultException;
import org.springframework.ldap.core.ContextMapperCallbackHandler;
import javax.naming.directory.SearchControls;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.query.SearchScope;
import org.springframework.ldap.support.LdapEncoder;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 두 전략이 같이 쓰는 한 사람 읽기(설계 2026-11-28 V3·§5.2). 전략이 베이스·필터·요청 속성·매퍼를 넘기고, 여기서는 식별 속성을 AND 로 더해
 * 읽은 뒤 고르고 판정한다. 매퍼는 전략의 것을 그대로 쓴다 — 정기 읽기와 판정이 갈리지 않게 하려는 것이다.
 */
final class SingleAccountRead {

    private SingleAccountRead() {
    }

    /**
     * 매퍼가 돌려준 엔트리 하나. 건너뛸 엔트리(한도 넘음·식별 속성 없음)는 {@code id} 가 null 이다.
     *
     * @param id     정규화한 직원 id. 건너뛸 엔트리면 null
     * @param active 디렉터리가 막지 않았으면 true
     */
    record Candidate(String id, boolean active) {

        static Candidate 건너뜀() {
            return new Candidate(null, false);
        }
    }

    /**
     * @param 검색베이스      직원 검색 베이스(컨텍스트 베이스에 상대적) — groupOfNames 는 user-search-base, DIT 는 root-dn
     * @param 절대_검색베이스  위의 절대 DN. DN 다시 읽기는 externalId 가 이 아래일 때만 한다
     * @param baseDn         컨텍스트 베이스
     * @param 사용자_필터     전략의 직원 필터 그대로
     * @param 식별_속성       직원 식별 속성 이름
     * @param 요청_속성       정기 읽기가 요청하는 속성
     * @param 매퍼           전략의 직원 매퍼를 {@link Candidate} 로 바꾼 것
     * @param externalId     현재상태의 DN. 없으면 null — DN 다시 읽기를 하지 않는다. DN 으로 읽을 수 없는 값도 "베이스 아래가 아님" 으로 둔다
     *                       (정기 읽기가 그런 값을 만들지 않았다)
     */
    static AccountState 읽는다(LdapTemplate template, String 검색베이스, String 절대_검색베이스, String baseDn,
                            String 사용자_필터, String 식별_속성, String[] 요청_속성,
                            ContextMapper<Candidate> 매퍼, String employeeId, String externalId) {
        List<Candidate> 고른것 = List.of();
        Optional<String> 값_필터 = 값_필터(식별_속성, employeeId);
        if (값_필터.isPresent()) {
            LdapQuery 쿼리 = LdapQueryBuilder.query()
                    .base(검색베이스)
                    .attributes(요청_속성)
                    .filter(AND(사용자_필터, 값_필터.get()));
            // 검색 베이스가 없으면(NameNotFoundException) 설정 오류다 — 삼키지 않고 올린다
            // 참조(referral)는 따라가지 않는다 — 정기 읽기가 건너뛰는 부분과 같다. 엔트리가 0개여도 그대로 DN 다시 읽기로 간다
            고른것 = 고른다(검색한다(template, 쿼리, 매퍼).엔트리, employeeId);
        }
        if (고른것.isEmpty() && 아래인가(externalId, 절대_검색베이스)) {
            LdapQuery 쿼리 = LdapQueryBuilder.query()
                    .base(LdapDns.상대로(externalId, baseDn))
                    .searchScope(SearchScope.OBJECT)
                    .attributes(요청_속성)
                    .filter(사용자_필터);
            try {
                검색_결과 결과 = 검색한다(template, 쿼리, 매퍼);
                if (결과.참조를_만났다 && 결과.엔트리.isEmpty()) {
                    // 그 엔트리는 다른 서버에 있다 — 없음으로 읽으면 활성인 사람을 거둔다. 값(id·DN)은 싣지 않는다
                    throw new DirectoryDataException("직원 DN 을 다시 읽는 검색이 엔트리 없이 referral 만 받았다 — 그 엔트리는 다른 서버에 있어 "
                            + "없음으로 판정하지 않는다. 직원 검색 베이스가 이 DC 의 이름 공간 안인지 확인하라");
                }
                고른것 = 고른다(결과.엔트리, employeeId);
            } catch (NameNotFoundException e) {
                // 그 DN 의 엔트리가 없다(결과 32) — 엔트리를 지운 퇴사자가 늘 이 길을 탄다. 고른 것이 없다
                고른것 = List.of();
            }
        }
        if (고른것.isEmpty()) {
            return AccountState.ABSENT;
        }
        return 고른것.stream().anyMatch(Candidate::active) ? AccountState.ACTIVE : AccountState.BLOCKED;
    }

    private record 검색_결과(List<Candidate> 엔트리, boolean 참조를_만났다) {
    }

    /**
     * 한 사람 읽기의 검색 — 페이징 없이 커넥션 하나에서 한 번 묻는다(한 사람이라 한 페이지다). {@link PagedLdapSearch#search} 를 쓰지 않는 까닭: 그쪽은
     * 필터·DN 을 로그·예외 문구에 싣는데 여기서는 그 값이 직원 id·DN 이고, 엔트리 0개 + referral 을 오류로 보는데 한 사람 검색에서 0건은 정상이다.
     * referral({@code PartialResultException})은 따라가지 않고 그 전까지 받은 엔트리를 남긴다. 로그는 남기지 않는다.
     */
    private static 검색_결과 검색한다(LdapTemplate template, LdapQuery query, ContextMapper<Candidate> 매퍼) {
        String base = query.base().toString();
        String filter = query.filter().encode();
        SearchControls controls = PagedLdapSearch.controlsOf(query);
        ContextMapperCallbackHandler<Candidate> handler = new ContextMapperCallbackHandler<>(매퍼);
        boolean 참조 = LdapTemplates.한_커넥션에서(template, 한커넥션 -> {
            try {
                한커넥션.search(base, filter, controls, handler);
                return false;
            } catch (PartialResultException e) {
                return true;
            }
        });
        return new 검색_결과(handler.getList(), 참조);
    }

    /** 정규화한 id 가 요청 id 와 정확히 같은 엔트리만. 건너뛸 엔트리는 뺀다 */
    private static List<Candidate> 고른다(List<Candidate> 읽은것, String employeeId) {
        return 읽은것.stream().filter(c -> c.id() != null && c.id().equals(employeeId)).toList();
    }

    private static boolean 아래인가(String externalId, String 절대_검색베이스) {
        if (externalId == null) {
            return false;
        }
        try {
            return LdapDns.아래인가(externalId, 절대_검색베이스);
        } catch (DirectoryDataException e) {
            return false;
        }
    }

    /**
     * {@code (<속성>=<값>)}. {@code objectGUID} 면 GUID 문자열을 바이트로 되돌려 바이트마다 {@code \xx}(소문자)로 쓰고, GUID 모양이 아니면
     * 빈 값이다(검색하지 않는다). 그 밖은 RFC 4515 로 이스케이프한다.
     */
    static Optional<String> 값_필터(String attribute, String employeeId) {
        if (LdapIdentifiers.이진인가(attribute)) {
            return LdapIdentifiers.guidBytes(employeeId).map(바이트 -> {
                StringBuilder 값 = new StringBuilder();
                for (byte b : 바이트) {
                    값.append('\\').append(HexFormat.of().toHexDigits(b));
                }
                return "(" + attribute + "=" + 값 + ")";
            });
        }
        return Optional.of("(" + attribute + "=" + LdapEncoder.filterEncode(employeeId) + ")");
    }

    /**
     * {@code (&<사용자 필터>(<식별>=<값>))}. 정기 읽기는 필터를 그대로 넘겨 괄호 없는 필터도 JNDI 가 받아 주지만 AND 로 이으려면 괄호가
     * 있어야 하므로 괄호로 시작하지 않으면 감싼다(계획 P8).
     */
    private static String AND(String 사용자_필터, String 값_필터) {
        String 필터 = 사용자_필터.strip();
        if (!필터.startsWith("(")) {
            필터 = "(" + 필터 + ")";
        }
        return "(&" + 필터 + 값_필터 + ")";
    }
}
