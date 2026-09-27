# 조직 멤버 PATCH 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 값 붙은 `remove members` 를 400 으로 거절하고, 조직 멤버 변경을 락 안에서 "바뀌는 멤버만" 보는 경로로 바꿔 요청 비용을 조직 크기와 무관하게 하고 동시 PATCH 덮어쓰기를 없앤다.

**Architecture:** 핸들러는 요청을 저장소를 읽지 않고 core 의 `GroupChange`(멤버 증분 또는 전체 교체)로 정리한다. `IncrementalSyncUseCase.changeGroup` 이 전역 쓰기 락 안에서 조직 META·관련 멤버 줄만 읽어 "바뀌는 멤버만 담은 조직" 두 장을 만들고, 기존 `diffAndApply`(Check 기준선·순환 검사·리스 재확인)에 넣은 뒤, 반영된 멤버 줄만 새 저장소 연산 `saveGroupChange` 로 쓴다. 조직 PATCH 응답은 204.

**Tech Stack:** Java 17, Spring Boot 3.5 WebFlux(함수형 라우터), Reactor, Lombok, AWS SDK v2 DynamoDB(async), OpenFGA, JUnit 5 + AssertJ, Testcontainers(DynamoDB Local, OpenFGA).

**Spec:** `docs/superpowers/specs/2026-09-26-group-member-patch-design.md`

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 모양을 그대로 따른다. Lombok 을 쓴다.
- Java 17 이다 — `switch` 패턴 매칭(`case Add add ->`)은 못 쓴다. `instanceof` 패턴을 쓴다.
- **값 붙은 remove(스펙 §7):** `op` 가 `remove`, `path` 가 `members`, `value` 가 `null` 이 아니면(빈 목록 포함) 400 `invalidValue`. 메시지는 정확히
  `members 에서 멤버를 골라 빼려면 path 에 필터를 쓰세요: members[value eq "<id>"]. Microsoft Entra ID 는 SCIM 테넌트 URL 에 ?aadOptscim062020 을 붙이면 이 형식으로 보냅니다.`
  `value` 가 없거나 `null` 이면 표준대로 전원 빼기(전체 교체, 빈 목록). 조직 `members` 에만 적용한다.
- **응답(스펙 §7):** 조직 PATCH 성공은 `204 No Content`(본문 없음, `attributes` 가 있어도). 잘못된 `attributes`·`excludedAttributes` 는 쓰기 전에 400. 부분 실패는 5xx. PUT 은 200 + 리소스(기존 `attributes` 규칙). 직원 PATCH 는 그대로.
- **락(스펙 §5):** 조직 PATCH·PUT 의 모든 멤버십 판단은 전역 쓰기 락을 잡은 뒤 읽은 값으로 한다. 핸들러는 락 밖에서 멤버십을 읽지 않는다(`MemberTypeResolver` 의 종류 판정만 예외).
- **좁힌 그림(스펙 §5):** 증분은 요청에 나온 멤버를 전후가 같아도 그림에 남긴다. 전체 교체는 바뀌는 멤버만 싣는다. 상위 조직은 싣지 않는다.
- **저장소(스펙 §6):** 키 구조는 바꾸지 않는다(테이블 재생성 없음). 락 안의 읽기는 본 테이블을 강한 일관성으로. 넣기는 소속 줄 → 멤버 줄, 빼기는 멤버 줄 → 소속 줄. 이미 있는 멤버 줄은 다시 쓰지 않는다(`addedAt` 보존). META 는 이름이나 멤버가 바뀌었을 때만 `updatedAt` 을 찍어 쓴다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로 돌린다(`--tests` 로 좁혀도 된다). 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다. DynamoDB Local 이 HTTP 999 를 내면 환경 부하다 — 한 번 다시 돌린다.
- 커밋마다 푸시한다(`git push`). 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 이 작업 트리의 명령 가드는 복잡한 bash(for 반복문, `cd … &&`, 변수 대입)를 거절한다. 단순한 명령을 따로따로 돌린다.

## Review Focus

1. **BatchGetItem 이 미처리 키를 돌려줄 때** — 다시 읽어야 한다. 빠뜨리면 멤버를 "없다" 로 보고 중복 추가(`addedAt` 덮어쓰기)하거나 빼기를 건너뛴다. → Task 2 `첫_배치가_미처리여도_다시_읽는다`.
2. **파싱과 락 사이에 조직이 지워졌을 때** — 404 이고 아무것도 쓰지 않는다. → Task 3 `없는_조직은_빈_결과다`, Task 4 `없는_조직_PATCH는_404다`.
3. **전체 교체 뒤에 오는 id 빼기가 직원·하위 조직 둘 다에 맞을 때** — 락 안에서 현재상태로 한쪽만 뺀다. → Task 3 `교체_뒤_모호한_빼기는_현재상태로_고른다`.
4. **멤버가 아닌 id 를 빼는 PATCH** — 204, OpenFGA 쓰기 없음, META 다시 쓰지 않음. → Task 3 등가 테스트 `비멤버_빼기`, Task 2 `바뀐_것이_없으면_쓰지_않는다`.
5. **type 없는 멤버가 큰 조직을 가리킬 때** — 종류 판정이 그 조직 파티션을 통째로 읽지 않는다. → Task 4 `type이_없으면_현재상태로_판정한다` 에 `findGroupCalls` 단언.

---

### Task 1: core `GroupChange` — 저장소 없이 정리한 조직 변경

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/GroupChange.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/model/GroupChangeTest.java`

**Interfaces:**
- Consumes: `MemberRef`, `GroupHeader`, `DirectoryGroup` (기존)
- Produces:
  - `record GroupChange(boolean renames, String displayName, boolean reidentifies, String externalId, Set<MemberRef> base, List<GroupChange.MemberOp> ops)`
  - `sealed interface GroupChange.MemberOp permits Add, RemoveId`, `record GroupChange.Add(MemberRef ref)`, `record GroupChange.RemoveId(String id)`
  - `static GroupChange delta()`, `static GroupChange replacement(String externalId, String displayName, Set<MemberRef> members)`
  - `GroupChange renamed(String)`, `GroupChange replacing(Set<MemberRef>)`, `GroupChange adding(Set<MemberRef>)`, `GroupChange removingId(String)`
  - `boolean replacesMembers()`, `Set<MemberRef> mentioned()`, `Set<String> ambiguousIds(Set<MemberRef> start)`
  - `Set<MemberRef> replay(Set<MemberRef> start, Predicate<String> 조직이면)`
  - `GroupHeader applyTo(GroupHeader)`, `DirectoryGroup applyTo(DirectoryGroup before, Predicate<String> 조직이면)`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class GroupChangeTest {

    /** 모호하지 않으면 부르지 않아야 한다 — 불리면 테스트가 깨진다. */
    private static final Predicate<String> 부르면_안된다 = id -> {
        throw new AssertionError("모호하지 않은데 종류를 물었다: " + id);
    };

    private static DirectoryGroup 조직(MemberRef... members) {
        return new DirectoryGroup("DEV", "cn=DEV", "개발본부", Set.of(members));
    }

    @Test
    @DisplayName("증분은 연산을 순서대로 적용한다 — 넣고 빼고")
    void 연산을_순서대로_적용한다() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.user("lee")))
                .removingId("kim");

        // when
        var after = change.applyTo(조직(MemberRef.user("kim"), MemberRef.user("park")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactlyInAnyOrder(MemberRef.user("park"), MemberRef.user("lee"));
        assertThat(after.displayName()).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("넣은 뒤 같은 id 를 빼면 변화가 없다")
    void 넣고_빼면_그대로다() {
        // given
        var change = GroupChange.delta().adding(Set.of(MemberRef.user("lee"))).removingId("lee");

        // when
        var after = change.applyTo(조직(MemberRef.user("kim")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("id 빼기가 직원·하위 조직 둘 다에 맞으면 조직이면 에 따라 한쪽만 뺀다")
    void 모호한_빼기는_한쪽만_뺀다() {
        // given
        var before = 조직(MemberRef.user("X"), MemberRef.group("X"));
        var change = GroupChange.delta().removingId("X");

        // when
        var 조직이다 = change.applyTo(before, id -> true);
        var 직원이다 = change.applyTo(before, id -> false);

        // then
        assertThat(조직이다.members()).containsExactly(MemberRef.user("X"));
        assertThat(직원이다.members()).containsExactly(MemberRef.group("X"));
    }

    @Test
    @DisplayName("전체 교체는 앞의 연산을 버리고 목록에서 뒤의 연산을 적용한다")
    void 교체는_앞_연산을_버린다() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.user("ghost")))
                .replacing(Set.of(MemberRef.user("kim"), MemberRef.user("park")))
                .removingId("kim");

        // when
        var after = change.applyTo(조직(MemberRef.user("lee")), 부르면_안된다);

        // then
        assertThat(change.replacesMembers()).isTrue();
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("연산이 가리키는 멤버 — id 빼기는 종류를 모르므로 직원·하위 조직 둘 다다")
    void 가리키는_멤버() {
        // given
        var change = GroupChange.delta().adding(Set.of(MemberRef.group("TEAM"))).removingId("kim");

        // when, then
        assertThat(change.mentioned()).containsExactlyInAnyOrder(
                MemberRef.group("TEAM"), MemberRef.user("kim"), MemberRef.group("kim"));
    }

    @Test
    @DisplayName("둘 다 멤버일 수 있는 id 빼기만 모호하다")
    void 모호한_id() {
        // given
        var change = GroupChange.delta()
                .adding(Set.of(MemberRef.group("X")))
                .removingId("X")
                .removingId("kim");

        // when, then — X 는 직원으로 이미 있고 하위 조직으로 들어온다. kim 은 직원만 있다
        assertThat(change.ambiguousIds(Set.of(MemberRef.user("X"), MemberRef.user("kim"))))
                .containsExactly("X");
    }

    @Test
    @DisplayName("이름만 바꾸면 externalId 는 그대로, PUT 은 둘 다 바꾼다")
    void 헤더를_바꾼다() {
        // given
        var header = new GroupHeader("DEV", "cn=DEV", "개발본부");

        // when
        var 이름만 = GroupChange.delta().renamed("플랫폼본부").applyTo(header);
        var put = GroupChange.replacement("cn=DEV-2", "개발본부2", Set.of()).applyTo(header);
        var 그대로 = GroupChange.delta().applyTo(header);

        // then
        assertThat(이름만).isEqualTo(new GroupHeader("DEV", "cn=DEV", "플랫폼본부"));
        assertThat(put).isEqualTo(new GroupHeader("DEV", "cn=DEV-2", "개발본부2"));
        assertThat(그대로).isEqualTo(header);
    }

    @Test
    @DisplayName("PUT 은 목록 전체로 교체한다")
    void PUT은_전체_교체다() {
        // given
        var change = GroupChange.replacement("cn=DEV", "개발본부", Set.of(MemberRef.user("park")));

        // when
        var after = change.applyTo(조직(MemberRef.user("kim")), 부르면_안된다);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("park"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*GroupChangeTest*'`
Expected: FAIL — `GroupChange` 가 없어 컴파일 오류.

- [ ] **Step 3: 구현한다**

