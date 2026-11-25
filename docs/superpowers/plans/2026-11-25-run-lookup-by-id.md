# 실행 기록 단건 조회를 보관 기간 전체로 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실행 id 에 시작 시각을 싣고, 단건 조회가 id 에서 키를 다시 만들어 GetItem 한 번으로 보관 기간 안의 어느 기록이든 찾게 한다.

**Architecture:** storage-dynamodb 의 `Keys` 가 실행 id 를 만들고 푼다(형식은 저장소 안의 일, core 는 불투명한 문자열). `DynamoDbSyncRunRepository.start` 는 시계를 한 번 읽어 id 와 `startedAt` 에 쓰고, `findById` 는 id 를 엄격히 검사한 뒤 PK(`SYNCRUN#<그 달>`)·SK(`<시작 시각 밀리초>#<runId>`)로 GetItem 한다. 목록·실행 중 조회는 그대로다.

**Tech Stack:** Java 17, DynamoDB SDK v2(DynamoDB Local, Testcontainers), Reactor, JUnit5·AssertJ, Spring WebFlux `WebTestClient`(관리 API 슬라이스).

**Spec:** `docs/superpowers/specs/2026-11-25-run-lookup-by-id-design.md`

## Global Constraints

- 실행 id 는 정확히 `<시작 시각>-<UUID>` 다. 시작 시각은 UTC 의 `uuuuMMdd'T'HHmmss.SSS'Z'`(밀리초, 예 `20261125T135329.645Z`), UUID 는 `UUID.toString()`(소문자 36자). 예: `20261125T135329.645Z-3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13`.
- 받는 모양은 정규식 `^\d{8}T\d{6}\.\d{3}Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` 하나이고, 시각 부분은 `ResolverStyle.STRICT` 로 풀려야 한다. 그 밖은 DynamoDB 를 부르지 않고 빈 결과다.
- `start` 는 시계를 **한 번** 읽어 그 `Instant` 로 id 와 `startedAt` 을 함께 만든다.
- 단건 조회는 GetItem 한 번이다(Query 없음). 읽기 일관성은 지금처럼 최종 일관성(`consistentRead` 를 켜지 않는다).
- `findRecent`·`findRunning`·`abandon`·`finish`·저장 아이템 모양(PK·SK·속성)은 바꾸지 않는다. SK 는 지금처럼 `Keys.syncRunSk(startedAt, runId)` 다.
- core 는 `runId` 를 해석하지 않는다. 새 지표·새 설정은 없다.
- 시험 메서드 이름·`@DisplayName` 은 한국어, 몸통은 `// given` / `// when` / `// then`. 새 시험 위에는 `// 깨뜨릴 변경: …` 한 줄을 단다.
- 시험은 벽시계 시각을 단언하지 않는다(고정 시계만 쓴다).
- Gradle 은 한 번에 하나만 돌린다. 과제 시험은 모듈·클래스 단위로만 돌린다(전체 `test`·`scaleTest` 는 메인 세션이 머지 전에 돌린다).
- `git stash` 를 쓰지 않는다. git 명령은 `&&`·`$(...)` 없이 하나씩 쓴다.
- 온보딩 문서는 고친 문서만 머리의 `기준 커밋:` 을 이 슬라이드의 마지막 코드 커밋(과제 1 의 커밋)으로 올린다 — 꼴 `기준 커밋: <해시7> (run-lookup-by-id, main 에 머지됨)`.

## Review Focus

- **시계가 밀리초보다 잘게 주는 시각**(Java 17 의 `Clock.systemUTC()` 는 마이크로초를 준다). id 의 시각과 SK 의 시각이 같은 방식(밀리초까지 자름)이어야 다시 만든 SK 가 저장된 SK 와 같다 — 과제 1 의 `KeysTest` 가 마이크로초 시각으로 못박는다.
- **점(`.`)이 든 id 가 경로 변수로 끝까지 들어오는지.** `GET /admin/sync/runs/{runId}` 가 `.645Z-…` 를 확장자로 자르면 404 다 — 과제 1 의 app-ldap 컨트롤러 시험이 새 모양 id 로 200 을 본다(두 앱 모두 경로 매칭 설정이 없어 WebFlux 기본을 쓴다).
- **달 경계.** id 의 시각과 PK 의 달은 둘 다 UTC 다. 7월 31일 23:59 UTC 에 시작한 기록은 7월 파티션이다 — 기존 시험 `지난달_기록도_찾는다` 가 그대로 지킨다.
- **모양은 맞는데 없는 id.** 시각이 같은 다른 UUID 는 빈 결과여야 한다(SK 에 runId 가 든다) — 과제 1 시험.
- **이상한 id 가 DynamoDB 오류가 되지 않는지.** 키 길이 한도(1024바이트)를 넘는 문자열·풀리지 않는 날짜(13월, 2월 30일)는 DynamoDB 를 부르지 않고 빈 결과 — 과제 1 시험.

