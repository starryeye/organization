package dev.starryeye.organization.admin.fixture;

import dev.starryeye.organization.admin.SyncRunResponse;
import org.awaitility.Awaitility;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Objects;

/**
 * 관리 API 로 동기화·재적재를 걸고 끝날 때까지 기다린다 — 두 앱의 테스트가 함께 쓴다.
 *
 * <p>작업은 요청과 떼어 돈다(설계 2026-09-29 §4). {@code POST} 는 곧바로 202 와 실행 기록(RUNNING)을 주고, 결과는
 * {@code GET /admin/sync/runs/{runId}} 로 본다. 옛 테스트는 {@code POST} 응답 본문에서 결과를 읽었으므로, {@link #끝까지}는 끝난
 * 기록의 본문을 같은 모양({@link WebTestClient.BodyContentSpec})으로 돌려준다 — 뒤에 이어진 {@code jsonPath} 단언을 그대로 쓴다.
 */
public final class SyncJobClient {

    private static final Duration 기본_대기 = Duration.ofMinutes(20);
    private static final Duration 확인_간격 = Duration.ofMillis(200);

    private SyncJobClient() {
    }

    /** 걸고, 끝날 때까지 기다린 뒤, 끝난 기록의 본문을 준다. */
    public static WebTestClient.BodyContentSpec 끝까지(WebTestClient client, String uri) {
        String runId = 건다(client, uri).runId();
        기다린다(client, runId, 기본_대기);
        return client.get().uri("/admin/sync/runs/{runId}", runId).exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    /** 걸기만 한다 — 202 와 RUNNING 기록을 확인하고 돌려준다. */
    public static SyncRunResponse 건다(WebTestClient client, String uri) {
        SyncRunResponse started = client.post().uri(uri).exchange()
                .expectStatus().isAccepted()
                .expectBody(SyncRunResponse.class)
                .returnResult().getResponseBody();
        Objects.requireNonNull(started, "202 응답에 실행 기록이 없다");
        if (!"RUNNING".equals(started.status())) {
            throw new AssertionError("202 로 받은 기록은 RUNNING 이어야 한다: " + started);
        }
        return started;
    }

    /** 기록이 RUNNING 을 벗어날 때까지 본다. 끝난 기록을 준다. */
    public static SyncRunResponse 기다린다(WebTestClient client, String runId, Duration 최대) {
        return Awaitility.await()
                .atMost(최대)
                .pollInterval(확인_간격)
                .until(() -> 조회한다(client, runId), run -> !"RUNNING".equals(run.status()));
    }

    /** {@code GET /admin/sync/runs/{runId}} — 200 이어야 한다. */
    public static SyncRunResponse 조회한다(WebTestClient client, String runId) {
        return client.get().uri("/admin/sync/runs/{runId}", runId).exchange()
                .expectStatus().isOk()
                .expectBody(SyncRunResponse.class)
                .returnResult().getResponseBody();
    }
}
