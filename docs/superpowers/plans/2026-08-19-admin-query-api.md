# 관리자 조회 API 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 동기화된 조직·직원을 접두사로 검색하고, 한 직원이 어떤 경로로 어느 조직에 접근되는지를 OpenFGA 의 실제 판정과 나란히 보여주는 읽기 전용 API 를 완성한다.

**Architecture:** `core` 에 조회 전용 포트 둘(`DirectorySearchRepository`, `RelationTupleChecker`)과 유스케이스 하나(`AdminQueryUseCase`)를 더한다. 새 모듈 `admin-api` 가 그 유스케이스를 HTTP 로 노출하고, app-ldap·app-scim 이 각자 탑재한다. 계층 순회는 이미 있는 `findGroupIdsContaining` 을 반복해 올라가는 방식이고, 캐시는 두지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.3.5 WebFlux (`@RestController`), AWS SDK v2, OpenFGA SDK, Lombok, JUnit 5, AssertJ, Testcontainers

**Spec:** [`docs/superpowers/specs/2026-08-19-admin-query-api-design.md`](../specs/2026-08-19-admin-query-api-design.md)

**선행 설계:** [`2026-08-14-organization-sync-design.md`](../specs/2026-08-14-organization-sync-design.md) — 특히 §2 의 정정 이력(`Check` 는 허용, 열거 API 만 금지), §4.3(식별자), §6.1(단일 테이블).

**선행 계획:** LDAP·SCIM 두 계획이 완료되어 `core` 포트 5종과 세 어댑터, `app-ldap`/`app-scim` 이 모두 동작하는 상태에서 시작한다.

## Global Constraints

- **Java 17.** 패키지 루트 `dev.starryeye.organization`. 새 모듈의 패키지는 `dev.starryeye.organization.admin`.
- **`core` 의 기존 파일을 수정하지 않는다.** 이 계획이 더하는 것은 `core.query` 패키지(신규), `core.port` 의 새 인터페이스 2개, `core.usecase` 의 새 유스케이스 1개뿐이다.
- **`Check` 는 허용, `Read`/`ListObjects` 는 금지** (선행 설계 §2 정정 이력). 쓰기 경로는 여전히 Write/Delete 만 쓴다.
- **조직명(`displayName`)은 튜플에 들어가지 않는다.** 조회 응답에만 실린다.
- **접두사 검색만 구현한다.** 부분일치·전문검색 없음.
- **키 접두사와 인덱스 상수는 `Keys` 에서만** 만든다. 인라인 리터럴 금지.
- **`shouldHaveAccess` 는 `active` 를 먼저 본다.** 비활성 직원은 모든 조직에 대해 false. 이걸 빠뜨리면 퇴사자 화면이 전부 "어긋남"으로 보여 화면이 쓸모없어진다.
- **`Check` 실패는 요청을 실패시키지 않는다.** 해당 칸만 `null`.
- **검색 파라미터가 없거나 둘 다 있으면 400.** 빈 접두사는 전체 열거가 되므로 입구에서 막는다.
- **`limit` 기본 20, 범위 1~100. `paths` 상한 200.**
- **`core` 에는 Jackson 이 없다.** `core.query` 의 record 는 Jackson 애너테이션 없이 리플렉션으로 직렬화된다 — 모든 필드가 항상 응답에 실린다(`cycle: false`, `openFgaCheck: null` 포함).
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
record RelationTuple(String user, String relation, String object) {
    static RelationTuple directMember(String userId, String groupId);
    static RelationTuple child(String childGroupId, String parentGroupId);
}
enum MemberType { USER, GROUP }

// dev.starryeye.organization.core.port
interface DirectoryStateRepository {
    Mono<DirectoryUser> findUser(String);   Mono<DirectoryGroup> findGroup(String);
    Flux<String> findGroupIdsContaining(MemberRef ref);
    Flux<String> findUserIdsByUserName(String userName);
    // ... 쓰기 메서드들은 이 계획에서 쓰지 않는다
}

// dev.starryeye.organization.storage (storage-dynamodb)
final class Keys {
    static final String PK, SK, GSI1PK, GSI1SK, GSI1, META;
    static final String USER_INDEX, GROUP_INDEX, SNAPSHOT_INDEX;
    static String userPk(String userId);      static String parseUserPk(String pk);
    static String groupPk(String groupId);    static String parseGroupPk(String pk);
}
final class Attrs {
    static AttributeValue s(String);  static AttributeValue bool(boolean);
    static String str(Map<String,AttributeValue>, String name);
    static boolean flag(Map<String,AttributeValue>, String name);
    static void putIfPresent(Map<String,AttributeValue>, String name, String value);
}

// dev.starryeye.organization.authz (authz-openfga)
class StoreBootstrapper {
    Mono<String> resolveStore();          // store 없으면 생성 — 쓰기 경로용
    Mono<String> findExistingStore();     // read-only
    OpenFgaClient client();
}
```

`core/src/testFixtures/java/.../core/fake/` 의 fake 5종도 그대로 재사용한다.

## File Structure

| 파일 | 책임 |
|---|---|
| `core/.../query/UserSummary.java` 외 6종 | 조회 결과 타입. Jackson 없이 직렬화되는 record |
| `core/.../port/DirectorySearchRepository.java` | 접두사 검색·페이징 포트 |
| `core/.../port/RelationTupleChecker.java` | `Check` 전용 포트 |
| `core/.../usecase/AdminQueryUseCase.java` | 계층 순회 + `shouldHaveAccess` + `Check` 병기 |
| `core/src/testFixtures/.../fake/FakeSearchRepository.java` | 검색 포트 페이크 |
| `core/src/testFixtures/.../fake/FakeTupleChecker.java` | Check 포트 페이크 |
| `storage-dynamodb/.../DynamoDbDirectorySearchRepository.java` | GSI 접두사 Query + 커서 |
| `authz-openfga/.../OpenFgaRelationTupleChecker.java` | SDK `check` 호출 |
| `admin-api/.../AdminQueryController.java` | 6개 엔드포인트 |
| `admin-api/.../AdminQueryConfig.java` | 빈 등록, 자동설정 |
| `admin-api/.../AdminQueryMetrics.java` | 지연·드리프트 카운터 |

---

## Task 1: 조회 계약 — 타입, 포트, 페이크

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/query/` 아래 `UserSummary`, `GroupSummary`, `Page`, `AccessPath`, `EmployeeDetail`, `OrgMember`, `OrganizationDetail`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java`, `RelationTupleChecker.java`
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java`, `FakeTupleChecker.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/query/PageTest.java`

**Interfaces:**
- Consumes: `core.model` 의 `RelationTuple`
- Produces: 위 7개 record 와 2개 인터페이스. Task 2~5 가 전부 이것에 의존한다.

**왜 record 에 Jackson 애너테이션이 없나.** `core` 의 의존은 reactor-core 와 slf4j 뿐이다(`core/build.gradle` 확인). Jackson 을 넣으면 도메인 모듈이 직렬화 라이브러리에 묶인다. 애너테이션 없이도 Jackson 은 record 를 직렬화하므로, 대신 **모든 필드가 항상 응답에 실린다** — `cycle` 은 false 일 때도 나오고 `openFgaCheck` 는 null 일 때도 나온다. 설계 §7 의 예시 JSON 이 일부 필드를 생략한 것은 지면 사정이며, 실제 응답은 항상 전체 필드를 담는다.

**`via` 가 enum 이 아니라 String 인 이유.** enum 을 쓰면 Jackson 이 `"DIRECT"` 로 쓰는데 설계 문서의 예시는 `"direct"` 다. 소문자로 쓰려면 `@JsonValue` 가 필요하고 그건 `core` 에 Jackson 을 들이는 일이다. 상수 두 개를 둔 String 이 그 대가보다 싸다.

- [ ] **Step 1: 실패하는 테스트 작성**

`PageTest.java`:

```java
package dev.starryeye.organization.core.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageTest {

    @Test
    @DisplayName("nextCursor 가 null 이면 마지막 페이지다")
    void 커서가_null이면_마지막_페이지다() {
        // given
        Page<String> page = new Page<>(List.of("a", "b"), null);

        // when, then
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("nextCursor 가 있으면 다음 페이지가 있다")
    void 커서가_있으면_다음_페이지가_있다() {
        // given
        Page<String> page = new Page<>(List.of("a"), "eyJQSyI6...");

        // when, then
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("빈 페이지도 만들 수 있고 항목이 비어 있다")
    void 빈_페이지() {
        // given
        Page<String> page = Page.empty();

        // when, then
        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("items 는 방어적으로 복사되어 밖에서 바꿔도 페이지가 안 바뀐다")
    void items는_방어적으로_복사된다() {
        // given
        List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(mutable, null);

        // when
        mutable.add("b");

        // then
        assertThat(page.items()).containsExactly("a");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests '*PageTest*'`
Expected: 컴파일 실패 — `Page` 를 찾을 수 없음

- [ ] **Step 3: 조회 타입 작성**

`core/src/main/java/dev/starryeye/organization/core/query/Page.java`:

```java
package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * 커서 기반 한 페이지. {@code nextCursor} 가 null 이면 마지막이다.
 *
 * <p>DynamoDB 는 offset 을 지원하지 않으므로 페이지 번호가 아니라 커서를 쓴다.
 * 커서는 저장소가 만든 불투명 문자열이며 호출자는 해석하지 않는다.
 */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> Page<T> empty() {
        return new Page<>(List.of(), null);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
```

`UserSummary.java`:

```java
package dev.starryeye.organization.core.query;

/** 직원 검색 결과 한 줄. 소속 정보는 담지 않는다 — 그건 상세 조회의 일이다. */
public record UserSummary(String employeeId, String userName,
                          String displayName, boolean active) {
}
```

`GroupSummary.java`:

