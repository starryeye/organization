package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DynamoDB 조건부 쓰기로 만든 전역 리스 락 (설계 §4).
 *
 * <p>새 테이블을 만들지 않는다. 기존 단일 테이블에 아이템 하나로 얹는다 — app-ldap 과
 * app-scim 은 서로 다른 테이블을 쓰므로 락도 자연히 분리된다.
 *
 * <p><b>토큰 조건이 핵심이다.</b> 반납과 갱신에 {@code token} 조건을 걸지 않으면, 내 리스가
 * 만료돼 남이 가져간 뒤에 내가 반납하면서 <b>남의 락을 풀어버린다</b>. 그 순간 두 인스턴스가
 * 동시에 쓴다.
 *
 * <p><b>완벽한 상호 배제가 아니다.</b> GC 정지 등으로 살아있는데 리스가 만료되면 늦은 쓰기가
 * 새어나갈 수 있다. OpenFGA 가 펜싱 토큰을 지원하지 않아 원천 차단이 불가능하다 —
 * 쓰기 직전 리스 재확인과 Check 기준선이 이를 좁힌다 (설계 §4.7).
 */
@Slf4j
@RequiredArgsConstructor
public class DynamoDbMutationLock implements MutationLock {

    private static final String TOKEN = "token";
    private static final String HOLDER = "holder";
    private static final String PURPOSE = "purpose";
    private static final String EXPIRES_AT = "expiresAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;
    /** 누가 쥐고 있는지 로그로 알아보기 위한 값. 배제 판단에는 쓰지 않는다. */
    private final String holderId;

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.getLockTtl());
            String token = UUID.randomUUID().toString();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.LOCK_PK));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(TOKEN, Attrs.s(token));
            item.put(HOLDER, Attrs.s(holderId));
            item.put(PURPOSE, Attrs.s(purpose.name()));
            item.put(EXPIRES_AT, Attrs.n(expiresAt.getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            // 아무도 없거나, 있어도 이미 만료됐으면 가져간다
                            .conditionExpression("attribute_not_exists(#pk) OR #expiresAt < :now")
                            .expressionAttributeNames(Map.of("#pk", Keys.PK, "#expiresAt", EXPIRES_AT))
                            .expressionAttributeValues(Map.of(":now", Attrs.n(now.getEpochSecond())))
                            .build()))
                    .thenReturn(new LockLease(token, expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다"));
        });
    }

    /**
     * 조건이 깨지면 <b>실패시키지 않는다.</b> 이미 만료돼 남이 가져갔다는 뜻인데, 그때 우리가
     * 할 일은 없다 — 작업은 이미 끝났고 응답은 나가야 한다. 대신 경고를 남긴다: TTL 이
     * 작업 시간보다 짧다는 신호다.
     */
    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                        .conditionExpression("#token = :token")
                        .expressionAttributeNames(Map.of("#token", TOKEN))
                        .expressionAttributeValues(Map.of(":token", Attrs.s(lease.token())))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> {
                    log.warn("변경 락 반납 실패 — 이미 리스를 잃은 상태다. TTL({})이 작업 시간보다 짧다는 신호다",
                            properties.getLockTtl());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<LockLease> renew(LockLease lease) {
        return Mono.defer(() -> {
            Instant expiresAt = clock.instant().plus(properties.getLockTtl());
            return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                            .tableName(properties.getTableName())
                            .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                            .updateExpression("SET #expiresAt = :expiresAt")
                            .conditionExpression("#token = :token")
                            .expressionAttributeNames(Map.of("#expiresAt", EXPIRES_AT, "#token", TOKEN))
                            .expressionAttributeValues(Map.of(
                                    ":expiresAt", Attrs.n(expiresAt.getEpochSecond()),
                                    ":token", Attrs.s(lease.token())))
                            .build()))
                    .thenReturn(new LockLease(lease.token(), expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("변경 락 리스를 잃었습니다"));
        });
    }
}
