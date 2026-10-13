# 작업 견고성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 리스 갱신의 일시 오류 한 번이 작업을 멈추지 않게 하고(S13), SCIM 재적재가 GSI 지연 때문에 막 만든 직원·조직의 권한을 지우거나 남기지 않게 한다(S17).

**Architecture:**
- **S13.** `LeaseKeeper` 가 "잃었다" 를 둘로만 판단한다.
  - 갱신이 `LockUnavailableException`(토큰 조건 실패)으로 끝났다.
  - 마지막으로 갱신에 성공한 때부터 갱신 주기 두 번 안에 성공하지 못했다.
  - 그 밖의 실패는 그 한도 안에서 쉬며 다시 시도한다.
- **S17.** SCIM 재적재가 장부(OpenFGA)로 열거를 보강한다.
  - `tuples`: 지울 후보가 가리키는 대상 중 GSI 열거에서 빠진 것을 본 테이블에서 강한 일관성으로 다시 읽는다. 그다음 있어야 할 줄을 다시 정한다(`TupleReconciler` 의 새 자리).
  - `wipe`: 지운 튜플의 대상 중 아직 남은 것을 같은 방법으로 찾아 지운다.
- **문서.** S15 는 README 로 정리하고, S18·S26 은 해소됐음을 점검 문서에 표시한다.

**Tech Stack:** Java 17, Reactor(`Retry.backoff`, `Mono.timeout`, `StepVerifier.withVirtualTime`), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-13-job-robustness-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다.
  - 합친 `// given, when`·`// when, then`·`// given, when, then` 도 된다.
  - 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.**
  - 이름·주석·메시지는 한국어 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다.
  - 클래스 이름은 영어다.
  - 메서드 이름은 그 파일의 관례를 따른다. 공개 API·포트·인터페이스 메서드는 영어이고, 내부 도우미와 테스트는 한국어다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.**
  - 한 번에 하나씩 포그라운드로 돌린다.
  - 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
  - core 전체를 돌릴 때 기존 불안정 테스트 `IncrementalSyncLocalQueueTest` 의 점검 S14 가 가끔 실패한다(보낸 수 3, 기대 2). 실패하면 한 번 다시 돌리고 두 결과를 모두 보고한다. 그 테스트는 고치지 않는다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML(`<모듈>/build/test-results/test/*.xml`)에서 옮긴다. 어림하지 않는다.
- **S13 규칙(spec §3.1).**
  - 즉시 멈춤은 `LockUnavailableException` 하나뿐이다.
  - 한도는 "마지막 갱신 성공(처음에는 일을 시작한 때) + 갱신 주기 × 2" 다.
  - 한 번 시도의 한도는 갱신 주기 ÷ 2 다.
  - 다시 시도 사이 쉬는 시간은 갱신 주기 ÷ 50 에서 시작해 두 배씩, 최대 갱신 주기 ÷ 5 다. 지터는 없다. 기본 10초면 5초, 200ms~2초다.
  - 시간은 `Schedulers.parallel().now(MILLISECONDS)` 로 잰다.
- **S17 규칙(spec §4).**
  - 보강은 SCIM 재적재에만 둔다. LDAP 장부 맞추기(`LedgerAlignment`)와 `replaceWith` 는 그대로다.
  - 다시 읽기는 `findUsers`(직원)와 `findGroup`(조직)만 쓴다. 둘 다 강한 일관성이다.
  - 빠진 것이 없으면 읽기가 0이다.
  - 다시 읽어도 없는 대상의 줄은 그대로 지운다.
  - 이력은 지운 줄만 남긴다. 다시 정해 쓴 줄은 남기지 않는다.
- **그대로인 것.** 포트, 저장 형식, 설정, `SyncJobs`·`IncrementalSyncUseCase` 의 코드는 바꾸지 않는다. SCIM 쓰기 한 건의 리스 확인(`리스를_확인한다`)도 그대로다.

## Review Focus

- **한도가 길어지면 안 된다.**
  - 다시 시도가 생겨도 멈추는 시점은 "마지막 성공 + 2×주기" 를 넘지 않아야 한다. 그래야 리스 TTL(30초) 전에 멈춘다.
  - 지키는 테스트: 과제 1 `일시_오류가_한도를_넘으면_멈춘다`. 19초까지는 멈추지 않고 20초에 멈춘다.
- **남이 가져가면 곧바로 멈춰야 한다.**
  - 조건 실패를 다시 시도하면 남의 락 아래에서 계속 쓴다.
  - 지키는 테스트: 과제 1 `조건_실패는_다시_시도하지_않는다`(시도 1번).
- **평소 재적재에 읽기가 늘면 안 된다.**
  - 10만 명 규모에서 보강이 늘 돌면 재적재가 느려진다.
  - 지키는 테스트: 과제 2 `평소에는_다시_읽지_않는다`, 과제 3 `평소_wipe_는_다시_읽지_않는다`. 컨트롤러의 `scaleTest`(S18 재적재)도 본다.
- **찌꺼기 줄은 여전히 지워야 한다.**
  - 다시 읽어도 없는 대상(유령)의 줄을 살리면 재적재가 청소를 못 한다.
  - 지키는 테스트: 과제 2 `다시_읽어도_없는_대상의_줄은_지운다`.
- **빈 조직도 확인은 다시 정한 줄로 본다.**
  - GSI 가 조직도를 통째로 놓쳤는데 장부가 대상을 찾았다면, "빈 조직도" 로 멈추지 않아야 한다.
  - 반대로 보강 전의 D 로 확인하면 정당한 줄을 지운다.
  - 지키는 테스트: 과제 2 `TupleReconcilerTest.빈_조직도_확인은_다시_정한_줄로_본다`.

---

### Task 1: 리스 갱신의 다시 시도 (S13)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LeaseKeeper.java`(통째로 바꾼다)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java`
- Modify: `docs/superpowers/specs/2026-10-13-job-robustness-design.md`(§3.1 쉬는 시간 문장)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LeaseKeeperTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java`

**Interfaces:**
- Consumes: `MutationLock.renew(LockLease)`, `LockUnavailableException`, `LockObserver.leaseLost(String)`.
- Produces:
  - `LeaseKeeper` 의 생성자 `(MutationLock, Duration renewInterval, LockObserver)` 와 `keep(LockLease, Mono<T>, String)` 의 모양은 그대로다. 동작만 바뀐다.
  - testFixtures `FakeMutationLock.transientRenewFailures`(`AtomicInteger`).
    - 0보다 크면 갱신이 일시 오류(`IllegalStateException`)로 끝나고 하나 줄어든다.
    - `Integer.MAX_VALUE` 면 늘 실패한다.
    - 실패해도 `renewAttempted` 는 오른다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`FakeMutationLock.java` — `failRenew` 필드 아래에 더한다.

```java
    /**
     * 남은 일시 오류 수 — 0 보다 크면 {@link #renew} 가 스로틀·네트워크 오류를 흉내 낸 {@link IllegalStateException} 으로 끝나고 하나 준다.
     * 리스를 잃은 것(조건 실패, {@link #failRenew})과 달리 다시 시도하면 성공할 수 있는 실패다. {@link Integer#MAX_VALUE} 면 늘 실패한다.
     */
    public final AtomicInteger transientRenewFailures = new AtomicInteger();
