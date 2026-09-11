# 하네스의 독립 기대값 — 설계

> 슬라이드 E. 브랜치 `harness-independent-expectation` (origin/main `b08839b` 에서 분기).
> 근거: [`2026-09-11-e2e-branch-review.md`](2026-09-11-e2e-branch-review.md) §3.

## 1. 문제

하네스(`SyncVerifier`)와 교차검증 프로브(`OpenFgaProbe.직접_대조한다`)가 **"OpenFGA 에 무엇이 있어야
하는가" 를 운영 코드에게 묻는다.**

```java
Set<RelationTuple> 기대튜플 = TupleMapper.toTuples(기대.snapshot()).tuples();
Set<RelationTuple> 후보 = TupleMapper.candidateTuples(기대.snapshot());
```

`TupleMapper.toTuples` 는 `FullSyncUseCase`·`RebuildUseCase`·`ScimRebuildUseCase` 가 **무엇을 쓸지 정할 때
부르는 바로 그 함수**다. 하네스 ②는 "앱이 `f(state)` 를 썼나" 를 묻고, 하네스의 답도 `f(expected)` 다.
`TupleMapper` 가 틀리면 결과와 정답이 같이 틀려서 통과한다. 채점자가 학생 답안지를 보고 정답을 쓰는 셈이다.

실패 모양 (리뷰 §3): `TupleMapper` 가 정당한 child 간선 하나를 떨어뜨리면 ②③이 같이 틀리고, ①은 DynamoDB 가
맞으니 통과한다. ④만 볼 수 있는데 30명 표본이라 한 가지를 놓칠 확률이 약 90% 다.

### 두 번째 문제 — 지워진 것을 묻지 않는다

하네스 ③(음성)의 후보는 **지금 조직도에 있는 멤버십**에서 나온다. 직원이 이동·삭제되거나 조직이 지워지면
그 멤버십은 조직도에서 사라지고, 후보에서도 사라진다. 그래서 **운영 코드가 OpenFGA 삭제를 잊어도 하네스는
그것을 묻지 않는다.** "퇴사자 권한 생존" 이 이 프로젝트가 처음부터 가장 위험하다고 본 결함인데, 정확히 그
모양을 놓친다.

지금은 두 곳(`LdapDeletionGuardScaleTest`, `ScimScaleScenarioTest` S15)이 이를 손 Check 로 메우고 있다
(리뷰 수정 D). 앞으로의 삭제 시나리오는 누군가 기억해서 손으로 넣어야만 잡힌다.

## 2. 목표와 비목표

**목표**

- 하네스 ②③④와 프로브가 `TupleMapper` 없이 **조직도만 보고 계산한 하나의 기대값**을 쓴다.
- 조직도가 **지워진 멤버십을 기억**하고, 하네스가 그것이 정말 없어졌는지 자동으로 묻는다.
- 프로브의 롤업 음성 기준을 `SyncVerifier` 와 같게 맞춘다 (지금은 자손만, `SyncVerifier` 는 자손 + 형제 가지).

**비목표**

- 운영 코드(`src/main`)는 한 줄도 바꾸지 않는다.
- OpenFGA 열거 API 는 쓰지 않는다 ("Read 는 불가능, Check 는 언제든지").
- `removeUser` 의 GSI 지연 문제(§9)는 이 슬라이드에서 고치지 않는다.

## 3. 접근안 — 왜 다른 길을 안 갔나

| | 내용 | 판단 |
|---|---|---|
| **A. 기대값 전담 부품을 따로 둔다 — 채택** | `core` testFixtures 에 `ChartExpectation` 을 두고 `OrgChart` 만 보고 계산. `TupleMapper` 를 import 하지 않는다. `SyncVerifier` 와 `OpenFgaProbe` 가 같이 쓴다 | 두 검증이 **같은 기대값을 서로 다른 경로(앱 어댑터 / SDK 직접)로** 묻게 된다. 프로브 자바독의 주장("같은 것을 다른 경로로")이 비로소 사실이 된다 |
| B. 같은 규칙을 `OrgChart` 의 메서드로 둔다 | `OrgChart` 가 "진실" 이니 자연스러운 자리 | "형제 가지 몇 개를 음성으로 넣는다" 같은 **검증 정책이 조직도 레코드에 섞이고**, 이미 여러 일을 하는 `OrgChart` 가 더 커진다 |
| C. 규칙을 `SyncVerifier` 안에 두고 프로브가 그걸 부른다 | 파일이 가장 적다 | 프로브(`authz-openfga`)가 검증기 내부에 묶인다. "같은 것을 다른 경로로" 가 "프로브가 검증기를 호출" 로 바뀌어 독립성 주장이 흐려진다 |

