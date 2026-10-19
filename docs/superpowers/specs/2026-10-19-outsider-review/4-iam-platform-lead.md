# 외부 리뷰 — IAM 플랫폼 리드 관점 (2026-10-19)

- 대상: `spike-ldap-5k` 체크아웃(작업 트리 그대로). 경로는 저장소 루트 기준이다.
- 방법: README, `settings.gradle`, 각 모듈 `src/main` 을 읽었다. 빌드·테스트·Docker 는 돌리지 않았다. `docs/superpowers/`·`.superpowers/`·git 이력은 보지 않았다.
- 표기: **확인됨** = 코드 경로를 끝까지 따라가 확인함. **의심** = 그럴 법하지만 끝까지 확인하지 못함. IdP 동작은 내 경험에서 나온 지식이고, 벤더 문서로 다시 확인해야 하는 부분은 따로 적었다.

---

## 1. 이 시스템을 어떻게 이해했나

1. 디렉터리(LDAP/AD 또는 SCIM 을 보내는 IdP)에 있는 "사람–조직(그룹)" 관계를 OpenFGA 튜플(`direct_member`, `child`)로 옮기는 동기화 서버다. 인가 판정은 소비 앱이 OpenFGA 에 `member` 를 Check 해서 직접 한다.
2. **app-ldap(pull)**: cron(기본 매일 03:00)에 맞춰 사용자·그룹(또는 OU)을 페이징으로 전부 읽어 `DirectorySnapshot` 을 만든다. `TupleMapper` 로 목표 튜플을 만들고, DynamoDB 에 저장한 "직전에 실제로 반영된 튜플" 스냅샷과 비교해 델타를 낸다.
3. 그 델타를 OpenFGA 에 3단계 순서(조직 연결 지우기 → 직원 → 조직 연결 쓰기)로 쓴다. 성공한 것만 새 스냅샷에 담고, DynamoDB 의 현재상태를 LDAP 대로 바꾼다.
4. **app-scim(push)**: IdP 요청 하나마다 클러스터 전체에 하나뿐인 DynamoDB 락을 잡는다. 변경 전후의 최소 그림을 만들고, OpenFGA Check 로 실제 있는 튜플을 기준선으로 삼아 델타를 적용한 뒤, 반영된 만큼만 DynamoDB 에 커밋한다.
5. 식별자: LDAP 은 `entryUUID`/`objectGUID`, SCIM 은 서버가 발급한 UUID 다. 비활성(AD UAC 비트·`accountExpires`·ppolicy 영구 잠금, SCIM `active=false`)이면 멤버십은 남기고 튜플만 지운다.
6. 안전장치: 삭제 가드(30%), "기록 중" 표시와 장부 훑기, 재적재(rebuild), 변경 이력(DynamoDB, 기본 30일).
7. 배포 하나에 커넥터 하나만 쓴다. 인증은 어디에도 없다.

## 2. 총평과 운영 승인 여부

**판정: 지금 상태로는 우리 회사 IdP·디렉터리에 연결하지 않는다.**

이 정도 규모의 프로젝트치고 실패 모드를 다룬 깊이는 드물게 좋다. 다룬 것: 부분 읽기, 범위 검색, 페이징 컨트롤, referral, 쓰기 순서와 단조성, 리스 상실, 응답 유실 재시도. 다만 IAM 운영 승인 기준에는 막히는 것이 있다. 첫째, **누구나 권한을 만들 수 있다.** 둘째, **퇴사자 권한이 언제, 확실히 사라지는지 보장할 수 없다.**

연결 전에 반드시 할 것:

1. **인증·인가.** SCIM 은 IdP 별 토큰 또는 OAuth2 client credentials 를 받고, 관리 API 는 SSO+RBAC 를 붙인다. OpenFGA 호출에도 자격 증명을 싣는다(C1).
2. **퇴사 반영 SLO.** LDAP 회차를 분 단위로 돌리거나 증분 경로를 만든다. 실패하면 빨리 재시도한다. 삭제 가드가 걸려도 명시적으로 막힌 계정의 회수는 통과시킨다. 특정 직원의 권한을 곧바로 거두는 긴급 회수 API 를 둔다(H1, H2).
3. **SCIM 회수의 내구성.** 비활성화 요청(의도)을 반영 결과와 분리해 저장하고, 서버가 끝까지 재시도한다. Entra 의 레거시 멤버 제거 형식을 받아 준다(H4, H5).
4. **주기적 대조와 신선도 경보.** 마지막 성공 동기화 시각을 지표로 내고, 장부(OpenFGA)와 정기적으로 대조한다(M6, M11).
5. **감사 증적.** 행위자·사유를 남기고, 보관 기간을 1년 이상으로 늘리고, 변경할 수 없는 저장소로 내보낸다(H7).

이 다섯이 끝나면 제한된 테넌트(LDAP 한 도메인, 소수 앱)로 파일럿을 하겠다.

## 3. 잘한 점

- **불변 식별자가 기본값이다.** `LdapProperties.java:63-67,76` 의 기본값이 `entryUUID` 이고 AD 는 `objectGUID` 다. `LdapIdentifiers.java:57-64` 는 GUID 바이트 순서(앞 세 묶음 리틀 엔디언)를 AD 도구와 같게 맞춘다. SCIM 은 `ScimUserHandler.java:47-48` 에서 서버가 UUID 를 발급하고 본문 `id` 를 무시한다. 개명·이동으로 튜플이 흔들리지 않고, 이름을 재사용해도 권한이 넘어가지 않는다.
- **부분 읽기를 정상 응답으로 오인하지 않는다.** 이것은 LDAP 동기화에서 가장 흔한 대량 삭제 원인이다.
  - 페이징 응답 컨트롤이 없으면 실패한다(`PagedLdapSearch.java:80-84`).
  - size-limit 예외를 삼키지 않는다(`LdapTemplates.java:54-58`).
  - AD 범위 검색을 같은 커넥션에서 끝까지 이어받고, 다 받지 못하면 예외로 끝낸다(`RangedAttributeReader.java:115-124,185-205`).
  - referral 만 받고 엔트리가 0건이면 실패한다(`PagedLdapSearch.java:153-167`).
  - 사람으로 대조된 멤버가 0명이면 멈춘다(`UnmatchedMemberGuard.java:41-49`).
