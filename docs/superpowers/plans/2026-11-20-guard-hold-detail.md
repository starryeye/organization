# 삭제 가드 멈춤 내역과 확인한 수로만 넘기는 force — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가드가 멈춘 LDAP 회차가 무엇을 지우려 했는지 실행 기록에 구조화해 남긴다. `force` 는 운영자가 확인한 줄 수(`maxDeletes`)로만 넘기게 한다. app-ldap 에 락 지표를 단다.

**Architecture:**
- 지울 줄과 디렉터리 그림만으로 내역을 세는 순수 값 `GuardHold`(core.model)를 둔다. 가드가 걸린 두 자리(비교 경로, 장부 맞추기)가 이것을 결론(`SyncOutcome`)에 붙이고, `SyncRun`·DynamoDB·관리 API 응답이 그대로 옮긴다.
- 강제는 허용 수 값 `ForceAllowance` 로만 들어오고, 상한만 N 인 같은 `DeletionGuard` 로 판정한다.
- app-ldap `SyncMetrics` 가 `LockObserver` 를 구현한다.

**Tech Stack:** Java 17, Gradle 멀티 모듈, Spring Boot WebFlux/Reactor, Micrometer, Lombok, JUnit5/AssertJ/Mockito, DynamoDB SDK v2(저장소에 Jackson 없음), Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-20-guard-hold-detail-design.md` — 결정 H1~H5·F1~F5·L1~L3, 동작 §3, 시험 §4. 이 계획은 그 설계를 따르고, 둘이 다르면 설계가 이긴다.

## Global Constraints

- **가드 문구의 끝 문장(F5)**, 그대로:
  `강제 실행하려면 실행 기록의 guardHold 를 확인한 뒤 force=true 와 maxDeletes(허용할 지울 줄 수)로 재요청하세요`
  — 옛 `강제 실행하려면 force=true 로 재요청하세요` 를 바꾼다. 앞부분(`삭제 대상 %d건(%s %d건의 %.1f%%)이 %s 초과했습니다. `)과 `넘은_것` 세 꼴은 그대로다.
- **강제 회차의 결론 앞 문구(F4):** `강제 실행 — 지울 줄 허용 %d건` (숫자는 쉼표 없이). `SyncOutcome.withNote` 로 붙이므로 뒤에 ` / ` 와 원래 메시지가 온다.
- **400 문구(F3)** 둘:
  - `force=true 에는 maxDeletes(허용할 지울 줄 수, 1 이상)가 함께 와야 합니다`
  - `maxDeletes 는 force=true 와 함께만 씁니다`
- **검사 순서:** `force=true` 의 `admin.destructive` 403 → `maxDeletes` 400 → (재적재) `mode` 400. 숫자가 아닌 `maxDeletes` 는 스프링 변환의 400 이다(설계 §3.2 판단).
- **예의 수:** 갈래마다 **10개**, `String` 자연 순서로 앞에서부터. 수는 서로 다른 id 의 수다.
- **JSON 칸 이름(설계 §3.1):**
  - `guardHold.lines.{total,directMember,child,other}`
  - `guardHold.users.{gone,inactive,losingMembership}.{count,sample}`
  - `guardHold.groups.{gone,losingParent}.{count,sample}`
- **DynamoDB 속성:** 실행 기록 아이템의 `guardHold`(M). 안은 다음과 같다.
  - 줄 수 `directMemberLines`·`childLines`·`otherLines`(N)
  - 갈래 `usersGone`·`usersInactive`·`usersLosingMembership`·`groupsGone`·`groupsLosingParent`(M) — 각각 `count`(N)·`ids`(L of S, 빈 L 가능)
  - 내역이 없으면 속성 자체가 없다.
- **지표 이름:** `sync.lock.wait`(Timer), `sync.lock.contended`, `sync.lock.lease_lost`(Counter), 태그 없음. 두 앱 모두 `*.lock.contended`·`*.lock.lease_lost` 를 생성자에서 0 으로 만든다.
- **core 는 Micrometer·Spring·Jackson 을 모른다.** 새 core 타입은 순수 자바 record 다.
- **로그는 수만 담는다.** 내역의 WARN 에 id 를 싣지 않는다. 직원 id 는 `DirectoryUser.id` 이고 `externalId` 가 아니다.
- **시험은 벽시계를 단언하지 않는다.**
- **Gradle 은 한 번에 하나만 돌린다.** 서브에이전트는 아래 각 과제의 모듈·클래스 시험만 돌린다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- **git:** `git stash` 를 쓰지 않는다. 명령은 하나씩, 경로는 그대로 쓴다(`&&`·`$(...)` 없이).
  - 커밋 메시지는 한국어 `feat:`/`fix:`/`test:`/`docs:` 꼴이고 끝에 ` (가드 멈춤 내역)` 을 붙인다.
  - 트레일러는 `Co-Authored-By: Claude <자기 모델 이름> <noreply@anthropic.com>` 이다.
- **주석·테스트 이름은 기존 꼴을 따른다.** 한국어 Javadoc, `@DisplayName` 한국어, 메서드 이름 한국어 밑줄이다. 근거는 `설계 2026-11-20 H1` 처럼 단다.

## Review Focus

1. **일부 반영 회차가 지우다 차단기로 멈춘 경우**(`SyncOutcome.stopped`)에도 내역이 붙어야 한다. 가드가 걸린 회차이기 때문이다. → 과제 2 의 시험.
2. **강제 회차가 장부 맞추기(기준선 없음)에서 비율은 넘고 줄 수는 N 안인 경우** 그대로 지워야 한다. 비율을 보지 않기 때문이다. → 과제 3 의 시험.
3. **설정에서 가드를 꺼 둔 배포**의 강제 회차도 N 을 넘으면 멈춰야 한다. → 과제 3 의 시험.
4. **지울 줄에 모양이 다른 줄이 섞인 경우**에도 내역 계산은 예외 없이 끝나야 한다. 예: `direct_member` 인데 user 자리가 `group:…`, object 에 `group:` 접두사가 없음. 줄은 관계로 세고, 접두사가 맞는 자리만 갈래로 센다. → 과제 1 의 시험.
5. **강제 회차의 장부 맞추기 결론**에서 허용 수 문구가 사정(`기준선 없음 …`)보다 앞이어야 한다. → 과제 3 의 시험.

---

### Task 1: core — 멈춤 내역 값 `GuardHold` 와 결론·실행 기록의 칸

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/GuardHold.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncRun.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/model/GuardHoldTest.java`(새)

