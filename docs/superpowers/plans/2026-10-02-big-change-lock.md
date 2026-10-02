# 큰 변경도 락을 지키며 끝까지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 ③-1 — 10만 명 규모의 SCIM 큰 변경이 전역 락을 잃지 않고 끝까지 끝나게 하고(리스 갱신·커밋 직전 확인·끊겨도 끝까지·같은 토큰 재획득), 조직 삭제를
직원 읽기·Check 없이 싸게 만들고, OpenFGA 요청을 동시에 보내고, DynamoDB 묶음 재시도에 상한을 두고, SCIM 재적재 스냅샷 저장을 락 밖으로 옮긴다.

**Architecture:** core 에 "리스를 쥔 채 일 돌리기" 부품(`LeaseKeeper`)을 두어 `SyncJobs`·`IncrementalSyncUseCase` 가 함께 쓴다. SCIM 쓰기(`withLock`)는
요청과 떼어 돌리고, OpenFGA 쓰기 직전·DynamoDB 커밋 직전에 늘 리스를 확인한다(`반영하고_커밋한다`). 조직 삭제는 계산 없이 "그 조직을 언급하는 줄"을
"없으면 무시"로 지우는 전용 경로, 멤버 대량 빼기는 빠지는 쪽만 Check 를 건너뛴다. storage 는 묶음 요청 부품(`BatchRequests`)과 META 를 맨 마지막에
지우는 `deleteGroup(groupId, members)` 를 갖는다. authz 는 Check·Write 묶음을 `openfga.request-concurrency` 만큼 동시에 보낸다(지우기 → 쓰기 순서 유지).
`SyncJobs#startLockedThen` 이 "반납 뒤 할 일"을 받아 SCIM 재적재가 스냅샷을 락 밖에서 저장한다.

**Tech Stack:** Java 17, Spring Boot 3.5 / WebFlux, Reactor, AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, Awaitility, Mockito,
Testcontainers(DynamoDB Local, OpenFGA v1.10.2), Lombok.

**Spec:** `docs/superpowers/specs/2026-10-02-big-change-lock-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then`), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고**(컴파일 실패 포함) 고친다.
- 리스 갱신 주기 `dynamodb.lock-renew-interval`(기본 10초), 락 TTL `dynamodb.lock-ttl`(30초), 획득 대기 `dynamodb.lock-acquire-timeout`(3초) — 값은 바꾸지 않는다.
- 리스 상실 메시지: 하트비트가 잃으면 `LockUnavailableException("작업 도중 락 리스를 잃었습니다", cause)`. `leaseLost` 사유: SCIM 쓰기 `쓰기 도중 리스 상실`, 동기화·재적재
  `작업 도중 리스 상실`, 확인 실패 `쓰기 직전 리스 재확인 실패`·`커밋 직전 리스 재확인 실패`, 반납 `반납 실패`.
- DynamoDB 묶음 재시도: **최대 5번, 100ms 부터 두 배씩**, 다 쓰면 남은 수를 담은 `IllegalStateException`. BatchGet 키 100개, BatchWrite 요청 25개(DynamoDB 한도).
- 새 설정 `openfga.request-concurrency`(기본 4). Check·Write 묶음을 이만큼 동시에 보낸다. 지우기 묶음을 다 보낸 뒤 쓰기 묶음을 보낸다.
- 조직 삭제의 DynamoDB 순서: 멤버 줄(상위 조직들의 "이 조직" 줄, 이 조직의 멤버 줄) → 소속 줄(이 조직의 소속 줄, 멤버들의 소속 줄) → **META 맨 마지막**.
- SCIM 재적재 스냅샷 저장 실패 사유 접두: `장부는 맞췄다 — 스냅샷 저장 실패: `.
- 운영 배포 전이라 이관·하위호환을 만들지 않는다.
- 서브에이전트는 과제에 적힌 **모듈 테스트나 테스트 클래스만** 돌린다. 앱 모듈 전체 `test`·`scaleTest` 는 돌리지 않는다(컨트롤러가 돌린다). Gradle 은 한 번에 하나, 포그라운드.
  파일 시스템 전체를 훑는 검색(`find /`)을 하지 않는다. `git stash` 를 쓰지 않는다.
- 커밋마다 `git push`(브랜치 `audit-big-change-lock`, 업스트림 설정돼 있음). 커밋 메시지는 제목 → 빈 줄 → `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  (heredoc `git commit -F - <<'EOF' … EOF`). 경로를 지정해 스테이징한다.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **IdP 가 큰 변경 도중 연결을 끊는다** — 변경은 커밋까지 끝나고 그다음 락을 반납한다. 취소가 락을 일찍 풀어 다음 요청이 반쯤 반영된 장부를 보지 않는다
   (Task 2 `요청이_끊겨도_커밋까지_하고_반납한다`).
2. **큰 조직 삭제가 중간에 멈춘다(리스 상실·DynamoDB 오류)** — META 가 남아 IdP 의 DELETE 재시도가 남은 줄을 마저 지운다(Task 5 `중간에_멈춰도_다시_지우면_끝난다`).
3. **10만 명 본부 밑의 작은 팀을 지운다** — 상위 조직 파티션을 읽지 않는다(Task 6 `상위_조직은_아이디만_읽는다`).
4. **OpenFGA 가 죽어 모든 묶음이 실패하는데 동시에 4개씩 보낸다** — 차단기는 보낸 순서대로 세어 세 묶음째에서 멈추고, 이미 나간 묶음은 보내지 않은 것으로 센다
   (Task 7 `동시에_보내도_순서대로_센다`).
5. **SCIM 재적재 스냅샷 저장이 락 반납 뒤에 실패한다** — FAILED 이고 사유에 "장부는 맞췄다"가 남으며, 락은 이미 풀려 있다(Task 8 `반납_뒤_저장_실패는_FAILED`).

---

### Task 1: `LeaseKeeper` — 리스를 쥔 채 일 돌리기, `SyncJobs` 가 먼저 쓴다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/LeaseKeeper.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LeaseKeeperTest.java`

**Interfaces:**
- Produces: `final class LeaseKeeper` (패키지 전용) — `LeaseKeeper(MutationLock lock, Duration renewInterval, LockObserver lockObserver)`,
  `<T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유)`. 갱신 실패 → `LockUnavailableException("작업 도중 락 리스를 잃었습니다", cause)`, `lockObserver.leaseLost(상실_사유)`.
- `SyncJobs` 공개 API 는 바뀌지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/LeaseKeeperTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스를 쥔 채 일을 돌린다 (설계 2026-10-02 §3.1).
 */
class LeaseKeeperTest {

    private FakeMutationLock lock;
    private final List<String> 상실 = new CopyOnWriteArrayList<>();
    private LockObserver observer;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        observer = new LockObserver() {
            @Override
            public void acquireFinished(Duration waited, boolean contended) {
            }

            @Override
            public void leaseLost(String reason) {
                상실.add(reason);
            }
        };
    }

    @Test
    @DisplayName("일이 갱신 주기보다 오래 걸리면 주기마다 리스를 갱신하고, 일이 끝나면 갱신도 멈춘다")
    void 주기마다_갱신하고_끝나면_멈춘다() throws InterruptedException {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofMillis(50), observer);

        // when
        String 결과 = keeper.keep(lease, Mono.delay(Duration.ofMillis(300)).thenReturn("끝"), "테스트").block();

        // then
        assertThat(결과).isEqualTo("끝");
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(3);
        int 끝났을때 = lock.renewAttempted.get();
        Thread.sleep(200);
        assertThat(lock.renewAttempted.get()).as("일이 끝난 뒤에는 갱신하지 않는다").isEqualTo(끝났을때);
        assertThat(상실).isEmpty();
    }

    @Test
    @DisplayName("갱신이 실패하면 하던 일을 취소하고 '리스를 잃었다'로 끝난다")
    void 갱신이_실패하면_멈춘다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.failRenew = true;
        AtomicBoolean 취소됨 = new AtomicBoolean();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofMillis(50), observer);

        // when, then
        assertThatThrownBy(() -> keeper.keep(lease, Mono.<String>never().doOnCancel(() -> 취소됨.set(true)),
                        "테스트 도중 리스 상실").block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class)
                .hasMessageContaining("리스");
        assertThat(취소됨).isTrue();
        assertThat(상실).containsExactly("테스트 도중 리스 상실");
    }

    @Test
    @DisplayName("빈 결과로 끝나는 일은 그대로 빈 결과다")
    void 빈_결과는_그대로다() {
        // given
        LockLease lease = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        LeaseKeeper keeper = new LeaseKeeper(lock, Duration.ofSeconds(10), observer);

        // when, then
        assertThat(keeper.keep(lease, Mono.<String>empty(), "테스트").blockOptional()).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*LeaseKeeperTest'`
Expected: 컴파일 실패 — `LeaseKeeper` 가 없다.

- [ ] **Step 3: 구현한다**

`core/src/main/java/dev/starryeye/organization/core/usecase/LeaseKeeper.java`:

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

import java.time.Duration;

/**
 * 락 리스를 쥔 채 일을 돌린다(설계 2026-10-02 §3.1). 도는 동안 {@code renewInterval} 마다 리스를 갱신하고, 갱신이 실패하면 — 남이 가져갔거나
 * 저장소가 답하지 않으면 — 그 일을 멈춘다. 리스 TTL(30초)보다 오래 걸리는 일도 끝까지 가고, 리스를 잃은 뒤에는 더 쓰지 않는다.
 *
 * <p>{@link SyncJobs}(동기화·재적재)와 {@link IncrementalSyncUseCase}(SCIM 쓰기)가 함께 쓴다. 대부분의 SCIM 쓰기는 갱신 주기 안에 끝나
 * 갱신이 한 번도 일어나지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
final class LeaseKeeper {

    private final MutationLock lock;
    private final Duration renewInterval;
    private final LockObserver lockObserver;

    /**
     * {@code work} 를 돌리며 리스를 갱신한다. 갱신이 실패하면 {@code work} 를 취소하고 {@link LockUnavailableException} 으로 끝난다.
     * {@code work} 가 어떻게 끝나든 갱신도 멈춘다.
     *
     * @param 상실_사유 리스를 잃었을 때 {@link LockObserver#leaseLost} 와 로그에 남길 말
     */
    <T> Mono<T> keep(LockLease lease, Mono<T> work, String 상실_사유) {
        return Mono.defer(() -> {
            Sinks.One<T> 상실 = Sinks.one();
            Disposable heartbeat = Flux.interval(renewInterval, renewInterval)
                    .concatMap(tick -> lock.renew(lease))
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
}
```

`SyncJobs`:
- 필드 `private final Duration renewInterval;` 를 `private final LeaseKeeper keeper;` 로 바꾸고, 생성자에서 `this.keeper = new LeaseKeeper(lock, renewInterval, lockObserver);` 로 만든다
  (생성자 서명은 그대로).
- `startLocked` 의 `flatMap(lease -> { … })` 를 이것으로 바꾼다:

```java
                    .flatMap(lease -> start(source, trigger,
                            keeper.keep(lease, work, "작업 도중 리스 상실"),
                            run -> 남은_기록을_닫는다(source, run.runId()),
                            () -> lock.release(lease),
                            onFinished));
```

- `리스를_갱신한다` 메서드와 그 자바독을 지운다. 클래스 자바독의 "작업 동안 리스를 갱신하고, 리스를 잃으면(=남이 가져갔으면) 작업을 멈추고" 문장 뒤에
  `({@link LeaseKeeper})` 를 붙인다. 쓰지 않게 된 import(`Disposable`, `Flux` 등)를 정리한다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*LeaseKeeperTest' --tests '*SyncJobsTest' --tests '*ScimRebuild*'` → PASS.
Run: `./gradlew :core:cleanTest :core:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/LeaseKeeper.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/LeaseKeeperTest.java
git commit -F - <<'EOF'
feat: 리스를 쥔 채 일 돌리기(LeaseKeeper) — 갱신 주기마다 리스를 미루고 잃으면 멈춘다, SyncJobs 가 먼저 쓴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 2: SCIM 쓰기의 리스 — 하트비트, 커밋 직전 확인, 끊겨도 끝까지 (M8·S3)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLeaseTest.java` (신규)

**Interfaces:**
- Consumes: `LeaseKeeper`(Task 1).
- Produces: `IncrementalSyncUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleChecker checker, MutationLock lock, Duration acquireTimeout, Duration renewInterval, DriftObserver driftObserver, LockObserver lockObserver)`
  (새 정식 생성자). 기존 7인자 생성자는 갱신 주기 10초로 위임해 남긴다(테스트 열네 곳이 그대로 컴파일).
  패키지 안 헬퍼 `private Mono<IncrementalSyncResult> 반영하고_커밋한다(TupleDelta delta, LockLease lease, Function<TupleWriteResult, Mono<Void>> 커밋)` — Task 6 이 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLeaseTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * SCIM 쓰기가 락 리스를 지킨다 (설계 2026-10-02 §3, 점검 C6·M8·S3).
 */
class IncrementalSyncLeaseTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private final List<String> 상실 = new CopyOnWriteArrayList<>();
    private LockObserver observer;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        observer = new LockObserver() {
            @Override
            public void acquireFinished(Duration waited, boolean contended) {
            }

            @Override
            public void leaseLost(String reason) {
                상실.add(reason);
            }
        };
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "kim", "kim@example.com", true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of())).block();
    }

    private IncrementalSyncUseCase 유스케이스(Duration 갱신주기) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, 갱신주기,
                IncrementalSyncUseCase.DriftObserver.NOOP, observer);
    }

    private static DirectoryGroup kim이_든_DEV001() {
        return new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(MemberRef.user("kim")));
    }

    @Test
    @DisplayName("갱신 주기보다 오래 걸리는 쓰기는 도중에 리스를 갱신하고 끝까지 간다")
    void 오래_걸리는_쓰기는_리스를_갱신한다() {
        // given — Check 가 300ms 걸린다
        checker.delayBy(tuple -> Duration.ofMillis(300));

        // when
        var result = 유스케이스(Duration.ofMillis(50)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5));

        // then — 하트비트 갱신 + 쓰기 직전 확인 + 커밋 직전 확인
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(4);
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("도중에 리스 갱신이 실패하면 멈추고 503 이며, OpenFGA 에도 DynamoDB 에도 쓰지 않는다")
    void 갱신이_실패하면_멈춘다() {
        // given
        checker.delayBy(tuple -> Duration.ofMillis(300));
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofMillis(50)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class)
                .hasMessageContaining("리스");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups.get("DEV001").members()).isEmpty();
        assertThat(상실).contains("쓰기 도중 리스 상실");
    }

    @Test
    @DisplayName("바뀐 튜플이 없어도 커밋 직전에 리스를 확인한다 — 확인이 실패하면 저장하지 않는다(점검 M8)")
    void 빈_델타도_커밋_직전에_확인한다() {
        // given — 소속 조직이 없는 kim 의 메일만 바꾼다(튜플 변화 없음). 그사이 리스를 잃었다
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofSeconds(10))
                .changeUser("kim", user -> user.withEmail("new@example.com")).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(state.users.get("kim").email()).as("남이 저장했을지 모르는 값을 덮지 않는다").isEqualTo("kim@example.com");
        assertThat(상실).contains("커밋 직전 리스 재확인 실패");
    }

    @Test
    @DisplayName("OpenFGA 에 쓴 뒤 커밋 직전 확인이 실패하면 DynamoDB 에 저장하지 않는다")
    void 쓴_뒤에도_커밋_직전에_확인한다() {
        // given — 쓰기 직전 확인은 통과하고, 쓰는 사이 리스를 잃는다
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV001").members()).isEmpty();
    }

    @Test
    @DisplayName("요청이 끊겨도 커밋까지 하고 그다음 반납한다 — 반쯤 반영된 채 락이 풀리지 않는다(점검 S3)")
    void 요청이_끊겨도_커밋까지_하고_반납한다() throws InterruptedException {
        // given — OpenFGA 쓰기가 300ms 걸린다
        writer.delay = Duration.ofMillis(300);

        // when — 쓰는 도중 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then — 반납은 커밋 뒤에 일어난다
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.released.get() == 1);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("락을 잡는 도중에 끊겨도 리스가 새지 않는다 — 잡은 락으로 일을 마치고 반납한다")
    void 락을_잡는_도중_끊겨도_리스가_새지_않는다() throws InterruptedException {
        // given — 저장소에서는 이미 잡혔는데 응답이 200ms 늦게 온다
        lock = new FakeMutationLock() {
            @Override
            public reactor.core.publisher.Mono<dev.starryeye.organization.core.port.LockLease> acquire(LockPurpose purpose) {
                return super.acquire(purpose).delayElement(Duration.ofMillis(200));
            }
        };

        // when — 응답을 기다리는 사이 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.released.get() == 1);
        assertThat(lock.isHeld()).isFalse();
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }
}
```

(`LockPurpose` 는 `dev.starryeye.organization.core.port.MutationLock.LockPurpose` — import 를 더하거나 `MutationLock.LockPurpose` 로 쓴다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncLeaseTest'`
Expected: 컴파일 실패 — 8인자 생성자가 없다.

