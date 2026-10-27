# 운영 준비 설계 (2026-10-27)

이 슬라이드는 두 출처에서 남은 운영 결함을 고친다.
- **외부 리뷰(2026-10-19)에서 남은 운영 항목.** N4(SCIM id 내구성), N9(운영 기본값), N11(작은 것들)이다. 근거: `docs/superpowers/specs/2026-10-19-outsider-review/0-종합.md`.
- **온보딩 문서를 쓰면서 코드로 확인한 문제.** `docs/onboarding/13-한계와-알려진-문제.md` §7 과 §1.2·§3.1·§6.5 다.

다음 슬라이드는 인증이다. 사용자가 방향을 정했다: "authorization server 는 그냥 가장 간단하게 준비해서 해봐(spring security)". 이 문서는 인증을 다루지 않는다.

**결정은 모두 내 추천이다.** 사용자가 "추천대로 진행"이라고 했으므로 질문 없이 정했다. 이 문서를 승인하는 것이 §2 의 결정들을 승인하는 것이다.

조사 자료가 둘 있다. 둘 다 git 이 무시하는 위치에 있다.
- `.superpowers/ops-readiness/research-startup-config.md`
- `.superpowers/ops-readiness/research-errors-records.md`

## 1. 문제

### 1.1 배포가 엉뚱한 저장소에 조용히 붙는다 (N4·13 §6.4, High)

**SCIM 배포에서는 DynamoDB 가 직원·조직 id 의 발급처다.** 그런데 테이블을 지켜 주는 장치가 없다.
- 삭제 보호도, PITR 도, IaC 도 없다.
- 문서에 적힌 복구 방법(wipe 뒤 재프로비저닝)은 모든 id 를 바꾼다.
- `create-table-on-startup` 이 운영에서도 켜져 있다. 클래스 기본값, 두 앱의 yml, prod 프로필이 모두 true 다. 그래서 테이블 이름에 오타가 나면 빈 테이블을 새로 만들고 기동해, 신원이 둘로 갈라진다.
- 생성을 끄면 반대 문제가 생긴다. 테이블이 없는지 확인하지 않고 기동하고, 그 뒤 첫 호출부터 실패한다.

**OpenFGA store 에도 같은 문제가 있다.**
- `openfga.store-name` 에 오타가 나면 새 store 를 조용히 만든다(`StoreBootstrapper.sharedResolution`).
- 두 이름의 클래스 기본값이 `organization` 으로 같다.

### 1.2 운영 기본값이 위험하다 (N9·13 §7.11)

- **헬스 세부.** app-ldap 헬스가 `show-details: always` 라서, 인증 없이 세부를 보여 준다. app-scim 은 `when-authorized` 다.
- **LDAP 평문.** LDAP 이 평문 `ldap://` 로 붙는다. TLS 를 요구하는 장치가 없다.
- **DynamoDB 시간 한도.** DynamoDB 클라이언트에 시간 한도가 없다. SDK 기본값으로 재시도가 8번이고 대기 상한이 20초라서, 스로틀이 이어지면 호출 한 번이 수십 초 걸린다.
  - SCIM 쓰기의 락 대기 한도(3초)는 다음 시도 전에만 본다. 날아가는 시도는 끊지 않는다. 그래서 SDK 안의 재시도 동안에는 3초 한도가 소용없다.
- **비밀값.** app-ldap 의 main `application.yml` 에 `ldap.bind-password: adminpassword` 가 있어 jar 에 실려 나간다.

### 1.3 설정 실수가 조용히 지나가거나 엉뚱하게 터진다 (13 §7.1·§7.2)

- **cron 키.** app-ldap 의 `@Scheduled("${sync.cron}")`·`"${sync.purge-cron}"` 에는 기본값이 없다. yml 에 키가 없으면 기동이 실패한다. `SyncProperties` 에 있는 기본값은 아무도 읽지 않는다.
- **전략 이름.** `ldap.strategy` 가 `dit` 가 아니면 오타까지 포함해 groupOfNames 로 돈다(`LdapConfig.ldapMappingStrategy`). DIT 디렉터리를 groupOfNames 로 읽으면 조직이 없는 조직도가 된다.

### 1.4 하루 1회 일이 한 번 실패하면 그날은 다시 돌지 않는다 (N11)

`DailyOnce` 는 표지를 먼저 잡고 일을 한다. 표지를 풀지도, 완료로 적지도 않는다. 그래서 다음 경우 모두 그날의 표지를 써 버린다.
- 일이 실패한 경우
- 인스턴스가 죽은 경우
- 아카이빙이 FAILED 를 값으로 돌려준 경우
- 재적재 중이라 건너뛴 경우(ABORTED)

### 1.5 헬스·모델·노출 (N11)

- **OpenFGA 헬스.** store id 를 한 번 캐시하면 그 뒤로는 OpenFGA 에 묻지 않고 UP 을 낸다(`OpenFgaHealthIndicator`).
- **모델 버전 누적.** 프로세스마다 store 를 처음 해석할 때 모델을 쓴다. 최신 모델과 비교하지 않으므로, 기동할 때마다 모델 버전이 쌓인다.
- **필터 원문 노출.** SCIM 필터 오류의 detail 에 필터 원문을 통째로 싣는다(`ScimFilter.Parser.fail`). `.search` 로 보내면 본문 한도 256KB 까지 그대로 돌려준다.

### 1.6 SCIM 대량 회수가 보이지 않는다 (N11)

삭제 가드는 LDAP 에만 있다. SCIM 의 비활성화·삭제·멤버 빼기가 한꺼번에 몰려도 이를 보여 주는 지표가 없다.

### 1.7 관리 API 오류가 대부분 500 이다 (13 §7.4·§7.12)

관리 API 에는 전역 예외 처리기가 없다. 그래서 다음이 모두 500 이 된다.
- 조회의 DynamoDB 오류: 일시 장애든 아니든 500 이다.
- 락을 잡는 중 일어난 스로틀·네트워크 오류
- 실행 기록을 열지 못한 경우: 작업은 시작하지도 않았다.

409 의 사유 문구도 응답 본문에 실리지 않는다. 같은 일시 장애가 SCIM 에서는 503 이다.

### 1.8 실행 기록과 지표가 실제와 다르다 (13 §7.5·§7.7·§7.8·§7.9·§1.2·§6.5)

- **전부 보류.** 장부 맞추기가 전부 보류로 끝나면 D 를 이미 썼는데도 `writtenCount` 가 0 이다. `sync.tuples.written` 도 세지 않는다.
- **아카이빙.**
  - 스냅샷 저장이 실패하면 드리프트 게이지와 관찰 시각이 갱신되지 않고, 남은 회수 지우기도 돌지 않는다.
  - `deletedCount` 는 지운 줄이 있어도 늘 0 이다.
