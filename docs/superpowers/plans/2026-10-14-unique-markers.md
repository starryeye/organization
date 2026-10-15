# userName·조직 externalId 유일성 표지판 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SCIM 직원 `userName`(대소문자 무시)과 조직 `externalId` 의 유일성을 최종 일관성 GSI 대신 DynamoDB 조건부 쓰기로 지키는 표지판 줄로 보장한다.

**Architecture:**
- 저장소 포트에 표지판 메서드 넷을 더한다(`claimUnique`·`takeOverUnique`·`releaseUnique`·`clearUniqueMarkers`).
- core 의 `UniqueMarkers` 가 판단한다: 차지, 409, 주인이 나, 넘겨받기, 503, 정리.
- 유스케이스는 SCIM 입구 여섯 곳에서 락 안·OpenFGA 쓰기 전에 차지하고, 일이 끝나면 정리한다.
- GSI 이름 조회(`findUserIdsByUserName`)는 없앤다. `wipe` 는 표지판까지 비운다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor 3.7, AWS SDK v2 DynamoDB(Async), JUnit 5, AssertJ, Testcontainers(DynamoDB Local).

**Spec:** `docs/superpowers/specs/2026-10-14-unique-markers-design.md`

## Global Constraints

- **표지판 줄.**
  - PK 는 `UNIQUE#USERNAME`(직원 userName), `UNIQUE#GROUP_EXTERNAL_ID`(조직 externalId)다.
  - SK 는 키, 속성은 `ownerId`(문자열)와 `claimedAt`(epoch 밀리초 숫자)다.
  - GSI 키 속성(`externalId`·`GSI1PK`·`GSI1SK`·`displayNameKey`)을 넣지 않는다. 테이블·GSI 정의는 바뀌지 않는다.
- **키 규칙(core 가 정한다).**
  - `USER_NAME` 은 `value.toLowerCase(Locale.ROOT)`, `GROUP_EXTERNAL_ID` 는 값 그대로다.
  - 키는 UTF-8 1024바이트까지다. 넘으면 `UniqueValueTooLongException` → SCIM 400 `invalidValue` 다.
  - 값이 null·빈 값이면 표지판이 없다.
- **조건.**
  - 차지: `attribute_not_exists(#pk)` + `ReturnValuesOnConditionCheckFailure.ALL_OLD`. 막히면 있던 표지판을 돌려준다(주인이 나여도). 돌려받지 못하면 `UniqueClaim("", 시도 시각)` 이다.
  - 넘겨받기: `#owner = :previous`.
  - 놓기: `#owner = :owner` 이고, 조건 실패는 조용히 넘어간다.
  - 비우기: 두 파티션을 강한 일관성 Query → BatchWrite 삭제(25개씩).
- **판단 순서(§4.1).**
  1. 주인이 그 값을 쥐면 `DirectoryConflictException` 이다. 문구는 지금과 같다: `이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s` / `이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s`.
  2. 주인이 나면 차지한 것으로 본다.
  3. `claimedAt + 넘겨받기_기준 <= 지금` 이면 넘겨받기다. 넘겨받기가 실패하면 503 이다.
  4. 그 밖이면 `TemporaryFailureException(…, Duration.ofSeconds(2))` 다.
  - 주인 확인은 `findUser`·`findGroupHeader`(강한 읽기)로만 한다.
- **시계.** `Instant.ofEpochMilli(Schedulers.parallel().now(TimeUnit.MILLISECONDS))` 를 쓴다(가상 시간으로 시험).
- **넘겨받기 기준.** 락 TTL 이다. 운영 결선은 `dynamodb.lock-ttl` 이고, 테스트 생성자는 `DEFAULT_LOCK_TTL = 30초` 다.
- **정리(§4.4·§4.5).**
  - 일이 값을 내고 `fullyApplied()` 면 "깨끗하면 놓을 값"을 읽지 않고 놓는다.
  - 그 밖(오류·일부 반영·빈 끝)이면 주인을 한 번 읽어 "건드린 값" 중 주인이 쥐지 않은 것만 놓는다.
  - 정리의 실패는 경고 로그만 남기고 결과를 바꾸지 않는다.
  - 리스를 잃어 일이 취소되면 정리하지 않는다.
- **범위.**
  - 표지판은 SCIM 입구 여섯 곳(`createUser`·`changeUser`·`removeUser`·`createGroup`·`changeGroup`·`removeGroup`)에서만 다룬다.
  - `upsertUser`·`upsertGroup`(테스트용), LDAP, `replaceWith`, `saveUser`·`saveGroup`·`saveGroupChange`·`deleteUser`·`deleteGroup` 은 바꾸지 않는다.
  - TransactWriteItems 를 쓰지 않는다.
- **없앨 것.**
  - `DirectoryStateRepository.findUserIdsByUserName` 은 없앤다.
  - `findGroupIdsByExternalId` 는 관리 API 가 쓰므로 남긴다. 다만 SCIM 중복 판정에는 쓰지 않는다.
- **테스트.**
  - 새 테스트는 `// given`·`// when`·`// then` 표지를 단다.
  - 서브에이전트는 해당 모듈 테스트만 돌린다(`./gradlew :core:test` 등). 전체 `test`·`scaleTest` 는 컨트롤러가 돌린다. Gradle 은 한 번에 하나만 돌린다.

## Review Focus

1. **`Kim` → `kim` 처럼 키가 같은 개명:** 표지판을 건드리지 않고, 자기 자신과 409 로 부딪치지 않아야 한다. → Task 3 `대소문자만_바꾸면_표지판을_건드리지_않는다`.
2. **생성이 일부 실패로 커밋을 건너뛰거나 오류로 끝난 경우:** 차지한 표지판을 놓아야 한다. 아니면 IdP 재시도가 30초 동안 503 이다. → Task 3 `생성이_일부_실패하면_표지판을_놓아_재시도가_곧_통과한다`, `생성이_오류로_끝나도_표지판을_놓는다`.
3. **도중에 리스를 잃어 일이 취소된 경우:** 표지판을 놓지 않아야 한다(락 없이 쓰지 않는다). → Task 3 `리스를_잃어_취소되면_표지판을_놓지_않는다`.
4. **표지판을 차지한 뒤 실패한 개명:** 새 표지판은 놓고 옛 표지판은 남겨야 한다(META 는 옛 이름). → Task 3 `개명이_오류로_끝나면_새_표지판만_놓는다`.
5. **조직 PATCH 로 externalId 를 비우기:** 커밋 뒤 옛 표지판을 놓아야 하고, 빈 값은 차지하지 않는다. → Task 3 `조직_externalId_를_비우면_옛_표지판을_놓는다`.

---

### Task 1: 표지판 포트·모델·DynamoDB 구현·가짜 저장소

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/UniqueKind.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/model/UniqueClaim.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` (메서드 넷 추가, `findUserIdsByUserName` 은 Task 3 에서 없앤다)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkers.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (위임 넷)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (전달 래퍼 177행 근처에 넷 추가)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkersTest.java`

**Interfaces:**
- Produces:
  - `enum UniqueKind { USER_NAME, GROUP_EXTERNAL_ID; String key(String value); static final int MAX_KEY_BYTES = 1024; static boolean fits(String key) }`
  - `record UniqueClaim(String ownerId, Instant claimedAt)`
  - 포트:
    - `Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at)` — 빈 값이면 차지한 것이다.
    - `Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at)`
    - `Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId)`
    - `Mono<Void> clearUniqueMarkers()`
  - 가짜 저장소:
    - `public final Map<UniqueKind, Map<String, UniqueClaim>> uniqueMarkers` (적은 표지판)
    - `public volatile RuntimeException failReleaseUnique`
    - 직원·조직 지도에 심은 것은 지금 값의 표지판을 `Instant.EPOCH` 에 쥔 것으로 본다.

- [ ] **Step 1: 모델 둘을 만든다**

`core/src/main/java/dev/starryeye/organization/core/model/UniqueKind.java`:

```java
package dev.starryeye.organization.core.model;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * SCIM 이 유일하게 지키는 값의 종류(설계 2026-10-14 §3.1). 표지판 줄의 키는 이 규칙으로 만든다 — 대소문자 무시 같은 규칙은 도메인의 몫이라
 * 저장소는 받은 키를 그대로 쓴다.
 */
public enum UniqueKind {

    /** 직원 userName — 대소문자를 가리지 않는다(RFC 7643 caseExact=false). 키는 소문자다. */
    USER_NAME {
        @Override
        public String key(String value) {
            return value.toLowerCase(Locale.ROOT);
        }
    },

    /** 조직 externalId — 대소문자를 가린다(RFC 7643 caseExact). 키는 값 그대로다. */
    GROUP_EXTERNAL_ID {
        @Override
        public String key(String value) {
            return value;
        }
    };

    /** 키의 최대 길이(UTF-8 바이트) — 포트 계약이다. DynamoDB 정렬키 한도와 같다. */
    public static final int MAX_KEY_BYTES = 1024;

    public abstract String key(String value);

    /** 키가 한도 안인가. */
    public static boolean fits(String key) {
        return key.getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES;
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/UniqueClaim.java`:

```java
package dev.starryeye.organization.core.model;

import java.time.Instant;

/**
 * 표지판 줄 하나 — 그 값을 쥔 주인(직원·조직 아이디)과 차지한 시각(설계 2026-10-14 §3.1).
 */
public record UniqueClaim(String ownerId, Instant claimedAt) {
}
```

- [ ] **Step 2: 포트에 메서드 넷을 더한다**

`DirectoryStateRepository.java` 의 `replaceCutEdges` 아래에 넣는다. import 에 `dev.starryeye.organization.core.model.UniqueClaim`, `dev.starryeye.organization.core.model.UniqueKind`, `java.time.Instant` 를 더한다.

