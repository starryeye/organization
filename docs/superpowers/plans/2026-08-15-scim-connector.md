# SCIM 커넥터 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** IdP 가 보내는 SCIM 2.0 변경 요청을 받아 조직·직원 관계를 즉시 OpenFGA 튜플로 반영하고, 하루 1회 감사용 스냅샷을 적재하는 인스턴스를 완성한다.

**Architecture:** LDAP 계획이 만든 `core` 포트와 세 어댑터를 그대로 재사용한다. SCIM 은 push 모델이라 전체를 읽지 않는다 — 변경된 리소스 하나에 대해 **직전 상태를 DynamoDB 에서 읽고**, 변경을 적용한 뒤, 그 리소스에 관련된 튜플만 다시 계산해 `TupleDelta` 를 만든다. 그 지점부터는 LDAP 과 완전히 같은 코드(`RelationTupleWriter`)를 탄다.

**Tech Stack:** Java 17, Spring Boot 3.3.5 WebFlux (functional routing), Jackson, Lombok, JUnit 5, AssertJ, Testcontainers

**Spec:** [`docs/superpowers/specs/2026-08-14-organization-sync-design.md`](../specs/2026-08-14-organization-sync-design.md) — 특히 §7.2, §7.3, §9.3, §10

**선행 계획:** [`2026-08-14-foundation-and-ldap-sync.md`](2026-08-14-foundation-and-ldap-sync.md) 이 완료되어 있어야 한다. `core` (포트 5종, `TupleMapper`, `TupleDiff`, `SnapshotIds`), `storage-dynamodb`, `authz-openfga` 가 전부 존재하고 테스트가 통과하는 상태에서 시작한다.

**병합 순서:** 이 계획은 LDAP 브랜치가 `main` 에 병합된 뒤 그 위에서 시작한다. 병합 전이라면 LDAP 브랜치에서 분기할 것 — `core` 의 포트와 어댑터 없이는 첫 태스크부터 컴파일되지 않는다.

## Global Constraints

- **Java 17**, 패키지 루트 `dev.starryeye.organization`. `connector-scim` 의 패키지는 `dev.starryeye.organization.scim`, `app-scim` 은 `dev.starryeye.organization.scim.app`.
- **`core` 를 수정하지 않는다.** 단, 이 계획이 새로 추가하는 유스케이스 2종은 `core.usecase` 에 들어간다 — 기존 파일은 건드리지 않는다.
- **SCIM push 요청은 `SyncRun` 에 기록하지 않는다** (스펙 §4.4). 요청 단위 이력이 폭증한다. 로그와 메트릭만 남긴다. `SyncRunRepository` 를 쓰는 곳은 하루 1회 스냅샷 아카이빙 배치(`trigger=ARCHIVE`) 하나뿐이다.
- **조직명(`displayName`)은 튜플에 들어가지 않는다.** 튜플 식별자는 직원 아이디와 조직코드뿐이다.
- **OpenFGA 는 Write/Delete 만 호출한다.** `Check`/`Read`/`ListObjects` 는 테스트 코드에만.
- **삭제 가드는 SCIM 에 적용하지 않는다** (스펙 §9.2). SCIM 의 삭제는 의도된 단건 삭제다.
- **키 접두사와 시각 포맷은 `Keys` 에서만** 만든다. 인라인 리터럴 금지, 정렬키에 `Instant.toString()` 금지 — `Keys.sortableTimestamp` 를 쓴다.
- **SCIM 에러 응답은 스펙 §9.3 의 스키마**를 따른다: `{"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],"status":"...","scimType":"...","detail":"..."}`.
- **`active: false` 인 유저는 튜플을 만들지 않는다** (스펙 §10.2). LDAP 과 동일 규칙.
- **필터/페이징은 구현하지 않는다** (스펙 §10). 목록 GET 은 지원하지 않는다.
- 테스트 규약: AssertJ, `// given` / `// when` / `// then` 주석, `@DisplayName` 에 한글. 테스트 메서드명도 한글 허용.
- **커밋은 각 태스크 끝에서 수행하고 즉시 푸시한다.** 커밋 메시지 말미에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## 이 계획이 재사용하는 기존 API

수정하지 말고 그대로 호출한다. 시그니처는 실제 코드에서 확인한 것이다.

```java
// dev.starryeye.organization.core.model
record DirectoryUser(String id, String externalId, String userName,
                     String displayName, String email, boolean active) {}
record DirectoryGroup(String id, String externalId, String displayName, Set<MemberRef> members) {}
record MemberRef(MemberType type, String id) { static MemberRef user(String); static MemberRef group(String); }
record DirectorySnapshot(Map<String,DirectoryUser> users, Map<String,DirectoryGroup> groups) { static empty(); }
record RelationTuple(String user, String relation, String object) {
    static RelationTuple directMember(String userId, String groupId);
    static RelationTuple child(String childGroupId, String parentGroupId);
}
record TupleDelta(Set<RelationTuple> toWrite, Set<RelationTuple> toDelete) {
    static empty(); static writeOnly(Set); static deleteOnly(Set); boolean isEmpty();
}
record TupleWriteResult(Set<RelationTuple> written, Set<RelationTuple> deleted, List<TupleFailure> failures) {
    static empty(); boolean hasFailure();
}
record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {}
record SyncOutcome(...) { static noChange(); static succeeded(TupleWriteResult, String); static failed(String); }
enum SyncSource { LDAP, SCIM }
enum SyncTrigger { SCHEDULED, MANUAL, FORCED, REBUILD, ARCHIVE }

// dev.starryeye.organization.core.tuple
TupleMappingResult TupleMapper.toTuples(DirectorySnapshot);   // record(Set<RelationTuple> tuples, List<String> warnings)
TupleDelta TupleDiff.between(Set<RelationTuple> baseline, Set<RelationTuple> target);
String SnapshotIds.generate(Instant at, SyncSource source);
String IdNormalizer.normalize(String raw);

// dev.starryeye.organization.core.port
interface DirectoryStateRepository {
    Mono<DirectoryUser> findUser(String);   Mono<DirectoryGroup> findGroup(String);
    Mono<Void> saveUser(DirectoryUser);     Mono<Void> saveGroup(DirectoryGroup);
    Mono<Void> deleteUser(String);          Mono<Void> deleteGroup(String);
    Flux<String> findGroupIdsContaining(MemberRef ref);
    Mono<Void> replaceWith(DirectorySnapshot);  Mono<DirectorySnapshot> loadAll();
}
interface RelationTupleWriter { Mono<TupleWriteResult> apply(TupleDelta); Mono<Void> resetStore(); }
interface TupleSnapshotRepository { Mono<TupleSnapshot> findLatest(); Mono<Void> save(TupleSnapshot);
    Flux<SnapshotMeta> listRecent(int); Mono<TupleSnapshot> findById(String);
    Mono<Void> reset(); Mono<Integer> purgeExpired(); }
interface SyncRunRepository { Mono<SyncRun> start(SyncSource, SyncTrigger);
    Mono<SyncRun> finish(SyncRun, SyncOutcome); Flux<SyncRun> findRecent(int); }
```

`core/src/test/java/.../core/fake/` 의 fake 5종(`FakeStateRepository`, `FakeTupleWriter`, `FakeSnapshotRepository`, `FakeSyncRunRepository`, `FakeSnapshotSource`)도 그대로 재사용한다.

## File Structure

| 파일 | 책임 |
|---|---|
| `connector-scim/.../dto/Scim*.java` | SCIM 2.0 리소스·에러·PATCH 요청 DTO |
| `connector-scim/.../ScimException.java` | SCIM 에러 응답으로 번역될 예외 |
| `connector-scim/.../ScimPatchApplier.java` | PATCH 연산을 도메인 객체에 적용 (순수 로직) |
| `connector-scim/.../ScimMapper.java` | DTO ↔ 도메인 변환 |
| `connector-scim/.../ScimUserHandler.java` | `/Users` 핸들러 |
| `connector-scim/.../ScimGroupHandler.java` | `/Groups` 핸들러 |
| `connector-scim/.../ScimRouter.java` | 함수형 라우팅 + 에러 → SCIM Error 변환 |
| `connector-scim/.../ScimConfig.java` | 빈 등록, 자동설정 |
| `core/.../usecase/IncrementalSyncUseCase.java` | SCIM 변경 → 델타 → OpenFGA → 상태 저장 |
| `core/.../usecase/SnapshotArchiveUseCase.java` | 하루 1회 현재상태 → 스냅샷 |
| `app-scim/.../ScimUseCaseConfig.java` | 유스케이스 빈 조립 |
| `app-scim/.../ArchiveScheduler.java` | 아카이빙·정리 스케줄러 |

---

## Task 1: SCIM DTO 와 에러 표현

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/dto/` 아래 `ScimUser`, `ScimName`, `ScimEmail`, `ScimGroup`, `ScimMember`, `ScimMeta`, `ScimError`, `ScimPatchOp`, `ScimOperation`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimException.java`, `ScimSchemas.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/dto/ScimSerializationTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: 위 DTO 전부. `ScimException(HttpStatus status, String scimType, String detail)` + 정적 팩토리 `notFound(String)`, `invalidSyntax(String)`, `invalidPath(String)`, `uniqueness(String)`, `internal(String)`, 접근자 `getStatus()`/`getScimType()`. `ScimSchemas.USER/GROUP/ERROR/PATCH_OP/SERVICE_PROVIDER_CONFIG`.

**설계 근거.** SCIM 은 스키마 URN 을 본문에 실어 보내고 IdP 가 그것을 검증한다. `$ref` 는 선택 필드이고 우리가 쓰지 않으므로 DTO 에 두지 않는다 — Jackson 에서 `$` 로 시작하는 필드명을 다루는 비용만 생기고 얻는 게 없다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ScimSerializationTest.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.scim.ScimSchemas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScimSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("SCIM User 는 IdP 가 보내는 본문 형태 그대로 역직렬화된다")
    void 유저_요청_본문을_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "externalId": "emp-1001",
                  "userName": "kim",
                  "name": {"formatted": "김철수"},
                  "displayName": "김철수",
                  "emails": [{"value": "kim@example.com", "primary": true}],
                  "active": true
                }
                """;

        // when
        ScimUser user = mapper.readValue(body, ScimUser.class);

        // then
        assertThat(user.userName()).isEqualTo("kim");
        assertThat(user.externalId()).isEqualTo("emp-1001");
        assertThat(user.displayName()).isEqualTo("김철수");
        assertThat(user.emails()).hasSize(1);
        assertThat(user.emails().get(0).value()).isEqualTo("kim@example.com");
        assertThat(user.emails().get(0).primary()).isTrue();
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("알 수 없는 SCIM 속성이 있어도 역직렬화가 실패하지 않는다")
    void 모르는_속성은_무시한다() throws Exception {
        // given — IdP 마다 보내는 확장 속성이 다르다
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "userName": "kim",
                  "nickName": "철수",
                  "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {"employeeNumber": "1001"}
                }
                """;

        // when
        ScimUser user = mapper.readValue(body, ScimUser.class);

        // then
        assertThat(user.userName()).isEqualTo("kim");
    }

    @Test
    @DisplayName("SCIM Group 의 members 는 type 으로 유저와 하위 조직을 구분한다")
    void 그룹_멤버를_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
                  "externalId": "DEV001",
                  "displayName": "개발본부",
                  "members": [
                    {"value": "DEV002", "type": "Group"},
                    {"value": "park", "type": "User"}
                  ]
                }
                """;

        // when
        ScimGroup group = mapper.readValue(body, ScimGroup.class);

        // then
        assertThat(group.externalId()).isEqualTo("DEV001");
        assertThat(group.displayName()).isEqualTo("개발본부");
        assertThat(group.members()).extracting(ScimMember::type)
                .containsExactly("Group", "User");
    }

    @Test
    @DisplayName("PATCH 요청의 Operations 는 SCIM 스펙대로 대문자 O 로 온다")
    void 패치_요청을_역직렬화한다() throws Exception {
        // given
        String body = """
                {
                  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                  "Operations": [
                    {"op": "add", "path": "members", "value": [{"value": "kim", "type": "User"}]}
                  ]
                }
                """;

        // when
        ScimPatchOp patch = mapper.readValue(body, ScimPatchOp.class);

        // then
        assertThat(patch.operations()).hasSize(1);
        assertThat(patch.operations().get(0).op()).isEqualTo("add");
        assertThat(patch.operations().get(0).path()).isEqualTo("members");
    }

    @Test
    @DisplayName("에러 응답은 SCIM Error 스키마로 직렬화되고 null 필드는 빠진다")
    void 에러_응답을_직렬화한다() throws Exception {
        // given
        ScimError error = new ScimError(List.of(ScimSchemas.ERROR), "404", null, "Group not found: DEV999");

        // when
        String json = mapper.writeValueAsString(error);

        // then
        assertThat(json).contains("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(json).contains("\"status\":\"404\"");
        assertThat(json).contains("Group not found: DEV999");
        assertThat(json).doesNotContain("scimType");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*ScimSerializationTest*'`