지운 것을 기억하는 방법에서 기각한 것:

- **검증기가 직전 조직도를 기억** — 호출부 12곳이 매번 `new SyncVerifier(...)` 로 새로 만든다. 기억할 자리가 없다.
- **편집 연산마다 지운 것을 기록** — `조직을_지운다` 처럼 조직 레코드가 통째로 사라질 때 그 안의 멤버십을 놓친다.
  `groups.remove` 한 줄로 사라지기 때문이다. 전후 비교만이 빠짐없이 잡는다.

**운영 코드와 합치지 않는다.** `ChartExpectation` 과 `TupleMapper` 는 같은 규칙을 두 번 쓴 것처럼 보인다.
하지만 합치면 이 슬라이드가 없앤 문제가 그대로 돌아온다. 두 곳이 만나는 곳은 일치 테스트(§7) 하나뿐이다.

## 4. `ChartExpectation` — 조직도가 요구하는 것

위치: `core/src/testFixtures/java/dev/starryeye/organization/core/fixture/ChartExpectation.java`

```java
ChartExpectation.of(OrgChart chart)                 // 끊긴 참조·순환이 있으면 거부
ChartExpectation.끊긴참조를_허용하며(OrgChart chart)   // 순환만 거부
  OrgChart chart()
  Set<RelationTuple> 있어야할튜플()
  Set<RelationTuple> 물어볼후보()                   // ⊇ 있어야할튜플()
  Set<RelationTuple> 롤업양성(String userId)
  Set<RelationTuple> 롤업음성(String userId)
```

### 규칙

| 대상 | 규칙 |
|---|---|
| `direct_member(u, G)` 가 있어야 함 | G 의 멤버에 u 가 있고, u 가 조직도에 있고, u 가 활성 |
| `child(C, G)` 가 있어야 함 | G 의 멤버에 조직 C 가 있고, C 가 조직도에 있음 |
| 물어볼 후보 | 조직도의 **모든** 멤버십 (존재·활성 여부 무관) + 조직도가 기억하는 **지워진 멤버십**. 각각 `direct_member` 또는 `child` 로 바꾼다 |
| 지웠다가 다시 넣은 멤버십 | 후보에도 있고 있어야할튜플에도 있다. 판정은 "있어야 함" 이 이긴다 (③의 판정이 `있어야할튜플.contains` 이므로 따로 처리할 것이 없다) |
| 조상 | **모든 부모**를 따라 올라간 닫힌 집합. `OrgChart.부모()` 는 첫 번째 부모 하나만 주므로 쓰지 않는다 |
| `롤업양성(u)` | u 가 활성이면 `member(u, X)`. X = u 의 직속 조직들과 그 조상들. u 가 비활성이거나 없으면 빈 집합 |
| `롤업음성(u)` | 직속 조직들의 **자손** + 직속 조직의 각 부모의 **자식들(형제 가지)** − 기대소속. u 가 비활성이면 기대소속도 음성에 들어간다 (멤버십은 남기고 튜플만 지우는 것이 비활성의 정의, 설계 §5.1) |

### 끊긴 참조 — 기본 거부, 선언하면 허용

끊긴 참조 = 조직의 멤버 목록이 조직도에 없는 직원이나 조직을 가리키는 것.

- **운영에서는 정당한 상태다.** SCIM 에서 조직이 직원보다 먼저 오면 반드시 생긴다. 이때 DynamoDB 는 멤버 줄을
  남기고(늦게 온 직원의 튜플을 만들 근거), `TupleMapper` 는 튜플을 쓰지 않는다(OpenFGA 는 대상의 존재를
  확인하지 않으므로, 미리 쓰면 프로비저닝 전에 권한이 생긴다). 이 슬라이드는 이 동작을 바꾸지 않는다.
