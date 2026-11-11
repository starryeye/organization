# 원천 없는 부여 줄 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 커밋에 닿은 조직 쪽 쓰기(조직 POST·PUT·PATCH, 지정 두기·없애기)는 커밋한 뒤의 상태가 요구하지 않는, 자기가 썼거나 결과를 모르는 부여 줄을 되지운다. 되지우지 못하고 끝난 요청은 카운터 `scim.writes.unsourced` 로 센다.

**Architecture:**
- core 에 새 추적기 `UnsourcedGrantTracker` 를 둔다(요청마다 하나). 이것이 두 가지를 쥔다.
  - "커밋한 뒤의 상태가 요구하는 줄": 저장하지 않는 커밋은 `있어야했던것`, 저장하는 커밋은 저장하지 못한 넣기의 줄.
  - "보냈을 수 있는 부여 줄": 본 쓰기의 묶음 알림과 취소에서 모은다.
- `IncrementalSyncUseCase` 의 조직 쪽 커밋 셋이 추적기로 되지울 줄을 정한다. 셋은 새 조직, 기존 조직, 지정이다. 공통 도우미 `원천_없는_줄을_되지운다` 를 부른다.
- `반영하고_커밋한다` 가 추적기에 묶음·끝남·취소를 알린다.
- 추적기는 관찰자 `UnsourcedGrantObserver` 를 부른다. app-scim 의 `ScimSyncMetrics` 가 이것을 구현해 카운터를 낸다.

**Tech Stack:** Java 17, Spring Boot 3.5(WebFlux), Reactor, Micrometer, JUnit 5·AssertJ. 새 의존성 없음.

**Spec:** `docs/superpowers/specs/2026-11-11-unsourced-grants-design.md` — 설계가 권위다. 이 계획과 부딪히면 설계를 따른다. 줄 번호는 `0496586` 기준이다.

## Global Constraints

- **코드 관례.**
  - 공개 타입은 영어 이름이다(`UnsourcedGrantObserver`, `UnsourcedGrantTracker`). 내부 메서드·변수는 한국어 이름이다.
  - 시험 메서드는 한국어 이름에 `@DisplayName` 한국어 문장을 단다. 본문은 `// given / when / then` 이고 단언은 AssertJ 다.
  - 주석 밀도와 말투는 둘레 코드를 따른다. 설계 절은 `(설계 2026-11-11 U4)` 처럼 단다.
- **Gradle.**
  - 한 번에 하나만 돌린다.
  - 구현 서브에이전트는 자기 과제 모듈의 시험만 돌린다(`./gradlew :core:test`, `./gradlew :app-scim:test`).
  - 전체 `cleanTest test` 와 `cleanScaleTest scaleTest` 는 컨트롤러가 머지 전에 돌린다.
- **git.**
  - `git stash` 를 쓰지 않는다. 바꾼 파일만 스테이징한다.
  - 커밋 메시지는 한국어 `feat:`/`fix:`/`test:`/`docs:` 한 문장에 ` (원천 없는 줄)` 을 붙인다.
  - 끝줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 이다.
- **원칙.**
  - 요청마다 전원을 읽지 않는다(실제 규모 10만 명). 이 슬라이드는 읽기를 하나도 늘리지 않는다.
  - OpenFGA Read 를 쓰지 않는다. 되지울 줄은 쓰기 결과와 그림으로 안다.
  - 운영 배포 전이라 이관하지 않는다.
- **값(정확히).**

  | 대상 | 값 |
  |---|---|
  | 카운터 | `scim.writes.unsourced`. 태그 없음, 기동 때 0. Prometheus 이름 `scim_writes_unsourced_total` |
  | 경보 예 | `sum(increase(scim_writes_unsourced_total[1h])) > 0` |
  | 한 요청에 더하는 수 | 많아야 1 |
- **로그 문구(그대로).** `{대상}` 은 아래 표의 값이다.

  | 경우 | 수준 | 문구 |
  |---|---|---|
  | 되지우기 결과에 실패가 담김 | WARN | `{대상} '{groupId}' 의 줄을 {N}개 지우지 못했다 — 재적재(mode=tuples)가 지운다` (N = 실패 수) |
  | 되지우기가 리스 상실이 아닌 오류로 끝남 | WARN | `{대상} '{groupId}' 의 줄 {N}개를 되지우지 못했다 — 재적재(mode=tuples)가 지운다` (N = 지울 줄 수, 예외를 붙인다) |
  | 커밋에 닿지 못하고 끝나 셈(U5 a) | WARN | `조직 '{groupId}' 의 쓰기가 커밋에 닿지 못하고 끝나({사유}) 원천 없는 줄 {N}개가 남았을 수 있다 — 같은 요청이 다시 와 성공했는지 보고, 아니면 재적재(mode=tuples)를 건다` |
  | 관찰자가 던짐 | WARN | `원천 없는 줄 지표를 올리지 못했다 — 요청의 결과는 그대로다` (예외를 붙인다) |

  | `{대상}` | 경로 |
  |---|---|
  | `저장하지 못한 새 조직` | 새 조직 POST(지금 문구와 같다) |
  | `넣기를 저장하지 못한 조직` | 조직 PUT·PATCH, 시험 전용 기존 조직 `upsertGroup` |
  | `지정을 저장하지 못한 조직` | 지정 두기·없애기(`groupId` 는 하위 조직) |

  - `{사유}` 는 오류면 `error.getClass().getSimpleName()`, 취소면 `리스 상실로 취소` 다.
  - 예외 메시지·`externalId`·줄 목록은 싣지 않는다. 오류의 메시지에는 키 값이 들어갈 수 있다.
  - (a) WARN 에는 예외를 붙이지 않는다. 원래 오류는 응답 경로가 남긴다.

## 계획의 판정 (설계가 열어 둔 것, §11·§2)

- **커밋 자리의 경계.**
  - "커밋 자리를 마쳤다" 는 조직 쪽 `Commit` 람다가 끝난 때다. 람다 안에서 저장, 이력, 회수 셈, 되지우기가 돈다.
  - 되지우기는 조직 저장 뒤, 뒤쪽 보류 목록 정리(`changeCutEdges(Set.of(), 뺄것)`) 전에 돈다.
  - 뒤쪽 정리가 실패해도 세지 않는다. 그때는 저장과 되지우기가 끝나 남은 부여 줄에 원천이 있다. 남은 보류 항목은 이미 쓰인 연결이라, 다음 보류 다시 보기가 뺀다.
  - 앞쪽 보류 쓰기(`changeCutEdges(새로_보류, Set.of())`)의 실패는 커밋 전이다 — U5 (a) 로 본다.