```java
    /**
     * 유일 값의 표지판을 차지한다(설계 2026-10-14 §3.2). 없으면 {@code ownerId} 가 {@code at} 에 차지하고 빈 값이다. 이미 있으면 — 주인이
     * {@code ownerId} 여도 — 아무것도 쓰지 않고 그 표지판을 돌려준다. 조건부 쓰기 하나다. {@code key} 는 {@link UniqueKind#key} 로 만든 값이고
     * {@link UniqueKind#MAX_KEY_BYTES} 를 넘지 않는다.
     */
    Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at);

    /** 표지판의 주인이 아직 {@code previousOwnerId} 일 때만 {@code ownerId} 로 바꾼다. 바꿨으면 참이다. */
    Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at);

    /** 표지판의 주인이 {@code ownerId} 일 때만 지운다. 남의 것이거나 없으면 아무것도 하지 않는다. */
    Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId);

    /** 표지판을 모두 지운다 — SCIM wipe 가 조직도를 비운 뒤 부른다. 직원·조직·보류 목록 같은 다른 줄은 건드리지 않는다. */
    Mono<Void> clearUniqueMarkers();
```

- [ ] **Step 3: 저장소 테스트를 쓴다 (실패 확인용)**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkersTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 유일 값 표지판 줄의 조건부 쓰기(설계 2026-10-14 §3.2) — DynamoDB Local 로 조건과 돌려받는 값을 본다. */
class DynamoDbUniqueMarkersTest extends DynamoDbTestSupport {

    private static final Instant T1 = Instant.parse("2026-10-14T00:00:00.123Z");
    private static final Instant T2 = Instant.parse("2026-10-14T00:01:00Z");

    private DynamoDbDirectoryStateRepository repository;

    @BeforeEach
    void 준비한다() {
        repository = new DynamoDbDirectoryStateRepository(client, properties, Clock.fixed(T1, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("없으면 차지하고 빈 값이다 — 있으면 주인이 나여도 아무것도 쓰지 않고 그 표지판(주인·시각)을 돌려준다")
    void 없으면_차지하고_있으면_돌려준다() {
        // when
        UniqueClaim 처음 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();
        UniqueClaim 남 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-2", T2).block();
        UniqueClaim 나 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T2).block();

        // then
        assertThat(처음).isNull();
        assertThat(남).isEqualTo(new UniqueClaim("u-1", T1));
        assertThat(나).as("주인이 나여도 차지하지 않는다 — 생성에는 자기 자신이 없다").isEqualTo(new UniqueClaim("u-1", T1));
    }

    @Test
    @DisplayName("종류마다 파티션이 따로다 — 같은 키라도 직원 userName 과 조직 externalId 는 부딪치지 않는다")
    void 종류마다_따로다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "ext-1", "u-1", T1).block();

        // when
        UniqueClaim 조직 = repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-1", T1).block();

        // then
        assertThat(조직).isNull();
    }

    @Test
    @DisplayName("저장소는 키를 바꾸지 않는다 — 대소문자 규칙은 core 가 키를 만들 때 적용한다")
    void 키를_그대로_쓴다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "Kim", "u-1", T1).block();

        // when
        UniqueClaim 소문자 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-2", T1).block();

        // then
        assertThat(소문자).isNull();
    }

    @Test
    @DisplayName("넘겨받기는 주인이 아직 옛 주인일 때만 된다")
    void 넘겨받기는_옛_주인일_때만이다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "ghost", T1).block();

        // when
        Boolean 넘겨받았다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-2", "ghost", T2).block();
        Boolean 늦었다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-3", "ghost", T2).block();

        // then
        assertThat(넘겨받았다).isTrue();
        assertThat(늦었다).isFalse();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block())
                .isEqualTo(new UniqueClaim("u-2", T2));
    }

    @Test
    @DisplayName("없는 표지판은 넘겨받지 못한다")
    void 없는_표지판은_넘겨받지_못한다() {
        // when
        Boolean 넘겨받았다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-2", "ghost", T2).block();

        // then
        assertThat(넘겨받았다).isFalse();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
    }

    @Test
    @DisplayName("놓기는 내 것만 지운다 — 남의 것이거나 없으면 조용히 넘어간다")
    void 놓기는_내_것만_지운다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();

        // when
        repository.releaseUnique(UniqueKind.USER_NAME, "kim", "u-2").block();
        repository.releaseUnique(UniqueKind.USER_NAME, "없음", "u-2").block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block())
                .isEqualTo(new UniqueClaim("u-1", T1));

        // when
        repository.releaseUnique(UniqueKind.USER_NAME, "kim", "u-1").block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
    }

    @Test
    @DisplayName("비우기는 두 종류의 표지판만 지운다 — 직원 META 와 보류 목록은 그대로다")
    void 비우기는_표지판만_지운다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();
        repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-1", T1).block();
        repository.saveUser(new DirectoryUser("u-1", null, "kim", "김", null, true)).block();
        repository.replaceCutEdges(Set.of(new GroupEdge("g-1", "g-2"))).block();

        // when
        repository.clearUniqueMarkers().block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
        assertThat(repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-9", T2).block()).isNull();
        assertThat(repository.findUser("u-1").block()).isNotNull();
        assertThat(repository.findCutEdges().collectList().block()).containsExactly(new GroupEdge("g-1", "g-2"));
    }

    @Test
    @DisplayName("1024바이트 키는 쓸 수 있다 — 포트 계약의 한도가 DynamoDB 정렬키 한도와 같다")
    void 한도_길이의_키를_쓴다() {
        // given — 한글 341자(1023바이트) + 1바이트
        String 키 = "가".repeat(341) + "a";

        // when
        UniqueClaim 처음 = repository.claimUnique(UniqueKind.USER_NAME, 키, "u-1", T1).block();

        // then
        assertThat(처음).isNull();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, 키, "u-2", T1).block())
                .isEqualTo(new UniqueClaim("u-1", T1));
    }
}
```

- [ ] **Step 4: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbUniqueMarkersTest*'`
Expected: 컴파일 실패 — `DynamoDbDirectoryStateRepository` 가 `claimUnique` 등을 구현하지 않았다.

- [ ] **Step 5: 키와 DynamoDB 구현을 쓴다**

`Keys.java` 의 보류 목록 상수(`CUT_EDGE_PREFIX`) 아래에 넣는다. import 에 `dev.starryeye.organization.core.model.UniqueKind` 를 더한다.

```java
    /**
     * 유일 값 표지판 파티션(설계 2026-10-14 §3.1) — 종류마다 하나, 정렬키가 값의 키다. GSI 키가 없어 직원·조직 열거와 어느 인덱스에도 섞이지 않는다.
     * SCIM 쓰기만 만든다.
     */
    public static final String UNIQUE_USERNAME_PK = "UNIQUE#USERNAME";
    public static final String UNIQUE_GROUP_EXTERNAL_ID_PK = "UNIQUE#GROUP_EXTERNAL_ID";

    public static String uniquePk(UniqueKind kind) {
        return switch (kind) {
            case USER_NAME -> UNIQUE_USERNAME_PK;
            case GROUP_EXTERNAL_ID -> UNIQUE_GROUP_EXTERNAL_ID_PK;
        };
    }
```

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkers.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 유일 값 표지판 줄(설계 2026-10-14 §3). 종류마다 파티션 하나({@link Keys#uniquePk})이고 정렬키가 값의 키다 — 키는 core 가 만든다
 * ({@link UniqueKind#key}). GSI 키 속성을 넣지 않아 어느 인덱스에도 실리지 않는다. 쓰기는 모두 조건부라 락이 겹치는 틈에서도 남의 표지판을
 * 덮어쓰지 않는다. {@link DynamoDbDirectoryStateRepository} 가 이것에 맡긴다.
 */
final class DynamoDbUniqueMarkers {

    private static final String OWNER_ID = "ownerId";
    private static final String CLAIMED_AT = "claimedAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    DynamoDbUniqueMarkers(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 없으면 차지한다(빈 값). 있으면 아무것도 쓰지 않고 그 표지판을 돌려준다 — 조건 실패 응답에 실려 온다(ALL_OLD). */
    Mono<UniqueClaim> claim(UniqueKind kind, String key, String ownerId, Instant at) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item(kind, key, ownerId, at))
                        .conditionExpression("attribute_not_exists(#pk)")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                        .build()))
                .then(Mono.<UniqueClaim>empty())
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.just(있던_표지판(error, at)));
    }

    /** 주인이 아직 {@code previousOwnerId} 일 때만 바꾼다. 바꿨으면 참이다. */
    Mono<Boolean> takeOver(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                        .tableName(properties.getTableName())
                        .item(item(kind, key, ownerId, at))
                        .conditionExpression("#owner = :previous")
                        .expressionAttributeNames(Map.of("#owner", OWNER_ID))
                        .expressionAttributeValues(Map.of(":previous", Attrs.s(previousOwnerId)))
                        .build()))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.just(false));
    }

    /** 주인이 {@code ownerId} 일 때만 지운다. 남의 것이거나 없으면 아무것도 하지 않는다. */
    Mono<Void> release(UniqueKind kind, String key, String ownerId) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.uniquePk(kind)), Keys.SK, Attrs.s(key)))
                        .conditionExpression("#owner = :owner")
                        .expressionAttributeNames(Map.of("#owner", OWNER_ID))
                        .expressionAttributeValues(Map.of(":owner", Attrs.s(ownerId)))
                        .build()))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }

    /** 두 파티션을 강한 일관성으로 읽어 묶음으로 지운다 — SCIM wipe 가 조직도를 비운 뒤 부른다. */
    Mono<Void> clear() {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return Flux.fromArray(UniqueKind.values())
                .concatMap(kind -> 정렬키들(Keys.uniquePk(kind))
                        .map(sk -> WriteRequest.builder()
                                .deleteRequest(DeleteRequest.builder()
                                        .key(Map.of(Keys.PK, Attrs.s(Keys.uniquePk(kind)), Keys.SK, Attrs.s(sk)))
                                        .build())
                                .build()))
                .buffer(BatchRequests.WRITE_LIMIT)
                .concatMap(묶음::write)
                .then();
    }

    private Flux<String> 정렬키들(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request).map(item -> Attrs.str(item, Keys.SK));
    }

    private static Map<String, AttributeValue> item(UniqueKind kind, String key, String ownerId, Instant at) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.uniquePk(kind)));
        item.put(Keys.SK, Attrs.s(key));
        item.put(OWNER_ID, Attrs.s(ownerId));
        item.put(CLAIMED_AT, Attrs.n(at.toEpochMilli()));
        return item;
    }

    /** 조건 실패 응답에 실린 표지판. 돌려받지 못했으면 주인을 모르는 새 표지판으로 본다 — 부르는 쪽이 503 으로 판단한다. */
    private static UniqueClaim 있던_표지판(ConditionalCheckFailedException error, Instant at) {
        if (!error.hasItem() || error.item().get(OWNER_ID) == null || error.item().get(CLAIMED_AT) == null) {
            return new UniqueClaim("", at);
        }
        return new UniqueClaim(Attrs.str(error.item(), OWNER_ID),
                Instant.ofEpochMilli(Attrs.longValue(error.item(), CLAIMED_AT)));
    }
}
```

`DynamoDbDirectoryStateRepository.java` 의 `// ---------- 보류 목록 ----------` 절 뒤(`// ---------- 공통 ----------` 앞)에 넣는다. import 에 `UniqueClaim`, `UniqueKind` 를 더한다. `java.time.Instant` 는 이미 있다.