Expected: 컴파일 실패 — DTO 클래스가 없다.

- [ ] **Step 3: 스키마 상수와 예외 작성**

`ScimSchemas.java`:

```java
package dev.starryeye.organization.scim;

/** SCIM 2.0 이 규정한 스키마 URN. IdP 가 본문의 이 값을 검증한다. */
public final class ScimSchemas {

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SERVICE_PROVIDER_CONFIG =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private ScimSchemas() {
    }
}
```

`ScimException.java`:

```java
package dev.starryeye.organization.scim;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * SCIM Error 응답(설계 §9.3)으로 번역되는 예외.
 *
 * <p>{@code scimType} 은 SCIM 이 규정한 오류 분류다. 404 와 500 에는 해당 분류가 없으므로 null 을 허용한다.
 */
@Getter
public class ScimException extends RuntimeException {

    private final HttpStatus status;
    private final String scimType;

    public ScimException(HttpStatus status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public static ScimException notFound(String detail) {
        return new ScimException(HttpStatus.NOT_FOUND, null, detail);
    }

    public static ScimException invalidSyntax(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", detail);
    }

    public static ScimException invalidPath(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidPath", detail);
    }

    public static ScimException uniqueness(String detail) {
        return new ScimException(HttpStatus.CONFLICT, "uniqueness", detail);
    }

    /** 하위 시스템(OpenFGA/DynamoDB) 실패. IdP 가 재시도하도록 5xx 로 돌려준다. */
    public static ScimException internal(String detail) {
        return new ScimException(HttpStatus.INTERNAL_SERVER_ERROR, null, detail);
    }
}
```

- [ ] **Step 4: DTO 작성**

모든 DTO 에 `@JsonIgnoreProperties(ignoreUnknown = true)` 와 `@JsonInclude(NON_NULL)` 을 단다. 전자는 IdP 확장 속성 때문에 요청 전체가 실패하지 않게 하고, 후자는 SCIM 의 선택 필드 규칙을 지킨다.

`ScimName.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimName(String formatted, String familyName, String givenName) {
}
```

`ScimEmail.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimEmail(String value, String type, Boolean primary) {
}
```

`ScimMeta.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimMeta(String resourceType, String location) {
}
```

`ScimUser.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUser(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        ScimName name,
        String displayName,
        List<ScimEmail> emails,
        Boolean active,
        ScimMeta meta
) {
}
```

`ScimMember.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param type "User" 또는 "Group". SCIM 이 중첩 그룹을 표현하는 유일한 수단이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimMember(String value, String type, String display) {
}
```

`ScimGroup.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimGroup(
        List<String> schemas,
        String id,
        String externalId,
        String displayName,
        List<ScimMember> members,
        ScimMeta meta
) {
}
```

`ScimOperation.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param value op 에 따라 모양이 달라진다 — members 배열이거나 단일 스칼라이거나
 *              path 없는 부분 리소스다. 그래서 Object 로 받고 적용 시점에 해석한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimOperation(String op, String path, Object value) {
}
```

`ScimPatchOp.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @param operations SCIM 스펙이 필드명을 대문자 "Operations" 로 규정한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimPatchOp(
        List<String> schemas,
        @JsonProperty("Operations") List<ScimOperation> operations
) {
}
```

`ScimError.java`:

```java
package dev.starryeye.organization.scim.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimError(List<String> schemas, String status, String scimType, String detail) {
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*ScimSerializationTest*'`

Expected: 5개 테스트 모두 PASS.

`모르는_속성은_무시한다` 가 실패하면 `@JsonIgnoreProperties` 가 빠진 DTO 가 있다. `에러_응답을_직렬화한다` 가 `scimType` 을 포함하면 `@JsonInclude(NON_NULL)` 이 빠진 것이다.

- [ ] **Step 6: build.gradle 확인 (변경 없음)**

`connector-scim/build.gradle` 은 선행 계획의 Task 1 에서 이미 다음을 선언하고 있다. 그대로 둔다 — Jackson 은 webflux starter 에 포함된다.

```groovy
dependencies {
    api project(':core')
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
}
```

- [ ] **Step 7: 커밋**

커밋 메시지:

```
feat: SCIM 2.0 DTO 와 에러 표현 추가

IdP 마다 보내는 확장 속성이 다르므로 모든 DTO 가 모르는 속성을 무시한다.
응답에서는 null 필드를 빼 SCIM 의 선택 필드 규칙을 따른다.

ScimException 은 SCIM Error 스키마(설계 §9.3)로 번역될 예외이며,
scimType 이 없는 경우(404, 500)를 위해 null 을 허용한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

커밋 후 현재 브랜치로 푸시한다.

---

## Task 2: ScimPatchApplier — PATCH 연산을 도메인에 적용

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`

**Interfaces:**
- Consumes: Task 1의 `ScimPatchOp`, `ScimOperation`, `ScimException`. `core` 의 `DirectoryGroup`, `DirectoryUser`, `MemberRef`.
- Produces:
  - `ScimPatchApplier.applyToGroup(DirectoryGroup before, ScimPatchOp patch) -> DirectoryGroup`
  - `ScimPatchApplier.applyToUser(DirectoryUser before, ScimPatchOp patch) -> DirectoryUser`
  - 둘 다 static. 지원하지 않는 path 는 `ScimException.invalidPath(...)` 를 던진다.

**이 태스크가 존재하는 이유.** IdP 가 그룹 멤버를 바꾸는 주 경로가 PATCH 다. PUT 은 전체 교체라 큰 그룹에서 비효율이고, Okta·Azure AD 모두 멤버 추가/삭제에 PATCH 를 쓴다. 여기가 틀리면 SCIM 연동이 실질적으로 동작하지 않는다.

**지원 범위 (설계 §10.1).** 아래 여섯 가지만 구현한다. 일반적인 SCIM 필터 문법 파서는 만들지 않는다.

| op | path | 동작 |
|---|---|---|
| `add` | `members` | 멤버 추가 |
| `remove` | `members` | 전체 제거 |
| `remove` | `members[value eq "kim"]` | 특정 멤버 제거 |
| `replace` | `members` | 전체 교체 |
| `replace` | `displayName` / `active` | 값 교체 |
| `add`/`replace` | path 없음 | 본문이 부분 리소스, 속성 병합 |

그 외 path 는 400 `invalidPath` 로 거절한다. **조용히 무시하면 안 된다** — IdP 는 200 을 받으면 반영됐다고 믿고 다시 보내지 않는다.

