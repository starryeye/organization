# 직원 이름 칸과 직원 PATCH (S-3) — 설계

> 브랜치 `scim-name-parts` (origin/main `cf28cc1` 에서 분기).
> 발단: IdP 호환성 감사 [`2026-09-09-idp-conformance-audit.md`](2026-09-09-idp-conformance-audit.md) §2.3 — "중".

## 1. 문제

**이름이 사라진다.** 도메인 모델(`DirectoryUser`)에 성·이름 칸이 없다. `ScimMapper` 는 들어온 `name` 에서 `formatted` 만
`displayName` 의 대체값으로 쓰고, 응답은 `new ScimName(displayName, null, null)` — 이름이 없어도 `formatted` 를 지어낸다.
Okta 의 최소 요구 속성 넷(`userName`, `name.givenName`, `name.familyName`, `emails`) 중 둘을 잃는다.

**PATCH 가 요청째 실패한다 (감사 문서에 없던 것).** Entra 문서의 사용자 PATCH 예시는 한 요청에 `name.familyName` 과
`emails[type eq "work"].value` 를 함께 보낸다. `ScimPatchApplier` 가 받는 직원 경로는 `active`·`displayName`·`userName`
셋뿐이고 나머지는 400 `invalidPath` 라, **Entra 가 사용자의 이름이나 이메일을 바꾸면 요청 전체가 실패한다.** 경로 없는
PATCH(부분 리소스 병합)는 `name`·`emails`·`externalId` 를 조용히 버린다.

**LDAP 은 이름을 읽지 않는다.** 표준 속성(`givenName`, `sn`)이 있는데도 읽지 않고, 읽어도 보여 줄 곳(admin 직원 상세)이 없다.

## 2. 결정 — 사용자와 정한 것 (2026-09-26)

| 주제 | 결정 | 버린 안 |
|---|---|---|
| 이름 범위 | **RFC 7643 `name` 하위 속성 여섯 전부를 한 묶음으로** — `formatted`, `familyName`, `givenName`, `middleName`, `honorificPrefix`, `honorificSuffix` | `givenName`·`familyName` 만, 셋만 |
| PATCH 범위 | **우리가 저장하는 직원 속성 전부** — 지금의 `userName`·`displayName`·`active` + `externalId`, `name`·`name.*`, `emails`·`emails[type eq "work"]` | `name` 만, `name`+`emails` 만 |
| LDAP·admin | **둘 다** — LDAP 은 표준 이름 속성을 읽고, admin 직원 상세가 이름을 보여 준다 | SCIM 만, SCIM+admin |
| 저장하지 않는 속성 | **지금 규칙 유지** — 경로로 오면 400 `invalidPath`(IdP 가 반영됐다고 오해하지 않게). README 에 저장하는 속성과 IdP 매핑에서 뺄 것을 적는다 | 조용히 무시하기 |

## 3. 도메인

- 새 값 객체 **`PersonName(formatted, familyName, givenName, middleName, honorificPrefix, honorificSuffix)`** (core/model).
  빈 문자열은 "없음"(null)으로 정규화한다. 여섯 칸이 모두 없으면 `PersonName.EMPTY` 와 같다.
- `DirectoryUser` 에 칸 하나 **`name`** 을 더한다. null 로 들어오면 `PersonName.EMPTY` 로 바꾼다.
- **기존 6인자 생성자를 남긴다**(이름 없음). 호출 100곳 중 테스트 89곳은 이름과 무관하다. 이름을 채우는 운영 코드(SCIM 매퍼,
  PATCH, LDAP 두 전략, 저장소)만 7인자를 쓴다.
- 튜플에는 쓰지 않는다 — 권한과 무관하다.

## 4. 저장소

- 직원 META 에 평평한 문자열 속성 여섯: `givenName`, `familyName`, `middleName`, `honorificPrefix`, `honorificSuffix`,
  `nameFormatted`. 값이 없는 칸은 두지 않는다(`Attrs.putIfPresent`). `formatted` 만 이름을 바꾼 것은 `displayName` 과 헷갈리지
  않게 하려는 것이다.
- GSI 쏠림 작업의 "바뀐 것만 쓴다"(`userItem`·`toUser` 로 비교)가 이름 칸도 그대로 비교한다 — 이름만 바뀌어도 쓰고
  `updatedAt` 을 찍는다.
