# 하네스 독립 기대값 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 하네스(`SyncVerifier`)와 프로브(`OpenFgaProbe`)가 운영의 `TupleMapper` 없이 조직도만 보고 계산한 하나의 기대값(`ChartExpectation`)을 쓰게 하고, 조직도가 지워진 멤버십을 기억해 "지웠어야 할 권한이 남았는가" 를 자동으로 묻게 한다.

**Architecture:** `core` testFixtures 에 `Membership` 레코드와 `ChartExpectation` 을 더한다. `OrgChart` 는 세 번째 칸 `지워진멤버십` 을 갖고, `OrgChartEditor.완성()` 이 편집 전후 멤버십 차이를 거기 쌓는다. `SyncVerifier` ②③④와 `OpenFgaProbe.직접_대조한다` 가 `ChartExpectation` 을 쓰고, 규모 테스트의 튜플 수·손 조직도 헬퍼·손 Check 를 옮긴다. 운영 코드(`src/main`)는 바뀌지 않는다.

**Tech Stack:** Java 17, Gradle 멀티모듈(`java-test-fixtures`), JUnit 5, AssertJ, Reactor, Spring Boot Test + Testcontainers(규모 테스트)

**Spec:** `docs/superpowers/specs/2026-09-11-harness-independent-expectation-design.md`

## Global Constraints

- **`src/main` 은 커밋에서 한 줄도 바뀌지 않는다.** 변이 확인용으로 운영 코드를 고치는 것은 임시이며, 확인 직후 `git checkout -- <파일>` 로 되돌리고 `git status --short` 로 `src/main` 변경이 없음을 확인한 뒤에 커밋한다.
- 새 코드는 `src/testFixtures` 와 `src/test` 에만 둔다.
- **`ChartExpectation.java` 는 `TupleMapper` 를 import 하지 않는다.** 둘을 합치지 않는다 (스펙 §3).
- 테스트는 BDD(`// given` / `// when` / `// then` 주석), AssertJ, **한글 `@DisplayName`** 으로 쓴다.
- `DirectorySnapshot`·`DirectoryGroup` 은 `Map.copyOf`/`Set.copyOf` 라 **순회 순서가 JVM 실행마다 달라진다.** 결과나 메시지의 순서가 필요한 곳은 정렬한다.
- 커밋할 때마다 push 한다 (`git push`). 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **서브에이전트는 빠른 모듈 테스트만 돌린다:** `./gradlew :core:test`, `./gradlew :authz-openfga:compileTestFixturesJava`, `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`. **`:app-ldap:test`·`:app-scim:test`(규모 테스트, 수십 분)는 메인 세션만 돌린다.** Gradle 을 동시에 두 개 띄우지 않는다.
- 식별자는 기존 코드처럼 한글 메서드명·영문 타입명을 쓴다.

---

## File Structure

| 파일 | 역할 | 태스크 |
|---|---|---|
| Create `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/Membership.java` | (조직, 멤버) 한 쌍 | 1 |
| Modify `core/src/testFixtures/.../fixture/OrgChart.java` | 3번째 칸 `지워진멤버십`, `멤버십들()` | 1 |
| Modify `core/src/testFixtures/.../fixture/OrgChartEditor.java` | `완성()` 전후 비교, 활성·레코드 편집 4종 | 1 |
| Create `core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartEditorTest.java` | 에디터 기억·편집 검증 | 1 |
| Modify `core/src/test/.../fixture/OrgChartFixtureTest.java` | "부모는 하나 이하" 못박기 | 1 |
| Create `core/src/testFixtures/.../fixture/ChartExpectation.java` | 조직도가 요구하는 것 | 2 |
| Create `core/src/test/.../fixture/ChartExpectationTest.java` | 규칙 단위 테스트 | 2 |
| Create `core/src/test/.../fixture/ChartExpectationAgreementTest.java` | `TupleMapper` 와 일치 (유일한 접점) | 2 |
| Modify `core/src/testFixtures/.../fixture/SyncVerifier.java` | ②③④ 가 `ChartExpectation` 사용 | 3 |
| Modify `core/src/test/.../fixture/SyncVerifierTest.java` | 헬퍼 → 에디터, 새 테스트 2개 | 3 |
| Modify `authz-openfga/src/testFixtures/java/dev/starryeye/organization/authz/fixture/OpenFgaProbe.java` | `ChartExpectation` 사용 + 오버로드 | 4 |
| Modify 4개 규모 테스트의 튜플 수 | `ChartExpectation.of(..).있어야할튜플().size()` | 5 |
| Modify `app-scim/src/test/.../ScimScaleScenarioTest.java` | 손 조직도 헬퍼 2개 → 에디터 | 5 |
| Modify `app-scim/src/test/.../ScimProvisioningOrderScaleTest.java` | S1-a 를 하네스로 | 5 |
| Modify `LdapDeletionGuardScaleTest.java`, `ScimScaleScenarioTest.java` | 손 Check 제거 (변이 증명 후) | 6 (메인 세션) |
| Modify 스펙 §10·§11, 리뷰 문서 §3 | 실측·결과 기록 | 7 (메인 세션) |

---

### Task 0: 기준 실측 (메인 세션)

**Files:** 없음 (scratchpad 에 기록)

- [ ] **Step 1: 기존 결과를 재사용할 수 있는지 본다**

Run: `ls -la app-ldap/build/test-results/test/ app-scim/build/test-results/test/`
그리고: `git log -1 --format=%cI b08839b`

XML 들의 수정 시각이 `b08839b` 커밋 시각보다 뒤이고, 그 뒤로 `app-ldap`/`app-scim`/`core`/`authz-openfga` 의 `src` 가 바뀌지 않았으면(스펙 커밋만 있음) 재사용한다. 아니면 Step 2.

- [ ] **Step 2: (재사용 불가 시) 규모 스위트를 돌린다**

Run: `./gradlew :app-ldap:test :app-scim:test` (timeout 최대, 백그라운드)
Expected: 전부 통과

- [ ] **Step 3: 클래스별 소요 시간을 기록한다**

각 `TEST-*.xml` 의 `<testsuite ... time="...">` 값을 클래스별로 scratchpad 의 `baseline-durations.txt` 에 적는다. Task 7 이 이것과 비교한다.

---

### Task 1: 조직도가 지워진 멤버십을 기억한다

