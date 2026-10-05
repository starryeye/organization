package dev.starryeye.organization.ldap.strategy;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;

/**
 * AD 기본 그룹(설계 2026-10-05 §3.2, 점검 M10). 사용자의 기본 그룹 소속은 그 그룹의 {@code member} 에 적히지 않고 사용자의 {@code primaryGroupID}
 * (그룹의 RID)로만 표현된다(MS-ADA3 primaryGroupID). 그룹의 RID 는 {@code objectSid} 의 마지막 하위 권한 값이다(MS-DTYP §2.4.2.2). 검색 베이스는 한 도메인
 * 안이고 referral 을 따르지 않으므로 RID 만 맞추면 된다. 속성이 없으면(OpenLDAP) null — 아무 일도 없다. 표준 밖 값은 짐작하지 않고 {@link DirectoryDataException}.
 */
public final class ActiveDirectoryPrimaryGroup {

    public static final String PRIMARY_GROUP_ID = "primaryGroupID";
    public static final String OBJECT_SID = "objectSid";

    private ActiveDirectoryPrimaryGroup() {
    }

    /** 직원의 기본 그룹 RID. 없으면 null. 정수가 아니면 데이터 오류 — 다듬지 않는다 */
    static Long 기본그룹_RID(Attributes attributes, String dn) {
        Object 값 = 첫값(attributes, PRIMARY_GROUP_ID, dn);
        if (값 == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(값));
        } catch (NumberFormatException e) {
            throw new DirectoryDataException("속성 '" + PRIMARY_GROUP_ID + "' 의 값 '" + 값 + "' 가 정수가 아닙니다: dn=" + dn, e);
        }
    }

    /** 그룹의 RID. {@code objectSid} 가 없으면 null. 이진으로 오지 않았거나 SID 형식이 아니면 데이터 오류 */
    static Long RID(Attributes attributes, String dn) {
        Object 값 = 첫값(attributes, OBJECT_SID, dn);
        if (값 == null) {
            return null;
        }
        if (!(값 instanceof byte[] 바이트)) {
            throw new DirectoryDataException(
                    "속성 '" + OBJECT_SID + "' 가 이진으로 오지 않았습니다 — 컨텍스트 소스의 이진 선언을 확인하세요: dn=" + dn);
        }
        return RID(바이트, dn);
    }

    /** 개정(1) · 하위 권한 수(1) · 식별 권한(6, big-endian) · 하위 권한(4 × 수, little-endian). RID 는 마지막 하위 권한(부호 없는 32비트) */
    static long RID(byte[] sid, String dn) {
        if (sid.length < 8 || sid[0] != 1) {
            throw new DirectoryDataException(
                    "속성 '%s' 가 SID 형식이 아닙니다(%d바이트): dn=%s".formatted(OBJECT_SID, sid.length, dn));
        }
        int 하위권한수 = sid[1] & 0xFF;
        if (하위권한수 < 1 || sid.length != 8 + 4 * 하위권한수) {
            throw new DirectoryDataException("속성 '%s' 의 하위 권한 수(%d)와 길이(%d바이트)가 맞지 않습니다: dn=%s"
                    .formatted(OBJECT_SID, 하위권한수, sid.length, dn));
        }
        int 끝 = sid.length - 4;
        return (sid[끝] & 0xFFL) | (sid[끝 + 1] & 0xFFL) << 8 | (sid[끝 + 2] & 0xFFL) << 16 | (sid[끝 + 3] & 0xFFL) << 24;
    }

    private static Object 첫값(Attributes attributes, String 이름, String dn) {
        Attribute attribute = attributes.get(이름);
        if (attribute == null || attribute.size() == 0) {
            return null;
        }
        try {
            return attribute.get();
        } catch (NamingException e) {
            throw new DirectoryDataException("속성 '" + 이름 + "' 을 읽지 못했습니다: dn=" + dn, e);
        }
    }
}
