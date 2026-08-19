package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbDirectorySearchRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository state;
    private DynamoDbDirectorySearchRepository search;

    @BeforeEach
    void 저장소를_준비한다() {
        state = new DynamoDbDirectoryStateRepository(client, properties);
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
}
