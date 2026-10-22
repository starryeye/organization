# SCIM 표준 잔여 — 키 한도를 넘는 값, `/Schemas`·`/ResourceTypes` — 설계

- 날짜: 2026-10-17
- 슬라이드: ③ SCIM 표준 잔여(운영 견고성 ② 다음, 인증 앞)
- 출발점:
  - S-1 설계(2026-09-25)가 남긴 것으로 둔 키 한도를 넘는 값의 500.
  - ⑤-2 설계(2026-10-06) §12: "`/Schemas`·`/ResourceTypes` — 후속 후보".
  - 점검(2026-09-09) S-4·전체 점검(2026-09-28) S7.

## 1. 문제

### 1.1 키 한도를 넘는 값은 500 이다

실제 DynamoDB 는 파티션키 2048바이트, 정렬키 1024바이트(UTF-8)를 넘는 키를 `ValidationException` 으로 거절한다. GSI 키 속성도 같다. DynamoDB Local 은 호출마다 다르다 — GetItem·BatchGetItem 키와 GSI 정렬키 피연산자는 거절하지만 Query 의 파티션키 값은 2048바이트를 넘어도 받는다. 지금까지의 시험은 긴 값을 보내지 않아 보지 못했다. 클라이언트가 준 값이 키가 되는 자리는 많은데, 길이를 보는 곳은 `userName`·조직 `externalId` 표지판(1024바이트, ②-a) 둘뿐이다.

| 갈래 | 자리 | 지금(실제 AWS) |
|---|---|---|
| 읽기 | SCIM 경로 아이디(`/Users/{id}`·`/Groups/{id}`), 필터 값(`id`·`userName`·`externalId`·`displayName`), 멤버 값(타입 판정·`members[value eq]`·멤버 직원 읽기) | 500 |
| 읽기 | 관리 API 직원·조직 상세, 조직 멤버, 접두어·`externalId` 검색, 이력 | 500(Spring 기본) |
| 쓰기 | 직원 `displayName`(GSI2 정렬키), 직원 `externalId`(GSI3 파티션키), 조직 `displayName`(GSI1 정렬키), 넣는 멤버 아이디(멤버 줄 정렬키 `MEMBER#GROUP#<id>`), 공백뿐인 조직 `externalId`(표지판 검사가 건너뜀) | 500 |

- 500 은 "서버 잘못" 신호다. IdP 가 같은 요청을 되풀이한다.
- **쓰기는 OpenFGA 를 쓴 뒤에 실패한다.**
  - 조직 POST 는 새 조직의 멤버 튜플을 쓴 뒤 META 쓰기에서 실패한다. 그 튜플은 주인이 없다(재시도는 새 UUID 를 받는다).
  - 조직 PUT·PATCH 는 OpenFGA 를 바꾸고 나간 멤버 줄을 지운 뒤 실패한다. 들어온 멤버 줄은 쓰이지 않는다.
  - 직원 PUT·PATCH 는 `active` 를 바꾸면 튜플을 쓴 뒤 실패한다.
- 클라이언트가 고를 수 있는 아이디는 경로·필터·멤버 참조·관리 API 값뿐이다. 직원·조직 아이디는 서버가 만든 UUID 다(④-1). 경로·쿼리 값은 Netty 기본 시작 줄 한도(약 4KB)까지, 본문 값은 본문 한도(256KB)까지 온다.

### 1.2 발견 엔드포인트가 501 이다

`/ServiceProviderConfig` 는 있지만 `/Schemas`·`/ResourceTypes` 는 501 이다. RFC 7644 §4 는 둘을 발견 엔드포인트로 정한다.

- "/Schemas" 는 지원하는 스키마 전부를 ListResponse 로 돌려준다(SHALL).
- 클라이언트는 자원 엔드포인트를 "/ResourceTypes" 로 찾는다(SHOULD, §3.2).

지금 연동하는 IdP(Entra·Okta·Ping·OneLogin·JumpCloud)는 쓰지 않는다(점검 S-4). 표준 검증 도구와 스키마를 읽는 클라이언트는 부딪힌다.

## 2. 결정 (사용자 확인, 2026-10-17)

