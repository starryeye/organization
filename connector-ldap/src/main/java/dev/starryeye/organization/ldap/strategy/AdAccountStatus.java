package dev.starryeye.organization.ldap.strategy;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.time.Duration;
import java.time.Instant;

/**
 * AD 가 이 계정을 <b>막았는가</b>. 표준(MS-ADTS)이 정한 신호 둘 중 하나라도 막혔다고 하면 막힌 것이다.
 *
 * <ul>
 *   <li>{@code userAccountControl} 의 {@code ACCOUNTDISABLE} 비트({@code 0x2}) — <b>비트로</b> 검사한다.
 *       AD 는 여러 플래그를 더한 값을 주므로({@code 66050} = 비활성 + 암호 만료 없음) 값 비교는 틀린다.</li>
 *   <li>{@code accountExpires} — 1601-01-01 UTC 부터 100나노초 단위. {@code 0} 과 {@link Long#MAX_VALUE} 는
 *       "만료 없음" 이다. 그 밖의 값이 {@code 지금} 이전(같은 시각 포함)이면 막혔다.</li>
 * </ul>
 *
 * <p><b>속성이 없으면 막히지 않은 것이다.</b> OpenLDAP 등 AD 가 아닌 디렉터리에는 두 속성이 없다.
 * <b>정수가 아니면 던진다</b> — 표준 밖의 값을 짐작해 읽으면, 막힌 퇴사자를 활성으로 두거나 멀쩡한 직원의
 * 권한을 지운다. 예외는 그 회차의 동기화를 실패시킨다.
 *
 * <p>막힌 계정은 SCIM 과 같은 의미의 비활성이다 — 멤버십은 남고 권한 튜플만 사라진다.
 * 설계: {@code docs/superpowers/specs/2026-09-25-ldap-disabled-account-design.md}.
 */
final class AdAccountStatus {

    static final String USER_ACCOUNT_CONTROL = "userAccountControl";
    static final String ACCOUNT_EXPIRES = "accountExpires";

    private static final long ACCOUNTDISABLE = 0x2;
    private static final Instant FILETIME_기원 = Instant.parse("1601-01-01T00:00:00Z");
    private static final long 초당_틱 = 10_000_000L;

    private AdAccountStatus() {
    }

    /**
     * @param dn 판단하는 엔트리. 값이 표준 밖일 때 오류 메시지에 싣는다 — 운영자가 로그만 보고 어느 계정인지
     *           찾게 한다
     */
    static boolean 막혔는가(String dn, Attributes attributes, Instant 지금) {
        return 비활성화됐는가(dn, attributes) || 만료됐는가(dn, attributes, 지금);
    }

    private static boolean 비활성화됐는가(String dn, Attributes attributes) {
        Long 값 = 정수(dn, attributes, USER_ACCOUNT_CONTROL);
        return 값 != null && (값 & ACCOUNTDISABLE) != 0;
    }

    private static boolean 만료됐는가(String dn, Attributes attributes, Instant 지금) {
        Long 값 = 정수(dn, attributes, ACCOUNT_EXPIRES);
        if (값 == null || 값 == 0 || 값 == Long.MAX_VALUE) {
            return false;
        }
        Instant 만료 = FILETIME_기원.plus(Duration.ofSeconds(값 / 초당_틱, (값 % 초당_틱) * 100));
        return !만료.isAfter(지금);
    }

    private static Long 정수(String dn, Attributes attributes, String 이름) {
        Attribute attribute = attributes.get(이름);
        if (attribute == null) {
            return null;
        }
        Object 원본;
        try {
            원본 = attribute.get();
        } catch (NamingException e) {
            throw new DirectoryDataException("속성 '" + 이름 + "' 을 읽지 못했습니다: dn=" + dn, e);
        }
        try {
            // 다듬지 않는다 — 공백이 섞인 값은 표준 밖이다
            return Long.parseLong(String.valueOf(원본));
        } catch (NumberFormatException e) {
            throw new DirectoryDataException("속성 '" + 이름 + "' 의 값 '" + 원본 + "' 가 정수가 아닙니다"
                    + " — AD 표준 밖이라 계정이 막혔는지 판단할 수 없습니다: dn=" + dn, e);
        }
    }
}