**Files:**
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/Membership.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChart.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChartEditor.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartEditorTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartFixtureTest.java`

**Interfaces:**
- Produces:
  - `record Membership(String 조직, MemberRef 멤버)`
  - `record OrgChart(DirectorySnapshot snapshot, Landmarks landmarks, Set<Membership> 지워진멤버십)` + 2인자 생성자(기억 없음) + `Set<Membership> 멤버십들()`
  - `OrgChartEditor` 의 `비활성으로_바꾼다(String userId)`, `활성으로_바꾼다(String userId)`, `비활성_직원을_넣는다(String orgCode, String userId)`, `직원_레코드만_지운다(String userId)` — 모두 `OrgChartEditor` 반환

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartEditorTest.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 에디터가 <b>지운 것을 기억하는지</b> 확인한다.
 *
 * <p>하네스는 조직도에 있는 멤버십만 묻는다. 지운 멤버십을 잊으면 "지웠어야 할 권한이 남았다"
 * 를 영원히 못 묻는다 — 퇴사자 권한 생존이 정확히 그 모양이다.
 */
class OrgChartEditorTest {

    private final OrgChart chart = OrgChartFixture.오천명();

    @Test
    @DisplayName("최초 조직도는 기억이 비어 있다")
    void 최초_조직도는_기억이_없다() {
        // then
        assertThat(chart.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("직원을 옮기면 옛 조직의 멤버십을 기억한다")
    void 이동하면_옛_소속을_기억한다() {
        // given
        String 직원 = chart.landmarks().L5직속직원();
        String 옛조직 = chart.직속조직(직원);
        String 새조직 = chart.landmarks().대상팀();
        assertThat(새조직).as("전제: 다른 조직으로 옮겨야 한다").isNotEqualTo(옛조직);

        // when
        OrgChart 이동후 = OrgChartEditor.편집한다(chart).직원을_옮긴다(직원, 옛조직, 새조직).완성();

        // then
        assertThat(이동후.지워진멤버십())
                .containsExactly(new Membership(옛조직, MemberRef.user(직원)));
    }

    @Test
    @DisplayName("직원을 지우면 모든 소속 조직의 멤버십을 기억한다 — 겸직이면 둘 다")
    void 삭제하면_모든_소속을_기억한다() {
        // given
        String 겸직 = chart.landmarks().겸직직원();
        var 소속들 = chart.직속조직들(겸직);
        assertThat(소속들).hasSize(2);

        // when
        OrgChart 삭제후 = OrgChartEditor.편집한다(chart).직원을_지운다(겸직).완성();

        // then
        assertThat(삭제후.지워진멤버십()).containsExactlyInAnyOrderElementsOf(
                소속들.stream().map(org -> new Membership(org, MemberRef.user(겸직))).toList());
    }

    @Test
    @DisplayName("조직을 지우면 그 조직 안의 멤버십과 상위 조직과의 연결까지 기억한다")
    void 조직을_지우면_안의_멤버까지_기억한다() {
        // given — 조직 레코드가 통째로 사라지는 경로. 연산마다 기록하는 방식이면 여기서 놓친다
        String 실 = chart.landmarks().삭제할실();
        String 부모 = chart.부모(실);
        var 안의멤버 = chart.snapshot().groups().get(실).members();
        assertThat(안의멤버).isNotEmpty();

        // when
        OrgChart 삭제후 = OrgChartEditor.편집한다(chart).조직을_지운다(실).완성();

        // then
        assertThat(삭제후.지워진멤버십()).contains(new Membership(부모, MemberRef.group(실)));
        assertThat(삭제후.지워진멤버십()).containsAll(
                안의멤버.stream().map(member -> new Membership(실, member)).toList());
    }

    @Test
    @DisplayName("멤버를 통째로 비우면 직원과 하위 조직 멤버십을 모두 기억한다")
    void 통째로_비우면_전부_기억한다() {
        // given
        String 팀 = chart.landmarks().대상팀();
        var 멤버들 = chart.snapshot().groups().get(팀).members();

        // when
        OrgChart 비운후 = OrgChartEditor.편집한다(chart).멤버를_모두_비운다(팀).완성();

        // then
        assertThat(비운후.지워진멤버십()).containsExactlyInAnyOrderElementsOf(
                멤버들.stream().map(member -> new Membership(팀, member)).toList());
    }

    @Test
    @DisplayName("편집을 이어 가면 기억도 이어진다")
    void 기억은_누적된다() {
        // given
        String 첫째 = chart.landmarks().L6직속직원();
        String 둘째 = chart.landmarks().L4직속직원();
        OrgChart 한번 = OrgChartEditor.편집한다(chart).직원을_지운다(첫째).완성();

        // when
        OrgChart 두번 = OrgChartEditor.편집한다(한번).직원을_지운다(둘째).완성();

        // then
        assertThat(두번.지워진멤버십()).contains(
                new Membership(chart.직속조직(첫째), MemberRef.user(첫째)),
                new Membership(chart.직속조직(둘째), MemberRef.user(둘째)));
    }

    @Test
    @DisplayName("비활성으로 바꾸면 멤버십은 그대로라 기억할 것이 없다")
    void 비활성화는_멤버십을_안_지운다() {
        // given
        String 직원 = chart.landmarks().L4직속직원();

        // when
        OrgChart 바꾼후 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(직원).완성();

        // then
        assertThat(바꾼후.snapshot().users().get(직원).active()).isFalse();
        assertThat(바꾼후.직속조직들(직원)).isEqualTo(chart.직속조직들(직원));
        assertThat(바꾼후.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("활성으로 되돌리면 active 만 바뀐다")
    void 재활성화는_active만_바꾼다() {
        // given
        String 직원 = chart.landmarks().L4직속직원();
        OrgChart 비활성 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(직원).완성();

        // when
        OrgChart 되돌린후 = OrgChartEditor.편집한다(비활성).활성으로_바꾼다(직원).완성();

        // then
        assertThat(되돌린후.snapshot().users().get(직원))
                .isEqualTo(chart.snapshot().users().get(직원));
    }

    @Test
    @DisplayName("비활성 직원을 넣으면 레코드와 멤버십이 함께 생긴다")
    void 비활성_직원을_넣는다() {
        // given
        String 팀 = chart.landmarks().대상파트();

        // when
        OrgChart 넣은후 = OrgChartEditor.편집한다(chart).비활성_직원을_넣는다(팀, "scim.inactive").완성();

        // then
        var 직원 = 넣은후.snapshot().users().get("scim.inactive");
        assertThat(직원.active()).isFalse();
        assertThat(직원.displayName()).isEqualTo("비활성 직원");
        assertThat(직원.email()).isNull();
        assertThat(넣은후.직속조직들("scim.inactive")).containsExactly(팀);
    }

    @Test
    @DisplayName("직원 레코드만 지우면 멤버 목록의 참조는 남는다 — 조직이 먼저 도착한 중간 상태")
    void 레코드만_지우면_참조가_남는다() {
        // given
        String 직원 = chart.landmarks().L6직속직원();
        String 조직 = chart.직속조직(직원);

        // when
        OrgChart 지운후 = OrgChartEditor.편집한다(chart).직원_레코드만_지운다(직원).완성();

        // then
        assertThat(지운후.snapshot().users()).doesNotContainKey(직원);
        assertThat(지운후.snapshot().groups().get(조직).members()).contains(MemberRef.user(직원));
        assertThat(지운후.지워진멤버십()).isEmpty();
    }

    @Test
    @DisplayName("없는 직원을 편집하면 거부한다 — 조용히 넘기면 기대값이 틀린 채 통과한다")
    void 없는_직원은_거부한다() {
        // when, then
        assertThatThrownBy(() -> OrgChartEditor.편집한다(chart).비활성으로_바꾼다("nobody"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrgChartEditor.편집한다(chart).직원_레코드만_지운다("nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`OrgChartFixtureTest` 의 마지막 테스트 뒤에 추가:

```java
    @Test
    @DisplayName("모든 조직의 부모는 하나 이하다 — 다중 부모는 단위 테스트만 다룬다")
    void 부모는_하나_이하다() {
        // when — 조직마다 자신을 하위 조직으로 가진 조직 수를 센다
        var 부모수 = new java.util.HashMap<String, Integer>();
        chart.snapshot().groups().values().forEach(group -> group.members().stream()
                .filter(member -> member.type() == MemberType.GROUP)
                .forEach(member -> 부모수.merge(member.id(), 1, Integer::sum)));

        // then — 이 성질이 깨지면 스펙 §11 의 "다중 부모는 단위 테스트로만 확인" 을 고쳐야 한다
        assertThat(부모수.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
    }
```

- [ ] **Step 2: 컴파일이 실패하는지 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.OrgChartEditorTest'`
Expected: 컴파일 실패 — `Membership`, `지워진멤버십()`, `비활성으로_바꾼다` 등을 찾을 수 없음

- [ ] **Step 3: `Membership` 을 만든다**

`core/src/testFixtures/java/dev/starryeye/organization/core/fixture/Membership.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.MemberRef;

import java.util.Objects;

/**
 * 조직 {@code 조직} 의 멤버 목록에 {@code 멤버} 가 있다는 사실 하나.
 *
 * <p>{@link OrgChart#지워진멤버십()} 이 이것을 모은다. 튜플이 아니라 멤버십으로 기억하는 이유는
 * 튜플로 바꾸는 규칙이 {@link ChartExpectation} 의 것이기 때문이다 — 조직도는 사실만 든다.
 */
public record Membership(String 조직, MemberRef 멤버) {

    public Membership {
        Objects.requireNonNull(조직, "조직");
        Objects.requireNonNull(멤버, "멤버");
    }
}
```

- [ ] **Step 4: `OrgChart` 에 세 번째 칸을 더한다**

`OrgChart.java` 의 레코드 선언과 import 를 바꾼다. 기존 메서드는 그대로 둔다.

```java
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
```

클래스 자바독 마지막 문단 뒤에 추가:

```java
 *
 * <p><b>{@link #지워진멤버십} 은 이 조직도에 이르기까지 사라진 멤버십이다.</b> 하네스는 조직도에
 * 있는 멤버십만 묻기 때문에, 이것이 없으면 "지웠어야 할 권한이 남았는가" 를 못 묻는다.
 * {@link OrgChartEditor#완성()} 이 편집 전후를 비교해 쌓는다. 2인자 생성자는 기억이 없는
 * 최초 조직도용이다.
```

레코드 선언부:

```java
public record OrgChart(DirectorySnapshot snapshot, Landmarks landmarks, Set<Membership> 지워진멤버십) {

    public OrgChart {
        지워진멤버십 = 지워진멤버십 == null ? Set.of() : Set.copyOf(지워진멤버십);
    }

    /** 기억이 없는 최초 조직도. */
    public OrgChart(DirectorySnapshot snapshot, Landmarks landmarks) {
        this(snapshot, landmarks, Set.of());
    }

    /** 지금 조직도의 멤버십 전부. 에디터가 편집 전후를 비교하는 데 쓴다. */
    public Set<Membership> 멤버십들() {
        Set<Membership> all = new HashSet<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                all.add(new Membership(group.id(), member));
            }
        }
        return all;
    }
```

(그 아래 `조상들`부터 기존 메서드는 그대로.)

- [ ] **Step 5: `OrgChartEditor` 를 고친다**

필드와 생성자:

```java
    private final OrgChart 원본;
    private final Map<String, DirectoryUser> users;
    private final Map<String, DirectoryGroup> groups;
    private final Landmarks landmarks;

    private OrgChartEditor(OrgChart chart) {
        this.원본 = chart;
        this.users = new LinkedHashMap<>(chart.snapshot().users());
        this.groups = new LinkedHashMap<>(chart.snapshot().groups());
        this.landmarks = chart.landmarks();
    }
```

`완성()`:

```java
    /**
     * 편집 결과. 편집 전후 멤버십을 비교해 <b>사라진 것을 이전 기억에 더한다.</b>
     *
     * <p>편집 메서드마다 기록하지 않는 이유: {@link #조직을_지운다} 처럼 조직 레코드가 통째로
     * 사라지면 그 안의 멤버십이 {@code groups.remove} 한 줄로 사라진다. 전후 비교만이 그것까지
     * 빠짐없이 잡고, 새 편집 메서드가 생겨도 기록을 빠뜨릴 수 없다.
     *
     * <p>다시 추가된 멤버십을 기억에서 빼지 않는다 — 판정은 {@link ChartExpectation} 이
     * "있어야 함" 을 우선한다.
     */
    public OrgChart 완성() {
        OrgChart 편집후 = new OrgChart(new DirectorySnapshot(users, groups), landmarks);
        Set<Membership> 사라진것 = new HashSet<>(원본.멤버십들());
        사라진것.removeAll(편집후.멤버십들());
        Set<Membership> 기억 = new HashSet<>(원본.지워진멤버십());
        기억.addAll(사라진것);
        return new OrgChart(편집후.snapshot(), landmarks, 기억);
    }
```

import 에 `java.util.HashSet` 추가.

`직원속성을_바꾼다` 바로 뒤에 추가:

```java
    /** 비활성의 정의대로 <b>멤버십은 그대로 두고</b> {@code active} 만 끈다. */
    public OrgChartEditor 비활성으로_바꾼다(String userId) {
        return 활성을_바꾼다(userId, false);
    }

    public OrgChartEditor 활성으로_바꾼다(String userId) {
        return 활성을_바꾼다(userId, true);
    }

    /** 비활성 직원을 만들어 조직에 넣는다 — "멤버지만 권한 없음" 상태. */
    public OrgChartEditor 비활성_직원을_넣는다(String orgCode, String userId) {
        users.put(userId, new DirectoryUser(userId, null, userId, "비활성 직원", null, false));
        멤버를_더한다(orgCode, MemberRef.user(userId));
        return this;
    }

    /**
     * 직원 레코드만 없앤다. <b>멤버 목록의 참조는 남는다.</b>
     *
     * <p>SCIM 에서 조직이 직원보다 먼저 도착한 중간 상태가 이 모양이다. 끊긴 참조가 생기므로
     * 이 조직도는 {@link ChartExpectation#끊긴참조를_허용하며} 로만 검증할 수 있다.
     */
    public OrgChartEditor 직원_레코드만_지운다(String userId) {
        require(users.remove(userId), "직원", userId);
        return this;
    }

    private OrgChartEditor 활성을_바꾼다(String userId, boolean active) {
        DirectoryUser 원본직원 = require(users.get(userId), "직원", userId);
        users.put(userId, new DirectoryUser(원본직원.id(), 원본직원.externalId(), 원본직원.userName(),
                원본직원.displayName(), 원본직원.email(), active));
        return this;
    }
```

`ChartExpectation` 은 Task 2 에서 생긴다. 이 태스크에서 자바독의 `{@link ChartExpectation}` 은 컴파일 오류가 아니지만 javadoc 경고가 날 수 있다 — 무시한다(Task 2 에서 해소).

- [ ] **Step 6: 테스트가 통과하는지 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.*'`
Expected: PASS. `부모는_하나_이하다` 가 실패하면 **픽스처를 고치지 말고** DONE_WITH_CONCERNS 로 보고한다 (스펙 §11 을 고칠 사실이다).

- [ ] **Step 7: 전체 core 테스트와 하류 컴파일**

Run: `./gradlew :core:test :authz-openfga:compileTestFixturesJava :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS (2인자 생성자가 남아 있으므로 기존 호출부가 그대로 컴파일된다)

- [ ] **Step 8: 커밋·푸시**

```bash
git add core/src/testFixtures/java/dev/starryeye/organization/core/fixture/Membership.java core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChart.java core/src/testFixtures/java/dev/starryeye/organization/core/fixture/OrgChartEditor.java core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartEditorTest.java core/src/test/java/dev/starryeye/organization/core/fixture/OrgChartFixtureTest.java
git commit -m "test: 조직도가 지워진 멤버십을 기억한다"
git push
```

---

### Task 2: `ChartExpectation` — 조직도가 요구하는 것

**Files:**
- Create: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/ChartExpectation.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationTest.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationAgreementTest.java`

**Interfaces:**
- Consumes: Task 1 의 `Membership`, `OrgChart.지워진멤버십()`, `OrgChartEditor.비활성으로_바꾼다`
- Produces:
  - `static ChartExpectation of(OrgChart chart)` — 끊긴 참조·순환이면 `IllegalArgumentException`
  - `static ChartExpectation 끊긴참조를_허용하며(OrgChart chart)` — 순환이면 `IllegalArgumentException`
  - `OrgChart chart()`
  - `Set<RelationTuple> 있어야할튜플()`, `Set<RelationTuple> 물어볼후보()` (⊇ 있어야할튜플)
  - `Set<RelationTuple> 롤업양성(String userId)`, `Set<RelationTuple> 롤업음성(String userId)` — `member` 튜플

- [ ] **Step 1: 실패하는 규칙 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationTest.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.starryeye.organization.core.model.RelationTuple.child;
import static dev.starryeye.organization.core.model.RelationTuple.directMember;
import static dev.starryeye.organization.core.model.RelationTuple.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조직도 → 기대값 규칙을 작은 조직도로 하나씩 못박는다 (스펙 §4).
 *
 * <pre>
 * CORP ─┬─ DEV ─┬─ TEAM   (a: 활성)
 *       │       └─ TEAM2  (d: 활성)
 *       │  (DEV 직속 b: 비활성)
 *       └─ MGT            (c: 활성)
 * </pre>
 */
class ChartExpectationTest {

    private final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    private final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    ChartExpectationTest() {
        직원("a", true);
        직원("b", false);
        직원("c", true);
        직원("d", true);
        조직("CORP", MemberRef.group("DEV"), MemberRef.group("MGT"));
        조직("DEV", MemberRef.group("TEAM"), MemberRef.group("TEAM2"), MemberRef.user("b"));
        조직("TEAM", MemberRef.user("a"));
        조직("TEAM2", MemberRef.user("d"));
        조직("MGT", MemberRef.user("c"));
    }

    @Test
    @DisplayName("활성 직원의 소속마다 direct_member, 조직 간선마다 child 를 요구한다")
    void 있어야할_튜플() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — b 는 비활성이라 없다
        assertThat(기대.있어야할튜플()).containsExactlyInAnyOrder(
                child("DEV", "CORP"), child("MGT", "CORP"),
                child("TEAM", "DEV"), child("TEAM2", "DEV"),
                directMember("a", "TEAM"), directMember("d", "TEAM2"), directMember("c", "MGT"));
    }

    @Test
    @DisplayName("비활성 직원의 멤버십은 후보로 묻는다 — 퇴사자 권한 생존을 잡는 자리")
    void 비활성은_후보에_있다() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.물어볼후보()).contains(directMember("b", "DEV"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("b", "DEV"));
        assertThat(기대.물어볼후보()).containsAll(기대.있어야할튜플());
    }

    @Test
    @DisplayName("지워진 멤버십은 후보로 묻고, 다시 넣은 것은 있어야 할 쪽이 이긴다")
    void 지워진_멤버십은_후보다() {
        // given — a 가 TEAM 에서 빠진 적이 있고, c 는 MGT 에서 빠졌다가 다시 들어왔다
        조직("TEAM");
        var 조직도 = new OrgChart(new DirectorySnapshot(users, groups), null, Set.of(
                new Membership("TEAM", MemberRef.user("a")),
                new Membership("MGT", MemberRef.user("c"))));

        // when
        var 기대 = ChartExpectation.of(조직도);

        // then
        assertThat(기대.물어볼후보()).contains(directMember("a", "TEAM"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("a", "TEAM"));
        assertThat(기대.있어야할튜플()).contains(directMember("c", "MGT"));
    }

    @Test
    @DisplayName("끊긴 참조는 기본으로 거부한다 — 대개 시나리오 버그다")
    void 끊긴_참조는_거부한다() {
        // given — 없는 직원과 없는 조직을 가리킨다
        조직("MGT", MemberRef.user("c"), MemberRef.user("ghost"), MemberRef.group("NOWHERE"));

        // when, then
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("NOWHERE")
                .hasMessageContaining("끊긴참조를_허용하며");
    }

    @Test
    @DisplayName("끊긴 참조를 허용하면 튜플은 기대하지 않되 없어야 함을 묻는다")
    void 허용하면_후보로_묻는다() {
        // given
        조직("MGT", MemberRef.user("c"), MemberRef.user("ghost"), MemberRef.group("NOWHERE"));

        // when
        var 기대 = ChartExpectation.끊긴참조를_허용하며(조직도());

        // then — 운영이 없는 대상에게 튜플을 지어내면 ③이 잡는다
        assertThat(기대.물어볼후보()).contains(directMember("ghost", "MGT"), child("NOWHERE", "MGT"));
        assertThat(기대.있어야할튜플()).doesNotContain(directMember("ghost", "MGT"), child("NOWHERE", "MGT"));
    }

    @Test
    @DisplayName("지워진 멤버십은 끊긴 참조 검사에서 빠진다 — 지운 직원을 가리키는 것이 당연하다")
    void 기억은_끊긴_참조가_아니다() {
        // given — ghost 는 지워졌고, 그 멤버십만 기억에 있다
        var 조직도 = new OrgChart(new DirectorySnapshot(users, groups), null,
                Set.of(new Membership("MGT", MemberRef.user("ghost"))));

        // when
        var 기대 = ChartExpectation.of(조직도);

        // then
        assertThat(기대.물어볼후보()).contains(directMember("ghost", "MGT"));
    }

    @Test
    @DisplayName("순환은 어느 팩토리로도 거부한다 — 어느 간선을 버릴지는 구현 세부다")
    void 순환은_거부한다() {
        // given — TEAM 이 CORP 를 하위로 갖는다: CORP → DEV → TEAM → CORP
        조직("TEAM", MemberRef.user("a"), MemberRef.group("CORP"));

        // when, then
        assertThatThrownBy(() -> ChartExpectation.of(조직도()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순환");
        assertThatThrownBy(() -> ChartExpectation.끊긴참조를_허용하며(조직도()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("순환");
    }

    @Test
    @DisplayName("롤업 양성은 직속 조직과 모든 조상이다")
    void 롤업_양성() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("a")).containsExactlyInAnyOrder(
                member("a", "TEAM"), member("a", "DEV"), member("a", "CORP"));
    }

    @Test
    @DisplayName("롤업 음성은 자손과 형제 가지다 — 기대소속은 빠진다")
    void 롤업_음성() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — a 의 형제 가지 TEAM2. 자손은 없다
        assertThat(기대.롤업음성("a")).containsExactly(member("a", "TEAM2"));
        // c 는 MGT 직속 — 형제 가지 DEV
        assertThat(기대.롤업음성("c")).containsExactly(member("c", "DEV"));
    }

    @Test
    @DisplayName("비활성 직원은 양성이 없고 기대소속까지 음성이다")
    void 비활성_롤업() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then — b 는 DEV 직속: 자손 TEAM·TEAM2, 형제 MGT, 그리고 자기 소속 DEV·CORP 까지
        assertThat(기대.롤업양성("b")).isEmpty();
        assertThat(기대.롤업음성("b")).containsExactlyInAnyOrder(
                member("b", "TEAM"), member("b", "TEAM2"), member("b", "MGT"),
                member("b", "DEV"), member("b", "CORP"));
    }

    @Test
    @DisplayName("부모가 둘인 조직의 조상은 두 갈래 모두다")
    void 다중_부모() {
        // given — X 가 TEAM 과 MGT 양쪽의 하위 조직
        직원("x", true);
        조직("X", MemberRef.user("x"));
        조직("TEAM", MemberRef.user("a"), MemberRef.group("X"));
        조직("MGT", MemberRef.user("c"), MemberRef.group("X"));

        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("x")).containsExactlyInAnyOrder(
                member("x", "X"), member("x", "TEAM"), member("x", "DEV"),
                member("x", "MGT"), member("x", "CORP"));
    }

    @Test
    @DisplayName("없는 직원의 롤업은 묻지 않는다")
    void 없는_직원의_롤업() {
        // when
        var 기대 = ChartExpectation.of(조직도());

        // then
        assertThat(기대.롤업양성("nobody")).isEmpty();
        assertThat(기대.롤업음성("nobody")).isEmpty();
    }

    // ---------- 거들기 ----------

    private void 직원(String id, boolean active) {
        users.put(id, new DirectoryUser(id, null, id, id, null, active));
    }

    private void 조직(String id, MemberRef... members) {
        groups.put(id, new DirectoryGroup(id, null, id, Set.of(members)));
    }

    private OrgChart 조직도() {
        return new OrgChart(new DirectorySnapshot(users, groups), null);
    }
}
```

`List` import 는 쓰이지 않으면 지운다.

- [ ] **Step 2: 실패하는 일치 테스트를 쓴다**

`core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationAgreementTest.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.tuple.TupleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChartExpectation} 과 운영의 {@link TupleMapper} 가 <b>만나는 유일한 곳.</b>
 *
 * <p>둘은 같은 규칙을 따로 쓴 것이다. 순환도 끊긴 참조도 없는 조직도에서는 같은 튜플을 요구해야
 * 한다. 갈리면 둘 중 하나가 틀렸다 — 하네스의 기대값을 운영에서 가져오지 않기로 한 대가로, 둘이
 * 같다는 사실을 여기서 따로 확인한다 (스펙 §3).
 */
