# 점검 ⑥-2 쓰기 길 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전역 락 하나를 유지한 채 SCIM 쓰기가 락을 비워 두거나 운 나쁘게 밀려 503 이 되는 일을 줄이고, 아무 말도 하지 않는 요청은 줄을 서지 않게 하고, OpenFGA 쓰기 결과를 선형 비용으로 모은다.
- 락 획득: 서버 안 줄(온 순서대로) + 서버끼리 full-jitter 백오프(10ms~100ms), 재적재·동기화 중엔 바로 503 (P3, ⑤-1 이월)
- 버리는 속성만 있는 직원 PATCH 는 락 없이 지금 모습을 돌려준다 (⑤-2 이월)
- OpenFGA 쓰기 결과는 누적기 하나에 모은다 (P6)

**Architecture:**
- **core.**
  - 새 `LocalWriteQueue`(서버 안 줄)와 `AcquireBackoff`(백오프 + 남은 한도 + 긴 작업이면 멈춤)를 둔다.
  - `IncrementalSyncUseCase.잡고_돌린다` 가 그 둘을 쓴다: 줄에서 차례를 받고 → 백오프로 락을 잡고 → 일하고 반납한 뒤 차례를 넘긴다.
  - `LockUnavailableException` 은 쥔 쪽의 용도를 싣는다.
- **connector-scim.**
  - `ScimPatchApplier` 의 직원 쪽을 "겨눈 속성(Target) → 저장하는 속성인가(storedAttribute)" 로 나눈다. 적용과 판정이 같은 규칙 하나를 쓴다.
  - `ScimUserHandler.patch` 는 판정이 참이면 락 없이 응답한다.
- **authz-openfga.** `OpenFgaRelationTupleWriter` 의 두 `reduce` 를 `collect(ResultAccumulator)` 로 바꾼다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor(`retryWhen`·`Retry.from`·`Sinks.One`·`VirtualTimeScheduler`), Lombok, AWS SDK v2 DynamoDB, OpenFGA Java SDK, JUnit 5, AssertJ, StepVerifier, WebTestClient·WebClient, Testcontainers(DynamoDB Local·OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-07-write-paths-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다. 합친 `// when, then` 도 된다. 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.**
  - 이름·주석·메시지는 한국어로, 주변처럼 평서문으로 쓴다.
  - `@DisplayName` 은 한국어 문장이다.
  - 클래스 이름은 영어다. 메서드 이름은 그 파일의 관례를 따른다 — `ScimPatchApplier` 는 영어, core 유스케이스는 한국어.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.**
  - 한 번에 하나씩 포그라운드로 돌린다.
  - 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
  - 예외: Task 7 은 정한 규모 테스트 클래스 하나만 돌린다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML 에서 옮긴다. 어림하지 않는다.
- **락 획득 규칙(스펙 §3).** 다음 값과 규칙은 계획의 값 그대로 쓴다:
  - 백오프는 full jitter 다. n번째(0부터) 재시도 대기는 `0 ~ min(100ms, 10ms × 2ⁿ)` 이다.
  - 한도는 `lock-acquire-timeout` 이고 줄에 선 순간부터 잰다.
  - 다음 시도 전, 걸린 시간 + 이번 대기 ≥ 한도면 시도하지 않는다.
  - 날아가는 중인 획득은 끊지 않는다 — `timeout` 을 락 획득에 걸지 않는다.
  - 재시도는 `lock.acquire(WRITE)` 가 만든 같은 `Mono` 를 다시 구독한다(같은 토큰, S14).
  - 쥔 쪽이 `REBUILD`·`SYNC` 면 다시 시도하지 않는다. 용도를 모르면(null) 다시 시도한다.
  - 줄에서 한도를 넘기면 `LockUnavailableException.잡혀_있다(WRITE)`(2초)다.
  - 차례는 반납까지 끝난 뒤(또는 획득 실패 뒤), **결과를 내기 전에** 넘긴다. 결과를 낸 뒤에 넘기면 IdP 의 다음 요청이 아직 넘어가지 않은 차례 뒤에 선다.
- **시간은 스케줄러 시계로 잰다.** 한도·경과는 `Schedulers.parallel().now(TimeUnit.MILLISECONDS)` 로 잰다. 가상 시간 테스트가 같은 시계를 본다.
- **동작을 바꾸지 않는 곳.** LDAP 동기화(`SyncJobs`)·재적재(`RebuildUseCase`·`ScimRebuildUseCase`)·아카이빙 `peek` 의 락 획득, 조직 PATCH, 직원 PUT.

## Review Focus

1. **줄의 차례를 쥔 요청이 실패로 끝나도 차례가 넘어간다.** 락 저장소 오류로 획득 실패, 락 안 계산이 던지는 400 같은 경우다. 다음 요청이 줄에서 묶여 한도를 다 쓰고 503 이 되면 안 된다 — Task 3 테스트 둘.
2. **한도 0.** 테스트와 일부 결선이 `Duration.ZERO` 를 쓴다. 줄이 비면 바로 차례를 받고 시도는 한 번이다(재시도 없음) — Task 2·3 테스트.
3. **쥔 쪽 용도를 모름(null).** 조건 실패 때 돌려받은 줄에 `purpose` 가 없으면 쓰기 경합처럼 다시 시도한다 — Task 2 테스트.
4. **경로 없는 값의 키가 모두 저장하지 않는 것인 PATCH.** 예: `{"op":"replace","value":{"nickName":"k","x-custom":1}}`. 락 없이 200 이고, 관찰자에 `nickName`·`other` 가 간다 — Task 5 테스트.
5. **버리는 속성만 있는 PATCH 를 없는 직원에.** 락 없이 404 다 — Task 5 테스트.

검토자가 코드로 볼 것: `LocalWriteQueue` 에서 차례를 주는 순간과 대기자가 빠지는 순간(시간 초과·취소)이 겹칠 때 차례를 잃지 않는가. 상태 `WAITING→GRANTED→TAKEN`, `WAITING/GRANTED→ABANDONED` 의 CAS 를 본다. 이 경합은 결정적으로 재현할 수 없어 테스트가 없다.

---

### Task 1: 서버 안 줄 `LocalWriteQueue` (core)

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/LocalWriteQueue.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LocalWriteQueueTest.java`

**Interfaces:**
- Consumes: `LockUnavailableException.잡혀_있다(MutationLock.LockPurpose)` (있음), `MutationLock.LockPurpose.WRITE` (있음)
- Produces:
  - `public final class LocalWriteQueue`
  - `public Mono<LocalWriteQueue.Turn> 줄을_선다(Duration 한도)`
  - `public final class Turn` — `public boolean 밀렸다()`, `public void 넘긴다()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package dev.starryeye.organization.core.usecase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 서버 안의 SCIM 쓰기 줄(설계 2026-10-07 §3.1). 차례는 온 순서대로, 끝난 요청이 넘기면 바로 다음 요청에 간다. */
class LocalWriteQueueTest {

    private final LocalWriteQueue 줄 = new LocalWriteQueue();

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    /** 줄에 선 요청 하나 — 차례를 받으면 이름을 남기고 차례를 담는다. 실패하면 실패를 담는다. */
    private static final class 대기자 {
        final AtomicReference<LocalWriteQueue.Turn> 차례 = new AtomicReference<>();
        final AtomicReference<Throwable> 실패 = new AtomicReference<>();
        Disposable 구독;

        static 대기자 세운다(LocalWriteQueue 줄, Duration 한도, List<String> 받은_순서, String 이름) {
            대기자 나 = new 대기자();
            나.구독 = 줄.줄을_선다(한도).subscribe(차례 -> {
                받은_순서.add(이름);
                나.차례.set(차례);
            }, 나.실패::set);
            return 나;
        }
    }

    @Test
    @DisplayName("줄이 비어 있으면 바로 차례를 받고 밀린 것이 아니다")
    void 비어_있으면_바로_받는다() {
        // when
        LocalWriteQueue.Turn 차례 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));

        // then
        assertThat(차례).isNotNull();
        assertThat(차례.밀렸다()).isFalse();
    }

    @Test
    @DisplayName("앞 요청이 넘기면 온 순서대로 차례를 받는다 — 줄에서 기다린 요청은 밀린 것이다")
    void 온_순서대로_받는다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");

        // when
        첫째.넘긴다();

        // then — 둘째만 받는다. 셋째는 둘째가 넘길 때까지 기다린다
        assertThat(받은_순서).containsExactly("둘째");
        assertThat(둘째.차례.get().밀렸다()).isTrue();

        // when
        둘째.차례.get().넘긴다();

        // then
        assertThat(받은_순서).containsExactly("둘째", "셋째");
    }

    @Test
    @DisplayName("넘긴다를 두 번 불러도 한 번만 넘긴다 — 줄의 요청을 건너뛰지 않는다")
    void 두_번_넘겨도_한_번이다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");

        // when
        첫째.넘긴다();
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("둘째");
    }

    @Test
    @DisplayName("한도 안에 차례가 오지 않으면 줄에서 빠져 503(2초)이다 — 앞에 선 것이 이 서버의 SCIM 쓰기다")
    void 한도가_지나면_빠진다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(1), 받은_순서, "둘째");

        // when
        시간.advanceTimeBy(Duration.ofMillis(999));
        Throwable 한도_전 = 둘째.실패.get();
        시간.advanceTimeBy(Duration.ofMillis(1));

        // then
        assertThat(한도_전).isNull();
        assertThat(둘째.실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(받은_순서).isEmpty();
    }

    @Test
    @DisplayName("한도가 지나 빠진 요청은 건너뛰고 그다음 요청에 차례를 준다")
    void 빠진_요청을_건너뛴다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자.세운다(줄, Duration.ofSeconds(1), 받은_순서, "둘째");
        대기자 셋째 = 대기자.세운다(줄, Duration.ofSeconds(10), 받은_순서, "셋째");
        시간.advanceTimeBy(Duration.ofSeconds(1));

        // when
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("셋째");
        assertThat(셋째.차례.get().밀렸다()).isTrue();
    }

    @Test
    @DisplayName("기다리다 취소한 요청은 줄에서 빠진다 — 다음 요청이 차례를 받고, 줄이 비면 새 요청은 바로 받는다")
    void 취소하면_빠진다() {
        // given
        List<String> 받은_순서 = new CopyOnWriteArrayList<>();
        LocalWriteQueue.Turn 첫째 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));
        대기자 둘째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "둘째");
        대기자 셋째 = 대기자.세운다(줄, Duration.ofSeconds(3), 받은_순서, "셋째");
        둘째.구독.dispose();

        // when
        첫째.넘긴다();

        // then
        assertThat(받은_순서).containsExactly("셋째");

        // when
        셋째.차례.get().넘긴다();
        LocalWriteQueue.Turn 새_요청 = 줄.줄을_선다(Duration.ofSeconds(3)).block(Duration.ofSeconds(1));

        // then
        assertThat(새_요청.밀렸다()).as("줄이 비었으니 바로 받는다").isFalse();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*LocalWriteQueueTest'`
Expected: 컴파일 실패 — `LocalWriteQueue` 가 없다.

- [ ] **Step 3: 구현한다**

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버 안의 SCIM 쓰기 줄(설계 2026-10-07 §3.1). 온 순서대로 차례를 준다 — 차례를 받은 요청 하나만 전역 락(DynamoDB)을 시도하고, 그 요청이
 * 끝나면(반납까지) 바로 다음 요청에 차례가 간다. 같은 서버 안에서는 반납과 다음 획득 사이에 빈 시간이 없고, DynamoDB 락 항목을 두드리는 것은
 * 서버마다 많아야 하나다.
 *
 * <p>줄에서 기다리는 동안에는 아무것도 부르지 않으므로 한도가 지나 빠져도 새는 것이 없다. 차례를 주는 순간과 빠지는 순간이 겹쳐도 차례를
 * 잃지 않는다 — 시간 초과와 겹치면 받은 차례를 쓰고, 취소와 겹치면 다음 요청에 넘긴다.
 */
