package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.model.ResourceTimes;
import dev.starryeye.organization.core.model.Timestamped;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDirectoryQueryRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository state;
    private DynamoDbDirectoryQueryRepository query;

    @BeforeEach
    void 저장소를_준비한다() {
        state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        query = new DynamoDbDirectoryQueryRepository(client, properties, state);
    }

    private void 직원(String id, String userName, String externalId) {
        state.saveUser(new DirectoryUser(id, externalId, userName, "직원 " + id, null, true)).block();
    }

    private void 조직(String id, String displayName, String externalId) {
        state.saveGroup(new DirectoryGroup(id, externalId, displayName, Set.of())).block();
    }

    /** 대소문자가 섞인 일곱 명. 소문자 순서는 A b c D e F g 다. */
    private void 일곱_명을_둔다() {
        for (String name : List.of("b", "A", "c", "D", "e", "F", "g")) {
            직원(name, name, "ext-" + name);
        }
    }

    private List<String> 끝까지_읽는다(int limit, boolean descending) {
        List<String> seen = new ArrayList<>();
        String from = null;
        do {
            Page<Timestamped<DirectoryUser>> page = query.listUsers(from, limit, descending).block();
            page.items().forEach(user -> seen.add(user.value().id()));
            from = page.nextCursor();
        } while (from != null);
        return seen;
    }

    @Test
    @DisplayName("userName 이 같으면 대소문자가 달라도 찾는다 — 돌려주는 값은 저장된 그대로다")
    void userName_일치는_대소문자를_가리지_않는다() {
        // given
        직원("Kim.Lee", "Kim.Lee", "ext-kim");
        직원("park", "park", "ext-park");

        // when
        List<DirectoryUser> found = query.findUsersByUserName("kIM.lEE").map(Timestamped::value).collectList().block();

        // then
        assertThat(found).extracting(DirectoryUser::userName).containsExactly("Kim.Lee");
    }

    @Test
    @DisplayName("조직명이 같으면 대소문자가 달라도 찾는다")
    void 조직명_일치는_대소문자를_가리지_않는다() {
        // given
        조직("DEV001", "Dev Team", "grp-dev");

        // when
        List<GroupHeader> found = query.findGroupHeadersByDisplayName("DEV TEAM").map(Timestamped::value).collectList().block();

        // then
        assertThat(found).containsExactly(new GroupHeader("DEV001", "grp-dev", "Dev Team"));
    }

    @Test
    @DisplayName("externalId 가 같은 직원과 조직을 종류별로 가른다")
    void externalId_는_종류별로_가른다() {
        // given — GSI3 는 직원 아이템과 조직 META 를 한 인덱스에 싣는다
        직원("u1", "u1", "X-1");
        조직("G1", "조직 1", "X-1");

        // when
        List<DirectoryUser> users = query.findUsersByExternalId("X-1").map(Timestamped::value).collectList().block();
        List<GroupHeader> groups = query.findGroupHeadersByExternalId("X-1").map(Timestamped::value).collectList().block();

        // then
        assertThat(users).extracting(DirectoryUser::id).containsExactly("u1");
        assertThat(groups).extracting(GroupHeader::id).containsExactly("G1");
    }

    @Test
    @DisplayName("externalId 는 대소문자를 가린다")
    void externalId_는_대소문자를_가린다() {
        // given
        직원("u1", "u1", "X-1");

        // when
        List<DirectoryUser> found = query.findUsersByExternalId("x-1").map(Timestamped::value).collectList().block();

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("externalId 로 찾은 직원은 본 테이블의 최신 값이다")
    void externalId_로_찾으면_최신_값이다() {
        // given
        state.saveUser(new DirectoryUser("u1", "X-1", "u1", "옛 이름", null, true)).block();
        state.saveUser(new DirectoryUser("u1", "X-1", "u1", "새 이름", null, false)).block();

        // when
        DirectoryUser found = query.findUsersByExternalId("X-1").map(Timestamped::value).blockFirst();

        // then
        assertThat(found.displayName()).isEqualTo("새 이름");
        assertThat(found.active()).isFalse();
    }

    @Test
    @DisplayName("오름차순으로 끝까지 이어 읽으면 전원이 userName 소문자 순으로 한 번씩 나오고 조직은 섞이지 않는다")
    void 오름차순으로_끝까지_읽는다() {
        // given
        일곱_명을_둔다();
        조직("G1", "a 조직", "grp-1");

        // when
        List<String> seen = 끝까지_읽는다(3, false);

        // then
        assertThat(seen).containsExactly("A", "b", "c", "D", "e", "F", "g");
    }

    @Test
    @DisplayName("내림차순으로 끝까지 이어 읽으면 역순으로 한 번씩 나온다")
    void 내림차순으로_끝까지_읽는다() {
        // given
        일곱_명을_둔다();

        // when
        List<String> seen = 끝까지_읽는다(3, true);

        // then
        assertThat(seen).containsExactly("g", "F", "e", "D", "c", "b", "A");
    }

    @Test
    @DisplayName("앞의 n 건을 건너뛴 위치에서 이어 읽으면 순서대로 읽은 것과 같다")
    void 건너뛴_위치는_순서대로_읽은_위치와_같다() {
        // given
        일곱_명을_둔다();

        // when
        String position = query.skipUsers(3, false).block();
        Page<Timestamped<DirectoryUser>> page = query.listUsers(position, 2, false).block();

        // then
        assertThat(page.items()).extracting(found -> found.value().id()).containsExactly("D", "e");
        assertThat(query.skipUsers(0, false).blockOptional()).isEmpty();
        assertThat(query.listUsers(query.skipUsers(100, false).block(), 2, false).block().items()).isEmpty();
    }

    @Test
    @DisplayName("직원 수와 조직 수를 따로 센다")
    void 직원과_조직을_따로_센다() {
        // given
        일곱_명을_둔다();
        조직("G1", "조직 1", "grp-1");
        조직("G2", "조직 2", "grp-2");

        // when, then
        assertThat(query.countUsers().block()).isEqualTo(7L);
        assertThat(query.countGroups().block()).isEqualTo(2L);
    }

    @Test
    @DisplayName("조직 목록도 조직명 소문자 순으로 이어 읽는다")
    void 조직_목록을_이어_읽는다() {
        // given
        조직("G1", "beta", "grp-1");
        조직("G2", "Alpha", "grp-2");
        조직("G3", "gamma", "grp-3");

        // when
        Page<Timestamped<GroupHeader>> first = query.listGroupHeaders(null, 2, false).block();
        Page<Timestamped<GroupHeader>> second = query.listGroupHeaders(first.nextCursor(), 2, false).block();

        // then
        assertThat(first.items()).extracting(found -> found.value().id()).containsExactly("G2", "G1");
        assertThat(second.items()).extracting(found -> found.value().id()).containsExactly("G3");
    }

    @Test
    @DisplayName("이어 읽을 위치의 직원이 그사이 지워져도 그 다음부터 이어 읽는다")
    void 이어_읽을_위치가_지워져도_이어_읽는다() {
        // given — 첫 페이지의 마지막 직원(c)을 페이지 사이에 지운다
        일곱_명을_둔다();
        Page<Timestamped<DirectoryUser>> first = query.listUsers(null, 3, false).block();
        state.deleteUser("c").block();

        // when
        Page<Timestamped<DirectoryUser>> second = query.listUsers(first.nextCursor(), 3, false).block();

        // then
        assertThat(second.items()).extracting(found -> found.value().id()).containsExactly("D", "e", "F");
    }

    @Test
    @DisplayName("목록과 userName 일치 조회에도 이름이 실린다 — GSI1 은 속성을 전부 담는다")
    void 목록에도_이름이_실린다() {
        // given
        PersonName 이름 = new PersonName(null, "홍", "길동", null, null, null);
        state.saveUser(new DirectoryUser("hong", "ext-hong", "hong", "홍길동", null, true, 이름)).block();

        // when, then
        assertThat(query.findUsersByUserName("HONG").blockFirst().value().name()).isEqualTo(이름);
        assertThat(query.listUsers(null, 10, false).block().items().get(0).value().name()).isEqualTo(이름);
    }

    /** 이 시각으로 쓰는 상태 저장소. 같은 테이블을 쓴다. */
    private DynamoDbDirectoryStateRepository 시각을_정한_저장소(String at) {
        return new DynamoDbDirectoryStateRepository(client, properties, Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("직원 단건·목록·userName·externalId 찾기가 생성 시각과 변경 시각을 함께 준다")
    void 직원_읽기가_두_시각을_준다() {
        // given — 처음 만들고 한 시간 뒤 표시명을 바꾼다
        DirectoryUser 처음 = new DirectoryUser("kim", "ext-kim", "kim", "김철수", null, true);
        시각을_정한_저장소("2026-01-01T00:00:00Z").saveUser(처음).block();
        시각을_정한_저장소("2026-01-01T01:00:00Z").saveUser(처음, 처음.withDisplayName("새 이름")).block();
        ResourceTimes 기대 = new ResourceTimes(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

        // when
        Timestamped<DirectoryUser> 단건 = query.findUser("kim").block();
        Timestamped<DirectoryUser> 이름 = query.findUsersByUserName("KIM").blockFirst();
        Timestamped<DirectoryUser> 외부 = query.findUsersByExternalId("ext-kim").blockFirst();
        Timestamped<DirectoryUser> 목록 = query.listUsers(null, 10, false).block().items().get(0);

        // then
        assertThat(단건.value().displayName()).isEqualTo("새 이름");
        assertThat(List.of(단건.times(), 이름.times(), 외부.times(), 목록.times())).containsOnly(기대);
    }

    @Test
    @DisplayName("없는 직원의 단건 읽기는 빈 결과다")
    void 없는_직원은_빈_결과다() {
        // when, then
        assertThat(query.findUser("없음").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("생성 시각이 없는 옛 아이템은 생성 시각이 null 이고 변경 시각은 있다")
    void 옛_아이템은_생성_시각이_없다() {
        // given — 이 설계 전의 모양(createdAt 없음)을 직접 쓴다
        client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(Map.of(
                        Keys.PK, AttributeValue.fromS(Keys.userPk("old")),
                        Keys.SK, AttributeValue.fromS(Keys.META),
                        Keys.GSI1PK, AttributeValue.fromS(Keys.USER_INDEX),
                        Keys.GSI1SK, AttributeValue.fromS("old"),
                        "userName", AttributeValue.fromS("old"),
                        "active", AttributeValue.fromBool(true),
                        "updatedAt", AttributeValue.fromS("2025-12-31T00:00:00Z")))
                .build()).join();

        // when
        Timestamped<DirectoryUser> found = query.findUser("old").block();

        // then
        assertThat(found.times()).isEqualTo(new ResourceTimes(null, Instant.parse("2025-12-31T00:00:00Z")));
    }

    @Test
    @DisplayName("조직 단건·목록·조직명·externalId 찾기가 생성 시각과 변경 시각을 함께 준다")
    void 조직_읽기가_두_시각을_준다() {
        // given — 처음 만들고 한 시간 뒤 멤버를 넣는다(멤버만 바뀌어도 조직이 바뀐 것이다)
        시각을_정한_저장소("2026-01-01T00:00:00Z").saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발", Set.of())).block();
        시각을_정한_저장소("2026-01-01T01:00:00Z")
                .saveGroupChange(new GroupHeader("DEV", "ext-DEV", "개발"), Set.of(MemberRef.user("kim")), Set.of())
                .block();
        ResourceTimes 기대 = new ResourceTimes(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"));

        // when
        Timestamped<GroupHeader> 단건 = query.findGroupHeader("DEV").block();
        Timestamped<GroupHeader> 이름 = query.findGroupHeadersByDisplayName("개발").blockFirst();
        Timestamped<GroupHeader> 외부 = query.findGroupHeadersByExternalId("ext-DEV").blockFirst();
        Timestamped<GroupHeader> 목록 = query.listGroupHeaders(null, 10, false).block().items().get(0);

        // then
        assertThat(단건.value()).isEqualTo(new GroupHeader("DEV", "ext-DEV", "개발"));
        assertThat(List.of(단건.times(), 이름.times(), 외부.times(), 목록.times())).containsOnly(기대);
        assertThat(query.findGroupHeader("없음").blockOptional()).isEmpty();
    }
}
