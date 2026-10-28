# 운영 준비 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 배포 전에 남은 운영 결함을 고친다.
- 기동: 테이블·store 가 엉뚱한 곳에 조용히 붙지 않고(D1·D2), 설정 실수에 멈춘다(D3)
- 시간: DynamoDB 호출과 락 획득에 한도를 두고(D4), 헬스가 실제로 묻는다(D5)
- 기록: 하루 1회 일은 실패하면 그날 다시 돌고(D7), 실행 기록·회수 지표·오류 응답이 실제와 맞는다(D6·D8·D10)
- 데이터: 직원 응답에 externalId 를 싣고(D9), 빈 소속 그림은 데이터 오류다(D11). 문서를 코드와 맞춘다(D12·D13)

**Architecture:**
- 기동 검사는 어댑터가 한다. `TableInitializer` 가 테이블의 있음·모양·보호를, `StoreBootstrapper` 가 store 의 있음·모델을, `LdapConfig` 가 LDAP 설정을 본다.
- 락 획득은 코어가 토큰을 만들고 시도마다 한도를 넘긴다. 어댑터는 그 한도를 요청 단위 `apiCallTimeout` 으로 건다.
- 하루 1회 표지는 상태(잡음·완료)·owner·임대를 갖고, 성공 판정은 부르는 쪽이 넘긴다.
- 관리 API 의 예외 처리기는 admin-api 에 두고, 분류기는 core 로 옮겨 SCIM 과 함께 쓴다.
- 회수 카운터는 core 의 관찰자 인터페이스로 받고 `ScimSyncMetrics` 가 Micrometer 로 낸다.

**Tech Stack:**
- Java 17, Spring Boot 3.5 WebFlux, Reactor
- AWS SDK v2 DynamoDB(Local 로 시험), OpenFGA Java SDK 0.9.11, Spring LDAP
- JUnit 5·AssertJ·Testcontainers, Micrometer

**Spec:** `docs/superpowers/specs/2026-10-27-ops-readiness-design.md` — 설계가 권위다. 이 계획과 부딪히면 설계를 따른다.

## Global Constraints

- 코드 관례: 기존 코드처럼 한국어 메서드·변수 이름을 쓴다(`잡는다`, `남은_기록을_닫는다`).
- 시험 관례:
  - 시험 메서드는 한국어 이름에 `@DisplayName` 한국어 문장을 단다.
  - 본문은 `// given / when / then` 으로 나눈다.
  - 단언은 AssertJ 다.
- Gradle:
  - 한 번에 Gradle 하나만 돌린다.
  - 구현 서브에이전트는 자기 과제의 모듈 시험만 돌린다(`./gradlew :<module>:test --tests '...'`).
  - 전체 `test`·`scaleTest` 는 컨트롤러가 머지 전에 돌린다.
- git:
  - `git stash` 를 쓰지 않는다.
  - 커밋 메시지는 최근 `git log` 처럼 한국어 `fix:`/`feat:`/`docs:` 한 문장이고 끝에 `(운영 준비)` 를 붙인다.
  - 커밋 끝줄은 `Co-Authored-By: <모델 이름> <noreply@anthropic.com>` 이다.
  - 바꾼 파일만 스테이징한다.
- OpenFGA Read(`RelationTupleScanner`)는 재적재와, 기준선을 믿을 수 없는 LDAP 회차(포인터의 "기록 중"·포인터 없음)에서만 쓴다. 다른 길에 넣지 않는다. `ReadLatestAuthorizationModel`·`ReadAuthorizationModels`·`GetStore` 는 이 원칙의 Read 가 아니다(설계 §4).
- 판단·쓰기는 Check·BatchCheck 만 쓴다. store 번호는 바꾸지 않는다.
- 규모 10만 명: 요청마다 전원을 읽지 않는다.
- 배포 하나에 커넥터 하나다. LDAP 과 SCIM 이 함께 도는 경우는 따지지 않는다.
- 운영 배포 전이다. 옛 테이블은 이관하지 않는다 — 모양 검사가 기동을 멈추고, 처방은 "테이블을 다시 만든다" 다.
- DynamoDB Local 2.5.3 은 삭제 보호를 지키고 PITR API(Update/DescribeContinuousBackups)는 UnknownOperation 으로 거절한다 — PITR 갈래는 가짜 클라이언트로 시험한다.
- 설계가 정한 문구(그대로 쓴다. `<…>` 만 채운다):
  - D1 없음: `DynamoDB 테이블 <name> 이 없다 — 처음 설치라면 dynamodb.create-table-on-startup=true 로 한 번 띄우거나 미리 만든다`
  - D1 PITR: `테이블은 만들었으나 PITR 을 켜지 못했다 — 콘솔·CLI 로 켠 뒤 다시 띄운다`
  - D1 복원본 WARN: `PITR 로 복원한 테이블이다 — 장부보다 낡은 기준선일 수 있어 재적재를 권한다(SCIM 은 mode=tuples)`
  - D2: `OpenFGA store <name> 이 없다 — 처음 설치라면 openfga.create-store=true 로 한 번 띄운다`
  - D8: 503 detail `일시 장애 — 잠시 뒤 다시 시도한다`, 500 detail `내부 오류`
  - D10 꼬리: ` — 현재상태는 디렉터리대로 교체했다`
- 설계가 정한 숫자:
  - `dynamodb.api-call-attempt-timeout` 10s, `dynamodb.api-call-timeout` 60s
  - 획득 시도 한도 = `min(남은 획득 한도, 1초)`, 하한 200ms. 락 TTL 30초는 그대로다
  - PITR 켜기 재시도는 2분 안에서
  - 하루 1회 표지 임대 = 잡은 때 + `sync.job-timeout` + 5분. 표지 TTL 3일
  - 죽은 기록의 나이 = 시작한 지 `sync.job-timeout` + 10분
  - cron 창: app-ldap `sync.purge-cron` `0 0 4-8 * * *`, app-scim `sync.archive-cron` `0 0 3-8 * * *`, `sync.purge-cron` `0 0 4-8 * * *`
  - SCIM 필터 입력 조각: 각각 앞 100자 + `…`
  - 카운터 `scim.revocations`, 태그 `kind=deactivate|delete_user|remove_member|delete_group`

## Review Focus

- **이 변경 전에 만든 테이블로 재시작한다**(GSI2 INCLUDE 에 `externalId` 가 없거나 TTL 이 꺼짐). 기동이 멈추고 문구가 무엇이 다른지와 "다시 만들어야" 를 말한다. 로컬 yml 이 `create-table-on-startup: true` 여도 고치거나 다시 만들지 않는다(Task 1).
- **기동 때 OpenFGA 에 닿지 않는데 `create-store=false` 다.** 앱은 경고만 남기고 뜬다. 닿은 뒤 첫 해석에서 store 가 없으면 만들지 않고 설정 오류(일시 장애 아님)를 낸다(Task 4).
- **DynamoDB 스로틀이 이어지는 동안 SCIM 쓰기가 온다.** 시도마다 1초에 끊겨 획득 한도(3초) 안에 503 으로 끝난다. 서버에서 성공했을 수 있는 획득은 토큰 반납으로 풀려, 다음 쓰기가 TTL(30초)을 기다리지 않는다(Task 3).
- **하루 1회 일을 돌던 인스턴스가 죽는다.** 임대가 끝난 뒤의 매시 시도가 다른 owner 로 잡아 돈다. 되살아난 옛 owner 의 늦은 완료·해제는 새 표지를 바꾸지 않는다 — DynamoDB Local 의 조건식으로 본다(Task 7).
- **관리 API 의 409 가 두 겹 감싸여 온다**(`ResponseStatusException(409)` 이 다른 예외에 다시 감싸임). 503 이 아니라 409 이고, 사유 문구가 본문 `detail` 에 있다(Task 12).

---

### Task 1: D1 — 테이블 기동 검사와 보호

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbProperties.java`
- Modify: `app-ldap/src/main/resources/application.yml`, `app-ldap/src/main/resources/application-prod.yml`
- Modify: `app-scim/src/main/resources/application.yml`, `app-scim/src/main/resources/application-prod.yml`
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTestSupport.java`
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/ContinuousBackupsStubClient.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableInitializerTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableProtectionTest.java` (새로)

**Interfaces:**
- Produces:
  - `DynamoDbProperties`: `createTableOnStartup` 기본 **false**, `boolean requireProtection = false`.
  - `TableInitializer.afterPropertiesSet()` 는 생성 여부와 상관없이 늘 `ensureTable().block()` 이다.
  - 패키지 전용 `static final List<String> GSI2_PROJECTION = List.of("userName", "displayName", "active", "externalId")` — 생성과 모양 검사가 함께 쓴다(D9 가 넣는 `externalId` 포함).
  - 패키지 전용 `static CreateTableRequest 테이블_요청(String table, boolean 삭제_보호)` — 시험이 옛 모양 테이블을 만들 때 고쳐 쓴다.
  - 패키지 전용 생성자 `TableInitializer(DynamoDbAsyncClient, DynamoDbProperties, Duration pitr_간격, Duration pitr_한도)`. 공개 2인자 생성자는 5초·2분이다.
- 함께 고치는 파일: `TableInitializer` 는 Task 4 가, `DynamoDbProperties` 는 Task 2·4 가, yml 은 Task 4·5·6·7 이 다시 고친다.
- yml 값:
  - 두 앱 `application.yml`: `create-table-on-startup: true`, `require-protection: false`
  - 두 앱 `application-prod.yml`: `dynamodb.create-table-on-startup: false`, `dynamodb.require-protection: true`

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `DynamoDbTestSupport`:
    - `setCreateTableOnStartup(true)` 로 바꾼다. 이제 생성이 꺼져 있으면 `ensureTable` 이 없는 테이블을 만들지 않는다.
    - `dropTableIfExists` 는 DescribeTable 로 `deletionProtectionEnabled` 가 켜져 있으면 UpdateTable(`deletionProtectionEnabled(false)`) 뒤 지운다.
  - `TableInitializerTest`(Local):
    - `테이블과_GSI를_생성한다` 의 GSI2 단언을 `containsExactlyInAnyOrder("userName", "displayName", "active", "externalId")` 로 고친다.
    - `생성이_꺼져_있는데_테이블이_없으면_기동을_멈춘다`: 테이블을 지우고 생성 끔 → `IllegalStateException`, 메시지는 D1 없음 문구(`organization-test` 를 채운 것)와 같다.
    - `이_변경_전에_만든_테이블이면_기동을_멈춘다` — Review Focus. `테이블_요청` 에서 GSI2 프로젝션만 옛 셋(`userName`·`displayName`·`active`)으로 바꿔 만들고 TTL 을 켠다. 생성 켬이어도 `ensureTable` 은 실패한다. 메시지에 `GSI2`·`프로젝션`·`다시 만들어야` 가 있다. 테이블은 지워지지 않았다.
    - `TTL_이_꺼진_테이블이면_기동을_멈춘다`: `테이블_요청` 으로 만들고 TTL 을 켜지 않는다 → 메시지에 `TTL`.
    - 기존 `GSI2_없는_옛_테이블이면_멈춘다`·`옛_GSI2_테이블이면_멈춘다` 의 단언은 그대로 통과해야 한다.
  - `TableProtectionTest extends DynamoDbTestSupport`:
    - 모두 `ContinuousBackupsStubClient(client)` 로 돈다. `describeTable`·`createTable`·`updateTable`·`waiter`·`updateTimeToLive`·`describeTimeToLive`·`close` 는 Local 로 넘기고, PITR 두 API(`updateContinuousBackups`·`describeContinuousBackups`)만 흉내 낸다. 나머지는 SDK 인터페이스의 기본 구현(UnsupportedOperationException)이다.
    - 간격 10ms·한도 200ms 생성자를 쓴다.
    - 시험:
      - `보호를_요구하면_삭제_보호와_PITR_을_켜서_만든다`: 지운 뒤 생성 → Local 의 `deletionProtectionEnabled()` true, 스텁의 `켜기_요청` 1번.
      - `막_만든_테이블이_PITR_을_잠시_거절하면_다시_켠다`: 스텁이 `ContinuousBackupsUnavailableException` 을 두 번 낸 뒤 받는다 → 성공, `켜기_요청` 3번.
      - `한도_안에_켜지_못하면_기동을_멈추고_테이블은_남는다`: 늘 거절 → 메시지는 D1 PITR 문구, 그 뒤 DescribeTable 은 성공한다.
      - `있는_테이블의_삭제_보호가_꺼져_있으면_기동을_멈춘다`: 스텁의 PITR 은 ENABLED → 메시지에 `삭제 보호`.
      - `있는_테이블의_PITR_이_꺼져_있으면_기동을_멈춘다`: Local UpdateTable 로 삭제 보호를 켜고 스텁 PITR 은 DISABLED → 메시지에 `PITR`.
      - `복원본이면_경고만_하고_기동한다`: 스텁의 DescribeTable 응답에 `restoreSummary` 를 붙인다 → 예외 없음.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :storage-dynamodb:test --tests '*TableInitializerTest*' --tests '*TableProtectionTest*'`. 컴파일 실패나 단언 실패를 기대한다.