- **차단기 멈춤.** 차단기로 멈춘 회차(FAILED)도 현재상태를 디렉터리대로 바꾼다. 권한은 틀리지 않는다(§17.3). 하지만 어디에도 그렇게 적혀 있지 않다.
- **재적재 force.** LDAP 재적재에 `force` 를 걸었는지가 실행 기록에 남지 않는다. 트리거는 늘 REBUILD 다.
- **죽은 RUNNING 기록.** 닫는 방법에 한계가 셋 있다.
  - 최근 100건만 본다.
  - app-scim 은 재적재만 이 정리를 부른다.
  - 아카이빙 기록은 TTL 로 사라질 때까지 RUNNING 이다.

### 1.9 빈 소속 그림이 전략마다 다르게 끝난다 (13 §7.13)

| 경우 | 결과 |
|---|---|
| groupOfNames, 사람 대조가 0건 | FAILED |
| groupOfNames, 조직이 0건 | 기준선이 있으면 전부 보류(ABORTED). 첫 적재면 SUCCEEDED(권한 0) |
| DIT, 소속이 0건 | FAILED |

ABORTED 는 `force` 를 불러들인다. 그런데 빈 그림에 `force` 를 걸면 장부가 통째로 지워진다.

### 1.10 직원 응답에 externalId 가 없다 (13 §7.3)

루트 README.md 는 LDAP 에서 "관리 API 가 보여 주는 DN" 을 `?externalId=` 에 쓰라고 한다. 그런데 `UserSummary`·`EmployeeDetail` 에는 그 칸이 없다.

### 1.11 문서가 코드와 다르다 (13 §7.6·§3.1 외)

루트 README.md 에 틀린 문장이 있다.
- "비활성 회수만 반영한 다음 회차는 같은 판정을 다시 받는다." 실제로는 대개 전부 보류가 된다.
- "다음 `sync.cron` 동기화도 같은 일을 한다." 비교 경로는 장부에만 있는 줄을 고치지 못한다.
- "OpenFGA·DynamoDB 장애는 503." 일시 장애가 아닌 오류는 500 이다.

자바독·주석의 낡은 서술은 온보딩 작업이 모아 두었다(`.superpowers/onboarding/writer-findings.md` C·D·E 절, `report-foundation.md` 1~6).

## 2. 결정

| # | 결정 | 누가 | 버린 길 |
|---|---|---|---|
| D1 | 테이블은 운영에서 만들지 않는다. 기동 때 늘 확인하고, 없거나 모양이 다르면 기동을 멈춘다. 운영에서는 삭제 보호와 PITR 을 요구한다 | 내 추천 | 앱이 늘 만들기(지금), IaC 전용 |
| D2 | store 도 운영에서 만들지 않는다. 테이블·store 이름의 클래스 기본값을 없앤다. 모델은 최신과 내용이 다를 때만 쓴다 | 내 추천 | 테이블·store 묶음 표지(§17.1) |
| D3 | 설정 검증: cron 의 출처를 하나로, 전략 이름 엄격, prod 비밀값은 환경에서, app-ldap 헬스 세부 `when-authorized`, `ldap.require-tls` | 내 추천 | StartTLS 지원(§17.2) |
| D4 | DynamoDB 호출에 시간 한도를 둔다. 락 획득 시도는 남은 한도로 끊고, 같은 토큰으로 다시 시도하거나 반납한다 | 내 추천 | 락 전용 클라이언트 |
| D5 | OpenFGA 헬스가 실제로 묻는다. probes 를 켠다(liveness·readiness 에 외부 의존을 넣지 않는다) | 내 추천 | — |
| D6 | SCIM 필터 오류는 입력 조각을 각각 앞 100자만 싣는다. 커밋된 회수를 세는 카운터 `scim.revocations{kind}` 를 둔다 | 내 추천 | 시간 창으로 막기(§17.4) |
| D7 | 하루 1회 표지에 상태를 둔다(잡음·완료). 실패하면 풀고, cron 은 하루 창 안에서 매시 다시 시도한다. 아카이빙에 기한을 준다 | 내 추천 | 해제만(다시 부를 주체가 없다) |
| D8 | 관리 API 에 예외 처리기를 둔다. 일시 장애는 503 + `Retry-After`, 락 경합은 409 + 사유, 응답은 RFC 9457 problem details 로 낸다 | 내 추천 | 전역 `WebExceptionHandler`, `include-message: always` |
| D9 | 직원 응답에 externalId 를 넣는다(GSI2 프로젝션 포함) | 내 추천 | 상세에만 넣기 |
| D10 | 실행 기록을 바로잡는다: 전부 보류도 쓴 수를 남긴다, `FORCED_REBUILD`, 차단기 메시지, 아카이빙 순서·건수, 죽은 기록은 나이로도 닫는다 | 내 추천 | 열린 기록 표 |
| D11 | 빈 소속 그림은 한 규칙으로 데이터 오류다(두 LDAP 전략 공통). DN 대조 가드는 남긴다 | 내 추천 | 문서만 고치기 |
| D12 | 루트 README.md·자바독·주석의 틀린 서술을 고친다 | 내 추천 | — |
| D13 | 온보딩 문서를 갱신한다(바뀐 절과 기준 커밋) | 사용자 규칙 | — |
| — | 취소된 회차의 늦은 쓰기(13 §7.10)는 고치지 않는다. 펜싱 틈과 같은 부류로 받아들인다 | 내 추천 | 협조적 멈춤(§17.5) |
| — | LDAP 쓰기 직전 리스 확인(N11)은 더하지 않는다. 멈춤 한도가 이미 같은 일을 한다 | 내 추천 | 묶음마다 확인(§17.6) |

## 3. D1 — 테이블 기동 검사와 보호

**설정**

| 키 | 클래스 기본값 | 로컬 `application.yml` | `application-prod.yml` |
|---|---|---|---|
| `dynamodb.create-table-on-startup` | false | true | false |
| `dynamodb.require-protection` | false | false | true |

**기동할 때 (`TableInitializer`)**

생성 여부와 상관없이 늘 아래 순서를 탄다.
1. DescribeTable 을 한다.
2. 테이블이 없을 때:
   - 생성이 꺼져 있으면 기동을 멈춘다. 오류 문구: `DynamoDB 테이블 <name> 이 없다 — 처음 설치라면 dynamodb.create-table-on-startup=true 로 한 번 띄우거나 미리 만든다`.
   - 생성이 켜져 있으면 지금처럼 만든다. `require-protection` 이면 두 가지를 더한다.
     - `deletionProtectionEnabled(true)` 로 만든다.
     - ACTIVE 가 된 뒤 `UpdateContinuousBackups` 로 PITR 을 켠다. 실제 AWS 는 막 만든 테이블에 이 요청을 한동안 `ContinuousBackupsUnavailableException` 으로 거절한다고 알려져 있다(확인하지 못함). 그래서 이 예외는 2분 안에서 쉬며 다시 시도한다. 그래도 켜지 못하면 기동을 멈춘다. 오류 문구: `테이블은 만들었으나 PITR 을 켜지 못했다 — 콘솔·CLI 로 켠 뒤 다시 띄운다`. 테이블은 남는다. 다음 기동은 3번의 보호 확인에서 같은 처방을 다시 보인다.
