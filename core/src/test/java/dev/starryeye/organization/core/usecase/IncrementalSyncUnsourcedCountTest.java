package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.port.TraceIds;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조직 쪽 쓰기가 원천 없는 부여 줄을 남겼을 수 있는 요청을 한 번씩 센다 — {@code scim.writes.unsourced}(설계 2026-11-11 U5~U7). 커밋 자리를 마치지 못하고
 * 끝났는데 보냈을 수 있는 부여 줄 가운데 요구하지 않는 줄이 있거나(U5 a), 되지우기가 실패한(U5 b) 요청이다. 원천이 있는 줄만 남긴 요청, 보내지 않은 요청,
 * 조직 쪽이 아닌 쓰기는 세지 않는다.
 *
 * <p>Check 는 가짜 writer 의 장부를 본다. "쓰는 도중 리스 상실" 은 갱신 주기를 100ms 로 줄이고 쓰는 사이 갱신을 실패시켜 만든다 — 쓰기는 1초 걸리게 해
 * 리스 상실이 먼저 온다({@link IncrementalSyncLeaseTest} 의 수법).
 */
class IncrementalSyncUnsourcedCountTest {

    private static final Duration 기본_갱신_주기 = Duration.ofSeconds(10);
    private static final Duration 짧은_갱신_주기 = Duration.ofMillis(100);
    private static final Duration 늦춤 = Duration.ofSeconds(1);
    private static final Duration 기다림 = Duration.ofSeconds(5);

    private final AtomicInteger 남긴_요청 = new AtomicInteger();
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    /** 장부에는 dm(u-1,G)·child(C,A) 가 있다. 저장소는 {@link #채운다} 가 채운다. */
    @BeforeEach
    void 준비한다() {
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker(writer.stored);
        lock = new FakeMutationLock();
        writer.stored.add(dm("u-1", "G"));
        writer.stored.add(child("C", "A"));
        저장소를_쓴다(new FakeStateRepository(), 기본_갱신_주기);
    }

    /** 활성 직원 u-1·u-2·u-3, 멤버 u-1 인 조직 G, 멤버 없는 조직 A·B·C·D. C 의 지정 상위는 A 다. */
    private static FakeStateRepository 채운다(FakeStateRepository state) {
        for (String id : new String[]{"u-1", "u-2", "u-3"}) {
            state.users.put(id, new DirectoryUser(id, null, id + "@corp.com", id, null, true));
        }
        state.groups.put("G", new DirectoryGroup("G", "grp-g", "조직", Set.of(MemberRef.user("u-1"))));
        for (String id : new String[]{"A", "B", "C", "D"}) {
            state.groups.put(id, new DirectoryGroup(id, "grp-" + id.toLowerCase(), id, Set.of()));
        }
        state.assignedParents.put("C", "A");
        return state;
    }

    private void 저장소를_쓴다(FakeStateRepository 저장소, Duration renewInterval) {
        state = 채운다(저장소);
        useCase = 유스케이스(state, renewInterval);
    }

