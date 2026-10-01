# 여러 대에서도 작업은 하나, 멈춘 뒤엔 훑어 맞추기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 중 M14(app-ldap 클러스터 락)·M15(하루 1회 작업 중복)·M2(쓰기 뒤·기록 전 멈춤으로 기준선 어긋남)·M16(나쁜 줄 하나가 묶음 전체를 실패시킴)과 곁가지 둘(장부 훑기 재시도 소진 메시지, 빈 D 가드)을 고친다.

**Architecture:** 작업 락은 core `SyncJobs#startLocked` 한 곳이 잡고(획득·하트비트·리스 상실·반납·죽은 기록 정리), SCIM 재적재·LDAP 동기화·LDAP 재적재가 모두 탄다 — app-ldap 의
프로세스 안 가드와 시작 때 정리는 없어진다. LDAP 은 OpenFGA 에 쓰기 직전 스냅샷 포인터에 "기록 중"(`writingSince`)을 남기고, 스냅샷 저장이 그것을 지운다.
표시가 남아 있으면 다음 동기화는 비교 대신 장부를 훑어 맞춘다(`LedgerAlignment` = 재적재와 같은 청소 + 지우기 전 삭제 가드). 하루 1회 작업은
`DAILY#<작업>#<날짜>` 표지(`DailyOnce`)를 잡은 한 대만 돌리고, 아카이빙은 재적재가 락을 쥐고 있으면(`MutationLock#peek`) 건너뛴다. OpenFGA 쓰기는 400 을
재시도하지 않고 반씩 쪼개 나쁜 줄만 실패로 남기며, 차단기는 한 줄도 못 살린 묶음만 센다.

**Tech Stack:** Java 17, Spring Boot 3.5 / WebFlux, Reactor, AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, Awaitility, Mockito,
Testcontainers(DynamoDB Local, OpenFGA v1.10.2), Lombok.

**Spec:** `docs/superpowers/specs/2026-09-30-cluster-jobs-baseline-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then`), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고**(컴파일 실패 포함) 고친다.
- 락 아이템 키 `LOCK#MUTATION`(옛 `LOCK#SCIM_WRITE`). `LockPurpose` 에 `SYNC`(LDAP 동기화). LDAP 재적재·SCIM 재적재는 `REBUILD`.
- 문구(그대로 쓴다): 죽은 기록 `비정상 종료로 중단`, 훑어 맞춤 `기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤`, 아카이빙 건너뜀 `재적재 중이라 건너뜀`,
  SCIM 빈 조직도 `조직도가 비어 있다 — 장부를 비우려면 mode=wipe`, 가드 기준 이름 `훑은 장부`(스냅샷 비교는 기존 `기준 스냅샷`).
- "기록 중" 칸: 스냅샷 포인터 아이템(`SNAPSHOT_POINTER`/`LATEST`)의 `writingSince`(ISO 시각). 스냅샷 저장의 포인터 PutItem 이 지운다.
- 하루 1회 표지: PK `DAILY#<작업>#<yyyy-MM-dd>`, SK `META`, `attribute_not_exists` 조건부 쓰기, `expiresAt` = 지금 + 3일, 날짜는 앱 시계의 UTC 날짜. 작업 이름 `scim-archive`·`scim-purge`·`ldap-purge`.
- 삭제 가드: 기존 `sync.deletion-guard`(기본 30%, 최소 기준 10줄)를 스냅샷 비교·훑어 맞추기·LDAP 재적재가 함께 쓴다. `FORCED`·`rebuild?force=true` 는 건너뛴다.
- OpenFGA Read 는 장부 청소(`TupleReconciler`)에서만 — LDAP 재적재·SCIM 재적재·"기록 중" 뒤 첫 LDAP 동기화. 판단·쓰기 경로는 Check·BatchCheck 만.
- OpenFGA 400(`FgaApiValidationError`)은 재시도하지 않는다. 그 밖은 `openfga.max-retries` 번 200ms 부터 늘려 가며. 재시도를 다 쓰면 원래 오류를 던진다.
- 운영 배포 전이라 이관·하위호환을 만들지 않는다.
- **컴파일 범위:** Task 2 뒤로 `app-scim` 은 Task 7 까지, `app-ldap` 은 Task 8 까지 컴파일되지 않는다(`SyncJobs` 생성자가 바뀐다). 그 사이 과제는 자기 모듈만 돌린다.
- 서브에이전트는 과제에 적힌 **모듈 테스트나 테스트 클래스만** 돌린다. 앱 모듈 전체 `test`·`scaleTest` 는 돌리지 않는다(컨트롤러가 돌린다). Gradle 은 한 번에 하나, 포그라운드.
- 커밋마다 `git push`(브랜치 `audit-cluster-jobs`, 업스트림 설정돼 있음). 커밋 메시지는 제목 → 빈 줄 → 하네스가 정한 공동 작성자 줄(heredoc `git commit -F - <<'EOF' … EOF`, 과제마다 적힌 그대로). 경로를 지정해 스테이징한다.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **두 인스턴스가 같은 초(03:00)에 정기 동기화를 건다** — 한 대만 돌고 다른 대는 기록도 오류도 남기지 않고 건너뛴다(Task 2 `두_인스턴스는_한_번에_하나만_돈다`, Task 8 `락을_못_잡으면_조용히_건너뛴다`).
2. **락을 잡은 작업이 자기 자신의 RUNNING 기록을 "비정상 종료"로 닫는다** — 자기 기록은 두고 죽은 것만 닫아야 한다(Task 2 `끝나지_못한_기록을_닫는다`).
3. **"기록 중" 뒤 첫 동기화에서 LDAP 이 정당하게 30% 넘게 바뀌었다** — 지우지 않고 ABORTED, 표시는 남고, `force` 로 진행한다(Task 4 `훑어_맞추기도_가드를_지킨다`·`강제면_훑어_맞추기가_지운다`).
4. **첫 설치에서 스냅샷 없이 "기록 중"만 남았다** — 기준선은 "없음"이고 정리 작업이 아무것도 잘못 지우지 않는다(Task 3 `첫_설치에서도_표시를_남긴다`).
5. **인가 모델이 통째로 없어 모든 줄이 거절된다** — 쪼개기가 폭주하지 않고(배치당 2n−1 호출) 차단기가 세 묶음 뒤 멈춘다(Task 6 `다_거절이면_호출이_묶인다`, 기존 차단기 테스트).

---

