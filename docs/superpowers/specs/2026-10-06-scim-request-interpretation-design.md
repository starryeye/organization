# 점검 ⑤-2 SCIM 요청 해석 — 저장하지 않는 RFC 속성은 받아서 버림, 조직 PATCH externalId, 속성 이름 대소문자, active 값, Location, 조직 URN

- 날짜: 2026-10-06
- 근거: `docs/superpowers/specs/2026-09-28-full-audit.md` §7 권고 ⑤ 를 나눈 둘째 — 중 M5·M18, 사소 S1·S5(앞부분)·S6·S8·S9·S12. ④-1(`2026-10-04-immutable-identifiers-design.md` §3.2)이 남긴
  "경로 없는 조직 PATCH 는 `externalId` 를 조용히 무시한다". ⑤-1(`2026-10-05-scim-error-signals-design.md` §11)이 이 목록을 넘겼다.
- 전제:
  - 실제 규모는 10만 명 이상이다.
  - 운영 배포 전이다.
  - 표준이 정한 신호만 받는다. 여기서 표준은 RFC 7643·7644 와 HTTP 다.
  - 배포 하나는 LDAP 아니면 SCIM 이다. app-scim 의 아이디는 모두 서버가 발급한 UUID 다(④-1).

## 1. 문제

| 항목 | 지금 | 결과 |
|---|---|---|
| M5 | 저장하지 않는 속성을 **path 형**으로 보내면 400 `invalidPath` 다. 예: `phoneNumbers[type eq "work"].value`, `addresses[…]`, `title`, enterprise `department` | PATCH 는 원자적이다(RFC 7644 §3.5.2). 그래서 같은 요청의 `active=false` 도 반영되지 않고 **퇴사자 권한이 남는다**. Entra 기본 매핑에 이 속성들이 있고, 옵션(`aadOptscim062020`) 없이는 모든 속성이 path 형으로 온다. 같은 속성이 **경로 없는 값 객체**로 오면 무시하고 200 이다(직원 PATCH 는 리소스를 돌려준다) — 모양에 따라 결과가 갈린다 |
| M18 | enterprise `manager` path PATCH 가 400 이다 | Entra 는 이것을 참조 실패로 센다. 10만 명 초기 동기화에서 관리자가 있는 직원이 6만 명을 넘으면 격리 상한에 닿는다(추정) |
| ④-1 이월 | 조직 PATCH 의 `externalId` — path 형은 400 `invalidPath`, 경로 없는 값의 키는 **조용히 무시** | IdP 는 204 를 받고 반영됐다고 믿는다. `externalId` 는 우리가 저장하는 속성이고 RFC 에서 readWrite 다 |
| S9 | 조직 PATCH 가 코어 Group URN 접두(`urn:ietf:params:scim:schemas:core:2.0:Group:displayName`)를 모른다 | path 형은 400 이다. 값 객체로 오면 204 인데 이름이 안 바뀐다. 직원 쪽은 이미 코어 User URN 을 뗀다 |
| S6 | JSON 본문의 속성 이름을 대소문자를 가려 읽고, 다르면 조용히 버린다(RFC 7643 §2.1 위반) | 조직 PUT 의 `"Members"` → 멤버 전원 삭제. 직원 PUT 의 `"Active":false` → 활성. `.search` 의 `"Filter"` → 필터 없는 첫 페이지. 카탈로그 IdP 는 camelCase 라 가능성은 낮다(추정) |
| S8 | PATCH `active` 의 문자열은 `"true"` 만 참이다(`Boolean.parseBoolean`) | `"yes"`·`"1"`·`" true"` 가 **조용히 비활성화**한다 |
| S1 | POST 201 에 `Location` 헤더가 없다(RFC 7644 §3.3 SHALL) | IdP 는 본문의 `id` 를 읽어 영향은 작다(추정). 점검이 함께 짚은 "`meta.location` 이 아이디를 인코딩하지 않는다" 는 ④-1 이후 사라졌다 — 아이디가 UUID 라 `?`·`%`·`/` 가 없다 |
| S5 앞부분 | `externalId` 없는 조직 POST(Okta 식)는 응답을 잃은 재시도면 같은 이름의 조직이 둘 생긴다 | 표준에 막을 신호가 없다(§6) |
| S12 | 우리가 거절하는 조회 모양이 400 이다 — `manager` 필터, Entra 이메일 매칭 `emails[type eq "work"]`, Ping `co`, JumpCloud 이메일 재연결 | "표준 신호만 받는다" 원칙에 따른 알려진 제한이다. README 에 목록이 없다 |

