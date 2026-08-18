package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDirectoryStateRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        repository = new DynamoDbDirectoryStateRepository(client, properties);
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id + ",ou=people", id, id + " 님", id + "@example.com", true);
    }

    private static DirectoryGroup 조직(String code, String name, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, name, Set.of(members));
    }

    @Test
    @DisplayName("저장한 직원을 직원 아이디로 그대로 조회한다")
    void 직원을_저장하고_조회한다() {
        // given
        var kim = 직원("kim");

        // when
        repository.saveUser(kim).block();
        var found = repository.findUser("kim").block();

        // then
        assertThat(found).isEqualTo(kim);
    }

    @Test
    @DisplayName("존재하지 않는 직원을 조회하면 빈 결과가 나온다")
    void 없는_직원은_빈_결과다() {
        // given, when
        var found = repository.findUser("ghost").block();

        // then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("저장한 조직을 조직코드로 조회하면 멤버십까지 함께 복원된다")
    void 조직을_멤버십까지_복원한다() {
        // given
        var dev002 = 조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        repository.saveGroup(dev002).block();
        var found = repository.findGroup("DEV002").block();

        // then
        assertThat(found).isEqualTo(dev002);
    }

    @Test
    @DisplayName("조직을 다시 저장하면 사라진 멤버십은 삭제된다")
    void 조직_재저장시_사라진_멤버십은_삭제된다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // when
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        var found = repository.findGroup("DEV002").block();

        // then
        assertThat(found.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("조직명이 바뀌어도 조직코드는 유지되어 멤버십이 보존된다")
    void 조직명_변경시_멤버십이_보존된다() {
        // given
        repository.saveGroup(조직("DEV001", "개발본부", MemberRef.user("park"))).block();

        // when
        repository.saveGroup(조직("DEV001", "플랫폼본부", MemberRef.user("park"))).block();
        var found = repository.findGroup("DEV001").block();

        // then
        assertThat(found.displayName()).isEqualTo("플랫폼본부");
        assertThat(found.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("역참조로 특정 직원이 속한 모든 조직을 찾는다")
    void 직원이_속한_조직을_역참조로_찾는다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("OPS001", "운영팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("SALES1", "영업팀", MemberRef.user("lee"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then
        assertThat(groupIds).containsExactlyInAnyOrder("DEV002", "OPS001");
    }

    @Test
    @DisplayName("역참조는 하위 조직이 어느 상위 조직에 속하는지도 찾는다")
    void 하위조직의_상위조직을_역참조로_찾는다() {
        // given
        repository.saveGroup(조직("DEV001", "개발본부", MemberRef.group("DEV002"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.group("DEV002")).collectList().block();

        // then
        assertThat(groupIds).containsExactly("DEV001");
    }

    @Test
    @DisplayName("조직을 삭제하면 조직 자체와 멤버십 아이템이 모두 사라진다")
    void 조직_삭제시_멤버십도_사라진다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();

        // when
        repository.deleteGroup("DEV002").block();

        // then
        assertThat(repository.findGroup("DEV002").block()).isNull();
        assertThat(repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("전체 교체는 스냅샷에 없는 기존 직원과 조직을 삭제한다")
    void 전체_교체는_사라진_엔트리를_삭제한다() {
        // given
        repository.saveUser(직원("kim")).block();
        repository.saveUser(직원("lee")).block();
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))).block();
        repository.saveGroup(조직("OLD001", "폐지된조직")).block();

        var snapshot = new DirectorySnapshot(
                Map.of("kim", 직원("kim")),
                Map.of("DEV002", 조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        repository.replaceWith(snapshot).block();
        var loaded = repository.loadAll().block();

        // then
        assertThat(loaded.users()).containsOnlyKeys("kim");
        assertThat(loaded.groups()).containsOnlyKeys("DEV002");
        assertThat(loaded.groups().get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("전체 조회는 저장한 직원과 조직을 멤버십까지 그대로 복원한다")
    void 전체_조회가_스냅샷을_복원한다() {
        // given
        var snapshot = new DirectorySnapshot(
                Map.of("kim", 직원("kim"), "park", 직원("park")),
                Map.of("DEV001", 조직("DEV001", "개발본부", MemberRef.group("DEV002"), MemberRef.user("park")),
                       "DEV002", 조직("DEV002", "백엔드팀", MemberRef.user("kim"))));
        repository.replaceWith(snapshot).block();

        // when
        var loaded = repository.loadAll().block();

        // then
        assertThat(loaded).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("한글 조직명이 담긴 조직도 저장하고 복원한다")
    void 한글_조직명도_왕복한다() {
        // given
        var 조직도 = 조직("개발본부", "개발본부", MemberRef.user("kim"));

        // when
        repository.saveGroup(조직도).block();
        var found = repository.findGroup("개발본부").block();

        // then
        assertThat(found).isEqualTo(조직도);
    }
}