- **기대 조직도에서는 대개 시나리오 버그다.** 픽스처나 에디터가 실수로 만든 끊긴 참조가 조용히 통과하면
  "튜플이 없다" 만 확인하고 넘어간다.
- 그래서 `of()` 는 거부하고, 일부러 만드는 시나리오만 `끊긴참조를_허용하며()` 로 선언한다.
- 허용했을 때: 튜플을 기대하지 않되 **후보에는 넣어 "없어야 함" 을 묻는다.** 운영이 없는 대상에게 튜플을
  지어내는 결함을 잡는다.
- 거부 메시지는 끊긴 참조를 정렬해 나열한다 (JVM 실행마다 `Set.copyOf` 순회 순서가 달라지므로).

### 순환 — 항상 거부

어느 back edge 를 버리는지는 DFS 순서라는 **구현 세부**다. 기대값이 그것을 흉내 내면 `TupleMapper` 를 다시
베끼는 것이 된다. 순환 시나리오(L16, S16)는 순환을 만든 뒤 하네스를 부르지 않고 필요한 튜플을 직접 Check
한다. 이 방식은 그대로 둔다.

## 5. 조직도가 지워진 멤버십을 기억한다

### `OrgChart`

```java
public record OrgChart(DirectorySnapshot snapshot, Landmarks landmarks, Set<Membership> 지워진멤버십) {
    public OrgChart(DirectorySnapshot snapshot, Landmarks landmarks) {
        this(snapshot, landmarks, Set.of());   // 기억 없음
    }
}

public record Membership(String 조직, MemberRef 멤버) { }   // 같은 fixture 패키지
```

2인자 생성자를 남기므로 기존에 조직도를 직접 만드는 곳(`OrgChartFixture`, `LdifRendererTest` 등)은 그대로
컴파일된다. 이들은 최초 조직도라 기억할 이력이 없다.

### `OrgChartEditor.완성()`

```
지워진멤버십 = 원본.지워진멤버십 ∪ (원본의 멤버십 − 편집 후 멤버십)
```

편집 메서드마다 기록하지 않고 전후를 한 번에 비교한다. 새 편집 메서드가 생겨도 빠뜨리지 않는다. 다시
추가된 멤버십을 기억에서 빼지 않는 이유는 §4 의 판정이 "있어야 함" 을 우선하기 때문이다.

이것으로 새로 잡히는 결함:

- 직원 이동: 옛 조직의 `direct_member` 가 남음
- 직원 삭제: 모든 조직의 `direct_member` 가 남음
- 조직 삭제: **그 조직 안 멤버들의** `direct_member`·`child`, 상위 조직과의 `child` 가 남음
- 멤버 통째 비우기 (SCIM 필터 없는 `remove members`)

### 에디터에 더하는 편집

| 메서드 | 대신하는 것 |
|---|---|
| `비활성으로_바꾼다(userId)` | `SyncVerifierTest.비활성으로_바꾼다`, `ScimScaleScenarioTest.활성을_바꾼다(.., false)` |
| `활성으로_바꾼다(userId)` | `ScimScaleScenarioTest.활성을_바꾼다(.., true)` |
| `비활성_직원을_넣는다(orgCode, userId)` | `ScimScaleScenarioTest.비활성_멤버를_더한다` (표시명 "비활성 직원", 메일 `null`) |
| `직원_레코드만_지운다(userId)` | 새로 필요. 멤버 목록의 참조는 그대로 두고 직원만 없앤다 (S1-a 의 중간 상태) |

손으로 만드는 헬퍼 세 개는 `new OrgChart(...)` 로 조직도를 다시 만들기 때문에 **기억을 날린다.** 그래서 지운다.

## 6. 기존 테스트를 옮기는 범위

