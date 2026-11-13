# 바뀐 것 없는 지정을 락 없이 끝내기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 상위로 다시 건 지정 PUT 과 지정이 없는 조직의 DELETE 가 전역 쓰기 락 없이 204 로 끝나게 하고, 그 논증이 성립하도록 저장하는 조직 커밋의 되지우기가 지정이 받치는 하위 조직 줄을 지우지 않게 하며, 커밋 자리 경계를 시험으로 고정한다.

**Architecture:** `IncrementalSyncUseCase.assignParent`·`clearParent` 앞에 "이미 그렇다" 만 판정하는 앞 확인(강한 일관성 읽기 + Check)을 두고, 아니면 지금 본문(`withLock`)으로 간다. `changeGroup`·`upsertGroupInternal` 의 커밋은 "저장하지 못한 넣기" 에서 `지정이_받치는_하위` 를 빼고 되지울 집합을 정한다. 커밋 경계 시험은 가짜 저장소의 보류 목록 쓰기 실패 주입으로 만든다.

**Tech Stack:** Java 17, Spring WebFlux / Reactor, Lombok, JUnit 5 + AssertJ, Testcontainers(OpenFGA·DynamoDB Local), Gradle 멀티 모듈(core, app-scim).

**Spec:** `docs/superpowers/specs/2026-11-13-lockless-noop-assignment-design.md`

## Global Constraints

- 결정은 설계 §2 의 L1~L8·T1 이다. 설계와 이 계획이 어긋나면 설계가 이긴다.
- 앞 확인은 아무것도 쓰지 않는다. "이미 그렇다" 가 아니면 지금 본문이 처음부터 다시 판정한다 — 앞 확인이 읽은 값을 락 안으로 넘기지 않는다(L3).
- 앞 확인의 읽기 순서: 대상 이름표·저장된 지정 → (지정이 요청의 P 일 때만) P 이름표 → Check `child(S,P)`. 저장된 지정이 P 가 아니면 Check 하지 않는다(L4).
- 앞 확인이 오류로 끝나면 지금 본문으로 간다. 로그는 DEBUG 까지다(L5).
- 새 지표를 두지 않는다(L6). 로그에 `externalId` 를 싣지 않는다.
- OpenFGA 는 Check 만 쓴다(Read 금지). 요청 하나가 읽는 양은 조직 수·직원 수를 따라가지 않는다.
- 서브에이전트는 모듈 시험만 돌린다: `./gradlew :core:test --tests <클래스>` 또는 `./gradlew :app-scim:test --tests <클래스>`. Gradle 은 한 번에 하나만 돈다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- git 은 꾸밈 없는 단일 명령으로 쓴다(`&&`·`$(...)` 금지 — 작업 트리 가드가 거절한다). `git stash` 금지.
- 커밋 메시지는 `<종류>: <한국어 요약> (락 없는 지정)` 이고, 끝 줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 이다.
- 식별자·자바독·주석은 이 저장소처럼 한국어로 쓴다. 자바독에 근거 설계를 `설계 2026-11-13 L<n>` 꼴로 적는다.

## Review Focus

1. **앞 확인의 읽기가 한 번 실패한 뒤의 응답** — 지금과 같아야 한다(락 안의 길이 답한다). → Task 2 의 Check 실패·지정 읽기 실패 시험.
2. **지정이 없는 조직을 가리키는 낡은 지정** — PUT 은 지금처럼 404(상위 없음), DELETE 는 지금처럼 204 이고 그 지정 줄을 지우지 않는다. → Task 2 시험.
3. **재적재가 락을 쥔 동안** — 바뀐 것 없는 요청은 204, 바꾸는 요청은 503 + `Retry-After` 60. → Task 2 의 e2e 시험.
4. **L8 의 지정 읽기가 실패하는 커밋** — 저장하지 않고 그 오류로 끝나며, 되지우지 않은 줄이 있으니 센다(U5 a). → Task 1 의 카운터 시험.
5. **성공한 하위 조직 넣기** — L8 이 지정을 읽지 않는다(실패 경로에서만 읽는다). → Task 1 시험.

---

### Task 1: 저장하는 조직 커밋의 되지우기에서 지정이 받치는 하위 조직 줄을 뺀다 (L8)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` — `upsertGroupInternal` 의 커밋(약 512-531행), `changeGroup` 의 커밋(약 676-688행), `지정이_받치는_하위`(약 755행)의 자바독
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` — `findAssignedParent`(약 242행)와 공개 필드
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedUndoTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedCountTest.java`

