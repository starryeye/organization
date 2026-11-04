package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DynamoDB 의 {@code LastEvaluatedKey} 를 불투명 문자열로 감싼다.
 *
 * <p>이 스키마의 키 속성은 전부 문자열(S)이므로 값만 뽑아 base64 로 감싼다.
 * 이름과 값을 각각 감싸는 이유는, 값에 구분자가 들어 있어도 파싱이 깨지지 않게 하기 위해서다.
 *
 * <p><b>어느 검색이 발급한 커서인지도 함께 담는다.</b> 담지 않으면 형식만 멀쩡한 다른 검색의
 * 커서가 그대로 해석돼 DynamoDB 까지 내려가고, 거기서 나는 ValidationException 은 500 이
 * 된다 — 설계 §9 는 손상된 커서를 400 으로 규정한다. 예를 들어 GSI1 커서는
 * {@code {PK, SK, GSI1PK, GSI1SK}} 인데 GSI2 질의는 {@code {PK, SK, GSI1PK, displayNameKey}} 를
 * 요구하므로 저장소가 거절한다. 같은 인덱스라도 파티션이 다르면(GSI1 의 USER_INDEX 와
 * GROUP_INDEX) 거절되지도 않고 엉뚱한 자리에서 읽기 시작하므로, 인덱스가 아니라
 * <b>검색 범위</b>(인덱스 + 파티션)를 통째로 담는다.
 *
 * <p><b>남은 한계 — 커서는 서명도 암호화도 하지 않는다.</b> base64 를 풀면 원본 키(직원·조직 아이디,
 * 색인 값)가 보이고, 고쳐 보낼 수도 있다. 고쳐 보내도 질의(인덱스·파티션)는 서버가 정하므로 그 검색
 * 안에서 읽기 시작하는 자리만 바뀐다. 커서를 주고받는 것은 관리 API 조회({@code admin.read} 범위)뿐이고
 * (SCIM 목록은 {@code startIndex} 로 읽고 커서는 서버의 책갈피에만 둔다), 그 토큰을 가진 쪽은 커서에
 * 담긴 데이터를 이미 응답으로 본다. 인증 사이클은 커서를 바꾸지 않았다.
 */
final class Cursor {

    private static final String PAIR_SEPARATOR = "|";
    private static final String KEY_VALUE_SEPARATOR = ":";

    private Cursor() {
    }

    static String encode(String scope, Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String pairs = lastEvaluatedKey.entrySet().stream()
                .map(entry -> b64(entry.getKey()) + KEY_VALUE_SEPARATOR + b64(entry.getValue().s()))
                .collect(Collectors.joining(PAIR_SEPARATOR));
        return b64(b64(scope) + PAIR_SEPARATOR + pairs);
    }

    /**
     * {@code scope} 가 발급한 커서가 아니면 {@link IllegalArgumentException} 이다 — 호출부가
     * 그것을 400 으로 옮긴다.
     */
    static Map<String, AttributeValue> decode(String scope, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] tokens = unb64(cursor).split("\\" + PAIR_SEPARATOR);
            if (tokens.length < 2) {
                throw new IllegalArgumentException("커서 형식이 올바르지 않다");
            }
            String stamped = unb64(tokens[0]);
            if (!stamped.equals(scope)) {
                throw new IllegalArgumentException(
                        "다른 검색(%s)이 발급한 커서다. 이 검색은 %s 를 쓴다".formatted(stamped, scope));
            }
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (int i = 1; i < tokens.length; i++) {
                String[] parts = tokens[i].split(KEY_VALUE_SEPARATOR, 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("커서 형식이 올바르지 않다");
                }
                key.put(unb64(parts[0]), Attrs.s(unb64(parts[1])));
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("커서를 해석할 수 없다", e);
        }
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
