# 재활성 가드 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 막혀 있던 LDAP 계정이 한 회차에 한꺼번에 활성으로 읽히면 그 재활성을 보류한다(그 직원은 비활성 그대로, 나머지 변경은 반영). 실행 기록에 보류 내역을 남기고, 정말 다시 켠 것이면 확인한 수(`maxReactivations`)로만 넘기게 한다.

**Architecture:**
- LDAP 스냅샷이 그 회차 그림의 비활성 소속 줄 I 를 세 번째 묶음 계열로 함께 저장한다.
- 순수 판정 `ReactivationGuard`(core.guard)가 직전 I 와 이번 그림으로 재활성을 세고, 걸리면 그 직원들을 비활성으로 되돌린 그림′ 을 준다. 동기화·재적재는 매핑 앞에서 이것을 부르고 그 뒤의 모든 단계에 그림′ 을 넘긴다.
- 보류는 결론(`SyncOutcome`)에 내역과 앞 문구로 붙고 상태를 ABORTED 로 바꾼다. `SyncRun`·DynamoDB·관리 API 응답이 내역을 옮긴다.
- 강제 허용 수 `ForceAllowance` 가 두 수를 갖고, 준 수의 가드만 바꾼다.

**Tech Stack:** Java 17, Gradle 멀티 모듈, Spring Boot WebFlux/Reactor, Lombok, JUnit5/AssertJ/Mockito, DynamoDB SDK v2(저장소에 Jackson 없음), Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-24-reactivation-guard-design.md` — 결정 R1~R13, 동작 §3, 시험 §4, 한계 §6, 점검 §12. 이 계획은 그 설계를 따르고, 둘이 다르면 설계가 이긴다.

## Global Constraints

- **재활성 가드 문구**(숫자는 쉼표 없이), 그대로:
  `재활성 대상 %d명(직전 비활성 소속 직원 %d명의 %.1f%%)이 %s 초과했습니다. 강제 실행하려면 실행 기록의 reactivationHold 를 확인한 뒤 force=true 와 maxReactivations(허용할 재활성 직원 수)로 재요청하세요`
  - `%s` 세 꼴: `임계치 %.1f%%를`(비율만), `상한 %d명을`(상한만), `임계치 %.1f%%와 상한 %d명을`(둘 다). 임계치는 `thresholdRatio * 100`.
- **보류한 회차의 결론 앞 문구:** `재활성 가드 발동 — 직원 %d명의 재활성을 보류(비활성 그대로 둠) / ` + 위 가드 문구. `withNote` 와 같은 규칙으로 붙는다 — 원래 메시지가 있으면 그 뒤에 ` / ` 와 원래 메시지가 온다(예: ` / 변경 없음`).
- **보류한 회차의 상태(R6):** 결론이 `SUCCEEDED`·`PARTIAL` 이면 `ABORTED`, `ABORTED`·`FAILED` 는 그대로.
- **강제 회차의 결론 앞 문구(R11):** `강제 실행 — 지울 줄 허용 %d건` / `강제 실행 — 재활성 허용 %d명` / `강제 실행 — 지울 줄 허용 %d건·재활성 허용 %d명`. 가장 바깥에 한 번 붙는다(보류 문구보다 앞).
- **400 문구** 둘(옛 문구를 바꾼다):
  - `force=true 에는 maxDeletes(허용할 지울 줄 수) 또는 maxReactivations(허용할 재활성 직원 수)가 1 이상으로 함께 와야 합니다`
  - `maxDeletes·maxReactivations 는 force=true 와 함께만 씁니다`
- **검사 순서:** `force=true` 의 `admin.destructive` 403 → (force 인데 수가 하나도 없거나 준 수가 1 미만) 400 → (force 없이 수) 400 → (재적재) `mode` 400.
- **WARN 두 줄**, 그대로: `재활성 가드 발동: {}`(인자는 가드 문구 — 수만 담는다), `직전 스냅샷을 읽지 못해 재활성 가드를 보지 않는다: {}`(인자는 오류 메시지). id 를 로그에 싣지 않는다.
- **설정:** `sync.reactivation-guard.enabled`(true)·`threshold-ratio`(0.3)·`min-baseline`(10)·`max-reactivations`(100). 상한 0 이하면 상한을 보지 않는다. 값 검사는 두지 않는다(삭제 가드 설정에도 없다 — 설계 §11·§12).
- **예의 수:** 10개(`GuardHold.SAMPLE_SIZE`), `String` 자연 순서 앞에서부터. 수는 서로 다른 직원 id 의 수.
- **DynamoDB:**
  - 스냅샷: 정렬키 접두사 `INACTIVE#`(`Keys.INACTIVE_CHUNK_PREFIX`, 번호 네 자리 `%04d`, 0~9999), 메타 칸 `inactiveCount`·`inactiveChunkCount`(I 가 비어 있지 않을 때만). 칸이 없으면 빈 I.
  - 실행 기록: M 속성 `reactivationHold` — `count`(N), `ids`(L of S). 없으면 속성이 없다.
