# 직원 한 명 변경의 스냅샷 축소 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 으로 직원 한 명이 바뀔 때 그 직원이 속한 조직의 멤버 전원을 읽던 것을 멈춰, 왕복 ≈3,203 → 3 으로 줄인다.

**Architecture:** `IncrementalSyncUseCase` 의 직원 경로(`upsertUser`/`removeUser`)에서 델타 계산용 스냅샷의 멤버 목록을 바뀐 그 직원 하나로 좁힌다. `diffAndApply` 가 이미 `mentioning(user:kim)` 으로 결과를 거르므로 델타는 동일하다. 저장소에는 조직 META 만 읽는 `findGroupHeader` 를 더한다. **저장 경로(`saveGroup`)에는 좁힌 목록이 절대 흘러가면 안 된다** — `saveGroup` 은 `members()` 를 최종 목록으로 받아 없는 멤버 줄을 삭제한다.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Reactor, Lombok, AssertJ, JUnit 5, DynamoDB Local(Testcontainers), OpenFGA

**Spec:** `docs/superpowers/specs/2026-09-10-single-user-snapshot-design.md`

## Global Constraints

- **테스트는 BDD 주석(`// given` / `// when` / `// then`)과 한글 `@DisplayName` 을 쓴다.** 기존 테스트가 전부 그렇다.
- **AssertJ 를 쓴다** (`assertThat`). JUnit 의 `Assertions` 를 쓰지 않는다.
- **Lombok 을 쓴다** — 운영 코드에서 `@Slf4j`, `@RequiredArgsConstructor` 등 기존 관례를 따른다.
- **커밋할 때마다 푸시한다.**
- **`saveGroup` 에 넘기는 `DirectoryGroup` 은 반드시 전체 멤버 목록을 갖는다.** 좁힌 것을 넘기면 그 조직의 멤버 줄이 삭제된다.
- **`upsertGroup` / `removeGroup` 경로는 한 줄도 바꾸지 않는다.**
- **`findGroupIdsContaining` 은 바꾸지 않는다.**

---

## File Structure

| 파일 | 책임 | 상태 |
|---|---|---|
| `core/src/main/java/dev/starryeye/organization/core/model/GroupHeader.java` | 멤버 없이 조직의 식별·표시 정보만 담는 값 | **신규** |
| `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` | `findGroupHeader` 추가 | 수정 |
| `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` | `GetItem(조직PK, META)` 구현 | 수정 |
| `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` | 구현 + `findUserCalls`·`findGroupHeaderCalls` 계측 | 수정 |
| `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` | 직원 경로의 스냅샷을 좁힘 | 수정 |
| `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java` | **읽기 횟수**를 못박는다 | **신규** |
| `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java` | `findGroupHeader` 검증 | 수정 |

---

## Task 1: `findGroupHeader` — 조직 META 만 읽는다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/GroupHeader.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: 없음 (첫 작업)
- Produces:
  - `dev.starryeye.organization.core.model.GroupHeader` — `record GroupHeader(String id, String externalId, String displayName)`
  - `DirectoryStateRepository.findGroupHeader(String groupId)` → `Mono<GroupHeader>`; 조직이 없으면 **빈 Mono**
  - `FakeStateRepository.findGroupHeaderCalls` — `public final List<String>`, 불린 순서대로의 조직 id

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest.java` 에 추가한다. 이 클래스의 기존 테스트가 쓰는 필드명(저장소 인스턴스 이름 등)과 준비 방식을 그대로 따를 것 — 아래 코드의 `repository` 가 그 클래스의 실제 이름과 다르면 실제 이름으로 바꾼다.

```java
@Test
@DisplayName("findGroupHeader 는 멤버를 읽지 않고 조직의 이름과 externalId 만 돌려준다")
void 헤더만_읽는다() {
    // given — 멤버가 있는 조직
    repository.saveGroup(new DirectoryGroup("PLANT", "ou=plant", "제1공장",
            Set.of(MemberRef.user("kim"), MemberRef.user("park")))).block();

    // when
    GroupHeader header = repository.findGroupHeader("PLANT").block();

    // then
    assertThat(header).isNotNull();
    assertThat(header.id()).isEqualTo("PLANT");
    assertThat(header.externalId()).isEqualTo("ou=plant");
    assertThat(header.displayName()).isEqualTo("제1공장");
}