**Interfaces:**
- Produces:
  - `public record GuardHold(int directMemberLines, int childLines, int otherLines, Sample usersGone, Sample usersInactive, Sample usersLosingMembership, Sample groupsGone, Sample groupsLosingParent)`
  - `public static final int SAMPLE_SIZE = 10`
  - `public int totalLines()` — 세 줄 수의 합
  - `public static GuardHold of(Set<RelationTuple> deletes, DirectorySnapshot directory)`
  - `public String summary()`
  - 안쪽 `public record Sample(int count, List<String> ids)` — 컴팩트 생성자에서 `ids = List.copyOf(ids)`
- Produces: `SyncOutcome` 의 마지막 구성 요소 `GuardHold guardHold`(null 가능).
  - 지금의 6인자 생성자 `SyncOutcome(SyncStatus, int, int, int, String, String)` 는 `guardHold=null` 로 위임하는 보조 생성자로 남긴다. 기존 호출 4곳(`SnapshotArchiveUseCase` 2, `DynamoDbSyncRunRepositoryTest` 2)이 그대로 컴파일된다.
  - 모든 정적 팩터리는 null 을 넣는다.
  - `public SyncOutcome withGuardHold(GuardHold hold)` — 칸만 바꾼 복사.
  - `withSuffix`·`withNote` 는 칸을 보존한다.
- Produces: `SyncRun` 의 마지막 구성 요소 `GuardHold guardHold`. `finished(outcome, at)` 가 `outcome.guardHold()` 를 옮긴다.

- [ ] **Step 1: 실패하는 시험을 쓴다** — `GuardHoldTest`. 그림은 `DirectorySnapshot(users, groups, Set.of())` 로 직접 만든다. 시험과 단언:
  - `줄은_관계로_센다`: dm 1·child 1·관계 `"viewer"` 1 → `directMemberLines=1, childLines=1, otherLines=1, totalLines()=3`.
  - `직원은_그림대로_가른다`: 그림에 없는 `U1` → `usersGone.count=1, ids=[U1]`. 비활성 `U2` → `usersInactive`. 활성 `U3` → `usersLosingMembership`.
  - `같은_직원의_줄_셋은_한_명이다`: `U1` 의 dm 3줄 → `usersGone.count=1`, `directMemberLines=3`.
  - `하위_조직_연결은_C_로_가르고_P_가_없으면_사라진_조직이다`:
    - `child(C1,P1)` 에서 C1 이 있고 P1 이 없으면 `groupsLosingParent.ids=[C1]`, `groupsGone.ids=[P1]`.
    - `child(C2,P2)` 에서 C2 가 없으면 `groupsGone` 에 C2.
  - `소속_줄의_조직이_없으면_사라진_조직이다`: dm `(U, G9)` 에서 G9 가 그림에 없음 → `groupsGone` 에 G9, 한 번만.
  - `예는_사전순_앞_10개다`: 사라진 직원 15명(`E15`…`E01` 역순으로 넣음) → `count=15`, `ids` 는 `E01`…`E10`.
  - `빈_지울_줄은_모두_0이다`: 모든 수 0, 모든 `ids` 빈 목록.
  - `모양이_다른_줄도_예외_없이_센다`(Review Focus 4):
    - `new RelationTuple("group:X", "direct_member", "group:G")` → `directMemberLines=1`, 직원 갈래 0. G 가 그림에 없으면 `groupsGone` 에 G.
    - `new RelationTuple("user:U", "direct_member", "G")`(접두사 없음) → 조직 갈래 0.
  - `요약은_수만_담는다`: 작은 예 하나에서 `summary()` 가 아래 형식과 정확히 같고, 예의 id 가 들어 있지 않다.
    `"지울 줄 %d건(소속 %d·하위 조직 연결 %d·그 밖 %d) — 직원 사라짐 %d·비활성 %d·소속 잃음 %d / 조직 사라짐 %d·상위 잃음 %d"`
  - `결론의_내역이_실행_기록으로_옮겨진다`: `SyncRun.started(...).finished(SyncOutcome.aborted("m").withGuardHold(hold), at).guardHold()` 가 `hold`.
  - `withNote 와 withSuffix 는 내역을 보존한다`.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.model.GuardHoldTest'` → 컴파일 실패(`GuardHold` 없음).
