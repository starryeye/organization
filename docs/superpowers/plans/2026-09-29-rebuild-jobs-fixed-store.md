# 재적재를 백그라운드 작업으로, 장부 번호는 고정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 치명 C3(재적재가 HTTP 요청에 묶임)·C2(재적재 뒤 옛 storeId)·C7(기한 없는 재적재)와 중 M13(읽기 전에 장부를 비움)·M17(첫 생성 중복)·M6(Check 가 장부를 준비하지 않음)을 고친다.

**Architecture:** 재적재는 장부(OpenFGA store)를 지우고 다시 만들지 않는다. 있어야 할 줄 D 를 먼저 다 읽고, D 를 전부 쓴 뒤, 새 포트
`RelationTupleScanner`(OpenFGA Read, 재적재 전용)로 장부를 훑어 D 에 없는 줄만 지운다(`TupleReconciler`). 재적재·수동 동기화는 core 의
`SyncJobs` 가 요청과 떼어 돌린다 — 요청은 겹침 검사(락·가드)와 실행 기록 열기까지만 하고 202 + RUNNING 기록으로 곧바로 답한다. `SyncJobs` 는
작업을 기한(`sync.job-timeout`)·서버 종료와 경주시키고, 어떻게 끝나든 먼저 반납하고 그다음 기록한다. OpenFGA 쓰기에는 3배치 연속 실패 차단기를,
`StoreBootstrapper` 에는 동시 첫 생성 수렴과 "모델 등록 뒤에만 번호 기억"을 넣고, Check 도 쓰기와 같은 준비 과정(`resolveStore`)을 탄다.

**Tech Stack:** Java 17, Spring Boot 3.5 / WebFlux, Reactor, AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, Awaitility,
Mockito, Testcontainers(DynamoDB Local, OpenFGA v1.10.2), Lombok.

**Spec:** `docs/superpowers/specs/2026-09-29-rebuild-jobs-fixed-store-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then`), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고**(컴파일 실패 포함) 고친다.
- **장부 번호(storeId)는 영구히 고정한다.** store 를 지우는 코드는 `StoreBootstrapper#convergeAfterCreate` 가 "방금 만든 자기 store" 를 지우는 것 하나뿐이다. `resetStore`·`recreateStore` 를 다시 만들지 않는다.
- **OpenFGA Read API 는 `OpenFgaRelationTupleScanner` 에서만 부르고, 그것은 재적재(`TupleReconciler`)만 쓴다.** 판단·쓰기 경로는 Check·BatchCheck 만.
- Read: 페이지 크기 `100`, `ConsistencyPreference.HIGHER_CONSISTENCY`, 빈 `ClientReadRequest`(= 장부 전체).
- `sync.job-timeout` 기본 `30m`(두 앱). 연속 실패 차단기 한도 `3`(상수, 설정 아님).
- 기록 문구(그대로 쓴다): `서버 종료로 중단`, `재시작으로 중단`, `기한 초과 — N분`(1분 미만이면 `기한 초과 — Nms`).
- API: 재적재·수동 동기화는 **202 Accepted** + `SyncRunResponse`(`status: RUNNING`), 겹치면 **409**, `GET /admin/sync/runs/{runId}` 는 없으면 **404**.
  app-ldap `POST /admin/sync/rebuild` 는 모드가 하나이고 `mode` 파라미터가 오면 **400**. app-scim 모드는 `tuples`(기본)·`wipe`(+`confirm=<테이블명>`) 그대로.
- 작업 끝 순서: **반납 → 기록**. 기한·종료·차단기로 멈춰도 이미 나간 쓰기는 되돌리지 않고 새 스냅샷도 만들지 않는다(직전 스냅샷이 다음 회차의 기준으로 남는다).
- 운영 배포 전이라 이관·하위호환을 만들지 않는다.
- **컴파일 범위:** Task 3 뒤로 `app-scim` 은 Task 7 까지, Task 4 뒤로 `app-ldap` 은 Task 8 까지 컴파일되지 않는다(유스케이스 API 가 바뀌고 앱 결선은 뒤 과제가 고친다). 그 사이 과제는 자기 모듈만 돌린다.
- 서브에이전트(구현자)는 과제에 적힌 **모듈 테스트나 테스트 클래스만** 돌린다. 앱 모듈 전체 `test`·`scaleTest` 는 돌리지 않는다(컨트롤러가 돌린다). Gradle 은 한 번에 하나만.
- 커밋마다 `git push`(브랜치 `audit-rebuild-jobs`, 업스트림 설정돼 있음). 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **HTTP 요청이 202 전후로 끊긴다**(프록시 60초, 클라이언트 취소) — 작업은 끝까지 가서 기록하고 락·가드를 푼다(Task 2 `요청이_끊겨도_작업은_끝까지_간다`, Task 3 `요청이_곧바로_끊겨도_끝까지_가고_반납한다`).
2. **202 로 답한 작업이 아직 도는데 또 건다** — 409 이고, 작업이 반납하면 다시 받는다(Task 8 `도는_동안은_409이고_반납하면_다시_받는다`, Task 7 `다른_작업이_락을_쥐면_409다`).
3. **달이 바뀐 직후 지난달에 시작한 작업의 결과를 본다** — `GET /runs/{runId}` 가 404 가 아니다(Task 1 `지난달_기록도_찾는다`).
4. **기준선 스냅샷이 깨진 상태에서 재적재로 복구하려 한다** — 재적재는 스냅샷을 읽지 않고 끝나야 한다(Task 4 `깨진_기준선에서도_끝난다`).
5. **서버 종료가 시작된 뒤 작업이 걸린다** — 일하지 않고 곧바로 FAILED("서버 종료로 중단") + 반납(Task 2 `종료_뒤의_작업은_곧바로_끝난다`).

---

### Task 1: 실행 기록을 번호로 찾는다 — `SyncRunRepository#findById`

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSyncRunRepository.java` (전체 교체)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`

