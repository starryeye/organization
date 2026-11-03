package dev.starryeye.organization.security;

import com.nimbusds.jose.jwk.JWKSet;
import io.netty.channel.ChannelOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.text.ParseException;
import java.time.Duration;

/**
 * 키 목록(jwk-set-uri)을 받는 클라이언트(설계 2026-10-28 A2, 컨트롤러 판정 — 키 목록 장애는 503). {@link ResourceServerSecurityConfig} 가 부트의 키 목록 디코더에
 * 붙인다. 시험 디코더는 키 목록을 받지 않으므로 무관하다.
 *
 * <ul>
 *   <li><b>시간 한도.</b> 연결 {@link #연결_한도}, 그리고 응답 머리부터 본문 끝까지 {@link #전체_한도}. 리액터 네티 기본값은 연결 30초·응답 무한이고, 읽기 사이 한도
 *       ({@code responseTimeout})는 본문을 조금씩 흘리는 서버를 끊지 못한다. 블랙홀 주소나 멈춘 인가 서버가 토큰이 온 요청마다 그만큼 붙잡지 않게 한다.</li>
 *   <li><b>실패 표시.</b> 키 목록을 받지 못한 것 — 연결 실패, 한도 초과, 2xx 가 아닌 상태, 키 목록으로 해석되지 않는 본문 — 을 모두 이 자리에서
 *       {@link KeySetUnavailableException} 으로 표시한다. 그 표지가 원인 사슬에 있을 때만 503 이다. 그래서 본문을 여기서 끝까지 읽어 해석해 보고, 성공하면 그
 *       본문으로 응답을 다시 만들어 스프링의 키 목록 소스에 넘긴다(키 목록은 작고 캐시되므로 드물게 받는다).</li>
 * </ul>
 */
final class KeySetClient {

    /** 키 목록 주소에 연결하는 한도 */
    static final Duration 연결_한도 = Duration.ofSeconds(2);
    /** 키 목록 응답을 머리부터 본문 끝까지 받는 한도 */
    static final Duration 전체_한도 = Duration.ofSeconds(5);

    private KeySetClient() {
    }

    static WebClient 만든다() {
        HttpClient 연결 = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) 연결_한도.toMillis());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(연결))
                .filter(KeySetClient::받는다)
                .build();
    }

    /** 응답을 본문까지 전체 한도 안에 받아 키 목록인지 본다. 어디서 실패하든 표지로 바꾼다 */
    private static Mono<ClientResponse> 받는다(ClientRequest 요청, ExchangeFunction 다음) {
        return 다음.exchange(요청)
                .flatMap(KeySetClient::키_목록인지_본다)
                .timeout(전체_한도)
                .onErrorMap(오류 -> !(오류 instanceof KeySetUnavailableException),
                        오류 -> new KeySetUnavailableException("키 목록을 받지 못했다 — " + 오류, 오류));
    }

    private static Mono<ClientResponse> 키_목록인지_본다(ClientResponse 응답) {
        if (!응답.statusCode().is2xxSuccessful()) {
            return 응답.releaseBody()
                    .then(Mono.error(new KeySetUnavailableException("키 목록 주소가 상태 " + 응답.statusCode().value() + " 으로 답했다")));
        }
        return 응답.bodyToMono(String.class).defaultIfEmpty("").handle((본문, 싱크) -> {
            try {
                JWKSet.parse(본문);
            } catch (ParseException e) {
                싱크.error(new KeySetUnavailableException("키 목록 주소의 본문이 키 목록으로 해석되지 않는다(" + 본문.length() + "자)", e));
                return;
            }
            // 원래 응답의 본문은 이미 읽었다 — 같은 본문으로 새 응답을 만든다
            싱크.next(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(본문)
                    .build());
        });
    }
}