**Interfaces:**
- Produces (Task 2 가 쓴다):
  - `FakeStateRepository.findAssignedParentCalls` — `public final List<String>`, `findAssignedParent` 가 받은 조직 id 를 차례로 적는다.
  - `FakeStateRepository.findAssignedParentFailsOnce` — `public final Set<String>`(동시 접근 안전한 집합). 여기 든 조직의 다음 `findAssignedParent` 는 `IllegalStateException("지정 읽기 실패(테스트)")` 로 끝나고, 그 조직을 집합에서 뺀다.

- [ ] **Step 1: 가짜 저장소에 읽기 기록과 한 번 실패 주입을 더한다**

`findAssignedParent` 가 `Mono.defer` 안에서 먼저 `findAssignedParentCalls` 에 적고, `findAssignedParentFailsOnce.remove(orgCode)` 가 참이면 위 예외로 끝난다. 각 필드에 한 줄 자바독.

- [ ] **Step 2: 실패하는 시험을 쓴다 — `IncrementalSyncUnsourcedUndoTest`**

이 클래스의 준비는 "C 의 지정 상위는 A, 장부에 `child(C,A)`" 다. 세 시험을 더한다.

```java
@Test
@DisplayName("하위 조직을 넣는 PATCH 가 결과를 몰라도 그 하위 조직의 지정 상위가 이 조직이면 연결 줄을 지우지 않는다 — 저장된 지정이 요구한다")
void 지정이_받치는_하위_조직_넣기는_결과를_몰라도_줄을_지우지_않는다() {
    // given — 장부에서 child(C,A) 가 빠져 있다(어긋남). A 에 C 를 넣는 PATCH 가 그 줄을 쓰는데 응답을 잃는다
    writer.stored.remove(child("C", "A"));
    writer.lostResponseFor(본_쓰기의(child("C", "A")));

    // when
    var 결과 = useCase.changeGroup("A", 넣는다(MemberRef.group("C"))).block();

    // then
    assertThat(결과.fullyApplied()).isFalse();
    assertThat(state.groups.get("A").members()).doesNotContain(MemberRef.group("C"));
    assertThat(writer.stored).contains(child("C", "A"));
}

@Test
@DisplayName("기존 조직 upsertGroup 도 지정이 받치는 하위 조직의 연결 줄을 지우지 않는다")
void 기존_조직_upsertGroup_도_지정이_받치는_하위_조직의_줄을_지우지_않는다() {
    // given — 위와 같다
    writer.stored.remove(child("C", "A"));
    writer.lostResponseFor(본_쓰기의(child("C", "A")));

    // when
    var 결과 = useCase.upsertGroup(new DirectoryGroup("A", "grp-a", "A", Set.of(MemberRef.group("C")))).block();

    // then
    assertThat(결과.fullyApplied()).isFalse();
    assertThat(state.groups.get("A").members()).doesNotContain(MemberRef.group("C"));
    assertThat(writer.stored).contains(child("C", "A"));
}

@Test
@DisplayName("하위 조직 넣기가 모두 저장되면 그 하위 조직의 지정을 읽지 않는다 — 실패 경로에서만 읽는다")
void 넣기가_모두_저장되면_하위_조직의_지정을_읽지_않는다() {
    // when
    var 결과 = useCase.changeGroup("G", 넣는다(MemberRef.group("S"))).block();

    // then — 순환 걷기는 G 와 그 조상의 지정을 읽는다. 넣은 S 의 지정은 읽지 않는다
    assertThat(결과.fullyApplied()).isTrue();
    assertThat(state.findAssignedParentCalls).doesNotContain("S");
}
```

지금 시험 `하위 조직을 넣는 PATCH 가 결과를 모르면 멤버 행을 저장하지 않고 연결 줄을 지운다`(지정 없는 S)는 그대로 통과해야 한다 — L8 이 지정 없는 하위 조직까지 지키지 않는다는 짝이다.

- [ ] **Step 3: 실패하는 시험을 쓴다 — `IncrementalSyncUnsourcedCountTest`**