**Interfaces:**
- Produces: `Mono<SyncRun> SyncRunRepository#findById(String runId)` — 없으면 빈 Mono.
- Produces (가짜): `FakeSyncRunRepository` — `public final List<SyncRun> finished`(끝난 순서), `SyncRun awaitFinished(String runId)`(최대 10초),
  `void failStart(RuntimeException)`, `void seed(SyncRun)`, `findRecent` 는 모든 기록(RUNNING 포함)을 최신순으로, `findById` 구현.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbSyncRunRepositoryTest` 끝에 더한다(필요한 import 는 이미 있다):

```java
    @Test
    @DisplayName("시작한 기록을 번호로 찾으면 RUNNING 이다")
    void 시작한_기록을_번호로_찾는다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.MANUAL).block();

        // when
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found).isNotNull();
        assertThat(found.runId()).isEqualTo(run.runId());
        assertThat(found.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(found.finishedAt()).isNull();
    }

    @Test
    @DisplayName("끝낸 기록을 번호로 찾으면 끝난 상태와 사유가 보인다")
    void 끝낸_기록을_번호로_찾는다() {
        // given
        var run = repository.start(SyncSource.SCIM, SyncTrigger.REBUILD).block();
        repository.finish(run, SyncOutcome.failed("기한 초과 — 30분")).block();

        // when
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(found.message()).isEqualTo("기한 초과 — 30분");
        assertThat(found.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("지난달에 시작한 기록도 번호로 찾는다 — 달이 바뀐 직후에 결과를 보러 와도 404 가 아니다")
    void 지난달_기록도_찾는다() {
        // given — 7월 31일 밤에 시작한 기록
        var 지난달_저장소 = new DynamoDbSyncRunRepository(client, properties,
                Clock.fixed(Instant.parse("2026-07-31T23:59:00Z"), ZoneOffset.UTC));
        var run = 지난달_저장소.start(SyncSource.LDAP, SyncTrigger.REBUILD).block();

        // when — 8월 14일에 찾는다
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found).isNotNull();
        assertThat(found.runId()).isEqualTo(run.runId());
    }

    @Test
    @DisplayName("없는 번호는 빈 결과다")
    void 없는_번호는_빈_결과다() {
        // given
        repository.start(SyncSource.LDAP, SyncTrigger.MANUAL).block();

        // when, then
        assertThat(repository.findById("없는-번호").blockOptional()).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbSyncRunRepositoryTest'`
Expected: 컴파일 실패 — `findById` 가 없다.

- [ ] **Step 3: 포트·DynamoDB·가짜를 고친다**

`SyncRunRepository` 에 더한다:

```java
    /**
     * 실행 기록 하나. 없으면 빈 Mono.
     *
     * <p>관리 API {@code GET /admin/sync/runs/{runId}} 가 쓴다 — 재적재·수동 동기화는 202 로 곧바로 답하고, 결과는 이것으로 본다
     * (설계 2026-09-29 §4). 보관 기간({@code syncrun-retention-days})이 지난 기록은 없다.
     */
    Mono<SyncRun> findById(String runId);
```

`DynamoDbSyncRunRepository` 의 `findRecent` 아래에 더한다:

```java
    /**
     * 이번 달과 지난달 파티션에서 번호로 찾는다. 기록은 보관 기간(기본 30일) 뒤 사라지므로 그보다 앞선 달에는 없다.
     *
     * <p>파티션 하나가 한 달치 기록(수십 건)이라 통째로 읽어도 싸다 — runId 로 찾는 인덱스를 따로 두지 않는다. 이번 달에서 찾으면
     * {@code next()} 가 구독을 끊어 지난달은 읽지 않는다({@link #findRecent} 와 같은 이유).
     */
    @Override
    public Mono<SyncRun> findById(String runId) {
        YearMonth thisMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        return queryMonth(thisMonth)
                .concatWith(queryMonth(thisMonth.minusMonths(1)))
                .filter(run -> runId.equals(run.runId()))
                .next();
    }
```

`FakeSyncRunRepository` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeSyncRunRepository implements SyncRunRepository {

    /** 끝난 기록 — 끝난 순서대로. 작업 스레드가 쓰고 테스트 스레드가 읽는다. */
    public final List<SyncRun> finished = new CopyOnWriteArrayList<>();

    /** runId → 지금 기록(RUNNING 이거나 끝난 것). 넣은 순서를 지킨다. */
    private final Map<String, SyncRun> 기록 = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Sinks.One<SyncRun>> 끝남 = new ConcurrentHashMap<>();
    private final Instant now;
    private RuntimeException startFailure;

    public FakeSyncRunRepository(Instant now) {
        this.now = now;
    }

    /** 설정하면 {@link #start} 가 이 예외로 실패한다. 실행 기록을 열지 못하는 경로를 보는 데 쓴다. */
    public void failStart(RuntimeException failure) {
        this.startFailure = failure;
    }

    /** 기록을 직접 심는다. 지난 프로세스가 남긴 RUNNING 기록 같은 것을 흉내 낸다. */
    public void seed(SyncRun run) {
        기록.put(run.runId(), run);
    }

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        return Mono.defer(() -> {
            if (startFailure != null) {
                return Mono.error(startFailure);
            }
            SyncRun run = SyncRun.started(UUID.randomUUID().toString(), source, trigger, now);
            기록.put(run.runId(), run);
            return Mono.just(run);
        });
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        return Mono.fromCallable(() -> {
            SyncRun done = run.finished(outcome, now);
            기록.put(done.runId(), done);
            finished.add(done);
            끝남신호(done.runId()).tryEmitValue(done);
            return done;
        });
    }

    @Override
    public Flux<SyncRun> findRecent(int limit) {
        List<SyncRun> 최신순;
        synchronized (기록) {
            최신순 = new ArrayList<>(기록.values());
        }
        Collections.reverse(최신순);
        return Flux.fromIterable(최신순).take(limit);
    }

    @Override
    public Mono<SyncRun> findById(String runId) {
        return Mono.fromSupplier(() -> 기록.get(runId));
    }

    /**
     * 이 기록이 끝날 때까지 기다린다(최대 10초). 요청과 떼어 도는 작업의 결과를 테스트가 받는 자리다.
     * 10초 안에 안 끝나면 {@code IllegalStateException} 이다.
     */
    public SyncRun awaitFinished(String runId) {
        return 끝남신호(runId).asMono().block(Duration.ofSeconds(10));
    }

    private Sinks.One<SyncRun> 끝남신호(String runId) {
        return 끝남.computeIfAbsent(runId, id -> Sinks.one());
    }
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbSyncRunRepositoryTest'` → PASS.
Run: `./gradlew :core:test` → PASS(가짜를 쓰는 기존 테스트 — `SnapshotArchiveUseCaseTest` 등 — 가 그대로 통과).

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSyncRunRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java
git commit -m "feat: 실행 기록을 번호로 찾는다(이번 달·지난달) — 202 로 건 작업의 결과를 볼 자리"
git push
```

---

### Task 2: `SyncJobs` — 작업을 요청과 떼어 돌리고, 기한·서버 종료로 멈춘다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java`

**Interfaces:**
- Consumes: `FakeSyncRunRepository#awaitFinished/failStart/finished` (Task 1).
- Produces: `public class SyncJobs`
  - `public SyncJobs(SyncRunRepository runs, Duration timeout)`
  - `public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work, Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished)` — RUNNING 기록을 준다. 구독할 때마다 작업 하나가 뜬다.
  - `public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work, Supplier<Mono<Void>> release)` — `onFinished` 없음.
  - `public void shutdown()` — 앱 종료 때(스프링 `destroyMethod`).
  - `static final String 종료_사유 = "서버 종료로 중단"`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 동기화·재적재 작업이 요청과 떨어져 돈다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 */
class SyncJobsTest {

    private static final Instant 지금 = Instant.parse("2026-09-29T03:00:00Z");

    private FakeSyncRunRepository runs;
    private SyncJobs jobs;
    private AtomicInteger 반납;
    private Supplier<Mono<Void>> 반납수단;

    @BeforeEach
    void 준비한다() {
        runs = new FakeSyncRunRepository(지금);
        jobs = new SyncJobs(runs, Duration.ofMinutes(1));
        반납 = new AtomicInteger();
        반납수단 = () -> Mono.fromRunnable(반납::incrementAndGet);
    }

    private SyncRun 건다(Mono<SyncOutcome> work) {
        return jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, work, 반납수단).block();
    }

    @Test
    @DisplayName("실행 기록을 열어 RUNNING 으로 곧바로 돌려주고, 작업은 따로 끝까지 가서 결과를 기록한다")
    void RUNNING을_곧바로_돌려주고_작업은_따로_끝난다() {
        // given — 테스트가 끝을 쥐고 있는 작업
        Sinks.One<SyncOutcome> 작업 = Sinks.one();

        // when
        SyncRun 열린것 = 건다(작업.asMono());

        // then — 작업이 끝나지 않았는데도 기록이 왔다
        assertThat(열린것.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.finished).isEmpty();

        // when — 작업이 끝나면
        작업.tryEmitValue(SyncOutcome.noChange());

        // then
        SyncRun 끝난것 = runs.awaitFinished(열린것.runId());
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("요청 구독이 끊겨도 작업은 끝까지 가서 결과를 기록하고 반납한다 — 앞단 프록시가 연결을 끊은 경우")
    void 요청이_끊겨도_작업은_끝까지_간다() {
        // given
        Sinks.One<SyncOutcome> 작업 = Sinks.one();

        // when — 요청이 붙자마자 끊긴다
        jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, 작업.asMono(), 반납수단).subscribe().dispose();
        작업.tryEmitValue(SyncOutcome.noChange());

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.finished).hasSize(1));
        assertThat(runs.finished.get(0).status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("작업이 오류로 끝나면 그 메시지로 FAILED 를 기록한다")
    void 오류는_FAILED로_기록한다() {
        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(Mono.error(new IllegalStateException("LDAP 연결 실패"))).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("LDAP 연결 실패");
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("작업이 결과 없이 끝나도 RUNNING 으로 남기지 않고 FAILED 로 기록한다")
    void 결과_없이_끝나도_FAILED다() {
        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(Mono.empty()).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).contains("결과 없이");
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("기한을 넘기면 남은 일을 멈추고 FAILED(기한 초과)로 기록한 뒤 반납한다")
    void 기한을_넘기면_멈춘다() {
        // given — 끝나지 않는 작업. OpenFGA 가 죽은 채로 배치마다 재시도하는 재적재다
        jobs = new SyncJobs(runs, Duration.ofMillis(200));
        AtomicBoolean 취소됨 = new AtomicBoolean();
        Mono<SyncOutcome> 끝나지_않는_작업 = Mono.<SyncOutcome>never().doOnCancel(() -> 취소됨.set(true));

        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(끝나지_않는_작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).startsWith("기한 초과");
        assertThat(취소됨).as("기한이 지나도 남은 일이 계속 돌면 락을 푼 뒤에 쓴다").isTrue();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("서버가 내려가면 도는 작업을 멈추고 FAILED(서버 종료로 중단)로 기록한 뒤 반납한다")
    void 서버가_내려가면_멈춘다() {
        // given — 일이 실제로 도는 중에 내려간다. RUNNING 을 받은 직후엔 아직 일을 구독하기 전일 수 있다
        AtomicBoolean 시작됨 = new AtomicBoolean();
        AtomicBoolean 취소됨 = new AtomicBoolean();
        SyncRun 열린것 = 건다(Mono.<SyncOutcome>never()
                .doOnSubscribe(subscription -> 시작됨.set(true))
                .doOnCancel(() -> 취소됨.set(true)));
        await().atMost(Duration.ofSeconds(5)).untilTrue(시작됨);

        // when — 종료는 기록·반납이 끝날 때까지 기다린 뒤 돌아온다
        jobs.shutdown();

        // then — 기다렸으므로 곧바로 보인다
        assertThat(runs.finished).hasSize(1);
        SyncRun 끝난것 = runs.finished.get(0);
        assertThat(끝난것.runId()).isEqualTo(열린것.runId());
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("서버 종료로 중단");
        assertThat(취소됨).isTrue();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("종료가 시작된 뒤에 걸린 작업은 일하지 않고 곧바로 FAILED(서버 종료로 중단)로 끝난다")
    void 종료_뒤의_작업은_곧바로_끝난다() {
        // given
        jobs.shutdown();
        AtomicBoolean 시작됨 = new AtomicBoolean();
        Mono<SyncOutcome> 작업 = Mono.fromCallable(() -> {
            시작됨.set(true);
            return SyncOutcome.noChange();
        });

        // when
        SyncRun 끝난것 = runs.awaitFinished(건다(작업).runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("서버 종료로 중단");
        assertThat(시작됨).as("내려가는 서버에서 새 작업이 장부에 쓰기 시작하면 안 된다").isFalse();
        assertThat(반납).hasValue(1);
    }

    @Test
    @DisplayName("실행 기록을 열지 못하면 작업을 시작하지 않고, 반납한 뒤 그 오류로 끝난다")
    void 기록을_못_열면_시작하지_않는다() {
        // given
        runs.failStart(new IllegalStateException("DynamoDB 장애"));
        AtomicBoolean 시작됨 = new AtomicBoolean();
        Mono<SyncOutcome> 작업 = Mono.fromCallable(() -> {
            시작됨.set(true);
            return SyncOutcome.noChange();
        });

        // when, then
        assertThatThrownBy(() -> 건다(작업)).hasMessageContaining("DynamoDB 장애");
        assertThat(시작됨).isFalse();
        assertThat(반납).as("반납한 뒤에 오류를 알린다").hasValue(1);
    }

    @Test
    @DisplayName("반납이 기록보다 먼저다 — 끝남을 본 운영자가 곧바로 다시 걸면 받아진다")
    void 반납이_기록보다_먼저다() {
        // given
        AtomicInteger 반납할때_끝난기록 = new AtomicInteger(-1);
        Supplier<Mono<Void>> 기록을_보는_반납 = () -> Mono.fromRunnable(() -> 반납할때_끝난기록.set(runs.finished.size()));

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL,
                Mono.just(SyncOutcome.noChange()), 기록을_보는_반납).block();
        runs.awaitFinished(열린것.runId());

        // then
        assertThat(반납할때_끝난기록).hasValue(0);
    }

    @Test
    @DisplayName("끝난 기록으로 onFinished 를 부른다 — 지표·로그를 거는 자리다")
    void 끝난_기록으로_onFinished를_부른다() {
        // given
        AtomicReference<SyncRun> 받은것 = new AtomicReference<>();

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.SCHEDULED,
                Mono.just(SyncOutcome.noChange()), 반납수단, 받은것::set).block();
        SyncRun 끝난것 = runs.awaitFinished(열린것.runId());

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(받은것.get()).isEqualTo(끝난것));
    }

    @Test
    @DisplayName("요청의 Reactor Context 를 작업이 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다")
    void 요청의_컨텍스트를_이어받는다() {
        // given
        AtomicReference<String> 본값 = new AtomicReference<>();
        Mono<SyncOutcome> 작업 = Mono.deferContextual(context -> {
            본값.set(context.getOrDefault("traceId", "없음"));
            return Mono.just(SyncOutcome.noChange());
        });

        // when
        SyncRun 열린것 = jobs.start(SyncSource.LDAP, SyncTrigger.MANUAL, 작업, 반납수단)
                .contextWrite(Context.of("traceId", "요청의-trace"))
                .block();
        runs.awaitFinished(열린것.runId());

        // then
        assertThat(본값.get()).isEqualTo("요청의-trace");
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsTest'`
Expected: 컴파일 실패 — `SyncJobs` 가 없다.

- [ ] **Step 3: `SyncJobs` 를 쓴다**

`core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 동기화·재적재 작업을 요청과 떼어 돌린다 (설계 2026-09-29 §4·§5, 점검 C3·C7).
 *
 * <p><b>왜 떼는가.</b> 전에는 작업이 HTTP 요청의 구독 안에서 돌았다. 앞단 프록시가 60초에 연결을 끊으면 구독이 취소돼 작업이
 * 중간에 멈췄고, 실행 기록은 영원히 RUNNING 으로, 락은 풀린 채 남았다. 이제 작업은 자기 구독으로 돌고, 요청은 실행 기록(RUNNING)만
 * 받아 간다.
 *
 * <p>한 작업이 지키는 규칙:
 * <ol>
 *   <li>실행 기록을 연 뒤에만 일을 시작한다. 기록을 열지 못하면 반납하고 그 오류로 끝난다.</li>
 *   <li>일은 서버 종료·기한({@code timeout})과 경주한다. 먼저 온 쪽이 이기고 진 쪽은 취소된다 — 종료면 FAILED("서버 종료로 중단"),
 *       기한이면 FAILED("기한 초과 — N분"). 종료가 이미 시작됐으면 일을 시작하지도 않는다.</li>
 *   <li>어떻게 끝나든 <b>먼저 반납하고 그다음 기록한다.</b> 기록에서 "끝남"을 본 운영자가 곧바로 다시 걸면 받아져야 한다.</li>
 *   <li>요청의 Reactor Context 를 이어받는다 — 작업 로그가 요청과 같은 traceId 로 묶인다.</li>
 * </ol>
 *
 * <p><b>멈춤은 되돌리기가 아니다.</b> 이미 나간 쓰기는 무르지 않는다. 멈췄다는 사실을 FAILED 로 남길 뿐이고, 새 스냅샷을 만들지 않으므로
 * 직전 스냅샷이 다음 회차의 기준으로 남는다 — OpenFGA 쓰기·지우기가 멱등이라 다음 회차나 재적재가 다시 맞춘다.
 */
@Slf4j
public class SyncJobs {

    static final String 종료_사유 = "서버 종료로 중단";

    /** 종료 때 작업이 반납·기록을 마치기를 기다리는 한도. 그 뒤에는 DynamoDB 클라이언트가 닫힌다. */
    private static final Duration 종료_대기 = Duration.ofSeconds(10);

    private final SyncRunRepository runs;
    private final Duration timeout;
    private final Sinks.Empty<Void> 종료 = Sinks.empty();
    private final Set<Mono<Void>> 도는_작업 = ConcurrentHashMap.newKeySet();

    public SyncJobs(SyncRunRepository runs, Duration timeout) {
        this.runs = runs;
        this.timeout = timeout;
    }

    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger,
                               Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        return start(source, trigger, work, release, run -> {
        });
    }

    /**
     * 실행 기록을 열고 {@code work} 를 요청과 떼어 띄운 뒤, 연 기록(RUNNING)을 준다. 구독할 때마다 작업 하나가 뜬다.
     *
     * @param release    겹침 검사(락·가드)를 푸는 수단. 작업이 어떻게 끝나든 정확히 한 번 부른다
     * @param onFinished 끝난 기록으로 부른다(지표·로그). 기록에 실패하면 부르지 않는다
     */
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger, Mono<SyncOutcome> work,
                               Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return Mono.deferContextual(context -> {
            Sinks.One<SyncRun> 열림 = Sinks.one();
            Sinks.Empty<Void> 끝남 = Sinks.empty();
            Mono<Void> 끝남신호 = 끝남.asMono();
            도는_작업.add(끝남신호);

            runs.start(source, trigger)
                    .onErrorResume(error -> 반납한다(release)
                            .then(Mono.fromRunnable(() -> 열림.tryEmitError(error)))
                            .then(Mono.<SyncRun>empty()))
                    .flatMap(run -> {
                        log.info("[{}] 작업 시작: source={} trigger={}", run.runId(), source, trigger);
                        열림.tryEmitValue(run);
                        return 끝까지_돌린다(run, work, release);
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
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, Mono<SyncOutcome> work, Supplier<Mono<Void>> release) {
        Mono<SyncOutcome> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        return Mono.firstWithSignal(종료되면, work)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> {
                    log.error("[{}] 작업 실패: {}", run.runId(), error.getMessage(), error);
                    return Mono.just(SyncOutcome.failed(error.getMessage()));
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

    /** 반납 실패는 작업을 실패시키지 않는다 — 일은 이미 끝났다. 남기기만 한다. */
    private static Mono<Void> 반납한다(Supplier<Mono<Void>> release) {
        return Mono.defer(release)
                .onErrorResume(error -> {
                    log.warn("작업을 끝내며 락·가드를 반납하지 못했다", error);
                    return Mono.empty();
                });
    }

    private static String 사람말로(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + "분" : duration.toMillis() + "ms";
    }
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsTest'` → PASS(11개).
Run: `./gradlew :core:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java
git commit -m "feat: SyncJobs — 작업을 요청과 떼어 돌리고 기한·서버 종료로 멈춘다(반납 뒤 기록, 요청 컨텍스트 이어받기)"
git push
```

---

### Task 3: 장부 안에서 청소한다 — `RelationTupleScanner`·`TupleReconciler`, SCIM 재적재

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/RelationTupleScanner.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java`
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleScanner.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java` (`stored` 추가)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`failLoadAll`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` (전체 교체)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildMode.java` (자바독)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java` (전체 교체)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildLockTest.java` (전체 교체)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java` (전체 교체)

**Interfaces:**
- Consumes: `SyncJobs`(Task 2), `FakeSyncRunRepository#awaitFinished`(Task 1).
- Produces: `interface RelationTupleScanner { Flux<RelationTuple> scanAll(); }` (core port).
- Produces: `final class TupleReconciler` (패키지 전용) — `static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner, Set<RelationTuple> desired)`,
  `record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger)`.
- Produces: `ScimRebuildUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner, TupleSnapshotRepository snapshots, MutationLock lock, Duration renewInterval, LockObserver lockObserver, SyncJobs jobs, Clock clock)`,
  `Mono<SyncRun> start(ScimRebuildMode mode)` — `execute` 는 없어진다.
- Produces (가짜): `FakeTupleWriter#stored`(쓰면 들어가고 지우면 빠지는 가짜 장부), `FakeTupleScanner(FakeTupleWriter)` — `scanCount`, `failWith(RuntimeException)`,
  `FakeStateRepository#failLoadAll(RuntimeException)`.
- 이 과제 뒤로 `app-scim` 은 Task 7 까지 컴파일되지 않는다.

- [ ] **Step 1: 포트·가짜를 만든다(테스트가 쓸 재료)**

`core/src/main/java/dev/starryeye/organization/core/port/RelationTupleScanner.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import reactor.core.publisher.Flux;

/**
 * 장부(OpenFGA store)의 모든 줄을 흘려 준다 — 재적재의 "장부 훑기" 전용 (설계 2026-09-29 §3.2).
 *
 * <p><b>OpenFGA Read API 를 쓰는 유일한 자리다.</b> 평소 쓰기·판단 경로는 Check·BatchCheck({@link RelationTupleChecker})만 쓴다.
 * 재적재는 사람이 거는 드문 백그라운드 작업이라 여기서만 장부 전체를 열거해, 스냅샷에 없는 찌꺼기까지 찾는다. 판단·쓰기 경로에서
 * 부르면 안 된다 — 요청마다 장부 전체를 읽게 된다.
 */
public interface RelationTupleScanner {

    /** 장부의 모든 줄. 캐시가 아니라 지금 있는 것을 읽는다 — 방금 쓴 줄도 나온다. */
    Flux<RelationTuple> scanAll();
}
```

`FakeTupleWriter` — 필드를 더하고 `apply` 의 두 루프에서 장부를 갱신한다(`resetStore` 관련은 Task 5 가 지운다. 여기서는 건드리지 않는다):

```java
    /**
     * 가짜 장부 — 쓰면 들어가고 지우면 빠진다. {@link FakeTupleScanner} 가 이것을 훑는다.
     * 테스트가 직접 넣어 "이미 있던 권한"이나 "이 서버를 거치지 않고 들어온 찌꺼기"를 흉내 낸다.
     */
    public final Set<RelationTuple> stored = Collections.synchronizedSet(new LinkedHashSet<>());
```

```java
        for (RelationTuple tuple : delta.toWrite()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                written.add(tuple);
                this.written.add(tuple);
                stored.add(tuple);
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                deleted.add(tuple);
                this.deleted.add(tuple);
                stored.remove(tuple);
            }
        }
```

(`import java.util.Collections;` 추가.)

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleScanner.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가짜 장부 훑기. {@link FakeTupleWriter#stored} — 쓰면 들어가고 지우면 빠지는 가짜 장부 — 를 그대로 흘린다.
 */
public class FakeTupleScanner implements RelationTupleScanner {

    public final AtomicInteger scanCount = new AtomicInteger();

    private final FakeTupleWriter writer;
    private RuntimeException failure;

    public FakeTupleScanner(FakeTupleWriter writer) {
        this.writer = writer;
    }

    /** 설정하면 훑기가 이 예외로 실패한다. */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public Flux<RelationTuple> scanAll() {
        return Flux.defer(() -> {
            scanCount.incrementAndGet();
            if (failure != null) {
                return Flux.error(failure);
            }
            List<RelationTuple> 사본;
            synchronized (writer.stored) {
                사본 = new ArrayList<>(writer.stored);
            }
            return Flux.fromIterable(사본);
        });
    }
}
```

`FakeStateRepository` — 필드·메서드를 더하고 `loadAll` 을 바꾼다:

```java
    private RuntimeException loadAllFailure;

    /** 설정하면 {@link #loadAll} 이 이 예외로 실패한다. 재적재가 읽기에 실패하는 경로를 보는 데 쓴다. */
    public void failLoadAll(RuntimeException failure) {
        this.loadAllFailure = failure;
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        return Mono.defer(() -> loadAllFailure != null
                ? Mono.error(loadAllFailure)
                : Mono.just(new DirectorySnapshot(users, groups)));
    }
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`ScimRebuildUseCaseTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 재적재 — 장부를 버리지 않고 안에서 청소하고, 요청과 떼어 돈다 (설계 2026-09-29 §3·§4).
 */
class ScimRebuildUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 백엔드_개발본부 = RelationTuple.child("DEV002", "DEV001");
    private static final RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleScanner scanner;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        scanner = new FakeTupleScanner(writer);
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(NOW);
        lock = new FakeMutationLock();
        useCase = new ScimRebuildUseCase(state, writer, scanner, snapshots, lock,
                Duration.ofSeconds(10), LockObserver.NOOP, new SyncJobs(runs, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id + "-이름", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, code + "-조직", Set.of(members));
    }

    private void 조직도를_심는다() {
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
    }

    /** 재적재를 걸고 끝날 때까지 기다린다 — 재적재는 요청과 떼어 돈다(설계 §4). */
    private SyncRun 재적재한다(ScimRebuildMode mode) {
        SyncRun started = useCase.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    // ---------- 요청과 떼어 돈다 ----------

    @Test
    @DisplayName("start 는 락을 잡고 RUNNING 기록을 곧바로 주며, 재적재는 따로 끝까지 간다")
    void start는_RUNNING을_곧바로_준다() {
        // given — 쓰기를 늦춰 도는 중인 것을 볼 수 있게 한다
        조직도를_심는다();
        writer.delay = Duration.ofMillis(300);

        // when
        SyncRun started = useCase.start(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(started.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(started.source()).isEqualTo(SyncSource.SCIM);
        assertThat(started.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(runs.awaitFinished(started.runId()).status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    // ---------- tuples 모드 ----------

    @Test
    @DisplayName("tuples 모드는 현재상태가 요구하는 튜플을 전부 쓴다 — 비활성 직원은 빼고")
    void tuples_모드는_요구되는_튜플을_쓴다() {
        // given
        조직도를_심는다();

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("tuples 모드는 장부를 훑어 조직도가 요구하지 않는 줄만 지운다 — 스냅샷에 없던 찌꺼기도")
    void tuples_모드는_찌꺼기를_지운다() {
        // given — 장부에 누구도 기록하지 않은 줄이 있다
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
        assertThat(writer.deleted).containsExactly(찌꺼기);
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(scanner.scanCount).hasValue(1);
    }

    @Test
    @DisplayName("tuples 모드는 장부를 비우지 않는다 — 쓰고 지우는 내내 기존 권한이 장부에 있다")
    void tuples_모드는_장부를_비우지_않는다() {
        // given — 장부에 이미 kim 의 권한이 있다
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));
        List<Boolean> 쓸때마다_있었나 = new CopyOnWriteArrayList<>();
        writer.onApply(() -> 쓸때마다_있었나.add(writer.stored.contains(김_백엔드)));

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then — 전에는 장부를 지우고 다시 만들어, 그동안 모든 권한 질의가 false 였다
        assertThat(쓸때마다_있었나).isNotEmpty().doesNotContain(false);
        assertThat(writer.stored).contains(김_백엔드);
    }

    @Test
    @DisplayName("tuples 모드는 현재상태를 읽지 못하면 장부에 아무것도 쓰거나 지우지 않고 FAILED 다")
    void 읽기가_실패하면_장부를_건드리지_않는다() {
        // given — 비운 뒤에 읽기가 실패하면 전사 권한이 0 이 된다(점검 M13)
        조직도를_심는다();
        writer.stored.add(김_백엔드);
        state.failLoadAll(new IllegalStateException("DynamoDB 읽기 실패"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("DynamoDB 읽기 실패");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(writer.stored).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("tuples 모드는 현재상태를 건드리지 않는다 — 상태가 곧 진실이다")
    void tuples_모드는_상태를_안_건드린다() {
        // given
        조직도를_심는다();

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("tuples 모드는 SCIM 이력에 남고, 장부에 있다고 볼 줄을 스냅샷에 기록한다")
    void tuples_모드는_이력과_스냅샷을_남긴다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("조직도가 비어 있으면 장부를 비우고 정상 종료한다")
    void 빈_조직도면_장부를_비운다() {
        // given
        writer.stored.add(찌꺼기);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 일부 실패하면 PARTIAL 이고 스냅샷에는 실제로 쓴 것만 담긴다")
    void 쓰기_부분_실패는_PARTIAL이다() {
        // given
        조직도를_심는다();
        writer.failFor(백엔드_개발본부::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("지우지 못한 찌꺼기는 스냅샷에 남는다 — 장부에 아직 있다")
    void 지우지_못한_줄은_스냅샷에_남는다() {
        // given
        조직도를_심는다();
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 백엔드_개발본부, 찌꺼기);
    }

    // ---------- wipe 모드 ----------

    @Test
    @DisplayName("wipe 모드는 장부를 비운 뒤 현재상태의 직원·조직을 전부 지운다")
    void wipe_모드는_장부와_상태를_비운다() {
        // given
        조직도를_심는다();
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();
    }

    @Test
    @DisplayName("wipe 모드는 튜플을 하나도 쓰지 않는다 — 상태가 비니 요구되는 튜플도 없다")
    void wipe_모드는_튜플을_안_쓴다() {
        // given
        조직도를_심는다();
        writer.stored.add(김_백엔드);

        // when
        재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(writer.appliedDeltas).isNotEmpty()
                .allSatisfy(delta -> assertThat(delta.toWrite()).isEmpty());
    }

    @Test
    @DisplayName("wipe 모드는 장부에서 한 줄이라도 못 지우면 조직도를 건드리지 않고 FAILED 다 — 다시 실행할 수 있게")
    void wipe_모드는_청소가_실패하면_조직도를_지킨다() {
        // given — 순서가 뒤집혀 있으면 조직도가 사라지고 낡은 권한만 남는다
        조직도를_심는다();
        writer.stored.add(김_백엔드);
        writer.failFor(김_백엔드::equals);

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("지우지 못해");
        assertThat(state.users).containsOnlyKeys("kim", "lee");
        assertThat(state.groups).containsOnlyKeys("DEV002", "DEV001");
    }

    @Test
    @DisplayName("wipe 모드는 장부를 훑지 못하면 조직도를 건드리지 않고 FAILED 다")
    void wipe_모드는_훑기가_실패하면_조직도를_지킨다() {
        // given
        조직도를_심는다();
        scanner.failWith(new IllegalStateException("OpenFGA 접속 불가"));

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(state.users).containsOnlyKeys("kim", "lee");
    }

    @Test
    @DisplayName("wipe 모드는 RESET 트리거로 이력에 남고, 감사 이력은 지우지 않는다")
    void wipe_모드는_감사_이력을_남긴다() {
        // given — 지우기 전에 이미 쌓여 있던 스냅샷이 있다
        조직도를_심는다();
        재적재한다(ScimRebuildMode.TUPLES);
        int 지우기_전_스냅샷 = snapshots.saved.size();

        // when
        var run = 재적재한다(ScimRebuildMode.WIPE);

        // then — 사고 뒤에 무슨 일이 있었는지 볼 유일한 기록이라 남긴다
        assertThat(run.trigger()).isEqualTo(SyncTrigger.RESET);
        assertThat(snapshots.resetCount).hasValue(0);
        assertThat(snapshots.saved).hasSize(지우기_전_스냅샷);
        assertThat(runs.finished).hasSize(2);
    }

    // ---------- 락 ----------
    // 락 획득·반납·거절의 상세 동작은 ScimRebuildLockTest 가 본다. 여기서는 정상 경로와 실패 경로의 반납만 회귀로 남긴다.

    @Test
    @DisplayName("재적재가 도는 동안, 즉 쓰기가 실제로 일어나는 순간에도 락이 잡혀 있다")
    void 도는_동안_락이_잡혀있다() {
        // given — 쓰기가 실제로 실행되는 순간의 락 상태를 남긴다.
        // 훅 안에서 바로 assertThat 을 부르면 안 된다 — 훅에서 던진 AssertionError 는 작업 실패(FAILED)로 삼켜진다.
        조직도를_심는다();
        AtomicInteger acquiredDuringWrite = new AtomicInteger(-1);
        AtomicInteger releasedDuringWrite = new AtomicInteger(-1);
        writer.onApply(() -> {
            acquiredDuringWrite.set(lock.acquired.get());
            releasedDuringWrite.set(lock.released.get());
        });

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 쓰기 시점에는 락이 잡혀 있었고(반납 전), 끝나면 반납된다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(acquiredDuringWrite).hasValue(1);
        assertThat(releasedDuringWrite).hasValue(0);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("재적재가 실패해도 락은 반납된다")
    void 실패해도_락을_반납한다() {
        // given
        조직도를_심는다();
        scanner.failWith(new IllegalStateException("터짐"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 안 반납하면 이후 모든 SCIM 변경이 영구히 503 이 된다
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(lock.released).hasValue(1);
    }
}
```

`ScimRebuildLockTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 재적재가 SCIM 쓰기와 <b>같은</b> 분산 락을 잡는다 (설계 §4.5).
 *
 * <p>전에는 인메모리 {@code MutationGate} 였다(이제는 삭제됐다). 인스턴스가 둘이면 재적재가
 * 도는 사실 자체를 다른 인스턴스가 몰라 쓰기가 그대로 통과했다 — 막고 있다고 믿지만 안 막혔다.
 */
class ScimRebuildLockTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    private FakeMutationLock lock;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        useCase = new ScimRebuildUseCase(
                new FakeStateRepository(),
                writer,
                new FakeTupleScanner(writer),
                new FakeSnapshotRepository(),
                lock,
                Duration.ofSeconds(10),
                LockObserver.NOOP,
                new SyncJobs(runs, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("재적재는 락을 잡고 끝나면 반납한다")
    void 락을_잡고_반납한다() {
        // when
        runs.awaitFinished(useCase.start(ScimRebuildMode.TUPLES).block().runId());

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 쥐고 있으면 재적재가 시작되지 않는다 — 기록도 열지 않는다")
    void 락이_없으면_시작하지_않는다() {
        // given — 다른 인스턴스의 쓰기나 재적재가 쥐고 있는 상황
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> useCase.start(ScimRebuildMode.TUPLES).block())
                .isInstanceOf(LockUnavailableException.class);

        // 거절은 장부에 아무 일도 일어나지 않았다는 뜻이어야 한다
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("요청이 곧바로 끊겨도 재적재는 끝까지 가고 락을 반납한다 — 앞단 프록시가 연결을 끊은 경우")
    void 요청이_곧바로_끊겨도_끝까지_가고_반납한다() {
        // when — 요청이 붙자마자 끊긴다
        useCase.start(ScimRebuildMode.TUPLES).subscribe().dispose();

        // then — 전에는 여기서 재적재가 취소돼 장부가 반쯤 빈 채로, 기록은 RUNNING 으로 남았다(점검 C3)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runs.finished).hasSize(1));
        assertThat(runs.finished.get(0).status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(lock.released).hasValue(1);
    }
}
```

`ScimRebuildRenewTest` 를 통째로 바꾼다(재적재가 실제로 쓰도록 한 명짜리 조직도를 심는다 — 빈 조직도면 쓰기를 건너뛰어 `delay` 가 듣지 않는다):

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 재적재는 몇 분을 쥐지만 TTL 은 30초다. 갱신하지 않으면 <b>도중에 리스를 잃고</b>
 * 다른 인스턴스의 쓰기가 반쯤 맞춘 장부 위로 들어온다 (설계 §4.4).
 */
class ScimRebuildRenewTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    /** 쓸 튜플이 있는 조직도. 비어 있으면 재적재가 쓰기를 건너뛰어 느린 쓰기로 긴 재적재를 흉내 낼 수 없다. */
    private static FakeStateRepository 한명짜리_조직도() {
        var state = new FakeStateRepository();
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "김", "kim@example.com", true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        return state;
    }

    private static ScimRebuildUseCase 재적재(FakeTupleWriter writer, FakeSyncRunRepository runs,
                                          FakeMutationLock lock, Duration 갱신주기) {
        return new ScimRebuildUseCase(한명짜리_조직도(), writer, new FakeTupleScanner(writer),
                new FakeSnapshotRepository(), lock, 갱신주기, LockObserver.NOOP,
                new SyncJobs(runs, Duration.ofMinutes(1)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SyncRun 끝까지(ScimRebuildUseCase useCase, FakeSyncRunRepository runs) {
        return runs.awaitFinished(useCase.start(ScimRebuildMode.TUPLES).block().runId());
    }

    @Test
    @DisplayName("재적재가 오래 걸리면 리스를 주기적으로 갱신한다")
    void 오래_걸리면_갱신한다() {
        // given — 느린 쓰기로 긴 재적재를 흉내낸다
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(600);
        var runs = new FakeSyncRunRepository(NOW);

        // when
        끝까지(재적재(writer, runs, lock, Duration.ofMillis(100)), runs);

        // then — 100ms 주기로 600ms 를 덮으려면 여러 번 갱신돼야 한다
        assertThat(lock.renewed.get())
                .as("갱신이 없으면 TTL 안에 끝나지 않는 재적재가 리스를 잃는다")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("재적재가 끝나면 갱신도 멈춘다")
    void 끝나면_갱신도_멈춘다() {
        // given — 갱신 주기보다 오래 걸리게 해 하트비트가 최소 한 번은 돌고 나서 끝나게 한다.
        // 성공 횟수(renewed) 로는 못 본다 — 반납 뒤에는 토큰이 안 맞아 매 tick 이 실패하므로
        // 새는 하트비트도 renewed 를 그대로 두어 "안 도는 것"과 구분이 안 된다. 그래서 시도
        // 자체를 세는 renewAttempted 로 본다.
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(150);
        var runs = new FakeSyncRunRepository(NOW);

        // when — 반납이 기록보다 먼저이므로, 끝난 기록을 받은 시점에 하트비트는 이미 멈췄다
        끝까지(재적재(writer, runs, lock, Duration.ofMillis(50)), runs);
        int 끝난직후 = lock.renewAttempted.get();

        // then — 재적재 도중 최소 한 번은 갱신을 시도했어야 한다. 0 이면 이 뒤의 "더 안 늘어난다"
        // 단언이 트리비얼하게 통과해버려 아무것도 증명하지 못한다.
        assertThat(끝난직후)
                .as("재적재가 갱신 주기보다 짧게 끝나면 이 검증 자체가 무의미해진다")
                .isGreaterThan(0);

        // 갱신이 계속 돌면 반납된 락을 갱신하려 들어 로그가 오염된다 — 시도 횟수가 더 늘지 않아야 한다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lock.renewAttempted.get()).isEqualTo(끝난직후));
    }

    @Test
    @DisplayName("재적재 도중 리스를 잃으면 중단하고 FAILED 로 기록한다")
    void 리스를_잃으면_중단하고_FAILED_다() {
        // given — 갱신이 실패하는 순간 이미 리스는 남의 것이다. 재적재는 몇 분짜리라
        // 그 뒤로도 계속 쓰면 남이 쓰고 있는 장부 위에 겹쳐 쓴다.
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(400);
        var runs = new FakeSyncRunRepository(NOW);
        lock.failRenew = true;

        // when
        var run = 끝까지(재적재(writer, runs, lock, Duration.ofMillis(50)), runs);

        // then — 실행 기록이 운영자가 가진 유일한 신호다. SUCCEEDED 로 남기면
        // "mode=tuples 를 한 번 더 돌려야 한다" 와 "할 일 없다" 가 구별되지 않는다.
        assertThat(run.status())
                .as("반쯤 맞춘 장부 위에 남의 쓰기가 들어왔을 수 있는 실행을 초록으로 남기면 안 된다")
                .isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("리스");
        assertThat(runs.finished).hasSize(1);
        assertThat(lock.released).as("중단해도 락은 반납을 시도한다").hasValue(1);
    }
}
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew :core:test --tests '*ScimRebuild*'`
Expected: 컴파일 실패 — `ScimRebuildUseCase` 생성자·`start` 가 없다.

- [ ] **Step 4: `TupleReconciler` 와 `ScimRebuildUseCase` 를 쓴다**

`core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 장부(OpenFGA store)를 있어야 할 줄 D 에 맞춘다 — 재적재의 2~3단계 (설계 2026-09-29 §3.1).
 *
 * <p><b>장부를 비우는 순간이 없다.</b> D 를 먼저 전부 쓰고(이미 있는 줄은 OpenFGA 가 무시한다), 그다음 장부를 훑어 D 에 없는 줄만
 * 지운다. 쓰기와 지우기 사이에 새로 생길 권한이 잠깐 없거나 지워질 권한이 잠깐 남을 뿐, 권한 질의는 내내 정상으로 답한다. 전에는
 * 장부를 지우고 다시 만들어 그동안 모든 질의가 false 였고, 장부 번호가 바뀌었다(점검 C2).
 *
 * <p><b>훑기는 흘려 보내며 비교한다.</b> 장부 전체를 메모리에 모으지 않는다 — 드는 것은 D 와 지울 후보뿐이다.
 *
 * <p>호출자는 D 를 <b>다 읽은 뒤에만</b> 부른다. 읽기가 실패했는데 부르면 빈 D 로 장부를 비운다(점검 M13).
 */
@Slf4j
final class TupleReconciler {

    private TupleReconciler() {
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return 쓴다(writer, desired)
                .flatMap(written -> 지울_줄을_찾는다(scanner, desired)
                        .flatMap(stale -> 지운다(writer, stale))
                        .map(deleted -> Reconciliation.of(written, deleted)));
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

    private static Mono<Set<RelationTuple>> 지울_줄을_찾는다(RelationTupleScanner scanner, Set<RelationTuple> desired) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicLong 읽은_줄 = new AtomicLong();
            return scanner.scanAll()
                    .doOnNext(tuple -> 읽은_줄.incrementAndGet())
                    .filter(tuple -> !desired.contains(tuple))
                    .collect(Collectors.toSet())
                    .doOnNext(stale -> log.info("장부 훑기: {}줄을 읽어 있어야 할 줄에 없는 {}줄을 찾았다 ({}ms)",
                            읽은_줄.get(), stale.size(), Duration.ofNanos(System.nanoTime() - 시작).toMillis()));
        });
    }

    /**
     * 장부 청소의 결과.
     *
     * @param result 쓰기·지우기를 합친 결과 — 실행 기록의 건수가 된다
     * @param ledger 장부에 실제로 있다고 볼 줄 — 새 스냅샷이 된다 (설계 §3.1 4단계)
     */
    record Reconciliation(TupleWriteResult result, Set<RelationTuple> ledger) {

        /**
         * 스냅샷에 담을 줄 = 쓰기에 성공한 줄 ∪ 지우기에 실패한 줄. 지우지 못한 찌꺼기를 넣어야 다음 LDAP 동기화가 그 줄을
         * "있는데 없어야 할 줄"로 보고 다시 지운다. 쓰기에 실패한 줄은 뺀다 — 다음 동기화가 다시 쓴다.
         */
        static Reconciliation of(TupleWriteResult written, TupleWriteResult deleted) {
            Set<RelationTuple> ledger = new HashSet<>(written.written());
            deleted.failures().forEach(failure -> ledger.add(failure.tuple()));

            List<TupleFailure> failures = new ArrayList<>(written.failures());
            failures.addAll(deleted.failures());
            return new Reconciliation(new TupleWriteResult(written.written(), deleted.deleted(), failures), ledger);
        }
    }
}
```

`ScimRebuildUseCase` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * SCIM 인스턴스의 수동 재적재. 관리자가 어긋났다고 판단했을 때 실행한다.
 *
 * <p><b>LDAP 판과 무엇이 다른가.</b> {@link RebuildUseCase} 는 LDAP 을 다시 읽어와 DynamoDB 까지 덮어쓴다. SCIM 은 push 모델이라
 * "전체를 다시 달라"고 말할 상대가 없다 — 그래서 <b>현재상태 자체가 진실</b>이고, {@link ScimRebuildMode#TUPLES} 는 그 상태가
 * 요구하는 튜플에 장부를 맞춘다.
 *
 * <p><b>장부를 버리지 않는다(설계 2026-09-29 §3).</b> 있어야 할 줄을 먼저 다 읽고, 전부 쓴 뒤, 장부를 훑어 없어야 할 줄만 지운다
 * ({@link TupleReconciler}). 읽기가 실패하면 장부에 아무것도 하지 않는다. 장부 번호(storeId)는 바뀌지 않는다.
 *
 * <p><b>{@code WIPE} 는 장부를 비운 뒤에만 조직도를 지운다.</b> 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고 FAILED 다 —
 * 조직도가 남아 있어야 다시 실행할 수 있다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만 살아남는다.
 *
 * <p><b>요청과 떼어 돈다(설계 §4).</b> {@link #start} 는 락을 잡고 실행 기록을 연 뒤 곧바로 돌아온다. 나머지는 {@link SyncJobs} 가
 * 돌리고, 끝나면 락을 반납한 뒤 결과를 기록한다.
 *
 * <p><b>감사 이력은 지우지 않는다.</b> {@code WIPE} 도 스냅샷과 실행 이력은 남긴다. 사고 뒤에 "무슨 일이 있었나"를 볼 유일한 기록인데
 * 그것까지 지우면 조사할 수단이 사라진다.
 */
@Slf4j
@RequiredArgsConstructor
public class ScimRebuildUseCase {

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final TupleSnapshotRepository snapshots;
    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;
    private final SyncJobs jobs;
    private final Clock clock;

    /**
     * 락을 잡고 실행 기록(RUNNING)을 연 뒤 돌려준다. 재적재는 요청과 떼어 돈다 — 결과는 실행 기록으로 본다.
     * 못 잡으면 {@link LockUnavailableException} 이고 아무것도 시작하지 않는다.
     *
     * <p>{@code WIPE} 의 확인값 검증은 호출자(컨트롤러)의 몫이다. 여기까지 왔다는 것은 이미 확인됐다는 뜻이므로 값 자체는 받지 않는다.
     */
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);

        long 시작 = System.nanoTime();
        return lock.acquire(MutationLock.LockPurpose.REBUILD)
                // 재적재는 획득을 재시도하지 않는다(원장 R3) — 못 잡았다는 것이 곧 경합이다.
                .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), false))
                .flatMap(lease -> {
                    Sinks.One<Throwable> 리스상실 = Sinks.one();
                    Disposable heartbeat = 리스를_갱신한다(lease, 리스상실);
                    return jobs.start(SyncSource.SCIM, triggerFor(mode),
                            리스를_잃으면_중단하고(Mono.defer(() -> rebuild(mode)), 리스상실),
                            () -> {
                                heartbeat.dispose();
                                return lock.release(lease);
                            });
                });
    }

    /**
     * 리스를 잃으면 재적재를 <b>취소</b>하고 그 예외로 끝낸다 (설계 §6, "재적재가 리스를 잃음 → 재적재 중단, FAILED 기록").
     *
     * <p>{@link Mono#firstWithSignal} 은 둘 중 먼저 신호를 내는 쪽을 택하고 진 쪽을 취소한다.
     * 재적재가 먼저 끝나면 상실 신호는 취소되고, 상실이 먼저 오면 재적재가 취소된다.
     *
     * <p><b>중단이 되돌리기는 아니다.</b> 이미 나간 쓰기·지우기를 무를 방법은 없다. 여기서 하는 일은 <b>보고</b>다 — {@code SyncRun} 은
     * 운영자가 가진 유일한 신호이고, 반쯤 맞춘 장부 위로 남의 쓰기가 들어왔을지 모르는 실행을 SUCCEEDED 로 남기면
     * "{@code mode=tuples} 를 한 번 더 돌려야 한다" 와 "할 일 없다" 가 구별되지 않는다.
     */
    private Mono<SyncOutcome> 리스를_잃으면_중단하고(Mono<SyncOutcome> rebuild, Sinks.One<Throwable> 리스상실) {
        return Mono.firstWithSignal(rebuild, 리스상실.asMono().flatMap(Mono::error));
    }

    private static Duration 경과(long 시작나노) {
        return Duration.ofNanos(System.nanoTime() - 시작나노);
    }

    private static SyncTrigger triggerFor(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? SyncTrigger.RESET : SyncTrigger.REBUILD;
    }

    /**
     * 재적재가 도는 동안 리스를 계속 미룬다 (설계 §4.4).
     *
     * <p>TTL 은 30초인데 재적재는 몇 분 걸린다. 갱신하지 않으면 도중에 리스를 잃고, 그 순간 다른 인스턴스의 쓰기가
     * <b>반쯤 맞춘 장부</b> 위로 들어온다 — 장부 훑기가 그 정당한 줄을 "있어야 할 줄에 없는 줄"로 지울 수도 있다.
     *
     * <p>갱신이 실패하면 이미 리스를 잃은 것이다. 그 사실을 {@code 리스상실} 로 흘려보내 {@link #리스를_잃으면_중단하고} 가
     * 재적재를 취소하고 FAILED 로 기록하게 한다. {@code concatMap} 이 에러를 그대로 전파하므로 이 구독도 함께 끝나 하트비트가 멈춘다.
     */
    private Disposable 리스를_갱신한다(LockLease lease, Sinks.One<Throwable> 리스상실) {
        return Flux.interval(renewInterval, renewInterval)
                .concatMap(tick -> lock.renew(lease))
                .subscribe(renewed -> {
                }, error -> {
                    log.error("재적재 도중 변경 락 리스를 잃었다. 재적재를 중단하고 FAILED 로 기록한다", error);
                    lockObserver.leaseLost("재적재 도중 리스 상실");
                    리스상실.tryEmitValue(error);
                });
    }

    private Mono<SyncOutcome> rebuild(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? wipe() : reloadTuples();
    }

    // ---------- TUPLES ----------

    private Mono<SyncOutcome> reloadTuples() {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples())
                    .flatMap(this::commitTuples);
        });
    }

    /**
     * 스냅샷에는 <b>장부에 실제로 있다고 볼 줄</b>만 담는다(설계 §3.1 4단계). 의도한 것을 담으면 부분 실패 뒤 스냅샷이 장부보다 앞서게
     * 되고, 그 기록을 믿는 다음 판단이 전부 어긋난다.
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger());
        TupleWriteResult result = reconciliation.result();

        return snapshots.save(snapshot)
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }

    // ---------- WIPE ----------

    /**
     * 있어야 할 줄이 없다(D = ∅)고 보고 장부를 비운 <b>뒤</b> 조직도를 지운다. 빈 스냅샷으로 교체하면 현재상태의 직원·조직이 전부 지워진다.
     */
    private Mono<SyncOutcome> wipe() {
        return TupleReconciler.reconcile(writer, scanner, Set.of()).flatMap(reconciliation -> {
            TupleWriteResult result = reconciliation.result();
            if (result.hasFailure()) {
                return Mono.error(new IllegalStateException(
                        "장부에서 %d줄을 지우지 못해 조직도를 지우지 않았다. 다시 실행하면 남은 줄부터 지운다"
                                .formatted(result.failures().size())));
            }
            return state.replaceWith(DirectorySnapshot.empty())
                    .thenReturn(SyncOutcome.succeeded(result, null))
                    .doOnSuccess(outcome -> log.warn(
                            "SCIM 조직도를 전부 비웠다. IdP 콘솔에서 전체 재프로비저닝을 실행해야 복구된다"));
        });
    }
}
```

`ScimRebuildMode` 의 클래스 자바독과 두 상수 자바독을 바꾼다(`from` 은 그대로):

```java
/**
 * SCIM 재적재가 무엇까지 맞추는가.
 *
 * <p>app-ldap 재적재는 LDAP 을 다시 읽어 장부와 현재상태를 함께 맞춘다. SCIM 은 push 모델이라 "다시 읽어온다"에 해당하는 동작이
 * 없어서, 대신 <b>DynamoDB(조직도)까지 지울지</b>를 고른다.
 */
public enum ScimRebuildMode {

    /**
     * 현재상태(DynamoDB)가 요구하는 튜플을 전부 쓰고, 장부를 훑어 요구하지 않는 줄을 지운다(설계 2026-09-29 §3).
     * 상태는 건드리지 않는다 — SCIM 에서는 그것이 곧 진실이기 때문이다.
     */
    TUPLES,

    /**
     * 장부를 비운 <b>뒤</b> 현재상태의 직원·조직까지 전부 지운다. 장부 청소가 한 줄이라도 실패하면 조직도를 건드리지 않는다.
     *
     * <p><b>되돌릴 수 없다.</b> SCIM 배포에서 DynamoDB 는 조직도의 유일한 사본이고, 스냅샷에는
     * 튜플의 식별자만 있어 이름·이메일·재직 여부를 복원할 수 없다. 실행 뒤에는 반드시
     * <b>IdP 콘솔에서 전체 재프로비저닝</b>을 걸어야 조직도가 돌아온다 — 그 절차는 이 API 밖에 있다.
     */
    WIPE;
```

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test` → PASS(`ScimRebuildUseCaseTest` 17, `ScimRebuildLockTest` 3, `ScimRebuildRenewTest` 3 포함).

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/RelationTupleScanner.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildMode.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleScanner.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildLockTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java
git commit -m "feat: SCIM 재적재가 장부를 버리지 않고 안에서 청소한다 — 먼저 읽고, 쓰고, 훑어 지운다. 요청과 떼어 돈다"
git push
```

---

### Task 4: app-ldap 재적재는 모드 하나로, 전체 동기화도 요청과 떼어 돈다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java` (전체 교체)
- Delete: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildMode.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java` (복구 안내 문구 두 곳)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java` (전체 교체)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java` (한 줄)

**Interfaces:**
- Consumes: `SyncJobs`(Task 2), `TupleReconciler`·`RelationTupleScanner`·`FakeTupleScanner`·`FakeTupleWriter#stored`(Task 3).
- Produces: `RebuildUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots, DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleScanner scanner, SyncJobs jobs, Clock clock)`,
  `Mono<SyncRun> start(Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished)`.
- Produces: `FullSyncUseCase(DirectorySnapshotSource source, TupleSnapshotRepository snapshots, DirectoryStateRepository state, RelationTupleWriter writer, DeletionGuard guard, SyncJobs jobs, Clock clock)`,
  `Mono<SyncRun> start(SyncTrigger trigger, Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished)` — `execute` 는 없어진다.
- `RebuildMode` 가 사라진다. 이 과제 뒤로 `app-ldap` 은 Task 8 까지 컴파일되지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`RebuildUseCaseTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * app-ldap 재적재 — 장부를 버리지 않고 안에서 청소한다 (설계 2026-09-29 §3). 옛 snapshot·store 두 모드를 하나로 합쳤다.
 */
class RebuildUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 찌꺼기 = RelationTuple.directMember("ghost", "DEV002");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleScanner scanner;
    private FakeSyncRunRepository runs;
    private AtomicInteger 반납;
    private RebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        scanner = new FakeTupleScanner(writer);
        runs = new FakeSyncRunRepository(고정시각);
        반납 = new AtomicInteger();
        useCase = new RebuildUseCase(source, snapshots, state, writer, scanner,
                new SyncJobs(runs, Duration.ofMinutes(1)), Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectorySnapshot 조직도(String userId, String groupCode) {
        return new DirectorySnapshot(
                Map.of(userId, new DirectoryUser(userId, "uid=" + userId, userId, userId, null, true)),
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀",
                        Set.of(MemberRef.user(userId)))));
    }

    /** 재적재를 걸고 끝날 때까지 기다린다 — 재적재는 요청과 떼어 돈다(설계 §4). */
    private SyncRun 재적재한다() {
        SyncRun started = useCase.start(() -> Mono.fromRunnable(반납::incrementAndGet), run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("LDAP 을 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄만 지운다 — 스냅샷에 없던 찌꺼기도")
    void 장부_안에서_청소한다() {
        // given — 장부에 kim 의 권한과, 어느 스냅샷에도 없는 찌꺼기가 있다
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        SyncRun run = 재적재한다();

        // then — 옛 snapshot 모드는 스냅샷에 없는 줄을 지우지 못했다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REBUILD);
        assertThat(writer.stored).containsExactly(김_백엔드);
        assertThat(writer.deleted).containsExactly(찌꺼기);
        assertThat(run.deletedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("끝나면 새 스냅샷과 현재상태가 LDAP 대로 남고, 스냅샷을 비우지 않는다")
    void 스냅샷과_현재상태가_남는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);

        // when
        재적재한다();

        // then
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.LDAP);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
        assertThat(snapshots.resetCount).hasValue(0);
        assertThat(state.users).containsOnlyKeys("kim");
        assertThat(state.groups).containsOnlyKeys("DEV002");
    }

    @Test
    @DisplayName("장부를 비우는 순간이 없다 — 쓰고 지우는 내내 기존 권한이 장부에 있다")
    void 장부를_비우지_않는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));
        List<Boolean> 쓸때마다_있었나 = new CopyOnWriteArrayList<>();
        writer.onApply(() -> 쓸때마다_있었나.add(writer.stored.contains(김_백엔드)));

        // when
        재적재한다();

        // then — 옛 store 모드는 장부를 지우고 다시 만들어, 그동안 모든 권한 질의가 false 였다
        assertThat(쓸때마다_있었나).isNotEmpty().doesNotContain(false);
        assertThat(writer.stored).contains(김_백엔드);
    }

    @Test
    @DisplayName("LDAP 읽기가 실패하면 장부에 아무것도 쓰거나 지우지 않고 FAILED 다 — 비운 뒤 읽기 실패로 전사 권한 0 이 되지 않는다")
    void 읽기가_실패하면_장부를_건드리지_않는다() {
        // given
        source.willFail(new IllegalStateException("LDAP 연결 실패"));
        writer.stored.addAll(Set.of(김_백엔드, 찌꺼기));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("LDAP 연결 실패");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(scanner.scanCount).hasValue(0);
        assertThat(writer.stored).containsExactlyInAnyOrder(김_백엔드, 찌꺼기);
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("기준선 스냅샷이 깨져 있어도 재적재는 스냅샷을 읽지 않고 끝난다 — 깨진 기준선을 고치는 수단이다")
    void 깨진_기준선에서도_끝난다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요"));

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(김_백엔드);
    }

    @Test
    @DisplayName("지우지 못한 줄은 새 스냅샷에 남는다 — 다음 동기화가 그 줄을 다시 지운다")
    void 지우지_못한_줄은_스냅샷에_남는다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.stored.add(찌꺼기);
        writer.failFor(찌꺼기::equals);

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).containsExactlyInAnyOrder(김_백엔드, 찌꺼기);
    }

    @Test
    @DisplayName("쓰지 못한 줄은 새 스냅샷에서 빠진다 — 다음 동기화가 그 줄을 다시 쓴다")
    void 쓰지_못한_줄은_스냅샷에서_빠진다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        writer.failFor(김_백엔드::equals);

        // when
        SyncRun run = 재적재한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("RUNNING 기록을 곧바로 주고, 끝나면 반납 수단과 onFinished 를 한 번씩 부른다")
    void 기록을_곧바로_주고_끝나면_반납한다() {
        // given
        source.willReturn(조직도("kim", "DEV002"));
        AtomicReference<SyncRun> 끝남 = new AtomicReference<>();

        // when
        SyncRun started = useCase.start(() -> Mono.fromRunnable(반납::incrementAndGet), 끝남::set).block();

        // then
        assertThat(started.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(started.trigger()).isEqualTo(SyncTrigger.REBUILD);
        SyncRun finished = runs.awaitFinished(started.runId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(끝남.get()).isEqualTo(finished));
        assertThat(반납).hasValue(1);
    }
}
```

`FullSyncUseCaseTest` 를 고친다:

1. import 에 `dev.starryeye.organization.core.model.SyncRun`, `reactor.core.publisher.Mono`, `java.time.Duration` 을 더한다.
2. `setUp()` 의 생성을 바꾼다:

```java
        useCase = new FullSyncUseCase(source, snapshots, state, writer,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                new SyncJobs(runs, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
```

3. `소속튜플` 아래에 도우미를 더한다:

```java
    /** 동기화를 걸고 끝날 때까지 기다린다 — 동기화는 요청과 떼어 돈다(설계 2026-09-29 §4). */
    private SyncRun 동기화한다(SyncTrigger trigger) {
        SyncRun started = useCase.start(trigger, Mono::empty, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }
```

4. 파일 안의 모든 `useCase.execute(<트리거>).block()` 을 `동기화한다(<트리거>)` 로 바꾼다(예: `var run = useCase.execute(SyncTrigger.SCHEDULED).block();` → `var run = 동기화한다(SyncTrigger.SCHEDULED);`,
   `useCase.execute(SyncTrigger.FORCED).block();` → `동기화한다(SyncTrigger.FORCED);`). `grep -n "execute(" FullSyncUseCaseTest.java` 결과가 비어야 한다.
5. `기준선이_깨지면_쓰지_않고_FAILED` 의 두 곳을 바꾼다:

```java
        snapshots.failFindLatest(new SnapshotIntegrityException(
                "기준선 스냅샷 20260806T030000-LDAP 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요"));
```

```java
        assertThat(run.message()).contains("POST /admin/sync/rebuild");
```

`DynamoDbTupleSnapshotRepositoryTest` 의 `메타가_없으면_오류다` 에서 `.hasMessageContaining("mode=store");` 를 `.hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");` 로 바꾼다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*RebuildUseCaseTest' --tests '*FullSyncUseCaseTest'`
Expected: 컴파일 실패 — `start` 가 없고 생성자가 다르다.

- [ ] **Step 3: 유스케이스와 문구를 고친다**

`RebuildUseCase` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * app-ldap 전체 재적재 (설계 2026-09-29 §3). LDAP 을 다시 읽어 장부(OpenFGA store)와 현재상태를 그것에 맞춘다.
 *
 * <p><b>장부를 버리지 않는다.</b> 먼저 LDAP 을 끝까지 읽고(실패하면 장부에 아무것도 하지 않는다), 있어야 할 줄을 전부 쓴 뒤, 장부를
 * 훑어 없어야 할 줄만 지운다({@link TupleReconciler}). 직전 스냅샷에 기대지 않으므로 기준선 스냅샷이 깨졌거나 스냅샷에 없는 찌꺼기가
 * 있어도 고친다 — 옛 {@code snapshot}(스냅샷에 없는 줄은 못 지움)·{@code store}(장부를 지우고 다시 만들어 인가 공백·번호 바뀜) 두 모드의
 * 장점만 가진다.
 *
 * <p>삭제 가드는 적용하지 않는다. 사람이 "지금 LDAP 이 진실"이라고 판단해 거는 작업이다.
 */
@Slf4j
@RequiredArgsConstructor
public class RebuildUseCase {

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleScanner scanner;
    private final SyncJobs jobs;
    private final Clock clock;

    /**
     * 실행 기록(RUNNING)을 열고 재적재를 요청과 떼어 띄운다. 겹침 검사(실행 가드)는 호출자가 하고 그 반납 수단을 넘긴다 —
     * 재적재가 어떻게 끝나든 한 번 불린다. {@code onFinished} 는 끝난 기록으로 불린다(지표).
     */
    public Mono<SyncRun> start(Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return jobs.start(SyncSource.LDAP, SyncTrigger.REBUILD, Mono.defer(this::rebuild), release, onFinished);
    }

    private Mono<SyncOutcome> rebuild() {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples())
                    .flatMap(reconciliation -> commit(directory, reconciliation));
        });
    }

    /** 스냅샷에는 장부에 실제로 있다고 볼 줄을 담는다(설계 §3.1 4단계). 그다음 현재상태를 LDAP 대로 바꾼다. */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory, TupleReconciler.Reconciliation reconciliation) {
        Instant now = clock.instant();
        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(now, SyncSource.LDAP), now, SyncSource.LDAP, reconciliation.ledger());
        TupleWriteResult result = reconciliation.result();

        return snapshots.save(snapshot)
                .then(Mono.defer(() -> state.replaceWith(directory)))
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }
}
```

`RebuildMode.java` 를 지운다: `git rm core/src/main/java/dev/starryeye/organization/core/usecase/RebuildMode.java`

`FullSyncUseCase` 를 고친다:
- import 에서 `SyncOutcome` 은 그대로 두고, `dev.starryeye.organization.core.port.SyncRunRepository` 를 빼고, `java.util.function.Consumer`·`java.util.function.Supplier` 를 더한다.
- 필드 `private final SyncRunRepository runs;` 를 지우고, `guard` 와 `clock` 사이에 `private final SyncJobs jobs;` 를 둔다(필드 순서: source, snapshots, state, writer, guard, jobs, clock).
- `execute` 메서드를 통째로 이것으로 바꾼다:

```java
    /**
     * 실행 기록(RUNNING)을 열고 동기화를 요청과 떼어 띄운다(설계 2026-09-29 §4). 겹침 검사(실행 가드)는 호출자가 하고 그 반납 수단을
     * 넘긴다 — 동기화가 어떻게 끝나든 한 번 불린다. {@code onFinished} 는 끝난 기록으로 불린다(지표·로그).
     */
    public Mono<SyncRun> start(SyncTrigger trigger, Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
        return jobs.start(SyncSource.LDAP, trigger, Mono.defer(() -> synchronize(trigger)), release, onFinished);
    }
```

`DynamoDbTupleSnapshotRepository` 의 두 문구에서 `POST /admin/sync/rebuild?mode=store 로 복구하세요` 를 `POST /admin/sync/rebuild 로 복구하세요` 로 바꾼다
(`findLatest` 의 "메타가 없습니다" 와 `toSnapshot` 의 "온전히 읽지 못했습니다").

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test` → PASS.
Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbTupleSnapshotRepositoryTest'` → PASS.
Run: `git grep -n "RebuildMode\b\|mode=store\|mode=snapshot" -- core storage-dynamodb` → 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add -A core/src/main/java/dev/starryeye/organization/core/usecase/ \
  core/src/test/java/dev/starryeye/organization/core/usecase/RebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java
git commit -m "feat: app-ldap 재적재를 모드 하나로 — 장부 안에서 청소, 전체 동기화와 함께 요청과 떼어 돈다"
git push
```

---

### Task 5: OpenFGA 장부 훑기, 재생성 제거, 3배치 연속 실패 차단기

**Files:**
- Create: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScanner.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaConfig.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/RelationTupleWriter.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java` (`resetStore` 관련 제거)
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScannerTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterTest.java` (재생성 테스트 제거)

**Interfaces:**
- Consumes: `RelationTupleScanner`(Task 3).
- Produces: `OpenFgaRelationTupleScanner(StoreBootstrapper)` + 스프링 빈 `RelationTupleScanner relationTupleScanner(StoreBootstrapper)`.
- Produces: `RelationTupleWriter` 에 `resetStore()` 가 없다. `OpenFgaRelationTupleWriter.연속_실패_한도 = 3`,
  `static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches, Function<Batch, Mono<TupleWriteResult>> send)`(패키지 전용).
- `StoreBootstrapper#recreateStore` 는 이 과제 뒤 아무도 부르지 않는다 — Task 6 이 지운다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`OpenFgaRelationTupleScannerTest.java`:

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재적재의 장부 훑기 — 이 서버에서 OpenFGA Read 를 부르는 유일한 자리다 (설계 2026-09-29 §3.2).
 */
class OpenFgaRelationTupleScannerTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleScanner scanner;

    @BeforeEach
    void 어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        scanner = new OpenFgaRelationTupleScanner(bootstrapper);
    }

    @Test
    @DisplayName("장부의 모든 줄을 페이지를 넘겨 빠짐없이 읽는다")
    void 페이지를_넘겨_모두_읽는다() {
        // given — 한 페이지(100줄)를 두 번 넘길 만큼 쓴다
        Set<RelationTuple> 쓴것 = IntStream.range(0, 250)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.apply(TupleDelta.writeOnly(쓴것)).block();

        // when
        List<RelationTuple> 읽은것 = scanner.scanAll().collectList().block();

        // then — 개수까지 본다. 같은 줄을 두 번 읽어도 집합 비교만으로는 모른다
        assertThat(읽은것).hasSize(250);
        assertThat(Set.copyOf(읽은것)).isEqualTo(쓴것);
    }

    @Test
    @DisplayName("이 서버를 거치지 않고 직접 써 넣은 줄도 나온다 — 스냅샷에 없는 찌꺼기를 찾는 이유다")
    void 직접_써넣은_줄도_나온다() throws Exception {
        // given
        bootstrapper.client().writeTuples(List.of(new ClientTupleKey()
                .user("user:ghost").relation("direct_member")._object("group:DEV001"))).get();
        writer.apply(TupleDelta.writeOnly(Set.of(RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        List<RelationTuple> 읽은것 = scanner.scanAll().collectList().block();

        // then
        assertThat(읽은것).containsExactlyInAnyOrder(
                RelationTuple.directMember("ghost", "DEV001"),
                RelationTuple.child("DEV002", "DEV001"));
    }

    @Test
    @DisplayName("방금 지운 줄은 나오지 않는다 — 캐시가 아니라 지금 있는 것을 읽는다")
    void 지운_줄은_나오지_않는다() {
        // given
        RelationTuple 남는것 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 지운것 = RelationTuple.directMember("lee", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(남는것, 지운것))).block();
        writer.apply(TupleDelta.deleteOnly(Set.of(지운것))).block();

        // when, then
        assertThat(scanner.scanAll().collectList().block()).containsExactly(남는것);
    }

    @Test
    @DisplayName("빈 장부는 아무 줄도 없다")
    void 빈_장부는_비어있다() {
        // when, then
        assertThat(scanner.scanAll().collectList().block()).isEmpty();
    }
}
```

`OpenFgaRelationTupleWriterBreakerTest.java`(컨테이너 없음):

```java
package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFGA 가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 수십 분 쥐지 않는다 (점검 C7, 설계 2026-09-29 §5).
 */
class OpenFgaRelationTupleWriterBreakerTest {

    private static List<Batch> 배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("user" + i, "DEV002"))))
                .toList();
    }

    @Test
    @DisplayName("배치가 3번 연달아 실패하면 남은 배치를 보내지 않고 오류로 끝난다")
    void 세번_연달아_실패하면_멈춘다() {
        // given
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 늘_실패 = batch -> {
            보낸것.add(batch);
            return Mono.just(batch.failed("연결 거부"));
        };

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(10), 늘_실패).block())
                .hasMessageContaining("3번 연달아")
                .hasMessageContaining("남은 7개")
                .hasMessageContaining("연결 거부");
        assertThat(보낸것).hasSize(3);
    }

    @Test
    @DisplayName("실패가 연달아 셋이 아니면 끝까지 보내고 실패는 결과에 담는다 — 드문 실패는 지금처럼 PARTIAL 로 넘긴다")
    void 연달아_셋이_아니면_끝까지_보낸다() {
        // given — 실패, 실패, 성공이 되풀이된다. 성공이 끼면 연속 횟수는 처음부터 다시 센다
        AtomicInteger 차례 = new AtomicInteger();
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 셋째마다_성공 = batch -> {
            보낸것.add(batch);
            return Mono.just(차례.incrementAndGet() % 3 == 0 ? batch.succeeded() : batch.failed("일시 오류"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(9), 셋째마다_성공).block();

        // then
        assertThat(보낸것).hasSize(9);
        assertThat(결과.failures()).hasSize(6);
        assertThat(결과.written()).hasSize(3);
    }

    @Test
    @DisplayName("배치가 셋보다 적으면 모두 실패해도 오류가 아니라 결과의 실패로 남는다 — SCIM 요청 한 건")
    void 배치가_적으면_차단기와_무관하다() {
        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(
                배치들(2), batch -> Mono.just(batch.failed("x"))).block();

        // then
        assertThat(결과.failures()).hasSize(2);
    }
}
```

`OpenFgaRelationTupleWriterTest` 에서 `store_재생성은_전부_비운다` 테스트(맨 끝 메서드)를 지운다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*ScannerTest' --tests '*BreakerTest'`
Expected: 컴파일 실패 — `OpenFgaRelationTupleScanner`·`보내되_연속_실패면_멈춘다` 가 없다.

- [ ] **Step 3: 구현한다**

`OpenFgaRelationTupleScanner.java`:

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.client.model.ClientReadResponse;
import dev.openfga.sdk.api.configuration.ClientReadOptions;
import dev.openfga.sdk.api.model.ConsistencyPreference;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.TupleKey;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 장부(OpenFGA store)의 모든 줄을 Read API 로 훑는다 (설계 2026-09-29 §3.2). 재적재만 쓴다.
 *
 * <p><b>이 서버에서 Read 를 부르는 유일한 자리다.</b> 판단·쓰기 경로는 Check·BatchCheck 만 쓴다({@link OpenFgaRelationTupleChecker}).
 * 빈 요청(키 없음)이 장부 전체를 뜻한다 — SDK 0.9.11 은 세 칸이 모두 비면 {@code tuple_key} 를 보내지 않는다.
 *
 * <p>페이지마다 {@value #PAGE_SIZE}줄을 받아 이어받기 토큰이 빌 때까지 넘긴다. 10만 명(약 11만 줄)이면 약 1,100번 부른다. 캐시가 아니라
 * 지금 있는 것을 읽는다({@code HIGHER_CONSISTENCY}) — 방금 쓴 줄이 안 보이면 그 줄을 "없어야 할 줄"로 잘못 고르지는 않지만, 방금 지운
 * 줄이 보이면 헛 지우기를 한다.
 */
@RequiredArgsConstructor
public class OpenFgaRelationTupleScanner implements RelationTupleScanner {

    /** OpenFGA Read 한 페이지의 상한. */
    static final int PAGE_SIZE = 100;

    private final StoreBootstrapper bootstrapper;

    @Override
    public Flux<RelationTuple> scanAll() {
        return bootstrapper.resolveStore()
                .flatMapMany(storeId -> 읽는다(storeId, null)
                        .expand(page -> 다음이_있다(page)
                                ? 읽는다(storeId, page.getContinuationToken())
                                : Mono.empty()))
                .flatMapIterable(page -> page.getTuples() == null ? List.<Tuple>of() : page.getTuples())
                .map(tuple -> 줄로(tuple.getKey()));
    }

    private Mono<ClientReadResponse> 읽는다(String storeId, String continuationToken) {
        return Mono.fromFuture(() -> {
            try {
                ClientReadOptions options = new ClientReadOptions()
                        .pageSize(PAGE_SIZE)
                        .consistency(ConsistencyPreference.HIGHER_CONSISTENCY);
                if (continuationToken != null) {
                    options.continuationToken(continuationToken);
                }
                return bootstrapper.clientFor(storeId).read(new ClientReadRequest(), options);
            } catch (Exception e) {
                throw new IllegalStateException("OpenFGA read 호출 실패", e);
            }
        });
    }

    private static boolean 다음이_있다(ClientReadResponse page) {
        String token = page.getContinuationToken();
        return token != null && !token.isBlank();
    }

    private static RelationTuple 줄로(TupleKey key) {
        return new RelationTuple(key.getUser(), key.getRelation(), key.getObject());
    }
}
```

`OpenFgaConfig` 에 빈을 더한다(import `dev.starryeye.organization.core.port.RelationTupleScanner`):

```java
    /** 재적재의 장부 훑기. Read API 를 쓰는 유일한 빈이다 — 판단·쓰기 경로에 주입하지 않는다. */
    @Bean
    public RelationTupleScanner relationTupleScanner(StoreBootstrapper bootstrapper) {
        return new OpenFgaRelationTupleScanner(bootstrapper);
    }
```

`OpenFgaRelationTupleWriter`:
- import 에 `java.util.concurrent.atomic.AtomicInteger`, `java.util.function.Function` 을 더한다.
- `resetStore()` 메서드를 지운다.
- `apply` 를 이것으로 바꾸고, 바로 아래에 상수와 메서드를 더한다:

```java
    /**
     * 이만큼 연달아 실패하면(배치마다 재시도한 뒤에도) 남은 배치를 보내지 않는다(점검 C7). 설정으로 두지 않는다 — 운영에서 바꿀
     * 이유가 보이면 그때 올린다.
     */
    static final int 연속_실패_한도 = 3;

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        if (delta.isEmpty()) {
            return Mono.just(TupleWriteResult.empty());
        }

        List<Batch> batches = batchesFor(delta);

        return bootstrapper.resolveStore()
                .then(보내되_연속_실패면_멈춘다(batches, this::applyBatch));
    }

    /**
     * 배치를 차례로 보낸다. {@value #연속_실패_한도}개가 연달아 실패하면 남은 배치를 보내지 않고 오류로 끝낸다(점검 C7, 설계 §5).
     *
     * <p>OpenFGA 가 느리거나 죽으면 배치마다 재시도를 거친 뒤 실패로 넘어가는데, 10만 명 재적재는 배치가 약 1,100개라 전부 그렇게 돌면
     * 수십 분 동안 락을 쥔다. 연달아 실패하는 것은 대개 한 배치가 아니라 OpenFGA 의 문제다. 드문 실패 한두 건은 지금처럼 결과의
     * {@code failures} 로 넘겨 PARTIAL 이 되게 둔다. 배치가 셋보다 적은 쓰기(SCIM 요청 한 건)는 해당이 없다.
     *
     * <p>패키지 전용 — 멈추는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     */
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send) {
        return Mono.defer(() -> {
            AtomicInteger 연속_실패 = new AtomicInteger();
            return Flux.fromIterable(batches)
                    .index()
                    .concatMap(indexed -> send.apply(indexed.getT2()).flatMap(result -> {
                        if (!result.hasFailure()) {
                            연속_실패.set(0);
                            return Mono.just(result);
                        }
                        if (연속_실패.incrementAndGet() < 연속_실패_한도) {
                            return Mono.just(result);
                        }
                        long 남은_배치 = batches.size() - indexed.getT1() - 1;
                        return Mono.<TupleWriteResult>error(new IllegalStateException(
                                "OpenFGA 쓰기 배치가 %d번 연달아 실패해 남은 %d개 배치를 보내지 않고 멈췄다 — 마지막 오류: %s"
                                        .formatted(연속_실패_한도, 남은_배치, result.failures().get(0).reason())));
                    }))
                    .reduce(TupleWriteResult.empty(), OpenFgaRelationTupleWriter::merge);
        });
    }