```java
package dev.starryeye.organization.core.query;

/** 조직 검색 결과 한 줄이자 계층 목록의 원소. */
public record GroupSummary(String orgCode, String displayName) {
}
```

`AccessPath.java`:

```java
package dev.starryeye.organization.core.query;

/**
 * 한 직원이 한 조직에 접근되는(또는 되어야 하는) 경로 한 줄.
 *
 * @param shouldHaveAccess 현재상태(DynamoDB)가 요구하는 값
 * @param openFgaCheck     OpenFGA 의 실제 판정. Check 호출이 실패하면 null
 * @param cycle            상위 순회 중 이미 본 조직에 다시 닿았는지
 */
public record AccessPath(String orgCode, String displayName, String via,
                         boolean shouldHaveAccess, Boolean openFgaCheck, boolean cycle) {

    /** 직원이 이 조직의 멤버로 직접 등록돼 있다. */
    public static final String DIRECT = "direct";
    /** 하위 조직을 통해 상위로 올라온 경로다. */
    public static final String ROLLUP = "rollup";

    /** 파생값과 실제 판정이 갈리는가. Check 를 못 했으면 판단을 보류한다(false). */
    public boolean drifted() {
        return openFgaCheck != null && shouldHaveAccess != openFgaCheck;
    }
}
```

`EmployeeDetail.java`:

```java
package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param paths     소속 조직과 그 상위 계층 전부. 어디에도 안 속하면 빈 목록
 * @param truncated 경로가 상한을 넘어 잘렸는지
 */
public record EmployeeDetail(String employeeId, String userName, String displayName,
                             String email, boolean active,
                             List<AccessPath> paths, boolean truncated) {

    public EmployeeDetail {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}
```

`OrgMember.java`:

```java
package dev.starryeye.organization.core.query;

/** 조직의 직속 소속 직원 한 줄. Check 실패 시 {@code openFgaCheck} 는 null. */
public record OrgMember(String employeeId, String displayName,
                        boolean active, Boolean openFgaCheck) {
}
```

`OrganizationDetail.java`:

```java
package dev.starryeye.organization.core.query;

import java.util.List;

/**
 * @param ancestors          상위 계층 전부. 최상위 조직이면 빈 목록
 * @param childOrganizations 직속 하위 조직만 (1 depth)
 * @param members            직속 소속 직원 첫 페이지
 */
public record OrganizationDetail(String orgCode, String displayName, String externalId,
                                 List<GroupSummary> ancestors,
                                 List<GroupSummary> childOrganizations,
                                 Page<OrgMember> members) {

    public OrganizationDetail {
        ancestors = ancestors == null ? List.of() : List.copyOf(ancestors);
        childOrganizations = childOrganizations == null ? List.of() : List.copyOf(childOrganizations);
    }
}
```

- [ ] **Step 4: 포트 두 개 작성**

`core/src/main/java/dev/starryeye/organization/core/port/DirectorySearchRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import reactor.core.publisher.Mono;

/**
 * 접두사 검색 전용 읽기 포트.
 *
 * <p>{@link DirectoryStateRepository} 에 얹지 않는다. 그쪽은 동기화 쓰기 경로의 심장이라
 * 조회 관심사를 섞으면 조회 코드가 쓰기 메서드까지 전부 보게 된다.
 *
 * <p>필드별로 메서드를 나눈 이유도 같다 — {@code field} 파라미터 하나로 합치면 구현이
 * 인덱스 선택 분기를 안고 가고, 호출부에서 어떤 인덱스를 타는지 보이지 않는다.
 *
 * <p>{@code prefix} 는 비어 있을 수 없다. 빈 접두사는 전체 열거이며 호출자가 막는다.
 * {@code cursor} 가 null 이면 첫 페이지다.
 */
public interface DirectorySearchRepository {

    Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit);

    Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit);

    Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit);
}
```

`RelationTupleChecker.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 인가 판정을 묻는다.
 *
 * <p>{@link RelationTupleWriter} 에 얹지 않는다. 얹으면 동기화 경로가 의도치 않게
 * 판정에 의존하기 쉬워지고 "쓰기 어댑터" 라는 이름이 거짓이 된다.
 *
 * <p>열거 API(Read/ListObjects)는 여전히 쓰지 않는다. {@code Check} 는 점 조회라
 * 열거를 대체하지 못하므로, 스냅샷 기준선과 diff 는 이 포트가 생겨도 그대로다.
 */
public interface RelationTupleChecker {

    /** 이 튜플이 성립하는가. 롤업 관계(`member`)도 서버가 해석해 답한다. */
    Mono<Boolean> check(RelationTuple tuple);
}
```

- [ ] **Step 5: 페이크 두 개 작성**

`core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSearchRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 메모리 위에서 접두사 검색과 커서 페이징을 흉내낸다.
 * 커서는 "다음에 읽을 항목의 인덱스" 를 그대로 문자열로 쓴다 — 불투명하다는 계약만 지키면 되고,
 * 테스트에서는 형식이 단순한 편이 낫다.
 */
public class FakeSearchRepository implements DirectorySearchRepository {

    public final List<UserSummary> users = new ArrayList<>();
    public final List<GroupSummary> groups = new ArrayList<>();

    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        return Mono.just(page(users, UserSummary::userName, prefix, cursor, limit));
    }

    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        // displayName 이 없는 직원은 인덱스에 실리지 않는다 — 실제 GSI 동작과 맞춘다
        List<UserSummary> indexed = users.stream().filter(u -> u.displayName() != null).toList();
        return Mono.just(page(indexed, UserSummary::displayName, prefix, cursor, limit));
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return Mono.just(page(groups, GroupSummary::displayName, prefix, cursor, limit));
    }

    private <T> Page<T> page(List<T> source, java.util.function.Function<T, String> sortKey,
                             String prefix, String cursor, int limit) {
        List<T> matched = source.stream()
                .filter(item -> sortKey.apply(item) != null && sortKey.apply(item).startsWith(prefix))
                .sorted(Comparator.comparing(sortKey))
                .toList();

        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(from + limit, matched.size());
        String next = to < matched.size() ? String.valueOf(to) : null;
        return new Page<>(matched.subList(from, to), next);
    }
}
```

`FakeTupleChecker.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link #allowed} 에 넣은 튜플만 true 로 답한다. 즉 "OpenFGA 에 실제로 있는 것" 을 흉내낸다.
 * {@link #failWhen} 을 걸면 그 튜플의 Check 가 실패한다 — 실패가 null 로 흐르는지 보는 데 쓴다.
 */
public class FakeTupleChecker implements RelationTupleChecker {

    public final Set<RelationTuple> allowed = new LinkedHashSet<>();
    public final List<RelationTuple> checked = new ArrayList<>();
    public Predicate<RelationTuple> failWhen = tuple -> false;

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return Mono.fromCallable(() -> {
            checked.add(tuple);
            if (failWhen.test(tuple)) {
                throw new IllegalStateException("Check 실패(테스트)");
            }
            return allowed.contains(tuple);
        });
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*PageTest*'`
Expected: PASS 4개

- [ ] **Step 7: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. 기존 테스트가 하나도 깨지지 않아야 한다 — 이 태스크는 순수 추가다.

- [ ] **Step 8: 커밋과 푸시**

```bash
git add core/src/main/java/dev/starryeye/organization/core/query core/src/main/java/dev/starryeye/organization/core/port core/src/testFixtures core/src/test/java/dev/starryeye/organization/core/query
git commit -m "feat: 조회 계약 추가 — 결과 타입, 검색·Check 포트, 페이크"
git push origin <현재 브랜치>
```

---

