package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지정 연결(설계 2026-11-04 §4)의 정본·역색인 저장 — 하위 조직 파티션의 {@code ASSIGNED_PARENT}, 상위 조직 파티션의 {@code ASSIGNED_CHILD#<하위>}.
 * 조직 지우기·전체 읽기·wipe 와의 관계를 DynamoDB Local 로 본다.
 */
class DynamoDbAssignedParentTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository repository;
    /** META 의 {@code updatedAt} 이 지정으로 움직이는지 보려면 시계가 지정 사이에 흘러야 한다. */
    private MutableClock clock;

    @BeforeEach
    void 저장소를_준비하고_조직을_만든다() {
        clock = new MutableClock(Instant.parse("2026-11-04T00:00:00Z"));
        repository = new DynamoDbDirectoryStateRepository(client, properties, clock);
        for (String id : List.of("P", "Q", "C", "D", "G")) {
            repository.saveGroup(조직(id)).block();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void 앞으로(Duration amount) {
            now = now.plus(amount);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static DirectoryGroup 조직(String id, MemberRef... members) {
        return new DirectoryGroup(id, null, id + " 팀", Set.of(members));
    }

    @Test
    @DisplayName("지정하면 하위 조직 파티션에 정본이, 상위 조직 파티션에 역색인이 생긴다")
    void 지정하면_정본과_역색인이_생긴다() {
        // given, when
        repository.saveAssignedParent("C", "P", null).block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isEqualTo("P");
        assertThat(repository.findAssignedChildren("P").collectList().block()).containsExactly("C");
        assertThat(정렬키들("GROUP#C")).contains(Keys.ASSIGNED_PARENT);
        assertThat(정렬키들("GROUP#P")).contains(Keys.assignedChildSk("C"));
    }

    @Test
    @DisplayName("지정 상위가 없는 조직은 빈 값이다")
    void 지정이_없으면_비어_있다() {
        // given, when, then
        assertThat(repository.findAssignedParent("C").block()).isNull();
        assertThat(repository.findAssignedChildren("P").collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("지정 상위를 바꾸면 새 상위의 역색인이 생기고 옛 상위의 역색인이 지워진다")
    void 바꾸면_옛_역색인이_지워진다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();

        // when
        repository.saveAssignedParent("C", "Q", "P").block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isEqualTo("Q");
        assertThat(repository.findAssignedChildren("P").collectList().block()).isEmpty();
        assertThat(repository.findAssignedChildren("Q").collectList().block()).containsExactly("C");
        assertThat(정렬키들("GROUP#P")).noneMatch(Keys::isAssignedChildSk);
    }

    @Test
    @DisplayName("같은 상위로 다시 지정해도 역색인이 지워지지 않는다")
    void 같은_상위로_다시_지정하면_역색인이_남는다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();

        // when — 락 안에서 읽은 지금 값이 P 이고 새 값도 P 다
        repository.saveAssignedParent("C", "P", "P").block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isEqualTo("P");
        assertThat(repository.findAssignedChildren("P").collectList().block()).containsExactly("C");
    }

    @Test
    @DisplayName("정본과 맞지 않는 역색인은 읽을 때 거른다")
    void 정본과_맞지_않는_역색인은_거른다() {
        // given — C 의 정본은 Q 를 가리키는데 P 파티션에 낡은 역색인이 있다
        repository.saveAssignedParent("C", "Q", null).block();
        역색인을_직접_넣는다("P", "C");

        // when
        List<String> p의_하위 = repository.findAssignedChildren("P").collectList().block();

        // then
        assertThat(p의_하위).isEmpty();
        assertThat(repository.findAssignedChildren("Q").collectList().block()).containsExactly("C");
    }

    @Test
    @DisplayName("정본이 없는 역색인도 거른다 — 정본을 쓰기 전에 멈춘 지정이다")
    void 정본이_없는_역색인은_거른다() {
        // given
        역색인을_직접_넣는다("P", "C");

        // when, then
        assertThat(repository.findAssignedChildren("P").collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("하위 조직은 아이디 오름차순으로 돌려준다")
    void 하위_조직은_아이디_오름차순이다() {
        // given
        repository.saveAssignedParent("G", "P", null).block();
        repository.saveAssignedParent("C", "P", null).block();
        repository.saveAssignedParent("D", "P", null).block();

        // when, then
        assertThat(repository.findAssignedChildren("P").collectList().block()).containsExactly("C", "D", "G");
    }

    @Test
    @DisplayName("지정을 없애면 정본과 역색인이 둘 다 지워진다")
    void 없애면_둘_다_지워진다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();

        // when
        repository.deleteAssignedParent("C", "P").block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isNull();
        assertThat(repository.findAssignedChildren("P").collectList().block()).isEmpty();
        assertThat(정렬키들("GROUP#C")).doesNotContain(Keys.ASSIGNED_PARENT);
        assertThat(정렬키들("GROUP#P")).noneMatch(Keys::isAssignedChildSk);
    }

    @Test
    @DisplayName("정본이 다른 상위를 가리키면 정본은 두고 역색인만 지운다")
    void 정본이_다른_상위를_가리키면_정본은_두고_역색인만_지운다() {
        // given — C 의 정본은 Q 이고, P 파티션에 낡은 역색인이 있다
        repository.saveAssignedParent("C", "Q", null).block();
        역색인을_직접_넣는다("P", "C");

        // when
        repository.deleteAssignedParent("C", "P").block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isEqualTo("Q");
        assertThat(정렬키들("GROUP#P")).noneMatch(Keys::isAssignedChildSk);
        assertThat(repository.findAssignedChildren("Q").collectList().block()).containsExactly("C");
    }

    @Test
    @DisplayName("지정이 없는 조직의 지정을 없애도 오류가 아니다")
    void 없는_지정을_없애도_오류가_아니다() {
        // given, when, then
        repository.deleteAssignedParent("C", "P").block();
        assertThat(repository.findAssignedParent("C").block()).isNull();
    }

    @Test
    @DisplayName("조직을 지우면 그 조직이 끼인 지정이 양방향으로 지워진다")
    void 조직을_지우면_양방향_지정이_지워진다() {
        // given — C → G, D → G, G → P
        repository.saveAssignedParent("C", "G", null).block();
        repository.saveAssignedParent("D", "G", null).block();
        repository.saveAssignedParent("G", "P", null).block();

        // when
        repository.deleteGroup("G", Set.of()).block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isNull();
        assertThat(repository.findAssignedParent("D").block()).isNull();
        assertThat(repository.findAssignedParent("G").block()).isNull();
        assertThat(repository.findAssignedChildren("P").collectList().block()).isEmpty();
        assertThat(repository.findGroup("G").block()).isNull();
        assertThat(정렬키들("GROUP#P")).noneMatch(Keys::isAssignedChildSk);
        assertThat(정렬키들("GROUP#C")).doesNotContain(Keys.ASSIGNED_PARENT);
    }

    @Test
    @DisplayName("낡은 역색인이 가리키는 하위 조직의 정본은 지우지 않는다")
    void 낡은_역색인이_가리키는_하위의_정본은_지우지_않는다() {
        // given — C 의 정본은 Q 인데 G 파티션에 낡은 역색인이 있다
        repository.saveAssignedParent("C", "Q", null).block();
        역색인을_직접_넣는다("G", "C");

        // when
        repository.deleteGroup("G", Set.of()).block();

        // then
        assertThat(repository.findAssignedParent("C").block()).isEqualTo("Q");
        assertThat(repository.findAssignedChildren("Q").collectList().block()).containsExactly("C");
        assertThat(repository.findGroup("G").block()).isNull();
    }

    @Test
    @DisplayName("전체 읽기는 지정 연결을 (상위, 하위) 쌍으로 싣는다")
    void 전체_읽기는_지정_연결을_싣는다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();

        // when
        DirectorySnapshot snapshot = repository.loadAll().block();

        // then
        assertThat(snapshot.assignedEdges()).containsExactly(new GroupEdge("P", "C"));
        assertThat(snapshot.groups()).containsOnlyKeys("P", "Q", "C", "D", "G");
    }

    @Test
    @DisplayName("전체 읽기의 조직에는 지정 연결이 멤버로 섞이지 않는다")
    void 전체_읽기의_조직에_지정이_멤버로_섞이지_않는다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();

        // when
        DirectorySnapshot snapshot = repository.loadAll().block();

        // then
        assertThat(snapshot.groups().get("P").members()).isEmpty();
        assertThat(snapshot.groups().get("C").members()).isEmpty();
    }

    @Test
    @DisplayName("지정은 조직의 META 와 SCIM 멤버를 건드리지 않는다")
    void 지정은_META_와_SCIM_멤버를_건드리지_않는다() {
        // given
        String 지정_전 = updatedAt("GROUP#P");
        clock.앞으로(Duration.ofHours(1));

        // when
        repository.saveAssignedParent("C", "P", null).block();
        repository.saveAssignedParent("C", "Q", "P").block();

        // then
        assertThat(updatedAt("GROUP#P")).isEqualTo(지정_전);
        assertThat(updatedAt("GROUP#C")).isEqualTo(지정_전);
        assertThat(repository.findGroup("P").block().members()).doesNotContain(MemberRef.group("C"));
        assertThat(repository.findMemberRefs("P").collectList().block()).doesNotContain(MemberRef.group("C"));
        assertThat(repository.findGroupIdsContaining(MemberRef.group("C")).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("빈 그림으로 바꾸면 지정이 모두 지워진다")
    void 빈_그림으로_바꾸면_지정이_모두_지워진다() {
        // given
        repository.saveAssignedParent("C", "P", null).block();
        repository.saveAssignedParent("D", "G", null).block();

        // when
        repository.replaceWith(DirectorySnapshot.empty()).block();

        // then
        assertThat(지정_아이템_수()).isZero();
        assertThat(repository.findAssignedParent("C").block()).isNull();
    }

    /** 정본 없이 역색인만 넣는다 — 지정이 중간에 멈췄거나 정본이 다른 상위로 바뀐 뒤의 낡은 역색인을 만든다. */
    private void 역색인을_직접_넣는다(String parent, String child) {
        client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(Map.of(
                        Keys.PK, Attrs.s(Keys.groupPk(parent)),
                        Keys.SK, Attrs.s(Keys.assignedChildSk(child))))
                .build()).join();
    }

    /** 파티션 하나의 정렬키 전부. 테이블에 실제로 무엇이 들어갔는지 직접 본다. */
    private List<String> 정렬키들(String pk) {
        var response = client.query(QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .consistentRead(true)
                .build()).join();
        return response.items().stream().map(item -> Attrs.str(item, Keys.SK)).toList();
    }

    /** 테이블 전체에서 정렬키가 {@code ASSIGNED_} 로 시작하는 아이템 수. */
    private int 지정_아이템_수() {
        return client.scan(ScanRequest.builder()
                .tableName(properties.getTableName())
                .filterExpression("begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":prefix", Attrs.s("ASSIGNED_")))
                .consistentRead(true)
                .build()).join().items().size();
    }

    /** META 의 updatedAt 을 직접 읽는다. 저장소 API 는 이 값을 노출하지 않는다. */
    private String updatedAt(String pk) {
        Map<String, AttributeValue> meta = client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build())
                .join().item();
        return meta.get("updatedAt").s();
    }
}
