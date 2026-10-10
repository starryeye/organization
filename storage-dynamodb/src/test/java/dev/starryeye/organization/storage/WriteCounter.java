package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 클라이언트를 감싸 아이템 쓰기(PutItem·UpdateItem) 수를 센다. "바뀌지 않았으면 쓰지 않는다" 를 호출 수로 단정하기 위한 계측이다 —
 * DynamoDB Local 은 GSI 쓰기 용량을 보여 주지 않으므로, 쓰기 요청이 없다는 것까지가 테스트로 증명할 수 있는 선이다
 * (GSI 설계 §9). 직원·조직 META 는 UpdateItem 으로 쓴다(설계 2026-10-09 §3.1).
 */
final class WriteCounter {

    private final AtomicLong writes = new AtomicLong();

    DynamoDbAsyncClient wrap(DynamoDbAsyncClient real) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putItem") || method.getName().equals("updateItem")) {
                        writes.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    long writes() {
        return writes.get();
    }

    void reset() {
        writes.set(0);
    }
}
