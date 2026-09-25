# SCIM 목록·필터 조회 (S-1) — 설계

> 브랜치 `scim-list-filter` (origin/main `fc61d17` 에서 분기).
> 발단: IdP 호환성 감사 [`2026-09-09-idp-conformance-audit.md`](2026-09-09-idp-conformance-audit.md) §2.1 — "치명".

## 1. 문제 — IdP 가 "이 사람이 이미 있는가" 를 물을 곳이 없다

우리 SCIM 엔드포인트에는 단건 조회(`GET /Users/{id}`)만 있고 **목록·필터 조회(`GET /Users`, `GET /Groups`)가
없다.** 404 가 나간다.

IdP 는 생성할지 갱신할지를 필터 조회로 정한다. Entra 는 연결 테스트에서부터 "무작위 사용자와 그룹" 을 조회하고,
Okta 는 `GET /Users?filter=userName eq "..."` 로 존재를 확인한다. 이것 없이는 **프로비저닝이 시작되지 않는다.**

`ServiceProviderConfig` 는 지금 `filter.supported=false`, `sort.supported=false` 를 선언한다.

## 2. 규모 전제 — 직원 10만 명 이상

**실제 운영 규모는 직원 10만 명이 넘는다**(2026-09-25 사용자). 규모 테스트 픽스처는 5천 명이라, 5천 명에서 괜찮은
설계가 운영에서도 괜찮다는 보장이 없다. 이 설계의 모든 비용은 **10만 명 기준**으로 계산한다.

설계 도중 이것이 한 번 결정을 뒤집었다 — 처음에는 "요청마다 인덱스를 다 읽고 잘라 준다" 를 골랐는데(5천 명이면
페이지당 약 2MB), 10만 명이면 페이지당 약 40MB, Okta 가져오기 한 번이 1,000페이지 × 40MB ≈ 40GB 읽기다(§10).

## 3. 결정 — 사용자와 정한 것 (2026-09-25)

| 주제 | 결정 | 버린 안 (§10 에 이유) |
|---|---|---|
| 필터 범위 | **`eq` + `and`** — 식별 속성 `eq` 하나를 인덱스로 찾고, `and` 로 붙은 나머지 `eq` 는 찾은 결과 위에서 확인. 그 밖은 400 `invalidFilter` | RFC 문법 전체, `eq` 만 |
| 대소문자 | **표준대로** — `userName`·`displayName` 은 대소문자를 가리지 않는다(RFC 7643 `caseExact=false`). 인덱스 키만 소문자, 저장·응답 값은 보낸 그대로 | 지금처럼 구분 |
| `externalId` | **새 GSI3** — 아이템이 이미 가진 `externalId` 속성을 파티션키로 | 전원 스캔, 미지원 |
| 필터 없는 목록의 페이지 | **DynamoDB 책갈피** — 다음 `startIndex` 의 이어 읽기 위치와 첫 페이지에서 센 `totalResults` 를 TTL 15분으로 저장 | 다 읽고 자르기, 건너뛰기+COUNT, 건수 카운터, 메모리 책갈피 |
| 정렬 | **인덱스 키만, 한 규칙** — 직원 `userName`, 조직 `displayName` 의 오름·내림. 필터 유무와 무관 | 임의 속성, 정렬 빼기 |
| 부가 기능 | **`attributes`·`excludedAttributes`, `POST /.search`, `sortBy` 모두** | — |
| 페이지 상한 | `count` 기본값·상한 **100** | — |

**원칙** — 표준이 정한 입력만 받고, 나머지는 종류별로 한 규칙으로 거절한다.
RFC 7644 는 필터 전체를 선택 기능으로 두고, 지원하지 않는 속성·연산자 조합에 `invalidFilter` 를 돌려주라고 정한다
(§3.4.2.2, §3.12). 좁게 받는 것이 표준 위반이 아니다.

**두 IdP 가 보내는 것** (Microsoft Learn "Develop a SCIM endpoint", 2026-09-16 판 확인 · Okta 는 감사 문서 기준):