class ChartExpectationAgreementTest {

    private final OrgChart chart = OrgChartFixture.오천명();

    @Test
    @DisplayName("규모 조직도에서 TupleMapper 와 같은 튜플을 요구한다")
    void 규모_조직도에서_같다() {
        // when
        var 기대 = ChartExpectation.of(chart).있어야할튜플();

        // then
        assertThat(기대).isEqualTo(TupleMapper.toTuples(chart.snapshot()).tuples());
    }

    @Test
    @DisplayName("비활성 직원이 섞여도 같다")
    void 비활성이_섞여도_같다() {
        // given
        var l = chart.landmarks();
        OrgChart 섞인것 = OrgChartEditor.편집한다(chart)
                .비활성으로_바꾼다(l.L4직속직원())
                .비활성으로_바꾼다(l.겸직직원())
                .완성();

        // when
        var 기대 = ChartExpectation.of(섞인것).있어야할튜플();

        // then
        assertThat(기대).isEqualTo(TupleMapper.toTuples(섞인것.snapshot()).tuples());
    }

    @Test
    @DisplayName("물어볼 후보는 TupleMapper 의 후보를 모두 포함한다")
    void 후보는_운영의_후보를_포함한다() {
        // given
        OrgChart 편집후 = OrgChartEditor.편집한다(chart)
                .직원을_지운다(chart.landmarks().L6직속직원())
                .완성();

        // when
        var 후보 = ChartExpectation.of(편집후).물어볼후보();

        // then — 지워진 멤버십만큼 더 많다
        assertThat(후보).containsAll(TupleMapper.candidateTuples(편집후.snapshot()));
        assertThat(후보).hasSizeGreaterThan(TupleMapper.candidateTuples(편집후.snapshot()).size());
    }
}
```

- [ ] **Step 3: 컴파일이 실패하는지 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.ChartExpectation*'`
Expected: 컴파일 실패 — `ChartExpectation` 을 찾을 수 없음

