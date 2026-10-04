# 불변 식별자 — SCIM 서버 발급 id, LDAP entryUUID·objectGUID, 반환 속성 명시 (점검 ④-1)

2026-10-04. 점검 권고 슬라이드 ④(식별자와 LDAP 필터)를 둘로 나눈 것 중 **④-1** 이다. ④-2 는 LDAP 읽기(M12·M11·M10·S20~S24)다.
③-2 는 PR #36 으로 머지됐다.

근거: `docs/superpowers/specs/2026-09-28-full-audit.md` §4(M7·M9·P7·S25), §7 권고 ④,
`docs/superpowers/specs/2026-09-28-scim-write-lock-design.md` §11(아이디를 생성 시점 userName 에서 만들어 이름 바꾼 사람의 옛 userName 을 새 입사자가 못 씀 — 새 백로그),
README "인가 모델"·"조회 API — 식별자 셋".

## 1. 문제

튜플의 직원·조직 식별자가 **이름**에서 온다. 다른 앱은 `Check(user:<직원 아이디>, member, group:<조직코드>)` 로 묻는다.

- **M7 — 지운 직원의 아이디를 새 입사자가 다시 받으면 남은 권한을 물려받는다.** SCIM 직원 아이디는 `userName` 을 정규화한 값이다. 상태에 없는 고아 튜플("kim 은 FIN 멤버")이
  남은 채 `DELETE /Users/kim@corp.com` 이 오면 삭제는 상태의 소속만 지운다. 몇 달 뒤 같은 메일의 신규 입사자가 POST 되면 아이디가 같아 FIN 권한을 갖는다.
  OpenFGA Read 를 쓰지 않으므로 삭제 때 그 직원의 튜플을 다 열거할 수 없다 — 아이디 재사용 자체가 증폭기다.
- **백로그(SCIM 쓰기 락 설계 §11)** — 아이디가 생성 시점 `userName` 에서 만들어져, 이름을 바꾼 사람의 옛 `userName` 으로 새 입사자를 만들면 그 아이디가 이미 있어 늘 409 다.
- **M9 — LDAP 아이디가 이름(`uid`/`cn`/`ou`)이다.**
  - 개명이 삭제 + 생성이다. DIT 에서 OU 하나를 개명하면 직속 5,000명의 튜플을 지우고 다시 쓴다 — 삭제가 기준선의 30% 를 넘으면 삭제 가드가 매일 멈추고 그동안 퇴사도 반영되지 않는다.
  - 대소문자만 바꿔도 새 사람이다(`JKim` → `jkim`).
  - DIT 는 이름 충돌이 흔하다(`OU=Sales,OU=Seoul` 과 `OU=Sales,OU=Busan`) — 뒤에 읽힌 쪽은 건너뛰어 그 아래 직원이 소속 0 이다.
  - 불변 식별자는 설정으로도 못 쓴다. `objectGUID` 는 이진 값인데 JNDI 기본 이진 목록에 없어 깨진 문자열로 읽힌다. `entryUUID` 는 운영 속성이라 이름을 대 요청해야 오는데
    우리 검색은 반환 속성을 지정하지 않는다 → "필수 속성 'entryUUID' 가 없습니다"로 회차 실패.
  - Keycloak·authentik·ConnId·Gluu 는 모두 불변 id 를 쓴다 — 우리가 뚜렷한 예외다.
- **P7 — LDAP 검색이 모든 속성을 받는다.** AD 사용자 하나에 `memberOf`·`proxyAddresses`·`userCertificate`·`thumbnailPhoto` 등 10~20KB 가 붙는다 — 10만 명이면 회차마다
  1~2GB(추정). DIT 전략은 원본 엔트리를 회차 끝까지 쥔다(힙 1GB 이하면 OOM, 추정).
- **S25 — LDAP 직원의 `userName` 이 원본이 아니라 정규화된 아이디다**(`uid: hong gd` → `hong_gd`).

