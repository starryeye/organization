# 재활성 가드의 읽기 오류 가르기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기록 중 회차와 재적재가 직전 스냅샷을 읽다 실패하면, 깨진 스냅샷(`SnapshotIntegrityException`)일 때만 재활성 판정 없이 넘기고 그 밖의 오류는 회차를 실패시킨다.

**Architecture:** core 의 `FullSyncUseCase.직전_I_를_읽어_본다` 가 `onErrorResume` 을 무결성 오류로 좁힌다(재적재도 같은 도우미를 쓴다). storage-dynamodb 의 `DynamoDbTupleSnapshotRepository.findById` 가 "가져온 아이템을 푸는 동안의 실패"를 모두 `SnapshotIntegrityException` 으로 묶어, 깨짐과 아닌 것의 경계를 저장소 한 곳에 둔다.

**Tech Stack:** Java 17, Gradle 멀티 모듈, Reactor, JUnit5·AssertJ, DynamoDB SDK v2(DynamoDB Local), logback `ListAppender`.

**Spec:** `docs/superpowers/specs/2026-11-25-reactivation-read-errors-design.md`

## Global Constraints

- 시험 메서드 이름·`@DisplayName` 은 한국어, 몸통은 `// given` / `// when` / `// then`. 새 시험 위에는 `// 깨뜨릴 변경: …` 한 줄(이 시험을 실패시킬 제품 변경)을 단다 — 기존 시험 파일의 관례다.
- WARN 문구는 정확히 `직전 스냅샷이 온전하지 않아 재활성 가드를 보지 않는다: {}` 다. `{}` 는 예외 메시지다(스냅샷 id 와 사정 — 직원 id 는 싣지 않는다).
- E3 의 사정 문구는 정확히 `메타를 풀 수 없다: <예외의 getClass().getSimpleName()>` 이다. 메시지 전체는 기존 `온전하지_않다` 도우미의 꼴 그대로다: `스냅샷 <id> 를 온전히 읽지 못했습니다(<사정>) — POST /admin/sync/rebuild 로 복구하세요`.
- 아이템을 **가져오는** 동안의 실패(SDK 예외)는 묶지 않는다. 묶는 것은 가져온 뒤 `toSnapshot` 안에서 난 `RuntimeException` 뿐이다. `SnapshotIntegrityException` 은 그대로 통과시킨다.
- 새 지표·새 설정은 없다. SCIM 은 건드리지 않는다.
- 시험은 벽시계 시각을 단언하지 않는다.
- Gradle 은 한 번에 하나만 돌린다. 과제 시험은 모듈·클래스 단위로만 돌린다(전체 `test`·`scaleTest` 는 메인 세션이 머지 전에 돌린다).
- `git stash` 를 쓰지 않는다. git 명령은 `&&`·`$(...)` 없이 하나씩 쓴다.
- 온보딩 문서는 고친 문서만 머리의 `기준 커밋:` 을 이 슬라이드의 마지막 코드 커밋으로 올린다 — 꼴 `기준 커밋: <해시7> (reactivation-read-errors, main 에 머지됨)`. 시험·Javadoc 만 바꾼 커밋은 코드 커밋으로 세지 않는다 — 여기서는 과제 1·2 의 제품 코드 커밋 가운데 나중 것이다.

## Review Focus

- **기록 중 회차의 오류가 읽기 뒤에 나면 메시지가 지금 그대로여야 한다.** 장부 맞추기 안(`훑어_맞춘다`)의 오류는 `기준선 의심 … / ` 사정이 붙는다. 이번 변경으로 실패하는 I 읽기는 그 앞이라 사정이 붙지 않는다 — 과제 1 의 새 시험이 메시지를 오류 메시지 그대로로 못박는다.
- **비교 경로의 깨진 메타도 같은 재적재 안내로 실패해야 한다.** E3 는 `findLatest` 에도 걸린다. 과제 2 의 시험이 `findLatest` 와 `findById` 둘 다 본다.
- **목록 읽기(`listRecent`·정리)는 바뀌지 않아야 한다.** E3 는 `findById` 만 감싼다. `snapshotMetas()` 의 메타 풀기는 그대로다 — 리뷰어는 감싸기가 그쪽으로 번지지 않았는지 본다(시험 없음, 범위 밖).
- **두 번째 쪽(paging) 가져오기 실패도 묶이지 않아야 한다.** `collectList()` 뒤에 감싸므로 어느 쪽의 Query 실패든 감싸기 전에 오른다. 과제 2 의 가져오기 실패 시험이 첫 쪽으로 그 경계를 못박는다.
- **깨짐일 때 WARN 이 한 번 남고, 깨짐이 아닐 때는 그 WARN 이 없어야 한다.** 과제 1 의 시험이 `ListAppender` 로 본다.

