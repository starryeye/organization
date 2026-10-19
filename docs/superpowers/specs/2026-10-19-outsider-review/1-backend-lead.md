# 외부 코드 리뷰 — 백엔드 엔지니어링 리드 관점 (2026-10-19)

- 대상: `/Users/starryeye/study/organization/.claude/worktrees/spike-ldap-5k` (브랜치 `scim-standard-rest` 시점의 작업 트리)
- 범위: 8개 모듈의 `src/main` 전부(약 1.9만 줄), 필요한 곳의 `src/test`·`src/testFixtures`, `README.md`, 설정 파일. `docs/superpowers/`·`.superpowers/`·git 이력은 읽지 않았다.
- 방법: 코드 읽기만 했다. 빌드·테스트·컨테이너는 돌리지 않았다. OpenFGA SDK 기본값은 Gradle 캐시의 `openfga-sdk-0.9.11.jar` 를 `javap` 로 확인했다(읽기 전용).
- 아래 경로는 저장소 루트 기준이다. `core/.../usecase/X.java` 는 `core/src/main/java/dev/starryeye/organization/core/usecase/X.java` 를 줄인 것이다.

---

## 1. 이 시스템을 어떻게 이해했나

1. 디렉터리(LDAP 또는 SCIM IdP)의 "누가 어느 조직 소속인가"를 OpenFGA 튜플(`user:U direct_member group:G`, `group:C child group:P`)로 옮기는 동기화 서버다. 인가 판단은 하지 않고, 다른 앱이 OpenFGA 에 `member` 를 Check 한다(롤업은 인가 모델이 한다).
2. 배포 하나는 커넥터 하나다 — `app-ldap`(pull: 주기적으로 전체를 읽어 직전 "튜플 스냅샷"과 diff) 또는 `app-scim`(push: 요청 하나의 영향 범위만 전후 그림으로 만들어 OpenFGA Check 결과를 기준선으로 diff).
3. 쓰기 순서는 늘 "OpenFGA 먼저 → 실제로 반영된 것만 DynamoDB(스냅샷·현재상태)에 커밋"이다. 스냅샷은 gzip 묶음, 현재상태는 단일 테이블(직원·조직 META, 멤버 줄 + 역방향 소속 줄, GSI1~3).
4. 클러스터 전체에서 쓰기는 DynamoDB 조건부 쓰기 리스 락 하나(`LOCK#MUTATION`)로 직렬화한다. SCIM 쓰기·재적재·LDAP 동기화가 모두 이 락을 쓰고, 리스는 하트비트로 갱신한다.
5. 재적재(rebuild)는 store 를 지우지 않고 "있어야 할 줄을 다 쓰고 → OpenFGA Read 로 장부를 훑어 → 없어야 할 줄만 지운다". LDAP 은 삭제 가드(30%)를 둔다.
6. OpenFGA 쓰기는 세 단계(조직 연결 지우기 → 직원 줄 → 조직 연결 쓰기)로 나가 단조 모델에서 중간 상태가 앞·뒤 어느 쪽보다 넓은 권한을 주지 않게 한다.
7. 회차는 HTTP 요청과 떼어 돌고(202 + 실행 기록), 감사 이력은 대상별 파티션에 남는다. 관리 조회 API 는 상태가 요구하는 값과 실제 Check 결과를 나란히 보여준다.
8. 전 구간이 Reactor/WebFlux 비동기다. LDAP(JNDI)만 블로킹이라 `boundedElastic` 에 격리했다.

## 2. 총평과 운영 승인 여부

**판정: 지금 상태로는 운영 승인하지 않는다(조건부 불승인).**

엔지니어링 수준 자체는 높다. 부분 실패·재시도·리스 상실·최종 일관성 인덱스·LDAP 페이징 같은, 보통 운영 사고가 나서야 배우는 것들을 코드와 테스트로 미리 막아 둔 흔적이 곳곳에 있다. 문제는 "얼마나 꼼꼼한가"가 아니라 아래 몇 가지가 **인가 시스템으로서의 최소 조건**에 걸린다는 점이다.

승인 전에 반드시 해야 할 것:

1. **모든 쓰기·관리 엔드포인트에 인증·인가**(C1). 지금은 네트워크에 닿는 누구나 임의 직원을 임의 조직에 넣거나 조직도를 통째로 지울 수 있다.
2. **"반영됐는지 모르는" OpenFGA 쓰기를 추적**하고, 장부 전체를 주기적으로 대조하는 자동 장치(H1). 지금은 드문 고장 하나가 회수되지 않는 권한을 영구히 남기고, 그것을 찾을 신호가 없다.
3. **회수(revocation) 지연의 상한**(H2). 기본 하루 1회 주기에, 삭제 가드가 걸리면 사람이 풀 때까지 퇴사자 회수를 포함한 모든 변경이 멈춘다.
4. SCIM `active` 누락·remove 를 활성으로 해석하는 fail-open(M1), 운영 프로필의 위험한 기본값(M5) 수정.
5. 실제 AWS(DynamoDB·OpenFGA 운영 구성)에서 10만 명 규모 부하·장애 주입 시험 — OpenFGA 재시작을 동기화 도중에 넣기, DynamoDB 스로틀·GSI 역압, 다중 인스턴스 SCIM 쓰기 경합. DynamoDB Local 은 파티션 한도·스로틀·GSI 역압을 재현하지 못한다.

## 3. 잘한 점

