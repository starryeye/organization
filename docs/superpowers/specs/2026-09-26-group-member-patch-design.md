# 조직 멤버 PATCH — 값 붙은 remove 거절, 멤버 증분 처리 — 설계

> 브랜치 `scim-group-member-delta` (origin/main `1b5f5ed` 에서 분기).
> 발단: S-3 최종 리뷰가 찾은 원래 있던 결함(백로그 7-1). S-3 설계 [`2026-09-26-scim-name-parts-design.md`](2026-09-26-scim-name-parts-design.md) §9.

## 1. 문제

### 1.1 Entra 기본 모드의 멤버 빼기가 조직을 통째로 비운다

Entra 기본 모드는 멤버 한 명을 이렇게 뺀다.

```json
{"op":"Remove","path":"members","value":[{"value":"u1091"}]}
```

`ScimPatchApplier` 조직 쪽은 `path` 가 `members` 인 `remove` 를 `withMembers(group, Set.of())` 로 처리한다 — **`value` 를 보지
않고 멤버 전원을 뺀다.** 조직 전원의 `direct_member`·`child` 튜플이 지워지고, Entra 는 2xx 를 받았으니 다시 보내지 않는다.

RFC 7644 §3.5.2.2 를 글자 그대로 읽으면 이 동작이 맞다 — "If the target location is a multi-valued attribute and no filter is
specified, the attribute and all values are removed". `remove` 의 `value` 는 RFC 가 정의하지 않은 칸이다.

### 1.2 멤버 한 명을 바꿔도 조직 전체를 읽는다

조직 PATCH 는 지금 이렇게 돈다 — 핸들러가 락 밖에서 `findGroup`(조직 파티션 전체) → 메모리에서 연산 적용 → `upsertGroup` 이
**전역 쓰기 락**(`LOCK#SCIM_WRITE`, 대기 한도 3초) 안에서 `findGroup` 을 다시, 상위 조직들의 `findGroup`, 변경 전·후 스냅샷마다
멤버 직원 전원 `findUser`(동시 8), 후보 튜플 전원 BatchCheck(50개씩 **차례로**), `saveGroup` 이 멤버 줄 전체 조회.

멤버 N 명 조직에서 한 명을 바꾸면 조직 파티션 전체 조회 3번 + `GetItem` 약 2N + BatchCheck N/50 번. N = 10만이면 읽기 20만 건,
BatchCheck 2,000 번 — GetItem 5ms·BatchCheck 10~20ms 로 잡으면(추정) **2~3분 동안 전역 락을 쥔다.** 그동안 다른 SCIM 쓰기는 3초
기다리다 실패한다. 5천 명 부서도 약 6초다.

### 1.3 동시 PATCH 가 서로의 변경을 지운다

핸들러가 **락 밖에서** 읽은 멤버 목록으로 목표 목록을 계산한다. 인사팀 [김, 이, 박] 에 "A 추가" 와 "B 추가" 가 동시에 오면 둘 다
[김, 이, 박] 을 읽는다 → A 요청이 [김, 이, 박, A] 를 쓰고 → B 요청이 [김, 이, 박, B] 를 목표로 락 안에서 비교해 **A 를 뺀다.**
IdP 는 A 추가에 2xx 를 받았으니 다시 보내지 않는다.

### 1.4 그 밖에 조직 크기를 따라가는 읽기

- **순환 검사**(`reaches` → `childIdsOf`)가 하위 조직을 내려갈 때 각 조직의 `findGroup`(직원 줄까지 전부)을 읽고 하위 조직 id 만 고른다.
- **`expandWithReferencedGroups`** 가 참조된 하위 조직의 존재만 확인하려고 `findGroup` 을 읽고 멤버를 버린다.
- **PATCH 응답**은 IdP 가 멤버를 달라고 하면 쓰기 뒤에 `findGroup` 을 읽는다(Entra 는 `excludedAttributes=members` 를 붙여 안 읽음).
- **PUT·POST 의 존재 확인**이 `findGroup` 을 읽는다.

## 2. 결정 — 사용자와 정한 것 (2026-09-26)