- [ ] **Step 3: 구현한다** — `ensureTable()` 의 순서(설계 §3):
  1. DescribeTable.
  2. `ResourceNotFoundException` 이면:
     - 생성 끔 → D1 없음 문구의 `IllegalStateException`.
     - 생성 켬 → 지금처럼 만들고 ACTIVE 를 기다린 뒤 TTL 을 켠다. `requireProtection` 이면 `deletionProtectionEnabled(true)` 로 만든다. TTL 다음에 `UpdateContinuousBackups(pointInTimeRecoveryEnabled=true)` 를 부른다. `ContinuousBackupsUnavailableException` 만 `pitr_간격` 으로 쉬며 `pitr_한도` 안에서 다시 시도한다(`Retry.fixedDelay` + 걸린 시간 확인). 끝내 못 켜면 D1 PITR 문구다.
  3. 있으면:
     - 모양 검사. 다른 점을 모두 모아 `"테이블 '%s' 의 모양이 이 버전과 다르다 — %s — 테이블을 다시 만들어야 한다"` 로 낸다. `%s` 는 다른 점을 `", "` 로 이은 것이다.
     - 다른 점 문구:
       - `"%s 인덱스 없음"`
       - `"%s 파티션키 %s(기대 %s)"`
       - `"%s 정렬키 %s(기대 %s)"`
       - `"GSI2 프로젝션 %s %s(기대 INCLUDE %s)"`
       - `"TTL %s %s(기대 expiresAt ENABLED)"`
     - 키 기대값: GSI1(`GSI1PK`, `GSI1SK`), GSI2(`GSI1PK`, `displayNameKey`), GSI3(`externalId`, `PK`). GSI2 프로젝션은 INCLUDE 이고 비키 속성 집합이 `GSI2_PROJECTION` 과 같아야 한다. TTL 은 DescribeTimeToLive 가 `expiresAt` 에 ENABLED·ENABLING 이어야 한다.
     - `requireProtection` 이면 보호 확인. `deletionProtectionEnabled()` 와 DescribeContinuousBackups 의 PITR ENABLED 가 둘 다 필요하다. 꺼진 것을 이어 `"테이블 '%s' 의 보호가 꺼져 있다: %s — 콘솔·CLI 로 켠 뒤 다시 띄운다"` 로 낸다(`삭제 보호`, `PITR`).
     - `restoreSummary()` 가 있으면 D1 복원본 문구로 WARN 한 번.
  - 기존 `requireCurrentDisplayNameIndex` 는 모양 검사에 흡수한다. GSI2 자바독에 `externalId` 를 더한 이유(D9, 설계 §11)를 한 줄 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :storage-dynamodb:test`, PASS. 시험 도우미를 바꿨으므로 모듈 전체다.
- [ ] **Step 5: 커밋** — `fix: 기동 때 늘 테이블을 확인해 없거나(생성 꺼짐) 모양·TTL 이 다르면 멈추고, 운영은 삭제 보호와 PITR 을 요구한다 — 이름 오타가 빈 테이블을 새로 만들어 신원이 갈라지던 것을 막는다 (운영 준비)`

---

### Task 2: D4a — DynamoDB 클라이언트 시간 한도

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbProperties.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (`dynamoDbAsyncClient`)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbConfigTest.java` (새로)

**Interfaces:**
- Produces:
  - `Duration apiCallAttemptTimeout = 10s`, `Duration apiCallTimeout = 60s`
  - 클라이언트에 `ClientOverrideConfiguration`(두 값)을 건다. 재시도 정책은 SDK 기본 그대로다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `기본값은_시도_10초_호출_60초다`
  - `클라이언트에_시도_한도와_호출_한도를_건다`:
    - `new DynamoDbConfig().dynamoDbAsyncClient(설정)` 을 만든다.
    - `serviceClientConfiguration().overrideConfiguration()` 의 `apiCallAttemptTimeout()`·`apiCallTimeout()` 이 설정값이다. 기본값과 바꾼 값(3s·7s) 둘 다 본다.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :storage-dynamodb:test --tests '*DynamoDbConfigTest*'`
- [ ] **Step 3: 구현한다** — 두 값의 자바독에 설계 §6 의 이유(Local 이 부하 아래 느림, 스냅샷 묶음 약 350KB)를 한 줄씩 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령, PASS.
- [ ] **Step 5: 커밋** — `fix: DynamoDB 클라이언트에 시도 한도 10초·호출 한도 60초를 둔다 — 스로틀이 이어지면 호출 하나가 SDK 재시도로 수십 초 매달리던 것을 끊는다 (운영 준비)`

---

### Task 3: D4b — 락 획득: 시도 한도·같은 토큰·조건부 반납

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/MutationLock.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/LockAttemptTimeoutException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AcquireBackoff.java` (`잡는다`, 클래스 자바독)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`잡는다`, `acquireTimeout` 자바독)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java` (`락을_잡고_띄운다`)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbMutationLock.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeMutationLock.java`
- Modify(부르는 곳 → `acquireOnce`): `FakeMutationLockTest`, `SnapshotArchiveUseCaseTest`, `LeaseKeeperTest`, `IncrementalSyncLocalQueueTest`, `DynamoDbMutationLockTest`, `TwoInstanceWriteTest`, `DynamoDbMutationLockHolderPurposeTest`, `ScimRebuildEndToEndTest`, `ScimErrorSignalsEndToEndTest`, `LdapSyncEndToEndTest`
- Modify(덮는 곳 → 3인자): `IncrementalSyncLockObservabilityTest`(익명 둘), `IncrementalSyncLockRetryAfterTest.잡히지_않는_락`, `IncrementalSyncLocalQueueTest`(세 하위 클래스), `IncrementalSyncLeaseTest`(익명 둘). 찾는 법: `grep -rn "acquire(LockPurpose\|acquire(MutationLock.LockPurpose\|super.acquire" */src/test`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/AcquireBackoffTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncAcquireAttemptTest.java` (새로)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbMutationLockTest.java`

**Interfaces:**
- Produces:
  ```java
  // MutationLock — 1인자 acquire 는 없앤다. 덮던 시험이 컴파일 오류로 드러나야 한다
  /** attemptTimeout 이 null 이면 클라이언트 전체 한도만 받는다. 같은 토큰의 다시 시도는 이미 성공한 획득을 그대로 다시 얻는다 */
  Mono<LockLease> acquire(LockPurpose purpose, String token, Duration attemptTimeout);
  /** 한 번만 시도하는 쪽(관리 API full·rebuild, 동기화) — 토큰을 새로 만들고 한도는 없다 */
  default Mono<LockLease> acquireOnce(LockPurpose purpose) { return acquire(purpose, UUID.randomUUID().toString(), null); }
  ```
  - `LockAttemptTimeoutException extends TemporaryFailureException`: 시도 한도로 끊겨 결과를 모른다. 대기는 `기본_대기` 다. `LockUnavailableException` 의 하위가 아니다.
  - `AcquireBackoff`:
    - `static final Duration 시도_상한 = 1s`, `시도_하한 = 200ms`
    - `static Duration 시도_한도(Duration 남은)` = `max(200ms, min(남은, 1s))`
    - `<T> Mono<T> 잡는다(Function<Duration, Mono<T>> 시도, Duration 한도)` — 다시 구독할 때마다 남은 한도로 시도 한도를 새로 계산한다.
    - 실패별 다음:
      - `LockUnavailableException` 은 지금 규칙 그대로다.
      - `LockAttemptTimeoutException` 은 기다리지 않고 다시 시도한다. 걸린 시간이 한도에 닿았으면 `new LockUnavailableException("변경 락을 얻지 못했습니다", TemporaryFailureException.기본_대기)` 로 멈춘다.
      - 그 밖의 실패는 그대로 흘린다.
  - `DynamoDbMutationLock.acquire(purpose, token, attemptTimeout)`:
    - 한도가 있으면 `PutItemRequest.overrideConfiguration(o -> o.apiCallTimeout(attemptTimeout))` 를 건다.
    - SDK 의 `ApiCallTimeoutException` 은 `LockAttemptTimeoutException` 으로 옮긴다.
    - 기존 패키지 전용 `acquire(purpose, token)` 은 없앤다.
  - `FakeMutationLock`:
    - 같은 토큰이면 이미 쥔 락도 다시 얻는다.
    - 손잡이:
      - `AtomicInteger 응답을_잃는_시도`: 0 보다 크면 그 시도는 락을 실제로 잡은 뒤, 한도만큼 늦게 `LockAttemptTimeoutException` 을 낸다. 한도가 null 이면 곧바로 낸다.
      - `RuntimeException acquireError`: 있으면 시도가 그 오류로 끝난다.
    - 기록: `List<String> 시도한_토큰`, `List<Duration> 시도_한도`, `List<String> 반납한_토큰`(모두 `CopyOnWriteArrayList`).
