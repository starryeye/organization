package dev.starryeye.organization.ldap.fixture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AD 의 이진 {@code objectSid} 를 만든다(MS-DTYP §2.4.2.2). 개정(1바이트, 1) · 하위 권한 수(1바이트) · 식별 권한(6바이트, big-endian) ·
 * 하위 권한(4바이트 × 수, little-endian) 순서다. 시나리오가 그룹에 {@code objectSid} 를 달 때 쓴다.
 *
 * <p>값이 범위를 넘으면 조용히 잘라 담지 않고 {@link IllegalArgumentException} 으로 깨뜨린다 — 잘못 심은 SID 로 다른 RID 를 시험하는 일이 없게 한다.
 */
public final class ActiveDirectorySids {

    /** 하위 권한 수의 상한(MS-DTYP §2.4.2.2) */
    private static final int 하위권한_최대수 = 15;
    private static final long 하위권한_최대값 = 0xFFFFFFFFL;

    private ActiveDirectorySids() {
    }

    /** 도메인 {@code S-1-5-21-1-2-3} 에 {@code rid} 를 붙인 SID. 그룹·사용자의 RID 는 그 도메인 SID 의 마지막 하위 권한이다 */
    public static byte[] 도메인_RID(long rid) {
        return of(5, 21, 1, 2, 3, rid);
    }

    /**
     * {@code S-1-<식별권한>-<하위권한>...}.
     *
     * @param 식별권한 식별 권한 값. NT 권한은 5 다
     * @param 하위권한 부호 없는 32비트 값 하나 이상, 열다섯 이하
     */
    public static byte[] of(int 식별권한, long... 하위권한) {
        if (식별권한 < 0) {
            throw new IllegalArgumentException("식별 권한은 0 이상이어야 합니다: " + 식별권한);
        }
        if (하위권한.length < 1 || 하위권한.length > 하위권한_최대수) {
            throw new IllegalArgumentException("하위 권한은 1개 이상 %d개 이하여야 합니다: %d개".formatted(하위권한_최대수, 하위권한.length));
        }
        ByteBuffer 버퍼 = ByteBuffer.allocate(8 + 4 * 하위권한.length);
        버퍼.put((byte) 1).put((byte) 하위권한.length);
        버퍼.order(ByteOrder.BIG_ENDIAN).putShort((short) 0).putInt(식별권한);
        버퍼.order(ByteOrder.LITTLE_ENDIAN);
        for (long 값 : 하위권한) {
            if (값 < 0 || 값 > 하위권한_최대값) {
                throw new IllegalArgumentException("하위 권한은 부호 없는 32비트여야 합니다: " + 값);
            }
            버퍼.putInt((int) 값);
        }
        return 버퍼.array();
    }
}
