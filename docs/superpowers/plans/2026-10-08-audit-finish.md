# 점검 ⑥-3 마무리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 ⑥ 의 남은 사소 셋을 닫는다.
- S19: 직원 표시명 검색이 대소문자를 가리지 않게 한다.
- S16 앞쪽: 관리 API 검색 커서를 위조하면 400 이 되게 한다.
- S27: 조직 상세의 이름표 읽기를 병렬로 바꾼다.

**Architecture:**
- **storage-dynamodb.**
  - 직원 META 에 소문자 표시명 `displayNameKey` 를 쓴다. GSI2 정렬키를 그 속성으로 바꾸고, GSI2 가 옛 모양이면 `TableInitializer` 가 기동을 멈춘다.
  - 검색 셋이 함께 쓰는 `query` 가 커서 시작 키를 검사한다.
- **core.** `AdminQueryUseCase` 의 상위·하위 조직 이름표 읽기를 `flatMapSequential`(동시 8)로 바꾼다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor(`flatMapSequential`·`VirtualTimeScheduler`), AWS SDK v2 DynamoDB, JUnit 5, AssertJ, WebTestClient, Testcontainers(DynamoDB Local).

**Spec:** `docs/superpowers/specs/2026-10-08-audit-finish-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다. 합친 `// when, then` 도 된다. 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.**
  - 이름·주석·메시지는 한국어 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다.
  - 클래스 이름은 영어다.
  - 메서드 이름은 그 파일의 관례를 따른다. `TableInitializer`·`Keys` 는 영어, `DynamoDbDirectorySearchRepository` 의 검사 도우미는 한국어(`시작_키를_확인한다` 옆)다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.**
  - 한 번에 하나씩 포그라운드로 돌린다.
  - 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML 에서 옮긴다. 어림하지 않는다.
- **S19 값.**
  - 새 속성 이름은 `displayNameKey` 이고, 값은 `Keys.indexKey(displayName)`(`toLowerCase(Locale.ROOT)`)다.
  - 직원 META 에만 쓴다. 표시명이 없거나 빈 문자열이면 쓰지 않는다.
  - GSI2 는 파티션키 `GSI1PK`, 정렬키 `displayNameKey`, 프로젝션 INCLUDE `userName`·`displayName`·`active` 다.
- **옛 테이블.**
  - GSI2 가 없거나 정렬키가 `displayNameKey` 가 아니면 `ensureTable` 이 `IllegalStateException` 으로 실패한다.
  - 메시지는 "테이블 '<이름>' 의 인덱스 GSI2 가 이 버전과 다르다(정렬키 <지금 값 또는 '인덱스 없음'>) — 테이블을 다시 만들어야 한다" 다.
  - 인덱스를 더해 주는 길(`addMissingIndex`)은 없앤다.
- **S16 검사.** 하나라도 어긋나면 `IllegalArgumentException("이 검색의 커서가 아니다")` 다.
  - 키 속성 = `{PK, SK, 인덱스 파티션키, 인덱스 정렬키}`.
  - 인덱스 파티션키 = 이 파티션.
  - 인덱스 정렬키가 이번 접두사로 시작하고 1024바이트(UTF-8) 이하.
  - `PK` 가 `USER#`(USER_INDEX)·`GROUP#`(GROUP_INDEX)로 시작.
  - `SK` = `META`.
- **S27.** `expandParents`·`childrenOf` 의 `concatMap(this::loadGroupOrEmpty)` → `flatMapSequential(this::loadGroupOrEmpty, LOAD_CONCURRENCY)`(8). 순서를 지킨다.
- **이관 코드 없음.** 운영 배포 전이라 데이터를 초기화할 수 있다.

## Review Focus

1. **빈 문자열 표시명.** DynamoDB 는 인덱스 키 속성에 빈 문자열을 받지 않는다(PutItem `ValidationException`). 그래서 `displayNameKey` 를 쓰지 않아야 한다. `Attrs.putIfPresent` 가 빈 값을 건너뛰는지 Task 1 테스트로 확인한다.
2. **검색어를 바꾼 채 이전 커서를 다시 보낸 요청.** 위조가 아니라 흔한 클라이언트 실수다. 400 이어야 하고 500 이면 안 된다 — Task 2 테스트.
3. **표시명이 대소문자만 다른 두 직원(`Kim`·`kim`).** 둘 다 찾혀야 한다. 정렬키가 같아도 GSI 는 중복을 허용한다 — Task 1 테스트.
4. **옛 GSI2 가 그대로인 테이블.** 기동이 메시지와 함께 멈춰야 한다. 조용히 지나가 실행 중에 500 이 나면 안 된다 — Task 1 테스트.
5. **상위 조직 수집의 순서와 상한.** 병렬로 바꿔도 결과 순서와 `MAX_PATHS` 잘림이 같아야 한다 — Task 3 테스트(지연 없는 실행과 같은 결과).

---

### Task 1: S19 — 직원 표시명 검색을 소문자 키로 (storage + core 포트·가짜)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (GSI2 자바독·`GSI2SK`, ~27-52)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`userItem` ~152-175)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java` (`ensureTable`·`userDisplayNameIndex`·`addMissingIndex` → 모양 검사, import)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (`searchUsersByDisplayName` ~46-53)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Cursor.java` (자바독 ~20 의 GSI2 키 이름)
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java` (~30-35 자바독)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java` (`searchUsersByDisplayName` ~116-124)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java` (더함, 주석 하나 고침)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableInitializerTest.java` (바꿈)

