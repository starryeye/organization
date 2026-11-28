package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 한 사람 읽기의 값 필터(설계 2026-11-28 §5.2) — 식별 속성에 따라 값을 쓰는 법이 갈린다. */
class SingleAccountReadTest {

    @Test
    @DisplayName("objectGUID 는 GUID 문자열을 바이트로 되돌려 바이트마다 소문자 두 자리로 쓴다")
    // 깨뜨릴 변경: 앞 세 묶음 뒤집기를 빼거나 대문자·한 자리로 쓰면 이 시험이 깨진다
    void objectGUID_값_필터는_바이트를_16진으로_쓴다() {
        // given — 바이트 00 01 … 0f 의 GUID 문자열
        String guid = LdapIdentifiers.guid(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15});

        // when
        var 필터 = SingleAccountRead.값_필터("objectGUID", guid);

        // then
        assertThat(필터).contains("(objectGUID=\\00\\01\\02\\03\\04\\05\\06\\07\\08\\09\\0a\\0b\\0c\\0d\\0e\\0f)");
    }

    @Test
    @DisplayName("objectGUID 인데 GUID 모양이 아니면 값 필터가 없다")
    // 깨뜨릴 변경: 모양을 보지 않고 문자열을 그대로 쓰면 이 시험이 깨진다
    void objectGUID_인데_GUID_모양이_아니면_빈_값이다() {
        // given, when, then
        assertThat(SingleAccountRead.값_필터("objectGUID", "not-a-guid")).isEmpty();
    }

    @Test
    @DisplayName("그 밖의 속성은 RFC 4515 로 이스케이프한다")
    // 깨뜨릴 변경: filterEncode 를 빼면 이 시험이 깨진다
    void 문자열_속성_값은_이스케이프한다() {
        // given, when
        var 필터 = SingleAccountRead.값_필터("uid", "a*(b)\\c");

        // then
        assertThat(필터).contains("(uid=a\\2a\\28b\\29\\5cc)");
    }

    @Test
    @DisplayName("괄호 없는 사용자 필터는 괄호로 감싸 AND 로 잇는다")
    // 깨뜨릴 변경: 괄호로 시작하지 않는 필터를 감싸지 않으면 (&objectClass=inetOrgPerson(uid=kim)) 로 깨져 이 시험이 깨진다
    void 괄호_없는_사용자_필터는_감싸서_잇는다() {
        // given
        var 값_필터 = "(uid=kim)";

        // when
        var 필터 = SingleAccountRead.AND("objectClass=inetOrgPerson", 값_필터);

        // then
        assertThat(필터).isEqualTo("(&(objectClass=inetOrgPerson)(uid=kim))");
    }

    @Test
    @DisplayName("괄호가 있는 사용자 필터는 다시 감싸지 않는다")
    // 깨뜨릴 변경: 항상 감싸면 ((objectClass=inetOrgPerson)) 로 겹괄호가 되어 이 시험이 깨진다
    void 괄호가_있는_사용자_필터는_그대로_잇는다() {
        // given, when
        var 필터 = SingleAccountRead.AND("(objectClass=inetOrgPerson)", "(uid=kim)");

        // then
        assertThat(필터).isEqualTo("(&(objectClass=inetOrgPerson)(uid=kim))");
    }
}
