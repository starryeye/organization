# SCIM 조직 계층 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 배포에서 운영자가 조직마다 상위 조직 하나를 지정한다(`PUT·DELETE /admin/organizations/{orgCode}/parent`). 그 지정 연결이 IdP 가 보낸 하위 조직 연결과 합쳐져 `child` 튜플이 되고, 상위 조직 롤업이 생긴다.

**Architecture:**
- 지정 연결은 `DirectorySnapshot` 의 세 번째 칸(`Set<GroupEdge> assignedEdges`)으로 흐른다. `TupleMapper` 가 멤버 행의 하위 조직 연결과 합친다. `DirectoryGroup` 은 바꾸지 않는다.
- 저장은 하위 조직 파티션의 정본 `ASSIGNED_PARENT` 와 상위 조직 파티션의 역색인 `ASSIGNED_CHILD#<하위>` 다. 멤버 행·META 와 따로 둔다.
- 지정 요청은 SCIM 쓰기와 같은 `IncrementalSyncUseCase.withLock` → `diffAndApply` 를 탄다. 감사 원인만 새 종류(`HIERARCHY_REQUEST`)다.
- IdP 경로(하위 조직 빼기·조직 지우기·보류 다시 보기·순환 판정)는 지정 연결을 원천으로 안다.

**Tech Stack:** Java 17(switch·레코드 패턴 없음), Spring Boot 3.5(WebFlux), Reactor, AWS SDK v2 DynamoDB, OpenFGA Java SDK, JUnit 5·AssertJ·Testcontainers, spring-security-test.

**Spec:** `docs/superpowers/specs/2026-11-04-scim-org-hierarchy-design.md` — 설계가 권위다. 이 계획과 부딪히면 설계를 따른다. 코드 사실은 `.superpowers/hierarchy/research-code.md`(git 무시, 줄 번호는 `61fd32d` 기준)에 있다.

## Global Constraints

- **코드 관례.** 기존 코드처럼 공개 API 는 영어 이름(`assignParent`), 내부 메서드·변수는 한국어 이름이다. 시험 메서드는 한국어 이름 + `@DisplayName` 한국어 문장이고, 본문은 `// given / when / then`, 단언은 AssertJ 다. 주석 밀도는 둘레 코드를 따른다.
- **Gradle.** 한 번에 하나만 돌린다. 구현 서브에이전트는 자기 과제 모듈의 시험만 돌린다(예: `./gradlew :core:test`). 전체 `test`·`scaleTest` 는 컨트롤러가 머지 전에 돌린다.
- **git.** `git stash` 금지. 바꾼 파일만 스테이징한다. 커밋 메시지는 한국어 `feat:`/`fix:`/`test:`/`docs:` 한 문장 + ` (조직 계층)` 이고, 끝줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 다.
- **원칙.**
  - OpenFGA Read 는 재적재와 기준선을 믿을 수 없는 LDAP 회차에서만 쓴다. 판단·쓰기는 Check 다.
  - 요청마다 전원을 읽지 않는다(실제 규모 10만 명).
  - 운영 배포 전이라 이관하지 않는다.
  - 배포 하나에 커넥터 하나다.
- **이름(정확히):**

  | 대상 | 값 |
  |---|---|
  | 경로 | `PUT /admin/organizations/{orgCode}/parent`, `DELETE /admin/organizations/{orgCode}/parent` |
  | 요청 본문 칸 | `parentOrgCode` |
  | 범위 | `admin.hierarchy` (상수 `Scopes.ADMIN_HIERARCHY`) |
  | 개발 클라이언트 | `org-chart` — 범위 `admin.read, admin.hierarchy`, 수명 10m, 비밀 `{noop}org-chart-secret` |
  | 저장 키 | 정본 정렬키 `ASSIGNED_PARENT`(속성 `parent`), 역색인 정렬키 접두 `ASSIGNED_CHILD#` |
  | 감사 | 종류 `AuditKind.HIERARCHY_REQUEST`, 속성 이름 `assignedParent` |
  | 조직 상세 칸 | `assignedParentOrgCode` |
- **설계가 정한 문구(그대로):**

  | 경우 | 문구 |
  |---|---|
  | 대상 조직이 없다(404) | `조직이 없다: <orgCode>` |
  | 상위 조직이 없다(404) | `상위 조직이 없다: <parentOrgCode>` |
  | 순환(409) | `<parentOrgCode> 를 <orgCode> 의 상위로 두면 순환이 된다` |
  | 본문 오류(400) | `parentOrgCode 가 필요하다` |
  | 변환 경고 | `지정 연결 '<parent>' → '<child>' 의 조직이 스냅샷에 없어 건너뜁니다` |
- **응답 코드.** 성공은 204, 본문 오류는 400, 없음은 404, 순환은 409, 걷기 예산 초과는 400 이다. 락·리스·일시 장애·부분 실패는 503 + `Retry-After` 다. 락 경합은 관리 API 의 다른 경로와 달리 409 가 아니다(설계 §3.1).

## Review Focus

- **IdP 가 조직 G 를 지웠는데 G 를 거치는 지정 연결 줄이 OpenFGA 에 남는다.** 그러면 지정하위의 직원이 계속 지정상위의 member 다. 줄은 지워지고 아이템도 정리돼야 한다(Task 3).
- **IdP 가 멤버 전체 교체(PUT, 빈 목록)를 보낸다.** 지정이 받치는 `child` 줄은 남고, 멤버 행은 되살아나지 않아야 한다(Task 3, Task 8).
- **조직 G 파티션에 낡은 역색인(`ASSIGNED_CHILD#C`)이 남아 있는데 C 의 정본은 이미 다른 상위 Q 를 가리킨다.** G 를 지워도 C→Q 는 그대로여야 한다(Task 2).
- **재적재 DFS 가 지정 연결을 잘라 보류 목록에 넣었다.** 다음 보류 다시 보기가 "멤버 행이 없다" 며 그 줄을 버리면 안 된다(Task 3).
- **운영 스크립트가 다른 SCIM 쓰기와 겹쳐 락을 못 잡는다.** 409(순환)가 아니라 503 + `Retry-After` 여야 스크립트가 다시 보낸다(Task 8).

---

### Task 1: 그림과 변환이 지정 연결을 싣는다 (설계 H2, §5.2)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/DirectorySnapshot.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java`, `core/src/test/java/dev/starryeye/organization/core/model/DirectorySnapshotTest.java`(없으면 새로)

**Interfaces:**
- Produces:
  ```java
  public record DirectorySnapshot(Map<String, DirectoryUser> users, Map<String, DirectoryGroup> groups, Set<GroupEdge> assignedEdges) {
      public DirectorySnapshot(Map<String, DirectoryUser> users, Map<String, DirectoryGroup> groups)  // assignedEdges = 빈 집합 — 기존 호출처 57곳은 그대로 둔다
      public DirectorySnapshot withAssignedEdges(Set<GroupEdge> edges)                                 // 나머지 칸은 그대로인 새 그림
      // compact 생성자: null → 빈 집합, Set.copyOf. empty() 는 그대로
  }
  ```
  - `TupleMapper.toTuples`·`toTuplesKeepingCycles` 는 지정 연결을 하위 조직 연결로 친다. 양끝이 `groups()` 에 없으면 경고하고 건너뛴다.
  - `candidateTuples` 는 지정 연결마다 `edge.tuple()` 을 걸러 내지 않고 싣는다.

