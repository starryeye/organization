# 외부 IdP·디렉터리 규격 적합성 감사

**작성일:** 2026-09-09
**방법:** 벤더 공식 문서와 우리 코드의 대조. **실제 연동 테스트는 아직 하지 않았다** —
인증이 없어 IdP 를 붙일 수 없다(마지막 슬라이드로 미뤄둔 것).
**대상:** Microsoft Entra ID, Okta (SCIM 수신) / Active Directory (LDAP 읽기)

---

## 0. 왜 이 감사를 했나

규모 E2E 는 **우리가 만든 요청**으로 우리 구현을 검증한다. 그것은 "우리 설계대로 동작하는가"
를 답하지 "실제 IdP 가 붙는가" 를 답하지 않는다. 두 질문은 다르고, 후자는 벤더가 무엇을
보내는지에 달려 있다.

ETag 검토(동시성 설계 §1.5)에서 벤더 문서를 읽다가 **더 큰 것들이 눈에 띄어** 범위를 넓혔다.

---

## 1. 요약 — 지금 상태로는 붙지 않는다

| | 항목 | 심각도 | 영향 |
|---|---|---|---|
| S-1 | **목록·필터 조회 엔드포인트가 없다** | **치명** | Entra·Okta 둘 다 연결 테스트조차 통과 못 함 |
| S-2 | **인증이 없다** | **치명** | 둘 다 베어러 토큰을 요구. 이미 아는 것(마지막 슬라이드) |
| S-3 | `name.givenName` / `familyName` 을 버린다 | 중 | Okta 가 요구하는 4개 속성 중 둘 |
| S-4 | `/Schemas` 없음 | 조건부 | 갤러리 앱만 해당. 커스텀 앱이면 불필요 |
| L-1 | **AD 범위 검색(range retrieval) 미지원** | **치명** | 1,500명 넘는 그룹이 **멤버 0명**으로 읽힘 |

**S-1 과 L-1 이 이번 감사의 수확이다.** 둘 다 우리 테스트로는 영영 안 잡힌다 — 요청을
우리가 만들고, 픽스처 최대 그룹이 500명이기 때문이다.

---

## 2. SCIM (Entra ID / Okta)

### 2.1 S-1 · 목록·필터 조회 엔드포인트가 없다 — 치명

**우리가 가진 것:**

```
POST   /scim/v2/Users          GET /scim/v2/Users/{id}
PUT    /scim/v2/Users/{id}     PATCH /scim/v2/Users/{id}     DELETE /scim/v2/Users/{id}
POST   /scim/v2/Groups         GET /scim/v2/Groups/{id}
PUT    /scim/v2/Groups/{id}    PATCH /scim/v2/Groups/{id}    DELETE /scim/v2/Groups/{id}
GET    /scim/v2/ServiceProviderConfig
```

**두 IdP 가 보내는 것 (문서에 명시):**

| | 요청 |
|---|---|
| Okta | `GET /Users?filter=userName eq "..."&startIndex=1&count=100` |
| Okta | `GET /Users?startIndex=1&count=100` |
| Okta | `GET /Groups?filter=displayName eq "..."&startIndex=1&count=100` |
| Entra | 필터 조회 필수 — "users are queried with their `userName` and `externalId`, groups are queried with `displayName`" |
| Entra | 목록·페이지네이션 필수 (RFC 7644 §3.4.2.4) |
| Entra | 연결 테스트 시 "random user and group" 을 조회 |

**`GET /Users` 도 `GET /Groups` 도 우리에게는 없다.** 404 가 나간다.

이것이 왜 치명인가: IdP 는 **"이 사용자가 이미 있는가"** 를 필터 조회로 판단한다. 그것 없이는
생성해야 할지 갱신해야 할지 정하지 못하므로, 프로비저닝 자체가 시작되지 않는다. Entra 는
설정 저장 단계의 연결 테스트에서 막히고, Okta 도 마찬가지다.

> **이 프로젝트가 열거 API 를 피해 온 것과 혼동하면 안 된다.** 그 제약은 **OpenFGA** 에
> 대한 것이다(`Read`/`ListObjects` 금지). SCIM 수신 쪽의 목록 조회는 우리 DynamoDB 를
> 읽는 것이고, `DirectorySearchRepository` 로 **이미 접두사 검색을 구현해 두었다** —
> admin 조회가 그것을 쓴다. 필요한 것은 그 위에 SCIM 형식의 라우트를 얹는 일이다.

