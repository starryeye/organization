package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDirectoryStateRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository repository;
    /** 시간을 손으로 옮길 수 있어야 addedAt 이 보존되는지 볼 수 있다. */
    private MutableClock clock;

    @BeforeEach
    void 저장소를_준비한다() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        repository = new DynamoDbDirectoryStateRepository(client, properties, clock);
    }

    /** 테스트가 지시할 때만 움직이는 시계. */
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

    /** 멤버 아이템의 addedAt 을 직접 읽는다. 저장소 API 는 이 값을 노출하지 않는다. */
    private String addedAt(String groupId, MemberRef member) {
        var response = client.getItem(builder -> builder
                .tableName(properties.getTableName())
                .key(java.util.Map.of(
                        Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                        Keys.SK, Attrs.s(Keys.memberSk(member))))).join();
        return response.item().get("addedAt").s();
    }

    @Test
    @DisplayName("이미 소속된 멤버의 addedAt 은 다시 동기화해도 최초 합류 시각 그대로다")
    void 기존_멤버의_addedAt은_보존된다() {
        // given — kim 이 1월 1일에 합류했다
        var kim = MemberRef.user("kim");
        repository.saveGroup(조직("DEV001", "개발본부", kim)).block();
        String 최초합류 = addedAt("DEV001", kim);

        // when — 한 달 뒤, 다른 사람이 들어오면서 같은 조직이 다시 저장된다
        clock.앞으로(Duration.ofDays(31));
        var park = MemberRef.user("park");
        repository.saveGroup(조직("DEV001", "개발본부", kim, park)).block();

        // then — kim 의 합류 시각은 그대로다. 덮어쓰면 "최초 합류" 가 아니라
        // "마지막 전체 동기화" 를 뜻하게 되어, 매일 도는 스케줄이 값을 무의미하게 만든다.
        assertThat(addedAt("DEV001", kim)).isEqualTo(최초합류);
        // 새로 온 사람은 지금 시각을 갖는다
        assertThat(addedAt("DEV001", park)).isNotEqualTo(최초합류);
    }

    @Test
    @DisplayName("떠났다가 다시 합류하면 addedAt 이 새로 찍힌다")
    void 재합류하면_addedAt이_갱신된다() {
        // given
        var kim = MemberRef.user("kim");
        repository.saveGroup(조직("DEV001", "개발본부", kim)).block();
        String 최초합류 = addedAt("DEV001", kim);

        // when — 빠졌다가
        clock.앞으로(Duration.ofDays(10));
        repository.saveGroup(조직("DEV001", "개발본부")).block();
        // 다시 들어온다
        clock.앞으로(Duration.ofDays(10));
        repository.saveGroup(조직("DEV001", "개발본부", kim)).block();

        // then — 이때는 갱신되는 것이 맞다. 보존 로직이 "한 번 쓰면 영원히" 가
        // 되어버리면 이 경우를 틀리게 만든다.
        assertThat(addedAt("DEV001", kim)).isNotEqualTo(최초합류);
    }
}
