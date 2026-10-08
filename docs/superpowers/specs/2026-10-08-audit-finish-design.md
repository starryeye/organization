# 점검 ⑥-3 마무리 — 직원 표시명 검색은 소문자 키로, 관리 API 검색 커서 위조는 400, 조직 상세 이름 읽기는 병렬로

- 날짜: 2026-10-08
- 근거:
  - `docs/superpowers/specs/2026-09-28-full-audit.md` §7 권고 ⑥ 의 사소 셋이다. ⑥-1·⑥-2 에서 빠졌다.
    - S16 앞쪽: 위조 커서가 500 이다.
    - S19: 표시명 검색이 대소문자를 가린다.
    - S27: 차례로 읽는다.
  - `docs/superpowers/specs/2026-10-06-read-paths-design.md` §11 의 "다른 관리 API 검색 커서의 위조 키 검사".
  - `docs/superpowers/specs/2026-10-07-write-paths-design.md` §10 의 남은 사소 "README 락 절 (아래 SCIM 절)".
- 전제:
  - 실제 규모는 10만 명 이상이다.
  - 운영 배포 전이다. 데이터를 초기화할 수 있으므로 이관 코드를 만들지 않는다.
  - 표준이 정한 신호만 받는다.
  - 배포 하나는 LDAP 아니면 SCIM 이다.

## 1. 문제

| 항목 | 지금 | 결과 |
|---|---|---|
| S19 | 관리자 검색 `?displayName=` 은 GSI2 를 쓴다. GSI2 의 정렬키가 `displayName` 속성 그 자체다(`Keys.GSI2SK`). `userName`·조직명 검색은 소문자 키(`Keys.indexKey`)로 찾는다 | `Kim` 으로는 `kim` 을 못 찾는다. 관리자 검색 셋 가운데 이것만 동작이 달라 헷갈린다. 조직 META 도 `displayName` 을 가져 GSI2 에 함께 실린다. 조회는 파티션으로 갈리지만 쓰기가 는다 |
| S16 앞쪽 | 관리 API 검색 셋(직원 `userName`·직원 `displayName`·조직명)의 커서는 검색 범위(인덱스/파티션)만 맞으면 시작 키를 그대로 DynamoDB 에 넘긴다(`DynamoDbDirectorySearchRepository.query`) | 위조한 시작 키나, 검색어를 바꾼 채 이전 커서를 다시 보낸 요청은 DynamoDB `ValidationException` 이 되어 500 이다. 멤버 목록 커서는 ⑥-1 에서 400 으로 고쳤다 |
| S27 | 관리 API 조직 상세는 상위 조직(`expandParents`)과 하위 조직(`childrenOf`)의 이름표를 GetItem 으로 하나씩 차례로 읽는다(`concatMap`) | 하위 조직이 200개면 GetItem 200번이 차례다. 같은 파일의 다른 읽기는 이미 `flatMapSequential`(동시 8)이다 |
| S27(아카이빙) | 점검 문서는 "아카이빙의 BatchCheck 약 2,200번이 차례" 라고 적었다 | ③-1 에서 `openfga.request-concurrency`(기본 4)로 이미 병렬이 됐다(`OpenFgaRelationTupleChecker.existing`). 표시만 남았다 |
| README | 락 절의 "(아래 SCIM 절)" 은 실제로는 위쪽 SCIM 절(직원 PATCH 문단)을 가리킨다 | 독자가 반대쪽을 찾는다 |

## 2. 결정 (사용자 확인, 2026-10-08)

- **S19 — 소문자 키를 따로 둔다.**
  - 직원 META 에 `displayNameKey` 를 쓰고 GSI2 의 정렬키를 그것으로 바꾼다.
  - 기존 테이블은 다시 만든다. 옛 모양의 테이블이면 기동을 멈춘다.
  - 고르지 않은 안은 §9 에 적는다: 문서만 고치기, 표시명 검색 없애기.