```java
@Test
@DisplayName("저장하지 못한 하위 조직 넣기의 지정을 읽지 못하면 저장하지 않고 그 오류로 끝나며 센다")
void 저장하지_못한_하위_조직_넣기의_지정을_읽지_못하면_센다() {
    // given — G 에 D 를 넣는 PATCH 가 child(D,G) 의 응답을 잃는다. 커밋이 D 의 지정을 읽다가 실패한다
    writer.lostResponseFor(본_쓰기의(child("D", "G")));
    state.findAssignedParentFailsOnce.add("D");

    // when
    var 요청 = IdP_로(useCase.changeGroup("G", GroupChange.delta().adding(Set.of(MemberRef.group("D")))));

    // then
    assertThatThrownBy(요청::block).hasMessageContaining("지정 읽기 실패");
    assertThat(state.findAssignedParentCalls).contains("D");
    assertThat(state.groups.get("G").members()).containsExactly(MemberRef.user("u-1"));
    assertThat(writer.stored).contains(child("D", "G"));
    assertThat(남긴_요청).hasValue(1);
}
```

- [ ] **Step 4: 시험이 실패하는지 본다**

Run: `./gradlew :core:test --tests "dev.starryeye.organization.core.usecase.IncrementalSyncUnsourcedUndoTest" --tests "dev.starryeye.organization.core.usecase.IncrementalSyncUnsourcedCountTest"`
Expected: 처음 두 Undo 시험은 `writer.stored` 에 `child(C,A)` 가 없어 FAIL. 카운터 시험은 `findAssignedParentCalls` 에 `D` 가 없어 FAIL. "지정을 읽지 않는다" 시험은 지금도 PASS 다(뒤 단계가 깨지 않는지 지키는 시험).

- [ ] **Step 5: 두 커밋을 고친다**

두 커밋 모두 같은 모양으로 바꾼다.
- 저장하지 못한 넣기의 멤버 집합(`차집합(차집합(넣으려던 멤버, 원래 멤버), 재조정한 멤버)`)을 먼저 구한다.
- **저장 전에** `지정이_받치는_하위(조직 id, 그 집합)` 로 받치는 하위 조직을 읽는다.
- 되지울 줄은 `멤버의_줄(차집합(그 집합, 받치는것), 조직 id)` 다. 그 뒤는 지금 순서 그대로다: 저장 → `추적.저장했다(그 줄)` → 이력 → 회수 셈 → `원천_없는_줄을_되지운다`.
- 읽기가 실패하면 저장하지 않고 그 오류로 끝난다(따로 잡지 않는다).
- 주석의 근거에 `설계 2026-11-13 L8` 을 더한다.
- `지정이_받치는_하위` 의 매개변수 이름과 자바독을 "빠지는 멤버" 에서 "멤버(빠지는 멤버, 저장하지 못한 넣기)" 로 넓힌다. 동작은 바꾸지 않는다.
- `upsertGroupInternal` 의 새 조직 갈래(`existing.isEmpty() && result.hasFailure()`)는 건드리지 않는다.

- [ ] **Step 6: 시험이 통과하는지 본다**

Run: Step 4 와 같은 명령
Expected: PASS(두 클래스 모두).

- [ ] **Step 7: core 모듈 시험 전체를 돌린다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 8: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedUndoTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedCountTest.java
git commit -m "fix: 저장하는 조직 커밋의 되지우기가 지정이 받치는 하위 조직의 연결 줄을 지우지 않는다 (락 없는 지정)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 2: 지정 PUT·DELETE 의 앞 확인 (L1~L5)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` — `assignParent`·`clearParent`(약 947-970행)와 그 자바독, 클래스 자바독의 지정 문단(약 118행)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncParentAssignmentTest.java`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimHierarchyEndToEndTest.java`

**Interfaces:**
- Consumes: Task 1 의 `FakeStateRepository.findAssignedParentFailsOnce`.
- Produces: 공개 시그니처는 그대로다 — `Mono<IncrementalSyncResult> assignParent(String orgCode, String parentOrgCode)`, `Mono<IncrementalSyncResult> clearParent(String orgCode)`. 락 없이 끝나면 `IncrementalSyncResult.noChange()` 를 낸다.
- 새 private 메서드(이름은 이대로):
  - `Mono<Boolean> 이미_지정돼_있다(String orgCode, String parentOrgCode)`
  - `Mono<Boolean> 지정이_없다(String orgCode)`
  - `Mono<IncrementalSyncResult> 락_안에서_지정한다(String orgCode, String parentOrgCode)` — 지금 `assignParent` 본문
  - `Mono<IncrementalSyncResult> 락_안에서_없앤다(String orgCode)` — 지금 `clearParent` 본문

- [ ] **Step 1: 실패하는 시험을 쓴다 — `IncrementalSyncParentAssignmentTest`**

"락을 시도하지 않았다" 는 `lock.시도한_토큰` 으로 본다(미리 쥔 락도 `acquired` 를 올리기 때문이다). 새 시험:

