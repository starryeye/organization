# SCIM 쓰기 경로 동시성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** app-scim 을 여러 대로 띄워도 동시 쓰기가 서로를 덮어쓰지 않고, 이미 어긋난 튜플은 그 튜플을 만질 때 걷어내진다.

**Architecture:** DynamoDB 조건부 쓰기로 만든 전역 리스 락이 SCIM 쓰기와 재적재를 인스턴스 전체에서 직렬화한다(예방). diff 의 기준선을 "DynamoDB 상태에서 유도한 것"에서 "OpenFGA 에 BatchCheck 로 물어본 실제"로 바꿔, 락이 못 막은 어긋남을 다음 터치에 걷어낸다(치유).

**Tech Stack:** Java 17, Spring Boot 3.5.16, Reactor, AWS SDK v2 (DynamoDB), openfga-sdk 0.9.11, JUnit 5, AssertJ, Testcontainers

**Spec:** `docs/superpowers/specs/2026-09-01-scim-concurrency-design.md`

## Global Constraints

- **테스트는 BDD 주석(given/when/then)과 한글 `@DisplayName`** 을 쓴다. 기존 테스트가 전부 그렇다.
- **Lombok** 을 쓴다 (`@RequiredArgsConstructor`, `@Slf4j`). 생성자를 직접 쓰지 않는다.
- **AssertJ** 로 단언한다. JUnit 의 `assertEquals` 를 쓰지 않는다.
- **열거 API(`Read`/`ListObjects`) 금지.** `Check`/`BatchCheck` 는 제한 없이 쓴다.
- **OpenFGA → DynamoDB 순서를 바꾸지 않는다.** 반대로 하면 중간 실패가 영구 어긋남이 된다 (설계 §3).
- **BatchCheck 실패 시 상태 기준선으로 폴백하지 않는다.** 조용히 옛 동작으로 돌아가고, 그게 하필 어긋남이 생기는 순간이다 (설계 §6).
- 락 TTL 기본 **30초**, 재적재 갱신 주기 **10초**, 획득 대기 한도 **3초** (설계 §4.4). 전부 설정으로 뺀다.
- 락 아이템: `PK=LOCK#SCIM_WRITE`, `SK=META`, 속성 `token`/`holder`/`purpose`/`expiresAt` (설계 §4.2).
- BatchCheck 청크 크기 **50** (OpenFGA 서버 기본 상한, 설계 §5.3).

---

## File Structure

**새로 만드는 것**

| 파일 | 책임 |
|---|---|
| `core/.../port/MutationLock.java` | 락 포트. 획득/반납/갱신 |
| `core/.../port/LockLease.java` | 획득 결과. 토큰과 만료를 들고 다닌다 |
| `core/.../usecase/LockUnavailableException.java` | 획득 실패. 503 으로 매핑된다 |
| `storage-dynamodb/.../DynamoDbMutationLock.java` | 조건부 쓰기 구현 |
| `core/src/testFixtures/.../fake/FakeMutationLock.java` | 유스케이스 테스트용 |

**고치는 것**

| 파일 | 무엇을 |
|---|---|
| `core/.../port/RelationTupleChecker.java` | 배치 메서드 `existing` 추가 |
| `authz-openfga/.../OpenFgaRelationTupleChecker.java` | `batchCheck` 로 구현 |
| `core/src/testFixtures/.../fake/FakeTupleChecker.java` | `existing` 구현 |
| `core/.../usecase/IncrementalSyncUseCase.java` | 락 획득, 기준선을 Check 로 |
| `core/.../usecase/ScimRebuildUseCase.java` | 같은 락을 잡고 갱신 |
| `storage-dynamodb/.../DynamoDbProperties.java` | 락 설정 |
| `app-scim/.../ScimUseCaseConfig.java` | 배선 |
| `app-scim/.../ScimSyncMetrics.java` (신규) | 드리프트·락 지표 |

**지우는 것**

| 파일 | 왜 |
|---|---|
| `core/.../usecase/MutationGate.java` | 분산 락이 흡수 (설계 §4.5) |
| `core/src/test/.../MutationGateTest.java` | 위와 함께 |

`MutationsSuspendedException` 은 **남긴다** — 재적재 경합에 계속 쓰인다.

---

## Task 1: 락 포트와 인메모리 가짜

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/LockLease.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/LockUnavailableException.java`
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/fake/FakeMutationLockTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `MutationLock.acquire(LockPurpose purpose) : Mono<LockLease>` — 못 잡으면 `LockUnavailableException`
  - `MutationLock.release(LockLease lease) : Mono<Void>`
  - `MutationLock.renew(LockLease lease) : Mono<LockLease>` — 리스를 잃었으면 `LockUnavailableException`
  - `LockLease` = `record LockLease(String token, Instant expiresAt)`
  - `LockPurpose` = `enum { WRITE, REBUILD }`
  - `FakeMutationLock` — `acquired`/`released` 카운터, `failAcquire` 플래그

- [ ] **Step 1: 포트와 값 타입을 만든다**

`LockLease.java`:

```java
package dev.starryeye.organization.core.port;

import java.time.Instant;

/**
 * 락을 쥐고 있다는 증거. {@code token} 이 이번 점유를 식별한다.
 *
 * <p>반납·갱신이 이 토큰을 조건으로 건다. 토큰 없이 반납하면 <b>내 리스가 만료돼 남이
 * 가져간 뒤에 남의 락을 풀어버린다</b> (설계 §4.3).
 */
public record LockLease(String token, Instant expiresAt) {
}
```

`MutationLock.java`:

```java
package dev.starryeye.organization.core.port;

import reactor.core.publisher.Mono;

/**
 * SCIM 쓰기와 재적재를 인스턴스 전체에서 직렬화하는 전역 락 (설계 §4).
 *
 * <p><b>왜 전역인가.</b> 엔티티별 락은 "무엇을 잠글지 정하는 것 자체가 읽기" 라는 난점이
 * 있다. 가용성 목적의 배포에서는 동시 쓰기가 드물어 직렬화 비용을 거의 치르지 않으므로
 * 그 복잡도를 사지 않는다 (설계 §4.1).
 *
 * <p><b>리스다.</b> 쥔 쪽이 죽어도 {@code expiresAt} 이 지나면 다른 쪽이 가져간다.
 * 대신 살아있는데 만료될 수 있어 완벽한 상호 배제가 아니다 (설계 §4.7).
 */
public interface MutationLock {

    /** 못 잡으면 {@link dev.starryeye.organization.core.usecase.LockUnavailableException}. */
    Mono<LockLease> acquire(LockPurpose purpose);

    /** 내 토큰일 때만 푼다. 아니면 경고만 남기고 조용히 끝낸다 — 일은 이미 끝났다. */
    Mono<Void> release(LockLease lease);

    /** 만료를 미룬다. 이미 리스를 잃었으면 {@code LockUnavailableException}. */
    Mono<LockLease> renew(LockLease lease);

    enum LockPurpose {
        WRITE,
        REBUILD
    }
}
```

`LockUnavailableException.java`:

```java
package dev.starryeye.organization.core.usecase;

/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다. 호출자는 503 으로 옮긴다 —
 * IdP 는 503 을 재시도 신호로 읽는다 (설계 §6).
 */
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 가짜 구현의 실패 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/fake/FakeMutationLockTest.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 락이 진짜 락의 계약을 흉내내는지 확인한다. 유스케이스 테스트가 이것에 기대므로
 * 여기가 틀리면 그 위의 테스트가 통째로 헛돈다.
 */
class FakeMutationLockTest {

