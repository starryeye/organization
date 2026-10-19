# 외부 검토 — 솔루션 아키텍트 리드 관점 (2026-10-19)

- 대상: `spike-ldap-5k` 워크트리의 현재 상태(브랜치 `scim-standard-rest`). README, 빌드 파일, `docker-compose.yml`, 두 앱 설정, 각 모듈 `src/main`, 필요한 만큼의 테스트를 읽었다.
- 하지 않은 것: 빌드·테스트·Docker 실행, `docs/superpowers/`·`.superpowers/`·git 이력 열람. 그래서 의도한 절충으로 보이는 것도 근거를 찾아보지 않고 질문으로 남겼다.
- 표기: 경로는 저장소 루트 기준 `path:line`. **확인됨**은 코드로 따라가 본 것, **의심**은 그럴듯하지만 실제 AWS·실제 디렉터리 없이는 끝까지 확인할 수 없었던 것이다.

---

## 1. 이 시스템을 어떻게 이해했나

```
 [LDAP/AD] ──pull(cron, 기본 하루 1회)──▶ app-ldap :8081 ─┐
                                                          ├─ core(유스케이스·포트) ─┬─▶ OpenFGA store (group/user 튜플) ◀── 소비 앱 Check(user, member, group)
 [IdP: Entra/Okta] ──SCIM push──▶ app-scim :8082 ─────────┘   + admin-api(조회·재적재)  └─▶ DynamoDB 단일 테이블(앱마다 하나): 현재상태·튜플 스냅샷·락·실행/감사 이력
```

1. 하는 일: 디렉터리의 "누가 어느 조직에 속하나"를 OpenFGA 튜플 `group:G#direct_member@user:U`, `group:P#child@group:C`로 옮긴다. 판정은 소비 앱이 OpenFGA에 직접 `Check(member)`로 묻고, `member`는 하위 조직을 따라 위로 롤업된다(`authz-openfga/src/main/resources/authorization-model.fga`).
2. 배포 하나에 커넥터 하나다. app-ldap 또는 app-scim이고, 둘이 같은 core·storage-dynamodb·authz-openfga·admin-api를 쓴다. 테이블과 store도 앱마다 따로 둔다.
3. app-ldap: cron마다 LDAP 전체를 메모리로 읽는다. 직전 "튜플 스냅샷"과 diff한 뒤 OpenFGA에 쓰고, 실제로 반영된 것만 새 스냅샷으로 남긴 다음 DynamoDB 현재상태를 통째로 맞춘다. 삭제 가드(30%)가 있다.
4. app-scim: SCIM 요청 하나마다 전역 DynamoDB 락을 잡는다. OpenFGA Check로 지금 있는 튜플을 읽어 목표와 diff하고, OpenFGA에 쓴 뒤 DynamoDB에 커밋한다. 이 배포에서는 DynamoDB가 조직도의 유일한 사본이고, 직원·조직 id(UUID)도 여기서 발급한다.
5. 공통 기능: 재적재(OpenFGA Read로 store 전체를 훑어 맞춤), 실행 이력, 감사 이력(TTL 30일), 관리 조회 API. 관리 조회 API는 소비 앱이 로그인 사용자를 우리 id로 바꾸는 경로이기도 하다.
6. 여러 대를 띄울 수 있다. 다만 모든 변경 작업은 앱별 DynamoDB 락 한 줄(`LOCK#MUTATION`)로 줄을 선다. 하루 한 번 작업은 날짜 표지로 한 대만 돈다.

---

## 2. 총평과 도입 승인 여부

**판정: 대기업 운영 환경 도입은 지금 상태로는 승인하지 않는다. 아래 조건을 채우면 다시 검토한다.**

실패 경로를 다루는 공학 수준은 이 규모의 프로젝트로는 드물게 높다. 쓰기 순서, 부분 실패, 리스, 기록 전 중단, 응답을 잃은 재시도까지 한 줄씩 생각했고, README도 한계를 숨기지 않는다. 그래도 도입을 막는 이유는 세 가지다.

1. **보안 태세가 운영 수준이 아니다.** SCIM·관리·actuator 어디에도 인증이 없다. 이 서버가 쓰는 OpenFGA도 인증 없이 열어 둘 수밖에 없는 구조다. 이 서버가 **전사 인가 데이터의 쓰기 입구**라는 점을 생각하면 결정적이다.
2. **플랫폼 계약이 대기업의 기대와 다르다.** store를 독점해 남의 줄을 지우고, 기동할 때마다 모델을 덮어쓴다. 소비 앱이 id 매핑을 각자 떠안는다.
3. **운영 수치가 검증되지 않았다.** 퇴사 회수 지연(LDAP 기본 하루), 전역 락의 처리량 상한, AD 전체 그룹을 읽을 때의 규모는 실제 AWS·실제 디렉터리 모양에서 재지 않았다.

승인 조건(모두 충족):
- C-1·H-1 해소: SCIM 인증, 관리·actuator 분리와 인증, OpenFGA 자격 증명과 권한 분리.
- H-6 해소: IaC로 만든 테이블에 PITR·백업·삭제 보호를 걸고, 운영에서는 테이블 자동 생성을 끈다. 복구 뒤에도 id가 보존되는 전략이 있어야 한다.
- H-5 해소: 회수 SLO(예: 퇴사 후 1시간 안 권한 회수)를 정하고 실제로 맞춘다.
- H-2 결정: store를 이 서버 전용으로 둘지, 전사가 함께 쓰는 공유 store로 둘지 정하고 문서화한다.
- 실측: 실제 AWS와 목표 디렉터리 모양에서 부하 시험(H-4, H-7, M-4, M-9).

