package dev.starryeye.organization.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기동 때 OpenFGA 설정을 검사한다(설계 2026-10-28 §7, A11·A12). 키를 빠뜨린 운영이 익명으로 OpenFGA 를 부르거나, 풀리지 않은 자리표시자가
 * 그대로 키가 되거나, 키가 평문으로 다니는 일을 막는다. 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은 자리표시자를 무시하고
 * {@code "${OPENFGA_API_TOKEN}"} 문자열을 그대로 넣으므로 명시적으로 본다. 컨테이너가 필요 없다.
 */
class OpenFgaSettingsCheckTest {

    private static final String 키_문구 = "openfga.require-api-token 인데 openfga.api-token 이 비었거나 풀리지 않은 자리표시자다"
            + " — 환경 변수 OPENFGA_API_TOKEN 을 준다";

    private static OpenFgaProperties 설정() {
        var properties = new OpenFgaProperties();
        properties.setStoreName("organization");
        properties.setApiUrl("https://openfga.example.com");
        properties.setApiToken("real-key-value");
        return properties;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("키를 요구하는데 null·빈 문자열·빈칸이면 멈추고, 오류 문구에 값을 싣지 않는다")
    void 키를_요구하는데_비면_멈추고_값을_싣지_않는다(String 키) {
        // given
        var properties = 설정();
        properties.setRequireApiToken(true);
        properties.setApiToken(키);

        // when, then
        assertThatThrownBy(() -> OpenFgaSettingsCheck.검사한다(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(키_문구);
    }

    @Test
    @DisplayName("풀리지 않은 자리표시자는 멈추고, 오류 문구에 그 값을 싣지 않는다")
    void 풀리지_않은_자리표시자는_멈춘다() {
        // given — ${OPENFGA_API_TOKEN} 에 환경 변수가 없으면 바인딩이 이 문자열을 그대로 넣는다
        var properties = 설정();
        properties.setRequireApiToken(true);
        properties.setApiToken("${OPENFGA_API_TOKEN}");

        // when, then
        assertThatThrownBy(() -> OpenFgaSettingsCheck.검사한다(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(키_문구)
                .message().doesNotContain("${OPENFGA_API_TOKEN}");
    }

    // 이름에 값을 싣지 않는다 — 시험 보고서에 키가(제어문자까지) 찍히지 않게
    @ParameterizedTest(name = "[{index}] 눈에 보이는 ASCII 밖의 글자가 든 키")
    @ValueSource(strings = {"s3cret-value\n", "s3cret-value\r\n", " s3cret-value", "s3cret value", "s3cret\tvalue",
            "s3cret\u0000value", "비밀-키-값"})
    @DisplayName("키에 눈에 보이는 ASCII 말고 다른 글자(줄바꿈·공백·제어문자·한글)가 있으면 멈추고, 오류 문구에 값을 싣지 않는다")
    void 키에_눈에_보이는_ASCII_말고_다른_글자가_있으면_멈추고_값을_싣지_않는다(String 키) {
        // given — 비밀 파일을 $(cat key) 로 읽으면 끝에 줄바꿈이 붙는다. JDK HTTP 클라이언트는 이런 헤더 값을 거절하며
        // "invalid header value: \"Bearer <키>\"" 로 키를 오류 문구에 싣는다 — 요청이 나가기 전에 여기서 막는다
        var 요구함 = 설정();
        요구함.setRequireApiToken(true);
        요구함.setApiToken(키);
        var 요구하지_않음 = 설정();
        요구하지_않음.setApiToken(키);

        // when, then — 키를 요구하든 않든 키가 있으면 모양을 본다
        for (var properties : new OpenFgaProperties[]{요구함, 요구하지_않음}) {
            assertThatThrownBy(() -> OpenFgaSettingsCheck.검사한다(properties))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("openfga.api-token 에 공백·제어문자·ASCII 밖 글자가 있다 — 키 끝에 줄바꿈이 붙지 않았는지 본다");
        }
    }

    @Test
    @DisplayName("빈칸뿐인 키는 익명이라 모양을 보지 않는다 — 키를 요구하지 않는 로컬")
    void 빈칸뿐인_키는_요구하지_않으면_통과한다() {
        // given — 클라이언트에는 키로 실리지 않는다(StoreBootstrapper.newClient)
        var properties = 설정();
        properties.setApiToken("   ");

        // when, then
        assertThatCode(() -> OpenFgaSettingsCheck.검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키를 요구하고 키가 있으면 통과한다")
    void 키를_요구하고_키가_있으면_통과한다() {
        // given
        var properties = 설정();
        properties.setRequireApiToken(true);

        // when, then
        assertThatCode(() -> OpenFgaSettingsCheck.검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키를 요구하지 않으면(기본값) 키가 없어도 통과한다 — 키 없는 로컬 OpenFGA")
    void 키_요구는_기본이_꺼져_있다() {
        // given
        var properties = 설정();
        properties.setApiToken(null);

        // when, then
        assertThatCode(() -> OpenFgaSettingsCheck.검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하면 https:// 만 받는다 — 키가 평문으로 다니지 않는다")
    void TLS_를_요구하면_https_만_받는다() {
        // given
        var 평문 = 설정();
        평문.setRequireTls(true);
        평문.setApiUrl("http://openfga.example.com:8080");
        var 암호화 = 설정();
        암호화.setRequireTls(true);
        암호화.setApiUrl("https://openfga.example.com");

        // when, then
        assertThatThrownBy(() -> OpenFgaSettingsCheck.검사한다(평문))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("openfga.require-tls 인데 openfga.api-url 이 https:// 가 아니다");
        assertThatCode(() -> OpenFgaSettingsCheck.검사한다(암호화)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TLS 를 요구하지 않으면(기본값) http:// 도 받는다 — 로컬 개발")
    void TLS_요구는_기본이_꺼져_있다() {
        // given
        var properties = 설정();
        properties.setApiUrl("http://localhost:8080");

        // when, then
        assertThatCode(() -> OpenFgaSettingsCheck.검사한다(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("부트스트래퍼는 만들기 전에 설정을 검사한다 — 풀리지 않은 키로 OpenFGA 에 붙지 않는다")
    void 부트스트래퍼는_만들기_전에_설정을_검사한다() {
        // given
        var properties = 설정();
        properties.setRequireApiToken(true);
        properties.setApiToken("${OPENFGA_API_TOKEN}");

        // when, then
        assertThatThrownBy(() -> new StoreBootstrapper(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(키_문구);
    }
}