- **커밋 규칙과 "기록 중" 표시.** OpenFGA 에 먼저 쓰고 실제 반영분만 스냅샷에 남기며, 쓰기 직전 포인터에 표시를 남겨 쓰기 후·기록 전 중단을 다음 회차가 장부 훑기로 복구한다(`core/.../usecase/FullSyncUseCase.java:93-95,134-140`, `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:92-99,135-141,166-176`). 스냅샷 저장 순서(메타 → 묶음 → 포인터)와 최신 스냅샷을 건너뛰는 정리(`:290-306`), 기준선을 온전히 못 읽으면 빈 기준선으로 넘어가지 않고 멈추는 것(`SnapshotIntegrityException`, `:192-244`)도 정확하다.
- **store 를 버리지 않는 재적재.** 쓰기 → 흘려 읽는 훑기 → 지우기 전 확인(가드·빈 조직도) → 지우기 순서이고, 장부 전체를 메모리에 모으지 않는다(`core/.../usecase/TupleReconciler.java:95-114,187-206`). GSI 열거 지연으로 막 만든 대상을 지우지 않도록 본 테이블 강한 읽기로 보강하는 것(`ScimRebuildUseCase.java:155-193`)은 쉽게 놓치는 부분이다.
- **OpenFGA 쓰기 단계화와 400 격리.** 단조 모델 위에서 단계 순서로 중간 상태의 권한 확대를 막고, 트랜잭션 쓰기·멱등 옵션을 못박았으며, 거절(400)된 배치를 대상 경계로 반씩 쪼개 나쁜 줄만 실패로 남긴다(`authz-openfga/.../OpenFgaRelationTupleWriter.java:287-296,406-428,456-461`). 연속 실패 차단기가 이미 나간 묶음의 결과를 기다린 뒤 멈추는 것도 좋다.
- **리스 락 구현.** 토큰 조건 반납·갱신, 같은 토큰 재획득으로 응답을 잃은 획득을 자기 락이 막지 않게 한 것(`storage-dynamodb/.../DynamoDbMutationLock.java:63-92`), 갱신 한도를 단조 타이머·"보낸 시각" 기준으로 잰 것(`core/.../usecase/LeaseKeeper.java:89-118`), OpenFGA 쓰기 직전·커밋 직전 리스 재확인(`IncrementalSyncUseCase.java:967-979`).
- **Check 기준선의 엄격함.** `HIGHER_CONSISTENCY`, correlationId 일대일 소진, 누락·개별 오류를 "없음"으로 격하하지 않는 것(`authz-openfga/.../OpenFgaRelationTupleChecker.java:159-210`).
- **역참조 불변식.** 넣을 때 소속 줄 먼저, 뺄 때 멤버 줄 먼저 — 어긋나도 "안전한 쪽"으로만 어긋나게 하고, 역참조는 강한 읽기 + 멤버 줄 확인으로 정확하게 만든다(`DynamoDbDirectoryStateRepository.java:456-472,694-714`).
- **LDAP 읽기의 함정 처리.** 페이징을 한 커넥션에서 돌리고, 응답 컨트롤 없는 응답·크기 한도 잘림·referral 을 조용히 삼키지 않는다(`connector-ldap/.../PagedLdapSearch.java:60-92`, `LdapTemplates.java:50-60`). 필요한 속성만 요청한다(`:184-195`). 헬스 프로브가 AD 서비스 계정을 잠그지 않게 한 것(`app-ldap/.../LdapHealthIndicator.java:66-100`)도 실제 운영 경험이 녹아 있다.
- **큰 응답의 스트리밍과 배압.** 10만 명 조직 응답을 쪽 단위로 흘려 쓰고, 페이지네이터 prefetch 를 1 로 묶어 느린 클라이언트가 조직 전체를 끌어오지 않게 했다(`connector-scim/.../ScimGroupStream.java:56-108`, `storage-dynamodb/.../Paginator.java:42-53`).
- **오류 분류.** 일시 장애 503 + Retry-After / 요청 오류 4xx / 버그 500 을 원인 사슬로 가르고, 인증 없는 엔드포인트로 내부 메시지를 내보내지 않는다(`connector-scim/.../ScimRouter.java:150-194`, `TemporaryFailureClassifier.java:34-52`).
- **유일성을 GSI 가 아니라 조건부 표지판으로**(`core/.../usecase/UniqueMarkers.java`, `storage-dynamodb/.../DynamoDbUniqueMarkers.java`) — 최종 일관성 인덱스로 유일성을 판단하는 흔한 실수를 피했다.
- **시험.** 테스트 클래스 215개, Testcontainers 기반 e2e, 저장소 경로의 10만 건 규모 시험(`ReplaceWithScaleTest`, `BulkPathScaleTest`, `AuditLogScaleTest`, `ScimLargeGroupReadScaleTest` 등), 락·큐를 가상 시간으로 시험할 수 있게 만든 설계.

## 4. 발견 사항

심각도 기준: Critical(데이터 손실·보안 구멍·장애·틀린 인가 결과), High, Medium, Low. 각 항목에 **확인됨**(코드로 추적함) 또는 **의심**(그럴듯하나 끝까지 추적하지 못함)을 단다.

### Critical

#### C1. 인증·인가가 전혀 없다 — 누구나 권한을 주고, 조직도를 지우고, 가드를 우회한다 — **확인됨**

- 근거
  - SCIM 쓰기 라우트 전부에 인증 필터가 없다: `connector-scim/.../ScimRouter.java:89-117`. ServiceProviderConfig 도 `authenticationSchemes: []` 로 선언한다(`:252`). 모듈 어디에도 `WebFilter`·Spring Security 의존성이 없다(`app-*/build.gradle`).
  - `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>`: 확인값이 테이블명뿐이고(`app-scim/.../AdminSyncController.java:79-86`), 기본 테이블명 `organization-scim` 은 설정과 README 에 공개돼 있다(`app-scim/src/main/resources/application.yml:29`).
  - `POST /admin/sync/full?force=true`·`/rebuild?force=true` 로 삭제 가드를 끈다(`app-ldap/.../AdminSyncController.java:48-71`).
  - 관리 조회·이력 API 가 이름·이메일·이전 값(PII)을 그대로 준다(`admin-api/.../AdminQueryController.java`, README "변경 이력").
  - app-ldap 은 `/actuator/health` 를 `show-details: always` 로 열어 LDAP URL·base DN·테이블명·OpenFGA 주소를 공개한다(`app-ldap/src/main/resources/application.yml:89-92`, `LdapHealthIndicator.java:88-92`, `DynamoDbHealthIndicator.java:35-38`).