3. 테이블이 있을 때:
   - 모양을 검사한다. 다르면 기동을 멈춘다. 이 프로젝트는 아직 운영 전이라 "테이블을 다시 만든다" 가 처방이다. 검사하는 것:
     - 지금의 GSI2 정렬키
     - GSI1·2·3 의 키
     - GSI2 프로젝션(D9)
     - TTL: `DescribeTimeToLive` 가 `expiresAt` 에 ENABLED(또는 ENABLING)여야 한다. 테이블을 미리 손으로 만들면서 TTL 을 빠뜨리면 실행 기록·감사 이력·책갈피가 끝없이 쌓이기 때문이다.
   - `require-protection` 이면 보호를 확인한다. `TableDescription.deletionProtectionEnabled()` 와 `DescribeContinuousBackups` 의 PITR 상태가 둘 다 켜져 있어야 하고, 아니면 기동을 멈춘다. 오류 문구는 무엇이 꺼져 있는지 적는다.
4. 복원본이면 경고만 한다. `TableDescription.restoreSummary()` 가 있으면 PITR 복원본이다. WARN 을 한 번 남긴다: `PITR 로 복원한 테이블이다 — 장부보다 낡은 기준선일 수 있어 재적재를 권한다(SCIM 은 mode=tuples)`. 자동으로 재적재하지는 않는다. 그러면 "기준선을 믿을 수 없는 회차" 가 넓어져 Read 원칙에 걸린다.

**권한.** 앱에 필요한 IAM 권한이 늘어난다.
- 늘: `DescribeTable`, `DescribeTimeToLive`
- `require-protection` 일 때: `DescribeContinuousBackups`
- 처음 생성할 때만: `CreateTable`, `UpdateContinuousBackups`

루트 README.md 와 온보딩 11 에 적는다.

**시험**
- DynamoDB Local 2.5.3 은 삭제 보호를 받아들이고 지킨다. 2026-10-27 에 확인했다: 생성·조회·삭제 거절·끄기가 된다. 그래서 생성할 때의 보호 켜기와 있는 테이블의 보호 확인은 Local 로 시험한다.
  - 시험 도우미의 테이블 지우기(`DynamoDbTestSupport.dropTableIfExists` 등)는 보호를 먼저 끄거나, 그 시험이 `require-protection=false` 로 돈다.
- Local 은 `UpdateContinuousBackups`·`DescribeContinuousBackups` 를 UnknownOperation 으로 거절한다. 2026-10-27 에 확인했다. 그래서 PITR 갈래는 가짜 클라이언트로 단위 시험한다.
- "생성 꺼짐 + 테이블 없음 → 기동 실패", "모양이 다름 → 기동 실패" 를 시험한다.

## 4. D2 — store 생성 스위치와 모델 비교

**store 생성 스위치.** `openfga.create-store` 를 둔다.

| 위치 | 값 |
|---|---|
| 클래스 기본값 | false |
| 로컬 `application.yml` | true |
| `application-prod.yml` | false |

- **기동 때 store 가 없을 때.** ListStores 가 성공했는데 그 이름의 store 가 없고 생성도 꺼져 있으면 기동을 멈춘다. 오류 문구: `OpenFGA store <name> 이 없다 — 처음 설치라면 openfga.create-store=true 로 한 번 띄운다`.
- **기동 때 OpenFGA 에 닿지 못할 때.** 지금처럼 경고만 남기고 기동한다(`OpenFgaStoreInitializer`). 그 뒤 첫 해석에서 store 가 없다고 판명되면 같은 문구의 오류를 낸다. 이 오류는 일시 장애가 아니다(설정 오류, 500).
- **이름 기본값.** `dynamodb.table-name` 과 `openfga.store-name` 의 클래스 기본값(`organization`)을 없앤다. 키가 없거나 비어 있으면 기동을 멈춘다. 두 앱의 yml 은 지금처럼 값을 적는다.

**모델은 내용이 다를 때만 쓴다.** `attachAndWriteModel` 이 이렇게 바뀐다.
1. 먼저 `ReadLatestAuthorizationModel` 을 부른다. Read 원칙에 걸리지 않는 API 다.
2. 모델이 없거나, 우리 모델과 `schema_version`·`type_definitions`·`conditions` 가 JSON 트리로 비교해 다를 때만 `WriteAuthorizationModel` 을 부른다.
3. 비교가 잘못되면 지금처럼 쓰게 되므로 안전하다.

modelId 를 클라이언트에 고정하는 일은 하지 않는다(§20).

**시험**
- 같은 store 로 두 번 부트스트랩하면 `ReadAuthorizationModels` 가 1개다. testcontainers 의 OpenFGA 로 본다.
- 새 이름 + 생성 꺼짐이면 오류가 나고, 그 이름의 store 는 0개다.

## 5. D3 — 설정 검증

- **cron (13 §7.1).**
  - `@Scheduled` 의 cron 은 속성 클래스의 값을 읽는다(SpEL 로 속성 빈 참조). 그래서 기본값의 출처는 속성 클래스 하나이고, yml 은 덮어쓸 때만 쓴다.
  - app-scim 에는 `sync.*` 속성 클래스가 없다. 지금은 `ArchiveScheduler` 의 `@Scheduled` 와 `ScimUseCaseConfig` 의 `@Value` 가 자리표시자 기본값으로 직접 읽는다. 그래서 속성 클래스를 새로 만든다: `archive-cron`·`purge-cron`·`job-timeout` 을 담고, 접두사는 `sync` 다. 세 곳이 그 빈을 읽는다.
  - 시험: 키 없이 컨텍스트가 뜨고, 등록된 cron 이 클래스 기본값이다(`ScheduledTaskHolder`).
- **`ldap.strategy` (13 §7.2).**
  - 받는 값은 `group-of-names`·`dit` 둘뿐이다(대소문자 무시). 그 밖이면 기동을 멈춘다. 오류 문구에 받는 값을 적는다.
  - 속성 타입은 문자열 그대로 둔다. enum 으로 바꾸면 시험 15곳을 고쳐야 하는데, 얻는 것이 같다.