- `IncrementalSyncUseCase.잡는다` 의 규칙(설계 §6 표):
  - 줄 서기 한 번에 토큰 하나를 만든다. 시도는 `한도 -> lock.acquire(WRITE, token, 한도)` 다.
  - 시도가 `LockUnavailableException` 이 아닌 오류로 끝나면 `결과를_모른다` 를 켠다. 시간 초과도 그 밖의 오류도 같다.
  - 백오프가 오류로 끝났는데 `결과를_모른다` 가 켜져 있으면, `lock.release(new LockLease(token, Instant.EPOCH))` 를 `timeout(1s)` 과 오류 삼킴으로 한 번 보낸다. 그 뒤 원래 오류를 낸다. 그다음은 지금의 `onErrorMap`·관찰자다.
  - 포트가 던지는 경우를 오류 신호로 바꾸는 `try/catch` 는 시도 함수 안으로 옮긴다.
- `SyncJobs.락을_잡고_띄운다` 는 `lock.acquireOnce(purpose)` 를 쓴다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `AcquireBackoffTest`:
    - 기존 시험은 `잡는다(한도 -> 시도, …)` 꼴로 바꾼다.
    - `시도마다_남은_한도와_1초_중_작은_값을_주고_200ms_아래로_내리지_않는다`: 가상 시간, 한도 2.1초. 시도는 받은 한도만큼 늦게 `LockAttemptTimeoutException` 을 낸다 → 받은 한도 `[1s, 1s, 200ms]`. 2.2초에 `LockUnavailableException` 이고 `retryAfter()` 는 10초다.
    - `끊긴_시도는_기다리지_않고_다시_시도한다`: 첫 시도는 1초에 끊기고 둘째는 성공 → 정확히 1초에 값이 온다.
  - `IncrementalSyncAcquireAttemptTest`:
    - 가상 시간, `FakeMutationLock`, 획득 한도 3초. 준비는 `IncrementalSyncLockRetryAfterTest` 를 본뜬다.
    - 시험:
      - `매달린_시도는_끊고_같은_토큰으로_다시_시도해_잡는다`: `응답을_잃는_시도=1` → 쓰기 `fullyApplied()`. `시도한_토큰` 둘이 같고, `시도_한도.get(0)` 은 1초다. 끝나면 `isHeld()` false.
      - `한도까지_결과를_모르면_503이고_같은_토큰으로_반납을_한_번_보낸다`: `응답을_잃는_시도=MAX` → `LockUnavailableException`. `반납한_토큰` 은 `[그 토큰]` 이고 `isHeld()` false.
      - `조건_실패만_있었으면_반납하지_않는다`: 다른 쓰기가 WRITE 로 쥐고 있다 → `반납한_토큰` 비었음.
      - `그_밖의_오류로_멈추면_결과를_모르는_시도로_보고_반납한다`: `acquireError = new IllegalStateException("스로틀")` → 곧바로 503(시도 1번), 반납 1번.
      - `스로틀이_이어져도_획득_한도_안에_503이고_다음_쓰기는_TTL을_기다리지_않는다` — Review Focus:
        - `응답을_잃는_시도=3` 이면 첫 쓰기는 3초 안팎에 `LockUnavailableException` 으로 끝난다.
        - 락은 토큰 반납으로 풀렸다.
        - 이어 `응답을_잃는_시도=0` 으로 둔 둘째 쓰기는 가상 시간 1초 안에 성공한다.
  - `DynamoDbMutationLockTest`(Local):
    - `시도_한도로_끊긴_획득은_결과를_모르는_예외다`: `acquire(WRITE, "토큰", Duration.ofMillis(1))` → `LockAttemptTimeoutException`.
    - `끊긴_뒤_같은_토큰은_잡고_남의_토큰은_막힌다`: 위 다음 `acquire(WRITE, "토큰", null)` 은 성공하고, 인스턴스2의 `acquireOnce(WRITE)` 는 `LockUnavailableException` 이다.
    - `토큰만으로_반납하면_풀린다`: `release(new LockLease("토큰", Instant.EPOCH))` 뒤 인스턴스2의 `acquireOnce` 성공.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*AcquireBackoffTest*' --tests '*IncrementalSyncAcquireAttemptTest*'`, `./gradlew :storage-dynamodb:test --tests '*DynamoDbMutationLockTest*'`
- [ ] **Step 3: 구현한다**
  - Interfaces 대로 구현한다.
  - `AcquireBackoff` 자바독의 "날아가는 중인 시도는 끊지 않는다" 를 설계 §6 의 셋 가름(조건 실패·시도 한도로 끊김·그 밖)과 조건부 반납으로 바꾼다. `acquireTimeout` 필드 자바독과 `DynamoDbMutationLock.acquire` 자바독의 "같은 Mono 를 다시 구독" 도 같이 고친다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, `./gradlew :storage-dynamodb:test`, 그다음 `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`. PASS.
- [ ] **Step 5: 커밋** — `fix: SCIM 쓰기의 락 획득 시도마다 남은 한도(최대 1초·최소 200ms)로 끊고 같은 토큰으로 다시 시도하며, 결과를 모르는 시도를 남긴 채 포기하면 토큰으로 반납한다 — 스로틀 중 3초 한도가 SDK 재시도에 묻히던 것을 막는다 (운영 준비)`

---

### Task 4: D2 — store 생성 스위치·이름 필수·모델 비교

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaProperties.java`
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/StoreBootstrapper.java` (생성자, `sharedResolution`, `attachAndWriteModel`, `listStoresNamed`)
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/OpenFgaStoreInitializer.java`
- Create: `authz-openfga/src/main/java/dev/starryeye/organization/authz/StoreNotFoundException.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbProperties.java`, `TableInitializer.java` (`afterPropertiesSet`)
- Modify: 두 앱 `application.yml`(`openfga.create-store: true`), `application-prod.yml`(`openfga.create-store: false`)
- Modify: `authz-openfga/src/test/java/dev/starryeye/organization/authz/OpenFgaTestSupport.java`
- Modify: 설정을 직접 만드는 시험 — `StoreBootstrapperTest`, `OpenFgaStoreInitializerTest`, `OpenFgaCheckCacheTest`, `OpenFgaBatchCheckTest`, `OpenFgaRelationTupleWriterBatchGroupingTest`, `OpenFgaRelationTupleWriterBreakerTest`, app-scim `OpenFgaHealthIndicatorTest`. store 를 새로 만들어야 하는 곳만 `setCreateStore(true)` 를 더한다.
- Test: `StoreBootstrapperTest`, `OpenFgaStoreInitializerTest`, `TableInitializerTest`

**Interfaces:**
- Produces:
  - `OpenFgaProperties`: `boolean createStore = false`. `storeName` 은 기본값 없음(null).
  - `DynamoDbProperties.tableName` 은 기본값 없음(null).
  - `public class StoreNotFoundException extends IllegalStateException`: 메시지는 D2 문구다. 일시 장애가 아니다.
  - `StoreBootstrapper(OpenFgaProperties)` 는 `storeName` 이 null·빈칸이면 `IllegalStateException("openfga.store-name 이 비어 있다 — 설정에 store 이름을 적는다")` 이다.
  - `TableInitializer.afterPropertiesSet()` 은 맨 먼저 `tableName` 이 null·빈칸이면 `IllegalStateException("dynamodb.table-name 이 비어 있다 — 설정에 테이블 이름을 적는다")` 이다.
  - 패키지 전용 `Mono<List<Store>> listStoresNamed()` — 시험이 덮어 첫 목록 조회를 실패시킨다.
- 동작:
  - `sharedResolution`: 찾기가 비면 `createStore` 일 때만 만들고, 아니면 `StoreNotFoundException` 이다.
  - `OpenFgaStoreInitializer`: `StoreNotFoundException` 만 다시 던져 기동을 멈춘다. 그 밖의 오류는 지금처럼 WARN 하고 넘어간다.
  - `attachAndWriteModel`:
    1. `client.readLatestAuthorizationModel()` 을 부른다.
    2. 모델이 없거나 읽기가 오류면 쓴다.
    3. 있으면 SDK 모델과 우리 요청을 같은 `ObjectMapper`(`setSerializationInclusion(NON_EMPTY)`)로 `valueToTree` 한다. `schema_version`·`type_definitions`·`conditions` 세 칸을 비교한다(없는 칸은 빈 것과 같다). 다를 때만 `writeAuthorizationModel` 이다.
    4. 쓰든 안 쓰든 끝나면 지금처럼 `clientRef`·`storeIdRef` 를 채운다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `StoreBootstrapperTest`:
    - `같은_store_로_두_번_부트스트랩하면_모델은_하나다`: 시험 도우미가 이미 해석한 store 를 새 `StoreBootstrapper(properties)` 로 다시 해석 → raw 클라이언트 `readAuthorizationModels()` 의 모델 수 1.
    - `모델이_다르면_새로_쓴다`: raw 클라이언트로 타입 하나짜리 다른 모델을 쓴 뒤 다시 해석 → 모델 수 3.
    - `새_이름에_생성이_꺼져_있으면_오류이고_store_를_만들지_않는다`: `StoreNotFoundException`, 메시지는 D2 문구, `countStoresNamed` 0.
    - `store_이름이_비어_있으면_만들_수_없다`
  - `OpenFgaStoreInitializerTest`:
    - `store_가_없고_생성이_꺼져_있으면_기동을_멈춘다`: `afterPropertiesSet()` 이 `StoreNotFoundException`.
    - `기동_때_닿지_않았으면_뜨고_닿은_뒤에도_store_를_만들지_않는다` — Review Focus:
      - `listStoresNamed()` 를 덮어 첫 호출만 `IOException` 으로 실패시키는 하위 클래스를 쓴다. 생성은 끔이다.
      - `afterPropertiesSet()` 은 예외가 없다.
      - 이어 `resolveStore().block()` 은 `StoreNotFoundException` 이고 `TemporaryFailureException` 이 아니다.
      - `countStoresNamed` 0.
  - `TableInitializerTest.테이블_이름이_비어_있으면_기동을_멈춘다`
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :authz-openfga:test --tests '*StoreBootstrapperTest*' --tests '*OpenFgaStoreInitializerTest*'`, `./gradlew :storage-dynamodb:test --tests '*TableInitializerTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `OpenFgaStoreInitializer`·`StoreBootstrapper` 자바독의 "없으면 만든다" 를 생성 스위치로 고친다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :authz-openfga:test`, `./gradlew :storage-dynamodb:test --tests '*TableInitializerTest*'`, `./gradlew :app-scim:test --tests '*OpenFgaHealthIndicatorTest*'`. PASS.
- [ ] **Step 5: 커밋** — `fix: 운영은 OpenFGA store 를 만들지 않고(openfga.create-store), 테이블·store 이름의 기본값을 없애며, 인가 모델은 최신과 내용이 다를 때만 쓴다 — 이름 오타가 빈 store 를 만들고 기동마다 모델 버전이 쌓이던 것을 막는다 (운영 준비)`

---

### Task 5: D5 — OpenFGA 헬스가 실제로 묻는다, probes