---

### Task 1: storage-dynamodb — 실행 id 에 시작 시각을 싣고 단건 조회를 GetItem 으로 (I1~I6)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (실행 기록 키 도우미 곁, ~:428-439)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java` (`start` ~:68-71, `findById` ~:176-189 와 그 Javadoc)
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java` (`findById` Javadoc — 주석만)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java`, `app-scim/src/main/java/dev/starryeye/organization/scim/app/AdminSyncController.java` (`run` 의 주석 "보관 기간(30일)" → "보관 기간(`dynamodb.syncrun-retention-days`)" — 주석만)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java`

**Interfaces:**
- Produces: `public static String Keys.runId(Instant startedAt, UUID random)` — Global Constraints 의 모양. `public static Optional<Instant> Keys.runIdStartedAt(String runId)` — 모양·STRICT 풀기가 맞으면 시작 시각(밀리초), 아니면(`null` 포함) `Optional.empty()`.
- `SyncRunRepository` 의 시그니처는 그대로다.

- [ ] **Step 1: `KeysTest` 에 실패할 시험을 쓴다**

```java
// 깨뜨릴 변경: 실행 id 의 시각 모양을 바꾸거나 SK 와 다른 정밀도로 자르기(설계 2026-11-25 I1)
@Test
@DisplayName("실행 id 는 UTC 시작 시각(밀리초)과 UUID 로 만들고, id 에서 시작 시각을 되살려 같은 SK 를 만든다")
void 실행_id_는_시작_시각과_UUID_로_만들고_시각을_되살린다() {
    // given — 시계가 마이크로초까지 준 시각
    Instant 시작 = Instant.parse("2026-11-25T13:53:29.645123Z");
    UUID 무작위 = UUID.fromString("3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13");

    // when
    String runId = Keys.runId(시작, 무작위);
    Optional<Instant> 되살린_시각 = Keys.runIdStartedAt(runId);

    // then
    assertThat(runId).isEqualTo("20261125T135329.645Z-3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13");
    assertThat(되살린_시각).contains(Instant.parse("2026-11-25T13:53:29.645Z"));
    assertThat(Keys.syncRunSk(되살린_시각.get(), runId)).isEqualTo(Keys.syncRunSk(시작, runId));
    assertThat(Keys.syncRunPk(되살린_시각.get())).isEqualTo(Keys.syncRunPk(시작));
}

// 깨뜨릴 변경: 모양 검사를 느슨하게 하거나 날짜를 너그럽게 풀기(설계 2026-11-25 I2)
@Test
@DisplayName("모양이 다르거나 풀리지 않는 실행 id 에서는 시작 시각을 얻지 못한다")
void 모양이_다른_실행_id_는_시각이_없다() {
    String uuid = "3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13";
    assertThat(Keys.runIdStartedAt(null)).isEmpty();
    assertThat(Keys.runIdStartedAt("")).isEmpty();
    assertThat(Keys.runIdStartedAt(uuid)).isEmpty();
    assertThat(Keys.runIdStartedAt("20261340T000000.000Z-" + uuid)).as("13월").isEmpty();
    assertThat(Keys.runIdStartedAt("20260230T000000.000Z-" + uuid)).as("2월 30일").isEmpty();
    assertThat(Keys.runIdStartedAt("20261125T135329.645Z-" + uuid.toUpperCase())).as("대문자 UUID").isEmpty();
    assertThat(Keys.runIdStartedAt("20261125T135329.645Z-" + uuid + "x")).isEmpty();
    assertThat(Keys.runIdStartedAt("x".repeat(1100))).isEmpty();
}
```

- [ ] **Step 2: `DynamoDbSyncRunRepositoryTest` 에 실패할 시험을 쓴다**

```java
// 깨뜨릴 변경: start 가 무작위 UUID 만 쓰거나 시계를 두 번 읽기(설계 2026-11-25 I1)
@Test
@DisplayName("시작한 기록의 번호에는 UTC 시작 시각(밀리초)이 실린다")
void 시작한_기록의_번호에는_시작_시각이_실린다() {
    // given, when
    var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED, 요청자).block();

    // then
    assertThat(run.runId()).matches("20260814T030000\\.000Z-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(Keys.runIdStartedAt(run.runId())).contains(run.startedAt());
}