## 2. 결정 (사용자 확인, 2026-10-04)

- **튜플의 직원·조직 식별자를 불변 id 로 바꾼다.** 이름 기반 유지 + 재사용 막기(묘비), 설정으로 고르기는 하지 않는다(§10).
  권한을 묻는 앱은 로그인한 사용자를 우리 id 로 바꾸는 조회가 필요하다(§5).
- **④ 를 둘로 나눈다.** ④-1 = 불변 식별자 + 반환 속성 명시(이 문서), ④-2 = LDAP 읽기(M12 검색 필터, M11 아이디 없는 엔트리, M10 AD 기본 그룹, S20~S24).
- **조직도 불변 id 로 한다** — OU 개명이 삭제 + 생성인 것이 조직 쪽 M9 다.

## 3. SCIM 식별자

### 3.1 서버 발급 UUID

- 직원·조직 POST 에서 무작위 UUID(v4, 소문자 하이픈 문자열)를 발급해 응답의 `id` 로 돌려준다 — RFC 7643 §3.1 의 `id`(서비스 제공자가 발급, 바뀌지 않고 다시 쓰지 않는다).
- IdP 는 이 `id` 를 저장해 이후 PATCH·PUT·DELETE 경로에 쓴다(Entra·Okta 의 동작). 요청 본문의 `id` 는 무시한다 — 클라이언트가 정하는 값이 아니다.
- 튜플은 `user:<uuid>`·`group:<uuid>` 다. UUID 에는 `IdNormalizer` 가 바꿀 문자가 없다.
- PUT 은 지금처럼 경로의 `id` 를 정본으로 삼는다. 멤버 값(`members[].value`)은 IdP 가 받은 `id` 다 — 지금처럼 `IdNormalizer` 를 거친다(UUID 는 그대로다).
- 조직코드를 `externalId` 에서 만들던 규칙(`organizationCode`)은 없어진다. `externalId` 는 속성으로만 남는다.

### 3.2 중복 판정(409 `uniqueness`, RFC 7644 §3.3)

- **직원** — `userName` 이 다른 직원과 겹치면 409 다(지금 규칙 그대로, 대소문자 무시, 락 안 GSI + 본 테이블 재확인). 아이디가 `userName` 에서 오지 않으므로 "아이디가 이미 있음"
  갈래는 없어진다.
- **조직** — RFC 핵심 스키마에서 `id` 말고는 유일한 속성이 없다. 그래도 **`externalId` 가 있으면 겹칠 때 409** 다 — 응답을 잃은 POST 를 IdP 가 재시도하면 같은 조직이 둘 생겨
  멤버가 갈린다. 지금도 조직코드(= `externalId`)로 같은 일을 막고 있다. 판정은 락 안에서 GSI3(`externalId`) + 본 테이블 재확인으로 한다(직원 `userName` 과 같은 방식).
  `externalId` 가 없는 조직은 중복 판정이 없다. `externalId` 를 **바꾸는** PUT 도 같은 확인을 한다(직원 `userName` 변경과 같다). 조직 PATCH 는 `externalId` 를 바꿀 수 없다 — 경로로 지정하면 400 `invalidPath`, 경로 없는 PATCH 는 `externalId` 를 조용히 무시한다(기존 동작).
- **구현 중 정한 것** — 조직 `externalId` 는 대소문자를 가린다(RFC 7643 `caseExact: true`). 직원의 `externalId` 는 중복을 판정하지 않는다(재시도 중복은 `userName`
  이 막는다). 같은 `externalId` 를 가진 직원이 둘이면 `?externalId=` 가 여러 줄을 돌려준다(§5).

### 3.3 풀리는 것

- M7: 새 입사자는 늘 새 UUID 를 받는다 — 지운 직원의 고아 튜플(`user:<옛 uuid>`)을 물려받지 않는다. 고아 튜플 자체는 남는다(§11).
- 백로그: 이름을 바꾼 사람의 옛 `userName` 을 새 입사자가 쓸 수 있다 — 중복 판정은 지금 쓰는 `userName` 끼리만 본다.

