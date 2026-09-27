# SCIM 쓰기의 판단을 전부 락 안으로 — 직원 PATCH·PUT 덮어쓰기, userName 중복, 직원 삭제 비용 — 설계

> 브랜치 `scim-user-write-lock` (origin/main `edae803` 에서 분기).
> 발단: 조직 멤버 PATCH 설계 [`2026-09-26-group-member-patch-design.md`](2026-09-26-group-member-patch-design.md) §12 의 "직원 쪽(바로 다음
> 슬라이드)", 조직 POST 중복 확인, 직원 삭제 비용. 동시성 설계 [`2026-09-01-scim-concurrency-design.md`](2026-09-01-scim-concurrency-design.md)
> 의 전역 쓰기 락(`LOCK#SCIM_WRITE`)을 그대로 쓴다.

## 1. 문제

모든 SCIM 쓰기는 이미 전역 쓰기 락으로 한 줄로 선다. 그런데 **판단에 쓰는 읽기가 락 밖에 있는 곳**이 남아 있다.

### 1.1 직원 PATCH·PUT 이 서로의 변경을 지운다

`ScimUserHandler.patch` 는 락 밖에서 `findUser` 로 직원을 읽고 `ScimPatchApplier.applyToUser` 로 새 직원을 계산한 뒤 `upsertUser`
(락 안)에 넘긴다. 락 안에서는 그 계산 결과를 그대로 저장한다.

퇴사자 김철수에게 "active=false" 와 "이름 변경" PATCH 가 거의 동시에 오면 둘 다 "활성 김철수" 를 읽는다 → 비활성화가 먼저 저장되고
→ 이름 변경이 "active=true, 새 이름" 을 저장한다. **퇴사자가 다시 활성이 되고 권한이 되살아난다.** IdP 는 둘 다 2xx 를 받았으니 다시
보내지 않는다. PUT 도 존재 확인만 락 밖이고 결과는 같다.

### 1.2 DELETE 와 PATCH 가 겹치면 지운 직원이 되살아난다

PATCH 가 락 밖에서 직원을 읽은 뒤 DELETE 가 먼저 락을 잡아 직원을 지우면, PATCH 는 락 안에서 읽어 둔 직원을 그대로 저장한다 —
**지운 직원이 다시 생기고 권한 튜플도 다시 쓰인다.**

### 1.3 userName 중복을 POST 만 막고, 그것도 락 밖이다

`rejectDuplicate` 는 POST 에서만, 락 밖에서, 최종 일관성인 GSI1 로 `userName` 을 찾는다. PATCH·PUT 으로 `userName` 을 다른 직원과
같게(대소문자 무시) 바꾸면 그대로 통과한다 — IdP 가 `userName eq "kim"` 으로 찾을 때 두 명이 나와 엉뚱한 사람을 고칠 수 있다.
RFC 7643 은 `userName` 을 `uniqueness: server`, `caseExact: false` 로 정하고, RFC 7644 §3.12 는 PUT·PATCH 에서도 중복이면 409
`uniqueness` 를 쓰게 한다.

### 1.4 직원 한 명을 지울 때 소속 조직 전체를 읽는다

`removeUserInternal` 이 소속 조직마다 `findGroup`(파티션 전체)을 읽고, 저장은 "이 직원을 뺀 전체 목록" 으로 `saveGroup` 을 불러 멤버 키
전체를 다시 읽는다. 10만 명 조직에 속한 직원 한 명을 지우면 락을 쥔 채 20만 줄을 읽는다(퇴사 처리는 흔한 요청이다).

### 1.5 조직 POST·DELETE 의 확인이 락 밖이다

같은 조직 POST 두 개가 동시에 오면 둘 다 201 이다. 조직 DELETE 는 락 밖에서 `findGroup`(파티션 전체)으로 존재를 확인한다.
직원 DELETE 의 존재 확인도 락 밖이다.

## 2. 결정 — 사용자와 정한 것 (2026-09-28)

| 주제 | 결정 | 버린 안 |
|---|---|---|
| 범위 | **1.1~1.5 전부** — 한 주제("쓰기 판단을 락 안으로") | 1.1·1.3 만, 1.1~1.4 |
| userName 중복 | **A. 락 안에서 GSI 로 찾고, 후보를 본 테이블에서 강한 일관성으로 다시 확인** | B. `USERNAME#` 표지판 줄(§9) |
| 직원 PATCH·PUT | **가. "옛 직원 → 새 직원" 계산을 유스케이스에 넘기고 락 안에서 적용** — `applyToUser` 그대로 | 나. `UserChange` 값 객체, 다. 버전 번호 낙관적 잠금(§9) |
| 동시성 검증 | **(a) 락 불변식 + (b) 경합 시나리오 E2E 반복 + (c) 두 인스턴스** | (a)(b), (b)만 |