```java
@Test
@DisplayName("같은 상위로 다시 지정하고 줄도 있으면 락을 시도하지 않고 끝난다 — Check 는 그 줄 하나다")
void 같은_지정이고_줄이_있으면_락_없이_끝난다() {
    // given
    state.assignedParents.put("C", "P");
    checker.allowed.add(RelationTuple.child("C", "P"));

    // when
    IncrementalSyncResult result = 지정한다("C", "P");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.시도한_토큰).isEmpty();
    assertThat(writer.appliedDeltas).isEmpty();
    assertThat(auditLog.events).isEmpty();
    assertThat(checker.checked).containsExactly(RelationTuple.child("C", "P"));
}

@Test
@DisplayName("다른 요청이 락을 쥔 동안에도 바뀐 것 없는 지정은 기다리지 않고 끝난다")
void 다른_요청이_락을_쥔_동안에도_같은_지정은_끝난다() {
    // given — 획득 한도가 0 이라 락을 시도하면 곧바로 LockUnavailableException 이다
    state.assignedParents.put("C", "P");
    checker.allowed.add(RelationTuple.child("C", "P"));
    lock.acquire(MutationLock.LockPurpose.WRITE, "다른-요청", null).block();

    // when
    IncrementalSyncResult result = 지정한다("C", "P");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.시도한_토큰).containsExactly("다른-요청");
}

@Test
@DisplayName("다른 상위로 바꾸는 요청은 앞 확인에서 Check 하지 않고 락 안으로 간다")
void 다른_상위면_앞_확인이_Check_하지_않는다() {
    // given
    state.assignedParents.put("C", "P0");
    checker.allowed.add(RelationTuple.child("C", "P0"));

    // when
    지정한다("C", "P");

    // then — 락 안의 Check 기준선이 child(C,P) 를 한 번 본다. 앞 확인이 보면 두 번이다
    assertThat(lock.acquired).hasValue(1);
    assertThat(checker.checked).containsOnlyOnce(RelationTuple.child("C", "P"));
}

@Test
@DisplayName("지정이 없는 조직을 가리키면 앞 확인이 넘기고 지금처럼 상위 없음으로 거절한다")
void 지정이_없는_조직을_가리키면_지금처럼_거절한다() {
    // given
    state.assignedParents.put("C", "GONE");
    checker.allowed.add(RelationTuple.child("C", "GONE"));

    // when
    HierarchyRejectedException 거절 = 거절("C", "GONE");

    // then
    assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.PARENT_NOT_FOUND);
    assertThat(lock.acquired).hasValue(1);
}

@Test
@DisplayName("앞 확인의 Check 가 실패하면 락 안에서 지금처럼 끝낸다")
void 앞_확인의_Check_가_실패하면_락_안에서_끝낸다() {
    // given — 첫 Check 만 실패한다
    state.assignedParents.put("C", "P");
    checker.allowed.add(RelationTuple.child("C", "P"));
    AtomicBoolean 처음 = new AtomicBoolean(true);
    checker.failFor(tuple -> 처음.getAndSet(false));

    // when
    IncrementalSyncResult result = 지정한다("C", "P");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.acquired).hasValue(1);
    assertThat(writer.appliedDeltas).isEmpty();
}

@Test
@DisplayName("앞 확인의 지정 읽기가 실패하면 락 안에서 지금처럼 끝낸다")
void 앞_확인의_지정_읽기가_실패하면_락_안에서_끝낸다() {
    // given
    state.assignedParents.put("C", "P");
    checker.allowed.add(RelationTuple.child("C", "P"));
    state.findAssignedParentFailsOnce.add("C");

    // when
    IncrementalSyncResult result = 지정한다("C", "P");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.acquired).hasValue(1);
}

@Test
@DisplayName("지정이 없는 조직의 지정을 없애면 락을 시도하지 않고 끝난다")
void 지정이_없으면_없애기는_락_없이_끝난다() {
    // when
    IncrementalSyncResult result = 없앤다("C");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.시도한_토큰).isEmpty();
}

@Test
@DisplayName("지정이 없는 조직을 가리키면 없애기는 락 없이 끝나고 그 지정 줄은 지금처럼 그대로다")
void 지정이_없는_조직을_가리키면_없애기는_락_없이_끝난다() {
    // given
    state.assignedParents.put("C", "GONE");

    // when
    IncrementalSyncResult result = 없앤다("C");

    // then
    assertThat(result.fullyApplied()).isTrue();
    assertThat(lock.시도한_토큰).isEmpty();
    assertThat(state.assignedParents).containsEntry("C", "GONE");
}
```