```java
package dev.starryeye.organization.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SCIM 조직 PATCH·PUT 을 <b>저장소를 읽기 전에</b> 정리한 변경(조직 멤버 PATCH 설계 §4).
 *
 * <p>{@link #base()} 가 있으면 <b>전체 교체</b> — 그 목록에 {@link #ops()} 를 순서대로 적용한 것이 목표다. 없으면 <b>멤버 증분</b> —
 * 지금 멤버에 {@link #ops()} 를 순서대로 적용한다. 어느 쪽이든 뜻은 "멤버 집합에 연산을 순서대로 적용한다" 하나다({@link #replay}).
 * 멤버십을 보고 판단하는 일(지금 멤버인가, id 빼기가 직원·하위 조직 중 무엇인가)은 유스케이스가 락 안에서 한다.
 *
 * @param renames      이름을 바꾸는가. {@code displayName} 은 이것이 참일 때만 뜻이 있다(null 도 값이다)
 * @param reidentifies {@code externalId} 를 바꾸는가 — PUT 만 참이다
 * @param base         전체 교체의 시작 목록. 증분이면 null
 * @param ops          멤버 연산. 순서가 뜻이다
 */
public record GroupChange(boolean renames, String displayName,
                          boolean reidentifies, String externalId,
                          Set<MemberRef> base, List<MemberOp> ops) {

    public GroupChange {
        base = base == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(base));
        ops = List.copyOf(ops);
    }

    /** 멤버 연산 하나. */
    public sealed interface MemberOp permits Add, RemoveId {
    }

    /** 멤버를 넣는다. 이미 있으면 그대로다. */
    public record Add(MemberRef ref) implements MemberOp {
    }

    /**
     * id 로 뺀다 — {@code members[value eq "x"]} 에는 종류가 없다. 직원 x 와 하위 조직 x 가 둘 다 멤버면 한쪽만 뺀다({@link #replay}).
     */
    public record RemoveId(String id) implements MemberOp {
    }

    /** 아무것도 바꾸지 않는 증분. PATCH 의 연산을 여기서부터 쌓는다. */
    public static GroupChange delta() {
        return new GroupChange(false, null, false, null, null, List.of());
    }

    /** PUT — 이름·{@code externalId}·멤버를 통째로 바꾼다. */
    public static GroupChange replacement(String externalId, String displayName, Set<MemberRef> members) {
        return new GroupChange(true, displayName, true, externalId, members, List.of());
    }

    public GroupChange renamed(String newDisplayName) {
        return new GroupChange(true, newDisplayName, reidentifies, externalId, base, ops);
    }

    /** 목표 목록을 정한다. 앞의 멤버 연산은 이 목록에 덮여 뜻이 없어진다. */
    public GroupChange replacing(Set<MemberRef> members) {
        return new GroupChange(renames, displayName, reidentifies, externalId, members, List.of());
    }

    public GroupChange adding(Set<MemberRef> refs) {
        List<MemberOp> next = new ArrayList<>(ops);
        refs.forEach(ref -> next.add(new Add(ref)));
        return new GroupChange(renames, displayName, reidentifies, externalId, base, next);
    }

    public GroupChange removingId(String id) {
        List<MemberOp> next = new ArrayList<>(ops);
        next.add(new RemoveId(id));
        return new GroupChange(renames, displayName, reidentifies, externalId, base, next);
    }

    public boolean replacesMembers() {
        return base != null;
    }

    /** 연산이 가리키는 멤버. id 빼기는 종류를 모르므로 직원·하위 조직 둘 다다. */
    public Set<MemberRef> mentioned() {
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                refs.add(add.ref());
            } else if (op instanceof RemoveId remove) {
                refs.add(MemberRef.user(remove.id()));
                refs.add(MemberRef.group(remove.id()));
            }
        }
        return refs;
    }

    /**
     * {@code start} 에 연산을 적용하는 동안 직원·하위 조직이 같은 id 로 <b>둘 다 멤버일 수 있는</b> 채 id 빼기를 만나는 id 들 —
     * 이때만 현재상태로 종류를 물어야 한다. 넣기만 따라가므로 실제보다 넉넉하게 센다.
     */
    public Set<String> ambiguousIds(Set<MemberRef> start) {
        Set<MemberRef> possible = new LinkedHashSet<>(start);
        Set<String> ids = new LinkedHashSet<>();
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                possible.add(add.ref());
            } else if (op instanceof RemoveId remove
                    && possible.contains(MemberRef.user(remove.id()))
                    && possible.contains(MemberRef.group(remove.id()))) {
                ids.add(remove.id());
            }
        }
        return ids;
    }

    /**
     * {@code start} 에 연산을 순서대로 적용한 멤버 집합. 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 id 로 빼면
     * {@code 조직이면} 이 참이면 하위 조직을, 아니면 직원을 뺀다 — 전에 {@code StateMemberTypeResolver} 로 고르던 규칙과 같다.
     */
    public Set<MemberRef> replay(Set<MemberRef> start, Predicate<String> 조직이면) {
        Set<MemberRef> members = new LinkedHashSet<>(start);
        for (MemberOp op : ops) {
            if (op instanceof Add add) {
                members.add(add.ref());
            } else if (op instanceof RemoveId remove) {
                MemberRef user = MemberRef.user(remove.id());
                MemberRef group = MemberRef.group(remove.id());
                if (members.contains(user) && members.contains(group)) {
                    members.remove(조직이면.test(remove.id()) ? group : user);
                } else {
                    members.remove(user);
                    members.remove(group);
                }
            }
        }
        return members;
    }

    public GroupHeader applyTo(GroupHeader header) {
        return new GroupHeader(header.id(),
                reidentifies ? externalId : header.externalId(),
                renames ? displayName : header.displayName());
    }

    /**
     * 멤버 전체를 아는 경우의 결과. 전체 목록을 비교하던 방식과 같은 뜻을 정의하고, 테스트가 두 방식을 견주는 데 쓴다.
     */
    public DirectoryGroup applyTo(DirectoryGroup before, Predicate<String> 조직이면) {
        GroupHeader header = applyTo(new GroupHeader(before.id(), before.externalId(), before.displayName()));
        Set<MemberRef> start = replacesMembers() ? base : before.members();
        return new DirectoryGroup(header.id(), header.externalId(), header.displayName(), replay(start, 조직이면));
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :core:test --tests '*GroupChangeTest*'`
Expected: PASS (8 tests)

- [ ] **Step 5: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/GroupChange.java core/src/test/java/dev/starryeye/organization/core/model/GroupChangeTest.java
git commit -m "feat: 조직 변경(GroupChange) — PATCH·PUT 을 저장소 없이 멤버 증분·전체 교체로 정리한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 2: 저장소 — 멤버 여부·멤버 키·하위 조직 id 읽기와 멤버 변경 저장

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (`memberSk` 근처)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`saveGroup`·`writeGroup`·`existingMemberSks`, 새 메서드)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (위임 레코드 `한번만_실패하는_상태저장소`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: `GroupHeader`, `MemberRef`, `MemberType` (기존)
- Produces (포트):
  - `Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates)`
  - `Flux<MemberRef> findMemberRefs(String groupId)`
  - `Flux<String> findChildGroupIds(String groupId)`
  - `Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed)`
- Produces (가짜): `FakeStateRepository.findMembersCalls: List<Set<MemberRef>>`, `findMemberRefsCalls: List<String>`, `findChildGroupIdsCalls: List<String>`
- Produces (Keys): `static String memberSkPrefix(MemberType type)`

- [ ] **Step 1: 실패하는 저장소 테스트를 쓴다** — `DynamoDbDirectoryStateRepositoryTest` 끝(`세는_저장소` 도우미 아래)에 더한다. import 에 `java.lang.reflect.InvocationTargetException`, `java.lang.reflect.Proxy`, `java.util.concurrent.CompletableFuture`, `java.util.concurrent.atomic.AtomicInteger`, `java.util.stream.IntStream`, `java.util.LinkedHashSet`, `software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient`, `software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest`, `software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse`, `dev.starryeye.organization.core.model.GroupHeader` 가 없으면 더한다.

```java
    // ---------- 조직 멤버 PATCH (설계 §6) ----------

    @Test
    @DisplayName("멤버 여부는 주어진 것 중 지금 멤버인 것만 돌려준다")
    void 멤버_여부를_확인한다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"), MemberRef.group("TEAM"))).block();

        // when
        var found = repository.findMembers("DEV", Set.of(
                MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("TEAM"), MemberRef.user("TEAM"))).block();

        // then
        assertThat(found).containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.group("TEAM"));
    }

    @Test
    @DisplayName("멤버 여부는 100개를 넘으면 나눠 읽는다 — BatchGetItem 은 한 번에 100개까지다")
    void 멤버_여부는_나눠_읽는다() {
        // given — 멤버 250명, 후보는 그 250명과 비멤버 10명
        Set<MemberRef> members = new LinkedHashSet<>();
        IntStream.range(0, 250).forEach(i -> members.add(MemberRef.user("u%03d".formatted(i))));
        repository.saveGroup(new DirectoryGroup("BIG", "cn=BIG", "큰조직", members)).block();
        Set<MemberRef> candidates = new LinkedHashSet<>(members);
        IntStream.range(0, 10).forEach(i -> candidates.add(MemberRef.user("x%03d".formatted(i))));

        // when
        var found = repository.findMembers("BIG", candidates).block();

        // then
        assertThat(found).containsExactlyInAnyOrderElementsOf(members);
    }

    @Test
    @DisplayName("첫 BatchGetItem 이 키를 전부 미처리로 돌려줘도 다시 읽어 빠짐없이 돌려준다")
    void 첫_배치가_미처리여도_다시_읽는다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"), MemberRef.user("park"))).block();
        AtomicInteger 호출 = new AtomicInteger();
        var 인색한 = new DynamoDbDirectoryStateRepository(첫_배치는_미처리(client, 호출), properties, clock);

        // when
        var found = 인색한.findMembers("DEV", Set.of(MemberRef.user("kim"), MemberRef.user("park"))).block();

        // then
        assertThat(found).containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.user("park"));
        assertThat(호출.get()).isGreaterThanOrEqualTo(2);
    }

    /** 첫 BatchGetItem 에 아무것도 읽지 않고 키를 전부 미처리로 돌려준다 — 처리량이 모자랄 때 DynamoDB 가 하는 일이다. */
    private static DynamoDbAsyncClient 첫_배치는_미처리(DynamoDbAsyncClient real, AtomicInteger 호출) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("batchGetItem") && args != null
                            && args[0] instanceof BatchGetItemRequest request
                            && 호출.getAndIncrement() == 0) {
                        return CompletableFuture.completedFuture(BatchGetItemResponse.builder()
                                .responses(Map.of())
                                .unprocessedKeys(request.requestItems())
                                .build());
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    @DisplayName("멤버 키 전체는 멤버 줄만 — META 와 이 조직 자신의 소속 줄은 섞이지 않는다")
    void 멤버_키_전체를_읽는다() {
        // given — DEV 는 TOP 의 하위 조직이라 DEV 파티션에 BELONGS_TO#GROUP#TOP 줄이 있다
        repository.saveGroup(조직("TOP", "본사", MemberRef.group("DEV"))).block();
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"), MemberRef.group("TEAM"))).block();

        // when
        var refs = repository.findMemberRefs("DEV").collectList().block();

        // then
        assertThat(refs).containsExactlyInAnyOrder(MemberRef.user("kim"), MemberRef.group("TEAM"));
    }

    @Test
    @DisplayName("하위 조직 id 는 하위 조직 멤버 줄만 읽는다")
    void 하위_조직_id를_읽는다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부",
                MemberRef.user("kim"), MemberRef.group("TEAM1"), MemberRef.group("TEAM2"))).block();

        // when
        var ids = repository.findChildGroupIds("DEV").collectList().block();

        // then
        assertThat(ids).containsExactlyInAnyOrder("TEAM1", "TEAM2");
        assertThat(repository.findChildGroupIds("NONE").collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("멤버 변경 저장은 준 멤버 줄만 넣고 빼며 소속 줄도 함께 움직인다")
    void 멤버_변경을_저장한다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"), MemberRef.user("park"))).block();

        // when
        repository.saveGroupChange(new GroupHeader("DEV", "cn=DEV", "개발본부"),
                Set.of(MemberRef.user("lee")), Set.of(MemberRef.user("kim"))).block();

        // then
        assertThat(repository.findGroup("DEV").block().members())
                .containsExactlyInAnyOrder(MemberRef.user("park"), MemberRef.user("lee"));
        assertThat(repository.findGroupIdsContaining(MemberRef.user("lee")).collectList().block()).containsExactly("DEV");
        assertThat(repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("멤버 변경 저장은 다른 멤버 줄을 건드리지 않는다 — 한 명 넣으면 PutItem 은 소속 줄·멤버 줄·META 셋이다")
    void 다른_멤버는_건드리지_않는다() {
        // given
        Set<MemberRef> members = new LinkedHashSet<>();
        IntStream.range(0, 50).forEach(i -> members.add(MemberRef.user("u%02d".formatted(i))));
        repository.saveGroup(new DirectoryGroup("DEV", "cn=DEV", "개발본부", members)).block();
        String 처음합류 = addedAt("DEV", MemberRef.user("u00"));
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        clock.앞으로(Duration.ofHours(1));

        // when
        세는.saveGroupChange(new GroupHeader("DEV", "cn=DEV", "개발본부"), Set.of(MemberRef.user("new")), Set.of()).block();

        // then
        assertThat(counter.puts()).isEqualTo(3);
        assertThat(addedAt("DEV", MemberRef.user("u00"))).isEqualTo(처음합류);
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T01:00:00Z");
    }

    @Test
    @DisplayName("이름도 멤버도 그대로면 멤버 변경 저장은 아무것도 쓰지 않는다")
    void 바뀐_것이_없으면_쓰지_않는다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"))).block();
        String 처음 = updatedAt(Keys.groupPk("DEV"));
        WriteCounter counter = new WriteCounter();
        var 세는 = 세는_저장소(counter);
        clock.앞으로(Duration.ofHours(1));

        // when
        세는.saveGroupChange(new GroupHeader("DEV", "cn=DEV", "개발본부"), Set.of(), Set.of()).block();

        // then
        assertThat(counter.puts()).isZero();
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo(처음);
    }

    @Test
    @DisplayName("이름만 바꿔도 META 를 다시 쓰고 updatedAt 이 그 시각이 된다")
    void 이름만_바꿔도_META를_쓴다() {
        // given
        repository.saveGroup(조직("DEV", "개발본부", MemberRef.user("kim"))).block();
        clock.앞으로(Duration.ofHours(2));

        // when
        repository.saveGroupChange(new GroupHeader("DEV", "cn=DEV", "플랫폼본부"), Set.of(), Set.of()).block();

        // then
        assertThat(repository.findGroupHeader("DEV").block().displayName()).isEqualTo("플랫폼본부");
        assertThat(updatedAt(Keys.groupPk("DEV"))).isEqualTo("2026-01-01T02:00:00Z");
        assertThat(repository.findGroup("DEV").block().members()).containsExactly(MemberRef.user("kim"));
    }
```

`조직(code, name, members...)` 도우미는 `externalId` 를 `"cn=" + code` 로 만든다 — 위 `GroupHeader` 의 `"cn=DEV"` 가 그것과 같아야 "이름도 그대로" 가 성립한다.

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`
Expected: FAIL — `findMembers` 등이 없어 컴파일 오류.

- [ ] **Step 3: 포트에 넷을 더한다** — `DirectoryStateRepository` 의 `findGroupIdsContaining` 위에 넣는다. import 에 `java.util.Set` 을 더한다.

```java
    /**
     * 주어진 멤버 중 지금 이 조직의 멤버인 것. <b>읽는 양이 조직 크기가 아니라 후보 수를 따른다</b> — 조직 멤버 PATCH 가 멤버 한 명을
     * 바꿀 때 조직 전체를 읽지 않으려고 쓴다(설계 `2026-09-26-group-member-patch-design.md` §6). 강한 일관성이다.
     */
    Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates);

    /** 이 조직의 멤버 전부를 <b>키만</b> 읽는다. 전체 교체가 목표 목록과 비교하는 데 쓴다. 강한 일관성이다. */
    Flux<MemberRef> findMemberRefs(String groupId);

    /** 이 조직의 하위 조직 id. 직원 멤버는 읽지 않는다 — 순환 검사가 계층을 내려갈 때 쓴다. 조직이 없으면 비어 있다. */
    Flux<String> findChildGroupIds(String groupId);

    /**
     * 멤버 줄을 {@code added} 만큼 넣고 {@code removed} 만큼 빼고 META 를 {@code header} 로 맞춘다. {@link #saveGroup} 과 같은 규칙이다 —
     * 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저, META 는 이름이나 멤버가 바뀌었을 때만 {@code updatedAt} 을 찍는다. 부르는 쪽이
     * {@code added} 가 지금 멤버가 아니고 {@code removed} 가 지금 멤버라는 것을 확인했다고 본다(락 안에서 {@link #findMembers} 로).
     */
    Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed);
