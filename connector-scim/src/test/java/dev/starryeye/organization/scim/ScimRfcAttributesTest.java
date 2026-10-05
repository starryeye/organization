package dev.starryeye.organization.scim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ScimRfcAttributesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "phoneNumbers[type eq \"work\"].value | phoneNumbers",
            "addresses[type eq \"work\"].streetAddress | addresses",
            "emails[type eq \"other\"] | emails",
            "emails[type eq \"work\"].display | emails",
            "title | title",
            "PHONENUMBERS | phoneNumbers",
            "urn:ietf:params:scim:schemas:core:2.0:User:title | title",
            "URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER:nickName | nickName",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.value | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department | department",
            "groups | groups",
            "password | password",
            "x509Certificates[value eq \"a\"].$ref | x509Certificates"
    })
    @DisplayName("RFC 7643 코어 User(§4.1)·enterprise(§4.3)가 정의한 속성과 하위 속성은 정규 이름으로 알아본다(설계 2026-10-06 §3.2)")
    void RFC_가_정의한_속성을_알아본다(String path, String 정규_이름) {
        // when, then
        assertThat(ScimRfcAttributes.userAttribute(path)).contains(정규_이름);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "name.givenNmae",
            "phoneNumber",
            "jobTitle",
            "id",
            "meta",
            "title[type eq \"x\"]",
            "urn:ietf:params:scim:schemas:extension:custom:2.0:User:costCenter",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:userName",
            "department",
            "phoneNumbers[type eq \"work\"].nope",
            ""
    })
    @DisplayName("RFC 에 없는 이름·하위 속성, 커스텀 확장, URN 없는 enterprise 이름은 모른다 — 400 으로 남긴다")
    void RFC_밖은_모른다(String path) {
        // when, then
        assertThat(ScimRfcAttributes.userAttribute(path)).isEmpty();
    }

    @Test
    @DisplayName("표에 없는 이름의 메트릭 태그는 other 하나다")
    void 표_밖의_태그는_other() {
        // when, then
        assertThat(ScimRfcAttributes.OTHER).isEqualTo("other");
    }
}
