package dev.starryeye.organization.authz;

import java.util.regex.Pattern;

/**
 * 기동 때 OpenFGA 설정을 검사한다(설계 2026-10-28 §7, A11·A12). {@link StoreBootstrapper} 를 만들 때 맨 먼저 부른다. 키는 오류 문구에 싣지 않는다.
 *
 * <ul>
 *   <li><b>키.</b> 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은 자리표시자를 무시하고 {@code "${OPENFGA_API_TOKEN}"} 문자열을
 *       그대로 넣는다. 그 값을 키로 실으면 OpenFGA 가 401 을 돌려줄 뿐이라 원인이 가려진다. {@code openfga.require-api-token} 인데 키가 비었거나
 *       <code>&#36;&#123;</code> 로 시작하면 멈춘다.
 *   <li><b>키의 모양.</b> 키가 있으면(요구하든 않든) 눈에 보이는 ASCII 만 받는다. SDK 는 키를 {@code Authorization: Bearer <키>} 헤더로 싣는데, JDK HTTP
 *       클라이언트는 줄바꿈·공백·제어문자·ASCII 밖 글자가 든 헤더 값을 {@code IllegalArgumentException: invalid header value: "Bearer <키>"} 로 거절하며
 *       <b>키를 오류 문구에 싣는다</b>. 이 오류는 401 이 아니라 {@link OpenFgaStoreInitializer} 가 경고 로그로, 헬스 인디케이터가 {@code Health.down(error)}
 *       로 그대로 내보낸다. 비밀 파일을 {@code $(cat key)} 로 읽으면 끝에 줄바꿈이 붙는 것이 현실적인 계기라, 요청이 나가기 전에 여기서 막는다.
 *   <li><b>TLS.</b> {@code openfga.require-tls} 인데 {@code openfga.api-url} 이 {@code https://} 가 아니면 멈춘다 — 키가 평문으로 다니지 않는다.
 * </ul>
 *
 * <p>{@code ldap.require-tls}·바인드 비밀번호 검사({@code LdapConfig.설정을_검사한다})와 같은 모양이다.
 */
final class OpenFgaSettingsCheck {

    /** 헤더 값으로 안전한 키: 공백·제어문자 없이 눈에 보이는 ASCII({@code 0x21}~{@code 0x7E})만. */
    private static final Pattern 눈에_보이는_ASCII = Pattern.compile("[\\x21-\\x7E]+");

    private OpenFgaSettingsCheck() {
    }

    static void 검사한다(OpenFgaProperties properties) {
        String 키 = properties.getApiToken();
        if (properties.isRequireApiToken() && (키 == null || 키.isBlank() || 키.startsWith("${"))) {
            throw new IllegalStateException("openfga.require-api-token 인데 openfga.api-token 이 비었거나 풀리지 않은 자리표시자다"
                    + " — 환경 변수 OPENFGA_API_TOKEN 을 준다");
        }
        if (키 != null && !키.isBlank() && !눈에_보이는_ASCII.matcher(키).matches()) {
            throw new IllegalStateException("openfga.api-token 에 공백·제어문자·ASCII 밖 글자가 있다 — 키 끝에 줄바꿈이 붙지 않았는지 본다");
        }
        String 주소 = properties.getApiUrl();
        if (properties.isRequireTls() && (주소 == null || !주소.startsWith("https://"))) {
            throw new IllegalStateException("openfga.require-tls 인데 openfga.api-url 이 https:// 가 아니다");
        }
    }
}