- [ ] **Step 1: 시험을 쓴다.**
  - `DirectorySnapshotTest`:
    - `두_인자_생성자는_지정_연결이_비었다`
    - `withAssignedEdges 는 다른 칸을 그대로 두고 지정 연결만 바꾼다`
    - `null 은 빈 집합이다`
  - `TupleMapperTest`. 그림은 조직 P·C(멤버 없음)에 지정 `{new GroupEdge("P","C")}` 다.
    - `지정_연결은_child_튜플이_된다` → `toTuples(그림).tuples()` 에 `RelationTuple.child("C","P")`.
    - `멤버_연결과_같은_쌍은_한_줄이다` — P 의 멤버가 `MemberRef.group("C")` 이고 지정도 (P,C) → `child` 관계 튜플이 정확히 1개.
    - `양끝이_없으면_경고하고_건너뛴다` — 지정 (P,"X"), X 는 그림에 없음 → `child("X","P")` 없음, `warnings()` 에 `지정 연결 'P' → 'X' 의 조직이 스냅샷에 없어 건너뜁니다`.
    - `보류에_있으면_빠진다` — `toTuples(그림, Set.of(new GroupEdge("P","C")))` → `child("C","P")` 없음.
    - `지정_연결이_닫는_순환은_DFS_가_자른다` — P 의 멤버 `group("C")`, 지정 (C,P) → `cutEdges()` 크기 1, `child` 튜플 1개.
    - `순환을_버리지_않는_변환도_지정_연결을_싣는다` → `toTuplesKeepingCycles` 결과에 `child("C","P")`.
    - `후보에_지정_연결이_실린다` → `candidateTuples` 에 `child("C","P")`. 양끝이 없는 지정 (P,"X") 도 `child("X","P")` 로 실린다.
- [ ] **Step 2: 실패를 본다.** `./gradlew :core:test --tests '*TupleMapperTest' --tests '*DirectorySnapshotTest'` — 컴파일 실패(생성자·메서드 없음).
- [ ] **Step 3: 구현한다.**
  - `collectChildEdges` 는 멤버 반복 뒤에 `snapshot.assignedEdges()` 를 `OrgGraph` 와 같은 아이디순(parent, child)으로 돌며 합친다. 이미 있는 `edges.get(parent)` 에 `child` 를 더한다.
  - 양끝 중 하나라도 `groups()` 에 없으면 위 경고를 남기고 건너뛴다.
  - 결정성은 `TreeMap`/`TreeSet` 이 지킨다.
- [ ] **Step 4: 통과를 본다.** 같은 명령, 그리고 `./gradlew :core:test` 전체가 초록이다(2-인자 생성자 덕에 다른 시험은 그대로).
- [ ] **Step 5: 커밋한다.** `feat: 그림에 지정 연결 칸을 더하고 변환·후보가 그것을 child 로 싣는다 (조직 계층)`

---

### Task 2: 지정 연결 저장 (설계 H3·H4·H9·H10, §4)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` — 새 메서드 넷과 `deleteGroup`·`loadAll` 계약 문구. 불변식 문구(127-130)를 "원천 연결(멤버 행의 하위 조직 연결 ∪ 지정 연결) − 보류 목록" 으로 고친다.
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `app-ldap/src/test/java/.../LdapInterruptedSyncScaleTest.java` — 전달용 record `한번만_실패하는_상태저장소`(190행 부근)에 새 메서드 넷을 그대로 전달한다.
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`, `DynamoDbDirectoryStateRepository.java`
- Test: `storage-dynamodb/src/test/java/.../KeysTest.java`, `storage-dynamodb/src/test/java/.../DynamoDbAssignedParentTest.java`(새로, `DynamoDbTestSupport` 상속)

**Interfaces:**
- Produces (포트):
  ```java
  /** 운영자가 이 조직에 지정한 상위 조직 id(설계 2026-11-04 §4). 없으면 빈 Mono. 강한 일관성. */
  Mono<String> findAssignedParent(String orgCode);
  /** 이 조직을 상위로 지정한 하위 조직 id — 역색인을 정본과 맞춘 것만, 아이디 오름차순. 강한 일관성. */
  Flux<String> findAssignedChildren(String orgCode);
  /** 지정 상위를 parentOrgCode 로 둔다. previousParentOrgCode 는 락 안에서 읽은 지금 값(없으면 null). 순서: 새 역색인 → 정본 → (바뀌었으면) 옛 역색인 지우기. */
  Mono<Void> saveAssignedParent(String orgCode, String parentOrgCode, String previousParentOrgCode);
  /** 지정 상위를 없앤다. 정본이 parentOrgCode 를 가리킬 때만 정본을 지우고(조건부), 역색인은 늘 지운다. 순서: 정본 → 역색인. */
  Mono<Void> deleteAssignedParent(String orgCode, String parentOrgCode);
  ```
  - `deleteGroup(groupId, members)` 계약에 다음을 더한다. META 를 지우기 전에 그 조직의 지정 상위 정본과 그 역색인을 지운다. 또 그 조직 파티션의 `ASSIGNED_CHILD#*` 마다 하위 조직의 정본을 지우되, 정본이 이 조직을 가리킬 때만이다. 그다음 그 역색인 아이템들을 지운다.
  - `loadAll()` 은 `assignedEdges` 를 싣는다(조직 파티션 읽기에서 함께 얻는다 — 조직당 읽기를 더하지 않는다).
- Produces (가짜): `public final Map<String, String> assignedParents`(하위 → 상위, `LinkedHashMap`).
  - `findAssignedChildren` 은 값이 같은 키를 정렬해 준다.
  - `deleteGroup` 은 그 조직이 하위이거나 상위인 줄을 지운다.
  - `loadAll` 은 `assignedParents` 를 `GroupEdge(상위, 하위)` 집합으로 싣는다.
  - `replaceWith` 끝에서 하위나 상위가 `groups` 에 없는 줄을 지운다(wipe).
- Produces (`Keys`): `ASSIGNED_PARENT = "ASSIGNED_PARENT"`, `ASSIGNED_CHILD_PREFIX = "ASSIGNED_CHILD#"`, `assignedChildSk(String childId)`, `isAssignedChildSk(String sk)`, `parseAssignedChildSk(String sk)`.