```

`RelationTupleWriter` 를 이것으로 바꾼다:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import reactor.core.publisher.Mono;

/**
 * 계산된 델타를 인가 시스템에 반영한다. 장부를 읽지 않는다 — 판정은 {@link RelationTupleChecker}, 재적재의 장부 훑기는
 * {@link RelationTupleScanner} 다.
 *
 * <p>장부(store)를 지우거나 다시 만드는 메서드는 없다 — 장부 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).
 */
public interface RelationTupleWriter {

    Mono<TupleWriteResult> apply(TupleDelta delta);
}
```

`FakeTupleWriter` 에서 `resetStoreCount` 필드, `resetStoreError` 필드, `failResetStore(...)`, `resetStore()` 를 지우고 쓰지 않게 된 import(`AtomicInteger`)를 정리한다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test` → PASS(가짜 정리 뒤에도).
Run: `./gradlew :authz-openfga:test` → PASS.
Run: `git grep -n "resetStore" -- core authz-openfga storage-dynamodb` → 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScanner.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaConfig.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleScannerTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterTest.java \
  core/src/main/java/dev/starryeye/organization/core/port/RelationTupleWriter.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java
git commit -m "feat: OpenFGA 장부 훑기(Read, 재적재 전용)·3배치 연속 실패 차단기, store 재생성 제거"
git push
```

---

### Task 6: 장부 준비 — 동시 첫 생성 수렴, 모델 등록 뒤에만 번호 기억, Check 도 준비 과정을 탄다

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/StoreBootstrapper.java` (전체 교체)
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java`
- Modify: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaTestSupport.java` (도우미 이동)
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/StoreBootstrapperTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleCheckerTest.java`

**Interfaces:**
- Produces: `StoreBootstrapper#convergeAfterCreate(String createdId): Mono<String>`(패키지 전용), `WriteAuthorizationModelRequest authorizationModel()`(패키지 전용, 재정의 가능).
  `recreateStore()` 가 없어진다. `resolveStore`·`findExistingStore`·`client`·`clientFor` 는 그대로.
- Produces (테스트 지원): `OpenFgaTestSupport` 의 `protected OpenFgaProperties 새_속성(String 접두사)`, `protected String createStoreDirectly(OpenFgaClient client, String name)`, `protected long countStoresNamed(String name)`.

- [ ] **Step 1: 테스트 지원 도우미를 옮긴다**

`StoreBootstrapperTest` 끝의 `createStoreDirectly`·`countStoresNamed` 를 지우고, `OpenFgaTestSupport` 에 이것을 더한다(import `dev.openfga.sdk.api.client.OpenFgaClient`,
`dev.openfga.sdk.api.configuration.ClientConfiguration`, `dev.openfga.sdk.api.configuration.ClientListStoresOptions`, `dev.openfga.sdk.api.model.CreateStoreRequest`):

