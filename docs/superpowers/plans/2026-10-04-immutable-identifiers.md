# 불변 식별자 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 ④-1 — 튜플의 직원·조직 식별자를 불변 id 로 바꾼다(SCIM 서버 발급 UUID, LDAP `entryUUID`·`objectGUID`). LDAP 검색은 필요한 속성만 요청하고(P7), LDAP `userName` 은 원본 로그인 값으로(S25), 관리 API 는 `externalId` 로 정확히 찾는다.

**Architecture:** 상태 포트에 `externalId` 조회 둘(`findUserIdsByExternalId`·`findGroupIdsByExternalId`, GSI3)을 더해 SCIM 조직 생성·변경의 `externalId` 중복(409)을 락 안에서 판정한다.
connector-scim 은 POST 에서 UUID 를 발급해 매퍼에 넘긴다. SCIM 테스트 하네스는 POST 응답의 `id` 를 받아 두는 번역부(`ScimIdBook`)로 경로·멤버 값·기대값을 서버 id 로 바꾼다.
connector-ldap 은 반환 속성을 명시하고(`PagedLdapSearch` 가 `SearchControls#setReturningAttributes`), 식별 값을 `LdapIdentifiers` 한 곳에서 읽는다(`objectGUID` 는 이진 → 표준 GUID 문자열).
기본 식별 속성은 `entryUUID`, 로그인 값은 새 설정 `user-login-attribute` 다.

**Tech Stack:** Java 17, Spring Boot 3.5 / WebFlux, Reactor, Spring LDAP(JNDI), UnboundID LDAP SDK 7.0.1(임베디드), AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, Testcontainers, Lombok.

**Spec:** `docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then` — 계획의 스니펫에 빠져 있어도 반드시 넣는다), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고**(컴파일 실패 포함) 고친다.
- SCIM `id`: `UUID.randomUUID().toString()`(소문자 하이픈). 본문의 `id` 는 무시한다. PUT·PATCH·DELETE 는 경로의 `id` 가 정본이다.
- 409 `uniqueness` 문구: 직원 `이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s`(지금 그대로), 조직 `이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s`.
  `externalId` 비교는 대소문자를 가린다(RFC 7643 `caseExact: true`). 빈 `externalId` 는 판정하지 않는다.
- LDAP 식별 속성 기본값 `entryUUID`(groupOfNames 의 직원·조직, DIT 의 OU·직원). 로그인 속성 `user-login-attribute` 기본 `uid`(두 전략). 식별 속성 이름이 `objectGUID`(대소문자 무시)면
  JNDI `java.naming.ldap.attributes.binary` 에 그 이름을 넣고 16바이트를 GUID 문자열(`%02x` × 16, 앞 세 묶음 바이트 뒤집음, 소문자)로 바꾼다. 16바이트가 아니면 `DirectoryDataException`.
- 운영 배포 전이라 이관·하위호환을 만들지 않는다.
- 서브에이전트는 과제에 적힌 **모듈 테스트나 테스트 클래스만** 돌린다. 앱 모듈 전체 `test`·`scaleTest` 는 돌리지 않는다(과제가 클래스를 지정한 경우만 그 클래스). Gradle 은 한 번에 하나,
  포그라운드. 파일 시스템 전체를 훑는 검색(`find /`)을 하지 않는다. `git stash` 를 쓰지 않는다. 백그라운드 프로세스를 띄우지 않는다.
- 커밋마다 `git push`(브랜치 `audit-identifiers`, 업스트림 설정돼 있음). 커밋 메시지는 제목 → 빈 줄 → `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  (heredoc `git commit -F - <<'EOF' … EOF`). 경로를 지정해 스테이징한다.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **IdP 가 응답을 잃은 직원 POST 를 다시 보낸다** — 같은 `userName` 이라 409 이고 새 직원이 둘 생기지 않는다(Task 1 `같은_userName_두_번째_생성은_409다`).
2. **PUT·PATCH 로 조직 `externalId` 를 다른 조직의 값으로 바꾼다** — 409 이고 아무것도 쓰지 않는다(Task 1 `externalId_를_남의_값으로_바꾸면_409다`).
3. **AD `objectGUID` 값이 16바이트가 아니다(손상·다른 속성)** — 데이터 오류로 회차가 멈추고 메시지에 DN 이 있다(Task 6 `objectGUID_가_16바이트가_아니면_데이터_오류다`).
4. **LDAP 엔트리에 로그인 속성이 없다(서비스 계정 등)** — `userName` 이 식별 값으로 대신 채워지고 회차는 계속된다(Task 6 `로그인_속성이_없으면_식별값으로_대신한다`).
5. **SCIM 조직 POST 의 멤버 값이 아직 없거나 지워진 id 다** — 멤버 줄은 저장되고 튜플은 없다(지금 동작 그대로), 하네스 번역부는 모르는 id 를 그대로 둔다(Task 3 `모르는_아이디는_그대로_둔다`).

---

## Task 1: `externalId` 조회 둘과 SCIM 생성·변경의 중복 판정

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`createUser`, `createGroup`, `changeGroupInternal`, 새 `externalId를_확인한다`)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java` (중복 GSI3 쿼리를 상태 포트로)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (위임 둘), `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java` (감시 둘)
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCreateUniquenessTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Produces: `Flux<String> findUserIdsByExternalId(String externalId)`, `Flux<String> findGroupIdsByExternalId(String externalId)` — GSI3(최종 일관성), 빈 값이면 비어 있다. 부르는 쪽이 본 테이블로 다시 확인한다.
  `IncrementalSyncUseCase#createUser` 는 아이디 중복을 보지 않는다(서버 발급), `createGroup`·`changeGroup` 은 `externalId` 중복이면 `DirectoryConflictException`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`IncrementalSyncCreateUniquenessTest`(셋업은 `IncrementalSyncUseCaseTest` 와 같은 모양 — `state`·`writer`·`checker`·`lock`·`useCase`):

```java
/** 서버가 발급하는 id 아래의 중복 판정(설계 2026-10-04 §3.2, 점검 M7). */
class IncrementalSyncCreateUniquenessTest {

    private static DirectoryUser 직원(String id, String userName) {
        return new DirectoryUser(id, null, userName, userName, null, true);
    }

    @Test
    @DisplayName("같은 userName 의 두 번째 생성은 409 다 — 응답을 잃은 POST 를 IdP 가 다시 보내도 직원이 둘 생기지 않는다")
    void 같은_userName_두_번째_생성은_409다() {
        // given
        useCase.createUser(직원("u-1", "kim@corp.com")).block();

        // when
        var 다시 = useCase.createUser(직원("u-2", "KIM@corp.com"));

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("userName");
        assertThat(state.users).containsOnlyKeys("u-1");
    }

    @Test
    @DisplayName("지운 직원과 같은 userName 으로 다시 만들면 새 id 이고 옛 id 의 고아 튜플을 물려받지 않는다(점검 M7)")
    void 지운_직원의_userName_을_다시_써도_권한을_물려받지_않는다() {
        // given — u-1 에게 상태에 없는 고아 튜플(FIN)이 남은 채 지워진다
        useCase.createUser(직원("u-1", "kim@corp.com")).block();
        checker.allowed.add(RelationTuple.directMember("u-1", "FIN"));
        useCase.removeUser("u-1").block();

        // when — 같은 메일의 신규 입사자
        var result = useCase.createUser(직원("u-2", "kim@corp.com")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsOnlyKeys("u-2");
        assertThat(checker.allowed).doesNotContain(RelationTuple.directMember("u-2", "FIN"));
    }

    @Test
    @DisplayName("이름을 바꾼 사람의 옛 userName 으로 새 입사자를 만들 수 있다")
    void 옛_userName_을_새_입사자가_쓴다() {
        // given
        useCase.createUser(직원("u-1", "old@corp.com")).block();
        useCase.changeUser("u-1", user -> user.withUserName("new@corp.com")).block();

        // when
        var result = useCase.createUser(직원("u-2", "old@corp.com")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.users).containsOnlyKeys("u-1", "u-2");
    }

    @Test
    @DisplayName("같은 externalId 의 두 번째 조직 생성은 409 다 — externalId 가 없으면 판정하지 않는다")
    void 조직_externalId_중복은_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();

        // when
        var 다시 = useCase.createGroup(new DirectoryGroup("g-2", "DEV001", "개발본부", Set.of()));
        var 없음1 = useCase.createGroup(new DirectoryGroup("g-3", null, "이름만", Set.of())).block();
        var 없음2 = useCase.createGroup(new DirectoryGroup("g-4", null, "이름만", Set.of())).block();

        // then
        assertThatThrownBy(다시::block).isInstanceOf(DirectoryConflictException.class).hasMessageContaining("externalId");
        assertThat(state.groups).containsOnlyKeys("g-1", "g-3", "g-4");
    }

    @Test
    @DisplayName("PUT·PATCH 로 조직 externalId 를 다른 조직의 값으로 바꾸면 409 이고 아무것도 쓰지 않는다")
    void externalId_를_남의_값으로_바꾸면_409다() {
        // given
        useCase.createGroup(new DirectoryGroup("g-1", "DEV001", "개발본부", Set.of())).block();
        useCase.createGroup(new DirectoryGroup("g-2", "DEV002", "백엔드팀", Set.of())).block();

        // when
        var 바꾸기 = useCase.changeGroup("g-2", GroupChange.replacement("DEV001", "백엔드팀", Set.of()));

        // then
        assertThatThrownBy(바꾸기::block).isInstanceOf(DirectoryConflictException.class);
        assertThat(state.groups.get("g-2").externalId()).isEqualTo("DEV002");
    }
}
```