- [ ] **Step 3: 구현한다**

`IncrementalSyncUseCase`:

1. import 에 `reactor.core.publisher.Sinks` 를 더하고, 쓰지 않게 되는 `reactor.core.publisher.SignalType` 을 뺀다.
2. 상수와 필드를 더한다(필드는 `acquireTimeout` 바로 아래 — `@RequiredArgsConstructor` 의 인자 순서가 된다):

```java
    /** 갱신 주기를 넘기지 않는 생성자가 쓰는 값 — 운영 결선은 {@code dynamodb.lock-renew-interval} 을 넘긴다. */
    static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofSeconds(10);
```

```java
    /** 락을 쥔 동안 리스를 갱신하는 주기(설계 2026-10-02 §3.1). */
    private final Duration renewInterval;
```

3. 기존 7인자 서명을 남기는 생성자를 더한다(Lombok 이 만드는 8인자 생성자와 함께 둔다):

```java
    /** 갱신 주기를 {@link #DEFAULT_RENEW_INTERVAL} 로 둔다 — 테스트용. 운영 결선은 8인자 생성자로 {@code dynamodb.lock-renew-interval} 을 넘긴다. */
    public IncrementalSyncUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleChecker checker,
                                  MutationLock lock, Duration acquireTimeout, DriftObserver driftObserver,
                                  LockObserver lockObserver) {
        this(state, writer, checker, lock, acquireTimeout, DEFAULT_RENEW_INTERVAL, driftObserver, lockObserver);
    }
```

4. `withLock` 과 그 자바독을 이것으로 바꾼다:

```java
    /**
     * 변경 하나를 락 안에서 실행한다 (설계 §4, 2026-10-02 §3).
     *
     * <p><b>왜 유스케이스가 잡나.</b> 핸들러마다 넣으면 나중에 경로가 하나 늘 때 조용히 빠지고,
     * 그 빠진 곳이 하필 다른 인스턴스와 경합한다. 여기 두면 여덟 경로({@link #upsertUser}·
     * {@link #createUser}·{@link #changeUser}·{@link #removeUser}·{@link #upsertGroup}·
     * {@link #createGroup}·{@link #changeGroup}·{@link #removeGroup})가 빠짐없이 덮인다.
     *
     * <p><b>요청과 떼어 돈다(점검 S3).</b> 락 잡기부터 반납까지를 요청의 구독과 따로 돌린다. IdP 가 연결을 끊어도 커밋까지 마치고
     * 반납한다 — 끊기는 순간 반납하면 이미 보낸 OpenFGA 쓰기가 다음 요청의 Check 뒤에 떨어져 그 요청의 기준선이 틀린다. 락을 잡는 도중에
     * 끊겨 리스가 새는 일도 없어진다. 요청의 Reactor Context(traceId)는 이어받는다.
     *
     * <p><b>리스를 지킨다.</b> 쥔 동안 {@link LeaseKeeper} 가 리스를 갱신하고, 잃으면 멈춰 503 이다. OpenFGA 쓰기 직전·DynamoDB 커밋 직전에
     * 다시 확인한다({@link #반영하고_커밋한다}). <b>반납한 뒤 응답한다</b> — IdP 의 다음 요청이 이 요청의 락에 막히지 않는다.
     *
     * <p><b>반납이 실패하면</b>(스로틀, 네트워크) 리스가 만료될 때까지 이 인스턴스도 남도 다시 잡지 못한다. 응답은 성공이다 — 일은 끝났다.
     * 대신 {@link LockObserver#leaseLost} 를 올려 {@code scim.lock.lease_lost} 에 나타난다.
     *
     * <p><b>획득이 예외로 끝나면 그것도 503 이다 (설계 §6 두 번째 행).</b> DynamoDB 부분 장애로
     * {@code putItem} 이 {@code SdkException} 을 던지면 그대로 흘려보낼 수 없다 —
     * {@code ScimRouter} 의 기본 분기가 500 을 내고, IdP 는 500 을 <b>영구 실패</b>로 읽어
     * 프로비저닝을 버린다. 재시도해야 할 바로 그 순간에. 그래서 획득 구간의 모든 에러를
     * {@link LockUnavailableException} 으로 옮긴다 — "어차피 커밋도 못 한다".
     */
    private Mono<IncrementalSyncResult> withLock(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return Mono.deferContextual(context -> {
            Sinks.One<IncrementalSyncResult> 결과 = Sinks.one();
            잡고_돌린다(work)
                    .contextWrite(context)
                    .subscribe(결과::tryEmitValue, 결과::tryEmitError, 결과::tryEmitEmpty);
            return 결과.asMono();
        });
    }

    private Mono<IncrementalSyncResult> 잡고_돌린다(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicBoolean 경합했다 = new AtomicBoolean();

            return lock.acquire(MutationLock.LockPurpose.WRITE)
                    // retryWhen 위에 둬야 시도마다 불린다 — 아래에 두면 마지막 실패만 본다.
                    .doOnError(LockUnavailableException.class, error -> 경합했다.set(true))
                    // 밀리초 단위로 쥐는 락이라 즉시 503 을 내면 재시도만 늘어난다. 짧게 기다려보고
                    // 그래도 안 되면 그때의 503 이 IdP 에게 의미 있는 신호가 된다 (설계 §4.4).
                    .retryWhen(Retry.fixedDelay(acquireRetries(), ACQUIRE_RETRY_DELAY)
                            .filter(LockUnavailableException.class::isInstance))
                    .onErrorMap(Exceptions::isRetryExhausted,
                            error -> new LockUnavailableException("변경 락을 얻지 못했습니다"))
                    // DynamoDB 장애 등 락 이외의 예외도 503 으로 옮긴다 (설계 §6).
                    .onErrorMap(error -> !(error instanceof LockUnavailableException),
                            error -> new LockUnavailableException("변경 락을 얻는 중 오류가 발생했습니다", error))
                    // 실패했다고 다 경합은 아니다. 위 onErrorMap 이 DynamoDB 장애도
                    // LockUnavailableException 으로 옮기므로 예외 타입으로는 구별할 수 없고,
                    // 실제로 밀렸을 때만 켜지는 이 플래그로 봐야 한다.
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), 경합했다.get()))
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), 경합했다.get()))
                    .flatMap(lease -> new LeaseKeeper(lock, renewInterval, lockObserver)
                            .keep(lease, Mono.defer(() -> work.apply(lease)), "쓰기 도중 리스 상실")
                            .materialize()
                            .flatMap(끝 -> 반납한다(lease).thenReturn(끝))
                            .<IncrementalSyncResult>dematerialize());
        });
    }

    /** 반납 실패는 요청을 실패시키지 않는다 — 일은 이미 끝났다. 리스가 만료될 때까지 아무도 잡지 못하므로 지표로 남긴다. */
    private Mono<Void> 반납한다(LockLease lease) {
        return lock.release(lease)
                .onErrorResume(error -> {
                    log.warn("변경 락 반납이 실패했다. 리스가 만료될 때까지 아무도 잡지 못한다", error);
                    lockObserver.leaseLost("반납 실패");
                    return Mono.empty();
                });
    }
```