**`members[value eq "..."]` 파싱.** 이 한 가지 패턴만 정규식으로 인식한다. 값은 큰따옴표로 감싸여 오지만 IdP 에 따라 작은따옴표를 쓰기도 하므로 둘 다 받는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ScimPatchApplierTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimPatchApplierTest {

    private static DirectoryGroup 조직(MemberRef... members) {
        return new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(members));
    }

    private static DirectoryUser 직원(boolean active) {
        return new DirectoryUser("kim", "emp-1001", "kim", "김철수", "kim@example.com", active);
    }

    private static ScimPatchOp 패치(String op, String path, Object value) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP),
                List.of(new ScimOperation(op, path, value)));
    }

    private static Map<String, Object> 멤버(String value, String type) {
        return Map.of("value", value, "type", type);
    }

    @Test
    @DisplayName("add members 는 기존 멤버를 유지한 채 새 멤버를 더한다")
    void 멤버를_추가한다() {
        // given
        var before = 조직(MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(멤버("kim", "User"))));

        // then
        assertThat(after.members())
                .containsExactlyInAnyOrder(MemberRef.user("lee"), MemberRef.user("kim"));
        assertThat(after.id()).isEqualTo("DEV002");
        assertThat(after.displayName()).isEqualTo("백엔드팀");
    }

    @Test
    @DisplayName("add members 의 type 이 Group 이면 하위 조직 멤버로 추가된다")
    void 하위조직을_추가한다() {
        // given
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(멤버("DEV003", "Group"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.group("DEV003"));
    }

    @Test
    @DisplayName("path 필터로 지정한 멤버 하나만 제거된다")
    void 특정_멤버만_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq \"kim\"]", null));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("작은따옴표로 감싼 필터 값도 인식한다")
    void 작은따옴표_필터도_인식한다() {
        // given — IdP 에 따라 작은따옴표를 쓴다
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("remove", "members[value eq 'kim']", null));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("필터 없는 remove members 는 멤버를 전부 비운다")
    void 멤버를_전부_제거한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.group("DEV003"));

        // when
        var after = ScimPatchApplier.applyToGroup(before, 패치("remove", "members", null));

        // then
        assertThat(after.members()).isEmpty();
    }

    @Test
    @DisplayName("replace members 는 기존 멤버를 버리고 새 목록으로 갈아끼운다")
    void 멤버를_교체한다() {
        // given
        var before = 조직(MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", "members", List.of(멤버("park", "User"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("조직명을 바꿔도 조직코드와 멤버십은 그대로 유지된다")
    void 조직명만_바꾼다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", "displayName", "플랫폼팀"));

        // then
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
        assertThat(after.id()).isEqualTo("DEV002");
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("path 가 없으면 본문을 부분 리소스로 보고 있는 속성만 병합한다")
    void path_없는_연산은_속성을_병합한다() {
        // given
        var before = 조직(MemberRef.user("kim"));

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("replace", null, Map.of("displayName", "플랫폼팀")));

        // then
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("연산이 여러 개면 순서대로 누적 적용된다")
    void 여러_연산을_순서대로_적용한다() {
        // given
        var before = 조직(MemberRef.user("lee"));
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("add", "members", List.of(멤버("kim", "User"))),
                new ScimOperation("remove", "members[value eq \"lee\"]", null),
                new ScimOperation("replace", "displayName", "플랫폼팀")));

        // when
        var after = ScimPatchApplier.applyToGroup(before, patch);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
        assertThat(after.displayName()).isEqualTo("플랫폼팀");
    }

    @Test
    @DisplayName("직원의 active 를 false 로 바꾸면 반영된다")
    void 직원을_비활성화한다() {
        // given
        var before = 직원(true);

        // when
        var after = ScimPatchApplier.applyToUser(before, 패치("replace", "active", false));

        // then
        assertThat(after.active()).isFalse();
        assertThat(after.id()).isEqualTo("kim");
        assertThat(after.email()).isEqualTo("kim@example.com");
    }

    @Test
    @DisplayName("지원하지 않는 path 는 조용히 무시하지 않고 invalidPath 로 거절한다")
    void 지원하지_않는_path는_거절한다() {
        // given — 무시하면 IdP 는 반영된 줄 알고 다시 보내지 않는다
        var before = 조직(MemberRef.user("kim"));

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before,
                패치("replace", "emails[type eq \"work\"].value", "x@example.com")))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("emails");
    }

    @Test
    @DisplayName("알 수 없는 op 는 invalidSyntax 로 거절한다")
    void 알_수_없는_op는_거절한다() {
        // given
        var before = 조직();

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.applyToGroup(before,
                패치("frobnicate", "members", List.of())))
                .isInstanceOf(ScimException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    @DisplayName("멤버의 type 이 없으면 User 로 간주한다")
    void type이_없으면_User로_본다() {
        // given — SCIM 에서 type 은 선택 필드다
        var before = 조직();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                패치("add", "members", List.of(Map.of("value", "kim"))));

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest*'`

Expected: 컴파일 실패 — `ScimPatchApplier` 가 없다.

- [ ] **Step 3: 구현**

`ScimPatchApplier.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM PATCH 연산을 도메인 객체에 적용한다.
 *
 * <p>설계 §10.1 이 정한 여섯 가지 형태만 지원한다. 일반적인 SCIM 필터 문법 파서는 만들지 않는다.
 * 지원하지 않는 path 는 조용히 무시하지 않고 거절한다 — IdP 는 2xx 를 받으면 반영됐다고 믿고
 * 다시 보내지 않으므로, 무시는 영구적인 상태 불일치가 된다.
 */
public final class ScimPatchApplier {

    /** {@code members[value eq "kim"]} 한 가지 패턴만 인식한다. 따옴표는 큰/작은 둘 다 받는다. */
    private static final Pattern MEMBER_VALUE_FILTER =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+[\"'](?<value>[^\"']+)[\"']\\s*]$");

    private ScimPatchApplier() {
    }

    public static DirectoryGroup applyToGroup(DirectoryGroup before, ScimPatchOp patch) {
        DirectoryGroup current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation);
        }
        return current;
    }

    public static DirectoryUser applyToUser(DirectoryUser before, ScimPatchOp patch) {
        DirectoryUser current = before;
        for (ScimOperation operation : operations(patch)) {
            current = applyOne(current, operation);
        }
        return current;
    }

    private static List<ScimOperation> operations(ScimPatchOp patch) {
        if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
            throw ScimException.invalidSyntax("PATCH 요청에 Operations 가 없습니다");
        }
        return patch.operations();
    }

    // ---------- 그룹 ----------

    private static DirectoryGroup applyOne(DirectoryGroup group, ScimOperation operation) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            return mergeGroupAttributes(group, asAttributeMap(operation.value()));
        }

        Matcher filter = MEMBER_VALUE_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            String target = filter.group("value");
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.removeIf(member -> member.id().equals(target));
            return withMembers(group, members);
        }

        if (path.trim().equals("members")) {
            return switch (op) {
                case "add" -> {
                    Set<MemberRef> members = new LinkedHashSet<>(group.members());
                    members.addAll(toMemberRefs(operation.value()));
                    yield withMembers(group, members);
                }
                case "remove" -> withMembers(group, Set.of());
                case "replace" -> withMembers(group, toMemberRefs(operation.value()));
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equals("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return new DirectoryGroup(group.id(), group.externalId(),
                    asString(operation.value()), group.members());
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    private static DirectoryGroup mergeGroupAttributes(DirectoryGroup group, Map<String, Object> attributes) {
        String displayName = attributes.containsKey("displayName")
                ? asString(attributes.get("displayName"))
                : group.displayName();
        Set<MemberRef> members = attributes.containsKey("members")
                ? toMemberRefs(attributes.get("members"))
                : group.members();
        return new DirectoryGroup(group.id(), group.externalId(), displayName, members);
    }

    private static DirectoryGroup withMembers(DirectoryGroup group, Set<MemberRef> members) {
        return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), members);
    }

    // ---------- 직원 ----------

    private static DirectoryUser applyOne(DirectoryUser user, ScimOperation operation) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            return mergeUserAttributes(user, asAttributeMap(operation.value()));
        }

        requireReplaceOrAdd(op, operation.op());
        return switch (path.trim()) {
            case "active" -> new DirectoryUser(user.id(), user.externalId(), user.userName(),
                    user.displayName(), user.email(), asBoolean(operation.value()));
            case "displayName" -> new DirectoryUser(user.id(), user.externalId(), user.userName(),
                    asString(operation.value()), user.email(), user.active());
            case "userName" -> new DirectoryUser(user.id(), user.externalId(),
                    asString(operation.value()), user.displayName(), user.email(), user.active());
            default -> throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
        };
    }

    private static DirectoryUser mergeUserAttributes(DirectoryUser user, Map<String, Object> attributes) {
        return new DirectoryUser(
                user.id(),
                user.externalId(),
                attributes.containsKey("userName") ? asString(attributes.get("userName")) : user.userName(),
                attributes.containsKey("displayName") ? asString(attributes.get("displayName")) : user.displayName(),
                user.email(),
                attributes.containsKey("active") ? asBoolean(attributes.get("active")) : user.active());
    }

    // ---------- 값 해석 ----------

    private static String normalizeOp(String op) {
        if (op == null || op.isBlank()) {
            throw ScimException.invalidSyntax("op 가 비어 있습니다");
        }
        return op.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireReplaceOrAdd(String normalizedOp, String originalOp) {
        if (!normalizedOp.equals("replace") && !normalizedOp.equals("add")) {
            throw ScimException.invalidSyntax("이 path 에는 replace 또는 add 만 지원합니다: " + originalOp);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<MemberRef> toMemberRefs(Object value) {
        if (!(value instanceof List<?> raw)) {
            throw ScimException.invalidSyntax("members 값은 배열이어야 합니다");
        }
        Set<MemberRef> members = new LinkedHashSet<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) {
                throw ScimException.invalidSyntax("members 원소는 객체여야 합니다");
            }
            Object id = map.get("value");
            if (id == null) {
                throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
            }
            Object type = map.get("type");
            // SCIM 에서 type 은 선택 필드다. 없으면 User 로 간주한다.
            boolean isGroup = type != null && type.toString().equalsIgnoreCase("Group");
            members.add(isGroup ? MemberRef.group(id.toString()) : MemberRef.user(id.toString()));
        }
        return members;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asAttributeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ScimException.invalidSyntax("path 없는 연산의 값은 객체여야 합니다");
        }
        return (Map<String, Object>) map;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        throw ScimException.invalidSyntax("boolean 값이 아닙니다: " + value);
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest*'`

Expected: 13개 테스트 모두 PASS.

`특정_멤버만_제거한다` 가 실패하면 정규식의 공백 처리를 확인한다 — IdP 는 `members[value eq "kim"]` 과 `members[ value eq "kim" ]` 을 둘 다 보낼 수 있다.

- [ ] **Step 5: 커밋**

커밋 메시지:

```
feat: ScimPatchApplier 추가 — PATCH 연산을 도메인에 적용

IdP 가 그룹 멤버를 바꾸는 주 경로가 PATCH 다. 설계 §10.1 이 정한 여섯
가지 형태만 지원하고 일반 SCIM 필터 파서는 만들지 않는다.

지원하지 않는 path 는 조용히 무시하지 않고 invalidPath 로 거절한다 —
IdP 는 2xx 를 받으면 반영됐다고 믿고 다시 보내지 않으므로, 무시하면
영구적인 상태 불일치가 된다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 3: ScimMapper — DTO ↔ 도메인 변환

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java`

**Interfaces:**
- Consumes: Task 1의 DTO, `core` 의 도메인 record 와 `IdNormalizer`
- Produces:
  - `ScimMapper.toDirectoryUser(ScimUser) -> DirectoryUser`
  - `ScimMapper.toDirectoryGroup(ScimGroup) -> DirectoryGroup`
  - `ScimMapper.toScimUser(DirectoryUser) -> ScimUser`
  - `ScimMapper.toScimGroup(DirectoryGroup) -> ScimGroup`
  - 전부 static.

**매핑 규칙 (설계 §10.2).**

| SCIM | 도메인 | 비고 |
|---|---|---|
| `User.userName` | `DirectoryUser.id` | **직원 아이디**. `IdNormalizer` 를 거친다 |
| `User.externalId` | `DirectoryUser.externalId` | 원본 보관 |
| `User.displayName` → `name.formatted` → `userName` | `displayName` | 앞의 것이 비면 다음으로 |
| `User.emails[primary].value` | `email` | primary 없으면 첫 번째 |
| `User.active` (없으면 true) | `active` | |
| `Group.externalId` → 없으면 `Group.id` → 없으면 UUID | `DirectoryGroup.id` | **조직코드**. `IdNormalizer` 를 거친다 |
| `Group.displayName` | `displayName` | **조직명. 튜플에 절대 들어가지 않는다** |
| `Group.members[].value` + `type` | `Set<MemberRef>` | `type` 없으면 User |

**조직코드가 없을 때.** `externalId` 도 `id` 도 없으면 UUID 를 발급하고 경고 로그를 남긴다. 이후 IdP 는 발급된 `id` 로 호출하므로 일관성은 유지되지만, 조직코드가 소스가 아니라 우리 쪽에서 만들어진 값이 된다 — 그 사실이 로그에 남아야 나중에 추적할 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ScimMapperTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimName;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimMapperTest {

    @Test
    @DisplayName("직원 아이디는 userName 에서 오고 표시명은 displayName 을 우선한다")
    void 유저를_도메인으로_변환한다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, "emp-1001", "kim",
                new ScimName("김철수", null, null), "철수",
                List.of(new ScimEmail("kim@example.com", "work", true)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.id()).isEqualTo("kim");
        assertThat(user.externalId()).isEqualTo("emp-1001");
        assertThat(user.displayName()).isEqualTo("철수");
        assertThat(user.email()).isEqualTo("kim@example.com");
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("displayName 이 없으면 name.formatted 를 표시명으로 쓴다")
    void 표시명이_없으면_formatted를_쓴다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim",
                new ScimName("김철수", null, null), null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.displayName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("active 가 없으면 활성으로 간주한다")
    void active가_없으면_활성이다() {
        // given — SCIM 에서 active 는 선택 필드다
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim",
                null, null, List.of(), null, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.active()).isTrue();
    }

    @Test
    @DisplayName("primary 표시가 없으면 첫 번째 이메일을 쓴다")
    void primary가_없으면_첫_이메일을_쓴다() {
        // given
        var scim = new ScimUser(List.of(ScimSchemas.USER), null, null, "kim", null, null,
                List.of(new ScimEmail("a@example.com", "home", null),
                        new ScimEmail("b@example.com", "work", null)), true, null);

        // when
        DirectoryUser user = ScimMapper.toDirectoryUser(scim);

        // then
        assertThat(user.email()).isEqualTo("a@example.com");
    }

    @Test
    @DisplayName("조직코드는 externalId 에서 오고 조직명은 displayName 에서 온다")
    void 그룹을_도메인으로_변환한다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("DEV002", "Group", null),
                        new ScimMember("park", "User", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("DEV001");
        assertThat(group.displayName()).isEqualTo("개발본부");
        assertThat(group.members())
                .containsExactlyInAnyOrder(MemberRef.group("DEV002"), MemberRef.user("park"));
    }

    @Test
    @DisplayName("externalId 가 없으면 id 를 조직코드로 쓴다")
    void externalId가_없으면_id를_쓴다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), "DEV009", null, "운영팀",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("DEV009");
    }

    @Test
    @DisplayName("조직코드가 아예 없으면 UUID 를 발급한다")
    void 조직코드가_없으면_UUID를_발급한다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, null, "임시팀", List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isNotBlank().hasSize(36);
        assertThat(group.displayName()).isEqualTo("임시팀");
    }

    @Test
    @DisplayName("한글 조직코드는 정규화를 거쳐도 보존된다")
    void 한글_조직코드가_보존된다() {
        // given
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "개발본부", "개발본부",
                List.of(), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim);

        // then
        assertThat(group.id()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("도메인 유저를 SCIM 응답으로 되돌리면 스키마와 필수 필드가 채워진다")
    void 유저를_SCIM_응답으로_변환한다() {
        // given
        var user = new DirectoryUser("kim", "emp-1001", "kim", "김철수", "kim@example.com", true);

        // when
        ScimUser scim = ScimMapper.toScimUser(user);

        // then
        assertThat(scim.schemas()).containsExactly(ScimSchemas.USER);
        assertThat(scim.id()).isEqualTo("kim");
        assertThat(scim.externalId()).isEqualTo("emp-1001");
        assertThat(scim.active()).isTrue();
        assertThat(scim.emails()).hasSize(1);
        assertThat(scim.meta().resourceType()).isEqualTo("User");
    }

    @Test
    @DisplayName("도메인 조직을 SCIM 응답으로 되돌리면 멤버 type 이 복원된다")
    void 그룹을_SCIM_응답으로_변환한다() {
        // given
        var group = new DirectoryGroup("DEV001", "DEV001", "개발본부",
                Set.of(MemberRef.group("DEV002"), MemberRef.user("park")));

        // when
        ScimGroup scim = ScimMapper.toScimGroup(group);

        // then
        assertThat(scim.schemas()).containsExactly(ScimSchemas.GROUP);
        assertThat(scim.id()).isEqualTo("DEV001");
        assertThat(scim.displayName()).isEqualTo("개발본부");
        assertThat(scim.members()).extracting(ScimMember::type)
                .containsExactlyInAnyOrder("Group", "User");
        assertThat(scim.meta().resourceType()).isEqualTo("Group");
    }

    @Test
    @DisplayName("이메일이 없는 직원은 emails 를 비운 채 응답한다")
    void 이메일이_없으면_빈_배열이다() {
        // given
        var user = new DirectoryUser("kim", null, "kim", "김철수", null, true);

        // when
        ScimUser scim = ScimMapper.toScimUser(user);

        // then
        assertThat(scim.emails()).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*ScimMapperTest*'`

Expected: 컴파일 실패 — `ScimMapper` 가 없다.

- [ ] **Step 3: 구현**

`ScimMapper.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimMeta;
import dev.starryeye.organization.scim.dto.ScimUser;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCIM DTO 와 도메인 모델을 오간다.
 *
 * <p>조직코드({@link DirectoryGroup#id()})와 조직명({@link DirectoryGroup#displayName()})은
 * 의도적으로 분리돼 있다. 코드는 튜플에 쓰이는 식별자이고 이름은 개편 때마다 바뀌는 속성이다.
 * SCIM Group 에는 둘을 나눌 칸이 없어 {@code externalId} 를 코드로 채택한다(설계 §4.3).
 */
@Slf4j
public final class ScimMapper {

    private ScimMapper() {
    }

    // ---------- SCIM → 도메인 ----------

    public static DirectoryUser toDirectoryUser(ScimUser scim) {
        if (scim.userName() == null || scim.userName().isBlank()) {
            throw ScimException.invalidSyntax("userName 은 필수입니다");
        }
        return new DirectoryUser(
                IdNormalizer.normalize(scim.userName()),
                scim.externalId(),
                scim.userName(),
                firstNonBlank(scim.displayName(), formatted(scim), scim.userName()),
                primaryEmail(scim.emails()),
                // SCIM 에서 active 는 선택 필드다. 없으면 활성으로 본다.
                scim.active() == null || scim.active());
    }

    public static DirectoryGroup toDirectoryGroup(ScimGroup scim) {
        return new DirectoryGroup(
                organizationCode(scim),
                scim.externalId(),
                scim.displayName(),
                toMemberRefs(scim.members()));
    }

    private static String organizationCode(ScimGroup scim) {
        String source = firstNonBlank(scim.externalId(), scim.id());
        if (source != null) {
            return IdNormalizer.normalize(source);
        }
        String generated = UUID.randomUUID().toString();
        log.warn("SCIM Group 에 externalId 도 id 도 없어 조직코드를 발급합니다: displayName='{}', 발급된 코드='{}'",
                scim.displayName(), generated);
        return generated;
    }

    private static Set<MemberRef> toMemberRefs(List<ScimMember> members) {
        if (members == null) {
            return Set.of();
        }
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (ScimMember member : members) {
            if (member.value() == null || member.value().isBlank()) {
                throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
            }
            // SCIM 에서 type 은 선택 필드다. 없으면 User 로 간주한다.
            boolean isGroup = member.type() != null && member.type().equalsIgnoreCase("Group");
            refs.add(isGroup ? MemberRef.group(member.value()) : MemberRef.user(member.value()));
        }
        return refs;
    }

    private static String formatted(ScimUser scim) {
        return scim.name() == null ? null : scim.name().formatted();
    }

    private static String primaryEmail(List<ScimEmail> emails) {
        if (emails == null || emails.isEmpty()) {
            return null;
        }
        return emails.stream()
                .filter(email -> Boolean.TRUE.equals(email.primary()))
                .findFirst()
                .orElse(emails.get(0))
                .value();
    }

    // ---------- 도메인 → SCIM ----------

    public static ScimUser toScimUser(DirectoryUser user) {
        List<ScimEmail> emails = user.email() == null
                ? List.of()
                : List.of(new ScimEmail(user.email(), "work", true));
        return new ScimUser(
                List.of(ScimSchemas.USER),
                user.id(),
                user.externalId(),
                user.userName(),
                new ScimName(user.displayName(), null, null),
                user.displayName(),
                emails,
                user.active(),
                new ScimMeta("User", "/scim/v2/Users/" + user.id()));
    }

    public static ScimGroup toScimGroup(DirectoryGroup group) {
        List<ScimMember> members = group.members().stream()
                .map(ref -> new ScimMember(ref.id(),
                        ref.type() == MemberType.GROUP ? "Group" : "User", null))
                .toList();
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                group.id(),
                group.externalId(),
                group.displayName(),
                members,
                new ScimMeta("Group", "/scim/v2/Groups/" + group.id()));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `./gradlew :connector-scim:test`

Expected: `ScimSerializationTest` 5 + `ScimPatchApplierTest` 13 + `ScimMapperTest` 11 = 29개 PASS.

- [ ] **Step 5: 커밋**

커밋 메시지:

```
feat: ScimMapper 추가 — DTO 와 도메인 모델 변환

조직코드는 Group.externalId 에서, 조직명은 displayName 에서 온다.
SCIM Group 에는 둘을 나눌 칸이 없어 externalId 를 코드로 채택했다(설계 §4.3).
조직명은 개편 때마다 바뀌므로 튜플에는 코드만 들어간다.

externalId 도 id 도 없으면 UUID 를 발급하고 경고를 남긴다 — 이후
IdP 는 발급된 id 로 호출하므로 일관성은 유지되지만, 조직코드가 소스가
아니라 우리 쪽에서 만들어진 값이라는 사실이 로그에 남아야 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 4: IncrementalSyncUseCase — SCIM 변경을 튜플에 반영

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncResult.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java`

**Interfaces:**
- Consumes: `DirectoryStateRepository`, `RelationTupleWriter`, `TupleMapper`, `TupleDiff`, 그리고 `core/src/test/java/.../core/fake/` 의 `FakeStateRepository`, `FakeTupleWriter`
- Produces:
  - `IncrementalSyncResult(boolean fullyApplied, TupleWriteResult writeResult)` + `boolean hasFailure()`
  - `IncrementalSyncUseCase(DirectoryStateRepository state, RelationTupleWriter writer)` 생성자
  - `Mono<IncrementalSyncResult> upsertUser(DirectoryUser user)`
  - `Mono<IncrementalSyncResult> upsertGroup(DirectoryGroup group)`
  - `Mono<IncrementalSyncResult> removeUser(String userId)`
  - `Mono<IncrementalSyncResult> removeGroup(String groupId)`

**LDAP 과 무엇이 다른가.** LDAP 은 전체를 읽어 직전 스냅샷과 diff 한다. SCIM 은 리소스 하나의 변경만 오므로 **그 리소스에 관련된 튜플만** 다시 계산한다. 계산 결과가 `TupleDelta` 로 수렴한 뒤부터는 LDAP 과 완전히 같은 코드(`RelationTupleWriter`)를 탄다.

**튜플 계산을 `TupleMapper` 에 맡기는 이유.** 한 조직의 튜플을 손으로 만들면 `TupleMapper` 의 규칙(비활성 유저 제외, 없는 멤버 스킵)이 두 벌이 되고 언젠가 어긋난다. 대신 **영향 범위만 담은 최소 스냅샷**을 만들어 `TupleMapper` 를 돌린다. 규칙은 한 곳에만 있다.

**영향 범위.**
- 조직 변경: 그 조직 + 그 조직의 멤버 유저들(활성 여부 판정에 필요)
- 유저 변경: 그 유저 + 그 유저가 속한 모든 조직(`findGroupIdsContaining` 으로 찾음). `active` 가 뒤집히면 그 유저의 모든 `direct_member` 튜플이 생기거나 사라진다.

**부분 실패 처리 (설계 §7.2).** OpenFGA 배치는 트랜잭션이므로 SCIM 단건 변경은 대개 전부 성공이거나 전부 실패다. 대형 그룹 PUT 으로 배치가 쪼개질 때만 부분 성공이 가능하다. 그 경우 **반영된 만큼만 상태에 저장**하고 실패를 알린다 — 저장하지 않으면 OpenFGA 에는 반영됐는데 DynamoDB 는 모르는 상태가 되어 영구히 어긋난다.

- [ ] **Step 1: 실패하는 테스트 작성**

`IncrementalSyncUseCaseTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeStateRepository;
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

class IncrementalSyncUseCaseTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private IncrementalSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        useCase = new IncrementalSyncUseCase(state, writer);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id, id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, "백엔드팀", Set.of(members));
    }

    @Test
    @DisplayName("조직에 멤버를 추가하면 그 멤버의 direct_member 튜플만 생성된다")
    void 멤버_추가가_튜플을_만든다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002")).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(writer.appliedDeltas.get(0).toDelete()).isEmpty();
    }

    @Test
    @DisplayName("조직에서 멤버를 빼면 그 튜플만 삭제된다")
    void 멤버_제거가_튜플을_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("lee", "DEV002"));
    }

    @Test
    @DisplayName("성공하면 변경된 조직이 현재상태에 저장된다")
    void 성공하면_상태가_저장된다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002")).block();

        // when
        useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("하위 조직을 멤버로 추가하면 child 튜플이 생성된다")
    void 하위조직_추가가_child_튜플을_만든다() {
        // given
        state.saveGroup(조직("DEV002")).block();
        state.saveGroup(조직("DEV001")).block();

        // when
        useCase.upsertGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // then
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.child("DEV002", "DEV001"));
    }

    @Test
    @DisplayName("직원을 비활성화하면 그 직원이 속한 모든 조직의 튜플이 사라진다")
    void 비활성화가_모든_소속_튜플을_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("OPS001", MemberRef.user("kim"))).block();

        // when
        var result = useCase.upsertUser(직원("kim", false)).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("kim", "OPS001"));
        assertThat(writer.appliedDeltas.get(0).toWrite()).isEmpty();
    }

    @Test
    @DisplayName("비활성 직원을 다시 활성화하면 소속 튜플이 되살아난다")
    void 재활성화가_튜플을_되살린다() {
        // given
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        useCase.upsertUser(직원("kim", true)).block();

        // then
        assertThat(writer.appliedDeltas.get(0).toWrite())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("직원을 삭제하면 소속 튜플이 지워지고 현재상태에서도 사라진다")
    void 직원_삭제가_튜플과_상태를_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var result = useCase.removeUser("kim").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
        assertThat(state.users).doesNotContainKey("kim");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
    }

    @Test
    @DisplayName("조직을 삭제하면 그 조직의 튜플과 상위 조직에서의 child 튜플이 모두 지워진다")
    void 조직_삭제가_상위_child_튜플도_지운다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var result = useCase.removeGroup("DEV002").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001"));
        assertThat(state.groups).doesNotContainKey("DEV002");
        assertThat(state.groups.get("DEV001").members()).isEmpty();
    }

    @Test
    @DisplayName("변경이 없으면 OpenFGA 를 호출하지 않는다")
    void 변경이_없으면_아무것도_쓰지_않는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var result = useCase.upsertGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 반영된 만큼만 상태에 저장하고 실패를 알린다")
    void 부분_실패시_반영분만_저장한다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV002")).block();
        writer.failFor(tuple -> tuple.user().equals("user:lee"));

        // when
        var result = useCase.upsertGroup(
                조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // then — 실패를 알리되, 반영된 kim 은 상태에도 남아야 OpenFGA 와 어긋나지 않는다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(result.hasFailure()).isTrue();
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("존재하지 않는 직원을 삭제해도 예외 없이 끝난다")
    void 없는_직원_삭제는_조용히_끝난다() {
        // given, when
        var result = useCase.removeUser("ghost").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :core:test --tests '*IncrementalSyncUseCaseTest*'`

Expected: 컴파일 실패 — `IncrementalSyncUseCase` 가 없다.

- [ ] **Step 3: 결과 타입 작성**

`IncrementalSyncResult.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.TupleWriteResult;

/**
 * @param fullyApplied 의도한 델타가 전부 반영됐는지. false 면 SCIM 응답은 5xx 여야 하며,
 *                     IdP 가 재시도해 나머지를 반영하게 한다.
 */
public record IncrementalSyncResult(boolean fullyApplied, TupleWriteResult writeResult) {

    public static IncrementalSyncResult noChange() {
        return new IncrementalSyncResult(true, TupleWriteResult.empty());
    }

    public static IncrementalSyncResult of(TupleWriteResult result) {
        return new IncrementalSyncResult(!result.hasFailure(), result);
    }

    public boolean hasFailure() {
        return writeResult.hasFailure();
    }
}
```

- [ ] **Step 4: 유스케이스 구현**

`IncrementalSyncUseCase.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SCIM 이 보낸 단건 변경을 튜플에 반영한다.
 *
 * <p>LDAP 은 전체를 읽어 직전 스냅샷과 diff 하지만, SCIM 은 리소스 하나의 변경만 온다.
 * 그래서 <b>영향 범위만 담은 최소 스냅샷</b>을 변경 전후로 각각 만들어 {@link TupleMapper} 에
 * 통과시키고, 그 둘을 {@link TupleDiff} 로 비교한다. 튜플 생성 규칙(비활성 유저 제외 등)이
 * 한 곳에만 있게 하려는 것이다 — 손으로 계산하면 규칙이 두 벌이 되고 언젠가 어긋난다.
 *
 * <p>이 유스케이스는 {@code SyncRun} 을 기록하지 않는다(설계 §4.4). SCIM 은 요청 단위라
 * 이력이 폭증한다.
 */
@Slf4j
@RequiredArgsConstructor
public class IncrementalSyncUseCase {

    private static final int LOAD_CONCURRENCY = 8;

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;

    /** 직원 생성·수정. 활성 여부가 바뀌면 그 직원이 속한 모든 조직의 튜플이 함께 움직인다. */
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
        return affectedGroupsOf(user.id())
                .flatMap(groups -> {
                    Mono<DirectorySnapshot> before = snapshotOf(groups, existingUser(user.id()));
                    Mono<DirectorySnapshot> after = snapshotOf(groups, Mono.just(user));
                    return applyAndCommit(before, after, state.saveUser(user));
                });
    }

    /** 조직 생성·수정. 멤버 목록을 통째로 교체한다. */
    public Mono<IncrementalSyncResult> upsertGroup(DirectoryGroup group) {
        return state.findGroup(group.id())
                .map(Set::of)
                .defaultIfEmpty(Set.of())
                .flatMap(existing -> {
                    Mono<DirectorySnapshot> before = existing.isEmpty()
                            ? Mono.just(DirectorySnapshot.empty())
                            : snapshotOfGroups(existing);
                    Mono<DirectorySnapshot> after = snapshotOfGroups(Set.of(group));
                    return applyAndCommit(before, after, state.saveGroup(group));
                });
    }

    /** 직원 삭제. 그 직원이 속한 모든 조직에서 멤버십도 함께 지운다. */
    public Mono<IncrementalSyncResult> removeUser(String userId) {
        return state.findUser(userId)
                .flatMap(user -> affectedGroupsOf(userId).flatMap(groups -> {
                    Mono<DirectorySnapshot> before = snapshotOf(groups, Mono.just(user));
                    Set<DirectoryGroup> without = removeMemberFrom(groups, MemberRef.user(userId));
                    Mono<DirectorySnapshot> after = snapshotOf(without, Mono.empty());
                    Mono<Void> commit = Flux.fromIterable(without)
                            .flatMap(state::saveGroup, LOAD_CONCURRENCY)
                            .then(state.deleteUser(userId));
                    return applyAndCommit(before, after, commit);
                }))
                .defaultIfEmpty(IncrementalSyncResult.noChange());
    }

    /** 조직 삭제. 상위 조직에서의 child 튜플까지 함께 지운다. */
    public Mono<IncrementalSyncResult> removeGroup(String groupId) {
        return state.findGroup(groupId)
                .flatMap(group -> parentsOf(groupId).flatMap(parents -> {
                    Set<DirectoryGroup> beforeGroups = new LinkedHashSet<>(parents);
                    beforeGroups.add(group);
                    Mono<DirectorySnapshot> before = snapshotOfGroups(beforeGroups);

                    Set<DirectoryGroup> afterParents = removeMemberFrom(parents, MemberRef.group(groupId));
                    Mono<DirectorySnapshot> after = snapshotOfGroups(afterParents);

                    Mono<Void> commit = Flux.fromIterable(afterParents)
                            .flatMap(state::saveGroup, LOAD_CONCURRENCY)
                            .then(state.deleteGroup(groupId));
                    return applyAndCommit(before, after, commit);
                }))
                .defaultIfEmpty(IncrementalSyncResult.noChange());
    }

    // ---------- 공통 ----------

    /**
     * 변경 전후 스냅샷을 튜플로 바꿔 diff 하고, OpenFGA 에 먼저 적용한 뒤 상태를 커밋한다.
     *
     * <p>부분 실패에도 커밋한다(설계 §7.2). 반영된 것을 상태에 남기지 않으면 OpenFGA 에는
     * 있는데 DynamoDB 는 모르는 상태가 되어 영구히 어긋난다.
     */
    private Mono<IncrementalSyncResult> applyAndCommit(Mono<DirectorySnapshot> beforeMono,
                                                       Mono<DirectorySnapshot> afterMono,
                                                       Mono<Void> commit) {
        return Mono.zip(beforeMono, afterMono).flatMap(both -> {
            Set<RelationTuple> before = tuplesOf(both.getT1());
            Set<RelationTuple> after = tuplesOf(both.getT2());
            TupleDelta delta = TupleDiff.between(before, after);

            if (delta.isEmpty()) {
                return commit.thenReturn(IncrementalSyncResult.noChange());
            }
            return writer.apply(delta)
                    .flatMap(result -> commit.thenReturn(IncrementalSyncResult.of(result)));
        });
    }

    private Set<RelationTuple> tuplesOf(DirectorySnapshot snapshot) {
        var mapping = TupleMapper.toTuples(snapshot);
        mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
        return mapping.tuples();
    }

    /** 이 직원이 속한 모든 조직. 활성 여부가 뒤집히면 전부 영향을 받는다. */
    private Mono<Set<DirectoryGroup>> affectedGroupsOf(String userId) {
        return state.findGroupIdsContaining(MemberRef.user(userId))
                .flatMap(state::findGroup, LOAD_CONCURRENCY)
                .collect(LinkedHashSet::new, Set::add);
    }

    /** 이 조직을 하위 조직으로 갖는 상위 조직들. */
    private Mono<Set<DirectoryGroup>> parentsOf(String groupId) {
        return state.findGroupIdsContaining(MemberRef.group(groupId))
                .flatMap(state::findGroup, LOAD_CONCURRENCY)
                .collect(LinkedHashSet::new, Set::add);
    }

    private Mono<DirectoryUser> existingUser(String userId) {
        return state.findUser(userId);
    }

    /**
     * 조직 집합과 (선택적) 변경된 유저 하나로 최소 스냅샷을 만든다.
     * 조직의 멤버 유저를 모두 실어야 {@link TupleMapper} 가 활성 여부를 판정할 수 있다.
     */
    private Mono<DirectorySnapshot> snapshotOf(Set<DirectoryGroup> groups, Mono<DirectoryUser> changed) {
        return changed.map(Set::of).defaultIfEmpty(Set.of())
                .flatMap(overrides -> loadMemberUsers(groups, overrides)
                        .map(users -> new DirectorySnapshot(users, byId(groups))));
    }

    private Mono<DirectorySnapshot> snapshotOfGroups(Set<DirectoryGroup> groups) {
        return loadMemberUsers(groups, Set.of())
                .map(users -> new DirectorySnapshot(users, byId(groups)));
    }

    /**
     * 조직들의 멤버 유저를 현재상태에서 읽어온다. {@code overrides} 에 있는 유저는
     * 저장된 값 대신 그 값을 쓴다 — 아직 저장 전인 변경 후 상태를 반영하기 위해서다.
     */
    private Mono<Map<String, DirectoryUser>> loadMemberUsers(Set<DirectoryGroup> groups,
                                                             Set<DirectoryUser> overrides) {
        Map<String, DirectoryUser> overrideById = byUserId(overrides);
        Set<String> memberIds = new LinkedHashSet<>();
        for (DirectoryGroup group : groups) {
            for (MemberRef member : group.members()) {
                if (member.type() == MemberType.USER) {
                    memberIds.add(member.id());
                }
            }
        }
        memberIds.addAll(overrideById.keySet());

        return Flux.fromIterable(memberIds)
                .flatMap(id -> overrideById.containsKey(id)
                        ? Mono.just(overrideById.get(id))
                        : state.findUser(id), LOAD_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, user) -> map.put(user.id(), user));
    }

    private static Set<DirectoryGroup> removeMemberFrom(Set<DirectoryGroup> groups, MemberRef ref) {
        Set<DirectoryGroup> result = new LinkedHashSet<>();
        for (DirectoryGroup group : groups) {
            Set<MemberRef> members = new LinkedHashSet<>(group.members());
            members.remove(ref);
            result.add(new DirectoryGroup(group.id(), group.externalId(), group.displayName(), members));
        }
        return result;
    }

    private static Map<String, DirectoryGroup> byId(Set<DirectoryGroup> groups) {
        Map<String, DirectoryGroup> map = new LinkedHashMap<>();
        groups.forEach(group -> map.put(group.id(), group));
        return map;
    }

    private static Map<String, DirectoryUser> byUserId(Set<DirectoryUser> users) {
        Map<String, DirectoryUser> map = new LinkedHashMap<>();
        users.forEach(user -> map.put(user.id(), user));
        return map;
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run: `./gradlew :core:test --tests '*IncrementalSyncUseCaseTest*'`

Expected: 11개 테스트 모두 PASS.

`비활성화가_모든_소속_튜플을_지운다` 가 실패하면 `loadMemberUsers` 의 override 처리를 확인한다 — 변경 후 스냅샷은 **아직 저장되지 않은** 유저 값을 써야 한다. 저장된 값을 읽으면 활성 상태가 그대로라 델타가 비어버린다.

- [ ] **Step 6: 커밋**

커밋 메시지:

```
feat: IncrementalSyncUseCase 추가 — SCIM 단건 변경을 튜플에 반영

LDAP 은 전체를 읽어 diff 하지만 SCIM 은 리소스 하나만 온다. 영향 범위만
담은 최소 스냅샷을 변경 전후로 만들어 TupleMapper 에 통과시키고 diff 한다 —
튜플 생성 규칙을 손으로 다시 쓰면 규칙이 두 벌이 되고 언젠가 어긋난다.

부분 실패에도 반영된 만큼 상태를 커밋한다(설계 §7.2). 저장하지 않으면
OpenFGA 에는 있는데 DynamoDB 는 모르는 상태가 되어 영구히 어긋난다.

SyncRun 은 기록하지 않는다 — 요청 단위 이력이 폭증한다(설계 §4.4).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 5: SnapshotArchiveUseCase — 하루 1회 감사용 스냅샷

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java`

**Interfaces:**
- Consumes: `DirectoryStateRepository`, `TupleSnapshotRepository`, `SyncRunRepository`, `TupleMapper`, `SnapshotIds`, fake 3종
- Produces: `SnapshotArchiveUseCase(DirectoryStateRepository, TupleSnapshotRepository, SyncRunRepository, Clock)` + `Mono<SyncRun> execute()`

**왜 필요한가 (설계 §7.3).** SCIM 은 push 라 diff 용 스냅샷이 필요 없다. 이 스냅샷은 **감사와 수동 복구용**이다. LDAP 인스턴스와 같은 저장 구조를 쓰므로, 나중에 소스를 바꾸거나 사고를 조사할 때 두 인스턴스의 기록을 같은 방식으로 읽을 수 있다.

**여기서는 `SyncRun` 을 기록한다.** SCIM 요청은 기록하지 않지만 이건 배치다(`trigger=ARCHIVE`). 새벽에 아카이빙이 실패했다면 그 사실이 남아야 한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`SnapshotArchiveUseCaseTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotArchiveUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-15T03:00:00Z");

    private FakeStateRepository state;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private SnapshotArchiveUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = new SnapshotArchiveUseCase(state, snapshots, runs,
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("현재상태를 튜플로 바꿔 SCIM 소스 스냅샷으로 적재한다")
    void 현재상태를_스냅샷으로_적재한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).id()).isEqualTo("20260815T030000-SCIM");
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("아카이빙은 ARCHIVE 트리거로 이력에 남는다")
    void ARCHIVE_트리거로_기록된다() {
        // given, when
        var run = useCase.execute().block();

        // then
        assertThat(run.trigger()).isEqualTo(SyncTrigger.ARCHIVE);
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(runs.finished).hasSize(1);
    }

    @Test
    @DisplayName("현재상태가 비어 있어도 빈 스냅샷을 남긴다")
    void 비어_있어도_스냅샷을_남긴다() {
        // given, when
        var run = useCase.execute().block();

        // then — 그날 조직이 비었다는 사실 자체가 기록이다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("적재에 실패하면 FAILED 로 기록된다")
    void 실패는_FAILED로_기록된다() {
        // given
        var failing = new SnapshotArchiveUseCase(state, snapshots, runs,
                Clock.fixed(고정시각, ZoneOffset.UTC)) {
        };
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of())).block();

        // when — loadAll 이 실패하는 상황을 흉내내기 위해 상태를 직접 손상시키지 않고,
        // 스냅샷 저장이 실패하는 경우를 확인한다
        var run = failing.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }
}
```

> 네 번째 테스트는 fake 에 실패 훅이 없어 실패 경로를 태울 수 없다. **fake 에 훅을 추가하지 말고**, 이 테스트를 지우고 대신 `execute()` 의 `onErrorResume` 이 `SyncOutcome.failed` 를 만든다는 것을 코드 독해로 확인한 뒤 그 사실을 보고서에 적어라. 요구사항이 없는 케이스를 위해 fake 표면을 넓히는 것은 비용이다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :core:test --tests '*SnapshotArchiveUseCaseTest*'`

Expected: 컴파일 실패 — `SnapshotArchiveUseCase` 가 없다.

- [ ] **Step 3: 구현**

`SnapshotArchiveUseCase.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * SCIM 인스턴스의 하루 1회 스냅샷 아카이빙.
 *
 * <p>SCIM 은 push 라 diff 용 스냅샷이 필요 없다. 이 스냅샷은 감사와 수동 복구용이며,
 * LDAP 인스턴스와 같은 저장 구조를 쓰므로 사고 조사 때 두 인스턴스의 기록을 같은 방식으로 읽는다.
 *
 * <p>SCIM push 요청과 달리 이것은 배치이므로 {@code SyncRun} 을 기록한다 —
 * 새벽에 아카이빙이 실패했다면 그 사실이 남아야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class SnapshotArchiveUseCase {

    private final DirectoryStateRepository state;
    private final TupleSnapshotRepository snapshots;
    private final SyncRunRepository runs;
    private final Clock clock;

    public Mono<SyncRun> execute() {
        return runs.start(SyncSource.SCIM, SyncTrigger.ARCHIVE)
                .doOnNext(run -> log.info("[{}] 스냅샷 아카이빙 시작", run.runId()))
                .flatMap(run -> Mono.defer(this::archive)
                        .onErrorResume(error -> {
                            log.error("[{}] 스냅샷 아카이빙 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    private Mono<SyncOutcome> archive() {
        return state.loadAll().flatMap(directory -> {
            var mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            Instant now = clock.instant();
            TupleSnapshot snapshot = new TupleSnapshot(
                    SnapshotIds.generate(now, SyncSource.SCIM),
                    now,
                    SyncSource.SCIM,
                    mapping.tuples());

            return snapshots.save(snapshot)
                    .thenReturn(new SyncOutcome(dev.starryeye.organization.core.model.SyncStatus.SUCCEEDED,
                            mapping.tuples().size(), 0, 0, snapshot.id(), null));
        });
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run: `./gradlew :core:test`

Expected: 기존 `core` 테스트 전부 + 새 테스트 PASS.

`현재상태를_스냅샷으로_적재한다` 의 스냅샷 아이디가 `20260815T030000-SCIM` 이어야 한다 — `SnapshotIds.generate` 가 `SyncSource.SCIM` 을 받는지 확인한다. LDAP 을 넘기면 두 인스턴스의 스냅샷이 구분되지 않는다.

- [ ] **Step 5: 커밋**

커밋 메시지:

```
feat: SnapshotArchiveUseCase 추가 — 하루 1회 감사용 스냅샷

SCIM 은 push 라 diff 용 스냅샷이 필요 없다. 이건 감사·수동복구용이며
LDAP 과 같은 저장 구조를 써서 사고 조사 때 두 인스턴스의 기록을 같은
방식으로 읽을 수 있게 한다.

SCIM 요청과 달리 배치이므로 SyncRun 을 ARCHIVE 트리거로 기록한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 6: SCIM 엔드포인트 — 핸들러와 라우터

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java`
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimConfig.java`
- Create: `connector-scim/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`, `ScimGroupHandlerTest.java`

**Interfaces:**
- Consumes: Task 1~5 전부 — DTO, `ScimException`, `ScimPatchApplier`, `ScimMapper`, `IncrementalSyncUseCase`, `DirectoryStateRepository`
- Produces:
  - `ScimUserHandler(DirectoryStateRepository, IncrementalSyncUseCase)` — `create`, `get`, `replace`, `patch`, `delete`
  - `ScimGroupHandler(DirectoryStateRepository, IncrementalSyncUseCase)` — 같은 다섯
  - `ScimRouter.scimRoutes(ScimUserHandler, ScimGroupHandler)` → `RouterFunction<ServerResponse>`
  - `ScimConfig` — 세 빈 등록

**엔드포인트 (설계 §10).**

| 메서드 | 경로 | 비고 |
|---|---|---|
| POST | `/scim/v2/Users`, `/scim/v2/Groups` | 생성. 이미 있으면 409 `uniqueness` |
| GET | `/scim/v2/Users/{id}`, `/scim/v2/Groups/{id}` | 단건만. 목록은 미지원 |
| PUT | 〃 | 전체 교체 |
| PATCH | 〃 | `ScimPatchApplier` 경유 |
| DELETE | 〃 | 204 |
| GET | `/scim/v2/ServiceProviderConfig` | 지원 기능 광고 |

**부분 실패 → 500 (설계 §7.2).** `IncrementalSyncResult.fullyApplied()` 가 false 면 상태는 이미 커밋된 뒤이므로, 응답만 500 으로 돌려 IdP 가 재시도하게 한다. 재시도는 같은 최종 상태를 목표로 하므로 이미 반영된 부분은 다음 diff 에서 자연히 제외된다.

**에러 번역은 라우터에서 한 곳에.** 핸들러는 `ScimException` 을 던지기만 하고, 라우터의 필터가 그것을 SCIM Error 본문으로 바꾼다. 핸들러마다 try/catch 를 두면 형식이 어긋난다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ScimGroupHandlerTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupHandlerTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        var useCase = new IncrementalSyncUseCase(state, writer);
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase))).build();
    }

    @Test
    @DisplayName("조직을 생성하면 201 과 함께 SCIM Group 본문이 돌아온다")
    void 조직을_생성한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV001","displayName":"개발본부","members":[]}
                """;

        // when, then
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV001")
                .jsonPath("$.displayName").isEqualTo("개발본부")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.GROUP);

        assertThat(state.groups).containsKey("DEV001");
    }

    @Test
    @DisplayName("이미 있는 조직코드로 생성하면 409 uniqueness 로 거절한다")
    void 중복_생성은_409다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of())).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV001","displayName":"개발본부"}
                """;

        // when, then
        client.post().uri("/scim/v2/Groups")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("uniqueness")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("없는 조직을 조회하면 404 와 SCIM Error 본문이 돌아온다")
    void 없는_조직_조회는_404다() {
        // given, when, then
        client.get().uri("/scim/v2/Groups/DEV999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo("404")
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR)
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("DEV999"));
    }

    @Test
    @DisplayName("PATCH 로 멤버를 추가하면 튜플이 생성되고 200 이 돌아온다")
    void PATCH로_멤버를_추가한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.members[0].value").isEqualTo("kim");

        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("지원하지 않는 PATCH path 는 400 invalidPath 로 거절한다")
    void 지원하지_않는_path는_400이다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"emails[type eq \\"work\\"].value",
                                "value":"x@example.com"}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidPath");
    }

    @Test
    @DisplayName("조직을 삭제하면 204 를 돌려주고 튜플과 상태가 사라진다")
    void 조직을_삭제한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when, then
        client.delete().uri("/scim/v2/Groups/DEV002")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(state.groups).doesNotContainKey("DEV002");
        assertThat(writer.appliedDeltas.get(0).toDelete()).isNotEmpty();
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 500 을 돌려 IdP 가 재시도하게 한다")
    void 부분_실패는_500이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of())).block();
        writer.failFor(tuple -> true);
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members",
                                "value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo(ScimSchemas.ERROR);
    }

    @Test
    @DisplayName("ServiceProviderConfig 는 지원하지 않는 기능을 정직하게 광고한다")
    void 지원기능을_광고한다() {
        // given, when, then
        client.get().uri("/scim/v2/ServiceProviderConfig")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.patch.supported").isEqualTo(true)
                .jsonPath("$.filter.supported").isEqualTo(false)
                .jsonPath("$.bulk.supported").isEqualTo(false);
    }
}
```

`ScimUserHandlerTest.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimUserHandlerTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        var useCase = new IncrementalSyncUseCase(state, writer);
        client = WebTestClient.bindToRouterFunction(
                ScimRouter.scimRoutes(new ScimUserHandler(state, useCase),
                        new ScimGroupHandler(state, useCase))).build();
    }

    @Test
    @DisplayName("직원을 생성하면 201 과 함께 SCIM User 본문이 돌아온다")
    void 직원을_생성한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "externalId":"emp-1001","userName":"kim","displayName":"김철수",
                 "emails":[{"value":"kim@example.com","primary":true}],"active":true}
                """;

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("kim")
                .jsonPath("$.userName").isEqualTo("kim")
                .jsonPath("$.active").isEqualTo(true);

        assertThat(state.users).containsKey("kim");
    }

    @Test
    @DisplayName("userName 이 없으면 400 invalidSyntax 로 거절한다")
    void userName이_없으면_400이다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"displayName":"김철수"}
                """;

        // when, then
        client.post().uri("/scim/v2/Users")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidSyntax");
    }

    @Test
    @DisplayName("PATCH 로 비활성화하면 소속 조직의 튜플이 사라진다")
    void 비활성화가_튜플을_지운다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"replace","path":"active","value":false}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Users/kim")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);

        assertThat(writer.appliedDeltas.get(0).toDelete()).isNotEmpty();
    }

    @Test
    @DisplayName("직원을 삭제하면 204 를 돌려주고 소속 조직에서도 빠진다")
    void 직원을_삭제한다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when, then
        client.delete().uri("/scim/v2/Users/kim")
                .exchange()
                .expectStatus().isNoContent();

        assertThat(state.users).doesNotContainKey("kim");
        assertThat(state.groups.get("DEV002").members()).isEmpty();
    }

    @Test
    @DisplayName("PUT 은 리소스를 통째로 교체한다")
    void PUT은_전체를_교체한다() {
        // given
        state.saveUser(new DirectoryUser("kim", "emp-1001", "kim", "김철수",
                "old@example.com", true)).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                 "userName":"kim","displayName":"김철수","
                 emails":[{"value":"new@example.com","primary":true}],"active":true}
                """.replace("\\"\n                 emails", "\\",\"emails");

        // when, then
        client.put().uri("/scim/v2/Users/kim")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        assertThat(state.users.get("kim").email()).isEqualTo("new@example.com");
    }
}
```

> `PUT은_전체를_교체한다` 의 본문 문자열은 위처럼 꼬아 쓰지 말고, 한 줄짜리 정상 JSON 으로 작성하라. 텍스트 블록에서 이스케이프를 다투는 것은 테스트의 요지가 아니다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :connector-scim:test --tests '*Handler*'`

Expected: 컴파일 실패 — 핸들러와 라우터가 없다.

- [ ] **Step 3: 유저 핸들러 구현**

`ScimUserHandler.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class ScimUserHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(ScimUser.class)
                .map(ScimMapper::toDirectoryUser)
                .flatMap(user -> state.findUser(user.id())
                        .flatMap(existing -> Mono.<DirectoryUser>error(ScimException.uniqueness(
                                "이미 존재하는 직원입니다: " + user.id())))
                        .switchIfEmpty(Mono.just(user)))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .flatMap(user -> ServerResponse.ok().bodyValue(ScimMapper.toScimUser(user)));
    }

    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimUser.class))
                .map(ScimMapper::toDirectoryUser)
                // PUT 은 경로의 id 를 정본으로 삼는다. 본문의 userName 이 달라도 리소스를 옮기지 않는다.
                .map(user -> new DirectoryUser(id, user.externalId(), user.userName(),
                        user.displayName(), user.email(), user.active()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class))
                .map(both -> ScimPatchApplier.applyToUser(both.getT1(), both.getT2()))
                .flatMap(user -> sync.upsertUser(user)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findUser(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("직원을 찾을 수 없습니다: " + id)))
                .then(sync.removeUser(id))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal(
                                "일부 튜플 삭제에 실패했습니다. 재시도해 주세요: " + id)));
    }

    /**
     * 부분 실패면 상태는 이미 커밋됐지만 응답은 5xx 로 돌려 IdP 가 재시도하게 한다(설계 §7.2).
     * 재시도는 같은 최종 상태를 목표로 하므로 이미 반영된 부분은 다음 diff 에서 자연히 제외된다.
     */
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.internal(
                    "일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id));
        }
        return state.findUser(id)
                .flatMap(saved -> ServerResponse.status(status).bodyValue(ScimMapper.toScimUser(saved)));
    }
}
```

- [ ] **Step 4: 그룹 핸들러 구현**

`ScimGroupHandler.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class ScimGroupHandler {

    private final DirectoryStateRepository state;
    private final IncrementalSyncUseCase sync;

    public Mono<ServerResponse> create(ServerRequest request) {
        return request.bodyToMono(ScimGroup.class)
                .map(ScimMapper::toDirectoryGroup)
                .flatMap(group -> state.findGroup(group.id())
                        .flatMap(existing -> Mono.<DirectoryGroup>error(ScimException.uniqueness(
                                "이미 존재하는 조직입니다: " + group.id())))
                        .switchIfEmpty(Mono.just(group)))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.CREATED, group.id(), result)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .flatMap(group -> ServerResponse.ok().bodyValue(ScimMapper.toScimGroup(group)));
    }

    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .then(request.bodyToMono(ScimGroup.class))
                .map(ScimMapper::toDirectoryGroup)
                // 경로의 조직코드가 정본이다. 본문의 externalId 가 달라도 리소스를 옮기지 않는다.
                .map(group -> new DirectoryGroup(id, group.externalId(),
                        group.displayName(), group.members()))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .zipWith(request.bodyToMono(ScimPatchOp.class))
                .map(both -> ScimPatchApplier.applyToGroup(both.getT1(), both.getT2()))
                .flatMap(group -> sync.upsertGroup(group)
                        .flatMap(result -> respond(HttpStatus.OK, id, result)));
    }

    public Mono<ServerResponse> delete(ServerRequest request) {
        String id = request.pathVariable("id");
        return state.findGroup(id)
                .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id)))
                .then(sync.removeGroup(id))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal(
                                "일부 튜플 삭제에 실패했습니다. 재시도해 주세요: " + id)));
    }

    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.internal(
                    "일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id));
        }
        return state.findGroup(id)
                .flatMap(saved -> ServerResponse.status(status).bodyValue(ScimMapper.toScimGroup(saved)));
    }
}
```

- [ ] **Step 5: 라우터와 에러 번역 구현**

`ScimRouter.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.scim.dto.ScimError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PATCH;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;