```

- [ ] **Step 4: 가짜 저장소를 맞춘다** — `FakeStateRepository` 에 필드 셋과 메서드 넷을 더한다. import 에 `dev.starryeye.organization.core.model.MemberType`, `java.util.LinkedHashSet`, `java.util.Set` 을 더한다.

```java
    /** {@link #findMembers} 가 받은 후보들. 무엇을 물었는지 단언하는 계측이다. */
    public final List<Set<MemberRef>> findMembersCalls = new ArrayList<>();

    /** {@link #findMemberRefs} 가 불린 순서대로의 조직 id. */
    public final List<String> findMemberRefsCalls = new ArrayList<>();

    /** {@link #findChildGroupIds} 가 불린 순서대로의 조직 id. */
    public final List<String> findChildGroupIdsCalls = new ArrayList<>();

    @Override
    public Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates) {
        return Mono.fromCallable(() -> {
            findMembersCalls.add(Set.copyOf(candidates));
            Set<MemberRef> found = new LinkedHashSet<>();
            candidates.stream().filter(membersOf(groupId)::contains).forEach(found::add);
            return found;
        });
    }

    @Override
    public Flux<MemberRef> findMemberRefs(String groupId) {
        return Flux.defer(() -> {
            findMemberRefsCalls.add(groupId);
            return Flux.fromIterable(membersOf(groupId));
        });
    }

    @Override
    public Flux<String> findChildGroupIds(String groupId) {
        return Flux.defer(() -> {
            findChildGroupIdsCalls.add(groupId);
            return Flux.fromIterable(membersOf(groupId))
                    .filter(ref -> ref.type() == MemberType.GROUP)
                    .map(MemberRef::id);
        });
    }

    @Override
    public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return Mono.fromRunnable(() -> {
            Set<MemberRef> members = new LinkedHashSet<>(membersOf(header.id()));
            members.removeAll(removed);
            members.addAll(added);
            groups.put(header.id(), new DirectoryGroup(header.id(), header.externalId(), header.displayName(), members));
        });
    }

    private Set<MemberRef> membersOf(String groupId) {
        DirectoryGroup group = groups.get(groupId);
        return group == null ? Set.of() : group.members();
    }
```

- [ ] **Step 5: `Keys` 에 종류별 멤버 정렬키 접두를 더한다** — `memberSk` 를 이것으로 만든다.

```java
    public static String memberSk(MemberRef ref) {
        return memberSkPrefix(ref.type()) + ref.id();
    }

    /** 한 종류 멤버 줄의 정렬키 접두. {@code begins_with} 로 그 종류만 읽는다. */
    public static String memberSkPrefix(MemberType type) {
        return MEMBER_PREFIX + type.name() + "#";
    }
```

- [ ] **Step 6: DynamoDB 구현** — `DynamoDbDirectoryStateRepository` 를 고친다. import 에 `dev.starryeye.organization.core.model.MemberType`, `software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest`, `software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse`, `software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes`, `java.time.Duration` 을 더한다.

상수(`QUERY_CONCURRENCY` 아래):

```java
    /** BatchGetItem 한 번에 담을 수 있는 키 수(DynamoDB 한도). */
    private static final int BATCH_GET_LIMIT = 100;
    /** 미처리 키를 다시 읽기 전에 쉬는 시간. 처리량이 모자라 남은 키라 곧바로 다시 부르면 또 남는다. */
    private static final Duration UNPROCESSED_RETRY_DELAY = Duration.ofMillis(50);
```

`saveGroup`·`writeGroup` 을 아래로 바꾸고(`writeGroup` 의 쓰기 부분을 `writeMembership` 으로 뽑는다) 새 메서드를 더한다:

```java
    /** 직원과 같은 규칙으로 쓴다. 조직의 변경은 META 또는 멤버 구성의 변경이다(GSI 설계 §3). */
    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        return storedGroupOf(group.id()).flatMap(stored -> writeGroup(group, stored.orElse(null)));
    }

    /** 지금 멤버와 비교하지 않는다 — 부르는 쪽이 락 안에서 {@link #findMembers} 로 확인한 차이다(조직 멤버 PATCH 설계 §6). */
    @Override
    public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return storedGroupOf(header.id())
                .flatMap(stored -> writeMembership(header, stored.orElse(null), List.copyOf(added), List.copyOf(removed)));
    }

    private Mono<Optional<Stored<GroupHeader>>> storedGroupOf(String groupId) {
        return findMeta(Keys.groupPk(groupId))
                .map(this::storedGroup)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private Mono<Void> writeGroup(DirectoryGroup group, Stored<GroupHeader> stored) {
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
        Set<String> targetSks = group.members().stream().map(Keys::memberSk).collect(Collectors.toSet());

        return existingMemberSks(group.id())
                .collectList()
                .flatMap(existing -> {
                    Set<String> existingSks = Set.copyOf(existing);
                    List<MemberRef> 떠난멤버 = existing.stream()
                            .filter(sk -> !targetSks.contains(sk))
                            .map(Keys::parseMemberSk)
                            .toList();
                    // 이미 있는 멤버는 건드리지 않는다. 다시 put 하면 addedAt 이 덮여
                    // "최초 합류" 가 아니라 "마지막 전체 동기화" 를 뜻하게 된다.
                    // 나머지 속성(GSI 키)은 groupId·member 로만 정해져 바뀔 것이 없다.
                    List<MemberRef> 새로온멤버 = group.members().stream()
                            .filter(member -> !existingSks.contains(Keys.memberSk(member)))
                            .toList();
                    return writeMembership(header, stored, 새로온멤버, 떠난멤버);
                });
    }

    /**
     * META 와 멤버 줄을 쓴다 — {@link #saveGroup} 과 {@link #saveGroupChange} 가 같은 규칙을 쓰도록 한 곳에 둔다.
     *
     * <p>조직의 변경은 META 의 변경 또는 멤버 구성의 변경이다 — SCIM 의 Group 은 members 를 담는다. header 를 그대로 비교하지 않고
     * 라운드트립하는 이유는 writeUser 의 자바독과 같다 — 빈 문자열 displayName 은 저장되지 않아 되읽으면 null 이 된다.
     *
     * <p>소속 줄이 항상 멤버 줄보다 많거나 같게 유지한다(설계 §5). 넣을 때는 소속 줄 먼저, 뺄 때는 멤버 줄 먼저 — 중간에 실패해도
     * "소속 줄만 남는" 안전한 방향으로만 어긋난다. 반대로 어긋나면 삭제가 그 조직을 못 찾아 권한이 남는다.
     */
    private Mono<Void> writeMembership(GroupHeader header, Stored<GroupHeader> stored,
                                       List<MemberRef> 새로온멤버, List<MemberRef> 떠난멤버) {
        boolean 바뀜 = stored == null || !stored.sameAs(toGroupHeader(header.id(), groupMeta(header)))
                || !떠난멤버.isEmpty() || !새로온멤버.isEmpty();
        Mono<Void> meta = 바뀜 ? putItem(stamped(groupMeta(header))) : Mono.empty();

        return Flux.fromIterable(떠난멤버)
                .flatMap(ref -> deleteItem(Keys.groupPk(header.id()), Keys.memberSk(ref))
                        .then(deleteItem(Keys.memberPk(ref), Keys.belongsToSk(header.id()))),
                        QUERY_CONCURRENCY)
                .then(meta)
                .then(Flux.fromIterable(새로온멤버)
                        .flatMap(member -> putItem(belongsToItem(member, header.id()))
                                .then(putItem(memberItem(header.id(), member))),
                                QUERY_CONCURRENCY)
                        .then());
    }

    /**
     * 멤버 줄 키를 {@code BatchGetItem} 으로 <b>강한 일관성</b>으로 읽는다. 조직 파티션을 훑지 않으므로 읽는 양이 조직 크기가 아니라
     * 후보 수를 따른다(조직 멤버 PATCH 설계 §6). 미처리 키는 잠깐 쉬었다 다시 읽는다 — 빠뜨리면 멤버를 "없다" 로 본다.
     */
    @Override
    public Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates) {
        return Flux.fromIterable(candidates)
                .buffer(BATCH_GET_LIMIT)
                .concatMap(chunk -> batchGet(chunk.stream()
                        .map(ref -> Map.of(
                                Keys.PK, Attrs.s(Keys.groupPk(groupId)),
                                Keys.SK, Attrs.s(Keys.memberSk(ref))))
                        .toList()))
                .map(item -> Keys.parseMemberSk(Attrs.str(item, Keys.SK)))
                .collect(LinkedHashSet<MemberRef>::new, Set::add)
                .map(found -> (Set<MemberRef>) found);
    }

    /** 멤버 줄만, <b>정렬키만</b> 읽는다. META 와 이 조직 자신의 소속 줄({@code BELONGS_TO#})은 접두가 달라 섞이지 않는다. */
    @Override
    public Flux<MemberRef> findMemberRefs(String groupId) {
        return querySortKeys(Keys.groupPk(groupId), Keys.MEMBER_PREFIX).map(Keys::parseMemberSk);
    }

    /** 하위 조직 멤버 줄만 읽는다 — 순환 검사가 직원 줄까지 읽지 않게(조직 멤버 PATCH 설계 §1.4). */
    @Override
    public Flux<String> findChildGroupIds(String groupId) {
        return querySortKeys(Keys.groupPk(groupId), Keys.memberSkPrefix(MemberType.GROUP))
                .map(Keys::parseMemberSk)
                .map(MemberRef::id);
    }

    private Flux<Map<String, AttributeValue>> batchGet(List<Map<String, AttributeValue>> keys) {
        String table = properties.getTableName();
        KeysAndAttributes 처음 = KeysAndAttributes.builder().keys(keys).consistentRead(true).build();
        return Mono.fromFuture(() -> client.batchGetItem(BatchGetItemRequest.builder()
                        .requestItems(Map.of(table, 처음))
                        .build()))
                .expand(response -> {
                    KeysAndAttributes 남은것 = response.unprocessedKeys().get(table);
                    if (남은것 == null || !남은것.hasKeys() || 남은것.keys().isEmpty()) {
                        return Mono.empty();
                    }
                    KeysAndAttributes 다시 = 남은것.toBuilder().consistentRead(true).build();
                    return Mono.delay(UNPROCESSED_RETRY_DELAY)
                            .then(Mono.fromFuture(() -> client.batchGetItem(BatchGetItemRequest.builder()
                                    .requestItems(Map.of(table, 다시))
                                    .build())));
                })
                .concatMapIterable(response -> response.responses().getOrDefault(table, List.of()));
    }

    /** 파티션에서 정렬키가 {@code prefix} 로 시작하는 줄의 <b>정렬키만</b> 강한 일관성으로 읽는다. */
    private Flux<String> querySortKeys(String pk, String prefix) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", Keys.PK, "#sk", Keys.SK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk), ":prefix", Attrs.s(prefix)))
                .projectionExpression("#sk")
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request).map(item -> Attrs.str(item, Keys.SK));
    }
```

`existingMemberSks` 도 멤버 줄만 키로 읽게 바꾼다(`saveGroup` 이 META·소속 줄까지 읽지 않도록):

```java
    private Flux<String> existingMemberSks(String groupId) {
        return querySortKeys(Keys.groupPk(groupId), Keys.MEMBER_PREFIX);
    }