| | 요청 |
|---|---|
| Entra | 연산자는 `eq`, `and` 만 쓴다(문서 명시). 사용자는 `userName`·`externalId`, 조직은 `displayName` 으로 조회 |
| Entra | `GET /Groups?excludedAttributes=members&filter=displayName eq "…"`, `GET /Groups/{id}?excludedAttributes=members` |
| Okta | `GET /Users?filter=userName eq "…"&startIndex=1&count=100`, `GET /Users?startIndex=1&count=100`, `GET /Groups?filter=displayName eq "…"&startIndex=1&count=100` |

## 4. 조회 규칙

### 4.1 필터 문법

```
filter   = term *( SP "and" SP term )
term     = attrPath SP "eq" SP compValue
attrPath = [ 리소스의 core 스키마 URN ":" ] attrName
compValue = JSON 문자열 / "true" / "false"
```

- `and`·`eq` 와 속성 이름은 대소문자를 가리지 않는다(RFC 7644 §3.4.2.2).
- URN 은 그 리소스의 core 스키마(`urn:ietf:params:scim:schemas:core:2.0:User` / `…:Group`)만 받는다.
- 공백은 RFC 의 ABNF 대로 한 칸(`SP`)이다.
- 이 문법에 맞지 않는 모든 것 — `or`·`not`, `eq` 가 아닌 연산자(`ne co sw ew pr gt ge lt le`), 괄호, 대괄호 값
  경로(`emails[type eq "work"].value`), 숫자·`null` 값 — 은 400 `invalidFilter` 다.

### 4.2 속성

| 리소스 | 속성 | `caseExact` | 찾는 법 | `and` 뒤 | 정렬 |
|---|---|---|---|---|---|
| User | `id` | 참 | GetItem (강한 일관성) | ✓ | |
| User | `userName` | **거짓** | GSI1 소문자 키 | ✓ | ✓ (기본) |
| User | `externalId` | 참 | GSI3 → GetItem | ✓ | |
| User | `displayName` | 거짓 | — | ✓ | |
| User | `active` | (불리언) | — | ✓ | |
| Group | `id` | 참 | GetItem (META) | ✓ | |
| Group | `displayName` | **거짓** | GSI1 소문자 키 | ✓ | ✓ (기본) |
| Group | `externalId` | 참 | GSI3 → GetItem | ✓ | |

- **인덱스로 찾을 조건 고르기** — `id` → `userName`/`displayName` → `externalId` 순으로 처음 나오는 것 하나.
  "찾는 법" 칸이 비어 있는 속성만으로 된 필터는 400 `invalidFilter` 다(스캔하지 않는다).
- **`and` 뒤의 조건**은 찾은 후보(몇 건) 위에서 메모리로 확인한다. 비교는 표의 `caseExact` 를 따른다.
- 값의 타입이 속성과 다르면(`active eq "true"`, `userName eq true`) 400 `invalidFilter` 다.
- 대소문자를 가리지 않는 비교는 `toLowerCase(Locale.ROOT)` 로 한다 — 인덱스 키와 같은 규칙이다.

### 4.3 정렬

- `sortBy` 는 직원 `userName`, 조직 `displayName` 만 받는다(URN 붙은 이름 포함). 그 밖은 400 `invalidValue`.
- `sortOrder` 는 `ascending`/`descending`(대소문자 무시), 없으면 `ascending`. 그 밖은 400 `invalidValue`.
- `sortBy` 가 없어도 같은 키의 오름차순으로 준다 — 결과 순서가 요청마다 흔들리지 않게.
- 필터 결과(메모리)는 소문자 키, 같으면 `id` 로 정렬한다. 필터 없는 목록은 인덱스 순서 그대로다.

### 4.4 페이지 (RFC 7644 §3.4.2.4)

- `startIndex` 는 1부터. 1 미만은 1 로 본다. `count` 가 없으면 100, 음수는 0, 100 초과는 100 으로 줄인다.
- `count=0` 이면 `Resources` 없이 `totalResults` 만 준다.
- 정수가 아닌 `startIndex`·`count` 는 400 `invalidValue`.
- 응답은 `ListResponse` — `schemas`(`urn:ietf:params:scim:api:messages:2.0:ListResponse`), `totalResults`,
  `startIndex`, `itemsPerPage`(실제로 담은 수), `Resources`. 결과가 0건이면 `Resources: []`(Entra 예시와 같다).

