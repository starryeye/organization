package dev.starryeye.organization.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * 키나 색인에 들어갈 값의 쓰기 한도(설계 2026-10-17 §3). 실제 DynamoDB 는 키가 한도를 넘으면 거절하는데 OpenFGA 를 쓴 뒤에 거절해 둘이 어긋나므로,
 * SCIM 쓰기가 표지판·OpenFGA 를 보기 전에 이 한도를 본다. 모두 UTF-8 바이트다.
 *
 * <ul>
 *   <li>{@code userName}·{@code displayName} — 키에는 {@code Locale.ROOT} 소문자가 들어가므로 <b>소문자로 만든 뒤</b> 잰다. 소문자로 바꾸면
 *       바이트가 늘 수 있다(U+0130 은 2에서 3바이트).</li>
 *   <li>{@code externalId} — 값 그대로 키가 된다. 공백뿐인 값도 잰다(표지판은 건너뛰지만 직원 GSI3 파티션키에는 그대로 들어간다).</li>
 *   <li>넣는 멤버의 아이디 — 멤버 줄 정렬키 {@code MEMBER#GROUP#<id>} 가 1024바이트라 접두어를 빼고 {@value #MAX_MEMBER_ID_BYTES}바이트다.</li>
 * </ul>
 *
 * <p>없는({@code null}) 값은 넘지 않는다. 읽기 쪽 한도는 저장소의 몫이다(설계 §4).
 */
public final class KeyValueLimits {

    /** 정렬키에 들어가는 값의 한도 — 표지판 키 한도와 같다. */
    public static final int MAX_INDEXED_BYTES = UniqueKind.MAX_KEY_BYTES;

    /** 멤버 줄 정렬키 {@code MEMBER#GROUP#<id>}(1024)에서 접두어를 뺀 아이디의 한도. */
    public static final int MAX_MEMBER_ID_BYTES = 1000;

    /** 한도를 넘은 속성과 그 한도. */
    public record Over(String attribute, int limitBytes) {
    }

    private KeyValueLimits() {
    }

    /** 직원 값 가운데 먼저 넘는 하나 — {@code userName}, {@code displayName}, {@code externalId} 순이다. */
    public static Optional<Over> over(DirectoryUser user) {
        return lowercased("userName", user.userName())
                .or(() -> lowercased("displayName", user.displayName()))
                .or(() -> asIs("externalId", user.externalId()));
    }

    /** 조직 값 가운데 먼저 넘는 하나 — {@code displayName}, {@code externalId} 순이다. */
    public static Optional<Over> over(GroupHeader header) {
        return lowercased("displayName", header.displayName())
                .or(() -> asIs("externalId", header.externalId()));
    }

    /** 넣는 멤버 가운데 아이디가 {@value #MAX_MEMBER_ID_BYTES}바이트를 넘는 것이 하나라도 있으면 {@code members} 다. */
    public static Optional<Over> overMembers(Collection<MemberRef> members) {
        return members.stream().anyMatch(member -> bytes(member.id()) > MAX_MEMBER_ID_BYTES)
                ? Optional.of(new Over("members", MAX_MEMBER_ID_BYTES))
                : Optional.empty();
    }

    private static Optional<Over> lowercased(String attribute, String value) {
        return value != null && bytes(value.toLowerCase(Locale.ROOT)) > MAX_INDEXED_BYTES
                ? Optional.of(new Over(attribute, MAX_INDEXED_BYTES))
                : Optional.empty();
    }

    private static Optional<Over> asIs(String attribute, String value) {
        return value != null && bytes(value) > MAX_INDEXED_BYTES
                ? Optional.of(new Over(attribute, MAX_INDEXED_BYTES))
                : Optional.empty();
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