## Task 2: AdminQueryUseCase — 계층 순회와 Check 병기

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java`

**Interfaces:**
- Consumes: Task 1 의 타입·포트 전부, `DirectoryStateRepository`, 페이크 4종(`FakeStateRepository`, `FakeSearchRepository`, `FakeTupleChecker`)
- Produces:
  - `AdminQueryUseCase(DirectoryStateRepository state, DirectorySearchRepository search, RelationTupleChecker checker)`
  - `Mono<Page<UserSummary>> searchEmployeesByUserName(String prefix, String cursor, int limit)`
  - `Mono<Page<UserSummary>> searchEmployeesByDisplayName(String prefix, String cursor, int limit)`
  - `Mono<Page<GroupSummary>> searchOrganizations(String prefix, String cursor, int limit)`
  - `Mono<EmployeeDetail> employeeDetail(String employeeId)` — 없으면 빈 `Mono`
  - `Mono<OrganizationDetail> organizationDetail(String orgCode, int memberPageSize)` — 없으면 빈 `Mono`
  - `Mono<Page<OrgMember>> organizationMembers(String orgCode, String cursor, int limit)` — 조직이 없으면 빈 `Mono`
  - `public static final int MAX_PATHS = 200`

**이 태스크가 이 계획의 핵심이다.** 나머지는 배선과 어댑터다.

**계층 순회.** 직속 소속은 `findGroupIdsContaining(MemberRef.user(id))`, 상위는 `findGroupIdsContaining(MemberRef.group(code))` 를 반복한다. 방문 집합을 두고, 이미 본 조직에 다시 닿으면 그 항목에 `cycle=true` 를 달고 더 올라가지 않는다. 총 경로가 `MAX_PATHS` 를 넘으면 자르고 `truncated=true`.

**`shouldHaveAccess` 규칙.** `직원.active && (직속이거나 조상)`. 순회로 도달한 조직은 정의상 전부 "직속이거나 조상" 이므로, 실제로는 **`active` 하나가 전부를 가른다.** 비활성 직원은 모든 경로가 false 다.

**Check 실패 처리.** `checker.check(...)` 가 에러를 내면 그 항목만 `openFgaCheck=null` 로 만들고 전체는 성공시킨다. `onErrorResume` 으로 감싸되 **항목 단위로** 감싸야 한다 — 스트림 전체에 걸면 첫 실패에서 나머지 항목이 사라진다.

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminQueryUseCaseTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSearchRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.query.AccessPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminQueryUseCaseTest {

    private FakeStateRepository state;
    private FakeSearchRepository search;
    private FakeTupleChecker checker;
    private AdminQueryUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        search = new FakeSearchRepository();
        checker = new FakeTupleChecker();
        useCase = new AdminQueryUseCase(state, search, checker);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "emp-" + id, id, id + "-이름", id + "@example.com", active);
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, code + "-조직", Set.of(members));
    }

    @Test
    @DisplayName("직속 소속과 그 상위 계층 전부가 경로로 나온다")
    void 직속과_상위계층이_경로가_된다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ kim
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV001", "ROOT");
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV002"))
                .extracting(AccessPath::via).containsExactly(AccessPath.DIRECT);
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("ROOT"))
                .extracting(AccessPath::via).containsExactly(AccessPath.ROLLUP);
        assertThat(detail.truncated()).isFalse();
    }

    @Test
    @DisplayName("비활성 직원은 모든 경로의 shouldHaveAccess 가 false 다")
    void 비활성_직원은_전부_false다() {
        // given — 소속은 그대로 있지만 비활성이다
        state.saveUser(직원("kim", false)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 경로는 보이되 권한은 없어야 한다. 이걸 빠뜨리면 퇴사자 화면이 전부 어긋남으로 보인다
        assertThat(detail.paths()).hasSize(2);
        assertThat(detail.paths()).allMatch(p -> !p.shouldHaveAccess());
        assertThat(detail.paths()).noneMatch(AccessPath::drifted);
    }

    @Test
    @DisplayName("OpenFGA 에 튜플이 없으면 파생값과 갈려 drifted 로 잡힌다")
    void 튜플이_없으면_드리프트다() {
        // given — 상태는 소속을 말하는데 OpenFGA 에는 아무 튜플도 없다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        var path = detail.paths().get(0);
        assertThat(path.shouldHaveAccess()).isTrue();
        assertThat(path.openFgaCheck()).isFalse();
        assertThat(path.drifted()).isTrue();
    }

    @Test
    @DisplayName("Check 가 실패하면 그 항목만 null 이 되고 조회는 성공한다")
    void Check_실패는_null로_흐른다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));
        checker.failWhen = tuple -> tuple.object().equals("group:DEV001");

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 요청 자체는 성공하고, 실패한 칸만 null 이다
        assertThat(detail.paths()).hasSize(2);
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV001"))
                .extracting(AccessPath::openFgaCheck).containsOnlyNulls();
        assertThat(detail.paths()).filteredOn(p -> p.orgCode().equals("DEV002"))
                .extracting(AccessPath::openFgaCheck).containsExactly(true);
    }

    @Test
    @DisplayName("Check 를 못 한 항목은 drifted 로 세지 않는다")
    void Check를_못하면_드리프트로_안_센다() {
        // given
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        checker.failWhen = tuple -> true;

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 모른다는 것과 어긋났다는 것은 다르다
        assertThat(detail.paths().get(0).openFgaCheck()).isNull();
        assertThat(detail.paths().get(0).drifted()).isFalse();
    }

    @Test
    @DisplayName("상위 계층에 순환이 있으면 표시하고 순회를 멈춘다")
    void 순환은_표시하고_멈춘다() {
        // given — DEV001 ⊇ DEV002 이고 DEV002 ⊇ DEV001 (순환)
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.group("DEV001"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 무한 루프에 빠지지 않고, 다시 닿은 지점을 드러낸다
        assertThat(detail.paths()).extracting(AccessPath::orgCode)
                .containsExactlyInAnyOrder("DEV002", "DEV001");
        assertThat(detail.paths()).anyMatch(AccessPath::cycle);
    }

    @Test
    @DisplayName("어느 조직에도 속하지 않은 직원은 빈 경로를 돌려준다")
    void 소속이_없으면_빈_경로다() {
        // given
        state.saveUser(직원("kim", true)).block();

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 404 가 아니다. 직원은 존재한다
        assertThat(detail.employeeId()).isEqualTo("kim");
        assertThat(detail.paths()).isEmpty();
    }

    @Test
    @DisplayName("없는 직원은 빈 Mono 다")
    void 없는_직원은_빈_Mono다() {
        // when, then
        assertThat(useCase.employeeDetail("nobody").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 조직을 참조하는 소속은 건너뛴다")
    void 없는_조직_참조는_건너뛴다() {
        // given — DEV002 가 kim 을 갖지만 DEV002 레코드 자체는 없다
        state.saveUser(직원("kim", true)).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"))).block();
        state.groups.remove("DEV002");

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then
        assertThat(detail.paths()).isEmpty();
    }

    @Test
    @DisplayName("조직 상세는 상위 전체와 직속 하위 1 depth 만 담는다")
    void 조직_상세는_상위_전체와_하위_한칸이다() {
        // given — ROOT ⊇ DEV001 ⊇ DEV002 ⊇ {kim, DEV003}, DEV003 ⊇ lee
        state.saveUser(직원("kim", true)).block();
        state.saveUser(직원("lee", true)).block();
        state.saveGroup(조직("DEV003", MemberRef.user("lee"))).block();
        state.saveGroup(조직("DEV002", MemberRef.user("kim"), MemberRef.group("DEV003"))).block();
        state.saveGroup(조직("DEV001", MemberRef.group("DEV002"))).block();
        state.saveGroup(조직("ROOT", MemberRef.group("DEV001"))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when
        var detail = useCase.organizationDetail("DEV002", 20).block();

        // then
        assertThat(detail.ancestors()).extracting("orgCode").containsExactly("DEV001", "ROOT");
        assertThat(detail.childOrganizations()).extracting("orgCode").containsExactly("DEV003");
        // 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다
        assertThat(detail.childOrganizations()).extracting("displayName").containsExactly("DEV003-조직");
        // 하위의 하위(lee)는 담기지 않는다
        assertThat(detail.members().items()).extracting("employeeId").containsExactly("kim");
        assertThat(detail.members().items()).extracting("openFgaCheck").containsExactly(true);
    }

    @Test
    @DisplayName("최상위 조직은 상위 계층이 비어 있다")
    void 최상위_조직은_상위가_없다() {
        // given
        state.saveGroup(조직("ROOT")).block();

        // when
        var detail = useCase.organizationDetail("ROOT", 20).block();

        // then
        assertThat(detail.ancestors()).isEmpty();
        assertThat(detail.childOrganizations()).isEmpty();
        assertThat(detail.members().items()).isEmpty();
    }

    @Test
    @DisplayName("없는 조직은 빈 Mono 다")
    void 없는_조직은_빈_Mono다() {
        // when, then
        assertThat(useCase.organizationDetail("NOPE", 20).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("경로가 상한을 넘으면 잘라내고 truncated 를 세운다")
    void 상한을_넘으면_자른다() {
        // given — kim 이 직속으로 속한 조직을 상한보다 많이 만든다
        state.saveUser(직원("kim", true)).block();
        for (int i = 0; i < AdminQueryUseCase.MAX_PATHS + 10; i++) {
            state.saveGroup(조직("G" + i, MemberRef.user("kim"))).block();
        }

        // when
        var detail = useCase.employeeDetail("kim").block();

        // then — 상한 없이 훑는 대신 그 사실을 드러낸다
        assertThat(detail.paths()).hasSizeLessThanOrEqualTo(AdminQueryUseCase.MAX_PATHS);
        assertThat(detail.truncated()).isTrue();
    }

    @Test
    @DisplayName("검색은 저장소에 그대로 위임한다")
    void 검색은_그대로_위임한다() {
        // given
        search.users.add(new dev.starryeye.organization.core.query.UserSummary(
                "gd.hong", "gd.hong", "홍길동", true));

        // when
        var byName = useCase.searchEmployeesByDisplayName("홍", null, 20).block();
        var byAccount = useCase.searchEmployeesByUserName("gd", null, 20).block();

        // then
        assertThat(byName.items()).extracting("employeeId").containsExactly("gd.hong");
        assertThat(byAccount.items()).extracting("employeeId").containsExactly("gd.hong");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest*'`
Expected: 컴파일 실패 — `AdminQueryUseCase` 를 찾을 수 없음

- [ ] **Step 3: 유스케이스 작성**