**Files:**
- Modify: `authz-openfga/src/main/java/dev/starryeye/organization/authz/StoreBootstrapper.java` (`findExistingStore`)
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/OpenFgaHealthIndicator.java`, `app-scim/src/main/java/dev/starryeye/organization/scim/app/OpenFgaHealthIndicator.java` (자바독·DOWN 사유)
- Modify: 두 앱 `application.yml` — `management.endpoint.health.probes.enabled: true`
- Test: `StoreBootstrapperTest`, `app-scim/src/test/java/dev/starryeye/organization/scim/app/OpenFgaHealthIndicatorTest.java`, `LdapSyncEndToEndTest`, `ScimEndToEndTest`

**Interfaces:**
- Consumes: Task 4 의 `StoreBootstrapper`.
- Produces: `Mono<String> findExistingStore()`
  - 캐시한 id 가 있으면 `clientFor(id).getStore()` 를 부른다. `FgaApiNotFoundError` 면 빈 Mono, 그 밖의 오류는 오류다.
  - 캐시가 없으면 지금처럼 이름으로 ListStores 를 한다.
  - 2초 상한·DOWN 번역은 인디케이터가 지금처럼 한다. DOWN 사유는 `"store 가 없다(지워졌거나 아직 만들지 않았다)"` 다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `StoreBootstrapperTest.해석한_store_가_지워지면_findExistingStore_가_빈다`: 해석 뒤 raw 클라이언트로 store 를 지운다 → 빈 Mono.
  - `OpenFgaHealthIndicatorTest.지운_store_는_DOWN_이다`: 해석·지움 뒤 `health()` → DOWN.
  - `LdapSyncEndToEndTest`·`ScimEndToEndTest` 에 `프로브는_외부_의존을_보지_않는다`: `/actuator/health/liveness`·`/actuator/health/readiness` 는 200, `$.status` UP, `$.components.openFga` 없음.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :authz-openfga:test --tests '*StoreBootstrapperTest*'`, `./gradlew :app-scim:test --tests '*OpenFgaHealthIndicatorTest*' --tests '*ScimEndToEndTest*'`, `./gradlew :app-ldap:test --tests '*LdapSyncEndToEndTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. 두 인디케이터는 같은 코드로 둔다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령, PASS.
- [ ] **Step 5: 커밋** — `fix: OpenFGA 헬스가 캐시한 store 를 GetStore 로 실제로 묻고, 두 앱에 liveness·readiness probes 를 켠다(외부 의존은 넣지 않는다) (운영 준비)`

---

### Task 6: D3 — 설정 검증(cron 출처·전략·비밀값·TLS·헬스 세부)

**Files:**
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncScheduler.java`, `UseCaseConfig.java`, `SyncProperties.java`
- Create: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncProperties.java`
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ArchiveScheduler.java`, `ScimUseCaseConfig.java` (`syncJobs`)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapProperties.java`, `LdapConfig.java`
- Modify: `app-ldap/src/main/resources/application.yml`, `application-prod.yml`, `app-ldap/src/test/resources/application-test.yml`, `app-scim/src/main/resources/application.yml`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncPropertiesTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncSchedulerCronTest.java` (새로)
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ArchiveSchedulerCronTest.java` (새로)
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/LdapConfigValidationTest.java` (새로)

**Interfaces:**
- Produces:
  - app-ldap:
    - `UseCaseConfig` 의 `@EnableConfigurationProperties(SyncProperties.class)` 를 지운다. 대신 `@Bean public SyncProperties syncProperties()` 로 이름 `syncProperties` 빈을 둔다. 클래스의 `@ConfigurationProperties("sync")` 가 바인딩한다.
    - `SyncScheduler`: `@Scheduled(cron = "#{@syncProperties.cron}")`, `@Scheduled(cron = "#{@syncProperties.purgeCron}")`.
  - app-scim:
    - `@ConfigurationProperties("sync") ScimSyncProperties { String archiveCron = "0 0 3 * * *"; String purgeCron = "0 0 4 * * *"; Duration jobTimeout = 30m; }`. `ScimUseCaseConfig` 의 `@Bean public ScimSyncProperties scimSyncProperties()` 로 둔다.
    - `ArchiveScheduler`: `#{@scimSyncProperties.archiveCron}`, `#{@scimSyncProperties.purgeCron}`. `syncJobs` 는 `@Value` 대신 `ScimSyncProperties.getJobTimeout()` 을 쓴다.
  - **cron 기본값은 이 과제에서 바꾸지 않는다** — Task 7 이 D7 의 창 값으로 바꾼다.
  - main yml 에서 `sync.cron`·`sync.purge-cron`·`sync.job-timeout`(app-ldap), `sync.archive-cron`·`sync.purge-cron`·`sync.job-timeout`(app-scim)을 지운다. 그 주석은 속성 클래스 자바독으로 옮긴다. 시험 yml 의 `"-"` 는 그대로 둔다.
  - `LdapProperties.requireTls = false`.
  - `static void LdapConfig.설정을_검사한다(LdapProperties)` — `ldapContextSource`·`ldapMappingStrategy` 가 맨 먼저 부른다.
    - `strategy` 는 대소문자 무시로 `group-of-names`·`dit` 만 받는다. 그 밖이면 `IllegalStateException("ldap.strategy 는 group-of-names 또는 dit 다(받은 값: %s)")`.
    - `bindDn` 이 비어 있지 않은데 `bindPassword` 가 null·빈칸이거나 `${` 로 시작하면 `IllegalStateException("ldap.bind-dn 이 있는데 ldap.bind-password 가 비었거나 풀리지 않은 자리표시자다 — 환경 변수 LDAP_BIND_PASSWORD 를 준다")`. 값은 싣지 않는다.
    - `requireTls` 인데 `url` 이 `ldaps://` 로 시작하지 않으면 `IllegalStateException("ldap.require-tls 인데 ldap.url 이 ldaps:// 가 아니다 — StartTLS 는 지원하지 않는다")`.
  - yml:
    - app-ldap `application-prod.yml`: `ldap.bind-password: ${LDAP_BIND_PASSWORD}`(기본값 없음), `ldap.require-tls: true`.
    - app-ldap `application.yml`: `management.endpoint.health.show-details: when-authorized`. 주석은 app-scim 과 같은 이유다.
    - app-ldap 시험 yml: `show-details: always`.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `SyncSchedulerCronTest`:
    - `ApplicationContextRunner` 를 쓴다. 사용자 설정은 `@EnableScheduling` 과, `syncProperties`·`SyncScheduler` 빈이다. 스케줄러의 협력자는 `SyncSchedulerTest.정해둔_동기화` 처럼 가짜로 만든다.
    - `키가_없으면_속성_클래스의_cron_으로_등록된다`: `ScheduledTaskHolder.getScheduledTasks()` 의 `CronTask` 표현식이 `{new SyncProperties().getCron(), new SyncProperties().getPurgeCron()}` 이다.
    - `설정으로_덮으면_그_값이다`: `withPropertyValues("sync.cron=0 30 * * * *")`.
    - `빼기는_끈다`: `sync.purge-cron=-` → 정리 cron 이 등록되지 않는다.
  - `ArchiveSchedulerCronTest`: 같은 세 시험(`ScimSyncProperties`, `ArchiveScheduler`).
  - `SyncPropertiesTest`: `설정_파일의_주기도_매시_정각이다` 를 `설정_파일에는_cron_키가_없다` 로 바꾼다 — `application.yml` 에 `sync.cron`·`sync.purge-cron` 이 없다.
  - `LdapConfigValidationTest`:
    - `전략_이름_오타는_기동을_멈추고_받는_값을_적는다`: `"ditt"` → 메시지에 `group-of-names`·`dit`.
    - `전략_이름은_대소문자를_가리지_않는다`: `"DIT"` 통과.
    - `바인드_DN_이_있는데_비밀번호가_비면_멈춘다`
    - `풀리지_않은_자리표시자는_멈추고_값을_싣지_않는다`: `"${LDAP_BIND_PASSWORD}"` → 메시지 `doesNotContain("${LDAP_BIND_PASSWORD}")`.
    - `익명_바인드는_통과한다`: `bindDn` null.
    - `TLS_를_요구하면_ldaps_만_받는다`: `ldap://` 실패, `ldaps://` 통과.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :connector-ldap:test --tests '*LdapConfigValidationTest*'`, `./gradlew :app-ldap:test --tests '*SyncSchedulerCronTest*' --tests '*SyncPropertiesTest*'`, `./gradlew :app-scim:test --tests '*ArchiveSchedulerCronTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령, 이어서 `./gradlew :app-ldap:test --tests '*LdapSyncEndToEndTest*'`(헬스 세부를 보는 시험)와 `./gradlew :app-scim:test --tests '*ScimKeyLimitEndToEndTest*'`(컨텍스트 기동). PASS.
- [ ] **Step 5: 커밋** — `fix: cron 기본값의 출처를 속성 클래스 하나로 두고(app-scim 에 sync 속성 클래스), ldap.strategy 오타·풀리지 않은 바인드 비밀번호·평문 LDAP(require-tls)를 기동 때 멈추며, app-ldap 헬스 세부를 when-authorized 로 닫는다 (운영 준비)`

---

### Task 7: D7 — 하루 1회 표지의 상태·임대, 아카이빙 기한

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DailyJobClaims.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/DailyOnce.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeDailyJobClaims.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDailyJobClaims.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java` (생성자, `아카이빙한다`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java` (`사람말로` 를 패키지 전용으로)
- Modify: app-ldap `SyncScheduler.java`, `UseCaseConfig.java`, `SyncProperties.java`, app-scim `ArchiveScheduler.java`, `ScimUseCaseConfig.java`, `ScimSyncProperties.java`
- Test: `DailyOnceTest`, `DynamoDbDailyJobClaimsTest`, `SnapshotArchiveUseCaseTest`, `SyncSchedulerTest`, `SyncPropertiesTest`, `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncPropertiesTest.java` (새로)