    private IncrementalSyncUseCase 유스케이스(FakeStateRepository state, Duration renewInterval) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, renewInterval,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, AuditRecorder.NONE, TraceIds.NONE,
                Duration.ofSeconds(30), RevocationObserver.NOOP, 남긴_요청::incrementAndGet);
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

    /** 본 쓰기에서만 그 줄의 결과를 모르게 한다 — 되지우기의 지우기(지우기뿐인 델타)는 걸리지 않는다. */
    private Predicate<RelationTuple> 본_쓰기의(RelationTuple 줄) {
        return t -> t.equals(줄) && !지우기만인_델타();
    }

    /** IdP 의 토큰으로 온 SCIM 요청이다. */
    private static <T> Mono<T> IdP_로(Mono<T> 요청) {
        return 요청.contextWrite(Context.of(Actor.KEY, Actor.client("idp")));
    }

    /** G 에 u-2 를 넣는 PATCH. */
    private Mono<IncrementalSyncResult> u2를_넣는다() {
        return IdP_로(useCase.changeGroup("G", GroupChange.delta().adding(Set.of(MemberRef.user("u-2")))));
    }

    /** 운영 도구의 토큰으로 온 지정 요청이다. */
    private Mono<IncrementalSyncResult> 지정(String orgCode, String parentOrgCode) {
        return useCase.assignParent(orgCode, parentOrgCode)
                .contextWrite(Context.of(Actor.KEY, Actor.client("org-chart")));
    }

    // ---- 센다 ----

    @Test
    @DisplayName("새 조직의 되지우기 결과에 실패가 담기면 센다")
    void 새_조직의_되지우기_결과에_실패가_담기면_센다() {
        // given — 본 쓰기의 dm(u-2,g-new) 가 확정 실패하고, 되지우기의 지우기도 실패한다
        writer.failFor(t -> t.equals(dm("u-2", "g-new")) || 지우기만인_델타());

        // when
        var 결과 = IdP_로(useCase.createGroup(new DirectoryGroup("g-new", "grp-new", "새 조직",
                Set.of(MemberRef.user("u-1"), MemberRef.user("u-2"))))).block();

        // then
        assertThat(결과.result().fullyApplied()).isFalse();
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("기존 조직의 되지우기 결과에 실패가 담기면 센다")
    void 기존_조직의_되지우기_결과에_실패가_담기면_센다() {
        // given
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
        writer.failFor(t -> 지우기만인_델타());

        // when
        var 결과 = u2를_넣는다().block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("지정의 되지우기 결과에 실패가 담기면 센다")
    void 지정의_되지우기_결과에_실패가_담기면_센다() {
        // given
        writer.lostResponseFor(본_쓰기의(child("C", "B")));
        writer.failFor(t -> 지우기만인_델타());

        // when
        var 결과 = 지정("C", "B").block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("되지우기가 오류로 끝나면 센다")
    void 되지우기가_오류로_끝나면_센다() {
        // given — 되지우기(지우기뿐인 델타)가 차단기에 멈춘다
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
        writer.abortWhen(d -> d.toWrite().isEmpty() && !d.toDelete().isEmpty());

        // when
        var 결과 = u2를_넣는다().block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("되지우기 전에 리스를 잃으면 한 번만 센다")
    void 되지우기_전에_리스를_잃으면_한_번만_센다() {
        // given — 본 쓰기 뒤의 커밋 직전 확인은 통과하고, 그다음 되지우기 직전 확인부터 잃는다
        writer.lostResponseFor(t -> {
            if (t.equals(dm("u-2", "G")) && !지우기만인_델타()) {
                lock.failRenewAfterAttempt = lock.renewAttempted.get() + 1;
                return true;
            }
            return false;
        });

        // when
        var 넣는다 = u2를_넣는다();

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("넣기를 쓴 뒤 조직 저장이 실패하면 센다")
    void 넣기를_쓴_뒤_조직_저장이_실패하면_센다() {
        // given
        저장소를_쓴다(new 조직_변경_저장이_실패하는_저장소(), 기본_갱신_주기);

        // when
        var 넣는다 = u2를_넣는다();

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(IllegalStateException.class).hasMessage("저장 실패(테스트)");
        assertThat(writer.stored).contains(dm("u-2", "G"));
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("넣기를 쓴 묶음 뒤 차단기로 멈추면 센다")
    void 넣기를_쓴_묶음_뒤_차단기로_멈추면_센다() {
        // given
        writer.abortWhen(d -> d.toWrite().contains(dm("u-2", "G")));

        // when
        var 넣는다 = u2를_넣는다();

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(TupleWriteAbortedException.class);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("쓰는 도중 리스를 잃어 알려 오지 않은 넣기가 있으면 센다")
    void 쓰는_도중_리스를_잃어_알려_오지_않은_넣기가_있으면_센다() {
        // given — 반영은 곧바로 하고 알림은 1초 뒤다. 쓰는 사이 리스를 잃는다
        저장소를_쓴다(new FakeStateRepository(), 짧은_갱신_주기);
        writer.delayBeforeReport = 늦춤;
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> u2를_넣는다().block(기다림)).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.stored).contains(dm("u-2", "G"));
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("알려 온 넣기 뒤에 리스를 잃어도 센다")
    void 알려_온_넣기_뒤에_리스를_잃어도_센다() {
        // given — 묶음은 알리고 전체 응답이 1초 늦는 사이 리스를 잃는다
        저장소를_쓴다(new FakeStateRepository(), 짧은_갱신_주기);
        writer.delay = 늦춤;
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> u2를_넣는다().block(기다림)).isInstanceOf(LockUnavailableException.class);
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("지정을 쓴 뒤 저장이 실패하면 센다")
    void 지정을_쓴_뒤_저장이_실패하면_센다() {
        // given
        저장소를_쓴다(new 지정_저장이_실패하는_저장소(), 기본_갱신_주기);

        // when
        var 지정한다 = 지정("C", "B");

        // then
        assertThatThrownBy(지정한다::block).isInstanceOf(IllegalStateException.class).hasMessage("저장 실패(테스트)");
        assertThat(writer.stored).contains(child("C", "B"));
        assertThat(남긴_요청).hasValue(1);
    }

    @Test
    @DisplayName("되지우는 도중 리스를 잃어도 한 번만 센다")
    void 되지우는_도중_리스를_잃어도_한_번만_센다() {
        // given — 본 쓰기의 넣기가 결과를 모른다. 두 번째 부름(되지우기)만 알림을 1초 늦추고, 그사이 리스를 잃는다
        저장소를_쓴다(new FakeStateRepository(), 짧은_갱신_주기);
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
        AtomicInteger 부름 = new AtomicInteger();
        writer.onApply(() -> {
            if (부름.incrementAndGet() == 2) {
                writer.delayBeforeReport = 늦춤;
                lock.failRenew = true;
            }
        });

        // when, then
        assertThatThrownBy(() -> u2를_넣는다().block(기다림)).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(2);
        assertThat(남긴_요청).hasValue(1);
    }

    // ---- 세지 않는다 ----

    @Test
    @DisplayName("성공은 세지 않는다")
    void 성공은_세지_않는다() {
        // when
        var 결과 = u2를_넣는다().block();

        // then
        assertThat(결과.fullyApplied()).isTrue();
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("커밋과 되지우기를 마친 부분 반영은 세지 않는다")
    void 커밋과_되지우기를_마친_부분_반영은_세지_않는다() {
        // given
        writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));

        // when
        var 결과 = u2를_넣는다().block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(writer.stored).doesNotContain(dm("u-2", "G"));
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("확정 실패뿐이면 세지 않는다")
    void 확정_실패뿐이면_세지_않는다() {
        // given
        writer.failFor(t -> true);

        // when
        var 결과 = u2를_넣는다().block();

        // then
        assertThat(결과.fullyApplied()).isFalse();
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("Check 오류는 세지 않는다")
    void Check_오류는_세지_않는다() {
        // given
        checker.failFor(t -> true);

        // when
        var 넣는다 = u2를_넣는다();

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(IllegalStateException.class);
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("쓰기 직전 리스 확인이 실패하면 세지 않는다")
    void 쓰기_직전_리스_확인이_실패하면_세지_않는다() {
        // given
        lock.failRenew = true;

        // when
        var 넣는다 = u2를_넣는다();

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("같은 상위로 다시 건 요청이 커밋 직전 리스를 잃어도 세지 않는다 — 그 줄은 저장된 지정이 요구한다")
    void 같은_상위로_다시_건_요청이_커밋_직전_리스를_잃어도_세지_않는다() {
        // given — 장부에서 child(C,A) 가 빠져 있어 다시 쓴다. 쓴 뒤의 커밋 직전 확인부터 잃는다
        writer.stored.remove(child("C", "A"));
        writer.onApply(() -> lock.failRenewAfterAttempt = lock.renewAttempted.get());

        // when
        var 지정한다 = 지정("C", "A");

        // then
        assertThatThrownBy(지정한다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.stored).contains(child("C", "A"));
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("기존 멤버의 어긋남만 고친 뒤 커밋이 실패하면 세지 않는다 — 그 줄은 저장된 멤버가 요구한다")
    void 기존_멤버의_어긋남만_고친_뒤_커밋이_실패하면_세지_않는다() {
        // given — 장부에서 기존 멤버 u-1 의 줄이 빠져 있다
        writer.stored.remove(dm("u-1", "G"));
        저장소를_쓴다(new 조직_변경_저장이_실패하는_저장소(), 기본_갱신_주기);

        // when
        var 넣는다 = IdP_로(useCase.changeGroup("G", GroupChange.delta().adding(Set.of(MemberRef.user("u-1")))));

        // then
        assertThatThrownBy(넣는다::block).isInstanceOf(IllegalStateException.class).hasMessage("저장 실패(테스트)");
        assertThat(writer.stored).contains(dm("u-1", "G"));
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("확정 실패만 알린 뒤 취소되면 세지 않는다")
    void 확정_실패만_알린_뒤_취소되면_세지_않는다() {
        // given — 묶음은 확정 실패만 알리고 전체 응답이 1초 늦는 사이 리스를 잃는다
        저장소를_쓴다(new FakeStateRepository(), 짧은_갱신_주기);
        writer.failFor(t -> true);
        writer.delay = 늦춤;
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> u2를_넣는다().block(기다림)).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("직원 쓰기의 커밋 오류는 세지 않는다")
    void 직원_쓰기의_커밋_오류는_세지_않는다() {
        // given — 비활성 직원 u-4 가 G 의 멤버다
        저장소를_쓴다(new 직원_저장이_실패하는_저장소(), 기본_갱신_주기);
        state.users.put("u-4", new DirectoryUser("u-4", null, "u-4@corp.com", "u-4", null, false));
        state.groups.put("G", new DirectoryGroup("G", "grp-g", "조직", Set.of(MemberRef.user("u-1"), MemberRef.user("u-4"))));

        // when
        var 바꾼다 = IdP_로(useCase.changeUser("u-4", u -> u.withActive(true)));

        // then
        assertThatThrownBy(바꾼다::block).isInstanceOf(IllegalStateException.class).hasMessage("저장 실패(테스트)");
        assertThat(writer.stored).contains(dm("u-4", "G"));
        assertThat(남긴_요청).hasValue(0);
    }

    @Test
    @DisplayName("조직 삭제의 오류는 세지 않는다")
    void 조직_삭제의_오류는_세지_않는다() {
        // given
        저장소를_쓴다(new 조직_삭제가_실패하는_저장소(), 기본_갱신_주기);

        // when
        var 지운다 = IdP_로(useCase.removeGroup("G"));

        // then
        assertThatThrownBy(지운다::block).isInstanceOf(IllegalStateException.class).hasMessage("저장 실패(테스트)");
        assertThat(남긴_요청).hasValue(0);
    }

    private static class 조직_변경_저장이_실패하는_저장소 extends FakeStateRepository {
        @Override
        public Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {
            return Mono.error(new IllegalStateException("저장 실패(테스트)"));
        }
    }

    private static class 지정_저장이_실패하는_저장소 extends FakeStateRepository {
        @Override
        public Mono<Void> saveAssignedParent(String orgCode, String parentOrgCode, String previousParentOrgCode) {
            return Mono.error(new IllegalStateException("저장 실패(테스트)"));
        }
    }

    private static class 직원_저장이_실패하는_저장소 extends FakeStateRepository {
        @Override
        public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
            return Mono.error(new IllegalStateException("저장 실패(테스트)"));
        }
    }

    private static class 조직_삭제가_실패하는_저장소 extends FakeStateRepository {
        @Override
        public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) {
            return Mono.error(new IllegalStateException("저장 실패(테스트)"));
        }
    }
}
