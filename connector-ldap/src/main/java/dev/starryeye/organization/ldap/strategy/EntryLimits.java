package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.KeyValueLimits;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 엔트리의 id·절대 DN 이 저장 한도를 넘는지 잰다(설계 2026-11-19 S2). 한도는 UTF-8 바이트이고, 같으면 통과·넘으면 건너뛴다.
 * id 를 먼저, 그다음 DN 을 잰다 — 둘 다 넘으면 id 쪽 문구를 돌려준다.
 */
final class EntryLimits {

    /** DN 이 넘을 때 예시 문구에 싣는 앞부분의 코드 포인트 수 — 경고 한 줄이 DN 길이만큼 늘지 않게 한다(설계 2026-11-19 S4) */
    static final int 예시_DN_코드_포인트 = 100;

    private EntryLimits() {
    }

    /**
     * @param 정규화한_id 저장될 id(정규화한 뒤의 값)
     * @param 절대DN      저장될 {@code externalId} — 베이스까지 붙인 절대 DN
     * @param id한도      id 의 한도(바이트). 직원은 {@link KeyValueLimits#MAX_MEMBER_ID_BYTES}, 조직은 {@link KeyValueLimits#MAX_GROUP_ID_BYTES}
     * @return 넘지 않으면 비었고, 넘으면 요약에 싣는 예시 문구다
     */
    static Optional<String> 넘는_값(String 정규화한_id, String 절대DN, int id한도) {
        int idBytes = 바이트(정규화한_id);
        int dnBytes = 바이트(절대DN);
        boolean dn넘음 = dnBytes > KeyValueLimits.MAX_INDEXED_BYTES;
        if (idBytes > id한도) {
            // DN 도 넘으면 같은 이유(S4)로 id 문구에서도 DN 앞부분만 싣는다
            String dn = dn넘음 ? 앞부분(절대DN) + "…" : 절대DN;
            return Optional.of("dn='%s'(id %d바이트, 한도 %d)".formatted(dn, idBytes, id한도));
        }
        if (dn넘음) {
            return Optional.of("dn='%s…'(DN %d바이트, 한도 %d)".formatted(
                    앞부분(절대DN), dnBytes, KeyValueLimits.MAX_INDEXED_BYTES));
        }
        return Optional.empty();
    }

    private static String 앞부분(String 절대DN) {
        int 끝 = 절대DN.offsetByCodePoints(0, Math.min(예시_DN_코드_포인트, 절대DN.codePointCount(0, 절대DN.length())));
        return 절대DN.substring(0, 끝);
    }

    private static int 바이트(String 값) {
        return 값.getBytes(StandardCharsets.UTF_8).length;
    }
}
