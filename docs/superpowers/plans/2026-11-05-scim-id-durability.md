# SCIM id 내구성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 설정 `sync.id-from-external-id` 를 켜면 SCIM POST 의 id 를 `externalId` 에서 정해(UUIDv5), `mode=wipe`·PITR 복원 뒤 IdP 가 다시 보내도 같은 id 가 나온다. 저장하지 못한 새 조직 POST 는 자기가 쓴 줄을 되지운다.

**Architecture:**
- connector-scim 의 `ScimIds` 가 후보 id 를 만든다(켜짐 + `externalId` 있음 → UUIDv5, 아니면 v4). 처리기는 그 후보로 `DirectoryUser`·`DirectoryGroup` 을 만든다.
- core 의 `createUser`·`createGroup` 이 락 안에서 후보를 본다 — 비었으면 그대로, 같은 `externalId` 의 리소스가 있으면 409, 다른 `externalId` 면 무작위 id. 정한 id 를 `CreateResult` 로 돌려준다.
- 새 조직의 커밋 자리(`upsertGroupInternal` 의 `Commit`)가 일부 실패면 그 요청이 쓴 줄·결과를 모르는 줄을 지운다.
- app-scim 이 설정을 읽어 `ScimIds` 빈을 올린다. connector-scim 의 `ScimConfig` 가 `ObjectProvider` 로 받는다(없으면 무작위).

**Tech Stack:** Java 17, Spring Boot 3.5(WebFlux), Reactor, JUnit 5·AssertJ·Testcontainers. 새 의존성 없음(UUIDv5 는 `java.security.MessageDigest` 의 SHA-1).

**Spec:** `docs/superpowers/specs/2026-11-05-scim-id-durability-design.md` — 설계가 권위다. 이 계획과 부딪히면 설계를 따른다. 줄 번호는 `8bffb0d` 기준이다.

## Global Constraints

- **코드 관례.** 공개 API·타입은 영어 이름(`CreateResult`, `ScimIds`), 내부 메서드·변수는 한국어 이름이다. 시험 메서드는 한국어 이름 + `@DisplayName` 한국어 문장이고, 본문은 `// given / when / then`, 단언은 AssertJ 다. 주석 밀도·말투는 둘레 코드를 따른다(설계 절을 `(설계 2026-11-05 I3)` 처럼 단다).
- **Gradle.** 한 번에 하나만 돌린다. 구현 서브에이전트는 자기 과제 모듈의 시험만 돌린다(예: `./gradlew :core:test`). 전체 `test`·`scaleTest` 는 컨트롤러가 머지 전에 돌린다.
- **git.** `git stash` 금지. 바꾼 파일만 스테이징한다. 커밋 메시지는 한국어 `feat:`/`fix:`/`test:`/`docs:` 한 문장 + ` (id 내구성)` 이고, 끝줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 다.
- **원칙.** 요청마다 전원을 읽지 않는다(실제 규모 10만 명). OpenFGA Read 를 쓰지 않는다(판단은 Check, 되지울 줄은 쓰기 결과로 안다). 운영 배포 전이라 이관하지 않는다.
- **값(정확히, 설계 I1·I6):**

  | 대상 | 값 |
  |---|---|
  | 설정 | `sync.id-from-external-id`(필드 `idFromExternalId`, 기본 `false`) |
  | 네임스페이스(영구 고정) | `628efe45-04dd-4c33-82e2-f2d612044e98` |
  | 이름 | 직원 `"User:" + externalId`, 조직 `"Group:" + externalId` — UTF-8, 대소문자 그대로 |
  | 후보를 만들지 않는 `externalId` | `null`, 빈 문자열, 공백뿐(`isBlank()`) |
  | "같은 `externalId`" | 둘 다 공백이 아니고 `String.equals`(대소문자 가림) |
- **설계가 정한 문구(그대로):**

  | 경우 | 문구 |
  |---|---|
  | 후보의 직원이 같은 `externalId`(409) | `같은 externalId 의 직원이 이미 있다: <id>` |
  | 후보의 조직이 같은 `externalId`(409) | `같은 externalId 의 조직이 이미 있다: <id>` |
  | 기동 로그(INFO, 켰을 때만) | `SCIM POST 의 id 를 externalId 에서 정한다(sync.id-from-external-id=true) — externalId 가 IdP 의 영구 식별자여야 한다` |
- **로그에 `externalId` 값을 싣지 않는다**(물러섬 INFO, 되지우기 WARN 모두).

## 계획의 판정 (설계가 열어 둔 것, §13)