// 깨뜨릴 변경: 단건 조회가 이번 달·지난달 파티션만 보기(설계 2026-11-25 §1 — 고치려는 한계)
@Test
@DisplayName("보관 기간을 늘린 배포에서 석 달 전에 시작한 기록도 번호로 찾는다")
void 석_달_전_기록도_번호로_찾는다() {
    // given — 감사 때문에 보관을 1년으로 늘린 배포. 5월에 시작해 끝난 기록
    properties.setSyncrunRetentionDays(365);
    Instant 석_달_전 = Instant.parse("2026-05-10T12:34:56.789Z");
    var 석_달_전_저장소 = new DynamoDbSyncRunRepository(client, properties, Clock.fixed(석_달_전, ZoneOffset.UTC));
    var run = 석_달_전_저장소.start(SyncSource.LDAP, SyncTrigger.SCHEDULED, 요청자).block();
    석_달_전_저장소.finish(run, SyncOutcome.noChange()).block();

    // when — 8월 14일에 찾는다
    var found = repository.findById(run.runId()).block();

    // then
    assertThat(found).isNotNull();
    assertThat(found.runId()).isEqualTo(run.runId());
    assertThat(found.startedAt()).isEqualTo(석_달_전);
    assertThat(found.status()).isNotEqualTo(SyncStatus.RUNNING);
}

// 깨뜨릴 변경: 단건 조회가 파티션을 Query 로 훑기(설계 2026-11-25 I2)
@Test
@DisplayName("번호 조회는 Query 없이 GetItem 한 번만 부른다")
void 번호_조회는_아이템_하나만_읽는다() {
    // given
    var run = repository.start(SyncSource.LDAP, SyncTrigger.MANUAL, 요청자).block();
    Map<String, AtomicInteger> 호출 = new ConcurrentHashMap<>();
    var 세는_저장소 = new DynamoDbSyncRunRepository(호출을_세는_클라이언트(호출), properties, Clock.fixed(지금, ZoneOffset.UTC));

    // when
    var found = 세는_저장소.findById(run.runId()).block();

    // then
    assertThat(found.runId()).isEqualTo(run.runId());
    assertThat(호출).containsOnlyKeys("getItem");
    assertThat(호출.get("getItem")).hasValue(1);
}

// 깨뜨릴 변경: SK 에서 runId 를 빼고 시각만으로 찾기(설계 2026-11-25 I1·I2)
@Test
@DisplayName("시각은 같고 UUID 가 다른 번호는 빈 결과다")
void 모양은_맞지만_없는_번호는_빈_결과다() {
    // given
    var run = repository.start(SyncSource.LDAP, SyncTrigger.MANUAL, 요청자).block();
    String 다른_번호 = Keys.runId(run.startedAt(), UUID.fromString("00000000-0000-4000-8000-000000000000"));

    // when, then
    assertThat(repository.findById(다른_번호).blockOptional()).isEmpty();
}