    @Test
    @DisplayName("획득하면 리스를 주고, 반납하면 다시 잡을 수 있다")
    void 획득과_반납이_짝을_이룬다() {
        // given
        var lock = new FakeMutationLock();

        // when
        var lease = lock.acquire(LockPurpose.WRITE).block();

        // then
        assertThat(lease).isNotNull();
        assertThat(lease.token()).isNotBlank();
        assertThat(lock.acquired).hasValue(1);

        // when
        lock.release(lease).block();

        // then
        assertThat(lock.released).hasValue(1);
        assertThat(lock.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("쥐고 있는 동안에는 두 번째 획득이 실패한다")
    void 쥐고_있으면_두_번째는_실패한다() {
        // given
        var lock = new FakeMutationLock();
        lock.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> lock.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("failAcquire 를 켜면 획득이 항상 실패한다 — 503 경로를 재현한다")
    void 실패를_강제할_수_있다() {
        // given
        var lock = new FakeMutationLock();
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> lock.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("반납한 리스를 갱신하려 하면 실패한다 — 리스 상실을 재현한다")
    void 잃은_리스는_갱신되지_않는다() {
        // given
        var lock = new FakeMutationLock();
        var lease = lock.acquire(LockPurpose.WRITE).block();
        lock.release(lease).block();

        // when, then
        assertThatThrownBy(() -> lock.renew(lease).block())
                .isInstanceOf(LockUnavailableException.class);
    }
}
```

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*FakeMutationLockTest*'`
Expected: FAIL — `FakeMutationLock` 이 없어 컴파일 실패

- [ ] **Step 4: 가짜를 만든다**

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 프로세스 안에서만 도는 락. 유스케이스가 락을 <b>제대로 잡고 제대로 반납하는지</b> 를
 * 보는 데 쓴다. 분산 동작 자체는 {@code DynamoDbMutationLockTest} 가 본다.
 */
public class FakeMutationLock implements MutationLock {

    public final AtomicInteger acquired = new AtomicInteger();
    public final AtomicInteger released = new AtomicInteger();
    public final AtomicInteger renewed = new AtomicInteger();

    /** 켜면 획득이 항상 실패한다 — 503 경로를 재현하는 데 쓴다. */
    public boolean failAcquire = false;

    private final AtomicReference<String> heldToken = new AtomicReference<>();

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return Mono.defer(() -> {
            if (failAcquire) {
                return Mono.error(new LockUnavailableException("락 획득 실패(테스트)"));
            }
            String token = UUID.randomUUID().toString();
            if (!heldToken.compareAndSet(null, token)) {
                return Mono.error(new LockUnavailableException("이미 다른 쪽이 쥐고 있다(테스트)"));
            }
            acquired.incrementAndGet();
            return Mono.just(new LockLease(token, Instant.now().plusSeconds(30)));
        });
    }

    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.fromRunnable(() -> {
            if (heldToken.compareAndSet(lease.token(), null)) {
                released.incrementAndGet();
            }
        });
    }

    @Override
    public Mono<LockLease> renew(LockLease lease) {
        return Mono.defer(() -> {
            if (!lease.token().equals(heldToken.get())) {
                return Mono.error(new LockUnavailableException("리스를 잃었다(테스트)"));
            }
            renewed.incrementAndGet();
            return Mono.just(new LockLease(lease.token(), Instant.now().plusSeconds(30)));
        });
    }
}
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*FakeMutationLockTest*'`
Expected: PASS 4건

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java core/src/main/java/dev/starryeye/organization/core/port/LockLease.java core/src/main/java/dev/starryeye/organization/core/usecase/LockUnavailableException.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java core/src/test/java/dev/starryeye/organization/core/fake/FakeMutationLockTest.java
git commit -m "feat: 분산 락 포트와 인메모리 가짜"
```

---

## Task 2: DynamoDB 락 구현

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbProperties.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`

**Interfaces:**
- Consumes: `MutationLock`, `LockLease`, `LockPurpose`, `LockUnavailableException` (Task 1)
- Produces: `DynamoDbMutationLock(DynamoDbAsyncClient, DynamoDbProperties, Clock, String holderId)` — Spring 빈으로 등록됨

- [ ] **Step 1: 락 키 상수를 더한다**

`Keys.java` 의 `GROUP_PREFIX` 선언 아래에 추가:

```java
    /** 전역 변경 락. 파티션 하나에 아이템 하나다 (설계 §4.2). */
    public static final String LOCK_PK = "LOCK#SCIM_WRITE";
```

- [ ] **Step 2: 설정을 더한다**

`DynamoDbProperties.java` 에 필드 세 개를 추가한다 (기존 필드와 같은 스타일, Lombok `@Getter @Setter` 가 이미 클래스에 붙어 있다):

```java
    /** 락 리스 길이. SCIM 쓰기 p99 보다 한참 길어야 한다 — 짧으면 살아있는데 만료된다. */
    private Duration lockTtl = Duration.ofSeconds(30);

    /** 락 획득 대기 한도. 넘으면 503 이 나가고 IdP 가 재시도한다. */
    private Duration lockAcquireTimeout = Duration.ofSeconds(3);

    /** 재적재처럼 오래 쥐는 작업의 갱신 주기. TTL 보다 충분히 짧아야 한다. */
    private Duration lockRenewInterval = Duration.ofSeconds(10);
```

`import java.time.Duration;` 를 더한다.

- [ ] **Step 3: 실패 테스트를 쓴다**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분산 락의 계약 (설계 §4.3).
 *
 * <p>가장 중요한 것은 <b>토큰 조건</b>이다. 없으면 내 리스가 만료돼 남이 가져간 뒤에
 * 내가 반납하면서 남의 락을 풀어버린다 — 그 순간 두 인스턴스가 동시에 쓴다.
 */
class DynamoDbMutationLockTest extends DynamoDbTestSupport {

    /** 테스트가 시간을 손으로 옮겨 만료를 재현한다. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        void 앞으로(Duration amount) {
            now = now.plus(amount);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;
    private DynamoDbMutationLock 인스턴스1;
    private DynamoDbMutationLock 인스턴스2;

    @BeforeEach
    void 락을_준비한다() {
        clock = new MutableClock();
        properties.setLockTtl(Duration.ofSeconds(30));
        인스턴스1 = new DynamoDbMutationLock(client, properties, clock, "instance-1");
        인스턴스2 = new DynamoDbMutationLock(client, properties, clock, "instance-2");
    }

    @Test
    @DisplayName("한쪽이 쥐고 있으면 다른 인스턴스는 획득하지 못한다")
    void 쥐고_있으면_다른_쪽은_못_잡는다() {
        // given
        인스턴스1.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("반납하면 다른 인스턴스가 곧바로 획득한다")
    void 반납하면_다른_쪽이_잡는다() {
        // given
        var lease = 인스턴스1.acquire(LockPurpose.WRITE).block();

        // when
        인스턴스1.release(lease).block();

        // then
        assertThat(인스턴스2.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("리스가 만료되면 반납하지 않았어도 다른 인스턴스가 가져간다")
    void 만료되면_다른_쪽이_가져간다() {
        // given — 쥔 인스턴스가 죽어 반납하지 못한 상황이다
        인스턴스1.acquire(LockPurpose.WRITE).block();

        // when
        clock.앞으로(Duration.ofSeconds(31));

        // then
        assertThat(인스턴스2.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("남의 토큰으로 반납하면 남의 락이 풀리지 않는다")
    void 남의_락은_풀지_못한다() {
        // given — 1이 만료돼 2가 가져갔다. 1은 그 사실을 모른 채 반납하러 온다.
        var 낡은리스 = 인스턴스1.acquire(LockPurpose.WRITE).block();
        clock.앞으로(Duration.ofSeconds(31));
        인스턴스2.acquire(LockPurpose.WRITE).block();

        // when — 1이 자기 토큰으로 반납을 시도한다
        인스턴스1.release(낡은리스).block();

        // then — 2의 락은 그대로여야 한다. 풀렸다면 세 번째가 들어와 동시에 쓴다.
        assertThatThrownBy(() -> 인스턴스1.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("갱신하면 만료가 미뤄져 다른 인스턴스가 가져가지 못한다")
    void 갱신하면_만료가_미뤄진다() {
        // given
        var lease = 인스턴스1.acquire(LockPurpose.WRITE).block();

        // when — 만료 직전에 갱신한다
        clock.앞으로(Duration.ofSeconds(25));
        LockLease 갱신됨 = 인스턴스1.renew(lease).block();
        clock.앞으로(Duration.ofSeconds(20));

        // then — 원래 만료(30초)는 지났지만 갱신했으므로 아직 유효하다
        assertThat(갱신됨).isNotNull();
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("리스를 잃은 뒤 갱신하려 하면 실패한다 — 재적재가 이걸 보고 멈춘다")
    void 잃은_리스는_갱신되지_않는다() {
        // given
        var 낡은리스 = 인스턴스1.acquire(LockPurpose.WRITE).block();
        clock.앞으로(Duration.ofSeconds(31));
        인스턴스2.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> 인스턴스1.renew(낡은리스).block())
                .isInstanceOf(LockUnavailableException.class);
    }
}
```

- [ ] **Step 4: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest*'`
Expected: FAIL — `DynamoDbMutationLock` 이 없어 컴파일 실패

- [ ] **Step 5: 구현한다**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DynamoDB 조건부 쓰기로 만든 전역 리스 락 (설계 §4).
 *
 * <p>새 테이블을 만들지 않는다. 기존 단일 테이블에 아이템 하나로 얹는다 — app-ldap 과
 * app-scim 은 서로 다른 테이블을 쓰므로 락도 자연히 분리된다.
 *
 * <p><b>토큰 조건이 핵심이다.</b> 반납과 갱신에 {@code token} 조건을 걸지 않으면, 내 리스가
 * 만료돼 남이 가져간 뒤에 내가 반납하면서 <b>남의 락을 풀어버린다</b>. 그 순간 두 인스턴스가
 * 동시에 쓴다.
 *
 * <p><b>완벽한 상호 배제가 아니다.</b> GC 정지 등으로 살아있는데 리스가 만료되면 늦은 쓰기가
 * 새어나갈 수 있다. OpenFGA 가 펜싱 토큰을 지원하지 않아 원천 차단이 불가능하다 —
 * 쓰기 직전 리스 재확인과 Check 기준선이 이를 좁힌다 (설계 §4.7).
 */
@Slf4j
@RequiredArgsConstructor
public class DynamoDbMutationLock implements MutationLock {

    private static final String TOKEN = "token";
    private static final String HOLDER = "holder";
    private static final String PURPOSE = "purpose";
    private static final String EXPIRES_AT = "expiresAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;
    /** 누가 쥐고 있는지 로그로 알아보기 위한 값. 배제 판단에는 쓰지 않는다. */
    private final String holderId;

    @Override
    public Mono<LockLease> acquire(LockPurpose purpose) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(properties.getLockTtl());
            String token = UUID.randomUUID().toString();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put(Keys.PK, Attrs.s(Keys.LOCK_PK));
            item.put(Keys.SK, Attrs.s(Keys.META));
            item.put(TOKEN, Attrs.s(token));
            item.put(HOLDER, Attrs.s(holderId));
            item.put(PURPOSE, Attrs.s(purpose.name()));
            item.put(EXPIRES_AT, Attrs.n(expiresAt.getEpochSecond()));

            return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                            .tableName(properties.getTableName())
                            .item(item)
                            // 아무도 없거나, 있어도 이미 만료됐으면 가져간다
                            .conditionExpression("attribute_not_exists(#pk) OR #expiresAt < :now")
                            .expressionAttributeNames(Map.of("#pk", Keys.PK, "#expiresAt", EXPIRES_AT))
                            .expressionAttributeValues(Map.of(":now", Attrs.n(now.getEpochSecond())))
                            .build()))
                    .thenReturn(new LockLease(token, expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("다른 인스턴스가 변경 락을 쥐고 있습니다"));
        });
    }

    /**
     * 조건이 깨지면 <b>실패시키지 않는다.</b> 이미 만료돼 남이 가져갔다는 뜻인데, 그때 우리가
     * 할 일은 없다 — 작업은 이미 끝났고 응답은 나가야 한다. 대신 경고를 남긴다: TTL 이
     * 작업 시간보다 짧다는 신호다.
     */
    @Override
    public Mono<Void> release(LockLease lease) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                        .conditionExpression("#token = :token")
                        .expressionAttributeNames(Map.of("#token", TOKEN))
                        .expressionAttributeValues(Map.of(":token", Attrs.s(lease.token())))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> {
                    log.warn("변경 락 반납 실패 — 이미 리스를 잃은 상태다. TTL({})이 작업 시간보다 짧다는 신호다",
                            properties.getLockTtl());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<LockLease> renew(LockLease lease) {
        return Mono.defer(() -> {
            Instant expiresAt = clock.instant().plus(properties.getLockTtl());
            return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                            .tableName(properties.getTableName())
                            .key(Map.of(Keys.PK, Attrs.s(Keys.LOCK_PK), Keys.SK, Attrs.s(Keys.META)))
                            .updateExpression("SET #expiresAt = :expiresAt")
                            .conditionExpression("#token = :token")
                            .expressionAttributeNames(Map.of("#expiresAt", EXPIRES_AT, "#token", TOKEN))
                            .expressionAttributeValues(Map.of(
                                    ":expiresAt", Attrs.n(expiresAt.getEpochSecond()),
                                    ":token", Attrs.s(lease.token())))
                            .build()))
                    .thenReturn(new LockLease(lease.token(), expiresAt))
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            new LockUnavailableException("변경 락 리스를 잃었습니다"));
        });
    }
}
```

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest*'`
Expected: PASS 6건