## 2. 결정 (사용자 확인, 2026-10-06)

- **M5·M18 — RFC 가 정의한 속성이면 path 형도 받아서 버린다.**
  - 기준은 RFC 7643 코어 User 스키마(§4.1)와 enterprise 확장(§4.3)에 이름이 있는지다(§3.2 표).
  - RFC 에 없는 이름은 지금처럼 400 이다. 오타나 모르는 확장이 여기에 든다.
  - 저장하는 속성은 엄격하게 검사하고, 표준이 정의했지만 다루지 않는 속성은 받아 주고, 모르는 것은 거절한다.
  - 권한 시스템에서 가장 비싼 사고는 퇴사자 권한이 남는 것이다. 매핑 실수를 400 으로 알리는 신호보다 이쪽을 지킨다.
  - 잃는 신호는 메트릭으로 대신 보인다(§3.3). 사용자가 함께 정한 네 가지가 있다:
    - 메트릭 라벨은 고정 목록에서만 뽑는다.
    - 로그에 값을 남기지 않는다.
    - 실제 IdP 요청을 회귀 테스트로 둔다.
    - README 에 저장하는 속성 목록을 적는다.
- **조직 PATCH 의 `externalId` — PATCH 로도 바꿀 수 있다.** PUT 과 같은 중복 판정을 거치고, 겹치면 409 다(§4.1). 두 모양이 같은 규칙이다.
- **S5 앞부분 — 코드로 막지 않고 한계로 문서화한다.** RFC 7644 의 POST 는 멱등이 아니다. 조직 `displayName` 은 uniqueness: none 이다(RFC 7643 §8.7.1 Group 스키마). 표준에 신호가 없는 곳에 규칙을 지어내지 않는다.
- **S6·S8·S9·S1·S12** 는 §4·§5·§6 설계로 확인받았다.

## 3. 저장하지 않는 속성 (M5·M18)

### 3.1 판정 규칙 — path 형

1. path 에서 접두를 대소문자 없이 뗀다. 대상은 코어 User URN(`urn:ietf:params:scim:schemas:core:2.0:User:`)과 enterprise URN(`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:`)이다.
2. 남은 것을 `속성[필터].하위속성` 으로 읽는다. 필터와 하위 속성은 없을 수 있다.
3. 우리가 저장하는 속성이면 지금처럼 적용한다. 적용할 수 없는 모양이면 지금처럼 오류다.
4. 적용하지 못했을 때:
   - **무시하고 성공**: 속성이 §3.2 표에 있고, 하위 속성이 없거나 그 속성의 RFC 하위 속성일 때. 무시한 속성 이름을 기록한다(§3.3).
     예: `phoneNumbers[type eq "work"].value`, `addresses[type eq "work"].streetAddress`, `emails[type eq "other"]`, `…:enterprise:2.0:User:manager`, `…:enterprise:2.0:User:department`, `title`, `groups`, `password`.
   - **그 밖은 지금처럼 400 `invalidPath`**. 예: `name.givenNmae`(오타), `phoneNumber`, 커스텀 확장 `urn:…:extension:<이름>:2.0:User:…`.
5. 공통 속성 `id`·`meta` 는 표에 넣지 않는다. path 로 오면 지금처럼 400 이다. 이렇게 보내는 IdP 는 없다.