- 시나리오: 내부망의 아무 단말이 `PATCH /scim/v2/Groups/<임원조직 id>` 로 `{"op":"add","path":"members","value":[{"value":"<자기 employeeId>"}]}` 를 보낸다 → 락 안에서 정상 처리되어 OpenFGA 에 `direct_member` 가 쓰이고, 그 조직과 모든 상위 조직의 `member` Check 가 참이 된다. 또는 `mode=wipe&confirm=organization-scim` 한 줄로 SCIM 조직도와 장부 전체가 지워진다.
- 왜 중요한가: 이 서버가 쓰는 데이터가 곧 다른 앱들의 인가 결과다. 인증 없는 쓰기 API 는 인가 시스템 전체의 권한 상승 경로다. 인증 없는 조회도 무거운 읽기(아래 L3, 첫 페이지마다 전체 COUNT, 직원 상세 하나에 Check 최대 200번)를 익명에게 열어 비용 증폭 공격면이 된다.
- 권장: SCIM 은 RFC 7644 §2 대로 IdP 별 Bearer 토큰(또는 mTLS)과 토큰 회전. 관리 API 는 별도 포트·별도 인증(OIDC + 역할), 파괴적 작업(wipe·force)은 2인 승인 또는 별도 권한. actuator 는 관리 포트로 분리하고 details 는 인증된 경우만. README 가 이것을 "앞단에서 보호"로 미뤘지만, 앞단 설정 하나의 실수가 곧 전사 권한 상승이므로 애플리케이션 안에서도 막아야 한다(심층 방어).

### High

#### H1. "반영됐는지 모르는" 쓰기를 "없음"으로 기록한다 — 회수되지 않는 권한이 영구히 남을 수 있고, 이를 찾는 자동 장치가 없다 — **확인됨**(코드 경로), 발생 빈도는 **의심**

- 근거
  - OpenFGA 쓰기가 400 이 아닌 오류로 끝나면(재시도 후) 그 배치의 줄은 전부 "실패"로만 기록되고 "쓰였을 수도 있음"은 남지 않는다: `authz-openfga/.../OpenFgaRelationTupleWriter.java:406-428`. 포트 문서도 "쓰고 응답을 잃은 묶음은 반영됐는지 몰라 실패로 센다"고 적는다(`core/.../port/RelationTupleWriter.java:23-29`).
  - **LDAP**: 새 스냅샷 = 기준선 − 지운 것 + 쓴 것(`core/.../usecase/FullSyncUseCase.java:166-182`). 실패한 쓰기는 기준선에 없는 채로 남고, 스냅샷 저장이 "기록 중" 표시를 지운다(`DynamoDbTupleSnapshotRepository.java:135-141`). PARTIAL 이든 차단기로 멈춘 FAILED 든 같다(`FullSyncUseCase.java:136-140`).
  - **SCIM**: 쓰기가 실패한 새 멤버는 멤버 줄을 저장하지 않는다(`IncrementalSyncUseCase.java:1065-1071`). OpenFGA 쓰기 뒤 리스 재확인이 실패해도 커밋 없이 503 이다(`:974-978`).
  - 이후의 회수는 전부 **상태(DynamoDB)에서 출발**한다: 직원 비활성화·삭제는 소속 줄로 조직을 찾고(`:1165-1169`, `:244-262`, `:512-541`), 조직 삭제는 저장된 멤버로 지울 줄을 만든다(`:579-584`). 드리프트 지표도 상태에서 뽑은 후보만 Check 한다(`:895-918`), 아카이빙도 마찬가지이고 결과는 로그 한 줄뿐이다(`core/.../usecase/SnapshotArchiveUseCase.java:112-119,140-147`). 관리 API 의 직원 상세도 상태의 소속에서 출발해 상태에 없는 조직은 보여주지 않는다(`core/.../usecase/AdminQueryUseCase.java:105-120`).
- 시나리오(LDAP): 03:00 동기화 도중 OpenFGA 파드가 재시작된다. 직전에 보낸 배치(최대 100줄 × 동시 4)는 서버에 커밋됐지만 응답을 잃고, 앱 재시도 3번도 서버가 내려간 동안 모두 실패한다 → 차단기가 서고 회차는 FAILED, 스냅샷에는 그 줄이 "없음"으로 기록되고 표시가 지워진다. 같은 날 그 배치에 있던 직원 kim 이 조직에서 빠진다(퇴사·이동). 다음 회차: 목표에도 기준선에도 그 줄이 없어 델타가 비고, `user:kim direct_member group:G` 는 OpenFGA 에 **영구히** 남는다 — kim 은 G 와 모든 상위 조직의 member 로 계속 판정된다. 사람이 재적재를 돌리기 전까지 아무 지표도 이것을 보지 못한다.
- 시나리오(SCIM): `PATCH /Groups/G add U` 처리 중 OpenFGA 가 커밋 뒤 응답을 잃거나, 쓰기 후 리스 재확인이 실패한다 → 튜플은 있고 멤버 줄은 없다, 503. IdP 가 재시도하기 전에 IdP 쪽에서 U 의 소속이 바뀌어 같은 add 를 다시 보내지 않으면, 나중에 U 를 `active=false` 로 바꿔도 소속 줄이 없으므로 그 튜플은 후보에 들지 않아 지워지지 않는다 — **비활성화된 직원이 G 권한을 계속 가진다.**
- 왜 중요한가: 확률은 낮지만 결과가 "틀린 인가(잔존 권한)"이고, 탐지 수단이 수동 재적재뿐이다. OpenFGA 재시작·장애는 운영에서 반드시 일어난다.
- 권장
  1. LDAP: 일시 오류(400 이 아닌 실패)나 차단기 멈춤이 하나라도 있으면 "기록 중" 표시를 지우지 않는다 — 다음 회차가 이미 있는 장부 훑기 경로(`LedgerAlignment`)로 정확히 맞춘다. 비용은 그런 회차 뒤 한 번의 훑기뿐이다.
  2. SCIM: OpenFGA 쓰기 전에 "보류 중 튜플(의도)"을 DynamoDB 에 남기고 커밋 때 지우는 아웃박스, 또는 커밋하지 못한 줄에 대한 즉시 보상 지우기(on_missing=IGNORE)와 실패 시 의도 기록.
  3. 두 앱 모두 주기적 자동 대조(장부 Read 훑기 → 상태와 비교 → 지표, 가능하면 자동 수정). 팀 원칙상 Read 를 재적재에만 쓰고 있지만, 탐지 전용 야간 훑기는 락 없이도 할 수 있다(쓰기 경합으로 생긴 거짓 양성은 다음 날 재확인으로 거른다). `extra` 건수는 반드시 지표로 낸다.
  4. "커밋 직후 응답 유실" 고장 주입 시험을 추가한다.