- **비밀값.**
  - app-ldap `application-prod.yml` 에 `ldap.bind-password: ${LDAP_BIND_PASSWORD}` 를 적는다. 기본값은 두지 않는다.
  - 자리표시자만으로는 기동이 멈추지 않는다. 부트의 `@ConfigurationProperties` 바인딩은 풀리지 않은 자리표시자를 무시하고 문자열 `"${LDAP_BIND_PASSWORD}"` 를 그대로 넣는다(점검자가 Boot 3.5 바이트코드로 확인). 그래서 `LdapConfig` 가 명시적으로 검증한다.
    - `bind-dn` 이 있는데 `bind-password` 가 비었거나 `${` 로 시작하면 기동을 멈춘다.
    - 오류 문구에 값은 싣지 않는다.
    - 시험: 위 두 경우가 기동 실패인지, 익명 바인드(`bind-dn` 없음)는 통과하는지.
  - 로컬 값은 main `application.yml` 에 그대로 둔다. prod 프로필이 덮는다.
- **헬스 세부.** app-ldap 의 `show-details` 를 `when-authorized` 로 바꾼다. 세부를 보는 시험은 시험 yml 에서 `always` 를 쓴다. app-scim 과 같은 모양이다.
- **`ldap.require-tls`.**
  - 값: 클래스 기본값 false, prod true.
  - 켜져 있는데 `ldap.url` 이 `ldaps://` 가 아니면 기동을 멈춘다.
  - `ldaps://` 는 JNDI 가 JVM 트러스트스토어로 연다. 사설 CA 는 `-Djavax.net.ssl.trustStore` 로 넘긴다. 이것을 루트 README.md 와 온보딩 11 에 적는다.
  - StartTLS 는 지원하지 않는다(§17.2).
  - 시험: 설정 검증 단위 시험. 실제 ldaps 연결은 시험하지 못한다(§16).

## 6. D4 — DynamoDB 시간 한도와 락 획득

**클라이언트 전체 시간 한도.** `DynamoDbConfig` 에 `ClientOverrideConfiguration` 을 둔다.

| 키 | 기본값 | 뜻 |
|---|---|---|
| `dynamodb.api-call-attempt-timeout` | 10s | 시도 한 번의 한도. 매달린 연결을 끊는다 |
| `dynamodb.api-call-timeout` | 60s | 재시도를 포함한 호출 전체의 한도 |

재시도 정책은 SDK 기본값 그대로 둔다. 기본값을 넉넉히 잡은 이유는 두 가지다.
- 규모 시험의 DynamoDB Local 이 부하 아래에서 느리다.
- 스냅샷 묶음(약 350KB)을 써야 한다.

검증은 규모 시험이 한다. 거기서 끊기면 시험 yml 만 늘리고, 그 사실을 §19 결과에 적는다.

**SCIM 쓰기의 락 획득** (13 §7.11, `IncrementalSyncUseCase.줄을_선다`·`AcquireBackoff`)
- **시도마다 한도.** 획득 시도 하나에 요청 단위 `apiCallTimeout` 을 준다.
  - 값은 `min(남은 획득 한도, 1초)` 이고, 하한은 200ms 다.
  - 1초로 자르는 이유가 있다. 락을 잡는 PutItem 은 보통 수십 ms 다. 한 시도가 남은 한도를 다 쓰면 끊긴 뒤 다시 시도할 여지가 없다. 3초 한도라면 매달린 시도가 와도 두세 번 다시 시도할 수 있다.
- **토큰은 코어가 만든다.** 지금은 어댑터(`DynamoDbMutationLock.acquire(purpose)`)가 토큰을 만들고, 코어는 같은 Mono 를 다시 구독해 같은 토큰을 얻는다. 시도마다 한도가 달라지면 Mono 를 다시 쓸 수 없다. 또 포기할 때 반납하려면 코어가 토큰을 쥐어야 한다.
  - 그래서 포트를 `acquire(purpose, token, attemptTimeout)` 로 바꾼다.
  - 줄 서기 한 번에 코어가 토큰 하나를 만든다. 관리 API·동기화의 한 번 시도도 같은 포트를 쓴다(한도 없음 = 클라이언트 전체 한도).
- **시도의 결과는 셋으로 가른다.**

| 결과 | 판단 | 다음 |
|---|---|---|
| 조건 실패(`LockUnavailableException`) | 남이 쥐었다 | 지금처럼 쉬었다 다시 시도한다 |
| 시도 한도로 끊김 | 결과를 모른다. 서버에서 성공했을 수 있다 | 같은 토큰으로 다시 시도한다. 획득 조건에 `#token = :token` 이 있어(`DynamoDbMutationLock.acquire`) 같은 토큰의 다시 시도는 성공한 획득을 그대로 다시 얻는다 |
| 그 밖의 오류(SDK 재시도를 다 쓴 스로틀·5xx·네트워크) | 지금처럼 곧바로 멈춘다(503) | 응답을 잃었을 수 있으므로 결과를 모르는 시도로 친다 |

  - 포기할 때(한도에 닿았거나 그 밖의 오류로 멈출 때) 결과를 모르는 시도가 하나라도 있었으면, 같은 토큰을 조건으로 반납을 한 번 보낸다(최선 노력, 짧은 한도). 이 반납도 실패하면 TTL(30초)이 푼다.
- **문서.** `AcquireBackoff` 자바독의 "날아가는 중인 시도는 끊지 않는다" 를 위 규칙으로 바꾼다.
- **시험.**
  - 가상 시간으로, 시도가 한도 안에 끊기고 같은 토큰으로 다시 시도하는지 본다.
  - 포기할 때 조건부 반납이 나가는지 본다.
  - DynamoDB Local 로, "끊긴 뒤 같은 토큰 재시도가 성공" 하는지 본다.

관리 API 의 `full`·`rebuild` 는 한 번만 시도하므로 클라이언트 전체 한도만 받는다.

## 7. D5 — OpenFGA 헬스와 probes

- **OpenFGA 헬스.** 캐시한 store id 가 있으면 `GetStore` 를 2초 상한으로 부른다.
  - 오류이거나 store 가 없으면 DOWN 이다. 그래서 지운 store 도 DOWN 으로 보인다.
  - 캐시가 없으면 지금처럼 `findExistingStore` 를 쓴다(ListStores, store 를 만들지 않음).
  - 두 앱의 `OpenFgaHealthIndicator` 가 같은 코드이므로 함께 고친다.
- **probes.** 두 앱에 `management.endpoint.health.probes.enabled: true` 를 둔다.
  - liveness·readiness 그룹은 부트 기본 구성 그대로 외부 의존을 넣지 않는다. OpenFGA·DynamoDB·LDAP 장애가 재시작을 부르지 않게 하려는 것이다.
  - 외부 의존은 `/actuator/health` 의 본 그룹에서 본다.

## 8. D6 — SCIM 필터 오류 문구와 회수 카운터

- **필터 오류 문구.**
  - 필터 오류 detail 에 싣는 입력 조각은 각각 앞 100자만 싣는다. 넘으면 `…` 를 붙인다.
  - 입력 조각은 셋이다: `ScimFilter.Parser` 의 끝의 `— filter: <원문>`, `attributePath` 오류의 path, `value` 오류의 token. 원문만 자르면 token 이 필터의 대부분일 때 상한이 생기지 않는다.
  - 시험: 세 오류 모두 긴 입력에서 detail 길이에 상한이 있다.