**Interfaces:**
- Produces: `Keys.GSI2SK == "displayNameKey"`. `searchUsersByDisplayName` 은 대소문자를 가리지 않는다. Task 2 의 커서 검사는 GSI2 커서의 정렬키 속성 이름으로 `Keys.GSI2SK` 를 쓴다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectorySearchRepositoryTest.java` 에 더한다. 필요하면 import 를 더한다: `software.amazon.awssdk.services.dynamodb.model.GetItemRequest`, `software.amazon.awssdk.services.dynamodb.model.AttributeValue`.

```java
    @Test
    @DisplayName("표시명 검색은 대소문자를 가리지 않는다 — 결과의 표시명은 저장한 그대로다(점검 S19)")
    void 표시명_검색은_대소문자를_가리지_않는다() {
        // given — 대소문자만 다른 두 직원
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();
        state.saveUser(new DirectoryUser("u2", "e2", "u2", "kim younghee", null, true)).block();

        // when
        var 대문자 = search.searchUsersByDisplayName("KIM", null, 20).block();
        var 소문자 = search.searchUsersByDisplayName("kim", null, 20).block();

        // then — 프로젝션이 표시명을 실어 온다(정렬키가 아니게 된 displayName)
        assertThat(대문자.items()).extracting(UserSummary::displayName)
                .containsExactlyInAnyOrder("Kim Chulsoo", "kim younghee");
        assertThat(소문자.items()).extracting(UserSummary::employeeId).containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    @DisplayName("직원 META 에는 소문자 표시명 키가 있고, 조직 META 에는 없다 — 조직은 GSI2 에 실리지 않는다")
    void 소문자_표시명_키는_직원에만_있다() {
        // given
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();
        state.saveGroup(new DirectoryGroup("PR001", "g1", "Kim Team", Set.of())).block();

        // when
        Map<String, AttributeValue> 직원 = 원본("USER#u1");
        Map<String, AttributeValue> 조직 = 원본("GROUP#PR001");

        // then
        assertThat(직원.get("displayNameKey").s()).isEqualTo("kim chulsoo");
        assertThat(직원.get("displayName").s()).isEqualTo("Kim Chulsoo");
        assertThat(조직).doesNotContainKey("displayNameKey");
    }

    @Test
    @DisplayName("표시명을 바꾸면 소문자 키도 따라 바뀐다 — 옛 이름으로는 안 찾힌다")
    void 표시명을_바꾸면_키도_바뀐다() {
        // given
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Kim Chulsoo", null, true)).block();

        // when
        state.saveUser(new DirectoryUser("u1", "e1", "u1", "Lee Chulsoo", null, true)).block();

        // then
        assertThat(search.searchUsersByDisplayName("kim", null, 20).block().items()).isEmpty();
        assertThat(search.searchUsersByDisplayName("LEE", null, 20).block().items())
                .extracting(UserSummary::employeeId).containsExactly("u1");
    }

    @Test
    @DisplayName("표시명이 빈 문자열인 직원도 저장되고 표시명 검색에는 안 잡힌다 — 인덱스 키에 빈 문자열을 쓰지 않는다")
    void 빈_표시명은_키를_쓰지_않는다() {
        // when — DynamoDB 는 인덱스 키 속성의 빈 문자열을 거절한다. 쓰면 이 저장이 ValidationException 이다
        state.saveUser(new DirectoryUser("blank", "e9", "blank", "", null, true)).block();

        // then
        assertThat(원본("USER#blank")).doesNotContainKey("displayNameKey");
        assertThat(search.searchUsersByDisplayName("", null, 20).block().items())
                .extracting(UserSummary::employeeId).doesNotContain("blank");
    }

    /** 본 테이블의 META 아이템 원본. */
    private Map<String, AttributeValue> 원본(String pk) {
        return client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(Keys.META)))
                .consistentRead(true)
                .build()).join().item();
    }
```

같은 파일의 `조직은_직원_표시명_검색에_안_섞인다` 의 given 주석을 바꾼다. 지금 주석은 "GSI2 는 GSI1PK 를 파티션키로 공유하므로 조직 META 도 이 인덱스에 실린다..." 이다. 새 주석: "// given — 조직 META 에는 소문자 표시명 키가 없어 GSI2 에 실리지 않는다. 이름까지 같은 접두사로 겹치게 두어 확인한다."

`TableInitializerTest.java` 를 바꾼다.

1. `테이블과_GSI를_생성한다` 의 GSI2 단언과 그 위 주석을 다음으로 바꾼다.

```java
        // GSI2 의 정렬키는 직원 META 에만 쓰는 소문자 표시명이다(설계 2026-10-08 §3.2). 글자로 적는 이유는
        // 상수가 옛 값으로 돌아가도 상수를 통한 단언은 그대로 통과하기 때문이다.
        var gsi2 = described.globalSecondaryIndexes().stream()
                .filter(i -> Keys.GSI2.equals(i.indexName())).findFirst().orElseThrow();
        assertThat(gsi2.keySchema()).extracting(k -> k.attributeName())
                .containsExactly("GSI1PK", "displayNameKey");
        // 표시명은 이제 키가 아니므로 프로젝션에 명시돼야 검색 결과의 표시명 칸이 빈칸이 되지 않는다
        assertThat(gsi2.projection().nonKeyAttributes())
                .containsExactlyInAnyOrder("userName", "displayName", "active");