```

`renew` 의 `renewAttempted.incrementAndGet();` 바로 아래, `if (failRenew)` 앞에 더한다.

```java
            if (transientRenewFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return Mono.error(new IllegalStateException("갱신 일시 오류(테스트) — 스로틀 흉내"));
            }
```

`Integer.MAX_VALUE` 는 줄여도 사실상 끝나지 않는다. `getAndUpdate` 는 이미 있는 `AtomicInteger` import 로 된다.

`LeaseKeeperTest.java` — 기존 테스트 `갱신이_응답하지_않으면_주기_안에_멈춘다` 를 새 규칙에 맞게 바꾼다. `@DisplayName` 과 마지막 단정만 바뀐다.

```java
    @Test
    @DisplayName("갱신이 답하지 않으면 — 반쯤 열린 연결 등 — 한 번 시도는 주기의 절반에서 끊고 다시 시도하되, 주기 두 번 안에 멈춘다")
    void 갱신이_응답하지_않으면_주기_안에_멈춘다() {
        // given
        FakeMutationLock 답없는_락 = new FakeMutationLock() {
            @Override
            public Mono<LockLease> renew(LockLease lease) {
                renewAttempted.incrementAndGet();
                return Mono.never();
            }
        };
        LockLease lease = 답없는_락.acquire(MutationLock.LockPurpose.WRITE).block();
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(답없는_락, Duration.ofSeconds(10), observer);

        // when, then — 첫 갱신은 10초에 나가 15초에 끊기고, 15.2초의 두 번째 시도도 답하지 않아 20초(시작 + 2×주기)에 잃은 것으로 본다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(20))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .hasMessage("작업 도중 락 리스를 잃었습니다")
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
        assertThat(답없는_락.renewAttempted.get()).as("한 번 시도는 주기의 절반에서 끊고 다시 시도한다").isEqualTo(2);
    }
```

같은 파일 끝(마지막 `}` 앞)에 세 테스트를 더한다. import 는 이미 있다(`StepVerifier`, `TimeoutException`, `AtomicBoolean`).

```java
    @Test
    @DisplayName("갱신이 일시 오류로 실패하면 쉬며 다시 시도하고, 성공하면 일은 계속된다")
    void 일시_오류는_다시_시도한다() {
        // given — 10초의 첫 갱신이 두 번 실패한 뒤 성공한다(10초, 10.2초 실패, 10.6초 성공). 20초 갱신은 곧바로 성공한다
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.transientRenewFailures.set(2);
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 25초 걸리는 일이 끝까지 간다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.delay(Duration.ofSeconds(25)).thenReturn("끝"), "테스트"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(25))
                .expectNext("끝")
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(상실).isEmpty();
        assertThat(lock.renewAttempted.get()).isEqualTo(4);
        assertThat(lock.renewed.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("일시 오류가 이어져 마지막 성공에서 갱신 주기 두 번이 지나면 멈춘다 — 그 전에는 멈추지 않는다")
    void 일시_오류가_한도를_넘으면_멈춘다() {
        // given — 갱신이 늘 일시 오류다
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.transientRenewFailures.set(Integer.MAX_VALUE);
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 19초까지는 일이 돈다. 20초(시작 + 2×주기)에 멈춘다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실"))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(19))
                .thenAwait(Duration.ofSeconds(1))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
        assertThat(lock.renewAttempted.get()).as("한도 안에서 여러 번 시도했다").isGreaterThan(5);
    }

    @Test
    @DisplayName("갱신이 조건 실패(남이 가져감)로 끝나면 다시 시도하지 않고 곧바로 멈춘다")
    void 조건_실패는_다시_시도하지_않는다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.failRenew = true;
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then — 10초의 첫 갱신에서 곧바로 끝난다
        StepVerifier.withVirtualTime(() -> keeper.keep(lease, Mono.<String>never(), "테스트 도중 리스 상실"))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(10))
                .expectError(LockUnavailableException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(lock.renewAttempted.get()).isEqualTo(1);
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
    }