- **JSON:** 단건 응답의 `reactivationHold.{count,sample}`(모양은 `GuardHoldResponse.Sample`). 없으면 칸이 없다. 목록 응답에는 없다.
- **core 는 Micrometer·Spring·Jackson 을 모른다.** 새 core 타입은 순수 자바 record·클래스다.
- **시험은 벽시계를 단언하지 않는다.**
- **Gradle 은 한 번에 하나만 돌린다.** 서브에이전트는 각 과제가 적은 모듈·클래스 시험만 돌린다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- **git:** `git stash` 를 쓰지 않는다. 명령은 하나씩, 경로는 그대로 쓴다(`&&`·`$(...)` 없이).
  - 커밋 메시지는 한국어 `feat:`/`fix:`/`test:`/`docs:` 꼴이고 끝에 ` (재활성 가드)` 를 붙인다.
  - 트레일러는 `Co-Authored-By: Claude <자기 모델 이름> <noreply@anthropic.com>` 이다.
- **주석·시험 이름은 기존 꼴을 따른다.** 한국어 Javadoc, `@DisplayName` 한국어, 메서드 이름 한국어 밑줄. 근거는 `설계 2026-11-24 R4` 처럼 단다.

## Review Focus

1. **재활성 직원이 같은 회차에 조직도 옮긴 경우** — 보류면 새 조직의 `direct_member` 도 쓰지 않고, 옛 조직 줄은 B 에 없으므로 지울 줄도 생기지 않아야 한다. → 과제 4 의 시험.
2. **직전 I 에 있던 직원이 이번 그림에서 사라진 경우**(퇴사) — 재활성이 아니고 기준 수에는 든다. → 과제 1 의 시험.
3. **I 칸이 없는 옛 스냅샷 메타**를 읽으면 예외 없이 빈 I 다(운영 배포 전이라 이관하지 않는다). → 과제 2 의 시험.
4. **보류한 직원에게는 감사 이력 줄이 생기지 않아야 한다** — 현재상태가 바뀌지 않으므로. → 과제 4 의 시험(`LdapSyncAuditTest`).
5. **경계값** — 재활성 수가 상한과 같거나, 비율이 임계치와 정확히 같거나, 기준 수가 `min-baseline` 과 같을 때. 초과만 걸리고 기준 수는 "이상"이면 비율을 본다. → 과제 1 의 시험.

---

### Task 1: core — 재활성 판정과 두 수의 허용 수

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/ReactivationGuardPolicy.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/ReactivationGuard.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/ReactivationCheck.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/model/ReactivationHold.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/ForceAllowance.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`(`forced`)
- Test: `core/src/test/java/dev/starryeye/organization/core/guard/ReactivationGuardTest.java`(새), `ForceAllowanceTest.java`, `DeletionGuardTest.java`

**Interfaces:**
- Produces:
  - `public record ReactivationGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline, int maxReactivations)` + `static ReactivationGuardPolicy defaults()` = `(true, 0.3, 10, 100)`.
  - `public class ReactivationGuard` — `ReactivationGuard(ReactivationGuardPolicy)`, `static ReactivationGuard forced(int maxReactivations)` = 정책 `(true, 1.0, Integer.MAX_VALUE, M)`, `ReactivationCheck check(Set<RelationTuple> previousInactive, DirectorySnapshot picture)`.
  - `public record ReactivationCheck(DirectorySnapshot picture, ReactivationHold hold, String message)` + `boolean held()`(= `hold != null`) + `static ReactivationCheck pass(DirectorySnapshot picture)`(hold·message null).
  - `public record ReactivationHold(int count, List<String> ids)` — 컴팩트 생성자에서 `ids = List.copyOf(ids)`, `static ReactivationHold of(Set<String> userIds)`(수와 정렬한 앞 `GuardHold.SAMPLE_SIZE` 개).
  - `public record ForceAllowance(Integer maxDeletes, Integer maxReactivations)` — 둘 다 null 이거나 준 값이 1 미만이면 `IllegalArgumentException`. 보조 생성자 `ForceAllowance(int maxDeletes)` = `(maxDeletes, null)` 를 둔다(기존 호출 `new ForceAllowance(12500)` 이 그대로 컴파일되고 같은 값과 같다). `note()` 는 Global Constraints 의 세 꼴.
  - `DeletionGuard.forced(int maxDeletes)` — 인자가 `ForceAllowance` 에서 `int` 로 바뀐다. 본문은 그대로.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `ReactivationGuardTest`. I 는 `RelationTuple.directMember(id, "G")` 로, 그림은 `DirectorySnapshot(users, groups, Set.of())` 로 직접 만든다.
    - `재활성이_없으면_보류하지_않는다`: I 에 비활성 3명, 그림에서도 셋 다 비활성 → `held()` 거짓, `picture()` 는 받은 그림 그대로(같은 객체여도 된다).
    - `직전_I_의_직원이_활성이면_재활성이다`: 기준 10명 중 4명 활성(40%) → 보류, `hold().count()==4`, 문구가 `재활성 대상 4명(직전 비활성 소속 직원 10명의 40.0%)이 임계치 30.0%를 초과했습니다. 강제 실행하려면 …재요청하세요` 와 정확히 같다.
    - `상한만_넘으면_상한_문구다`: 정책 `(true, 0.9, 10, 2)`, 기준 10 중 3 → `상한 2명을`.
    - `둘_다_넘으면_둘_다_적는다`: 정책 `(true, 0.3, 10, 2)`, 기준 10 중 5 → `임계치 30.0%와 상한 2명을`.
    - `경계는_초과만_걸린다`(Review Focus 5): 기준 10 중 3(30%, 정확히 임계치)·상한 3 → 보류 없음. 기준이 정확히 `min-baseline`(10)이면 비율을 본다.
    - `기준_수가_min_baseline_미만이면_비율은_보지_않고_상한은_본다`: 기준 9 중 9 활성, 상한 100 → 보류 없음. 상한 5 → 보류(`상한 5명을`).
    - `상한_0_이하면_상한을_보지_않는다`, `가드를_끄면_보류하지_않는다`.
    - `forced_는_M명까지_통과하고_넘으면_보류한다`: `forced(3)` — 재활성 3명 통과, 4명 보류. 기준 수가 1이어도 같다(비율을 보지 않는다).
    - `그림에_없는_직원은_재활성이_아니고_기준에는_든다`(Review Focus 2): I 의 직원 10명 중 2명은 그림에 없고 3명은 활성 → 기준 10, 재활성 3(30%) → 보류 없음.
    - `user_접두사가_아닌_줄은_세지_않는다`: I 에 `new RelationTuple("group:X", "direct_member", "group:G")` 를 섞어도 기준 수에 들지 않고 예외가 없다.
    - `같은_직원의_줄_여럿은_한_명이다`: 한 직원이 조직 셋의 줄 → 기준 1.
    - `그림′_은_재활성_직원만_비활성이다`: 보류 뒤 `picture().users()` 에서 재활성 직원만 `active()==false`, 다른 직원·`groups()`·`assignedEdges()` 는 그대로.
    - `내역은_사전순_앞_10개다`: 재활성 15명(`E15`…`E01` 순서로 넣음) → `count==15`, `ids` 는 `E01`…`E10`.
  - `ForceAllowanceTest`: 기존 시험을 유지하고 더한다 — `(null, null)`·`(null, 0)`·`(0, 5)` 는 IAE, `(null, 7).note()` = `강제 실행 — 재활성 허용 7명`, `(12500, 7).note()` = `강제 실행 — 지울 줄 허용 12500건·재활성 허용 7명`, `new ForceAllowance(5)` 가 `new ForceAllowance(5, null)` 과 같다.
  - `DeletionGuardTest`: `DeletionGuard.forced(new ForceAllowance(90))` 세 곳을 `DeletionGuard.forced(90)` 으로 바꾼다.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.guard.*'` → 컴파일 실패(새 타입 없음).