**다만 그대로는 안 맞는다.** admin 조회는 **접두사** 검색이고 커서 페이징인데, SCIM 은
**완전 일치**(`eq`) 와 `startIndex`/`count` 오프셋 페이징이다. 그 간극을 어떻게 메울지가
설계 거리다.

### 2.2 S-2 · 인증이 없다 — 치명 (이미 아는 것)

Entra: "Accept a single bearer token for authentication and authorization."
Okta: "Authentication (OAuth 2.0, Basic Auth, or HTTP Authorization header)".

처음부터 마지막 슬라이드로 미뤄둔 항목이다. 여기 적는 이유는 **S-1 을 고쳐도 이것 없이는
못 붙는다**는 것을 한 자리에서 보기 위해서다.

### 2.3 S-3 · `name.givenName` / `familyName` 을 버린다 — 중

Okta 의 최소 요구 속성 넷: `userName`, `name.givenName`, `name.familyName`, `emails`.

우리 `ScimMapper` 는 들어온 `name` 에서 `formatted` 만 보고, 응답에서는
`new ScimName(displayName, null, null)` 로 돌려준다. **성과 이름이 유실된다.**

우리 도메인 모델(`DirectoryUser`)에 그 칸이 없는 것이 원인이다. 튜플에는 안 쓰이는 값이라
의도적으로 안 담았는데, IdP 호환에는 필요하다.

### 2.4 S-4 · `/Schemas` 없음 — 조건부

Entra 의 요구사항 표에는 있지만, 본문은 "schema discovery 는 특정 갤러리 애플리케이션에서
쓰이며 **커스텀 비갤러리 SCIM 애플리케이션에서는 현재 지원되지 않는다**" 고 한다.
커스텀 앱으로 붙일 것이면 불필요하다. Okta 는 사용하지 않는 구성요소로 분류한다.

### 2.5 맞게 되어 있는 것들

확인해서 문제없는 항목도 적어 둔다 — 다음에 같은 것을 다시 뒤지지 않게.

| | |
|---|---|
| `id` 가 모든 리소스 응답에 있다 | `ScimMapper.toScimUser`/`toScimGroup` ✓ |
| PATCH `op` 값 대소문자 무시 | Entra 는 `Add`/`Replace`/`Remove` 로 보낸다. `ScimPatchApplier.normalizeOp` 가 소문자화 ✓ |
| `active=false` 소프트 삭제와 복원 | S5/S6 이 검증. 비활성이어도 GET 이 리소스를 돌려준다 ✓ |
| `Content-Type: application/scim+json` | `ScimRouter.SCIM_JSON` ✓ |
| PATCH 응답에 전체 리소스 불필요 | 우리는 담아 보내지만 금지가 아니다 ✓ |
| Okta 가 사용자에 DELETE 를 안 보냄 | 비활성화로 대신. 우리는 둘 다 지원 ✓ |
| 조직 `displayName` 유일성 | Entra 요구. 우리는 조직코드로 식별하고 이름은 속성이라 충돌해도 뭉개지지 않는다 ✓ |

---

## 3. LDAP (Active Directory)

### 3.1 L-1 · 범위 검색(range retrieval) 미지원 — 치명

AD 는 다중값 속성 하나에서 한 번에 돌려주는 값의 개수를 `MaxValRange` 로 제한한다.
그 수를 넘으면 속성 **이름 자체를 바꿔서** 돌려준다:

```
member;range=0-1499        (버전·정책에 따라 0-999, 0-4999)
```

그러면 다음 요청에서 `member;range=1500-*` 로 이어 받아야 한다.

**우리 코드는 `member` 를 정확한 이름으로 읽는다:**

```java
values(attributes, config.getMemberAttribute())   // attributes.get("member")
```

`member;range=0-1499` 는 이 이름과 매치되지 않으므로 **`null` 이 돌아오고, 그 조직은 멤버
0명으로 읽힌다.**

