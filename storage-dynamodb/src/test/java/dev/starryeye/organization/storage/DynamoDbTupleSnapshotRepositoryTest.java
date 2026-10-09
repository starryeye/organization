package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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

    /** 실제 아이디처럼 UUID 로 만든 튜플. 압축이 잘 안 되는 모양이라 묶음이 여럿 생긴다. */
    private static Set<RelationTuple> UUID_튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember(
                        UUID.nameUUIDFromBytes(("user" + i).getBytes(StandardCharsets.UTF_8)).toString(),
                        UUID.nameUUIDFromBytes(("group" + (i % 1_000)).getBytes(StandardCharsets.UTF_8)).toString()))
                .collect(Collectors.toSet());
    }

    /** 묶음을 작게(바이트) 잘라 작은 스냅샷도 여러 묶음이 되게 하는 저장소. */
    private DynamoDbTupleSnapshotRepository 작은_묶음_저장소(int chunkSize) {
        return new DynamoDbTupleSnapshotRepository(client, properties, Clock.fixed(지금, ZoneOffset.UTC), chunkSize);
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
    @DisplayName("튜플 120개도 묶음 하나로 저장되고 전부 복원된다")
    void 튜플이_많아도_전부_복원된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(120));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
    }

    @Test
    @DisplayName("UUID 튜플 4만 줄은 묶음 여럿으로 나뉘어 저장되고 그대로 복원된다 — 묶음마다 PutItem 하나다")
    void 여러_묶음으로_나뉘어도_그대로_복원된다() {
        // given
        var tuples = UUID_튜플들(40_000);
        WriteCounter writes = new WriteCounter();
        var 세는_저장소 = new DynamoDbTupleSnapshotRepository(writes.wrap(client), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        세는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, tuples)).block();
        var latest = repository.findLatest().block();

        // then — 메타의 묶음 수 = 묶음 아이템 수 > 1, 번호는 0부터 이어지고, 묶음마다 350,000바이트 이하, 튜플 줄은 없다
        var items = 파티션("20260814T030000-LDAP");
        var 묶음_키 = 묶음_키들(items);
        assertThat(latest.tuples()).isEqualTo(tuples);
        assertThat(묶음_키).hasSizeGreaterThan(1);
        assertThat(메타(items).get("chunkCount").n()).isEqualTo(String.valueOf(묶음_키.size()));
        assertThat(묶음_키).isEqualTo(IntStream.range(0, 묶음_키.size()).mapToObj(Keys::chunkSk).toList());
        assertThat(items).filteredOn(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .allSatisfy(item -> assertThat(item.get("data").b().asByteArray().length).isLessThanOrEqualTo(350_000));
        assertThat(items).noneMatch(item -> Keys.isTupleSk(item.get(Keys.SK).s()));

        // and — 메타 1 + 묶음마다 1 + 포인터 1. 묶음을 BatchWriteItem 에 담지 않는다(spec §3.3)
        assertThat(writes.puts()).isEqualTo(묶음_키.size() + 2);
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

    /** 이 저장소가 쓴 한 스냅샷 파티션의 원본 아이템(강한 일관성, 끝까지). 정렬키 순서다. */
    private List<Map<String, AttributeValue>> 파티션(String snapshotId) {
        return Paginator.queryAll(client, QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshotId))))
                        .consistentRead(true)
                        .build())
                .collectList().block();
    }

    private static List<String> 묶음_키들(List<Map<String, AttributeValue>> items) {
        return items.stream().map(item -> item.get(Keys.SK).s()).filter(Keys::isChunkSk).toList();
    }

    private static Map<String, AttributeValue> 메타(List<Map<String, AttributeValue>> items) {
        return items.stream().filter(item -> Keys.META.equals(item.get(Keys.SK).s())).findFirst().orElseThrow();
    }

    private void 아이템을_쓴다(Map<String, AttributeValue> item) {
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(item).build()).join();
    }

    /** 메타를 읽어 고친 뒤 통째로 다시 쓴다. */
    private void 메타를_고친다(String snapshotId, Consumer<Map<String, AttributeValue>> 고치기) {
        Map<String, AttributeValue> meta = new HashMap<>(메타(파티션(snapshotId)));
        고치기.accept(meta);
        아이템을_쓴다(meta);
    }

    /** 묶음을 지우고, 메타의 묶음 수를 빼고, 튜플 줄을 아이템 하나씩 쓴다 — 이 설계(2026-10-09) 전의 저장 모양이다. */
    private void 옛_형식으로_바꾼다(String snapshotId, Set<RelationTuple> tuples) {
        묶음_키들(파티션(snapshotId)).forEach(sk -> 아이템을_지운다(snapshotId, sk));
        메타를_고친다(snapshotId, meta -> meta.remove("chunkCount"));
        tuples.forEach(tuple -> 아이템을_쓴다(Map.of(
                Keys.PK, AttributeValue.fromS(Keys.snapshotPk(snapshotId)),
                Keys.SK, AttributeValue.fromS(Keys.tupleSk(tuple)))));
    }

    /** 두 번째 묶음(정렬키 CHUNK#) PutItem 만 실패시키고 나머지는 진짜 클라이언트로 보낸다. */
    private DynamoDbAsyncClient 두번째_묶음_쓰기가_실패하는_클라이언트() {
        AtomicInteger 묶음_쓰기_수 = new AtomicInteger();
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putItem") && args != null && args[0] instanceof PutItemRequest request
                            && Keys.isChunkSk(request.item().get(Keys.SK).s())
                            && 묶음_쓰기_수.incrementAndGet() == 2) {
                        return CompletableFuture.failedFuture(new IllegalStateException("묶음 쓰기 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
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

        // then — 메타 1 + 묶음 1, 아무도 expiresAt 을 갖지 않고 메타만 보관 기한을 갖는다
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotContainKey(Keys.EXPIRES_AT));
        assertThat(items).filteredOn(item -> Keys.META.equals(item.get(Keys.SK).s()))
                .singleElement()
                .satisfies(meta -> assertThat(meta.get("retainUntil").n())
                        .isEqualTo(String.valueOf(지금.plusSeconds(7 * 86400).getEpochSecond())));
    }

    @Test
    @DisplayName("메타를 먼저 쓴다 — 묶음 쓰기가 도중에 실패해도 메타가 있어 정리 대상이고, 포인터는 직전 스냅샷 그대로다")
    void 메타를_먼저_쓴다() {
        // given — 직전 스냅샷 S1 이 최신이다. S2 를 묶음 여럿으로 저장하다 두 번째 묶음 쓰기가 실패한다
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        var 실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
                두번째_묶음_쓰기가_실패하는_클라이언트(), properties, Clock.fixed(지금, ZoneOffset.UTC), 16);

        // when
        assertThatThrownBy(() -> 실패하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block());

        // then — 묶음은 차례로 하나씩 가므로 첫 묶음만 있다. 메타는 목록에 있고(정리 작업이 찾는다), 포인터는 S1 이다
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .contains("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");

        // and — 보존 기간 뒤 정리하면 반쪽 S2 는 메타·묶음까지 지워지고 최신 S1 은 남는다
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
                .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
    }

    @Test
    @DisplayName("풀어서 센 튜플 수가 메타와 다르면 오류다 — 반쪽 기준선으로 삭제를 놓치지 않는다")
    void 튜플_수가_다르면_오류다() {
        // given — 메타는 튜플 5개라는데 본문에는 4개다
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();
        메타를_고친다("20260814T030000-LDAP", meta -> meta.put("tupleCount", AttributeValue.fromN("5")));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 튜플 5 · 읽음 4");
    }

    @Test
    @DisplayName("묶음 하나가 빠지면 오류다 — 반쪽 본문으로 기준선을 만들지 않는다")
    void 묶음이_빠지면_오류다() {
        // given — 묶음 여럿인 스냅샷에서 두 번째 묶음이 사라졌다
        작은_묶음_저장소(16).save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block();
        int 묶음_수 = 묶음_키들(파티션("20260814T030000-LDAP")).size();
        assertThat(묶음_수).isGreaterThan(2);
        아이템을_지운다("20260814T030000-LDAP", Keys.chunkSk(1));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 묶음 %d · 읽음 %d".formatted(묶음_수, 묶음_수 - 1))
                .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
    }

    @Test
    @DisplayName("묶음 번호가 0부터 이어지지 않으면 오류다")
    void 묶음_번호가_건너뛰면_오류다() {
        // given — 마지막 묶음이 다른 번호로 옮겨졌다(묶음 수는 그대로)
        작은_묶음_저장소(16).save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block();
        var 묶음들 = 파티션("20260814T030000-LDAP").stream()
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .toList();
        int 마지막 = 묶음들.size() - 1;
        Map<String, AttributeValue> 옮긴_묶음 = new HashMap<>(묶음들.get(마지막));
        옮긴_묶음.put(Keys.SK, AttributeValue.fromS(Keys.chunkSk(9_999)));
        아이템을_쓴다(옮긴_묶음);
        아이템을_지운다("20260814T030000-LDAP", Keys.chunkSk(마지막));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("묶음 번호가 %d 에서 끊긴다".formatted(마지막));
    }

    @Test
    @DisplayName("본문이 깨지면 오류다")
    void 본문이_깨지면_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        Map<String, AttributeValue> 묶음 = new HashMap<>(파티션("20260814T030000-LDAP").stream()
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .findFirst().orElseThrow());
        묶음.put("data", AttributeValue.fromB(SdkBytes.fromUtf8String("깨진 본문")));
        아이템을_쓴다(묶음);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("본문을 풀 수 없다");
    }

    @Test
    @DisplayName("옛 형식(튜플 한 줄이 아이템 하나) 스냅샷은 오류다 — 이관하지 않고 재적재로 고친다")
    void 옛_형식은_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        옛_형식으로_바꾼다("20260814T030000-LDAP", 튜플들(3));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("옛 형식")
                .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
    }

    @Test
    @DisplayName("옛 형식 스냅샷도 기한이 지나면 정리되고 파티션에 아무것도 남지 않는다")
    void 옛_형식도_정리된다() {
        // given — 10일 전 옛 형식 스냅샷(보존 7일 지남)과 오늘 스냅샷(최신)
        repository.saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(3))).block();
        옛_형식으로_바꾼다("20260804T030000-LDAP", 튜플들(3));
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(2))).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(파티션("20260804T030000-LDAP")).isEmpty();
        assertThat(repository.findLatest().block().tuples()).hasSize(2);
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
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
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
        assertThat(파티션("20260806T030000-LDAP")).hasSize(2);
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
        // given — 만료된 후보(묶음 25개 넘게 → 지우기 배치 둘 이상)와, 정리 대상이 아닌 최신 스냅샷을 따로 둔다
        작은_묶음_저장소(4).saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(60))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(1))).block();
        assertThat(묶음_키들(파티션("20260804T030000-LDAP"))).hasSizeGreaterThan(BatchRequests.WRITE_LIMIT);

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

    // ---------- 기록 중 표시 (설계 2026-09-30 §4.1) ----------

    @Test
    @DisplayName("아무 일도 없었으면 기록 중이 아니다")
    void 처음엔_기록_중이_아니다() {
        // when, then
        assertThat(repository.isWriting().block()).isFalse();
    }

    @Test
    @DisplayName("기록 중 표시를 남기면 isWriting 이 참이고, 기준선은 그대로다")
    void 기록_중_표시를_남긴다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        repository.markWriting().block();

        // then
        assertThat(repository.isWriting().block()).isTrue();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }

    @Test
    @DisplayName("스냅샷을 저장하면 기록 중 표시가 사라진다 — 포인터를 통째로 새로 쓴다")
    void 저장하면_표시가_사라진다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.markWriting().block();

        // when
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // then
        assertThat(repository.isWriting().block()).isFalse();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }

    @Test
    @DisplayName("포인터가 없어도 표시만 남길 수 있고, 그때 기준선은 '없음'이며 정리도 아무것도 지우지 않는다")
    void 첫_설치에서도_표시를_남긴다() {
        // when
        repository.markWriting().block();

        // then
        assertThat(repository.isWriting().block()).isTrue();
        assertThat(repository.findLatest().blockOptional()).isEmpty();
        assertThat(repository.purgeExpired().block()).isZero();
    }
}