```java
    // ---------- 유일 값 표지판 ----------

    @Override
    public Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at) {
        return 표지판().claim(kind, key, ownerId, at);
    }

    @Override
    public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return 표지판().takeOver(kind, key, ownerId, previousOwnerId, at);
    }

    @Override
    public Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId) {
        return 표지판().release(kind, key, ownerId);
    }

    @Override
    public Mono<Void> clearUniqueMarkers() {
        return 표지판().clear();
    }

    private DynamoDbUniqueMarkers 표지판() {
        return new DynamoDbUniqueMarkers(client, properties);
    }
```

- [ ] **Step 6: 가짜 저장소와 전달 래퍼를 맞춘다**

`FakeStateRepository.java` 의 `cutEdges` 필드 아래에 필드를, `replaceCutEdges` 아래에 메서드를 넣는다. import 에 `UniqueClaim`, `UniqueKind`, `java.time.Instant`, `java.util.EnumMap` 을 더한다. `LinkedHashMap` 은 이미 있다.

```java
    /**
     * 유일 값 표지판(설계 2026-10-14 §3) — 차지·넘겨받기로 적은 것. 직원·조직 지도에 바로 심은 것은 여기 적지 않아도 지금 값의 표지판을
     * 오래전({@link Instant#EPOCH})에 쥔 것으로 본다({@link #표지판}). 실제 저장소에서 SCIM 으로 만든 직원·조직은 늘 자기 값의 표지판을 쥐고 있다.
     */
    public final Map<UniqueKind, Map<String, UniqueClaim>> uniqueMarkers = new EnumMap<>(UniqueKind.class);

    /** 설정하면 {@link #releaseUnique} 가 이 예외로 끝난다 — 놓기 실패를 흉내 낸다. */
    public volatile RuntimeException failReleaseUnique;
```

```java
    @Override
    public Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at) {
        return Mono.fromCallable(() -> {
            UniqueClaim 있던것 = 표지판(kind, key);
            if (있던것 != null) {
                return 있던것;
            }
            적은_표지판(kind).put(key, new UniqueClaim(ownerId, at));
            return null;
        });
    }

    @Override
    public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
        return Mono.fromCallable(() -> {
            UniqueClaim 있던것 = 표지판(kind, key);
            if (있던것 == null || !있던것.ownerId().equals(previousOwnerId)) {
                return false;
            }
            적은_표지판(kind).put(key, new UniqueClaim(ownerId, at));
            return true;
        });
    }

    @Override
    public Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId) {
        return Mono.defer(() -> {
            if (failReleaseUnique != null) {
                return Mono.error(failReleaseUnique);
            }
            UniqueClaim 있던것 = 적은_표지판(kind).get(key);
            if (있던것 != null && 있던것.ownerId().equals(ownerId)) {
                적은_표지판(kind).remove(key);
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> clearUniqueMarkers() {
        return Mono.fromRunnable(uniqueMarkers::clear);
    }

    /** 키의 표지판 — 적은 것이 먼저고, 없으면 지금 그 값을 쥔 직원·조직을 오래된 표지판으로 본다. 없으면 null. */
    private UniqueClaim 표지판(UniqueKind kind, String key) {
        UniqueClaim 적은것 = 적은_표지판(kind).get(key);
        if (적은것 != null) {
            return 적은것;
        }
        return switch (kind) {
            case USER_NAME -> users.values().stream()
                    .filter(user -> user.userName() != null && !user.userName().isBlank()
                            && kind.key(user.userName()).equals(key))
                    .findFirst()
                    .map(user -> new UniqueClaim(user.id(), Instant.EPOCH))
                    .orElse(null);
            case GROUP_EXTERNAL_ID -> groups.values().stream()
                    .filter(group -> group.externalId() != null && !group.externalId().isBlank()
                            && kind.key(group.externalId()).equals(key))
                    .findFirst()
                    .map(group -> new UniqueClaim(group.id(), Instant.EPOCH))
                    .orElse(null);
        };
    }

    private Map<String, UniqueClaim> 적은_표지판(UniqueKind kind) {
        return uniqueMarkers.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
    }
```

`LdapInterruptedSyncScaleTest.java` 의 전달 래퍼(177행 `implements DirectoryStateRepository`)에 다른 전달과 같은 모양으로 넷을 더한다. import 에 `UniqueClaim`, `UniqueKind`, `java.time.Instant` 를 더한다(이미 있으면 그대로).

```java
        @Override public Mono<UniqueClaim> claimUnique(UniqueKind kind, String key, String ownerId, Instant at) {
            return 실제.claimUnique(kind, key, ownerId, at);
        }
        @Override public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
            return 실제.takeOverUnique(kind, key, ownerId, previousOwnerId, at);
        }
        @Override public Mono<Void> releaseUnique(UniqueKind kind, String key, String ownerId) {
            return 실제.releaseUnique(kind, key, ownerId);
        }
        @Override public Mono<Void> clearUniqueMarkers() { return 실제.clearUniqueMarkers(); }
```

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbUniqueMarkersTest*'`
Expected: PASS (8 tests)

Run: `./gradlew :core:test :app-ldap:compileTestJava`
Expected: BUILD SUCCESSFUL. core 테스트는 그대로다 — 유스케이스는 아직 표지판을 쓰지 않는다.

