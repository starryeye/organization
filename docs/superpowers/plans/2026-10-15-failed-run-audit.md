# 실패한 회차·요청의 변경 이력 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실패하거나 중단된 회차·SCIM 요청이 그 사이 실제로 한 변경(쓴·지운 튜플, 바꾼 조직도)도 변경 이력에 남긴다(운영 견고성 ②-b).

**Architecture:**
- 포트 둘이 쓰기 하나가 끝날 때마다 알린다 — OpenFGA 쓰기는 묶음마다, 전체 교체(`replaceWith`)는 직원·조직 하나마다.
- 회차는 `SyncJobs` 가 연 변경 모음(`RunAudit`)에, SCIM 요청은 요청의 모음(`AuditChanges`)에 즉시 더한다.
- 회차·요청이 값·오류·취소 무엇으로 끝나든 락 안에서 끝난 시각을 잡고, 반납한 뒤 한 곳(`SyncJobs`·`withLock`)에서 남긴다.
- 유스케이스는 이력을 직접 쓰지 않는다 — `audit.now()`·`recordRun` 호출과 이력 줄 조립이 빠진다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor 3.7, AWS SDK v2 DynamoDB(Async), OpenFGA, JUnit 5, AssertJ, Awaitility, Testcontainers(DynamoDB Local).

**Spec:** `docs/superpowers/specs/2026-10-15-failed-run-audit-design.md`

## Global Constraints

- **이력 규칙은 그대로다(설계 §5).** 무엇을 남기나는 바뀌지 않고, 언제 더하나만 바뀐다.
  - LDAP 동기화: 쓴·지운 튜플과 조직도 변경.
  - 장부 맞추기·LDAP 재적재: 지운 튜플과 조직도 변경. SCIM `tuples`: 지운 튜플만. 쓴 튜플은 남기지 않는다(중복 무시로 써 이미 있던 줄과 가를 수 없다).
  - SCIM `wipe`: 지운 튜플과 지운 직원·조직(남은 대상 포함).
  - SCIM 요청: 지금의 커밋 자리들이 더하는 것 + OpenFGA 에 쓴·지운 튜플.
- **이력 저장 형식·테이블·GSI·OpenFGA 모델은 바뀌지 않는다.** 새 의존성이 없다.
- **시각.** 락 안의 일이 끝난 때(값·오류·취소 무엇이든, 반납 전)다. `AuditRecorder.now()` 로 잡는다. 회차의 모든 줄은 시각이 하나다.
- **순서.**
  - 회차(`SyncJobs`): 시각 → 반납 → 반납 뒤 할 일(결론) → `AuditRecorder.recordRun` → `runs.finish`.
  - SCIM 요청(`withLock`): 반납 → 차례 넘김 → 이력 → 응답. 응답(503 등)은 바뀌지 않는다.
- **빈 모음은 쓰지 않는다.** `AuditRecorder.record` 가 이미 그렇다.
- **이력 쓰기 실패는 회차·요청을 실패시키지 않는다.** 지금처럼 오류 로그·지표 `audit.write.failures`·회차 메시지 앞 `감사 기록 N건 실패` 로 알린다.
- **알림은 여러 스레드에서 온다.** `AuditChanges` 는 이미 `synchronized` 다. 테스트가 알림을 모을 때는 `CopyOnWriteArrayList` 를 쓴다.
- **포트 모양.** 두 포트 모두 알림을 받는 2-인자 메서드가 추상이고, 지금의 1-인자 메서드는 알림 없이 그것을 부르는 `default` 다.
  - `RelationTupleWriter.apply(TupleDelta, Consumer<TupleWriteResult> 묶음마다)`
  - `DirectoryStateRepository.replaceWith(DirectorySnapshot, DirectoryChanges.Listener 변경마다)`
- **테스트.**
  - 새 테스트는 `// given`·`// when`·`// then` 표지를 단다(합친 `// given, when`·`// when, then` 도 된다). `@DisplayName` 은 한국어 문장이다.
  - 서브에이전트는 해당 모듈 테스트만 돌린다(`./gradlew :core:test` 등). 전체 `test`·`scaleTest` 는 컨트롤러가 돌린다. Gradle 은 한 번에 하나만 돌린다.
- **git.** `git stash` 를 쓰지 않는다. 커밋 메시지 끝에 `Co-Authored-By:` 줄을 단다.

## 계획에서 정한 것 (설계가 정하지 않았거나 고친 것)

1. **OpenFGA 는 묶음의 응답이 오는 자리에서 곧바로 알린다.** 설계 §4 는 "결과를 모으는 자리(`ResultAccumulator`)" 라 했다. 그런데 그 자리는 `flatMapSequential` 이 보낸 순서를 지키느라 먼저 끝난 묶음을 붙들어 둔다. 그 사이 취소되면 끝난 묶음을 놓친다. 설계 §4 문구는 Task 8 에서 고친다.
2. **끝단 테스트는 L15-a 다.** 설계 §10 의 끝단 행은 "스냅샷 저장이 실패해도 … 다음 회차는 변경 없음" 이라 했다. 그런데 다음 회차가 "변경 없음" 인 것은 스냅샷을 저장한 **뒤** 현재상태 교체가 실패한 경우다(L15-a·L15-b). 스냅샷 저장이 실패하면 기준선이 옛것이라 다음 회차가 다시 쓴다. 그래서 L15-a 에 이력 단언을 더하고, 설계 §10 문구는 Task 8 에서 고친다. 스냅샷 저장 실패는 core 테스트(Task 5)가 본다.
3. **가짜 OpenFGA 는 델타 전체를 묶음 하나로 보고, 반영하자마자 알린 뒤 결과를 낸다.** `delay` 가 있으면 알린 뒤 늦게 낸다. 그래서 "묶음은 끝났는데 전체 응답이 늦는 사이 취소" 를 흉내 낼 수 있다.
4. **가짜 저장소의 `replaceWith` 는 실제 저장소의 순서로 하나씩 바꾸고 알린다.** 순서는 직원 쓰기 → 조직 쓰기 → 사라진 조직 지우기 → 사라진 직원 지우기다. `failReplaceWithAfter` 로 도중 실패를 흉내 낸다.
5. **`TupleReconciler` 에는 6-인자 오버로드 하나만 더한다.** 같은 자리 수에 람다 모양만 다른 오버로드(4-인자 `DeleteCheck` 와 4-인자 `Consumer`)를 두지 않는다.
6. **`SyncJobs` 의 `Function<SyncRun, …>` 오버로드는 마지막 사용처가 옮긴 뒤(Task 6) 없앤다.** 이력이 없는 작업의 `Mono` 오버로드·`startLocked` 는 그대로다.
7. **`AuditChanges.directory(DirectoryChanges)` 는 남긴다.** `storage-dynamodb` 의 `AuditLogScaleTest` 가 쓴다.

## Review Focus

1. **동시에 보낸 OpenFGA 묶음 중 뒤 묶음이 먼저 끝나고, 앞 묶음을 기다리는 사이 취소된다.** 끝난 뒤 묶음은 이력에 남아야 한다. → Task 3 `먼저_끝난_묶음은_순서를_기다리지_않고_알린다`.
2. **회차가 기한·리스 상실로 멈춘다.** 이력의 시각은 반납 뒤가 아니라 락 안에서 끝난 때여야 한다. → Task 4 `기한으로_멈춘_회차의_시각도_반납_전이다`.
3. **조직의 삭제가 먼저 오고 그 조직의 "멤버만 빠짐" 이 뒤에 온다.** 여전히 삭제 줄이어야 한다(설계는 반대 순서만 말한다). → Task 1 `삭제_뒤의_멤버만_빠짐도_삭제_줄이다`.
4. **아무것도 모으지 못하고 실패한 회차.** 빈 이력 사건을 쓰면 안 된다. → Task 4 `아무것도_모으지_못하고_실패하면_쓰지_않는다`.
5. **락을 잡지 못한 SCIM 요청(503).** 이력을 쓰지 않고, 시각이 없다고 터지지도 않아야 한다. → Task 7 `락을_잡지_못하면_남기지_않는다`.

---

### Task 1: 변경 모음의 규칙 — 이름표가 그대로인 조직 결과는 상태를 보지 않는다, 전체 교체의 알림을 받는다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/DirectoryChanges.java` (중첩 인터페이스 `Listener`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/audit/AuditChanges.java` (`group` 규칙, `directoryListener()`)
- Test: `core/src/test/java/dev/starryeye/organization/core/audit/AuditChangesTest.java`

**Interfaces:**
- Produces:
  - `DirectoryChanges.Listener` — `void user(DirectoryChanges.UserDiff diff)`, `void group(DirectoryChanges.GroupDiff diff)`, 상수 `Listener NONE`.
  - `AuditChanges.directoryListener()` → `DirectoryChanges.Listener` — 받은 것을 `user(before, after)`·`group(before, after, added, removed)` 로 더한다.
  - `AuditChanges.group(before, after, added, removed)` — 이름표 속성이 같으면(둘 다 null 이 아님) 멤버 변화만 적는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`AuditChangesTest` 끝(`전체_교체_결과` 뒤)에 더한다:

```java
    @Test
    @DisplayName("이름표가 그대로인 결과 뒤에 그 조직의 삭제가 오면 삭제 줄에 지우기 전 이름표가 남는다")
    void 멤버만_빠짐_뒤의_삭제는_이름표를_남긴다() {
        // given — 전체 교체가 GSI 밖 조직의 "멤버만 빠짐"(이름표를 모름)을 먼저 알리고, 그 조직의 삭제가 뒤에 온다
        GroupHeader 모르는_이름표 = new GroupHeader("TEAM", null, null);

        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(모르는_이름표, 모르는_이름표, Set.of(), Set.of(김))
                .group(이름표("TEAM", "옛팀"), null, Set.of(), Set.of(이))
                .entries();

        // then
        AuditEntry 팀의_줄 = 줄(entries, 팀);
        assertThat(팀의_줄.deleted()).isTrue();
        assertThat(팀의_줄.attributes()).containsExactly(
                new AttributeChange("externalId", "cn=TEAM", null),
                new AttributeChange("displayName", "옛팀", null));
        assertThat(팀의_줄.membersRemoved()).containsExactly(김, 이);
    }

    @Test
    @DisplayName("삭제가 먼저 오고 이름표가 그대로인 결과가 뒤에 와도 삭제 줄이다")
    void 삭제_뒤의_멤버만_빠짐도_삭제_줄이다() {
        // given
        GroupHeader 옛팀 = 이름표("TEAM", "옛팀");

        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(옛팀, null, Set.of(), Set.of(이))
                .group(옛팀, 옛팀, Set.of(), Set.of(김))
                .entries();

        // then
        AuditEntry 팀의_줄 = 줄(entries, 팀);
        assertThat(팀의_줄.deleted()).isTrue();
        assertThat(팀의_줄.attributes()).containsExactly(
                new AttributeChange("externalId", "cn=TEAM", null),
                new AttributeChange("displayName", "옛팀", null));
        assertThat(팀의_줄.membersRemoved()).containsExactly(김, 이);
    }

    @Test
    @DisplayName("이름표가 그대로인 결과 하나만 오면 지금과 같다 — 생성·삭제·속성 없이 멤버 변화만 적는다")
    void 이름표가_그대로인_결과_하나는_멤버만_적는다() {
        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(이름표("DEV", "개발"), 이름표("DEV", "개발"), Set.of(김), Set.of())
                .entries();

        // then
        AuditEntry 개발의_줄 = 줄(entries, 개발);
        assertThat(개발의_줄.created()).isFalse();
        assertThat(개발의_줄.deleted()).isFalse();
        assertThat(개발의_줄.attributes()).isEmpty();
        assertThat(개발의_줄.membersAdded()).containsExactly(김);
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
    }

    @Test
    @DisplayName("전체 교체가 변경마다 알리는 것을 받아 같은 규칙으로 적는다")
    void 전체_교체의_알림을_받는다() {
        // given
        AuditChanges 모음 = new AuditChanges();
        DirectoryChanges.Listener 듣는다 = 모음.directoryListener();

        // when
        듣는다.user(new DirectoryChanges.UserDiff(null, 직원("kim")));
        듣는다.group(new DirectoryChanges.GroupDiff(null, 이름표("DEV", "개발본부"), Set.of(김), Set.of()));

        // then
        List<AuditEntry> entries = 모음.entries();
        assertThat(줄(entries, 김).created()).isTrue();
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
        assertThat(줄(entries, 개발).created()).isTrue();
        assertThat(줄(entries, 개발).membersAdded()).containsExactly(김);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*AuditChangesTest*'`
Expected: 컴파일 실패 — `DirectoryChanges.Listener`·`directoryListener()` 가 없다. (컴파일되게 두 줄만 임시로 막으면 앞의 두 테스트가 `deleted` 또는 `attributes` 단언에서 실패한다.)

- [ ] **Step 3: `DirectoryChanges.Listener` 를 더한다**

`DirectoryChanges` 레코드 안, `GroupDiff` 뒤에 더한다:

```java
    /**
     * 전체 교체가 직원·조직 하나의 저장·삭제를 마칠 때마다 듣는다(설계 2026-10-15 §4). 여러 스레드에서 불릴 수 있다.
     * 같은 조직이 두 번 올 수 있다 — 이름표가 그대로인 "멤버만 빠짐" 뒤에 그 조직의 삭제가 오는 식이다.
     */
    public interface Listener {

        /** 아무것도 듣지 않는다. */
        Listener NONE = new Listener() {
            @Override
            public void user(UserDiff diff) {
            }

            @Override
            public void group(GroupDiff diff) {
            }
        };

        void user(UserDiff diff);

        void group(GroupDiff diff);
    }
```

- [ ] **Step 4: `AuditChanges` 의 규칙과 `directoryListener()` 를 고친다**

`group(...)` 을 바꾼다:

```java
    /**
     * 조직 하나의 이름표 이전·이후와 들어오고 나간 멤버. 이름표가 null 인 쪽이 생성·삭제를 뜻한다.
     *
     * <p><b>이전과 이후의 이름표가 같으면 멤버 변화만 적고 상태는 보지 않는다(설계 2026-10-15 §5).</b> 변경을 끝나는 즉시 더하면 전체 교체가 GSI 밖
     * 조직에서 멤버를 뺄 때 내는 "멤버만 빠짐"(이름표를 모를 수 있다)이 그 조직의 삭제보다 먼저 온다. 상태를 보면 처음 본 이전을 붙들어 삭제 줄의 이름표가
     * 사라진다. 이름표가 같으면 속성 변화가 없으므로 한 번만 오는 경우의 결과는 전과 같다.
     */
    public synchronized AuditChanges group(GroupHeader before, GroupHeader after,
                                           Set<MemberRef> added, Set<MemberRef> removed) {
        String id = after != null ? after.id() : before.id();
        boolean 이름표가_그대로다 = before != null && after != null && 속성(before).equals(속성(after));
        if (!이름표가_그대로다) {
            대상(MemberRef.group(id)).상태를_본다(속성(before), before == null, 속성(after), after == null);
        }
        멤버를_적는다(id, added, removed);
        return this;
    }
```

