package dev.starryeye.authserver;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Base64;

/** 시험에서 쓰는 키. 운영이 {@code AUTH_SIGNING_KEY} 로 주는 것과 같은 모양(PKCS#8 PEM)으로 만든다. */
final class TestKeys {

    private TestKeys() {
    }

    static KeyPair 키_쌍(String 알고리즘, int 크기) {
        try {
            var generator = KeyPairGenerator.getInstance(알고리즘);
            generator.initialize(크기);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair RSA_키_쌍() {
        return 키_쌍("RSA", 2048);
    }

    /** {@code openssl genpkey -algorithm RSA} 가 내는 모양 — {@code -----BEGIN PRIVATE KEY-----}, 64자마다 줄바꿈. */
    static String PKCS8_PEM(PrivateKey 개인_키) {
        String 본문 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(개인_키.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + 본문 + "\n-----END PRIVATE KEY-----\n";
    }
}