- [ ] **Step 8: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/UniqueKind.java core/src/main/java/dev/starryeye/organization/core/model/UniqueClaim.java core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkers.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbUniqueMarkersTest.java app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -m "feat: 유일 값 표지판 줄(UNIQUE#USERNAME·UNIQUE#GROUP_EXTERNAL_ID)의 조건부 차지·넘겨받기·놓기·비우기를 저장소 포트와 DynamoDB 에 더한다(운영 견고성 ②-a)"
```

---

### Task 2: 표지판 판단(`UniqueMarkers`)과 키 길이 400

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/UniqueMarkers.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/UniqueValueTooLongException.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java` (`toScimError`, `GroupGraphTooLargeException` 분기 아래)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/UniqueMarkersTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimErrorTranslationTest.java`

**Interfaces:**
- Consumes: Task 1 의 `UniqueKind`, `UniqueClaim`, 포트 넷, 가짜 저장소의 `uniqueMarkers`·`failReleaseUnique`.
- Produces (core usecase 패키지, 패키지 전용):
  - `final class UniqueMarkers { UniqueMarkers(DirectoryStateRepository state, Duration 넘겨받기_기준); Mono<Void> 차지한다(UniqueKind 종류, String 값, String 주인); Mono<Void> 놓는다(UniqueKind 종류, String 값, String 주인); Mono<Void> 쥐지_않은_것을_놓는다(UniqueKind 종류, String 주인, Collection<String> 값들); static final Duration 쓰는_중_대기 = 2초 }`
  - `public class UniqueValueTooLongException extends RuntimeException`

- [ ] **Step 1: 판단 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/UniqueMarkersTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 유일 값 표지판의 판단(설계 2026-10-14 §4.1·§4.4) — 가짜 저장소와 가상 시간으로 본다. */
class UniqueMarkersTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private FakeStateRepository state;
    private UniqueMarkers 표지판;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        표지판 = new UniqueMarkers(state, TTL);
    }

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    private static DirectoryUser 직원(String id, String userName) {
        return new DirectoryUser(id, null, userName, userName, null, true);
    }

    private void 적는다(UniqueKind 종류, String 키, String 주인, Instant 시각) {
        state.uniqueMarkers.computeIfAbsent(종류, ignored -> new LinkedHashMap<>()).put(키, new UniqueClaim(주인, 시각));
    }

    private Map<String, UniqueClaim> 적은것(UniqueKind 종류) {
        return state.uniqueMarkers.getOrDefault(종류, Map.of());
    }

    @Test
    @DisplayName("비어 있으면 소문자 키로 차지한다")
    void 비어_있으면_소문자_키로_차지한다() {
        // when
        표지판.차지한다(UniqueKind.USER_NAME, "Kim.Lee", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim.lee");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim.lee").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("주인이 그 값을 쥐고 있으면 409 다 — 대소문자만 달라도 그렇다")
    void 주인이_쥐면_409다() {
        // given
        state.users.put("u-1", 직원("u-1", "kim"));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "KIM", "u-2").block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 userName 을 쓰는 직원이 있습니다: userName=KIM, id=u-1");
    }

    @Test
    @DisplayName("같은 아이디로 다시 와도 주인이 그 값을 쥐고 있으면 409 다 — 생성에는 자기 자신이 없다")
    void 같은_아이디라도_쥐고_있으면_409다() {
        // given
        state.users.put("u-1", 직원("u-1", "kim"));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block())
                .isInstanceOf(DirectoryConflictException.class);
    }

    @Test
    @DisplayName("주인이 나면 새 표지판도 차지한 것으로 본다 — 내가 전에 차지하고 놓지 못한 것이다")
    void 주인이_나면_차지한_것으로_본다() {
        // given — u-1 은 아직 old 이고, 방금(가상 시각 0) 남긴 kim 표지판이 u-1 의 것이다
        VirtualTimeScheduler.getOrSet();
        state.users.put("u-1", 직원("u-1", "old"));
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isEqualTo(new UniqueClaim("u-1", Instant.EPOCH));
    }

    @Test
    @DisplayName("주인이 그 값을 쥐지 않고 락 TTL 이 지난 표지판은 넘겨받는다")
    void 오래된_표지판은_넘겨받는다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        적는다(UniqueKind.USER_NAME, "kim", "ghost", Instant.EPOCH);
        시간.advanceTimeBy(TTL);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isEqualTo(new UniqueClaim("u-2", Instant.EPOCH.plus(TTL)));
    }

    @Test
    @DisplayName("주인이 그 값을 쥐지 않아도 락 TTL 안의 새 표지판이면 503(2초)이다 — 다른 쓰기가 아직 저장하지 않았을 수 있다")
    void 새_표지판은_503이다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        적는다(UniqueKind.USER_NAME, "kim", "in-flight", Instant.EPOCH);
        시간.advanceTimeBy(TTL.minusMillis(1));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block())
                .isInstanceOfSatisfying(TemporaryFailureException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("in-flight");
    }

    @Test
    @DisplayName("주인이 이름을 바꿔 그 값을 쥐지 않는 오래된 표지판도 넘겨받는다")
    void 이름을_바꾼_주인의_표지판을_넘겨받는다() {
        // given — 실제 시계(지금)로 보면 EPOCH 표지판은 오래됐다
        state.users.put("u-1", 직원("u-1", "lee"));
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("넘겨받기가 조건에서 막히면(그 사이 남이 넘겨받았다) 503 이다")
    void 넘겨받기가_막히면_503이다() {
        // given
        state = new FakeStateRepository() {
            @Override
            public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
                return Mono.just(false);
            }
        };
        표지판 = new UniqueMarkers(state, TTL);
        적는다(UniqueKind.USER_NAME, "kim", "ghost", Instant.EPOCH);

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block())
                .isInstanceOf(TemporaryFailureException.class);
    }

    @Test
    @DisplayName("키가 1024바이트를 넘으면 400 이고, 1024바이트는 차지한다")
    void 긴_키는_400이다() {
        // given — 한글 한 자는 3바이트다
        String 한도 = "가".repeat(341) + "a";
        String 넘침 = "가".repeat(341) + "ab";

        // when
        표지판.차지한다(UniqueKind.USER_NAME, 한도, "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys(한도);
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, 넘침, "u-2").block())
                .isInstanceOf(UniqueValueTooLongException.class)
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("값이 없거나 비면 아무것도 하지 않는다")
    void 빈_값은_차지하지_않는다() {
        // when
        표지판.차지한다(UniqueKind.USER_NAME, null, "u-1").block();
        표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, " ", "g-1").block();

        // then
        assertThat(state.uniqueMarkers.values()).allMatch(Map::isEmpty);
    }

    @Test
    @DisplayName("조직 externalId 는 대소문자를 가린다 — 다른 대소문자는 다른 값이다")
    void 조직_externalId_는_대소문자를_가린다() {
        // given
        state.groups.put("g-1", new DirectoryGroup("g-1", "EXT-1", "개발", Set.of()));

        // when
        표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-2").block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).containsOnlyKeys("ext-1");
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, "EXT-1", "g-3").block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=EXT-1, id=g-1");
    }

    @Test
    @DisplayName("정리는 주인이 지금 쥐지 않은 값만 놓는다")
    void 쥐지_않은_것만_놓는다() {
        // given — u-1 은 new 를 쥐고 있다. old·new 표지판이 둘 다 u-1 의 것이다
        state.users.put("u-1", 직원("u-1", "new"));
        적는다(UniqueKind.USER_NAME, "old", "u-1", Instant.EPOCH);
        적는다(UniqueKind.USER_NAME, "new", "u-1", Instant.EPOCH);

        // when
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("Old", "new")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("new");
    }

    @Test
    @DisplayName("주인이 없으면 건드린 값을 모두 놓는다")
    void 주인이_없으면_모두_놓는다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("kim")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).isEmpty();
    }

    @Test
    @DisplayName("남의 표지판은 놓지 않는다")
    void 남의_표지판은_놓지_않는다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-2", Instant.EPOCH);

        // when
        표지판.놓는다(UniqueKind.USER_NAME, "Kim", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("놓기가 실패해도 오류를 내지 않는다 — 경고만 남기고, 남은 표지판은 나중에 넘겨받는다")
    void 놓기_실패는_오류가_아니다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);
        state.failReleaseUnique = new IllegalStateException("놓기 실패(테스트)");

        // when
        표지판.놓는다(UniqueKind.USER_NAME, "kim", "u-1").block();
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("kim")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim");
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*UniqueMarkersTest*'`
Expected: 컴파일 실패 — `UniqueMarkers`, `UniqueValueTooLongException` 이 없다.

- [ ] **Step 3: 예외와 판단을 쓴다**

`core/src/main/java/dev/starryeye/organization/core/usecase/UniqueValueTooLongException.java`:

```java
package dev.starryeye.organization.core.usecase;

/**
 * 유일 값(직원 userName·조직 externalId)이 표지판 키 한도(UTF-8 1024바이트)를 넘는다(설계 2026-10-14 §3.1). 같은 요청은 다시 보내도 늘 넘으므로
 * 호출자는 400 {@code invalidValue}(영구 거절)로 옮긴다 — 500 이면 IdP 가 같은 실패를 되풀이한다.
 */
public class UniqueValueTooLongException extends RuntimeException {

    public UniqueValueTooLongException(String message) {
        super(message);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/usecase/UniqueMarkers.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 유일 값(직원 userName, 조직 externalId) 표지판의 판단(설계 2026-10-14 §4). <b>락 안에서만 부른다.</b> 차지하기, 부딪쳤을 때의 판단, 끝난 뒤의
 * 정리를 한 곳에 둔다 — 두 종류가 같은 규칙을 쓴다. 주인 확인은 본 테이블 강한 읽기다 — GSI 를 보지 않는다.
 */
@Slf4j
final class UniqueMarkers {

    /** 다른 쓰기가 같은 값을 차지하고 아직 저장하지 않았을 수 있을 때의 재시도 대기 — 쓰기 락 경합(2초)과 같다. */
    static final Duration 쓰는_중_대기 = Duration.ofSeconds(2);

    private final DirectoryStateRepository state;
    /** 이만큼 지난 표지판은 주인이 그 값을 쥐지 않으면 넘겨받는다 — 락 TTL 이다(§4.1). */
    private final Duration 넘겨받기_기준;

    UniqueMarkers(DirectoryStateRepository state, Duration 넘겨받기_기준) {
        this.state = state;
        this.넘겨받기_기준 = 넘겨받기_기준;
    }

    /**
     * {@code 주인} 이 {@code 값} 을 쓰도록 표지판을 차지한다(§4.1). 값이 없거나 비면 아무것도 하지 않는다. 남은 표지판의 주인이 그 값을 쥐고 있으면
     * {@link DirectoryConflictException}(409)이고 — 주인이 나여도 그렇다(생성에는 자기 자신이 없다) — 주인이 나면 차지한 것으로 본다. 락 TTL 이
     * 지난 표지판은 넘겨받고, 그 밖이면 {@link TemporaryFailureException}(503)이다. 키가 한도를 넘으면 {@link UniqueValueTooLongException}(400)이다.
     */
    Mono<Void> 차지한다(UniqueKind 종류, String 값, String 주인) {
        if (값 == null || 값.isBlank()) {
            return Mono.empty();
        }
        String 키 = 종류.key(값);
        if (!UniqueKind.fits(키)) {
            return Mono.error(new UniqueValueTooLongException(
                    "%s 이(가) %d바이트를 넘습니다".formatted(이름(종류), UniqueKind.MAX_KEY_BYTES)));
        }
        return Mono.defer(() -> {
            Instant 지금 = 지금();
            return state.claimUnique(종류, 키, 주인, 지금)
                    .flatMap(있던것 -> 판단한다(종류, 값, 키, 주인, 있던것, 지금));
        });
    }

    private Mono<Void> 판단한다(UniqueKind 종류, String 값, String 키, String 주인, UniqueClaim 있던것, Instant 지금) {
        return 쥐고_있다(종류, 있던것.ownerId(), 키).flatMap(쥐었다 -> {
            if (쥐었다) {
                return Mono.<Void>error(new DirectoryConflictException(충돌_문구(종류, 값, 있던것.ownerId())));
            }
            if (있던것.ownerId().equals(주인)) {
                return Mono.<Void>empty();
            }
            if (!있던것.claimedAt().plus(넘겨받기_기준).isAfter(지금)) {
                return state.takeOverUnique(종류, 키, 주인, 있던것.ownerId(), 지금)
                        .flatMap(넘겨받았다 -> 넘겨받았다 ? Mono.<Void>empty() : Mono.<Void>error(쓰는_중(종류)));
            }
            return Mono.<Void>error(쓰는_중(종류));
        });
    }

    /** 주인을 다시 읽지 않고 놓는다 — 깨끗이 끝나 결과를 아는 경우(§4.5). 놓기는 조건부라 남의 것을 지우지 않는다. 실패해도 경고만 남긴다. */
    Mono<Void> 놓는다(UniqueKind 종류, String 값, String 주인) {
        if (값 == null || 값.isBlank()) {
            return Mono.empty();
        }
        return Mono.defer(() -> state.releaseUnique(종류, 종류.key(값), 주인))
                .onErrorResume(error -> {
                    log.warn("{} 표지판을 놓지 못했다 — 주인이 쥐지 않으면 {} 뒤 넘겨받는다: 주인={}", 이름(종류), 넘겨받기_기준, 주인, error);
                    return Mono.empty();
                });
    }

    /**
     * 주인이 지금 쥐고 있지 않은 값만 놓는다 — 깨끗이 끝나지 않아(오류·일부 반영) 무엇이 저장됐는지 모르는 경우(§4.4). 주인을 한 번 강하게 읽는다.
     * 실패해도 경고만 남긴다 — 남은 표지판은 나중에 넘겨받는다.
     */
    Mono<Void> 쥐지_않은_것을_놓는다(UniqueKind 종류, String 주인, Collection<String> 값들) {
        List<String> 볼것 = 값들.stream().filter(값 -> 값 != null && !값.isBlank()).distinct().toList();
        if (볼것.isEmpty()) {
            return Mono.empty();
        }
        return 지금_값(종류, 주인)
                .flatMap(쥔값 -> Flux.fromIterable(볼것)
                        .filter(값 -> 쥔값.map(v -> !종류.key(v).equals(종류.key(값))).orElse(true))
                        .concatMap(값 -> 놓는다(종류, 값, 주인))
                        .then())
                .onErrorResume(error -> {
                    log.warn("{} 표지판을 정리하려고 주인을 읽지 못했다 — 남은 표지판은 {} 뒤 넘겨받는다: 주인={}", 이름(종류), 넘겨받기_기준, 주인, error);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> 쥐고_있다(UniqueKind 종류, String 주인, String 키) {
        return 지금_값(종류, 주인).map(쥔값 -> 쥔값.map(v -> 종류.key(v).equals(키)).orElse(false));
    }

    /** 주인이 지금 쥔 값 — 본 테이블 강한 읽기다. 주인이 없거나 값이 비면 빈 값이다. */
    private Mono<Optional<String>> 지금_값(UniqueKind 종류, String 주인) {
        Mono<String> 값 = switch (종류) {
            case USER_NAME -> state.findUser(주인).mapNotNull(DirectoryUser::userName);
            case GROUP_EXTERNAL_ID -> state.findGroupHeader(주인).mapNotNull(GroupHeader::externalId);
        };
        return 값.filter(v -> !v.isBlank()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    private static String 이름(UniqueKind 종류) {
        return switch (종류) {
            case USER_NAME -> "userName";
            case GROUP_EXTERNAL_ID -> "externalId";
        };
    }

    private static String 충돌_문구(UniqueKind 종류, String 값, String 주인) {
        return switch (종류) {
            case USER_NAME -> "이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s".formatted(값, 주인);
            case GROUP_EXTERNAL_ID -> "이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s".formatted(값, 주인);
        };
    }

    private static TemporaryFailureException 쓰는_중(UniqueKind 종류) {
        return new TemporaryFailureException(
                "다른 쓰기가 같은 %s 을(를) 차지하고 아직 저장하지 않았다".formatted(이름(종류)), 쓰는_중_대기);
    }

    /** 스케줄러 시계 — 쓰기 락 백오프와 같은 시계라 가상 시간으로 시험한다({@link AcquireBackoff#지금}). */
    private static Instant 지금() {
        return Instant.ofEpochMilli(Schedulers.parallel().now(TimeUnit.MILLISECONDS));
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*UniqueMarkersTest*'`
Expected: PASS (15 tests)