| 무엇 | 고른 것 | 버린 것 |
|---|---|---|
| 범위 | 키 한도(A)와 `/Schemas`·`/ResourceTypes`(B) | A 만 |
| A 의 길 | 쓰기는 core 가 먼저 막고(400), 읽기는 저장소가 막는다(없음) | 입구에서만 막기, 긴 값을 해시해 키에 넣기(§8) |
| 그대로 둔 것 | 칸이 다 나온 뒤의 중복 키(500), 커스텀 확장 path·`manager eq`·이메일 필터(400), `groups` 무시, 쿼리 파라미터 이름 대소문자, 옛 테이블 기동 검사 | — (§11) |

## 3. 쓰기 — core 가 키에 들어갈 값을 먼저 본다

**규칙: 키나 색인에 들어갈 값은 한도 안이어야 한다. 넘으면 400 `invalidValue` 이고, 그 요청은 아무것도 쓰지 않는다.**

| 값 | 들어가는 키 | 한도 (UTF-8) |
|---|---|---|
| `userName` | 표지판·GSI1 정렬키(소문자) | 1024바이트. `Locale.ROOT` 소문자로 만든 뒤 잰다(지금과 같다) |
| 직원 `displayName`(본문에 없으면 `name.formatted`, 그다음 `userName` 으로 정해진 값) | GSI2 정렬키(소문자) | 1024바이트. 소문자로 만든 뒤 잰다 |
| 조직 `displayName` | GSI1 정렬키(소문자) | 1024바이트. 소문자로 만든 뒤 잰다 |
| 직원·조직 `externalId` | GSI3 파티션키, 조직 표지판 | 1024바이트. 직원은 실제 한도가 2048이지만 조직 표지판과 같게 둔다. **공백뿐인 값도 잰다** |
| 넣는 멤버의 아이디(조직 POST·PUT 의 `members`, PATCH add·replace) | 멤버 줄 정렬키 `MEMBER#GROUP#<id>`(1024)와 OpenFGA 튜플 `user` 칸(512) | 500바이트 |

- **멤버 아이디의 500은 두 한도 가운데 작은 쪽을 내린 값이다.**
  - 멤버 줄 정렬키 `MEMBER#GROUP#<id>` 는 1024바이트라 아이디는 1011바이트까지다.
  - 멤버 튜플의 `user` 칸은 `user:<id>`(직원) 또는 `group:<id>`(조직)이고 512바이트 이하여야 한다. 아이디는 507·506바이트까지다.
  - 작은 쪽(506)을 500으로 내린다. 1000으로 두면 507~1000바이트 아이디가 쓰기 검사를 지나 멤버 줄로 저장되고, 나중에 조직 삭제가 그 튜플을 지우다 OpenFGA 400 을 받아 503 이 되풀이된다.
- **어디서 보나.** 쓰기 락 안에서, 표지판을 차지하기 전, OpenFGA 를 보기 전이다(지금 `userName` 길이를 보는 자리).
  - 대상은 `createUser`·`changeUser`·`createGroup`·`changeGroup`, 그리고 테스트용 `upsertUser`·`upsertGroup` 이다.
  - PUT·PATCH 는 바꾼 결과 값을 본다. 바뀌지 않은 값도 결과에 있으면 본다 — 지금 저장된 값은 이미 한도 안이다.
- **예외 하나.** `UniqueValueTooLongException` 을 모든 값에 쓰는 이름으로 넓힌다.
  - 문구에는 어느 속성인지와 한도를 담고, 값은 담지 않는다.
  - SCIM 은 지금처럼 400 `invalidValue` 다.
- **빼는 연산은 보지 않는다.** `remove members[value eq "긴 값"]` 은 읽기다. §4 의 "없음" 이 되어, 멤버가 아닌 것을 빼는 지금처럼 204 다.
- **LDAP 은 보지 않는다.** 이 검사는 SCIM 쓰기 경로에만 건다(§7).

## 4. 읽기 — 저장소가 "그런 키는 없다" 로 답한다

**규칙: DynamoDB 한도를 넘는 키(파티션키 2048, 정렬키 1024바이트)의 아이템은 있을 수 없다. 그런 키는 DynamoDB 를 부르지 않고 "없음" 으로 답한다.**

