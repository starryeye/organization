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

    /**
     * 걸러내는 문자.
     *
     * <p>앞의 여섯은 <b>OpenFGA 파싱</b>을 깬다 — 공백류, 타입 구분자({@code :}),
     * userset 구분자({@code #}), 와일드카드({@code *}), 쉼표, 역슬래시.
     *
     * <p>마지막 {@code |} 는 OpenFGA 가 아니라 <b>우리 저장소 키</b> 때문이다.
     * {@code Keys.tupleSk} 가 튜플을 {@code user|relation|object} 로 잇고
     * {@code parseTupleSk} 가 그대로 되읽는데, {@code user} 값에 파이프가 남으면
     * 경계가 밀려 <b>전혀 다른 튜플로 되읽힌다</b>. 그 값이 스냅샷에 들어가면 다음 diff 의
     * 기준선이 오염되고, 그때부터 쓰기·삭제가 엉뚱한 대상으로 간다 — 조용히.
     */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\s:#*,\\\\|]");

    private IdNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("식별자는 비어 있을 수 없습니다");
        }
        return FORBIDDEN.matcher(raw).replaceAll("_");
    }
}