`emails` 는 우리가 하나(`type: "work"`)만 저장하는 복수 속성이다. `emails[type eq "work"]` 와 그 `.value` 는 지금처럼 적용한다. 다른 `type` 이나 `.display` 같은 하위 속성은 4번 규칙으로 무시한다.

- **구현 중 정한 것 — URN 없는 enterprise 이름은 400 이다.** 맨 `department` 가 여기에 든다. RFC 7644 §3.10 은 확장 속성을 URN 으로 완전히 쓰라 하고, Entra 매핑 대상도 URN 이름이다. URN 없이 보내는 IdP 근거는 없다.
- **구현 중 정한 것 — 필터 없는 `emails.value` 는 받아서 버린다.** 전에는 400 이었다. 우리가 적용하지 못하는 `emails` 모양이라 4번 규칙을 그대로 따른다. 저장된 work 이메일을 고를 수 있는 필터(`emails[primary eq true].value`, `emails[value eq "…"].value`)도 같다. 한계는 §11 에 적는다.

### 3.2 RFC 표

connector-scim `ScimRfcAttributes` 한 곳에 둔다. 이름은 대소문자 없이 비교한다.

| 묶음 | 속성 | 하위 속성 |
|---|---|---|
| 코어 단일(§4.1) | `userName`, `displayName`, `nickName`, `profileUrl`, `title`, `userType`, `preferredLanguage`, `locale`, `timezone`, `active`, `password` | — |
| 코어 `name`(§4.1) | `name` | `formatted`, `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix` |
| 코어 복수(§4.1, §2.4) | `emails`, `phoneNumbers`, `ims`, `photos`, `groups`, `entitlements`, `roles`, `x509Certificates` | `value`, `display`, `type`, `primary`, `$ref` |
| 코어 `addresses`(§4.1) | `addresses` | `formatted`, `streetAddress`, `locality`, `region`, `postalCode`, `country`, `type`, `primary` |
| enterprise 단일(§4.3) | `employeeNumber`, `costCenter`, `organization`, `division`, `department` | — |
| enterprise `manager`(§4.3) | `manager` | `value`, `$ref`, `displayName` |

RFC 7643 은 2015 년 이후 바뀌지 않았다. 표를 손볼 일은 RFC 가 바뀔 때뿐이다.

### 3.3 보이게 하기

- `ScimPatchApplier.applyToUser` 가 받아서 버린 속성 이름을 `Consumer<String>` 으로 넘긴다. 이름은 §3.2 표의 정규 이름이나 `other` 다.
- 직원 PATCH 핸들러는 무시한 것이 있으면 다음 둘을 한다:
  - **DEBUG 한 줄**을 남긴다. 아이디와 무시한 이름 목록만 담고 **값은 남기지 않는다**(전화번호·주소는 개인정보다).
  - connector-scim 의 관찰자 인터페이스 `IgnoredAttributeObserver` 로 알린다.
- app-scim `ScimSyncMetrics` 가 관찰자를 구현해 카운터 `scim.patch.ignored`(태그 `attribute`)를 올린다.
  - 태그 값은 §3.2 표의 정규 이름이다. 표에 없는 키(§3.4 의 경로 없는 값)는 `other` 로 묶는다. **요청 문자열을 태그에 그대로 넣지 않는다** — 태그 수가 늘 유한하다.
  - 관찰자가 없으면 아무 일도 하지 않는다. 테스트와 다른 조립이 여기에 든다.
- WARN 으로 남기지 않는다. Entra 가 갱신마다 같은 연산을 다시 실을 것이므로(추정) 경고가 정상 흐름이 된다. 앞서 `type` 없는 멤버의 경고를 요약 한 줄로 줄인 것(P1)과 같은 이유다.
- **구현 중 정한 것 — 메트릭은 요청 하나에서 이름마다 한 번 센다.** 핸들러가 이름을 집합으로 모으기 때문이다.
  - 변경이 성공한 뒤에만 센다. 뒤 연산이 400 이면 세지 않는다(테스트로 고정).
  - 부분 실패 503 은 세고, 그 재시도도 다시 센다. 관측 카운터의 가장자리다.
