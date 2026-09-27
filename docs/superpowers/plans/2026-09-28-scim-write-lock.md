# SCIM 쓰기 판단을 락 안으로 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 쓰기 요청의 판단(직원 읽기, 존재 확인, 아이디·userName 중복 확인)을 모두 전역 쓰기 락 안으로 옮겨 동시 요청의 덮어쓰기·부활·중복을 없애고, 직원 삭제 비용을 조직 크기와 무관하게 만든다.

**Architecture:** `IncrementalSyncUseCase` 에 입구 셋(`createUser`, `changeUser(id, 계산)`, `createGroup`)을 더하고, `removeUser`·`removeGroup` 은 대상이 없으면 빈 결과를 돌려준다. 핸들러는 본문 해석만 하고 저장소를 읽지 않는다. 중복은 core 예외 `DirectoryConflictException` → 라우터가 409 `uniqueness`. 직원 삭제는 소속 조직 헤더만 읽고 `saveGroupChange` 로 그 직원의 줄만 지운다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux(함수형 라우터), Reactor, Lombok, AWS SDK v2 DynamoDB(async), OpenFGA, JUnit 5 + AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-28-scim-write-lock-design.md`

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 모양을 따른다. Lombok 을 쓴다. Java 17 — `switch` 패턴 매칭은 못 쓴다.
- **판단은 락 안(스펙 §3):** SCIM 쓰기 입구(`createUser`·`changeUser`·`removeUser`·`createGroup`·`changeGroup`·`removeGroup`)의 판단 읽기는 모두 `withLock` 이 락을 잡은 뒤에 한다. 핸들러는 판단을 위해 저장소를 읽지 않는다(멤버 `type` 판정의 `MemberTypeResolver` 와, 쓰기 뒤 응답을 만드는 읽기만 예외).
- **없음은 빈 결과:** `changeUser`·`removeUser`·`removeGroup`(·기존 `changeGroup`)은 대상이 없으면 빈 `Mono` — 핸들러가 404 로 바꾼다. 404 메시지는 지금 것 그대로: `직원을 찾을 수 없습니다: <id>`, `조직을 찾을 수 없습니다: <id>`.
- **중복은 `DirectoryConflictException`(core, `dev.starryeye.organization.core.usecase`) → SCIM 라우터가 409, `scimType` `uniqueness`.** 메시지는 정확히 `이미 존재하는 직원입니다: <id>`, `이미 같은 userName 을 쓰는 직원이 있습니다: userName=<userName>, id=<id>`, `이미 존재하는 조직입니다: <id>`.
- **userName 중복 규칙(스펙 §4, A):** POST 는 항상, PATCH·PUT 은 `userName` 이 글자 그대로 바뀌었을 때만. `findUserIdsByUserName`(GSI, 대소문자 무시) → 자기 자신 제외 → 후보마다 `findUser`(강한 일관성)로 다시 읽어 `userName` 이 여전히 같을(대소문자 무시) 때만 충돌.
- **직원 삭제(스펙 §5):** 소속 조직은 헤더만(`affectedGroupHeadersOf`). 튜플이 원래 있었는데 삭제되지 않은 조직은 멤버십을 그대로 두고, 나머지는 `saveGroupChange(헤더, ∅, {직원})`. 하나라도 실패하면 직원 레코드를 지우지 않는다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로(`--tests` 로 좁혀도 된다). 루트 `test`·`build`·`check`·`scaleTest` 는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다. DynamoDB Local 이 HTTP 999 를 내면 환경 부하다 — 한 번 다시 돌린다.
- 커밋마다 `git push`. 커밋 트레일러는 자기 하네스가 지시하는 줄, 없으면 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 이 작업 트리의 명령 가드는 복잡한 bash(for 반복문, `cd … &&`, 변수 대입)를 거절한다. 단순한 명령을 따로따로 돌린다.

## Review Focus

1. **자기 `userName` 의 대소문자만 바꾸는 PATCH** — 통과해야 한다(GSI 가 자기 자신을 찾는다). → Task 1 `자기_대소문자만_바꾸면_통과한다`.
2. **GSI 가 오래된 후보를 돌려줄 때**(방금 지웠거나 이름을 바꾼 직원) — 잘못된 409 를 내면 안 된다. → Task 1 `GSI에만_남은_후보는_무시한다`.
3. **PUT 계산이 다른 아이디의 직원을 돌려줄 때** — 경로의 아이디로 저장한다. → Task 1 `경로의_아이디로_저장한다`.
4. **소속이 없는 직원 삭제** — 204 이고 직원이 사라진다. → Task 2 `소속_없는_직원도_지운다`.
5. **IdP 재시도로 같은 직원 POST 가 두 번** — 두 번째는 409 이고 아무것도 쓰지 않는다. → Task 1 `있는_아이디면_충돌이다`.

---

### Task 1: core — 직원·조직 생성과 직원 변경을 락 안에서, userName 중복, 없는 대상은 빈 결과

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/DirectoryConflictException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`upsertUser` 아래·`upsertGroup` 아래에 새 입구, `removeUserInternal`·`removeGroupInternal` 의 `.defaultIfEmpty(IncrementalSyncResult.noChange())` 제거)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java` (`없는_직원_삭제는_조용히_끝난다`)
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncWriteDecisionTest.java`

**Interfaces:**
- Produces:
  - `public class DirectoryConflictException extends RuntimeException` (생성자 `(String message)`)
  - `public Mono<IncrementalSyncResult> createUser(DirectoryUser user)` — 중복이면 `DirectoryConflictException`
  - `public Mono<IncrementalSyncResult> changeUser(String userId, UnaryOperator<DirectoryUser> 계산)` — 없으면 빈 Mono
  - `public Mono<IncrementalSyncResult> createGroup(DirectoryGroup group)` — 있으면 `DirectoryConflictException`
  - `removeUser`·`removeGroup` 은 대상이 없으면 빈 Mono

- [ ] **Step 1: 실패하는 테스트를 쓴다** — `IncrementalSyncWriteDecisionTest`

```java
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
    @DisplayName("직원 생성 — 이미 있는 아이디면 충돌이고 아무것도 쓰지 않는다")
    void 있는_아이디면_충돌이다() {
        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("kim", "kim2", true)).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 존재하는 직원입니다: kim");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.users.get("kim").userName()).isEqualTo("kim");
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
    @DisplayName("조직 생성 — 이미 있으면 충돌이고, 없으면 만든다")
    void 조직_생성() {
        // when, then
        assertThatThrownBy(() -> useCase.createGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of())).block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 존재하는 조직입니다: DEV001");

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
```

그리고 `IncrementalSyncUseCaseTest.없는_직원_삭제는_조용히_끝난다` 를 바꾼다:

```java
    @Test
    @DisplayName("존재하지 않는 직원을 삭제하면 빈 결과다 — 핸들러가 404 로 바꾼다")
    void 없는_직원_삭제는_조용히_끝난다() {
        // given, when
        var result = useCase.removeUser("ghost").blockOptional(Duration.ofSeconds(10));

        // then
        assertThat(result).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncWriteDecisionTest*' --tests '*IncrementalSyncUseCaseTest*'`
Expected: FAIL — `DirectoryConflictException`·`createUser` 등이 없어 컴파일 오류.

- [ ] **Step 3: 구현한다**

`DirectoryConflictException`:

```java
package dev.starryeye.organization.core.usecase;

/**
 * 이미 있는 아이디로 만들거나, 다른 직원의 userName 과 겹치게 만들거나 바꾸려 했다. 호출자는 409 {@code uniqueness} 로 옮긴다
 * (RFC 7644 §3.12 — PUT·PATCH 도 같다). 판단은 전역 쓰기 락 안에서 한다(SCIM 쓰기 락 설계 §3·§4).
 */
public class DirectoryConflictException extends RuntimeException {

    public DirectoryConflictException(String message) {
        super(message);
    }
}
```

`IncrementalSyncUseCase` — import 에 `java.util.Objects`, `java.util.function.UnaryOperator` 를 더한다. `upsertUserInternal` 메서드 바로 아래에:

```java
    /**
     * 직원 생성(POST). 아이디·userName 중복을 <b>락 안에서</b> 확인한다(SCIM 쓰기 락 설계 §3·§4). 겹치면
     * {@link DirectoryConflictException}.
     *
     * <p>아이디 중복뿐 아니라 {@code userName} 중복도 막는다. 아이디는 생성 시점의 {@code userName} 에서 발급되고 그 뒤의
     * {@code userName} 변경을 따라가지 않는다(SCIM 의 정체성은 id 다). 그래서 이름이 바뀐 사람을 IdP 가 <b>새 userName 으로</b>
     * 다시 POST 하면 그 아이디로는 아무도 찾지 못해 같은 사람의 레코드가 둘 생긴다 — 튜플도 두 벌이 되고, 한쪽을 비활성화해도
     * 다른 쪽 권한이 남는다.
     */
    public Mono<IncrementalSyncResult> createUser(DirectoryUser user) {
        return withLock(lease -> state.findUser(user.id())
                .flatMap(existing -> Mono.<IncrementalSyncResult>error(
                        new DirectoryConflictException("이미 존재하는 직원입니다: " + user.id())))
                .switchIfEmpty(Mono.defer(() -> userName을_확인한다(user.userName(), user.id())
                        .then(Mono.defer(() -> upsertUserInternal(user, lease))))));
    }

    /**
     * 직원 PATCH·PUT. 락을 잡은 뒤 직원을 읽어 {@code 계산} 을 적용한다(SCIM 쓰기 락 설계 §3). 직원이 없으면 빈 {@code Mono} 다.
     *
     * <p>전에는 핸들러가 락 밖에서 읽은 직원으로 계산해, 동시에 온 비활성화와 이름 변경 중 늦게 저장된 쪽이 비활성화를
     * 되돌렸고(퇴사자 권한 부활), 그 사이 DELETE 가 끝났으면 지운 직원을 다시 만들었다(설계 §1.1·§1.2). 계산이 던지는
     * 예외는 그대로 나오고 아무것도 쓰지 않는다. 계산 결과의 아이디는 무시하고 {@code userId} 로 저장한다 — 경로가 정본이다.
     * {@code userName} 이 바뀌었으면 중복을 확인한다(§4).
     */
    public Mono<IncrementalSyncResult> changeUser(String userId, UnaryOperator<DirectoryUser> 계산) {
        return withLock(lease -> state.findUser(userId)
                .flatMap(before -> {
                    DirectoryUser after = 계산.apply(before).withId(userId);
                    Mono<Void> 확인 = Objects.equals(before.userName(), after.userName())
                            ? Mono.empty()
                            : userName을_확인한다(after.userName(), userId);
                    return 확인.then(Mono.defer(() -> upsertUserInternal(after, lease)));
                }));
    }

    /**
     * {@code userName} 이 다른 직원과 겹치는지 확인한다(SCIM 쓰기 락 설계 §4). GSI 로 후보를 찾고(대소문자 무시), 자기 자신을
     * 뺀 뒤, 후보마다 본 테이블을 강한 일관성으로 다시 읽어 여전히 같은 {@code userName} 일 때만 충돌이다 — GSI 에 잠깐 남은
     * 옛 값(방금 지웠거나 이름을 바꾼 직원) 때문에 잘못 거절하지 않는다. 남는 틈은 방금 저장돼 아직 GSI 에 없는 직원뿐이다(설계 §10).
     */
    private Mono<Void> userName을_확인한다(String userName, String selfId) {
        if (userName == null) {
            return Mono.empty();
        }
        return state.findUserIdsByUserName(userName)
                .filter(id -> !id.equals(selfId))
                .concatMap(state::findUser)
                .filter(other -> userName.equalsIgnoreCase(other.userName()))
                .next()
                .flatMap(other -> Mono.error(new DirectoryConflictException(
                        "이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s".formatted(userName, other.id()))));
    }
```

`upsertGroupInternal` 메서드 바로 아래에:

```java
    /** 조직 생성(POST). 이미 있는지를 <b>락 안에서</b> 확인한다(SCIM 쓰기 락 설계 §3). 있으면 {@link DirectoryConflictException}. */
    public Mono<IncrementalSyncResult> createGroup(DirectoryGroup group) {
        return withLock(lease -> state.findGroupHeader(group.id())
                .flatMap(existing -> Mono.<IncrementalSyncResult>error(
                        new DirectoryConflictException("이미 존재하는 조직입니다: " + group.id())))
                .switchIfEmpty(Mono.defer(() -> upsertGroupInternal(group, lease))));
    }
```

`removeUserInternal` 과 `removeGroupInternal` 끝의 `.defaultIfEmpty(IncrementalSyncResult.noChange());` 를 지워 `;` 로 끝낸다. 두 공개 메서드(`removeUser`·`removeGroup`) 자바독에 "대상이 없으면 빈 {@code Mono} 다 — 존재 확인도 락 안이다(SCIM 쓰기 락 설계 §3)." 한 줄을 더한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS (새 12개 포함 전부)

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/DirectoryConflictException.java core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncWriteDecisionTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java
git commit -m "feat: 직원·조직 생성과 직원 변경의 판단을 락 안으로 — userName 중복, 없는 대상은 빈 결과"
git push
```

---

### Task 2: core — 직원 삭제가 소속 조직을 통째로 읽지 않는다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`removeUserInternal`, `affectedGroupsOf` 삭제, 클래스 자바독의 "유저 변경" 항목, `removeUser` 자바독)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`

**Interfaces:**
- Consumes: Task 1 의 `removeUser` 빈 결과, 기존 `saveGroupChange(GroupHeader, Set<MemberRef>, Set<MemberRef>)`, `affectedGroupHeadersOf`, `tupleFor`
- Produces: 없음(동작만)

- [ ] **Step 1: 실패하는 테스트를 쓴다** — `IncrementalSyncReadScopeTest` 끝에(이 클래스의 준비: 대형조직 `PLANT` 에 직원 300명 `u0`~`u299`, 모두 활성, 튜플이 심겨 있다).

```java
    @Test
    @DisplayName("직원을 삭제할 때 소속 조직을 통째로 읽지 않는다 — 헤더만 읽고 그 직원의 줄만 지운다")
    void 삭제에_조직을_통째로_읽지_않는다() {
        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then
        assertThat(state.findGroupCalls).as("조직 파티션을 통째로 읽지 않는다").isEmpty();
        assertThat(state.findGroupHeaderCalls).contains(대형조직);
        assertThat(state.groups.get(대형조직).members())
                .hasSize(대형조직_멤버수 - 1)
                .doesNotContain(MemberRef.user("u0"));
        assertThat(writer.deleted).containsExactly(RelationTuple.directMember("u0", 대형조직));
        assertThat(state.users).doesNotContainKey("u0");
    }

    @Test
    @DisplayName("소속이 없는 직원도 지운다")
    void 소속_없는_직원도_지운다() {
        // given
        state.users.put("loner", 직원("loner", true));

        // when
        var result = useCase.removeUser("loner").block(Duration.ofSeconds(10));

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).doesNotContainKey("loner");
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest*'`
Expected: FAIL — `삭제에_조직을_통째로_읽지_않는다` 에서 `findGroupCalls` 에 `PLANT` 가 있다(`affectedGroupsOf` 가 `findGroup` 을 부른다).

- [ ] **Step 3: 구현한다** — `removeUserInternal` 을 바꾼다.

```java
    private Mono<IncrementalSyncResult> removeUserInternal(String userId, LockLease lease) {
        MemberRef 이직원 = MemberRef.user(userId);
        return state.findUser(userId)
                .flatMap(user -> affectedGroupHeadersOf(userId).flatMap(headers -> {
                    // 스냅샷은 좁힌다 — 델타에는 이 직원의 튜플만 남으므로 동료가 필요 없다.
                    Mono<DirectorySnapshot> before = 직원한명_그림(headers, userId, Mono.just(user));
                    // 삭제 후에는 어느 조직에도 속하지 않으므로 조직이 하나도 없는 그림이 맞다.
                    Mono<DirectorySnapshot> after = 직원한명_그림(Set.of(), userId, Mono.empty());

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        // 튜플이 원래 있었는데 지워지지 않은 조직은 멤버십을 남긴다(reconcileRemovedMember 와 같은 판단).
                        // 나머지는 그 직원의 멤버 줄·소속 줄만 지운다 — 조직 멤버 목록 전체를 읽고 쓰지 않는다(설계 §5).
                        Mono<Void> saveGroups = Flux.fromIterable(headers)
                                .filter(header -> 멤버십을_지운다(tupleFor(이직원, header.id()), beforeTuples, result))
                                .flatMap(header -> state.saveGroupChange(header, Set.of(), Set.of(이직원)), LOAD_CONCURRENCY)
                                .then();
                        if (result.hasFailure()) {
                            return saveGroups;
                        }
                        return saveGroups.then(Mono.defer(() -> state.deleteUser(userId)));
                    };

                    return diffAndApply(before, after, RelationTuple.userRef(userId), lease, commit);
                }));
    }

    /** 튜플이 원래 없었거나 이번에 지워졌으면 멤버십도 지운다. 원래 있었는데 지우지 못했으면 남겨 재시도가 다시 보게 한다. */
    private static boolean 멤버십을_지운다(RelationTuple tuple, Set<RelationTuple> beforeTuples, TupleWriteResult result) {
        return !beforeTuples.contains(tuple) || result.deleted().contains(tuple);
    }
```

`affectedGroupsOf` 메서드는 더 쓰지 않으므로 지운다(`grep -n "affectedGroupsOf"` 로 남은 참조가 자바독뿐인지 확인). 클래스 자바독 "유저 변경" 항목의 "{@link #removeUser} 는 반대로 {@link #affectedGroupsOf} 로 조직을 <b>멤버 목록째로</b> 그대로 읽는다 — 커밋이 {@code saveGroup} 이라 최종 멤버 목록 전체를 요구해서다(설계 §4.4)." 를 "{@link #removeUser} 도 헤더만 읽고, 커밋은 {@code saveGroupChange} 로 그 직원의 줄만 지운다(SCIM 쓰기 락 설계 §5)." 로 바꾼다. `affectedGroupHeadersOf` 자바독의 "{@link #upsertUser} 에서만 쓴다" 는 "{@link #upsertUser}·{@link #removeUser} 가 쓴다" 로. `removeUser` 자바독의 "삭제 튜플이 실패한 조직은 멤버 목록을 원래대로 유지한다({@link #reconcileRemovedMember})" 는 "삭제 튜플이 실패한 조직은 멤버십을 그대로 둔다" 로.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS — 특히 `IncrementalSyncUseCaseTest.직원_삭제_부분_실패시_재시도가_남은_튜플을_다시_지운다`(실패한 조직은 멤버십 유지, 직원 레코드 유지, 재시도가 남은 튜플만 다시 지움)가 그대로 통과해야 한다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java
git commit -m "feat: 직원 삭제가 소속 조직 헤더만 읽고 그 직원의 줄만 지운다"
git push
```

---

### Task 3: core — 락 불변식 테스트와 락 실패 테스트

**Files:**
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java` (`isHeld()`)
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java` (`락을_못_잡으면_쓰지_않는다`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`withLock` 자바독의 경로 수)

**Interfaces:**
- Consumes: Task 1·2 의 입구 전부, 기존 `changeGroup`·`GroupChange`
- Produces: `public boolean FakeMutationLock.isHeld()`

- [ ] **Step 1: 가짜 락에 "지금 쥐고 있나" 를 더한다**

```java
    /** 지금 누군가 쥐고 있는가. 판단 읽기가 락 안에서 일어나는지 보는 테스트가 쓴다. */
    public boolean isHeld() {
        return heldToken.get() != null;
    }
```

- [ ] **Step 2: 불변식 테스트를 쓴다** — `WriteDecisionLockInvariantTest`

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
        @Override public Flux<String> findUserIdsByUserName(String n) { return 본다Flux("findUserIdsByUserName " + n, () -> super.findUserIdsByUserName(n)); }
        @Override public Mono<DirectoryGroup> findGroup(String id) { return 본다("findGroup " + id, () -> super.findGroup(id)); }
        @Override public Mono<GroupHeader> findGroupHeader(String id) { return 본다("findGroupHeader " + id, () -> super.findGroupHeader(id)); }
        @Override public Mono<Set<MemberRef>> findMembers(String g, Set<MemberRef> c) { return 본다("findMembers " + g, () -> super.findMembers(g, c)); }
        @Override public Flux<MemberRef> findMemberRefs(String g) { return 본다Flux("findMemberRefs " + g, () -> super.findMemberRefs(g)); }
        @Override public Flux<String> findChildGroupIds(String g) { return 본다Flux("findChildGroupIds " + g, () -> super.findChildGroupIds(g)); }
        @Override public Flux<String> findGroupIdsContaining(MemberRef ref) { return 본다Flux("findGroupIdsContaining " + ref.id(), () -> super.findGroupIdsContaining(ref)); }
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
```

- [ ] **Step 3: 락 실패 테스트를 넓힌다** — `IncrementalSyncDriftTest.락을_못_잡으면_쓰지_않는다` 의 `@DisplayName` 을 `"락을 못 잡으면 여덟 변경 경로 모두 아무것도 쓰지 않고 실패한다"` 로 바꾸고, 기존 단언들 뒤(`writer.written` 단언 앞)에 더한다:

```java
        assertThatThrownBy(() -> useCase.createUser(직원("park", true)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThatThrownBy(() -> useCase.changeUser("kim", u -> u.withActive(false)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThatThrownBy(() -> useCase.createGroup(new DirectoryGroup("NEW", "cn=NEW", "새 조직", Set.of())).block())
                .isInstanceOf(LockUnavailableException.class);
```

`IncrementalSyncUseCase.withLock` 자바독의 "다섯 경로({@link #upsertUser}·…)" 목록을 여덟으로 고친다: `upsertUser`·`createUser`·`changeUser`·`removeUser`·`upsertGroup`·`createGroup`·`changeGroup`·`removeGroup`.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS. 확인 삼아 `createUser` 를 잠깐 `state.findUser(user.id()).flatMap(e -> Mono.<IncrementalSyncResult>error(...)).switchIfEmpty(Mono.defer(() -> withLock(...)))` 모양(읽기를 락 밖으로)으로 바꾸면 `직원_입구` 가 깨지는 것을 보고 되돌린다 — 보고서에 그 실패 출력을 남긴다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java
git commit -m "test: 판단 읽기는 모두 락을 쥔 동안에만 일어난다 — 락 불변식, 락 실패 경로 여덟"
git push
```

---

### Task 4: SCIM — 핸들러가 새 입구를 쓰고 판단 읽기를 하지 않는다, 409 매핑

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java` (`create`·`replace`·`patch`·`delete`, `rejectDuplicate` 삭제)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java` (`create`·`delete`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java` (`toScimError`)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`, `ScimGroupHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 의 `createUser`·`changeUser`·`createGroup`, 빈 결과를 돌려주는 `removeUser`·`removeGroup`, `DirectoryConflictException`

- [ ] **Step 1: 실패하는 테스트를 쓴다** — `ScimUserHandlerTest` 끝에(`client`, `state`, `writer` 필드를 쓴다; import 에 `dev.starryeye.organization.core.model.DirectoryUser` 가 이미 있다).

```java
    @Test
    @DisplayName("PATCH 로 다른 직원의 userName(대소문자만 다름)으로 바꾸면 409 uniqueness 이고 그대로다")
    void PATCH_userName_충돌은_409다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveUser(new DirectoryUser("lee", "e2", "lee", "이영희", null, true)).block();

        // when, then
        client.patch().uri("/scim/v2/Users/lee").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"userName","value":"KIM"}]}""")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("uniqueness")
                .jsonPath("$.detail").isEqualTo("이미 같은 userName 을 쓰는 직원이 있습니다: userName=KIM, id=kim");
        assertThat(state.users.get("lee").userName()).isEqualTo("lee");
    }

    @Test
    @DisplayName("PUT 으로 다른 직원의 userName 으로 바꾸면 409 uniqueness 다")
    void PUT_userName_충돌은_409다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveUser(new DirectoryUser("lee", "e2", "lee", "이영희", null, true)).block();

        // when, then
        client.put().uri("/scim/v2/Users/lee").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"이영희"}""")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.scimType").isEqualTo("uniqueness");
    }

    @Test
    @DisplayName("없는 직원에 PATCH·PUT·DELETE 하면 404 이고 아무것도 만들지 않는다")
    void 없는_직원은_404다() {
        // when, then
        client.patch().uri("/scim/v2/Users/ghost").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"displayName","value":"x"}]}""")
                .exchange().expectStatus().isNotFound();
        client.put().uri("/scim/v2/Users/ghost").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"ghost"}""")
                .exchange().expectStatus().isNotFound();
        client.delete().uri("/scim/v2/Users/ghost").exchange().expectStatus().isNotFound();
        assertThat(state.users).doesNotContainKey("ghost");
        assertThat(writer.appliedDeltas).isEmpty();
    }
```

`ScimGroupHandlerTest` 끝(`지원기능을_선언한다` 위)에:

```java
    @Test
    @DisplayName("없는 조직을 DELETE 하면 404 다")
    void 없는_조직_DELETE는_404다() {
        client.delete().uri("/scim/v2/Groups/NONE").exchange().expectStatus().isNotFound();
        assertThat(writer.appliedDeltas).isEmpty();
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimUserHandlerTest*' --tests '*ScimGroupHandlerTest*'`
Expected: FAIL — `PATCH_userName_충돌은_409다`·`PUT_userName_충돌은_409다` 가 200(중복을 막지 않는다).

- [ ] **Step 3: 라우터가 충돌을 409 로 옮긴다** — `ScimRouter.toScimError` 의 `LockUnavailableException` 분기 바로 아래(import 에 `dev.starryeye.organization.core.usecase.DirectoryConflictException`):

```java
        // 아이디·userName 이 이미 있다 — RFC 7644 §3.12 의 409 uniqueness. 판단은 락 안에서 했다(SCIM 쓰기 락 설계 §3·§4).
        if (error instanceof DirectoryConflictException conflict) {
            return write(HttpStatus.CONFLICT, "uniqueness", conflict.getMessage());
        }
```

- [ ] **Step 4: 직원 핸들러를 바꾼다** — `ScimUserHandler`. `rejectDuplicate` 는 지운다(그 자바독은 Task 1 이 `createUser` 로 옮겼다).

```java
    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .map(ScimMapper::toDirectoryUser)
                // 아이디·userName 중복은 락 안에서 확인한다(SCIM 쓰기 락 설계 §3·§4)
                .flatMap(user -> sync.createUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result, projection))));
    }

    /** PUT — 본문으로 통째로 교체한다. 직원 읽기·존재 확인·userName 중복 확인은 락 안에서 한다(SCIM 쓰기 락 설계 §3). */
    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .map(ScimMapper::toDirectoryUser)
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 userName 이 달라도 리소스를 옮기지 않는다.
                .flatMap(user -> sync.changeUser(id, before -> user.withId(id))
                        .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    /**
     * PATCH — 연산 적용은 락 안에서, 락을 잡은 뒤 읽은 직원에 한다(SCIM 쓰기 락 설계 §3). 락 밖에서 읽은 직원으로 계산하면
     * 동시에 온 비활성화를 되돌리거나 방금 지운 직원을 되살린다.
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimPatchOp.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(patch -> sync.changeUser(id, before -> ScimPatchApplier.applyToUser(before, patch))
                        .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        // 존재 확인은 락 안에서 한다 — 없으면 빈 결과다(SCIM 쓰기 락 설계 §3)
        return sync.removeUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal(
                                "일부 튜플 삭제에 실패했습니다. 재시도해 주세요: " + id)));
    }
```

- [ ] **Step 5: 조직 핸들러를 바꾼다** — `ScimGroupHandler.create` 의 `.flatMap(group -> state.findGroupHeader(group.id()) … .switchIfEmpty(Mono.just(group)))` 단계와 그 뒤 `sync.upsertGroup(group)` 을 하나로:

```java
                // 이미 있는지는 락 안에서 확인한다(SCIM 쓰기 락 설계 §3)
                .flatMap(group -> sync.createGroup(group)
                        .flatMap(result -> respond(HttpStatus.CREATED, group.id(), result, projection))));
```

`delete`:

```java
    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        // 존재 확인은 락 안에서 한다 — 락 밖에서 조직 파티션을 통째로 읽지 않는다(SCIM 쓰기 락 설계 §3)
        return sync.removeGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal(
                                "일부 튜플 삭제에 실패했습니다. 재시도해 주세요: " + id)));
    }
```

쓰지 않게 된 import(`DirectoryGroup` 등)가 있으면 정리한다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: PASS — 기존 POST 409 테스트(`userName 이 바뀐 뒤 같은 사람을 새 userName 으로 다시 생성하면 409 로 막는다`, 조직 `중복_생성은_409다`)와 503 테스트 포함.

- [ ] **Step 7: 커밋한다**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java
git commit -m "feat: SCIM 핸들러가 판단 읽기를 하지 않는다 — 직원 PATCH·PUT userName 중복 409, 없는 대상 404 는 락 안에서"
git push
```

---

### Task 5: 두 인스턴스 — 락 둘·같은 테이블로 동시 변경

**Files:**
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TwoInstanceWriteTest.java`

**Interfaces:**
- Consumes: Task 1 의 `createUser`·`changeUser`, 기존 `DynamoDbMutationLock(client, properties, clock, holderId)`, `DynamoDbDirectoryStateRepository(client, properties, clock)`, core testFixtures 의 `FakeTupleWriter`·`FakeTupleChecker`

- [ ] **Step 1: 테스트를 쓴다**

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app-scim 두 대를 흉내 낸다 (SCIM 쓰기 락 설계 §7 (c)). 유스케이스 둘이 락 객체를 따로, 테이블은 같이 쓴다 — 두 인스턴스가
 * 같은 직원을 동시에 바꿔도 락 안에서 읽고 계산하므로 서로의 변경을 지우지 않는다. 분산 락 자체의 계약은
 * {@link DynamoDbMutationLockTest} 가 본다.
 */
class TwoInstanceWriteTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository 저장소;
    private IncrementalSyncUseCase 인스턴스1;
    private IncrementalSyncUseCase 인스턴스2;

    @BeforeEach
    void 두_인스턴스를_띄운다() {
        properties.setLockTtl(Duration.ofSeconds(30));
        Clock clock = Clock.systemUTC();
        저장소 = new DynamoDbDirectoryStateRepository(client, properties, clock);
        // OpenFGA 가짜는 두 인스턴스가 함께 쓴다 — 같은 store 를 흉내 낸다. 쓰기는 락 안이라 한 번에 하나다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleChecker checker = new FakeTupleChecker();
        인스턴스1 = new IncrementalSyncUseCase(new DynamoDbDirectoryStateRepository(client, properties, clock), writer, checker,
                new DynamoDbMutationLock(client, properties, clock, "instance-1"),
                Duration.ofSeconds(30), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        인스턴스2 = new IncrementalSyncUseCase(new DynamoDbDirectoryStateRepository(client, properties, clock), writer, checker,
                new DynamoDbMutationLock(client, properties, clock, "instance-2"),
                Duration.ofSeconds(30), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    @Test
    @DisplayName("두 인스턴스가 같은 직원의 서로 다른 속성을 동시에 바꿔도 모든 변경이 남는다")
    void 동시_변경이_모두_남는다() {
        // given
        인스턴스1.createUser(new DirectoryUser("kim", "ext-kim", "kim", "김철수", "kim@example.com", true))
                .block(Duration.ofSeconds(30));
        List<UnaryOperator<DirectoryUser>> 변경 = List.of(
                u -> u.withDisplayName("표시명 바뀜"),
                u -> u.withEmail("new@example.com"),
                u -> u.withExternalId("ext-new"),
                u -> u.withName(u.name().withGivenName("길동")),
                u -> u.withName(u.name().withFamilyName("홍")),
                u -> u.withName(u.name().withMiddleName("중")),
                u -> u.withName(u.name().withHonorificPrefix("Mr.")),
                u -> u.withName(u.name().withHonorificSuffix("Jr.")));

        // when — 두 인스턴스가 번갈아, 한꺼번에 보낸다. 락을 못 잡은 쪽은 기다렸다 다시 잡는다(대기 한도 30초)
        Flux.range(0, 변경.size())
                .flatMap(i -> (i % 2 == 0 ? 인스턴스1 : 인스턴스2).changeUser("kim", 변경.get(i))
                        .subscribeOn(Schedulers.boundedElastic()), 변경.size())
                .blockLast(Duration.ofMinutes(2));

        // then
        DirectoryUser kim = 저장소.findUser("kim").block();
        assertThat(kim.displayName()).isEqualTo("표시명 바뀜");
        assertThat(kim.email()).isEqualTo("new@example.com");
        assertThat(kim.externalId()).isEqualTo("ext-new");
        assertThat(kim.name().givenName()).isEqualTo("길동");
        assertThat(kim.name().familyName()).isEqualTo("홍");
        assertThat(kim.name().middleName()).isEqualTo("중");
        assertThat(kim.name().honorificPrefix()).isEqualTo("Mr.");
        assertThat(kim.name().honorificSuffix()).isEqualTo("Jr.");
    }
}
```

- [ ] **Step 2: 통과를 확인한다** — Task 1 이 입구를 만들었으므로 바로 통과해야 한다. 이 테스트가 옛 방식(락 밖 읽기)을 잡는지 한 번 확인한다: 테스트 안에서 `changeUser` 대신 "`저장소.findUser("kim")` 로 먼저 읽고 계산한 뒤 `upsertUser`" 로 바꿔 돌리면 변경 일부가 사라져 실패하는 것을 보고 되돌린다(보고서에 출력을 남긴다).

Run: `./gradlew :storage-dynamodb:test --tests '*TwoInstanceWriteTest*'`
Expected: PASS

- [ ] **Step 3: 커밋한다**

```bash
git add storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TwoInstanceWriteTest.java
git commit -m "test: 두 인스턴스가 같은 직원을 동시에 바꿔도 모든 변경이 남는다"
git push
```

---

### Task 6: 경합 시나리오 E2E, 규모 테스트 한 단계, README·스펙

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteRaceEndToEndTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java` (Order 5 추가)
- Modify: `README.md` (`## SCIM`, `### app-scim 여러 대 띄우기(동시성 제어)`)
- Modify: `docs/superpowers/specs/2026-09-26-group-member-patch-design.md` §12

**Interfaces:**
- Consumes: Task 1~4 의 동작(409·404·락 안 판단)

- [ ] **Step 1: 경합 E2E 를 쓴다** — `ScimWriteRaceEndToEndTest`. 컨테이너와 `check` 는 `ScimGroupMemberPatchEndToEndTest` 와 같은 모양이다.

```java
package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시에 온 SCIM 쓰기가 서로를 지우지 않는다 — 실제 DynamoDB 락과 OpenFGA 위에서 (SCIM 쓰기 락 설계 §7 (b)).
 *
 * <p>시나리오마다 {@link #라운드} 번 반복해 한 번의 타이밍 운에 기대지 않는다. 요청들은 출발선을 맞춰 동시에 보내고, 락을 못
 * 잡은 503 은 IdP 처럼 200ms 쉬었다 다시 보낸다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimWriteRaceEndToEndTest {

    private static final int 라운드 = 10;

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 요청 하나. 503 이면 IdP 처럼 200ms 쉬었다 다시 보낸다 — ScimRebuildLockScaleTest.보낸다 와 같은 관례. */
    private int 보낸다(HttpMethod method, String uri, String body) {
        int status = 503;
        for (int 시도 = 0; 시도 < 10; 시도++) {
            WebTestClient.RequestBodySpec spec = client.method(method).uri(uri);
            status = (body == null ? spec.exchange()
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange())
                    .returnResult(Void.class).getStatus().value();
            if (status != 503) {
                return status;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status;
    }

    /** 요청들을 출발선을 맞춰 동시에 보내고 상태코드를 요청 순서대로 돌려준다. */
    private List<Integer> 동시에(List<Callable<Integer>> 요청들) throws Exception {
        var pool = Executors.newFixedThreadPool(요청들.size());
        var 출발 = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> 요청 : 요청들) {
                futures.add(pool.submit(() -> {
                    출발.await();
                    return 요청.call();
                }));
            }
            출발.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(1, TimeUnit.MINUTES));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String 직원본문(String userName) {
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"%s","displayName":"%s","active":true}""".formatted(userName, userName);
    }

    private static String 조직본문(String code, String... userNames) {
        String members = String.join(",", Arrays.stream(userNames)
                .map(name -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(name)).toList());
        return """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"%s","displayName":"%s","members":[%s]}""".formatted(code, code, members);
    }

    private static String 패치(String operations) {
        return """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[%s]}""".formatted(operations);
    }

    private void 직원을_만든다(String userName) {
        assertThat(보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(userName))).isEqualTo(201);
    }

    private void 조직을_만든다(String code, String... userNames) {
        assertThat(보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code, userNames))).isEqualTo(201);
    }

    private JsonNode 조회한다(String uri) {
        return client.get().uri(uri).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    @Test
    @DisplayName("같은 직원에 서로 다른 속성을 바꾸는 PATCH 다섯 개를 동시에 보내도 다섯 변경이 모두 남는다")
    void 서로_다른_속성_PATCH() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = "race1-" + r;
            String 조직 = "R1G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"새 이름\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"add\",\"path\":\"emails[type eq \\\"work\\\"].value\",\"value\":\"new@example.com\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"name.givenName\",\"value\":\"길동\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"externalId\",\"value\":\"ext-바뀜\"}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}"))));

            // then — 락 밖에서 읽은 직원으로 계산하면 늦게 저장된 쪽이 앞의 변경을 지운다(설계 §1.1)
            assertThat(statuses).as("라운드 %d", r).containsOnly(200);
            JsonNode user = 조회한다(uri);
            assertThat(user.get("displayName").asText()).as("라운드 %d", r).isEqualTo("새 이름");
            assertThat(user.get("emails").get(0).get("value").asText()).as("라운드 %d", r).isEqualTo("new@example.com");
            assertThat(user.get("name").get("givenName").asText()).as("라운드 %d", r).isEqualTo("길동");
            assertThat(user.get("externalId").asText()).as("라운드 %d", r).isEqualTo("ext-바뀜");
            assertThat(user.get("active").asBoolean()).as("라운드 %d", r).isFalse();
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d — 비활성이면 권한이 없다", r).isFalse();
        }
    }

    @Test
    @DisplayName("비활성화 PATCH 와 이름 변경 PATCH 가 동시에 와도 비활성이 유지되고 권한이 되살아나지 않는다")
    void 비활성화와_이름_변경() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = "race2-" + r;
            String 조직 = "R2G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}")),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"개명\"}"))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsOnly(200);
            JsonNode user = 조회한다(uri);
            assertThat(user.get("active").asBoolean()).as("라운드 %d", r).isFalse();
            assertThat(user.get("displayName").asText()).as("라운드 %d", r).isEqualTo("개명");
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }

    @Test
    @DisplayName("DELETE 와 PATCH 가 동시에 와도 지운 직원이 되살아나지 않는다")
    void 삭제와_변경() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = "race3-" + r;
            String 조직 = "R3G" + r;
            직원을_만든다(id);
            조직을_만든다(조직, id);
            String uri = "/scim/v2/Users/" + id;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.DELETE, uri, null),
                    () -> 보낸다(HttpMethod.PATCH, uri, 패치("{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"x\"}"))));

            // then — PATCH 가 먼저면 200 뒤 삭제, DELETE 가 먼저면 PATCH 는 404. 어느 쪽이든 직원은 없다(설계 §1.2)
            assertThat(statuses.get(0)).as("라운드 %d", r).isEqualTo(204);
            assertThat(statuses.get(1)).as("라운드 %d", r).isIn(200, 404);
            client.get().uri(uri).exchange().expectStatus().isNotFound();
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }

    @Test
    @DisplayName("대소문자만 다른 userName 으로 동시에 만들면 하나만 201 이고 하나는 409 다")
    void 대소문자만_다른_생성() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String 대문자 = "Case-" + r;
            String 소문자 = "case-" + r;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(대문자)),
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Users", 직원본문(소문자))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(201, 409);
            JsonNode found = client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"" + 소문자 + "\"")
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(found.get("totalResults").asInt()).as("라운드 %d", r).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 조직을 동시에 만들면 하나만 201 이고 하나는 409 다")
    void 같은_조직_동시_생성() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String code = "DUP" + r;

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code)),
                    () -> 보낸다(HttpMethod.POST, "/scim/v2/Groups", 조직본문(code))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(201, 409);
            client.get().uri("/scim/v2/Groups/" + code).exchange().expectStatus().isOk();
        }
    }

    @Test
    @DisplayName("직원 비활성화와 그 직원을 조직에 넣는 PATCH 가 동시에 와도 멤버십은 생기고 권한은 없다")
    void 비활성화와_조직_추가() throws Exception {
        for (int r = 0; r < 라운드; r++) {
            // given
            String id = "race6-" + r;
            String 조직 = "R6G" + r;
            직원을_만든다(id);
            조직을_만든다(조직);

            // when
            List<Integer> statuses = 동시에(List.of(
                    () -> 보낸다(HttpMethod.PATCH, "/scim/v2/Users/" + id,
                            패치("{\"op\":\"replace\",\"path\":\"active\",\"value\":false}")),
                    () -> 보낸다(HttpMethod.PATCH, "/scim/v2/Groups/" + 조직,
                            패치("{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"%s\",\"type\":\"User\"}]}".formatted(id)))));

            // then
            assertThat(statuses).as("라운드 %d", r).containsExactlyInAnyOrder(200, 204);
            JsonNode group = 조회한다("/scim/v2/Groups/" + 조직);
            List<String> members = new ArrayList<>();
            group.get("members").forEach(member -> members.add(member.get("value").asText()));
            assertThat(members).as("라운드 %d", r).containsExactly(id);
            assertThat(check("user:" + id, "member", "group:" + 조직)).as("라운드 %d", r).isFalse();
        }
    }
}
```

- [ ] **Step 2: 통과를 확인한다**

Run: `./gradlew :app-scim:test --tests '*ScimWriteRaceEndToEndTest*'`
Expected: PASS (6). 실패하면 어느 시나리오의 몇 번째 라운드인지(`as("라운드 %d")`)를 보고서에 남기고, 단언을 느슨하게 하지 말고 원인을 조사한다.

- [ ] **Step 3: 규모 테스트에 한 단계를 더한다** — `ScimGroupMemberPatchScaleTest` 끝에(이 클래스의 `보낸다(String operations)` 는 조직 `ALL` 에 PATCH 를 보내고 204 를 기대한다).

```java
    @Test
    @Order(5)
    @DisplayName("10만 명 조직에 속한 직원 한 명을 지워도 조직 파티션을 훑지 않는다")
    void 소속_직원_삭제는_조직을_훑지_않는다() {
        // given — leaver 를 ALL 에 넣는다
        state.saveUser(new DirectoryUser("leaver", "ext-leaver", "leaver", "퇴사자", null, true)).block();
        보낸다("""
                [{"op":"add","path":"members","value":[{"value":"leaver","type":"User"}]}]
                """);
        assertThat(check("user:leaver", "member", "group:" + 조직)).isTrue();
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .delete().uri("/scim/v2/Users/leaver").exchange().expectStatus().isNoContent();

        // then — 직원 파티션만 읽는다(소속 줄 찾기, 삭제). 조직 파티션 10만 줄은 읽지 않는다
        읽은양을_찍는다("소속 직원 삭제", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(2);
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(10);
        assertThat(checks.checkedTuples.get()).isLessThanOrEqualTo(1);
        assertThat(state.findMembers(조직, Set.of(MemberRef.user("leaver"))).block()).isEmpty();
        assertThat(check("user:leaver", "member", "group:" + 조직)).isFalse();
    }
```

Run: `./gradlew :app-scim:compileTestJava` — 컴파일만(규모 테스트 실행은 컨트롤러).

- [ ] **Step 4: README 와 이전 스펙을 고친다**

`README.md` `## SCIM` 의 PATCH 표 아래 "`op` 와 `path` 의 속성 이름은 대소문자를 가리지 않는다" 문단 뒤에:

```markdown
**`userName` 은 POST·PUT·PATCH 모두에서 대소문자를 무시하고 유일하다**(RFC 7643 `uniqueness: server`, `caseExact: false`). 겹치면
409 `uniqueness` 다. 확인은 전역 쓰기 락 안에서 GSI 로 후보를 찾고 본 테이블에서 다시 읽는다 — 방금(GSI 반영 전, 보통 1초 미만)
저장된 직원과 대소문자만 다른 이름은 드물게 통과할 수 있다. 설계: `docs/superpowers/specs/2026-09-28-scim-write-lock-design.md`.
```

`### app-scim 여러 대 띄우기(동시성 제어)` 절 첫 문단 뒤에:

```markdown
**쓰기 요청의 판단은 전부 락 안에서 일어난다.** 직원·조직을 읽고, 존재를 확인하고, 아이디·`userName` 중복을 확인하는 일을 모두 락을
잡은 뒤에 한다 — 동시에 온 PATCH 가 서로의 변경을 지우거나(비활성화가 되돌려져 퇴사자 권한이 되살아나는 것 포함) 방금 지운 직원을
되살리지 않는다. 직원 삭제는 소속 조직의 이름표만 읽고 그 직원의 줄만 지운다 — 조직 크기와 무관하다.
```

`docs/superpowers/specs/2026-09-26-group-member-patch-design.md` §12 에서 "**직원 쪽(바로 다음 슬라이드)**", "조직 POST 의 중복 확인이 락 밖이다", "`removeUser` 가 소속 조직마다 …" 세 줄 끝에 각각 "→ 해결: `2026-09-28-scim-write-lock-design.md`" 를 붙인다.

- [ ] **Step 5: 커밋한다**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteRaceEndToEndTest.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java README.md docs/superpowers/specs/2026-09-26-group-member-patch-design.md
git commit -m "test: 동시 SCIM 쓰기 경합 시나리오 여섯을 반복한다, 10만 명 조직 소속 직원 삭제 / docs: userName 유일성, 판단은 락 안"
git push
```

---

## Self-Review (계획 작성자)

- **스펙 대응:** §3 입구 → Task 1(createUser·changeUser·createGroup·빈 결과)·Task 4(핸들러). §4 userName 규칙 → Task 1. §5 직원 삭제 → Task 2. §6 응답·README → Task 4·6. §7 (a) → Task 3, (b) → Task 6, (c) → Task 5, 규모 → Task 6, core 시나리오 → Task 1·2. §1.2 부활 → Task 1 `지운_직원을_되살리지_않는다`, Task 6 `삭제와_변경`.
- **타입 일관성:** `createUser(DirectoryUser)`, `changeUser(String, UnaryOperator<DirectoryUser>)`, `createGroup(DirectoryGroup)`, `DirectoryConflictException(String)` 을 Task 3·4·5·6 이 그대로 쓴다. `FakeMutationLock.isHeld()` 는 Task 3 에서만 쓴다.
- **바뀌지 않는 것:** `upsertUser`·`upsertGroup`(테스트·등가 비교가 계속 쓴다), `changeGroup`, LDAP 전체 동기화.