## 4. LDAP 식별자와 반환 속성

### 4.1 식별 속성의 기본값

- 두 전략의 식별 속성 기본값을 `entryUUID` 로 바꾼다 — groupOfNames 의 `user-id-attribute`·`group-id-attribute`, DIT 의 `user-id-attribute`·`group-id-attribute`(OU).
  `entryUUID`(RFC 4530)는 OpenLDAP·389DS·UnboundID 등이 자동으로 유지하는 운영 속성이다.
- AD 는 `objectGUID` 로 설정하라고 README 에 적는다. **식별 속성 이름이 `objectGUID`(대소문자 무시)면** JNDI 환경에 이진 속성으로 선언하고(`java.naming.ldap.attributes.binary`),
  16바이트를 표준 GUID 문자열로 바꾼다 — 소문자, 앞 세 묶음은 바이트 순서를 뒤집는다(리틀 엔디언). AD 도구(`Get-ADUser`)가 보여 주는 값과 같고 Keycloak 과 같은 방식이다.
  16바이트가 아니면 데이터 오류(`DirectoryDataException`)다.
- 이름 기반(`uid`/`cn`/`ou`/`employeeNumber`)도 설정으로 여전히 쓸 수 있다 — 그때는 개명이 삭제 + 생성이라는 것을 README 에 적는다.
- 개명(`cn`/`ou`/`uid` 변경)은 같은 id 의 이름 변경이 된다 — 삭제 가드에 걸리지 않고, 대소문자 개명이 새 사람이 되지 않고, DIT 의 같은 이름 OU 가 충돌하지 않는다.
- 멤버 대조는 지금처럼 서버가 준 절대 DN 으로 한다(`LdapDns`). 바뀌지 않는다.

### 4.2 사람이 읽는 값

- **직원 `userName`** — 새 설정 `user-login-attribute` 의 **원본 값**(정규화하지 않음, 점검 S25). 기본 `uid`, AD 는 `sAMAccountName` 을 권한다. 값이 없으면 식별 값으로 대신한다
  (지금 표시명 대체와 같은 규칙).
- **조직 표시명** — 이름 속성(`group-name-attribute`, 기본 `description`)이 없으면 지금은 조직코드로 대신했다. 이제 **DN 의 첫 RDN 값**(`cn`/`ou`)으로 대신한다.
- **`externalId`** — 두 전략 모두 서버가 준 **절대 DN** 이다(DIT 는 지금까지 베이스를 뺀 상대 DN 이었다 — 아래 §4.4).

### 4.3 반환 속성 명시 (P7)

- 모든 검색이 필요한 속성만 이름을 대 요청한다 — 그래야 운영 속성 `entryUUID` 도 온다.
  - 직원: 식별·로그인·표시명·메일 속성, `cn`, 계정 상태(`userAccountControl`·`accountExpires`), 이름(`sn`·`givenName`·`middleName`·`generationQualifier`).
  - 그룹(groupOfNames): 식별·이름 속성, 멤버 속성. AD 범위 읽기(`member;range=…`)는 지금과 같다.
  - OU(DIT): 식별·이름 속성.
- DIT 전략도 매퍼에서 바로 필요한 값만 뽑아 작은 레코드로 들고 간다 — 원본 엔트리(`DirContextAdapter`)를 회차 끝까지 쥐지 않는다.
- 회차당 전송량·힙이 AD 10만 명에서 1~2GB → 수백 MB 이하로 준다고 본다(추정, §11).

### 4.4 구현 중 정한 것

위 §4.1~§4.3 에 적지 않았고 구현하며 정했다.