/**
 * SCIM 2.0 라우팅. 에러 번역은 여기 한 곳에서만 한다 —
 * 핸들러마다 try/catch 를 두면 응답 형식이 어긋난다.
 */
@Slf4j
public final class ScimRouter {

    private ScimRouter() {
    }

    public static RouterFunction<ServerResponse> scimRoutes(ScimUserHandler users,
                                                            ScimGroupHandler groups) {
        return RouterFunctions.route()
                .POST("/scim/v2/Users", users::create)
                .GET("/scim/v2/Users/{id}", users::get)
                .PUT("/scim/v2/Users/{id}", users::replace)
                .PATCH("/scim/v2/Users/{id}", users::patch)
                .DELETE("/scim/v2/Users/{id}", users::delete)
                .POST("/scim/v2/Groups", groups::create)
                .GET("/scim/v2/Groups/{id}", groups::get)
                .PUT("/scim/v2/Groups/{id}", groups::replace)
                .PATCH("/scim/v2/Groups/{id}", groups::patch)
                .DELETE("/scim/v2/Groups/{id}", groups::delete)
                .GET("/scim/v2/ServiceProviderConfig", request -> serviceProviderConfig())
                .onError(Throwable.class, ScimRouter::toScimError)
                .build();
    }

    private static Mono<ServerResponse> toScimError(Throwable error, org.springframework.web.reactive.function.server.ServerRequest request) {
        if (error instanceof ScimException scim) {
            return write(scim.getStatus(), scim.getScimType(), scim.getMessage());
        }
        // 본문 파싱 실패 등 SCIM 이 모르는 예외는 400 으로 번역한다.
        if (error instanceof org.springframework.core.codec.DecodingException
                || error instanceof org.springframework.web.server.ServerWebInputException) {
            return write(HttpStatus.BAD_REQUEST, "invalidSyntax", "요청 본문을 해석할 수 없습니다");
        }
        log.error("SCIM 요청 처리 중 예기치 않은 오류", error);
        return write(HttpStatus.INTERNAL_SERVER_ERROR, null, "내부 오류가 발생했습니다");
    }

