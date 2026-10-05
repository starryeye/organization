# 점검 ⑤-1 SCIM 재시도 신호 — 일시 장애는 503 + Retry-After, 413, 라우트 밖 요청, 오류 문구

- 날짜: 2026-10-05
- 근거: `docs/superpowers/specs/2026-09-28-full-audit.md` §7 권고 ⑤ — 중 M4·M3, 사소 S2·S7·S10·S11. ③-1(`2026-10-02-big-change-lock-design.md` §11)이 남긴 "재시도 상한 소진·커밋 저장소 오류가 500".
- 나눔: ⑤ 는 둘이다(사용자 결정). ⑤-1 은 응답 신호(이 문서), ⑤-2 는 요청 해석(M5·M18·S1·S5·S6·S8·S9·S12, ④-1 이월 "경로 없는 조직 PATCH 의 externalId").
- 전제: 실제 규모 10만 명+, 운영 배포 전, 표준이 정한 신호만 받는다(여기서는 HTTP·RFC 7644 상태 코드, 각 SDK 의 재시도 분류).

## 1. 문제

| 항목 | 지금 | 결과 |
|---|---|---|
| M4 | 재시도하면 낫는 실패가 500 이다 — 튜플 부분 실패, OpenFGA Check 실패, DynamoDB 장애, 락 갱신 중 저장소 장애, 쓰기 차단기(`TupleWriteAbortedException`), 재시도 상한 소진(③-1 이월) | 코드 주석 스스로 "IdP 는 500 을 영구 실패로 읽는다" 고 전제한다. 그 전제대로면 퇴사자 비활성화의 부분 실패를 아무도 재시도하지 않아 남은 조직 권한이 남는다. 500 이 버그와 장애를 함께 뜻해 운영자도 가를 수 없다 |
| M3 | 256KB 를 넘는 본문이 500 이다(WebFlux 의 `DataBufferLimitException` 을 아무도 번역하지 않는다) | IdP 가 결코 성공할 수 없는 요청을 일시 오류로 보고 되풀이하거나 포기한다. RFC 7644 §3.12 에는 413 이 있다 |
| S2 | 503 에 `Retry-After` 가 없다 | 다른 SCIM 쓰기가 락을 쥔 경우(수백 ms)와 재적재·전체 동기화가 쥔 경우(수 분)를 IdP 가 구분할 수 없다 |
| S7 | 라우트 밖 요청(`/Schemas`, `/ResourceTypes`, `/Bulk`, `/Me`, 틀린 메서드)의 오류가 Spring 기본 JSON 이고, 틀린 메서드가 405 가 아니라 404 다 | SCIM 클라이언트가 오류를 해석하지 못한다 |
| S10 | 415 등 WebFlux 가 정한 오류의 `detail` 에 Spring 의 `reason` 을 그대로 실어 내부 자바 클래스 이름이 인증 없는 엔드포인트로 나간다 | 내부 정보 노출 |
| S11 | path 없는 `remove` 의 `scimType` 이 `invalidSyntax` 다 | RFC 7644 §3.5.2.2 는 `noTarget` 이다 |

## 2. 결정 (사용자 확인, 2026-10-05)

- ⑤ 를 둘로 나눈다 — ⑤-1 응답 신호, ⑤-2 요청 해석.
- M4: **core 표지 + 어댑터가 라이브러리 예외를 알아보기.** 우리가 만드는 일시 장애는 core 표지를 달고, 라이브러리 예외(AWS SDK·OpenFGA SDK)는 그 SDK 를 가진 어댑터가 core 포트로 알아보며
  connector-scim 의 분류기가 모은다(§3.3). 처음 결정은 "조립하는 모듈(app-scim)의 분류기" 였으나 계획 단계에서 바꿨다 — SDK 를 아는 코드가 어댑터 안에 머물고 app-scim 은 조립만 한다.
  일시 장애는 503 + `Retry-After`, 요청 문제는 4xx, 그 밖은 500(진짜 버그).