```

- [ ] **Step 7: LDAP 규모 테스트의 위임 레코드를 맞춘다** — `LdapInterruptedSyncScaleTest.한번만_실패하는_상태저장소` 에 넷을 더한다(import 에 `java.util.Set` 이 없으면 더한다).

```java
        @Override public Mono<Set<MemberRef>> findMembers(String groupId, Set<MemberRef> candidates) {
            return 실제.findMembers(groupId, candidates);
        }
        @Override public Flux<MemberRef> findMemberRefs(String groupId) { return 실제.findMemberRefs(groupId); }
        @Override public Flux<String> findChildGroupIds(String groupId) { return 실제.findChildGroupIds(groupId); }
        @Override public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
            return 실제.saveGroupChange(header, added, removed);
        }
```

- [ ] **Step 8: 통과를 확인한다** — 하나씩 돌린다.

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest*'`
Expected: PASS (기존 전부 + 새 9개)

Run: `./gradlew :core:test`
Expected: PASS (가짜 저장소가 컴파일된다)

Run: `./gradlew :app-ldap:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -m "feat: 저장소가 멤버 여부·멤버 키·하위 조직 id 만 읽고 바뀐 멤버 줄만 쓴다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 3: 유스케이스 `changeGroup` — 락 안에서 바뀌는 멤버만 담은 그림

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`upsertGroup` 아래에 새 입구, `childIdsOf`, `expandWithReferencedGroups`)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java:409-410`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncDriftTest.java` (락 실패 테스트 + 새 테스트)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java` (새 테스트 하나)
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/GroupChangeEquivalenceTest.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/GroupChangeReadScopeTest.java`

**Interfaces:**
- Consumes: Task 1 `GroupChange` 전부, Task 2 `findMembers`·`findMemberRefs`·`findChildGroupIds`·`saveGroupChange`, 가짜의 `findMembersCalls`·`findMemberRefsCalls`·`findChildGroupIdsCalls`
- Produces: `public Mono<IncrementalSyncResult> changeGroup(String groupId, GroupChange change)` — 조직이 없으면 빈 `Mono`

- [ ] **Step 1: 등가 테스트를 쓴다** — `GroupChangeEquivalenceTest`

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
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>바뀌는 멤버만 담은 그림으로 바꿔도 결과가 같다</b> (조직 멤버 PATCH 설계 §8.2).
 *
 * <p>같은 변경을 전체 목록을 비교하던 방식({@code upsertGroup})과 새 방식({@code changeGroup})으로 따로 돌려, OpenFGA 에 남는
 * 튜플과 저장된 조직이 같은지 본다. 두 방식은 각자의 세계에서 돈다. 처음 OpenFGA 는 상태와 맞춰 둔다 — 어긋남이 있으면 옛 방식은
 * 조직 전원을 고치고 새 방식은 요청에 나온 멤버만 고치므로(설계 §11) 결과가 달라지는 것이 맞다. 그 차이는
 * {@code IncrementalSyncDriftTest} 가 따로 못 박는다.
 */
class GroupChangeEquivalenceTest {

    private static final String 조직 = "DEV";

    /** 한 방식이 도는 세계. OpenFGA 의 최종 상태는 처음 심은 튜플에 쓴 것을 더하고 지운 것을 뺀 것이다. */
    private record 세계(FakeStateRepository state, FakeTupleWriter writer, FakeTupleChecker checker,
                      IncrementalSyncUseCase useCase) {

        static 세계 만든다() {
            var state = new FakeStateRepository();
            var writer = new FakeTupleWriter();
            var checker = new FakeTupleChecker();
            var useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(),
                    Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
            var w = new 세계(state, writer, checker, useCase);
            w.직원("kim", true);
            w.직원("park", true);
            w.직원("lee", true);
            w.직원("choi", false);
            w.조직("TEAM1");
            w.조직("TEAM2");
            state.groups.put(조직, new DirectoryGroup(조직, "cn=DEV", "개발본부",
                    Set.of(MemberRef.user("kim"), MemberRef.user("park"), MemberRef.group("TEAM1"))));
            checker.allowed.add(RelationTuple.directMember("kim", 조직));
            checker.allowed.add(RelationTuple.directMember("park", 조직));
            checker.allowed.add(RelationTuple.child("TEAM1", 조직));
            return w;
        }

        void 직원(String id, boolean active) {
            state.users.put(id, new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active));
        }

        void 조직(String id, MemberRef... members) {
            state.groups.put(id, new DirectoryGroup(id, "cn=" + id, id + " 팀", Set.of(members)));
        }

        void 멤버를_더한다(MemberRef... refs) {
            DirectoryGroup dev = state.groups.get(조직);
            Set<MemberRef> members = new LinkedHashSet<>(dev.members());
            members.addAll(Set.of(refs));
            state.groups.put(조직, new DirectoryGroup(조직, dev.externalId(), dev.displayName(), members));
        }

        Set<RelationTuple> openFga() {
            Set<RelationTuple> tuples = new LinkedHashSet<>(checker.allowed);
            tuples.addAll(writer.written);
            tuples.removeAll(writer.deleted);
            return tuples;
        }

        /** 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 — 현재상태에 그 조직이 있으면 하위 조직이다. */
        boolean 조직이면(String id) {
            return state.groups.containsKey(id);
        }
    }

    private static void 같은_결과다(GroupChange change) {
        같은_결과다(change, w -> {
        });
    }

    /** 같은 변경을 두 방식으로 돌려 튜플·저장 상태를 견준다. {@code 준비} 는 두 세계에 똑같이 적용한다. */
    private static void 같은_결과다(GroupChange change, Consumer<세계> 준비) {
        // given
        세계 옛 = 세계.만든다();
        세계 새 = 세계.만든다();
        준비.accept(옛);
        준비.accept(새);
        DirectoryGroup 목표 = change.applyTo(옛.state().groups.get(조직), 옛::조직이면);

        // when
        var 옛결과 = 옛.useCase().upsertGroup(목표).block(Duration.ofSeconds(10));
        var 새결과 = 새.useCase().changeGroup(조직, change).block(Duration.ofSeconds(10));

        // then
        assertThat(새결과.fullyApplied()).isEqualTo(옛결과.fullyApplied());
        assertThat(새.openFga()).containsExactlyInAnyOrderElementsOf(옛.openFga());
        assertThat(새.state().groups.get(조직)).isEqualTo(옛.state().groups.get(조직));
    }

    @Test
    @DisplayName("활성 직원을 넣는다")
    void 활성_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("lee"))));
    }

    @Test
    @DisplayName("비활성 직원을 넣으면 멤버십만 생기고 튜플은 없다")
    void 비활성_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("choi"))));
    }

    @Test
    @DisplayName("아직 없는 직원을 넣으면 멤버 줄만 남는다")
    void 없는_직원_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("ghost"))));
    }

    @Test
    @DisplayName("이미 멤버인 직원을 넣으면 아무것도 바뀌지 않는다")
    void 이미_멤버_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("kim"))));
    }

    @Test
    @DisplayName("멤버를 id 로 뺀다")
    void 멤버_빼기() {
        같은_결과다(GroupChange.delta().removingId("park"));
    }

    @Test
    @DisplayName("멤버가 아닌 id 를 빼면 아무것도 바뀌지 않는다")
    void 비멤버_빼기() {
        같은_결과다(GroupChange.delta().removingId("lee"));
    }

    @Test
    @DisplayName("하위 조직을 넣으면 child 엣지가 생긴다")
    void 하위_조직_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("TEAM2"))));
    }

    @Test
    @DisplayName("아직 없는 하위 조직을 넣으면 멤버 줄만 남는다")
    void 없는_하위_조직_추가() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("GHOST"))));
    }

    @Test
    @DisplayName("순환을 만드는 하위 조직은 엣지를 버리고 멤버십은 남긴다")
    void 순환_하위_조직() {
        // TEAM2 → MID → DEV 인데 DEV 아래에 TEAM2 를 넣는다. 두 홉으로 둔다 — 한 홉(TEAM2 → DEV)이면 옛 방식은 상위 조직
        // TEAM2 를 멤버째 스냅샷에 실어 TupleMapper 의 DFS 가 어느 간선을 버릴지가 순서에 달린다
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.group("TEAM2"))), w -> {
            w.조직("MID", MemberRef.group(조직));
            w.조직("TEAM2", MemberRef.group("MID"));
            w.checker().allowed.add(RelationTuple.child(조직, "MID"));
            w.checker().allowed.add(RelationTuple.child("MID", "TEAM2"));
        });
    }

    @Test
    @DisplayName("직원과 하위 조직이 같은 id 로 둘 다 멤버면 id 빼기는 현재상태로 한쪽만 뺀다")
    void 같은_id() {
        같은_결과다(GroupChange.delta().removingId("X"), w -> {
            w.직원("X", true);
            w.조직("X");
            w.멤버를_더한다(MemberRef.user("X"), MemberRef.group("X"));
            w.checker().allowed.add(RelationTuple.directMember("X", 조직));
            w.checker().allowed.add(RelationTuple.child("X", 조직));
        });
    }

    @Test
    @DisplayName("OpenFGA 쓰기·삭제가 일부 실패하면 반영된 멤버만 저장된다")
    void 일부_실패() {
        같은_결과다(GroupChange.delta().adding(Set.of(MemberRef.user("lee"))).removingId("park"),
                w -> w.writer().failFor(tuple -> tuple.equals(RelationTuple.directMember("lee", 조직))
                        || tuple.equals(RelationTuple.directMember("park", 조직))));
    }

    @Test
    @DisplayName("전체 교체 — 빠지는 멤버와 들어오는 멤버")
    void 전체_교체() {
        같은_결과다(GroupChange.delta().replacing(Set.of(MemberRef.user("kim"), MemberRef.user("lee"))));
    }

    @Test
    @DisplayName("전체 비우기")
    void 전체_비우기() {
        같은_결과다(GroupChange.delta().replacing(Set.of()));
    }

    @Test
    @DisplayName("PUT — 이름·externalId·멤버를 통째로 바꾼다")
    void PUT() {
        같은_결과다(GroupChange.replacement("cn=DEV-2", "개발본부2",
                Set.of(MemberRef.user("park"), MemberRef.group("TEAM2"))));
    }

    @Test
    @DisplayName("이름만 바꾼다")
    void 이름만() {
        같은_결과다(GroupChange.delta().renamed("플랫폼본부"));
    }

    @Test
    @DisplayName("전체 교체 뒤의 연산은 목표 목록에 적용된다")
    void 교체_뒤_연산() {
        같은_결과다(GroupChange.delta()
                .replacing(Set.of(MemberRef.user("kim")))
                .adding(Set.of(MemberRef.user("lee")))
                .removingId("kim"));
    }
}
```

- [ ] **Step 2: 비용 테스트를 쓴다** — `GroupChangeReadScopeTest`

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
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>조직 멤버 변경이 조직 크기만큼 읽지 않는다</b> (조직 멤버 PATCH 설계 §8.2).
 *
 * <p>되돌려도 튜플은 맞고 E2E 도 통과한다 — 여기서만 잡힌다. {@code IncrementalSyncReadScopeTest} 가 직원 쪽을 같은 방식으로 본다.
 */
class GroupChangeReadScopeTest {

    private static final int 멤버수 = 5_000;
    private static final String 대형조직 = "ALL";

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private IncrementalSyncUseCase useCase;
    private Set<MemberRef> 전원;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        useCase = new IncrementalSyncUseCase(state, writer, checker, new FakeMutationLock(),
                Duration.ZERO, IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);

        전원 = new LinkedHashSet<>();
        IntStream.range(0, 멤버수).forEach(i -> {
            String id = "u" + i;
            state.users.put(id, 직원(id));
            전원.add(MemberRef.user(id));
            checker.allowed.add(RelationTuple.directMember(id, 대형조직));
        });
        state.users.put("newbie", 직원("newbie"));
        state.groups.put(대형조직, new DirectoryGroup(대형조직, "cn=ALL", "전 직원", 전원));