---

### Task 1: core — 직전 I 읽기를 무결성 오류로 좁힌다 (E1·E2)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (`직전_I_를_읽어_본다` ~:206-219, `synchronize` 의 WRITING 주석 ~:232)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java` (`rebuild` Javadoc ~:105-107 의 "없거나 읽지 못하면")
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java`

**Interfaces:**
- Consumes: `FakeSnapshotRepository.failFindLatest(RuntimeException)`(core testFixtures), 기존 도우미 `막힌_둘이_든_첫_회차를_돈다(상한_1명)`·`막혔던_둘도_활성인_조직도(Set.of("kim"))`·`동기화한다(SyncTrigger)`(FullSyncUseCaseTest), `유스케이스(상한_1명)`·`막힌_둘이_든_스냅샷을_둔다()`·`조직도(Set, Set)`·`재적재한다()`(RebuildUseCaseTest).
- Produces: `static Mono<Set<RelationTuple>> 직전_I_를_읽어_본다(TupleSnapshotRepository snapshots)` — 시그니처는 그대로, 동작만 바뀐다.

- [ ] **Step 1: FullSyncUseCaseTest 에 실패할 시험을 쓴다**

```java
// 깨뜨릴 변경: 기록 중 회차가 깨짐이 아닌 스냅샷 읽기 오류도 삼키고 판정 없이 장부를 맞추기(설계 2026-11-25 E1)
@Test
@DisplayName("기록 중 회차가 깨짐이 아닌 오류로 직전 스냅샷을 읽지 못하면 FAILED 이고 아무것도 쓰지 않는다 — 다음 정각이 다시 본다")
void 기록_중_회차가_일시_오류로_스냅샷을_못_읽으면_실패한다() {
    // given
    막힌_둘이_든_첫_회차를_돈다(상한_1명);
    var 앞_장부 = List.copyOf(writer.stored);
    snapshots.writing.set(true);
    snapshots.failFindLatest(new RuntimeException("DynamoDB 일시 장애(테스트)"));
    source.willReturn(막혔던_둘도_활성인_조직도(Set.of("kim")));

    // when
    var run = 동기화한다(SyncTrigger.SCHEDULED);

    // then
    assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(run.message()).isEqualTo("DynamoDB 일시 장애(테스트)");
    assertThat(run.reactivationHold()).isNull();
    assertThat(writer.stored).containsExactlyInAnyOrderElementsOf(앞_장부);
    assertThat(snapshots.saved).hasSize(1);
    assertThat(snapshots.writing).isTrue();
    assertThat(state.users.get("blocked1").active()).isFalse();
    assertThat(재활성_가드를_보지_않는다는_WARN()).isEmpty();
}
```

`재활성_가드를_보지_않는다는_WARN()` 은 이 시험 파일에 더하는 private 도우미다. `FullSyncUseCase` 로거에 `ListAppender<ILoggingEvent>` 를 붙여(`SnapshotArchiveUseCaseTest.setUp` 과 같은 방식, `@BeforeEach` 에서 붙이고 `@AfterEach` 에서 뗀다) WARN 가운데 `getFormattedMessage()` 가 `"직전 스냅샷이 온전하지 않아 재활성 가드를 보지 않는다: "` 로 시작하는 것들의 메시지 목록을 돌려준다.

기존 시험 `기록_중_회차가_스냅샷을_못_읽으면_판정_없이_맞춘다`(~:1790)는 이름을 `기록_중_회차가_깨진_스냅샷이면_판정_없이_맞춘다` 로, 표시 이름을 `"기록 중 회차가 최신 스냅샷이 깨져 있으면 판정 없이 장부를 맞춘다 — 회차는 실패하지 않는다"` 로, 위 `// 깨뜨릴 변경:` 을 `기록 중 회차가 깨진 스냅샷으로 회차를 실패시키기(재적재·장부 맞추기는 깨진 스냅샷을 고치는 길이다)` 로 바꾸고, then 끝에 한 줄을 더한다:

