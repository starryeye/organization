# 인증 설계 (2026-10-28)

이 슬라이드는 서버에 인증을 처음 넣는다. 남은 슬라이드 가운데 마지막이다.
- **외부 리뷰(2026-10-19) §5 "인증 슬라이드가 맡을 것".** 리뷰 넷이 모두 "운영 불가" 의 첫 이유로 인증 부재를 꼽았다. 근거: `docs/superpowers/specs/2026-10-19-outsider-review/0-종합.md` §5.
- **온보딩 13 §1.1~§1.3.** 인증 없음, 감사 행위자 없음, 로그인 → employeeId 정확 조회 없음(N10).

사용자가 방향을 정했다: "authorization server 는 그냥 가장 간단하게 준비해서 해봐(spring security)", 그리고 "추천대로 진행". **그 밖의 결정은 모두 내 추천이다.** 질문 없이 정했고, 이 문서를 승인하는 것이 §2 의 결정을 승인하는 것이다.

조사 자료(git 이 무시하는 위치): `.superpowers/auth/research-code.md` — HTTP 표면, 감사·회차 모델, Reactor Context 흐름, 시험이 HTTP 를 부르는 방식, OpenFGA 클라이언트, 직원 조회를 파일:줄로 정리했다. 이 문서의 코드 사실은 거기서 왔다.

## 1. 문제

### 1.1 어디에도 인증이 없다 (13 §1.1, 리뷰 Critical)

- SCIM `/scim/v2/**`, 관리 API `/admin/**`, actuator(`health,info,metrics,prometheus`) 모두 누구나 부른다.
- 그래서 누구나 `mode=wipe` 로 SCIM 조직도를 비우고(확인값은 공개된 테이블명), `force=true` 로 삭제 가드를 넘기며, 조직도·이름·이메일·감사 이력을 읽고, 직원 상세(경로마다 Check) 같은 비싼 호출로 비용을 키울 수 있다.
- OpenFGA 호출에도 자격 증명이 없다(`StoreBootstrapper.newClient`). 장부 쓰기 권한을 이 서버로 좁힐 수 없어, 누구나 OpenFGA 에 직접 줄을 쓸 수 있다.
- 지금의 우회는 "앞단 게이트웨이·네트워크 정책에서 막는다" 뿐이다.

### 1.2 누가 했는지 남지 않는다 (13 §1.2)

- 감사의 원인 `AuditCause(source, kind, id)` 에 행위자가 없다. 실행 기록 `SyncRun` 에도 없다.
- `force`·`wipe` 를 걸었다는 사실은 트리거(`FORCED`, `FORCED_REBUILD`, `RESET`)로 남지만, 누가 걸었는지는 모른다.

### 1.3 로그인한 사람을 employeeId 로 바꾸는 정확한 조회가 없다 (13 §1.3, N10)

- 권한을 묻는 앱(직원의 권한을 OpenFGA 에 묻는 다른 서비스)은 `GET /admin/employees?userName=` (접두사, 대소문자 무시) 이나 `?externalId=` (정확, 여럿 가능) 로 찾는다.
- 0건·2건 이상일 때의 계약이 없다. LDAP 의 userName 은 유일하지 않고, 직원 externalId 는 어느 쪽에서도 유일성을 강제하지 않는다.

## 2. 결정