- **구현 중 정한 것 — 경로 없는 값에서 enterprise 확장 객체 키 `urn:…:enterprise:2.0:User`(끝 콜론 없음)는 태그 `other` 다.** 표는 속성 이름으로 찾기 때문이다.

### 3.4 경로 없는 값 객체 — 그대로

모르는 키는 지금처럼 무시한다. 바뀌는 것은 무시한 키를 §3.3 으로 기록하는 것뿐이다. 경로 없는 쪽을 "RFC 밖이면 400" 으로 맞추지 않는다.
지금 통과하는 Entra 커스텀 확장 키(`urn:…:extension:<이름>:2.0:User:…` 를 키로 단 값)가 새로 실패해 비활성화를 막기 때문이다.
그래서 두 모양이 갈리는 곳은 "path 형의 RFC 밖 이름" 하나만 남는다(§11).

## 4. 조직 PATCH (④-1 이월·S9)

### 4.1 `externalId`

- core `GroupChange` 에 `reidentified(String externalId)` 를 더한다. PUT 이 이미 쓰는 "`externalId` 를 바꾼다" 칸(`reidentifies`)을 PATCH 도 켠다. 자바독의 "PUT 만 참이다" 를 고친다.
- 판정은 이미 있는 길을 쓴다. 유스케이스의 조직 변경은 헤더의 `externalId` 가 **바뀌었을 때만** 락 안에서 GSI3 조회와 본 테이블 재확인을 한다. 겹치면 아무것도 쓰지 않고 `DirectoryConflictException` 이고, 라우터에서 409 `uniqueness` 가 된다. 같은 값이면 확인 없이 지나간다. core 로직은 바뀌지 않는다.
- PATCH 적용기 — 두 모양이 같은 규칙이다:
  - path `externalId`: add·replace 는 그 값으로 바꾸고, remove 는 비운다(null).
  - 경로 없는 값의 `externalId` 키: add·replace 와 같다.
  - Okta 가 경로 없는 값에 함께 싣는 `id` 키는 지금처럼 무시한다.

### 4.2 코어 Group URN 접두 (S9)

조직 PATCH 의 path 와 경로 없는 값의 키에서 `urn:ietf:params:scim:schemas:core:2.0:Group:` 를 대소문자 없이 뗀다. 직원 쪽과 같은 `stripUrn(이름, URN)` 이다.
`…:Group:displayName`, `…:Group:members`, `…:Group:externalId` 가 접두 없는 이름과 같게 풀린다.

## 5. 형식 (S6·S8·S1)

### 5.1 속성 이름 대소문자 (S6)

- SCIM 요청 DTO 여덟에 `@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)` 를 단다.
  대상은 `ScimUser`, `ScimGroup`, `ScimName`, `ScimEmail`, `ScimMember`, `ScimPatchOp`, `ScimOperation`, `ScimSearchRequest` 다.
- 클래스 단위로만 건다. 전역 `ObjectMapper` 는 건드리지 않아 관리 API 의 JSON 은 그대로다.
- `ScimPatchOp` 의 `"Operations"`(RFC 가 대문자로 정함)는 `"operations"` 로 와도 받는다.
- PATCH 값 객체 안의 키는 이미 대소문자 없이 읽는다(`ScimPatchApplier.attribute`).
- 응답 직렬화는 바뀌지 않는다.
- **구현 중 정한 것 — 확인했다: 관리 API 는 SCIM DTO 를 쓰지 않는다.** 그래서 영향이 없다.
- **구현 중 정한 것 — 같은 속성이 대소문자만 달리 두 번 오면 어느 값이 쓰이는지는 정하지 않았다.** Jackson 에 맡긴다. 이것은 §11 에 적는다.