### Task 1: 락 — `SYNC` 목적, 들여다보기(`peek`), 키 이름 `LOCK#MUTATION`

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`

**Interfaces:**
- Produces: `MutationLock.LockPurpose.SYNC`; `Mono<MutationLock.LockPurpose> MutationLock#peek()` — 지금 쥔 목적, 없거나 만료면 빈 Mono. 잡지 않는다.
- Produces (가짜): `FakeMutationLock#peek()` — 쥔 동안 획득 때의 목적을 준다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbMutationLockTest` 끝에 더한다:

```java
    @Test
    @DisplayName("아무도 쥐지 않았으면 들여다봐도 빈 값이다")
    void 비었으면_들여다봐도_빈_값이다() {
        // when, then
        assertThat(인스턴스1.peek().blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("쥔 목적을 들여다본다 — 들여다보기는 락을 잡지 않는다")
    void 쥔_목적을_들여다본다() {
        // given
        var lease = 인스턴스1.acquire(LockPurpose.REBUILD).block();

        // when
        var 쥔_목적 = 인스턴스2.peek().block();

        // then — 들여다본 쪽은 여전히 잡을 수 없고, 반납하면 빈 값이다
        assertThat(쥔_목적).isEqualTo(LockPurpose.REBUILD);
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
        인스턴스1.release(lease).block();
        assertThat(인스턴스2.peek().blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("만료된 락은 쥔 것으로 보지 않는다 — 죽은 인스턴스가 남긴 줄 때문에 아카이빙이 영영 건너뛰어지지 않게")
    void 만료된_락은_쥔_것이_아니다() {
        // given
        인스턴스1.acquire(LockPurpose.REBUILD).block();

        // when — TTL(30초)을 넘긴다
        clock.앞으로(Duration.ofSeconds(31));

        // then
        assertThat(인스턴스2.peek().blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("app-ldap 동기화 목적(SYNC)으로도 잡는다")
    void 동기화_목적으로_잡는다() {
        // when
        인스턴스1.acquire(LockPurpose.SYNC).block();

        // then
        assertThat(인스턴스2.peek().block()).isEqualTo(LockPurpose.SYNC);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest'`
Expected: 컴파일 실패 — `peek`·`SYNC` 가 없다.

- [ ] **Step 3: 구현한다**

`MutationLock` 의 클래스 자바독 첫 문단을 바꾸고, `renew` 아래에 `peek` 을, 열거형에 `SYNC` 를 더한다:

```java
/**
 * 인스턴스 전체에서 한 번에 하나만 하게 하는 전역 리스 락 (설계 §4, 2026-09-30 §3).
 *
 * <p>앱마다 테이블이 달라 락도 앱마다 따로다. app-scim 은 SCIM 쓰기와 재적재를, app-ldap 은 동기화와 재적재를 이 락 하나로 줄 세운다.
```

(기존 "왜 전역인가"·"리스다" 문단은 그대로 둔다.)

```java
    /**
     * 지금 쥔 목적을 본다. 아무도 없거나 만료됐으면 빈 Mono. <b>잡지 않는다</b> — 아카이빙처럼 락을 잡으면 안 되는(잡으면 몇 분 동안
     * SCIM 쓰기가 503 이 되는) 쪽이 "재적재 중인가"를 알 때 쓴다(설계 2026-09-30 §5.2).
     */
    Mono<LockPurpose> peek();

    enum LockPurpose {
        WRITE,
        REBUILD,
        /** app-ldap 전체 동기화(정기·수동). 재적재는 {@link #REBUILD}. */
        SYNC
    }
```

`Keys` 의 락 키를 바꾼다:

```java
    /**
     * 전역 변경·작업 락. 파티션 하나에 아이템 하나다 (설계 §4.2). 앱마다 테이블이 달라 app-scim 은 SCIM 쓰기·재적재, app-ldap 은 동기화·재적재를
     * 이 한 줄로 줄 세운다(설계 2026-09-30 §3.1).
     */
    public static final String LOCK_PK = "LOCK#MUTATION";
```

`DynamoDbMutationLock` 에 `renew` 아래로 더한다(import `software.amazon.awssdk.services.dynamodb.model.GetItemRequest`):

```java
    /**
     * 강한 일관성으로 읽는다 — 방금 잡힌 락을 못 보면 재적재 도중에 아카이빙이 돈다. 만료된 줄은 테이블 TTL 이 지울 때까지 남아 있으므로
     * {@code expiresAt} 을 직접 본다(획득 조건과 같은 기준: 만료 시각이 지금보다 앞이면 빈 락).
     */
    @Override
    public Mono<LockPurpose> peek() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item())
                .filter(item -> Attrs.longValue(item, Keys.EXPIRES_AT) >= clock.instant().getEpochSecond())
                .map(item -> LockPurpose.valueOf(Attrs.str(item, PURPOSE)));
    }
```

클래스 자바독의 "app-ldap 과 app-scim 은 서로 다른 테이블을 쓰므로 락도 자연히 분리된다" 문장은 그대로 맞다 — 건드리지 않는다.

`FakeMutationLock` — 필드와 `peek` 을 더하고 `acquire`·`release` 가 목적을 기록·해제하게 한다(import `java.util.concurrent.atomic.AtomicReference` 는 이미 있다):

```java
    private final AtomicReference<LockPurpose> heldPurpose = new AtomicReference<>();
```

`acquire` 의 `acquired.incrementAndGet();` 바로 앞에 `heldPurpose.set(purpose);` 를, `release` 의 `released.incrementAndGet();` 바로 앞에 `heldPurpose.set(null);` 를 넣고:

```java
    @Override
    public Mono<LockPurpose> peek() {
        return Mono.defer(() -> heldToken.get() == null ? Mono.empty() : Mono.justOrEmpty(heldPurpose.get()));
    }
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest'` → PASS.
Run: `./gradlew :core:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java
git commit -F - <<'EOF'
feat: 락에 동기화 목적(SYNC)과 들여다보기(peek) — 키 이름은 두 앱이 함께 쓰는 LOCK#MUTATION

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 2: `SyncJobs#startLocked` — 락 잡기·하트비트·리스 상실·죽은 기록 정리를 한 곳에서

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java` (전체 교체)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java`, `ScimRebuildLockTest.java`, `ScimRebuildRenewTest.java`, `FullSyncUseCaseTest.java`, `RebuildUseCaseTest.java` (생성자 줄만)

**Interfaces:**
- Consumes: `MutationLock#peek`·`LockPurpose.SYNC`(Task 1), `FakeSyncRunRepository#seed/findById/awaitFinished`.
- Produces: `SyncJobs(SyncRunRepository runs, MutationLock lock, Duration renewInterval, LockObserver lockObserver, Duration timeout)`;
  `public Mono<SyncRun> startLocked(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose, Mono<SyncOutcome> work, Consumer<SyncRun> onFinished)` — 못 잡으면 `LockUnavailableException`;
  `static final String 비정상_종료_사유 = "비정상 종료로 중단"`. 옛 `start(…, release…)` 둘은 **패키지 전용**(공개 API 아님).
- Produces: `ScimRebuildUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner, TupleSnapshotRepository snapshots, SyncJobs jobs, Clock clock)` — 락·갱신 주기·관찰자는 `SyncJobs` 가 갖는다.
- 이 과제 뒤로 `app-scim`·`app-ldap` 은 컴파일되지 않는다(Task 7·8 이 고친다).

- [ ] **Step 1: 생성자를 쓰는 테스트부터 고친다(컴파일 실패를 본다)**

`SyncJobsTest`:
- import 에 `dev.starryeye.organization.core.fake.FakeMutationLock`, `dev.starryeye.organization.core.port.MutationLock`, `java.util.concurrent.atomic.AtomicReference`(없으면)를 더한다.
- 필드 `private FakeMutationLock lock;` 를 더하고 `@BeforeEach` 첫 줄에 `lock = new FakeMutationLock();` 를 넣는다.
- 파일 안의 `new SyncJobs(<저장소>, <기한>)` 세 곳을 모두 `new SyncJobs(<저장소>, lock, Duration.ofSeconds(10), LockObserver.NOOP, <기한>)` 로 바꾼다
  (`@BeforeEach`, `기한을_넘기면_멈춘다` 의 `Duration.ofMillis(200)`, `기록을_이렇게_여는` 도우미).

`ScimRebuildUseCaseTest` 의 생성:

```java
        useCase = new ScimRebuildUseCase(state, writer, scanner, snapshots,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
```

`ScimRebuildLockTest` 의 생성:

```java
        useCase = new ScimRebuildUseCase(
                new FakeStateRepository(),
                writer,
                new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
```

`ScimRebuildRenewTest` 의 도우미:

```java
    private static ScimRebuildUseCase 재적재(FakeTupleWriter writer, FakeSyncRunRepository runs,
                                          FakeMutationLock lock, Duration 갱신주기) {
        return new ScimRebuildUseCase(한명짜리_조직도(), writer, new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                new SyncJobs(runs, lock, 갱신주기, LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
```

`FullSyncUseCaseTest`·`RebuildUseCaseTest` 의 `new SyncJobs(runs, Duration.ofMinutes(1))` 를
`new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1))` 로 바꾼다(import `FakeMutationLock` 추가).
이 두 유스케이스의 나머지는 Task 4 가 바꾼다.

`SyncJobsTest` 끝에 새 테스트를 더한다:

```java
    // ---------- 락을 잡고 띄우기 (설계 2026-09-30 §3) ----------

    private SyncRun 락을_잡고_건다(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work) {
        return jobs.startLocked(source, trigger, MutationLock.LockPurpose.SYNC, work, run -> {
        }).block();
    }

    @Test
    @DisplayName("락을 잡고 작업을 띄우며, 끝나면 반납한다 — 도는 동안에는 그 목적으로 쥐고 있다")
    void 락을_잡고_띄우고_반납한다() {
        // given
        AtomicReference<MutationLock.LockPurpose> 도는_동안 = new AtomicReference<>();
        Mono<SyncOutcome> 작업 = lock.peek().map(purpose -> {
            도는_동안.set(purpose);
            return SyncOutcome.noChange();
        });

        // when
        SyncRun 끝난것 = runs.awaitFinished(락을_잡고_건다(SyncSource.LDAP, SyncTrigger.MANUAL, 작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(도는_동안.get()).isEqualTo(MutationLock.LockPurpose.SYNC);
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 락을 쥐고 있으면 곧바로 LockUnavailableException 이고, 기록을 열지도 일을 시작하지도 않는다")
    void 락을_못_잡으면_시작하지_않는다() {
        // given
        lock.failAcquire = true;
        AtomicBoolean 시작됨 = new AtomicBoolean();

        // when, then
        assertThatThrownBy(() -> 락을_잡고_건다(SyncSource.LDAP, SyncTrigger.SCHEDULED, 시작하면_표시한다(시작됨)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(시작됨).isFalse();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("두 인스턴스가 같은 락으로 동시에 걸면 한쪽만 돈다 — 같은 초에 도는 정기 동기화")
    void 두_인스턴스는_한_번에_하나만_돈다() {
        // given — 같은 락(같은 테이블)을 쓰는 두 인스턴스
        SyncJobs 가 = jobs;
        SyncJobs 나 = new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1));
        Sinks.One<SyncOutcome> 가의_작업 = Sinks.one();

        // when
        SyncRun 가의_기록 = 가.startLocked(SyncSource.LDAP, SyncTrigger.SCHEDULED, MutationLock.LockPurpose.SYNC,
                가의_작업.asMono(), run -> {
                }).block();

        // then — 가가 도는 동안 나는 못 건다
        assertThatThrownBy(() -> 나.startLocked(SyncSource.LDAP, SyncTrigger.SCHEDULED, MutationLock.LockPurpose.SYNC,
                Mono.just(SyncOutcome.noChange()), run -> {
                }).block())
                .isInstanceOf(LockUnavailableException.class);

        // when — 가가 끝나면
        가의_작업.tryEmitValue(SyncOutcome.noChange());
        runs.awaitFinished(가의_기록.runId());

        // then — 나도 걸 수 있다
        SyncRun 나의_기록 = 나.startLocked(SyncSource.LDAP, SyncTrigger.MANUAL, MutationLock.LockPurpose.SYNC,
                Mono.just(SyncOutcome.noChange()), run -> {
                }).block();
        assertThat(runs.awaitFinished(나의_기록.runId()).status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("작업 도중 리스를 잃으면(남이 가져갔으면) 멈추고 FAILED 로 기록한다")
    void 리스를_잃으면_멈춘다() {
        // given
        jobs = new SyncJobs(runs, lock, Duration.ofMillis(50), LockObserver.NOOP, Duration.ofMinutes(1));
        lock.failRenew = true;
        AtomicBoolean 취소됨 = new AtomicBoolean();

        // when
        SyncRun 끝난것 = runs.awaitFinished(락을_잡고_건다(SyncSource.LDAP, SyncTrigger.MANUAL,
                Mono.<SyncOutcome>never().doOnCancel(() -> 취소됨.set(true))).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).contains("리스");
        assertThat(취소됨).as("남이 가져간 뒤에도 계속 쓰면 두 인스턴스가 겹친다").isTrue();
    }

    @Test
    @DisplayName("락을 잡은 작업은 시작할 때 같은 앱의 끝나지 못한 락 작업 기록을 '비정상 종료로 중단'으로 닫는다 — 아카이빙 기록과 자기 기록은 두고")
    void 끝나지_못한_기록을_닫는다() {
        // given — 죽은 인스턴스가 남긴 SCIM 재적재 기록, 도는 중인 아카이빙, 다른 앱(LDAP)의 기록
        runs.seed(SyncRun.started("죽은-재적재", SyncSource.SCIM, SyncTrigger.REBUILD, 지금.minusSeconds(300)));
        runs.seed(SyncRun.started("도는-아카이빙", SyncSource.SCIM, SyncTrigger.ARCHIVE, 지금.minusSeconds(60)));
        runs.seed(SyncRun.started("다른-앱", SyncSource.LDAP, SyncTrigger.SCHEDULED, 지금.minusSeconds(600)));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLocked(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, Mono.just(SyncOutcome.noChange()), run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).as("자기 기록은 닫지 않는다").isEqualTo(SyncStatus.SUCCEEDED);
        SyncRun 죽은것 = runs.findById("죽은-재적재").block();
        assertThat(죽은것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(죽은것.message()).isEqualTo("비정상 종료로 중단");
        assertThat(runs.findById("도는-아카이빙").block().status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.findById("다른-앱").block().status()).isEqualTo(SyncStatus.RUNNING);
    }
```

(`시작하면_표시한다` 도우미는 이미 파일에 있다. import 에 `dev.starryeye.organization.core.model.SyncStatus`·`LockUnavailableException` 이 없으면 더한다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsTest' --tests '*ScimRebuild*'`
Expected: 컴파일 실패 — `SyncJobs` 생성자·`startLocked`, `ScimRebuildUseCase` 생성자가 다르다.

- [ ] **Step 3: 구현한다**

`SyncJobs` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 동기화·재적재 작업을 요청과 떼어 돌린다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 *
 * <p><b>왜 떼는가.</b> 전에는 작업이 HTTP 요청의 구독 안에서 돌았다. 앞단 프록시가 60초에 연결을 끊으면 구독이 취소돼 작업이
 * 중간에 멈췄고, 실행 기록은 영원히 RUNNING 으로, 락은 풀린 채 남았다. 이제 작업은 자기 구독으로 돌고, 요청은 실행 기록(RUNNING)만
 * 받아 간다.
 *
 * <p><b>작업 락(설계 2026-09-30 §3).</b> {@link #startLocked} 가 앱의 작업 락을 잡고, 작업 동안 리스를 갱신하고, 리스를 잃으면(=남이 가져갔으면)
 * 작업을 멈추고, 끝나면 반납한다. 여러 인스턴스가 같은 초에 걸어도 한 번에 하나만 돈다. SCIM 재적재·LDAP 동기화·LDAP 재적재가 모두 이것을 탄다.
 *
 * <p>한 작업이 지키는 규칙:
 * <ol>
 *   <li>실행 기록을 연 뒤에만 일을 시작한다. 기록을 열지 못하면 반납하고 그 오류로 끝난다.</li>
 *   <li>일을 시작하기 전에, 같은 앱의 락 작업 기록 중 RUNNING 으로 남은 것을 "비정상 종료로 중단"으로 닫는다 — 락을 쥐었으니 그것들은
 *       죽은 작업이다(강제 종료·정전·다른 인스턴스의 비정상 종료). 락을 잡지 않는 아카이빙({@code ARCHIVE}) 기록과 자기 기록은 건드리지 않는다.</li>
 *   <li>일은 서버 종료·기한({@code timeout})·리스 상실과 경주한다. 먼저 온 쪽이 이기고 진 쪽은 취소된다. 종료가 이미 시작됐으면 일을
 *       시작하지도 않는다.</li>
 *   <li>어떻게 끝나든 <b>먼저 반납하고 그다음 기록한다.</b> 기록에서 "끝남"을 본 운영자가 곧바로 다시 걸면 받아져야 한다.</li>
 *   <li>요청의 Reactor Context 를 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다.</li>
 * </ol>
 *
 * <p><b>멈춤은 되돌리기가 아니다.</b> 이미 나간 쓰기는 무르지 않는다. 멈췄다는 사실을 FAILED 로 남기고, 기록 규칙은 각 작업이 지킨다
 * (LDAP 은 "기록 중" 표시로 다음 회차가 맞춘다 — 설계 2026-09-30 §4).
 */
@Slf4j
public class SyncJobs {

    static final String 종료_사유 = "서버 종료로 중단";
    static final String 비정상_종료_사유 = "비정상 종료로 중단";

    /** 종료 때 작업이 반납·기록을 마치기를 기다리는 한도. 그 뒤에는 DynamoDB 클라이언트가 닫힌다. */
    private static final Duration 종료_대기 = Duration.ofSeconds(10);

    /** 끝나지 못한 기록을 찾을 때 볼 최근 기록 수. 죽은 작업의 기록은 가장 최근 것들 사이에 있다. */
    private static final int 살펴볼_기록 = 100;

    private final SyncRunRepository runs;
    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;
    private final Duration timeout;
    private final Sinks.Empty<Void> 종료 = Sinks.empty();
    private final Set<Mono<Void>> 도는_작업 = ConcurrentHashMap.newKeySet();

    public SyncJobs(SyncRunRepository runs, MutationLock lock, Duration renewInterval,
                    LockObserver lockObserver, Duration timeout) {
        this.runs = runs;
        this.lock = lock;
        this.renewInterval = renewInterval;
        this.lockObserver = lockObserver;
        this.timeout = timeout;
    }

    /**
     * 앱의 작업 락을 잡고, 실행 기록을 열고, 작업을 요청과 떼어 띄운 뒤 연 기록(RUNNING)을 준다(설계 2026-09-30 §3.2).
     * 못 잡으면 {@link LockUnavailableException} — 재시도하지 않는다. 못 잡았다는 것이 곧 다른 작업이 돈다는 뜻이다.
     *
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    public Mono<SyncRun> startLocked(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                     Mono<SyncOutcome> work, Consumer<SyncRun> onFinished) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            return lock.acquire(purpose)
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), false))
                    .flatMap(lease -> {
                        Sinks.One<Throwable> 리스상실 = Sinks.one();
                        Disposable heartbeat = 리스를_갱신한다(lease, 리스상실);
                        return start(source, trigger,
                                Mono.firstWithSignal(work, 리스상실.asMono().flatMap(Mono::error)),
                                run -> 남은_기록을_닫는다(source, run.runId()),
                                () -> {
                                    heartbeat.dispose();
                                    return lock.release(lease);
                                },
                                onFinished);
                    });
        });
    }

    /** 패키지 전용 기본 단위 — 겹침 검사는 호출자가 하고 그 반납 수단을 넘긴다. 바깥은 {@link #startLocked} 를 쓴다. */
    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger,
                        Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        return start(source, trigger, work, release, run -> {
        });
    }

    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work,
                        Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return start(source, trigger, work, run -> Mono.empty(), release, onFinished);
    }

    /**
     * 실행 기록을 열고 {@code work} 를 요청과 떼어 띄운 뒤, 연 기록(RUNNING)을 준다. 구독할 때마다 작업 하나가 뜬다.
     *
     * @param 먼저       기록을 연 뒤·일을 시작하기 전에 할 일. 실패해도 일은 한다
     * @param release    겹침 검사(락·가드)를 푸는 수단. 작업이 어떻게 끝나든 정확히 한 번 부른다
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work,
                        Function<SyncRun, Mono<Void>> 먼저, Supplier<Mono<Void>> release,
                        Consumer<SyncRun> onFinished) {
        return Mono.deferContextual(context -> {
            Sinks.One<SyncRun> 열림 = Sinks.one();
            Sinks.Empty<Void> 끝남 = Sinks.empty();
            Mono<Void> 끝남신호 = 끝남.asMono();
            도는_작업.add(끝남신호);

            // 여는 호출이 곧바로 던지거나 빈 응답이어도 반납하고 오류로 알린다 — 아니면 요청이 매달리고 락·가드가 풀리지 않는다
            Mono.defer(() -> runs.start(source, trigger))
                    .switchIfEmpty(Mono.error(() -> new IllegalStateException("실행 기록을 열지 못했다 — 빈 응답")))
                    .onErrorResume(error -> {
                        log.error("실행 기록을 열지 못했다: source={} trigger={}", source, trigger, error);
                        return 반납한다(release)
                                .then(Mono.fromRunnable(() -> 열림.tryEmitError(error)))
                                .then(Mono.<SyncRun>empty());
                    })
                    .flatMap(run -> {
                        log.info("[{}] 작업 시작: source={} trigger={}", run.runId(), source, trigger);
                        열림.tryEmitValue(run);
                        return 끝까지_돌린다(run, work, 먼저, release);
                    })
                    .doOnNext(onFinished)
                    .doFinally(signal -> {
                        도는_작업.remove(끝남신호);
                        끝남.tryEmitEmpty();
                    })
                    .contextWrite(context)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(finished -> {
                    }, error -> log.error("동기화 작업 뒤처리가 예기치 않게 실패했다", error));

            return 열림.asMono();
        });
    }

    /**
     * 앱이 내려갈 때 부른다(설계 §4 "정상 종료"). 도는 작업을 멈춰 FAILED("서버 종료로 중단")로 기록하게 하고, 반납·기록을 마칠 때까지
     * 최대 {@link #종료_대기} 기다린다. 기다리지 않으면 이 뒤에 DynamoDB 클라이언트가 닫혀 기록이 RUNNING 으로 남고 락도 TTL 까지 묶인다.
     * 이 뒤에 걸린 작업은 일을 시작하지 않고 같은 사유로 끝난다.
     */
    public void shutdown() {
        종료.tryEmitEmpty();
        List<Mono<Void>> 남은것 = List.copyOf(도는_작업);
        if (남은것.isEmpty()) {
            return;
        }
        log.warn("서버 종료: 도는 동기화 작업 {}개를 멈추고 FAILED 로 기록한다", 남은것.size());
        try {
            Mono.when(남은것).block(종료_대기);
        } catch (RuntimeException e) {
            log.error("서버 종료: {} 안에 작업 기록을 마치지 못했다 — 기록이 RUNNING 으로 남을 수 있다", 종료_대기, e);
        }
    }

    /**
     * 종료 신호를 <b>먼저</b> 구독한다. 종료가 이미 시작됐으면 그 자리에서 이겨 {@code work} 를 구독하지도 않는다.
     */
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, Mono<SyncOutcome> work,
                                    Function<SyncRun, Mono<Void>> 먼저, Supplier<Mono<Void>> release) {
        Mono<SyncOutcome> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        Mono<SyncOutcome> 일 = Mono.defer(() -> 먼저.apply(run)).then(work);
        return Mono.firstWithSignal(종료되면, 일)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> {
                    // 메시지 없는 오류도 있다 — 이유를 비워 두면 기록만 보고는 무엇이 터졌는지 모른다
                    String 사유 = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
                    log.error("[{}] 작업 실패: {}", run.runId(), 사유, error);
                    return Mono.just(SyncOutcome.failed(사유));
                })
                .flatMap(outcome -> 반납한다(release).thenReturn(outcome))
                .flatMap(outcome -> runs.finish(run, outcome))
                .doOnNext(finished -> log.info("[{}] 작업 끝: status={} written={} deleted={} failed={}",
                        finished.runId(), finished.status(), finished.writtenCount(),
                        finished.deletedCount(), finished.failureCount()))
                .onErrorResume(error -> {
                    log.error("[{}] 작업 결과를 기록하지 못했다 — 기록이 RUNNING 으로 남는다", run.runId(), error);
                    return Mono.empty();
                });
    }

    /**
     * 같은 앱의 락 작업 기록 중 RUNNING 으로 남은 것을 닫는다(설계 2026-09-30 §3.3). 락을 쥐었으니 그것들은 죽은 작업이다. 락을 잡지 않는
     * 아카이빙과 방금 연 자기 기록은 건드리지 않는다. 찾거나 닫다 실패해도 작업은 계속한다 — 기록 정리는 부가 일이다.
     */
    private Mono<Void> 남은_기록을_닫는다(SyncSource source, String 지금_기록) {
        return runs.findRecent(살펴볼_기록)
                .filter(run -> run.source() == source
                        && run.status() == SyncStatus.RUNNING
                        && run.trigger() != SyncTrigger.ARCHIVE
                        && !run.runId().equals(지금_기록))
                .concatMap(run -> runs.finish(run, SyncOutcome.failed(비정상_종료_사유))
                        .doOnNext(closed -> log.warn("[{}] 끝나지 못한 채 남은 실행 기록을 닫았다: {}", closed.runId(), 비정상_종료_사유))
                        .onErrorResume(error -> {
                            log.warn("[{}] 끝나지 못한 실행 기록을 닫지 못했다", run.runId(), error);
                            return Mono.empty();
                        }))
                .then()
                .onErrorResume(error -> {
                    log.warn("끝나지 못한 실행 기록을 찾지 못했다 — 작업은 계속한다", error);
                    return Mono.empty();
                });
    }

    /**
     * 작업이 도는 동안 리스를 계속 미룬다(설계 §4.4). TTL 은 30초인데 작업은 몇 분 걸린다. 갱신이 실패하면 이미 리스를 잃은 것이다 —
     * 그 사실을 {@code 리스상실} 로 흘려 작업을 취소하고 FAILED 로 기록하게 한다. {@code concatMap} 이 에러를 전파하므로 이 구독도 함께 끝난다.
     */
    private Disposable 리스를_갱신한다(LockLease lease, Sinks.One<Throwable> 리스상실) {
        return Flux.interval(renewInterval, renewInterval)
                .concatMap(tick -> lock.renew(lease))
                .subscribe(renewed -> {
                }, error -> {
                    log.error("작업 도중 락 리스를 잃었다. 작업을 멈추고 FAILED 로 기록한다", error);
                    lockObserver.leaseLost("작업 도중 리스 상실");
                    리스상실.tryEmitValue(error);
                });
    }

    /** 반납 실패는 작업을 실패시키지 않는다 — 일은 이미 끝났다. 남기기만 한다. */
    private static Mono<Void> 반납한다(Supplier<Mono<Void>> release) {
        return Mono.defer(release)
                .onErrorResume(error -> {
                    log.warn("작업을 끝내며 락·가드를 반납하지 못했다", error);
                    return Mono.empty();
                });
    }

    private static Duration 경과(long 시작나노) {
        return Duration.ofNanos(System.nanoTime() - 시작나노);
    }

    private static String 사람말로(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + "분" : duration.toMillis() + "ms";
    }
}
```

`ScimRebuildUseCase`:
- 필드 `lock`·`renewInterval`·`lockObserver` 와 메서드 `리스를_잃으면_중단하고`·`경과`·`리스를_갱신한다` 를 지운다. 쓰지 않게 된 import(`Disposable`, `Flux`, `Sinks`, `LockLease`, `Duration`)를 정리한다(`MutationLock` 은 목적 때문에 남긴다).
- 필드 순서는 `state, writer, scanner, snapshots, jobs, clock` 이다.
- `start` 를 이것으로 바꾼다:

```java
    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 돌려준다. 재적재는 요청과 떼어 돈다 — 결과는 실행 기록으로 본다. 락 잡기·갱신·리스를
     * 잃으면 멈추기·반납은 {@link SyncJobs#startLocked} 가 한다. 못 잡으면 {@link LockUnavailableException} 이고 아무것도 시작하지 않는다.
     *
     * <p>{@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미 확인됐다는 뜻이므로 값 자체는 받지 않는다.
     */
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLocked(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD,
                Mono.defer(() -> rebuild(mode)), run -> {
                });
    }
```

- 클래스 자바독의 "요청과 떼어 돈다" 문단을 이것으로 바꾼다:

```java
 * <p><b>요청과 떼어 돈다(설계 2026-09-29 §4, 2026-09-30 §3).</b> {@link #start} 는 {@link SyncJobs#startLocked} 로 작업 락을 잡고 실행 기록을 연 뒤
 * 곧바로 돌아온다. 재적재가 도는 동안 리스를 갱신하고, 리스를 잃으면 멈춰 FAILED 로 남긴다. 끝나면 락을 반납한 뒤 결과를 기록한다.
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test` → PASS(`SyncJobsTest` 기존 14 + 새 5, `ScimRebuild*` 전부).
Run: `git grep -n "new SyncJobs(runs, Duration" -- core` → 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildLockTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java
git commit -F - <<'EOF'
feat: SyncJobs 가 작업 락을 잡고 띄운다 — 갱신·리스 상실·반납·죽은 기록 정리를 한 곳에서, SCIM 재적재가 먼저 탄다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 3: 스냅샷 포인터의 "기록 중" 표시, `reset()` 제거

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java`, `ScimRebuildUseCaseTest.java` (`resetCount` 단언 한 줄씩)

**Interfaces:**
- Produces: `Mono<Void> TupleSnapshotRepository#markWriting()`, `Mono<Boolean> TupleSnapshotRepository#isWriting()`. `reset()` 은 없어진다.
- Produces (가짜): `FakeSnapshotRepository` — `public final AtomicBoolean writing`(markWriting 이 켜고 save 가 끔), `failSave(RuntimeException)`, `failMarkWriting(RuntimeException)`,
  `public final AtomicInteger purgeCalls`(purgeExpired 호출 수). `resetCount` 는 없어진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbTupleSnapshotRepositoryTest` 에서 `리셋하면_전부_사라진다` 와 `재적재_초기화가_중간에_실패해도_기준선_깨짐으로_남지_않는다`(두 테스트와 위 주석)를 지우고, 끝에 더한다:

```java
    // ---------- 기록 중 표시 (설계 2026-09-30 §4.1) ----------

    @Test
    @DisplayName("아무 일도 없었으면 기록 중이 아니다")
    void 처음엔_기록_중이_아니다() {
        // when, then
        assertThat(repository.isWriting().block()).isFalse();
    }

    @Test
    @DisplayName("기록 중 표시를 남기면 isWriting 이 참이고, 기준선은 그대로다")
    void 기록_중_표시를_남긴다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        repository.markWriting().block();

        // then
        assertThat(repository.isWriting().block()).isTrue();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }

    @Test
    @DisplayName("스냅샷을 저장하면 기록 중 표시가 사라진다 — 포인터를 통째로 새로 쓴다")
    void 저장하면_표시가_사라진다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.markWriting().block();

        // when
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // then
        assertThat(repository.isWriting().block()).isFalse();
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }

    @Test
    @DisplayName("포인터가 없어도 표시만 남길 수 있고, 그때 기준선은 '없음'이며 정리도 아무것도 지우지 않는다")
    void 첫_설치에서도_표시를_남긴다() {
        // when
        repository.markWriting().block();

        // then
        assertThat(repository.isWriting().block()).isTrue();
        assertThat(repository.findLatest().blockOptional()).isEmpty();
        assertThat(repository.purgeExpired().block()).isZero();
    }
```

`RebuildUseCaseTest` 와 `ScimRebuildUseCaseTest` 에서 `assertThat(snapshots.resetCount).hasValue(0);` 줄을 지운다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbTupleSnapshotRepositoryTest'`
Expected: 컴파일 실패 — `markWriting`·`isWriting` 이 없다.

- [ ] **Step 3: 구현한다**

`TupleSnapshotRepository` — `reset()` 을 지우고 `save` 아래에 더한다:

```java
    /**
     * "기록 중" 표시를 남긴다 — LDAP 이 OpenFGA 에 쓰기 <b>직전</b>에 부른다(설계 2026-09-30 §4.1). 다음 {@link #save} 가 포인터를 새로 쓰면서
     * 표시가 사라진다. 그래서 표시가 남아 있다는 것은 "쓰기 시작했는데 기록을 끝내지 못했다"는 뜻이다. 포인터가 없어도(첫 설치) 표시만 남긴다.
     */
    Mono<Void> markWriting();

    /** "기록 중" 표시가 남아 있는가 — 지난 회차가 쓰기 시작한 뒤 기록 전에 멈췄다. 강한 일관성으로 읽는다. */
    Mono<Boolean> isWriting();
```

인터페이스 자바독의 "OpenFGA 의 <b>열거</b> API(Read/ListObjects)를 쓰지 않으므로 …" 문단은 "평소 동기화는 OpenFGA 의 열거 API(Read)를 쓰지 않으므로 …"로 첫 구절만 고친다
(재적재·멈춘 뒤 첫 동기화는 장부를 훑는다).

`DynamoDbTupleSnapshotRepository`:
- import `software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest`.
- 상수 `private static final String WRITING_SINCE = "writingSince";` 를 더한다.
- `reset()` 과 그 자바독을 지운다(`snapshotMetas()` 는 `listRecent` 가 쓰므로 남긴다).
- `latestId()` 를 이것으로 바꾸고, 그 아래에 둘을 더한다:

```java
    /** 최신 포인터가 가리키는 스냅샷 id. 강한 일관성으로 읽는다 — 정리 작업이 이 값으로 최신을 건너뛴다. 포인터가 없거나 "기록 중" 표시만 있으면 빈 Mono. */
    private Mono<String> latestId() {
        return 포인터().flatMap(item -> Mono.justOrEmpty(Attrs.str(item, SNAPSHOT_ID)));
    }

    private Mono<Map<String, AttributeValue>> 포인터() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item());
    }

    /** 포인터 줄에 칸 하나를 붙인다(UpdateItem) — 스냅샷 번호는 그대로 둔다. 포인터가 없으면 칸만 있는 줄이 생긴다. */
    @Override
    public Mono<Void> markWriting() {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .updateExpression("SET #writingSince = :now")
                        .expressionAttributeNames(Map.of("#writingSince", WRITING_SINCE))
                        .expressionAttributeValues(Map.of(":now", Attrs.s(clock.instant().toString())))
                        .build()))
                .then();
    }

    @Override
    public Mono<Boolean> isWriting() {
        return 포인터()
                .map(item -> item.containsKey(WRITING_SINCE))
                .defaultIfEmpty(false);
    }
```

(`Attrs.str` 은 없는 속성에 null 을 준다 — "기록 중" 칸만 있는 포인터는 빈 Mono 가 된다.) `writePointer` 는 PutItem 으로 줄을 통째로 쓰므로 칸이
저절로 사라진다 — 바꾸지 않는다. 자바독에 한 줄 더한다: "줄을 통째로 새로 써 '기록 중' 칸도 지운다(설계 2026-09-30 §4.1)."

`FakeSnapshotRepository` — `resetCount`·`reset()` 을 지우고 더한다(import `java.util.concurrent.atomic.AtomicBoolean`):

```java
    /** "기록 중" 표시 — markWriting 이 켜고 save 가 끈다(설계 2026-09-30 §4.1). 테스트가 직접 켜 "지난 회차가 기록 전에 멈춤"을 흉내 낸다. */
    public final AtomicBoolean writing = new AtomicBoolean();

    /** purgeExpired 가 불린 횟수. 하루 1회 작업이 한 번만 도는지 보는 데 쓴다. */
    public final AtomicInteger purgeCalls = new AtomicInteger();

    private RuntimeException saveError;
    private RuntimeException markWritingError;

    /** 설정하면 save 가 이 오류로 끝난다 — 쓰기 뒤·스냅샷 저장 전에 멈춘 회차를 흉내 낸다. */
    public void failSave(RuntimeException error) {
        this.saveError = error;
    }

    /** 설정하면 markWriting 이 이 오류로 끝난다. */
    public void failMarkWriting(RuntimeException error) {
        this.markWritingError = error;
    }

    @Override
    public Mono<Void> markWriting() {
        return Mono.defer(() -> {
            if (markWritingError != null) {
                return Mono.error(markWritingError);
            }
            writing.set(true);
            return Mono.empty();
        });
    }

    @Override
    public Mono<Boolean> isWriting() {
        return Mono.fromSupplier(writing::get);
    }
```

`save` 를 바꾼다:

```java
    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return Mono.defer(() -> {
            if (saveError != null) {
                return Mono.error(saveError);
            }
            saved.add(snapshot);
            writing.set(false);
            return Mono.empty();
        });
    }
```

`purgeExpired` 를 바꾼다:

```java
    @Override
    public Mono<Integer> purgeExpired() {
        return Mono.fromSupplier(() -> {
            purgeCalls.incrementAndGet();
            return 0;
        });
    }
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbTupleSnapshotRepositoryTest'` → PASS.
Run: `./gradlew :core:test` → PASS.
Run: `git grep -n "reset()" -- core/src/main storage-dynamodb/src/main` → 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java
git commit -F - <<'EOF'
feat: 스냅샷 포인터에 '기록 중' 표시(writingSince) — 쓰기 직전에 붙이고 스냅샷 저장이 지운다. 쓰지 않는 reset() 제거

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 4: 멈춘 뒤 첫 동기화는 장부를 훑어 맞춘다 — 삭제 가드는 훑어 맞추기·재적재에도

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java` (전체 교체)
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (전체 교체)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java` (전체 교체)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` (`reloadTuples`)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`, `RebuildUseCaseTest.java`, `ScimRebuildUseCaseTest.java`, `TupleReconcilerTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`

**Interfaces:**
- Consumes: `SyncJobs#startLocked`(Task 2), `TupleSnapshotRepository#markWriting/isWriting`·`FakeSnapshotRepository#writing/failSave/failMarkWriting`(Task 3), `FakeTupleScanner`·`FakeTupleWriter#stored`.
- Produces: `SyncOutcome#withNote(String note)`; `DeletionGuard#evaluate(int deleteCount, int baselineCount, String baselineLabel)`;
  `TupleReconciler.DeleteCheck`(패키지 전용, `holdReason(desired, stale, scanned)`, `NONE`), `reconcile(writer, scanner, desired, DeleteCheck)`, `Reconciliation#held()`/`heldReason()`;
  `FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots, DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner, DeletionGuard guard, SyncJobs jobs, Clock clock)`,
  `Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished)`;
  `RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots, DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner, DeletionGuard guard, SyncJobs jobs, Clock clock)`,
  `Mono<SyncRun> start(boolean force, Consumer<SyncRun> onFinished)`.
  `FullSyncUseCase.기준선_의심 = "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤"`, `ScimRebuildUseCase.빈_조직도 = "조직도가 비어 있다 — 장부를 비우려면 mode=wipe"`.

- [ ] **Step 1: 테스트 설정을 새 API 로 바꾸고 실패하는 테스트를 쓴다**

`DeletionGuardTest` 끝에 더한다:

```java
    @Test
    @DisplayName("지울 수와 기준 수로도 판정한다 — 장부를 훑어 맞출 때는 기준이 훑은 장부다")
    void 수로_판정한다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());

        // when
        var 넘음 = guard.evaluate(10, 20, "훑은 장부");
        var 안넘음 = guard.evaluate(2, 20, "훑은 장부");
        var 기준이_작음 = guard.evaluate(5, 9, "훑은 장부");

        // then
        assertThat(넘음.aborted()).isTrue();
        assertThat(넘음.message()).contains("훑은 장부 20건").contains("임계치");
        assertThat(안넘음.aborted()).isFalse();
        assertThat(기준이_작음.aborted()).as("최소 기준(10) 미만이면 판정하지 않는다").isFalse();
    }
```

`TupleReconcilerTest` 끝에 더한다(파일의 import 에 없으면 `java.util.Optional` 을 더한다):

```java
    @Test
    @DisplayName("지우기 전 확인이 멈출 이유를 주면 쓴 줄은 두고 아무것도 지우지 않는다")
    void 멈출_이유가_있으면_지우지_않는다() {
        // given
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.add(찌꺼기);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                (desired, stale, scanned) -> Optional.of("멈춤(테스트)")).block();

        // then
        assertThat(reconciliation.held()).isTrue();
        assertThat(reconciliation.heldReason()).isEqualTo("멈춤(테스트)");
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 찌꺼기);
        assertThat(writer.deleted).isEmpty();
    }
```

`FullSyncUseCaseTest`:
- import 에 `dev.starryeye.organization.core.fake.FakeTupleScanner`, `java.util.concurrent.atomic.AtomicBoolean` 을 더한다.
- 필드 `private FakeTupleScanner scanner;` 를 더하고, `setUp()` 의 생성을 바꾼다:

```java
        scanner = new FakeTupleScanner(writer);
        useCase = new FullSyncUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
```

- 도우미 `동기화한다` 의 본문을 바꾼다:

```java
    private SyncRun 동기화한다(SyncTrigger trigger) {
        SyncRun started = useCase.start(trigger, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }
```

- 끝에 더한다:

```java
    // ---------- 기록 중 표시와 훑어 맞추기 (설계 2026-09-30 §4) ----------

    @Test
    @DisplayName("OpenFGA 에 쓰기 직전에 '기록 중' 표시를 남기고, 스냅샷을 저장하면 표시가 사라진다")
    void 쓰기_직전에_표시를_남기고_저장하면_사라진다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        AtomicBoolean 쓸때_표시 = new AtomicBoolean();
        writer.onApply(() -> 쓸때_표시.set(snapshots.writing.get()));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(쓸때_표시).as("표시가 쓰기보다 먼저다").isTrue();
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("스냅샷 저장이 실패하면 '기록 중' 표시가 남는다 — 다음 회차가 이것을 보고 장부를 훑어 맞춘다")
    void 저장이_실패하면_표시가_남는다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failSave(new IllegalStateException("스냅샷 저장 실패(스로틀)"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("쓰는 도중 멈추면(기한·서버 종료·오류) '기록 중' 표시가 남는다 — 표시를 지우는 것은 스냅샷 저장뿐이다")
    void 쓰는_도중_멈추면_표시가_남는다() {
        // given — 기한·종료는 작업을 취소하고, 오류는 작업을 끝낸다. 어느 쪽이든 스냅샷 저장에 닿지 않는다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        writer.onApply(() -> {
            throw new IllegalStateException("쓰는 도중 멈춤(흉내)");
        });

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("변경이 없으면 '기록 중' 표시를 남기지 않는다")
    void 변경이_없으면_표시하지_않는다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("'기록 중' 표시를 남기지 못하면 OpenFGA 에 쓰지 않고 FAILED 다 — 표시 없이 쓰기 시작하는 일은 없다")
    void 표시를_못_남기면_쓰지_않는다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));
        snapshots.failMarkWriting(new IllegalStateException("포인터 쓰기 실패"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("지난 회차가 기록 전에 멈췄으면 장부를 훑어 맞춘다 — 되돌려진 입사자의 권한이 남지 않는다(점검 M2)")
    void 멈춘_뒤_첫_동기화는_훑어_맞춘다_남는_쪽() {
        // given — 월요일 기준선엔 park 이 없다. 화요일 회차가 park 의 권한을 쓰고 스냅샷 저장 전에 멈췄다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        snapshots.save(new TupleSnapshot("월요일", 고정시각, SyncSource.LDAP, Set.of(김))).block();
        writer.stored.addAll(Set.of(김, 박));
        snapshots.writing.set(true);
        // 수요일 park 입사 취소 — 목요일 LDAP 에는 kim 만 있다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 기준선(월요일)과 비교하면 지울 게 없지만, 장부를 훑어 park 을 찾아 지운다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).contains("기준선 의심");
        assertThat(writer.stored).containsExactly(김);
        assertThat(snapshots.writing).isFalse();
        assertThat(snapshots.saved.get(snapshots.saved.size() - 1).tuples()).containsExactly(김);
    }

    @Test
    @DisplayName("지운 뒤 멈췄다가 되살아난 직원도 권한을 되찾는다(점검 M2 의 빠지는 쪽)")
    void 멈춘_뒤_첫_동기화는_훑어_맞춘다_빠지는_쪽() {
        // given — 월요일 기준선엔 kim 이 있다. 화요일 회차가 kim 의 권한을 지우고 스냅샷 저장 전에 멈췄다
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        snapshots.save(new TupleSnapshot("월요일", 고정시각, SyncSource.LDAP, Set.of(김))).block();
        snapshots.writing.set(true);
        // 오후에 kim 이 되살아났다
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then — 기준선과 비교하면 쓸 게 없지만, 훑어 맞추기는 있어야 할 줄을 전부 쓴다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).containsExactly(김);
    }

    @Test
    @DisplayName("훑어 맞추기도 삭제 가드를 지킨다 — 지울 줄이 장부의 30% 를 넘으면 지우지 않고 ABORTED, 표시는 남는다")
    void 훑어_맞추기도_가드를_지킨다() {
        // given — 장부에 20줄, LDAP 에는 그중 10명만 남았다(50% 를 지워야 한다)
        writer.stored.addAll(소속튜플(20, "DEV002"));
        snapshots.writing.set(true);
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.SCHEDULED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("기준선 의심").contains("임계치");
        assertThat(writer.stored).hasSize(20);
        assertThat(snapshots.writing).isTrue();
    }

    @Test
    @DisplayName("force=true(FORCED) 면 훑어 맞추기의 가드를 건너뛰고 지운다")
    void 강제면_훑어_맞추기가_지운다() {
        // given
        writer.stored.addAll(소속튜플(20, "DEV002"));
        snapshots.writing.set(true);
        source.willReturn(조직도(IntStream.range(0, 10).mapToObj(i -> "user" + i).collect(Collectors.toSet()), "DEV002"));

        // when
        var run = 동기화한다(SyncTrigger.FORCED);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).hasSize(10);
        assertThat(snapshots.writing).isFalse();
    }
```

`RebuildUseCaseTest`:
- import 에 `dev.starryeye.organization.core.guard.DeletionGuard`, `dev.starryeye.organization.core.guard.DeletionGuardPolicy`, `dev.starryeye.organization.core.model.DirectorySnapshot`(없으면), `java.util.concurrent.atomic.AtomicBoolean`, `java.util.stream.Collectors`, `java.util.stream.IntStream` 를 더한다.
- 필드 `private AtomicInteger 반납;` 을 `private FakeMutationLock lock;` 으로 바꾸고 `setUp()` 을 바꾼다:

```java
        lock = new FakeMutationLock();
        useCase = new RebuildUseCase(source, snapshots, state, writer, scanner,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
```

- 도우미를 바꾼다:

```java
    /** 재적재를 걸고 끝날 때까지 기다린다 — 재적재는 요청과 떼어 돈다(설계 §4). */
    private SyncRun 재적재한다() {
        return 재적재한다(false);
    }

    private SyncRun 재적재한다(boolean force) {
        SyncRun started = useCase.start(force, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }
```

- `기록을_곧바로_주고_끝나면_반납한다` 의 `useCase.start(() -> Mono.fromRunnable(반납::incrementAndGet), 끝남::set)` 를 `useCase.start(false, 끝남::set)` 로,
  `assertThat(반납).hasValue(1);` 을 `assertThat(lock.released).hasValue(1);` 로 바꾸고, `@DisplayName` 의 "반납 수단과" 를 "락 반납과" 로 고친다.
- `쓰기가_차단기로_멈추면_훑지_않는다` 의 마지막 단언 뒤에 한 줄을 더한다(재적재 쓰기 단계 정지도 표시가 남아 다음 동기화가 맞춘다 — 설계 2026-09-30 §4):

```java
        assertThat(snapshots.writing).as("표시가 남아 다음 동기화가 장부를 훑어 맞춘다").isTrue();
```

- 쓰지 않게 된 import(`AtomicInteger`, `Mono` 등)를 정리한다. `FakeMutationLock` import 는 Task 2 에서 이미 더했으면 그대로 둔다.
- 끝에 더한다:

```java
    @Test
    @DisplayName("재적재도 쓰기 전에 '기록 중' 표시를 남기고, 끝나면 표시가 사라진다 — 중간에 멈추면 다음 동기화가 맞춘다")
    void 재적재도_표시를_남긴다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        AtomicBoolean 쓸때_표시 = new AtomicBoolean();
        writer.onApply(() -> 쓸때_표시.set(snapshots.writing.get()));

        // when
        재적재한다();

        // then
        assertThat(쓸때_표시).isTrue();
        assertThat(snapshots.writing).isFalse();
    }

    @Test
    @DisplayName("LDAP 이 0명을 돌려주면 재적재는 장부를 지우지 않고 ABORTED 다 — 설정 실수로 전사 권한이 지워지지 않게")
    void 빈_조직도면_재적재가_지우지_않는다() {
        // given — 장부에 20줄이 있는데 LDAP 이 아무도 돌려주지 않는다
        var 장부 = IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.stored.addAll(장부);
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("임계치").contains("force=true");
        assertThat(writer.stored).hasSize(20);
        assertThat(snapshots.saved).isEmpty();
        assertThat(snapshots.writing).as("표시가 남아 다음 회차도 다시 확인한다").isTrue();
    }

    @Test
    @DisplayName("force=true 면 재적재의 가드를 건너뛰고 지운다")
    void 강제면_재적재가_지운다() {
        // given
        writer.stored.addAll(IntStream.range(0, 20)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet()));
        source.willReturn(DirectorySnapshot.empty());

        // when
        SyncRun run = 재적재한다(true);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
    }
```

`ScimRebuildUseCaseTest` 의 `빈_조직도면_장부를_비운다` 를 둘로 바꾼다:

```java
    @Test
    @DisplayName("조직도가 비었는데 장부에 줄이 있으면 지우지 않고 FAILED 다 — 조직도를 잃은 채로 돌면 장부 전체가 지워진다")
    void 빈_조직도면_장부를_지우지_않는다() {
        // given
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("조직도가 비어 있다 — 장부를 비우려면 mode=wipe");
        assertThat(writer.stored).containsExactly(찌꺼기);
        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    @DisplayName("조직도도 장부도 비어 있으면 할 일 없이 정상 종료한다")
    void 둘_다_비었으면_정상_종료한다() {
        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*FullSyncUseCaseTest' --tests '*RebuildUseCaseTest' --tests '*TupleReconcilerTest' --tests '*DeletionGuardTest' --tests '*ScimRebuildUseCaseTest'`
Expected: 컴파일 실패 — 새 생성자·`start`·`evaluate(int,int,String)`·`DeleteCheck` 가 없다.

- [ ] **Step 3: 구현한다**

`SyncOutcome` 에 더한다:

```java
    /** 이 결론 앞에 한마디를 붙인다 — "기준선 의심"처럼 회차의 사정을 기록에 남길 때(설계 2026-09-30 §4.2). */
    public SyncOutcome withNote(String note) {
        String combined = message == null ? note : note + " / " + message;
        return new SyncOutcome(status, writtenCount, deletedCount, failureCount, snapshotId, combined);
    }
```

`DeletionGuard` 의 `evaluate` 를 둘로 나눈다(메시지는 "기준 스냅샷" 경로에서 지금과 글자 하나 다르지 않다):

```java
    public GuardDecision evaluate(TupleDelta delta, Set<RelationTuple> baseline) {
        return evaluate(delta.toDelete().size(), baseline == null ? 0 : baseline.size(), "기준 스냅샷");
    }

    /**
     * 지울 수와 기준 수로 판정한다 — 장부를 훑어 맞출 때는 기준이 스냅샷이 아니라 훑은 장부다(설계 2026-09-30 §4.3).
     *
     * @param baselineLabel 메시지에 쓸 기준의 이름("기준 스냅샷", "훑은 장부")
     */
    public GuardDecision evaluate(int deleteCount, int baselineCount, String baselineLabel) {
        if (!policy.enabled()) {
            return GuardDecision.proceed();
        }
        if (baselineCount < policy.minBaseline()) {
            return GuardDecision.proceed();
        }

        double ratio = (double) deleteCount / baselineCount;
        if (ratio <= policy.thresholdRatio()) {
            return GuardDecision.proceed();
        }

        return GuardDecision.abort(
                "삭제 대상 %d건(%s %d건의 %.1f%%)이 임계치 %.1f%%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
                        .formatted(deleteCount, baselineLabel, baselineCount, ratio * 100, policy.thresholdRatio() * 100));
    }
```

`TupleReconciler` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다.
 *
 * <p><b>지우기 전에 확인한다(설계 2026-09-30 §4.3).</b> 훑은 뒤 {@link DeleteCheck} 가 멈출 이유를 주면 지우지 않는다 — 삭제 가드(LDAP)나
 * 빈 조직도 규칙(SCIM)이 여기서 걸린다. 쓴 줄은 그대로 둔다(권한을 더하는 쪽이라 해가 없다).
 *
 * <p><b>훑기는 흘려 보내며 비교한다.</b> 장부 전체를 메모리에 모으지 않는다 — 드는 것은 D 와 지울 후보뿐이다.
 *
 * <p>호출자는 D 를 <b>다 읽은 뒤에만</b> 부른다. 읽기가 실패했는데 부르면 빈 D 로 장부를 비운다(점검 M13).
 */
@Slf4j
final class TupleReconciler {

    private TupleReconciler() {
    }

    /** 훑은 뒤·지우기 전에 멈출지 정한다. 멈출 이유를 주면 지우지 않고 {@link Reconciliation#held()} 로 끝난다. */
    @FunctionalInterface
    interface DeleteCheck {

        Optional<String> holdReason(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned);

        DeleteCheck NONE = (desired, stale, scanned) -> Optional.empty();
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return reconcile(writer, scanner, desired, DeleteCheck.NONE);
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DeleteCheck check) {
        return Mono.defer(() -> 쓴다(writer, desired)
                .flatMap(written -> 훑는다(scanner, desired)
                        .flatMap(scan -> {
                            Optional<String> hold = check.holdReason(desired, scan.stale(), scan.scanned());
                            if (hold.isPresent()) {
                                log.warn("장부를 훑었지만 지우지 않는다: {}", hold.get());
                                return Mono.just(Reconciliation.held(written, hold.get()));
                            }
                            return 지운다(writer, scan.stale())
                                    .map(deleted -> Reconciliation.of(written, deleted))
                                    .onErrorResume(TupleWriteAbortedException.class,
                                            stopped -> Mono.just(Reconciliation.stopped(written, stopped)));
                        })));
    }

    private static Mono<TupleWriteResult> 쓴다(RelationTupleWriter writer, Set<RelationTuple> desired) {
        return desired.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.writeOnly(desired));
    }

    private static Mono<TupleWriteResult> 지운다(RelationTupleWriter writer, Set<RelationTuple> stale) {
        return stale.isEmpty()
                ? Mono.just(TupleWriteResult.empty())
                : writer.apply(TupleDelta.deleteOnly(stale));
    }

    private static Mono<Scan> 훑는다(RelationTupleScanner scanner, Set<RelationTuple> desired) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicLong 읽은_줄 = new AtomicLong();
            return scanner.scanAll()
                    .doOnNext(tuple -> 읽은_줄.incrementAndGet())
                    .filter(tuple -> !desired.contains(tuple))
                    .collect(Collectors.toSet())
                    .map(stale -> new Scan(stale, 읽은_줄.get()))
                    .doOnNext(scan -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            scan.scanned(), scan.stale().size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /** 훑은 결과 — 지울 후보와 읽은 줄 수(삭제 가드의 기준). */
    private record Scan(Set<RelationTuple> stale, long scanned) {
    }

    /**
     * 장부 청소의 결과.
     *
     * @param result     쓰기·지우기를 합친 결과 — 실행 기록의 건수가 된다
     * @param ledger     장부에 실제로 있다고 볼 줄 — 새 스냅샷이 된다 (설계 §3.1 4단계)
     * @param stopReason 지우기가 연속 실패 차단기로 멈춘 사유. 멈추지 않았으면 {@code null}
     * @param heldReason 지우기 전 확인이 멈춘 사유. 멈추지 않았으면 {@code null} — 이때는 스냅샷을 남기지 않는다
     */
    record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger, String stopReason, String heldReason) {

        /**
         * 스냅샷에 담을 줄 = 쓰기에 성공한 줄 ∪ 지우기에 실패한 줄. 지우지 못한 찌꺼기를 넣어야 다음 LDAP 동기화가 그 줄을
         * "있는데 없어야 할 줄"로 보고 다시 지운다. 쓰기에 실패한 줄은 뺀다 — 다음 동기화가 다시 쓴다.
         */
        static Reconciliation of(TupleWriteResult written, TupleWriteResult deleted) {
            return 합친다(written, deleted, null);
        }

        /** 지우기가 차단기로 멈췄다. 보내지 않은 지우기는 {@code partial} 에 실패로 들어 있어 {@code ledger} 에 남는다. */
        static Reconciliation stopped(TupleWriteResult written, TupleWriteAbortedException stopped) {
            return 합친다(written, stopped.partial(), stopped.getMessage());
        }

        /** 지우기 전에 멈췄다 — 지운 것이 없다. 호출자는 스냅샷을 남기지 않고 사유로 끝낸다. */
        static Reconciliation held(TupleWriteResult written, String reason) {
            return new Reconciliation(written, Set.copyOf(written.written()), null, reason);
        }

        boolean held() {
            return heldReason != null;
        }

        /** 실행 기록에 남길 결론. 멈췄으면 FAILED(스냅샷은 남긴다), 아니면 실패 유무로 SUCCEEDED·PARTIAL 이다. */
        SyncOutcome outcome(String snapshotId) {
            if (stopReason != null) {
                return SyncOutcome.stopped(result, snapshotId, stopReason);
            }
            return result.hasFailure()
                    ? SyncOutcome.partial(result, snapshotId)
                    : SyncOutcome.succeeded(result, snapshotId);
        }

        private static Reconciliation 합친다(TupleWriteResult written, TupleWriteResult deleted, String stopReason) {
            Set<RelationTuple> ledger = new HashSet<>(written.written());
            deleted.failures().forEach(failure -> ledger.add(failure.tuple()));

            List<TupleFailure> failures = new ArrayList<>(written.failures());
            failures.addAll(deleted.failures());
            return new Reconciliation(
                    new TupleWriteResult(written.written(), deleted.deleted(), failures), ledger, stopReason, null);
        }
    }
}
```

(`TupleReconcilerTest` 등에서 `new Reconciliation(…)` 을 세 인자로 부르는 곳이 있으면 네 번째 `null` 을 더한다.)

`core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * LDAP 장부 맞추기 — 재적재와 "멈춘 뒤 첫 동기화"가 함께 쓴다(설계 2026-09-30 §4).
 *
 * <ol>
 *   <li>쓰기 전에 "기록 중" 표시를 남긴다 — 이 청소가 중간에 멈춰도 다음 회차가 다시 맞춘다.</li>
 *   <li>장부를 D 에 맞추되, 지우기 전에 삭제 가드를 본다({@code force} 면 건너뛴다). 가드에 걸리면 지우지 않고 ABORTED — 스냅샷을 남기지 않으므로
 *       표시가 남아 다음 회차도 다시 확인한다.</li>
 *   <li>끝나면 스냅샷(표시가 사라진다)과 현재상태를 남긴다.</li>
 * </ol>
 */
@RequiredArgsConstructor
final class LedgerAlignment {

    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final DeletionGuard guard;
    private final Clock clock;

    Mono<SyncOutcome> align(DirectorySnapshot directory, Set<RelationTuple> desired, boolean force) {
        TupleReconciler.DeleteCheck check = force ? TupleReconciler.DeleteCheck.NONE : this::가드로_본다;
        return snapshots.markWriting()
                .then(TupleReconciler.reconcile(writer, scanner, desired, check))
                .flatMap(reconciliation -> reconciliation.held()
                        ? Mono.just(SyncOutcome.aborted(reconciliation.heldReason()))
                        : commit(directory, reconciliation));
    }

    private Optional<String> 가드로_본다(Set<RelationTuple> desired, Set<RelationTuple> stale, long scanned) {
        GuardDecision decision = guard.evaluate(stale.size(), Math.toIntExact(scanned), "훑은 장부");
        return decision.aborted() ? Optional.of(decision.message()) : Optional.empty();
    }

    /** 스냅샷에는 장부에 실제로 있다고 볼 줄을 담는다(설계 2026-09-29 §3.1 4단계). 그다음 현재상태를 LDAP 대로 바꾼다. */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger());

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(reconciliation.outcome(snapshot.id()));
    }
}
```

`RebuildUseCase` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * app-ldap 전체 재적재 (설계 2026-09-29 §3). LDAP 을 다시 읽어 장부(OpenFGA store)와 현재상태를 그것에 맞춘다.
 *
 * <p><b>장부를 버리지 않는다.</b> 먼저 LDAP 을 끝까지 읽고(실패하면 장부에 아무것도 하지 않는다), 있어야 할 줄을 전부 쓴 뒤, 장부를
 * 훑어 없어야 할 줄만 지운다({@link LedgerAlignment}). 직전 스냅샷에 기대지 않으므로 기준선 스냅샷이 깨졌거나 스냅샷에 없는 찌꺼기가
 * 있어도 고친다.
 *
 * <p><b>삭제 가드를 지킨다(설계 2026-09-30 §4.3).</b> LDAP 이 설정 실수로 0명을 돌려주면 장부 전체를 지우게 된다 — 지울 줄이 훑은 장부의
 * 임계치를 넘으면 지우지 않고 ABORTED 다. 사람이 확인한 뒤 {@code force} 로 넘긴다.
 */
@Slf4j
public class RebuildUseCase {

    private final DirectorySnapshotSource source;
    private final SyncJobs jobs;
    private final LedgerAlignment alignment;

    public RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                          DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                          DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.jobs = jobs;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);
    }

    /**
     * 작업 락(REBUILD)을 잡고 실행 기록(RUNNING)을 연 뒤 재적재를 요청과 떼어 띄운다. 못 잡으면 {@link LockUnavailableException}.
     *
     * @param force      true 면 삭제 가드를 건너뛴다 — ABORTED 뒤 사람이 확인하고 넘기는 통로다
     * @param onFinished 끝난 기록으로 불린다(지표)
     */
    public Mono<SyncRun> start(boolean force, Consumer<SyncRun> onFinished) {
        return jobs.startLocked(SyncSource.LDAP, SyncTrigger.REBUILD, MutationLock.LockPurpose.REBUILD,
                Mono.defer(() -> rebuild(force)), onFinished);
    }

    private Mono<SyncOutcome> rebuild(boolean force) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            return alignment.align(directory, mapping.tuples(), force);
        });
    }
}
```

`FullSyncUseCase` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LDAP 전체 동기화.
 *
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 실제 성공한 튜플만 새 스냅샷으로 커밋</b>하는 것이다.
 * 실패한 튜플은 새 스냅샷에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 *
 * <p><b>기록 중 표시(설계 2026-09-30 §4).</b> OpenFGA 에 쓰기 직전 스냅샷 포인터에 "기록 중"을 남기고, 스냅샷 저장이 그것을 지운다. 표시가 남아
 * 있으면 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라 기준선을 믿을 수 없다 — 이번 회차는 비교 대신 장부를 훑어 맞춘다({@link LedgerAlignment}).
 * 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일(점검 M2)이 이것으로 막힌다.
 */
@Slf4j
public class FullSyncUseCase {

    static final String 기준선_의심 = "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤";

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final DeletionGuard guard;
    private final SyncJobs jobs;
    private final Clock clock;
    private final LedgerAlignment alignment;

    public FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots,
                           DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner,
                           DeletionGuard guard, SyncJobs jobs, Clock clock) {
        this.source = source;
        this.snapshots = snapshots;
        this.state = state;
        this.writer = writer;
        this.guard = guard;
        this.jobs = jobs;
        this.clock = clock;
        this.alignment = new LedgerAlignment(snapshots, state, writer, scanner, guard, clock);
    }

    /**
     * 작업 락(SYNC)을 잡고 실행 기록(RUNNING)을 연 뒤 동기화를 요청과 떼어 띄운다(설계 2026-09-30 §3). 못 잡으면
     * {@link LockUnavailableException} — 다른 인스턴스가 동기화·재적재 중이다. {@code onFinished} 는 끝난 기록으로 불린다(지표·로그).
     */
    public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
        return jobs.startLocked(SyncSource.LDAP, trigger, MutationLock.LockPurpose.SYNC,
                Mono.defer(() -> synchronize(trigger)), onFinished);
    }

    private Mono<SyncOutcome> synchronize(SyncTrigger trigger) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return snapshots.isWriting().flatMap(writing -> writing
                    ? 훑어_맞춘다(directory, mapping.tuples(), trigger)
                    : 비교해_맞춘다(directory, mapping.tuples(), trigger));
        });
    }

    /** 기준선을 믿을 수 없는 회차 — 재적재와 같은 청소로 한 번 맞춘다. {@code FORCED} 면 삭제 가드를 건너뛴다. */
    private Mono<SyncOutcome> 훑어_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired, SyncTrigger trigger) {
        log.warn(기준선_의심);
        return alignment.align(directory, desired, trigger == SyncTrigger.FORCED)
                .map(outcome -> outcome.withNote(기준선_의심));
    }

    private Mono<SyncOutcome> 비교해_맞춘다(DirectorySnapshot directory, Set<RelationTuple> desired, SyncTrigger trigger) {
        return baseline().flatMap(baseline -> {
            TupleDelta delta = TupleDiff.between(baseline, desired);
            if (delta.isEmpty()) {
                log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                return state.replaceWith(directory).thenReturn(SyncOutcome.noChange());
            }
            if (trigger != SyncTrigger.FORCED) {
                GuardDecision decision = guard.evaluate(delta, baseline);
                if (decision.aborted()) {
                    log.warn("삭제 가드 발동: {}", decision.message());
                    return Mono.just(SyncOutcome.aborted(decision.message()));
                }
            }
            // 표시가 쓰기보다 먼저다 — 표시를 남기지 못하면 쓰지 않는다(설계 2026-09-30 §11)
            return snapshots.markWriting()
                    .then(Mono.defer(() -> writer.apply(delta)))
                    .flatMap(result -> commit(directory, baseline, result))
                    .onErrorResume(TupleWriteAbortedException.class, stopped ->
                            saveSnapshotAndState(directory, baseline, stopped.partial())
                                    .map(snapshotId -> SyncOutcome.stopped(
                                            stopped.partial(), snapshotId, stopped.getMessage())));
        });
    }

    private Mono<Set<RelationTuple>> baseline() {
        return snapshots.findLatest()
                .map(TupleSnapshot::tuples)
                .defaultIfEmpty(Set.of());
    }

    private Mono<SyncOutcome> commit(DirectorySnapshot directory,
                                     Set<RelationTuple> baseline,
                                     TupleWriteResult result) {
        return saveSnapshotAndState(directory, baseline, result)
                .map(snapshotId -> result.hasFailure()
                        ? SyncOutcome.partial(result, snapshotId)
                        : SyncOutcome.succeeded(result, snapshotId));
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 실제 반영된 것, 현재상태는 LDAP 에서 읽은 사실 그대로다.
     *
     * <p>쓰기가 차단기로 멈췄을 때도 여기를 탄다(설계 2026-09-29 §5) — 이미 나간 쓰기를 스냅샷에 담지 않으면 기준선이 장부와 어긋난다.
     * 스냅샷 저장이 "기록 중" 표시를 지운다. 저장한 스냅샷 아이디를 준다.
     */
    private Mono<String> saveSnapshotAndState(DirectorySnapshot directory,
                                              Set<RelationTuple> baseline,
                                              TupleWriteResult result) {
        Set<RelationTuple> committed = new HashSet<>(baseline);
        committed.removeAll(result.deleted());
        committed.addAll(result.written());

        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP),
                now,
                SyncSource.LDAP,
                committed);

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(snapshot.id());
    }
}
```

`ScimRebuildUseCase` — 상수 둘을 더하고(import `java.util.Optional`) `reloadTuples` 를 바꾼다:

```java
    /** 조직도가 비었는데 장부에 지울 줄이 있으면 지우지 않는다(설계 2026-09-30 §4.3) — 조직도를 잃은 채로 돌면 장부 전체가 지워진다. */
    static final String 빈_조직도 = "조직도가 비어 있다 — 장부를 비우려면 mode=wipe";

    private static final TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
            desired.isEmpty() && !stale.isEmpty() ? Optional.of(빈_조직도) : Optional.empty();

    private Mono<SyncOutcome> reloadTuples() {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 빈_조직도면_멈춘다)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(SyncOutcome.failed(reconciliation.heldReason()))
                            : commitTuples(reconciliation));
        });
    }
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java \
  core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java \
  core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java