- **AD 의 까다로운 부분을 다룬다.**
  - `primaryGroupID` 를 RID 로 대조한다(`ActiveDirectoryPrimaryGroup.java:47-58`, `GroupOfNamesStrategy.java:162-183`).
  - `userAccountControl` 을 값이 아니라 비트로 본다. `accountExpires` 의 0·MAX 값을 처리한다(`AccountStatus.java:53-65`).
  - 인증이 실패하면 헬스 프로브가 30분 동안 bind 하지 않아 서비스 계정이 잠기지 않는다(`LdapHealthIndicator.java:45`).
- **"반영된 것만 기준선"이고, 기록 전에 멈춘 회차는 장부를 훑어 맞춘다.** `FullSyncUseCase.java:93-95,166-182`, `LedgerAlignment.java:47-56` 에 있다. 재시도 큐 없이도 실패가 다음 회차에 다시 잡힌다.
- **단조 모델에 맞춘 쓰기 순서와 "회수 우선" 원칙이 코드에 있다.** 쓰기는 3단계로 나간다(`OpenFgaRelationTupleWriter.java:287-296`). 앞 단계가 실패하면 뒤 단계의 *쓰기*는 보류하고 *지우기*는 보낸다(`:97-104,151-159`). 그래서 이동하는 직원이 옛 조직과 새 조직의 권한을 동시에 갖지 않는다.
- **SCIM 쓰기 경로가 경합에 강하다.**
  - 판단을 모두 락 안에서 하고, 커밋 직전에 리스를 다시 확인한다(`IncrementalSyncUseCase.java:967-979`).
  - 기준선을 OpenFGA Check 로 잡고, 드리프트를 관측한다(`:884-937`).
  - 역참조를 강한 일관성으로 읽는다(`DirectoryStateRepository.java:93-100`).
- **저장하지 않는 RFC 속성은 받아서 버린다.** 그래서 같은 PATCH 에 실린 `active=false` 가 막히지 않는다(`ScimPatchApplier.java:326-333`). Entra 가 보내는 문자열 `"False"` 는 받고, 그 밖의 값은 400 이다(`:527-540`).
- **오류를 IdP 가 이해하는 신호로 바꾼다.** 재시도하면 나을 일은 503 + Retry-After, 요청이 틀렸으면 4xx, 버그만 500 이다. 내부 메시지는 응답에 싣지 않는다(`ScimRouter.java:150-194`).
- **재적재가 장부를 비우지 않는다.** 있어야 할 줄을 먼저 다 쓰고 나서 나머지를 지운다(`TupleReconciler.java:95-110`). `wipe` 는 장부를 먼저, 조직도를 나중에 지운다.
- **감사 이력의 단위가 좋다.** 대상 × 사건 하나가 한 줄이고, 바뀐 속성의 전후 값과 실제로 쓰고 지운 튜플이 남는다(`AuditEntry.java:21-32`).

---

## 4. 발견 사항

### Critical

#### C1. 인증·인가가 어디에도 없다 — 누구나 권한을 만들고, 가드를 우회하고, 조직도를 지울 수 있다 [확인됨]

- **근거**
  - `app-scim/build.gradle:3-15`, `app-ldap/build.gradle:3-15`: 보안 의존성이 없다.
  - `connector-scim/.../ScimRouter.java:89-117`: 라우트에 인증 필터가 없다. `:252` 에서 `authenticationSchemes` 가 빈 목록이다.
  - `app-ldap/.../AdminSyncController.java:48-71`: `force`·`rebuild` 를 누구나 부를 수 있다.
  - `app-scim/.../AdminSyncController.java:51-87`: `wipe` 의 확인값은 테이블명이고, 기본값이 `organization-scim` 이다(`app-scim/src/main/resources/application.yml:29`).
  - `admin-api/.../AdminQueryController.java:37-100`: 이름·이메일·소속·이력이 공개된다.
  - `authz-openfga/.../StoreBootstrapper.java:304`: `ClientConfiguration` 에 apiUrl 만 있고 자격 증명이 없다.
  - `app-ldap/src/main/resources/application.yml:92`: 헬스 상세가 항상 공개된다(`show-details: always`).
- **시나리오**
  - 사내망 누구든 `POST /scim/v2/Users` 로 사용자를 만들고 `PATCH /scim/v2/Groups/<재무본부 id>` 로 자신을 멤버로 넣는다. 그 순간부터 소비 앱의 `Check(member, 재무본부 및 상위)` 가 true 다.
  - LDAP 이 설정 실수로 0명을 돌려준 날 누군가 `POST /admin/sync/full?force=true` 를 부르면 전 직원 권한이 회수된다.
  - `POST /admin/sync/rebuild?mode=wipe&confirm=organization-scim` 한 번이면 SCIM 조직도의 유일한 사본이 사라진다.
  - OpenFGA 가 인증을 요구하도록 바꾸는 순간 이 서버는 OpenFGA 에 붙지 못한다. 반대로 인증 없는 OpenFGA 를 전제하면, 누구나 OpenFGA 에 튜플을 직접 쓸 수 있다.
- **왜 중요한가**: 잘못된 권한 부여, 데이터 손실, 개인정보 노출이 모두 한 번의 HTTP 요청으로 가능하다. 저장소 메모로 보아 인증을 마지막 순서로 둔 것 같은데, 운영 연결 전에는 반드시 있어야 한다.
- **권고**
  - SCIM: IdP 연결마다 토큰을 따로 발급하고(해시 저장, 회전 가능), 또는 OAuth2 client credentials 를 받는다. 토큰마다 허용 리소스와 연산을 정한다.
  - 관리 API: SSO 를 붙이고 역할을 셋으로 나눈다(조회 / 동기화 / 파괴적 작업). `force`·`wipe` 는 두 사람 승인을 받고 사유를 필수로 한다.
  - OpenFGA: preshared key 또는 OIDC client credentials 를 설정할 수 있게 한다.
  - actuator 는 관리 포트로 분리한다.