```java
    assertThat(재활성_가드를_보지_않는다는_WARN())
            .containsExactly("직전 스냅샷이 온전하지 않아 재활성 가드를 보지 않는다: "
                    + "기준선 스냅샷 20260806T030000-LDAP 의 묶음 수가 맞지 않습니다 — POST /admin/sync/rebuild 로 복구하세요");
```

- [ ] **Step 2: RebuildUseCaseTest 에 실패할 시험을 쓴다**

```java
// 깨뜨릴 변경: 재적재가 깨짐이 아닌 스냅샷 읽기 오류도 삼키고 판정 없이 장부를 맞추기(설계 2026-11-25 E1)
@Test
@DisplayName("재적재는 깨짐이 아닌 오류로 직전 스냅샷을 읽지 못하면 FAILED 이고 아무것도 쓰지 않는다")
void 재적재는_일시_오류로_스냅샷을_못_읽으면_실패한다() {
    // given
    useCase = 유스케이스(상한_1명);
    막힌_둘이_든_스냅샷을_둔다();
    snapshots.failFindLatest(new RuntimeException("DynamoDB 일시 장애(테스트)"));
    source.willReturn(조직도(Set.of("kim", "blocked1", "blocked2"), Set.of()));

    // when
    SyncRun run = 재적재한다();

    // then
    assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
    assertThat(run.message()).isEqualTo("DynamoDB 일시 장애(테스트)");
    assertThat(writer.stored).containsExactly(김_백엔드);
    assertThat(snapshots.saved).hasSize(1);
    assertThat(snapshots.writing).isFalse();
    assertThat(state.users).isEmpty();
}
```

기존 `재적재는_스냅샷을_못_읽어도_판정_없이_끝낸다`(~:565)는 이름을 `재적재는_깨진_스냅샷이어도_판정_없이_끝낸다`, 표시 이름을 `"재적재는 직전 스냅샷이 깨져 있어도 판정 없이 끝낸다"` 로 바꾼다. 단언은 그대로 둔다.

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.FullSyncUseCaseTest' --tests 'dev.starryeye.organization.core.usecase.RebuildUseCaseTest'`
Expected: 새 시험 둘이 FAIL(`SUCCEEDED` 가 `FAILED` 가 아님). 이름만 바꾼 깨짐 시험은 WARN 문구 단언에서 FAIL(지금 문구는 `직전 스냅샷을 읽지 못해 …`).

- [ ] **Step 4: `직전_I_를_읽어_본다` 를 좁힌다**

`onErrorResume(error -> …)` 를 `onErrorResume(SnapshotIntegrityException.class, …)` 로 바꾸고 WARN 문구를 Global Constraints 의 것으로 바꾼다. Javadoc 은 "스냅샷이 없으면 빈 집합(판정 없음), 깨졌으면(`SnapshotIntegrityException`) WARN 을 남기고 빈 집합, 그 밖의 오류는 그대로 올려 회차가 실패한다(설계 2026-11-25 E1) — 깨진 스냅샷은 이 두 길이 고칠 일이고 일시 장애는 다음 정각이 다시 본다" 의 뜻으로 고친다. `synchronize` 의 WRITING 줄 주석 "없거나 읽지 못하면 판정 없이 간다" 를 "없거나 깨졌으면 판정 없이 간다. 그 밖의 읽기 오류는 회차를 실패시킨다" 로, `RebuildUseCase.rebuild` Javadoc 의 "(없거나 읽지 못하면 판정 없음)" 을 "(없거나 깨졌으면 판정 없음, 그 밖의 읽기 오류는 실패)" 로 고친다.

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: Step 3 과 같은 명령.
Expected: PASS(두 클래스 전부).