- [ ] **Step 4: `ChartExpectation` 을 만든다**

`core/src/testFixtures/java/dev/starryeye/organization/core/fixture/ChartExpectation.java`:

```java
package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 조직도가 OpenFGA 에 요구하는 것 — 하네스({@link SyncVerifier})와 프로브({@code OpenFgaProbe})가
 * 함께 쓰는 <b>하나의 기대값</b> (스펙 §4).
 *
 * <p><b>운영의 {@code TupleMapper} 를 쓰지 않는다. 합치지도 말 것.</b> 하네스가 운영 코드에게 정답을
 * 물으면 운영이 틀릴 때 정답도 같이 틀려 검증이 통과한다. 둘이 같은 규칙을 따른다는 사실은
 * {@code ChartExpectationAgreementTest} 한 곳에서만 확인한다.
 *
 * <p><b>끊긴 참조</b>(멤버 목록이 조직도에 없는 직원·조직을 가리킴)는 {@link #of} 가 거부한다. 운영에서는
 * 정당한 상태(SCIM 에서 조직이 먼저 도착)지만 기대 조직도에서는 대개 시나리오 버그다. 일부러 만드는
 * 시나리오만 {@link #끊긴참조를_허용하며} 로 선언한다. 허용하면 튜플을 기대하지 않되 후보로 넣어
 * "없어야 함" 을 묻는다.
 *
 * <p><b>순환</b>은 항상 거부한다. 어느 간선을 버릴지는 운영 구현의 세부라 기대값이 흉내 내면
 * {@code TupleMapper} 를 다시 베끼는 것이 된다. 순환 시나리오는 필요한 튜플을 직접 Check 한다.
 */
public final class ChartExpectation {

    private static final Comparator<MemberRef> 멤버순 =
            Comparator.comparing(MemberRef::type).thenComparing(MemberRef::id);

    private final OrgChart chart;
    /** 하위 조직 → 그 조직을 하위로 가진 조직들. 조직도에 있는 조직끼리만. */
    private final Map<String, Set<String>> 부모들;
    private final Set<RelationTuple> 있어야할튜플;
    private final Set<RelationTuple> 물어볼후보;

    private ChartExpectation(OrgChart chart, boolean 끊긴참조허용) {
        this.chart = chart;
        DirectorySnapshot snapshot = chart.snapshot();
        if (!끊긴참조허용) {
            List<String> 끊긴것 = 끊긴참조들(snapshot);
            if (!끊긴것.isEmpty()) {
                throw new IllegalArgumentException(
                        "조직도에 끊긴 참조가 있습니다 — 의도한 것이면 ChartExpectation.끊긴참조를_허용하며 로 "
                                + "선언하세요: " + 끊긴것);
            }
        }
        순환이_없어야_한다(snapshot);
        this.부모들 = 부모들을_모은다(snapshot);
        this.있어야할튜플 = 있어야할튜플을_모은다(snapshot);
        this.물어볼후보 = 후보를_모은다(chart);
    }

    public static ChartExpectation of(OrgChart chart) {
        return new ChartExpectation(chart, false);
    }

    public static ChartExpectation 끊긴참조를_허용하며(OrgChart chart) {
        return new ChartExpectation(chart, true);
    }

    public OrgChart chart() {
        return chart;
    }

    /** 활성 직원의 소속마다 {@code direct_member}, 조직도에 있는 하위 조직마다 {@code child}. */
    public Set<RelationTuple> 있어야할튜플() {
        return 있어야할튜플;
    }

    /**
     * OpenFGA 에 물어볼 것. 조직도의 <b>모든</b> 멤버십(존재·활성 무관) + 지워진 멤버십.
     * 여기서 {@link #있어야할튜플} 을 뺀 것은 전부 없어야 한다.
     */
    public Set<RelationTuple> 물어볼후보() {
        return 물어볼후보;
    }

    /** 활성 직원이면 직속 조직과 모든 조상에 대해 {@code member}. 아니면 없다. */
    public Set<RelationTuple> 롤업양성(String userId) {
        if (!활성인가(userId)) {
            return Set.of();
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        기대소속(userId).forEach(org -> tuples.add(RelationTuple.member(userId, org)));
        return tuples;
    }

    /**
     * 이 직원이 {@code member} 면 안 되는 조직들. 직속 조직의 <b>자손</b>과 직속 조직의 부모가 가진
     * <b>형제 가지</b>, 기대소속은 뺀다. 비활성이면 기대소속도 음성이다 — 멤버십은 남기고 튜플만 지우는
     * 것이 비활성의 정의다 (설계 §5.1).
     */
    public Set<RelationTuple> 롤업음성(String userId) {
        if (!chart.snapshot().users().containsKey(userId)) {
            return Set.of();
        }
        Set<String> 기대소속 = 기대소속(userId);
        Set<String> 음성 = new TreeSet<>();
        for (String 직속 : 직속조직들(userId)) {
            음성.addAll(자손들(직속));
            for (String 부모 : 부모들.getOrDefault(직속, Set.of())) {
                음성.addAll(자식조직들(부모));
            }
        }
        음성.removeAll(기대소속);
        if (!활성인가(userId)) {
            음성.addAll(기대소속);
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        음성.forEach(org -> tuples.add(RelationTuple.member(userId, org)));
        return tuples;
    }

    // ---------- 조직도 읽기 ----------

    private boolean 활성인가(String userId) {
        DirectoryUser user = chart.snapshot().users().get(userId);
        return user != null && user.active();
    }

    private Set<String> 직속조직들(String userId) {
        Set<String> orgs = new TreeSet<>();
        for (DirectoryGroup group : chart.snapshot().groups().values()) {
            if (group.members().contains(MemberRef.user(userId))) {
                orgs.add(group.id());
            }
        }
        return orgs;
    }

    /** 직속 + <b>모든 부모</b>를 따라 올라간 조상. {@link OrgChart#부모} 는 첫 부모만 주므로 쓰지 않는다. */
    private Set<String> 기대소속(String userId) {
        Set<String> found = new TreeSet<>();
        Deque<String> 남은것 = new ArrayDeque<>(직속조직들(userId));
        while (!남은것.isEmpty()) {
            String current = 남은것.pop();
            if (found.add(current)) {
                남은것.addAll(부모들.getOrDefault(current, Set.of()));
            }
        }
        return found;
    }

    private Set<String> 자손들(String orgCode) {
        Set<String> found = new TreeSet<>();
        Deque<String> 남은것 = new ArrayDeque<>(자식조직들(orgCode));
        while (!남은것.isEmpty()) {
            String current = 남은것.pop();
            if (found.add(current)) {
                남은것.addAll(자식조직들(current));
            }
        }
        return found;
    }

    /** 조직도에 있는 직속 하위 조직들. 끊긴 참조는 조직이 아니다. */
    private Set<String> 자식조직들(String orgCode) {
        DirectoryGroup group = chart.snapshot().groups().get(orgCode);
        if (group == null) {
            return Set.of();
        }
        Set<String> children = new TreeSet<>();
        for (MemberRef member : group.members()) {
            if (member.type() == MemberType.GROUP && chart.snapshot().groups().containsKey(member.id())) {
                children.add(member.id());
            }
        }
        return children;
    }

    // ---------- 만들 때 한 번 ----------

    private static List<String> 끊긴참조들(DirectorySnapshot snapshot) {
        List<String> 끊긴것 = new ArrayList<>();
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            for (MemberRef member : 정렬한_멤버(group)) {
                boolean 있다 = member.type() == MemberType.USER
                        ? snapshot.users().containsKey(member.id())
                        : snapshot.groups().containsKey(member.id());
                if (!있다) {
                    끊긴것.add("%s → %s:%s".formatted(group.id(), member.type(), member.id()));
                }
            }
        }
        return 끊긴것;
    }

    private static void 순환이_없어야_한다(DirectorySnapshot snapshot) {
        Map<String, Integer> 색 = new HashMap<>();   // 없음=미방문, 1=방문중, 2=끝남
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            훑는다(group.id(), snapshot, 색, new ArrayList<>());
        }
    }

    private static void 훑는다(String node, DirectorySnapshot snapshot, Map<String, Integer> 색,
                            List<String> 경로) {
        Integer 현재 = 색.get(node);
        if (현재 != null && 현재 == 2) {
            return;
        }
        경로.add(node);
        if (현재 != null && 현재 == 1) {
            throw new IllegalArgumentException(
                    "조직도에 순환이 있습니다 — 기대값은 순환을 다루지 않습니다 (L16/S16 처럼 직접 Check "
                            + "하세요): " + String.join(" → ", 경로.subList(경로.indexOf(node), 경로.size())));
        }
        색.put(node, 1);
        DirectoryGroup group = snapshot.groups().get(node);
        for (MemberRef member : 정렬한_멤버(group)) {
            if (member.type() == MemberType.GROUP && snapshot.groups().containsKey(member.id())) {
                훑는다(member.id(), snapshot, 색, 경로);
            }
        }
        색.put(node, 2);
        경로.remove(경로.size() - 1);
    }

    private static Map<String, Set<String>> 부모들을_모은다(DirectorySnapshot snapshot) {
        Map<String, Set<String>> 부모들 = new HashMap<>();
        for (DirectoryGroup group : snapshot.groups().values()) {
            for (MemberRef member : group.members()) {
                if (member.type() == MemberType.GROUP && snapshot.groups().containsKey(member.id())) {
                    부모들.computeIfAbsent(member.id(), key -> new TreeSet<>()).add(group.id());
                }
            }
        }
        return 부모들;
    }

    private static Set<RelationTuple> 있어야할튜플을_모은다(DirectorySnapshot snapshot) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : 정렬한_조직(snapshot)) {
            for (MemberRef member : 정렬한_멤버(group)) {
                if (member.type() == MemberType.USER) {
                    DirectoryUser user = snapshot.users().get(member.id());
                    if (user != null && user.active()) {
                        tuples.add(RelationTuple.directMember(member.id(), group.id()));
                    }
                } else if (snapshot.groups().containsKey(member.id())) {
                    tuples.add(RelationTuple.child(member.id(), group.id()));
                }
            }
        }
        return tuples;
    }

    private static Set<RelationTuple> 후보를_모은다(OrgChart chart) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : 정렬한_조직(chart.snapshot())) {
            for (MemberRef member : 정렬한_멤버(group)) {
                tuples.add(튜플로(group.id(), member));
            }
        }
        chart.지워진멤버십().stream()
                .sorted(Comparator.comparing(Membership::조직).thenComparing(Membership::멤버, 멤버순))
                .forEach(m -> tuples.add(튜플로(m.조직(), m.멤버())));
        return tuples;
    }

    private static RelationTuple 튜플로(String groupId, MemberRef member) {
        return member.type() == MemberType.USER
                ? RelationTuple.directMember(member.id(), groupId)
                : RelationTuple.child(member.id(), groupId);
    }

    private static List<DirectoryGroup> 정렬한_조직(DirectorySnapshot snapshot) {
        return snapshot.groups().values().stream()
                .sorted(Comparator.comparing(DirectoryGroup::id)).toList();
    }

    private static List<MemberRef> 정렬한_멤버(DirectoryGroup group) {
        return group.members().stream().sorted(멤버순).toList();
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.*'`
Expected: PASS

