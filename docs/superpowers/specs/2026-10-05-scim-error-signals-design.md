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
- M4: **core 표지 + 앱이 라이브러리 예외를 알아보기.** 우리가 만드는 일시 장애는 core 표지를 달고, 라이브러리 예외(AWS SDK·OpenFGA SDK)는 조립하는 모듈(app-scim)의 분류기가 알아본다.
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
- 기다릴 시간(상수, 운영에서 바꿀 이유가 보이면 그때 설정으로 올린다):

  | 원인 | `retryAfter` |
  |---|---|
  | 락 획득 조건 실패 — 쥔 쪽이 SCIM 쓰기(`WRITE`) | 2초 |
  | 락 획득 조건 실패 — 쥔 쪽이 재적재(`REBUILD`)·전체 동기화(`SYNC`) | 60초 |
  | 락 획득 조건 실패 — 쥔 쪽의 용도를 돌려받지 못함 | 2초(흔한 경우인 쓰기 경합으로 본다) |
  | 락 저장소 오류(조건 실패가 아닌 것)·리스 상실·갱신 실패 | 10초 |
  | 쓰기 차단기·재시도 상한 소진·분류기가 알아본 라이브러리 장애·부분 실패 | 10초 |

### 3.2 락을 쥔 쪽의 용도

- `DynamoDbMutationLock.acquire` 의 `PutItem` 에 `ReturnValuesOnConditionCheckFailure.ALL_OLD` 를 단다. 조건이 깨지면 DynamoDB 가 기존 락 항목을 돌려주고, 그 `PURPOSE`(이미 적고 있다)로 위 표의 시간을 고른다.
  돌려받지 못하면(빈 항목) 2초.
- core 의 획득 재시도(3초 기다림)가 끝나 `LockUnavailableException` 을 새로 만들 때, 마지막 시도의 `retryAfter` 를 물려받는다(재시도 소진 예외의 원인이 마지막 실패다).

### 3.3 라이브러리 예외 분류기

- connector-scim 에 인터페이스 `TemporaryFailureClassifier` 를 둔다: `Optional<Duration> 재시도_대기(Throwable)` — 원인 체인 어디에든 일시 장애가 있으면 기다릴 시간.
- connector-scim 의 기본 구현은 core 표지(`TemporaryFailureException`)만 본다.
- app-scim 의 구현(빈으로 바꿔 끼운다)은 core 표지에 더해, 각 SDK 가 스스로 정한 재시도 분류를 따른다:
  - AWS SDK: `SdkClientException`(네트워크·시간 초과)과, `AwsServiceException` 중 스로틀링이거나 5xx 이거나 SDK 가 재시도 가능하다고 표시한 것. 조건 실패·검증 오류(4xx)는 아니다 — 다시 보내도 같다.
  - OpenFGA SDK: `ApiException`(`FgaError`) 중 거절(`FgaApiValidationError`, 400)이 아닌 것, 그리고 OpenFGA 호출이 `IOException`·시간 초과로 끝난 것.
    우리 어댑터가 감싼 `IllegalStateException("OpenFGA … 호출 실패", 원인)` 도 원인 체인으로 따라가 본다.
  - 모두 10초.

### 3.4 라우터 규칙

`ScimRouter` 한 곳에서, 이 순서로:

1. `ScimException`·락 이외의 알려진 도메인 예외(409 `uniqueness`, 400 `invalidValue` 등) — 지금과 같다.
2. 분류기가 일시 장애라고 하면 → 503 + `Retry-After: <초>`(정수 초, RFC 9110 §10.2.3) + SCIM Error. `LockUnavailableException` 도 이 길로 온다.
3. 본문 파싱 실패 → 400 `invalidSyntax`(지금과 같다), 본문 크기 한도 → 413(§4.1), WebFlux 상태 예외 → 그 상태 + 우리 문구(§4.3).
4. 그 밖 → 500 + ERROR 로그(진짜 버그).

### 3.5 부분 실패

- 핸들러가 일부 튜플만 반영된 결과(`fullyApplied == false`)에 500(`ScimException.internal`) 대신 503 + `Retry-After: 10` 을 낸다. 상태는 이미 커밋됐고 재시도가 같은 최종 상태를 목표로 다시
  계산하는 설계(SCIM 쓰기 락 설계 §7.2)는 그대로다.