**필터 있는 조회** — 후보를 전부 읽고(몇 건) 정렬·자르기를 메모리에서 한다.

**필터 없는 목록 — 책갈피**

```
startIndex=1         → COUNT 질의로 totalResults + 인덱스 처음부터 count 건 → 책갈피(1+count) 저장
startIndex=N, 책갈피 있음 → 책갈피 위치부터 count 건 이어 읽기, totalResults 는 책갈피 값 → 책갈피(N+count) 저장
startIndex=N, 책갈피 없음 → COUNT + 앞의 N-1건 건너뛰기(키만 읽음) + count 건 → 책갈피 저장
```

- 책갈피 키는 `(리소스 종류, 정렬 방향, startIndex)` 다. `count` 는 키에 넣지 않는다 — 책갈피가 가리키는 것은
  "N번째 항목 앞" 이라는 위치라 `count` 와 무관하다.
- 두 가져오기가 동시에 같은 책갈피를 써도 둘 다 올바른 위치라, 마지막 쓰기가 이겨도 된다.
- **`totalResults` 는 첫 페이지(또는 책갈피가 없는 요청)에서 센 값을 이어 쓴다.** 가져오기 도중 직원이 늘거나
  줄어도 반영하지 않는다 — RFC 7644 §3.4.2.4 는 페이지 요청 사이에 결과가 달라질 수 있음을 인정한다.
- 책갈피가 없으면 건너뛰어서라도 **정확한** 페이지를 준다. 느릴 뿐 틀리지 않는다.
- 내림차순은 DynamoDB 역방향 질의(`ScanIndexForward=false`)다.

### 4.5 `attributes` · `excludedAttributes` (RFC 7644 §3.9)

- 리소스를 돌려주는 **모든 응답**(단건 GET, 목록, `.search`, POST·PUT·PATCH 응답)에 적용한다.
- 이름은 최상위 속성(`members`)과 한 단계 하위 속성(`name.formatted`, `emails.value`)이며, URN 붙은 이름도 받는다.
- `id` 와 `schemas` 는 어느 경우에도 남긴다(`id` 는 RFC 7643 에서 `returned: always`).
- 둘을 함께 주면 400 `invalidValue`(함께 쓸 수 없다고 정해져 있다). 모르는 속성 이름도 400 `invalidValue`.
- **조직 응답에 `members` 가 필요 없으면 멤버 줄을 읽지 않는다** — META 한 줄(`findGroupHeader`)만 읽는다. Entra 가
  조직을 조회할 때마다 1,600명 조직의 파티션 전체를 읽던 비용이 사라진다.
- `members` 가 필요하면 페이지에 든 조직만 동시성 8 로 `findGroup` 한다.

### 4.6 `POST /.search` (RFC 7644 §3.4.3)

- `POST /scim/v2/Users/.search`, `POST /scim/v2/Groups/.search`. 본문은 SearchRequest
  (`urn:ietf:params:scim:api:messages:2.0:SearchRequest`) — `filter`, `startIndex`, `count`, `sortBy`, `sortOrder`,
  `attributes`, `excludedAttributes`. 같은 조회 엔진으로 들어간다. 응답은 200 `ListResponse`.
- `schemas` 에 SearchRequest URN 이 없으면 400 `invalidSyntax`.
- **서버 루트 조회**(`GET /scim/v2/?filter=`, `POST /scim/v2/.search` — 여러 리소스 종류를 한꺼번에)는 지원하지 않고
  501 을 돌려준다(RFC 7644 §3.12 "Service provider does not support the request operation"). 두 IdP 모두 쓰지 않는다.

### 4.7 ServiceProviderConfig

`filter: {supported: true, maxResults: 100}`, `sort: {supported: true}`. 나머지는 그대로.

## 5. 저장소

### 5.1 GSI1 정렬키를 소문자로

- 직원 아이템 `GSI1SK = lower(userName ?: id)`, 조직 META `GSI1SK = lower(displayName ?: id)`.
- `userName`·`displayName` 속성은 보낸 그대로 둔다 — Entra 요구("Values sent should be stored in the same format").
- 이 키를 쓰는 기존 조회도 소문자로 묻는다: `findUserIdsByUserName`(생성 시 중복 판정 → `Kim` 이 있으면 `kim` 생성은
  409), admin 의 `userName`·조직명 접두사 검색(대소문자를 가리지 않게 된다 — 사용자 동의).