### High

#### H1. LDAP 퇴사 반영이 기본 하루 1회이고, 실패하면 수 초 안에 재시도를 다 써 버린다 [확인됨]

- **근거**
  - 주기: `app-ldap/src/main/resources/application.yml:20` 이 `cron: "0 0 3 * * *"` 이고, `SyncProperties.java:14` 도 같다.
  - 재시도: `LdapDirectorySnapshotSource.java:58-65` 가 `Retry.backoff(3, 200ms)` 다. 수 초 안에 소진되고 FAILED 로 끝난다.
  - 증분 없음: `DirectorySnapshotSource.java:9-14` 에 "이 인터페이스에는 증분이라는 개념이 없다" 고 적혀 있다.
  - 판정 시각: 만료 판정 기준이 회차 시작 시각이다(`AccountStatus.java:49-65`, `GroupOfNamesStrategy.java:56`).
  - 긴급 회수 API 없음: 관리 API 는 조회 전용이다(`AdminQueryController.java`).
- **시나리오**
  - 09:00 에 해고자 AD 계정을 비활성화하면 다음 날 03:00 까지 약 18시간 동안 OpenFGA `member` 가 true 다.
  - 03:00 에 DC 가 재부팅 중이면 재시도 3번이 1~2초 안에 끝나 FAILED 가 된다. 다음 시도는 다음 날 03:00 이므로 최대 약 42시간이 된다.
  - `accountExpires` 가 오늘 17:00 인 계약직도 다음 날 03:00 까지 권한이 유효하다.
  - 사람이 `POST /admin/sync/full` 을 부르면 몇 분 안에 반영되지만, 그 경로는 사람이 기억해야만 작동한다.
- **왜 중요한가**: 퇴사, 특히 해고 시의 회수는 대개 분~1시간 SLO 로 감사받는다. 이 서버가 회사 전체 인가의 근거라면 하루 지연은 기본값으로 둘 수 없다.
- **권고**
  - 회차 주기를 분 단위로 내린다. 스냅샷 diff 라 OpenFGA 쓰기는 작고 비용은 주로 디렉터리 전체 읽기이므로, 10만 명 기준 실측이 필요하다.
  - 실패하면 분 단위 지수 백오프로 다시 건다.
  - AD 는 DirSync 컨트롤이나 `uSNChanged` 기반 증분을, OpenLDAP 은 syncrepl/accesslog 기반 증분을 검토한다.
  - "특정 직원 즉시 회수" API 를 둔다. 다음 전체 동기화가 되돌리지 않도록 현재상태에도 반영한다.
  - `last_success_timestamp` 에 SLO 경보를 건다(M11).

#### H2. 삭제 가드가 '전부 아니면 전무'라서, 대량 변경 하나가 모든 퇴사자 회수를 멈춘다 [확인됨]

- **근거**
  - `FullSyncUseCase.java:126-132`: 가드가 걸리면 쓰기·지우기·현재상태 교체를 모두 건너뛰고 ABORTED 로 끝난다. 장부 훑기도 같다(`LedgerAlignment.java:49-55`).
  - `DeletionGuard.java:32-48`: 기준은 튜플 수 비율 하나뿐이다.
  - `SyncScheduler.java:32-45`: 다음 날도 같은 기준선으로 돌아 다시 ABORTED 가 된다.
  - `SyncMetrics.java:40-42`: 알림은 카운터 하나다.
- **시나리오**
  - 조직 개편으로 레거시 대형 그룹(전체 튜플의 35%)을 지운다. 그날부터 매일 ABORTED 다.
  - 같은 기간 퇴사자 40명의 회수와 입사자 부여가 모두 멈춘다. 사람이 `force` 할 때까지 기간에 끝이 없다.
  - `force` 는 인증 없는 API 이고(C1), 누가 왜 했는지 남지 않는다(H7).
  - 대량 정리 해고처럼 정상적인 사건도 같은 방식으로 멈춘다.
- **왜 중요한가**: 가드는 "잘못 읽은 디렉터리"를 막으려는 것인데, 실제로는 "정당한 퇴사 처리"까지 함께 막는다. 퇴사자의 권한이 무기한 유지된다.
- **권고**
  - 델타를 범주로 나눈다. 디렉터리가 명시적으로 막거나 지운 계정의 회수(UAC 비트·만료·엔트리 삭제를 직접 관측한 사람)는 가드 밖에서 늘 적용한다.
  - 가드는 구조적 대량 삭제(그룹 소멸, 멤버 대조 실패, 사람 수 급감)에만 건다.
  - 사람 단위 비율과 튜플 단위 비율을 따로 둔다.
  - ABORTED 는 호출(page) 알림으로 보내고, `force` 승인 흐름(누가, 왜)을 만든다.

#### H3. 기준선 스냅샷이 없으면 장부가 비었다고 믿는다 — 상태를 잃은 뒤 남은 권한이 영구히, 보이지 않게 남는다 [확인됨, 발동 조건은 운영 사건]

- **근거**
  - `FullSyncUseCase.java:144-148`: 스냅샷이 없으면 빈 기준선을 쓴다. `:120` 의 `TupleDiff` 는 기준선이 비면 지울 것이 0이다.
  - `DynamoDbTupleSnapshotRepository.java:150-153,179-183`: 포인터가 없으면 "기록 중" 표시도 없어서 장부 훑기 경로를 타지 않는다.
  - 발동을 부르는 문서: README 는 테이블을 다시 만들라고 여러 번 지시하고(`README.md:129-146`), store 번호는 영구 고정한다(`README.md:94-97`).