- [ ] **Step 5: 라우터 번역 테스트를 쓰고 실패를 확인한다**

`ScimErrorTranslationTest.java` 에 넣는다. import 에 `dev.starryeye.organization.core.usecase.UniqueValueTooLongException` 을 더한다.

```java
    @Test
    @DisplayName("유일 값이 표지판 키 한도를 넘으면 400 invalidValue 다 — 다시 보내도 늘 넘으므로 Retry-After 가 없다")
    void 긴_유일_값은_400이다() {
        // given
        var 예외 = new UniqueValueTooLongException("userName 이(가) 1024바이트를 넘습니다");

        // when, then
        번역한다(예외)
                .expectStatus().isEqualTo(400)
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").isEqualTo("userName 이(가) 1024바이트를 넘습니다");
    }
```

Run: `./gradlew :connector-scim:test --tests '*ScimErrorTranslationTest*'`
Expected: FAIL — 지금은 500 이다.

- [ ] **Step 6: 라우터에 분기를 넣는다**

`ScimRouter.toScimError` 의 `GroupGraphTooLargeException` 분기 바로 아래에 넣는다. import 에 `dev.starryeye.organization.core.usecase.UniqueValueTooLongException` 을 더한다.

```java
        // 유일 값이 표지판 키 한도를 넘는다 — 같은 요청은 다시 보내도 늘 넘으므로 영구 거절(설계 2026-10-14 §3.1)
        if (error instanceof UniqueValueTooLongException tooLong) {
            return write(HttpStatus.BAD_REQUEST, "invalidValue", tooLong.getMessage(), null);
        }
```

Run: `./gradlew :connector-scim:test --tests '*ScimErrorTranslationTest*'`
Expected: PASS

- [ ] **Step 7: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/UniqueMarkers.java core/src/main/java/dev/starryeye/organization/core/usecase/UniqueValueTooLongException.java core/src/test/java/dev/starryeye/organization/core/usecase/UniqueMarkersTest.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimErrorTranslationTest.java
git commit -m "feat: 유일 값 표지판의 판단 — 주인이 쥐면 409, 주인이 나면 차지, 락 TTL 이 지나면 넘겨받기, 그 밖은 503(2초), 키 1024바이트 초과는 400 invalidValue(운영 견고성 ②-a)"
```

---

### Task 3: 유스케이스가 표지판으로 유일성을 지킨다 (GSI 이름 조회 없앰)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
  - 필드·생성자, `createUser`, `changeUser`, `userName을_확인한다`(삭제), `removeUserInternal`, `createGroup`, `externalId를_확인한다`(삭제), `changeGroupInternal`, `removeGroupInternal`, 도우미 셋
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` (`findUserIdsByUserName` 삭제, `findGroupIdsByExternalId` 자바독)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`findUserIdsByUserName` 삭제)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`findUserIdsByUserName` 삭제)
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java` (`userName_은_대소문자를_가리지_않는다` 삭제)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (`findUserIdsByUserName` 전달 삭제)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java` (`dynamoDb.getLockTtl()` 을 넘김)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncWriteDecisionTest.java` (GSI 후보 테스트 셋 → 낡은 표지판 테스트 셋)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUniqueMarkerTest.java`

**Interfaces:**
- Consumes: Task 2 의 `UniqueMarkers`(`차지한다`·`놓는다`·`쥐지_않은_것을_놓는다`), Task 1 의 포트·가짜 저장소.
- Produces:
  - `IncrementalSyncUseCase` 11인자 생성자. 마지막 인자는 `Duration lockTtl` 이다.
  - 10인자 생성자는 `DEFAULT_LOCK_TTL`(30초)로 위임한다. 7·8인자 생성자는 그대로다.