#### H2. 회수 지연에 상한이 없다 — 기본 하루 1회 주기에, 삭제 가드가 걸리면 사람이 풀 때까지 모든 변경(퇴사자 회수 포함)이 멈춘다 — **확인됨**

- 근거
  - 기본 주기는 하루 1회(`app-ldap/.../SyncProperties.java:14`, `application.yml:20`). README 도 "막힌 직후부터 다음 동기화까지는 권한이 남는다"고 적는다.
  - 비교 경로에서 가드가 걸리면 쓰기도 지우기도 하지 않고 ABORTED 다(`core/.../usecase/FullSyncUseCase.java:126-131`). 장부 맞추기 경로에서는 있어야 할 줄만 쓰고 지우기는 하지 않으며, 표시가 남아 다음 회차도 다시 걸린다(`LedgerAlignment.java:47-61`, README:236-238).
  - 가드는 지울 줄의 **원인**을 가리지 않는다 — 계정 비활성화(가장 강한 회수 신호)로 생긴 지우기도 비율에 함께 센다(`core/.../guard/DeletionGuard.java:32-48`). README 도 큰 조직 개명이 가드에 걸리면 "그동안 퇴사도 반영되지 않는다"고 적는다(README:462).
  - 알림은 `sync.guard.aborted` 카운터 하나다(`app-ldap/.../SyncMetrics.java`). 하루 한 번 오르는 카운터라 경보 규칙이 없으면 놓친다.
- 시나리오: 금요일 오후 대규모 개편으로 튜플의 35% 가 빠지게 된다 → 토요일 03:00 회차 ABORTED. 같은 금요일 해고된 직원 20명의 `userAccountControl` 비활성화도 같은 회차에 들어 있어 함께 멈춘다. 월요일 담당자가 `force=true` 를 걸 때까지 약 3일 동안 해고자가 권한을 유지한다. 신규 입사자 권한도 나가지 않는다.
- 왜 중요한가: 인가 동기화에서 회수 경로의 가용성은 곧 보안 통제다. "잘못된 대량 삭제를 막는다"는 목적이 "정상 회수까지 막는다"로 번진다.
- 권장: (a) 가드는 "디렉터리에서 사라진 대상"에만 걸고, 대상이 남아 있으면서 비활성화된 경우의 지우기는 늘 적용한다. (b) 쓰기는 가드와 무관하게 적용한다. (c) 전부/전무 대신 상한까지 적용하고 나머지를 보류하는 방식 검토. (d) 회수 SLA 를 정하고 주기를 그에 맞춘다(예: 시간 단위 전체 + AD `uSNChanged`/`whenChanged` 기반 비활성화 증분). (e) ABORTED·PARTIAL·"마지막 성공 후 N시간 경과"를 호출(page)되는 경보로.

### Medium

#### M1. SCIM 에서 `active` 가 빠지거나 remove 되면 활성으로 해석한다(fail-open) — **확인됨**(코드), IdP 별 발생 여부는 **의심**

- 근거: PUT·POST 본문에 `active` 가 없으면 `true`(`connector-scim/.../ScimMapper.java:51-52`). PATCH `remove active` 도 `true`(`ScimPatchApplier.java:367-368`, README 표 "User | active | remove(비움. active 는 '없음' = 활성)").
- 시나리오: 비활성화된 직원에 대해 IdP 가 전체 표현을 PUT 으로 보내면서 `active` 를 생략하거나(일부 IdP·커스텀 매핑은 false·미변경 불리언을 생략한다), 원천 속성이 비어 `remove active` 를 보낸다 → `changeUser` 가 활성으로 저장하고 모든 소속 튜플을 다시 쓴다 → 퇴사자 권한 복구.
- 왜 중요한가: RFC 7643 은 `active` 의 의미를 서비스 제공자에게 맡긴다. 제공자가 고를 수 있는 기본값 중 이 쪽이 권한을 여는 쪽이다.
- 권장: PUT 에서 `active` 가 없으면 **기존 값 유지**, PATCH `remove active` 는 비활성 또는 400. POST 의 기본값만 활성으로. 활성으로 뒤집힌 PATCH/PUT 은 감사 이력과 별개로 WARN·지표.

#### M2. LDAP 에서 온 긴 값(특히 조직 `description`)이 GSI 키 한도를 넘으면 상태 교체가 매 회차 통째로 실패한다 — **확인됨**(코드 경로), 실데이터 발생은 **의심**

