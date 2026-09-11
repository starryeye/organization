# e2e-verification-harness 브랜치 코드 리뷰 기록

**작성일:** 2026-09-11
**범위:** 브랜치의 앞선 14커밋 (`ad4e63d..5b5273e`, 58파일 8,629줄). 이전 슬라이드들이 빌드·테스트로만
검증하고 코드리뷰를 거치지 않았던 부분이다. 스냅샷 축소 슬라이드(`5b5273e..060e03f`)는 자기 사이클에서
따로 리뷰받았다.

**그중 9커밋(`6496392`, `1d90d8c..b983bd2`)은 PR #15·#16 으로 이미 main 에 들어가 있다.** 그래서 B·C·D·F·G 는 main 에 있는 코드를 고친 것이고, 이 수정들이 담긴 다음 PR 이 들어가기 전까지 main 의 하네스는 그 결함 — 에러를 `false` 로 삼키는 프로브, 순환에 대해 아무것도 묻지 않는 S16 — 을 그대로 갖고 있다. A 만 아직 main 에 없는 `cbe39de` 의 결함이다.
**방법:** 세 갈래로 나눠 각각 독립 리뷰어(opus)에게 맡겼다 — 운영 코드 386줄 / 픽스처·하네스 1,936줄 /
규모 시나리오 3,100줄.

---

## 1. 요약

**전면적 허위는 없었다.** "전부 true 를 돌려주는 프로브" 같은 것은 없었다. `SyncVerifier` 는 단계마다
하나씩 망가뜨려 특정 메시지를 단언하는 11개짜리 변이 스위트를 갖고 있고, ④는 조상 도달뿐 아니라 형제
가지로 새는지까지 묻는다. 열거 API 는 어디에도 없다.

**구멍은 특정 지점에 있었다.** 그중 머지 전에 막아야 한다고 판단한 여섯 건(A·B·C·D·F·G)을 고쳤고,
하나(E)는 설계 변경이라 별도 슬라이드로, 하나(H)는 실제 AD 연동 전에 풀어야 할 것으로 기록한다.

---

## 2. 고친 것

| # | 등급 | 무엇 | 커밋 |
|---|---|---|---|
| A | Important(블로커) | 범위 검색 이어받기를 **정규화된 조직코드**로 키를 잡아, `IdNormalizer` 가 같은 코드로 만드는 두 조직(`제1공장 A` / `제1공장:A`)의 멤버 목록이 뒤바뀌었다 — 1,597개 가짜 권한. `realDn` 으로 키를 잡도록 고치고, 한 번도 실행된 적 없던 그 경로에 테스트를 붙였다 | `addb1d3` |
| B | Critical | `OpenFgaProbe` 가 에러·무응답 항목을 `false` 로 기록했다. 그런데 `false` 가 이 프로브가 묻는 것 대부분의 **기대값**(아래로 안 새는지)이라, 한 청크가 통째로 에러 나도 전부 통과했다. 감사 대상인 `OpenFgaRelationTupleChecker.toFound` 는 같은 경우에 전부 던진다. 프로브도 던지게 하고, 이 파일에 처음으로 자기 테스트를 붙였다 | `857e74b` |
| C | Critical | S16 이 순환에 대해 **아무것도 단언하지 않았다** — 다른 부문 직원 한 명의 권한만 봤다(0.025초). 순환을 닫는 간선의 부재와 가지 안 롤업 생존을 직접 묻게 했다 | `ed18552` |
| D | Important | L12-a(1,992명 삭제)와 S15(20명 제외)가 삭제를 **자기보고 카운트와 DynamoDB 로만** 확인했다. 지워진 사람은 기대 조직도에서도 빠져 하네스 후보 집합(`candidateTuples`)의 사각지대에 떨어진다. OpenFGA 에 직접 묻게 했다 | `ed18552` |
| F | Important | L16 이 `.sorted()` 없이 자손을 골라 JVM 실행마다 다른 조직에 순환을 걸었다(S16 은 정렬돼 있었다). 고정하고 S16 과 같은 것을 단언하게 했다 | `ed18552` |
| G | Important | `ScimRebuildLockScaleTest` 의 락 대기 `500ms` 가 경합 없는 기준선 적재 6,476건 전체에 걸려 CI 플레이크가 확정적이었다. 값이 컨텍스트 시작 때 빈에 박혀 S18-b 에만 줄 수 없으므로, **기준선 적재만 503 을 재시도**하게 했다 — 실제 IdP 가 503 에 하는 행동 그대로다(설계 §1.1). S18-b 의 500ms 와 단언은 그대로다 | `a845238` |
| — | — | C 를 고치며 S16 에 달린 주석이 순환 방어를 `TupleMapper.removeCycles` 로 설명했는데, 그건 LDAP 전체 동기화의 기제다. SCIM 은 최소 스냅샷이라 `IncrementalSyncUseCase.withoutCycleCreatingEdges` 가 거른다. 바로잡았다 | `70715e9` |