C-1·H-1만 고친 뒤라면 제한된 파일럿은 승인할 수 있다. 조건은 내부망, 소비 앱 하나, 조직 단위만 읽는 디렉터리다.

---

## 3. 잘한 점

- **의존 방향과 포트.** `core`는 reactor·slf4j만 의존한다(`core/build.gradle:3-5`). 저장소·인가·디렉터리는 포트(`core/src/main/java/dev/starryeye/organization/core/port/*`)이고 어댑터 모듈이 구현하며 앱 모듈은 배선만 한다. 저장소를 다른 DB로 바꾸는 길이 열려 있다.
- **"쓴 만큼만 기록" 원장 설계.** OpenFGA에 먼저 쓰고 실제로 성공한 튜플만 스냅샷으로 커밋하므로, 실패분은 다음 diff가 자동으로 다시 잡는다(`core/.../usecase/FullSyncUseCase.java:150-182`). 쓰기 직전 "기록 중" 표시를 남겨 기록 전에 멈춘 회차를 다음 회차가 장부 훑기로 맞춘다(`FullSyncUseCase.java:93-95,133-135`, `LedgerAlignment.java:47-56`).
- **인가 공백 없는 재적재.** store를 지우지 않고 "다 쓰고 → 훑고 → 남는 것만 지운다." 읽기가 실패하면 장부를 건드리지 않는다(`core/.../usecase/TupleReconciler.java:26-39,95-114`). store 번호도 고정된다.
- **리스 락의 세부.** 토큰 조건 반납·갱신, 응답을 잃은 획득도 같은 토큰으로 성공한다(`storage-dynamodb/.../DynamoDbMutationLock.java:57-91,116-151`). 단조 타이머로 갱신 한도를 재고(`core/.../usecase/LeaseKeeper.java:36-40`), OpenFGA 쓰기 직전과 커밋 직전에 리스를 다시 확인한다(`IncrementalSyncUseCase.java:967-988`). 펜싱이 없다는 한계도 스스로 적어 두었다(`DynamoDbMutationLock.java:34-36`).
- **SCIM 기준선을 OpenFGA Check로 잡는다.** 상태 기준선이 아니라 실제 튜플과 diff하고, 그 과정에서 어긋남을 지표로 낸다(`IncrementalSyncUseCase.java:884-937`). BatchCheck 응답이 개수·correlationId·개별 오류 면에서 조금이라도 어긋나면 "없음"으로 떨어뜨리지 않고 실패시킨다(`authz-openfga/.../OpenFgaRelationTupleChecker.java:159-213`).
- **단조 모델을 쓴 3단계 쓰기 순서와 연속 실패 차단기.** 400 응답은 쪼개 다시 보내 나쁜 줄만 남긴다(`authz-openfga/.../OpenFgaRelationTupleWriter.java:39-47,96-140`).
- **작업을 요청에서 떼어 낸다.** 202+runId로 응답하고, 서버 종료·기한·리스 상실과 경주시킨 뒤, 반납하고 나서 기록한다. 죽은 RUNNING 기록도 정리한다(`core/.../usecase/SyncJobs.java:181-273,308-331`).
- **운영 상식이 반영된 세부.** 헬스 프로브가 store를 만들지 않는다(`StoreBootstrapper.java:84-100`). AD 서비스 계정 잠금을 막으려고 인증 실패 뒤 30분 동안 바인드를 쉰다(`app-ldap/.../LdapHealthIndicator.java:66-79`). 일시 장애는 503+Retry-After, 버그는 500으로 나눈다(README:768-794). 키 길이를 사전에 검증하고, 큰 조직 응답은 스트리밍한다.
- **불변 id.** 튜플에 이름을 넣지 않고 entryUUID·objectGUID·서버 발급 UUID만 쓴다(README:79-87). 개명이 삭제+생성이 되지 않는다.
- **테스트 자산.** Testcontainers 종단 시험과 `@ScaleTest` 20개 클래스(5천 명, 일부 10만 행)가 있다. README가 "실제 AD에서는 확인하지 못했다"처럼 검증 한계를 명시한다(README:458,494,514).

---

## 4. 발견 사항

### Critical

#### C-1. SCIM·관리 API·actuator가 같은 포트에서 인증 없이 열려 있다 — 누구나 전사 권한 그래프를 쓸 수 있다 (확인됨)