### 5.2 PATCH 의 `active` 값 (S8)

- 받는 값:
  - JSON boolean.
  - 문자열 `"true"`/`"false"`(대소문자 무관). Entra 가 `"True"`/`"False"` 를 문자열로 보낸다는 근거가 문서에 있다.
- 그 밖은 400 `invalidValue` 다(`"yes"`, `"1"`, `" true"`). 조용히 비활성화하지 않는다.
- POST·PUT 본문의 `active`(`Boolean`)는 Jackson 이 boolean 이 아닌 문자열을 이미 400 으로 거절한다. 이것은 테스트로 확인만 한다.
- **구현 중 정한 것 — POST·PUT 본문의 `"active":"yes"` 는 바꾸기 전에도 Jackson 이 400 으로 거절했다.** POST 를 확인 테스트로 고정했다(PUT 은 같은 DTO).

### 5.3 `Location` (S1)

- 직원·조직 POST 201 에 `Location` 헤더를 단다. 값은 본문 `meta.location` 과 같다(`/scim/v2/Users/<id>`, `/scim/v2/Groups/<id>`).
- 상대 참조는 HTTP 가 허용한다(RFC 9110 §10.2.2). 절대 URL 은 프록시 뒤에서 forwarded 헤더 설정이 필요해 범위 밖이다(§11).
- 아이디 인코딩은 더하지 않는다. 아이디가 서버 발급 UUID 다(④-1).

## 6. 문서로 정리 (S5 앞부분·S12·M18)

- README SCIM 절:
  - **저장하는 직원 속성 목록**(`userName`, `displayName`, `externalId`, `active`, `name` 여섯 칸, `type: "work"` 이메일 하나)과 "그 밖의 RFC 속성(§3.2)은 path 로 와도 받아서 버린다" 를 적는다.
    지금의 "path 로 PATCH 하면 400, 매핑에서 빼라" 문단을 바꾼다. `manager` 를 빼라는 안내도 지운다(M18).
  - 무시한 속성은 `scim_patch_ignored_total{attribute}` 로 본다는 것을 적는다.
  - 커스텀 확장을 path 로 보내면 400 이라는 것을 적는다.
  - **거절하는 조회 모양(S12)** 목록 — `manager` 필터, `emails[type eq "work"]` 필터, Ping `co`, JumpCloud 이메일 재연결 — 과 "매칭 속성은 `userName` 이나 `externalId` 를 쓴다" 를 적는다.
  - **S5 앞부분**:
    - `externalId` 없는 조직 POST 를 재시도하면 같은 이름의 조직이 생길 수 있다.
    - 멤버 없는 쪽이 재시도 찌꺼기다. 관리 API `?displayName=` 으로 찾는다.
    - IdP 가 조직 `externalId` 를 보낼 수 있으면 매핑한다. 그러면 409 로 막힌다.
  - 조직 PATCH 설명을 고친다 — PATCH 로도 `externalId` 를 바꾸고 겹치면 409, 코어 Group URN 접두를 받는다.
- 점검 문서:
  - M5·M18·S1·S6·S8·S9 와 ④-1 이월에 해결 표시를 한다.
  - S5 앞부분·S12 에는 "문서로 정리" 표시를 한다.
  - ⑤-1 과 같은 모양을 쓴다.
  - **구현 중 정한 것 — 점검 문서에는 ④-1 이월 행이 없어 표시하지 않는다.** 이월은 ④-1 설계(§3.2)와 이 설계의 §4.1 이 기록한다.

## 7. 바뀌는 곳