- [ ] **Step 3: 구현한다.**
  - 판정 순서는 설계 §3.2 그대로다. 기준 = I 의 `user:` 줄에서 뗀 서로 다른 id. 재활성 = 그중 그림에 있고 활성인 id. 재활성이 비면 `pass`. 가드가 꺼졌으면 `pass`. 상한 초과와 임계치 초과(기준 ≥ `minBaseline`)를 따로 보아 하나라도 넘으면 보류.
  - 그림′ 은 직원 맵을 한 번 복사해 재활성 id 만 `withActive(false)` 로 바꾸고 `new DirectorySnapshot(users′, picture.groups(), picture.assignedEdges())` 로 만든다.
  - `DeletionGuard.forced` 의 Javadoc 과 `ForceAllowance` 의 Javadoc 을 두 수 기준으로 고친다(설계 2026-11-24 R9·R11). `ReactivationGuard` Javadoc 에 R2~R4 와 "그림만 고친다 — 뒤의 단계는 그림′ 을 본다"를 적는다.
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 명령 PASS. 이어서 `./gradlew :core:test` PASS(이 과제는 `DeletionGuard.forced` 호출처 둘 — `FullSyncUseCase`·`RebuildUseCase` — 도 `forced(allowance.maxDeletes())` 로 바꿔야 컴파일된다. 그 두 줄만 바꾸고 동작은 과제 4 가 맡는다. `maxDeletes` 가 null 인 허용 수는 아직 어느 입구에서도 만들지 않는다).
- [ ] **Step 5: 커밋한다** — `feat: 재활성 판정 ReactivationGuard(직전 비활성 소속 줄로 세고 걸리면 그림′)와 두 수의 허용 수 ForceAllowance (재활성 가드)`