    private static Mono<ServerResponse> write(HttpStatus status, String scimType, String detail) {
        ScimError body = new ScimError(List.of(ScimSchemas.ERROR),
                String.valueOf(status.value()), scimType, detail);
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    /**
     * 지원하지 않는 기능을 정직하게 광고한다. 여기서 filter 를 지원한다고 하면
     * IdP 가 필터 질의를 보내기 시작하고, 우리는 그것을 처리할 수 없다.
     */
    private static Mono<ServerResponse> serviceProviderConfig() {
        Map<String, Object> config = Map.of(
                "schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG),
                "patch", Map.of("supported", true),
                "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter", Map.of("supported", false, "maxResults", 0),
                "changePassword", Map.of("supported", false),
                "sort", Map.of("supported", false),
                "etag", Map.of("supported", false),
                "authenticationSchemes", List.of());
        return ServerResponse.ok().bodyValue(config);
    }
}
```

- [ ] **Step 6: 설정과 자동설정 등록**

`ScimConfig.java`:

```java
package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ScimConfig {

    @Bean
    public ScimUserHandler scimUserHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync) {
        return new ScimUserHandler(state, sync);
    }

    @Bean
    public ScimGroupHandler scimGroupHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync) {
        return new ScimGroupHandler(state, sync);
    }

    @Bean
    public RouterFunction<ServerResponse> scimRouterFunction(ScimUserHandler users, ScimGroupHandler groups) {
        return ScimRouter.scimRoutes(users, groups);
    }
}
```

`connector-scim/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
dev.starryeye.organization.scim.ScimConfig
```

- [ ] **Step 7: 테스트가 통과하는지 확인**

Run: `./gradlew :connector-scim:build`

Expected: `ScimSerializationTest` 5 + `ScimPatchApplierTest` 13 + `ScimMapperTest` 11 + `ScimGroupHandlerTest` 8 + `ScimUserHandlerTest` 5 = 42개 PASS.

`중복_생성은_409다` 가 500 을 내면 `switchIfEmpty` 안에서 던진 에러가 `onError` 필터까지 도달하는지 확인한다. `부분_실패는_500이다` 가 200 을 내면 `respond` 가 `fullyApplied()` 를 보지 않는 것이다.

- [ ] **Step 8: 커밋**

커밋 메시지:

```
feat: SCIM 2.0 엔드포인트 추가 — Users, Groups, ServiceProviderConfig

