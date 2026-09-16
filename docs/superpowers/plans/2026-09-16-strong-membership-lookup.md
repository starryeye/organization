# 소속 역참조 강한 일관성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 소속 역참조(`findGroupIdsContaining`)를 GSI(최종 일관성)에서 멤버 쪽 파티션의 `BELONGS_TO#` 줄(강한 일관성)로 바꿔, 막 추가된 소속을 못 봐서 삭제가 권한을 남기는 결함을 없앤다.

**Architecture:** 조직 파티션의 `MEMBER#...` 줄과 짝을 이루는 `BELONGS_TO#GROUP#<gid>` 줄을 직원(`USER#<uid>`)·하위조직(`GROUP#<cid>`) 파티션에 함께 쓴다. 쓰기는 "넣을 때 소속 줄 먼저, 뺄 때 멤버 줄 먼저" 순서를 지켜 실패 시 항상 안전한 방향으로만 어긋나게 한다. 역참조는 소속 줄을 강한 일관성으로 읽고 멤버 줄 존재를 `GetItem` 으로 확인해 **정확한** 목록을 돌려준다. 멤버 줄에서 GSI1 키를 뗀다.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Reactor, AWS SDK v2 (DynamoDbAsyncClient), JUnit 5, AssertJ, Testcontainers(DynamoDB Local)

**Spec:** `docs/superpowers/specs/2026-09-16-strong-membership-lookup-design.md`

## Global Constraints

- 작업 위치는 워크트리 `/Users/starryeye/study/organization/.claude/worktrees/spike-ldap-5k`, 브랜치 `strong-membership-lookup` (origin/main `cdc6c88` 에서 분기). 다른 디렉터리로 `cd` 하지 않는다.
- **이 슬라이드는 운영 코드를 바꾼다.** 허용 범위는 `storage-dynamodb/src/main`, `core/src/main/java/.../port/DirectoryStateRepository.java`, `core/src/main/java/.../usecase/IncrementalSyncUseCase.java` 뿐이다. 그 밖의 운영 코드는 건드리지 않는다.
- **OpenFGA 에 쓰는 튜플 규칙은 바뀌지 않는다.** `TupleMapper` 와 델타 계산은 손대지 않는다.
- 새 정렬키 접두사는 `BELONGS_TO#` 다. `MEMBER#` 로 시작하면 안 된다 — 조직 파티션을 읽는 `toGroup`·`existingMemberSks` 가 `MEMBER#` 로 멤버를 고르기 때문이다.
- `BELONGS_TO#` 줄에는 GSI 키(`GSI1PK`/`GSI1SK`)를 넣지 않는다.
- 테스트는 BDD(`// given` / `// when` / `// then` 주석), AssertJ, 한글 `@DisplayName`, 한글 메서드명 + 영문 타입명.
- 커밋 메시지는 각 태스크에 적힌 그대로 쓰고, 빈 줄 뒤에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 를 붙인 뒤 `git push` 한다(이 브랜치의 업스트림은 이미 `origin/strong-membership-lookup` 이다).
- 서브에이전트가 돌려도 되는 것: `./gradlew :storage-dynamodb:test`, `./gradlew :core:test`, `./gradlew :storage-dynamodb:compileJava :core:compileJava`, `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`. **`:app-ldap:test` 와 `:app-scim:test` 는 절대 돌리지 않는다**(수십 분). Gradle 을 동시에 두 개 띄우지 않고, 포그라운드로 끝까지 기다린다.
- `git stash` 를 맨손으로 쓰지 않는다.

---

## File Structure

| 파일 | 역할 | 태스크 |
|---|---|---|
| Modify `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` | `BELONGS_TO#` 키 규칙 추가, `memberGsi1Pk` 제거 | 1 |
| Modify `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java` | 키 규칙 테스트 | 1 |
| Modify `storage-dynamodb/src/main/java/.../DynamoDbDirectoryStateRepository.java` | 쓰기 순서, 삭제 정리, 역참조 교체 | 2, 3 |
| Modify `storage-dynamodb/src/test/java/.../DynamoDbDirectoryStateRepositoryTest.java` | 저장소 동작 테스트 | 2, 3 |
| Create `storage-dynamodb/src/test/java/.../ReverseLookupQueryShapeTest.java` | "색인을 쓰지 않는다" 증명 | 4 |
| Modify `core/src/main/java/.../port/DirectoryStateRepository.java` | 계약 변경, `containsMember` 제거 | 3 |
| Modify `core/src/main/java/.../usecase/IncrementalSyncUseCase.java` | `affectedGroupHeadersOf` 의 확인 단계 제거 | 3 |
| Modify `core/src/testFixtures/java/.../fake/FakeStateRepository.java` | 페이크를 새 계약에 맞춤 | 3 |
| Modify `core/src/test/java/.../usecase/IncrementalSyncUseCaseTest.java`, `IncrementalSyncReadScopeTest.java` | 없어진 확인 단계에 기대던 단언 정리 | 3 |
| Modify `app-ldap/src/test/java/.../LdapInterruptedSyncScaleTest.java` | 저장소 대역에서 `containsMember` 제거 | 3 |
| Modify `docs/superpowers/specs/2026-09-16-strong-membership-lookup-design.md` | §11 실측 기록 | 6 |