### 변이로 확인한 것

고친 테스트가 정말로 잡는지, 방어를 일부러 끄고 돌려 봤다.

| 끈 방어 | 경로 | 결과 |
|---|---|---|
| `TupleMapper.removeCycles` 가 back edge 를 버리지 않게 | LDAP (L16) | **L16 실패** — `순환을 닫는 간선(조상이 자손의 child)이 그대로 남아 있다`. 나머지 11개 통과 |
| `IncrementalSyncUseCase.withoutCycleCreatingEdges` 를 건너뛰게 | SCIM (S16) | **S16 실패** — 같은 메시지. 나머지 15개 통과. 이 변이에서 `TupleMapper.removeCycles` 는 그 간선을 거르지 못했다 — SCIM 의 최소 스냅샷에는 조상 사슬이 없어서다. 두 경로의 순환 방어가 서로 다른 곳에 있다는 것이 이것으로 확인된다 |

A·B 는 각 수정의 테스트가 **수정 전 코드에서 실패하는 것**을 구현자가 확인했다.

---

## 3. 별도 슬라이드로 — E

**하네스 ②단계가 기대 튜플을 운영 코드와 같은 `TupleMapper` 에서 뽑는다.** Critical.

`SyncVerifier` 와 `OpenFgaProbe` 가 `TupleMapper.toTuples(기대.snapshot())` 을 부르는데, `FullSyncUseCase`·
`RebuildUseCase`·`ScimRebuildUseCase` 가 **무엇을 쓸지 정할 때 부르는 바로 그 함수**다. ②는 "앱이
`f(state)` 를 썼나" 를 묻고 하네스의 답도 `f(expected)` 다. **매핑 규칙 자체는 어디서도 독립적으로
진술되지 않는다.**

실패 모양: `TupleMapper` 가 정당한 child 간선 하나를 떨어뜨리면 ②와 ③이 같은 이유로 같이 틀린다.
①은 DynamoDB 가 맞으니 통과한다. ④만 볼 수 있는데 30명 표본이라 한 조직 가지(약 28명)를 놓칠 확률이
약 90% 다. 그 가지 아래 수천 명이 조용히 권한을 잃는다.

고치는 방향: ②의 기대값을 `OrgChart` 에서 직접 유도한다 — 활성 사용자의 소속마다 `direct_member`
하나, 조직→조직 간선마다 `child` 하나. `OrgChart.멤버십수()`/`child간선수()` 가 이미 같은 것을 센다.

**미룬 이유:** 하네스의 기대값 원천을 바꾸는 것이라 이미 통과하던 규모 시나리오 전부가 새 기준으로 다시
검증돼야 한다. 설계 판단이 필요한 크기라 스펙부터 쓴다.

---

## 4. 실제 AD 연동 전에 — H 외

AD 범위 검색 설계 문서(`2026-09-09-ad-range-retrieval-design.md`) §6 에도 적었다.

**H. `dnOf` 의 평면 트리 가정이 실제 AD 의 DN 과 맞지 않는다.** Important. 기존 결함이다.
`GroupOfNamesStrategy.dnOf` 는 모든 엔트리가 검색 베이스 바로 아래 있고 RDN 이 식별 속성이라고 가정해
`sAMAccountName=hgd,ou=users,...` 를 만든다. 실제 AD 사용자는 `CN=Hong Gildong,OU=Seoul,OU=Users,...` 다.
`member` 값은 서버가 준 DN 이므로 이 재구성과 **하나도 일치하지 않고, 모든 그룹이 멤버 0명**으로
읽힌다. 삭제 가드(30%)가 그 회차를 중단시킬 가능성이 높지만 보장되지는 않는다. 코드 주석은 "정확한
형태보다 일관성이 중요하다" 고 하는데, 서버 DN 과의 대조에는 틀린 말이다.

**범위 이어받기에서 알아보지 못한 속성을 "완료" 로 읽는다.** Important. `RangedAttributeReader.읽는다`
는 알아본 것이 없으면 `Chunk.완결(빈 목록)` 을 돌려주고, `전부_읽는다` 가 그걸 종료로 받는다. 이어받기
응답에 속성이 아예 없거나 `member;range=0-1499;<다른옵션>` 처럼 정규식이 못 받는 모양이면 그때까지 읽은
것이 완료로 처리된다. 명세를 따르는 AD 가 이런 응답을 준다고 보이지는 못했다 — 클래스가 스스로 내건
"짐작하지 않는다" 는 불변식의 구멍이다.

---

## 5. 기록만 하는 것

### 운영 코드 (Minor)

- `GroupOfNamesStrategy.values(Attributes, String)` — 호출자가 없는 죽은 코드인데, 읽기 실패를 삼키고
  읽은 만큼을 돌려준다. 설계 §3.4 가 없앤 바로 그 모양이다. 누가 다시 쓰기 전에 지울 것
