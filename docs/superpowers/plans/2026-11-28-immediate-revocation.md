# 한 사람 즉시 회수 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /admin/sync/revocations {"employeeId"}` 가 LDAP 에서 그 사람만 다시 읽어, 막혔거나 없으면 그 사람의 장부 줄을 바로 지운다. 지우기 전후로 스냅샷 포인터에 남긴 표시로 다음 정각 회차의 기준선이 그것을 안다.

**Architecture:** core 에 한 사람 계정 상태 포트(`DirectoryAccountSource`)와 유스케이스(`ImmediateRevocationUseCase`)를 둔다. 유스케이스는 락 밖에서 현재상태·LDAP 을 본 뒤 `SyncJobs` 로 작업을 띄운다. 작업은 현재상태 소속 → Check 로 거둘 줄을 고르고, PRESENT 면 포인터의 `revokedUnknown` 에 먼저 적고 지운 뒤 정리한다. `FullSyncUseCase` 는 표시를 반영한 B′·U′ 로 비교한다. connector-ldap 은 두 전략에 한 사람 읽기를, 어댑터에 주소 넘김과 상한을 더한다. storage-dynamodb 는 포인터 줄의 String Set 두 칸을 다룬다. app-ldap 은 끝점·결선·게이지를 맡는다.

**Tech Stack:** Java 17, Spring WebFlux·Reactor, Spring LDAP(JNDI)·UnboundID 임베디드 LDAP, DynamoDB SDK v2(DynamoDB Local·Testcontainers), OpenFGA(Testcontainers), JUnit5·AssertJ·Mockito.

**Spec:** `docs/superpowers/specs/2026-11-28-immediate-revocation-design.md`

## Global Constraints

- 끝점은 `POST /admin/sync/revocations`, 본문 `{"employeeId": "<직원 id>"}`, 범위 `admin.sync`(이미 `POST /admin/sync/**` 에 걸려 있다), app-ldap 만. 받으면 202 와 RUNNING 실행 기록이다.
- 락 밖 검사 순서: 400(`employeeId` 없음·빈칸) → 404(현재상태에 없음) → LDAP 읽기 → 409(활성) → 503·500(읽기 실패) → 작업 띄우기. 락을 못 잡으면 409 `다른 동기화·재적재가 진행 중입니다`.
- 정한 문구(그대로 쓴다):
  - 활성 409: `디렉터리에서 활성인 직원은 거두지 않습니다 — 디렉터리에서 먼저 막으세요`
  - 거둘 줄 없음: `즉시 회수 — 장부에 거둘 줄이 없습니다`
  - 앞머리: `즉시 회수 — 디렉터리에서 막힘`, `즉시 회수 — 디렉터리에 없음`
  - 정리 실패 꼬리: ` — 즉시 회수 표시를 정리하지 못했습니다. 다음 동기화가 그 줄을 다시 지웁니다`
  - 한도: `즉시 회수 표시가 한도(256KB)를 넘습니다 — 다음 동기화가 기준선을 새로 남긴 뒤 다시 거세요. 여럿이면 디렉터리에서 막은 뒤 POST /admin/sync/full 을 쓰세요`
  - 변경 없음 저장: `변경 없음 — 즉시 회수를 기준선에 반영해 스냅샷을 새로 남김`
  - 부분: `SyncOutcome.partial` 의 문구(`N건의 튜플 적용에 실패했습니다. 다음 동기화가 다시 시도합니다`)를 그대로 쓴다.
- 포인터 줄(PK `SNAPSHOT_POINTER`, SK `LATEST`)의 String Set 두 칸 `revokedGone`·`revokedUnknown`. 원소는 `Keys.tupleSk(tuple)`. 한도는 두 칸 원소의 UTF-8 바이트 합 **262,144**(256KB)이다. 같으면 허용하고 넘으면 거절한다.
- 다음 회차(PRESENT): `B′ = B − gone − unknown`, `U′ = (U − gone) ∪ unknown`. 델타·가드 기준 수(|B′|+|U′|)·새 스냅샷 식이 모두 B′·U′ 다. 계산은 core 가 한다.
- LDAP 한 사람 읽기: 템플릿(`시작_주소별_템플릿`)을 차례로 한 번씩 쓴다. 한 주소의 상한은 10초, 요청 전체 상한은 30초다. 둘 다 코드 상수이고 대기(backoff)는 없다. 503 은 Spring LDAP `CommunicationException`·`ServiceUnavailableException`·주소 상한 초과·전체 상한 초과뿐이고, 그 밖은 모르는 종류까지 500 이다.
- 새 지표·새 설정은 없다. `sync.last.success` 는 `REVOCATION` 회차를 세지 않는다.
- 로그·실행 기록 메시지에 직원 id·DN 을 싣지 않는다. 새 WARN 에 직원에 관한 것은 수만 싣는다(LDAP 주소 같은 설정 값은 실어도 된다).
- 시험 메서드 이름·`@DisplayName` 은 한국어로 쓰고, 몸통은 `// given` / `// when` / `// then` 으로 나눈다. 새 시험 위에는 `// 깨뜨릴 변경: …` 한 줄을 단다. 벽시계 시각은 단언하지 않는다(고정 시계만 쓴다).
- Gradle 은 한 번에 하나만 돌린다. 과제 시험은 모듈·클래스 단위로만 돌린다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- `git stash` 를 쓰지 않는다. git 명령은 `&&`·`$(...)` 없이 하나씩 쓴다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 메시지 꼴은 `feat: … (즉시 회수)`.
- 온보딩은 고친 문서만 머리를 `기준 커밋: <해시7> (immediate-revocation, main 에 머지됨)` 으로 올린다. 해시는 마지막 제품 코드 커밋이다.

## 계획의 판단 (스펙 §10 이 빌더에게 맡긴 것)