- **시나리오**
  - DynamoDB 테이블을 다시 만들거나(키 레이아웃 변경, 사고 복구), 설정이 다른 테이블을 가리키게 된다.
  - 마지막 동기화 이후 퇴사한 A 의 튜플이 OpenFGA 에 남아 있다.
  - 다음 동기화에서 기준선이 비어 있으므로 목표만 쓰고 지우기는 0이다. A 의 튜플이 영구히 남는다.
  - A 는 현재상태에 없으므로 관리 API 로도 보이지 않는다(404). 어떤 지표도 오르지 않는다.
  - PITR 로 과거 시점에 복원한 뒤, 그 사이 입사했다가 다음 회차 전에 퇴사한 사람도 같은 일을 겪는다.
- **왜 중요한가**: 퇴사자 권한이 남고, 그것을 발견할 수단이 없다. LDAP 배포에는 주기적 대조가 없다(M6).
- **권고**
  - 기준선을 믿을 근거가 없으면 비교 대신 장부 훑기(이미 있는 `LedgerAlignment`)를 한다. 근거가 없는 경우는 다음과 같다.
    - 첫 회차
    - 포인터가 없을 때
    - 스냅샷에 기록한 store id·모델과 지금 장부가 다를 때
  - 스냅샷에 store id 를 기록해 짝이 맞는지 확인한다.

#### H4. SCIM 비활성화가 OpenFGA 에서 실패하면 '비활성화 요청 자체'가 사라진다 [코드 확인됨 / IdP 재시도 동작은 의심]

- **근거**
  - `IncrementalSyncUseCase.java:252-259,1024-1029`: 반영이 실패하면 `active` 를 *이전 값*으로 되돌려 저장한다.
  - `ScimUserHandler.java:125-130`: 응답은 503 + Retry-After 다.
  - `SnapshotArchiveUseCase.java:107-131,140-145`: 상태가 `active=true` 이고 튜플도 있으므로 일일 대조에 어긋남으로 잡히지 않는다. 잡히더라도 로그만 남는다.
  - 다른 대조 경로가 없다(`README.md:916-918`).
- **시나리오**
  - Okta 가 퇴사자에게 `PATCH active=false` 를 보낸다. 그 순간 OpenFGA 가 일시 장애다.
  - 상태는 `active=true` 로 남고 응답은 503 이다.
  - IdP 가 그 503 을 재시도하지 않거나 한도 끝에 포기하면, 시스템 어디에도 "이 사람은 비활성이어야 한다"는 기록이 없다. 장부와 상태가 서로 일치하므로 드리프트 지표도 아카이빙 대조도 조용하다.
  - 퇴사자는 IdP 가 다시 건드리지 않으므로 권한이 영구히 남는다.
- **벤더 동작(내 지식, 확인 필요)**
  - Entra 는 실패한 작업을 이후 주기에 재시도한다(에스크로).
  - Okta 의 5xx 처리(자동 재시도인지, 관리자 Task 로 쌓이는지)는 앱과 설정에 따라 다르다. README 도 문서 근거가 없다고 적었다(`README.md:789`).
- **왜 중요한가**: 회수의 내구성이 외부 시스템의 재시도 정책에 달려 있다. IAM 에서 회수 방향은 서버가 끝까지 책임져야 한다.
- **권고**
  - 의도(desired)와 반영(applied)을 나눈다. 요청 값 `active=false` 는 커밋하고, "미반영 회수" 표시(outbox)를 남겨 백그라운드에서 재시도한다.
  - 응답은 202/200 으로 하고 IdP 재시도에 기대지 않는다.
  - 주기적 대조로 "상태가 비활성인데 튜플이 있음"을 탐지해 자동으로 회수한다.

#### H5. Entra 의 레거시 멤버 제거 형식을 400 으로 거절한다 — 그룹에서 빠진 사람이 권한을 유지한다 [코드 확인됨 / Entra 기본값은 의심]

- **근거**: `ScimPatchApplier.java:207-216` 은 `remove` + `value` 를 400 `invalidValue` 로 거절한다. 안내 문구는 `:45-47` 에 있다. README 는 `?aadOptscim062020` 을 "반드시" 붙이라고 한다(`README.md:680-685`).
- **시나리오**
  - 플래그 없이 연결된 Entra 앱에서 사용자를 "재무팀" 그룹에서 뺀다.
  - Entra 가 `{"op":"Remove","path":"members","value":[{"value":"<id>"}]}` 를 보낸다.
  - 서버는 400 을 돌려준다. Entra 는 4xx 를 고쳐야 할 실패로 기록하고 같은 요청을 성공시킬 방법이 없다.
  - 재무팀 권한이 유지된다. 부서 이동이 있을 때마다 이런 잔존 권한이 쌓인다.
- **벤더 동작(확인 필요)**: 이 형식은 Entra 의 SCIM 호환 플래그가 꺼진 구성에서 나온다. 앱 생성 시점이나 갤러리 앱 여부에 따라 기본값이 다를 수 있으니 테넌트별로 확인해야 한다.
- **왜 중요한가**: 표준에 없는 모양을 거절하는 것은 원칙적으로 맞다. 하지만 결과가 fail-open(권한 잔존)이고, 한 줄짜리 설정 누락이 조용한 이동자 권한 누적으로 이어진다.
- **권고**
  - `remove` + `value` 를 "열거한 멤버만 제거"로 받는다. 이 모양을 "전원 삭제"로 읽을 IdP 는 없으므로 의미가 모호하지 않다.
  - 받지 않겠다면 연결 시점에 강제한다. 예를 들어 테넌트 URL 의 플래그를 연결 검증으로 확인하고, 이 400 을 경보 지표로 올린다.

#### H6. SCIM 배포에서는 조직 계층(롤업)이 사실상 만들어지지 않는다 [코드 확인됨 / 벤더 동작은 의심(내 지식)]

- **근거**
  - `TupleMapper.java:98-116`: `child` 간선은 GROUP 타입 멤버에서만 나온다(`ScimMapper.java:111-122`).
  - `ScimRfcAttributes.java:48-49`, `ScimPatchApplier.java:326-333`: enterprise 확장의 `department`·`organization`·`division`·`manager` 는 받아서 버린다.