**Interfaces:**
- Consumes: Task 6 의 `ScimSyncProperties`, `syncProperties` 빈.
- Produces:
  ```java
  // DailyJobClaims
  /** 없거나, CLAIMED 인데 임대가 끝났으면 owner 로 잡는다. DONE 이면 못 잡는다 */
  Mono<Boolean> claim(String job, LocalDate day, String owner, Instant leaseUntil);
  /** owner 가 쥔 표지만 DONE 으로 — 남의 것이면 아무것도 하지 않는다 */
  Mono<Void> complete(String job, LocalDate day, String owner);
  /** owner 가 쥔 표지만 지운다 — 남의 것이면 아무것도 하지 않는다 */
  Mono<Void> release(String job, LocalDate day, String owner);
  ```
  - `DailyOnce(DailyJobClaims claims, Clock clock, Duration jobTimeout)`. `static final Duration 임대_여유 = Duration.ofMinutes(5)`.
  - `public <T> Mono<T> run(String job, Mono<T> work, Predicate<? super T> 성공)`:
    - 잡을 때마다 owner UUID 를 새로 만든다. 임대는 `clock.instant() + jobTimeout + 임대_여유` 다.
    - 잡은 날짜를 끝낼 때도 그대로 쓴다. 자정을 넘겨도 다시 계산하지 않는다.
    - 값이 `성공` 을 통과하면 `complete` 다. 통과하지 못한 값·오류·빈 끝·취소는 `release` 다.
    - 끝내기 호출의 오류는 WARN 하고 삼킨다. 일의 값·오류는 그대로 낸다. 취소일 때의 `release` 는 따로 구독하고 기다리지 않는다.
  - `DynamoDbDailyJobClaims`:
    - 아이템 칸은 `state`(`CLAIMED`/`DONE`), `owner`, `leaseUntil`(N, epoch 초), `expiresAt`(3일)이다.
    - claim: PutItem, 조건 `attribute_not_exists(#pk) OR (#state = :claimed AND #leaseUntil < :now)`. `:now` 는 어댑터 시계다.
    - complete: UpdateItem `SET #state = :done`, 조건 `#owner = :owner`.
    - release: DeleteItem, 조건 `#owner = :owner`.
    - 조건 실패는 complete·release 모두 빈 끝이다(INFO 로그).
  - `FakeDailyJobClaims(Supplier<Instant> 지금)` — 임대 판정을 이 시각으로 한다.
    - 인자 없는 생성자는 `Instant.EPOCH` 고정이라 임대가 끝나지 않는다.
    - `Optional<String> 상태(String job, LocalDate day)` 는 `"CLAIMED"`·`"DONE"`·빈 값이다.
  - `SnapshotArchiveUseCase` 생성자 끝에 `Duration 기한`. `아카이빙한다` 의 일에 `timeout(기한, 오류 IllegalStateException("기한 초과 — " + SyncJobs.사람말로(기한)))` 를 건다. 기존 `onErrorResume` 이 FAILED 로 남긴다.
  - 성공 판정을 넘긴다:
    - app-ldap 정리: `count -> true`
    - app-scim 아카이빙: `run -> run.status() == SyncStatus.SUCCEEDED`
    - app-scim 정리: `count -> true`
  - `DailyOnce`·`SnapshotArchiveUseCase` 결선에 `getJobTimeout()` 을 넘긴다.
  - cron 기본값: `SyncProperties.purgeCron = "0 0 4-8 * * *"`, `ScimSyncProperties.archiveCron = "0 0 3-8 * * *"`, `purgeCron = "0 0 4-8 * * *"`. 자바독에 설계 §9 의 UTC 자정 조건을 적는다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `DailyOnceTest`:
    - 공통: `AtomicReference<Instant> 지금` 하나로 가짜 표지(`지금::get`)와 `DailyOnce` 의 시계(시험 안의 작은 `Clock` 하위 클래스)를 함께 움직인다. jobTimeout 은 30분이다.
    - 기존 두 시험은 `run(job, 작업, v -> true)` 로 바꾼다.
    - `실패하면_풀어_같은_날_다시_잡힌다`: 첫 일은 오류 → 둘째 일이 돈다.
    - `성공이_아니면_풀어_다시_잡힌다`: 판정 false.
    - `성공하면_DONE_이고_같은_날은_건너뛴다`: `상태` 는 `"DONE"`, 둘째 일은 돌지 않는다.
    - `임대가_끝난_CLAIMED_는_다시_잡힌다`: 가의 일은 `Sinks.one()` 으로 매달아 둔다. `지금` 을 36분 옮기면 나가 잡아 돈다.
    - `남의_표지는_풀지_않는다`: 위에 이어 나의 일도 매달아 두고 가의 일을 오류로 끝낸다 → `상태` 는 `"CLAIMED"`(나의 것). 나의 일이 성공하면 `"DONE"`.
  - `DynamoDbDailyJobClaimsTest`(Local, 움직이는 시계):
    - 기존 셋을 새 시그니처로 바꾼다.
    - `표지에_상태_owner_임대_만료를_단다`
    - `DONE_이면_다시_잡지_못한다`
    - `임대가_끝난_CLAIMED_는_다른_owner_가_잡는다`
    - `죽은_owner_의_늦은_완료와_해제는_새_표지를_바꾸지_않는다` — Review Focus:
      - A 가 임대 T+35분으로 잡는다. 시계를 T+36분으로 옮기면 B 가 잡는다(true).
      - A 의 `complete` 뒤 아이템은 `CLAIMED`·owner B 다. A 의 `release` 뒤에도 아이템이 있다.
      - B 의 `complete` 뒤에는 `DONE` 이다.
  - `SnapshotArchiveUseCaseTest.기한을_넘기면_FAILED_기한_초과로_끝낸다`: 가상 시간, 기한 30분, 체커가 답하지 않는다(`Mono.never()`) → 기록 FAILED, 메시지 `"기한 초과 — 30분"`.
  - `SyncPropertiesTest.정리는_하루_창_안에서_매시_시도한다`: `"0 0 4-8 * * *"`. `ScimSyncPropertiesTest` 에 아카이빙·정리 창 둘.
  - `SyncSchedulerTest`: `new DailyOnce(claims, 오늘, Duration.ofMinutes(30))` 으로 고친다.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*DailyOnceTest*' --tests '*SnapshotArchiveUseCaseTest*'`, `./gradlew :storage-dynamodb:test --tests '*DynamoDbDailyJobClaimsTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `DailyOnce` 자바독은 표지를 쓰는 세 경우(실패·인스턴스 죽음·성공 아님)와 임대 규칙으로 다시 쓴다(D12 목록의 `DailyOnce`).
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, `./gradlew :storage-dynamodb:test --tests '*DynamoDbDailyJobClaimsTest*'`, `./gradlew :app-ldap:test --tests '*SyncSchedulerTest*' --tests '*SyncPropertiesTest*' --tests '*SyncSchedulerCronTest*'`, `./gradlew :app-scim:test --tests '*ScimSyncPropertiesTest*' --tests '*ArchiveSchedulerCronTest*'`. PASS.
- [ ] **Step 5: 커밋** — `fix: 하루 1회 표지에 상태(잡음·완료)·owner·임대를 두어 실패·인스턴스 죽음·성공 아닌 아카이빙이면 풀고 하루 창 안에서 매시 다시 시도하며, 아카이빙에 작업 기한을 준다 (운영 준비)`

---

### Task 8: D10a — 아카이빙의 순서와 건수

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java` (`archive`, `차례로_지운다`, `복구결과`)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java`

**Interfaces:**
- Consumes: Task 7 의 생성자(`Duration 기한`).
- Produces: `archive()` 의 순서와 결론(설계 §12):
  1. 비교한 직후 `수를_알린다(여분, 빠짐)`.
  2. 스냅샷 저장. 오류는 잡아 두고 다음 단계를 막지 않는다.
  3. `차례로_지운다`.
  4. 결론:
     - 저장 성공 → SUCCEEDED, `writtenCount = actual.size()`, `snapshotId` 는 그 스냅샷, 메시지는 지금처럼 `복구.메시지()`.
     - 저장 실패 → FAILED, `writtenCount = 0`, `snapshotId = null`, 메시지 `"스냅샷 저장 실패 — " + 원인 메시지` 에 복구 메시지가 있으면 `" / " + 복구 메시지` 를 잇는다.
     - 어느 쪽이든 `deletedCount` 는 직원마다 `writeResult().deleted().size()` 의 합이다. `failureCount` 는 `writeResult().failures().size()` 의 합에, 오류로 끝난 직원의 `여분` 가운데 그 직원의 `direct_member` 줄 수를 더한 것이다.
  - `복구결과` 에 `long 지운줄`, `long 실패줄` 을 더한다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `관찰자는_스냅샷_저장보다_먼저_불린다`: 관찰자가 불릴 때 `snapshots` 에 저장된 것이 없다.
  - `스냅샷_저장이_실패해도_관찰자와_남은_회수_지우기는_돈다`: `snapshots.failSave(new IllegalStateException("디스크"))`, 남은 줄 있는 kim → `writer.stored` 에서 kim 의 줄이 빠졌다. 관찰자가 불렸다. 기록 FAILED, 메시지는 `"스냅샷 저장 실패 — 디스크 / 어긋난 직원 1명 다시 맞춤(실패 0명)"`.
  - `지운_줄과_실패한_줄을_센다`: kim 의 남은 줄 둘은 지워지고 lee 의 남은 줄 하나는 `failFor` → `deletedCount` 2, `failureCount` 1, `writtenCount` 는 실제 줄 수.
  - `다시_맞추기가_오류로_끝난_직원의_남은_줄은_실패로_센다`: 오류를 내는 지우기 함수, 그 직원의 남은 줄 2 → `failureCount` 2.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*SnapshotArchiveUseCaseTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. 클래스 자바독에 건수의 뜻(쓴 수 = 스냅샷 줄 수, 지운 수·실패 수 = 복구의 줄 합)을 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test --tests '*SnapshotArchiveUseCaseTest*'`, PASS.
- [ ] **Step 5: 커밋** — `fix: 아카이빙은 비교 직후 어긋남 수를 알리고, 스냅샷 저장과 남은 회수 지우기를 서로 막지 않게 돌리며, 지운 줄·실패한 줄을 실행 기록에 센다 (운영 준비)`

---

### Task 9: D10b — 죽은 RUNNING 기록을 나이로도 닫는다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeSyncRunRepository.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/StaleRuns.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SyncJobs.java` (생성자, `남은_기록을_닫는다`, `살펴볼_기록` 삭제)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java` (`건너뛴다`, `아카이빙한다`)
- Modify: `app-ldap/.../UseCaseConfig.java`, `app-scim/.../ScimUseCaseConfig.java` (`SyncJobs` 7인자)
- Test: `SyncJobsTest`, `SnapshotArchiveUseCaseTest`, `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`

**Interfaces:**
- Consumes: Task 7·8 의 `SnapshotArchiveUseCase`(같은 파일을 이어 고친다).
- Produces:
  - `Flux<SyncRun> findRunning()` — 이번 달·지난달 파티션의 RUNNING 기록 전부, 최신순이다. DynamoDB 는 `findRecent` 와 같은 Query 에 `filterExpression("#status = :running")` 을 더한다. 가짜는 저장된 기록을 거른다.
  - `SyncJobs(SyncRunRepository, MutationLock, Duration renewInterval, LockObserver, Duration timeout, AuditRecorder, Clock)`. 기존 두 생성자는 `Clock.systemUTC()` 로 위임한다. 두 앱 결선은 `Clock` 빈을 넘긴다.
  - `final class StaleRuns`:
    - `static final Duration 여유 = Duration.ofMinutes(10)`
    - `static final String 기한_경과_사유 = "비정상 종료로 중단(기한 + 10분 경과)"`
    - `static boolean 오래됐다(SyncRun run, Instant now, Duration jobTimeout)` — `startedAt < now − (jobTimeout + 여유)`
    - `static Mono<Void> 닫는다(SyncRunRepository runs, String 지금_기록, Function<SyncRun, String> 사유)` — `findRunning()` 에서 자기 기록을 빼고 사유가 null 이 아닌 것만 `abandon` 한다. 로그와 오류 삼킴은 지금의 `남은_기록을_닫는다` 를 옮긴다.
  - SyncJobs 의 사유 함수(설계 §12):
    - 같은 source 이고 ARCHIVE 가 아니면 `비정상_종료_사유`
    - 아니고 `오래됐다(run, clock.instant(), timeout)` 이면 `기한_경과_사유`
    - 아니면 null
  - SnapshotArchiveUseCase: `runs.start` 직후(두 갈래 모두) `오래됐다(run, clock.instant(), 기한) ? 기한_경과_사유 : null` 로 닫는다. 실패해도 아카이빙은 계속한다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `SyncJobsTest`:
    - 준비의 `jobs` 를 `Clock.fixed(지금, UTC)` 7인자로 만든다. 시스템 시계면 심은 기록이 모두 "오래됐다" 가 돼 기존 `끝나지_못한_기록을_닫는다` 가 깨진다. 그 시험의 기대는 그대로다.
    - `최근_100건_밖의_RUNNING_도_닫는다`: 죽은 SCIM 재적재 RUNNING 을 먼저 심고, 그 뒤 끝난 기록 100건을 심는다 → 닫힌다.
    - `오래된_아카이빙_기록은_닫고_최근_것은_둔다`: 기한 1분이다. ARCHIVE RUNNING 을 `지금 − 12분`·`지금 − 5분` 에 심는다 → 앞의 것은 FAILED(`기한_경과_사유`), 뒤의 것은 RUNNING.
  - `SnapshotArchiveUseCaseTest.아카이빙을_시작할_때_오래된_기록을_닫는다`: 기한 30분, `지금 − 41분` 의 ARCHIVE RUNNING → FAILED(`기한_경과_사유`). `지금 − 5분` 의 것은 그대로다.
  - `DynamoDbSyncRunRepositoryTest.두_달_파티션의_RUNNING_만_찾는다`: 움직이는 시계로 지난달에 하나 시작하고, 이번 달에 셋 시작해 둘을 끝낸다 → `findRunning()` 은 2건(지난달 1, 이번 달 1).
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*SyncJobsTest*' --tests '*SnapshotArchiveUseCaseTest*'`, `./gradlew :storage-dynamodb:test --tests '*DynamoDbSyncRunRepositoryTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `SyncJobs` 클래스 자바독의 규칙 2 를 두 규칙으로 고친다. 비용(app-ldap 매시 두 달치 약 1,500건 Query)을 `findRunning` 자바독에 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, `./gradlew :storage-dynamodb:test --tests '*DynamoDbSyncRunRepositoryTest*'`, `./gradlew :app-ldap:compileJava :app-scim:compileJava`. PASS.
- [ ] **Step 5: 커밋** — `fix: 락을 쥔 회차는 두 달 파티션의 RUNNING 을 모두 보고 닫으며, 동기화·재적재·아카이빙 시작은 기한 + 10분이 지난 RUNNING 을 종류와 상관없이 닫는다 — 최근 100건 밖과 아카이빙 기록이 RUNNING 으로 남던 것을 막는다 (운영 준비)`

---

### Task 10: D10c — 전부 보류의 건수, FORCED_REBUILD, 차단기 메시지

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/model/SyncTrigger.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/LedgerAlignment.java` (`align`, `commit`)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java` (`비교해_맞춘다` 의 차단기 갈래)
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/RebuildUseCase.java` (`start`)
- Test: `FullSyncUseCaseTest`, `RebuildUseCaseTest`, `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncMetricsTest.java`