```java
    /** 이 테스트만의 새 store 이름을 가진 설정. 부트스트래퍼끼리의 경주나 store 가 없는 상태를 볼 때 쓴다. */
    protected OpenFgaProperties 새_속성(String 접두사) {
        OpenFgaProperties fresh = new OpenFgaProperties();
        fresh.setApiUrl(properties.getApiUrl());
        fresh.setStoreName(접두사 + UUID.randomUUID());
        fresh.setWriteBatchSize(properties.getWriteBatchSize());
        fresh.setMaxRetries(properties.getMaxRetries());
        return fresh;
    }

    /** 부트스트래퍼를 거치지 않고 store 를 만든다 — 다른 인스턴스가 만든 store 를 흉내 낸다. */
    protected String createStoreDirectly(OpenFgaClient client, String name) {
        try {
            return client.createStore(new CreateStoreRequest().name(name)).get().getId();
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 store 생성 실패", e);
        }
    }

    /**
     * 이름이 같은 store 수. 검증용 도우미도 페이지를 끝까지 넘긴다 — 여러 테스트가 한 페이지보다 많은 store 를 만들어 두므로,
     * 첫 페이지만 보면 뒤쪽 store 를 세지 못해 정상 동작을 실패로 본다.
     */
    protected long countStoresNamed(String name) {
        try {
            OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
            long count = 0;
            String continuationToken = null;
            do {
                ClientListStoresOptions options = new ClientListStoresOptions();
                if (continuationToken != null && !continuationToken.isBlank()) {
                    options.continuationToken(continuationToken);
                }
                var response = client.listStores(options).get();
                count += response.getStores().stream().filter(store -> name.equals(store.getName())).count();
                continuationToken = response.getContinuationToken();
            } while (continuationToken != null && !continuationToken.isBlank());
            return count;
        } catch (Exception e) {
            throw new IllegalStateException("store 목록 조회 실패", e);
        }
    }
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`StoreBootstrapperTest`:
- `재구성_도중_겹치는_resolveStore_는_합류한다` 와 `cold_상태에서도_recreateStore_는_끝난다`(그리고 각 위의 자바독 주석)를 지운다.
- 클래스 끝에 더한다(import `dev.openfga.sdk.api.model.WriteAuthorizationModelRequest`; 쓰지 않게 된 `AtomicBoolean`·`Timeout` import 는 새 테스트가 쓰는지 보고 정리):

```java
    // ---------- 동시 첫 생성 수렴(점검 M17) ----------

    @Test
    @DisplayName("store 를 만든 뒤 같은 이름이 먼저 있으면, 먼저 만든 것을 쓰고 방금 만든 자기 store 를 지운다")
    void 먼저_만든_store_가_이긴다() throws Exception {
        // given — 다른 인스턴스가 먼저 만들고, 내가 곧이어 만든 순간
        OpenFgaProperties 속성 = 새_속성("converge-");
        OpenFgaClient raw = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        String 먼저 = createStoreDirectly(raw, 속성.getStoreName());
        Thread.sleep(5);
        String 나중 = createStoreDirectly(raw, 속성.getStoreName());

        // when
        String 고른것 = new StoreBootstrapper(속성).convergeAfterCreate(나중).block(Duration.ofSeconds(10));

        // then
        assertThat(고른것).isEqualTo(먼저);
        assertThat(countStoresNamed(속성.getStoreName()))
                .as("늦게 만든 쪽이 자기 store 를 지워야 하나로 모인다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("내가 가장 먼저 만들었으면 그대로 쓰고, 남이 만든 store 는 지우지 않는다")
    void 내가_먼저면_그대로_쓴다() throws Exception {
        // given
        OpenFgaProperties 속성 = 새_속성("converge-first-");
        OpenFgaClient raw = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        String 먼저 = createStoreDirectly(raw, 속성.getStoreName());
        Thread.sleep(5);
        createStoreDirectly(raw, 속성.getStoreName());

        // when
        String 고른것 = new StoreBootstrapper(속성).convergeAfterCreate(먼저).block(Duration.ofSeconds(10));

        // then — 남의 것은 그쪽이 지운다. 여기서 지우면 그쪽이 쓰려던 store 가 사라진다
        assertThat(고른것).isEqualTo(먼저);
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 OpenFGA 에 부트스트래퍼 둘이 동시에 떠도 store 하나로 모인다")
    void 두_인스턴스가_동시에_떠도_하나로_모인다() {
        // given — 같은 이름을 노리는 서로 다른 두 인스턴스
        OpenFgaProperties 속성 = 새_속성("twin-");
        StoreBootstrapper 가 = new StoreBootstrapper(속성);
        StoreBootstrapper 나 = new StoreBootstrapper(속성);

        // when
        List<String> 번호들 = Flux.merge(
                        가.resolveStore().subscribeOn(Schedulers.parallel()),
                        나.resolveStore().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(Duration.ofSeconds(30));

        // then
        assertThat(new HashSet<>(번호들)).as("두 인스턴스가 같은 장부를 써야 한다").hasSize(1);
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);
    }

    // ---------- 모델 등록 뒤에만 번호를 기억한다(점검 M6) ----------

    @Test
    @DisplayName("인가 모델 등록이 실패하면 번호를 기억하지 않고, 다음 시도가 같은 store 에 모델을 등록한다")
    void 모델_등록이_실패하면_번호를_기억하지_않는다() {
        // given — 첫 모델 등록만 실패하는 부트스트래퍼. 빈 타입 정의는 OpenFGA 가 거절한다
        OpenFgaProperties 속성 = 새_속성("model-fail-");
        AtomicBoolean 실패시킨다 = new AtomicBoolean(true);
        StoreBootstrapper 부트스트래퍼 = new StoreBootstrapper(속성) {
            @Override
            WriteAuthorizationModelRequest authorizationModel() {
                return 실패시킨다.getAndSet(false)
                        ? new WriteAuthorizationModelRequest().schemaVersion("1.1").typeDefinitions(List.of())
                        : super.authorizationModel();
            }
        };

        // when — 첫 해석은 모델 등록에서 실패한다
        assertThatThrownBy(() -> 부트스트래퍼.resolveStore().block()).isInstanceOf(RuntimeException.class);

        // then — 번호를 기억하지 않았다. 기억했다면 이 프로세스는 재시작 전까지 모델 없는 store 를 쓴다
        assertThatThrownBy(부트스트래퍼::client).hasMessageContaining("해석되지 않았다");
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);

        // when — 다시 해석하면
        String 번호 = 부트스트래퍼.resolveStore().block(Duration.ofSeconds(10));

        // then — 새로 만들지 않고 아까 만든 store 에 모델을 등록했다
        assertThat(번호).isNotBlank();
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);
        assertThat(부트스트래퍼.client()).isNotNull();
    }
```

`OpenFgaRelationTupleCheckerTest`:
- `store가_없으면_에러로_끝난다` 를 지우고 이것으로 바꾼다(쓰지 않게 된 `catchThrowable`·`UUID` import 정리):

```java
    @Test
    @DisplayName("store 가 아직 없으면 Check 가 준비 과정을 타 store 를 만들고 판정한다 — 시작 때 OpenFGA 가 안 닿았던 인스턴스도 재시작 없이 낫는다")
    void store가_없으면_Check가_만든다() {
        // given — 이 컨테이너에 존재한 적 없는 store 이름. 시작 때 준비하지 못한 인스턴스다
        OpenFgaProperties 속성 = 새_속성("missing-");
        OpenFgaRelationTupleChecker 체커 = new OpenFgaRelationTupleChecker(new StoreBootstrapper(속성));

        // when
        Boolean allowed = 체커.check(new RelationTuple("user:kim", "member", "group:DEV002")).block();

        // then — 오류가 아니라 판정이다. 빈 장부라 false 다
        assertThat(allowed).isFalse();
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);
    }
```

- `resolve_안한_새_부트스트래퍼로도_Check가_된다` 의 주석 세 줄(`// 이 프로세스 안에서 resolveStore()/recreateStore() 를 …`)을 이것으로 바꾼다:

```java
        // 이 프로세스 안에서 resolveStore() 를 한 번도 부른 적 없는 새 StoreBootstrapper —
        // Check 가 스스로 준비 과정을 타 이미 있는 store 를 찾아 쓴다
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*StoreBootstrapperTest' --tests '*OpenFgaRelationTupleCheckerTest'`
Expected: 컴파일 실패 — `convergeAfterCreate`·`authorizationModel` 이 없다.

- [ ] **Step 4: 구현한다**

`StoreBootstrapper` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * store 이름으로 storeId 를 해석하고 인가 모델을 등록한다.
 *
 * <p>앱의 어느 곳도 storeId 나 modelId 를 알지 못한다. 설정에는 store-name 만 있고,
 * write 호출에는 authorization_model_id 를 넘기지 않아 서버가 최신 모델을 쓴다.
 *
 * <p><b>store 번호는 한 번 만들면 바뀌지 않는다(설계 2026-09-29 §2).</b> 재적재도 store 를 지우고 다시 만들지 않는다 — 장부 안에서
 * 청소한다. 이 클래스가 store 를 지우는 경우는 하나뿐이다: 동시에 처음 뜬 인스턴스끼리 같은 이름 store 를 둘 만들었을 때 늦게 만든
 * 쪽이 자기 것을 지운다({@link #convergeAfterCreate}).
 */
@Slf4j
public class StoreBootstrapper {

    private static final String MODEL_RESOURCE = "authorization-model.json";

    /** 같은 이름 store 가 여럿일 때 이기는 쪽: 가장 먼저 만들어진 것, 같으면 id 사전순. */
    private static final Comparator<Store> 먼저_만든_순 = Comparator
            .comparing(Store::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Store::getId);

    private final OpenFgaProperties properties;
    private final AtomicReference<OpenFgaClient> clientRef = new AtomicReference<>();

    /**
     * storeId 가 없는 클라이언트. store 를 찾거나 만들 때만 쓴다.
     *
     * <p>전에는 부를 때마다 새로 만들었다. store 목록 조회는 재귀 페이징이라 <b>페이지마다</b> 하나씩 생겼고,
     * 첫 부트스트랩 한 번에 여러 개가 만들어졌다. 이 클라이언트는 어떤 store 에도 묶여 있지 않아 상태가 없으므로
     * 재사용해도 안전하다.
     */
    private final AtomicReference<OpenFgaClient> storelessClientRef = new AtomicReference<>();
    private final AtomicReference<String> storeIdRef = new AtomicReference<>();

    /** {@link #clientFor(String)} 이 돌려주는 client 를 storeId 별로 재사용한다. {@code clientRef} 와는 별개의 캐시다. */
    private final ConcurrentMap<String, OpenFgaClient> readOnlyClients = new ConcurrentHashMap<>();

    /**
     * 진행 중인 해석을 공유하기 위한 in-flight Mono. resolveStore() 를 동시에 여러 곳에서
     * 호출해도 실제 찾기 → 만들기 → 수렴 → 모델 등록 파이프라인은 한 번만 구성/구독되고, 모든 호출자가 같은 결과를 공유한다.
     *
     * <p>성공하면 storeIdRef 가 채워져 이후 호출은 이 필드를 아예 거치지 않는다(빠른 경로).
     * 실패하면 doFinally 에서 이 필드를 비워, 다음 호출이 캐시된 에러를 영원히 받는 대신
     * 새로 시도할 수 있게 한다.
     */
    private final AtomicReference<Mono<String>> resolutionRef = new AtomicReference<>();

    public StoreBootstrapper(OpenFgaProperties properties) {
        this.properties = properties;
    }

    /**
     * 이미 해석했으면 캐시된 storeId 를 준다. 없으면 찾고, 그래도 없으면 만든다. 쓰기·Check·장부 훑기가 모두 이것을 탄다 —
     * 시작 때 OpenFGA 가 안 닿았어도 첫 요청 때 닿으면 그때 준비된다(점검 M6).
     */
    public Mono<String> resolveStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return Mono.defer(this::sharedResolution);
    }

    /**
     * 헬스체크 전용 read-only 조회. 캐시된 storeId 가 있으면 그것을 쓰고, 없으면 이름으로 store 존재 여부만 확인한다 —
     * {@link #resolveStore()} 와 달리 store 를 만들거나 인가 모델을 쓰지 않는다.
     *
     * <p>헬스 프로브는 관찰만 해야지 인프라를 만들면 안 된다. 인증 없는
     * {@code GET /actuator/health} 는 k8s 프로브·로드밸런서·오타난 {@code openfga.store-name}
     * 설정 등 무엇이든 호출할 수 있는데, 이 경로가 {@link #resolveStore()} 를 타면 오타난 이름으로 빈 store 를 새로 만든 채
     * 조용히 UP 을 보고하게 된다. store 가 없으면 {@link Mono#empty()} 를 그대로 돌려주고, DOWN 으로의 번역은 호출자(헬스 인디케이터)
     * 몫이다.
     */
    public Mono<String> findExistingStore() {
        String cached = storeIdRef.get();
        if (cached != null) {
            return Mono.just(cached);
        }
        return findStoreIdByName();
    }

    /**
     * 진행 중인 해석이 있으면 그것을 공유하고, 없으면 하나만 새로 만들어 등록한다.
     * compareAndSet 으로 등록 경쟁의 승자만 실제 파이프라인을 구독하게 하고,
     * 패자는 승자가 등록한 Mono 를 그대로 반환해 같은 storeId 를 받는다.
     *
     * <p>이것은 <b>한 프로세스 안</b>의 동시 호출을 하나로 묶는다. 프로세스(인스턴스)끼리의 경주는 {@link #convergeAfterCreate} 가 푼다.
     */
    private Mono<String> sharedResolution() {
        Mono<String> existing = resolutionRef.get();
        if (existing != null) {
            return existing;
        }

        Mono<String> created = findStoreIdByName()
                .switchIfEmpty(Mono.defer(() -> createStore().flatMap(this::convergeAfterCreate)))
                .flatMap(this::attachAndWriteModel)
                .doFinally(signal -> resolutionRef.set(null))
                .cache();

        if (resolutionRef.compareAndSet(null, created)) {
            return created;
        }
        return resolutionRef.get();
    }

    /**
     * 방금 store 를 만든 뒤 같은 이름 목록을 다시 본다(점검 M17). 빈 OpenFGA 에 인스턴스 둘이 동시에 뜨면 둘 다 "없다"를 보고 각자
     * 만든다 — OpenFGA 는 이름 유일성을 강제하지 않는다. 그래서 만든 쪽마다 목록을 다시 보고, 모두가 <b>가장 먼저 만들어진 것</b>
     * (createdAt, 같으면 id 사전순)을 쓴다. 그것이 내 것이 아니면 방금 만든 내 store 를 지운다. 남이 만든 store 는 지우지 않는다 —
     * 그쪽도 같은 규칙으로 스스로 물러난다. 동시에 떠도 결국 하나로 모인다.
     *
     * <p>목록이 생성 직후 바로 보인다는 전제다 — 같은 OpenFGA 서버라 성립한다고 본다(설계 §11).
     *
     * <p>패키지 전용 — 경주의 한 순간(남이 먼저 만든 뒤 내가 만든 상태)을 테스트가 직접 만들어 본다.
     */
    Mono<String> convergeAfterCreate(String createdId) {
        return listStoresNamed().flatMap(stores -> {
            Store winner = stores.stream().min(먼저_만든_순).orElse(null);
            if (winner == null || winner.getId().equals(createdId)) {
                return Mono.just(createdId);
            }
            log.warn("같은 이름 store '{}' 를 다른 인스턴스가 먼저 만들었다. 내가 만든 {} 를 지우고 {} 를 쓴다",
                    properties.getStoreName(), createdId, winner.getId());
            return deleteStoreById(createdId).thenReturn(winner.getId());
        });
    }

    /** storeId 로 직접 client 를 만들어 지운다. clientRef 캐시 상태에 기대지 않는다. */
    private Mono<Void> deleteStoreById(String storeId) {
        return Mono.fromCallable(() -> newClient(storeId))
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.deleteStore();
                    } catch (Exception e) {
                        throw new IllegalStateException("store 삭제 실패", e);
                    }
                }))
                .then();
    }

    public OpenFgaClient client() {
        OpenFgaClient client = clientRef.get();
        if (client == null) {
            throw new IllegalStateException("store 가 아직 해석되지 않았다. resolveStore() 를 먼저 호출하라");
        }
        return client;
    }

    /**
     * storeId 에 묶인 client 를 준다. {@code clientRef}/{@code storeIdRef} 캐시를 읽지도 쓰지도 않는다.
     *
     * <p>{@link #findExistingStore()} 는 store 존재만 확인하고 storeId 를 돌려줄 뿐 {@code clientRef} 를 채우지 않는다 — 그래서 보기만
     * 하는 쪽은 {@link #client()} 대신 이것을 쓴다. Check·장부 훑기도 {@link #resolveStore()} 가 준 storeId 로 이것을 쓴다.
     *
     * <p><b>storeId 별로 client 를 재사용한다.</b> 전에는 호출마다 새로 만들었는데, Check 는 <b>응답 한 줄당 한 번</b> 돈다 — 경로
     * 200개짜리 직원 상세 하나가 인증 없는 GET 한 번에 커넥션 풀과 셀렉터 스레드를 200벌 만든다. store 번호는 바뀌지 않으므로
     * 엔트리는 사실상 하나다.
     */
    public OpenFgaClient clientFor(String storeId) {
        return readOnlyClients.computeIfAbsent(storeId, this::newClient);
    }

    private Mono<String> findStoreIdByName() {
        return listStoresNamed().flatMap(this::resolveUniqueMatch);
    }

    /** 이 이름의 store 전부. */
    private Mono<List<Store>> listStoresNamed() {
        return listAllStores(null, new ArrayList<>())
                .map(all -> all.stream()
                        .filter(store -> properties.getStoreName().equals(store.getName()))
                        .toList());
    }

    /**
     * store 목록을 continuation token 이 소진될 때까지 전부 순회한다.
     *
     * <p>{@code listStores()} 는 한 페이지(OpenFGA 기본 50개)만 반환한다. 공유 OpenFGA
     * 서버에 store 가 그보다 많으면, 첫 페이지에 없다고 곧장 {@code createStore} 로
     * 넘어가는 것은 위험하다 — 실제로는 다음 페이지에 이름이 이미 존재하는 store 가 있는데
     * 못 찾은 것뿐이고, OpenFGA 는 이름 유일성을 강제하지 않으므로 조용히 두 번째
     * store 가 만들어진다. 이후 이 앱은 새로 만든 빈 store 에 튜플을 쓰고, 기존 소비자는
     * 여전히 첫 번째 store 를 조회하는 완전한 인가 실패로 이어진다.
     */
    private Mono<List<Store>> listAllStores(String continuationToken, List<Store> accumulated) {
        return Mono.fromCallable(this::storelessClient)
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        ClientListStoresOptions options = new ClientListStoresOptions();
                        if (continuationToken != null && !continuationToken.isBlank()) {
                            options.continuationToken(continuationToken);
                        }
                        return client.listStores(options);
                    } catch (Exception e) {
                        throw new IllegalStateException("store 목록 조회 실패", e);
                    }
                }))
                .flatMap(response -> {
                    accumulated.addAll(response.getStores());
                    String next = response.getContinuationToken();
                    if (next != null && !next.isBlank()) {
                        return listAllStores(next, accumulated);
                    }
                    return Mono.just(accumulated);
                });
    }

    /**
     * 찾기 단계에서 같은 이름의 store 가 둘 이상이면 임의로 하나를 골라 쓰는 대신 에러로 멈춘다 — 오래전부터 둘이었다는 뜻이고,
     * 어느 쪽에 진짜 데이터가 있는지 모른다. 사람이 개입해 정리해야 한다. (방금 만든 뒤의 중복은 {@link #convergeAfterCreate} 가 푼다.)
     */
    private Mono<String> resolveUniqueMatch(List<Store> matches) {
        if (matches.size() > 1) {
            return Mono.error(new IllegalStateException(
                    "OpenFGA 에 이름이 '%s' 인 store 가 %d개 있다. 이름 유일성이 깨진 상태이므로 "
                            .formatted(properties.getStoreName(), matches.size())
                            + "임의로 하나를 고르는 대신 멈춘다. 수동으로 정리해야 한다."));
        }
        if (matches.isEmpty()) {
            return Mono.empty();
        }
        return Mono.just(matches.get(0).getId());
    }

    private Mono<String> createStore() {
        log.info("OpenFGA store '{}' 을 생성한다", properties.getStoreName());
        return Mono.fromCallable(this::storelessClient)
                .flatMap(client -> Mono.fromFuture(() -> {
                    try {
                        return client.createStore(new CreateStoreRequest().name(properties.getStoreName()));
                    } catch (Exception e) {
                        throw new IllegalStateException("store 생성 실패", e);
                    }
                }))
                .map(response -> response.getId());
    }

    /**
     * 인가 모델을 쓴 <b>뒤에만</b> storeId 를 기억한다(점검 M6). 먼저 기억하면 모델 쓰기가 실패해도 캐시가 남아, 다음
     * {@link #resolveStore()} 가 캐시를 보고 다시 시도하지 않는다 — 이 프로세스는 재시작 전까지 모델 없는 store 에 쓰고 묻는다.
     */
    private Mono<String> attachAndWriteModel(String storeId) {
        return Mono.fromCallable(() -> newClient(storeId))
                .flatMap(client -> Mono.fromFuture(() -> {
                            try {
                                return client.writeAuthorizationModel(authorizationModel());
                            } catch (Exception e) {
                                throw new IllegalStateException("인가 모델 등록 실패", e);
                            }
                        })
                        .doOnNext(response -> {
                            log.info("OpenFGA 인가 모델을 등록했다");
                            clientRef.set(client);
                            storeIdRef.set(storeId);
                        }))
                .thenReturn(storeId);
    }

    /** 등록할 인가 모델. 패키지 전용 — 모델 등록이 실패하는 경로를 테스트가 흉내 낸다. */
    WriteAuthorizationModelRequest authorizationModel() {
        try (InputStream in = new ClassPathResource(MODEL_RESOURCE).getInputStream()) {
            return new ObjectMapper().readValue(in, WriteAuthorizationModelRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException(MODEL_RESOURCE + " 를 읽을 수 없다", e);
        }
    }

    /** 없으면 만들고, 있으면 그대로 쓴다. 경합해서 둘이 만들어져도 한쪽만 남고 나머지는 버려진다. */
    private OpenFgaClient storelessClient() {
        OpenFgaClient existing = storelessClientRef.get();
        if (existing != null) {
            return existing;
        }
        OpenFgaClient created = newClient(null);
        return storelessClientRef.compareAndSet(null, created) ? created : storelessClientRef.get();
    }

    private OpenFgaClient newClient(String storeId) {
        try {
            ClientConfiguration configuration = new ClientConfiguration().apiUrl(properties.getApiUrl());
            if (storeId != null) {
                configuration.storeId(storeId);
            }
            return new OpenFgaClient(configuration);
        } catch (Exception e) {
            throw new IllegalStateException("OpenFGA 클라이언트 생성 실패", e);
        }
    }
}
```

`OpenFgaRelationTupleChecker`:
- 클래스 자바독의 두 번째 문단(`<p><b>{@code findExistingStore()} 를 쓴다.</b> …`)을 이것으로 바꾼다:

```java
 * <p><b>쓰기와 같은 준비 과정({@code resolveStore()})을 탄다(점검 M6).</b> store 가 없으면 만들고 인가 모델을 등록한다. 시작 때
 * OpenFGA 가 안 닿아 준비하지 못했어도 첫 요청 때 닿으면 그때 준비되고, 안 닿으면 그 요청만 실패하고 다음 요청이 다시 시도한다
 * (실패는 캐시하지 않는다). 전에는 {@code findExistingStore()} 를 써서 store 가 없으면 재시작 전까지 멤버 추가가 전부 500 이었다.
 * 헬스 체크만 여전히 {@code findExistingStore()}(보기만)다 — 오타 난 이름으로 빈 store 를 만들지 않는다.