| # | 결정 | 이유 |
|---|---|---|
| A1 | **새 앱 `app-auth`: Spring Authorization Server.** 부여 방식은 `client_credentials` 하나, 클라이언트 인증은 `client_secret_basic`·`client_secret_post`. 등록 클라이언트는 Boot 속성(`spring.security.oauth2.authorizationserver.client.*`)으로만 둔다. 액세스 토큰은 서명된 JWT 다 | 사용자가 정한 "Spring Security 로 가장 간단한 authorization server". 이 서버의 호출자는 모두 기계(IdP, 운영 도구, 권한을 묻는 앱, Prometheus)라 사람 로그인이 필요 없다 |
| A2 | **두 앱(app-ldap·app-scim)은 OAuth2 리소스 서버다.** 서명 키 목록(`jwk-set-uri`)과 발급자(`issuer-uri`)로 JWT 를 그 자리에서 검증한다. 기동 때 인가 서버에 묻지 않는다 | 표준(RFC 6750 bearer, RFC 7519 JWT)이라 회사 IdP 를 인가 서버로 써도 설정만 바꾸면 된다. 기동이 인가 서버에 묶이지 않는다 |
| A3 | **범위(scope) 여섯 개.** `scim`, `admin.read`, `admin.sync`, `admin.destructive`, `directory.lookup`, `actuator`. §4 의 표대로 경로에 묶는다 | 리뷰가 짚은 위험(wipe·force, PII 읽기, 비용)을 각각 다른 범위로 가른다 |
| A4 | **`force=true` 와 `mode=wipe` 는 `admin.destructive` 가 더 있어야 한다.** 필터가 아니라 컨트롤러가 본다 | 쿼리 값을 스프링이 바꾼 뒤(`force=yes`·`1` 도 true)에 판정해야 우회가 없다 |
| A5 | **401·403 본문을 그 표면의 오류 형식에 맞춘다.** SCIM 은 SCIM Error(`application/scim+json`), 관리 API 는 problem details, actuator 는 스프링 기본. 모두 `WWW-Authenticate: Bearer …`(RFC 6750 §3)를 단다 | 보안 필터가 앞에서 거절하면 지금의 오류 번역(`ScimRouter.onError`, `AdminExceptionHandler`)을 거치지 않는다 |
| A6 | **헬스.** `/actuator/health`·`/health/liveness`·`/health/readiness` 는 인증 없이 상태만. 세부는 `actuator` 범위가 있을 때만. `info`·`metrics`·`prometheus` 는 `actuator` 범위 | 프로브·로드밸런서는 토큰 없이 부른다(11 운영). Prometheus 는 `oauth2` 스크레이프 설정으로 토큰을 받는다 |
| A7 | **SCIM `authenticationSchemes`** 에 `oauthbearertoken` 하나를 싣는다. 발견 엔드포인트(`ServiceProviderConfig`·`ResourceTypes`·`Schemas`)도 `scim` 범위가 있어야 한다 | RFC 7643 §5 가 이 칸을 요구한다. RFC 7644 §2 는 익명을 "허용해도 된다(MAY)" 일 뿐이다. IdP 는 같은 토큰으로 발견도 부른다 |
| A8 | **감사 행위자.** `AuditCause` 와 `SyncRun` 에 `actor` 를 더한다. 토큰으로 온 요청은 `client:<sub>`, 예약 작업은 `system:scheduler`, 아카이빙의 남은 회수 지우기는 `system:archive`. 관리 API 응답(이력의 `cause.actor`, 회차의 `requestedBy`)에 싣는다 | 13 §1.2. 운영자마다 클라이언트를 따로 주면 사람 단위로도 남는다 |
| A9 | **행위자는 Reactor Context 로 나른다.** 인증 뒤 웹 필터가 `chain.filter(exchange).contextWrite(…)` 로 싣고, core 는 그 Context 에서 읽는다(Spring 의존 없이) | 조사 §5: 두 분리 지점(`IncrementalSyncUseCase.withLock`, `SyncJobs.띄운다`)이 요청 Context 를 되넣으므로 감사 호출까지 닿는다. 메서드마다 인자를 더하는 것보다 좁다 |
| A10 | **정확 조회 둘.** `GET /admin/employees/lookup`(`userName` 대소문자 무시 정확, 또는 `externalId` 정확)과 `GET /admin/organizations/lookup`(`externalId` 정확). 정확히 하나면 200, 없으면 404, 둘 이상이면 409(수만, 목록은 싣지 않음). `directory.lookup` 이나 `admin.read` | N10 의 "0건·2건 이상" 계약. 권한을 묻는 앱은 `Check(user:<employeeId>, member, group:<orgCode>)` 를 묻는데, SCIM 배포의 orgCode 는 서버가 발급한 UUID 라 wipe 뒤 바뀐다 — 앱이 orgCode 도 찾아야 한다. PII 를 훑는 범위 없이 이 둘만 받는다 |
| A11 | **OpenFGA 사전 공유 키.** `openfga.api-token` 을 클라이언트에 싣는다. prod 는 `openfga.require-api-token: true` 로, 키가 비었거나 풀리지 않은 자리표시자면 기동을 멈춘다(값은 싣지 않는다). 기동 때 OpenFGA 가 401 이면 기동을 멈춘다 | **목표는 익명 호출 차단이다.** 사전 공유 키 모드에서는 키가 맞으면 API 전부가 열린다 — 쓰기만 이 서버로 좁히려면 OIDC 와 OpenFGA 의 실험 기능(access control)이 필요해 범위 밖이다(§15). 권한을 묻는 앱에는 따로 키를 준다(OpenFGA 는 키를 여럿 받는다). 운영 준비 D3 의 비밀번호 검사와 같은 모양이다 |
| A12 | **운영 설정 검사.** prod 에서 리소스 서버는 `issuer-uri`·`jwk-set-uri` 가 없으면, app-auth 는 서명 키가 없거나 클라이언트 비밀이 `{bcrypt}` 가 아니면 기동을 멈춘다. **비밀이 다니는 새 경로는 prod 에서 https 여야 한다** — 리소스 서버의 `issuer-uri`·`jwk-set-uri`, app-auth 의 발급자(`auth.require-https`), 키를 실을 때의 `openfga.api-url`(`openfga.require-tls`). 아니면 기동을 멈춘다 | 설정 실수가 "인증 없는 운영", "재시작마다 모든 토큰 무효", "비밀·키 목록이 평문으로 다님" 으로 조용히 넘어가지 않게. 평문 `jwk-set-uri` 는 중간자가 자기 키를 내밀어 아무 토큰이나 위조하게 한다. 선례는 `ldap.require-tls` 다 |
| A13 | **문서.** 루트 README 와 온보딩(13 §1.1~1.3 고침, 03·06·09·11·12·02·15)을 코드대로 고친다. 기준 커밋은 이 브랜치의 마지막 코드 커밋 | 온보딩은 늘 현재 동작이다 |

**고치지 않기로 한 것.**
- **사람 로그인(SSO)·관리 화면.** 호출자가 모두 기계다. 운영자는 자기 클라이언트로 토큰을 받는다. 회사 IdP 연동은 A2 덕분에 설정만으로 가능하지만 이 슬라이드에서 시험하지 않는다.
- **토큰 하나만 무효로 하기(revocation·introspection).** 리소스 서버는 JWT 를 그 자리에서 검증한다. 무효화는 서명 키 교체로 한다(§3.4).
- **요청 수 제한.** 인증으로 익명 비용 증폭은 막힌다. 인증된 클라이언트의 과용은 범위 밖이다.