| # | 판단 | 이유 |
|---|---|---|
| P1 | 400 은 컨트롤러가, 404·409(활성)·LDAP 읽기는 유스케이스가 본다 | 판정 순서를 core 시험(가짜)이 본다. 본문 꼴은 HTTP 의 일이다 |
| P2 | 포트 메서드: `baselinePointer()`·`findLatestMarked()`·`markRevoking(snapshotId, tuples)`·`settleRevocation(snapshotId, gone, dropped)`. `baselineState()`·`findLatest()` 는 이것들 위의 default 다 | 기존 호출처가 그대로다. 표시는 포인터를 한 번 읽어 함께 준다(V14) |
| P3 | 조건(스냅샷 번호·기록 중) 실패는 `BaselineMovedException`. 표시 적기 실패는 `즉시 회수 표시를 남기지 못해 거두지 않았습니다 — <원인 문구>` 로 감싸 FAILED 로 둔다. 한도 예외(`RevocationMarksFullException`)는 감싸지 않는다 | 스펙은 조건 실패의 문구를 정하지 않았다. 한도 문구는 V9 그대로 기록에 남아야 한다 |
| P4 | 응답 문구: 400 `employeeId 가 필요합니다`, 404 `현재상태에 그 직원이 없습니다 — 직원 id 를 확인하세요`, 503 `디렉터리를 읽지 못했습니다(일시 장애) — 잠시 뒤 다시 시도하세요` + `Retry-After: 10`, 500 `디렉터리에서 직원을 읽지 못했습니다 — 서버 로그를 확인하세요` | 스펙은 사유를 `detail` 에 싣는다고만 했다. 직원 id 는 싣지 않는다 |
| P5 | 거둘 줄이 없으면 문구는 V5 그대로다(앞머리 없음) | V5 가 문구를 정했다 |
| P6 | 가짜 저장소는 한도를 셈하지 않고 실패 갈고리로 흉내 낸다. 진짜 한도는 DynamoDB 시험이 본다 | 원소 문자열 꼴(`Keys.tupleSk`)은 저장소의 일이다 |
| P7 | 주소 상한은 시도마다 Reactor `timeout(10초)`, 전체 상한은 바깥 `timeout(30초)` 로 둔다. 시험용으로 두 값을 받는 패키지 전용 생성자를 둔다 | "min(남은 상한, 10초)" 와 같다 — 바깥이 남은 시간에서 끊는다 |
| P8 | 사용자 필터가 괄호로 시작하지 않으면 괄호로 감싼 뒤 `(&<필터>(<속성>=<값>))` 로 잇는다 | 정기 읽기는 필터를 그대로 넘기고 JNDI 가 바깥 괄호 없는 필터를 받아 준다. AND 로 이으면 괄호가 있어야 한다 |

## Review Focus