- [ ] **Step 1: 시험을 쓴다.**
  - `KeysTest`: `assignedChildSk("C")` = `"ASSIGNED_CHILD#C"`, 파싱이 되돌린다, `isMemberSk("ASSIGNED_CHILD#C")` 는 false, `isBelongsToSk` 도 false.
  - `DynamoDbAssignedParentTest` — 각 시험은 `saveGroup` 으로 조직 P·Q·C·D·G 를 만든 뒤 시작한다:
    - `지정하면_정본과_역색인이_생긴다` — `saveAssignedParent("C","P",null)` → `findAssignedParent("C")` = `P`, `findAssignedChildren("P")` = `[C]`.
    - `바꾸면_옛_역색인이_지워진다` — C→P 뒤 `saveAssignedParent("C","Q","P")` → `findAssignedChildren("P")` 빈, `("Q")` = `[C]`.
    - `정본과_맞지_않는_역색인은_거른다` — C→Q 상태에서 `GROUP#P / ASSIGNED_CHILD#C` 를 클라이언트로 직접 넣는다 → `findAssignedChildren("P")` 빈.
    - `없애면_둘_다_지워진다` — `deleteAssignedParent("C","P")` → 둘 다 빈.
    - `정본이_다른_상위를_가리키면_정본은_두고_역색인만_지운다` — C→Q, P 에 낡은 역색인 → `deleteAssignedParent("C","P")` → `findAssignedParent("C")` = `Q`, P 파티션에 `ASSIGNED_CHILD#C` 없음.
    - `조직을_지우면_양방향_지정이_지워진다` — C→G, D→G, G→P → `deleteGroup("G", Set.of())`:
      - `findAssignedParent("C")`·`("D")`·`("G")` 모두 빈.
      - `findAssignedChildren("P")` 빈.
      - `findGroup("G")` 빈.
    - `낡은_역색인이_가리키는_하위의_정본은_지우지_않는다` — C→Q 이고 G 파티션에 낡은 `ASSIGNED_CHILD#C` → `deleteGroup("G", …)` 뒤 `findAssignedParent("C")` = `Q`.
    - `전체_읽기는_지정_연결을_싣는다` — C→P → `loadAll().block().assignedEdges()` = `{GroupEdge("P","C")}`.
    - `지정은_META_와_SCIM_멤버를_건드리지_않는다` — P 의 META `updatedAt` 원시 속성(GetItem)이 지정 전후로 같다. `findGroup("P").members()`·`findMemberRefs("P")` 에 C 가 없다.
    - `빈_그림으로_바꾸면_지정이_모두_지워진다` — C→P 뒤 `replaceWith(DirectorySnapshot.empty())` → 테이블에 정렬키 `ASSIGNED_` 로 시작하는 아이템이 없다(Scan).
- [ ] **Step 2: 실패를 본다.** `./gradlew :storage-dynamodb:test --tests '*KeysTest' --tests '*DynamoDbAssignedParentTest'` — 컴파일 실패.
- [ ] **Step 3: 구현한다.**
  - **포트·가짜·전달 record:** 위 계약대로다. 가짜의 계측 목록은 더하지 않는다(필요한 과제가 더한다).
  - **`findAssignedParent`:** GetItem(`groupPk(orgCode)`, `ASSIGNED_PARENT`, 강한 일관성)이다. `Keys.fitsPartitionKey` 를 넘으면 빈 Mono 다.
  - **`findAssignedChildren`:** `querySortKeys(groupPk, ASSIGNED_CHILD_PREFIX)` → `flatMapSequential(child -> findAssignedParent(child).filter(orgCode::equals).map(p -> child), QUERY_CONCURRENCY)`.
  - **`saveAssignedParent`·`deleteAssignedParent`:** `putItem`/`deleteItem` 을 위 순서대로 부른다. 조건부 삭제는 `DeleteItem` 의 `conditionExpression "#parent = :parent"` 이고, 조건 실패(`ConditionalCheckFailedException`)는 삼킨다.
  - **`deleteGroup`:** 기존 `querySortKeys(pk, BELONGS_TO_PREFIX)` 옆에서 `querySortKeys(pk, ASSIGNED_CHILD_PREFIX)` 와 `findAssignedParent(groupId)` 를 읽는다. META 를 지우기 바로 앞에 다음 순서로 정리한다.
    1. 하위들의 정본(조건부)
    2. 이 조직 파티션의 역색인들
    3. 이 조직의 정본
    4. 그 상위 파티션의 역색인
  - **`loadAll`:** 조직마다 `queryPartition` 한 번으로 `DirectoryGroup` 과 `ASSIGNED_PARENT` 아이템을 함께 얻는다. `toGroup` 을 고치지 말고, 같은 `items` 에서 지정 상위를 뽑는 private 도우미를 쓴다. 그 결과로 `new DirectorySnapshot(users, groups, edges)` 를 만든다.
- [ ] **Step 4: 통과를 본다.** 같은 명령, 그리고 `./gradlew :storage-dynamodb:test`, `./gradlew :core:test`, `./gradlew :app-ldap:compileTestJava` 가 초록이다.
- [ ] **Step 5: 커밋한다.** `feat: 지정 상위의 정본·역색인 저장과 조직 지우기·전체 읽기·wipe 의 정리 (조직 계층)`

---

### Task 3: IdP 쓰기 경로가 지정 연결을 안다 (설계 H5·H6, §5.3~§5.6)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/OrgGraph.java` — `튜플_그래프의_부모들`(213행 부근).
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`:
  - `changeGroupInternal`(515)
  - `removeGroupInternal`(677), `조직을_언급하는_튜플`(695), `조직_삭제를_커밋한다`(702)
  - `한_줄을_다시_본다`(749)
  - 클래스 주석의 불변식 문구(62-67)
- Modify: `OrgGraph` 클래스 주석의 불변식 문구(27-45).
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncAssignedEdgeTest.java`(새로, 조립은 `IncrementalSyncCycleTest:30-60` 과 같다)

**Interfaces:**
- Consumes: Task 2 의 `findAssignedParent`, `findAssignedChildren`, `deleteAssignedParent`, 가짜 `assignedParents`.
- Produces: 다음 동작. 새 공개 API 는 없다.
  - `OrgGraph` 가 위로 걸을 때 부모 = `findGroupIdsContaining(group(id))` ∪ `findAssignedParent(id)` − 보류다. 예산 계산은 그대로 조직 하나에 하나다.
  - `한_줄을_다시_본다` 는 "원천이 있다" 를 `findMembers(parent, {group(child)})` 가 비지 않았거나 `findAssignedParent(child)` 가 `parent` 인 것으로 본다.
  - `changeGroupInternal` 은 락 안에서 빠지는 GROUP 멤버마다 `findAssignedParent(member.id())` 를 읽는다. 그 값이 이 조직이면 그 줄을 `확인없이_지울것` 에서 뺀다. 그림에는 싣지 않는다 — 실으면 이미 있는 줄을 다시 쓴다(조사 §5.5). 그러면 `reconcileGroupMembers` 가 멤버 행 삭제를 성공으로 읽는다.
  - `removeGroupInternal`:
    - `findAssignedParent(G)` 와 `findAssignedChildren(G)` 를 읽어 `child(G, 지정상위)`, `child(하위, G)` 를 지울 줄에 더한다.
    - 지정 연결이 있으면 `연결을_지운다` 가 참이다.
    - **다 지웠으면** 저장 정리는 `deleteGroup` 이 한다(Task 2).
    - **일부만 지웠으면** `result.deleted()` 에 든 지정 연결만 `state.deleteAssignedParent(하위, 상위)` 한다. 못 지운 것은 아이템을 남긴다.

  > 이 과제는 이력을 남기지 않는다. `AuditChanges.assignedParent` 는 Task 4 가 만들고, 두 커밋 경로에 지정 하위의 이력 줄을 그때 더한다(Task 4 Step 3 마지막 항목).