`AdminQueryUseCase.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.query.AccessPath;
import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 관리자 조회. 현재상태(DynamoDB)에서 계층을 재구성하고 OpenFGA 의 실제 판정을 나란히 싣는다.
 *
 * <p><b>왜 둘을 나란히 두나.</b> 현재상태가 요구하는 튜플과 OpenFGA 에 실제로 있는 튜플이
 * 갈릴 수 있는데(follow-ups §6 의 미룬 동시성 결함), 지금 그 어긋남을 알아챌 다른 장치가 없다.
 * 이 화면이 유일한 신호다.
 *
 * <p><b>캐시를 두지 않는다.</b> 계층 깊이가 보통 4~6단이라 요청당 한 자릿수 왕복이면 끝난다.
 * 캐시를 두면 무효화가 새 문제가 되고, 두 앱이 각자 캐시하면 서로 다른 걸 보게 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminQueryUseCase {

    /** 한 직원의 경로 상한. 정상 조직도에서 넘을 일이 없다 — 넘으면 계층이 비정상이거나 버그다. */
    public static final int MAX_PATHS = 200;

    private static final int CHECK_CONCURRENCY = 8;

    private final DirectoryStateRepository state;
    private final DirectorySearchRepository search;
    private final RelationTupleChecker checker;

    public Mono<Page<UserSummary>> searchEmployeesByUserName(String prefix, String cursor, int limit) {
        return search.searchUsersByUserName(prefix, cursor, limit);
    }

    public Mono<Page<UserSummary>> searchEmployeesByDisplayName(String prefix, String cursor, int limit) {
        return search.searchUsersByDisplayName(prefix, cursor, limit);
    }

    public Mono<Page<GroupSummary>> searchOrganizations(String prefix, String cursor, int limit) {
        return search.searchGroupsByDisplayName(prefix, cursor, limit);
    }

    // ---------- 직원 상세 ----------

    public Mono<EmployeeDetail> employeeDetail(String employeeId) {
        return state.findUser(employeeId).flatMap(user ->
                directGroupsOf(employeeId)
                        .flatMap(this::climb)
                        .flatMap(reached -> toDetail(user, reached)));
    }

    /**
     * 롤업까지 한 번에 묻는 튜플. relation 이 {@code direct_member} 가 아니라 {@code member} 다 —
     * {@code member} 는 {@code direct_member or member from child} 로 정의돼 있어(선행 설계 §5)
     * 직속이든 상위든 한 번의 Check 로 답이 나온다. {@code RelationTuple} 에 이 팩토리가 없어
     * 생성자를 직접 쓴다.
     */
    private static RelationTuple memberOf(String employeeId, String orgCode) {
        return new RelationTuple("user:" + employeeId, "member", "group:" + orgCode);
    }

    /** 직원이 직접 멤버로 등록된 조직들. 레코드가 없는 참조는 건너뛴다. */
    private Mono<List<DirectoryGroup>> directGroupsOf(String employeeId) {
        return state.findGroupIdsContaining(MemberRef.user(employeeId))
                .flatMap(this::loadGroup, CHECK_CONCURRENCY)
                .collectList();
    }

    private Flux<DirectoryGroup> loadGroup(String groupId) {
        return state.findGroup(groupId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("조직 '{}' 이 멤버십에서 참조되지만 레코드가 없어 건너뛴다", groupId)))
                .flux();
    }

    /**
     * 직속 조직들에서 시작해 상위로 끝까지 올라간다.
     *
     * <p>방문 집합은 무한 루프 방지에 필수이고, 이미 본 조직에 다시 닿으면 그것이 곧 순환이다.
     * 그때 조용히 멈추지 않고 표시해 올린다 — 관리 도구에서 순환은 숨길 사실이 아니다.
     */
    private Mono<Reached> climb(List<DirectoryGroup> direct) {
        Reached reached = new Reached();
        for (DirectoryGroup group : direct) {
            if (reached.entries.size() >= MAX_PATHS) {
                reached.truncated = true;
                break;
            }
            reached.add(group, AccessPath.DIRECT, false);
        }
        return expand(reached.entries.stream().map(entry -> entry.group.id()).toList(), reached)
                .thenReturn(reached);
    }

    private Mono<Void> expand(List<String> frontier, Reached reached) {
        if (frontier.isEmpty() || reached.entries.size() >= MAX_PATHS) {
            return Mono.empty();
        }
        return Flux.fromIterable(frontier)
                .concatMap(childId -> state.findGroupIdsContaining(MemberRef.group(childId)))
                .flatMap(this::loadGroup, CHECK_CONCURRENCY)
                .collectList()
                .flatMap(parents -> {
                    List<String> next = new ArrayList<>();
                    for (DirectoryGroup parent : parents) {
                        if (reached.seen.contains(parent.id())) {
                            reached.markCycle(parent.id());
                            continue;
                        }
                        if (reached.entries.size() >= MAX_PATHS) {
                            reached.truncated = true;
                            break;
                        }
                        reached.add(parent, AccessPath.ROLLUP, false);
                        next.add(parent.id());
                    }
                    return expand(next, reached);
                });
    }

    private Mono<EmployeeDetail> toDetail(DirectoryUser user, Reached reached) {
        return Flux.fromIterable(reached.entries)
                .flatMap(entry -> checkOrNull(memberOf(user.id(), entry.group.id()))
                                .map(allowed -> new AccessPath(entry.group.id(), entry.group.displayName(),
                                        entry.via, user.active(), allowed, entry.cycle))
                                .defaultIfEmpty(new AccessPath(entry.group.id(), entry.group.displayName(),
                                        entry.via, user.active(), null, entry.cycle)),
                        CHECK_CONCURRENCY)
                .collectList()
                .map(paths -> new EmployeeDetail(user.id(), user.userName(), user.displayName(),
                        user.email(), user.active(), paths, reached.truncated));
    }

    // ---------- 조직 상세 ----------

    public Mono<OrganizationDetail> organizationDetail(String orgCode, int memberPageSize) {
        return state.findGroup(orgCode).flatMap(group ->
                Mono.zip(ancestorsOf(group), childrenOf(group),
                                membersPage(group, null, memberPageSize))
                        .map(parts -> new OrganizationDetail(
                                group.id(), group.displayName(), group.externalId(),
                                parts.getT1(), parts.getT2(), parts.getT3())));
    }

    public Mono<Page<OrgMember>> organizationMembers(String orgCode, String cursor, int limit) {
        return state.findGroup(orgCode).flatMap(group -> membersPage(group, cursor, limit));
    }

    private Mono<List<GroupSummary>> ancestorsOf(DirectoryGroup group) {
        Reached reached = new Reached();
        reached.seen.add(group.id());
        return expand(List.of(group.id()), reached)
                .thenReturn(reached.entries.stream()
                        .map(entry -> new GroupSummary(entry.group.id(), entry.group.displayName()))
                        .toList());
    }

    /**
     * 직속 하위 조직만(1 depth). 멤버 참조에는 조직코드밖에 없으므로 표시명을 채우려면
     * 각 하위 조직을 읽어야 한다 — 코드만 담아 돌려주면 관리 화면의 이름 칸이 비어버린다.
     * 하위 조직은 보통 수십 개라 이 정도 읽기는 감당된다.
     */
    private Mono<List<GroupSummary>> childrenOf(DirectoryGroup group) {
        return Flux.fromIterable(group.members())
                .filter(member -> member.type() == MemberType.GROUP)
                .map(MemberRef::id)
                .sort()
                .concatMap(this::loadGroup)
                .map(child -> new GroupSummary(child.id(), child.displayName()))
                .collectList();
    }

    private Mono<Page<OrgMember>> membersPage(DirectoryGroup group, String cursor, int limit) {
        List<String> userIds = group.members().stream()
                .filter(member -> member.type() == MemberType.USER)
                .map(MemberRef::id)
                .sorted()
                .toList();

        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(from + limit, userIds.size());
        String next = to < userIds.size() ? String.valueOf(to) : null;

        return Flux.fromIterable(userIds.subList(from, to))
                .concatMap(userId -> state.findUser(userId)
                        .flatMap(user -> checkOrNull(memberOf(user.id(), group.id()))
                                .map(allowed -> new OrgMember(user.id(), user.displayName(),
                                        user.active(), allowed))
                                .defaultIfEmpty(new OrgMember(user.id(), user.displayName(),
                                        user.active(), null))))
                .collectList()
                .map(items -> new Page<>(items, next));
    }

    // ---------- Check ----------

    /**
     * Check 를 부르되 실패는 빈 신호로 바꾼다. 호출자가 그것을 null 로 채운다.
     *
     * <p>반드시 <b>항목 단위</b>로 감싸야 한다. 스트림 전체에 {@code onErrorResume} 을 걸면
     * 첫 실패에서 나머지 항목이 통째로 사라진다.
     */
    private Mono<Boolean> checkOrNull(RelationTuple tuple) {
        return checker.check(tuple)
                .onErrorResume(error -> {
                    log.warn("Check 실패 — 판정을 보류한다. tuple={}", tuple, error);
                    return Mono.empty();
                });
    }

    // ---------- 순회 상태 ----------

    private static final class Reached {
        private final Set<String> seen = new LinkedHashSet<>();
        private final List<Entry> entries = new ArrayList<>();
        private boolean truncated;

        void add(DirectoryGroup group, String via, boolean cycle) {
            if (seen.add(group.id())) {
                entries.add(new Entry(group, via, cycle));
            }
        }

        void markCycle(String groupId) {
            entries.stream()
                    .filter(entry -> entry.group.id().equals(groupId))
                    .forEach(entry -> entry.cycle = true);
        }
    }

    private static final class Entry {
        private final DirectoryGroup group;
        private final String via;
        private boolean cycle;

        Entry(DirectoryGroup group, String via, boolean cycle) {
            this.group = group;
            this.via = via;
            this.cycle = cycle;
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :core:test --tests '*AdminQueryUseCaseTest*'`
Expected: PASS 13개