- [ ] **Step 1: 유스케이스 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUniqueMarkerTest.java`:

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
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCIM 입구가 유일 값 표지판을 차지하고 정리한다(설계 2026-10-14 §4.2~§4.5). */
class IncrementalSyncUniqueMarkerTest {

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
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock,
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    private static DirectoryUser 직원(String id, String userName) {
        return new DirectoryUser(id, null, userName, userName, null, true);
    }

    private Map<String, UniqueClaim> 적은것(UniqueKind 종류) {
        return state.uniqueMarkers.getOrDefault(종류, Map.of());
    }

    private void 적는다(UniqueKind 종류, String 키, String 주인, Instant 시각) {
        state.uniqueMarkers.computeIfAbsent(종류, ignored -> new LinkedHashMap<>()).put(키, new UniqueClaim(주인, 시각));
    }

    /** DEV001 이 아직 없는 직원 u-1 을 멤버로 적어 두었다 — u-1 을 만들면 튜플이 생긴다. */
    private void u1이_든_조직을_심는다() {
        state.groups.put("DEV001", new DirectoryGroup("DEV001", null, "개발", Set.of(MemberRef.user("u-1"))));
    }

    // ---------- 직원 ----------

    @Test
    @DisplayName("생성은 userName 의 소문자 키로 표지판을 차지한다")
    void 생성은_표지판을_차지한다() {
        // when
        useCase.createUser(직원("u-1", "Kim.Lee")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim.lee");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim.lee").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("응답을 잃은 POST 를 IdP 가 다시 보내면(새 UUID) 409 이고 직원은 하나다")
    void 다시_보낸_POST_는_409다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();

        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("u-2", "kim")).block())
                .isInstanceOf(DirectoryConflictException.class);
        assertThat(state.users).containsOnlyKeys("u-1");
    }

    @Test
    @DisplayName("다른 쓰기가 차지하고 아직 저장하지 않은 새 표지판이면 503 이고 아무것도 쓰지 않는다")
    void 새_표지판의_주인이_아직_저장하지_않았으면_503이다() {
        // given — 지금(실제 시계) 차지한 표지판, 주인 META 는 아직 없다
        적는다(UniqueKind.USER_NAME, "kim", "in-flight", Instant.now());

        // when, then
        assertThatThrownBy(() -> useCase.createUser(직원("u-2", "kim")).block())
                .isInstanceOf(TemporaryFailureException.class);
        assertThat(state.users).isEmpty();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("in-flight");
    }

    @Test
    @DisplayName("생성이 일부 실패로 커밋을 건너뛰면 표지판을 놓아, 재시도가 곧바로 통과한다")
    void 생성이_일부_실패하면_표지판을_놓아_재시도가_곧_통과한다() {
        // given
        u1이_든_조직을_심는다();
        writer.failFor(tuple -> true);

        // when
        var 처음 = useCase.createUser(직원("u-1", "kim")).block();

        // then
        assertThat(처음.fullyApplied()).isFalse();
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");

        // when — IdP 의 재시도는 새 UUID 다
        writer.failFor(tuple -> false);
        var 다시 = useCase.createUser(직원("u-2", "kim")).block();

        // then
        assertThat(다시.fullyApplied()).isTrue();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("생성이 오류로 끝나도 표지판을 놓는다")
    void 생성이_오류로_끝나도_표지판을_놓는다() {
        // given
        u1이_든_조직을_심는다();
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.createUser(직원("u-1", "kim")).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");
    }

    @Test
    @DisplayName("이름을 바꾸면 새 이름을 차지하고 커밋 뒤 옛 이름을 놓아, 남이 곧 옛 이름을 쓴다")
    void 개명은_옛_표지판을_놓는다() {
        // given
        useCase.createUser(직원("u-1", "old")).block();

        // when
        useCase.changeUser("u-1", u -> u.withUserName("new")).block();
        useCase.createUser(직원("u-2", "old")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("new").ownerId()).isEqualTo("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("old").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("Kim 에서 kim 처럼 대소문자만 바꾸면 표지판을 건드리지 않고 자기 자신과 부딪치지 않는다")
    void 대소문자만_바꾸면_표지판을_건드리지_않는다() {
        // given
        useCase.createUser(직원("u-1", "Kim")).block();
        UniqueClaim 전 = 적은것(UniqueKind.USER_NAME).get("kim");

        // when
        useCase.changeUser("u-1", u -> u.withUserName("kim")).block();

        // then
        assertThat(state.users.get("u-1").userName()).isEqualTo("kim");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isSameAs(전);
    }

    @Test
    @DisplayName("비활성화처럼 이름을 안 바꾸는 변경은 표지판을 건드리지 않는다")
    void 비활성화는_표지판을_건드리지_않는다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();
        UniqueClaim 전 = 적은것(UniqueKind.USER_NAME).get("kim");

        // when
        useCase.changeUser("u-1", u -> u.withActive(false)).block();

        // then
        assertThat(state.users.get("u-1").active()).isFalse();
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isSameAs(전);
    }

    @Test
    @DisplayName("개명이 오류로 끝나면 새 표지판만 놓고 옛 표지판은 남긴다 — META 는 옛 이름이다")
    void 개명이_오류로_끝나면_새_표지판만_놓는다() {
        // given — u-1 은 DEV001 의 멤버다. 이름을 바꾸며 비활성화하면 튜플을 지운다
        state.groups.put("DEV001", new DirectoryGroup("DEV001", null, "개발", Set.of(MemberRef.user("u-1"))));
        useCase.createUser(직원("u-1", "old")).block();
        writer.abortWhen(delta -> true);

        // when
        assertThatThrownBy(() -> useCase.changeUser("u-1", u -> u.withUserName("new").withActive(false)).block())
                .isInstanceOf(TupleWriteAbortedException.class);

        // then
        assertThat(state.users.get("u-1").userName()).isEqualTo("old");
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("old");
    }

    @Test
    @DisplayName("커밋 뒤 옛 표지판을 놓다가 실패해도 요청은 성공이다")
    void 놓기가_실패해도_요청은_성공이다() {
        // given
        useCase.createUser(직원("u-1", "old")).block();
        state.failReleaseUnique = new IllegalStateException("놓기 실패(테스트)");

        // when
        var result = useCase.changeUser("u-1", u -> u.withUserName("new")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users.get("u-1").userName()).isEqualTo("new");
        assertThat(적은것(UniqueKind.USER_NAME).get("old").ownerId()).as("남은 표지판은 나중에 넘겨받는다").isEqualTo("u-1");
    }

    @Test
    @DisplayName("직원을 지우면 표지판을 놓는다")
    void 삭제는_표지판을_놓는다() {
        // given
        useCase.createUser(직원("u-1", "kim")).block();

        // when
        useCase.removeUser("u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).doesNotContainKey("kim");
    }

    @Test
    @DisplayName("직원 삭제가 일부 실패해 직원이 남으면 표지판도 남긴다")
    void 삭제가_일부_실패하면_표지판을_남긴다() {
        // given
        u1이_든_조직을_심는다();
        useCase.createUser(직원("u-1", "kim")).block();
        writer.failFor(tuple -> true);

        // when
        useCase.removeUser("u-1").block();

        // then
        assertThat(state.users).containsKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("도중에 리스를 잃어 일이 취소되면 표지판을 놓지 않는다 — 락 없이 쓰지 않는다")
    void 리스를_잃어_취소되면_표지판을_놓지_않는다() {
        // given — Check 가 느린 사이 갱신(50ms 주기)이 실패한다
        u1이_든_조직을_심는다();
        checker.delayBy(tuple -> Duration.ofMillis(300));
        lock.failRenew = true;
        IncrementalSyncUseCase 짧은_갱신 = new IncrementalSyncUseCase(state, writer, checker, lock,
                Duration.ZERO, Duration.ofMillis(50), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        // when
        assertThatThrownBy(() -> 짧은_갱신.createUser(직원("u-1", "kim")).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(state.users).doesNotContainKey("u-1");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).as("락 TTL 뒤 넘겨받는다").isEqualTo("u-1");
    }

    // ---------- 조직 ----------

    @Test
    @DisplayName("조직 생성은 externalId 를 차지하고, 같은 externalId 의 두 번째 생성은 409 다")
    void 조직_생성은_표지판을_차지한다() {
        // when
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID).get("EXT-1").ownerId()).isEqualTo("g-1");
        assertThatThrownBy(() -> useCase.createGroup(new DirectoryGroup("g-2", "EXT-1", "개발2", Set.of())).block())
                .isInstanceOf(DirectoryConflictException.class);
    }

    @Test
    @DisplayName("조직 PATCH 로 externalId 를 비우면 커밋 뒤 옛 표지판을 놓는다 — 빈 값은 차지하지 않는다")
    void 조직_externalId_를_비우면_옛_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.delta().reidentified(null)).block();

        // then
        assertThat(state.groups.get("g-1").externalId()).isNull();
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).isEmpty();
    }

    @Test
    @DisplayName("조직 externalId 를 바꾸면 새 값을 차지하고 커밋 뒤 옛 값을 놓는다")
    void 조직_externalId_변경은_옛_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.changeGroup("g-1", GroupChange.replacement("EXT-2", "개발", Set.of())).block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).containsOnlyKeys("EXT-2");
    }

    @Test
    @DisplayName("조직을 지우면 표지판을 놓는다")
    void 조직_삭제는_표지판을_놓는다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "EXT-1", "개발", Set.of())).block();

        // when
        useCase.removeGroup("g-1").block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).isEmpty();
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*IncrementalSyncUniqueMarkerTest*'`
Expected: FAIL. 유스케이스가 아직 표지판을 쓰지 않아 아래 테스트들이 실패한다.
- `생성은_표지판을_차지한다`·`개명은_옛_표지판을_놓는다` 등: 적은 표지판이 비어 있다.
- `새_표지판의_주인이_아직_저장하지_않았으면_503이다`: 지금은 통과(201)한다.
- `조직_생성은_표지판을_차지한다`.

다음 테스트들은 고치기 전에도 통과할 수 있다. Review Focus 를 고정하는 테스트다.
- `생성이_오류로_끝나도_표지판을_놓는다`
- `리스를_잃어_취소되면_표지판을_놓지_않는다`

- [ ] **Step 3: 유스케이스를 바꾼다**

`IncrementalSyncUseCase.java`:

(a) `DEFAULT_RENEW_INTERVAL` 아래에 상수를, `traceIds` 필드 아래에 필드를 넣는다. 이 필드가 마지막 final 필드가 되어, Lombok 생성자가 11인자(…, `audit`, `traceIds`, `lockTtl`)가 된다.

```java
    /** 표지판 넘겨받기 기준을 넘기지 않는 생성자가 쓰는 값 — 운영 결선은 {@code dynamodb.lock-ttl} 을 넘긴다(설계 2026-10-14 §4.1). */
    static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);
```

```java
    /**
     * 유일 값 표지판을 넘겨받는 기준 — 락 TTL 이다(설계 2026-10-14 §4.1). 이보다 오래된 표지판은 주인이 그 값을 쥐지 않으면 넘겨받는다.
     */
    private final Duration lockTtl;
```

(b) 8인자 생성자 아래에 10인자 생성자를 더한다. 7·8인자 생성자 자바독의 "10인자 생성자" 는 "11인자 생성자" 로 고친다.

```java
    /** 표지판 넘겨받기 기준을 {@link #DEFAULT_LOCK_TTL} 로 둔다 — 테스트용. 운영 결선은 11인자 생성자로 {@code dynamodb.lock-ttl} 을 넘긴다. */
    public IncrementalSyncUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleChecker checker,
                                  MutationLock lock, Duration acquireTimeout, Duration renewInterval,
                                  DriftObserver driftObserver, LockObserver lockObserver,
                                  AuditRecorder audit, TraceIds traceIds) {
        this(state, writer, checker, lock, acquireTimeout, renewInterval, driftObserver, lockObserver, audit, traceIds,
                DEFAULT_LOCK_TTL);
    }
```

(c) `createUser` 를 바꾸고 `userName을_확인한다` 를 지운다.

```java
    /**
     * 직원 생성(POST). {@code userName} 의 표지판을 <b>락 안에서, OpenFGA 쓰기 전에</b> 차지한다(설계 2026-10-14 §4.2) — 겹치면 409, 다른 쓰기가
     * 아직 저장하지 않은 값이면 503 이다. 아이디는 서버가 발급한 UUID 라 겹칠 일이 없어 보지 않는다(설계 2026-10-04 §3.2) — 지운 직원의 아이디가
     * 다시 쓰이지 않아 남은 권한을 물려받지 않는다(점검 M7).
     *
     * <p>생성에는 "자기 자신"이 없다 — 표지판의 주인이 이 아이디여도 그 직원이 그 이름을 쥐고 있으면 409 다(같은 아이디로 다시 온 POST 가 기존 직원을
     * 덮어쓰지 않는다). 끝나면 표지판을 정리한다({@link #표지판을_정리한다}).
     */
    public Mono<IncrementalSyncResult> createUser(DirectoryUser user) {
        return withLock((lease, 기록) -> 표지판().차지한다(UniqueKind.USER_NAME, user.userName(), user.id())
                .then(Mono.defer(() -> 표지판을_정리한다(upsertUserInternal(user, Optional.empty(), lease, 기록),
                        UniqueKind.USER_NAME, user.id(), List.of(), 값들(user.userName())))));
    }
```

(d) `changeUser` 를 바꾼다. 자바독의 마지막 문장은 "{@code userName} 의 키(소문자)가 바뀌면 새 이름의 표지판을 차지하고, 커밋 뒤 옛 것을 놓는다(설계 2026-10-14 §4.2)." 로 고친다.

```java
    public Mono<IncrementalSyncResult> changeUser(String userId, UnaryOperator<DirectoryUser> 계산) {
        return withLock((lease, 기록) -> state.findUser(userId)
                .flatMap(before -> {
                    DirectoryUser after = 계산.apply(before).withId(userId);
                    Mono<IncrementalSyncResult> 일 = Mono.defer(() -> upsertUserInternal(after, Optional.of(before), lease, 기록));
                    if (!키가_다르다(UniqueKind.USER_NAME, before.userName(), after.userName())) {
                        return 일;
                    }
                    return 표지판().차지한다(UniqueKind.USER_NAME, after.userName(), userId)
                            .then(Mono.defer(() -> 표지판을_정리한다(일, UniqueKind.USER_NAME, userId,
                                    값들(before.userName()), 값들(before.userName(), after.userName()))));
                }));
    }
```

