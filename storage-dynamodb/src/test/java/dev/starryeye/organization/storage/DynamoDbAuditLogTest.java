package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 변경 이력 저장소(설계 2026-10-10 §3·§5·§6)를 DynamoDB Local 위에서 본다. */
class DynamoDbAuditLogTest extends DynamoDbTestSupport {

    private static final Instant T1 = Instant.parse("2026-10-10T03:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);
    private static final Instant T3 = T1.plusSeconds(120);
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 이 = MemberRef.user("lee");
    private static final MemberRef 개발 = MemberRef.group("DEV");

    private DynamoDbAuditLog auditLog;

    @BeforeEach
    void 저장소를_준비한다() {
        auditLog = new DynamoDbAuditLog(client, properties);
    }

    private static AuditCause 회차(String runId) {
        return AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, runId);
    }

    private static AuditEntry 표시명_변경(MemberRef subject, String 전, String 후) {
        return new AuditEntry(subject, false, false, List.of(new AttributeChange("displayName", 전, 후)),
                null, null, null, null, null, null);
    }

    /** 개발 조직이 만들어지며 멤버 {@code 수} 명이 들어온 줄 — u0000 부터. */
    private static AuditEntry 멤버_추가(int 수) {
        List<MemberRef> members = IntStream.range(0, 수).mapToObj(i -> MemberRef.user("u%04d".formatted(i))).toList();
        return new AuditEntry(개발, true, false, List.of(new AttributeChange("displayName", null, "개발본부")),
                null, null, members, null, null, null);
    }

    private static void 쓴다(DynamoDbAuditLog log, Instant at, AuditCause cause, AuditEntry... entries) {
        assertThat(log.write(new AuditEvent(at, cause, List.of(entries))).block()).isZero();
    }

    private static Page<AuditRecord> 읽는다(DynamoDbAuditLog log, MemberRef subject, String cursor, int limit) {
        return log.history(subject, cursor, limit).block();
    }