- **직원 표시명의 대체 순서는 표시명 속성 → `cn` → `userName` 이다.** 마지막이 id 가 아니라 원본 `userName`(§4.2) 이다 — 식별 값이 UUID 라 표시명으로 보이면 안 되고,
  정규화된 id 도 원본 이름이 아니다.
- **조직(groupOfNames 의 그룹, DIT 의 OU) 표시명의 대체는 이름 속성 → 절대 DN 의 첫 RDN 값이다**(점검 M9). `root-dn` 이 `""` 인 DIT 의 루트 OU 도 같은 규칙으로
  DN 에서 뽑는다.
- **DIT 의 `externalId` 도 절대 DN 이다.** 베이스를 뺀 상대 DN 이던 것을 groupOfNames 와 맞췄다 — 관리 API 의 `?externalId=` 조회(§5)가 전략과 무관하게 전체 DN 으로 찾게 하려는 것이다.
  상대 DN 은 안쪽 키(대조키·부모 조회)와 로그에만 남는다. `docs/superpowers/plans/2026-08-15-follow-ups.md` 의 "externalId 형식이 두 전략 간 불일치" 항목이 이걸로 풀린다.
- **데이터 오류는 조용히 건너뛰지 않고 읽기를 실패시킨다**(`DirectoryDataException`, 재시도하지 않는다). 둘이다.
  - DIT 에서 같은 id 를 가진 직원 중 `userAccountControl`·`accountExpires` 가 정수가 아닌 것 — 지금까지는 말없이 건너뛰었다. groupOfNames 와 같아졌다.
  - groupOfNames 에서 비어 있는 id.

## 5. 권한을 묻는 앱이 id 를 찾는 길

- 직원: 이미 있는 `GET /admin/employees?userName=`(접두사 검색).
- **새로: 관리 API 에 `?externalId=`(정확히 일치)** — `GET /admin/employees?externalId=…`·`GET /admin/organizations?externalId=…`. 이미 있는 GSI3 와
  `DirectoryQueryRepository#findUsersByExternalId`·`findGroupHeadersByExternalId` 를 쓴다 — 새 인덱스는 없다. SCIM 이면 IdP 가 보낸 `externalId`(예: Okta 사용자 id, Entra 에서
  objectId 를 매핑했으면 그 값)로 찾는다 — 앱이 로그인 토큰에서 받는 `sub`·`oid` 와 맞출 수 있다. LDAP 이면 `externalId` 는 DN 이다.
  - **구현 중 정한 것** — 정확히 일치이고 한 페이지(`nextCursor` null)로 끝난다. GSI3 후보를 본 테이블로 다시 확인한다. 직원 조회는 `userName`·`displayName`·`externalId` 중 정확히 하나,
    조직 조회는 `displayName`·`externalId` 중 정확히 하나를 받고 아니면 400 이다. `externalId` 가 겹치는 리소스가 둘이면 여러 줄이 온다.
- app-scim 은 `GET /scim/v2/Users?filter=userName eq "…"`·`filter=externalId eq "…"` 로도 찾는다(이미 있다).
- README "식별자 셋"을 새 뜻으로 다시 쓴다 — `employeeId` 는 불변 id, `userName` 은 원본 계정명, `displayName` 은 사람이 읽는 이름. 조직도 같다(`orgCode` 는 불변 id).

## 6. 옮기기 (운영 배포 전 — 이관 코드는 만들지 않는다)

- **app-scim** — 기존 데이터의 아이디는 `userName` 에서 온 값이라 쓸 수 없다. `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` 뒤 IdP 에서 프로비저닝을 처음부터 다시 한다(테이블·store 를
  새로 만들어도 된다).
- **app-ldap** — 첫 동기화에서 모든 id 가 바뀌어 옛 튜플 전부가 지울 대상이 되고 삭제 가드가 멈춘다. 한 번 `POST /admin/sync/full?force=true` 로 넘긴다(또는 테이블·store 를 새로).
- README 에 절차를 적는다.