```

`ScimRebuildRenewTest.java` 끝(마지막 `}` 앞)에 더한다. `SyncStatus` import 는 이미 있다.

```java
    @Test
    @DisplayName("갱신이 일시 오류로 몇 번 실패해도 재적재는 끝까지 간다 — 리스는 아직 남아 있다")
    void 일시_오류에도_끝까지_간다() {
        // given — 100ms 주기면 한도는 200ms, 쉬는 시간은 2ms 에서 두 배씩이다. 세 번 실패해도 한도 안에서 성공한다
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(600);
        var runs = new FakeSyncRunRepository(NOW);
        lock.transientRenewFailures.set(3);

        // when
        var run = 끝까지(재적재(writer, runs, lock, Duration.ofMillis(100)), runs);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(1);
        assertThat(lock.renewAttempted.get()).isGreaterThanOrEqualTo(lock.renewed.get() + 3);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.LeaseKeeperTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildRenewTest'`
Expected: FAIL. 실패할 테스트는 넷이다.
- `일시_오류는_다시_시도한다`: 첫 일시 오류에서 멈춘다.
- `일시_오류가_한도를_넘으면_멈춘다`: 10초에 멈춰 `expectNoEvent` 가 깨진다.
- `갱신이_응답하지_않으면_주기_안에_멈춘다`: 시도가 1번이다.
- `일시_오류에도_끝까지_간다`: FAILED 다.

`조건_실패는_다시_시도하지_않는다` 는 지금도 통과한다(회귀 고정용).

- [ ] **Step 3: 구현한다**

`LeaseKeeper.java` 를 아래로 바꾼다.

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 락 리스를 쥔 채 일을 돌린다(설계 2026-10-02 §3.1). 도는 동안 {@code renewInterval} 마다 리스를 갱신한다.
 *
 * <p><b>잃었다고 보는 것은 둘뿐이다(설계 2026-10-13 §3.1).</b>
 * <ul>
 *   <li>갱신이 {@link LockUnavailableException} 으로 끝났다 — 저장소의 토큰 조건이 깨졌다, 곧 남이 가져갔다. 곧바로 멈춘다.</li>
 *   <li>마지막으로 갱신에 성공한 때(처음에는 일을 시작한 때)부터 갱신 주기 두 번 안에 성공하지 못했다 — TTL(30초)보다 먼저 멈춘다.</li>
 * </ul>
 * 그 밖의 실패(스로틀·네트워크·SDK 오류)와 한 번 시도의 시간 초과는 잃었다고 단정하지 않는다 — 그 한도 안에서 쉬며 다시 시도한다. 한 번 시도는
 * 주기의 절반 안에 답해야 하고(매달린 연결 하나가 한도를 다 쓰지 않게), 쉬는 시간은 주기의 1/50 에서 두 배씩 늘려 주기의 1/5 까지다(기본 10초면
 * 5초, 200ms~2초). 한도는 전과 같으므로 TTL 전에 멈춘다는 안전 여유는 줄지 않는다.
 *
 * <p>{@link SyncJobs}(동기화·재적재)와 {@link IncrementalSyncUseCase}(SCIM 쓰기)가 함께 쓴다. 대부분의 SCIM 쓰기는 갱신 주기 안에 끝나
 * 갱신이 한 번도 일어나지 않는다. 시간은 Reactor 스케줄러 시계로 잰다 — 가상 시간 테스트가 그대로 된다.
 */
@Slf4j
@RequiredArgsConstructor
final class LeaseKeeper {

    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;

    /**
     * {@code work} 를 돌리며 리스를 갱신한다. 리스를 잃으면 {@code work} 를 취소하고 {@link LockUnavailableException} 으로 끝난다.
     * {@code work} 가 어떻게 끝나든 갱신도 멈춘다.
     *
     * @param 상실_사유 리스를 잃었을 때 {@link LockObserver#leaseLost} 와 로그에 남길 말
     */
    <T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유) {
        return Mono.defer(() -> {
            Sinks.One<T> 상실 = Sinks.one();
            AtomicLong 마지막_성공 = new AtomicLong(지금());
            Disposable heartbeat = Flux.interval(renewInterval, renewInterval)
                    // 갱신(다시 시도 포함)이 이어지는 동안 온 tick 은 버린다 — 한도는 마지막 성공에서 재므로 tick 을 놓쳐도 늦어지지 않는다
                    .onBackpressureDrop()
                    .concatMap(tick -> 갱신한다(lease, 마지막_성공, 상실_사유))
                    .subscribe(renewed -> {
                    }, error -> {
                        log.error("{} — 락 리스를 갱신하지 못했다. 하던 일을 멈춘다", 상실_사유, error);
                        lockObserver.leaseLost(상실_사유);
                        상실.tryEmitError(new LockUnavailableException("작업 도중 락 리스를 잃었습니다", error));
                    });
            return Mono.firstWithSignal(work, 상실.asMono())
                    .doFinally(signal -> heartbeat.dispose());
        });
    }

    /** 한 tick 의 갱신. 조건 실패면 곧바로 오류다. 그 밖의 실패는 마지막 성공 + 2×주기 까지 쉬며 다시 시도한다. */
    private Mono<LockLease> 갱신한다(LockLease lease, AtomicLong 마지막_성공, String 상실_사유) {
        return Mono.defer(() -> {
            long 남은_밀리초 = 마지막_성공.get() + 2 * renewInterval.toMillis() - 지금();
            if (남은_밀리초 <= 0) {
                return Mono.error(new TimeoutException("마지막 갱신 성공에서 갱신 주기 두 번이 지났다"));
            }
            return Mono.defer(() -> lock.renew(lease))
                    .timeout(renewInterval.dividedBy(2))
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, renewInterval.dividedBy(50))
                            .maxBackoff(renewInterval.dividedBy(5))
                            .jitter(0)
                            // 남이 가져간 것은 다시 시도하지 않는다 — 그대로 아래로 흘러 곧바로 멈춘다
                            .filter(error -> !(error instanceof LockUnavailableException))
                            .doBeforeRetry(signal -> log.warn("{} — 락 리스 갱신이 실패해 다시 시도한다({}번째 실패): {}",
                                    상실_사유, signal.totalRetries() + 1, signal.failure().toString())))
                    .timeout(Duration.ofMillis(남은_밀리초))
                    .doOnNext(renewed -> 마지막_성공.set(지금()));
        });
    }

    private static long 지금() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }
}
```

설계 문서 `docs/superpowers/specs/2026-10-13-job-robustness-design.md` §3.1 의 "짧게 쉬며 다시 갱신한다. 쉬는 시간은 200ms 에서 시작해 두 배씩 늘고, 최대 2초다." 를 아래로 바꾼다.

```markdown
  - 짧게 쉬며 다시 갱신한다. 쉬는 시간은 갱신 주기의 1/50 에서 시작해 두 배씩 늘고, 최대 주기의 1/5 다(기본 10초면 200ms~2초). 지터는 없다.
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.LeaseKeeperTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildRenewTest'`
Expected: PASS(`LeaseKeeperTest` 7개, `ScimRebuildRenewTest` 4개).

Run: `./gradlew :core:test`
Expected: PASS(모듈 전체). 리스를 쓰는 기존 테스트(`SyncJobsTest`, `IncrementalSyncLeaseTest`, `IncrementalSyncBigChangeTest`, `IncrementalSyncLockObservabilityTest`, `IncrementalSyncDriftTest`)가 그대로다. 이 테스트들의 `failRenew` 는 조건 실패라 지금처럼 곧바로 멈춘다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/LeaseKeeper.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java docs/superpowers/specs/2026-10-13-job-robustness-design.md core/src/test/java/dev/starryeye/organization/core/usecase/LeaseKeeperTest.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java
git commit -m "$(cat <<'EOF'
fix: 리스 갱신은 조건 실패(남이 가져감)만 곧바로 멈추고, 그 밖의 실패는 마지막 성공 + 2×주기 안에서 쉬며 다시 시도한다(점검 S13)

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 2: SCIM 재적재 `tuples` 가 장부로 GSI 열거를 보강한다 (S17)

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleSubjects.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/audit/AuditChanges.java`(대상 꺼내기를 도우미로)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java`(지우기 전 다시 정하기 자리)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`(`reloadTuples`, 새 도우미 `열거를_보강한다`)
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleSubjectsTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java`

**Interfaces:**
- Consumes: 과제 1 없음(독립). 기존 `DirectoryStateRepository.findUsers(Set<String>)`·`findGroup(String)`(강한 일관성), `TupleMapper.toTuples(DirectorySnapshot)`, `FakeStateRepository`(`users`·`groups`·`findUsersCalls`·`findGroupCalls`), `FakeTupleWriter`(`stored`·`written`·`deleted`).
- Produces:
  - `public final class TupleSubjects`
    - `static Optional<MemberRef> userSide(RelationTuple)`
    - `static Set<MemberRef> of(Collection<RelationTuple>)`: user 쪽과 object 쪽 모두.
  - `TupleReconciler.DesiredRevision`
    - `Mono<Set<RelationTuple>> additionalDesired(Set<RelationTuple> stale)`
    - 상수 `NONE`.
  - `TupleReconciler.reconcile(writer, scanner, desired, DesiredRevision, DeleteCheck)`. 기존 3·4인자 판은 `NONE` 으로 넘긴다.
  - 테스트 클래스 `ScimRebuildIndexLagTest` 와 그 안의 `열거가_늦은_저장소`(`FakeStateRepository` 를 잇는 중첩 클래스).
    - `숨긴_직원`·`숨긴_조직` 은 열거(`loadAll`)에서만 빠진다.
    - 과제 3 이 이 클래스에 `replaceWith` 덮어쓰기와 `wipe` 테스트를 더한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/tuple/TupleSubjectsTest.java`

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TupleSubjectsTest {

    @Test
    @DisplayName("user 쪽 대상 — user:kim 은 직원, group:TEAM 은 조직. #관계가 붙거나 다른 종류면 대상이 아니다")
    void user_쪽_대상() {
        // given, when, then
        assertThat(TupleSubjects.userSide(RelationTuple.directMember("kim", "DEV"))).contains(MemberRef.user("kim"));
        assertThat(TupleSubjects.userSide(RelationTuple.child("TEAM", "DEV"))).contains(MemberRef.group("TEAM"));
        assertThat(TupleSubjects.userSide(new RelationTuple("group:DEV#member", "viewer", "doc:1"))).isEmpty();
        assertThat(TupleSubjects.userSide(new RelationTuple("doc:1", "parent", "group:DEV"))).isEmpty();
    }

    @Test
    @DisplayName("튜플들이 가리키는 대상 전부 — user 쪽과 object 쪽을 함께")
    void 양쪽_대상() {
        // given
        List<RelationTuple> tuples = List.of(RelationTuple.directMember("kim", "DEV"), RelationTuple.child("TEAM", "HQ"));

        // when, then
        assertThat(TupleSubjects.of(tuples)).containsExactlyInAnyOrder(
                MemberRef.user("kim"), MemberRef.group("DEV"), MemberRef.group("TEAM"), MemberRef.group("HQ"));
    }
}
```

