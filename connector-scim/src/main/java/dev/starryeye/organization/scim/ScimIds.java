package dev.starryeye.organization.scim;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * SCIM POST 가 쓸 후보 id 를 정한다(설계 2026-11-05 I1·I2·I6).
 *
 * <ul>
 *   <li>기본은 무작위 v4 다. 켜면({@link #fromExternalId()}) {@code externalId} 가 공백이 아닐 때만
 *   결정적 v5 를 낸다 — 같은 {@code externalId} 는 늘 같은 id 다(설계 I1). 이 결정적 id 는
 *   {@code externalId} 가 IdP 의 영구 식별자일 때만 켠다(설계 I2).</li>
 *   <li>네임스페이스 {@link #NAMESPACE} 는 영구 고정이다(설계 I6). 바꾸면 그 뒤 모든 후보가 달라지고,
 *   이미 만든 결정적 id 와 어긋난다.</li>
 *   <li>직원은 {@code "User:" + externalId}, 조직은 {@code "Group:" + externalId} 를 이름으로 쓴다 —
 *   UTF-8, 대소문자 그대로.</li>
 * </ul>
 */
public final class ScimIds {

    /** 영구 고정 네임스페이스(설계 I6). 바꾸지 않는다. */
    static final UUID NAMESPACE = UUID.fromString("628efe45-04dd-4c33-82e2-f2d612044e98");

    private static final String USER_PREFIX = "User:";
    private static final String GROUP_PREFIX = "Group:";

    private final boolean fromExternalId;

    private ScimIds(boolean fromExternalId) {
        this.fromExternalId = fromExternalId;
    }

    /** 늘 무작위 v4 다 — 기본 꺼짐(설계 I2). */
    public static ScimIds random() {
        return new ScimIds(false);
    }

    /** {@code externalId} 가 공백이 아니면 v5, 아니면 v4 다. */
    public static ScimIds fromExternalId() {
        return new ScimIds(true);
    }

    public String user(String externalId) {
        return candidate(USER_PREFIX, externalId);
    }

    public String group(String externalId) {
        return candidate(GROUP_PREFIX, externalId);
    }

    private String candidate(String prefix, String externalId) {
        if (!fromExternalId || externalId == null || externalId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return nameBased(NAMESPACE, prefix + externalId).toString();
    }

    /**
     * RFC 9562 §5.5 의 이름 기반 UUID(v5). SHA-1(네임스페이스 16바이트 빅엔디언 + 이름 UTF-8)의 앞 16바이트에서
     * 버전 비트(6번 바이트 상위 4비트)를 5 로, 변종 비트(8번 바이트 상위 2비트)를 10 으로 맞춘다.
     */
    static UUID nameBased(UUID namespace, String name) {
        MessageDigest sha1 = sha1();
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();

        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("모든 JVM 이 가져야 하는 SHA-1 이 없다", e);
        }
    }
}