---

### Task 1: 키 규칙 — `BELONGS_TO#` 추가, 멤버 줄의 GSI 키 제거

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`

**Interfaces:**
- Consumes: 기존 `Keys.MEMBER_PREFIX`, `Keys.memberSk`, `Keys.userPk`, `Keys.groupPk`
- Produces:
  - `Keys.BELONGS_TO_PREFIX` = `"BELONGS_TO#"`
  - `String Keys.belongsToSk(String groupId)` → `"BELONGS_TO#GROUP#<gid>"`
  - `boolean Keys.isBelongsToSk(String sk)`
  - `String Keys.parseBelongsToSk(String sk)` → gid
  - `String Keys.memberPk(MemberRef ref)` → USER 면 `userPk(id)`, GROUP 이면 `groupPk(id)`
  - `Keys.memberGsi1Pk` **삭제**

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`KeysTest.java` 의 `멤버십_역참조_키는_정렬키와_같다` 테스트(39~47행)를 **지우고** 그 자리에 넣는다:

```java
    @Test
    @DisplayName("소속 정렬키는 MEMBER# 로 시작하지 않는다 — 조직 파티션에서 멤버로 오인되면 안 된다")
    void 소속_정렬키는_멤버와_구분된다() {
        // given, when
        String belongsTo = Keys.belongsToSk("DEV002");

        // then
        assertThat(belongsTo).isEqualTo("BELONGS_TO#GROUP#DEV002");
        assertThat(Keys.isMemberSk(belongsTo)).isFalse();
        assertThat(Keys.isBelongsToSk(belongsTo)).isTrue();
        assertThat(Keys.isBelongsToSk(Keys.memberSk(MemberRef.user("kim")))).isFalse();
    }

    @Test
    @DisplayName("소속 정렬키는 왕복 변환해도 조직코드가 그대로다")
    void 소속_정렬키는_왕복_변환된다() {
        // given
        String sk = Keys.belongsToSk("DEV002");

        // when, then
        assertThat(Keys.parseBelongsToSk(sk)).isEqualTo("DEV002");
    }

    @Test
    @DisplayName("멤버의 파티션키는 직원이면 USER#, 하위 조직이면 GROUP# 이다")
    void 멤버의_파티션키() {
        // given, when, then
        assertThat(Keys.memberPk(MemberRef.user("kim"))).isEqualTo("USER#kim");
        assertThat(Keys.memberPk(MemberRef.group("DEV002"))).isEqualTo("GROUP#DEV002");
    }
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.KeysTest'`
Expected: 컴파일 실패 — `belongsToSk`, `isBelongsToSk`, `parseBelongsToSk`, `memberPk` 없음

- [ ] **Step 3: `Keys` 를 고친다**

`MEMBER_PREFIX` 선언 아래에 상수를 더한다:

```java
    public static final String BELONGS_TO_PREFIX = "BELONGS_TO#";
```

`memberGsi1Pk` 메서드(139~142행)를 **지우고** `isMemberSk` 뒤에 더한다:

```java
    /**
     * 멤버 쪽 파티션에 적는 소속 줄의 정렬키. <b>{@code MEMBER#} 로 시작하지 않는 것이 핵심이다</b> —
     * 조직 파티션을 읽는 {@code toGroup}·{@code existingMemberSks} 가 {@code MEMBER#} 로 멤버를
     * 고르므로, 하위 조직의 소속 줄이 그 조직의 멤버로 오인되면 계층이 통째로 어긋난다.
     */
    public static String belongsToSk(String groupId) {
        return BELONGS_TO_PREFIX + GROUP_PREFIX + groupId;
    }

    public static boolean isBelongsToSk(String sk) {
        return sk.startsWith(BELONGS_TO_PREFIX);
    }

    public static String parseBelongsToSk(String sk) {
        if (sk == null || !isBelongsToSk(sk)) {
            throw new IllegalArgumentException("소속 정렬키가 아니다: " + sk);
        }
        return sk.substring(BELONGS_TO_PREFIX.length() + GROUP_PREFIX.length());
    }

    /** 멤버 자신의 파티션키. 직원은 {@code USER#}, 하위 조직은 {@code GROUP#} 이다. */
    public static String memberPk(MemberRef ref) {
        return ref.type() == MemberType.USER ? userPk(ref.id()) : groupPk(ref.id());
    }
```