- [ ] **Step 1: 시험을 쓴다.** `IncrementalSyncAssignedEdgeTest`.
  - `IdP_가_하위_조직을_빼도_지정이_받치면_줄이_남는다`:
    - given: P{group C}, C, `assignedParents` C→P, `checker.allowed` ⊇ `child(C,P)`.
    - when: `changeGroup("P", GroupChange.delta().removingId("C"))`.
    - then: `writer.deleted` 에 `child(C,P)` 없음. `state.groups.get("P").members()` 에 `group(C)` 없음. `result.fullyApplied()`.
  - `IdP_가_멤버를_통째로_비워도_지정이_받치면_줄이_남는다` — 같은 조건에서 `GroupChange.delta().replacing(Set.of())` → 위와 같다.
  - `지정이_없으면_지금처럼_지운다` — 지정 없음 → `writer.deleted` 에 `child(C,P)`.
  - `지정이_다른_상위를_가리키면_지운다` — C→Q → `writer.deleted` 에 `child(C,P)`.
  - `조직을_지우면_지정_연결의_줄도_지운다`:
    - given: G·P·C, G→P, C→G, `allowed` ⊇ `child(G,P)`, `child(C,G)`.
    - when: `removeGroup("G")`.
    - then: `writer.deleted` ⊇ 둘. `assignedParents` 에 G·C 키 없음. `state.groups` 에 G 없음.
  - `조직_지우기가_일부_실패하면_못_지운_지정은_남는다`:
    - given: 위와 같고 `writer.failFor(t -> t.equals(child("C","G")))`.
    - then: `fullyApplied()` 거짓. `assignedParents` 에 C→G 남음, G→P 없음. G 는 남음.
  - `IdP_연결이_지정_연결과_순환을_닫으면_보류한다`:
    - given: C→P 지정, `allowed` ⊇ `child(C,P)`.
    - when: `changeGroup("C", delta().adding(Set.of(group("P"))))`.
    - then: `state.cutEdges` = `{GroupEdge("C","P")}`. `writer.written` 에 `child(P,C)` 없음.
  - `보류_다시_보기는_지정을_원천으로_본다`:
    - given: X{group Y}, Y, P, C. C→P 지정. `cutEdges` = `{GroupEdge("P","C")}`. `allowed` ⊇ `child(Y,X)`. `child(C,P)` 는 없음.
    - when: `changeGroup("X", delta().removingId("Y"))`.
    - then: `writer.written` 에 `child(C,P)`. `cutEdges` 빈.
  - `보류_다시_보기는_원천이_모두_없으면_버린다` — 위에서 지정 없음 → `cutEdges` 빈, `child(C,P)` 쓰지 않음(회귀).
- [ ] **Step 2: 실패를 본다.** `./gradlew :core:test --tests '*IncrementalSyncAssignedEdgeTest'` — 첫·둘째·조직 지우기·순환·보류 시험이 실패한다.
- [ ] **Step 3: 구현한다.** 위 Produces 대로 한다. `조직을_언급하는_튜플` 은 `Set<GroupEdge> 지정` 인자를 더 받는다. 읽기는 모두 락 안이다(지금 `findGroup` 다음).
- [ ] **Step 4: 통과를 본다.** `./gradlew :core:test` 전체가 초록이다(`IncrementalSyncCycleTest`·`IncrementalSyncCycleRestoreTest` 포함).
- [ ] **Step 5: 커밋한다.** `feat: IdP 의 하위 조직 빼기·조직 지우기·보류 다시 보기·순환 판정이 지정 연결을 원천으로 본다 (조직 계층)`

---