git commit -F - <<'EOF'
feat: 멈춘 뒤 첫 LDAP 동기화는 장부를 훑어 맞춘다(점검 M2) — 삭제 가드는 훑어 맞추기·재적재에도, 재적재 force, SCIM 빈 조직도 거절

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 5: 하루 1회 표지(`DailyOnce`), 재적재 중이면 아카이빙 건너뛰기

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DailyJobClaims.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/DailyOnce.java`
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeDailyJobClaims.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaims.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`, `DynamoDbConfig.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/DailyOnceTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaimsTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java`

**Interfaces:**
- Consumes: `MutationLock#peek`·`FakeMutationLock`(Task 1).
- Produces: `interface DailyJobClaims { Mono<Boolean> claim(String job, LocalDate day); }`; `DailyOnce(DailyJobClaims claims, Clock clock)` + `<T> Mono<T> run(String job, Mono<T> work)`;
  `DynamoDbDailyJobClaims` 빈(`DynamoDbConfig#dailyJobClaims`); `FakeDailyJobClaims`;
  `SnapshotArchiveUseCase(DirectoryStateRepository state, RelationTupleChecker checker, TupleSnapshotRepository snapshots, SyncRunRepository runs, MutationLock lock, Clock clock)`, `SnapshotArchiveUseCase.재적재_중 = "재적재 중이라 건너뜀"`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/DailyOnceTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 한 번 작업은 클러스터 전체에서 한 번만 (설계 2026-09-30 §5.1).
 */