- [ ] **Step 4: 컴파일을 살린다**

`memberGsi1Pk` 를 지우면 `DynamoDbDirectoryStateRepository` 의 두 곳(`memberItem` 262행, `findGroupIdsContaining` 300행)이 깨진다. **그 두 곳을 `Keys.memberSk(...)` 로 바꾼다** — `memberGsi1Pk` 가 `memberSk` 를 그대로 돌려주고 있었으므로 값이 같고 동작도 그대로다. Task 2·3 이 이 두 곳을 다시 고친다.

- [ ] **Step 5: 테스트를 돌린다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS (`KeysTest` 의 새 테스트 3개와 기존 저장소 테스트 전부)

- [ ] **Step 6: 커밋·푸시**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java
git commit -m "refactor: 소속 줄 키 규칙을 더하고 멤버 GSI 키 헬퍼를 지운다"
git push
```

---

### Task 2: 쓰기 경로 — 소속 줄을 함께 쓰고, 순서를 고정한다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 의 `Keys.belongsToSk`, `Keys.isBelongsToSk`, `Keys.memberPk`
- Produces: `saveGroup`·`deleteGroup`·`deleteUser` 의 새 동작. 외부 시그니처는 그대로

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest.java` 끝(마지막 `}` 앞)에 더한다:

```java
    @Test
    @DisplayName("조직을 저장하면 멤버 쪽 파티션에도 소속 줄이 생긴다")
    void 소속_줄이_함께_생긴다() {
        // given, when
        repository.saveGroup(조직("DEV002", "백엔드팀",
                MemberRef.user("kim"), MemberRef.group("DEV003"))).block();

        // then
        assertThat(정렬키들("USER#kim")).contains("BELONGS_TO#GROUP#DEV002");
        assertThat(정렬키들("GROUP#DEV003")).contains("BELONGS_TO#GROUP#DEV002");
    }

    @Test
    @DisplayName("멤버가 빠지면 그 멤버의 소속 줄도 사라진다")
    void 빠진_멤버의_소속_줄이_사라진다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀",
                MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // when
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("lee"))).block();

        // then
        assertThat(정렬키들("USER#kim")).doesNotContain("BELONGS_TO#GROUP#DEV002");
        assertThat(정렬키들("USER#lee")).contains("BELONGS_TO#GROUP#DEV002");
    }

    @Test
    @DisplayName("하위 조직의 소속 줄은 그 조직의 멤버로 읽히지 않는다")
    void 소속_줄은_멤버가_아니다() {
        // given — DEV003 은 DEV002 의 하위이고, 자기 멤버로 park 한 명을 갖는다
        repository.saveGroup(조직("DEV003", "플랫폼팀", MemberRef.user("park"))).block();
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.group("DEV003"))).block();

        // when
        var found = repository.findGroup("DEV003").block();

        // then — BELONGS_TO#GROUP#DEV002 가 멤버로 섞이면 안 된다
        assertThat(found.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("조직을 지우면 그 멤버들의 소속 줄까지 사라진다")
    void 조직_삭제가_소속_줄을_치운다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀",
                MemberRef.user("kim"), MemberRef.group("DEV003"))).block();

        // when
        repository.deleteGroup("DEV002").block();

        // then
        assertThat(정렬키들("USER#kim")).isEmpty();
        assertThat(정렬키들("GROUP#DEV003")).isEmpty();
    }

    @Test
    @DisplayName("아직 없는 직원을 가리키는 소속 줄이 있어도 그 직원은 여전히 '없음' 이다")
    void 소속_줄만_있는_직원은_없는_직원이다() {
        // given — SCIM 에서 조직이 직원보다 먼저 도착한 모양
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("아직없음"))).block();

        // when, then
        assertThat(정렬키들("USER#아직없음")).containsExactly("BELONGS_TO#GROUP#DEV002");
        assertThat(repository.findUser("아직없음").block()).isNull();
        assertThat(repository.loadAll().block().users()).doesNotContainKey("아직없음");
    }

    @Test
    @DisplayName("멤버 줄과 소속 줄은 GSI 에 실리지 않는다 — 직원·조직 열거에 섞이면 안 된다")
    void 멤버십_줄은_색인에_없다() {
        // given
        repository.saveUser(직원("kim")).block();
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();

        // when — 전체 열거는 GSI1 의 USER_INDEX/GROUP_INDEX 파티션을 훑는다
        var snapshot = repository.loadAll().block();

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim");
        assertThat(snapshot.groups()).containsOnlyKeys("DEV002");
    }

    @Test
    @DisplayName("직원을 지우면 직원 파티션이 통째로 빈다 — 남은 소속 줄도 함께")
    void 직원_삭제가_파티션을_비운다() {
        // given — 소속 줄만 남은 상태(중간 실패로 생길 수 있는 모양)를 직접 만든다
        repository.saveUser(직원("kim")).block();
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("DEV002", "백엔드팀")).block();
        repository.saveGroup(조직("DEV004", "고아팀", MemberRef.user("kim"))).block();
        지운다("GROUP#DEV004", "MEMBER#USER#kim");

        // when
        repository.deleteUser("kim").block();

        // then
        assertThat(정렬키들("USER#kim")).isEmpty();
    }
```