- admin 의 **직원 표시명** 검색은 GSI2 가 원문 `displayName` 속성을 키로 쓰므로 그대로 대소문자를 가린다. 범위 밖.

### 5.2 GSI3 — `externalId`

- 파티션키 = 아이템의 `externalId` 속성, 정렬키 = `PK`. **새 속성을 만들지 않는다**(GSI2 가 `displayName` 을 그대로
  쓰는 것과 같은 방식, `Keys.GSI2PK` 자바독).
- `externalId` 를 가진 아이템은 직원 아이템과 조직 META 뿐이다(`DynamoDbDirectoryStateRepository` 의 `EXTERNAL_ID`
  쓰기 두 곳). 멤버 줄·소속 줄에는 없어 인덱스에 실리지 않는다.
- **KEYS_ONLY.** 찾은 `PK` 로 본 테이블을 GetItem 한다 — 인덱스가 조금 늦어도 낡은 속성을 돌려주지 않고, 인덱스가
  작다. `PK` 접두사(`USER#`/`GROUP#`)로 종류를 가른다.
- 값마다 파티션이 따로라 쏠림이 없다.

### 5.3 새 읽기 포트

**`DirectoryQueryRepository`** (core/port, 구현 `DynamoDbDirectoryQueryRepository`)

```java
Flux<DirectoryUser> findUsersByUserName(String userName);      // GSI1 소문자 키 일치
Flux<DirectoryUser> findUsersByExternalId(String externalId);  // GSI3 → GetItem
Flux<GroupHeader>   findGroupHeadersByDisplayName(String displayName);
Flux<GroupHeader>   findGroupHeadersByExternalId(String externalId);

Mono<IndexPage<DirectoryUser>> listUsers(String from, int limit, boolean descending);
Mono<IndexPage<GroupHeader>>   listGroupHeaders(String from, int limit, boolean descending);
Mono<Long>   countUsers();                 Mono<Long>   countGroups();
Mono<String> skipUsers(long n, boolean descending);
Mono<String> skipGroups(long n, boolean descending);
```

- `from`·`IndexPage.next` 는 **불투명한 위치 문자열**이다(DynamoDB LastEvaluatedKey 를 인코딩 — admin 조회의
  `Cursor` 와 같은 방식). core 는 그 안을 모른다. `null` 은 처음.
- `id` 조회는 기존 `findUser`·`findGroupHeader`·`findGroup` 을 쓴다.
- **왜 `DirectorySearchRepository` 에 얹지 않나** — 그 포트는 admin 용 "접두사 + 커서" 로 정의돼 있다(자바독).
  SCIM 은 "완전 일치 + 위치" 로 계약이 다르다. 쓰기 경로의 심장인 `DirectoryStateRepository` 에 조회를 얹지 않는다는
  기존 원칙도 그대로다.

**`PageBookmarkRepository`** (core/port, 구현 `DynamoDbPageBookmarkRepository`)

```java
Mono<PageBookmark> find(ListingKind kind, boolean descending, long startIndex);
Mono<Void>         save(ListingKind kind, boolean descending, long startIndex, PageBookmark bookmark);
// PageBookmark(String position, long totalResults)
```

- 아이템: PK `PAGE#USER#ASC`(종류·방향), SK `START#<startIndex>`, 속성 `position`, `totalResults`,
  `expiresAt`(저장 시각 + 15분, epoch 초).
- 테이블에 DynamoDB TTL(`expiresAt`)을 켠다. TTL 삭제는 늦게 일어나므로 **읽을 때 `expiresAt` 이 지났으면 없는 것**
  으로 본다.
- 책갈피 수명 관리(TTL)가 디렉터리 읽기와 달라 포트를 나눈다.

### 5.4 테이블

- GSI3 는 테이블 생성 시 함께 만든다. TTL 설정도 초기화 때 켠다.
- **GSI1 키 값이 바뀌므로 기존 테이블은 재생성해야 한다**(①·③ 과 같다). 운영 배포 전이라 가능하다.
  그래서 GSI2 처럼 "기존 테이블에 없으면 더하기" 는 GSI3 에 두지 않는다. README 에 적는다.