(e) `removeUserInternal` 의 마지막 `return diffAndApply(...)` 를 감싼다.

```java
                    return 표지판을_정리한다(
                            diffAndApply(before, after, RelationTuple.userRef(userId), Set.of(), lease, 기록, commit),
                            UniqueKind.USER_NAME, userId, 값들(user.userName()), 값들(user.userName()));
```

(f) `createGroup` 을 바꾸고 `externalId를_확인한다` 를 지운다. 자바독의 "다른 조직과 겹치는지 락 안에서 확인한다(설계 2026-10-04 §3.2)" 는 "표지판을 락 안에서 차지한다(설계 2026-10-14 §4.3)" 로 고친다.

```java
    public Mono<IncrementalSyncResult> createGroup(DirectoryGroup group) {
        return withLock((lease, 기록) -> 표지판().차지한다(UniqueKind.GROUP_EXTERNAL_ID, group.externalId(), group.id())
                .then(Mono.defer(() -> 표지판을_정리한다(upsertGroupInternal(group, lease, 기록),
                        UniqueKind.GROUP_EXTERNAL_ID, group.id(), List.of(), 값들(group.externalId())))));
    }
```

(g) `changeGroupInternal` 의 `확인` 과 그 뒤를 바꾼다. `return 확인.then(Mono.defer(() -> 바뀌는_멤버(...)...)))` 덩어리를 아래로 바꾼다. 안쪽 본문(`바뀌는_멤버` 부터 `보류를_다시_본다` 까지)은 한 글자도 바꾸지 않고 `일` 로 옮긴다.

```java
                    GroupHeader 바뀐헤더 = change.applyTo(header);
                    Mono<IncrementalSyncResult> 일 = Mono.defer(() -> 바뀌는_멤버(groupId, change).flatMap(전후 -> {
                        // … 지금 본문 그대로 …
                    }));
                    if (!키가_다르다(UniqueKind.GROUP_EXTERNAL_ID, header.externalId(), 바뀐헤더.externalId())) {
                        return 일;
                    }
                    return 표지판().차지한다(UniqueKind.GROUP_EXTERNAL_ID, 바뀐헤더.externalId(), groupId)
                            .then(Mono.defer(() -> 표지판을_정리한다(일, UniqueKind.GROUP_EXTERNAL_ID, groupId,
                                    값들(header.externalId()), 값들(header.externalId(), 바뀐헤더.externalId()))));
```

`changeGroup` 자바독의 마지막 문단도 고친다: "{@code externalId} 가 바뀌면 새 값의 표지판을 먼저 차지하고(겹치면 아무것도 쓰지 않고 409), 커밋 뒤 옛 것을 놓는다(설계 2026-10-14 §4.3)."

(h) `removeGroupInternal` 의 `return 반영하고_커밋한다(...).flatMap(...)` 를 감싼다.

```java
                            return 표지판을_정리한다(
                                    반영하고_커밋한다(TupleDelta.deleteOnly(조직을_언급하는_튜플(group, parentIds)), lease, 기록,
                                            result -> 조직_삭제를_커밋한다(group, parentIds, result, 기록))
                                            .flatMap(result -> 연결을_지운다
                                                    ? 보류를_다시_본다(lease, 기록).thenReturn(result)
                                                    : Mono.just(result)),
                                    UniqueKind.GROUP_EXTERNAL_ID, groupId, 값들(group.externalId()), 값들(group.externalId()));
```

(i) `// ---------- 공통 ----------` 아래, `Commit` 인터페이스 앞에 도우미 넷을 넣는다. import 에 `dev.starryeye.organization.core.model.UniqueKind`, `java.util.Arrays`, `java.util.List` 를 더한다(이미 있으면 그대로).

```java
    private UniqueMarkers 표지판() {
        return new UniqueMarkers(state, lockTtl);
    }

    /**
     * 표지판을 차지한(또는 놓을) 일이 끝나면 정리한다(설계 2026-10-14 §4.4·§4.5). 값을 내고 전부 반영됐으면(깨끗이) 결과를 알므로
     * {@code 깨끗하면_놓을것} 을 다시 읽지 않고 놓는다. 그 밖(오류·일부 반영·빈 끝)이면 주인을 한 번 읽어 {@code 건드린것} 중 주인이 쥐지 않은 것만
     * 놓는다. 정리는 결과를 바꾸지 않는다. 리스를 잃어 일이 취소되면 여기까지 오지 않는다 — 락 없이 더 쓰지 않는다. 남은 표지판은 락 TTL 뒤 넘겨받는다.
     */
    private Mono<IncrementalSyncResult> 표지판을_정리한다(Mono<IncrementalSyncResult> 일, UniqueKind 종류, String 주인,
                                                     List<String> 깨끗하면_놓을것, List<String> 건드린것) {
        return 일.materialize().flatMap(끝 -> {
            UniqueMarkers 표지판 = 표지판();
            Mono<Void> 정리 = 끝.isOnNext() && 끝.get().fullyApplied()
                    ? Flux.fromIterable(깨끗하면_놓을것).concatMap(값 -> 표지판.놓는다(종류, 값, 주인)).then()
                    : 표지판.쥐지_않은_것을_놓는다(종류, 주인, 건드린것);
            return 정리.thenReturn(끝);
        }).<IncrementalSyncResult>dematerialize();
    }

    /** null 을 뺀 값 목록 — 표지판 정리에 넘긴다. */
    private static List<String> 값들(String... 값) {
        return Arrays.stream(값).filter(Objects::nonNull).toList();
    }

    /** 두 값이 다른 표지판 키인가 — 빈 값은 표지판이 없다. */
    private static boolean 키가_다르다(UniqueKind 종류, String 전, String 후) {
        String 전키 = 전 == null || 전.isBlank() ? null : 종류.key(전);
        String 후키 = 후 == null || 후.isBlank() ? null : 종류.key(후);
        return !Objects.equals(전키, 후키);
    }
```

- [ ] **Step 4: GSI 이름 조회를 없애고 결선을 바꾼다**

- `DirectoryStateRepository.java`:
  - `findUserIdsByUserName` 과 그 자바독을 지운다.
  - `findGroupIdsByExternalId` 자바독을 `/** 같은 것을 조직으로. 관리 API 가 쓴다 — SCIM 의 중복 판정은 표지판이다(설계 2026-10-14). */` 로 고친다.
- `FakeStateRepository.java`, `DynamoDbDirectoryStateRepository.java`: `findUserIdsByUserName` 을 지운다.
- `LdapInterruptedSyncScaleTest.java`: `findUserIdsByUserName` 전달을 지운다.
- `DynamoDbDirectoryStateRepositoryTest.java`: `userName_은_대소문자를_가리지_않는다` 테스트를 지운다.
- `ScimUseCaseConfig.java` 의 `incrementalSyncUseCase` 를 바꾼다:

```java
        return new IncrementalSyncUseCase(state, writer, checker, lock,
                dynamoDb.getLockAcquireTimeout(), dynamoDb.getLockRenewInterval(), metrics, metrics,
                auditRecorder, traceIds, dynamoDb.getLockTtl());
```

- [ ] **Step 5: 기존 테스트를 맞춘다**

`WriteDecisionLockInvariantTest.java` 의 `감시하는_저장소`:
- `findUserIdsByUserName` 줄을 지운다.
- 아래 둘을 더한다. import 에 `UniqueClaim`, `UniqueKind`, `java.time.Instant` 를 더한다.

```java
        @Override public Mono<UniqueClaim> claimUnique(UniqueKind k, String key, String owner, Instant at) { return 본다("claimUnique " + key, () -> super.claimUnique(k, key, owner, at)); }
        @Override public Mono<Boolean> takeOverUnique(UniqueKind k, String key, String owner, String prev, Instant at) { return 본다("takeOverUnique " + key, () -> super.takeOverUnique(k, key, owner, prev, at)); }
```

`직원_입구` 의 then 에 한 줄, `조직_입구` 의 then 에 두 줄을 더한다.

```java
        assertThat(모든읽기).as("중복 판단은 표지판이다 — GSI 를 보지 않는다").anyMatch(읽기 -> 읽기.startsWith("claimUnique "));
```

```java
        assertThat(모든읽기).as("중복 판단은 표지판이다 — GSI 를 보지 않는다").anyMatch(읽기 -> 읽기.startsWith("claimUnique "));
        assertThat(모든읽기).noneMatch(읽기 -> 읽기.startsWith("findGroupIdsByExternalId "));
```

`IncrementalSyncWriteDecisionTest.java`:
- GSI 후보 테스트 셋(`GSI에만_남은_후보는_무시한다`, `GSI3에만_남은_후보는_조직_생성에서_무시한다`, `GSI3에만_남은_후보는_조직_변경에서_무시한다`)과 도우미 `GSI3에_옛_조직이_남게_준비한다` 를 지운다. 그 자리에 아래 셋을 넣는다.
- 기존 도우미 `준비한다(FakeStateRepository)` 를 그대로 쓴다(kim·lee·DEV001 을 심는다).
- import 에 `UniqueClaim`, `UniqueKind`, `java.time.Instant`, `java.util.LinkedHashMap` 을 더한다. 쓰지 않게 된 `Flux` import 는 지운다.

