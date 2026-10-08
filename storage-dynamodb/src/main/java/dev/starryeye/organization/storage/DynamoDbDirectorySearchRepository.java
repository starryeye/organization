package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * GSI 파티션 안에서 정렬키 접두사로 훑는다.
 *
 * <p>DynamoDB 정렬키로 할 수 있는 것은 정확 일치와 {@code begins_with} 뿐이다.
 * 부분일치는 Scan 이거나 검색엔진이므로 이 계획의 범위 밖이다(설계 §12).
 */
@RequiredArgsConstructor
public class DynamoDbDirectorySearchRepository implements DirectorySearchRepository {

    /** DynamoDB 정렬키의 최대 길이(UTF-8 바이트). 이보다 긴 시작 키는 DynamoDB 가 거절한다. */
    private static final int MAX_SORT_KEY_BYTES = 1024;

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        // GSI1 정렬키는 소문자다(Keys.indexKey) — 접두사도 소문자로 묻는다
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        // GSI2 는 GSI1 과 같은 파티션키 속성(USER_INDEX)을 쓰고 정렬키만 소문자 표시명으로 바꾼 인덱스다(Keys.GSI2PK).
        // 정렬키가 소문자라 접두사도 소문자로 묻는다 — userName·조직명 검색과 같다(점검 S19).
        return query(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK, Keys.USER_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toGroupSummary);
    }

    /**
     * 조직 META 아이템 하나만 집어 온다. {@code Query} 가 아니라 {@code GetItem} 인 것이
     * 핵심이다 — {@code Query(PK=GROUP#code)} 는 같은 파티션의 멤버십 아이템까지 전부
     * 끌어온다.
     */
    @Override
    public Mono<GroupSummary> findGroupSummary(String orgCode) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(orgCode)), Keys.SK, Attrs.s(Keys.META)))
                .projectionExpression("#pk, #displayName")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#displayName", "displayName"))
                .build();

        return Mono.fromFuture(() -> client.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> toGroupSummary(response.item()));
    }

    /** 멤버 목록 커서의 검색 범위 — 조직마다 다르다. 다른 조직의 커서는 {@link Cursor#decode} 가 거절한다. */
    private static String memberScope(String orgCode) {
        return "group-members/" + orgCode;
    }

    @Override
    public Mono<Page<String>> findGroupUserMemberIds(String orgCode, String cursor, int limit) {
        String pk = Keys.groupPk(orgCode);
        String prefix = Keys.memberSkPrefix(MemberType.USER);
        String scope = memberScope(orgCode);
        return Mono.defer(() -> {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(properties.getTableName())
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                    .expressionAttributeValues(Map.of(":pk", Attrs.s(pk), ":prefix", Attrs.s(prefix)))
                    .projectionExpression("#pk, #sk")
                    .consistentRead(true)
                    .limit(limit);
            Map<String, AttributeValue> start = Cursor.decode(scope, cursor);
            if (start != null) {
                request.exclusiveStartKey(시작_키를_확인한다(start, pk, prefix));
            }
            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> new Page<>(
                            response.items().stream()
                                    .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)).id())
                                    .toList(),
                            Cursor.encode(scope, response.lastEvaluatedKey())));
        });
    }

    /**
     * 커서에서 꺼낸 시작 키를 그대로 믿지 않는다 — 범위만 맞춘 위조 커서가 다른 파티션이나 다른 접두를 가리키거나 정렬키 한도(1024바이트)를
     * 넘으면 DynamoDB 가 {@code ValidationException} 을 내 500 이 된다(점검 S16 앞쪽). 이 조직의 직원 멤버 키가 아니면 400 으로 갈 예외다.
     *
     * <p>키 속성이 정확히 {@code PK}·{@code SK} 둘임을 먼저 확인하므로 아래에서 둘 다 꺼낼 수 있고, 값은 {@link Cursor#decode} 가 문자열로만 만든다.
     */
    private static Map<String, AttributeValue> 시작_키를_확인한다(Map<String, AttributeValue> start, String pk, String prefix) {
        if (!start.keySet().equals(Set.of(Keys.PK, Keys.SK))
                || !pk.equals(start.get(Keys.PK).s())
                || !start.get(Keys.SK).s().startsWith(prefix)
                || start.get(Keys.SK).s().getBytes(StandardCharsets.UTF_8).length > MAX_SORT_KEY_BYTES) {
            throw new IllegalArgumentException("이 조직의 멤버 목록 커서가 아니다");
        }
        return start;
    }

    /**
     * 검색 커서에서 꺼낸 시작 키를 그대로 믿지 않는다(설계 2026-10-08 §4, 점검 S16 앞쪽). 범위(인덱스/파티션)만 맞춘 위조 커서나, 검색어를 바꾼 채
     * 다시 보낸 이전 커서는 DynamoDB 가 {@code ValidationException} 으로 거절해 500 이 된다. 이 검색의 시작 키가 아니면 400 으로 갈 예외다.
     *
     * <p>본다: 키 속성이 본 테이블 {@code PK}·{@code SK} 와 인덱스 키 둘로 정확히 넷, 인덱스 파티션키가 이 파티션, 인덱스 정렬키가 이번 접두사로
     * 시작하고 정렬키 한도(1024바이트) 안, {@code PK} 가 종류 접두({@code USER#}/{@code GROUP#})로 시작하고 {@code SK} 가 {@code META}.
     * 값이 문자열인 것은 {@link Cursor#decode} 가 이미 지켰다.
     */
    private static Map<String, AttributeValue> 검색_시작_키를_확인한다(Map<String, AttributeValue> start, String pkName, String skName,
                                                                String partition, String prefix) {
        String 종류_접두 = Keys.USER_INDEX.equals(partition) ? Keys.USER_PREFIX : Keys.GROUP_PREFIX;
        if (!start.keySet().equals(Set.of(Keys.PK, Keys.SK, pkName, skName))
                || !partition.equals(start.get(pkName).s())
                || !start.get(skName).s().startsWith(prefix)
                || start.get(skName).s().getBytes(StandardCharsets.UTF_8).length > MAX_SORT_KEY_BYTES
                || !start.get(Keys.PK).s().startsWith(종류_접두)
                || !Keys.META.equals(start.get(Keys.SK).s())) {
            throw new IllegalArgumentException("이 검색의 커서가 아니다");
        }
        return start;
    }

    @Override
    public Flux<String> findChildOrgCodes(String orgCode) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.groupPk(orgCode)),
                        ":prefix", Attrs.s(Keys.memberSkPrefix(MemberType.GROUP))))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request)
                .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)).id());
    }

    /**
     * {@code Mono.defer} 로 감싸는 이유: {@link Cursor#decode} 는 손상된 커서에서
     * {@link IllegalArgumentException} 을 던지는데, 감싸지 않으면 이 예외가 Mono 를
     * 조립하는 시점(메서드 호출 시점)에 곧바로 튀어나온다. 그러면 {@code Mono.zip} 처럼
     * 여러 Mono 를 조립만 하고 아직 구독하지 않은 코드에서 인자 평가 중에 예외가 터져
     * Reactor 체인에 진입하지도 못한 채 죽는다. {@code defer} 로 감싸면 구독 시점까지
     * 평가가 미뤄져 예외가 정상적인 {@code onError} 신호가 된다.
     *
     * <p>시작 키는 {@link #검색_시작_키를_확인한다} 로 검사한다.
     */
    private <T> Mono<Page<T>> query(String indexName, String pkName, String skName, String partition,
                                    String prefix, String cursor, int limit,
                                    Function<Map<String, AttributeValue>, T> mapper) {
        // 인덱스만이 아니라 파티션까지 커서에 담는다 — GSI1 은 USER_INDEX 와 GROUP_INDEX 를
        // 함께 쓰므로 인덱스 이름만 담으면 두 검색의 커서가 서로 통과해 버린다.
        String scope = indexName + "/" + partition;
        return Mono.defer(() -> {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(properties.getTableName())
                    .indexName(indexName)
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", pkName, "#sk", skName))
                    .expressionAttributeValues(Map.of(
                            ":pk", Attrs.s(partition), ":prefix", Attrs.s(prefix)))
                    .limit(limit);

            Map<String, AttributeValue> start = Cursor.decode(scope, cursor);
            if (start != null) {
                request.exclusiveStartKey(검색_시작_키를_확인한다(start, pkName, skName, partition, prefix));
            }

            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> toPage(scope, response, mapper));
        });
    }

    private <T> Page<T> toPage(String scope, QueryResponse response,
                               Function<Map<String, AttributeValue>, T> mapper) {
        List<T> items = response.items().stream().map(mapper).toList();
        return new Page<>(items, Cursor.encode(scope, response.lastEvaluatedKey()));
    }

    private static UserSummary toUserSummary(Map<String, AttributeValue> item) {
        return new UserSummary(
                Keys.parseUserPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "userName"),
                Attrs.str(item, "displayName"),
                Attrs.flag(item, "active"));
    }

    private static GroupSummary toGroupSummary(Map<String, AttributeValue> item) {
        return new GroupSummary(
                Keys.parseGroupPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "displayName"));
    }
}
