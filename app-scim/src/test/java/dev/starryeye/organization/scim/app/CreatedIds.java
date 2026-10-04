package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;

/**
 * POST 응답의 서버 발급 id(설계 2026-10-04 §3.1)를 꺼낸다. 서버가 id 를 정하므로 e2e 테스트는 만든 뒤 응답에서 받아
 * 뒤 요청의 경로·멤버 값과 Check 에 쓴다 — {@code userName}·{@code externalId} 로 아이디를 짐작하지 않는다.
 */
final class CreatedIds {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CreatedIds() {
    }

    /** 201 인지 확인하고 본문의 {@code id} 를 돌려준다. */
    static String 만든_아이디(WebTestClient.ResponseSpec 응답) {
        return 만든_아이디(응답.expectStatus().isCreated().expectBody().returnResult());
    }

    /** 본문을 따로 단정한 뒤({@code expectBody().jsonPath(…).returnResult()}) 그 결과에서 {@code id} 를 꺼낸다. */
    static String 만든_아이디(EntityExchangeResult<byte[]> 결과) {
        return 만든_아이디(new String(결과.getResponseBody(), StandardCharsets.UTF_8));
    }

    /** 201 응답 본문의 {@code id}. 없으면 멈춘다. */
    static String 만든_아이디(String 응답본문) {
        try {
            JsonNode id = JSON.readTree(응답본문).get("id");
            if (id == null || id.asText().isBlank()) {
                throw new IllegalStateException("201 응답에 id 가 없다: " + 응답본문);
            }
            return id.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("201 응답 본문이 JSON 이 아니다: " + 응답본문, e);
        }
    }
}