5. `diffAndApply` 의 끝(델타 계산 뒤)을 공용 헬퍼로 바꾼다. `withoutCycleCreatingEdges(actual, 원하는것).flatMap(after -> { … })` 안을 이것으로:

```java
                return withoutCycleCreatingEdges(actual, 원하는것).flatMap(after ->
                        반영하고_커밋한다(TupleDiff.between(actual, after), lease,
                                result -> commit.apply(result, actual, after)));
```

   그리고 `diffAndApply` 아래에 둘을 더한다:

```java
    /**
     * 델타를 OpenFGA 에 반영하고 DynamoDB 에 커밋한다. <b>OpenFGA 쓰기 직전과 커밋 직전에 늘 리스를 확인한다</b>(설계 2026-10-02 §3.2, 점검 M8) —
     * 바뀐 튜플이 없을 때도. 커밋은 조건 없는 덮어쓰기라, 30초 넘게 멈춘 요청이 확인 없이 커밋하면 다른 인스턴스가 저장한 비활성화를 되돌린다.
     * 확인이 실패하면 쓰지 않고 503 이다. renew 는 토큰 조건이 걸린 조건부 쓰기라 성공했다는 것이 곧 "아직 내가 쥐고 있다"는 증거다.
     *
     * <p>{@code writer.apply}·{@code 커밋} 을 {@code Mono.defer} 로 감싼다 — 감싸지 않으면 확인이 실패해도 그 표현식이 이미 평가된 뒤다.
     */
    private Mono<IncrementalSyncResult> 반영하고_커밋한다(TupleDelta delta, LockLease lease,
                                                   Function<TupleWriteResult, Mono<Void>> 커밋) {
        if (delta.isEmpty()) {
            return 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                    .then(Mono.defer(() -> 커밋.apply(TupleWriteResult.empty())))
                    .thenReturn(IncrementalSyncResult.noChange());
        }
        return 리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")
                .then(Mono.defer(() -> writer.apply(delta)))
                .flatMap(result -> 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                        .then(Mono.defer(() -> 커밋.apply(result)))
                        .thenReturn(IncrementalSyncResult.of(result)));
    }

    /** 리스를 확인(갱신)한다. 실패하면 {@link LockUnavailableException} — 저장소 장애도 503 으로 옮긴다(획득과 같은 이유). */
    private Mono<Void> 리스를_확인한다(LockLease lease, String 실패_사유) {
        return lock.renew(lease)
                .doOnError(error -> lockObserver.leaseLost(실패_사유))
                .onErrorMap(error -> !(error instanceof LockUnavailableException),
                        error -> new LockUnavailableException("변경 락 리스를 확인하지 못했습니다", error))
                .then();
    }
```

6. `diffAndApply` 자바독의 "**리스 재확인은 델타가 있을 때만 일어난다(설계 §4.7).**" 문단을 지우고 이 한 줄로 바꾼다:
   `<p><b>리스는 OpenFGA 쓰기 직전과 커밋 직전에 늘 확인한다</b> — {@link #반영하고_커밋한다} 참고(설계 2026-10-02 §3.2).`

`ScimUseCaseConfig#incrementalSyncUseCase` 의 생성을 바꾼다:

```java
        return new IncrementalSyncUseCase(state, writer, checker, lock,
                dynamoDb.getLockAcquireTimeout(), dynamoDb.getLockRenewInterval(), metrics, metrics);
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSync*' --tests '*WriteDecision*' --tests '*GroupChange*'` → PASS.
Run: `./gradlew :core:cleanTest :core:test` → PASS.
Run: `./gradlew :connector-scim:test` → PASS.
Run: `./gradlew :app-scim:compileTestJava` → 성공.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLeaseTest.java
git commit -F - <<'EOF'
feat: SCIM 쓰기가 리스를 지킨다 — 하트비트 갱신, 커밋 직전 늘 확인(점검 M8), 요청이 끊겨도 커밋까지 하고 반납(점검 S3), 반납 뒤 응답

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 3: 같은 토큰이면 락 획득 성공 (S14)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`

**Interfaces:**
- Produces: 패키지 전용 `Mono<LockLease> acquire(LockPurpose purpose, String token)`. 공개 `acquire(purpose)` 는 무작위 토큰으로 이것을 부른다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbMutationLockTest` 끝에 더한다:

```java
    @Test
    @DisplayName("같은 토큰으로 다시 잡으면 성공한다 — 서버에선 성공했는데 응답을 잃어 SDK 가 같은 요청을 다시 보낸 경우(점검 S14)")
    void 같은_토큰이면_다시_잡힌다() {
        // given — 첫 PutItem 은 서버에서 성공했다
        인스턴스1.acquire(LockPurpose.WRITE, "같은-토큰").block();

        // when — SDK 가 같은 요청(같은 토큰)을 다시 보낸다
        var lease = 인스턴스1.acquire(LockPurpose.WRITE, "같은-토큰").block();

        // then — 자기 락에 막히지 않고, 반납도 된다
        assertThat(lease.token()).isEqualTo("같은-토큰");
        인스턴스1.release(lease).block();
        assertThat(인스턴스2.peek().blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("다른 토큰은 여전히 막힌다")
    void 다른_토큰은_막힌다() {
        // given
        인스턴스1.acquire(LockPurpose.WRITE, "가").block();

        // when, then
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE, "나").block())
                .isInstanceOf(LockUnavailableException.class);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest'`
Expected: 컴파일 실패 — 토큰을 받는 `acquire` 가 없다.

- [ ] **Step 3: 구현한다**

`DynamoDbMutationLock#acquire(LockPurpose)` 를 둘로 나눈다:

```java
    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return acquire(purpose, UUID.randomUUID().toString());
    }

    /**
     * 정한 토큰으로 잡는다. <b>같은 토큰이면 이미 있는 줄도 다시 써서 성공한다</b>(설계 2026-10-02 §3.4, 점검 S14) — SDK 는 응답을 잃은 PutItem 을
     * 같은 요청(같은 토큰)으로 다시 보내는데, 조건에 이 경우가 없으면 서버에서 이미 성공한 자기 락에 막혀 30초 동안 모든 쓰기가 503 이 된다.
     * 앱 쪽 재시도(획득 대기)는 시도마다 새 토큰이라 남의 락을 가져가지 않는다.
     */
    Mono<LockLease> acquire(LockPurpose purpose, String token) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.getLockTtl());

            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.LOCK_PK));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(TOKEN, Attrs.s(token));
            item.put(HOLDER, Attrs.s(holderId));
            item.put(PURPOSE, Attrs.s(purpose.name()));
            item.put(Keys.EXPIRES_AT, Attrs.n(expiresAt.getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            // 아무도 없거나, 있어도 이미 만료됐거나, 내 토큰이면(응답을 잃은 재시도) 가져간다
                            .conditionExpression("attribute_not_exists(#pk) OR #expiresAt < :now OR #token = :token")
                            .expressionAttributeNames(Map.of(
                                    "#pk", Keys.PK, "#expiresAt", Keys.EXPIRES_AT, "#token", TOKEN))
                            .expressionAttributeValues(Map.of(
                                    ":now", Attrs.n(now.getEpochSecond()), ":token", Attrs.s(token)))
                            .build()))
                    .thenReturn(new LockLease(token, expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다"));
        });
    }
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest' --tests '*TwoInstanceWriteTest'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java
git commit -F - <<'EOF'
fix: 같은 토큰이면 락 획득 성공 — 응답을 잃은 PutItem 의 SDK 재시도가 자기 락에 막혀 30초 503 이 되던 것(점검 S14)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 4: DynamoDB 묶음 요청 부품 — 재시도 상한 (BatchGet·BatchWrite)

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/BatchRequests.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`batchGet`, `findMembers`, 상수)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java` (`batchWrite`, 상수)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BatchRequestsTest.java` (신규)

**Interfaces:**
- Produces: 패키지 전용 `final class BatchRequests(DynamoDbAsyncClient client, String tableName)` — `static final int GET_LIMIT = 100`, `WRITE_LIMIT = 25`,
  `MAX_ATTEMPTS = 5`, `Duration BASE_DELAY = 100ms`; `Flux<Map<String, AttributeValue>> get(List<Map<String, AttributeValue>> keys)`(강한 일관성),
  `Mono<Void> write(List<WriteRequest> requests)`. Task 5 가 `write` 를 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BatchRequestsTest.java`:

```java
package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DynamoDB 묶음 요청은 처리 못 한 키를 정해진 횟수만 다시 보낸다 (설계 2026-10-02 §4.4).
 */
class BatchRequestsTest extends DynamoDbTestSupport {

    private static Map<String, AttributeValue> 키(String pk) {
        return Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META));
    }

    private void 심는다(String pk) {
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(키(pk)).build()).join();
    }

    /** batchGetItem·batchWriteItem 만 가로채고 나머지는 실제 클라이언트로 보낸다. */
    private DynamoDbAsyncClient 가로채는_클라이언트(java.util.function.BiFunction<String, Object, Object> 가로채기) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    Object 바꾼것 = 가로채기.apply(method.getName(), args == null ? null : args[0]);
                    return 바꾼것 != null ? 바꾼것 : method.invoke(client, args);
                });
    }

    @Test
    @DisplayName("처리 못 한 키가 계속 남으면 다섯 번 보낸 뒤 남은 수를 담아 실패한다 — 끝없이 다시 묻지 않는다")
    void 읽기는_다섯번_보낸_뒤_실패한다() {
        // given — 매번 아무것도 읽지 못하고 키를 그대로 돌려준다
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchGetItem")) {
                return null;
            }
            보낸_횟수.incrementAndGet();
            return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                    .responses(Map.of())
                    .unprocessedKeys(((BatchGetItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());

        // when, then
        assertThatThrownBy(() -> batch.get(List.of(키("USER#kim"))).collectList().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1건");
        assertThat(보낸_횟수).hasValue(BatchRequests.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("한 번 밀린 키는 쉬었다 다시 읽어 전부 돌려준다")
    void 밀린_키를_다시_읽는다() {
        // given — 두 줄이 있고, 첫 요청은 통째로 밀린다
        심는다("USER#kim");
        심는다("USER#lee");
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchGetItem") || 보낸_횟수.incrementAndGet() > 1) {
                return null;
            }
            return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                    .responses(Map.of())
                    .unprocessedKeys(((BatchGetItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());

        // when
        var 읽은것 = batch.get(List.of(키("USER#kim"), 키("USER#lee"))).collectList().block();

        // then
        assertThat(읽은것).hasSize(2);
        assertThat(보낸_횟수).hasValue(2);
    }

    @Test
    @DisplayName("쓰기도 처리 못 한 요청이 계속 남으면 다섯 번 보낸 뒤 실패한다")
    void 쓰기는_다섯번_보낸_뒤_실패한다() {
        // given
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        var batch = new BatchRequests(가로채는_클라이언트((name, request) -> {
            if (!name.equals("batchWriteItem")) {
                return null;
            }
            보낸_횟수.incrementAndGet();
            return CompletableFuture.completedFuture(BatchWriteItemResponse.builder()
                    .unprocessedItems(((BatchWriteItemRequest) request).requestItems())
                    .build());
        }), properties.getTableName());
        WriteRequest 하나 = WriteRequest.builder().putRequest(PutRequest.builder().item(키("USER#kim")).build()).build();

        // when, then
        assertThatThrownBy(() -> batch.write(List.of(하나)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1건");
        assertThat(보낸_횟수).hasValue(BatchRequests.MAX_ATTEMPTS);
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*BatchRequestsTest'`
Expected: 컴파일 실패 — `BatchRequests` 가 없다.