`TupleReconcilerTest.java` 끝(마지막 `}` 앞)에 더한다.

```java
    @Test
    @DisplayName("다시 정해 더한 줄 중 장부에 있는 것은 지우지 않고, 장부에 없는 것은 쓴다")
    void 더한_줄은_지우지_않고_없으면_쓴다() {
        // given — 장부에 kim(D), lee(D 밖), ghost(D 밖). 다시 정하기가 lee 와 park 을 더한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 김 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        RelationTuple 박 = RelationTuple.directMember("park", "DEV002");
        RelationTuple 유령 = RelationTuple.directMember("ghost", "DEV002");
        writer.stored.addAll(Set.of(김, 이, 유령));

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(김),
                stale -> Mono.just(Set.of(김, 이, 박)), TupleReconciler.DeleteCheck.NONE).block();

        // then
        assertThat(writer.deleted).containsExactly(유령);
        assertThat(writer.stored).containsExactlyInAnyOrder(김, 이, 박);
        assertThat(reconciliation.result().written()).contains(박);
        assertThat(reconciliation.ledger()).containsExactlyInAnyOrder(김, 이, 박);
    }

    @Test
    @DisplayName("빈 조직도 확인은 다시 정한 줄로 본다 — 처음 D 가 비었어도 더한 줄이 있으면 멈추지 않는다")
    void 빈_조직도_확인은_다시_정한_줄로_본다() {
        // given — 처음 D 는 비었고 장부에 lee 가 있다. 다시 정하기가 lee 를 더한다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleScanner scanner = new FakeTupleScanner(writer);
        RelationTuple 이 = RelationTuple.directMember("lee", "DEV002");
        writer.stored.add(이);
        TupleReconciler.DeleteCheck 빈_조직도면_멈춘다 = (desired, stale, scanned) ->
                desired.isEmpty() && !stale.isEmpty() ? Optional.of("빈 조직도") : Optional.empty();

        // when
        var reconciliation = TupleReconciler.reconcile(writer, scanner, Set.of(),
                stale -> Mono.just(Set.of(이)), 빈_조직도면_멈춘다).block();

        // then
        assertThat(reconciliation.held()).isFalse();
        assertThat(writer.deleted).isEmpty();
        assertThat(writer.stored).containsExactly(이);
    }
```

`TupleReconcilerTest.java` 에 `import reactor.core.publisher.Mono;` 가 없으면 더한다.

`core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java`

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
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
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 재적재가 GSI 열거를 장부로 보강한다(설계 2026-10-13 §4). 락 직전에 커밋돼 아직 GSI 에 없는 대상의 권한을 지우지 않는다. DynamoDB Local 은 GSI 가
 * 즉시 반영돼 지연을 재현할 수 없어(§7) 가짜 저장소로 본다.
 */
class ScimRebuildIndexLagTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 이 = MemberRef.user("lee");
    private static final MemberRef 박 = MemberRef.user("park");
    private static final MemberRef 팀 = MemberRef.group("TEAM");
    private static final MemberRef 백엔드 = MemberRef.group("DEV002");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 이_백엔드 = RelationTuple.directMember("lee", "DEV002");
    private static final RelationTuple 백엔드_개발본부 = RelationTuple.child("DEV002", "DEV001");
    private static final RelationTuple 김_팀 = RelationTuple.directMember("kim", "TEAM");
    private static final RelationTuple 박_팀 = RelationTuple.directMember("park", "TEAM");
    private static final RelationTuple 팀_개발본부 = RelationTuple.child("TEAM", "DEV001");
    private static final RelationTuple 유령_백엔드 = RelationTuple.directMember("ghost", "DEV002");

    /** GSI 지연을 흉내 낸다 — 숨긴 대상은 열거(loadAll)에서만 빠지고 강한 읽기(findUsers·findGroup)에서는 보인다. 조직 파티션은 강한 읽기라 멤버로는 보인다. */
    static class 열거가_늦은_저장소 extends FakeStateRepository {

        final Set<String> 숨긴_직원 = new LinkedHashSet<>();
        final Set<String> 숨긴_조직 = new LinkedHashSet<>();

        @Override
        public Mono<DirectorySnapshot> loadAll() {
            return super.loadAll().map(all -> new DirectorySnapshot(
                    보이는_것(all.users(), 숨긴_직원), 보이는_것(all.groups(), 숨긴_조직)));
        }

        private static <T> Map<String, T> 보이는_것(Map<String, T> all, Set<String> hidden) {
            Map<String, T> visible = new LinkedHashMap<>(all);
            visible.keySet().removeAll(hidden);
            return visible;
        }
    }

    private 열거가_늦은_저장소 state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FakeAuditLog auditLog;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new 열거가_늦은_저장소();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        auditLog = new FakeAuditLog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new ScimRebuildUseCase(state, writer, new FakeTupleScanner(writer), new FakeSnapshotRepository(),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                clock, new AuditRecorder(auditLog, AuditObserver.NOOP, clock));
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "emp-" + id, id, id, null, true);
    }

    private void 조직을_심는다(String id, MemberRef... members) {
        state.saveGroup(new DirectoryGroup(id, id, id + "-조직", Set.of(members))).block();
    }

    private SyncRun 재적재한다(ScimRebuildMode mode) {
        SyncRun started = useCase.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("tuples — 락 직전에 만들어져 GSI 에 아직 없는 직원의 권한을 지우지 않는다")
    void 막_만든_직원의_권한을_지우지_않는다() {
        // given — kim 은 오래된 직원, lee 는 GSI 에 아직 없다. 둘 다 DEV002 의 멤버고 장부에 권한이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("DEV002", 김, 이);
        조직을_심는다("DEV001", 백엔드);
        writer.stored.addAll(Set.of(김_백엔드, 이_백엔드, 백엔드_개발본부));
        state.숨긴_직원.add("lee");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).doesNotContain(이_백엔드);
        assertThat(writer.stored).contains(김_백엔드, 이_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("tuples — GSI 에 아직 없는 조직의 멤버 권한과 상위 연결을 지우지 않는다")
    void 막_만든_조직의_권한을_지우지_않는다() {
        // given — TEAM 은 GSI 에 아직 없다. DEV001 은 TEAM 을 하위 조직으로 적었고, 장부에 kim→TEAM, TEAM→DEV001 이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("TEAM", 김);
        조직을_심는다("DEV001", 팀);
        조직을_심는다("DEV002", 이);
        writer.stored.addAll(Set.of(김_팀, 팀_개발본부, 이_백엔드));
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).doesNotContain(김_팀, 팀_개발본부);
        assertThat(writer.stored).contains(김_팀, 팀_개발본부, 이_백엔드);
    }

    @Test
    @DisplayName("tuples — 다시 읽은 조직의 새 멤버 줄이 장부에 아직 없으면 써 준다")
    void 새_멤버의_줄은_써진다() {
        // given — TEAM 과 park 이 GSI 에 아직 없다. 장부에는 kim→TEAM 만 있고 park→TEAM 은 아직 없다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("park")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("TEAM", 김, 박);
        조직을_심는다("DEV002", 이);
        writer.stored.addAll(Set.of(김_팀, 이_백엔드));
        state.숨긴_조직.add("TEAM");
        state.숨긴_직원.add("park");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).contains(김_팀, 박_팀, 이_백엔드);
        assertThat(writer.written).contains(박_팀);
    }

    @Test
    @DisplayName("tuples — 다시 읽어도 없는 대상(찌꺼기)의 줄은 지운다")
    void 다시_읽어도_없는_대상의_줄은_지운다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.addAll(Set.of(김_백엔드, 유령_백엔드));

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).containsExactly(유령_백엔드);
        assertThat(state.findUsersCalls).contains(Set.of("ghost"));
    }

    @Test
    @DisplayName("tuples — 열거에서 빠진 것이 없으면 다시 읽지 않는다")
    void 평소에는_다시_읽지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.add(김_백엔드);

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.findUsersCalls).isEmpty();
        assertThat(state.findGroupCalls).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.tuple.TupleSubjectsTest' --tests 'dev.starryeye.organization.core.usecase.TupleReconcilerTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildIndexLagTest'`