그리고 같은 파일의 거들기 절(아래쪽 `private static DirectoryGroup 조직(...)` 근처)에 더한다:

```java
    /** 파티션 하나의 정렬키 전부. 테이블에 실제로 무엇이 들어갔는지 직접 본다. */
    private List<String> 정렬키들(String pk) {
        var response = client.query(software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .consistentRead(true)
                .build()).join();
        return response.items().stream().map(item -> Attrs.str(item, Keys.SK)).toList();
    }

    private void 지운다(String pk, String sk) {
        client.deleteItem(software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build()).join();
    }
```

`java.util.List` import 가 없으면 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: 새 테스트 5개 FAIL (소속 줄이 아직 안 쓰인다), 기존 테스트는 PASS

- [ ] **Step 3: 저장소를 고친다**

`memberItem` 에서 GSI 키 두 줄을 지우고, 바로 아래에 소속 줄 아이템을 더한다:

```java
    private Map<String, AttributeValue> memberItem(String groupId, MemberRef member) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.groupPk(groupId)));
        item.put(Keys.SK, Attrs.s(Keys.memberSk(member)));
        item.put("addedAt", Attrs.s(Instant.now(clock).toString()));
        return item;
    }

    /**
     * 멤버 쪽 파티션에 적는 소속 줄. <b>GSI 키를 넣지 않는다</b> — 넣으면 직원·조직 열거
     * ({@code USER_INDEX}/{@code GROUP_INDEX})와 조회 API 결과에 섞인다.
     */
    private Map<String, AttributeValue> belongsToItem(MemberRef member, String groupId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.memberPk(member)));
        item.put(Keys.SK, Attrs.s(Keys.belongsToSk(groupId)));
        item.put("addedAt", Attrs.s(Instant.now(clock).toString()));
        return item;
    }
```

`saveGroup` 의 반환부(240~246행)를 바꾼다:

```java
                    // 소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다(설계 §5).
                    // 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저 — 중간에 실패해도
                    // "소속 줄만 남는" 안전한 방향으로만 어긋난다. 반대로 어긋나면
                    // 삭제가 그 조직을 못 찾아 권한이 남는다.
                    return Flux.fromIterable(떠난멤버)
                            .map(Keys::parseMemberSk)
                            .flatMap(ref -> deleteItem(Keys.groupPk(group.id()), Keys.memberSk(ref))
                                    .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(group.id()))),
                                    QUERY_CONCURRENCY)
                            .then(putItem(meta))
                            .then(Flux.fromIterable(새로온멤버)
                                    .flatMap(member -> putItem(belongsToItem(member, group.id()))
                                            .then(putItem(memberItem(group.id(), member))),
                                            QUERY_CONCURRENCY)
                                    .then());
```

`deleteGroup` 을 바꾼다:

```java
    /**
     * 조직 파티션을 비우고, <b>그 멤버들의 소속 줄까지</b> 지운다. 소속 줄을 남기면 역참조가
     * 그 조직을 후보로 계속 들고 오고(확인 단계가 걸러 내지만) 파티션에 영원히 쌓인다.
     */
    @Override
    public Mono<Void> deleteGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .collectList()
                .flatMap(sks -> {
                    List<MemberRef> members = sks.stream()
                            .filter(Keys::isMemberSk)
                            .map(Keys::parseMemberSk)
                            .toList();
                    return Flux.fromIterable(sks)
                            .flatMap(sk -> deleteItem(Keys.groupPk(groupId), sk), QUERY_CONCURRENCY)
                            .thenMany(Flux.fromIterable(members))
                            .flatMap(ref -> deleteItem(Keys.memberPk(ref), Keys.belongsToSk(groupId)),
                                    QUERY_CONCURRENCY)
                            .then();
                });
    }
```

