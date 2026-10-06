package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Paginator} 가 소비자의 신호에 맞춰서만 다음 쪽을 읽는다 — 멤버를 흘려 쓰는 SCIM 응답의 바탕이다(설계 2026-10-06 §4.2).
 *
 * <p>두 테스트가 각각 다른 것을 증명한다. 소비자가 <b>취소하면</b> 더 읽지 않는다. 소비자가 <b>멈추기만 해도</b>(취소 없이 더 요청하지 않음)
 * 많아야 한 쪽만 앞서 읽는다 — 느린 클라이언트가 조직 전체를 끌어오지 못한다.
 */
class PaginatorLazinessTest extends DynamoDbTestSupport {

    private final AtomicLong 쿼리 = new AtomicLong();
    private DynamoDbAsyncClient 세는_클라이언트;
    private QueryRequest request;

    @BeforeEach
    void 멤버_쉰_명_조직을_심는다() {
        // 멤버 50명 조직, 쪽 크기 10 — 끝까지 읽으면 Query 가 6번(쪽이 가득 차 끝난 뒤의 빈 쪽 포함) 나간다
        var state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        Set<MemberRef> members = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            members.add(MemberRef.user("u%03d".formatted(i)));
        }
        state.saveGroup(new DirectoryGroup("BIG", null, "큰 조직", members)).block();
        쿼리.set(0);
        세는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        쿼리.incrementAndGet();
                    }
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.groupPk("BIG")), ":prefix", Attrs.s(Keys.MEMBER_PREFIX)))
                .limit(10)
                .build();
    }

    @Test
    @DisplayName("소비자가 앞의 몇 줄만 받고 취소하면 다음 쪽 Query 를 보내지 않는다")
    void 취소하면_더_읽지_않는다() {
        // given — @BeforeEach 가 50명 조직과 Query 를 세는 클라이언트를 준비했다

        // when — 앞의 5줄만 받고 취소한다
        var 받은것 = Paginator.queryAll(세는_클라이언트, request).take(5).collectList().block();

        // then
        assertThat(받은것).hasSize(5);
        assertThat(쿼리.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("소비자가 5줄을 요청하고 취소 없이 멈춰도 많아야 한 쪽만 앞서 읽는다 — 멈춘 클라이언트가 조직 전체를 끌어오지 못한다")
    void 멈춘_소비자는_한_쪽보다_앞서_읽게_하지_않는다() {
        // given — @BeforeEach 가 50명 조직과 Query 를 세는 클라이언트를 준비했다

        // when — 5줄만 요청해 받고 취소하지 않은 채 멈춘다
        StepVerifier.create(Paginator.queryAll(세는_클라이언트, request), 5)
                .expectNextCount(5)
                .thenAwait(Duration.ofMillis(300))
                // then — 첫 쪽(10줄)에 앞선 쪽 하나까지만. 멈춘 소비자가 쪽을 끌어왔다면 6번(전부)이다
                .then(() -> assertThat(쿼리.get()).isLessThanOrEqualTo(2))
                .thenCancel()
                .verify();
    }
}