Expected: FAIL. 컴파일 오류다(`TupleSubjects`, 5인자 `reconcile`·`DesiredRevision` 이 없다).

컴파일 오류를 피해 `ScimRebuildIndexLagTest` 만 먼저 보고 싶으면 다른 두 파일을 잠시 빼고 돌린다. 그러면 처음 세 테스트가 실패한다(숨긴 대상의 줄이 지워진다).

- [ ] **Step 3: 구현한다**

(1) `core/src/main/java/dev/starryeye/organization/core/tuple/TupleSubjects.java`

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 튜플이 가리키는 대상(직원·조직)을 꺼낸다(설계 2026-10-13 §4.2). user 쪽은 {@code user:kim}(직원)·{@code group:TEAM}(조직), object 쪽은
 * {@code group:DEV}(조직)다. {@code #관계} 가 붙은 user 쪽(userset)과 다른 종류는 대상이 아니다.
 */
public final class TupleSubjects {

    private static final String 직원 = RelationTuple.USER_TYPE + ":";
    private static final String 조직 = RelationTuple.GROUP_TYPE + ":";

    private TupleSubjects() {
    }

    /** user 쪽 대상 — 이 튜플로 권한을 얻는 쪽이다. */
    public static Optional<MemberRef> userSide(RelationTuple tuple) {
        return 대상(tuple.user());
    }

    /** 튜플들이 가리키는 대상 전부 — user 쪽과 object 쪽을 함께, 처음 나온 순서로. */
    public static Set<MemberRef> of(Collection<RelationTuple> tuples) {
        Set<MemberRef> subjects = new LinkedHashSet<>();
        for (RelationTuple tuple : tuples) {
            대상(tuple.user()).ifPresent(subjects::add);
            대상(tuple.object()).ifPresent(subjects::add);
        }
        return subjects;
    }

    private static Optional<MemberRef> 대상(String typedId) {
        if (typedId.contains("#")) {
            return Optional.empty();
        }
        if (typedId.startsWith(직원)) {
            return Optional.of(MemberRef.user(typedId.substring(직원.length())));
        }
        if (typedId.startsWith(조직)) {
            return Optional.of(MemberRef.group(typedId.substring(조직.length())));
        }
        return Optional.empty();
    }
}
```

(2) `AuditChanges.java` — `tuples`·`revoked` 의 `튜플의_대상(tuple)` 을 `TupleSubjects.userSide(tuple)` 로 바꾼다. private 메서드 `튜플의_대상` 은 지운다. `import dev.starryeye.organization.core.tuple.TupleSubjects;` 를 더한다. 쓰지 않게 된 `Optional` import 는 지운다(다른 곳에서 쓰지 않으면).

(3) `TupleReconciler.java`

`DeleteCheck` 인터페이스 아래에 더한다.

```java
    /**
     * 훑은 뒤·지우기 전에 있어야 할 줄을 다시 정한다(설계 2026-10-13 §4.3). 지울 후보를 보고 있어야 할 줄에 더할 것을 준다 — 없으면 빈 집합.
     * 더한 줄 중 장부에 있는 것(지울 후보에 든 것)은 지우지 않고, 장부에 없는 것은 쓴다. SCIM 재적재가 GSI 열거에서 빠진 대상을 다시 읽어 쓴다.
     */
    @FunctionalInterface
    interface DesiredRevision {

        Mono<Set<RelationTuple>> additionalDesired(Set<RelationTuple> stale);

        DesiredRevision NONE = stale -> Mono.just(Set.of());
    }
```

`reconcile` 두 판을 아래로 바꾸고, 5인자 판을 더한다.

```java
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired) {
        return reconcile(writer, scanner, desired, DesiredRevision.NONE, DeleteCheck.NONE);
    }

    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DeleteCheck check) {
        return reconcile(writer, scanner, desired, DesiredRevision.NONE, check);
    }

    /**
     * D 를 쓰고, 장부를 훑어 지울 후보를 가르고, 지우기 전에 {@code revision} 으로 D 를 다시 정한 뒤, {@code check} 를 거쳐 지운다. {@code check} 는
     * 다시 정한 D 와 남은 후보를 본다.
     */
    static Mono<Reconciliation> reconcile(RelationTupleWriter writer, RelationTupleScanner scanner,
                                          Set<RelationTuple> desired, DesiredRevision revision, DeleteCheck check) {
        return Mono.defer(() -> 쓴다(writer, desired)
                .flatMap(written -> 훑는다(scanner, desired)
                        .flatMap(scan -> revision.additionalDesired(scan.stale())
                                .flatMap(extra -> 더한다(writer, desired, written, scan, extra))))
                .flatMap(revised -> {
                    Scan scan = revised.scan();
                    Optional<String> hold = check.holdReason(revised.desired(), scan.stale(), scan.scanned());
                    if (hold.isPresent()) {
                        log.warn("장부를 훑었지만 지우지 않는다: {}", hold.get());
                        return Mono.just(Reconciliation.held(revised.written(), hold.get()));
                    }
                    return 지운다(writer, scan.stale())
                            .map(deleted -> Reconciliation.of(revised.written(), deleted, scan.present()))
                            .onErrorResume(TupleWriteAbortedException.class,
                                    stopped -> Mono.just(Reconciliation.stopped(revised.written(), stopped, scan.present())));
                }));
    }

    /** 다시 정한 결과 — 더한 줄을 넣은 D, 쓰기 결과(더 쓴 줄 포함), 지울 후보와 장부에 있다고 볼 줄. */
    private record 다시_정함(Set<RelationTuple> desired, TupleWriteResult written, Scan scan) {
    }

    /** 더할 줄 중 장부에 있는 것은 후보에서 빼 있어야 할 줄로 옮기고, 장부에 없는 것은 쓴다. 쓴 줄은 장부에 있다고 본다. */
    private static Mono<다시_정함> 더한다(RelationTupleWriter writer, Set<RelationTuple> desired, TupleWriteResult written,
                                      Scan scan, Set<RelationTuple> extra) {
        Set<RelationTuple> 새로 = new HashSet<>(extra);
        새로.removeAll(desired);
        if (새로.isEmpty()) {
            return Mono.just(new 다시_정함(desired, written, scan));
        }
        Set<RelationTuple> 장부에_있던 = new HashSet<>(새로);
        장부에_있던.retainAll(scan.stale());
        Set<RelationTuple> 쓸것 = new HashSet<>(새로);
        쓸것.removeAll(장부에_있던);
        Set<RelationTuple> 남은_후보 = new HashSet<>(scan.stale());
        남은_후보.removeAll(새로);
        Set<RelationTuple> 다시_정한_D = new HashSet<>(desired);
        다시_정한_D.addAll(새로);
        log.warn("지우기 전에 있어야 할 줄 {}개를 더했다 — 장부에 있던 {}줄은 지우지 않고 {}줄은 새로 쓴다",
                새로.size(), 장부에_있던.size(), 쓸것.size());
        return 쓴다(writer, 쓸것).map(더_쓴 -> {
            Set<RelationTuple> present = new HashSet<>(scan.present());
            present.addAll(장부에_있던);
            present.addAll(더_쓴.written());
            return new 다시_정함(다시_정한_D, 합친다(written, 더_쓴), new Scan(남은_후보, present, scan.scanned()));
        });
    }

    /** 처음 쓰기와 더 쓴 결과를 합친다 — 지운 것은 아직 없다. */
    private static TupleWriteResult 합친다(TupleWriteResult first, TupleWriteResult more) {
        Set<RelationTuple> written = new HashSet<>(first.written());
        written.addAll(more.written());
        List<TupleFailure> failures = new ArrayList<>(first.failures());
        failures.addAll(more.failures());
        return new TupleWriteResult(written, first.deleted(), failures);
    }
