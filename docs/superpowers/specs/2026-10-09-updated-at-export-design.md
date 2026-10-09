# 변경 시각 내보내기 — SCIM 응답의 `meta.created`·`meta.lastModified`

- 날짜: 2026-10-09
- 근거: 기존 백로그 6번(`updatedAt` 내보내기). `docs/superpowers/specs/2026-09-25-gsi1-hot-partition-design.md` §2·§9 에서 "진짜 변경 시각으로 만들되 내보내기는 범위 밖" 으로 남겼다.
- 전제:
  - 운영 배포 전이라 저장 형식을 바꿔도 이관 코드를 만들지 않는다.
  - 표준이 정한 신호만 받는다.
  - 배포 하나는 LDAP 아니면 SCIM 이다.
  - 실제 규모는 직원 10만 명 이상이다.
- 뒤따르는 일: 변경 이력(감사 로그, 백로그 7). 관리 API 에 "언제 무엇이 바뀌었나" 를 보이는 일은 그 슬라이드에서 함께 설계한다.

## 1. 문제

RFC 7643 §3.1 은 리소스의 `meta` 에 다음 둘을 둔다. 둘 다 서버가 정하는 값(readOnly)이고, 기본으로 돌려준다(returned default).
- `created` — 리소스가 서비스 제공자에 추가된 시각.
- `lastModified` — 리소스가 마지막으로 바뀐 시각. 만든 뒤 한 번도 바뀌지 않았다면 `created` 와 같아야 한다(MUST).

우리 SCIM 응답의 `meta` 에는 `resourceType`·`location` 만 있다.