지금 시험 둘에 단언을 더한다: `같은_지정인데_줄이_없으면_쓴다` 에 `assertThat(lock.acquired).hasValue(1);`, `없애면_줄을_지운다` 에 `assertThat(lock.acquired).hasValue(1);`.

- [ ] **Step 2: 실패하는 e2e 시험을 쓴다 — `ScimHierarchyEndToEndTest`**

`@Autowired MutationLock lock;` 을 더한다(`ScimErrorSignalsEndToEndTest` 와 같은 방식). 지금 `@Order(9)` 인 wipe 시험을 `@Order(10)` 으로 미루고 새 시험을 `@Order(9)` 로 둔다. `@Order(7)` 이 DIV 를 지웠으므로 상위 조직을 새로 만든다.

```java
@Test
@Order(9)
@DisplayName("재적재가 락을 쥔 동안에도 바뀐 것 없는 지정과 지정 없는 조직의 DELETE 는 204 이고, 바꾸는 지정은 503 이다")
void 재적재가_락을_쥔_동안에도_바뀐_것_없는_지정은_204_다() {
    // given — TEAM 을 DIV4 에 건다
    빈_조직을_만든다("DIV4", "전략본부4");
    빈_조직을_만든다("DIV5", "전략본부5");
    지정한다(서버("TEAM"), 서버("DIV4")).exchange().expectStatus().isNoContent();
    var lease = lock.acquireOnce(MutationLock.LockPurpose.REBUILD).block(Duration.ofSeconds(10));
    try {
        // when, then — 바뀐 것이 없으면 락을 기다리지 않는다
        지정한다(서버("TEAM"), 서버("DIV4")).exchange().expectStatus().isNoContent();
        client.delete().uri("/admin/organizations/{orgCode}/parent", 서버("DIV4")).exchange()
                .expectStatus().isNoContent();
        // 바꾸는 지정은 지금처럼 락을 기다리지 않고 503 이다(쥔 쪽이 재적재라 60초)
        지정한다(서버("TEAM"), 서버("DIV5")).exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60");
    } finally {
        lock.release(lease).block(Duration.ofSeconds(10));
    }

    // when, then — 락이 풀리면 바꾸는 지정도 된다
    지정한다(서버("TEAM"), 서버("DIV5")).exchange().expectStatus().isNoContent();
    assertThat(state.findAssignedParent(서버("TEAM")).blockOptional()).contains(서버("DIV5"));
}
```

- [ ] **Step 3: 시험이 실패하는지 본다**

Run: `./gradlew :core:test --tests "dev.starryeye.organization.core.usecase.IncrementalSyncParentAssignmentTest"`
Expected: 여섯이 FAIL 이다.
- `시도한_토큰` 이 비지 않아서: `같은_지정이고_줄이_있으면_락_없이_끝난다`, `지정이_없으면_없애기는_락_없이_끝난다`, `지정이_없는_조직을_가리키면_없애기는_락_없이_끝난다`
- `LockUnavailableException` 이라서: `다른_요청이_락을_쥔_동안에도_같은_지정은_끝난다`
- 주입한 실패를 락 안의 길이 받아서: `앞_확인의_Check_가_실패하면_락_안에서_끝낸다`, `앞_확인의_지정_읽기가_실패하면_락_안에서_끝낸다`
`다른_상위면_앞_확인이_Check_하지_않는다`·`지정이_없는_조직을_가리키면_지금처럼_거절한다` 는 지금도 PASS 다(잘못 구현하면 깨지는 시험이다).

Run: `./gradlew :app-scim:test --tests "dev.starryeye.organization.scim.app.ScimHierarchyEndToEndTest"`(Docker 필요)
Expected: 새 시험이 FAIL — 락을 쥔 동안의 첫 PUT 이 503 이다.

- [ ] **Step 4: 앞 확인을 구현한다**