- **정한 id 를 돌려주는 길.** `createUser`·`createGroup` 이 `Mono<CreateResult>` 를 낸다(`record CreateResult(String id, IncrementalSyncResult result)`). `withLock` 은 `IncrementalSyncResult` 에 묶여 있어(떠난 요청의 경고 로그) 바꾸지 않는다 — 락 안에서 정한 id 를 `AtomicReference` 에 담고 `withLock(...)` 뒤에 `map` 으로 묶는다. 비용: 시험 호출부 몇 곳이 `.result()` 를 붙인다.
- **검사 순서는 한도 → 후보 → 표지판.** 설계 §3.1 은 후보를 "맨 먼저" 라 적었지만 한도(`한도를_본다`)는 읽기 없는 검사라 앞에 둔다. 둘 다 걸리는 요청이 400 을 받는다는 것만 다르다.
- **조직도 같은 후보 검사를 탄다.** 설계 I3 은 "조직은 표지가 앞에서 같은 결과를 낸다" 고 했다. 한 규칙으로 두면 같은 `externalId` 는 후보 검사가 409 를 내고(문구에 `externalId` 가 들어간다), 다르면 무작위다. 조직 후보는 **헤더만**(`findGroupHeader`) 읽는다 — 파티션을 읽지 않는다.
- **같은 `externalId` 판정은 둘 다 공백이 아닐 때만 참이다.** 후보가 무작위인 POST(꺼짐·`externalId` 없음)는 겹칠 일이 없다. 시험이 고정 id 로 겹치게 하면 무작위로 물러선다 — 그래서 지금의 "같은 id·같은 userName 은 409(`userName`)" 시험이 그대로 통과한다.
- **되지우기는 일부 실패한 결과(커밋 자리)만 덮는다.** 차단기 중단(`TupleWriteAbortedException`)·리스 상실·커밋 오류처럼 오류로 끝난 새 조직 POST 는 커밋 자리에 오지 않아 되지우지 않는다 — 차단기는 OpenFGA 가 연달아 실패한다는 뜻이라 지우기도 실패하기 쉽고, 리스를 잃으면 쓰면 안 된다. 남은 줄은 재적재가 지운다. 설계 §9 "남긴 것" 에 적는다.
- **보류 목록은 되지우지 않는다**(설계 §13 셋째 줄에 대한 답). 저장하지 않은 새 조직 G 가 넣은 보류 줄 가운데 G 가 상위인 줄은 원천(G 의 멤버 행)이 없어 다음 다시 보기가 버린다(`원천이_있다`). G 가 하위인 줄은 상위의 멤버 행이 원천이라 남는 것이 맞고, 같은 id 의 재시도가 같은 판정을 다시 한다. 설계 §9 에 적는다.
- **직원 POST 는 되지우지 않는다.** 결정적 id 의 직원 POST 는 그 id 를 멤버로 둔 조직이 남아 있으면 줄을 쓴다. 그 줄의 원천(조직의 멤버 행)은 남아 있으므로, 그 조직에서 빠질 때 지금 경로가 지운다 — 새지 않는다.

## Review Focus

- **큰 조직의 재POST.** 같은 `externalId` 로 다시 온 조직 POST 의 후보 검사가 조직 파티션(멤버 수천 개)을 읽으면 전역 락을 쥔 채 비용이 조직 크기를 따른다. 헤더만 읽어야 한다(Task 2).
- **공백뿐인 `externalId`.** 켜짐에서 `"  "` 이 같은 결정적 id 를 만들면 서로 다른 리소스가 한 id 를 다툰다. 무작위여야 한다(Task 1, Task 4).
- **대소문자만 다른 `externalId`.** 저장된 `Ext-1` 과 POST 의 `ext-1` 은 다른 리소스다(RFC 7643 caseExact). 409 가 아니라 무작위여야 한다(Task 2).
- **후보에서 물러선 POST 의 응답.** 응답의 `id`·`Location` 이 후보가 아니라 정한 id 여야 한다 — 아니면 IdP 가 남의 id 로 PATCH 한다(Task 4).
- **되지운 뒤 같은 id 의 재시도.** 첫 시도에 쓴 멤버가 재시도 본문에서 빠졌으면 그 줄이 남지 않아야 한다 — I11 의 존재 이유다(Task 3).

---