    /** 파티션의 아이템 수 — 조각까지 센다. */
    private int 아이템_수(MemberRef subject) {
        return client.query(QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.auditPk(subject))))
                        .build())
                .join().count();
    }

    private void 조각을_지운다(MemberRef subject, Instant at, String causeId, int part) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.auditPk(subject)), Keys.SK, Attrs.s(Keys.auditSk(at, causeId, part))))
                .build()).join();
    }

    @Test
    @DisplayName("대상의 이력을 최신순으로 읽고, 원인·시각·변경을 그대로 돌려준다")
    void 최신순으로_읽는다() {
        // given
        쓴다(auditLog, T1, 회차("run-1"), 표시명_변경(김, "김", "김철수"));
        쓴다(auditLog, T2, AuditCause.scimRequest("trace-2"), new AuditEntry(김, false, false, null,
                List.of("DEV"), null, null, null, List.of(RelationTuple.directMember("kim", "DEV")), null));

        // when
        Page<AuditRecord> page = 읽는다(auditLog, 김, null, 20);

        // then
        assertThat(page.items()).hasSize(2);
        AuditRecord 최근 = page.items().get(0);
        assertThat(최근.at()).isEqualTo(T2);
        assertThat(최근.cause()).isEqualTo(AuditCause.scimRequest("trace-2"));
        assertThat(최근.entry().joined()).containsExactly("DEV");
        assertThat(최근.entry().granted()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        AuditRecord 처음 = page.items().get(1);
        assertThat(처음.at()).isEqualTo(T1);
        assertThat(처음.cause()).isEqualTo(회차("run-1"));
        assertThat(처음.entry().attributes()).containsExactly(new AttributeChange("displayName", "김", "김철수"));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("대상마다 파티션이 따로다 — 같은 사건의 다른 대상 줄은 섞이지 않고, 기록이 없으면 빈 쪽이다")
    void 대상마다_따로다() {
        // given
        쓴다(auditLog, T1, 회차("run-1"), 표시명_변경(김, "a", "b"), 멤버_추가(1));

        // when
        Page<AuditRecord> 김의_이력 = 읽는다(auditLog, 김, null, 20);
        Page<AuditRecord> 이의_이력 = 읽는다(auditLog, 이, null, 20);

        // then
        assertThat(김의_이력.items()).singleElement()
                .satisfies(record -> assertThat(record.entry().subject()).isEqualTo(김));
        assertThat(이의_이력.items()).isEmpty();
        assertThat(이의_이력.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서로 이어 읽는다 — 쪽이 limit 줄로 차면 다음 커서를 준다")
    void 커서로_이어_읽는다() {
        // given
        쓴다(auditLog, T1, 회차("run-1"), 표시명_변경(김, "a", "b"));
        쓴다(auditLog, T2, 회차("run-2"), 표시명_변경(김, "b", "c"));
        쓴다(auditLog, T3, 회차("run-3"), 표시명_변경(김, "c", "d"));

        // when
        Page<AuditRecord> 첫쪽 = 읽는다(auditLog, 김, null, 2);
        Page<AuditRecord> 다음쪽 = 읽는다(auditLog, 김, 첫쪽.nextCursor(), 2);

        // then
        assertThat(첫쪽.items()).extracting(record -> record.cause().id()).containsExactly("run-3", "run-2");
        assertThat(첫쪽.nextCursor()).isNotNull();
        assertThat(다음쪽.items()).extracting(record -> record.cause().id()).containsExactly("run-1");
        assertThat(다음쪽.nextCursor()).isNull();
    }

    @Test
    @DisplayName("남의 커서, 범위만 맞춘 위조 커서, 망가진 커서는 거절한다 — 400 으로 갈 예외")
    void 남의_커서와_망가진_커서는_거절한다() {
        // given
        String 이의_파티션 = Keys.auditPk(이);
        String 이의_커서 = Cursor.encode("AUDIT/" + 이의_파티션,
                Map.of(Keys.PK, Attrs.s(이의_파티션), Keys.SK, Attrs.s(Keys.auditSk(T1, "run-1", 0))));
        String 범위만_맞춘_위조 = Cursor.encode("AUDIT/" + Keys.auditPk(김),
                Map.of(Keys.PK, Attrs.s(이의_파티션), Keys.SK, Attrs.s(Keys.auditSk(T1, "run-1", 0))));

        // when, then
        assertThatThrownBy(() -> 읽는다(auditLog, 김, 이의_커서, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> 읽는다(auditLog, 김, 범위만_맞춘_위조, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> 읽는다(auditLog, 김, "망가진-커서", 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("큰 목록은 조각으로 나눠 쓰고, 읽을 때 순서대로 합친다")
    void 큰_목록은_조각으로_나눈다() {
        // given — 멤버 값 "USER#u0000" 은 10바이트 + 4 = 14. 한도 200 이면 조각마다 14개, 50명이면 조각 4개
        DynamoDbAuditLog 작은_조각 = new DynamoDbAuditLog(client, properties, 200);
        쓴다(작은_조각, T1, 회차("run-1"), 멤버_추가(50));

        // when
        Page<AuditRecord> page = 읽는다(작은_조각, 개발, null, 20);

        // then
        assertThat(아이템_수(개발)).isEqualTo(4);
        assertThat(page.items()).singleElement().satisfies(record -> {
            assertThat(record.entry().created()).isTrue();
            assertThat(record.entry().attributes()).containsExactly(new AttributeChange("displayName", null, "개발본부"));
            assertThat(record.entry().membersAdded()).hasSize(50)
                    .startsWith(MemberRef.user("u0000"))
                    .endsWith(MemberRef.user("u0049"));
        });
    }

    @Test
    @DisplayName("쪽 경계가 조각 사이에 걸리지 않는다 — 한 줄씩 넘겨도 줄마다 목록이 온전하다")
    void 쪽_경계가_조각_사이에_걸리지_않는다() {
        // given — 30명이면 조각 3개(14·14·2)
        DynamoDbAuditLog 작은_조각 = new DynamoDbAuditLog(client, properties, 200);
        쓴다(작은_조각, T1, 회차("run-1"), 멤버_추가(30));
        쓴다(작은_조각, T2, 회차("run-2"), 멤버_추가(30));
        쓴다(작은_조각, T3, 회차("run-3"), 멤버_추가(30));

        // when
        List<String> 읽은_회차 = new ArrayList<>();
        String cursor = null;
        do {
            Page<AuditRecord> page = 읽는다(작은_조각, 개발, cursor, 1);
            for (AuditRecord record : page.items()) {
                assertThat(record.entry().membersAdded()).hasSize(30);
                읽은_회차.add(record.cause().id());
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        // then
        assertThat(읽은_회차).containsExactly("run-3", "run-2", "run-1");
    }

    @Test
    @DisplayName("조각이 모자란 줄은 돌려주지 않는다 — 가운데 조각을 잃은 줄")
    void 가운데_조각을_잃은_줄() {
        // given
        DynamoDbAuditLog 작은_조각 = new DynamoDbAuditLog(client, properties, 200);
        쓴다(작은_조각, T1, 회차("run-1"), 멤버_추가(30));
        쓴다(작은_조각, T2, 회차("run-2"), 멤버_추가(30));
        조각을_지운다(개발, T2, "run-2", 1);

        // when
        Page<AuditRecord> page = 읽는다(작은_조각, 개발, null, 20);

        // then
        assertThat(page.items()).extracting(record -> record.cause().id()).containsExactly("run-1");
    }

    @Test
    @DisplayName("첫 조각을 잃은 줄의 남은 조각은 다음 줄을 해치지 않는다")
    void 첫_조각을_잃은_줄() {
        // given — 역순으로 읽으면 run-2 의 #0002·#0001 뒤에 run-1 의 조각이 바로 온다
        DynamoDbAuditLog 작은_조각 = new DynamoDbAuditLog(client, properties, 200);
        쓴다(작은_조각, T1, 회차("run-1"), 멤버_추가(30));
        쓴다(작은_조각, T2, 회차("run-2"), 멤버_추가(30));
        조각을_지운다(개발, T2, "run-2", 0);

        // when
        Page<AuditRecord> page = 읽는다(작은_조각, 개발, null, 20);

        // then
        assertThat(page.items()).singleElement().satisfies(record -> {
            assertThat(record.cause().id()).isEqualTo("run-1");
            assertThat(record.entry().membersAdded()).hasSize(30);
        });
    }

    @Test
    @DisplayName("줄마다 만료 시각을 넣는다 — 기록 시각 + 보관 일수(기본 30일)")
    void 만료_시각을_넣는다() {
        // given
        쓴다(auditLog, T1, 회차("run-1"), 표시명_변경(김, "a", "b"));

        // when
        var item = client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.auditPk(김)), Keys.SK, Attrs.s(Keys.auditSk(T1, "run-1", 0))))
                .build()).join().item();

        // then
        assertThat(Attrs.longValue(item, Keys.EXPIRES_AT)).isEqualTo(T1.plus(Duration.ofDays(30)).getEpochSecond());
    }

    @Test
    @DisplayName("직원을 지우고 조직도를 비워도(wipe) 이력은 남는다")
    void 지우고_비워도_이력은_남는다() {
        // given
        var state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        state.saveUser(new DirectoryUser("kim", null, "kim", "김", null, true)).block();
        쓴다(auditLog, T1, 회차("run-1"), 표시명_변경(김, "a", "b"));

        // when
        state.deleteUser("kim").block();
        state.replaceWith(DirectorySnapshot.empty()).block();

        // then
        assertThat(읽는다(auditLog, 김, null, 20).items()).hasSize(1);
    }

    @Test
    @DisplayName("묶음 쓰기가 끝내 실패하면 오류 대신 빠진 줄 수를 준다")
    void 묶음이_실패하면_빠진_줄을_센다() {
        // given
        DynamoDbAsyncClient 묶음이_안되는 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("batchWriteItem")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("테스트용 실패"));
                    }
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var 실패하는 = new DynamoDbAuditLog(묶음이_안되는, properties);
        List<AuditEntry> 서른줄 = IntStream.range(0, 30)
                .mapToObj(i -> 표시명_변경(MemberRef.user("u" + i), "a", "b"))
                .toList();

        // when
        Integer 빠진 = 실패하는.write(new AuditEvent(T1, 회차("run-1"), 서른줄)).block();

        // then
        assertThat(빠진).isEqualTo(30);
    }
}