- `RawEntry` 의 `dn`(재구성한 절대 DN)과 `realDn`(서버가 준 베이스 상대 DN)이 이름으로 구별되지 않는다.
  사용자 쪽은 `realDn = dn` 이라 `@param realDn` 설명이 거짓이다. 사용자 쪽에 범위 읽기를 붙이는 날 함정이 된다
- 이어받기 다음 위치를 받은 개수로 센다. 명세는 `high + 1` 이다. 방향은 안전하다(짧은 조각이면 중복만
  생기고 건너뛰지 않는다)
- 템플릿 설정이 `LdapTemplates` 외에 `EmbeddedLdapSupport`·`LdifRendererTest` 에 한 벌씩 더 있다.
  `LdapTemplates` 자바독이 경고하는 바로 그 표류가 운영과 검증 사이에서 난다
- `GroupOfNamesStrategy` 의 `java.util.Map` 중복 import, `RangedAttributeReader` 의 풀리지 않는 자바독 링크

### 픽스처·하네스

| 등급 | 무엇 |
|---|---|
| Important | 결정성 미고정 세 곳 — `SyncVerifierTest.java:102`, `:224` 의 `iterator().next()`, `ScimRequestRenderer.scimGroup` 의 멤버 배열 순서(시드 파일 바이트가 실행마다 다름). 그걸 막으려던 `ScimRequestRendererTest` 는 같은 JVM 에서 두 번 렌더링해 salt 차이를 원리상 볼 수 없다 |
| Important | `OrgChart.조상들` 에 순환 가드가 없다. 기대 조직도에 순환이 들어가는 날 결과가 아니라 10분 타임아웃으로 나타난다 |
| Important | `OrgChartEditor` 에 비활성화 연산이 없어 테스트 세 곳이 손으로 복제한다. LDAP 쪽은 비활성화를 표현할 방법이 아예 없어 `SyncVerifier` 의 비활성 분기가 LDAP 에서는 죽은 코드다 |
| Important | 프로브의 음성 집합(자손만)이 `SyncVerifier`(자손 + 형제 가지)보다 약하다. "같은 사실을 서로 다른 경로로 두 번 묻는다" 는 서술보다 증명하는 것이 적다 |
| Minor | `Landmarks` 자바독의 "직속 500명"(실제 1,600), "가장 짧은 롤업 체인"(테스트와 모순) |
| Minor | `RollupSampling` 이 표본 0개가 되면 ④가 Check 없이 조용히 통과한다(지금 호출자는 없음) |
| Minor | `LdifRenderer` 가 DN 을 RFC 4514 로 이스케이프하지 않는다(지금 id 는 안전한 문자뿐) |
| Minor | 픽스처의 모든 id 가 `[A-Za-z0-9._]` 라 `IdNormalizer` 가 규모에서 한 번도 동작하지 않는다 — A 를 규모 테스트가 못 잡은 이유이기도 하다 |
| Minor | LDAP 쪽 `active` 가 늘 참이라 퇴사자 권한 생존은 SCIM 으로만 검증된다 |
| Minor | `attr(...)`·LDIF 루트 뼈대 중복, `DitLdifRenderer` 가 소속 없는 직원을 빠뜨림, 시드 크기를 문자 수로 셈 |

### 규모 시나리오 (Minor)

- 하드코딩: `ScimLimitsAndRecoveryScaleTest` 의 `"DEV5_0"`(→ `Landmarks.대상팀`), `isGreaterThan(5_000)`(→ 픽스처에서 유도)
- 낡은 주석 숫자: `LdapPagingScaleTest` "4,024명"(5,124), `LdapDeletionGuardScaleTest` "3,879"(4,649), `AdminQueryScaleTest` "500명"
- `ScimRebuildLockScaleTest` 의 벽시계 단언 `isGreaterThan(2_000L)` 과 `Thread.sleep(700)` — 리스 갱신 카운터 지표로 대체할 수 있다
- L4 가 겸직 직원을 제외하지 않는다(지금은 산술적으로 우연히 안전)
- 12개 중 6개 클래스가 프로브 교차 검증을 건너뛴다. `DitScaleSyncTest` 는 자기 자바독과 모순된다
- 컨테이너 블록 ×12, `검증한다()` ×8(두 변종), `성립하는가` ×7 등 약 400줄 중복. 비용 측정 테스트 둘은 L1·S2 와 거의 같다
- `ScimLimitsAndRecoveryScaleTest` 의 `runs.get(0)` 이 순서에 묶여 있다

---

## 6. 이 리뷰가 말할 수 없는 것

- **실제 AD 로는 아무것도 검증하지 못했다.** 범위 검색도, H 도 합성 응답과 코드 읽기에 기댄 판단이다.
- 리뷰어는 Gradle 을 돌리지 않고 코드를 읽었다. 고친 것은 전부 전체 빌드와, C·F 는 변이로 확인했다.
  최종 빌드: `BUILD SUCCESSFUL in 11m 10s`, 결과파일 88 / 테스트 578 / 실패 0 / 에러 0.