### Task 2: 스냅샷의 비활성 소속 줄 I — core 모델과 DynamoDB 묶음 계열

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/TupleSnapshot.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/model/TupleSnapshotTest.java`, `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`

**Interfaces:**
- Produces:
  - `TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain, Set<RelationTuple> inactive)` — 새 정규 생성자. `inactive` 는 null 이면 빈 집합, 복사한다. B·U 와 겹쳐도 된다(겹침 검사는 B·U 사이에만). 지금의 5인자·4인자 생성자는 빈 I 로 위임한다.
  - `Keys.INACTIVE_CHUNK_PREFIX = "INACTIVE#"`, `Keys.inactiveChunkSk(int)`, `Keys.isInactiveChunkSk(String)` — `uncertainChunkSk`·`isUncertainChunkSk` 와 같은 꼴.
  - 저장소가 I 를 저장·읽고 만료 정리에서 지운다.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `TupleSnapshotTest`: `I_는_B_와_겹쳐도_된다`(같은 줄이 B 와 I 에 있어도 예외 없음), `5인자_생성자는_빈_I_다`.
  - `DynamoDbTupleSnapshotRepositoryTest`(지금 U 왕복 시험과 같은 자리·같은 준비):
    - `비활성_소속_줄을_왕복한다`: I 가 있는 스냅샷 저장 → `findLatest()`·`findById()` 의 `inactive()` 가 같다.
    - `비활성_소속_줄이_여러_묶음이어도_왕복한다`: 작은 `chunkSize` 생성자(지금 시험이 쓰는 것)로 I 를 묶음 여럿으로 나눈다.
    - `I_가_비면_메타에_칸이_없다`: 저장 뒤 메타 아이템에 `inactiveCount`·`inactiveChunkCount` 가 없다.
    - `I_칸이_없는_옛_메타는_빈_I_로_읽는다`(Review Focus 3): 칸 없이 쓴 메타·본문으로 읽으면 `inactive()` 가 빈 집합, 예외 없음.
    - `만료_정리가_I_묶음도_지운다`: 지난 스냅샷의 `INACTIVE#` 아이템이 정리 뒤 남지 않는다.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.model.TupleSnapshotTest'` → 컴파일 실패. 이어서 `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest'` → FAIL.
- [ ] **Step 3: 구현한다.**
  - 저장소에 세 번째 `ChunkSeries`(`"비활성 "`, `inactiveCount`, `inactiveChunkCount`, `Keys::isInactiveChunkSk`, `Keys::inactiveChunkSk`, 메타에 묶음 수가 없을 때의 문구)를 둔다. `EncodedChunks` 에 I 묶음을 더한다. 저장 순서는 메타 → 본문 → U → I → 포인터. 메타 칸은 U 처럼 비어 있지 않을 때만. 읽기는 U 처럼 칸이 있을 때만. `isChunkRow` 가 I 묶음도 묶음으로 본다.
  - 저장소 클래스 Javadoc 에 I 계열을 한 문단 더한다(설계 2026-11-24 R1·§3.1).
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 두 명령 PASS. 이어서 `./gradlew :core:test` PASS, `./gradlew :storage-dynamodb:test` PASS.
- [ ] **Step 5: 커밋한다** — `feat: 튜플 스냅샷에 그 회차 그림의 비활성 소속 줄 I 를 INACTIVE# 묶음 계열로 남긴다 (재활성 가드)`

### Task 3: 보류 내역의 칸 — 결론·실행 기록·DynamoDB·관리 API 응답

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncRun.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncStatus.java`(ABORTED 주석)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`
- Modify: `admin-api/src/main/java/dev/starryeye/organization/admin/SyncRunResponse.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/model/SyncOutcomeTest.java`(새), `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`, `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java`

**Interfaces:**
- Consumes: 과제 1 의 `ReactivationHold`.
- Produces:
  - `SyncOutcome` 의 마지막 구성 요소 `ReactivationHold reactivationHold`(null 가능). 지금의 6인자·7인자 생성자는 null 로 위임하는 보조 생성자로 남긴다. 정적 팩터리는 null. `withSuffix`·`withNote`·`withGuardHold` 는 칸을 보존한다.
  - `public SyncOutcome withReactivationHold(ReactivationHold hold, String note)` — 상태를 R6 대로 바꾸고(`SUCCEEDED`·`PARTIAL` → `ABORTED`), 칸을 넣고, `note` 를 `withNote` 규칙으로 앞에 붙인다.
  - `SyncRun` 의 마지막 구성 요소 `ReactivationHold reactivationHold`. `finished` 가 옮긴다.
  - `SyncRunResponse` 의 마지막 칸 `@JsonInclude(NON_NULL) GuardHoldResponse.Sample reactivationHold` — `from` 은 싣고(`new GuardHoldResponse.Sample(hold.count(), hold.ids())`), `listed` 는 null.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `SyncOutcomeTest`:
    - `보류는_성공과_부분을_ABORTED_로_바꾼다`: `succeeded(…)`·`partial(…)`·`noChange()` 에 적용 → `ABORTED`. `noChange()` 의 메시지는 `<note> / 변경 없음`.
    - `보류는_ABORTED_와_FAILED_를_그대로_둔다`: `aborted("m")`·`stopped(…)`·`failed("m")` → 상태 그대로, 메시지 `<note> / m`, 칸이 붙는다.
    - `보류는_건수와_스냅샷과_멈춤_내역을_보존한다`.
    - `withNote_와_withSuffix_와_withGuardHold_는_보류_칸을_보존한다`.
    - `결론의_보류_칸이_실행_기록으로_옮겨진다`: `SyncRun.started(…).finished(outcome, at).reactivationHold()`.
  - `DynamoDbSyncRunRepositoryTest`: `보류_내역을_왕복한다`(count·ids), `보류_내역이_없으면_속성이_없다`(아이템에 `reactivationHold` 없음), 빈 `ids` 도 왕복.
  - `AdminSyncControllerTest`: `단건_응답에_보류_내역이_있고_목록에는_없다` — `runs.findById` 가 보류 칸이 있는 기록을 주면 `$.reactivationHold.count`·`$.reactivationHold.sample[0]` 이 맞고, `runs.findRecent` 의 같은 기록은 목록에서 `$[0].reactivationHold` 가 없다. 칸이 없는 기록의 단건에도 칸이 없다.