- **회수 카운터.** `scim.revocations{kind}` 는 커밋된 회수를 센다.

| kind | 언제 | 세는 수 |
|---|---|---|
| `deactivate` | `active` 가 true → false 로 저장될 때(삭제가 일부 실패해 비활성으로 남긴 경우 포함) | 1 |
| `delete_user` | 직원 레코드가 실제로 지워질 때. 그 직원이 그때 활성이었을 때만 센다 | 1 |
| `remove_member` | 조직에서 멤버를 뺀 것이 커밋될 때(PATCH remove, PUT 으로 빠짐) | 커밋된 뺀 직원 수 |
| `delete_group` | 조직 삭제가 커밋될 때 | 커밋 때 지운 직원 멤버 수 |

  - 직원 하나의 회수는 한 번만 센다. 예: 삭제가 일부 실패해 비활성으로 남기면 `deactivate` 로 센다. 그 뒤 IdP 의 DELETE 재시도로 레코드가 지워질 때는 이미 비활성이므로 `delete_user` 로 세지 않는다.
  - 그룹 쪽은 실패하면 의도를 저장하지 않으므로 커밋된 만큼만 센다. 그래서 장애 중 IdP 재시도가 경보를 거짓으로 울리지 않는다.
  - 막지는 않는다(§17.4). 루트 README.md 와 온보딩 11 에 경보 식의 예를 적는다: 1시간 증가량이 평소보다 크게 늘면 경보.

## 9. D7 — 하루 1회 표지와 아카이빙 기한

**표지 아이템** `DAILY#<job>#<UTC 날짜>` 에 칸을 더한다.

| 칸 | 값 |
|---|---|
| `state` | `CLAIMED` 또는 `DONE` |
| `owner` | 잡을 때마다 새로 만드는 UUID |
| `leaseUntil` | epoch 초. 잡은 때 + job-timeout + 5분 |
| `expiresAt` | 지금과 같다. TTL 3일 |

**잡기.** 조건부 PutItem 으로 잡는다.
- 조건: `attribute_not_exists(PK) OR (state = CLAIMED AND leaseUntil < :now)`
- 이 조건 때문에 DONE 이면 그날은 다시 잡지 못한다.
- 죽은 인스턴스가 잡은 표지는 임대가 끝나면 다시 잡힌다.

**끝내기.**
- 일이 성공하면 owner 를 조건으로 `state = DONE` 으로 바꾼다.
- 성공이 아니면 owner 를 조건으로 표지를 지운다. 표지를 조건 없이 지우는 것은 금지다. 늦은 해제가 남이 새로 잡은 표지를 지운다.
- 성공 판정은 부르는 쪽이 넘긴다.
  - 정리(purge): 오류 없이 끝나면 성공이다.
  - 아카이빙: SUCCEEDED 만 성공이다. FAILED 와 ABORTED(재적재 중)는 다시 시도한다.

**cron.** 하루 창 안에서 매시 시도하도록 기본값을 바꾼다.

| 키 | 앱 | 지금 | 바뀐 값 |
|---|---|---|---|
| `sync.purge-cron` | app-ldap | `0 0 4 * * *` | `0 0 4-8 * * *` |
| `sync.archive-cron` | app-scim | `0 0 3 * * *` | `0 0 3-8 * * *` |
| `sync.purge-cron` | app-scim | `0 0 4 * * *` | `0 0 4-8 * * *` |

- 표지의 날짜는 UTC 다. 창이 UTC 자정을 넘지 않아야 한 창이 한 날로 묶인다.
  - 기본 창(03~08시)은 서버 시간대가 UTC 든 KST 든 이 조건을 지킨다. KST 08시는 UTC 23시다.
  - 다른 시간대에서 창을 바꿀 때는 이 조건을 지켜야 한다. 이것을 문서에 적는다.
  - 조건을 어겨도 정확성은 깨지지 않는다(멱등). 창이 두 날로 갈려 하루에 두 번 돌 수 있을 뿐이다.

**아카이빙 기한.** `SnapshotArchiveUseCase` 에 기한을 준다. 값은 `sync.job-timeout`(30분)이고, 넘으면 FAILED "기한 초과" 로 끝낸다.
- 기한이 있어야 표지의 임대(job-timeout + 5분)보다 먼저 끝나서, 두 인스턴스가 함께 도는 일을 막는다.
- 기한이 있어야 D10 의 나이 규칙으로 아카이빙 기록을 닫을 수 있다.

**함께 돌 때.** 두 인스턴스가 함께 돌아도 정확성은 깨지지 않고 비용만 든다.
- 정리는 멱등이다.
- 아카이빙은 스냅샷 하나와 BatchCheck 를 더 쓴다. 남은 회수 지우기는 WRITE 락 + Check 기준선이라 멱등이다.

**시험**
- `DailyOnceTest`:
  - 실패 뒤 같은 날 다시 잡힌다.
  - DONE 이면 건너뛴다.
  - 남의 표지는 풀지 않는다.
  - 임대가 끝난 CLAIMED 는 다시 잡힌다.
- `DynamoDbDailyJobClaimsTest`: Local 로 조건식을 확인한다.

## 10. D8 — 관리 API 예외 처리기

admin-api 에 `@RestControllerAdvice` 를 둔다. 적용 범위는 네 컨트롤러뿐이다: `AdminQueryController`, `AuditHistoryController`, 두 앱의 `AdminSyncController`. 표지 애너테이션이나 `assignableTypes` 로 좁힌다. SCIM 라우터와 actuator 는 건드리지 않는다.

**응답 본문.** RFC 9457 problem details(`application/problem+json`, 스프링 `ProblemDetail`)로 낸다. 칸은 `type`(about:blank), `title`, `status`, `detail` 이다.

| 경우 | 상태 | detail |
|---|---|---|
| 락 경합(`LockUnavailableException`, 조건 실패) | 409 | 지금 컨트롤러가 쓰는 사유 문구(예: "다른 동기화·재적재가 진행 중입니다") |
| 일시 장애(분류기가 참) | 503 + `Retry-After`(초) | "일시 장애 — 잠시 뒤 다시 시도한다" |
| `ResponseStatusException` | 그 상태 | 그 사유 |
| 입력 오류(`ServerWebInputException` 등) | 400 | 스프링이 준 이유 |
| 그 밖 | 500 | "내부 오류" — 예외 문구를 싣지 않는다 |