`directory(...)` 바로 뒤에 더한다:

```java
    /** 전체 교체가 변경마다 알리는 것을 이 모음에 더한다(설계 2026-10-15 §4). 회차가 도중에 실패해도 끝난 변경은 이미 들어 있다. */
    public DirectoryChanges.Listener directoryListener() {
        return new DirectoryChanges.Listener() {
            @Override
            public void user(DirectoryChanges.UserDiff diff) {
                AuditChanges.this.user(diff.before(), diff.after());
            }

            @Override
            public void group(DirectoryChanges.GroupDiff diff) {
                AuditChanges.this.group(diff.before(), diff.after(), diff.added(), diff.removed());
            }
        };
    }
```

클래스 자바독의 목록에 한 줄을 더한다: `<li>이름표가 그대로인 조직 결과는 멤버 변화만 적는다 — 상태(생성·삭제·속성)는 이름표가 바뀐 결과로만 본다(설계 2026-10-15 §5).</li>`

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test --tests '*AuditChangesTest*'`
Expected: PASS. 이어서 `./gradlew :core:test` 도 PASS(규칙은 한 번만 오는 결과를 바꾸지 않는다).

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/DirectoryChanges.java core/src/main/java/dev/starryeye/organization/core/audit/AuditChanges.java core/src/test/java/dev/starryeye/organization/core/audit/AuditChangesTest.java
git commit -m "feat: 변경 모음이 이름표가 그대로인 조직 결과의 상태를 보지 않는다 — 끝나는 즉시 더해도 뒤에 온 삭제가 이름표를 남긴다. 전체 교체의 변경마다 알림을 받는 Listener 를 더한다(운영 견고성 ②-b)"
```

---

### Task 2: 전체 교체(`replaceWith`)가 변경마다 알린다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` (`replaceWith` 두 개)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`replaceWith` 2-인자, `failReplaceWithAfter`)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java:706` 근처 (`replaceWith` 2-인자)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java:111` (`열거가_늦은_저장소.replaceWith`)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java:183` (`한번만_실패하는_상태저장소.replaceWith`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: `DirectoryChanges.Listener` (Task 1).
- Produces:
  - `DirectoryStateRepository.replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다)` — 추상. 직원·조직 하나의 저장·삭제가 끝날 때마다 알리고, 다 끝나면 알린 것 전부를 `DirectoryChanges` 로 돌려준다.
  - `DirectoryStateRepository.replaceWith(DirectorySnapshot snapshot)` — `default`, `replaceWith(snapshot, DirectoryChanges.Listener.NONE)`.
  - `FakeStateRepository.failReplaceWithAfter` (`public volatile int`, 기본 `-1`) — 변경을 이만큼 마친 뒤 남은 변경이 있으면 `IllegalStateException("replaceWith 도중 실패(테스트)")` 로 끝난다. 0 이면 아무것도 바꾸지 않고 실패한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다 (DynamoDB Local)**

`DynamoDbDirectoryStateRepositoryTest` 의 "전체 교체의 비교 결과" 절 끝에 더한다. `java.util.concurrent.CopyOnWriteArrayList` import 를 더한다.

```java
    /** 받은 알림을 모은다. 저장소가 단계 안에서 동시에 알리므로 스레드 안전한 목록이다. */
    private static final class 받은_알림 implements DirectoryChanges.Listener {

        final List<DirectoryChanges.UserDiff> 직원 = new CopyOnWriteArrayList<>();
        final List<DirectoryChanges.GroupDiff> 조직 = new CopyOnWriteArrayList<>();

        @Override
        public void user(DirectoryChanges.UserDiff diff) {
            직원.add(diff);
        }

        @Override
        public void group(DirectoryChanges.GroupDiff diff) {
            조직.add(diff);
        }
    }

    @Test
    @DisplayName("전체 교체는 바뀐 직원·조직마다 알리고, 알린 것 전부가 돌려준 결과다")
    void 전체_교체는_변경마다_알린다() {
        // given
        repository.replaceWith(new DirectorySnapshot(
                Map.of("kim", 직원("kim"), "lee", 직원("lee")),
                Map.of("DEV", 조직("DEV", "개발", MemberRef.user("kim")), "OLD", 조직("OLD", "옛 조직")))).block();
        받은_알림 알림 = new 받은_알림();

        // when — kim 은 바뀌고 lee 는 사라진다. DEV 는 바뀌고 OLD 는 사라진다
        DirectoryChanges changes = repository.replaceWith(new DirectorySnapshot(
                Map.of("kim", 직원("kim").withDisplayName("김철수")),
                Map.of("DEV", 조직("DEV", "개발본부", MemberRef.user("kim")))), 알림).block();

        // then
        assertThat(알림.직원).hasSize(2).containsExactlyInAnyOrderElementsOf(changes.users());
        assertThat(알림.조직).hasSize(2).containsExactlyInAnyOrderElementsOf(changes.groups());
    }

    @Test
    @DisplayName("전체 교체가 도중에 실패하면 그때까지 알린 변경과 테이블이 같다 — 알리지 않은 변경은 쓰이지 않았다")
    void 전체_교체가_도중에_실패하면_알린_것과_테이블이_같다() {
        // given — 사라질 조직 OLD 가 있다. 직원을 쓰는 단계가 끝난 뒤, 조직을 지우는 단계에서 DynamoDB 가 실패한다
        repository.saveGroup(조직("OLD", "옛 조직")).block();
        DynamoDbAsyncClient 지우기에_실패하는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("deleteItem") || method.getName().equals("batchWriteItem")) {
                        return CompletableFuture.failedFuture(new IllegalStateException(method.getName() + " 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
        var 끊기는_저장소 = new DynamoDbDirectoryStateRepository(지우기에_실패하는_클라이언트, properties, clock);
        받은_알림 알림 = new 받은_알림();

        // when
        assertThatThrownBy(() -> 끊기는_저장소.replaceWith(
                new DirectorySnapshot(Map.of("kim", 직원("kim")), Map.of()), 알림).block());

        // then — kim 은 쓰였고 알렸다. OLD 는 지우지 못했고 알리지 않았다
        assertThat(알림.직원).containsExactly(new DirectoryChanges.UserDiff(null, 직원("kim")));
        assertThat(알림.조직).isEmpty();
        assertThat(repository.findUser("kim").block()).isNotNull();
        assertThat(repository.findGroup("OLD").block()).isNotNull();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`
Expected: 컴파일 실패 — `replaceWith(DirectorySnapshot, DirectoryChanges.Listener)` 가 없다.

- [ ] **Step 3: 포트를 고친다**

`DirectoryStateRepository` 의 `Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot);` 를 둘로 바꾼다(기존 자바독은 1-인자 쪽에 그대로 둔다):

```java
    default Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot) {
        return replaceWith(snapshot, DirectoryChanges.Listener.NONE);
    }

    /**
     * {@link #replaceWith(DirectorySnapshot)} 와 같되, 직원·조직 하나의 저장·삭제가 끝날 때마다 그 변경으로 {@code 변경마다} 를 부른다(설계 2026-10-15
     * §4). 도중에 실패해도 끝난 변경은 이미 알린 뒤다 — 실패한 회차의 조직도 변경도 이력에 남는다. 직원·조직 하나의 저장이 도중에 실패하면 이미 쓴 그 일부는
     * 알리지 않는다(§8). 다 끝나면 알린 것 전부를 돌려준다. 여러 스레드에서 부를 수 있다.
     */
    Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다);
```

- [ ] **Step 4: DynamoDB 구현을 고친다**

`DynamoDbDirectoryStateRepository.replaceWith(DirectorySnapshot)` 를 2-인자로 바꾼다(1-인자 `@Override` 는 지운다). 자바독 끝에 문단을 더한다:

```java
     * <p><b>변경마다 알린다(설계 2026-10-15 §4).</b> 직원·조직 하나의 저장·삭제가 끝날 때마다 그 비교 결과로 {@code 변경마다} 를 부른다 — 도중에 실패해도
     * 끝난 변경은 알린 뒤다. 단계 안에서는 동시에 쓰므로 여러 스레드에서 부른다. 직원·조직 하나의 저장이 도중에 실패하면 이미 쓴 그 일부는 알리지 않는다.
```

본문은 큐에 더하던 네 자리를 "더하고 알리기" 로 바꾼다:

```java
    @Override
    public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
        return Mono.zip(
                        storedIndex(Keys.USER_INDEX, this::storedUser, DirectoryUser::id),
                        storedIndex(Keys.GROUP_INDEX, this::storedGroup, GroupHeader::id))
                .flatMap(stored -> {
                    Map<String, Stored<DirectoryUser>> users = stored.getT1();
                    Map<String, Stored<GroupHeader>> groups = stored.getT2();
                    Queue<DirectoryChanges.UserDiff> 직원변경 = new ConcurrentLinkedQueue<>();
                    Queue<DirectoryChanges.GroupDiff> 조직변경 = new ConcurrentLinkedQueue<>();
                    // 끝난 변경은 곧바로 알리고, 돌려줄 결과에도 담는다
                    Consumer<DirectoryChanges.UserDiff> 직원이_끝났다 = diff -> {
                        직원변경.add(diff);
                        변경마다.user(diff);
                    };
                    Consumer<DirectoryChanges.GroupDiff> 조직이_끝났다 = diff -> {
                        조직변경.add(diff);
                        변경마다.group(diff);
                    };

                    Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                            .flatMap(user -> writeUser(user, users.get(user.id())), QUERY_CONCURRENCY)
                            .doOnNext(직원이_끝났다)
                            .then();
                    Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                            .flatMap(group -> writeGroup(group, groups.get(group.id())), QUERY_CONCURRENCY)
                            .doOnNext(조직이_끝났다)
                            .then();
                    // (지금의 두 단계 주석 그대로)
                    Mono<Void> removeStaleGroups = Flux.fromIterable(groups.values())
                            .filter(group -> !snapshot.groups().containsKey(group.value().id()))
                            .flatMap(group -> findMemberRefs(group.value().id()).collect(Collectors.toSet())
                                    .map(members -> new DirectoryChanges.GroupDiff(group.value(), null, Set.of(), members)),
                                    QUERY_CONCURRENCY)
                            .collectList()
                            .flatMapMany(Flux::fromIterable)
                            .flatMap(diff -> deleteGroup(diff.id(), diff.removed())
                                    .doOnSuccess(ignored -> 조직이_끝났다.accept(diff)), QUERY_CONCURRENCY)
                            .then();
                    Mono<Void> removeStaleUsers = Flux.fromIterable(users.values())
                            .filter(user -> !snapshot.users().containsKey(user.value().id()))
                            .flatMap(user -> 직원을_지운다(user.value().id())
                                    .doOnNext(멤버였던_조직 -> {
                                        직원이_끝났다.accept(new DirectoryChanges.UserDiff(user.value(), null));
                                        멤버였던_조직.forEach(groupId -> 조직이_끝났다.accept(
                                                멤버만_빠짐(groupId, user.value().id(), snapshot, groups)));
                                    }), QUERY_CONCURRENCY)
                            .then();

                    return upsertUsers.then(upsertGroups).then(removeStaleGroups).then(removeStaleUsers)
                            .then(Mono.fromSupplier(() -> new DirectoryChanges(List.copyOf(직원변경), List.copyOf(조직변경))));
                });
    }
```

`java.util.function.Consumer` import 가 없으면 더한다.

- [ ] **Step 5: 가짜 저장소를 고친다**

`FakeStateRepository` 의 `replaceWith(DirectorySnapshot)` 를 지우고 아래로 바꾼다. 필드 영역(`failReleaseUnique` 근처)에 손잡이를 더한다:

```java
    /**
     * 설정하면 {@link #replaceWith(DirectorySnapshot, DirectoryChanges.Listener)} 가 변경을 이만큼 마친 뒤, 남은 변경이 있으면
     * {@code IllegalStateException} 으로 끝난다 — 도중에 끊긴 전체 교체를 흉내 낸다. 0 이면 아무것도 바꾸지 않고 실패한다. 음수면 실패하지 않는다.
     */
    public volatile int failReplaceWithAfter = -1;
```

```java
    /**
     * 저장소와 같은 종류의 비교 결과를 돌려준다(설계 2026-10-10 §4.3) — 바뀐 직원·조직, 들어오고 나간 멤버, 사라진 것. 다만 사라진 직원을 지우며 그
     * 직원을 아직 멤버로 적은 조직의 멤버 줄까지 지우는 연쇄(실제 저장소의 "멤버만 빠짐")는 흉내 내지 않는다 — 그 결과는 저장소 테스트가 본다.
     *
     * <p>실제 저장소의 순서(직원 쓰기 → 조직 쓰기 → 사라진 조직 지우기 → 사라진 직원 지우기)로 하나씩 바꾸고, 바꿀 때마다 {@code 변경마다} 로 알린다
     * (설계 2026-10-15 §4). {@link #failReplaceWithAfter} 로 도중에 끊을 수 있다 — 그러면 그때까지 바꾼 것만 남는다.
     */
    @Override
    public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
        return Mono.defer(() -> {
            List<DirectoryChanges.UserDiff> 직원변경 = new ArrayList<>();
            List<DirectoryChanges.GroupDiff> 조직변경 = new ArrayList<>();
            List<Runnable> 차례 = new ArrayList<>();
            snapshot.users().values().forEach(user -> {
                DirectoryUser before = users.get(user.id());
                if (!user.equals(before)) {
                    DirectoryChanges.UserDiff diff = new DirectoryChanges.UserDiff(before, user);
                    차례.add(() -> {
                        users.put(user.id(), user);
                        직원변경.add(diff);
                        변경마다.user(diff);
                    });
                }
            });
            snapshot.groups().values().forEach(group -> {
                DirectoryGroup before = groups.get(group.id());
                Set<MemberRef> added = new LinkedHashSet<>(group.members());
                Set<MemberRef> removed = new LinkedHashSet<>();
                if (before != null) {
                    added.removeAll(before.members());
                    removed.addAll(before.members());
                    removed.removeAll(group.members());
                }
                GroupHeader 이전 = before == null ? null : 이름표(before);
                GroupHeader 이후 = 이름표(group);
                if (before == null || !이후.equals(이전) || !added.isEmpty() || !removed.isEmpty()) {
                    DirectoryChanges.GroupDiff diff = new DirectoryChanges.GroupDiff(이전, 이후, added, removed);
                    차례.add(() -> {
                        groups.put(group.id(), group);
                        조직변경.add(diff);
                        변경마다.group(diff);
                    });
                }
            });
            groups.values().stream()
                    .filter(group -> !snapshot.groups().containsKey(group.id()))
                    .forEach(group -> {
                        DirectoryChanges.GroupDiff diff =
                                new DirectoryChanges.GroupDiff(이름표(group), null, Set.of(), group.members());
                        차례.add(() -> {
                            groups.remove(group.id());
                            조직변경.add(diff);
                            변경마다.group(diff);
                        });
                    });
            users.values().stream()
                    .filter(user -> !snapshot.users().containsKey(user.id()))
                    .forEach(user -> {
                        DirectoryChanges.UserDiff diff = new DirectoryChanges.UserDiff(user, null);
                        차례.add(() -> {
                            users.remove(user.id());
                            직원변경.add(diff);
                            변경마다.user(diff);
                        });
                    });

            for (int i = 0; i < 차례.size(); i++) {
                if (i == failReplaceWithAfter) {
                    return Mono.error(new IllegalStateException("replaceWith 도중 실패(테스트)"));
                }
                차례.get(i).run();
            }
            // 다 마쳤으면 조직도를 통째로 맞춘다 — 바뀌지 않은 직원·조직의 순서까지 전과 같게 둔다
            users.clear();
            groups.clear();
            users.putAll(snapshot.users());
            groups.putAll(snapshot.groups());
            return Mono.just(new DirectoryChanges(직원변경, 조직변경));
        });
    }
```

