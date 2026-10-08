package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbDirectorySearchRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository state;
    private DynamoDbDirectorySearchRepository search;

    @BeforeEach
    void 저장소를_준비한다() {
        state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        search = new DynamoDbDirectorySearchRepository(client, properties);
    }

    @Test
    @DisplayName("표시명 접두사로 직원을 찾는다")
    void 표시명_접두사로_찾는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "gd.hong", "홍길동", "a@b.c", true)).block();
        state.saveUser(new DirectoryUser("cs.kim", "e2", "cs.kim", "김철수", "b@b.c", true)).block();

        // when
        var page = search.searchUsersByDisplayName("홍", null, 20).block();

        // then — INCLUDE 프로젝션이 userName·active 까지 실어 오는지 네 필드 전부를 확인한다
        assertThat(page.items()).containsExactly(
                new UserSummary("gd.hong", "gd.hong", "홍길동", true));
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("표시명이 없는 직원은 표시명 검색에 잡히지 않는다")
    void 표시명이_없으면_인덱스에_없다() {
        // given
        state.saveUser(new DirectoryUser("noname", "e3", "noname", null, null, true)).block();

        // when — 어떤 접두사로도 안 잡힌다
        var page = search.searchUsersByDisplayName("n", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("같은 표시명 인덱스를 공유하는 조직은 직원 표시명 검색에 섞이지 않는다")
    void 조직은_직원_표시명_검색에_안_섞인다() {
        // given — 조직 META 에는 소문자 표시명 키가 없어 GSI2 에 실리지 않는다. 이름까지 같은 접두사로 겹치게 두어 확인한다.
        state.saveUser(new DirectoryUser("gd.hong", "e1", "gd.hong", "홍길동", null, true)).block();
        state.saveGroup(new DirectoryGroup("PR001", "g1", "홍보팀", Set.of())).block();

        // when
        var users = search.searchUsersByDisplayName("홍", null, 20).block();
        var groups = search.searchGroupsByDisplayName("홍", null, 20).block();

        // then — 직원 검색에는 직원만, 조직 검색에는 조직만
        assertThat(users.items()).extracting(UserSummary::employeeId).containsExactly("gd.hong");
        assertThat(groups.items()).extracting("orgCode").containsExactly("PR001");
    }

    @Test
    @DisplayName("표시명 검색은 대소문자를 가리지 않는다 — 결과의 표시명은 저장한 그대로다(점검 S19)")
    void 표시명_검색은_대소문자를_가리지_않는다() {
        // given — 대소문자만 다른 두 직원
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();
        state.saveUser(new DirectoryUser("u2", "e2", "u2", "kim younghee", null, true)).block();

        // when
        var 대문자 = search.searchUsersByDisplayName("KIM", null, 20).block();
        var 소문자 = search.searchUsersByDisplayName("kim", null, 20).block();

        // then — 프로젝션이 표시명을 실어 온다(정렬키가 아니게 된 displayName)
        assertThat(대문자.items()).extracting(UserSummary::displayName)
                .containsExactlyInAnyOrder("Kim Chulsoo", "kim younghee");
        assertThat(소문자.items()).extracting(UserSummary::employeeId).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    @DisplayName("직원 META 에는 소문자 표시명 키가 있고, 조직 META 에는 없다 — 조직은 GSI2 에 실리지 않는다")
    void 소문자_표시명_키는_직원에만_있다() {
        // given
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();
        state.saveGroup(new DirectoryGroup("PR001", "g1", "Kim Team", Set.of())).block();

        // when
        Map<String, AttributeValue> 직원 = 원본("USER#u1");
        Map<String, AttributeValue> 조직 = 원본("GROUP#PR001");

        // then
        assertThat(직원.get("displayNameKey").s()).isEqualTo("kim chulsoo");
        assertThat(직원.get("displayName").s()).isEqualTo("Kim Chulsoo");
        assertThat(조직).doesNotContainKey("displayNameKey");
    }

    @Test
    @DisplayName("표시명을 바꾸면 소문자 키도 따라 바뀐다 — 옛 이름으로는 안 찾힌다")
    void 표시명을_바꾸면_키도_바뀐다() {
        // given
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();

        // when
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Lee Chulsoo", null, true)).block();

        // then
        assertThat(search.searchUsersByDisplayName("kim", null, 20).block().items()).isEmpty();
        assertThat(search.searchUsersByDisplayName("LEE", null, 20).block().items())
                .extracting(UserSummary::employeeId).containsExactly("u1");
    }

    @Test
    @DisplayName("표시명이 빈 문자열인 직원도 저장되고, 소문자 표시명 키는 쓰이지 않는다 — 인덱스 키에 빈 문자열을 쓰지 않는다")
    void 빈_표시명은_키를_쓰지_않는다() {
        // given — 표시명이 빈 문자열인 직원
        DirectoryUser 빈_표시명 = new DirectoryUser("blank", "e9", "blank", "", null, true);

        // when — DynamoDB 는 인덱스 키 속성의 빈 문자열을 거절한다. 쓰면 이 저장이 ValidationException 이다
        state.saveUser(빈_표시명).block();

        // then
        assertThat(원본("USER#blank")).doesNotContainKey("displayNameKey");
    }

    /** 본 테이블의 META 아이템 원본. */
    private Map<String, AttributeValue> 원본(String pk) {
        return client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                .consistentRead(true)
                .build()).join().item();
    }

    @Test
    @DisplayName("계정명 접두사로 직원을 찾는다")
    void 계정명_접두사로_찾는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "gd.hong", "홍길동", null, true)).block();
        state.saveUser(new DirectoryUser("cs.kim", "e2", "cs.kim", "김철수", null, true)).block();

        // when
        var page = search.searchUsersByUserName("gd", null, 20).block();

        // then
        assertThat(page.items()).extracting("employeeId").containsExactly("gd.hong");
    }

    @Test
    @DisplayName("조직명 접두사로 조직을 찾는다")
    void 조직명_접두사로_찾는다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV001", "x", "플랫폼개발본부", Set.of())).block();
        state.saveGroup(new DirectoryGroup("OPS001", "y", "인프라본부", Set.of())).block();

        // when
        var page = search.searchGroupsByDisplayName("플랫폼", null, 20).block();

        // then
        assertThat(page.items()).extracting("orgCode").containsExactly("DEV001");
    }

    @Test
    @DisplayName("계정명·조직명 접두사 검색은 대소문자를 가리지 않는다 — 결과의 값은 보낸 그대로다")
    void 접두사_검색은_대소문자를_가리지_않는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "GD.Hong", "홍길동", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "g1", "Dev Team", Set.of())).block();

        // when
        var users = search.searchUsersByUserName("gd.h", null, 20).block();
        var groups = search.searchGroupsByDisplayName("DEV", null, 20).block();

        // then
        assertThat(users.items()).extracting(UserSummary::userName).containsExactly("GD.Hong");
        assertThat(groups.items()).extracting("displayName").containsExactly("Dev Team");
    }

    @Test
    @DisplayName("커서로 다음 페이지를 이어 읽으면 중복도 누락도 없다")
    void 커서로_이어_읽는다() {
        // given — 같은 접두사를 가진 직원 5명
        for (int i = 1; i <= 5; i++) {
            state.saveUser(new DirectoryUser("u" + i, "e" + i, "u" + i, "가나다" + i, null, true)).block();
        }

        // when — 2건씩 끝까지 읽는다
        List<String> collected = new ArrayList<>();
        String cursor = null;
        do {
            var page = search.searchUsersByDisplayName("가나다", cursor, 2).block();
            page.items().forEach(item -> collected.add(item.employeeId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // then
        assertThat(collected).containsExactlyInAnyOrder("u1", "u2", "u3", "u4", "u5");
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("결과가 없으면 빈 페이지이고 커서도 없다")
    void 결과가_없으면_빈_페이지다() {
        // when
        var page = search.searchUsersByDisplayName("없는이름", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다른 인덱스의 커서를 들고 오면 저장소 오류가 아니라 IllegalArgumentException 이다")
    void 다른_인덱스의_커서는_IllegalArgumentException_이다() {
        // given — 계정명 검색(GSI1)이 발급한, 형식은 멀쩡한 커서
        for (int i = 1; i <= 3; i++) {
            state.saveUser(new DirectoryUser("u" + i, "e" + i, "u" + i, "가나다" + i, null, true)).block();
        }
        String gsi1Cursor = search.searchUsersByUserName("u", null, 1).block().nextCursor();
        assertThat(gsi1Cursor).isNotNull();

        // when, then — 그대로 흘려보내면 GSI2 는 exclusiveStartKey 모양이 달라
        // DynamoDbException 으로 거절하고 컨트롤러가 그것을 500 으로 옮긴다.
        // 설계 §9 는 손상된 커서를 400 으로 규정한다.
        assertThatThrownBy(() -> search.searchUsersByDisplayName("가", gsi1Cursor, 20).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("깨진 커서는 Mono 를 만들 때는 던지지 않고, 구독할 때 IllegalArgumentException 으로 나온다")
    void 깨진_커서는_구독_시점에_실패한다() {
        // given
        AtomicReference<Mono<Page<UserSummary>>> built = new AtomicReference<>();

        // when — Mono 조립 자체는 예외 없이 끝나야 한다(Mono.defer 로 감쌌기 때문에 커서
        // 해석이 구독 시점까지 미뤄진다)
        assertThatCode(() -> built.set(search.searchUsersByDisplayName("아무개", "!!not-base64!!", 20)))
                .doesNotThrowAnyException();

        // then — 구독해야 비로소 IllegalArgumentException 이 onError 신호로 나온다
        assertThatThrownBy(() -> built.get().block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("검색어를 바꾼 채 이전 커서를 다시 보내면 IllegalArgumentException 이다 — DynamoDB 오류(500)로 새지 않는다(점검 S16 앞쪽)")
    void 검색어를_바꾼_커서는_거절한다() {
        // given — "u" 검색이 발급한 진짜 커서
        for (int i = 1; i <= 3; i++) {
            state.saveUser(new DirectoryUser("u" + i, "e" + i, "u" + i, "가나다" + i, null, true)).block();
        }
        String 커서 = search.searchUsersByUserName("u", null, 1).block().nextCursor();
        assertThat(커서).isNotNull();

        // when, then — 같은 인덱스·파티션이라 범위 검사는 통과하지만 시작 키가 "v" 접두 밖이다
        assertThatThrownBy(() -> search.searchUsersByUserName("v", 커서, 1).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위는 맞아도 시작 키가 이 검색의 것이 아니면 IllegalArgumentException 이다 — 검색 셋 모두(점검 S16 앞쪽)")
    void 위조한_검색_시작_키는_거절한다() {
        // given — 검색마다 범위(인덱스/파티션)는 맞춘 위조 커서들
        record 검색(String 범위, String 파티션키, String 정렬키, String 파티션, String 종류_PK, Function<String, Page<?>> 묻기) {
        }
        List<검색> 검색들 = List.of(
                new 검색("GSI1/USER_INDEX", Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX, Keys.userPk("u1"),
                        c -> search.searchUsersByUserName("u", c, 1).block()),
                new 검색("GSI2/USER_INDEX", Keys.GSI2PK, Keys.GSI2SK, Keys.USER_INDEX, Keys.userPk("u1"),
                        c -> search.searchUsersByDisplayName("u", c, 1).block()),
                new 검색("GSI1/GROUP_INDEX", Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX, Keys.groupPk("G1"),
                        c -> search.searchGroupsByDisplayName("u", c, 1).block()));

        for (검색 s : 검색들) {
            Map<String, AttributeValue> 정상 = Map.of(
                    Keys.PK, Attrs.s(s.종류_PK()), Keys.SK, Attrs.s(Keys.META),
                    s.파티션키(), Attrs.s(s.파티션()), s.정렬키(), Attrs.s("u1"));
            List<Map<String, AttributeValue>> 위조들 = List.of(
                    바꾼다(정상, s.파티션키(), Attrs.s(Keys.USER_INDEX.equals(s.파티션()) ? Keys.GROUP_INDEX : Keys.USER_INDEX)), // 다른 파티션
                    뺀다(정상, Keys.SK),                                                                                  // 키가 모자람
                    바꾼다(정상, "extra", Attrs.s("x")),                                                                    // 키가 남음
                    바꾼다(정상, s.정렬키(), Attrs.s("x1")),                                                                  // 다른 접두사
                    바꾼다(정상, s.정렬키(), Attrs.s("u" + "x".repeat(1100))),                                                // 정렬키 한도 초과
                    바꾼다(정상, Keys.PK, Attrs.s(Keys.USER_INDEX.equals(s.파티션()) ? Keys.groupPk("G1") : Keys.userPk("u1"))), // 다른 종류의 PK
                    바꾼다(정상, Keys.SK, Attrs.s("MEMBER#USER#u1")));                                                       // META 가 아닌 SK

            // when, then
            for (Map<String, AttributeValue> 위조 : 위조들) {
                String 커서 = Cursor.encode(s.범위(), 위조);
                assertThatThrownBy(() -> s.묻기().apply(커서))
                        .as("%s / %s", s.범위(), 위조)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static Map<String, AttributeValue> 바꾼다(Map<String, AttributeValue> 원래, String 이름, AttributeValue 값) {
        Map<String, AttributeValue> 새것 = new HashMap<>(원래);
        새것.put(이름, 값);
        return 새것;
    }

    private static Map<String, AttributeValue> 뺀다(Map<String, AttributeValue> 원래, String 이름) {
        Map<String, AttributeValue> 새것 = new HashMap<>(원래);
        새것.remove(이름);
        return 새것;
    }

    private void 조직을_심는다(String orgCode, int 직원수, String... 하위조직) {
        Set<MemberRef> members = new LinkedHashSet<>();
        for (int i = 0; i < 직원수; i++) {
            members.add(MemberRef.user("u%03d".formatted(i)));
        }
        for (String child : 하위조직) {
            members.add(MemberRef.group(child));
        }
        state.saveGroup(new DirectoryGroup(orgCode, "ext-" + orgCode, orgCode + "-조직", members)).block();
    }

    @Test
    @DisplayName("직원 멤버를 아이디 순으로 한 쪽씩 읽고, 커서로 이어 읽으면 중복도 누락도 없다 — 하위 조직·META 는 섞이지 않는다(설계 2026-10-06 §3.1)")
    void 직원_멤버를_한_쪽씩_읽는다() {
        // given — 직원 5명, 하위 조직 둘
        조직을_심는다("DEV", 5, "SUB1", "SUB2");

        // when — 2명씩 끝까지 읽는다
        List<String> 읽은것 = new ArrayList<>();
        int 쪽수 = 0;
        String cursor = null;
        do {
            var page = search.findGroupUserMemberIds("DEV", cursor, 2).block();
            읽은것.addAll(page.items());
            cursor = page.nextCursor();
            쪽수++;
        } while (cursor != null && 쪽수 < 10);

        // then
        assertThat(읽은것).containsExactly("u000", "u001", "u002", "u003", "u004");
        assertThat(쪽수).isGreaterThan(1);
    }

    @Test
    @DisplayName("쪽 크기에 딱 맞게 끝나면 커서가 있는 쪽 뒤에 빈 마지막 쪽이 오고, 그 쪽의 커서는 없다 — 중복도 누락도 없다")
    void 쪽_경계에서_빈_마지막_쪽이_온다() {
        // given — 직원 4명, 쪽 크기 2
        조직을_심는다("DEV", 4);

        // when — 커서가 없을 때까지 읽는다
        var 첫쪽 = search.findGroupUserMemberIds("DEV", null, 2).block();
        var 둘째쪽 = search.findGroupUserMemberIds("DEV", 첫쪽.nextCursor(), 2).block();
        var 셋째쪽 = search.findGroupUserMemberIds("DEV", 둘째쪽.nextCursor(), 2).block();

        // then — 표준 신호: 쪽이 가득 차면 커서가 오고, 비어 있는 마지막 쪽에서 끝난다
        assertThat(첫쪽.items()).containsExactly("u000", "u001");
        assertThat(첫쪽.nextCursor()).isNotNull();
        assertThat(둘째쪽.items()).containsExactly("u002", "u003");
        assertThat(둘째쪽.nextCursor()).isNotNull();
        assertThat(셋째쪽.items()).isEmpty();
        assertThat(셋째쪽.nextCursor()).isNull();
    }

    @Test
    @DisplayName("직원 멤버가 없으면 빈 쪽이고 커서도 없다")
    void 직원_멤버가_없으면_빈_쪽이다() {
        // given
        조직을_심는다("EMPTY", 0, "SUB1");

        // when
        var page = search.findGroupUserMemberIds("EMPTY", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다른 조직의 멤버 커서는 IllegalArgumentException 이다 — 다른 파티션을 엉뚱하게 이어 읽지 않는다")
    void 다른_조직의_커서는_거절한다() {
        // given
        조직을_심는다("A", 3);
        조직을_심는다("B", 3);
        String A의_커서 = search.findGroupUserMemberIds("A", null, 1).block().nextCursor();

        // when, then
        assertThatThrownBy(() -> search.findGroupUserMemberIds("B", A의_커서, 1).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위는 맞아도 시작 키가 이 조직의 직원 멤버가 아니면 IllegalArgumentException 이다 — DynamoDB 오류(500)로 새지 않는다(점검 S16 앞쪽)")
    void 위조한_시작_키는_거절한다() {
        // given — 같은 범위로 위조한 커서 넷: 다른 PK, 하위 조직 SK, 속성이 하나 더 있음, 정렬키 한도(1024바이트)를 넘는 SK
        조직을_심는다("DEV", 3);
        String 범위 = "group-members/DEV";
        String 다른_PK = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("OTHER")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.user("u000")))));
        String 조직_SK = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("DEV")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.group("SUB1")))));
        String 남는_속성 = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("DEV")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.user("u000"))), "extra", Attrs.s("x")));
        String 너무_긴_SK = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("DEV")),
                Keys.SK, Attrs.s(Keys.memberSkPrefix(MemberType.USER) + "x".repeat(1100))));

        // when, then
        for (String 위조 : List.of(다른_PK, 조직_SK, 남는_속성, 너무_긴_SK)) {
            assertThatThrownBy(() -> search.findGroupUserMemberIds("DEV", 위조, 1).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("깨진 멤버 커서는 Mono 를 만들 때는 던지지 않고, 구독할 때 IllegalArgumentException 으로 나온다")
    void 깨진_멤버_커서는_구독할_때_실패한다() {
        // given — 형식이 깨진 커서

        // when
        var mono = search.findGroupUserMemberIds("DEV", "!!not-base64!!", 1);

        // then
        assertThatThrownBy(mono::block).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("하위 조직 아이디만 정렬 순으로 읽는다 — 직원 멤버는 섞이지 않는다")
    void 하위_조직_아이디만_읽는다() {
        // given
        조직을_심는다("DEV", 3, "SUB2", "SUB1");

        // when
        var 하위 = search.findChildOrgCodes("DEV").collectList().block();

        // then
        assertThat(하위).containsExactly("SUB1", "SUB2");
    }
}