- [ ] **Step 3: 구현한다.**
  - 분류 규칙은 설계 §3.1 의 표 그대로다. 자리마다 따로 본다.
    - user 자리가 `user:` 로 시작하면 직원 갈래.
    - `group:` 으로 시작하면 조직으로 읽는다.
    - 줄 묶음은 관계(`DIRECT_MEMBER`·`CHILD`·그 밖)로만 정한다.
  - 갈래마다 서로 다른 id 를 집합으로 모은다. 끝에 정렬해 앞 10개를 뽑는다(뽑는 방법은 자유).
  - Javadoc 에 근거(설계 2026-11-20 H2·H3)와 "가드가 걸렸을 때만 만든다"를 적는다.
- [ ] **Step 4: 통과를 확인한다** — 같은 명령 PASS. 이어서 `./gradlew :core:test` 전체 PASS(보조 생성자 덕에 기존 시험이 그대로 돈다).
- [ ] **Step 5: 커밋한다** — `feat: 가드 멈춤 내역 값 GuardHold(줄 셋·직원 셋·조직 둘, 사전순 예 10개)와 결론·실행 기록의 칸 (가드 멈춤 내역)`

### Task 2: core — 가드가 걸린 회차의 결론에 내역을 붙이고 WARN 을 남긴다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`(`비교해_맞춘다`, `비활성_회수만_반영한다`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`(`align`, `가드로_본다`)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`, `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java`

**Interfaces:**
- Consumes: Task 1 의 `GuardHold.of`, `GuardHold.summary()`, `SyncOutcome.withGuardHold`.
- Produces: 가드가 걸린 LDAP 회차(ABORTED, 또는 일부 반영 뒤 차단기로 FAILED)의 `SyncRun.guardHold()` 가 null 이 아니다. 가드를 지난 회차는 null 이다.
  - `TupleReconciler` 의 타입(`DeleteVerdict`·`Reconciliation`)은 바꾸지 않는다(설계 §3.1 판단).

- [ ] **Step 1: 실패하는 시험을 쓴다.** 기존 가드 시험의 준비(기준 스냅샷·그림)를 다시 쓴다.
  - `FullSyncUseCaseTest`:
    - `가드가_통째로_막으면_실행_기록에_내역이_있다`: 사라진 직원 수가 그림대로이고, `lines.total` 이 메시지의 "삭제 대상 N건"의 N 과 같다.
    - `비활성_회수만_반영해도_내역은_지우려던_전체다`: `usersInactive.count` 가 반영한 직원 수이고, `totalLines()` 는 `delta.toDelete()` 전체다.
    - `비활성_회수만_반영하다_차단기로_멈춰도_내역이_있다`(Review Focus 1): 쓰기 가짜가 지우기에서 차단기로 멈춘다. 기존 "비활성 회수 … 차단기" 시험의 준비를 쓴다. 결과는 FAILED 이고 `guardHold` 가 null 이 아니다.
    - `가드를_지나면_내역이_없다`.
  - `RebuildUseCaseTest`(장부 맞추기):
    - `재적재가_전부_보류하면_내역이_있다`: 훑은 장부의 찌꺼기가 내역의 사라진 직원·조직으로 나온다.
    - `재적재가_비활성_회수만_반영해도_내역이_있다`.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.FullSyncUseCaseTest' --tests 'dev.starryeye.organization.core.usecase.RebuildUseCaseTest'` → 새 시험 FAIL(`guardHold` 가 null).
- [ ] **Step 3: 구현한다.**
  - 비교 경로: `decision.aborted()` 일 때 `GuardHold.of(delta.toDelete(), directory)` 를 만든다. `log.warn("삭제 가드 내역: {}", hold.summary())` 를 남긴다. 그 회차가 내는 결론 셋(`aborted`, `revokedOnly`, `stopped`)에 `withGuardHold(hold)` 로 붙인다. 내역은 `비활성_회수만_반영한다` 에 인자로 넘긴다.
  - 장부 맞추기:
    - `align` 안에 지역 `AtomicReference<GuardHold>` 를 둔다.
    - `가드로_본다` 가 `decision.aborted()` 일 때 `GuardHold.of(stale, directory)` 를 그 그릇에 담고 같은 WARN 을 남긴다.
    - `held(...)` 결론과 `commit` 의 결론에 `withGuardHold(그릇.get())` 을 붙인다. null 이면 칸도 null 이다.