## 7. 바뀌는 곳

| 모듈 | 바뀌는 것 |
|---|---|
| connector-scim | POST 가 UUID 발급(`ScimMapper`·`ScimUserHandler`·`ScimGroupHandler`), `organizationCode` 없앰, 본문 `id` 무시 |
| core | `createUser` 의 아이디 중복 갈래 정리, `createGroup` 의 `externalId` 중복 판정(락 안 — 상태 포트에 `findGroupIdsByExternalId`(GSI3)를 더하고 후보를 `findGroupHeader` 로 다시 확인, `userName` 확인과 같은 방식), `AdminQueryUseCase` 의 `externalId` 조회 |
| connector-ldap | 식별 속성 기본값 `entryUUID`, `objectGUID` 이진 선언·GUID 문자열, `user-login-attribute`, 표시명 대체를 RDN 으로, 반환 속성 명시, DIT 매퍼가 값만 들고 감, DIT `externalId` 절대 DN(§4.4) |
| admin-api | `?externalId=` 조회 |
| testFixtures | SCIM 요청 하네스가 POST 응답의 `id` 를 받아 뒤 요청·기대값에 바꿔 넣는 번역 단계(`ScimIdBook`), `OrgChart.아이디를_바꾼다` 는 바뀐 id → 처음 id 표를 남겨 `RollupSampling` 이 처음 id 로 표본을 골라 실행마다 같게 한다(서버 id 는 무작위라 그 순서로 고르면 매번 달라진다) |
| app-scim 테스트 | e2e·규모 테스트를 `ScimIdBook` 으로 새 id 에 맞춘다. "늦게 도착함" 시나리오는 Entra 순서(빈 조직 → 직원 → 멤버 PATCH)로 다시 썼다(§8) |
| app-ldap 테스트 | 기존 e2e·규모 테스트는 test 프로필이 이름 기반 id(groupOfNames `uid`/`cn`, DIT `uid`/`ou`)를 명시해 그대로 돈다. 기본값 `entryUUID` 는 새 테스트가 본다(§8) |
| README | 식별자 셋, LDAP 식별 속성(AD 는 `objectGUID`, `user-login-attribute`), 옮기기 절차 |
| 점검 문서 | M7·M9·P7·S25 해결 표시 |

## 8. 검증

테스트 규칙은 기존과 같다(Lombok, AssertJ, BDD, 한글 `@DisplayName`). 각 테스트는 먼저 지금 코드로 실패하는 것을 본 뒤 고친다.

| 대상 | 확인할 것 | 모듈 |
|---|---|---|
| SCIM id | 직원·조직 POST 가 UUID 를 돌려주고 그 id 로 GET·PATCH·PUT·DELETE 가 된다. 본문의 `id` 는 무시한다 | connector-scim |
| 직원 중복 | 같은 `userName`(대소문자만 다름 포함) 두 번째 POST 는 409 | connector-scim·core |
| 조직 중복 | 같은 `externalId` 두 번째 POST 는 409, `externalId` 가 없으면 중복 판정 없음, 판정 읽기는 락 안 | core·connector-scim |
| M7 | 지운 직원과 같은 `userName` 으로 다시 만들면 새 id 이고 옛 id 의 고아 튜플을 물려받지 않는다 | core |
| 옛 userName | 이름을 바꾼 사람의 옛 `userName` 으로 새 입사자를 만들 수 있다 | core |
| LDAP 기본 id | 임베디드 서버에서 직원·조직 id 가 `entryUUID` 다(두 전략) | connector-ldap |
| objectGUID | 16바이트 → 표준 GUID 문자열(바이트 순서 포함), 16바이트가 아니면 데이터 오류, 이진 선언이 JNDI 환경에 들어간다 | connector-ldap |
| 개명 | `ou`·`cn`·`uid` 를 바꿔도 같은 id 이고 삭제 가드에 걸리지 않는다 | connector-ldap·app-ldap |
| 반환 속성 | 검색이 명시한 속성만 요청한다(그 밖의 속성은 오지 않는다), `entryUUID` 가 온다 | connector-ldap |
| userName | LDAP 직원 `userName` 이 `user-login-attribute` 의 원본 값이다(S25) | connector-ldap |
| 관리 API | `?externalId=` 로 직원·조직을 정확히 찾는다 | admin-api·core |
| 규모 | 기존 SCIM 규모 테스트(5천·10만)가 서버 발급 id 로, LDAP 은 기존 규모 테스트가 이름 기반 id 로 그대로, 기본값 `entryUUID` 는 `LdapEntryUuidScaleTest` 가 통과한다 | app-scim·app-ldap scaleTest |