- [ ] **Step 6: `TupleMapper` 를 import 하지 않는지 확인한다**

Run: `grep -n "TupleMapper" core/src/testFixtures/java/dev/starryeye/organization/core/fixture/ChartExpectation.java`
Expected: import 줄이 없다 (자바독의 `{@code TupleMapper}` 언급만 있다)

- [ ] **Step 7: 커밋·푸시**

```bash
git add core/src/testFixtures/java/dev/starryeye/organization/core/fixture/ChartExpectation.java core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationTest.java core/src/test/java/dev/starryeye/organization/core/fixture/ChartExpectationAgreementTest.java
git commit -m "test: 조직도만 보고 기대 튜플을 계산하는 ChartExpectation"
git push
```

---

### Task 3: `SyncVerifier` 가 `ChartExpectation` 을 쓴다

**Files:**
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/SyncVerifier.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/fixture/SyncVerifierTest.java`

**Interfaces:**
- Consumes: Task 2 의 `ChartExpectation` 전부, Task 1 의 에디터 편집
- Produces: `Mono<VerificationResult> 검증한다(OrgChart 기대)` (그대로), `Mono<VerificationResult> 검증한다(ChartExpectation 기대)` (신규)

- [ ] **Step 1: `SyncVerifierTest` 에 실패하는 테스트 두 개를 쓰고 헬퍼를 에디터로 바꾼다**

1. `롤업까지_펼친다()` 를 조직도를 받고 활성 직원만 펼치도록 바꾼다:

```java
    /** OpenFGA 가 member 를 해석해 주는 것을 흉내 낸다 — 활성 직원의 직속 + 모든 조상. */
    private Set<RelationTuple> 롤업까지_펼친다(OrgChart 조직도) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        조직도.snapshot().users().values().stream()
                .filter(DirectoryUser::active)
                .forEach(user -> 조직도.기대소속(user.id()).forEach(org ->
                        tuples.add(RelationTuple.member(user.id(), org))));
        return tuples;
    }
