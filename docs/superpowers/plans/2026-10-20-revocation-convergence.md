# 회수 수렴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 퇴사자 권한 회수가 끝까지 수렴하도록 만든다.
- 결과를 모르는 OpenFGA 쓰기(N1)
- 기준선 없음(N6)
- 전부/전무 삭제 가드(N3)
- SCIM 회수 의도 소실(N2)
- LDAP 긴 값(N7)
- LDAP 재시도 창·신선도(N8)

**Architecture:**
- core 의 동기화 유스케이스가 실패 종류(확정/불확실)와 스냅샷의 불확실 줄로 다음 회차를 정한다.
- 가드는 비활성으로 생긴 회수만 떼어 다시 본다.
- SCIM 은 요청한 `active` 를 저장하고, 하루 아카이빙이 어긋난 직원을 락 안에서 다시 맞춘다.
- 저장소는 불확실 줄을 따로 된 압축 묶음 계열로 저장하고, 색인 정렬키를 잘라 쓴다.
- LDAP 은 재시도 창을 설정으로 늘리고, app-ldap 이 마지막 성공 시각 게이지를 낸다.

**Tech Stack:**
- Java 17, Spring Boot 3.5 WebFlux, Reactor
- AWS SDK v2 DynamoDB(Local 로 시험), OpenFGA Java SDK 0.9.11, Spring LDAP
- JUnit 5·AssertJ·Testcontainers, Micrometer

**Spec:** `docs/superpowers/specs/2026-10-20-revocation-convergence-design.md` — 설계가 권위다. 이 계획과 부딪히면 설계를 따른다.

## Global Constraints

- 코드 관례: 기존 코드처럼 한국어 메서드·변수 이름을 쓴다(`쪼개며_보낸다`).
- 시험 관례:
  - 시험 메서드는 한국어 이름에 `@DisplayName` 한국어 문장을 단다.
  - 본문은 `// given / when / then` 으로 나눈다.
  - 단언은 AssertJ 다.
- Gradle:
  - 한 번에 Gradle 하나만 돌린다.
  - 구현 서브에이전트는 자기 과제의 모듈 시험만 돌린다(`./gradlew :<module>:test --tests '...'`).
  - 전체 `test`·`scaleTest` 는 컨트롤러가 머지 전에 돌린다.
- git:
  - `git stash` 를 쓰지 않는다.
  - 커밋 메시지는 최근 `git log` 처럼 한국어 `fix:`/`feat:`/`docs:` 한 문장이고 끝에 `(회수 수렴)` 을 붙인다.
  - 커밋 끝줄은 `Co-Authored-By: <모델 이름> <noreply@anthropic.com>` 이다.
  - 바꾼 파일만 스테이징한다.
- OpenFGA Read(`RelationTupleScanner`)는 재적재와, 기준선을 믿을 수 없는 LDAP 회차(포인터의 "기록 중"·포인터 없음)에서만 쓴다. 다른 길에 넣지 않는다.
- 판단·쓰기는 Check·BatchCheck 만 쓴다. store 번호는 바꾸지 않는다.
- 규모 10만 명: 요청마다 전원을 읽지 않는다. 아카이빙 복구는 어긋난 직원만 다시 맞춘다.
- 배포 하나에 커넥터 하나다. LDAP 과 SCIM 이 함께 도는 경우는 따지지 않는다.
- 운영 배포 전이다. 옛 형식 스냅샷은 이관하지 않는다(U 칸이 없으면 빈 U).
- 설계 §5.3 의 메시지 문구(그대로 쓴다): `"삭제 가드 발동 — 비활성 회수 %d건만 반영하고 나머지 지우기 %d건·쓰기 %d건은 보류"`.
- 설계 §4 의 회차 메모(그대로 쓴다): `"기준선 없음(첫 회차 또는 새 테이블) — 장부를 훑어 맞춤"`.
- 설계 §8 의 숫자:
  - `ldap.max-retries` 기본 5
  - `ldap.retry-min-backoff` 10s
  - `ldap.retry-max-backoff` 2m
  - `sync.cron` 기본 `"0 0 * * * *"`
  - 게이지 이름 `sync.last.success`(epoch 초, 태그 `source=LDAP`), 1분마다 갱신
- 설계 §6.4 의 게이지 이름: `scim.archive.drift`, 태그 `kind=extra|missing`.
- 설계 §7 의 숫자: 색인 정렬키 상한은 `Keys.MAX_SORT_KEY_BYTES`(1024)다.

## Review Focus

- **새 LDAP 재시도 기본값(10초~2분, 5번)이 기존 시험을 몇 분씩 늘이면 안 된다.** 재시도를 타는 시험은 짧은 값을 써야 한다(Task 9).
- **불확실 줄(U)이 남은 상태에서 다음 회차가 가드의 일부 반영을 받아도 식이 맞아야 한다.** U 의 줄이 사라지거나 B 와 겹치면 안 된다(Task 5).
- **기준선 없는 첫 회차가 가드에 통째로 걸리면** 스냅샷이 없으므로, 다음 회차도 장부를 다시 훑어야 한다(Task 4).
- **처음 만드는 직원(POST)이 OpenFGA 단계에서 실패하면** 오류 경로에서도 레코드를 만들지 않는다. 의도를 저장하다 실패하면 원래 오류가 그대로 나간다(Task 6).
- **아카이빙이 어긋남을 본 뒤 복구 전에 그 직원이 지워졌으면** 복구는 건너뛰고 실패로 세지 않는다(Task 7).

---

