package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.query.Page;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 변경 이력(설계 2026-10-10 §3·§5). 대상 하나 × 사건 하나가 한 줄이다.
 *
 * <ul>
 *   <li>파티션은 {@code AUDIT#USER#<id>}·{@code AUDIT#GROUP#<id>}, 정렬키는 {@code <시각>#<원인 id>#<조각>} 이다({@link Keys#auditPk}·{@link Keys#auditSk}).</li>
 *   <li>직원·조직 파티션과 따로라 직원·조직을 지워도, {@code wipe} 해도 남는다. GSI 키가 없어 직원·조직 열거에 섞이지 않는다.</li>
 *   <li>줄마다 {@code expiresAt}(기록 시각 + {@code dynamodb.audit-retention-days})을 넣어 테이블 TTL 이 지운다.</li>
 * </ul>
 *
 * <p><b>조각(§3.4).</b> 목록(소속·멤버·튜플)이 아이템 한도(400KB)를 넘으면 한 줄을 조각 몇 개로 나눈다. 앞 조각부터 목록 순서대로 채우고, 조각마다
 * 전체 조각 수를 적는다. 머리(생성·삭제·속성)는 0번 조각에만 싣는다. 읽을 때 조각을 합치고, 조각이 모자란 줄(쓰다 일부만 남음)은 버린다 — 빠질 수는
 * 있어도 틀린 줄은 돌려주지 않는다.
 *
 * <p><b>쓰기(§4.1).</b> 묶음 쓰기({@link BatchRequests} — 25개씩, 처리 못 한 것은 5번까지 다시 보낸다)다. 묶음 하나가 끝내 실패해도 나머지는 계속
 * 쓰고, 실패한 아이템 수를 돌려준다.
 *
 * <p><b>읽기.</b> 한도를 넘는 키의 아이템은 있을 수 없어 부르지 않고 없음이다(설계 2026-10-17 §4) — 실제 DynamoDB 는 그런 키를
 * {@code ValidationException} 으로 거절한다. 대상의 파티션키({@code AUDIT#USER#<id>} 등)를 접두어까지 재서, 넘으면 빈 쪽을 답한다.
 */
@Slf4j
public class DynamoDbAuditLog implements AuditLog {

    /** 조각 하나에 담을 목록 값의 어림 바이트(값의 UTF-8 바이트 + 4). 아이템 한도 400KB 에서 속성 이름·머리 몫을 남긴다. */
    static final int PART_BUDGET = 300_000;

    private static final int WRITE_CONCURRENCY = 8;
    private static final String SCOPE_PREFIX = "AUDIT/";
    /** 한 줄의 0번 조각 정렬키 끝({@link Keys#auditSk}). 다음 쪽 커서는 늘 이 키를 가리킨다. */
    private static final String FIRST_PART_SUFFIX = "#0000";

    private static final String AT = "at";
    private static final String SOURCE = "source";
    private static final String KIND = "kind";
    private static final String CAUSE_ID = "causeId";
    private static final String PART = "part";
    private static final String PARTS = "parts";
    private static final String CREATED = "created";
    private static final String DELETED = "deleted";
    private static final String ATTRIBUTES = "attributes";
    private static final String NAME = "name";
    private static final String BEFORE = "before";
    private static final String AFTER = "after";
    private static final String JOINED = "joined";
    private static final String LEFT = "left";
    private static final String MEMBERS_ADDED = "membersAdded";
    private static final String MEMBERS_REMOVED = "membersRemoved";
    private static final String GRANTED = "granted";
    private static final String REVOKED = "revoked";
    private static final String TUPLE_SEPARATOR = "|";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final BatchRequests batches;
    private final int partBudget;

    public DynamoDbAuditLog(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        this(client, properties, PART_BUDGET);
    }

    /** 조각 한도를 정한다 — 테스트가 작은 한도로 나누기·합치기를 본다. */
    DynamoDbAuditLog(DynamoDbAsyncClient client, DynamoDbProperties properties, int partBudget) {
        this.client = client;
        this.properties = properties;
        this.batches = new BatchRequests(client, properties.getTableName());
        this.partBudget = partBudget;
    }

    @Override
    public Mono<Integer> write(AuditEvent event) {
        long expiresAt = event.at().plus(Duration.ofDays(properties.getAuditRetentionDays())).getEpochSecond();
        return Flux.fromIterable(event.entries())
                .concatMapIterable(entry -> 아이템들(event, entry, expiresAt))
                .map(item -> WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build())
                .buffer(BatchRequests.WRITE_LIMIT)
                .flatMap(batch -> batches.write(batch)
                        .thenReturn(0)
                        .onErrorResume(error -> {
                            log.error("감사 기록 묶음 하나({}줄)를 쓰지 못했다 — 원인 {}", batch.size(), event.cause().id(), error);
                            return Mono.just(batch.size());
                        }), WRITE_CONCURRENCY)
                .reduce(0, Integer::sum);
    }

    /** 한 줄을 조각 아이템으로 — 목록 값을 앞 조각부터 채운다. 머리(생성·삭제·속성)는 0번 조각에만 싣는다. */
    private List<Map<String, AttributeValue>> 아이템들(AuditEvent event, AuditEntry entry, long expiresAt) {
        Map<String, List<String>> 목록 = new LinkedHashMap<>();
        목록.put(JOINED, entry.joined());
        목록.put(LEFT, entry.left());
        목록.put(MEMBERS_ADDED, entry.membersAdded().stream().map(DynamoDbAuditLog::멤버값).toList());
        목록.put(MEMBERS_REMOVED, entry.membersRemoved().stream().map(DynamoDbAuditLog::멤버값).toList());
        목록.put(GRANTED, entry.granted().stream().map(DynamoDbAuditLog::튜플값).toList());
        목록.put(REVOKED, entry.revoked().stream().map(DynamoDbAuditLog::튜플값).toList());

        List<Map<String, List<String>>> 조각들 = new ArrayList<>();
        Map<String, List<String>> 지금 = new LinkedHashMap<>();
        조각들.add(지금);
        int 크기 = 0;
        for (Map.Entry<String, List<String>> 한_목록 : 목록.entrySet()) {
            for (String value : 한_목록.getValue()) {
                int size = value.getBytes(StandardCharsets.UTF_8).length + 4;
                if (크기 > 0 && 크기 + size > partBudget) {
                    지금 = new LinkedHashMap<>();
                    조각들.add(지금);
                    크기 = 0;
                }
                지금.computeIfAbsent(한_목록.getKey(), ignored -> new ArrayList<>()).add(value);
                크기 += size;
            }
        }

        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (int part = 0; part < 조각들.size(); part++) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.auditPk(entry.subject())));
            item.put(Keys.SK, Attrs.s(Keys.auditSk(event.at(), event.cause().id(), part)));
            item.put(AT, Attrs.s(event.at().toString()));
            item.put(SOURCE, Attrs.s(event.cause().source().name()));
            item.put(KIND, Attrs.s(event.cause().kind().name()));
            item.put(CAUSE_ID, Attrs.s(event.cause().id()));
            item.put(PART, Attrs.n(part));
            item.put(PARTS, Attrs.n(조각들.size()));
            item.put(Keys.EXPIRES_AT, Attrs.n(expiresAt));
            if (part == 0) {
                if (entry.created()) {
                    item.put(CREATED, Attrs.bool(true));
                }
                if (entry.deleted()) {
                    item.put(DELETED, Attrs.bool(true));
                }
                if (!entry.attributes().isEmpty()) {
                    item.put(ATTRIBUTES, 속성값(entry.attributes()));
                }
            }
            조각들.get(part).forEach((name, values) -> item.put(name, 문자열_목록(values)));
            items.add(item);
        }
        return items;
    }

    @Override
    public Mono<Page<AuditRecord>> history(MemberRef subject, String cursor, int limit) {
        String pk = Keys.auditPk(subject);
        // 한도를 넘는 파티션키의 이력은 있을 수 없다 — 커서를 보기 전에 빈 쪽으로 답한다(설계 2026-10-17 §4)
        if (!Keys.fitsPartitionKey(pk)) {
            return Mono.just(Page.empty());
        }
        String scope = SCOPE_PREFIX + pk;
        return Mono.defer(() -> {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(properties.getTableName())
                    .keyConditionExpression("#pk = :pk")
                    .expressionAttributeNames(Map.of("#pk", Keys.PK))
                    .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                    .scanIndexForward(false);
            Map<String, AttributeValue> start = Cursor.decode(scope, cursor);
            if (start != null) {
                request.exclusiveStartKey(시작_키를_확인한다(start, pk));
            }
            return Paginator.queryAll(client, request.build())
                    // 역순이라 한 줄의 조각은 번호가 큰 것부터 오고 0번이 마지막이다 — 0번에서 끊으면 쪽 경계가 조각 사이에 걸리지 않는다(§5)
                    .bufferUntil(item -> Attrs.integer(item, PART) == 0)
                    .concatMapIterable(조각들 -> 합친다(subject, 조각들).stream().toList())
                    .take(limit)
                    .collectList()
                    .map(줄들 -> new Page<>(
                            줄들.stream().map(읽은줄::record).toList(),
                            줄들.size() == limit ? Cursor.encode(scope, 줄들.get(줄들.size() - 1).key()) : null));
        });
    }

    /** 읽은 줄과, 다음 쪽이 이어 읽을 그 줄의 0번 조각 키. */
    private record 읽은줄(AuditRecord record, Map<String, AttributeValue> key) {
    }

    /**
     * 한 줄의 조각(역순)을 합친다. 0번 조각과 같은 줄의 조각만 쓰고, 그 수가 0번 조각이 적은 전체 조각 수와 같을 때만 돌려준다 — 앞 줄의 남은 조각이
     * 섞여 있으면(첫 조각을 잃은 줄) 그것은 버린다.
     */
    private Optional<읽은줄> 합친다(MemberRef subject, List<Map<String, AttributeValue>> 조각들) {
        Map<String, AttributeValue> 첫조각 = 조각들.get(조각들.size() - 1);
        String 줄 = Keys.auditRecordKey(Attrs.str(첫조각, Keys.SK));
        List<Map<String, AttributeValue>> 이줄 = new ArrayList<>(조각들.stream()
                .filter(item -> 줄.equals(Keys.auditRecordKey(Attrs.str(item, Keys.SK))))
                .toList());
        if (이줄.size() < 조각들.size()) {
            log.warn("첫 조각을 잃은 감사 기록의 남은 조각 {}개를 건너뛴다: {}", 조각들.size() - 이줄.size(), Attrs.str(첫조각, Keys.PK));
        }
        if (Attrs.integer(첫조각, PART) != 0 || Attrs.integer(첫조각, PARTS) != 이줄.size()) {
            log.warn("조각이 모자란 감사 기록을 건너뛴다: {} {}", Attrs.str(첫조각, Keys.PK), 줄);
            return Optional.empty();
        }
        Collections.reverse(이줄);

        AuditCause cause = new AuditCause(SyncSource.valueOf(Attrs.str(첫조각, SOURCE)),
                AuditKind.valueOf(Attrs.str(첫조각, KIND)), Attrs.str(첫조각, CAUSE_ID));
        AuditEntry entry = new AuditEntry(subject,
                Attrs.flag(첫조각, CREATED),
                Attrs.flag(첫조각, DELETED),
                속성들(첫조각),
                이어붙인다(이줄, JOINED),
                이어붙인다(이줄, LEFT),
                이어붙인다(이줄, MEMBERS_ADDED).stream().map(DynamoDbAuditLog::멤버).toList(),
                이어붙인다(이줄, MEMBERS_REMOVED).stream().map(DynamoDbAuditLog::멤버).toList(),
                이어붙인다(이줄, GRANTED).stream().map(DynamoDbAuditLog::튜플).toList(),
                이어붙인다(이줄, REVOKED).stream().map(DynamoDbAuditLog::튜플).toList());
        AuditRecord record = new AuditRecord(Instant.parse(Attrs.str(첫조각, AT)), cause, entry);
        return Optional.of(new 읽은줄(record, Map.of(Keys.PK, 첫조각.get(Keys.PK), Keys.SK, 첫조각.get(Keys.SK))));
    }

    /**
     * 커서에서 꺼낸 시작 키를 그대로 믿지 않는다(점검 S16 앞쪽과 같은 검사). 범위만 맞춘 위조 커서가 다른 파티션을 가리키거나 정렬키 한도를 넘으면
     * DynamoDB 가 {@code ValidationException} 을 내 500 이 된다. 이 대상의 이력 키가 아니면 400 으로 갈 예외다.
     *
     * <p>키 집합 검사가 먼저다 — 뒤의 {@code start.get(...)} 은 키가 있다는 것에 기댄다. 값이 문자열인 것은 {@link Cursor#decode} 가 지켰다.
     * 우리가 발급하는 커서는 늘 한 줄의 0번 조각 키를 가리키므로 정렬키가 {@code #0000} 으로 끝나야 한다 — 빈 정렬키 같은 어긋난 값을 걸러낸다.
     */
    private static Map<String, AttributeValue> 시작_키를_확인한다(Map<String, AttributeValue> start, String pk) {
        if (!start.keySet().equals(Set.of(Keys.PK, Keys.SK))
                || !pk.equals(start.get(Keys.PK).s())
                || !start.get(Keys.SK).s().endsWith(FIRST_PART_SUFFIX)
                || !Keys.fitsSortKey(start.get(Keys.SK).s())) {
            throw new IllegalArgumentException("이 대상의 이력 커서가 아니다");
        }
        return start;
    }

    private static List<String> 이어붙인다(List<Map<String, AttributeValue>> 조각들, String name) {
        List<String> values = new ArrayList<>();
        for (Map<String, AttributeValue> item : 조각들) {
            AttributeValue list = item.get(name);
            if (list != null && list.hasL()) {
                list.l().forEach(value -> values.add(value.s()));
            }
        }
        return values;
    }

    private static AttributeValue 속성값(List<AttributeChange> changes) {
        return AttributeValue.builder().l(changes.stream().map(change -> {
            Map<String, AttributeValue> map = new HashMap<>();
            map.put(NAME, Attrs.s(change.name()));
            Attrs.putIfPresent(map, BEFORE, change.before());
            Attrs.putIfPresent(map, AFTER, change.after());
            return AttributeValue.builder().m(map).build();
        }).toList()).build();
    }

    private static List<AttributeChange> 속성들(Map<String, AttributeValue> item) {
        AttributeValue list = item.get(ATTRIBUTES);
        if (list == null || !list.hasL()) {
            return List.of();
        }
        return list.l().stream()
                .map(value -> new AttributeChange(Attrs.str(value.m(), NAME), Attrs.str(value.m(), BEFORE),
                        Attrs.str(value.m(), AFTER)))
                .toList();
    }

    private static AttributeValue 문자열_목록(List<String> values) {
        return AttributeValue.builder().l(values.stream().map(Attrs::s).toList()).build();
    }

    private static String 멤버값(MemberRef ref) {
        return ref.type().name() + "#" + ref.id();
    }

    private static MemberRef 멤버(String value) {
        String[] parts = value.split("#", 2);
        return new MemberRef(MemberType.valueOf(parts[0]), parts[1]);
    }

    private static String 튜플값(RelationTuple tuple) {
        return tuple.user() + TUPLE_SEPARATOR + tuple.relation() + TUPLE_SEPARATOR + tuple.object();
    }

    private static RelationTuple 튜플(String value) {
        String[] parts = value.split("\\" + TUPLE_SEPARATOR, 3);
        return new RelationTuple(parts[0], parts[1], parts[2]);
    }
}