### Task 4: 지정 유스케이스와 감사 (설계 H1·H4·H5·H7, §3.1·§5.4·§6)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/audit/AuditKind.java`, `AuditCause.java`, `AuditChanges.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/HierarchyRejectedException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` — `withLock`(855), `diffAndApply`(1001·1045), 새 공개 메서드 둘, Task 3 의 조직 지우기 이력.
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncParentAssignmentTest.java`(새로, 조립은 `IncrementalSyncAuditTest:55-69` 의 10-인자 + `FakeAuditLog`), `core/src/test/.../audit/AuditChangesTest.java`(있으면 더한다)

**Interfaces:**
- Consumes: Task 1 `DirectorySnapshot.withAssignedEdges`, Task 2 포트 넷, Task 3 의 보류 원천 판정.
- Produces:
  ```java
  // AuditKind 에 HIERARCHY_REQUEST
  public static AuditCause hierarchyRequest(String traceId, String actor)  // SyncSource.SCIM, HIERARCHY_REQUEST; traceId 가 비면 임의 UUID(scimRequest 와 같다)
  public synchronized AuditChanges assignedParent(String orgCode, String before, String after)  // 대상 group(orgCode) 에 AttributeChange("assignedParent", before, after)

  public final class HierarchyRejectedException extends RuntimeException {
      public enum Reason { PARENT_NOT_FOUND, CYCLE }
      public HierarchyRejectedException(Reason reason, String orgCode, String parentOrgCode)  // 메시지는 Global Constraints 의 404·409 문구
      public Reason reason(); public String orgCode(); public String parentOrgCode();
  }

  // IncrementalSyncUseCase
  public Mono<IncrementalSyncResult> assignParent(String orgCode, String parentOrgCode)  // 대상이 없으면 빈 Mono
  public Mono<IncrementalSyncResult> clearParent(String orgCode)                         // 대상이 없으면 빈 Mono
  ```
  - `AuditChanges.assignedParent` 의 속성 줄은 `속성_순서` 의 속성들 뒤에 붙는다.
  - `withLock(work)` 는 새 `withLock(Function<ContextView, AuditCause> 원인, work)` 에 지금 원인(`scimRequest`)을 넘긴다. 두 새 메서드는 `hierarchyRequest(traceIds.traceIdOf(context), Actor.from(context))` 를 넘긴다.
  - private `diffAndApply(…, boolean 지우기만)` 에 마지막 인자 `Consumer<OrgGraph.거른결과> 쓰기전_확인` 을 더한다. 기존 호출은 `거름 -> { }` 를 넘긴다. `거른다` 직후·반영 전에 부른다.

- [ ] **Step 1: 시험을 쓴다.** `IncrementalSyncParentAssignmentTest`.
  - 조립: 조직 P·P0·C 를 `state.groups` 에 둔다. 행위자는 `.contextWrite(Context.of(Actor.KEY, Actor.client("org-chart")))`, `TraceIds` 는 `context -> "trace-1"` 이다.
  - `지정하면_child_를_쓰고_저장하고_남긴다`:
    - `assignParent("C","P")` → `writer.written` = `{child(C,P)}`. `assignedParents` C→P.
    - `auditLog.events` 의 원인 = `AuditCause.hierarchyRequest("trace-1", "client:org-chart")`.
    - C 의 이력 줄 `attributes` = `[AttributeChange("assignedParent", null, "P")]`, `granted` 에 `child(C,P)`.
  - `같은_지정을_다시_하면_아무것도_하지_않는다` — C→P, `allowed` ⊇ `child(C,P)` → `writer.appliedDeltas` 빈, `auditLog.events` 빈.
  - `같은_지정인데_줄이_없으면_쓴다` — C→P, `allowed` 빈 → `written` = `{child(C,P)}`, `attributes` 에 `assignedParent` 없음.
  - `바꾸면_옛_줄을_지우고_새_줄을_쓴다` — C→P0, `allowed` ⊇ `child(C,P0)` → `assignParent("C","P")` → `deleted` ⊇ `child(C,P0)`, `written` ⊇ `child(C,P)`, `assignedParents` C→P, 속성 P0→P.
  - `IdP_멤버가_옛_쌍을_받치면_옛_줄은_남는다` — P0{group C} 이고 나머지는 위와 같다 → `deleted` 에 `child(C,P0)` 없음.
  - `없애면_줄을_지운다` — `clearParent("C")` → `deleted` ⊇ `child(C,P0)`, `assignedParents` 빈, 속성 P0→null.
  - `없애도_IdP_멤버가_받치면_줄이_남는다`.
  - `지정이_없는데_없애면_아무것도_하지_않는다` — `appliedDeltas` 빈, 이력 없음.
  - `없앤_뒤_보류_줄이_돌아온다`:
    - given: C→P 지정, `allowed` ⊇ `child(C,P)`. C{group P}(IdP). `cutEdges` = `{GroupEdge("C","P")}`.
    - when: `clearParent("C")`.
    - then: `written` ⊇ `child(P,C)`. `cutEdges` 빈.
  - `순환이면_거절하고_아무것도_쓰지_않는다`:
    - given: C{group P}, `allowed` ⊇ `child(P,C)`.
    - when: `assignParent("C","P")`.
    - then: `HierarchyRejectedException`, `reason()` = `CYCLE`, 메시지 `P 를 C 의 상위로 두면 순환이 된다`. `appliedDeltas` 빈, `assignedParents` 빈, `cutEdges` 빈, 이력 없음.
  - `자기_자신은_순환이다` — `assignParent("C","C")` → `CYCLE`.
  - `보류_중인_같은_지정은_거절한다` — C→P 지정, `cutEdges` = `{GroupEdge("P","C")}`, C{group P}, `allowed` ⊇ `child(P,C)` → `CYCLE`. `assignedParents` 는 C→P 그대로, `cutEdges` 그대로.
  - `상위_조직이_없으면_거절한다` — `assignParent("C","NONE")` → `PARENT_NOT_FOUND`, 메시지 `상위 조직이 없다: NONE`.
  - `대상_조직이_없으면_빈_결과다` — `assignParent("NONE","P").blockOptional()` 빈. `clearParent("NONE")` 도 빈.
  - `쓰기가_실패하면_저장을_바꾸지_않는다` — `writer.failFor(t -> t.equals(child("C","P")))` → `fullyApplied()` 거짓, `assignedParents` 빈.
  - `조직을_지우면_지정_하위의_이력에_남는다` — Task 3 의 조직 지우기 시험 조건 + `FakeAuditLog` → C 의 줄 `attributes` = `[AttributeChange("assignedParent", "G", null)]`.
  - `AuditChangesTest`: `assignedParent` 와 `group(...)` 의 이름 바꾸기가 같은 조직에 함께 오면, 속성 순서는 `displayName` 다음 `assignedParent` 다.
- [ ] **Step 2: 실패를 본다.** `./gradlew :core:test --tests '*IncrementalSyncParentAssignmentTest' --tests '*AuditChangesTest'` — 컴파일 실패.
- [ ] **Step 3: 구현한다.**
  - **`assignParent` 순서(모두 `withLock` 안):**
    1. `findGroupHeader(orgCode)` 가 없으면 빈 Mono 다.
    2. `orgCode.equals(parentOrgCode)` 면 `CYCLE` 이다(읽기 전).
    3. `findGroupHeader(parentOrgCode)` 가 없으면 `PARENT_NOT_FOUND` 다.
    4. `findAssignedParent(orgCode)` 로 이전 값 P0 를 읽는다. P0 의 헤더가 없으면 P0 를 없는 것으로 친다.
    5. 그림을 만든다. 조직은 C(멤버 빈) + P0 + P 다. 상위 조직의 멤버로는 `findMembers(상위, {group(C)})` 결과만 싣는다. 전 그림은 `withAssignedEdges({(P0,C)})`, 후 그림은 `withAssignedEdges({(P,C)})` 다(`snapshotOfGroups` 뒤에 붙인다).
    6. `diffAndApply(전, 후, groupRef(C), Set.of(), lease, 기록, commit, false, 거름 -> { … })`. 확인은 `!거름.남길것().contains(child(C,P))` 이면 `CYCLE` 을 던진다.
    7. commit: `result.hasFailure()` 면 아무것도 하지 않는다. P0≠P 면 `state.saveAssignedParent(C,P,P0)` 와 `기록.assignedParent(C,P0,P)` 다. 같으면 저장·속성 기록 없음이다.
    8. P0 가 있고 P0≠P 면 `보류를_다시_본다` 다.
  - **`clearParent`:** 같은 틀이다. 지정이 없으면 `IncrementalSyncResult.noChange()` 다. 전 그림은 `{(P0,C)}`, 후 그림은 빈 지정이다. commit 은 실패가 없을 때만 `state.deleteAssignedParent(C,P0)` 와 이력이다. 끝에 `보류를_다시_본다` 다.
  - **조직 지우기 이력:** Task 3 의 두 커밋 경로에서 지정 하위마다 `기록.assignedParent(하위, G, null)` 이다.
- [ ] **Step 4: 통과를 본다.** `./gradlew :core:test` 전체가 초록이다.
- [ ] **Step 5: 커밋한다.** `feat: 지정 상위를 두고 없애는 유스케이스 — 같은 락·Check 기준선·diff, 순환은 거절, 새 감사 원인 HIERARCHY_REQUEST (조직 계층)`

---

### Task 5: 재적재·아카이빙이 지정 연결을 싣는다 (설계 H6·H10, §5.6)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` — `열거를_보강한다` 의 `new DirectorySnapshot(직원들, 조직들)`(191) → `directory.assignedEdges()` 를 이월.
- Test: `core/src/test/.../usecase/ScimRebuildUseCaseTest.java`, `ScimRebuildIndexLagTest.java`, `SnapshotArchiveUseCaseTest.java`

**Interfaces:**
- Consumes: Task 1(그림·변환), Task 2(가짜 `loadAll`·`replaceWith`).