**구현 후 보정.** 위 표는 계획 시점의 것이고, 구현에서 다음과 같이 정리됐다.

- **LDAP 기존 e2e·규모 테스트는 test 프로필이 이름 기반 id 를 명시해 그대로 돈다**(groupOfNames `uid`/`cn`, DIT `uid`/`ou`). 기본값 `entryUUID` 는 새 테스트가 본다 —
  connector-ldap 의 `ImmutableIdentifierTest`(전략 테스트), app-ldap 의 `LdapImmutableIdEndToEndTest`(groupOfNames)·`LdapDitImmutableIdEndToEndTest`(DIT),
  `LdapEntryUuidScaleTest`(groupOfNames 6,124명·352그룹 — 기대 조직도를 `entryUUID` 맵으로 번역해 이름 기반 규모 테스트와 같은 두 경로 검증을 돈다).
- **개명은 두 갈래를 e2e 로 확인했다.** `entryUUID` 에서는 ModifyDN 이 같은 id 라 큰 개명도 삭제 가드에 걸리지 않고, 이름 기반 id 에서는 삭제+생성이라 가드가 멈춘다.
- **SCIM 하네스는 `ScimIdBook` 이 번역한다.** 요청을 보낼 때는 조직도 id 를 서버 id 로, 검증할 때는 기대값과 Check 를 서버 id 로 바꾼다. 드리프트 복구·재적재 등의 e2e 는 POST 응답의 id 를 쓴다.
- **"늦게 도착함" 시나리오는 SCIM 에서 일어날 수 없다** — IdP 는 받은 `id` 로만 참조한다. 그래서 Entra 순서(빈 조직 → 직원 → 멤버 PATCH)로 다시 썼다:
  `ScimProvisioningOrderScaleTest` S1-a·S1-b, `ScimGroupMemberPatchScaleTest` 순서 8(10만 명 조직 밑에 새 조직을 PATCH 로 하위로 붙여도 파티션을 훑지 않는다), `ScimEndToEndTest` 순서 9.

머지 전 컨트롤러가 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 한 번에 하나씩 돌리고, 결과를 §9 에 적는다.

## 9. 결과 (구현 후 기록)

2026-10-04, 브랜치 `audit-identifiers` f837131(그 뒤 커밋은 주석·스펙만), DynamoDB Local·OpenFGA 컨테이너, 개발 노트북.