```

2. `옛_테이블에_GSI2를_보강한다`·`옛_아이템도_표시명으로_찾힌다` 두 테스트와 도우미 `GSI2가_백필을_마칠_때까지_기다린다`(그리고 그 위 자바독)를 지우고 다음 둘로 바꾼다.

```java
    @Test
    @DisplayName("GSI2 없이 만들어진 옛 테이블이면 기동을 멈춘다 — 인덱스를 더해도 옛 직원은 새 키가 없어 실리지 않는다(설계 2026-10-08 §3.4)")
    void GSI2_없는_옛_테이블이면_멈춘다() {
        // given
        GSI2_없는_옛_테이블을_만든다();

        // when, then
        assertThatThrownBy(() -> new TableInitializer(client, properties).ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Keys.GSI2)
                .hasMessageContaining("인덱스 없음")
                .hasMessageContaining("다시 만들어야");
    }

    @Test
    @DisplayName("GSI2 정렬키가 옛 displayName 인 테이블이면 기동을 멈춘다 — 그대로 두면 표시명 검색만 실행 중에 500 이다")
    void 옛_GSI2_테이블이면_멈춘다() {
        // given — 이 슬라이드 전의 GSI2(정렬키 displayName)
        client.deleteTable(DeleteTableRequest.builder().tableName(properties.getTableName()).build()).join();
        client.createTable(CreateTableRequest.builder()
                .tableName(properties.getTableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attribute(Keys.PK), attribute(Keys.SK), attribute(Keys.GSI1PK), attribute("displayName"))
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.SK).keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(Keys.GSI2)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("displayName").keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build()).join();

        // when, then
        assertThatThrownBy(() -> new TableInitializer(client, properties).ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정렬키 displayName")
                .hasMessageContaining("다시 만들어야");
    }
```

3. import 를 정리한다.
   - 쓰지 않게 된 `Awaitility`, `IndexStatus`, `PutItemRequest`, `UserSummary`, `Map`, `Duration` 을 지운다(남은 쓰임이 있으면 둔다).
   - `static org.assertj.core.api.Assertions.assertThatThrownBy` 를 더한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectorySearchRepositoryTest' --tests '*TableInitializerTest'`
Expected: 실패 — 대소문자 검색이 `u2` 를 못 찾는다, `displayNameKey` 가 없다, GSI2 정렬키가 `displayName` 이다, 옛 테이블에서 예외가 없다.

- [ ] **Step 3: 구현한다**

`Keys.java` — GSI2 자바독과 두 상수를 다음으로 바꾼다:

```java
    public static final String GSI2 = "GSI2";

    /**
     * GSI2 — 관리 API 의 직원 표시명 접두사 검색. 파티션키는 {@link #GSI1PK} 를 그대로 쓰고({@link #USER_INDEX}), 정렬키는 직원 META 에만
     * 쓰는 소문자 표시명 {@link #GSI2SK} 다 — {@code userName}·조직명 검색처럼 대소문자를 가리지 않는다(설계 2026-10-08 §3, 점검 S19).
     *
     * <p><b>조직은 실리지 않는다.</b> 조직 META 에는 {@link #GSI2SK} 를 쓰지 않는다. DynamoDB 는 인덱스의 키 속성을 전부 가진 아이템만
     * 싣는다. 표시명이 없는(또는 빈) 직원도 같은 까닭으로 실리지 않는다 — 아이디·계정명으로는 여전히 찾힌다.
     *
     * <p><b>옛 테이블은 다시 만든다.</b> 예전 GSI2 는 백필이 기존 아이템을 싣도록 {@code displayName} 을 그대로 정렬키로 썼다. 새 키 속성은
     * 옛 아이템에 없어 인덱스를 더해도 백필이 싣지 못한다. 운영 배포 전이라 테이블을 다시 만들고, 옛 모양이면 {@link TableInitializer} 가
     * 기동을 멈춘다.
     */
    public static final String GSI2PK = GSI1PK;
    /** @see #GSI2PK — 직원 META 에만 쓰는 소문자 표시명({@link #indexKey}). */
    public static final String GSI2SK = "displayNameKey";
```

`DynamoDbDirectoryStateRepository.userItem` — GSI2 주석 다섯 줄(`// GSI2(표시명 검색)를 위해 따로 쓸 것이 없다 …`)을 지운다. `Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());` 바로 아래에 다음을 더한다:

```java
        // GSI2(표시명 검색)의 정렬키 — 소문자라 대소문자를 가리지 않는다(Keys.GSI2SK). 표시명이 없거나 비면 쓰지 않는다:
        // 그 직원은 GSI2 에 실리지 않고, DynamoDB 는 인덱스 키에 빈 문자열을 받지 않는다. 조직 META 에는 쓰지 않는다.
        Attrs.putIfPresent(item, Keys.GSI2SK, user.displayName() == null ? null : Keys.indexKey(user.displayName()));
```