- **도우미 하나.** `Keys` 에 "파티션키에 들어가나 / 정렬키에 들어가나" 를 둔다. 접두어(`USER#`·`GROUP#`·`MEMBER#USER#`·`MEMBER#GROUP#`·`AUDIT#USER#`·`AUDIT#GROUP#`)와 소문자 변환까지 끝난 **최종 키 문자열**을 잰다 — 소문자로 바꾸면 바이트가 늘 수 있다(예: U+0130 은 2에서 3바이트).
- **거치는 곳.** 클라이언트 값이 키가 되는 읽기 메서드 전부(약 20개).

  | 저장소 | 메서드 |
  |---|---|
  | 상태 | 직원·조직 찾기, 조직 이름표 찾기, 멤버 종류 판정, 멤버 확인, 멤버 목록, 소속 조직 찾기, `externalId` 로 찾기 |
  | 조회 | 아이디·`userName`·`externalId`·표시명으로 찾기 |
  | 검색 | 직원 `userName`·표시명 접두어, 조직 표시명 접두어, 조직 요약, 조직 멤버 쪽 |
  | 이력 | 대상의 이력 쪽 |

  묶음 읽기(멤버 종류 판정, 멤버 확인, 멤버 직원 읽기)는 한도를 넘는 아이디만 빼고 나머지를 읽는다.
- **클라이언트가 보는 것** — 모두 지금 "없는 대상" 과 같은 응답이다.

  | 요청 | 지금(실제 AWS) | 이제 |
  |---|---|---|
  | `GET`·`PUT`·`PATCH`·`DELETE` `/Users`·`/Groups/{긴 id}` | 500 | 404 |
  | `filter=id`·`userName`·`externalId`·`displayName eq "긴 값"` | 500 | 200 빈 목록 |
  | 조직 PATCH `remove members[value eq "긴 값"]` | 500 | 204 |
  | 타입 없는 긴 멤버 아이디를 넣기 | 500 | 판정이 "없음" → §3 검사가 400 |
  | 관리 API 직원·조직 상세, 조직 멤버 | 500 | 404 |
  | 관리 API 접두어·`externalId` 검색, 이력 | 500 | 200 빈 쪽 |

- **쓰기는 저장소에서 다시 보지 않는다.** SCIM 쓰기는 §3 이 먼저 막고, LDAP 은 §7 이다.

## 5. `/Schemas`·`/ResourceTypes`

**RFC 7644 §4 그대로 두되, 우리가 실제로 저장하고 돌려주는 것만 알린다.**

- **엔드포인트.** GET 만 받고, 다른 메서드는 지금처럼 405 다.

  | 요청 | 응답 |
  |---|---|
  | `GET /scim/v2/ResourceTypes` | ListResponse — `User`(`/Users`), `Group`(`/Groups`) |
  | `GET /scim/v2/ResourceTypes/{이름}` | 단건. 이름은 `User`·`Group` 이고 대소문자를 가리지 않는다. 모르면 404 SCIM 오류 |
  | `GET /scim/v2/Schemas` | ListResponse — 코어 User·Group 스키마 |
  | `GET /scim/v2/Schemas/{URN}` | 단건. URN 은 대소문자를 가리지 않는다. 모르면 404 SCIM 오류 |

  - `filter` 가 오면 403 이다(RFC 7644 §4 의 SHOULD). 그 밖의 쿼리 파라미터는 무시한다.
  - 지금의 501 목록에서 둘을 뺀다. `Bulk`·`/Me` 는 501 그대로다.
- **ResourceType 자원**(RFC 7643 §6)
  - `schemas`, `id`·`name`(`User`·`Group`), `endpoint`(`/Users`·`/Groups`), `description`, `schema`(코어 URN)
  - `meta`(`resourceType: "ResourceType"`, `location`)
  - `schemaExtensions` 는 없다 — 저장하는 enterprise 속성이 없다.
- **Schema 자원**(RFC 7643 §7)
  - `schemas`, `id`(URN), `name`, `description`, `attributes`
  - `meta`(`resourceType: "Schema"`, `location`)
