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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장하지 못한 새 조직 POST 가 쓴 줄과 결과를 모르는 줄을 되지운다(설계 2026-11-05 I11).
 */
class IncrementalSyncNewGroupUndoTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        lock = new FakeMutationLock();
        useCase = new IncrementalSyncUseCase(state, writer, new FakeTupleChecker(), lock,
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        for (String id : new String[]{"u-1", "u-2", "u-3"}) {
            useCase.createUser(new DirectoryUser(id, null, id + "@corp.com", id, null, true)).block();
        }
    }

    private static RelationTuple dm(String userId) {
        return RelationTuple.directMember(userId, "g-new");
    }

    private static DirectoryGroup 새_조직(String... 멤버) {
        Set<MemberRef> 멤버들 = new LinkedHashSet<>();
        for (String id : 멤버) {
            멤버들.add(MemberRef.user(id));
        }
        return new DirectoryGroup("g-new", "grp-new", "새 조직", 멤버들);
    }

    /** 지금 적용 중인 델타(appliedDeltas 의 마지막)가 지우기뿐인가 */
    private static boolean 지우기만인_델타(FakeTupleWriter writer) {
        return writer.appliedDeltas.get(writer.appliedDeltas.size() - 1).toWrite().isEmpty();
    }

    @Test
    @DisplayName("일부 실패한 새 조직은 쓴 줄과 결과를 모르는 줄을 되지운다")
    void 일부_실패한_새_조직은_쓴_줄과_결과를_모르는_줄을_되지운다() {
        // given
        writer.failFor(dm("u-2")::equals);
        writer.lostResponseFor(dm("u-3")::equals);

        // when
        var 결과 = useCase.createGroup(새_조직("u-1", "u-2", "u-3")).block();

        // then
        assertThat(결과.result().fullyApplied()).isFalse();
        assertThat(state.groups).doesNotContainKey("g-new");
        assertThat(writer.stored).doesNotContain(dm("u-1"), dm("u-2"), dm("u-3"));
    }

    @Test
    @DisplayName("되지운 뒤 같은 id 의 재시도에서 빠진 멤버의 줄이 남지 않는다")
    void 되지운_뒤_같은_id_의_재시도에서_빠진_멤버의_줄이_남지_않는다() {
        // given
        writer.failFor(dm("u-2")::equals);
        useCase.createGroup(새_조직("u-1", "u-2")).block();
        writer.failFor(t -> false);

        // when
        var 결과 = useCase.createGroup(새_조직("u-2")).block();

        // then
        assertThat(결과.result().fullyApplied()).isTrue();
        assertThat(state.groups).containsKey("g-new");
        assertThat(writer.stored).contains(dm("u-2")).doesNotContain(dm("u-1"));
    }

    @Test
    @DisplayName("되지우기의 줄이 실패하면 줄은 남고 결과는 부분 반영 그대로다")
    void 되지우기의_줄이_실패하면_줄은_남고_결과는_부분_반영_그대로다() {
        // given
        writer.failFor(t -> t.equals(dm("u-2")) || 지우기만인_델타(writer));

        // when
        var 결과 = useCase.createGroup(새_조직("u-1", "u-2")).block();

        // then
        assertThat(결과.result().fullyApplied()).isFalse();
        assertThat(state.groups).doesNotContainKey("g-new");
        assertThat(writer.stored).contains(dm("u-1"));
        var 마지막 = writer.appliedDeltas.get(writer.appliedDeltas.size() - 1);
        assertThat(마지막.toWrite()).isEmpty();
        assertThat(마지막.toDelete()).contains(dm("u-1"));
    }

    @Test
    @DisplayName("되지우기가 오류로 끝나도 요청은 부분 반영으로 끝난다")
    void 되지우기가_오류로_끝나도_요청은_부분_반영으로_끝난다() {
        // given
        writer.failFor(dm("u-2")::equals);
        writer.abortWhen(d -> d.toWrite().isEmpty() && !d.toDelete().isEmpty());

        // when
        var 결과 = useCase.createGroup(새_조직("u-1", "u-2")).block();

        // then
        assertThat(결과.result().fullyApplied()).isFalse();
        assertThat(state.groups).doesNotContainKey("g-new");
        assertThat(writer.stored).doesNotContain(dm("u-1"));
    }

    @Test
    @DisplayName("되지우기 전에 리스를 잃으면 지우지 않고 리스 상실로 끝난다")
    void 되지우기_전에_리스를_잃으면_지우지_않고_리스_상실로_끝난다() {
        // given — 본 쓰기 뒤의 커밋 직전 확인은 통과하고, 그다음 되지우기 직전 확인부터 잃는다
        writer.failFor(t -> {
            if (t.equals(dm("u-2"))) {
                lock.failRenewAfterAttempt = lock.renewAttempted.get() + 1;
                return true;
            }
            return false;
        });

        // when
        var 만든다 = useCase.createGroup(새_조직("u-1", "u-2"));

        // then
        assertThatThrownBy(만든다::block).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas.stream().filter(d -> d.toWrite().isEmpty())).isEmpty();
        assertThat(state.groups).doesNotContainKey("g-new");
        assertThat(writer.stored).contains(dm("u-1"));
    }

    @Test
    @DisplayName("확정 실패뿐이면 되지울 줄이 없어 더 쓰지 않는다")
    void 확정_실패뿐이면_되지울_줄이_없어_더_쓰지_않는다() {
        // given
        writer.failFor(t -> true);
        int 전 = writer.appliedDeltas.size();

        // when
        useCase.createGroup(새_조직("u-1")).block();

        // then
        assertThat(writer.appliedDeltas.size()).isEqualTo(전 + 1);
    }

    @Test
    @DisplayName("있던 조직의 일부 실패는 되지우지 않는다")
    void 있던_조직의_일부_실패는_되지우지_않는다() {
        // given
        useCase.createGroup(새_조직()).block();
        writer.failFor(dm("u-2")::equals);

        // when
        useCase.upsertGroup(새_조직("u-1", "u-2")).block();

        // then
        assertThat(writer.stored).contains(dm("u-1"));
        assertThat(state.groups.get("g-new").members()).contains(MemberRef.user("u-1"));
    }
}
