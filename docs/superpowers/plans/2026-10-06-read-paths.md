# 점검 ⑥-1 읽기 길 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 요청 하나가 10만 명 조직을 통째로 읽거나 힙에 올리지 않게 한다.
- 관리 API 멤버 목록·조직 상세는 키 커서로 한 쪽씩만 읽는다(P4·S16).
- 멤버를 싣는 SCIM 조직 응답은 멤버를 DynamoDB 한 쪽씩 흘려 쓴다(P5).

**Architecture:**
- **관리 API.** 검색 포트(`DirectorySearchRepository`)에 메서드 둘을 더한다.
  - "직원 멤버 아이디 한 쪽" 은 `MEMBER#USER#` 접두 Query 에 `Limit` 과 시작 키를 주고, 기존 `Cursor` 로 감싼다.
  - "하위 조직 아이디" 는 `MEMBER#GROUP#` 접두로 읽는다.
  - `AdminQueryUseCase` 는 `findGroup` 대신 이 둘과 조직 헤더 GetItem 을 쓴다.
- **SCIM.** connector-scim 에 `ScimGroupStream` 을 둔다.
  - 앞부분(이름표·투영)은 트리로 만들고 `members` 배열만 기존 `findMemberRefs` 로 이어 읽어 `Flux<DataBuffer>` 로 흘린다.
  - 핸들러와 목록이 멤버를 실을 때 그것을 쓴다.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux, Reactor, Jackson 2, AWS SDK v2 DynamoDB, JUnit 5, AssertJ, StepVerifier, WebTestClient, Testcontainers(DynamoDB Local·OpenFGA).

**Spec:** `docs/superpowers/specs/2026-10-06-read-paths-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다. 합친 `// when, then` 도 된다. 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.** 이름·주석·메시지는 한국어로, 주변처럼 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다. 클래스 이름은 영어다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.** 한 번에 하나씩 포그라운드로 돌린다. 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML 에서 옮긴다. 어림하지 않는다.
- **요청당 전원 읽기 금지.**
  - 관리 API 의 조직 상세·멤버 목록은 조직 파티션 전체를 읽지 않는다(`findGroup` 을 부르지 않는다).
  - SCIM 응답은 멤버 전체를 읽되 메모리에 모으지 않는다. 멤버를 `collectList`·`findGroup` 으로 모으면 안 된다.
- **커서는 표준 신호를 쓴다.** 다음 쪽 커서는 DynamoDB `LastEvaluatedKey` 가 있을 때만 준다.
  - 커서의 검색 범위는 `group-members/<orgCode>` 다.
  - 시작 키는 커서를 그대로 믿지 않는다. 속성이 `PK`·`SK` 둘뿐이고, `PK` 가 이 조직이고, `SK` 가 `MEMBER#USER#` 로 시작해야 한다. 아니면 `IllegalArgumentException` 이다(관리 API 가 400 으로 바꾼다).
- **응답 모양.** SCIM 조직 JSON 의 값은 지금과 같다. 달라지는 것은 필드 순서(`members` 가 맨 뒤, 목록의 `itemsPerPage` 가 맨 뒤)와 `Content-Length` 가 없다는 것뿐이다.
- **멤버 순서.** 정렬키 순이다 — 하위 조직이 먼저, 그다음 직원, 각각 아이디 순.

## Review Focus

1. **멤버가 0명인 조직.** `"members":[]` 가 나온다(지금과 같다) — Task 3 테스트.
2. **하위 속성 투영.** `attributes=members.value` 면 멤버마다 `value` 만, `excludedAttributes=members.type` 이면 멤버마다 `type` 만 빠진다 — Task 3 테스트.
3. **`excludedAttributes=members`.** 지금처럼 이름표만 읽고 `findMemberRefs` 를 부르지 않는다 — Task 4 테스트.
4. **목록 도중 지워진 조직.** 건너뛰고 `itemsPerPage` 가 실제로 쓴 수다 — Task 3 테스트.
5. **관리 API 커서 오용.** 다른 조직의 커서와 위조한 시작 키는 400 이다(저장소 `IllegalArgumentException`) — Task 1 테스트, admin-api 매핑은 기존.

---

### Task 1: 검색 포트 — 직원 멤버 한 쪽·하위 조직 아이디 (core 포트 + storage + 가짜)

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java` (메서드 둘)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (구현 둘)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java` (가짜 구현 둘)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java` (더함)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/PaginatorLazinessTest.java` (새)