**Interfaces:**
- Produces:
  - `static SyncOutcome SyncOutcome.held(TupleWriteResult result, String reason)`: ABORTED, `written = result.written().size()`, `deleted = 0`, `failure = result.failures().size()`, 스냅샷 null, 메시지 reason.
  - `LedgerAlignment.align` 의 `heldEntirely()` 갈래가 `SyncOutcome.held(reconciliation.result(), reconciliation.heldReason())` 를 낸다.
  - `SyncTrigger.FORCED_REBUILD` — "LDAP 재적재 + 삭제 가드 우회". `RebuildUseCase.start` 는 `force ? FORCED_REBUILD : REBUILD` 로 남긴다. 락 용도는 REBUILD 그대로다.
  - `static final String FullSyncUseCase.현재상태_교체 = " — 현재상태는 디렉터리대로 교체했다"`(패키지 전용). 붙이는 곳은 둘뿐이다:
    - `비교해_맞춘다` 의 `onErrorResume(TupleWriteAbortedException)` → `stopped.getMessage() + 현재상태_교체`
    - `LedgerAlignment.commit` 에서 `!revokedOnly() && stopReason() != null` 일 때의 결론 메시지 끝
  - 붙이지 않는 곳: `비활성_회수만_반영한다` 의 차단기, revokedOnly 커밋, 장부 맞추기 쓰기 단계의 차단기(오류로 끝남).

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `FullSyncUseCaseTest`:
    - `전부_보류한_장부_맞추기도_쓴_수를_남긴다`: 기준선 없음, 장부의 대부분이 목표 밖 → ABORTED. `writtenCount` 는 쓴 D 의 줄 수(> 0), `deletedCount` 0.
    - `차단기로_멈춘_비교_경로는_교체했다고_적는다`: `abortWhen` → FAILED, `message()` 가 `" — 현재상태는 디렉터리대로 교체했다"` 로 끝난다.
    - `비활성_회수만_반영하다_차단기로_멈추면_교체했다고_적지_않는다`
  - `RebuildUseCaseTest`:
    - `force_재적재는_FORCED_REBUILD_로_남는다`, `force_없는_재적재는_REBUILD_다`
    - `장부_맞추기의_지우기가_차단기로_멈추면_교체했다고_적는다`
    - `전부_보류한_재적재도_쓴_수를_남긴다`
  - `SyncMetricsTest.전부_보류_회차의_쓴_수도_센다`: ABORTED, `writtenCount` 5 → `sync.tuples.written` 5, `sync.guard.aborted` 1.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :core:test --tests '*FullSyncUseCaseTest*' --tests '*RebuildUseCaseTest*'`, `./gradlew :app-ldap:test --tests '*SyncMetricsTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `SyncOutcome.held` 자바독은 설계 §12 를 따른다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test`, `./gradlew :app-ldap:test --tests '*SyncMetricsTest*' --tests '*AdminSyncControllerTest*'`. PASS.
- [ ] **Step 5: 커밋** — `fix: 전부 보류한 장부 맞추기도 쓴 수를 남기고, force 재적재를 FORCED_REBUILD 로 기록하며, 현재상태를 교체한 차단기 회차에만 그 사실을 메시지에 적는다 (운영 준비)`

---

### Task 11: D11 — 빈 소속 그림은 데이터 오류

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/EmptyMembershipGuard.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java` (`UnmatchedMemberGuard.확인한다` 다음)
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java` (`!users.isEmpty() && 소속수 == 0` 검사를 대신한다)
- Test: `GroupOfNamesStrategyTest`, `DitStrategyTest`, `PrimaryGroupTest`, `LdapDirectorySnapshotSourceTest` (모두 `connector-ldap/src/test/java/dev/starryeye/organization/ldap/`)

**Interfaces:**
- Produces: `static void EmptyMembershipGuard.확인한다(DirectorySnapshot 그림, String 세부)`
  - 사람 소속은 조직마다 `MemberType.USER` 이고 그 id 가 `그림.users()` 에 있는 멤버의 수다. 활성 여부는 보지 않는다.
  - 0 이면 `DirectoryDataException("디렉터리 그림에 사람 소속이 하나도 없다(사용자 %d명, 조직 %d개, %s) — 비어 있는 검색의 베이스·필터를 확인하라")` 이다.
  - 세부 문구:
    - groupOfNames: `"member 값 " + 멤버값수 + "개"`. `UnmatchedMemberGuard` 를 먼저 부르고 그대로 둔다.
    - DIT: `"부모 조직을 찾은 직원 0명 — root-dn 과 org-unit-filter 가 직원이 있는 곳을 조직으로 읽는지 확인하라(AD 의 CN=Users 는 OU 가 아니다, 점검 S22) — " + 건너뛴_직원.요약()`
- 결과(설계 §13): `LdapDirectorySnapshotSource` 가 `DirectoryDataException` 을 다시 읽지 않는다. 회차는 FAILED 이고 `force` 로 넘길 수 없다. 동기화와 재적재가 같은 `fetchAll` 을 탄다(`RebuildUseCase.rebuild`).

- [ ] **Step 1: 실패하는 시험을 쓴다** — 0건은 검색 필터를 `"(objectClass=없는클래스)"` 로 바꾼 설정이나 `server.delete(dn)` 으로 만든다.
  - `GroupOfNamesStrategyTest`:
    - `사용자_검색이_0건이면_데이터_오류다`: `DirectoryDataException`.
    - `조직_검색이_0건이면_데이터_오류다`: 메시지에 `조직 0개`.
    - `member_대조가_0건이면_가드가_DN_힌트로_멈춘다`: `MemberMatchingFailedException`.
    - `일부만_대조되면_통과한다`: 직원 하나를 지워도 성공.
  - `PrimaryGroupTest.기본_그룹_소속만_있고_DN_대조가_0건이면_가드가_멈춘다`: member 값을 모두 없는 DN 으로 두고 RID 소속만 남긴다 → `MemberMatchingFailedException`.
  - `DitStrategyTest`:
    - `사용자_검색이_0건이면_데이터_오류다`: 메시지에 `사용자 0명`.
    - `조직_검색이_0건이면_데이터_오류다`
    - `부모_조직을_찾은_직원이_없으면_데이터_오류다`: 메시지에 `CN=Users`.
    - `일부만_소속되면_통과한다`
  - `LdapDirectorySnapshotSourceTest.빈_소속_그림은_다시_읽지_않는다`: 재시도 횟수를 3 으로 두어도 읽기는 한 번이고 `DirectoryDataException` 이다(재적재도 같은 `fetchAll` 이다).
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :connector-ldap:test --tests '*GroupOfNamesStrategyTest*' --tests '*DitStrategyTest*' --tests '*PrimaryGroupTest*' --tests '*LdapDirectorySnapshotSourceTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. 클래스 자바독에 근거(SCIM 빈 조직도 규칙과 같은 모양, RFC 4511 의 0건은 성공, 가드를 남기는 까닭)를 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :connector-ldap:test`, PASS. 다른 시험이 빈 그림으로 성공을 기대하고 있었으면 그 기대를 고치고 까닭을 시험 주석 한 줄로 남긴다.
- [ ] **Step 5: 커밋** — `fix: 두 LDAP 전략 모두 사람 소속이 하나도 없는 디렉터리 그림을 데이터 오류로 멈춘다(사용자 0·조직 0·대조 0) — 빈 그림이 전부 보류와 force 로 장부를 비우던 길을 막는다 (운영 준비)`

---

### Task 12: D8 — 관리 API 예외 처리기