## 3. 유스케이스 입구와 락 안의 흐름

핸들러는 요청 본문을 해석만 하고 **저장소를 읽지 않는다**(멤버 `type` 판정의 `MemberTypeResolver` 만 예외 — 조직 멤버 PATCH 설계와
같다). 판단은 전부 `IncrementalSyncUseCase` 안, `withLock` 이 락을 잡은 뒤에 한다.

| 요청 | 입구 | 락 안에서 | 결과 |
|---|---|---|---|
| 직원 POST | **`createUser(user)`** (새) | 아이디(본 테이블 `findUser`)·`userName`(§4) 중복 확인 → 지금의 `upsertUserInternal` | 중복이면 409 |
| 직원 PATCH | **`changeUser(id, 계산)`** (새) | `findUser`(없으면 빈 결과) → 계산 적용(`applyToUser`) → `userName` 이 바뀌었으면 §4 → `upsertUserInternal` | 규칙 위반은 지금처럼 400 |
| 직원 PUT | `changeUser(id, 계산)` | 위와 같다. 계산은 "본문으로 교체, 아이디는 경로 것" | |
| 직원 DELETE | `removeUser(id)` | `findUser`(없으면 빈 결과) → §5 | |
| 조직 POST | **`createGroup(group)`** (새) | `findGroupHeader` 가 있으면 충돌 → 지금의 `upsertGroupInternal` | 있으면 409 |
| 조직 PATCH·PUT | `changeGroup` | 그대로(조직 멤버 PATCH 설계) | |
| 조직 DELETE | `removeGroup(id)` | `findGroup`(없으면 빈 결과) → 지금의 삭제 흐름 | 핸들러의 락 밖 전체 읽기는 없어진다 |

- **계산**은 `UnaryOperator<DirectoryUser>` 처럼 순수 함수다. core 는 그것이 SCIM PATCH 인지 모른다. 계산이 던지는 예외
  (`ScimException` 400)는 락 안에서 그대로 나오고, `withLock` 이 락을 반납한다. 아무것도 쓰지 않는다.
- **없음은 빈 결과**다 — 핸들러가 404 로 바꾼다(`changeGroup` 과 같은 방식). `removeUser`·`removeGroup` 은 지금 대상이 없으면
  `noChange` 를 돌려주는데 빈 결과로 바꾼다.
- **중복은 core 예외 `DirectoryConflictException`** 이다. SCIM 라우터가 409 `uniqueness` 로 바꾼다(`LockUnavailableException` → 503 과 같은
  자리).
- 직원 저장 흐름(`upsertUserInternal` — 소속 조직 헤더만 읽고, 그 직원의 튜플만 비교하고, 새 직원은 실패하면 만들지 않는 규칙)은 그대로다.

**동시 요청.** 락을 두 번째로 잡은 요청은 앞 요청이 저장한 직원을 읽어 계산한다 — 앞의 변경을 지우지 않는다(1.1). 앞 요청이 DELETE 였다면
직원이 없어 404 다 — 되살리지 않는다(1.2).

## 4. userName 중복 규칙 (A)

- **언제:** 직원 POST 는 항상. PATCH·PUT 은 계산을 적용한 뒤 `userName` 이 **글자 그대로** 바뀌었을 때만.
- **어떻게(락 안):**
  1. `findUserIdsByUserName(새 userName)` — GSI1, 대소문자 무시.
  2. 후보에서 **자기 자신을 뺀다**(PATCH·PUT). 그래서 "kim" → "Kim" 처럼 대소문자만 바꾸는 것은 통과한다.
  3. 남은 후보마다 `findUser`(본 테이블, 강한 일관성)로 다시 읽어, 레코드가 있고 `userName` 이 여전히 같을(대소문자 무시) 때만 중복이다 —
     방금 지워졌거나 이름을 바꾼 사람이 GSI 에 잠깐 남아 있어도 잘못된 409 를 내지 않는다.
  4. 중복이면 `DirectoryConflictException` → 409 `uniqueness`, 메시지는 지금 POST 와 같다:
     `이미 같은 userName 을 쓰는 직원이 있습니다: userName=<이름>, id=<아이디>`.