```

- `check` 와 `existing` 의 `bootstrapper.findExistingStore().switchIfEmpty(Mono.error(…))` 두 곳을 각각 `bootstrapper.resolveStore()` 로 바꾼다(뒤의 `.flatMap(storeId -> …)` 는 그대로).

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :authz-openfga:test` → PASS.
Run: `git grep -n "recreateStore" -- authz-openfga core` → 결과 없음.

- [ ] **Step 6: 커밋**

```bash
git add authz-openfga/src/main/java/dev/starryeye/organization/authz/StoreBootstrapper.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaTestSupport.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/StoreBootstrapperTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleCheckerTest.java
git commit -m "feat: 장부 준비 — 동시 첫 생성은 먼저 만든 store 로 수렴, 모델 등록 뒤에만 번호 기억, Check 도 준비 과정을 탄다"
git push
```

---

### Task 7: app-scim — 재적재 202, 실행 기록 단건 조회, 기한·종료 결선 (+ 두 앱 공용 테스트 도우미)

**Files:**
- Modify: `admin-api/build.gradle`
- Create: `admin-api/src/testFixtures/java/dev/starryeye/organization/admin/fixture/SyncJobClient.java`
- Modify: `app-scim/build.gradle`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/AdminSyncController.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Modify: `app-scim/src/main/resources/application.yml`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRebuildEndToEndTest.java` (전체 교체)
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLimitsAndRecoveryScaleTest.java` (S18)
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRebuildLockScaleTest.java` (S18-b)

**Interfaces:**
- Consumes: `ScimRebuildUseCase#start`(Task 3), `SyncJobs`(Task 2), `SyncRunRepository#findById`(Task 1), `RelationTupleScanner` 빈(Task 5).
- Produces (테스트 도우미, Task 8 도 쓴다): `dev.starryeye.organization.admin.fixture.SyncJobClient`
  - `static WebTestClient.BodyContentSpec 끝까지(WebTestClient client, String uri)` — POST(202) → 끝날 때까지 → 끝난 기록 본문.
  - `static SyncRunResponse 건다(WebTestClient client, String uri)` — POST 202, RUNNING 확인.
  - `static SyncRunResponse 기다린다(WebTestClient client, String runId, Duration 최대)`.
  - `static SyncRunResponse 조회한다(WebTestClient client, String runId)` — GET 200.

- [ ] **Step 1: 테스트 도우미를 만든다**

`admin-api/build.gradle` 맨 위에 `apply plugin: 'java-test-fixtures'` 를 더하고, `dependencies` 안에 더한다:

```groovy
    // 두 앱의 테스트가 "202 로 건 작업이 끝날 때까지 기다린다"를 같은 도우미로 쓴다
    testFixturesApi 'org.springframework:spring-test'
    testFixturesImplementation 'org.springframework.boot:spring-boot-starter-webflux'
    testFixturesImplementation 'org.awaitility:awaitility'
```

`admin-api/src/testFixtures/java/dev/starryeye/organization/admin/fixture/SyncJobClient.java`:

```java
package dev.starryeye.organization.admin.fixture;

import dev.starryeye.organization.admin.SyncRunResponse;
import org.awaitility.Awaitility;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Objects;

/**
 * 관리 API 로 동기화·재적재를 걸고 끝날 때까지 기다린다 — 두 앱의 테스트가 함께 쓴다.
 *
 * <p>작업은 요청과 떼어 돈다(설계 2026-09-29 §4). {@code POST} 는 곧바로 202 와 실행 기록(RUNNING)을 주고, 결과는
 * {@code GET /admin/sync/runs/{runId}} 로 본다. 옛 테스트는 {@code POST} 응답 본문에서 결과를 읽었으므로, {@link #끝까지}는 끝난
 * 기록의 본문을 같은 모양({@link WebTestClient.BodyContentSpec})으로 돌려준다 — 뒤에 이어진 {@code jsonPath} 단언을 그대로 쓴다.
 */
public final class SyncJobClient {

    private static final Duration 기본_대기 = Duration.ofMinutes(20);
    private static final Duration 확인_간격 = Duration.ofMillis(200);

    private SyncJobClient() {
    }

    /** 걸고, 끝날 때까지 기다린 뒤, 끝난 기록의 본문을 준다. */
    public static WebTestClient.BodyContentSpec 끝까지(WebTestClient client, String uri) {
        String runId = 건다(client, uri).runId();
        기다린다(client, runId, 기본_대기);
        return client.get().uri("/admin/sync/runs/{runId}", runId).exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    /** 걸기만 한다 — 202 와 RUNNING 기록을 확인하고 돌려준다. */
    public static SyncRunResponse 건다(WebTestClient client, String uri) {
        SyncRunResponse started = client.post().uri(uri).exchange()
                .expectStatus().isAccepted()
                .expectBody(SyncRunResponse.class)
                .returnResult().getResponseBody();
        Objects.requireNonNull(started, "202 응답에 실행 기록이 없다");
        if (!"RUNNING".equals(started.status())) {
            throw new AssertionError("202 로 받은 기록은 RUNNING 이어야 한다: " + started);
        }
        return started;
    }

    /** 기록이 RUNNING 을 벗어날 때까지 본다. 끝난 기록을 준다. */
    public static SyncRunResponse 기다린다(WebTestClient client, String runId, Duration 최대) {
        return Awaitility.await()
                .atMost(최대)
                .pollInterval(확인_간격)
                .until(() -> 조회한다(client, runId), run -> !"RUNNING".equals(run.status()));
    }

    /** {@code GET /admin/sync/runs/{runId}} — 200 이어야 한다. */
    public static SyncRunResponse 조회한다(WebTestClient client, String runId) {
        return client.get().uri("/admin/sync/runs/{runId}", runId).exchange()
                .expectStatus().isOk()
                .expectBody(SyncRunResponse.class)
                .returnResult().getResponseBody();
    }
}
```

`app-scim/build.gradle` 의 `testImplementation testFixtures(project(':connector-scim'))` 아래에 더한다:

```groovy
    testImplementation testFixtures(project(':admin-api'))
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`ScimRebuildEndToEndTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.admin.fixture.SyncJobClient;
import dev.starryeye.organization.authz.OpenFgaProperties;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.Containers;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 재적재가 실제 인프라 위에서 동작하는지 확인한다.
 *
 * <p>이 스위트의 핵심은 {@link #튜플_재적재가_어긋남을_고치고_번호는_그대로다()} 다. 조회 API 가 어긋남을 드러내는 것까지는 이전
 * 사이클에서 확인했고, 여기서는 <b>그걸 실제로 고칠 수 있는지</b>, 그리고 고치는 동안 장부 번호가 바뀌지 않는지를 본다.
 *
 * <p>재적재는 202 로 곧바로 답하고 따로 돈다 — 결과는 {@link SyncJobClient} 로 기다려 본다(설계 2026-09-29 §4).
 *
 * <p>순서에 의존한다({@link Order}) — 앞 테스트가 만든 조직도 위에서 뒤 테스트가 어긋남을
 * 만들고 복구하고, 마지막에 전부 비운다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimRebuildEndToEndTest {

    /** {@code application-test.yml} 의 {@code dynamodb.table-name} 과 같아야 한다 */
    private static final String TABLE_NAME = "organization-scim-e2e";

    @Container
    static final GenericContainer<?> OPENFGA = Containers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = Containers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired OpenFgaProperties openFgaProperties;
    @Autowired MutationLock lock;

    private void 조직도를_만든다() {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"gd.hong","displayName":"홍길동","active":true}""")
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"gd.hong"}]}""")
                .exchange().expectStatus().isCreated();
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    /** 이름으로 새로 찾은 장부 번호 — 이 앱이 캐시한 번호가 아니라 OpenFGA store 목록에서 찾는다. 같은 이름이 둘이면 오류다. */
    private String 장부_번호() {
        return new StoreBootstrapper(openFgaProperties).findExistingStore().block(Duration.ofSeconds(10));
    }

    @Test
    @Order(1)
    @DisplayName("튜플 재적재가 직접 지운 튜플을 되살리고 직접 심은 찌꺼기를 지우며, 장부 번호는 그대로다")
    void 튜플_재적재가_어긋남을_고치고_번호는_그대로다() throws Exception {
        // given — 조직도를 만든 뒤 OpenFGA 에서 튜플 하나를 직접 지우고, 누구도 기록하지 않은 줄을 직접 심는다
        조직도를_만든다();
        String 재적재_전_번호 = 장부_번호();
        bootstrapper.client().deleteTuples(List.of(
                new ClientTupleKeyWithoutCondition()
                        .user("user:gd.hong").relation("direct_member")._object("group:DEV002"))).get();
        bootstrapper.client().writeTuples(List.of(
                new ClientTupleKey().user("user:ghost").relation("direct_member")._object("group:DEV002"))).get();

        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(false);

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=tuples")
                .jsonPath("$.trigger").isEqualTo("REBUILD")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 어긋남이 사라졌다. 이것이 이 기능의 존재 이유다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
        // 찌꺼기는 지워졌다 — 장부를 훑어 조직도가 요구하지 않는 줄을 지운다(설계 §3.1)
        assertThat(check("user:ghost", "member", "group:DEV002")).isFalse();
        // 장부 번호는 그대로다 — 다른 앱이 번호를 적어 둬도 된다(설계 §7)
        assertThat(장부_번호()).isEqualTo(재적재_전_번호);
    }

    @Test
    @Order(2)
    @DisplayName("튜플 재적재는 조직도를 건드리지 않는다")
    void 튜플_재적재는_조직도를_남긴다() {
        // when, then — 상태가 곧 진실이므로 재적재가 그것을 지우면 안 된다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("홍길동");
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("백엔드팀");
    }

    @Test
    @Order(3)
    @DisplayName("재적재는 SCIM 이력에 남는다")
    void 이력에_남는다() {
        // when, then
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].source").isEqualTo("SCIM")
                .jsonPath("$[0].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(4)
    @DisplayName("없는 실행 기록 번호는 404 다")
    void 없는_기록은_404다() {
        // when, then
        client.get().uri("/admin/sync/runs/missing-run")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @Order(5)
    @DisplayName("다른 작업이 락을 쥐고 있으면 재적재는 곧바로 409 이고 기록을 남기지 않는다")
    void 다른_작업이_락을_쥐면_409다() {
        // given — 다른 인스턴스의 SCIM 쓰기가 락을 쥔 순간
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block(Duration.ofSeconds(10));

        try {
            // when, then
            client.post().uri("/admin/sync/rebuild?mode=tuples")
                    .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        } finally {
            lock.release(lease).block(Duration.ofSeconds(10));
        }

        // then — 거절은 기록을 열지 않는다
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("wipe 는 confirm 이 테이블명과 다르면 400 이고 아무것도 지우지 않는다")
    void confirm이_틀리면_400이다() {
        // when, then — 불리언 플래그였다면 손가락이 미끄러져 조직도가 날아갔을 자리다
        client.post().uri("/admin/sync/rebuild?mode=wipe")
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/sync/rebuild?mode=wipe&confirm=아무거나")
                .exchange().expectStatus().isBadRequest();

        // 조직도는 그대로다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk();
    }

    @Test
    @Order(7)
    @DisplayName("알 수 없는 mode 는 400 이다")
    void 알수없는_모드는_400이다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=nuke")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @Order(8)
    @DisplayName("wipe 는 장부와 조직도를 전부 비우고 감사 이력은 남긴다")
    void wipe가_조직도를_비운다() {
        // when — 테이블명을 그대로 적어야만 실행된다
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=wipe&confirm=" + TABLE_NAME)
                .jsonPath("$.trigger").isEqualTo("RESET")
                .jsonPath("$.status").isEqualTo("SUCCEEDED");

        // then — 장부가 비었다
        assertThat(check("user:gd.hong", "member", "group:DEV002")).isFalse();

        // then — 직원도 조직도 사라졌다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isNotFound();
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();

        // then — 사고 뒤에 무슨 일이 있었는지 볼 기록은 남아 있다
        client.get().uri("/admin/sync/runs?limit=20")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].trigger").isEqualTo("RESET")
                .jsonPath("$[1].trigger").isEqualTo("REBUILD");
    }

    @Test
    @Order(9)
    @DisplayName("wipe 뒤에도 SCIM 쓰기는 열려 있다 — IdP 재푸시를 받아야 하기 때문이다")
    void wipe_뒤에_쓰기가_열려있다() {
        // when — IdP 가 재프로비저닝으로 다시 밀어넣는 상황이다
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"cs.kim","displayName":"김철수","active":true}""")
                .exchange().expectStatus().isCreated();

        // then — 락이 반납되지 않았다면 여기서 503 이 났을 것이다
        client.get().uri("/admin/employees/cs.kim")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayName").isEqualTo("김철수");
    }
}
```

`ScimLimitsAndRecoveryScaleTest` 의 S18 에서 `// when` 블록을 바꾼다(import `dev.starryeye.organization.admin.fixture.SyncJobClient`):

```java
        // when
        long t0 = System.currentTimeMillis();
        SyncJobClient.끝까지(client, "/admin/sync/rebuild?mode=tuples")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
        System.out.printf("=== S18. mode=tuples 재적재: %.1f초%n",
                (System.currentTimeMillis() - t0) / 1000.0);
```

같은 테스트의 고아 튜플 주석(`// 고아 튜플은 사라진다. 재적재는 이전 스냅샷을 지우고 다시 쓰므로,` 두 줄)을 이것으로 바꾼다:

```java
        // 고아 튜플은 사라진다. 재적재는 장부를 훑어 멤버십에서 유도되지 않는 줄을 지우므로
        // 남을 자리가 없다.
```

`이력과_메트릭이_남는다` 의 주석 `// 재적재는 store 를 비우고 상태가 요구하는 튜플을 전부 다시 쓴다 — 픽스처에서 유도한다` 를
`// 재적재는 상태가 요구하는 튜플을 전부 쓴다(이미 있는 줄도 센다) — 픽스처에서 유도한다` 로 바꾼다.

`ScimRebuildLockScaleTest` 의 `S18b_재적재_중_쓰기와_리스` 를 통째로 바꾸고 도우미를 더한다(import 에서 `LockLease`, `Mono`, `CompletableFuture`, `CountDownLatch`, `TimeUnit`, `doAnswer` 를 빼고
`dev.starryeye.organization.admin.SyncRunResponse`, `dev.starryeye.organization.admin.fixture.SyncJobClient`, `dev.starryeye.organization.core.model.RelationTuple` 을 더한다):

```java
    @Test
    @Order(2)
    @DisplayName("S18-b. 재적재 도중 들어온 SCIM 쓰기는 503 이고, 기존 권한은 내내 참이며, 재적재는 리스를 지켜 완주한다")
    void S18b_재적재_중_쓰기와_리스() {
        // given — 앞선 기준 적재에서 쓰기가 renew 를 불렀을 수 있다. 이 시나리오의 호출만 센다
        clearInvocations(lock);
        RelationTuple 기존권한 = RelationTuple.member(기대.landmarks().L6직속직원(), 기대.landmarks().회사());

        // when — 재적재를 건다. 202 는 락을 잡은 뒤에만 오므로 이 뒤의 쓰기는 재적재와 겹친다
        long t0 = System.currentTimeMillis();
        String runId = SyncJobClient.건다(client, "/admin/sync/rebuild?mode=tuples").runId();

        // 도는 동안 SCIM 쓰기를 두드리고, 기존 권한이 내내 참인지 본다 — 장부를 비우는 순간이 없어야 한다(설계 2026-09-29 §3.1)
        List<Integer> 응답들 = new ArrayList<>();
        List<Boolean> 권한들 = new ArrayList<>();
        while (도는_중이다(runId) && 응답들.size() < 200) {
            응답들.add(쓰기를_시도한다());
            권한들.add(ScaleVerification.성립하는가(checker, 기존권한));
        }
        SyncRunResponse 끝난것 = SyncJobClient.기다린다(client, runId, Duration.ofMinutes(20));
        long 소요 = System.currentTimeMillis() - t0;

        System.out.printf("%n=== S18-b. 재적재 %.1f초 / 그동안 쓰기 %d건 시도%n",
                소요 / 1000.0, 응답들.size());
        System.out.println("    응답 분포: " + 응답들.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        code -> code, java.util.TreeMap::new, java.util.stream.Collectors.counting())));

        // then — 재적재가 완주했다
        assertThat(끝난것.status()).as("재적재가 실패했다 — 리스를 잃었을 수 있다: " + 끝난것.message())
                .isEqualTo("SUCCEEDED");
        // 리스 갱신이 TTL 을 넘길 만큼 여러 번 일했다. 쓰기는 델타가 있을 때만 renew 를
        // 부르고(설계 §4.7) 여기서 두드린 쓰기는 이미 활성인 직원에게 active:true 를 보내
        // 델타가 없다 — 그러므로 이 호출들은 재적재의 하트비트다. 한 번만 불렸다는 것으로는
        // 재적재가 갱신 주기만큼만 돌았다는 것만 보일 뿐 리스가 제 만료를 넘겨 살아남았다는
        // 주장은 못 한다 — 그래서 최소 횟수를 요구한다
        verify(lock, atLeast(최소_갱신_횟수)
                .description("renew 가 " + 최소_갱신_횟수 + "번 미만으로 불렸다 — 재적재가 리스가"
                        + " 만료됐을 시점보다 먼저 끝나 갱신이 필요 없었다는 뜻이라, 이 테스트의 전제"
                        + "(재적재가 리스 TTL 보다 오래 걸린다)가 이 환경에서는 성립하지 않는다"))
                .renew(any());

        // 그동안 들어온 쓰기는 503 이다. IdP 는 503 을 재시도 신호로 보므로 유실되지 않는다
        assertThat(응답들).as("재적재 중에 쓰기를 한 번도 못 시도했다").isNotEmpty();
        assertThat(응답들).as("503 이외의 거절이 있었다 — IdP 가 영구 실패로 볼 수 있다")
                .allMatch(code -> code == 503 || code == 200);
        assertThat(응답들).as("재적재가 락을 쥐고 있는데 쓰기가 한 건도 안 막혔다")
                .contains(503);

        // 재적재 도중 기존 권한이 한 번이라도 거짓이면 인가 공백이 있다
        assertThat(권한들).isNotEmpty().doesNotContain(false);

        // 재적재가 끝난 상태는 정합이다
        검증한다();
    }

    private boolean 도는_중이다(String runId) {
        return "RUNNING".equals(SyncJobClient.조회한다(client, runId).status());
    }
```

`@MockitoSpyBean MutationLock lock;` 위 주석을 이것으로 바꾼다(202 는 락을 잡은 뒤에만 오므로 "락을 잡았다"를 기다릴 일이 없어졌다):

```java
    /**
     * 실제 락을 감싼 스파이. 동작은 그대로다(callRealMethod) — "리스를 갱신했다" 를 직접 확인하려고 쓴다.
     * 운영 코드에 지표를 더하지 않는다.
     */
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew :app-scim:compileTestJava`
Expected: 컴파일 실패 — `ScimRebuildUseCase` 생성자·`execute` 가 없다(Task 3 뒤로 앱이 깨져 있다).

- [ ] **Step 4: 앱을 고친다**

`AdminSyncController`(app-scim) 를 통째로 바꾼다:

```java
package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import dev.starryeye.organization.core.usecase.ScimRebuildMode;
import dev.starryeye.organization.core.usecase.ScimRebuildUseCase;
import dev.starryeye.organization.storage.DynamoDbProperties;
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

/**
 * SCIM 인스턴스의 관리 API. app-ldap 의 같은 이름 컨트롤러와 표면을 맞춘다.
 *
 * <p><b>{@code /full} 이 없다.</b> LDAP 은 언제든 다시 읽어올 수 있지만 SCIM 은 push 모델이라
 * "전체를 다시 달라"고 말할 상대가 없다. 그래서 재적재와 이력 조회만 제공한다.
 */
@Slf4j
@RestController
@RequestMapping("/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    private static final int MIN_RUNS_LIMIT = 1;
    private static final int MAX_RUNS_LIMIT = 100;

    private final ScimRebuildUseCase rebuild;
    private final SyncRunRepository runs;
    private final DynamoDbProperties dynamoDb;

    /**
     * 재적재를 건다. 락을 잡았으면 곧바로 202 와 실행 기록(RUNNING)을 준다 — 재적재는 요청과 떼어 돌고(설계 2026-09-29 §4),
     * 결과는 {@code GET /admin/sync/runs/{runId}} 로 본다.
     *
     * @param mode    {@code tuples}(기본) 또는 {@code wipe}
     * @param confirm {@code wipe} 일 때만 필요하다. DynamoDB 테이블명을 그대로 적어야 한다
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(defaultValue = "tuples") String mode,
                                         @RequestParam(required = false) String confirm) {
        ScimRebuildMode rebuildMode;
        try {
            rebuildMode = ScimRebuildMode.from(mode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 mode 값: " + mode, e);
        }

        if (rebuildMode == ScimRebuildMode.WIPE) {
            requireConfirmation(confirm);
            log.warn("SCIM 조직도 전체 초기화 요청. 실행 뒤 IdP 재프로비저닝이 필요하다");
        } else {
            log.warn("SCIM 튜플 재적재 요청");
        }

        return rebuild.start(rebuildMode)
                .map(SyncRunResponse::from)
                // 다른 인스턴스가 SCIM 쓰기나 재적재로 락을 쥐고 있어 이번 재적재가 시작하지
                // 못한 경우는 409 다 — 관리자가 잠시 뒤 다시 시도하면 된다.
                .onErrorMap(LockUnavailableException.class, e ->
                        new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }

    /**
     * 조직도의 유일한 사본을 지우는 요청이라 불리언 플래그로는 부족하다. 테이블명을 적게 하면
     * 관리자가 <b>자기가 무엇을 지우는지 찾아보게</b> 된다 — 손가락이 미끄러져 눌리지 않는다.
     */
    private void requireConfirmation(String confirm) {
        if (!dynamoDb.getTableName().equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "wipe 는 confirm 에 DynamoDB 테이블명을 그대로 적어야 합니다. "
                            + "이 작업은 조직도를 전부 지우며 되돌릴 수 없고, 실행 뒤 IdP 콘솔에서 "
                            + "전체 재프로비저닝을 걸어야 복구됩니다");
        }
    }

    @GetMapping("/runs")
    public Flux<SyncRunResponse> runs(@RequestParam(defaultValue = "20") int limit) {
        int clamped = Math.max(MIN_RUNS_LIMIT, Math.min(limit, MAX_RUNS_LIMIT));
        return runs.findRecent(clamped).map(SyncRunResponse::from);
    }

    /** 실행 기록 하나. 202 로 건 작업의 결과를 여기서 본다. 없으면 404 — 잘못된 번호이거나 보관 기간(30일)이 지났다. */
    @GetMapping("/runs/{runId}")
    public Mono<SyncRunResponse> run(@PathVariable String runId) {
        return runs.findById(runId)
                .map(SyncRunResponse::from)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "실행 기록이 없습니다: " + runId)));
    }
}
```

`ScimUseCaseConfig`:
- import 에 `dev.starryeye.organization.core.port.RelationTupleScanner`, `dev.starryeye.organization.core.usecase.SyncJobs`,
  `org.springframework.beans.factory.annotation.Value`, `java.time.Duration` 을 더한다.
- `scimRebuildUseCase` 빈을 바꾸고 `SyncJobs` 빈을 더한다:

```java
    /**
     * 재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). {@code sync.job-timeout} 을 넘기면 멈춰 FAILED 로 기록하고 락을 반납한다.
     * 앱이 내려갈 때 도는 재적재를 FAILED("서버 종료로 중단")로 기록하고 락을 반납한다 — DynamoDB 클라이언트보다 먼저 닫힌다
     * (이 빈이 실행 기록 저장소를 거쳐 클라이언트에 기대므로 스프링이 먼저 닫는다).
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, @Value("${sync.job-timeout:30m}") Duration jobTimeout) {
        return new SyncJobs(runs, jobTimeout);
    }

    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 RelationTupleScanner scanner,
                                                 TupleSnapshotRepository snapshots,
                                                 MutationLock lock,
                                                 DynamoDbProperties dynamoDb,
                                                 ScimSyncMetrics metrics,
                                                 SyncJobs jobs,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, scanner, snapshots, lock,
                dynamoDb.getLockRenewInterval(), metrics, jobs, clock);
    }
```

`app-scim/src/main/resources/application.yml` 의 `sync:` 아래에 더한다:

```yaml
  # 재적재 한 번의 전체 기한. 넘으면 남은 일을 멈추고 FAILED("기한 초과 — 30분")로 기록한 뒤 락을 푼다
  job-timeout: 30m
```

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :app-scim:compileTestJava` → 성공.
Run: `./gradlew :app-scim:test --tests '*ScimRebuildEndToEndTest'` → PASS(9개).
(규모 테스트 두 개는 컴파일만 확인한다. 실행은 컨트롤러가 한다.)

- [ ] **Step 6: 커밋**

```bash
git add admin-api/build.gradle admin-api/src/testFixtures \
  app-scim/build.gradle app-scim/src/main app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRebuildEndToEndTest.java \
  app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLimitsAndRecoveryScaleTest.java \
  app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimRebuildLockScaleTest.java
git commit -m "feat: app-scim 재적재를 202 로 곧바로 답하고 따로 돌린다 — GET /runs/{runId}, sync.job-timeout, 종료 시 FAILED+락 반납"
git push
```

---

### Task 8: app-ldap — 수동 동기화·재적재 202, 모드 하나, 재시작 정리

**Files:**
- Modify: `app-ldap/build.gradle`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/AdminSyncController.java` (전체 교체)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncExecutionGuard.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncProperties.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncScheduler.java`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java` (전체 교체)
- Create: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/InterruptedRunCleanup.java`
- Modify: `app-ldap/src/main/resources/application.yml`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminSyncControllerTest.java` (전체 교체)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncExecutionGuardTest.java` (신규)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/InterruptedRunCleanupTest.java` (신규)
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncSchedulerGuardReleaseTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/TraceCorrelationTest.java`
- Test: `AdminQueryScaleTest`, `DitScaleSyncTest`, `LdapDeletionGuardScaleTest`, `LdapInterruptedSyncScaleTest`, `LdapPagingScaleTest`, `LdapScaleScenarioTest`, `LdapScaleSyncCostTest` (호출부만)

**Interfaces:**
- Consumes: `FullSyncUseCase#start`·`RebuildUseCase#start`(Task 4), `SyncJobs`(Task 2), `SyncRunRepository#findById`·`FakeSyncRunRepository#seed`(Task 1), `RelationTupleScanner` 빈(Task 5), `SyncJobClient`(Task 7).
- Produces: `SyncExecutionGuard#releaseOnce(): Supplier<Mono<Void>>`, `SyncProperties#getJobTimeout(): Duration`(기본 30분),
  `InterruptedRunCleanup(SyncRunRepository) implements InitializingBean`(`static final String 사유 = "재시작으로 중단"`).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`app-ldap/build.gradle` 의 `testImplementation testFixtures(project(':connector-ldap'))` 아래에 `testImplementation testFixtures(project(':admin-api'))` 를 더한다.

`AdminSyncControllerTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class AdminSyncControllerTest {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private FullSyncUseCase fullSync;
    private RebuildUseCase rebuild;
    private SyncRunRepository runs;
    private SyncExecutionGuard executionGuard;
    private WebTestClient client;

    @BeforeEach
    void 컨트롤러를_준비한다() {
        fullSync = Mockito.mock(FullSyncUseCase.class);
        rebuild = Mockito.mock(RebuildUseCase.class);
        runs = Mockito.mock(SyncRunRepository.class);
        executionGuard = new SyncExecutionGuard();
        client = WebTestClient.bindToController(
                new AdminSyncController(fullSync, rebuild, runs, executionGuard,
                        new SyncMetrics(new SimpleMeterRegistry()))).build();
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
        Mockito.when(fullSync.start(eq(SyncTrigger.MANUAL), any(), any()))
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
        Mockito.when(fullSync.start(eq(SyncTrigger.FORCED), any(), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.FORCED)));

        // when, then
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.trigger").isEqualTo("FORCED");

        Mockito.verify(fullSync).start(eq(SyncTrigger.FORCED), any(), any());
    }

    @Test
    @DisplayName("동기화가 이미 진행 중이면 409 로 거절하고 걸지 않는다")
    void 중복_실행은_409로_거절한다() {
        // given
        executionGuard.tryAcquire();

        // when, then
        client.post().uri("/admin/sync/full").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
        Mockito.verifyNoInteractions(fullSync);
    }

    @Test
    @DisplayName("202 로 답한 작업이 도는 동안 다시 걸면 409 이고, 작업이 반납하면 다시 받는다")
    void 도는_동안은_409이고_반납하면_다시_받는다() {
        // given — 반납 수단을 쥔 채 아직 끝나지 않은 작업
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Mono<Void>>> 반납수단 = ArgumentCaptor.forClass(Supplier.class);
        Mockito.when(fullSync.start(any(), 반납수단.capture(), any()))
                .thenReturn(Mono.just(도는실행(SyncTrigger.MANUAL)));
        client.post().uri("/admin/sync/full").exchange().expectStatus().isAccepted();

        // when, then — 202 로 답했어도 작업은 아직 돈다
        client.post().uri("/admin/sync/full").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        client.post().uri("/admin/sync/rebuild").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // when — 작업이 끝나 반납하면
        반납수단.getValue().get().block();

        // then
        client.post().uri("/admin/sync/full").exchange().expectStatus().isAccepted();
    }

    @Test
    @DisplayName("작업을 걸지 못하면(기록을 열지 못함) 5xx 이고 가드는 풀린다")
    void 걸지_못하면_가드가_풀린다() {
        // given
        Mockito.when(fullSync.start(any(), any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("DynamoDB 장애")));

        // when
        client.post().uri("/admin/sync/full").exchange().expectStatus().is5xxServerError();

        // then — 안 풀리면 이후 모든 동기화가 409 로 막힌다
        assertThat(executionGuard.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("유스케이스가 Mono 를 만들기도 전에 던져도 가드는 풀린다")
    void 동기_예외에도_가드가_풀린다() {
        // given
        Mockito.when(fullSync.start(any(), any(), any()))
                .thenThrow(new IllegalStateException("Mono 구성 전 동기 예외"));

        // when
        client.post().uri("/admin/sync/full").exchange().expectStatus().is5xxServerError();

        // then
        assertThat(executionGuard.tryAcquire()).isTrue();
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

        // when, then — Flux.take(-1) 이 조립 시점에 던지던 예외가 더 이상 나오면 안 된다
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
    @DisplayName("실행 기록 하나를 번호로 본다 — 202 로 건 작업의 결과를 보는 자리다")
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
    @DisplayName("rebuild 는 모드 없이 재적재를 걸고 202 와 RUNNING 기록을 준다")
    void rebuild_는_202다() {
        // given
        Mockito.when(rebuild.start(any(), any())).thenReturn(Mono.just(도는실행(SyncTrigger.REBUILD)));

        // when, then
        client.post().uri("/admin/sync/rebuild").exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.trigger").isEqualTo("REBUILD");
    }

    @Test
    @DisplayName("rebuild 에 mode 를 주면 400 이다 — 재적재 모드는 하나로 합쳐졌다")
    void rebuild_에_mode를_주면_400이다() {
        // when, then — 조용히 무시하면 mode=store 로 "깨끗이 비우기"를 기대한 사람이 다른 일을 받는다
        client.post().uri("/admin/sync/rebuild?mode=store").exchange()
                .expectStatus().isBadRequest();

        Mockito.verifyNoInteractions(rebuild);
        assertThat(executionGuard.tryAcquire()).as("거절한 요청은 가드를 잡지 않는다").isTrue();
    }
}
```

`SyncExecutionGuardTest.java`(신규):

```java
package dev.starryeye.organization.ldap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncExecutionGuardTest {

    @Test
    @DisplayName("반납 수단은 몇 번 불려도 한 번만 푼다 — 그사이 남이 잡은 점유를 풀지 않는다")
    void 반납은_한_번만_푼다() {
        // given
        var guard = new SyncExecutionGuard();
        guard.tryAcquire();
        var 반납 = guard.releaseOnce();

        // when — 작업이 반납하고, 다른 작업이 잡은 뒤, 요청 쪽이 다시 반납을 부른다
        반납.get().block();
        assertThat(guard.tryAcquire()).as("반납했으면 다시 잡힌다").isTrue();
        반납.get().block();

        // then — 두 번째 반납이 남의 점유를 풀면 동기화 둘이 겹친다
        assertThat(guard.tryAcquire()).isFalse();
    }
}
```

`InterruptedRunCleanupTest.java`(신규):

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InterruptedRunCleanupTest {

    private static final Instant 지금 = Instant.parse("2026-09-29T03:00:00Z");

    @Test
    @DisplayName("지난 프로세스가 남긴 app-ldap RUNNING 기록을 FAILED(재시작으로 중단)로 닫는다")
    void 남은_RUNNING을_닫는다() {
        // given — 죽은 프로세스의 기록, 다른 앱(SCIM)의 기록, 이미 끝난 기록
        var runs = new FakeSyncRunRepository(지금);
        runs.seed(SyncRun.started("ldap-죽음", SyncSource.LDAP, SyncTrigger.MANUAL, 지금.minusSeconds(60)));
        runs.seed(SyncRun.started("scim-도는중", SyncSource.SCIM, SyncTrigger.REBUILD, 지금.minusSeconds(30)));
        runs.seed(SyncRun.started("ldap-끝남", SyncSource.LDAP, SyncTrigger.SCHEDULED, 지금.minusSeconds(3600))
                .finished(SyncOutcome.noChange(), 지금.minusSeconds(3500)));

        // when
        new InterruptedRunCleanup(runs).afterPropertiesSet();

        // then
        SyncRun 죽은것 = runs.findById("ldap-죽음").block();
        assertThat(죽은것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(죽은것.message()).isEqualTo("재시작으로 중단");
        assertThat(runs.findById("scim-도는중").block().status())
                .as("다른 앱의 기록은 그 앱의 일이다").isEqualTo(SyncStatus.RUNNING);
        assertThat(runs.findById("ldap-끝남").block().status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("기록을 읽지 못해도 앱 시작을 막지 않는다")
    void 읽지_못해도_시작을_막지_않는다() {
        // given
        SyncRunRepository 고장난_저장소 = new SyncRunRepository() {
            @Override
            public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
                return Mono.error(new IllegalStateException("쓰지 않는다"));
            }

            @Override
            public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
                return Mono.error(new IllegalStateException("쓰지 않는다"));
            }

            @Override
            public Flux<SyncRun> findRecent(int limit) {
                return Flux.error(new IllegalStateException("DynamoDB 장애"));
            }

            @Override
            public Mono<SyncRun> findById(String runId) {
                return Mono.empty();
            }
        };

        // when, then
        assertThatCode(() -> new InterruptedRunCleanup(고장난_저장소).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
```

`SyncSchedulerGuardReleaseTest` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스케줄 동기화를 <b>걸지 못해도</b> {@link SyncExecutionGuard} 는 반드시 반납되어야 한다.
 *
 * <p>반납되지 않으면 스케줄러가 영구히 멈추고, 이후 모든 야간 동기화가 "이전 동기화가
 * 아직 진행 중" 경고만 남긴 채 조용히 건너뛴다. 아무도 알아채지 못한다.
 *
 * <p>걸고 나서의 반납은 작업({@code SyncJobs})의 몫이다 — 여기서는 걸기 전 두 가지 실패를 본다.
 */
class SyncSchedulerGuardReleaseTest {

    @Test
    @DisplayName("동기화가 Mono 생성 전에 동기적으로 예외를 던져도 가드는 반납된다")
    void 동기적_예외에도_가드가_반납된다() {
        // given
        var guard = new SyncExecutionGuard();
        // 만료 스냅샷 정리 경로는 이 테스트가 다루는 시나리오와 무관하다.
        var scheduler = new SyncScheduler(new 걸지_못하는_동기화(true), null, guard,
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);

        // when
        assertThatCode(scheduler::전체동기화).doesNotThrowAnyException();

        // then
        assertThat(guard.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("실행 기록을 열지 못해 걸지 못하면 가드는 반납된다")
    void 걸지_못하면_가드가_반납된다() {
        // given
        var guard = new SyncExecutionGuard();
        var scheduler = new SyncScheduler(new 걸지_못하는_동기화(false), null, guard,
                new SyncMetrics(new SimpleMeterRegistry()), ObservationRegistry.NOOP);

        // when
        scheduler.전체동기화();

        // then
        assertThat(guard.tryAcquire()).isTrue();
    }

    /** start() 가 곧바로 던지거나(동기) 오류 Mono 를 준다. */
    private static final class 걸지_못하는_동기화 extends FullSyncUseCase {

        private final boolean 동기로_던진다;

        걸지_못하는_동기화(boolean 동기로_던진다) {
            super(null, null, null, null,
                    new DeletionGuard(DeletionGuardPolicy.defaults()), null, Clock.systemUTC());
            this.동기로_던진다 = 동기로_던진다;
        }

        @Override
        public Mono<SyncRun> start(SyncTrigger trigger, Supplier<Mono<Void>> release, Consumer<SyncRun> onFinished) {
            if (동기로_던진다) {
                throw new IllegalStateException("Mono 구성 전 동기 예외");
            }
            return Mono.error(new IllegalStateException("실행 기록을 열지 못했다"));
        }
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :app-ldap:compileTestJava`
Expected: 컴파일 실패 — `releaseOnce`, `InterruptedRunCleanup`, 새 유스케이스 API 결선이 없다.

- [ ] **Step 3: 앱을 고친다**

`SyncExecutionGuard` 에 더한다(import `reactor.core.publisher.Mono`, `java.util.function.Supplier`; `AtomicBoolean` 은 이미 있다):

```java
    /**
     * 이번 점유를 푸는 반납 수단. 몇 번 불려도 한 번만 푼다 — 작업 쪽(SyncJobs)과 요청 쪽(걸기 실패 처리)이 둘 다 불러도
     * 그사이 남이 잡은 점유를 풀지 않는다.
     */
    public Supplier<Mono<Void>> releaseOnce() {
        AtomicBoolean 반납됨 = new AtomicBoolean(false);
        return () -> Mono.fromRunnable(() -> {
            if (반납됨.compareAndSet(false, true)) {
                release();
            }
        });
    }
```

`SyncProperties` 에 필드를 더한다(import `java.time.Duration`):

```java
    /**
     * 동기화·재적재 한 번의 전체 기한(설계 2026-09-29 §5). 넘으면 남은 일을 멈추고 FAILED("기한 초과 — N분")로 기록한 뒤 가드를 푼다.
     * 10만 명 재적재(쓰기 약 1,100 배치 + 장부 훑기 약 1,100 호출)를 수 분으로 추정해 넉넉히 잡았다.
     */
    private Duration jobTimeout = Duration.ofMinutes(30);
```

`InterruptedRunCleanup.java`(신규):

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;
import java.util.List;

/**
 * 앱이 뜰 때, 지난 프로세스가 끝내지 못한 app-ldap 실행 기록(RUNNING)을 FAILED("재시작으로 중단")로 닫는다 (설계 2026-09-29 §4).
 *
 * <p>실행 가드가 프로세스 안에만 있어, 이 프로세스가 뜨는 시점에 살아 있는 app-ldap 작업은 없다 — 남은 RUNNING 은 전부 죽은
 * 프로세스(kill -9, 정전, 정상 종료 대기 초과)의 것이다. 두면 영원히 RUNNING 으로 보여 "지금 도는 중인가"를 기록으로 판단할 수 없다.
 *
 * <p>빈 생성 단계에서 돈다 — 웹 서버가 요청을 받기 전, 스케줄러가 돌기 전이라 이 프로세스가 연 기록을 잘못 닫지 않는다. 실패해도 앱
 * 시작을 막지 않는다(경고만 남긴다) — 기록 정리는 부가 기능이다.
 *
 * <p>app-ldap 을 여러 대 띄우면 이 전제가 깨진다 — 점검 ②-2(M14 클러스터 락)에서 다시 본다.
 */
@Slf4j
@RequiredArgsConstructor
public class InterruptedRunCleanup implements InitializingBean {

    static final String 사유 = "재시작으로 중단";

    /** 최근 기록 몇 개를 볼지. 죽은 프로세스의 RUNNING 은 가장 최근 기록들 사이에 있다. */
    private static final int 살펴볼_기록 = 100;

    private final SyncRunRepository runs;

    @Override
    public void afterPropertiesSet() {
        try {
            List<SyncRun> 닫은것 = runs.findRecent(살펴볼_기록)
                    .filter(run -> run.source() == SyncSource.LDAP && run.status() == SyncStatus.RUNNING)
                    .concatMap(run -> runs.finish(run, SyncOutcome.failed(사유)))
                    .collectList()
                    .block(Duration.ofSeconds(30));
            if (닫은것 != null && !닫은것.isEmpty()) {
                log.warn("지난 프로세스가 끝내지 못한 실행 기록 {}개를 FAILED(\"{}\")로 닫았다: {}",
                        닫은것.size(), 사유, 닫은것.stream().map(SyncRun::runId).toList());
            }
        } catch (RuntimeException e) {
            log.warn("시작 시점에 남은 실행 기록을 정리하지 못했다. 앱은 계속 기동한다", e);
        }
    }
}
```

`UseCaseConfig` 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleScanner;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
import dev.starryeye.organization.core.usecase.RebuildUseCase;
import dev.starryeye.organization.core.usecase.SyncJobs;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

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

    @Bean
    public SyncExecutionGuard syncExecutionGuard() {
        return new SyncExecutionGuard();
    }

    /**
     * 동기화·재적재를 요청과 떼어 돌린다(설계 2026-09-29 §4·§5). {@code sync.job-timeout} 을 넘기면 멈춰 FAILED 로 기록하고 가드를
     * 푼다. 앱이 내려갈 때 도는 작업을 FAILED("서버 종료로 중단")로 기록한다 — 실행 기록 저장소를 거쳐 DynamoDB 클라이언트에 기대므로
     * 스프링이 클라이언트보다 먼저 닫는다.
     */
    @Bean(destroyMethod = "shutdown")
    public SyncJobs syncJobs(SyncRunRepository runs, SyncProperties properties) {
        return new SyncJobs(runs, properties.getJobTimeout());
    }

    /** 테이블이 준비된 뒤에 돈다 — 첫 배포에서 테이블이 없으면 읽기가 실패한다(실패해도 시작은 막지 않는다). */
    @Bean
    @DependsOn("tableInitializer")
    public InterruptedRunCleanup interruptedRunCleanup(SyncRunRepository runs) {
        return new InterruptedRunCleanup(runs);
    }

    @Bean
    public FullSyncUseCase fullSyncUseCase(DirectorySnapshotSource source,
                                           TupleSnapshotRepository snapshots,
                                           DirectoryStateRepository state,
                                           RelationTupleWriter writer,
                                           DeletionGuard guard,
                                           SyncJobs jobs,
                                           Clock clock) {
        return new FullSyncUseCase(source, snapshots, state, writer, guard, jobs, clock);
    }

    @Bean
    public RebuildUseCase rebuildUseCase(DirectorySnapshotSource source,
                                         TupleSnapshotRepository snapshots,
                                         DirectoryStateRepository state,
                                         RelationTupleWriter writer,
                                         RelationTupleScanner scanner,
                                         SyncJobs jobs,
                                         Clock clock) {
        return new RebuildUseCase(source, snapshots, state, writer, scanner, jobs, clock);
    }
}
```

`AdminSyncController`(app-ldap) 를 통째로 바꾼다:

```java
package dev.starryeye.organization.ldap.app;

import dev.starryeye.organization.admin.SyncRunResponse;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.usecase.FullSyncUseCase;
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

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * app-ldap 관리 API. 동기화·재적재는 겹치지 않으면 곧바로 202 와 실행 기록(RUNNING)을 주고 따로 돈다(설계 2026-09-29 §4) —
 * 결과는 {@code GET /admin/sync/runs/{runId}} 로 본다.
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
    private final SyncExecutionGuard executionGuard;
    private final SyncMetrics metrics;

    /**
     * @param force true 면 삭제 가드를 건너뛴다. ABORTED 이후 사람이 판단해서 승인하는 통로다
     */
    @PostMapping("/full")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> full(@RequestParam(defaultValue = "false") boolean force) {
        SyncTrigger trigger = force ? SyncTrigger.FORCED : SyncTrigger.MANUAL;
        log.info("수동 전체 동기화 요청: trigger={}", trigger);
        return guarded(release -> fullSync.start(trigger, release, metrics::record));
    }

    /**
     * 전체 재적재를 건다. 모드는 하나다(설계 §3.3) — LDAP 을 다시 읽어 있어야 할 줄을 쓰고, 장부를 훑어 없어야 할 줄을 지운다.
     * 옛 {@code mode} 파라미터가 오면 400 으로 알린다 — 조용히 무시하면 {@code mode=store} 로 "깨끗이 비우기"를 기대한 사람이
     * 다른 일을 받는다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<SyncRunResponse> rebuild(@RequestParam(required = false) String mode) {
        if (mode != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "재적재 모드는 하나로 합쳐졌습니다 — mode 없이 POST /admin/sync/rebuild 를 호출하세요 (받은 mode: " + mode + ")");
        }
        log.warn("전체 재적재 요청");
        return guarded(release -> rebuild.start(release, metrics::record));
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

    /**
     * 겹치지 않게 가드를 잡고 작업을 건다. 못 잡으면 409. 잡았으면 반납 수단을 작업에 넘긴다 — 작업이 어떻게 끝나든 SyncJobs 가
     * 한 번 부른다. 걸기 전에 실패하면(기록을 열지 못함, 동기 예외) 여기서도 부르지만 {@link SyncExecutionGuard#releaseOnce} 라 한 번만
     * 푼다. 가드 잡기와 걸기가 같은 구독 안에서 일어나, 그 사이에 요청이 끊겨 가드만 잡힌 채 남는 틈이 없다.
     */
    private Mono<SyncRunResponse> guarded(Function<Supplier<Mono<Void>>, Mono<SyncRun>> start) {
        return Mono.defer(() -> {
            if (!executionGuard.tryAcquire()) {
                return Mono.<SyncRun>error(new ResponseStatusException(
                        HttpStatus.CONFLICT, "동기화가 이미 진행 중입니다"));
            }
            Supplier<Mono<Void>> release = executionGuard.releaseOnce();
            return Mono.defer(() -> start.apply(release))
                    .onErrorResume(error -> release.get().then(Mono.<SyncRun>error(error)));
        }).map(SyncRunResponse::from);
    }
}
```

`SyncScheduler.전체동기화()` 를 바꾼다(import `java.util.function.Supplier`, `reactor.core.publisher.Mono` 는 이미 있다):

```java
    @Scheduled(cron = "${sync.cron}")
    public void 전체동기화() {
        if (!executionGuard.tryAcquire()) {
            log.warn("이전 동기화가 아직 진행 중이라 이번 스케줄을 건너뛴다");
            return;
        }
        Supplier<Mono<Void>> release = executionGuard.releaseOnce();
        관측하며실행("sync.ldap.full",
                Mono.defer(() -> fullSync.start(SyncTrigger.SCHEDULED, release, run -> {
                            metrics.record(run);
                            log.info("스케줄 동기화 완료: status={} written={} deleted={} failed={}",
                                    run.status(), run.writtenCount(), run.deletedCount(), run.failureCount());
                        }))
                        // 걸고 나서의 반납은 작업(SyncJobs)이 한다. 여기서는 걸지 못한 경우만 푼다
                        .onErrorResume(error -> {
                            log.error("스케줄 동기화를 걸지 못했다", error);
                            return release.get().then(Mono.empty());
                        }));
    }
```

`관측하며실행` 자바독 끝에 한 문단을 더한다:

```java
     *
     * <p><b>동기화 본체는 따로 돈다.</b> {@code work} 는 동기화를 거는 데서 끝나고 관측도 그때 닫힌다. 본체는 {@code SyncJobs} 가
     * 이 Reactor Context 를 이어받아 돌리므로 본체 로그에도 같은 traceId 가 붙는다.
```

`app-ldap/src/main/resources/application.yml` 의 `sync:` 아래 `purge-cron` 다음 줄에 더한다:

```yaml
  # 동기화·재적재 한 번의 전체 기한. 넘으면 남은 일을 멈추고 FAILED("기한 초과 — 30분")로 기록한 뒤 가드를 푼다
  job-timeout: 30m
```

- [ ] **Step 4: E2E·규모 테스트 호출부를 고친다**

모든 파일에 import `dev.starryeye.organization.admin.fixture.SyncJobClient` 를 더하고, 쓰지 않게 된 import(`Duration` 등)를 정리한다.

`LdapSyncEndToEndTest`:
- import `dev.starryeye.organization.authz.OpenFgaProperties` 를 더하고 필드 `@Autowired OpenFgaProperties openFgaProperties;` 를 더한다.
- `check` 아래에 도우미를 더한다:

```java
    /** 이름으로 새로 찾은 장부 번호 — 이 앱이 캐시한 번호가 아니라 OpenFGA store 목록에서 찾는다. 같은 이름이 둘이면 오류다. */
    private String 장부_번호() {
        return new StoreBootstrapper(openFgaProperties).findExistingStore().block(Duration.ofSeconds(10));
    }
```

- Order 1 의 `client.post().uri("/admin/sync/full").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED");` 를
  `SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");` 로 바꾼다.
- Order 2 의 `client.post().uri("/admin/sync/full").exchange().expectStatus().isOk().expectBody()` 를 `SyncJobClient.끝까지(client, "/admin/sync/full")` 로 바꾼다(뒤의 `jsonPath` 넷은 그대로).
- Order 3·4·5 메서드(`snapshot_모드_재적재가_동작한다`, `snapshot_모드는_잔여_튜플을_남긴다`, `store_모드_재적재가_동작한다`)를 지우고 이것으로 바꾼다(`잔여튜플을_심는다` 도우미는 그대로 둔다):

```java
    @Test
    @Order(3)
    @DisplayName("재적재 후에도 롤업이 그대로 성립하고, 장부 번호는 바뀌지 않는다")
    void 재적재가_동작하고_번호는_그대로다() {
        // given
        String 재적재_전_번호 = 장부_번호();

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.trigger").isEqualTo("REBUILD");

        // then
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        assertThat(snapshots.findLatest().block().tuples()).hasSize(3);
        // 옛 store 모드는 장부를 지우고 다시 만들어 번호가 바뀌었다(점검 C2)
        assertThat(장부_번호()).isEqualTo(재적재_전_번호);
    }

    @Test
    @Order(4)
    @DisplayName("재적재는 스냅샷에 없던 찌꺼기 줄을 지우고, 있어야 할 권한은 남긴다")
    void 재적재가_찌꺼기를_지운다() {
        // given — 어긋남을 흉내낸다. 과거의 부분 실패로 남은 줄, 혹은 사람이 손으로 넣은 줄이 이런 모습이다
        잔여튜플을_심는다("user:ghost", "direct_member", "group:DEV001");
        assertThat(check("user:ghost", "member", "group:DEV001")).isTrue();

        // when
        SyncJobClient.끝까지(client, "/admin/sync/rebuild")
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.deletedCount").isEqualTo(1);

        // then — 옛 snapshot 모드는 스냅샷에 없는 줄을 지우지 못했다(설계 §14.2 의 한계가 풀렸다)
        assertThat(check("user:ghost", "member", "group:DEV001")).isFalse();
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        // 개수까지 본다. Check 만 보면 "필요한 것이 있다" 는 알아도 "필요 없는 것이 없다" 는 모른다.
        // 조직도가 요구하는 튜플은 정확히 3개다(kim→DEV002, park→DEV001, DEV002→DEV001)
        assertThat(snapshots.findLatest().block().tuples()).hasSize(3);
    }

    @Test
    @Order(5)
    @DisplayName("재적재에 mode 를 주면 400 이다 — 모드는 하나로 합쳐졌다")
    void 재적재_모드는_하나다() {
        // when, then
        client.post().uri("/admin/sync/rebuild?mode=store").exchange().expectStatus().isBadRequest();
        client.post().uri("/admin/sync/rebuild?mode=snapshot").exchange().expectStatus().isBadRequest();
    }
```

- 맨 끝 `동기화한다()` 의 본문을 `SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");` 로 바꾼다.

`TraceCorrelationTest` 의 `블로킹_격리를_넘어_traceId가_이어진다` 에서
`client.post().uri("/admin/sync/full").exchange().expectStatus().isOk();` 를 이것으로 바꾼다(주석도):

```java
        // when — 전체 동기화는 LDAP 읽기(boundedElastic) → 튜플 변환 → OpenFGA/DynamoDB 쓰기까지 탄다.
        // 본체는 요청과 떼어 돌지만(SyncJobs) 요청의 컨텍스트를 이어받으므로 같은 traceId 여야 한다
        SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");
```

규모 테스트 호출부(각각 아래 블록만 바꾼다):

| 파일 | 바꿀 것 | 바꾼 뒤 |
|---|---|---|
| `AdminQueryScaleTest` | `client.mutate().responseTimeout(Duration.ofMinutes(10)).build().post().uri("/admin/sync/full").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED");` | `SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");` |
| `DitScaleSyncTest` | `동기화한다()` 본문 | `return SyncJobClient.끝까지(client, "/admin/sync/full");` |
| `LdapDeletionGuardScaleTest` | `동기화한다(boolean force)` 본문 | `return SyncJobClient.끝까지(client, "/admin/sync/full" + (force ? "?force=true" : ""));` |
| `LdapInterruptedSyncScaleTest` | `동기화한다()` 본문 | `return SyncJobClient.끝까지(client, "/admin/sync/full");` |
| `LdapScaleScenarioTest` | `동기화한다()` 본문 | `return SyncJobClient.끝까지(client, "/admin/sync/full");` |
| `LdapPagingScaleTest` | `client.mutate()…post().uri("/admin/sync/full").exchange().expectStatus().isOk().expectBody()` (뒤의 `jsonPath` 둘은 그대로) | `SyncJobClient.끝까지(client, "/admin/sync/full")` |
| `LdapScaleSyncCostTest` | `client.mutate()…post().uri("/admin/sync/full").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("SUCCEEDED");` | `SyncJobClient.끝까지(client, "/admin/sync/full").jsonPath("$.status").isEqualTo("SUCCEEDED");` |

확인: `git grep -n 'uri("/admin/sync/full' -- app-ldap/src/test` 결과가 `AdminSyncControllerTest` 만 남아야 한다.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :app-ldap:compileTestJava` → 성공.
Run: `./gradlew :app-ldap:test --tests '*AdminSyncControllerTest' --tests '*SyncExecutionGuardTest' --tests '*InterruptedRunCleanupTest' --tests '*SyncSchedulerGuardReleaseTest'` → PASS.
Run: `./gradlew :app-ldap:test --tests '*LdapSyncEndToEndTest' --tests '*TraceCorrelationTest'` → PASS.
(규모 테스트는 컴파일만 확인한다. `TraceCorrelationTest` 가 traceId 로 실패하면 멈추고 보고한다 — 작업 로그의 MDC 가 비었는지, 요청 컨텍스트가 `SyncJobs` 까지 왔는지를 적는다.)

- [ ] **Step 6: 커밋**

```bash
git add app-ldap/build.gradle app-ldap/src/main app-ldap/src/test
git commit -m "feat: app-ldap 수동 동기화·재적재를 202 로 곧바로 답하고 따로 돌린다 — 재적재 모드 하나(mode 는 400), GET /runs/{runId}, 재시작 때 RUNNING 정리"
git push
```

---

### Task 9: README·점검 문서

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md` (표 6행)

**Interfaces:** 없음(문서).

- [ ] **Step 1: README 를 고친다**

1. "## 스냅샷이 왜 있는가" 첫 문단(29~31행)을 바꾼다:

```markdown
OpenFGA에는 지금 어떤 튜플이 있는지 물어볼 수 있는 read API가 있지만, 이 서버는 평소에 그것을 **쓰지
않는다** — 재적재의 장부 훑기(아래 "재적재·수동 동기화")만 예외다. 동기화의 diff는 "LDAP에서
방금 읽은 것"과 "직전 sync가 실제로 OpenFGA에 반영했다고 기록해 둔 것"(스냅샷)을 비교해서 계산된다.
```

2. 같은 절의 "대가로 **드리프트를 감지할 수 없다** — … 이걸 되돌리는 수단이 `rebuild`(아래 관리 API 참고)이며, `app-ldap`에만 있다." 문장의 끝을 바꾼다:

```markdown
알 방법이 없다. 이걸 되돌리는 수단이 `rebuild`(아래 관리 API 참고)다 — 재적재는 장부를 Read로 훑어 조직도가
요구하지 않는 줄을 지우므로, 스냅샷에 없는 찌꺼기까지 치운다.
```

3. 같은 절 끝 문장 "`POST /admin/sync/rebuild?mode=store` 로 복구한다." 를 "`POST /admin/sync/rebuild` 로 복구한다 — 재적재는 기준선 스냅샷을 읽지 않는다." 로 바꾼다.

4. "## 인가 모델" 끝 문단(76~79행, "이 서버 코드 어디에도 OpenFGA의 Read/Check/ListObjects 호출이 없다 …")을 바꾼다:

```markdown
OpenFGA 호출은 세 가지뿐이다. 쓰기는 Write(쓰기·지우기), 판단은 Check·BatchCheck(점 조회), 그리고
**Read(목록 읽기)는 재적재의 장부 훑기에서만** 쓴다(`RelationTupleScanner`). ListObjects는 쓰지 않는다.
`storeId`/`modelId`도 `authz-openfga` 밖의 코드는 다루지 않는다 — 설정에는 store 이름만 있고, 나머지는
런타임에 해석한다.

**store(장부) 번호는 한 번 만들어지면 바뀌지 않는다.** 재적재도 store를 지우고 다시 만들지 않는다. 다른
앱은 store를 이름으로 찾아도, 번호를 설정에 적어 두어도 된다. 빈 OpenFGA에 인스턴스 둘이 동시에 처음 떠 같은
이름 store를 둘 만들면, 둘 다 먼저 만들어진 쪽을 쓰고 늦게 만든 쪽이 자기 것을 지운다. 오래전부터 같은
이름이 둘이면 어느 쪽이 진짜인지 모르므로 멈추고 알린다.
```

5. "## 관리 API" 의 두 표를 바꾼다:

```markdown
**app-ldap**

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/full` | 전체 동기화를 건다 — 202 + 실행 기록(`runId`, `RUNNING`) |
| `POST /admin/sync/full?force=true` | 삭제 가드를 건너뛰고 건다 |
| `POST /admin/sync/rebuild` | 재적재를 건다 — LDAP을 다시 읽어 장부와 현재상태를 맞춘다(아래 "재적재·수동 동기화") |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 |
| `GET /admin/sync/runs/{runId}` | 실행 기록 하나 — 건 작업의 결과를 여기서 본다. 없으면 404 |

**app-scim**

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/rebuild?mode=tuples` | 재적재를 건다 — **현재상태(DynamoDB)가 요구하는 튜플을 전부 쓰고, 장부를 훑어 요구하지 않는 줄을 지운다.** 조직도는 건드리지 않는다 |
| `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` | 장부를 비운 뒤 **조직도까지 전부 지운다.** 되돌릴 수 없다 — 아래 경고 참고 |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 (재적재 + 하루 1회 아카이빙) |
| `GET /admin/sync/runs/{runId}` | 실행 기록 하나. 없으면 404 |
```

6. 표 뒤 헬스 문단 다음, "### app-scim 재적재를 부를 때 알아야 할 것" **앞에** 새 절을 넣는다:

```markdown
### 재적재·수동 동기화 (두 앱 공통)

**요청은 곧바로 답하고 작업은 따로 돈다.** `POST /admin/sync/rebuild`(두 앱)와 `POST /admin/sync/full`(app-ldap)은
겹치는 작업이 없으면 실행 기록을 열고 **202 Accepted**와 그 기록(`runId`, `status: RUNNING`)을 곧바로 돌려준다.
결과는 `GET /admin/sync/runs/{runId}`로 본다 — `status`가 `RUNNING`에서 `SUCCEEDED`·`PARTIAL`·`FAILED` 중 하나로
바뀐다. 작업은 앱 안에서 요청과 따로 돌아, 앞단 프록시가 연결을 끊어도 멈추지 않는다. 겹치면 곧바로 **409**다
(app-scim은 전역 락, app-ldap은 실행 가드).

**재적재는 장부를 버리지 않는다.** 순서는 이렇다.

1. 있어야 할 줄을 먼저 다 읽는다(app-ldap은 LDAP 전체, app-scim은 DynamoDB 현재상태). 여기서 실패하면 장부에
   아무것도 하지 않고 `FAILED`다.
2. 있어야 할 줄을 전부 쓴다. 이미 있는 줄은 OpenFGA가 무시한다.
3. 장부를 Read로 훑어 있어야 할 줄에 없는 것을 지운다 — 스냅샷에 없는 찌꺼기(중단으로 못 지운 줄, 직접 써 넣은
   줄)까지 지운다.
4. 새 스냅샷에는 장부에 실제로 있다고 볼 줄(쓴 줄 + 지우지 못한 줄)을 담는다. 지우지 못한 줄은 다음 동기화가 다시 지운다.

그래서 **인가 공백이 없다** — 재적재 동안에도 권한 질의는 정상으로 답하고, **장부 번호(storeId)도 바뀌지 않는다.**
전에는 store를 지우고 다시 만들어 그동안 모든 질의가 false였고 번호가 바뀌었다. 장부는 이 서버만 쓴다는 전제다 —
다른 앱이 이 장부에 직접 쓴 줄은 재적재가 지운다.

**기한이 있다.** `sync.job-timeout`(기본 30분, 두 앱)을 넘기면 남은 일을 멈추고 `FAILED`("기한 초과 — 30분")로
기록한 뒤 락·가드를 푼다. OpenFGA 쓰기 배치가 **3번 연달아** 실패하면(배치마다 재시도한 뒤에도) 남은 배치를 보내지
않고 `FAILED`로 끝낸다 — OpenFGA가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 쥐지 않는다. 드문 실패 한두
건은 지금처럼 `PARTIAL`이다. 멈춰도 이미 나간 쓰기는 되돌리지 않는다 — 다시 실행하면 맞춰진다.

**서버가 내려가면** 도는 작업을 멈추고 `FAILED`("서버 종료로 중단")로 기록한 뒤 락·가드를 푼다(최대 10초 기다린다).
강제로 죽으면(kill -9, 정전) 그 기록은 `RUNNING`으로 남는다 — app-ldap은 다음 시작 때 `FAILED`("재시작으로 중단")로
닫고, app-scim은 그대로 남지만 락은 TTL(30초) 뒤 풀려 다음 작업은 걸 수 있다.

**app-ldap 재적재는 모드가 하나다.** 옛 `mode=snapshot`·`mode=store`를 합쳤다 — 위 방식이 스냅샷에 없는 줄까지
치우면서 인가 공백도 없다. `mode`를 주면 400이다. 기준선 스냅샷이 깨졌어도 재적재는 스냅샷을 읽지 않으므로 이것으로
복구한다.
```

7. "### app-scim 재적재를 부를 때 알아야 할 것" 절에서 첫 문단("재적재가 도는 동안 SCIM 변경 요청은 503이다 …")은 두고 그 끝에 한 문장을 더한다:
   "쓰기를 막는 이유는 장부 훑기가 도중에 들어온 정당한 줄을 '있어야 할 줄에 없는 줄'로 잘못 지우지 않게 하려는 것이다."
   그 뒤의 세 문단 — "**인가 공백이 생긴다.** …", "**중간에 실패하면 권한이 없는 채로 남는다.** …", "**요청이 그동안 매달려 있다.** …" — 을 지운다.

8. "### ⚠️ `mode=wipe`는 되돌릴 수 없다" 의 "지우는 순서는 **OpenFGA 먼저, DynamoDB 나중**이다. …" 문단을 바꾼다:

```markdown
지우는 순서는 **장부 먼저, 조직도 나중**이다. 장부 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고
`FAILED`로 끝난다 — 다시 실행하면 남은 줄부터 지운다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만
살아남는다 — 지워진 사람들의 권한만 남는 셈이라 최악이다.
```

9. "### 조회 API" 의 app-scim 항목 "store를 비우고 현재상태가 요구하는 튜플을 전부 다시 쓰므로, 튜플 쪽 어긋남은 무엇이든 사라진다." 를
   "현재상태가 요구하는 튜플을 전부 쓰고 장부를 훑어 요구하지 않는 줄을 지우므로, 튜플 쪽 어긋남은 무엇이든 사라진다." 로 바꾼다.

10. "### app-scim 여러 대 띄우기" 에서
   - "무엇보다 **되돌릴 수 없는 파괴적 작업**(`resetStore()`)이라 — 무엇이 돌고 있는지 모른 채" 를 "무엇보다 장부 전체를 훑어 지우는 작업이라 — 무엇이 돌고 있는지 모른 채" 로,
   - "이미 나간 `resetStore()`나 쓰기를 무를 방법은 없지만" 을 "이미 나간 쓰기·지우기를 무를 방법은 없지만" 으로,
   - "반쯤 초기화된 저장소 위로" 를 "반쯤 맞춘 장부 위로" 로 바꾼다.

11. 확인: `grep -n "resetStore\|mode=store\|mode=snapshot\|인가 공백이 생긴다\|Read/Check/ListObjects" README.md` 결과가 없어야 한다.

- [ ] **Step 2: 점검 문서에 해결을 표시한다**

`docs/superpowers/specs/2026-09-28-full-audit.md` 의 요약 표에서 C2(73행)·C3(74행)·C7(78행)·M6(85행)·M13(92행)·M17(96행) 행 끝 칸의 설명 뒤에
` **→ 해결(2026-09-29, 슬라이드 ②-1)**` 을 붙인다(C1 행이 쓰는 모양과 같게).

- [ ] **Step 3: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md
git commit -m "docs: 재적재·수동 동기화 절 — 202 로 곧바로 답함, 장부 안에서 청소(인가 공백 없음, 번호 고정), 기한·차단기, 종료·재시작 정리. 점검 C2·C3·C7·M6·M13·M17 해결 표시"
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **한 번에 하나씩** 돌린다(백그라운드면 완료 알림을 받은 뒤 다음).
- 규모 테스트 출력에서 S18(`mode=tuples` 재적재 시간)·S18-b 와 `장부 훑기: N줄을 읽어 … (Mms)` 로그를 모아 스펙 §9 에 적는다 — 읽은 줄 수, 호출 수(= ⌈줄 수 / 100⌉), 훑기 시간, 재적재 전체 시간, `test`·`scaleTest` 소요.
- 스펙 §9 를 커밋·푸시한 뒤 PR.