**Files:**
- Move: `connector-scim/src/main/java/dev/starryeye/organization/scim/TemporaryFailureClassifier.java` → `core/src/main/java/dev/starryeye/organization/core/port/TemporaryFailureClassifier.java`
- Move: `connector-scim/src/test/java/dev/starryeye/organization/scim/TemporaryFailureClassifierTest.java` → `core/src/test/java/dev/starryeye/organization/core/port/TemporaryFailureClassifierTest.java`
- Modify: `core/build.gradle` — `implementation 'com.fasterxml.jackson.core:jackson-core'`(버전은 Boot BOM)
- Modify(import): `ScimRouter.java`, `ScimConfig.java`, `ScimErrorTranslationTest.java`, `ScimErrorSignalsEndToEndTest.java`
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminApi.java`
- Create: `admin-api/src/main/java/dev/starryeye/organization/admin/AdminExceptionHandler.java`
- Modify: `AdminQueryConfig.java`, `AdminQueryController.java`, `AuditHistoryController.java`, app-ldap·app-scim `AdminSyncController.java` (`@AdminApi`)
- Test: `admin-api/src/test/java/dev/starryeye/organization/admin/AdminExceptionHandlerTest.java` (새로), `AdminQueryControllerTest`, app-ldap `AdminSyncControllerTest`, app-scim `ScimErrorSignalsEndToEndTest`

**Interfaces:**
- Produces:
  - `TemporaryFailureClassifier` 의 공개 표면은 그대로다(생성자·`표지만()`·`재시도_대기`). 패키지만 `core.port` 다.
    - 자리를 고른 까닭: 두 HTTP 층(connector-scim, admin-api)이 모두 core 에 기댄다. 다른 공통 모듈은 없다.
    - core 에는 Jackson 이 없다. Jackson 예외 제외 규칙(`JacksonException`)을 지키려고 jackson-core 하나만 더한다. 두 앱에는 이미 실린다.
    - 이 한 클래스 때문에 새 모듈을 만드는 것보다 싸다.
  - `@Target(TYPE) @Retention(RUNTIME) public @interface AdminApi` — 네 컨트롤러에 단다.
  - `@RestControllerAdvice(annotations = AdminApi.class) public class AdminExceptionHandler`:
    - 생성자는 `AdminExceptionHandler(TemporaryFailureClassifier)` 하나다. 컴포넌트 스캔으로 등록된다.
    - `@ExceptionHandler(Throwable.class) ResponseEntity<ProblemDetail> handle(Throwable error)`
  - `AdminQueryConfig` 에 `@Bean TemporaryFailureClassifier temporaryFailureClassifier(ObjectProvider<TemporaryFailureRecognizer> recognizers)` — `orderedStream()` 으로 모은다.
- 판정(위에서부터, 원인 사슬은 같은 예외를 두 번 보지 않고 따라간다):
  1. 사슬에 `ResponseStatusException` 이 있으면 그 상태다. detail 은 `getReason()`, 없으면 상태 문구다. `ServerWebInputException` 도 여기다(400, 스프링의 이유).
  2. 사슬에 `LockUnavailableException` 이 있으면 409, detail 은 그 메시지다.
  3. `classifier.재시도_대기(error)` 가 있으면 503, 헤더 `Retry-After` 는 초를 올림하고 최소 1이다(`ScimRouter.재시도_초` 와 같다). detail 은 D8 503 문구다. WARN 로그.
  4. 그 밖은 500, detail 은 D8 500 문구다. 예외는 ERROR 로그로만 남긴다.
  - 본문은 `ProblemDetail.forStatusAndDetail` — `type` about:blank, `title` 상태 문구, `status`, `detail`.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `AdminExceptionHandlerTest`:
    - `WebTestClient.bindToController(던지는_컨트롤러).controllerAdvice(new AdminExceptionHandler(TemporaryFailureClassifier.표지만()))` 로 준비한다. `던지는_컨트롤러` 는 `@AdminApi @RestController` 이고 경로마다 정한 예외를 던진다.
    - 시험:
      - `감싼_락_경합은_409_이고_사유를_싣는다`: `new ResponseStatusException(CONFLICT, "다른 동기화·재적재가 진행 중입니다", LockUnavailableException.잡혀_있다(SYNC))` → 409, `Content-Type` `application/problem+json`, `$.type` `about:blank`, `$.title` `Conflict`, `$.detail` 그 문구, `Retry-After` 없음.
      - `원인_사슬의_락_경합은_503_이_아니라_409_다`: `LockUnavailableException.잡혀_있다(REBUILD)` 그대로 → 409.
      - `두_겹_감싼_409_도_409_다` — Review Focus: `new IllegalStateException("감쌈", 위의 ResponseStatusException)` → 409, 같은 detail.
      - `일시_장애는_503_과_Retry_After_다`: `TemporaryFailureException("x", 10초)` → 503, `Retry-After: 10`, detail 은 D8 503 문구.
      - `입력_오류는_400_이고_스프링의_이유를_싣는다`: `@RequestParam int n` 에 `?n=abc` → 400, problem+json.
      - `그_밖은_500_이고_예외_문구를_싣지_않는다`: `NullPointerException("비밀")` → 500, `$.detail` 은 `내부 오류`, 본문에 `비밀` 없음.
  - app-ldap `AdminSyncControllerTest`:
    - 준비에 `.controllerAdvice(new AdminExceptionHandler(new TemporaryFailureClassifier(List.of(new DynamoDbTemporaryFailures()))))` 를 더한다.
    - `락을_못_잡으면_409다` 에 `$.detail` 을 더한다.
    - 새로 `실행_기록을_열지_못한_일시_장애는_503_이다`: `fullSync.start` 가 `ProvisionedThroughputExceededException` → 503 + `Retry-After`.
    - `동기_예외는_5xx다` 는 500 과 `$.detail` 을 본다.
  - `AdminQueryControllerTest`: 준비에 advice 를 결선하고 `검색_파라미터가_없으면_400이다` 에 problem+json 을 더한다.
  - `ScimErrorSignalsEndToEndTest.관리_API_재적재가_락_경합이면_409_problem_details_다`: 시험이 REBUILD 로 락을 쥔 채 `POST /admin/sync/rebuild` → 409, problem+json, `$.detail` 에 `REBUILD`.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :admin-api:test --tests '*AdminExceptionHandlerTest*'`
- [ ] **Step 3: 구현한다** — 분류기를 옮기고 Interfaces 대로 구현한다. 처리기 자바독에 판정 순서의 까닭(설계 §10: 락 경합은 `TemporaryFailureException` 의 하위라 분류기를 먼저 보면 409 가 503 이 된다)을 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :core:test --tests '*TemporaryFailureClassifierTest*'`, `./gradlew :connector-scim:test`, `./gradlew :admin-api:test`, `./gradlew :app-ldap:test --tests '*AdminSyncControllerTest*'`, `./gradlew :app-scim:test --tests '*ScimErrorSignalsEndToEndTest*'`. PASS.
- [ ] **Step 5: 커밋** — `feat: 관리 API 네 컨트롤러에 예외 처리기를 두어 락 경합은 409 와 사유, 일시 장애는 503 과 Retry-After, 그 밖은 500 으로 problem details 를 내고, 분류기를 core 로 옮겨 SCIM 과 함께 쓴다 (운영 준비)`

---

### Task 13: D9 — 직원 응답의 externalId

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/query/UserSummary.java`, `EmployeeDetail.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/AdminQueryUseCase.java` (`findEmployeesByExternalId`, `toDetail`)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectorySearchRepository.java` (`toUserSummary`)
- Modify: `UserSummary` 를 만드는 시험·가짜 — `FakeSearchRepositoryTest`, `AdminQueryControllerTest`, `DynamoDbDirectorySearchRepositoryTest`(`grep -rn "new UserSummary(\|new EmployeeDetail(" */src`)
- Test: `DynamoDbDirectorySearchRepositoryTest`, `AdminQueryUseCaseTest`, `AdminQueryControllerTest`

**Interfaces:**
- Consumes: Task 1 의 GSI2 프로젝션(`externalId` 포함).
- Produces:
  - `record UserSummary(String employeeId, String userName, String displayName, boolean active, String externalId)`
  - `record EmployeeDetail(String employeeId, String userName, String displayName, PersonName name, String email, boolean active, String externalId, List<AccessPath> paths, boolean truncated)`
  - `externalId` 는 SCIM 에서 비어 있을 수 있다(null). JSON 칸 이름은 `externalId` 다.
  - 경로별로 값을 얻는 곳(설계 §11):
    - 상세: 읽은 `DirectoryUser.externalId()`
    - userName·displayName 검색: `Attrs.str(item, "externalId")`
    - externalId 검색: 본 테이블에서 다시 읽은 `user.externalId()`

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `DynamoDbDirectorySearchRepositoryTest`:
    - `userName_검색은_externalId_를_싣는다`
    - `표시명_검색은_externalId_를_싣는다`: GSI2 프로젝션으로 온다.
    - `externalId_가_없는_직원은_빈_값이다`
  - `AdminQueryUseCaseTest`:
    - `externalId_검색_결과에_externalId_가_있다`
    - `직원_상세에_externalId_가_있다`
  - `AdminQueryControllerTest.직원_응답에_externalId_가_있다`: 검색 `$.items[0].externalId`, 상세 `$.externalId`.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectorySearchRepositoryTest*'`, `./gradlew :core:test --tests '*AdminQueryUseCaseTest*'`, `./gradlew :admin-api:test --tests '*AdminQueryControllerTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `TableInitializer.userDisplayNameIndex` 자바독의 "세 곳이 같아야 한다" 목록에 `externalId` 가 맞는지 본다.
- [ ] **Step 4: 돌려 통과를 본다** — Step 2 명령과 `./gradlew :core:test`, `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`. PASS.
- [ ] **Step 5: 커밋** — `feat: 직원 검색·상세 응답에 externalId 를 싣는다(표시명 검색은 GSI2 프로젝션으로) — README 가 쓰라는 DN 을 관리 API 가 보여 주지 않던 것을 고친다 (운영 준비)`

---

### Task 14: D6 — SCIM 필터 오류 문구와 회수 카운터

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimFilter.java` (`Parser.fail`, `attributePath`, 값 token 오류)
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/RevocationObserver.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (12인자 생성자, 세는 자리 넷)
- Modify: `app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncMetrics.java`, `ScimUseCaseConfig.java` (`incrementalSyncUseCase`)
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimFilterTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncRevocationCountTest.java` (새로)
- Test: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimSyncMetricsTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface RevocationObserver {
      enum Kind {
          DEACTIVATE("deactivate"), DELETE_USER("delete_user"), REMOVE_MEMBER("remove_member"), DELETE_GROUP("delete_group");
          // tag() 가 이 문자열을 준다
      }
      void revoked(Kind kind, int count);
      RevocationObserver NOOP = (kind, count) -> { };
  }
  ```
  - `IncrementalSyncUseCase` 에 기존 11인자 + `RevocationObserver` 의 12인자 생성자를 더한다. 11인자는 `NOOP` 으로 위임한다. 운영 결선은 `metrics` 를 넘긴다.
  - `ScimSyncMetrics implements RevocationObserver`: `count > 0` 이면 `scim.revocations{kind=<tag>}` 를 `count` 만큼 올린다.
  - 세는 자리(설계 §8 표). 모두 그 상태 쓰기가 성공한 뒤 `남긴다` 와 같은 자리에서 센다. 관찰자 예외는 WARN 하고 삼킨다.
    - `직원을_저장한다(저장본, 저장할것, …)`: 저장본이 null 이 아니고 `저장본.active() && !저장할것.active()` 면 `DEACTIVATE` 1. 삭제가 일부 실패해 비활성으로 남기는 경우도 이 자리를 탄다.
    - `removeUserInternal` 의 성공 갈래: `state.deleteUser` 뒤 `user.active()` 면 `DELETE_USER` 1.
    - `upsertGroupInternal`·`changeGroup` 의 커밋: 뺀 멤버(`existingOrEmpty − reconciled`, `뺄것`) 가운데 `MemberType.USER` 수만큼 `REMOVE_MEMBER`.
    - `조직_삭제를_커밋한다`: 다 지웠으면 `group.members()`, 일부면 `지운멤버` 가운데 `MemberType.USER` 수만큼 `DELETE_GROUP`.
  - `static String ScimFilter.Parser.앞부분(String 값)`: 코드 포인트 100개를 넘으면 앞 100개 + `"…"`. 원문(`— filter: `), 지원하지 않는 path, 지원하지 않는 token 세 자리에 쓴다.