// 깨뜨릴 변경: 모양 검사 없이 DynamoDB 에 키를 보내기 — 긴 문자열은 키 길이 한도로 오류다(설계 2026-11-25 I2)
@Test
@DisplayName("모양이 다른 번호는 DynamoDB 를 부르지 않고 빈 결과다")
void 모양이_다른_번호는_DynamoDB_를_부르지_않는다() {
    // given
    Map<String, AtomicInteger> 호출 = new ConcurrentHashMap<>();
    var 세는_저장소 = new DynamoDbSyncRunRepository(호출을_세는_클라이언트(호출), properties, Clock.fixed(지금, ZoneOffset.UTC));

    // when, then
    for (String 번호 : List.of("3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13", "20261340T000000.000Z-3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13", "x".repeat(1100))) {
        assertThat(세는_저장소.findById(번호).blockOptional()).as(번호.length() > 60 ? "긴 문자열" : 번호).isEmpty();
    }
    assertThat(호출).isEmpty();
}
```

`호출을_세는_클라이언트(Map<String, AtomicInteger>)` 는 이 시험 파일에 더하는 private 도우미다 — `java.lang.reflect.Proxy` 로 `DynamoDbAsyncClient` 를 감싸, `serviceName`·`close` 를 뺀 모든 호출의 메서드 이름을 세고 진짜 `client` 로 보낸다(`DynamoDbTupleSnapshotRepositoryTest` 의 `지우기_호출을_세는_클라이언트` 와 같은 꼴).

기존 `시작하면_RUNNING으로_기록된다`·`지난달_기록도_찾는다`·`없는_번호는_빈_결과다` 는 그대로 둔다.

- [ ] **Step 3: app-ldap `AdminSyncControllerTest` 에 시험 하나를 쓴다**

```java
// 깨뜨릴 변경: 경로 변수의 점 뒤를 확장자로 잘라 저장소에 앞부분만 넘기기(설계 2026-11-25 I1 — 새 id 에는 점이 든다)
@Test
@DisplayName("점이 든 새 모양의 실행 id 도 경로에서 잘리지 않고 저장소에 그대로 간다")
void 점이_든_실행_id_도_그대로_찾는다() {
    // given
    String 새_번호 = "20261125T135329.645Z-3f2a9c1e-0b7d-4c8e-9a51-2d6f0e4b7a13";
    Mockito.when(runs.findById(새_번호))
            .thenReturn(Mono.just(완료된실행(SyncTrigger.SCHEDULED, SyncStatus.SUCCEEDED)));

    // when, then
    client.get().uri("/admin/sync/runs/" + 새_번호).exchange()
            .expectStatus().isOk();
    Mockito.verify(runs).findById(새_번호);
}
```

(인증이 필요한 슬라이스면 같은 파일의 단건 조회 시험이 쓰는 토큰·`mutateWith` 를 그대로 따른다.)

- [ ] **Step 4: 시험이 실패하는지 본다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.KeysTest' --tests 'dev.starryeye.organization.storage.DynamoDbSyncRunRepositoryTest'`
Expected: 컴파일 실패(`Keys.runId`·`Keys.runIdStartedAt` 없음). 도우미 두 개의 빈 껍데기(`throw new UnsupportedOperationException()`)를 먼저 두면 새 시험들이 FAIL 한다.

Run: `./gradlew :app-ldap:test --tests 'dev.starryeye.organization.ldap.app.AdminSyncControllerTest'`
Expected: 새 시험은 지금도 PASS 일 수 있다(WebFlux 는 기본으로 확장자를 자르지 않는다) — 경계를 못박는 시험이라 괜찮다. 결과를 보고서에 적는다.

- [ ] **Step 5: 구현한다**