- [ ] **Step 3: 구현한다**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/BatchRequests.java`:

```java
package dev.starryeye.organization.storage;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB 묶음 요청(BatchGetItem·BatchWriteItem)을 보내고, 처리 못 한 키를 정해진 횟수만 다시 보낸다(설계 2026-10-02 §4.4).
 *
 * <p>DynamoDB 는 처리량이 모자라면 묶음 일부를 돌려준다(UnprocessedKeys·UnprocessedItems). AWS 는 지수 백오프로 다시 보내라고 권한다.
 * 상한을 두지 않으면 스로틀이 계속될 때 락을 쥔 요청이 끝나지 않는다 — 그래서 {@value #MAX_ATTEMPTS}번까지 {@link #BASE_DELAY} 부터 두 배씩
 * 쉬며 보내고, 그래도 남으면 남은 수를 담아 실패시킨다.
 */
@RequiredArgsConstructor
final class BatchRequests {

    /** BatchGetItem 한 번에 담을 수 있는 키 수(DynamoDB 한도). */
    static final int GET_LIMIT = 100;
    /** BatchWriteItem 한 번에 담을 수 있는 요청 수(DynamoDB 한도). */
    static final int WRITE_LIMIT = 25;
    static final int MAX_ATTEMPTS = 5;
    static final Duration BASE_DELAY = Duration.ofMillis(100);

    private final DynamoDbAsyncClient client;
    private final String tableName;

    /** 키를 강한 일관성으로 읽는다. 키는 {@value #GET_LIMIT}개 이하. */
    Flux<Map<String, AttributeValue>> get(List<Map<String, AttributeValue>> keys) {
        if (keys.isEmpty()) {
            return Flux.empty();
        }
        return get(KeysAndAttributes.builder().keys(keys).consistentRead(true).build(), 1);
    }

    private Flux<Map<String, AttributeValue>> get(KeysAndAttributes keys, int attempt) {
        return Mono.fromFuture(() -> client.batchGetItem(BatchGetItemRequest.builder()
                        .requestItems(Map.of(tableName, keys))
                        .build()))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> 읽은것 =
                            Flux.fromIterable(response.responses().getOrDefault(tableName, List.of()));
                    KeysAndAttributes 남은것 = response.unprocessedKeys().get(tableName);
                    if (남은것 == null || !남은것.hasKeys() || 남은것.keys().isEmpty()) {
                        return 읽은것;
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        return 읽은것.concatWith(Mono.error(new IllegalStateException(
                                "BatchGetItem 이 %d회 보낸 뒤에도 %d건을 읽지 못했다".formatted(attempt, 남은것.keys().size()))));
                    }
                    KeysAndAttributes 다시 = 남은것.toBuilder().consistentRead(true).build();
                    return 읽은것.concatWith(Mono.delay(쉬는시간(attempt)).thenMany(get(다시, attempt + 1)));
                });
    }

    /** 쓰기·지우기 요청을 보낸다. 요청은 {@value #WRITE_LIMIT}개 이하. */
    Mono<Void> write(List<WriteRequest> requests) {
        return write(requests, 1);
    }

    private Mono<Void> write(List<WriteRequest> requests, int attempt) {
        if (requests.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(tableName, requests))
                        .build()))
                .flatMap(response -> {
                    List<WriteRequest> 남은것 = response.unprocessedItems().getOrDefault(tableName, List.of());
                    if (남은것.isEmpty()) {
                        return Mono.empty();
                    }
                    if (attempt >= MAX_ATTEMPTS) {
                        return Mono.error(new IllegalStateException(
                                "BatchWriteItem 이 %d회 보낸 뒤에도 %d건을 처리하지 못했다".formatted(attempt, 남은것.size())));
                    }
                    return Mono.delay(쉬는시간(attempt)).then(write(남은것, attempt + 1));
                })
                .then();
    }

    private static Duration 쉬는시간(int attempt) {
        return BASE_DELAY.multipliedBy(1L << (attempt - 1));
    }
}
```

`DynamoDbDirectoryStateRepository`:
- 상수 `BATCH_GET_LIMIT`·`UNPROCESSED_RETRY_DELAY` 를 지운다. `findMembers` 의 `.buffer(BATCH_GET_LIMIT)` 를 `.buffer(BatchRequests.GET_LIMIT)` 로 바꾼다.
- `batchGet` 본문을 `return new BatchRequests(client, properties.getTableName()).get(keys);` 로 바꾼다. `findMembers` 자바독의
  "미처리 키는 잠깐 쉬었다 다시 읽는다" 를 "미처리 키는 {@link BatchRequests} 규칙(5번까지, 백오프)으로 다시 읽는다" 로 고친다.
- 쓰지 않게 된 import(`KeysAndAttributes`, `BatchGetItemRequest`, `Duration` 이 다른 곳에서 안 쓰이면)를 정리한다.

`DynamoDbTupleSnapshotRepository`:
- `batchWrite(List<WriteRequest>)`·`batchWrite(List<WriteRequest>, int)` 와 상수 `MAX_BATCH_ATTEMPTS`·`BATCH_RETRY_BASE_DELAY` 를 지운다. `BATCH_SIZE` 는
  `BatchRequests.WRITE_LIMIT` 로 바꾼다. `.concatMap(this::batchWrite)` 를 쓰던 곳은 `.concatMap(new BatchRequests(client, properties.getTableName())::write)` 로
  바꾸거나(메서드 참조 대상이 매번 만들어지지 않게) 메서드 첫 줄에 `BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());` 를 두고 `묶음::write` 로 쓴다.
  지운 `batchWrite` 자바독의 근거(핫루프, save 실패 → FAILED)는 `BatchRequests` 자바독이 대신한다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*BatchRequestsTest' --tests '*DynamoDbTupleSnapshotRepositoryTest' --tests '*DynamoDbDirectoryStateRepositoryTest'` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/BatchRequests.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BatchRequestsTest.java