- S2: **원인별 `Retry-After`** — 쓰기 경합 2초, 긴 작업(재적재·전체 동기화) 60초, 장애·부분 실패 10초. 429 는 쓰지 않는다(HTTP 의 뜻은 속도 제한이다).
- M3·S7·S10·S11 은 §4 설계로 확인받았다.

## 3. 재시도 신호 (M4·S2·③-1 이월)

### 3.1 core 표지 `TemporaryFailureException`

- core(`core.port`)에 `TemporaryFailureException extends RuntimeException` 을 둔다. 뜻은 "같은 요청을 잠시 뒤 다시 보내면 나을 수 있다" 이고, 기다릴 시간 `Duration retryAfter()` 를 싣는다.
- 이 표지를 다는 것:
  - `LockUnavailableException`(core) — `TemporaryFailureException` 을 상속한다. 락 경합·리스 상실·락 저장소 장애 모두.
  - `TupleWriteAbortedException`(core) — OpenFGA 쓰기 차단기. 상속으로 단다(부분 결과를 싣는 기존 계약은 그대로).
  - storage 의 재시도 상한 소진(`BatchRequests`, 지금은 `IllegalStateException`) — `TemporaryFailureException` 으로 던진다(③-1 이월).
  - OpenFGA `batchCheck` 응답 이상(`OpenFgaRelationTupleChecker`) — 개별 오류(내부 오류뿐일 때)·응답 수 불일치·요청에 없거나 두 번 온 correlationId. 답을 못 받은 항목을 "없음"으로 격하하지 않고 멈추는
    기존 계약은 그대로이고, 던지는 예외가 `IllegalStateException` 에서 이 표지로 바뀐다.
- 표지를 달지 않는 것: check·`batchCheck` 호출 **순간**의 동기 예외는 매개변수 거절이라 다시 보내도 같다 — 500(버그)이다.
- 기다릴 시간(상수, 운영에서 바꿀 이유가 보이면 그때 설정으로 올린다):

  | 원인 | `retryAfter` |
  |---|---|
  | 락 획득 조건 실패 — 쥔 쪽이 SCIM 쓰기(`WRITE`) | 2초 |
  | 락 획득 조건 실패 — 쥔 쪽이 재적재(`REBUILD`)·전체 동기화(`SYNC`) | 60초 |
  | 락 획득 조건 실패 — 쥔 쪽의 용도를 돌려받지 못함 | 2초(흔한 경우인 쓰기 경합으로 본다) |
  | 락 저장소 오류(조건 실패가 아닌 것)·리스 상실·갱신 실패 | 10초 |
  | 쓰기 차단기·재시도 상한 소진·`batchCheck` 응답 이상·분류기가 알아본 라이브러리 장애·부분 실패 | 10초 |

- **구현 중 정한 것:** `batchCheck` 의 **개별 오류는 OpenFGA 자신의 분류로 가른다** — 오류 항목 중 하나라도 `inputError`(`NO_ERROR` 가 아닌 값)가 있으면 거절이라 우리 버그(500, `IllegalStateException`),
  모두 `internalError`(또는 둘 다 없음)면 일시 장애(10초)다. 이유: 개별 오류를 전부 503 으로 하면 모델에 없는 relation 같은 결정적인 우리 버그에 IdP 가 끝없이 재시도한다.
  수 불일치와 모르는·중복 correlationId 는 서버가 답을 못 한 것이라 일시 장애(10초)다.
- **구현 중 정한 것:** 입력 오류와 내부 오류가 섞이면 입력 오류가 이긴다(500). 메시지·로그의 "(예: …)" 는 첫 `inputError` 항목을 든다 — 500 의 원인을 가리지 않으려고.

### 3.2 락을 쥔 쪽의 용도

- `DynamoDbMutationLock.acquire` 의 `PutItem` 에 `ReturnValuesOnConditionCheckFailure.ALL_OLD` 를 단다. 조건이 깨지면 DynamoDB 가 기존 락 항목을 돌려주고, 그 `PURPOSE`(이미 적고 있다)로 위 표의 시간을 고른다.
  돌려받지 못하면(빈 항목) 2초.