- **S16 앞쪽** — 검색 커서의 시작 키를 검사해 400 으로 한다. ⑥-1 멤버 목록과 같은 방식이다.
- **S27** — 상위·하위 조직 이름표를 순서를 지키는 병렬 읽기로 바꾼다.

## 3. S19 — 직원 표시명 검색을 소문자 키로

### 3.1 저장

- 직원 META 를 쓸 때 `displayNameKey = Keys.indexKey(displayName)` 를 함께 쓴다.
  - 같은 함수가 `userName`·조직명 검색의 키를 만든다.
  - 표시명이 없으면 쓰지 않는다. 표시명 없는 직원이 표시명 검색에 안 잡히는 성질은 그대로다.
- 조직 META 에는 쓰지 않는다. 그래서 조직은 GSI2 에 실리지 않는다.
- 이 속성은 `displayName` 에서 나오므로, META 를 저장본과 비교해 바뀐 것만 쓰는 규칙(GSI1 쏠림 설계)과 함께 움직인다. 표시명이 그대로면 이 속성도 그대로다.
- **구현 중 정한 것:** 표시명이 빈 직원은 저장은 되고 `displayNameKey` 는 쓰지 않는다 — DynamoDB 는 인덱스 키에 빈 문자열을 받지 않는다(빈 접두사 검색도 거절한다).

### 3.2 인덱스

- `Keys.GSI2SK` 를 `"displayNameKey"` 로 바꾼다. 파티션키는 지금처럼 `GSI1PK` 다.
- 프로젝션(INCLUDE)에 `displayName` 을 더한다.
  - 지금은 `displayName` 이 키라서 자동으로 실렸다. 이제 키가 아니므로 명시하지 않으면 검색 결과의 표시명 칸이 조용히 빈다.
  - 프로젝션은 `userName`, `displayName`, `active` 가 된다.
- `Keys` 의 GSI2 자바독을 새 구조에 맞게 고친다.
  - 지금 자바독은 "자기만의 키 속성을 만들지 않는다 — 백필 때문" 이다.
  - 새 자바독은 "운영 배포 전이라 테이블을 다시 만든다, 옛 모양이면 기동을 멈춘다" 로 바꾼다.

### 3.3 검색

- `searchUsersByDisplayName` 도 접두사를 `Keys.indexKey(prefix)` 로 바꿔서 묻는다.
- 포트 자바독의 "대소문자를 가린다" 를 "가리지 않는다(다른 두 검색과 같다)" 로 고친다.

### 3.4 옛 테이블

- `TableInitializer.addMissingIndex`(GSI2 가 없으면 더한다)를 없앤다. 그 자리에 GSI2 모양 검사를 둔다.
  - GSI2 가 없거나 정렬키가 `displayNameKey` 가 아니면 기동을 멈춘다. 예외 메시지는 "테이블 '<이름>' 의 인덱스 GSI2 가 이 버전과 다르다(정렬키 <지금 값>) — 테이블을 다시 만들어야 한다" 다.
  - 없애는 이유: 새 키 속성은 옛 아이템에 없다. 그래서 인덱스를 더해도 DynamoDB 백필이 옛 직원을 싣지 못하고, 표시명 검색이 조용히 빈다. 지금 코드가 그 인덱스를 더해 주는 것은 옛 키(`displayName`)일 때만 맞았다.
  - 옛 GSI2 가 그대로 있으면 표시명 검색만 실행 중에 `ValidationException`(500)이 된다. 기동 실패가 원인을 바로 보인다.
- 검사는 `create-table-on-startup` 이 켜졌을 때만 돈다. 지금 `ensureTable` 과 같다.
- README 에 "기존 테이블은 다시 만들어야 한다" 를 적는다.

## 4. S16 앞쪽 — 관리 API 검색 커서 위조는 400