```

`@BeforeEach` 의 호출은 `checker.allowed.addAll(롤업까지_펼친다(chart));` 로 바꾼다.

2. private `비활성으로_바꾼다(String)` 헬퍼를 지우고, 세 호출부를 다음으로 바꾼다:

```java
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();
```

3. 두 테스트를 추가한다 (`비활성직원의_잔여튜플을_잡는다` 뒤):

```java
    @Test
    @DisplayName("③ 옮긴 직원의 옛 조직 튜플이 남아 있으면 잡는다 — 지운 멤버십을 기억하므로")
    void 옮긴_직원의_잔여튜플을_잡는다() {
        // given — 앱이 새 조직 튜플은 썼는데 옛 조직 튜플 삭제를 잊은 모양
        String 직원 = chart.landmarks().L5직속직원();
        String 옛조직 = chart.직속조직(직원);
        String 새조직 = chart.landmarks().대상팀();
        assertThat(새조직).as("전제: 다른 조직으로 옮겨야 한다").isNotEqualTo(옛조직);
        OrgChart 이동후 = OrgChartEditor.편집한다(chart).직원을_옮긴다(직원, 옛조직, 새조직).완성();

        state.groups.put(옛조직, 이동후.snapshot().groups().get(옛조직));
        state.groups.put(새조직, 이동후.snapshot().groups().get(새조직));
        checker.allowed.add(RelationTuple.directMember(직원, 새조직));
        assertThat(checker.allowed).as("전제: 옛 튜플이 남아 있다")
                .contains(RelationTuple.directMember(직원, 옛조직));

        // when
        var result = verifier.검증한다(이동후).block();

        // then — 옛 멤버십은 조직도에서 사라졌지만 기억에 남아 여전히 묻는다
        assertThat(result.어긋남()).anyMatch(message ->
                message.startsWith("③ 남아 있으면 안 되는 튜플")
                        && message.contains(직원) && message.contains(옛조직));
    }

    @Test
    @DisplayName("비활성 직원이 섞인 조직도도 올바른 앱이면 통과한다 — 운영 매핑에 속지 않는지 보는 자리")
    void 비활성이_섞여도_올바른_앱이면_통과한다() {
        // given — 앱은 운영의 TupleMapper 로 쓴다. 하네스는 그것과 따로 계산한다.
        // TupleMapper 가 비활성을 거르지 못하면 이 테스트가 깨져야 한다 — 하네스가 운영에게
        // 정답을 묻던 시절에는 같이 틀려서 통과했다 (스펙 §7 변이 #1).
        String 퇴사자 = chart.landmarks().L4직속직원();
        OrgChart 비활성된조직도 = OrgChartEditor.편집한다(chart).비활성으로_바꾼다(퇴사자).완성();
        state.users.put(퇴사자, 비활성된조직도.snapshot().users().get(퇴사자));
        checker.allowed.clear();
        checker.allowed.addAll(TupleMapper.toTuples(비활성된조직도.snapshot()).tuples());
        checker.allowed.addAll(롤업까지_펼친다(비활성된조직도));

        // when
        var result = verifier.검증한다(비활성된조직도).block();

        // then
        assertThat(result).isNotNull();
        assertThat(result.어긋났는가()).as(result == null ? "" : result.요약()).isFalse();
    }
```

4. `멤버십이_사라진_튜플은_못_잡는다` 의 주석 첫 두 줄을 다음으로 바꾼다 (테스트 본문은 그대로):

```java
        // given — 조직도에 한 번도 없었던, 완전히 떠 있는 튜플. 지운 멤버십은 기억하지만
        // 한 번도 없던 것은 후보에 들어갈 길이 없어 하네스가 아예 물어보지 않는다(스펙 §11).
```

`@DisplayName` 을 `"③ 은 조직도에 한 번도 없었던 튜플까지는 못 잡는다 — 알려진 한계를 못박는다"` 로 바꾼다.

- [ ] **Step 2: 새 테스트가 실패하는지 확인한다**

Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.SyncVerifierTest'`
Expected: `옮긴_직원의_잔여튜플을_잡는다` FAIL (옛 하네스는 옛 멤버십을 묻지 않는다). 나머지 PASS.

- [ ] **Step 3: `SyncVerifier` 를 고친다**

1. `import dev.starryeye.organization.core.tuple.TupleMapper;` 와 `import dev.starryeye.organization.core.model.DirectoryUser;` 중 쓰지 않게 되는 것을 지운다 (`DirectoryUser` 는 ① 에서 계속 쓴다 — 확인 후 판단).

2. 클래스 자바독의 두 문단을 바꾼다:

```java
 * <p><b>③ 이 가장 중요하다.</b> "있어야 할 것이 있다" 만 보면 잘못 남은 튜플을 영원히 못
 * 잡는다 — 이 프로젝트가 처음부터 위험하다고 본 것(퇴사자 권한 생존)이 정확히 그 모양이다.
 * 기대값과 후보는 {@link ChartExpectation} 이 조직도만 보고 계산한다. <b>운영의 {@code TupleMapper}
 * 에게 묻지 않는다</b> — 운영이 틀리면 정답도 같이 틀려 통과하기 때문이다. 후보에는 비활성 직원의
 * 멤버십과 조직도가 기억하는 <b>지워진 멤버십</b>이 들어간다.
 *
 * <p><b>한계를 알고 쓴다.</b> 에디터로 지운 멤버십은 잡지만, <b>조직도에 한 번도 없었던 튜플</b>은
 * 후보에 들어갈 길이 없어 못 잡는다(스펙 §11). 그것을 잡으려면 열거가 필요한데 금지돼 있다.
```

3. `검증한다` 를 두 개로:

```java
    /** ①~④ 를 전부 돈다. 조직도에 끊긴 참조나 순환이 있으면 거부한다. */
    public Mono<VerificationResult> 검증한다(OrgChart 기대) {
        return 검증한다(ChartExpectation.of(기대));
    }

    /** 끊긴 참조를 일부러 허용한 기대값처럼, 기대값을 직접 넘길 때. */
    public Mono<VerificationResult> 검증한다(ChartExpectation 기대) {
        return 상태를_대조한다(기대.chart())
                .flatMap(상태결과 -> 튜플을_대조한다(기대)
                        .flatMap(튜플결과 -> 롤업을_대조한다(기대)
                                .map(롤업결과 -> 상태결과.합친다(튜플결과).합친다(롤업결과))));
    }
```

4. `튜플을_대조한다`:

```java
    private Mono<VerificationResult> 튜플을_대조한다(ChartExpectation 기대) {
        Set<RelationTuple> 기대튜플 = 기대.있어야할튜플();
        Set<RelationTuple> 물어볼것 = 기대.물어볼후보();

        return checker.existing(물어볼것).map(실제 -> {
```

(나머지 본문 그대로.)

5. `롤업을_대조한다`:

```java
    private Mono<VerificationResult> 롤업을_대조한다(ChartExpectation 기대) {
        List<String> 표본 = 롤업표본.표본을_고른다(기대.chart());
        if (표본.isEmpty()) {
            return Mono.just(VerificationResult.통과());
        }

        Set<RelationTuple> 참이어야 = new LinkedHashSet<>();
        Set<RelationTuple> 거짓이어야 = new LinkedHashSet<>();
        for (String userId : 표본) {
            참이어야.addAll(기대.롤업양성(userId));
            거짓이어야.addAll(기대.롤업음성(userId));
        }

        Set<RelationTuple> 물어볼것 = new LinkedHashSet<>(참이어야);
        물어볼것.addAll(거짓이어야);
```

