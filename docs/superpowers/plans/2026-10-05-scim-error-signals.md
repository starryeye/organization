# 점검 ⑤-1 SCIM 재시도 신호 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 오류 응답이 IdP 에게 재시도 여부를 바르게 알린다 — 일시 장애는 503 + 원인별 `Retry-After`, 큰 본문 413, 라우트 밖 요청 405·501·404, 오류 문구에 내부 정보 없음, path 없는 remove 는 `noTarget`.

**Architecture:** core 에 일시 장애 표지(`TemporaryFailureException`)와 인식기 포트(`TemporaryFailureRecognizer`)를 둔다. 우리가 만드는 일시 장애(락·쓰기 차단기·재시도 상한)는 표지를 달고,
각 SDK 의 예외는 그 SDK 를 가진 어댑터(storage-dynamodb·authz-openfga)가 인식기 빈으로 알아본다. connector-scim 의 분류기가 원인 사슬을 따라가며 둘을 모아 `ScimRouter` 의 번역 한 곳에서 쓴다.

**Tech Stack:** Java 17, Spring Boot 3 WebFlux(RouterFunction), Reactor, AWS SDK v2 2.28.29, OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, WebTestClient, Testcontainers(DynamoDB Local 2.5.3, OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-05-scim-error-signals-design.md`

## Global Constraints

- 모든 테스트는 `// given` / `// when` / `// then` 표식을 단다(`// when, then` 합본 허용). 아래 코드 조각에 표식이 없으면 더한다.
- 이름·주석·메시지는 주변 코드처럼 한국어 평서문. `@DisplayName` 은 한국어 문장. 클래스 이름은 영어(주변과 같다).
- 커밋 메시지: 한국어 제목, 빈 줄, 그 다음 자기 하네스가 주는 `Co-Authored-By:` 줄(heredoc). 커밋마다 `git push`.
- Gradle 은 한 번에 하나, 포그라운드. 과제가 정한 모듈 테스트만 돌린다. `scaleTest` 와 전체 `test` 는 컨트롤러가 돌린다.
- 기다릴 시간은 상수다 — 쓰기 경합 2초, 긴 작업(`REBUILD`·`SYNC`) 60초, 그 밖 10초. `Retry-After` 는 정수 초(RFC 9110 §10.2.3).
- 429 는 쓰지 않는다. 503 응답 본문도 SCIM Error(`schemas`, `status: "503"`, `detail`)다.
- 500 은 진짜 버그에만 — 모르는 예외는 500 + ERROR 로그(지금처럼).
- 각 SDK 의 재시도 분류를 따른다 — AWS: `SdkClientException`, 스로틀링, 5xx, `retryable()`. OpenFGA: 거절(`FgaApiValidationError`)이 아닌 `ApiException`.

## Review Focus

1. 일시 장애가 `CompletionException`·Reactor 의 감싼 예외 안 깊이 있어도 503 이다 — Task 4 분류기 테스트.
2. 락 획득 재시도(3초)를 다 쓴 뒤에도 마지막 실패의 기다릴 시간(재적재면 60초)을 잃지 않는다 — Task 1 테스트.
3. DynamoDB 조건 실패·검증 오류(4xx, 스로틀링 아님)가 SCIM 쓰기에서 나면 503 이 아니라 500 이다 — Task 2 인식기 테스트.
4. OpenFGA 400(검증 거절)이 Check 에서 나면 503 이 아니라 500 이다 — Task 3 인식기 테스트.
5. 있는 경로의 틀린 메서드(예: `DELETE /scim/v2/Users`)는 405 + `Allow: GET, POST`, 깊은 모르는 경로(`/scim/v2/Users/x/y`)는 404 — Task 5 테스트.

---

### Task 1: core 일시 장애 표지와 락의 기다릴 시간

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/TemporaryFailureException.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/TemporaryFailureRecognizer.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LockUnavailableException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/TupleWriteAbortedException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java:~663-676`(락 획득 재시도 소진)
- Test: `core/src/test/java/dev/starryeye/organization/core/port/TemporaryFailureExceptionTest.java`(새), 락 획득 재시도 테스트(기존 `IncrementalSyncUseCase` 락 테스트 파일에 더하거나 새 파일)

**Interfaces:**
- Produces:
  - `public class TemporaryFailureException extends RuntimeException` — `public static final Duration 기본_대기 = Duration.ofSeconds(10)`,
    생성자 `(String message, Duration retryAfter)`, `(String message, Duration retryAfter, Throwable cause)`, `public Duration retryAfter()`.
  - `@FunctionalInterface public interface TemporaryFailureRecognizer { Optional<Duration> 재시도_대기(Throwable error); }` — 예외 **하나**를 본다(사슬은 호출자가 따라간다).
  - `LockUnavailableException extends TemporaryFailureException` — 기존 생성자 `(String)`·`(String, Throwable)` 은 `기본_대기`, 새 `(String, Duration)`,
    `public static final Duration 쓰기_경합_대기 = Duration.ofSeconds(2)`, `public static final Duration 긴_작업_대기 = Duration.ofSeconds(60)`,
    `public static LockUnavailableException 잡혀_있다(MutationLock.LockPurpose 쥔_용도)` — `WRITE`·null → 2초, `REBUILD`·`SYNC` → 60초.
  - `TupleWriteAbortedException extends TemporaryFailureException`(기다릴 시간 `기본_대기`, `partial()` 계약 그대로).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class TemporaryFailureExceptionTest {

    @Test
    @DisplayName("락을 쥔 쪽이 SCIM 쓰기면 2초, 재적재·전체 동기화면 60초, 모르면 쓰기 경합으로 보고 2초다(설계 2026-10-05 §3.1)")
    void 락을_쥔_쪽의_용도로_기다릴_시간을_정한다() {
        // when, then
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE).retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.REBUILD).retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.SYNC).retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(LockUnavailableException.잡혀_있다(null).retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("락 저장소 오류·리스 상실(용도를 모르는 락 실패)과 쓰기 차단기는 일시 장애이고 10초다")
    void 그_밖의_락_실패와_차단기는_10초다() {
        // given
        var 리스상실 = new LockUnavailableException("변경 락 리스를 잃었습니다");
        var 저장소오류 = new LockUnavailableException("변경 락을 얻는 중 오류가 발생했습니다", new RuntimeException("dynamo"));
        var 차단기 = new TupleWriteAbortedException("멈췄다", TupleWriteResult.empty());

        // when, then
        assertThat(List.<TemporaryFailureException>of(리스상실, 저장소오류, 차단기))
                .allSatisfy(e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(10)));
    }
}
```

락 획득 재시도 소진 테스트 — 늘 `LockUnavailableException.잡혀_있다(REBUILD)` 로 실패하는 가짜 락(기존 core 락 테스트의 가짜 모양을 따른다)으로 `IncrementalSyncUseCase` 의 쓰기 하나(예: `removeUser`)를 부르면
`LockUnavailableException` 으로 끝나고 그 `retryAfter()` 가 60초다(재시도를 다 쓴 뒤 새로 만드는 예외가 마지막 실패의 시간을 물려받는다). 지금은 10초가 아니라 기본 생성자라 실패한다.
(획득 재시도 간격을 짧게 하는 생성자·설정이 기존 테스트에 있으면 그것을 쓴다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*TemporaryFailureExceptionTest'` 그리고 재시도 소진 테스트
Expected: 컴파일 실패, 그다음 60초 단정 실패.

- [ ] **Step 3: 구현**

```java
package dev.starryeye.organization.core.port;

/**
 * 같은 요청을 잠시 뒤 다시 보내면 나을 수 있는 실패(설계 2026-10-05 §3.1, 점검 M4). SCIM 은 503 + {@code Retry-After} 로 옮긴다 — IdP 가 재시도하게.
 * 우리가 만드는 일시 장애(락, 쓰기 차단기, 재시도 상한)가 이것을 단다. 라이브러리 예외는 어댑터의 {@link TemporaryFailureRecognizer} 가 알아본다.
 */
public class TemporaryFailureException extends RuntimeException {

    /** 원인별 시간이 따로 없을 때 — 하위 시스템 장애·부분 실패·리스 상실 */
    public static final Duration 기본_대기 = Duration.ofSeconds(10);

    private final Duration retryAfter;

    public TemporaryFailureException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public TemporaryFailureException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /** 다시 보내기 전에 기다릴 시간 */
    public Duration retryAfter() {
        return retryAfter;
    }
}
```

```java
package dev.starryeye.organization.core.port;

/**
 * 라이브러리 예외 하나가 일시 장애인지 알아본다 — 그 라이브러리를 가진 어댑터가 구현해 빈으로 낸다(설계 2026-10-05 §3.3). 원인 사슬은 호출자가 따라간다.
 */
@FunctionalInterface
public interface TemporaryFailureRecognizer {

    /** 일시 장애면 기다릴 시간, 아니면 빈 값 */
    Optional<Duration> 재시도_대기(Throwable error);
}
```

`LockUnavailableException`:

```java
/**
 * 락을 잡지 못했거나 쥐고 있던 리스를 잃었다 — 일시 장애다(설계 2026-10-05 §3.1). 다른 쪽이 락을 쥐어 조건이 깨졌으면 {@link #잡혀_있다} 로 쥔 쪽의 용도에 맞는 시간을 싣고,
 * 그 밖(락 저장소 오류·리스 상실·갱신 실패)은 {@link TemporaryFailureException#기본_대기} 다.
 */
public class LockUnavailableException extends TemporaryFailureException {

    /** 다른 SCIM 쓰기가 쥐고 있다 — 밀리초 단위로 쥐는 락이다 */
    public static final Duration 쓰기_경합_대기 = Duration.ofSeconds(2);
    /** 재적재·전체 동기화가 쥐고 있다 — 수 분 걸린다 */
    public static final Duration 긴_작업_대기 = Duration.ofSeconds(60);

    public LockUnavailableException(String message) {
        super(message, 기본_대기);
    }

    /** 락 자체의 문제가 아니라 저장소 장애로 실패했을 때. 원인을 물고 간다 — 끊으면 DynamoDB 가 무엇을 던졌는지가 사라진다. */
    public LockUnavailableException(String message, Throwable cause) {
        super(message, 기본_대기, cause);
    }

    public LockUnavailableException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }

    /** 다른 쪽이 락을 쥐고 있어 획득 조건이 깨졌다. 쥔 쪽의 용도를 모르면(null) 흔한 경우인 쓰기 경합으로 본다. */
    public static LockUnavailableException 잡혀_있다(MutationLock.LockPurpose 쥔_용도) {
        Duration 대기 = 쥔_용도 == MutationLock.LockPurpose.REBUILD || 쥔_용도 == MutationLock.LockPurpose.SYNC
                ? 긴_작업_대기 : 쓰기_경합_대기;
        return new LockUnavailableException("다른 작업이 변경 락을 쥐고 있습니다(" + (쥔_용도 == null ? "용도 모름" : 쥔_용도) + ")", 대기);
    }
}
```

`TupleWriteAbortedException` 은 `extends TemporaryFailureException` 으로 바꾸고 생성자에서 `super(message, 기본_대기)`. 자바독에 "OpenFGA 가 연달아 실패한 것이라 일시 장애다(SCIM 은 503)" 한 줄.

`IncrementalSyncUseCase` 의 락 획득(`잡고_돌린다`):

```java
                    .onErrorMap(Exceptions::isRetryExhausted,
                            error -> new LockUnavailableException("변경 락을 얻지 못했습니다", 마지막_대기(error)))
```

```java
    /** 재시도를 다 쓴 예외의 원인은 마지막 실패다 — 그 기다릴 시간(쥔 쪽의 용도)을 물려받는다. */
    private static Duration 마지막_대기(Throwable 소진) {
        return 소진.getCause() instanceof TemporaryFailureException 마지막
                ? 마지막.retryAfter()
                : TemporaryFailureException.기본_대기;
    }
```

락 주석의 "IdP 는 503 을 재시도 신호로…" 는 그대로 두고, LockUnavailableException 자바독의 "(설계 §6)" 을 "(설계 2026-10-05 §3.1)" 로 고친다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS(기존 락·차단기 테스트 포함 — `TupleWriteAbortedException` 을 잡는 LDAP 경로는 상속만 바뀌어 그대로다).

- [ ] **Step 5: 커밋**

```bash
git add core/src/
git commit -F - <<'EOF'
feat: core 일시 장애 표지(TemporaryFailureException)와 인식기 포트 — 락은 쥔 쪽 용도로 2초·60초, 그 밖 10초, 쓰기 차단기도 일시 장애(점검 M4·S2)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 2: storage — 락을 쥔 쪽의 용도, 재시도 상한, DynamoDB 인식기

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java:~62-89`(acquire)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/BatchRequests.java:~57,~83`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTemporaryFailures.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java`(인식기 빈)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`(더함), `BatchRequestsTest.java`(단정 종류), `DynamoDbTemporaryFailuresTest.java`(새)

**Interfaces:**
- Consumes: Task 1 의 `LockUnavailableException.잡혀_있다(LockPurpose)`, `TemporaryFailureException`, `TemporaryFailureRecognizer`.
- Produces: `public final class DynamoDbTemporaryFailures implements TemporaryFailureRecognizer`, 빈 이름 `dynamoDbTemporaryFailures`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbMutationLockTest`(실제 DynamoDB Local) 에:

```java
    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 획득 실패의 기다릴 시간이 60초다 — DynamoDB 가 돌려준 기존 락 항목의 용도로 정한다(설계 2026-10-05 §3.2)")
    void 재적재가_쥔_락은_60초다() {
        // given
        lock.acquire(MutationLock.LockPurpose.REBUILD).block();

        // when, then
        StepVerifier.create(lock.acquire(MutationLock.LockPurpose.WRITE))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .extracting(e -> ((LockUnavailableException) e).retryAfter())
                        .isEqualTo(Duration.ofSeconds(60)))
                .verify();
    }

    @Test
    @DisplayName("다른 SCIM 쓰기가 락을 쥐고 있으면 2초다")
    void 쓰기가_쥔_락은_2초다() {
        // given
        lock.acquire(MutationLock.LockPurpose.WRITE).block();

        // when, then
        StepVerifier.create(lock.acquire(MutationLock.LockPurpose.WRITE))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(LockUnavailableException.class)
                        .extracting(e -> ((LockUnavailableException) e).retryAfter())
                        .isEqualTo(Duration.ofSeconds(2)))
                .verify();
    }
```

(`lock` 생성·acquire 의 토큰 인자는 그 테스트 파일의 기존 모양을 따른다.) **DynamoDB Local 2.5.3 이 조건 실패 때 기존 항목을 돌려주지 않으면** 60초 테스트가 2초로 실패한다 —
그때는 그 사실을 보고서에 적고(스펙 §10 한계), 60초 경로는 `ConditionalCheckFailedException.builder().item(Map.of("purpose", s("REBUILD")))` 로 만든 예외를 매핑 함수에
직접 넣는 단위 테스트로 고정한다(매핑을 패키지 전용 static `쥔_용도(ConditionalCheckFailedException)` 로 뽑는다).

`DynamoDbTemporaryFailuresTest`:

```java
class DynamoDbTemporaryFailuresTest {

    private final DynamoDbTemporaryFailures 인식기 = new DynamoDbTemporaryFailures();

    @Test
    @DisplayName("네트워크·시간 초과(SdkClientException), 스로틀링, 5xx 는 일시 장애다 — AWS SDK 의 재시도 분류를 따른다")
    void 일시_장애를_알아본다() {
        // given
        var 네트워크 = SdkClientException.create("connection reset");
        var 스로틀링 = ProvisionedThroughputExceededException.builder().message("slow down").statusCode(400).build();
        var 서버오류 = DynamoDbException.builder().message("internal").statusCode(500).build();

        // when, then
        assertThat(List.of(네트워크, 스로틀링, 서버오류))
                .allSatisfy(e -> assertThat(인식기.재시도_대기(e)).contains(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("조건 실패·검증 오류(4xx, 스로틀링 아님)는 일시 장애가 아니다 — 다시 보내도 같다")
    void 결정적인_실패는_아니다() {
        // given
        var 조건실패 = ConditionalCheckFailedException.builder().message("cond").statusCode(400).build();
        var 검증 = DynamoDbException.builder().message("ValidationException").statusCode(400).build();

        // when, then
        assertThat(인식기.재시도_대기(조건실패)).isEmpty();
        assertThat(인식기.재시도_대기(검증)).isEmpty();
        assertThat(인식기.재시도_대기(new IllegalStateException("x"))).isEmpty();
    }
}
```

(스로틀링 판정은 SDK 가 오류 코드로 한다 — `ProvisionedThroughputExceededException` 이 `isThrottlingException()` 을 참으로 주지 않으면 `awsErrorDetails(...errorCode("ThrottlingException"))` 로
만든다. 실제로 쓴 모양을 보고서에 적는다.)

`BatchRequestsTest` 에서 상한 소진을 `IllegalStateException` 으로 단정하던 곳을 `TemporaryFailureException`(기다릴 시간 10초)으로 바꾼다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest' --tests '*DynamoDbTemporaryFailuresTest' --tests '*BatchRequestsTest'`
Expected: 컴파일 실패, 그다음 단정 실패.

- [ ] **Step 3: 구현**

`acquire` 의 `PutItemRequest` 에 `.returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)` 를 달고:

```java
                    .onErrorMap(ConditionalCheckFailedException.class, error ->
                            LockUnavailableException.잡혀_있다(쥔_용도(error)));
```

```java
    /** 조건 실패 때 DynamoDB 가 돌려준 기존 락 항목의 용도. 돌려받지 못했거나 알 수 없는 값이면 null(쓰기 경합으로 본다). */
    static MutationLock.LockPurpose 쥔_용도(ConditionalCheckFailedException error) {
        if (!error.hasItem() || error.item().get(PURPOSE) == null) {
            return null;
        }
        try {
            return MutationLock.LockPurpose.valueOf(error.item().get(PURPOSE).s());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
```

`renew` 의 조건 실패(리스 상실)는 지금처럼 `new LockUnavailableException("변경 락 리스를 잃었습니다")`(10초).

`BatchRequests` 의 두 `new IllegalStateException(…)` 을 `new TemporaryFailureException(…, TemporaryFailureException.기본_대기)` 로(메시지 그대로). 클래스 자바독에 "상한을 다 쓰면 일시 장애(③-1 이월, 설계 2026-10-05 §3.1)".

```java
/**
 * DynamoDB(AWS SDK v2) 예외 하나가 일시 장애인지 알아본다(설계 2026-10-05 §3.3). SDK 자신의 재시도 분류를 따른다 — 네트워크·시간 초과, 스로틀링, 5xx, SDK 가 재시도 가능하다고 표시한 것.
 * 조건 실패·검증 오류 같은 4xx 는 다시 보내도 같아 일시 장애가 아니다.
 */
public final class DynamoDbTemporaryFailures implements TemporaryFailureRecognizer {

    @Override
    public Optional<Duration> 재시도_대기(Throwable error) {
        if (error instanceof SdkClientException) {
            return Optional.of(TemporaryFailureException.기본_대기);
        }
        if (error instanceof SdkServiceException service
                && (service.isThrottlingException() || service.statusCode() >= 500 || service.retryable())) {
            return Optional.of(TemporaryFailureException.기본_대기);
        }
        return Optional.empty();
    }
}
```

`DynamoDbConfig` 에 `@Bean public TemporaryFailureRecognizer dynamoDbTemporaryFailures() { return new DynamoDbTemporaryFailures(); }`.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add storage-dynamodb/src/
git commit -F - <<'EOF'
feat: 락 획득 실패가 쥔 쪽의 용도로 기다릴 시간을 정하고(ReturnValuesOnConditionCheckFailure), 재시도 상한 소진은 일시 장애, DynamoDB 일시 장애 인식기(점검 M4·S2·③-1 이월)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 3: authz-openfga — Check 응답 이상과 OpenFGA 인식기

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java:~165,~171,~183`
- Create: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaTemporaryFailures.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaConfig.java`(인식기 빈)
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaTemporaryFailuresTest.java`(새), Checker 의 응답 이상 테스트(기존 파일에 단정 종류)

**Interfaces:**
- Consumes: Task 1 의 `TemporaryFailureException`, `TemporaryFailureRecognizer`.
- Produces: `public final class OpenFgaTemporaryFailures implements TemporaryFailureRecognizer`, 빈 이름 `openFgaTemporaryFailures`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
class OpenFgaTemporaryFailuresTest {

    private final OpenFgaTemporaryFailures 인식기 = new OpenFgaTemporaryFailures();

    private static final HttpHeaders 빈_헤더 = HttpHeaders.of(Map.of(), (a, b) -> true);

    @Test
    @DisplayName("OpenFGA 의 거절(400 검증 오류)이 아닌 ApiException 은 일시 장애다 — 내부 오류(500)")
    void 거절이_아닌_오류는_일시_장애다() {
        // given — SDK 0.9.11 의 공개 생성자(String, int, java.net.http.HttpHeaders, String)
        ApiException 내부오류 = new FgaApiInternalError("internal", 500, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(내부오류)).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("OpenFGA 의 거절(FgaApiValidationError)은 일시 장애가 아니다 — 다시 보내도 같다(우리 버그다)")
    void 거절은_아니다() {
        // given
        ApiException 거절 = new FgaApiValidationError("validation", 400, 빈_헤더, "{}");

        // when, then
        assertThat(인식기.재시도_대기(거절)).isEmpty();
        assertThat(인식기.재시도_대기(new IllegalStateException("x"))).isEmpty();
    }
}
```

(`HttpHeaders` 는 `java.net.http.HttpHeaders` 다 — Spring 의 것이 아니다.)

Checker: batchCheck 응답에 개별 오류가 있거나, 답한 수가 물은 수와 다르거나, 모르는 correlationId 가 오면 지금은 `IllegalStateException` 이다 → `TemporaryFailureException`(10초)이어야 한다.
기존 Checker 테스트(가짜 응답으로 이 셋을 고정한 테스트가 있으면 그것)의 단정 종류를 바꾸거나, 없으면 셋 중 하나(개별 오류)를 단정하는 테스트를 더한다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaTemporaryFailuresTest'` 와 Checker 테스트
Expected: 컴파일 실패, 그다음 단정 종류 실패.

- [ ] **Step 3: 구현**

```java
/**
 * OpenFGA SDK 예외 하나가 일시 장애인지 알아본다(설계 2026-10-05 §3.3). 거절(400 검증 오류 — 다시 보내도 같다)이 아닌 {@code ApiException} 은 일시 장애다.
 * 쓰기의 재시도 분류({@link OpenFgaErrors})와 같은 기준이다.
 */
public final class OpenFgaTemporaryFailures implements TemporaryFailureRecognizer {

    @Override
    public Optional<Duration> 재시도_대기(Throwable error) {
        if (error instanceof FgaApiValidationError) {
            return Optional.empty();
        }
        return error instanceof ApiException
                ? Optional.of(TemporaryFailureException.기본_대기)
                : Optional.empty();
    }
}
```

Checker 의 응답 이상 세 곳 `throw new IllegalStateException(…)` → `throw new TemporaryFailureException(…, TemporaryFailureException.기본_대기)`(메시지 그대로). check·batchCheck 를 부르는 순간의
동기 예외를 감싸는 두 곳(`"OpenFGA check 호출 실패"`, `"OpenFGA batchCheck 호출 실패"`)은 그대로 둔다 — SDK 가 매개변수를 거절한 것이라 우리 버그다(500).

`OpenFgaConfig` 에 `@Bean public TemporaryFailureRecognizer openFgaTemporaryFailures() { return new OpenFgaTemporaryFailures(); }`.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :authz-openfga:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add authz-openfga/src/
git commit -F - <<'EOF'
feat: OpenFGA batchCheck 응답 이상은 일시 장애, 거절이 아닌 OpenFGA SDK 오류를 알아보는 인식기(점검 M4)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 4: connector-scim — 분류기, 503 + Retry-After, 413, 오류 문구, 부분 실패

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/TemporaryFailureClassifier.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimException.java`(503 생성자, `retryAfter`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java`(번역 규칙, 새 `scimRoutes` 오버로드)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java`(분류기·한도 주입)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`, `ScimGroupHandler.java`(부분 실패 503, 주석)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/TemporaryFailureClassifierTest.java`(새), `ScimErrorTranslationTest.java`(새), 핸들러 테스트(부분 실패 단정)

**Interfaces:**
- Consumes: Task 1 의 `TemporaryFailureException`, `TemporaryFailureRecognizer`, `LockUnavailableException`.
- Produces:
  - `public final class TemporaryFailureClassifier` — `TemporaryFailureClassifier(List<TemporaryFailureRecognizer>)`, `static TemporaryFailureClassifier 표지만()`, `Optional<Duration> 재시도_대기(Throwable)`.
  - `ScimException.temporarilyUnavailable(String detail, Duration retryAfter)`(503), `Optional<Duration> getRetryAfter()`.
  - `ScimRouter.scimRoutes(ScimUserHandler, ScimGroupHandler, ScimListHandler, TemporaryFailureClassifier, long 본문_한도)` — 기존 세 인자 판은 `표지만()`·262144 로 위임한다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TemporaryFailureClassifierTest`:

```java
class TemporaryFailureClassifierTest {

    @Test
    @DisplayName("원인 사슬 어디에든 일시 장애 표지가 있으면 그 시간이다 — CompletionException·감싼 예외 안 깊이 있어도")
    void 사슬_깊이의_표지를_찾는다() {
        // given
        var 분류기 = TemporaryFailureClassifier.표지만();
        var 깊이 = new CompletionException(new IllegalStateException("감쌈",
                new LockUnavailableException("재적재 중", Duration.ofSeconds(60))));

        // when, then
        assertThat(분류기.재시도_대기(깊이)).contains(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("어댑터 인식기가 알아본 라이브러리 예외는 일시 장애다")
    void 인식기를_묻는다() {
        // given
        class 라이브러리오류 extends RuntimeException {}
        var 분류기 = new TemporaryFailureClassifier(List.of(e -> e instanceof 라이브러리오류
                ? Optional.of(Duration.ofSeconds(10)) : Optional.empty()));

        // when, then
        assertThat(분류기.재시도_대기(new RuntimeException(new 라이브러리오류()))).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("I/O 실패와 시간 초과는 일시 장애다 — OpenFGA HTTP 클라이언트가 감싸지 않고 올리는 것")
    void 입출력_실패는_일시_장애다() {
        // given
        var 분류기 = TemporaryFailureClassifier.표지만();

        // when, then
        assertThat(분류기.재시도_대기(new CompletionException(new java.net.ConnectException("refused")))).contains(Duration.ofSeconds(10));
        assertThat(분류기.재시도_대기(new java.util.concurrent.TimeoutException())).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("모르는 예외는 일시 장애가 아니다 — 500(버그)으로 간다")
    void 모르는_예외는_아니다() {
        // when, then
        assertThat(TemporaryFailureClassifier.표지만().재시도_대기(new NullPointerException())).isEmpty();
    }
}
```

`ScimErrorTranslationTest` — 핸들러 대신 예외를 던지는 작은 라우트로 번역만 본다. `ScimRouter` 의 번역 함수를 패키지 전용 static
`toScimError(Throwable, TemporaryFailureClassifier, long 본문_한도)` 로 열고 `RouterFunctions.route(GET("/boom"), r -> Mono.error(예외)).filter(…)` 대신 그 함수를 직접 불러 응답을
`WebTestClient.bindToRouterFunction(route(GET("/boom"), r -> toScimError(예외, …)))` 로 받는다:

- `LockUnavailableException.잡혀_있다(REBUILD)` → 503, 헤더 `Retry-After: 60`, 본문 `$.status == "503"`, `$.schemas[0] == ScimSchemas.ERROR`.
- `TupleWriteAbortedException` → 503, `Retry-After: 10`.
- `new NullPointerException()` → 500, `Retry-After` 없음.
- 원인 사슬에 `new DataBufferLimitException("Exceeded limit on max bytes to buffer : 262144")` 를 단 `DecodingException` → 413, `$.detail` 에 `262144` 와 "PATCH" 가 있다, `scimType` 없음.
- `new UnsupportedMediaTypeStatusException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON))` → 415, `$.detail` 에 `"application/scim+json"` 이 있고 자바 클래스 이름(`org.springframework`·`Exception`)이 없다.
- `new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "내부 이유 com.x.Y")` → 406, `$.detail` 에 `com.x.Y` 가 없다.

핸들러 테스트(기존 `ScimUserHandlerTest`·`ScimGroupHandlerTest` 의 부분 실패 테스트 — `fullyApplied == false` 를 만드는 가짜 쓰기를 쓴 곳)의 기대 상태를 500 → 503, `Retry-After: 10` 으로 바꾼다.
그런 테스트가 없으면 직원 DELETE 의 부분 실패 하나를 더한다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*TemporaryFailureClassifierTest' --tests '*ScimErrorTranslationTest'`
Expected: 컴파일 실패, 그다음 503·413·문구 단정 실패.

- [ ] **Step 3: 구현**

```java
/**
 * 예외가 일시 장애(다시 보내면 나을 수 있다)인지 원인 사슬을 따라가며 가른다(설계 2026-10-05 §3.3). core 표지 → 어댑터 인식기 → I/O 실패·시간 초과 순으로 본다.
 * 어디에도 없으면 일시 장애가 아니다 — 500(버그).
 */
public final class TemporaryFailureClassifier {

    private final List<TemporaryFailureRecognizer> recognizers;

    public TemporaryFailureClassifier(List<TemporaryFailureRecognizer> recognizers) {
        this.recognizers = List.copyOf(recognizers);
    }

    /** core 표지와 I/O 실패만 본다 — 어댑터가 없는 테스트·조립의 기본값 */
    public static TemporaryFailureClassifier 표지만() {
        return new TemporaryFailureClassifier(List.of());
    }

    public Optional<Duration> 재시도_대기(Throwable error) {
        Set<Throwable> 본것 = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable 원인 = error; 원인 != null && 본것.add(원인); 원인 = 원인.getCause()) {
            if (원인 instanceof TemporaryFailureException 일시) {
                return Optional.of(일시.retryAfter());
            }
            for (TemporaryFailureRecognizer 인식기 : recognizers) {
                Optional<Duration> 대기 = 인식기.재시도_대기(원인);
                if (대기.isPresent()) {
                    return 대기;
                }
            }
            if (원인 instanceof IOException || 원인 instanceof TimeoutException) {
                return Optional.of(TemporaryFailureException.기본_대기);
            }
        }
        return Optional.empty();
    }
}
```

`ScimException` 에 `private final Duration retryAfter;`, 네 인자 생성자 `(HttpStatus, String scimType, String detail, Duration retryAfter)`(기존 세 인자 생성자는 `retryAfter` null 로 위임),
`public Optional<Duration> getRetryAfter()`, 그리고:

```java
    /** 다시 보내면 나을 수 있다 — 503 + Retry-After(설계 2026-10-05 §3.5). 부분 실패에 쓴다. */
    public static ScimException temporarilyUnavailable(String detail, Duration retryAfter) {
        return new ScimException(HttpStatus.SERVICE_UNAVAILABLE, null, detail, retryAfter);
    }
```

`internal` 의 자바독을 "커밋 직후 다시 읽기가 빈 경우처럼 설명할 수 없는 상태 — 500" 으로 고친다.

`ScimRouter` — 번역 순서(설계 §3.4):

```java
    static Mono<ServerResponse> toScimError(Throwable error, TemporaryFailureClassifier 분류기, long 본문_한도) {
        if (error instanceof ScimException scim) {
            return write(scim.getStatus(), scim.getScimType(), scim.getMessage(), scim.getRetryAfter().orElse(null));
        }
        if (error instanceof DirectoryConflictException conflict) {
            return write(HttpStatus.CONFLICT, "uniqueness", conflict.getMessage(), null);
        }
        if (error instanceof GroupGraphTooLargeException tooLarge) {
            return write(HttpStatus.BAD_REQUEST, "invalidValue", tooLarge.getMessage(), null);
        }
        // 본문 크기 한도 — DecodingException 이 감싸 오므로 그 분기보다 먼저 본다(점검 M3)
        if (원인에_있다(error, DataBufferLimitException.class)) {
            return write(HttpStatus.PAYLOAD_TOO_LARGE, null,
                    "요청 본문이 한도(%d바이트)를 넘었습니다 — 큰 조직의 멤버는 PATCH 로 나눠 보내세요".formatted(본문_한도), null);
        }
        // 다시 보내면 나을 수 있다 — 503 + Retry-After. 500 은 버그에만 남긴다(점검 M4, 설계 §3.4)
        Optional<Duration> 대기 = 분류기.재시도_대기(error);
        if (대기.isPresent()) {
            log.warn("SCIM 요청이 일시 장애로 실패했다 — 503, Retry-After {}초", 대기.get().toSeconds(), error);
            return write(HttpStatus.SERVICE_UNAVAILABLE, null, 일시_장애_문구(error), 대기.get());
        }
        if (error instanceof DecodingException || error instanceof ServerWebInputException) {
            return write(HttpStatus.BAD_REQUEST, "invalidSyntax", "요청 본문을 해석할 수 없습니다", null);
        }
        // WebFlux 가 정한 상태는 쓰되 Spring 의 reason 은 싣지 않는다 — 내부 클래스 이름이 나간다(점검 S10)
        if (error instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            return write(status, null, 상태_문구(status), null);
        }
        log.error("SCIM 요청 처리 중 예기치 않은 오류", error);
        return write(HttpStatus.INTERNAL_SERVER_ERROR, null, "내부 오류가 발생했습니다", null);
    }
```

- `원인에_있다(Throwable, Class<?>)` — 원인 사슬을 따라가는 작은 도우미(같은 예외를 두 번 보지 않는다).
- `일시_장애_문구(error)` — `TemporaryFailureException` 이면 그 메시지, 아니면 "하위 시스템이 잠시 응답하지 않습니다 — 잠시 뒤 다시 보내 주세요"(라이브러리 메시지를 싣지 않는다).
- `상태_문구(status)` — 415 는 "Content-Type 은 application/scim+json 또는 application/json 이어야 합니다", 그 밖은 `status.getReasonPhrase()`.
- `write(status, scimType, detail, Duration retryAfter)` — `retryAfter` 가 있으면 `.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()))`.
- 락 전용 분기(`LockUnavailableException` → 503)는 지운다 — 분류기가 표지로 잡는다. 그 위의 주석은 503 분기로 옮기고 "IdP 는 503 을 재시도 신호로…" 를 남긴다.
- `scimRoutes(users, groups, lists, 분류기, 본문_한도)` 의 `.onError(Throwable.class, (error, request) -> toScimError(error, 분류기, 본문_한도))`. 세 인자 판은
  `scimRoutes(users, groups, lists, TemporaryFailureClassifier.표지만(), 256 * 1024)`.

`ScimConfig`:

```java
    @Bean
    public RouterFunction<ServerResponse> scimRouterFunction(ScimUserHandler users, ScimGroupHandler groups,
                                                             ScimListHandler lists,
                                                             ObjectProvider<TemporaryFailureRecognizer> recognizers,
                                                             @Value("${spring.codec.max-in-memory-size:256KB}") DataSize 본문_한도) {
        return ScimRouter.scimRoutes(users, groups, lists,
                new TemporaryFailureClassifier(recognizers.orderedStream().toList()), 본문_한도.toBytes());
    }
```

핸들러의 부분 실패 네 곳(`ScimUserHandler` delete·respond, `ScimGroupHandler` patch·delete·respond) — `ScimException.internal("일부 튜플 … 재시도해 주세요: " + id)` 를
`ScimException.temporarilyUnavailable("일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기)` 로(삭제는 "삭제"). `respond` 의 자바독 첫 줄
"응답은 5xx 로 돌려 IdP 가 재시도하게 한다" 를 "응답은 503 + Retry-After 로 돌려 IdP 가 재시도하게 한다(설계 2026-10-05 §3.5)" 로.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test` 그다음 `./gradlew :app-scim:compileTestJava`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/
git commit -F - <<'EOF'
feat: SCIM 일시 장애는 503 + Retry-After(분류기 — core 표지·어댑터 인식기·I/O), 부분 실패도 503, 큰 본문 413, 오류 문구에 내부 정보 없음(점검 M4·S2·M3·S10)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 5: connector-scim — 라우트 밖 요청(S7)과 `noTarget`(S11)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java`(마지막 라우트)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java:~123-128, ~215-220`(path 없는 remove)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRouteMissTest.java`(새), `ScimPatchApplierTest`(더함)

**Interfaces:**
- Consumes: Task 4 의 `ScimRouter.scimRoutes(…)`·`toScimError`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimRouteMissTest` — 기존 핸들러 테스트처럼 `WebTestClient.bindToRouterFunction(ScimRouter.scimRoutes(…))` 로 묶는다(핸들러는 가짜 상태로 만든다):

```java
    @Test
    @DisplayName("있는 경로에 틀린 메서드는 405 이고 Allow 에 그 경로가 받는 메서드를 싣는다 — SCIM Error 형식이다(점검 S7)")
    void 틀린_메서드는_405다() {
        // when, then
        client.delete().uri("/scim/v2/Users").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertThat(allow).contains("GET").contains("POST"))
                .expectBody().jsonPath("$.status").isEqualTo("405")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2/Bulk", "/scim/v2/Me", "/scim/v2/Schemas", "/scim/v2/ResourceTypes", "/scim/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:User"})
    @DisplayName("지원하지 않는 SCIM 엔드포인트는 501 이다 — RFC 7644 §3.12, /Me 는 §3.11")
    void 지원하지_않는_엔드포인트는_501이다(String path) {
        // when, then
        client.get().uri(path).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectBody().jsonPath("$.status").isEqualTo("501");
    }

    @Test
    @DisplayName("모르는 경로는 404 이고 SCIM Error 형식이다")
    void 모르는_경로는_404다() {
        // when, then
        client.get().uri("/scim/v2/Users/kim/extra").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }
```

`/scim/v2/Bulk` 는 POST 로도 501 인지 한 줄 더 본다. `ScimPatchApplierTest` 에 — 직원과 조직 각각 path 없는 `{"op":"remove"}` 가 400 `noTarget`(RFC 7644 §3.5.2.2).

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimRouteMissTest' --tests '*ScimPatchApplierTest'`
Expected: 405·501·404 형식과 `noTarget` 단정 실패.

- [ ] **Step 3: 구현**

`scimRoutes` 의 마지막 라우트(`rootQuery` 들 뒤, `.onError` 앞)에 `.route(RequestPredicates.path("/scim/v2/**"), ScimRouter::라우트_밖)`:

```java
    /** 지원하지 않는 SCIM 엔드포인트 — RFC 7644 는 501 로 알린다(§3.12, /Me 는 §3.11). Bulk 는 ServiceProviderConfig 가 지원 안 함으로 선언했다. */
    private static final List<PathPattern> 지원_안_함 = 패턴("/scim/v2/Bulk", "/scim/v2/Me", "/scim/v2/Me/**",
            "/scim/v2/Schemas", "/scim/v2/Schemas/**", "/scim/v2/ResourceTypes", "/scim/v2/ResourceTypes/**");

    /** 있는 경로와 받는 메서드 — 위 라우트와 같아야 한다. 틀린 메서드는 405 + Allow 로 알린다. */
    private static final Map<PathPattern, Set<HttpMethod>> 받는_메서드 = Map.of(
            패턴하나("/scim/v2/Users"), Set.of(HttpMethod.GET, HttpMethod.POST),
            패턴하나("/scim/v2/Users/.search"), Set.of(HttpMethod.POST),
            패턴하나("/scim/v2/Users/{id}"), Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            패턴하나("/scim/v2/Groups"), Set.of(HttpMethod.GET, HttpMethod.POST),
            패턴하나("/scim/v2/Groups/.search"), Set.of(HttpMethod.POST),
            패턴하나("/scim/v2/Groups/{id}"), Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            패턴하나("/scim/v2/ServiceProviderConfig"), Set.of(HttpMethod.GET));

    private static Mono<ServerResponse> 라우트_밖(ServerRequest request) {
        PathContainer path = request.requestPath().pathWithinApplication();
        if (지원_안_함.stream().anyMatch(p -> p.matches(path))) {
            return Mono.error(ScimException.notImplemented("지원하지 않는 SCIM 엔드포인트입니다: " + path.value()));
        }
        // `.search` 가 `{id}` 에도 맞으므로 받는 메서드를 모두 모은다
        Set<HttpMethod> 받는것 = 받는_메서드.entrySet().stream()
                .filter(e -> e.getKey().matches(path))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!받는것.isEmpty()) {
            return Mono.error(ScimException.methodNotAllowed("이 경로는 " + request.method() + " 를 받지 않습니다: " + path.value(), 받는것));
        }
        return Mono.error(ScimException.notFound("없는 SCIM 경로입니다: " + path.value()));
    }
```

`ScimException.methodNotAllowed(String detail, Set<HttpMethod> allow)` 를 더하고(405, `allow` 를 싣는다), `toScimError` 가 `ScimException` 의 `allow` 가 있으면 `Allow` 헤더를 단다
(`write` 에 헤더를 더하는 방식은 Task 4 의 `Retry-After` 와 같은 자리). 패턴은 `PathPatternParser.defaultInstance.parse(…)`.

`ScimPatchApplier` — path 없는 연산의 두 자리(조직 ~:125, 직원 ~:217):

```java
        if (path == null || path.isBlank()) {
            if (op.equals("remove")) {
                // RFC 7644 §3.5.2.2 — path 없는 remove 는 400 noTarget(점검 S11)
                throw ScimException.noTarget("remove 에는 path 가 있어야 합니다");
            }
            requireReplaceOrAdd(op, operation.op());
            …(그대로)
```

(조직 쪽의 정규화된 op 변수 이름은 그 메서드의 것을 쓴다.)

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/
git commit -F - <<'EOF'
feat: SCIM 라우트 밖 요청도 SCIM Error — 틀린 메서드 405 + Allow, Bulk·Me·Schemas·ResourceTypes 501, 그 밖 404(점검 S7), path 없는 remove 는 noTarget(S11)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 6: app-scim 끝에서 끝

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimErrorSignalsEndToEndTest.java`

**Interfaces:**
- Consumes: Task 1~5 전부. 인식기 빈 이름 `dynamoDbTemporaryFailures`·`openFgaTemporaryFailures`.

- [ ] **Step 1: 테스트를 쓴다**

기존 app-scim e2e(예: `ScimEndToEndTest`)의 모양(Testcontainers DynamoDB·OpenFGA, `@SpringBootTest`, `WebTestClient`)을 따른다:

```java
    @Test
    @DisplayName("재적재가 변경 락을 쥐고 있으면 SCIM 쓰기는 503 이고 Retry-After 가 60초다(점검 M4·S2)")
    void 재적재_중의_쓰기는_60초_뒤에_다시() {
        // given — 재적재 용도로 락을 쥔다
        var lease = lock.acquire(MutationLock.LockPurpose.REBUILD).block(Duration.ofSeconds(10));

        try {
            // when, then — 쓰기는 3초 기다려 본 뒤 503
            client.post().uri("/scim/v2/Users").contentType(SCIM_JSON).bodyValue(직원_본문("kim"))
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                    .expectBody().jsonPath("$.status").isEqualTo("503");
        } finally {
            lock.release(lease).block(Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName("한도를 넘는 PATCH 본문은 413 이고 한도를 알려 준다(점검 M3)")
    void 큰_본문은_413() {
        // given — 멤버 값 5,000개(한 줄 약 60바이트, 합쳐 256KB 를 넘는다)
        String 멤버들 = IntStream.range(0, 5_000)
                .mapToObj(i -> "{\"value\":\"00000000-0000-4000-8000-%012d\"}".formatted(i))
                .collect(Collectors.joining(","));
        String 본문 = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[%s]}]}""".formatted(멤버들);

        // when, then
        client.patch().uri("/scim/v2/Groups/any").contentType(SCIM_JSON).bodyValue(본문)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectBody().jsonPath("$.status").isEqualTo("413")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("262144"));
    }

    @Test
    @DisplayName("Bulk 는 501 이다 — 지원하지 않음을 SCIM Error 로 알린다(점검 S7)")
    void Bulk_는_501() {
        // when, then
        client.post().uri("/scim/v2/Bulk").contentType(SCIM_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .expectBody().jsonPath("$.status").isEqualTo("501");
    }

    @Test
    @DisplayName("앱은 두 어댑터의 일시 장애 인식기를 모두 싣는다")
    void 인식기_둘이_실린다() {
        // then
        assertThat(context.getBeansOfType(TemporaryFailureRecognizer.class))
                .containsKeys("dynamoDbTemporaryFailures", "openFgaTemporaryFailures");
    }
```

(`MutationLock` 의 획득·반납 시그니처, 직원 본문 도우미, 클라이언트 설정은 기존 e2e 와 core 포트를 따른다. 락 획득의 토큰 인자가 있으면 고정 토큰을 쓴다.)

- [ ] **Step 2: 통과를 본다**

Run: `./gradlew :app-scim:test --tests '*ScimErrorSignalsEndToEndTest'`
Expected: PASS. (앞 과제가 기능을 넣었으므로 처음부터 통과한다 — 확인용으로 `LockUnavailableException.잡혀_있다` 의 60초를 잠깐 2초로 바꿔 첫 테스트가 실패하는 것을 한 번 보고
되돌린다. 보고서에 적는다.) DynamoDB Local 이 조건 실패 때 기존 항목을 돌려주지 않는다면 첫 테스트는 `Retry-After: 2` 가 된다 — 그때는 Task 2 보고서의 결론을 따라 기대값을 2 로 두고
`@DisplayName` 과 보고서에 그 사실(실제 DynamoDB 에서는 60)을 적는다.

- [ ] **Step 3: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimErrorSignalsEndToEndTest.java
git commit -F - <<'EOF'
test: app-scim e2e — 재적재 중 쓰기는 503 + Retry-After 60, 큰 본문 413, Bulk 501, 인식기 둘이 실린다

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 7: README, 점검 문서, 스펙 보정

**Files:**
- Modify: `README.md`(SCIM 절), `docs/superpowers/specs/2026-09-28-full-audit.md`, `docs/superpowers/specs/2026-10-05-scim-error-signals-design.md`

- [ ] **Step 1: README**

SCIM 절에 오류 표:

| 상태 | 언제 | IdP 는 |
|---|---|---|
| 400 (`invalidSyntax`·`invalidPath`·`invalidValue`·`invalidFilter`·`noTarget`·`mutability`) | 요청이 틀렸다 | 고쳐 보낸다 |
| 404 | 없는 리소스·경로 | — |
| 405 + `Allow` | 경로가 받지 않는 메서드 | — |
| 409 `uniqueness` | `userName`·조직 `externalId` 가 겹친다 | — |
| 413 | 본문이 한도(기본 256KB)를 넘었다 | 멤버를 PATCH 로 나눠 보낸다 |
| 415 | Content-Type 이 SCIM·JSON 이 아니다 | — |
| 501 | 지원하지 않는 기능(Bulk, `/Me`, `/Schemas`, `/ResourceTypes`, 서버 루트 조회) | — |
| 503 + `Retry-After` | 일시 장애 — 다른 쓰기가 락을 쥠(2초), 재적재·전체 동기화 중(60초), OpenFGA·DynamoDB 장애나 부분 실패(10초) | `Retry-After` 뒤에 같은 요청을 다시 보낸다 |
| 500 | 우리 버그 | 로그를 본다 |

그리고 지금 README 에서 "부분 실패는 500", "IdP 는 500 을 …" 같은 문장을 찾아 고친다.

- [ ] **Step 2: 점검 문서**

요약 표의 M3·M4 행과 부록 표의 S2·S7·S10·S11 행 끝에 `**→ 해결(2026-10-05, 슬라이드 ⑤-1)**`(④-2 의 표시와 같은 모양).

- [ ] **Step 3: 스펙 보정**

- §3.3 — "app-scim 의 구현" 을 고친다: 인식기는 SDK 를 가진 어댑터(storage-dynamodb `DynamoDbTemporaryFailures`, authz-openfga `OpenFgaTemporaryFailures`)가 core 포트
  `TemporaryFailureRecognizer` 빈으로 내고, connector-scim 의 분류기가 모은다 — app-scim 은 OpenFGA SDK 를 main 에 두지 않는다(계획 단계에서 정한 것). I/O 실패·시간 초과도 일시 장애로 본다.
- §3.1 — OpenFGA batchCheck 응답 이상(개별 오류·수 불일치·모르는 correlationId)도 표지를 단다. check·batchCheck 호출 순간의 동기 예외는 매개변수 거절이라 500 이다.
- 구현 중 정한 것(컨트롤러 장부의 `Ruling:`)을 해당 절에 "구현 중 정한 것" 으로 한 줄씩. §8 은 건드리지 않는다(컨트롤러가 채운다).

- [ ] **Step 4: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-05-scim-error-signals-design.md
git commit -F - <<'EOF'
docs: ⑤-1 README SCIM 오류 표(503 + Retry-After, 413, 405, 501), 점검 M3·M4·S2·S7·S10·S11 해결 표시, 설계 보정

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 그다음 `./gradlew cleanScaleTest scaleTest` 를 한 번에 하나씩 돌린다.
- 스펙 §8 에 결과(테스트 수·시간)를 적고 커밋한다.