## 3. 인가 서버 `app-auth` (A1·A12)

### 3.1 모양

- 새 Gradle 모듈 `app-auth`. Spring Boot 앱이고 `spring-boot-starter-oauth2-authorization-server`, `spring-boot-starter-actuator` 를 쓴다. **Spring Authorization Server 는 서블릿 기반이라 이 앱만 Spring MVC 다**(나머지는 WebFlux). core 등 다른 모듈에 기대지 않는다.
- 포트: 로컬 9000. 발급자(`spring.security.oauth2.authorizationserver.issuer`): 로컬 `http://localhost:9000`, prod 는 설정 필수.
- 엔드포인트: 토큰(`/oauth2/token`), 서명 키 목록(`/oauth2/jwks`), 메타데이터(`/.well-known/oauth-authorization-server`). 인가 코드·사용자 로그인·동의 화면은 쓰지 않는다 — 폼 로그인과 사용자 저장소를 두지 않는다(Boot 가 사용자 비밀번호를 만들어 로그에 찍는 일이 없게).
- actuator: `health`(프로브 포함)만 노출, 인증 없음.

### 3.2 클라이언트와 토큰

- 클라이언트는 Boot 속성으로 등록한다. 클라이언트마다 `client-id`, `client-secret`, `authorization-grant-types: client_credentials`, `scopes`, `token.access-token-time-to-live`.
- **범위를 요청하지 않으면 등록된 범위를 모두 준다**(RFC 6749 §3.3 의 기본 범위). IdP 화면에 범위 칸이 없거나 비워 둔 경우를 위해서다. Spring Authorization Server 가 기본으로 그렇게 하지 않으면 그렇게 만든다(§16 확인할 것).
- 토큰 수명 기본값: 10분. IdP(Entra 의 client credentials)용 클라이언트는 60분을 권한다(Entra 앱 갤러리 요건이 60분~6시간이다).
- **붙여 넣는 토큰(Okta 의 HTTP Header 방식).** 그 IdP 용 클라이언트만 수명을 길게(예: 180일) 주고, 운영자가 토큰 엔드포인트에서 한 번 받아 IdP 화면에 붙여 넣는다. RFC 7644 §7 은 bearer 토큰의 수명이 유한해야 한다고 하므로, 수명이 **365일을 넘는 클라이언트 설정은 기동을 멈춘다.**
- 액세스 토큰 클레임: `iss`, `sub`(= client id), `aud`(Spring 기본), `scope`, `iat`, `exp`, `jti`, `kid`(헤더). 리소스 서버는 `iss`·서명·`exp`·`nbf`·`scope` 를 본다. `aud` 는 보지 않는다(이 배포의 인가 서버는 이 서버들만을 위한 것이다).
- 발급 기록: 토큰을 발급할 때 Spring Authorization Server 는 기본으로 인가 기록을 메모리에 쌓는다. 이 배포는 introspection·revocation 을 쓰지 않으므로 **기록을 남기지 않는 저장소로 바꾼다** — 쌓이기만 하는 메모리를 두지 않는다.

### 3.3 서명 키

- 로컬·시험: 기동마다 RSA 키를 새로 만든다(Boot 기본). 재시작하면 그 전 토큰은 모두 무효다.
- prod: RSA 개인 키 PEM 을 설정(`auth.signing-key`, 환경 변수 `AUTH_SIGNING_KEY`)으로 받는다. `kid` 는 공개 키에서 계산한 지문으로 고정한다. 비었거나 풀리지 않은 자리표시자면 기동을 멈춘다(`auth.require-signing-key: true`, prod).
- 키가 고정이고 발급 기록을 남기지 않으므로 app-auth 는 상태가 없다. 여러 대로 띄울 수 있다.

### 3.4 키 교체 = 전부 무효화

- 키 하나만 둔다. 교체하면 그 전 토큰은 리소스 서버가 새 키 목록을 받는 순간 모두 무효다(리소스 서버는 모르는 `kid` 를 보면 키 목록을 다시 받는다).
- client credentials 로 받는 클라이언트는 다음 요청에서 새 토큰을 받는다. 붙여 넣은 토큰을 쓰는 IdP 만 운영자가 다시 받아 붙여 넣는다.
- 붙여 넣은 토큰이 새었을 때의 처방이 이것이다. 한 배포에 커넥터가 하나라 영향받는 IdP 도 하나다.

### 3.5 클라이언트 비밀

- 로컬 yml 은 `{noop}` 비밀을 쓴다(개발용 클라이언트: `scim-idp`, `ops`, `asking-app`, `prometheus`).
- prod 는 비밀을 `{bcrypt}` 해시로 받는다. **prod 에서 `{bcrypt}` 가 아닌 비밀이 있으면 기동을 멈춘다**(`auth.require-hashed-secrets: true`). 평문 비밀은 클라이언트(IdP·운영 도구) 쪽에만 있다.

## 4. 리소스 서버 — 두 앱 (A2~A7)

### 4.1 설정