- **이미 거둔 사람을 다시 부름.** 장부에 줄이 없으니 쓰기도 표시도 없이 `즉시 회수 — 장부에 거둘 줄이 없습니다` 다. 앞 회수의 표시는 그대로 남아야 한다 — 과제 4 시험.
- **필터 특수 문자가 든 id**(`*`, `(`, `)`, `\`). 값은 RFC 4515 로 이스케이프한다. `*` 가 모든 엔트리를 고르면 안 된다 — 과제 5 시험.
- **다바이트 줄의 한도.** 한글 조직 코드는 글자당 3바이트다. 한도는 글자 수가 아니라 UTF-8 바이트로 센다 — 과제 2 시험.
- **소속이 하나도 없는 직원.** 후보가 비면 Check 도 부르지 않고 거둘 줄 없음으로 끝난다 — 과제 4 시험.
- **본문이 없거나 `{}`, `employeeId` 가 공백.** 셋 다 400 이고 유스케이스를 부르지 않는다 — 과제 7 시험.

---

### Task 1: core — 즉시 회수 표시의 모델·포트·가짜 저장소

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/RevocationMarks.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/RevocationMarksFullException.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/BaselineMovedException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`. 컴파일만 맞춘다 — `markRevoking`·`settleRevocation` 은 `UnsupportedOperationException("과제 2")` 를 던진다. `findLatest` 재정의는 `findLatestMarked` 로 옮기고 표시는 `RevocationMarks.NONE` 으로 둔다. `baselineState` 재정의는 `baselinePointer` 로 옮기고, 상태에 포인터의 `snapshotId` 를 함께 싣는다.
- Test: `core/src/test/java/dev/starryeye/organization/core/fake/FakeSnapshotRepositoryTest.java`

**Interfaces:**
- Produces:
  - `record RevocationMarks(Set<RelationTuple> gone, Set<RelationTuple> unknown)`
    - null 은 빈 집합으로 받고 `Set.copyOf` 로 굳힌다.
    - `static final RevocationMarks NONE`, `boolean isEmpty()`.
    - `static final int MAX_BYTES = 262_144`.
  - `RevocationMarksFullException extends RuntimeException`. 인자 없는 생성자이고, 메시지는 Global Constraints 의 한도 문구다.
  - `BaselineMovedException extends RuntimeException(String message)`.
  - `TupleSnapshotRepository` 에 더하는 것:
    - `record BaselinePointer(BaselineState state, String snapshotId)` — snapshotId 는 포인터의 번호이고, 없으면 null.
    - `record MarkedSnapshot(TupleSnapshot snapshot, RevocationMarks marks)`.
    - `Mono<BaselinePointer> baselinePointer()` — 포인터 줄만 강한 일관성으로 읽는다. 본문은 읽지 않는다.
    - `default Mono<BaselineState> baselineState()` = `baselinePointer().map(BaselinePointer::state)`.
    - `Mono<MarkedSnapshot> findLatestMarked()` — 계약은 지금의 `findLatest` 와 같다. 표시는 같은 포인터 읽기에서 온다.
    - `default Mono<TupleSnapshot> findLatest()` = `findLatestMarked().map(MarkedSnapshot::snapshot)`.
    - `Mono<Void> markRevoking(String snapshotId, Set<RelationTuple> tuples)` — `revokedUnknown` 에 더한다.
      - 조건: 포인터 번호 == snapshotId 이고 기록 중 칸이 없다. 아니면 `BaselineMovedException`.
      - 더한 뒤 한도를 넘으면 `RevocationMarksFullException` 이고 아무것도 쓰지 않는다.
      - 빈 집합이면 아무것도 하지 않는다.
    - `Mono<Void> settleRevocation(String snapshotId, Set<RelationTuple> gone, Set<RelationTuple> dropped)`.
      - `gone` 은 unknown 에서 빼서 gone 칸에 더한다. `dropped` 는 unknown 에서만 뺀다.
      - 조건은 `markRevoking` 과 같다. 둘 다 비면 아무것도 하지 않는다.
  - `FakeSnapshotRepository`:
    - `RevocationMarks marks()`.
    - `void failMarkRevoking(RuntimeException)`, `void failSettleRevocation(RuntimeException)`.
    - `save` 가 표시를 비운다. `markWriting` 은 남긴다. `failFindLatest` 는 `findLatestMarked` 에 걸린다.

- [ ] **Step 1: Write the failing test** — `FakeSnapshotRepositoryTest` 에 시험 넷을 더한다.
  - `표시를_적고_정리하면_지운_줄은_gone_으로_옮기고_버린_줄은_빠진다`
    - `markRevoking(id, {a,b,c})` 뒤 `settleRevocation(id, {a}, {b})`.
    - `marks()` 는 `gone == {a}`, `unknown == {c}`.
    - `findLatestMarked().block().marks()` 도 같다.
  - `스냅샷_번호가_다르거나_기록_중이면_BaselineMovedException`
    - 두 경우 모두 `isInstanceOf(BaselineMovedException.class)` 이고 `marks().isEmpty()` 다.
  - `save_는_표시를_지우고_markWriting_은_남긴다`.
  - `baselinePointer_는_상태와_포인터_번호를_준다`
    - 없음은 `(NONE, null)`, 저장 뒤는 `(PRESENT, id)`, 기록 중은 `(WRITING, id)`.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:test --tests '*FakeSnapshotRepositoryTest'`
  Expected: 컴파일 실패(`markRevoking` 없음).
- [ ] **Step 3: Implement** — 위 Interfaces 대로 만든다. Javadoc 에 설계 번호(V6·V8·V9·V14)를 단다. 저장소의 컴파일 맞춤도 이 단계다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :core:test --tests '*FakeSnapshotRepositoryTest'`
  이어서: `./gradlew :storage-dynamodb:compileTestJava`
  Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit**
  `git add` 로 위 파일들을 올린다. 메시지: `feat: 즉시 회수 표시의 모델과 저장소 포트, 가짜 저장소 — 포인터 번호 조건·정리 규칙 (즉시 회수)`.

### Task 2: storage-dynamodb — 포인터 줄의 두 칸

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java` — 포인터 읽기 `포인터()`·`writePointer`·`markWriting` 곁, ~:178-235.
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbRevocationMarksTest.java` (`extends DynamoDbTestSupport`; 저장소 준비는 `DynamoDbTupleSnapshotRepositoryTest` 를 따른다)

**Interfaces:**
- Consumes: 과제 1 의 포트·예외·`RevocationMarks.MAX_BYTES`.
- Produces: 실제 저장소의 다섯 메서드(`baselinePointer`·`findLatestMarked`·`markRevoking`·`settleRevocation` 과 지우는 `save`).

- [ ] **Step 1: Write the failing test** — `DynamoDbRevocationMarksTest`:
  - `표시를_더하면_findLatestMarked_가_스냅샷과_함께_준다`.
  - `정리하면_지운_줄은_gone_칸으로_옮기고_버린_줄은_빠진다`
    - 남은 unknown 원소는 그대로다.
    - 마지막 원소를 빼면 칸이 사라진다 — 포인터 아이템을 직접 읽어 `revokedUnknown` 키가 없다.
  - `스냅샷_번호가_다르면_BaselineMovedException_이고_칸은_그대로다`.
  - `기록_중이면_BaselineMovedException`.
  - `한도와_같으면_허용하고_넘으면_RevocationMarksFullException_이고_아무것도_더하지_않는다`
    - 원소 바이트를 `Keys.tupleSk(t).getBytes(UTF_8).length` 로 셈해, 합이 정확히 262,144 인 집합과 그보다 1바이트 큰 집합을 만든다.
  - `한도는_UTF_8_바이트로_센다`
    - 한글 조직 코드 줄로 글자 수는 한도 아래, 바이트는 한도 위인 집합을 만든다 → `RevocationMarksFullException`.
  - `save_가_표시를_지운다`, `markWriting_은_표시를_남긴다`.
  - `풀_수_없는_원소는_SnapshotIntegrityException`
    - 포인터 아이템의 `revokedGone` 에 `"garbage"` 를 직접 넣는다.
  - `빈_집합은_아무것도_쓰지_않는다`
    - `markRevoking(id, Set.of())` 와 `settleRevocation(id, Set.of(), Set.of())` 뒤 포인터 아이템이 그대로다.
    - 번호가 틀려도 예외가 없다.
  - `baselinePointer_는_본문을_읽지_않는다` — 기존 `GetCounter` 로 GetItem 1회·Query 0회를 본다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbRevocationMarksTest'`
  Expected: FAIL(`UnsupportedOperationException("과제 2")`).
- [ ] **Step 3: Implement**
  - 속성 이름 상수 `REVOKED_GONE = "revokedGone"`, `REVOKED_UNKNOWN = "revokedUnknown"`.
  - `findLatestMarked`: `포인터()` 한 번 → 번호로 `findById` → 같은 아이템에서 두 칸을 푼다.
    - 풀이는 `Keys.isTupleSk` 와 `Keys.parseTupleSk` 다. 실패(런타임 예외 포함)는 `SnapshotIntegrityException("스냅샷 포인터의 즉시 회수 표시를 풀 수 없습니다 — POST /admin/sync/rebuild 로 복구하세요")` 이다.
    - 가져오기 실패는 감싸지 않는다(E1·E3 과 같다).
  - `markRevoking`: 포인터를 강한 일관성으로 읽어 지금 두 칸으로 바이트 합(gone + (unknown ∪ 새 줄))을 셈한다.
    - 넘으면 예외, 아니면 UpdateItem `ADD #unknown :s`.
    - 조건식은 `#snapshotId = :id AND attribute_not_exists(#writingSince)` 다. `ConditionalCheckFailedException` 이면 `BaselineMovedException("포인터의 기준선이 읽은 것과 다릅니다")`.
  - `settleRevocation`: 한 UpdateItem 에서 `ADD #gone :gone`(gone 이 있을 때)과 `DELETE #unknown :drop`(gone ∪ dropped 가 있을 때)을 함께 쓴다. 조건은 같다. 빈 String Set 은 보내지 않는다.
  - `save` 는 지금처럼 `writePointer` PutItem 이라 칸을 지운다 — Javadoc 에만 적는다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbRevocationMarksTest'`
  이어서: `./gradlew :storage-dynamodb:test --tests '*DynamoDbTupleSnapshotRepositoryTest'`
  Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: 스냅샷 포인터의 즉시 회수 표시 두 칸 — 조건부 ADD·DELETE, 256KB 한도, 같은 포인터 읽기로 함께 준다 (즉시 회수)`.

### Task 3: core — 다음 회차가 표시를 반영한 기준선(B′·U′)으로 비교한다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` — `synchronize` 의 PRESENT 갈래 ~:238-243, `비교해_맞춘다` 의 변경 없음 ~:299-305, `기준선` ~:355-366, `기준_스냅샷` ~:373-377.
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncRevocationMarksTest.java` (조립은 `FullSyncUseCaseTest` 의 것을 따른다)

**Interfaces:**
- Consumes:
  - 과제 1 의 `findLatestMarked()`·`MarkedSnapshot`.
  - 가짜의 `markRevoking`/`settleRevocation` — 시험에서 표시를 심는 수단이다.
- Produces:
  - `SyncOutcome.noChangeWithSnapshot(String snapshotId)` → `SUCCEEDED, 0, 0, 0, snapshotId, "변경 없음 — 즉시 회수를 기준선에 반영해 스냅샷을 새로 남김"`.
  - `기준선` 은 `of(MarkedSnapshot)` 로 만든다. 표시가 있었는지(`marks` 가 비지 않음)를 담는다.

- [ ] **Step 1: Write the failing test** — `FullSyncRevocationMarksTest`. 기준은 직전 스냅샷 B = {kim-be, kim-pf, park-be}, U = ∅ 이고, 표시는 가짜 API 로 심는다.
  - `회수_뒤_다시_풀리면_다음_회차가_줄을_다시_쓴다`
    - 표시: gone = {kim-be}, unknown = {kim-pf}. D 에 kim 이 활성이다.
    - `writer.written` ⊇ {kim-be, kim-pf}.
    - 새 스냅샷 `tuples` ⊇ 둘이고, `snapshots.marks().isEmpty()`.
  - `결과를_몰랐던_줄만_다시_지우고_지운_줄은_다시_지우지_않는다`
    - 표시는 같고, D 에서 kim 이 비활성이다.
    - 마지막 델타의 `toDelete == {kim-pf}`, `toWrite` 는 비었다.
  - `가드의_기준_수는_B′와_U′의_합이다`
    - B 는 10줄이고 gone 이 그 가운데 5줄이다.
    - 이 회차가 지울 줄로 가드가 멈추게 한다 → 메시지에 `기준 스냅샷 5건` 이 든다.
  - `변경_없음이어도_표시가_있으면_스냅샷을_새로_남긴다`
    - B = {kim-be, park-be}, 표시 gone = {kim-be}, D = {park-be}(kim 비활성).
    - 결과: `SUCCEEDED`, 메시지는 그 문구 그대로, `snapshotId` 가 null 이 아니다.
    - 새 스냅샷은 `tuples == {park-be}` 이고 `uncertain` 이 비었다.
    - `writer.appliedDeltas` 가 비었고, 표시가 비었다.
  - `표시가_없으면_변경_없음은_스냅샷을_남기지_않는다` — 저장 수가 그대로이고 메시지는 `변경 없음`.
  - `전부_보류면_표시가_남는다` — 가드가 통째로 멈춘다(ABORTED) → `snapshots.marks()` 가 그대로이고 저장이 없다.
  - `기록_중_회차는_표시를_읽지_않고_저장이_지운다`
    - 표시를 심은 뒤 `writing.set(true)`. 장부 맞추기로 끝난다.
    - `snapshots.marks().isEmpty()` 이고, 델타에 표시의 영향이 없다 — 지울 줄은 훑은 장부가 정한다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:test --tests '*FullSyncRevocationMarksTest'`
  Expected: FAIL(첫 시험의 `written` 에 kim-be 가 없음 등).
- [ ] **Step 3: Implement**
  - `기준_스냅샷()` 을 `findLatestMarked()` 로 바꾸고, 재활성 판정에는 `직전.snapshot().inactive()` 를 쓴다.
  - `기준선.of(MarkedSnapshot)` 가 위 식으로 B′·U′ 를 만든다.
  - 변경 없음 갈래: 표시가 있었으면 `스냅샷을_저장한다(directory, baseline, TupleWriteResult.empty())` → `state.replaceWith` → `noChangeWithSnapshot(id)`. 없으면 지금 그대로다.
  - 클래스 Javadoc 의 흐름 설명에 V10 한 문장을 더한다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :core:test --tests '*FullSyncRevocationMarksTest'`
  이어서: `./gradlew :core:test --tests '*FullSyncUseCaseTest'`
  Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: 다음 회차가 즉시 회수 표시를 반영한 기준선(B′·U′)으로 비교하고, 변경 없음이어도 표시가 있으면 스냅샷을 새로 남긴다 (즉시 회수)`.

### Task 4: core — 즉시 회수 유스케이스

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncTrigger.java` — `REVOCATION` 을 더한다.
- Modify: `core/src/main/java/dev/starryeye/organization/core/audit/AuditKind.java` — `REVOCATION` 을 더한다.
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java` — `LockPurpose.REVOCATION` 을 더한다.
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryAccountSource.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryUnavailableException.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryReadException.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/ImmediateRevocationUseCase.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/UnknownEmployeeException.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/StillActiveInDirectoryException.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ImmediateRevocationUseCaseTest.java` (`SyncJobs`·가짜 조립은 `FullSyncUseCaseTest` 를 따르고, 이력은 `FakeAuditLog` 로 본다)

**Interfaces:**
- Consumes:
  - 과제 1 의 `baselinePointer`·`markRevoking`·`settleRevocation` 과 두 예외.
  - `SyncJobs.startLockedThen(SyncSource, SyncTrigger, LockPurpose, AuditKind, BiFunction<SyncRun, RunAudit, Mono<Mono<SyncOutcome>>>, Consumer<SyncRun>)`.
  - `DirectoryStateRepository.findUser`·`findGroupIdsContaining(MemberRef.user(id))`.
  - `RelationTupleChecker.existing`.
  - `RelationTupleWriter.apply(TupleDelta.deleteOnly(E), 기록.changes()::tuples)`.
- Produces:
  - `interface DirectoryAccountSource { Mono<AccountState> accountState(String employeeId, String externalId); enum AccountState { ACTIVE, BLOCKED, ABSENT } }`.
    - externalId 는 현재상태의 DN 이고, 없으면 null 이다.
    - 일시 장애 신호면 `DirectoryUnavailableException`, 그 밖은 `DirectoryReadException`.
  - `DirectoryUnavailableException extends TemporaryFailureException(String message, Throwable cause)` — 대기는 `TemporaryFailureException.기본_대기`.
  - `DirectoryReadException extends RuntimeException(String message, Throwable cause)`.
  - `UnknownEmployeeException`·`StillActiveInDirectoryException` — 인자 없는 생성자이고, 메시지는 P4·Global Constraints 의 404·409 문구다.
  - `ImmediateRevocationUseCase(DirectoryStateRepository state, DirectoryAccountSource accounts, RelationTupleChecker checker, RelationTupleWriter writer, TupleSnapshotRepository snapshots, SyncJobs jobs)`.
  - `Mono<SyncRun> start(String employeeId, Consumer<SyncRun> onFinished)`.
    - 락 밖 오류: `UnknownEmployeeException`, `StillActiveInDirectoryException`, 포트의 두 예외(그대로 올린다).
    - 락 경합은 `LockUnavailableException`(그대로 올린다).

- [ ] **Step 1: Write the failing test** — `ImmediateRevocationUseCaseTest`. kim 은 be·pf·qa 소속이고, 장부(`FakeTupleChecker`)에는 be·pf 만 있다.
  - `현재상태에_없는_직원은_UnknownEmployeeException_이고_디렉터리를_읽지_않는다`.
  - `디렉터리에서_활성이면_StillActiveInDirectoryException_이고_실행_기록이_없다`.
  - `디렉터리_읽기_실패는_그대로_올리고_실행_기록이_없다` — `DirectoryUnavailableException`·`DirectoryReadException` 두 경우.
  - `막힌_직원의_장부_줄만_지운다`
    - `deleted == {kim-be, kim-pf}`. 기록은 `REVOCATION`, `SUCCEEDED`, `deletedCount 2`.
    - 메시지는 `즉시 회수 — 디렉터리에서 막힘`.
  - `없는_직원은_앞머리가_디렉터리에_없음이다` — ABSENT.
  - `장부에_거둘_줄이_없으면_쓰지도_표시하지도_않는다`
    - 장부가 비었다. `appliedDeltas` 가 비었고, `marks()` 가 비었다.
    - 메시지는 `즉시 회수 — 장부에 거둘 줄이 없습니다`.
  - `이미_거둔_사람을_다시_부르면_거둘_줄이_없고_앞_표시는_그대로다` (Review Focus)
    - 첫 회수 뒤 장부에서 두 줄을 뺀 채 다시 부른다.
    - 메시지는 거둘 줄 없음이고, `marks().gone()` 이 첫 회수 그대로다.
  - `소속이_없는_직원은_Check_없이_거둘_줄_없음이다` (Review Focus) — `checker.checked` 가 비었다.
  - `기준선이_있으면_지우기_전에_표시하고_지운_뒤_정리한다`
    - `writer.onApply` 갈고리에서 `marks().unknown() == E` 를 본다.
    - 끝난 뒤 `gone == E` 이고 `unknown` 이 비었다.
  - `결과를_모르는_줄은_unknown_에_남고_확정_실패는_빠진다`
    - `lostResponseFor(kim-pf)` 를 걸면 `PARTIAL` 이다.
    - 메시지: `즉시 회수 — 디렉터리에서 막힘 / 1건의 튜플 적용에 실패했습니다. 다음 동기화가 다시 시도합니다`.
    - `gone == {kim-be}`, `unknown == {kim-pf}`.
    - `failFor(kim-pf)` 로 바꾸면 `unknown` 이 비었다.
  - `기록_중이거나_기준선이_없으면_표시하지_않고_지운다` — WRITING·NONE 두 경우. 지우지만 `marks()` 가 비었다.
  - `표시를_남기지_못하면_지우지_않는다`
    - `failMarkRevoking(new RevocationMarksFullException())` → `FAILED`, 메시지가 한도 문구 그대로, `appliedDeltas` 가 비었다.
    - `failMarkRevoking(new BaselineMovedException("x"))` → 메시지가 `즉시 회수 표시를 남기지 못해 거두지 않았습니다` 로 시작한다.
  - `정리에_실패하면_결론은_그대로이고_메시지_끝에_알린다` — `SUCCEEDED` 이고, 메시지는 앞머리 + 정리 실패 꼬리다.
  - `차단기로_멈추면_그때까지로_정리하고_FAILED` — `abortWhen` → `FAILED`, 표시가 부분 결과로 정리됐다.
  - `락을_못_잡으면_LockUnavailableException` — `FakeMutationLock` 을 미리 쥔다.
  - `이력에_REVOCATION_종류로_지운_줄이_남는다` — `FakeAuditLog` 의 원인 종류와 회수 줄.
  - `실행_기록_메시지에_직원_id_가_없다` — 직원 id 를 `emp-7f3a` 처럼 남다른 값으로 두고, 모든 결론 메시지에 그 문자열이 없다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :core:test --tests '*ImmediateRevocationUseCaseTest'`
  Expected: 컴파일 실패(유스케이스 없음).
- [ ] **Step 3: Implement** — 흐름은 스펙 §5.3 이다.
  - 앞머리는 BLOCKED → 막힘, ABSENT → 없음이다.
  - 결론:
    - 실패가 없으면 `new SyncOutcome(SUCCEEDED, 0, deleted, 0, null, 앞머리)`.
    - 실패가 있으면 `SyncOutcome.partial(result, null).withNote(앞머리)`.
    - 차단기면 `SyncOutcome.stopped(partial, null, 사유).withNote(앞머리)`.
    - 정리 실패면 `.withSuffix(꼬리)` 를 더한다.
  - 정리의 `dropped` 는 `result.failures()` 가운데 `uncertain == false` 인 줄이다.
  - 정리 실패 WARN 에는 줄 수만 싣는다.
  - 반납 뒤 할 일은 없으니 결론을 `Mono.just` 로 감싼다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :core:test --tests '*ImmediateRevocationUseCaseTest'`
  Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: 한 사람 즉시 회수 유스케이스 — 락 밖에서 현재상태·디렉터리를 보고, 현재상태 소속을 Check 로 걸러 표시→지우기→정리 (즉시 회수)`.

### Task 5: connector-ldap — 두 전략의 한 사람 읽기

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapMappingStrategy.java`
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/SingleAccountRead.java` (패키지 전용 도우미)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`, `DitStrategy.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapIdentifiers.java` (GUID 문자열 → 바이트)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java` (`static boolean 아래인가(String 절대DN, String 절대베이스)`)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesReadAccountTest.java`, `DitReadAccountTest.java` (`extends EmbeddedLdapSupport`)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapIdentifiersTest.java`, `strategy/LdapDnsTest.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/SingleAccountReadTest.java` (도우미가 패키지 전용이라 같은 패키지)

**Interfaces:**
- Consumes: 과제 4 의 `DirectoryAccountSource.AccountState`.
- Produces:
  - `AccountState readAccount(LdapTemplate template, String employeeId, String externalId)` on `LdapMappingStrategy` — 두 전략이 구현한다.
  - `public static Optional<byte[]> LdapIdentifiers.guidBytes(String guid)` — `guid(byte[])` 의 역이다. 모양이 `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` 가 아니면 빈 값.
  - `static Optional<String> SingleAccountRead.값_필터(String attribute, String employeeId)`.
    - `objectGUID` 면 `(objectGUID=\xx…)` 다(바이트마다 소문자 두 자리). GUID 모양이 아니면 빈 값이다.
    - 그 밖은 `(<attribute>=<LdapEncoder.filterEncode(id)>)`.

- [ ] **Step 1: Write the failing test**
  - `LdapIdentifiersTest.GUID_문자열을_바이트로_되돌리면_guid_가_같은_문자열을_준다` — 바이트 0..15 를 왕복한다. 대문자·모양 밖의 문자열은 빈 값이다.
  - `SingleAccountRead` 값 필터 단위 시험:
    - GUID 바이트 `00 01 … 0f` 의 GUID 문자열 → `(objectGUID=\00\01\02\03\04\05\06\07\08\09\0a\0b\0c\0d\0e\0f)`.
    - `uid` 와 `a*(b)\c` → `(uid=a\2a\28b\29\5cc)`.
  - `LdapDnsTest.아래인가` — 같음 참, 자식 참, 형제 거짓, 대소문자·공백만 다른 베이스 참.
  - 두 전략 시험에 같은 이름으로 각각 둔다.
    - 엔트리: 활성 kim, 영구 잠금(`pwdAccountLockedTime: 000001010000Z`) lee.
    - 같은 uid 가 두 OU 에 있는 dup(하나 잠금·하나 활성), 공백이 든 uid `a b`, 괄호가 든 uid `p(1)`.
    - 직원 검색 베이스 밖에 같은 uid 를 가진 out.
    - 시험:
      - `활성이면_ACTIVE`, `영구_잠금이면_BLOCKED`.
      - `엔트리를_지우면_DN_다시_읽기의_32로_ABSENT` — 서버에서 lee 를 지우고 lee 의 DN 을 넘긴다.
      - `검색_베이스_밖은_고르지도_다시_읽지도_않아_ABSENT` — out 의 DN 을 넘긴다.
      - `대소문자만_다른_id_는_다른_사람이라_ABSENT` — `Kim`, externalId null.
      - `id_가_겹치면_하나라도_활성이면_ACTIVE`.
      - `정규화가_바꾼_id_는_DN_으로_다시_읽어_ACTIVE` — `a_b` 와 그 DN.
      - `필터_특수_문자는_이스케이프한다` (Review Focus) — `p(1)` 은 ACTIVE 다. `*` 는 externalId null 로 ABSENT 다.
      - `한도를_넘는_엔트리는_고르지_않는다` — 기존 `*LengthLimitTest` 의 긴 id 를 쓴다.
      - `검색_베이스가_없으면_NameNotFoundException_이_오른다` — 직원 검색 베이스를 `ou=nowhere` 로 둔다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :connector-ldap:test --tests '*ReadAccountTest' --tests '*SingleAccountReadTest' --tests '*LdapIdentifiersTest' --tests '*LdapDnsTest'`
  Expected: 컴파일 실패.
- [ ] **Step 3: Implement**
  - 전략마다 베이스(groupOfNames 는 `user-search-base`, DIT 는 `root-dn`)·필터(P8)·`직원_속성`·`userMapper(config, clock.instant())` 를 `SingleAccountRead` 에 넘긴다. 매퍼 결과를 `(id, active)` 로 바꾸는 것도 전략이다.
  - id 검색과 DN 다시 읽기는 둘 다 `PagedLdapSearch.search(…, properties.getPageSize(), …)` 다. DN 다시 읽기는 `SearchScope.OBJECT` 이고, 베이스는 `LdapDns.상대로(externalId, baseDn)`, 필터는 사용자 필터만이다. `controlsOf` 가 범위를 옮긴다.
  - `org.springframework.ldap.NameNotFoundException` 은 DN 다시 읽기에서만 빈 결과로 바꾼다.
  - 고르기는 `id.equals(employeeId)` 이고, 한도 넘음·식별 속성 없음은 뺀다. 판정은 스펙 V3 이다.
- [ ] **Step 4: Run test to verify it passes** — Step 2 의 명령. Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: 두 전략의 한 사람 읽기 — 정기 읽기와 같은 베이스·필터·매퍼에 식별 속성을 AND 로 더하고, 못 찾으면 현재상태 DN 을 base 로 다시 본다 (즉시 회수)`.

### Task 6: connector-ldap — 한 사람 읽기 어댑터(주소 넘김·상한·실패 분류)

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapDirectoryAccountSource.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java` (`ldapDirectorySnapshotSource` 곁, ~:239-247)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapDirectoryAccountSourceTest.java` (두 서버·가로채기는 `LdapFailoverTest` 를 따른다)

**Interfaces:**
- Consumes: 과제 5 의 `LdapMappingStrategy.readAccount`, 과제 4 의 포트·예외.
- Produces:
  - `public LdapDirectoryAccountSource(List<LdapTemplate> 시작_주소별_템플릿, LdapMappingStrategy strategy)`.
  - 패키지 전용 `LdapDirectoryAccountSource(List<LdapTemplate>, LdapMappingStrategy, Duration 전체_상한, Duration 주소당_상한)`.
  - 상수 `전체_상한 = 30초`, `주소당_상한 = 10초`.
  - 빈 `@Bean DirectoryAccountSource ldapDirectoryAccountSource(LdapTemplate template, LdapMappingStrategy strategy, LdapProperties properties)` — 템플릿은 `시작_주소별_템플릿(properties, template)` 이다.

- [ ] **Step 1: Write the failing test** — `LdapDirectoryAccountSourceTest`. 서버 A·B 가 같은 그림을 갖고, 시험용 상한을 짧게 준다.
  - `첫_주소의_검색이_바쁘면_둘째_주소에서_답한다` — A 가 검색에 `ResultCode.BUSY` 를 낸다 → `ACTIVE`.
  - `첫_주소가_인증을_거절하면_넘기지_않고_DirectoryReadException` — B 의 바인드 수가 0 이다.
  - `모든_주소가_바쁘면_DirectoryUnavailableException`.
  - `주소_상한을_넘으면_다음_주소로_넘긴다` — 주소당 200ms 이고, A 의 검색 가로채기가 2초 잔다 → `ACTIVE`.
  - `전체_상한을_넘으면_DirectoryUnavailableException` — 전체 300ms·주소당 200ms 이고, 둘 다 잔다.
  - `검색_베이스가_없으면_DirectoryReadException` — 과제 5 의 32 가 500 쪽으로 간다.
  - 시간은 단언하지 않는다 — 결과 종류만 본다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :connector-ldap:test --tests '*LdapDirectoryAccountSourceTest'`
  Expected: 컴파일 실패.
- [ ] **Step 3: Implement**
  - k 번째 시도: `Mono.fromCallable(() -> strategy.readAccount(템플릿들.get(k), id, dn)).subscribeOn(Schedulers.boundedElastic()).timeout(주소당_상한)`.
  - 일시 장애 신호 — `org.springframework.ldap.CommunicationException`, `org.springframework.ldap.ServiceUnavailableException`, `java.util.concurrent.TimeoutException` — 면 다음 템플릿으로 간다.
    - 넘길 때 WARN `한 사람 읽기를 다음 주소로 넘긴다 — 시작 주소 {}` 를 남긴다(`LdapUrls.첫_주소`).
  - 그 밖은 바로 `DirectoryReadException("디렉터리에서 직원을 읽지 못했습니다(" + 단순 클래스명 + ")", e)`.
  - 다 쓰면 `DirectoryUnavailableException("디렉터리의 모든 주소가 일시 장애로 답하지 않았습니다", 마지막)`.
  - 사슬 전체에 `.timeout(전체_상한)` 을 걸고, 그 `TimeoutException` 은 `DirectoryUnavailableException("디렉터리 읽기가 상한 안에 끝나지 않았습니다", e)` 로 바꾼다.
  - 클래스 Javadoc 에 적는다: JNDI 의 막힌 호출은 `ldap.read-timeout` 까지 그 스레드에 남는다(§5.2). 같은 이유로 `ldap.read-timeout` 이 주소 상한보다 짧으면 그 시간 초과는 500 쪽이다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :connector-ldap:test --tests '*LdapDirectoryAccountSourceTest'`
  이어서: `./gradlew :connector-ldap:test --tests '*LdapFailoverTest'`
  Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: 한 사람 읽기 어댑터 — 주소를 차례로 한 번씩, 주소 10초·전체 30초 상한, 일시 장애 신호만 503 쪽 (즉시 회수)`.

### Task 7: app-ldap — 끝점·결선·게이지·종단

**Files:**
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/LastSuccessGauge.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java` (생성자 인자 추가 포함)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LastSuccessGaugeTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapAuthEndToEndTest.java`
- Create: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapImmediateRevocationEndToEndTest.java` (인프라 조립은 `LdapRevocationConvergenceEndToEndTest` 를 따른다)

**Interfaces:**
- Consumes: 과제 4 의 `ImmediateRevocationUseCase.start` 와 그 예외들, 과제 6 의 `DirectoryAccountSource` 빈.
- Produces:
  - `@PostMapping("/revocations") @ResponseStatus(ACCEPTED) Mono<SyncRunResponse> revoke(@RequestBody(required = false) RevocationRequest body)`.
  - `record RevocationRequest(String employeeId)`.
  - `UseCaseConfig` 의 `@Bean ImmediateRevocationUseCase`.

- [ ] **Step 1: Write the failing test**
  - `AdminSyncControllerTest`:
    - `즉시_회수는_202와_RUNNING을_준다` — 유스케이스를 흉내 내고, `trigger` 가 `REVOCATION` 이다.
    - `즉시_회수의_본문이_없거나_비면_400이고_유스케이스를_부르지_않는다` (Review Focus) — 본문 없음, `{}`, `{"employeeId":"  "}`.
    - 예외별 응답:
      - `UnknownEmployeeException` → 404 와 P4 문구.
      - `StillActiveInDirectoryException` → 409 와 활성 문구.
      - `LockUnavailableException` → 409 `다른 동기화·재적재가 진행 중입니다`.
      - `DirectoryUnavailableException` → 503 과 `Retry-After: 10`, P4 문구.
      - `DirectoryReadException` → 500 과 P4 문구.
  - `LastSuccessGaugeTest.즉시_회수_회차는_세지_않는다` — 더 최근의 `REVOCATION` SUCCEEDED 가 있어도 값은 그 앞 `SCHEDULED` 의 끝난 시각이다.
  - `LdapAuthEndToEndTest.범위가_없으면_즉시_회수는_403이다` — `admin.sync` 없는 토큰이다.
  - `LdapImmediateRevocationEndToEndTest` (`@Order` 로 이어지는 한 줄기):
    1. `/admin/sync/full` 로 동기화하면 kim 의 줄이 Check 로 성립한다.
    2. LDAP 에서 kim 에 `pwdAccountLockedTime: 000001010000Z` 를 넣는다.
    3. `POST /admin/sync/revocations {"employeeId":"kim"}` → 202. 끝날 때까지 기다리면(`SyncJobClient.기다린다`) `SUCCEEDED`, `trigger REVOCATION`, `deletedCount` 가 kim 의 소속 수다.
    4. kim 의 Check 가 거짓이다. `/admin/employees/kim/history` 의 첫 줄은 `cause.kind == REVOCATION` 이다.
    5. LDAP 에서 잠금을 지운다.
    6. `/admin/sync/full` 이 kim 의 줄을 다시 쓴다(`writtenCount ≥ 1`, Check 참).
    - 따로: 활성인 park 을 부르면 409 와 활성 문구다.
- [ ] **Step 2: Run test to verify it fails**
  Run: `./gradlew :app-ldap:test --tests '*AdminSyncControllerTest' --tests '*LastSuccessGaugeTest'`
  Expected: 컴파일 실패.
- [ ] **Step 3: Implement**
  - 컨트롤러: 400 판정은 `body == null || employeeId == null || employeeId.isBlank()` 다. 로그는 `log.info("즉시 회수 요청")` 이고 직원 id 는 싣지 않는다.
  - 예외 대응: 404·409 는 `ResponseStatusException(상태, 예외 메시지, e)`, 락 경합은 `/full` 과 같은 409, 503 은 `RetryLaterStatusException(P4 문구, e.retryAfter(), e)` 다.
  - 500 은 `log.error("즉시 회수의 디렉터리 읽기가 실패했다", e)` 를 남긴 뒤 `ResponseStatusException(INTERNAL_SERVER_ERROR, P4 문구, e)` 를 던진다.
  - `onFinished` 는 `metrics::record` 다.
  - 게이지: `센다` 앞에서 `run.trigger() != SyncTrigger.REVOCATION` 으로 거르고 Javadoc 에 V12 를 적는다.
  - 컨트롤러 클래스 Javadoc 에 새 끝점 한 문단을 더한다.
- [ ] **Step 4: Run test to verify it passes**
  Run: `./gradlew :app-ldap:test --tests '*AdminSyncControllerTest' --tests '*LastSuccessGaugeTest'`
  이어서: `./gradlew :app-ldap:test --tests '*LdapAuthEndToEndTest'`
  이어서: `./gradlew :app-ldap:test --tests '*LdapImmediateRevocationEndToEndTest'`
  Expected: PASS.
- [ ] **Step 5: Commit** — 메시지: `feat: POST /admin/sync/revocations — 즉시 회수 끝점과 결선, sync.last.success 는 즉시 회수 회차를 세지 않는다 (즉시 회수)`.

### Task 8: 문서 — 온보딩과 README 를 현재 동작으로

**Files:**
- Modify: `docs/onboarding/02-용어집.md` — 즉시 회수, 즉시 회수 표시.
- Modify: `docs/onboarding/04-데이터-모델.md` — 스냅샷 포인터의 두 칸·한도.
- Modify: `docs/onboarding/05-LDAP-동기화.md`
  - §2 트리거 표에 `REVOCATION`.
  - §6.1·§6.2 에 B′·U′, §6.3 에 변경 없음의 저장.
  - §6.7 의 "현재상태는 판단에 쓰이지 않는다" 를 "이 API 가 입력으로 쓴다(404·DN·후보)" 로.
  - §12 "회수는 언제 닿나" 에 비상 경로.
- Modify: `docs/onboarding/08-동시성과-작업.md` — 락 용도 `REVOCATION`, 작업 목록.
- Modify: `docs/onboarding/09-관리-API.md` — 끝점·응답 표(스펙 §5.1 과 P4 문구).
- Modify: `docs/onboarding/10-감사-이력.md` — 원인 종류 `REVOCATION`.
- Modify: `docs/onboarding/11-운영.md` — `sync.last.success` 가 세지 않는 회차, `sync.duration{trigger=REVOCATION}`, 퇴사 처리 절차(디렉터리에서 막기 → 요청 → `GET /admin/sync/runs/{runId}`).
- Modify: `docs/onboarding/12-장애와-복구.md` — "막은 계정의 권한을 지금 거둬야 한다" 처방, 한도(V9)에 닿았을 때, 503·500 의 뜻.
- Modify: `docs/onboarding/13-한계와-알려진-문제.md` — §4.6 을 "일부 고침" 으로 바꾸고, 남은 것(증분 없음)과 스펙 §11 의 한계를 적는다.
- Modify: `docs/onboarding/15-결정-색인.md` — V1~V15 와 P1~P8 을 한 묶음으로.
- Modify: `README.md` — 관리 API 목록, 운영 절의 퇴사 처리.

**Interfaces:**
- Consumes: 과제 1~7 의 이름·문구·응답 표. 코드와 다르면 코드가 맞고 문서를 고친다.

- [ ] **Step 1: 기준 커밋을 정한다**
  Run: `git log --oneline -1 -- core connector-ldap storage-dynamodb app-ldap`
  그 해시7 이 머리의 값이다.
- [ ] **Step 2: 위 문서를 고친다** — 고친 문서만 머리를 `기준 커밋: <해시7> (immediate-revocation, main 에 머지됨)` 으로 올린다. 문서끼리 서로 가리키는 링크(절 앵커)가 실제 제목과 맞는지 본다.
- [ ] **Step 3: 검증**
  Run: `grep -rn '현재상태는 판단에 쓰이지 않는다' docs/onboarding`
  Expected: 남은 줄이 이 API 의 예외를 함께 말한다(또는 없다).
  이어서 새로 넣은 문구 넷을 코드와 대조한다: `grep -rn '즉시 회수 — 장부에 거둘 줄이 없습니다' core docs`, `grep -rn '즉시 회수 표시가 한도(256KB)' core docs`, `grep -rn '변경 없음 — 즉시 회수를 기준선에' core docs`, `grep -rn '디렉터리에서 활성인 직원은 거두지 않습니다' core docs`.
  Expected: 넷 다 core 와 docs 양쪽에 있다.
- [ ] **Step 4: Commit** — 메시지: `docs: 즉시 회수를 온보딩(02·04·05·08·09·10·11·12·13·15)과 README 에 현재 동작으로 반영한다 (즉시 회수)`.

---

마무리(메인 세션): 전체 `cleanTest test` → `cleanScaleTest scaleTest` 를 차례로 돌리고, 최종 리뷰와 그 수정을 마친다. 그다음 스펙 끝에 결과 절(검증 수치·바뀐 곳·구현 중 판정·리뷰와 수정·남긴 것)을 적는다. 푸시는 사용자의 말을 기다린다.