에러 번역을 라우터 한 곳에서만 한다 — 핸들러마다 try/catch 를 두면
응답 형식이 어긋난다.

부분 실패는 500 으로 돌려 IdP 가 재시도하게 한다(설계 §7.2). 상태는
이미 커밋된 뒤이므로, 재시도는 같은 최종 상태를 목표로 하고 이미 반영된
부분은 다음 diff 에서 자연히 제외된다.

ServiceProviderConfig 는 filter/bulk 를 지원하지 않는다고 정직하게
광고한다 — 지원한다고 하면 IdP 가 필터 질의를 보내기 시작한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## Task 7: app-scim 조립, 아카이빙 스케줄러, End-to-End

**Files:**
- Create: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimUseCaseConfig.java`
- Create: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ArchiveScheduler.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncApplication.java` (`@EnableScheduling`)
- Modify: `app-scim/src/main/resources/application.yml`
- Modify: `app-scim/build.gradle` (테스트 의존성)
- Create: `app-scim/src/test/resources/application-test.yml`
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimEndToEndTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 4~6 전부와 세 어댑터
- Produces: 동작하는 SCIM 인스턴스 (포트 8082)

**앱 클래스 위치.** 선행 계획의 Task 1 이 `ScimSyncApplication` 을 `dev.starryeye.organization.scim.app` 에 만들었고 `scanBasePackages = "dev.starryeye.organization"` 이 붙어 있다. 그대로 쓴다.