- 근거: 조직 표시명 기본 속성이 `description` 이고(`app-ldap/src/main/resources/application.yml:52,60`, `GroupOfNamesStrategy.java:259`), 그 값을 소문자로 GSI1 정렬키에 넣는다(`DynamoDbDirectoryStateRepository.java:562-571`; 직원은 `:200-208` 의 GSI1SK·GSI2SK). 길이 검사(`KeyValueLimits`)는 SCIM 쓰기에만 있다(`IncrementalSyncUseCase.java:233,276`, README:630 "LDAP 에서 온 긴 값은 막지 않는다"). `replaceWith` 는 `flatMap` 이라 한 건의 오류가 같은 단계의 나머지를 취소한다(`:773-778`). 스냅샷은 그보다 먼저 저장되고(`FullSyncUseCase.java:179-181`), 다음 회차는 델타가 비어 "변경 없음" 경로에서 다시 `replaceWith` 를 부른다(`:121-125`).
- 시나리오: AD 그룹 하나의 설명이 한글 350자(약 1,050바이트)다 → UpdateItem 이 ValidationException → 회차 FAILED. 튜플은 맞게 반영됐지만 이후 매일 같은 자리에서 FAILED 가 되고, 현재상태(관리 API)·조직도 감사 이력·`sync.duration{status=SUCCEEDED}` 가 멈춘다. 운영자는 "LDAP 동기화가 매일 실패"로 보고 원인을 찾아야 한다.
- 권장: 키에는 바이트 상한으로 자른 값(+ 아이디 꼬리로 유일성)을 넣고 원본은 일반 속성으로. 다른 데이터 오류처럼 "건너뛰고 요약 한 줄" 정책을 이 경로에도 적용. 긴 `description` 시험 추가.

#### M3. 영구 거절 줄 하나가 매 회차 뒤 단계의 부여 전부를 막는다(블라스트 반경) — **확인됨**

- 근거: 앞 단계에 실패한 줄이 하나라도 있으면 뒤 단계의 쓰기를 보내지 않는다 — 400 거절로 확정된 실패도 포함한다(`OpenFgaRelationTupleWriter.java:97-104,151-159,205-207`). README 도 비용을 인정한다("첫 적재라면 하위 조직 연결 쓰기 전부, 곧 롤업 전체", README:889-890).
- 시나리오: 이름 기반 id 설정이나 데이터 오류로 직원 한 명의 줄이 OpenFGA 에서 400 으로 거절된다 → 첫 적재라면 ③단계(조직 연결 쓰기)가 전부 보류돼 **전사 롤업 권한이 하나도 생기지 않는다.** 데이터를 고칠 때까지 매 회차 PARTIAL 이다.
- 분석: 단계 규칙이 지키려는 성질(중간 상태가 앞·뒤보다 넓지 않음)을 깨는 것은 "실제로 남아 있을 수 있는 앞 단계의 지우기 실패"뿐이다. 400 으로 거절된 줄은 반영되지 않은 것이 확정이고, 형식 오류 줄은 애초에 장부에 존재할 수 없다. 쓰기 실패는 뒤 상태의 부분집합 쪽으로만 어긋난다.
- 권장: 뒤 단계 보류는 "일시 오류로 실패한(=불확실한) 지우기"가 있을 때만, 그리고 가능하면 그 대상이 걸친 하위 그래프로 한정한다. 400 거절 줄은 격리 목록·지표로 따로 다룬다.

#### M4. 전역 단일 락 — 쓰기 처리량 상한이 인스턴스 수와 무관하고, SCIM 쓰기에는 전체 기한이 없어 느린 의존성 하나가 클러스터 전체 쓰기를 수 분간 멈출 수 있다 — 구조는 **확인됨**, 수치는 **의심**

- 근거
  - 모든 SCIM 쓰기·재적재·동기화가 락 하나를 쓴다(`core/.../port/MutationLock.java:5-16`). 락을 쥔 채 순차 왕복이 최소 8번 이상이다: 획득 → 직원 읽기 → 소속 Query + 조직마다 확인 GetItem·헤더 GetItem → BatchCheck → 리스 확인 → OpenFGA Write → 리스 확인 → 커밋 → 반납(`IncrementalSyncUseCase.java:244-262,884-937,967-979`).
  - SCIM 쓰기 경로에 `timeout` 이 없다(`IncrementalSyncUseCase.java:737-809` — 코드 전체에서 `.timeout(` 은 `LeaseKeeper`·`LocalWriteQueue`·`SyncJobs` 에만 있다). 하트비트는 일이 도는 한 리스를 계속 늘린다(`LeaseKeeper.java:58-79`).
  - OpenFGA 호출은 SDK 기본 재시도 3회·읽기 타임아웃 10초(`StoreBootstrapper.java:302-312` 가 기본값 그대로 생성; SDK `Configuration` 기본값을 javap 로 확인) 위에 앱 재시도 3회가 겹친다(`OpenFgaErrors.java:38-42`, `OpenFgaRelationTupleWriter.java:387-397`). DynamoDB 클라이언트는 `apiCallTimeout` 이 없다(`DynamoDbConfig.java:26-36`).
- 시나리오: OpenFGA 가 느려져 호출마다 10초 타임아웃이 난다. PATCH 하나가 BatchCheck 와 Write 재시도(앱 4번 × SDK 시도 × 10초)로 수십 초~수 분 락을 쥔다. 그동안 모든 인스턴스의 SCIM 쓰기는 3초 대기 뒤 503(Retry-After 2초)이다. 정상 상태에서도 락 점유가 100ms 라면 클러스터 전체 상한은 초당 약 10건이고, 10만 명 초기 프로비저닝·대규모 개편은 수 시간과 대량의 503 이 된다. Okta 의 5xx 처리는 README 도 "문서에 없다"고 적는다(README:789) — 수동 재시도 작업이 쌓일 수 있다.
- 권장: 락 안의 일에 요청 단위 기한(예: 15~20초)을 두고 넘으면 리스를 놓고 503. 재시도 계층을 하나로(SDK 재시도를 끄거나 앱 재시도를 끄거나). OpenFGA 상태를 보는 차단기로 장애 중에는 락을 잡기 전에 빠르게 503. 실제 AWS 에서 락 점유 p50/p99 와 초당 처리량을 재고, 상한이 IdP 요구에 못 미치면 대상(직원·조직) 단위 락으로 나누는 설계를 검토.