class DailyOnceTest {

    private static final Instant 오늘_새벽 = Instant.parse("2026-09-30T18:00:00Z");

    @Test
    @DisplayName("같은 날 두 인스턴스가 부르면 표지를 먼저 잡은 쪽만 돈다")
    void 같은_날에는_한_번만_돈다() {
        // given — 표지 저장소를 함께 쓰는 두 인스턴스
        var claims = new FakeDailyJobClaims();
        var 가 = new DailyOnce(claims, Clock.fixed(오늘_새벽, ZoneOffset.UTC));
        var 나 = new DailyOnce(claims, Clock.fixed(오늘_새벽.plusSeconds(1), ZoneOffset.UTC));
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        가.run("scim-archive", 작업).block();
        나.run("scim-archive", 작업).block();

        // then
        assertThat(돈_횟수).hasValue(1);
    }

    @Test
    @DisplayName("다음 날이나 다른 작업은 따로 센다")
    void 날짜와_작업마다_따로_센다() {
        // given
        var claims = new FakeDailyJobClaims();
        var 오늘 = new DailyOnce(claims, Clock.fixed(오늘_새벽, ZoneOffset.UTC));
        var 내일 = new DailyOnce(claims, Clock.fixed(오늘_새벽.plusSeconds(86400), ZoneOffset.UTC));
        AtomicInteger 돈_횟수 = new AtomicInteger();
        Mono<Integer> 작업 = Mono.fromSupplier(돈_횟수::incrementAndGet);

        // when
        오늘.run("scim-archive", 작업).block();
        오늘.run("scim-purge", 작업).block();
        내일.run("scim-archive", 작업).block();

        // then
        assertThat(돈_횟수).hasValue(3);
    }
}
```

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaimsTest.java`:

```java
package dev.starryeye.organization.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDailyJobClaimsTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-09-30T18:00:00Z");
    private static final LocalDate 오늘 = LocalDate.parse("2026-09-30");

    private DynamoDbDailyJobClaims claims;

    @BeforeEach
    void 준비한다() {
        claims = new DynamoDbDailyJobClaims(client, properties, Clock.fixed(지금, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("그날 표지는 한 번만 잡힌다 — 두 번째는 거짓이다")
    void 한_번만_잡힌다() {
        // when
        var 처음 = claims.claim("scim-archive", 오늘).block();
        var 두번째 = claims.claim("scim-archive", 오늘).block();

        // then
        assertThat(처음).isTrue();
        assertThat(두번째).isFalse();
    }

    @Test
    @DisplayName("다른 날·다른 작업의 표지는 따로다")
    void 날짜와_작업마다_따로다() {
        // given
        claims.claim("scim-archive", 오늘).block();

        // when, then
        assertThat(claims.claim("scim-archive", 오늘.plusDays(1)).block()).isTrue();
        assertThat(claims.claim("scim-purge", 오늘).block()).isTrue();
    }

    @Test
    @DisplayName("표지는 사흘 뒤 테이블 TTL 로 사라지도록 만료 시각을 단다")
    void 사흘_뒤_만료된다() {
        // when
        claims.claim("ldap-purge", 오늘).block();

        // then
        var item = client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s("DAILY#ldap-purge#2026-09-30"), Keys.SK, Attrs.s(Keys.META)))
                .build()).join().item();
        assertThat(Attrs.longValue(item, Keys.EXPIRES_AT)).isEqualTo(지금.plusSeconds(3 * 86400).getEpochSecond());
    }
}
```

`SnapshotArchiveUseCaseTest`:
- import `dev.starryeye.organization.core.fake.FakeMutationLock`, `dev.starryeye.organization.core.port.MutationLock`.
- 필드 `private FakeMutationLock lock;` 를 더하고 `setUp()` 의 생성을 바꾼다:

```java
        lock = new FakeMutationLock();
        useCase = new SnapshotArchiveUseCase(state, checker, snapshots, runs, lock,
                Clock.fixed(고정시각, ZoneOffset.UTC));
```

- 끝에 더한다:

```java
    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 오늘 아카이빙을 건너뛰고 ABORTED 로 남긴다 — 잠깐 어긋난 장부를 '실제'로 찍지 않게")
    void 재적재_중이면_건너뛴다() {
        // given
        lock.acquire(MutationLock.LockPurpose.REBUILD).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).isEqualTo("재적재 중이라 건너뜀");
        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    @DisplayName("SCIM 쓰기가 잠깐 쥔 락은 건너뛸 이유가 아니다")
    void 쓰기_락은_건너뛰지_않는다() {
        // given
        lock.acquire(MutationLock.LockPurpose.WRITE).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*DailyOnceTest' --tests '*SnapshotArchiveUseCaseTest'` → 컴파일 실패.
Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDailyJobClaimsTest'` → 컴파일 실패.

- [ ] **Step 3: 구현한다**

`core/src/main/java/dev/starryeye/organization/core/port/DailyJobClaims.java`:

```java
package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * 하루 한 번 도는 작업을 클러스터 전체에서 한 번만 돌리기 위한 표지(설계 2026-09-30 §5.1). 먼저 잡은 인스턴스만 그날 작업을 돌린다.
 */
public interface DailyJobClaims {

    /** 그날 표지를 잡는다. 잡았으면 true, 이미 누가 잡았으면 false. */
    Mono<Boolean> claim(String job, LocalDate day);
}
```

`core/src/main/java/dev/starryeye/organization/core/usecase/DailyOnce.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 하루 한 번 작업을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1, 점검 M15). 인스턴스가 셋이면 매일 03:00 아카이빙(직원 10만 GetItem +
 * BatchCheck 약 2,200번 + 10만 줄 스냅샷)이 세 번 돌고 스냅샷도 셋 쌓였다. 날짜는 앱 시계의 UTC 날짜다.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyOnce {

    private final DailyJobClaims claims;
    private final Clock clock;

    /** 오늘 표지를 잡은 인스턴스만 {@code work} 를 돌린다. 못 잡으면 빈 Mono — 다른 인스턴스가 이미 했다. */
    public <T> Mono<T> run(String job, Mono<T> work) {
        return Mono.defer(() -> {
            LocalDate 오늘 = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            return claims.claim(job, 오늘).flatMap(잡았다 -> {
                if (!잡았다) {
                    log.info("오늘({}) {} 은 다른 인스턴스가 이미 했다 — 건너뛴다", 오늘, job);
                    return Mono.<T>empty();
                }
                return work;
            });
        });
    }
}
```

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeDailyJobClaims.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.DailyJobClaims;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeDailyJobClaims implements DailyJobClaims {

    private final Set<String> 잡힌_표지 = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Boolean> claim(String job, LocalDate day) {
        return Mono.fromSupplier(() -> 잡힌_표지.add(job + "#" + day));
    }
}
```

`Keys` 에 더한다(import `java.time.LocalDate` 가 없으면 더한다):

```java
    /** 하루 1회 작업 표지(설계 2026-09-30 §5.1). 날짜는 yyyy-MM-dd. */
    public static String dailyJobPk(String job, LocalDate day) {
        return "DAILY#" + job + "#" + day;
    }
```

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaims.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DailyJobClaims;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 하루 1회 표지를 "없을 때만 쓰기"로 잡는다(설계 2026-09-30 §5.1). 사흘 뒤 테이블 TTL 로 사라진다 — 날짜가 키에 들어 있어 다음 날은 새 줄이다.
 */
@RequiredArgsConstructor
public class DynamoDbDailyJobClaims implements DailyJobClaims {

    private static final Duration 보관 = Duration.ofDays(3);

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<Boolean> claim(String job, LocalDate day) {
        return Mono.defer(() -> {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.dailyJobPk(job, day)));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(Keys.EXPIRES_AT, Attrs.n(clock.instant().plus(보관).getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            .conditionExpression("attribute_not_exists(#pk)")
                            .expressionAttributeNames(Map.of("#pk", Keys.PK))
                            .build()))
                    .thenReturn(true)
                    .onErrorResume(ConditionalCheckFailedException.class, taken -> Mono.just(false));
        });
    }
}
```

`DynamoDbConfig` 에 빈을 더한다(import `dev.starryeye.organization.core.port.DailyJobClaims`):

```java
    @Bean
    public DailyJobClaims dailyJobClaims(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbDailyJobClaims(client, properties, clock);
    }
```

`SnapshotArchiveUseCase`:
- 필드 `private final MutationLock lock;` 를 `runs` 와 `clock` 사이에 더한다(import `dev.starryeye.organization.core.port.MutationLock`).
- 상수와 메서드를 더하고, 지금의 `execute()` 본문은 `아카이빙한다()` 로 이름만 바꾼다:

```java
    static final String 재적재_중 = "재적재 중이라 건너뜀";

    /**
     * 재적재가 작업 락을 쥐고 있으면 오늘은 건너뛴다(설계 2026-09-30 §5.2) — 재적재의 쓰기와 지우기 사이에 잠깐 어긋난 장부를 "실제"로 찍으면
     * 거짓 어긋남 경고가 감사 기록에 남는다. 락은 들여다보기만 한다 — 잡으면 몇 분 동안 SCIM 쓰기가 전부 503 이다.
     */
    public Mono<SyncRun> execute() {
        return 재적재_중인가().flatMap(rebuilding -> rebuilding ? 건너뛴다() : 아카이빙한다());
    }

    /** 들여다보지 못하면 재적재가 아니라고 보고 진행한다 — 아카이빙을 통째로 잃는 것보다 낫다. */
    private Mono<Boolean> 재적재_중인가() {
        return lock.peek()
                .map(purpose -> purpose == MutationLock.LockPurpose.REBUILD)
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    log.warn("변경 락을 들여다보지 못했다 — 재적재가 아니라고 보고 아카이빙한다", error);
                    return Mono.just(false);
                });
    }

    private Mono<SyncRun> 건너뛴다() {
        log.warn("재적재 중이라 오늘 아카이빙을 건너뛴다");
        return runs.start(SyncSource.SCIM, SyncTrigger.ARCHIVE)
                .flatMap(run -> runs.finish(run, SyncOutcome.aborted(재적재_중)));
    }
```