## 6. 오류와 경계

| 입력 | 처리 |
|---|---|
| 필터 파싱 실패, `eq`·`and` 외 연산자, 괄호·대괄호, 모르는 속성, 인덱스로 찾을 `eq` 가 없음, 타입 불일치 | 400 `invalidFilter` |
| 인덱스 키가 아닌 `sortBy`, 표준 밖 `sortOrder` | 400 `invalidValue` |
| 정수가 아닌 `startIndex`·`count` | 400 `invalidValue` |
| `startIndex < 1` / `count < 0` / `count > 100` | 1 / 0 / 100 으로 봄 |
| `attributes` 와 `excludedAttributes` 동시, 모르는 속성 이름 | 400 `invalidValue` |
| `.search` 본문에 SearchRequest URN 없음, 본문 파싱 실패 | 400 `invalidSyntax` |
| 서버 루트 조회 | 501 |
| 결과 0건 | 200, `totalResults: 0`, `Resources: []` |

- 오류 번역은 지금처럼 `ScimRouter.toScimError` 한 곳이다. `ScimException` 에 `invalidFilter`·`invalidValue` 생성자를
  더한다.
- 목록 조회는 쓰기 락을 잡지 않는다(읽기뿐이고, 재적재 중에도 SCIM GET 은 통과한다는 기존 규칙과 같다).

## 7. 부품 배치

| 모듈 | 새로 / 바뀜 |
|---|---|
| core | 새: `port/DirectoryQueryRepository`, `port/PageBookmarkRepository`, `query/IndexPage`, `query/PageBookmark`, `query/ListingKind` |
| storage-dynamodb | 새: `DynamoDbDirectoryQueryRepository`, `DynamoDbPageBookmarkRepository`. 바뀜: `Keys`(GSI3, `PAGE#`, 소문자 키), `TableInitializer`(GSI3, TTL), `DynamoDbDirectoryStateRepository`(GSI1SK 소문자, `findUserIdsByUserName`), `DynamoDbDirectorySearchRepository`(접두사 소문자), `DynamoDbConfig`(빈) |
| connector-scim | 새: `ScimQuery`(파라미터·본문 → 한 형태, 경계 규칙), `ScimFilter`(파서), `ScimAttributeProjection`, `ScimUserListing`·`ScimGroupListing`(조회 실행), `dto/ScimListResponse`, `dto/ScimSearchRequest`. 바뀜: `ScimRouter`(라우트·SPC·루트 501), 두 핸들러(`list`·`search`, 응답에 속성 선택), `ScimSchemas`, `ScimException` |
| app-scim | 바뀜: `ScimUseCaseConfig`(새 포트 배선) |
| README | SCIM 절의 "목록 조회와 필터는 지원하지 않는다" 를 지원 범위로 교체, 테이블 재생성 안내 |

## 8. 검증

테스트 규칙은 기존과 같다 — Lombok, AssertJ, BDD(given/when/then), 한글 `@DisplayName`.

1. **단위 (connector-scim)**
   - `ScimFilter`: §4.1 의 받는 것(대소문자 무시, URN 이름, `\"` 이스케이프, `and` 여러 개)과 거절하는 것 각각.
   - `ScimQuery`: `startIndex`·`count` 경계(0, 음수, 101, 정수 아님), `sortBy`·`sortOrder`, `.search` 본문 변환.
   - `ScimAttributeProjection`: `id`·`schemas` 유지, `members` 빼기, 하위 속성, 동시 사용, 모르는 이름.
2. **저장소 (storage-dynamodb, DynamoDB Local 컨테이너)**
   - GSI1 소문자: `findUserIdsByUserName("KIM")` 이 `kim` 을 찾음, admin 접두사 검색도 대소문자 무시.
   - GSI3: 같은 `externalId` 의 직원·조직이 종류별로 갈림, 본 테이블의 최신 값을 돌려줌.
   - 페이지: 오름·내림으로 끝까지 이어 읽으면 전원이 정확히 한 번, `skip(n)` 의 위치 = 순서대로 이어 읽은 위치,
     COUNT, 책갈피 저장·읽기, 만료된 책갈피는 없는 것.