- `spring-boot-starter-oauth2-resource-server` 를 더한다. WebFlux `SecurityWebFilterChain` 하나를 두 앱이 함께 쓴다 — 새 모듈 `web-security`(java-library). SCIM 규칙은 app-ldap 에서는 해당 경로가 없어 무해하다.
- `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`, `issuer-uri` 를 둘 다 준다. 키 목록은 첫 토큰을 볼 때 받는다(기동 때 인가 서버에 묻지 않는다). prod 는 둘 다 필수다(A12).
- 권한은 `scope` 클레임에서 `SCOPE_<범위>` 로 만든다(스프링 기본).
- CSRF 는 끈다(bearer 토큰만 받는 API). 세션은 만들지 않는다.

### 4.2 경로와 범위

| 경로 | 요구 |
|---|---|
| `/scim/v2/**` (발견 포함) | `SCOPE_scim` |
| `GET /admin/employees/lookup`, `GET /admin/organizations/lookup` | `SCOPE_directory.lookup` 또는 `SCOPE_admin.read` |
| 그 밖의 `GET /admin/**` | `SCOPE_admin.read` |
| `POST /admin/sync/**` | `SCOPE_admin.sync` (+ `force=true`·`mode=wipe` 면 컨트롤러가 `SCOPE_admin.destructive` 를 더 요구, A4) |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | 없음(상태만) |
| `/actuator/**` 의 나머지, 헬스 세부 | `SCOPE_actuator` |
| 그 밖 모든 경로 | 거절(403, 인증 없으면 401) |

- 범위 하나가 다른 범위를 품지 않는다. 운영 도구에 필요한 범위를 모두 준다(예: `ops` = `admin.read admin.sync`, 비상용 `ops-break-glass` = `+ admin.destructive`).

### 4.3 위험한 요청 (A4)

- app-ldap `POST /admin/sync/full?force=true`, `POST /admin/sync/rebuild?force=true`, app-scim `POST /admin/sync/rebuild?mode=wipe` 는 `SCOPE_admin.destructive` 가 없으면 403 이다.
- 판정은 컨트롤러가 스프링이 바꾼 값(`boolean force`, `mode`)으로 한다. 403 은 `ResponseStatusException(FORBIDDEN, "<무엇>은 admin.destructive 범위가 필요하다")` 로 내고, 운영 준비 D8 의 처리기가 problem details 로 바꾼다.
- 거절은 실행 기록을 열기 전이다 — 락도 회차도 만들지 않는다.

### 4.4 401·403 본문 (A5)

| 표면 | 401 (토큰 없음·잘못됨·만료) | 403 (범위 부족) |
|---|---|---|
| SCIM | `application/scim+json` `{"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],"status":"401","detail":"인증이 필요하다"}` | 같은 모양, `"status":"403"`, `"detail":"이 요청에 필요한 범위가 없다"` |
| 관리 API | problem details, `title` 상태 문구, `detail` 위와 같은 문구 | 같음 |
| actuator·그 밖 | 스프링 기본(본문 없음) | 스프링 기본 |

- 모두 `WWW-Authenticate: Bearer` 를 단다. 토큰이 잘못됐으면 `error="invalid_token"`, 범위가 모자라면 `error="insufficient_scope"`(RFC 6750 §3.1). 토큰 내용이나 검증 실패의 세부를 본문에 싣지 않는다.
- 컨트롤러의 403(A4)은 관리 API 처리기를 거쳐 같은 모양이 된다.

### 4.5 헬스 세부 (A6)

- `management.endpoint.health.show-details: when-authorized` 는 그대로 두고, `actuator` 범위가 있을 때만 세부가 보이게 한다(`management.endpoint.health.roles` 를 그 권한으로). 범위 없는 인증된 클라이언트는 세부를 보지 못한다.
- 프로브 그룹은 인증 없이 상태만 낸다. 운영 준비 D5 대로 외부 의존을 넣지 않는다.

### 4.6 SCIM 발견 (A7)

`ServiceProviderConfig.authenticationSchemes`:

```json
[{"type":"oauthbearertoken","name":"OAuth Bearer Token",
  "description":"OAuth 2.0 client credentials 로 받은 bearer 토큰(RFC 6750)",
  "specUri":"https://www.rfc-editor.org/info/rfc6750","primary":true}]
```

## 5. 감사 행위자 (A8·A9)

### 5.1 값

| 출처 | actor |
|---|---|
| 토큰으로 온 요청(SCIM 쓰기, 관리 API 의 동기화·재적재) | `client:<sub>` — `sub` 는 client id |
| 예약 LDAP 동기화(`SyncScheduler`) | `system:scheduler` |
| SCIM 아카이빙(그 회차와 남은 회수 지우기 `revokeLeftovers`) | `system:archive` |

- 행위자가 없는 경로는 없다. core 는 행위자를 찾지 못하면 오류가 아니라 `system:unknown` 을 쓰고 WARN 을 남긴다(감사가 일을 막지 않는다는 기존 원칙, 감사 이력 설계 2026-10-10).

### 5.2 나르는 길

