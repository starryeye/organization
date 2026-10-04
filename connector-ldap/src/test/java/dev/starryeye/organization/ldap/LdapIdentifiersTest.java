package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DirectoryDataException;
import dev.starryeye.organization.ldap.strategy.LdapIdentifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 식별 값을 읽는 규칙(설계 2026-10-04 §4.1) — {@code objectGUID} 는 이진이라 표준 GUID 문자열로 바꾸고, 그 밖의 속성은 문자열 그대로다.
 * 이진으로 받으려면 JNDI 환경에 선언돼 있어야 해서 {@code LdapConfig#jndiEnvironment} 도 여기서 본다.
 */
class LdapIdentifiersTest {

    @Test
    @DisplayName("objectGUID 16바이트를 AD 도구가 보여 주는 GUID 문자열로 바꾼다 — 앞 세 묶음은 바이트 순서를 뒤집는다")
    void objectGUID_를_GUID_문자열로_바꾼다() {
        // given — AD 의 a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80
        byte[] 바이트 = HexFormat.of().parseHex("d4c3b2a1f6e51807292a3b4c5d6e7f80");

        // when
        String guid = LdapIdentifiers.guid(바이트);

        // then
        assertThat(guid).isEqualTo("a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80");
    }

    @Test
    @DisplayName("objectGUID 가 16바이트가 아니면 데이터 오류다")
    void objectGUID_가_16바이트가_아니면_데이터_오류다() {
        // given
        byte[] 열다섯 = new byte[15];

        // when, then
        assertThatThrownBy(() -> LdapIdentifiers.guid(열다섯)).isInstanceOf(DirectoryDataException.class);
    }

    @Test
    @DisplayName("이진 속성인지는 이름의 대소문자를 가리지 않고 objectGUID 만 가른다")
    void 이진인가는_objectGUID_만_가른다() {
        // given, when, then
        assertThat(LdapIdentifiers.이진인가("objectGUID")).isTrue();
        assertThat(LdapIdentifiers.이진인가("OBJECTGUID")).isTrue();
        assertThat(LdapIdentifiers.이진인가("entryUUID")).isFalse();
        assertThat(LdapIdentifiers.이진인가("uid")).isFalse();
        assertThat(LdapIdentifiers.이진인가(null)).isFalse();
    }

    @Test
    @DisplayName("식별 속성이 문자열이면 첫 값을 그대로 읽고, 없으면 null 이다")
    void 문자열_식별값을_읽는다() throws NamingException {
        // given
        var attributes = new BasicAttributes(true);
        attributes.put(new BasicAttribute("entryUUID", "9f1c3b52-0a2d-4c53-8f9e-1b7d2a6c4e10"));

        // when
        String 있는것 = LdapIdentifiers.식별값(attributes, "entryUUID", "uid=kim,ou=people");
        String 없는것 = LdapIdentifiers.식별값(attributes, "uid", "uid=kim,ou=people");

        // then
        assertThat(있는것).isEqualTo("9f1c3b52-0a2d-4c53-8f9e-1b7d2a6c4e10");
        assertThat(없는것).isNull();
    }

    @Test
    @DisplayName("식별 속성이 objectGUID 이고 이진 값이 오면 GUID 문자열로 읽는다")
    void 이진_objectGUID_를_읽는다() throws NamingException {
        // given
        byte[] 바이트 = HexFormat.of().parseHex("d4c3b2a1f6e51807292a3b4c5d6e7f80");
        var attributes = new BasicAttributes(true);
        attributes.put(new BasicAttribute("objectGUID", 바이트));

        // when
        String 값 = LdapIdentifiers.식별값(attributes, "objectGUID", "cn=kim,ou=people");

        // then
        assertThat(값).isEqualTo("a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80");
    }

    @Test
    @DisplayName("objectGUID 가 이진으로 오지 않으면 — JNDI 에 이진 선언이 빠졌다 — 데이터 오류다")
    void objectGUID_가_이진으로_오지_않으면_데이터_오류다() {
        // given — 선언이 빠지면 JNDI 는 바이트를 문자열로 뭉갠다
        var attributes = new BasicAttributes(true);
        attributes.put(new BasicAttribute("objectGUID", "문자열로_온_값"));

        // when, then
        assertThatThrownBy(() -> LdapIdentifiers.식별값(attributes, "objectGUID", "cn=kim,ou=people"))
                .isInstanceOf(DirectoryDataException.class)
                .hasMessageContaining("cn=kim,ou=people");
    }

    @Test
    @DisplayName("식별 속성이 objectGUID 면 JNDI 환경에 이진 속성으로 선언한다")
    void objectGUID_는_이진으로_선언한다() {
        // given
        var properties = new LdapProperties();
        properties.getGroupOfNames().setUserIdAttribute("objectGUID");
        properties.getGroupOfNames().setGroupIdAttribute("objectGUID");

        // when
        var 환경 = LdapConfig.jndiEnvironment(properties);

        // then
        assertThat(환경).containsEntry("java.naming.ldap.attributes.binary", "objectGUID");
    }

    @Test
    @DisplayName("DIT 의 식별 속성이 objectGUID 여도 선언하고, 여러 속성이 겹쳐도 한 번만 적는다")
    void DIT_의_objectGUID_도_한_번만_선언한다() {
        // given — 대소문자가 달라도 같은 속성이다
        var properties = new LdapProperties();
        properties.getGroupOfNames().setUserIdAttribute("objectGUID");
        properties.getDit().setGroupIdAttribute("OBJECTGUID");
        properties.getDit().setUserIdAttribute("objectGuid");

        // when
        var 환경 = LdapConfig.jndiEnvironment(properties);

        // then
        assertThat(환경).containsEntry("java.naming.ldap.attributes.binary", "objectGUID");
    }

    @Test
    @DisplayName("식별 속성이 모두 문자열이면 이진 선언을 넣지 않고, 타임아웃은 그대로 싣는다")
    void 이진_속성이_없으면_선언하지_않는다() {
        // given — 기본값(entryUUID)
        var properties = new LdapProperties();

        // when
        var 환경 = LdapConfig.jndiEnvironment(properties);

        // then
        assertThat(환경)
                .doesNotContainKey("java.naming.ldap.attributes.binary")
                .containsEntry("com.sun.jndi.ldap.connect.timeout", "10000")
                .containsEntry("com.sun.jndi.ldap.read.timeout", "150000");
    }
}