- `Keys`: 포맷터 하나(`uuuuMMdd'T'HHmmss.SSS'Z'`, `withZone(ZoneOffset.UTC)`, `withResolverStyle(ResolverStyle.STRICT)`)와 정규식 하나(Global Constraints)를 상수로 두고 `runId`·`runIdStartedAt` 을 만든다. `runIdStartedAt` 은 정규식이 맞을 때만 시각 부분(처음 20자)을 풀고, `DateTimeParseException` 이면 빈 값이다. Javadoc 한 줄: "실행 id 는 시작 시각을 싣는다 — 단건 조회가 id 에서 키를 다시 만든다(설계 2026-11-25 I1·I2)".
- `DynamoDbSyncRunRepository.start`: `Instant 지금 = clock.instant();` 한 번 읽고 `SyncRun.started(Keys.runId(지금, UUID.randomUUID()), source, trigger, 지금, requestedBy)`.
- `DynamoDbSyncRunRepository.findById`: `Keys.runIdStartedAt(runId)` 가 비면 `Mono.empty()`(DynamoDB 를 부르지 않는다). 있으면 `GetItemRequest`(테이블, 키 `PK = Keys.syncRunPk(시작)`, `SK = Keys.syncRunSk(시작, runId)`, `consistentRead` 없음)를 `Mono.fromFuture(() -> client.getItem(...))` 로 보내고, 아이템이 있을 때만 `toRun` 으로 바꾼다. Javadoc 을 "id 에서 키를 다시 만들어 GetItem 한 번 — 보관 기간 안이면 몇 달 전 것도 찾는다. 모양이 다른 id 는 DynamoDB 를 부르지 않고 빈 결과(설계 2026-11-25 I2)" 의 뜻으로 고친다(두 달·`LastSuccessGauge` 이야기는 `findRecent` 쪽 Javadoc 에 이미 있으면 빼고, 없으면 그 문장만 남긴다).
- `SyncRunRepository.findById` Javadoc: "보관 기간(`syncrun-retention-days`) 안이면 몇 달 전 것도 찾는다" 를 더한다.
- 두 `AdminSyncController.run` 의 주석: "보관 기간(30일)" → "보관 기간(`dynamodb.syncrun-retention-days`)".

- [ ] **Step 6: 시험이 통과하는지 본다**

Run: Step 4 의 두 명령을 차례로.
Expected: PASS. 이어서 `./gradlew :storage-dynamodb:test` 를 한 번(모듈 전체) 돌려 PASS.

