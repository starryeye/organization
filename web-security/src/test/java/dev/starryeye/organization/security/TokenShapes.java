package dev.starryeye.organization.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 서명하지 않고 꼴만 갖춘 토큰 — 디코더가 키를 보기 전·후에 어디서 거절하는지 시험한다. */
final class TokenShapes {

    private TokenShapes() {
    }

    /**
     * 다섯 조각 compact JWE 꼴({@code alg=RSA-OAEP}, {@code enc=A128GCM}). 스프링의 Nimbus 디코더는 이 머리를 JWS 머리로 바꾸다 실패하고, 그 실패를
     * {@code BadJwtException} 이 아닌 {@code JwtException} 으로 낸다 — 키 목록에 닿기 전이다.
     */
    static String JWE_꼴() {
        String 조각 = b64("x");
        return b64("{\"alg\":\"RSA-OAEP\",\"enc\":\"A128GCM\"}") + "." + 조각 + "." + 조각 + "." + 조각 + "." + 조각;
    }

    /** RS256 JWS 꼴이고 {@code kid} 가 캐시된 키 목록에 없다 — 디코더가 키 목록을 다시 받으러 간다. 서명은 쓰레기다. */
    static String 모르는_kid(String kid) {
        return b64("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\"}") + "." + b64("{\"sub\":\"x\"}") + "." + b64("garbage-signature");
    }

    private static String b64(String 글) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(글.getBytes(StandardCharsets.UTF_8));
    }
}