| 주제 | 결정 | 버린 안 |
|---|---|---|
| 값 붙은 `remove members` | **400 `invalidValue`**. Entra 는 `aadOptscim062020` 을 켜야 한다(README 에 필수로) | 적힌 멤버만 삭제(AWS 식), 적힌 멤버만 + PATCH 로 전원 비우기 금지 |
| 범위 | **오류 + 조직 멤버 증분(1.2) + 락 안에서 읽기(1.3)**. 직원 쪽(락 밖 읽기, PATCH/PUT `userName` 중복)은 **바로 다음 슬라이드** | 오류만, 직원까지 한 번에 |
| 접근 | **A. 바뀌는 멤버만 보는 좁힌 그림** — 지금의 비교 엔진(`diffAndApply`)을 그대로 쓰고 그림만 좁힌다 | B. 전체 비교를 유지하고 빠르게, C. 조직별 락 (§10) |
| 추가 범위 | **전체 교체는 멤버 아이디만 읽어 차이만 처리**, **조직 PATCH 응답은 204** | 204 만, 둘 다 빼기 |
| PUT | 전체 교체와 같은 길 | — (1절 제시 때 이의 없음) |

**거절을 고른 이유(사용자와 합의).** 이 서버는 우리 회사 디렉터리를 동기화하므로 Entra 설정(`?aadOptscim062020`)을 우리가 바꿀 수
있다. 그 옵션은 Entra 의 비표준 동작 넷(비활성화, 값 하나 추가, 여러 속성 교체, 멤버 빼기)을 표준 모양으로 바꾸는데, 나머지 셋은
S-3 까지 이미 받는다. 거절은 Entra 프로비저닝 로그에 보이는 **큰 실패**이고, 추측해서 받아 주면 다른 IdP 가 다른 뜻으로 보냈을 때
알 길이 없다. 대가: 옵션을 켜기 전까지 Entra 의 멤버 빼기는 반영되지 않는다(옛 소속 권한이 남음).

## 3. 조사 — IdP 와 SCIM 서버는 멤버 빼기를 어떻게 하나 (2026-09-26)

공식 문서·공개 소스 기준. "미확인" 은 공개 자료에서 요청 모양을 찾지 못한 것이다.

**보내는 쪽(IdP)**