(`private Mono<SyncRun> 아카이빙한다()` 는 옛 `execute()` 그대로다.)

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test` → PASS.
Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDailyJobClaimsTest'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DailyJobClaims.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/DailyOnce.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeDailyJobClaims.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaims.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/DailyOnceTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaimsTest.java
git commit -F - <<'EOF'
feat: 하루 1회 작업은 클러스터 전체에서 한 번(DAILY# 표지), 재적재가 락을 쥐면 아카이빙은 ABORTED 로 건너뛴다(점검 M15)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 6: OpenFGA 400 은 재시도 없이 반씩 쪼개고, 차단기는 한 줄도 못 살린 묶음만 센다

**Files:**
- Create: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaErrors.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScanner.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaErrorsTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterSplitTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterTest.java`

**Interfaces:**
- Produces (패키지 전용): `OpenFgaErrors.거절인가(Throwable)`, `OpenFgaErrors.일시_오류만_다시(int maxRetries): reactor.util.retry.Retry`;
  `OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch batch, Function<Batch, Mono<Void>> send, Predicate<Throwable> 거절인가): Mono<TupleWriteResult>`; `Batch#halves()`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`OpenFgaErrorsTest.java`:

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaApiValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFGA 오류를 "다시 보내도 같은 거절"과 "다시 보낼 만한 일시 오류"로 나눈다 (점검 M16, 설계 2026-09-30 §6.1).
 */
class OpenFgaErrorsTest {

    private static FgaApiValidationError 거절() {
        return new FgaApiValidationError("type 'nosuchtype' not found", 400,
                HttpHeaders.of(Map.of(), (name, value) -> true), "{\"code\":\"validation_error\"}");
    }

    @Test
    @DisplayName("거절(400)은 감싸져 와도 알아본다 — 그 밖은 거절이 아니다")
    void 거절을_알아본다() {
        // when, then
        assertThat(OpenFgaErrors.거절인가(거절())).isTrue();
        assertThat(OpenFgaErrors.거절인가(new CompletionException(new IllegalStateException("감쌈", 거절())))).isTrue();
        assertThat(OpenFgaErrors.거절인가(new IllegalStateException("연결 거부"))).isFalse();
    }

    @Test
    @DisplayName("거절은 다시 시도하지 않는다 — 다시 보내도 같다")
    void 거절은_다시_시도하지_않는다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<Void> 보내기 = Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(거절());
        });

        // when, then
        assertThatThrownBy(() -> 보내기.retryWhen(OpenFgaErrors.일시_오류만_다시(2)).block())
                .isInstanceOf(FgaApiValidationError.class);
        assertThat(시도).hasValue(1);
    }

    @Test
    @DisplayName("일시 오류는 다시 시도하고, 다 쓰면 'Retries exhausted' 가 아니라 원래 오류를 던진다")
    void 일시_오류는_다시_시도하고_원인을_남긴다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<Void> 보내기 = Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(new IllegalStateException("연결 거부"));
        });

        // when, then
        assertThatThrownBy(() -> 보내기.retryWhen(OpenFgaErrors.일시_오류만_다시(2)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("연결 거부");
        assertThat(시도).hasValue(3);
    }
}
```

`OpenFgaRelationTupleWriterSplitTest.java`(컨테이너 없음):

```java
package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잘못된 줄 하나가 같은 배치 100줄을 끌고 가지 않는다 (점검 M16, 설계 2026-09-30 §6.2).
 */
class OpenFgaRelationTupleWriterSplitTest {

    /** 테스트용 거절 — OpenFGA 400 을 흉내 낸다. */
    private static final class 거절 extends RuntimeException {
        거절(String message) {
            super(message);
        }
    }

    private static final Predicate<Throwable> 거절인가 = error -> error instanceof 거절;

    private static List<RelationTuple> 줄들(int count) {
        return IntStream.range(0, count).mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002")).toList();
    }

    @Test
    @DisplayName("나쁜 줄 하나가 섞인 배치는 반씩 쪼개 나머지를 반영하고 그 줄만 실패로 남긴다")
    void 나쁜_줄만_실패로_남긴다() {
        // given — 8줄 중 하나가 거절된다
        List<RelationTuple> 줄 = 줄들(8);
        RelationTuple 나쁜_줄 = 줄.get(5);
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return batch.tuples().contains(나쁜_줄) ? Mono.error(new 거절("없는 타입")) : Mono.empty();
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄), send, 거절인가).block();

        // then — 8 → 4 → 2 → 1 로 좁혀 간다: 처음 1번 + 층마다 2번씩 세 층
        assertThat(결과.written()).hasSize(7).doesNotContain(나쁜_줄);
        assertThat(결과.failures()).extracting(TupleFailure::tuple).containsExactly(나쁜_줄);
        assertThat(결과.failures().get(0).reason()).contains("없는 타입");
        assertThat(보낸_횟수).hasValue(7);
    }

    @Test
    @DisplayName("일시 오류는 쪼개지 않는다 — 쪼개도 같이 실패한다")
    void 일시_오류는_쪼개지_않는다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return Mono.error(new IllegalStateException("연결 거부"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄들(8)), send, 거절인가).block();

        // then
        assertThat(보낸_횟수).hasValue(1);
        assertThat(결과.failures()).hasSize(8);
        assertThat(결과.written()).isEmpty();
    }

    @Test
    @DisplayName("다 거절이면(인가 모델이 없음) 쪼개기가 폭주하지 않는다 — 배치 n줄에 호출 2n−1 번")
    void 다_거절이면_호출이_묶인다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        Function<Batch, Mono<Void>> send = batch -> {
            보낸_횟수.incrementAndGet();
            return Mono.error(new 거절("모델 없음"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.쪼개며_보낸다(Batch.writes(줄들(8)), send, 거절인가).block();

        // then
        assertThat(보낸_횟수).hasValue(15);
        assertThat(결과.failures()).hasSize(8);
        assertThat(결과.written()).isEmpty();
    }
}
```

`OpenFgaRelationTupleWriterBreakerTest` 끝에 더한다(import `dev.starryeye.organization.core.model.TupleFailure`, `java.util.Set`):

```java
    @Test
    @DisplayName("쪼개서 일부라도 살린 배치는 연속 실패로 세지 않는다 — 나쁜 줄이 여기저기 있어도 끝까지 보낸다")
    void 일부라도_살린_배치는_세지_않는다() {
        // given — 한 줄도 못 살린 배치와 일부 살린 배치가 번갈아 온다. 옛 규칙("실패가 하나라도 있으면 셈")이면 세 번째에서 멈춘다
        List<Batch> 배치 = IntStream.range(0, 6)
                .mapToObj(i -> Batch.writes(List.of(
                        RelationTuple.directMember("a" + i, "DEV002"), RelationTuple.directMember("b" + i, "DEV002"))))
                .toList();
        AtomicInteger 차례 = new AtomicInteger();
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> {
            보낸것.add(batch);
            if (차례.getAndIncrement() % 2 == 0) {
                return Mono.just(batch.failed("일시 오류"));
            }
            return Mono.just(new TupleWriteResult(Set.of(batch.tuples().get(0)), Set.of(),
                    List.of(new TupleFailure(batch.tuples().get(1), "거절"))));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send).block();

        // then
        assertThat(보낸것).hasSize(6);
        assertThat(결과.written()).hasSize(3);
    }
```

`OpenFgaRelationTupleWriterTest` 끝에 더한다(import `dev.openfga.sdk.api.client.model.ClientTupleKey`, `dev.openfga.sdk.api.client.model.ClientWriteRequest`, `java.util.HashSet`, `java.util.List`,
`static org.assertj.core.api.Assertions.catchThrowable` 을 더한다):

```java
    @Test
    @DisplayName("없는 타입의 줄 하나가 섞인 배치는 나머지를 반영하고 그 줄만 실패로 남긴다(점검 M16)")
    void 나쁜_줄_하나는_나머지를_끌고_가지_않는다() {
        // given — 정상 5줄과 모델에 없는 타입의 줄 하나를 한 배치로 보낸다
        Set<RelationTuple> 정상 = IntStream.range(0, 5)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        RelationTuple 나쁜_줄 = new RelationTuple("user:bad", "direct_member", "nosuchtype:x");
        Set<RelationTuple> 보낼것 = new HashSet<>(정상);
        보낼것.add(나쁜_줄);

        // when
        var 결과 = writer.apply(TupleDelta.writeOnly(보낼것)).block();

        // then
        assertThat(결과.written()).containsExactlyInAnyOrderElementsOf(정상);
        assertThat(결과.failures()).extracting(failure -> failure.tuple()).containsExactly(나쁜_줄);
        assertThat(check("user:user0", "member", "group:DEV002")).isTrue();
    }

    @Test
    @DisplayName("실제 OpenFGA 의 400 을 거절로 알아본다")
    void 실제_400을_거절로_알아본다() {
        // when
        Throwable thrown = catchThrowable(() -> bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                new ClientTupleKey().user("user:bad").relation("direct_member")._object("nosuchtype:x")))).get());

        // then
        assertThat(OpenFgaErrors.거절인가(thrown)).isTrue();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaErrorsTest' --tests '*SplitTest' --tests '*BreakerTest'`
Expected: 컴파일 실패 — `OpenFgaErrors`·`쪼개며_보낸다` 가 없다.

- [ ] **Step 3: 구현한다**

`OpenFgaErrors.java`:

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.errors.FgaApiValidationError;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * OpenFGA 오류를 "다시 보내도 같은 거절"과 "다시 보낼 만한 일시 오류"로 나눈다(점검 M16, 설계 2026-09-30 §6.1). 쓰기와 장부 훑기가 같은
 * 분류를 쓴다.
 */
final class OpenFgaErrors {

    private OpenFgaErrors() {
    }

    /**
     * OpenFGA 가 요청 내용을 거절했다(400 — 없는 타입, 너무 긴 아이디 등). SDK 가 CompletionException 등으로 감싸 주므로 원인 사슬을 따라가며 본다.
     */
    static boolean 거절인가(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof FgaApiValidationError) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 일시 오류만 {@code maxRetries} 번 200ms 부터 늘려 가며 다시 시도한다. 다 쓰면 원래 오류를 그대로 던진다 — "Retries exhausted" 로 원인을
     * 가리지 않는다(②-1 최종 리뷰).
     */
    static Retry 일시_오류만_다시(int maxRetries) {
        return Retry.backoff(maxRetries, Duration.ofMillis(200))
                .filter(error -> !거절인가(error))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
```

`OpenFgaRelationTupleWriter`:
- import 에 `java.util.function.Predicate` 를 더하고, `reactor.util.retry.Retry`·`java.time.Duration` 이 쓰지 않게 되면 뺀다.
- `보내되_연속_실패면_멈춘다` 안의 `if (!result.hasFailure()) {` 를 `if (!한_줄도_못_살렸다(result)) {` 로 바꾸고, 그 자바독 끝에 한 문단을 더한다:

```java
     *
     * <p><b>세는 것은 "한 줄도 못 살린 묶음"뿐이다(설계 2026-09-30 §6.3).</b> 거절된 묶음은 쪼개 보내므로 일부라도 반영됐으면 OpenFGA 가 죽은 것이
     * 아니다 — 셈을 처음부터 다시 한다. 인가 모델이 통째로 없어 모든 줄이 거절되면 세 묶음 뒤에 멈춘다.
```

- 그 메서드 아래에 더한다:

```java
    /** 묶음에서 한 줄도 반영되지 못했다 — 일시 오류로 다 실패했거나, 쪼개도 전부 거절됐다. OpenFGA 가 죽었다는 신호다. */
    private static boolean 한_줄도_못_살렸다(TupleWriteResult result) {
        return result.hasFailure() && result.written().isEmpty() && result.deleted().isEmpty();
    }
```

- `applyBatch` 를 이것으로 바꾸고 둘을 더한다:

```java
    private Mono<TupleWriteResult> applyBatch(Batch batch) {
        return 쪼개며_보낸다(batch, this::보낸다, OpenFgaErrors::거절인가);
    }

    /** 한 배치를 보낸다. 일시 오류만 다시 시도한다 — 거절(400)은 다시 보내도 같다. */
    private Mono<Void> 보낸다(Batch batch) {
        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.client().write(toRequest(batch), writeOptions());
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA write 호출 실패", e);
                    }
                })
                .retryWhen(OpenFgaErrors.일시_오류만_다시(properties.getMaxRetries()))
                .then();
    }

    /**
     * 배치를 보내고, OpenFGA 가 거절하면(400) 반으로 나눠 다시 보낸다 — 한 줄까지 좁혀도 거절되면 그 줄만 실패로 남긴다(점검 M16,
     * 설계 2026-09-30 §6.2). 요청 하나가 원자적이라 줄 하나가 걸리면 같은 배치 100줄이 함께 실패했고, 다시 돌려도 같은 99명이 빠졌다.
     * 일시 오류(재시도 뒤에도 실패)는 쪼개지 않는다 — 쪼개도 같이 실패한다.
     *
     * <p>패키지 전용 — 쪼개는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     */
    static Mono<TupleWriteResult> 쪼개며_보낸다(Batch batch, Function<Batch, Mono<Void>> send,
                                            Predicate<Throwable> 거절인가) {
        return send.apply(batch)
                .thenReturn(batch.succeeded())
                .onErrorResume(error -> {
                    if (거절인가.test(error) && batch.tuples().size() > 1) {
                        log.warn("OpenFGA 가 배치 {}건을 거절했다 — 반으로 나눠 다시 보낸다: {}",
                                batch.tuples().size(), rootMessage(error));
                        return Flux.fromIterable(batch.halves())
                                .concatMap(half -> 쪼개며_보낸다(half, send, 거절인가))
                                .reduce(TupleWriteResult.empty(), OpenFgaRelationTupleWriter::merge);
                    }
                    log.error("배치 {}건 적용 실패", batch.tuples().size(), error);
                    return Mono.just(batch.failed(rootMessage(error)));
                });
    }
```

(`rootMessage` 가 인스턴스 메서드면 `static` 으로 바꾼다 — 지금도 `private static` 이다.)

- `Batch` 레코드에 더한다:

```java
        /** 반으로 나눈다 — 거절된 배치에서 나쁜 줄을 찾아 좁힐 때 쓴다(설계 2026-09-30 §6.2). */
        List<Batch> halves() {
            int mid = tuples.size() / 2;
            return List.of(new Batch(tuples.subList(0, mid), delete),
                    new Batch(tuples.subList(mid, tuples.size()), delete));
        }
```

`OpenFgaRelationTupleScanner` — `읽는다` 의 `.retryWhen(Retry.backoff(properties.getMaxRetries(), Duration.ofMillis(200)))` 를
`.retryWhen(OpenFgaErrors.일시_오류만_다시(properties.getMaxRetries()))` 로 바꾸고, 쓰지 않게 된 import(`Retry`, `Duration`)를 정리한다. 그 자바독 끝에 한 문장을 더한다:
"거절(400)은 다시 읽지 않고, 재시도를 다 쓰면 원래 오류를 던져 실행 기록에 진짜 원인이 남는다(설계 2026-09-30 §6.4)."

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :authz-openfga:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaErrors.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScanner.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaErrorsTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterSplitTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterTest.java
git commit -F - <<'EOF'
feat: OpenFGA 400 은 재시도 없이 반씩 쪼개 나쁜 줄만 실패로(점검 M16), 차단기는 한 줄도 못 살린 묶음만 센다, 훑기 재시도 소진은 원래 원인으로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 7: app-scim — 새 `SyncJobs`·재적재·아카이빙 결선, 하루 1회 아카이빙·정리

**Files:**
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ArchiveScheduler.java`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimEndToEndTest.java`

**Interfaces:**
- Consumes: `SyncJobs(runs, lock, renewInterval, lockObserver, timeout)`(Task 2), `ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock)`(Task 2),
  `SnapshotArchiveUseCase(state, checker, snapshots, runs, lock, clock)`·`DailyOnce`·`DailyJobClaims` 빈(Task 5).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimEndToEndTest` 에 더한다(`@Autowired ArchiveScheduler scheduler;` 와 import `static org.awaitility.Awaitility.await`·`java.time.Duration` 이 없으면 더한다.
실행 기록 저장소는 이미 `runs` 로 주입돼 있다). 이 클래스는 순서대로 돌고, `@Order(7)` 은 "실행 기록이 아카이빙 하나뿐"을 단언한다 — 새 테스트는
**맨 뒤(`@Order(10)`)** 에 둔다:

```java
    @Test
    @Order(10)
    @DisplayName("예약 아카이빙은 하루 한 번만 돈다 — 여러 대가 같은 시각에 불러도 기록과 스냅샷은 하나다(점검 M15)")
    void 예약_아카이빙은_하루_한_번이다() {
        // given
        long 전 = 아카이빙_기록_수();

        // when — 두 인스턴스의 스케줄러가 같은 날 부른 것과 같다(표지 저장소가 같은 테이블이다)
        scheduler.스냅샷아카이빙();
        scheduler.스냅샷아카이빙();

        // then — 하나는 돌고, 하나는 건너뛴다
        await().atMost(Duration.ofSeconds(30)).until(() -> 아카이빙_기록_수() == 전 + 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(아카이빙_기록_수()).isEqualTo(전 + 1));
    }

    private long 아카이빙_기록_수() {
        return runs.findRecent(100).collectList().block(Duration.ofSeconds(10)).stream()
                .filter(run -> run.trigger() == dev.starryeye.organization.core.model.SyncTrigger.ARCHIVE)
                .count();
    }
```

(이 테스트 클래스에서 스케줄러를 부르는 것은 이것뿐이다 — 오늘 표지가 비어 있다. 다른 테스트가 부르게 되면 그 전에 이 테스트가 실패하므로 알아챈다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :app-scim:compileTestJava`
Expected: 컴파일 실패 — `ScimUseCaseConfig` 가 옛 생성자를 쓴다(Task 2·5 뒤로 깨져 있다).

- [ ] **Step 3: 결선한다**

`ScimUseCaseConfig`:
- import 에 `dev.starryeye.organization.core.port.DailyJobClaims`, `dev.starryeye.organization.core.usecase.DailyOnce` 를 더한다.
- `syncJobs`·`scimRebuildUseCase`·`snapshotArchiveUseCase` 빈을 바꾸고 `dailyOnce` 를 더한다:

```java
    /**
     * 재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). 작업 락(SCIM 쓰기와 같은 락)을 잡고 갱신하고 반납하는 일도 여기서 한다(설계 2026-09-30 §3) —
     * 락 대기·리스 상실 지표는 SCIM 쓰기와 같은 {@link ScimSyncMetrics} 로 남긴다. 앱이 내려갈 때 도는 재적재를 FAILED("서버 종료로 중단")로 기록하고
     * 락을 반납한다 — DynamoDB 클라이언트보다 먼저 닫힌다(이 빈이 실행 기록 저장소를 거쳐 클라이언트에 기대므로 스프링이 먼저 닫는다).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             ScimSyncMetrics metrics, @Value("${sync.job-timeout:30m}") Duration jobTimeout) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), metrics, jobTimeout);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 RelationTupleScanner scanner,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncJobs jobs,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, scanner, snapshots, jobs, clock);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          RelationTupleChecker checker,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          MutationLock lock,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, checker, snapshots, runs, lock, clock);
    }

    /** 하루 1회 작업(아카이빙·만료 스냅샷 정리)을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1). */
    @Bean
    public DailyOnce dailyOnce(DailyJobClaims claims, Clock clock) {
        return new DailyOnce(claims, clock);
    }
```

`ArchiveScheduler`:
- 필드 `private final DailyOnce dailyOnce;` 를 더한다(import `dev.starryeye.organization.core.usecase.DailyOnce`).
- 두 메서드를 바꾼다:

```java
    /**
     * {@code Mono.defer} 로 감싸는 이유: 유스케이스가 Mono 를 반환하기 전에 동기 예외를 던지면
     * 그것이 스케줄러 메서드 밖으로 새어나가 에러 처리를 건너뛴다. 그러면 실패가 로그에 남지 않는다.
     *
     * <p>하루 1회 표지를 먼저 잡은 인스턴스만 돈다(설계 2026-09-30 §5.1) — 여러 대가 같은 시각에 불러도 스냅샷은 하루 하나다.
     */
    @Scheduled(cron = "${sync.archive-cron:0 0 3 * * *}")
    public void 스냅샷아카이빙() {
        관측하며실행("sync.scim.archive",
                dailyOnce.run("scim-archive", Mono.defer(archive::execute))
                        .doOnNext(run -> log.info("스냅샷 아카이빙 완료: status={} snapshotId={}",
                                run.status(), run.snapshotId()))
                        .doOnError(error -> log.error("스냅샷 아카이빙이 예기치 않게 실패했다", error)));
    }

    /**
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로(최신까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다) 이 정리가
     * 보존 기간이 지난 스냅샷을 지우는 유일한 경로다(최신은 건너뛴다). 끄면(`sync.purge-cron: "-"`) 스냅샷(각 약 10만 아이템)이
     * 끝없이 쌓인다. 하루 1회 표지를 잡은 인스턴스만 돈다.
     */
    @Scheduled(cron = "${sync.purge-cron:0 0 4 * * *}")
    public void 만료스냅샷정리() {
        관측하며실행("sync.scim.purge",
                dailyOnce.run("scim-purge", Mono.defer(snapshots::purgeExpired))
                        .doOnNext(count -> log.info("만료 스냅샷 정리 완료: {}건", count))
                        .doOnError(error -> log.error("만료 스냅샷 정리에 실패했다", error)));
    }
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :app-scim:compileTestJava` → 성공.
Run: `./gradlew :app-scim:test --tests '*ScimEndToEndTest' --tests '*ScimRebuildEndToEndTest' --tests '*TraceCorrelationTest'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java \
  app-scim/src/main/java/dev/starryeye/organization/scim/app/ArchiveScheduler.java \
  app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimEndToEndTest.java
git commit -F - <<'EOF'
feat: app-scim — 작업 락은 SyncJobs 가, 아카이빙·정리는 하루 1회 표지를 잡은 한 대만(점검 M15)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 8: app-ldap — 작업 락, 재적재 `force`, 하루 1회 정리, 실행 가드·시작 때 정리 제거

**Files:**
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java` (전체 교체)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncScheduler.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java` (전체 교체)
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/SnapshotIds.java` (자바독 한 문단)
- Delete: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncExecutionGuard.java`, `InterruptedRunCleanup.java`
- Delete: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncExecutionGuardTest.java`, `InterruptedRunCleanupTest.java`, `SyncSchedulerGuardReleaseTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java` (전체 교체)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncSchedulerTest.java` (신규)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java`

**Interfaces:**
- Consumes: `FullSyncUseCase#start(trigger, onFinished)`·`RebuildUseCase#start(force, onFinished)`·새 생성자(Task 4), `SyncJobs`(Task 2), `DailyOnce`·`DailyJobClaims`(Task 5),
  `MutationLock#acquire/peek`(Task 1), `TupleSnapshotRepository#markWriting/isWriting`·`FakeSnapshotRepository#purgeCalls`(Task 3), `FakeDailyJobClaims`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`AdminSyncControllerTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private FullSyncUseCase fullSync;
    private RebuildUseCase rebuild;
    private SyncRunRepository runs;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        fullSync = Mockito.mock(FullSyncUseCase.class);
        rebuild = Mockito.mock(RebuildUseCase.class);
        runs = Mockito.mock(SyncRunRepository.class);
        client = WebTestClient.bindToController(
                new AdminSyncController(fullSync, rebuild, runs, new SyncMetrics(new SimpleMeterRegistry()))).build();
    }

    private static SyncRun 도는실행(SyncTrigger trigger) {
        return SyncRun.started("run-1", SyncSource.LDAP, trigger, 지금);
    }

    private static SyncRun 완료된실행(SyncTrigger trigger, SyncStatus status) {
        return SyncRun.builder()
                .runId("run-1")
                .source(SyncSource.LDAP)
                .trigger(trigger)
                .startedAt(지금)
                .finishedAt(지금.plusSeconds(5))
                .status(status)
                .writtenCount(12)
                .deletedCount(3)
                .failureCount(0)
                .snapshotId("20260814T030000-LDAP")
                .build();
    }

    @Test
    @DisplayName("수동 실행은 MANUAL 로 동기화를 걸고, 끝나기를 기다리지 않고 202 와 RUNNING 기록을 준다")
    void 수동_실행은_202와_RUNNING을_준다() {
        // given
        Mockito.when(fullSync.start(eq(SyncTrigger.MANUAL), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.MANUAL)));

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.runId").isEqualTo("run-1")
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("force=true 로 요청하면 FORCED 트리거로 걸어 삭제 가드를 우회한다")
    void 강제_실행은_FORCED로_건다() {
        // given
        Mockito.when(fullSync.start(eq(SyncTrigger.FORCED), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");
    }

    @Test
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 409 다")
    void 락을_못_잡으면_409다() {
        // given
        Mockito.when(fullSync.start(any(), any()))
                .thenReturn(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));
        Mockito.when(rebuild.start(Mockito.anyBoolean(), any()))
                .thenReturn(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));

        // when, then
        client.post().uri("/admin/sync/full").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.post().uri("/admin/sync/rebuild").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("유스케이스가 Mono 를 만들기도 전에 던져도 매달리지 않고 5xx 다")
    void 동기_예외는_5xx다() {
        // given
        Mockito.when(fullSync.start(any(), any())).thenThrow(new IllegalStateException("Mono 구성 전 동기 예외"));

        // when, then
        client.post().uri("/admin/sync/full").exchange().expectStatus().is5xxServerError();
    }

    @Test
    @DisplayName("최근 실행 이력을 limit 만큼 조회한다")
    void 최근_이력을_조회한다() {
        // given
        Mockito.when(runs.findRecent(5))
                .thenReturn(Flux.just(완료된실행(SyncTrigger.SCHEDULED, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs?limit=5").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].runId").isEqualTo("run-1")
                .jsonPath("$[0].trigger").isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("limit 이 0 이하면 500 대신 최소값으로 보정해 조회한다")
    void limit_이_음수면_최소값으로_보정된다() {
        // given
        Mockito.when(runs.findRecent(anyInt())).thenReturn(Flux.empty());

        // when, then
        client.get().uri("/admin/sync/runs?limit=-1").exchange()
                .expectStatus().isOk();

        Mockito.verify(runs).findRecent(eq(1));
    }

    @Test
    @DisplayName("limit 이 상한을 넘으면 500 대신 상한값으로 보정해 조회한다")
    void limit_이_상한을_넘으면_상한값으로_보정된다() {
        // given
        Mockito.when(runs.findRecent(anyInt())).thenReturn(Flux.empty());

        // when, then
        client.get().uri("/admin/sync/runs?limit=100000").exchange()
                .expectStatus().isOk();

        Mockito.verify(runs).findRecent(eq(100));
    }

    @Test
    @DisplayName("실행 기록 하나를 번호로 본다")
    void 기록_하나를_본다() {
        // given
        Mockito.when(runs.findById("run-1"))
                .thenReturn(Mono.just(완료된실행(SyncTrigger.REBUILD, SyncStatus.SUCCEEDED)));

        // when, then
        client.get().uri("/admin/sync/runs/run-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
    }

    @Test
    @DisplayName("없는 실행 기록 번호는 404 다")
    void 없는_번호는_404다() {
        // given
        Mockito.when(runs.findById("missing-run")).thenReturn(Mono.empty());

        // when, then
        client.get().uri("/admin/sync/runs/missing-run").exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("rebuild 는 가드를 지키며 재적재를 걸고 202 와 RUNNING 기록을 준다")
    void rebuild_는_가드를_지키며_건다() {
        // given
        Mockito.when(rebuild.start(eq(false), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
        Mockito.verify(rebuild).start(eq(false), any());
    }

    @Test
    @DisplayName("rebuild?force=true 는 삭제 가드를 건너뛰고 건다 — ABORTED 뒤 사람이 확인하고 넘기는 통로")
    void rebuild_force는_가드를_건너뛴다() {
        // given
        Mockito.when(rebuild.start(eq(true), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild?force=true").exchange().expectStatus().isAccepted();
        Mockito.verify(rebuild).start(eq(true), any());
    }

    @Test
    @DisplayName("rebuild 에 mode 를 주면 400 이다 — 재적재 모드는 하나로 합쳐졌다")
    void rebuild_에_mode를_주면_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
    }
}
```

`SyncSchedulerTest.java`(신규):

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeDailyJobClaims;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SyncSchedulerTest {

    private static final Clock 오늘 = Clock.fixed(Instant.parse("2026-09-30T19:00:00Z"), ZoneOffset.UTC);

    /** start() 가 정해 둔 Mono 를 준다. 동기 예외 테스트가 익명 하위 클래스로 덮으므로 final 이 아니다. */
    private static class 정해둔_동기화 extends FullSyncUseCase {

        private final Mono<SyncRun> 결과;
        final AtomicInteger 건_횟수 = new AtomicInteger();

        정해둔_동기화(Mono<SyncRun> 결과) {
            super(null, null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), null, Clock.systemUTC());
            this.결과 = 결과;
        }

        @Override
        public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
            건_횟수.incrementAndGet();
            return 결과;
        }
    }

    private static SyncScheduler 스케줄러(FullSyncUseCase fullSync, FakeSnapshotRepository snapshots,
                                     FakeDailyJobClaims claims) {
        return new SyncScheduler(fullSync, snapshots, new DailyOnce(claims, 오늘),
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);
    }

    @Test
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 정기 동기화는 조용히 건너뛴다 — 같은 초에 도는 두 대")
    void 락을_못_잡으면_조용히_건너뛴다() {
        // given
        var 동기화 = new 정해둔_동기화(Mono.error(new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다")));
        var scheduler = 스케줄러(동기화, new FakeSnapshotRepository(), new FakeDailyJobClaims());

        // when, then
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();
        assertThat(동기화.건_횟수).hasValue(1);
    }

    @Test
    @DisplayName("동기화가 Mono 생성 전에 동기적으로 예외를 던져도 스케줄러 밖으로 새지 않는다")
    void 동기_예외가_새지_않는다() {
        // given
        var 던지는_동기화 = new 정해둔_동기화(null) {
            @Override
            public Mono<SyncRun> start(SyncTrigger trigger, Consumer<SyncRun> onFinished) {
                throw new IllegalStateException("Mono 구성 전 동기 예외");
            }
        };
        var scheduler = 스케줄러(던지는_동기화, new FakeSnapshotRepository(), new FakeDailyJobClaims());

        // when, then
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("만료 스냅샷 정리는 하루 1회 표지를 잡은 인스턴스만 돈다")
    void 정리는_하루_한_번이다() {
        // given — 표지 저장소와 스냅샷 저장소를 함께 쓰는 두 인스턴스
        var claims = new FakeDailyJobClaims();
        var snapshots = new FakeSnapshotRepository();
        var 가 = 스케줄러(new 정해둔_동기화(Mono.empty()), snapshots, claims);
        var 나 = 스케줄러(new 정해둔_동기화(Mono.empty()), snapshots, claims);

        // when
        가.만료스냅샷정리();
        나.만료스냅샷정리();

        // then
        assertThat(snapshots.purgeCalls).hasValue(1);
    }
}
```

`LdapSyncEndToEndTest` 끝(마지막 `동기화한다()` 도우미 위)에 더한다(import `dev.starryeye.organization.core.port.LockLease`, `dev.starryeye.organization.core.port.MutationLock`,
`org.springframework.http.HttpStatus`; 필드 `@Autowired MutationLock lock;`):

```java
    // ---------- 클러스터 락·기준선 의심 (설계 2026-09-30) ----------

    @Test
    @Order(11)
    @DisplayName("다른 인스턴스가 작업 락을 쥐고 있으면 수동 동기화·재적재는 곧바로 409 다")
    void 다른_인스턴스가_락을_쥐면_409다() {
        // given — 다른 인스턴스의 정기 동기화가 락을 쥔 순간
        LockLease lease = lock.acquire(MutationLock.LockPurpose.SYNC).block(Duration.ofSeconds(10));

        try {
            // when, then
            client.post().uri("/admin/sync/full").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
            client.post().uri("/admin/sync/rebuild").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        } finally {
            lock.release(lease).block(Duration.ofSeconds(10));
        }
    }

    @Test
    @Order(12)
    @DisplayName("지난 회차가 기록 전에 멈췄으면 다음 동기화는 장부를 훑어 맞춘다 — 스냅샷에 없던 줄도 지운다(점검 M2)")
    void 기록_중_표시가_남으면_훑어_맞춘다() {
        // given — 지난 회차가 쓰기 시작한 뒤 멈췄다. 그 사이 장부에 스냅샷이 모르는 줄이 생겼다
        잔여튜플을_심는다("user:ghost", "direct_member", "group:DEV001");
        snapshots.markWriting().block(Duration.ofSeconds(10));

        // when
        SyncJobClient.끝까지(client, "/admin/sync/full")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.message").value(message -> assertThat((String) message).contains("기준선 의심"));

        // then
        assertThat(check("user:ghost", "member", "group:DEV001")).isFalse();
        assertThat(snapshots.isWriting().block(Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @Order(13)
    @DisplayName("재적재에 force=true 를 줄 수 있다 — 가드에 걸린 뒤 사람이 넘기는 통로")
    void 재적재에_force를_줄_수_있다() {
        // when, then
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?force=true")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :app-ldap:compileTestJava`
Expected: 컴파일 실패 — 컨트롤러·스케줄러·설정이 옛 API 를 쓴다.

- [ ] **Step 3: 구현한다**

지운다:

```bash
git rm app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncExecutionGuard.java \
  app-ldap/src/main/java/dev/starryeye/organization/ldap/app/InterruptedRunCleanup.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncExecutionGuardTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/InterruptedRunCleanupTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncSchedulerGuardReleaseTest.java
```

`AdminSyncController` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * app-ldap 관리 API. 동기화·재적재는 작업 락을 잡으면 곧바로 202 와 실행 기록(RUNNING)을 주고 따로 돈다(설계 2026-09-29 §4) —
 * 결과는 {@code GET /admin/sync/runs/{runId}} 로 본다. 락은 클러스터 전체에서 하나라 다른 인스턴스가 쥐고 있으면 409 다(설계 2026-09-30 §3).
 */
@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    /** GET /admin/sync/runs 의 limit 을 이 범위로 강제한다 — 0 이하나 과도하게 큰 값을 막는다 */
    private static final int MIN_RUNS_LIMIT = 1;
    private static final int MAX_RUNS_LIMIT = 100;

    private final FullSyncUseCase fullSync;
    private final RebuildUseCase rebuild;
    private final SyncRunRepository runs;
    private final SyncMetrics metrics;

    /**
     * @param force true 면 삭제 가드를 건너뛴다. ABORTED 이후 사람이 판단해서 승인하는 통로다
     */
    @PostMapping("/full")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force) {
        SyncTrigger trigger = force ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={}", trigger);
        return 걸되_겹치면_409(() -> fullSync.start(trigger, metrics::record));
    }

    /**
     * 전체 재적재를 건다. 모드는 하나다 — LDAP 을 다시 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄을 지운다. 지울 줄이 훑은 장부의
     * 임계치를 넘으면(LDAP 이 설정 실수로 0명을 돌려주는 경우 등) 지우지 않고 ABORTED 다 — 사람이 확인한 뒤 {@code force=true} 로 넘긴다.
     * 옛 {@code mode} 파라미터가 오면 400 으로 알린다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(required = false) String mode,
                                         @RequestParam(defaultValue = "false") boolean force) {
        if (mode != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "재적재 모드는 하나로 합쳐졌습니다 — mode 없이 POST /admin/sync/rebuild 를 호출하세요 (받은 mode: " + mode + ")");
        }
        log.warn("전체 재적재 요청: force={}", force);
        return 걸되_겹치면_409(() -> rebuild.start(force, metrics::record));
    }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        int clampedLimit = Math.max(MIN_RUNS_LIMIT, Math.min(limit, MAX_RUNS_LIMIT));
        return runs.findRecent(clampedLimit).map(SyncRunResponse::from);
    }

    /** 실행 기록 하나. 202 로 건 작업의 결과를 여기서 본다. 없으면 404 — 잘못된 번호이거나 보관 기간(30일)이 지났다. */
    @GetMapping("/runs/{runId}")
    public Mono<SyncRunResponse> run(@PathVariable String runId) {
        return runs.findById(runId)
                .map(SyncRunResponse::from)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "실행 기록이 없습니다: " + runId)));
    }

    /** 작업 락을 못 잡았다는 것은 다른 인스턴스가 동기화·재적재 중이라는 뜻이다 — 곧바로 409, 관리자가 잠시 뒤 다시 건다. */
    private static Mono<SyncRunResponse> 걸되_겹치면_409(Supplier<Mono<SyncRun>> start) {
        return Mono.defer(start)
                .map(SyncRunResponse::from)
                .onErrorMap(LockUnavailableException.class, busy ->
                        new ResponseStatusException(HttpStatus.CONFLICT, "다른 동기화·재적재가 진행 중입니다", busy));
    }
}
```

`SyncScheduler`:
- 필드를 `fullSync, snapshots, dailyOnce, metrics, observations` 로 바꾼다(`SyncExecutionGuard` 필드 삭제, import `dev.starryeye.organization.core.usecase.DailyOnce`·`LockUnavailableException` 추가, `Supplier` import 삭제).
- 두 메서드를 바꾼다:

```java
    /**
     * 작업 락은 {@code FullSyncUseCase#start} 가 잡는다(설계 2026-09-30 §3). 여러 대가 같은 초에 불러도 한 대만 돌고, 나머지는 락을 못 잡아 기록 없이
     * 건너뛴다 — 오류가 아니다.
     */
    @Scheduled(cron = "${sync.cron}")
    public void 전체동기화() {
        관측하며실행("sync.ldap.full",
                Mono.defer(() -> fullSync.start(SyncTrigger.SCHEDULED, run -> {
                            metrics.record(run);
                            log.info("스케줄 동기화 완료: status={} written={} deleted={} failed={}",
                                    run.status(), run.writtenCount(), run.deletedCount(), run.failureCount());
                        }))
                        .onErrorResume(LockUnavailableException.class, busy -> {
                            log.info("다른 인스턴스가 동기화·재적재 중이라 이번 스케줄을 건너뛴다");
                            return Mono.empty();
                        })
                        .doOnError(error -> log.error("스케줄 동기화를 걸지 못했다", error)));
    }

    /**
     * 스냅샷은 테이블 TTL 을 쓰지 않으므로(최신까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다) 이 정리가
     * 보존 기간이 지난 스냅샷을 지우는 유일한 경로다(최신은 건너뛴다). 끄면(`sync.purge-cron: "-"`) 스냅샷(각 약 10만 아이템)이
     * 끝없이 쌓인다. 하루 1회 표지를 잡은 인스턴스만 돈다(설계 2026-09-30 §5.1).
     */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        관측하며실행("sync.ldap.purge",
                dailyOnce.run("ldap-purge", Mono.defer(snapshots::purgeExpired))
                        .doOnNext(count -> log.info("만료 스냅샷 정리 완료: {}건", count))
                        .doOnError(error -> log.error("만료 스냅샷 정리에 실패했다", error)));
    }