public final class LocalWriteQueue {

    private static final int WAITING = 0;
    private static final int GRANTED = 1;
    private static final int TAKEN = 2;
    private static final int ABANDONED = 3;

    private final Object 잠금 = new Object();
    private final Deque<Waiter> 대기열 = new ArrayDeque<>();
    /** 지금 누군가 차례를 쥐고 있는가. {@link #잠금} 안에서만 읽고 쓴다. */
    private boolean 차례가_나가_있다;

    /**
     * 줄을 선다. 차례가 오면 {@link Turn} 을 내고, {@code 한도} 안에 오지 않으면 줄에서 빠져 {@code 잡혀_있다(WRITE)}(503, 2초)다 —
     * 앞에 선 것이 이 서버의 SCIM 쓰기다.
     */
    public Mono<Turn> 줄을_선다(Duration 한도) {
        return Mono.defer(() -> {
            Waiter 나 = new Waiter();
            synchronized (잠금) {
                if (!차례가_나가_있다) {
                    차례가_나가_있다 = true;
                    return Mono.just(new Turn(false));
                }
                대기열.addLast(나);
            }
            return 나.신호.asMono()
                    .timeout(한도, Mono.defer(() -> 시간이_지났다(나)))
                    .doOnNext(차례 -> 나.상태.set(TAKEN))
                    .doOnCancel(() -> 떠났다(나));
        });
    }