#### M5. 운영 프로필이 개발 기본값(localhost 엔드포인트·정적 자격증명·테이블 자동 생성)을 그대로 물려받는다 — **확인됨**

- 근거: `application-prod.yml` 은 로그 설정만 바꾼다(두 앱 모두 15줄). 기본 `application.yml` 에 `dynamodb.endpoint: http://localhost:8000`, `create-table-on-startup: true`, `openfga.api-url: http://localhost:8080`, `ldap.bind-password: adminpassword` 가 있다(`app-ldap/src/main/resources/application.yml:33,69,76-79`, app-scim `:20,27-30`). 엔드포인트가 있으면 정적 자격증명 `local/local` 을 쓴다(`DynamoDbConfig.java:29-34`). 속성 기본값도 `createTableOnStartup = true`(`DynamoDbProperties.java:18`), 테이블이 없으면 만든다(`TableInitializer.java:34-48`).
- 시나리오: 운영 배포에서 테이블명 환경변수에 오타가 난다 → 앱이 빈 테이블을 새로 만들고 정상 기동한다. app-scim 이면 조직도가 빈 채로 IdP 요청을 받는다 — GET 이 404 → IdP 가 POST 로 다시 만들어 새 UUID 가 생기고, 옛 UUID 의 튜플은 장부에 고아 권한으로 남는다. 권한을 묻는 앱의 신원 변환도 새 UUID(권한 없음)를 받는다.
- 권장: prod 프로필에서 `create-table-on-startup: false`, 엔드포인트·비밀번호 기본값 제거(없으면 기동 실패). SCIM 은 기동 시 "테이블이 비었는데 장부에 줄이 있다"면 기동을 멈추는 확인.

#### M6. 직원·조직 열거 인덱스의 파티션 키가 값 하나(`USER_INDEX`·`GROUP_INDEX`)다 — 대량 적재 때 GSI 역압 — **의심**

- 근거: GSI1PK 가 모든 직원 META 에 `USER_INDEX`, 조직 META 에 `GROUP_INDEX`(`storage-dynamodb/.../Keys.java:66-69`, `DynamoDbDirectoryStateRepository.java:200,566`). GSI2 도 같은 파티션 키(`Keys.java:42`, `:208`). GSI1 은 ALL 프로젝션(`TableInitializer.java:65-73`).
- 시나리오: 첫 LDAP 적재 10만 명 — `replaceWith` 가 동시 8로 META 를 쓰면 쓰기마다 GSI1·GSI2 쓰기가 같은 파티션 키 값 하나로 몰린다. GSI 파티션이 쓰기 한도에 닿으면 DynamoDB 는 **본 테이블 쓰기까지 스로틀**한다. SDK 재시도로 느려지다가 30분 기한·실패로 이어질 수 있다. SCIM 대량 프로비저닝도 같다. 적응형 용량·정렬키 기준 분할이 얼마나 받아 주는지는 실제 AWS 에서만 알 수 있다(DynamoDB Local 은 재현 못 함).
- 권장: 실제 AWS 에서 `WriteThrottleEvents`(GSI 별)를 보며 10만 건 적재 측정. 필요하면 인덱스 파티션 키를 `USER_INDEX#00..15` 로 나누고 목록은 흩어 모으기.

#### M7. 권한을 묻는 앱의 신원 변환(로그인 → employeeId)이 인증 없는 관리 API 의 접두사 검색에 기댄다 — **확인됨**(설계)

- 근거: README:336-345 가 앱에 `GET /admin/employees?userName=` (접두사·대소문자 무시 검색, 결과에서 같은 줄을 고르라)나 `?externalId=` (GSI3 최종 일관성, 직원 externalId 는 유일하지 않음 — README:347-348)를 쓰라고 안내한다. 구현은 `AdminQueryUseCase.java:51-74`, `DirectorySearchRepository.java:23-28`.
- 시나리오: 소비 앱이 "결과 첫 줄"을 쓰거나 대소문자 비교를 잘못하면 다른 사람의 id 를 받는다 — 곧 틀린 인가 결과다. 막 만든 직원은 GSI 지연으로 잠깐 안 보여 로그인 직후 권한 없음이 된다. 관리 API 에는 SLA·인증·호출 한도가 없다.
- 권장: 런타임 용도의 전용 엔드포인트(정확 일치, 0/1건, 모호하면 409, 인증·캐시 지침·SLA)를 두고 관리 API 와 분리.

#### M8. 운영 신호의 공백 — "마지막 성공 이후 경과" 지표가 없고, 일부 상실·어긋남이 지표로 나오지 않는다 — **확인됨**

- 근거: app-ldap 지표는 끝난 회차의 타이머·카운터뿐이다(`app-ldap/.../SyncMetrics.java`). 실행 기록 쓰기가 실패하면 지표도 남지 않는다(`SyncJobs.java:205,269-272` — `onFinished` 는 기록 성공 뒤에만). app-ldap 은 리스 상실 관찰자가 NOOP 이다(`UseCaseConfig.java:48`). SCIM 아카이빙의 어긋남은 로그 한 줄뿐이다(`SnapshotArchiveUseCase.java:140-147`).
- 시나리오: 스케줄러가 락을 못 잡아 매일 건너뛰거나(`SyncScheduler.java:40-43` — INFO 로그만), 실행 기록 저장이 실패해 회차가 RUNNING 으로 남아도 대시보드는 조용하다.
- 권장: `sync_last_success_timestamp{source}` 게이지와 경과 경보, 리스 상실·아카이빙 어긋남(`extra`/`missing`)을 지표로, PARTIAL 을 경보 대상으로.