- **속성** — 응답 DTO(`ScimUser`·`ScimGroup`·하위 레코드)와 같은 속성만 담는다. 공통 속성(`id`·`externalId`·`meta`)은 RFC 7643 의 예처럼 스키마에 넣지 않는다.
  - **User**
    - `userName`: 필수, 대소문자 무시, 서버 유일 — ②-a 표지판과 같다.
    - `name`: 복합, 하위는 `formatted`·`familyName`·`givenName`·`middleName`·`honorificPrefix`·`honorificSuffix`.
    - `displayName`.
    - `emails`: 복수 복합, 하위는 `value`·`type`·`primary`. 한 개만 저장하고 `type: work`·`primary: true` 로 돌려준다.
    - `active`.
  - **Group**
    - `displayName`.
    - `members`: 복수 복합, 하위는 `value`·`type`. `type` 은 `User`·`Group` 이다. `display`·`$ref` 는 돌려주지 않아 넣지 않는다.
  - 각 속성의 성질(`type`·`multiValued`·`required`·`caseExact`·`mutability`·`returned`·`uniqueness`·`canonicalValues`)은 지금 코드의 실제 동작대로 적는다. 구현이 코드를 읽어 정하고, §9 의 시험이 그 일부를 고정한다.
- **어긋남을 막는다.** 시험이 응답 DTO 의 칸·하위 칸과 스키마의 속성·하위 속성을 맞대 본다. 한쪽에만 속성을 더하면 실패한다.
- **`ServiceProviderConfig` 는 그대로다.**

## 6. 바뀌는 곳

| 모듈 | 무엇 |
|---|---|
| core | 키 값 한도 검사(§3), 넓힌 예외, 유스케이스 여섯 곳의 검사 자리 |
| storage-dynamodb | `Keys` 의 한도 도우미, 읽기 메서드의 "없음"(§4) |
| connector-scim | 발견 자원(§5), 라우터의 경로·메서드 표, 예외 번역 |
| admin-api | 바뀌지 않는다 — 저장소가 "없음" 으로 답하면 지금의 404·빈 쪽이다 |
| 문서 | README(엔드포인트 표·501 목록·한도), S-1 설계·⑤-2 설계 §12·점검(2026-09-09) S-4·전체 점검(2026-09-28) S7 의 해결 표시 |

테이블·GSI·OpenFGA 모델은 바뀌지 않는다.

## 7. 이 설계가 말할 수 없는 것

- **LDAP 에서 온 긴 값은 막지 않는다.** 1KB 넘는 표시명 같은 값이 오면 지금처럼 그 회차의 현재상태 교체가 실패한다(FAILED). 사내 디렉터리에서 현실적이지 않다. **→ 바로잡음(2026-10-20 설계 §7)**
- **DynamoDB Local 은 키 길이를 호출마다 다르게 다룬다.** GetItem·BatchGetItem 키와 GSI 정렬키는 거절하고, Query 파티션키는 받는다. 그래서 실제 AWS 의 거절은 문서 근거(AWS 키 한도)이고, 시험은 "한도를 넘는 키로 DynamoDB 를 부르지 않는다" 를 고정한다.
- **`begins_with` 피연산자가 1024바이트를 넘을 때 AWS 가 거절하는지 확인하지 못했다.** 넘는 접두어로 시작하는 정렬키는 있을 수 없으니 거절하든 안 하든 "없음" 이 맞다.
- **우리 한도(500·1024바이트)는 DynamoDB 한도와 OpenFGA 한도에서 정한 값이다.** RFC 는 길이를 정하지 않는다. 직원 `externalId` 는 2048까지 들어가지만 1024로 막는다.
  - 멤버 아이디의 500은 DynamoDB 정렬키(1024)뿐 아니라 OpenFGA 튜플 `user` 칸(512바이트)에서도 온다.
  - 이 512는 실제 OpenFGA 로 확인했다(최종 리뷰 뒤 일회용 시험). `user` 가 정확히 512바이트인 튜플은 쓰이고, 513바이트는 쓰기가 400 `invalid TupleKey.User: value length must be at most 512 bytes`(`FgaApiValidationError`)로 거절된다. 지우기도 같고(`TupleKeyWithoutCondition.User`), `group:<id>` 도 같다.
  - 시험은 남기지 않았다. 한도는 `KeyValueLimits.MAX_MEMBER_ID_BYTES` 한 곳이고, OpenFGA 서버를 올리면 이 값을 다시 확인해야 한다.