(`GroupChange.replacement`·`DirectoryUser#withUserName` 의 실제 시그니처를 따른다. `createGroup` 이 아이디로 "이미 존재하는 조직"을 막던 기존 테스트와 `createUser` 의 "이미 존재하는 직원"
테스트는 새 규칙으로 고치거나(같은 `externalId`·`userName` 으로) 지운다 — 바꾼 것을 보고서에 적는다.)

`DynamoDbDirectoryStateRepositoryTest`:

```java
    @Test
    @DisplayName("externalId 로 직원·조직 아이디를 찾는다 — 종류를 섞지 않는다")
    void externalId_로_찾는다() {
        // given
        repository.saveUser(new DirectoryUser("u-1", "ext-1", "kim", "김", null, true)).block();
        repository.saveGroup(new DirectoryGroup("g-1", "ext-1", "개발", Set.of())).block();

        // when
        var 직원 = repository.findUserIdsByExternalId("ext-1").collectList().block();
        var 조직 = repository.findGroupIdsByExternalId("ext-1").collectList().block();
        var 없음 = repository.findGroupIdsByExternalId("ext-9").collectList().block();

        // then
        assertThat(직원).containsExactly("u-1");
        assertThat(조직).containsExactly("g-1");
        assertThat(없음).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncCreateUniquenessTest'` 그다음 `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: 컴파일 실패(포트 메서드 없음), core 는 조직 중복이 아이디로만 판정돼 실패.

- [ ] **Step 3: 포트·저장소·가짜**

포트(`findUserIdsByUserName` 옆):

```java
    /**
     * {@code externalId} 가 같은 직원 아이디. GSI3(최종 일관성)라 막 저장된 직원이 늦게 보일 수 있다 — 부르는 쪽이 본 테이블로 다시 확인한다.
     * 빈 값이면 비어 있다. {@code externalId} 는 대소문자를 가린다(RFC 7643 caseExact).
     */
    Flux<String> findUserIdsByExternalId(String externalId);

    /** 같은 것을 조직으로. SCIM 조직 생성·변경의 중복 판정(설계 2026-10-04 §3.2)과 관리 API 가 쓴다. */
    Flux<String> findGroupIdsByExternalId(String externalId);
```

`DynamoDbDirectoryStateRepository` — `DynamoDbDirectoryQueryRepository#byExternalId` 와 같은 쿼리를 옮겨 둔다:

```java
    @Override
    public Flux<String> findUserIdsByExternalId(String externalId) {
        return pksByExternalId(externalId, Keys.USER_PREFIX).map(Keys::parseUserPk);
    }

    @Override
    public Flux<String> findGroupIdsByExternalId(String externalId) {
        return pksByExternalId(externalId, Keys.GROUP_PREFIX).map(Keys::parseGroupPk);
    }

    /** GSI3 에서 {@code externalId} 가 같은 아이템 중 {@code prefix} 종류의 META 만 골라 PK 를 준다. */
    private Flux<String> pksByExternalId(String externalId, String prefix) {
        if (externalId == null || externalId.isEmpty()) {
            return Flux.empty();
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI3)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI3PK, "#sk", Keys.GSI3SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(externalId), ":prefix", Attrs.s(prefix)))
                .build();
        return Paginator.queryAll(client, request)
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .map(item -> Attrs.str(item, Keys.PK));
    }
```

`DynamoDbDirectoryQueryRepository` 의 `findUsersByExternalId`·`findGroupHeadersByExternalId` 는 `state.findUserIdsByExternalId(externalId).concatMap(state::findUser)`·
`state.findGroupIdsByExternalId(externalId).concatMap(state::findGroupHeader)` 로 바꾸고 `byExternalId` 를 지운다(같은 쿼리 두 벌 금지).

`FakeStateRepository`:

```java
    @Override
    public Flux<String> findUserIdsByExternalId(String externalId) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(users.values()))
                .filter(user -> externalId != null && externalId.equals(user.externalId()))
                .map(DirectoryUser::id));
    }

    @Override
    public Flux<String> findGroupIdsByExternalId(String externalId) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(groups.values()))
                .filter(group -> externalId != null && externalId.equals(group.externalId()))
                .map(DirectoryGroup::id));
    }
```

`LdapInterruptedSyncScaleTest` 위임 둘, `WriteDecisionLockInvariantTest` 의 `감시하는_저장소` 감시 둘(`본다Flux("findUserIdsByExternalId " + e, …)` 모양).

- [ ] **Step 4: 유스케이스**

```java
    /**
     * 직원 생성(POST). {@code userName} 중복을 <b>락 안에서</b> 확인한다(SCIM 쓰기 락 설계 §4). 아이디는 서버가 발급한 UUID 라 겹칠 일이 없어 보지 않는다
     * (설계 2026-10-04 §3.2) — 지운 직원의 아이디가 다시 쓰이지 않아 남은 권한을 물려받지 않는다(점검 M7).
     */
    public Mono<IncrementalSyncResult> createUser(DirectoryUser user) {
        return withLock(lease -> userName을_확인한다(user.userName(), user.id())
                .then(Mono.defer(() -> upsertUserInternal(user, Optional.empty(), lease))));
    }

    /** 조직 생성(POST). {@code externalId} 가 있으면 다른 조직과 겹치는지 <b>락 안에서</b> 확인한다(설계 2026-10-04 §3.2). 겹치면 {@link DirectoryConflictException}. */
    public Mono<IncrementalSyncResult> createGroup(DirectoryGroup group) {
        return withLock(lease -> externalId를_확인한다(group.externalId(), group.id())
                .then(Mono.defer(() -> upsertGroupInternal(group, lease))));
    }

    /**
     * 조직 {@code externalId} 가 다른 조직과 겹치는지 — {@link #userName을_확인한다} 와 같은 방식이다. GSI3 로 후보를 찾고 자기 자신을 뺀 뒤 본 테이블을
     * 강한 일관성으로 다시 읽어 여전히 같은 값일 때만 충돌이다. 빈 값은 보지 않는다. RFC 핵심 스키마에는 조직의 유일 속성이 없지만, 응답을 잃은 POST 의
     * 재시도가 같은 조직을 둘 만들지 않게 막는다(설계 §3.2).
     */
    private Mono<Void> externalId를_확인한다(String externalId, String selfId) {
        if (externalId == null || externalId.isBlank()) {
            return Mono.empty();
        }
        return state.findGroupIdsByExternalId(externalId)
                .filter(id -> !id.equals(selfId))
                .concatMap(state::findGroupHeader)
                .filter(other -> externalId.equals(other.externalId()))
                .next()
                .flatMap(other -> Mono.error(new DirectoryConflictException(
                        "이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s".formatted(externalId, other.id()))));
    }
```