git commit -F - <<'EOF'
feat: DynamoDB 묶음 요청 부품 — 처리 못 한 키는 5번까지 백오프로 다시, 그래도 남으면 실패(BatchGet 무한 재시도 제거)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 5: `deleteGroup(groupId, members)` — 묶어 지우고 META 맨 마지막

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (호출 한 줄)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (위임 저장소 한 줄)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: `BatchRequests`(Task 4).
- Produces: 포트 `Mono<Void> deleteGroup(String groupId, Set<MemberRef> members)` — 옛 `deleteGroup(String)` 은 없어진다.
  가짜 `FakeStateRepository#deleteGroupCalls`(`List<String>`), `deleteGroup` 은 조직을 지우고 모든 조직의 멤버에서 그 조직을 뺀다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest`:
- 기존 두 테스트의 `repository.deleteGroup("DEV002").block();` 을 지운 조직의 멤버를 넘기도록 바꾼다:
  `조직_삭제시_멤버십도_사라진다` 는 `repository.deleteGroup("DEV002", Set.of(MemberRef.user("kim"))).block();`,
  `조직_삭제가_소속_줄을_치운다` 는 `repository.deleteGroup("DEV002", Set.of(MemberRef.user("kim"), MemberRef.group("DEV003"))).block();`.
- 끝에 더한다(import `java.lang.reflect.Proxy`, `java.util.concurrent.CompletableFuture`, `java.util.concurrent.atomic.AtomicInteger`,
  `software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient`, `static org.assertj.core.api.Assertions.assertThatThrownBy` 가 없으면 더한다.
  이 클래스에는 시계 필드 `clock`, 도우미 `조직(code, name, MemberRef...)`·`정렬키들(pk)` 가 이미 있다):

```java
    @Test
    @DisplayName("조직을 지우면 상위 조직의 그 조직 줄, 그 조직의 소속 줄, 멤버 줄, 멤버들의 소속 줄, META 가 모두 사라진다")
    void 조직_삭제가_상위_조직_줄까지_치운다() {
        // given — 본부 ⊃ 팀 ⊃ kim
        repository.saveGroup(조직("TEAM", "팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("HQ", "본부", MemberRef.group("TEAM"))).block();

        // when
        repository.deleteGroup("TEAM", Set.of(MemberRef.user("kim"))).block();

        // then
        assertThat(정렬키들("GROUP#TEAM")).isEmpty();
        assertThat(정렬키들("USER#kim")).isEmpty();
        assertThat(repository.findGroup("HQ").block().members()).isEmpty();
    }

    @Test
    @DisplayName("지우다 중간에 멈춰도 META 가 남아, 다시 지우면 끝난다")
    void 중간에_멈춰도_다시_지우면_끝난다() {
        // given — 멤버 60명(멤버 줄 묶음이 셋), 두 번째 BatchWrite 가 실패한다
        Set<MemberRef> 멤버 = new LinkedHashSet<>();
        for (int i = 0; i < 60; i++) {
            멤버.add(MemberRef.user("u" + i));
        }
        repository.saveGroup(new DirectoryGroup("BIG", "BIG", "큰 조직", 멤버)).block();
        AtomicInteger 보낸_횟수 = new AtomicInteger();
        DynamoDbAsyncClient 두번째에_실패하는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("batchWriteItem") && 보낸_횟수.incrementAndGet() == 2) {
                        return CompletableFuture.failedFuture(new IllegalStateException("batchWriteItem 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
        var 멈추는_저장소 = new DynamoDbDirectoryStateRepository(두번째에_실패하는_클라이언트, properties, clock);

        // when — 첫 시도는 실패한다
        assertThatThrownBy(() -> 멈추는_저장소.deleteGroup("BIG", 멤버).block());

        // then — META 가 남아 조직이 보이고, 다시 지우면 끝난다
        DirectoryGroup 남은것 = repository.findGroup("BIG").block();
        assertThat(남은것).isNotNull();
        repository.deleteGroup("BIG", 남은것.members()).block();
        assertThat(repository.findGroup("BIG").block()).isNull();
        assertThat(정렬키들("USER#u0")).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: 컴파일 실패 — `deleteGroup(String, Set)` 이 없다.

- [ ] **Step 3: 구현한다**

`DirectoryStateRepository` — `Mono<Void> deleteGroup(String groupId);` 와 그 자바독을 이것으로 바꾼다:

```java
    /**
     * 조직을 지운다 — 상위 조직들의 "이 조직" 멤버 줄과 이 조직의 멤버 줄을 먼저, 이 조직의 소속 줄과 멤버들의 소속 줄을 그다음, META 를 맨 마지막에
     * (설계 2026-10-02 §4.1). 멤버는 호출자가 이미 읽은 것을 받는다 — 조직 파티션을 다시 훑지 않는다.
     *
     * <p><b>META 가 맨 마지막이다.</b> 중간에 멈추면(오류·리스 상실) 조직이 남아, 같은 삭제를 다시 부르면 남은 것을 마저 지운다. 멤버 줄을 소속 줄보다
     * 먼저 지운다 — 소속 줄만 남는 방향으로만 어긋난다(강한 소속 조회 설계 §5).
     */
    Mono<Void> deleteGroup(String groupId, Set<MemberRef> members);
```

`DynamoDbDirectoryStateRepository` — 옛 `deleteGroup(String)` 과 자바독을 이것으로 바꾼다(import `java.util.ArrayList`,
`software.amazon.awssdk.services.dynamodb.model.DeleteRequest`, `software.amazon.awssdk.services.dynamodb.model.WriteRequest` 가 없으면 더한다):

```java
    /**
     * 이 조직의 소속 줄만 읽어 상위 조직을 알아낸다(줄 몇 개) — 멤버 줄 10만 개를 다시 훑지 않는다. 지우기는 25개씩 묶어
     * {@link BatchRequests} 로 보낸다(멤버 10만 명이면 약 8,000번, 묶음 재시도 상한).
     */
    @Override
    public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) {
        String pk = Keys.groupPk(groupId);
        MemberRef 이조직 = MemberRef.group(groupId);
        return querySortKeys(pk, Keys.BELONGS_TO_PREFIX)
                .collectList()
                .flatMap(소속_정렬키 -> {
                    List<WriteRequest> 멤버_줄 = new ArrayList<>();
                    소속_정렬키.forEach(sk -> 멤버_줄.add(지우기(Keys.groupPk(Keys.parseBelongsToSk(sk)), Keys.memberSk(이조직))));
                    members.forEach(member -> 멤버_줄.add(지우기(pk, Keys.memberSk(member))));

                    List<WriteRequest> 소속_줄 = new ArrayList<>();
                    소속_정렬키.forEach(sk -> 소속_줄.add(지우기(pk, sk)));
                    members.forEach(member -> 소속_줄.add(지우기(Keys.memberPk(member), Keys.belongsToSk(groupId))));

                    return 묶어_보낸다(멤버_줄)
                            .then(Mono.defer(() -> 묶어_보낸다(소속_줄)))
                            .then(Mono.defer(() -> deleteItem(pk, Keys.META)));
                });
    }

    private static WriteRequest 지우기(String pk, String sk) {
        return WriteRequest.builder()
                .deleteRequest(DeleteRequest.builder()
                        .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                        .build())
                .build();
    }

    private Mono<Void> 묶어_보낸다(List<WriteRequest> requests) {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return Flux.fromIterable(requests)
                .buffer(BatchRequests.WRITE_LIMIT)
                .flatMap(묶음::write, QUERY_CONCURRENCY)
                .then();
    }
```

`FakeStateRepository` — 옛 `deleteGroup(String)` 을 이것으로 바꾼다:

```java
    /** {@link #deleteGroup} 가 불린 조직 id — 삭제 전용 경로가 조직을 몇 번 지우는지 본다. */
    public final List<String> deleteGroupCalls = new ArrayList<>();

    @Override
    public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) {
        return Mono.fromRunnable(() -> {
            deleteGroupCalls.add(groupId);
            groups.remove(groupId);
            MemberRef 이조직 = MemberRef.group(groupId);
            groups.replaceAll((id, group) -> {
                if (!group.members().contains(이조직)) {
                    return group;
                }
                Set<MemberRef> 남은멤버 = new LinkedHashSet<>(group.members());
                남은멤버.remove(이조직);
                return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), 남은멤버);
            });
        });
    }
```

`IncrementalSyncUseCase#removeGroupInternal` 의 호출을 바꾼다(그 자리의 `group` 은 이미 읽은 조직이다):
`return saveParents.then(Mono.defer(() -> state.deleteGroup(groupId, group.members())));`

`LdapInterruptedSyncScaleTest` 의 위임 저장소 줄을 바꾼다:
`@Override public Mono<Void> deleteGroup(String groupId, Set<MemberRef> members) { return 실제.deleteGroup(groupId, members); }`
(import `dev.starryeye.organization.core.model.MemberRef`·`java.util.Set` 이 없으면 더한다.)

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'` → PASS.
Run: `./gradlew :core:cleanTest :core:test` → PASS.
Run: `./gradlew :app-ldap:compileTestJava` → 성공.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java
git commit -F - <<'EOF'
feat: 조직 삭제는 받은 멤버로 묶어 지우고 META 를 맨 마지막에 — 중간에 멈춰도 다시 지우면 끝난다, 파티션을 두 번 훑지 않는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 6: 조직 삭제 전용 경로, 빠지는 멤버는 Check 없이 (C6)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncBigChangeTest.java` (신규)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCandidateScopeTest.java` (단언 하나)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/GroupChangeReadScopeTest.java` (단언 넷)

**Interfaces:**
- Consumes: `반영하고_커밋한다`(Task 2), `deleteGroup(groupId, members)`·`FakeStateRepository#deleteGroupCalls`(Task 5).
- `diffAndApply` 에 인자 `Set<RelationTuple> 확인없이_지울것` 이 붙는다(`changeGroup` 만 비지 않은 집합을 넘기고 나머지는 `Set.of()`).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncBigChangeTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큰 변경을 싸게 — 조직 삭제는 계산 없이, 빠지는 멤버는 Check 없이 지운다 (설계 2026-10-02 §4.1·§4.2, 점검 C6).
 */
class IncrementalSyncBigChangeTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(), Duration.ZERO,
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        // 본부(HQ) ⊃ 팀(TEAM) ⊃ kim(활성)·lee(비활성)·하위 조직 SUB
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", false)).block();
        state.saveGroup(new DirectoryGroup("SUB", "SUB", "하위", Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("TEAM", "TEAM", "팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("SUB")))).block();
        state.saveGroup(new DirectoryGroup("HQ", "HQ", "본부", Set.of(MemberRef.group("TEAM")))).block();
        checker.allowed.addAll(TupleMapper.toTuples(state.loadAll().block()).tuples());
        state.findUserCalls.clear();
        state.findGroupCalls.clear();
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    @Test
    @DisplayName("조직 삭제는 직원을 읽지 않고 Check 하지 않으며, 그 조직을 언급하는 줄을 '없으면 무시'로 다 지운다")
    void 조직_삭제는_계산하지_않는다() {
        // when
        var result = useCase.removeGroup("TEAM").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).as("멤버 직원을 읽지 않는다").isEmpty();
        assertThat(checker.checked).as("Check 하지 않는다").isEmpty();
        assertThat(writer.deleted).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "TEAM"),
                RelationTuple.directMember("lee", "TEAM"),
                RelationTuple.child("SUB", "TEAM"),
                RelationTuple.child("TEAM", "HQ"));
        assertThat(state.groups).doesNotContainKey("TEAM");
        assertThat(state.groups.get("HQ").members()).isEmpty();
        assertThat(state.groups.get("SUB").members()).as("하위 조직 자신의 멤버는 그대로").containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("상위 조직은 아이디만 읽는다 — 큰 본부 밑의 작은 팀을 지울 때 본부 멤버 목록을 읽지 않는다")
    void 상위_조직은_아이디만_읽는다() {
        // when
        useCase.removeGroup("TEAM").block();

        // then
        assertThat(state.findGroupCalls).as("지우는 조직만 한 번 읽는다").containsExactly("TEAM");
        assertThat(state.deleteGroupCalls).containsExactly("TEAM");
    }

    @Test
    @DisplayName("멤버도 상위 조직도 없는 조직도 지운다")
    void 빈_조직도_지운다() {
        // given
        state.saveGroup(new DirectoryGroup("EMPTY", "EMPTY", "빈 조직", Set.of())).block();

        // when
        var result = useCase.removeGroup("EMPTY").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups).doesNotContainKey("EMPTY");
    }

    @Test
    @DisplayName("일부 줄을 못 지우면 조직을 남기고, 지운 멤버·상위 조직 줄만 뺀다 — 재시도가 남은 것을 지운다")
    void 일부를_못_지우면_조직을_남긴다() {
        // given
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("kim", "TEAM")));

        // when
        var result = useCase.removeGroup("TEAM").block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("kim"));
        assertThat(state.groups.get("HQ").members()).as("상위 조직 줄은 지워졌다").isEmpty();
    }

    @Test
    @DisplayName("멤버 전원 빼기는 빠지는 멤버를 Check·직원 읽기 없이 '없으면 무시'로 지운다")
    void 전원_빼기는_확인하지_않는다() {
        // when
        var result = useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).isEmpty();
        assertThat(checker.checked).isEmpty();
        assertThat(writer.deleted).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "TEAM"),
                RelationTuple.directMember("lee", "TEAM"),
                RelationTuple.child("SUB", "TEAM"));
        assertThat(state.groups.get("TEAM").members()).isEmpty();
    }

    @Test
    @DisplayName("교체에서 들어오는 멤버는 지금처럼 Check 하고, 빠지는 멤버는 Check 하지 않는다")
    void 들어오는_쪽만_확인한다() {
        // given
        state.saveUser(직원("park", true)).block();
        state.findUserCalls.clear();

        // when — kim·lee·SUB 가 빠지고 park 이 들어온다
        useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of(MemberRef.user("park")))).block();

        // then
        assertThat(checker.checked).containsExactly(RelationTuple.directMember("park", "TEAM"));
        assertThat(state.findUserCalls).containsOnly("park");
        assertThat(writer.written).containsExactly(RelationTuple.directMember("park", "TEAM"));
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("빠지는 멤버의 줄을 못 지우면 그 멤버는 남는다")
    void 빠지는_멤버를_못_지우면_남는다() {
        // given
        writer.failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", "TEAM")));

        // when
        var result = useCase.changeGroup("TEAM", GroupChange.delta().replacing(Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.groups.get("TEAM").members()).containsExactly(MemberRef.user("lee"));
    }
}
```

기존 테스트 둘의 단언을 새 규칙으로 고친다(설계 2026-10-02 §4.1·§4.2 — 삭제·빠지는 쪽은 Check 하지 않는다):
- `IncrementalSyncCandidateScopeTest#removeGroup_은_초점_튜플만_지운다` — `assertThat(checker.checked).containsExactlyInAnyOrder(KIM_DEV001, PARK_DEV001, DEV001_HQ);` 를
  `assertThat(checker.checked).as("삭제는 Check 없이 지운다(설계 2026-10-02 §4.1)").isEmpty();` 로. `writer.deleted` 단언은 그대로 둔다.
- `GroupChangeReadScopeTest#한명_빼기` — `findUserCalls` 단언을 `assertThat(state.findUserCalls).as("빠지는 멤버는 읽지 않는다").isEmpty();` 로,
  `checker.checked` 단언을 `assertThat(checker.checked).as("빠지는 멤버는 Check 하지 않는다").isEmpty();` 로. `writer.deleted` 단언은 그대로.
- `GroupChangeReadScopeTest#전체_교체` — `findUserCalls` 단언을 `.isNotEmpty().allMatch("newbie"::equals)` 로, `checker.checked` 단언을
  `.containsOnly(RelationTuple.directMember("newbie", 대형조직))` 로.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncBigChangeTest' --tests '*IncrementalSyncCandidateScopeTest' --tests '*GroupChangeReadScopeTest'`
Expected: FAIL — 지금은 삭제·빠지는 멤버도 Check 하고 직원을 읽는다.

- [ ] **Step 3: 구현한다**

`IncrementalSyncUseCase`:

1. `removeGroup` 자바독을 바꾼다:

```java
    /**
     * 조직 삭제 (설계 2026-10-02 §4.1). 삭제 뒤의 모습은 "이 조직을 언급하는 줄이 하나도 없음"으로 정해져 있어 계산(직원 읽기·Check·diff)을 하지 않는다.
     * 조직 파티션을 한 번 읽어 멤버를 얻고, 상위 조직은 아이디만 읽는다(소속 줄). 그 조직을 언급하는 줄을 "없으면 무시"로 지운다.
     *
     * <p>다 지웠으면 조직을 지운다(META 맨 마지막 — 저장소 계약). 일부를 못 지웠으면 조직을 남기고 지운 멤버·상위 조직 줄만 뺀다 — 응답은 5xx 이고
     * IdP 의 재시도가 남은 것을 지운다. 대상이 없으면 빈 {@code Mono} 다 — 존재 확인도 락 안이다(SCIM 쓰기 락 설계 §3).
     */
```

2. `removeGroupInternal` 을 바꾸고 둘을 더한다:

```java
    private Mono<IncrementalSyncResult> removeGroupInternal(String groupId, LockLease lease) {
        return state.findGroup(groupId)
                .flatMap(group -> state.findGroupIdsContaining(MemberRef.group(groupId))
                        .collect(LinkedHashSet<String>::new, Set::add)
                        .flatMap(parentIds -> 반영하고_커밋한다(
                                TupleDelta.deleteOnly(조직을_언급하는_튜플(group, parentIds)), lease,
                                result -> 조직_삭제를_커밋한다(group, parentIds, result))));
    }

    /** 조직이 사라지면 없어야 할 줄 — 직원→조직, 하위 조직→조직, 조직→상위 조직. 비활성 직원의 줄도 넣는다 — 있으면 지워야 하고 없으면 무시된다. */
    private static Set<RelationTuple> 조직을_언급하는_튜플(DirectoryGroup group, Set<String> parentIds) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        group.members().forEach(member -> tuples.add(tupleFor(member, group.id())));
        parentIds.forEach(parent -> tuples.add(RelationTuple.child(group.id(), parent)));
        return tuples;
    }

    private Mono<Void> 조직_삭제를_커밋한다(DirectoryGroup group, Set<String> parentIds, TupleWriteResult result) {
        if (!result.hasFailure()) {
            return state.deleteGroup(group.id(), group.members());
        }
        MemberRef 이조직 = MemberRef.group(group.id());
        Set<MemberRef> 지운멤버 = group.members().stream()
                .filter(member -> result.deleted().contains(tupleFor(member, group.id())))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
        Mono<Void> 상위에서_뺀다 = Flux.fromIterable(parentIds)
                .filter(parent -> result.deleted().contains(RelationTuple.child(group.id(), parent)))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .flatMap(parent -> state.saveGroupChange(parent, Set.of(), Set.of(이조직)), LOAD_CONCURRENCY)
                .then();
        return 상위에서_뺀다.then(Mono.defer(() -> state.saveGroupChange(header, Set.of(), 지운멤버)));
    }
```

   (`parentsOf` 는 `upsertGroup` 이 계속 쓴다 — 남긴다.)

3. `diffAndApply` 서명에 `Set<RelationTuple> 확인없이_지울것` 을 `focus` 다음 자리에 더하고, 델타를 만드는 부분을 바꾼다:

```java
                return withoutCycleCreatingEdges(actual, 원하는것).flatMap(after -> {
                    TupleDelta 계산 = TupleDiff.between(actual, after);
                    if (확인없이_지울것.isEmpty()) {
                        return 반영하고_커밋한다(계산, lease, result -> commit.apply(result, actual, after));
                    }
                    // 빠지는 멤버의 줄은 Check 없이 "없으면 무시"로 지운다(설계 2026-10-02 §4.2). 커밋의 재조정은 그 줄이 있었다고 본다 —
                    // 지우기가 실패한 멤버만 남는다.
                    Set<RelationTuple> 지울것 = new LinkedHashSet<>(계산.toDelete());
                    지울것.addAll(확인없이_지울것);
                    Set<RelationTuple> 있다고_볼것 = new LinkedHashSet<>(actual);
                    있다고_볼것.addAll(확인없이_지울것);
                    return 반영하고_커밋한다(new TupleDelta(계산.toWrite(), 지울것), lease,
                            result -> commit.apply(result, 있다고_볼것, after));
                });
```

   `diffAndApply` 를 부르는 다른 곳(`upsertUser`·`createUser`·`changeUser`·`removeUser`·`upsertGroup`·`createGroup` 등)은 `focus` 다음에 `Set.of()` 를 넘긴다.
   자바독에 한 줄 더한다: `{@code 확인없이_지울것} — Check 없이 지울 줄(빠지는 멤버). 드리프트 지표는 이 줄을 재지 않는다.`

4. `changeGroupInternal` 을 바꾼다:

```java
    private Mono<IncrementalSyncResult> changeGroupInternal(String groupId, GroupChange change, LockLease lease) {
        return state.findGroupHeader(groupId)
                .flatMap(header -> 바뀌는_멤버(groupId, change).flatMap(전후 -> {
                    GroupHeader 바뀐헤더 = change.applyTo(header);
                    Set<MemberRef> 빠질것 = 차집합(전후.전(), 전후.후());
                    DirectoryGroup 전 = new DirectoryGroup(groupId, header.externalId(), header.displayName(), 전후.전());
                    // 그림에는 빠지는 멤버를 싣지 않는다 — 그 멤버의 직원을 읽지도, 그 줄을 Check 하지도 않는다(설계 2026-10-02 §4.2)
                    DirectoryGroup 그림_전 = new DirectoryGroup(groupId, header.externalId(), header.displayName(),
                            차집합(전후.전(), 빠질것));
                    DirectoryGroup 후 = new DirectoryGroup(groupId, 바뀐헤더.externalId(), 바뀐헤더.displayName(), 전후.후());
                    Set<RelationTuple> 확인없이_지울것 = 빠질것.stream()
                            .map(member -> tupleFor(member, groupId))
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        DirectoryGroup reconciled = reconcileGroupMembers(전, 후, beforeTuples, afterTuples, result);
                        Set<MemberRef> 넣을것 = 차집합(reconciled.members(), 전.members());
                        Set<MemberRef> 뺄것 = 차집합(전.members(), reconciled.members());
                        return Mono.defer(() -> state.saveGroupChange(바뀐헤더, 넣을것, 뺄것));
                    };

                    return diffAndApply(snapshotOfGroups(Set.of(그림_전)), snapshotOfGroups(Set.of(후)),
                            RelationTuple.groupRef(groupId), 확인없이_지울것, lease, commit);
                }));
    }
```

   `changeGroup` 자바독 끝에 한 문장을 더한다: `<p>빠지는 멤버의 줄은 Check·직원 읽기 없이 "없으면 무시"로 지운다(설계 2026-10-02 §4.2) — 멤버 전원 빼기·빈 교체가 조직 크기만큼 읽지 않는다.`

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSync*' --tests '*GroupChange*' --tests '*WriteDecision*'` → PASS.
`GroupChangeEquivalenceTest` 가 깨지면: 빠지는 멤버를 Check 없이 지우므로 "원래 줄이 없던 빠지는 멤버"(비활성 직원·없는 하위 조직)의 삭제가 이제 시도된다.
실패 주입이 그 줄에 걸리는 경우만 결과가 달라진다 — 그 경우 기대값을 새 규칙(지우기에 실패한 멤버는 남는다)으로 고치고 이유를 주석 한 줄로 적는다.
Run: `./gradlew :core:cleanTest :core:test` → PASS.
Run: `./gradlew :connector-scim:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncBigChangeTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCandidateScopeTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/GroupChangeReadScopeTest.java
git commit -F - <<'EOF'
feat: 조직 삭제는 계산 없이 그 조직을 언급하는 줄을 지운다, 빠지는 멤버는 Check·직원 읽기 없이(점검 C6) — 상위 조직은 아이디만 읽는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

(`GroupChangeEquivalenceTest` 를 고쳤으면 그 경로도 스테이징한다.)

---

### Task 7: OpenFGA 요청을 동시에 여러 개 (`openfga.request-concurrency`)

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaProperties.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaConfig.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleCheckerTest.java`

**Interfaces:**
- Produces: `OpenFgaProperties#getRequestConcurrency()`(기본 4); `OpenFgaRelationTupleChecker(StoreBootstrapper, int 동시)`(1인자 생성자는 동시 1 로 남김);
  `OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(List<Batch>, Function<Batch, Mono<TupleWriteResult>>, int 동시)`(2인자는 동시 1 로 위임).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`OpenFgaRelationTupleWriterBreakerTest` 끝에 더한다(import `java.time.Duration`, `java.util.concurrent.CopyOnWriteArrayList` 가 없으면 더한다):

```java
    @Test
    @DisplayName("묶음을 설정한 수만큼 동시에 보내되 넘지 않고, 지우기 묶음을 다 보낸 뒤 쓰기 묶음을 보낸다")
    void 동시에_보내되_지우기가_먼저다() {
        // given — 지우기 묶음 넷, 쓰기 묶음 넷
        List<Batch> 배치 = new ArrayList<>();
        IntStream.range(0, 4).forEach(i -> 배치.add(Batch.deletes(List.of(RelationTuple.directMember("d" + i, "DEV002")))));
        IntStream.range(0, 4).forEach(i -> 배치.add(Batch.writes(List.of(RelationTuple.directMember("w" + i, "DEV002")))));
        AtomicInteger 지금 = new AtomicInteger();
        AtomicInteger 최대 = new AtomicInteger();
        List<Boolean> 시작한_종류 = new CopyOnWriteArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            최대.accumulateAndGet(지금.incrementAndGet(), Math::max);
            시작한_종류.add(batch.delete());
            return Mono.delay(Duration.ofMillis(50))
                    .map(tick -> {
                        지금.decrementAndGet();
                        return batch.succeeded();
                    });
        });

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 2).block();

        // then
        assertThat(최대.get()).isEqualTo(2);
        assertThat(시작한_종류.subList(0, 4)).containsOnly(true);
        assertThat(시작한_종류.subList(4, 8)).containsOnly(false);
        assertThat(결과.deleted()).hasSize(4);
        assertThat(결과.written()).hasSize(4);
    }

    @Test
    @DisplayName("동시에 보내도 차단기는 보낸 순서대로 세어 세 묶음째에서 멈추고, 이미 나간 묶음은 보내지 않은 것으로 센다")
    void 동시에_보내도_순서대로_센다() {
        // given — 쓰기 묶음 여덟 개가 모두 일시 오류다
        List<Batch> 배치 = IntStream.range(0, 8)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("u" + i, "DEV002"))))
                .toList();
        AtomicInteger 보낸 = new AtomicInteger();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            보낸.incrementAndGet();
            return Mono.delay(Duration.ofMillis(10)).thenReturn(batch.failed("일시 오류"));
        });

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 4).block())
                .isInstanceOf(TupleWriteAbortedException.class)
                .satisfies(error -> assertThat(((TupleWriteAbortedException) error).partial().failures())
                        .as("실패한 셋 + 보내지 않은(이미 나간 것 포함) 다섯").hasSize(8));
        assertThat(보낸.get()).isLessThanOrEqualTo(7);
    }
```

`OpenFgaRelationTupleCheckerTest` 끝에 더한다(이 클래스의 `writer`·`bootstrapper` 와 import 를 쓴다. `IntStream`·`Collectors`·`Set` import 가 없으면 더한다):

```java
    @Test
    @DisplayName("묶음을 동시에 물어도 답은 하나씩 물을 때와 같다")
    void 동시에_물어도_답이_같다() {
        // given — 120줄을 쓰고, 그 120줄과 없는 80줄을 함께 묻는다(묶음 넷)
        Set<RelationTuple> 있는것 = IntStream.range(0, 120)
                .mapToObj(i -> RelationTuple.directMember("u" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.apply(TupleDelta.writeOnly(있는것)).block();
        Set<RelationTuple> 물을것 = new java.util.HashSet<>(있는것);
        IntStream.range(0, 80).forEach(i -> 물을것.add(RelationTuple.directMember("none" + i, "DEV002")));

        // when
        var 답 = new OpenFgaRelationTupleChecker(bootstrapper, 4).existing(물을것).block();

        // then
        assertThat(답).containsExactlyInAnyOrderElementsOf(있는것);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*BreakerTest'`
Expected: 컴파일 실패 — 3인자 `보내되_연속_실패면_멈춘다` 가 없다.

- [ ] **Step 3: 구현한다**

`OpenFgaProperties` 에 더한다:

```java
    /** Check·Write 묶음을 동시에 이만큼 보낸다(설계 2026-10-02 §4.3). OpenFGA 가 버거우면 낮춘다. */
    private int requestConcurrency = 4;
```

`OpenFgaRelationTupleChecker` — `@RequiredArgsConstructor` 를 지우고 필드와 생성자를 이것으로:

```java
    private final StoreBootstrapper bootstrapper;
    /** 동시에 보내는 묶음 수(설계 2026-10-02 §4.3). */
    private final int 동시;

    /** 묶음을 하나씩 묻는다 — 테스트용. 운영 결선은 {@code openfga.request-concurrency} 를 넘긴다. */
    public OpenFgaRelationTupleChecker(StoreBootstrapper bootstrapper) {
        this(bootstrapper, 1);
    }

    public OpenFgaRelationTupleChecker(StoreBootstrapper bootstrapper, int 동시) {
        this.bootstrapper = bootstrapper;
        this.동시 = Math.max(1, 동시);
    }
```

  `existing` 의 `.concatMap(chunk -> checkChunk(storeId, chunk))` 를 `.flatMap(chunk -> checkChunk(storeId, chunk), 동시)` 로 바꾼다(답은 집합이라 순서가 상관없다).