- core 에 행위자의 Context 키와 읽는 도우미를 둔다(Reactor 만 쓴다).
- `web-security` 의 웹 필터가 인증 뒤 `chain.filter(exchange).contextWrite(Context 키 → client:<sub>)` 로 싣는다. 하류 전체를 감싸야 한다(조사 §5.1).
- 예약 작업은 구독할 때 `contextWrite` 로 `system:…` 을 싣는다(`SyncScheduler`·`ArchiveScheduler` 는 이미 관측 키를 싣는 자리가 있다).
- 읽는 자리는 둘이다. `IncrementalSyncUseCase.withLock` 이 이미 `deferContextual` 로 잡은 Context 에서, `SyncJobs.띄운다` 가 잡은 Context 에서 읽어 원인과 회차에 넣는다.

### 5.3 저장과 응답

- `AuditCause(source, kind, id, actor)`. 감사 아이템에 `actor` 속성을 더한다(조각마다). 정렬키는 바꾸지 않는다.
- `SyncRun.requestedBy`. 회차 아이템에 `requestedBy` 속성을 더한다.
- 관리 API: 이력 `cause.actor`, 회차 `requestedBy`. 속성이 없는 옛 아이템은 칸을 비운다(운영 배포 전이라 이관하지 않는다).

## 6. 정확 조회 (A10)

- `GET /admin/employees/lookup?userName=<값>` 또는 `?externalId=<값>`. 정확히 하나여야 하고, 아니면 400.
- `userName`: 대소문자 무시 정확 일치 — 이미 있는 `DirectoryQueryRepository.findUsersByUserName`(GSI1 소문자 키). 소문자 키가 1024바이트를 넘으면 찾지 못한다(404).
- `externalId`: 정확 일치(대소문자 구분) — `findUserIdsByExternalId`(GSI3) 뒤 본 테이블로 다시 확인. 지금 `GET /admin/employees?externalId=` 와 같은 길이다.
- 응답:
  - 정확히 한 명: 200, `UserSummary`(`employeeId`, `userName`, `displayName`, `active`, `externalId`). 비활성 직원도 그대로 준다 — 묻는 앱이 `active` 를 본다.
  - 0명: 404 problem details "일치하는 직원이 없다".
  - 둘 이상: 409 problem details "N명이 일치한다 — externalId 로 찾는다". 직원 목록은 싣지 않는다(이 범위는 PII 를 훑는 범위가 아니다).
- 읽기는 GSI 한 번과 본 테이블 몇 번이다. 요청마다 전원을 읽지 않는다.

**조직 정확 조회** `GET /admin/organizations/lookup?externalId=<값>`:
- 지금의 `GET /admin/organizations?externalId=` 와 같은 정확 일치 길을 쓴다.
- 정확히 하나: 200, 조직 요약(`orgCode`, `displayName`, `externalId`). 0개: 404 "일치하는 조직이 없다". 둘 이상: 409 "N개가 일치한다". SCIM 은 조직 externalId 를 유일하게 지키므로 409 는 LDAP 에서만 날 수 있다.
- 권한을 묻는 앱은 이것으로 orgCode 를 찾아 Check 한다. wipe 뒤에는 다시 찾는다(12 장애와 복구).

## 7. OpenFGA 키 (A11)

- `OpenFgaProperties.apiToken`, `requireApiToken`(클래스 기본 false, prod true). `StoreBootstrapper.newClient` 가 키가 있으면 `Credentials(new ApiToken(키))` 를 싣는다.
- 검사: `requireApiToken` 인데 키가 null·빈칸·`${` 로 시작하면 `IllegalStateException("openfga.require-api-token 인데 openfga.api-token 이 비었거나 풀리지 않은 자리표시자다 — 환경 변수 OPENFGA_API_TOKEN 을 준다")`.
- prod yml: `openfga.api-token: ${OPENFGA_API_TOKEN}`, `require-api-token: true`. 로컬 docker-compose 의 OpenFGA 는 사전 공유 키로 띄우고 로컬 yml 에 같은 키를 둔다.
- 잘못된 키(401)는 일시 장애가 아니다 — 지금처럼 500 이고 재시도하지 않는다(`OpenFgaTemporaryFailures`).
- 기동 때 store 해석이 401 로 끝나면 기동을 멈춘다(`OpenFgaStoreInitializer`). 닿지 않는 것(연결 실패)은 지금처럼 경고만 남기고 뜬다 — 키 실수는 설정 실수다.
- **https.** 키를 실을 때(`require-api-token`) prod 는 `openfga.require-tls: true` 로 `openfga.api-url` 이 `https://` 여야 한다(A12).
- **키를 켜는 순서.** OpenFGA 서버에 사전 공유 키를 켜는 순간 키 없이 Check 하던 권한을 묻는 앱은 모두 401 이다. 앱마다 키를 먼저 나눠 주고 서버에 키를 켠다(11 운영에 적는다).
- **OpenFGA 서버를 다른 팀과 함께 쓰는 경우.** 서버의 인증을 켜고 끄는 것은 이 저장소가 정할 수 없다. 이 슬라이드의 범위는 "클라이언트가 키를 싣고, prod 에서 키를 요구한다" 이다. 이 배포 전용 OpenFGA(로컬 docker-compose 와 시험)는 키를 켜서 띄운다.

## 8. 시험

### 8.1 앱 시험이 토큰을 받는 법