- [ ] **Step 4: 통과를 확인한다** — Step 2 의 명령 PASS. 이어서 `./gradlew :core:test` PASS.
- [ ] **Step 5: 커밋한다** — `feat: 가드가 걸린 LDAP 회차(비교·장부 맞추기)의 결론에 멈춤 내역을 붙이고 수만 담은 WARN 을 남긴다 (가드 멈춤 내역)`

### Task 3: 확인한 수로만 넘기는 force — core 의 허용 수·강제 가드·입구, app-ldap 컨트롤러

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/ForceAllowance.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`(F5 문구, `forced`, 클래스·메서드 Javadoc 의 "force 로 건너뛴다" 문장)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`, `LedgerAlignment.java`, `RebuildUseCase.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`
- Test:
  - `core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`
  - `core/src/test/java/dev/starryeye/organization/core/guard/ForceAllowanceTest.java`(새)
  - `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
  - `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java`
  - `core/src/test/java/dev/starryeye/organization/core/usecase/LdapSyncAuditTest.java`
  - `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java`
  - `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java`

**Interfaces:**
- Consumes: Task 2 의 내역 붙이기(강제 회차가 N 을 넘어 멈춰도 같은 자리에서 내역이 붙는다).
- Produces:
  - `public record ForceAllowance(int maxDeletes)` — 컴팩트 생성자가 `maxDeletes < 1` 이면 `IllegalArgumentException`. `public String note()` 는 Global Constraints 의 F4 문구다.
  - `DeletionGuard`:
    - `public static DeletionGuard forced(ForceAllowance allowance)` — 정책 `(enabled=true, 비율은 보지 않음, maxDeletes=allowance.maxDeletes())`. 비율을 끄는 방법은 자유다. 예: `minBaseline=Integer.MAX_VALUE`.
  - `FullSyncUseCase`:
    - `public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished)` — `FORCED` 면 **곧바로**(Mono 를 만들기 전에) `IllegalArgumentException` 을 던진다.
    - `public Mono<SyncRun> startForced(ForceAllowance allowance, Consumer<SyncRun> onFinished)` — 트리거 `FORCED`.
  - `RebuildUseCase`:
    - `public Mono<SyncRun> start(Consumer<SyncRun> onFinished)` — 트리거 `REBUILD`.
    - `public Mono<SyncRun> startForced(ForceAllowance allowance, Consumer<SyncRun> onFinished)` — 트리거 `FORCED_REBUILD`.
    - `start(boolean, …)` 는 없앤다.
  - `LedgerAlignment.align(DirectorySnapshot, Set<RelationTuple>, DeletionGuard guard, AuditChanges)` 와 `align(…, DeletionGuard guard, AuditChanges, boolean 쓴_줄도_남긴다)` — `boolean force` 대신 이번 회차의 가드를 받는다.
    - 생성자에서 `DeletionGuard` 를 뺀다.
    - `DeleteCheck.NONE` 은 이 클래스에서 더 쓰지 않는다.
  - `AdminSyncController.full(boolean force, Integer maxDeletes, Authentication)`, `rebuild(String mode, boolean force, Integer maxDeletes, Authentication)` — `maxDeletes` 는 `@RequestParam(required = false) Integer`.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `DeletionGuardTest`: 세 꼴(비율만·상한만·둘 다)의 메시지가 F5 문장으로 끝난다(`endsWith`).
  - `ForceAllowanceTest`: 0·-1 은 `IllegalArgumentException`. 1 은 된다. `note()` 는 `"강제 실행 — 지울 줄 허용 12500건"`(12500 으로).
  - 강제 가드(`DeletionGuardTest`에 추가):
    - `강제_가드는_비율을_보지_않는다`: 기준 100, 지울 90, N=90 → 진행.
    - `강제_가드는_N_을_넘으면_상한_문구로_멈춘다`: 지울 91, N=90 → `aborted`, 메시지에 `상한 90건을` 이 있다.
    - `강제_가드는_비활성_회수를_떼어_본다`: 전체 91 > N=90 이고 비활성 회수 10 ≤ 90 → `partial()`.
  - `FullSyncUseCaseTest`:
    - 기존 `동기화한다(SyncTrigger.FORCED)` 다섯 곳을 새 도우미 `강제로_동기화한다(int maxDeletes)` 로 옮긴다. N 은 그 시험의 지울 줄 수 이상이다.
    - 535 행의 시험 이름은 "허용 수 안이면 훑어 맞추기도 지운다"로 바꾼다.
    - 새 시험:
      - `강제_동기화가_허용_수를_넘으면_멈추고_내역이_있다`: ABORTED, 메시지가 `강제 실행 — 지울 줄 허용 N건 / 삭제 대상` 으로 시작, `guardHold` 있음.
      - `강제_동기화는_설정_가드가_꺼져_있어도_허용_수를_본다`(Review Focus 3): `new DeletionGuard(new DeletionGuardPolicy(false, 0.3, 10, 5000))` 로 만든 유스케이스 + `startForced(new ForceAllowance(1), …)` 에 지울 줄 2 → ABORTED.
      - `기준선_없는_강제_동기화는_비율을_넘어도_허용_수_안이면_지운다`(Review Focus 2): 포인터 없음, 장부 찌꺼기가 훑은 줄의 50% 이상이고 N 이상이 아님 → 지운다.
      - `강제_장부_맞추기의_결론은_허용_수가_사정보다_앞이다`(Review Focus 5): 메시지가 `강제 실행 — 지울 줄 허용 N건 / 기준선 없음(` 으로 시작.
      - `강제_동기화가_변경_없음이어도_허용_수를_적는다`: `강제 실행 — 지울 줄 허용 N건 / 변경 없음`.
      - `start_에_FORCED_를_주면_거절한다`: `assertThatThrownBy(() -> useCase.start(SyncTrigger.FORCED, r -> {}))` 가 `IllegalArgumentException`.
  - `RebuildUseCaseTest`:
    - `start(false, …)` → `start(…)`. `start(true, …)` → `startForced(new ForceAllowance(N), …)`.
    - 331 행의 "가드를 건너뛰고 지운다"는 허용 수 안이면 지운다는 시험으로 바꾼다.
    - 새 `강제_재적재가_허용_수를_넘으면_장부의_후보를_지우지_않는다`: ABORTED, 장부의 찌꺼기가 그대로, `guardHold` 있음.
  - `LdapSyncAuditTest`: 110·271·323·446 행을 새 입구로 옮긴다. 허용 수는 넉넉히 준다.
  - `AdminSyncControllerTest`:
    - 기존 `force=true` 요청들을 `force=true&maxDeletes=12500` 으로 바꾼다. 모의는 `fullSync.startForced(eq(new ForceAllowance(12500)), any())`·`rebuild.startForced(…)`·`rebuild.start(any())` 로 바꾼다.
    - 403 시험은 `maxDeletes` 를 함께 주어도, 빼도 403 이다(범위가 먼저).
    - 새 400 시험 다섯(`$.detail` 은 Global Constraints 문구):
      - `full?force=true` → 첫 문구
      - `full?force=true&maxDeletes=0` → 첫 문구
      - `full?maxDeletes=10` → 둘째 문구
      - `rebuild?force=true` → 첫 문구
      - `rebuild?maxDeletes=10` → 둘째 문구
    - 각 400 은 유스케이스를 부르지 않는다(`verifyNoInteractions`).
    - `full?force=true&maxDeletes=abc` 는 400 이다(문구는 단언하지 않는다).
  - `LdapSyncEndToEndTest`(Order 13): `/admin/sync/rebuild?force=true&maxDeletes=1000` 으로 바꾸고, `$.message` 가 `강제 실행 — 지울 줄 허용 1000건` 으로 시작함을 더한다.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.guard.*'` → 컴파일 실패(`ForceAllowance` 없음).
- [ ] **Step 3: 구현한다.**
  - 유스케이스는 허용 수(없으면 null)로 이번 회차의 가드를 하나 고른다 — `allowance == null ? guard : DeletionGuard.forced(allowance)`. 그 가드를 비교 경로와 `alignment.align` 에 함께 쓴다.
    - 비교 경로의 `if (trigger != SyncTrigger.FORCED)` 는 없앤다. 가드는 늘 본다.
  - 허용 수가 있으면 "반납 뒤 할 일"의 결론에 `withNote(allowance.note())` 를 가장 바깥에서 붙인다. 장부 맞추기의 사정 문구보다 나중에 붙여 앞에 오게 한다.
  - 컨트롤러는 도우미 하나(`허용_수를_정한다(boolean force, Integer maxDeletes, Authentication caller)` → `ForceAllowance` 또는 null)로 403·400 을 정한다.
    - 403 은 기존 `요구한다_force` 다.
    - 400 은 `ResponseStatusException(HttpStatus.BAD_REQUEST, 문구)` 다.
    - 재적재는 그 뒤에 `mode` 를 본다.
    - 요청 로그에 `maxDeletes` 를 함께 적는다.
  - Javadoc 의 "force 면 가드를 건너뛴다" 문장(`DeletionGuard` 클래스, `FullSyncUseCase` 클래스·`훑어_맞춘다`, `LedgerAlignment` 클래스, `RebuildUseCase.start`, 컨트롤러 두 메서드, `SyncTrigger` 의 `FORCED`·`FORCED_REBUILD` 주석)을 바꾼다. 새 문장: 허용 수 N 을 상한으로 같은 가드를 본다(설계 2026-11-20 F1·F2).
- [ ] **Step 4: 통과를 확인한다.** 한 번에 하나씩 돌린다.
  - `./gradlew :core:test` PASS.
  - `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.AdminSyncControllerTest'` PASS.
  - `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.LdapSyncEndToEndTest'` PASS(Docker 필요). 돌릴 수 없으면 보고에 그렇게 적는다.
- [ ] **Step 5: 커밋한다** — `feat: force=true 는 maxDeletes=N(1 이상)과 함께만 — 강제 회차는 비율 없이 상한 N 의 같은 가드로 보고 결론 앞에 허용 수를 적는다 (가드 멈춤 내역)`

### Task 4: 저장과 응답 — DynamoDB 속성, `guardHold` JSON, 목록에서 빼기

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`(필요하면 `Attrs.java` 에 M·L 도우미)
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/GuardHoldResponse.java`
- Modify: `admin-api/src/main/java/dev/starryeye/organization/admin/SyncRunResponse.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`(목록), `app-scim/src/main/java/dev/starryeye/organization/scim/app/AdminSyncController.java`(목록, 101행)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`, `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java`

**Interfaces:**
- Consumes: Task 1 의 `GuardHold`, `GuardHold.Sample`, `SyncRun.guardHold()`.
- Produces:
  - `public record GuardHoldResponse(Lines lines, Users users, Groups groups)`. 안쪽 레코드는 다음과 같다.
    - `Lines(int total, int directMember, int child, int other)`
    - `Users(Sample gone, Sample inactive, Sample losingMembership)`
    - `Groups(Sample gone, Sample losingParent)`
    - `Sample(int count, List<String> sample)`
  - `public static GuardHoldResponse from(GuardHold hold)`.
  - `SyncRunResponse` 의 마지막 구성 요소 `@JsonInclude(JsonInclude.Include.NON_NULL) GuardHoldResponse guardHold`.
    - `from(SyncRun)` 은 싣는다(null 이면 null).
    - 새 `public static SyncRunResponse listed(SyncRun run)` 은 늘 null 이다.
  - 두 앱의 `GET /admin/sync/runs` 는 `SyncRunResponse::listed` 를 쓴다. 단건·202 는 `from` 그대로다.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `DynamoDbSyncRunRepositoryTest`:
    - `멈춤_내역을_싣고_읽는다`: 갈래 하나는 빈 예 목록이다. `finish` 뒤 `findById` 의 `guardHold()` 가 넣은 값과 `equals`.
    - `멈춤_내역이_없으면_null_이다`: 기존 완료 시험처럼 끝낸 기록의 `guardHold()` 가 null.
    - `목록도_내역째_읽는다`: `findRecent` 로 읽은 기록의 `guardHold()` 가 넣은 값이다(설계 §3.1 판단 — 응답에서만 뺀다).
  - `AdminSyncControllerTest`:
    - `실행_기록_하나에는_멈춤_내역이_실린다`: 모의 `runs.findById` 가 내역 있는 기록을 준다. 단언:
      - `$.guardHold.lines.total` 이 합이다.
      - `$.guardHold.users.gone.count`·`$.guardHold.users.gone.sample[0]` 이 있다.
      - `$.guardHold.groups.losingParent.count` 가 있다.
    - `실행_기록_목록에는_멈춤_내역이_없다`: 모의 `runs.findRecent` 가 같은 기록을 준다. `$[0].guardHold` 가 `doesNotExist()`.
    - `내역이_없는_기록에는_칸이_없다`.
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbSyncRunRepositoryTest'` → FAIL(속성을 쓰지 않음). `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.AdminSyncControllerTest'` → 컴파일 실패 또는 FAIL.
- [ ] **Step 3: 구현한다.** 속성 이름은 Global Constraints 그대로다. 저장소의 Query 는 바꾸지 않는다(`ProjectionExpression` 없음). 읽을 때 속성이 없으면 null 이다.
- [ ] **Step 4: 통과를 확인한다.** 한 번에 하나씩 돌린다.
  - Step 2 의 두 명령 PASS.
  - `./gradlew :app-scim:test --tests '*AdminSyncController*'` PASS.
- [ ] **Step 5: 커밋한다** — `feat: 멈춤 내역을 실행 기록 아이템(guardHold M)과 단건 응답에 싣고 두 앱의 목록 응답에서는 뺀다 (가드 멈춤 내역)`

### Task 5: 락 지표 — app-ldap `sync.lock.*`, 두 앱 카운터 기동 때 0, 경합은 조건 실패만

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`(획득 관찰, 약 157행)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncMetrics.java`, `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java`(`syncJobs`)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java`(생성자)
- Test:
  - `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java`
  - `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncMetricsTest.java`
  - `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncLockMetricsWiringTest.java`(새)
  - `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java`

**Interfaces:**
- Produces:
  - `public class SyncMetrics implements LockObserver`.
    - 생성자 `public SyncMetrics(MeterRegistry registry)` 가 `sync.lock.contended`·`sync.lock.lease_lost` 를 0 으로 등록한다.
    - `record(SyncRun)` 은 그대로다.
  - `UseCaseConfig.syncJobs(SyncRunRepository, MutationLock, DynamoDbProperties, SyncProperties, AuditRecorder, Clock, SyncMetrics)` — `LockObserver.NOOP` 대신 `SyncMetrics` 를 넘긴다.

- [ ] **Step 1: 실패하는 시험을 쓴다.**
  - `SyncJobsTest`(기록하는 관찰자를 시험 안에 둔다):
    - `락이_잡혀_있으면_경합으로_알린다`: `lock.failAcquire = true` → 관찰자가 `contended=true` 를 한 번 받는다.
    - `락_획득이_다른_오류로_끝나면_경합이_아니다`: `acquireOnce` 가 `Mono.error(new IllegalStateException("저장소 오류"))` 를 주는 가짜 → `contended=false` 를 한 번 받는다.
  - `SyncMetricsTest`:
    - `락_카운터는_기동_때_0으로_있다`: `registry.find("sync.lock.contended").counter().count()==0`, `lease_lost` 도 같다.
    - `경합한_획득은_대기와_경합을_남긴다`: `acquireFinished(Duration.ofMillis(5), true)` → timer `count()==1`, contended 1.
    - `리스_상실을_센다`: `leaseLost("작업 도중 리스 상실")` → 1.
  - `SyncLockMetricsWiringTest`:
    - `new UseCaseConfig().syncJobs(…, metrics)` 로 만든 `SyncJobs` 에 `failAcquire` 인 `FakeMutationLock` 을 준다.
    - `startLockedThen(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC, AuditKind.SYNC, …)` 가 `LockUnavailableException` 이다.
    - 그 뒤 `sync.lock.contended` 가 1 이다.
    - 나머지 인자(`DynamoDbProperties`·`SyncProperties` 기본값, `FakeSyncRunRepository`, `FakeAuditLog` 로 만든 `AuditRecorder`, 고정 `Clock`)는 core testFixtures 의 가짜로 채운다.
  - `ScimSyncMetricsTest`: `락_카운터는_기동_때_0으로_있다`(`scim.lock.contended`·`scim.lock.lease_lost`).
- [ ] **Step 2: 실패를 확인한다** — `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.SyncJobsTest'` → 다른 오류 시험 FAIL(지금은 `true`).
- [ ] **Step 3: 구현한다.**
  - `SyncJobs`: `doOnError` 의 경합 값을 `error instanceof LockUnavailableException` 으로 바꾼다.
  - `SyncMetrics`: `@RequiredArgsConstructor` 를 명시 생성자로 바꾼다.
    - `acquireFinished` 는 `registry.timer("sync.lock.wait").record(waited)` 를 하고, 경합이면 contended 를 1 올린다.
    - `leaseLost` 는 `log.warn("작업 락 리스를 잃었다: {}", reason)` 와 카운터 1 이다.
    - Javadoc 에 표(`ScimSyncMetrics` 와 같은 꼴)와 "contended 는 둘 이상 띄운 배포에서 매시 (인스턴스 수 − 1)만큼 오르는 것이 정상"을 적는다.
  - `ScimSyncMetrics` 생성자에 두 카운터의 0 등록을 더한다. 주석은 회수 카운터와 같은 까닭을 적는다.
  - `LockObserver` 의 `@param` 주석에 `sync.lock.*` 도 적는다.
- [ ] **Step 4: 통과를 확인한다.** 한 번에 하나씩 돌린다.
  - `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.SyncJobsTest'` PASS.
  - `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.SyncMetricsTest' --tests 'dev.starryeye.organization.ldap.app.SyncLockMetricsWiringTest'` PASS.
  - `./gradlew :app-scim:test --tests 'dev.starryeye.organization.scim.app.ScimSyncMetricsTest'` PASS.
- [ ] **Step 5: 커밋한다** — `feat: app-ldap 락 지표 sync.lock.wait·contended·lease_lost 를 결선하고, 두 앱 락 카운터를 기동 때 0 으로 만들며, SyncJobs 는 조건 실패만 경합으로 센다 (가드 멈춤 내역)`

### Task 6: 문서 — README 와 온보딩을 현재 동작으로

**Files:**
- Modify: `README.md`
- Modify: `docs/onboarding/` 의 `01`·`02`·`03`·`05`·`07`·`08`·`09`·`10`·`11`·`12`·`13`·`14`·`15`, 온보딩 `README.md`

**Interfaces:**
- Consumes: Task 1~5 의 동작(설계 §3), 이름(Global Constraints), 마지막 코드 커밋 해시(`git log -1 --format=%h` — 이 과제의 첫 문서 커밋 전 HEAD).

- [ ] **Step 1: 고칠 곳을 센다.** 아래 셋의 결과를 보고에 남긴다.
  - `grep -rn "force=true" README.md docs/onboarding`
  - `grep -rn "LockObserver.NOOP\|락 지표" docs/onboarding`
  - `grep -rn "재요청하세요" README.md docs/onboarding`
- [ ] **Step 2: 고친다.** 규칙은 한 가지다 — **요청으로서의 `force=true` 는 모두 `force=true&maxDeletes=N` 이다.** N 은 내역의 `lines.total` 에 작은 여유를 둔 줄 수다. 문서별로 반드시 담을 것:
  - **09 관리 API:**
    - §1 표의 `full`·`rebuild` 행: `maxDeletes` 와 400.
    - §9.2: F3 의 표·400 문구 둘·검사 순서, 숫자가 아닌 값은 스프링 400 이 먼저.
    - §9.4: `guardHold` JSON 모양(설계 §3.1)과 칸의 뜻, 단건에만 싣고 목록에는 없다.
  - **12 장애와 복구 §4.4(가드 발동 런북):**
    1. 실행 기록 단건을 연다.
    2. 갈래로 원인을 가른다 — 사라진 직원·조직이 크면 검색 베이스·필터 실수 또는 큰 퇴사, 비활성이 크면 정책·시계, 소속을 잃는 활성 직원이 크면 개편.
    3. 예의 id 를 직원·조직 상세로 확인한다.
    4. `maxDeletes` 에 작은 여유를 두고 건다.
    5. 다시 멈추면(허용 수 초과) 내역을 다시 본다.
    - 줄 수와 엔트리 수가 다르다는 것도 적는다. 같은 절의 다른 `force=true` 도 바꾼다.
  - **05 LDAP 동기화(삭제 가드 절):** 강제 회차의 규칙(F2 — 비율 없이 상한 N, 비활성 떼어 보기, 설정 가드를 꺼도 본다), 결론 앞 허용 수 문구, 멈춤 내역.
  - **07 재적재:** `force=true&maxDeletes=N`, 강제 재적재도 N 을 넘으면 장부의 후보를 지우지 않는다.
  - **11 운영:**
    - 지표 표에 `sync.lock.wait`·`sync.lock.contended`·`sync.lock.lease_lost`.
    - `contended` 는 매시 (인스턴스 수 − 1)만큼 오르는 것이 정상이다.
    - 경보는 `increase(sync_lock_lease_lost_total[1h]) > 0`.
    - SCIM 의 `scim.lock.contended`·`lease_lost` 도 기동 때 0 이다.
  - **README:** `force=true` 예를 모두 바꾼다. 지표 목록과 경보 예에 위 `lease_lost` 식을 더한다.
  - **13 한계:**
    - §3.9 와 §6.8 을 "고친 것" 표로 옮긴다. 근거는 설계 2026-11-20 이다.
    - 끝의 상태 표(약 520·534행)를 고친다.
    - 설계 §6 가운데 운영자가 알아야 할 것을 새 항목이나 기존 항목에 남긴다.
      - 예는 사전순 앞 10개다.
      - 롤업 영향은 세지 않는다.
      - 강제 회차가 예외로 끝나면 허용 수가 남지 않는다.
      - 멈춘 배포가 오래 가면 목록 읽기가 내역째 읽는다.
  - **15 결정 색인:** H1~H5·F1~F5·L1~L3 을 그 파일의 꼴대로 더한다.
  - **02 용어집:** `force` 와 가드 항목을 새 동작으로 고친다. 멈춤 내역 용어를 더한다.
  - **01·03·08·10·14·온보딩 README:** 같은 규칙으로 `force=true` 를 고친다.
  - **모든 고친 온보딩 파일:** 머리의 `기준 커밋:` 줄을 `기준 커밋: <마지막 코드 커밋> (guard-hold-detail, main 에 머지됨)` 으로 바꾼다.
- [ ] **Step 3: 확인한다.**
  - `grep -rn "force=true 로 재요청" README.md docs/onboarding` → 0줄.
  - `grep -rn "force=true" README.md docs/onboarding` 의 남은 줄은 모두 같은 줄이나 같은 명령에 `maxDeletes` 가 있다. 또는 400 을 설명하는 문장이다.
  - `grep -rn "LockObserver.NOOP" docs/onboarding` → "고친 것" 표의 문장뿐이다.
  - 문서 안 링크의 절 번호가 맞다(바꾼 제목이 있으면 그 앵커를 쓰는 곳을 고친다).
- [ ] **Step 4: 커밋한다** — `docs: 멈춤 내역·force=true&maxDeletes=N·app-ldap 락 지표를 README 와 온보딩에 반영하고, 13 §3.9·§6.8 을 고친 항목으로 옮긴다 (가드 멈춤 내역)`. 파일이 많으면 README+09+12 와 나머지로 두 번에 나눠도 된다.
