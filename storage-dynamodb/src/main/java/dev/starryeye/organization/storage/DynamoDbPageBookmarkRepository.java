package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.PageBookmarkRepository;
import dev.starryeye.organization.core.query.ListingKind;
import dev.starryeye.organization.core.query.PageBookmark;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * SCIM 목록 책갈피 (S-1 설계 §5.3). 아이템 하나가 책갈피 하나다 — PK {@code PAGE#<종류>#<방향>},
 * SK {@code START#<startIndex>}.
 *
 * <p>만료는 테이블 TTL({@link Keys#EXPIRES_AT})이 치우지만 그 삭제는 늦게 일어난다. 그래서 읽을 때 만료
 * 시각을 직접 보고, 지났으면 없는 것으로 본다.
 */
@RequiredArgsConstructor
public class DynamoDbPageBookmarkRepository implements PageBookmarkRepository {

    /** IdP 가 다음 페이지를 이 안에 부르지 않으면 처음부터 건너뛰어 읽는다. 느릴 뿐 틀리지 않는다. */
    static final Duration TTL = Duration.ofMinutes(15);

    private static final String POSITION = "position";
    private static final String TOTAL_RESULTS = "totalResults";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    /**
     * <b>강한 일관성으로 읽는다</b> — 바로 전 요청(다른 인스턴스일 수 있다)이 쓴 책갈피를 놓치면 건너뛰기로
     * 떨어진다. 틀리지는 않지만 페이지당 비용이 전원 수로 뛴다.
     */
    @Override
    public Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(key(kind, descending, startIndex))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(GetItemResponse::item)
                .filter(item -> Attrs.longValue(item, Keys.EXPIRES_AT) > clock.instant().getEpochSecond())
                .map(item -> new PageBookmark(Attrs.str(item, POSITION), Attrs.longValue(item, TOTAL_RESULTS)));
    }

    @Override
    public Mono<Void> save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark) {
        Map<String, AttributeValue> item = new HashMap<>(key(kind, descending, startIndex));
        item.put(POSITION, Attrs.s(bookmark.position()));
        item.put(TOTAL_RESULTS, Attrs.n(bookmark.totalResults()));
        item.put(Keys.EXPIRES_AT, Attrs.n(clock.instant().plus(TTL).getEpochSecond()));
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item)
                        .build()))
                .then();
    }

    private static Map<String, AttributeValue> key(ListingKind kind, boolean descending, long startIndex) {
        return Map.of(
                Keys.PK, Attrs.s(Keys.pagePk(kind.name(), descending)),
                Keys.SK, Attrs.s(Keys.pageSk(startIndex)));
    }
}
