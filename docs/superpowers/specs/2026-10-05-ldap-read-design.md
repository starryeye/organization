# 점검 ④-2 LDAP 읽기 — 검색 필터, AD 기본 그룹, 건너뛰기, 실패 판정, 원자적 쓰기

- 날짜: 2026-10-05
- 근거: `docs/superpowers/specs/2026-09-28-full-audit.md` §7 권고 ④ — M10·M11·M12, 사소 S20~S24
- 앞 슬라이드: ④-1 불변 식별자(`docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md`, PR #37). 그 §12 가 이 슬라이드의 몫을 적어 두었다.
- 전제: 실제 규모 10만 명+, 운영 배포 전(데이터 초기화 가능 — 이관 코드를 만들지 않는다), 표준이 정한 신호만 받는다, OpenFGA Read 는 재적재와 멈춘 뒤 첫 LDAP 동기화에서만.

## 1. 문제

| 항목 | 지금 | 결과 |
|---|---|---|
| M12 | 사용자·그룹 검색이 `objectClass` **값 하나**만 받는다 | AD 의 `user` 는 `computer` 의 상위 클래스라 컴퓨터 계정(gMSA 포함)이 직원이 된다. 사람만 고르는 관용 필터 `(&(objectCategory=person)(objectClass=user))` 를 표현할 수 없다 |
| M11 | 식별 속성이 없거나 빈 엔트리는 회차 전체를 실패시킨다 — 같은 상황인 "정규화 뒤 아이디 겹침"은 건너뛰고 계속한다 | 사번을 id 로 쓰는 배포에서 사번 없는 서비스 계정 하나가 매일 전사 동기화를 멈춘다. 그동안 AD 에서 막은 퇴사자가 권한을 유지한다 |
| M10 | groupOfNames 는 그룹의 `member` 만 대조한다 | AD 는 사용자의 **기본 그룹**(`primaryGroupID`) 소속을 그 그룹의 `member` 에 적지 않는다. 관리자가 어떤 그룹을 사용자의 기본 그룹으로 바꾸면 다음 회차에 그 조직 권한과 상위 롤업이 지워진다 |
| S20 | OpenFGA 쓰기가 "지우기 배치 전부 → 쓰기 배치 전부" | 조직을 옮기는 직원이 두 단계 사이에 어느 조직에도 없다. 쓰기가 실패하면 다음 회차(최대 하루)까지. 주석 "순서가 뒤집히면 결과가 달라진다"는 틀렸다 — 한 델타의 지우기와 쓰기는 겹치지 않는다 |
| S21 | referral 로 인한 `PartialResultException` 을 Spring LDAP 이 DEBUG 로 삼킨다(`ignorePartialResultException=true`) | 검색 범위가 위임 서브트리·자식 도메인을 걸치면 그 부분이 늘 빠지는데 운영자는 모른다 |
| S22 | DIT 에서 부모 OU 를 찾지 못한 직원은 직원마다 경고 한 줄을 남기고 소속 없이 적재된다 | AD 기본 컨테이너 `CN=Users`(OU 가 아니다) 아래 전원이 그러면 "아무도 권한 없이 SUCCEEDED". 10만 명이면 경고도 10만 줄 |
| S23 | LDAP 읽기 실패는 데이터 오류(`DirectoryDataException`)만 빼고 모두 재시도한다(기본 3번) | 비밀번호 오류·없는 검색 베이스·크기 한도처럼 다시 읽어도 같은 실패로 10만 명을 4번 읽는다. 헬스 프로브는 틀린 비밀번호로 몇 초마다 바인드해 서비스 계정을 잠글 수 있다(추정) |
| S24 | 계정 상태는 AD 신호(`userAccountControl`·`accountExpires`)만 본다 | OpenLDAP ppolicy 의 관리자 영구 잠금(`pwdAccountLockedTime: 000001010000Z`)을 활성으로 읽는다 |

④-1 이 줄여 둔 것: 기본 식별 속성이 `entryUUID` 라 M11 은 일부 엔트리에만 있는 속성(사번 등)을 id 로 쓸 때만 생기고, 개명이 델타를 만들지 않아 S20 의 큰 틈(5,000명 OU 개명)은 사라졌다.
남은 S20 은 조직을 옮기는 직원과 하위 조직이다.

## 2. 결정 (사용자 확인, 2026-10-05)

- 범위: 여덟 개를 한 슬라이드로(나누지 않음).
- M10: **구현하고 늘 켠다** — 설정 스위치 없음. AD 계정 상태를 읽는 방식과 같다(표준 속성, 없으면 아무 일도 없음).
- S20: **같은 대상의 지우기와 쓰기를 한 요청에 묶는다**(OpenFGA Write 한 요청은 원자적이다).
- M12: **필터 전체를 설정 하나로** 받는다 — `objectClass` 설정은 없앤다.
- M11·S22: **건너뛰고 요약 경고 한 줄, 아무도 남지 않을 때만 실패**한다. 비율 설정은 두지 않는다.
- S21·S23·S24 와 나머지는 §3~§6 설계로 확인받았다.

## 3. 읽는 대상과 소속

### 3.1 검색 필터 (M12)

- `LdapProperties` 의 `objectClass` 값 설정을 RFC 4515 필터 문자열 설정으로 바꾼다. 옛 설정은 없앤다.

  | 전략 | 옛 설정 | 새 설정 | 기본값(지금과 같은 뜻) |
  |---|---|---|---|
  | groupOfNames | `user-object-class` | `user-filter` | `(objectClass=inetOrgPerson)` |
  | groupOfNames | `group-object-class` | `group-filter` | `(objectClass=groupOfNames)` |
  | DIT | `org-unit-object-class` | `org-unit-filter` | `(objectClass=organizationalUnit)` |
  | DIT | `user-object-class` | `user-filter` | `(objectClass=inetOrgPerson)` |

- 검색은 `LdapQueryBuilder.query().base(…).attributes(…).filter(설정한 필터)` 로 만든다. 필터 문자열은 그대로 서버에 간다 — 우리가 해석하지 않는다.
- 필터 문법이 틀리면 JNDI 가 보내기 전에 거절한다(`InvalidSearchFilterException`). 다시 해도 같으므로 재시도하지 않는다(§4.1) — 첫 동기화가 원인을 담아 FAILED 다.
- README 에 AD 권장값: 사람 `(&(objectCategory=person)(objectClass=user))`, 그룹 `(objectClass=group)`, DIT 에서 `CN=Users` 같은 컨테이너도 조직으로 읽으려면
  `(|(objectClass=organizationalUnit)(objectClass=container))`.

### 3.2 AD 기본 그룹 (M10, groupOfNames 만)

- AD 의 기본 그룹 소속은 사용자의 `primaryGroupID`(정수, 그룹의 RID)로만 표현되고 그 그룹의 `member` 에는 없다(MS-ADA3 primaryGroupID). 그룹의 `objectSid`(이진 SID)의
  마지막 하위 권한 값이 RID 다.
- 직원 검색은 `primaryGroupID` 를, 그룹 검색은 `objectSid` 를 함께 요청한다(groupOfNames 의 속성 도우미 — 두 전략이 함께 쓰는 고정 목록에는 넣지 않는다).
- `objectSid` 는 이진으로 **늘** 선언한다 — `LdapConfig.jndiEnvironment` 의 `java.naming.ldap.attributes.binary` 에 식별 속성의 `objectGUID` 와 함께 싣는다. 선언이 없으면
  JNDI 가 바이트를 문자열로 뭉갠다.
- 그룹을 다 읽은 뒤 RID → 그룹 id 표를 만들고, `primaryGroupID` 가 그 표에 있는 직원을 그 그룹의 직원 멤버로 더한다(집합이라 `member` 로 이미 있으면 그대로).
- 오류: `primaryGroupID` 가 정수가 아니거나 `objectSid` 가 SID 형식(개정 1, 하위 권한 수, 6바이트 식별 권한, 하위 권한마다 4바이트 little-endian)이 아니면
  `DirectoryDataException` — AD 계정 상태 값과 같은 규칙(표준 밖 값을 짐작해 읽지 않는다). 속성이 없으면(OpenLDAP) 아무 일도 없다.
- RID 만 맞추는 근거: 검색 베이스는 한 도메인 이름 공간 안이고 referral 을 따르지 않으므로(§4.2) 읽은 그룹은 모두 같은 도메인 SID 를 갖는다. 같은 RID 의 그룹이 둘이면
  데이터 오류다(한 도메인에서 RID 는 유일).
- "사람으로 대조된 `member` 가 하나도 없으면 실패" 가드(`UnmatchedMemberGuard`)는 DN 대조를 지켜보는 것이라 `member` 값만 센다 — 기본 그룹으로 더한 멤버는 넣지 않는다.
- 결과: 기본 그룹(대개 Domain Users)이 그룹 검색 베이스 안에 있으면 그 조직에 전원이 멤버로 들어간다 — AD 가 말하는 소속과 같다.

### 3.3 OpenLDAP 관리자 잠금 (S24)

- ppolicy(draft-behera-ldap-password-policy)의 `pwdAccountLockedTime` 이 `000001010000Z` 면 관리자만 풀 수 있는 영구 잠금이다 → 비활성으로 읽는다.
- 다른 값은 일시 잠금(잠긴 시각)이라 활성이다 — AD 의 일시 잠금을 비활성으로 보지 않는 것과 같다. 값을 해석하지 않고 그 문자열과 같은지만 본다.
- 운영 속성이라 이름을 대 요청한다(두 전략의 고정 속성 목록에 더한다).
- AD 규칙(`AdAccountStatus`)과 함께 "디렉터리 표준이 정한 막힘 신호" 한 곳에 둔다 — 이름을 `AccountStatus` 로 바꾸고 AD 둘·ppolicy 하나를 담는다.

### 3.4 건너뛰기와 "아무도 남지 않으면 실패" (M11·S22)

- 건너뛰는 사유 셋을 한 규칙으로 다룬다.
  - **id 없음** — 식별 속성이 없거나 빈 엔트리(직원·그룹·OU). 지금은 `LdapIdentifiers.필수` 가 던진다 → 매퍼가 "id 없음"으로 표시하고 전략이 건너뛴다.
  - **id 겹침** — 정규화 뒤 같은 id(지금 `DuplicateIdGuard`).
  - **부모 없음** — DIT 에서 부모 조직을 찾지 못한 직원(S22). 직원으로는 적재하되 소속은 없다(지금과 같다).
- 회차마다 검색별·사유별 건수와 예시 DN 다섯 개를 **경고 한 줄**로 남긴다 — 엔트리마다 한 줄을 남기지 않는다.
- 실패(`DirectoryDataException`, 재시도 없음)는 "아무도 남지 않을 때"뿐이다.
  - 직원 검색이 엔트리를 하나 이상 받았는데 남은 직원이 0명.
  - 그룹(또는 OU) 검색이 엔트리를 하나 이상 받았는데 남은 조직이 0개.
  - DIT 에서 남은 직원이 있는데 소속을 가진 직원이 0명(S22).
- 일부만 빠지는 경우는 막지 않는다 — 기준선이 있으면 삭제 가드(30%)가, 첫 적재면 빠진 사람이 처음부터 없던 것과 같다.
- 메시지는 원인을 말한다: 어느 검색, 어느 속성, 몇 건, 예시 DN.

## 4. 실패 판정

### 4.1 재시도할 실패만 고른다 (S23)

- LDAP 표준(RFC 4511)의 결과 코드 중 "요청 자체가 틀렸다"는 것은 재시도하지 않는다. Spring LDAP 이 결과 코드를 예외 종류로 옮겨 주므로 종류로 가른다.

  | 결과 코드 | Spring LDAP 예외 | 흔한 원인 |
  |---|---|---|
  | 49 invalidCredentials | `AuthenticationException` | 비밀번호 오류, 잠긴 서비스 계정 |
  | 50 insufficientAccessRights | `NoPermissionException` | 읽기 권한 없음 |
  | 32 noSuchObject | `NameNotFoundException` | 검색 베이스 오타 |
  | 34 invalidDNSyntax | `InvalidNameException` | DN 오타 |
  | 4 sizeLimitExceeded | `SizeLimitExceededException` | 서버 관리 한도 |
  | (클라이언트) | `InvalidSearchFilterException` | 필터 오타(§3.1) |

- 이미 있는 `DirectoryDataException`(데이터·설정 어긋남)도 그대로 재시도하지 않는다.
- 그 밖은 — 모르는 종류까지 — 지금처럼 재시도한다: 연결 끊김, 51 busy, 52 unavailable, 응답 시간 초과. 일시 장애 한 번에 하루치 동기화를 잃지 않는다는 기존 원칙(`LdapProperties.maxRetries`·`LdapDirectorySnapshotSource` 자바독)이다.
- 재시도하지 않은 실패는 마지막 실패를 그대로 이력에 남긴다(지금 `onRetryExhaustedThrow` 와 같다).

### 4.2 referral 을 드러낸다 (S21)

- 참조를 따라가지 않는 것은 그대로다(`java.naming.referral` 기본). 따라가면 다른 DC 의 주소·자격 증명·DNS 가 필요하고, AD 도메인 루트에서는 DNS 파티션 같은 엉뚱한 영역도 읽는다.
- 대신 조용히 넘기지 않는다. `LdapTemplates.configured` 의 `ignorePartialResultException` 을 끄고, `PagedLdapSearch` 가 페이지마다 `PartialResultException` 을 잡아
  **검색마다 경고 한 줄**(검색 베이스, 필터, 서버가 준 참조 정보)을 남긴 뒤 계속한다.
- 그 페이지에서 이미 받은 엔트리를 잃지 않게 결과를 우리 콜백 핸들러로 직접 모은다(`search(…, mapper, processor)` 는 예외가 나면 목록을 돌려주지 않는다). 쿠키는 Spring 이
  `finally` 에서 처리하므로 다음 페이지로 이어진다. 페이징을 안 하는 경로(`page-size` 0)도 같다.
- 실패로 만들지 않는다 — AD 에서 도메인 루트를 검색 베이스로 쓰면 참조가 늘 온다.

### 4.3 헬스 프로브가 서비스 계정을 잠그지 않게 (S23)

- `LdapHealthIndicator` 가 인증 실패(`AuthenticationException`)를 받으면 그 시각과 오류를 기억하고, **30분** 동안은 바인드하지 않고 기억한 오류로 DOWN 을 답한다.
- 30분이 지나면 한 번 다시 바인드한다 — 비밀번호를 고쳤으면 UP 으로 돌아온다(재시작 불필요).
- 30분에 한 번은 흔한 잠금 기준(관찰 창 15~30분 안에 여러 번 실패)에 닿지 않는다. 상수로 둔다 — 운영에서 바꿀 이유가 보이면 그때 설정으로 올린다. 시계는 주입해 테스트한다.
- 인증이 아닌 실패(연결 끊김 등)는 지금처럼 매번 다시 확인한다.

## 5. 원자적 쓰기 (S20)

- `OpenFgaRelationTupleWriter.batchesFor` 가 델타의 지우기와 쓰기를 튜플의 **`user` 칸**(옮겨 가는 대상 — `direct_member` 는 직원, `child` 는 하위 조직)별로 모으고,
  한 대상의 묶음을 쪼개지 않고 배치에 담는다. 배치 한도(`write-batch-size`, 100)는 지우기와 쓰기를 합친 수다.
- 배치 하나가 Write 요청 하나다 — OpenFGA 는 한 요청의 `writes`·`deletes` 를 원자적으로 반영한다. 옮기는 직원은 옛 조직에서 새 조직으로 한 번에 넘어가고, 요청이 실패하면
  둘 다 반영되지 않아 옛 상태를 유지한다(권한 공백도, 두 조직 동시 권한도 없다).
- 한 대상의 변경이 한도보다 많으면(한 직원이 100개 넘는 소속을 한꺼번에 바꿈) 그 대상만 여러 배치로 나눈다 — 이 드문 경우는 원자성을 잃는다(§11).
- 지우기와 쓰기의 순서가 없어지므로 종류별 창(`windowUntilChanged`)을 없애고 모든 배치를 `request-concurrency` 만큼 동시에 보낸다. 결과는 보낸 순서대로 센다(지금과 같다).
- 거절(400)로 반을 나눌 때(M16)도 대상 묶음 경계로 나눈다. 한 대상 묶음까지 좁혀도 거절되면 그 묶음을 줄 단위로 나눈다(지금의 "한 줄까지").
- 결과 집계(`written`·`deleted`·`failures`), 연속 실패 차단기, 멱등 옵션(`on_duplicate`·`on_missing` = IGNORE)은 그대로다.
- 틀린 주석을 지운다. SCIM 도 같은 어댑터를 쓰므로 함께 바뀐다 — core 의 커밋 순서(③-2 의 "보류 줄 → 멤버 줄 → 풀린 줄")는 서로 다른 `apply` 호출이라 영향이 없다.

## 6. 옮기기

- 설정 이름이 바뀐다(§3.1). `app-ldap/src/main/resources/application.yml` 과 `app-ldap/src/test/resources/application-test.yml`, 테스트 코드의 설정을 고친다. 운영 배포 전이라
  옛 이름을 받아 주지 않는다.
- 데이터 이관은 없다. 첫 동기화에서 기본 그룹 소속이 더해질 수 있다(AD).

## 7. 바뀌는 곳

| 모듈 | 바뀌는 것 |
|---|---|
| connector-ldap | `LdapProperties`(필터 넷), `GroupOfNamesStrategy`(필터, 기본 그룹, 건너뛰기), `DitStrategy`(필터, 건너뛰기·부모 없음), `AccountStatus`(AD + ppolicy), `UserAttributes`(`pwdAccountLockedTime`), `LdapIdentifiers`(없음은 표시), 건너뛴 엔트리 요약 부품, SID 해석, `LdapConfig`(`objectSid` 이진), `LdapTemplates`(`ignorePartialResultException` 끔), `PagedLdapSearch`(referral 경고·직접 모음), `LdapDirectorySnapshotSource`(재시도 분류) |
| app-ldap | `LdapHealthIndicator`(인증 실패 30분 쉼), 설정 yml 둘 |
| authz-openfga | `OpenFgaRelationTupleWriter`(대상별 섞인 배치, 동시 전송, 경계 쪼개기) |
| 문서 | README `## LDAP`(필터·AD 권장값, 기본 그룹, ppolicy, 건너뛰기, referral, 재시도·헬스), 점검 문서 M10·M11·M12·S20~S24 해결 표시 |

## 8. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| AD 식 필터 | 컴퓨터 계정(`objectClass: computer`, `objectCategory` 다름)이 섞인 픽스처에서 사람만 남는다 | connector-ldap |
| 필터 문법 오류 | 첫 읽기가 재시도 없이 실패하고 메시지에 필터가 있다 | connector-ldap |
| 기본 그룹 | `primaryGroupID` + 이진 `objectSid` 픽스처, `LdapConfig` 로 만든 컨텍스트 소스(이진 선언이 실제로 들어가게) — 그 그룹의 멤버가 된다. 정수 아님·SID 형식 오류·같은 RID 둘은 데이터 오류 | connector-ldap |
| ppolicy | 영구 잠금 값은 비활성, 일시 잠금 값은 활성, 속성 없음은 활성 | connector-ldap |
| 건너뛰기 | 사유 셋의 요약 경고 한 줄(건수·예시), 일부 빠짐은 계속, 아무도 안 남으면 데이터 오류(직원·조직·DIT 소속 0) | connector-ldap |
| referral | `InMemoryOperationInterceptor` 가 검색 결과 중간에 참조를 보낸다 — 경고가 남고, 참조 앞뒤 엔트리와 다음 페이지를 잃지 않는다(페이징·비페이징) | connector-ldap |
| 재시도 분류 | 표의 예외는 한 번만 읽고 실패, 연결 끊김·모르는 예외는 재시도 | connector-ldap |
| 헬스 쉼 | 인증 실패 뒤 30분 안의 프로브는 바인드하지 않고 DOWN, 30분 뒤 한 번 다시 시도해 UP(주입한 시계) | app-ldap |
| 섞인 배치 | 대상별 묶음·한도·넘치는 대상·쪼갤 때 경계(단위), 실제 OpenFGA 에 지우기·쓰기가 섞인 배치가 반영된다(컨테이너) | authz-openfga |
| e2e | AD 모양 픽스처: 필터로 컴퓨터 계정 제외, 기본 그룹 소속, 권한 Check | app-ldap |
| 규모 | 기존 규모 테스트 전체가 새 배치 짜기를 지난다 — 머지 전 `test`·`scaleTest` 둘 다 | 전체 |

## 9. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간과 결과를 구현이 끝나면 여기에 적는다.

## 10. 왜 다른 길을 안 갔나

- **M10 — 설정으로 켜기(기본 끔).** Entra Connect 가 기본 그룹을 빼는 업계 관행을 따르지만, 평범한 관리 조작이 권한을 지우는 결함이 기본 배포에 남는다.
- **M10 — 문서만("조직 그룹을 기본 그룹으로 쓰지 않는다").** 싸지만 운영자가 지켜야 하는 조건이 늘고, 어겨도 오류도 경고도 없다.
- **S20 — 쓰기 먼저, 지우기 나중.** 틈 대신 잠깐 두 조직 권한을 함께 갖고, 지우기가 실패하면 다음 회차까지 남는다 — 권한이 새는 쪽이다.
- **S20 — 주석만 고치기.** ④-1 뒤 개명은 델타를 만들지 않아 틈이 작아졌지만, 옮기는 직원의 공백과 쓰기 실패 시 하루 공백은 그대로다.
- **M12 — `objectClass` 설정 + 추가 필터 AND(Keycloak 식).** 기존 설정을 유지하지만 같은 일을 하는 설정이 둘이 된다.
- **M11 — 건너뛴 비율이 정한 값을 넘으면 실패.** 첫 동기화에서도 부분 누락을 잡지만 새 설정이 생기고 "몇 % 가 정상인가"를 배포마다 정해야 한다.
- **S21 — referral 따라가기(`follow`).** 자식 도메인까지 읽지만 다른 DC 의 주소·자격 증명·DNS 가 필요하고 AD 의 DNS 파티션까지 훑는다.
- **S21 — referral 이면 실패.** AD 도메인 루트를 베이스로 쓰는 배포가 늘 실패한다.
- **S23 — 재시도할 것만 나열(허용 목록).** 응답 시간 초과는 JNDI 가 메시지로만 구분해(`"LDAP response read timed out"`) 종류로 가를 수 없다. 모르는 실패를 재시도하지 않게 되면 일시 장애가
  하루치 동기화를 날린다.
- **S23 헬스 — 재시작까지 바인드하지 않기.** 가장 확실하지만 AD 관리자가 계정 잠금을 풀거나 비밀번호를 맞춰도 재시작 전까지 DOWN 이다.
- **S23 헬스 — 바인드 없이 연결만(RootDSE 익명 읽기).** 잠금은 없지만 자격 증명 문제를 헬스가 못 잡는다 — 하루 한 번 동기화까지 아무도 모른다.

## 11. 이 설계가 말할 수 없는 것

- **실제 AD 로 확인하지 못한다** — `primaryGroupID`·`objectSid` 와 referral 은 임베디드 UnboundID 로만 본다(④-1 의 `objectGUID`·`member;range=` 와 같은 한계).
- **기본 그룹은 RID 로만 맞춘다** — 검색 베이스가 한 도메인 안이라는 전제다(referral 을 따르지 않으므로 성립). 다른 도메인의 그룹은 애초에 읽지 않는다.
- **ppolicy 는 표준 영구 잠금 값만 본다** — `pwdLockoutDuration: 0` 정책 아래의 다른 값(사실상 영구)은 정책 엔트리를 읽어야 알 수 있어 일시 잠금(활성)으로 읽는다.
- **한 대상이 배치 한도보다 많이 바뀌면 그 대상은 원자적이지 않다** — 한 직원이 100개 넘는 소속을 한꺼번에 바꾸는 경우.
- **referral 로 빠진 부분은 경고만 한다** — 그 직원들은 이번 회차에 없는 것과 같고, 기준선이 있으면 삭제 가드가 큰 빠짐을 막는다.
- **헬스의 30분 쉼은 프로브만 막는다** — 정기 동기화는 하루 한 번 바인드하고(재시도 없음, §4.1), 관리 API 수동 동기화도 그때마다 한 번 바인드한다.
- **첫 적재에서 일부만 건너뛰면 막지 않는다** — 기준선이 없어 삭제 가드가 볼 것이 없다. 경고 한 줄이 유일한 신호다.

## 12. 범위 밖

- 권고 ⑤(SCIM 오류 신호 — M4·M3·M5·M18)·⑥(나머지 성능 — P3·P4·P5·P6), 기존 백로그, 인증(마지막).
- AD 의 중첩 그룹 확장(`LDAP_MATCHING_RULE_IN_CHAIN`) — 지금처럼 그룹의 `member` 에 든 하위 그룹을 `child` 로 읽는다.
- 건너뛴 엔트리 수를 지표(메트릭)로 내보내기 — 경고 로그만 남긴다.