        state.findGroupCalls.clear();
        state.findGroupHeaderCalls.clear();
        state.findUserCalls.clear();
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", true);
    }

    @Test
    @DisplayName("한 명을 넣을 때 조직 전체도 동료도 읽지 않는다")
    void 한명_추가() {
        // when
        var result = useCase.changeGroup(대형조직, GroupChange.delta().adding(Set.of(MemberRef.user("newbie"))))
                .block(Duration.ofSeconds(10));

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findGroupCalls).as("조직 파티션을 통째로 읽지 않는다").isEmpty();
        assertThat(state.findMemberRefsCalls).isEmpty();
        assertThat(state.findMembersCalls).containsExactly(Set.of(MemberRef.user("newbie")));
        assertThat(state.findUserCalls).isNotEmpty().allMatch("newbie"::equals);
        assertThat(checker.checked).containsOnly(RelationTuple.directMember("newbie", 대형조직));
        assertThat(state.groups.get(대형조직).members()).hasSize(멤버수 + 1);
    }

    @Test
    @DisplayName("한 명을 뺄 때도 그 한 명만 본다")
    void 한명_빼기() {
        // when
        useCase.changeGroup(대형조직, GroupChange.delta().removingId("u0")).block(Duration.ofSeconds(10));

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMembersCalls).containsExactly(Set.of(MemberRef.user("u0"), MemberRef.group("u0")));
        assertThat(state.findUserCalls).isNotEmpty().allMatch("u0"::equals);
        assertThat(checker.checked).containsOnly(RelationTuple.directMember("u0", 대형조직));
        assertThat(writer.deleted).containsExactly(RelationTuple.directMember("u0", 대형조직));
        assertThat(state.groups.get(대형조직).members()).hasSize(멤버수 - 1);
    }

    @Test
    @DisplayName("전체 교체는 멤버 키를 한 번 읽고 바뀐 멤버만 본다")
    void 전체_교체() {
        // given — 한 명 빠지고 한 명 들어온 목록
        Set<MemberRef> 목표 = new LinkedHashSet<>(전원);
        목표.remove(MemberRef.user("u0"));
        목표.add(MemberRef.user("newbie"));

        // when
        useCase.changeGroup(대형조직, GroupChange.delta().replacing(목표)).block(Duration.ofSeconds(10));

        // then
        assertThat(state.findMemberRefsCalls).containsExactly(대형조직);
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findUserCalls).isNotEmpty().allMatch(id -> id.equals("u0") || id.equals("newbie"));
        assertThat(checker.checked).containsOnly(
                RelationTuple.directMember("u0", 대형조직), RelationTuple.directMember("newbie", 대형조직));
        assertThat(state.groups.get(대형조직).members()).isEqualTo(목표);
    }
}
```

- [ ] **Step 3: 어긋남·경합·없는 조직·모호한 빼기 테스트를 더한다** — `IncrementalSyncDriftTest` 에. import 에 `dev.starryeye.organization.core.model.GroupChange` 를 더한다.

락 실패 테스트(`락을_못_잡으면_쓰지_않는다`)의 `@DisplayName` 을 `"락을 못 잡으면 다섯 변경 경로 모두 아무것도 쓰지 않고 실패한다"` 로 바꾸고 `removeGroup` 단언 뒤에 더한다:

```java
        assertThatThrownBy(() -> useCase.changeGroup("DEV001",
                GroupChange.delta().adding(Set.of(MemberRef.user("kim")))).block())
                .isInstanceOf(LockUnavailableException.class);
```

클래스 끝에 더한다:

```java
    @Test
    @DisplayName("조직 변경은 요청에 나온 멤버의 어긋남만 고친다 — 나오지 않은 멤버의 잘못 남은 튜플은 그대로다(조직 멤버 PATCH 설계 §11)")
    void 조직_변경은_나온_멤버만_고친다() {
        // given — kim 은 비활성인데 튜플이 남아 있다
        state.users.put("kim", 직원("kim", false));
        state.users.put("park", 직원("park", true));
        state.users.put("lee", 직원("lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("park"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
        checker.allowed.add(RelationTuple.directMember("park", "DEV001"));

        // when — lee 만 넣는다
        useCase.changeGroup("DEV001", GroupChange.delta().adding(Set.of(MemberRef.user("lee")))).block();

        // then — kim 은 요청에 없어 점검하지 않는다. 옛 방식(upsertGroup)은 여기서 kim 을 지웠다
        assertThat(writer.deleted).doesNotContain(RelationTuple.directMember("kim", "DEV001"));
        assertThat(writer.written).contains(RelationTuple.directMember("lee", "DEV001"));
    }

    @Test
    @DisplayName("요청에 나온 멤버는 전후가 같아도 어긋남을 고친다 — IdP 의 재전송이 흔한 복구 경로다")
    void 나온_멤버는_고친다() {
        // given — kim 은 비활성인데 튜플이 남아 있다
        state.users.put("kim", 직원("kim", false));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));

        // when — 이미 멤버인 kim 을 다시 넣는다
        useCase.changeGroup("DEV001", GroupChange.delta().adding(Set.of(MemberRef.user("kim")))).block();

        // then
        assertThat(writer.deleted).contains(RelationTuple.directMember("kim", "DEV001"));
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("변경은 락을 잡은 뒤의 상태에 적용된다 — 그 사이 다른 요청이 넣은 멤버를 지우지 않는다(설계 §1.3)")
    void 사이에_들어온_멤버를_지우지_않는다() {
        // given — lee 추가를 정리해 둔다. 정리는 저장소를 읽지 않는다
        state.users.put("park", 직원("park", true));
        state.users.put("lee", 직원("lee", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of()));
        var lee추가 = GroupChange.delta().adding(Set.of(MemberRef.user("lee")));
        // 그 사이 다른 요청이 park 를 넣었다
        useCase.changeGroup("DEV001", GroupChange.delta().adding(Set.of(MemberRef.user("park")))).block();

        // when
        useCase.changeGroup("DEV001", lee추가).block();

        // then
        assertThat(state.groups.get("DEV001").members())
                .containsExactlyInAnyOrder(MemberRef.user("park"), MemberRef.user("lee"));
    }

    @Test
    @DisplayName("조직이 없으면 빈 결과이고 아무것도 쓰지 않는다")
    void 없는_조직은_빈_결과다() {
        // given
        state.users.put("kim", 직원("kim", true));

        // when
        var result = useCase.changeGroup("NONE", GroupChange.delta().adding(Set.of(MemberRef.user("kim"))))
                .blockOptional(Duration.ofSeconds(10));

        // then
        assertThat(result).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups).doesNotContainKey("NONE");
        assertThat(lock.released).as("빈 결과여도 락은 반납된다").hasValue(1);
    }

    @Test
    @DisplayName("전체 교체 뒤의 id 빼기가 직원·하위 조직 둘 다에 맞으면 락 안에서 현재상태로 한쪽만 뺀다")
    void 교체_뒤_모호한_빼기는_현재상태로_고른다() {
        // given — 조직 X 가 현재상태에 있다
        state.users.put("X", 직원("X", true));
        state.groups.put("X", new DirectoryGroup("X", "cn=X", "엑스팀", Set.of()));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부", Set.of()));
        var change = GroupChange.delta()
                .replacing(Set.of(MemberRef.user("X"), MemberRef.group("X")))
                .removingId("X");

        // when
        useCase.changeGroup("DEV001", change).block();

        // then — 조직 X 가 있으므로 하위 조직 쪽을 뺀다
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("X"));
    }
```

- [ ] **Step 4: 순환 검사·참조 조직이 파티션을 통째로 읽지 않는 테스트** — `IncrementalSyncReadScopeTest` 끝에 더한다.

```java
    @Test
    @DisplayName("하위 조직을 참조하는 조직을 만들어도 그 하위 조직의 파티션을 통째로 읽지 않는다 — 존재 확인도 순환 검사도")
    void 참조한_하위_조직을_통째로_읽지_않는다() {
        // when — 300명짜리 PLANT 를 하위 조직으로 둔 새 조직을 만든다
        useCase.upsertGroup(new DirectoryGroup("NEW", "ou=new", "새 조직", Set.of(MemberRef.group(대형조직))))
                .block(Duration.ofSeconds(10));

        // then
        assertThat(state.findGroupCalls).as("존재는 헤더로, 하위 조직 id 는 하위 조직 줄로").doesNotContain(대형조직);
        assertThat(state.findGroupHeaderCalls).contains(대형조직);
        assertThat(state.findChildGroupIdsCalls).contains(대형조직);
    }
```

그리고 `IncrementalSyncUseCaseTest` 의 `순환_검사는_같은_조직을_다시_읽지_않는다` 에서 `state.findGroupCalls.clear();` 를 `state.findChildGroupIdsCalls.clear();` 로, 단언 두 줄을 아래로 바꾼다:

```java
        assertThat(state.findChildGroupIdsCalls).filteredOn("SHARED"::equals).hasSize(1);
        assertThat(state.findChildGroupIdsCalls).filteredOn("LEAF"::equals).hasSize(1);
```

- [ ] **Step 5: 실패를 확인한다**

Run: `./gradlew :core:test --tests '*GroupChange*' --tests '*IncrementalSyncDriftTest*' --tests '*IncrementalSyncReadScopeTest*' --tests '*IncrementalSyncUseCaseTest*'`
Expected: FAIL — `changeGroup` 이 없어 컴파일 오류.

- [ ] **Step 6: 구현한다** — `IncrementalSyncUseCase`. import 에 `dev.starryeye.organization.core.model.GroupChange`, `java.util.function.Predicate` 를 더한다.

`upsertGroupInternal` 바로 아래에:

```java
    /**
     * 조직 PATCH·PUT (조직 멤버 PATCH 설계 §5). 조직이 없으면 빈 {@code Mono} 다.
     *
     * <p><b>모든 판단을 락을 잡은 뒤 읽은 값으로 한다.</b> 전에는 핸들러가 락 밖에서 읽은 멤버 목록으로 목표를 계산해, 동시에 온 두
     * PATCH 가 서로가 넣은 멤버를 지웠다(설계 §1.3). {@link GroupChange} 는 저장소를 읽지 않고 만들어진다.
     *
     * <p><b>바뀌는 멤버만 담은 조직</b>을 {@link #diffAndApply} 에 넣는다. 멤버십에서 나오는 튜플은
     * {@code direct_member(user:X, group:G)}·{@code child(group:S, group:G)} 뿐이라 한 멤버와 이 조직만 언급한다 — 그림에 없는 멤버의
     * 튜플은 델타에 들어오지 않는다. 이미 있는 조직이라 상위 조직과의 child 엣지도 바뀌지 않으므로 상위 조직을 싣지 않는다
     * ({@link #upsertGroup} 이 싣는 이유는 새로 생기는 조직이다). 그래서 비용이 조직 크기가 아니라 바뀌는 멤버 수를 따른다.
     * {@link #직원한명_그림} 과 같은 논리다.
     *
     * <p>증분은 요청에 나온 멤버를 전후가 같아도 그림에 남긴다 — 그 멤버의 어긋남은 지금처럼 고친다. 요청에 나오지 않은 멤버는
     * 점검하지 않는다(설계 §11). 전체 교체는 목록이 전원을 가리키므로 바뀌는 멤버만 싣는다.
     */
    public Mono<IncrementalSyncResult> changeGroup(String groupId, GroupChange change) {
        return withLock(lease -> changeGroupInternal(groupId, change, lease));
    }

    private Mono<IncrementalSyncResult> changeGroupInternal(String groupId, GroupChange change, LockLease lease) {
        return state.findGroupHeader(groupId)
                .flatMap(header -> 바뀌는_멤버(groupId, change).flatMap(전후 -> {
                    GroupHeader 바뀐헤더 = change.applyTo(header);
                    DirectoryGroup 전 = new DirectoryGroup(groupId, header.externalId(), header.displayName(), 전후.전());
                    DirectoryGroup 후 = new DirectoryGroup(groupId, 바뀐헤더.externalId(), 바뀐헤더.displayName(), 전후.후());

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        DirectoryGroup reconciled = reconcileGroupMembers(전, 후, beforeTuples, afterTuples, result);
                        Set<MemberRef> 넣을것 = 차집합(reconciled.members(), 전.members());
                        Set<MemberRef> 뺄것 = 차집합(전.members(), reconciled.members());
                        return Mono.defer(() -> state.saveGroupChange(바뀐헤더, 넣을것, 뺄것));
                    };

                    return diffAndApply(snapshotOfGroups(Set.of(전)), snapshotOfGroups(Set.of(후)),
                            RelationTuple.groupRef(groupId), lease, commit);
                }));
    }

    /** 그림에 실을 멤버의 변경 전·후 소속. 둘 다 이 조직 멤버 중 일부다. */
    private record 멤버전후(Set<MemberRef> 전, Set<MemberRef> 후) {
    }

    private Mono<멤버전후> 바뀌는_멤버(String groupId, GroupChange change) {
        if (change.replacesMembers()) {
            return state.findMemberRefs(groupId)
                    .collect(LinkedHashSet<MemberRef>::new, Set::add)
                    .flatMap(지금 -> 종류판정(groupId, change, change.base()).map(조직이면 -> {
                        Set<MemberRef> 목표 = change.replay(change.base(), 조직이면);
                        return new 멤버전후(차집합(지금, 목표), 차집합(목표, 지금));
                    }));
        }
        return state.findMembers(groupId, change.mentioned())
                .flatMap(지금 -> 종류판정(groupId, change, 지금)
                        .map(조직이면 -> new 멤버전후(지금, change.replay(지금, 조직이면))));
    }

    /**
     * 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 id 로 빼면 — 전처럼 현재상태에 그 조직이 있으면 하위 조직을 뺀다
     * ({@code StateMemberTypeResolver} 의 순서). 모호할 수 있는 id 만 묻는다.
     */
    private Mono<Predicate<String>> 종류판정(String groupId, GroupChange change, Set<MemberRef> start) {
        Set<String> ids = change.ambiguousIds(start);
        if (ids.isEmpty()) {
            return Mono.<Predicate<String>>just(id -> false);
        }
        ids.forEach(id -> log.warn("members[value eq \"{}\"] 가 직원과 하위 조직 양쪽에 걸립니다. 현재상태로 한쪽만 지웁니다: 조직={}",
                id, groupId));
        return Flux.fromIterable(ids)
                .filterWhen(id -> state.findGroupHeader(id).hasElement())
                .collect(Collectors.toSet())
                .<Predicate<String>>map(조직 -> 조직::contains);
    }

    private static Set<MemberRef> 차집합(Set<MemberRef> from, Set<MemberRef> minus) {
        Set<MemberRef> result = new LinkedHashSet<>(from);
        result.removeAll(minus);
        return result;
    }