- `@SpringBootTest` 38개가 모두 보안 필터를 탄다. 이들을 일일이 고치지 않도록 **시험 전용 키 한 쌍**을 testFixtures 에 둔다:
  - 시험 프로필의 리소스 서버는 그 공개 키로 검증한다(시험 전용 `ReactiveJwtDecoder` — 발급자 검증은 운영과 같다).
  - `WebTestClientBuilderCustomizer` 가 모든 범위를 가진 토큰을 그 개인 키로 서명해 기본 헤더로 싣는다. `client.mutate()` 로 만든 클라이언트도 이어받는다.
  - 범위·만료를 바꾼 토큰을 만드는 도우미를 둔다(거절 시험용).
  - `WebClient` 를 직접 만드는 시험(`ScimWriteContentionScaleTest`)은 같은 도우미로 헤더를 단다.
- 보안 필터 자체는 운영과 같은 구성이다. 바뀌는 것은 키를 어디서 받느냐뿐이다.

### 8.2 진짜 인가 서버로 하는 시험

- app-auth 를 같은 JVM 에서 무작위 포트로 띄우고, app-scim 을 그 `jwk-set-uri`·`issuer` 로 띄운다. app-auth(서블릿)와 app-scim(WebFlux)을 한 시험 클래스패스에 두면 웹 앱 종류 판정과 컴포넌트 스캔이 겹친다 — 시험을 어느 모듈에 둘지, 웹 앱 종류를 어떻게 고정할지는 구현이 정한다(내 판단: 겹침을 피하는 쪽이면 어느 것이든). client credentials 로 받은 토큰으로 SCIM 쓰기 하나와 관리 API 하나가 통과하고, 그 행위자가 감사·회차에 `client:<id>` 로 남는다.
- app-auth 자체: 범위 없이 요청하면 등록된 범위를 모두 받는다, 등록되지 않은 범위는 거절, 틀린 비밀 401, 수명 365일 초과 설정은 기동 멈춤, prod 검사(키·`{bcrypt}`).

### 8.3 거절 시험(두 앱)

- 토큰 없음 401, 서명이 다른 토큰 401, 만료 401, 발급자가 다른 토큰 401, 범위 부족 403 — SCIM 은 SCIM Error 본문, 관리 API 는 problem details, 둘 다 `WWW-Authenticate`.
- `force=true`·`mode=wipe` 를 `admin.sync` 만으로 부르면 403 이고 회차가 생기지 않는다. `admin.destructive` 가 있으면 202.
- 프로브는 토큰 없이 200, 헬스 세부는 `actuator` 범위가 있을 때만, `/actuator/prometheus` 는 범위 없으면 401.
- SCIM 발견도 토큰이 필요하다. `authenticationSchemes` 내용.

### 8.4 OpenFGA

- `Containers.openFga()` 를 사전 공유 키로 띄운다(한 곳). 시험 yml 에 같은 키를 둔다. raw 클라이언트를 쓰는 시험도 키를 단다.
- 키가 틀리면 OpenFGA 호출이 실패한다는 시험 하나. prod 검사 시험.

## 9. 문서 (A13)

- 루트 README: 인증 절 — 클라이언트·범위 표, 토큰 받는 `curl`, IdP 설정(Entra client credentials, Okta 붙여 넣는 토큰), Prometheus `oauth2` 스크레이프, 키 교체, prod 설정·검사, "인증이 없다" 문장 정리.
- 온보딩: 13 §1.1~§1.3 → 고침(§1.2 의 위변조 방지·외부 내보내기는 남김), 03 아키텍처(세 번째 앱), 06 SCIM(인증·발견), 07 재적재·초기화(wipe·force 의 범위), 09 관리 API(범위·정확 조회 둘·401/403 표), 10 감사 이력(행위자), 11 운영(app-auth 배포·비밀·키·https·Prometheus·OpenFGA 키를 켜는 순서), 12 장애와 복구(인가 서버가 멈췄을 때, 토큰 유출, wipe 뒤 orgCode 다시 찾기), 14 개발 가이드(시험 토큰, 로컬에서 토큰 받기), 02 용어집(범위, 행위자, 클라이언트), 15 결정 색인. 기준 커밋을 고친다.
- `docker/scim/replay.py` 등 로컬 도구가 토큰을 받아 싣게 고친다. `docker-compose.yml` 의 OpenFGA 는 사전 공유 키로 띄운다.

## 10. 바뀌는 곳 (모듈별 어림)

| 모듈 | 바뀌는 것 |
|---|---|
| `app-auth` (새로) | 인가 서버 앱, 서명 키 설정, 기록 없는 인가 저장소, 기본 범위, 설정 검사, 시험 |
| `web-security` (새로) | `SecurityWebFilterChain`, 401·403 본문, 행위자 웹 필터 |
| core | 행위자 Context 키, `AuditCause.actor`, `SyncRun.requestedBy`, 읽는 자리 둘 |
| storage-dynamodb | 감사·회차 아이템의 속성 하나씩 |
| authz-openfga | `apiToken`·검사, 시험 컨테이너 키 |
| connector-scim | `authenticationSchemes` |
| admin-api | 정확 조회 둘, 응답의 `actor`·`requestedBy` |
| app-ldap·app-scim | 의존·yml, 컨트롤러의 `admin.destructive` 판정, 예약 작업의 행위자, 시험 토큰 |
| docs | §9 |

## 11. 이 설계가 말할 수 없는 것