- [ ] **Step 6: 두 테스트 저장소를 고친다**

`ScimRebuildIndexLagTest.열거가_늦은_저장소.replaceWith(DirectorySnapshot)` 를 2-인자로 바꾼다. 바뀐 곳은 `super.replaceWith(snapshot, 변경마다)` 와 "멤버만 빠짐" 결과를 알리는 줄뿐이다:

```java
        /**
         * 실제 저장소처럼 숨긴 대상은 열거되지 않아 비교·삭제에서 빠지고, 끝난 뒤에도 남는다. 보이는 직원을 지우면 실제 저장소(직원을_지운다)처럼 그 직원의
         * 멤버 줄이 있던 조직의 멤버 줄도 지우고, 그 조직마다 이름표가 그대로인 "멤버만 빠짐" 비교 결과를 더한다 — 숨은 조직은 저장본에 없어 이름표가 비어 있다.
         * "멤버만 빠짐" 도 실제 저장소처럼 그 직원을 지운 뒤에 알린다 — 그 조직의 삭제는 나중에(wipe 의 남은 대상 지우기) 온다.
         */
        @Override
        public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
            Map<String, DirectoryUser> 숨은_직원 = new LinkedHashMap<>(users);
            숨은_직원.keySet().retainAll(숨긴_직원);
            Map<String, DirectoryGroup> 숨은_조직 = new LinkedHashMap<>(groups);
            숨은_조직.keySet().retainAll(숨긴_조직);
            users.keySet().removeAll(숨긴_직원);
            groups.keySet().removeAll(숨긴_조직);
            DirectoryChanges changes = super.replaceWith(snapshot, 변경마다).block();
            List<DirectoryChanges.GroupDiff> 조직변경 = new ArrayList<>(changes.groups());
            for (DirectoryChanges.UserDiff diff : changes.users()) {
                if (diff.after() != null) {
                    continue;
                }
                MemberRef 지운_직원 = MemberRef.user(diff.id());
                for (Map.Entry<String, DirectoryGroup> hidden : 숨은_조직.entrySet()) {
                    DirectoryGroup group = hidden.getValue();
                    if (!group.members().contains(지운_직원)) {
                        continue;
                    }
                    Set<MemberRef> 남은_멤버 = new LinkedHashSet<>(group.members());
                    남은_멤버.remove(지운_직원);
                    hidden.setValue(new DirectoryGroup(group.id(), group.externalId(), group.displayName(), 남은_멤버));
                    GroupHeader 이름표 = new GroupHeader(group.id(), null, null);
                    DirectoryChanges.GroupDiff 멤버만_빠짐 =
                            new DirectoryChanges.GroupDiff(이름표, 이름표, Set.of(), Set.of(지운_직원));
                    조직변경.add(멤버만_빠짐);
                    변경마다.group(멤버만_빠짐);
                }
            }
            users.putAll(숨은_직원);
            groups.putAll(숨은_조직);
            return Mono.just(new DirectoryChanges(changes.users(), 조직변경));
        }
```

`LdapInterruptedSyncScaleTest.한번만_실패하는_상태저장소` 의 `replaceWith(DirectorySnapshot)` 를 2-인자로 바꾼다:

```java
        @Override
        public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot, DirectoryChanges.Listener 변경마다) {
            if (상태저장을_끊는다.get()) {
                return Mono.error(new IllegalStateException("상태 저장 직전에 프로세스가 죽었다"));
            }
            return 실제.replaceWith(snapshot, 변경마다);
        }
```

- [ ] **Step 7: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`
Expected: PASS.
Run: `./gradlew :core:test :app-ldap:compileTestJava`
Expected: PASS(가짜의 순서가 바뀌어도 끝 상태는 전과 같다).

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -m "feat: 전체 교체(replaceWith)가 직원·조직 하나의 저장·삭제가 끝날 때마다 알린다 — 도중에 실패해도 끝난 변경은 알린 뒤다. 가짜 저장소는 실제 순서로 하나씩 바꾸고 도중 실패를 흉내 낸다(운영 견고성 ②-b)"
```

---

### Task 3: OpenFGA 쓰기가 묶음마다 알린다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/RelationTupleWriter.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java` (`apply`, `보내되_연속_실패면_멈춘다`)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java`