- **시나리오**
  - Entra ID 프로비저닝은 그룹 안의 그룹(중첩 멤버십)을 SCIM 앱으로 내보내지 않는다(문서화된 제한으로 알고 있다).
  - Okta Group Push 는 Okta 에 중첩 그룹이 없어서 그룹 안의 그룹을 보내지 않는다.
  - 그래서 Entra/Okta 로 연결하면 `child` 튜플이 하나도 생기지 않는다. README 의 핵심 약속인 "하위 조직 소속이면 상위 조직의 member"(`README.md:73-77`)가 SCIM 배포에서는 작동하지 않는다.
  - 실제 기업 조직을 담고 있는 속성(`department`·`manager`·`costCenter`)은 버려진다.
  - Google Workspace 는 내가 아는 한 범용 SCIM 클라이언트로 연결할 수 없다(카탈로그 앱만 지원).
- **왜 중요한가**: SCIM 배포에서 이 제품의 존재 이유인 롤업이 빠진다. 소비 앱은 상위 조직 Check 가 늘 false 라서 결국 자체 로직을 다시 만든다.
- **권고**: SCIM 배포의 "조직 원천"을 정의한다. 선택지는 셋이다.
  - (a) enterprise·커스텀 확장 속성의 조직 코드와, 별도 조직 마스터의 상위 관계를 매핑한다.
  - (b) HRIS(Workday 등)용 커넥터를 둔다.
  - (c) 조직 계층을 관리 API 나 정의 파일로 받는다.
  - 최소한 README 에 "Entra/Okta 로는 평면 그룹만 만들어진다"고 적는다.

#### H7. 감사 증적이 감사인의 질문에 답하지 못한다(행위자·사유·보관·부여 기록) [확인됨]

- **근거**
  - `AuditCause.java:12-32`: 원인은 소스·종류·runId/traceId 뿐이고 *행위자*가 없다.
  - `app-ldap/.../AdminSyncController.java:48-54`: `force` 에 요청자도 사유도 없다.
  - `app-ldap/src/main/resources/application.yml:80-83`: 스냅샷 7일, 실행 이력 30일, 변경 이력 30일이고, 테이블 TTL 이 지운다.
  - `LedgerAlignment.java:32-33`, `TupleReconciler.java:93`: 재적재와 장부 맞추기로 생긴 *부여*는 기록하지 않는다.
  - 이력은 앱이 쓰기 권한을 가진 같은 테이블에 있다(`DynamoDbAuditLog.java:40-42,105`). 변경 불가 저장소가 아니다.
- **시나리오**: SOX/ISO 감사에서 다음을 요청받으면 답할 수 없다.
  - "지난 12개월 퇴사자 전원이 몇 시간 안에 권한을 잃었나" — 30일이 지나면 기록이 없다.
  - "누가 언제 왜 삭제 가드를 우회했나" — 알 수 없다.
  - "3월 2일 기준 재무본부 접근자 목록" — 스냅샷은 7일만 남는다.
  - 재적재로 부여된 권한은 언제 생겼는지 이력에 없다.
- **왜 중요한가**: 회수의 적시성 증명과 특권 작업 추적은 IAM 감사의 기본 질문이다.
- **권고**
  - 이력에 행위자(인증 주체), 사유·티켓, 디렉터리에서 변경된 시각 대비 회수 시각(지연)을 남긴다.
  - 1년 이상을 append-only 저장소(S3 Object Lock 등)나 SIEM 으로 내보낸다.
  - 재적재 부여도 쓰기 전에 Read 결과와 비교해 "실제로 새로 생긴 것"을 판별하고 기록한다.
  - 특정 시점 접근을 재구성할 수 있도록 스냅샷을 오래 보관한다. 압축 묶음이라 저렴하다.

### Medium

#### M1. PUT 에서 `active` 를 빼거나 `remove active` 를 보내면 비활성 직원이 다시 활성화된다 [코드 확인됨 / 영향은 IdP 에 따라 다름]

- **근거**: `ScimMapper.java:51-52` 에서 `active == null` 이면 true 다. `ScimUserHandler.java:62-71` 의 PUT 은 전체 교체다. `ScimPatchApplier.java:367-368` 은 `remove` 를 true 로 만든다.
- **시나리오**: 비활성화된 퇴사자에게 HR 연동 SCIM 클라이언트가 프로필만 담은 PUT(`active` 생략)을 보낸다. `active=true` 가 되고, 보존된 멤버십 튜플이 모두 다시 생긴다. Okta 는 PUT 에 늘 `active` 를 싣지만 모든 클라이언트가 그렇지는 않다.
- **권고**: PUT 에서 `active` 가 없으면 기존 값을 유지한다. RFC 7644 §3.5.1 은 기본값 부여를 허용할 뿐 강제하지 않는다. `remove active` 는 false 로 보거나 400 으로 거절한다. 재활성화(false→true)는 감사 이벤트로 따로 표시한다.

#### M2. 비활성 기간에도 멤버십이 보존되어, 재활성·재연결 때 옛 권한이 통째로 되살아난다 [코드 확인됨 / IdP 동작은 의심]

- **근거**: `TupleMapper.java:131-133` 은 비활성이면 튜플만 뺀다. `IncrementalSyncUseCase.java:244-262,1165-1169` 는 활성화될 때 소속 전체를 다시 계산한다.
- **시나리오**
  - (a) Entra 에서 사용자가 범위를 벗어나면 `active=false` 가 온다. 범위 밖에 있는 동안의 그룹 변경은 이 앱으로 내보내지지 않는 것으로 알고 있다. 6개월 뒤 재입사로 `active=true` 가 오면, 그 사이 IdP 에서 빠진 그룹까지 퇴사 당시 멤버십이 모두 복원된다.
  - (b) Okta 에서 로그인을 재사용하면, `userName` 매칭으로 새 사람이 옛 SCIM 사용자에 연결되어 옛 사람의 멤버십을 물려받을 수 있다.
  - README 의 "옛 id 의 권한을 물려받지 않는다"(`README.md:84-87,704-707`)는 DELETE 된 경우에만 성립한다.
