package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCIM 쓰기의 판단이 락 안에서 일어난다 (SCIM 쓰기 락 설계 §3·§4).
 */
class IncrementalSyncWriteDecisionTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        준비한다(state);
    }

    private void 준비한다(FakeStateRepository 저장소) {
        state = 저장소;
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock,
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        state.users.put("kim", 직원("kim", "kim", true));
        state.users.put("lee", 직원("lee", "lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
    }

    private static DirectoryUser 직원(String id, String userName, boolean active) {
        return new DirectoryUser(id, "uid=" + id, userName, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("직원 생성 — 같은 userName 이면 충돌이고 아무것도 쓰지 않는다")
    void 같은_userName이면_충돌이다() {
        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("park", "kim", true)).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 userName 을 쓰는 직원이 있습니다: userName=kim, id=kim");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.users).doesNotContainKey("park");
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("직원 생성 — 대소문자만 다른 userName 이면 충돌이다")
    void 대소문자만_다른_userName은_충돌이다() {
        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("KIMX", "KIM", true)).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 userName 을 쓰는 직원이 있습니다: userName=KIM, id=kim");
        assertThat(state.users).doesNotContainKey("KIMX");
    }

    @Test
    @DisplayName("직원 생성 — 겹치지 않으면 만든다")
    void 겹치지_않으면_만든다() {
        // when
        var result = useCase.createUser(직원("park", "park", true)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsKey("park");
    }

    @Test
    @DisplayName("직원 변경 — 남의 userName 으로 바꾸면 충돌이고 그대로다")
    void 남의_userName으로_바꾸면_충돌이다() {
        // when, then
        assertThatThrownBy(() -> useCase.changeUser("lee", u -> u.withUserName("Kim")).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 userName 을 쓰는 직원이 있습니다: userName=Kim, id=kim");
        assertThat(state.users.get("lee").userName()).isEqualTo("lee");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("직원 변경 — 자기 userName 의 대소문자만 바꾸는 것은 통과한다")
    void 자기_대소문자만_바꾸면_통과한다() {
        // when
        useCase.changeUser("kim", u -> u.withUserName("KIM")).block();

        // then
        assertThat(state.users.get("kim").userName()).isEqualTo("KIM");
    }

    @Test
    @DisplayName("직원 변경 — userName 을 바꾸지 않으면 중복을 확인하지 않는다, 이미 겹친 직원도 비활성화된다")
    void userName을_안_바꾸면_중복을_확인하지_않는다() {
        // given — lee 가 이미 kim 과 같은 userName 을 쓰고 있다(GSI 지연 틈·LDAP 동기화로 생길 수 있는 상태)
        state.users.put("lee", 직원("lee", "kim", true));

        // when
        useCase.changeUser("lee", u -> u.withActive(false)).block();

        // then — 409 로 막히면 IdP 가 재시도를 멈춰 퇴사자 권한이 남는다
        assertThat(state.users.get("lee").active()).isFalse();
    }

    @Test
    @DisplayName("GSI 에만 남은 옛 후보(지워졌거나 이름이 바뀐 직원)는 중복으로 보지 않는다")
    void GSI에만_남은_후보는_무시한다() {
        // given — GSI 가 "park" 로 찾으면 지워진 ghost 와 이름을 바꾼 lee 를 돌려준다
        준비한다(new FakeStateRepository() {
            @Override
            public Flux<String> findUserIdsByUserName(String userName) {
                return "park".equalsIgnoreCase(userName) ? Flux.just("ghost", "lee") : super.findUserIdsByUserName(userName);
            }
        });

        // when
        useCase.changeUser("kim", u -> u.withUserName("park")).block();

        // then — 본 테이블로 다시 읽으면 ghost 는 없고 lee 의 userName 은 lee 다
        assertThat(state.users.get("kim").userName()).isEqualTo("park");
    }

    /** GSI3 가 "cn=NEW"·"cn=MOVED" 로 찾으면 지워진 ghost 와 externalId 를 바꾼 DEV001(지금은 cn=DEV001)을 돌려주게 한다. */
    private void GSI3에_옛_조직이_남게_준비한다() {
        준비한다(new FakeStateRepository() {
            @Override
            public Flux<String> findGroupIdsByExternalId(String externalId) {
                return Set.of("cn=NEW", "cn=MOVED").contains(externalId)
                        ? Flux.just("ghost", "DEV001") : super.findGroupIdsByExternalId(externalId);
            }
        });
    }

    @Test
    @DisplayName("조직 생성 — GSI3 에만 남은 옛 후보(지워졌거나 externalId 를 바꾼 조직)는 중복으로 보지 않는다")
    void GSI3에만_남은_후보는_조직_생성에서_무시한다() {
        // given
        GSI3에_옛_조직이_남게_준비한다();

        // when
        var result = useCase.createGroup(new DirectoryGroup("NEW", "cn=NEW", "새 조직", Set.of())).block();

        // then — 본 테이블로 다시 읽으면 ghost 는 없고 DEV001 의 externalId 는 cn=DEV001 이다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups.get("NEW").externalId()).isEqualTo("cn=NEW");
    }

    @Test
    @DisplayName("조직 변경 — GSI3 에만 남은 옛 후보는 externalId 를 바꿀 때 중복으로 보지 않는다")
    void GSI3에만_남은_후보는_조직_변경에서_무시한다() {
        // given
        GSI3에_옛_조직이_남게_준비한다();
        state.groups.put("DEV002", new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", Set.of()));

        // when
        useCase.changeGroup("DEV002", GroupChange.replacement("cn=MOVED", "백엔드팀", Set.of())).block();

        // then
        assertThat(state.groups.get("DEV002").externalId()).isEqualTo("cn=MOVED");
    }

    @Test
    @DisplayName("조직 변경 — externalId 를 바꾸지 않으면 중복을 확인하지 않는다, 이미 겹친 조직도 이름을 바꿀 수 있다")
    void externalId를_안_바꾸면_중복을_확인하지_않는다() {
        // given — 두 조직이 이미 같은 externalId 를 쓰고 있다(GSI3 지연 틈·LDAP 동기화로 생길 수 있는 상태)
        state.groups.put("DUP-A", new DirectoryGroup("DUP-A", "cn=DUP", "가", Set.of()));
        state.groups.put("DUP-B", new DirectoryGroup("DUP-B", "cn=DUP", "나", Set.of()));

        // when
        useCase.changeGroup("DUP-A", GroupChange.replacement("cn=DUP", "가(개명)", Set.of())).block();
        useCase.changeGroup("DUP-B", GroupChange.delta().renamed("나(개명)")).block();

        // then — 409 로 막히면 IdP 가 재시도를 멈춰 이름 변경이 영영 반영되지 않는다
        assertThat(state.groups.get("DUP-A").displayName()).isEqualTo("가(개명)");
        assertThat(state.groups.get("DUP-B").displayName()).isEqualTo("나(개명)");
    }

    @Test
    @DisplayName("변경 계산은 락을 잡은 뒤의 직원에 적용된다 — 그 사이 저장된 비활성화를 되돌리지 않는다(설계 §1.1)")
    void 사이에_저장된_비활성화를_되돌리지_않는다() {
        // given — 이름 바꾸기 계산을 만들어 둔다. 계산은 저장소를 읽지 않는다
        UnaryOperator<DirectoryUser> 이름바꾸기 = u -> u.withDisplayName("김철수(개명)");
        // 그 사이 다른 요청이 비활성화를 저장했다
        useCase.changeUser("kim", u -> u.withActive(false)).block();
        writer.written.clear();

        // when
        useCase.changeUser("kim", 이름바꾸기).block();

        // then — 옛 방식(락 밖에서 읽은 활성 직원으로 계산)이면 active=true 로 되살아난다
        assertThat(state.users.get("kim").active()).isFalse();
        assertThat(state.users.get("kim").displayName()).isEqualTo("김철수(개명)");
        assertThat(writer.written).doesNotContain(RelationTuple.directMember("kim", "DEV001"));
    }

    @Test
    @DisplayName("지운 직원을 변경이 되살리지 않는다 — 없으면 빈 결과다(설계 §1.2)")
    void 지운_직원을_되살리지_않는다() {
        // given
        useCase.removeUser("kim").block();
        writer.written.clear();

        // when
        var result = useCase.changeUser("kim", u -> u.withDisplayName("x")).blockOptional(Duration.ofSeconds(10));

        // then
        assertThat(result).isEmpty();
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(writer.written).isEmpty();
    }

    @Test
    @DisplayName("계산이 예외를 던지면 아무것도 쓰지 않고 락을 반납한다")
    void 계산이_실패하면_쓰지_않는다() {
        // when, then
        assertThatThrownBy(() -> useCase.changeUser("kim", u -> {
            throw new IllegalArgumentException("잘못된 PATCH");
        }).block()).isInstanceOf(IllegalArgumentException.class);
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.users.get("kim").displayName()).isEqualTo("kim 님");
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("변경 계산이 다른 아이디의 직원을 돌려줘도 경로의 아이디로 저장한다")
    void 경로의_아이디로_저장한다() {
        // when
        useCase.changeUser("kim", u -> 직원("other", "kim", false)).block();

        // then
        assertThat(state.users).doesNotContainKey("other");
        assertThat(state.users.get("kim").active()).isFalse();
    }

    @Test
    @DisplayName("조직 생성 — 다른 조직과 externalId 가 같으면 충돌이고, 겹치지 않으면 만든다")
    void 조직_생성() {
        // when, then
        assertThatThrownBy(() -> useCase.createGroup(new DirectoryGroup("DEV009", "cn=DEV001", "개발본부", Set.of())).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=cn=DEV001, id=DEV001");
        assertThat(state.groups).doesNotContainKey("DEV009");

        var result = useCase.createGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("없는 직원·조직 삭제는 빈 결과이고 아무것도 쓰지 않는다")
    void 없는_대상_삭제는_빈_결과다() {
        // when
        var 직원 = useCase.removeUser("ghost").blockOptional(Duration.ofSeconds(10));
        var 조직 = useCase.removeGroup("NONE").blockOptional(Duration.ofSeconds(10));

        // then
        assertThat(직원).isEmpty();
        assertThat(조직).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
    }
}