**설정.** 최종 리뷰 수정 웨이브에서 `app-scim/application.yml` 에 `dynamodb`/`openfga` 블록이 이미 추가됐다(그게 없으면 실제 AWS 로 부팅한다). 여기에 `sync` 블록만 더한다 — SCIM 인스턴스에는 `deletion-guard` 가 없다. 삭제 가드는 LDAP 전체 동기화 전용이고, SCIM 의 삭제는 의도된 단건 삭제다(설계 §9.2).

- [ ] **Step 1: 유스케이스 조립과 스케줄러 작성**

`ScimUseCaseConfig.java`:

```java
package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ArchiveProperties.class)
public class ScimUseCaseConfig {

    @Bean
    public IncrementalSyncUseCase incrementalSyncUseCase(DirectoryStateRepository state,
                                                          RelationTupleWriter writer) {
        return new IncrementalSyncUseCase(state, writer);
    }

    @Bean
    public SnapshotArchiveUseCase snapshotArchiveUseCase(DirectoryStateRepository state,
                                                          TupleSnapshotRepository snapshots,
                                                          SyncRunRepository runs,
                                                          Clock clock) {
        return new SnapshotArchiveUseCase(state, snapshots, runs, clock);
    }
}
```

`ArchiveProperties.java`:

```java
package dev.starryeye.organization.scim.app;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("sync")
public class ArchiveProperties {

    private String archiveCron = "0 0 3 * * *";
    private String purgeCron = "0 0 4 * * *";
}
```

`ArchiveScheduler.java`:

```java
package dev.starryeye.organization.scim.app;

import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveScheduler {

    private final SnapshotArchiveUseCase archive;
    private final TupleSnapshotRepository snapshots;

    /**
     * {@code Mono.defer} 로 감싸는 이유: 유스케이스가 Mono 를 반환하기 전에 동기 예외를 던지면
     * 그것이 스케줄러 메서드 밖으로 새어나가 아래 에러 컨슈머를 건너뛴다. 그러면 실패가
     * 로그에 남지 않는다.
     */
    @Scheduled(cron = "${sync.archive-cron}")
    public void 스냅샷아카이빙() {
        Mono.defer(archive::execute)
                .subscribe(
                        run -> log.info("스냅샷 아카이빙 완료: status={} snapshotId={}",
                                run.status(), run.snapshotId()),
                        error -> log.error("스냅샷 아카이빙이 예기치 않게 실패했다", error));
    }

    /** DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다. */
    @Scheduled(cron = "${sync.purge-cron}")
    public void 만료스냅샷정리() {
        Mono.defer(snapshots::purgeExpired)
                .subscribe(
                        count -> log.info("만료 스냅샷 정리 완료: {}건", count),
                        error -> log.error("만료 스냅샷 정리에 실패했다", error));
    }
}
```