- **아이디 중복(POST):** `findUser(id)` 가 있으면 409 `이미 존재하는 직원입니다: <아이디>` — 지금 규칙을 락 안으로 옮긴다.
- 빈 `userName` 은 지금처럼 거절한다(POST 400 `invalidSyntax`, PATCH 400 `invalidValue`). LDAP 전체 동기화는 이 확인을 거치지 않는다.

## 5. 직원 삭제

1. 소속 조직은 **헤더만** 읽는다 — `upsertUser` 가 이미 쓰는 `affectedGroupHeadersOf`.
2. 그림은 지금과 같다(`직원한명_그림` — 이 직원의 튜플만).
3. 커밋: 조직마다, 이 직원의 튜플이 원래 있었는데 삭제되지 않았으면 멤버십을 **그대로 두고**(지금의 `reconcileRemovedMember` 와 같은 판단),
   아니면 `saveGroupChange(헤더, ∅, {이 직원})` — 그 직원의 멤버 줄·소속 줄만 지우고 META 에 `updatedAt` 을 찍는다.
4. 하나라도 실패하면 직원 레코드를 지우지 않는다(지금과 같다). 모두 성공하면 `deleteUser`.

비용: 조직 m 개에 속한 직원이면 헤더 m, 조직마다 META 읽기·쓰기 한 번, 줄 삭제 둘 — **조직 크기와 무관.** 옛 흐름의
`saveGroup(이 직원을 뺀 목록)` 이 바꾸는 것도 그 직원의 멤버 줄·소속 줄·META `updatedAt` 셋뿐이라 결과가 같다.

## 6. 응답·오류·README

| 상황 | 지금 | 바뀐 뒤 |
|---|---|---|
| 직원·조직 POST 가 있는 아이디 | 409(락 밖) | 409(락 안) |
| 직원 POST 가 있는 `userName` | 409(락 밖, GSI 만) | 409(락 안, GSI + 재확인) |
| 직원 PATCH·PUT 으로 남의 `userName` | **통과** | **409 `uniqueness`** |
| PATCH·PUT·DELETE 대상 없음 | 404(락 밖) | 404(락 안) |
| PATCH 규칙 위반 | 400 | 400 — 대상이 없으면 404 가 먼저(계산은 직원을 읽은 뒤 적용) |
| 락을 못 잡음 | 503 | 503 |

응답 본문은 그대로다(직원 POST 201, PATCH·PUT 200 + 직원, DELETE 204). README: SCIM 절에 "`userName` 은 POST·PUT·PATCH 모두에서
대소문자 무시로 유일, 겹치면 409" 와 §10 의 한계 한 줄. "app-scim 여러 대 띄우기(동시성 제어)" 절에 "쓰기 요청의 판단(읽기·존재 확인·중복
확인)은 전부 전역 락 안에서 일어난다 — 동시 요청이 서로의 변경을 지우지 않는다".

## 7. 검증

테스트 규칙은 기존과 같다 — Lombok, AssertJ, BDD(given/when/then), 한글 `@DisplayName`.

1. **core (가짜 저장소)**
   - **(a) 락 불변식** — 가짜 락이 "지금 쥐고 있나" 를 알려 주고, 가짜 저장소가 읽을 때마다 그 값을 기록한다. SCIM 쓰기 입구 전부
     (`createUser`·`changeUser`·`removeUser`·`createGroup`·`changeGroup`·`removeGroup`)에서 **판단에 쓰는 모든 읽기가 락을 쥔 동안**
     이었는지 본다. 타이밍과 무관하고, 누가 읽기를 락 밖으로 되돌리면 깨진다.
   - 순서 흉내: "이름 바꾸기" 계산을 만든 뒤 다른 요청이 비활성화를 저장 → 실행 결과가 "비활성 + 새 이름".
   - `userName` 중복: 대소문자만 다른 POST 충돌, PATCH 로 남의 이름 충돌, 자기 대소문자만 변경 통과, GSI 에만 남은 옛 후보 무시(가짜 저장소가
     오래된 아이디를 돌려주게 한다).
   - 아이디 중복(직원·조직 POST), 없는 대상은 빈 결과·쓰기 없음·락 반납, 계산이 예외를 던지면 쓰기 없음·락 반납.
   - 직원 삭제: 5,000명 조직 멤버 삭제에 `findGroup` 0회, 줄·튜플이 옛 흐름과 같게 지워짐, OpenFGA 삭제가 실패한 조직은 멤버십과 직원 레코드가 남음.
   - 락 실패 테스트("락을 못 잡으면 아무것도 쓰지 않는다")에 새 입구 셋.