- `DynamoDbDirectorySearchRepository.query` 가 커서에서 꺼낸 시작 키를 검사한다. 하나라도 어긋나면 `IllegalArgumentException` 이고, admin-api 의 기존 `onErrorMap` 이 400 으로 바꾼다. 검사하는 것:
  1. 키 속성이 정확히 넷이다: 본 테이블의 `PK`·`SK`, 그 인덱스의 파티션키·정렬키.
  2. 인덱스 파티션키가 이 검색의 파티션(`USER_INDEX`/`GROUP_INDEX`)과 같다.
  3. 인덱스 정렬키가 이번 요청의 접두사로 시작하고, 1024바이트(UTF-8)를 넘지 않는다.
     - 검색어를 바꾼 채 이전 커서를 다시 보낸 요청도 여기서 400 이 된다.
  4. `PK` 가 그 종류의 접두(`USER#`/`GROUP#`)로 시작하고, `SK` 가 `META` 다.
- 값이 문자열이 아니면 `Cursor.decode` 가 이미 거절한다(⑥-1).
- 대상이 아닌 것: SCIM 목록의 커서. 서버가 보관하는 책갈피(`PageBookmark`)의 위치라 클라이언트가 볼 수 없다.

## 5. S27 — 조직 상세의 이름표 읽기를 병렬로

- `AdminQueryUseCase` 의 `expandParents` 와 `childrenOf` 에서 `concatMap(this::loadGroupOrEmpty)` 를 `flatMapSequential(this::loadGroupOrEmpty, LOAD_CONCURRENCY)` 로 바꾼다.
  - 같은 파일의 다른 읽기와 같고, `LOAD_CONCURRENCY` 는 8 이다.
- 순서는 그대로다. `expandParents` 뒤의 `acceptParent`(방문 집합·상한)는 결과를 하나씩 차례로 받으므로 그대로 안전하다.
- 효과: 하위 조직 200개면 GetItem 200번이 차례이던 것이 약 25번의 왕복이다.
- 아카이빙의 BatchCheck 는 이미 병렬이다. 점검 문서에 표시만 한다.
- **구현 중 정한 것:** 순서 고정은 앞쪽 조직이 더 느린 지연으로 시험한다 — 지연이 같으면 `flatMap` 도 같은 순서를 내 판별하지 못한다.

## 6. 문서

- README 에 다음을 적는다.
  - 관리자 표시명 검색이 대소문자를 가리지 않는다.
  - 기존 테이블은 다시 만들어야 한다. GSI2 가 옛 모양이면 기동이 멈춘다.
  - 위조하거나 검색어를 바꾼 검색 커서는 400 이다.
  - 락 절의 "(아래 SCIM 절)" 을 "(위 SCIM 절)" 로 고친다.
- 점검 문서에 다음을 해결로 표시한다.
  - S16: 앞쪽까지 전부.
  - S19.
  - S27: 아카이빙은 ③-1 에서 해결, 조직 상세는 ⑥-3 에서 해결.

## 7. 바뀌는 곳

| 모듈 | 무엇 |
|---|---|
| storage-dynamodb | `Keys`: `GSI2SK = "displayNameKey"`, 자바독. `DynamoDbDirectoryStateRepository`: 직원 META 에 `displayNameKey` 를 쓴다. `TableInitializer`: GSI2 프로젝션에 `displayName` 을 넣고, `addMissingIndex` 대신 모양 검사로 기동을 멈춘다. `DynamoDbDirectorySearchRepository`: 표시명 접두사를 소문자로 묻고, 검색 커서 시작 키를 검사한다 |
| core | 포트 `DirectorySearchRepository` 자바독. `AdminQueryUseCase` 의 이름표 읽기 둘. 가짜 검색 저장소(`FakeSearchRepository`)의 표시명 검색이 대소문자를 가리지 않게 한다 |
| admin-api | 테스트만 — 검색 커서 위조가 HTTP 400 이다 |
| 문서 | README, 점검 문서 |

