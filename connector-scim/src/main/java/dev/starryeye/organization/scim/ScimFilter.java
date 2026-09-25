package dev.starryeye.organization.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SCIM 필터의 좁은 부분집합 — {@code 속성 eq 값 (and 속성 eq 값)*} 만 받는다 (S-1 설계 §4.1).
 *
 * <pre>
 * filter   = term *( SP "and" SP term )
 * term     = attrPath SP "eq" SP compValue
 * attrPath = [ 리소스의 core 스키마 URN ":" ] attrName
 * compValue = JSON 문자열 / "true" / "false"
 * </pre>
 *
 * <p>RFC 7644 §3.4.2.2 는 필터 전체를 선택 기능으로 두고, 지원하지 않는 조합에는 {@code invalidFilter} 를
 * 돌려주라고 정한다. Entra 는 {@code eq}·{@code and} 만 쓴다고 문서에 밝혔고 Okta 는 {@code eq} 만 쓴다. 이
 * 문법에 맞지 않는 모든 것 — {@code or}·{@code not}, 다른 연산자, 괄호, 대괄호 값 경로, 하위 속성,
 * 숫자·{@code null} — 은 한 규칙으로 거절한다.
 *
 * <p>여기서는 모양만 본다. 어떤 속성을 받을지, 값의 타입이 맞는지는 리소스마다 달라 조회 쪽이 판단한다.
 *
 * @param terms 한 개 이상. 모두 {@code and} 로 묶인다
 */
public record ScimFilter(List<Term> terms) {

    /**
     * @param attribute 스키마 URN 을 뗀 속성 이름, 소문자
     * @param value     {@link String} 또는 {@link Boolean}
     */
    public record Term(String attribute, Object value) {
    }

    public static ScimFilter parse(String raw, ScimResourceType type) {
        if (raw == null || raw.isEmpty()) {
            throw ScimException.invalidFilter("필터가 비어 있습니다");
        }
        Parser parser = new Parser(raw, type);
        List<Term> terms = new ArrayList<>();
        terms.add(parser.term());
        while (!parser.atEnd()) {
            parser.space();
            parser.keyword("and");
            parser.space();
            terms.add(parser.term());
        }
        return new ScimFilter(List.copyOf(terms));
    }

    private static final class Parser {

        private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[a-z][a-z0-9_-]*");

        private final String text;
        private final ScimResourceType type;
        private int position;

        Parser(String text, ScimResourceType type) {
            this.text = text;
            this.type = type;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        Term term() {
            String attribute = attributePath();
            space();
            keyword("eq");
            space();
            return new Term(attribute, value());
        }

        /** 공백 한 칸(ABNF 의 SP). 두 칸 이상은 문법 밖이다. */
        void space() {
            if (atEnd() || text.charAt(position) != ' ') {
                throw fail("공백 한 칸이 와야 합니다 (위치 " + position + ")");
            }
            position++;
        }

        void keyword(String word) {
            if (!text.regionMatches(true, position, word, 0, word.length())) {
                throw fail("'" + word + "' 가 와야 합니다 (위치 " + position + ")");
            }
            position += word.length();
        }

        private String attributePath() {
            int start = position;
            while (!atEnd() && text.charAt(position) != ' ') {
                position++;
            }
            String path = text.substring(start, position);
            String local = type.localName(path);
            if (local == null || !ATTRIBUTE_NAME.matcher(local).matches()) {
                throw fail("지원하지 않는 속성 경로입니다: '" + path + "'");
            }
            return local;
        }

        private Object value() {
            if (!atEnd() && text.charAt(position) == '"') {
                return string();
            }
            int start = position;
            while (!atEnd() && text.charAt(position) != ' ') {
                position++;
            }
            String token = text.substring(start, position);
            if ("true".equals(token)) {
                return Boolean.TRUE;
            }
            if ("false".equals(token)) {
                return Boolean.FALSE;
            }
            throw fail("지원하지 않는 값입니다: '" + token + "' — 문자열과 true/false 만 받습니다");
        }

        /** JSON 문자열(RFC 8259 §7) — 따옴표 안의 이스케이프를 푼다. */
        private String string() {
            StringBuilder out = new StringBuilder();
            position++;
            while (!atEnd()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    break;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw fail("알 수 없는 이스케이프입니다: \\" + escaped);
                }
            }
            throw fail("문자열이 닫히지 않았습니다");
        }

        private char unicode() {
            if (position + 4 > text.length()) {
                throw fail("\\u 뒤에는 16진수 네 자리가 와야 합니다");
            }
            String hex = text.substring(position, position + 4);
            position += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw fail("\\u 뒤에는 16진수 네 자리가 와야 합니다: " + hex);
            }
        }

        private ScimException fail(String detail) {
            return ScimException.invalidFilter(detail + " — filter: " + text);
        }
    }
}