`deleteUser` 를 바꾼다:

```java
    /** 직원 파티션을 통째로 비운다 — {@code META} 와 남아 있을 수 있는 소속 줄까지. */
    @Override
    public Mono<Void> deleteUser(String userId) {
        return queryPartition(Keys.userPk(userId))
                .map(item -> Attrs.str(item, Keys.SK))
                .flatMap(sk -> deleteItem(Keys.userPk(userId), sk), QUERY_CONCURRENCY)
                .then();
    }
```

`java.util.List` import 가 없으면 더한다.

- [ ] **Step 4: 테스트를 돌린다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS (새 테스트 5개 포함)

- [ ] **Step 5: 커밋·푸시**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java
git commit -m "feat: 소속 줄을 멤버 쪽 파티션에 함께 쓰고 삭제까지 정리한다"
git push
```

---

### Task 3: 역참조를 강한 일관성으로 바꾸고, 확인을 저장소 안으로 옮긴다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1·2 의 키 규칙과 소속 줄
- Produces:
  - 포트에서 `Mono<Boolean> containsMember(String, MemberRef)` **제거**
  - `Flux<String> findGroupIdsContaining(MemberRef ref)` 의 계약: **강한 일관성, 정확함**(멤버 줄이 실제로 있는 조직만)
  - `IncrementalSyncUseCase.affectedGroupHeadersOf` 가 확인 단계 없이 헤더만 읽는다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest.java` 에 더한다:

```java
    @Test
    @DisplayName("역참조는 소속 줄로 찾는다 — 막 추가된 멤버십도 즉시 보인다")
    void 역참조가_소속_줄로_찾는다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("DEV003", "플랫폼팀", MemberRef.user("kim"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then
        assertThat(groupIds).containsExactlyInAnyOrder("DEV002", "DEV003");
    }

    @Test
    @DisplayName("소속 줄만 남고 멤버 줄이 없으면 역참조에서 빠진다 — 중간 실패로 남은 찌꺼기")
    void 찌꺼기_소속_줄은_걸러진다() {
        // given — 멤버 줄만 지워 "소속 줄만 남은" 모양을 만든다
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        지운다("GROUP#DEV002", "MEMBER#USER#kim");
        assertThat(정렬키들("USER#kim")).contains("BELONGS_TO#GROUP#DEV002");

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then — 화면에 "속하지 않은 조직" 이 보이면 안 된다
        assertThat(groupIds).isEmpty();
    }

    @Test
    @DisplayName("하위 조직도 자기 상위 조직을 역참조로 찾는다")
    void 하위_조직의_역참조() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.group("DEV003"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.group("DEV003")).collectList().block();

        // then
        assertThat(groupIds).containsExactly("DEV002");
    }
```

`core` 쪽에서는 `IncrementalSyncUseCaseTest` 의 `낡은_역참조가_보고한_조직은_멤버줄_확인으로_걸러진다` 테스트를 **통째로 지운다** — 같은 성격의 검증이 위 `찌꺼기_소속_줄은_걸러진다` 로 옮겨간다. `IncrementalSyncReadScopeTest.퇴사에_동료를_읽지_않는다` 의 마지막 단언(92~95행 `containsMemberCalls` 블록)도 지운다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: 새 테스트 중 `찌꺼기_소속_줄은_걸러진다` FAIL (아직 GSI 로 찾으므로 빈 결과가 아니라 DEV002 가 나오거나, 소속 줄을 안 보므로 결과가 다르다)

- [ ] **Step 3: 저장소의 역참조를 바꾼다**

```java
    /**
     * 멤버 쪽 파티션의 소속 줄을 <b>강한 일관성</b>으로 읽고, 조직 쪽 멤버 줄이 실제로 있는지
     * 확인한 것만 돌려준다.
     *
     * <p><b>GSI 를 쓰지 않는다.</b> GSI1 은 최종 일관성이라 막 추가된 멤버십을 아직 모를 수 있고,
     * 그 창에 삭제가 들어오면 그 조직의 튜플과 멤버 줄이 남는다(설계 §1).
     *
     * <p><b>확인까지 여기서 한다.</b> 쓰기가 중간에 실패하면 소속 줄만 남을 수 있다. 부르는 쪽에
     * 확인을 맡기면 관리자 조회처럼 그대로 믿는 곳에서 "속하지 않은 조직" 이 보인다(설계 §6).
     */
    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(Keys.memberPk(ref)),
                        ":prefix", Attrs.s(Keys.BELONGS_TO_PREFIX)))
                .consistentRead(true)
                .build();

        return Paginator.queryAll(client, request)
                .map(item -> Keys.parseBelongsToSk(Attrs.str(item, Keys.SK)))
                .flatMap(groupId -> containsMember(groupId, ref)
                        .filter(Boolean::booleanValue)
                        .map(confirmed -> groupId), QUERY_CONCURRENCY);
    }
```