### Task 1: 실패 종류 — 확정과 불확실

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/TupleFailure.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/TupleWriteResult.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java` (`쪼개며_보낸다`, `Batch`)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/model/TupleWriteResultTest.java` (없으면 만든다)
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterResultTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java`

**Interfaces:**
- Produces:
  - `record TupleFailure(RelationTuple tuple, String reason, boolean uncertain)`. 2인자 생성자 `TupleFailure(tuple, reason)` 는 `uncertain=false`(확정)이다.
  - `static TupleFailure uncertain(RelationTuple tuple, String reason)`
  - `Set<RelationTuple> TupleWriteResult.uncertain()`: 실패 가운데 `uncertain` 인 줄.
  - `FakeTupleWriter.lostResponseFor(Predicate<RelationTuple>)`: 걸린 줄은 가짜 장부(`stored`, `written`/`deleted` 기록)에 실제로 반영하지만, 결과에는 쓴·지운 줄로 넣지 않고 불확실 실패(`"테스트용 응답 유실"`)로 넣는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `TupleWriteResultTest.불확실_실패만_불확실_줄로_꺼낸다`: 실패 `[확정 a, 불확실 b]` → `uncertain()` 는 `{b}`.
  - `OpenFgaRelationTupleWriterResultTest` 에 더한다(기존 `쪼개며_보낸다` 시험 방식, 가짜 `send`):
    - `한_줄까지_좁혀도_거절되면_확정_실패다` → `failures().get(0).uncertain()` 는 false.
    - `일시_오류로_재시도를_다_쓴_묶음은_불확실_실패다`: `send` 가 거절이 아닌 오류 → 모든 실패가 `uncertain()` true.
  - `OpenFgaRelationTupleWriterBreakerTest` 에 더한다(기존 차단기 시험 방식):
    - `차단기로_보내지_않은_묶음은_확정_실패다`: "연속 실패로 보내지 않음" 줄은 false.
    - `앞_단계_실패로_보내지_않은_쓰기는_확정_실패다`: "앞 단계 실패로 보내지 않음" 줄은 false. 일시 오류로 실패한 앞 단계 묶음 줄은 true.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*TupleWriteResultTest*'`, `./gradlew :authz-openfga:test --tests '*OpenFgaRelationTupleWriterResultTest*' --tests '*OpenFgaRelationTupleWriterBreakerTest*'`. 컴파일 실패나 단언 실패를 기대한다.
- [ ] **Step 3: 구현한다**
  - `Batch` 에 `TupleWriteResult uncertain(String reason)` 을 더하고, `쪼개며_보낸다` 의 일시 오류 갈래(`log.error("배치 {}건 적용 실패"…)`)가 그것을 쓴다.
  - 거절 갈래와 보내지 않은 갈래는 `failed` 그대로다(확정).
  - `FakeTupleWriter.lostResponseFor` 를 더한다. `failFor` 와 겹치면 `failFor` 가 이긴다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령, PASS.
- [ ] **Step 5: 커밋** — `feat: OpenFGA 쓰기 실패를 확정(거절·보내지 않음)과 불확실(보냈는데 결과 모름)로 나눈다 (회수 수렴)`

---

### Task 2: 스냅샷의 불확실 줄과 포인터 상태(저장소·포트)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/TupleSnapshot.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (호출만 바꾼다. 동작은 그대로)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`