- `ScimException.internal` 은 "저장된 리소스를 다시 읽지 못했다"(커밋 직후 다시 읽기가 빈 경우 — 동시 DELETE 등)에만 남는다.
- 코드 주석의 "IdP 는 500 을 영구 실패로 본다" 전제를 고쳐 적는다: 503 은 재시도 신호, 500 은 버그.

## 4. 형식과 상태 코드 (M3·S7·S10·S11)

### 4.1 큰 본문 (M3)

- 원인 체인에 `DataBufferLimitException` 이 있으면 413 + SCIM Error. `detail` 에 한도 바이트 수와 "큰 조직의 멤버는 PATCH 로 나눠 보낸다" 를 싣는다.
- 한도 값은 Spring 코덱 설정(`spring.codec.max-in-memory-size`, 기본 256KB)을 `ScimConfig` 가 읽어 라우터에 넘긴다. 한도 자체는 올리지 않는다(인증 전 노출 — 별도 결정).

### 4.2 라우트 밖 요청 (S7)

`/scim/v2/**` 아래에서 맞는 라우트가 없을 때도 SCIM Error 로 답한다.

- 있는 경로에 틀린 메서드 → 405 + `Allow` 헤더(그 경로가 받는 메서드).
- `/Schemas`, `/ResourceTypes`, `/Bulk`, `/Me` → 501 — 서버 루트 조회와 같다. RFC 7644 는 지원하지 않는 작업을 501 로 알린다(§3.12). `/Me` 는 §3.11 이 501 을 명시하고, Bulk 는
  `ServiceProviderConfig` 가 이미 지원 안 함으로 선언했다.
- 그 밖 → 404.

### 4.3 내부 정보 노출 (S10)

- WebFlux 가 정한 상태 코드(415·406 등)는 그대로 쓰되 `detail` 은 Spring 의 `reason` 을 싣지 않고 우리 문구를 쓴다. 415 는 "Content-Type 은 application/scim+json 또는 application/json 이어야
  합니다", 그 밖은 HTTP 상태 이름.

### 4.4 `noTarget` (S11)

- path 없는 `remove` 는 400 `noTarget`(RFC 7644 §3.5.2.2) — 직원·조직 모두. 다른 path 없는 연산의 판정은 그대로다.

## 5. 바뀌는 곳

| 모듈 | 바뀌는 것 |
|---|---|
| core | `TemporaryFailureException`(새), `LockUnavailableException`·`TupleWriteAbortedException` 이 상속, 락 획득 재시도 소진 때 `retryAfter` 물려받기 |
| storage-dynamodb | `DynamoDbMutationLock`(`ReturnValuesOnConditionCheckFailure.ALL_OLD`, 쥔 쪽 용도 → `retryAfter`), `BatchRequests`(상한 소진 → `TemporaryFailureException`) |
| connector-scim | `TemporaryFailureClassifier`(새, 기본 구현), `ScimRouter`(규칙, 413, 405·501·404, 문구), `ScimConfig`(분류기·한도 주입), 핸들러(부분 실패 503), `ScimPatchApplier`(`noTarget`), `ScimException`(503 생성자) |
| app-scim | AWS·OpenFGA SDK 를 아는 분류기 빈 |
| 문서 | README SCIM 오류 표(상태, 언제, 재시도 여부, `Retry-After`), 점검 문서 M3·M4·S2·S7·S10·S11 해결 표시 |

## 6. 옮기기

- 없음. 응답 상태만 바뀐다.

## 7. (빈 절 — 번호를 ④ 설계와 맞춘다)

## 8. 검증