- 근거
  - SCIM 발견 응답 자체가 인증 방식이 없다고 선언한다: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java:252` (`"authenticationSchemes", List.of()`).
  - 어느 모듈에도 Spring Security 의존이 없고(`app-scim/build.gradle`, `app-ldap/build.gradle`), main 소스에서 인증 관련 코드를 grep해도 나오지 않는다.
  - 관리 API가 같은 포트에 자동 구성된다: `admin-api/src/main/resources/META-INF/spring/...AutoConfiguration.imports`, `app-scim/.../AdminSyncController.java:49-86`.
  - `wipe`의 확인값은 테이블명뿐이고 기본값이 공개돼 있다: `app-scim/src/main/resources/application.yml:29`.
  - app-ldap에서는 `force=true`로 삭제 가드를 우회할 수 있다: `app-ldap/.../AdminSyncController.java:48-71`.
  - app-ldap 헬스가 세부를 늘 보여 준다: `app-ldap/src/main/resources/application.yml:90-92` (`show-details: always` — LDAP URL·baseDn·OpenFGA URL·테이블명·예외 메시지).
  - README도 인정한다: README:385-387, 437.
- 시나리오
  - 8082에 닿는 누구나 다음 순서로 자기에게 권한을 줄 수 있다: `POST /scim/v2/Users {"userName":"x"}` → `PATCH /scim/v2/Groups/<임원 조직 id> add members [x의 id]` → OpenFGA에 `direct_member` 튜플이 생긴다 → 이 store에 Check하는 모든 앱이 x를 임원 조직 구성원으로 판정한다.
  - `POST /admin/sync/rebuild?mode=wipe&confirm=organization-scim` 한 줄이면 조직도 유일 사본과 장부가 사라진다.
  - `GET /admin/employees?displayName=김`과 `/history`로 전 직원의 이름·이메일과 변경 전후 값을 긁어 갈 수 있다.
- 왜 중요한가
  - 이 서버는 인가 데이터의 쓰기 입구다.
  - Okta·Entra 클라우드가 SCIM을 호출하려면 그 포트가 외부에서 닿아야 한다. 그런데 관리·actuator가 같은 포트·같은 경로 공간에 있어 네트워크만으로는 떼어 낼 수 없다.
  - RFC 7644 §2는 SCIM 인증을 전제로 한다.
- 권고
  - SCIM: IdP별 Bearer 토큰(회전 가능) 또는 OAuth2 client credentials를 쓰고, 가능하면 mTLS·IP 허용 목록을 더한다. `ServiceProviderConfig.authenticationSchemes`도 채운다.
  - 관리·actuator: `management.server.port`로 별도 리스너에 두고 OIDC+RBAC을 건다. `wipe`·`force`는 승인 절차나 별도 운영 도구로 옮기고, 호출자 신원을 감사에 남긴다.
  - 관리 조회에 속도 제한을 둔다.

### High

#### H-1. OpenFGA 클라이언트에 자격 증명이 없고, 서비스가 store 생성·삭제·모델 쓰기 권한을 요구한다 (확인됨)

- 근거
  - `authz-openfga/.../StoreBootstrapper.java:302-308`: `ClientConfiguration`에 `apiUrl`만 넣고 `credentials` 설정이 없다.
  - `authz-openfga/.../OpenFgaProperties.java:10-23`: 자격 증명 설정 항목이 없다.
  - 런타임에 store 목록 조회·생성·삭제와 모델 쓰기를 한다: `StoreBootstrapper.java:145,206-227,246-257,266-281`.
  - `docker-compose.yml:2-10`: OpenFGA를 인증 없이 띄운다.
- 시나리오
  - 이 서버를 돌리려면 OpenFGA가 인증 없는 요청을 받아야 한다. 그러면 소비 앱이든 같은 망의 누구든 `Write`로 튜플을 직접 넣을 수 있다. 동기화를 우회하는 권한 상승이다.
  - H-3 때문에 그렇게 넣은 줄은 자동으로 사라지지 않는다.
- 왜 중요한가
  - OpenFGA는 preshared key나 OIDC 인증을 지원하는데, 이 설계가 그것을 쓸 수 없게 만든다.
  - store 생성·삭제와 모델 쓰기는 배포 파이프라인 권한이지 런타임 권한이 아니다.
- 권고
  - SDK `credentials`(ApiToken / ClientCredentials)를 지원하고 비밀은 Secrets Manager에서 읽는다.
  - store·모델은 파이프라인이 만든다. 런타임은 설정에 고정한 `storeId`·`authorizationModelId`에 Write·Read·Check만 하고, 소비 앱에는 Check 전용 자격을 준다.

#### H-2. store를 독점하고 기동마다 모델을 덮어쓴다 — 소비 앱이 같은 store에서 관계를 조합할 수 없다 (확인됨)

- 근거
  - 재적재는 빈 키 Read로 store 전체를 훑고, "있어야 할 줄"에 없는 것은 무엇이든 지운다: `authz-openfga/.../OpenFgaRelationTupleScanner.java:37-44`, `core/.../usecase/TupleReconciler.java:187-205`. README:206-207도 "다른 앱이 이 장부에 직접 쓴 줄은 재적재가 지운다"고 적는다.
  - store를 찾았든 만들었든 기동할 때마다 `writeAuthorizationModel`을 부른다: `StoreBootstrapper.java:115-117,266-281`.
  - 쓰기에 모델 id를 고정하지 않는다: `StoreBootstrapper.java:25-26`.
- 시나리오
  - 문서 서비스가 같은 store에 `type document { define viewer: [user, group#member] }`를 넣으려 한다고 하자. 이 서버의 파드가 재시작하면 자기 모델이 latest가 되어 `document` 타입이 사라지고, 그 앱의 Check가 오류를 낸다. 다음 재적재는 `document` 튜플을 지운다.
  - 결국 소비 앱은 별도 store를 써야 한다. 그러면 조직 롤업은 예/아니오 Check로만 얻고, 조합(ListObjects·조직 기반 공유)은 앱 코드에서 해야 한다.
  - 모델 변경을 롤링 배포하는 중에 옛 버전 파드가 재시작하면 옛 모델이 다시 latest가 된다. 파드가 뜰 때마다 모델 버전도 하나씩 쌓인다.
- 왜 중요한가
  - OpenFGA를 도입하는 가장 큰 이유가 같은 그래프 안에서 관계를 조합하는 것이다. 대기업은 보통 OpenFGA를 공유 플랫폼으로 운영한다.
- 권고
  - 계약을 정한다.
  - 공유 store라면 다음을 한다.
    - 이 서버가 소유한 타입(`group`의 `direct_member`·`child`)만 훑고 지운다. 타입 필터 Read를 쓴다.
    - 모델은 모듈형(`fga.mod`)으로 나눠 파이프라인에서 쓰고, 런타임은 모델을 쓰지 않으며 모델 id를 고정한다.
  - 전용 store라면 소비 앱 통합 가이드(조합 패턴, 별도 store 운영)를 공식화한다.

#### H-3. 장부에 직접 넣은 줄은 자동으로 찾지도 지우지도 못한다 (확인됨)

- 근거
  - README:41-44: 드리프트를 감지할 수 없고, 되돌리는 수단은 사람이 거는 재적재뿐이라고 스스로 적었다.
  - LDAP 동기화는 스냅샷하고만 diff한다: `FullSyncUseCase.java:117-121`.
  - SCIM 하루 대조는 후보를 현재상태의 멤버십에서만 뽑는다: `core/.../usecase/SnapshotArchiveUseCase.java:42-44,108-113`. 어긋나도 WARN 로그만 남기고 지표는 없다: `SnapshotArchiveUseCase.java:140-146`.
  - SCIM 쓰기 경로의 어긋남 지표는 그 요청이 건드린 대상만 본다: `IncrementalSyncUseCase.java:905-918`.
- 시나리오
  - 내부자(또는 H-1로 열린 OpenFGA에 닿는 누군가)가 `group:재무#direct_member@user:X`를 직접 쓴다. 이 줄은 LDAP 스냅샷에도, SCIM 후보 집합에도 없다. 그래서 매일 동기화와 아카이빙이 모두 지나치고, 누가 수동 재적재를 걸 때까지 영구히 남는다.
- 왜 중요한가
  - 인가 원장에 주기적 대조가 없으면, 한 번 뚫린 권한이 지속성을 얻는다.
- 권고
  - 락 없이 store를 읽기만 하는 대조 작업을 주기적으로 돌려 `ledger.extra/missing` 지표와 경보를 낸다.
  - 필요하면 가드를 단 재적재를 정기 실행한다(LDAP은 비용이 낮다).
  - H-1과 함께 고친다.

#### H-4. SCIM 쓰기 전부가 DynamoDB 락 한 줄로 직렬화된다 — 처리량 상한이 인스턴스 수와 무관하고, 큰 작업이 퇴사 처리까지 막는다 (구조는 확인됨, 수치는 의심)

- 근거
  - `storage-dynamodb/.../Keys.java:105`(`LOCK#MUTATION`).
  - 쓰기마다 락을 잡고(`IncrementalSyncUseCase.java:737-809`), 쓰기 직전·커밋 직전에 갱신을 두 번 더 한다(`IncrementalSyncUseCase.java:967-988`).
  - README:849-850: "쓰기 처리량의 상한(락을 쥐는 시간의 역수)은 서버 수와 무관하다."
  - 재적재 중 SCIM 쓰기는 503(Retry-After 60): README:243-249.
  - 10만 명 조직 삭제도 락을 쥔 채 끝까지 간다: README:860-861.
  - 락 대기 3초를 넘기면 503: `DynamoDbProperties.java:28-29`.
- 시나리오
  - 쓰기 한 건이 락 안에서 DynamoDB 왕복 8~12번과 OpenFGA Check·Write를 순서대로 한다. 대략 50~150ms로 잡으면 클러스터 전체가 초당 7~20건이다.
  - 정상 상태(하루 수천 건)에서는 충분하다.
  - 그러나 10만 명 초기 프로비저닝과 멤버십 PATCH는 몇 시간이 걸린다.
  - 대규모 개편이나 대형 조직 삭제·재적재가 도는 몇 분 동안은 모든 SCIM 쓰기가 503이다. 같은 시간에 온 퇴사자 비활성화 PATCH도 503이다. Entra는 개별 오류를 다음 주기(수십 분)에 다시 보내므로 회수가 그만큼 늦는다.
  - 서버 간 경합은 지터 백오프라, 대수를 늘리면 락이 비는 시간이 오히려 늘 수 있다.
- 왜 중요한가
  - 인스턴스를 늘려도 SCIM 쓰기 처리량은 늘지 않는다(수평 확장 불가).
  - 가장 시급한 쓰기인 회수가 대량 작업 뒤에 갇힌다.
- 권고
  - 실제 AWS에서 p50/p99 락 보유 시간과 초당 처리량을 잰다.
  - 대상(직원·조직)별 락이나 버전 속성을 쓴 낙관적 동시성으로 나눈다.
  - 비활성화·삭제에 우선순위를 준다.
  - 대형 조직 작업과 재적재는 전역 쓰기 락 밖의 비동기 작업으로 뺀다.

#### H-5. LDAP 경로의 회수가 늦다 — 하루 1회 전체 동기화가 기본이고, 실패·가드 중단 뒤 자동 재시도가 없다 (확인됨)

- 근거
  - `app-ldap/src/main/resources/application.yml:20`(`sync.cron: "0 0 3 * * *"`), `app-ldap/.../SyncProperties.java:14`.
  - 정기 실행은 cron뿐이다: `app-ldap/.../SyncScheduler.java:32-45`.
  - 가드에 걸리면 쓰기·지우기를 하나도 하지 않고 ABORTED로 끝난다: `FullSyncUseCase.java:126-132`, `core/.../guard/DeletionGuard.java:32-48`.
  - 종료 시 실행 중이던 작업은 FAILED로 남는다: `SyncJobs.java:226-238`.
  - README:535: "막힌 직후부터 다음 동기화까지는 권한이 남는다."
- 시나리오
  - 09:00에 AD에서 비활성화된 퇴사자는 다음 날 03:00까지 모든 조직 권한을 유지한다.
  - 그 03:00 회차가 DC 점검이나 롤링 배포로 FAILED가 되면 다시 24시간이 늘어난다.
  - 개편으로 지울 튜플이 30%를 넘으면 매일 ABORTED가 되고, 사람이 `force=true`를 걸 때까지 입사·퇴사가 하나도 반영되지 않는다. `force`는 검토 없이 전부를 반영한다.
- 왜 중요한가
  - 인가 시스템에서 회수 지연은 감사 지적 1순위다.
  - 가드가 대량 삭제는 막지만, 같은 장치가 회수까지 같이 막는다.
- 권고
  - 회수 SLO를 정한다.
  - 짧은 주기(5~15분)를 10만 명 규모에서 검증한다.
  - 증분 감지(AD DirSync/USN, OpenLDAP syncrepl·modifyTimestamp)로 비활성화를 빠르게 반영한다.
  - 실패하면 백오프 재시도한다.
  - 가드는 "대량 삭제만 보류하고, 비활성 사용자의 회수와 새 부여는 적용"하는 쪽으로 나눈다.
  - 가드 중단과 동기화 지연에 경보를 건다(M-2).

#### H-6. SCIM 배포에서 DynamoDB가 신원(id) 발급처인데 백업·PITR·삭제 보호가 없고, 문서화된 복구 절차는 모든 id를 바꾼다 (확인됨)

- 근거
  - POST마다 무작위 UUID를 만든다: `connector-scim/.../ScimUserHandler.java:48`, `ScimGroupHandler.java:42`.
  - README:263-269: DynamoDB가 조직도의 유일한 사본이고, 복구는 wipe 뒤 IdP 재프로비저닝이다. README:927-928도 같다.
  - `storage-dynamodb/.../TableInitializer.java:51-92`: PAY_PER_REQUEST·GSI·TTL만 만든다. PITR, 삭제 보호, CMK 암호화가 없다.
  - `DynamoDbProperties.java:18`(`createTableOnStartup = true`), `app-*/src/main/resources/application.yml`(true).
  - `application-prod.yml`은 로그 설정만 바꾼다(두 앱 모두 1-15행).
  - 저장소에 IaC가 없다.
- 시나리오
  - 테이블을 실수로 지우거나 잘못된 배포가 wipe를 부르면, 복구는 wipe 뒤 재프로비저닝뿐이다. 그 과정에서 모든 `employeeId`·`orgCode`가 새 UUID가 된다.
  - 소비 앱이 저장해 둔 id는 모두 고아가 된다: 매핑 캐시, 자기 store의 `owner user:<uuid>`, 조직 id 기반 정책이 조용히 깨진다.
  - H-4 처리량 때문에 재프로비저닝 몇 시간 동안 인가 데이터도 비어 있다.
- 왜 중요한가
  - "불변 id"(README:84-87)는 레코드가 다시 만들어지지 않을 때만 성립한다. 복구 절차가 그 전제를 깨뜨린다.
- 권고
  - IaC로 테이블을 만들고 PITR·온디맨드 백업·삭제 보호를 건다. 운영에서는 `create-table-on-startup=false`로 두고, 테이블이 없으면 기동을 실패시킨다.
  - RPO/RTO를 정한다.
  - 재프로비저닝 때 id를 보존하는 방법을 마련한다. 예: `externalId`·`userName` 묘비로 옛 id 재사용, id 매핑 내보내기·가져오기.

#### H-7. 규모 가정이 "사람당 소속 1~2개" 조직도에 맞춰져 있다 — AD 전체 그룹을 읽으면 튜플·아이템 수가 수십 배가 된다 (의심)

- 근거
  - README:57("튜플 15만 줄" ≒ 10만 명), `SyncProperties.java:19-22`(30분 기한의 근거가 "약 1,100 배치").
  - 픽스처 모양: `core/src/testFixtures/.../OrgChartFixture.java:42-61`(5천 명 중 겸직 166명).
  - README:481,486-490은 AD에 `group-filter: (objectClass=group)`을 권한다.
  - 전체를 메모리에 올린다: `FullSyncUseCase.java:88-96,144-148`.
  - 멤버십 한 줄마다 PutItem 두 번(소속 줄+멤버 줄): `DynamoDbDirectoryStateRepository.java:456-472`.
  - 장부 훑기는 100줄 페이지를 순서대로 읽는다: `OpenFgaRelationTupleScanner.java:31,37-44`.
- 시나리오
  - 10만 명 AD에서 `(objectClass=group)`이면 보안·배포 그룹이 5만 개 이상이고, 1인당 평균 20~50개 소속이면 `direct_member`가 200만~500만 줄이다.
  - 그러면 다음이 모두 커진다.
    - 디렉터리·매핑·기준선을 동시에 담느라 힙이 수 GB 필요하다.
    - 첫 적재 때 DynamoDB 쓰기가 400만~1,000만 건이다.
    - 재적재는 Read 2만~5만 페이지를 순서대로 읽는다.
  - 30분 기한을 넘기면 첫 동기화가 계속 FAILED가 되고, 남은 "기록 중" 표시 때문에 매 회차가 장부 맞추기로 다시 돈다.
- 왜 중요한가
  - 목표 디렉터리 모양에 따라 같은 코드가 동작하거나 아예 동작하지 않는다.
- 권고
  - 지원하는 디렉터리 모양(조직 단위 그룹만)을 명시하고 필터 권고를 바꾼다.
  - 튜플 100만~500만 줄 규모로 용량 시험을 한다.
  - diff를 흘려 보내는 방식으로 바꾸고, 멤버십 줄은 묶음 쓰기로 바꾼다. 기한은 규모에 비례하게 잡는다.

### Medium

#### M-1. OpenFGA 헬스가 기동한 뒤로는 OpenFGA를 실제로 확인하지 않는다 (확인됨)

- 근거: `app-scim/.../OpenFgaHealthIndicator.java:40-53` → `StoreBootstrapper.findExistingStore()`(`StoreBootstrapper.java:94-100`)가 캐시된 storeId가 있으면 네트워크 호출 없이 UP을 준다. app-ldap 쪽도 같은 코드다.
- 시나리오: OpenFGA가 죽어도 `/actuator/health`는 UP이다. SCIM 쓰기는 전부 503이고 LDAP 동기화는 차단기로 FAILED가 되는데, 프로브·대시보드는 정상이다.
- 권고: 짧은 제한 시간의 실제 호출(모델 읽기나 고정된 Check)로 확인한다. readiness와 liveness를 나눈다.

#### M-2. 관측 공백 — 동기화 신선도, app-ldap 락 지표, 하루 대조 결과가 지표로 나오지 않고 트레이스는 내보내지 않는다 (확인됨)

- 근거
  - 끝난 회차만 기록하고 "마지막 성공 경과" 게이지가 없다: `app-ldap/.../SyncMetrics.java:20-43`.
  - app-ldap 락 관찰자가 꺼져 있다: `app-ldap/.../UseCaseConfig.java:48`(`LockObserver.NOOP`).
  - 하루 대조 결과는 로그뿐이다: `SnapshotArchiveUseCase.java:140-146`.
  - 트레이싱은 켜 두었지만 내보내는 곳이 없다: `app-*/application.yml` tracing 절("내보내는 곳이 없으므로").
- 시나리오: 스케줄이 조용히 돌지 않거나 매일 ABORTED여도, 사람이 로그를 읽기 전에는 아무도 모른다.
- 권고: `sync.last_success_age_seconds` 게이지와 경보, `sync.guard.aborted` 경보, 대조 어긋남 게이지, OTLP 내보내기. SLO(회수 지연)를 정의한다.

#### M-3. 감사 이력이 최선 노력이고, 같은 테이블에 30일 TTL로 남으며, 행위자가 없다 (확인됨)

- 근거
  - 감사 쓰기가 실패해도 결론은 그대로다: `SyncJobs.java:279-290`.
  - 묶음 실패는 세기만 한다: `storage-dynamodb/.../DynamoDbAuditLog.java:104-117`.
  - README:425-433("빠질 수 있다"), `application.yml` `audit-retention-days: 30`.
  - 관리 호출자 신원은 남지 않는다(C-1로 인증이 없다).
- 시나리오: 감사인이 "누가 언제 wipe·force를 걸었나, 이 권한은 언제 생겼나"를 1년 뒤에 묻는다. 답할 기록이 없거나 빠져 있다. 같은 IAM 역할이 감사 줄을 고칠 수도 있다.
- 권고: 아웃박스나 DynamoDB Streams로 불변 저장소(S3 Object Lock 또는 SIEM)에 보내고, 규정 보관 기간을 따르며, 행위자를 기록한다.

#### M-4. GSI 파티션키가 상수(`USER_INDEX`·`GROUP_INDEX`)다 — 대량 쓰기 때 GSI 역압이 걸리고 전체 열거가 한 키에 몰린다 (의심)

- 근거: `Keys.java:42,67-69`, 모든 직원 META가 같은 GSI1PK에 ALL 프로젝션으로 실린다(`DynamoDbDirectoryStateRepository.java:200`, `TableInitializer.java:66-72`). 열거와 비교가 이 한 키를 Query한다(`DynamoDbDirectoryStateRepository.java:820-865`).
- 시나리오: 첫 적재나 10만 명 속성 일괄 변경 때 GSI 쓰기가 한 파티션키로 몰린다. GSI 스로틀은 본 테이블 쓰기를 역압하므로 SCIM 503이 늘거나 LDAP 교체가 늦어진다. DynamoDB Local은 이 동작을 흉내 내지 않는다.
- 권고: GSI PK를 샤딩하고(`USER_INDEX#0..N`, 흩어 읽고 모으기), AWS에서 부하 시험을 한다.

#### M-5. 설정 기본값이 개발용이고, prod 프로필은 로그만 바꾸며, LDAP 기본이 평문이다 (확인됨)

- 근거
  - `app-ldap/src/main/resources/application.yml:29-33`(`ldap://localhost`, `bind-password: adminpassword`), `:68-79`(localhost OpenFGA·DynamoDB, 테이블 자동 생성), `:90-92`(헬스 세부 공개), `:100-102`(DEBUG).
  - `LdapProperties.java:14`(`ldap://` 기본), `LdapConfig.java:24-31`(TLS 관련 설정 없음).
  - endpoint를 주면 고정 자격 "local"을 쓴다: `DynamoDbConfig.java:29-34`.
- 시나리오: 운영 배포에서 override 하나를 빠뜨리면 다음 중 하나가 조용히 일어난다. AD에 평문 simple bind로 붙거나, PITR 없는 테이블이 자동으로 생기거나, 헬스 세부가 노출된다.
- 권고: prod 프로필에서 필수 설정을 `@Validated`로 강제하고 기본값을 없앤다. LDAP은 `ldaps://`·StartTLS를 강제하고 truststore를 설정한다. 비밀은 비밀 저장소에서 읽는다.

#### M-6. SCIM 경로에 대량 회수 안전장치가 없다 (확인됨)

- 근거: `DeletionGuard.java:12-13`(SCIM 단건 삭제에는 적용하지 않음). `IncrementalSyncUseCase.removeUser/removeGroup`(508-576)에 속도·비율 검사가 없다.
- 시나리오: Entra 범위 필터를 잘못 고치면 몇 시간에 걸쳐 10만 건의 비활성화·DELETE가 정상 요청으로 들어온다. 전사 권한이 차례로 사라지고, DELETE였다면 H-6처럼 id까지 바뀐다.
- 권고: 시간당 비활성화·삭제 비율 상한을 넘으면 보류하고 경보를 낸다. 소프트 삭제(묘비)를 둔다.

#### M-7. 공유 OpenFGA에 대한 부하 격리가 없다 (의심)

- 근거
  - 모든 Check·Read가 캐시를 우회한다: `OpenFgaRelationTupleChecker.java:50`(`HIGHER_CONSISTENCY`).
  - 매일 전체 후보를 BatchCheck한다: `SnapshotArchiveUseCase.java:112-113`.
  - 인증 없는 GET 한 번에 Check가 최대 200번 나간다: `core/.../usecase/AdminQueryUseCase.java:42-45`.
  - 조절 수단은 동시 수 4뿐이다: `OpenFgaProperties.java:23`.
- 시나리오: 03:00 아카이빙(Check 약 15만 건), 재적재(Write·Read 각 1,100번 이상), 관리 조회 폭주가 운영 Check 트래픽과 같은 OpenFGA 용량을 나눠 쓴다.
- 권고: 속도 제한, 작업 시간대 분리, OpenFGA 용량 계획에 이 부하를 넣는다. 관리 조회에는 인증과 쿼터를 건다.

#### M-8. 상위 조직 Check는 넓고 깊은 트리에서 하위 조직 전체를 펼칠 수 있다 (의심)

- 근거: `member: direct_member or member from child`(`authorization-model.fga`)이고, 튜플 방향이 `(group:C, child, group:P)`다(`core/.../model/RelationTuple.java:17-19`). 상위 P에 대한 Check는 P의 모든 하위 C로 내려간다.
- 시나리오: 구성원이 아닌 사용자에 대해 `Check(user:x, member, group:회사)`를 부르면, 결론을 내기 전에 하위 조직 수천 개를 방문한다. 소비 앱 지연이 늘고 OpenFGA 해석 한도(깊이 25 등)에 걸릴 수 있다.
- 권고: 실제 조직 트리로 음성 Check의 p99를 잰다. OpenFGA 버전별 재귀 최적화가 적용되는지 확인하고, 필요하면 모델이나 조상 튜플을 미리 계산하는 쪽을 검토한다.

#### M-9. 소비 앱의 신원 매핑이 인증 없는 관리 조회 API에 기댄다 (확인됨)

- 근거
  - README:84-87,336-345: 앱이 로그인 사용자를 `employeeId`로 직접 바꿔야 한다.
  - `userName`은 접두사 검색만 된다(`admin-api/.../AdminQueryController.java:37-55`). LDAP `externalId`는 DN 문자열 그대로라 표기가 다르면 맞지 않는다(README:341-342).
- 시나리오: 소비 앱마다 로그인 경로에 이 서버 조회를 끼우거나 캐시를 둔다. 결과적으로 이 서버가 로그인 경로의 런타임 의존이 된다. "인가는 앱이 OpenFGA에 직접 묻는다"(README:5-7)는 그림과 다르다. 조직 id(UUID)도 같은 방식으로 찾아야 한다.
- 권고: 정확 일치 매핑 API를 인증해서 제공한다. 또는 IdP 토큰 클레임(objectGUID, SCIM id 등)과 id를 맞추는 정책을 둔다. 매핑 변경 이벤트를 발행한다.

#### M-10. 배포 산출물과 운영 자동화가 없다 (확인됨)

- 근거: 저장소에 Dockerfile·쿠버네티스 매니페스트·IaC·CI가 없다. README:944("CI 가 아직 없어").
- 권고: 컨테이너 이미지, 프로브·PDB·종료 유예를 갖춘 매니페스트, 테이블·경보 IaC, `test`+`scaleTest`를 도는 CI를 마련한다.

### Low

#### L-1. 하루 한 번 작업은 일을 시작하기 전에 표지를 잡는다 — 잡은 인스턴스가 죽으면 그날 작업은 빠진다 (확인됨)

- 근거: `core/.../usecase/DailyOnce.java:24-34`, `storage-dynamodb/.../DynamoDbDailyJobClaims.java:30-45`. 죽은 ARCHIVE 기록은 닫는 대상에서 빠진다: `SyncJobs.java:310-313`.
- 영향: 아카이빙이나 스냅샷 정리가 하루 빠질 수 있고, ARCHIVE 기록이 RUNNING으로 TTL까지 남는다.
- 권고: 표지에 리스·완료 상태를 두고, 미완료면 다른 인스턴스가 다시 잡게 한다.

#### L-2. 리스 락에 펜싱이 없고 서버 시계에 의존한다 (확인됨, 문서화됨)

- 근거: `DynamoDbMutationLock.java:34-36`, README:856·901.
- 영향: GC 정지 등으로 리스가 만료된 뒤 늦은 쓰기가 들어갈 좁은 틈이 있다. 쓰기 직전·커밋 직전 재확인이 그 틈을 좁힌다.
- 권고: NTP 상태를 감시하고 `scim.lock.lease_lost` 경보를 운영 런북에 넣는다.

#### L-3. app-ldap과 app-scim이 같은 store·테이블을 가리키는 오설정을 막지 않는다 (확인됨)

- 근거: README:805-809는 경고만 한다. store·테이블에 소유 커넥터 표지가 없다.
- 영향: 두 앱의 재적재가 서로의 줄을 지운다.
- 권고: store·테이블 메타에 커넥터 종류를 기록하고, 기동할 때 대조한다.

#### L-4. 핵심 불변식이 거대한 클래스의 주석과 설계 문서 번호에 흩어져 있다 (확인됨)

- 근거: `IncrementalSyncUseCase.java`가 1,249줄이고 `DynamoDbDirectoryStateRepository.java`가 1,066줄이다. 주석이 "설계 2026-10-02 §3.2", "점검 M8"처럼 외부 문서를 참조한다.
- 영향: 운영팀에 인수인계하는 비용이 크다. 쓰기 순서 같은 불변식을 잘못 바꿔도 단위 테스트만으로는 드러나지 않을 수 있다.
- 권고: 불변식을 저장소 안의 짧은 ADR·런북으로 옮기고, 아키텍처 테스트(ArchUnit 등)로 순서·의존 규칙을 고정한다.

---

## 5. 질문 (답에 따라 판단이 바뀐다)

1. 목표 디렉터리의 실제 모양은 어떤가? 사용자당 그룹 수, 조직 트리의 깊이·폭, AD에서 읽을 그룹의 범위에 따라 H-7·M-8의 심각도가 달라진다.
2. OpenFGA는 이 서버 전용인가, 전사 공유 플랫폼인가? 소비 앱이 같은 그래프에서 관계를 조합해야 하는가? H-2의 등급이 바뀐다.
3. 퇴사 회수 SLO는 무엇인가? 하루 1회 기본값은 의도한 절충인가?
4. SCIM 쓰기 처리량·지연을 실제 AWS에서 잰 적이 있는가? 초기 프로비저닝 시간 목표는 얼마인가?
5. SCIM 포트를 공개 인터넷에 둘 것인가(클라우드 IdP 직접 호출), IdP 에이전트나 사설 연결을 경유할 것인가? 앞단 게이트웨이가 인증을 맡을 계획인가?
6. 소비 앱은 `employeeId`·`orgCode`를 어디에 저장하는가? H-6에서 id가 바뀔 때의 파급 범위가 여기서 정해진다.
7. 하이브리드 기업(AD + Entra)에서는 어느 커넥터를 쓰는가? 두 배포는 같은 사람에게 서로 다른 id를 주는데, 소비 앱은 이를 어떻게 다루는가?
8. DR·다중 리전 요구와 RPO/RTO는 무엇인가? 지금은 단일 리전 DynamoDB와 단일 store다.
9. 감사 보관 요구(기간, 불변성, 행위자 기록)는 무엇인가?
10. 인가 모델은 앞으로 누가 어떻게 바꾸는가? 모델 버전 고정과 롤아웃 절차가 있는가?

---

## 6. 상위 5개 (먼저 할 순서)

1. **C-1 인증과 노출 분리** — SCIM 토큰·mTLS, 관리·actuator를 별도 포트로 옮기고 OIDC+RBAC, wipe·force는 승인 절차를 거친다.
2. **H-1(+H-3) 장부 무결성** — OpenFGA 자격 증명과 권한 분리(런타임은 Write·Read·Check만, 소비 앱은 Check만). 장부를 주기적으로 대조하고 경보를 낸다.
3. **H-6 SCIM 신원 내구성** — IaC·PITR·백업·삭제 보호, 운영에서 자동 생성 금지, 재프로비저닝 때 id 보존 전략.
4. **H-5 LDAP 회수 지연** — 회수 SLO를 정하고, 짧은 주기·증분 감지·실패 재시도를 둔다. 가드는 회수를 막지 않게 나누고, 신선도 경보를 단다.
5. **H-2 store 계약 결정** — 공유라면 소유 타입만 대조하고 모델은 파이프라인이 쓰며 id를 고정한다. 전용이라면 소비 앱 통합 가이드를 공식화한다. 소비 앱이 붙기 전에 정해야 나중 비용이 작다.

(그다음은 H-4 전역 락 실측·재설계와 H-7 규모 가정 검증이다. 둘 다 실제 AWS·실제 디렉터리 모양에서 부하 시험부터 한다.)