- **저장하는 커밋의 "요구하지 않는 줄" = 저장하지 못한 넣기의 줄.**
  - 저장하지 못한 넣기는 넣으려던 멤버에서 `reconcileGroupMembers` 가 저장한 멤버를 뺀 것이다. 그 멤버마다 `tupleFor(멤버, 조직)` 이다.
  - 근거: 쓴 넣기는 재조정이 저장한다. 원래 멤버, 빼지 못한 멤버, 상위 연결은 저장된 행이 요구한다.
  - 그래서 되지울 줄은 `(written ∪ uncertain) ∩ 저장하지_못한_넣기의_줄` 이다. 설계 U4 의 식과 같다.
  - 다른 점이 하나 있다. 비활성 직원 멤버의 결과를 모르는 지우기는 다시 지우지 않는다. 레코드가 있는 직원이라 하루 아카이빙이 지운다.
- **저장하지 않는 커밋**(새 조직의 실패, 지정의 실패·같은 상위)의 되지울 줄은 `(written ∪ uncertain) − 있어야했던것` 이다(U4).
- **결과에 실패가 없으면 되지울 줄이 없다.** 이때 커밋한 뒤의 상태는 요청한 그대로다. 그래서 성공한 새 조직 POST 의 상위 연결 `child(G,P)` 를 지우지 않는다.
- **저장하는 커밋은 저장이 성공한 직후 `추적.저장했다(…)` 를 부른다.** 지정의 저장은 넣기가 없어 `Set.of()` 를 넘긴다. 그 뒤의 U5 (a) 판단은 커밋한 뒤의 상태로 한다.
- **"보냈을 수 있는 부여 줄"(U6)은 본 쓰기 델타의 쓰기 줄에서만 고른다.** 줄마다 아래 셋 가운데 하나면 든다.
  - 묶음 알림의 `written` 에 있다.
  - 묶음 알림의 결과를 모르는 실패이면서 `delta.toWrite()` 에 있다.
  - 쓰기가 시작된 뒤 끝나기(값·오류) 전에 취소됐고, 아직 어느 묶음에도 알려 오지 않은 `delta.toWrite()` 의 줄이다.
  - 결과를 모르는 지우기는 들지 않는다. 되지울 줄은 결과를 모르는 지우기를 담는다(I11 그대로). 저장된 상태가 요구하지 않으면 다시 지워도 안전하다.
- **추적기는 본 쓰기에만 건다.** 본 쓰기는 `반영하고_커밋한다` 의 `writer.apply` 다.
  - 되지우기, 보류 다시 보기의 쓰기는 `기록::tuples` 만 받는다.
  - 직원 쪽 쓰기, 남은 회수, 조직 삭제는 `UnsourcedGrantTracker.없음()` 을 넘긴다. 이것은 세지도 남기지도 않는다.
- **취소의 모양.** `반영하고_커밋한다` 의 비지 않은 델타 갈래 끝에 `doOnError(추적::오류로_끝났다)` 와 `doOnCancel(추적::취소됐다)` 를 단다. 리스 상실은 `LeaseKeeper` 가 하던 일을 취소하므로 여기 닿는다.
- **끊긴 묶음의 시험.** `FakeTupleWriter` 에 `delayBeforeReport` 를 더한다. 반영은 곧바로 하고, 알림과 결과는 그만큼 늦춘다.
- **시험 전용 기존 조직 `upsertGroup` 도 같은 도우미를 건다.** 규칙이 하나가 된다(설계 §2 가 계획에 맡김).
- **되지우기 결과의 실패·오류(U5 b)도 추적기가 센다.** U5 (a) 와 합쳐 요청 하나에 한 번이다.

## Review Focus

시험이 따로 짚지 않으면 쓰는 사람을 가장 먼저 물 입력이다. 각 줄의 시험을 맡은 과제에 넣었다.

