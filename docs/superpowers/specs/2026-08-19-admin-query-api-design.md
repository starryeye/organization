# 관리자 조회 API 설계

작성일: 2026-08-19

## 1. 개요

동기화된 조직·직원 데이터를 관리자가 조회하는 읽기 전용 API. 두 가지를 답한다.

- **찾기** — 직원과 조직을 이름·계정명 접두사로 찾는다
- **왜 접근되는가** — 한 직원이 어떤 조직들에 어떤 경로로 속하는지 보여주고, **OpenFGA 의 실제 판정을 나란히** 붙인다

두 번째가 이 API 의 존재 이유다. DynamoDB 가 말하는 "있어야 할 권한"과 OpenFGA 가 말하는 "실제 권한"이 갈릴 수 있고([`follow-ups §6`](../plans/2026-08-15-follow-ups.md)), 지금 그 어긋남을 알아챌 다른 장치가 없다.

**선행 문서:** [`2026-08-14-organization-sync-design.md`](2026-08-14-organization-sync-design.md). 그 문서의 비목표 "직원/조직 조회 admin API — 다음 사이클" 이 이 설계다.

### 목표

- 직원: 계정명·표시명 접두사 검색, 직원 아이디 정확 조회
- 조직: 조직명 접두사 검색, 조직코드 정확 조회
- 직원 상세: 소속 조직과 그 상위 계층 전부 + 각 조직에 대한 `Check` 결과
- 조직 상세: 상위 계층 전부 + 직속 하위 조직·직속 소속 직원(1 depth)
- LDAP 배포·SCIM 배포 어느 쪽에서도 동작

### 비목표

- **인증/인가** — 별도 사이클. 이 API 는 열려 있다
- 부분일치·전문 검색 (접두사만)
- 하위 방향 다단 순회 (1 depth 고정)
- 쓰기 — 조회 전용. 수동 동기화/재적재는 기존 `/admin/sync` 소관
- 조직코드 접두사 검색 (§7.3)

### 전제

한 조직도는 하나의 소스로만 동기화한다(선행 설계 §1). app-ldap 과 app-scim 은 **서로 다른 테이블·서로 다른 OpenFGA store** 를 쓰는 독립 배포다. 따라서 이 API 는 "하나의 데이터를 보는 하나의 API" 가 아니라 **각 배포가 자기 데이터에 대해 갖추는 기능**이며, 공유 모듈로 만들어 두 앱이 각자 탑재한다.

---

## 2. 결정 사항 요약

| 항목 | 결정 | 근거 |
|---|---|---|
| 배치 | 공유 모듈 `admin-api`, 두 앱 모두 포함 | 두 배포가 독립이라 각자 자기 데이터를 조회해야 한다. 새 인스턴스를 늘리지 않는다 |
| 매칭 | 접두사만 | DynamoDB 정렬키는 정확 일치와 `begins_with` 만 가능. 부분일치는 Scan 또는 검색엔진이 필요 |
| 읽기 전략 | 요청 시 계산, 캐시 없음 | 계층 깊이 4~6단이라 요청당 한 자릿수 왕복. 무효화 문제를 만들지 않는다 |
| 인덱스 | GSI2 하나 추가 (직원 표시명) | 나머지는 기존 PK/GSI1 로 충분 |
| 권한 근거 | DynamoDB 파생값 + OpenFGA `Check` 병기 | `Check` 는 허용된다(선행 설계 §2 정정 이력). 둘을 나란히 보여야 어긋남이 드러난다 |
| `Check` 실패 | 해당 칸만 `null`, 요청은 성공 | 조회 API 가 인가 서버 장애에 끌려 내려가면 안 된다 |
| 파라미터 이름 | 모델 필드명 그대로 (`userName`, `displayName`) | 새 어휘를 만들면 저장된 필드와의 대응을 매번 번역해야 한다 |
| 페이징 | 커서(불투명 문자열) | DynamoDB 는 offset 을 지원하지 않는다 |
| 컨트롤러 | `@RestController` | 기존 `AdminSyncController` 와 같은 표면 |
| 오류 | `ResponseStatusException` | 〃 |