`ScimSyncApplication.java` 에 `@EnableScheduling` 을 추가한다:

```java
package dev.starryeye.organization.scim.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = "dev.starryeye.organization")
public class ScimSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScimSyncApplication.class, args);
    }
}
```

- [ ] **Step 2: 설정에 sync 블록 추가**

`app-scim/src/main/resources/application.yml` 의 기존 `openfga`/`dynamodb` 블록은 그대로 두고, `spring` 블록 아래에 다음을 추가한다. **`deletion-guard` 는 넣지 않는다** — 삭제 가드는 LDAP 전용이다(설계 §9.2).

```yaml
sync:
  archive-cron: "0 0 3 * * *"
  purge-cron: "0 0 4 * * *"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

logging:
  level:
    dev.starryeye.organization: DEBUG
```

- [ ] **Step 3: 테스트 의존성 추가**

`app-scim/build.gradle` 의 `dependencies` 블록에 다음 세 줄을 더한다. Testcontainers 와 OpenFGA SDK 는 E2E 에서 컨테이너를 띄우고 `Check` 로 검증하는 데 필요하다 — `authz-openfga` 가 `implementation` 으로 선언해 전이되지 않는다.

```groovy
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'

    testImplementation libs.testcontainers.junit
    testImplementation libs.openfga.sdk
```

- [ ] **Step 4: 테스트 설정 작성**

`app-scim/src/test/resources/application-test.yml`:

```yaml
sync:
  archive-cron: "-"
  purge-cron: "-"

dynamodb:
  region: ap-northeast-2
  table-name: organization-scim-e2e
  create-table-on-startup: true

openfga:
  store-name: organization-scim-e2e
```

`"-"` 는 Spring 의 "이 스케줄을 비활성화한다" 표기다. 테스트 도중 스케줄러가 제멋대로 도는 것을 막는다.

- [ ] **Step 5: End-to-End 테스트 작성**

`ScimEndToEndTest.java`:

```java
package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.usecase.SnapshotArchiveUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCIM 요청 → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간을 실제 컨테이너 위에서 확인한다.
 *
 * <p>테스트는 순서에 의존한다. 앞선 테스트가 만든 상태 위에서 다음 테스트가 변경을 가한다 —
 * SCIM 이 push 모델이라는 사실 자체가 그런 순차성을 전제하기 때문이다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimEndToEndTest {

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
    @Autowired DirectoryStateRepository state;
    @Autowired TupleSnapshotRepository snapshots;
    @Autowired SnapshotArchiveUseCase archive;

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("직원과 조직을 만들고 멤버로 넣으면 OpenFGA 에 소속이 반영된다")
    void 직원과_조직을_만들고_연결한다() {
        // given, when — 직원 둘
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"kim","displayName":"김철수","active":true}
                        """)
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"park","displayName":"박민수","active":true}
                        """)
                .exchange().expectStatus().isCreated();

        // when — 조직 둘, 하위 조직과 직원을 멤버로
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"kim","type":"User"}]}
                        """)
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"개발본부",
                         "members":[{"value":"DEV002","type":"Group"},
                                    {"value":"park","type":"User"}]}
                        """)
                .exchange().expectStatus().isCreated();

        // then — 직속 소속
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        // then — 하위 조직을 통한 롤업. 이것이 인가 모델의 존재 이유다
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
        // then — 상속은 상위로만 향한다. 상위 직속인 park 은 하위 조직의 멤버가 아니다
        assertThat(check("user:park", "member", "group:DEV002")).isFalse();

        var loaded = state.loadAll().block();
        assertThat(loaded.users()).containsOnlyKeys("kim", "park");
        assertThat(loaded.groups().get("DEV001").displayName()).isEqualTo("개발본부");
    }

    @Test
    @Order(2)
    @DisplayName("PATCH 로 멤버를 빼면 그 소속만 사라지고 나머지는 남는다")
    void PATCH로_멤버를_뺀다() {
        // given, when
        client.patch().uri("/scim/v2/Groups/DEV002").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"remove","path":"members[value eq \\"kim\\"]"}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(check("user:kim", "member", "group:DEV002")).isFalse();
        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("직원을 비활성화하면 남은 소속의 튜플도 사라진다")
    void 비활성화가_소속을_지운다() {
        // given, when
        client.patch().uri("/scim/v2/Users/park").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":false}]}
                        """)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false);

        // then — 비활성 직원에게 권한이 남지 않는다
        assertThat(check("user:park", "member", "group:DEV001")).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("직원을 다시 활성화하면 소속이 되살아난다")
    void 재활성화가_소속을_되살린다() {
        // given, when
        client.patch().uri("/scim/v2/Users/park").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"replace","path":"active","value":true}]}
                        """)
                .exchange().expectStatus().isOk();

        // then
        assertThat(check("user:park", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("조직을 삭제하면 상위 조직에서의 연결도 함께 끊긴다")
    void 조직을_삭제한다() {
        // given — DEV002 는 DEV001 의 하위 조직이다
        // when
        client.delete().uri("/scim/v2/Groups/DEV002")
                .exchange().expectStatus().isNoContent();

        // then
        assertThat(state.loadAll().block().groups()).doesNotContainKey("DEV002");
        assertThat(state.loadAll().block().groups().get("DEV001").members())
                .noneMatch(member -> member.id().equals("DEV002"));
    }

    @Test
    @Order(6)
    @DisplayName("없는 리소스를 조회하면 SCIM Error 스키마로 404 가 돌아온다")
    void 없는_리소스는_SCIM_에러다() {
        // given, when, then
        client.get().uri("/scim/v2/Groups/DEV999")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.schemas[0]").isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error")
                .jsonPath("$.status").isEqualTo("404");
    }

    @Test
    @Order(7)
    @DisplayName("아카이빙은 현재상태를 SCIM 소스 스냅샷으로 남긴다")
    void 아카이빙이_스냅샷을_남긴다() {
        // given — 앞선 테스트들이 만든 상태가 남아 있다
        // when
        var run = archive.execute().block();

        // then
        assertThat(run.status().name()).isEqualTo("SUCCEEDED");
        var snapshot = snapshots.findLatest().block();
        assertThat(snapshot.source().name()).isEqualTo("SCIM");
        assertThat(snapshot.id()).endsWith("-SCIM");
        assertThat(snapshot.tuples())
                .anyMatch(tuple -> tuple.object().equals("group:DEV001"));
    }

    @Test
    @Order(8)
    @DisplayName("헬스체크가 DynamoDB 와 OpenFGA 연결을 모두 UP 으로 보고한다")
    void 헬스체크가_UP이다() {
        // given, when, then
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components.dynamoDb.status").isEqualTo("UP")
                .jsonPath("$.components.openFga.status").isEqualTo("UP");
    }
}
```

- [ ] **Step 6: 테스트 실행**

Run: `./gradlew :app-scim:test`

Expected: 8개 테스트 모두 PASS.

`직원과_조직을_만들고_연결한다` 의 롤업이 실패하면 `IncrementalSyncUseCase` 가 `child` 튜플을 만들었는지 확인한다. `상속은_상위로만` 단언이 실패하면 `RelationTuple.child` 의 인자 순서가 뒤집힌 것이다.

`비활성화가_소속을_지운다` 가 실패하면 `IncrementalSyncUseCase.upsertUser` 의 override 처리를 확인한다 — 변경 후 스냅샷은 아직 저장되지 않은 유저 값을 써야 한다.

- [ ] **Step 7: 전체 빌드 확인**

Run: `./gradlew clean build`

Expected: 전 모듈 `BUILD SUCCESSFUL`. LDAP 계획의 기존 테스트가 하나도 깨지지 않아야 한다 — 이 계획은 `core` 에 유스케이스 두 개를 **추가**할 뿐 기존 파일을 수정하지 않는다.

- [ ] **Step 8: README 갱신**

`README.md` 의 모듈 표에서 `connector-scim` 과 `app-scim` 의 "별도 계획에서 구현" 표기를 지우고, 다음 내용을 담은 SCIM 절을 추가한다.

- SCIM 은 push 모델이라 전체를 읽지 않는다. 리소스 하나의 변경만 오므로 **영향 범위만 담은 최소 스냅샷**을 변경 전후로 만들어 diff 한다.
- 지원 엔드포인트 표 (Users/Groups × POST/GET/PUT/PATCH/DELETE, ServiceProviderConfig). **목록 GET 과 필터는 지원하지 않는다.**
- PATCH 지원 범위 — `members` 의 add/remove/replace 와 `members[value eq "..."]` 한 가지 필터 패턴. 그 외 path 는 400 으로 거절한다.
- SCIM push 요청은 `SyncRun` 에 기록하지 않는다. 요청 단위 이력이 폭증하기 때문이며, 남는 것은 하루 1회 아카이빙(`trigger=ARCHIVE`)뿐이다.
- 실행: `./gradlew :app-scim:bootRun` (포트 8082). LDAP 인스턴스와 **같은 테이블·store 를 동시에 쓰지 않는다** — 한 조직도는 하나의 소스로만 동기화한다(설계 §1 전제). 둘 다 돌리려면 `dynamodb.table-name` 과 `openfga.store-name` 을 다르게 설정한다.

- [ ] **Step 9: 커밋**

커밋 메시지:

```
feat: app-scim 조립과 SCIM end-to-end 테스트 추가

SCIM 요청 → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간을 실제 컨테이너
위에서 확인한다. 롤업과 그 반대 방향, 비활성화·재활성화, 조직 삭제 시
상위 연결 해제까지 단언한다.

SCIM 인스턴스에는 deletion-guard 설정이 없다 — 삭제 가드는 LDAP 전체
동기화 전용이고 SCIM 의 삭제는 의도된 단건 삭제다(설계 §9.2).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## 완료 조건

- `./gradlew clean build` 가 전 모듈에서 통과하고, LDAP 계획의 기존 테스트가 하나도 깨지지 않는다
- `docker compose up -d && ./gradlew :app-scim:bootRun` 으로 SCIM 인스턴스가 뜬다
- `POST /scim/v2/Groups` 로 만든 조직에 `PATCH` 로 직원을 넣으면 `Check(user:kim, member, group:상위조직)` 이 롤업으로 true 가 된다
- 직원을 `active: false` 로 바꾸면 모든 소속 튜플이 사라지고, 되돌리면 되살아난다
- 조직을 삭제하면 상위 조직에서의 `child` 튜플까지 함께 사라진다
- 지원하지 않는 PATCH path 가 400 `invalidPath` 로 거절되고, 조용히 무시되지 않는다
- 부분 실패가 5xx 로 보고되어 IdP 가 재시도할 수 있다
- 프로덕션 코드 어디에도 OpenFGA read/check 호출이 없다
- SCIM 요청 처리 경로에서 `SyncRunRepository` 를 호출하지 않는다

## 이 계획이 다루지 않는 것

- **인증** — SCIM 엔드포인트가 열려 있다. 설계 §1 이 비목표로 명시했다. IdP 연동 전에 Bearer 토큰 검증이 반드시 필요하다.
- **SCIM 필터·페이징** — `GET /Users`, `GET /Groups` 목록은 지원하지 않는다.
- **Enterprise User 확장** — `employeeNumber` 등을 파싱하지 않는다. 사번을 직원 아이디로 쓰려면 이 확장이 필요하다.
- **LDAP 과 SCIM 동시 운용** — 한 조직도는 하나의 소스로만 동기화한다.
- **admin 조회 API** — 직원 아이디·조직코드·조직명 기준 검색. GSI 키는 이미 준비돼 있어 백필이 필요 없다.