- core 의 획득 재시도(3초 기다림)가 끝나 `LockUnavailableException` 을 새로 만들 때, 마지막 시도의 `retryAfter` 를 물려받는다(재시도 소진 예외의 원인이 마지막 실패다).
- **구현 중 정한 것:** DynamoDB Local 2.5.3 은 `ReturnValuesOnConditionCheckFailure.ALL_OLD` 를 지킨다 — 재적재가 쥔 락에 쓰기가 60초를 받는 것을 storage 통합 테스트와 app-scim e2e 가,
  `WRITE` 락에 2초를 받는 것을 storage 통합 테스트가 실제로 확인했다. §10 의 "Local 지원 불확실" 한계는 이 사실로 풀렸다.

### 3.3 라이브러리 예외 분류기

- core(`core.port`)에 포트 `TemporaryFailureRecognizer` 를 둔다: `Optional<Duration> 재시도_대기(Throwable)` — 예외 **하나**를 보고 일시 장애인지, 기다릴 시간은 얼마인지.
- SDK 를 가진 어댑터가 자기 SDK 의 예외를 알아보는 구현을 core 포트 빈으로 낸다. 기다릴 시간은 모두 10초.
  - storage-dynamodb `DynamoDbTemporaryFailures`(빈 `dynamoDbTemporaryFailures`) — AWS SDK 자신의 재시도 분류를 따른다: `SdkClientException`(네트워크·시간 초과)과, `SdkServiceException` 중
    스로틀링이거나 5xx 이거나 SDK 가 재시도 가능하다고 표시한 것. 조건 실패·검증 오류(4xx)는 아니다 — 다시 보내도 같다.
  - authz-openfga `OpenFgaTemporaryFailures`(빈 `openFgaTemporaryFailures`) — OpenFGA SDK 자신의 재시도 분류를 따른다: `FgaError.isRetryable()`(429, 그리고 501 을 뺀 5xx)일 때만.
    그 밖은 모두 빈 값이다 — 거절(400)·인증(401·403)·store 없음(404)·501, 그리고 평범한 `ApiException`. 네트워크 실패는 SDK 가 `ApiException(IOException)` 으로 감싸므로 인식기가 빈 값을 돌려주고
    분류기가 사슬을 따라가 I/O 규칙으로 잡는다. 응답 해석 실패는 SDK 가 `ApiException(JacksonException)`(상태 0)으로 감싸고, 분류기가 Jackson 을 빼므로 일시 장애가 아니다(500).
- connector-scim 의 `TemporaryFailureClassifier`(클래스)가 `ObjectProvider<TemporaryFailureRecognizer>` 로 인식기를 모은다: `Optional<Duration> 재시도_대기(Throwable)` — 원인 체인 어디에든 일시 장애가 있으면 기다릴 시간.
  체인의 예외마다 core 표지 → 어댑터 인식기 → I/O 실패·시간 초과(`IOException`·`TimeoutException`) 순으로 보고, 어디에도 없으면 일시 장애가 아니다. 인식기가 없는 조립(테스트)의 기본값은 core 표지와 I/O 만 본다.
  우리 어댑터가 감싼 `IllegalStateException("OpenFGA … 호출 실패", 원인)` 도 원인 체인으로 따라가 본다.
- app-scim 은 조립만 하고 OpenFGA SDK 를 main 에 두지 않는다. AWS SDK 는 헬스 지표 때문에 main 에 있다. 인식기 빈 둘이 앱에 실리는 것은 e2e 가 확인한다.
- **구현 중 정한 것:** I/O 규칙은 Jackson 의 `JacksonException` 을 뺀다(사슬은 계속 따라간다). 이유: Jackson 의 해석·매핑 실패가 API 상 `IOException` 하위라, 빼지 않으면 깨진 JSON 본문이 400 대신 503 이 되어
  IdP 가 같은 본문을 끝없이 다시 보낸다(검토에서 잡음). 순서를 바꾸는 길(400 을 503 앞으로)은 응답 인코딩 실패(서버 버그)가 503 이 되는 같은 결함을 남겨 택하지 않았다.