```

`childIdsOf` 의 저장소 읽기를 하위 조직 id 로 바꾼다(`if (scan.budget-- <= 0)` 블록 아래의 `return state.findGroup(...)` 전체):

```java
        return state.findChildGroupIds(groupId)
                .collectList()
                .doOnNext(ids -> scan.childIds.put(groupId, ids));
```

`childIdsOf` 자바독 "없는 조직은 빈 목록으로 캐시한다" 는 그대로 맞다(없는 조직은 빈 목록이 나온다).

`expandWithReferencedGroups` 의 읽기를 헤더로 바꾼다(`Flux.fromIterable(missingIds)` 부분):

```java
        return Flux.fromIterable(missingIds)
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .map(header -> new DirectoryGroup(header.id(), header.externalId(), header.displayName(), Set.of()))
```

(뒤의 `.collect(...)`·`.map(loaded -> ...)` 는 그대로다. 자바독에 "존재만 확인하므로 헤더만 읽는다 — 파티션을 통째로 읽으면 하위 조직의 직원 줄까지 읽는다(조직 멤버 PATCH 설계 §1.4)" 한 줄을 더한다.)

- [ ] **Step 7: 통과를 확인한다**

Run: `./gradlew :core:test`
Expected: PASS (등가 16, 비용 3, 어긋남 쪽 새 5, 읽기 범위 새 1 포함 전부)

- [ ] **Step 8: 커밋한다**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/
git commit -m "feat: 조직 변경을 락 안에서 바뀌는 멤버만 담은 그림으로 처리한다 — 순환 검사·참조 조직도 파티션을 통째로 읽지 않는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 4: SCIM — PATCH 를 `GroupChange` 로, 값 붙은 remove 거절, 응답 204, PUT 도 같은 길

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java:33-154` (조직 쪽 전부)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java` (`create`·`replace`·`patch`)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/StateMemberTypeResolver.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 `GroupChange`, Task 3 `IncrementalSyncUseCase.changeGroup`
- Produces: `public static Mono<GroupChange> ScimPatchApplier.toGroupChange(ScimPatchOp patch, MemberTypeResolver resolver)`, `static final String ScimPatchApplier.REMOVE_WITH_VALUE`. `ScimPatchApplier.applyToGroup` 은 없어진다.

- [ ] **Step 1: 기존 조직 테스트를 새 API 로 옮긴다** — `ScimPatchApplierTest` 의 조직 테스트는 모두 `ScimPatchApplier.applyToGroup(before, <패치>, <resolver>).block()` 모양이다. 도우미를 더하고 모두 `적용한다(before, <패치>, <resolver>)` 로 바꾼다(`assertThatThrownBy(() -> ...)` 안의 것도). import 에 `dev.starryeye.organization.core.model.GroupChange`, `java.util.function.Predicate` 를 더한다.

```java
    /** 모호하지 않으면 부르지 않아야 한다. */
    private static final Predicate<String> 부르면_안된다 = id -> {
        throw new AssertionError("모호하지 않은데 종류를 물었다: " + id);
    };

    /** 조직 PATCH 를 변경으로 정리해 before 에 적용한 결과. 멤버십을 보는 판단(종류 모르는 빼기)은 {@code 조직이면} 이 한다. */
    private static DirectoryGroup 적용한다(DirectoryGroup before, ScimPatchOp patch, MemberTypeResolver resolver,
                                      Predicate<String> 조직이면) {
        return ScimPatchApplier.toGroupChange(patch, resolver).block().applyTo(before, 조직이면);
    }

    private static DirectoryGroup 적용한다(DirectoryGroup before, ScimPatchOp patch, MemberTypeResolver resolver) {
        return 적용한다(before, patch, resolver, 부르면_안된다);
    }
```

`필터_remove는_종류를_구분한다`(직원·하위 조직 같은 id) 는 종류 판정이 유스케이스로 옮겨졌으므로 이렇게 바꾼다:

```java
    @Test
    @DisplayName("직원과 하위 조직이 같은 id 를 쓰면 필터 remove 가 한쪽만 지운다 — 종류는 적용할 때 현재상태로 고른다")
    void 필터_remove는_종류를_구분한다() {
        // given — 조직코드와 직원 아이디는 서로 다른 네임스페이스라 겹칠 수 있다
        var before = 조직(MemberRef.user("X"), MemberRef.group("X"));

        // when — 현재상태에 조직 X 가 있다(유스케이스는 락 안에서 findGroupHeader 로 판정한다)
        var after = 적용한다(before, 패치("remove", "members[value eq \"X\"]", null), USER_ONLY, id -> true);

        // then
        assertThat(after.members()).containsExactly(MemberRef.user("X"));
    }
```

`type이_없으면_현재상태로_판정한다` 끝에 단언을 하나 더한다(Review Focus 5):

```java
        assertThat(state.findGroupCalls).as("존재만 보면 되므로 조직 파티션을 통째로 읽지 않는다").isEmpty();
```

- [ ] **Step 2: 새 테스트를 더한다** — `ScimPatchApplierTest` 끝에.

```java
    // ---------- 조직 멤버 PATCH (설계 §4·§7) ----------

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기(값 붙은 remove members)는 400 invalidValue 로 거절하고 필터와 옵션을 안내한다")
    void 값_붙은_remove는_거절한다() {
        // given — MS 호환성 문서의 기본 모드 예시 그대로
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("Remove", "members", List.of(Map.of("value", "u1091")))));

        // when, then
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(patch, USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class, e -> {
                    assertThat(e.getScimType()).isEqualTo("invalidValue");
                    assertThat(e.getMessage()).isEqualTo(ScimPatchApplier.REMOVE_WITH_VALUE)
                            .contains("members[value eq \"<id>\"]").contains("?aadOptscim062020");
                });
    }

    @Test
    @DisplayName("값이 빈 목록이어도 거절한다 — 아무도 안 빼는지 다 빼는지 뜻이 갈린다")
    void 빈_목록_값도_거절한다() {
        assertThatThrownBy(() -> ScimPatchApplier.toGroupChange(패치("remove", "members", List.of()), USER_ONLY).block())
                .isInstanceOfSatisfying(ScimException.class,
                        e -> assertThat(e.getScimType()).isEqualTo("invalidValue"));
    }

    @Test
    @DisplayName("값이 null 이면 표준대로 전원 빼기다")
    void 값이_null이면_전원_빼기다() {
        // given — "value": null 은 DTO 에서 null 이라 값 없음과 같다(Keycloak 커뮤니티 플러그인이 조직을 비울 때 보낸다)

        // when
        var change = ScimPatchApplier.toGroupChange(패치("remove", "members", null), USER_ONLY).block();

        // then
        assertThat(change.replacesMembers()).isTrue();
        assertThat(change.base()).isEmpty();
    }

    @Test
    @DisplayName("Entra 옵션 모드(aadOptscim062020)의 멤버 빼기는 id 빼기다")
    void Entra_옵션_모드_빼기() {
        // given — MS 호환성 문서의 옵션 모드 예시 그대로
        var patch = 패치("remove", "members[value eq \"7f4bc1a3-285e-48ae-8202-5accb43efb0e\"]", null);

        // when
        var change = ScimPatchApplier.toGroupChange(patch, USER_ONLY).block();

        // then
        assertThat(change.replacesMembers()).isFalse();
        assertThat(change.ops()).containsExactly(new GroupChange.RemoveId("7f4bc1a3-285e-48ae-8202-5accb43efb0e"));
    }

    @Test
    @DisplayName("Okta 의 add·필터 remove·replace 는 증분 추가·id 빼기·전체 교체다")
    void Okta_모양() {
        // given — Okta SCIM 2.0 문서 예시 모양(add 에는 type 없이 display 가 온다)
        var 추가 = 패치("add", "members", List.of(Map.of("value", "23a35c27", "display", "test.user@okta.local")));
        var 빼기 = 패치("remove", "members[value eq \"89bb1940\"]", null);
        var 교체 = 패치("replace", "members", List.of(Map.of("value", "23a35c27"), Map.of("value", "89bb1940")));

        // when
        var 추가변경 = ScimPatchApplier.toGroupChange(추가, USER_ONLY).block();
        var 빼기변경 = ScimPatchApplier.toGroupChange(빼기, USER_ONLY).block();
        var 교체변경 = ScimPatchApplier.toGroupChange(교체, USER_ONLY).block();

        // then
        assertThat(추가변경.ops()).containsExactly(new GroupChange.Add(MemberRef.user("23a35c27")));
        assertThat(빼기변경.ops()).containsExactly(new GroupChange.RemoveId("89bb1940"));
        assertThat(교체변경.base()).containsExactlyInAnyOrder(MemberRef.user("23a35c27"), MemberRef.user("89bb1940"));
    }

    @Test
    @DisplayName("PATCH 정리는 멤버십을 읽지 않는다 — 조직이 없어도 변경이 만들어진다")
    void 정리는_멤버십을_읽지_않는다() {
        // when
        var change = ScimPatchApplier.toGroupChange(
                패치("add", "members", List.of(멤버("kim", "User"))), USER_ONLY).block();

        // then
        assertThat(change).isEqualTo(GroupChange.delta().adding(java.util.Set.of(MemberRef.user("kim"))));
    }