- `assignParent` 는 `이미_지정돼_있다` 가 참이면 `Mono.just(IncrementalSyncResult.noChange())`, 아니면 `락_안에서_지정한다`.
- `clearParent` 는 `지정이_없다` 가 참이면 `noChange()`, 아니면 `락_안에서_없앤다`.
- `이미_지정돼_있다`: 대상 이름표가 있고 → `state.findAssignedParent(orgCode)` 가 `parentOrgCode` 와 같고 → `state.findGroupHeader(parentOrgCode)` 가 있고 → `checker.check(RelationTuple.child(orgCode, parentOrgCode))` 가 참. 빈 값은 거짓이다. 대상 이름표와 지정은 함께 읽어도 된다. P 이름표와 Check 는 지정이 P 일 때만이다(Global Constraints 의 순서).
- `지정이_없다`: 대상 이름표가 있고 `지금_지정(orgCode)` 가 빈 값이다(지금 `clearParent` 와 같은 뜻).
- 둘 다 오류를 `onErrorResume` 으로 거짓으로 바꾸고, DEBUG 로 "지정 앞 확인이 실패했다 — 락 안의 길로 간다: 조직 {}" 를 남긴다(오류 객체도 함께).
- 자바독: 공개 메서드에 앞 확인(조건, 락 없이 끝나는 경우, 그 밖은 지금 길, 설계 2026-11-13 L1~L5)을 적는다. 락 없이 읽어도 되는 까닭은 한두 줄로 — 앞 확인은 쓰지 않고, 읽기는 강한 일관성이며, 저장된 값을 먼저 읽고 Check 를 마지막에 한다(설계 §3.3). 클래스 자바독의 "같은 락·Check 기준선·diff·이력으로 반영한다" 문장에 "바뀐 것이 없으면 락 없이 끝난다" 를 더한다.

- [ ] **Step 5: 시험이 통과하는지 본다**

Run: Step 3 의 두 명령을 차례로
Expected: 둘 다 PASS(e2e 는 10개).

- [ ] **Step 6: core 모듈 시험 전체를 돌린다**

Run: `./gradlew :core:test`
Expected: PASS. 실패하는 지금 시험이 "같은 상위·줄 있음에서 락을 기대" 하는 것이면, 줄이 없는 경우로 바꾸거나 기대를 고치고 보고서에 적는다(설계 §11).

- [ ] **Step 7: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncParentAssignmentTest.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimHierarchyEndToEndTest.java
git commit -m "feat: 같은 상위로 다시 건 지정과 지정 없는 조직의 지정 없애기를 락 없이 끝낸다 — 그 밖은 지금처럼 락 안에서 (락 없는 지정)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 3: 커밋 자리 경계 시험 둘 (T1)

**Files:**
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` — `changeCutEdges`(약 301행)와 공개 필드
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedCountTest.java`

**Interfaces:**
- Produces:
  - `FakeStateRepository.failCutEdgesAdding` — `public volatile RuntimeException`. 설정하면 `changeCutEdges` 가 `added` 가 비지 않을 때 아무것도 바꾸지 않고 이 예외로 끝난다.
  - `FakeStateRepository.failCutEdgesRemoving` — 같은 것을 `removed` 가 비지 않을 때.

- [ ] **Step 1: 가짜 저장소에 주입을 더한다**

`changeCutEdges` 를 `Mono.defer` 로 감싸 두 필드를 먼저 본다. 둘 다 비었으면 지금 동작 그대로다.

- [ ] **Step 2: 시험을 쓴다 — `IncrementalSyncUnsourcedCountTest`**

이 클래스의 준비: 장부에 `dm(u-1,G)`·`child(C,A)`, C 의 지정 상위는 A, 조직 G(멤버 u-1)·A·B·C·D.

```java
@Test
@DisplayName("앞쪽 보류 쓰기가 실패하면 센다 — 커밋 자리 전이다")
void 앞쪽_보류_쓰기가_실패하면_센다() {
    // given — C 의 상위는 A 라 C 에 A 를 넣으면 순환이고 새로 보류한다. u-2 의 줄은 그 전에 쓴다
    state.failCutEdgesAdding = new IllegalStateException("보류 쓰기 실패(테스트)");

    // when
    var 요청 = IdP_로(useCase.changeGroup("C",
            GroupChange.delta().adding(Set.of(MemberRef.user("u-2"), MemberRef.group("A")))));

    // then
    assertThatThrownBy(요청::block).hasMessageContaining("보류 쓰기 실패");
    assertThat(writer.stored).contains(dm("u-2", "C"));
    assertThat(state.cutEdges).isEmpty();
    assertThat(남긴_요청).hasValue(1);
}

@Test
@DisplayName("커밋과 되지우기를 마친 뒤 뒤쪽 보류 목록 정리가 실패하면 세지 않는다")
void 뒤쪽_보류_목록_정리가_실패하면_세지_않는다() {
    // given — 보류 목록에 장부에 이미 있는 줄 A ⊃ C 가 남아 있다. G 에 D 를 넣어 하위 조직 연결이 생기면 목록을 읽고 그 줄을 뺄 것에 넣는다.
    //         u-2 의 넣기는 응답을 잃어 저장하지 않고 되지운다 — 경계를 옮기면 이 줄 때문에 센다
    state.cutEdges.add(new GroupEdge("A", "C"));
    writer.lostResponseFor(본_쓰기의(dm("u-2", "G")));
    state.failCutEdgesRemoving = new IllegalStateException("보류 정리 실패(테스트)");

    // when
    var 요청 = IdP_로(useCase.changeGroup("G",
            GroupChange.delta().adding(Set.of(MemberRef.user("u-2"), MemberRef.group("D")))));

    // then
    assertThatThrownBy(요청::block).hasMessageContaining("보류 정리 실패");
    assertThat(state.groups.get("G").members()).contains(MemberRef.group("D")).doesNotContain(MemberRef.user("u-2"));
    assertThat(writer.stored).doesNotContain(dm("u-2", "G"));
    assertThat(state.cutEdges).contains(new GroupEdge("A", "C"));
    assertThat(남긴_요청).hasValue(0);
}
```