---

## 3. 식별자 세 개의 의미

혼동이 잦은 지점이라 먼저 못박는다.

| API 이름 | 도메인 필드 | 뜻 | LDAP 에서 | SCIM 에서 |
|---|---|---|---|---|
| `employeeId` | `DirectoryUser.id` | **튜플에 들어가는 안정 식별자.** 정규화됨 | `uid` 정규화 | `userName` 정규화 |
| `userName` | `DirectoryUser.userName` | 계정명 (원본) | **`id` 와 같은 값** | `User.userName` 원본 |
| `displayName` | `DirectoryUser.displayName` | 사람이 읽는 이름 | `user-name-attribute` → `cn` → `uid` | `displayName` → `name.formatted` → `userName` |

예: 이름 `홍길동`, 계정 `gd.hong` →
`employeeId=gd.hong`, `userName=gd.hong`, `displayName=홍길동`, 튜플은 `user:gd.hong`.

**LDAP 배포에서는 `employeeId` 와 `userName` 이 항상 같은 값이다.** 두 전략 모두 같은 값을 두 자리에 넣는다. SCIM 에서만 갈릴 수 있고, 그것도 정규화가 실제로 개입했을 때뿐이다(`"gd hong"` → `id="gd_hong"`, `userName="gd hong"`).

**두 값이 갈라져 보이면 그 자체가 정보다** — 정규화가 개입했다는 뜻이므로 응답에 셋 다 싣는다.

### 접두사 검색이 뒤지는 값에 주의

`?userName=` 은 **GSI1SK 에 실린 원본 계정명**을 뒤진다. 경로의 `{employeeId}` 는 **PK 의 정규화된 id** 를 찾는다. 정규화가 개입한 직원은 두 값이 다르므로 같은 이름을 붙이면 안 된다. 그래서 파라미터를 `?employeeId=` 로 통일하지 않는다.

`employeeId` 접두사 검색은 제공하지 않는다. 제공하려면 GSI1SK 를 정규화된 id 로 바꿔야 하는데, 기존 아이템 전체를 다시 쓰는 마이그레이션이고 `GSI1SK=userName` 은 SCIM 의 `userName` 중복 검사(`findUserIdsByUserName`)가 이미 쓰고 있다.

---

## 4. 모듈 구조

```
admin-api (신규)                  조회 엔드포인트. core + webflux 에만 의존
  └─ core
       ├─ AdminQueryUseCase              계층 순회 + Check 병기
       ├─ DirectorySearchRepository      (신규 포트) 접두사 검색·페이징
       ├─ DirectoryStateRepository       (기존) findUser / findGroup 재사용
       └─ RelationTupleChecker           (신규 포트) Check 전용
  storage-dynamodb   DirectorySearchRepository 구현 + GSI2 추가
  authz-openfga      RelationTupleChecker 구현
```

의존 방향은 기존과 같다: `app-*` → 어댑터 → `core`. `admin-api` 는 `storage-dynamodb` 도 `authz-openfga` 도 모른다.

등록은 기존 커넥터와 같은 패턴 — `AdminQueryConfig` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. 두 앱은 `implementation project(':admin-api')` 한 줄만 추가한다.

### 포트를 둘로 나누는 이유

**`RelationTupleChecker` 를 `RelationTupleWriter` 에 얹지 않는다.** 얹으면 동기화 경로가 의도치 않게 `Check` 에 의존하기 쉬워지고, "쓰기 어댑터" 라는 이름이 거짓이 된다.

**`DirectorySearchRepository` 를 `DirectoryStateRepository` 에 얹지 않는다.** 그쪽은 이미 메서드 9개에 동기화 쓰기 경로의 심장이다. 조회 관심사를 섞으면 `admin-api` 가 쓰기 메서드까지 전부 보게 된다.

### 새 타입

```java
public record UserSummary(String employeeId, String userName,
                          String displayName, boolean active) {}
public record GroupSummary(String orgCode, String displayName) {}
public record Page<T>(List<T> items, String nextCursor) {}   // nextCursor == null 이면 끝
```