```

클래스 자바독의 "지우기 전에 확인한다" 문단 아래에 한 문단을 더한다.

```java
 *
 * <p><b>지우기 전에 다시 정할 수 있다(설계 2026-10-13 §4.3).</b> {@link DesiredRevision} 이 더한 줄은 지우지 않고, 장부에 없으면 쓴다 — SCIM 재적재가
 * GSI 열거에서 빠진 대상을 본 테이블로 다시 읽어 쓴다. LDAP 장부 맞추기는 쓰지 않는다.
```

(이미 import 된 `ArrayList`·`List`·`HashSet`·`TupleFailure`·`TupleWriteResult` 를 쓴다. 새 import 는 없다.)

(4) `ScimRebuildUseCase.java`

import 를 더한다(이미 있는 것은 넣지 않는다).

```java
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.tuple.TupleSubjects;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
```

클래스 자바독의 "보류 목록도 맞춘다" 문단 아래에 더한다.

```java
 *
 * <p><b>GSI 열거를 장부로 보강한다(설계 2026-10-13 §4).</b> 직원·조직 아이디는 GSI 로 훑는다(최종 일관성). 락 직전에 커밋된 쓰기가 아직 GSI 에 없으면
 * 그 대상의 권한이 "있어야 할 줄에 없는 줄" 로 지워진다. 그래서 지우기 전에, 지울 후보가 가리키는 대상 중 열거에서 빠진 것을 본 테이블에서 강한 일관성으로
 * 다시 읽어 조직도에 넣는다. {@code WIPE} 는 지운 튜플의 대상 중 남은 것을 같은 방법으로 찾아 지운다. 빠진 것이 없으면 읽기가 없다.
```

`TUPLES` 절에 상수를 더한다(`빈_조직도면_멈춘다` 아래).

```java
    /** 열거에서 빠진 조직을 다시 읽을 때의 동시성. 평소에는 0개다. */
    private static final int 보강_동시성 = 8;
```

`reloadTuples` 를 바꾼다.

```java
    /**
     * 장부를 맞춘 뒤 보류 목록을 버린 연결로 바꾼다 — 락 안이다. 멈춘 경우(빈 조직도)는 목록을 두고 간다. 지우기 전에 GSI 열거를 장부로 보강하고
     * (설계 2026-10-13 §4.3), 보류 목록은 보강한 조직도로 정한다.
     */
    private Mono<Mono<SyncOutcome>> reloadTuples(AuditCause cause) {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
            // 보강한 조직도의 결과 — 보류 목록은 이것으로 정한다. 보강할 것이 없으면 처음 결과 그대로다
            AtomicReference<TupleMappingResult> 최종 = new AtomicReference<>(mapping);
            TupleReconciler.DesiredRevision 보강 = stale -> 열거를_보강한다(directory, stale).map(보강된 -> {
                if (보강된 == directory) {
                    return Set.<RelationTuple>of();
                }
                TupleMappingResult 다시 = TupleMapper.toTuples(보강된);
                최종.set(다시);
                return 다시.tuples();
            });

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 보강, 빈_조직도면_멈춘다)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(Mono.just(SyncOutcome.failed(reconciliation.heldReason())))
                            // 재적재는 전체를 정하는 연산이라 보류 목록도 처음부터 다시 정한다(설계 2026-10-03 §4.6).
                            // 이력의 시각은 락 안의 일이 끝난 이때 잡는다 — 쓰는 것은 반납한 뒤다(설계 2026-10-10 §4.4)
                            : state.replaceCutEdges(최종.get().cutEdges())
                                    .then(Mono.fromSupplier(() -> commitTuples(reconciliation, cause, audit.now()))));
        });
    }

    /**
     * 지울 후보가 가리키는 대상 중 GSI 열거에서 빠진 것을 본 테이블에서 강한 일관성으로 다시 읽어 조직도에 넣는다(설계 2026-10-13 §4.3).
     * 다시 읽은 조직의 멤버 중 조직도에 없는 직원도 함께 읽는다 — 장부에 아직 줄이 없는 새 멤버다. 빠진 것이 없거나 다시 읽어도 없으면 받은 조직도를
     * 그대로 준다(같은 객체) — 평소에는 읽기가 없다.
     */
    private Mono<DirectorySnapshot> 열거를_보강한다(DirectorySnapshot directory, Set<RelationTuple> stale) {
        Set<String> 빠진_직원 = new LinkedHashSet<>();
        Set<String> 빠진_조직 = new LinkedHashSet<>();
        for (MemberRef subject : TupleSubjects.of(stale)) {
            if (subject.type() == MemberType.USER && !directory.users().containsKey(subject.id())) {
                빠진_직원.add(subject.id());
            } else if (subject.type() == MemberType.GROUP && !directory.groups().containsKey(subject.id())) {
                빠진_조직.add(subject.id());
            }
        }
        if (빠진_직원.isEmpty() && 빠진_조직.isEmpty()) {
            return Mono.just(directory);
        }
        return Flux.fromIterable(빠진_조직)
                .flatMap(state::findGroup, 보강_동시성)
                .collectMap(DirectoryGroup::id)
                .flatMap(groups -> {
                    Set<String> 읽을_직원 = new LinkedHashSet<>(빠진_직원);
                    groups.values().forEach(group -> group.members().stream()
                            .filter(member -> member.type() == MemberType.USER
                                    && !directory.users().containsKey(member.id()))
                            .forEach(member -> 읽을_직원.add(member.id())));
                    Mono<Map<String, DirectoryUser>> users = 읽을_직원.isEmpty()
                            ? Mono.just(Map.of())
                            : state.findUsers(읽을_직원).collectMap(DirectoryUser::id);
                    return users.map(found -> {
                        if (groups.isEmpty() && found.isEmpty()) {
                            return directory;
                        }
                        log.warn("GSI 열거에서 빠진 대상을 본 테이블에서 찾아 조직도에 넣었다 — 직원 {}명, 조직 {}개. 락 직전에 커밋된 쓰기다",
                                found.size(), groups.size());
                        Map<String, DirectoryUser> 직원들 = new LinkedHashMap<>(directory.users());
                        직원들.putAll(found);
                        Map<String, DirectoryGroup> 조직들 = new LinkedHashMap<>(directory.groups());
                        조직들.putAll(groups);
                        return new DirectorySnapshot(직원들, 조직들);
                    });
                });
    }
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.tuple.TupleSubjectsTest' --tests 'dev.starryeye.organization.core.usecase.TupleReconcilerTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildIndexLagTest' --tests 'dev.starryeye.organization.core.audit.AuditChangesTest'`
Expected: PASS. 새 테스트는 9개다(`TupleSubjectsTest` 2, `TupleReconcilerTest` 2, `ScimRebuildIndexLagTest` 5). `AuditChangesTest` 는 그대로다.