- [ ] **Step 6: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java
git commit -m "fix: 기록 중 회차·재적재는 깨진 스냅샷만 재활성 판정 없이 넘기고 그 밖의 직전 스냅샷 읽기 오류는 회차를 실패시킨다 (재활성 읽기 오류)"
```

---

### Task 2: storage-dynamodb — 푸는 동안의 실패를 무결성 오류로 묶는다 (E3)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java` (`findById` ~:236-240, `toSnapshot` 호출 자리)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`

**Interfaces:**
- Consumes: 시험 파일의 기존 도우미 `스냅샷(String, Instant, Set)`·`튜플들(int)`·`메타를_고친다(String, Consumer<Map<String, AttributeValue>>)`·`지금`, 기존 `온전하지_않다(String snapshotId, String 사정)`(제품 코드).
- Produces: `findById`·`findLatest` 가 푸는 동안의 실패에 `SnapshotIntegrityException` 을 낸다. 시그니처는 그대로다.

- [ ] **Step 1: 실패할 시험을 쓴다**

세 시험은 같은 꼴이다 — `repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();` 뒤 `메타를_고친다` 로 칸 하나를 깨고, `findLatest()` 와 `findById("20260814T030000-LDAP")` **둘 다** 아래처럼 단언한다.

```java
assertThatThrownBy(() -> repository.findLatest().block())
        .isInstanceOf(SnapshotIntegrityException.class)
        .hasMessageContaining("20260814T030000-LDAP")
        .hasMessageContaining("메타를 풀 수 없다: <예외 이름>")
        .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
```

| 시험 이름 | 깨는 칸 | `<예외 이름>` |
|---|---|---|
| `메타의_튜플_수가_수가_아니면_무결성_오류다` | `meta.put("tupleCount", AttributeValue.fromS("다섯"))` | `NumberFormatException` |
| `메타의_만든_시각이_시각이_아니면_무결성_오류다` | `meta.put("createdAt", AttributeValue.fromS("어제"))` | `DateTimeParseException` |
| `메타의_출처가_모르는_값이면_무결성_오류다` | `meta.put("source", AttributeValue.fromS("FTP"))` | `IllegalArgumentException` |

`// 깨뜨릴 변경:` 은 셋 다 "푸는 동안의 RuntimeException 을 무결성 오류로 묶지 않기 — 기록 중 회차·재적재가 깨진 스냅샷 때문에 영영 실패한다(설계 2026-11-25 E3)".

경계의 반대쪽 시험 하나:

```java
// 깨뜨릴 변경: 가져오기 실패(SDK 오류)까지 무결성 오류로 묶기 — 일시 장애에 재활성 가드가 꺼진다(설계 2026-11-25 E3)
@Test
@DisplayName("스냅샷 아이템을 가져오다 실패하면 무결성 오류가 아니라 그 오류 그대로다 — 깨짐과 일시 장애를 가른다")
void 가져오기_실패는_무결성_오류로_묶지_않는다() {
    // given
    repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();
    var 가져오기가_실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
            Query가_스로틀되는_클라이언트(), properties, Clock.fixed(지금, ZoneOffset.UTC));

    // when, then
    assertThatThrownBy(() -> 가져오기가_실패하는_저장소.findLatest().block())
            .isInstanceOf(ProvisionedThroughputExceededException.class)
            .isNotInstanceOf(SnapshotIntegrityException.class);
    assertThatThrownBy(() -> 가져오기가_실패하는_저장소.findById("20260814T030000-LDAP").block())
            .isInstanceOf(ProvisionedThroughputExceededException.class);
}
```

`Query가_스로틀되는_클라이언트()` 는 이 시험 파일의 기존 프록시 도우미(`두번째_묶음_쓰기가_실패하는_클라이언트` 등)와 같은 꼴의 private 도우미다 — `method.getName().equals("query")` 이면 `CompletableFuture.failedFuture(ProvisionedThroughputExceededException.builder().message("스로틀(테스트)").build())`, 나머지는 진짜 `client` 로 보낸다(포인터 `getItem` 은 그대로 통과한다).

- [ ] **Step 2: 시험이 실패하는지 본다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest'`
Expected: 메타 세 시험 FAIL(`NumberFormatException` 등이 그대로 올라 `SnapshotIntegrityException` 이 아님). 가져오기 실패 시험은 지금도 PASS 다 — 경계를 못박는 시험이라 괜찮다.

- [ ] **Step 3: `findById` 에서 `toSnapshot` 을 감싼다**

`findById` 의 `.flatMap(items -> Mono.justOrEmpty(toSnapshot(snapshotId, items)))` 가 부르는 자리를 private 도우미(예: `풀어_본다(String snapshotId, List<Map<String, AttributeValue>> items)`)로 바꾼다. 도우미는 `toSnapshot` 을 부르고, `SnapshotIntegrityException` 은 그대로 다시 던지고, 그 밖의 `RuntimeException e` 는 `온전하지_않다(snapshotId, "메타를 풀 수 없다: " + e.getClass().getSimpleName())` 로 바꿔 던진다. Javadoc 한 줄: "가져온 아이템을 푸는 동안의 실패는 모두 깨짐이다 — 가져오기 실패(SDK)는 여기 오기 전에 오른다(설계 2026-11-25 E3)". `snapshotMetas()`·`listRecent` 는 건드리지 않는다.

