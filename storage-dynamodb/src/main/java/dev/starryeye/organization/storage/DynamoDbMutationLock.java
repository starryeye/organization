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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
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

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;
    /** 누가 쥐고 있는지 로그로 알아보기 위한 값. 배제 판단에는 쓰지 않는다. */
    private final String holderId;

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return acquire(purpose, UUID.randomUUID().toString());
    }

    /**
     * 정한 토큰으로 잡는다. <b>같은 토큰이면 이미 있는 줄도 다시 써서 성공한다</b>(설계 2026-10-02 §3.4, 점검 S14) — SDK 는 응답을 잃은 PutItem 을
     * 같은 요청(같은 토큰)으로 다시 보내는데, 조건에 이 경우가 없으면 서버에서 이미 성공한 자기 락에 막혀 30초 동안 모든 쓰기가 503 이 된다.
     * 앱 쪽 재시도(획득 대기)도 같은 토큰을 쓴다 — {@link #acquire(LockPurpose)} 가 토큰을 한 번 만들고, 재시도는 같은 {@code Mono} 를 다시
     * 구독한다. 같은 토큰은 자기 락만 맞추므로 남의 락을 가져가지 않고, "응답만 잃은" 경우의 보호가 앱 재시도까지 넓어진다.
     */
    Mono<LockLease> acquire(LockPurpose purpose, String token) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.getLockTtl());

            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.LOCK_PK));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(TOKEN, Attrs.s(token));
            item.put(HOLDER, Attrs.s(holderId));
            item.put(PURPOSE, Attrs.s(purpose.name()));
            item.put(Keys.EXPIRES_AT, Attrs.n(expiresAt.getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            // 아무도 없거나, 있어도 이미 만료됐거나, 내 토큰이면(응답을 잃은 재시도) 가져간다
                            .conditionExpression("attribute_not_exists(#pk) OR #expiresAt < :now OR #token = :token")
                            .expressionAttributeNames(Map.of(
                                    "#pk", Keys.PK, "#expiresAt", Keys.EXPIRES_AT, "#token", TOKEN))
                            .expressionAttributeValues(Map.of(
                                    ":now", Attrs.n(now.getEpochSecond()), ":token", Attrs.s(token)))
                            // 조건이 깨지면 쥐고 있는 쪽의 줄을 돌려받는다 — 그 용도로 기다릴 시간을 정한다(설계 2026-10-05 §3.2)
                            .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                            .build()))
                    .thenReturn(new LockLease(token, expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            LockUnavailableException.잡혀_있다(쥔_용도(error)));
        });
    }

    /** 조건 실패 때 DynamoDB 가 돌려준 기존 락 항목의 용도. 돌려받지 못했거나 알 수 없는 값(문자열이 아닌 속성 포함)이면 null(쓰기 경합으로 본다). */
    static LockPurpose 쥔_용도(ConditionalCheckFailedException error) {
        if (!error.hasItem()) {
            return null;
        }
        AttributeValue 용도 = error.item().get(PURPOSE);
        // 문자열이 아닌 속성이면 s() 가 null 이다 — valueOf(null) 이 NPE 를 낸다
        if (용도 == null || 용도.s() == null) {
            return null;
        }
        try {
            return LockPurpose.valueOf(용도.s());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
                            .expressionAttributeNames(Map.of("#expiresAt", Keys.EXPIRES_AT, "#token", TOKEN))
                            .expressionAttributeValues(Map.of(
                                    ":expiresAt", Attrs.n(expiresAt.getEpochSecond()),
                                    ":token", Attrs.s(lease.token())))
                            .build()))
                    .thenReturn(new LockLease(lease.token(), expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("변경 락 리스를 잃었습니다"));
        });
    }

    /**
     * 강한 일관성으로 읽는다 — 방금 잡힌 락을 못 보면 재적재 도중에 아카이빙이 돈다. 만료된 줄은 테이블 TTL 이 지울 때까지 남아 있으므로
     * {@code expiresAt} 을 직접 본다(획득 조건과 같은 기준: 만료 시각이 지금보다 앞이면 빈 락).
     */
    @Override
    public Mono<LockPurpose> peek() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item())
                .filter(item -> Attrs.longValue(item, Keys.EXPIRES_AT) >= clock.instant().getEpochSecond())
                .map(item -> LockPurpose.valueOf(Attrs.str(item, PURPOSE)));
    }
}
