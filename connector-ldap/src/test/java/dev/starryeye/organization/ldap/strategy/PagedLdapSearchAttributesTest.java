package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 검색에 달 속성 이름 목록을 설정 값에서 만든다 — 설정이 비었거나 겹쳐도 요청 목록은 깨끗하다. */
class PagedLdapSearchAttributesTest {

    @Test
    @DisplayName("null 이거나 빈 이름은 목록에서 뺀다 — 설정하지 않은 속성을 요청하지 않는다")
    void 비어_있는_이름은_뺀다() {
        // given
        String[] 이름들 = {"uid", null, "", "  ", "mail"};

        // when
        String[] 속성 = PagedLdapSearch.속성목록(이름들);

        // then
        assertThat(속성).containsExactly("uid", "mail");
    }

    @Test
    @DisplayName("같은 이름은 한 번만 남기고 순서는 처음 나온 대로 둔다 — LDAP 속성 이름은 대소문자를 가리지 않는다")
    void 겹치는_이름은_처음_것만_남긴다() {
        // given
        String[] 이름들 = {"cn", "displayName", "CN", "mail", "DisplayName"};

        // when
        String[] 속성 = PagedLdapSearch.속성목록(이름들);

        // then
        assertThat(속성).containsExactly("cn", "displayName", "mail");
    }
}