- **`/Schemas` 는 자원 스키마(User·Group)만 싣는다.** `ServiceProviderConfig`·`ResourceType`·`Schema` 자신의 스키마(RFC 7643 §8.7.2)는 싣지 않는다.
- **`/Schemas` 는 받는 것이 아니라 저장하는 것을 알린다.** RFC 가 정의한 다른 속성(`nickName`·enterprise 확장 등)을 PATCH 로 받아서 버리는 것(⑤-2)은 그대로이고 스키마에 나오지 않는다.
- **IdP 의 실제 발견 요청으로 확인하지 못했다.** 지금 연동하는 IdP 는 부르지 않는다.

## 8. 왜 다른 길을 안 갔나

- **입구(SCIM 핸들러·관리 컨트롤러)에서만 막기.**
  - 고칠 곳은 적다.
  - 그러나 저장소의 한도 지식이 입구로 새고, 새 엔드포인트·내부 경로(타입 없는 멤버 판정은 락 밖에서 돈다)는 입구가 잊으면 다시 500 이다.
  - 쓰기를 입구에서 막아도 PATCH·PUT 은 바꾼 결과 값을 락 안에서만 안다.
- **긴 값을 해시해 키에 넣기.**
  - 어떤 길이든 받는다.
  - 그러나 `userName`·표시명 정렬과 접두어 검색이 깨지고 키 형식이 바뀐다. RFC 가 요구하지 않는 길이를 위해 치를 값이 아니다.
- **저장소가 쓰기도 막기.**
  - 마지막 그물은 된다.
  - 그러나 쓰기는 OpenFGA 다음이라 거기서 막으면 지금과 같은 어긋남이 남는다. SCIM 은 §3 이 먼저 막고, LDAP 까지 막으려면 회차의 실패 규칙을 새로 정해야 한다(§11).
- **`/Schemas` 에 RFC 의 전체 User 스키마를 싣기.** 저장하지 않는 속성을 지원하는 것처럼 알린다.
- **멤버 아이디 한도를 DynamoDB 정렬키 한도(아이디 1011바이트)로만 두기.** 처음 설계가 이렇게 1000으로 두었다. 최종 리뷰가 OpenFGA 튜플 `user` 칸(512바이트)이라는 두 번째 한도를 찾았다(§7). 그 한도를 모르면 507바이트 넘는 아이디가 쓰기 검사를 지나, 조직 삭제가 영영 503 이 되는 멤버 줄이 생긴다.

## 9. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| 쓰기 한도 | 값마다 한도 바이트는 통과, 한 바이트 넘으면 400(`invalidValue`)이고 표지판·OpenFGA·DynamoDB 에 아무것도 쓰지 않는다. 소문자로 늘어나는 글자, 공백뿐인 `externalId`, PATCH 의 바꾼 결과 값, 넣는 멤버, 빼는 멤버는 보지 않음 | core |
| 읽기 "없음" | 메서드마다 한도를 넘는 키는 DynamoDB 요청이 하나도 나가지 않고 없음이다(요청이 나가면 실패하는 감싸개 클라이언트). 한도 딱 그 바이트에서는 부른다. 묶음 읽기는 넘는 것만 뺀다 | storage-dynamodb(Local) |
| SCIM 끝단 | §4 표의 응답 — 긴 경로 아이디 404, 긴 필터 값 200 빈 목록, 긴 멤버 빼기 204, 긴 멤버 넣기·긴 표시명 400 | app-scim |
| 발견 | 목록·단건·대소문자·모르는 이름 404·`filter` 403·다른 메서드 405·`meta`, DTO 와 스키마의 속성 맞대기 | connector-scim |
| 관리 API | 긴 아이디 상세 404, 긴 접두어 검색 빈 쪽 | admin-api 또는 app 끝단 |
| 전체 | 머지 전 `test`·`scaleTest` 둘 다 | 전체 |