| 대상 | 바뀐 뒤 |
|---|---|
| `SyncVerifier.검증한다(OrgChart)` | `검증한다(ChartExpectation.of(기대))` 로 위임. 호출부 12곳은 그대로 |
| `SyncVerifier.검증한다(ChartExpectation)` | 새로 추가. ① 은 `기대.chart()`, ②③ 은 `있어야할튜플`/`물어볼후보`, ④ 는 `롤업양성`/`롤업음성`. `새면_안되는_조직들` 은 `ChartExpectation` 으로 옮기고 지운다 |
| `OpenFgaProbe.직접_대조한다(OrgChart, List<String>)` | 시그니처 유지, 내부에서 `ChartExpectation.of(chart)`. 롤업 음성에 형제 가지가 더해진다. 호출부 5곳 그대로 |
| 튜플 수를 세는 4곳 | `ChartExpectation.of(기대).있어야할튜플().size()` — `LdapDeletionGuardScaleTest:62`, `LdapScaleScenarioTest:448`, `DitScaleSyncTest:115`, `ScimLimitsAndRecoveryScaleTest:169` |
| S1-a (`ScimProvisioningOrderScaleTest`) | 손 Check 두 개 → 중간 상태 조직도(모든 직원에 `직원_레코드만_지운다`)를 `검증한다(ChartExpectation.끊긴참조를_허용하며(중간))` 로 전체 검증 |
| 손 Check 두 곳 — `LdapDeletionGuardScaleTest:147` 이하, `ScimScaleScenarioTest:442` 이하 | §7 의 변이 증명 후 손 Check 와 "하네스는 이들을 아예 묻지 않는다" 주석을 지운다. 하네스는 50명 표본이 아니라 지운 사람 **전원**을 묻는다 |
| `SyncVerifier` 자바독의 "한계" | "에디터로 지운 멤버십은 잡는다. 조직도에 한 번도 없었던 튜플은 여전히 못 잡는다(열거 금지)" 로 고친다 |

**그대로 두는 `TupleMapper` 사용처:** `TupleMapperTest`, `CandidateTuplesTest`, `DitStrategyTest`,
`TwoStrategiesSameShapeTest`, `OrgChartFixtureTest` (`TupleMapper` 자체를 검증함), `SyncVerifierTest:40`,
`IncrementalSyncUseCaseTest:50` (가짜 저장소를 "올바른 앱" 으로 채움 — 운영이 쓸 것을 흉내 내는 것이 목적).

`TupleMapper.candidateTuples` 는 운영(`IncrementalSyncUseCase`, `SnapshotArchiveUseCase`)도 쓰므로 남는다.

## 7. 검증 방법

**단위 테스트** (`core/src/test`, BDD given/when/then, AssertJ, 한글 `@DisplayName`)

- `ChartExpectationTest`
  - 활성 직원만 `direct_member`, 존재하는 하위 조직만 `child`
  - 비활성 직원은 후보에 있고, 있어야할튜플에는 없다
  - 끊긴 참조: `of()` 는 거부(정렬된 목록), `끊긴참조를_허용하며()` 는 튜플 없이 후보로 묻는다
  - 순환은 두 팩토리 모두 거부
  - 부모가 둘인 조직의 조상은 두 갈래 모두
  - 롤업 음성에 자손과 형제 가지가 들어가고 기대소속은 빠진다
  - 지워진 멤버십이 후보에 들어가고, 다시 넣으면 있어야할튜플에도 있다
- `ChartExpectationAgreementTest` — **두 계산이 만나는 유일한 곳**
  - 순환도 끊긴 참조도 없는 조직도(`OrgChartFixture.오천명()` 과 작은 손 조직도)에서
    `있어야할튜플 == TupleMapper.toTuples(..).tuples()`, `물어볼후보 ⊇ TupleMapper.candidateTuples(..)`
- `OrgChartEditorTest` — 편집 종류마다 지워진 멤버십이 기억되는지. 조직 삭제 시 그 조직 안의 멤버십까지
- `SyncVerifierTest` — 가짜 앱이 옮긴 직원의 옛 튜플을 남기면 ③이 잡는다

**변이로 증명** (각각 확인 후 되돌린다)