- [ ] **Step 3: 시험이 통과하는지 본다**

Run: `./gradlew :core:test --tests "dev.starryeye.organization.core.usecase.IncrementalSyncUnsourcedCountTest"`
Expected: PASS(지금 코드가 이미 경계를 지킨다 — 이 과제는 경계를 고정하는 시험이다).

- [ ] **Step 4: 경계를 옮겨 두 시험이 깨지는지 확인하고 되돌린다(커밋하지 않는다)**

`IncrementalSyncUseCase.diffAndApply` 의 `마치면_알린다` 를 잠시 고쳐 본다.
1. 앞쪽: `보류와_함께_커밋한다` 를 부르기 전에 `추적.커밋을_마쳤다()` 를 부르게 한다 → `앞쪽_보류_쓰기가_실패하면_센다` 가 FAIL(0) 이어야 한다.
2. 뒤쪽: `커밋을_마쳤다` 를 `보류와_함께_커밋한다(...)` 전체 뒤로 옮긴다 → `뒤쪽_보류_목록_정리가_실패하면_세지_않는다` 가 FAIL(1) 이어야 한다.
각각 Step 3 의 명령으로 확인한 뒤 손으로 되돌리고, `git diff -- core/src/main` 이 빈 것을 본다. 하나라도 깨지지 않으면 시나리오를 고친다(설계 §4.2 의 "응답을 잃은 넣기가 꼭 있어야 한다").

- [ ] **Step 5: core 모듈 시험 전체를 돌린다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋한다**