- **`./gradlew cleanTest test`** — 1,154개 통과, 3분 59초(③-2 1,051개에서 +103).
- **`./gradlew cleanScaleTest scaleTest`** — 79개 통과, 13분 39초(새 규모 테스트 `LdapEntryUuidScaleTest` +1).
- **규모 실측**(같은 실행, 시간은 참고):

  | 항목 | 값 |
  |---|---|
  | LDAP 전체 동기화 6,124명·352조직 — `entryUUID`(`LdapEntryUuidScaleTest`) | 동기화 7.7초, 검증 3.4초 |
  | 같은 조직도 — 이름 기반(`LdapScaleSyncCostTest`) | 동기화 9.0초, 검증 2.6초 |
  | 10만 명 조직에 새 조직을 PATCH 로 하위 조직으로 붙이기(Order 8, 다시 쓴 시나리오) | 41ms, Query 2, 훑은 아이템 0, GetItem 2 |
  | 맨 위 조직을 마지막에 POST(Order 10) | 20ms, Query 7(③-2 의 6 에서 +1 — 생성의 `externalId` GSI3 확인) |
  | `type` 없는 멤버 1,000명 추가(P1, 비교용) | 767ms, GetItem 1, BatchGet 키 4,000(③-2 와 같다) |
  | SCIM 조직 먼저 순서(S1-b) — 직원 6,124명 POST + 멤버 PATCH 350건(검증 제외) | 50.4초 |

  `entryUUID` 는 이름 기반과 동기화 비용이 같다(속성 하나를 이름을 대 더 요청할 뿐이다). 앞선 실행에서도 8.2초 대 9.7초였다.
- **리뷰에서 고친 것** — 과제 리뷰:
  - 생성의 중복 확인은 자기 id 를 빼지 않는다(같은 id 로 다시 온 POST 가 덮어쓰지 않게).
  - S1 의 "아직 튜플 없음" 확인이 번역 안 된 직원 id 를 물어 공허했다 → 직원을 만든 뒤 서버 id 로 묻는다.
  - 롤업 표본을 원래 아이디 순으로 고른다(실행마다 같은 표본).
  - 표시명이 UUID 로 새지 않음을 고정하는 테스트를 더했다.
  - DIT 루트 OU(`root-dn: ""`)의 표시명이 null 이 되던 퇴행을 고쳤다(절대 DN 의 첫 RDN).
  - DIT `externalId` 를 절대 DN 으로 맞췄다.

  최종 리뷰:
  - 문서·주석을 코드대로 고쳤다: 조직 `externalId` 변경은 PUT 만, POST 재시도, 표시명 대체 순서.
  - groupOfNames 경고 로그에 DN 을 더했다.
  - 기대값 정렬을 원래 아이디로 바꿨다.
  - 경합 409 의 `scimType: uniqueness` 를 단정한다.
  - 대소문자 테스트가 유스케이스의 거르기를 본다.

## 10. 왜 다른 길을 안 갔나

- **이름 기반 유지 + 지운 아이디 묘비(409).** 재사용 상속(M7)은 막지만 이름을 바꾼 사람의 옛 이름이 계속 막히고, LDAP 개명이 삭제 + 생성인 문제(M9)는 그대로다. 묘비 정리라는
  운영 부담이 생긴다.
- **설정으로 이름/불변 id 고르기.** 두 경로를 모두 유지·테스트해야 하고, 기본값이 이름이면 M7·M9 가 기본 배포에 남는다. LDAP 식별 속성은 원래 설정이라 이름 기반도 쓸 수는 있다.
- **SCIM — `externalId` 에서 결정적 UUID(v5).** 재시도가 멱등해지지만 `externalId` 는 선택 속성이고 IdP 가 재사용할 수 있어 M7 이 돌아온다.
- **SCIM — IdP `externalId` 를 그대로 `id` 로.** IdP 마다 값의 뜻이 다르다(Entra 기본은 `mailNickname` — 이름 기반).
- **`objectGUID` 를 16진·Base64 로.** 단순하지만 운영자가 AD 에서 보는 값과 달라 대조가 어렵다.
- **조직은 코드 유지(직원만 불변 id).** OU 개명이 삭제 + 생성인 문제가 남는다. 사용자가 조직도 불변 id 로 정했다.

## 11. 이 설계가 말할 수 없는 것

- **권한을 묻는 앱은 조회가 필요하다.** 로그인한 사용자를 우리 id 로 바꾸려면 관리 API(`userName`·`externalId`) 또는 SCIM 목록 필터를 불러야 한다. 토큰 클레임과의 자동 연결은
  하지 않는다.
