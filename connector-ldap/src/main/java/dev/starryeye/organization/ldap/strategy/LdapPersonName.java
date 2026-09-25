package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.PersonName;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;

/**
 * LDAP 표준 이름 속성을 직원 이름으로 읽는다 (S-3 설계 §5). 속성 이름은 표준이라 설정으로 빼지 않는다 — ⑥ 의 AD 계정
 * 상태와 같은 방식이다.
 *
 * <ul>
 *   <li>{@code givenName} → givenName, {@code sn} → familyName, {@code generationQualifier} → honorificSuffix (RFC 4519)</li>
 *   <li>{@code middleName} → middleName (AD 스키마)</li>
 * </ul>
 * {@code formatted}·{@code honorificPrefix} 는 LDAP 표준 속성이 없어 비운다. 속성이 없으면 빈칸이다. 여러 값이면 첫 값이다.
 */
final class LdapPersonName {

    private LdapPersonName() {
    }

    static PersonName from(Attributes attributes) {
        return new PersonName(
                null,
                first(attributes, "sn"),
                first(attributes, "givenName"),
                first(attributes, "middleName"),
                null,
                first(attributes, "generationQualifier"));
    }

    private static String first(Attributes attributes, String name) {
        Attribute attribute = attributes.get(name);
        if (attribute == null) {
            return null;
        }
        try {
            Object value = attribute.get();
            return value == null ? null : value.toString();
        } catch (NamingException e) {
            throw new DirectoryDataException("속성 '" + name + "' 을 읽지 못했습니다", e);
        }
    }
}