`containsMember` 는 `@Override` 를 떼고 **패키지 전용**(접근 제어자 없음)으로 바꾼다 — `private` 으로 만들면 이 메서드를 직접 부르는 기존 테스트 세 줄(`DynamoDbDirectoryStateRepositoryTest:308,309,316`)이 깨진다. 그 테스트들은 멤버 줄 확인 자체를 지키는 값 있는 테스트이므로 그대로 둔다. 자바독의 "GSI1 이라 최종 일관성" 설명은 "쓰기가 중간에 실패해 소속 줄만 남은 경우를 걸러낸다"로 고치고, 패키지 전용인 이유(위 테스트)를 한 줄 적는다.

- [ ] **Step 4: 포트와 유스케이스를 고친다**

`DirectoryStateRepository.java`:
- `containsMember` 선언과 자바독을 지운다.
- `findGroupIdsContaining` 의 자바독을 바꾼다:

```java
    /**
     * 역참조 — 이 멤버가 속한 조직들. SCIM 이 직원·조직을 삭제하거나 상위 조직을 찾을 때 쓴다.
     *
     * <p><b>강한 일관성이고 정확하다.</b> 구현은 멤버 쪽 파티션의 소속 줄을 읽고, 조직 쪽 멤버 줄이
     * 실제로 있는지 확인한 것만 돌려준다. 최종 일관성 인덱스를 쓰면 막 추가된 소속을 놓쳐 삭제가
     * 권한을 남긴다(설계 `2026-09-16-strong-membership-lookup-design.md` §1).
     */
    Flux<String> findGroupIdsContaining(MemberRef ref);
```

`IncrementalSyncUseCase.affectedGroupHeadersOf` (841~848행)를 바꾼다:

```java
    /**
     * 이 직원이 속한 모든 조직의 헤더. 멤버 목록이 필요 없는 {@link #upsertUser} 에서만 쓴다.
     *
     * <p>역참조가 강한 일관성이고 정확하므로(포트 계약 참고) 여기서 멤버십을 다시 확인하지 않는다.
     * 확인은 저장소 안에서 이미 끝났다.
     */
    private Mono<Set<GroupHeader>> affectedGroupHeadersOf(String userId) {
        return state.findGroupIdsContaining(MemberRef.user(userId))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .collect(LinkedHashSet<GroupHeader>::new, Set::add);
    }
```

클래스 자바독(54~58행 부근)에서 `containsMember` 를 언급하는 문장을 "역참조가 강한 일관성이라 그대로 믿는다"로 고친다. 쓰이지 않게 된 import 는 지운다.

- [ ] **Step 5: 페이크와 대역을 고친다**

`FakeStateRepository`: `staleGroupIdsContaining` 필드와 자바독, `containsMember` 메서드, `containsMemberCalls`, `ContainsMemberCall` 레코드를 지운다. `findGroupIdsContaining` 은 실제 멤버십에서만 유도하게 한다:

```java
    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        return Flux.fromIterable(groups.values())
                .filter(group -> group.members().contains(ref))
                .map(DirectoryGroup::id);
    }
```

`LdapInterruptedSyncScaleTest` 의 저장소 대역에서 `containsMember` 위임 한 줄을 지운다.

- [ ] **Step 6: 전부 돌린다**

Run: `./gradlew :storage-dynamodb:test :core:test :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS

- [ ] **Step 7: 커밋·푸시**

```bash
git add storage-dynamodb/src core/src app-ldap/src/test
git commit -m "feat: 역참조를 강한 일관성으로 바꾸고 확인을 저장소 안으로 옮긴다"
git push
```

---

### Task 4: "역참조가 색인을 쓰지 않는다" 를 못박는다

**Files:**
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReverseLookupQueryShapeTest.java`

**Interfaces:**
- Consumes: Task 3 의 `findGroupIdsContaining`
- Produces: 없음(테스트만)