- **권고**
  - 비활성화 후 N일이 지나면 멤버십을 정리하는 옵션을 둔다. 또는 재활성화 시 멤버십을 "미확인"으로 두고, IdP 가 다시 주장할 때까지 튜플을 만들지 않는다.
  - 재활성화를 접근 검토 대상으로 표시한다.

#### M3. 전역 쓰기 락 하나가 클러스터 전체 SCIM 쓰기를 직렬화한다 — 대형 작업 중에는 퇴사 요청이 503 이다 [코드 확인됨 / IdP 반응은 의심]

- **근거**
  - `IncrementalSyncUseCase.java:737-809`: 모든 쓰기가 같은 락을 쓴다.
  - `DynamoDbProperties.java:29`: 획득 대기는 3초다.
  - `LockUnavailableException.java:17,60`: 재적재나 동기화가 락을 쥐고 있으면 곧바로 503(Retry-After 60초)이다.
  - README 도 처리량 상한이 서버 수와 무관하다고 적었다(`README.md:850`).
- **시나리오**
  - 10만 명 조직 DELETE, 초기 대량 프로비저닝, `mode=tuples` 재적재가 도는 동안 모든 SCIM 쓰기가 503 이다. 그 사이 온 비활성화는 IdP 재시도 정책에 맡겨진다(H4 와 겹친다).
  - 요청 하나가 락 안에서 DynamoDB 왕복 8~10회와 OpenFGA 호출 0~2회를 한다. 클러스터 처리량은 초당 수십 건 이하일 것으로 추정한다(측정하지 않았다).
- **권고**
  - 락을 직원·조직 키 단위로 쪼갠다. 직원 비활성화는 그 직원 키만 잡으면 된다.
  - 회수 요청을 우선하는 큐를 둔다.
  - 대형 작업은 묶음 사이마다 락을 양보한다.
  - 10만 명 초기 적재의 실제 처리량을 측정한다.

#### M4. AD 밖 디렉터리의 '막힌 계정' 신호가 좁고, 못 읽으면 전원 활성(fail-open)이다 [확인됨]

- **근거**: `AccountStatus.java:22-24,49-51` 에서 속성이 없으면 막히지 않은 것이다. `UserAttributes.java:16-19` 이 요청하는 상태 속성은 UAC·`accountExpires`·`pwdAccountLockedTime` 뿐이다.
- **시나리오**
  - 389DS·RHDS·FreeIPA 는 `nsAccountLock: TRUE` 로 계정을 막는데, 이 속성을 읽지 않는다. 막힌 퇴사자가 활성으로 남는다.
  - AD LDS 의 `msDS-UserAccountDisabled`, posix `shadowExpire` 도 같다.
  - AD 에서 동기화 계정의 ACL 이 UAC 를 읽지 못해도(위임 축소, 보호된 계정) 오류 없이 전원이 활성이 된다.
- **권고**
  - 디렉터리별 "막힘 규칙"을 설정으로 받는다(속성과 값의 매처).
  - "AD 인데 UAC 를 가진 엔트리가 0" 같은 읽기 권한 이상은 회차 실패로 처리한다.

#### M5. 대량 '부여'에는 가드가 없다 [확인됨]

- **근거**: `DeletionGuard.java:23-25` 는 지울 수만 센다. `FullSyncUseCase.java:126-132` 도 같다. `TupleReconciler.java:98-104` 는 쓰기를 먼저 모두 하고 가드는 지우기에만 건다.
- **시나리오**
  - `group-search-base` 를 도메인 루트로 넓히거나 필터를 실수로 바꾼다.
  - 기본 제공 그룹, 배포 그룹, Domain Users 같은 그룹이 조직이 되어 수만 명에게 새 `member` 가 생기고, 경고 없이 반영된다.
  - `primaryGroupID` 처리 때문에 Domain Users 가 범위에 들어오면 전원이 그 조직과 상위 조직의 멤버가 된다(`README.md:509`).
- **권고**: 부여 쪽에도 가드를 둔다. 새 조직의 멤버 수나 한 사람의 새 소속 수가 급증하면 멈춘다. 첫 적재는 dry-run 리포트를 낸 뒤 승인을 받는다.

#### M6. 주기적 대조가 없고, 대조 결과도 로그 한 줄뿐이다 [확인됨]

- **근거**
  - `FullSyncUseCase.java:117-142`: 스냅샷만 비교하고 OpenFGA 실제 상태를 보지 않는다.
  - `SnapshotArchiveUseCase.java:119,129,140-145`: 어긋남은 WARN 로그이고 회차는 SUCCEEDED 다.
  - README 도 드리프트를 감지할 수 없다고 적었다(`README.md:41-44`).
- **시나리오**: 누군가 OpenFGA 에 튜플을 직접 쓰거나(C1 이 있으면 누구나 가능), OpenFGA 를 백업에서 복원한다. LDAP 배포는 이것을 영원히 모른다. SCIM 배포는 하루에 한 번 로그에만 남고 아무도 보지 않는다.
- **권고**
  - LDAP 은 이미 있는 장부 훑기를 주 1회 이상 스케줄로 돌린다.
  - 어긋남 건수를 지표와 회차 상태로 올린다. 예를 들어 `DRIFT` 같은 상태를 둔다.
  - `extra`(있어서는 안 될 권한)의 자동 회수를 옵션으로 둔다.

#### M7. 로그인 사용자를 employeeId 로 바꾸는 일을 소비 앱에 떠넘기고, 그 경로가 인증 없는 접두사 검색이다 [확인됨]