**결과가 조용하고 크다.** 6,000명짜리 그룹이 빈 그룹으로 읽히면 그 6,000개의
`direct_member` 튜플이 삭제 대상이 된다. 삭제 가드(30%)가 잡을 수도 있지만, 전체 대비
비율이 작으면 **그대로 지워진다.** 그리고 어디에도 오류가 남지 않는다 — 서버는 정상 응답을
했고 우리는 정상적으로 읽었다고 믿는다.

> **우리 테스트로는 영영 안 잡힌다.** 픽스처 최대 조직이 500명이고, UnboundID 인메모리
> 서버는 범위 검색을 하지 않는다. 임베디드 서버가 실제 서버보다 관대한 또 하나의 사례다 —
> 앞서 빈 조직 스키마 위반과 페이징 쿠키에서 겪은 것과 같은 종류다.

**DIT 전략은 이 문제가 없다.** dn 경로로 소속을 도출하므로 다중값 속성을 읽지 않는다.
`groupOfNames` 전략만 해당한다.

### 3.2 맞게 되어 있는 것들

| | |
|---|---|
| `MaxPageSize` (AD 기본 1000) | `page-size` 기본 500 으로 그 아래. 그리고 페이징 자체가 구현돼 있다 ✓ |
| 페이징 쿠키가 커넥션에 묶임 | `SingleContextSource` 로 한 커넥션 안에서 돈다 — 실제 OpenLDAP 실측에서 고친 것인데 **AD 도 같은 제약**이라 여기서도 필수다 ✓ |
| 참조(referral)로 인한 부분 결과 | `ignorePartialResultException(true)` — AD 연동의 표준 대응 ✓ |
| objectClass·속성 이름이 AD 와 다름 | `groupOfNames`/`inetOrgPerson` 대신 AD 는 `group`/`user`. 전부 설정으로 빠져 있어 값만 바꾸면 된다 ✓ |
| 서버가 결과를 잘랐을 때 | `ignoreSizeLimitExceededException(false)` 로 예외를 올린다 ✓ |

---

## 4. 권고 순서

1. **L-1 (AD 범위 검색)** — 가장 조용하고 가장 크다. 그리고 SCIM 과 달리 **인증 없이도
   지금 고치고 검증할 수 있다.** UnboundID 로는 재현이 안 되므로 검증 방법부터 정해야 한다.
2. **S-2 (인증)** — S-1 을 고쳐도 이것 없이는 못 붙는다. 그리고 이것이 있어야 실제 IdP 로
   위 판단들을 확인할 수 있다.
3. **S-1 (목록·필터 조회)** — 설계 거리가 있다. `DirectorySearchRepository` 는 접두사 검색과
   커서 페이징인데 SCIM 은 완전 일치와 오프셋 페이징이다.
4. **S-3 (`name` 유실)** — 도메인 모델에 칸을 더하는 일이라 파급을 봐야 한다.

**S-4 는 커스텀 앱으로 붙일 것이면 하지 않는다.**

---

## 5. 이 감사의 한계

**전부 문서 기준이다.** 벤더 문서가 실제 동작을 다 적지는 않고, 버전에 따라 달라진다.
특히 다음은 문서로 확인되지 않아 **모른다고 적어 둔다:**

- 두 IdP 가 `If-Match` 를 정말 한 번도 안 보내는가 (문서에 없다는 것만 확인)
- Okta 의 OIN 앱과 AIW 커스텀 앱이 보내는 요청이 정확히 어떻게 다른가
- AD 의 `MaxValRange` 실제 값 (버전·정책에 따라 1000/1500/5000)

**인증 슬라이드가 끝나면 실제 테넌트로 확인하고 이 문서를 갱신한다.**

## 출처

- [Entra: Develop a SCIM endpoint for user provisioning](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)
- [Okta: SCIM 2.0 implementation details](https://developer.okta.com/docs/api/openapi/okta-scim/guides/scim-20)
- [Okta: SCIM integration concepts and requirements (FAQ)](https://developer.okta.com/docs/concepts/scim/faqs/)
- [MS-ADTS: Range Retrieval of Attribute Values](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-adts/e27b48db-6f82-44cd-9038-2e54f790cc1f)
- [AD: Searching Using Range Retrieval](https://learn.microsoft.com/en-us/previous-versions/windows/desktop/ldap/searching-using-range-retrieval)