**왜 이 테스트인가:** DynamoDB Local 은 GSI 를 즉시 반영해 지연 자체를 재현할 수 없다. 그래서 지연을 흉내 내는 대신 **보낸 요청의 모양**을 검사한다. 누가 GSI 조회로 되돌리면 이 테스트가 실패한다.

- [ ] **Step 1: 테스트를 쓴다**

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역참조가 <b>본문 테이블을 강한 일관성으로</b> 읽는지 요청 자체로 확인한다.
 *
 * <p>DynamoDB Local 은 GSI 를 즉시 반영하므로 "인덱스가 늦어서 못 봤다" 를 재현할 수 없다.
 * 재현 대신 요청의 모양을 못박는다 — 누군가 GSI 조회로 되돌리면 여기서 걸린다.
 */
class ReverseLookupQueryShapeTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("역참조 조회는 인덱스를 지정하지 않고 강한 일관성으로 읽는다")
    void 역참조는_색인을_쓰지_않는다() {
        // given
        List<QueryRequest> 보낸것 = new CopyOnWriteArrayList<>();
        DynamoDbAsyncClient 기록하는클라이언트 = 기록하는_클라이언트(보낸것);
        var repository = new DynamoDbDirectoryStateRepository(
                기록하는클라이언트, properties, Clock.systemUTC());
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        보낸것.clear();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then
        assertThat(groupIds).containsExactly("DEV002");
        assertThat(보낸것).isNotEmpty();
        assertThat(보낸것).allSatisfy(request -> {
            assertThat(request.indexName()).as("역참조가 인덱스를 쓰면 최종 일관성으로 돌아간다").isNull();
            assertThat(request.consistentRead()).as("강한 일관성으로 읽어야 한다").isTrue();
        });
    }

    private DynamoDbAsyncClient 기록하는_클라이언트(List<QueryRequest> 보낸것) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(builder -> builder.addExecutionInterceptor(
                        new ExecutionInterceptor() {
                            @Override
                            public void beforeExecution(Context.BeforeExecution context,
                                                        ExecutionAttributes attributes) {
                                if (context.request() instanceof QueryRequest query) {
                                    보낸것.add(query);
                                }
                            }
                        }))
                .build();
    }
}
```

- [ ] **Step 2: 돌린다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.ReverseLookupQueryShapeTest'`
Expected: PASS. 실패하면 인터셉터가 `QueryRequest` 를 못 받는 것이므로 `beforeMarshalling` 등 다른 훅으로 바꾸고, 바꾼 이유를 보고서에 적는다.

- [ ] **Step 3: 쓰기 순서를 못박는 테스트를 같은 파일에 더한다**

`ReverseLookupQueryShapeTest` 안에 더한다(같은 기록 클라이언트를 쓴다 — `PutItemRequest`/`DeleteItemRequest` 도 받도록 인터셉터를 넓힌다):

```java
    @Test
    @DisplayName("멤버를 넣을 때는 소속 줄이 먼저, 뺄 때는 멤버 줄이 먼저다")
    void 쓰기_순서가_고정된다() {
        // given
        List<String> 순서 = new CopyOnWriteArrayList<>();
        DynamoDbAsyncClient 기록하는클라이언트 = 정렬키를_기록하는_클라이언트(순서);
        var repository = new DynamoDbDirectoryStateRepository(
                기록하는클라이언트, properties, Clock.systemUTC());

        // when — 넣는다
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // then — 소속 줄이 멤버 줄보다 먼저 쓰인다. 반대면 삭제가 조직을 못 찾아 권한이 남는다
        assertThat(순서).containsSubsequence("BELONGS_TO#GROUP#DEV002", "MEMBER#USER#kim");

        // when — 뺀다
        순서.clear();
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", Set.of())).block();

        // then — 멤버 줄이 소속 줄보다 먼저 지워진다
        assertThat(순서).containsSubsequence("MEMBER#USER#kim", "BELONGS_TO#GROUP#DEV002");
    }
```

거들기로 더한다 — 쓰기 요청의 정렬키를 순서대로 모은다:

```java
    private DynamoDbAsyncClient 정렬키를_기록하는_클라이언트(List<String> 순서) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(builder -> builder.addExecutionInterceptor(
                        new ExecutionInterceptor() {
                            @Override
                            public void beforeExecution(Context.BeforeExecution context,
                                                        ExecutionAttributes attributes) {
                                if (context.request() instanceof PutItemRequest put) {
                                    순서.add(Attrs.str(put.item(), Keys.SK));
                                } else if (context.request() instanceof DeleteItemRequest delete) {
                                    순서.add(Attrs.str(delete.key(), Keys.SK));
                                }
                            }
                        }))
                .build();
    }
```