## 8. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| S19 검색 | `"KIM"`·`"kim"` 으로 `"Kim Chulsoo"` 를 찾는다. 결과 줄에 표시명·`userName`·`active` 가 실린다(프로젝션). 표시명이 없는 직원은 안 잡힌다 | storage (DynamoDB Local) |
| S19 저장 | 직원 META 에 `displayNameKey` 가 소문자로 있다. 조직 META 에는 없다. 표시명을 바꾸면 따라 바뀐다 | storage |
| 옛 테이블 | 새 테이블의 GSI2 정렬키가 `displayNameKey` 이고 프로젝션에 `displayName` 이 있다. GSI2 가 옛 키이거나 없는 테이블이면 `ensureTable` 이 메시지와 함께 실패한다(기존 "GSI2 를 보강한다" 테스트를 바꾼다) | storage `TableInitializerTest` |
| S16 | 검색 셋 각각에서 다음이 `IllegalArgumentException` 이다: 다른 파티션, 키가 모자람·남음, 다른 접두사(검색어 바꾼 재사용), 1024바이트 초과, 잘못된 `PK` 접두·`SK`. 정상 다음 쪽은 그대로 이어진다 | storage |
| S16 HTTP | 검색 커서 위조가 400 이다 | admin-api |
| S27 | 가짜 검색 저장소의 이름표 읽기에 지연을 주고 가상 시간으로 잰다. 하위 조직 20개·상위 조직 여럿이 차례 시간보다 짧게 끝나고, 순서가 그대로다 | core |
| 전체 | 머지 전 `test`·`scaleTest` 둘 다 | 전체 |

## 9. 왜 다른 길을 안 갔나

- **S19 문서만 고치기.** 테이블은 그대로지만 관리자 검색 셋 가운데 하나만 대소문자를 가리는 불일치가 남는다.
- **S19 표시명 검색 없애기.** 인덱스와 쓰기 증폭이 사라지지만, 관리자가 이름으로 직원을 찾을 길도 사라진다.
- **옛 설계(GSI2 가 기존 속성을 키로 쓴다)를 지키며 고치기.**
  - 옛 설계는 "새 키 속성은 백필이 옛 아이템을 싣지 못한다" 를 피하려고 `displayName` 을 그대로 키로 썼다.
  - 그 걱정은 운영 데이터가 있을 때의 것이다. 운영 배포 전이라 테이블을 다시 만들면 된다. 옛 모양이면 기동을 멈춰 조용히 비는 검색을 막는다.
- **옛 GSI2 를 지우고 새로 만드는 이관.** UpdateTable 로 인덱스를 지우고 새로 만들어도 옛 아이템에는 `displayNameKey` 가 없어 싣지 못한다. 전원을 다시 쓰는 잡이 따로 있어야 한다. 이관 코드를 만들지 않는다는 전제와 맞지 않는다.

## 10. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간과 결과를 구현이 끝나면 여기에 적는다.

## 11. 이 설계가 말할 수 없는 것

- **대소문자 접기는 `Keys.indexKey`(`toLowerCase(Locale.ROOT)`)의 규칙을 따른다.** 한국어 표시명에는 영향이 없다. 언어마다 다른 대소문자 규칙(터키어 i 등)은 다루지 않는다. `userName`·조직명 검색과 같은 한계다.
- **접두사 검색만이다.** 부분 일치는 여전히 범위 밖이다(Scan 이거나 검색엔진).
- **병렬 읽기의 효과는 왕복 수로 셌다.** 실제 AWS 지연에서 재지 않았다.

## 12. 범위 밖

- ①②③ 이 미뤄 둔 사소 다섯: S13·S15·S17·S18·S26.
- 기존 백로그: 튜플 스냅샷 쏠림, `loadAll` 비용, `updatedAt` 내보내기, 감사 로그.
- 인증(마지막).