- **판정 순서.** 위 표의 위에서부터 판정한다. 락 경합 판정이 분류기보다 먼저여야 한다. `LockUnavailableException` 은 `TemporaryFailureException` 의 하위 타입이다. 또 app-scim `AdminSyncController` 는 이것을 원인으로 품은 `ResponseStatusException(409)` 로 감싼다. 분류기는 원인 사슬을 따라가므로, 먼저 보면 409 가 503 이 된다.
  - 그래서 `ResponseStatusException` 은 자기 상태로 먼저 답한다.
  - 원인 사슬에 `LockUnavailableException` 이 있으면 409 다.
  - 그다음에 분류기를 본다.
- **분류기 공유.** 분류기는 SCIM 과 같은 것(`TemporaryFailureClassifier` + 두 인식기)을 쓴다. 두 HTTP 층이 함께 쓰도록 공유 모듈로 옮긴다. 어디로 옮길지는 계획에서 정한다. Jackson 예외 제외 규칙은 그대로 유지한다.
- **`runs.start` 실패.** 실행 기록을 열지 못한 일시 실패는 503 이다. 작업은 시작하지 않았으므로 다시 걸어도 된다.
- **SCIM.** 응답은 바꾸지 않는다. 표준 신호(일시 장애)만 503 이고, 나머지는 한 규칙(500)이다. 루트 README.md 의 서술만 고친다.
- **시험.**
  - 컨트롤러 시험에 advice 를 결선해 409·503·500·400 의 상태와 본문을 확인한다.
  - 앱 끝단 시험 하나로 결선을 확인한다.

## 11. D9 — 직원 응답의 externalId

- `UserSummary`·`EmployeeDetail` 에 `externalId` 를 더한다. SCIM 은 없을 수 있으므로 비어 있을 수 있다.
- 검색 경로마다 값을 얻는 방법:

| 경로 | 방법 |
|---|---|
| 상세 | 이미 읽은 직원 META 에서 꺼낸다. 추가 읽기 0 |
| userName 검색 | GSI1 이 ALL 프로젝션이라 이미 받는다 |
| externalId 검색 | 본 테이블을 다시 읽으므로 이미 받는다 |
| displayName 검색 | GSI2 의 INCLUDE 에 `externalId` 를 더한다 |

- **GSI2 프로젝션.**
  - `externalId` 는 GSI3 의 파티션키 속성 그 자체다. 그래도 GSI2 의 비키 속성으로 넣을 수 있음을 DynamoDB Local 2.5.3 으로 확인했다(2026-10-27). 실제 AWS 는 확인하지 못했다(§16).
  - 모양 검사(D1)가 프로젝션도 본다. 그래서 옛 테이블로는 기동하지 않는다.
  - 실제 AWS 가 이 프로젝션을 거절하면 GSI2 를 ALL 프로젝션으로 바꾼다. 비용은 둘이다.
    - GSI2 저장량이 직원 META 전체만큼 는다.
    - 프로젝션에 없던 속성(예: `updatedAt`)이 바뀌어도 GSI2 쓰기가 생긴다.
  - 데이터 모델은 같고, 모양 검사가 보는 값만 바뀐다. 첫 실제 배포 때 테이블을 만들면서 드러나므로 그때 바꾼다.
- **시험.**
  - 저장소 시험(Local): 세 검색 경로에서 값이 나온다.
  - `AdminQueryControllerTest`: 응답에 칸이 있다.

## 12. D10 — 실행 기록 바로잡기

- **전부 보류 (13 §7.5).**
  - `SyncOutcome.held(result, reason)` 을 더한다. 상태는 ABORTED 이고, 쓴 수·실패 수는 결과대로, 지운 수는 0, 스냅샷은 없다.
  - `LedgerAlignment.align` 의 전부 보류 갈래가 이것을 낸다. 그래서 `sync.tuples.written` 도 센다.
- **`FORCED_REBUILD` (13 §1.2).**
  - `SyncTrigger.FORCED_REBUILD` 를 더한다. LDAP 재적재에 `force=true` 가 걸리면 이 트리거로 남긴다. 락 용도는 REBUILD 그대로다.
  - 운영 전이라 이관할 것이 없다. SCIM 재적재에는 force 가 없다.
- **차단기 메시지 (13 §7.9).** 차단기로 멈추고 현재상태를 실제로 교체한 회차의 메시지 끝에 `— 현재상태는 디렉터리대로 교체했다` 를 붙인다.
  - 붙이는 곳은 둘이다: `FullSyncUseCase.saveSnapshotAndState` 를 탄 차단기 경로, 그리고 revokedOnly 가 아닌 `LedgerAlignment.commit`.
  - 교체하지 않는 차단기 경로에는 붙이지 않는다. 그런 경로는 셋이다: `비활성_회수만_반영한다` 의 차단기, revokedOnly 커밋, 장부 맞추기 쓰기 단계의 차단기(오류로 끝남).
  - 동작은 바꾸지 않는다. 이유는 §17.3 에 있다.
- **아카이빙 (13 §7.7·§7.8).**
  - 비교 직후 관찰자(`ArchiveDriftObserver.observed`)를 부른다. 그래서 관찰 시각이 "비교한 시각" 이라는 이름과 맞는다.
  - 스냅샷 저장과 남은 회수 지우기를 서로 독립으로 돌린다. 저장이 실패해도 지운다. 스냅샷 내용은 지우기 전에 관찰한 실제이므로 순서와 상관없이 같다.
  - 결과 메시지는 저장 실패면 FAILED "스냅샷 저장 실패 …" 에 복구 결과를 붙인다.
  - `deletedCount` 는 지운 줄의 합, `failureCount` 는 실패한 줄의 합이다. `writtenCount` 는 지금처럼 스냅샷에 담은 줄 수다. 이 뜻을 문서에 적는다.
- **죽은 RUNNING 기록 (13 §6.5).** 닫는 규칙이 둘이다.
  - 락을 쥔 회차는 같은 source 의 ARCHIVE 가 아닌 RUNNING 을 나이와 상관없이 닫는다(지금 규칙). 살펴보는 범위를 최근 100건에서 두 달 파티션 전체(`status = RUNNING` 필터)로 넓힌다.
  - 누구든 시작할 때, 시작한 지 job-timeout + 10분이 지난 RUNNING 은 종류와 상관없이 닫는다. 동기화·재적재·아카이빙 시작이 모두 부른다. 그래서 app-scim 도 매일 정리된다.
  - 잘못 닫혀도 늦은 `finish` 가 조건 없이 덮어쓴다(지금과 같다).
  - 비용: app-ldap 은 매시 회차마다 두 달치(약 1,500건) Query 를 한다. 아이템이 작아 읽기 용량은 수십 단위다.
- **시험.**
  - `FullSyncUseCaseTest`·`RebuildUseCaseTest`: 전부 보류일 때 쓴 수를 남기는지, `FORCED_REBUILD`, 차단기 메시지.
  - `SnapshotArchiveUseCaseTest`: 저장이 실패해도 관찰자와 지우기가 불리는지, 건수.
  - `SyncJobsTest`: 101번째 기록, 오래된 ARCHIVE 를 닫고 최근 것은 닫지 않는지.
  - `SyncMetricsTest`.