```

- [ ] **Step 3: 핸들러 테스트를 고치고 더한다** — `ScimGroupHandlerTest`.

`PATCH로_멤버를_추가한다` 를 204 로 바꾼다:

```java
    @Test
    @DisplayName("PATCH 로 멤버를 추가하면 튜플이 생성되고 본문 없이 204 가 돌아온다")
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
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }
```

클래스 끝(`지원기능을_선언한다` 위)에 더한다:

```java
    @Test
    @DisplayName("없는 조직에 PATCH 하면 404 이고 아무것도 쓰지 않는다")
    void 없는_조직_PATCH는_404다() {
        // given
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"kim","type":"User"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/NONE")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isNotFound();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기는 400 invalidValue 이고 멤버도 튜플도 그대로다")
    void Entra_기본_모드_빼기는_400이다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        String patch = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"Remove","path":"members","value":[{"value":"kim"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(patch)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("aadOptscim062020"));

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("kim"));
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("PUT 은 전체 교체로 처리하고 200 과 리소스를 돌려준다")
    void PUT은_전체_교체다() {
        // given
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", "DEV002", "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"DEV002","displayName":"플랫폼팀",
                 "members":[{"value":"lee","type":"User"}]}
                """;

        // when, then
        client.put().uri("/scim/v2/Groups/DEV002")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("플랫폼팀")
                .jsonPath("$.members[0].value").isEqualTo("lee");

        assertThat(state.groups.get("DEV002").members()).containsExactly(MemberRef.user("lee"));
    }

    @Test
    @DisplayName("없는 조직에 PUT 하면 404 다")
    void 없는_조직_PUT은_404다() {
        String body = """
                {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                 "externalId":"NONE","displayName":"없음","members":[]}
                """;
        client.put().uri("/scim/v2/Groups/NONE")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }
```

- [ ] **Step 4: 실패를 확인한다**

Run: `./gradlew :connector-scim:test --tests '*ScimPatchApplierTest*' --tests '*ScimGroupHandlerTest*'`
Expected: FAIL — `toGroupChange` 가 없어 컴파일 오류.

- [ ] **Step 5: `ScimPatchApplier` 조직 쪽을 바꾼다** — `MEMBER_VALUE_FILTER` 는 그대로 두고, 생성자 다음의 `applyToGroup` 부터 `withMembers` 까지(조직 쪽 전부)를 아래로 바꾼다. `toMemberRefs`·`memberRef` 는 그대로 쓴다. import 에서 `DirectoryGroup` 을 빼고 `GroupChange`·`java.util.Set` 을 더한다(`Set` 은 이미 있다). `log` 를 더 쓰지 않으면 `@Slf4j`·import 를 뺀다.

```java
    /** 값 붙은 remove members — RFC 7644 에 없는 모양이라 "그 멤버만" 으로 추측하지 않는다(조직 멤버 PATCH 설계 §2·§7). */
    static final String REMOVE_WITH_VALUE = "members 에서 멤버를 골라 빼려면 path 에 필터를 쓰세요: members[value eq \"<id>\"]. "
            + "Microsoft Entra ID 는 SCIM 테넌트 URL 에 ?aadOptscim062020 을 붙이면 이 형식으로 보냅니다.";

    /**
     * 조직 PATCH 를 <b>저장소를 읽지 않고</b> {@link GroupChange} 로 정리한다(조직 멤버 PATCH 설계 §4). 연산은 배열 순서대로 쌓인다.
     * {@code type} 이 빠진 멤버의 종류만 {@code resolver} 로 판정한다. 멤버십을 보고 하는 판단(지금 멤버인가, id 빼기가 직원·하위
     * 조직 중 무엇인가)은 유스케이스가 락 안에서 한다 — 락 밖에서 읽은 목록으로 계산하면 동시에 온 PATCH 가 서로를 지운다.
     */
    public static Mono<GroupChange> toGroupChange(ScimPatchOp patch, MemberTypeResolver resolver) {
        Mono<GroupChange> current = Mono.just(GroupChange.delta());
        for (ScimOperation operation : operations(patch)) {
            current = current.flatMap(change -> applyOne(change, operation, resolver));
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

    private static Mono<GroupChange> applyOne(GroupChange change, ScimOperation operation,
                                              MemberTypeResolver resolver) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeGroupAttributes(change, asAttributeMap(operation.value()), resolver);
        }

        Matcher filter = MEMBER_VALUE_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            return Mono.just(change.removingId(IdNormalizer.normalize(filter.group("value"))));
        }

        if (path.trim().equalsIgnoreCase("members")) {
            return switch (op) {
                case "add" -> toMemberRefs(operation.value(), resolver).map(change::adding);
                case "remove" -> {
                    // RFC 7644 §3.5.2.2 — 필터 없는 remove 는 전원 삭제다. remove 의 value 는 RFC 가 정하지 않은 칸이다
                    if (operation.value() != null) {
                        throw ScimException.invalidValue(REMOVE_WITH_VALUE);
                    }
                    yield Mono.just(change.replacing(Set.of()));
                }
                case "replace" -> toMemberRefs(operation.value(), resolver).map(change::replacing);
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equalsIgnoreCase("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return Mono.just(change.renamed(asString(operation.value())));
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    private static Mono<GroupChange> mergeGroupAttributes(GroupChange change, Map<String, Object> attributes,
                                                          MemberTypeResolver resolver) {
        GroupChange renamed = has(attributes, "displayName")
                ? change.renamed(asString(attribute(attributes, "displayName")))
                : change;
        return has(attributes, "members")
                ? toMemberRefs(attribute(attributes, "members"), resolver).map(renamed::replacing)
                : Mono.just(renamed);
    }
```

클래스 자바독 첫 문단 끝에 "조직은 {@link GroupChange} 로 정리만 하고 적용은 유스케이스가 한다." 를 더한다. `applyToGroup` 자바독(현재상태 조회 설명)은 없어진다.

- [ ] **Step 6: `ScimGroupHandler` 를 바꾼다** — import 에 `dev.starryeye.organization.core.model.GroupChange` 를 더하고 `DirectoryGroup` 을 더 안 쓰면 뺀다.

`create` 의 존재 확인:

```java
                .flatMap(group -> state.findGroupHeader(group.id())
                        .flatMap(existing -> Mono.<DirectoryGroup>error(ScimException.uniqueness(
                                "이미 존재하는 조직입니다: " + group.id())))
                        .switchIfEmpty(Mono.just(group)))
```

(`DirectoryGroup` import 는 이 `Mono.<DirectoryGroup>` 때문에 남는다.)

`replace`:

```java
    /** PUT — 전체 교체로 처리한다(조직 멤버 PATCH 설계 §4). 존재 확인은 락 안에서 한다. */
    public Mono<ServerResponse> replace(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request).flatMap(projection -> request.bodyToMono(ScimGroup.class)
                .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다")))
                .flatMap(scim -> ScimMapper.toDirectoryGroup(scim, memberTypes))
                // 경로의 조직코드가 정본이다. 본문의 externalId 가 달라도 리소스를 옮기지 않는다.
                .map(group -> GroupChange.replacement(group.externalId(), group.displayName(), group.members()))
                .flatMap(change -> sync.changeGroup(id, change)
                        .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id))))
                .flatMap(result -> respond(HttpStatus.OK, id, result, projection)));
    }
```

`patch`:

```java
    /**
     * 조직 PATCH — 성공하면 본문 없이 204 다(조직 멤버 PATCH 설계 §7). RFC 7644 §3.5.2 가 허용하고, Entra 는 멤버 전체를 담은
     * 본문을 권하지 않는다. 응답을 만들려고 멤버를 읽지 않는다. 잘못된 attributes 는 지금처럼 쓰기 전에 400 이다.
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        String id = request.pathVariable("id");
        return projection(request)
                .then(request.bodyToMono(ScimPatchOp.class)
                        .switchIfEmpty(Mono.error(ScimException.invalidSyntax("요청 본문이 비어 있습니다"))))
                .flatMap(patch -> ScimPatchApplier.toGroupChange(patch, memberTypes))
                .flatMap(change -> sync.changeGroup(id, change)
                        .switchIfEmpty(Mono.error(ScimException.notFound("조직을 찾을 수 없습니다: " + id))))
                .flatMap(result -> result.fullyApplied()
                        ? ServerResponse.noContent().build()
                        : Mono.error(ScimException.internal("일부 튜플 적용에 실패했습니다. 재시도해 주세요: " + id)));
    }
```

`byProjection` 자바독의 "쓰기 응답(create/replace/patch)" 을 "쓰기 응답(create/replace)" 로 고친다.

- [ ] **Step 7: `StateMemberTypeResolver` 가 헤더만 읽게 한다**

```java
    @Override
    public Mono<MemberType> resolve(String id) {
        // 존재만 보면 된다 — findGroup 은 그 조직의 멤버 줄까지 통째로 읽는다(조직 멤버 PATCH 설계 §1.4)
        return state.findGroupHeader(id)
                .map(header -> MemberType.GROUP)
                .switchIfEmpty(Mono.defer(() -> state.findUser(id).map(user -> MemberType.USER)))
                .defaultIfEmpty(MemberType.USER)
                .doOnNext(type -> log.warn(
                        "SCIM 멤버에 type 이 없어 현재상태로 추정합니다: value='{}', 추정={}", id, type));
    }
```

- [ ] **Step 8: 통과를 확인한다**

Run: `./gradlew :connector-scim:test`
Expected: PASS

- [ ] **Step 9: 커밋한다**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java connector-scim/src/main/java/dev/starryeye/organization/scim/ScimGroupHandler.java connector-scim/src/main/java/dev/starryeye/organization/scim/StateMemberTypeResolver.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java
git commit -m "feat: 조직 PATCH·PUT 을 락 안의 조직 변경으로 — 값 붙은 remove members 는 400, PATCH 응답은 204

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 5: E2E·기존 테스트의 응답 코드·README

**Files:**
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchEndToEndTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimEndToEndTest.java:131-136`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimDriftHealingEndToEndTest.java:80-130`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java` (조직 PATCH 기대 코드 200 → 204: 185, 310, 328, 342, 365, 366, 416, 485 줄)
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLimitsAndRecoveryScaleTest.java` (조직 PATCH 기대 코드 200 → 204: 108-110, 111, 257, 258-259, 283 줄)
- Modify: `README.md` (`## SCIM` 의 PATCH 표와 그 아래)

**Interfaces:**
- Consumes: Task 4 까지의 동작(조직 PATCH 204, 값 붙은 remove 400, PUT 200)

- [ ] **Step 1: E2E 를 쓴다** — `ScimGroupMemberPatchEndToEndTest`. 컨테이너·도우미는 `ScimDriftHealingEndToEndTest` 와 같은 모양이다.

```java
package dev.starryeye.organization.scim.app;

import com.fasterxml.jackson.databind.JsonNode;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조직 멤버 PATCH 를 실제 컨테이너 위에서 IdP 문서의 모양 그대로 보낸다 (조직 멤버 PATCH 설계 §8.4).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimGroupMemberPatchEndToEndTest {

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

    private void 직원을_만든다(String userName) {
        client.post().uri("/scim/v2/Users").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
                         "userName":"%s","displayName":"%s","active":true}
                        """.formatted(userName, userName))
                .exchange().expectStatus().isCreated();
    }

    private void 조직을_만든다(String code, String... userNames) {
        String members = String.join(",", java.util.Arrays.stream(userNames)
                .map(name -> "{\"value\":\"%s\",\"type\":\"User\"}".formatted(name)).toList());
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],
                         "externalId":"%s","displayName":"%s","members":[%s]}
                        """.formatted(code, code, members))
                .exchange().expectStatus().isCreated();
    }

    private WebTestClient.ResponseSpec 패치(String code, String operations) {
        return client.patch().uri("/scim/v2/Groups/" + code).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange();
    }

    private List<String> 멤버들(String code) {
        JsonNode group = client.get().uri("/scim/v2/Groups/" + code).exchange()
                .expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        List<String> values = new ArrayList<>();
        if (group.has("members")) {
            group.get("members").forEach(member -> values.add(member.get("value").asText()));
        }
        return values;
    }

    @Test
    @DisplayName("Okta 식 add(type 없이 display)·필터 remove 는 204 이고 멤버와 권한이 따라온다")
    void Okta_식_추가와_빼기() {
        // given
        직원을_만든다("okta1");
        조직을_만든다("OKTA");

        // when — 추가
        패치("OKTA", """
                [{"op":"add","path":"members","value":[{"value":"okta1","display":"okta1@example.com"}]}]
                """).expectStatus().isNoContent().expectBody().isEmpty();

        // then
        assertThat(멤버들("OKTA")).containsExactly("okta1");
        assertThat(check("user:okta1", "member", "group:OKTA")).isTrue();

        // when — 필터 빼기
        패치("OKTA", """
                [{"op":"remove","path":"members[value eq \\"okta1\\"]"}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("OKTA")).isEmpty();
        assertThat(check("user:okta1", "member", "group:OKTA")).isFalse();
    }

    @Test
    @DisplayName("Entra 기본 모드의 멤버 빼기는 400 invalidValue 이고 멤버도 권한도 그대로다")
    void Entra_기본_모드_빼기는_거절된다() {
        // given
        직원을_만든다("entra1");
        조직을_만든다("ENTRA", "entra1");

        // when, then — MS 호환성 문서의 기본 모드 예시 모양 그대로
        패치("ENTRA", """
                [{"op":"Remove","path":"members","value":[{"value":"entra1"}]}]
                """).expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue")
                .jsonPath("$.detail").value(d -> assertThat((String) d).contains("aadOptscim062020"));

        assertThat(멤버들("ENTRA")).containsExactly("entra1");
        assertThat(check("user:entra1", "member", "group:ENTRA")).isTrue();
    }

    @Test
    @DisplayName("Entra 옵션 모드(aadOptscim062020)의 멤버 빼기는 204 다")
    void Entra_옵션_모드_빼기() {
        // given
        직원을_만든다("entra2");
        조직을_만든다("ENTRA2", "entra2");

        // when
        패치("ENTRA2", """
                [{"op":"remove","path":"members[value eq \\"entra2\\"]"}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("ENTRA2")).isEmpty();
        assertThat(check("user:entra2", "member", "group:ENTRA2")).isFalse();
    }

    @Test
    @DisplayName("replace members 는 목록대로 교체하고 204 다")
    void 전체_교체() {
        // given
        직원을_만든다("r1");
        직원을_만든다("r2");
        직원을_만든다("r3");
        조직을_만든다("REPL", "r1", "r2");

        // when
        패치("REPL", """
                [{"op":"replace","path":"members","value":[{"value":"r2"},{"value":"r3"}]}]
                """).expectStatus().isNoContent();

        // then
        assertThat(멤버들("REPL")).containsExactlyInAnyOrder("r2", "r3");
        assertThat(check("user:r1", "member", "group:REPL")).isFalse();
        assertThat(check("user:r3", "member", "group:REPL")).isTrue();
    }

    @Test
    @DisplayName("같은 조직에 멤버 추가 PATCH 를 동시에 보내도 서로의 멤버를 지우지 않는다")
    void 동시_추가() throws Exception {
        // given
        List<String> 사람들 = List.of("c1", "c2", "c3", "c4", "c5");
        사람들.forEach(this::직원을_만든다);
        조직을_만든다("CONC");

        // when — 동시에 쏜다. 락을 못 잡은 요청(503)은 IdP 처럼 다시 보낸다
        var pool = Executors.newFixedThreadPool(사람들.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            사람들.forEach(id -> futures.add(pool.submit(() -> 넣을때까지("CONC", id))));
            for (Future<Integer> future : futures) {
                assertThat(future.get(1, TimeUnit.MINUTES)).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }

        // then — 락 밖에서 읽은 목록으로 계산하면 먼저 끝난 추가가 나중 요청에 지워진다(설계 §1.3)
        assertThat(멤버들("CONC")).containsExactlyInAnyOrderElementsOf(사람들);
        사람들.forEach(id -> assertThat(check("user:" + id, "member", "group:CONC")).isTrue());
    }

    private int 넣을때까지(String code, String userName) {
        for (int 시도 = 0; 시도 < 10; 시도++) {
            int status = client.patch().uri("/scim/v2/Groups/" + code).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                             "Operations":[{"op":"add","path":"members","value":[{"value":"%s","type":"User"}]}]}
                            """.formatted(userName))
                    .exchange().returnResult(Void.class).getStatus().value();
            if (status != 503) {
                return status;
            }
        }
        return 503;
    }
}
```

- [ ] **Step 2: 실패를 확인한다** — Task 4 가 끝났으므로 이 E2E 는 통과해야 한다. 먼저 기존 E2E 두 개가 새 동작에서 깨지는 것을 본다.

Run: `./gradlew :app-scim:test --tests '*ScimEndToEndTest*' --tests '*ScimDriftHealingEndToEndTest*'`
Expected: FAIL — `ScimEndToEndTest` 의 조직 PATCH 가 204 인데 200 을 기대, `ScimDriftHealingEndToEndTest` 는 조직 PUT 이 더 이상 kim 을 점검하지 않아 튜플이 남는다.

- [ ] **Step 3: 기존 E2E 를 새 동작에 맞춘다**

`ScimEndToEndTest.PATCH로_멤버를_뺀다` 의 `.exchange().expectStatus().isOk();` 를 `.exchange().expectStatus().isNoContent();` 로.

`ScimDriftHealingEndToEndTest` — 클래스 자바독 끝에 한 문단을 더하고 테스트의 이름과 "when" 을 바꾼다:

```java
 * <p>조직 멤버 PATCH 설계(2026-09-26) §11 이후 조직 쓰기는 <b>요청에 나온 멤버</b>만 점검한다 — 그래서 "건드리는" 쓰기는 그 직원을
 * 가리키는 조직 PATCH 다. 그 직원을 가리키지 않는 조직 PUT·PATCH 는 이 튜플을 보지 않는다(직원 쪽 쓰기와 재적재는 여전히 본다).