| IdP | 멤버 한 명 빼기 | 비고 |
|---|---|---|
| Entra ID 기본 | `{"op":"Remove","path":"members","value":[{"value":"…"}]}` | [MS 호환성 문서](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-config-problem-scim-compatibility). 2018 년부터 "곧 기본값" 이라 했으나 아직 선택 |
| Entra ID `aadOptscim062020` | `{"op":"remove","path":"members[value eq \"…\"]"}` | 같은 문서. 옵션은 멤버 빼기 외 셋도 바꾼다 |
| Okta | `members[value eq "…"]` 필터. 추가는 `add members [...]`, 통째 밀기는 `replace members [전체]` | [Okta SCIM 2.0](https://developer.okta.com/docs/api/openapi/okta-scim/guides/scim-20/) |
| Authentik | 필터 방식, PATCH 미지원 대상엔 PUT | [authentik SCIM provider](https://docs.goauthentik.io/add-secure-apps/providers/scim/) |
| Keycloak | 공식 발신 없음. 커뮤니티 플러그인은 **매번 `replace members [전체]`**, 비울 때 `remove members` + `value:null` | [mitodl/keycloak-scim GroupAdapter](https://github.com/mitodl/keycloak-scim/blob/eec8ecd14971886f0d00f3dc688b587c3002f252/src/main/java/sh/libre/scim/core/GroupAdapter.java#L158-L196) |
| Google Workspace | 그룹을 보내지 않음(직원만) | Google Workspace 관리자 도움말 |
| PingOne | 그룹 미지원(`/v2/Groups` 501) | PingOne SCIM API |
| AWS, Auth0 | 보내는 기능 없음(받는 쪽 전용) | |
| OneLogin | 문서 예시는 SCIM 1.x 식(멤버마다 `operation`) | 2.0 커넥터의 실제 모양 미확인 |
| JumpCloud, CyberArk, Rippling, PingFederate, SailPoint | 미확인 | SailPoint 는 직원 `groups` 를 PATCH 하는 모드도 있음 |

**받는 쪽(SCIM 서버·라이브러리)이 값 붙은 remove 를 받으면**

| 서버 | 처리 | 출처 |
|---|---|---|
| AWS IAM Identity Center | 적힌 멤버만 삭제. 전원 삭제·전체 교체는 400 | [PatchGroup](https://docs.aws.amazon.com/singlesignon/latest/developerguide/patchgroup.html), [Limitations](https://docs.aws.amazon.com/singlesignon/latest/developerguide/limitations.html) |
| Captain-P-Goldfish SCIM-SDK | **기본은 400 `invalidValue`**("values must not be set for remove operation"), 단 `MsAzurePatchRemoveRebuilder` 우회가 기본으로 켜져 적힌 멤버만 삭제 | [ResourceEndpointHandler](https://github.com/Captain-P-Goldfish/SCIM-SDK/blob/4614a439087a000956282326bd28fe5cec281caf/scim-sdk-server/src/main/java/de/captaingoldfish/scim/sdk/server/endpoints/ResourceEndpointHandler.java#L116-L137), [MsAzurePatchRemoveRebuilder](https://github.com/Captain-P-Goldfish/SCIM-SDK/blob/4614a439087a000956282326bd28fe5cec281caf/scim-sdk-server/src/main/java/de/captaingoldfish/scim/sdk/server/patch/workarounds/msazure/MsAzurePatchRemoveRebuilder.java#L69-L76) |
| UnboundID SCIM2 SDK (Ping) | 원래 전원 삭제, v4.1.0(2025-10)부터 `members` 에 한해 적힌 멤버만 | [CHANGELOG](https://github.com/pingidentity/scim2/blob/master/CHANGELOG.md), [PatchOperation](https://github.com/pingidentity/scim2/blob/cfe7b2faf53c0203cfc2c4abb9d131ad7432915d/scim2-sdk-common/src/main/java/com/unboundid/scim2/common/messages/PatchOperation.java) |
| Keycloak 26.x SCIM | **200 을 주고 아무도 빼지 않음**(remove 의 `value` 를 읽지 않음) | [AttributeMapper](https://github.com/keycloak/keycloak/blob/6688a3d63f59e0c4a9131bfdd556c4312799f04e/scim/core/src/main/java/org/keycloak/scim/resource/schema/attribute/AttributeMapper.java#L101-L125), [#52752](https://github.com/keycloak/keycloak/issues/52752) |
| Entra 자신(Graph `rp/scim`) | 필터 방식만, 한 요청에 한 명 | [Entra SCIM API](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/entra-id-scim-api-reference) |
| Google Cloud·Workspace 수신, Auth0 수신, Okta 수신 | 미확인(문서에 없음) | |

RFC 정오표나 SCIM 개정 초안 중 이 모호함을 다루는 것은 없다(2026-09 기준, RFC 7644 errata 7122 는 ABNF 문제).

**PATCH 응답.** Entra: "Update to the group PATCH request should yield an HTTP 204 No Content in the response. Returning a body with a
list of all the members isn't advisable" ([Entra SCIM 튜토리얼](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)).
Okta: "The SCIM server response to PATCH method requests can also be an HTTP 204 response" (Okta SCIM 2.0). RFC 7644 §3.5.2: 200 과
리소스, 또는 204.

## 4. 요청 해석 — 조직 PATCH·PUT 을 "변경" 으로 정리한다

저장소를 읽기 전에, 요청 전체를 **변경**(`GroupChange`, core) 하나로 정리한다. 변경은 둘 중 하나다.

- **멤버 증분** — 이름 변경(선택) + 순서 있는 멤버 연산 목록(추가 `ref`, 빼기 `ref`, 종류 모르는 빼기 `id`).
- **전체 교체** — 목표 멤버 목록 + 이름·`externalId`(PUT).

| 요청 | 변경 |
|---|---|
| `add members [...]` | 증분: 추가 |
| `remove members[value eq "x"]` | 증분: 종류 모르는 빼기 `x` |
| `remove members` + `value` 칸(빈 목록 포함) | **400 `invalidValue`** (§7) |
| `remove members`, `value` 없음 또는 `null` | 전체 교체(빈 목록) — 표준대로 전원 빼기 |
| `replace members [...]` | 전체 교체 |
| `replace displayName`, 경로 없는 `{"displayName": …}` | 증분: 이름만 |
| 경로 없는 `{"members": [...]}` | 전체 교체 |
| `PUT /Groups/{id}` | 전체 교체(이름·`externalId` 포함) |

- **여러 연산은 순서대로 쌓는다** — 지금 코드가 멤버 집합에 순서대로 적용하는 것과 같은 뜻이다. "A 추가 → A 빼기" 는 변화 없음.
  중간에 전체 교체가 나오면 그 목록이 목표가 되고, 뒤의 연산은 목표 목록에 적용한다(저장소 없이 계산된다).
- 400 은 모두 이 단계에서 난다. 요청 하나는 전부 반영되거나 전부 거절된다(지금과 같다).
- 멤버의 `type` 이 빠졌으면 지금처럼 `MemberTypeResolver` 가 저장된 상태로 판정한다.
- 종류 모르는 빼기 `x` 가 직원 `x` 와 하위 조직 `x` 둘 다에 맞으면 지금 규칙(`removeMemberById`) 대로 현재 상태로 한쪽만 고르고
  경고를 남긴다 — 판정은 락 안에서 한다(§5).

## 5. 락 안의 처리

`IncrementalSyncUseCase` 에 조직 변경 입구를 하나 더한다(예: `patchGroup(groupId, GroupChange)`). **모든 판단은 전역 쓰기 락을
잡은 뒤 읽은 값으로 한다.** 핸들러는 락 밖에서 저장소를 읽지 않는다(`MemberTypeResolver` 의 종류 판정만 예외 — 조직 멤버십이 아니라
id 의 종류라 드물게 바뀐다).

**멤버 증분**

1. 조직 META 1건(`findGroupHeader`) — 없으면 404.
2. 연산에 나온 멤버들만 **지금 멤버인지** 멤버 줄로 확인한다(종류 모르는 빼기는 두 종류 다). 연산을 순서대로 되감아 각 멤버의
   변경 전·후 소속을 구한다. 전후가 같은 멤버(이미 있는데 추가, 없는데 빼기)는 떨어진다.
3. 남은 직원은 `findUser`(활성 여부), 하위 조직은 `findGroupHeader`(존재 여부).
4. **좁힌 그림** 두 장 — 이 조직의 멤버 목록을 "바뀌는 멤버만" 으로 좁힌 변경 전·후 스냅샷. 직원은 바뀌는 직원만, 하위 조직은 멤버를
   비운 헤더로 싣는다. 상위 조직은 싣지 않는다. 이것을 지금의 `diffAndApply(before, after, group:G, …)` 에 넣는다 — Check 기준선,
   어긋남 관측, 순환 검사, 쓰기 직전 락 재확인, OpenFGA 쓰기가 전부 그대로다.
5. 커밋 — `reconcileGroupMembers` 를 좁힌 전후에 적용해 실제로 반영된 멤버만 남기고, 그 차이만 멤버 줄로 쓴다(§6). 이름이 바뀌었거나
   멤버 줄이 하나라도 바뀌었으면 META 에 `updatedAt` 을 찍는다(GSI1 쏠림 설계의 "조직의 변경" 과 같은 정의).

**왜 좁혀도 결과가 같은가.** 멤버십에서 나오는 튜플은 `direct_member(user:X, group:G)` 와 `child(group:S, group:G)` 둘뿐이고
(`TupleMapper`), 둘 다 한 멤버와 이 조직만 언급한다. 바뀌지 않는 멤버의 튜플은 두 그림 어디에도 나오지 않아 델타에 들어오지 않는다.
이 조직이 이미 있으므로 상위 조직의 `child(group:G, group:P)` 는 멤버 변경으로 바뀌지 않는다 — 지금 `upsertGroup` 이 상위 조직을 싣는
이유(새로 생기는 조직)는 여기 없다. 하위 조직을 멤버 없는 헤더로 싣는 이유는 `expandWithReferencedGroups` 자바독과 같다.
`직원한명_그림` 이 이미 쓰는 논리다.

**전체 교체**

1. 조직 META + **멤버 아이디 전체**(키만).
2. 목표 목록과 비교해 넣을 멤버·뺄 멤버를 구한다. 이후는 증분의 3~5 와 같다(2 는 이미 알므로 건너뛴다). PUT 은 이름·`externalId`
   도 META 에 반영한다.

비용: 증분은 멤버 k 명을 바꾸면 읽기 약 2k+1, Check k, 쓰기 약 2k+1 — **조직 크기와 무관.** 전체 교체는 여기에 멤버 키 조회 한 번
(10만 개 ≈ 15MB, Query 15회, 추정 1~2초).

**그대로 두는 것.** POST(새 조직)는 지금의 `upsertGroup` — 멤버가 전부 새것이라 비용이 보낸 멤버 수에 비례한다. LDAP 전체 동기화
(`replaceWith`)와 직원 쪽 연산은 바꾸지 않는다.

**동시 요청.** 증분은 락 안에서 "그 멤버가 지금 멤버인가" 만 보고 그 멤버만 쓴다 — 다른 요청이 넣은 멤버를 모른 채 지울 수 없다.
전체 교체는 락 안에서 읽은 목록과 비교한다.

## 6. 저장소

`DirectoryStateRepository` 에 넷을 더한다. 키 구조는 바뀌지 않는다 — **테이블 재생성 불필요.** 락 안의 읽기는 전부 본 테이블을
강한 일관성으로 읽는다(GSI 없음).

| 포트 | 하는 일 | DynamoDB |
|---|---|---|
| 멤버 여부 | 주어진 멤버 중 지금 이 조직 멤버인 것 | 멤버 줄 키 `BatchGetItem`(ConsistentRead), 100개씩, 미처리 키 재시도 |
| 멤버 키 전체 | 전체 교체용 | 조직 파티션 Query, `begins_with(SK, "MEMBER#")`, 키만 |
| 하위 조직 id | 순환 검사용 | Query `begins_with(SK, "MEMBER#GROUP#")` — 직원 줄을 읽지 않는다 |
| 멤버 변경 저장 | 넣을 줄·뺄 줄 + META | 넣기는 소속 줄 → 멤버 줄, 빼기는 멤버 줄 → 소속 줄(①의 불변식). 새 줄의 `addedAt` 은 지금처럼. META 는 바뀌었을 때만 `updatedAt` 을 찍어 쓴다 |

같이 고친다(§1.4): `childIdsOf` 는 "하위 조직 id" 를, `expandWithReferencedGroups` 와 POST 의 존재 확인은 `findGroupHeader` 를
쓴다. PATCH·PUT 의 존재 확인은 락 안(§5 의 1 단계)으로 옮긴다. `findGroup`·`saveGroup` 은 GET·POST·LDAP 이 계속 쓴다.

## 7. 응답·오류·README

- **조직 PATCH 성공은 `204 No Content`**(본문 없음), `attributes` 가 있어도. 잘못된 `attributes`·`excludedAttributes` 는 지금처럼 쓰기 전에
  400. 부분 실패는 지금처럼 5xx(IdP 재시도).
- PUT 은 RFC 7644 §3.5.1 대로 200 + 리소스(지금처럼 `attributes` 규칙 — 멤버를 빼 달라면 헤더만). 직원 PATCH 는 200 + 본문 그대로.
- **값 붙은 remove.** `op` 가 `remove`, `path` 가 `members`, `value` 칸이 있고 `null` 이 아니면(빈 목록 포함) 400 `invalidValue`.
  메시지: `members 에서 멤버를 골라 빼려면 path 에 필터를 쓰세요: members[value eq "<id>"]. Microsoft Entra ID 는 SCIM 테넌트 URL 에
  ?aadOptscim062020 을 붙이면 이 형식으로 보냅니다.` 조직 `members` 에만 적용한다 — 값이 하나인 속성(조직 `displayName`, 직원
  `emails`)은 값 칸이 붙어도 뜻이 갈리지 않는다.
- **README**
  - Entra 연결 절차: SCIM 테넌트 URL 끝에 `?aadOptscim062020` **필수**. 빠뜨리면 멤버 빼기가 400 으로 실패하고 Entra 프로비저닝 로그에 남는다.
  - PATCH 표의 조직 `members` 줄(add / 필터 remove / 값 붙은 remove → 400 / 값 없는 remove / replace), 조직 PATCH 응답 204.
  - 운영 메모: 멤버 추가·빼기는 조직 크기와 무관, 전체 교체는 멤버 키 조회 한 번. 조직 PATCH 는 바뀐 멤버의 권한만 점검한다 —
    조직 전체 점검은 SCIM 재적재.

## 8. 검증

테스트 규칙은 기존과 같다 — Lombok, AssertJ, BDD(given/when/then), 한글 `@DisplayName`.

1. **요청 해석 (connector-scim)** — §4 표의 각 줄. 문서 예시 글자 그대로: Entra 기본 빼기 → 400 `invalidValue`·메시지, Entra 옵션 빼기,
   Okta add·필터 remove·replace. `value: []` → 400, `value: null` → 전원 빼기. 연산 쌓기(추가→빼기, replace→add).
2. **결과가 같다 (core, 가장 중요)** — 같은 변경을 지금 방식(`upsertGroup` 에 전체 목록)과 새 방식으로 각각 돌려 **OpenFGA 튜플과
   저장 상태(멤버·`updatedAt` 변경 여부)가 같은지** 비교한다. 상황: 활성·비활성 직원 추가, 없는 직원 추가(멤버 줄만), 이미 멤버 추가,
   비멤버 빼기, 하위 조직 추가, 순환을 만드는 하위 조직(엣지 버림·멤버십 유지), 직원·하위 조직 같은 id, OpenFGA 쓰기·삭제 일부 실패.
   **부작용을 못 박는다** — 건드리지 않은 멤버의 빠진 튜플은 새 방식에서 그대로 빠져 있다. **비용** — 호출 수를 세는 가짜 저장소로
   5,000명 조직에 한 명 추가 시 `findGroup` 0회, `findUser` 1회, Check 후보 1건.
3. **저장소 (storage-dynamodb)** — 멤버 여부 100개 초과 분할, 멤버 키 조회에 META 없음, 하위 조직 id 조회가 직원 줄을 읽지 않음, 줄 쓰는
   순서, `updatedAt` 은 바뀌었을 때만.
4. **E2E (app-scim)** — Okta·Entra 모양 → 204, 이어서 GET 으로 결과. Entra 기본 빼기 → 400 이고 아무것도 안 바뀜. PUT 이 전체 교체.
   **같은 조직에 멤버 추가 PATCH 여러 개를 동시에** 보내 전부 남는다(지금 코드에서는 실패하는 테스트).
5. **규모 (`@ScaleTest`, 10만 명)** — 멤버 10만 명 조직을 저장소에 직접 적재하고, 한 명 추가·빼기의 DynamoDB 요청 수와 Check 수가 작은
   상수 이하, 전체 교체(한 명 다른 목록)는 멤버 키 조회 1회 + Check 1건. 시간은 기록만 한다(증명은 요청 수로).

## 9. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간을 구현이 끝나면 여기에 적는다.

## 10. 왜 다른 길을 안 갔나

**값 붙은 remove 를 적힌 멤버만 삭제로 받기(AWS, 두 SDK 의 기본 설정).** 표준이 정하지 않은 칸을 추측하는 것이다. 우리는 IdP 설정을
바꿀 수 있고, 그 옵션이 Entra 를 전부 표준 모양으로 바꾼다. 필요해지면 §4 해석 한 곳만 바꾸면 된다.

**B. 전체 비교를 유지하고 빠르게**(BatchGetItem, 병렬 Check). 10~20초로 줄어도 요청마다 10만 명을 읽고 그동안 전역 락을 쥔다.

**C. 조직별 락.** 다른 조직의 요청은 막지 않지만 큰 조직 요청 자체는 여전히 분 단위다. 순환 검사와 `child` 엣지가 여러 조직에 걸쳐
락을 나누기 어렵다.

**전체 교체 때 DB·OpenFGA 를 초기화하고 다시 적재.** 전체 교체는 사고가 아니라 IdP 가 평소에 보내는 요청이다(Okta 통째 밀기, Keycloak
플러그인은 매번). 전체 재적재는 분 단위이고 그동안 모든 권한이 사라진다. 상태 전체를 다시 맞추는 도구는 SCIM 재적재로 이미 있다.

**PATCH 응답을 200 + 멤버로 유지.** 응답을 만들려고 조직 전체를 읽는다. Entra 가 권하지 않고 RFC 가 204 를 허용한다.

## 11. 이 설계가 말할 수 없는 것

- **IdP 동작은 문서 기준이다.** 실제 테넌트 검증은 인증 슬라이드 뒤. IdP 가 10만 명을 한 요청에 담아 보내는지도 모른다.
- **시간은 추정이다.** DynamoDB Local 은 실제 AWS 와 지연이 다르다 — 규모 테스트는 요청 수로 증명한다.
- **전역 락은 그대로다.** 10만 명 조직의 전체 교체는 멤버 키 조회(추정 1~2초) 동안 락을 쥔다 — 다른 요청의 대기 한도 3초와 겹치면
  실패할 수 있다.
- **조직 PATCH 가 조직 전원의 권한 어긋남을 더 이상 덤으로 고치지 않는다.** 바뀐 멤버만 점검하고, 어긋남 지표도 바뀐 멤버에서만 잡힌다.
- **Entra 옵션을 켜기 전까지 Entra 의 멤버 빼기는 반영되지 않는다**(400).

## 12. 범위 밖 (백로그)

- **직원 쪽(바로 다음 슬라이드)** — 직원 PATCH·PUT 이 락 밖에서 읽음(§1.3 과 같은 덮어쓰기), PATCH·PUT 의 `userName` 중복 검사 없음.
- 조직 POST 의 중복 확인이 락 밖이다(같은 조직 POST 두 개가 동시에 오면 둘 다 통과).
- 이 슬라이드 뒤 사용자 제안: 유명 SCIM·LDAP 오픈소스와 코드 비교, 전체 코드·요구사항 리뷰, IdP·IAM 공식 문서 비교 분석.
