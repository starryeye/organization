package dev.starryeye.organization.core.tuple;

import java.util.regex.Pattern;

/**
 * OpenFGA object id 로 쓸 수 있게 식별자를 정규화한다.
 *
 * <p>허용 목록이 아니라 금지 목록을 쓰는 이유는 한글 조직코드를 보존하기 위해서다.
 * {@code [A-Za-z0-9._@-]} 허용 목록을 쓰면 "개발본부"가 "____"가 되어
 * 서로 다른 조직이 같은 id 로 뭉개진다.
 */
public final class IdNormalizer {

    /** OpenFGA 파싱을 깨는 문자: 공백류, 타입 구분자(:), userset 구분자(#), 와일드카드(*), 쉼표, 역슬래시 */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\s:#*,\\\\]");

    private IdNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("식별자는 비어 있을 수 없습니다");
        }
        return FORBIDDEN.matcher(raw).replaceAll("_");
    }
}