3. **E2E (app-scim, 기본 `test`)**
   - §3 의 IdP 요청을 글자 그대로. 대소문자(`Kim` 생성 → `userName eq "kim"` 으로 찾음, `kim` 생성은 409).
   - `excludedAttributes=members` 일 때 `findGroup` 이 **한 번도 불리지 않음** — 저장소를 `@MockitoSpyBean` 으로.
   - `.search`, `ListResponse` 형태, ServiceProviderConfig, §6 표의 각 줄.
4. **규모 (`@ScaleTest`)**
   - **10만 명 목록** — 저장소 쓰기로 직원 10만 명을 직접 심는다(목록은 읽기만 검증하므로 SCIM API 를 거치지 않는다).
     `startIndex=1, 101, …` 로 끝까지 가져와 `totalResults = 100,000`, 전원이 정확히 한 번. **DynamoDB 클라이언트에
     요청·아이템을 세는 인터셉터를 끼워 가져오기 전체의 읽은 아이템 수가 `2N` 근처(COUNT N + 페이지 N)임을 단정한다**
     — 다 읽고 자르기였다면 `N²/100` 이다. 무작위 100명을 `userName eq`·`externalId eq` 로 찾아 조회당 몇 건만 읽는지도.
   - **5천 명 SCIM 시나리오에 추가** — 조직 목록을 멤버 포함으로 끝까지 가져와 기대 조직도와 대조한다.
   - 소요 시간과 읽은 아이템 수를 이 문서 §11 에 기록한다.

## 9. 성능 (10만 명 기준)

| 경로 | 비용 |
|---|---|
| 필터 조회 (IdP 가 가장 많이 부름) | 인덱스 질의 1번 + GetItem 몇 번, 0~1건 |
| 조직 조회 + `excludedAttributes=members` | META 한 줄 |
| 필터 없는 목록, 책갈피 있음 | 페이지당 `count` 건 + 책갈피 PutItem 1번 |
| 필터 없는 목록, 첫 페이지 | COUNT(파티션 한 번, 약 40MB 분량, 1~2초) + `count` 건 |
| 필터 없는 목록, 책갈피 없음 | COUNT + 건너뛰기 N-1건(키만) + `count` 건 |
| Okta 가져오기 한 번 | 약 `2N` 아이템 읽기(40GB → 약 80MB) |
| 조직 목록 + 멤버 | 페이지의 조직마다 파티션 1번. 가져오기 전체는 멤버십 총수에 선형 |
| GSI3 쓰기 증폭 | `externalId` 가 있는 아이템 쓰기마다 KEYS_ONLY 인덱스 쓰기 1번 |

## 10. 왜 다른 길을 안 갔나

**필터 — RFC 문법 전체.** `co`·`sw`·`pr`·`gt`·`or`·`not`·괄호까지 받으면 인덱스를 못 타는 조건이 생기고, 10만 명을
요청마다 스캔한다. 두 IdP 는 `eq`(Entra 는 `and` 까지)만 보낸다. 파서·평가기·테스트 양이 몇 배다.

**필터 — `eq` 만.** Entra 문서가 `and` 를 쓴다고 명시한다. 한쪽 조건을 인덱스로 찾고 나머지를 몇 건 위에서
확인하는 것이라 추가 비용이 거의 없다.

**대소문자 — 지금처럼 구분.** RFC 7643 이 `userName` 을 `caseExact=false` 로 정한다. 구분하면 IdP 가 대소문자를 달리
보낼 때 "없다" 고 판단해 같은 사람을 또 만든다.

**`externalId` — 전원 스캔.** 조회 한 번에 10만 건. Entra 가 직원마다 조회하면 초기 프로비저닝이 10만 × 10만이다.
**GSI3 를 ALL 로.** 인덱스가 아이템 전체를 복제해 커지고, 인덱스가 늦으면 낡은 값을 돌려준다. KEYS_ONLY + GetItem 이
작고 항상 최신이다.