- [ ] **Step 5: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋과 푸시**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/AdminQueryUseCaseTest.java
git commit -m "feat: AdminQueryUseCase — 계층 순회와 OpenFGA Check 병기"
git push origin <현재 브랜치>
```

---

## Task 3: storage-dynamodb — GSI2 와 검색 구현

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (상수 4개 추가)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java` (GSI2 생성 + 기존 테이블 보강)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`saveUser` 에 GSI2 키 2줄)
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Cursor.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepositoryTest.java`, `CursorTest.java`

**Interfaces:**
- Consumes: Task 1 의 `DirectorySearchRepository`, `Page`, `UserSummary`, `GroupSummary`
- Produces: `DynamoDbDirectorySearchRepository(DynamoDbAsyncClient, DynamoDbProperties)` — `DirectorySearchRepository` 구현

**기존 테이블 보강이 필요하다.** `TableInitializer` 는 테이블이 없을 때만 만든다. 이미 뜬 적 있는 로컬 테이블에는 GSI2 가 없어서 표시명 검색이 `ValidationException` 으로 죽는다. 그래서 `describeTable` 결과에 GSI2 가 없으면 `UpdateTable` 로 추가한다. DynamoDB 는 **한 번에 하나의 GSI 만** 생성할 수 있고 백필 중에는 `CREATING` 상태이므로, 요청만 보내고 완료를 기다리지 않는다(백필 중에도 쓰기는 계속 된다).

**커서 인코딩.** `LastEvaluatedKey` 는 `Map<String, AttributeValue>` 이고, 이 스키마의 키 속성은 전부 문자열(S)이다. 각 이름·값을 base64 로 감싸 `|` 로 잇고 전체를 다시 base64 로 감싼다. Jackson 을 쓰지 않는 이유는 `storage-dynamodb` 에 Jackson 이 없기 때문이다(`spring-boot-starter` 는 Jackson 을 가져오지 않는다).

- [ ] **Step 1: 커서 테스트 작성**

`CursorTest.java`:

```java
package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorTest {

    @Test
    @DisplayName("키를 감쌌다 풀면 원래 값이 그대로 나온다")
    void 왕복하면_원래_값이다() {
        // given
        Map<String, AttributeValue> key = Map.of(
                Keys.PK, Attrs.s("USER#gd.hong"),
                Keys.SK, Attrs.s("META"),
                Keys.GSI1PK, Attrs.s("USER_INDEX"),
                Keys.GSI1SK, Attrs.s("gd.hong"));

        // when
        Map<String, AttributeValue> restored = Cursor.decode(Cursor.encode(key));

        // then
        assertThat(restored).isEqualTo(key);
    }

    @Test
    @DisplayName("구분자나 한글이 값에 들어 있어도 왕복한다")
    void 특수문자와_한글도_왕복한다() {
        // given — 값에 구분자가 그대로 들어 있어도 base64 로 감싸므로 깨지지 않는다
        Map<String, AttributeValue> key = Map.of(
                Keys.PK, Attrs.s("USER#a|b:c"),
                Keys.GSI1SK, Attrs.s("홍길동"));

        // when, then
        assertThat(Cursor.decode(Cursor.encode(key))).isEqualTo(key);
    }

    @Test
    @DisplayName("null 키는 null 커서가 된다 — 마지막 페이지")
    void null_키는_null_커서다() {
        // when, then
        assertThat(Cursor.encode(null)).isNull();
        assertThat(Cursor.encode(Map.of())).isNull();
    }

    @Test
    @DisplayName("손상된 커서는 IllegalArgumentException 이다")
    void 손상된_커서는_거절한다() {
        // when, then — 호출부가 이걸 400 으로 옮긴다
        assertThatThrownBy(() -> Cursor.decode("!!not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :storage-dynamodb:test --tests '*CursorTest*'`
Expected: 컴파일 실패 — `Cursor` 없음

- [ ] **Step 3: `Cursor` 작성**

```java
package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DynamoDB 의 {@code LastEvaluatedKey} 를 불투명 문자열로 감싼다.
 *
 * <p>이 스키마의 키 속성은 전부 문자열(S)이므로 값만 뽑아 base64 로 감싼다.
 * 이름과 값을 각각 감싸는 이유는, 값에 구분자가 들어 있어도 파싱이 깨지지 않게 하기 위해서다.
 *
 * <p>커서에 원본 키가 담기지만 이 API 는 인증이 없어 어차피 데이터가 열려 있다.
 * 암호화는 인증 사이클에서 함께 다룬다.
 */
final class Cursor {

    private static final String PAIR_SEPARATOR = "|";
    private static final String KEY_VALUE_SEPARATOR = ":";

    private Cursor() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String joined = lastEvaluatedKey.entrySet().stream()
                .map(entry -> b64(entry.getKey()) + KEY_VALUE_SEPARATOR + b64(entry.getValue().s()))
                .collect(Collectors.joining(PAIR_SEPARATOR));
        return b64(joined);
    }

    static Map<String, AttributeValue> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String joined = unb64(cursor);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (String pair : joined.split("\\" + PAIR_SEPARATOR)) {
                String[] parts = pair.split(KEY_VALUE_SEPARATOR, 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("커서 형식이 올바르지 않다");
                }
                key.put(unb64(parts[0]), Attrs.s(unb64(parts[1])));
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("커서를 해석할 수 없다", e);
        }
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
```

`Cursor` 는 패키지 전용(`final class`, `package-private`)이다 — 커서 형식은 저장소 구현의 비밀이고 밖에서는 불투명 문자열이어야 한다.

- [ ] **Step 4: `Keys` 에 상수 추가**

`Keys.java` 의 GSI1 상수 아래에 더한다:

```java
    public static final String GSI2PK = "GSI2PK";
    public static final String GSI2SK = "GSI2SK";
    public static final String GSI2 = "GSI2";

    /** 직원 표시명 검색용 GSI2 파티션 */
    public static final String USER_DISPLAY_NAME_INDEX = "USER_DISPLAY_NAME_INDEX";
```

- [ ] **Step 5: `saveUser` 가 GSI2 키를 쓰게 한다**

`DynamoDbDirectoryStateRepository.saveUser` 의 `GSI1SK` 줄 아래에 더한다:

```java
        // 표시명이 없는 직원은 GSI2 에 실리지 않는다 — DynamoDB 는 정렬키 속성이 없는
        // 아이템을 인덱스에 넣지 않는다. 의도한 동작이며, 아이디·계정명으로는 여전히 찾힌다.
        if (user.displayName() != null && !user.displayName().isBlank()) {
            item.put(Keys.GSI2PK, Attrs.s(Keys.USER_DISPLAY_NAME_INDEX));
            item.put(Keys.GSI2SK, Attrs.s(user.displayName()));
        }
```

- [ ] **Step 6: `TableInitializer` 에 GSI2 생성과 보강 추가**

`createTable` 의 `attributeDefinitions` 에 `attribute(Keys.GSI2PK), attribute(Keys.GSI2SK)` 를 더하고, `globalSecondaryIndexes` 를 둘로 늘린다:

```java
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName(Keys.GSI1)
                                .keySchema(
                                        KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName(Keys.GSI1SK).keyType(KeyType.RANGE).build())
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        userDisplayNameIndex())
```

```java
    /**
     * 직원 표시명 접두사 검색용 인덱스.
     *
     * <p>프로젝션이 {@code ALL} 이 아니라 {@code INCLUDE} 인 이유: 검색 결과 한 줄을 그리는 데
     * 필요한 속성만 담으면 된다. {@code KEYS_ONLY} 로 더 줄이면 결과 20건마다 GetItem 20번이
     * 붙어 오히려 손해다.
     */
    private static GlobalSecondaryIndex userDisplayNameIndex() {
        return GlobalSecondaryIndex.builder()
                .indexName(Keys.GSI2)
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.GSI2PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.GSI2SK).keyType(KeyType.RANGE).build())
                .projection(Projection.builder()
                        .projectionType(ProjectionType.INCLUDE)
                        .nonKeyAttributes("userName", "displayName", "active")
                        .build())
                .build();
    }
```

그리고 `afterPropertiesSet` 의 존재 확인 경로에 보강을 붙인다. 기존 `.doOnNext(response -> log.info(...))` 를 아래로 교체한다:

```java
                .flatMap(response -> {
                    log.info("DynamoDB 테이블 '{}' 이 이미 존재한다", table);
                    return addMissingIndex(table, response);
                })
```

```java
    /**
     * 이미 있는 테이블에 GSI2 가 없으면 더한다. 기존 배포에서 표시명 검색이
     * ValidationException 으로 죽는 것을 막는다.
     *
     * <p>완료를 기다리지 않는다 — 백필 중에도 테이블 쓰기는 계속되고, 검색만 잠시 비어 보인다.
     * DynamoDB 는 한 번에 하나의 GSI 만 만들 수 있으므로 이미 만드는 중이면 그대로 둔다.
     */
    private Mono<Void> addMissingIndex(String table, DescribeTableResponse response) {
        boolean present = response.table().globalSecondaryIndexes() != null
                && response.table().globalSecondaryIndexes().stream()
                        .anyMatch(index -> Keys.GSI2.equals(index.indexName()));
        if (present) {
            return Mono.empty();
        }
        log.info("DynamoDB 테이블 '{}' 에 인덱스 '{}' 를 추가한다", table, Keys.GSI2);
        GlobalSecondaryIndex index = userDisplayNameIndex();
        UpdateTableRequest request = UpdateTableRequest.builder()
                .tableName(table)
                .attributeDefinitions(attribute(Keys.GSI2PK), attribute(Keys.GSI2SK))
                .globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
                        .create(CreateGlobalSecondaryIndexAction.builder()
                                .indexName(index.indexName())
                                .keySchema(index.keySchema())
                                .projection(index.projection())
                                .build())
                        .build())
                .build();
        return Mono.fromFuture(() -> client.updateTable(request))
                .doOnError(error -> log.warn("인덱스 '{}' 추가 실패 — 표시명 검색이 동작하지 않는다", Keys.GSI2, error))
                .onErrorResume(error -> Mono.empty())
                .then();
    }
```

임포트를 더한다: `DescribeTableResponse`, `UpdateTableRequest`, `GlobalSecondaryIndexUpdate`, `CreateGlobalSecondaryIndexAction`.

- [ ] **Step 7: 검색 구현 테스트 작성**

`DynamoDbDirectorySearchRepositoryTest.java`. 기존 `storage-dynamodb` 테스트의 Testcontainers 셋업(`amazon/dynamodb-local`)을 그대로 따른다 — 같은 디렉터리의 기존 테스트에서 컨테이너 기동·클라이언트 생성·`TableInitializer` 호출 방식을 복사한다.

```java
    @Test
    @DisplayName("표시명 접두사로 직원을 찾는다")
    void 표시명_접두사로_찾는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "gd.hong", "홍길동", "a@b.c", true)).block();
        state.saveUser(new DirectoryUser("cs.kim", "e2", "cs.kim", "김철수", "b@b.c", true)).block();

        // when
        var page = search.searchUsersByDisplayName("홍", null, 20).block();

        // then
        assertThat(page.items()).extracting("employeeId").containsExactly("gd.hong");
        assertThat(page.items()).extracting("displayName").containsExactly("홍길동");
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("표시명이 없는 직원은 표시명 검색에 잡히지 않는다")
    void 표시명이_없으면_인덱스에_없다() {
        // given
        state.saveUser(new DirectoryUser("noname", "e3", "noname", null, null, true)).block();

        // when — 어떤 접두사로도 안 잡힌다
        var page = search.searchUsersByDisplayName("n", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("계정명 접두사로 직원을 찾는다")
    void 계정명_접두사로_찾는다() {
        // given
        state.saveUser(new DirectoryUser("gd.hong", "e1", "gd.hong", "홍길동", null, true)).block();

        // when
        var page = search.searchUsersByUserName("gd", null, 20).block();

        // then
        assertThat(page.items()).extracting("employeeId").containsExactly("gd.hong");
    }

    @Test
    @DisplayName("조직명 접두사로 조직을 찾는다")
    void 조직명_접두사로_찾는다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV001", "x", "플랫폼개발본부", Set.of())).block();
        state.saveGroup(new DirectoryGroup("OPS001", "y", "인프라본부", Set.of())).block();

        // when
        var page = search.searchGroupsByDisplayName("플랫폼", null, 20).block();

        // then
        assertThat(page.items()).extracting("orgCode").containsExactly("DEV001");
    }

    @Test
    @DisplayName("커서로 다음 페이지를 이어 읽으면 중복도 누락도 없다")
    void 커서로_이어_읽는다() {
        // given — 같은 접두사를 가진 직원 5명
        for (int i = 1; i <= 5; i++) {
            state.saveUser(new DirectoryUser("u" + i, "e" + i, "u" + i, "가나다" + i, null, true)).block();
        }

        // when — 2건씩 끝까지 읽는다
        List<String> collected = new ArrayList<>();
        String cursor = null;
        do {
            var page = search.searchUsersByDisplayName("가나다", cursor, 2).block();
            page.items().forEach(item -> collected.add(item.employeeId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // then
        assertThat(collected).containsExactlyInAnyOrder("u1", "u2", "u3", "u4", "u5");
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("결과가 없으면 빈 페이지이고 커서도 없다")
    void 결과가_없으면_빈_페이지다() {
        // when
        var page = search.searchUsersByDisplayName("없는이름", null, 20).block();

        // then
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }
```

- [ ] **Step 8: 테스트 실패 확인**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectorySearchRepositoryTest*'`
Expected: 컴파일 실패 — `DynamoDbDirectorySearchRepository` 없음

- [ ] **Step 9: 검색 구현 작성**

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GSI 파티션 안에서 정렬키 접두사로 훑는다.
 *
 * <p>DynamoDB 정렬키로 할 수 있는 것은 정확 일치와 {@code begins_with} 뿐이다.
 * 부분일치는 Scan 이거나 검색엔진이므로 이 계획의 범위 밖이다(설계 §12).
 */
@RequiredArgsConstructor
public class DynamoDbDirectorySearchRepository implements DirectorySearchRepository {

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public Mono<Page<UserSummary>> searchUsersByUserName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.USER_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<UserSummary>> searchUsersByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI2, Keys.GSI2PK, Keys.GSI2SK, Keys.USER_DISPLAY_NAME_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toUserSummary);
    }

    @Override
    public Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit) {
        return query(Keys.GSI1, Keys.GSI1PK, Keys.GSI1SK, Keys.GROUP_INDEX,
                prefix, cursor, limit, DynamoDbDirectorySearchRepository::toGroupSummary);
    }

    private <T> Mono<Page<T>> query(String indexName, String pkName, String skName, String partition,
                                    String prefix, String cursor, int limit,
                                    Function<Map<String, AttributeValue>, T> mapper) {
        QueryRequest.Builder request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(indexName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", pkName, "#sk", skName))
                .expressionAttributeValues(Map.of(
                        ":pk", Attrs.s(partition), ":prefix", Attrs.s(prefix)))
                .limit(limit);

        Map<String, AttributeValue> start = Cursor.decode(cursor);
        if (start != null) {
            request.exclusiveStartKey(start);
        }

        return Mono.fromFuture(() -> client.query(request.build()))
                .map(response -> toPage(response, mapper));
    }

    private <T> Page<T> toPage(QueryResponse response, Function<Map<String, AttributeValue>, T> mapper) {
        List<T> items = response.items().stream().map(mapper).toList();
        return new Page<>(items, Cursor.encode(response.lastEvaluatedKey()));
    }

    private static UserSummary toUserSummary(Map<String, AttributeValue> item) {
        return new UserSummary(
                Keys.parseUserPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "userName"),
                Attrs.str(item, "displayName"),
                Attrs.flag(item, "active"));
    }

    private static GroupSummary toGroupSummary(Map<String, AttributeValue> item) {
        return new GroupSummary(
                Keys.parseGroupPk(Attrs.str(item, Keys.PK)),
                Attrs.str(item, "displayName"));
    }
}
```

**`limit` 을 그대로 넘기는 것에 주의.** DynamoDB 의 `limit` 은 "필터 적용 전에 읽을 아이템 수" 인데, 여기는 필터 없이 키 조건만 쓰므로 반환 개수와 같다. 필터를 나중에 더하면 이 가정이 깨진다.

- [ ] **Step 10: 빈 등록**

`storage-dynamodb` 의 기존 `@Configuration` 클래스(`DynamoDbConfig` 등, 같은 패키지에서 확인)에 빈을 더한다:

```java
    @Bean
    public DirectorySearchRepository directorySearchRepository(DynamoDbAsyncClient client,
                                                               DynamoDbProperties properties) {
        return new DynamoDbDirectorySearchRepository(client, properties);
    }
```

- [ ] **Step 11: 테스트 통과 확인**

Run: `./gradlew :storage-dynamodb:test`
Expected: 새 테스트 6개 + `CursorTest` 4개 통과, 기존 테스트 전부 통과

- [ ] **Step 12: 전체 빌드와 커밋**

```bash
./gradlew build
git add storage-dynamodb
git commit -m "feat: GSI2 와 접두사 검색 구현 — 표시명 인덱스와 커서 페이징"
git push origin <현재 브랜치>
```

---

## Task 4: authz-openfga — RelationTupleChecker 구현

**Files:**
- Create: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleChecker.java`
- Modify: `authz-openfga` 의 기존 `@Configuration` 클래스 (빈 등록)
- Test: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaRelationTupleCheckerTest.java`

**Interfaces:**
- Consumes: Task 1 의 `RelationTupleChecker`, 기존 `StoreBootstrapper`
- Produces: `OpenFgaRelationTupleChecker(StoreBootstrapper)` — `RelationTupleChecker` 구현

**`resolveStore()` 가 아니라 `findExistingStore()` 를 쓴다.** `resolveStore()` 는 store 가 없으면 만들고 인가 모델을 쓴다. 조회 경로가 인프라를 프로비저닝하면 안 된다 — 헬스 인디케이터에서 같은 이유로 고쳤던 문제다. store 가 없으면 Check 가 성립할 수 없으므로 에러로 끝내고, 호출자가 그것을 `null` 로 옮긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

기존 `authz-openfga` 테스트의 Testcontainers 셋업(`openfga/openfga:v1.10.2`)을 그대로 따른다.

```java
    @Test
    @DisplayName("쓴 튜플은 Check 가 true 로 답한다")
    void 쓴_튜플은_true다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("하위 조직을 통한 롤업도 한 번의 Check 로 true 가 된다")
    void 롤업도_한_번의_Check로_답한다() {
        // given — DEV001 ⊇ DEV002 ⊇ kim
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV001")).block();

        // then — member 는 direct_member or member from child 로 정의돼 있다
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("없는 튜플은 false 다")
    void 없는_튜플은_false다() {
        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:nobody", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("상속은 상위로만 향한다 — 상위 직속은 하위의 멤버가 아니다")
    void 상속은_상위로만_향한다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("park", "DEV001"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:park", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :authz-openfga:test --tests '*OpenFgaRelationTupleCheckerTest*'`
Expected: 컴파일 실패

- [ ] **Step 3: 구현 작성**

```java
package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 인가 판정을 묻는다. 열거 API 는 쓰지 않는다 — {@code Check} 는 점 조회다.
 *
 * <p><b>{@code findExistingStore()} 를 쓴다.</b> {@code resolveStore()} 는 store 가 없으면
 * 만들고 인가 모델을 쓴다. 조회 경로가 인프라를 프로비저닝하면 안 된다. store 가 없으면
 * Check 가 성립할 수 없으므로 에러로 끝내고, 호출자가 그것을 "판정 보류" 로 옮긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaRelationTupleChecker implements RelationTupleChecker {

    private final StoreBootstrapper bootstrapper;

    @Override
    public Mono<Boolean> check(RelationTuple tuple) {
        return bootstrapper.findExistingStore()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "OpenFGA store 가 아직 없어 Check 를 할 수 없다")))
                .flatMap(storeId -> Mono.fromFuture(() -> bootstrapper.client()
                                .check(new ClientCheckRequest()
                                        .user(tuple.user())
                                        .relation(tuple.relation())
                                        ._object(tuple.object())))
                        .map(response -> Boolean.TRUE.equals(response.getAllowed())));
    }
}
```

`bootstrapper.client().check(...)` 가 `CompletableFuture` 를 돌려주지 않고 예외를 던지는 형태라면 `Mono.fromCallable` 로 감싼 뒤 `flatMap(Mono::fromFuture)` 로 잇는다 — `OpenFgaRelationTupleWriter` 가 SDK 를 감싸는 방식을 그대로 따를 것.

- [ ] **Step 4: 빈 등록**

`authz-openfga` 의 기존 `@Configuration` 에 더한다:

```java
    @Bean
    public RelationTupleChecker relationTupleChecker(StoreBootstrapper bootstrapper) {
        return new OpenFgaRelationTupleChecker(bootstrapper);
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :authz-openfga:test`
Expected: 새 테스트 4개 + 기존 테스트 전부 통과

- [ ] **Step 6: 전체 빌드와 커밋**

```bash
./gradlew build
git add authz-openfga
git commit -m "feat: RelationTupleChecker 구현 — 조회 경로는 store 를 만들지 않는다"
git push origin <현재 브랜치>
```

---

## Task 5: admin-api 모듈

**Files:**
- Modify: `settings.gradle` (`include 'admin-api'`)
- Create: `admin-api/build.gradle`
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminQueryController.java`
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminQueryConfig.java`
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminQueryMetrics.java`
- Create: `admin-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `admin-api/src/test/java/dev/starryeye/organization/admin/AdminQueryControllerTest.java`

**Interfaces:**
- Consumes: Task 2 의 `AdminQueryUseCase`, Task 1 의 조회 타입
- Produces: 6개 엔드포인트. `AdminQueryConfig` 가 `AdminQueryUseCase`·컨트롤러·메트릭 빈을 등록한다

**`@RestController` 로 간다.** `connector-scim` 은 함수형 라우팅이지만 그건 SCIM 의 단일 지점 에러 번역 요구 때문이었다. 여기는 그 요구가 없고, 이미 있는 `AdminSyncController` 와 같은 표면이어야 관리 API 가 한 덩어리로 읽힌다.

**`admin-api/build.gradle`:**

```groovy
dependencies {
    api project(':core')
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'io.micrometer:micrometer-core'

    testImplementation testFixtures(project(':core'))
}
```

`AutoConfiguration.imports` 내용은 한 줄: `dev.starryeye.organization.admin.AdminQueryConfig`

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminQueryControllerTest.java` — `WebTestClient.bindToController(...)` 로 컨트롤러만 띄우고 페이크 포트를 물린다. 메트릭은 `SimpleMeterRegistry` 를 직접 만들어 `AdminQueryMetrics` 에 넣고, 테스트에서 `registry.counter(...)` 로 값을 읽는다.

```java
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // ... setUp 에서
    var useCase = new AdminQueryUseCase(state, search, checker);
    var metrics = new AdminQueryMetrics(registry);
    client = WebTestClient.bindToController(new AdminQueryController(useCase, metrics)).build();
```

```java
    @Test
    @DisplayName("표시명으로 직원을 검색한다")
    void 표시명으로_검색한다() {
        // given
        search.users.add(new UserSummary("gd.hong", "gd.hong", "홍길동", true));

        // when, then
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].employeeId").isEqualTo("gd.hong")
                .jsonPath("$.items[0].displayName").isEqualTo("홍길동")
                .jsonPath("$.nextCursor").doesNotExist();
    }

    @Test
    @DisplayName("검색 파라미터가 없으면 400 이다")
    void 검색_파라미터가_없으면_400이다() {
        // when, then — 빈 접두사는 전체 열거가 되므로 입구에서 막는다
        client.get().uri("/admin/employees")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("검색 파라미터를 둘 다 주면 400 이다")
    void 파라미터가_둘이면_400이다() {
        // when, then — 어느 인덱스를 탈지 모호하다
        client.get().uri("/admin/employees?userName=gd&displayName=홍")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("limit 이 범위를 벗어나면 400 이다")
    void limit_범위를_벗어나면_400이다() {
        // when, then
        client.get().uri("/admin/employees?displayName=홍&limit=0")
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/admin/employees?displayName=홍&limit=101")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("손상된 커서는 400 이다")
    void 손상된_커서는_400이다() {
        // given — 저장소가 IllegalArgumentException 을 던지는 상황
        search.failWith = new IllegalArgumentException("커서를 해석할 수 없다");

        // when, then
        client.get().uri("/admin/employees?displayName=홍&cursor=!!broken!!")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("없는 직원은 404 다")
    void 없는_직원은_404다() {
        // when, then
        client.get().uri("/admin/employees/nobody")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("직원 상세는 경로와 Check 결과를 함께 준다")
    void 직원_상세는_경로와_Check를_준다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.allowed.add(new RelationTuple("user:kim", "member", "group:DEV002"));

        // when, then
        client.get().uri("/admin/employees/kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].orgCode").isEqualTo("DEV002")
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @DisplayName("Check 가 실패해도 200 이고 그 칸만 null 이다")
    void Check_실패해도_200이다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failWhen = tuple -> true;

        // when, then — 조회 API 가 인가 서버 장애에 끌려 내려가면 안 된다
        client.get().uri("/admin/employees/kim")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].openFgaCheck").isEmpty();
    }

    @Test
    @DisplayName("어긋남을 만나면 드리프트 카운터가 올라간다")
    void 드리프트_카운터가_올라간다() {
        // given — 상태는 소속을 말하는데 OpenFGA 에는 튜플이 없다
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — follow-ups §6 에서 감지 장치를 안 두기로 했으므로 이게 유일한 신호다
        assertThat(registry.counter("authz_drift_detected").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Check 를 못 한 것은 드리프트가 아니라 보류로 센다")
    void 보류는_드리프트로_안_센다() {
        // given
        state.saveUser(new DirectoryUser("kim", "e1", "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failWhen = tuple -> true;

        // when
        client.get().uri("/admin/employees/kim").exchange().expectStatus().isOk();

        // then — 모른다는 것과 어긋났다는 것은 다르다
        assertThat(registry.counter("authz_drift_detected").count()).isZero();
        assertThat(registry.counter("authz_check_failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("없는 조직은 404 다")
    void 없는_조직은_404다() {
        // when, then
        client.get().uri("/admin/organizations/NOPE")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("조직 상세는 상위 계층과 직속 하위를 준다")
    void 조직_상세를_준다() {
        // given
        state.saveGroup(new DirectoryGroup("DEV002", "x", "백엔드팀", Set.of())).block();
        state.saveGroup(new DirectoryGroup("DEV001", "y", "플랫폼개발본부",
                Set.of(MemberRef.group("DEV002")))).block();

        // when, then
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orgCode").isEqualTo("DEV002")
                .jsonPath("$.ancestors[0].orgCode").isEqualTo("DEV001");
    }
```

`FakeSearchRepository` 에 `failWith` 필드를 더한다(Task 1 의 페이크를 수정):

```java
    /** 설정되면 모든 검색이 이 예외로 실패한다. 커서 손상 같은 경우를 흉내낸다. */
    public RuntimeException failWith;
```
각 `search*` 메서드 첫 줄에 `if (failWith != null) return Mono.error(failWith);` 를 더한다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :admin-api:test`
Expected: 모듈이 없어 실패 → `settings.gradle` 과 `build.gradle` 부터 만든다

- [ ] **Step 3: 컨트롤러 작성**

```java
package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.GroupSummary;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import dev.starryeye.organization.core.query.UserSummary;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 관리자 조회. 기존 {@code /admin/sync} 와 같은 표면이 되도록 {@code @RestController} 와
 * {@link ResponseStatusException} 을 쓴다.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminQueryController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AdminQueryUseCase useCase;
    private final AdminQueryMetrics metrics;

    @GetMapping("/employees")
    public Mono<Page<UserSummary>> searchEmployees(
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int size = validLimit(limit);
        if (present(userName) == present(displayName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userName 과 displayName 중 정확히 하나를 지정해야 한다");
        }
        Mono<Page<UserSummary>> result = present(userName)
                ? useCase.searchEmployeesByUserName(userName, cursor, size)
                : useCase.searchEmployeesByDisplayName(displayName, cursor, size);
        return result.onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    @GetMapping("/employees/{employeeId}")
    public Mono<EmployeeDetail> employee(@PathVariable String employeeId) {
        return useCase.employeeDetail(employeeId)
                .doOnNext(metrics::recordEmployeeDetail)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "직원을 찾을 수 없다: " + employeeId)));
    }

    @GetMapping("/organizations")
    public Mono<Page<GroupSummary>> searchOrganizations(
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        int size = validLimit(limit);
        if (!present(displayName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName 이 필요하다");
        }
        return useCase.searchOrganizations(displayName, cursor, size)
                .onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    @GetMapping("/organizations/{orgCode}")
    public Mono<OrganizationDetail> organization(@PathVariable String orgCode) {
        return useCase.organizationDetail(orgCode, DEFAULT_LIMIT)
                .doOnNext(metrics::recordOrganizationDetail)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조직을 찾을 수 없다: " + orgCode)));
    }

    @GetMapping("/organizations/{orgCode}/members")
    public Mono<Page<OrgMember>> members(@PathVariable String orgCode,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int size = validLimit(limit);
        return useCase.organizationMembers(orgCode, cursor, size)
                .doOnNext(metrics::recordMembers)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조직을 찾을 수 없다: " + orgCode)))
                .onErrorMap(IllegalArgumentException.class, this::badRequest);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private int validLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 한다: " + limit);
        }
        return limit;
    }

    private ResponseStatusException badRequest(IllegalArgumentException e) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
}
```

**`present(userName) == present(displayName)` 한 줄이 두 경우를 다 잡는다** — 둘 다 없거나 둘 다 있으면 400 이다.

- [ ] **Step 4: 메트릭 작성**

```java
package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.query.AccessPath;
import dev.starryeye.organization.core.query.EmployeeDetail;
import dev.starryeye.organization.core.query.OrgMember;
import dev.starryeye.organization.core.query.OrganizationDetail;
import dev.starryeye.organization.core.query.Page;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 조회하면서 부수적으로 드리프트를 센다.
 *
 * <p>follow-ups §6 에서 별도 감지 장치를 두지 않기로 했으므로, 이 카운터가 조회한 범위에
 * 한해서나마 유일한 신호다. 0 이 아니면 수동 재적재를 실행할 근거가 된다.
 *
 * <p>Check 를 못 한 항목은 세지 않는다 — 모른다는 것과 어긋났다는 것은 다르다.
 */
public class AdminQueryMetrics {

    private final Counter drift;
    private final Counter checkFailed;

    public AdminQueryMetrics(MeterRegistry registry) {
        this.drift = Counter.builder("authz_drift_detected")
                .description("현재상태가 요구하는 권한과 OpenFGA 판정이 갈린 건수")
                .register(registry);
        this.checkFailed = Counter.builder("authz_check_failed")
                .description("Check 호출이 실패해 판정을 보류한 건수")
                .register(registry);
    }

    public void recordEmployeeDetail(EmployeeDetail detail) {
        detail.paths().forEach(this::record);
    }

    public void recordOrganizationDetail(OrganizationDetail detail) {
        recordMembers(detail.members());
    }

    public void recordMembers(Page<OrgMember> page) {
        page.items().forEach(member -> {
            if (member.openFgaCheck() == null) {
                checkFailed.increment();
            } else if (member.active() != member.openFgaCheck()) {
                drift.increment();
            }
        });
    }

    private void record(AccessPath path) {
        if (path.openFgaCheck() == null) {
            checkFailed.increment();
        } else if (path.drifted()) {
            drift.increment();
        }
    }
}
```

- [ ] **Step 5: 설정 작성**

```java
package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.port.DirectorySearchRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.usecase.AdminQueryUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminQueryConfig {

    @Bean
    public AdminQueryUseCase adminQueryUseCase(DirectoryStateRepository state,
                                               DirectorySearchRepository search,
                                               RelationTupleChecker checker) {
        return new AdminQueryUseCase(state, search, checker);
    }

    @Bean
    public AdminQueryMetrics adminQueryMetrics(MeterRegistry registry) {
        return new AdminQueryMetrics(registry);
    }

    @Bean
    public AdminQueryController adminQueryController(AdminQueryUseCase useCase,
                                                     AdminQueryMetrics metrics) {
        return new AdminQueryController(useCase, metrics);
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :admin-api:test`
Expected: 테스트 10개 통과

- [ ] **Step 7: 전체 빌드와 커밋**

```bash
./gradlew build
git add settings.gradle admin-api core/src/testFixtures
git commit -m "feat: admin-api 모듈 — 조회 엔드포인트 6종과 드리프트 카운터"
git push origin <현재 브랜치>
```

---

## Task 6: 두 앱 배선, E2E, 문서

**Files:**
- Modify: `app-scim/build.gradle`, `app-ldap/build.gradle` (`implementation project(':admin-api')`)
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/AdminQueryEndToEndTest.java`
- Create: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/AdminQuerySmokeTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 5 의 `admin-api` 모듈 전체
- Produces: 두 앱에서 동작하는 조회 API

**E2E 는 app-scim 에만 둔다.** `admin-api` 가 두 앱에서 같은 코드를 타고 app-scim 에 이미 컨테이너 E2E 인프라가 있다. app-ldap 에서 확인할 유일한 것은 공유 모듈이 자동설정으로 잡히는지다.

- [ ] **Step 1: 두 앱에 의존 추가**

두 `build.gradle` 의 `dependencies` 에 각각 한 줄:

```groovy
    implementation project(':admin-api')
```

app-scim 에는 `micrometer-registry-prometheus` 가 없다면 함께 더한다(app-ldap 에는 이미 있다). `MeterRegistry` 빈이 없으면 `AdminQueryMetrics` 가 뜨지 않는다 — 다만 actuator 가 있으면 `SimpleMeterRegistry` 가 자동 구성되므로 대개 문제되지 않는다. **`./gradlew :app-scim:test` 로 확인할 것.**

- [ ] **Step 2: app-ldap 스모크 테스트**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("app-ldap 에서도 admin-api 자동설정이 잡힌다")
class AdminQuerySmokeTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("조회 컨트롤러와 유스케이스 빈이 등록된다")
    void 빈이_등록된다() {
        // when, then — 공유 모듈이 두 앱 모두에서 잡히는지가 여기서 확인할 전부다
        assertThat(context.getBean(AdminQueryController.class)).isNotNull();
        assertThat(context.getBean(AdminQueryUseCase.class)).isNotNull();
    }
}
```

app-ldap 의 기존 테스트가 쓰는 프로파일·컨테이너 설정을 그대로 따른다. 컨테이너를 띄우지 않고 뜨지 않는다면 기존 E2E 와 같은 셋업을 재사용한다.

- [ ] **Step 3: app-scim E2E 작성**

`ScimEndToEndTest` 의 컨테이너 셋업을 그대로 복사하고, SCIM 으로 데이터를 만든 뒤 조회 API 로 확인한다.

```java
    @Test
    @Order(1)
    @DisplayName("SCIM 으로 만든 직원을 표시명으로 검색하고 상세에서 경로를 본다")
    void 검색하고_상세를_본다() {
        // given — SCIM 으로 직원과 조직을 만든다
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"gd.hong","displayName":"홍길동","active":true}""")
                .exchange().expectStatus().isCreated();
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV002","displayName":"백엔드팀",
                         "members":[{"value":"gd.hong"}]}""")
                .exchange().expectStatus().isCreated();

        // when, then — 표시명 접두사 검색
        client.get().uri("/admin/employees?displayName=홍")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].employeeId").isEqualTo("gd.hong");

        // then — 상세에서 파생값과 실제 판정이 모두 true 로 일치한다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("홍길동")
                .jsonPath("$.paths[0].orgCode").isEqualTo("DEV002")
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(true);
    }

    @Test
    @Order(2)
    @DisplayName("OpenFGA 에서 튜플을 직접 지우면 조회가 어긋남을 드러낸다")
    void 드리프트를_드러낸다() throws Exception {
        // given — 이 API 의 존재 이유다. 튜플만 직접 지워 상태와 어긋나게 만든다
        bootstrapper.client().deleteTuples(List.of(
                new ClientTupleKeyWithoutCondition()
                        .user("user:gd.hong").relation("direct_member")._object("group:DEV002"))).get();

        // when, then — 상태는 그대로이므로 파생값은 true 인데 실제 판정은 false 다
        client.get().uri("/admin/employees/gd.hong")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths[0].shouldHaveAccess").isEqualTo(true)
                .jsonPath("$.paths[0].openFgaCheck").isEqualTo(false);
    }

    @Test
    @Order(3)
    @DisplayName("조직 상세가 상위 계층과 직속 소속을 준다")
    void 조직_상세를_준다() {
        // given — 상위 조직을 만들어 계층을 만든다
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"DEV001","displayName":"플랫폼개발본부",
                         "members":[{"value":"DEV002","type":"Group"}]}""")
                .exchange().expectStatus().isCreated();

        // when, then
        client.get().uri("/admin/organizations/DEV002")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("백엔드팀")
                .jsonPath("$.ancestors[0].orgCode").isEqualTo("DEV001")
                .jsonPath("$.members.items[0].employeeId").isEqualTo("gd.hong");
    }

    @Test
    @Order(4)
    @DisplayName("검색 파라미터 없이 부르면 400 이다")
    void 파라미터가_없으면_400이다() {
        // when, then
        client.get().uri("/admin/employees").exchange().expectStatus().isBadRequest();
    }
```

`deleteTuples` 의 정확한 SDK 시그니처는 `OpenFgaRelationTupleWriter` 의 삭제 경로를 참고할 것.

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :app-scim:test :app-ldap:test`
Expected: 새 E2E 4개 + 스모크 1개 통과, 기존 테스트 전부 통과. Docker 가 필요하다.

**Docker 컨테이너를 직접 정지·삭제하지 말 것.** 호스트의 8000·8080·1389 포트는 다른 프로젝트가 쓰고 있고, Testcontainers 는 임의 포트를 잡는다.

- [ ] **Step 5: README 갱신**

"관리 API" 절에 조회 엔드포인트를 더한다. 담을 내용:

- 6개 엔드포인트와 각 파라미터
- **식별자 셋의 뜻** — `employeeId`(정규화, 튜플에 들어감) / `userName`(원본 계정명) / `displayName`(사람 이름). 홍길동/gd.hong 예시
- 접두사만 지원하고 부분일치는 안 된다는 것
- `shouldHaveAccess` 와 `openFgaCheck` 가 갈리면 어긋난 것이고, 대응은 `/admin/sync/rebuild` 라는 것
- **인증이 없다는 것** — `/admin/sync` 와 마찬가지로 열려 있으니 실제 운영 전에 보호해야 한다
- 조직코드 접두사 검색은 없다는 것

- [ ] **Step 6: 전체 빌드와 커밋**

```bash
./gradlew build
git add app-scim app-ldap README.md
git commit -m "feat: 두 앱에 조회 API 를 배선하고 드리프트를 E2E 로 못박는다"
git push origin <현재 브랜치>
```

---

## 완료 조건

- `./gradlew build` 가 통과하고 기존 테스트가 하나도 깨지지 않는다
- 두 앱 모두에서 `/admin/employees`, `/admin/organizations` 가 뜬다
- 표시명·계정명·조직명 접두사 검색이 각각 동작하고 커서로 이어 읽힌다
- 직원 상세가 직속 소속과 상위 계층 전부를 주고, 각 줄에 `shouldHaveAccess` 와 `openFgaCheck` 가 함께 실린다
- 비활성 직원의 모든 경로가 `shouldHaveAccess: false` 다
- OpenFGA 튜플을 직접 지우면 E2E 가 어긋남을 잡아낸다
- `Check` 가 실패해도 조회는 200 이고 해당 칸만 `null` 이다
- 검색 파라미터가 없거나 둘 다이면 400, 없는 리소스는 404

## 이 계획이 다루지 않는 것

- **인증** — 조회 API 도 `/admin/sync` 도 열려 있다. 별도 사이클
- 부분일치·전문 검색
- 하위 다단 순회 (1 depth 고정)
- 조직코드 접두사 검색, `employeeId` 접두사 검색 (설계 §3, §7.3)
- 조직도 전체 트리 반환
- SCIM 쓰기 경로의 동시성 — 이 API 는 그 결함을 **감지**할 뿐 고치지 않는다
- 관리 화면(UI). 이 계획은 API 까지다