- [ ] **Step 1: 실패하는 시험을 쓴다**
  - `ScimFilterTest`:
    - `긴_필터의_오류_문구는_원문을_100자로_자른다`: 10,000자 필터 → `getMessage().length()` < 400, `…` 를 품는다.
    - `긴_속성_경로의_오류_문구도_자른다`
    - `긴_값_토큰의_오류_문구도_자른다`: `userName eq ` 뒤 10,000자 토큰.
  - `IncrementalSyncRevocationCountTest`:
    - 기록하는 관찰자를 쓴다. 준비는 `IncrementalSyncRevocationIntentTest` 를 본뜬다.
    - 시험:
      - `비활성화가_저장되면_deactivate_1`
      - `이미_비활성인_직원의_수정은_세지_않는다`
      - `삭제가_일부_실패해_비활성으로_남기면_deactivate_이고_재시도로_지워질_때는_세지_않는다`: 합 `{DEACTIVATE=1}`.
      - `활성_직원을_지우면_delete_user_1`
      - `PATCH_로_뺀_직원_수만큼_remove_member`: 직원 둘과 하위 조직 하나를 뺀다 → 2.
      - `PUT_으로_빠진_직원도_remove_member`
      - `조직을_지우면_직원_멤버_수만큼_delete_group`
      - `그룹_쪽이_실패하면_커밋된_만큼만_세고_재시도가_나머지를_센다`: PATCH 로 둘을 빼는데 하나는 `failFor` → 1. 실패를 풀고 재시도 → 합 2.
  - `ScimSyncMetricsTest.회수를_종류별로_센다`: 태그 넷, 0 은 올리지 않는다.
- [ ] **Step 2: 돌려 실패를 본다** — `./gradlew :connector-scim:test --tests '*ScimFilterTest*'`, `./gradlew :core:test --tests '*IncrementalSyncRevocationCountTest*'`, `./gradlew :app-scim:test --tests '*ScimSyncMetricsTest*'`
- [ ] **Step 3: 구현한다** — Interfaces 대로 구현한다. `RevocationObserver` 자바독에 "막지 않고 보이게만 한다(설계 §17.4)" 와 직원 하나를 한 번만 세는 규칙을 적는다.
- [ ] **Step 4: 돌려 통과를 본다** — `./gradlew :connector-scim:test`, `./gradlew :core:test`, `./gradlew :app-scim:test --tests '*ScimSyncMetricsTest*' --tests '*ScimKeyLimitEndToEndTest*'`. PASS.
- [ ] **Step 5: 커밋** — `feat: SCIM 필터 오류 문구의 입력 조각을 각각 100자로 자르고, 커밋된 회수를 scim.revocations{kind} 로 센다(비활성화·직원 삭제·멤버 빼기·조직 삭제) (운영 준비)`

---

### Task 15: D12 — 루트 README·자바독·주석 정정

**Files:**
- Modify: `README.md`
- Modify: `docker-compose.yml`
- Modify(자바독·주석): `LedgerAlignment.java`, `IncompleteAttributeReadException.java`, `core/src/testFixtures/.../fixture/Landmarks.java`, `storage-dynamodb/.../Keys.java`(`EXPIRES_AT`), `SyncScheduler.java`·`ArchiveScheduler.java`(스냅샷 주석), `DynamoDbProperties.java`(`lockRenewInterval`), `DirectoryConflictException.java`, `ScimException.java`(scimType), `FullSyncUseCaseTest.java`(주석)
- 출처: `.superpowers/onboarding/writer-findings.md` C·D·E 절, `.superpowers/onboarding/report-foundation.md` 1~6

**Interfaces:**
- Consumes: Task 1~14 의 동작. 이 과제는 코드 동작을 바꾸지 않는다.

- [ ] **Step 1: 목록을 코드로 다시 확인한다** — 아래 항목마다 지금 코드(이 브랜치의 HEAD)를 읽고, 출처의 서술이 아직 틀렸는지 본다. 이미 맞거나 이 슬라이드가 고친 항목은 건너뛰고 보고에 적는다. `AcquireBackoff`(Task 3)와 `DailyOnce`(Task 7)는 고쳐졌는지만 본다.
- [ ] **Step 2: 루트 README.md 를 고친다**(설계 §14)
  - 틀린 세 문장(설계 §1.11): 비활성 회수만 반영한 다음 회차, 다음 `sync.cron` 이 같은 일을 한다, OpenFGA·DynamoDB 장애는 503
  - 오류 표: 403 행, 표지판을 쓰는 중일 때의 2초 503, 관리 API 의 problem details 표(Task 12)
  - `active` 를 저장하지 않는 경우의 넷째(커밋에 닿은 뒤의 오류)
  - 직원 displayName 필터는 대소문자를 가리지 않는다
  - 조직 PUT 에 members 가 없으면 멤버를 다 지운다
  - `GET /Users/.search` 는 404 다
  - TTL 을 쓰는 아이템 수(다섯)
  - 쓰기 단계의 넷째 값
  - scaleTest 소요 시간(마지막 실행 기준. 머지 전 실행 뒤 컨트롤러가 채울 자리로 둔다)
  - 실행 기록은 두 달 파티션만 읽는다는 함정
  - 시드 생성기 사용법(`generateScaleSeed`, `generateScimSeed`)
  - 이 슬라이드가 바꾼 설정:
    - `dynamodb.create-table-on-startup`·`require-protection`·`api-call-attempt-timeout`·`api-call-timeout`
    - `openfga.create-store`
    - `ldap.require-tls`, `LDAP_BIND_PASSWORD`
    - probes, app-ldap 헬스 세부
    - cron 창과 UTC 자정 조건
  - IAM 권한(설계 §3):
    - 늘: `DescribeTable`·`DescribeTimeToLive`
    - `require-protection` 일 때: `DescribeContinuousBackups`
    - 처음 생성할 때만: `CreateTable`·`UpdateContinuousBackups`
  - `ldaps://` 와 사설 CA(`-Djavax.net.ssl.trustStore`)
  - `scim.revocations` 경보 식 예: 1시간 증가량이 평소보다 크게 늘면 경보
  - 직원 응답의 `externalId`
- [ ] **Step 3: 자바독·주석·`docker-compose.yml` 을 고친다** — Files 목록대로 고친다. `Landmarks` 의 500 은 실제 1,600 으로, `Keys.EXPIRES_AT` 의 넷은 다섯으로 고친다. `docker-compose.yml` 의 옛 과제 번호는 지운다.
- [ ] **Step 4: 컴파일 확인** — `./gradlew :core:compileTestJava :storage-dynamodb:compileJava :connector-ldap:compileJava :connector-scim:compileJava :app-ldap:compileJava :app-scim:compileJava`
- [ ] **Step 5: 커밋** — `docs: 루트 README 의 틀린 서술과 낡은 자바독·주석을 코드대로 고치고, 이 슬라이드의 설정·IAM 권한·경보 식·cron 창 규칙을 적는다 (운영 준비)`

---

### Task 16: D13 — 온보딩 문서 갱신

**Files:**
- Modify: `docs/onboarding/` 의 모든 문서 머리. 내용은 아래 절만 고친다.

**Interfaces:**
- Consumes: Task 1~15. 문서는 코드대로 쓴다. 설계와 코드가 다르면 코드를 따르고 보고한다.

- [ ] **Step 1: 바뀐 절을 고친다**(설계 §14 D13)
  - 11 운영: 새 설정·IAM·probes·경보 식·cron 창 규칙·ldaps 트러스트스토어
  - 12 장애와 복구: 테이블이 없을 때, store 가 없을 때, 모양이 다를 때, PITR 복원본(WARN 과 재적재 권고)
  - 09 관리 API: 오류 응답 표(problem details·409·503·500·400), 직원 응답의 `externalId`
  - 07 재적재·초기화·아카이빙: 아카이빙 순서·건수·기한, 하루 1회 표지(상태·owner·임대·성공 판정)
  - 08 동시성과 작업: 락 획득 시도 한도와 조건부 반납, 죽은 기록 닫기(두 규칙)
  - 05 LDAP 동기화: 빈 소속 규칙, `FORCED_REBUILD`, 전부 보류의 건수, 차단기 메시지
  - 02 용어집: 하루 1회 표지의 상태(잡음·완료)·owner·임대, 시도 한도, `FORCED_REBUILD`
  - 15 결정 색인: 설계 §2 의 D1~D13 과 고치지 않은 두 결정
  - 13 한계와 알려진 문제:
    - §7 에서 고친 항목은 지우거나 "고침(<커밋>)" 으로 바꾼다 — 7.1·7.2(Task 6), 7.3(13), 7.4(12), 7.5(10), 7.6(15), 7.7·7.8(8), 7.9(10), 7.11(2·3), 7.13(11).
    - 7.10·7.12·7.14 는 남긴다. §1.2·§3.1·§6.4·§6.5 도 맞춘다.
- [ ] **Step 2: 기준 커밋 줄을 고친다** — 이 과제를 시작할 때 `git rev-parse --short HEAD`(Task 15 의 커밋)를 읽는다. 모든 문서 머리를 `> 기준 커밋: <해시> (ops-readiness, main 에 머지됨). 코드가 바뀌면 이 문서도 함께 고친다.` 로 바꾼다.
- [ ] **Step 3: 링크를 검사한다** — `python3 -I /private/tmp/claude-501/-Users-starryeye-study-organization/66da4000-5d9c-4738-a02a-3badafe736f4/scratchpad/links.py`. 깨진 링크 0.
- [ ] **Step 4: 커밋** — `docs: 온보딩 문서를 운영 준비 슬라이드의 동작으로 갱신한다 — 운영 설정·IAM·장애 처방·관리 API 오류 표·하루 1회 표지·락 획득·죽은 기록·빈 소속 규칙·용어·결정 색인, 13 §7 의 고친 항목 (운영 준비)`

---

## 머지 전(컨트롤러)
- `./gradlew cleanTest test` 를 돌린 다음 `./gradlew cleanScaleTest scaleTest` 를 돌린다. 한 번에 하나다.
- 규모 시험이 D4 의 시간 한도(10초·60초)에 끊기면 시험 yml 만 늘린다. 그 사실을 설계 §19 에 적는다.
- 설계 §19 결과를 채우고, README 의 scaleTest 소요 시간을 그 실행으로 고친다.