    private Mono<Turn> 시간이_지났다(Waiter 나) {
        if (나.상태.compareAndSet(WAITING, ABANDONED)) {
            빼낸다(나);
            return Mono.error(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE));
        }
        // 차례를 주는 순간과 겹쳤다 — 이미 받은 차례를 쓴다
        return Mono.just(나.차례);
    }

    private void 떠났다(Waiter 나) {
        if (나.상태.compareAndSet(WAITING, ABANDONED)) {
            빼낸다(나);
        } else if (나.상태.compareAndSet(GRANTED, ABANDONED)) {
            // 차례를 받았지만 건네받기 전에 떠났다 — 쥔 채로 사라지면 줄이 멈춘다
            나.차례.넘긴다();
        }
    }

    private void 빼낸다(Waiter 나) {
        synchronized (잠금) {
            대기열.remove(나);
        }
    }

    /** 다음 요청에 차례를 준다. 이미 떠난 요청은 건너뛴다. 줄이 비었으면 차례를 거둔다. */
    private void 다음에게() {
        while (true) {
            Waiter 다음;
            synchronized (잠금) {
                다음 = 대기열.pollFirst();
                if (다음 == null) {
                    차례가_나가_있다 = false;
                    return;
                }
            }
            다음.차례 = new Turn(true);
            if (다음.상태.compareAndSet(WAITING, GRANTED)) {
                다음.신호.tryEmitValue(다음.차례);
                return;
            }
        }
    }

    /** 받은 차례. 일이 끝나면(반납까지, 또는 실패 뒤) {@link #넘긴다} 를 부른다 — 여러 번 불러도 한 번만 넘긴다. */
    public final class Turn {

        private final boolean 밀렸다;
        private final AtomicBoolean 넘겼다 = new AtomicBoolean();

        private Turn(boolean 밀렸다) {
            this.밀렸다 = 밀렸다;
        }

        /** 앞에 다른 요청이 있어 줄에서 기다렸는가 — {@code scim.lock.contended} 에 든다. */
        public boolean 밀렸다() {
            return 밀렸다;
        }

        public void 넘긴다() {
            if (넘겼다.compareAndSet(false, true)) {
                다음에게();
            }
        }
    }

    private static final class Waiter {
        final Sinks.One<Turn> 신호 = Sinks.one();
        final AtomicInteger 상태 = new AtomicInteger(WAITING);
        volatile Turn 차례;
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*LocalWriteQueueTest'`
Expected: PASS (6개).

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/LocalWriteQueue.java core/src/test/java/dev/starryeye/organization/core/usecase/LocalWriteQueueTest.java
git commit -F - <<'EOF'
feat: 서버 안 SCIM 쓰기 줄 — 온 순서대로 차례, 반납 뒤 바로 다음 요청, 한도가 지나면 줄에서 빠져 503(2초), 빠지는 순간과 겹쳐도 차례를 잃지 않는다(점검 P3)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 2: 백오프 `AcquireBackoff` + 쥔 용도를 싣는 `LockUnavailableException` (core)

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/AcquireBackoff.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LockUnavailableException.java` (전체 교체, 아래)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AcquireBackoffTest.java` (새)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/LockUnavailableExceptionTest.java` (새)

**Interfaces:**
- Produces:
  - `final class AcquireBackoff`(패키지 전용)
    - `AcquireBackoff(DoubleSupplier 무작위)`, `static AcquireBackoff 무작위로()`
    - `Duration 대기(long n)`
    - `<T> Mono<T> 잡는다(Mono<T> 시도, Duration 한도)`
    - `static long 지금()` — 스케줄러 시계, 밀리초
  - `LockUnavailableException`
    - `public boolean 긴_작업이_쥐었다()`
    - 기존 생성자 셋과 `잡혀_있다` 는 그대로 쓸 수 있다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LockUnavailableExceptionTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 쥔 쪽의 용도가 획득 재시도를 정한다(설계 2026-10-07 §3.3). */
class LockUnavailableExceptionTest {

    @ParameterizedTest
    @EnumSource(value = LockPurpose.class, names = {"REBUILD", "SYNC"})
    @DisplayName("재적재·동기화가 쥐었으면 긴 작업이고 60초다")
    void 재적재_동기화는_긴_작업이다(LockPurpose 용도) {
        // when
        LockUnavailableException 실패 = LockUnavailableException.잡혀_있다(용도);

        // then
        assertThat(실패.긴_작업이_쥐었다()).isTrue();
        assertThat(실패.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("쓰기가 쥐었거나 용도를 모르면 긴 작업이 아니다 — 쓰기 경합으로 보고 다시 시도한다")
    void 쓰기와_모름은_긴_작업이_아니다() {
        // when, then
        assertThat(LockUnavailableException.잡혀_있다(LockPurpose.WRITE).긴_작업이_쥐었다()).isFalse();
        assertThat(LockUnavailableException.잡혀_있다(null).긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("리스를 잃었다").긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("저장소 오류", new RuntimeException()).긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("못 잡았다", Duration.ofSeconds(60)).긴_작업이_쥐었다())
                .as("대기 시간이 아니라 용도로 가른다").isFalse();
    }
}
```

`AcquireBackoffTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 다른 서버가 쥔 락을 다시 시도하는 간격과 한도(설계 2026-10-07 §3.2·§3.3). */
class AcquireBackoffTest {

    /** 늘 상한만큼 기다린다 — 대기 = min(100ms, 10ms × 2ⁿ). */
    private static final AcquireBackoff 상한만큼 = new AcquireBackoff(() -> 1.0);

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    private static Mono<String> 늘_실패하는_시도(AtomicInteger 시도, Supplier<Throwable> 실패) {
        return Mono.defer(() -> {
            시도.incrementAndGet();
            return Mono.error(실패.get());
        });
    }

    @Test
    @DisplayName("n번째 재시도 대기는 0 과 min(100ms, 10ms × 2ⁿ) 사이이고 무작위 값에 비례한다 — full jitter")
    void 대기는_상한_안이다() {
        // when, then
        assertThat(상한만큼.대기(0)).isEqualTo(Duration.ofMillis(10));
        assertThat(상한만큼.대기(1)).isEqualTo(Duration.ofMillis(20));
        assertThat(상한만큼.대기(3)).isEqualTo(Duration.ofMillis(80));
        assertThat(상한만큼.대기(4)).as("100ms 에서 멈춘다").isEqualTo(Duration.ofMillis(100));
        assertThat(상한만큼.대기(62)).as("자리 넘침 없이 100ms").isEqualTo(Duration.ofMillis(100));
        assertThat(new AcquireBackoff(() -> 0.5).대기(2)).isEqualTo(Duration.ofMillis(20));
        assertThat(new AcquireBackoff(() -> 0.0).대기(5)).isZero();
    }

    @Test
    @DisplayName("다른 SCIM 쓰기가 쥐고 있으면 한도까지 다시 시도하고, 다 쓰면 마지막 실패의 대기(2초)를 물려받는다")
    void 한도까지_다시_시도한다() {
        // given — 0·10·30·70·150·250ms 에 시도한다. 다음 대기 100ms 를 더하면 350ms 라 한도 300ms 를 넘는다
        AtomicInteger 시도 = new AtomicInteger();

        // when, then
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(
                        늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(LockPurpose.WRITE)), Duration.ofMillis(300)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(249))
                .thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(LockUnavailableException.class, 실패 -> {
                    assertThat(실패).hasMessageContaining("변경 락을 얻지 못했습니다");
                    assertThat(실패.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                }))
                .verify();
        assertThat(시도).hasValue(6);
    }

    @ParameterizedTest
    @EnumSource(value = LockPurpose.class, names = {"REBUILD", "SYNC"})
    @DisplayName("재적재·동기화가 쥐고 있으면 다시 시도하지 않고 그 실패(60초)를 그대로 낸다")
    void 긴_작업이면_바로_끝낸다(LockPurpose 용도) {
        // given
        AtomicInteger 시도 = new AtomicInteger();

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(
                늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(용도)), Duration.ofSeconds(3)).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쥔 쪽 용도를 모르면 쓰기 경합처럼 다시 시도한다")
    void 용도를_모르면_다시_시도한다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        Mono<String> 두_번_뒤_성공 = Mono.defer(() -> 시도.incrementAndGet() <= 2
                ? Mono.error(LockUnavailableException.잡혀_있다(null))
                : Mono.just("잡았다"));

        // when, then
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(두_번_뒤_성공, Duration.ofSeconds(3)))
                .thenAwait(Duration.ofMillis(100))
                .expectNext("잡았다")
                .verifyComplete();
        assertThat(시도).hasValue(3);
    }

    @Test
    @DisplayName("락 이외의 오류는 다시 시도하지 않고 그대로 흘린다 — 503 으로 옮기는 것은 유스케이스다")
    void 락_이외의_오류는_그대로다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();
        RuntimeException 저장소장애 = new RuntimeException("DynamoDB 가 응답하지 않는다(테스트)");

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(늘_실패하는_시도(시도, () -> 저장소장애), Duration.ofSeconds(3)).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isSameAs(저장소장애);
    }

    @Test
    @DisplayName("한도가 0 이면 한 번만 시도하고 마지막 실패의 대기를 물려받는다")
    void 한도_0이면_한_번이다() {
        // given
        AtomicInteger 시도 = new AtomicInteger();

        // when
        Throwable 실패 = catchThrowable(() -> 상한만큼.잡는다(
                늘_실패하는_시도(시도, () -> LockUnavailableException.잡혀_있다(LockPurpose.WRITE)), Duration.ZERO).block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("날아가는 중인 시도는 한도가 지나도 끊지 않는다 — 끊으면 서버에서 성공한 락이 TTL 동안 샌다")
    void 날아가는_시도를_끊지_않는다() {
        // given — 시도 하나가 500ms 걸린다. 한도는 300ms 다
        AtomicInteger 시도 = new AtomicInteger();
        AtomicBoolean 끊겼다 = new AtomicBoolean();

        // when, then — 300ms 에 끊지 않고 500ms 에 시도가 끝난 뒤 멈춘다
        StepVerifier.withVirtualTime(() -> 상한만큼.잡는다(Mono.defer(() -> {
                            시도.incrementAndGet();
                            return Mono.delay(Duration.ofMillis(500))
                                    .then(Mono.<String>error(LockUnavailableException.잡혀_있다(LockPurpose.WRITE)))
                                    .doOnCancel(() -> 끊겼다.set(true));
                        }), Duration.ofMillis(300)))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(499))
                .thenAwait(Duration.ofMillis(1))
                .expectError(LockUnavailableException.class)
                .verify();
        assertThat(시도).hasValue(1);
        assertThat(끊겼다).isFalse();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*AcquireBackoffTest' --tests '*LockUnavailableExceptionTest'`
Expected: 컴파일 실패 — `AcquireBackoff`·`긴_작업이_쥐었다` 가 없다.

- [ ] **Step 3: 구현한다**

`LockUnavailableException.java` 전체:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.TemporaryFailureException;

import java.time.Duration;

/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다 — 일시 장애다(설계 2026-10-05 §3.1). 다른 쪽이 락을 쥐어 조건이 깨졌으면 {@link #잡혀_있다} 로 쥔 쪽의 용도에 맞는 시간을 싣고,
 * 그 밖(락 저장소 오류·리스 상실·갱신 실패)은 {@link TemporaryFailureException#기본_대기} 다.
 */
public class LockUnavailableException extends TemporaryFailureException {

    /** 다른 SCIM 쓰기가 쥐고 있다 — 밀리초 단위로 쥐는 락이다 */
    public static final Duration 쓰기_경합_대기 = Duration.ofSeconds(2);
    /** 재적재·전체 동기화가 쥐고 있다 — 수 분 걸린다 */
    public static final Duration 긴_작업_대기 = Duration.ofSeconds(60);

    /** 조건 실패 때 돌려받은 쥔 쪽의 용도 — {@link #잡혀_있다} 로 만든 것만 싣는다. 그 밖(저장소 오류·리스 상실)이나 용도를 모르면 null 이다. */
    private final MutationLock.LockPurpose 쥔_용도;

    public LockUnavailableException(String message) {
        super(message, 기본_대기);
        this.쥔_용도 = null;
    }

    /**
     * 락 자체의 문제가 아니라 저장소 장애로 획득에 실패했을 때 쓴다.
     * 원인을 물고 가야 한다 — 여기서 끊으면 로그에 "락을 얻는 중 오류" 만 남고 DynamoDB 가
     * 무엇을 던졌는지가 사라진다.
     */
    public LockUnavailableException(String message, Throwable cause) {
        super(message, 기본_대기, cause);
        this.쥔_용도 = null;
    }

    public LockUnavailableException(String message, Duration retryAfter) {
        super(message, retryAfter);
        this.쥔_용도 = null;
    }

    private LockUnavailableException(String message, Duration retryAfter, MutationLock.LockPurpose 쥔_용도) {
        super(message, retryAfter);
        this.쥔_용도 = 쥔_용도;
    }

    /** 다른 쪽이 락을 쥐고 있어 획득 조건이 깨졌다. 쥔 쪽의 용도를 모르면(null) 흔한 경우인 쓰기 경합으로 본다. */
    public static LockUnavailableException 잡혀_있다(MutationLock.LockPurpose 쥔_용도) {
        Duration 대기 = 긴_작업(쥔_용도) ? 긴_작업_대기 : 쓰기_경합_대기;
        return new LockUnavailableException(
                "다른 작업이 변경 락을 쥐고 있습니다(" + (쥔_용도 == null ? "용도 모름" : 쥔_용도) + ")", 대기, 쥔_용도);
    }

    /** 재적재·동기화가 쥐고 있다 — 몇 분 걸리므로 획득 재시도가 결과를 바꾸지 못한다(설계 2026-10-07 §3.3). 용도를 모르면 아니다(쓰기 경합으로 본다). */
    public boolean 긴_작업이_쥐었다() {
        return 긴_작업(쥔_용도);
    }

    private static boolean 긴_작업(MutationLock.LockPurpose 용도) {
        return 용도 == MutationLock.LockPurpose.REBUILD || 용도 == MutationLock.LockPurpose.SYNC;
    }
}
```

`AcquireBackoff.java`:

```java
package dev.starryeye.organization.core.usecase;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * 다른 서버가 쥔 전역 락을 다시 시도하는 간격(설계 2026-10-07 §3.2) — AWS 권고의 지수 백오프 + full jitter. n번째(0부터) 재시도는
 * {@code 0 ~ min(최대_간격, 처음_간격 × 2ⁿ)} 사이 무작위로 기다린다. 경합이 짧으면 곧 다시 시도하고, 길면 많아야 100ms 간격으로 두드린다.
 *
 * <p><b>다음 시도 전에 남은 한도를 본다.</b> 걸린 시간에 이번 대기를 더해 한도에 닿으면 시도하지 않고 멈춘다. 날아가는 중인 시도는 끊지 않는다 —
 * 서버에서 성공한 획득의 응답을 끊으면 그 락은 TTL(30초) 동안 새어 모든 쓰기를 막는다.
 *
 * <p><b>쥔 쪽이 재적재·동기화면 다시 시도하지 않는다</b>(§3.3) — 몇 분 걸리는 일이라 한도 안의 재시도가 결과를 바꾸지 못한다.
 */
final class AcquireBackoff {

    /** 첫 재시도 대기의 상한. */
    static final Duration 처음_간격 = Duration.ofMillis(10);
    /** 대기 상한이 여기서 멈춘다 — 경합이 이어져도 서버 하나가 초당 약 15~20번만 두드린다(추정). */
    static final Duration 최대_간격 = Duration.ofMillis(100);

    private final DoubleSupplier 무작위;

    AcquireBackoff(DoubleSupplier 무작위) {
        this.무작위 = 무작위;
    }

    static AcquireBackoff 무작위로() {
        return new AcquireBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** n번째(0부터) 재시도 전 대기. */
    Duration 대기(long n) {
        long 상한 = Math.min(최대_간격.toMillis(), 처음_간격.toMillis() << Math.min(n, 10));
        return Duration.ofMillis((long) (상한 * 무작위.getAsDouble()));
    }

    /**
     * {@code 시도} 를 다시 구독하며 잡는다 — 같은 {@code Mono} 를 다시 구독하므로 재시도도 같은 토큰이다(점검 S14). 다시 시도하는 실패는
     * 쥔 쪽이 재적재·동기화가 아닌 {@link LockUnavailableException} 뿐이다. 그 밖의 실패는 그대로 흘린다. 한도에 닿으면 마지막 실패의
     * 대기를 물려받은 503 이다.
     */
    <T> Mono<T> 잡는다(Mono<T> 시도, Duration 한도) {
        return Mono.defer(() -> {
            long 시작 = 지금();
            return 시도.retryWhen(Retry.from(신호들 -> 신호들.concatMap(신호 -> {
                Throwable 실패 = 신호.failure();
                if (!(실패 instanceof LockUnavailableException 락) || 락.긴_작업이_쥐었다()) {
                    return Mono.<Long>error(실패);
                }
                Duration 대기 = 대기(신호.totalRetries());
                if (지금() - 시작 + 대기.toMillis() >= 한도.toMillis()) {
                    return Mono.<Long>error(new LockUnavailableException("변경 락을 얻지 못했습니다", 락.retryAfter()));
                }
                return Mono.delay(대기);
            })));
        });
    }

    /** 스케줄러 시계(밀리초) — 가상 시간 테스트에서도 한도와 대기가 같은 시계를 본다. */
    static long 지금() {
        return Schedulers.parallel().now(TimeUnit.MILLISECONDS);
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*AcquireBackoffTest' --tests '*LockUnavailableExceptionTest'`
Expected: PASS (`AcquireBackoffTest` 8개 — 매개변수 둘 포함, `LockUnavailableExceptionTest` 3개).

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/AcquireBackoff.java core/src/main/java/dev/starryeye/organization/core/usecase/LockUnavailableException.java core/src/test/java/dev/starryeye/organization/core/usecase/AcquireBackoffTest.java core/src/test/java/dev/starryeye/organization/core/usecase/LockUnavailableExceptionTest.java
git commit -F - <<'EOF'
feat: 락 획득 백오프 — full jitter 0~min(100ms, 10ms×2ⁿ), 다음 시도 전 남은 한도 확인(날아가는 시도는 끊지 않음), 쥔 쪽이 재적재·동기화면 다시 시도하지 않는다(점검 P3·⑤-1 이월)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 3: 유스케이스에 줄과 백오프를 붙인다 (core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
  - `ACQUIRE_RETRY_DELAY`(~119), `acquireTimeout` 자바독(~127-134), `acquireRetries`(~147-149) 를 바꾼다.
  - `잡고_돌린다`(~654-680), `마지막_대기`(~682-687), `경과`(~703-705) 를 바꾼다.
  - import 를 정리한다.
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLockRetryAfterTest.java` (기대값 바뀜)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLockObservabilityTest.java:131` (주석만)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLocalQueueTest.java` (새)

**Interfaces:**
- Consumes: Task 1 `LocalWriteQueue`·`Turn`, Task 2 `AcquireBackoff`·`LockUnavailableException.긴_작업이_쥐었다()`
- Produces: 공개 API 변화 없음. 생성자 둘(7·8인자)이 그대로다 — 새 필드는 초기화한 `final` 이라 Lombok `@RequiredArgsConstructor` 가 받지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`IncrementalSyncLocalQueueTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 서버 안 줄과 백오프를 붙인 결선(설계 2026-10-07 §3). 줄·백오프 각각의 규칙은 {@link LocalWriteQueueTest}·{@link AcquireBackoffTest} 가 본다 —
 * 여기서는 차례가 반납 뒤에(실패해도) 넘어가는지, 줄에서 한도를 넘기면 DynamoDB 를 부르지 않고 503 인지, 재시도가 같은 Mono 를 다시 구독하는지 본다.
 */
class IncrementalSyncLocalQueueTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private 세는_락 lock;
    private 기록하는_관찰자 관찰자;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new 세는_락();
        관찰자 = new 기록하는_관찰자();
        // kim 을 비활성화하면 지울 튜플이 있어 OpenFGA 쓰기(writer.delay)를 탄다. lee 는 소속이 없다
        state.users.put("kim", 직원("kim", true));
        state.users.put("lee", 직원("lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
    }

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    /** acquire 를 부른 수(Mono 를 만든 수 — 토큰이 정해지는 때)와 구독한 수(DynamoDB 에 보낸 수)를 따로 센다. */
    private static final class 세는_락 extends FakeMutationLock {
        final AtomicInteger 만든_수 = new AtomicInteger();
        final AtomicInteger 보낸_수 = new AtomicInteger();

        @Override
        public Mono<LockLease> acquire(LockPurpose purpose) {
            만든_수.incrementAndGet();
            Mono<LockLease> 실제 = super.acquire(purpose);
            return Mono.defer(() -> {
                보낸_수.incrementAndGet();
                return 실제;
            });
        }
    }

    private static final class 기록하는_관찰자 implements LockObserver {
        final List<Boolean> 경합 = new ArrayList<>();

        @Override
        public void acquireFinished(Duration waited, boolean contended) {
            경합.add(contended);
        }

        @Override
        public void leaseLost(String reason) {
        }
    }

    private IncrementalSyncUseCase 유스케이스(Duration 한도) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, 한도,
                IncrementalSyncUseCase.DriftObserver.NOOP, 관찰자);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("같은 서버의 쓰기 둘이 겹치면 뒤 요청은 줄에서 기다렸다가, 앞 요청이 반납한 뒤 DynamoDB 시도 한 번으로 잡는다")
    void 뒤_요청은_줄에서_기다린다() {
        // given — 앞 요청(kim 비활성화)은 OpenFGA 쓰기에 1초 걸린다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofSeconds(1);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(3));
        AtomicReference<IncrementalSyncResult> 앞 = new AtomicReference<>();
        AtomicReference<IncrementalSyncResult> 뒤 = new AtomicReference<>();

        // when
        유스케이스.upsertUser(직원("kim", false)).subscribe(앞::set);
        유스케이스.upsertUser(직원("lee", false)).subscribe(뒤::set);
        시간.advanceTimeBy(Duration.ofMillis(999));

        // then — 뒤 요청은 줄에서 기다린다. DynamoDB 에는 앞 요청 하나만 갔다
        assertThat(lock.보낸_수).hasValue(1);
        assertThat(뒤.get()).isNull();

        // when
        시간.advanceTimeBy(Duration.ofSeconds(2));

        // then
        assertThat(앞.get()).isNotNull();
        assertThat(뒤.get()).isNotNull();
        assertThat(lock.보낸_수).as("뒤 요청은 비워진 락을 한 번에 잡는다").hasValue(2);
        assertThat(관찰자.경합).as("뒤 요청은 줄에서 밀렸다").containsExactly(false, true);
    }

    @Test
    @DisplayName("줄에서 한도를 넘기면 DynamoDB 를 부르지 않고 503(2초)이며 경합으로 센다")
    void 줄에서_한도를_넘기면_503이다() {
        // given — 앞 요청이 OpenFGA 쓰기에 5초 걸린다. 한도는 1초다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        writer.delay = Duration.ofSeconds(5);
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(1));
        AtomicReference<Throwable> 뒤_실패 = new AtomicReference<>();
        유스케이스.upsertUser(직원("kim", false)).subscribe();

        // when
        유스케이스.upsertUser(직원("lee", false)).subscribe(결과 -> { }, 뒤_실패::set);
        시간.advanceTimeBy(Duration.ofSeconds(1));

        // then
        assertThat(뒤_실패.get()).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(lock.보낸_수).as("줄에서 빠진 요청은 DynamoDB 를 부르지 않았다").hasValue(1);
        assertThat(관찰자.경합).containsExactly(false, true);
        시간.advanceTimeBy(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("앞 요청이 락 안에서 실패해도 차례를 넘긴다 — 뒤 요청은 줄에서 밀리지 않는다")
    void 락_안에서_실패해도_넘긴다() {
        // given
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ofSeconds(3));

        // when
        Throwable 앞_실패 = catchThrowable(() -> 유스케이스.changeUser("kim", u -> {
            throw new IllegalArgumentException("계산 실패(테스트)");
        }).block(Duration.ofSeconds(5)));
        IncrementalSyncResult 뒤 = 유스케이스.upsertUser(직원("lee", false)).block(Duration.ofSeconds(5));

        // then
        assertThat(앞_실패).isInstanceOf(IllegalArgumentException.class);
        assertThat(뒤).isNotNull();
        assertThat(lock.isHeld()).isFalse();
        assertThat(관찰자.경합).as("앞 요청이 차례를 넘겨 뒤 요청은 바로 받았다").containsExactly(false, false);
    }

    @Test
    @DisplayName("락 획득이 실패해도 차례를 넘긴다 — 한도 0 인 뒤 요청이 줄에서 묶이지 않는다")
    void 획득이_실패해도_넘긴다() {
        // given
        IncrementalSyncUseCase 유스케이스 = 유스케이스(Duration.ZERO);
        lock.failAcquire = true;

        // when
        Throwable 앞_실패 = catchThrowable(() -> 유스케이스.upsertUser(직원("kim", false)).block(Duration.ofSeconds(5)));
        lock.failAcquire = false;
        IncrementalSyncResult 뒤 = 유스케이스.upsertUser(직원("lee", false)).block(Duration.ofSeconds(5));

        // then
        assertThat(앞_실패).isInstanceOf(LockUnavailableException.class);
        assertThat(뒤).isNotNull();
    }

    @Test
    @DisplayName("재시도는 acquire 가 만든 같은 Mono 를 다시 구독한다 — 실제 락에서는 같은 토큰이다(점검 S14)")
    void 재시도는_같은_Mono_다() {
        // given — 다른 SCIM 쓰기가 쥐고 있다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        LockLease 남의_리스 = lock.acquire(MutationLock.LockPurpose.WRITE).block();
        lock.만든_수.set(0);
        lock.보낸_수.set(0);
        AtomicReference<IncrementalSyncResult> 결과 = new AtomicReference<>();

        // when — 첫 시도가 막힌 뒤 남이 반납한다
        유스케이스(Duration.ofSeconds(3)).upsertUser(직원("lee", false)).subscribe(결과::set);
        lock.release(남의_리스).block();
        시간.advanceTimeBy(Duration.ofMillis(100));

        // then
        assertThat(결과.get()).isNotNull();
        assertThat(lock.만든_수).as("acquire 는 한 번 불렀다 — 토큰은 그때 정해진다").hasValue(1);
        assertThat(lock.보낸_수).as("처음 한 번 + 다시 구독 한 번").hasValue(2);
    }
}
```

`IncrementalSyncLockRetryAfterTest.java` 의 두 테스트와 `유스케이스` 자바독을 다음으로 바꾼다(필드·`준비한다`·`잡히지_않는_락` 은 그대로):

```java
    /** 한도 400ms — 쓰기 경합이면 백오프로 여러 번 다시 시도할 수 있다. */
    private IncrementalSyncUseCase 유스케이스(MutationLock lock) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ofMillis(400),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    @Test
    @DisplayName("재적재가 쥔 락이면 다시 시도하지 않고 바로 60초다 — 몇 분 걸리는 일이라 한도 안의 재시도가 결과를 바꾸지 못한다(설계 2026-10-07 §3.3)")
    void 재적재가_쥐면_바로_60초다() {
        // given
        var 시도 = new AtomicInteger();
        var 재적재가_쥔_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.REBUILD));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(재적재가_쥔_락).removeUser("kim").block());

        // then
        assertThat(시도).hasValue(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쓰기가 쥐어 다시 시도하다 재적재가 쥐면 그 자리에서 멈추고 60초다")
    void 도중에_재적재가_쥐면_멈춘다() {
        // given — 처음엔 SCIM 쓰기가 쥐고 있다가 재적재가 잡았다
        var 시도 = new AtomicInteger();
        var 쥔_쪽이_바뀌는_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(
                n == 0 ? MutationLock.LockPurpose.WRITE : MutationLock.LockPurpose.REBUILD));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(쥔_쪽이_바뀌는_락).removeUser("kim").block());

        // then
        assertThat(시도).hasValue(2);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("쓰기가 끝내 쥐고 있으면 한도까지 다시 시도하고, 마지막 실패의 기다릴 시간(2초)을 물려받는다")
    void 쓰기가_쥐면_한도까지_시도한다() {
        // given
        var 시도 = new AtomicInteger();
        var 쓰기가_쥔_락 = 잡히지_않는_락(시도, n -> LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE));

        // when
        var 실패 = catchThrowable(() -> 유스케이스(쓰기가_쥔_락).removeUser("kim").block());

        // then
        assertThat(시도.get()).as("백오프로 여러 번 다시 시도했다").isGreaterThan(1);
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
    }
```

클래스 자바독은 다음으로 바꾼다: "획득을 포기할 때의 기다릴 시간은 쥔 쪽의 용도를 따른다(설계 2026-10-05 §3.1, 2026-10-07 §3.3). 재적재·동기화면 다시 시도하지 않고 바로 60초, 쓰기 경합이면 한도까지 다시 시도한 뒤 마지막 실패의 2초다."

`IncrementalSyncLockObservabilityTest.java:131` 의 주석 `// when — 200ms 간격으로 두 번 재시도할 수 있는 예산` 은 `// when — 한도 400ms 안에서 백오프로 다시 시도한다` 로 바꾼다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncLocalQueueTest' --tests '*IncrementalSyncLockRetryAfterTest'`
Expected: 실패한다.
- `뒤_요청은_줄에서_기다린다`: 지금은 줄이 없어 뒤 요청도 DynamoDB 를 두드린다(`보낸_수` > 1).
- `줄에서_한도를_넘기면_503이다`: 지금은 200ms 간격으로 두드린다.
- `재적재가_쥐면_바로_60초다`: 지금은 시도가 3번이다.

- [ ] **Step 3: 유스케이스를 바꾼다**

1. `ACQUIRE_RETRY_DELAY` 상수와 자바독을 지운다. `acquireRetries()` 를 지운다. `마지막_대기` 를 지운다.
2. `acquireTimeout` 의 자바독을 다음으로 바꾼다:

```java
    /**
     * 락 획득을 포기하기까지의 대기 한도(설계 §4.4, 2026-10-07 §3.1). 줄에 선 순간부터 잰다 — 서버 안 줄에서 기다린 시간과 다른 서버와
     * 백오프로 겨룬 시간이 모두 든다. 한도는 시도와 시도 사이에서만 본다 — 날아가는 중인 획득은 끊지 않는다.
     */
    private final Duration acquireTimeout;
```

3. 필드 둘을 `lockObserver` 아래에 더한다(초기화한 `final` 이라 생성자 인자가 아니다):

```java
    /** 서버 안의 SCIM 쓰기 줄(설계 2026-10-07 §3.1) — 이 유스케이스 하나, 곧 서버 하나에 하나다. */
    private final LocalWriteQueue 줄 = new LocalWriteQueue();
    /** 다른 서버가 쥔 락을 다시 시도하는 간격(§3.2). */
    private final AcquireBackoff 백오프 = AcquireBackoff.무작위로();
```

4. `잡고_돌린다` 를 다음 두 메서드로 바꾼다(위의 `withLock` 자바독은 그대로 둔다):

```java
    private Mono<IncrementalSyncResult> 잡고_돌린다(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return Mono.defer(() -> {
            long 시작 = AcquireBackoff.지금();
            AtomicBoolean 경합했다 = new AtomicBoolean();

            return 줄.줄을_선다(acquireTimeout)
                    // 줄에서 한도를 넘겼다 — 앞에 이 서버의 쓰기가 있었으니 경합이다
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), true))
                    .flatMap(차례 -> {
                        if (차례.밀렸다()) {
                            경합했다.set(true);
                        }
                        return 잡는다(시작, 경합했다)
                                .flatMap(lease -> new LeaseKeeper(lock, renewInterval, lockObserver)
                                        .keep(lease, Mono.defer(() -> work.apply(lease)), "쓰기 도중 리스 상실")
                                        .materialize()
                                        .flatMap(끝 -> 반납한다(lease).thenReturn(끝)))
                                // 획득 실패도 끝이다
                                .onErrorResume(error -> Mono.just(Signal.<IncrementalSyncResult>error(error)))
                                // 반납까지 끝났거나 획득이 실패했다 — 결과를 내기 <b>전에</b> 다음 요청에 차례를 넘긴다(설계 2026-10-07 §3.1).
                                // 결과를 낸 뒤에 넘기면 그 결과를 받은 IdP 의 다음 요청이 아직 넘어가지 않은 차례 뒤에 줄을 선다
                                .doOnNext(끝 -> 차례.넘긴다())
                                // 끝이 오지 않고 취소돼도 차례를 쥔 채 사라지지 않는다 — 넘긴다는 한 번만 넘긴다
                                .doFinally(signal -> 차례.넘긴다())
                                .<IncrementalSyncResult>dematerialize();
                    });
        });
    }

    /**
     * 차례를 받은 요청이 전역 락을 잡는다 — 다른 서버와는 백오프로 겨룬다(설계 2026-10-07 §3.2). 한도는 줄에 선 순간({@code 시작})부터 잰다.
     *
     * <p><b>획득이 예외로 끝나면 그것도 503 이다 (설계 §6 두 번째 행).</b> 락 이외의 예외(DynamoDB 장애 등)도 {@link LockUnavailableException} 으로 감싼다.
     */
    private Mono<LockLease> 잡는다(long 시작, AtomicBoolean 경합했다) {
        Duration 남은_한도 = acquireTimeout.minusMillis(AcquireBackoff.지금() - 시작);
        // acquire 를 한 번만 부르고 그 Mono 를 다시 구독해야 재시도도 같은 토큰이다(점검 S14)
        Mono<LockLease> 시도 = lock.acquire(MutationLock.LockPurpose.WRITE)
                // 백오프 위에 둬야 시도마다 불린다 — 아래에 두면 마지막 실패만 본다.
                .doOnError(LockUnavailableException.class, error -> 경합했다.set(true));
        return 백오프.잡는다(시도, 남은_한도)
                .onErrorMap(error -> !(error instanceof LockUnavailableException),
                        error -> new LockUnavailableException("변경 락을 얻는 중 오류가 발생했습니다", error))
                // 실패했다고 다 경합은 아니다. 위 onErrorMap 이 DynamoDB 장애도 LockUnavailableException 으로 옮기므로
                // 예외 타입으로는 구별할 수 없고, 실제로 밀렸을 때만 켜지는 이 플래그로 봐야 한다.
                .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), 경합했다.get()))
                .doOnError(error -> lockObserver.acquireFinished(경과(시작), 경합했다.get()));
    }
```

5. `경과` 를 스케줄러 시계로 바꾼다:

```java
    /** 스케줄러 시계로 잰 경과 — 한도와 같은 시계다({@link AcquireBackoff#지금}). */
    private static Duration 경과(long 시작) {
        return Duration.ofMillis(AcquireBackoff.지금() - 시작);
    }
```

6. 파일 안에서 `경과(` 와 `System.nanoTime` 의 다른 쓰임이 있는지 이 파일만 `grep` 으로 확인한다. 있으면 같은 시계로 맞춘다.
7. import 를 정리한다. `reactor.core.publisher.Signal`·`reactor.core.scheduler.Schedulers` 는 필요하면 더하고, 쓰지 않게 된 `reactor.core.Exceptions`·`reactor.util.retry.Retry` 는 지운다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS — 새 테스트 5개, 바뀐 테스트 3개, 기존 core 테스트 전부.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLocalQueueTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLockRetryAfterTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncLockObservabilityTest.java
git commit -F - <<'EOF'
feat: SCIM 쓰기 락 획득을 서버 안 줄 + 서버끼리 백오프로 — 200ms 고정 재시도를 없애고, 차례는 반납 뒤 넘기고, 재적재·동기화 중엔 한 번 시도하고 바로 503(60초)(점검 P3·⑤-1 이월)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 4: 두 서버 경합 — 실제 DynamoDB 락 (storage-dynamodb, 테스트만)

**Files:**
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TwoInstanceWriteTest.java` (테스트 둘 더함)

**Interfaces:**
- Consumes: Task 3 결선, `DynamoDbMutationLock.acquire(LockPurpose)`·`release(LockLease)`(있음), `DynamoDbTestSupport` 의 `client`·`properties`(있음)

- [ ] **Step 1: 테스트를 더한다**

import 에 다음을 더한다:
- `dev.starryeye.organization.core.port.MutationLock`
- `dev.starryeye.organization.core.usecase.IncrementalSyncResult`
- `dev.starryeye.organization.core.usecase.LockUnavailableException`
- `static org.assertj.core.api.Assertions.catchThrowable`

```java
    @Test
    @DisplayName("두 인스턴스가 서로 다른 직원 40명을 한꺼번에 바꿔도 실패 없이 모두 남는다 — 서버마다 줄, 서버끼리 백오프(설계 2026-10-07 §3)")
    void 두_인스턴스의_동시_쓰기가_모두_남는다() {
        // given
        Flux.range(0, 40)
                .concatMap(i -> 인스턴스1.createUser(new DirectoryUser("u" + i, "ext-u" + i, "user" + i, "직원 " + i, null, true)))
                .blockLast(Duration.ofMinutes(1));

        // when — 짝수는 인스턴스1, 홀수는 인스턴스2 로 한꺼번에 보낸다
        List<IncrementalSyncResult> 결과 = Flux.range(0, 40)
                .flatMap(i -> (i % 2 == 0 ? 인스턴스1 : 인스턴스2).changeUser("u" + i, u -> u.withDisplayName("바뀐 " + i))
                        .subscribeOn(Schedulers.boundedElastic()), 40)
                .collectList()
                .block(Duration.ofMinutes(2));

        // then
        assertThat(결과).hasSize(40).allSatisfy(r -> assertThat(r.fullyApplied()).isTrue());
        for (int i = 0; i < 40; i++) {
            assertThat(저장소.findUser("u" + i).block().displayName()).isEqualTo("바뀐 " + i);
        }
    }

    @Test
    @DisplayName("다른 인스턴스의 재적재가 락을 쥐고 있으면 쓰기는 한 번 시도하고 바로 503(60초)이다 — 한도 3초를 기다리지 않는다(설계 2026-10-07 §3.3)")
    void 재적재_중이면_바로_503이다() {
        // given — 인스턴스1 이 재적재로 락을 쥔다. 쓰는 쪽의 한도는 운영 기본값 3초다
        Clock clock = Clock.systemUTC();
        DynamoDbMutationLock 인스턴스1의_락 = new DynamoDbMutationLock(client, properties, clock, "instance-1");
        var 재적재_리스 = 인스턴스1의_락.acquire(MutationLock.LockPurpose.REBUILD).block(Duration.ofSeconds(10));
        IncrementalSyncUseCase 운영_한도_인스턴스 = new IncrementalSyncUseCase(
                new DynamoDbDirectoryStateRepository(client, properties, clock), new FakeTupleWriter(), new FakeTupleChecker(),
                new DynamoDbMutationLock(client, properties, clock, "instance-2"),
                Duration.ofSeconds(3), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        long 시작 = System.nanoTime();

        // when
        Throwable 실패 = catchThrowable(() -> 운영_한도_인스턴스
                .upsertUser(new DirectoryUser("kim", "ext-kim", "kim", "김철수", null, true))
                .block(Duration.ofSeconds(10)));
        Duration 걸린_시간 = Duration.ofNanos(System.nanoTime() - 시작);
        인스턴스1의_락.release(재적재_리스).block(Duration.ofSeconds(10));

        // then
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
        assertThat(걸린_시간).as("재시도 없이 끝난다").isLessThan(Duration.ofSeconds(1));
    }
```

- [ ] **Step 2: 돌린다**

Run: `./gradlew :storage-dynamodb:test --tests '*TwoInstanceWriteTest'`
Expected: PASS (3개 — 기존 1, 새 2).

Task 3 전 코드라면 둘째 테스트가 실패한다(약 3초 재시도, `걸린_시간` ≥ 1초). 이 과제는 Task 3 뒤라 RED 단계가 없다.

- [ ] **Step 3: 커밋한다**

```bash
git add storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TwoInstanceWriteTest.java
git commit -F - <<'EOF'
test: 두 인스턴스 경합 — 실제 DynamoDB 락 위에서 동시 40건이 모두 남고, 다른 인스턴스의 재적재 중엔 한 번 시도하고 바로 503(60초)(⑥-2)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 5: 버리는 속성만 있는 직원 PATCH 는 락 없이 (connector-scim)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java`
  - 직원 절(~255-375)을 `Target`·`storedAttribute`·`discard` 로 나눈다.
  - `touchesNoStoredUserAttribute` 를 더한다.
  - `applyOne`(직원)·`applyPath`·`mergeUserAttributes` 를 없앤다.
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java` (`patch`, `respond` → 본문 렌더링 분리)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java` (더함)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java` (더함)

**Interfaces:**
- Produces: `public static boolean ScimPatchApplier.touchesNoStoredUserAttribute(ScimPatchOp patch, Consumer<String> 버림)`
  - 저장하는 직원 속성에 닿는 연산이 하나도 없으면 true.
  - 모양 검사는 `applyToUser` 와 같다. 어기면 `ScimException`(400)을 던진다.
  - 버린 속성 이름은 `버림` 으로 넘긴다.
- `applyToUser(DirectoryUser, ScimPatchOp, Consumer<String>)`·`applyToUser(DirectoryUser, ScimPatchOp)` 의 시그니처와 동작은 그대로다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimPatchApplierTest.java` 에 더한다. 기존 도우미 `패치(String op, String path, Object value)`·`패치(ScimOperation... operations)` 를 쓴다. 필요하면 import 를 더한다: `java.util.ArrayList`, `java.util.List`, `java.util.Map`, `dev.starryeye.organization.scim.dto.ScimOperation`.

```java
    private static final String MANAGER = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager";

    @Test
    @DisplayName("버리는 속성만 있는 PATCH 는 저장하는 속성에 닿지 않는다 — 버린 이름은 알린다(설계 2026-10-07 §4)")
    void 버리는_속성만이면_닿지_않는다() {
        // given
        List<String> 버린것 = new ArrayList<>();
        var 패치 = 패치(
                new ScimOperation("replace", MANAGER, Map.of("value", "lee")),
                new ScimOperation("replace", "phoneNumbers[type eq \"work\"].value", "010-1234-5678"),
                new ScimOperation("replace", "emails[type eq \"home\"].value", "kim@home.example"));

        // when
        boolean 닿지_않는다 = ScimPatchApplier.touchesNoStoredUserAttribute(패치, 버린것::add);

        // then
        assertThat(닿지_않는다).isTrue();
        assertThat(버린것).containsExactly("manager", "phoneNumbers", "emails");
    }

    @Test
    @DisplayName("경로 없는 값의 키가 모두 저장하지 않는 것이어도 닿지 않는다 — 모르는 키는 other 다")
    void 경로_없는_값의_키가_모두_버리는_것이면_닿지_않는다() {
        // given
        List<String> 버린것 = new ArrayList<>();
        var 패치 = 패치("replace", null, Map.of("nickName", "k"));

        // when, then
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치, 버린것::add)).isTrue();
        assertThat(버린것).containsExactly("nickName");
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(
                패치("replace", null, Map.of("x-custom", 1)), 버린것::add)).isTrue();
        assertThat(버린것).containsExactly("nickName", "other");
    }

    @Test
    @DisplayName("저장하는 속성이 하나라도 섞이면 닿는다 — 같은 값을 다시 보낸 것도 락 안으로 간다")
    void 저장하는_속성이_섞이면_닿는다() {
        // when, then
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(
                패치(new ScimOperation("replace", MANAGER, Map.of("value", "lee")),
                        new ScimOperation("replace", "displayName", "김철수")), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "name.givenName", "철수"), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "emails[type eq \"work\"].value", "a@b.c"), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", null, Map.of("nickName", "k", "active", false)), 이름 -> { })).isFalse();
        assertThat(ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "urn:ietf:params:scim:schemas:core:2.0:User:active", true), 이름 -> { })).isFalse();
    }

    @Test
    @DisplayName("판정도 적용과 같은 모양 검사를 한다 — 모르는 path·경로 없는 remove·객체가 아닌 값은 400 이다")
    void 판정도_모양을_검사한다() {
        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", "fooBar", "x"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("invalidPath"));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("remove", null, null), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getScimType()).isEqualTo("noTarget"));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("replace", null, "문자열"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> ScimPatchApplier.touchesNoStoredUserAttribute(패치("frobnicate", "displayName", "x"), 이름 -> { }))
                .isInstanceOfSatisfying(ScimException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
```

`ScimUserHandlerTest.java` 에 더한다(기존 `클라이언트(IgnoredAttributeObserver)`·`직원을_만든다` 를 쓴다):

```java
    private static final String MANAGER_PATCH = """
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
             "Operations":[
               {"op":"replace","path":"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager","value":{"value":"lee"}},
               {"op":"replace","path":"phoneNumbers[type eq \\"work\\"].value","value":"010-1234-5678"}]}
            """;

    @Test
    @DisplayName("버리는 속성만 있는 PATCH 는 락 없이 200 과 지금 모습을 돌려준다 — Entra 의 manager(설계 2026-10-07 §4)")
    void 버리는_속성만_있는_PATCH_는_락이_없다() {
        // given
        List<Set<String>> 알림 = new ArrayList<>();
        var 관찰하는_클라이언트 = 클라이언트(알림::add);
        String id = 직원을_만든다(관찰하는_클라이언트, "kim");
        int 잡은_수 = lock.acquired.get();
        int 적용한_수 = writer.appliedDeltas.size();

        // when
        관찰하는_클라이언트.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(MANAGER_PATCH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.active").isEqualTo(true);

        // then
        assertThat(lock.acquired.get()).as("락을 잡지 않았다").isEqualTo(잡은_수);
        assertThat(writer.appliedDeltas).as("OpenFGA 에 쓰지 않았다").hasSize(적용한_수);
        assertThat(알림).containsExactly(Set.of("manager", "phoneNumbers"));
    }

    @Test
    @DisplayName("버리는 속성만 있는 PATCH 도 attributes 투영을 지킨다")
    void 락_없는_응답도_투영을_지킨다() {
        // given
        String id = 직원을_만든다(client, "lee");

        // when, then
        client.patch().uri("/scim/v2/Users/" + id + "?attributes=userName")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(MANAGER_PATCH)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userName").isEqualTo("lee")
                .jsonPath("$.active").doesNotExist();
    }

    @Test
    @DisplayName("버리는 속성만 있는 PATCH 를 없는 직원에 보내면 락 없이 404 다")
    void 없는_직원은_락_없이_404다() {
        // given
        int 잡은_수 = lock.acquired.get();

        // when, then
        client.patch().uri("/scim/v2/Users/no-such-id")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(MANAGER_PATCH)
                .exchange()
                .expectStatus().isNotFound();
        assertThat(lock.acquired.get()).isEqualTo(잡은_수);
    }

    @Test
    @DisplayName("경로 없는 값의 키가 모두 저장하지 않는 것이면 락 없이 200 이다")
    void 경로_없는_값의_버리는_키만이면_락이_없다() {
        // given
        List<Set<String>> 알림 = new ArrayList<>();
        var 관찰하는_클라이언트 = 클라이언트(알림::add);
        String id = 직원을_만든다(관찰하는_클라이언트, "park");
        int 잡은_수 = lock.acquired.get();

        // when
        관찰하는_클라이언트.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","value":{"nickName":"k","x-custom":1}}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(lock.acquired.get()).isEqualTo(잡은_수);
        assertThat(알림).containsExactly(Set.of("nickName", "other"));
    }

    @Test
    @DisplayName("저장하는 속성이 섞이면 같은 값이어도 락 안에서 처리한다 — 어긋남 고치기를 남긴다")
    void 저장하는_속성이_섞이면_락_안이다() {
        // given
        String id = 직원을_만든다(client, "choi");
        int 잡은_수 = lock.acquired.get();

        // when
        client.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[
                           {"op":"replace","path":"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager","value":{"value":"lee"}},
                           {"op":"replace","path":"active","value":true}]}
                        """)
                .exchange()
                .expectStatus().isOk();

        // then
        assertThat(lock.acquired.get()).isEqualTo(잡은_수 + 1);
    }

    @Test
    @DisplayName("모르는 path 는 여전히 400 이고 락을 잡지 않는다 — 판정이 먼저 거절한다")
    void 모르는_path_는_락_전에_400이다() {
        // given
        String id = 직원을_만든다(client, "jung");
        int 잡은_수 = lock.acquired.get();

        // when, then
        client.patch().uri("/scim/v2/Users/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"fooBar","value":"x"}]}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
        assertThat(lock.acquired.get()).isEqualTo(잡은_수);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest' --tests '*ScimUserHandlerTest'`
Expected: 컴파일 실패 — `touchesNoStoredUserAttribute` 가 없다.

- [ ] **Step 3: `ScimPatchApplier` 의 직원 절을 바꾼다**

`applyToUser(DirectoryUser, ScimPatchOp, Consumer<String>)` 의 본문과 직원용 `applyOne`·`applyPath`·`mergeUserAttributes` 를 다음으로 바꾼다. `applyUserName`·`applyWorkEmail`·`mergeName`·`primaryEmail`·`EMAIL_FILTER`·`NAME_PARTS` 와 나머지 도우미는 그대로 쓴다. 필요하면 `java.util.ArrayList` import 를 더한다.

```java
    /** 직원 PATCH. 받아서 버린 속성의 이름({@link ScimRfcAttributes} 의 정규 이름이나 {@code other})을 {@code 버림} 으로 넘긴다(설계 2026-10-06 §3.3). */
    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch, Consumer<String> 버림) {
        DirectoryUser current = before;
        for (ScimOperation operation : operations(patch)) {
            String op = requireKnownOp(operation.op());
            for (Target target : targets(operation, op)) {
                Optional<UserChange> change = storedAttribute(target.name());
                if (change.isPresent()) {
                    current = change.get().apply(current, op, target.value());
                } else {
                    discard(target, 버림);
                }
            }
        }
        return current;
    }

    /**
     * 이 PATCH 의 어떤 연산도 저장하는 직원 속성에 닿지 않는가(설계 2026-10-07 §4) — 참이면 핸들러가 락 없이 지금 모습을 돌려준다.
     * 가르는 규칙은 {@link #applyToUser} 와 같은 하나({@link #storedAttribute})다. 저장하는지는 path 이름(과 이메일 필터의 type)만으로 정해지고
     * 직원의 현재 값과 무관하다 — 그래서 요청만 보고 정한다. 모양 검사(모르는 연산·모르는 path 400, 경로 없는 remove noTarget, 값이 객체가
     * 아니면 400)도 같은 도우미를 지나 같다. 버린 속성 이름은 {@code 버림} 으로 넘긴다.
     */
    public static boolean touchesNoStoredUserAttribute(ScimPatchOp patch, Consumer<String> 버림) {
        boolean touches = false;
        for (ScimOperation operation : operations(patch)) {
            String op = requireKnownOp(operation.op());
            for (Target target : targets(operation, op)) {
                if (storedAttribute(target.name()).isPresent()) {
                    touches = true;
                } else {
                    discard(target, 버림);
                }
            }
        }
        return !touches;
    }

    /** 연산 하나가 겨누는 속성 — path 형이면 하나(이름은 앞뒤 공백을 뺀 path), 경로 없는 값이면 키마다 하나(F1). */
    private record Target(String name, Object value, boolean pathForm) {
    }

    /**
     * 연산을 겨눈 속성들로 편다. 경로 없는 값은 add/replace 만 되고 값이 객체여야 한다. Jackson 은 값 객체를 {@code LinkedHashMap} 으로 주므로
     * 키 순서대로 누적 적용된다.
     */
    private static List<Target> targets(ScimOperation operation, String op) {
        String path = operation.path();
        if (path == null || path.isBlank()) {
            requireTarget(op);
            requireReplaceOrAdd(op, operation.op());
            List<Target> targets = new ArrayList<>();
            asAttributeMap(operation.value()).forEach((key, value) -> targets.add(new Target(key, value, false)));
            return targets;
        }
        return List.of(new Target(path.trim(), operation.value(), true));
    }

    /**
     * 저장하지 않는 속성 — path 형이면 RFC 가 정의한 것만 받아서 버리고 모르는 것은 400 {@code invalidPath}, 경로 없는 값의 키는 무시하고
     * 이름만 알린다(설계 2026-10-06 §3). 이름은 표의 정규 이름이나 {@code other} — 요청 문자열을 메트릭 태그로 넘기지 않는다.
     */
    private static void discard(Target target, Consumer<String> 버림) {
        if (target.pathForm()) {
            버림.accept(ScimRfcAttributes.userAttribute(target.name())
                    .orElseThrow(() -> ScimException.invalidPath("지원하지 않는 path 입니다: " + target.name())));
        } else {
            버림.accept(ScimRfcAttributes.userAttribute(target.name()).orElse(ScimRfcAttributes.OTHER));
        }
    }

    /** 저장하는 속성 하나에 연산을 적용한다. */
    @FunctionalInterface
    private interface UserChange {
        DirectoryUser apply(DirectoryUser user, String op, Object value);
    }

    /**
     * path 형식과 경로 없는 값의 키 하나를 <b>같은 규칙</b>으로 가른다(F1) — 저장하는 속성이면 그 적용, 아니면 빈 값. 이름만 보고 정한다
     * (직원의 현재 값과 무관하다). 적용({@link #applyToUser})과 판정({@link #touchesNoStoredUserAttribute})이 이 하나를 쓴다.
     */
    private static Optional<UserChange> storedAttribute(String name) {
        String target = stripUrn(name, CORE_USER_URN);
        Matcher email = EMAIL_FILTER.matcher(target);
        if (email.matches()) {
            if (!email.group("type").equalsIgnoreCase("work")) {
                return Optional.empty();
            }
            return Optional.of((user, op, value) -> applyWorkEmail(user, op, value, email, name));
        }

        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name.")) {
            BiFunction<PersonName, String, PersonName> part = NAME_PARTS.get(lower.substring("name.".length()));
            if (part == null) {
                return Optional.empty();
            }
            return Optional.of((user, op, value) -> user.withName(part.apply(user.name(), op.equals("remove") ? null : asString(value))));
        }
        UserChange change = switch (lower) {
            case "username" -> (user, op, value) -> applyUserName(user, op.equals("remove"), value);
            case "displayname" -> (user, op, value) -> user.withDisplayName(op.equals("remove") ? null : asString(value));
            case "externalid" -> (user, op, value) -> user.withExternalId(op.equals("remove") ? null : asString(value));
            // active 가 없으면 활성이다 — POST 에 active 가 없을 때와 같은 규칙
            case "active" -> (user, op, value) -> user.withActive(op.equals("remove") || asBoolean(value));
            case "name" -> (user, op, value) -> user.withName(op.equals("remove") ? PersonName.EMPTY : mergeName(user.name(), asAttributeMap(value)));
            case "emails" -> (user, op, value) -> user.withEmail(op.equals("remove") ? null : primaryEmail(value));
            default -> null;
        };
        return Optional.ofNullable(change);
    }
```

주의:
- 정규식 `Matcher` 는 상태를 가진다. 람다가 `email` 을 잡아도 그 `Matcher` 는 이 호출 안에서만 쓰여 안전하다.
- `applyWorkEmail(..., path)` 의 마지막 인자는 오류 문구용이다. 원래와 같이 앞뒤 공백을 뺀 path 가 간다.
- 경로 없는 값의 키는 원래처럼 공백을 빼지 않는다.

- [ ] **Step 4: `ScimUserHandler` 를 바꾼다**

`patch` 를 다음으로 바꾼다. 자바독에 락 없는 갈래를 한 문장 더한다.

```java
    /**
     * PATCH — 연산 적용은 락 안에서, 락을 잡은 뒤 읽은 직원에 한다(SCIM 쓰기 락 설계 §3). 락 밖에서 읽은 직원으로 계산하면
     * 동시에 온 비활성화를 되돌리거나 방금 지운 직원을 되살린다. 단 저장하는 속성에 하나도 닿지 않는 PATCH 는 우리 상태에 아무 말도 하지 않으므로
     * 락 없이 지금 모습을 돌려준다(설계 2026-10-07 §4) — Check·쓰기·저장이 없고, 같은 순간 지워지면 지우기 직전 모습일 수 있다(GET 과 같다).
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimPatchOp.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(patch -> {
                    // 락 안의 계산이 다시 돌 수 있어 집합으로 모은다
                    Set<String> 버린것 = ConcurrentHashMap.newKeySet();
                    if (ScimPatchApplier.touchesNoStoredUserAttribute(patch, 버린것::add)) {
                        return state.findUser(id)
                                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                                .doOnNext(user -> 버린것을_알린다(id, 버린것))
                                .flatMap(user -> 본문으로(HttpStatus.OK, id, user, projection));
                    }
                    return sync.changeUser(id, before -> ScimPatchApplier.applyToUser(before, patch, 버린것::add))
                            .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                            .doOnNext(result -> 버린것을_알린다(id, 버린것))
                            .flatMap(result -> respond(HttpStatus.OK, id, result, projection));
                }));
    }
```

`respond` 의 렌더링을 `본문으로` 로 떼어 두 갈래가 함께 쓴다:

```java
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.temporarilyUnavailable(
                    "일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기));
        }
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id)))
                .flatMap(saved -> 본문으로(status, id, saved, projection));
    }

    /** 직원 하나의 SCIM 응답 — 생성은 Location 을 단다(RFC 7644 §3.3 SHALL, 설계 2026-10-06 §5.3). */
    private static Mono<ServerResponse> 본문으로(HttpStatus status, String id, DirectoryUser user, ScimAttributeProjection projection) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(SCIM_JSON);
        if (status == HttpStatus.CREATED) {
            builder.location(URI.create(ScimMapper.userLocation(id)));
        }
        return builder.bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimUser(user))));
    }
```

필요하면 `DirectoryUser` import 를 더한다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: PASS — 새 테스트 10개(applier 4, handler 6)와 기존 connector-scim 테스트 전부. 특히 `실패한_요청은_알리지_않는다` 와 `ScimPatchApplierTest` 의 기존 직원 PATCH 테스트가 그대로 통과해야 한다.

- [ ] **Step 6: 커밋한다**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java
git commit -F - <<'EOF'
feat: 버리는 속성만 있는 직원 PATCH 는 락 없이 지금 모습을 돌려준다 — 적용과 판정이 같은 규칙(storedAttribute) 하나, 모양 검사도 같다, 저장하는 속성이 섞이면 같은 값이어도 락 안(⑤-2 이월)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 6: OpenFGA 쓰기 결과는 누적기 하나에 (authz-openfga)

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java`
  - 연속 실패 차단기의 `reduce`(~154-169)를 바꾼다.
  - 쪼개기의 `reduce`(~378)를 바꾼다.
  - `merge`(~432-440)를 없앤다.
  - `ResultAccumulator` 를 더한다.
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterResultTest.java` (새)

**Interfaces:**
- Produces: `static final class OpenFgaRelationTupleWriter.ResultAccumulator`(패키지 전용) — `void 더한다(TupleWriteResult)`, `TupleWriteResult 결과()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.ResultAccumulator;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 묶음 결과를 누적기 하나에 모은다 — 묶음마다 누적 전체를 복사하면 비용이 묶음 수의 제곱이다(점검 P6, 설계 2026-10-07 §5). */
class OpenFgaRelationTupleWriterResultTest {

    private static List<Batch> 배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("user" + i, "DEV002"))))
                .toList();
    }

    @Test
    @DisplayName("누적기는 쓴 줄·지운 줄을 합집합으로, 실패는 더한 순서대로 모으고 결과는 불변이다")
    void 누적기는_합치고_순서를_지킨다() {
        // given
        RelationTuple 가 = RelationTuple.directMember("kim", "DEV001");
        RelationTuple 나 = RelationTuple.directMember("lee", "DEV001");
        RelationTuple 다 = RelationTuple.directMember("park", "DEV001");
        ResultAccumulator 누적기 = new ResultAccumulator();

        // when
        누적기.더한다(new TupleWriteResult(Set.of(가), Set.of(), List.of(new TupleFailure(다, "첫 실패"))));
        누적기.더한다(new TupleWriteResult(Set.of(가, 나), Set.of(다), List.of(new TupleFailure(나, "둘째 실패"))));
        TupleWriteResult 결과 = 누적기.결과();

        // then
        assertThat(결과.written()).containsExactlyInAnyOrder(가, 나);
        assertThat(결과.deleted()).containsExactly(다);
        assertThat(결과.failures()).extracting(TupleFailure::reason).containsExactly("첫 실패", "둘째 실패");
        assertThatThrownBy(() -> 결과.written().add(다)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("묶음이 2,000개여도 결과를 빠짐없이 모은다")
    void 묶음이_많아도_빠짐없이_모은다() {
        // given
        Function<Batch, Mono<TupleWriteResult>> 늘_성공 = batch -> Mono.just(batch.succeeded());

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(2_000), 늘_성공).block();

        // then
        assertThat(결과.written()).hasSize(2_000)
                .contains(RelationTuple.directMember("user0", "DEV002"), RelationTuple.directMember("user1999", "DEV002"));
        assertThat(결과.failures()).isEmpty();
    }

    @Test
    @DisplayName("띄엄띄엄 실패한 묶음은 보낸 순서대로 실패에 담고 나머지는 쓴 줄에 담는다")
    void 실패는_보낸_순서대로다() {
        // given — 3번과 7번 묶음만 실패한다(연달아가 아니라 차단기는 서지 않는다)
        Function<Batch, Mono<TupleWriteResult>> 둘만_실패 = batch -> {
            String user = batch.tuples().get(0).user();
            return Mono.just(user.equals("user:user3") || user.equals("user:user7") ? batch.failed("거절") : batch.succeeded());
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(10), 둘만_실패).block();

        // then
        assertThat(결과.written()).hasSize(8);
        assertThat(결과.failures()).extracting(failure -> failure.tuple().user()).containsExactly("user:user3", "user:user7");
    }
}
```

`RelationTuple.directMember(id, group)` 의 `user()` 는 `"user:" + id` 다(`USER_TYPE + ":" + userId`). `TupleFailure` 는 `record TupleFailure(RelationTuple tuple, String reason)` 다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaRelationTupleWriterResultTest'`
Expected: 컴파일 실패 — `ResultAccumulator` 가 없다.

- [ ] **Step 3: 구현한다**

`ResultAccumulator` 를 writer 안에 더한다(`묶음_결과` 레코드 근처):

```java
    /**
     * 묶음 결과를 모은다(점검 P6, 설계 2026-10-07 §5). 묶음마다 지금까지의 누적 전체를 새 집합에 복사하면 비용이 묶음 수의 제곱이다 —
     * 튜플 11만이면 해시 삽입 약 1.2억 번. 더할 때는 가변 집합에 넣고 {@link #결과} 에서 한 번만 불변으로 만든다.
     * 구독마다 새로 만들고({@code collect} 의 공급자), {@code collect} 가 신호를 하나씩 주므로 잠그지 않는다.
     */
    static final class ResultAccumulator {

        private final Set<RelationTuple> written = new HashSet<>();
        private final Set<RelationTuple> deleted = new HashSet<>();
        private final List<TupleFailure> failures = new ArrayList<>();

        void 더한다(TupleWriteResult result) {
            written.addAll(result.written());
            deleted.addAll(result.deleted());
            failures.addAll(result.failures());
        }

        TupleWriteResult 결과() {
            return new TupleWriteResult(written, deleted, failures);
        }
    }
```

연속 실패 차단기의 `.reduce(TupleWriteResult.empty(), (누적, 묶음) -> { ... })` 를 다음으로 바꾼다. 셈 규칙과 주석은 그대로 옮긴다:

```java
                    .collect(ResultAccumulator::new, (누적기, 묶음) -> {
                        if (묶음.보낸_결과().isPresent()) {
                            TupleWriteResult result = 묶음.보낸_결과().get();
                            if (!멈춤.get()) {
                                if (!한_줄도_못_살렸다(result)) {
                                    연속_실패.set(0);
                                } else if (연속_실패.incrementAndGet() >= 연속_실패_한도) {
                                    멈춘_사유.set(result.failures().get(0).reason());
                                    멈춤.set(true);
                                }
                            }
                            누적기.더한다(result);
                        }
                        if (묶음.보내지_않은_결과().hasFailure()) {
                            누적기.더한다(묶음.보내지_않은_결과());
                        }
                    })
                    .map(ResultAccumulator::결과)
```

그 뒤의 `.doOnNext(누적 -> ...)`·`.flatMap(누적 -> ...)` 는 그대로 둔다.

`쪼개며_보낸다` 의 반쪽 합치기를 바꾼다:

```java
                        return Flux.fromIterable(batch.halves())
                                .concatMap(half -> 쪼개며_보낸다(half, send, 거절인가))
                                .collect(ResultAccumulator::new, ResultAccumulator::더한다)
                                .map(ResultAccumulator::결과);
```

`private static TupleWriteResult merge(...)` 를 지운다. 쓰지 않게 된 import 가 생기면 지운다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :authz-openfga:test`
Expected: PASS — 새 테스트 3개와 기존 차단기·단계·쪼개기·OpenFGA 컨테이너 테스트 전부.

- [ ] **Step 5: 커밋한다**

```bash
git add authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriterResultTest.java
git commit -F - <<'EOF'
perf: OpenFGA 쓰기 결과를 누적기 하나에 모으고 끝에서 한 번만 만든다 — 묶음마다 누적 전체를 복사하던 제곱 비용 제거(튜플 11만이면 해시 삽입 약 1.2억 → 11만, 점검 P6)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 7: 규모 — 쓰기 경합과 Entra `manager` (app-scim)

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteContentionScaleTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java` (S3 단정 하나 더함, ~463-466)

**Interfaces:**
- Consumes: Task 3·5 결선. `ScaleContainers`·`@ScaleTest`(있음). `MeterRegistry` 의 `scim.lock.wait` 타이머(있음).

- [ ] **Step 1: 새 규모 테스트를 쓴다**

```java
package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 쓰기 경합 (쓰기 길 설계 2026-10-07 §3.5·§4·§8). 서버 한 대에 동시 40개가 와도 서버 안 줄이 온 순서대로 차례를 줘 503 이 거의 없고,
 * 버리는 속성만 있는 PATCH(Entra 의 manager)는 락을 잡지 않는다. 시간은 기록만 한다 — DynamoDB Local 은 AWS 와 왕복 시간이 다르다.
 * 직원은 저장소에 직접 심는다(소속·튜플 없음) — 락을 쥐는 시간은 직원 하나를 읽고 저장하는 만큼이다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ScaleTest
class ScimWriteContentionScaleTest {

    private static final int 직원_수 = 400;
    private static final int 동시_수 = 40;
    private static final int 버리는_PATCH_수 = 1_000;
    private static final String MANAGER = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager";

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @LocalServerPort int port;
    @Autowired DirectoryStateRepository state;
    @Autowired MeterRegistry registry;

    private WebClient 웹() {
        return WebClient.create("http://127.0.0.1:" + port);
    }

    private static String 아이디(int i) {
        return "w%04d".formatted(i);
    }

    private long 락_대기_수() {
        Timer 대기 = registry.find("scim.lock.wait").timer();
        return 대기 == null ? 0 : 대기.count();
    }

    @Test
    @Order(1)
    @DisplayName("직원 400명을 저장소에 직접 심는다")
    void 심는다() {
        // when
        Flux.range(0, 직원_수)
                .concatMap(i -> state.saveUser(new DirectoryUser(아이디(i), "ext-" + 아이디(i), 아이디(i), "직원 " + i, null, true)))
                .blockLast(Duration.ofMinutes(5));

        // then
        assertThat(state.findUser(아이디(직원_수 - 1)).block()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("서버 한 대에 동시 40개로 400건 — 서버 안 줄이 온 순서대로 차례를 줘 503 이 1% 이하다(점검 P3, 설계 2026-10-07 §3.5)")
    void 동시_40개() {
        // given
        WebClient 웹 = 웹();
        long 시작 = System.currentTimeMillis();

        // when — 직원마다 표시명을 바꾼다. 락 안에서 직원을 읽고 저장한다
        Map<Integer, Integer> 응답 = Flux.range(0, 직원_수)
                .flatMap(i -> 웹.patch().uri("/scim/v2/Users/" + 아이디(i))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"replace","path":"displayName","value":"바뀐 %d"}]}
                                """.formatted(i))
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
                        .map(상태 -> Map.entry(i, 상태)), 동시_수)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block(Duration.ofMinutes(5));
        long 걸린_ms = System.currentTimeMillis() - 시작;

        // then
        Map<Integer, Integer> 집계 = new TreeMap<>();
        응답.values().forEach(상태 -> 집계.merge(상태, 1, Integer::sum));
        Timer 대기 = registry.find("scim.lock.wait").timer();
        System.out.printf("동시 %d × %d건: %s, %,dms(%.1f건/초), 락 대기 평균 %.0fms·최대 %.0fms%n",
                동시_수, 직원_수, 집계, 걸린_ms, 직원_수 * 1000.0 / 걸린_ms,
                대기.mean(TimeUnit.MILLISECONDS), 대기.max(TimeUnit.MILLISECONDS));
        assertThat(집계.keySet()).as("200 과 503 이외의 응답이 나왔다").isSubsetOf(200, 503);
        assertThat(집계.getOrDefault(503, 0)).as("503 은 1% 이하다").isLessThanOrEqualTo(직원_수 / 100);
        응답.forEach((i, 상태) -> {
            if (상태 == 200) {
                assertThat(state.findUser(아이디(i)).block().displayName()).isEqualTo("바뀐 " + i);
            }
        });
    }

    @Test
    @Order(3)
    @DisplayName("버리는 속성(Entra 의 manager)만 있는 PATCH 1,000건은 락을 잡지 않는다 — 모두 200 이고 락 대기 기록이 늘지 않는다(설계 2026-10-07 §4)")
    void 버리는_속성만_있는_PATCH_는_락을_잡지_않는다() {
        // given
        WebClient 웹 = 웹();
        long 전 = 락_대기_수();
        long 시작 = System.currentTimeMillis();

        // when
        Map<Integer, Long> 집계 = Flux.range(0, 버리는_PATCH_수)
                .flatMap(i -> 웹.patch().uri("/scim/v2/Users/" + 아이디(i % 직원_수))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("""
                                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                                 "Operations":[{"op":"replace","path":"%s","value":{"value":"%s"}}]}
                                """.formatted(MANAGER, 아이디((i + 1) % 직원_수)))
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())), 16)
                .collect(Collectors.groupingBy(상태 -> 상태, TreeMap::new, Collectors.counting()))
                .block(Duration.ofMinutes(5));
        long 걸린_ms = System.currentTimeMillis() - 시작;

        // then
        System.out.printf("버리는 속성만 있는 PATCH %,d건(동시 16): %s, %,dms%n", 버리는_PATCH_수, 집계, 걸린_ms);
        assertThat(집계).containsOnlyKeys(200);
        assertThat(락_대기_수()).as("락을 잡지 않았다").isEqualTo(전);
    }
}
```

- [ ] **Step 2: S3 에 단정을 더한다**

`ScimScaleScenarioTest.S3_락_경합` 의 `isSubsetOf(200, 503)` 단정 바로 뒤에 더한다:

```java
            assertThat(집계.getOrDefault(503, 0))
                    .as("서버 안 줄 — 동시 16개는 한도(3초) 안에 모두 차례를 받는다(설계 2026-10-07 §3.5)")
                    .isZero();