**Interfaces:**
- Produces:
  - `Mono<Page<String>> findGroupUserMemberIds(String orgCode, String cursor, int limit)`
    - 직원 멤버 아이디(정렬 순)와 다음 쪽 커서(없으면 null)를 돌려준다.
    - 커서가 잘못되면 구독할 때 `IllegalArgumentException` 이다.
  - `Flux<String> findChildOrgCodes(String orgCode)` — 하위 조직 아이디(정렬 순). 호출자가 `take` 로 자를 수 있게 쪽 단위로 이어 읽는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectorySearchRepositoryTest` 에 더한다. 기존 `state`·`search` 필드와 `저장소를_준비한다()` 를 쓴다. import 는 `MemberRef`·`DirectoryGroup`·`DirectoryUser`·`Set`·`LinkedHashSet` 이다.

```java
    private void 조직을_심는다(String orgCode, int 직원수, String... 하위조직) {
        Set<MemberRef> members = new LinkedHashSet<>();
        for (int i = 0; i < 직원수; i++) {
            members.add(MemberRef.user("u%03d".formatted(i)));
        }
        for (String child : 하위조직) {
            members.add(MemberRef.group(child));
        }
        state.saveGroup(new DirectoryGroup(orgCode, "ext-" + orgCode, orgCode + "-조직", members)).block();
    }

    @Test
    @DisplayName("직원 멤버를 아이디 순으로 한 쪽씩 읽고, 커서로 이어 읽으면 중복도 누락도 없다 — 하위 조직·META 는 섞이지 않는다(설계 2026-10-06 §3.1)")
    void 직원_멤버를_한_쪽씩_읽는다() {
        // given — 직원 5명, 하위 조직 둘
        조직을_심는다("DEV", 5, "SUB1", "SUB2");

        // when — 2명씩 끝까지 읽는다
        List<String> 읽은것 = new ArrayList<>();
        int 쪽수 = 0;
        String cursor = null;
        do {
            var page = search.findGroupUserMemberIds("DEV", cursor, 2).block();
            읽은것.addAll(page.items());
            cursor = page.nextCursor();
            쪽수++;
        } while (cursor != null && 쪽수 < 10);

        // then
        assertThat(읽은것).containsExactly("u000", "u001", "u002", "u003", "u004");
        assertThat(쪽수).isGreaterThan(1);
    }

    @Test
    @DisplayName("직원 멤버가 없으면 빈 쪽이고 커서도 없다")
    void 직원_멤버가_없으면_빈_쪽이다() {
        // given
        조직을_심는다("EMPTY", 0, "SUB1");

        // when
        var page = search.findGroupUserMemberIds("EMPTY", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다른 조직의 멤버 커서는 IllegalArgumentException 이다 — 다른 파티션을 엉뚱하게 이어 읽지 않는다")
    void 다른_조직의_커서는_거절한다() {
        // given
        조직을_심는다("A", 3);
        조직을_심는다("B", 3);
        String A의_커서 = search.findGroupUserMemberIds("A", null, 1).block().nextCursor();

        // when, then
        assertThatThrownBy(() -> search.findGroupUserMemberIds("B", A의_커서, 1).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위는 맞아도 시작 키가 이 조직의 직원 멤버가 아니면 IllegalArgumentException 이다 — DynamoDB 오류(500)로 새지 않는다(점검 S16 앞쪽)")
    void 위조한_시작_키는_거절한다() {
        // given — 같은 범위로 위조한 커서 셋: 다른 PK, 하위 조직 SK, 속성이 하나 더 있음
        조직을_심는다("DEV", 3);
        String 범위 = "group-members/DEV";
        String 다른_PK = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("OTHER")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.user("u000")))));
        String 조직_SK = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("DEV")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.group("SUB1")))));
        String 남는_속성 = Cursor.encode(범위, Map.of(Keys.PK, Attrs.s(Keys.groupPk("DEV")),
                Keys.SK, Attrs.s(Keys.memberSk(MemberRef.user("u000"))), "extra", Attrs.s("x")));

        // when, then
        for (String 위조 : List.of(다른_PK, 조직_SK, 남는_속성)) {
            assertThatThrownBy(() -> search.findGroupUserMemberIds("DEV", 위조, 1).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("깨진 멤버 커서는 Mono 를 만들 때는 던지지 않고, 구독할 때 IllegalArgumentException 으로 나온다")
    void 깨진_멤버_커서는_구독할_때_실패한다() {
        // when
        var mono = search.findGroupUserMemberIds("DEV", "!!not-base64!!", 1);

        // then
        assertThatThrownBy(mono::block).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("하위 조직 아이디만 정렬 순으로 읽는다 — 직원 멤버는 섞이지 않는다")
    void 하위_조직_아이디만_읽는다() {
        // given
        조직을_심는다("DEV", 3, "SUB2", "SUB1");

        // when
        var 하위 = search.findChildOrgCodes("DEV").collectList().block();

        // then
        assertThat(하위).containsExactly("SUB1", "SUB2");
    }
```

`PaginatorLazinessTest`(새). `DynamoDbTestSupport` 를 상속하고, `Query` 를 세는 프록시를 둔다. 같은 패키지의 `GetCounter` 와 같은 모양이다.

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 쪽을 이어 읽는 {@link Paginator} 가 아래에서 끌어당긴 만큼만 다음 쪽을 읽는다 — 멤버를 흘려 쓰는 SCIM 응답의 바탕이다(설계 2026-10-06 §4.2). */
class PaginatorLazinessTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("앞의 몇 줄만 받고 멈추면 다음 쪽 Query 를 보내지 않는다")
    void 받은_만큼만_읽는다() {
        // given — 멤버 50명 조직, 쪽 크기 10
        var state = new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC());
        Set<MemberRef> members = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            members.add(MemberRef.user("u%03d".formatted(i)));
        }
        state.saveGroup(new DirectoryGroup("BIG", null, "큰 조직", members)).block();
        AtomicLong 쿼리 = new AtomicLong();
        DynamoDbAsyncClient 세는_클라이언트 = (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("query")) {
                        쿼리.incrementAndGet();
                    }
                    try {
                        return method.invoke(client, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.groupPk("BIG")), ":prefix", Attrs.s(Keys.MEMBER_PREFIX)))
                .limit(10)
                .build();

        // when
        var 받은것 = Paginator.queryAll(세는_클라이언트, request).take(5).collectList().block();

        // then
        assertThat(받은것).hasSize(5);
        assertThat(쿼리.get()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectorySearchRepositoryTest' --tests '*PaginatorLazinessTest'`
Expected: 컴파일 실패(새 메서드 없음). `PaginatorLazinessTest` 는 컴파일되면 처음부터 통과할 수 있다(지금 동작을 고정한다). 통과 여부를 보고서에 적는다. 실패하면 멈추고 NEEDS_CONTEXT 로 보고한다 — §4 의 바탕이 무너진다.

- [ ] **Step 3: 포트**

`DirectorySearchRepository` 끝에 더한다:

```java
    /**
     * 조직의 <b>직원 멤버</b> 아이디 한 쪽(아이디 순). {@code cursor} 가 null 이면 첫 쪽이고, 다음 쪽이 없으면 {@code nextCursor} 가 null 이다.
     * 쪽이 정확히 끝나면 빈 마지막 쪽이 한 번 올 수 있다(저장소의 표준 신호를 그대로 쓴다). 잘못된 커서는 구독할 때 {@link IllegalArgumentException} 이다.
     *
     * <p>조직 파티션 전체를 읽지 않는다 — 10만 명 조직이어도 쪽 크기만큼만 읽는다(설계 2026-10-06 §3.1, 점검 P4).
     */
    Mono<Page<String>> findGroupUserMemberIds(String orgCode, String cursor, int limit);

    /** 조직의 직속 하위 조직 아이디(아이디 순). 쪽 단위로 이어 읽으므로 호출자가 {@code take} 로 자르면 거기서 멈춘다(설계 2026-10-06 §3.2). */
    Flux<String> findChildOrgCodes(String orgCode);
```

(import `reactor.core.publisher.Flux`.)

- [ ] **Step 4: 저장소 구현**

`DynamoDbDirectorySearchRepository` 에 더한다. import 는 `MemberType`·`Flux`·`Set`·`Paginator` 이고, `Paginator` 는 같은 패키지다.

```java
    /** 멤버 목록 커서의 검색 범위 — 조직마다 다르다. 다른 조직의 커서는 {@link Cursor#decode} 가 거절한다. */
    private static String memberScope(String orgCode) {
        return "group-members/" + orgCode;
    }

    @Override
    public Mono<Page<String>> findGroupUserMemberIds(String orgCode, String cursor, int limit) {
        String pk = Keys.groupPk(orgCode);
        String prefix = Keys.memberSkPrefix(MemberType.USER);
        String scope = memberScope(orgCode);
        return Mono.defer(() -> {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(properties.getTableName())
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                    .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                    .expressionAttributeValues(Map.of(":pk", Attrs.s(pk), ":prefix", Attrs.s(prefix)))
                    .projectionExpression("#pk, #sk")
                    .consistentRead(true)
                    .limit(limit);
            Map<String, AttributeValue> start = Cursor.decode(scope, cursor);
            if (start != null) {
                request.exclusiveStartKey(시작_키를_확인한다(start, pk, prefix));
            }
            return Mono.fromFuture(() -> client.query(request.build()))
                    .map(response -> new Page<>(
                            response.items().stream()
                                    .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)).id())
                                    .toList(),
                            Cursor.encode(scope, response.lastEvaluatedKey())));
        });
    }

    /**
     * 커서에서 꺼낸 시작 키를 그대로 믿지 않는다 — 범위만 맞춘 위조 커서가 다른 파티션이나 다른 접두를 가리키면 DynamoDB 가
     * {@code ValidationException} 을 내 500 이 된다(점검 S16 앞쪽). 이 조직의 직원 멤버 키가 아니면 400 으로 갈 예외다.
     */
    private static Map<String, AttributeValue> 시작_키를_확인한다(Map<String, AttributeValue> start, String pk, String prefix) {
        String startPk = start.get(Keys.PK) == null ? null : start.get(Keys.PK).s();
        String startSk = start.get(Keys.SK) == null ? null : start.get(Keys.SK).s();
        if (!start.keySet().equals(Set.of(Keys.PK, Keys.SK)) || !pk.equals(startPk)
                || startSk == null || !startSk.startsWith(prefix)) {
            throw new IllegalArgumentException("이 조직의 멤버 목록 커서가 아니다");
        }
        return start;
    }

    @Override
    public Flux<String> findChildOrgCodes(String orgCode) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.groupPk(orgCode)),
                        ":prefix", Attrs.s(Keys.memberSkPrefix(MemberType.GROUP))))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request)
                .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)).id());
    }
```

`Keys.memberSkPrefix`·`Keys.parseMemberSk`·`Keys.groupPk`·`Keys.PK`·`Keys.SK` 는 이미 있다. 접근 범위가 패키지 전용이면 그대로 쓴다(같은 패키지다).

- [ ] **Step 5: 가짜 구현**

`FakeSearchRepository` 에 더한다. 커서는 기존 `page(...)` 와 같은 "다음 인덱스" 문자열이다. `state` 가 없으면 빈 결과다. import 는 `MemberType`·`MemberRef`·`Flux` 다.

```java
    /** 상태 저장소의 조직 멤버 중 직원만 아이디 순으로 자른다. 커서는 다음 인덱스다(불투명하다는 계약만 지킨다). */
    @Override
    public Mono<Page<String>> findGroupUserMemberIds(String orgCode, String cursor, int limit) {
        if (failWith != null) return Mono.error(failWith);
        return Mono.fromCallable(() -> {
            List<String> ids = memberIds(orgCode, MemberType.USER);
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(from + limit, ids.size());
            return new Page<>(ids.subList(from, to), to < ids.size() ? String.valueOf(to) : null);
        });
    }

    @Override
    public Flux<String> findChildOrgCodes(String orgCode) {
        return Flux.defer(() -> Flux.fromIterable(memberIds(orgCode, MemberType.GROUP)));
    }

    private List<String> memberIds(String orgCode, MemberType type) {
        if (state == null || state.groups.get(orgCode) == null) {
            return List.of();
        }
        return state.groups.get(orgCode).members().stream()
                .filter(member -> member.type() == type)
                .map(MemberRef::id)
                .sorted()
                .toList();
    }
```

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test`, 그다음 `./gradlew :core:compileTestFixturesJava :core:test`
Expected: PASS. 포트에 메서드가 늘어 다른 구현체가 컴파일되지 않으면 그 구현체에도 더한다. 예를 들어 app 테스트의 익명 구현이 있을 수 있다. 그런 곳은 보고서에 적는다.

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/PaginatorLazinessTest.java
git commit -F - <<'EOF'
feat: 검색 포트에 직원 멤버 한 쪽(키 커서, 시작 키 검사)·하위 조직 아이디 — 조직 파티션을 통째로 읽지 않는다(점검 P4·S16)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 2: core — 관리 조회가 조직 파티션 전체를 읽지 않는다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java`
  - `organizationDetail` 과 `organizationMembers` 를 바꾼다.
  - `ancestorsOf` 와 `childrenOf` 를 바꾼다.
  - `membersPage` 를 바꾼다.
  - `parseCursor` 를 지운다.
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java`

**Interfaces:**
- Consumes: Task 1 의 `findGroupUserMemberIds`, `findChildOrgCodes`.
- 공개 시그니처(`organizationDetail(String, int)`, `organizationMembers(String, String, int)`)는 바뀌지 않는다.

- [ ] **Step 1: 테스트를 고친다·더한다**

1. `순회는_멤버_목록을_읽지_않는다`(~260): `assertThat(state.findGroupCalls).containsExactly("DEV002")` 를 `isEmpty()` 로 바꾼다. 주석은 "조회 대상 조직 자신도 파티션 전체를 읽지 않는다 — 헤더·하위 조직·멤버 한 쪽만(설계 2026-10-06 §3.2)" 이다.
2. `상한을_넘는_커서는_빈_페이지다`(~490)와 `파싱할_수_없는_커서는_예외다`(~505)를 지운다. 커서 해석은 이제 검색 포트 몫이다(Task 1 의 저장소 테스트가 덮는다). 대신 다음을 더한다:

```java
    @Test
    @DisplayName("멤버 목록은 조직 파티션 전체를 읽지 않고 검색 포트의 한 쪽과 그 커서를 그대로 쓴다(설계 2026-10-06 §3.1)")
    void 멤버_목록은_한_쪽만_읽는다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveUser(직원("park", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.user("park"))).block();
        state.findGroupCalls.clear();

        // when
        var 첫_쪽 = useCase.organizationMembers("DEV002", null, 2).block();
        var 둘째_쪽 = useCase.organizationMembers("DEV002", 첫_쪽.nextCursor(), 2).block();

        // then
        assertThat(첫_쪽.items()).extracting("employeeId").containsExactly("kim", "lee");
        assertThat(둘째_쪽.items()).extracting("employeeId").containsExactly("park");
        assertThat(둘째_쪽.nextCursor()).isNull();
        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("검색 포트가 커서를 거절하면(IllegalArgumentException) 그대로 흘려 관리 API 가 400 으로 바꾼다")
    void 거절된_커서는_그대로_흐른다() {
        // given
        state.saveGroup(조직("DEV002")).block();
        search.failWith = new IllegalArgumentException("이 조직의 멤버 목록 커서가 아니다");

        // when, then
        assertThatThrownBy(() -> useCase.organizationMembers("DEV002", "x", 20).block())
                .isInstanceOf(IllegalArgumentException.class);
    }
```

나머지 조직 상세·멤버 목록 테스트는 그대로 통과해야 한다. 예를 들면 하위 조직 상한, 없는 조직은 빈 Mono, 지워진 직원은 건너뜀, Check 순서다.

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest'`
Expected: `순회는_멤버_목록을_읽지_않는다`·`멤버_목록은_한_쪽만_읽는다` 가 `findGroupCalls` 단정에서 실패한다.

- [ ] **Step 3: 구현**

`organizationDetail`·`organizationMembers`:

```java
    public Mono<OrganizationDetail> organizationDetail(String orgCode, int memberPageSize) {
        // 조직 파티션 전체(findGroup)를 읽지 않는다 — 헤더·하위 조직·멤버 첫 쪽만(설계 2026-10-06 §3.2, 점검 P4)
        return state.findGroupHeader(orgCode).flatMap(header ->
                Mono.zip(ancestorsOf(header), childrenOf(header.id()),
                                membersPage(header.id(), null, memberPageSize))
                        .map(parts -> new OrganizationDetail(
                                header.id(), header.displayName(), header.externalId(),
                                parts.getT1(), parts.getT2(), parts.getT3())));
    }

    public Mono<Page<OrgMember>> organizationMembers(String orgCode, String cursor, int limit) {
        return state.findGroupHeader(orgCode).flatMap(header -> membersPage(header.id(), cursor, limit));
    }
```

`ancestorsOf(DirectoryGroup group)` 의 매개변수를 `GroupHeader header` 로 바꾼다. 몸통의 `group.id()`·`group.displayName()` 을 `header.id()`·`header.displayName()` 으로 바꾸고, 나머지는 그대로다.

`childrenOf`:

```java
    private Mono<List<GroupSummary>> childrenOf(String orgCode) {
        return search.findChildOrgCodes(orgCode)
                .take(MAX_PATHS + 1)
                .collectList()
                .flatMap(childIds -> {
                    if (childIds.size() > MAX_PATHS) {
                        log.warn("조직 '{}' 의 직속 하위 조직이 상한({})을 넘어 잘렸습니다", orgCode, MAX_PATHS);
                        childIds = childIds.subList(0, MAX_PATHS);
                    }
                    return Flux.fromIterable(childIds)
                            .concatMap(this::loadGroupOrEmpty)
                            .collectList();
                });
    }
```

(자바독의 "멤버 참조에는 조직코드밖에 없으므로…" 문단은 둔다. "하위 조직 아이디는 `MEMBER#GROUP#` 줄만 상한까지 읽는다" 한 줄을 더한다.)

`membersPage`:

```java
    private Mono<Page<OrgMember>> membersPage(String orgCode, String cursor, int limit) {
        // 쪽 하나만 읽는다 — 커서는 검색 포트가 발급·검사한다(설계 2026-10-06 §3.1, 점검 P4·S16)
        return search.findGroupUserMemberIds(orgCode, cursor, limit).flatMap(page ->
                // 설계 §8.2 의 동시성: 병렬로 내고 결과는 쪽 순서대로(flatMapSequential)
                Flux.fromIterable(page.items())
                        .flatMapSequential(userId -> loadUserOrEmpty(userId)
                                .flatMap(user -> checkOrNull(RelationTuple.member(user.id(), orgCode))
                                        .map(allowed -> new OrgMember(user.id(), user.displayName(),
                                                user.active(), allowed))
                                        .defaultIfEmpty(new OrgMember(user.id(), user.displayName(),
                                                user.active(), null))), CHECK_CONCURRENCY)
                        .collectList()
                        .map(items -> new Page<>(items, page.nextCursor())));
    }
```

- `parseCursor` 와 그 자바독을 지운다.
- 쓰지 않게 된 import(`DirectoryGroup`, `MemberType` 등)는 지운다. 다른 곳에서 쓰면 둔다.

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :core:test`, 그다음 `./gradlew :admin-api:test`
Expected: PASS. admin-api 테스트가 오프셋 커서("2" 같은 값)를 단정하면 새 계약(불투명 커서)으로 고치고 보고서에 적는다.

- [ ] **Step 5: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java
git commit -F - <<'EOF'
feat: 관리 API 조직 상세·멤버 목록은 조직 파티션 전체를 읽지 않는다 — 헤더·하위 조직·멤버 한 쪽만, 오프셋 커서 없앰(점검 P4·S16)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

(admin-api 테스트를 고쳤으면 그 경로도 `git add` 한다.)

---

### Task 3: connector-scim — `ScimGroupStream` (조직 응답 흘려 쓰기 부품)

**Files:**
- Create: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupStream.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimJson.java` (`string`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimAttributeProjection.java` (`applyToElement`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java` (`toScimMember` 를 꺼내 두 곳이 같이 쓴다)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupStreamTest.java` (새)

**Interfaces:**
- Produces:
  - `final class ScimGroupStream`(패키지 전용), `ScimGroupStream(DirectoryStateRepository state)`.
  - `Flux<DataBuffer> group(GroupHeader header, ScimAttributeProjection projection)` — 조직 하나. 헤더는 호출자가 이미 읽었다.
  - `Flux<DataBuffer> list(ScimQuery query, long totalResults, List<GroupHeader> headers)` — 목록. 헤더마다 GetItem 으로 다시 확인하고, 지워진 조직은 건너뛴다. `itemsPerPage` 는 맨 끝이다.
  - `static final int 멤버_묶음 = 1_000`.
  - `ScimAttributeProjection.applyToElement(String attribute, ObjectNode element)` — 복합 속성 원소 하나에 하위 속성 투영을 건다.
  - `ScimJson.string(JsonNode)` — 직렬화.
  - `ScimMapper.toScimMember(MemberRef)` — public static.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String 모은다(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body).map(buffer -> {
            String text = buffer.toString(StandardCharsets.UTF_8);
            DataBufferUtils.release(buffer);
            return text;
        }).block();
    }

    /** 트리 방식(지금까지의 응답)과 흘려 쓴 응답을 값으로 견준다 — 필드 순서와 멤버 순서는 보지 않는다. */
    private static void 같은_값이다(String 흘려쓴, JsonNode 트리) throws Exception {
        JsonNode 받은 = JSON.readTree(흘려쓴);
        assertThat(멤버_없이(받은)).isEqualTo(멤버_없이(트리));
        assertThat(멤버들(받은)).containsExactlyInAnyOrderElementsOf(멤버들(트리));
    }

    private static JsonNode 멤버_없이(JsonNode node) {
        var copy = node.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("members");
        return copy;
    }

    private static List<JsonNode> 멤버들(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        if (node.has("members")) node.get("members").forEach(list::add);
        return list;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "- | -",
            "members | -",
            "members.value | -",
            "displayName,members | -",
            "- | members.type",
            "- | externalId"
    })
    @DisplayName("흘려 쓴 조직 JSON 은 트리 방식과 같은 값이다 — 투영(attributes·excludedAttributes·members 하위 속성)까지(설계 2026-10-06 §4.2)")
    void 트리_방식과_같은_값이다(String attributes, String excluded) throws Exception {
        // given
        var state = new FakeStateRepository();
        var group = new DirectoryGroup("DEV", "ext-DEV", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("SUB1")));
        state.saveGroup(group).block();
        var projection = ScimAttributeProjection.of(ScimResourceType.GROUP,
                ScimAttributeProjection.split(attributes), ScimAttributeProjection.split(excluded));

        // when
        String 흘려쓴 = 모은다(new ScimGroupStream(state).group(new GroupHeader("DEV", "ext-DEV", "개발본부"), projection));

        // then
        같은_값이다(흘려쓴, projection.apply(ScimJson.tree(ScimMapper.toScimGroup(group))));
    }

    @Test
    @DisplayName("멤버가 0명인 조직은 \"members\":[] 다 — 지금과 같다")
    void 멤버가_없으면_빈_배열이다() throws Exception {
        // given
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("EMPTY", null, "빈 조직", Set.of())).block();

        // when
        String 흘려쓴 = 모은다(new ScimGroupStream(state).group(new GroupHeader("EMPTY", null, "빈 조직"),
                ScimAttributeProjection.all()));

        // then
        assertThat(JSON.readTree(흘려쓴).get("members").isArray()).isTrue();
        assertThat(JSON.readTree(흘려쓴).get("members")).isEmpty();
    }

    @Test
    @DisplayName("앞의 묶음만 받고 멈추면 멤버를 더 읽지 않는다 — 10만 명 조직도 통째로 꺼내지 않는다")
    void 받은_만큼만_멤버를_꺼낸다() {
        // given — 멤버 10만 명을 하나씩 꺼낼 때마다 센다
        AtomicInteger 꺼낸_수 = new AtomicInteger();
        var state = new FakeStateRepository() {
            @Override
            public Flux<MemberRef> findMemberRefs(String groupId) {
                return Flux.range(0, 100_000)
                        .doOnNext(i -> 꺼낸_수.incrementAndGet())
                        .map(i -> MemberRef.user("u%06d".formatted(i)));
            }
        };

        // when — 앞부분과 첫 묶음만 받는다
        new ScimGroupStream(state).group(new GroupHeader("ALL", null, "전 직원"), ScimAttributeProjection.all())
                .take(2)
                .doOnNext(DataBufferUtils::release)
                .blockLast();

        // then
        assertThat(꺼낸_수.get()).isLessThan(10 * ScimGroupStream.멤버_묶음);
    }

    @Test
    @DisplayName("멤버 도중 저장소가 실패하면 닫는 ]} 를 쓰지 않고 오류로 끝난다 — 완결된 JSON 이 나가지 않는다")
    void 도중_오류면_닫지_않는다() {
        // given
        var state = new FakeStateRepository() {
            @Override
            public Flux<MemberRef> findMemberRefs(String groupId) {
                return Flux.range(0, 2_500).map(i -> MemberRef.user("u%06d".formatted(i)))
                        .concatWith(Flux.error(new IllegalStateException("저장소 실패")));
            }
        };

        // when, then
        StepVerifier.create(new ScimGroupStream(state).group(new GroupHeader("ALL", null, "전 직원"),
                        ScimAttributeProjection.all()))
                .thenConsumeWhile(buffer -> {
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return !text.endsWith("]}");
                })
                .verifyError(IllegalStateException.class);
    }

    @Test
    @DisplayName("목록은 조직을 차례로 흘려 쓰고, 그사이 지워진 조직은 건너뛰며 itemsPerPage 는 실제로 쓴 수다")
    void 목록은_지워진_조직을_건너뛴다() throws Exception {
        // given — 목록을 만들 때는 둘이었는데 GONE 은 그사이 지워졌다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim")))).block();
        var query = new ScimQuery(null, 1, 100, false, ScimAttributeProjection.all());
        List<GroupHeader> headers = List.of(new GroupHeader("DEV", null, "개발"), new GroupHeader("GONE", null, "사라짐"));

        // when
        JsonNode 목록 = JSON.readTree(모은다(new ScimGroupStream(state).list(query, 2, headers)));

        // then
        assertThat(목록.get("schemas").get(0).asText()).isEqualTo(ScimSchemas.LIST_RESPONSE);
        assertThat(목록.get("totalResults").asLong()).isEqualTo(2);
        assertThat(목록.get("startIndex").asLong()).isEqualTo(1);
        assertThat(목록.get("itemsPerPage").asInt()).isEqualTo(1);
        assertThat(목록.get("Resources")).hasSize(1);
        assertThat(목록.get("Resources").get(0).get("id").asText()).isEqualTo("DEV");
        assertThat(목록.get("Resources").get(0).get("members")).hasSize(1);
    }
```

(`FakeStateRepository` 가 `final` 이 아니라 익명 하위 클래스를 쓸 수 있다. `findMemberRefs` 를 덮는다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimGroupStreamTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`ScimJson` 에 더한다(import `com.fasterxml.jackson.core.JsonProcessingException`, `java.io.UncheckedIOException`, `com.fasterxml.jackson.databind.JsonNode`):

```java
    static String string(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
```

`ScimAttributeProjection` 에 더한다(`apply` 가 배열 원소에 하는 일과 같다):

```java
    /**
     * 복합 속성의 원소 하나(예: 멤버 하나)에 하위 속성 투영을 건다 — {@link #apply} 가 배열 원소마다 하는 것과 같다.
     * 멤버를 흘려 쓸 때 멤버마다 부른다(설계 2026-10-06 §4.2).
     */
    public ObjectNode applyToElement(String attribute, ObjectNode element) {
        String name = attribute.toLowerCase(Locale.ROOT);
        if (!include.isEmpty() && !include.contains(name)) {
            Set<String> subs = subAttributes(include, name);
            if (!subs.isEmpty()) {
                retain(element, subs);
            }
        }
        for (String path : exclude) {
            if (path.startsWith(name + ".")) {
                removeIgnoringCase(element, path.substring(name.length() + 1));
            }
        }
        return element;
    }
```

`ScimMapper` — `toScimGroup(DirectoryGroup)` 안의 멤버 만들기를 꺼낸다:

```java
    /** 멤버 하나 — 조직 응답과 흘려 쓰는 응답이 같은 모양을 쓴다. */
    public static ScimMember toScimMember(MemberRef ref) {
        return new ScimMember(ref.id(), ref.type() == MemberType.GROUP ? "Group" : "User", null);
    }
```

`toScimGroup(DirectoryGroup)` 의 `.map(ref -> new ScimMember(...))` 를 `.map(ScimMapper::toScimMember)` 로 바꾼다.

`ScimGroupStream`:

```java
package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 멤버를 싣는 조직 응답을 흘려 쓴다(설계 2026-10-06 §4, 점검 P5). 멤버 줄을 DynamoDB 한 쪽씩 이어 읽어 JSON 으로 바로 내보낸다 —
 * 10만 명 조직도 메모리에 통째로 들지 않는다. 앞부분(이름표·투영)은 트리로 만들고 {@code members} 배열만 흘린다.
 *
 * <p>JSON 객체의 필드 순서에는 뜻이 없다(RFC 8259 §4). 그래서 {@code members} 는 맨 뒤에, 목록의 {@code itemsPerPage} 는 실제로 쓴 수를
 * 안 뒤 맨 끝에 쓴다. 첫 바이트가 나간 뒤 저장소가 실패하면 닫는 괄호를 쓰지 않고 오류로 끝난다 — 상태 코드를 바꿀 수 없으니 연결이 끊긴다(§10).
 */
final class ScimGroupStream {

    /** 멤버를 이만큼씩 묶어 버퍼 하나로 내보낸다. 멤버 하나가 약 40~60바이트라 묶음 하나가 수십 KB 다. */
    static final int 멤버_묶음 = 1_000;

    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private final DirectoryStateRepository state;

    ScimGroupStream(DirectoryStateRepository state) {
        this.state = state;
    }

    /** 조직 하나. 헤더는 호출자가 이미 읽었다 — 없는 조직이면 응답을 쓰기 전에 404 를 냈다. */
    Flux<DataBuffer> group(GroupHeader header, ScimAttributeProjection projection) {
        return Flux.concat(
                Mono.fromSupplier(() -> buffer(head(header, projection))),
                members(header.id(), projection),
                Mono.fromSupplier(() -> buffer("]}")));
    }

    /**
     * 목록(RFC 7644 §3.4.2). 조직을 하나씩 차례로 쓴다 — 앞 조직을 다 쓰기 전에 뒤 조직을 미리 읽지 않는다(미리 읽으면 큰 조직 하나를 통째로 쥔다).
     * 목록을 만든 뒤 지워진 조직은 헤더가 비어 건너뛴다.
     */
    Flux<DataBuffer> list(ScimQuery query, long totalResults, List<GroupHeader> headers) {
        return Flux.defer(() -> {
            AtomicInteger 쓴_수 = new AtomicInteger();
            Flux<DataBuffer> resources = Flux.fromIterable(headers)
                    .concatMap(listed -> state.findGroupHeader(listed.id())
                            .flatMapMany(header -> Flux.concat(
                                    Mono.fromSupplier(() -> buffer(쓴_수.getAndIncrement() == 0 ? "" : ",")),
                                    group(header, query.projection()))));
            return Flux.concat(
                    Mono.fromSupplier(() -> buffer(listHead(query, totalResults))),
                    resources,
                    Mono.fromSupplier(() -> buffer("],\"itemsPerPage\":" + 쓴_수.get() + "}")));
        });
    }

    private static String head(GroupHeader header, ScimAttributeProjection projection) {
        // 헤더로 만든 조직에는 members 가 없다(null 은 쓰지 않는다). id·schemas 는 늘 남아 객체가 비지 않는다
        String json = ScimJson.string(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header))));
        return json.substring(0, json.length() - 1) + ",\"members\":[";
    }

    private static String listHead(ScimQuery query, long totalResults) {
        ObjectNode head = JsonNodeFactory.instance.objectNode();
        head.putArray("schemas").add(ScimSchemas.LIST_RESPONSE);
        head.put("totalResults", totalResults);
        head.put("startIndex", query.startIndex());
        String json = ScimJson.string(head);
        return json.substring(0, json.length() - 1) + ",\"Resources\":[";
    }

    private Flux<DataBuffer> members(String groupId, ScimAttributeProjection projection) {
        return state.findMemberRefs(groupId)
                .map(ref -> ScimJson.string(projection.applyToElement("members",
                        ScimJson.tree(ScimMapper.toScimMember(ref)))))
                .buffer(멤버_묶음)
                .index()
                .map(묶음 -> buffer((묶음.getT1() == 0 ? "" : ",") + String.join(",", 묶음.getT2())));
    }

    private static DataBuffer buffer(String json) {
        return BUFFERS.wrap(json.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS. `toScimMember` 로 바꾼 `toScimGroup` 은 기존 테스트가 지킨다.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupStream.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimJson.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimAttributeProjection.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupStreamTest.java
git commit -F - <<'EOF'
feat: 멤버를 싣는 SCIM 조직 응답을 흘려 쓰는 부품 — 멤버를 한 쪽씩 이어 읽어 묶음으로 내보내고, 목록은 조직을 차례로·itemsPerPage 는 맨 끝(점검 P5)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 4: connector-scim — 핸들러·목록이 멤버를 실을 때 흘려 쓴다

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java` (`get`·`respond`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java` (`slice` 를 꺼내고 `streamed` 를 더한다)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimListHandler.java` (`listGroups`·`searchGroups`)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java`, `ScimGroupListingTest.java`, `ScimListHandlerTest.java` (더함·바꿈)

**Interfaces:**
- Consumes: Task 3 의 `ScimGroupStream.group(...)`·`list(...)`.
- Produces:
  - `ScimGroupListing.slice(ScimQuery)` — 지금의 헤더 자르기를 꺼낸 것, 패키지 전용.
  - `ScimGroupListing.streamed(ScimQuery): Mono<Flux<DataBuffer>>`, 패키지 전용.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimGroupHandlerTest` 에 더한다. 기존 `client`·`state` 를 쓴다. Task 5 등에서 쓰는 생성 도우미가 이 파일에 이미 있으면(예: `만든다(uri, body)`) 그것을 쓴다.

```java
    @Test
    @DisplayName("멤버를 싣는 조직 GET 은 흘려 쓴다 — Content-Length 가 없고 멤버가 다 실린다(설계 2026-10-06 §4.2, 점검 P5)")
    void 멤버를_싣는_GET_은_흘려_쓴다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV", "ext-DEV", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();

        // when, then
        client.get().uri("/scim/v2/Groups/DEV").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody()
                .jsonPath("$.id").isEqualTo("DEV")
                .jsonPath("$.displayName").isEqualTo("개발본부")
                .jsonPath("$.members.length()").isEqualTo(2);
    }

    @Test
    @DisplayName("excludedAttributes=members 면 지금처럼 이름표만 읽고 멤버 줄을 읽지 않는다")
    void 멤버를_빼면_멤버_줄을_읽지_않는다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV", null, "개발본부", Set.of(MemberRef.user("kim")))).block();
        state.findMemberRefsCalls.clear();

        // when, then
        client.get().uri("/scim/v2/Groups/DEV?excludedAttributes=members").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.members").doesNotExist();
        assertThat(state.findMemberRefsCalls).isEmpty();
    }

    @Test
    @DisplayName("없는 조직의 멤버 GET 은 응답을 쓰기 전에 404 다")
    void 없는_조직은_404다() {
        // when, then
        client.get().uri("/scim/v2/Groups/NOPE").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.status").isEqualTo("404");
    }

    @Test
    @DisplayName("attributes=members 가 붙은 PATCH 의 200 응답도 흘려 쓴다")
    void attributes_가_붙은_PATCH_도_흘려_쓴다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV", null, "개발본부", Set.of())).block();

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV?attributes=members").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"kim","type":"User"}]}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody().jsonPath("$.members[0].value").isEqualTo("kim");
    }
```

`ScimGroupListingTest` — 기존 `members_가_남으면_페이지의_조직을_읽는다`(멤버를 `findGroup` 으로 모으던 갈래를 단정)를 지우고 아래로 바꾼다. `setUp` 은 DEV001(멤버 kim·DEV002)과 DEV002(멤버 park)를 심는다. import 는 `DataBufferUtils`·`StandardCharsets`·`JsonNode`·`ObjectMapper` 다.

```java
    @Test
    @DisplayName("members 가 응답에 남으면 조직 파티션을 통째로 읽지 않고 흘려 쓴다 — 멤버 줄만 이어 읽는다(설계 2026-10-06 §4.3)")
    void members_가_남으면_흘려_쓴다() throws Exception {
        // when
        String body = listing.streamed(ScimQuery.of(ScimResourceType.GROUP, null, 1L, 100L, null, null, null, null))
                .flatMap(DataBufferUtils::join)
                .map(buffer -> {
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return text;
                })
                .block();
        JsonNode page = new ObjectMapper().readTree(body);

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMemberRefsCalls).containsExactlyInAnyOrder("DEV001", "DEV002");
        assertThat(page.get("itemsPerPage").asInt()).isEqualTo(2);
        assertThat(page.get("Resources").get(0).get("id").asText()).isEqualTo("DEV002");
        assertThat(page.get("Resources").get(1).get("members")).hasSize(2);
    }
```

`ScimListHandlerTest` 에 더한다. `setUp` 이 DEV001(멤버 Kim.Lee) 하나를 심는다.

```java
    @Test
    @DisplayName("멤버를 싣는 조직 목록은 흘려 쓴다 — Content-Length 가 없고 itemsPerPage 가 실제 수다(점검 P5)")
    void 멤버를_싣는_목록은_흘려_쓴다() {
        // when, then
        client.get().uri("/scim/v2/Groups").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(ScimRouter.SCIM_JSON)
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody()
                .jsonPath("$.itemsPerPage").isEqualTo(1)
                .jsonPath("$.Resources[0].id").isEqualTo("DEV001")
                .jsonPath("$.Resources[0].members[0].value").isEqualTo("Kim.Lee");
    }
```

(`excludedAttributes=members` 목록은 기존 테스트가 지킨다 — 지금처럼 한 번에 쓰는 응답이다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*ScimGroupHandlerTest' --tests '*ScimGroupListingTest'`
Expected: `Content-Length` 단정에서 실패한다(지금은 `bodyValue` 라 길이가 붙는다). 목 서버에서 `bodyValue` 응답에 `Content-Length` 가 안 붙으면 이 단정은 처음부터 통과한다. 그때는 보고서에 적고, 흘려 쓰기를 고정하는 다른 신호를 쓴다. 예를 들어 `state.findGroupCalls` 가 비어 있다(`findGroup` 을 부르지 않는다)는 단정이다.

- [ ] **Step 3: 구현**

`ScimGroupHandler`:
- 필드 `private final ScimGroupStream stream;` 을 더한다.
- `@RequiredArgsConstructor` 대신 생성자를 직접 쓴다. 생성자 서명은 그대로 두고 `stream = new ScimGroupStream(state)` 를 만든다. 생성자 호출부(`ScimConfig`·테스트)는 바꾸지 않는다.

```java
    public ScimGroupHandler(DirectoryStateRepository state, IncrementalSyncUseCase sync, MemberTypeResolver memberTypes) {
        this.state = state;
        this.sync = sync;
        this.memberTypes = memberTypes;
        this.stream = new ScimGroupStream(state);
    }
```

`get` — 시그니처(`Mono<ServerResponse> get(ServerRequest request)`)는 그대로, 몸통을 바꾼다:

```java
    public Mono<ServerResponse> get(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> body(HttpStatus.OK, id, projection,
                ScimException.notFound("조직을 찾을 수 없습니다: " + id)));
    }
```

`respond` — 부분 실패 503 확인(지금 그대로) 뒤 `body` 를 부른다:

```java
    private Mono<ServerResponse> respond(HttpStatus status, String id, IncrementalSyncResult result,
                                         ScimAttributeProjection projection) {
        if (!result.fullyApplied()) {
            return Mono.error(ScimException.temporarilyUnavailable(
                    "일부 튜플 적용에 실패했습니다 — 잠시 뒤 다시 보내 주세요: " + id, TemporaryFailureException.기본_대기));
        }
        return body(status, id, projection, ScimException.internal("저장된 리소스를 다시 읽지 못했습니다: " + id));
    }
```

공통 응답 만들기(새 private 메서드 둘). 멤버를 실으면 헤더를 먼저 읽고 흘려 쓰고, 아니면 지금처럼 이름표 트리다.

```java
    /**
     * 조직 응답. 헤더를 먼저 읽는다 — 없으면 응답을 쓰기 전에 {@code missing}(GET 이면 404, 쓰기 직후면 500)이다.
     * {@code members} 가 남으면 멤버를 흘려 쓰고(설계 2026-10-06 §4, 점검 P5), 남지 않으면 지금처럼 이름표만 쓴다(S-1 설계 §4.5).
     */
    private Mono<ServerResponse> body(HttpStatus status, String id, ScimAttributeProjection projection,
                                      ScimException missing) {
        return state.findGroupHeader(id)
                .switchIfEmpty(Mono.error(missing))
                .flatMap(header -> projection.includes("members")
                        ? builder(status, id).body(BodyInserters.fromDataBuffers(stream.group(header, projection)))
                        : builder(status, id).bodyValue(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header)))));
    }

    /** 생성(201)이면 {@code Location} 을 단다 — RFC 7644 §3.3(설계 2026-10-06 ⑤-2 §5.3). */
    private static ServerResponse.BodyBuilder builder(HttpStatus status, String id) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(status).contentType(SCIM_JSON);
        if (status == HttpStatus.CREATED) {
            builder.location(URI.create(ScimMapper.groupLocation(id)));
        }
        return builder;
    }
```

`byProjection` 은 쓰는 곳이 없어지면 지운다. 동작은 지금과 같아야 한다 — GET 404, 쓰기 뒤 다시 읽기 실패 500, POST 의 `Location`, 부분 실패 503. 지금 `respond` 의 Location 처리(⑤-2)를 `builder` 로 옮기면서 같은 값이 나는지 기존 테스트가 지킨다. (import `org.springframework.web.reactive.function.BodyInserters`, `dev.starryeye.organization.core.model.GroupHeader`.)

`ScimGroupListing`:
- 지금 `list` 안의 헤더 자르기(`request.filter() == null ? ScimPager.unfiltered(...) : filtered(request)`)를 `Mono<ScimPager.Slice<GroupHeader>> slice(ScimQuery request)` 로 꺼낸다. `list` 는 그것을 쓴다.
- 필드 `private final ScimGroupStream stream;` 을 더한다. 생성자에서 `new ScimGroupStream(state)` 로 만들고, 생성자 서명은 그대로다(`@RequiredArgsConstructor` 대신 직접 생성자).
- 더한다:

```java
    /** 멤버를 싣는 목록 — 헤더만 자르고 조직은 흘려 쓴다(설계 2026-10-06 §4.3). */
    Mono<Flux<DataBuffer>> streamed(ScimQuery request) {
        return slice(request).map(page -> stream.list(request, page.totalResults(), page.items()));
    }
```

- `resources(...)` 의 `members` 갈래(`state.findGroup` + `collectList`)는 지우고 이름표 갈래만 남긴다 — 멤버를 싣는 목록은 `ScimListHandler` 가 `streamed` 로 보낸다(`count == 0` 이면 `Resources` 가 없으니 이름표 갈래로 충분하다). `list` 의 자바독에 "멤버를 싣는 목록은 {@link #streamed}" 한 줄을 더한다.

`ScimListHandler`:

```java
    public Mono<ServerResponse> listGroups(ServerRequest request) {
        return Mono.fromCallable(() -> ScimQuery.fromRequest(ScimResourceType.GROUP, request))
                .flatMap(this::groupsResponse);
    }

    public Mono<ServerResponse> searchGroups(ServerRequest request) {
        return body(request)
                .map(search -> ScimQuery.fromSearch(ScimResourceType.GROUP, search))
                .flatMap(this::groupsResponse);
    }

    /** 멤버가 응답에 남고 자원을 담는 쪽(count > 0)이면 흘려 쓴다(설계 2026-10-06 §4.3). 아니면 지금처럼 한 번에. */
    private Mono<ServerResponse> groupsResponse(ScimQuery query) {
        if (query.projection().includes("members") && query.count() > 0) {
            return groups.streamed(query).flatMap(body ->
                    ServerResponse.ok().contentType(SCIM_JSON).body(BodyInserters.fromDataBuffers(body)));
        }
        return groups.list(query).flatMap(ScimListHandler::ok);
    }
```

(import `org.springframework.web.reactive.function.BodyInserters`, `org.springframework.core.io.buffer.DataBuffer`, `reactor.core.publisher.Flux`.)

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew :connector-scim:test`, 그다음 `./gradlew :app-scim:compileTestJava`
Expected: PASS. 기존 조직 응답 테스트(투영·Location·404·부분 실패 503·`excludedAttributes`)가 모두 그대로 통과해야 한다. 바꿔야 하는 기존 단정이 있으면 이유와 함께 보고서에 적는다. 예를 들어 `findGroupCalls` 를 세던 테스트다.

- [ ] **Step 5: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupListing.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimListHandler.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupListingTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimListHandlerTest.java
git commit -F - <<'EOF'
feat: 멤버를 싣는 SCIM 조직 응답(GET·목록·.search·쓰기 응답)을 흘려 쓴다 — 헤더를 먼저 읽어 404 는 응답 전에, 멤버는 메모리에 모으지 않는다(점검 P5)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

(바꾼 테스트 파일이 더 있으면 그 경로도 `git add` 한다.)

---

### Task 5: app-scim 규모 — 10만 명 조직 읽기

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLargeGroupReadScaleTest.java`

**Interfaces:**
- Consumes: Task 1~4 전부. `DynamoDbReadCounter`(app-scim 테스트, `@Import`)를 쓰고, `ScaleContainers`·`@ScaleTest` 를 쓴다.

- [ ] **Step 1: 테스트를 쓴다**

`ScimGroupMemberPatchScaleTest` 의 클래스 모양을 그대로 따른다:
- `@Testcontainers`, `@ActiveProfiles("test")`, `@TestMethodOrder(OrderAnnotation)`, `@SpringBootTest(RANDOM_PORT)`
- `@Import({DynamoDbReadCounter.class, TupleCheckCounter.class})`, `@ScaleTest`
- `ScaleContainers.openFga()/dynamoDb()`, `주소를_등록한다`
- `@Autowired WebTestClient client`, `DirectoryStateRepository state`, `DynamoDbReadCounter counter`

```java
    private static final int 전체 = 100_000;
    private static final String 조직 = "ALL";

    @Test
    @Order(1)
    @DisplayName("멤버 10만 명 조직과 첫 쪽 직원 20명을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();
        Set<MemberRef> 전원 = new LinkedHashSet<>();
        for (int i = 0; i < 전체; i++) {
            전원.add(MemberRef.user("m%06d".formatted(i)));
        }

        // when — 첫 쪽(아이디 순 앞 20명)은 직원 레코드도 둔다. 관리 API 는 레코드가 없는 멤버를 건너뛴다
        for (int i = 0; i < 20; i++) {
            String id = "m%06d".formatted(i);
            state.saveUser(new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true)).block();
        }
        state.saveGroup(new DirectoryGroup(조직, "ext-" + 조직, "전 직원", 전원)).block(Duration.ofMinutes(30));

        // then
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isEqualTo((long) 전체);
        System.out.printf("심기: 멤버 %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("관리 API 멤버 목록 첫 쪽은 조직 파티션을 훑지 않는다 — 훑은 아이템이 쪽 크기 수준이다(점검 P4)")
    void 관리_API_첫_쪽은_쪽_크기만큼_읽는다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.get().uri("/admin/organizations/" + 조직 + "/members?limit=20").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(20)
                .jsonPath("$.nextCursor").isNotEmpty();

        // then
        System.out.printf("관리 API 멤버 첫 쪽: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번%n",
                System.currentTimeMillis() - 시작, counter.queries.get(), counter.scannedItems.get(), counter.getItems.get());
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(20);
    }

    @Test
    @Order(3)
    @DisplayName("관리 API 조직 상세도 조직 파티션을 훑지 않는다 — 하위 조직 접두와 멤버 첫 쪽만(점검 P4)")
    void 관리_API_상세도_파티션을_훑지_않는다() {
        // given
        counter.reset();

        // when
        client.get().uri("/admin/organizations/" + 조직).exchange().expectStatus().isOk();

        // then
        System.out.printf("관리 API 조직 상세: Query %,d번, 훑은 아이템 %,d%n", counter.queries.get(), counter.scannedItems.get());
        assertThat(counter.scannedItems.get()).isLessThanOrEqualTo(20 + 200);
    }

    @Test
    @Order(4)
    @DisplayName("멤버 10만 명 조직 GET 은 흘려 쓴다 — 멤버 100,000개, Content-Length 없음, 걸린 시간 기록(점검 P5)")
    void SCIM_조직_GET_은_흘려_쓴다() throws Exception {
        // given
        long 시작 = System.currentTimeMillis();

        // when
        var result = client.mutate().responseTimeout(Duration.ofMinutes(5)).build()
                .get().uri("/scim/v2/Groups/" + 조직).exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.CONTENT_LENGTH)
                .expectBody().returnResult();

        // then
        JsonNode body = new ObjectMapper().readTree(result.getResponseBody());
        System.out.printf("SCIM 조직 GET(멤버 %,d명): %,dms, 응답 %,d바이트%n",
                body.get("members").size(), System.currentTimeMillis() - 시작, result.getResponseBody().length);
        assertThat(body.get("members")).hasSize(전체);
    }
```

(이 테스트는 응답 전체를 받아 세므로, 테스트 쪽 메모리는 응답 크기만큼 쓴다. 서버 쪽은 흘려 쓴다.)

- [ ] **Step 2: 돌린다**

Run: `./gradlew :app-scim:scaleTest --tests '*ScimLargeGroupReadScaleTest'`
Expected: PASS. 출력의 측정 줄을 보고서에 옮긴다. 컨트롤러는 나중에 전체 `scaleTest` 를 돌린다. 이 클래스 하나만 돌리는 것은 구현자가 해도 된다.

- [ ] **Step 3: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLargeGroupReadScaleTest.java
git commit -F - <<'EOF'
test: 10만 명 조직 읽기 규모 — 관리 API 첫 쪽·상세는 쪽 크기만큼, SCIM 조직 GET 은 흘려 써 멤버 100,000개(점검 P4·P5)

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

### Task 6: README, 점검 문서, 스펙 보정

**Files:**
- Modify: `README.md` (관리 API 절, SCIM 절), `docs/superpowers/specs/2026-09-28-full-audit.md`, `docs/superpowers/specs/2026-10-06-read-paths-design.md`

- [ ] **Step 1: README**

관리 API 절의 멤버 목록·조직 상세 설명을 고친다(코드를 보고 쓴다):
- 멤버 목록 `cursor` 는 불투명 문자열이다(다음 쪽 응답의 `nextCursor` 를 그대로 넘긴다).
- 쪽이 정확히 끝나면 빈 마지막 쪽이 한 번 올 수 있다.
- 다른 조직의 커서나 고친 커서는 400 이다.
- 조직 상세는 멤버 첫 쪽만 읽는다.
- 오프셋(숫자) 커서를 설명하는 문장이 있으면 지운다.

SCIM 절:
- 멤버를 싣는 조직 응답(`GET /Groups/{id}`, 목록·`.search`, 쓰기 응답)은 청크로 전송된다(`Content-Length` 없음).
- 멤버를 DynamoDB 한 쪽씩 읽어 메모리에 모으지 않는다.
- 응답 도중 저장소가 실패하면 연결이 끊긴다.
- `excludedAttributes=members` 를 붙이면(Entra) 이름표만 읽는다.

- [ ] **Step 2: 점검 문서**

- P4·P5 행(요약 표) 끝에 `**→ 해결(2026-10-06, 슬라이드 ⑥-1)**` 을 단다.
- S16 행(부록 표) 끝에 `**→ 뒤쪽(멤버 목록 오프셋) 해결·앞쪽(위조 커서)은 멤버 목록에서만 해결(2026-10-06, 슬라이드 ⑥-1)**` 을 단다.
- 모양은 ⑤-2 표시와 같게 맞춘다.

- [ ] **Step 3: 스펙 보정**

컨트롤러가 넘기는 "구현 중 정한 것" 목록이 있으면 해당 절 끝에 한 줄씩 적는다. §9 는 건드리지 않는다. 스펙 문장이 코드와 다르면 코드에 맞춘다.

- [ ] **Step 4: 커밋**

```bash
git add README.md docs/superpowers/specs/2026-09-28-full-audit.md docs/superpowers/specs/2026-10-06-read-paths-design.md
git commit -F - <<'EOF'
docs: ⑥-1 README — 관리 API 멤버 목록 불투명 커서·빈 마지막 쪽, SCIM 조직 응답 청크 전송, 점검 P4·P5·S16 표시, 설계 보정

<자기 하네스의 Co-Authored-By 줄>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 를 먼저 돌리고, 그다음 `./gradlew cleanScaleTest scaleTest` 를 돌린다. 한 번에 하나씩이다.
- 스펙 §9 에 결과(테스트 수·시간·규모 측정)를 적고 커밋한다.
