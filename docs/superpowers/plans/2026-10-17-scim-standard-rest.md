# SCIM 표준 잔여 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 클라이언트가 준 값이 DynamoDB 키 한도를 넘어도 500 이 나지 않게 한다. 쓰기는 400 으로 먼저 막고, 읽기는 "없음" 으로 답한다. 그리고 `/Schemas`·`/ResourceTypes` 를 RFC 7644 §4 대로 연다.

**Architecture:**
- core 가 키에 들어갈 값의 한도를 한 규칙(`KeyValueLimits`)으로 정한다. 쓰기 유스케이스가 표지판·OpenFGA 전에 그 규칙을 본다.
- 저장소 어댑터는 `Keys` 의 한도 도우미로 최종 키 문자열을 재고, 넘으면 DynamoDB 를 부르지 않고 빈 결과를 준다.
- connector-scim 은 정적 발견 자원을 라우터에 단다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor 3.7, AWS SDK v2 DynamoDB(Async), Jackson, JUnit 5, AssertJ, Testcontainers(DynamoDB Local·OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-17-scim-standard-rest-design.md`

## Global Constraints

- **쓰기 한도(§3).** 모두 UTF-8 바이트이고, 넘으면 400 `invalidValue` 다. 요청은 아무것도 쓰지 않는다 — 표지판·OpenFGA·DynamoDB 어디에도.
  - `userName`·직원 `displayName`·조직 `displayName`: `Locale.ROOT` 소문자로 만든 뒤 1024바이트.
  - 직원·조직 `externalId`: 값 그대로 1024바이트. 공백뿐인 값도 잰다.
  - 넣는 멤버의 아이디: 1000바이트.
- **오류 문구.** 지금 형식 그대로 `"%s 이(가) %d바이트를 넘습니다"` 다. 속성 이름과 한도를 싣고, 값은 싣지 않는다.
- **검사 자리.** 쓰기 락 안, 표지판 차지 전, OpenFGA 전이다. 대상은 `createUser`·`changeUser`(바꾼 결과)·`upsertUser`·`createGroup`·`changeGroup`(바꾼 이름표 + 넣는 멤버)·`upsertGroup` 이다. 빼는 멤버는 보지 않는다. LDAP 경로는 보지 않는다.
- **읽기 한도(§4).** DynamoDB 파티션키 2048, 정렬키 1024바이트다.
  - **최종 키 문자열**(접두어·소문자 변환 포함)을 잰다.
  - 넘으면 DynamoDB 요청을 하나도 보내지 않고 없음(빈 `Mono`·`Flux`·`Page.empty()`)이다.
  - 묶음 읽기는 넘는 아이디만 뺀다.
- **발견(§5).**
  - GET 만 받는다(다른 메서드는 405).
  - 목록은 ListResponse(`startIndex` 1)다.
  - 단건 이름·URN 은 대소문자를 가리지 않고, 모르면 404 SCIM 오류다.
  - `filter` 쿼리 파라미터가 있으면 403 SCIM 오류다. 다른 쿼리 파라미터는 무시한다.
  - 스키마에는 응답 DTO 와 같은 속성만 담는다. 공통 속성(`id`·`externalId`·`meta`)과 enterprise 확장은 담지 않는다.
  - `Bulk`·`/Me` 는 501 그대로다.
- **바뀌지 않는 것.** 테이블·GSI·OpenFGA 모델, `ServiceProviderConfig`, 칸이 다 나온 뒤 중복 키의 500.
- **테스트.**
  - 새 테스트는 `// given`·`// when`·`// then` 표지를 단다(합친 표지도 된다). `@DisplayName` 은 한국어 문장이다.
  - 서브에이전트는 해당 모듈 테스트만 돌린다. 전체 `test`·`scaleTest` 는 컨트롤러가 돌린다. Gradle 은 한 번에 하나만 돌린다.
- **git.** `git stash` 를 쓰지 않는다. 커밋 메시지 끝에 `Co-Authored-By:` 줄을 단다.

## Review Focus

1. **소문자로 바꾸면 길어지는 글자**: U+0130 `İ` 는 2바이트지만 소문자 `i̇` 는 3바이트다. 원문은 한도 안인데 키는 넘는 값은 쓰기면 400, 읽기면 없음이어야 한다. → Task 1 `소문자로_길어지는_값도_잰다`, Task 2 `소문자로_길어지는_접두어도_잰다`.
2. **`displayName` 없이 긴 `name.formatted` 만 온 PUT**: 저장할 표시명이 `name.formatted` 로 정해지므로 400 이어야 한다. → Task 4 `표시명이_이름에서_정해져도_한도를_본다`.
3. **멤버 목록에 긴 아이디가 하나 섞인 조직 PUT**: 묶음 읽기가 500 이 아니라 그 하나만 "없음" 으로 보고, 넣는 멤버라 400 이어야 한다. → Task 2 `묶음_읽기는_넘는_아이디만_뺀다`, Task 4 `긴_멤버가_섞인_교체는_400이다`.
4. **긴 멤버를 빼는 PATCH**: 멤버가 아닌 것을 빼는 것과 같아 204 이고 아무것도 바뀌지 않아야 한다. → Task 1 `빼는_멤버는_보지_않는다`, Task 4 `긴_멤버_빼기는_204다`.
5. **`/Schemas/{URN}` 의 대소문자, 발견 요청의 `filter`·`attributes`**: URN 은 대소문자 무시, `filter` 는 403, `attributes` 는 무시(전체 자원)여야 한다. → Task 3 `URN_은_대소문자를_가리지_않는다`, `filter_가_오면_403이다`, `다른_쿼리_파라미터는_무시한다`.

---

### Task 1: core — 키에 들어갈 값의 한도를 쓰기 전에 본다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/KeyValueLimits.java`
- Rename: `core/src/main/java/dev/starryeye/organization/core/usecase/UniqueValueTooLongException.java` → `ValueTooLongException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/UniqueMarkers.java` (새 예외를 쓴다)
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/GroupChange.java` (`added()`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (검사 여섯 곳)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java` (예외 번역 분기의 타입 이름)
- Test: `core/src/test/java/dev/starryeye/organization/core/model/KeyValueLimitsTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncKeyLimitTest.java`
- Modify(test): `core/src/test/java/dev/starryeye/organization/core/usecase/UniqueMarkersTest.java`, `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimErrorTranslationTest.java` (이름 바꿈)

**Interfaces:**
- Produces:
  - `KeyValueLimits` (final, static 만):
    - `public static final int MAX_INDEXED_BYTES = UniqueKind.MAX_KEY_BYTES;`(1024)
    - `public static final int MAX_MEMBER_ID_BYTES = 1000;`
    - `public record Over(String attribute, int limitBytes) {}`
    - `public static Optional<Over> over(DirectoryUser user)` — `userName`·`displayName` 은 소문자 뒤, `externalId` 는 그대로 잰다. 먼저 넘는 것 하나를 준다(순서: `userName`, `displayName`, `externalId`). `null` 은 넘지 않는다.
    - `public static Optional<Over> over(GroupHeader header)` — `displayName`(소문자 뒤), `externalId`(그대로).
    - `public static Optional<Over> overMembers(Collection<MemberRef> members)` — 아이디 하나라도 1000바이트를 넘으면 `Over("members", 1000)`.
  - `ValueTooLongException extends RuntimeException`: 생성자 `ValueTooLongException(String attribute, int limitBytes)`, 메시지 `"%s 이(가) %d바이트를 넘습니다"`, 접근자 `attribute()`·`limitBytes()`. 자바독은 옛 클래스의 "같은 요청은 다시 보내도 늘 넘으므로 400 invalidValue" 를 키 한도 전체로 넓힌다.
  - `GroupChange.added()` → `Set<MemberRef>`: 넣는 멤버 — 교체면 `base` 전부, 그리고 `Add` 연산의 멤버.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`KeyValueLimitsTest`:
- `한도_바이트까지는_넘지_않는다`: `userName`·`displayName`·`externalId` 가 정확히 1024바이트(ASCII)인 직원 → `over` 비어 있음. 아이디 1000바이트 멤버 → `overMembers` 비어 있음.
- `한_바이트_넘으면_그_속성을_준다`: `displayName` 만 1025바이트 → `Over("displayName", 1024)`. `externalId` 만 1025 → `Over("externalId", 1024)`. 아이디 1001바이트 멤버 → `Over("members", 1000)`.
- `소문자로_길어지는_값도_잰다`: `"İ".repeat(512)`(원문 1024바이트, 소문자 1536바이트)을 `userName` 으로 → `Over("userName", 1024)`. 같은 값을 조직 `displayName` 으로 → `Over("displayName", 1024)`.
- `공백뿐인_externalId_도_잰다`: `" ".repeat(1025)` → `Over("externalId", 1024)`.
- `없는_값은_넘지_않는다`: `displayName`·`externalId` 가 `null` 인 직원·조직 → 비어 있음.

`IncrementalSyncKeyLimitTest`(가짜 저장소·가짜 OpenFGA·가짜 Check, `IncrementalSyncAuditTest` 의 준비를 따른다). 모든 400 시험은 `ValueTooLongException` 의 `attribute()` 와 함께 다음을 단언한다.
- `state.users`·`state.groups` 가 그대로다.
- `writer.appliedDeltas` 가 비어 있다.
- `state.uniqueMarkers` 에 새 표지판이 없다.

시험:
- `긴_표시명의_직원_생성은_아무것도_쓰지_않는다` — `displayName` 1025바이트로 `createUser`.
- `바꾼_결과가_넘는_직원_변경은_아무것도_쓰지_않는다` — 있는 직원을 `changeUser` 로 `externalId` 1025바이트로.
- `긴_표시명의_조직_생성은_OpenFGA_를_쓰지_않는다` — 있는 직원을 멤버로 둔 조직을 `displayName` 1025바이트로 `createGroup`.
- `공백뿐인_긴_externalId_의_조직은_표지판도_차지하지_않는다` — `createGroup` 의 `externalId` 가 `" ".repeat(1025)`.
- `긴_멤버를_넣는_조직_변경은_아무것도_쓰지_않는다` — `changeGroup(id, GroupChange.delta().adding(Set.of(MemberRef.user("u".repeat(1001)))))`.
- `빼는_멤버는_보지_않는다` — `changeGroup(id, GroupChange.delta().removingId("u".repeat(1001)))` 가 오류 없이 끝나고 조직 멤버가 그대로다.
- `한도_바이트의_값은_그대로_쓴다` — `displayName` 정확히 1024바이트로 `createUser` 하면 저장된다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*KeyValueLimitsTest*' --tests '*IncrementalSyncKeyLimitTest*'`
Expected: 컴파일 실패(`KeyValueLimits`·`ValueTooLongException`·`GroupChange.added()` 없음).

- [ ] **Step 3: 구현한다**

- `KeyValueLimits` 를 만든다.
- 예외 이름을 바꾸고, `UniqueMarkers` 의 길이 검사가 `new ValueTooLongException(이름(종류), UniqueKind.MAX_KEY_BYTES)` 를 던지게 한다. 그 검사는 포트 계약이라 남긴다.
- `GroupChange.added()` 를 더한다.
- `IncrementalSyncUseCase` 에 private 도우미 `Mono<Void> 한도를_본다(Optional<KeyValueLimits.Over> 넘음)`(넘으면 `Mono.error(new ValueTooLongException(...))`)를 두고 여섯 곳에서 부른다.
  - `createUser`: 표지판 차지 앞에서 `over(user)`.
  - `changeUser`: `after` 를 계산한 직후, 표지판·`일` 앞에서 `over(after)`.
  - `upsertUser`: 맨 앞에서 `over(user)`.
  - `createGroup`: 표지판 차지 앞에서 이름표 `over(...)` 와 `overMembers(group.members())`.
  - `changeGroupInternal`: `바뀐헤더` 를 계산한 직후, 표지판·`일` 앞에서 `over(바뀐헤더)` 와 `overMembers(change.added())`.
  - `upsertGroup`: 맨 앞에서 이름표·멤버.
- `ScimRouter` 의 `UniqueValueTooLongException` 분기를 `ValueTooLongException` 으로 바꾼다(400 `invalidValue`, 문구 그대로). 주석은 "키에 들어갈 값이 한도를 넘는다(설계 2026-10-17 §3)" 로 고친다.
- 두 시험 파일의 옛 이름을 바꾼다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test :connector-scim:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add core connector-scim
git commit -m "fix: SCIM 쓰기가 키에 들어갈 값(userName·displayName·externalId·넣는 멤버 아이디)의 한도를 표지판·OpenFGA 전에 본다 — 넘으면 400 invalidValue 이고 아무것도 쓰지 않는다. 소문자로 길어지는 값과 공백뿐인 externalId 도 잰다(③ SCIM 표준 잔여)"
```

---

### Task 2: storage — 한도를 넘는 키는 DynamoDB 를 부르지 않고 없음이다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (한도 상수·도우미)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (기존 `MAX_SORT_KEY_BYTES`·`MAX_PARTITION_KEY_BYTES` 를 `Keys` 로 옮긴다)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbAuditLog.java` (기존 `MAX_SORT_KEY_BYTES` 도 `Keys` 로)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeyLimitReadTest.java`

**Interfaces:**
- Produces:
  - `Keys.MAX_PARTITION_KEY_BYTES = 2048`, `Keys.MAX_SORT_KEY_BYTES = 1024`.
  - `static boolean fitsPartitionKey(String key)`, `static boolean fitsSortKey(String key)` — UTF-8 바이트가 한도 이하인가.
- Consumes: 없음(Task 1 과 독립).

**고칠 읽기 메서드.** 각 메서드는 자기가 만드는 최종 키(접두어·`indexKey` 소문자 포함)를 재고, 넘으면 DynamoDB 를 부르지 않고 없음이다.
- 상태: `findUser`, `findUsers`(넘는 아이디만 뺌), `findMemberTypes`(뺌), `findUserIdsByExternalId`, `findGroupIdsByExternalId`, `findGroup`, `findGroupHeader`, `findGroupIdsContaining`, `findMembers`(넘는 후보만 뺌 — 정렬키 `MEMBER#…#id`), `findMemberRefs`.
- 조회: `findUser`, `findGroupHeader`, `findUsersByUserName`, `findGroupHeadersByDisplayName`(GSI1 정렬키), `findGroupHeadersByExternalId`·`findUsersByExternalId` — 상태 저장소에 맡기면 그쪽의 검사로 충분하다.
- 검색: `searchUsersByUserName`·`searchUsersByDisplayName`·`searchGroupsByDisplayName`(소문자로 만든 접두어가 정렬키 한도를 넘으면 `Page.empty()`), `findGroupSummary`, `findGroupUserMemberIds`, `findChildOrgCodes`.
- 이력: `history`(`Keys.auditPk(subject)` 가 넘으면 `Page.empty()` — 커서 검사보다 먼저).

- [ ] **Step 1: 실패하는 시험을 쓴다**

`KeyLimitReadTest extends DynamoDbTestSupport`. 도우미를 둘 둔다.
- **요청하면 실패하는 클라이언트.** `java.lang.reflect.Proxy` 로 만든 `DynamoDbAsyncClient` 다. `serviceName`·`close` 를 뺀 어떤 메서드든 부르면 `CompletableFuture.failedFuture(new AssertionError("DynamoDB 를 불렀다: " + method.getName()))` 를 준다.
- **요청을 세는 클라이언트.** Local 클라이언트를 감싸 메서드 호출 수를 센다.

시험:
- `넘는_아이디는_부르지_않고_없음이다` — 실패 클라이언트로 만든 세 저장소에서 다음이 모두 빈 결과이고 오류가 없다.
  - 상태: `findUser`·`findGroup`·`findGroupHeader`·`findMemberRefs`·`findGroupIdsContaining(MemberRef.user(긴))` 에 `"x".repeat(2044)`(`USER#` 와 합쳐 2049바이트).
  - 조회: `findUser`·`findGroupHeader`.
  - 검색: `findGroupSummary`·`findGroupUserMemberIds`·`findChildOrgCodes`.
  - 이력: `history(MemberRef.user(긴), null, 10)` 은 `Page.empty()`.
- `넘는_색인_값은_부르지_않고_없음이다` — `findUsersByUserName`·`findGroupHeadersByDisplayName`·세 접두어 검색에 1025바이트, `findUserIdsByExternalId`·`findGroupIdsByExternalId` 에 2049바이트.
- `넘는_멤버_후보는_부르지_않고_없음이다` — `findMembers("DEV", Set.of(MemberRef.user("u".repeat(1013))))`(정렬키 `MEMBER#USER#` 12바이트와 합쳐 1025바이트) → 빈 집합.
- `소문자로_길어지는_접두어도_잰다` — `searchUsersByUserName("İ".repeat(512), null, 10)` → `Page.empty()`, 실패 클라이언트.
- `묶음_읽기는_넘는_아이디만_뺀다` — Local 에 `kim` 을 심는다. `findUsers(Set.of("kim", 긴))` 은 `kim` 만이다. `findMemberTypes(Set.of("kim", 긴))` 은 `kim → USER` 만이다.
- `한도_바이트의_키는_부른다` — 세는 클라이언트에서 `findUser("x".repeat(2043))`(`USER#` 와 합쳐 정확히 2048)가 빈 결과이고 호출 수가 1 이상이다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*KeyLimitReadTest*'`
Expected: FAIL — `AssertionError: DynamoDB 를 불렀다: …`(지금은 길이를 보지 않고 부른다).

- [ ] **Step 3: 구현한다**

- `Keys` 에 상수와 도우미를 둔다.
- 위 메서드들에 검사를 단다. 검색·이력의 기존 커서 검사는 `Keys` 상수를 쓰게 바꾼다.
- 클래스 자바독(또는 각 저장소의 읽기 절)에 규칙 한 줄을 적는다: "한도를 넘는 키의 아이템은 있을 수 없어 부르지 않고 없음이다(설계 2026-10-17 §4) — DynamoDB Local 은 거절하지 않지만 실제 DynamoDB 는 `ValidationException` 이다."

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add storage-dynamodb
git commit -m "fix: 저장소가 한도를 넘는 키(파티션 2048·정렬 1024바이트, 접두어·소문자 포함)는 DynamoDB 를 부르지 않고 없음으로 답한다 — 경로 아이디·필터 값·멤버 값·관리 API 값이 실제 DynamoDB 에서 500 이던 것을 404·빈 결과로(③ SCIM 표준 잔여)"
```

---

### Task 3: connector-scim — `/Schemas`·`/ResourceTypes`

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimDiscovery.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimSchemas.java` (`RESOURCE_TYPE`, `SCHEMA`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimException.java` (`forbidden`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java` (라우트 넷, `받는_메서드` 넷, `지원_안_함` 에서 넷 뺌)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimDiscoveryTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimDiscoverySchemaAlignmentTest.java`
- Modify(test): `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimRouteMissTest.java`

**Interfaces:**
- Produces:
  - `ScimSchemas.RESOURCE_TYPE = "urn:ietf:params:scim:schemas:core:2.0:ResourceType"`, `ScimSchemas.SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema"`.
  - `ScimException.forbidden(String detail)` — 403, `scimType` 없음.
  - `final class ScimDiscovery` — 처리기 넷: `static Mono<ServerResponse> resourceTypes(ServerRequest)`, `resourceType(ServerRequest)`(경로 변수 `name`), `schemas(ServerRequest)`, `schema(ServerRequest)`(경로 변수 `id`). 패키지 전용 `static JsonNode userSchema()`·`groupSchema()` 는 정렬 시험이 쓴다.
- Consumes: 없음.

**자원 내용(정확한 값).**
- **ResourceType**
  - `{"schemas":[RESOURCE_TYPE], "id":"User", "name":"User", "endpoint":"/Users", "description":"User Account", "schema":USER, "meta":{"resourceType":"ResourceType", "location":"/scim/v2/ResourceTypes/User"}}`
  - Group 도 같은 모양이다: `id`·`name` 은 `"Group"`, `endpoint` 는 `"/Groups"`, `description` 은 `"Group"`, `schema` 는 `GROUP`.
- **Schema**: `{"schemas":[SCHEMA], "id":<URN>, "name":"User"|"Group", "description":"User Account"|"Group", "attributes":[…], "meta":{"resourceType":"Schema", "location":"/scim/v2/Schemas/<URN>"}}`.
- **속성 성질**(RFC 7643 §7 이름). 하위 속성도 같은 성질을 모두 싣는다.

  | 속성 | 성질 |
  |---|---|
  | `userName` | string, required **true**, caseExact **false**, mutability readWrite, returned default, uniqueness **server** |
  | `name` | complex, required false. 하위 여섯은 string, caseExact false |
  | 직원 `displayName` | string, caseExact false, uniqueness none |
  | `emails` | complex, multiValued **true**. `value`(string, caseExact false), `type`(string, canonicalValues `["work"]`), `primary`(boolean) |
  | `active` | boolean |
  | 조직 `displayName` | string, required false(지금 POST 가 요구하지 않는다), caseExact false |
  | `members` | complex, multiValued true. `value`(string, mutability **immutable**), `type`(string, canonicalValues `["User","Group"]`, mutability immutable) |

- **목록 응답**: `new ScimListResponse(List.of(LIST_RESPONSE), 2, 1, 2, [User, Group])`.

- [ ] **Step 1: 실패하는 시험을 쓴다**

`ScimDiscoveryTest`(`ScimRouteMissTest` 와 같은 `WebTestClient.bindToRouterFunction` 준비):
- `자원_종류_목록은_User_와_Group_이다` — `GET /scim/v2/ResourceTypes` → 200, `$.schemas[0]=LIST_RESPONSE`, `$.totalResults=2`, `$.Resources[0].id=User`, `$.Resources[0].endpoint=/Users`, `$.Resources[0].schema=USER`, `$.Resources[1].id=Group`, `$.Resources[0].meta.location=/scim/v2/ResourceTypes/User`.
- `자원_종류_단건은_대소문자를_가리지_않는다` — `GET /scim/v2/ResourceTypes/group` → 200, `$.id=Group`, `$.schemas[0]=RESOURCE_TYPE`.
- `모르는_자원_종류는_404다` — `GET /scim/v2/ResourceTypes/Device` → 404, `$.schemas[0]=ERROR`.
- `스키마_목록은_코어_User_와_Group_이다` — `GET /scim/v2/Schemas` → 200, `$.totalResults=2`, `$.Resources[0].id=USER`, `$.Resources[1].id=GROUP`.
- `URN_은_대소문자를_가리지_않는다` — `GET /scim/v2/Schemas/URN:IETF:PARAMS:SCIM:SCHEMAS:CORE:2.0:USER` → 200, `$.id=USER`.
- `모르는_스키마는_404다` — `GET /scim/v2/Schemas/urn:ietf:params:scim:schemas:extension:enterprise:2.0:User` → 404.
- `userName_은_필수이고_대소문자를_무시하며_서버에서_유일하다` — User 스키마의 `userName` 속성: `required=true`, `caseExact=false`, `uniqueness=server`.
- `멤버의_type_은_User_와_Group_이다` — Group 스키마 `members.subAttributes[name=type].canonicalValues` 가 `["User","Group"]`.
- `filter_가_오면_403이다` — `GET /scim/v2/Schemas?filter=id eq "x"` 와 `GET /scim/v2/ResourceTypes?filter=…` → 403, `$.schemas[0]=ERROR`.
- `다른_쿼리_파라미터는_무시한다` — `GET /scim/v2/Schemas?attributes=id&count=1` → 200, `$.totalResults=2`, `$.Resources[0].attributes` 가 있다.

`ScimDiscoverySchemaAlignmentTest`:
- `직원_스키마는_응답_DTO_와_같은_속성이다` — `ScimUser` 레코드 칸에서 공통 속성(`schemas`·`id`·`externalId`·`meta`)을 뺀 이름 집합이 `userSchema()` 의 `attributes[].name` 집합과 같다. `name`·`emails` 의 하위 속성 집합은 `ScimName`·`ScimEmail` 칸 집합과 같다. 레코드 칸은 `Class.getRecordComponents()` 로 읽는다.
- `조직_스키마는_응답_DTO_와_같은_속성이다` — `ScimGroup` 칸(공통 뺌)과 `groupSchema()` 가 같다. `members` 의 하위 속성은 `ScimMember` 칸에서 `display` 를 뺀 것과 같다(`display` 는 늘 `null` 이라 돌려주지 않는다 — 시험에 그 까닭을 주석으로 둔다).

`ScimRouteMissTest`:
- `지원하지_않는_엔드포인트는_501이다` 의 값에서 `Schemas`·`ResourceTypes` 경로 셋을 뺀다.
- `경로마다_Allow_가_정확하다` 에 줄 넷을 더한다(모두 `GET`): `/scim/v2/Schemas`, `/scim/v2/Schemas/x`, `/scim/v2/ResourceTypes`, `/scim/v2/ResourceTypes/User`.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimDiscovery*' --tests '*ScimRouteMissTest*'`
Expected: 컴파일 실패(`ScimDiscovery` 없음). 붙이면 501 이라 실패한다.

- [ ] **Step 3: 구현한다**

- `ScimDiscovery` 는 자원을 클래스를 불러올 때 한 번 `ObjectNode` 로 짓는다(`ScimJson` 의 매퍼).
  - `filter` 쿼리 파라미터가 있으면 `ScimException.forbidden("이 엔드포인트는 filter 를 받지 않습니다 — RFC 7644 §4")` 이다.
  - 단건은 `equalsIgnoreCase` 로 찾는다.
- 라우트는 `.GET("/scim/v2/ResourceTypes", …)`, `.GET("/scim/v2/ResourceTypes/{name}", …)`, `.GET("/scim/v2/Schemas", …)`, `.GET("/scim/v2/Schemas/{id}", …)` 다. 모두 `ServiceProviderConfig` 줄 뒤, 라우트 밖 처리 앞에 둔다.
- 라우터의 `지원_안_함` 주석과 README 의 501 줄은 Task 4 에서 맞춘다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim
git commit -m "feat: /Schemas·/ResourceTypes 를 RFC 7644 §4 대로 연다 — 목록은 ListResponse, 단건은 대소문자 무시, filter 는 403, 저장하고 돌려주는 속성만 알리고 응답 DTO 와 맞대 보는 시험을 둔다(③ SCIM 표준 잔여)"
```

---

### Task 4: 끝단과 문서

**Files:**
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimKeyLimitEndToEndTest.java` (DynamoDB Local·OpenFGA 컨테이너, `ScimAuditHistoryEndToEndTest` 의 준비를 따른다)
- Modify: `README.md` (SCIM 엔드포인트 표에 넷, 501 줄, 한도 문단)
- Modify: `docs/superpowers/specs/2026-09-25-scim-list-filter-design.md`, `docs/superpowers/specs/2026-10-06-scim-request-interpretation-design.md` §12, `docs/superpowers/specs/2026-09-28-full-audit.md` S-4·S7 줄 (해결 표시)

**Interfaces:**
- Consumes: Task 1(400 `invalidValue`), Task 2(없음), Task 3(엔드포인트).

- [ ] **Step 1: 끝단 시험을 쓴다**

`ScimKeyLimitEndToEndTest`. 긴 값은 `"x".repeat(2100)`(경로·필터·멤버)과 `"x".repeat(1025)`(표시명)이다.
- `긴_경로_아이디는_404다` — `GET`·`PUT`·`PATCH`·`DELETE /scim/v2/Users/{긴}`, `GET /scim/v2/Groups/{긴}` → 404 SCIM 오류.
  - 경로가 Netty 시작 줄 한도를 넘지 않게 긴 아이디는 2100바이트로 둔다.
- `긴_필터_값은_빈_목록이다` — `GET /scim/v2/Users?filter=userName eq "긴"`·`id eq "긴"`, `GET /scim/v2/Groups?filter=displayName eq "긴"` → 200, `totalResults=0`.
- `긴_표시명의_직원_생성은_400이다` — `POST /Users` 의 `displayName` 1025바이트 → 400, `scimType=invalidValue`, 본문 `detail` 에 `displayName` 이 있다.
- `표시명이_이름에서_정해져도_한도를_본다` — 있는 직원에 `displayName` 없이 `name.formatted` 1025바이트로 `PUT` → 400 `invalidValue`. 다시 `GET` 하면 표시명이 그대로다.
- `긴_멤버가_섞인_교체는_400이다` — 있는 조직에 `PUT` 으로 멤버 `[{value: 있는 직원}, {value: "u".repeat(1001)}]` → 400 `invalidValue`. 다시 `GET` 하면 멤버가 그대로다.
- `긴_멤버_빼기는_204다` — `PATCH` `{"op":"remove","path":"members[value eq \"<긴>\"]"}` → 204, 멤버가 그대로다.
- `관리_API_의_긴_값은_없음이다` — `GET /admin/employees/{긴}` → 404. `GET /admin/employees?userName=<1025바이트>` → 200 이고 `items` 가 비어 있다. `GET /admin/employees/{긴}/history` → 200 이고 `items` 가 비어 있다.
- `발견_엔드포인트를_앱에서_연다` — `GET /scim/v2/Schemas` → 200 `totalResults=2`. `GET /scim/v2/ResourceTypes/User` → 200.

- [ ] **Step 2: 돌린다**

Run: `./gradlew :app-scim:test --tests '*ScimKeyLimitEndToEndTest*'`
Expected: PASS(Task 1~3 위에서). DynamoDB Local 은 키 길이를 거절하지 않으므로 이 시험은 HTTP 응답을 고정한다. "부르지 않음" 은 Task 2 가 고정한다.

- [ ] **Step 3: 문서를 고친다**

- **README.**
  - SCIM 엔드포인트 표에 `/scim/v2/Schemas`·`/scim/v2/Schemas/{URN}`·`/scim/v2/ResourceTypes`·`/scim/v2/ResourceTypes/{이름}`(GET)를 더한다.
  - 501 줄에서 `/Schemas`·`/ResourceTypes` 를 뺀다.
  - SCIM 절에 "값의 한도" 문단을 더한다.
    - `userName`·`displayName`·`externalId` 는 UTF-8 1024바이트(소문자로 만든 뒤), 멤버 아이디는 1000바이트이고, 넘으면 400 `invalidValue` 다.
    - 그보다 긴 아이디·필터 값으로 찾으면 없음(404·빈 목록)이다.
    - LDAP 에서 온 긴 값은 막지 않는다.
  - 라우터 `지원_안_함` 주석도 맞춘다.
- **옛 설계.**
  - S-1 설계의 "키 길이 초과 필터 값 → 500" 남긴 것에 `→ 해결(2026-10-17 설계)` 를 단다.
  - ⑤-2 §12 의 `/Schemas`·`/ResourceTypes` 줄에도 같은 표시를 단다.
  - 전체 점검 S-4(`/Schemas` 없음)·S7 표의 `/Schemas`·`/ResourceTypes` 줄에도 단다.

- [ ] **Step 4: 커밋**

```bash
git add app-scim README.md docs/superpowers/specs connector-scim
git commit -m "docs: 키 한도와 발견 엔드포인트를 README 에 적고 옛 설계의 남긴 것에 해결 표시를 한다. 끝단 시험이 긴 경로·필터 값의 404·빈 목록, 긴 표시명·멤버의 400, 긴 멤버 빼기의 204, 관리 API 의 없음, 발견 엔드포인트를 본다(③ SCIM 표준 잔여)"
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로(동시에 말고) 돌린다.
- 설계 §10 에 결과, 구현 중 판정, 남긴 것을 적는다.