- [ ] **Step 2: 실패를 확인한다** — 하나씩: `./gradlew :core:test --tests 'dev.starryeye.organization.core.model.SyncOutcomeTest'`, `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbSyncRunRepositoryTest'`, `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.AdminSyncControllerTest'` → FAIL(컴파일 또는 단언).
- [ ] **Step 3: 구현한다.**
  - DynamoDB 는 지금 `guardHold` 의 갈래 하나(`count`·`ids`)를 쓰고 읽는 도우미가 있으면 그것을 쓴다. 없으면 같은 꼴로 둔다.
  - `SyncStatus.ABORTED` 주석을 "가드가 걸렸다 — 무엇을 반영·보류했는지는 메시지와 두 내역(`guardHold`·`reactivationHold`)에 있다"로 고친다(설계 2026-11-24 R6).
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 세 명령 PASS(하나씩). 이어서 `./gradlew :core:test` PASS.
- [ ] **Step 5: 커밋한다** — `feat: 재활성 보류 내역 reactivationHold 를 결론·실행 기록·DynamoDB·단건 응답에 싣고 보류한 결론은 ABORTED 로 바꾼다 (재활성 가드)`

### Task 4: core — 동기화·재적재가 매핑 앞에서 재활성을 판정하고 그림′ 으로 진행한다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`(`commit` 의 스냅샷 I)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`, `RebuildUseCaseTest.java`, `LdapSyncAuditTest.java`

**Interfaces:**
- Consumes: 과제 1 의 `ReactivationGuard`·`ReactivationCheck`·`ForceAllowance`, 과제 2 의 `TupleSnapshot.inactive()`·6인자 생성자, 과제 3 의 `SyncOutcome.withReactivationHold`.
- Produces:
  - 생성자 `FullSyncUseCase(DirectorySnapshotSource, TupleSnapshotRepository, DirectoryStateRepository, RelationTupleWriter, RelationTupleScanner, DeletionGuard guard, ReactivationGuard reactivationGuard, SyncJobs, Clock)` — `RebuildUseCase` 도 같은 자리에 `ReactivationGuard` 를 더한다. 옛 8인자 생성자는 남기지 않는다(호출처: app-ldap `UseCaseConfig` 2곳은 과제 5, core 시험 6곳은 이 과제가 고친다).
  - `static Mono<Mono<SyncOutcome>> 재활성_보류를_적는다(Mono<Mono<SyncOutcome>> 일, ReactivationCheck check)`(FullSyncUseCase, 패키지 공개) — 보류면 "반납 뒤 할 일"의 결론에 `withReactivationHold(check.hold(), 앞 문구)` 를 적용한다. 재적재도 쓴다. 앞 문구는 Global Constraints 그대로.
  - 강제 입구는 그대로(`startForced(ForceAllowance, …)`). 회차를 띄울 때 두 가드를 한 번 고른다 — `maxDeletes` 가 있으면 `DeletionGuard.forced(maxDeletes)`, `maxReactivations` 가 있으면 `ReactivationGuard.forced(maxReactivations)`, 없는 쪽은 설정의 가드.