2. **SCIM 핸들러 (connector-scim)** — PATCH·PUT 이름 충돌 409, 없는 직원 PATCH·PUT·DELETE 404, 없는 조직 DELETE 404, 라우터가
   `DirectoryConflictException` 을 409 `uniqueness` 로, 기존 POST 409 테스트 유지.
3. **(b) 경합 시나리오 E2E (app-scim, 실제 DynamoDB 락·OpenFGA)** — 시나리오마다 여러 라운드 반복, 503 은 IdP 처럼 쉬었다 재시도.
   - 같은 직원에 서로 다른 속성 PATCH 5개 동시 → 다섯 변경 모두 남음.
   - 비활성화 PATCH + 이름 변경 PATCH 동시 → 비활성 유지, 권한 없음.
   - DELETE + PATCH 동시 → 직원이 되살아나지 않음.
   - 대소문자만 다른 `userName` POST 둘 동시 → 하나 201, 하나 409.
   - 같은 조직 POST 둘 동시 → 하나 201, 하나 409.
   - 직원 비활성화 + 그 직원을 조직에 넣는 조직 PATCH 동시 → 멤버십은 생기고 권한 튜플은 없음.
4. **(c) 두 인스턴스 (storage-dynamodb, DynamoDB Local)** — 유스케이스 둘(락 객체 둘, 같은 테이블)로 "app-scim 두 대" 를 흉내 내 같은
   직원에 동시 PATCH 를 번갈아 보내고, 모든 변경이 남는지 본다.
5. **규모 (`@ScaleTest`, 10만 명)** — 조직 멤버 PATCH 규모 테스트에 "10만 명 조직 소속 직원 한 명 삭제" 단계를 더한다 — 조직 파티션
   Query 0회, 훑은 아이템 몇 개, Check 튜플 1.

## 8. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간을 구현이 끝나면 여기에 적는다.

## 9. 왜 다른 길을 안 갔나

**B. `USERNAME#` 표지판 줄.** 틈 없이 정확하지만 생성·이름 변경·삭제마다 줄 하나를 더 쓰고, 기존 테이블을 다시 만들어야 하며, 같은 저장소
코드를 쓰는 LDAP 전체 동기화와 규칙을 나눠야 한다. 쓰기가 전역 락으로 한 줄로 서므로 A 의 남는 틈은 GSI 지연(보통 1초 미만)뿐이고,
Entra·Okta 는 `userName`(UPN)을 테넌트 안에서 이미 유일하게 관리한다 — 이 확인은 안전망이다. 틈이 문제가 되면 §4 한 곳을 B 로 바꾼다.

**나. `UserChange` 값 객체.** 직원 PATCH 규칙(이름 여섯 칸·이메일 필터 등)을 core 로 옮겨 SCIM 쪽과 두 벌이 된다. 조직에서 값 객체를 쓴 이유는
"멤버십을 조금만 읽기" 였고, 직원은 레코드가 하나라 그럴 필요가 없다.

**다. 버전 번호 낙관적 잠금.** 전역 락이 이미 같은 일을 하고, 저장 형식과 재시도가 늘어난다.

## 10. 이 설계가 말할 수 없는 것

- **userName 의 GSI 지연 틈.** 방금(GSI 반영 전) 저장된 직원과 대소문자만 다른 `userName` 으로 만들거나 바꾸는 요청은 통과할 수 있다.
  DynamoDB Local 로는 재현할 수 없어 설계로만 다룬다.
- **시간은 추정이다** — 규모 테스트는 요청 수로 증명한다.
- **전역 락은 그대로다.** 쓰기끼리는 한 줄로 선다(동시성 설계의 전제).

## 11. 범위 밖 (백로그)

- 조직 멤버 PATCH 설계 §12 의 나머지 — 큰 변경의 리스 갱신, BatchGet 재시도 상한·백오프, 요청 본문 256KB 한도, POST·DELETE 의 `parentsOf`
  통째 읽기.
- 사용자 제안: 유명 SCIM·LDAP 오픈소스와 코드 비교, 전체 코드·요구사항 리뷰, IdP·IAM 공식 문서 비교 분석.