**Interfaces:**
- Produces:
  - `RelationTupleWriter.apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다)` — 추상. 묶음 하나가 끝날 때마다 그 묶음의 결과(실제로 쓴 줄·지운 줄·실패)로 부른다.
  - `RelationTupleWriter.apply(TupleDelta delta)` — `default`, `apply(delta, 묶음 -> { })`.
  - `OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(List<Batch>, Function<Batch, Mono<TupleWriteResult>>, int 동시, Consumer<TupleWriteResult> 묶음마다)` — 패키지 전용 static. 지금의 2·3-인자는 알림 없이 이것을 부른다.
  - `FakeTupleWriter.apply(delta, 묶음마다)` — 델타 전체를 묶음 하나로 보고, 반영하자마자 알린 뒤 결과를 낸다(`delay` 는 알린 뒤에 늦춘다, `abortWhen` 은 알린 뒤에 멈춘다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`OpenFgaRelationTupleWriterBreakerTest` 에 더한다(`reactor.core.Disposable` import 를 더한다. `Sinks`·`CopyOnWriteArrayList`·`AtomicInteger` 는 이미 있다):

```java
    @Test
    @DisplayName("차단기로 멈추면 실제로 보낸 묶음의 결과만 알린다 — 보내지 않은 묶음은 알리지 않는다")
    void 차단기로_멈추면_보낸_묶음만_알린다() {
        // given — 첫 배치는 나가고, 그 뒤로 OpenFGA 가 죽는다
        AtomicInteger 보낸_수 = new AtomicInteger();
        Function<Batch, Mono<TupleWriteResult>> 첫_배치만_성공 = batch ->
                Mono.just(보낸_수.incrementAndGet() == 1 ? batch.succeeded() : batch.failed("연결 거부"));
        List<TupleWriteResult> 알린것 = new CopyOnWriteArrayList<>();

        // when
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(
                배치들(10), 첫_배치만_성공, 1, 알린것::add).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then — 보낸 넷(성공 하나, 실패 셋)만 알렸다
        assertThat(알린것).hasSize(4);
        assertThat(알린것.stream().mapToInt(result -> result.written().size()).sum()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 보낸 묶음 중 뒤 묶음이 먼저 끝나면 곧바로 알린다 — 앞 묶음을 기다리다 취소돼도 끝난 묶음은 알린 뒤다")
    void 먼저_끝난_묶음은_순서를_기다리지_않고_알린다() {
        // given — 동시 2개. 첫 묶음은 응답이 오지 않고, 둘째 묶음은 곧바로 성공한다
        List<Batch> batches = 배치들(2);
        Sinks.One<TupleWriteResult> 오지_않는_응답 = Sinks.one();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> batch == batches.get(0)
                ? 오지_않는_응답.asMono()
                : Mono.just(batch.succeeded());
        List<TupleWriteResult> 알린것 = new CopyOnWriteArrayList<>();

        // when — 첫 묶음을 기다리는 동안 취소한다(기한·리스 상실)
        Disposable 쓰기 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(batches, send, 2, 알린것::add).subscribe();
        쓰기.dispose();

        // then — 결과를 보낸 순서대로 모으는 자리는 둘째 묶음을 붙들고 있었지만, 알림은 이미 나갔다
        assertThat(알린것).singleElement().satisfies(result ->
                assertThat(result.written()).containsExactlyInAnyOrderElementsOf(batches.get(1).writes()));
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaRelationTupleWriterBreakerTest*'`
Expected: 컴파일 실패 — 4-인자 `보내되_연속_실패면_멈춘다` 가 없다.

- [ ] **Step 3: 포트를 고친다**

`RelationTupleWriter` 를 바꾼다(`java.util.function.Consumer` import):

```java
public interface RelationTupleWriter {

    /** 알림 없이 반영한다. */
    default Mono<TupleWriteResult> apply(TupleDelta delta) {
        return apply(delta, 묶음 -> {
        });
    }

    /**
     * 델타를 반영하되, 쓰기 묶음 하나가 끝날 때마다 그 묶음의 결과(실제로 쓴 줄·지운 줄·실패)로 {@code 묶음마다} 를 부른다(설계 2026-10-15 §4). 차단기로
     * 멈추거나({@link TupleWriteAbortedException}) 도중에 취소돼도 끝난 묶음은 이미 알린 뒤다 — 실패한 회차·요청의 권한 변경도 이력에 남는다. 쓰고 응답을
     * 잃은 묶음은 반영됐는지 모르므로 알리지 않는다(§8). 여러 스레드에서 부를 수 있다.
     */
    Mono<TupleWriteResult> apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다);
}
```

- [ ] **Step 4: OpenFGA 구현을 고친다**

`apply(TupleDelta)` 의 `@Override` 를 2-인자로 바꾼다(1-인자는 포트의 `default` 가 맡는다):

```java
    /** 겹치는 델타의 거부({@link #batchesFor})도 던지지 않고 오류 신호로 돌려준다 — 구독할 때 배치를 짠다. 묶음마다 알린다(설계 2026-10-15 §4). */
    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다) {
        return Mono.defer(() -> {
            if (delta.isEmpty()) {
                return Mono.just(TupleWriteResult.empty());
            }

            List<Batch> batches = batchesFor(delta);

            return bootstrapper.resolveStore()
                    .then(보내되_연속_실패면_멈춘다(batches, this::applyBatch, properties.getRequestConcurrency(), 묶음마다));
        });
    }
```

`보내되_연속_실패면_멈춘다` 의 3-인자는 4-인자를 부르고, 4-인자가 지금의 몸을 갖는다:

```java
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send, int 동시) {
        return 보내되_연속_실패면_멈춘다(batches, send, 동시, 묶음 -> {
        });
    }

    /**
     * {@code 묶음마다} 는 실제로 보낸 요청의 응답이 오는 즉시 그 결과로 불린다(설계 2026-10-15 §4) — 보내지 않은 묶음(차단기·앞 단계 실패)은 알리지 않는다.
     * 결과를 모으는 자리({@link ResultAccumulator})가 아니라 응답 자리에서 알린다. 모으는 자리는 보낸 순서를 지키느라 먼저 끝난 묶음을 붙들어 둘 수 있어,
     * 그 사이 취소되면 끝난 묶음을 놓친다.
     */
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send, int 동시,
                                                     Consumer<TupleWriteResult> 묶음마다) {
        // (지금 3-인자의 몸 그대로) 단, 보내고_실패를_적는다 에 알림을 붙인다:
            Function<Batch, Mono<TupleWriteResult>> 보내고_실패를_적는다 = batch -> send.apply(batch)
                    .doOnNext(result -> {
                        if (result.hasFailure()) {
                            실패한_단계.add(batch.단계());
                        }
                    })
                    .doOnNext(묶음마다);
        // …
    }
```

긴 자바독(지금 3-인자 위의 것)은 4-인자 위로 옮기고, 위 문단을 그 끝에 붙인다.

- [ ] **Step 5: 가짜 OpenFGA 를 고친다**

`FakeTupleWriter.apply(TupleDelta)` 를 2-인자로 바꾼다(`java.util.function.Consumer` import). 반영 계산은 그대로이고, 결과를 내기 전에 알린다:

```java
    /**
     * 델타 전체를 묶음 하나로 보고, 반영하자마자 알린 뒤 결과를 낸다(설계 2026-10-15 §4). {@link #delay} 는 알린 뒤에 늦춘다 — 묶음은 끝났는데 전체 응답이
     * 늦는 사이 취소돼도 반영한 것은 이미 알린 뒤다. {@link #abortWhen} 에 걸리면 반영한 것을 알린 뒤 멈춘다.
     */
    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta, Consumer<TupleWriteResult> 묶음마다) {
        // (지금 apply 의 몸 그대로 — applyHook, appliedDeltas, written/deleted/failures 계산, applied 만들기)
        TupleWriteResult applied = new TupleWriteResult(written, deleted, failures);
        묶음마다.accept(applied);
        Mono<TupleWriteResult> result = abortWhen.test(delta)
                ? Mono.error(new TupleWriteAbortedException("테스트용 차단 — 연속 실패로 멈췄다", applied))
                : Mono.just(applied);
        return delay.isZero() ? result : result.delayElement(delay);
    }
```

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaRelationTupleWriter*'`
Expected: PASS.
Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/RelationTupleWriter.java authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java
git commit -m "feat: OpenFGA 쓰기가 묶음의 응답이 오는 즉시 그 결과를 알린다 — 차단기로 멈추거나 취소돼도 끝난 묶음은 알린 뒤다(운영 견고성 ②-b)"
```

---

### Task 4: `SyncJobs` 가 회차의 변경 모음을 열고, 어떻게 끝나든 남긴다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/audit/RunAudit.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java` (`syncJobs` 빈)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java` (`syncJobs` 빈)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsRunAwareWorkTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsAuditTest.java` (새 파일)

**Interfaces:**
- Consumes: `AuditRecorder.recordRun(AuditCause, Instant, List<AuditEntry>, SyncOutcome)`, `AuditRecorder.now()`, `AuditChanges`.
- Produces:
  - `RunAudit(AuditKind kind)` — `AuditChanges changes()`, `AuditKind kind()`, `void kind(AuditKind kind)`.
  - `SyncJobs(SyncRunRepository, MutationLock, Duration renewInterval, LockObserver, Duration timeout, AuditRecorder audit)` — 새 생성자. 지금의 5-인자는 `AuditRecorder.NONE` 으로 이것을 부른다.
  - `SyncJobs.startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose, AuditKind kind, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work, Consumer<SyncRun> onFinished)` — 새 오버로드. 이것으로 연 회차만 이력을 남긴다.
  - 지금의 `startLocked`·`startLockedThen(…, Mono, …)`·`startLockedThen(…, Function, …)` 은 이력이 없다(모음을 열지 않는다). `Function` 오버로드는 Task 6 에서 없앤다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SyncJobsAuditTest.java` 를 만든다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.audit.RunAudit;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 회차의 이력을 SyncJobs 가 모으고, 회차가 어떻게 끝나든 남긴다(설계 2026-10-15 §3). */
class SyncJobsAuditTest {

    private static final Instant 지금 = Instant.parse("2026-10-15T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV");

    private final FakeSyncRunRepository runs = new FakeSyncRunRepository(지금);
    private final FakeMutationLock lock = new FakeMutationLock();
    private final FakeAuditLog auditLog = new FakeAuditLog();
    private final AtomicInteger 빠진_줄_지표 = new AtomicInteger();
    private final AuditRecorder audit =
            new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, Clock.fixed(지금, ZoneOffset.UTC));

    private SyncJobs 잡스(Duration renewInterval, Duration timeout) {
        return new SyncJobs(runs, lock, renewInterval, LockObserver.NOOP, timeout, audit);
    }

    private SyncJobs 잡스() {
        return 잡스(Duration.ofSeconds(10), Duration.ofMinutes(1));
    }

    private SyncRun 건다(SyncJobs jobs, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work) {
        SyncRun started = jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                AuditKind.SYNC, work, run -> {
                }).block();
        return runs.awaitFinished(started.runId());
    }

    /** 김에게 권한 하나를 준 쓰기가 끝났다. */
    private static void 김에게_줬다(RunAudit 기록) {
        기록.changes().tuples(new TupleWriteResult(Set.of(김_개발), Set.of(), List.of()));
    }

    @Test
    @DisplayName("값으로 끝난 회차는 모은 것을 회차 id 와 종류로, 락 안에서 끝난 시각에 남긴다")
    void 값으로_끝나면_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.cause()).isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId()));
            assertThat(event.at()).isEqualTo(지금);
        });
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("모은 것이 없으면 쓰지 않는다")
    void 빈_모음은_쓰지_않는다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.just(Mono.just(SyncOutcome.noChange())));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("아무것도 모으지 못하고 실패한 회차는 쓰지 않는다")
    void 아무것도_모으지_못하고_실패하면_쓰지_않는다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.error(new IllegalStateException("LDAP 읽기 실패")));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("락 안의 일이 오류로 끝나도 그때까지 모은 것을 FAILED 회차로 남긴다")
    void 오류로_끝나도_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>error(new IllegalStateException("현재상태 교체 실패"));
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("현재상태 교체 실패");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("반납 뒤 할 일이 실패해도 모은 것을 남긴다")
    void 반납_뒤_할_일이_실패해도_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.<SyncOutcome>error(new IllegalStateException("스냅샷 저장 실패"));
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("스냅샷 저장 실패");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("기한으로 멈춘 회차도 그때까지 모은 것을 남긴다")
    void 기한으로_멈춰도_남긴다() {
        // when
        SyncRun run = 건다(잡스(Duration.ofSeconds(10), Duration.ofMillis(200)), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).startsWith("기한 초과");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("리스를 잃어 멈춘 회차도 그때까지 모은 것을 남긴다")
    void 리스를_잃어도_남긴다() {
        // given
        lock.failRenew = true;

        // when
        SyncRun run = 건다(잡스(Duration.ofMillis(50), Duration.ofMinutes(1)), (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("서버가 내려가 멈춘 회차도 그때까지 모은 것을 남긴다 — 종료는 이력과 기록을 마칠 때까지 기다린다")
    void 서버가_내려가도_남긴다() {
        // given — 쓰기를 마치고 더 기다리는 중이다
        SyncJobs jobs = 잡스();
        AtomicBoolean 썼다 = new AtomicBoolean();
        jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (started, 기록) -> Mono.defer(() -> {
                    김에게_줬다(기록);
                    썼다.set(true);
                    return Mono.<Mono<SyncOutcome>>never();
                }), run -> {
                }).block();
        await().atMost(Duration.ofSeconds(5)).untilTrue(썼다);

        // when
        jobs.shutdown();

        // then
        assertThat(runs.finished).singleElement()
                .satisfies(run -> assertThat(run.message()).isEqualTo("서버 종료로 중단"));
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
    }

    @Test
    @DisplayName("이력의 시각은 반납 뒤가 아니라 락 안의 일이 끝난 때다")
    void 시각은_락_안의_일이_끝난_때다() {
        // given — 반납하는 순간 시계가 1분 간다. 이력은 반납 뒤에 쓴다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(지금);
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock()));

        // when
        SyncRun run = 건다(jobs, (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(지금.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(지금));
    }

    @Test
    @DisplayName("기한으로 멈춘 회차의 시각도 반납 전, 락 안에서 끝난 때다")
    void 기한으로_멈춘_회차의_시각도_반납_전이다() {
        // given
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(지금);
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMillis(200),
                new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock()));

        // when
        SyncRun run = 건다(jobs, (started, 기록) -> Mono.defer(() -> {
            김에게_줬다(기록);
            return Mono.<Mono<SyncOutcome>>never();
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(지금.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(지금));
    }

    @Test
    @DisplayName("이력 쓰기가 실패해도 회차의 상태는 그대로이고, 메시지 앞과 지표에 빠진 줄이 남는다")
    void 이력_쓰기가_실패하면_회차_메시지에_붙는다() {
        // given
        auditLog.failWith = new IllegalStateException("DynamoDB 장애");

        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).startsWith("감사 기록 1건 실패");
        assertThat(빠진_줄_지표).hasValue(1);
    }

    @Test
    @DisplayName("일이 종류를 바꾸면 그 종류로 남긴다 — 장부를 훑어 맞추는 LDAP 동기화")
    void 일이_종류를_바꾸면_그_종류로_남긴다() {
        // when
        SyncRun run = 건다(잡스(), (started, 기록) -> Mono.fromSupplier(() -> {
            기록.kind(AuditKind.LEDGER_ALIGNMENT);
            김에게_줬다(기록);
            return Mono.just(SyncOutcome.noChange());
        }));

        // then
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, run.runId())));
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsAuditTest*'`
Expected: 컴파일 실패 — `RunAudit`, 6-인자 생성자, 새 오버로드가 없다.

- [ ] **Step 3: `RunAudit` 를 만든다**

```java
package dev.starryeye.organization.core.audit;

import java.util.Objects;

/**
 * 회차 하나의 변경 모음(설계 2026-10-15 §3). {@code SyncJobs} 가 회차를 열 때 만들어 일에 넘기고, 일은 쓰기가 끝나는 즉시 {@link #changes()} 에 더한다.
 * 회차가 값·오류·취소 무엇으로 끝나든 {@code SyncJobs} 가 락을 반납한 뒤 이 모음을 종류와 함께 남긴다.
 *
 * <p>종류는 부르는 쪽이 기본값을 준다. LDAP 동기화가 장부 맞추기로 가면 일이 {@link AuditKind#LEDGER_ALIGNMENT} 로 바꾼다.
 */
public final class RunAudit {

    private final AuditChanges changes = new AuditChanges();
    private volatile AuditKind kind;

    public RunAudit(AuditKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /** 이 회차가 실제로 한 변경. 여러 스레드에서 더할 수 있다. */
    public AuditChanges changes() {
        return changes;
    }

    public AuditKind kind() {
        return kind;
    }

    /** 이 회차가 남길 종류를 바꾼다 — 기준선을 믿지 못해 장부를 훑어 맞추는 LDAP 동기화가 부른다. */
    public void kind(AuditKind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }
}
```

- [ ] **Step 4: `SyncJobs` 를 고친다**

필드와 생성자:

```java
    private final AuditRecorder audit;

    /** 이력을 남기지 않는다 — 이력과 무관한 테스트가 쓴다. 운영 결선은 이력을 받는 생성자다. */
    public SyncJobs(SyncRunRepository runs, MutationLock lock, Duration renewInterval,
                    LockObserver lockObserver, Duration timeout) {
        this(runs, lock, renewInterval, lockObserver, timeout, AuditRecorder.NONE);
    }

    /** @param audit 회차의 변경 모음을 남긴다(설계 2026-10-15 §3) — 종류({@link AuditKind})를 받는 {@code startLockedThen} 으로 연 회차만 */
    public SyncJobs(SyncRunRepository runs, MutationLock lock, Duration renewInterval,
                    LockObserver lockObserver, Duration timeout, AuditRecorder audit) {
        this.runs = runs;
        this.lock = lock;
        this.keeper = new LeaseKeeper(lock, renewInterval, lockObserver);
        this.lockObserver = lockObserver;
        this.timeout = timeout;
        this.audit = audit;
    }
```

`startLockedThen` 셋과 락 잡기:

```java
    public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                         Mono<Mono<SyncOutcome>> work, Consumer<SyncRun> onFinished) {
        return 락을_잡고_띄운다(source, trigger, purpose, null, (run, 기록) -> work, onFinished);
    }

    // (지금 자바독 그대로) — Task 6 에서 없앤다
    public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                         Function<SyncRun, Mono<Mono<SyncOutcome>>> work, Consumer<SyncRun> onFinished) {
        return 락을_잡고_띄운다(source, trigger, purpose, null, (run, 기록) -> work.apply(run), onFinished);
    }

    /**
     * 변경 이력을 남기는 회차(동기화·재적재)를 띄운다(설계 2026-10-15 §3). {@link #startLockedThen(SyncSource, SyncTrigger, MutationLock.LockPurpose, Mono, Consumer)}
     * 와 같되, 회차의 변경 모음({@link RunAudit})을 함께 연다. {@code work} 는 기록을 연 뒤 락을 쥔 채로 한 번 불리고, 연 실행 기록(회차 id 가 이력의
     * 원인이다)과 모음을 받는다. 일은 쓰기가 끝나는 즉시 모음에 더한다.
     *
     * <p>회차가 값·오류·취소(기한·서버 종료·리스 상실) 무엇으로 끝나든: 락 안의 일이 끝난 때를 이력의 시각으로 잡고 → 반납하고 → 반납 뒤 할 일로 결론을
     * 정하고 → 모음을 그 결론과 함께 남기고 → 기록을 닫는다. 빈 모음은 쓰지 않는다. 이력 쓰기가 실패해도 결론은 그대로이고 메시지 앞에
     * "감사 기록 N건 실패" 가 붙는다.
     *
     * @param kind 남길 이력의 종류 — 일이 {@link RunAudit#kind(AuditKind)} 로 바꿀 수 있다
     */
    public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                         AuditKind kind, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work,
                                         Consumer<SyncRun> onFinished) {
        return 락을_잡고_띄운다(source, trigger, purpose, Objects.requireNonNull(kind, "kind"), work, onFinished);
    }

    /** 앱의 작업 락을 잡고 띄운다. {@code kind} 가 null 이면 이력이 없는 작업이다 — 모음을 열지 않는다. */
    private Mono<SyncRun> 락을_잡고_띄운다(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                        AuditKind kind, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work,
                                        Consumer<SyncRun> onFinished) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            return lock.acquire(purpose)
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), false))
                    .flatMap(lease -> 띄운다(source, trigger, kind, work,
                            run -> 남은_기록을_닫는다(source, run.runId()),
                            일 -> keeper.keep(lease, 일, "작업 도중 리스 상실"),
                            () -> lock.release(lease),
                            onFinished));
        });
    }
```

`start(…, onFinished)` 는 `띄운다(source, trigger, null, (run, 기록) -> work.map(Mono::just), run -> Mono.empty(), UnaryOperator.identity(), release, onFinished)` 를 부른다.

`띄운다` 는 `AuditKind kind` 를 `trigger` 뒤에 받고, `work` 를 `BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>>` 로 받아 `끝까지_돌린다(run, kind, work, 먼저, 지키기, release)` 로 넘긴다. 자바독의 `@param` 에 `kind` 를 더한다: `이력의 종류. null 이면 이력이 없는 작업이다`.

`끝까지_돌린다` 와 새 `남긴다`:

```java
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, AuditKind kind,
                                    BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>> work,
                                    Function<SyncRun, Mono<Void>> 먼저,
                                    UnaryOperator<Mono<Mono<SyncOutcome>>> 지키기, Supplier<Mono<Void>> release) {
        RunAudit 기록 = kind == null ? null : new RunAudit(kind);
        AtomicReference<Instant> 끝난_때 = new AtomicReference<>();
        Mono<Mono<SyncOutcome>> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        Mono<Mono<SyncOutcome>> 일 = 지키기.apply(Mono.defer(() -> 먼저.apply(run)).then(Mono.defer(() -> work.apply(run, 기록))));
        return Mono.firstWithSignal(종료되면, 일)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> Mono.just(Mono.just(실패로(run, error))))
                // 락 안의 일이 끝난 때 — 값·오류·취소 무엇이든 반납 전이다. 이력의 시각이다(설계 2026-10-15 §3)
                .doOnNext(ignored -> 끝난_때.set(audit.now()))
                .flatMap(반납_뒤 -> 반납한다(release).thenReturn(반납_뒤))
                // 반납 뒤 할 일(설계 2026-10-02 §5) — 락 없이 돈다
                .flatMap(반납_뒤 -> 반납_뒤
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("반납 뒤 할 일이 결과 없이 끝났다")))
                        .onErrorResume(error -> Mono.just(실패로(run, error))))
                // 결론이 정해진 뒤 모은 것을 그 결론과 함께 남긴다(설계 2026-10-15 §3)
                .flatMap(outcome -> 남긴다(run, 기록, 끝난_때.get(), outcome))
                .flatMap(outcome -> runs.finish(run, outcome))
                // (지금의 doOnNext 로그·onErrorResume 그대로)
    }

    /**
     * 회차의 모음을 결론과 함께 남긴다. 이력이 없는 작업이면 결론 그대로다. 빈 모음은 {@link AuditRecorder} 가 쓰지 않는다. 이력 단계는 회차를 실패시키지
     * 않는다 — 쓰기 실패는 결론의 메시지에 붙고, 그 밖의 뜻밖의 오류는 로그만 남긴다.
     */
    private Mono<SyncOutcome> 남긴다(SyncRun run, RunAudit 기록, Instant at, SyncOutcome outcome) {
        if (기록 == null) {
            return Mono.just(outcome);
        }
        return Mono.defer(() -> audit.recordRun(AuditCause.run(run.source(), 기록.kind(), run.runId()), at,
                        기록.changes().entries(), outcome))
                .defaultIfEmpty(outcome)
                .onErrorResume(error -> {
                    log.error("[{}] 이력을 남기지 못했다 — 회차의 결론은 그대로다", run.runId(), error);
                    return Mono.just(outcome);
                });
    }
```

import: `AuditCause`, `AuditKind`, `AuditRecorder`, `RunAudit`, `java.time.Instant`, `java.util.Objects`, `java.util.concurrent.atomic.AtomicReference`, `java.util.function.BiFunction`.

클래스 자바독의 규칙 목록에 하나를 더한다: `<li>이력을 남기는 회차는 변경 모음을 연다. 어떻게 끝나든 락 안의 일이 끝난 때를 시각으로 잡고, 반납하고, 결론을 정한 뒤 모음을 결론과 함께 남기고 기록한다(설계 2026-10-15 §3).</li>`. "멈춤은 되돌리기가 아니다" 문단에 `이미 나간 쓰기는 이력에 남는다.` 를 더한다.

- [ ] **Step 5: 결선을 고친다**

`app-ldap` `UseCaseConfig.syncJobs`:

```java
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             SyncProperties properties, AuditRecorder auditRecorder) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), LockObserver.NOOP, properties.getJobTimeout(),
                auditRecorder);
    }
```

`app-scim` `ScimUseCaseConfig.syncJobs`:

```java
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             ScimSyncMetrics metrics, @Value("${sync.job-timeout:30m}") Duration jobTimeout,
                             AuditRecorder auditRecorder) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), metrics, jobTimeout, auditRecorder);
    }
```

두 빈의 자바독 끝에 `회차의 변경 이력도 여기서 남긴다(설계 2026-10-15 §3).` 를 더한다.

- [ ] **Step 6: `SyncJobsRunAwareWorkTest` 를 새 오버로드로 옮긴다**

```java
    @Test
    @DisplayName("일은 연 실행 기록과 회차의 변경 모음을 받고, 반납 뒤 할 일은 락을 반납한 뒤에 돈다")
    void 일은_연_실행_기록을_받는다() {
        // given
        AtomicReference<String> 받은_기록 = new AtomicReference<>();
        AtomicReference<RunAudit> 받은_모음 = new AtomicReference<>();
        AtomicBoolean 반납_뒤에_락을_쥠 = new AtomicBoolean(true);

        // when
        SyncRun started = jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                AuditKind.SYNC, (run, 기록) -> {
                    받은_기록.set(run.runId());
                    받은_모음.set(기록);
                    return Mono.just(Mono.fromSupplier(() -> {
                        반납_뒤에_락을_쥠.set(lock.isHeld());
                        return SyncOutcome.noChange();
                    }));
                }, run -> {
                }).block();
        SyncRun finished = runs.awaitFinished(started.runId());

        // then
        assertThat(받은_기록.get()).isEqualTo(started.runId());
        assertThat(받은_모음.get().kind()).isEqualTo(AuditKind.SYNC);
        assertThat(반납_뒤에_락을_쥠).isFalse();
        assertThat(finished.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }
```

클래스 자바독을 `일이 연 실행 기록과 변경 모음을 받는 길 — 회차 id 를 이력의 원인으로 적는다(설계 2026-10-10 §4.2, 2026-10-15 §3).` 로 바꾼다. import `AuditKind`·`RunAudit` 를 더한다.

- [ ] **Step 7: 통과를 본다**

Run: `./gradlew :core:test --tests '*SyncJobs*'`
Expected: PASS.
Run: `./gradlew :core:test :app-ldap:compileJava :app-scim:compileJava`
Expected: PASS. (유스케이스는 아직 `Function` 오버로드로 직접 이력을 쓴다 — `SyncJobs` 는 그 회차의 모음을 열지 않으므로 두 번 쓰지 않는다.)

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/audit/RunAudit.java core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsAuditTest.java core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsRunAwareWorkTest.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java
git commit -m "feat: SyncJobs 가 회차의 변경 모음(RunAudit)을 열고, 값·오류·기한·종료·리스 상실 무엇으로 끝나든 락 안에서 끝난 시각으로 결론과 함께 남긴다(운영 견고성 ②-b)"
```

---

### Task 5: LDAP 동기화·장부 맞추기·재적재가 끝나는 즉시 모은다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java` (6-인자 `reconcile`, `지운다`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java` (`failRenew` 를 `volatile` 로)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java` (`fullSyncUseCase`·`rebuildUseCase` 빈)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LdapSyncAuditTest.java`

**Interfaces:**
- Consumes: `SyncJobs.startLockedThen(…, AuditKind, BiFunction<SyncRun, RunAudit, …>, …)`, 6-인자 `SyncJobs` 생성자(Task 4); `RunAudit`(Task 4); `RelationTupleWriter.apply(delta, 묶음마다)`(Task 3); `replaceWith(snapshot, 변경마다)`·`FakeStateRepository.failReplaceWithAfter`(Task 2); `AuditChanges.directoryListener()`(Task 1).
- Produces:
  - `TupleReconciler.reconcile(RelationTupleWriter, RelationTupleScanner, Set<RelationTuple> desired, DesiredRevision, DeleteCheck, Consumer<Set<RelationTuple>> 지운_줄마다)` — 지우기 묶음이 끝날 때마다 실제로 지운 줄로 부른다. 지금의 오버로드는 알림 없이 이것을 부른다.
  - `LedgerAlignment(snapshots, state, writer, scanner, guard, clock)` — `AuditRecorder` 가 빠진다. `align(DirectorySnapshot, Set<RelationTuple>, boolean force, AuditChanges 기록)`.
  - `FullSyncUseCase`·`RebuildUseCase` 의 생성자는 8-인자 하나만 남는다(`AuditRecorder` 9-인자는 없앤다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TupleReconcilerTest` 에 더한다(`java.util.List`·`java.util.concurrent.CopyOnWriteArrayList` import):

```java
    @Test
    @DisplayName("지우기 묶음이 끝날 때마다 지운 줄을 알린다 — 차단기로 멈춰도 끝난 것은 알린 뒤다")
    void 지운_줄을_묶음마다_알린다() {
        // given — 찌꺼기를 지우는 쓰기가 차단기로 멈춘다(그 전에 반영한 것은 반영됐다)
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(찌꺼기);
        writer.abortWhen(delta -> !delta.toDelete().isEmpty());
        List<RelationTuple> 알린_줄 = new CopyOnWriteArrayList<>();

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(RelationTuple.directMember("kim", "DEV002")),
                TupleReconciler.DesiredRevision.NONE, TupleReconciler.DeleteCheck.NONE, 알린_줄::addAll).block();

        // then
        assertThat(reconciliation.stopReason()).isNotNull();
        assertThat(알린_줄).containsExactly(찌꺼기);
    }
```

`LdapSyncAuditTest` 의 준비를 바꾼다 — 이력은 `SyncJobs` 가 남긴다. 필드 `clock`·`audit`·`guard` 를 두고 `setUp` 을 아래로 바꾼다:

```java
    private Clock clock;
    private AuditRecorder audit;
    private DeletionGuard guard;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        clock = Clock.fixed(고정시각, ZoneOffset.UTC);
        audit = new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, clock);
        SyncJobs jobs = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1), audit);
        guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        sync = new FullSyncUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
        rebuild = new RebuildUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }
```

`시계가_가는_락으로_동기화한다` 도 같은 식으로 바꾼다:

```java
    private SyncRun 시계가_가는_락으로_동기화한다(ClockAdvancingLock 시계가_가는_락, SyncTrigger trigger) {
        Clock 시계 = 시계가_가는_락.clock();
        SyncJobs jobs = new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, 시계));
        FullSyncUseCase 시계를_보는_동기화 = new FullSyncUseCase(source, snapshots, state, writer, new FakeTupleScanner(writer),
                new DeletionGuard(DeletionGuardPolicy.defaults()), jobs, 시계);
        SyncRun started = 시계를_보는_동기화.start(trigger, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }
```

새 테스트를 끝에 더한다:

```java
    @Test
    @DisplayName("스냅샷 저장이 실패한 회차도 OpenFGA 에 쓴 튜플을 남긴다 — 현재상태는 바뀌지 않아 조직도 변경은 없다")
    void 스냅샷_저장이_실패해도_쓴_튜플을_남긴다() {
        // given
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, run.runId())));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        assertThat(김의_줄.created()).isFalse();
    }

    @Test
    @DisplayName("현재상태 교체가 도중에 실패한 회차도 쓴 튜플과 그때까지 끝난 조직도 변경을 남긴다")
    void 현재상태_교체가_도중에_실패해도_끝난_것을_남긴다() {
        // given — 직원 kim 을 쓴 뒤, 조직 DEV 를 쓰기 전에 끊긴다
        state.failReplaceWithAfter = 1;
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.granted()).containsExactly(김_개발);
        assertThat(김의_줄.joined()).as("DEV 의 멤버 줄은 쓰지 못했다").isEmpty();
        assertThat(auditLog.entriesOf(MemberRef.group("DEV"))).isEmpty();
    }

    @Test
    @DisplayName("쓰는 도중 리스를 잃어 멈춘 회차도 그때까지 끝난 쓰기를 남긴다")
    void 쓰는_도중_리스를_잃어도_끝난_쓰기를_남긴다() {
        // given — 쓰기 묶음은 끝났는데 전체 응답이 늦는 사이 리스를 잃는다
        SyncJobs 짧게_갱신하는_잡스 = new SyncJobs(runs, lock, Duration.ofMillis(50), LockObserver.NOOP, Duration.ofMinutes(1),
                audit);
        FullSyncUseCase 동기화 = new FullSyncUseCase(source, snapshots, state, writer, new FakeTupleScanner(writer), guard,
                짧게_갱신하는_잡스, clock);
        writer.delay = Duration.ofMinutes(10);
        writer.onApply(() -> lock.failRenew = true);
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun started = 동기화.start(SyncTrigger.SCHEDULED, ignored -> {
        }).block();
        SyncRun run = runs.awaitFinished(started.runId());

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(auditLog.lastOf(김).granted()).containsExactly(김_개발);
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("장부 맞추기가 현재상태 교체에 실패해도 지운 줄을 장부 맞추기로 남긴다")
    void 장부_맞추기가_실패해도_지운_줄을_남긴다() {
        // given — 지난 회차가 쓰다 멈췄고 장부에 찌꺼기가 있다. 맞춘 뒤 현재상태 교체가 곧바로 실패한다
        writer.stored.add(유령_개발);
        snapshots.writing.set(true);
        state.failReplaceWithAfter = 0;
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.LEDGER_ALIGNMENT, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
        assertThat(auditLog.entriesOf(김)).isEmpty();
    }

    @Test
    @DisplayName("재적재가 스냅샷 저장에 실패해도 지운 줄을 재적재로 남긴다")
    void 재적재가_스냅샷_저장에_실패해도_지운_줄을_남긴다() {
        // given
        writer.stored.add(유령_개발);
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));
        source.willReturn(조직도(직원("kim")));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.LDAP, AuditKind.REBUILD, run.runId())));
        assertThat(auditLog.lastOf(유령).revoked()).containsExactly(유령_개발);
    }
```

클래스 자바독을 `LDAP 동기화·재적재의 이력(설계 2026-10-10 §4.2·§4.4·§4.5) — 실패·중단된 회차도 그때까지 한 변경을 남긴다(설계 2026-10-15).` 로 바꾼다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*TupleReconcilerTest*' --tests '*LdapSyncAuditTest*'`
Expected: 컴파일 실패 — 6-인자 `reconcile`, 8-인자 생성자에 이력이 없는 동기화 등.

- [ ] **Step 3: `TupleReconciler` 를 고친다**

지금의 5-인자 `reconcile` 은 6-인자를 부르고, 6-인자가 몸을 갖는다(`java.util.function.Consumer` import):

```java
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check) {
        return reconcile(writer, scanner, desired, revision, check, 지운_줄 -> {
        });
    }

    /**
     * (지금 5-인자의 자바독) {@code 지운_줄마다} 는 지우기 묶음이 끝날 때마다 실제로 지운 줄로 불린다(설계 2026-10-15 §5) — 차단기로 멈추거나 취소돼도
     * 끝난 묶음은 이미 알린 뒤다. 여러 스레드에서 불릴 수 있다. 쓴 줄은 알리지 않는다(이력은 재적재가 쓴 줄을 남기지 않는다).
     */
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check,
                                          Consumer<Set<RelationTuple>> 지운_줄마다) {
        // (지금 몸 그대로) 단, 지운다(writer, scan.stale()) 를 지운다(writer, scan.stale(), 지운_줄마다) 로
    }

    private static Mono<TupleWriteResult> 지운다(RelationTupleWriter writer, Set<RelationTuple> stale,
                                              Consumer<Set<RelationTuple>> 지운_줄마다) {
        return stale.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.deleteOnly(stale), 묶음 -> 지운_줄마다.accept(묶음.deleted()));
    }
```

- [ ] **Step 4: `LedgerAlignment` 를 고친다**

```java
/**
 * LDAP 장부 맞추기 — 재적재와 "멈춘 뒤 첫 동기화"가 함께 쓴다(설계 2026-09-30 §4).
 *
 * <ol>
 *   <li>쓰기 전에 "기록 중" 표시를 남긴다 — 이 청소가 중간에 멈춰도 다음 회차가 다시 맞춘다.</li>
 *   <li>장부를 D 에 맞추되, 지우기 전에 삭제 가드를 본다({@code force} 면 건너뛴다). 가드에 걸리면 지우지 않고 ABORTED — 스냅샷을 남기지 않으므로
 *       표시가 남아 다음 회차도 다시 확인한다.</li>
 *   <li>끝나면 스냅샷(표시가 사라진다)과 현재상태를 남긴다.</li>
 *   <li>지운 줄과 현재상태가 바뀐 것은 끝나는 즉시 회차의 변경 모음에 더한다(설계 2026-10-15 §5) — 스냅샷 저장·현재상태 교체가 실패해도 남는다. 쓴 줄은
 *       중복 무시로 써 이미 있던 줄과 가를 수 없어 남기지 않는다(설계 2026-10-10 §3.5). 이력은 {@link SyncJobs} 가 남긴다.</li>
 * </ol>
 */
@RequiredArgsConstructor
final class LedgerAlignment {

    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final DeletionGuard guard;
    private final Clock clock;

    /** 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    Mono<Mono<SyncOutcome>> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force,
                                  AuditChanges 기록) {
        TupleReconciler.DeleteCheck check = force ? TupleReconciler.DeleteCheck.NONE : this::가드로_본다;
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, TupleReconciler.DesiredRevision.NONE, check,
                        기록::revoked))
                .flatMap(reconciliation -> reconciliation.held()
                        ? Mono.just(Mono.just(SyncOutcome.aborted(reconciliation.heldReason())))
                        : commit(directory, reconciliation, 기록));
    }

    private Optional<String> 가드로_본다(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned) {
        GuardDecision decision = guard.evaluate(stale.size(), Math.toIntExact(scanned), "훑은 장부");
        return decision.aborted() ? Optional.of(decision.message()) : Optional.empty();
    }

    /** 스냅샷에는 장부에 실제로 있다고 볼 줄을 담는다(설계 2026-09-29 §3.1 4단계). 그다음 현재상태를 LDAP 대로 바꾼다. */
    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation,
                                           AuditChanges 기록) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger());

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())))
                .thenReturn(Mono.just(reconciliation.outcome(snapshot.id())));
    }
}
```

import: `AuditCause`·`AuditRecorder` 를 지운다.

- [ ] **Step 5: `FullSyncUseCase` 를 고친다**

필드 `audit` 과 9-인자 생성자를 없애고, 8-인자 생성자가 직접 채운다(`this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);`). 클래스 자바독의 "이력" 문단을 바꾼다:

```java
 * <p><b>이력(설계 2026-10-10 §4, 2026-10-15).</b> 회차가 OpenFGA 에 쓰고 지운 튜플은 묶음마다, 현재상태가 바뀐 것은 직원·조직 하나마다 끝나는 즉시
 * 회차의 변경 모음에 더한다. 회차가 어떻게 끝나든 {@link SyncJobs} 가 락을 반납한 뒤 남긴다 — 스냅샷 저장·현재상태 교체가 실패하거나 리스를 잃어도
 * 이미 한 변경이 남는다. 장부를 훑어 맞춘 회차는 종류를 장부 맞추기로 바꾸고 지운 줄만 남긴다.
```

메서드를 바꾼다(`commit` 은 남기고, `남긴다`·`저장함` 은 없앤다):

```java
    public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
        return jobs.startLockedThen(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC, AuditKind.SYNC,
                (run, 기록) -> Mono.defer(() -> synchronize(trigger, 기록)), onFinished);
    }

    /** 락 안에서 맞추고 "반납 뒤 할 일"을 내놓는다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    private Mono<Mono<SyncOutcome>> synchronize(SyncTrigger trigger, RunAudit 기록) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return snapshots.isWriting().flatMap(writing -> writing
                    ? 훑어_맞춘다(directory, mapping.tuples(), trigger, 기록)
                    : 비교해_맞춘다(directory, mapping.tuples(), trigger, 기록.changes()));
        });
    }

    // (자바독 그대로)
    private Mono<Mono<SyncOutcome>> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                               SyncTrigger trigger, RunAudit 기록) {
        log.warn(기준선_의심);
        기록.kind(AuditKind.LEDGER_ALIGNMENT);
        return alignment.align(directory, desired, trigger == SyncTrigger.FORCED, 기록.changes())
                .map(반납_뒤 -> 반납_뒤.map(outcome -> outcome.withNote(기준선_의심)))
                .onErrorMap(error -> new IllegalStateException(
                        기준선_의심 + " / " + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()),
                        error));
    }

    private Mono<Mono<SyncOutcome>> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired,
                                                SyncTrigger trigger, AuditChanges 기록) {
        return baseline().flatMap(baseline -> {
            TupleDelta delta = TupleDiff.between(baseline, desired);
            if (delta.isEmpty()) {
                log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                return state.replaceWith(directory, 기록.directoryListener())
                        .thenReturn(Mono.just(SyncOutcome.noChange()));
            }
            if (trigger != SyncTrigger.FORCED) {
                GuardDecision decision = guard.evaluate(delta, baseline);
                if (decision.aborted()) {
                    log.warn("삭제 가드 발동: {}", decision.message());
                    return Mono.just(Mono.just(SyncOutcome.aborted(decision.message())));
                }
            }
            // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11). 쓴·지운 줄은 묶음마다 기록에 더한다
            return snapshots.markWriting()
                    .then(Mono.defer(() -> writer.apply(delta, 기록::tuples)))
                    .flatMap(result -> commit(directory, baseline, result, 기록))
                    .onErrorResume(TupleWriteAbortedException.class, stopped ->
                            saveSnapshotAndState(directory, baseline, stopped.partial(), 기록)
                                    .map(snapshotId -> Mono.just(
                                            SyncOutcome.stopped(stopped.partial(), snapshotId, stopped.getMessage()))));
        });
    }

    private Mono<Mono<SyncOutcome>> commit(DirectorySnapshot directory, Set<RelationTuple> baseline,
                                           TupleWriteResult result, AuditChanges 기록) {
        return saveSnapshotAndState(directory, baseline, result, 기록)
                .map(snapshotId -> Mono.just(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshotId)
                        : SyncOutcome.succeeded(result, snapshotId)));
    }

    /**
     * (지금 자바독의 앞 두 문단 그대로) 스냅샷 저장이 "기록 중" 표시를 지운다. 현재상태가 바뀐 것은 직원·조직 하나마다 {@code 기록} 에 더한다. 저장한
     * 스냅샷 아이디를 준다.
     */
    private Mono<String> saveSnapshotAndState(DirectorySnapshot directory, Set<RelationTuple> baseline,
                                              TupleWriteResult result, AuditChanges 기록) {
        Set<RelationTuple> committed = new HashSet<>(baseline);
        committed.removeAll(result.deleted());
        committed.addAll(result.written());

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed);

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory, 기록.directoryListener())))
                .thenReturn(snapshot.id());
    }
```

import: `AuditCause`·`AuditRecorder`·`DirectoryChanges` 를 지우고 `RunAudit` 를 더한다.

- [ ] **Step 6: `RebuildUseCase` 를 고친다**

```java
    public RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                          DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                          DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.jobs = jobs;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);
    }

    // (자바독 그대로)
    public Mono<SyncRun> start(boolean force, Consumer<SyncRun> onFinished) {
        return jobs.startLockedThen(SyncSource.LDAP, SyncTrigger.REBUILD, MutationLock.LockPurpose.REBUILD,
                AuditKind.REBUILD, (run, 기록) -> Mono.defer(() -> rebuild(force, 기록.changes())), onFinished);
    }

    private Mono<Mono<SyncOutcome>> rebuild(boolean force, AuditChanges 기록) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            return alignment.align(directory, mapping.tuples(), force, 기록);
        });
    }
```

9-인자 생성자와 "이력을 남기지 않는다" 자바독을 없앤다. 클래스 자바독의 이력 문단을 `회차 id 로, 현재상태가 바뀐 것과 지운 줄을 남긴다. 끝나는 즉시 회차의 변경 모음에 더하고, 회차가 어떻게 끝나든 {@link SyncJobs} 가 락을 반납한 뒤 남긴다(설계 2026-10-15).` 로 바꾼다. import: `AuditCause`·`AuditRecorder` 를 지우고 `AuditChanges` 를 더한다.

- [ ] **Step 7: 가짜 락과 결선을 고친다**

`FakeMutationLock` 의 `public boolean failRenew = false;` 를 `public volatile boolean failRenew = false;` 로 바꾼다 — 테스트가 쓰기 도중(다른 스레드)에 켠다.

`app-ldap` `UseCaseConfig` 의 `fullSyncUseCase`·`rebuildUseCase` 빈에서 `AuditRecorder auditRecorder` 매개변수를 빼고 8-인자 생성자를 부른다.

- [ ] **Step 8: 통과를 본다**

Run: `./gradlew :core:test --tests '*TupleReconcilerTest*' --tests '*LdapSyncAuditTest*'`
Expected: PASS.
Run: `./gradlew :core:test :app-ldap:compileTestJava`
Expected: PASS.

- [ ] **Step 9: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java core/src/test/java/dev/starryeye/organization/core/usecase/LdapSyncAuditTest.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java
git commit -m "fix: LDAP 동기화·장부 맞추기·재적재가 쓰기가 끝나는 즉시 회차의 모음에 더한다 — 스냅샷 저장·현재상태 교체가 실패하거나 리스를 잃어도 이미 한 변경이 이력에 남는다(운영 견고성 ②-b)"
```

---

### Task 6: SCIM 재적재(`tuples`·`wipe`)가 끝나는 즉시 모은다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java` (`Function` 오버로드를 없앤다)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`failReplaceCutEdges`·`failClearUniqueMarkers`)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java` (`scimRebuildUseCase` 빈)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildAuditTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java`

**Interfaces:**
- Consumes: 새 `startLockedThen`·6-인자 `SyncJobs`(Task 4), 6-인자 `TupleReconciler.reconcile`(Task 5), `replaceWith(snapshot, 변경마다)`·`failReplaceWithAfter`(Task 2), `AuditChanges.directoryListener()`(Task 1).
- Produces:
  - `ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock)` — 생성자 하나만 남는다(`AuditRecorder` 7-인자는 없앤다).
  - `FakeStateRepository.failReplaceCutEdges`·`failClearUniqueMarkers` (`public volatile RuntimeException`) — 설정하면 `replaceCutEdges`·`clearUniqueMarkers` 가 그 예외로 끝난다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimRebuildAuditTest` 의 준비를 바꾼다 — 필드 `snapshots` 를 두고, 이력은 `SyncJobs` 가 남긴다:

```java
    private FakeSnapshotRepository snapshots;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        snapshots = new FakeSnapshotRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new ScimRebuildUseCase(state, writer, new FakeTupleScanner(writer), snapshots,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                        new AuditRecorder(auditLog, AuditObserver.NOOP, clock)),
                clock);
    }
