package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbTupleSnapshotRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private DynamoDbTupleSnapshotRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        properties.setSnapshotRetentionDays(7);
        repository = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금, ZoneOffset.UTC));
    }

    private static TupleSnapshot 스냅샷(String id, Instant at, Set<RelationTuple> tuples) {
        return new TupleSnapshot(id, at, SyncSource.LDAP, tuples);
    }

    private static Set<RelationTuple> 튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("저장한 스냅샷이 최신 스냅샷으로 조회된다")
    void 저장한_스냅샷이_최신으로_조회된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(3));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260814T030000-LDAP");
        assertThat(latest.source()).isEqualTo(SyncSource.LDAP);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("스냅샷이 하나도 없으면 최신 조회는 빈 결과를 준다")
    void 스냅샷이_없으면_빈_결과다() {
        // given, when
        var latest = repository.findLatest().block();

        // then
        assertThat(latest).isNull();
    }

    @Test
    @DisplayName("나중에 저장한 스냅샷이 최신 스냅샷을 덮어쓴다")
    void 나중_스냅샷이_최신이_된다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        repository.save(스냅샷("20260815T030000-LDAP", 지금.plusSeconds(86400), 튜플들(5))).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260815T030000-LDAP");
        assertThat(latest.tuples()).hasSize(5);
    }

    @Test
    @DisplayName("DynamoDB 배치 한계인 25건을 넘는 튜플도 나누어 저장되고 전부 복원된다")
    void 배치_한계를_넘는_튜플도_저장된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(120));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).hasSize(120);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("한글 조직코드가 담긴 튜플도 저장 후 그대로 복원된다")
    void 한글_조직코드_튜플도_복원된다() {
        // given
        var tuples = Set.of(RelationTuple.child("백엔드팀", "개발본부"),
                            RelationTuple.directMember("kim", "백엔드팀"));

        // when
        repository.save(스냅샷("20260814T030000-LDAP", 지금, tuples)).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).isEqualTo(tuples);
    }

    @Test
    @DisplayName("아이디로 과거 스냅샷을 직접 조회할 수 있다")
    void 아이디로_과거_스냅샷을_조회한다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var old = repository.findById("20260813T030000-LDAP").block();

        // then
        assertThat(old.tuples()).hasSize(2);
    }

    @Test
    @DisplayName("최근 스냅샷 목록은 최신순으로 나온다")
    void 최근_목록은_최신순이다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var metas = repository.listRecent(7).collectList().block();

        // then
        assertThat(metas).extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP", "20260813T030000-LDAP");
        assertThat(metas.get(0).tupleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 초 안에서도 밀리초 차이가 최근 목록 정렬을 뒤집지 않는다")
    void 같은_초_안에서도_밀리초로_최신순이_유지된다() {
        // given — Instant.toString() 은 나노초가 0 이면 소수점을 생략해서
        // "T03:00:00Z" 와 "T03:00:00.500Z" 를 문자열로 그대로 비교하면 '.'(0x2E) 이
        // 'Z'(0x5A) 보다 작아 더 이른 시각(정각)이 오히려 뒤로 밀린다.
        var 정각스냅샷 = 스냅샷("20260814T030000-LDAP-A", 지금, 튜플들(1));
        var 반초후스냅샷 = 스냅샷("20260814T030000-LDAP-B", 지금.plusMillis(500), 튜플들(1));

        repository.save(정각스냅샷).block();
        repository.save(반초후스냅샷).block();

        // when
        var metas = repository.listRecent(7).collectList().block();

        // then
        assertThat(metas).extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP-B", "20260814T030000-LDAP-A");
    }

    @Test
    @DisplayName("리셋하면 모든 스냅샷과 최신 포인터가 사라진다")
    void 리셋하면_전부_사라진다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        repository.reset().block();

        // then
        assertThat(repository.findLatest().block()).isNull();
        assertThat(repository.listRecent(30).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("보존 기간이 지난 스냅샷만 정리되고 최근 것은 남는다")
    void 만료된_스냅샷만_정리된다() {
        // given — 보존 7일. 10일 전 것은 만료, 오늘 것은 유효
        var 만료된시각 = 지금.minusSeconds(10 * 86400);
        var 만료스냅샷 = new TupleSnapshot("20260804T030000-LDAP", 만료된시각, SyncSource.LDAP, 튜플들(2));
        var 유효스냅샷 = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(4));

        repository.saveWithCreatedAt(만료스냅샷).block();
        repository.save(유효스냅샷).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }

    /** 이 저장소가 쓴 한 스냅샷 파티션의 원본 아이템(강한 일관성). */
    private List<Map<String, AttributeValue>> 파티션(String snapshotId) {
        return client.query(QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshotId))))
                        .consistentRead(true)
                        .build()).join().items();
    }

    private void 아이템을_지운다(String snapshotId, String sk) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, AttributeValue.fromS(Keys.snapshotPk(snapshotId)), Keys.SK, AttributeValue.fromS(sk)))
                .build()).join();
    }

    /** 지정한 호출만 실패시키고 나머지는 진짜 클라이언트로 보낸다. 보낸 Query 요청은 기록한다. */
    private DynamoDbAsyncClient 가로채는_클라이언트(String 실패시킬_메서드, List<QueryRequest> 보낸_Query) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query") && args != null && args[0] instanceof QueryRequest request) {
                        보낸_Query.add(request);
                    }
                    if (method.getName().equals(실패시킬_메서드)) {
                        return CompletableFuture.failedFuture(new IllegalStateException(실패시킬_메서드 + " 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
    }

    @Test
    @DisplayName("최신 스냅샷은 보존 기간이 지나도 정리하지 않는다 — 비교 기준이다")
    void 최신은_보존_기간이_지나도_정리하지_않는다() {
        // given — 8일 동안 변경 없음·가드 중단·실패만 있어 새 스냅샷이 안 쓰였다. 최신 = 8일 전 것(보존 7일)
        var 최신 = new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(4));
        repository.saveWithCreatedAt(최신).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isZero();
        assertThat(repository.findLatest().block().tuples()).hasSize(4);
    }

    @Test
    @DisplayName("보존 기간이 지난 스냅샷 중 최신만 남기고 나머지는 정리한다")
    void 지난_것_중_최신만_남긴다() {
        // given — 10일 전 A, 8일 전 B(최신). 둘 다 보존 기간(7일)을 넘겼다
        repository.saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(2))).block();
        repository.saveWithCreatedAt(new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(3))).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(repository.findById("20260804T030000-LDAP").blockOptional()).isEmpty();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260806T030000-LDAP");
    }

    @Test
    @DisplayName("스냅샷 아이템에는 테이블 TTL(expiresAt)이 없다 — DynamoDB 가 최신을 알아서 지우지 못한다")
    void 스냅샷_아이템에는_TTL이_없다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        var items = 파티션("20260814T030000-LDAP");

        // then — 메타 1 + 튜플 3, 아무도 expiresAt 을 갖지 않고 메타만 보관 기한을 갖는다
        assertThat(items).hasSize(4);
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotContainKey(Keys.EXPIRES_AT));
        assertThat(items).filteredOn(item -> Keys.META.equals(item.get(Keys.SK).s()))
                .singleElement()
                .satisfies(meta -> assertThat(meta.get("retainUntil").n())
                        .isEqualTo(String.valueOf(지금.plusSeconds(7 * 86400).getEpochSecond())));
    }

    @Test
    @DisplayName("메타를 먼저 쓴다 — 튜플 쓰기가 실패해도 메타가 있어 정리 대상이고, 포인터는 직전 스냅샷 그대로다")
    void 메타를_먼저_쓴다() {
        // given — 직전 스냅샷 S1 이 최신이다. S2 를 저장하다 튜플 쓰기(BatchWriteItem)가 실패한다
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        var 실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("batchWriteItem", new ArrayList<>()), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        assertThatThrownBy(() -> 실패하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block());

        // then — S2 의 메타는 목록에 있고(정리 작업이 찾는다), 포인터는 S1 이다
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .contains("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");

        // and — 보존 기간 뒤 정리하면 반쪽 S2 는 지워지고 최신 S1 은 남는다
        var 여드레_뒤 = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금.plusSeconds(8 * 86400), ZoneOffset.UTC));
        assertThat(여드레_뒤.purgeExpired().block()).isEqualTo(1);
        assertThat(파티션("20260814T030000-LDAP")).isEmpty();
        assertThat(여드레_뒤.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");
    }

    @Test
    @DisplayName("포인터는 있는데 그 스냅샷의 메타가 없으면 빈 기준선이 아니라 오류다")
    void 메타가_없으면_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        아이템을_지운다("20260814T030000-LDAP", Keys.META);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("mode=store");
    }

    @Test
    @DisplayName("읽은 튜플 수가 메타와 다르면 오류다 — 반쪽 기준선으로 삭제를 놓치지 않는다")
    void 튜플_수가_다르면_오류다() {
        // given — 튜플 4개 중 하나가 사라졌다
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();
        String 튜플_키 = 파티션("20260814T030000-LDAP").stream()
                .map(item -> item.get(Keys.SK).s())
                .filter(sk -> !Keys.META.equals(sk))
                .findFirst().orElseThrow();
        아이템을_지운다("20260814T030000-LDAP", 튜플_키);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 튜플 4 · 읽음 3");
    }

    @Test
    @DisplayName("튜플이 하나도 없는 스냅샷도 정상 기준선이다")
    void 빈_스냅샷도_정상_기준선이다() {
        // given — 조직도가 비어 튜플이 0개인 회차
        repository.save(스냅샷("20260814T030000-LDAP", 지금, Set.of())).block();

        // when
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260814T030000-LDAP");
        assertThat(latest.tuples()).isEmpty();
    }

    @Test
    @DisplayName("포인터를 못 읽으면 정리는 아무것도 지우지 않고 실패한다 — 최신 없음으로 보고 최신까지 지우지 않는다")
    void 포인터를_못_읽으면_정리하지_않는다() {
        // given — 보존 기간이 지난 최신 하나
        repository.saveWithCreatedAt(new TupleSnapshot("20260806T030000-LDAP", 지금.minusSeconds(8 * 86400), SyncSource.LDAP, 튜플들(2))).block();
        var 포인터가_안_읽히는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("getItem", new ArrayList<>()), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when, then
        assertThatThrownBy(() -> 포인터가_안_읽히는_저장소.purgeExpired().block());
        assertThat(파티션("20260806T030000-LDAP")).hasSize(3);
    }

    @Test
    @DisplayName("스냅샷 파티션은 강한 일관성으로 읽는다 — 저장 직후 읽어도 튜플 수가 모자라 보이지 않는다")
    void 스냅샷_파티션은_강한_일관성으로_읽는다() {
        // given
        List<QueryRequest> 보낸_Query = new ArrayList<>();
        var 기록하는_저장소 = new DynamoDbTupleSnapshotRepository(
                가로채는_클라이언트("없음", 보낸_Query), properties, Clock.fixed(지금, ZoneOffset.UTC));
        기록하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        기록하는_저장소.findLatest().block();

        // then — GSI 가 아닌(본 테이블) Query 는 전부 consistentRead
        assertThat(보낸_Query).filteredOn(request -> request.indexName() == null)
                .isNotEmpty()
                .allSatisfy(request -> assertThat(request.consistentRead()).isTrue());
    }

    @Test
    @DisplayName("삭제 중 튜플 배치 하나가 실패해도 메타는 남아 다음 정리가 다시 찾는다")
    void 삭제_중_실패해도_메타는_남는다() {
        // given — 만료된 후보(튜플 60개 → 배치 3개)와, 정리 대상이 아닌 최신 스냅샷을 따로 둔다
        repository.saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(60))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(1))).block();

        AtomicInteger 배치_호출_수 = new AtomicInteger();
        DynamoDbAsyncClient 두번째_배치만_실패하는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("batchWriteItem") && 배치_호출_수.incrementAndGet() == 2) {
                        return CompletableFuture.failedFuture(new IllegalStateException("batchWriteItem 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
        var 실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
                두번째_배치만_실패하는_클라이언트, properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        assertThatThrownBy(() -> 실패하는_저장소.purgeExpired().block());

        // then — 메타는 목록에 남아 있어 다음 정리가 이 스냅샷을 다시 찾는다
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .contains("20260804T030000-LDAP");

        // and — 정상 클라이언트로 다시 정리하면 남은 조각까지 마저 지운다
        assertThat(repository.purgeExpired().block()).isEqualTo(1);
        assertThat(파티션("20260804T030000-LDAP")).isEmpty();
    }
}