- **근거**
  - `README.md:336-345`
  - `AdminQueryController.java:37-54`: 접두사 검색이다.
  - `GroupOfNamesStrategy.java:219`: `userName` 은 로그인 속성 원본이고 유일성 검증이 없다.
  - `GroupOfNamesStrategy.java:90-91`: LDAP 의 `externalId` 는 DN 이라 이동·개명 때 바뀐다.
- **시나리오**
  - OpenLDAP 에서 `uid` 가 중복되거나(지사 둘), 대소문자만 다른 계정이 있다. 앱이 `?userName=jkim` 결과에서 엉뚱한 줄을 골라 다른 사람의 id 로 Check 하면 잘못 허용하거나 잘못 거부한다.
  - DN 으로 매핑한 앱은 OU 이동 뒤 매핑이 깨진다.
  - 매 로그인이 관리 API 가용성에 묶인다.
- **권고**
  - 토큰에 실리는 불변 id(Entra `oid`/`onPremisesImmutableId`, Okta user id, AD `objectGUID`)로 정확히 일치를 찾는 조회를 1급 API 로 만들고 인증을 붙인다.
  - 또는 튜플의 user id 자체를 IdP 불변 id 로 맞추는 방안을 검토한다.
  - LDAP `userName` 의 유일성 위반을 탐지한다.

#### M8. 조직 모델이 '그룹 = 조직' 또는 'OU 트리 = 조직' 하나뿐이다 [확인됨]

- **근거**: `LdapConfig.java:83-88` 에서 배포마다 전략이 하나다. `DitStrategy.java:109-135` 에서 직원은 부모 OU 하나에만 속한다. `GroupOfNamesStrategy.java:59-72` 에서는 필터로 고른 모든 그룹이 조직이 된다.
- **시나리오**
  - 대기업은 HR 조직(부서 코드, 상위 부서, 매니저)을 AD 속성(`department`, `company`, `manager`, `extensionAttributeN`)에 두고, 그룹은 권한·메일용으로 쓰는 경우가 많다.
  - 이 모델은 둘을 같은 `group` 타입에 섞거나(groupOfNames), 관리 위임용 OU 를 조직으로 오인한다(DIT).
  - DIT 는 겸직·파견·매트릭스 조직을 표현하지 못한다. 조직과 보안 그룹을 한 배포에서 함께 쓸 수도 없다.
- **권고**: "속성 기반 조직" 전략(사용자 속성의 조직 코드 + 조직 마스터의 상위 관계)을 추가한다. 그룹 전략과 한 배포에서 함께 쓰되, OpenFGA 타입은 `org`/`group` 으로 나눈다.

#### M9. OpenFGA store 를 독점하고 부팅마다 모델을 덮어쓴다 — 소비 앱이 같은 store 에 자원 관계를 결합할 수 없다 [확인됨]

- **근거**
  - `StoreBootstrapper.java:117,266-281`: store 를 해석할 때마다 `writeAuthorizationModel` 을 부른다.
  - `authz-openfga/src/main/resources/authorization-model.fga`: 모델에는 user 와 group 만 있다.
  - `TupleReconciler.java:98-110`: 있어야 할 줄(D)에 없는 줄은 전부 지운다.
  - `README.md:205-207`
- **시나리오**
  - 문서 앱 팀이 같은 store 에 `document#viewer: [group#member]` 타입과 튜플을 추가한다.
  - app-ldap 이 재시작하면 최신 모델이 user/group 만으로 바뀌어 그 팀의 Check 가 오류나 거부가 된다.
  - 재적재는 그 팀의 튜플을 지운다.
  - OpenFGA 는 store 사이 관계를 잇지 못하므로, 소비 앱은 "조직 멤버인가"만 묻고 나머지는 직접 구현해야 한다.
- **권고**: 둘 중 하나를 고른다.
  - 모델 병합(modular models)을 지원한다.
  - "조직 전용 store, 소비 앱은 contextual tuples 나 2단계 Check" 를 공식 계약으로 만든다.
  - 어느 쪽이든 장부 훑기의 범위를 이 서버가 소유한 타입과 관계로 제한한다.

#### M10. LDAP 연결에 보안·가용성 설정이 없다 [확인됨]

- **근거**
  - `LdapConfig.java:24-31`: URL 이 하나뿐이고 StartTLS·신뢰 저장소·풀 설정이 없다.
  - `LdapProperties.java:14-17`
  - `app-ldap/src/main/resources/application.yml:30-33`: 평문 `ldap://` 와 평문 비밀번호다.
- **시나리오**
  - 기본값대로 389 포트에 단순 bind 하면 서비스 계정 비밀번호와 전 직원 디렉터리가 평문으로 오간다.
  - 최근 AD 는 LDAP 서명과 채널 바인딩 요구가 강해지는 추세라(내 지식), 평문 단순 bind 가 거절될 수 있다.
  - DC 하나가 내려가면 그 회차가 실패하고, H1 과 겹쳐 하루를 잃는다.
- **권고**: ldaps·StartTLS 를 강제하는 옵션, 여러 DC URL(또는 DNS SRV), 시크릿 매니저 연동을 넣고, 헬스 상세는 비공개로 바꾼다.

#### M11. 운영 가시성: '마지막 성공 동기화 시각'과 '건너뛴 엔트리 수'가 지표에 없다 [확인됨]

- **근거**: `SyncMetrics.java:20-43` 에는 timer 와 counter 만 있고 신선도 게이지가 없다. `SkippedEntries.java:12-17` 은 경고 로그 한 줄만 남긴다. `SnapshotArchiveUseCase.java:140-145` 도 로그다.
- **시나리오**
  - 매일 FAILED/ABORTED 가 이어져도, 경보 규칙을 따로 짜지 않으면 아무도 모른다.
  - 사용자 검색 베이스가 지사 하나를 빠뜨려 2만 명을 건너뛰어도 로그 한 줄이다. 첫 적재라면 가드도 없다.
- **권고**: `last_success_timestamp{source}` 게이지, 건너뛴 엔트리 수와 대조 실패한 member 수 지표를 추가하고, 기본 알림 규칙을 함께 배포한다.

### Low