`changeGroupInternal` 은 헤더를 읽은 뒤 `바뀐헤더 = change.applyTo(header)` 를 먼저 계산하고, `externalId` 가 바뀌었으면(`!Objects.equals(header.externalId(), 바뀐헤더.externalId())`)
`externalId를_확인한다(바뀐헤더.externalId(), groupId)` 를 앞에 둔다 — 지금 몸체는 그 뒤에 `then(Mono.defer(...))` 로 이어진다. `changeGroup` 자바독에 한 문장.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test` 그다음 `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest' --tests '*DynamoDbDirectoryQueryRepositoryTest'` 그다음 `./gradlew :app-ldap:compileTestJava`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryQueryRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -F - <<'EOF'
feat: externalId 로 직원·조직 아이디를 찾고, SCIM 조직 생성·변경의 externalId 중복을 락 안에서 막는다 — 직원 생성은 아이디 중복을 보지 않는다(서버 발급)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 2: SCIM 서버 발급 UUID (connector-scim)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java` (`toDirectoryUser(ScimUser, String id)`, `toDirectoryGroup(ScimGroup, String id, MemberTypeResolver)`, `organizationCode` 삭제, 클래스 자바독)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimUserHandler.java`, `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimUserHandlerTest.java`, `ScimGroupHandlerTest.java`, `ScimMapperTest.java`, `ScimListHandlerTest.java` 등 POST 뒤 아이디를 쓰는 테스트

**Interfaces:**
- Consumes: `createUser`·`createGroup`(Task 1).
- Produces: POST 응답의 `id` 는 서버 발급 UUID. `ScimMapper.toDirectoryUser(ScimUser scim, String id)`, `ScimMapper.toDirectoryGroup(ScimGroup scim, String id, MemberTypeResolver resolver)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimUserHandlerTest`:

```java
    @Test
    @DisplayName("직원 POST 는 서버가 발급한 UUID 를 id 로 돌려주고, 그 id 로 다시 읽힌다 — 본문의 id 는 무시한다")
    void POST는_서버가_id_를_발급한다() {
        // given
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"id":"client-chosen","userName":"kim@corp.com","displayName":"김철수"}
                """;

        // when
        String id = client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("id").toString();

        // then
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(state.users).containsOnlyKeys(id);
        client.get().uri("/scim/v2/Users/" + id).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.userName").isEqualTo("kim@corp.com");
    }
```

`ScimGroupHandlerTest` — 같은 모양으로 `POST는_서버가_id_를_발급한다`(조직, `externalId` 는 그대로 `DEV001`)와, 기존 `조직을_생성한다` 의 `$.id` 단정을 UUID 로·`$.externalId` 를 `DEV001` 로.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimUserHandlerTest' --tests '*ScimGroupHandlerTest'`
Expected: FAIL — id 가 `kim_corp.com` 류·`DEV001`.

- [ ] **Step 3: 매퍼와 핸들러**

`ScimMapper`:

```java
    /** {@code id} 는 부르는 쪽이 정한다 — POST 는 서버가 발급한 UUID, PUT 은 경로의 id(설계 2026-10-04 §3.1). 본문의 {@code id} 는 쓰지 않는다. */
    public static DirectoryUser toDirectoryUser(ScimUser scim, String id) {
        if (scim.userName() == null || scim.userName().isBlank()) {
            throw ScimException.invalidSyntax("userName 은 필수입니다");
        }
        return new DirectoryUser(
                id,
                scim.externalId(),
                scim.userName(),
                firstNonBlank(scim.displayName(), formatted(scim), scim.userName()),
                primaryEmail(scim.emails()),
                // SCIM 에서 active 는 선택 필드다. 없으면 활성으로 본다.
                scim.active() == null || scim.active(),
                toPersonName(scim.name()));
    }
```

`toDirectoryGroup(ScimGroup scim, String id, MemberTypeResolver resolver)` 는 `organizationCode(scim)` 대신 `id` 를 쓴다(지금 ③-2 의 `Mono.defer` 몸체 그대로, `code` → `id`).
`organizationCode` 를 지운다. 클래스 자바독의 "SCIM Group 에는 둘을 나눌 칸이 없어 externalId 를 코드로 채택한다" 류 문장을 "id 는 서버가 발급하고 externalId 는 속성으로만 둔다"로 고친다.

`ScimUserHandler`:

```java
    public Mono<ServerResponse> create(ServerRequest request) {
        return projection(request).flatMap(projection -> request.bodyToMono(ScimUser.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                // id 는 서버가 발급한다 — RFC 7643 §3.1(설계 2026-10-04 §3.1)
                .map(scim -> ScimMapper.toDirectoryUser(scim, UUID.randomUUID().toString()))
                // userName 중복은 락 안에서 확인한다(SCIM 쓰기 락 설계 §4)
                .flatMap(user -> sync.createUser(user)
                        .flatMap(result -> respond(HttpStatus.CREATED, user.id(), result, projection))));
    }
```

`replace` 는 `.map(scim -> ScimMapper.toDirectoryUser(scim, id))` 로 바꾸고 `before -> user.withId(id)` 를 `before -> user` 로. `ScimGroupHandler.create` 는
`ScimMapper.toDirectoryGroup(scim, UUID.randomUUID().toString(), memberTypes)`, `replace` 는 경로 `id` 를 넘긴다(주석 "경로의 조직코드가 정본" → "경로의 id 가 정본").

- [ ] **Step 4: 기존 테스트를 서버 id 로**

connector-scim 테스트 중 POST 응답 뒤에 `userName`·`externalId` 에서 만든 아이디로 경로를 부르던 곳은 응답의 `id` 를 받아 쓴다. 테스트 클래스마다 도우미 하나:

```java
    /** POST 응답의 서버 발급 id. */
    private String 만든_아이디(WebTestClient.ResponseSpec 응답) {
        return 응답.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id").toString();
    }
```

상태에 직접 심고(`state.saveUser(new DirectoryUser("kim", …))`) 그 아이디로 부르는 테스트는 그대로 둔다(심은 아이디가 정본이다). `ScimMapperTest` 의 "userName 에서 아이디를 만든다"
류 테스트는 "넘겨받은 id 를 쓴다"로 바꾼다. 바꾼 테스트를 보고서에 적는다. `fixture/ScimRequestRendererTest` 가 깨지면 Task 3 이 고친다 — 이 과제에서는 그 클래스를 건드리지 않는다.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS(`ScimRequestRendererTest` 만 실패하면 보고서에 적고 넘어간다 — Task 3).

- [ ] **Step 6: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ connector-scim/src/test/java/dev/starryeye/organization/scim/
git commit -F - <<'EOF'
feat: SCIM 직원·조직 id 를 서버가 발급한다(UUID) — userName·externalId 에서 아이디를 만들지 않는다, 본문 id 는 무시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 3: SCIM 테스트 하네스의 아이디 번역, 시드·재생 스크립트, app-scim 규모 테스트

**Files:**
- Create: `connector-scim/src/testFixtures/java/dev/starryeye/organization/scim/fixture/ScimIdBook.java`
- Modify: `connector-scim/src/testFixtures/java/dev/starryeye/organization/scim/fixture/ScimRequest.java` (`차트아이디` 성분), `ScimRequestRenderer.java`(생성 요청에 차트 아이디, 자바독), `ScimSeedWriter.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChart.java`, `Landmarks.java` (`아이디를_바꾼다`)
- Modify: `docker/scim/replay.py`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java`, `ScimLimitsAndRecoveryScaleTest.java`, `ScimRebuildLockScaleTest.java`,
  `ScimScaleSyncCostTest.java`, `ScimProvisioningOrderScaleTest.java`, `ScimGroupMemberPatchScaleTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/fixture/ScimIdBookTest.java`(새), `ScimRequestRendererTest.java`

**Interfaces:**
- Produces: `ScimRequest(String method, String path, Object body, String 설명, String 차트아이디)` — `차트아이디` 는 생성(POST) 요청이 만드는 리소스의 조직도 아이디, 그 밖은 null.
  `ScimRequest.post(path, body, 설명)`(차트아이디 null)과 `ScimRequest.post(path, body, 설명, 차트아이디)`.
  `ScimIdBook` — `void 기록한다(String 차트아이디, String 서버아이디)`, `String 서버(String 차트아이디)`(모르면 그대로), `ScimRequest 번역한다(ScimRequest)`,
  `RelationTuple 번역한다(RelationTuple)`, `OrgChart 번역한다(OrgChart)`. `OrgChart#아이디를_바꾼다(UnaryOperator<String>)`, `Landmarks#아이디를_바꾼다(UnaryOperator<String>)`.