`DirectoryGroup` 을 검색 결과로 그대로 돌려주면 `members` 가 딸려와 검색 한 번에 조직마다 멤버 전체를 읽는다. 그래서 가벼운 타입을 쓴다.

```java
public interface DirectorySearchRepository {
    Mono<Page<UserSummary>>  searchUsersByUserName(String prefix, String cursor, int limit);
    Mono<Page<UserSummary>>  searchUsersByDisplayName(String prefix, String cursor, int limit);
    Mono<Page<GroupSummary>> searchGroupsByDisplayName(String prefix, String cursor, int limit);
}

public interface RelationTupleChecker {
    Mono<Boolean> check(RelationTuple tuple);
}
```

메서드를 `field` 파라미터 하나로 합치지 않는다. 합치면 저장소 구현이 인덱스 선택 분기를 안고 가고, 호출부에서 어떤 인덱스를 타는지 보이지 않는다.

---

## 5. 인덱스

| 검색 | 인덱스 | 쿼리 |
|---|---|---|
| `employeeId` 정확 | 없음 | `GetItem(PK=USER#<id>, SK=META)` |
| `orgCode` 정확 | 없음 | `Query(PK=GROUP#<code>)` |
| `userName` 접두사 | GSI1 `USER_INDEX` (기존) | `begins_with(GSI1SK, prefix)` |
| 조직 `displayName` 접두사 | GSI1 `GROUP_INDEX` (기존) | `begins_with(GSI1SK, prefix)` |
| **직원 `displayName` 접두사** | **GSI2 (신규)** | `begins_with(GSI2SK, prefix)` |

### GSI2

- **키 속성을 새로 만들지 않는다.** 파티션키는 기존 `GSI1PK` 를 그대로 쓰고, 정렬키는 아이템 속성 `displayName` 그 자체다
- 프로젝션 `INCLUDE(userName, active)` — `displayName` 은 이제 키 속성이라 여기 넣으면 `ValidationException` 이다

**전용 속성(`GSI2PK`/`GSI2SK`)을 쓰면 안 되는 이유.** DynamoDB 는 인덱스의 키 속성을 **전부** 가진 아이템만 인덱스에 투영한다. 전용 파티션키를 새로 만들면 그 속성은 이 기능이 배포된 뒤에 쓰인 아이템에만 있으므로, **배포 이전에 저장된 직원은 하나도 인덱싱되지 않는다.** `UpdateTable` 의 백필도 그들을 건너뛴다.

그 결과가 배포마다 다르고 한쪽은 영구적이다. app-ldap 은 `FullSyncUseCase` 가 매 실행마다 `replaceWith` 로 전원을 다시 써서 첫 스케줄 동기화 뒤 스스로 낫는다. 반면 **app-scim 에는 대량 쓰기 경로가 아예 없다** — `IncrementalSyncUseCase` 와 `SnapshotArchiveUseCase` 뿐이고 후자는 읽기만 한다. 즉 SCIM 배포에서는 표시명 검색이 기존 직원 전체에 대해 영구히 비어 나오고, IdP 가 개별 직원을 건드릴 때만 하나씩 채워진다. 아이디·계정명 검색은 정상 동작하므로 "그 직원은 표시명이 없나 보다" 로 보여 진단도 어렵다.

기존 속성 위에 얹으면 이 문제가 사라진다 — 모든 아이템이 이미 `GSI1PK` 를 갖고 있어 `UpdateTable` 백필이 그대로 덮는다. **마이그레이션이 필요 없다는 말은 이 키 설계에서만 참이다.**

**대가: 조직 아이템도 GSI2 에 실린다.** 조직 META 의 `GSI1PK` 는 `GROUP_INDEX` 이고 그쪽도 `displayName` 을 가지므로 함께 인덱싱된다. 직원 검색은 파티션 `USER_INDEX` 만 읽으므로 섞이지 않는다(테스트가 이름 접두사를 겹치게 해서 못박는다). 조직당 인덱스 쓰기 하나가 늘 뿐이다.