#### L1. AD 권장 그룹 필터가 배포 그룹·기본 제공 그룹을 조직으로 만들고, 외부 보안 주체(FSP) 멤버는 조용히 빠진다 [확인됨]

- **근거**: `LdapProperties.java:73-74` 와 `application.yml:49` 가 `(objectClass=group)` 을 권한다. DN 이 대조되지 않은 멤버는 요약 로그만 남는다(`GroupOfNamesStrategy.java:144-145`).
- **권고**
  - 보안 그룹만 고르는 필터를 권한다: `(&(objectClass=group)(groupType:1.2.840.113556.1.4.803:=2147483648))`.
  - `isCriticalSystemObject` 인 그룹은 뺀다.
  - FSP 는 `objectSid` 로 해석하거나 따로 센다.

#### L2. 모르는 설정 키를 조용히 무시한다 [확인됨]

- **근거**: `LdapProperties.java:11` 은 `ignoreUnknownFields` 기본값(true)을 쓴다. README 도 같은 동작을 적었다(`README.md:496-498`).
- **시나리오**: `group-filtr` 같은 오타가 있으면 기본 필터로 다른 엔트리를 읽는다. 첫 적재라면 가드도 없다.
- **권고**: `ignoreUnknownFields=false` 와 `@Validated` 를 건다.

#### L3. 순환을 끊는 방식이 AD 중첩 그룹의 의미와 다르다(거부 쪽) [확인됨]

- **근거**: `TupleMapper.java:144-177`
- **시나리오**: AD 에 흔한 A⊂B⊂A 순환에서, AD 토큰은 양쪽 멤버 모두에게 두 그룹을 준다. 여기서는 조직 id 순서로 간선 하나를 버려 한쪽 멤버가 다른 쪽 권한을 받지 못한다. 거부 쪽이라 위험은 낮지만 "AD 와 판정이 다르다"는 문의가 생긴다.
- **권고**: 강연결 성분(SCC)을 동치로 접어 표현하거나, 최소한 지표와 관리 화면에 노출한다.

#### L4. OpenLDAP 에서 refint 없이 개명·이동하면 멤버가 조용히 빠진다 [의심]

- **근거**: `GroupOfNamesStrategy.java:129-146` 에서 DN 대조에 실패하면 요약 경고만 남는다. `UnmatchedMemberGuard.java:41-49` 는 전원이 대조 실패할 때만 멈춘다.
- **시나리오**: ModDN 뒤 그룹의 `member` 값이 옛 DN 으로 남으면, 다음 회차에 그 사람의 소속이 회수된다(줄어드는 쪽). AD 는 링크 속성이라 자동으로 고쳐진다.
- **권고**: refint 오버레이가 필요하다는 전제를 문서화하고, 대조 실패 수를 지표와 임계치로 관리한다.

---

## 5. 질문 — 답에 따라 판단이 바뀌는 것

1. 목표 퇴사 SLO 는 얼마인가(예: 해고 15분, 일반 퇴사 4시간)? H1·H2 의 등급과 해법(주기 단축 vs 증분 vs 긴급 회수)이 이 답에 달려 있다.
2. 소비 앱은 OpenFGA Check 결과를 캐시하는가? 신원 매핑(M7)은 실제로 어떻게 할 계획인가?
3. 첫 연결 대상 IdP 와 구성은 무엇인가? Entra 라면 `aadOptscim062020` 을 확인할 수 있는가? Okta 라면 5xx 를 자동 재시도하는지 관리자 Task 로 쌓는지 확인했는가(H4, H5)?
4. OpenFGA store 를 소비 앱의 자원 모델과 공유할 계획인가, 조직 전용인가(M9)?
5. SCIM 배포의 조직 계층은 어디서 올 것으로 보는가 — Entra/Okta 인가, 별도 HRIS 커넥터인가, 커스텀 SCIM 클라이언트인가(H6)?
6. 조직 개편처럼 정상적인 대량 변경으로 가드가 걸렸을 때의 런북은 무엇인가? `force` 는 누가 승인하는가(H2)?
7. 관리 API 와 SCIM 엔드포인트는 어떤 네트워크 경계 뒤에 놓이는가? 인증 전 단계에서 mTLS·사설 엔드포인트로 임시 보호할 계획이 있는가(C1)?
8. 감사 보관 요구(SOX·ISO·내부 규정)는 몇 년인가(H7)?
9. OpenFGA·DynamoDB 의 백업과 복원 런북에 "복원 뒤 장부 훑기"가 들어 있는가(H3, M6)?
10. LDAP 쪽 원천은 AD 하나인가, 여러 포리스트·도메인인가? 여러 도메인이면 RID 대조(`primaryGroupID`)와 커넥터 하나의 제약을 다시 봐야 한다.
11. Google Workspace 를 대상으로 생각하는가? 내 지식으로는 범용 SCIM 클라이언트로 연결할 수 없다.

## 6. 상위 5개 — 먼저 고칠 순서

1. **C1 인증·인가 부재**: SCIM 토큰과 OAuth, 관리 API 의 SSO+RBAC, OpenFGA 자격 증명.
2. **H1 LDAP 퇴사 반영이 하루 1회**: 회차를 분 단위로, 빠른 재시도, 긴급 회수 API, 신선도 경보.
3. **H2 삭제 가드가 퇴사자 회수까지 멈춤**: 명시적으로 막히거나 지워진 계정의 회수는 가드 밖에서 처리.
4. **H4 SCIM 비활성화 실패 시 회수 의도가 사라짐**: 의도와 반영을 분리하고 서버가 끝까지 재시도. M6 의 주기적 대조와 함께 고친다.
5. **H5 Entra 레거시 멤버 제거를 400 으로 거절**: 열거한 멤버만 제거하는 것으로 받거나, 연결 시점에 강제.

바로 다음 순위: H3(상태 유실 뒤 남은 권한 — 기준선이 없으면 장부 훑기), H7(감사 증적), H6(SCIM 조직 원천 정의).
