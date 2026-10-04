package dev.starryeye.organization.ldap.strategy;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;

/**
 * 식별 값을 한 곳에서 읽는다(설계 2026-10-04 §4.1). {@code objectGUID}(AD)는 이진 값이라 표준 GUID 문자열로 바꾸고, 그 밖의 속성은 문자열 그대로 읽는다.
 * {@code objectGUID} 를 이진으로 받으려면 컨텍스트 소스의 JNDI 환경에 선언돼 있어야 한다({@code LdapConfig#jndiEnvironment}).
 */
public final class LdapIdentifiers {

    public static final String OBJECT_GUID = "objectGUID";

    private LdapIdentifiers() {
    }

    /** 이 속성을 이진으로 읽어야 하는가. 이름의 대소문자는 가리지 않는다. */
    public static boolean 이진인가(String attribute) {
        return OBJECT_GUID.equalsIgnoreCase(attribute);
    }

    /** 식별 값. 없으면 null. {@code objectGUID} 가 16바이트가 아니면 {@link DirectoryDataException}. */
    public static String 식별값(Attributes attributes, String attribute, String dn) throws NamingException {
        Attribute 값 = attributes.get(attribute);
        if (값 == null || 값.size() == 0) {
            return null;
        }
        Object 첫값 = 값.get();
        if (이진인가(attribute)) {
            if (!(첫값 instanceof byte[] 바이트)) {
                throw new DirectoryDataException("objectGUID 가 이진으로 오지 않았습니다 — 컨텍스트 소스의 이진 선언을 확인하세요: " + dn);
            }
            return guid(바이트, dn);
        }
        return 첫값.toString();
    }

    /**
     * 없어서는 안 되는 식별 값. 없거나 비어 있으면 그 엔트리는 id 를 가질 수 없다 — 같은 디렉터리를 다시 읽어도 생기지 않으므로
     * 재시도하지 않는 종류({@link DirectoryDataException})로 던진다.
     */
    static String 필수(Attributes attributes, String attribute, String dn) {
        String 값;
        try {
            값 = 식별값(attributes, attribute, dn);
        } catch (NamingException e) {
            throw new DirectoryDataException("속성 '" + attribute + "' 를 읽지 못했습니다: dn=" + dn, e);
        }
        if (값 == null || 값.isBlank()) {
            throw new DirectoryDataException("필수 속성 '" + attribute + "' 가 없습니다: dn=" + dn);
        }
        return 값;
    }

    public static String guid(byte[] b) {
        return guid(b, "(알 수 없음)");
    }

    /** AD 도구가 보여 주는 GUID 문자열 — 앞 세 묶음은 리틀 엔디언이라 바이트를 뒤집는다. */
    private static String guid(byte[] b, String dn) {
        if (b.length != 16) {
            throw new DirectoryDataException("objectGUID 는 16바이트여야 합니다(%d바이트): %s".formatted(b.length, dn));
        }
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".formatted(
                b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
    }
}