- [ ] **Step 1: 시험을 쓴다.**
  - `ScimRebuildUseCaseTest`:
    - `재적재는_지정_연결을_튜플로_싣는다` — P·C, C→P → `mode=tuples` 재적재 뒤 `writer.stored` 에 `child(C,P)`.
    - `재적재가_자른_지정_연결은_보류_목록에_들어간다` — P{group C}(IdP), C→P 가 아니라 P→C 지정(`assignedParents.put("P","C")`) → 끝난 뒤 `state.cutEdges` 크기 1, `writer.stored` 의 `child` 1줄.
    - `wipe_는_지정을_모두_지운다` — `mode=wipe` 뒤 `assignedParents` 빈.
  - `ScimRebuildIndexLagTest`: 보강 경로를 타는 기존 시나리오에 C→P 지정을 더한다 → 결과 `writer.stored` 에 `child(C,P)`.
  - `SnapshotArchiveUseCaseTest`:
    - `지정_연결은_빠짐으로_세지_않는다` — C→P, `checker.allowed` ⊇ `child(C,P)` → 빠짐 0, 여분 0.
    - `없는_지정_줄은_빠짐으로_센다` — `allowed` 빈 → 빠짐 1.
    - 결과 칸 이름은 그 시험 파일의 기존 단언을 따른다.
- [ ] **Step 2: 실패를 본다.** `./gradlew :core:test --tests '*ScimRebuildUseCaseTest' --tests '*ScimRebuildIndexLagTest' --tests '*SnapshotArchiveUseCaseTest'` — 보강 시험이 실패한다(다른 것은 Task 1·2 로 이미 통과할 수 있다 — 그러면 그대로 둔다).
- [ ] **Step 3: 구현한다.** 191행 한 줄을 고친다.
- [ ] **Step 4: 통과를 본다.** `./gradlew :core:test`.
- [ ] **Step 5: 커밋한다.** `fix: 재적재의 보강한 그림이 지정 연결을 이월한다, 재적재·wipe·아카이빙의 지정 연결 시험 (조직 계층)`

---