| 모듈 | 바뀌는 것 |
|---|---|
| core | `GroupChange.reidentified` |
| connector-scim | `ScimRfcAttributes`(새), `IgnoredAttributeObserver`(새). `ScimPatchApplier`: §3.1 판정, 받아서 버린 이름 알림, 조직 `externalId`·Group URN, `active` 값. DTO 여덟에 대소문자 무시. 핸들러: `Location`, DEBUG 한 줄, 관찰자. `ScimConfig`: 관찰자 주입, 없으면 아무 일도 안 함 |
| app-scim | `ScimSyncMetrics` 가 `IgnoredAttributeObserver` 를 구현(`scim.patch.ignored`), 조립 |
| 문서 | README SCIM 절, 점검 문서 |

규모 영향:
- 판정·형식 변경에는 저장소 읽기가 없다.
- 조직 PATCH 가 `externalId` 를 **바꿀 때만** GSI3 조회 한 번이 는다(PUT 과 같다).

## 8. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| M5 회귀 | 점검 문서의 Entra 요청 — `phoneNumbers[type eq "work"].value` replace + 경로 없는 `{"active":false}` — 이 200 이고 비활성화가 반영된다(직원 PATCH 는 200 과 리소스를 돌려준다) | connector-scim 핸들러, app-scim e2e |
| 무시 규칙 | 다음 path 가 오류 없이 지나가고(직원 PATCH 는 200) 아무것도 바뀌지 않는다: `addresses[…].streetAddress`, `emails[type eq "other"]`, `…:enterprise:2.0:User:manager`·`…:enterprise:2.0:User:department`, `title`, 코어 URN 접두가 붙은 `title` | connector-scim `ScimPatchApplierTest` |
| 400 유지 | `name.givenNmae`, `phoneNumber`, 커스텀 확장 path, `id` 가 400 `invalidPath` 다 | connector-scim |
| 보이게 하기 | 무시한 이름이 관찰자로 가고, 라벨은 표의 정규 이름 또는 `other` 다. DEBUG 줄에는 직원 id 와 속성 이름만 실린다 — 집합에 정규 이름·`other` 만 들어가므로 구조로 보장한다(별도 로그 단정 없음) | connector-scim, app-scim `ScimSyncMetrics` |
| 조직 `externalId` | path·경로 없는 값·remove 가 `externalId` 를 바꾼다. 다른 조직의 값이면 409 이고 아무것도 바뀌지 않는다. 같은 값이면 확인 없이 지나간다 | connector-scim, core, app-scim e2e |
| Group URN | 접두 붙은 `displayName`(path·경로 없는 값)이 이름을 바꾼다 | connector-scim |
| S6 | 조직 PUT `"Members"` 가 멤버를 지키고, 직원 PUT `"Active":false` 가 비활성이고, `.search` `"Filter"` 가 거른다(점검이 spike 브랜치에서 재현한 모양) | connector-scim 핸들러 |
| S8 | `true`·`false`·`"True"`·`"false"` 는 반영되고, `"yes"`·`"1"`·`" true"` 는 400 `invalidValue` 이고 바뀌지 않는다. POST 본문의 `"active":"yes"` 는 400 | connector-scim |
| S1 | 직원·조직 POST 의 `Location` 이 본문 `meta.location` 과 같다 | connector-scim 핸들러, app-scim e2e |
| 규모 | 기존 규모 테스트 전체가 바뀐 적용기를 지난다 — 머지 전 `test`·`scaleTest` 둘 다 | 전체 |

- **구현 중 정한 것 — app-scim `ScimNameEndToEndTest` 의 "저장하지 않는 속성은 거절한다" 를 새 규칙으로 바꿨다.**
  - `title` 은 받아서 버리고 `name.givenName` 은 반영한다.
  - `name.givenNmae` 는 400 이다.
  - 계획은 같은 모듈 테스트만 짚었다. 이 테스트는 작업별 검토가 찾았다.

## 9. 결과 (구현 후 기록)

2026-10-06, 브랜치 `audit-scim-requests` f0ac29d(그 뒤 커밋은 스펙만), DynamoDB Local·OpenFGA 컨테이너, 개발 노트북.