- **구현 중 정한 것:** OpenFGA 인식기는 처음에 '거절 아닌 ApiException 전부' 였으나 SDK 가 응답 해석 실패를 ApiException 으로 감싸 Jackson 제외가 우회되어, SDK 의 `FgaError.isRetryable()` 로 바꿨다(최종 검토).

### 3.4 라우터 규칙

`ScimRouter` 한 곳에서, 이 순서로:

1. 자기 상태를 싣는 `ScimException`(400·404·405·501·503 등)과 알려진 도메인 예외(409 `uniqueness`, 400 `invalidValue` 등) — 지금과 같다.
2. 본문 크기 한도 → 413(§4.1).
3. 분류기가 일시 장애라고 하면 → 503 + `Retry-After: <초>`(정수 초, RFC 9110 §10.2.3) + SCIM Error. `LockUnavailableException` 도 이 길로 온다.
4. 본문 파싱 실패 → 400 `invalidSyntax`(지금과 같다), WebFlux 상태 예외 → 그 상태 + 우리 문구(§4.3).
5. 그 밖 → 500 + ERROR 로그(진짜 버그).

- **구현 중 정한 것:** 본문 한도(413)는 400 해석 실패보다 앞에 둔다 — 한도 예외가 `DecodingException` 에 싸여 오므로 뒤에 두면 400 이 되어 IdP 가 같은 본문을 되풀이한다.
- **구현 중 정한 것:** 분류기가 낸 503 의 `detail` 은 늘 고정 문구 "일시적으로 처리할 수 없습니다 — N초 뒤 다시 보내 주세요" 다. 예외 메시지를 싣지 않는다 — OpenFGA 서버 메시지·라이브러리 예외 문자열·락 용도 이름이
  인증 없는 엔드포인트로 나가지 않게(§4.3 의 취지). 예외는 WARN 로그에만 남는다. 부분 실패 503(§3.5)은 핸들러가 쓴 고정 문구에 리소스 `id` 만 붙는다.
- **구현 중 정한 것:** `Retry-After` 와 문구의 초는 정수 초로 **올림, 최소 1** 이다 — 1초 미만 대기가 0 이 되어 즉시 재시도를 부르지 않게.

### 3.5 부분 실패

- 핸들러가 일부 튜플만 반영된 결과(`fullyApplied == false`)에 500(`ScimException.internal`) 대신 503 + `Retry-After: 10` 을 낸다. 상태는 이미 커밋됐고 재시도가 같은 최종 상태를 목표로 다시
  계산하는 설계(SCIM 쓰기 락 설계 §7.2)는 그대로다.
- `ScimException.internal` 은 "저장된 리소스를 다시 읽지 못했다"(커밋 직후 다시 읽기가 빈 경우 — 동시 DELETE 등)에만 남는다.
- 코드 주석의 "IdP 는 500 을 영구 실패로 본다" 전제를 고쳐 적는다: 503 은 재시도 신호, 500 은 버그.
- **구현 중 정한 것:** 이 전제(또는 "응답은 5xx")를 적은 주석은 connector-scim 의 `ScimRouter`·핸들러뿐 아니라 core 의 `IncrementalSyncUseCase`(락 획득 구간·조직 삭제)·`IncrementalSyncResult`와
  authz-openfga 의 `OpenFgaRelationTupleWriter` 에도 있었다. connector-scim 의 것은 코드 변경과 함께, 나머지는 마지막 문서 커밋에서 "503 은 재시도 신호, 500 은 버그" 로 고쳤다.

## 4. 형식과 상태 코드 (M3·S7·S10·S11)

### 4.1 큰 본문 (M3)

- 원인 체인에 `DataBufferLimitException` 이 있으면 413 + SCIM Error. `detail` 에 한도 바이트 수와 "큰 조직의 멤버는 PATCH 로 나눠 보낸다" 를 싣는다.
- 한도 값은 Spring 코덱 설정(`spring.codec.max-in-memory-size`, 기본 256KB)을 `ScimConfig` 가 읽어 라우터에 넘긴다. 한도 자체는 올리지 않는다(인증 전 노출 — 별도 결정).
- **구현 중 정한 것:** 413 이 생기기 전에는 한도를 넘는 본문이 400 이 아니라 500 이었다(구현 중 연결 테스트로 확인) — 점검 M3 의 진단이 맞다.