- [ ] **Step 7: 테스트가 실제로 무는지 확인한다**

`DynamoDbMutationLock.release` 의 `conditionExpression("#token = :token")` 과 그 뒤의 두 `expressionAttribute*` 줄을 지우고 (조건 없는 삭제로 만들고) 다시 돌린다.

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest*'`
Expected: `남의_락은_풀지_못한다` FAIL

확인했으면 되돌린다.

- [ ] **Step 8: 빈으로 등록한다**

`DynamoDbConfig.java` 에 추가:

```java
    /**
     * 인스턴스 식별자. 배제 판단에는 쓰지 않고 "누가 쥐고 있나" 를 로그로 보기 위한 값이라
     * 재시작마다 달라져도 무방하다.
     */
    @Bean
    public MutationLock mutationLock(DynamoDbAsyncClient client, DynamoDbProperties properties,
                                     Clock clock) {
        return new DynamoDbMutationLock(client, properties, clock,
                java.util.UUID.randomUUID().toString());
    }
```

`import dev.starryeye.organization.core.port.MutationLock;` 를 더한다.

- [ ] **Step 9: 커밋**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/ storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java
git commit -m "feat: DynamoDB 조건부 쓰기로 만든 전역 리스 락"
```

---

## Task 3: BatchCheck 로 실제 튜플 읽기

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/RelationTupleChecker.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleChecker.java`
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaBatchCheckTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `RelationTupleChecker.existing(Set<RelationTuple> candidates) : Mono<Set<RelationTuple>>` — 후보 중 OpenFGA 에 실제로 있는 것만

- [ ] **Step 1: 포트에 배치 메서드를 더한다**

`RelationTupleChecker.java` 의 `check` 아래에 추가:

```java
    /**
     * 후보 중 OpenFGA 에 <b>실제로 있는 것만</b> 돌려준다 (설계 §5.3).
     *
     * <p>diff 의 기준선을 DynamoDB 상태가 아니라 OpenFGA 실제 상태로 삼기 위한 것이다.
     * 상태에서 유도한 기준선은 "있어야 했던 것" 이라, 어긋난 튜플을 영원히 못 본다.
     *
     * <p>후보가 비면 호출 없이 빈 집합을 돌려준다.
     */
    Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates);
```

`import java.util.Set;` 를 더한다.

- [ ] **Step 2: 가짜에 구현을 더한다**

`FakeTupleChecker.java` 에 추가 (기존 `check` 를 재사용해 지연·실패 설정이 그대로 먹게 한다):

```java
    @Override
    public Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates) {
        return reactor.core.publisher.Flux.fromIterable(candidates)
                .concatMap(tuple -> check(tuple).map(found -> java.util.Map.entry(tuple, found)))
                .filter(java.util.Map.Entry::getValue)
                .map(java.util.Map.Entry::getKey)
                .collect(LinkedHashSet<RelationTuple>::new, Set::add)
                .map(found -> (Set<RelationTuple>) found);
    }
```

- [ ] **Step 3: 실패 테스트를 쓴다**

`authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaBatchCheckTest.java`:

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchCheck 로 "OpenFGA 에 실제로 있는 튜플" 을 읽어온다 (설계 §5.3).
 *
 * <p>열거 API 를 쓰지 않고도 <b>후보를 알고 있다면</b> 실제 상태를 알 수 있다는 것이 요점이다.
 */
@Testcontainers
class OpenFgaBatchCheckTest {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private StoreBootstrapper bootstrapper;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 준비한다() {
        var properties = new OpenFgaProperties();
        properties.setApiUrl("http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        properties.setStoreName("batch-check-test-" + System.nanoTime());
        bootstrapper = new StoreBootstrapper(properties);
        bootstrapper.resolveStore().block();
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    private void 튜플을_심는다(List<RelationTuple> tuples) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(tuples.stream()
                    .map(t -> new ClientTupleKey().user(t.user()).relation(t.relation())._object(t.object()))
                    .toList())).get();
        } catch (Exception e) {
            throw new IllegalStateException("튜플 심기 실패", e);
        }
    }

    @Test
    @DisplayName("후보 중 실제로 있는 튜플만 돌려준다")
    void 있는_것만_돌려준다() {
        // given — kim 만 심고 park 는 심지 않는다
        var 있는것 = RelationTuple.directMember("kim", "DEV001");
        var 없는것 = RelationTuple.directMember("park", "DEV001");
        튜플을_심는다(List.of(있는것));

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of(있는것, 없는것)).block();

        // then
        assertThat(실제).containsExactly(있는것);
    }

    @Test
    @DisplayName("후보가 비면 OpenFGA 를 부르지 않고 빈 집합을 준다")
    void 후보가_비면_빈_집합이다() {
        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of()).block();

        // then
        assertThat(실제).isEmpty();
    }

    @Test
    @DisplayName("배치 상한(50)을 넘는 후보도 나눠 물어 전부 확인한다")
    void 상한을_넘으면_나눠_묻는다() {
        // given — 120명 중 짝수 번째만 심는다. 청크 경계에서 빠뜨리면 여기서 드러난다.
        List<RelationTuple> 전체 = IntStream.range(0, 120)
                .mapToObj(i -> RelationTuple.directMember("user%03d".formatted(i), "DEV001"))
                .toList();
        List<RelationTuple> 심을것 = IntStream.range(0, 120)
                .filter(i -> i % 2 == 0)
                .mapToObj(전체::get)
                .toList();
        튜플을_심는다(심을것);

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.copyOf(전체)).block();

        // then
        assertThat(실제).hasSize(60);
        assertThat(실제).containsExactlyInAnyOrderElementsOf(심을것);
    }

    @Test
    @DisplayName("child 관계도 같은 방식으로 확인된다")
    void child도_확인된다() {
        // given
        var 있는것 = RelationTuple.child("DEV002", "DEV001");
        var 없는것 = RelationTuple.child("DEV003", "DEV001");
        튜플을_심는다(List.of(있는것));

        // when
        Set<RelationTuple> 실제 = checker.existing(Set.of(있는것, 없는것)).block();

        // then
        assertThat(실제).containsExactly(있는것);
    }
}
```

- [ ] **Step 4: 실패를 확인한다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaBatchCheckTest*'`
Expected: FAIL — `existing` 미구현