Run: `./gradlew :core:test`
Expected: PASS(모듈 전체). `ScimRebuildUseCaseTest`·`ScimRebuildAuditTest`·`RebuildUseCaseTest`·`LdapSyncAuditTest` 가 그대로다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/tuple/TupleSubjects.java core/src/main/java/dev/starryeye/organization/core/audit/AuditChanges.java core/src/main/java/dev/starryeye/organization/core/usecase/TupleReconciler.java core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java core/src/test/java/dev/starryeye/organization/core/tuple/TupleSubjectsTest.java core/src/test/java/dev/starryeye/organization/core/usecase/TupleReconcilerTest.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java
git commit -m "$(cat <<'EOF'
fix: SCIM 재적재 tuples 가 지울 후보의 대상 중 GSI 열거에서 빠진 것을 본 테이블로 강하게 다시 읽어, 락 직전에 만든 직원·조직의 권한을 지우지 않는다(점검 S17)

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 3: SCIM 재적재 `wipe` 가 남은 대상을 지우고, 문서를 정리한다 (S17·S15·S18·S26)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`(`wipe`, 새 도우미 `남은_대상을_지운다`)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java`(`replaceWith` 덮어쓰기, `wipe` 테스트 둘)
- Modify: `README.md`(락 설정 표 두 줄, SCIM 재적재 절 한 문단)
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md`(S13·S15·S17·S18·S26 줄 끝)

**Interfaces:**
- Consumes:
  - 과제 2: `TupleSubjects.of(Collection<RelationTuple>)`, `ScimRebuildIndexLagTest.열거가_늦은_저장소`(`숨긴_직원`·`숨긴_조직`), 테스트 필드 `state`·`writer`·`auditLog`, 도우미 `직원(id)`·`조직을_심는다(id, members...)`·`재적재한다(mode)`.
  - 기존: `DirectoryStateRepository.findUsers`·`findGroup`·`deleteGroup(id, members)`·`deleteUser(id)`, `DirectoryChanges`·`UserDiff`·`GroupDiff`, `FakeAuditLog.lastOf(MemberRef)`.
- Produces: 없음(마지막 과제).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimRebuildIndexLagTest.java`

(1) `열거가_늦은_저장소` 안(`loadAll` 아래)에 `replaceWith` 덮어쓰기를 더한다. `import dev.starryeye.organization.core.model.DirectoryChanges;` 를 더한다.

```java
        /** 실제 저장소처럼 숨긴 대상은 열거되지 않아 비교·삭제에서 빠지고, 끝난 뒤에도 남는다. */
        @Override
        public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot) {
            Map<String, DirectoryUser> 숨은_직원 = new LinkedHashMap<>(users);
            숨은_직원.keySet().retainAll(숨긴_직원);
            Map<String, DirectoryGroup> 숨은_조직 = new LinkedHashMap<>(groups);
            숨은_조직.keySet().retainAll(숨긴_조직);
            users.keySet().removeAll(숨긴_직원);
            groups.keySet().removeAll(숨긴_조직);
            DirectoryChanges changes = super.replaceWith(snapshot).block();
            users.putAll(숨은_직원);
            groups.putAll(숨은_조직);
            return Mono.just(changes);
        }
```

(2) 상수를 더한다(`유령_백엔드` 아래).

```java
    private static final RelationTuple 이_팀 = RelationTuple.directMember("lee", "TEAM");
```

(3) 클래스 끝(마지막 `}` 앞)에 테스트 둘을 더한다.

```java
    @Test
    @DisplayName("wipe — GSI 열거에서 빠진 직원·조직도 장부로 찾아 지우고, 이력에 삭제로 남긴다")
    void wipe_는_빠진_대상도_지운다() {
        // given — lee 와 TEAM 은 락 직전에 만들어져 GSI 에 없다. 장부에는 둘의 권한이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("DEV002", 김);
        조직을_심는다("TEAM", 이);
        writer.stored.addAll(Set.of(김_백엔드, 이_팀));
        state.숨긴_직원.add("lee");
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();
        assertThat(auditLog.lastOf(이).deleted()).isTrue();
        assertThat(auditLog.lastOf(팀).deleted()).isTrue();
        assertThat(auditLog.lastOf(팀).membersRemoved()).containsExactly(이);
    }

    @Test
    @DisplayName("wipe — 열거에서 빠진 것이 없으면 다시 읽지 않는다")
    void 평소_wipe_는_다시_읽지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.add(김_백엔드);

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.users).isEmpty();
        assertThat(state.findUsersCalls).isEmpty();
        assertThat(state.findGroupCalls).isEmpty();
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.ScimRebuildIndexLagTest'`
Expected: FAIL. `wipe_는_빠진_대상도_지운다` 가 실패한다. lee 와 TEAM 이 남는다(`state.users`·`state.groups` 가 비지 않는다). `평소_wipe_는_다시_읽지_않는다` 는 지금도 통과한다(회귀 고정용).

- [ ] **Step 3: 구현한다**

`ScimRebuildUseCase.java`

import 를 더한다(이미 있는 것은 넣지 않는다).

```java
import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.GroupHeader;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
```

`wipe` 의 `state.replaceWith(DirectorySnapshot.empty())` 바로 다음 줄에 남은 대상 지우기를 끼운다. 나머지 줄은 그대로다.

```java
            return state.replaceWith(DirectorySnapshot.empty())
                    // GSI 열거에서 빠져 남은 대상을 장부로 찾아 지운다(설계 2026-10-13 §4.4)
                    .flatMap(changes -> 남은_대상을_지운다(changes, result.deleted()))
                    .flatMap(changes -> state.replaceCutEdges(Set.of()).thenReturn(changes))
```

`wipe` 아래에 도우미를 더한다.