1. **상위가 먼저 새 조직을 멤버로 적어 둔 뒤 온 새 조직 POST 의 일부 실패.** 상위 연결 `child(G,P)` 도 되지운다(Task 2 #13).
2. **하위 조직을 넣는 PATCH 의 결과를 모름.** `child(S,G)` 를 지우고 멤버 행을 저장하지 않는다(Task 2 #7).
3. **한 PATCH 안에 결과를 모르는 빼기와 넣기.** 넣기만 되지우고 빼지 못한 멤버의 줄은 다시 지우지 않는다(Task 2 #6).
4. **되지우는 도중 리스 상실.** 한 번만 센다(Task 3 #11).
5. **확정 실패만 알린 뒤 취소.** 세지 않는다(Task 3 #19).

---

### Task 1: 관찰자와 추적기

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/UnsourcedGrantObserver.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/UnsourcedGrantTracker.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/UnsourcedGrantTrackerTest.java`

**Interfaces:**
- Produces:
  - `public interface UnsourcedGrantObserver`
    - `void leftUnsourced();`
    - `UnsourcedGrantObserver NOOP = () -> { };`
    - 자바독은 `RevocationObserver` 의 모양을 따른다. core 가 Micrometer 를 모르게 하는 콜백이고, 요청 하나에 많아야 한 번 부른다.
  - `@Slf4j final class UnsourcedGrantTracker`(package-private). 모든 인스턴스 메서드는 `synchronized` 다 — 묶음 알림과 취소가 다른 스레드에서 온다.

    | 메서드 | 뜻 |
    |---|---|
    | `static UnsourcedGrantTracker 조직(String groupId, UnsourcedGrantObserver observer)` | 조직 쪽 쓰기 한 요청의 추적기 |
    | `static UnsourcedGrantTracker 없음()` | 세지도 남기지도 않는다. `묶음마다` 는 `기록` 을 그대로 돌려준다 |
    | `void 요구한다(Set<RelationTuple> 있어야했던것)` | 저장하지 않는 커밋의 기준. `diffAndApply` 가 부른다 |
    | `void 저장했다(Set<RelationTuple> 저장하지_못한_넣기의_줄)` | 저장하는 커밋이 저장 성공 직후 부른다. 이 뒤의 기준은 이 집합이다 |
    | `Set<RelationTuple> 되지울것(TupleWriteResult result)` | `!result.hasFailure()` 면 빈 집합. 아니면 `요구하지_않는(written ∪ uncertain)` |
    | `Consumer<TupleWriteResult> 묶음마다(TupleDelta delta, Consumer<TupleWriteResult> 기록)` | 본 쓰기가 시작됐다고 적고 델타를 쥔다. 돌려준 것은 `기록` 에 먼저 넘기고 모은다 |
    | `void 쓰기가_끝났다()` | 본 쓰기가 값이나 오류로 끝났다 |
    | `void 커밋을_마쳤다()` | 조직 쪽 `Commit` 이 끝났다. 이 뒤의 끝남·취소는 세지 않는다 |
    | `void 오류로_끝났다(Throwable error)` | U5 (a). 사유는 단순 클래스 이름 |
    | `void 취소됐다()` | U5 (a). 사유는 `리스 상실로 취소` |
    | `void 되지우기가_실패했다()` | U5 (b). 세기만 하고 WARN 은 부르는 쪽이 남긴다 |

  - 셈의 알고리즘. 이것은 시그니처가 정하지 않는다.
    ```
    요구하지_않는(줄들) = 저장했다 ? 줄들 ∩ 저장하지_못한_넣기의_줄 : 줄들 − 있어야했던것
    보냈을_수_있는(취소) = 알린_written
                         ∪ (알린_uncertain ∩ delta.toWrite())
                         ∪ (취소 && 쓰기_시작 && !쓰기가_끝났다 ? delta.toWrite() − 알린_모든_줄 : ∅)
      // 알린_모든_줄 = 알린 written ∪ deleted ∪ 실패의 줄
    끝남(사유, 취소):
      커밋을_마쳤다 면 끝
      남은 = 요구하지_않는(보냈을_수_있는(취소))
      남은 이 비면 끝
      한 번만 → observer.leftUnsourced() 와 (a) WARN(남은.size())
    되지우기가_실패했다: 한 번만 → observer.leftUnsourced()
    ```
    "한 번만" 은 요청마다 플래그 하나를 공유한다(a·b 합쳐 1). `observer` 가 던지면 관찰자 WARN 만 남기고 삼킨다.

- [ ] **Step 1: 실패하는 시험을 쓴다** — `UnsourcedGrantTrackerTest`.
  - 줄 `a`·`b`·`c`·`e` 는 아무 `RelationTuple.directMember(...)` 다.
  - 관찰자는 `AtomicInteger 셈` 을 올리는 람다다.
  - 결과는 `new TupleWriteResult(written, deleted, failures)` 로 만든다. 실패는 확정 `new TupleFailure(t, "x")`, 불확실 `TupleFailure.uncertain(t, "x")` 다.

  | # | 이름 | 준비와 부름 | 단언 |
  |---|---|---|---|
  | 1 | `실패가_없으면_되지울_줄이_없다` | 요구한다(∅); 되지울것(written {a}) | 비어 있다 |
  | 2 | `저장하지_않는_커밋은_있어야했던것을_뺀_쓴_줄과_결과를_모르는_줄을_되지운다` | 요구한다({b}); 되지울것(written {a}, 실패 [불확실 b, 불확실 c, 확정 e]) | `{a, c}` |
  | 3 | `저장한_뒤에는_저장하지_못한_넣기의_줄만_되지운다` | 요구한다(∅); 저장했다({c}); 되지울것(written {a}, 실패 [불확실 c, 불확실 e]) | `{c}` |
  | 4 | `오류로_끝나면_요구하지_않는_보낸_줄이_있을_때_한_번_센다` | 요구한다({a}); 묶음마다(쓰기 {a,b}, 기록)(written {a,b}); 쓰기가_끝났다; 오류로_끝났다 두 번 | 셈 1, `기록` 이 그 묶음을 받았다 |
  | 5 | `요구하는_줄만_보냈으면_세지_않는다` | 요구한다({a}); 묶음 written {a}; 쓰기가_끝났다; 오류로_끝났다 | 셈 0 |
  | 6 | `확정_실패만_알려_왔으면_세지_않는다` | 요구한다(∅); 묶음 실패 [확정 a]; 쓰기가_끝났다; 오류로_끝났다 | 셈 0 |
  | 7 | `쓰는_도중_취소되면_알려_오지_않은_쓰기_줄도_센다` | 요구한다(∅); 묶음마다(쓰기 {a}, 기록) 만 만든다(알림 없음); 취소됐다 | 셈 1 |
  | 8 | `쓰기가_끝난_뒤의_오류는_알려_온_줄만_본다` | 요구한다(∅); 묶음마다(쓰기 {a,b}) 에 실패 [확정 a] 만 알림; 쓰기가_끝났다; 오류로_끝났다 | 셈 0 |
  | 9 | `커밋을_마친_뒤에는_세지_않는다` | 요구한다(∅); 묶음 written {a}; 쓰기가_끝났다; 커밋을_마쳤다; 오류로_끝났다; 취소됐다 | 셈 0 |
  | 10 | `되지우기_실패와_커밋_전_끝남은_합쳐_한_번이다` | 요구한다(∅); 묶음 written {a}; 쓰기가_끝났다; 되지우기가_실패했다; 오류로_끝났다 | 셈 1 |
  | 11 | `결과를_모르는_지우기는_보냈을_수_있는_부여_줄이_아니다` | 요구한다(∅); 묶음마다(지우기 {a}) 에 실패 [불확실 a]; 쓰기가_끝났다; 오류로_끝났다 | 셈 0 |
  | 12 | `관찰자가_던져도_삼킨다` | 관찰자가 `IllegalStateException`; 요구한다(∅); 묶음 written {a}; 오류로_끝났다 | 던지지 않는다 |

- [ ] **Step 2: 돌려 실패를 본다.** 실행: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.UnsourcedGrantTrackerTest'`. 기대: 컴파일 실패(타입 없음).
- [ ] **Step 3: 두 타입을 만든다.**
  - 자바독은 둘 다 짧게 쓴다. 설계 U4·U5·U6 과 위 판정을 가리킨다.
  - `없음()` 의 판정은 `groupId == null` 로 한다.
  - `요구한다`·`저장했다` 는 받은 집합을 복사해 쥔다.
- [ ] **Step 4: 다시 돌려 통과를 본다.** 같은 명령. 기대: 12개 PASS.
- [ ] **Step 5: 커밋한다.** 메시지: `feat: 원천 없는 줄 관찰자와 요청마다의 추적기를 더한다 (원천 없는 줄)`

---

### Task 2: 조직 쪽 커밋의 되지우기 (U1–U4)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
  - 필드·생성자 :175-212
  - `upsertGroupInternal` :474-507
  - `새_조직이_쓴_줄을_되지운다` :509-538
  - `changeGroupInternal` :607-655
  - `지정을_바꾼다` :919-946
  - `diffAndApply` :1258-1365
  - 클래스·메서드 자바독
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleChecker.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedUndoTest.java`

**Interfaces:**
- Consumes (Task 1): `UnsourcedGrantTracker.조직`·`없음`·`요구한다`·`저장했다`·`되지울것`, `UnsourcedGrantObserver.NOOP`.
- Produces:
  - 필드 `private final UnsourcedGrantObserver unsourcedGrantObserver;` 를 `revocationObserver` 바로 뒤에 둔다. Lombok 이 13인자 생성자를 만든다. 마지막 인자가 `UnsourcedGrantObserver` 다.
  - 명시 12인자 생성자 `(…, Duration lockTtl, RevocationObserver revocationObserver)` 는 `UnsourcedGrantObserver.NOOP` 으로 13인자를 부른다.
    - 지금 38곳이 12인자 이하를 쓴다. 그대로 컴파일돼야 한다.
    - 다른 생성자 자바독의 "운영 결선은 12인자" 를 "13인자" 로 고친다.
  - `diffAndApply` 두 오버로드 모두 마지막 인자로 `UnsourcedGrantTracker 추적` 을 받는다.
    - `있어야했던것` 을 셈한 직후 `추적.요구한다(있어야했던것)` 를 부른다.
    - 직원 쪽(`upsertUserInternal`)과 `남은_회수만_지운다` 는 `UnsourcedGrantTracker.없음()` 을 넘긴다.
  - `private Mono<Void> 원천_없는_줄을_되지운다(String groupId, String 대상, TupleWriteResult result, UnsourcedGrantTracker 추적, LockLease lease, AuditChanges 기록)`
    - `새_조직이_쓴_줄을_되지운다` 를 바꾼 것이다.
    - 지울 줄은 `추적.되지울것(result)` 이다.
    - 나머지 규칙은 지금 그대로다. 비면 부르지 않는다. 리스를 확인한다(`"쓰기 직전 리스 재확인 실패"`). `LockUnavailableException` 은 내보내고 그 밖의 오류는 WARN 을 남기고 삼킨다.
    - WARN 두 줄은 Global Constraints 의 `{대상}` 문구를 쓴다.
  - `private static Set<RelationTuple> 멤버의_줄(Set<MemberRef> members, String groupId)` — 멤버마다 `tupleFor` 를 쓴다.
  - `FakeTupleChecker`:
    - `public FakeTupleChecker(Set<RelationTuple> 장부)` 를 더한다. `allowed` 가 그 집합 자체다.
    - 인자 없는 생성자는 새 `LinkedHashSet` 으로 그것을 부른다.
    - 자바독: 가짜 writer 의 장부(`FakeTupleWriter.stored`)를 넘기면 Check 가 그 장부를 본다. 재시도가 앞 요청이 남긴 줄을 보는 시험에 쓴다.

- [ ] **Step 1: `FakeTupleChecker` 에 장부 생성자를 더한다.** 위 Interfaces 대로다. 기존 시험은 바뀌지 않는다.
- [ ] **Step 2: 실패하는 시험을 쓴다** — `IncrementalSyncUnsourcedUndoTest`.
  - 준비(`@BeforeEach`):
    - `state = new FakeStateRepository()`, `writer = new FakeTupleWriter()`, `checker = new FakeTupleChecker(writer.stored)`, `lock = new FakeMutationLock()`.
    - `useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, DriftObserver.NOOP, LockObserver.NOOP)`.
    - 활성 직원 `u-1`·`u-2`·`u-3` 을 `state.users` 에 넣는다(`new DirectoryUser(id, null, id + "@corp.com", id, null, true)`).
    - 조직 `G`(`"grp-g"`, `"조직"`, 멤버 `u-1`)를 넣고 `writer.stored` 에 `dm("u-1","G")` 를 넣는다.
    - 조직 `A`·`B`·`C`·`D`·`S` 는 멤버 없이 넣는다.
    - `state.assignedParents.put("C","A")` 를 하고 `writer.stored` 에 `child("C","A")` 를 넣는다.
  - 도우미:
    - `dm(user, group)` 는 `RelationTuple.directMember`, `child(c, p)` 는 `RelationTuple.child` 다.
    - `지우기만인_델타()` 는 `writer.appliedDeltas` 의 마지막의 `toWrite()` 가 비었는가다.
    - "되지우기 직전 리스 상실" 은 `IncrementalSyncNewGroupUndoTest` 의 수법을 쓴다. 주입 술어 안에서 `lock.failRenewAfterAttempt = lock.renewAttempted.get() + 1` 을 둔다. 그러면 커밋 직전 확인은 지나고 되지우기 직전 확인이 실패한다.
  - PATCH 는 `GroupChange.delta().adding(Set.of(...))` 로 만든다(빼기 `.removingId(id)`, 이름 `.renamed(이름)`). PUT 은 `GroupChange.replacement("grp-g", "조직", 멤버들)` 이다.

  | # | 이름 | given | when | then |
  |---|---|---|---|---|
  | 1 | `기존_조직_PATCH_의_넣기가_결과를_모르면_멤버를_저장하지_않고_줄을_지운다` | `lostResponseFor(dm(u-2,G))` | PATCH u-2 넣기 | 부분 반영. `G` 멤버 `{u-1}`. 장부에 `dm(u-2,G)` 없고 `dm(u-1,G)` 있다 |
  | 2 | `되지운_뒤_같은_PATCH_를_다시_보내면_멤버가_저장되고_줄이_있다` | #1 뒤 주입을 끈다 | 같은 PATCH | 전부 반영. 멤버 `{u-1,u-2}`. 장부에 `dm(u-2,G)` |
  | 3 | `되지운_뒤_같은_PUT_을_다시_보내면_멤버가_저장되고_줄이_있다` | `lostResponseFor(dm(u-2,G))` | PUT `{u-1,u-2}` 를 두 번(둘째는 주입을 끄고) | 첫째 뒤: 멤버 `{u-1}`, 장부에 `dm(u-2,G)` 없음. 둘째 뒤: 멤버 `{u-1,u-2}`, 장부에 있음 |
  | 4 | `확정_실패한_넣기는_되지울_것이_없어_더_쓰지_않는다` | `failFor(dm(u-2,G))` | PATCH u-2 넣기 | `writer.appliedDeltas` 1개 |
  | 5 | `원래_있던_멤버의_줄은_지우지_않는다` | 장부에서 `dm(u-1,G)` 를 뺀다(어긋남). 본 쓰기의 넣기 둘 다 결과를 모르게 한다(되지우기의 지우기는 성공) | PATCH `{u-1,u-2}` 넣기 | 마지막 델타는 지우기 `{dm(u-2,G)}` 뿐이다. 장부에 `dm(u-1,G)` 있다. 멤버 `{u-1}` |
  | 6 | `결과를_모르는_빼기와_넣기가_함께면_넣기만_되지운다` | 본 쓰기의 `dm(u-1,G)` 지우기와 `dm(u-2,G)` 쓰기를 결과를 모르게 한다 | PATCH `removingId("u-1")` + u-2 넣기 | 마지막 델타는 지우기 `{dm(u-2,G)}` 뿐이다. 멤버 `{u-1}`(빼기를 모름 → 남김) |
  | 7 | `하위_조직을_넣는_PATCH_가_결과를_모르면_멤버_행을_저장하지_않고_연결_줄을_지운다` | `lostResponseFor(child(S,G))` | PATCH `MemberRef.group("S")` 넣기 | 멤버에 S 없음. 장부에 `child(S,G)` 없음 |
  | 8 | `되지우기_직전_리스를_잃으면_지우지_않고_리스_상실로_끝난다_조직은_이미_저장돼_있다` | `dm(u-2,G)` 결과를 모르게 하며 리스 상실 수법 | PATCH `renamed("새 이름")` + u-2 넣기 | `LockUnavailableException`. `G` 이름 `"새 이름"`. 장부에 `dm(u-2,G)` 있음. 델타 1개 |
  | 9 | `새_연결이_결과를_모르면_지정은_그대로이고_그_줄을_지운다_그_뒤_다른_상위를_걸면_그것만_남는다` | `lostResponseFor(child(C,B))` | `assignParent("C","B")`, 주입을 끄고 `assignParent("C","D")` | 첫째: 부분 반영, 지정 `C→A`, 장부에 `child(C,B)`·`child(C,A)` 없음. 둘째: 지정 `C→D`, 장부에 `child(C,D)` 만 있고 `child(C,A)`·`child(C,B)` 없음 |
  | 10 | `옛_연결_지우기가_결과를_모르고_새_연결이_확정_실패면_더_쓰지_않는다` | `lostResponseFor(child(C,A))`, `failFor(child(C,B))` | `assignParent("C","B")` | 델타 1개. 지정 `C→A` |
  | 11 | `같은_상위로_다시_건_요청이_실패해도_더_쓰지_않는다` | 장부에서 `child(C,A)` 를 뺀다. `lostResponseFor(child(C,A))` | `assignParent("C","A")` | 델타 1개. 장부에 `child(C,A)` 있음 |
  | 12 | `지정의_되지우기_직전_리스를_잃으면_지우지_않고_리스_상실로_끝난다` | `child(C,B)` 결과를 모르게 하며 리스 상실 수법 | `assignParent("C","B")` | `LockUnavailableException`. 델타 1개. 장부에 `child(C,B)` 있음. 지정 `C→A` |
  | 13 | `새_조직의_상위_연결_줄도_되지운다` | 조직 `P`(`"grp-p"`, 멤버 `MemberRef.group("g-new")`). `failFor(dm(u-2,g-new))` | `createGroup(new DirectoryGroup("g-new","grp-new","새 조직",{u-1,u-2}))` | `state.groups` 에 `g-new` 없음. 장부에 `child(g-new,P)`·`dm(u-1,g-new)` 없음 |

- [ ] **Step 3: 돌려 실패를 본다.**
  - 실행: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.IncrementalSyncUnsourcedUndoTest'`.
  - 기대 FAIL: #1·#3·#5·#6·#7·#8·#9·#12.
  - #2·#4·#10·#11·#13 은 지금도 지난다. 옮기는 동안 지킬 핀이다.
- [ ] **Step 4: 구현한다.**
  - **필드·생성자.** Interfaces 대로다.
  - **`diffAndApply`.** 추적기 인자를 받고 `추적.요구한다(있어야했던것)` 를 부른다.
  - **도우미.** `원천_없는_줄을_되지운다` 로 바꾼다. 자바독은 U1·U4 를 가리키고, 리스 상실만 내보낸다는 지금 문단을 둔다.
  - **`upsertGroupInternal`.** `추적 = UnsourcedGrantTracker.조직(group.id(), unsourcedGrantObserver)` 을 만든다.
    - 새 조직이고 실패가 있으면 `원천_없는_줄을_되지운다(group.id(), "저장하지 못한 새 조직", …)` 를 부른다.
    - 그 밖은 `saveGroup` 성공 뒤 `추적.저장했다(멤버의_줄(차집합(차집합(group.members(), existingOrEmpty.members()), reconciled.members()), group.id()))` 를 부른다.
    - 그다음 이력, 회수 셈을 지금처럼 하고 `원천_없는_줄을_되지운다(group.id(), "넣기를 저장하지 못한 조직", …)` 를 부른다.
  - **`changeGroupInternal`.** 추적기를 같은 식으로 만든다.
    - `saveGroupChange` 성공 뒤 `추적.저장했다(멤버의_줄(차집합(차집합(후.members(), 전.members()), reconciled.members()), groupId))` 를 부른다.
    - 그다음 이력, 회수 셈, `원천_없는_줄을_되지운다(groupId, "넣기를 저장하지 못한 조직", …)` 순이다.
    - `reconcileGroupMembers` 는 바꾸지 않는다(U2).
  - **`지정을_바꾼다`.** `추적 = 조직(하위_id, …)` 이다.
    - 실패가 있으면 `원천_없는_줄을_되지운다(하위_id, "지정을 저장하지 못한 조직", …)` 를 부른다. 같은 상위여도 같다.
    - 실패가 없고 바뀌지 않으면 지금처럼 빈 `Mono` 다.
    - 저장하면 저장 성공 뒤 `추적.저장했다(Set.of())` 를 부르고 이력을 남긴다.
  - **자바독을 코드대로 고친다.**
    - 클래스의 "부분 실패" 문단: 조직 쪽 커밋은 커밋한 뒤의 상태가 요구하지 않는 줄을 되지운다(설계 2026-11-11 U1).
    - `upsertGroup` 의 되지우기 링크.
    - `changeGroup`: 결과를 모르는 넣기는 저장하지 않고 되지운다.
    - `assignParent`: 지정을 저장하지 않으면 그 요청이 쓴 새 연결 줄을 되지운다.
- [ ] **Step 5: 돌려 통과를 본다.** 실행: `./gradlew :core:test`. 기대: core 전체 PASS. 이 시험 13개와 기존 `IncrementalSyncNewGroupUndoTest` 7개가 그대로 든다.
- [ ] **Step 6: 커밋한다.** 메시지: `feat: 조직 쪽 커밋이 커밋한 뒤의 상태가 요구하지 않는 쓴 줄과 결과를 모르는 줄을 되지운다 — 기존 조직의 결과를 모르는 넣기와 지정 실패도 (원천 없는 줄)`

---

### Task 3: 남은 요청을 센다 (U5–U7)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
  - `반영하고_커밋한다` :1395-1407
  - `diffAndApply` 의 커밋 감싸기
  - `원천_없는_줄을_되지운다`
  - `removeGroupInternal` :799
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedCountTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `묶음마다`, `쓰기가_끝났다`, `커밋을_마쳤다`, `오류로_끝났다`, `취소됐다`, `되지우기가_실패했다`.
  - Task 2: 13인자 생성자, `diffAndApply(…, 추적)`, `원천_없는_줄을_되지운다`.
- Produces:
  - `반영하고_커밋한다(TupleDelta delta, LockLease lease, AuditChanges 기록, UnsourcedGrantTracker 추적, Function<TupleWriteResult, Mono<Void>> 커밋)`.
    - 비지 않은 갈래의 본 쓰기는 `Mono.defer(() -> writer.apply(delta, 추적.묶음마다(delta, 기록::tuples)))` 다.
    - 그 Mono 에 `doOnSuccess(r -> 추적.쓰기가_끝났다())` 와 `doOnError(e -> 추적.쓰기가_끝났다())` 를 단다.
    - 갈래 끝에 `doOnError(추적::오류로_끝났다)` 와 `doOnCancel(추적::취소됐다)` 를 단다.
    - 빈 델타 갈래는 그대로 둔다.
    - `removeGroupInternal` 은 `UnsourcedGrantTracker.없음()` 을 넘긴다.
  - `diffAndApply` 는 `보류와_함께_커밋한다` 에 넘기는 `commit` 을 감싼다. 감싼 것은 `(r, b, a) -> commit.apply(r, b, a).then(Mono.fromRunnable(추적::커밋을_마쳤다))` 다.
  - `원천_없는_줄을_되지운다` 의 두 WARN 자리(결과에 실패, 리스 상실이 아닌 오류)에서 `추적.되지우기가_실패했다()` 를 부른다.
  - `FakeTupleWriter`:
    - `public Duration delayBeforeReport = Duration.ZERO;` 를 더한다.
    - 0 이 아니면 반영(`stored`·`written`·`deleted`)은 지금처럼 곧바로 한다. 그 뒤 `Mono.delay(delayBeforeReport)` 다음에 묶음을 알리고, 지금의 결과(차단·`delay` 포함)를 낸다.
    - 0 이면 지금과 똑같다.
    - 자바독: 그 사이 취소되면 반영은 됐는데 알려 오지 않은 묶음이 된다(설계 2026-11-11 §11).

- [ ] **Step 1: `FakeTupleWriter.delayBeforeReport` 를 더한다.** 기존 `FakeTupleWriterTest` 가 지나야 한다.
- [ ] **Step 2: 실패하는 시험을 쓴다** — `IncrementalSyncUnsourcedCountTest`.
  - 준비는 Task 2 의 시험과 같다(장부를 보는 checker, 직원 셋, `G`·`A`·`B`·`C`·`D`, 지정 `C→A`).
  - 유스케이스는 13인자로 만든다.
    ```java
    private IncrementalSyncUseCase 유스케이스(FakeStateRepository state, Duration renewInterval) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, renewInterval,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, AuditRecorder.NONE, TraceIds.NONE,
                Duration.ofSeconds(30), RevocationObserver.NOOP, 남긴_요청::incrementAndGet);
    }
    ```
    `남긴_요청` 은 `AtomicInteger` 다. 기본 `renewInterval` 은 10초다.
  - 시험 끝마다 `assertThat(남긴_요청).hasValue(n)` 이다.
  - 커밋 오류는 `FakeStateRepository` 를 상속해 그 메서드만 `Mono.error(new IllegalStateException("저장 실패(테스트)"))` 로 덮는다. 기존 `IncrementalSyncAuditTest.삭제가_실패하는_저장소` 의 모양이다.
  - "쓰는 도중 리스 상실" 은 `IncrementalSyncLeaseTest` 의 수법을 쓴다. `renewInterval` 은 `Duration.ofMillis(20)`, 늦춤은 `Duration.ofSeconds(1)`, `writer.onApply(() -> lock.failRenew = true)`, `.block(Duration.ofSeconds(5))` 다.
  - "넣기" 는 PATCH u-2 넣기다.

  | # | 이름 | given | 기대 |
  |---|---|---|---|
  | 1 | `새_조직의_되지우기_결과에_실패가_담기면_센다` | `failFor(t -> t.equals(dm(u-2,g-new)) \|\| 지우기만인_델타())`. `createGroup(g-new{u-1,u-2})` | 부분 반영, 1 |
  | 2 | `기존_조직의_되지우기_결과에_실패가_담기면_센다` | `lostResponseFor(dm(u-2,G))`, `failFor(t -> 지우기만인_델타())`. 넣기 | 부분 반영, 1 |
  | 3 | `지정의_되지우기_결과에_실패가_담기면_센다` | `lostResponseFor(child(C,B))`, `failFor(t -> 지우기만인_델타())`. `assignParent("C","B")` | 부분 반영, 1 |
  | 4 | `되지우기가_오류로_끝나면_센다` | `lostResponseFor(dm(u-2,G))`, `abortWhen(d -> d.toWrite().isEmpty() && !d.toDelete().isEmpty())`. 넣기 | 부분 반영, 1 |
  | 5 | `되지우기_전에_리스를_잃으면_한_번만_센다` | Task 2 #8 의 리스 상실 수법. 넣기 | `LockUnavailableException`, 1 |
  | 6 | `넣기를_쓴_뒤_조직_저장이_실패하면_센다` | `saveGroupChange` 실패 저장소. 넣기 | 오류, 1 |
  | 7 | `넣기를_쓴_묶음_뒤_차단기로_멈추면_센다` | `abortWhen(d -> d.toWrite().contains(dm(u-2,G)))`. 넣기 | `TupleWriteAbortedException`, 1 |
  | 8 | `쓰는_도중_리스를_잃어_알려_오지_않은_넣기가_있으면_센다` | 20ms, `delayBeforeReport` 1초, `onApply` 로 `failRenew`. 넣기 | `LockUnavailableException`, 1 |
  | 9 | `알려_온_넣기_뒤에_리스를_잃어도_센다` | 20ms, `writer.delay` 1초, `onApply` 로 `failRenew`. 넣기 | `LockUnavailableException`, 1 |
  | 10 | `지정을_쓴_뒤_저장이_실패하면_센다` | `saveAssignedParent` 실패 저장소. `assignParent("C","B")` | 오류, 1 |
  | 11 | `되지우는_도중_리스를_잃어도_한_번만_센다` | 20ms, `lostResponseFor(dm(u-2,G))`. `onApply` 에서 두 번째 부름(되지우기)에만 `writer.delayBeforeReport = 1초` 와 `lock.failRenew = true`. 넣기 | `LockUnavailableException`, 1 |
  | 12 | `성공은_세지_않는다` | — 넣기 | 전부 반영, 0 |
  | 13 | `커밋과_되지우기를_마친_부분_반영은_세지_않는다` | `lostResponseFor(dm(u-2,G))`. 넣기 | 부분 반영, 0 |
  | 14 | `확정_실패뿐이면_세지_않는다` | `failFor(t -> true)`. 넣기 | 부분 반영, 0 |
  | 15 | `Check_오류는_세지_않는다` | `checker.failFor(t -> true)`. 넣기 | 오류, 0 |
  | 16 | `쓰기_직전_리스_확인이_실패하면_세지_않는다` | `lock.failRenew = true`. 넣기 | `LockUnavailableException`, 0, 델타 0개 |
  | 17 | `같은_상위로_다시_건_요청이_커밋_직전_리스를_잃어도_세지_않는다` | 장부에서 `child(C,A)` 를 뺀다. `onApply(() -> lock.failRenewAfterAttempt = lock.renewAttempted.get())`. `assignParent("C","A")` | `LockUnavailableException`, 0 |
  | 18 | `기존_멤버의_어긋남만_고친_뒤_커밋이_실패하면_세지_않는다` | 장부에서 `dm(u-1,G)` 를 뺀다. `saveGroupChange` 실패 저장소. PATCH `{u-1}` 넣기 | 오류, 0 |
  | 19 | `확정_실패만_알린_뒤_취소되면_세지_않는다` | 20ms, `failFor(t -> true)`, `writer.delay` 1초, `onApply` 로 `failRenew`. 넣기 | `LockUnavailableException`, 0 |
  | 20 | `직원_쓰기의_커밋_오류는_세지_않는다` | 비활성 직원 `u-4` 가 `G` 의 멤버다. `saveUser` 실패 저장소. `changeUser("u-4", u -> u.withActive(true))` | 오류, 0, 장부에 `dm(u-4,G)` 있음 |
  | 21 | `조직_삭제의_오류는_세지_않는다` | `deleteGroup` 실패 저장소. `removeGroup("G")` | 오류, 0 |

- [ ] **Step 3: 돌려 실패를 본다.**
  - 실행: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.IncrementalSyncUnsourcedCountTest'`.
  - 기대 FAIL: 셈이 1 인 #1–#11. 0 인 #12–#21 은 지금도 지난다.
- [ ] **Step 4: 구현한다.** Interfaces 대로다. `반영하고_커밋한다` 와 `diffAndApply` 의 자바독에 추적기를 한 문단씩 더한다. 내용은 본 쓰기만 보고, 커밋 자리를 마친 뒤의 끝남은 세지 않는다는 것이다.
- [ ] **Step 5: 돌려 통과를 본다.** 실행: `./gradlew :core:test`. 기대: core 전체 PASS.
- [ ] **Step 6: 커밋한다.** 메시지: `feat: 조직 쪽 쓰기가 원천 없는 줄을 남겼을 수 있는 요청을 한 번씩 센다 — 커밋 전에 끝났거나 되지우기가 실패한 요청 (원천 없는 줄)`

---

### Task 4: 지표와 결선 (app-scim)

**Files:**
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java:99-101`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java`

**Interfaces:**
- Consumes: `UnsourcedGrantObserver`, 13인자 생성자(Task 2).
- Produces:
  - `ScimSyncMetrics implements …, UnsourcedGrantObserver`.
    - 생성자에서 `Counter.builder("scim.writes.unsourced").description(…).register(registry)` 를 필드에 쥔다.
    - `leftUnsourced()` 가 그것을 1 올린다.
    - 설명: `조직 쪽 쓰기가 원천 없는 줄(저장된 상태가 요구하지 않는 부여 줄)을 남겼을 수 있는 요청 수 — 오르면 WARN 의 조직을 보고, 같은 요청이 성공하지 않았으면 재적재(mode=tuples)`.
    - 클래스 자바독의 표에 행을 더한다. 기동 때 0 으로 두는 까닭은 `scim.revocations` 문단과 같다.
  - `ScimUseCaseConfig.incrementalSyncUseCase` 는 13번째 인자로 `metrics` 를 넘긴다.

- [ ] **Step 1: 실패하는 시험을 쓴다** — `ScimSyncMetricsTest` 에 둘을 더한다.
  ```java
  @Test
  @DisplayName("원천 없는 줄 카운터는 남기기 전부터 태그 없이 0 으로 있다 — 기동 직후의 첫 증가도 1시간 증가량에 잡힌다")
  void 원천_없는_줄_카운터는_남기기_전부터_0_으로_있다() {
      assertThat(registry.find("scim.writes.unsourced").counters()).hasSize(1);
      assertThat(registry.get("scim.writes.unsourced").counter().getId().getTags()).isEmpty();
      assertThat(registry.get("scim.writes.unsourced").counter().count()).isZero();
  }

  @Test
  @DisplayName("원천 없는 줄을 남긴 요청을 알리면 하나씩 오른다(설계 2026-11-11 U5)")
  void 원천_없는_줄을_남긴_요청을_센다() {
      metrics.leftUnsourced();
      metrics.leftUnsourced();
      assertThat(registry.get("scim.writes.unsourced").counter().count()).isEqualTo(2);
  }
  ```
  `registry`·`metrics` 는 기존 `@BeforeEach` 가 만든 것이다.
- [ ] **Step 2: 돌려 실패를 본다.** 실행: `./gradlew :app-scim:test --tests 'dev.starryeye.organization.scim.app.ScimSyncMetricsTest'`. 기대: 컴파일 실패(`leftUnsourced` 없음).
- [ ] **Step 3: 구현한다.** Interfaces 대로다.
- [ ] **Step 4: 돌려 통과를 본다.** 실행: `./gradlew :app-scim:test`. 기대: app-scim 전체 PASS. 애플리케이션 컨텍스트를 띄우는 기존 시험이 결선을 본다.
- [ ] **Step 5: 커밋한다.** 메시지: `feat: 원천 없는 줄을 남긴 요청을 scim.writes.unsourced 로 내고 기동 때 0 으로 만든다 (원천 없는 줄)`

---

### Task 5: 문서 (U8)

**Files:**
- Modify: `README.md`
- Modify: `docs/onboarding/` 의 `02-용어집.md`, `03-아키텍처.md`, `06-SCIM.md`, `09-관리-API.md`, `11-운영.md`, `12-장애와-복구.md`, `13-한계와-알려진-문제.md`, `15-결정-색인.md`

**할 일.** 먼저 README 와 온보딩 전체에서 아래 말을 찾는다: `되지우`, `되지운`, `다른 값을 걸`, `결과를 모르`, `지우지 못했다`, `scim.revocations`. 찾은 자리를 모두 지금 동작대로 고친다. 반드시 들어갈 것:

| 문서 | 자리(0496586 기준) | 들어갈 것 |
|---|---|---|
| README | :637 지정 503 | 503 이면 같은 요청을 다시 보낸다. 옛 연결이 지워졌을 수 있다 — 권한이 빠진 쪽이다. 다른 값을 걸어도 줄이 남지 않는다. 503 을 내기 전에 그 요청이 쓴 새 연결 줄을 지운다. 되지우지 못했으면 `scim.writes.unsourced` 가 오른다 |
| README | :1271 부분 실패 | 규칙 하나(U1)로 고친다. 새 조직 POST, 기존 조직의 결과를 모르는 넣기(저장하지 않고 지운 뒤 503), 지정 실패가 이 규칙을 따른다. 오류로 끝난 요청과 되지우기에 실패한 요청은 센다. 켜짐의 한계 문장은 "센다" 로 고친다 |
| README | :1518 지표 | `scim.writes.unsourced` 문단을 둔다: 무엇을 세나(U5 a·b), 태그 없음, 기동 때 0, 경보 예, 대응 순서(§3.3), OpenFGA 쓰기 장애 중에 함께 오른다는 것 |
| 02 | :1169-1171 고아, :1302 지표 예 | "원천 없는 줄" 항목을 더한다(§1.1 정의). 고아가 생기는 길을 고친다. 카운터 예에 `scim.writes.unsourced` 를 넣는다 |
| 03 | :125 관찰자 표 | `UnsourcedGrantObserver` 행 — `ScimSyncMetrics`(`scim.writes.unsourced` — 기동 때 0 으로 만든다) |
| 06 | :41, :805 결과표, :821, 재조정 설명, :1232 지표표 | 조직 PUT·PATCH 의 결과를 모르는 넣기는 저장하지 않고 지운다. 지정 실패는 새 연결을 지운다. 지표 행을 더한다 |
| 09 | :289 근처 503 | README :637 과 같은 안내 |
| 11 | :349-350 기동 때 0, :387 지표표, :473 경보 예, :617 로그표 | 기동 때 0 으로 만드는 목록에 넣는다. 지표 행, 경보 예를 더한다. 로그표에 WARN 세 문구(Global Constraints, `{대상}` 셋)를 넣는다 |
| 12 | :437-439, :464-468, :725 | 되지우기를 세 경로로 넓힌다. 지정 503 안내를 고친다. 대응표에 `scim_writes_unsourced_total` 이 오름 → WARN 의 조직 id → IdP 로그 → 성공 안 했으면 `mode=tuples` 를 넣는다 |
| 13 | :160-163 | 설계 §6 의 다섯 한계를 적는다: 카운터는 맞았는지 모름, 인스턴스마다, 끊긴 묶음은 위쪽 어림, 저장하지 못한 새 직원 POST 의 줄, 벤더 동작은 문서 근거. 켜짐의 한계 문장을 고친다 |
| 15 | :98 설계 목록, :112 근처, 결정 표 | 2026-11-05 SCIM id 내구성 항목과 같은 모양으로 넣는다: 설계 목록 한 줄, 요약 한 줄, U1–U8 결정 표 |

- 고친 문서의 머리 `기준 커밋:` 줄을 Task 4 의 커밋 해시로 바꾼다. 괄호는 `(unsourced-grants, main 에 머지됨)` 이다(14 §6.6 의 규칙).
- 손대지 않은 문서는 그대로 둔다.
- 문체는 둘레 문단을 따른다. 짧은 문장이고, 한 문장에 생각 하나다.

- [ ] **Step 1: 위 표대로 고친다.**
- [ ] **Step 2: 확인한다.** 실행: `grep -rn "다른 값을 걸기 전에" README.md docs/onboarding/` 와 `grep -rn "scim.writes.unsourced" README.md docs/onboarding/ | wc -l`. 기대: 앞의 것은 남은 자리가 없다. 뒤의 것은 6 이상이다.
- [ ] **Step 3: 커밋한다.** 메시지: `docs: 조직 쪽 쓰기의 되지우기 규칙과 scim.writes.unsourced 를 README 와 온보딩에 반영한다 (원천 없는 줄)`

---

### Task 6: 설계 §9 결과 기록 (컨트롤러)

컨트롤러가 한다. 서브에이전트에 맡기지 않는다.

- [ ] **Step 1:** 최종 리뷰를 마친 트리에서 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로 돌린다(동시에 돌리지 않는다). 건수를 적어 둔다.
- [ ] **Step 2:** 설계 §9 에 다음을 적는다.
  - 바뀐 곳(커밋 해시).
  - 계획의 판정 요약.
  - 최종 리뷰가 고친 것.
  - 두 시험의 건수.
  - 검증 한계: 실제 OpenFGA 의 끊긴 묶음은 보지 않았다. 운영 writer 의 단계 규칙은 가짜 writer 에 없다.
- [ ] **Step 3: 커밋한다.** 메시지: `docs: 원천 없는 줄 설계 §9 에 결과와 최종 트리 검증을 적는다 (원천 없는 줄)`