`TableInitializer.java`:

1. `ensureTable` 의 `return addMissingIndex(table, response);` 를 `return requireCurrentDisplayNameIndex(table, response);` 로 바꾼다.
2. `userDisplayNameIndex()` 의 프로젝션을 `.nonKeyAttributes("userName", "displayName", "active")` 로 바꾼다.
3. 그 자바독의 첫 문단과 키 속성 문장을 다음 뜻으로 고친다.
   - 정렬키는 직원 META 에만 쓰는 소문자 표시명({@link Keys#GSI2SK})이다.
   - 키 속성(PK/SK/GSI1PK/displayNameKey)은 자동으로 실린다.
   - `displayName` 은 이제 키가 아니므로 프로젝션에 적는다.
   - "프로젝션 목록은 인덱스가 생성될 때 한 번 굳는다" 문단과 세 곳이 같아야 한다는 문단은 그대로 둔다.
4. `addMissingIndex` 메서드와 그 자바독을 지우고 다음을 둔다:

```java
    /**
     * 이미 있는 테이블의 GSI2 가 이 버전의 모양인지 본다(설계 2026-10-08 §3.4). 없거나 정렬키가 {@link Keys#GSI2SK} 가 아니면 기동을 멈춘다.
     *
     * <p>더해 주지 않는 까닭: 새 키 속성은 옛 아이템에 없어 인덱스를 더해도 백필이 옛 직원을 싣지 못한다 — 표시명 검색이 조용히 빈다.
     * 옛 GSI2 를 그대로 두면 표시명 검색만 실행 중에 {@code ValidationException}(500)이다. 운영 배포 전이라 테이블을 다시 만든다.
     */
    private Mono<Void> requireCurrentDisplayNameIndex(String table, DescribeTableResponse response) {
        List<GlobalSecondaryIndexDescription> indexes = response.table().globalSecondaryIndexes() == null
                ? List.of() : response.table().globalSecondaryIndexes();
        String sortKey = indexes.stream()
                .filter(index -> Keys.GSI2.equals(index.indexName()))
                .findFirst()
                .flatMap(index -> index.keySchema().stream()
                        .filter(key -> key.keyType() == KeyType.RANGE)
                        .findFirst())
                .map(KeySchemaElement::attributeName)
                .orElse(null);
        if (Keys.GSI2SK.equals(sortKey)) {
            return Mono.empty();
        }
        return Mono.error(new IllegalStateException(
                "테이블 '%s' 의 인덱스 %s 가 이 버전과 다르다(정렬키 %s) — 테이블을 다시 만들어야 한다".formatted(
                        table, Keys.GSI2, sortKey == null ? "인덱스 없음" : sortKey)));
    }
```

5. import 를 정리한다.
   - `software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription` 과 `java.util.List` 를 더한다.
   - 쓰지 않게 된 `CreateGlobalSecondaryIndexAction`, `GlobalSecondaryIndexUpdate`, `UpdateTableRequest` 를 지운다.
6. `externalIdIndex()` 자바독에 `{@link #addMissingIndex}` 링크가 있으면 `{@link #requireCurrentDisplayNameIndex}` 로 바꾸거나 문장을 맞춘다. 컴파일 경고는 없지만 낡은 링크다.

`DynamoDbDirectorySearchRepository.searchUsersByDisplayName` 을 다음으로 바꾼다:

```java
    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        // GSI2 는 GSI1 과 같은 파티션키 속성(USER_INDEX)을 쓰고 정렬키만 소문자 표시명으로 바꾼 인덱스다(Keys.GSI2PK).
        // 정렬키가 소문자라 접두사도 소문자로 묻는다 — userName·조직명 검색과 같다(점검 S19).
        return query(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK, Keys.USER_INDEX,
                Keys.indexKey(prefix), cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }
```

`Cursor.java` 자바독(~20)의 `{@code {PK, SK, GSI1PK, displayName}}` 을 `{@code {PK, SK, GSI1PK, displayNameKey}}` 로 바꾼다.

`DirectorySearchRepository.searchUsersByDisplayName` 자바독:

```java
    /**
     * 직원 {@code displayName} 접두사 검색. 대소문자를 가리지 않는다 — {@link #searchUsersByUserName}·{@link #searchGroupsByDisplayName}
     * 과 같다(설계 2026-10-08 §3). 결과의 표시명은 저장한 그대로다.
     */
```

`FakeSearchRepository.searchUsersByDisplayName` — 자바독을 "직원 {@code displayName} 은 GSI2 의 소문자 키로 찾으므로 대소문자를 가리지 않는다(운영 코드와 맞춘다)." 로 바꾼다. 끝의 `page(indexed, UserSummary::displayName, prefix, cursor, limit, false)` 의 `false` 를 `true` 로 바꾼다. 빈 문자열 표시명 직원도 인덱스에서 빼도록 필터를 `u.displayName() != null && !u.displayName().isEmpty()` 로 바꾼다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS — 새 테스트(검색 4, 초기화 2)와 기존 storage 테스트 전부.

Run: `./gradlew :core:test`
Expected: PASS — 가짜를 바꿨다. 표시명 검색의 대소문자에 기대던 core 테스트가 있으면 이름과 함께 보고한다.

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Cursor.java core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableInitializerTest.java
git commit -F - <<'EOF'
feat: 직원 표시명 검색은 소문자 키(displayNameKey)로 — 대소문자를 가리지 않고, 조직은 GSI2 에 안 실리며, GSI2 가 옛 모양이면 기동을 멈춘다(점검 S19)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 2: S16 앞쪽 — 관리 API 검색 커서 위조는 400 (storage)

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (`query` ~152-176, 검사 도우미 더함)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java` (더함)

**Interfaces:**
- Consumes: Task 1 의 `Keys.GSI2SK`(GSI2 정렬키 속성 이름)와, `searchUsersByDisplayName` 이 `query` 에 소문자 접두사를 넘긴다는 것.
- Produces: 없음(저장소 안의 검사).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectorySearchRepositoryTest.java` 에 더한다. `Cursor.encode(String, Map)` 은 같은 패키지라 쓸 수 있다.

```java
    @Test
    @DisplayName("검색어를 바꾼 채 이전 커서를 다시 보내면 IllegalArgumentException 이다 — DynamoDB 오류(500)로 새지 않는다(점검 S16 앞쪽)")
    void 검색어를_바꾼_커서는_거절한다() {
        // given — "u" 검색이 발급한 진짜 커서
        for (int i = 1; i <= 3; i++) {
            state.saveUser(new DirectoryUser("u" + i, "e" + i, "u" + i, "가나다" + i, null, true)).block();
        }
        String 커서 = search.searchUsersByUserName("u", null, 1).block().nextCursor();
        assertThat(커서).isNotNull();

        // when, then — 같은 인덱스·파티션이라 범위 검사는 통과하지만 시작 키가 "v" 접두 밖이다
        assertThatThrownBy(() -> search.searchUsersByUserName("v", 커서, 1).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위는 맞아도 시작 키가 이 검색의 것이 아니면 IllegalArgumentException 이다 — 검색 셋 모두(점검 S16 앞쪽)")
    void 위조한_검색_시작_키는_거절한다() {
        // given — 검색마다 범위(인덱스/파티션)는 맞춘 위조 커서들
        record 검색(String 범위, String 파티션키, String 정렬키, String 파티션, String 종류_PK, java.util.function.Function<String, Page<?>> 묻기) {
        }
        List<검색> 검색들 = List.of(
                new 검색("GSI1/USER_INDEX", Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX, Keys.userPk("u1"),
                        c -> search.searchUsersByUserName("u", c, 1).block()),
                new 검색("GSI2/USER_INDEX", Keys.GSI2PK, Keys.GSI2SK, Keys.USER_INDEX, Keys.userPk("u1"),
                        c -> search.searchUsersByDisplayName("u", c, 1).block()),
                new 검색("GSI1/GROUP_INDEX", Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX, Keys.groupPk("G1"),
                        c -> search.searchGroupsByDisplayName("u", c, 1).block()));

        for (검색 s : 검색들) {
            Map<String, AttributeValue> 정상 = Map.of(
                    Keys.PK, Attrs.s(s.종류_PK()), Keys.SK, Attrs.s(Keys.META),
                    s.파티션키(), Attrs.s(s.파티션()), s.정렬키(), Attrs.s("u1"));
            List<Map<String, AttributeValue>> 위조들 = List.of(
                    바꾼다(정상, s.파티션키(), Attrs.s(Keys.USER_INDEX.equals(s.파티션()) ? Keys.GROUP_INDEX : Keys.USER_INDEX)), // 다른 파티션
                    뺀다(정상, Keys.SK),                                                                                  // 키가 모자람
                    바꾼다(정상, "extra", Attrs.s("x")),                                                                    // 키가 남음
                    바꾼다(정상, s.정렬키(), Attrs.s("x1")),                                                                  // 다른 접두사
                    바꾼다(정상, s.정렬키(), Attrs.s("u" + "x".repeat(1100))),                                                // 정렬키 한도 초과
                    바꾼다(정상, Keys.PK, Attrs.s(Keys.USER_INDEX.equals(s.파티션()) ? Keys.groupPk("G1") : Keys.userPk("u1"))), // 다른 종류의 PK
                    바꾼다(정상, Keys.SK, Attrs.s("MEMBER#USER#u1")));                                                       // META 가 아닌 SK

            // when, then
            for (Map<String, AttributeValue> 위조 : 위조들) {
                String 커서 = Cursor.encode(s.범위(), 위조);
                assertThatThrownBy(() -> s.묻기().apply(커서))
                        .as("%s / %s", s.범위(), 위조)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    private static Map<String, AttributeValue> 바꾼다(Map<String, AttributeValue> 원래, String 이름, AttributeValue 값) {
        Map<String, AttributeValue> 새것 = new HashMap<>(원래);
        새것.put(이름, 값);
        return 새것;
    }

    private static Map<String, AttributeValue> 뺀다(Map<String, AttributeValue> 원래, String 이름) {
        Map<String, AttributeValue> 새것 = new HashMap<>(원래);
        새것.remove(이름);
        return 새것;
    }
```

필요하면 import 를 더한다: `java.util.HashMap`, `dev.starryeye.organization.core.query.Page`, `software.amazon.awssdk.services.dynamodb.model.AttributeValue`.

`Cursor.encode(scope, 맵)` 은 패키지 전용이고, 빈 맵이면 null 을 준다. 위조 맵은 모두 비어 있지 않다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectorySearchRepositoryTest'`
Expected: 실패 — 위조 커서가 DynamoDB `ValidationException`(또는 `CompletionException` 으로 감싼 `DynamoDbException`)으로 나온다. `IllegalArgumentException` 이 아니다.

- [ ] **Step 3: 구현한다**

`query` 안의 `request.exclusiveStartKey(start);` 를 다음으로 바꾼다:

```java
                request.exclusiveStartKey(검색_시작_키를_확인한다(start, pkName, skName, partition, prefix));
```

`시작_키를_확인한다` 아래에 도우미를 더한다:

```java
    /**
     * 검색 커서에서 꺼낸 시작 키를 그대로 믿지 않는다(설계 2026-10-08 §4, 점검 S16 앞쪽). 범위(인덱스/파티션)만 맞춘 위조 커서나, 검색어를 바꾼 채
     * 다시 보낸 이전 커서는 DynamoDB 가 {@code ValidationException} 으로 거절해 500 이 된다. 이 검색의 시작 키가 아니면 400 으로 갈 예외다.
     *
     * <p>본다: 키 속성이 본 테이블 {@code PK}·{@code SK} 와 인덱스 키 둘로 정확히 넷, 인덱스 파티션키가 이 파티션, 인덱스 정렬키가 이번 접두사로
     * 시작하고 정렬키 한도(1024바이트) 안, {@code PK} 가 종류 접두({@code USER#}/{@code GROUP#})로 시작하고 {@code SK} 가 {@code META}.
     * 값이 문자열인 것은 {@link Cursor#decode} 가 이미 지켰다.
     */
    private static Map<String, AttributeValue> 검색_시작_키를_확인한다(Map<String, AttributeValue> start, String pkName, String skName,
                                                                String partition, String prefix) {
        String 종류_접두 = Keys.USER_INDEX.equals(partition) ? Keys.USER_PREFIX : Keys.GROUP_PREFIX;
        if (!start.keySet().equals(Set.of(Keys.PK, Keys.SK, pkName, skName))
                || !partition.equals(start.get(pkName).s())
                || !start.get(skName).s().startsWith(prefix)
                || start.get(skName).s().getBytes(StandardCharsets.UTF_8).length > MAX_SORT_KEY_BYTES
                || !start.get(Keys.PK).s().startsWith(종류_접두)
                || !Keys.META.equals(start.get(Keys.SK).s())) {
            throw new IllegalArgumentException("이 검색의 커서가 아니다");
        }
        return start;
    }
```

`query` 의 자바독 끝에 한 줄 더한다: "시작 키는 {@link #검색_시작_키를_확인한다} 로 검사한다."

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS — 새 테스트 둘. 기존 "커서로 이어 읽는다"·"다른 인덱스의 커서"·멤버 목록 커서 테스트도 그대로 통과한다.

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java
git commit -F - <<'EOF'
fix: 관리 API 검색 커서의 시작 키를 검사한다 — 위조·검색어를 바꾼 재사용은 500 이 아니라 400(점검 S16 앞쪽)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 3: S27 — 조직 상세의 이름표 읽기를 병렬로 (core)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java` (`expandParents` ~209, `childrenOf` ~324, 각 자바독 한 줄)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java` (`findGroupSummary` 에 지연·동시 수 계측)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java` (더함)

**Interfaces:**
- Produces(테스트 고정물): `FakeSearchRepository.summaryDelay`(`Duration`, 기본 `ZERO`), `FakeSearchRepository.summaryInFlightMax`(`AtomicInteger`) — 이름표 읽기가 동시에 몇 개였는지.

- [ ] **Step 1: 가짜에 계측을 더한다(테스트 고정물)**

`FakeSearchRepository` 에 필드 둘을 더하고 `findGroupSummary` 를 바꾼다. 필요하면 import 를 더한다: `java.time.Duration`, `java.util.concurrent.atomic.AtomicInteger`.

```java
    /** {@link #findGroupSummary} 한 번이 걸리는 시간. 병렬로 읽는지 가상 시간으로 재는 데 쓴다. */
    public Duration summaryDelay = Duration.ZERO;

    /** {@link #findGroupSummary} 가 동시에 몇 개까지 진행 중이었나. */
    public final AtomicInteger summaryInFlightMax = new AtomicInteger();

    private final AtomicInteger summaryInFlight = new AtomicInteger();

    @Override
    public Mono<GroupSummary> findGroupSummary(String orgCode) {
        return Mono.fromRunnable(() -> {
                    findGroupSummaryCalls.add(orgCode);
                    summaryInFlightMax.accumulateAndGet(summaryInFlight.incrementAndGet(), Math::max);
                })
                .then(summaryDelay.isZero() ? Mono.<Void>empty() : Mono.delay(summaryDelay).then())
                .then(Mono.defer(() -> Mono.justOrEmpty(lookupGroup(orgCode))))
                .doFinally(signal -> summaryInFlight.decrementAndGet());
    }
```

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`AdminQueryUseCaseTest.java` 에 더한다. 필요하면 import 를 더한다:
- `reactor.test.scheduler.VirtualTimeScheduler`
- `java.time.Duration`
- `java.util.concurrent.atomic.AtomicReference`
- `java.util.stream.IntStream`
- `dev.starryeye.organization.core.query.OrganizationDetail`(실제 패키지는 `AdminQueryUseCase` 의 import 에서 확인한다)

이 클래스에 `@AfterEach` 가 없으면 테스트 안에서 `try/finally` 로 `VirtualTimeScheduler.reset()` 을 부른다.

```java
    @Test
    @DisplayName("조직 상세는 하위 조직 이름표를 동시에 8개까지 읽고, 순서는 그대로다(점검 S27)")
    void 하위_조직_이름표를_병렬로_읽는다() {
        // given — 하위 조직 20개, 이름표 읽기마다 100ms
        MemberRef[] 하위 = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> MemberRef.group("C%02d".formatted(i))).toArray(MemberRef[]::new);
        for (MemberRef child : 하위) {
            state.saveGroup(조직(child.id())).block();
        }
        state.saveGroup(조직("P", 하위)).block();
        search.summaryDelay = Duration.ofMillis(100);
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        try {
            AtomicReference<OrganizationDetail> 결과 = new AtomicReference<>();

            // when — 8개씩 세 번이면 300ms 다. 하나씩이면 2초다
            useCase.organizationDetail("P", 20).subscribe(결과::set);
            시간.advanceTimeBy(Duration.ofMillis(300));

            // then
            assertThat(결과.get()).as("300ms 안에 끝난다").isNotNull();
            assertThat(결과.get().childOrganizations()).extracting("orgCode")
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).mapToObj("C%02d"::formatted).toList());
            assertThat(search.summaryInFlightMax).hasValue(8);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    @Test
    @DisplayName("조직 상세는 상위 조직 이름표도 동시에 읽고, 결과(순서·상한)는 하나씩 읽을 때와 같다(점검 S27)")
    void 상위_조직_이름표를_병렬로_읽는다() {
        // given — X 를 담은 상위 조직 10개
        state.saveGroup(조직("X")).block();
        for (int i = 1; i <= 10; i++) {
            state.saveGroup(조직("P%02d".formatted(i), MemberRef.group("X"))).block();
        }
        var 기대 = useCase.organizationDetail("X", 20).block().ancestors();
        search.summaryDelay = Duration.ofMillis(100);
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        try {
            AtomicReference<OrganizationDetail> 결과 = new AtomicReference<>();

            // when
            useCase.organizationDetail("X", 20).subscribe(결과::set);
            시간.advanceTimeBy(Duration.ofMillis(300));

            // then
            assertThat(결과.get()).isNotNull();
            assertThat(결과.get().ancestors()).isEqualTo(기대);
            assertThat(결과.get().ancestors()).hasSize(10);
            assertThat(search.summaryInFlightMax.get()).as("하나씩 읽지 않는다").isGreaterThan(1);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }
```

`조직(code, MemberRef...)` 도우미는 이 파일에 있다. `OrganizationDetail` 의 `ancestors()`·`childOrganizations()` 접근자 이름은 기존 테스트(`detail.ancestors()`, `detail.childOrganizations()`)와 같다.

- [ ] **Step 3: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest'`
Expected: 새 둘이 실패한다 — 300ms 에 끝나지 않는다(차례로 2초·1초), `summaryInFlightMax` 가 1 이다.

- [ ] **Step 4: 구현한다**

`AdminQueryUseCase.expandParents` 의 `.concatMap(this::loadGroupOrEmpty)` 를 다음으로 바꾼다:

```java
                // flatMapSequential 이라 병렬로 읽으면서도 순서를 지킨다 — acceptParent 는 결과를 하나씩 차례로 받는다(점검 S27)
                .flatMapSequential(this::loadGroupOrEmpty, LOAD_CONCURRENCY)
```

`childrenOf` 의 `.concatMap(this::loadGroupOrEmpty)` 도 같은 줄로 바꾼다. 주석은 "flatMapSequential 이라 병렬로 읽으면서도 하위 조직 순서를 지킨다(점검 S27)" 다.

- [ ] **Step 5: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS — 새 테스트 둘과 기존 core 테스트 전부. 특히 `findGroupSummaryCalls` 순서를 단언하는 기존 테스트가 있으면 그대로 통과해야 한다. 가짜는 구독 순서대로 기록하고, `flatMapSequential` 은 소스 순서대로 구독한다.

- [ ] **Step 6: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java
git commit -F - <<'EOF'
perf: 조직 상세의 상위·하위 조직 이름표를 순서를 지키며 병렬(동시 8)로 읽는다 — 하위 조직 200개면 GetItem 200번 차례 → 약 25번 왕복(점검 S27)

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```

---

### Task 4: admin-api 400 테스트와 문서 (admin-api, README, 점검 문서)

**Files:**
- Test: `admin-api/src/test/java/dev/starryeye/organization/admin/AdminQueryControllerTest.java` (더함)
- Modify: `README.md` (~128-134 키 바뀜 목록과 직접 만든 테이블 안내, ~329 검색 문단, ~284-289 커서 문단, ~732 락 절)
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md` (S16 ~1262, S19 ~1265, S27 ~1273 행)
- Modify: `docs/superpowers/specs/2026-10-08-audit-finish-design.md` (구현 중 정한 것 — 있을 때만)

- [ ] **Step 1: admin-api 테스트를 더한다**

`AdminQueryControllerTest` 에 더한다. 같은 파일의 `저장소의_IllegalArgumentException_은_400이_된다`(~127) 와 같은 모양으로, 조직 검색 쪽을 덮는다.

```java
    @Test
    @DisplayName("조직 검색 커서가 이 검색의 것이 아니면 400 이다(점검 S16 앞쪽)")
    void 조직_검색_커서_위조는_400이다() {
        // given — 저장소가 검색 커서를 거절한 상황
        search.failWith = new IllegalArgumentException("이 검색의 커서가 아니다");

        // when, then
        client.get().uri("/admin/organizations?displayName=개발&cursor=forged")
                .exchange().expectStatus().isBadRequest();
    }
```

Run: `./gradlew :admin-api:test`
Expected: PASS(매핑은 이미 있다 — 이 테스트는 조직 검색 경로에서도 400 으로 옮겨지는 것을 고정한다).

- [ ] **Step 2: README 를 고친다**

1. 키 바뀜 목록(~128 "**S-1(SCIM 목록·필터)도 키를 바꾼다**" 문단) 다음에 한 문단을 더한다:

```markdown
**⑥-3(관리자 표시명 검색)도 키를 바꾼다** — GSI2 의 정렬키가 직원 META 에만 쓰는 소문자 표시명(`displayNameKey`)이 되고, 조직은 GSI2 에
실리지 않는다(`2026-10-08-audit-finish-design.md`). 기존 테이블은 다시 만들어야 한다. `create-table-on-startup` 이 켜져 있으면 GSI2 가
옛 모양(정렬키 `displayName`)이거나 없는 테이블에서 서버가 "테이블을 다시 만들어야 한다" 며 기동을 멈춘다.
```

2. "직접 만든 AWS 테이블이라면" 문장의 GSI2 를 다음으로 고친다: `GSI2(파티션키 `GSI1PK`, 정렬키 `displayNameKey`, 프로젝션 `INCLUDE` — `userName`·`displayName`·`active`)`.
3. "**검색은 접두사만 지원한다.**" 문단 끝에 한 문장을 더한다: "세 검색(`userName`·직원 `displayName`·조직 `displayName`) 모두 대소문자를 가리지 않는다 — `displayName=KIM` 이 `Kim Chulsoo` 를 찾는다. 결과의 값은 저장한 그대로다."
4. "**조직 멤버 목록의 커서.**" 문단 끝에 한 문장을 더한다: "직원·조직 검색의 `cursor` 도 같다 — 다른 검색이 발급했거나, 검색어를 바꾼 채 이전 커서를 다시 보냈거나, 이 검색의 키가 아닌 값으로 고친 커서는 400 이다."
5. 락 절(~732)의 "(아래 SCIM 절)" 을 "(위 SCIM 절)" 로 고친다. 이 문장 하나만 고친다. README 의 다른 "아래 SCIM 절"(~325·~498)은 옳은 방향이니 그대로 둔다.

- [ ] **Step 3: 점검 문서를 표시한다**

`docs/superpowers/specs/2026-09-28-full-audit.md` 의 사소 표에서 행의 마지막 칸 안(`|` 앞)에 붙인다. ⑥-1·⑥-2 의 관례와 같다.
- S16 행: 지금 있는 "**→ 뒤쪽(멤버 목록 오프셋) 해결·앞쪽(위조 커서)은 멤버 목록에서만 해결(2026-10-06, 슬라이드 ⑥-1)**" 뒤에 " **→ 앞쪽 나머지(검색 커서)도 해결(2026-10-08, 슬라이드 ⑥-3)**" 을 붙인다.
- S19 행: " **→ 해결(2026-10-08, 슬라이드 ⑥-3)**" 를 붙인다.
- S27 행: " **→ 해결 — 아카이빙 BatchCheck 는 ③-1(2026-10-03, `openfga.request-concurrency`), 조직 상세 이름표는 ⑥-3(2026-10-08)**" 을 붙인다.

- [ ] **Step 4: 스펙에 구현 중 정한 것을 적는다**

앞 과제 보고서에 계획과 달리 정한 것이 있으면, `2026-10-08-audit-finish-design.md` 의 해당 절 끝에 "구현 중 정한 것:" 한 줄로 적는다. 한 구절의 까닭을 함께 쓴다. 예를 들면 빈 문자열 표시명의 처리, 가짜의 빈 표시명 제외다. 없으면 건너뛴다. §10 은 건드리지 않는다.

Run: `./gradlew :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL(문서만 바뀌었지만 같은 커밋의 테스트가 컴파일되는지 본다 — admin-api 는 앱 모듈이 포함한다).

- [ ] **Step 5: 커밋한다**

```bash
git add admin-api/src/test/java/dev/starryeye/organization/admin/AdminQueryControllerTest.java README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-08-audit-finish-design.md
git commit -F - <<'EOF'
docs: ⑥-3 README — 표시명 검색 대소문자 무시·GSI2 키 바뀜(테이블 재생성, 옛 모양이면 기동 중단)·검색 커서 400, 락 절 "위 SCIM 절"; 점검 S16·S19·S27 해결 표시

Co-Authored-By: <자기 하네스의 값>
EOF
git push
```