### Task 1: 후보 id 함수 `ScimIds` (설계 I1·I6)

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimIds.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimIdsTest.java`

**Interfaces:**
- Produces:
  - `public final class ScimIds`
  - `static final UUID NAMESPACE` — 위 표의 값
  - `public static ScimIds random()` — 늘 v4
  - `public static ScimIds fromExternalId()` — `externalId` 가 공백이 아니면 v5, 아니면 v4
  - `public String user(String externalId)`, `public String group(String externalId)` — `UUID.toString()`(소문자, 하이픈)
  - `static UUID nameBased(UUID namespace, String name)` — RFC 9562 §5.5. 패키지 공개(시험용)

- [ ] **Step 1: 실패하는 시험을 쓴다** — `ScimIdsTest`:

```java
@Test @DisplayName("RFC 9562 의 시험 값을 낸다 — DNS 네임스페이스와 www.example.com")
void RFC_시험_값() {
    assertThat(ScimIds.nameBased(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com"))
            .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
}

@Test @DisplayName("켜면 같은 externalId 에서 늘 같은 id 가 나온다 — 네임스페이스가 바뀌면 이 값이 깨진다")
void 고정_값() {
    var ids = ScimIds.fromExternalId();
    assertThat(ids.user("00u1abcd")).isEqualTo("46af28dd-ee3f-5eed-9506-e28af7d370de").isEqualTo(ids.user("00u1abcd"));
    assertThat(ids.group("00u1abcd")).isEqualTo("2481ef44-a4ea-5cfe-95f6-9d2420431168");
    assertThat(ids.user("00U1ABCD")).isEqualTo("8ea0c031-99b6-59cd-976c-78d72b50f428");   // 대소문자를 가린다
    assertThat(ids.user("한글-외부id")).isEqualTo("cb554ee8-d5a6-57fa-89db-4a3c9f9c80a1"); // UTF-8
    UUID 결정적 = UUID.fromString(ids.user("00u1abcd"));
    assertThat(결정적.version()).isEqualTo(5);
    assertThat(결정적.variant()).isEqualTo(2);
}

@Test @DisplayName("켜도 externalId 가 없거나 비었거나 공백뿐이면 무작위다")
void 켜도_없으면_무작위() {
    var ids = ScimIds.fromExternalId();
    for (String 없음 : new String[]{null, "", "  "}) {
        assertThat(UUID.fromString(ids.user(없음)).version()).isEqualTo(4);
        assertThat(ids.user(없음)).isNotEqualTo(ids.user(없음));
        assertThat(UUID.fromString(ids.group(없음)).version()).isEqualTo(4);
    }
}

@Test @DisplayName("끄면 externalId 가 있어도 무작위다")
void 끄면_무작위() {
    var ids = ScimIds.random();
    assertThat(UUID.fromString(ids.user("00u1abcd")).version()).isEqualTo(4);
    assertThat(ids.user("00u1abcd")).isNotEqualTo(ids.user("00u1abcd"));
    assertThat(UUID.fromString(ids.group("00u1abcd")).version()).isEqualTo(4);
}
```

- [ ] **Step 2: 실패를 본다** — `./gradlew :connector-scim:test --tests '*ScimIdsTest'` → 컴파일 실패(`ScimIds` 없음).

- [ ] **Step 3: `ScimIds` 를 만든다.** `nameBased` 는 SHA-1(네임스페이스 16바이트 빅엔디언 + 이름 UTF-8)의 앞 16바이트에서 6번 바이트를 `& 0x0f | 0x50`, 8번 바이트를 `& 0x3f | 0x80` 으로 맞춘다. 클래스 자바독에 I2(기본 꺼짐, 영구 식별자일 때만)·I6(네임스페이스는 바꾸지 않는다 — 바꾸면 그 뒤 모든 후보가 달라진다)를 적는다.

- [ ] **Step 4: 통과를 본다** — 같은 명령, PASS.

- [ ] **Step 5: 커밋** — `feat: externalId 에서 SCIM 후보 id 를 정하는 ScimIds(UUIDv5, 고정 네임스페이스) (id 내구성)`

---

### Task 2: 락 안의 후보 검사와 `CreateResult` (설계 I3·I4·I5)

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/CreateResult.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` — `createUser`(371-386), `createGroup`(480-492)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/DirectoryConflictException.java` — 자바독
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java:50-51`, `ScimGroupHandler.java:44-45` — 새 반환형만 맞춘다(`created.id()`, `created.result()`)
- Modify(호출부): `core/src/test/.../usecase/` 의 `IncrementalSyncAuditTest`·`IncrementalSyncCreateUniquenessTest`·`IncrementalSyncCycleTest`·`IncrementalSyncDriftTest`·`IncrementalSyncKeyLimitTest`·`IncrementalSyncReadScopeTest`·`IncrementalSyncRevocationIntentTest`·`IncrementalSyncUniqueMarkerTest`·`IncrementalSyncWriteDecisionTest`·`WriteDecisionLockInvariantTest`, `storage-dynamodb/src/test/.../TwoInstanceWriteTest` — 결과를 읽는 곳만 `.result()` 를 붙인다
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCreateIdTest.java`

**Interfaces:**
- Produces:
  - `public record CreateResult(String id, IncrementalSyncResult result)` — `id` 는 락 안에서 정한 id
  - `public Mono<CreateResult> createUser(DirectoryUser user)` — `user.id()` 는 후보
  - `public Mono<CreateResult> createGroup(DirectoryGroup group)` — `group.id()` 는 후보

- [ ] **Step 1: 실패하는 시험을 쓴다** — `IncrementalSyncCreateIdTest`. 준비는 `IncrementalSyncCreateUniquenessTest` 와 같다(가짜 저장소·writer·checker·lock). 직원은 `new DirectoryUser(id, externalId, userName, userName, null, true)` 로 만든다.

| 시험 | given | when | then |
|---|---|---|---|
| `후보가_비어_있으면_그_id_로_만든다` | — | `createUser(c-1, ext-1, kim)` | `CreateResult.id() == "c-1"`, `fullyApplied`, `state.users` 에 `c-1` |
| `후보에_같은_externalId_의_직원이_있으면_409_이고_아무것도_쓰지_않는다` | `createUser(c-1, ext-1, kim)` | `createUser(c-1, ext-1, kim2)` | `DirectoryConflictException`, 메시지 `같은 externalId 의 직원이 이미 있다: c-1`. `state.users` 는 `c-1` 하나이고 `userName` 은 `kim`. `state.uniqueMarkers.get(UniqueKind.USER_NAME)` 에 `kim2` 키가 없다. `writer.appliedDeltas` 의 크기가 when 전과 같다 |
| `후보에_externalId_가_다른_직원이_있으면_무작위_id_로_만들고_기존_직원은_그대로다` | `createUser(c-1, ext-OLD, kim)` | `createUser(c-1, ext-NEW, park)` | 결과 id 가 `c-1` 이 아니고 `UUID.fromString(id).version() == 4`. `state.users` 에 둘 다. `c-1` 의 `userName` 은 `kim` |
| `externalId_대소문자만_다르면_같은_리소스가_아니다` | `createUser(c-1, Ext-1, kim)` | `createUser(c-1, ext-1, park)` | 409 가 아니다. 결과 id 가 v4, 둘 다 있다 |
| `externalId_없는_후보가_차_있으면_무작위_id_로_만든다` | `createUser(c-1, null, kim)` | `createUser(c-1, null, park)` | 결과 id 가 v4, 둘 다 있다 |
| `조직_후보에_같은_externalId_의_조직이_있으면_409다` | `createGroup(g-1, DEV001, "개발본부", {})` | `createGroup(g-1, DEV001, "덮어쓴 조직", {})` | `DirectoryConflictException`, 메시지 `같은 externalId 의 조직이 이미 있다: g-1`. `g-1` 의 `displayName` 은 `개발본부` |
| `조직_후보에_externalId_가_다른_조직이_있으면_무작위_id_로_만들고_파티션을_읽지_않는다` | `createGroup(g-1, DEV001, …)`, 그 뒤 `state.findGroupCalls.clear()` | `createGroup(g-1, DEV999, "새 조직", {})` | 결과 id 가 v4, `state.groups` 에 둘 다, `g-1` 은 그대로. `state.findGroupHeaderCalls` 에 `g-1` 이 있고 `state.findGroupCalls` 에 `g-1` 이 없다 |

- [ ] **Step 2: 실패를 본다** — `./gradlew :core:test --tests '*IncrementalSyncCreateIdTest'` → 컴파일 실패(`CreateResult` 없음).

- [ ] **Step 3: 후보 검사를 넣는다.**
  - `createUser`: `withLock` 안에서 한도 → `state.findUser(후보)` → (없음) 후보 그대로 / (같은 `externalId`) `DirectoryConflictException` / (다름) `user.withId(UUID.randomUUID().toString())` → 정한 직원으로 지금의 표지판·`upsertUserInternal`·`표지판을_정리한다`. 정한 id 를 `AtomicReference<String>` 에 담고 `withLock(...).map(result -> new CreateResult(정한.get(), result))`.
  - `createGroup`: 같은 모양, 읽기는 `state.findGroupHeader(후보)`. 물러서면 `new DirectoryGroup(새 id, externalId, displayName, members)`.
  - 같은 `externalId` 판정은 static 한 곳(`같은_externalId(String 저장본, String 요청)`)에 둔다.
  - 물러설 때 INFO 한 줄 — 리소스 종류(`직원`/`조직`), 후보 id, 정한 id. `externalId` 값은 싣지 않는다.
  - `createUser` 자바독의 "아이디는 서버가 발급한 UUID 라 겹칠 일이 없어 보지 않는다 … 지운 직원의 아이디가 다시 쓰이지 않아 남은 권한을 물려받지 않는다(점검 M7)" 를 고친다: 후보를 락 안에서 본다(I3). 결정적 id 면 지운 직원의 id 가 같은 `externalId` 에 다시 쓰인다 — I2 의 전제에서 같은 사람이다(I4). 삭제가 일부 실패하면 기록이 비활성으로 남아 같은 `externalId` 의 POST 는 409 다. `createGroup` 자바독도 같은 뜻으로 고친다.
  - `DirectoryConflictException` 자바독의 "아이디는 서버가 발급하므로 아이디 겹침으로는 생기지 않는다" 를 "생성 후보의 리소스가 같은 `externalId` 로 이미 있을 때도 던진다(설계 2026-11-05 I3)" 로 고친다.
  - 처리기 두 곳은 `created -> respond(HttpStatus.CREATED, created.id(), created.result(), projection)` 로만 바꾼다(후보 만들기는 Task 4).

- [ ] **Step 4: 기존 시험을 맞춘다.** 결과를 읽는 호출부에 `.result()` 를 붙인다. 읽기 계측(`findUserCalls`·`findGroupHeaderCalls`)의 기대값이 후보 검사 읽기 하나로 바뀌어 깨지면, 기대값을 그 읽기만큼 고치고 시험 주석에 까닭(후보 검사, 설계 I3)을 단다 — 단언을 지우거나 느슨하게 하지 않는다.

- [ ] **Step 5: 통과를 본다** — `./gradlew :core:test`, 그다음 `./gradlew :connector-scim:test`, 그다음 `./gradlew :storage-dynamodb:test --tests '*TwoInstanceWriteTest'` 를 하나씩. 모두 PASS.

- [ ] **Step 6: 커밋** — `feat: 생성 후보 id 를 락 안에서 본다 — 같은 externalId 면 409, 다르면 무작위, 정한 id 를 CreateResult 로 돌려준다 (id 내구성)`

---

### Task 3: 저장하지 못한 새 조직 POST 의 되지우기 (설계 I11)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` — `upsertGroupInternal` 의 `Commit`(461-464), `upsertGroup` 자바독의 "아직 없던 조직은 …" 문단(434-438)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncNewGroupUndoTest.java`

**Interfaces:**
- Consumes: Task 2 의 `createGroup(...)` → `Mono<CreateResult>`
- Produces: 없음(내부). 새 private 메서드 `Mono<Void> 새_조직이_쓴_줄을_되지운다(String groupId, TupleWriteResult result, LockLease lease, AuditChanges 기록)` — 오류를 내지 않는다

- [ ] **Step 1: 실패하는 시험을 쓴다** — `IncrementalSyncNewGroupUndoTest`. 직원 `u-1`·`u-2`·`u-3` 를 먼저 만들어 둔다(`createUser`). `dm(u)` = `RelationTuple.directMember(u, "g-new")`. 조직은 `new DirectoryGroup("g-new", "grp-new", "새 조직", 멤버들)`.

| 시험 | given | when | then |
|---|---|---|---|
| `일부_실패한_새_조직은_쓴_줄과_결과를_모르는_줄을_되지운다` | `writer.failFor(dm(u-2)::equals)`, `writer.lostResponseFor(dm(u-3)::equals)` | `createGroup(g-new, {u-1,u-2,u-3})` | `result().fullyApplied()` 거짓, `state.groups` 에 `g-new` 없음, `writer.stored` 에 `dm(u-1)`·`dm(u-2)`·`dm(u-3)` 모두 없음 |
| `되지운_뒤_같은_id_의_재시도에서_빠진_멤버의_줄이_남지_않는다` | 첫 시도 `{u-1,u-2}` 에 `failFor(dm(u-2)::equals)` → 그 뒤 `failFor(t -> false)` | 같은 id 로 `createGroup(g-new, {u-2})` | `fullyApplied`, `state.groups` 에 `g-new`, `writer.stored` 에 `dm(u-2)` 있고 `dm(u-1)` 없음 |
| `되지우기의_줄이_실패하면_줄은_남고_결과는_부분_반영_그대로다` | `failFor(t -> t.equals(dm(u-2)) \|\| 지우기만인_델타(writer))` — 도우미는 `writer.appliedDeltas` 의 마지막 델타(지금 적용 중인 것)의 `toWrite()` 가 비었는지 본다 | `createGroup(g-new, {u-1,u-2})` | 예외 없이 `fullyApplied` 거짓, `g-new` 없음, `writer.stored` 에 `dm(u-1)` 있음 |
| `되지우기가_오류로_끝나도_요청은_부분_반영으로_끝난다` | `failFor(dm(u-2)::equals)`, `writer.abortWhen(d -> d.toWrite().isEmpty() && !d.toDelete().isEmpty())` — 가짜는 적용한 뒤 멈추므로 줄은 지워진다. 오류를 삼키는지만 본다 | `createGroup(g-new, {u-1,u-2})` | 예외 없이 `fullyApplied` 거짓, `g-new` 없음 |
| `확정_실패뿐이면_되지울_줄이_없어_더_쓰지_않는다` | `failFor(t -> true)`, `int 전 = writer.appliedDeltas.size()` | `createGroup(g-new, {u-1})` | `writer.appliedDeltas.size() == 전 + 1` |
| `있던_조직의_일부_실패는_되지우지_않는다` | `createGroup(g-new, {})`, `failFor(dm(u-2)::equals)` | `upsertGroup(g-new, {u-1,u-2})` | `writer.stored` 에 `dm(u-1)` 있음, `state.groups.get("g-new").members()` 에 `u-1` 있음 |

- [ ] **Step 2: 실패를 본다** — `./gradlew :core:test --tests '*IncrementalSyncNewGroupUndoTest'` → 앞의 둘이 FAIL(`dm(u-1)` 이 남는다).

- [ ] **Step 3: 되지우기를 넣는다.** `Commit` 의 `existing.isEmpty() && result.hasFailure()` 자리에서 `새_조직이_쓴_줄을_되지운다` 를 부른다:
  - 지울 줄 = `result.written()` ∪ `result.uncertain()`. 비면 아무것도 하지 않는다.
  - `리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")` 뒤 `writer.apply(new TupleDelta(Set.of(), 지울것), 기록::tuples)` — 지우기는 "없으면 무시" 다(OpenFGA 어댑터가 늘 `on_missing=IGNORE`).
  - 오류(리스 확인 포함)이거나 결과에 실패가 있으면 WARN 한 줄(조직 id, 못 지운 줄 수, "재적재(mode=tuples)가 지운다") 을 남기고 끝낸다. 요청의 결과(부분 반영, 503)는 바뀌지 않는다.
  - 자바독: 왜 지우나(설계 I11 — 결정적 id 의 재시도가 같은 id 로 와서 그 사이 빠진 멤버의 줄이 새 그림의 후보에 없다), 무엇을 덮지 않나(오류로 끝난 요청, 보류 목록 — 위 "계획의 판정").
  - `upsertGroup` 자바독의 "아직 없던 조직은 하나라도 실패하면 레코드 자체를 만들지 않는다" 뒤에 "그리고 그 요청이 쓴 줄을 되지운다" 를 잇는다.

- [ ] **Step 4: 통과를 본다** — `./gradlew :core:test`, PASS.

- [ ] **Step 5: 커밋** — `feat: 저장하지 못한 새 조직 POST 가 쓴 줄과 결과를 모르는 줄을 되지운다 (id 내구성)`

---

### Task 4: 처리기가 후보를 만들고 정한 id 로 답한다 (설계 §3.1)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java` — 생성자, `create`, `respond` 자바독(120-124)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java` — 생성자, `create`, `respond` 자바독(100-105)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java` — 두 처리기 빈
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimIdIssueTest.java`

**Interfaces:**
- Consumes: Task 1 의 `ScimIds`, Task 2 의 `CreateResult`
- Produces:
  - `public ScimUserHandler(DirectoryQueryRepository query, IncrementalSyncUseCase sync, IgnoredAttributeObserver ignoredAttributes, ScimIds ids)` — 기존 두 생성자는 `ScimIds.random()` 으로 넘긴다
  - `public ScimGroupHandler(DirectoryStateRepository state, DirectoryQueryRepository query, IncrementalSyncUseCase sync, MemberTypeResolver memberTypes, ScimIds ids)` — 기존 생성자는 `ScimIds.random()`
  - `ScimConfig` 의 두 빈이 `ObjectProvider<ScimIds> ids` 를 받아 `ids.getIfAvailable(ScimIds::random)` 을 넘긴다(Task 5 가 빈을 올린다)

- [ ] **Step 1: 실패하는 시험을 쓴다** — `ScimIdIssueTest`. 라우터 준비는 `ScimUserHandlerTest` 의 것을 따르되 처리기에 `ScimIds` 를 넘긴다(켜짐 `fromExternalId()`, 꺼짐 `random()`). 결정적 값은 Task 1 의 고정 값이다.

| 시험 | 준비 | 요청 | then |
|---|---|---|---|
| `꺼짐이면_externalId_가_있어도_무작위_id_다` | 꺼짐 | POST /Users `externalId "00u1abcd"` | 201, 본문 `id` 가 v4 이고 `46af28dd-…` 가 아니다 |
| `켜면_직원_id_가_externalId_에서_나온다` | 켜짐 | POST /Users `externalId "00u1abcd"` | 201, 본문 `id` == `46af28dd-ee3f-5eed-9506-e28af7d370de`, `Location` 이 `/scim/v2/Users/46af28dd-ee3f-5eed-9506-e28af7d370de` 로 끝난다 |
| `켜면_조직_id_가_externalId_에서_나온다` | 켜짐 | POST /Groups `externalId "00u1abcd"`, 멤버 없음 | 201, `id` == `2481ef44-a4ea-5cfe-95f6-9d2420431168`, `Location` 도 |
| `켜도_externalId_가_없거나_공백뿐이면_무작위다` | 켜짐 | POST /Users `externalId` 없음, 다른 userName 으로 `"  "` | 둘 다 201, `id` 가 v4 |
| `켜고_같은_externalId_의_두_번째_직원은_409_uniqueness_다` | 켜짐, POST `externalId "00u1abcd"` userName `kim` | POST `externalId "00u1abcd"` userName `park` | 409, 본문 `scimType` == `uniqueness`. GET `46af28dd-…` 의 `userName` 은 `kim` |
| `후보에서_물러서면_정한_id_로_답한다` | 켜짐, POST A(`externalId "00u1abcd"`, `kim`) → PUT A 를 `externalId "00u9zzzz"` 로 | POST B(`externalId "00u1abcd"`, `park`) | 201, 본문 `id` 가 v4 이고 `46af28dd-…` 가 아니며, `Location` 이 그 `id` 로 끝난다. GET `46af28dd-…` 의 `userName` 은 `kim` |

- [ ] **Step 2: 실패를 본다** — `./gradlew :connector-scim:test --tests '*ScimIdIssueTest'` → 컴파일 실패(생성자 없음).

- [ ] **Step 3: 처리기를 바꾼다.**
  - `create` 의 `UUID.randomUUID().toString()` 을 `ids.user(scim.externalId())`·`ids.group(scim.externalId())` 로 바꾼다. 주석: id 는 서버가 정한다(RFC 7643 §3.1) — 후보는 `ScimIds`, 최종은 락 안의 유스케이스(설계 2026-11-05 I3).
  - `respond` 자바독의 POST 재시도 설명을 고친다. 직원: 결정적 id 면 그 id 를 멤버로 둔 조직이 남아 있을 때 줄을 쓴다 — 그 줄의 원천(조직의 멤버 행)이 남아 있어 빠질 때 지운다. 응답을 잃은 POST 의 재시도는 같은 `userName` 이면 409, 켜졌고 같은 `externalId` 면 409 다. 조직: 실패한 새 조직은 저장하지 않고 그 요청이 쓴 줄을 되지운다(설계 I11). 켜졌으면 재시도가 같은 id 로 온다.
  - `ScimConfig` 두 빈에 `ObjectProvider<ScimIds>` 를 단다. 빈 자바독: 없으면 무작위(지금 동작), app-scim 이 `sync.id-from-external-id` 로 올린다.

- [ ] **Step 4: 통과를 본다** — `./gradlew :connector-scim:test`, PASS.

- [ ] **Step 5: 커밋** — `feat: SCIM POST 가 ScimIds 로 후보 id 를 만들고 유스케이스가 정한 id 로 답한다 (id 내구성)`

---

### Task 5: 설정·배선·종단 시험 (설계 §3.2·§3.3·§4)

**Files:**
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncProperties.java` — 필드 `idFromExternalId`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java` — `ScimIds` 빈
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncPropertiesTest.java`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimIdDurabilityEndToEndTest.java`

**Interfaces:**
- Consumes: Task 1 의 `ScimIds`, Task 4 의 `ScimConfig` 가 받는 `ObjectProvider<ScimIds>`
- Produces: `ScimSyncProperties#isIdFromExternalId()`, `@Bean public ScimIds scimIds(ScimSyncProperties properties)`

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `ScimSyncPropertiesTest` 에 `id_는_기본으로_externalId_에서_정하지_않는다`: `assertThat(new ScimSyncProperties().isIdFromExternalId()).isFalse()`.
  - `ScimIdDurabilityEndToEndTest` — `ScimHierarchyEndToEndTest` 의 머리(컨테이너·`@DynamicPropertySource`·`@ActiveProfiles("test")`·순서·`SyncJobClient`)를 따르고 `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "sync.id-from-external-id=true")` 다. 기대 id 는 `ScimIds.fromExternalId().user("00u-kim")`·`.group("grp-team")` 로 계산한다. 직원 kim 은 `externalId "00u-kim"`, `userName "kim@corp.com"`. 조직 TEAM 은 `externalId "grp-team"`, 멤버 `[kim]`.

| 순서 | 시험 | 하는 일 | then |
|---|---|---|---|
| 1 | `켜면_externalId_에서_id_가_나온다` | kim·TEAM POST | 응답 id 가 기대 id, `user:<kim>` `member` `group:<TEAM>` Check 참 |
| 2 | `wipe_뒤_같은_본문으로_다시_보내면_같은_id_다` | `mode=wipe&confirm=<테이블>` 끝까지 → 같은 본문 둘을 다시 POST | 응답 id 가 1 과 같다, Check 참 |
| 3 | `기록을_잃은_뒤_다시_보내면_같은_id_이고_장부_줄이_이어진다` | `state.deleteUser(kim).block()`(장부 줄은 둔다) → kim 다시 POST → TEAM 에 kim 을 PATCH `add` | POST 201 이고 id 가 같다, PATCH 204, Check 참. `mode=tuples` 끝까지 → SUCCEEDED, Check 여전히 참 |
| 4 | `같은_externalId_로_이름만_다른_직원은_409_다` | `externalId "00u-kim"`, `userName "kim2@corp.com"` POST | 409, `scimType` `uniqueness`. GET kim 의 `userName` 은 `kim@corp.com` |

- [ ] **Step 2: 실패를 본다** — `./gradlew :app-scim:test --tests '*ScimSyncPropertiesTest'` → 컴파일 실패. 그다음 `./gradlew :app-scim:test --tests '*ScimIdDurabilityEndToEndTest'` → 1 이 FAIL(id 가 무작위).

- [ ] **Step 3: 설정과 빈을 만든다.**
  - `ScimSyncProperties` 에 `private boolean idFromExternalId = false;` 와 자바독: 켜면 `externalId` 가 있는 POST 의 id 를 그 값에서 정한다(설계 2026-11-05 I1). `externalId` 가 IdP 의 영구 식별자일 때만 켠다 — Entra 는 직원 매핑을 `objectId` → `externalId` 로 바꾼 뒤(기본 `mailNickname` 은 다시 쓰일 수 있다), 매칭 속성은 `externalId` 우선순위 1(I2). 켜기 전에 만든 리소스는 이관하지 않는다(I7). `application.yml` 에는 넣지 않는다(기본값의 출처는 이 클래스 — 클래스 자바독의 약속).
  - `ScimUseCaseConfig#scimIds`: 켜졌으면 위 표의 기동 로그를 INFO 로 남기고 `ScimIds.fromExternalId()`, 아니면 `ScimIds.random()`.

- [ ] **Step 4: 통과를 본다** — 위 두 명령, PASS. 그다음 `./gradlew :app-scim:test --tests '*ScimEndToEndTest'` 로 꺼짐(기본) 경로가 그대로인지 본다.

- [ ] **Step 5: 커밋** — `feat: sync.id-from-external-id 설정과 종단 시험 — wipe·기록 유실 뒤 같은 id (id 내구성)`

---

### Task 6: 문서 (설계 I10)

**Files:** `README.md`, `docs/onboarding/` 의 02·04·06·07·11·12·13·15

**Interfaces:**
- Consumes: Task 1~5 의 동작. 기준 커밋은 Task 5 의 커밋(`git log -1 --format=%h` — 이 과제를 시작할 때의 HEAD)이고 괄호는 `(scim-id-durability, main 에 머지됨)` 이다.

- [ ] **Step 1: README 를 고친다.**
  - 97행 "불변 id는 이름에서 만들지 않는다" — SCIM id 는 기본 무작위 UUID, 켜면 `externalId` 에서 정하는 UUIDv5(이름이 아니라 IdP 의 영구 식별자에서). 
  - 1164행 "`id` 는 서버가 발급한다" — 후보·락 안 검사(같은 `externalId` 면 409, 다르면 무작위)·켜는 조건(Entra `objectId` 매핑과 매칭 우선순위 1, Okta 는 앱이 Okta 사용자 id 를 싣는 경우)·켜지 않으면 지금과 같음.
  - "운영 배포" 의 설정 표(318행 부근)에 `sync.id-from-external-id`(기본 `false`, 운영 권장은 IdP 매핑을 확인한 뒤 `true`).
  - 584행 "⚠️ `mode=wipe`" 와 PITR(346행 부근) — 켜져 있으면 재프로비저닝이 같은 id 를 다시 만든다. PITR 은 "같은 id" 까지만 약속하고, IdP 가 멤버십을 다시 보냈는지 확인한 뒤 맨 마지막에 `mode=tuples`(설계 §3.2).
- [ ] **Step 2: 온보딩을 고친다.**
  - 13 §2.1(104행 부근)과 491행 표 — "복구 뒤 id 보존" 을 남김에서 "켤 수 있는 설정으로 고침, `externalId` 없는 리소스는 보존하지 않음" 으로. 남는 것: 벤더 동작 미확인, 매핑이 맞는지 서버가 모름(설계 §6).
  - 13 §2.7·02 용어집 "고아 튜플"(1169-1171행) — 실패한 새 조직 POST 의 줄은 이제 되지운다. 오류로 끝난 요청과 되지우기 실패만 남고 재적재가 지운다.
  - 12 §3.4(240행), §3.7(273행), §5.5(431-445행 — "새 UUID(Y)" 서술을 켜짐/꺼짐으로 나누고 되지우기를 적는다), §8.4(674-680행 — 켜져 있으면 권한을 묻는 앱의 id 가 낡지 않는다).
  - 11 §1.5 `sync.*`(101행) 에 설정 한 줄.
  - 02 용어집 "불변 id"(298행), 04(177행), 06(65·226·267·712·999행), 07(420행) 의 "POST 마다 `UUID.randomUUID()`" 서술을 "후보는 `ScimIds`(기본 무작위, 켜면 `externalId` 의 UUIDv5), 최종은 락 안의 유스케이스" 로.
  - 15 결정 색인 — 106행을 고치고 I1·I2·I3·I4·I11 행을 더한다(결정, 이유 한 줄, 설계 `2026-11-05` 절).
  - 고친 문서 머리의 `기준 커밋:` 줄을 올린다. 건드리지 않은 문서는 그대로 둔다.
- [ ] **Step 3: 확인한다** — `grep -n 'randomUUID' README.md docs/onboarding/*.md` 의 남은 줄이 모두 "기본(꺼짐)" 이나 무관한 것(인스턴스 `holder` 등)인지 본다. 문서 안 링크 앵커(`#55-…`)가 바뀐 제목과 맞는지 본다.
- [ ] **Step 4: 커밋** — `docs: SCIM id 내구성 — 켤 수 있는 결정적 id, 후보 검사, 새 조직 POST 되지우기, 복구 절차 (id 내구성)`

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 다음 `./gradlew cleanScaleTest scaleTest` — 하나씩. 규모 시험은 POST 마다 GetItem 하나가 늘 뿐이라 새로 두지 않는다(설계 §4). 읽기 수를 세는 규모 시험이 깨지면 그 시험의 기대값을 후보 검사 읽기만큼 고치고 까닭을 단다.
- 설계 §9 를 채운다 — 검증 결과, "계획의 판정" 다섯 줄, 남긴 것(오류로 끝난 새 조직 POST, 벤더 미확인).