- [ ] **Step 1: 실패하는 시험을 쓴다.** 도우미 `유스케이스(DeletionGuardPolicy, ReactivationGuardPolicy)` 를 두고, 보류를 일으키는 시험은 낮은 상한 정책(예 `(true, 0.3, 10, 1)`)이나 기준 10명 이상을 쓴다. 첫 회차로 막힌 직원이 든 스냅샷을 만든 뒤(기존 `조직도(활성, 비활성, 조직)` 도우미) 두 번째 회차의 그림을 바꾼다.
  - `FullSyncUseCaseTest`(비교 경로):
    - `막혔던_직원이_한꺼번에_활성이면_재활성을_보류한다`: 두 번째 회차에서 막힌 직원 둘이 활성 + 새 입사자 하나 → 쓴 줄은 입사자의 것뿐, 상태 `ABORTED`, `reactivationHold.count==2`, 메시지가 `재활성 가드 발동 — 직원 2명의 재활성을 보류(비활성 그대로 둠) / 재활성 대상 2명(…` 로 시작, 현재상태(`state`)에서 두 직원 `active()==false`, 마지막 스냅샷의 `inactive()` 에 두 직원의 줄이 있다.
    - `같은_그림이면_다음_회차도_보류한다`.
    - `다시_비활성으로_읽히면_보류가_풀린다`: 보류 뒤 세 번째 회차에서 둘이 다시 비활성 → 상태 `SUCCEEDED`, `reactivationHold` null, 지운 줄 0.
    - `문턱_아래면_그대로_쓴다`: 재활성 하나(상한 1, 기준 미만) → `SUCCEEDED`, 그 직원의 줄을 쓰고, 다음 스냅샷 I 에서 빠진다.
    - `재활성_직원이_조직을_옮겨도_보류하면_어느_줄도_쓰지_않는다`(Review Focus 1): 막혔던 직원이 활성 + 다른 조직으로 → 쓴 줄·지운 줄 모두 그 직원의 것이 없다.
    - `변경이_없어도_보류하면_ABORTED_이고_끝이_변경_없음이다`.
    - `삭제_가드와_함께_걸리면_두_내역이_있다`: 같은 회차에 지울 줄이 삭제 가드를 넘게 한다 → `ABORTED`, `guardHold`·`reactivationHold` 둘 다 null 아님, 메시지는 보류 문구가 앞이고 삭제 가드 문구가 뒤.
    - `비활성_회수만_반영한_회차도_그림′_의_I_를_남긴다`: 삭제 가드 일부 반영 + 재활성 보류 → 저장한 스냅샷의 `inactive()` 에 보류 직원의 줄이 있다.
    - `차단기로_멈춰도_FAILED_그대로이고_보류_내역이_붙는다`.
    - `재활성만_강제로_넘기면_허용_수_안에서_쓴다`: `startForced(new ForceAllowance(null, 2), …)` → 보류 없음, 두 직원의 줄을 쓰고, 메시지가 `강제 실행 — 재활성 허용 2명` 으로 시작. 같은 회차의 삭제 가드는 설정대로 본다(지울 줄이 설정을 넘으면 걸린다).
    - `허용_수를_넘으면_강제해도_보류한다`: `ForceAllowance(null, 1)` 에 재활성 둘 → 보류, 메시지가 `강제 실행 — 재활성 허용 1명 / 재활성 가드 발동 — …` 로 시작.
    - `설정에서_꺼_두어도_강제_회차는_허용_수를_본다`.
    - `기록_중_회차는_최신_스냅샷의_I_로_판정한다`: `snapshots.writing` 을 켜고 재활성 둘 → 장부 맞추기 결론에 보류, `기준선 의심 …` 사정 문구도 남는다.
    - `기록_중_회차가_스냅샷을_못_읽으면_판정_없이_맞춘다`: `snapshots.failFindLatest(…)` + 기록 중 → 재활성 줄을 쓰고 `reactivationHold` null, 회차는 실패하지 않는다.
    - `기록_중_회차에서_재활성만_강제하고_삭제_가드가_전부_보류면_다음_회차가_다시_센다`(설계 §6·§12 고정): 첫 회차는 줄을 쓰고 스냅샷을 남기지 않는다, 다음 강제 아닌 회차는 같은 직원을 다시 재활성으로 센다.
    - `기준선_없는_회차는_판정하지_않는다`.
  - `RebuildUseCaseTest`: `재적재도_스냅샷이_있으면_재활성을_보류한다`, `재적재는_스냅샷이_없으면_판정하지_않는다`, `재적재는_스냅샷을_못_읽어도_판정_없이_끝낸다`(`failFindLatest`), 장부 맞추기 커밋의 스냅샷 `inactive()` 가 그림′ 의 것.
  - `LdapSyncAuditTest`: `보류한_직원은_이력이_없다`(Review Focus 4) — 보류한 회차의 이력에 그 직원의 줄(속성 변경·부여)이 없고, 같은 회차의 입사자 이력은 있다. 이 파일의 생성자 호출 4곳에 `new ReactivationGuard(ReactivationGuardPolicy.defaults())` 를 더한다.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.FullSyncUseCaseTest' --tests 'dev.starryeye.organization.core.usecase.RebuildUseCaseTest' --tests 'dev.starryeye.organization.core.usecase.LdapSyncAuditTest'` → 컴파일 실패 또는 새 시험 FAIL.