(그 뒤 `checker.existing(물어볼것).map(...)` 부분 그대로.) 이 메서드 자바독의 "음성 쪽이 이 단계의 핵심이다" 문단은 두고, 끝에 한 줄 추가: `규칙은 {@link ChartExpectation#롤업양성}·{@link ChartExpectation#롤업음성} 에 있다.`

6. `활성인가` 와 `새면_안되는_조직들` 메서드와 그 자바독을 지운다.

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew :core:test`
Expected: PASS (전체 core)

- [ ] **Step 5: 변이로 증명한다 — 각각 확인 후 반드시 되돌린다**

변이 #1 — `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java` 의 `collectDirectMembers` 에서
```java
                if (!user.active()) {
                    continue;
                }
```
세 줄을 주석 처리한다.
Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.SyncVerifierTest'`
Expected: `비활성이_섞여도_올바른_앱이면_통과한다` FAIL, 메시지에 `③ 남아 있으면 안 되는 튜플` 과 L4직속직원 아이디.
되돌린다: `git checkout -- core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`

변이 #2 — 같은 파일 `collectChildEdges` 의 `if (member.type() != MemberType.GROUP) { continue; }` 바로 뒤에 `if (member.id().startsWith("DEV")) { continue; }` 를 넣는다.
Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.SyncVerifierTest'`
Expected: `맞으면_통과한다` FAIL, 메시지에 `② 있어야 할 튜플이 없다: (group:DEV`.
되돌린다: `git checkout -- core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`

변이 #3 — `ChartExpectation.후보를_모은다` 의 `chart.지워진멤버십().stream()...forEach(...)` 문장을 주석 처리한다.
Run: `./gradlew :core:test --tests 'dev.starryeye.organization.core.fixture.SyncVerifierTest'`
Expected: `옮긴_직원의_잔여튜플을_잡는다` FAIL.
되돌린다: 주석을 푼다.

각 변이의 실패 메시지 첫 줄을 보고서에 적는다.

- [ ] **Step 6: 되돌림 확인과 전체 테스트**

Run: `git status --short`
Expected: `core/src/main` 아래 변경 없음

Run: `./gradlew :core:test :authz-openfga:compileTestFixturesJava :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS

- [ ] **Step 7: 커밋·푸시**

```bash
git add core/src/testFixtures/java/dev/starryeye/organization/core/fixture/SyncVerifier.java core/src/test/java/dev/starryeye/organization/core/fixture/SyncVerifierTest.java
git commit -m "test: 하네스가 운영 매핑 대신 ChartExpectation 으로 기대값을 잡는다"
git push
```

---

### Task 4: `OpenFgaProbe` 가 같은 기대값을 쓴다

**Files:**
- Modify: `authz-openfga/src/testFixtures/java/dev/starryeye/organization/authz/fixture/OpenFgaProbe.java`

**Interfaces:**
- Consumes: `ChartExpectation.of`, `있어야할튜플()`, `물어볼후보()`, `롤업양성(String)`, `롤업음성(String)`
- Produces: `VerificationResult 직접_대조한다(OrgChart chart, List<String> 롤업표본)` (그대로), `VerificationResult 직접_대조한다(ChartExpectation 기대, List<String> 롤업표본)` (신규)

- [ ] **Step 1: import 를 바꾼다**

`import dev.starryeye.organization.core.tuple.TupleMapper;` 를 지우고 `import dev.starryeye.organization.core.fixture.ChartExpectation;` 를 더한다.

- [ ] **Step 2: `직접_대조한다` 를 두 개로 바꾼다**

기존 메서드 전체(자바독 포함)를 다음으로 바꾼다:

```java
    /**
     * 조직도가 요구하는 상태를 OpenFGA 에 직접 물어 대조한다 — 양성·음성·롤업을 한 번에.
     *
     * <p>{@code SyncVerifier} 와 <b>같은 기대값({@link ChartExpectation})을 다른 경로로</b> 묻는다.
     * 둘이 같은 답을 내야 하고, 갈리면 어느 한쪽 — 대개 그 사이에 있는 어댑터 — 이 틀린 것이다.
     */
    public VerificationResult 직접_대조한다(OrgChart chart, List<String> 롤업표본) {
        return 직접_대조한다(ChartExpectation.of(chart), 롤업표본);
    }

    /** 끊긴 참조를 일부러 허용한 기대값처럼, 기대값을 직접 넘길 때. */
    public VerificationResult 직접_대조한다(ChartExpectation 기대, List<String> 롤업표본) {
        List<String> 어긋남 = new ArrayList<>();

        Set<RelationTuple> 기대튜플 = 기대.있어야할튜플();
        Map<RelationTuple, Boolean> 답 = batchCheck(기대.물어볼후보());
        답.forEach((tuple, allowed) -> {
            boolean 기대값 = 기대튜플.contains(tuple);
            if (기대값 != allowed) {
                어긋남.add("OpenFGA 직접질의: %s 가 기대=%s 실제=%s"
                        .formatted(읽기쉽게(tuple), 기대값, allowed));
            }
        });

        // 음성은 자손과 형제 가지 — SyncVerifier 와 같은 규칙이다. 비활성 직원은 소속이 그대로여도
        // 권한이 없으므로 기대소속까지 음성이다(설계 §5.1).
        Map<RelationTuple, Boolean> 롤업기대 = new LinkedHashMap<>();
        for (String userId : 롤업표본) {
            기대.롤업양성(userId).forEach(tuple -> 롤업기대.put(tuple, true));
            기대.롤업음성(userId).forEach(tuple -> 롤업기대.put(tuple, false));
        }
        batchCheck(new LinkedHashSet<>(롤업기대.keySet())).forEach((tuple, allowed) -> {
            if (!롤업기대.get(tuple).equals(allowed)) {
                어긋남.add("OpenFGA 직접질의 롤업: %s 가 기대=%s 실제=%s"
                        .formatted(읽기쉽게(tuple), 롤업기대.get(tuple), allowed));
            }
        });

        return new VerificationResult(어긋남);
    }
```

`batchCheck` 는 `public Map<RelationTuple, Boolean> batchCheck(Set<RelationTuple> tuples)` 다 (103행). `물어볼후보()` 는 이미 `있어야할튜플()` 을 포함하므로 합칠 필요가 없다.

- [ ] **Step 3: 컴파일과 기존 프로브 테스트**

Run: `./gradlew :authz-openfga:test :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS (`authz-openfga:test` 는 Testcontainers 로 OpenFGA 한 개를 띄운다 — 수십 초)

- [ ] **Step 4: 커밋·푸시**

```bash
git add authz-openfga/src/testFixtures/java/dev/starryeye/organization/authz/fixture/OpenFgaProbe.java
git commit -m "test: OpenFgaProbe 가 하네스와 같은 기대값을 다른 경로로 묻는다"
git push
```

---

### Task 5: 규모 테스트를 옮긴다

**Files:**
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapDeletionGuardScaleTest.java` (62행 부근)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapScaleScenarioTest.java` (447행 부근 `전체튜플수()`)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/DitScaleSyncTest.java` (115행)
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimLimitsAndRecoveryScaleTest.java` (169행)
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java` (S5·S6·S12, 헬퍼 609~630행)
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimProvisioningOrderScaleTest.java` (S1-a, `검증한다()`)

**Interfaces:**
- Consumes: `ChartExpectation.of/끊긴참조를_허용하며/있어야할튜플/chart`, `SyncVerifier.검증한다(ChartExpectation)`, `OpenFgaProbe.직접_대조한다(ChartExpectation, List<String>)`, 에디터의 `비활성으로_바꾼다`/`활성으로_바꾼다`/`비활성_직원을_넣는다`/`직원_레코드만_지운다`

**이 태스크는 규모 테스트를 돌리지 않는다.** 컴파일까지만 확인하고, 실행은 Task 6 에서 메인 세션이 한다.

- [ ] **Step 1: 튜플 수 4곳**

`LdapDeletionGuardScaleTest`:
```java
    private static final int 최초튜플수 = ChartExpectation.of(최초).있어야할튜플().size();
```
import `dev.starryeye.organization.core.fixture.ChartExpectation` 추가.

`LdapScaleScenarioTest.전체튜플수()`:
```java
    private static int 전체튜플수() {
        return ChartExpectation.of(최초).있어야할튜플().size();
    }
```
import 추가.

`DitScaleSyncTest` 115행:
```java
        int 기대튜플 = ChartExpectation.of(기대).있어야할튜플().size();
```
`import dev.starryeye.organization.core.tuple.TupleMapper;` 는 파일에 다른 사용처가 없으면 지우고 `ChartExpectation` import 추가.

`ScimLimitsAndRecoveryScaleTest` 169행:
```java
                .isEqualTo(ChartExpectation.of(기대).있어야할튜플().size() - 1);
```
`TupleMapper` import 는 다른 사용처가 없으면 지우고 `ChartExpectation` import 추가.