- **`./gradlew cleanTest test`** — 1,425개 통과, 4분 40초(⑤-1 1,311개에서 +114).
  - 첫 실행은 app-ldap `LdapImmutableIdEndToEndTest` 2건이 실패했다. 임베디드 LDAP 의 "LDAP connection has been closed" 로 첫 동기화가 재시도 3회 뒤 FAILED 가 됐고, 둘째 실패는 그 연쇄다.
  - 이 슬라이드는 LDAP 코드를 건드리지 않았다. 별도 과제로 띄운 간헐 실패와 같은 증상이다. 다시 돌리자 전부 통과했다.
- **`./gradlew cleanScaleTest scaleTest`** — 79개 통과, 14분 47초. 새 규모 테스트는 없다 — 요청 해석은 저장소 읽기를 늘리지 않는다.
- **규모 실측**(같은 실행, 시간은 참고):

  | 항목 | ⑤-1 | ⑤-2 |
  |---|---|---|
  | LDAP 전체 동기화 6,124명 — 이름 기반(`LdapScaleSyncCostTest`) | 10.5초 / 검증 2.7초 | 12.5초 / 검증 3.4초 |
  | 같은 조직도 — `entryUUID`(`LdapEntryUuidScaleTest`) | 10.5초 / 검증 3.6초 | 7.8초 / 검증 3.6초 |
  | SCIM 조직 먼저 순서(S1-b) — 직원 6,124명 POST + 멤버 PATCH 350건 | 41.0초 | 49.1초 |
  | 10만 명 조직 삭제 | 38.2초 | 40.4초 |
  | `type` 없는 멤버 1,000명 추가 | 891ms | 1,247ms |

  쓰기 길은 바뀌지 않았다. 차이는 같은 노트북에서 실행할 때마다 생기는 흔들림이다(LDAP 두 줄이 서로 반대로 움직인 것도 같은 까닭이다).
- **남은 사소한 것**(최종 검토 뒤 고치지 않고 남김):
  - 조직 PATCH `externalId` 에 객체·배열 값이 오면 400 `invalidValue` 다. 코드와 테스트에는 있고 §4.1·README 에는 적지 않았다.
  - §8 Group URN 행이 path 형 `members`·`externalId` 테스트를 싣지 않는다.
  - README 의 "`manager` 를 PATCH path 로 보내면 200" 은 enterprise URN 형태에만 맞다. 맨 `manager` 는 400 이다(§3.1).

## 10. 왜 다른 길을 안 갔나

- **400 을 유지하고 README 만 강화(M5 의 다른 안).** 코드는 그대로지만, 매핑을 정리하지 않은 테넌트에서 퇴사자 권한이 남는 위험이 남는다. 안전이 고객 설정에 달린다.
- **모르는 path 를 전부 무시.** 오타(`dispalyName`)까지 조용히 통과한다. 저장하는 속성이 영영 갱신되지 않는 불일치가 생긴다.
- **모든 확장 스키마를 무시.** 같은 이유로 선을 RFC 정의에 긋는다. 커스텀 확장의 잔여는 §11 이다.
- **RFC 표를 스키마 JSON 리소스로 싣기.** 나중에 `/Schemas` 의 바탕이 될 수 있지만 지금은 크기만 는다. 표 하나면 충분하다.
- **무시를 요청마다 WARN.** Entra 가 매번 다시 보내 경고가 정상 흐름이 된다.
- **조직 PATCH 의 `externalId` 를 계속 막고 두 모양만 맞추기(400 `mutability`).** 코드는 단순하지만 RFC(readWrite)와 어긋나고, PUT 으로는 바뀌는데 PATCH 로는 안 바뀌는 비대칭이 남는다.
- **`externalId` 없는 조직 POST 에 `displayName` 중복 409(S5).** RFC(uniqueness: none)를 어기고, 이름 중복을 허용하는 IdP 의 정상 조직을 막고, 판정 조회가 하나 는다.
  - 버려지는 조직은 대개 비어 있다(Okta 는 빈 멤버로 만들고 PATCH 로 채운다).
  - 그 아이디로 권한을 준 곳도 없어 권한이 새지 않는다.
