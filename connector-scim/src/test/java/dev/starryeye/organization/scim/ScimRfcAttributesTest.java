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
            // 코어 User(§4.1) 단일 속성 11개 — 표에 한 줄씩. 오타 하나가 같은 요청의 비활성화를 다시 막는다(점검 M5)
            "userName | userName",
            "displayName | displayName",
            "nickName | nickName",
            "profileUrl | profileUrl",
            "title | title",
            "userType | userType",
            "preferredLanguage | preferredLanguage",
            "locale | locale",
            "timezone | timezone",
            "active | active",
            "password | password",
            // 코어 User 복합·복수 속성 10개
            "name | name",
            "emails | emails",
            "phoneNumbers | phoneNumbers",
            "ims | ims",
            "photos | photos",
            "addresses | addresses",
            "groups | groups",
            "entitlements | entitlements",
            "roles | roles",
            "x509Certificates | x509Certificates",
            // enterprise(§4.3) 속성 6개 — 전체 URN 으로
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:employeeNumber | employeeNumber",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:costCenter | costCenter",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:organization | organization",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:division | division",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:department | department",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager | manager",
            // name 의 하위 속성 6개 전부
            "name.formatted | name",
            "name.familyName | name",
            "name.givenName | name",
            "name.middleName | name",
            "name.honorificPrefix | name",
            "name.honorificSuffix | name",
            // addresses 의 하위 속성 8개 전부
            "addresses.formatted | addresses",
            "addresses.streetAddress | addresses",
            "addresses.locality | addresses",
            "addresses.region | addresses",
            "addresses.postalCode | addresses",
            "addresses.country | addresses",
            "addresses.type | addresses",
            "addresses.primary | addresses",
            // manager 의 하위 속성 3개 전부
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.value | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.$ref | manager",
            "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager.displayName | manager",
            // 복수 속성의 공통 하위 속성(§2.4) — 속성마다 하나씩, 다섯 이름이 모두 나온다
            "emails.primary | emails",
            "phoneNumbers.type | phoneNumbers",
            "ims.value | ims",
            "photos.display | photos",
            "groups.$ref | groups",
            "entitlements.value | entitlements",
            "roles.type | roles",
            "x509Certificates.value | x509Certificates",
            // path 모양과 대소문자 — 필터, 큰·작은 글자, 코어 URN 접두
            "phoneNumbers[type eq \"work\"].value | phoneNumbers",
            "addresses[type eq \"work\"].streetAddress | addresses",
            "emails[type eq \"other\"] | emails",
            "emails[type eq \"work\"].display | emails",
            "PHONENUMBERS | phoneNumbers",
            "urn:ietf:params:scim:schemas:core:2.0:User:title | title",
            "URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER:nickName | nickName",
            "x509Certificates[value eq \"a\"].$ref | x509Certificates"
    })
    @DisplayName("RFC 7643 코어 User(§4.1)·enterprise(§4.3)가 정의한 속성 27개와 하위 속성은 하나도 빠짐없이 정규 이름으로 알아본다(설계 2026-10-06 §3.2)")
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
