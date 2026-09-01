package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기준선을 OpenFGA 실제 상태에서 읽는다 (설계 §5).
 *
 * <p>여기서 못박는 것은 <b>경합이 남긴 튜플을 다음 터치가 걷어낸다</b> 는 것이다.
 * 경합 자체를 재현하는 테스트는 타이밍에 기대 흔들리므로, 경합이 남겼을 결과를 직접 심는다.
 */
class IncrementalSyncDriftTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        // 재시도 0회. 락 획득 실패를 곧바로 관찰하기 위한 것이고, 재시도 자체는
        // 실제 대기가 필요해 여기서 볼 대상이 아니다.
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, 0);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("경합이 남긴 잘못된 튜플을 다음 터치가 걷어낸다")
    void 잘못된_튜플을_걷어낸다() {
        // given — §1 경합의 최종 상태를 그대로 만든다.
        // DynamoDB: kim 은 DEV001 멤버지만 비활성.
        state.users.put("kim", 직원("kim", false));
        state.users.put("park", 직원("park", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park"))));
        // OpenFGA: 있어서는 안 될 kim 의 튜플이 남아 있다.
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
        checker.allowed.add(RelationTuple.directMember("park", "DEV001"));

        // when — 아무 변경이나 DEV001 을 건드린다
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park")))).block();

        // then — 상태 기준선이었다면 델타가 비어 잘못된 튜플이 살아남는다
        assertThat(writer.deleted)
                .as("비활성 kim 의 튜플은 지워져야 한다")
                .contains(RelationTuple.directMember("kim", "DEV001"));
        assertThat(writer.written)
                .as("park 의 튜플은 이미 있으므로 다시 쓰지 않는다")
                .doesNotContain(RelationTuple.directMember("park", "DEV001"));
    }

    @Test
    @DisplayName("OpenFGA 에 빠진 튜플이 있으면 다시 쓴다")
    void 빠진_튜플을_다시_쓴다() {
        // given — 상태상 있어야 하는데 OpenFGA 에는 없다(커밋 직전 크래시 등)
        state.users.put("park", 직원("park", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("park"))));
        // checker.allowed 는 비어 있다 — OpenFGA 에 아무것도 없다

        // when
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("park")))).block();

        // then
        assertThat(writer.written).contains(RelationTuple.directMember("park", "DEV001"));
    }

    @Test
    @DisplayName("변경 하나에 락을 정확히 한 번 잡고 반드시 반납한다")
    void 락을_잡고_반납한다() {
        // given
        state.users.put("kim", 직원("kim", true));

        // when
        useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).as("반납이 새면 이후 모든 변경이 영구히 막힌다").hasValue(1);
    }

    @Test
    @DisplayName("쓰기가 실패해도 락은 반납된다")
    void 실패해도_반납한다() {
        // given — kim 의 튜플이 실제로 OpenFGA 에 있어야 델타가 비지 않고 실제 쓰기가 걸린다
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
        writer.failFor(tuple -> true);

        // when — 쓰기 실패는 예외가 아니라 TupleWriteResult 의 실패 목록으로 보고된다
        // (설계 §7.2, 부분 실패는 상태로 반영된다)
        var result = useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(lock.released).as("실패해도 락은 반납된다").hasValue(1);
    }

    @Test
    @DisplayName("락을 못 잡으면 아무것도 쓰지 않고 실패한다")
    void 락을_못_잡으면_쓰지_않는다() {
        // given
        lock.failAcquire = true;
        state.users.put("kim", 직원("kim", true));

        // when, then
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThat(writer.written).isEmpty();
        assertThat(writer.deleted).isEmpty();
    }

    @Test
    @DisplayName("쓰기 직전에 리스를 잃었으면 OpenFGA 에 쓰지 않는다")
    void 리스를_잃으면_쓰지_않는다() {
        // given — 계산은 끝났는데 그 사이 GC 정지 등으로 리스가 만료돼 남이 가져간 상황.
        // 늦은 쓰기가 나가면 두 인스턴스가 동시에 쓰는 바로 그 창이다(설계 §4.7).
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThat(writer.written).isEmpty();
        assertThat(writer.deleted).isEmpty();
        assertThat(lock.released).as("실패해도 락은 반납된다").hasValue(1);
    }

    @Test
    @DisplayName("BatchCheck 가 실패하면 상태 기준선으로 폴백하지 않고 실패한다")
    void Check_실패는_폴백하지_않는다() {
        // given — 폴백하면 조용히 옛 동작으로 돌아가고, 그게 하필 어긋남이 생기는 순간이다
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.failFor(tuple -> true);

        // when, then
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(Exception.class);
        assertThat(writer.written).isEmpty();
        assertThat(writer.deleted).isEmpty();
        assertThat(lock.released).as("실패해도 락은 반납된다").hasValue(1);
    }
}