- [ ] **Step 2: `ScimScaleScenarioTest` 의 손 조직도 헬퍼를 에디터로**

S5:
```java
        기대 = OrgChartEditor.편집한다(기대).비활성으로_바꾼다(겸직).완성();
```
S6:
```java
        기대 = OrgChartEditor.편집한다(기대).활성으로_바꾼다(겸직).완성();
```
S12:
```java
        기대 = OrgChartEditor.편집한다(기대).비활성_직원을_넣는다(팀, 비활성).완성();
```
private static `활성을_바꾼다`, `비활성_멤버를_더한다` 두 메서드를 지운다. 그 결과 쓰이지 않게 된 import(`ArrayList`, `LinkedHashSet` 등)가 있으면 지운다 — 파일 안의 다른 사용처를 grep 으로 확인한 뒤에.

- [ ] **Step 3: S1-a 를 하네스로**

`ScimProvisioningOrderScaleTest`:

import 추가: `dev.starryeye.organization.core.fixture.ChartExpectation`, `dev.starryeye.organization.core.fixture.OrgChartEditor`.

`검증한다()` 를 둘로:
```java
    private void 검증한다() {
        검증한다(ChartExpectation.of(기대));
    }

    private void 검증한다(ChartExpectation 기대값) {
        var 하네스 = new SyncVerifier(state, checker).검증한다(기대값).block(Duration.ofMinutes(10));
        assertThat(하네스).isNotNull();
        assertThat(하네스.어긋났는가()).as(하네스 == null ? "" : 하네스.요약()).isFalse();

        var 직접 = new OpenFgaProbe(bootstrapper)
                .직접_대조한다(기대값, RollupSampling.기본값().표본을_고른다(기대값.chart()));
        assertThat(직접.어긋났는가()).as(직접.요약()).isFalse();
    }
```

S1-a 본문의 `// then` 이하를 다음으로 바꾼다:
```java
        // then — 조직은 다 만들어졌지만 직원이 아직 없는 중간 상태 전체를 하네스로 잰다.
        // 멤버 목록은 아직 없는 직원을 가리키고(끊긴 참조), 그 멤버십의 튜플은 없어야 한다 —
        // 운영이 "스냅샷에 없어 건너뜁니다" 로 미뤄 둔 상태다. 조직끼리의 child 는 이미 성립한다.
        // 직원이 없으니 롤업 표본은 비고, ④ 는 이 단계에서 할 일이 없다.
        검증한다(ChartExpectation.끊긴참조를_허용하며(직원이_아직_없는_조직도()));
```

거들기 절에 추가:
```java
    /** 조직만 도착한 상태 — 멤버 목록의 직원 참조는 남고 직원 레코드만 없다. */
    private static OrgChart 직원이_아직_없는_조직도() {
        var editor = OrgChartEditor.편집한다(기대);
        기대.snapshot().users().keySet().stream().sorted().forEach(editor::직원_레코드만_지운다);
        return editor.완성();
    }
```

S1-a 에서 쓰이지 않게 된 지역 변수(`대표직원`, `팀`)와 그 import(`RelationTuple` 이 `성립하는가` 등 다른 곳에서 쓰이면 유지)를 정리한다.

- [ ] **Step 4: 컴파일**

Run: `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS

Run: `grep -rn "new OrgChart(" app-ldap/src app-scim/src`
Expected: 결과 없음

- [ ] **Step 5: 커밋·푸시**

```bash
git add app-ldap/src/test app-scim/src/test
git commit -m "test: 규모 테스트가 ChartExpectation 을 쓰고 S1-a 중간 상태를 하네스로 잰다"
git push
```

---

### Task 6: 규모 테스트 실행, 변이 증명, 손 Check 제거 (메인 세션)

**Files:**
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapDeletionGuardScaleTest.java` (L12-a 의 손 Check)
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java` (S15 의 손 Check)

- [ ] **Step 1: 옮긴 상태로 규모 스위트를 돌린다**

Run: `./gradlew :app-ldap:test :app-scim:test` (백그라운드, 최대 timeout)
Expected: 전부 통과. 실패하면 하네스가 새로 잡은 것인지(지워진 멤버십·형제 가지 음성) 시나리오 기대값 버그인지 가려서 고친 뒤 다시 돈다. 새로 잡은 것이 운영 결함이면 멈추고 사용자에게 알린다.

- [ ] **Step 2: 손 Check 두 곳을 지운다**

`LdapDeletionGuardScaleTest.L12a_경계_아래는_통과한다`: `검증한다();` 뒤의 주석 문단("지워진 사람들은 기대 조직도에서도 통째로 빠지므로 …")부터 `표본.forEach(...)` 문장 끝까지 지운다. 쓰이지 않게 된 `List<String> 지워진사람들 = …;` 선언과 `ArrayList` import(다른 사용처 없으면)를 지운다. `검증한다();` 바로 뒤에 한 줄 주석을 둔다:
```java
        // 지운 사람들의 권한이 사라졌는지는 하네스 ③이 전원을 묻는다 — 조직도가 지운 멤버십을 기억한다
```

`ScimScaleScenarioTest.S15_대형조직_교체`: `assertThat(멤버를_끝까지_읽는다(대형조직)).hasSize(…);` 뒤의 주석 문단("뺀 20명은 대형조직 말고 …")과 `뺄사람.forEach(id -> assertThat(성립하는가(RelationTuple.member(id, 대형조직)))…isFalse());` 를 지우고 같은 한 줄 주석을 둔다.

Run: `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS

- [ ] **Step 3: 변이 #4 — OpenFGA 삭제가 조용히 no-op 이어도 하네스가 잡는다**

`authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java` 의 `applyBatch` 첫 줄에 넣는다:
```java
        if (batch.delete()) {
            return Mono.just(batch.succeeded());   // 변이: 지운 척만 한다
        }
```
Run: `./gradlew :app-ldap:test --tests '*LdapDeletionGuardScaleTest'`
Expected: L12-a FAIL, 메시지 `③ 남아 있으면 안 되는 튜플` — deletedCount 는 맞게 나오고 **하네스가** 잡는다.

Run: `./gradlew :app-scim:test --tests '*ScimScaleScenarioTest'`
Expected: 삭제가 있는 첫 시나리오에서 FAIL, 메시지 `③ 남아 있으면 안 되는 튜플` (S15 까지 가지 못해도 된다 — 하네스가 no-op 삭제를 잡는다는 것이 요점).

되돌린다: `git checkout -- authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaRelationTupleWriter.java`
Run: `git status --short` → `src/main` 변경 없음 확인

- [ ] **Step 4: 되돌린 상태로 두 클래스를 다시 돌린다**

Run: `./gradlew :app-ldap:test --tests '*LdapDeletionGuardScaleTest' :app-scim:test --tests '*ScimScaleScenarioTest'`
Expected: PASS

- [ ] **Step 5: 커밋·푸시**

```bash
git add app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapDeletionGuardScaleTest.java app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java
git commit -m "test: 지운 사람 권한의 손 Check 를 하네스 ③ 으로 대신한다"
git push
```

---

### Task 7: 실측과 기록 (메인 세션)

**Files:**
- Modify: `docs/superpowers/specs/2026-09-11-harness-independent-expectation-design.md` (§10, §11)
- Modify: `docs/superpowers/specs/2026-09-11-e2e-branch-review.md` (§3 끝)

- [ ] **Step 1: 전체 스위트**

Run: `./gradlew test` (백그라운드, 최대 timeout)
Expected: 전부 통과

- [ ] **Step 2: 클래스별 소요 시간 비교**

`app-ldap`·`app-scim` 의 `TEST-*.xml` `time` 값을 Task 0 의 `baseline-durations.txt` 와 나란히 놓는다.

- [ ] **Step 3: 스펙 §10 에 표로 적는다**

`## 10. 실측` 의 본문 한 줄을 표로 바꾼다: `| 테스트 클래스 | 전 (초) | 후 (초) | 차이 |`. 표 아래에 변이 #1~#4 의 결과(각 실패 메시지 첫 줄)를 적는다.

- [ ] **Step 4: 스펙 §11 다섯째 항목을 사실로 고친다**

Task 1 의 `부모는_하나_이하다` 가 통과했으면:
```
- **규모 픽스처에는 부모가 둘인 조직이 없다** (`OrgChartFixtureTest.부모는_하나_이하다` 가 못박는다).
  조상 계산의 다중 부모 처리는 `ChartExpectationTest.다중_부모` 로만 확인된다.
```

- [ ] **Step 5: 리뷰 문서 §3 끝에 해소 기록**

`**미룬 이유:** …` 문단 뒤에:
```
**해소:** 슬라이드 E — [`2026-09-11-harness-independent-expectation-design.md`](2026-09-11-harness-independent-expectation-design.md).
```

- [ ] **Step 6: 커밋·푸시**

```bash
git add docs/superpowers/specs/2026-09-11-harness-independent-expectation-design.md docs/superpowers/specs/2026-09-11-e2e-branch-review.md
git commit -m "docs: 하네스 독립 기대값 실측과 변이 결과"
git push
```