**왜:** 서버가 id 를 정하므로 조직도(기대값)의 아이디와 서버 아이디가 다르다. 요청은 조직도 아이디로 만들고 **보낼 때** 번역하고, 기대값·Check 는 **볼 때** 번역한다 —
조직도·렌더러·시나리오 코드는 조직도 아이디로 계속 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimIdBookTest`:

```java
class ScimIdBookTest {

    @Test
    @DisplayName("경로의 아이디와 조직 멤버 값, PATCH 의 멤버 값·필터를 서버 아이디로 바꾼다")
    void 요청을_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("DEV", "g-1");

        // when
        var 경로 = 번역부.번역한다(ScimRequestRenderer.직원비활성("kim"));
        var 추가 = 번역부.번역한다(ScimRequestRenderer.멤버추가("DEV", MemberRef.user("kim")));
        var 제거 = 번역부.번역한다(ScimRequestRenderer.멤버제거("DEV", "kim"));

        // then
        assertThat(경로.path()).isEqualTo("/scim/v2/Users/u-1");
        assertThat(추가.path()).isEqualTo("/scim/v2/Groups/g-1");
        assertThat(((ScimPatchOp) 추가.body()).operations().get(0).value())
                .asList().extracting("value").containsExactly("u-1");
        assertThat(((ScimPatchOp) 제거.body()).operations().get(0).path()).isEqualTo("members[value eq \"u-1\"]");
    }

    @Test
    @DisplayName("모르는 아이디는 그대로 둔다 — 아직 없거나 지워진 리소스를 가리키는 멤버")
    void 모르는_아이디는_그대로_둔다() {
        // given
        var 번역부 = new ScimIdBook();

        // when
        var 요청 = 번역부.번역한다(ScimRequestRenderer.멤버추가("DEV", MemberRef.user("ghost")));

        // then
        assertThat(요청.path()).isEqualTo("/scim/v2/Groups/DEV");
        assertThat(((ScimPatchOp) 요청.body()).operations().get(0).value()).asList().extracting("value").containsExactly("ghost");
    }

    @Test
    @DisplayName("한 조직도 아이디가 두 서버 아이디로 기록되면 멈춘다 — 직원·조직 아이디가 겹친 하네스 오류")
    void 겹친_기록은_멈춘다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");

        // when, then
        assertThatThrownBy(() -> 번역부.기록한다("kim", "u-2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("조직도와 튜플을 서버 아이디로 바꾼다 — 멤버·랜드마크·지워진 멤버십까지")
    void 기대값을_번역한다() {
        // given — 작은 조직도는 core testFixtures 의 생성기를 쓰거나 손으로 만든다
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("DEV", "g-1");

        // when
        var 튜플 = 번역부.번역한다(RelationTuple.member("kim", "DEV"));

        // then
        assertThat(튜플).isEqualTo(RelationTuple.member("u-1", "g-1"));
    }
}
```

(조직도 번역은 `OrgChart` 를 손으로 만들어 `번역한다(chart).snapshot()` 의 직원·조직 키와 멤버, `landmarks()` 한두 칸을 단정하는 테스트를 하나 더 둔다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimIdBookTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`ScimRequest` 에 성분을 더한다: `record ScimRequest(String method, String path, Object body, String 설명, String 차트아이디)`. 팩토리 `post/put/patch/delete` 는 `차트아이디` null,
`post(path, body, 설명, 차트아이디)` 를 더한다. `ScimRequestRenderer.직원생성` 은 `ScimRequest.post(USERS, scimUser(user), "직원 생성 " + user.id(), user.id())`,
`조직생성` 은 `… , group.id())`. 렌더러 클래스 자바독의 "조직코드는 externalId 로 간다 … 직원 쪽은 userName 이 아이디의 원천" 단락을 "서버가 id 를 발급하므로 요청은 조직도 아이디로
만들고 보낼 때 `ScimIdBook` 이 번역한다. 조직도 아이디는 조직의 `externalId` 로 간다"로 고친다.

`ScimIdBook`:

```java
/**
 * 조직도 아이디 → 서버 발급 id(설계 2026-10-04 §3.1). SCIM 은 서버가 id 를 정하므로 테스트는 POST 응답의 id 를 받아 두었다가 뒤 요청의 경로·멤버 값과
 * 기대값·Check 를 서버 id 로 바꾼다. 모르는 아이디(아직 없거나 지워진 리소스)는 그대로 둔다.
 */
public final class ScimIdBook {

    private static final Pattern 멤버필터 = Pattern.compile("^members\\[value eq \"(.*)\"]$");

    private final Map<String, String> 서버아이디 = new ConcurrentHashMap<>();

    public void 기록한다(String 차트아이디, String 서버아이디) {
        String 전 = this.서버아이디.putIfAbsent(차트아이디, 서버아이디);
        if (전 != null && !전.equals(서버아이디)) {
            throw new IllegalStateException("조직도 아이디 %s 가 두 서버 id 로 기록됐다: %s, %s".formatted(차트아이디, 전, 서버아이디));
        }
    }

    public String 서버(String 차트아이디) {
        return 서버아이디.getOrDefault(차트아이디, 차트아이디);
    }

    public ScimRequest 번역한다(ScimRequest request) {
        return new ScimRequest(request.method(), 경로(request.path()), 본문(request.body()), request.설명(), request.차트아이디());
    }

    public RelationTuple 번역한다(RelationTuple tuple) {
        return new RelationTuple(타입아이디(tuple.user()), tuple.relation(), 타입아이디(tuple.object()));
    }

    public OrgChart 번역한다(OrgChart chart) {
        return chart.아이디를_바꾼다(this::서버);
    }

    private String 경로(String path) {
        for (String 앞 : List.of(ScimRequestRenderer.USERS + "/", ScimRequestRenderer.GROUPS + "/")) {
            if (path.startsWith(앞)) {
                return 앞 + 서버(path.substring(앞.length()));
            }
        }
        return path;
    }

    private Object 본문(Object body) {
        if (body instanceof ScimGroup group && group.members() != null) {
            return new ScimGroup(group.schemas(), group.id(), group.externalId(), group.displayName(),
                    group.members().stream().map(this::멤버).toList(), group.meta());
        }
        if (body instanceof ScimPatchOp patch) {
            return new ScimPatchOp(patch.schemas(), patch.operations().stream().map(this::연산).toList());
        }
        return body;
    }

    private ScimOperation 연산(ScimOperation operation) {
        String path = operation.path();
        if (path != null) {
            Matcher 필터 = 멤버필터.matcher(path);
            if (필터.matches()) {
                path = "members[value eq \"" + 서버(필터.group(1)) + "\"]";
            }
        }
        Object value = operation.value() instanceof List<?> list
                ? list.stream().map(element -> element instanceof ScimMember member ? 멤버(member) : element).toList()
                : operation.value();
        return new ScimOperation(operation.op(), path, value);
    }

    private ScimMember 멤버(ScimMember member) {
        return new ScimMember(서버(member.value()), member.type(), member.ref());
    }

    private String 타입아이디(String typed) {
        int 구분 = typed.indexOf(':');
        return 구분 < 0 ? 서버(typed) : typed.substring(0, 구분 + 1) + 서버(typed.substring(구분 + 1));
    }
}
```

(DTO 레코드의 실제 성분 이름·순서 — `ScimGroup`·`ScimPatchOp`·`ScimOperation`·`ScimMember` — 을 따른다. 렌더러의 다른 요청 모양(경로 없는 `add` 의 `members` 맵 등)이 있으면 같이 번역한다.)

`OrgChart#아이디를_바꾼다`:

```java
    /** 직원·조직 아이디를 {@code 바꾼다} 로 바꾼 조직도 — SCIM 서버 발급 id 와 대조할 때 쓴다(설계 2026-10-04 §3.1). 멤버·랜드마크·지워진 멤버십까지 바꾼다. */
    public OrgChart 아이디를_바꾼다(UnaryOperator<String> 바꾼다) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        snapshot.users().values().forEach(user -> users.put(바꾼다.apply(user.id()), user.withId(바꾼다.apply(user.id()))));
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        snapshot.groups().values().forEach(group -> groups.put(바꾼다.apply(group.id()), new DirectoryGroup(
                바꾼다.apply(group.id()), group.externalId(), group.displayName(),
                group.members().stream().map(member -> new MemberRef(member.type(), 바꾼다.apply(member.id())))
                        .collect(Collectors.toSet()))));
        Set<Membership> 지워진 = 지워진멤버십.stream()
                .map(m -> new Membership(바꾼다.apply(m.조직()), new MemberRef(m.멤버().type(), 바꾼다.apply(m.멤버().id()))))
                .collect(Collectors.toSet());
        return new OrgChart(new DirectorySnapshot(users, groups), landmarks.아이디를_바꾼다(바꾼다), 지워진);
    }
```

`Landmarks#아이디를_바꾼다(UnaryOperator<String>)` 는 모든 `String` 칸과 `빈조직들` 을 바꾼 새 `Landmarks` 를 돌려준다.

- [ ] **Step 4: 시드·재생 스크립트**

`ScimSeedWriter` 가 NDJSON 줄에 `차트아이디` 를 함께 쓴다(레코드 직렬화면 저절로 — 확인). `docker/scim/replay.py`:

```python
def translate(request, ids):
    path = request["path"]
    for prefix in ("/scim/v2/Users/", "/scim/v2/Groups/"):
        if path.startswith(prefix):
            path = prefix + ids.get(path[len(prefix):], path[len(prefix):])
    body = request["body"]
    if isinstance(body, dict) and isinstance(body.get("members"), list):
        body = dict(body, members=[dict(m, value=ids.get(m["value"], m["value"])) for m in body["members"]])
    return dict(request, path=path, body=body)
```

`send` 는 상태코드와 응답 본문(JSON)을 함께 돌려주고, `main` 은 `ids = {}` 를 두어 보내기 전에 `translate`, POST 가 201 이면 `ids[request["차트아이디"]] = 응답["id"]`.
모듈 docstring 에 "서버가 id 를 발급하므로 POST 응답의 id 로 뒤 요청을 바꾼다" 한 문장.

- [ ] **Step 5: app-scim 규모 테스트를 번역부로**

각 클래스의 보내기 도우미가 보내기 전에 `번역부.번역한다(request)` 하고, POST 가 201 이고 `request.차트아이디()` 가 있으면 응답 본문의 `id` 를 `번역부.기록한다(request.차트아이디(), id)`.
`검증한다()` 는 `ScaleVerification.두_경로로_검증한다(state, checker, bootstrapper, 번역부.번역한다(기대))`, `성립하는가(tuple)` 은 `번역부.번역한다(tuple)` 로 묻는다.
테스트가 조직도 아이디로 상태를 직접 읽는 곳(`state.findMembers(조직도아이디, …)` 등)은 `번역부.서버(…)` 로. 상태에 직접 심은 아이디(규모 테스트의 `ALL`·`HQ` 같은 시드)는 그대로다.

시나리오를 다시 써야 하는 두 곳(Ruling — 서버가 id 를 정하면 IdP 는 만든 리소스의 id 로만 참조할 수 있다. 아직 없는 리소스를 참조하는 "늦게 도착함"은 SCIM 에서 생기지 않는다):
- `ScimProvisioningOrderScaleTest` S1-a·S1-b(조직을 먼저 만들 때 아직 없는 직원을 참조)는 Entra 의 실제 순서로 다시 쓴다: 조직을 멤버 없이 먼저 만들고 → 직원을 만들고 →
  PATCH 로 멤버를 더하면 그때 튜플이 생긴다. 테스트 수와 `@DisplayName` 의 뜻("순서가 바뀌어도 결국 맞는다")을 유지한다.
- `ScimGroupMemberPatchScaleTest` Order 8("10만 명 조직이 먼저 적어 둔 조직 POST")은 SCIM 에서 생길 수 없다 — "10만 명 조직에 새 조직을 PATCH 로 하위 조직으로 붙여도
  조직 파티션을 훑지 않는다"로 바꾼다(`LATE` 를 POST 로 만든 뒤 `ALL` 에 `add members [{"value":<LATE 서버 id>,"type":"Group"}]`, `queries`·`scannedItems` 상한은 지금 값).
  Order 10 의 `ROOT` 확인은 POST 응답의 id 로 묻는다.
바꾼 시나리오를 보고서에 적는다.

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :connector-scim:test` 그다음 `./gradlew :app-scim:compileTestJava`
Expected: PASS·컴파일 성공. 규모 테스트(`scaleTest`)는 돌리지 않는다 — 컨트롤러가 돌린다.

- [ ] **Step 7: 커밋**

```bash
git add connector-scim/src/testFixtures/ connector-scim/src/test/java/dev/starryeye/organization/scim/fixture/ \
  core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChart.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fixture/Landmarks.java \
  docker/scim/replay.py app-scim/src/test/java/dev/starryeye/organization/scim/app/
git commit -F - <<'EOF'
test: SCIM 하네스가 POST 응답의 서버 id 로 요청·기대값을 번역한다(ScimIdBook), 재생 스크립트도 — 늦게 도착함 시나리오는 Entra 순서로 다시 쓴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 4: app-scim e2e 테스트를 서버 id 로

**Files:**
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/` 의 e2e 테스트 중 HTTP 로 POST 한 뒤 아이디를 쓰는 것
  (`ScimEndToEndTest`, `ScimNameEndToEndTest`, `ScimDriftHealingEndToEndTest`, `ScimGroupMemberPatchEndToEndTest`, `ScimWriteRaceEndToEndTest`, `ScimQueryEndToEndTest`, 그 밖에 깨지는 것)

**Interfaces:**
- Consumes: POST 응답의 서버 id(Task 2), `ScimIdBook`(Task 3, 쓸모 있으면).

- [ ] **Step 1: 깨지는 테스트를 찾는다**

Run: 클래스마다 `./gradlew :app-scim:test --tests '<클래스>'`(한 번에 하나, 포그라운드). 실패를 모은다.

- [ ] **Step 2: 고친다**

POST 뒤 `userName`·`externalId` 에서 만든 아이디로 경로·멤버·튜플을 부르던 곳을 응답의 `id` 로 바꾼다(도우미 `만든_아이디(응답)` — Task 2 와 같은 모양). 테스트의 **뜻**은 바꾸지 않는다.
상태에 직접 심은 아이디는 그대로다. 아이디 모양(`userName` 정규화)을 단정하던 테스트는 UUID 모양·`userName` 원본 단정으로 바꾼다. 바꾼 테스트와 이유를 보고서에 적는다.
테스트가 보던 성질 자체가 서버 id 에서 성립하지 않으면(예: "아이디 충돌" 경합) 고치지 말고 보고서에 적고 멈춘다(DONE_WITH_CONCERNS).

- [ ] **Step 3: 통과를 본다**

Run: 고친 클래스마다 `./gradlew :app-scim:test --tests '<클래스>'`
Expected: PASS. 앱 모듈 전체 `test` 는 돌리지 않는다.

- [ ] **Step 4: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/
git commit -F - <<'EOF'
test: app-scim e2e 테스트가 POST 응답의 서버 id 를 쓴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 5: LDAP 반환 속성 명시(P7), DIT 는 값만 들고 간다

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/PagedLdapSearch.java` (`controlsOf` 가 반환 속성 설정)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`, `DitStrategy.java` (쿼리에 `.attributes(…)`, DIT 엔트리 레코드)
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/ReturningAttributesTest.java`

**Interfaces:**
- Produces: 각 전략의 반환 속성 목록 — 직원 `[식별, 표시명, 메일, cn, userAccountControl, accountExpires, sn, givenName, middleName, generationQualifier]`(Task 6 이 로그인 속성을 더한다),
  그룹 `[식별, 이름, 멤버]`, OU `[식별, 이름]`. 패키지 전용 `static String[] 직원_속성(LdapProperties.GroupOfNames)` 류로 한 곳에 둔다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

UnboundID `InMemoryOperationInterceptor` 로 검색 요청의 속성 목록을 잡는다:

```java
/** 검색이 필요한 속성만 요청한다(설계 2026-10-04 §4.3, 점검 P7). */
class ReturningAttributesTest {

    private InMemoryDirectoryServer server;
    private final List<List<String>> 요청속성 = new CopyOnWriteArrayList<>();

    @BeforeEach
    void 서버를_띄운다() throws Exception {
        var config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.setSchema(null);
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                요청속성.add(List.of(request.getRequest().getAttributes()));
            }
        });
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        // groupOfNames 픽스처 LDIF 를 넣는다 — 직원 엔트리에 thumbnailPhoto 같은 쓰지 않는 속성을 하나 둔다
    }

    @Test
    @DisplayName("groupOfNames 의 직원·그룹 검색이 쓰는 속성만 요청한다 — 모든 속성(빈 목록)을 요청하지 않는다")
    void groupOfNames_는_필요한_속성만_요청한다() {
        // given
        var strategy = new GroupOfNamesStrategy(이름기반_설정());

        // when
        strategy.read(템플릿());

        // then
        assertThat(요청속성).isNotEmpty().allSatisfy(속성 -> assertThat(속성).isNotEmpty());
        assertThat(요청속성).anySatisfy(속성 -> assertThat(속성).contains("uid", "displayName", "mail", "userAccountControl")
                .doesNotContain("thumbnailPhoto"));
        assertThat(요청속성).anySatisfy(속성 -> assertThat(속성).contains("cn", "description", "member"));
    }

    @Test
    @DisplayName("DIT 의 OU·직원 검색도 쓰는 속성만 요청한다")
    void DIT_도_필요한_속성만_요청한다() {
        // (DIT 픽스처로 같은 단정)
    }
}
```

(템플릿·설정 도우미는 `EmbeddedLdapSupport`·기존 전략 테스트의 모양을 따른다. 범위 읽기(`member;range=`)를 하는 보조 검색은 지금 요청 모양 그대로면 된다 — 단정에서 뺀다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-ldap:test --tests '*ReturningAttributesTest'`
Expected: FAIL — 요청 속성이 비어 있다(모든 속성).

- [ ] **Step 3: 구현**

`PagedLdapSearch.controlsOf`:

```java
    private static SearchControls controlsOf(LdapQuery query) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(query.searchScope() == null
                ? SearchControls.SUBTREE_SCOPE
                : query.searchScope().getId());
        // 쓰는 속성만 요청한다 — 비워 두면 서버가 모든 사용자 속성을 준다(AD 10만 명이면 회차마다 1~2GB, 설계 2026-10-04 §4.3).
        // 운영 속성(entryUUID)은 이름을 대야만 온다
        controls.setReturningAttributes(query.attributes());
        return controls;
    }
```

전략의 쿼리에 `.attributes(직원_속성(config))` 등을 단다(`LdapQueryBuilder.query().base(…).attributes(…).where(…)` 순서는 Spring LDAP 규칙을 따른다). 속성 목록은 설정 값에서 만들고
중복·null 을 뺀다. DIT 전략은 매퍼에서 바로 직원(`DirectoryUser` 를 만드는 데 필요한 값과 DN)·OU(DN·식별·이름) 작은 레코드를 만들고 `DirContextAdapter` 를 들고 가지 않는다 —
계정 상태·이름은 매퍼 안에서 `AdAccountStatus`·`LdapPersonName` 으로 계산한다(동기화 시각 `지금` 은 매퍼를 만들 때 잡는다).

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-ldap:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/ connector-ldap/src/test/java/dev/starryeye/organization/ldap/ReturningAttributesTest.java
git commit -F - <<'EOF'
feat: LDAP 검색이 쓰는 속성만 요청한다(점검 P7) — DIT 는 원본 엔트리 대신 값만 들고 간다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 6: LDAP 불변 식별자 — 기본 `entryUUID`, `objectGUID`, 로그인 속성, 표시명 대체

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapIdentifiers.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapProperties.java` (기본값 `entryUUID`, `userLoginAttribute`), `LdapConfig.java` (`jndiTimeouts` → `jndiEnvironment`, 이진 선언)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`, `DitStrategy.java`, `LdapDns.java`(첫 RDN 값)
- Modify: `app-ldap/src/main/resources/application.yml`(기본 `entryUUID`·`user-login-attribute`), `app-ldap/src/test/resources/application-test.yml`(기존 테스트는 이름 기반을 명시 — groupOfNames·DIT 둘 다)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/ImmutableIdentifierTest.java`(새), `LdapIdentifiersTest.java`(새), 기본값에 기대던 기존 테스트

**Interfaces:**
- Consumes: 반환 속성 목록(Task 5) — 로그인 속성을 더한다.
- Produces: `LdapProperties.GroupOfNames#userLoginAttribute`·`LdapProperties.Dit#userLoginAttribute`(기본 `"uid"`), 식별 속성 기본값 `"entryUUID"`.
  `LdapIdentifiers.식별값(Attributes attributes, String attribute, String dn) : String`(없으면 null), `LdapIdentifiers.guid(byte[]) : String`, `LdapIdentifiers.이진인가(String) : boolean`,
  `LdapConfig.jndiEnvironment(LdapProperties) : Map<String, Object>`, `LdapDns.첫_RDN_값(String dn) : String`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`LdapIdentifiersTest`:

```java
    @Test
    @DisplayName("objectGUID 16바이트를 AD 도구가 보여 주는 GUID 문자열로 바꾼다 — 앞 세 묶음은 바이트 순서를 뒤집는다")
    void objectGUID_를_GUID_문자열로_바꾼다() {
        // given — AD 의 a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80
        byte[] 바이트 = HexFormat.of().parseHex("d4c3b2a1f6e51807292a3b4c5d6e7f80");

        // when
        String guid = LdapIdentifiers.guid(바이트);

        // then
        assertThat(guid).isEqualTo("a1b2c3d4-e5f6-0718-292a-3b4c5d6e7f80");
    }

    @Test
    @DisplayName("objectGUID 가 16바이트가 아니면 데이터 오류다")
    void objectGUID_가_16바이트가_아니면_데이터_오류다() {
        // when, then
        assertThatThrownBy(() -> LdapIdentifiers.guid(new byte[15])).isInstanceOf(DirectoryDataException.class);
    }

    @Test
    @DisplayName("식별 속성이 objectGUID 면 JNDI 환경에 이진 속성으로 선언한다")
    void objectGUID_는_이진으로_선언한다() {
        // given
        var properties = new LdapProperties();
        properties.getGroupOfNames().setUserIdAttribute("objectGUID");
        properties.getGroupOfNames().setGroupIdAttribute("objectGUID");

        // when
        var 환경 = LdapConfig.jndiEnvironment(properties);

        // then
        assertThat(환경).containsEntry("java.naming.ldap.attributes.binary", "objectGUID");
    }
```

`ImmutableIdentifierTest`(임베디드 서버, 기본 설정 = `entryUUID`):

