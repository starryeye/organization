package dev.starryeye.organization.scim;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RFC 7643 이 정의한 직원 속성 — 코어 User(§4.1)와 enterprise 확장(§4.3), 그리고 그 하위 속성(설계 2026-10-06 §3.2).
 * 우리가 저장하지 않는 속성을 path 로 받았을 때 둘을 가른다. "표준이 정의했지만 다루지 않는 것" 은 받아서 버리고, "모르는 것" 은 400 이다.
 * RFC 7643 은 2015 년 이후 바뀌지 않았다.
 */
final class ScimRfcAttributes {

    /** 표에 없는 키(경로 없는 값)를 메트릭 태그로 묶는 이름 — 요청 문자열을 태그에 싣지 않는다. */
    static final String OTHER = "other";

    private static final String CORE_USER_URN = "urn:ietf:params:scim:schemas:core:2.0:user:";
    private static final String ENTERPRISE_USER_URN = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:user:";

    /** RFC 7644 §3.10 의 PATCH path — {@code 속성[필터].하위속성}. 필터 안은 보지 않는다. */
    private static final Pattern PATH = Pattern.compile(
            "^(?<attr>[A-Za-z][\\w$-]*)(?:\\[(?<filter>.*)])?(?:\\.(?<sub>[A-Za-z$][\\w$-]*))?$", Pattern.DOTALL);

    /** 정규 이름과 소문자 하위 속성. 하위 속성이 없으면 단일 속성이다. */
    private record Attribute(String name, Set<String> subAttributes) {
    }

    /** 복수 속성의 공통 하위 속성(RFC 7643 §2.4). */
    private static final Set<String> 복수_하위 = Set.of("value", "display", "type", "primary", "$ref");

    private static final Map<String, Attribute> CORE = 표(
            단일("userName"), 단일("displayName"), 단일("nickName"), 단일("profileUrl"), 단일("title"),
            단일("userType"), 단일("preferredLanguage"), 단일("locale"), 단일("timezone"), 단일("active"), 단일("password"),
            new Attribute("name", 소문자("formatted", "familyName", "givenName", "middleName",
                    "honorificPrefix", "honorificSuffix")),
            복수("emails"), 복수("phoneNumbers"), 복수("ims"), 복수("photos"), 복수("groups"),
            복수("entitlements"), 복수("roles"), 복수("x509Certificates"),
            new Attribute("addresses", 소문자("formatted", "streetAddress", "locality", "region", "postalCode",
                    "country", "type", "primary")));

    private static final Map<String, Attribute> ENTERPRISE = 표(
            단일("employeeNumber"), 단일("costCenter"), 단일("organization"), 단일("division"), 단일("department"),
            new Attribute("manager", 소문자("value", "$ref", "displayName")));

    private ScimRfcAttributes() {
    }

    /**
     * path 가 RFC 가 정의한 직원 속성을 가리키면 그 속성의 정규 이름을 돌려준다. 하위 속성이 있으면 그것도 RFC 가 정의한 것이어야 한다.
     * 코어 User URN 접두는 떼고 코어 표에서, enterprise URN 접두는 떼고 enterprise 표에서 찾는다.
     * URN 없는 enterprise 이름(예: {@code department})은 RFC 의 모양이 아니라 모른다(RFC 7644 §3.10).
     */
    static Optional<String> userAttribute(String path) {
        String rest = path.trim();
        String lower = rest.toLowerCase(Locale.ROOT);
        Map<String, Attribute> table = CORE;
        if (lower.startsWith(ENTERPRISE_USER_URN)) {
            table = ENTERPRISE;
            rest = rest.substring(ENTERPRISE_USER_URN.length());
        } else if (lower.startsWith(CORE_USER_URN)) {
            rest = rest.substring(CORE_USER_URN.length());
        }
        Matcher matcher = PATH.matcher(rest);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Attribute attribute = table.get(matcher.group("attr").toLowerCase(Locale.ROOT));
        if (attribute == null) {
            return Optional.empty();
        }
        // 필터와 하위 속성은 하위 속성을 가진 속성에만 뜻이 있다
        if ((matcher.group("filter") != null || matcher.group("sub") != null) && attribute.subAttributes().isEmpty()) {
            return Optional.empty();
        }
        String sub = matcher.group("sub");
        if (sub != null && !attribute.subAttributes().contains(sub.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(attribute.name());
    }

    private static Attribute 단일(String name) {
        return new Attribute(name, Set.of());
    }

    private static Attribute 복수(String name) {
        return new Attribute(name, 복수_하위);
    }

    private static Set<String> 소문자(String... names) {
        return Arrays.stream(names).map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Attribute> 표(Attribute... attributes) {
        return Arrays.stream(attributes).collect(Collectors.toUnmodifiableMap(
                attribute -> attribute.name().toLowerCase(Locale.ROOT), Function.identity()));
    }
}