**`displayName` 이 없는 직원은 GSI2 에 실리지 않는다.** 정렬키 속성이 없는 아이템은 인덱스에 들어가지 않는다 — 위 규칙의 같은 면이다. `saveUser` 가 `putIfPresent` 로 쓰므로 이름 없는 직원은 표시명 검색에 안 잡힌다. 의도한 동작이며, 계정명·아이디로는 여전히 찾힌다.

프로젝션을 `KEYS_ONLY` 로 줄이면 결과 20건마다 `GetItem` 20번이 붙어 오히려 손해다. `ALL` 은 검색 결과 한 줄을 그리는 데 필요 없는 속성까지 복제한다.

> **정정 이력 (2026-08-20).** 이 절은 원래 `GSI2PK = USER_DISPLAY_NAME_INDEX` 라는 전용 상수 속성을 지정하면서 동시에 "마이그레이션은 없다 — DynamoDB 가 백필한다" 고 적고 있었다. 그 둘은 양립할 수 없다. 전체 브랜치 리뷰가 잡아냈고, 태스크별 리뷰 6번이 전부 통과시킨 이유는 구현이 계획을 충실히 따랐고 계획이 이 스펙을 충실히 따랐기 때문이다.

---

## 6. 페이징

- 커서는 DynamoDB `LastEvaluatedKey` 를 base64 로 감싼 불투명 문자열
- `limit` 기본 20, 최대 100
- 정렬은 정렬키 순서 그대로 (가나다순). 관련도 순위 개념은 없다 — 접두사 검색이라 성립하지 않는다
- 커서에 원본 키가 노출되지만 인증이 없는 API 라 어차피 데이터가 열려 있다. 암호화는 인증 사이클에서 함께 다룬다

---

## 7. API

### 7.1 검색

```
GET /admin/employees?userName={prefix}&cursor=&limit=
GET /admin/employees?displayName={prefix}&cursor=&limit=
GET /admin/organizations?displayName={prefix}&cursor=&limit=
```

```json
{ "items": [ { "employeeId": "gd.hong", "userName": "gd.hong",
               "displayName": "홍길동", "active": true } ],
  "nextCursor": "eyJQSyI6..." }
```

`userName` 과 `displayName` 을 동시에 주면 400 — 어느 인덱스를 탈지 모호하다.
둘 다 없으면 400 — 전체 열거는 지원하지 않는다(§9).

### 7.2 직원 상세

```
GET /admin/employees/{employeeId}
```

```json
{
  "employeeId": "gd.hong", "userName": "gd.hong", "displayName": "홍길동",
  "email": "gd.hong@example.com", "active": true,
  "paths": [
    { "orgCode": "DEV002", "displayName": "백엔드팀", "via": "direct",
      "shouldHaveAccess": true, "openFgaCheck": true },
    { "orgCode": "DEV001", "displayName": "플랫폼개발본부", "via": "rollup",
      "shouldHaveAccess": true, "openFgaCheck": true },
    { "orgCode": "ROOT", "displayName": "전사", "via": "rollup",
      "shouldHaveAccess": true, "openFgaCheck": false }
  ]
}
```

- 직속 소속은 `findGroupIdsContaining(MemberRef.user(id))` 로 찾는다
- 상위 계층은 `findGroupIdsContaining(MemberRef.group(code))` 를 반복해 **끝까지** 올라간다
- `via` 는 `direct`(직속) 또는 `rollup`(상위 계층)

**빈 결과와 끊어진 참조.** 어느 조직에도 속하지 않은 직원은 `paths: []` 를 돌려준다 — 404 가 아니다. 직원은 존재하기 때문이다. 조직이 멤버로 참조하는데 그 조직 레코드가 없는 경우(동기화 도중 흔하다)는 그 항목을 건너뛰고 경고 로그를 남긴다.

**순회에 상한을 둔다.** `paths` 는 최대 200개까지 만들고, 그 이상이면 자른 뒤 응답에 `"truncated": true` 를 단다. 정상 조직도에서 한 직원의 경로가 200개를 넘을 일은 없다 — 넘는다면 계층이 비정상이거나 버그이므로, 상한 없이 저장소를 훑는 대신 그 사실을 드러낸다. (같은 이유로 `IncrementalSyncUseCase` 의 순환 검사에도 상한이 있다.)