```java
    /** 낡은 표지판 — 오래전(EPOCH)에 남아 있다. 실제 시계로 보면 락 TTL 이 지났다. */
    private void 낡은_표지판을_적는다(UniqueKind 종류, String 키, String 주인) {
        state.uniqueMarkers.computeIfAbsent(종류, ignored -> new LinkedHashMap<>()).put(키, new UniqueClaim(주인, Instant.EPOCH));
    }

    @Test
    @DisplayName("주인이 지워진 낡은 표지판은 넘겨받는다 — 그 이름으로 바꿀 수 있다(설계 2026-10-14 §4.1)")
    void 낡은_표지판은_넘겨받는다() {
        // given
        준비한다(new FakeStateRepository());
        낡은_표지판을_적는다(UniqueKind.USER_NAME, "park", "ghost");

        // when
        useCase.changeUser("kim", u -> u.withUserName("park")).block();

        // then
        assertThat(state.users.get("kim").userName()).isEqualTo("park");
        assertThat(state.uniqueMarkers.get(UniqueKind.USER_NAME).get("park").ownerId()).isEqualTo("kim");
    }

    @Test
    @DisplayName("조직 생성 — 주인이 지워진 낡은 externalId 표지판은 넘겨받는다")
    void 낡은_조직_표지판은_생성에서_넘겨받는다() {
        // given
        준비한다(new FakeStateRepository());
        낡은_표지판을_적는다(UniqueKind.GROUP_EXTERNAL_ID, "cn=NEW", "ghost");

        // when
        var result = useCase.createGroup(new DirectoryGroup("NEW", "cn=NEW", "새 조직", Set.of())).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.groups.get("NEW").externalId()).isEqualTo("cn=NEW");
    }

    @Test
    @DisplayName("조직 변경 — externalId 를 바꿔 그 값을 쥐지 않는 주인의 낡은 표지판은 넘겨받는다")
    void 낡은_조직_표지판은_변경에서_넘겨받는다() {
        // given — DEV001 은 지금 cn=DEV001 이다. 옛 값 cn=MOVED 의 표지판이 남아 있다
        준비한다(new FakeStateRepository());
        낡은_표지판을_적는다(UniqueKind.GROUP_EXTERNAL_ID, "cn=MOVED", "DEV001");
        state.groups.put("DEV002", new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", Set.of()));

        // when
        useCase.changeGroup("DEV002", GroupChange.replacement("cn=MOVED", "백엔드팀", Set.of())).block();

        // then
        assertThat(state.groups.get("DEV002").externalId()).isEqualTo("cn=MOVED");
    }
```

- [ ] **Step 6: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL.
- `IncrementalSyncUniqueMarkerTest` 17개가 통과한다.
- 기존 `IncrementalSyncCreateUniquenessTest` 등이 그대로 통과한다. 가짜 저장소의 지도에 심은 직원·조직은 자기 값의 표지판을 쥔 것으로 보기 때문이다.

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*' :app-scim:compileJava :app-ldap:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUniqueMarkerTest.java core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncWriteDecisionTest.java
git commit -m "fix: SCIM 쓰기가 userName·조직 externalId 표지판을 락 안에서 OpenFGA 쓰기 전에 차지하고 끝나면 정리한다 — GSI 이름 조회를 없애 방금 만든 직원·조직과도 겹치지 않는다(운영 견고성 ②-a)"
```

---

### Task 4: wipe 가 표지판을 비운다, 끝단 테스트, 문서

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java` (`wipe`)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteRaceEndToEndTest.java` (테스트 하나 + 주석 둘)
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-28-scim-write-lock-design.md` (§10)
- Modify: `docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md` (§11)

**Interfaces:**
- Consumes: Task 1 의 `clearUniqueMarkers()`, 가짜 저장소의 `uniqueMarkers`.

- [ ] **Step 1: wipe 테스트를 쓰고 실패를 확인한다**

`ScimRebuildUseCaseTest.java` 의 `wipe는_보류_목록을_비운다` 아래에 넣는다. import 에 `UniqueClaim`, `UniqueKind`, `java.util.LinkedHashMap` 을 더한다(이미 있으면 그대로).

```java
    @Test
    @DisplayName("wipe 는 유일 값 표지판도 비운다 — 조직도가 비었으니 쥘 값도 없다(설계 2026-10-14 §5)")
    void wipe는_표지판을_비운다() {
        // given
        조직도를_심는다();
        state.uniqueMarkers.computeIfAbsent(UniqueKind.USER_NAME, ignored -> new LinkedHashMap<>())
                .put("kim", new UniqueClaim("kim", NOW));
        state.uniqueMarkers.computeIfAbsent(UniqueKind.GROUP_EXTERNAL_ID, ignored -> new LinkedHashMap<>())
                .put("cn=DEV002", new UniqueClaim("DEV002", NOW));

        // when
        재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(state.uniqueMarkers).isEmpty();
    }
```

Run: `./gradlew :core:test --tests '*ScimRebuildUseCaseTest*'`
Expected: FAIL — 표지판이 남아 있다.

- [ ] **Step 2: wipe 를 바꾼다**

`ScimRebuildUseCase.wipe` 의 사슬을 바꾼다. `남은_대상을_지운다` 다음, `replaceCutEdges` 앞에 한 줄을 넣는다.

```java
            return state.replaceWith(DirectorySnapshot.empty())
                    // GSI 열거에서 빠져 남은 대상을 장부로 찾아 지운다(설계 2026-10-13 §4.4)
                    .flatMap(changes -> 남은_대상을_지운다(changes, result.deleted()))
                    // 표지판도 비운다 — 조직도가 비었으니 쥘 값도 없다(설계 2026-10-14 §5)
                    .flatMap(changes -> state.clearUniqueMarkers().thenReturn(changes))
                    .flatMap(changes -> state.replaceCutEdges(Set.of()).thenReturn(changes))
```

그 자리 위의 주석 "조직도와 함께 보류 목록도 비운다 — …" 는 그대로 둔다.

Run: `./gradlew :core:test --tests '*ScimRebuildUseCaseTest*'`
Expected: PASS

- [ ] **Step 3: 끝단 테스트를 더하고 주석을 고친다**

`ScimWriteRaceEndToEndTest.java` 에 넣는다. 위치는 `대소문자만_다른_생성` 앞이고, 클래스의 `보낸다`·`직원본문` 도우미와 `client`(WebTestClient)를 쓴다.

```java
    @Test
    @DisplayName("같은 본문의 POST 를 잇달아 두 번 보내면(응답을 잃은 재시도) 두 번째는 409 이고 직원은 하나다")
    void 같은_POST_를_두_번_보내면_두_번째는_409다() {
        // given
        String 본문 = 직원본문("retry.kim");

        // when
        int 처음 = 보낸다(HttpMethod.POST, "/scim/v2/Users", 본문);
        int 다시 = 보낸다(HttpMethod.POST, "/scim/v2/Users", 본문);

        // then
        assertThat(처음).isEqualTo(201);
        assertThat(다시).isEqualTo(409);
        client.get().uri("/scim/v2/Users?filter={f}", "userName eq \"retry.kim\"")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.totalResults").isEqualTo(1);
    }
```

`대소문자만_다른_생성` 위 자바독의 둘째·셋째 문단("DynamoDB Local 의 GSI 는 즉시 반영되므로 … 수용한 한계다(…)")을 아래로 바꾼다.

```java
     * <p>유일성은 표지판 줄의 조건부 쓰기로 지킨다(설계 2026-10-14) — GSI 를 보지 않으므로 실제 DynamoDB 에서도 두 번째 POST 가 첫 번째의
     * GSI 반영을 기다리지 않고 409 다.
```

`같은 조직을 동시에 만들면 …` 테스트 위 자바독에도 GSI 지연을 말하는 문장이 있다(275~279행 근처). 같은 뜻으로 바꾼다: "externalId 유일성도 표지판 줄로 지킨다(설계 2026-10-14) — GSI 지연과 상관없다."

Run: `./gradlew :app-scim:test --tests '*ScimWriteRaceEndToEndTest*'`
Expected: PASS

- [ ] **Step 4: README 와 옛 설계에 적는다**

`README.md`:

(a) "**⑥-3(관리자 표시명 검색)도 키를 바꾼다** — …" 문단 바로 뒤에 넣는다.

```markdown
**②-a(유일 값 표지판)는 SCIM 테이블에 줄을 더한다** — 직원 `userName`·조직 `externalId` 의 유일성을 `UNIQUE#USERNAME`·`UNIQUE#GROUP_EXTERNAL_ID`
표지판 줄로 지킨다(`2026-10-14-unique-markers-design.md`). 테이블·GSI 정의는 바뀌지 않는다. 표지판 없이 저장된 직원·조직은 중복을 막지 못하므로,
기존 SCIM 테이블은 비우고(테이블을 다시 만들거나 `mode=wipe`) IdP 에서 전체 재프로비저닝한다. LDAP 테이블은 그대로다.
```

(b) SCIM 지원 범위 표 바로 뒤의 첫 문단("필터 없는 목록은 …") 앞에 넣는다.

```markdown
**`userName`·조직 `externalId` 의 유일성은 표지판 줄로 지킨다.** 쓰기 락 안에서, OpenFGA 쓰기 전에 그 값의 표지판을 조건부로 차지한다. 그래서 응답을
잃은 POST 를 IdP 가 곧바로 다시 보내도 409 다 — 최종 일관성 GSI 를 보지 않는다. 다른 쓰기가 차지하고 아직 저장하지 않은 값은 503(Retry-After 2초)이다.
서버가 죽었거나 리스를 잃은 직후에는 그 값의 표지판이 남아, 락 TTL(`dynamodb.lock-ttl`, 30초)이 지날 때까지 같은 값의 쓰기가 503 일 수 있다.
값이 1024바이트를 넘으면 400 `invalidValue` 다.
```

`docs/superpowers/specs/2026-09-28-scim-write-lock-design.md` §10:
- "방금(GSI 반영 전) 저장된 직원과 대소문자만 다른 `userName` 으로 만들거나 바꾸는 요청은 통과할 수 있다" 로 시작하는 문장을 찾는다(`grep -n "GSI 반영 전" docs/superpowers/specs/2026-09-28-scim-write-lock-design.md`).
- 그 문장이 있는 항목 끝에 붙인다: ` **→ 해결(2026-10-14, 표지판 줄 — `2026-10-14-unique-markers-design.md`)**`

`docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md` §11:
- "중복 판정은 GSI 후보를 본 테이블로 다시 확인한다" 가 든 항목을 찾는다(`grep -n "GSI 후보" docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md`).
- 그 끝에 같은 표시를 붙인다.

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimWriteRaceEndToEndTest.java README.md docs/superpowers/specs/2026-09-28-scim-write-lock-design.md docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md
git commit -m "feat: SCIM wipe 가 유일 값 표지판도 비운다, 같은 POST 두 번이면 두 번째 409 끝단 테스트, README·옛 설계에 표지판 유일성을 적는다(운영 견고성 ②-a)"
```
