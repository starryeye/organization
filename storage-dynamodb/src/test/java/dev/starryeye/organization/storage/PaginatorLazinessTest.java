package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 쪽을 이어 읽는 {@link Paginator} 가 아래에서 끌어당긴 만큼만 다음 쪽을 읽는다 — 멤버를 흘려 쓰는 SCIM 응답의 바탕이다(설계 2026-10-06 §4.2). */
class PaginatorLazinessTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("앞의 몇 줄만 받고 멈추면 다음 쪽 Query 를 보내지 않는다")
    void 받은_만큼만_읽는다() {
        // given — 멤버 50명 조직, 쪽 크기 10
        var state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        Set<MemberRef> members = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            members.add(MemberRef.user("u%03d".formatted(i)));
        }
        state.saveGroup(new DirectoryGroup("BIG", null, "큰 조직", members)).block();
        AtomicLong 쿼리 = new AtomicLong();
        DynamoDbAsyncClient 세는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
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
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.groupPk("BIG")), ":prefix", Attrs.s(Keys.MEMBER_PREFIX)))
                .limit(10)
                .build();

        // when
        var 받은것 = Paginator.queryAll(세는_클라이언트, request).take(5).collectList().block();

        // then
        assertThat(받은것).hasSize(5);
        assertThat(쿼리.get()).isEqualTo(1);
    }
}