### 7.3 조직 상세

```
GET /admin/organizations/{orgCode}
GET /admin/organizations/{orgCode}/members?cursor=&limit=
```

```json
{
  "orgCode": "DEV002", "displayName": "백엔드팀", "externalId": "...",
  "ancestors": [ { "orgCode": "DEV001", "displayName": "플랫폼개발본부" },
                 { "orgCode": "ROOT", "displayName": "전사" } ],
  "childOrganizations": [ { "orgCode": "DEV003", "displayName": "인프라파트" } ],
  "members": { "items": [ { "employeeId": "gd.hong", "displayName": "홍길동",
                            "active": true, "openFgaCheck": true } ],
               "nextCursor": null }
}
```

하위 조직은 보통 수십 개라 통째로 싣고, 소속 직원은 **첫 페이지(기본 20건)** 만 싣는다. 나머지는 `/members` 로 넘긴다. 상세 응답에서는 `limit` 을 받지 않는다 — 크기를 조절하려면 `/members` 를 쓴다.

조직에 소속 직원이 없으면 `members.items` 는 빈 배열이고, 하위 조직이 없으면 `childOrganizations` 도 빈 배열이다. `ancestors` 는 최상위 조직이면 빈 배열이다.

**조직코드 접두사 검색은 없다.** 조직의 GSI1SK 는 `displayName` 이라 조직코드로는 정확 일치만 된다. 직원이 `userName` 접두사 검색을 갖는 것과 비대칭인데, 그건 직원의 GSI1SK 가 마침 `userName` 이라 공짜로 얻어진 것이다. 조직코드는 보통 정확히 알거나 이름으로 찾는 값이라 인덱스를 더 파지 않는다.

---

## 8. 권한 판정

### 8.1 `shouldHaveAccess` — DynamoDB 파생값

```
shouldHaveAccess(직원, 조직) = 직원.active AND (직속 소속 OR 그 조직이 직속 소속의 조상)
```

**`active` 를 먼저 보는 것이 핵심이다.** 비활성 직원은 튜플을 만들지 않으므로(선행 설계 §10.2) `Check` 가 전부 false 로 나온다. `active` 를 반영하지 않으면 퇴사자 화면이 온통 "어긋남"으로 도배돼 화면이 쓸모없어진다.

### 8.2 `openFgaCheck` — 실제 판정

`Check(user:<employeeId>, member, group:<orgCode>)` 결과를 그대로 싣는다.

`member` 는 롤업된 관계이므로(`direct_member or member from child`) 상위 조직에 대해서도 한 번의 `Check` 로 답이 나온다. 롤업 튜플을 우리가 구성할 필요가 없다.

**호출은 페이지 단위로만 한다.** 조직 상세의 소속 직원 목록은 한 페이지(기본 20건)에 대해서만 `Check` 를 부르고, 동시성 제한을 걸어 병렬로 낸다. 조직 전체 인원에 대해 부르지 않는다 — "하위 1 depth" 제약이 여기서 값을 한다.

### 8.3 순환

상위 순회에 방문 집합을 둔다(무한 루프 방지로 어차피 필요). 이미 본 조직에 다시 닿으면 해당 항목에 `"cycle": true` 를 단다.

저장된 계층에 순환이 있으면 `TupleMapper` 가 간선 하나를 버리므로 파생값과 실제가 갈린다. 그때 "왜 다르지"가 아니라 "순환이 있구나"로 읽히게 만든다. 관리 도구에서 이것은 결함이 아니라 기능이다.

### 8.4 `Check` 실패

`Check` 가 실패하면 요청을 500 으로 만들지 않고 해당 칸만 `"openFgaCheck": null` 로 내보낸다.

조회 API 가 인가 서버 장애에 같이 끌려 내려가면 안 되고, 장애 중에도 DynamoDB 쪽 사실은 볼 수 있어야 한다. 파생값은 그대로 유효하다.

---

## 9. 오류