- **`active` 문자열을 전부 거절.** 표준만 보면 boolean 만 받아야 하지만, Entra 가 문자열을 보낸다는 문서 근거가 있다. 근거가 있는 모양만 받는다.
- **`Location` 을 절대 URL 로.** 프록시 뒤 forwarded 헤더 설정이 함께 필요하다. IdP 는 본문 `id` 를 읽는다.

## 11. 이 설계가 말할 수 없는 것

- **커스텀 확장 path 는 여전히 400 이다** — 같은 키를 경로 없는 값으로 보내면 무시한다(§3.4). Entra 커스텀 확장을 옵션 없이(path 형으로) 보내는 테넌트는 그 속성이 든 PATCH 의 비활성화가 막힌다.
- **"Entra 가 같은 연산을 매번 다시 싣는다" 는 추정이다.** 맞든 틀리든 이제는 직원 PATCH 가 200 이라 해가 없다.
- **RFC 가 읽기 전용으로 정한 `groups` 도 무시한다.** 엄밀히는 400 `mutability` 다. 이렇게 보내는 IdP 근거는 없고, 경로 없는 값에서는 원래 무시했다.
- **`Location` 은 상대 참조다.**
- **S5 앞부분은 막지 않는다** — 재시도 찌꺼기 조직이 남을 수 있다(§6).
- **Entra·Okta 실제 요청으로 확인하지 못했다.** 요청 모양은 점검 문서의 문서 근거를 따른다.
- **저장하는 `emails` 의 우리가 적용하지 못하는 모양도 받아서 버린다.** 필터 없는 `emails.value`, 저장된 work 이메일을 고를 수 있는 다른 필터가 여기에 든다. 그 모양으로 보내는 IdP 는 이메일 갱신이 조용히 빠진다. 메트릭 `attribute=emails` 로 보인다.
- **같은 속성이 대소문자만 달리 두 번 온 본문의 결과는 정하지 않았다.**
  - 정확히는 — 레코드의 칸이 다 나오기 전의 중복은 나중 값이 이기고, 칸이 다 나온 뒤의 중복(대소문자만 다른 것 포함)은 Jackson 이 `InvalidDefinitionException` 을 내 500 이 된다.
  - ⑤-1 에서 그 예외(서버 정의 버그)를 500 에 두었고, 메시지로 가르는 신호를 지어내지 않는다. RFC 8259 는 중복 이름을 정하지 않으며 그렇게 보내는 IdP 근거가 없다.
- **M18 은 `manager` path PATCH 의 400 만 해소한다.** 참조 확인 `id eq … and manager eq …` 필터는 여전히 400(S12)이고, Entra 가 이것을 참조 실패로 세어 격리 상한에 넣는지는 확인하지 못했다(추정).

## 12. 범위 밖

- `/Schemas`·`/ResourceTypes`(지금 501)로 저장하는 속성을 표준 방식으로 알리기 — 후속 후보.
- 쿼리 파라미터 이름(`filter`·`attributes`)의 대소문자.
- ⑤-1 §11 의 후속(재적재 중 락 획득 재시도 조기 종료). **→ 해결(2026-10-07, ⑥-2)**
- 권고 ⑥(나머지 성능: P3·P4·P5·P6), 기존 백로그, 인증(마지막).
- **버린 연산만 있는 직원 PATCH 도 락 안에서 직원 전체 diff·Check·저장을 돈다** — 10만 명 Entra 초기 동기화의 `manager` PATCH 가 전에는 빨리 실패했다. 아무것도 안 바뀌는 PATCH 를 일찍 끝내면 그 직원의 어긋남 고치기를 잃으므로 ⑥ P3(락 재시도)와 함께 정한다. **→ 해결(2026-10-07, ⑥-2 — 모든 연산이 버리는 속성인 PATCH 만 락 없이)**