### Low

#### L1. 기동할 때마다 인가 모델을 새로 쓰고, 쓰기·Check 가 모델 id 를 고정하지 않는다 — **확인됨**
- 근거: store 를 찾은 뒤 늘 `writeAuthorizationModel`(`authz-openfga/.../StoreBootstrapper.java:115-125,266-281`), 기동 시 예열이 이것을 부른다(`OpenFgaStoreInitializer.java:36-44`). 쓰기·Check 요청에 `authorization_model_id` 가 없다(`OpenFgaRelationTupleWriter.java:387-397`).
- 영향: 배포 × 인스턴스 수만큼 모델 버전이 쌓이고, 모델이 바뀌는 롤링 배포 중 옛 인스턴스가 새 모델로 검증받는다. 같은 store 에 누가 다른 모델을 쓰면 다음 재시작이 조용히 되돌린다.
- 권장: 최신 모델과 내용이 같으면 쓰지 않기, 모델 id 를 해석해 쓰기·Check 에 고정.

#### L2. 한 파티션으로 몰리는 BatchWriteItem 에 짧은 재시도 예산(약 1.5초) — 대형 조직 삭제·대형 감사 줄 — **의심**
- 근거: 조직 삭제가 멤버 줄(한 조직 파티션)을 25개씩 동시 8로 BatchWrite(`DynamoDbDirectoryStateRepository.java:578-632`), 재시도는 5번·100ms 부터(`BatchRequests.java:32-33,84-89`). 감사 이력도 300KB 조각 여러 개를 한 대상 파티션에 BatchWrite(`DynamoDbAuditLog.java:59,104-117`). 스냅샷 묶음은 바로 이 문제 때문에 PutItem 하나씩으로 바꿨다(`DynamoDbTupleSnapshotRepository.java:101-110`).
- 시나리오: 10만 명 조직 DELETE → 한 파티션 초당 1,000 WCU 한도에 걸려 미처리분이 예산 안에 안 빠지고 503 → IdP 재시도로 여러 번에 나눠 끝난다(META 가 마지막이라 수렴은 한다). 10만 명 조직의 감사 줄(약 4.5MB)은 일부 조각이 빠져 그 줄이 통째로 버려질 수 있다.
- 권장: 단일 파티션 대상은 순차·속도 제한 쓰기, 재시도 예산을 크기에 비례하게.

#### L3. 필터 없는 목록의 첫 페이지마다 전체 COUNT, 책갈피가 없으면 선형 건너뛰기 — 인증 없는 비용 증폭 — **확인됨**
- 근거: `ScimPager.java:42-56`, `DynamoDbDirectoryQueryRepository.java:187-224`(COUNT 도 GSI1 ALL 아이템 크기만큼 RCU 를 쓴다).
- 시나리오: `GET /scim/v2/Users?startIndex=99999&count=1` 반복 → 요청마다 10만 아이템 COUNT + 약 10만 키 건너뛰기.
- 권장: 인증(C1)과 함께 호출 한도, COUNT 캐시(수 분), 큰 startIndex 상한.

#### L4. 하루 1회 표지를 일 전에 잡는다 — 그날 실패하면 다시 돌지 않는다 — **확인됨**
- 근거: `core/.../usecase/DailyOnce.java:24-35`. 아카이빙·정리가 실패해도 그날 표지는 남는다.
- 권장: 표지에 상태(진행·완료)를 두고 실패·만료 시 다른 인스턴스가 다시 잡게.

#### L5. 계정 상태 속성을 읽지 못하면 전원 활성(fail-open)인데 감지 신호가 없다 — **확인됨**
- 근거: 속성이 없으면 막히지 않은 것으로 본다(`connector-ldap/.../AccountStatus.java:49-75`), README:533 이 전제를 적는다.
- 시나리오: 서비스 계정 ACL 변경으로 `userAccountControl` 을 못 읽게 되면 비활성 계정 전원이 다음 회차에 권한을 되찾는다.
- 권장: AD 로 설정됐는데 회차의 직원 전원에서 그 속성이 비면 경고·지표(또는 회차 실패).

#### L6. 코드 구조 — **확인됨**
- 1,000줄이 넘는 클래스 둘(`IncrementalSyncUseCase.java` 1,249줄, `DynamoDbDirectoryStateRepository.java` 1,066줄)에 쓰기 경로의 거의 모든 규칙이 몰려 있다. 운영 클래스에 테스트 전용 생성자가 여럿 있다(`IncrementalSyncUseCase.java:161-183`, `SyncJobs.java:81-84`).
- 두 앱의 헬스 인디케이터(`app-ldap`·`app-scim` 의 `OpenFgaHealthIndicator`·`DynamoDbHealthIndicator`)와 예약 작업 관측 도우미(`SyncScheduler.java:78-87`·`ArchiveScheduler.java:67-76`), 저장소들의 `queryPartition`·`putItem`·`deleteItem` 이 복제돼 있다.
- 주석이 설계 문서 번호·점검 번호("설계 2026-10-02 §3.4, 점검 S14")에 강하게 기대고 코드보다 긴 곳이 많다. 결정 근거는 ADR 로, 주석은 불변식·계약으로 줄이는 편이 새 사람에게 읽힌다. 한글 메서드 이름은 팀 안에서는 일관되지만 스택 트레이스·검색·외부 협업에서 비용이 있다.
- README(984줄)에 브랜치별 이관 메모("이 브랜치는 키 레이아웃을 바꾼다")가 운영 문서와 섞여 있다.

