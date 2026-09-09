package dev.starryeye.organization.ldap.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapOperations;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Active Directory 의 <b>범위 검색</b>(range retrieval)으로 잘려 온 다중값 속성을 이어 읽는다.
 *
 * <p>AD 는 다중값 속성 하나에서 한 번에 돌려주는 값의 개수를 {@code MaxValRange} 정책으로
 * 제한한다(버전·정책에 따라 1,000 / 1,500 / 5,000). 그 수를 넘으면 <b>속성 이름 자체를
 * 바꿔서</b> 돌려준다 — MS-ADTS 명세는 이렇게 규정한다:
 *
 * <blockquote>
 * 요청에 범위 옵션이 없는데 그 속성의 값이 한 번에 돌려주기에 너무 많으면, 서버는
 * <b>(1) 범위 옵션 없는 속성을 값 없이</b> 그리고 <b>(2) 범위 옵션이 붙은 속성을 그 범위의
 * 값과 함께</b> 담아 돌려준다.
 * </blockquote>
 *
 * <pre>
 * member                 →  (값 없음)
 * member;range=0-1499    →  1,500개
 * </pre>
 *
 * <p>그래서 {@code member} 만 이름으로 찾으면 <b>빈 속성</b>을 읽고 끝난다. 6,000명짜리
 * 그룹이 <b>멤버 0명</b>으로 읽히고, 그 6,000개의 튜플이 삭제 대상이 된다. 서버는 정상
 * 응답했고 우리는 정상적으로 읽었다고 믿으므로 <b>어디에도 오류가 남지 않는다.</b>
 *
 * <p>이어받으려면 {@code member;range=<다음>-*} 로 재요청한다. 응답의 상한이 숫자가 아니라
 * {@code *} 로 오면 그것이 마지막 조각이다.
 *
 * <p><b>이어받기는 커넥션 하나 안에서 끝내야 한다.</b> 명세가 값의 순서를 이렇게 규정한다 —
 * "임의지만 <b>같은 LDAP 커넥션에서는</b> 일관된다". 조각을 서로 다른 커넥션에서 받으면
 * 순서가 달라져 어떤 값은 두 번 오고 어떤 값은 아예 안 온다. 페이징 쿠키와 같은 제약이고,
 * 같은 이유로 {@link LdapTemplates#한_커넥션에서} 를 쓴다.
 *
 * <p>범위 옵션을 붙여 보내지 않는 서버(OpenLDAP, 임베디드 UnboundID)에서는 평범한 속성 하나가
 * 그대로 오므로 이 클래스는 값을 그대로 돌려주고 끝난다 — 동작이 바뀌지 않는다.
 */
@Slf4j
final class RangedAttributeReader {

    /** {@code member;range=0-1499} 또는 {@code member;range=1500-*}. */
    private static final Pattern RANGE = Pattern.compile(
            "^(?<name>[^;]+);range=(?<low>\\d+)-(?<high>\\d+|\\*)$", Pattern.CASE_INSENSITIVE);

    /**
     * 값이 안 늘어나는데 완료 표시도 없는 응답이 오면 여기서 멈춘다. 서버가 예상 밖으로
     * 동작할 때 무한 루프로 도는 것보다, 읽은 만큼으로 끝내고 경고를 남기는 편이 낫다.
     */
    private static final int 최대조각수 = 1_000;

    private RangedAttributeReader() {
    }

    /**
     * @param values 이번 조각의 값들
     * @param 완료   더 받을 것이 없는가. 범위 옵션이 아예 없었거나 상한이 {@code *} 면 참
     */
    record Chunk(List<String> values, boolean 완료) {

        Chunk {
            values = List.copyOf(values);
        }

        static Chunk 완결(List<String> values) {
            return new Chunk(values, true);
        }
    }

    /**
     * 응답에서 그 속성을 읽는다. 범위 옵션이 붙은 쪽이 있으면 <b>그쪽을 쓴다</b> — 범위가
     * 걸린 응답에는 원래 이름의 속성도 함께 오지만 값이 비어 있기 때문이다.
     */
    static Chunk 읽는다(Attributes attributes, String 속성명) {
        if (attributes == null) {
            return Chunk.완결(List.of());
        }
        String 찾는이름 = 속성명.toLowerCase(Locale.ROOT);
        Attribute 평범한것 = null;

        NamingEnumeration<? extends Attribute> 전부 = attributes.getAll();
        try {
            while (전부.hasMore()) {
                Attribute attribute = 전부.next();
                String id = attribute.getID();
                if (id.toLowerCase(Locale.ROOT).equals(찾는이름)) {
                    평범한것 = attribute;
                    continue;
                }
                Matcher matcher = RANGE.matcher(id);
                if (matcher.matches()
                        && matcher.group("name").toLowerCase(Locale.ROOT).equals(찾는이름)) {
                    return new Chunk(값들(attribute), "*".equals(matcher.group("high")));
                }
            }
        } catch (Exception e) {
            log.warn("속성 '{}' 을 읽지 못했습니다", 속성명, e);
            return Chunk.완결(List.of());
        }
        return Chunk.완결(평범한것 == null ? List.of() : 값들(평범한것));
    }

    /**
     * 완료될 때까지 이어받는다. <b>범위 0 부터 다시 읽는다</b> — 처음 조각은 검색을 돌린 다른
     * 커넥션에서 왔고, 순서 일관성은 커넥션 단위이기 때문이다. 섞어 쓰면 누락과 중복이 난다.
     *
     * @param 한커넥션 {@link LdapTemplates#한_커넥션에서} 안에서 받은 템플릿이어야 한다
     */
    static List<String> 전부_읽는다(LdapOperations 한커넥션, String dn, String 속성명) {
        List<String> 모은것 = new ArrayList<>();
        int 다음 = 0;
        for (int 조각 = 0; 조각 < 최대조각수; 조각++) {
            Chunk chunk = 한조각(한커넥션, dn, 속성명, 다음);
            모은것.addAll(chunk.values());
            if (chunk.완료()) {
                return 모은것;
            }
            if (chunk.values().isEmpty()) {
                log.warn("범위 검색이 값을 더 주지 않는데 완료 표시도 없습니다. 읽은 만큼으로 끝냅니다: "
                        + "dn={}, 속성={}, 지금까지 {}개", dn, 속성명, 모은것.size());
                return 모은것;
            }
            다음 += chunk.values().size();
        }
        log.warn("범위 검색 조각이 {}개를 넘었습니다. 읽은 만큼으로 끝냅니다: dn={}, 속성={}, {}개",
                최대조각수, dn, 속성명, 모은것.size());
        return 모은것;
    }

    private static Chunk 한조각(LdapOperations 한커넥션, String dn, String 속성명, int 시작) {
        String 요청이름 = 속성명 + ";range=" + 시작 + "-*";
        return 한커넥션.lookup(dn, new String[]{요청이름},
                (org.springframework.ldap.core.ContextMapper<Chunk>) context ->
                        읽는다(((DirContextAdapter) context).getAttributes(), 속성명));
    }

    private static List<String> 값들(Attribute attribute) {
        List<String> result = new ArrayList<>();
        try {
            NamingEnumeration<?> 전부 = attribute.getAll();
            while (전부.hasMore()) {
                result.add((String) 전부.next());
            }
        } catch (Exception e) {
            log.warn("속성 '{}' 의 값을 읽지 못했습니다", attribute.getID(), e);
        }
        return result;
    }
}