```

- [ ] **Step 3: 새 규모 테스트를 돌린다**

Run: `./gradlew :app-scim:scaleTest --tests '*ScimWriteContentionScaleTest'` (Bash 타임아웃 10분)
Expected: PASS (3개).

출력의 두 줄("동시 40 × 400건 …", "버리는 속성만 있는 PATCH …")을 보고서에 옮긴다. S3 는 `ScimScaleScenarioTest` 전체가 5천 명 적재라 길어 여기서 돌리지 않는다. 컨트롤러의 `scaleTest` 가 돌린다.

- [ ] **Step 4: 커밋한다**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteContentionScaleTest.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java
git commit -F - <<'EOF'
test: 쓰기 경합 규모 — 서버 한 대 동시 40 × 400건 503 1% 이하, 버리는 속성만 있는 PATCH 1,000건은 락 대기 0, S3(동시 16) 503 0건 단정(점검 P3·⑤-2 이월)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 8: 문서 (README, 점검 문서, 이월 표시, 스펙)

**Files:**
- Modify: `README.md`
  - 락 절의 표(~737-740)
  - 락 설정 표의 `lock-acquire-timeout` 행(~752)
  - 지표 표의 `scim.lock.wait` 행(~801)
  - SCIM 절의 버리는 속성 문단(~634)
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md` (P3 ~100, P6 ~103 행)
- Modify: `docs/superpowers/specs/2026-10-05-scim-error-signals-design.md` §11 (재적재 중 획득 재시도 줄)
- Modify: `docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md` §12 (버린 연산만 있는 직원 PATCH 줄)
- Modify: `docs/superpowers/specs/2026-10-07-write-paths-design.md` (구현 중 정한 것 — 있을 때만)