```

`시계가_가는_락으로_재적재한다` 도 `new SyncJobs(runs, 시계가_가는_락, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1), new AuditRecorder(auditLog, AuditObserver.NOOP, 시계))` 와 6-인자 `ScimRebuildUseCase(…, 시계)` 로 바꾼다.

새 테스트를 끝에 더한다:

```java
    @Test
    @DisplayName("tuples — 보류 목록 저장이 실패해도 지운 찌꺼기 줄을 남긴다")
    void tuples_는_보류_목록이_실패해도_지운_줄을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        state.failReplaceCutEdges = new IllegalStateException("보류 목록 저장 실패");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.lastOf(MemberRef.user("ghost")).revoked()).containsExactly(찌꺼기);
    }

    @Test
    @DisplayName("tuples — 스냅샷 저장이 실패해도 지운 찌꺼기 줄을 남긴다")
    void tuples_는_스냅샷_저장이_실패해도_지운_줄을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패"));

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).startsWith("장부는 맞췄다 — 스냅샷 저장 실패");
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.run(SyncSource.SCIM, AuditKind.REBUILD, run.runId())));
        assertThat(auditLog.lastOf(MemberRef.user("ghost")).revoked()).containsExactly(찌꺼기);
    }

    @Test
    @DisplayName("wipe — 장부 지우기가 일부 실패해 조직도를 지우지 않아도 지운 줄을 남긴다")
    void wipe_는_장부_지우기가_일부_실패해도_지운_줄을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));
        writer.failFor(tuple -> tuple.equals(백엔드_개발본부));

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.revoked()).containsExactly(김_백엔드);
        assertThat(김의_줄.deleted()).isFalse();
        assertThat(auditLog.entriesOf(MemberRef.group("DEV002"))).isEmpty();
        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("wipe — 조직도 지우기가 도중에 실패해도 그때까지 지운 조직과 줄을 남긴다")
    void wipe_는_조직도_지우기가_도중에_실패해도_지운_것을_남긴다() {
        // given — 빈 조직도로 바꾸며 조직 DEV002 를 지운 뒤 끊긴다
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));
        state.failReplaceWithAfter = 1;

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        AuditEntry 백엔드의_줄 = auditLog.lastOf(MemberRef.group("DEV002"));
        assertThat(백엔드의_줄.deleted()).isTrue();
        assertThat(백엔드의_줄.membersRemoved()).containsExactly(김);
        assertThat(백엔드의_줄.revoked()).containsExactly(백엔드_개발본부);
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.deleted()).isFalse();
        assertThat(김의_줄.revoked()).containsExactly(김_백엔드);
        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("wipe — 표지판 비우기가 실패해도 지운 직원·조직과 줄을 남긴다")
    void wipe_는_표지판_비우기가_실패해도_지운_것을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));
        state.failClearUniqueMarkers = new IllegalStateException("표지판 비우기 실패");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.lastOf(김).deleted()).isTrue();
        assertThat(auditLog.lastOf(김).revoked()).containsExactly(김_백엔드);
        assertThat(auditLog.lastOf(MemberRef.group("DEV002")).deleted()).isTrue();
        assertThat(auditLog.lastOf(MemberRef.group("DEV001")).deleted()).isTrue();
    }

    @Test
    @DisplayName("wipe — 보류 목록 비우기가 실패해도 지운 직원·조직과 줄을 남긴다")
    void wipe_는_보류_목록이_실패해도_지운_것을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 백엔드_개발본부));
        state.failReplaceCutEdges = new IllegalStateException("보류 목록 비우기 실패");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(auditLog.lastOf(김).deleted()).isTrue();
        assertThat(auditLog.lastOf(MemberRef.group("DEV002")).revoked()).containsExactly(백엔드_개발본부);
    }