- [ ] **Step 4: 시험이 통과하는지 본다**

Run: Step 2 와 같은 명령.
Expected: PASS(클래스 전부).

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java
git commit -m "fix: 스냅샷 저장소는 가져온 아이템을 푸는 동안의 실패를 모두 무결성 오류로 묶는다 — 메타 칸 하나가 깨진 스냅샷이 재적재를 막지 않게 (재활성 읽기 오류)"
```

---

### Task 3: 문서 — 동작 문서를 현재 동작으로 고친다

**Files:**
- Modify: `README.md` (~:596 재활성 가드 절의 "기록 중 회차와 재적재는 …" 문장과 WARN 문구)
- Modify: `docs/onboarding/04-데이터-모델.md` (~:572-580 스냅샷 읽기 검사 목록)
- Modify: `docs/onboarding/05-LDAP-동기화.md` (~:546 무결성 메시지 표, ~:705 WARN 두 줄, ~:713·715 흐름 그림, ~:719)
- Modify: `docs/onboarding/07-재적재-초기화-아카이빙.md` (~:117, ~:243)
- Modify: `docs/onboarding/11-운영.md` (~:661 로그 표의 WARN 줄)
- Modify: `docs/onboarding/12-장애와-복구.md` (§3.5 "할 일")
- Modify: `docs/onboarding/13-한계와-알려진-문제.md` (§3.12 ~:320)
- Modify: `docs/onboarding/15-결정-색인.md` (설계 문서 목록 ~:108, 새 절, 더 깊이 ~:595)

**Interfaces:**
- Consumes: 과제 1·2 의 동작과 문구(Global Constraints 의 두 문구). 기준 커밋 해시는 `git log --oneline -5` 에서 과제 1·2 의 제품 코드 커밋 가운데 나중 것.

- [ ] **Step 1: 동작 문장을 고친다**

모든 자리에서 같은 한 규칙으로 쓴다: **기록 중 회차와 재적재는 스냅샷이 없거나 깨졌으면(`SnapshotIntegrityException`) 재활성 가드를 보지 않고 넘어가고(WARN `직전 스냅샷이 온전하지 않아 재활성 가드를 보지 않는다: …`), 그 밖의 읽기 오류(DynamoDB 일시 장애 등)면 쓰기 전에 회차가 `FAILED` 다 — 메시지는 그 오류의 메시지, 기록 중 표시는 남아 다음 정각이 다시 본다(설계 2026-11-25).** 자리별로:
- README :596, 05 :705·:713·:715·:719, 07 :117·:243("재활성 가드 때문에 재적재가 실패하지는 않는다" → "깨진 스냅샷 때문에는 실패하지 않는다. 깨짐이 아닌 읽기 오류면 재적재가 `FAILED` 다 — 다시 건다"), 11 :661(표의 문구와 뜻 칸 — "기록 중 회차나 재적재가 본 최신 스냅샷이 깨졌다. 그 회차는 판정 없이 진행한다 — [12 — 3.5]"), 13 §3.12 의 "스냅샷이 없으면 보지 않는다" 항목(사정의 "스냅샷을 읽지 못할 때" → "스냅샷이 깨졌을 때", 끝에 "깨짐이 아닌 읽기 오류면 회차가 실패한다(설계 2026-11-25)").
- 04 :572 목록에 한 항목: 메타 칸의 모양이 틀리면(수 칸이 수가 아님, `createdAt` 이 시각이 아님, 모르는 `source`) 사정 `메타를 풀 수 없다: <예외 이름>`. :580 의 "B 와 U 가 겹치면 … `IllegalArgumentException` 으로 거절한다. 이것만 `SnapshotIntegrityException` 이 아니다" 는 "읽을 때는 이것도 `SnapshotIntegrityException`(사정 `메타를 풀 수 없다: IllegalArgumentException`)이다. 저장할 때도 같은 생성자를 거치므로 겹친 스냅샷은 애초에 만들어지지 않는다" 로 고친다.
- 05 :546 표에 한 줄: `| 메타 칸의 모양이 틀림(수가 아님·시각이 아님·모르는 출처) | 스냅샷 <id> 를 온전히 읽지 못했습니다(메타를 풀 수 없다: <예외 이름>) — POST /admin/sync/rebuild 로 복구하세요 |`.
- 12 §3.5 "할 일" 끝에: 재적재는 재활성 가드를 보려고 최신 스냅샷의 I 를 읽어 보는데, 깨져 있으면 WARN 을 남기고 판정 없이 맞춘다. 깨짐이 아닌 읽기 오류면 재적재가 `FAILED` 다 — 다시 건다.

- [ ] **Step 2: 결정 색인을 고친다**

- 설계 문서 목록(~:108): 2026-11-24 줄의 끝 칸 "현재(가장 최신)" 을 "현재. R8 의 읽기 오류는 2026-11-25 가 좁혔다" 로 바꾸고, 그 아래 줄을 더한다: `| [2026-11-25 재활성 읽기 오류](../superpowers/specs/2026-11-25-reactivation-read-errors-design.md) | 재활성 가드 §9.5 의 R8 — 기록 중 회차·재적재는 깨진 스냅샷만 판정 없이 넘기고 그 밖의 읽기 오류는 회차 실패, 저장소는 푸는 동안의 실패를 무결성 오류로 묶음, 새 지표 없음 | 현재(가장 최신). [재활성 읽기 오류(E1~E5)](#재활성-읽기-오류e1e5) |`.
- `## 재활성 가드(R1~R13)` 절의 R8 줄 "결정" 칸 끝에 ` (읽기 오류 부분은 바뀜 — E1)` 을 붙인다.
- `## 검증 방식` 앞에 새 절 `## 재활성 읽기 오류(E1~E5)`: 머리 문단 한 줄(설계 링크, 소유자는 "추천대로"(2026-11-25)라고만 했고 결정은 메인 세션의 추천이다, 번호는 그 설계의 것) + 기존 절과 같은 다섯 칸 표(`# | 결정 | 까닭 | 버린 길 | 동작 문서`) E1~E5. 결정·까닭은 설계 §3 표에서, 버린 길은 §2 의 B·C·D 를 E1 줄에, E3 줄에는 "가져오기 실패까지 묶기(일시 장애에 가드가 꺼진다)" 를 적는다. 동작 문서 칸은 05 재활성 가드·07 재활성 가드·04 스냅샷 포인터.
- `## 더 깊이` 끝에: `- 재활성 가드의 읽기 오류 가르기와 말할 수 없는 것: [재활성 읽기 오류 §3·§6](../superpowers/specs/2026-11-25-reactivation-read-errors-design.md)`.

- [ ] **Step 3: 고친 문서의 기준 커밋을 올린다**

고친 온보딩 문서(04·05·07·11·12·13·15)만 머리 줄을 `> 기준 커밋: <해시7> (reactivation-read-errors, main 에 머지됨). 코드가 바뀌면 이 문서도 함께 고친다.` 로 바꾼다. 다른 온보딩 문서와 온보딩 README 는 그대로 둔다.

- [ ] **Step 4: 남은 옛 문구가 없는지 본다**

Run: `grep -rn "읽지 못해 재활성 가드를 보지 않는다\|읽기가 실패하면 보지\|없거나 실패면 판정 없음\|없거나 읽지 못하면 판정\|읽다 실패해도 WARN" README.md docs/onboarding core/src/main`
Expected: 출력은 `docs/onboarding/15-결정-색인.md` 의 R8 줄 하나뿐이다(옛 결정의 기록이라 그대로 두고 Step 2 가 "바뀜" 표시를 붙였다).

- [ ] **Step 5: 커밋한다**

```bash
git add README.md docs/onboarding/04-데이터-모델.md docs/onboarding/05-LDAP-동기화.md docs/onboarding/07-재적재-초기화-아카이빙.md docs/onboarding/11-운영.md docs/onboarding/12-장애와-복구.md docs/onboarding/13-한계와-알려진-문제.md docs/onboarding/15-결정-색인.md
git commit -m "docs: 기록 중 회차·재적재의 직전 스냅샷 읽기 오류 가르기(깨짐만 판정 없이, 그 밖은 실패)와 메타 칸 깨짐의 무결성 오류를 README 와 온보딩에 반영한다 (재활성 읽기 오류)"
```