`OpenFgaConfig#relationTupleChecker` 를 바꾼다:

```java
    @Bean
    public RelationTupleChecker relationTupleChecker(StoreBootstrapper bootstrapper, OpenFgaProperties properties) {
        return new OpenFgaRelationTupleChecker(bootstrapper, properties.getRequestConcurrency());
    }
```

`OpenFgaRelationTupleWriter`:
- import `reactor.util.function.Tuples` 를 더한다.
- `apply` 의 `.then(보내되_연속_실패면_멈춘다(batches, this::applyBatch));` 를 `.then(보내되_연속_실패면_멈춘다(batches, this::applyBatch, properties.getRequestConcurrency()));` 로.
- `보내되_연속_실패면_멈춘다` 를 둘로 바꾼다(본문의 차단기 규칙은 그대로, 받는 자리만 순서대로 받은 결과로 바뀐다):

```java
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send) {
        return 보내되_연속_실패면_멈춘다(batches, send, 1);
    }

    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send, int 동시) {
        int 동시_수 = Math.max(1, 동시);
        return Mono.defer(() -> {
            AtomicInteger 연속_실패 = new AtomicInteger();
            AtomicReference<TupleWriteResult> 지금까지 = new AtomicReference<>(TupleWriteResult.empty());
            return Flux.fromIterable(batches)
                    .index()
                    // 지우기 묶음을 다 보낸 뒤 쓰기 묶음을 보낸다(batchesFor 순서) — 같은 종류 안에서만 동시에 보낸다
                    .windowUntilChanged(indexed -> indexed.getT2().delete())
                    .concatMap(같은_종류 -> 같은_종류.flatMapSequential(
                            indexed -> send.apply(indexed.getT2()).map(result -> Tuples.of(indexed.getT1(), result)),
                            동시_수))
                    .concatMap(indexed -> {
                        TupleWriteResult result = indexed.getT2();
                        TupleWriteResult 누적 = 지금까지.accumulateAndGet(result, OpenFgaRelationTupleWriter::merge);
                        if (!한_줄도_못_살렸다(result)) {
                            연속_실패.set(0);
                            return Mono.just(누적);
                        }
                        if (연속_실패.incrementAndGet() < 연속_실패_한도) {
                            return Mono.just(누적);
                        }
                        List<Batch> 남은_배치 = batches.subList(Math.toIntExact(indexed.getT1()) + 1, batches.size());
                        TupleWriteResult partial = 남은_배치.stream()
                                .map(batch -> batch.failed("연속 실패로 보내지 않음"))
                                .reduce(누적, OpenFgaRelationTupleWriter::merge);
                        return Mono.<TupleWriteResult>error(new TupleWriteAbortedException(
                                "OpenFGA 쓰기 배치가 %d번 연달아 실패해 남은 %d개 배치를 보내지 않고 멈췄다 — 마지막 오류: %s"
                                        .formatted(연속_실패_한도, 남은_배치.size(), result.failures().get(0).reason()),
                                partial));
                    })
                    .last(TupleWriteResult.empty());
        });
    }
```

- 자바독 첫 문장 "배치를 차례로 보낸다." 를 이것으로 바꾼다:
  `같은 종류(지우기·쓰기) 안에서 {@code 동시}개까지 동시에 보내고, 결과는 보낸 순서대로 센다(설계 2026-10-02 §4.3). 지우기 묶음을 다 보낸 뒤 쓰기 묶음을 보낸다.`
  그리고 이 문단을 더한다: `<p>멈추면 이미 나간 묶음(최대 동시 수 − 1)은 결과를 기다리지 않고 "보내지 않음"으로 센다 — 실제로 반영됐더라도 쓰기·지우기가 멱등이라 다음 회차(LDAP)·다음 같은 대상 쓰기(SCIM)가 같은 결과로 맞춘다.`

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :authz-openfga:test --tests '*BreakerTest' --tests '*SplitTest' --tests '*OpenFgaRelationTupleCheckerTest' --tests '*BatchOrderTest'` → PASS.
Run: `./gradlew :authz-openfga:cleanTest :authz-openfga:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaProperties.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java \
  authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaConfig.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterBreakerTest.java \
  authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleCheckerTest.java
git commit -F - <<'EOF'
feat: OpenFGA Check·Write 묶음을 동시에(openfga.request-concurrency, 기본 4) — 지우기 뒤 쓰기, 차단기는 보낸 순서대로

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 8: SCIM 재적재 스냅샷은 락을 반납한 뒤에 (P8)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java`

**Interfaces:**
- Consumes: `LeaseKeeper`(Task 1).
- Produces: `public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose, Mono<Mono<SyncOutcome>> work, Consumer<SyncRun> onFinished)`
  — 락 안의 일이 "반납 뒤 할 일"을 내놓는다. 순서: 작업 → 반납 → 반납 뒤 할 일 → 기록. 기존 `startLocked` 는 `work.map(Mono::just)` 로 이것을 부른다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SyncJobsTest` 끝에 더한다:

```java
    // ---------- 반납 뒤 할 일 (설계 2026-10-02 §5) ----------

    @Test
    @DisplayName("반납 뒤 할 일은 락을 반납한 뒤 돌고, 그 결론이 기록된다")
    void 반납_뒤_할_일은_락_밖에서_돈다() {
        // given
        AtomicReference<Boolean> 그때_락 = new AtomicReference<>();
        Mono<Mono<SyncOutcome>> 작업 = Mono.just(Mono.fromSupplier(() -> {
            그때_락.set(lock.isHeld());
            return SyncOutcome.noChange();
        }));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLockedThen(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, 작업, run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(그때_락.get()).as("반납 뒤에 돈다").isFalse();
    }

    @Test
    @DisplayName("반납 뒤 할 일이 실패하면 그 사유로 FAILED 를 기록한다")
    void 반납_뒤_할_일이_실패하면_FAILED() {
        // given
        Mono<Mono<SyncOutcome>> 작업 = Mono.just(Mono.error(new IllegalStateException("뒤에서 실패")));

        // when
        SyncRun 끝난것 = runs.awaitFinished(jobs.startLockedThen(SyncSource.SCIM, SyncTrigger.REBUILD,
                MutationLock.LockPurpose.REBUILD, 작업, run -> {
                }).block().runId());

        // then
        assertThat(끝난것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(끝난것.message()).isEqualTo("뒤에서 실패");
        assertThat(lock.released).hasValue(1);
    }
```

`ScimRebuildUseCaseTest` 끝에 더한다(import `java.util.concurrent.atomic.AtomicReference`, `dev.starryeye.organization.core.model.TupleSnapshot`,
`reactor.core.publisher.Mono` 가 없으면 더한다. 필드 `state`·`writer`·`scanner`·`snapshots`·`lock`·`runs`, 상수 `NOW`, 도우미 `재적재한다` 는 이미 있다):

```java
    @Test
    @DisplayName("튜플 스냅샷은 락을 반납한 뒤에 저장한다 — 저장하는 동안 SCIM 쓰기를 막지 않는다(점검 P8)")
    void 스냅샷은_락을_반납한_뒤_저장한다() {
        // given
        AtomicReference<Boolean> 저장할때_락 = new AtomicReference<>();
        FakeSnapshotRepository 지켜보는_저장소 = new FakeSnapshotRepository() {
            @Override
            public Mono<Void> save(TupleSnapshot snapshot) {
                return Mono.defer(() -> {
                    저장할때_락.set(lock.isHeld());
                    return super.save(snapshot);
                });
            }
        };
        var 재적재 = new ScimRebuildUseCase(state, writer, scanner, 지켜보는_저장소,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        // when
        var run = runs.awaitFinished(재적재.start(ScimRebuildMode.TUPLES).block().runId());

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(저장할때_락.get()).as("저장할 때 락은 이미 풀려 있다").isFalse();
        assertThat(지켜보는_저장소.saved).hasSize(1);
    }

    @Test
    @DisplayName("반납 뒤 스냅샷 저장이 실패하면 FAILED 이고 사유에 장부는 맞췄다고 남긴다")
    void 반납_뒤_저장_실패는_FAILED() {
        // given
        snapshots.failSave(new IllegalStateException("스로틀"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).startsWith("장부는 맞췄다 — 스냅샷 저장 실패: ").contains("스로틀");
        assertThat(lock.released).hasValue(1);
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsTest' --tests '*ScimRebuildUseCaseTest'`
Expected: 컴파일 실패 — `startLockedThen` 이 없다. (`스냅샷은_락을_반납한_뒤_저장한다` 는 지금 코드로는 락 안에서 저장해 실패한다.)

- [ ] **Step 3: 구현한다**

`SyncJobs`:

1. `startLocked` 를 위임으로 바꾸고 `startLockedThen` 을 더한다:

```java
    public Mono<SyncRun> startLocked(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                     Mono<SyncOutcome> work, Consumer<SyncRun> onFinished) {
        return startLockedThen(source, trigger, purpose, work.map(Mono::just), onFinished);
    }

    /**
     * {@link #startLocked} 와 같되, 락 안의 일이 <b>"반납 뒤 할 일"</b>을 내놓는다(설계 2026-10-02 §5). 순서: 작업 → 반납 → 반납 뒤 할 일 → 기록.
     * 락이 필요 없는 마무리(SCIM 재적재의 튜플 스냅샷 저장 등)를 락 밖으로 빼 그동안 다른 쓰기를 막지 않는다. 반납 뒤 할 일은 기한·서버 종료 경주 밖에서
     * 돈다 — 짧은 마무리만 둔다. 실패하면 그 사유로 FAILED 다.
     */
    public Mono<SyncRun> startLockedThen(SyncSource source, SyncTrigger trigger, MutationLock.LockPurpose purpose,
                                         Mono<Mono<SyncOutcome>> work, Consumer<SyncRun> onFinished) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            return lock.acquire(purpose)
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), false))
                    .flatMap(lease -> 띄운다(source, trigger,
                            keeper.keep(lease, work, "작업 도중 리스 상실"),
                            run -> 남은_기록을_닫는다(source, run.runId()),
                            () -> lock.release(lease),
                            onFinished));
        });
    }
```

2. 패키지 전용 `start` 둘은 그대로 두고 본문을 `띄운다(source, trigger, work.map(Mono::just), run -> Mono.empty(), release, onFinished)` 로 바꾼다
   (4인자는 `onFinished` 자리에 `run -> { }`). 6인자 `start(..., 먼저, release, onFinished)` 는 이름을 `띄운다` 로 바꾸고 `work` 의 타입을
   `Mono<Mono<SyncOutcome>>` 로 바꾼다 — 같은 이름이면 제네릭 소거로 서명이 겹친다. 그 안에서 `끝까지_돌린다(run, work, 먼저, release)` 를 부르는 것은 그대로다.
3. `끝까지_돌린다` 를 이것으로 바꾸고 `실패로` 를 더한다:

```java
    private Mono<SyncRun> 끝까지_돌린다(SyncRun run, Mono<Mono<SyncOutcome>> work,
                                    Function<SyncRun, Mono<Void>> 먼저, Supplier<Mono<Void>> release) {
        Mono<Mono<SyncOutcome>> 종료되면 = 종료.asMono().then(Mono.error(() -> new IllegalStateException(종료_사유)));
        Mono<Mono<SyncOutcome>> 일 = Mono.defer(() -> 먼저.apply(run)).then(work);
        return Mono.firstWithSignal(종료되면, 일)
                .timeout(timeout, Mono.error(() -> new IllegalStateException("기한 초과 — " + 사람말로(timeout))))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("작업이 결과 없이 끝났다")))
                .onErrorResume(error -> Mono.just(Mono.just(실패로(run, error))))
                .flatMap(반납_뒤 -> 반납한다(release).thenReturn(반납_뒤))
                // 반납 뒤 할 일(설계 2026-10-02 §5) — 락 없이 돈다
                .flatMap(반납_뒤 -> 반납_뒤
                        .switchIfEmpty(Mono.error(() -> new IllegalStateException("반납 뒤 할 일이 결과 없이 끝났다")))
                        .onErrorResume(error -> Mono.just(실패로(run, error))))
                .flatMap(outcome -> runs.finish(run, outcome))
                .doOnNext(finished -> log.info("[{}] 작업 끝: status={} written={} deleted={} failed={}",
                        finished.runId(), finished.status(), finished.writtenCount(),
                        finished.deletedCount(), finished.failureCount()))
                .onErrorResume(error -> {
                    log.error("[{}] 작업 결과를 기록하지 못했다 — 기록이 RUNNING 으로 남는다", run.runId(), error);
                    return Mono.empty();
                });
    }

    /** 메시지 없는 오류도 있다 — 이유를 비워 두면 기록만 보고는 무엇이 터졌는지 모른다. */
    private static SyncOutcome 실패로(SyncRun run, Throwable error) {
        String 사유 = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        log.error("[{}] 작업 실패: {}", run.runId(), 사유, error);
        return SyncOutcome.failed(사유);
    }
```