@Test
@DisplayName("없는 조직이면 빈 결과다 — findGroup 이 빈 것을 돌려주던 것과 같은 뜻")
void 없는_조직은_빈_결과다() {
    // when
    GroupHeader header = repository.findGroupHeader("없는조직").block();

    // then
    assertThat(header).isNull();
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`

Expected: 컴파일 실패 — `GroupHeader` 와 `findGroupHeader` 가 없다.

- [ ] **Step 3: `GroupHeader` 를 만든다**

`core/src/main/java/dev/starryeye/organization/core/model/GroupHeader.java`:

```java
package dev.starryeye.organization.core.model;

/**
 * 멤버 없이 조직의 식별·표시 정보만 담는다. <b>{@link DirectoryGroup} 을 멤버 없이 쓰지
 * 않는 이유가 있다</b> — 이 코드베이스에서 "멤버 0개" 는 이미 다른 뜻으로 쓰인다
 * ({@code IncrementalSyncUseCase.expandWithReferencedGroups} 가 "존재만 확인하고 튜플은
 * 만들지 마라" 는 표시로 빈 멤버를 쓴다). 거기에 "진짜 멤버가 없는 조직" 과 "헤더만 읽은
 * 조직" 까지 겹치면 한 값이 세 가지 뜻을 갖고, 누군가 헤더를 스냅샷에 그대로 넣고
 * "멤버 0명이네" 로 읽는 사고가 난다.
 *
 * <p>별도 타입이면 스냅샷에 넣으려면 {@code new DirectoryGroup(id, ..., 멤버)} 를 손으로
 * 만들어야 하고, 그 순간 "누구를 넣을지" 를 명시하게 된다.
 *
 * @param id 조직코드. 튜플에 쓰이는 안정 식별자
 * @param displayName 조직명. 튜플에 절대 쓰지 않는다
 */
public record GroupHeader(
        String id,
        String externalId,
        String displayName
) {
}
```

- [ ] **Step 4: 인터페이스에 더한다**

`DirectoryStateRepository.java` 의 `findGroup` 선언 바로 아래에 넣는다. `import dev.starryeye.organization.core.model.GroupHeader;` 를 추가할 것.

```java
    /**
     * 멤버를 빼고 조직의 META 만 읽는다. <b>읽는 양이 조직 크기를 따라가지 않는다.</b>
     *
     * <p>직원 한 명에 대한 연산은 조직의 id 만 있으면 튜플을 만들 수 있는데,
     * {@link #findGroup} 은 파티션을 통째로 읽어 1,600명 조직이면 1,601 아이템을 가져온다.
     * {@link #findUser} 가 이미 같은 이유로 Query 대신 GetItem 을 쓴다.
     *
     * <p>조직이 없으면 빈 {@code Mono} 다 — {@link #findGroup} 이 그때 빈 것을 돌려주는
     * 것과 같은 뜻이므로, 부르는 쪽의 "없는 조직은 건너뛴다" 동작이 바뀌지 않는다.
     */
    Mono<GroupHeader> findGroupHeader(String groupId);
```

- [ ] **Step 5: DynamoDB 구현을 쓴다**

`DynamoDbDirectoryStateRepository.java` 의 `findGroup` 바로 아래에 넣는다. `import dev.starryeye.organization.core.model.GroupHeader;` 를 추가할 것.

```java
    /**
     * PK 와 SK 를 모두 알고 있으므로 {@code GetItem} 으로 META 한 건만 집어온다 —
     * {@link #findUser} 와 같은 이유다. 읽는 양이 조직 크기를 따라가지 않는다.
     *
     * <p><b>강한 일관성으로 읽는다.</b> 클래스 자바독의 "강한 일관성" 절 참고.
     */
    @Override
    public Mono<GroupHeader> findGroupHeader(String groupId) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                                Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(GetItemResponse::hasItem)
                .map(response -> new GroupHeader(groupId,
                        Attrs.str(response.item(), EXTERNAL_ID),
                        Attrs.str(response.item(), DISPLAY_NAME)));
    }