`PutItemRequest`·`DeleteItemRequest` import 를 더한다.

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.ReverseLookupQueryShapeTest'`
Expected: PASS

- [ ] **Step 4: 커밋·푸시**

```bash
git add storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ReverseLookupQueryShapeTest.java
git commit -m "test: 역참조가 색인을 쓰지 않는 것과 쓰기 순서를 못박는다"
git push
```

---

### Task 5: 변이로 증명한다

**Files:** 없음(확인 후 전부 되돌린다)

**Interfaces:**
- Consumes: Task 1~4 의 코드와 테스트

각 변이는 **하나씩** 넣고, 실패를 확인하고, 즉시 되돌린다. 되돌린 뒤 `git status --short` 가 비어 있는지 확인한다.

- [ ] **Step 1: 변이 A — 쓰기 순서를 뒤집는다**

`saveGroup` 의 새 멤버 블록에서 `putItem(belongsToItem(...)).then(putItem(memberItem(...)))` 의 순서를 바꾼다.

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.ReverseLookupQueryShapeTest'`
Expected: Task 4 Step 3 의 `쓰기_순서가_고정된다` FAIL
되돌린다: `git checkout -- storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`

- [ ] **Step 2: 변이 B — 확인 단계를 뺀다**

`findGroupIdsContaining` 의 `.flatMap(groupId -> containsMember(...))` 를 지우고 groupId 를 그대로 흘린다.
Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: `찌꺼기_소속_줄은_걸러진다` FAIL
되돌린다: `git checkout -- <같은 파일>`

- [ ] **Step 3: 변이 C — 역참조를 GSI 로 되돌린다**

`findGroupIdsContaining` 이 `indexName(Keys.GSI1)` 로 조회하게 바꾼다(멤버 줄에 GSI 키가 없으므로 결과는 비게 된다).
Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.ReverseLookupQueryShapeTest'`
Expected: FAIL (`indexName` 단언)
되돌린다: `git checkout -- <같은 파일>`

- [ ] **Step 4: 변이 D — 조직 삭제의 소속 줄 정리를 뺀다**

`deleteGroup` 에서 멤버들의 소속 줄을 지우는 `.flatMap(ref -> deleteItem(...))` 구간을 지운다.
Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: `조직_삭제가_소속_줄을_치운다` FAIL
되돌린다: `git checkout -- <같은 파일>`

- [ ] **Step 5: 되돌림 확인**

Run: `git status --short`
Expected: 변경 없음 (또는 Step 1 에서 새로 만든 `WriteOrderTest` 만 커밋된 상태)

Run: `./gradlew :storage-dynamodb:test :core:test`
Expected: PASS

각 변이의 실패 테스트 이름과 메시지 첫 줄을 보고서에 적는다.

---

### Task 6: 규모 측정과 기록 (메인 세션)

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-strong-membership-lookup-design.md` (§11)

- [ ] **Step 1: 기준값을 확인한다**

`cdc6c88` 기준 규모 테스트 소요 시간은 슬라이드 E 의 실측과 같은 값이다 — `docs/superpowers/specs/2026-09-11-harness-independent-expectation-design.md` §10 의 "후" 열을 기준으로 쓴다.

- [ ] **Step 2: 전체 스위트를 돌린다**

Run: `./gradlew test` (백그라운드, 최대 timeout)
Expected: 전부 통과

- [ ] **Step 3: 클래스별 소요 시간을 뽑는다**

`app-ldap/build/test-results/test/TEST-*.xml` 와 `app-scim/build/test-results/test/TEST-*.xml` 의 `testsuite time` 을 읽어 §10 의 값과 나란히 놓는다. 특히 `LdapScaleSyncCostTest`(최초 적재), `ScimScaleSyncCostTest`(최초 싱크), `ScimScaleScenarioTest`(대형 조직 교체·S3 경합).

- [ ] **Step 4: 스펙 §11 을 채운다**

`## 11. 실측` 의 문장을 표로 바꾼다: `| 테스트 클래스 | 전(초) | 후(초) | 차이 |`. 표 아래에 변이 A~D 의 결과(실패 테스트 이름과 메시지 첫 줄)를 적는다. 락 보유 시간이 눈에 띄게 늘었으면 §7 의 배치 검토를 "다음 후보" 로 한 줄 남긴다.

- [ ] **Step 5: 커밋·푸시**

```bash
git add docs/superpowers/specs/2026-09-16-strong-membership-lookup-design.md
git commit -m "docs: 소속 역참조 변경의 실측과 변이 결과"
git push
```