### Task 6: 조회 API 가 지정 연결을 보여 준다 (설계 H11, §7)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/query/OrganizationDetail.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java` — `organizationDetail`(268), `expandParents`(205), `childrenOf`(320).
- Test: `core/src/test/.../usecase/AdminQueryUseCaseTest.java`, `admin-api/src/test/.../AdminQueryControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `findAssignedParent`, `findAssignedChildren`.
- Produces: `OrganizationDetail(String orgCode, String displayName, String externalId, String assignedParentOrgCode, List<GroupSummary> ancestors, List<GroupSummary> childOrganizations, Page<OrgMember> members)`. 생성 호출처는 `AdminQueryUseCase` 한 곳이다.
  - `expandParents`: 부모 id = `Flux.concat(findGroupIdsContaining(group(orgCode)), findAssignedParent(orgCode).flux()).distinct()`. 그 뒤는 그대로다(`take(MAX_PATHS + 1)` 등).
  - `childrenOf`: id = `findChildOrgCodes(orgCode)` ∪ `findAssignedChildren(orgCode)` 를 중복 없이 아이디 오름차순으로 정렬한 것. 그 뒤는 그대로다.

- [ ] **Step 1: 시험을 쓴다.** `AdminQueryUseCaseTest`:
  - `조직_상세는_지정_상위를_싣는다` — C→P → `assignedParentOrgCode()` = `P`. 지정이 없으면 null.
  - `지정_상위도_상위_계층에_나온다` — C→P, R{group P}(IdP) → C 상세의 `ancestors` orgCode = `[P, R]`.
  - `지정_하위도_직속_하위_조직에_나온다` — C→P, P{group D}(IdP) → P 상세의 `childOrganizations` orgCode = `[C, D]`.
  - `같은_쌍이_멤버와_지정_둘_다여도_한_번만_나온다`.
  - `직원_상세의_롤업이_지정_상위로_올라간다` — kim ∈ C, C→P → 경로에 P(`via` = `rollup`).
  - `AdminQueryControllerTest`: 조직 상세 JSON 에 `$.assignedParentOrgCode` 가 있다(값과 null 둘 다).
- [ ] **Step 2: 실패를 본다.** `./gradlew :core:test --tests '*AdminQueryUseCaseTest'`.
- [ ] **Step 3: 구현한다.** 위 Produces 대로 한다. `organizationDetail` 의 `Mono.zip` 에 `findAssignedParent(orgCode).map(Optional::of).defaultIfEmpty(Optional.empty())` 를 더한다.
- [ ] **Step 4: 통과를 본다.** `./gradlew :core:test` 다음 `./gradlew :admin-api:test`.
- [ ] **Step 5: 커밋한다.** `feat: 조직 상세에 지정 상위를 싣고 상위 계층·직속 하위·직원 롤업이 지정 연결을 합친다 (조직 계층)`

---

### Task 7: 범위 `admin.hierarchy` 와 개발 클라이언트 `org-chart` (설계 H8, §8)

**Files:**
- Modify: `web-security/src/main/java/dev/starryeye/organization/security/Scopes.java` — 상수, "여섯" → "일곱".
- Modify: `web-security/src/main/java/dev/starryeye/organization/security/ResourceServerSecurityConfig.java` — 규칙(58-67), javadoc 표(28-38).
- Modify: `web-security/src/testFixtures/java/.../TestTokens.java` — `ALL_SCOPES` 에 더함, 문구. `TestAuthAutoConfiguration.java` 의 문구.
- Modify: `web-security/src/test/java/.../TestTokensTest.java`, `MockRoutesApplication.java`, `ResourceServerSecurityConfigTest.java`
- Modify: `app-auth/src/main/resources/auth-server.yml`, `app-auth/src/test/resources/auth-server-test.yml`
- Modify: `app-scim/src/test/java/.../RealAuthServerEndToEndTest.java` — `해시한_비밀_인자`(91), 주석의 "다섯".
- Modify: `app-auth/src/main/java/.../ClientSettingsCheck.java:17` — 주석 열거에 `org-chart` 가 맞으면 더한다.

**Interfaces:**
- Produces: `Scopes.ADMIN_HIERARCHY = "admin.hierarchy"`. 규칙 두 줄을 `denyAll` 앞에 둔다:
  ```java
  .pathMatchers(HttpMethod.PUT, "/admin/organizations/*/parent").hasAuthority(권한(Scopes.ADMIN_HIERARCHY))
  .pathMatchers(HttpMethod.DELETE, "/admin/organizations/*/parent").hasAuthority(권한(Scopes.ADMIN_HIERARCHY))
  ```

- [ ] **Step 1: 시험을 쓴다.**
  - `TestTokensTest`: 일곱 개 `containsExactly`(끝에 `admin.hierarchy`).
  - `MockRoutesApplication`: `PUT /admin/organizations/{orgCode}/parent` 와 `DELETE` 같은 경로 → `ServerResponse.noContent()`.
  - `ResourceServerSecurityConfigTest` 행렬에 더할 행:
    ```
    "admin.hierarchy,   PUT,    /admin/organizations/X/parent, 204",
    "admin.hierarchy,   DELETE, /admin/organizations/X/parent, 204",
    "admin.read,        PUT,    /admin/organizations/X/parent, 403",
    "admin.sync,        DELETE, /admin/organizations/X/parent, 403",
    "admin.destructive, PUT,    /admin/organizations/X/parent, 403",
    "scim,              PUT,    /admin/organizations/X/parent, 403",
    "admin.hierarchy,   GET,    /admin/employees,              403",
    "admin.hierarchy,   POST,   /admin/sync/full,              403",
    "admin.hierarchy,   PUT,    /admin/organizations/X,        403",
    ```
  - 토큰 없이 `PUT /admin/organizations/X/parent` → 401 시험 하나(기존 401 시험을 따라 한다).
  - `RealAuthServerEndToEndTest`: 기존 시험에 `org-chart` 로 토큰을 받아 범위에 `admin.hierarchy` 가 있는지 보는 단언 하나를 더한다(그 클래스의 토큰 받기 도우미를 쓴다).
- [ ] **Step 2: 실패를 본다.** `./gradlew :web-security:test`.
- [ ] **Step 3: 구현한다.**
  - **`auth-server.yml`:** `asking-app` 앞에 `org-chart` 블록을 넣는다. 모양은 `ops` 와 같고, 범위는 `admin.read, admin.hierarchy`, 수명 10m 이다.
  - **`auth-server-test.yml`:** `org-chart` 의 `registration.client-secret` 은 평문 `org-chart-secret` 의 bcrypt 해시(`{bcrypt}$2a$10$…`)다. README "bcrypt 만들기" 또는 `new BCryptPasswordEncoder().encode("org-chart-secret")` 로 만든다.
  - **`RealAuthServerEndToEndTest`:** `해시한_비밀_인자` 에 `"org-chart"` 를 더한다.
- [ ] **Step 4: 통과를 본다.** `./gradlew :web-security:test` 다음 `./gradlew :app-auth:test` 다음 `./gradlew :app-scim:test --tests '*RealAuthServerEndToEndTest'`.
- [ ] **Step 5: 커밋한다.** `feat: 범위 admin.hierarchy 를 지정 경로 둘에 묶고 개발 클라이언트 org-chart 를 더한다 (조직 계층)`

---

### Task 8: 지정 API 컨트롤러와 종단 시험 (설계 H1, §3)

**Files:**
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/RetryLaterStatusException.java`
- Modify: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminExceptionHandler.java` — `재시도_초` 를 패키지 전용 `static` 으로 연다(규칙은 그대로).
- Create: `app-scim/src/main/java/dev/starryeye/organization/scim/app/OrganizationHierarchyController.java`
- Test: `admin-api/src/test/.../AdminExceptionHandlerTest.java`, `app-scim/src/test/.../OrganizationHierarchyControllerTest.java`(슬라이스, 새로), `app-scim/src/test/.../ScimHierarchyEndToEndTest.java`(새로, Testcontainers)

**Interfaces:**
- Consumes: Task 4 `assignParent`·`clearParent`·`HierarchyRejectedException`. Task 7 의 경로 규칙.
- Produces:
  ```java
  public class RetryLaterStatusException extends ResponseStatusException {
      public RetryLaterStatusException(String reason, Duration retryAfter, Throwable cause)  // 503
      @Override public HttpHeaders getHeaders()   // Retry-After: AdminExceptionHandler.재시도_초(retryAfter)
  }

  @AdminApi @RestController @RequestMapping("/admin/organizations") @RequiredArgsConstructor
  public class OrganizationHierarchyController {
      @PutMapping("/{orgCode}/parent") @ResponseStatus(HttpStatus.NO_CONTENT)
      public Mono<Void> assign(@PathVariable String orgCode, @RequestBody(required = false) JsonNode body)
      @DeleteMapping("/{orgCode}/parent") @ResponseStatus(HttpStatus.NO_CONTENT)
      public Mono<Void> clear(@PathVariable String orgCode)
  }
  ```
- **오류 바꾸기(컨트롤러 안, 이 순서):**

  | 경우 | 응답 |
  |---|---|
  | 본문이 null, `parentOrgCode` 가 텍스트가 아님, 공백뿐 | `ResponseStatusException(400, "parentOrgCode 가 필요하다")` (유스케이스를 부르지 않는다) |
  | 빈 Mono | `ResponseStatusException(404, "조직이 없다: " + orgCode)` |
  | `HierarchyRejectedException` `PARENT_NOT_FOUND` | 404, 그 메시지 |
  | `HierarchyRejectedException` `CYCLE` | 409, 그 메시지 |
  | `GroupGraphTooLargeException` | 400, 그 메시지 |
  | `LockUnavailableException` | `RetryLaterStatusException(e.getMessage(), e.retryAfter(), e)` |
  | `!result.fullyApplied()` | `RetryLaterStatusException("일부 튜플 적용에 실패했다 — 잠시 뒤 다시 보낸다: " + orgCode, TemporaryFailureException.기본_대기, null)` |
  | 그 밖 | 바꾸지 않는다(처리기가 503/500) |

- [ ] **Step 1: 시험을 쓴다.**
  - `AdminExceptionHandlerTest`: `RetryLaterStatusException("x", Duration.ofMillis(1200), null)` → 503, `Retry-After: 2`, detail `x`.
  - `OrganizationHierarchyControllerTest`:
    - 조립: `WebTestClient.bindToController(new OrganizationHierarchyController(mock(IncrementalSyncUseCase.class))).controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))`. 보안 없음이다 — 범위는 Task 7 행렬이 본다.
    - 시험:
      - `지정하면_204` — 유스케이스가 `IncrementalSyncResult.noChange()` → 204, 본문 없음.
      - `본문이_없으면_400`, `parentOrgCode_가_숫자면_400`, `공백이면_400` — detail `parentOrgCode 가 필요하다`. 유스케이스를 부르지 않는다(`verifyNoInteractions`).
      - `대상이_없으면_404`
      - `상위가_없으면_404`
      - `순환이면_409`
      - `걷기_예산을_넘으면_400`
      - `락을_못_잡으면_503_과_Retry_After` — `new LockUnavailableException(...)` 의 `retryAfter()` 초 올림.
      - `일부_실패면_503_과_Retry_After_10`
      - `없애면_204`, `없앨_대상이_없으면_404`
    - 모든 오류 응답은 `application/problem+json` 이다.
  - `ScimHierarchyEndToEndTest`:
    - 골격은 `ScimEndToEndTest` 와 같다(`@TestMethodOrder`, Containers, `check(...)`, `CreatedIds`). 이력은 `ScimAuditHistoryEndToEndTest` 를 따른다(`@AutoConfigureObservability(metrics = false)`, `traceparent`).
    - 순서대로:
      1. **`평평한_그룹에_상위를_지정하면_롤업이_생긴다`**
         - SCIM 으로 직원 kim, 조직 TEAM{kim}, DIV{} 를 만든다.
         - `check(user:kim, member, group:DIV)` 거짓.
         - `PUT /admin/organizations/{TEAM}/parent` `{"parentOrgCode":"{DIV}"}` → 204.
         - 참.
      2. **`SCIM_에는_지정이_보이지_않고_관리_API_에는_보인다`**
         - `GET /scim/v2/Groups/{DIV}` 의 `members` 에 TEAM 없음.
         - `GET /admin/organizations/{TEAM}` `$.assignedParentOrgCode` = DIV.
         - DIV 상세 `$.childOrganizations[*].orgCode` 에 TEAM.
      3. **`IdP_가_멤버를_통째로_바꿔도_롤업이_남는다`** — `PUT /scim/v2/Groups/{DIV}`(같은 이름·멤버 빈 목록) → 여전히 참.
      4. **`이력에_지정과_원인이_남는다`** — `GET /admin/organizations/{TEAM}/history` 의 첫 줄 `cause.kind` = `HIERARCHY_REQUEST`, `cause.actor` = `client:test-client`, `attributes[0].name` = `assignedParent`.
      5. **`순환이면_409_없는_상위면_404_본문이_없으면_400`**
      6. **`재적재_뒤에도_롤업이_남는다`** — `POST /admin/sync/rebuild?mode=tuples` 를 끝까지 기다린 뒤 참(`SyncJobClient`).
      7. **`상위_조직을_IdP_가_지우면_줄과_지정이_사라진다`**
         - `DELETE /scim/v2/Groups/{DIV}` → 204.
         - `check(group:TEAM, child, group:DIV)` 거짓.
         - `state.findAssignedParent(TEAM).blockOptional()` 빈.
      8. **`없애면_롤업이_사라진다`** — 새 조직 DIV2 로 다시 지정 → 참 → `DELETE /admin/organizations/{TEAM}/parent` → 204 → 거짓.
      9. **`wipe_는_지정을_모두_지운다`** — DIV3 로 다시 지정 → `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` 를 끝까지 기다린 뒤 `state.findAssignedParent(TEAM).blockOptional()` 빈.
- [ ] **Step 2: 실패를 본다.** `./gradlew :admin-api:test --tests '*AdminExceptionHandlerTest'` 다음 `./gradlew :app-scim:test --tests '*OrganizationHierarchyControllerTest'` — 컴파일 실패.
- [ ] **Step 3: 구현한다.** 위 Produces 와 오류 표대로 한다.
- [ ] **Step 4: 통과를 본다.** `./gradlew :admin-api:test` 다음 `./gradlew :app-scim:test --tests '*OrganizationHierarchyControllerTest' --tests '*ScimHierarchyEndToEndTest'`.
- [ ] **Step 5: 커밋한다.** `feat: 지정 API(PUT·DELETE /admin/organizations/{orgCode}/parent) — 락 경합은 503 + Retry-After, 순환 409, 종단 시험 (조직 계층)`

---

### Task 9: 루트 README (설계 H13, §10)

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 고칠 곳을 찾는다.** `grep -n "롤업\|중첩 조직\|child\|admin.destructive\|asking-app\|mode=wipe\|관리 API" README.md`.
- [ ] **Step 2: 코드대로 고친다.**
  - **인가 모델 절(82~86):** 롤업의 원천이 둘(IdP 가 보낸 하위 조직 멤버, 운영자의 지정)이고 같은 `child` 튜플이 된다.
  - **SCIM 절, 새 소절 "조직 계층은 지정으로":**
    - Entra·Okta 는 그룹 안의 그룹을 보내지 않는다. 근거 링크는 설계 §1.1 의 Microsoft Learn·Snowflake 다. 실제 테넌트로 확인하지 않았다고 적는다.
    - 그래서 계층은 지정 API 로 준다. 표준대로 중첩 그룹을 보내는 SCIM 클라이언트는 그대로 된다.
  - **관리 API 표와 새 소절:**
    - 두 경로를 표에 더한다. 요청·응답은 설계 §3.1 의 표를 옮긴다(락 경합이 503 인 까닭 한 줄 포함).
    - 운영자가 쓰는 순서는 설계 §3.2 와 curl 예다(`org-chart` 토큰 받기 → lookup → PUT).
    - 조직 상세의 `assignedParentOrgCode` 를 적는다.
  - **인증 절:** 범위 표에 `admin.hierarchy`, 개발 클라이언트 표에 `org-chart` 를 더한다. "여섯 범위"·"다섯 클라이언트" 같은 수를 고친다.
  - **중첩 조직과 순환 절(1136~):** 지정은 순환이면 409 다. 재적재가 자른 지정 연결은 보류로 가고, 순환이 풀리면 돌아온다. 보류 줄의 원천은 멤버 행 또는 지정이다.
  - **IdP 의 하위 조직 빼기와 조직 지우기:** 지정이 받치는 줄은 남는다. 조직을 지우면 그 조직이 끼인 지정이 정리된다.
  - **wipe 절:** 지정을 모두 지운다. IdP 재프로비저닝 뒤 지정을 다시 건다.
- [ ] **Step 3: 맞는지 본다.** 고친 문단마다 설계·코드(Task 1~8 의 실제 이름·문구)와 맞춘다. `grep -n "admin.hierarchy\|org-chart\|assignedParentOrgCode" README.md` 가 각 절에 걸린다.
- [ ] **Step 4: 커밋한다.** `docs: README 에 조직 계층 지정을 싣는다 — Entra·Okta 의 평평한 그룹, 지정 API, 범위 admin.hierarchy, 순환·보류·wipe (조직 계층)`

---

### Task 10: 온보딩 문서 (설계 H13, §10)

**Files:**
- Modify: `docs/onboarding/` 아래 해당 파일들. 조사 §13 의 grep 목록을 참고한다:
  - `13-한계와-알려진-문제.md` (§2.2, 486행 표)
  - `02-용어집.md` (지정 연결·`admin.hierarchy`·`org-chart`, "개발 클라이언트는 다섯")
  - `09-관리-API.md`
  - `11-운영.md` (범위·클라이언트 표, 운영 예, 지정 스크립트 운영)
  - `12-장애와-복구.md` (wipe 뒤 지정 다시 걸기)
  - `07-재적재-초기화-아카이빙.md`
  - `14-개발-가이드.md`
  - `15-결정-색인.md` (새 결정 H1~H13 색인)
  - 롤업·`child`·보류 목록을 설명하는 곳

- [ ] **Step 1: 고칠 곳을 찾는다.** `grep -rn "롤업\|보류 목록\|child\|N5\|다섯\|여섯\|asking-app\|admin.destructive\|mode=wipe" docs/onboarding/`.
- [ ] **Step 2: 코드대로 고친다.**
  - 13 §2.2 를 "고침(설계 2026-11-04)" 으로 바꾼다. 남는 것은 벤더 동작을 실제 테넌트로 확인하지 않은 것과 운영자 원천과의 대응이다.
  - 용어집에 "지정 연결" 항목을 더한다.
  - 각 문서의 기준 커밋 줄을 이 브랜치의 마지막 코드 커밋(Task 8 의 커밋)으로 바꾼다.
- [ ] **Step 3: 맞는지 본다.** 링크 앵커(`02-용어집.md#…`)가 살아 있다. `grep -rn "다섯\|여섯" docs/onboarding/` 에 남은 것은 범위·클라이언트 수가 아니다.
- [ ] **Step 4: 커밋한다.** `docs: 온보딩에 조직 계층 지정을 옮긴다 — 13 §2.2 고침, 용어집 지정 연결, 범위·클라이언트 표, wipe 뒤 다시 걸기, 기준 커밋 (조직 계층)`

---

## 머지 전 (컨트롤러)

- 전체 `./gradlew test` 다음 `./gradlew scaleTest` 를 한 번에 하나씩 돌린다.
- 설계 §15 에 결과(시험 수·시간, 구현 중 판정, 남긴 것)를 적고 커밋한다.