```

- [ ] **Step 6: Fake 구현을 쓴다**

`FakeStateRepository.java`. 필드는 클래스 상단의 `findGroupCalls` 옆에, 메서드는 `findGroup` 바로 아래에 둔다. `import dev.starryeye.organization.core.model.GroupHeader;` 를 추가할 것.

```java
    /**
     * {@link #findGroupHeader} 가 불린 순서대로의 조직 id. {@link #findGroupCalls} 와
     * 같은 목적의 계측이다.
     */
    public final List<String> findGroupHeaderCalls = new ArrayList<>();

    @Override
    public Mono<GroupHeader> findGroupHeader(String groupId) {
        return Mono.fromRunnable(() -> findGroupHeaderCalls.add(groupId))
                .then(Mono.justOrEmpty(groups.get(groupId)))
                .map(group -> new GroupHeader(
                        group.id(), group.externalId(), group.displayName()));
    }
```

- [ ] **Step 7: `LdapInterruptedSyncScaleTest` 안의 구현체를 고친다**

`app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` 안에 `DirectoryStateRepository` 를 구현하는 것이 하나 더 있다. 인터페이스에 메서드가 늘었으므로 컴파일이 깨진다.

그 구현이 다른 메서드를 위임(delegate)하는 방식이면 `findGroupHeader` 도 같은 방식으로 위임한다. 위임 대상 필드명은 그 클래스의 기존 메서드가 쓰는 이름을 그대로 따를 것.

```java
    @Override
    public Mono<GroupHeader> findGroupHeader(String groupId) {
        return delegate.findGroupHeader(groupId);
    }
```

- [ ] **Step 8: 통과를 확인한다**

Run: `./gradlew :core:compileTestFixturesJava :app-ldap:compileTestJava :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`

Expected: PASS, 컴파일 오류 없음

- [ ] **Step 9: 커밋하고 푸시한다**

커밋 메시지:

```
feat: 조직 META 만 읽는 findGroupHeader

findGroup 은 파티션을 통째로 읽어 1,600명 조직이면 1,601 아이템이 온다.
직원 한 명에 대한 연산은 조직 id 만 있으면 튜플을 만들 수 있다.
findUser 가 이미 같은 이유로 GetItem 을 쓴다.

DirectoryGroup 을 멤버 없이 쓰지 않고 GroupHeader 를 따로 둔다 —
이 코드베이스에서 '멤버 0개' 는 이미 다른 뜻이다.
```

커밋 후 현재 브랜치를 origin 에 푸시한다.

---

## Task 2: `upsertUser` 의 스냅샷을 그 직원 하나로 좁힌다

**Files:**
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`

**Interfaces:**
- Consumes: `DirectoryStateRepository.findGroupHeader(String)` → `Mono<GroupHeader>`; `FakeStateRepository.findGroupHeaderCalls`, `.findGroupCalls`
- Produces:
  - `FakeStateRepository.findUserCalls` — `public final List<String>` (Step 3)
  - `IncrementalSyncUseCase` private `직원한명_그림(Set<GroupHeader> headers, String userId, Mono<DirectoryUser> user)` → `Mono<DirectorySnapshot>`
  - `IncrementalSyncUseCase` private `affectedGroupHeadersOf(String userId)` → `Mono<Set<GroupHeader>>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java` 를 새로 만든다.

생성자 인자와 `FakeTupleWriter`·`FakeTupleChecker` 사용법은 같은 디렉터리의 `IncrementalSyncCandidateScopeTest` 를 본떠 맞출 것 — 아래 코드는 그 클래스의 형태를 따른 것이다.

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>직원 한 명에 대한 연산이 조직 크기만큼 읽지 않는다.</b>
 *
 * <p>{@link IncrementalSyncCandidateScopeTest} 가 <b>OpenFGA 쪽</b>(무엇을 Check 하는가)을
 * 못박는다면, 이 테스트는 <b>DynamoDB 쪽</b>(무엇을 읽는가)을 못박는다. 둘은 다른 질문이고,
 * 전자만 고쳐 놓은 채로 후자가 오래 살아 있었다 — 결과가 맞아서 어떤 테스트도 묻지 않았다.
 *
 * <p><b>이 테스트가 없으면 이 최적화는 조용히 되돌아간다.</b> 되돌려도 튜플은 여전히 맞고
 * 규모 E2E 도 전부 통과하기 때문이다. 여기서만 잡힌다.
 */
class IncrementalSyncReadScopeTest {

