package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조직 쪽 커밋은 이 요청이 썼거나 결과를 모르는 부여 줄 가운데 커밋한 뒤의 상태가 요구하지 않는 줄을 되지운다(설계 2026-11-11 U1~U4) —
 * 기존 조직의 결과를 모르는 넣기, 지정의 실패, 새 조직의 실패.
 *
 * <p>Check 는 가짜 writer 의 장부를 본다 — 재시도가 앞 요청이 남긴 줄을 그대로 본다.
 */
class IncrementalSyncUnsourcedUndoTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    /** 활성 직원 u-1·u-2·u-3, 멤버 u-1 인 조직 G, 멤버 없는 조직 A·B·C·D·S. C 의 지정 상위는 A 다. 장부에는 dm(u-1,G)·child(C,A) 가 있다. */
    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker(writer.stored);
        lock = new FakeMutationLock();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        for (String id : new String[]{"u-1", "u-2", "u-3"}) {
            state.users.put(id, new DirectoryUser(id, null, id + "@corp.com", id, null, true));
        }
        state.groups.put("G", new DirectoryGroup("G", "grp-g", "조직", Set.of(MemberRef.user("u-1"))));
        writer.stored.add(dm("u-1", "G"));
        for (String id : new String[]{"A", "B", "C", "D", "S"}) {
            state.groups.put(id, new DirectoryGroup(id, "grp-" + id.toLowerCase(), id, Set.of()));
        }
        state.assignedParents.put("C", "A");
        writer.stored.add(child("C", "A"));
    }

    private static RelationTuple dm(String userId, String groupId) {
        return RelationTuple.directMember(userId, groupId);
    }

    private static RelationTuple child(String childId, String parentId) {
        return RelationTuple.child(childId, parentId);
    }

    /** 지금 적용 중인 델타(appliedDeltas 의 마지막)가 지우기뿐인가 */
    private boolean 지우기만인_델타() {
        return 마지막_델타().toWrite().isEmpty();
    }

    private TupleDelta 마지막_델타() {
        return writer.appliedDeltas.get(writer.appliedDeltas.size() - 1);
    }

    /** 본 쓰기에서만 그 줄의 결과를 모르게 한다 — 되지우기의 지우기(지우기뿐인 델타)는 성공한다. */
    private Predicate<RelationTuple> 본_쓰기의(RelationTuple 줄) {
        return t -> t.equals(줄) && !지우기만인_델타();
    }

    private static GroupChange 넣는다(MemberRef... 멤버) {
        return GroupChange.delta().adding(Set.of(멤버));
    }

    private Set<MemberRef> G의_멤버() {
        return state.groups.get("G").members();
    }

    /** 운영 도구의 토큰으로 온 지정 요청이다. */
    private Mono<IncrementalSyncResult> 지정(String orgCode, String parentOrgCode) {
        return useCase.assignParent(orgCode, parentOrgCode)
                .contextWrite(Context.of(Actor.KEY, Actor.client("org-chart")));
    }

    @Test
    @DisplayName("기존 조직 PATCH 의 넣기가 결과를 모르면 멤버를 저장하지 않고 그 줄을 지운다")
    void 기존_조직_PATCH_의_넣기가_결과를_모르면_멤버를_저장하지_않고_줄을_지운다() {
        // given
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));

        // when
        var 결과 = useCase.changeGroup("G", 넣는다(MemberRef.user("u-2"))).block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(G의_멤버()).containsExactly(MemberRef.user("u-1"));
        assertThat(writer.stored).doesNotContain(dm("u-2", "G")).contains(dm("u-1", "G"));
    }

    @Test
    @DisplayName("되지운 뒤 같은 PATCH 를 다시 보내면 멤버가 저장되고 줄이 있다")
    void 되지운_뒤_같은_PATCH_를_다시_보내면_멤버가_저장되고_줄이_있다() {
        // given
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
        useCase.changeGroup("G", 넣는다(MemberRef.user("u-2"))).block();
        writer.lostResponseFor(t -> false);

        // when
        var 결과 = useCase.changeGroup("G", 넣는다(MemberRef.user("u-2"))).block();

        // then
        assertThat(결과.fullyApplied()).isTrue();
        assertThat(G의_멤버()).containsExactlyInAnyOrder(MemberRef.user("u-1"), MemberRef.user("u-2"));
        assertThat(writer.stored).contains(dm("u-2", "G"));
    }

    @Test
    @DisplayName("되지운 뒤 같은 PUT 을 다시 보내면 멤버가 저장되고 줄이 있다")
    void 되지운_뒤_같은_PUT_을_다시_보내면_멤버가_저장되고_줄이_있다() {
        // given
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
        GroupChange 교체 = GroupChange.replacement("grp-g", "조직", Set.of(MemberRef.user("u-1"), MemberRef.user("u-2")));

        // when — 첫째
        useCase.changeGroup("G", 교체).block();

        // then
        assertThat(G의_멤버()).containsExactly(MemberRef.user("u-1"));
        assertThat(writer.stored).doesNotContain(dm("u-2", "G"));

        // when — 둘째, 주입을 끈다
        writer.lostResponseFor(t -> false);
        useCase.changeGroup("G", 교체).block();

        // then
        assertThat(G의_멤버()).containsExactlyInAnyOrder(MemberRef.user("u-1"), MemberRef.user("u-2"));
        assertThat(writer.stored).contains(dm("u-2", "G"));
    }

    @Test
    @DisplayName("확정 실패한 넣기는 되지울 것이 없어 더 쓰지 않는다")
    void 확정_실패한_넣기는_되지울_것이_없어_더_쓰지_않는다() {
        // given
        writer.failFor(dm("u-2", "G")::equals);

        // when
        useCase.changeGroup("G", 넣는다(MemberRef.user("u-2"))).block();

        // then
        assertThat(writer.appliedDeltas).hasSize(1);
    }

    @Test
    @DisplayName("원래 있던 멤버의 줄은 결과를 몰라도 지우지 않는다")
    void 원래_있던_멤버의_줄은_지우지_않는다() {
        // given — 원래 멤버 u-1 의 줄이 장부에 없다(어긋남). 본 쓰기의 넣기 둘 다 결과를 모르고, 되지우기의 지우기는 성공한다
        writer.stored.remove(dm("u-1", "G"));
        writer.lostResponseFor(본_쓰기의(dm("u-1", "G")).or(본_쓰기의(dm("u-2", "G"))));

        // when
        useCase.changeGroup("G", 넣는다(MemberRef.user("u-1"), MemberRef.user("u-2"))).block();

        // then
        assertThat(마지막_델타().toWrite()).isEmpty();
        assertThat(마지막_델타().toDelete()).containsExactly(dm("u-2", "G"));
        assertThat(writer.stored).contains(dm("u-1", "G"));
        assertThat(G의_멤버()).containsExactly(MemberRef.user("u-1"));
    }

    @Test
    @DisplayName("결과를 모르는 빼기와 넣기가 한 PATCH 에 함께면 넣기만 되지운다")
    void 결과를_모르는_빼기와_넣기가_함께면_넣기만_되지운다() {
        // given — 본 쓰기의 dm(u-1,G) 지우기와 dm(u-2,G) 쓰기가 결과를 모른다
        writer.lostResponseFor(본_쓰기의(dm("u-1", "G")).or(본_쓰기의(dm("u-2", "G"))));

        // when
        useCase.changeGroup("G", GroupChange.delta().removingId("u-1").adding(Set.of(MemberRef.user("u-2")))).block();

        // then — 빼기를 모르니 u-1 은 멤버로 남고, 그 줄은 다시 지우지 않는다
        assertThat(마지막_델타().toWrite()).isEmpty();
        assertThat(마지막_델타().toDelete()).containsExactly(dm("u-2", "G"));
        assertThat(G의_멤버()).containsExactly(MemberRef.user("u-1"));
    }

    @Test
    @DisplayName("하위 조직을 넣는 PATCH 가 결과를 모르면 멤버 행을 저장하지 않고 연결 줄을 지운다")
    void 하위_조직을_넣는_PATCH_가_결과를_모르면_멤버_행을_저장하지_않고_연결_줄을_지운다() {
        // given
        writer.lostResponseFor(본_쓰기의(child("S", "G")));

        // when
        useCase.changeGroup("G", 넣는다(MemberRef.group("S"))).block();

        // then
        assertThat(G의_멤버()).doesNotContain(MemberRef.group("S"));
        assertThat(writer.stored).doesNotContain(child("S", "G"));
    }

    @Test
    @DisplayName("되지우기 직전 리스를 잃으면 지우지 않고 리스 상실로 끝난다 — 조직은 이미 저장돼 있다")
    void 되지우기_직전_리스를_잃으면_지우지_않고_리스_상실로_끝난다_조직은_이미_저장돼_있다() {
        // given — 본 쓰기 뒤의 커밋 직전 확인은 통과하고, 그다음 되지우기 직전 확인부터 잃는다
        writer.lostResponseFor(t -> {
            if (t.equals(dm("u-2", "G"))) {
                lock.failRenewAfterAttempt = lock.renewAttempted.get() + 1;
                return true;
            }
            return false;
        });

        // when
        var 바꾼다 = useCase.changeGroup("G", GroupChange.delta().renamed("새 이름").adding(Set.of(MemberRef.user("u-2"))));

        // then
        assertThatThrownBy(바꾼다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(state.groups.get("G").displayName()).isEqualTo("새 이름");
        assertThat(writer.stored).contains(dm("u-2", "G"));
        assertThat(writer.appliedDeltas).hasSize(1);
    }

    @Test
    @DisplayName("새 연결이 결과를 모르면 지정은 그대로이고 그 줄을 지운다 — 그 뒤 다른 상위를 걸면 그 연결만 남는다")
    void 새_연결이_결과를_모르면_지정은_그대로이고_그_줄을_지운다_그_뒤_다른_상위를_걸면_그것만_남는다() {
        // given
        writer.lostResponseFor(본_쓰기의(child("C", "B")));

        // when — 첫째
        var 첫째 = 지정("C", "B").block();

        // then
        assertThat(첫째.fullyApplied()).isFalse();
        assertThat(state.assignedParents).containsEntry("C", "A");
        assertThat(writer.stored).doesNotContain(child("C", "B"), child("C", "A"));

        // when — 둘째, 주입을 끄고 다른 상위를 건다
        writer.lostResponseFor(t -> false);
        지정("C", "D").block();

        // then
        assertThat(state.assignedParents).containsEntry("C", "D");
        assertThat(writer.stored).filteredOn(t -> t.mentions(RelationTuple.groupRef("C")))
                .containsExactly(child("C", "D"));
    }

    @Test
    @DisplayName("옛 연결 지우기가 결과를 모르고 새 연결이 확정 실패면 되지울 것이 없어 더 쓰지 않는다")
    void 옛_연결_지우기가_결과를_모르고_새_연결이_확정_실패면_더_쓰지_않는다() {
        // given
        writer.lostResponseFor(child("C", "A")::equals);
        writer.failFor(child("C", "B")::equals);

        // when
        지정("C", "B").block();

        // then
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.assignedParents).containsEntry("C", "A");
    }

    @Test
    @DisplayName("같은 상위로 다시 건 요청이 결과를 몰라도 그 줄은 지정이 요구해 더 쓰지 않는다")
    void 같은_상위로_다시_건_요청이_실패해도_더_쓰지_않는다() {
        // given
        writer.stored.remove(child("C", "A"));
        writer.lostResponseFor(child("C", "A")::equals);

        // when
        지정("C", "A").block();

        // then
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.stored).contains(child("C", "A"));
    }

    @Test
    @DisplayName("지정의 되지우기 직전 리스를 잃으면 지우지 않고 리스 상실로 끝난다")
    void 지정의_되지우기_직전_리스를_잃으면_지우지_않고_리스_상실로_끝난다() {
        // given — 본 쓰기 뒤의 커밋 직전 확인은 통과하고, 그다음 되지우기 직전 확인부터 잃는다
        writer.lostResponseFor(t -> {
            if (t.equals(child("C", "B"))) {
                lock.failRenewAfterAttempt = lock.renewAttempted.get() + 1;
                return true;
            }
            return false;
        });

        // when
        var 지정한다 = 지정("C", "B");

        // then
        assertThatThrownBy(지정한다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.stored).contains(child("C", "B"));
        assertThat(state.assignedParents).containsEntry("C", "A");
    }

    @Test
    @DisplayName("상위가 먼저 적어 둔 새 조직이 일부 실패하면 상위 연결 줄도 되지운다")
    void 새_조직의_상위_연결_줄도_되지운다() {
        // given
        state.groups.put("P", new DirectoryGroup("P", "grp-p", "상위 조직", Set.of(MemberRef.group("g-new"))));
        writer.failFor(dm("u-2", "g-new")::equals);

        // when
        useCase.createGroup(new DirectoryGroup("g-new", "grp-new", "새 조직",
                Set.of(MemberRef.user("u-1"), MemberRef.user("u-2")))).block();

        // then
        assertThat(state.groups).doesNotContainKey("g-new");
        assertThat(writer.stored).doesNotContain(child("g-new", "P"), dm("u-1", "g-new"));
    }
}