`ScimRebuildUseCase`:

```java
    public Mono<SyncRun> start(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);
        return jobs.startLockedThen(SyncSource.SCIM, triggerFor(mode), MutationLock.LockPurpose.REBUILD,
                Mono.defer(() -> rebuild(mode)), run -> {
                });
    }

    /** 락 안에서 할 일을 하고 "반납 뒤 할 일"을 내놓는다 — 튜플 스냅샷 저장은 락을 반납한 뒤에 한다(설계 2026-10-02 §5, 점검 P8). */
    private Mono<Mono<SyncOutcome>> rebuild(ScimRebuildMode mode) {
        return mode == ScimRebuildMode.WIPE ? wipe().map(Mono::just) : reloadTuples();
    }

    private Mono<Mono<SyncOutcome>> reloadTuples() {
        return state.loadAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 빈_조직도면_멈춘다)
                    .map(reconciliation -> reconciliation.held()
                            ? Mono.just(SyncOutcome.failed(reconciliation.heldReason()))
                            : commitTuples(reconciliation));
        });
    }

    /**
     * 스냅샷에는 <b>장부에 실제로 있다고 볼 줄</b>만 담는다(설계 §3.1 4단계). 지우기가 차단기로 멈췄어도 같다 — 스냅샷을 남긴 뒤 FAILED 로 기록한다.
     * <b>락을 반납한 뒤에 돈다</b>(설계 2026-10-02 §5) — SCIM 쓰기 경로는 이 스냅샷을 읽지 않는다. 저장이 실패하면 FAILED 다 — 장부는 이미 맞췄다.
     */
    private Mono<SyncOutcome> commitTuples(TupleReconciler.Reconciliation reconciliation) {
        return Mono.defer(() -> {
                    Instant now = clock.instant();
                    TupleSnapshot snapshot = new TupleSnapshot(
                            SnapshotIds.generate(now, SyncSource.SCIM), now, SyncSource.SCIM, reconciliation.ledger());
                    return snapshots.save(snapshot).thenReturn(reconciliation.outcome(snapshot.id()));
                })
                .onErrorMap(error -> new IllegalStateException("장부는 맞췄다 — 스냅샷 저장 실패: "
                        + (error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName()), error));
    }
```

  클래스 자바독의 "요청과 떼어 돈다" 문단 끝에 `튜플 스냅샷 저장은 락을 반납한 뒤에 한다({@link SyncJobs#startLockedThen}).` 를 더한다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*SyncJobsTest' --tests '*ScimRebuild*'` → PASS.
Run: `./gradlew :core:cleanTest :core:test` → PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/SyncJobsTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java
git commit -F - <<'EOF'
feat: SCIM 재적재 튜플 스냅샷은 락을 반납한 뒤 저장(점검 P8) — SyncJobs#startLockedThen 의 반납 뒤 할 일

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 9: 규모 테스트(10만 명 조직 삭제)와 README

**Files:**
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java`
- Modify: `README.md`

**Interfaces:** 없음(검증·문서).

- [ ] **Step 1: 규모 테스트를 더한다**

`ScimGroupMemberPatchScaleTest`:
- import 에 `dev.starryeye.organization.core.port.MutationLock`, `org.springframework.http.HttpStatus`, `java.util.concurrent.CompletableFuture`,
  `java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicInteger`, `static org.awaitility.Awaitility.await` 를 더한다.
- 필드 `@Autowired MutationLock lock;` 를 더한다.
- 클래스 끝에 더한다:

```java
    private void 직원을_만든다(String userName, HttpStatus 기대) {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"%s","active":true}
                        """.formatted(userName))
                .exchange().expectStatus().isEqualTo(기대);
    }

    @Test
    @Order(6)
    @DisplayName("10만 명 조직 삭제는 직원을 읽지 않고 Check 하지 않는다. 그동안 다른 쓰기는 503 이고, 끝나면 받아진다(점검 C6)")
    void 큰_조직을_지운다() throws Exception {
        // given
        counter.reset();
        checks.reset();
        AtomicInteger 들여다봄 = new AtomicInteger();
        long 시작 = System.currentTimeMillis();

        // when — 삭제를 따로 걸고, 락이 잡힌 동안 다른 쓰기를 보낸다
        CompletableFuture<Void> 삭제 = CompletableFuture.runAsync(() ->
                client.mutate().responseTimeout(Duration.ofMinutes(10)).build()
                        .delete().uri("/scim/v2/Groups/" + 조직).exchange().expectStatus().isNoContent());
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            들여다봄.incrementAndGet();
            return lock.peek().blockOptional().isPresent();
        });
        직원을_만든다("during-delete", HttpStatus.SERVICE_UNAVAILABLE);
        삭제.get(10, TimeUnit.MINUTES);

        // then
        읽은양을_찍는다("큰 조직 삭제", 시작);
        assertThat(counter.getItems.get() - 들여다봄.get()).as("멤버 직원을 읽지 않는다").isLessThanOrEqualTo(20);
        assertThat(checks.checkedTuples.get()).as("Check 없이 지운다").isZero();
        assertThat(counter.scannedItems.get()).as("조직 파티션을 한 번만 훑는다").isLessThanOrEqualTo(전체 + 20);
        assertThat(state.findGroupHeader(조직).blockOptional()).isEmpty();
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isZero();
        assertThat(state.findGroupIdsContaining(MemberRef.user(멤버(5))).collectList().block()).isEmpty();
        assertThat(check("user:newbie1", "member", "group:" + 조직)).isFalse();

        // and — 끝난 뒤에는 쓰기가 받아진다
        직원을_만든다("after-delete", HttpStatus.CREATED);
    }
```

  (클래스 자바독의 첫 문단 끝에 `조직 삭제(점검 C6)도 같은 조직으로 잰다 — 마지막 순서다.` 를 더한다.)

- [ ] **Step 2: 규모 테스트를 돌린다**

Run: `./gradlew :app-scim:scaleTest --tests '*ScimGroupMemberPatchScaleTest'` → PASS. 출력의 `큰 조직 삭제:` 줄(시간·Query·훑은 아이템·GetItem·Check)을 보고서에 붙인다.

- [ ] **Step 3: README 를 고친다**

1. "### app-scim 여러 대 띄우기(동시성 제어)" 절의 표 아래 "락 관련 설정 세 개" 표에서 `dynamodb.lock-renew-interval` 행의 설명을 바꾼다:
   `락을 쥔 동안 리스를 갱신하는 주기 — SCIM 쓰기·재적재·동기화 모두. TTL보다 충분히 짧아야 한다`
2. 그 표 바로 아래의 "**재적재가 도중에 리스를 잃으면 중단하고 `FAILED`로 기록한다.**" 문단 앞에 새 문단을 넣는다:

```markdown
**큰 변경도 락을 지키며 끝까지 간다.** 10만 명 조직 삭제처럼 오래 걸리는 SCIM 쓰기는 락을 쥔 동안 리스를 `lock-renew-interval`마다
갱신해 TTL(30초)을 넘겨도 끝난다. 그동안 다른 SCIM 쓰기는 503이고 IdP가 재시도한다. OpenFGA 쓰기 직전과 DynamoDB 커밋 직전에 리스를 늘
다시 확인하고(바뀐 튜플이 없어도), 확인이 실패하면 저장하지 않고 503이다 — 오래 멈춘 요청이 다른 인스턴스가 저장한 비활성화를 덮지 않는다.
IdP가 연결을 끊어도 그 변경은 커밋까지 마치고 락을 반납한다. 락 획득 응답을 잃어 SDK가 같은 요청을 다시 보내도 자기 락에 막히지 않는다.

**조직 삭제는 계산하지 않는다.** 조직 파티션을 한 번 읽어 멤버를 얻고, 상위 조직은 아이디만 읽는다. 그 조직을 언급하는 튜플을 Check 없이
"없으면 무시"로 지우고, DynamoDB는 묶어서(25개씩) 지우며 조직 META를 맨 마지막에 지운다 — 중간에 멈춰도 IdP의 DELETE 재시도가 남은 것을
마저 지운다. 멤버 전원 빼기·빈 교체도 빠지는 멤버는 Check·직원 읽기 없이 지운다.
```

3. 같은 절의 "이 락은 완벽한 상호 배제를 보장하지 않는다(반납 자체의 실패, 구독 취소 등 좁은 틈이 있다)" 를
   "이 락은 완벽한 상호 배제를 보장하지 않는다(반납 자체의 실패, 커밋 직전 확인과 커밋 사이에 30초 넘게 멈추는 경우 등 좁은 틈이 있다)" 로 바꾼다.
4. 지표 표의 `scim.lock.lease_lost` 설명을 바꾼다:
   `쥐고 있어야 할 리스를 잃었다 — 작업·쓰기 도중 갱신 실패, 쓰기 직전·커밋 직전 재확인 실패, 반납 실패. **응답에 흔적이 없거나(반납 실패) 503 뿐이다.** 0이 아니면 락이 TTL만큼 묶였거나 두 인스턴스가 겹쳤을 수 있다`
5. 위 2 에서 넣은 "**조직 삭제는 계산하지 않는다.**" 문단 바로 뒤에 새 문단을 하나 더 넣는다(README 에는 OpenFGA 설정 표가 따로 없다):

```markdown
**OpenFGA 요청은 동시에 여러 개 보낸다.** Check·Write 묶음을 `openfga.request-concurrency`(기본 4)만큼 동시에 보낸다 — 지우기 묶음을 다
보낸 뒤 쓰기 묶음을 보낸다. 재적재·LDAP 동기화도 함께 빨라진다. OpenFGA가 버거우면 낮춘다. DynamoDB 묶음 요청(BatchGet·BatchWrite)은
처리 못 한 키를 5번까지(100ms부터 두 배씩) 다시 보내고, 그래도 남으면 실패한다.
```

6. 확인: `grep -n "획득 도중 취소\|구독 취소" README.md` 결과가 없어야 한다.

- [ ] **Step 4: 점검 문서에 해결을 표시한다**

`docs/superpowers/specs/2026-09-28-full-audit.md` 요약 표의 C6·M8·P8·S14·S3 행 끝 칸 설명 뒤에 ` **→ 해결(2026-10-02, 슬라이드 ③-1)**` 을 붙인다(C1 행과 같은 모양).
S3·S14 행이 사소 표(§4.4)에만 있으면 그 행의 끝 칸에 붙인다.

- [ ] **Step 5: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java README.md \
  docs/superpowers/specs/2026-09-28-full-audit.md
git commit -F - <<'EOF'
docs: 큰 변경도 락을 지키며 끝까지(갱신·커밋 직전 확인·끊겨도 끝까지), 조직 삭제 비용, OpenFGA 동시 요청 — 10만 명 조직 삭제 규모 테스트, 점검 C6·M8·P8·S3·S14 해결 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 **한 번에 하나씩** 돌린다(백그라운드면 완료 알림을 받은 뒤 다음).
- 결과(테스트 수·시간, 10만 명 조직 삭제 실측)를 스펙 §9 에 적고 커밋·푸시한 뒤 PR.