- **키·인덱스는 바뀌지 않는다.** GSI1 은 ALL 프로젝션이라 목록·필터 조회에도 이름이 실린다. GSI2(`INCLUDE userName, active`)·
  GSI3(`KEYS_ONLY`)는 이름을 담지 않는다. **테이블 재생성은 필요 없다.**

## 5. LDAP

두 전략(`GroupOfNamesStrategy`, `DitStrategy`)이 표준 속성을 읽는다. 속성 이름은 설정으로 빼지 않는다 — ⑥ 의 AD 계정 상태와
같은 방식이다(표준을 따른다).

| LDAP 속성 | → `PersonName` | 출처 |
|---|---|---|
| `givenName` | `givenName` | RFC 4519 |
| `sn` | `familyName` | RFC 4519 |
| `generationQualifier` | `honorificSuffix` | RFC 4519 (Jr., III) |
| `middleName` | `middleName` | AD 스키마 |

- `formatted`·`honorificPrefix` 는 LDAP 표준 속성이 없어 비운다. 속성이 없으면 빈칸이다.
- 엔트리를 이미 속성 목록 없이 검색하므로 검색은 바꾸지 않는다.

## 6. admin

직원 상세(`EmployeeDetail`)에 `name`(`PersonName`)을 더한다. JSON 에는 값이 있는 칸만 나간다.

## 7. SCIM

### 7.1 생성·교체와 응답