### 4.2 라우트 밖 요청 (S7)

`/scim/v2/**` 아래에서 맞는 라우트가 없을 때도 SCIM Error 로 답한다.

- 있는 경로에 틀린 메서드 → 405 + `Allow` 헤더(그 경로가 받는 메서드).
- `/Schemas`, `/ResourceTypes`, `/Bulk`, `/Me`(와 그 아래 경로) → 501 — 서버 루트 조회와 같다. RFC 7644 는 지원하지 않는 작업을 501 로 알린다(§3.12). `/Me` 는 §3.11 이 501 을 명시하고, Bulk 는
  `ServiceProviderConfig` 가 이미 지원 안 함으로 선언했다.
- 그 밖 → 404.
- **구현 중 정한 것:** 405 의 `Allow` 지도에 서버 루트 라우트도 넣는다(`/scim/v2`·`/scim/v2/` 는 GET, `/scim/v2/.search` 는 POST) — 그래야 `DELETE /scim/v2` 가 404 가 아니라 405 다.
  `.search` 가 `{id}` 패턴에도 맞으므로 받는 메서드를 모두 모으고, `Allow` 는 이름순으로 낸다(실행마다 헤더 순서가 달라지지 않게).

### 4.3 내부 정보 노출 (S10)

- WebFlux 가 정한 상태 코드(415·406 등)는 그대로 쓰되 `detail` 은 Spring 의 `reason` 을 싣지 않고 우리 문구를 쓴다. 415 는 "Content-Type 은 application/scim+json 또는 application/json 이어야
  합니다", 그 밖은 HTTP 상태 이름.

### 4.4 `noTarget` (S11)

- path 없는 `remove` 는 400 `noTarget`(RFC 7644 §3.5.2.2) — 직원·조직 모두. 다른 path 없는 연산의 판정은 그대로다.

## 5. 바뀌는 곳

| 모듈 | 바뀌는 것 |
|---|---|
| core | `TemporaryFailureException`(새), `TemporaryFailureRecognizer`(새 포트), `LockUnavailableException`·`TupleWriteAbortedException` 이 상속, 락 획득 재시도 소진 때 `retryAfter` 물려받기, 전제를 적은 주석 정정 |
| storage-dynamodb | `DynamoDbMutationLock`(`ReturnValuesOnConditionCheckFailure.ALL_OLD`, 쥔 쪽 용도 → `retryAfter`), `BatchRequests`(상한 소진 → `TemporaryFailureException`), `DynamoDbTemporaryFailures`(새, AWS SDK 인식기 빈) |
| authz-openfga | `OpenFgaTemporaryFailures`(새, OpenFGA SDK 인식기 빈), `OpenFgaRelationTupleChecker`(`batchCheck` 응답 이상 → 표지) |
| connector-scim | `TemporaryFailureClassifier`(새, 인식기를 모은다), `ScimRouter`(규칙, 413, 405·501·404, 문구), `ScimConfig`(인식기·한도 주입), 핸들러(부분 실패 503), `ScimPatchApplier`(`noTarget`), `ScimException`(503·405 생성자) |
| app-scim | main 은 그대로(조립만) — e2e 테스트만 더한다 |
| 문서 | README SCIM 오류 표(상태, 언제, IdP 가 할 일, `Retry-After`), 점검 문서 M3·M4·S2·S7·S10·S11 해결 표시 |

## 6. 옮기기

- 없음. 응답 상태만 바뀐다.