- [ ] **Step 7: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java app-scim/src/main/java/dev/starryeye/organization/scim/app/AdminSyncController.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java
git commit -m "fix: 실행 id 에 시작 시각을 싣고 단건 조회는 id 에서 키를 다시 만들어 GetItem 한 번 — 보관 기간을 늘려도 몇 달 전 회차를 찾는다 (실행 기록 단건 조회)"
```

---

### Task 2: 문서 — 실행 id 모양과 단건 조회의 범위를 현재 동작으로

**Files:**
- Modify: `docs/onboarding/04-데이터-모델.md` (~:199 식별자 표의 `runId` 줄, ~:618 실행 기록 절, ~:802 TTL 절의 "다만 실행 기록 읽기는 이번 달과 지난달만 …")
- Modify: `docs/onboarding/09-관리-API.md` (~:680 `runId` 칸, 실행 기록 단건 조회를 설명하는 곳에 "보관 기간(30일)" 같은 문장이 있으면 함께)
- Modify: `docs/onboarding/10-감사-이력.md` (~:304)
- Modify: `docs/onboarding/11-운영.md` (~:36 `syncrun-retention-days` 줄)
- Modify: `docs/onboarding/13-한계와-알려진-문제.md` (§6.6, §8 한눈에 표의 해당 줄)
- Modify: `docs/onboarding/15-결정-색인.md` (설계 문서 목록, 새 절, 더 깊이)

**Interfaces:**
- Consumes: 과제 1 의 동작. 기준 커밋 해시는 `git log --oneline -3` 에서 과제 1 의 커밋.

- [ ] **Step 1: 동작 문장을 고친다**

모든 자리에서 같은 규칙으로 쓴다: **실행 id 는 `<UTC 시작 시각 yyyyMMdd'T'HHmmss.SSS'Z'>-<UUID>`(예 `20261125T135329.645Z-3f2a9c1e-…`)다. 단건 조회(`GET /admin/sync/runs/{runId}`)는 id 에서 키를 다시 만들어 아이템 하나만 읽으므로 보관 기간(`dynamodb.syncrun-retention-days`) 안의 기록은 몇 달 전 것도 찾는다. 모양이 다른 id 는 404 다. 목록(`GET /admin/sync/runs`)과 죽은 기록 닫기는 지금처럼 이번 달·지난달만 본다(설계 2026-11-25).** 자리별로:
- 04 :199 의 `UUID` → 새 모양. 04 실행 기록 절(:618~)에 id 모양과 단건 조회가 GetItem 이라는 줄(없으면 더한다). 04 :802 의 "다만 … 찾히지 않는다" → "단건 조회는 보관 기간 안이면 찾는다. 목록은 이번 달·지난달만 보인다".
- 09 :680 `runId` 칸 → "`<UTC 시작 시각>-<UUID>`(예 …). 감사 이력의 `cause.id` 가 이 값이다".
- 10 :304, 11 :36 의 "30일보다 늘려도 … 찾히지 않는다" → 단건 조회는 찾고 목록만 두 달.
- 13 §6.6: 이 절 머리의 "고친 것" 표(옛 번호 6.1~6.8 이 있는 표)에 `6.6` 줄을 더한다 — `| 6.6 | 실행 기록은 이번 달과 지난달만 읽는다 — 보관을 늘려도 오래된 회차를 관리 API 로 찾지 못했다 | 고침(<과제 1 해시>, 설계 2026-11-25) — 실행 id 에 시작 시각을 싣고 단건 조회가 id 에서 키를 다시 만든다([04 — 실행 기록](04-데이터-모델.md#실행-기록)). 목록은 그대로 두 달이다 |`. 본문 §6.6 절은 지운다. §8 한눈에 표의 `실행 기록은 두 달만 읽음 | 확인함 | 6.6` 줄은 지운다(고친 항목은 표에서 뺀다 — 이 문서의 규칙).
- 머리 문단의 "확인함" 상태 설명은 그대로 둔다(상태 표는 정의다).

- [ ] **Step 2: 결정 색인을 고친다**

- 설계 문서 목록에서 2026-11-25 재활성 읽기 오류 줄 끝의 "현재(가장 최신)" 을 "현재" 로 바꾸고, 그 아래에 `| [2026-11-25 실행 기록 단건 조회](../superpowers/specs/2026-11-25-run-lookup-by-id-design.md) | 13 §6.6 — 실행 id 에 시작 시각, 단건 조회는 id 에서 키를 다시 만들어 GetItem 한 번(보관 기간 전체), 모양이 다른 id 는 404, 목록·죽은 기록 닫기는 그대로 두 달 | 현재(가장 최신). [실행 기록 단건 조회(I1~I6)](#실행-기록-단건-조회i1i6) |` 를 더한다.
- `## 검증 방식` 앞에 새 절 `## 실행 기록 단건 조회(I1~I6)` — 머리 문단 한 줄(설계 링크, 소유자는 "추천대로"(2026-11-25)라고만 했고 결정은 메인 세션의 추천이다, 번호는 그 설계의 것) + 다섯 칸 표(`# | 결정 | 까닭 | 버린 길 | 동작 문서`) I1~I6. 결정·까닭은 설계 §3 에서, 버린 길은 I1 줄에 §2 의 A′·B·C·D·E 를 한 줄씩 줄여서 적는다. 동작 문서 칸은 04 실행 기록·09 `runId`.
- `## 더 깊이` 끝에 `- 실행 기록 단건 조회의 결정과 말할 수 없는 것: [실행 기록 단건 조회 §3·§6](../superpowers/specs/2026-11-25-run-lookup-by-id-design.md)`.
- 감사 절의 "보관은 30일 … 함께 늘린다"(~:274) 줄은 그대로 둔다(여전히 맞다).

- [ ] **Step 3: 고친 문서의 기준 커밋을 올린다**

고친 온보딩 문서(04·09·10·11·13·15)만 머리 줄을 `> 기준 커밋: <과제 1 해시7> (run-lookup-by-id, main 에 머지됨). 코드가 바뀌면 이 문서도 함께 고친다.` 로 바꾼다.

- [ ] **Step 4: 남은 옛 문구가 없는지 본다**

Run: `grep -rn "30일보다 길게 늘려도\|30일보다 늘려도\|runId\` | UUID\|실행 기록 \`runId\` | UUID" docs/onboarding README.md`
Expected: 출력 없음.

- [ ] **Step 5: 커밋한다**

```bash
git add docs/onboarding/04-데이터-모델.md docs/onboarding/09-관리-API.md docs/onboarding/10-감사-이력.md docs/onboarding/11-운영.md docs/onboarding/13-한계와-알려진-문제.md docs/onboarding/15-결정-색인.md
git commit -m "docs: 실행 id 모양(시작 시각-UUID)과 보관 기간 전체의 단건 조회를 온보딩에 반영하고 13 §6.6 을 고친 항목으로 옮긴다 (실행 기록 단건 조회)"
```
