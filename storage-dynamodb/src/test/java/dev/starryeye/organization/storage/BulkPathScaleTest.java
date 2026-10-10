package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대량 경로 두 곳을 실제 규모로 잰다(설계 2026-10-09 §7). 앱 규모 테스트(S18·S19 등)는 5천 명 조직도라, 10만 명의 요청 수와
 * 압축률은 저장소 수준에서 본다. DynamoDB Local 의 시간은 AWS 와 다르다 — 요청 수와 바이트만 믿는다.
 */
@ScaleTest
class BulkPathScaleTest extends DynamoDbTestSupport {

    private static final int 직원_수 = 100_000;
    private static final int 조직_수 = 1_000;

    private static String uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Test
    @DisplayName("직원 10만 명 전체 조회는 GetItem 없이 BatchGet 1,000번으로 읽는다")
    void 전체_조회는_BatchGet_으로_읽는다() {
        // given — 직원 10만, 조직 100(조직마다 직원 10명)
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (int i = 0; i < 직원_수; i++) {
            String id = "u%06d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true));
        }
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (int g = 0; g < 100; g++) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (int m = 0; m < 10; m++) {
                members.add(MemberRef.user("u%06d".formatted(g * 10 + m)));
            }
            groups.put("G%03d".formatted(g), new DirectoryGroup("G%03d".formatted(g), "g" + g, "조직 " + g, members));
        }
        var snapshot = new DirectorySnapshot(users, groups);
        new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC())
                .replaceWith(snapshot).block(Duration.ofMinutes(30));
        GetCounter gets = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(gets.wrap(client), properties, Clock.systemUTC());

        // when
        long 시작 = System.currentTimeMillis();
        var loaded = 세는.loadAll().block(Duration.ofMinutes(10));
        long 걸림 = System.currentTimeMillis() - 시작;

        // then
        System.out.printf("전체 조회: 직원 %,d · 조직 %,d — %,dms, GetItem %,d, BatchGet %,d%n",
                loaded.users().size(), loaded.groups().size(), 걸림, gets.gets(), gets.batchGets());
        assertThat(loaded).isEqualTo(snapshot);
        assertThat(gets.gets()).isZero();
        assertThat(gets.batchGets()).isEqualTo(직원_수 / BatchRequests.GET_LIMIT);
    }

    @Test
    @DisplayName("UUID 아이디 튜플 15만 줄 스냅샷은 압축 묶음 몇 개로 저장되고 그대로 읽힌다")
    void 튜플_15만_줄_스냅샷은_압축_묶음으로_왕복한다() {
        // given — 직원 10만이 조직 1,000곳에 하나씩, 그중 5만 명은 한 곳 더(직원 1인당 소속 약 1.5)
        Set<RelationTuple> tuples = new HashSet<>();
        for (int i = 0; i < 직원_수; i++) {
            tuples.add(RelationTuple.directMember(uuid("user" + i), uuid("group" + (i % 조직_수))));
        }
        for (int i = 0; i < 직원_수 / 2; i++) {
            tuples.add(RelationTuple.directMember(uuid("user" + i), uuid("group" + ((i + 500) % 조직_수))));
        }
        long 원본_바이트 = tuples.stream()
                .mapToLong(tuple -> Keys.tupleSk(tuple).getBytes(StandardCharsets.UTF_8).length + 1)
                .sum();
        WriteCounter writes = new WriteCounter();
        var repository = new DynamoDbTupleSnapshotRepository(writes.wrap(client), properties, Clock.systemUTC());
        var snapshot = new TupleSnapshot("20261009T030000-SCIM", Instant.now(), SyncSource.SCIM, tuples);

        // when
        long 시작 = System.currentTimeMillis();
        repository.save(snapshot).block(Duration.ofMinutes(10));
        long 저장 = System.currentTimeMillis() - 시작;
        시작 = System.currentTimeMillis();
        var latest = repository.findLatest().block(Duration.ofMinutes(10));
        long 읽기 = System.currentTimeMillis() - 시작;

        // then
        List<Map<String, AttributeValue>> 묶음들 = Paginator.queryAll(client, QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshot.id()))))
                        .consistentRead(true)
                        .build())
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .collectList().block();
        long 압축_바이트 = 묶음들.stream().mapToLong(item -> item.get("data").b().asByteArray().length).sum();
        System.out.printf("스냅샷: 튜플 %,d · 원본 %,d바이트 → 압축 %,d바이트(%.1f배) · 묶음 %d — 저장 %,dms(PutItem %d) · 읽기 %,dms%n",
                tuples.size(), 원본_바이트, 압축_바이트, (double) 원본_바이트 / 압축_바이트, 묶음들.size(), 저장, writes.writes(), 읽기);
        assertThat(tuples).hasSize(150_000);
        assertThat(latest.tuples()).isEqualTo(tuples);
        assertThat(writes.writes()).isEqualTo(묶음들.size() + 2);
        assertThat(묶음들.size()).isLessThan((int) (원본_바이트 / DynamoDbTupleSnapshotRepository.CHUNK_SIZE));
    }
}