| 항목 | 지금 |
|---|---|
| 변경 시각 | 저장소가 직원·조직 META 가 실제로 바뀔 때만 `updatedAt` 을 찍는다(PR #26, GSI1 쏠림 설계 §3). 그러나 core 모델로 읽어 오지 않고, 응답 어디에도 나가지 않는다 |
| 생성 시각 | 없다 |
| 하위 조직 삭제 | SCIM 으로 조직을 지우면 상위 조직의 멤버 줄을 지운다. 그러나 상위 조직 META 의 `updatedAt` 은 그대로다. Group 은 `members` 를 담으므로, 내보내면 상위 조직의 `lastModified` 가 틀린 값이 된다 |

**생성 시각을 둘 자리가 문제다.**
- 저장소는 META 를 통째로 다시 쓰는 PutItem 으로 쓴다. 그래서 생성 시각을 META 에 두면, 바꿀 때마다 옛 값을 다시 실어야 남는다.
- 그런데 쓰기 경로는 저장본을 다시 읽지 않는다.
  - SCIM 쓰기는 부르는 쪽이 락 안에서 읽은 "이전 값"(도메인 값)을 넘긴다(설계 2026-10-03 §3.5, 점검 S28).
  - 전체 동기화는 저장본을 도메인 값으로 줄여서 든다(`Stored<T>`).
- 그러니 쓰는 순간에는 옛 생성 시각을 손에 쥐고 있지 않다.

## 2. 결정 (사용자 확인, 2026-10-09)

- **SCIM 응답에만 내보낸다.** app-scim 의 직원·조직 응답 `meta` 다. 관리 API 는 바꾸지 않는다.
  - 고르지 않은 안: SCIM 과 관리 API 둘 다(§9).
- **META 쓰기를 UpdateItem 으로 바꾸고, 생성 시각은 `if_not_exists` 로 DynamoDB 가 보존하게 한다.**
  - 고르지 않은 안: 도메인 모델이 시각을 들고 다니기, 생성 시각을 별도 아이템에 두기(§9).

## 3. 저장(쓰기)

### 3.1 META 를 UpdateItem 으로 쓴다

**바뀌는 곳.** 직원·조직 META 를 쓰는 곳은 셋이다.
- `saveUser`
- 전체 동기화의 직원 쓰기(`writeUser`)
- `saveGroup`·`saveGroupChange`·전체 동기화가 함께 쓰는 조직 META 쓰기(`writeMembership`)

셋 다 지금은 `putItem(stamped(item))` 이다.

**바뀐 뒤의 식.** 아이템을 만드는 코드(`userItem`·`groupMeta`)는 그대로 두고, 쓰는 방식만 UpdateItem 으로 바꾼다.
- 키(`PK`·`SK`)를 뺀 아이템의 모든 속성을 `SET` 한다.
- `updatedAt = :지금` 을 넣는다.
- `createdAt = if_not_exists(createdAt, :지금)` 을 넣는다.
  - 처음 만들 때만 들어가고, 이후로는 DynamoDB 가 보존한다.
  - 처음 만들 때 두 시각은 같은 `:지금` 이다(RFC MUST).
- 선택 속성 가운데 이번 아이템에 없는 것은 `REMOVE` 한다.
  - PutItem 의 "통째 교체" 와 같은 결과다. 예를 들어 email 을 비우면 속성이 사라진다.
  - 직원의 선택 속성: `externalId`, `userName`, `displayName`, `displayNameKey`(GSI2 정렬키), `email`, 이름 여섯(`nameFormatted`, `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix`).
  - 조직의 선택 속성: `externalId`, `displayName`.
  - 늘 있는 속성은 직원 `GSI1PK`·`GSI1SK`·`active`, 조직 `GSI1PK`·`GSI1SK` 다.
  - 이 목록은 아이템을 만드는 코드 옆 한 곳에 둔다. "아이템이 가질 수 있는 선택 속성은 모두 목록에 있다" 를 테스트로 지킨다. 목록을 빠뜨리면 옛 값이 조용히 남기 때문이다.
  - 아이템에서 속성을 빼더라도 이 목록에서는 빼지 않는다. UpdateItem 은 목록에 없는 속성을 지우지 않는다 — 옛 아이템에 남은 그 속성 때문에 비교가 늘 "다르다" 가 되어, 매 전체 동기화가 그 아이템들을 다시 쓴다(GSI1 쏠림).

**그대로인 것.** 바뀌었을 때만 쓴다는 규칙은 그대로다. 같으면 쓰지 않고, 두 시각도 그대로다.
- "같은가" 비교(`sameContent`)는 `updatedAt` 과 함께 `createdAt` 도 빼고 본다.
- 빼지 않으면 모든 저장본이 다르게 보인다. 그러면 전체 동기화가 매번 전원을 다시 쓰고, PR #26 이 없앤 쏠림이 돌아온다.

**비용.** UpdateItem 의 쓰기 용량과 GSI 쓰기는 PutItem 과 같다(아이템 크기로 센다). 다시 읽기는 없다.
- `createdAt` 은 GSI1(ALL 프로젝션)에도 실린다. 아이템마다 40바이트 남짓이다.

### 3.2 조직 삭제가 상위 조직의 변경 시각을 올린다

- **지금.** `deleteGroup` 은 상위 조직들(소속 줄로 이미 안다)의 "이 조직" 멤버 줄을 지운다. 상위 조직 META 는 건드리지 않는다.
- **바뀐 뒤.** 멤버 줄을 지운 뒤 상위 조직마다 `SET updatedAt = :지금` 을 UpdateItem 으로 보낸다.
  - 조건은 `attribute_exists(PK)` 다. 없는 조직의 META 를 만들지 않는다.
  - 조건 실패는 무시한다(그 조직은 이미 없다).
  - 순서는 지금 단계(멤버 줄 → 소속 줄 → META 맨 마지막) 안에서 멤버 줄 다음이다. 중간에 멈춰도 다시 부르면 남은 것을 마저 한다.
- **직원 삭제는 손대지 않는다.** SCIM 직원 삭제는 이미 소속 조직마다 `saveGroupChange(…, 빠진 멤버)` 로 멤버 변경을 쓴다. 그래서 그 조직 META 를 다시 쓰고 변경 시각도 움직인다(`IncrementalSyncUseCase.removeUser`).
- **LDAP 전체 동기화는 상위 조직이 남으면 추가 쓰기가 없다.** 상위 조직을 먼저 멤버 차이로 다시 쓰고(`upsertGroups`), 그 뒤 `deleteGroup` 이 찾는 소속 줄이 이미 없다.
  - 상위·하위가 함께 사라지는 경우(LDAP 하위 트리 폐지, SCIM `mode=wipe` 의 `replaceWith(빈 조직도)`)에는 조직↔조직 연결마다 조건부 UpdateItem 이 하나씩 나간다. 곧 지워질 META 를 한 번 더 쓰거나 조건 실패로 끝난다 — 무해하고 수가 적다.

### 3.3 옛 아이템

- 생성 시각이 없는 아이템은 응답에 `created` 를 싣지 않는다.
- 그 아이템이 다음에 바뀌면 그 변경 시각이 생성 시각으로 들어간다. 실제 생성 시각이 아니다.
- 운영 배포 전이라 이관하지 않는다. 테이블을 다시 만들면 생기지 않는 경우다(README).

## 4. 읽기와 SCIM 응답

### 4.1 core — 시각 묶음

- `ResourceTimes(Instant created, Instant lastModified)` — 저장본에 없으면 null 이다.
- `Timestamped<T>(T value, ResourceTimes times)` — 도메인 값과 시각을 함께 나른다.
- 도메인 레코드(`DirectoryUser`·`GroupHeader`·`DirectoryGroup`)는 바꾸지 않는다. 동등 비교, 쓰기 판단, LDAP 조직도 비교에 영향이 없다.

### 4.2 SCIM 전용 조회 포트가 시각을 함께 준다

`DirectoryQueryRepository` 는 connector-scim 만 쓴다.
- 지금 있는 여섯 읽기가 `Timestamped` 를 돌려준다.
  - 직원 목록 `listUsers` 와 조직 목록 `listGroupHeaders` 는 `Page` 의 원소가 `Timestamped` 다.
  - `findUsersByUserName`, `findUsersByExternalId`, `findGroupHeadersByDisplayName`, `findGroupHeadersByExternalId` 도 같다.
- 단건 읽기 둘을 더한다. `findUser(id)`, `findGroupHeader(id)` 이고, 강한 일관성 GetItem 이다.
  - 쓰기 직후 응답이 방금 쓴 값을 읽어야 한다. 지금 `DirectoryStateRepository` 의 같은 이름 메서드와 같은 일관성이다.
- 추가 읽기는 없다.
  - GSI1 은 ALL 프로젝션이라 목록과 userName·displayName 찾기에 두 시각이 이미 실려 온다.
  - externalId 찾기는 지금처럼 GSI3(KEYS_ONLY) → 본 테이블 GetItem 이다.

### 4.3 SCIM 핸들러 — 응답을 만드는 읽기만 바꾼다

| 리소스 | 이 포트로 바꾸는 읽기 |
|---|---|
| 직원 | GET, 쓰기 뒤 다시 읽기(POST·PUT·PATCH 의 `respond`), 저장 속성에 닿지 않는 PATCH 의 지금 모습 |
| 조직 | GET·쓰기 뒤 다시 읽기의 머리(`body`), 목록에서 멤버를 흘려 쓸 때 조직마다 다시 읽는 머리(`ScimGroupStream.list`) |

- 멤버 줄 읽기(`findMemberRefs`)는 그대로다.
- 락 안의 쓰기 판단 읽기(`DirectoryStateRepository`)도 그대로다.

### 4.4 응답 모양

- `meta` 는 `resourceType`, `created`, `lastModified`, `location` 순이다(RFC 7643 §3.1 예시 순서).
- 시각은 ISO-8601 UTC 문자열이다(`Instant.toString()`, 예: `2026-10-09T03:00:00.123456Z`). RFC 의 DateTime(xsd:dateTime)이다.
- 값이 없으면 싣지 않는다.
- `attributes`·`excludedAttributes` 가 `meta.created`·`meta.lastModified` 를 받는다(`ScimResourceType` 의 속성 경로). 지금은 모르는 이름이라 400 이다.

## 5. 문서

- README: SCIM 응답의 `meta.created`·`meta.lastModified` 와 그 뜻(무엇이 바뀌면 움직이나), 옛 아이템.
- 백로그(메모리): 6번을 해결로 표시한다.

## 6. 바뀌는 곳

| 모듈 | 무엇 |
|---|---|
| core | `ResourceTimes`, `Timestamped`, `DirectoryQueryRepository` 반환형과 단건 읽기 둘 |
| storage-dynamodb | `DynamoDbDirectoryStateRepository` — META UpdateItem 쓰기, 선택 속성 목록, `sameContent` 가 `createdAt` 도 뺌, `deleteGroup` 의 상위 조직 변경 시각. `DynamoDbDirectoryQueryRepository` — 시각을 싣는 읽기와 단건 읽기 |
| connector-scim | `ScimMeta` 두 칸, `ScimMapper`, 응답을 만드는 읽기(직원·조직 핸들러, 조직 흘려 쓰기, 목록), `ScimResourceType` 속성 경로 |
| 문서 | README |

## 7. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| 생성 | 새로 만들면 `createdAt == updatedAt` 이다(직원·조직) | storage(DynamoDB Local, 시계를 손으로 옮긴다) |
| 그대로 | 같은 값을 다시 쓰면 두 시각이 그대로이고 쓰기 요청이 0번이다 | storage |
| 변경 | 바뀌면 `createdAt` 은 그대로, `updatedAt` 만 새 시각이다. 조직은 멤버만 바뀌어도 그렇다 | storage |
| 속성 지우기 | 선택 속성을 비우면(예: email, externalId) 속성이 실제로 사라진다. GSI3 에서도 빠진다 | storage |
| 선택 속성 목록 | 모든 선택 속성을 채운 직원·조직의 아이템 키에서 늘 있는 속성을 빼면 목록과 같다 | storage |
| 전체 동기화 | 같은 규칙이다. 같은 조직도를 다시 동기화하면 쓰기 0번이다 | storage |
| 하위 조직 삭제 | 상위 조직의 `updatedAt` 이 움직이고 `createdAt` 은 그대로다. 상위 조직 META 가 없으면 만들지 않는다 | storage |
| 조회 포트 | 여섯 읽기와 단건 둘이 두 시각을 싣는다. 옛 아이템(`createdAt` 없음)은 `created` 가 null 이다 | storage |
| SCIM 응답 | 다음 경우마다 `meta.created`·`meta.lastModified` 를 본다: POST 는 둘이 같다, GET, 같은 값 PATCH 는 변경 시각이 그대로다, 바꾸는 PATCH·PUT 은 변경 시각만 움직인다, 조직 멤버 추가·제거, 하위 조직 삭제 뒤 상위 조직 GET, 목록·필터 응답, 멤버를 흘려 쓰는 응답, `attributes=meta.lastModified`, `excludedAttributes=meta.created` | connector-scim, app-scim |
| 규모 | `ReplaceWithScaleTest` 의 "같은 조직도 쓰기 0번" 이 핵심 회귀 단정이다. `createdAt` 을 비교에서 빼지 않으면 여기서 10만 번으로 깨진다. 쓰기 계측(`WriteCounter`)이 UpdateItem 도 센다 | storage |
| 전체 | 머지 전 `test`·`scaleTest` 둘 다 | 전체 |

## 8. 이 설계가 말할 수 없는 것

- **서버가 여러 대면 각자의 시계로 찍는다.** 시계가 어긋나면 변경 시각이 아주 조금 거꾸로 갈 수 있다. 쓰기는 전역 락으로 줄을 서지만, 시각은 쓰는 서버의 것이다.
- **직원의 소속 변경은 직원의 변경이 아니다(PR #26 규칙).** 우리는 User 의 `groups` 를 내보내지 않는다.
- **옛 아이템의 생성 시각은 틀릴 수 있다(§3.3).**
- **SCIM 직원 삭제 중 튜플 삭제가 일부 실패하면 그 조직의 멤버십은 남는다.** 그 조직은 바뀌지 않았으므로 변경 시각도 그대로다(지금 규칙 그대로).

## 9. 왜 다른 길을 안 갔나

- **SCIM 과 관리 API 둘 다 내보내기.** LDAP 배포에서도 보인다. 그러나 관리 API 의 변경 정보는 감사 로그(백로그 7)와 겹친다. 그 슬라이드에서 함께 정한다.
- **도메인 모델이 시각을 들고 다니기.**
  - `DirectoryUser`·`GroupHeader` 에 두 시각을 넣고, 쓸 때 "이전 값" 의 생성 시각을 다시 실어 PutItem 하는 안이다. 저장소 쓰기 방식은 그대로다.
  - 그러나 레코드 동등 비교가 시각까지 보게 된다. 그 영향이 LDAP 조직도 비교, SCIM 계산, 모든 생성자 호출부, 수많은 테스트로 번진다.
  - 시각을 빠뜨린 경로에서는 생성 시각이 조용히 사라진다.
- **생성 시각을 별도 아이템에 두기.**
  - 생성할 때 한 번만 쓰고 META 는 건드리지 않는 안이다.
  - 그러나 SCIM 응답마다 읽기가 하나 는다. GSI1 목록에는 그 값이 실리지 않아 목록 한 쪽(100명)마다 BatchGet 이 붙는다.
- **쓰기 전에 저장본을 다시 읽어 생성 시각을 옮겨 싣기.** 점검 S28 에서 일부러 없앤 다시 읽기를 되살린다.
  - 직원 PATCH 마다 GetItem 하나가 는다.
  - 전체 동기화는 저장본을 줄여 드는 이유(10만 명 메모리)와 부딪친다.

## 10. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간과 결과를 구현이 끝나면 여기에 적는다.

## 11. 범위 밖

- `meta.version`(ETag, RFC 7644 §3.14).
- `meta.lastModified` 로 거르기·정렬하기. 지금처럼 400 이다.
- 관리 API 노출(감사 로그 슬라이드).
- 변경 이력(감사 로그), 인증(마지막).