**페이지 — 요청마다 다 읽고 자르기.** 5천 명에서 처음 고른 안이다. 10만 명이면 페이지당 40MB, 가져오기 한 번에 40GB,
요청마다 메모리에 10만 건. 읽는 양이 인원의 제곱으로 는다.
**페이지 — 건너뛰기 + COUNT.** `totalResults` 때문에 어차피 전원을 세야 해서 위 안보다 싸지 않고 더 복잡하다.
**페이지 — 건수 카운터 아이템.** 매 페이지 COUNT 가 사라지지만 SCIM 생성·삭제, LDAP 전체 교체 등 모든 쓰기 경로가
카운터를 맞춰야 하고, 어긋나면 바로잡는 잡이 필요하다. 책갈피에 첫 페이지의 건수를 실으면 가져오기당 COUNT 는 한
번이라 카운터의 이득이 작다.
**페이지 — 메모리 책갈피.** 앱이 여러 대면 다음 페이지 요청이 다른 인스턴스로 가 매번 처음부터 건너뛴다. 쓰기 락을
DynamoDB 에 두는 것과 같은 이유로 DynamoDB 에 둔다.
**페이지 — RFC 9865 커서 페이징.** 표준이지만 두 IdP 모두 `startIndex`/`count` 를 보낸다.

**정렬 — 임의 속성.** 필터 없는 목록에서 인덱스 키가 아닌 정렬은 전원을 읽어 정렬해야 한다. 필터 결과에만 허용하면
같은 `sortBy` 가 필터 유무에 따라 되고 안 되는 두 규칙이 된다. **정렬 빼기** 는 표준에 맞지만 사용자가 부가 기능을
모두 넣기로 했고, 인덱스 키 정렬은 추가 비용이 없다.

**포트 — `DirectorySearchRepository` 확장.** 계약(접두사 + 커서)이 다르다(§5.3).

## 11. 결과 (구현 후 기록)

규모 테스트의 소요 시간, 10만 명 가져오기의 읽은 아이템 수, 기본 `test`·`scaleTest` 시간을 구현이 끝나면 여기에 적는다.

## 12. 이 설계가 말할 수 없는 것

- **DynamoDB Local 은 AWS 가 아니다.** 규모 테스트가 증명하는 것은 "읽는 양이 인원에 선형" 이라는 알고리즘 비용이지,
  운영 지연·처리량·파티션 한계가 아니다. COUNT 1~2초도 추정이다.
- **IdP 동작은 문서 기준이다.** 실제 테넌트 검증은 인증 슬라이드 뒤다(감사 문서 §5 와 같다).
- **인증이 없다.** 누구나 필터 없는 목록 첫 페이지를 반복 호출해 매번 COUNT(파티션 전체)를 일으킬 수 있다 — admin
  조회에서 지적된 것과 같은 증폭 경로다. 인증 슬라이드에서 함께 막힌다.
- **GSI 는 최종 일관성이다.** 막 만든 직원을 직후 수 ms 안에 `userName`·`externalId` 필터로 못 찾을 수 있다. IdP 가
  다시 생성하려 해도 `id` 중복은 강한 일관성 GetItem 으로 걸린다 — 빠지는 것은 "대소문자만 다른 `userName` 을 수 ms
  안에 연달아 생성" 뿐이다.
- **`totalResults` 는 가져오기 시작 시점의 값이다**(§4.4). 표준이 허용한다.
- **`externalId` 가 2,048바이트를 넘으면 쓰기가 실패한다** — DynamoDB 인덱스 파티션키 한계. LDAP 은 DN 이
  `externalId` 라 그 길이의 DN 이 있으면 동기화 회차가 실패한다.
- **`emails[type eq "work"].value` 필터는 받지 않는다.** Entra 에서 고유성 판단 속성을 이메일로 바꿔 설정하면 보내는
  필터다. 기본값이 아니고 우리 모델은 이메일을 하나만 가진다.
- **범위 밖 — GSI1 쏠림.** 직원 전원이 GSI1 의 한 파티션키(`USER_INDEX`)에 모여 있어, 10만 명 LDAP 전체 동기화에서 이
  인덱스의 파티션키당 쓰기 처리량 한계에 걸려 본 테이블 쓰기까지 늦춰질 수 있다. S-1 과 별개인 기존 구조 문제라 백로그에
  따로 올렸다.