- POST/PUT 의 `name` 여섯 칸을 **보낸 그대로** 저장한다.
- `displayName` 대체 규칙(`displayName` → `name.formatted` → `userName`)은 그대로다.
- **응답의 `name` 은 저장된 값이다.** 모두 비었으면 `name` 을 넣지 않는다 — 지금처럼 `formatted` 를 지어내지 않는다(Entra: "Values
  sent should be stored in the same format they were sent").
- `ScimName` DTO 에 `middleName`·`honorificPrefix`·`honorificSuffix` 를 더한다. `ScimResourceType.USER` 의 속성 목록에
  `name.middlename`·`name.honorificprefix`·`name.honorificsuffix` 를 더한다(`attributes`·`excludedAttributes` 가 안다).
- `name.*` 필터는 인덱스가 없어 지금처럼 400 `invalidFilter` 다.

### 7.2 직원 PATCH

op(`add`/`replace`/`remove`)는 지금처럼 대소문자를 가리지 않는다. **경로의 속성 이름도 대소문자를 가리지 않게 바꾼다**(RFC 7643
§2.1 "Attribute names are case insensitive"). 지금 README 는 "`path`는 대소문자를 구분한다" 고 적고 있다 — 표준과 어긋나므로
고치고, 규칙이 갈리지 않도록 **조직 PATCH 경로(`members`, `displayName`, `members[value eq "…"]`)에도 같이 적용한다.**

| 경로 | `add` / `replace` | `remove` |
|---|---|---|
| `userName` | 값 설정 | 400 `mutability` — 필수 속성(RFC 7644 §3.5.2.2) |
| `displayName`, `externalId` | 값 설정 | 비움 |
| `active` | 값 설정 | "없음" = 활성 — POST 에 `active` 가 없을 때와 같은 규칙 |
| `name` | 준 하위 속성만 바꾸고 나머지는 그대로(RFC 7644 §3.5.2.3) | 이름 전부 비움 |
| `name.formatted`, `name.familyName`, `name.givenName`, `name.middleName`, `name.honorificPrefix`, `name.honorificSuffix` | 그 칸 설정 | 그 칸 비움 |
| `emails` | 목록 중 `primary` 가 참인 것, 없으면 첫째를 이메일로 — POST 와 같은 규칙 | 비움 |
| `emails[type eq "work"].value` | `add`: 설정. `replace`: 이메일이 있으면 설정, 없으면 400 `noTarget`(RFC 7644 §3.5.2.3) | 비움 |
| `emails[type eq "work"]` | 값 객체의 `value` 로 위와 같다 | 비움 |
| 그 밖의 필터(`type eq "home"` 등), 그 밖의 경로 | 400 `invalidPath` | 같음 |

- 우리는 이메일을 하나만 담고 `type: "work"` 로 내보낸다 — 그래서 값 경로 필터는 `type eq "work"` 하나만 받는다. 필터의 `eq` 와
  `"work"` 비교는 대소문자를 가리지 않는다(`type` 은 RFC 7643 에서 `caseExact=false`).
- **경로 없는 PATCH**(값 객체 병합, `add`/`replace`)도 같은 속성을 모두 반영한다: `userName`, `displayName`, `active`,
  `externalId`, `name`(하위 속성 병합), `emails`(목록 규칙). 값 객체의 키 이름도 대소문자를 가리지 않는다.
- 여러 연산은 순서대로 적용하고, 하나라도 실패하면 요청 전체가 실패하고 아무것도 반영되지 않는다(지금과 같다).

### 7.3 우리가 저장하지 않는 속성

`title`, `phoneNumbers`, `addresses`, 엔터프라이즈 확장(`department`, `manager`, `employeeNumber`) 등은 저장하지 않는다.

- PATCH 에 **경로로** 오면 400 `invalidPath` — 지금 규칙을 유지한다. 반영되지 않은 변경을 반영됐다고 IdP 가 오해하면 안 된다.
  Entra 는 기본 속성 매핑에 이런 속성을 넣을 수 있으므로, **운영자가 IdP 의 속성 매핑에서 빼야 한다.** README 에 우리가 저장하는
  속성 목록과 함께 적는다.
- POST 본문이나 경로 없는 PATCH 의 값 객체에 섞여 오면 지금처럼 무시한다.

## 8. 검증

테스트 규칙은 기존과 같다 — Lombok, AssertJ, BDD(given/when/then), 한글 `@DisplayName`.

1. **core** — `PersonName` 정규화(빈 문자열 → 없음, 여섯 칸 모두 없으면 `EMPTY`), 6인자 생성자 → `EMPTY`, null → `EMPTY`.
2. **저장소** — 이름 저장·되읽기, 이름만 바뀌어도 PutItem 과 `updatedAt`, 이름이 없으면 속성을 두지 않음, 목록 조회(GSI1)에도
   이름이 실림.
3. **SCIM (connector-scim)** — POST 로 여섯 칸 저장·응답, 이름이 없으면 응답에 `name` 없음; §7.2 표의 각 줄;
   **Entra 문서의 PATCH 예시를 글자 그대로**(이메일 + 성 한 요청) 보내 둘 다 반영; 400 `noTarget`·`mutability`·`invalidPath`;
   경로 없는 병합; `attributes=name.middleName`.
4. **LDAP (connector-ldap)** — 두 전략이 네 속성을 읽음, 속성이 없으면 빈칸.
5. **admin** — 직원 상세 JSON 에 `name`.
6. **E2E (app-scim)** — Okta 식 POST(성·이름) → GET·`userName eq` 조회에 이름; Entra 식 PATCH.

## 9. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간을 구현이 끝나면 여기에 적는다.

## 10. 왜 다른 길을 안 갔나

**`givenName`·`familyName` 만.** 두 IdP 의 필수 매핑은 이 둘이지만, Okta 프로필에는 `middleName`·경칭도 있고 RFC 가 정한 칸은
여섯이다. 한 묶음(`PersonName`)으로 담으면 `DirectoryUser` 에는 칸 하나만 늘어 비용 차이가 거의 없다.

**PATCH 를 `name` 만.** Entra 가 이름과 이메일을 한 요청에 보내므로 이름 경로만 받으면 그 요청은 여전히 400 이다.

**6인자 생성자 없애기.** 호출 100곳(테스트 89곳)이 이름과 무관한데 전부 고쳐야 한다. 남겨 두면 "이름 없음" 이 기본값이라는 뜻도
분명하다.

**`name` 을 DynamoDB 맵(M) 속성 하나로.** 평평한 문자열이 `Attrs.putIfPresent`·"바뀐 것만 쓴다" 비교와 그대로 맞고, GSI 프로젝션
규칙도 단순하다.

**저장하지 않는 속성을 조용히 무시.** IdP 가 반영됐다고 믿게 된다 — README 의 기존 원칙과 어긋난다.

## 11. 이 설계가 말할 수 없는 것

- **IdP 동작은 문서 기준이다.** 실제 테넌트 검증은 인증 슬라이드 뒤다.
- **이메일은 하나만 담는다.** `emails` 에 여러 개를 보내면 primary(없으면 첫째) 하나만 남고, `add` 로 이메일을 더해도 하나로 바뀐다.
- **LDAP 의 `formatted`·`honorificPrefix` 는 비어 있다** — 표준 속성이 없다.
- **POST 본문의 저장하지 않는 속성은 여전히 조용히 무시된다**(§7.3). 경로 PATCH 만 거절한다.
