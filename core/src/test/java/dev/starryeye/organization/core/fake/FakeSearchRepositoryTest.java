package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FakeSearchRepositoryTest {

    private FakeSearchRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FakeSearchRepository();
    }

    @Test
    @DisplayName("접두사가 맞지 않으면 항목이 제외된다")
    void 접두사_필터링() {
        // given
        repository.users.add(new UserSummary("id1", "alice", "Alice", true));
        repository.users.add(new UserSummary("id2", "bob", "Bob", true));
        repository.users.add(new UserSummary("id3", "charlie", "Charlie", true));

        // when
        Page<UserSummary> result = repository.searchUsersByUserName("al", null, 10).block();

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).userName()).isEqualTo("alice");
    }

    @Test
    @DisplayName("결과는 정렬되어 있다")
    void 결과_정렬() {
        // given
        repository.users.add(new UserSummary("id1", "charlie", "Charlie", true));
        repository.users.add(new UserSummary("id2", "alice", "Alice", true));
        repository.users.add(new UserSummary("id3", "bob", "Bob", true));

        // when
        Page<UserSummary> result = repository.searchUsersByUserName("", null, 10).block();

        // then
        assertThat(result.items())
                .extracting(UserSummary::userName)
                .containsExactly("alice", "bob", "charlie");
    }

    @Test
    @DisplayName("페이징은 2씩 5개를 모두 반환하고 커서가 종료된다")
    void 페이징_전체_순회() {
        // given
        repository.users.add(new UserSummary("id1", "alice", "Alice", true));
        repository.users.add(new UserSummary("id2", "bob", "Bob", true));
        repository.users.add(new UserSummary("id3", "charlie", "Charlie", true));
        repository.users.add(new UserSummary("id4", "david", "David", true));
        repository.users.add(new UserSummary("id5", "eve", "Eve", true));

        // when
        Page<UserSummary> page1 = repository.searchUsersByUserName("", null, 2).block();
        Page<UserSummary> page2 = repository.searchUsersByUserName("", page1.nextCursor(), 2).block();
        Page<UserSummary> page3 = repository.searchUsersByUserName("", page2.nextCursor(), 2).block();

        // then
        // 첫 페이지: 2개, 다음 커서 있음
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.items()).extracting(UserSummary::userName).containsExactly("alice", "bob");

        // 두 번째 페이지: 2개, 다음 커서 있음
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.hasNext()).isTrue();
        assertThat(page2.items()).extracting(UserSummary::userName).containsExactly("charlie", "david");

        // 세 번째 페이지: 1개, 다음 커서 없음
        assertThat(page3.items()).hasSize(1);
        assertThat(page3.hasNext()).isFalse();
        assertThat(page3.items()).extracting(UserSummary::userName).containsExactly("eve");

        // 전체 검증: 중복 없음
        List<String> allNames = new ArrayList<>();
        allNames.addAll(page1.items().stream().map(UserSummary::userName).toList());
        allNames.addAll(page2.items().stream().map(UserSummary::userName).toList());
        allNames.addAll(page3.items().stream().map(UserSummary::userName).toList());
        assertThat(allNames).containsExactly("alice", "bob", "charlie", "david", "eve");
    }

    @Test
    @DisplayName("displayName 이 null 인 직원은 displayName 검색에서 제외된다")
    void displayName_null_제외() {
        // given
        repository.users.add(new UserSummary("id1", "alice", "Alice", true));
        repository.users.add(new UserSummary("id2", "bob", null, true));  // displayName이 null
        repository.users.add(new UserSummary("id3", "charlie", "Charlie", true));

        // when
        Page<UserSummary> result = repository.searchUsersByDisplayName("", null, 10).block();

        // then - displayName이 null인 bob은 제외됨
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(UserSummary::displayName).containsExactly("Alice", "Charlie");
    }
}