## 7. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| 일시 장애 → 503 | 가짜 분류기·core 표지로 503, `Retry-After` 초, SCIM Error 형식 | connector-scim 라우터 |
| 버그 → 500 | 모르는 예외(예: `NullPointerException`)는 500, ERROR 로그 | connector-scim 라우터 |
| 부분 실패 → 503 | 핸들러가 `fullyApplied == false` 결과에 503 + 10초 | connector-scim 핸들러 |
| 413 | 한도를 넘는 PATCH 본문 → 413, `detail` 에 한도. 깨진 JSON 본문은 400 `invalidSyntax`(503 이 아니다) | connector-scim·app-scim e2e |
| 405·501·404 | 틀린 메서드 405 + `Allow`, `/Bulk`·`/Me`·`/Schemas`·`/ResourceTypes` 501, 모르는 경로 404 — 모두 SCIM Error | connector-scim 라우터 |
| 415 문구 | 내부 클래스 이름이 `detail` 에 없다 | connector-scim 라우터 |
| `noTarget` | path 없는 `remove` → 400 `noTarget`(직원·조직) | connector-scim |
| AWS 인식기 | 실제 AWS SDK 예외(스로틀링·5xx·클라이언트 오류는 일시, 조건 실패·검증은 아님) | storage-dynamodb 단위 |
| OpenFGA 인식기 | 실제 OpenFGA SDK 예외(429·500 은 일시, 501·거절·인증·store 없음·해석 실패·네트워크 감싸기는 인식기가 빈 값) | authz-openfga 단위 |
| `batchCheck` 응답 이상 | 수 불일치·모르는/중복 correlationId·내부 오류뿐인 개별 오류는 일시 장애, 입력 오류가 있으면(섞여도) 아님 | authz-openfga 단위 |
| 분류기 | core 표지·어댑터 인식기·I/O 를 원인 체인 깊이와 상관없이 가른다. 모르는 예외와 Jackson 해석 실패는 일시 장애가 아니다 | connector-scim 단위 |
| 락 쥔 쪽 용도 | 실제 DynamoDB Local 에서 `REBUILD` 락을 심어 두면 획득 실패의 `retryAfter` 가 60초, `WRITE` 면 2초(Local 2.5.3 은 기존 항목을 돌려준다 — §3.2) | storage-dynamodb |
| 끝에서 끝 | 재적재 용도 락을 심어 두면 SCIM 쓰기가 503 + `Retry-After: 60`, 큰 PATCH 413, `/scim/v2/Bulk` 501, 두 어댑터의 인식기가 앱에 실리고 그 둘로 조립한 분류기가 네트워크 실패·5xx 와 해석 실패·검증 오류를 가른다 | app-scim e2e |

## 8. 결과 (구현 후 기록)

2026-10-05, 브랜치 `audit-scim-errors` c69b51b(그 뒤 커밋은 스펙만), DynamoDB Local·OpenFGA 컨테이너, 개발 노트북.

- **`./gradlew cleanTest test`** — 1,311개 통과, 4분 36초(④-2 1,240개에서 +71).
- **`./gradlew cleanScaleTest scaleTest`** — 79개 통과, 14분 31초. 새 규모 테스트는 없다. 오류 번역은 요청 하나의 길이라 규모와 무관하고, 기존 규모 테스트 전체가 바뀐 분류기·라우터를 지난다. S18-b(재적재 중 쓰기 64건)도 여기에 든다.
- **규모 실측**(같은 실행, 시간은 참고):

  | 항목 | ④-2 | ⑤-1 |
  |---|---|---|
  | LDAP 전체 동기화 6,124명 — 이름 기반(`LdapScaleSyncCostTest`) | 9.5초 / 검증 2.5초 | 10.5초 / 검증 2.7초 |
  | 같은 조직도 — `entryUUID`(`LdapEntryUuidScaleTest`) | 8.2초 / 검증 3.5초 | 10.5초 / 검증 3.6초 |
  | SCIM 조직 먼저 순서(S1-b) — 직원 6,124명 POST + 멤버 PATCH 350건 | 53.5초 | 41.0초 |
  | 10만 명 조직 삭제 | 37.5초 | 38.2초 |
  | `type` 없는 멤버 1,000명 추가 | 913ms | 891ms |

  이 슬라이드는 쓰기 길을 바꾸지 않았다. 차이는 같은 노트북에서 실행할 때마다 생기는 흔들림이다.