    /** 조직 크기에 비례하는 읽기가 있으면 확실히 드러나도록 크게 잡는다. */
    private static final int 대형조직_멤버수 = 300;
    private static final String 대형조직 = "PLANT";

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        useCase = new IncrementalSyncUseCase(
                state, writer, new FakeTupleChecker(), new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        Set<MemberRef> 멤버 = new LinkedHashSet<>();
        IntStream.range(0, 대형조직_멤버수).forEach(i -> {
            DirectoryUser 동료 = 직원("u" + i, true);
            state.users.put(동료.id(), 동료);
            멤버.add(MemberRef.user(동료.id()));
        });
        state.groups.put(대형조직, new DirectoryGroup(대형조직, "ou=plant", "제1공장", 멤버));

        state.findGroupCalls.clear();
        state.findGroupHeaderCalls.clear();
        state.findUserCalls.clear();
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("직원 한 명을 비활성화할 때 그 한 명만 읽는다 — 동료 299명을 읽지 않는다")
    void 퇴사에_동료를_읽지_않는다() {
        // when — u0 퇴사
        useCase.upsertUser(직원("u0", false)).block(Duration.ofSeconds(10));

        // then — 읽은 직원은 본인뿐이다
        assertThat(state.findUserCalls)
                .as("동료를 읽어도 그 결과는 mentioning(user:u0) 이 전부 버린다")
                .containsExactly("u0");

        // then — 조직은 헤더로만 읽는다
        assertThat(state.findGroupCalls)
                .as("멤버 목록이 필요 없으므로 파티션을 통째로 읽지 않는다")
                .isEmpty();
        assertThat(state.findGroupHeaderCalls).containsExactly(대형조직);
    }

    @Test
    @DisplayName("좁혀도 델타는 그대로다 — 비활성화하면 그 직원의 튜플만 지워진다")
    void 좁혀도_델타가_같다() {
        // when
        useCase.upsertUser(직원("u0", false)).block(Duration.ofSeconds(10));

        // then — 동료의 튜플은 건드리지 않는다
        assertThat(writer.deleted)
                .as("u0 것만 지워야 한다")
                .isNotEmpty()
                .allSatisfy(tuple -> assertThat(tuple.user()).isEqualTo("user:u0"));
        assertThat(writer.written).isEmpty();
    }
}
```

**확인할 것:** `FakeTupleWriter` 의 기록 필드명이 `written`/`deleted` 가 맞는지, `FakeTupleChecker` 가 기본적으로 "튜플이 이미 있다" 를 어떻게 흉내내는지. 후자에 따라 `좁혀도_델타가_같다` 의 삭제 델타가 실제로 생기도록 `@BeforeEach` 에 준비를 더해야 할 수 있다. `IncrementalSyncCandidateScopeTest` 가 같은 것을 어떻게 준비하는지 그대로 따를 것.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest*'`

Expected: 컴파일 실패 — `state.findUserCalls` 가 없다.

- [ ] **Step 3: Fake 에 `findUserCalls` 를 더한다**

`FakeStateRepository.java` 의 기존 `findUser` 를 아래로 바꾸고, 필드를 `findGroupCalls` 옆에 둔다.

```java
    /** {@link #findUser} 가 불린 순서대로의 직원 아이디. {@link #findGroupCalls} 와 같은 목적. */
    public final List<String> findUserCalls = new ArrayList<>();

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.fromRunnable(() -> findUserCalls.add(userId))
                .then(Mono.justOrEmpty(users.get(userId)));
    }
```

- [ ] **Step 4: 실패 이유가 맞는지 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest*'`

Expected: `퇴사에_동료를_읽지_않는다` 가 **컴파일이 아니라 단언으로** 실패한다 — `findUserCalls` 에 동료가 잔뜩 들어 있고 `findGroupCalls` 가 비어 있지 않다.

**실패 메시지를 눈으로 확인할 것.** 컴파일 오류만 보고 넘어가면 이 테스트가 무엇을 잡는지 증명하지 못한다.

- [ ] **Step 5: 한 사람짜리 스냅샷을 만든다**

`IncrementalSyncUseCase.java` 의 `snapshotOf` 바로 아래에 추가한다. `import dev.starryeye.organization.core.model.GroupHeader;` 를 추가할 것.

```java
    /**
     * <b>직원 한 명에 대한 연산을 위한 스냅샷.</b> 조직의 멤버 목록을 그 직원 하나로 바꾸고,
     * 유저도 그 한 명만 싣는다. {@link #loadMemberUsers} 를 부르지 않는다.
     *
     * <p><b>왜 동료를 안 실어도 결과가 같은가.</b> {@link #diffAndApply} 가 후보·목표·상태
     * 기준선 셋 모두를 {@code mentioning(user:그사람)} 으로 좁힌다. 동료의 튜플은
     * {@code direct_member(user:X, group:G)} 라 어느 자리도 그 직원이 아니므로 <b>세 집합
     * 전부에서 사라진다.</b> 좁힌 스냅샷은 그 튜플들을 애초에 만들지 않을 뿐, 걸러진 결과가
     * 같다. child 간선은 {@code group:} 둘로만 이루어져 역시 언급되지 않고, 사용자는 조직
     * 그래프에 순환을 만들 수 없다.
     *
     * <p><b>여기서 만든 조직을 {@code saveGroup} 에 넘기면 안 된다.</b> 멤버 목록이 한 명뿐이라
     * 그 조직의 나머지 멤버 줄이 전부 삭제된다 — 저장에는 반드시 전체 목록을 쓴다.
     */
    private Mono<DirectorySnapshot> 직원한명_그림(Set<GroupHeader> headers,
                                             String userId,
                                             Mono<DirectoryUser> user) {
        Set<MemberRef> 그사람만 = Set.of(MemberRef.user(userId));
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        headers.forEach(header -> groups.put(header.id(), new DirectoryGroup(
                header.id(), header.externalId(), header.displayName(), 그사람만)));

        return user.map(Set::of).defaultIfEmpty(Set.<DirectoryUser>of())
                .map(users -> new DirectorySnapshot(byUserId(users), groups));
    }

    /** 이 직원이 속한 모든 조직의 헤더. 멤버 목록이 필요 없는 경로에서 쓴다. */
    private Mono<Set<GroupHeader>> affectedGroupHeadersOf(String userId) {
        return state.findGroupIdsContaining(MemberRef.user(userId))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .collect(LinkedHashSet<GroupHeader>::new, Set::add);
    }
```

`byUserId(Set<DirectoryUser>)` 는 이 클래스에 이미 있다 (`loadMemberUsers` 가 쓴다). 없으면 그 자리에서 쓰는 것과 같은 방식으로 맵을 만들 것.

- [ ] **Step 6: `upsertUser` 를 새 스냅샷으로 바꾼다**

`upsertUserInternal` 의 몸통을 아래로 바꾼다. **커밋(`state.saveUser`)은 그대로 둔다** — 이 경로는 `saveGroup` 을 부르지 않으므로 안전하다.

```java
    private Mono<IncrementalSyncResult> upsertUserInternal(DirectoryUser user, LockLease lease) {
        DirectoryUser neverStored = new DirectoryUser(
                user.id(), user.externalId(), user.userName(), user.displayName(), user.email(), false);

        return affectedGroupHeadersOf(user.id())
                .flatMap(headers -> state.findUser(user.id())
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(existing -> {
                            DirectoryUser existingUser = existing.orElse(neverStored);
                            Mono<DirectorySnapshot> before =
                                    직원한명_그림(headers, user.id(), Mono.just(existingUser));
                            Mono<DirectorySnapshot> after =
                                    직원한명_그림(headers, user.id(), Mono.just(user));

                            Commit commit = (result, beforeTuples, afterTuples) -> {
                                if (existing.isEmpty() && result.hasFailure()) {
                                    return Mono.empty();
                                }
                                return Mono.defer(() -> state.saveUser(reconcileUser(existingUser, user, result)));
                            };

                            return diffAndApply(before, after, RelationTuple.userRef(user.id()), lease, commit);
                        }));
    }
```

- [ ] **Step 7: core 전체가 통과하는지 확인한다**

Run: `./gradlew :core:test`

Expected: 새 테스트 PASS. **기존 core 테스트가 전부 그대로 통과해야 한다** — 특히 `IncrementalSyncUseCaseTest`, `IncrementalSyncCandidateScopeTest`, `IncrementalSyncDriftTest`.

하나라도 깨지면 좁힌 스냅샷이 델타를 바꾼 것이다. **테스트를 고치지 말고 멈춰서 원인을 찾을 것.**

- [ ] **Step 8: SCIM 규모 시나리오로 회귀를 확인한다**

Run: `./gradlew :app-scim:test`

Expected: 전부 통과. 좁힌 스냅샷이 다른 델타를 내면 여기서 터진다.

- [ ] **Step 9: 커밋하고 푸시한다**

커밋 메시지:

```
perf: upsertUser 의 스냅샷을 그 직원 하나로 좁힌다

diffAndApply 가 mentioning(user:kim) 으로 결과를 거르므로 동료의 튜플은
세 집합 전부에서 사라진다. 그런데도 동료 전원을 DynamoDB 에서 읽고 있었다 —
1,600명 조직이면 왕복 3,200 번이고 전부 버려진다.

왕복 3,203 -> 3, 아이템 4,803 -> 3.

IncrementalSyncReadScopeTest 가 읽기 횟수를 못박는다. 되돌려도 튜플은
맞고 규모 E2E 도 통과하므로 그 테스트가 없으면 조용히 되돌아간다.
```

커밋 후 현재 브랜치를 origin 에 푸시한다.

---

## Task 3: `removeUser` 의 스냅샷만 좁힌다 — 저장 경로는 그대로 둔다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`

**Interfaces:**
- Consumes: `직원한명_그림(Set<GroupHeader>, String, Mono<DirectoryUser>)`, 기존 `affectedGroupsOf(String)` → `Mono<Set<DirectoryGroup>>` (전체 멤버를 담은 조직)
- Produces: 없음 (마지막 코드 작업)

**이 작업의 위험:** `removeUser` 의 커밋은 `reconcileRemovedMember` → `state.saveGroup` 을 부른다. `saveGroup` 은 `group.members()` 를 **최종 목록**으로 받아 거기 없는 멤버 줄을 삭제한다. 좁힌 조직이 커밋까지 흘러가면 **그 조직의 멤버 줄이 전부 지워진다.** 그래서 `affectedGroupsOf`(전체 멤버)는 그대로 두고 **스냅샷만** 좁힌다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`IncrementalSyncReadScopeTest.java` 에 두 개를 더한다. `import dev.starryeye.organization.core.model.DirectoryGroup;` 은 이미 있다.

```java
    @Test
    @DisplayName("직원을 삭제할 때도 동료를 읽지 않는다 — 그 직원 하나만 읽는다")
    void 삭제에_동료를_읽지_않는다() {
        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then
        assertThat(state.findUserCalls)
                .as("삭제 대상 본인만 읽는다")
                .containsExactly("u0");
    }

    @Test
    @DisplayName("직원을 삭제해도 동료 299명의 멤버십은 그대로 남는다 — saveGroup 에 좁힌 목록이 새면 전부 지워진다")
    void 삭제가_동료의_멤버십을_지우지_않는다() {
        // when
        useCase.removeUser("u0").block(Duration.ofSeconds(10));

        // then
        DirectoryGroup 저장된조직 = state.groups.get(대형조직);
        assertThat(저장된조직.members())
                .as("u0 만 빠지고 나머지는 그대로여야 한다")
                .hasSize(대형조직_멤버수 - 1)
                .doesNotContain(MemberRef.user("u0"))
                .contains(MemberRef.user("u1"), MemberRef.user("u299"));
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest*'`

Expected:
- `삭제에_동료를_읽지_않는다` 가 **단언으로** 실패한다 — `findUserCalls` 에 동료가 들어 있다.
- `삭제가_동료의_멤버십을_지우지_않는다` 는 **지금 통과한다.** 그것이 맞다 — 새 동작이 아니라 Step 3 에서 깨뜨릴 수 있는 것을 미리 못박는 것이다. 진짜로 잡는지는 Step 5 의 변이 검사에서 확인한다.

- [ ] **Step 3: `removeUser` 의 스냅샷만 좁힌다**

`removeUserInternal` 을 아래로 바꾼다. **`groups` 와 `without` 은 좁히지 않는다** — 커밋이 그것을 쓴다.

```java
    private Mono<IncrementalSyncResult> removeUserInternal(String userId, LockLease lease) {
        return state.findUser(userId)
                .flatMap(user -> affectedGroupsOf(userId).flatMap(groups -> {
                    // 스냅샷은 좁힌다 — 델타에는 이 직원의 튜플만 남으므로 동료가 필요 없다.
                    Set<GroupHeader> headers = groups.stream()
                            .map(group -> new GroupHeader(
                                    group.id(), group.externalId(), group.displayName()))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    Mono<DirectorySnapshot> before = 직원한명_그림(headers, userId, Mono.just(user));
                    // 삭제 후에는 어느 조직에도 속하지 않으므로 조직이 하나도 없는 그림이 맞다.
                    Mono<DirectorySnapshot> after = 직원한명_그림(Set.of(), userId, Mono.empty());

                    // 커밋에는 좁히지 않은 groups/without 을 쓴다 — saveGroup 은 members() 를
                    // 최종 목록으로 받아 거기 없는 멤버 줄을 지운다. 좁힌 것을 넘기면
                    // 이 조직의 멤버가 통째로 삭제된다.
                    Set<DirectoryGroup> without = removeMemberFrom(groups, MemberRef.user(userId));

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        Set<DirectoryGroup> reconciled = reconcileRemovedMember(
                                groups, without, MemberRef.user(userId), beforeTuples, result);
                        Mono<Void> saveGroups = Flux.fromIterable(reconciled)
                                .flatMap(state::saveGroup, LOAD_CONCURRENCY)
                                .then();
                        if (result.hasFailure()) {
                            return saveGroups;
                        }
                        return saveGroups.then(Mono.defer(() -> state.deleteUser(userId)));
                    };

                    return diffAndApply(before, after, RelationTuple.userRef(userId), lease, commit);
                }))
                .defaultIfEmpty(IncrementalSyncResult.noChange());
    }
```

`import java.util.stream.Collectors;` 가 없으면 추가할 것.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test :app-scim:test`

Expected: 전부 PASS.

- [ ] **Step 5: 변이 검사 — 테스트가 진짜로 잡는지 확인한다**

각각을 **일부러 망가뜨리고 지정된 테스트가 실패하는지** 확인한 뒤 되돌린다.

| # | 망가뜨릴 것 | 실패해야 할 테스트 |
|---|---|---|
| 1 | Step 3 의 `commit` 에 `groups` 대신 좁힌 조직(멤버가 `u0` 하나뿐인 것)을 넘긴다 | `삭제가_동료의_멤버십을_지우지_않는다` |
| 2 | Task 2 Step 6 을 되돌려 `affectedGroupsOf` + `snapshotOf(groups, ...)` 로 되돌린다 | `퇴사에_동료를_읽지_않는다` |
| 3 | `직원한명_그림` 의 `그사람만` 을 `Set.of()` 로 바꾼다 | `좁혀도_델타가_같다` (삭제 델타가 안 생김) |

각 변이마다: 고친다 → `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest*'` → 지정된 테스트가 실패하는지 확인 → 되돌린다 → 다시 통과 확인.

**어느 하나라도 통과해 버리면 그 테스트는 아무것도 지키지 않는 것이므로 테스트를 고쳐야 한다.**

- [ ] **Step 6: 커밋하고 푸시한다**

커밋 메시지:

```
perf: removeUser 의 스냅샷도 좁힌다 — 저장 경로는 그대로

스냅샷만 좁히고 groups/without 은 그대로 둔다. saveGroup 이 members() 를
최종 목록으로 받아 거기 없는 멤버 줄을 지우므로, 좁힌 것을 커밋까지
흘리면 그 조직의 멤버가 통째로 삭제된다.

조직 읽기는 남는다 — 커밋이 전체 멤버 목록을 요구한다.
```

커밋 후 현재 브랜치를 origin 에 푸시한다.

---

## Task 4: 실측하고 문서를 갱신한다

**Files:**
- Modify: `docs/superpowers/specs/2026-09-10-single-user-snapshot-design.md`
- Modify: `docs/superpowers/plans/2026-09-06-scale-e2e-scenarios.md`

**Interfaces:**
- Consumes: Task 2·3 의 구현
- Produces: 없음

- [ ] **Step 1: 전체 빌드를 한 번만 돌린다**

```bash
./gradlew --stop
```

그 다음:

```bash
./gradlew clean build
```

**동시에 두 개의 Gradle 빌드를 돌리지 말 것.** 전에 그렇게 해서 test-fixtures jar 를 읽지 못해 29개가 가짜로 실패했다.

Expected: `BUILD SUCCESSFUL`, 실패 0

- [ ] **Step 2: 실측값을 뽑는다**

```bash
grep -rho "=== S1[^<]*\|=== S3[^<]*\|싱크 [0-9.]*초[^<]*" app-scim/build/test-results/test/
```

찾을 것:

| 출처 | 지금 값 |
|---|---|
| `ScimProvisioningOrderScaleTest` — S1 조직 먼저 | **835.4초** |
| `ScimScaleSyncCostTest` — 직원 먼저 싱크 | **46.5초** |
| `ScimScaleScenarioTest` — S3 동시 응답 분포 | `{200=71, 503=129}` |

- [ ] **Step 3: 스펙의 §5.6 표를 채운다**

`docs/superpowers/specs/2026-09-10-single-user-snapshot-design.md` 의 §5.6 표에서 "구현 후 측정해 채운다" 를 실제 값으로 바꾼다.

**S3 의 503 이 줄지 않았으면 줄지 않았다고 적을 것.** §5.7 이 이미 "이것은 관측이지 단정이 아니다" 라고 적어 뒀다. 예상과 다르면 숫자를 고치지 말고 **예상이 틀렸다고 적는다.**

- [ ] **Step 4: 시나리오 문서의 실측 표를 갱신한다**

`docs/superpowers/plans/2026-09-06-scale-e2e-scenarios.md` 의 "실측 비용" 표를 새 수치로 바꾼다.

그리고 그 아래 **"직원 한 명을 바꾸는 비용이 그가 속한 조직의 크기에 비례한다"** 절은 지금 *"고치지 않고 특성으로 기록해 둔다"* 로 적혀 있다. 이제 고쳤으므로 **"고쳤다 (2026-09-10)"** 로 바꾸고 `docs/superpowers/specs/2026-09-10-single-user-snapshot-design.md` 를 가리킨다. `removeUser` 의 조직 읽기가 남는다는 것도 함께 적는다.

- [ ] **Step 5: 커밋하고 푸시한다**

커밋 메시지: `docs: 스냅샷 축소 실측 반영`

커밋 후 현재 브랜치를 origin 에 푸시한다.

---

## Self-Review 결과

**스펙 커버리지**

| 스펙 절 | 구현하는 작업 |
|---|---|
| §4.1 그림과 저장의 구분 | Task 3 Step 3 (`groups` 를 커밋에 유지) |
| §4.2 한 사람짜리 그림 | Task 2 Step 5 |
| §4.3 `upsertUser` 온전히 좁힘 | Task 2 Step 6 |
| §4.4 `removeUser` 그림만 좁힘 | Task 3 Step 3 |
| §4.5 조직 경로 불변 | Global Constraints |
| §4.6 `findGroupHeader` + `GroupHeader` 별도 타입 | Task 1 |
| §5.2 A 읽기 횟수 | Task 2 Step 1, Task 3 Step 1 |
| §5.3 B 결과 동등성 | Task 2 Step 7·8, Task 3 Step 4 |
| §5.4 C 멤버 줄 보존 | Task 3 Step 1 |
| §5.5 D 변이 검사 | Task 3 Step 5 |
| §5.6 E 실측 | Task 4 |

빠진 스펙 요구사항 없음.

**빈칸 없음** — 모든 코드 단계에 실제 코드가, 모든 실행 단계에 실제 명령이 있다.

**타입 일관성** — Task 1~3 에서 같은 이름·시그니처를 쓴다:
- `GroupHeader(String id, String externalId, String displayName)`
- `findGroupHeader(String) → Mono<GroupHeader>`
- `직원한명_그림(Set<GroupHeader>, String, Mono<DirectoryUser>) → Mono<DirectorySnapshot>`
- `affectedGroupHeadersOf(String) → Mono<Set<GroupHeader>>`
- `FakeStateRepository.findUserCalls` / `.findGroupCalls` / `.findGroupHeaderCalls` — 전부 `List<String>`

**구현자가 코드를 보고 맞춰야 하는 것** (계획이 단정하지 않는 것):

| 확인할 것 | 어디 |
|---|---|
| `FakeTupleWriter` 의 기록 필드명이 `written`/`deleted` 인가 | Task 2 Step 1 |
| `FakeTupleChecker` 가 "이미 있는 튜플" 을 흉내내는 방식 | Task 2 Step 1 |
| `DynamoDbDirectoryStateRepositoryTest` 의 저장소 필드명·준비 방식 | Task 1 Step 1 |
| `LdapInterruptedSyncScaleTest` 안 구현체의 위임 필드명 | Task 1 Step 7 |
| `byUserId(Set<DirectoryUser>)` 의 존재 | Task 2 Step 5 |

이들은 전부 **기존 파일을 열면 바로 보이는 것**이며, 어느 것도 설계 판단을 요구하지 않는다.