**Interfaces:**
- Produces:
  - `record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples, Set<RelationTuple> uncertain)`. 4인자 생성자는 `uncertain` 을 빈 집합으로 둔다. `meta()` 의 튜플 수는 `tuples` 만 센다.
  - `TupleSnapshotRepository.BaselineState { NONE, WRITING, PRESENT }` 와 `Mono<BaselineState> baselineState()`. `isWriting()` 은 지운다.
    - 포인터 줄이 없다 → `NONE`
    - `writingSince` 가 있다 → `WRITING`
    - 스냅샷 번호가 있다 → `PRESENT`
    - 그 밖 → `NONE`
  - `Keys.uncertainChunkSk(int index)`: `"UNCERTAIN#%04d"` 이고 0~9999 다. `Keys.isUncertainChunkSk(String sk)`.
  - `FakeSnapshotRepository.baselineState()`: `writing` 이면 `WRITING`, 저장한 것이 없으면 `NONE`, 아니면 `PRESENT`.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `KeysTest.불확실_묶음_정렬키는_네_자리_번호다`
  - `DynamoDbTupleSnapshotRepositoryTest` 에 더한다:
    - `불확실_줄이_있는_스냅샷을_저장하고_그대로_읽는다`: B 3줄, U 2줄 → `findLatest()` 의 `tuples` 와 `uncertain` 이 같다. 작은 묶음 크기 생성자로 U 가 묶음 여럿이 되게 한다.
    - `불확실_줄이_없으면_메타에_칸을_남기지_않고_빈_집합으로_읽는다`
    - `불확실_묶음이_빠지면_무결성_예외다`: U 묶음 하나를 지운 뒤 `findLatest()` → `SnapshotIntegrityException`.
    - `정리는_불확실_묶음도_지운다`: 기한 지난 스냅샷을 정리하면 파티션이 빈다.
    - `포인터가_없으면_기준선_없음이다`
    - `기록_중이면_기록_중이다`: 포인터가 없는데 `markWriting` 만 한 경우와, 스냅샷이 있는데 `markWriting` 한 경우 둘 다.
    - `스냅샷을_저장하면_기준선_있음이다`
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :storage-dynamodb:test --tests '*DynamoDbTupleSnapshotRepositoryTest*' --tests '*KeysTest*'`
- [ ] **Step 3: 구현한다**
  - **저장:** U 가 비어 있지 않으면 메타에 `uncertainCount`·`uncertainChunkCount` 를 적고, `SnapshotChunks.encode(U, chunkSize)` 묶음을 `uncertainChunkSk` 로 하나씩 PutItem 한다(본문 묶음과 같은 까닭). 순서는 메타 → 본문 묶음 → U 묶음 → 포인터다.
  - **읽기:** 메타에 `uncertainCount` 가 있으면 U 묶음을 번호 순서로 모아 풀고, 수와 묶음 수를 본문과 같은 규칙으로 검사한다. 없으면 빈 U 다.
  - **정리:** `deleteChunks` 가 두 접두어를 모두 하나씩 지운다. 옛 형식 줄 지우기에는 U 묶음이 들어가지 않는다.
  - **포트:** `isWriting` 을 `baselineState` 로 바꾼다. `FullSyncUseCase` 는 `baselineState().map(s -> s == WRITING)` 으로 지금 동작을 유지한다(Task 4 가 바꾼다).
  - core 의 기존 `isWriting` 시험은 `baselineState` 로 옮긴다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령과 `./gradlew :core:test`, PASS.
- [ ] **Step 5: 커밋** — `feat: 튜플 스냅샷에 결과를 모르는 줄(U)을 따로 된 압축 묶음으로 저장하고, 포인터를 없음·기록 중·있음 셋으로 읽는다 (회수 수렴)`

---

### Task 3: N1 — 불확실 줄로 다음 회차를 정한다(core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (`비교해_맞춘다`, `baseline`, `saveSnapshotAndState`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java` (`Reconciliation`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java` (`commit`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` (스냅샷에 U 를 넘긴다)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java` (있는 곳에 더한다)

**Interfaces:**
- Consumes:
  - Task 1: `TupleWriteResult.uncertain()`, `FakeTupleWriter.lostResponseFor`
  - Task 2: `TupleSnapshot.uncertain()`, 5인자 생성자
- Produces:
  - `TupleReconciler.Reconciliation` 에 `Set<RelationTuple> uncertain` 칸이 생긴다.
  - `ledger` 의 뜻이 바뀐다: 훑을 때 있던 D 의 줄 ∪ 확정적으로 쓴 줄 ∪ (지울 후보 − 지운 줄 − U). `uncertain` 은 불확실하게 실패한 지우기다.
  - Task 5 가 `Reconciliation` 을 만들 때 지울 후보(stale)를 넘겨 이 식을 쓴다.
  - 비교 경로의 식(설계 §3.2·§3.3):
    - 델타: 쓸 줄 = D − B, 지울 줄 = (B − D) ∪ (U − D)
    - 가드 기준 수: |B| + |U|
    - U_new = (U − (R.쓴 줄 ∪ R.지운 줄)) ∪ R.uncertain()
    - B_new = ((B − R.지운 줄) ∪ R.쓴 줄) − U_new

- [ ] **Step 1: 실패하는 시험을 쓴다** (`FullSyncUseCaseTest` 의 기존 준비를 쓴다. `writer.stored` 가 가짜 장부다)
  - `결과를_모르는_쓰기는_불확실_줄로_남고_목표에서_빠지면_다음_회차가_지운다`
    - 1회차: kim∈G 쓰기를 `lostResponseFor` 로 → 스냅샷 `uncertain` 에 `dm(kim,G)`, `tuples` 에 없음, 상태 PARTIAL.
    - 2회차: kim 이 G 에서 빠진 디렉터리 → `writer.stored` 에 `dm(kim,G)` 없음, 스냅샷 `uncertain` 비었음.
  - `결과를_모르는_지우기는_불확실_줄로_남고_목표에_다시_들면_다음_회차가_쓴다`
    - 1회차: 기준선에 있는 `dm(lee,G)` 지우기를 `lostResponseFor` → 스냅샷 `tuples` 에 없음, `uncertain` 에 있음.
    - 2회차: lee 가 G 에 다시 듦 → `writer.stored` 에 있음, 스냅샷 `tuples` 에 있음.
  - `차단기로_멈춘_회차도_불확실_줄을_남긴다`: `abortWhen` 과 `lostResponseFor` 를 함께 걸면 FAILED 이고, 스냅샷 `uncertain` 에 그 줄이 든다.
  - `불확실_줄이_남아_있으면_목표가_같아도_변경_없음이_아니다`: 2회차 디렉터리가 1회차와 같아도 그 줄을 다시 보낸다(`appliedDeltas` 마지막에 든다).
  - `TupleReconcilerTest.훑은_뒤_지우기가_불확실하게_실패하면_불확실_줄이고_확정_실패는_기준선에_남는다`
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*FullSyncUseCaseTest*' --tests '*TupleReconcilerTest*'`
- [ ] **Step 3: 구현한다**
  - Interfaces 의 식대로 구현한다.
  - `LedgerAlignment.commit` 과 `ScimRebuildUseCase` 의 스냅샷은 `reconciliation.uncertain()` 을 넘긴다.
  - 설계 2026-09-30:65 가 틀렸다고 적힌 자바독(`FullSyncUseCase` 의 커밋 설명)은 "결과를 모르는 줄은 U 로 남는다"로 고친다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령과 `./gradlew :core:test`, PASS.
- [ ] **Step 5: 커밋** — `fix: LDAP 동기화가 결과를 모르는 쓰기·지우기를 스냅샷의 불확실 줄로 남겨 다음 회차가 목표대로 다시 쓰거나 지운다 — 응답 유실로 권한이 영원히 남거나 빠지던 것을 막는다 (회수 수렴)`

---

### Task 4: N6 — 기준선이 없으면 장부를 훑어 맞춘다(core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (`synchronize`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java` (쓰기 묶음 알림)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LdapSyncAuditTest.java`

**Interfaces:**
- Consumes: Task 2 `baselineState()`, Task 3 `Reconciliation`.
- Produces:
  - `FullSyncUseCase.기준선_없음` 상수. 값은 Global Constraints 의 회차 메모다.
  - `LedgerAlignment.align(DirectorySnapshot, Set<RelationTuple> desired, boolean force, AuditChanges 기록, boolean 쓴_줄도_남긴다)`. 기존 4인자는 `false` 로 위임한다.
  - `TupleReconciler.reconcile(...)` 에 `Consumer<TupleWriteResult> 쓴_묶음마다` 를 받는 오버로드를 더한다. 쓰기 단계가 그것으로 알린다.
- 회차별 동작:
  - `NONE`: 장부 맞추기. 이력 종류는 `SYNC` 그대로이고 쓴 줄과 지운 줄을 모두 남긴다. 결론에 `기준선_없음` 메모를 붙인다.
  - `WRITING`: 지금과 같다. `LEDGER_ALIGNMENT` 이고 지운 줄만 남기며, `기준선_의심` 메모를 붙인다.
  - `PRESENT`: 비교 경로.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `기준선이_없으면_장부를_훑어_목표_밖의_줄을_지운다`: 저장한 스냅샷 없음, `writer.stored` 에 목표 밖 `dm(ghost,G)` → 1회차 뒤 `stored` 에 없음, 스냅샷 `tuples` = 목표, 메시지에 `기준선_없음`.
  - `기준선이_없는_첫_설치는_목표를_모두_쓰고_결과가_지금과_같다`: 빈 장부 → `stored` = 목표, 상태 SUCCEEDED.
  - `기준선이_없어도_삭제_가드는_훑은_장부로_본다`: `stored` 의 대부분이 목표 밖 → ABORTED, 지운 줄 없음.
  - `기준선이_없는_첫_회차가_가드에_통째로_걸리면_다음_회차도_장부를_훑는다`: Review Focus 다. 2회차의 `scanner.scanCount` 가 늘고, 스냅샷이 여전히 없다.
  - `LdapSyncAuditTest.기준선이_없는_첫_회차는_쓴_줄을_부여로_남긴다`: 이력 종류가 SYNC 이고 쓴 튜플이 담긴다.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*FullSyncUseCaseTest*' --tests '*LdapSyncAuditTest*'`
- [ ] **Step 3: 구현한다**
  - Interfaces 대로 구현한다.
  - 첫 회차가 이제 장부를 훑으므로 기존 시험의 기대(첫 회차 `appliedDeltas` 모양 등)가 바뀌면, 바뀐 까닭을 시험 주석 한 줄로 남기고 고친다. 행동이 설계 §4 와 다르면 고치지 말고 보고한다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, PASS.
- [ ] **Step 5: 커밋** — `fix: LDAP 스냅샷 포인터가 없으면(첫 회차·새 테이블) 기준선을 믿지 않고 장부를 훑어 맞춘다 — 새 테이블과 기존 store 짝에서 목표 밖 줄이 영원히 남던 것을 막는다 (회수 수렴)`

---

### Task 5: N3 — 가드가 걸려도 비활성 회수는 먼저(core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/GuardDecision.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncStatus.java` (ABORTED 설명)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java` (`DeleteCheck`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` (`DeleteCheck` 새 모양)
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java`

**Interfaces:**
- Consumes: Task 3 의 식, Task 4 의 `LedgerAlignment`.
- Produces:
  - `TupleMapper.inactiveMemberTuples(DirectorySnapshot)`: 그림에 있는 비활성 직원 u 가 조직 g 의 직원 멤버일 때의 `RelationTuple.directMember(u, g)` 집합(설계 §5.1).
  - `GuardDecision`:
    - 칸 `Set<RelationTuple> revocationsOnly` 를 더한다(기본 빈 집합).
    - `static GuardDecision revokeOnly(Set<RelationTuple>, String message)`. `aborted()` 는 true 다.
    - `boolean partial()`: `revocationsOnly` 가 비어 있지 않음.
  - `DeletionGuard.evaluate(Set<RelationTuple> deletes, Set<RelationTuple> inactiveMemberTuples, int baselineCount, String baselineLabel)` — 설계 §5.2 순서:
    1. 전체가 통과하면 `proceed`.
    2. 아니면 `deletes ∩ inactive` 가 비어 있지 않고 통과하면 `revokeOnly(그 부분, 메시지)`.
    3. 아니면 `abort`.
    - `GuardDecision.message` 는 기존 초과 문구 그대로다. 일부 반영 회차의 결론 메시지는 호출자(`FullSyncUseCase`·`LedgerAlignment`)가 §5.3 문구로 만들고(N=반영한 비활성 회수, M=보류한 나머지 지우기, K=보류한 쓰기 — 장부 맞추기 경로는 K=0) 그 뒤에 `" / "` 와 초과 문구를 잇는다.
  - `SyncOutcome.revokedOnly(TupleWriteResult result, String snapshotId, String message)`: 상태 `ABORTED`, 건수는 result 다.
  - `TupleReconciler.DeleteCheck` 의 반환을 `record DeleteVerdict(Set<RelationTuple> toDelete, String holdReason)` 로 바꾼다.
    - 진행: (stale, null)
    - 전부 보류: (∅, 사유)
    - 일부: (부분, 사유)
  - `Reconciliation`:
    - `heldEntirely()`: 지운 것 없음 + 사유 → 스냅샷을 남기지 않는다(지금의 `held()`).
    - `revokedOnly()`: 일부 지움 + 사유.
    - `ledger` 는 Task 3 의 식대로 보류한 후보를 포함한다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `TupleMapperTest.비활성_직원의_소속_줄만_돌려준다`: 활성 직원, 그림에 없는 직원, 하위 조직 멤버는 빠진다.
  - `DeletionGuardTest` 에 더한다:
    - `가드_안이면_전부_진행한다`
    - `넘으면_비활성_회수만_떼어_다시_본다`: 기준 100, 지울 40 중 비활성 20, 임계 0.3 → `partial()`, `revocationsOnly` = 그 20.
    - `비활성_회수도_넘으면_전부_보류한다`: 비활성 35 → `aborted()` 이고 `partial()` false.
    - `비활성_회수가_없으면_전부_보류한다`
  - `FullSyncUseCaseTest` 에 더한다:
    - `가드가_걸려도_비활성_회수는_반영하고_현재상태는_바꾸지_않는다`
      - 기준선에 많은 줄, 디렉터리에서 대부분이 사라지고 한 명은 비활성 → 그 사람의 줄만 지워진다.
      - `state.replaceWith` 가 불리지 않는다. 스냅샷이 저장되고 "기록 중" 이 꺼진다.
      - 상태 ABORTED, 메시지에 "비활성 회수 1건만 반영".
    - `가드가_걸리면_부여는_보류한다`: 같은 회차에 새 입사자의 쓰기가 `stored` 에 없다.
    - `비활성_회수만_반영한_다음_회차는_나머지로_다시_가드를_본다`: 2회차에 같은 디렉터리 → 다시 ABORTED, 지운 줄 0.
    - `불확실_줄이_있는_채로_비활성_회수만_반영해도_식이_맞다`: Review Focus 다. 1회차에 불확실 줄을 만들고, 2회차에 가드 일부 반영 → B∩U=∅. U 의 줄이 사라지지 않거나 반영 결과로 풀린다.
  - `TupleReconcilerTest.장부_맞추기에서도_비활성_회수만_지우고_보류한_후보는_기준선에_남긴다`
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*TupleMapperTest*' --tests '*DeletionGuardTest*' --tests '*FullSyncUseCaseTest*' --tests '*TupleReconcilerTest*'`
- [ ] **Step 3: 구현한다**
  - 비교 경로의 일부 반영: 표시 → `writer.apply(TupleDelta.deleteOnly(부분), 기록::tuples)` → Task 3 식으로 스냅샷 저장 → **현재상태 교체 없음** → `SyncOutcome.revokedOnly`.
  - 장부 맞추기 경로는 `DeleteVerdict` 를 따른다. 일부면 스냅샷을 저장하고 현재상태는 바꾸지 않는다.
  - FORCED·`force` 는 지금처럼 가드를 건너뛴다.
  - `ScimRebuildUseCase` 의 빈 조직도 규칙은 진행/전부 보류만 쓴다.
  - `SyncStatus.ABORTED` 설명은 설계 §5.3 대로 고친다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, PASS.
- [ ] **Step 5: 커밋** — `fix: 삭제 가드가 걸려도 디렉터리가 막힘으로 명시한 회수는 따로 가드를 다시 봐 먼저 반영하고, 부여·나머지 지우기·현재상태 교체는 보류한다 — 큰 개편 하나가 퇴사자 회수를 매일 멈추던 것을 막는다 (회수 수렴)`

---

### Task 6: N2 — SCIM 은 요청한 활성 여부를 저장한다(core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
  - `upsertUserInternal`, `reconcileUser`, `removeUserInternal`
  - 자바독 :198-231, :500-507
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncRevocationIntentTest.java` (새로)

**Interfaces:**
- Consumes: 기존 가짜들(`FakeTupleChecker`·`FakeTupleWriter`·`FakeStateRepository`·`FakeMutationLock`). 시험 준비는 같은 패키지의 다른 `IncrementalSync*Test` 를 본뜬다.
- Produces: 설계 §6.1·§6.2 의 규칙. 공개 시그니처는 바뀌지 않는다.
  - 결과에 실패가 있으면 요청값 `user` 를 저장한다(`reconcileUser` 는 없애거나 늘 요청값을 돌려준다).
  - `diffAndApply` 단계가 오류로 끝났을 때(Check 오류, 쓰기 오류, `TupleWriteAbortedException`):
    - 오류가 `LockUnavailableException` 이 아니고 저장본이 있으면, 리스를 다시 확인한 뒤 요청값을 저장하고 이력에 남긴 다음 **원래 오류를 다시 던진다**.
    - 저장이나 리스 확인이 실패하면 로그만 남기고 원래 오류를 던진다.
  - 직원 삭제:
    - 결과에 실패가 있으면 레코드와 지우지 못한 소속은 남기고 `user.withActive(false)` 를 저장한다.
    - 오류 경로도 위와 같이 비활성으로 저장한다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `비활성화가_일부_실패해도_비활성으로_저장하고_부분_반영을_알린다`: 소속 둘 중 하나의 지우기를 `failFor` → 저장본 `active` false, 결과 `fullyApplied()` false.
  - `비활성화의_Check가_실패해도_비활성으로_저장하고_오류를_낸다`: 체커가 오류 → 오류가 나가고 저장본 `active` false.
  - `비활성화의_쓰기가_차단기로_멈춰도_비활성으로_저장한다`: `abortWhen`.
  - `리스를_잃으면_비활성화를_저장하지_않는다`: 커밋 직전 리스 확인 실패를 흉내 → 저장본 `active` true.
  - `비활성화가_실패한_뒤_다음_쓰기가_남은_줄을_지운다`: 실패 뒤 `active` 를 싣지 않은 이름 변경 → 남은 `dm` 가 `stored` 에서 빠진다.
  - `직원_삭제가_일부_실패하면_비활성_레코드로_남고_재시도가_끝낸다`
  - `처음_만드는_직원이_OpenFGA_단계에서_오류로_끝나면_레코드를_만들지_않는다`: Review Focus 다.
  - `의도를_저장하다_실패하면_원래_오류가_나간다`: Review Focus 다. 체커 오류 + `state.saveUser` 실패 → 체커의 오류가 나간다.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*IncrementalSyncRevocationIntentTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. 오류를 잡는 범위는 `diffAndApply` 가 만든 Mono 뿐이다. 그 앞(한도·표지판·계산 4xx)은 잡지 않는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, PASS. 기존 시험 가운데 "실패하면 `active` 를 되돌린다"를 고정하던 것은 새 규칙으로 고치고, 시험 주석에 설계 §6.1 을 단다.
- [ ] **Step 5: 커밋** — `fix: SCIM 직원의 active 는 OpenFGA 단계가 실패·오류로 끝나도 요청값을 저장하고, 직원 삭제가 실패하면 비활성으로 남긴다 — 회수 의도가 IdP 재시도에만 달려 있던 것을 없앤다 (회수 수렴)`

---

### Task 7: N2 — 하루 아카이빙이 어긋난 직원을 다시 맞춘다(core·app-scim)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (새 입구)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/ArchiveDriftObserver.java`
- Modify: app-scim 의 아카이빙 배선(`SnapshotArchiveUseCase` 를 만드는 설정 클래스)과 `ScimSyncMetrics`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java`

**Interfaces:**
- Consumes: Task 6 의 저장 규칙.
- Produces:
  - `public Mono<IncrementalSyncResult> IncrementalSyncUseCase.reapplyUser(String userId)`
    - 락 안에서 저장본을 읽어 `upsertUserInternal(저장본, Optional.of(저장본), …)` 로 다시 반영한다.
    - 상태는 바꾸지 않는다.
    - 직원이 없으면 빈 Mono 다.
  - `interface ArchiveDriftObserver { void observed(long extra, long missing); ArchiveDriftObserver NOOP }`
  - `SnapshotArchiveUseCase` 생성자에 `ArchiveDriftObserver driftObserver` 와 `Function<String, Mono<IncrementalSyncResult>> 직원을_다시_맞춘다` 를 더한다.
  - 아카이빙 순서:
    1. 스냅샷(관찰한 실제)을 저장한다.
    2. 어긋남 수를 관찰자에 알린다.
    3. 어긋난 `direct_member` 줄의 직원 아이디마다 차례로(concatMap) 다시 맞춘다.
    4. 결론 메시지에 `"어긋난 직원 %d명 다시 맞춤(실패 %d명)"` 을 남긴다.
  - 다시 맞추기의 결과 분류:
    - 빈 결과(그새 지워짐) → 건너뜀이고 실패가 아니다.
    - 부분 반영·오류 → 실패로 센다.
  - `child` 줄의 어긋남은 수에만 들고 다시 맞추지 않는다.
  - app-scim: 게이지 `scim.archive.drift{kind=extra}`·`{kind=missing}` 은 마지막 값을 담는다(AtomicLong).

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `SnapshotArchiveUseCaseTest` 에 더한다:
    - `어긋난_직원을_다시_맞춰_남은_줄을_지운다`: 상태가 비활성 kim(소속 G), `stored` 에 `dm(kim,G)` → 아카이빙 뒤 없음.
    - `빠진_줄도_다시_쓴다`
    - `어긋남_수를_관찰자에_알린다`
    - `조직_연결_줄의_어긋남은_다시_맞추지_않는다`
    - `다시_맞추기_전에_지워진_직원은_건너뛰고_실패로_세지_않는다`: Review Focus 다.
    - `다시_맞추기가_실패한_직원을_센다`
  - `ScimSyncMetricsTest.아카이빙_어긋남을_게이지로_낸다`
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*SnapshotArchiveUseCaseTest*'`, `./gradlew :app-scim:test --tests '*ScimSyncMetricsTest*'`
- [ ] **Step 3: 구현한다** — 배선은 app-scim 설정에서 `incrementalSyncUseCase::reapplyUser` 를 넘긴다. 핵심 클래스 자바독에 설계 2026-10-20 §6.4 와 2026-09-01 §1.4 와의 관계를 한 문단으로 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test` 다음에 `./gradlew :app-scim:test --tests '*ScimSyncMetricsTest*'`, PASS. 아카이빙 배선을 바꿨으므로 app-scim 의 기동 시험 하나(`--tests '*ScimKeyLimitEndToEndTest*'`)로 컨텍스트가 뜨는지도 본다.
- [ ] **Step 5: 커밋** — `feat: SCIM 하루 아카이빙이 어긋난 직원을 락 안에서 저장된 그대로 다시 반영해 남은 회수를 끝내고, 어긋남 수를 scim.archive.drift 게이지로 낸다 (회수 수렴)`

---

### Task 8: N7 — 색인 정렬키를 잘라 쓴다(storage)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (GSI1SK·GSI2SK 를 쓰는 세 자리: 직원 META 의 GSI1SK·GSI2SK, 조직 META 의 GSI1SK)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/LongIndexValueTest.java` (새로, DynamoDB Local)

**Interfaces:**
- Produces: `static String Keys.indexKeyForWrite(String raw)`
  - `indexKey(raw)` 를 UTF-8 `MAX_SORT_KEY_BYTES` 이하로, 코드 포인트(서로게이트 쌍 포함)를 가르지 않고 자른다.
  - 읽기는 계속 `indexKey` 를 쓴다(한도를 넘는 조회 키는 "없음", 설계 2026-10-17 §4).

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `KeysTest` 에 더한다:
    - `쓰기_색인키는_1024바이트를_넘지_않는다`: `"가".repeat(400)` → 바이트 ≤1024 이고 원래 값의 앞부분이다.
    - `쓰기_색인키는_코드포인트를_가르지_않는다`: 4바이트 문자(예: `"😀".repeat(300)`) → 끝이 온전한 문자.
    - `한도_안의_값은_자르지_않는다`
    - `소문자로_늘어나는_글자도_한도_안으로_자른다`: `"İ".repeat(400)`.
  - `LongIndexValueTest` 에 더한다:
    - `긴_조직_표시명도_현재상태_교체가_성공하고_목록에_나온다`: `replaceWith` 로 1,200바이트 표시명 조직 → 성공, 조직 META 의 GSI1SK ≤1024바이트, 조직 목록(이름표 열거)에 든다.
    - `긴_직원_표시명도_저장된다`: GSI2SK.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :storage-dynamodb:test --tests '*KeysTest*' --tests '*LongIndexValueTest*'`
- [ ] **Step 3: 구현한다** — 세 쓰기 자리를 `indexKeyForWrite` 로 바꾼다. `externalId`(GSI3 파티션키가 속성 자체)는 건드리지 않는다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령, PASS.
- [ ] **Step 5: 커밋** — `fix: 색인 정렬키(GSI1SK·GSI2SK)는 쓸 때 1024바이트로 잘라 넣는다 — LDAP 의 긴 description 때문에 매 회차 상태 교체가 실패하던 것을 막는다 (회수 수렴)`

---

### Task 9: N8 — LDAP 재시도 창·기본 주기·신선도 게이지

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapProperties.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapDirectorySnapshotSource.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncProperties.java`
- Modify: `app-ldap/src/main/resources/application.yml`
- Create: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/LastSuccessGauge.java`
- Modify: 재시도를 타는 시험의 설정. 찾는 법: `grep -rln "LdapProperties\|ldap\." connector-ldap/src/test app-ldap/src/test` 와 `connector-ldap/src/testFixtures`. 예: `EmbeddedLdapSupport`, `LdapTimeoutTest`, `LdapReadTimeoutDuringPagingTest`, `WrongSearchBaseTest`, `LdapDirectorySnapshotSourceTest`, app-ldap 의 test 설정 yml.
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapDirectorySnapshotSourceTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LastSuccessGaugeTest.java`

**Interfaces:**
- Produces:
  - `LdapProperties`: `maxRetries = 5`, `Duration retryMinBackoff = 10s`, `Duration retryMaxBackoff = 2m`.
  - `static RetryBackoffSpec LdapDirectorySnapshotSource.재시도_규칙(LdapProperties)`: `Retry.backoff(maxRetries, retryMinBackoff).maxBackoff(retryMaxBackoff)` 에 기존 filter·로그·`onRetryExhaustedThrow` 를 더한 것이다. `fetchAll` 이 쓴다.
  - `SyncProperties.cron` 기본 `"0 0 * * * *"`. `application.yml` 의 `sync.cron` 도 같다.
  - `LastSuccessGauge`:
    - 게이지 `sync.last.success`(태그 `source=LDAP`)를 단다.
    - 1분마다(`@Scheduled(fixedDelay = 60_000)` 또는 같은 뜻) `SyncRunRepository.findRecent(Integer.MAX_VALUE)` 에서 상태가 SUCCEEDED·PARTIAL 인 첫 기록의 `finishedAt` epoch 초로 값을 바꾼다.
    - 그런 기록이 없거나 읽기가 실패하면 값을 NaN 으로 둔다. 읽기 실패는 WARN 한 줄을 남긴다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `LdapDirectorySnapshotSourceTest.재시도_규칙은_설정한_횟수와_최소_최대_대기를_쓴다`: `RetryBackoffSpec` 의 `maxAttempts`·`minBackoff`·`maxBackoff` 공개 필드를 단언한다. 기본값 5·10s·2m 과 바꾼 값 둘 다.
  - `LastSuccessGaugeTest` 에 더한다(`FakeSyncRunRepository`):
    - `가장_최근의_성공_또는_부분_회차_시각을_낸다`: 최신 FAILED, 그다음 PARTIAL, 그다음 SUCCEEDED → PARTIAL 의 시각.
    - `성공한_회차가_없으면_값이_없다`: NaN.
    - `가드로_멈춘_회차는_세지_않는다`: ABORTED 만 → NaN.
  - `SyncPropertiesTest.기본_주기는_매시_정각이다` (새로 만든다: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncPropertiesTest.java`)
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :connector-ldap:test --tests '*LdapDirectorySnapshotSourceTest*'`, `./gradlew :app-ldap:test --tests '*LastSuccessGaugeTest*' --tests '*SyncPropertiesTest*'`
- [ ] **Step 3: 구현한다**
  - Interfaces 대로 구현한다.
  - **재시도를 타는 기존 시험은 `retry-min-backoff` 10ms·`retry-max-backoff` 50ms 로 줄인다**(Review Focus). 프로퍼티를 직접 만드는 시험은 setter 로, Spring 시험은 test 설정 yml 로 바꾼다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :connector-ldap:test` 다음에 `./gradlew :app-ldap:test`. PASS 이고 두 모듈 모두 시간이 이 과제 전보다 눈에 띄게 늘지 않아야 한다. 보고에 전후 시간을 적는다.
- [ ] **Step 5: 커밋** — `feat: LDAP 읽기 재시도 창을 설정(기본 5번·10초~2분)으로 늘려 DC 재부팅을 버티고, 기본 동기화를 매시 정각으로, 마지막 성공 시각을 sync.last.success 게이지로 낸다 (회수 수렴)`

---

### Task 10: 끝단 시험과 문서

**Files:**
- Create: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapRevocationConvergenceEndToEndTest.java`
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRevocationConvergenceEndToEndTest.java`
- Modify: `README.md`
- Modify (옛 설계 표시, 주변 글은 두고 `**→ 바로잡음(2026-10-20 설계 §N)**` 이나 `**→ 해결(2026-10-20 설계)**` 만 덧붙인다):
  - `docs/superpowers/specs/2026-09-30-cluster-jobs-baseline-design.md` (:65 "기준선이 정확하다")
  - `docs/superpowers/specs/2026-09-29-integrity-quick-fixes-design.md` (§3.4 "포인터가 없으면 빈 결과")
  - `docs/superpowers/specs/2026-08-14-organization-sync-design.md` (§7.2 반영된 만큼만 저장 — 활성 여부)
  - `docs/superpowers/specs/2026-09-01-scim-concurrency-design.md` (§1.1 표의 첫 행, §1.4 주기 리컨실러 행)
  - `docs/superpowers/specs/2026-09-25-ldap-disabled-account-design.md` (§4 :86 가드)
  - `docs/superpowers/specs/2026-10-17-scim-standard-rest-design.md` (§7 LDAP 긴 값 첫 불릿)
  - `docs/superpowers/specs/2026-10-19-outsider-review/0-종합.md` (§2 표의 N1·N2·N3·N6·N7·N8 행)

**Interfaces:**
- Consumes: Task 1~9 전부. 끝단 준비는 기존 `ScimKeyLimitEndToEndTest`(app-scim)와 app-ldap 의 기존 끝단 시험(Testcontainers OpenFGA·DynamoDB Local·임베디드 LDAP)을 본뜬다. OpenFGA 에 직접 줄을 쓰고 읽는 시험 도우미가 이미 있으면 그것을 쓴다(`grep -rn "client().write\|OpenFgaClient" */src/test`).

- [ ] **Step 1: 끝단 시험을 쓴다**
  - `LdapRevocationConvergenceEndToEndTest`:
    - `빈_테이블과_기존_store_의_첫_동기화가_목표_밖의_줄을_지운다`: 먼저 OpenFGA 에 목표 밖 `dm(ghost,G)` 를 쓴다 → `POST /admin/sync/full` → 회차 SUCCEEDED, 메시지에 기준선 없음 메모, 그 줄 Check false.
    - `긴_description_조직도_동기화가_두_번_모두_성공한다`: 임베디드 LDAP 에 1,200바이트 description 그룹을 더한 뒤 동기화 두 번 → 둘 다 SUCCEEDED, 관리 API 조직 상세 200.
  - `ScimRevocationConvergenceEndToEndTest`:
    - `비활성_직원에게_남은_줄을_아카이빙이_지운다`: 직원을 SCIM 으로 만들고 조직에 넣은 뒤 `active=false` PATCH. 그다음 OpenFGA 에 그 직원의 `dm` 을 직접 다시 써서 남은 줄을 흉내 낸다 → 아카이빙 실행(빈 호출) → Check false, 게이지 `scim.archive.drift{kind=extra}` 가 1.
- [ ] **Step 2: 돌려 통과를 본다** — `./gradlew :app-ldap:test --tests '*LdapRevocationConvergenceEndToEndTest*'`, 이어서 `./gradlew :app-scim:test --tests '*ScimRevocationConvergenceEndToEndTest*'`. 동작이 설계와 다르면 단언을 맞추지 말고 실제 응답을 보고한다.
- [ ] **Step 3: README 를 고친다**
  - 회수 지연과 목표: "다음 정각 + 회차 시간". 재시도 창 설정 셋, `sync.last.success` 와 경보 예("지금 − 값 > 2시간").
  - 삭제 가드의 비활성 회수 먼저(§5): 일부 반영 동안 현재상태가 그대로라는 것.
  - 기준선 없음 = 장부 훑기(§4): 테이블을 다시 만들라는 지시들 옆에 한 줄.
  - 불확실 줄(§3)
  - SCIM 활성 여부 저장과 아카이빙 복구(§6), `scim.archive.drift` 경보.
  - LDAP 긴 값(§7)
- [ ] **Step 4: 옛 설계에 표시를 단다** — Files 목록대로.
- [ ] **Step 5: 컴파일 확인** — `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
- [ ] **Step 6: 커밋** — `docs: 회수 수렴을 README 와 옛 설계에 적고, 끝단 시험이 기준선 없는 첫 동기화의 장부 맞추기·긴 description·SCIM 아카이빙 복구를 본다 (회수 수렴)`

---

## 머지 전(컨트롤러)
- `./gradlew cleanTest test` 를 돌린 다음 `./gradlew cleanScaleTest scaleTest` 를 돌린다. 한 번에 하나다.
- 설계 §13 결과를 채운다.