#### L7. 시험의 빈 곳 — **확인됨**
- DynamoDB Local 이라 스로틀·파티션 한도·GSI 역압·조건부 쓰기 지연이 시험되지 않는다(M6, L2 와 연결).
- "OpenFGA 가 커밋하고 응답을 잃음" 고장 주입이 없다(H1).
- 다중 인스턴스 SCIM 쓰기 경합은 저장소 수준(`TwoInstanceWriteTest`)과 단일 서버 동시성(`ScimWriteContentionScaleTest`)까지만 있다. 처리량은 기록만 하고 단언하지 않는다.
- 시간 기반 대기: `IncrementalSyncLeaseTest.java:169,192,210,230` 이 `Thread.sleep(50)` 뒤 dispose 해 "쓰는 도중 끊김"을 흉내 낸다 — 느린 CI 에서는 다른 지점에서 끊겨 의도와 다른 경로를 시험할 수 있다.

#### L8. `spring.reactor.context-propagation: auto` 의 전역 훅 비용 — **의심**
- 근거: 두 앱 `application.yml:17`. 설정 주석도 JVM 전역 스위치라고 적는다. 15만 원소 파이프라인(TupleMapper·diff·배치 짜기)과 10만 건 저장 흐름에서 신호마다 ThreadLocal 복원 비용이 붙는다. 측정이 필요하다.

#### L9. 동기화·재적재의 첫 리스 한도를 "획득 시각"이 아니라 "일을 시작한 시각"부터 잰다 — **확인됨**(영향 작음)
- 근거: 락 획득 → 실행 기록 PutItem → 그 뒤에 `keeper.keep`(`SyncJobs.java:145-152,192-203`), 첫 마감은 `keep` 호출 시각부터(`LeaseKeeper.java:62`). 실행 기록 쓰기가 스로틀로 10초 넘게 걸리면 첫 갱신 실패 시의 멈춤 한도가 실제 만료(획득 + 30초)보다 늦어진다.

#### L10. SCIM 아카이빙은 락 밖에서 돌고, 프로세스가 죽으면 그 실행 기록은 영원히 RUNNING 이다 — **확인됨**
- 근거: 아카이빙은 `SyncJobs` 를 타지 않고(`SnapshotArchiveUseCase.java:84-93`), 남은 기록 닫기는 ARCHIVE 를 일부러 제외한다(`SyncJobs.java:310-313`). 보관 기간(30일) 동안 RUNNING 으로 보인다.

## 5. 질문 — 답에 따라 판단이 바뀌는 것

1. 운영 네트워크 노출 모델은 무엇인가(같은 VPC 의 누가 8081/8082 에 닿는가)? 인증 도입(C1)의 형태(IdP 별 Bearer, mTLS, 관리 API OIDC)와 시점은?
2. 실제로 쓸 IdP(Okta·Entra 외 포함)는 503·Retry-After·부분 실패를 어떻게 다루는가? 특히 Okta 푸시가 5xx 를 자동 재시도하는지, 수동 "작업"으로 쌓는지. PUT 에서 `active` 를 생략하는 IdP 가 있는가? (H1·M1·M4 의 심각도가 이것에 달렸다.)
3. 회수 SLA(퇴사·비활성화 뒤 몇 분/시간 안에 권한이 사라져야 하는가)와 운영 동기화 주기는? 가드 ABORTED 에 대한 당직 대응은? (H2)
4. 예상 SCIM 쓰기 부하 — 초기 프로비저닝 기간, 개편 때 초당 요청 수, 동시 연결 수. 실제 AWS 에서 락 점유 p99 를 잰 적이 있는가? (M4)
5. DynamoDB 용량 모드·리전 구성, 실제 AWS 에서 10만 건 첫 적재를 돌려 GSI 스로틀을 본 적이 있는가? (M6)
6. 다른 시스템이 같은 OpenFGA store 에 쓰거나 모델 id 를 고정해 Check 하는가? (L1, 그리고 재적재가 "남의 줄"을 지운다는 전제)
7. 권한을 묻는 앱들은 실제로 신원 변환을 어떻게 구현했는가 — 어떤 엔드포인트, 캐시, 실패 시 동작? (M7)
8. 배포 형태 — 인스턴스 수, k8s liveness/readiness 가 `/actuator/health` 전체(LDAP·OpenFGA 포함)를 보는가? 그렇다면 LDAP 장애가 재시작 루프가 된다. JVM 힙은 10만 명 회차(조직도·스냅샷 여러 벌·감사 모음)를 기준으로 잡았는가?
9. 재적재·장부 대조를 정기적으로 돌릴 계획이 있는가, 아니면 사람이 판단할 때만인가? (H1)

## 6. 상위 5개 — 먼저 고칠 순서

1. **C1 인증·인가 부재** — SCIM Bearer/mTLS, 관리 API 분리·인증, 파괴적 작업 보호, actuator 분리.
2. **H1 불확실한 쓰기의 추적과 자동 대조** — LDAP 은 일시 실패 시 "기록 중" 표시 유지(즉시 가능), SCIM 은 의도 기록 또는 보상 지우기, 두 앱 모두 야간 장부 대조와 `extra` 지표.
3. **H2 회수 지연 상한** — 비활성화 지우기는 가드 밖으로, 쓰기는 가드와 무관하게, 회수 SLA 에 맞춘 주기, 경보.
4. **M1 `active` fail-open** — PUT 누락은 기존 값 유지, `remove active` 는 비활성 또는 400.
5. **M5 운영 프로필 기본값** — prod 에서 테이블 자동 생성 끄기, 엔드포인트·비밀번호 기본값 제거, SCIM 빈 테이블 기동 차단.

그다음은 M2(LDAP 긴 값), M3(거절 줄의 블라스트 반경), M4(락 안 기한·재시도 계층) 순으로 본다.
