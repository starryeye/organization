package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.client.model.ClientReadResponse;
import dev.openfga.sdk.api.configuration.ClientReadOptions;
import dev.openfga.sdk.api.model.ConsistencyPreference;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.TupleKey;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * 장부(OpenFGA store)의 모든 줄을 Read API 로 훑는다 (설계 2026-09-29 §3.2). 재적재만 쓴다.
 *
 * <p><b>이 서버에서 Read 를 부르는 유일한 자리다.</b> 판단·쓰기 경로는 Check·BatchCheck 만 쓴다({@link OpenFgaRelationTupleChecker}).
 * 빈 요청(키 없음)이 장부 전체를 뜻한다 — SDK 0.9.11 은 세 칸이 모두 비면 {@code tuple_key} 를 보내지 않는다.
 *
 * <p>페이지마다 {@value #PAGE_SIZE}줄을 받아 이어받기 토큰이 빌 때까지 넘긴다. 10만 명(약 11만 줄)이면 약 1,100번 부른다. 캐시가 아니라
 * 지금 있는 것을 읽는다({@code HIGHER_CONSISTENCY}) — 방금 쓴 줄이 안 보이면 그 줄을 "없어야 할 줄"로 잘못 고르지는 않지만, 방금 지운
 * 줄이 보이면 헛 지우기를 한다.
 */
@RequiredArgsConstructor
public class OpenFgaRelationTupleScanner implements RelationTupleScanner {

    /** OpenFGA Read 한 페이지의 상한. */
    static final int PAGE_SIZE = 100;

    private final StoreBootstrapper bootstrapper;
    private final OpenFgaProperties properties;

    @Override
    public Flux<RelationTuple> scanAll() {
        return bootstrapper.resolveStore()
                .flatMapMany(storeId -> 읽는다(storeId, null)
                        .expand(page -> 다음이_있다(page)
                                ? 읽는다(storeId, page.getContinuationToken())
                                : Mono.empty()))
                .flatMapIterable(page -> page.getTuples() == null ? List.<Tuple>of() : page.getTuples())
                .map(tuple -> 줄로(tuple.getKey()));
    }

    /**
     * 한 페이지를 읽는다. 실패하면 그 페이지만 쓰기와 같은 정책으로 다시 읽는다 — 약 1,100번 중 한 번의 흔들림으로 재적재 전체가
     * 실패하지 않게. 처음부터 다시 훑지 않는다(이미 흘려 보낸 줄이 두 번 나온다).
     */
    private Mono<ClientReadResponse> 읽는다(String storeId, String continuationToken) {
        return Mono.fromFuture(() -> {
                    try {
                        ClientReadOptions options = new ClientReadOptions()
                                .pageSize(PAGE_SIZE)
                                .consistency(ConsistencyPreference.HIGHER_CONSISTENCY);
                        if (continuationToken != null) {
                            options.continuationToken(continuationToken);
                        }
                        return bootstrapper.clientFor(storeId).read(new ClientReadRequest(), options);
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA read 호출 실패", e);
                    }
                })
                .retryWhen(Retry.backoff(properties.getMaxRetries(), Duration.ofMillis(200)));
    }

    private static boolean 다음이_있다(ClientReadResponse page) {
        String token = page.getContinuationToken();
        return token != null && !token.isBlank();
    }

    private static RelationTuple 줄로(TupleKey key) {
        return new RelationTuple(key.getUser(), key.getRelation(), key.getObject());
    }
}