```java
    /**
     * 조직도를 비운 뒤 남은 것을 지운다(설계 2026-10-13 §4.4). 지운 튜플의 대상 중 방금 지운 직원·조직에 없는 것을 본 테이블에서 강한 일관성으로
     * 다시 확인해, 아직 있으면 조직 먼저(그때의 멤버로), 그다음 직원 순으로 지운다 — GSI 열거에서 빠진, 락 직전에 만든 대상이다. 지운 것을 비교 결과에
     * 더해 이력에 삭제로 남긴다. 확인할 것이 없으면 받은 결과를 그대로 준다 — 평소에는 읽기가 없다.
     */
    private Mono<DirectoryChanges> 남은_대상을_지운다(DirectoryChanges changes, Set<RelationTuple> deleted) {
        Set<String> 지운_직원 = changes.users().stream()
                .map(DirectoryChanges.UserDiff::id)
                .collect(Collectors.toSet());
        Set<String> 지운_조직 = changes.groups().stream()
                .map(DirectoryChanges.GroupDiff::id)
                .collect(Collectors.toSet());
        Set<String> 볼_직원 = new LinkedHashSet<>();
        Set<String> 볼_조직 = new LinkedHashSet<>();
        for (MemberRef subject : TupleSubjects.of(deleted)) {
            if (subject.type() == MemberType.USER && !지운_직원.contains(subject.id())) {
                볼_직원.add(subject.id());
            } else if (subject.type() == MemberType.GROUP && !지운_조직.contains(subject.id())) {
                볼_조직.add(subject.id());
            }
        }
        if (볼_직원.isEmpty() && 볼_조직.isEmpty()) {
            return Mono.just(changes);
        }
        Mono<List<DirectoryChanges.GroupDiff>> 조직 = Flux.fromIterable(볼_조직)
                .concatMap(state::findGroup)
                .concatMap(group -> state.deleteGroup(group.id(), group.members())
                        .thenReturn(new DirectoryChanges.GroupDiff(
                                new GroupHeader(group.id(), group.externalId(), group.displayName()),
                                null, Set.of(), group.members())))
                .collectList();
        Mono<List<DirectoryChanges.UserDiff>> 직원 = Mono.defer(() -> 볼_직원.isEmpty()
                ? Mono.just(List.<DirectoryChanges.UserDiff>of())
                : state.findUsers(볼_직원)
                        .concatMap(user -> state.deleteUser(user.id())
                                .thenReturn(new DirectoryChanges.UserDiff(user, null)))
                        .collectList());
        return 조직.flatMap(groups -> 직원.map(users -> {
            if (groups.isEmpty() && users.isEmpty()) {
                return changes;
            }
            log.warn("GSI 열거에서 빠져 남아 있던 대상을 지웠다 — 직원 {}명, 조직 {}개. 락 직전에 만든 대상이다",
                    users.size(), groups.size());
            List<DirectoryChanges.UserDiff> 모든_직원 = new ArrayList<>(changes.users());
            모든_직원.addAll(users);
            List<DirectoryChanges.GroupDiff> 모든_조직 = new ArrayList<>(changes.groups());
            모든_조직.addAll(groups);
            return new DirectoryChanges(모든_직원, 모든_조직);
        }));
    }
```

`README.md`

(1) 락 설정 표의 `dynamodb.lock-ttl` 줄 끝 `두 경로 모두에 적용된다 |` 를 아래로 바꾼다.

```markdown
두 경로 모두에 적용된다. 만료는 각 서버의 시계로 판단하므로 서버 시계를 NTP 로 맞춘다 — 30초 넘게 어긋나면 남의 락을 가져갈 수 있다 |
```

(2) 같은 표의 `dynamodb.lock-renew-interval` 줄 끝 `TTL보다 충분히 짧아야 한다 |` 를 아래로 바꾼다.

```markdown
TTL보다 충분히 짧아야 한다. 갱신이 일시 오류(스로틀·네트워크)로 실패하면 짧게 쉬며 다시 시도하고, 마지막 성공에서 주기 두 번 안에 성공하지 못할 때만 멈춘다. 남이 가져간 것이 확인되면(토큰 조건 실패) 곧바로 멈춘다 |
```

(3) `### app-scim 재적재를 부를 때 알아야 할 것` 절의 "**튜플 스냅샷은 락을 푼 뒤 저장한다.**" 문단 아래(빈 줄 하나 띄우고, `### ⚠️ \`mode=wipe\`는 되돌릴 수 없다` 앞)에 더한다.

```markdown
**막 만든 직원·조직의 권한을 지우지 않는다.** 재적재는 직원·조직을 GSI(최종 일관성)로 훑는다. 락 직전에 만든 대상이 아직 GSI 에 없어도
장부(OpenFGA)에 그 대상의 줄이 있으므로, 지우기 전에 본 테이블에서 강한 일관성으로 다시 읽어 조직도에 넣는다. `mode=wipe` 도 같은 방법으로
남은 대상을 찾아 지운다. 장부에 줄이 없는 새 대상(비활성이거나 어느 조직에도 없는 직원, 멤버 없는 조직)은 권한이 없어 빠져도 권한은 바뀌지
않는다 — `wipe` 뒤에 남을 수 있지만, IdP 가 다시 보낼 때 같은 `userName`·`externalId` 로 찾아 이어 붙인다.
```

`docs/superpowers/specs/2026-09-28-full-audit.md` — 사소 표의 다섯 줄 끝 칸을 바꾼다(줄 머리 `| S13 |`·`| S15 |`·`| S17 |`·`| S18 |`·`| S26 |` 로 찾는다).

- S13 줄 끝 `| R-11 |` → `| R-11 **→ 해결(2026-10-13, 작업 견고성 — 조건 실패만 곧바로 멈추고 나머지는 한도 안에서 다시 시도)** |`
- S15 줄 끝 `| R-13 |` → `| R-13 **→ 문서로 정리(2026-10-13 — README \`dynamodb.lock-ttl\`: 서버 시계를 NTP 로 맞춘다)** |`
- S17 줄 끝 `| R-15 |` → `| R-15 **→ 해결(2026-10-13, 작업 견고성 — 장부에 나온 대상을 본 테이블로 강하게 다시 읽어 열거를 보강. 실제로는 tuples 가 그 대상의 권한을 지웠다)** |`
- S18 줄 끝 `| cost-batch D2 |` → `| cost-batch D2 **→ 이미 해소(기준선 포인터 GetItem·스냅샷 Query 가 강한 일관성 — 2026-10-13 확인)** |`
- S26 줄 끝 `| cost-batch P13 |` → `| cost-batch P13 **→ 이미 해소(①: 스냅샷이 TTL 을 쓰지 않아 정리 잡이 유일한 삭제 경로이고 주석도 그렇게 말한다 — 2026-10-13 확인)** |`

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.usecase.ScimRebuildIndexLagTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildUseCaseTest' --tests 'dev.starryeye.organization.core.usecase.ScimRebuildAuditTest'`
Expected: PASS. `ScimRebuildIndexLagTest` 는 7개다. 기존 `wipe` 테스트들이 그대로다.

Run: `./gradlew :core:test`
Expected: PASS(모듈 전체).

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildIndexLagTest.java README.md docs/superpowers/specs/2026-09-28-full-audit.md
git commit -m "$(cat <<'EOF'
fix: SCIM 재적재 wipe 가 지운 튜플의 대상 중 GSI 열거에서 빠져 남은 직원·조직을 강하게 다시 읽어 지우고 이력에 남긴다(점검 S17) / README·점검 문서 S13·S15·S17·S18·S26 정리

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```