- **실제 IdP.** Entra 의 client credentials 와 Okta 의 붙여 넣는 토큰으로 실제 프로비저닝을 돌려 보지 않는다. 근거는 IdP 문서와 공개 자료뿐이다(조사 날짜 2026-10-28). Entra 의 범위 칸·토큰 요청 모양이 우리 토큰 엔드포인트와 맞는지는 실제로 붙여 봐야 안다.
- **회사 IdP 를 인가 서버로.** A2 로 설정만 바꾸면 되도록 했지만, 범위 이름·`sub` 의 모양이 IdP 마다 다르다. 시험하지 않는다.
- **키 교체의 실제 지연.** 리소스 서버가 모르는 `kid` 를 보고 키 목록을 다시 받는 간격은 라이브러리 기본값이다. 시험은 로컬에서만 한다.

## 12. 왜 다른 길을 안 갔나

- **리소스 서버마다 고정 API 키.** 가장 단순하지만 인가 서버가 아니고, 키마다 무기한이며(RFC 7644 §7 위반), Entra 가 새 SCIM 앱에 받지 않는 방식이다.
- **두 앱 안에 인가 서버를 넣는다.** 발급자가 앱마다 생기고, WebFlux 앱에 서블릿 기반 서버를 섞어야 한다.
- **인가 코드 + 사람 로그인.** 호출자가 모두 기계다. 사람 행위자는 운영자별 클라이언트로 얻는다. 사람 로그인은 회사 IdP 연동 때 다룬다.
- **불투명 토큰 + introspection.** 매 요청이 인가 서버에 묻게 되어 인가 서버가 모든 요청의 가용성에 묶인다. JWT 는 그 자리에서 검증한다. 대가로 토큰 하나만 무효로 할 수 없다(§3.4).
- **경로 필터에서 `force`·`wipe` 를 본다.** 쿼리 문자열을 스프링과 다르게 해석하면(`force=yes`) 우회된다. 컨트롤러가 바뀐 값으로 본다.
- **행위자를 메서드 인자로.** SCIM 쓰기 연산 여섯과 회차 시작 셋의 서명을 모두 바꾼다. Context 는 이미 두 분리 지점을 지나 감사까지 닿는다(조사 §5).
- **감사 정렬키에 행위자.** 키가 바뀌면 이력 조회와 겹친다. 속성 하나면 된다.

## 13. 검증

- **머지 전.** `test` 와 `scaleTest` 를 둘 다 돌린다. 메인 세션이 차례로 돌린다.
- §8 의 시험. 특히 진짜 인가 서버로 받은 토큰이 리소스 서버를 통과하는 시험(§8.2)과 거절 시험(§8.3).

## 14. 결과 (구현 후 기록)

구현 계획 `docs/superpowers/plans/2026-10-29-auth.md` 의 과제 11개를 과제마다 구현·리뷰로 돌리고, 브랜치 전체 리뷰 뒤 수정 한 번을 더했다. 코드의 마지막 커밋은 `8b492c6`, 온보딩 문서의 기준 커밋도 그것이다.

### 14.1 머지 전 검증

| 실행 | 기준 | 결과 |
|---|---|---|
| `./gradlew cleanTest test` 1회차 | `b5579e5`(코드 과제 끝) | 2,278건 통과, 실패 0 |
| `./gradlew cleanScaleTest scaleTest` 1회차 | `b5579e5` | 89건 통과, 16분 35초 |
| `./gradlew cleanTest test` 2회차 | `da001a3`(수정 묶음 뒤) | 2,283건 통과, 실패 0 |
| `./gradlew cleanScaleTest scaleTest` 2회차 | `da001a3` | 89건 통과, 16분 5초 |

- 감사 아이템마다 붙은 `actor` 속성은 규모 시험의 시간·결과를 바꾸지 않았다.
- 실제 jar 로 확인한 것(과제 6의 스모크): app-auth·app-scim bootJar 와 docker compose 로 토큰 발급, 401·403, 헬스 세부, `replay.py` 가 토큰을 받아 싣는 것, prod 검사(AUTH_* 없음·http 면 멈춤).

### 14.2 구현 중 판정

