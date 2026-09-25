package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

/** 클라이언트를 감싸 GetItem 호출 수를 센다. 전체 교체가 직원·조직마다 저장본을 따로 읽지 않는지 단정한다. */
final class GetCounter {

    private final AtomicLong gets = new AtomicLong();

    DynamoDbAsyncClient wrap(DynamoDbAsyncClient real) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getItem")) {
                        gets.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    long gets() {
        return gets.get();
    }

    void reset() {
        gets.set(0);
    }
}