```java
    @Test
    @DisplayName("기본 설정에서 직원·조직 id 는 entryUUID 다 — groupOfNames")
    void 기본_id_는_entryUUID_다() {
        // given — 기본값(식별 속성을 지정하지 않음)
        var strategy = new GroupOfNamesStrategy(기본값_그대로(), 고정시계);

        // when
        var snapshot = strategy.read(템플릿());

        // then — 서버가 유지하는 entryUUID 와 같다
        String kim = entryUUID("uid=kim,ou=people,dc=example,dc=com");
        assertThat(snapshot.users()).containsKey(kim);
        assertThat(snapshot.users().get(kim).userName()).isEqualTo("kim");
    }

    @Test
    @DisplayName("ou·cn·uid 를 바꿔도(ModifyDN) 같은 id 다 — 이름 변경이지 삭제+생성이 아니다")
    void 개명은_같은_id_다() {
        // given
        String 전 = entryUUID("cn=DEV002,ou=groups,dc=example,dc=com");

        // when
        server.modifyDN("cn=DEV002,ou=groups,dc=example,dc=com", "cn=PLATFORM", true);
        var snapshot = new GroupOfNamesStrategy(기본값_그대로(), 고정시계).read(템플릿());

        // then
        assertThat(snapshot.groups()).containsKey(전);
        assertThat(snapshot.groups().get(전).externalId()).startsWith("cn=PLATFORM");
    }

    @Test
    @DisplayName("직원 userName 은 로그인 속성의 원본 값이다 — 정규화하지 않는다(점검 S25)")
    void userName_은_원본이다() {
        // given — uid: "hong gd" 인 직원
        // when / then: userName 은 "hong gd", id 는 entryUUID
    }

    @Test
    @DisplayName("로그인 속성이 없으면 userName 을 식별 값으로 대신한다")
    void 로그인_속성이_없으면_식별값으로_대신한다() { … }

    @Test
    @DisplayName("조직 이름 속성이 없으면 표시명은 DN 의 첫 RDN 값이다")
    void 조직명이_없으면_RDN_값이다() { … }

    @Test
    @DisplayName("DIT 기본 설정에서도 OU·직원 id 는 entryUUID 이고, OU 개명은 같은 id 다")
    void DIT_도_entryUUID_다() { … }

    @Test
    @DisplayName("식별 속성을 objectGUID 로 두면 이진 값을 GUID 문자열 id 로 읽는다")
    void objectGUID_로_읽는다() {
        // given — 스키마 없는 임베디드 서버에 objectGUID 이진 값을 단 직원, 컨텍스트 소스는 LdapConfig 로 만든다(이진 선언이 들어가게)
        // when / then: id == LdapIdentifiers.guid(그 바이트)
    }
```

(`entryUUID(dn)` 은 `server.getEntry(dn, "entryUUID")` 로 읽는 도우미. UnboundID 임베디드 서버는 운영 속성 `entryUUID` 를 만들고 ModifyDN 에도 유지한다 — 유지하지 않으면
보고서에 적고 개명 테스트를 그 사실에 맞게 바꾼다(id 가 그대로라는 단정은 바꾸지 않는다 — 그 경우 멈춘다).)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-ldap:test --tests '*LdapIdentifiersTest' --tests '*ImmutableIdentifierTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`LdapIdentifiers`:

```java
/**
 * 식별 값을 한 곳에서 읽는다(설계 2026-10-04 §4.1). {@code objectGUID}(AD)는 이진 값이라 표준 GUID 문자열로 바꾸고, 그 밖의 속성은 문자열 그대로 읽는다.
 * {@code objectGUID} 를 이진으로 받으려면 컨텍스트 소스의 JNDI 환경에 선언돼 있어야 한다({@code LdapConfig#jndiEnvironment}).
 */
final class LdapIdentifiers {

    static final String OBJECT_GUID = "objectGUID";

    private LdapIdentifiers() {
    }

    static boolean 이진인가(String attribute) {
        return OBJECT_GUID.equalsIgnoreCase(attribute);
    }

    /** 식별 값. 없으면 null. {@code objectGUID} 가 16바이트가 아니면 {@link DirectoryDataException}. */
    static String 식별값(Attributes attributes, String attribute, String dn) throws NamingException {
        Attribute 값 = attributes.get(attribute);
        if (값 == null || 값.size() == 0) {
            return null;
        }
        Object 첫값 = 값.get();
        if (이진인가(attribute)) {
            if (!(첫값 instanceof byte[] 바이트)) {
                throw new DirectoryDataException("objectGUID 가 이진으로 오지 않았습니다 — 컨텍스트 소스의 이진 선언을 확인하세요: " + dn);
            }
            return guid(바이트, dn);
        }
        return 첫값.toString();
    }

    static String guid(byte[] b) {
        return guid(b, "(알 수 없음)");
    }

    /** AD 도구가 보여 주는 GUID 문자열 — 앞 세 묶음은 리틀 엔디언이라 바이트를 뒤집는다. */
    private static String guid(byte[] b, String dn) {
        if (b.length != 16) {
            throw new DirectoryDataException("objectGUID 는 16바이트여야 합니다(%d바이트): %s".formatted(b.length, dn));
        }
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".formatted(
                b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
    }
}
```

(`DirectoryDataException` 의 실제 생성자를 따른다.)

`LdapConfig`: `jndiTimeouts` 를 `jndiEnvironment` 로 바꾸고, 네 식별 속성(groupOfNames 둘, DIT 둘) 중 `이진인가` 인 것이 있으면 `"java.naming.ldap.attributes.binary"` 에 그 이름들을
공백으로 이어 넣는다. 부르는 곳(`ldapContextSource`, `LdapTimeoutTest`)을 고친다. `LdapIdentifiers` 를 config 에서 쓰려면 public 으로 둔다.

`LdapProperties`: 네 식별 속성 기본값 `"entryUUID"`, `userLoginAttribute = "uid"`(두 전략). 자바독: "불변 id(설계 2026-10-04 §4.1). AD 는 objectGUID. 이름 기반(uid/cn/ou)도 쓸 수 있지만 개명이 삭제+생성이다."

전략: 식별 값은 `LdapIdentifiers.식별값(…)` 으로 읽고 `IdNormalizer.normalize` 를 지금처럼 건다(UUID·GUID 는 그대로다). 없으면 지금처럼 필수 속성 오류. `userName` 은
`user-login-attribute` 의 **원본 값**(없으면 식별 값). 조직 표시명 대체는 식별 값이 아니라 `LdapDns.첫_RDN_값(dn)`(새 정적 메서드 — `LdapName` 으로 파싱해 가장 왼쪽 RDN 의 값).
반환 속성 목록에 로그인 속성을 더한다(Task 5 의 도우미).

`app-ldap/src/main/resources/application.yml`: 두 전략의 `user-id-attribute`·`group-id-attribute` 를 `entryUUID` 로, `user-login-attribute: uid` 를 더한다(로컬 docker OpenLDAP 은
`entryUUID` 를 유지한다). `app-ldap/src/test/resources/application-test.yml` 은 기존 테스트가 이름 기반 기대값을 쓰므로 groupOfNames(`uid`/`cn`)와 DIT(`uid`/`ou`)를 **명시**하고
`user-login-attribute: uid` 를 둔다 — 주석 "기존 e2e·규모 테스트는 이름 기반으로 돈다. 기본값(entryUUID)은 LdapImmutableIdEndToEndTest·LdapEntryUuidScaleTest 가 본다(설계 §8)".

- [ ] **Step 4: 기본값에 기대던 테스트를 고친다**

connector-ldap 테스트 중 `new LdapProperties()` 의 기본 식별 속성(`uid`/`cn`/`ou`)에 기대던 것은 이름 기반을 명시한다 — 테스트 도우미 하나로 모은다(예: `EmbeddedLdapSupport` 나
testFixtures 에 `static LdapProperties 이름기반()`). 식별자가 주제가 아닌 테스트의 기대값은 바꾸지 않는다. 바꾼 파일을 보고서에 적는다.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :connector-ldap:test` 그다음 `./gradlew :app-ldap:compileTestJava`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add connector-ldap/src/ app-ldap/src/main/resources/application.yml app-ldap/src/test/resources/application-test.yml
git commit -F - <<'EOF'
feat: LDAP 식별 속성 기본값을 entryUUID 로, objectGUID 는 이진 → GUID 문자열, userName 은 로그인 속성 원본(S25), 조직명 대체는 RDN 값(점검 M9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 7: app-ldap 기본값(entryUUID) e2e·규모 테스트

**Files:**
- Create: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapImmutableIdEndToEndTest.java`
- Create: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapEntryUuidScaleTest.java` (`@ScaleTest`)

**Interfaces:**
- Consumes: Task 6 의 기본값·설정 이름.

- [ ] **Step 1: e2e 테스트**

기존 app-ldap e2e 테스트(임베디드 UnboundID + Testcontainers DynamoDB·OpenFGA)의 모양을 따르고, `@DynamicPropertySource` 로 식별 속성을 `entryUUID`·로그인 `uid` 로 덮는다.

```java
    @Test
    @DisplayName("기본값(entryUUID)으로 전체 동기화하면 직원·조직 id 가 entryUUID 이고 userName 은 uid 다")
    void entryUUID_로_동기화한다() { … }

    @Test
    @DisplayName("OU·그룹·uid 개명 뒤 동기화는 같은 id 의 이름 변경이다 — 삭제 가드에 걸리지 않고 권한이 그대로다")
    void 개명은_삭제_가드에_걸리지_않는다() {
        // given — 첫 동기화 뒤 조직 하나를 ModifyDN 으로 개명(그 조직 직속이 기준선의 30% 를 넘게 픽스처를 잡는다)
        // when — POST /admin/sync/full
        // then — SUCCEEDED, 그 조직 id 그대로, 직속 직원 member Check 참
    }