- [ ] **Step 5: 구현한다**

`OpenFgaRelationTupleChecker.java` 에 추가:

```java
    /**
     * 배치 상한만큼 잘라 물어본다. OpenFGA 서버 기본값이 요청당 50건이라 그보다 크게 보내면
     * 통째로 거절당한다 — 조직 하나가 50명을 넘는 것은 평범하므로 반드시 나눠야 한다.
     */
    private static final int BATCH_SIZE = 50;

    @Override
    public Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates) {
        if (candidates.isEmpty()) {
            return Mono.just(Set.of());
        }
        return bootstrapper.findExistingStore()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "OpenFGA store 가 아직 없어 BatchCheck 를 할 수 없다")))
                .flatMap(storeId -> Flux.fromIterable(List.copyOf(candidates))
                        .buffer(BATCH_SIZE)
                        .concatMap(chunk -> checkChunk(storeId, chunk))
                        .collect(LinkedHashSet<RelationTuple>::new, Set::add)
                        .map(found -> (Set<RelationTuple>) found));
    }

    /**
     * {@code correlationId} 로 응답과 요청을 잇는다. 응답 순서는 보장되지 않으므로
     * 인덱스로 짝지으면 <b>엉뚱한 튜플이 있다고 판단</b>한다.
     */
    private Flux<RelationTuple> checkChunk(String storeId, List<RelationTuple> chunk) {
        Map<String, RelationTuple> byCorrelationId = new LinkedHashMap<>();
        List<ClientBatchCheckItem> items = new ArrayList<>();
        for (int i = 0; i < chunk.size(); i++) {
            String correlationId = "c" + i;
            RelationTuple tuple = chunk.get(i);
            byCorrelationId.put(correlationId, tuple);
            items.add(new ClientBatchCheckItem()
                    .user(tuple.user())
                    .relation(tuple.relation())
                    ._object(tuple.object())
                    .correlationId(correlationId));
        }

        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.clientFor(storeId)
                                .batchCheck(new ClientBatchCheckRequest().checks(items));
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA batchCheck 호출 실패", e);
                    }
                })
                .flatMapIterable(response -> response.getResult().entrySet().stream()
                        .filter(entry -> Boolean.TRUE.equals(entry.getValue().isAllowed()))
                        .map(entry -> byCorrelationId.get(entry.getKey()))
                        .filter(java.util.Objects::nonNull)
                        .toList());
    }
```

필요한 임포트를 더한다:

```java
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
```

> **주의:** `ClientBatchCheckResponse.getResult()` 와 `ClientBatchCheckSingleResponse.isAllowed()` 의
> 정확한 시그니처를 `javap` 로 먼저 확인할 것. SDK 0.9.11 에서 이름이 다르면 그에 맞춘다.
> 확인 명령:
> `unzip -p ~/.gradle/caches/modules-2/files-2.1/dev.openfga/openfga-sdk/0.9.11/*/openfga-sdk-0.9.11.jar 'dev/openfga/sdk/api/client/model/ClientBatchCheckResponse.class' > /tmp/r.class && javap /tmp/r.class`

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaBatchCheckTest*'`
Expected: PASS 4건

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/RelationTupleChecker.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleChecker.java authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaBatchCheckTest.java
git commit -m "feat: BatchCheck 로 OpenFGA 의 실제 튜플을 읽는다"
```

---

## Task 4: 후보 집합 계산

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/CandidateTuplesTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: `TupleMapper.candidateTuples(DirectorySnapshot snapshot) : Set<RelationTuple>` — `active`·순환 필터를 적용하지 않은 멤버십 전체

- [ ] **Step 1: 실패 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/tuple/CandidateTuplesTest.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보 집합 (설계 §5.1).
 *
 * <p><b>왜 별도 계산이 필요한가.</b> {@link TupleMapper#toTuples} 는 "있어야 하는 튜플" 을
 * 준다 — 비활성 직원과 순환 간선을 빼고 준다. 그런데 우리가 OpenFGA 에 물어봐야 하는 것은
 * "혹시 있을지 모르는 튜플" 이다. 비활성이라 빠진 바로 그 튜플이 잘못 남아 있는 경우를
 * 잡으려는 것이므로, 필터를 적용하기 <b>전</b>의 멤버십에서 뽑아야 한다.
 */
class CandidateTuplesTest {

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, code + " 조직", Set.of(members));
    }

    private static DirectorySnapshot 스냅샷(Set<DirectoryUser> users, Set<DirectoryGroup> groups) {
        return new DirectorySnapshot(
                users.stream().collect(Collectors.toMap(DirectoryUser::id, Function.identity())),
                groups.stream().collect(Collectors.toMap(DirectoryGroup::id, Function.identity())));
    }

    @Test
    @DisplayName("비활성 직원의 튜플도 후보에 들어간다 — 잘못 남은 그것을 잡아야 하므로")
    void 비활성_직원도_후보다() {
        // given — kim 은 DEV001 멤버지만 비활성이다. §1 경합이 남기는 바로 그 모양이다.
        var snapshot = 스냅샷(
                Set.of(직원("kim", false), 직원("park", true)),
                Set.of(조직("DEV001", MemberRef.user("kim"), MemberRef.user("park"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — toTuples 라면 kim 이 빠지지만, 후보에는 있어야 한다
        assertThat(후보).contains(RelationTuple.directMember("kim", "DEV001"));
        assertThat(후보).contains(RelationTuple.directMember("park", "DEV001"));
        assertThat(TupleMapper.toTuples(snapshot).tuples())
                .as("대조: toTuples 는 비활성을 뺀다")
                .doesNotContain(RelationTuple.directMember("kim", "DEV001"));
    }

    @Test
    @DisplayName("순환을 만드는 간선도 후보에 들어간다 — 잘못 쓰였을 수 있으므로")
    void 순환_간선도_후보다() {
        // given — A -> B -> A 순환
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("A", MemberRef.group("B")), 조직("B", MemberRef.group("A"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — 두 간선 모두 후보다. toTuples 는 하나를 버린다.
        assertThat(후보).contains(
                RelationTuple.child("B", "A"),
                RelationTuple.child("A", "B"));
    }

    @Test
    @DisplayName("스냅샷에 없는 멤버가 참조돼도 후보에 들어간다")
    void 스냅샷에_없는_멤버도_후보다() {
        // given — DEV001 이 아직 스냅샷에 없는 choi 를 멤버로 적고 있다
        var snapshot = 스냅샷(Set.of(), Set.of(조직("DEV001", MemberRef.user("choi"))));

        // when
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(snapshot);

        // then — 그 튜플이 잘못 쓰여 있을 수 있으므로 확인 대상이다
        assertThat(후보).contains(RelationTuple.directMember("choi", "DEV001"));
    }

    @Test
    @DisplayName("멤버가 없는 조직은 후보를 만들지 않는다")
    void 멤버가_없으면_후보도_없다() {
        // given
        var snapshot = 스냅샷(Set.of(), Set.of(조직("DEV001")));

        // when, then
        assertThat(TupleMapper.candidateTuples(snapshot)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*CandidateTuplesTest*'`
Expected: FAIL — `candidateTuples` 없음

- [ ] **Step 3: 구현한다**

`TupleMapper.java` 에 public static 메서드를 더한다:

```java
    /**
     * 이 스냅샷의 멤버십에서 나올 수 있는 <b>모든</b> 튜플. `active` 필터도 순환 필터도
     * 적용하지 않는다 (설계 §5.1).
     *
     * <p><b>{@link #toTuples} 와 다른 질문에 답한다.</b> {@code toTuples} 는 "있어야 하는
     * 튜플" 이고 이쪽은 "혹시 있을지 모르는 튜플" 이다. 잘못 남은 튜플은 대개 비활성 직원의
     * 것이라(경합이 활성일 때 쓰고 지나갔으므로) 필터를 적용하면 정확히 그것을 놓친다.
     *
     * <p>이 집합은 <b>OpenFGA 에 물어볼 대상</b>일 뿐 쓰거나 지울 대상이 아니다.
     * 무엇을 쓰고 지울지는 이것과 {@code toTuples} 결과를 비교해 정한다.
     */
    public static Set<RelationTuple> candidateTuples(DirectorySnapshot snapshot) {
        Set<RelationTuple> candidates = new LinkedHashSet<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                candidates.add(switch (member.type()) {
                    case USER -> RelationTuple.directMember(
                            IdNormalizer.normalize(member.id()), IdNormalizer.normalize(group.id()));
                    case GROUP -> RelationTuple.child(
                            IdNormalizer.normalize(member.id()), IdNormalizer.normalize(group.id()));
                });
            }
        }
        return candidates;
    }
```