- [ ] **Step 3: 구현한다.** 흐름은 설계 §3.3 그대로다.
  - `synchronize`: `source.fetchAll()` → `snapshots.baselineState()` → 상태별로 직전 I 를 정한다.
    - `PRESENT`: 최신 스냅샷을 **한 번** 읽는다(비면 지금의 `SnapshotIntegrityException` 문구 그대로). 그 스냅샷의 I 로 판정하고, 같은 스냅샷을 `비교해_맞춘다` 의 기준선으로 넘긴다 — 지금 `비교해_맞춘다` 안의 `baseline()` 읽기를 이것으로 바꾼다.
    - `WRITING`: `findLatest()` 의 I, 비면 빈 집합, 오류면 WARN `직전 스냅샷을 읽지 못해 재활성 가드를 보지 않는다: {}` 후 빈 집합.
    - `NONE`: 빈 집합(판정 없음).
    - 판정 뒤: 보류면 WARN `재활성 가드 발동: {}`. 매핑(`TupleMapper.toTuples`)과 그 경고 로그, 삭제 가드의 `inactiveMemberTuples`·`GuardHold.of`, 현재상태 교체, 스냅샷 I 는 모두 `check.picture()` 다. 경로의 결과를 `재활성_보류를_적는다` 로 감싼다.
  - 스냅샷 I: `스냅샷을_저장한다` 가 그림을 받아 `TupleMapper.inactiveMemberTuples(그림)` 을 6인자 생성자로 넣는다. 부르는 자리(커밋, 차단기 멈춤, `비활성_회수만_반영한다` 의 둘)에 그림′ 을 넘긴다 — `비활성_회수만_반영한다` 는 그림을 인자로 더 받는다. `LedgerAlignment.commit` 도 받은 그림(그림′)의 I 를 넣는다.
  - 재적재: `rebuild` 가 `findLatest()` 를 기록 중과 같은 규칙으로 읽어 판정하고, 그림′ 으로 매핑해 `alignment.align` 에 넘기고, 결과를 `재활성_보류를_적는다` 로 감싼다.
  - 결론 순서: 경로의 결론(장부 맞추기 사정 문구 포함) → `재활성_보류를_적는다` → `허용_수를_적는다`(가장 바깥, 지금 그대로).
  - 클래스 Javadoc(`FullSyncUseCase`·`RebuildUseCase`·`LedgerAlignment`)에 재활성 가드 한 문단을 더한다(설계 2026-11-24 R4·R8, §6 의 장부 맞추기 한계).
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 명령 PASS. 이어서 `./gradlew :core:test` PASS.
- [ ] **Step 5: 커밋한다** — `feat: 동기화·재적재가 매핑 앞에서 재활성을 판정하고 걸리면 그 직원을 비활성으로 되돌린 그림으로 진행하며 스냅샷에 I 를 남긴다 (재활성 가드)`

### Task 5: app-ldap — 설정·결선·`maxReactivations` 요청·종단 시험

**Files:**
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncProperties.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java`
- Modify: `app-ldap/src/main/resources/application.yml`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncPropertiesTest.java`, `AdminSyncControllerTest.java`, `LdapSyncEndToEndTest.java`

**Interfaces:**
- Consumes: 과제 1 의 `ReactivationGuard`·`ReactivationGuardPolicy`·`ForceAllowance(Integer, Integer)`, 과제 4 의 9인자 생성자.
- Produces:
  - `SyncProperties.ReactivationGuardConfig`(`enabled`·`thresholdRatio`·`minBaseline`·`maxReactivations`, 기본 Global Constraints 그대로), `SyncProperties.getReactivationGuard()`.
  - 빈 `ReactivationGuard reactivationGuard(SyncProperties)` — `deletionGuard` 빈과 같은 꼴. `fullSyncUseCase`·`rebuildUseCase` 빈이 그것을 받는다.
  - `application.yml` 의 `sync.reactivation-guard:` 블록(`deletion-guard:` 아래, 같은 들여쓰기·네 값).
  - `POST /admin/sync/full`·`POST /admin/sync/rebuild` 의 `@RequestParam(required = false) Integer maxReactivations`. 허용 수 도우미가 세 값을 받아 Global Constraints 의 순서·문구로 판정하고 `new ForceAllowance(maxDeletes, maxReactivations)` 를 만든다. 요청 로그에 `maxReactivations` 를 더한다.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `SyncPropertiesTest`: 기본값 넷, `sync.reactivation-guard.*` 바인딩(기존 삭제 가드 바인딩 시험과 같은 방식).
  - `AdminSyncControllerTest`(동기화·재적재 각각, 기존 강제 시험과 같은 준비):
    - `maxReactivations_만으로_강제한다`: `startForced(eq(new ForceAllowance(null, 2)), any())`.
    - `두_수를_함께_준다`: `ForceAllowance(12500, 2)`.
    - `force_에_수가_하나도_없으면_400`, `maxReactivations_가_0_이면_400`(새 문구), `force_없이_maxReactivations_만_오면_400`(새 문구).
    - `범위가_없으면_maxReactivations_보다_403_이_먼저다`.
    - `재적재의_허용_수_400_은_mode_400_보다_먼저다`(maxReactivations=0 + mode).
    - 기존 시험의 옛 400 문구 단언을 새 문구로 바꾼다.
  - `LdapSyncEndToEndTest` — 새 `@Order(16)` 시나리오(지금 마지막은 `@Order(15)`). `@DynamicPropertySource` 에 `sync.reactivation-guard.max-reactivations=1` 을 더한다(설계 §11 — 앞 순서가 한 회차에 둘 이상을 다시 켜지 않는지 확인한다. `@Order(9)` 는 kim 하나다).
    - 전제: kim 은 `userAccountControl` `514` 로 막고, park 은 `accountExpires` 를 지우고 `514` 로 막는다 → 동기화(두 직원이 I 에 든 스냅샷).
    - 두 직원의 `userAccountControl` 을 지운다(읽지 못함을 흉내 낸다) → 동기화 → 실행 기록 단건이 `ABORTED`, `$.reactivationHold.count` 2, `$.reactivationHold.sample` 이 `["kim","park"]`, `check("user:kim", "direct_member", "group:DEV002")` 거짓, 현재상태의 kim `active()` 거짓.
    - `514` 로 되돌린다 → 동기화 → `ABORTED` 가 아니다.
    - 두 직원을 `512` 로 켠다 → 동기화 → 다시 `ABORTED`. 이어서 `force=true&maxReactivations=2` 로 동기화(기존 `@Order(13)` 의 강제 요청 방식) → 메시지가 `강제 실행 — 재활성 허용 2명` 으로 시작하고 kim 의 `direct_member` Check 가 참.