- **키 목록에 닿지 않으면 503 + `Retry-After: 10`(계획은 401).** 서버 쪽 일시 장애다. 401 `invalid_token` 은 멀쩡한 토큰을 버리게 하고, IdP(Entra)가 401 이 이어지면 프로비저닝을 격리할 수 있다. 판정은 예외 종류가 아니라 원인 표지(`KeySetUnavailableException`, `KeySetClient` 에서만 만든다)로 한다 — 예외 종류로 가르면 JWE 꼴로 꾸민 토큰이 키 목록이 멀쩡해도 503 을 냈다(리뷰가 재현). 키 목록 요청에는 연결 2초·전체 5초 한도를 둔다.
- **app-auth 설정 파일 이름 `auth-server*.yml`(`spring.config.name=auth-server`).** 진짜 인가 서버 시험에서 app-scim 과 한 클래스패스에 놓일 때 `application.yml` 이 부딪히기 때문이다. 바깥에 둔 `application*.yml` 은 읽히지 않는다(문서에 적음).
- **SAS 1.5.8 은 범위 없는 요청에 범위를 하나도 주지 않는다.** 등록된 범위를 모두 주게 했다(`DefaultScopes`).
- **app-auth 가 내는 끝점은 토큰·키 목록·메타데이터뿐이다.** 인가 코드·디바이스 끝점을 열어 두면 익명 요청마다 ERROR 스택이 찍혔다(리뷰가 재현). introspect·revoke 는 발급 기록이 없어 거짓 응답을 내므로 닫았다. 메타데이터에는 SAS 가 요구해 `authorization_endpoint` 가 남는다(403).
- **서명 키는 RSA 2048비트 이상.** 짧은 키는 기동은 되지만 발급이 모두 실패했다.
- **OpenFGA 키는 보이는 ASCII 만.** 줄바꿈 등이 붙으면 JDK 예외 문구에 토큰이 실려 로그·헬스로 샜다(리뷰가 실제 SDK 로 재현). 인증 거절(401/403)은 다시 시도하지 않는다.
- **app-auth 의 체인 1 CSRF 가 익명 요청마다 세션을 만들었다(브랜치 전체 리뷰가 재현).** 세션을 만들지 않게 고쳤다. 그런 요청은 이제 403 이 아니라 401 이다.
- **발급자가 https 면 `{bcrypt}` 가 아닌 비밀은 프로필과 상관없이 기동을 멈춘다.** 프로필 이름이 `prod` 가 아니면 공개된 개발 클라이언트가 열리는 길을 막는다.
- **이 슬라이드 밖의 운영 결함 하나를 함께 고쳤다.** fat jar(`java -jar`)에서 인가 모델 파일을 ForkJoinPool 스레드의 클래스 로더로 찾아 못 읽었다(커밋 ce29e28 부터). 클래스의 로더로 읽는다. 시험은 빈 TCCL 로 흉내 낸다.
- **app-auth 의 Tomcat 이 시험 클래스패스에 오면 app-scim 의 모든 `@SpringBootTest` 가 Netty 대신 Tomcat 위에서 돌았다.** 시험 전용 자동 구성으로 Netty 로 되돌리고 지키는 시험을 둔다.
- **옛 감사 줄의 `cause.actor` 는 비지 않고 `system:unknown` 이다**(§5.3 "칸을 비운다" 와 다르다 — `AuditCause` 는 비지 않는 값이라 계획 과제 1 을 따랐다). 회차의 `requestedBy` 는 칸이 없다.
- **키 교체는 인스턴스마다 늦게 먹는다(§3.4 보다 좁다).** 리소스 서버는 모르는 `kid` 의 토큰을 볼 때만 키 목록을 다시 받는다(Spring `ReactiveRemoteJWKSource`, 간격·횟수 제한 없음). 옛 토큰을 곧바로 끊으려면 app-auth 가 새 키를 낸 뒤에 앱을 다시 띄운다 — 먼저 띄우면 옛 키 목록을 다시 받는다.
- **정확 조회의 userName 길은 저장된 userName 을 소문자로 바꿔 질의와 같은 것만 남긴다.** userName 이 빈 직원은 GSI1 에 id 로 색인돼, 그 id 로 조회하면 잡히던 것을 막는다.

### 14.3 남긴 것

- **실제 `java -jar` 로 fat jar 수정을 확인하지 않았다.** 시험이 같은 조건(빈 TCCL)을 흉내 낸다.
- **모르는 `kid` 마다 키 목록을 다시 받는다.** 꾸민 토큰으로 app-auth 에 요청을 하나씩 일으킬 수 있다(1:1, `/oauth2/jwks` 는 어차피 공개). 쿨다운은 두지 않았다.
- **커서는 서명·암호화가 없다.** `admin.read` 는 이미 그 데이터를 본다.
- **작은 것.** TRACE 는 Tomcat 이 400 으로 답한다. https 확인이 두 검사에서 대소문자를 다르게 본다(둘 다 막는 쪽). 시험 두 곳이 개발 클라이언트 아이디를 적어 둔다(빠지면 크게 실패). 비 prod 프로필에 http 발급자를 쓰면 개발 클라이언트가 살아 있다(운영 리소스 서버는 https 를 요구한다).
- 실제 Entra·Okta·회사 IdP 연동은 시험하지 않았다(§11). §15 의 범위 밖 항목은 그대로다.

## 15. 범위 밖

- 사람 로그인·관리 화면·회사 IdP 연동 시험.
- 토큰 하나만 무효화, introspection.
- 요청 수 제한.
- 감사 이력의 위변조 방지·외부 내보내기(13 §1.2 의 나머지).
- mTLS, 앞단 게이트웨이 구성.
- OpenFGA 쓰기를 이 서버로만 좁히기(OIDC + OpenFGA 의 실험 기능 access control). 사전 공유 키로는 익명 차단까지다(A11).
- N5(SCIM 조직 계층의 원천) — 제품 결정이 필요하다.

## 16. 확인할 것 (구현 때 사실로 정한다)

- Spring Authorization Server 가 client credentials 요청에 범위가 없을 때 무엇을 주는가(§3.2 — 등록된 범위 전부가 아니면 그렇게 만든다).
- Boot 3.5 의 리액티브 actuator 에서 `management.endpoint.health.roles` 가 `SCOPE_actuator` 권한과 맞는가(§4.5 — 맞지 않으면 같은 결과를 내는 다른 길을 쓴다).
- OpenFGA 사전 공유 키 모드에서 `/healthz` 가 인증 없이 열리는가(§8.4 시험 컨테이너 대기 조건).
- Boot 가 SAS 를 올릴 때 사용자 저장소·폼 로그인을 만들지 않게 하는 방법(§3.1).