```

```java
    @Test
    @DisplayName("경합이 남긴 퇴사자 튜플을 그 직원을 가리키는 다음 SCIM 쓰기가 걷어낸다")
    void 어긋난_튜플이_치유된다() {
```

"when" 블록(조직 PUT)을 아래로 바꾼다:

```java
        // when — kim 을 가리키는 조직 쓰기가 한 번 온다(IdP 의 재전송 같은)
        client.patch().uri("/scim/v2/Groups/DEV001").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"kim","type":"User"}]}]}
                        """)
                .exchange().expectStatus().isNoContent();
```

규모 테스트 둘(`ScimScaleScenarioTest`, `ScimLimitsAndRecoveryScaleTest`)에서 `ScimRequestRenderer.멤버추가`·`멤버제거`·`멤버전체제거`·`멤버전체교체` 를 `보낸다(…, 200)` 으로 보내는 곳을 모두 `204` 로 바꾼다. `grep -n "ScimRequestRenderer\.\(멤버추가\|멤버제거\|멤버전체제거\|멤버전체교체\|조직표시명변경\)" -A3` 로 찾는다 — 기대 코드는 호출의 마지막 인자다(여러 줄에 걸친 호출이 있다). `조직교체`(PUT) 는 200 그대로다.

- [ ] **Step 4: 통과를 확인한다** — 하나씩.

Run: `./gradlew :app-scim:test --tests '*ScimGroupMemberPatchEndToEndTest*'`
Expected: PASS (5)

Run: `./gradlew :app-scim:test --tests '*ScimEndToEndTest*' --tests '*ScimDriftHealingEndToEndTest*'`
Expected: PASS

Run: `./gradlew :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL (규모 테스트는 컴파일만 — 실행은 컨트롤러가 한다)

- [ ] **Step 5: README 를 고친다** — `## SCIM` 의 PATCH 표에서 조직 `members` 줄을 바꾼다:

```markdown
| Group | `members` | `add` / `replace` / `remove` — `value` 가 없으면 전원 빼기(RFC 7644 §3.5.2.2), **`value` 가 있으면 400 `invalidValue`** |
```

표 아래 "그 외 path는 … `invalidPath`로 400을 돌려준다" 문단 다음에 넣는다:

```markdown
**조직 PATCH 는 성공하면 `204 No Content` 다**(본문 없음). RFC 7644 §3.5.2 가 허용하고, Entra 는 조직 PATCH 에 멤버 전체를 담아
돌려주는 것을 권하지 않으며 Okta 도 204 를 받는다. PUT 과 직원 PATCH 는 200 과 리소스다.

**Microsoft Entra ID 로 연결할 때는 SCIM 테넌트 URL 끝에 `?aadOptscim062020` 을 반드시 붙인다.** 이 옵션이 없으면 Entra 는 멤버
한 명을 `{"op":"Remove","path":"members","value":[{"value":"…"}]}` 로 빼는데, RFC 7644 로 읽으면 이것은 "멤버 전원 삭제" 이고
`value` 는 remove 에 정의되지 않은 칸이다. 추측하지 않고 400 `invalidValue` 로 거절한다 — 그 멤버는 빠지지 않고 Entra 프로비저닝
로그에 실패로 남는다. 옵션을 켜면 Entra 는 `members[value eq "…"]` 로 보낸다(Okta 는 원래 이 모양이다). 이 옵션은 비활성화·경로
없는 PATCH 의 모양도 표준으로 바꾸는데, 그 모양들은 이미 받는다. 설계: `docs/superpowers/specs/2026-09-26-group-member-patch-design.md`.

**조직 멤버 변경의 비용.** 멤버 추가·빼기와 이름 변경은 조직 크기와 무관하게 요청에 나온 멤버만 읽고 쓴다. 전체 교체(`replace
members`, 경로 없는 `members`, `PUT`)는 저장된 멤버 아이디를 한 번 훑고 바뀐 멤버만 처리한다. 조직 PATCH·PUT 은 요청에 나온(전체
교체는 바뀐) 멤버의 권한만 OpenFGA 와 맞춰 본다 — 조직 전원을 맞추려면 `POST /admin/sync/rebuild?mode=tuples` 다. 요청 본문은
WebFlux 기본 한도(256KB, 멤버 약 7천 명)를 넘으면 받지 못한다.
```

- [ ] **Step 6: 커밋한다**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ README.md
git commit -m "test: 조직 멤버 PATCH E2E — Okta·Entra 모양, 동시 추가, 응답 204 / docs: Entra 는 aadOptscim062020 필수

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

### Task 6: 규모 테스트 — 멤버 10만 명 조직

**Files:**
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/DynamoDbReadCounter.java`
- Create: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java`

**Interfaces:**
- Consumes: Task 2 `findMemberRefs`·`findMembers`, Task 3 `changeGroup`, Task 1 `GroupChange.replacement`
- Produces: `DynamoDbReadCounter.batchGetKeys: AtomicLong`

- [ ] **Step 1: 읽기 계측에 BatchGetItem 키 수를 더한다** — `DynamoDbReadCounter`. import 에 `software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest` 를 더한다.

```java
    final AtomicLong batchGetKeys = new AtomicLong();
```

`reset()` 에 `batchGetKeys.set(0);`, 프록시의 `getItem` 분기 아래에:

```java
                    if (method.getName().equals("batchGetItem") && args != null
                            && args[0] instanceof BatchGetItemRequest request) {
                        request.requestItems().values()
                                .forEach(keys -> batchGetKeys.addAndGet(keys.keys().size()));
                    }
```

- [ ] **Step 2: 규모 테스트를 쓴다** — `ScimGroupMemberPatchScaleTest`

```java
package dev.starryeye.organization.scim.app;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.authz.fixture.ScaleContainers;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멤버 10만 명 조직의 멤버 변경 (조직 멤버 PATCH 설계 §8.5).
 *
 * <p>한 명을 넣고 빼는 PATCH 가 조직 파티션을 훑지 않는다는 것을 <b>읽은 양</b>으로 단정한다 — DynamoDB Local 의 속도는 AWS 와 달라
 * 시간으로는 아무것도 증명하지 못한다. 조직은 저장소에 직접 심는다 — 멤버 줄만 있으면 된다. 바뀌지 않는 멤버의 직원 레코드와
 * 튜플은 이 경로가 보지 않으므로 심지 않는다. 10만 명 전체 교체는 HTTP 본문 한도(256KB)를 넘으므로 유스케이스를 직접 부른다.
 */
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DynamoDbReadCounter.class)
@ScaleTest
class ScimGroupMemberPatchScaleTest {

    private static final int 전체 = 100_000;
    private static final String 조직 = "ALL";

    @Container
    static final GenericContainer<?> OPENFGA = ScaleContainers.openFga();

    @Container
    static final GenericContainer<?> DYNAMODB = ScaleContainers.dynamoDb();

    @DynamicPropertySource
    static void 인프라_주소를_주입한다(DynamicPropertyRegistry registry) {
        ScaleContainers.주소를_등록한다(registry::add, OPENFGA, DYNAMODB);
    }

    @Autowired WebTestClient client;
    @Autowired DirectoryStateRepository state;
    @Autowired IncrementalSyncUseCase sync;
    @Autowired StoreBootstrapper bootstrapper;
    @Autowired DynamoDbReadCounter counter;

    private static String 멤버(int i) {
        return "m%06d".formatted(i);
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    private void 보낸다(String operations) {
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/" + 조직).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":%s}
                        """.formatted(operations))
                .exchange().expectStatus().isNoContent();
    }

    private void 읽은양을_찍는다(String 이름, long 시작) {
        System.out.printf("%s: %,dms, Query %,d번, 훑은 아이템 %,d, GetItem %,d번, BatchGet 키 %,d%n",
                이름, System.currentTimeMillis() - 시작, counter.queries.get(), counter.scannedItems.get(),
                counter.getItems.get(), counter.batchGetKeys.get());
    }

    @Test
    @Order(1)
    @DisplayName("멤버 10만 명 조직과 새 직원 둘을 저장소에 직접 심는다")
    void 심는다() {
        // given
        long 시작 = System.currentTimeMillis();
        Set<MemberRef> 전원 = new LinkedHashSet<>();
        for (int i = 0; i < 전체; i++) {
            전원.add(MemberRef.user(멤버(i)));
        }

        // when
        state.saveUser(new DirectoryUser("newbie1", "ext-newbie1", "newbie1", "신입 1", null, true)).block();
        state.saveUser(new DirectoryUser("newbie2", "ext-newbie2", "newbie2", "신입 2", null, true)).block();
        state.saveGroup(new DirectoryGroup(조직, "ext-" + 조직, "전 직원", 전원)).block(Duration.ofMinutes(30));

        // then
        assertThat(state.findMemberRefs(조직).count().block(Duration.ofMinutes(5))).isEqualTo((long) 전체);
        System.out.printf("심기: 멤버 %,d명, %,dms%n", 전체, System.currentTimeMillis() - 시작);
    }

    @Test
    @Order(2)
    @DisplayName("한 명을 넣는 PATCH 는 조직 파티션을 훑지 않는다 — Query 0번, 읽기 몇 건")
    void 한명을_넣는다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        보낸다("""
                [{"op":"add","path":"members","value":[{"value":"newbie1","type":"User"}]}]
                """);

        // then
        읽은양을_찍는다("한 명 넣기", 시작);
        assertThat(counter.queries.get()).as("조직 파티션을 훑지 않는다").isZero();
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(10);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(2);
        assertThat(check("user:newbie1", "member", "group:" + 조직)).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("한 명을 빼는 PATCH 도 조직 파티션을 훑지 않는다")
    void 한명을_뺀다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        보낸다("""
                [{"op":"remove","path":"members[value eq \\"%s\\"]"}]
                """.formatted(멤버(1)));

        // then
        읽은양을_찍는다("한 명 빼기", 시작);
        assertThat(counter.queries.get()).isZero();
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(2);
        assertThat(state.findMembers(조직, Set.of(MemberRef.user(멤버(1)))).block()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("전체 교체는 멤버 키를 한 번 훑고 바뀐 멤버만 읽는다")
    void 전체_교체는_멤버_키만_훑는다() {
        // given — 지금 목록에서 한 명 빠지고 한 명 들어온 목록
        Set<MemberRef> 목표 = new LinkedHashSet<>(state.findMemberRefs(조직).collectList().block(Duration.ofMinutes(5)));
        목표.remove(MemberRef.user(멤버(2)));
        목표.add(MemberRef.user("newbie2"));
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when — 10만 명 본문은 HTTP 한도(256KB)를 넘으므로 유스케이스를 직접 부른다
        var result = sync.changeGroup(조직, GroupChange.replacement("ext-" + 조직, "전 직원", 목표))
                .block(Duration.ofMinutes(5));

        // then
        읽은양을_찍는다("전체 교체", 시작);
        assertThat(result.fullyApplied()).isTrue();
        assertThat(counter.scannedItems.get()).as("멤버 줄을 한 번만 훑는다").isLessThanOrEqualTo(전체 + 10);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(check("user:newbie2", "member", "group:" + 조직)).isTrue();
        assertThat(state.findMembers(조직, Set.of(MemberRef.user(멤버(2)))).block()).isEmpty();
    }
}
```

- [ ] **Step 3: 컴파일을 확인한다** — 실행은 하지 않는다(규모 테스트는 컨트롤러가 머지 전에 `./gradlew cleanScaleTest scaleTest` 로 돌린다).

Run: `./gradlew :app-scim:compileTestJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋한다**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/DynamoDbReadCounter.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java
git commit -m "test: 멤버 10만 명 조직의 멤버 변경이 파티션을 훑지 않는다 — 규모 테스트

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```

---

## Self-Review (계획 작성자)

- **스펙 대응:** §4 해석 → Task 4. §5 락 안 처리·좁힌 그림·전체 교체 → Task 3. §6 저장소 넷·`childIdsOf`·`expandWithReferencedGroups`·POST 존재 확인 → Task 2·3·4. §7 응답 204·오류 메시지·PUT 200·README → Task 4·5. §8.1 → Task 4, §8.2 → Task 3, §8.3 → Task 2, §8.4 → Task 5, §8.5 → Task 6. §11 의 E2E 변경 → Task 5.
- **타입 일관성:** `GroupChange`(Task 1) 의 메서드 이름을 Task 3·4·6 이 그대로 쓴다. 포트 메서드 넷(Task 2) 을 Task 3 이 쓴다. 가짜의 `findMembersCalls`·`findMemberRefsCalls`·`findChildGroupIdsCalls` 를 Task 3 이 쓴다.
- **바뀌지 않는 것:** `upsertGroup`(POST 가 계속 씀), `removeGroup`, 직원 쪽 연산, LDAP 전체 동기화.