```

(DIT 전략 한 건도 같은 모양으로 — OU 개명.)

- [ ] **Step 2: 규모 테스트**

`LdapScaleSyncCostTest` 의 시드·측정 모양을 따라 같은 크기의 조직도를 `entryUUID` 설정으로 전체 동기화하고, 직원 수·id 모양(UUID)·소요 시간을 남긴다. 기존 규모 테스트는 이름 기반 그대로다.

- [ ] **Step 3: 통과를 본다**

Run: `./gradlew :app-ldap:test --tests '*LdapImmutableIdEndToEndTest'` 그다음 `./gradlew :app-ldap:compileTestJava`(규모 테스트는 컨트롤러가 `scaleTest` 로 돌린다)
Expected: PASS.

- [ ] **Step 4: 커밋**

```bash
git add app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapImmutableIdEndToEndTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapEntryUuidScaleTest.java
git commit -F - <<'EOF'
test: app-ldap 기본값(entryUUID) e2e — 개명은 같은 id 라 삭제 가드에 걸리지 않는다, 규모 테스트 하나

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 8: 관리 API `?externalId=`

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java`
- Modify: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminQueryController.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java`, admin-api 의 컨트롤러 테스트

**Interfaces:**
- Consumes: `findUserIdsByExternalId`·`findGroupIdsByExternalId`(Task 1).
- Produces: `AdminQueryUseCase#findEmployeesByExternalId(String) : Mono<Page<UserSummary>>`, `#findOrganizationsByExternalId(String) : Mono<Page<GroupSummary>>`(한 페이지, `nextCursor` null).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
    @Test
    @DisplayName("externalId 로 직원을 정확히 찾는다 — IdP 의 사용자 id 로 우리 id 를 얻는 길")
    void externalId_로_직원을_찾는다() {
        // given
        state.users.put("u-1", new DirectoryUser("u-1", "okta-00u1", "kim", "김", null, true));
        state.users.put("u-2", new DirectoryUser("u-2", "okta-00u2", "lee", "이", null, true));

        // when
        var page = useCase.findEmployeesByExternalId("okta-00u1").block();

        // then
        assertThat(page.items()).extracting(UserSummary::employeeId).containsExactly("u-1");
        assertThat(page.nextCursor()).isNull();
    }
```

(조직도 같은 모양. 컨트롤러: `GET /admin/employees?externalId=okta-00u1` 200 과 한 줄, `userName`·`displayName`·`externalId` 중 둘 이상이면 400, `GET /admin/organizations?externalId=`.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest'` 그다음 `./gradlew :admin-api:test`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

```java
    /** {@code externalId} 로 정확히 찾는다(설계 2026-10-04 §5). GSI3 후보를 본 테이블로 다시 확인한다 — 한 페이지다. */
    public Mono<Page<UserSummary>> findEmployeesByExternalId(String externalId) {
        return state.findUserIdsByExternalId(externalId)
                .concatMap(state::findUser)
                .filter(user -> externalId.equals(user.externalId()))
                .map(user -> new UserSummary(user.id(), user.userName(), user.displayName(), user.active()))
                .collectList()
                .map(items -> new Page<>(items, null));
    }

    public Mono<Page<GroupSummary>> findOrganizationsByExternalId(String externalId) {
        return state.findGroupIdsByExternalId(externalId)
                .concatMap(state::findGroupHeader)
                .filter(header -> externalId.equals(header.externalId()))
                .map(header -> new GroupSummary(header.id(), header.displayName()))
                .collectList()
                .map(items -> new Page<>(items, null));
    }
```

컨트롤러: `searchEmployees` 에 `@RequestParam(required = false) String externalId` — 셋 중 **정확히 하나**가 아니면 400("userName·displayName·externalId 중 정확히 하나를
지정해야 한다"), `externalId` 면 위 메서드. `searchOrganizations` 도 `displayName`·`externalId` 중 정확히 하나.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest'` 그다음 `./gradlew :admin-api:test`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java \
  admin-api/src/
git commit -F - <<'EOF'
feat: 관리 API 가 externalId 로 직원·조직을 정확히 찾는다 — 권한을 묻는 앱이 IdP 사용자 id 로 우리 id 를 얻는 길

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 9: README, 점검 문서, 스펙 보정

**Files:**
- Modify: `README.md`, `docs/superpowers/specs/2026-09-28-full-audit.md`, `docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md`

- [ ] **Step 1: README**

- "인가 모델": 튜플의 식별자는 **불변 id**(SCIM 서버 발급 UUID, LDAP `entryUUID`/`objectGUID`)와 조직의 불변 id 다. 권한을 묻는 앱은 로그인한 사용자를 우리 id 로 바꾼다(아래 조회 API).
- "조회 API — 식별자 셋": `employeeId`·`orgCode` 는 불변 id, `userName` 은 원본 계정명(LDAP 은 `user-login-attribute`), `displayName`. 예시를 UUID 로. 표에 `?externalId=`(정확히 일치) 두 줄.
- `## LDAP`: 식별 속성 기본 `entryUUID`, AD 는 `objectGUID`(이진 → GUID 문자열, 자동 선언), `user-login-attribute`(기본 `uid`, AD `sAMAccountName`), 이름 기반도 쓸 수 있지만
  개명이 삭제+생성이고 큰 조직 개명은 삭제 가드가 멈춘다. 검색은 쓰는 속성만 요청한다.
- `## SCIM`: `id` 는 서버가 발급한다, `userName` 중복·조직 `externalId` 중복은 409, 아직 없는 리소스는 참조할 수 없다(IdP 는 받은 id 로만 참조).
- "옮기기"(새 절 또는 기존 운영 절): app-scim 은 `mode=wipe` 뒤 IdP 재프로비저닝(또는 테이블·store 새로), app-ldap 은 첫 동기화를 `POST /admin/sync/full?force=true` 로 한 번.

- [ ] **Step 2: 점검 문서**

요약 표의 M7·M9·P7 행과 S25 행 끝에 `**→ 해결(2026-10-04, 슬라이드 ④-1)**`(③ 의 표시와 같은 모양).

- [ ] **Step 3: 스펙 보정**

- §3.2: 조직 `externalId` 를 **바꾸는** PUT·PATCH 도 같은 확인을 한다(직원 `userName` 변경과 같다) — 한 문장.
- §7·§8: LDAP 기존 e2e·규모 테스트는 test 프로필이 이름 기반(`uid`/`cn`/`ou`)을 명시해 그대로 돌고, 기본값 `entryUUID` 는 새 전략 테스트·`LdapImmutableIdEndToEndTest`·
  `LdapEntryUuidScaleTest` 가 본다. SCIM 하네스는 `ScimIdBook` 으로 번역한다. 늦게 도착함 시나리오는 Entra 순서로 다시 썼다.
- §11 에 더한다: (a) `userName`·조직 `externalId` 중복 판정은 GSI 후보를 본 테이블로 다시 확인한다 — 막 저장돼 GSI 에 아직 없는 리소스와는 겹칠 수 있다(직원 `userName` 의 기존 틈과 같다).
  (b) SCIM 에서 "아직 없는 리소스를 먼저 참조"하는 경로는 이제 생기지 않는다 — 상위 조직이 새 조직을 먼저 적어 두는 처리(`상위_조직들`)는 SCIM 에서 비게 된다(코드는 남는다).

- [ ] **Step 4: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md
git commit -F - <<'EOF'
docs: ④-1 README(불변 id, 식별자 셋, LDAP 식별 속성, 옮기기), 점검 M7·M9·P7·S25 해결 표시, 설계 §3.2·§7·§8·§11 보정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 그다음 `./gradlew cleanScaleTest scaleTest` 를 한 번에 하나씩 돌린다.
- 스펙 §9 에 결과(테스트 수·시간, `LdapEntryUuidScaleTest` 시간)를 적고 커밋한다.