## 13. D11 — 빈 소속 그림은 데이터 오류

**규칙.** 두 LDAP 전략이 공통으로 쓰는 한 곳에 둔다.

> 디렉터리 그림에 사람 소속(조직의 멤버 가운데 직원인 것, 활성 여부와 상관없이)이 하나도 없으면 데이터 오류(`DirectoryDataException`)다.

- **결과.** 회차는 FAILED 이고, 다시 읽지 않으며, `force` 로도 넘길 수 없다.
- **가드는 남긴다.** `UnmatchedMemberGuard`(groupOfNames 의 DN 대조 0건 검사)는 그대로 둔다.
  - 그 가드는 AD 기본 그룹(RID) 소속을 일부러 셈에서 뺀다. 그래서 RID 소속이 있어도 DN 대조가 전부 어긋나면 잡는다.
  - 새 규칙만 두면 RID 소속 하나로 통과해 버린다(점검 리뷰 H 의 회귀).
  - 새 규칙은 가드가 보지 않는 모양을 더 덮는다: 조직 0건, 사용자 0건.
- **대신하는 것.** DIT 의 "소속 0" 검사만 새 규칙이 대신한다.
- **메시지.** 가드의 DN 형태 힌트는 그대로다. 새 규칙의 메시지는 사용자 수·조직 수·member 값 수를 적어 어느 검색이 비었는지 보이게 한다.
- **활성 여부를 보지 않는 이유.** 시계 오류로 전원이 비활성으로 읽히는 경우는 지금처럼 가드가 본다.
- **근거.**
  - SCIM 의 빈 조직도 규칙과 같은 모양이다.
  - RFC 4511 에서 0건 검색은 성공이므로, 표준 신호가 아니라 우리 쪽 한 규칙으로 판정한다.
  - 빈 그림이 장부 맞추기(Read)까지 가지 않으므로 Read 도 줄어든다.
- **시험.** `GroupOfNamesStrategyTest`·`DitStrategyTest` 에서 확인한다.
  - 0건 세 모양(사용자 0, 조직 0, 대조 0)이 모두 FAILED 다.
  - AD 기본 그룹(RID) 소속만 있고 DN 대조가 0건이면 FAILED 다(가드).
  - 일부만 대조되면 통과한다.
  - 재적재도 같다.

## 14. D12·D13 — 문서

**D12 (루트 README.md, 자바독, 주석).** 루트 README.md 와 코드 주석·자바독의 틀린 서술을 고친다. 옛 설계 문서는 작업 기록이므로 고치지 않는다.

루트 README.md:
- §1.11 의 세 문장
- 오류 표: 403 행, 표지판을 쓰는 중일 때의 2초 503
- `active` 를 저장하지 않는 경우의 넷째(커밋에 닿은 뒤의 오류)
- 직원 displayName 필터는 대소문자를 가리지 않는다
- 조직 PUT 에 members 가 없으면 멤버를 다 지운다
- `GET /Users/.search` 는 404 다
- TTL 을 쓰는 아이템 수(다섯)
- 쓰기 단계의 넷째 값
- scaleTest 소요 시간(마지막 실행 기준)
- 실행 기록은 두 달 파티션만 읽는다는 함정
- 시드 생성기 사용법
- 이 슬라이드가 바꾼 설정과 IAM 권한

자바독·주석:
- `LedgerAlignment`, `IncompleteAttributeReadException`, `Landmarks`(500 → 실제 1,600), `DailyOnce`, `Keys.EXPIRES_AT`(넷 → 다섯), Scheduler 의 스냅샷 주석, `DynamoDbProperties.lockRenewInterval`, `DirectoryConflictException`, `ScimException` 의 scimType 서술, `AcquireBackoff`
- `docker-compose.yml` 의 옛 과제 번호
- `FullSyncUseCaseTest` 의 주석

목록의 출처는 `.superpowers/onboarding/writer-findings.md` C·D·E 와 `report-foundation.md` 다. 고칠 때마다 코드로 다시 확인한다.

**D13 (온보딩 문서).** `docs/onboarding/` 에서 바뀐 절만 고치고, 모든 문서 머리의 기준 커밋을 이 슬라이드의 머지 커밋으로 갱신한다.
- 13 §7 에서 고친 항목은 지우거나 "고침(커밋)" 으로 바꾼다. 13 은 지금의 한계만 담는다.
- 11 운영: 새 설정·IAM·probes·경보 식.
- 12 장애와 복구: 테이블·store 가 없을 때, 복원본.
- 09 관리 API: 오류 응답 표, externalId.
- 07 재적재·초기화·아카이빙: 아카이빙 순서·기한, 하루 1회 표지.
- 08 동시성과 작업: 락 획득 시도 한도, 죽은 기록 닫기.
- 05 LDAP 동기화: 빈 소속 규칙, `FORCED_REBUILD`, 전부 보류의 건수.
- 02 용어집: 바뀐 용어(하루 1회 표지의 상태 등).
- 15 결정 색인: 이 문서의 결정.

## 15. 바뀌는 곳 (모듈별 어림)

| 모듈 | 바뀌는 것 |
|---|---|
| storage-dynamodb | `TableInitializer`(기동 검사·보호·모양), `DynamoDbConfig`(시간 한도), `DynamoDbProperties`, `DynamoDbMutationLock`(시도 한도), `DynamoDbDailyJobClaims`(상태 표지), `DynamoDbSyncRunRepository`(RUNNING 필터 조회), `DynamoDbDirectorySearchRepository`(externalId) |
| authz-openfga | `StoreBootstrapper`(생성 스위치·모델 비교), `OpenFgaProperties` |
| core | `DailyOnce`, `SyncJobs`(죽은 기록), `AcquireBackoff`·`IncrementalSyncUseCase`(획득), `SyncOutcome`·`LedgerAlignment`(전부 보류), `SyncTrigger`, `RebuildUseCase`, `SnapshotArchiveUseCase`, `FullSyncUseCase`(차단기 메시지), 분류기(공유 위치로 옮길 경우) |
| connector-ldap | `LdapConfig`(전략·TLS 검증), 빈 소속 규칙, 두 전략 |
| connector-scim | `ScimFilter`(문구), 회수 카운터 결선 |
| app-scim (새 클래스) | `sync.*` 속성 클래스(archive-cron·purge-cron·job-timeout) |
| admin-api | 예외 처리기, `UserSummary`·`EmployeeDetail`, `AdminQueryUseCase` |
| app-ldap / app-scim | yml·prod yml, 스케줄러 cron, `OpenFgaHealthIndicator`, 지표 |
| 문서 | 루트 README.md, `docker-compose.yml` 주석, `docs/onboarding/` |

## 16. 이 설계가 말할 수 없는 것

