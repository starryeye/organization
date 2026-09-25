package dev.starryeye.organization.scim.app;

import org.springframework.beans.factory.config.BeanPostProcessor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DynamoDB 클라이언트 빈을 감싸 Query 가 훑은 아이템 수와 GetItem 횟수를 센다 (S-1 설계 §8.4).
 *
 * <p>성능 주장을 시간이 아니라 <b>읽은 양</b>으로 단정하기 위한 계측이다 — DynamoDB Local 의 속도는 AWS 와
 * 달라 시간으로는 아무것도 증명하지 못한다. {@code @Import} 로 테스트 컨텍스트에만 들어간다.
 */
class DynamoDbReadCounter implements BeanPostProcessor {

    final AtomicLong queries = new AtomicLong();
    final AtomicLong scannedItems = new AtomicLong();
    final AtomicLong getItems = new AtomicLong();

    void reset() {
        queries.set(0);
        scannedItems.set(0);
        getItems.set(0);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DynamoDbAsyncClient client)) {
            return bean;
        }
        return Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("query") && result instanceof CompletableFuture<?> future) {
                        return future.thenApply(response -> {
                            queries.incrementAndGet();
                            scannedItems.addAndGet(((QueryResponse) response).scannedCount());
                            return response;
                        });
                    }
                    if (method.getName().equals("getItem")) {
                        getItems.incrementAndGet();
                    }
                    return result;
                });
    }
}
