package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.Timestamped;
import dev.starryeye.organization.core.query.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 긴 색인 값(LDAP 의 자유 서술 {@code description} 이 조직 표시명이 된다)도 상태 교체가 성공한다(설계 2026-10-20 §7).
 *
 * <p>색인 정렬키(GSI1SK·GSI2SK)는 쓸 때 1024바이트로 자르고, 원래 값은 {@code displayName}·{@code userName} 속성에 그대로 둔다. 실제 DynamoDB 는
 * 1024바이트를 넘는 정렬키를 {@code ValidationException} 으로 거절한다. DynamoDB Local 2.5.3 도 1200바이트 GSI 정렬키를 거절한다(검토에서 확인:
 * {@code ValidationException: Size limit exceeded}) — 자르지 않은 키는 상태 교체를 실패시킨다. 시험은 <b>읽어 본 정렬키의 길이</b>를 본다.
 */
class LongIndexValueTest extends DynamoDbTestSupport {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** 400자지만 1200바이트 — 정렬키 한도(1024)를 넘는다. */
    private static final String 긴_표시명 = "가".repeat(400);

    private DynamoDbDirectoryStateRepository state;
    private DynamoDbDirectoryQueryRepository query;
    private DynamoDbDirectorySearchRepository search;

    @BeforeEach
    void 저장소를_준비한다() {
        state = new DynamoDbDirectoryStateRepository(client, properties, CLOCK);
        query = new DynamoDbDirectoryQueryRepository(client, properties, state);
        search = new DynamoDbDirectorySearchRepository(client, properties);
    }

    @Test
    @DisplayName("긴 조직 표시명도 현재 상태 교체가 성공하고 조직 목록에 나온다")
    void 긴_조직_표시명도_현재상태_교체가_성공하고_목록에_나온다() {
        // given — 표시명이 1200바이트인 조직 하나
        assertThat(긴_표시명.getBytes(StandardCharsets.UTF_8)).hasSize(1200);
        var 스냅샷 = new DirectorySnapshot(Map.of(),
                Map.of("DEV", new DirectoryGroup("DEV", "cn=DEV", 긴_표시명, Set.of())));

        // when
        state.replaceWith(스냅샷).block();

        // then — 조직 META 의 정렬키는 한도 안이고, 원래 표시명은 속성에 그대로 있다
        Map<String, AttributeValue> meta = 읽는다(Keys.groupPk("DEV"));
        assertThat(meta.get(Keys.GSI1SK).s().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(meta.get(Keys.GSI1SK).s()).isEqualTo(Keys.indexKeyForWrite(긴_표시명));
        assertThat(meta.get("displayName").s()).isEqualTo(긴_표시명);

        // then — 조직 목록(이름표 열거)에 들고, 이름은 잘리지 않은 원래 값이다
        var 목록 = query.listGroupHeaders(null, 10, false).block();
        assertThat(목록.items()).extracting(Timestamped::value)
                .containsExactly(new GroupHeader("DEV", "cn=DEV", 긴_표시명));
        assertThat(state.loadAll().block().groups().get("DEV").displayName()).isEqualTo(긴_표시명);

        // then — 같은 상태로 다시 교체해도 성공하고 바뀐 것이 없다
        assertThat(state.replaceWith(스냅샷).block().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("긴 직원 표시명도 저장되고, 잘린 정렬키(GSI2SK)로 표시명 접두사 검색이 된다")
    void 긴_직원_표시명도_저장된다() {
        // given — 표시명이 1200바이트인 직원 하나
        var 직원 = new DirectoryUser("kim", null, "kim", 긴_표시명, null, true);

        // when
        state.replaceWith(new DirectorySnapshot(Map.of("kim", 직원), Map.of())).block();

        // then — 직원 META 의 GSI2SK 는 한도 안이고, 원래 표시명은 속성에 그대로 있다
        Map<String, AttributeValue> meta = 읽는다(Keys.userPk("kim"));
        assertThat(meta.get(Keys.GSI2SK).s().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(meta.get(Keys.GSI2SK).s()).isEqualTo(Keys.indexKeyForWrite(긴_표시명));
        assertThat(meta.get("displayName").s()).isEqualTo(긴_표시명);

        // then — 되읽은 값은 잘리지 않았고, 표시명 접두사 검색(GSI2)에 걸린다
        assertThat(state.findUser("kim").block()).isEqualTo(직원);
        assertThat(search.searchUsersByDisplayName("가".repeat(10), null, 10).block().items())
                .extracting(UserSummary::employeeId)
                .containsExactly("kim");
    }

    @Test
    @DisplayName("긴 직원 계정명도 저장된다 — 직원 META 의 GSI1SK 도 한도 안으로 자른다")
    void 긴_직원_계정명도_저장된다() {
        // given
        var 직원 = new DirectoryUser("kim", null, 긴_표시명, "김", null, true);

        // when
        state.saveUser(직원).block();

        // then
        Map<String, AttributeValue> meta = 읽는다(Keys.userPk("kim"));
        assertThat(meta.get(Keys.GSI1SK).s().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(meta.get("userName").s()).isEqualTo(긴_표시명);
        assertThat(state.findUser("kim").block()).isEqualTo(직원);
    }

    private Map<String, AttributeValue> 읽는다(String pk) {
        return client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, AttributeValue.fromS(pk), Keys.SK, AttributeValue.fromS(Keys.META)))
                        .consistentRead(true)
                        .build())
                .join()
                .item();
    }
}