`ResponseStatusException` 을 쓴다. `AdminSyncController` 가 이미 그렇게 하고 있어 관리 API 표면 전체가 한 형식이 된다. SCIM Error 스키마는 쓰지 않는다 — 그것은 IdP 와의 계약이다.

| 상황 | 응답 |
|---|---|
| 없는 `employeeId` / `orgCode` | 404 |
| `userName` 과 `displayName` 동시 지정 | 400 |
| 검색 파라미터 없음 | 400 |
| `limit` 이 1~100 밖 | 400 |
| 손상된 `cursor` | 400 |
| 저장소 장애 | 500 |
| `Check` 실패 | 500 아님 — §8.4 |

**검색 파라미터가 없으면 400** 인 것이 중요하다. 빈 접두사를 허용하면 전 직원 열거가 되고, 그것은 GSI 파티션 하나를 통째로 훑는 요청이다. 관리 화면이 실수로 빈 검색을 날려 테이블을 긁는 사고가 흔하므로 입구에서 막는다.

---

## 10. 관측성

`SyncMetrics` 가 쓰는 `MeterRegistry` 패턴을 따라 `AdminQueryMetrics` 를 둔다.

- 엔드포인트별 지연
- `Check` 호출 수 / 실패율 — `openFgaCheck: null` 비율은 OpenFGA 건강 신호
- **`authz_drift_detected`** — `shouldHaveAccess != openFgaCheck` 인 건수

마지막 항목은 부수 효과로 얻는 드리프트 감지다. [`follow-ups §6`](../plans/2026-08-15-follow-ups.md) 에서 별도 감지 장치를 두지 않기로 했는데, 이 API 를 쓸 때마다 조회한 범위에 한해 표본 검증이 일어난다. 카운터가 0 이 아니면 그것이 수동 재적재를 실행할 근거가 된다.

---

## 11. 테스트

| 층 | 대상 |
|---|---|
| `core` 단위 | 상위 계층 순회(순환 포함), `shouldHaveAccess` 규칙 — 특히 **비활성 직원은 전부 false** |
| `storage-dynamodb` | Testcontainers. GSI2 접두사 검색, `displayName` 없는 직원이 인덱스에서 빠지는 것, 커서 페이징 왕복 |
| `admin-api` | `WebTestClient` + fake 포트. 파라미터 검증 400 들, `Check` 실패가 `null` 로 흐르는지 |
| E2E | **app-scim 에만** 전체 컨테이너. app-ldap 에는 빈 등록·라우팅 스모크만 |

E2E 를 한 앱에만 두는 이유는 `admin-api` 가 두 앱에서 같은 코드를 타고 app-scim 에 이미 컨테이너 E2E 인프라가 있어서다. app-ldap 에서 확인할 유일한 것은 공유 모듈이 자동설정으로 잡히는지다.

**드리프트를 실제로 만들어 검증한다.** E2E 에서 SCIM 으로 직원을 만든 뒤 OpenFGA 에서 튜플을 직접 지우고(테스트 코드는 SDK 직접 호출 가능), 조회 시 `shouldHaveAccess: true` / `openFgaCheck: false` 로 나오는지 단언한다. 이 API 의 존재 이유 중 하나이므로 못박아 둔다.

테스트 규약은 기존과 같다 — AssertJ, `// given` / `// when` / `// then`, `@DisplayName` 에 한글.

---

## 12. 이 설계가 다루지 않는 것

- **인증** — 이 API 는 열려 있다. `/admin/sync` 도 마찬가지이며, 두 표면을 함께 보호하는 것이 인증 사이클의 범위다
- 부분일치·전문 검색 — 검색엔진 도입이 필요하다
- 하위 다단 순회 — 큰 본부에서 응답이 수천 건이 된다
- 조직코드 접두사 검색 — §7.3
- `employeeId` 접두사 검색 — §3
- 조직도 전체 트리 반환 — 열거에 가까워 이 API 의 성격과 맞지 않는다
- SCIM 쓰기 경로의 동시성 — [`follow-ups §6`](../plans/2026-08-15-follow-ups.md) 의 미룬 결정. 이 API 는 그 결함을 **감지**할 뿐 고치지 않는다