- [ ] **Step 1: README 를 고친다**

1. 락 절 "락을 못 잡았을 때의 동작은 두 경로가 다르다" 표의 SCIM 쓰기 행을 다음으로 바꾼다:

```markdown
| SCIM 쓰기 | 서버 안에서 온 순서대로 줄을 서고, 차례가 된 요청 하나만 락을 시도한다. 다른 서버가 쥐었으면 짧고 무작위한 간격(10ms 에서 두 배씩 100ms 까지, full jitter)으로 다시 시도한다. `lock-acquire-timeout` 안에 못 잡으면 503. 쥔 쪽이 재적재·동기화면 다시 시도하지 않고 바로 503(`Retry-After` 60초) |
```

2. 표 아래 문단 끝("이 비대칭은 실수가 아니라 결정이다.") 다음에 한 문단을 더한다:

```markdown
**같은 서버의 SCIM 쓰기는 줄을 선다(설계 2026-10-07 §3).** 앞 요청이 락을 반납하면 줄의 다음 요청이 곧바로 잡는다 — 예전처럼 200ms 고정 간격으로
다시 두드리느라 락이 비는 시간이 없고, 먼저 온 요청이 먼저 잡는다. DynamoDB 락 항목을 두드리는 것은 서버마다 많아야 하나다. 서버끼리는 순서를
보장하지 않는다. 전역 락 하나라 쓰기 처리량의 상한(락을 쥐는 시간의 역수)은 서버 수와 무관하다.
```