```

`ScimRebuildIndexLagTest` 의 준비(`setUp`)를 같은 식으로 바꾼다 — `new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1), new AuditRecorder(auditLog, AuditObserver.NOOP, clock))` 와 6-인자 `ScimRebuildUseCase(…, clock)`. `열거가_늦은_저장소` 에 손잡이를 더하고 `deleteUser` 가 따른다:

```java
        /** 설정하면 {@link #deleteUser} 가 이 예외로 끝난다 — wipe 의 남은 대상 지우기 실패를 흉내 낸다. 전체 교체의 직원 삭제에는 걸리지 않는다. */
        volatile RuntimeException 직원_지우기_실패;

        @Override
        public Mono<Void> deleteUser(String userId) {
            if (직원_지우기_실패 != null) {
                return Mono.error(직원_지우기_실패);
            }
            // (지금 몸 그대로)
        }
```

새 테스트를 wipe 테스트들 뒤에 더한다:

```java
    @Test
    @DisplayName("wipe — 남은 대상을 지우다 실패해도 그때까지 지운 조직은 지우기 전 이름표와 함께 이력에 남는다")
    void wipe_는_남은_대상_지우기가_실패해도_지운_것을_남긴다() {
        // given — lee 와 TEAM 은 GSI 에 없다. 남은 대상 중 조직 TEAM 을 지운 뒤 직원 lee 를 지우다 실패한다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("DEV002", 김);
        조직을_심는다("TEAM", 이);
        writer.stored.addAll(Set.of(김_백엔드, 이_팀));
        state.숨긴_직원.add("lee");
        state.숨긴_조직.add("TEAM");
        state.직원_지우기_실패 = new IllegalStateException("직원 지우기 실패");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        AuditEntry 팀_줄 = auditLog.lastOf(팀);
        assertThat(팀_줄.deleted()).isTrue();
        assertThat(팀_줄.attributes())
                .extracting(AttributeChange::name, AttributeChange::before, AttributeChange::after)
                .containsExactly(tuple("externalId", "TEAM", null), tuple("displayName", "TEAM-조직", null));
        assertThat(auditLog.lastOf(김).deleted()).isTrue();
        AuditEntry 이의_줄 = auditLog.lastOf(이);
        assertThat(이의_줄.deleted()).isFalse();
        assertThat(이의_줄.revoked()).containsExactly(이_팀);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*ScimRebuildAuditTest*' --tests '*ScimRebuildIndexLagTest*'`
Expected: 컴파일 실패 — 손잡이 둘이 없다. (붙이면 새 실패 테스트가 "이력 없음" 으로 실패한다 — 지금은 실패한 회차에 쓰지 않는다.)

- [ ] **Step 3: 가짜 저장소에 손잡이 둘을 더한다**

```java
    /** 설정하면 {@link #replaceCutEdges} 가 이 예외로 끝난다 — 보류 목록 저장 실패를 흉내 낸다. */
    public volatile RuntimeException failReplaceCutEdges;

    /** 설정하면 {@link #clearUniqueMarkers} 가 이 예외로 끝난다 — 표지판 비우기 실패를 흉내 낸다. */
    public volatile RuntimeException failClearUniqueMarkers;
```

```java
    @Override
    public Mono<Void> replaceCutEdges(Set<GroupEdge> edges) {
        return Mono.defer(() -> failReplaceCutEdges != null
                ? Mono.error(failReplaceCutEdges)
                : Mono.fromRunnable(() -> {
                    cutEdges.clear();
                    cutEdges.addAll(edges);
                }));
    }

    @Override
    public Mono<Void> clearUniqueMarkers() {
        return Mono.defer(() -> failClearUniqueMarkers != null
                ? Mono.error(failClearUniqueMarkers)
                : Mono.fromRunnable(uniqueMarkers::clear));
    }
```

- [ ] **Step 4: `ScimRebuildUseCase` 를 고친다**

필드 `audit` 과 명시한 6-인자 생성자를 없앤다 — `@RequiredArgsConstructor` 가 6-인자 생성자를 만든다.

```java
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLockedThen(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD, AuditKind.REBUILD,
                (run, 기록) -> Mono.defer(() -> rebuild(mode, 기록.changes())),
                run -> {
                });
    }

    /** 락 안에서 할 일을 하고 "반납 뒤 할 일"을 내놓는다 — 튜플 스냅샷 저장은 락을 반납한 뒤에 한다. 바꾼 것은 끝나는 즉시 {@code 기록} 에 더한다. */
    private Mono<Mono<SyncOutcome>> rebuild(ScimRebuildMode mode, AuditChanges 기록) {
        return mode == ScimRebuildMode.WIPE ? wipe(기록) : reloadTuples(기록);
    }
```

`reloadTuples(AuditChanges 기록)` — `TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 보강, 빈_조직도면_멈춘다, 기록::revoked)` 를 부르고, 커밋은 `commitTuples(reconciliation)`:

```java
            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 보강, 빈_조직도면_멈춘다, 기록::revoked)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(Mono.just(SyncOutcome.failed(reconciliation.heldReason())))
                            // 재적재는 전체를 정하는 연산이라 보류 목록도 처음부터 다시 정한다(설계 2026-10-03 §4.6)
                            : state.replaceCutEdges(최종.get().cutEdges())
                                    .then(Mono.fromSupplier(() -> commitTuples(reconciliation))));
```

```java
    /**
     * 스냅샷에는 <b>장부에 실제로 있다고 볼 줄</b>만 담는다(설계 §3.1 4단계). 지우기가 차단기로 멈췄어도 같다 — 스냅샷을 남긴 뒤 FAILED 로 기록한다.
     * <b>락을 반납한 뒤에 돈다</b>(설계 2026-10-02 §5) — SCIM 쓰기 경로는 이 스냅샷을 읽지 않는다. 저장이 실패하면 FAILED 다 — 장부는 이미 맞췄다.
     * 지운 줄은 지우기 묶음이 끝날 때마다 이미 회차의 변경 모음에 들어 있어, 저장이 실패해도 이력에 남는다(설계 2026-10-15 §5).
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation) {
        return Mono.defer(() -> {
                    Instant now = clock.instant();
                    TupleSnapshot snapshot = new TupleSnapshot(
                            SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger());
                    return snapshots.save(snapshot).thenReturn(reconciliation.outcome(snapshot.id()));
                })
                .onErrorMap(error -> new IllegalStateException("장부는 맞췄다 — 스냅샷 저장 실패: "
                        + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()), error));
    }
```

`wipe`:

```java
    /**
     * 있어야 할 줄이 없다(D = ∅)고 보고 장부를 비운 <b>뒤</b> 조직도를 지운다. 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다.
     * 지운 줄은 지우기 묶음마다, 지운 직원·조직은 하나마다 끝나는 즉시 {@code 기록} 에 더한다 — 어디서 실패해도 그때까지 지운 것이 이력에 남는다(설계 2026-10-15 §5).
     */
    private Mono<Mono<SyncOutcome>> wipe(AuditChanges 기록) {
        DirectoryChanges.Listener 변경마다 = 기록.directoryListener();
        return TupleReconciler.reconcile(writer, scanner, Set.of(), TupleReconciler.DesiredRevision.NONE,
                        TupleReconciler.DeleteCheck.NONE, 기록::revoked)
                .flatMap(reconciliation -> {
                    TupleWriteResult result = reconciliation.result();
                    if (result.hasFailure()) {
                        String 멈춘_이유 = reconciliation.stopReason() == null ? "" : " — 멈춘 이유: " + reconciliation.stopReason();
                        return Mono.error(new IllegalStateException(
                                "장부에서 %d줄을 지우지 못해 조직도를 지우지 않았다. 다시 실행하면 남은 줄부터 지운다%s"
                                        .formatted(result.failures().size(), 멈춘_이유)));
                    }
                    // 조직도와 함께 보류 목록도 비운다 — 연결의 멤버 줄이 사라졌으니 보류할 것도 없다
                    return state.replaceWith(DirectorySnapshot.empty(), 변경마다)
                            // GSI 열거에서 빠져 남은 대상을 장부와 표지판의 주인으로 찾아 지운다(설계 2026-10-13 §4.4, 2026-10-14 §5)
                            .flatMap(changes -> 남은_대상을_지운다(changes, result.deleted(), 변경마다))
                            // 표지판도 비운다 — 조직도가 비었으니 쥘 값도 없다(설계 2026-10-14 §5)
                            .flatMap(changes -> state.clearUniqueMarkers().thenReturn(changes))
                            .flatMap(changes -> state.replaceCutEdges(Set.of()).thenReturn(changes))
                            .doOnSuccess(changes -> log.warn(
                                    "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"))
                            .thenReturn(Mono.just(SyncOutcome.succeeded(result, null)));
                });
    }
```

`남은_대상을_지운다` 두 메서드는 `DirectoryChanges.Listener 변경마다` 를 끝 인자로 받아 넘기고, 지울 때마다 알린다:

```java
        Mono<List<DirectoryChanges.GroupDiff>> 조직 = Flux.fromIterable(볼_조직)
                .flatMap(state::findGroup, 보강_동시성)
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(group -> {
                    Set<MemberRef> 나간_멤버 = new LinkedHashSet<>(먼저_나간_멤버.getOrDefault(group.id(), Set.of()));
                    나간_멤버.addAll(group.members());
                    DirectoryChanges.GroupDiff 삭제 = new DirectoryChanges.GroupDiff(
                            new GroupHeader(group.id(), group.externalId(), group.displayName()), null, Set.of(), 나간_멤버);
                    return state.deleteGroup(group.id(), group.members())
                            .then(Mono.fromRunnable(() -> 변경마다.group(삭제)))
                            .thenReturn(삭제);
                })
                .collectList();
        Mono<List<DirectoryChanges.UserDiff>> 직원 = Mono.defer(() -> 볼_직원.isEmpty()
                ? Mono.just(List.<DirectoryChanges.UserDiff>of())
                : state.findUsers(볼_직원)
                        .concatMap(user -> {
                            DirectoryChanges.UserDiff 삭제 = new DirectoryChanges.UserDiff(user, null);
                            return state.deleteUser(user.id())
                                    .then(Mono.fromRunnable(() -> 변경마다.user(삭제)))
                                    .thenReturn(삭제);
                        })
                        .collectList());
```

그 자바독의 첫 문단 끝 "지운 것을 비교 결과에 더해 이력에 삭제로 남긴다" 를 `지운 것은 하나 지울 때마다 {@code 변경마다} 로 알려 이력에 삭제로 남기고(설계 2026-10-15 §4), 비교 결과에도 더해 돌려준다.` 로 바꾼다. 둘째 문단의 이력 설명("이력이 이름표(이전 값)와 … 이름표가 비어 보인다")은 `이력은 이 접기에 기대지 않는다 — 변경 모음은 이름표가 그대로인 결과의 상태를 보지 않아 뒤에 온 삭제가 이름표를 남긴다(설계 2026-10-15 §5). 접기는 돌려주는 값을 정확하게 두려고 남긴다.` 로 바꾼다.

클래스 자바독의 "이력(설계 2026-10-10 §4.2)" 문단 끝에 `지운 줄과 지운 직원·조직은 끝나는 즉시 회차의 변경 모음에 더하고, 회차가 어떻게 끝나든 {@link SyncJobs} 가 락을 반납한 뒤 남긴다 — 도중에 실패한 재적재도 그때까지 지운 것이 남는다(설계 2026-10-15).` 를 더한다. import: `AuditCause`·`AuditRecorder` 를 지운다.

- [ ] **Step 5: `SyncJobs` 의 `Function` 오버로드를 없앤다**

마지막 사용처가 옮겼다. `startLockedThen(SyncSource, SyncTrigger, MutationLock.LockPurpose, Function<SyncRun, Mono<Mono<SyncOutcome>>>, Consumer<SyncRun>)` 와 그 자바독을 지운다. 이제 안 쓰면 `java.util.function.Function` import 도 확인한다(`먼저` 가 아직 `Function` 을 쓴다).

확인: `grep -rn "startLockedThen" core/src app-ldap/src app-scim/src --include='*.java'` 에서 `run ->` 한 인자 람다로 부르는 곳이 없어야 한다.

- [ ] **Step 6: 결선을 고친다**

`app-scim` `ScimUseCaseConfig.scimRebuildUseCase` 에서 `AuditRecorder auditRecorder` 매개변수를 빼고 `new ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock)` 를 부른다.

- [ ] **Step 7: 통과를 본다**

Run: `./gradlew :core:test --tests '*ScimRebuild*' --tests '*SyncJobs*'`
Expected: PASS(기존 `wipe_는_멤버만_빠진_조직도_삭제로_남긴다` 도 — Task 1 의 규칙이 이름표를 지킨다).
Run: `./gradlew :core:test :app-scim:compileTestJava :app-ldap:compileTestJava`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildAuditTest.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java
git commit -m "fix: SCIM 재적재(tuples·wipe)가 지운 줄과 지운 직원·조직을 끝나는 즉시 회차의 모음에 더한다 — 보류 목록·스냅샷·표지판·남은 대상이 실패해도 그때까지 지운 것이 이력에 남는다(운영 견고성 ②-b)"
```

---

### Task 7: SCIM 쓰기 요청이 튜플을 묶음마다 모으고, 어떻게 끝나든 남긴다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`withLock`, `잡고_돌린다`, `반영하고_커밋한다`, `한_줄을_다시_본다`)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncAuditTest.java`

**Interfaces:**
- Consumes: `RelationTupleWriter.apply(delta, 묶음마다)`(Task 3), `FakeMutationLock.failRenew`(volatile, Task 5), `FakeTupleWriter.onApply`·`abortWhen`·`failFor`.
- Produces: 없음(요청의 이력 규칙만 바뀐다).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`IncrementalSyncAuditTest` 의 `setUp` 에서 `FakeTupleWriter writer` 를 필드로 올린다(`private FakeTupleWriter writer;`, `writer = new FakeTupleWriter();`). import: `dev.starryeye.organization.core.port.TemporaryFailureException`.

`오류로_끝난_요청은_남기지_않는다` 를 뒤집는다:

```java
    @Test
    @DisplayName("커밋 자리가 기록한 뒤 오류로 끝난 요청도 그때까지 모은 것을 남긴다")
    void 오류로_끝난_요청도_모은_것을_남긴다() {
        // given — 소속 조직 빼기와 튜플 지우기가 끝난 뒤, 직원 삭제 커밋이 실패한다
        FakeStateRepository 실패하는_저장소 = new 삭제가_실패하는_저장소();
        실패하는_저장소.saveUser(직원("kim", true)).block();
        실패하는_저장소.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(김))).block();
        checker.allowed.addAll(TupleMapper.toTuples(실패하는_저장소.loadAll().block()).tuples());
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        IncrementalSyncUseCase 실패하는_유스케이스 = new IncrementalSyncUseCase(실패하는_저장소, new FakeTupleWriter(), checker,
                lock, Duration.ZERO, Duration.ofSeconds(10), IncrementalSyncUseCase.DriftObserver.NOOP,
                LockObserver.NOOP, audit, context -> "trace-1");

        // when
        assertThatThrownBy(() -> 실패하는_유스케이스.removeUser("kim").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("삭제 실패");

        // then — 삭제는 없고, 나간 조직·잃은 권한·멤버 나감이 남는다
        assertThat(auditLog.events).singleElement().satisfies(event ->
                assertThat(event.cause()).isEqualTo(AuditCause.scimRequest("trace-1")));
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.deleted()).isFalse();
        assertThat(김의_줄.left()).containsExactly("DEV");
        assertThat(김의_줄.revoked()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        assertThat(auditLog.lastOf(개발).membersRemoved()).containsExactly(김);
    }
```

새 테스트를 끝에 더한다:

```java
    @Test
    @DisplayName("오류로 끝난 요청의 시각도 반납 뒤가 아니라 락 안에서 끝난 때다")
    void 오류로_끝난_요청의_시각도_락_안의_시각이다() {
        // given — 직원 삭제 커밋이 실패한다. 반납하는 순간 시계가 1분 간다
        ClockAdvancingLock 시계가_가는_락 = new ClockAdvancingLock(고정시각);
        FakeStateRepository 실패하는_저장소 = new 삭제가_실패하는_저장소();
        실패하는_저장소.saveUser(직원("kim", true)).block();
        실패하는_저장소.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of(김))).block();
        checker.allowed.addAll(TupleMapper.toTuples(실패하는_저장소.loadAll().block()).tuples());
        IncrementalSyncUseCase 유스케이스 = new IncrementalSyncUseCase(실패하는_저장소, new FakeTupleWriter(), checker,
                시계가_가는_락, Duration.ZERO, Duration.ofSeconds(10), IncrementalSyncUseCase.DriftObserver.NOOP,
                LockObserver.NOOP, new AuditRecorder(auditLog, AuditObserver.NOOP, 시계가_가는_락.clock()), context -> "trace-1");

        // when
        assertThatThrownBy(() -> 유스케이스.removeUser("kim").block()).hasMessage("삭제 실패");

        // then
        assertThat(시계가_가는_락.clock().instant()).isEqualTo(고정시각.plus(ClockAdvancingLock.STEP));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(고정시각));
    }

    @Test
    @DisplayName("OpenFGA 에 쓴 뒤 커밋 직전 리스 확인이 실패해도(503) 쓴 튜플을 남긴다 — 커밋하지 못한 소속은 없다")
    void 커밋_직전_리스_확인이_실패해도_쓴_튜플을_남긴다() {
        // given — OpenFGA 쓰기가 끝나면 리스를 잃는다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of())).block();
        openFga를_상태와_맞춘다();
        writer.onApply(() -> lock.failRenew = true);

        // when
        assertThatThrownBy(() -> useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(김))).block())
                .isInstanceOf(LockUnavailableException.class);

        // then
        AuditEntry 김의_줄 = auditLog.lastOf(김);
        assertThat(김의_줄.granted()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        assertThat(김의_줄.joined()).isEmpty();
        assertThat(state.groups.get("DEV").members()).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 차단기로 멈춘 요청은 끝난 묶음에서 실제로 쓴 튜플만 남긴다")
    void 차단기로_멈추면_실제로_쓴_튜플만_남긴다() {
        // given — kim 의 줄은 쓰이고 lee 의 줄은 실패한 뒤 차단기가 선다
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(new DirectoryGroup("DEV", "DEV", "개발", Set.of())).block();
        openFga를_상태와_맞춘다();
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", "DEV")));
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.changeGroup("DEV",
                GroupChange.delta().adding(Set.of(김, MemberRef.user("lee")))).block())
                .isInstanceOf(TemporaryFailureException.class);

        // then
        assertThat(auditLog.lastOf(김).granted()).containsExactly(RelationTuple.directMember("kim", "DEV"));
        assertThat(auditLog.entriesOf(MemberRef.user("lee"))).isEmpty();
    }

    @Test
    @DisplayName("락을 잡지 못한 요청(503)은 모은 것이 없어 남기지 않는다")
    void 락을_잡지_못하면_남기지_않는다() {
        // given
        lock.failAcquire = true;

        // when
        assertThatThrownBy(() -> useCase.createUser(직원("kim", true)).block())
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(auditLog.events).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncAuditTest*'`
Expected: FAIL — 뒤집은 테스트·시각 테스트·리스 확인 테스트·차단기 테스트가 "이력 없음"으로 실패한다(지금은 값을 낸 요청만 남기고, 튜플은 커밋 뒤에 더한다). `락을_잡지_못하면_남기지_않는다` 는 지금도 통과한다(지키는 테스트).

- [ ] **Step 3: 튜플을 묶음마다 더한다**

`반영하고_커밋한다`:

```java
    /**
     * (자바독 앞 두 문단 그대로)
     *
     * <p>OpenFGA 묶음이 끝날 때마다 실제로 쓰고 지운 줄을 {@code 기록} 에 더한다(설계 2026-10-15 §6) — 뒤의 리스 확인·커밋이 실패해도 OpenFGA 에 쓴 줄은
     * 이력에 남는다.
     */
    private Mono<IncrementalSyncResult> 반영하고_커밋한다(TupleDelta delta, LockLease lease, AuditChanges 기록,
                                                   Function<TupleWriteResult, Mono<Void>> 커밋) {
        if (delta.isEmpty()) {
            return 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                    .then(Mono.defer(() -> 커밋.apply(TupleWriteResult.empty())))
                    .thenReturn(IncrementalSyncResult.noChange());
        }
        return 리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")
                .then(Mono.defer(() -> writer.apply(delta, 기록::tuples)))
                .flatMap(result -> 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                        .then(Mono.defer(() -> 커밋.apply(result)))
                        .thenReturn(IncrementalSyncResult.of(result)));
    }
```

`한_줄을_다시_본다` 의 쓰기:

```java
                    : 리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")
                            // OpenFGA 에 실제로 쓴 줄은 묶음이 끝나는 즉시 이 요청의 권한 변경으로 더한다(설계 2026-10-15 §6)
                            .then(Mono.defer(() -> writer.apply(new TupleDelta(Set.of(edge.tuple()), Set.of()), 기록::tuples)))
                            .flatMap(result -> {
                                if (!result.written().contains(edge.tuple())) {
                                    return Mono.empty(); // 쓰기 실패 — 목록에 남겨 다음에 다시 본다
                                }
                                그래프.보류에서_뺀다(edge);
                                log.info("순환이 풀려 보류했던 연결을 썼다: 조직 '{}' → '{}'", edge.parent(), edge.child());
                                return state.changeCutEdges(Set.of(), Set.of(edge));
                            }));
```

- [ ] **Step 4: 어떻게 끝나든 남긴다**

`withLock`:

```java
    private Mono<IncrementalSyncResult> withLock(BiFunction<LockLease, AuditChanges, Mono<IncrementalSyncResult>> work) {
        return Mono.deferContextual(context -> {
            AuditCause 원인 = AuditCause.scimRequest(traceIds.traceIdOf(context));
            AuditChanges 기록 = new AuditChanges();
            // 락 안의 일이 끝난 때 — 값·오류·취소(리스 상실) 무엇이든 반납 전이다. 이력의 시각이다(설계 2026-10-15 §6)
            AtomicReference<Instant> 끝난_시각 = new AtomicReference<>();
            Runnable 끝난_때를_잡는다 = () -> 끝난_시각.compareAndSet(null, audit.now());
            Sinks.One<IncrementalSyncResult> 결과 = Sinks.one();
            AtomicBoolean 요청이_떠났다 = new AtomicBoolean();
            // 알리기 전에 본다 — 값을 받은 뒤에 오는 취소는 떠난 것이 아니다.
            잡고_돌린다(lease -> work.apply(lease, 기록)
                                    .doOnSuccess(ignored -> 끝난_때를_잡는다.run())
                                    .doOnError(ignored -> 끝난_때를_잡는다.run())
                                    .doOnCancel(끝난_때를_잡는다),
                            // 락을 잡지 못한 요청은 모은 것이 없어 쓰지 않는다 — 시각도 없으니 지금으로 둔다
                            () -> audit.record(원인, Optional.ofNullable(끝난_시각.get()).orElseGet(audit::now), 기록.entries()))
                    // (지금의 contextWrite·subscribe 그대로)
            return 결과.asMono().doOnCancel(() -> 요청이_떠났다.set(true));
        });
    }
```

자바독의 "이력(설계 2026-10-10 §4.4)" 문단을 바꾼다:

```java
     * <p><b>이력(설계 2026-10-10 §4.4, 2026-10-15 §6).</b> 요청마다 원인(traceId)과 변경 모음 하나를 만들어 커밋 자리들에 넘긴다. OpenFGA 에 쓴 줄은
     * 묶음이 끝날 때마다, 조직도 변경은 각 커밋이 끝난 직후 더한다. 락을 반납하고 다음 요청에 차례를 넘긴 뒤, 응답하기 전에 모은 것을 한 번에 남긴다 —
     * 요청이 값·오류(리스 상실·차단기·커밋 실패) 무엇으로 끝나든 그때까지 실제로 한 변경이다. 응답은 바뀌지 않는다. 이력 쓰기는 실패해도 요청을
     * 실패시키지 않는다({@link AuditRecorder}). 시각은 일이 락 안에서 끝난 때 잡는다 — 반납 뒤에 잡으면 그 사이 락을 잡고 커밋한 다른 요청·회차보다 늦은
     * 시각이 되어 최신순이 뒤집힌다.
```

`잡고_돌린다` 의 이력 자리를 "값일 때만" 에서 "어떻게 끝나든" 으로 바꾼다:

```java
                                // 반납하고 차례를 넘긴 뒤, 응답하기 전에 이력을 남긴다 — 값·오류·빈 끝 무엇이든 그때까지 모은 것이다(설계 2026-10-15 §6)
                                .flatMap(끝 -> Mono.defer(반납_뒤)
                                        .onErrorResume(error -> {
                                            log.error("이력을 남기지 못했다 — 요청의 결과는 그대로다", error);
                                            return Mono.empty();
                                        })
                                        .thenReturn(끝))
```

그 자바독을 `{@code 반납_뒤} 는 반납하고 차례를 넘긴 뒤, 요청이 어떻게 끝나든 부른다 — 이력이다(설계 2026-10-15 §6). 그것이 오류로 끝나도 요청의 결과는 그대로다(설계 2026-10-10 §4.5).` 로 바꾼다.

`남긴다(Runnable)` 의 자바독("앞의 커밋이 성공한 뒤에만 돈다")은 그대로 맞다 — 조직도 변경은 커밋이 끝난 뒤에 더한다.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSync*'`
Expected: PASS.
Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncAuditTest.java
git commit -m "fix: SCIM 쓰기 요청이 튜플을 OpenFGA 묶음마다 모으고 어떻게 끝나든 이력을 남긴다 — 쓴 뒤 리스 확인·커밋이 실패하거나 차단기로 멈춰도 실제로 쓴 튜플이 남는다(운영 견고성 ②-b)"
```

---

### Task 8: 문서와 끝단 — README, 옛 설계 표시, L15-a

**Files:**
- Modify: `README.md` ("변경 이력(감사 로그)" 절의 "빠질 수 있다")
- Modify: `docs/superpowers/specs/2026-10-10-audit-log-design.md` (§4.4 첫 줄, §10 둘째 줄)
- Modify: `docs/superpowers/specs/2026-10-13-job-robustness-design.md` (§10.2 "남긴 것" 의 wipe 줄)
- Modify: `docs/superpowers/specs/2026-10-15-failed-run-audit-design.md` (§4 OpenFGA 알림 자리, §10 끝단 행)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (L15-a)

**Interfaces:**
- Consumes: 앞 과제 전부. `/admin/employees/{id}/history` 응답(`items[].cause.kind`·`created`·`granted`).

- [ ] **Step 1: L15-a 에 이력 단언을 더한다**

`L15a_상태저장_직전에_끊긴다` 의 `// then` 끝(상태가 비어 있다는 단언 뒤)에 더한다:

```java
        // 상태 저장은 실패했지만 OpenFGA 에 쓴 권한은 이 회차의 이력에 남는다(설계 2026-10-15). 다음 회차(L15-b)는 "변경 없음" 이라 다시 만들지 못한다
        client.get().uri("/admin/employees/{id}/history?limit=5", 기대.landmarks().L6직속직원()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].cause.source").isEqualTo("LDAP")
                .jsonPath("$.items[0].cause.kind").isEqualTo("SYNC")
                .jsonPath("$.items[0].created").isEqualTo(false)
                .jsonPath("$.items[0].granted").isNotEmpty()
                .jsonPath("$.items[1]").doesNotExist();
```

`@DisplayName` 을 `"L15-a. 상태 저장 직전에 끊기면 OpenFGA 만 반영되고 상태는 낡는다 — 쓴 권한은 이력에 남는다"` 로 바꾼다.

Run: `./gradlew :app-ldap:compileTestJava`
Expected: PASS. (이 테스트는 `@ScaleTest` 라 실행은 컨트롤러의 `scaleTest` 가 한다.)

- [ ] **Step 2: README 를 고친다**

"변경 이력(감사 로그)" 절의 **빠질 수 있다** 를 아래로 바꾸고, 그 앞에 **실패한 회차·요청도 남긴다** 를 더한다:

```markdown
- **실패한 회차·요청도 남긴다.**
  - 회차·요청이 실패하거나 멈춰도 그때까지 실제로 한 변경(쓰고 지운 튜플, 바꾼 조직도)을 그 회차·요청을 원인으로 남긴다.
  - 멈춤에는 기한(`sync.job-timeout`)·서버 종료·리스 상실, SCIM 의 503 이 든다.
  - 회차가 어떻게 끝났는지(`FAILED` 등)는 `/admin/sync/runs` 의 그 `runId` 에 있다.
- **빠질 수 있다.**
  - 이력은 회차·요청이 끝난 뒤, 락을 반납한 뒤에 쓴다.
  - 쓰기가 끝내 실패해도 동기화·SCIM 요청은 실패하지 않는다. 대신 셋이 남는다.
    - ERROR 로그.
    - 지표 `audit.write.failures`.
    - 회차 메시지 앞의 `감사 기록 N건 실패`.
  - 반납 뒤 이력을 쓰는 동안 회차는 RUNNING 이다(큰 회차는 시간이 든다). 그 사이 서버가 내려가면 남은 줄이 빠지고 `감사 기록 N건 실패` 도 남지 않을 수 있다.
  - 서버 프로세스가 회차·요청 도중에 죽으면 그때까지 모은 변경이 남지 않는다. 회차 기록은 RUNNING 으로 남았다가 다음 락 작업이 "비정상 종료로 중단" 으로 닫는다.
  - OpenFGA 가 반영하고 응답을 잃은 쓰기 묶음은 반영됐는지 몰라 남지 않는다. 직원·조직 하나의 저장이 도중에 실패해 쓰다 만 부분도 남지 않는다.
```

(지운 줄: "이력은 커밋한 뒤, 락을 반납한 뒤에 쓴다." 와 "이력은 회차·요청이 성공으로 끝나야 쓴다. …다시 만들지도 않는다.")

- [ ] **Step 3: 옛 설계에 표시한다**

`2026-10-10-audit-log-design.md` §4.4 첫 줄 "**상태(현재상태·스냅샷)를 커밋한 뒤에 쓴다.** 커밋되지 않은 변경을 이력에 남기지 않는다." 아래에 더한다:

```markdown
  - **(2026-10-15 바뀜)** 실제로 일어난 변경은 회차·요청이 어떻게 끝나든 남긴다. 쓰기가 끝나는 즉시 모으고, 락을 반납한 뒤 남긴다(`2026-10-15-failed-run-audit-design.md`).
```

§10 의 "**실패한 회차·요청이 이미 한 변경은 이력에 없다.**" 앞에 `**(해결 — 2026-10-15 설계)**` 를 붙인다.

`2026-10-13-job-robustness-design.md` §10.2 "남긴 것" 의 "`wipe` 의 남은 대상 지우기가 실패하면 `FAILED` 이고 이력이 하나도 남지 않는다. …" 줄 끝에 `→ 해결(2026-10-15 설계, 운영 견고성 ②-b).` 를 더한다.

- [ ] **Step 4: 이 설계의 두 문구를 바로잡는다**

`2026-10-15-failed-run-audit-design.md` §4 첫 항목의 "OpenFGA 구현은 묶음 결과를 모으는 자리(`ResultAccumulator`)에서 알린다. …" 를 바꾼다:

```markdown
  - OpenFGA 구현은 묶음의 응답이 오는 자리에서 곧바로 알린다. 결과를 모으는 자리(`ResultAccumulator`)는 보낸 순서를 지키느라 먼저 끝난 묶음을 붙들 수 있어, 그 사이 취소되면 끝난 묶음을 놓친다. 차단기로 멈추거나 도중에 취소돼도 끝난 묶음은 이미 알린 뒤다.
```

§10 끝단 행을 바꾼다:

```markdown
| 끝단 | LDAP 동기화 중 스냅샷을 저장한 뒤 상태 저장(`replaceWith`)이 실패해도 OpenFGA 에 쓴 권한이 이력에 남고, 다음 회차는 "변경 없음" 이다(L15-a·L15-b). 스냅샷 저장 자체가 실패한 회차는 core 가 본다 | app-ldap |
```

- [ ] **Step 5: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-10-10-audit-log-design.md docs/superpowers/specs/2026-10-13-job-robustness-design.md docs/superpowers/specs/2026-10-15-failed-run-audit-design.md app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -m "docs: 실패한 회차·요청도 이력을 남긴다 — README·이력 설계 §4.4·§10·작업 견고성 §10.2 를 고치고, 설계 §4 의 알림 자리와 §10 끝단 문구를 바로잡고, L15-a 가 상태 저장이 실패한 회차의 이력을 본다(운영 견고성 ②-b)"
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로(동시에 말고) 돌린다.
- 설계 §11 에 두 시간·개수, 구현 중 판정, 남긴 것을 적는다.