```

(`관측하며실행` 은 그대로 둔다. 위 코드는 락 경합만 삼키고 나머지 오류는 관측(`observation::error`)까지 흘린다.)

`SnapshotIds` 의 `FORMAT` 자바독 두 번째 문단을 바꾼다(지워진 실행 가드를 가리키고 있다):

```java
     * <p>작업 락이 클러스터 전체에서 겹치기 실행을 막지만(설계 2026-09-30 §3), 실행이 초 단위로 끝나지도 않으므로 밀리초면
     * 현실적인 충돌 창은 닫힌다. 고정 너비·0 패딩이라 사전순 정렬은 그대로 시간순이다.
```

`UseCaseConfig` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.port.DailyJobClaims;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.DailyOnce;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import dev.starryeye.organization.storage.DynamoDbProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SyncProperties.class)
public class UseCaseConfig {

    @Bean
    public DeletionGuard deletionGuard(SyncProperties properties) {
        var config = properties.getDeletionGuard();
        return new DeletionGuard(new DeletionGuardPolicy(
                config.isEnabled(), config.getThresholdRatio(), config.getMinBaseline()));
    }

    /**
     * 동기화·재적재를 요청과 떼어 돌리고(설계 2026-09-29 §4·§5), 작업 락을 클러스터 전체에서 하나로 잡는다(설계 2026-09-30 §3) — app-ldap 을 여러 대
     * 띄워도 한 번에 하나만 돈다. 락을 잡은 작업이 시작할 때 죽은 인스턴스가 남긴 RUNNING 기록을 "비정상 종료로 중단"으로 닫는다.
     * 앱이 내려갈 때 도는 작업을 FAILED("서버 종료로 중단")로 기록하고 락을 반납한다 — 실행 기록 저장소를 거쳐 DynamoDB 클라이언트에 기대므로
     * 스프링이 클라이언트보다 먼저 닫는다.
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, MutationLock lock, DynamoDbProperties dynamoDb,
                             SyncProperties properties) {
        return new SyncJobs(runs, lock, dynamoDb.getLockRenewInterval(), LockObserver.NOOP, properties.getJobTimeout());
    }

    /** 하루 1회 작업(만료 스냅샷 정리)을 클러스터 전체에서 한 번만 돌린다(설계 2026-09-30 §5.1). */
    @Bean
    public DailyOnce dailyOnce(DailyJobClaims claims, Clock clock) {
        return new DailyOnce(claims, clock);
    }

    @Bean
    public FullSyncUseCase fullSyncUseCase(DirectorySnapshotSource source,
                                           TupleSnapshotRepository snapshots,
                                           DirectoryStateRepository state,
                                           RelationTupleWriter writer,
                                           RelationTupleScanner scanner,
                                           DeletionGuard guard,
                                           SyncJobs jobs,
                                           Clock clock) {
        return new FullSyncUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }

    @Bean
    public RebuildUseCase rebuildUseCase(DirectorySnapshotSource source,
                                         TupleSnapshotRepository snapshots,
                                         DirectoryStateRepository state,
                                         RelationTupleWriter writer,
                                         RelationTupleScanner scanner,
                                         DeletionGuard guard,
                                         SyncJobs jobs,
                                         Clock clock) {
        return new RebuildUseCase(source, snapshots, state, writer, scanner, guard, jobs, clock);
    }
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :app-ldap:compileTestJava` → 성공.
Run: `./gradlew :app-ldap:test --tests '*AdminSyncControllerTest' --tests '*SyncSchedulerTest'` → PASS.
Run: `./gradlew :app-ldap:test --tests '*LdapSyncEndToEndTest' --tests '*TraceCorrelationTest'` → PASS.
Run: `git grep -n "SyncExecutionGuard\|InterruptedRunCleanup\|재시작으로 중단" -- '*.java'` → 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add -A app-ldap/src/main/java/dev/starryeye/organization/ldap/app app-ldap/src/test/java/dev/starryeye/organization/ldap/app \
  core/src/main/java/dev/starryeye/organization/core/tuple/SnapshotIds.java
git commit -F - <<'EOF'
feat: app-ldap 도 작업 락 하나로 — 여러 대여도 동기화·재적재는 한 번에 하나(점검 M14), 재적재 force, 정리는 하루 1회, 실행 가드·시작 때 정리 제거

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 9: README·점검 문서

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md` (요약 표 4행)

**Interfaces:** 없음(문서).

- [ ] **Step 1: README 를 고친다**

1. "## 스냅샷이 왜 있는가" 첫 문단의 "재적재의 장부 훑기(아래 "재적재·수동 동기화")만 예외다." 를
   "재적재의 장부 훑기와, 지난 회차가 기록 전에 멈춘 뒤 첫 동기화(아래 "재적재·수동 동기화")만 예외다." 로 바꾼다.

2. "## 인가 모델" 의 "**Read(목록 읽기)는 재적재의 장부 훑기에서만** 쓴다(`RelationTupleScanner`)." 를
   "**Read(목록 읽기)는 장부 훑기에서만** 쓴다(`RelationTupleScanner`) — 재적재와, 지난 회차가 기록 전에 멈춘 뒤 첫 동기화다." 로 바꾼다.

3. "### 재적재·수동 동기화 (두 앱 공통)" 절에서:
   - 첫 문단의 "겹치면 곧바로 **409**다(app-scim은 전역 락, app-ldap은 실행 가드)." 를
     "겹치면 곧바로 **409**다 — 두 앱 모두 DynamoDB 작업 락 하나로 클러스터 전체에서 한 번에 하나만 돈다(아래 "여러 대 띄우기")." 로 바꾼다.
   - "**서버가 내려가면** …" 문단을 이것으로 바꾼다:

```markdown
**서버가 내려가면** 도는 작업을 멈추고 `FAILED`("서버 종료로 중단")로 기록한 뒤 락을 푼다(최대 10초 기다린다).
강제로 죽으면(kill -9, 정전) 그 기록은 `RUNNING`으로 남고 락은 TTL(30초) 뒤 풀린다. 다음에 락을 잡은 작업이 시작하면서 그 기록을
`FAILED`("비정상 종료로 중단")로 닫는다 — 락을 쥐었으니 남은 `RUNNING`은 죽은 작업이다(SCIM 아카이빙 기록은 건드리지 않는다).
```

   - "**app-ldap 재적재는 모드가 하나다.** …" 문단 뒤에 두 문단을 더한다:

```markdown
**기록 전에 멈춘 동기화는 다음 회차가 훑어 맞춘다.** app-ldap 동기화는 OpenFGA에 쓰기 직전 스냅샷 포인터에 "기록 중" 표시를 남기고,
스냅샷 저장이 그것을 지운다. 표시가 남아 있으면(저장 실패·강제 종료·기한 초과·서버 종료·재적재 중단) 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라
스냅샷을 믿을 수 없다. 그래서 다음 동기화는 스냅샷 비교 대신 재적재와 같은 청소(있어야 할 줄을 쓰고, 장부를 훑어 나머지를 지움)를 한 번
하고, 실행 기록에 "기준선 의심 — 장부를 훑어 맞춤"을 남긴다. 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일을 막는다.

**삭제 가드는 훑어 맞추기와 재적재에도 걸린다.** 지울 줄이 훑은 장부의 30%(`sync.deletion-guard`)를 넘으면 지우지 않고 `ABORTED`다 —
LDAP이 설정 실수로 0명을 돌려주면 장부 전체가 지워지던 것을 막는다. 사람이 확인한 뒤 `POST /admin/sync/full?force=true` 또는
`POST /admin/sync/rebuild?force=true`로 넘긴다. 훑어 맞추기가 가드에 걸리면 "기록 중" 표시가 남아 다음 회차도 다시 확인한다.
app-scim `mode=tuples`는 조직도가 비었는데 장부에 줄이 있으면 지우지 않고 `FAILED`다("조직도가 비어 있다 — 장부를 비우려면 mode=wipe").
```

   - "**기한이 있다.** …" 문단의 "OpenFGA 쓰기 배치가 **3번 연달아** 실패하면(배치마다 재시도한 뒤에도) 남은 배치를 보내지 않고 `FAILED`로 끝낸다" 부분을
     "OpenFGA 쓰기 배치가 **3번 연달아 한 줄도 반영되지 못하면**(배치마다 재시도한 뒤에도) 남은 배치를 보내지 않고 `FAILED`로 끝낸다" 로 바꾸고, 그 문단 끝에 더한다:

```markdown
OpenFGA가 요청 내용을 거절하면(400 — 없는 타입, 너무 긴 아이디 등) 다시 보내도 같으므로 재시도하지 않는다. 대신 그 배치를 반씩 쪼개
다시 보내 나쁜 줄만 실패로 남기고 나머지는 반영한다(나쁜 줄 하나가 100줄을 끌고 가지 않는다).
```

4. 관리 API 표(app-ldap)의 `POST /admin/sync/rebuild` 행 설명 끝에 " — 가드에 걸리면 `?force=true`" 를 더한다.

5. "### app-scim 여러 대 띄우기(동시성 제어)" 절 앞에 새 절을 넣는다:

```markdown
### 여러 대 띄우기 — 작업은 한 번에 하나, 하루 한 번 작업은 한 번만

**두 앱 모두 여러 대를 띄울 수 있다.** 동기화·재적재는 DynamoDB 조건부 쓰기 락 하나(`LOCK#MUTATION`, 앱마다 테이블이 다르다)를 잡은
인스턴스 하나만 돈다. 매일 `sync.cron`에 여러 대가 같은 초에 불러도 한 대만 돌고, 나머지는 로그만 남기고 건너뛴다. 관리 API는 곧바로 409다.
작업 동안 리스를 `dynamodb.lock-renew-interval`마다 갱신하고, 갱신이 실패하면(=남이 가져갔으면) 멈추고 `FAILED`로 남긴다.

**하루 한 번 작업(app-scim 아카이빙·만료 스냅샷 정리, app-ldap 만료 스냅샷 정리)은 `DAILY#<작업>#<날짜>` 표지를 먼저 잡은 인스턴스만
돈다**(날짜는 UTC, 표지는 사흘 뒤 TTL로 사라진다). 아카이빙은 재적재가 락을 쥐고 있으면 그날은 건너뛰고 `ABORTED`("재적재 중이라 건너뜀")로
남긴다 — 재적재 도중의 잠깐 어긋난 장부를 "실제"로 찍지 않기 위해서다. 락은 들여다보기만 한다(잡으면 그동안 SCIM 쓰기가 503이다).
```

6. "### app-scim 여러 대 띄우기(동시성 제어)" 첫 문장 "**`app-scim`은 여러 인스턴스를 액티브-액티브로 띄울 수 있다.**" 는 그대로 두고,
   그 절의 "같은 DynamoDB 조건부 쓰기 전역 락" 설명은 위 절과 같은 락임을 알 수 있게 "같은 DynamoDB 조건부 쓰기 전역 락(`LOCK#MUTATION`)" 으로 고친다.

7. 확인: `grep -n "실행 가드\|재시작으로 중단\|재적재의 장부 훑기에서만" README.md` 결과가 없어야 한다.

- [ ] **Step 2: 점검 문서에 해결을 표시한다**

`docs/superpowers/specs/2026-09-28-full-audit.md` 의 요약 표에서 M2·M14·M15·M16 행 끝 칸의 설명 뒤에 ` **→ 해결(2026-09-30, 슬라이드 ②-2)**` 을 붙인다(C1 행이 쓰는 모양과 같게).

- [ ] **Step 3: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md
git commit -F - <<'EOF'
docs: 여러 대 띄우기(작업 락 하나·하루 1회 표지), 기록 중 표시와 훑어 맞추기, 가드가 재적재에도, 400 쪼개기. 점검 M2·M14·M15·M16 해결 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **한 번에 하나씩** 돌린다(백그라운드면 완료 알림을 받은 뒤 다음).
- 결과(테스트 수·시간, 규모 테스트의 재적재 시간)를 스펙 §9 에 적고 커밋·푸시한 뒤 PR.