3. `dynamodb.lock-acquire-timeout` 행의 설명 첫 문장 "락 획득 재시도 대기 한도" 를 "락 획득 대기 한도 — 서버 안 줄에서 기다린 시간과 다른 서버와 겨룬 시간이 모두 든다" 로 바꾼다. `Retry-After` 괄호도 "쥔 쪽이 SCIM 쓰기면 2초, 재적재·동기화면 재시도 없이 바로 60초" 로 맞춘다.
4. 지표 표 `scim.lock.wait` 행 설명에 "(서버 안 줄에서 기다린 시간 포함)" 을 더한다.
5. SCIM 절의 "RFC 7643 이 정의했지만 저장하지 않는 속성은 path 로 와도, 경로 없는 값으로 와도 받아서 버린다" 항목 끝에 한 문장을 더한다:

```markdown
PATCH 의 연산이 모두 이런 속성이면(예: Entra 의 `manager`) 락을 잡지 않고 지금 모습을 200 으로 돌려준다 — 저장하는 속성이 하나라도 섞이면(같은 값 포함) 락 안에서 처리한다.
```

- [ ] **Step 2: 점검 문서와 이월 표시를 고친다**

1. `2026-09-28-full-audit.md` 표 P3 행 끝에 ` **→ 일부 해결(2026-10-07, 슬라이드 ⑥-2) — 빈 시간·서버 안 순서는 해결, 처리량 상한은 남음**` 을 붙인다.
2. 같은 표 P6 행 끝에 ` **→ 해결(2026-10-07, 슬라이드 ⑥-2)**` 를 붙인다.
3. `2026-10-05-scim-error-signals-design.md` §11 의 "재적재가 락을 쥔 동안(마지막 실패 60초) SCIM 쓰기의 획득 재시도를 …" 줄 끝에 ` **→ 해결(2026-10-07, ⑥-2)**` 를 붙인다.
4. `2026-10-06-scim-request-interpretation-design.md` §12 의 "**버린 연산만 있는 직원 PATCH 도 락 안에서 직원 전체 diff·Check·저장을 돈다**" 줄 끝에 ` **→ 해결(2026-10-07, ⑥-2 — 모든 연산이 버리는 속성인 PATCH 만 락 없이)**` 를 붙인다. 같은 문서의 "⑤-1 §11 의 후속(재적재 중 락 획득 재시도 조기 종료)" 줄에도 ` **→ 해결(2026-10-07, ⑥-2)**` 를 붙인다.

- [ ] **Step 3: 스펙에 구현 중 정한 것을 적는다**

앞 과제 보고서에 계획과 달리 정한 것이 있으면 `2026-10-07-write-paths-design.md` 의 해당 절 끝에 "구현 중 정한 것:" 한 줄로 적는다(한 구절의 까닭과 함께). 없으면 이 단계는 건너뛴다. §10 은 건드리지 않는다.

- [ ] **Step 4: 커밋한다**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-05-scim-error-signals-design.md docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md docs/superpowers/specs/2026-10-07-write-paths-design.md
git commit -F - <<'EOF'
docs: ⑥-2 README — SCIM 쓰기는 서버 안 줄 + 서버끼리 백오프, 재적재·동기화 중엔 바로 503, 버리는 속성만 있는 PATCH 는 락 없이; 점검 P3 일부·P6 해결, ⑤-1·⑤-2 이월 해결 표시

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```