> `MemberRef.type()` 이 돌려주는 enum 의 정확한 상수명을 먼저 확인할 것:
> `grep -n "enum" core/src/main/java/dev/starryeye/organization/core/model/MemberRef.java`
> `USER`/`GROUP` 이 아니면 그에 맞춘다. `IdNormalizer` 적용 여부도
> `TupleMapper.toTuples` 가 하는 방식과 반드시 일치시킨다 — 다르면 같은 튜플이
> 후보와 목표에서 다른 문자열이 되어 영원히 지웠다 썼다 한다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*CandidateTuplesTest*'`
Expected: PASS 4건

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java core/src/test/java/dev/starryeye/organization/core/tuple/CandidateTuplesTest.java
git commit -m "feat: 후보 튜플 계산 — 필터 적용 전 멤버십 전체"
```

---

## Task 5: 쓰기 경로에 락과 Check 기준선 적용

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java`

**Interfaces:**
- Consumes: `MutationLock`/`LockLease`/`LockPurpose` (Task 1), `RelationTupleChecker.existing` (Task 3), `TupleMapper.candidateTuples` (Task 4)
- Produces: `IncrementalSyncUseCase(DirectoryStateRepository, RelationTupleWriter, RelationTupleChecker, MutationLock, int acquireRetries)` — 생성자에서 `MutationGate` 가 빠지고 셋이 들어온다.
  **Task 8 이 여기에 `DriftObserver` 를 하나 더 붙인다** — 그때 이 태스크의 테스트도 함께 고쳐야 한다

- [ ] **Step 1: 실패 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java`:

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기준선을 OpenFGA 실제 상태에서 읽는다 (설계 §5).
 *
 * <p>여기서 못박는 것은 <b>경합이 남긴 튜플을 다음 터치가 걷어낸다</b> 는 것이다.
 * 경합 자체를 재현하는 테스트는 타이밍에 기대 흔들리므로, 경합이 남겼을 결과를 직접 심는다.
 */
class IncrementalSyncDriftTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        // 재시도 0회. 락 획득 실패를 곧바로 관찰하기 위한 것이고, 재시도 자체는
        // 실제 대기가 필요해 여기서 볼 대상이 아니다.
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, 0);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    @Test
    @DisplayName("경합이 남긴 잘못된 튜플을 다음 터치가 걷어낸다")
    void 잘못된_튜플을_걷어낸다() {
        // given — §1 경합의 최종 상태를 그대로 만든다.
        // DynamoDB: kim 은 DEV001 멤버지만 비활성.
        state.users.put("kim", 직원("kim", false));
        state.users.put("park", 직원("park", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park"))));
        // OpenFGA: 있어서는 안 될 kim 의 튜플이 남아 있다.
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
        checker.allowed.add(RelationTuple.directMember("park", "DEV001"));

        // when — 아무 변경이나 DEV001 을 건드린다
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park")))).block();

        // then — 상태 기준선이었다면 델타가 비어 잘못된 튜플이 살아남는다
        assertThat(writer.deleted)
                .as("비활성 kim 의 튜플은 지워져야 한다")
                .contains(RelationTuple.directMember("kim", "DEV001"));
        assertThat(writer.written)
                .as("park 의 튜플은 이미 있으므로 다시 쓰지 않는다")
                .doesNotContain(RelationTuple.directMember("park", "DEV001"));
    }

    @Test
    @DisplayName("OpenFGA 에 빠진 튜플이 있으면 다시 쓴다")
    void 빠진_튜플을_다시_쓴다() {
        // given — 상태상 있어야 하는데 OpenFGA 에는 없다(커밋 직전 크래시 등)
        state.users.put("park", 직원("park", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("park"))));
        // checker.allowed 는 비어 있다 — OpenFGA 에 아무것도 없다

        // when
        useCase.upsertGroup(new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("park")))).block();

        // then
        assertThat(writer.written).contains(RelationTuple.directMember("park", "DEV001"));
    }

    @Test
    @DisplayName("변경 하나에 락을 정확히 한 번 잡고 반드시 반납한다")
    void 락을_잡고_반납한다() {
        // given
        state.users.put("kim", 직원("kim", true));

        // when
        useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).as("반납이 새면 이후 모든 변경이 영구히 막힌다").hasValue(1);
    }

    @Test
    @DisplayName("쓰기가 실패해도 락은 반납된다")
    void 실패해도_반납한다() {
        // given
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        writer.failAll = true;

        // when
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(Exception.class);

        // then
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("락을 못 잡으면 아무것도 쓰지 않고 실패한다")
    void 락을_못_잡으면_쓰지_않는다() {
        // given
        lock.failAcquire = true;
        state.users.put("kim", 직원("kim", true));

        // when, then
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(LockUnavailableException.class);
        assertThat(writer.written).isEmpty();
        assertThat(writer.deleted).isEmpty();
    }

    @Test
    @DisplayName("BatchCheck 가 실패하면 상태 기준선으로 폴백하지 않고 실패한다")
    void Check_실패는_폴백하지_않는다() {
        // given — 폴백하면 조용히 옛 동작으로 돌아가고, 그게 하필 어긋남이 생기는 순간이다
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.failFor(tuple -> true);

        // when, then
        assertThatThrownBy(() -> useCase.upsertUser(직원("kim", false)).block())
                .isInstanceOf(Exception.class);
        assertThat(writer.written).isEmpty();
        assertThat(writer.deleted).isEmpty();
        assertThat(lock.released).as("실패해도 락은 반납된다").hasValue(1);
    }
}
```

> `FakeTupleWriter` 에 `failAll` 플래그와 `written`/`deleted` 필드가 있는지 먼저 확인할 것:
> `cat core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java`
> 없으면 이 태스크에서 같은 이름으로 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncDriftTest*'`
Expected: FAIL — 생성자 시그니처 불일치로 컴파일 실패

- [ ] **Step 3: 생성자와 필드를 바꾼다**

`IncrementalSyncUseCase.java` 에서 `private final MutationGate gate;` 를 지우고 대신:

```java
    private final RelationTupleChecker checker;
    private final MutationLock lock;
```

`import` 를 정리한다 (`MutationGate` 제거, `RelationTupleChecker`·`MutationLock`·`LockLease` 추가).

- [ ] **Step 4: 네 개의 변경 진입점을 락으로 감싼다**

`upsertUser`/`upsertGroup`/`removeUser`/`removeGroup` 네 곳을 같은 모양으로 바꾼다.
`upsertUser` 예시:

```java
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
        return withLock(() -> upsertUserInternal(user));
    }
```

그리고 공통 헬퍼를 더한다:

```java
    /**
     * 변경 하나를 락 안에서 실행한다 (설계 §4).
     *
     * <p><b>왜 유스케이스가 잡나.</b> 핸들러마다 넣으면 나중에 경로가 하나 늘 때 조용히 빠지고,
     * 그 빠진 곳이 하필 다른 인스턴스와 경합한다. 여기 두면 네 경로가 빠짐없이 덮이고
     * 경로가 늘어도 자동으로 포함된다({@code MutationGate} 가 같은 이유로 여기 있었다).
     *
     * <p><b>반드시 반납한다.</b> 새면 리스가 만료될 때까지 모든 변경이 막힌다.
     * 성공·실패·취소 어느 경로로 끝나든 {@code doFinally} 가 반납한다.
     */
    private Mono<IncrementalSyncResult> withLock(Supplier<Mono<IncrementalSyncResult>> work) {
        return lock.acquire(MutationLock.LockPurpose.WRITE)
                // 밀리초 단위로 쥐는 락이라 즉시 503 을 내면 재시도만 늘어난다. 짧게 기다려보고
                // 그래도 안 되면 그때의 503 이 IdP 에게 의미 있는 신호가 된다 (설계 §4.4).
                .retryWhen(Retry.fixedDelay(acquireRetries, ACQUIRE_RETRY_DELAY)
                        .filter(LockUnavailableException.class::isInstance))
                .onErrorMap(Exceptions::isRetryExhausted,
                        error -> new LockUnavailableException("변경 락을 얻지 못했습니다"))
                .flatMap(lease -> Mono.defer(work)
                        .doFinally(signal -> lock.release(lease).subscribe()));
    }
```

클래스에 상수와 필드를 더한다:

```java
    /** 재시도 간격. 대기 한도를 이 값으로 나눈 횟수가 {@code acquireRetries} 다. */
    private static final Duration ACQUIRE_RETRY_DELAY = Duration.ofMillis(200);
```

`acquireRetries` 는 생성자 마지막 파라미터로 받는다(`int`). `app-scim` 이
`dynamoDb.getLockAcquireTimeout().toMillis() / 200` 으로 계산해 넘긴다 —
기본 3초면 15회다.

임포트: `java.util.function.Supplier`, `java.time.Duration`,
`reactor.util.retry.Retry`, `reactor.core.Exceptions`.

- [ ] **Step 5: 기준선을 Check 로 바꾼다**

`diffAndApply` 를 다음으로 교체한다:

```java
    /**
     * 기준선을 <b>OpenFGA 에 물어서</b> 만든다 (설계 §5).
     *
     * <p>전에는 {@code TupleMapper(변경 전 상태)} 를 기준선으로 썼다. 그것은 "있어야 했던 것"
     * 이라, 어긋난 튜플이 있어도 양쪽에서 똑같이 빠져 델타가 비었다 — 계산은 매번 정확하고
     * 틀린 곳을 볼 방법만 없었다.
     *
     * <p>후보는 {@code TupleMapper.candidateTuples} 로 뽑는다. `active` 필터를 적용하기 전의
     * 멤버십이어야 비활성 직원의 잘못 남은 튜플이 확인 대상에 들어온다.
     *
     * <p><b>Check 가 실패하면 폴백하지 않는다.</b> 상태 기준선으로 돌아가면 조용히 옛 동작이
     * 되고, 그게 하필 어긋남이 생기는 순간이다. 실패시켜 IdP 가 재시도하게 둔다.
     */
    private Mono<IncrementalSyncResult> diffAndApply(Mono<DirectorySnapshot> beforeMono,
                                                      Mono<DirectorySnapshot> afterMono,
                                                      Commit commit) {
        return Mono.zip(beforeMono, afterMono).flatMap(both -> {
            DirectorySnapshot beforeSnapshot = both.getT1();
            DirectorySnapshot afterSnapshot = both.getT2();

            Set<RelationTuple> candidates = new LinkedHashSet<>();
            candidates.addAll(TupleMapper.candidateTuples(beforeSnapshot));
            candidates.addAll(TupleMapper.candidateTuples(afterSnapshot));

            return checker.existing(candidates).flatMap(actual ->
                    withoutCycleCreatingEdges(actual, tuplesOf(afterSnapshot)).flatMap(after -> {
                        TupleDelta delta = TupleDiff.between(actual, after);

                        if (delta.isEmpty()) {
                            return commit.apply(TupleWriteResult.empty(), actual, after)
                                    .thenReturn(IncrementalSyncResult.noChange());
                        }
                        return writer.apply(delta)
                                .flatMap(result -> commit.apply(result, actual, after)
                                        .thenReturn(IncrementalSyncResult.of(result)));
                    }));
        });
    }
```

`import java.util.LinkedHashSet;` 가 없으면 더한다.

- [ ] **Step 6: 배선을 고친다**

`ScimUseCaseConfig.java` 에서 `mutationGate()` 빈을 지우고 `incrementalSyncUseCase` 를 바꾼다:

```java
    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer,
                                                          RelationTupleChecker checker,
                                                          MutationLock lock,
                                                          DynamoDbProperties dynamoDb) {
        int acquireRetries = (int) (dynamoDb.getLockAcquireTimeout().toMillis() / 200);
        return new IncrementalSyncUseCase(state, writer, checker, lock, acquireRetries);
    }
```

임포트를 정리한다.

> `ScimRebuildUseCase` 는 아직 `MutationGate` 를 받는다. Task 6 에서 바꾸므로,
> 이 태스크에서는 `mutationGate()` 빈을 **지우지 말고 남겨둔다** — 지우면 컨텍스트가 뜨지 않는다.
> `MutationGate` 삭제는 Task 6 의 몫이다.

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncDriftTest*'`
Expected: PASS 6건

- [ ] **Step 8: 기존 테스트가 깨지지 않는지 본다**

Run: `./gradlew :core:test :app-scim:test`
Expected: 전부 PASS. 깨지면 생성자 변경 때문이므로 호출부를 고친다.

- [ ] **Step 9: 테스트가 실제로 무는지 확인한다**

`diffAndApply` 의 `checker.existing(candidates)` 를 `Mono.just(tuplesOf(beforeSnapshot))` 로
바꿔(옛 동작으로 되돌려) 다시 돌린다.

Run: `./gradlew :core:test --tests '*IncrementalSyncDriftTest*'`
Expected: `잘못된_튜플을_걷어낸다` FAIL

확인했으면 되돌린다.

- [ ] **Step 10: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java
git commit -m "feat: 쓰기 경로에 분산 락과 Check 기준선"
```

---

## Task 6: 재적재를 같은 락으로 옮기고 MutationGate 삭제

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/AdminSyncController.java`
- Delete: `core/src/main/java/dev/starryeye/organization/core/usecase/MutationGate.java`
- Delete: `core/src/test/java/dev/starryeye/organization/core/usecase/MutationGateTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildLockTest.java`

**Interfaces:**
- Consumes: `MutationLock` (Task 1), `FakeMutationLock` (Task 1)
- Produces: `ScimRebuildUseCase(DirectoryStateRepository, RelationTupleWriter, TupleSnapshotRepository, SyncRunRepository, MutationLock, Clock)`

- [ ] **Step 1: 실패 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildLockTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재적재가 SCIM 쓰기와 <b>같은</b> 분산 락을 잡는다 (설계 §4.5).
 *
 * <p>전에는 인메모리 {@code MutationGate} 였다. 인스턴스가 둘이면 재적재가 도는 사실 자체를
 * 다른 인스턴스가 몰라 쓰기가 그대로 통과했다 — 막고 있다고 믿지만 안 막혔다.
 */
class ScimRebuildLockTest {

    private FakeMutationLock lock;
    private ScimRebuildUseCase useCase;

