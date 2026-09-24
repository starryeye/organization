package dev.starryeye.organization.ldap.strategy;

import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * DN 을 표준 파서({@link LdapName})로 다룬다. 문자열로 이어 붙이거나 자르지 않는다.
 *
 * <p>실제 디렉터리의 DN 은 속성 이름 대소문자({@code CN=} vs {@code cn=}), 쉼표 주변 공백,
 * 이스케이프({@code CN=Hong\, Gildong}), 다중값 RDN({@code CN=hgd+OU=Seoul}) 으로 흔들린다.
 * 문자열 다듬기로는 뒤의 둘을 다룰 수 없다 — 이스케이프된 쉼표를 RDN 경계로 잘못 자르면
 * 그 사람은 어느 그룹의 멤버로도 대조되지 않는다.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-09-24-real-dn-matching-design.md} §5.
 */
final class LdapDns {

    private LdapDns() {
    }

    /**
     * 서버가 준 베이스 상대 DN 앞에 베이스를 붙여 절대 DN 으로 만든다.
     * 그룹의 {@code member} 값은 절대 DN 이므로 대조하려면 이쪽을 맞춰야 한다.
     */
    static String 절대로(String 상대DN, String baseDn) {
        LdapName 절대 = 파싱한다(baseDn);
        try {
            // 두 인자 모두 이미 파싱한다() 를 통과한 LdapName 이라 addAll 은 실제로는
            // 던지지 않는다 — InvalidNameException 은 이 메서드의 체크 예외 시그니처가
            // 강제하는 것일 뿐이다. 그래도 예외를 지우지 않고 감싸는 것은, 다루는 값이
            // 문자열이 아니라 파싱된 DN 이라는 전제가 훗날 깨졌을 때 조용히 넘어가지 않기
            // 위해서다.
            절대.addAll(파싱한다(상대DN));
        } catch (InvalidNameException e) {
            throw new IllegalStateException(
                    "DN 을 합치지 못했습니다: '" + 상대DN + "' 아래 '" + baseDn + "'", e);
        }
        return 절대.toString();
    }

    /**
     * 절대 DN 에서 베이스를 떼어 낸다. 범위 검색 재요청은 {@code ContextSource} 의 베이스에
     * 상대적인 DN 을 받으므로 절대 DN 을 그대로 넘기면 엔트리를 찾지 못한다.
     */
    static String 상대로(String 절대DN, String baseDn) {
        LdapName 절대 = 파싱한다(절대DN);
        LdapName 베이스 = 파싱한다(baseDn);
        if (!절대.startsWith(베이스)) {
            throw new IllegalStateException(
                    "DN '" + 절대DN + "' 이 베이스 '" + baseDn + "' 아래에 있지 않습니다");
        }
        return 절대.getSuffix(베이스.size()).toString();
    }

    /**
     * 대조용 키. 같은 엔트리를 가리키는 두 DN 은 표기가 달라도 같은 키가 된다.
     * 값은 소문자로 맞춘다 — LDAP 의 이름 속성은 대개 대소문자를 가리지 않는다.
     */
    static String 대조키(String dn) {
        LdapName name = 파싱한다(dn);
        List<String> rdn들 = new ArrayList<>();
        // LdapName 은 0 이 뿌리 쪽이다. 사람이 읽는 순서로 되돌려 키를 만든다
        for (int i = name.size() - 1; i >= 0; i--) {
            rdn들.add(정규화한다(name.getRdn(i)));
        }
        return String.join(",", rdn들);
    }

    /** 다중값 RDN 은 적힌 순서가 달라도 같은 엔트리다 — 정렬해 한 모양으로 만든다. */
    private static String 정규화한다(Rdn rdn) {
        List<String> 쌍들 = new ArrayList<>();
        try {
            NamingEnumeration<? extends Attribute> 속성들 = rdn.toAttributes().getAll();
            while (속성들.hasMore()) {
                Attribute 속성 = 속성들.next();
                for (int i = 0; i < 속성.size(); i++) {
                    쌍들.add(속성.getID().toLowerCase(Locale.ROOT) + "="
                            + Rdn.escapeValue(속성.get(i)).toLowerCase(Locale.ROOT));
                }
            }
        } catch (NamingException e) {
            throw new IllegalStateException("RDN '" + rdn + "' 을 읽지 못했습니다", e);
        }
        Collections.sort(쌍들);
        return String.join("+", 쌍들);
    }

    private static LdapName 파싱한다(String dn) {
        try {
            return new LdapName(dn == null ? "" : dn);
        } catch (InvalidNameException e) {
            throw new IllegalStateException("DN 을 해석하지 못했습니다: '" + dn + "'."
                    + " 대조하는 DN 은 모두 서버가 준 값이라 문법은 올바를 것입니다 —"
                    + " 우리가 DN 을 다루는 방식이 틀렸을 수 있습니다", e);
        }
    }
}