- **실제 AWS 의 동작.** PITR·삭제 보호·GSI2 프로젝션의 실제 AWS 동작을 확인하지 못했다. Local 은 PITR 을 모르고, 프로젝션은 Local 로만 확인했다.
- **ldaps 실제 연결.** 사설 CA 트러스트스토어를 넣은 실제 연결은 시험하지 않는다.
- **모델 버전 누적.** OpenFGA 가 같은 내용의 모델도 새 버전으로 쌓는지는 지금 확인하지 못했다. D2 의 시험이 "비교한 뒤에는 1개" 를 보이므로, 어느 쪽이든 결과는 같다.
- **시간 한도 기본값.** D4 의 기본값(10초·60초)이 실제 AWS 의 부하에 맞는지 모른다. 규모 시험이 Local 기준으로만 확인한다.
- **SDK 취소.** OpenFGA SDK 의 요청을 cancel 하면 HTTP 와 SDK 내부 재시도가 멈추는지 모른다. 그래서 받아들인 늦은 쓰기(§17.5)의 최대 시간을 정확히 말할 수 없다.
- **cron 시간대.** 서버 시간대와 하루 창 cron 의 관계는 운영 배치에 달렸다. 문서에 조건을 적는 것으로 그친다.

## 17. 왜 다른 길을 안 갔나

### 17.1 테이블·store 묶음 표지
테이블에 `DEPLOYMENT#META {connector, storeId}` 를 두면 두 앱이 테이블·store 를 함께 쓰는 것(13 §7.14)과 "새 테이블 + 기존 store" 를 Read 없이 막을 수 있다.
- 첫째는 "배포 하나엔 커넥터 하나" 원칙상 따지지 않는 경우다.
- 둘째는 운영에서 생성을 끄면(D1·D2) 오타로는 생기지 않고, 의도한 생성이나 복원으로만 생긴다.
- 비용(포트·구현·주입, 기동과 늦은 해석 모두에서 치명 처리)에 비해 막는 것이 적다.

### 17.2 StartTLS
`ldaps://` 만으로 암호화 요구를 채운다. StartTLS 가 필요한 디렉터리가 나오면 spring-ldap `DefaultTlsDirContextAuthenticationStrategy` 로 더한다.

### 17.3 차단기 멈춤에서 현재상태를 교체하지 않기
LDAP 의 현재상태는 권한 판단에 쓰이지 않는다. 회차마다 디렉터리를 다시 읽고, 기준선은 스냅샷이다. 읽는 곳은 관리 API 의 `shouldHaveAccess` 와 감사 이력의 속성 변경뿐이다.
- B 는 실제로 나간 줄이고 U 는 모르는 줄이라, 다음 회차가 수렴한다(회수 수렴 설계 §3).
- 교체하지 않아도 "옛 조직도 대 반쯤 새 장부" 로 어긋나기는 마찬가지다.
- 가드는 그림을 거부하는 것(사람의 판단을 기다린다)이고, 차단기는 그림은 받았지만 인프라가 실패한 것이다. 지금의 구분이 일관되므로 문서와 메시지만 고친다.

### 17.4 SCIM 대량 회수를 시간 창으로 막기
"표준이 정한 신호만 받는다" 원칙과 부딪힌다. IdP 의 의도된 대량 퇴사를 막으면 회수가 늦어진다. 그래서 보이게만 한다(D6).

### 17.5 취소된 회차의 늦은 쓰기 — 협조적 멈춤을 미룬 이유
기한·서버 종료 때 cancel 대신 멈춤 신호를 보내는 방법이 있다. writer 가 나간 묶음을 기다린 뒤 차단기처럼 끝내면 늦은 쓰기가 사라지고, LDAP 은 Read 가 줄어든다. 미룬 이유는 셋이다.
- **바꿀 곳이 많다.** writer 포트에 신호를 넘겨야 하고, 유예 동안에도 리스를 갱신하도록 `LeaseKeeper` 의 경주를 옮겨야 한다. 종료 대기(10초)와 부트 단계 한도(30초)도 다시 맞춰야 한다.
- **효과를 잴 수 없다.** SDK 취소 동작을 모른다(§16).
- **받아들인 위험과 같은 부류다.** 남는 위험은 펜싱 틈(13 §3.2)과 같은 모양이다: 반납 직후 다른 보유자와 늦은 요청이 겹친다. 회복 경로도 같다: 아카이빙이나 재적재.

13 에 남기고, 실제로 배포 중 회차가 자주 끊긴다는 것이 보이면 다시 연다.

### 17.6 LDAP 묶음마다 리스 확인
`LeaseKeeper` 의 멈춤 한도는 갱신 주기 두 번(20초)으로 TTL(30초)보다 먼저 일을 취소한다. 그래서 그 뒤로는 새 묶음이 나가지 않는다. 묶음 전 확인은 같은 일을 한 번 더 할 뿐이다. 남는 것은 이미 나간 묶음의 늦은 반영이고, 이는 §17.5 와 같다.


## 18. 검증

- **머지 전.** `test` 와 `scaleTest` 를 둘 다 돌린다. 메인 세션이 차례로 돌린다.
- **DynamoDB Local 로 하는 시험.** 삭제 보호, 기동 검사, 모양 검사(GSI2 프로젝션 포함), 하루 1회 표지 조건식, RUNNING 필터 조회, 같은 토큰 재획득.
- **가짜로 하는 시험.** PITR 확인, 획득 시도 한도·조건부 반납(가상 시간), 예외 처리기.
- **testcontainers OpenFGA 로 하는 시험.** 모델 비교 쓰기, store 생성 스위치, 헬스의 GetStore.
- **규모 시험.** D4 의 시간 한도로 도는지 본다. 끊기면 시험 yml 로 늘리고, 그 사실을 결과에 적는다.

## 19. 결과 (구현 후 기록)

(구현 뒤에 채운다: 머지 전 검증 수치, 구현 중 판정, 남긴 것.)

## 20. 범위 밖

- **인증**: 다음 슬라이드다. Spring Security 로 가장 간단한 authorization server 를 세운다. 관리 API·SCIM·actuator 인증, 헬스 세부를 볼 권한, 감사 행위자, 로그인 → employeeId 조회(N10)를 다룬다.
- **N5** SCIM 조직 계층의 원천: 제품 결정이 필요하다.
- **N11 의 "영구 거절 줄"**:
  - 확정 거절도 뒤 단계를 막는 것은 fail-closed 로 이미 결정했다(설계 2026-10-05 §11).
  - 이름 기반 id 에서 먼저 읽은 쪽이 이기는 것은 받아들였다.
- **13 §7.10** 늦은 쓰기와 **13 §7.14** 두 앱 공유: §17 을 본다.
- **modelId 고정**: 롤링 배포 중 옛 모델로 되돌아가는 문제는 따로 다룬다.
- **SCIM 오류 상태**: 바꾸지 않는다. 문서만 고친다.