- [ ] **Step 2: 실패를 확인한다** — 하나씩: `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.SyncPropertiesTest'`, `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.AdminSyncControllerTest'` → FAIL. (`UseCaseConfig` 가 과제 4 의 생성자로 아직 컴파일되지 않으면 이 단계는 컴파일 실패다.)
- [ ] **Step 3: 구현한다.** 컨트롤러 Javadoc(두 엔드포인트, 허용 수 도우미)의 검사 순서·설명을 두 수 기준으로 고친다(설계 2026-11-24 R9·R10). `SyncProperties.ReactivationGuardConfig` Javadoc 에 기본값이 잰 값이 아님을 적는다.
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 두 명령 PASS. 이어서 `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.LdapSyncEndToEndTest'` PASS. 마지막으로 `./gradlew :app-ldap:test` PASS.
- [ ] **Step 5: 커밋한다** — `feat: app-ldap 에 sync.reactivation-guard 설정과 결선, force=true&maxReactivations=M 요청, 속성을 못 읽는 종단 시나리오를 더한다 (재활성 가드)`

### Task 6: 문서 — README 와 온보딩을 현재 동작으로

**Files:**
- Modify: `README.md`
- Modify: `docs/onboarding/` 의 02·04·05·07·09·11·12·13·15, 그리고 `maxDeletes` 를 "`force=true` 에 반드시 함께"로 적은 다른 온보딩 문서와 `docs/onboarding/README.md`

**Interfaces:**
- Consumes: 과제 1~5 의 동작·이름·문구. 마지막 코드 커밋(과제 5 의 커밋) 번호.

- [ ] **Step 1: 고칠 곳을 찾는다** — `grep -rn "maxDeletes\|force=true\|삭제 가드\|부여 쪽\|4.6" README.md docs/onboarding` 로 목록을 만든다. 옛 설계 문서(`docs/superpowers/`)는 그때의 기록이라 고치지 않는다.
- [ ] **Step 2: 고친다.**
  - **02 용어집:** `재활성 가드`, `재활성 보류`(보류 내역 `reactivationHold`), `비활성 소속 줄`(스냅샷의 I)을 더한다. `멈춤 내역` 항목에 두 내역의 차이를 한 줄 적는다.
  - **04 데이터 모델:** 스냅샷의 `INACTIVE#` 묶음과 메타 칸 둘, 실행 기록의 `reactivationHold` 속성.
  - **05 LDAP 동기화:** 가드 절에 재활성 가드 하위 절 — 판정(R2·R3), 그림′(R4), 어느 회차에서 보는지(R8), 상태와 문구(R6), 회차 흐름(설계 §3.3).
  - **07 재적재:** 재적재도 스냅샷이 있으면 재활성을 보고, 없거나 못 읽으면 보지 않는다. `maxReactivations`.
  - **09 관리 API:** 두 엔드포인트의 `maxReactivations`, 검사 순서와 400 문구 둘, 단건의 `reactivationHold` JSON 예, 강제 문구 세 꼴.
  - **11 운영:** `sync.reactivation-guard.*` 설정 표, 경보 해석(`sync.guard.aborted` 가 두 원인을 가진다, 보류 중에는 `sync.last.success` 도 멈춘다).
  - **12 장애와 복구:** §4.6 을 설계 §3.6 의 절차로 다시 쓴다(저절로 → 보류, 보이는 것, 할 일, 두 수를 함께 주는 경우). §4.4 의 강제 문장에 "재활성도 보류됐으면 두 수를 함께"를 잇는다.
  - **13 한계:** §3.3 을 일부 고친 항목으로 — 재활성 가드가 막는 것과 남은 것(일반 부여 가드, SCIM). 새 항목 §3.12 "재활성 가드가 말하지 않는 것"(설계 §6 의 항목들, 상태 받아들임). §8 한눈에 표.
  - **15 결정 색인:** 새 절 "재활성 가드(R1~R13)".
  - **README:** 삭제 가드를 설명하는 절과 설정 표, 강제 요청 예, 경보 절에 재활성 가드를 같은 깊이로 더한다.
  - 고친 온보딩 문서의 머리 `기준 커밋:` 을 `<과제 5 의 커밋> (reactivation-guard, main 에 머지됨)` 으로 바꾼다.
- [ ] **Step 3: 확인한다** — 문서 안 링크(`#…` 앵커)가 실제 제목과 맞는지 `grep` 으로 본다. 새 절 제목을 바꿨으면 그 앵커를 쓰는 곳을 함께 고친다.
- [ ] **Step 4: 커밋한다** — `docs: 재활성 가드·보류 내역·force=true&maxReactivations=M 을 README 와 온보딩에 반영하고, 12 §4.6 을 다시 쓰고 13 §3.3 을 일부 고친 항목으로 옮긴다 (재활성 가드)`