| 무엇 | 어떻게 | 어디 |
|---|---|---|
| 일시 장애 → 503 | 가짜 분류기·core 표지로 503, `Retry-After` 초, SCIM Error 형식 | connector-scim 라우터 |
| 버그 → 500 | 모르는 예외(예: `NullPointerException`)는 500, ERROR 로그 | connector-scim 라우터 |
| 부분 실패 → 503 | 핸들러가 `fullyApplied == false` 결과에 503 + 10초 | connector-scim 핸들러 |
| 413 | 한도를 넘는 PATCH 본문 → 413, `detail` 에 한도 | connector-scim·app-scim e2e |
| 405·501·404 | 틀린 메서드 405 + `Allow`, `/Bulk`·`/Me`·`/Schemas`·`/ResourceTypes` 501, 모르는 경로 404 — 모두 SCIM Error | connector-scim 라우터 |
| 415 문구 | 내부 클래스 이름이 `detail` 에 없다 | connector-scim 라우터 |
| `noTarget` | path 없는 `remove` → 400 `noTarget`(직원·조직) | connector-scim |
| 분류기 | 실제 AWS SDK 예외(스로틀링·5xx·클라이언트 오류는 일시, 조건 실패·검증은 아님)와 OpenFGA SDK 예외(거절은 아님)를 원인 체인 깊이와 상관없이 가른다 | app-scim 단위 |
| 락 쥔 쪽 용도 | 실제 DynamoDB Local 에서 `REBUILD` 락을 심어 두면 획득 실패의 `retryAfter` 가 60초, `WRITE` 면 2초. Local 이 기존 항목을 돌려주지 않으면 그 사실을 기록하고 2초 기본값을 시험한다 | storage |
| 끝에서 끝 | 재적재 용도 락을 심어 두면 SCIM 쓰기가 503 + `Retry-After: 60`, 큰 PATCH 413, `/scim/v2/Bulk` 501 | app-scim e2e |

## 9. 결과 (구현 후 기록)

머지 전 `test`·`scaleTest` 시간과 결과를 구현이 끝나면 여기에 적는다.

## 10. 왜 다른 길을 안 갔나

- **4xx 가 아니면 모두 503.** 가장 단순하고 500 이 사라지지만, 진짜 버그도 IdP 가 끝없이 재시도하고 운영자가 응답으로 버그와 장애를 가를 수 없다.
- **어댑터가 모든 I/O 실패를 core 표지로 감싸기.** 모듈 경계가 가장 깔끔하지만 DynamoDB 저장소의 호출 지점 수십 곳을 고쳐야 한다. 분류기는 원인 체인 한 곳에서 같은 일을 한다.
- **`Retry-After` 하나로 통일.** 재적재 중에는 IdP 가 헛되이 자주 두드리고, 짧은 경합에는 너무 오래 기다린다.
- **락 경합은 429.** AWS·Auth0 가 쓰지만 HTTP 의 429 는 속도 제한이라 뜻이 맞지 않는다.
- **라이브러리 예외를 connector-scim 이 직접 알아보기.** connector-scim 이 AWS·OpenFGA SDK 에 의존하게 된다 — 지금 모듈 경계를 깬다.
- **본문 한도 올리기.** 인증 없는 엔드포인트에서 메모리를 더 쓰게 한다 — 인증 슬라이드 뒤에 다시 본다.

## 11. 이 설계가 말할 수 없는 것

- **IdP 가 503·`Retry-After` 를 어떻게 다루는지는 문서·추정이다** — Entra 는 개별 오류를 다음 주기에 재시도한다고 문서화했고, Okta 의 5xx 반응은 문서에 없다. `Retry-After` 를 지키는지는
  IdP 마다 다르다.
- **영구히 거절되는 튜플의 부분 실패도 503 이다** — 결과에 실패 사유는 있지만 일시/영구 구분이 없다. 서버 발급 UUID 아래에서는 OpenFGA 가 거절할 아이디가 거의 생기지 않는다. 생기면 IdP 가
  되풀이하고 ERROR 로그가 남는다.
- **분류기는 알려진 라이브러리 예외만 안다** — 새 의존성의 장애 예외는 분류기에 더할 때까지 500 이다(안전한 쪽: 버그로 보인다).
- **DynamoDB Local 이 조건 실패 때 기존 항목을 돌려주는지** — 구현에서 확인한다. 실제 DynamoDB 는 지원한다(SDK 2.28 의 `ReturnValuesOnConditionCheckFailure`).

## 12. 범위 밖

- ⑤-2 요청 해석: M5(저장하지 않는 속성의 path 연산), M18(`manager` 매핑 안내), S1(`Location` 헤더·`meta.location` 인코딩), S5 앞부분(`externalId` 없는 조직 POST 재시도), S6(속성 이름 대소문자),
  S8(`active` 문자열), S9(조직 PATCH URN 접두), S12(거절하는 조회 모양), ④-1 이월(경로 없는 조직 PATCH 의 `externalId`).
- app-ldap·관리 API 의 오류 응답(IdP 신호가 아니다).
- 권고 ⑥(나머지 성능), 기존 백로그, 인증(마지막).