```bash
git add core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUnsourcedCountTest.java
git commit -m "test: 앞쪽 보류 쓰기의 실패는 세고 뒤쪽 보류 목록 정리의 실패는 세지 않는다는 커밋 자리 경계를 고정한다 (락 없는 지정)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 4: README 와 온보딩을 코드대로 고친다 (L7)

**Files:**
- Modify: `README.md`, `docs/onboarding/02-용어집.md`, `06-SCIM.md`, `08-동시성과-작업.md`, `09-관리-API.md`, `11-운영.md`, `12-장애와-복구.md`, `13-한계와-알려진-문제.md`, `15-결정-색인.md`

**Interfaces:**
- Consumes: Task 1~3 의 동작. 설계 §2·§3.

- [ ] **Step 1: 고칠 자리를 모두 찾는다**

Run(각각 따로): `grep -n "바뀐 것이 없는 PUT" README.md docs/onboarding/*.md`, `grep -n "assignParent" README.md docs/onboarding/*.md`, `grep -n "되지우기 식 밖" docs/onboarding/*.md`, `grep -n "저장하는 커밋" docs/onboarding/*.md`
알려진 자리(이것만이 아닐 수 있다):
- README 지정 절 4(약 657행), 09 §4.4 의 4(약 311행), 11 §9.8(약 893행), 12(약 472행), 13 §2.2(약 120행) — "바뀐 것이 없는 PUT 도 전역 쓰기 락을 잡고 … 속도를 조절하거나 먼저 비교" 를 바꾼다. 바뀐 것 없는 요청(같은 상위·줄 있음, 지정 없는 DELETE)은 락 없이 읽기 셋(DELETE 는 둘)과 Check 하나로 204 이고, 빠진 줄이 있으면 락 안에서 고친다. 그래서 먼저 비교할 까닭이 없다. 다만 동시에 많이 보내지 말고 차례로(또는 작은 동시성으로) 보낸다 — Check 가 IdP 쓰기와 같은 OpenFGA 에 몰린다(설계 §3.5).
- 06 약 49행(지정 요청도 같은 뼈대) — 바뀐 것이 없으면 락 없이 끝나는 앞 확인을 적는다. 06 §6.10 의 `assignParent`·`clearParent` 행(약 894행) — 앞 확인의 읽기를 적는다. 06 약 827행(저장하는 커밋의 되지울 집합) — L8 을 더한다.
- 08 약 168행(입구 `withLock` 을 타는 요청 목록) — 지정 요청 둘은 앞 확인이 "이미 그렇다" 면 락을 잡지 않는다.
- 12 §5.7 — 재적재 중에도 바뀐 것 없는 지정은 204 다(설계 §3.4).
- 13 §2.7 "알려진 어긋남 — 되지우기 식 밖의 경우"(약 171-175행) — L8 로 고쳤다. 남은 것은 같은 드리프트에서 커밋 전에 끝난 PATCH 의 U5 (a) 거짓 셈 하나다(설계 §2 고치지 않기로 한 것).
- 02 — 용어 "지정의 앞 확인"을 더하고(무엇·조건·그 밖은 지금 길·코드 `IncrementalSyncUseCase.이미_지정돼_있다`·`지정이_없다`), 원천 없는 줄 항목에 지정이 받치는 하위 조직은 되지우지 않는다는 것을 더한다. 06·08 의 새 문장이 이 용어에 링크한다.
- 15 — `## 원천 없는 줄(U1~U8)` 뒤에 `## 락 없는 지정(L1~L8, T1)` 절을 더한다. 형식은 U 절과 같다(설계 §2 의 결정 표, 버린 길은 설계 §7, 동작 문서 링크, 고치지 않기로 한 것 표). U4 행에 "저장하는 커밋은 지정이 받치는 하위 조직의 줄도 뺀다(L8)" 를 더한다.

- [ ] **Step 2: 고친다**

각 문장은 지금 코드 동작이다. 근거 설계 링크는 `../superpowers/specs/2026-11-13-lockless-noop-assignment-design.md`.

- [ ] **Step 3: 고친 온보딩 문서의 기준 커밋 줄을 바꾼다**

이 브랜치의 마지막 코드 커밋(Task 3 의 커밋)을 `git log --oneline -5` 로 찾아, 고친 온보딩 문서 맨 위의 `> 기준 커밋:` 줄을 `> 기준 커밋: <그 해시> (lockless-noop-assignment, main 에 머지됨). …` 로 바꾼다(뒤 문장은 그대로).

- [ ] **Step 4: 링크가 살아 있는지 본다**

Run: `grep -n "lockless-noop-assignment-design" README.md docs/onboarding/*.md`
Expected: 새로 넣은 링크가 모두 같은 파일 이름을 가리킨다. 문서 안 앵커(`#…`)를 새로 걸었으면 그 제목이 있는지 grep 으로 본다.

- [ ] **Step 5: 커밋한다**

```bash
git add README.md docs/onboarding/02-용어집.md docs/onboarding/06-SCIM.md docs/onboarding/08-동시성과-작업.md docs/onboarding/09-관리-API.md docs/onboarding/11-운영.md docs/onboarding/12-장애와-복구.md docs/onboarding/13-한계와-알려진-문제.md docs/onboarding/15-결정-색인.md
git commit -m "docs: 바뀐 것 없는 지정의 락 없는 앞 확인과 지정이 받치는 하위 조직의 되지우기를 README 와 온보딩에 반영한다 (락 없는 지정)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

## 과제 뒤 (메인 세션)

- 전체 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로 돌린다.
- 설계 §9(결과)를 채운다 — 검증 수, 바뀐 곳(커밋 표), 구현 중 판정, 최종 리뷰와 수정, 남긴 것.

## 계획의 판정

- **설계 §4.3 의 L8 시험 "카운터 0" 은 넣지 않는다.** L8 이 없어도 되지우기가 성공해 카운터는 0 이라, 그 단언은 L8 을 가르지 못한다. 가르는 단언은 장부에 줄이 남는 것이다. 대신 L8 의 읽기 실패를 세는 시험(Review Focus 4)을 넣었다.
- **L8 의 지정 읽기는 저장 전이다.** 읽기가 실패하면 저장하지 않은 커밋 오류가 된다 — 지금 "넣기를 쓴 뒤 조직 저장이 실패하면 센다" 와 같은 길이다.
- **e2e 는 Task 2 에 넣는다.** 앞 확인의 결과를 실제 락·DynamoDB·OpenFGA 로 보는 시험이라, 따로 두면 리뷰가 같은 코드를 두 번 본다.
