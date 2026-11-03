package dev.starryeye.organization.authz;

/**
 * 기동 때 OpenFGA 설정을 검사한다(설계 2026-10-28 §7, A11·A12). {@link StoreBootstrapper} 를 만들 때 맨 먼저 부른다. 키는 오류 문구에 싣지 않는다.
 *
 * <ul>
 *   <li><b>키.</b> 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은 자리표시자를 무시하고 {@code "${OPENFGA_API_TOKEN}"} 문자열을
 *       그대로 넣는다. 그 값을 키로 실으면 OpenFGA 가 401 을 돌려줄 뿐이라 원인이 가려진다. {@code openfga.require-api-token} 인데 키가 비었거나
 *       <code>&#36;&#123;</code> 로 시작하면 멈춘다.
 *   <li><b>TLS.</b> {@code openfga.require-tls} 인데 {@code openfga.api-url} 이 {@code https://} 가 아니면 멈춘다 — 키가 평문으로 다니지 않는다.
 * </ul>
 *
 * <p>{@code ldap.require-tls}·바인드 비밀번호 검사({@code LdapConfig.설정을_검사한다})와 같은 모양이다.
 */
final class OpenFgaSettingsCheck {

    private OpenFgaSettingsCheck() {
    }

    static void 검사한다(OpenFgaProperties properties) {
        String 키 = properties.getApiToken();
        if (properties.isRequireApiToken() && (키 == null || 키.isBlank() || 키.startsWith("${"))) {
            throw new IllegalStateException("openfga.require-api-token 인데 openfga.api-token 이 비었거나 풀리지 않은 자리표시자다"
                    + " — 환경 변수 OPENFGA_API_TOKEN 을 준다");
        }
        String 주소 = properties.getApiUrl();
        if (properties.isRequireTls() && (주소 == null || !주소.startsWith("https://"))) {
            throw new IllegalStateException("openfga.require-tls 인데 openfga.api-url 이 https:// 가 아니다");
        }
    }
}