- **고아 튜플은 그대로 남는다.** 새 입사자가 물려받지 않을 뿐, 지운 직원의 옛 id 로 남은 튜플은 SCIM 재적재(`mode=tuples`)가 지운다.
  - 5xx 뒤에 재시도된 조직 POST 는 새 UUID 를 만든다(PUT·PATCH 와 달리 같은 최종 상태를 목표로 하지 않는다). 실패한 새 조직은 저장하지 않으므로 `externalId` 가
    같아도 409 가 아니다 — 409 는 응답만 잃은 성공한 POST 의 재시도다. 첫 번째 id 로 이미 써 둔 튜플은 고아가 되어 `mode=tuples` 재적재만 지운다. 첫 번째 id 는 어떤
    상태·응답에도 없으므로 권한이 새는 것은 아니다. Entra 가 조직을 빈 채로 만들어 드문 경우다. 직원 POST 는 쓸 튜플이 없어(새 UUID 를 가리키는 조직이 없다) 해당하지 않는다.
- **`objectGUID` 와 `member;range=` 는 실제 AD 로 확인하지 못했다** — 바이트 변환은 단위 테스트, 이진 선언은 JNDI 환경 값으로만 본다. 검색이 `member` 를 이름을 대 요청할 때
  AD 가 `member;range=…` 로 나눠 주는 동작도 임베디드 서버로는 볼 수 없다.
- **전송량·힙 감소는 추정이다** — 임베디드 서버는 속성이 몇 개뿐이라 AD 크기를 재지 못한다. 요청 속성 목록만 단정한다.
- **튜플이 UUID 라 OpenFGA 를 직접 보는 운영자는 읽기 어렵다** — 관리 API 로 본다.
- **옮길 때 한 번의 조치가 필요하다** — SCIM 은 재프로비저닝, LDAP 은 force 동기화(또는 테이블·store 새로).
- **조직 `externalId` 중복 판정은 RFC 밖의 우리 규칙이다** — `externalId` 를 일부러 겹치게 보내는 IdP 설정이면 409 다.
- **`userName`·조직 `externalId` 중복 판정은 GSI 후보를 본 테이블로 다시 확인한다** — 막 저장돼 GSI 에 아직 없는 리소스와는 겹칠 수 있다(직원 `userName` 의 기존 틈과 같다).
- **SCIM 에서 "아직 없는 리소스를 먼저 참조"하는 경로는 이제 생기지 않는다** — IdP 는 받은 `id` 로만 가리킨다. 상위 조직이 새 조직을 먼저 적어 두는 처리(`상위_조직들`)는 SCIM 에서 비게 된다(코드는 남는다).
- **`entryUUID` 규모 테스트는 groupOfNames 만 본다** — DIT 의 `entryUUID` 는 e2e(`LdapDitImmutableIdEndToEndTest`)까지만 있다.
- **기존 app-ldap e2e(삭제 가드, 멈춘 뒤 OpenFGA Read 복구, 보관, 어긋남)는 이름 기반 id 로 돈다** — `entryUUID` 로 끝에서 끝까지 확인한 것은 동기화와 개명뿐이다. 나머지 경로는
  id 의 모양을 보지 않는다(id 중립).
- **관리 API 의 `?externalId=` 조회도 같은 GSI3 지연이 있다** — 방금 만든 리소스는 잠깐(보통 1초 미만) 안 나올 수 있다. 중복 판정과 같은 인덱스를 쓴다.

## 12. 범위 밖

- ④-2(LDAP 읽기): M12 사용자·그룹 검색 필터, M11 아이디 없는 엔트리 건너뛰기, M10 AD 기본 그룹(`primaryGroupID`), S20 쓰기 순서, S21 referral 경고, S22 `CN=Users`,
  S23 결정적 실패 재시도·헬스 바인드, S24 ppolicy 잠금.
- 권고 ⑤(SCIM 오류 신호)·⑥(나머지 성능), 기존 백로그, 인증(마지막).