| 변이 | 기대 |
|---|---|
| `TupleMapper` 의 비활성 필터를 끈다 | 새 하네스는 실패한다 (`SyncVerifierTest` 의 가짜 앱이 `TupleMapper` 출력으로 채워지므로). 옛 하네스였다면 통과했다 — **이 슬라이드의 핵심 증거** |
| `TupleMapper` 가 정당한 child 간선 하나를 떨어뜨린다 | ②가 잡는다 (리뷰 §3 의 실패 모양) |
| `ChartExpectation` 이 지워진 멤버십을 후보에 넣지 않게 한다 | 옛 튜플을 남기는 `SyncVerifierTest` 가 실패한다 |
| 운영의 OpenFGA 삭제를 no-op 으로 만든다 | `LdapDeletionGuardScaleTest` 가 **손 Check 없이 하네스 ③으로** 실패한다. 손 Check 를 지우기 전 필수 |

**전체 규모 테스트** — 긴 Gradle 스위트는 메인 세션이 돌린다 (서브에이전트는 모듈 단위 빠른 테스트만).
지워진 멤버십이 쌓이면 Check 가 늘어나므로 규모 시나리오의 검증 시간을 전후로 재서 §10 에 기록한다.

## 8. 파일 위치 규칙

- 새 코드는 전부 `testFixtures`(공유 테스트 도구)와 `test`(테스트)에 둔다. **`src/main` 은 바뀌지 않는다.**
- `testFixtures` 는 Gradle `java-test-fixtures` 플러그인의 별도 소스셋이고, 다른 모듈은
  `testImplementation testFixtures(...)` 로만 가져온다. 운영 코드가 import 하면 컴파일 에러다.
- 배포 jar(`app-ldap`, `app-scim` bootJar)에 test-fixtures jar 가 들어가지 않는 것을 이 슬라이드 시작 시점에
  확인했다 (9/8~9/10 빌드본 기준, `core` jar 의 fixture 클래스 0개).

## 9. 범위 밖 — 따로 다룰 것

**`removeUser` 가 GSI 지연 때문에 권한을 남길 가능성.** `IncrementalSyncUseCase.affectedGroupsOf` 는 직원이
속한 조직을 GSI(`findGroupIdsContaining`, 최종 일관성)로 찾는다. 조직에 직원을 추가한 직후 그 직원을 지우면
방금 추가한 조직을 못 찾을 수 있다. 그러면 그 조직은 삭제 전 그림에 없으므로 기준선에도 없고, DynamoDB 에는
삭제된 직원을 가리키는 멤버 줄이, OpenFGA 에는 그 직원의 `direct_member` 가 남는다. 이전에 `containsMember`
로 고친 회귀와 같은 종류다. **코드를 읽고 본 가능성이며 테스트로 확인하지 않았다.** E 이후 별도 조사 슬라이드로.

## 10. 실측

구현 마지막 태스크에서 규모 시나리오별 하네스 검증 시간을 전후로 재서 이 절에 표로 추가한다.

## 11. 이 설계가 말할 수 없는 것

- **조직도에 한 번도 없었던 튜플은 여전히 못 잡는다.** 운영이 엉뚱한 (직원, 조직) 쌍에 튜플을 지어내면
  후보에 없으므로 묻지 않는다. 잡으려면 열거가 필요한데 금지돼 있다. ④ 롤업 음성 표본이 일부를 본다.
- **에디터를 거치지 않은 지움은 기억되지 않는다.** 2인자 생성자로 새로 만든 조직도는 이력이 없다.
  이 슬라이드 뒤에는 최초 조직도를 만드는 곳만 2인자 생성자를 쓴다.
- **코드는 독립이지만 이해는 독립이 아니다.** `ChartExpectation` 과 `TupleMapper` 를 같은 사람(과 AI)이
  같은 설계 문서를 보고 썼다. 설계 문서 자체가 틀렸다면 둘 다 같이 틀린다. 일치 테스트는 둘이 같다는 것만
  말한다.
- **순환의 기대 동작은 여전히 L16/S16 의 손 Check 가 전부다.** `ChartExpectation` 은 순환을 거부할 뿐이다.
- **부모가 둘인 조직이 규모 픽스처에 있는지는 확인하지 않았다.** 없다면 조상 계산의 다중 부모 처리는
  단위 테스트로만 확인된다.
