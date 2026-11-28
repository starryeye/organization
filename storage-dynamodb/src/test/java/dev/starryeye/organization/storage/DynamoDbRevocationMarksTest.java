package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.RevocationMarks;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.BaselineMovedException;
import dev.starryeye.organization.core.port.RevocationMarksFullException;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository.BaselineState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스냅샷 포인터 줄의 즉시 회수 표시 두 칸(설계 2026-11-28 V6·V8·V9·V14). */
class DynamoDbRevocationMarksTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-11-28T03:00:00Z");
    private static final String 번호 = "20261128T030000-LDAP";

    private DynamoDbTupleSnapshotRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        repository = new DynamoDbTupleSnapshotRepository(client, properties, Clock.fixed(지금, ZoneOffset.UTC));
    }

    private static RelationTuple 줄(String user, String group) {
        return RelationTuple.directMember(user, group);
    }

    private void 기준선을_남긴다(RelationTuple... tuples) {
        repository.save(new TupleSnapshot(번호, 지금, SyncSource.LDAP, Set.of(tuples))).block();
    }

    private Map<String, AttributeValue> 포인터_아이템() {
        return client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                .consistentRead(true)
                .build()).join().item();
    }

    private static int 바이트(RelationTuple tuple) {
        return Keys.tupleSk(tuple).getBytes(StandardCharsets.UTF_8).length;
    }

    /** 바이트가 정확히 {@code bytes} 인 줄 하나. 사용자 칸을 채운다. */
    private static RelationTuple 바이트가_정해진_줄(int bytes) {
        int 뼈대 = 바이트(줄("", "G"));
        return 줄("a".repeat(bytes - 뼈대), "G");
    }

    // 깨뜨릴 변경: findLatestMarked 가 표시 칸을 읽지 않거나 새 줄을 unknown 이 아닌 곳에 더하면 깨진다
    @Test
    @DisplayName("표시를 더하면 findLatestMarked 가 스냅샷과 함께 준다")
    void 표시를_더하면_findLatestMarked_가_스냅샷과_함께_준다() {
        // given
        기준선을_남긴다(줄("u1", "G1"), 줄("u2", "G2"));

        // when
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();
        var marked = repository.findLatestMarked().block();

        // then
        assertThat(marked.snapshot().id()).isEqualTo(번호);
        assertThat(marked.snapshot().tuples()).containsExactlyInAnyOrder(줄("u1", "G1"), 줄("u2", "G2"));
        assertThat(marked.marks().unknown()).containsExactly(줄("u1", "G1"));
        assertThat(marked.marks().gone()).isEmpty();
    }

    // 깨뜨릴 변경: 정리가 gone 으로 옮기지 않거나 dropped 를 unknown 에서 빼지 않으면 깨진다
    @Test
    @DisplayName("정리하면 지운 줄은 gone 칸으로 옮기고 버린 줄은 빠진다")
    void 정리하면_지운_줄은_gone_칸으로_옮기고_버린_줄은_빠진다() {
        // given
        기준선을_남긴다(줄("u1", "G1"), 줄("u1", "G2"), 줄("u1", "G3"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"), 줄("u1", "G2"), 줄("u1", "G3"))).block();

        // when
        repository.settleRevocation(번호, Set.of(줄("u1", "G1")), Set.of(줄("u1", "G2"))).block();
        var marks = repository.findLatestMarked().block().marks();

        // then
        assertThat(marks.gone()).containsExactly(줄("u1", "G1"));
        assertThat(marks.unknown()).containsExactly(줄("u1", "G3"));
    }

    // 깨뜨릴 변경: 비운 Set 칸을 그대로 두면(DynamoDB 는 빈 Set 을 못 가진다) 칸이 남거나 쓰기가 실패한다
    @Test
    @DisplayName("마지막 원소를 빼면 칸이 사라진다")
    void 마지막_원소를_빼면_칸이_사라진다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();

        // when
        repository.settleRevocation(번호, Set.of(), Set.of(줄("u1", "G1"))).block();

        // then
        assertThat(포인터_아이템()).doesNotContainKey("revokedUnknown").doesNotContainKey("revokedGone");
        assertThat(repository.findLatestMarked().block().marks().isEmpty()).isTrue();
    }

    // 깨뜨릴 변경: 번호 조건이 없으면 다른 기준선에 표시가 붙는다
    @Test
    @DisplayName("스냅샷 번호가 다르면 BaselineMovedException 이고 칸은 그대로다")
    void 스냅샷_번호가_다르면_BaselineMovedException_이고_칸은_그대로다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();

        // when, then
        assertThatThrownBy(() -> repository.markRevoking("다른-번호", Set.of(줄("u2", "G2"))).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThatThrownBy(() -> repository.settleRevocation("다른-번호", Set.of(줄("u1", "G1")), Set.of()).block())
                .isInstanceOf(BaselineMovedException.class);
        var marks = repository.findLatestMarked().block().marks();
        assertThat(marks.unknown()).containsExactly(줄("u1", "G1"));
        assertThat(marks.gone()).isEmpty();
    }

    // 깨뜨릴 변경: 기록 중 조건이 없으면 기록 도중의 기준선에 표시가 붙는다
    @Test
    @DisplayName("기록 중이면 BaselineMovedException")
    void 기록_중이면_BaselineMovedException() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markWriting().block();

        // when, then
        assertThatThrownBy(() -> repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThatThrownBy(() -> repository.settleRevocation(번호, Set.of(줄("u1", "G1")), Set.of()).block())
                .isInstanceOf(BaselineMovedException.class);
        assertThat(포인터_아이템()).doesNotContainKey("revokedUnknown").doesNotContainKey("revokedGone");
    }

    // 깨뜨릴 변경: 한도 비교가 >= 이거나, 넘는데도 쓰면 깨진다
    @Test
    @DisplayName("한도와 같으면 허용하고 넘으면 RevocationMarksFullException 이고 아무것도 더하지 않는다")
    void 한도와_같으면_허용하고_넘으면_RevocationMarksFullException_이고_아무것도_더하지_않는다() {
        // given — unknown 에 이미 있는 줄 위에 새 줄을 얹는다
        RelationTuple 먼저 = 줄("u0", "G0");
        기준선을_남긴다(먼저);
        repository.markRevoking(번호, Set.of(먼저)).block();
        int 남은 = RevocationMarks.MAX_BYTES - 바이트(먼저);
        RelationTuple 딱_맞는 = 바이트가_정해진_줄(남은);
        RelationTuple 하나_넘는 = 바이트가_정해진_줄(남은 + 1);

        // when, then — 1바이트 넘으면 거절하고 아무것도 더하지 않는다
        assertThatThrownBy(() -> repository.markRevoking(번호, Set.of(하나_넘는)).block())
                .isInstanceOf(RevocationMarksFullException.class);
        assertThat(repository.findLatestMarked().block().marks().unknown()).containsExactly(먼저);

        // when, then — 합이 정확히 한도면 허용한다
        repository.markRevoking(번호, Set.of(딱_맞는)).block();
        assertThat(repository.findLatestMarked().block().marks().unknown()).containsExactlyInAnyOrder(먼저, 딱_맞는);
    }

    // 깨뜨릴 변경: 글자 수로 세면 한글 줄이 한도를 넘는데도 들어간다
    @Test
    @DisplayName("한도는 UTF-8 바이트로 센다")
    void 한도는_UTF_8_바이트로_센다() {
        // given — 글자 수는 한도 아래, 바이트는 한도 위
        기준선을_남긴다(줄("u0", "G0"));
        RelationTuple 한글 = 줄("u1", "가".repeat(90_000));
        assertThat(Keys.tupleSk(한글).length()).isLessThan(RevocationMarks.MAX_BYTES);
        assertThat(바이트(한글)).isGreaterThan(RevocationMarks.MAX_BYTES);

        // when, then
        assertThatThrownBy(() -> repository.markRevoking(번호, Set.of(한글)).block())
                .isInstanceOf(RevocationMarksFullException.class);
        assertThat(포인터_아이템()).doesNotContainKey("revokedUnknown");
    }

    // 깨뜨릴 변경: save 가 포인터를 갱신(UpdateItem)으로 바꾸면 이전 회차의 표시가 새 기준선에 남는다
    @Test
    @DisplayName("save 가 표시를 지운다")
    void save_가_표시를_지운다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();
        repository.settleRevocation(번호, Set.of(줄("u1", "G1")), Set.of()).block();

        // when
        repository.save(new TupleSnapshot("20261128T040000-LDAP", 지금, SyncSource.LDAP, Set.of(줄("u2", "G2")))).block();

        // then
        assertThat(포인터_아이템()).doesNotContainKey("revokedUnknown").doesNotContainKey("revokedGone");
        assertThat(repository.findLatestMarked().block().marks().isEmpty()).isTrue();
    }

    // 깨뜨릴 변경: markWriting 이 표시 칸을 지우면 기록 중에 멈춘 회차의 표시를 다음 회차가 잃는다
    @Test
    @DisplayName("markWriting 은 표시를 남긴다")
    void markWriting_은_표시를_남긴다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();

        // when
        repository.markWriting().block();

        // then
        assertThat(포인터_아이템().get("revokedUnknown").ss()).containsExactly(Keys.tupleSk(줄("u1", "G1")));
        assertThat(repository.baselinePointer().block().state()).isEqualTo(BaselineState.WRITING);
    }

    // 깨뜨릴 변경: 풀기 실패를 삼키면 깨진 표시가 조용히 사라져 지운 줄이 되살아난다
    @Test
    @DisplayName("풀 수 없는 원소는 SnapshotIntegrityException")
    void 풀_수_없는_원소는_SnapshotIntegrityException() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        client.updateItem(UpdateItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                .updateExpression("SET revokedGone = :g")
                .expressionAttributeValues(Map.of(":g", AttributeValue.builder().ss("garbage").build()))
                .build()).join();

        // when, then
        assertThatThrownBy(() -> repository.findLatestMarked().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("즉시 회수 표시를 풀 수 없습니다");
    }

    // 깨뜨릴 변경: 빈 집합에도 UpdateItem 을 보내면 빈 Set 오류가 나거나 번호 검사에 걸린다
    @Test
    @DisplayName("빈 집합은 아무것도 쓰지 않는다")
    void 빈_집합은_아무것도_쓰지_않는다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        repository.markRevoking(번호, Set.of(줄("u1", "G1"))).block();
        var 전 = 포인터_아이템();

        // when
        assertThatCode(() -> {
            repository.markRevoking(번호, Set.of()).block();
            repository.settleRevocation(번호, Set.of(), Set.of()).block();
            repository.markRevoking("틀린-번호", Set.of()).block();
            repository.settleRevocation("틀린-번호", Set.of(), Set.of()).block();
        }).doesNotThrowAnyException();

        // then
        assertThat(포인터_아이템()).isEqualTo(전);
    }

    // 깨뜨릴 변경: baselinePointer 가 본문을 읽으면(Query) 매 회차 시작이 스냅샷 크기만큼 비싸진다
    @Test
    @DisplayName("baselinePointer 는 본문을 읽지 않는다")
    void baselinePointer_는_본문을_읽지_않는다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        GetCounter gets = new GetCounter();
        AtomicInteger 질의 = new AtomicInteger();
        var 세는_클라이언트 = gets.wrap(client);
        var 질의도_세는 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        질의.incrementAndGet();
                    }
                    try {
                        return method.invoke(세는_클라이언트, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var 세는_저장소 = new DynamoDbTupleSnapshotRepository(질의도_세는, properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        var pointer = 세는_저장소.baselinePointer().block();

        // then
        assertThat(pointer.snapshotId()).isEqualTo(번호);
        assertThat(pointer.state()).isEqualTo(BaselineState.PRESENT);
        assertThat(gets.gets()).isEqualTo(1);
        assertThat(질의.get()).isZero();
    }

    // 깨뜨릴 변경: findLatestMarked 가 포인터를 두 번 읽으면 스냅샷과 표시가 서로 다른 기준선에서 올 수 있다(V14)
    @Test
    @DisplayName("findLatestMarked 는 포인터를 한 번만 읽는다")
    void findLatestMarked_는_포인터를_한_번만_읽는다() {
        // given
        기준선을_남긴다(줄("u1", "G1"));
        GetCounter gets = new GetCounter();
        var 세는_저장소 = new DynamoDbTupleSnapshotRepository(gets.wrap(client), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        세는_저장소.findLatestMarked().block();

        // then
        assertThat(gets.gets()).isEqualTo(1);
    }
}