## 10. 결과 (구현 후 기록)

### 10.1 머지 전 검증

- `./gradlew cleanTest test`: 1,804개 통과(4분 46초, 5baf2cb).
- `./gradlew cleanScaleTest scaleTest`: 89개 통과(16분 24초, 5baf2cb). 이 슬라이드는 대량 경로를 바꾸지 않는다 — 멤버 한도 검사는 넣는 멤버마다 바이트 길이를 잴 뿐이다.
- 끝단(`ScimKeyLimitEndToEndTest`, DynamoDB Local·OpenFGA)이 §4 표의 응답을 본다.
  - 긴 경로 아이디 404, 긴 필터 값 빈 목록, 긴 멤버 빼기 204, 긴 표시명·긴 멤버 400, 관리 API 의 없음, 발견 엔드포인트 둘.
  - 긴 값은 Local 이 거절하는 호출(GetItem·BatchGet·GSI 정렬키)에 닿는 길이다. 가드가 빠지면 500 으로 실패한다. 긴 멤버는 타입 없는 2100바이트라 묶음 읽기(타입 판정) 가드까지 지난다.
  - 긴 표시명 400 뒤 같은 `userName` 으로 다시 만들면 201 이다 — 표지판이 남지 않는다.

### 10.2 구현 중 판정

- **과제 리뷰에서 정한 것.**
  - DynamoDB Local 은 키 길이를 호출마다 다르게 다룬다(Task 2 구현의 탐침 — GetItem·BatchGet 키와 GSI 정렬키는 거절, Query 파티션키는 받음). §1.1·§7 을 고쳤다. 코드의 판단("부르지 않는다")은 그대로다.
  - 조직 스키마의 `members.value` 를 `required` 로 알린다. value 없는 멤버는 POST·PUT·PATCH 모두 400 이라 `required: false` 는 거짓이었다.
  - 점검 S-4(`/Schemas` 없음)는 2026-09-09 점검 문서의 것이다. 해결 표시를 거기 달고 이 설계의 출처를 고쳤다.
- **최종 리뷰에서 고친 것.**
  - **멤버 아이디 한도를 1000에서 500바이트로 내렸다.** 멤버 아이디는 OpenFGA 튜플 `user` 칸에 들어가고 그 칸은 512바이트다(일회용 시험으로 확인, §7·§8). 1000이면 507바이트 넘는 없는 멤버가 쓰기 검사를 지나 조직 삭제가 영영 503 이 됐다.
  - 발견 스키마의 속성마다 `description`(RFC 7643 §7 "When applicable … MUST")을 달고, 공유 노드 대신 복사본을 내보낸다. README 표에 `filter` 403 을 드러냈다.
  - 모르는 멤버 아이디의 WARN 로그는 아이디를 64자로 자른다.
- **남긴 것.**
  - LDAP 에서 온 긴 값은 막지 않는다(§7·§11). 멤버 아이디가 OpenFGA 512 를 넘는 경우도 같다 — LDAP 의 아이디는 짧다.
  - 시험하지 않은 것: 실제 AWS 의 거절(문서 근거, §7), 실제 IdP 의 발견 요청.
  - 작은 것: 표지판 순서 단언이 끝 상태만 본다, `createGroup`·`upsertGroup` 의 검사 줄이 같다, `fits*`·`overMembers` 가 값마다 바이트 배열을 만든다(10만 명 PUT 에서도 ms 단위), 로그 자르기가 서로게이트 쌍을 가를 수 있다.

## 11. 범위 밖

- LDAP 에서 온 긴 값의 회차 처리(건너뛰기·잘라 넣기 등).
- 칸이 다 나온 뒤의 중복 키(500, ⑤-2 §11), 커스텀 확장 path·`manager eq`·이메일 필터(400, ⑤-2 §11·점검 S12), `groups` 의 `mutability`, 쿼리 파라미터 이름의 대소문자, 옛 테이블 기동 검사.
- `ServiceProviderConfig` 의 `authenticationSchemes`(인증 슬라이드).
- 인증(마지막).