    @BeforeEach
    void 준비한다() {
        lock = new FakeMutationLock();
        useCase = new ScimRebuildUseCase(
                new FakeStateRepository(),
                new FakeTupleWriter(),
                new FakeSnapshotRepository(),
                new FakeSyncRunRepository(),
                lock,
                Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("재적재는 락을 잡고 끝나면 반납한다")
    void 락을_잡고_반납한다() {
        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then
        assertThat(lock.acquired).hasValue(1);
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("다른 인스턴스가 쥐고 있으면 재적재가 시작되지 않는다")
    void 락이_없으면_시작하지_않는다() {
        // given — 다른 인스턴스의 쓰기나 재적재가 쥐고 있는 상황
        lock.failAcquire = true;

        // when, then
        assertThatThrownBy(() -> useCase.execute(ScimRebuildMode.TUPLES).block())
                .isInstanceOf(LockUnavailableException.class);
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*ScimRebuildLockTest*'`
Expected: FAIL — 생성자 시그니처 불일치

- [ ] **Step 3: 재적재를 바꾼다**

`ScimRebuildUseCase.java` 에서 `private final MutationGate gate;` 를 `private final MutationLock lock;` 로 바꾸고, `execute` 를 다음으로 교체한다:

```java
    public Mono<SyncRun> execute(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);

        return lock.acquire(MutationLock.LockPurpose.REBUILD)
                .flatMap(lease -> runs.start(SyncSource.SCIM, triggerFor(mode))
                        .flatMap(run -> rebuild(mode)
                                .onErrorResume(error -> {
                                    log.error("SCIM 재적재 실패: mode={}", mode, error);
                                    return Mono.just(SyncOutcome.failed(error.getMessage()));
                                })
                                .flatMap(outcome -> runs.finish(run, outcome)))
                        .doFinally(signal -> lock.release(lease).subscribe()));
    }
```

임포트를 정리한다 (`MutationGate`·`MutationsSuspendedException` 제거, `MutationLock` 추가).

> **리스 갱신은 이 태스크에서 하지 않는다.** 재적재가 TTL(30초)보다 오래 걸리면 리스를
> 잃는다. 갱신은 Task 7 에서 붙인다 — 여기서 함께 하면 락 교체와 갱신 중 어느 쪽이
> 문제인지 구분되지 않는다.

- [ ] **Step 4: 배선을 고치고 MutationGate 를 지운다**

`ScimUseCaseConfig.java`:

```java
    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncRunRepository runs,
                                                 MutationLock lock,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, snapshots, runs, lock, clock);
    }
```

`mutationGate()` 빈과 `MutationGate` 임포트를 지운다.

```bash
git rm core/src/main/java/dev/starryeye/organization/core/usecase/MutationGate.java
git rm core/src/test/java/dev/starryeye/organization/core/usecase/MutationGateTest.java
```

> `MutationGateTest.java` 가 실제로 있는지 먼저 확인할 것. 없으면 그 줄은 건너뛴다.

- [ ] **Step 5: 예외 매핑을 고친다**

`AdminSyncController.java` 와 SCIM 라우터에서 `MutationsSuspendedException` 을 잡던 자리에
`LockUnavailableException` 을 더한다. 상태 코드는 기존과 같게 유지한다 — 관리자 API 는 409,
SCIM 쓰기는 503.

정확한 위치는 다음으로 찾는다:
`grep -rn "MutationsSuspendedException" app-scim/src/main`

`MutationsSuspendedException` 클래스 자체는 **지우지 않는다** — 남아 있는 다른 용도가 있는지
`grep -rn "MutationsSuspendedException" --include='*.java' .` 로 확인하고, 아무도 안 쓰면
그때 지운다.

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*ScimRebuildLockTest*'`
Expected: PASS 2건

- [ ] **Step 7: 전체 빌드로 회귀를 본다**

Run: `./gradlew build`
Expected: 전부 PASS. 특히 `ScimRebuildEndToEndTest` 의 "재적재 중 SCIM 쓰기가 503",
"wipe 뒤에도 SCIM 쓰기가 열려 있다" 가 새 락 위에서 그대로 통과해야 한다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "refactor: 재적재를 분산 락으로 옮기고 MutationGate 삭제"
```

---

## Task 7: 재적재 리스 갱신

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java`

**Interfaces:**
- Consumes: `MutationLock.renew` (Task 1)
- Produces: `ScimRebuildUseCase(..., MutationLock lock, Duration renewInterval, Clock clock)`

- [ ] **Step 1: 실패 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildRenewTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 재적재는 몇 분을 쥐지만 TTL 은 30초다. 갱신하지 않으면 <b>도중에 리스를 잃고</b>
 * 다른 인스턴스의 쓰기가 반쯤 재적재된 OpenFGA 위로 들어온다 (설계 §4.4).
 */
class ScimRebuildRenewTest {

    @Test
    @DisplayName("재적재가 오래 걸리면 리스를 주기적으로 갱신한다")
    void 오래_걸리면_갱신한다() {
        // given — 느린 쓰기로 긴 재적재를 흉내낸다
        var lock = new FakeMutationLock();
        var writer = new FakeTupleWriter();
        writer.delay = Duration.ofMillis(600);
        var useCase = new ScimRebuildUseCase(
                new FakeStateRepository(), writer,
                new FakeSnapshotRepository(), new FakeSyncRunRepository(),
                lock, Duration.ofMillis(100),
                Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();

        // then — 100ms 주기로 600ms 를 덮으려면 여러 번 갱신돼야 한다
        assertThat(lock.renewed.get())
                .as("갱신이 없으면 TTL 안에 끝나지 않는 재적재가 리스를 잃는다")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("재적재가 끝나면 갱신도 멈춘다")
    void 끝나면_갱신도_멈춘다() {
        // given
        var lock = new FakeMutationLock();
        var useCase = new ScimRebuildUseCase(
                new FakeStateRepository(), new FakeTupleWriter(),
                new FakeSnapshotRepository(), new FakeSyncRunRepository(),
                lock, Duration.ofMillis(50),
                Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));

        // when
        useCase.execute(ScimRebuildMode.TUPLES).block();
        int 끝난직후 = lock.renewed.get();

        // then — 갱신이 계속 돌면 반납된 락을 갱신하려 들어 로그가 오염된다
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(lock.renewed.get()).isEqualTo(끝난직후));
    }
}
```

> `FakeTupleWriter` 에 `delay` 필드가 없으면 이 태스크에서 더한다:
> ```java
> /** 쓰기 응답을 늦춘다. 긴 작업을 흉내내는 데 쓴다. */
> public Duration delay = Duration.ZERO;
> ```
> 그리고 `apply` 가 결과를 돌려주기 전에 `delayElement(delay)` 를 태운다 (`ZERO` 면 생략).

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*ScimRebuildRenewTest*'`
Expected: FAIL — 생성자에 `renewInterval` 이 없음

- [ ] **Step 3: 갱신을 붙인다**

`ScimRebuildUseCase.java` 에 `private final Duration renewInterval;` 을 더하고 (필드 순서는
`lock` 다음, `clock` 앞), `execute` 를 다음으로 바꾼다:

```java
    public Mono<SyncRun> execute(ScimRebuildMode mode) {
        log.warn("SCIM 재적재 요청: mode={}", mode);

        return lock.acquire(MutationLock.LockPurpose.REBUILD)
                .flatMap(lease -> {
                    Disposable heartbeat = 리스를_갱신한다(lease);
                    return runs.start(SyncSource.SCIM, triggerFor(mode))
                            .flatMap(run -> rebuild(mode)
                                    .onErrorResume(error -> {
                                        log.error("SCIM 재적재 실패: mode={}", mode, error);
                                        return Mono.just(SyncOutcome.failed(error.getMessage()));
                                    })
                                    .flatMap(outcome -> runs.finish(run, outcome)))
                            .doFinally(signal -> {
                                heartbeat.dispose();
                                lock.release(lease).subscribe();
                            });
                });
    }

    /**
     * 재적재가 도는 동안 리스를 계속 미룬다 (설계 §4.4).
     *
     * <p>TTL 은 30초인데 재적재는 몇 분 걸린다. 갱신하지 않으면 도중에 리스를 잃고, 그 순간
     * 다른 인스턴스의 쓰기가 <b>반쯤 재적재된 OpenFGA</b> 위로 들어온다.
     *
     * <p>갱신이 실패하면 이미 리스를 잃은 것이다. 재적재를 되돌릴 방법이 없으므로 경고만
     * 남기고 하던 일을 마친다 — 그 상태는 재적재가 실패했을 때와 같아
     * {@code mode=tuples} 를 한 번 더 실행하면 복구된다.
     */
    private Disposable 리스를_갱신한다(LockLease lease) {
        return Flux.interval(renewInterval, renewInterval)
                .concatMap(tick -> lock.renew(lease)
                        .doOnError(error -> log.error(
                                "재적재 도중 변경 락 리스를 잃었다. 다른 인스턴스의 쓰기가 들어올 수 있다", error))
                        .onErrorResume(error -> Mono.empty()))
                .subscribe();
    }
```

필요한 임포트: `reactor.core.Disposable`, `reactor.core.publisher.Flux`,
`dev.starryeye.organization.core.port.LockLease`, `java.time.Duration`.

- [ ] **Step 4: 배선을 고친다**

`ScimUseCaseConfig.java` 의 `scimRebuildUseCase` 에 `DynamoDbProperties` 를 받아
`properties.getLockRenewInterval()` 을 넘긴다.

> `core` 는 `storage-dynamodb` 를 모른다(의존 방향이 반대다). `DynamoDbProperties` 를
> `core` 로 넘기지 말고, **`app-scim` 의 설정 클래스에서 `Duration` 값만 꺼내 넘긴다.**

```java
    @Bean
    public ScimRebuildUseCase scimRebuildUseCase(DirectoryStateRepository state,
                                                 RelationTupleWriter writer,
                                                 TupleSnapshotRepository snapshots,
                                                 SyncRunRepository runs,
                                                 MutationLock lock,
                                                 DynamoDbProperties dynamoDb,
                                                 Clock clock) {
        return new ScimRebuildUseCase(state, writer, snapshots, runs, lock,
                dynamoDb.getLockRenewInterval(), clock);
    }
```

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*ScimRebuildRenewTest*'`
Expected: PASS 2건

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat: 재적재가 도는 동안 락 리스를 갱신한다"
```

---

## Task 8: 지표와 E2E

**Files:**
- Create: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimDriftHealingEndToEndTest.java`

**Interfaces:**
- Consumes: 전부 (Task 1~7)
- Produces: 없음 (마지막 태스크)

> **먼저 읽을 것.** 이 태스크는 `IncrementalSyncUseCase` 생성자에 파라미터를 하나 더한다.
> Task 5 에서 만든 `IncrementalSyncDriftTest` 의 생성자 호출이 깨지므로 **함께 고친다** —
> `new IncrementalSyncUseCase(state, writer, checker, lock, 0, DriftObserver.NOOP)`.
> `ScimUseCaseConfig` 의 배선도 같이 고친다.

- [ ] **Step 1: 드리프트 관측 훅을 유스케이스에 더한다**

`IncrementalSyncUseCase` 에 함수형 콜백을 하나 받는다 — `core` 가 Micrometer 를 알지
않도록 하기 위해서다(`core` 의 의존성은 reactor 와 slf4j 뿐이다).

```java
    /**
     * 어긋남을 발견했을 때 부른다. 기본은 아무것도 하지 않는다.
     *
     * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 — 이 모듈의 의존성은
     * reactor 와 slf4j 뿐이고, 그 경계를 지표 때문에 허물지 않는다.
     */
    public interface DriftObserver {
        void observed(int extra, int missing);

        DriftObserver NOOP = (extra, missing) -> {
        };
    }
```

`diffAndApply` 안에서 상태 기준선과 실제를 비교해 부른다:

```java
            return checker.existing(candidates).flatMap(actual -> {
                Set<RelationTuple> 상태기준선 = tuplesOf(beforeSnapshot);
                int extra = (int) actual.stream().filter(t -> !상태기준선.contains(t)).count();
                int missing = (int) 상태기준선.stream().filter(t -> !actual.contains(t)).count();
                if (extra > 0 || missing > 0) {
                    log.warn("OpenFGA 어긋남 발견: 있어선 안 될 튜플 {}건, 빠진 튜플 {}건", extra, missing);
                    driftObserver.observed(extra, missing);
                }
                return withoutCycleCreatingEdges(actual, tuplesOf(afterSnapshot)).flatMap(after -> {
                    // ... Task 5 와 동일
                });
            });
```

생성자에 `DriftObserver driftObserver` 를 더한다 (마지막 파라미터).

- [ ] **Step 2: 지표 빈을 만든다**

`app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java`:

```java
package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase.DriftObserver;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * 어긋남을 지표로 남긴다 (설계 §7).
 *
 * <p>Check 기준선을 넣으면 "있어야 했던 것"과 "진짜 있는 것"을 둘 다 갖게 된다. 둘이 다르면
 * 그것이 곧 어긋남이다 — 별도 스캔 없이 쓰기 경로가 지나가면서 알려준다.
 *
 * <p>이 값이 지속적으로 오르면 재적재 시점을 판단할 근거가 된다.
 */
@RequiredArgsConstructor
public class ScimSyncMetrics implements DriftObserver {

    private final MeterRegistry registry;

    @Override
    public void observed(int extra, int missing) {
        if (extra > 0) {
            registry.counter("scim.drift.detected", "kind", "extra").increment(extra);
        }
        if (missing > 0) {
            registry.counter("scim.drift.detected", "kind", "missing").increment(missing);
        }
    }
}
```

`ScimUseCaseConfig` 에 빈으로 등록하고 `incrementalSyncUseCase` 에 넘긴다.

- [ ] **Step 3: E2E 실패 테스트를 쓴다**

`app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimDriftHealingEndToEndTest.java`:

```java
package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어긋난 튜플이 다음 터치에 걷어내지는지 실제 컨테이너 위에서 확인한다 (설계 §8.3).
 *
 * <p>경합을 재현하는 대신 <b>경합이 남겼을 결과를 직접 심는다</b>. 타이밍에 기대지 않아
 * 흔들리지 않으면서, 설계가 막으려는 위험(퇴사자 권한 생존)을 그대로 못박는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimDriftHealingEndToEndTest {

    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        registry.add("openfga.api-url",
                () -> "http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080));
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000));
    }

    @Autowired WebTestClient client;
    @Autowired StoreBootstrapper bootstrapper;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    private void 잔여튜플을_심는다(String user, String relation, String object) {
        try {
            bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                    new ClientTupleKey().user(user).relation(relation)._object(object)))).get();
        } catch (Exception e) {
            throw new IllegalStateException("튜플 심기 실패", e);
        }
    }

    @Test
    @DisplayName("경합이 남긴 퇴사자 튜플을 다음 SCIM 쓰기가 걷어낸다")
    void 어긋난_튜플이_치유된다() {
        // given — kim 을 만들고 DEV001 에 넣은 뒤 비활성으로 바꾼다
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.valueOf("application/scim+json"))
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":true}
                        """)
                .exchange().expectStatus().is2xxSuccessful();

        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.valueOf("application/scim+json"))
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().is2xxSuccessful();

        client.put().uri("/scim/v2/Users/kim")
                .contentType(MediaType.valueOf("application/scim+json"))
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":false}
                        """)
                .exchange().expectStatus().is2xxSuccessful();

        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();

        // given — 경합이 남겼을 튜플을 직접 심는다.
        // DynamoDB 에는 kim 이 DEV001 멤버로 남아 있고(비활성), OpenFGA 에만 튜플이 산다.
        잔여튜플을_심는다("user:kim", "direct_member", "group:DEV001");
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();

        // when — DEV001 을 아무렇게나 한 번 건드린다
        client.put().uri("/scim/v2/Groups/DEV001")
                .contentType(MediaType.valueOf("application/scim+json"))
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().is2xxSuccessful();

        // then — 상태 기준선이었다면 델타가 비어 그대로 남는다
        assertThat(check("user:kim", "member", "group:DEV001"))
                .as("비활성 직원의 잘못 남은 권한은 다음 터치에 사라져야 한다")
                .isFalse();
    }
}
```

- [ ] **Step 4: 실패/통과를 확인한다**

Run: `./gradlew :app-scim:test --tests '*ScimDriftHealingEndToEndTest*'`
Expected: PASS 1건

> SCIM 요청 본문의 필드명(`externalId` 로 조직코드를 주는지 등)이 실제 매핑과 맞는지
> `ScimEndToEndTest` 의 기존 요청을 보고 맞출 것. 다르면 그쪽을 따른다.

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build --rerun-tasks`
Expected: 전부 PASS

- [ ] **Step 6: 문서를 갱신한다**

`README.md` 에 다음을 더한다:

- app-scim 을 여러 대로 띄울 수 있고, SCIM 쓰기와 재적재가 DynamoDB 전역 락으로
  직렬화된다는 것
- 락 설정 세 개(`dynamodb.lock-ttl`, `lock-acquire-timeout`, `lock-renew-interval`)
- `scim.drift.detected` 가 무엇을 뜻하고 오르면 무엇을 해야 하는지(재적재)

`docs/superpowers/plans/2026-08-15-follow-ups.md` §6 을 해결 표시하고, 남은 것
(주기적 대조, 자동 수렴, 스냅샷 의미 통일, 처리량 스케일 아웃)을 적는다.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat: 어긋남 지표와 치유 E2E, 문서 갱신"
```

---

## Self-Review

**1. 스펙 커버리지**

| 스펙 절 | 태스크 |
|---|---|
| §3 전체 흐름 | Task 5 (쓰기 경로), Task 6 (재적재) |
| §4.1 전역 락 근거 | Task 1 (포트 javadoc) |
| §4.2 아이템 모양 | Task 2 |
| §4.3 세 연산 + 토큰 조건 | Task 2 (Step 3 테스트 `남의_락은_풀지_못한다`) |
| §4.4 TTL·대기·갱신 | Task 2 (설정), Task 7 (갱신) |
| §4.5 MutationGate 흡수 | Task 6 |
| §4.6 크래시 | Task 2 (`만료되면_다른_쪽이_가져간다`), Task 7 (갱신 실패 로그) |
| §4.7 한계 | Task 2 (구현 javadoc에 명시) |
| §5.1 후보 집합 | Task 4 |
| §5.2 연산별 후보 | Task 5 (`candidates` 계산) |
| §5.3 포트 | Task 3 |
| §5.4 한계 | 설계 문서에만 (코드 변경 없음) |
| §6 에러 처리 | Task 5 (`Check_실패는_폴백하지_않는다`), Task 2 (반납 실패 경고) |
| §7 관측 | Task 8 |
| §8 테스트 전략 | Task 2·5·8 + 각 태스크의 변이 검증 단계 |

**검토에서 찾아 본문에 반영한 것 — 획득 대기.** 설계 §4.4 는 "3초 안에 못 잡으면 503" 인데
Task 2 의 `acquire` 는 즉시 실패한다. 대기를 `withLock` 의 `retryWhen` 으로 붙이도록
**Task 5 Step 4 본문을 고쳤다**(검토 노트로 두면 실행자가 놓친다).

**2. 플레이스홀더 스캔** — "적절히", "등등", "TBD" 없음. 확인이 필요한 곳(SDK 시그니처,
`MemberRef` enum 상수명, `FakeTupleWriter` 필드)은 **확인 명령을 함께 적었다.**

**3. 타입 일관성**

- `LockLease(String token, Instant expiresAt)` — Task 1 정의, Task 2·5·6·7 사용. 일치
- `MutationLock.LockPurpose.{WRITE,REBUILD}` — Task 1 정의, Task 5·6 사용. 일치
- `RelationTupleChecker.existing(Set<RelationTuple>) : Mono<Set<RelationTuple>>` — Task 3 정의,
  Task 5 사용. 일치
- `TupleMapper.candidateTuples(DirectorySnapshot) : Set<RelationTuple>` — Task 4 정의,
  Task 5 사용. 일치
- `IncrementalSyncUseCase` 생성자는 Task 5 에서 5개(`acquireRetries` 포함), Task 8 에서
  `DriftObserver` 가 붙어 6개가 된다. Task 8 이 Task 5 의 테스트를 고쳐야 하므로
  **Task 8 맨 앞에 그 지시를 넣었다**
