package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * <b>판단에 쓰는 읽기는 모두 락을 쥔 동안에만 일어난다</b> (SCIM 쓰기 락 설계 §7 (a)).
 *
 * <p>가짜 저장소가 읽기가 실제로 일어나는 순간(구독 시점)마다 락을 쥐고 있었는지 기록한다. 타이밍과 무관하게 결정적이고,
 * 누가 읽기를 {@code withLock} 밖으로 되돌리면 — 예를 들어 입구가 {@code state.findUser(...).flatMap(u -> withLock(...))} 이
 * 되면 — 바로 깨진다. 동시 요청이 서로를 지우지 않는다는 성질의 뿌리가 이것이다.
 */
class WriteDecisionLockInvariantTest {

    private final List<String> 락밖읽기 = new ArrayList<>();
    private final List<String> 모든읽기 = new ArrayList<>();
    private FakeMutationLock lock;
    private 감시하는_저장소 state;
    private IncrementalSyncUseCase useCase;

    /** 읽기마다 그 순간 락을 쥐고 있었는지 기록한다. */
    private final class 감시하는_저장소 extends FakeStateRepository {

        private <T> Mono<T> 본다(String 이름, Supplier<Mono<T>> 읽기) {
            return Mono.defer(() -> {
                기록한다(이름);
                return 읽기.get();
            });
        }

        private <T> Flux<T> 본다Flux(String 이름, Supplier<Flux<T>> 읽기) {
            return Flux.defer(() -> {
                기록한다(이름);
                return 읽기.get();
            });
        }

        private void 기록한다(String 이름) {
            모든읽기.add(이름);
            if (!lock.isHeld()) {
                락밖읽기.add(이름);
            }
        }

        @Override public Mono<DirectoryUser> findUser(String id) { return 본다("findUser " + id, () -> super.findUser(id)); }
        @Override public Flux<DirectoryUser> findUsers(Set<String> ids) { return 본다Flux("findUsers " + ids, () -> super.findUsers(ids)); }
        @Override public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) { return 본다("findMemberTypes " + ids, () -> super.findMemberTypes(ids)); }
        @Override public Flux<String> findUserIdsByUserName(String n) { return 본다Flux("findUserIdsByUserName " + n, () -> super.findUserIdsByUserName(n)); }
        @Override public Mono<DirectoryGroup> findGroup(String id) { return 본다("findGroup " + id, () -> super.findGroup(id)); }
        @Override public Mono<GroupHeader> findGroupHeader(String id) { return 본다("findGroupHeader " + id, () -> super.findGroupHeader(id)); }
        @Override public Mono<Set<MemberRef>> findMembers(String g, Set<MemberRef> c) { return 본다("findMembers " + g, () -> super.findMembers(g, c)); }
        @Override public Flux<MemberRef> findMemberRefs(String g) { return 본다Flux("findMemberRefs " + g, () -> super.findMemberRefs(g)); }
        @Override public Flux<String> findChildGroupIds(String g) { return 본다Flux("findChildGroupIds " + g, () -> super.findChildGroupIds(g)); }
        @Override public Flux<String> findGroupIdsContaining(MemberRef ref) { return 본다Flux("findGroupIdsContaining " + ref.id(), () -> super.findGroupIdsContaining(ref)); }
        @Override public Flux<GroupEdge> findCutEdges() { return 본다Flux("findCutEdges", super::findCutEdges); }
    }

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        state = new 감시하는_저장소();
        useCase = new IncrementalSyncUseCase(state, new FakeTupleWriter(), new FakeTupleChecker(), lock,
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        state.users.put("kim", 직원("kim"));
        state.users.put("lee", 직원("lee"));
        state.groups.put("TEAM", new DirectoryGroup("TEAM", "cn=TEAM", "팀", Set.of()));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.group("TEAM"))));
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", true);
    }

    /** 실패(충돌·예외)로 끝나도 읽기는 기록된다 — 그 판단 읽기도 락 안이어야 한다. */
    private void 돌린다(Mono<?> 요청) {
        catchThrowable(() -> 요청.blockOptional(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("직원 생성·변경·삭제의 판단 읽기는 모두 락 안에서 일어난다 — 성공·충돌·없음 모두")
    void 직원_입구() {
        // when
        돌린다(useCase.createUser(직원("park")));
        돌린다(useCase.createUser(직원("kim")));                                    // 아이디 충돌
        돌린다(useCase.changeUser("lee", u -> u.withUserName("KIM")));              // userName 충돌
        돌린다(useCase.changeUser("kim", u -> u.withDisplayName("새 이름")));
        돌린다(useCase.changeUser("ghost", u -> u));                               // 없음
        돌린다(useCase.removeUser("kim"));
        돌린다(useCase.removeUser("ghost"));                                       // 없음

        // then
        assertThat(모든읽기).as("읽기가 하나도 없으면 이 테스트는 아무것도 증명하지 못한다").isNotEmpty();
        assertThat(락밖읽기).isEmpty();
    }

    @Test
    @DisplayName("조직 생성·변경·삭제의 판단 읽기는 모두 락 안에서 일어난다 — 성공·충돌·없음 모두")
    void 조직_입구() {
        // when
        돌린다(useCase.createGroup(new DirectoryGroup("NEW", "cn=NEW", "새 조직", Set.of(MemberRef.group("TEAM")))));
        돌린다(useCase.createGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of())));   // 충돌
        돌린다(useCase.changeGroup("DEV001", GroupChange.delta().adding(Set.of(MemberRef.user("lee")))));
        돌린다(useCase.changeGroup("DEV001", GroupChange.delta().replacing(Set.of(MemberRef.user("kim")))));
        돌린다(useCase.changeGroup("NONE", GroupChange.delta().removingId("kim")));                        // 없음
        돌린다(useCase.removeGroup("DEV001"));
        돌린다(useCase.removeGroup("NONE"));                                                               // 없음

        // then
        assertThat(모든읽기).isNotEmpty();
        assertThat(락밖읽기).isEmpty();
    }
}