## 9. 왜 다른 길을 안 갔나

- **4xx 가 아니면 모두 503.** 가장 단순하고 500 이 사라지지만, 진짜 버그도 IdP 가 끝없이 재시도하고 운영자가 응답으로 버그와 장애를 가를 수 없다.
- **어댑터가 모든 I/O 실패를 core 표지로 감싸기.** 모듈 경계가 가장 깔끔하지만 DynamoDB 저장소의 호출 지점 수십 곳을 고쳐야 한다. 분류기는 원인 체인 한 곳에서 같은 일을 한다.
- **`Retry-After` 하나로 통일.** 재적재 중에는 IdP 가 헛되이 자주 두드리고, 짧은 경합에는 너무 오래 기다린다.
- **락 경합은 429.** AWS·Auth0 가 쓰지만 HTTP 의 429 는 속도 제한이라 뜻이 맞지 않는다.
- **라이브러리 예외를 connector-scim 이 직접 알아보기.** connector-scim 이 AWS·OpenFGA SDK 에 의존하게 된다 — 지금 모듈 경계를 깬다.
- **조립하는 app-scim 이 SDK 를 아는 분류기를 갖기.** 처음 설계였다. 계획 단계에서 어댑터가 core 포트 인식기를 내는 쪽으로 바꿨다 — SDK 를 아는 코드가 어댑터 안에 머물고 app-scim 은 OpenFGA SDK 를 main 에 두지 않는다.
- **본문 한도 올리기.** 인증 없는 엔드포인트에서 메모리를 더 쓰게 한다 — 인증 슬라이드 뒤에 다시 본다.

## 10. 이 설계가 말할 수 없는 것

- **IdP 가 503·`Retry-After` 를 어떻게 다루는지는 문서·추정이다** — Entra 는 개별 오류를 다음 주기에 재시도한다고 문서화했고, Okta 의 5xx 반응은 문서에 없다. `Retry-After` 를 지키는지는
  IdP 마다 다르다.
- **영구히 거절되는 튜플의 부분 실패도 503 이다** — 결과에 실패 사유는 있지만 일시/영구 구분이 없다. 서버 발급 UUID 아래에서는 OpenFGA 가 거절할 아이디가 거의 생기지 않는다. 생기면 IdP 가
  되풀이하고 ERROR 로그가 남는다.
- **OpenFGA 쓰기 차단기가 결정적 거절(인가 모델 불일치 등)로 서도 `TupleWriteAbortedException` 은 표지라 503 이다.** 그 사고 동안 IdP 는 재시도를 계속한다(WARN 로그, 재적재로 복구).
- **분류기는 알려진 라이브러리 예외만 안다** — 새 의존성의 장애 예외는 분류기에 더할 때까지 500 이다(안전한 쪽: 버그로 보인다).
- **DynamoDB Local 이 조건 실패 때 기존 항목을 돌려주는지** — 확인했다: Local 2.5.3 은 돌려준다(§3.2). 실제 DynamoDB 도 지원한다(SDK 2.28 의 `ReturnValuesOnConditionCheckFailure`).

## 11. 범위 밖

- ⑤-2 요청 해석: M5(저장하지 않는 속성의 path 연산), M18(`manager` 매핑 안내), S1(`Location` 헤더·`meta.location` 인코딩), S5 앞부분(`externalId` 없는 조직 POST 재시도), S6(속성 이름 대소문자),
  S8(`active` 문자열), S9(조직 PATCH URN 접두), S12(거절하는 조회 모양), ④-1 이월(경로 없는 조직 PATCH 의 `externalId`).
- 재적재가 락을 쥔 동안(마지막 실패 60초) SCIM 쓰기의 획득 재시도를 3초 다 기다리지 않고 바로 끝낼 수 있다(요청마다 3초·PutItem 약 15번 절약) — 최적화라 미룬다.
- app-ldap·관리 API 의 오류 응답(IdP 신호가 아니다).
- 권고 ⑥(나머지 성능), 기존 백로그, 인증(마지막).
