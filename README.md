# organization

LDAP / SCIM 디렉터리의 조직·직원 관계를 [OpenFGA](https://openfga.dev) 튜플로 동기화하는 서버.

권한 부여 자체를 하지 않는다. 인가 판단은 애플리케이션들이 OpenFGA에 직접 묻는다. 이 서버가 하는 일은
디렉터리(누가 어느 조직 소속인가)를 읽어 OpenFGA가 이해하는 관계로 옮기고, 그 상태를 DynamoDB에
보관하는 것뿐이다.

- 설계: [docs/superpowers/specs/2026-08-14-organization-sync-design.md](docs/superpowers/specs/2026-08-14-organization-sync-design.md)
- 구현 계획: [docs/superpowers/plans/](docs/superpowers/plans/)

## 왜 이런 서버가 필요한가

조직도는 여러 시스템이 각자의 방식으로 들고 있다. 어떤 곳은 LDAP, 어떤 곳은 SCIM을 지원하는 IdP를
쓴다. 이 서버는 그 차이를 흡수해서 하위 시스템들이 "이 사람이 이 조직(과 그 상위 조직들)에 속하는가"
라는 질문 하나만 OpenFGA에 던지면 되게 만든다.

LDAP와 SCIM은 성격이 정반대라 같은 코드로 다룰 수 없다.

- **LDAP은 pull 모델이다.** 서버가 주기적으로 전체를 읽어온다. 무엇이 바뀌었는지는 LDAP 자신도
  알려주지 않는다. 그래서 이번에 읽은 전체 상태를 **직전 스냅샷과 diff**해서 델타를 직접 계산한다.
- **SCIM은 push 모델이다.** IdP가 변경 건별로 요청을 보낸다. 델타가 이미 주어진 셈이라 diff가
  필요 없다.

두 커넥터는 이 차이만 흡수하고, 그 이후의 파이프라인(`TupleDelta` → OpenFGA 쓰기 → DynamoDB 반영)은
완전히 공유한다.

## 스냅샷이 왜 있는가

OpenFGA에는 지금 어떤 튜플이 있는지 물어볼 수 있는 read API가 있지만, 이 서버는 그것을 **쓰지
않는다.** 튜플 상태의 진실의 원천은 항상 DynamoDB에 저장한 스냅샷이다. 그래서 diff는 "LDAP에서
방금 읽은 것"과 "직전 sync가 실제로 OpenFGA에 반영했다고 기록해 둔 것"을 비교해서 계산된다.

이 결정은 파이프라인 전체를 단순하게 만든다.

1. 새 델타를 **OpenFGA에 먼저 적용**한다.
2. **실제로 성공한 튜플만** 새 스냅샷으로 커밋한다. 실패한 튜플은 새 스냅샷에 들어가지 않는다.
3. 다음 sync는 그 스냅샷을 기준으로 diff를 다시 계산하므로, 실패했던 튜플이 자동으로 다시 델타에
   잡힌다.

재시도 큐도, 실패 상태를 추적하는 별도 상태머신도 필요 없는 이유가 이것이다. 대가로 **드리프트를
감지할 수 없다** — 누군가 OpenFGA를 이 서버를 거치지 않고 직접 고치면 스냅샷과 실제가 어긋나도
알 방법이 없다. 이걸 되돌리는 수단이 `rebuild`(아래 관리 API 참고)이며, `app-ldap`에만 있다.

현재상태(`DirectoryStateRepository`)와 스냅샷은 서로 다른 것을 기록한다는 점도 중요하다.
현재상태는 "LDAP/SCIM에서 읽은 사실 그대로", 스냅샷은 "OpenFGA에 실제로 반영된 것"이다. 부분
실패가 나면 둘이 갈린다.

## 인가 모델

```
type group
  relations
    define direct_member: [user]
    define child: [group]
    define member: direct_member or member from child
```

`direct_member`와 `child`를 분리해 두었기 때문에 `member`는 조직 계층을 따라 **롤업**된다.
어떤 직원이 하위 조직에만 직접 속해 있어도, 그 상위 조직들의 `member`로도 인정된다. 예를 들어
`kim`이 `DEV002`(백엔드팀)의 직접 멤버이고 `DEV002`가 `DEV001`(개발본부)의 하위 조직이면,
`Check(user:kim, member, group:DEV001)`은 `kim`을 `DEV001`에 명시적으로 넣지 않아도 참이다.
이것이 이 인가 모델이 존재하는 이유다.

**조직명은 튜플에 절대 넣지 않는다.** 조직명은 개편 때마다 바뀌지만 튜플은 그 시점의 사실을
영구히 기록하는 것이 아니라 지금 참인 관계를 표현하는 것이라, 이름이 바뀔 때마다 튜플을 다시
쓰는 것은 사고를 부른다. 튜플의 식별자는 **직원 아이디와 조직코드뿐**이다. 조직명은 DynamoDB의
현재상태에만 보관되고, 조회가 필요하면 거기서 가져온다.

이 서버 코드 어디에도 OpenFGA의 Read/Check/ListObjects 호출이 없다 — Write/Delete만 쓴다.
(테스트 코드의 `Check` 호출은 예외다: 인가 모델이 실제로 성립하는지 확인하는 용도이며, 이 규칙은
프로덕션 코드에만 적용된다.) `storeId`/`modelId`도 `authz-openfga` 밖의 코드는 다루지 않는다 —
설정에는 store 이름만 있고, 나머지는 시작 시점에 런타임에서 해석한다.

## 구조

| 모듈 | 책임 |
|---|---|
| `core` | 도메인 모델, 포트, 유스케이스, 튜플 변환·비교, 삭제 가드 |
| `storage-dynamodb` | 현재상태 / 스냅샷 / 실행이력 저장소 |
| `authz-openfga` | store 해석, 인가 모델 등록, 멱등 튜플 쓰기 |
| `connector-ldap` | groupOfNames / DIT 두 매핑 전략 |
| `connector-scim` | SCIM 2.0 엔드포인트 |
| `app-ldap` | LDAP 동기화 인스턴스 (8081) |
| `app-scim` | SCIM 수신 인스턴스 (8082) |

의존 방향은 항상 `app-*` → 어댑터(`storage-dynamodb`/`authz-openfga`/`connector-*`) → `core`다.
`core`는 스프링 컨텍스트도, 어떤 구체 어댑터도 모른다.

## 로컬 실행

```bash
docker compose up -d
./gradlew :app-ldap:bootRun
```

`docker-compose.yml`은 OpenFGA, DynamoDB Local, (수동 검증용) OpenLDAP을 띄운다. app-ldap을
띄우면 시작 시점에 OpenFGA store와 인가 모델이 자동으로 준비된다.

DynamoDB 테이블은 `dynamodb.create-table-on-startup` 이 켜져 있을 때만 만들어진다. 로컬
기본값은 `true` 이고, **실제 AWS 에서는 끄는 것을 전제로 한다** — 테이블 수명주기는 배포
도구가 관리해야 하고, 애플리케이션이 부팅할 때마다 스키마를 만들려 드는 것은 인덱스 구성이
바뀔 때 특히 위험하다(follow-ups §7).

| 서비스 | 주소 |
|---|---|
| OpenFGA | http://localhost:8080 (플레이그라운드 http://localhost:3000) |
| DynamoDB Local | http://localhost:8000 |
| OpenLDAP | ldap://localhost:1389 |
| app-ldap | http://localhost:8081 |

## 관리 API

**두 앱의 `/admin/sync`는 표면이 다르다.** `app-ldap`은 언제든 LDAP을 다시 읽어올 수 있어
전체 동기화가 성립하지만, SCIM은 push 모델이라 "전체를 다시 달라"고 말할 상대가 없다. 그래서
`app-scim`에는 `full`이 없고 재적재와 이력 조회만 있다.

**app-ldap**

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/full` | 즉시 전체 동기화 |
| `POST /admin/sync/full?force=true` | 삭제 가드를 건너뛰고 실행 |
| `POST /admin/sync/rebuild?mode=snapshot` | 직전 스냅샷으로 전부 지운 뒤 재적재 |
| `POST /admin/sync/rebuild?mode=store` | store를 재생성한 뒤 재적재 (재적재까지 인가 질의 실패) |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 |

**app-scim**

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/rebuild?mode=tuples` | store를 재생성하고 **현재상태(DynamoDB)가 요구하는 튜플을 전부 다시 쓴다.** 조직도는 건드리지 않는다 |
| `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` | store와 **조직도까지 전부 지운다.** 되돌릴 수 없다 — 아래 경고 참고 |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 (재적재 + 하루 1회 아카이빙) |

`GET /actuator/health`는 두 앱 공통이며 DynamoDB / OpenFGA 연결 상태를 포함한다.
app-ldap 은 LDAP 연결까지 함께 본다 — 이 앱의 파이프라인이 거기서 시작하기 때문이다.

### app-scim 재적재를 부를 때 알아야 할 것

**재적재가 도는 동안 SCIM 변경 요청은 503이다.** IdP는 503을 재시도 신호로 보므로
프로비저닝이 유실되지 않고, 재시도 시점에는 재적재가 끝난 상태 위에서 처리된다. 조회
(SCIM GET, 관리자 조회 API)는 그대로 통과한다 — 무슨 일이 벌어지는지 들여다보는 것이 그
순간 가장 필요한 일이기 때문이다. 재적재끼리 겹치면 두 번째 요청이 409로 거절된다.
(두 경로 모두 같은 전역 락을 쓰기 때문이다 — 락을 못 잡았을 때 SCIM 쓰기와 재적재가 서로
다르게 반응하는 이유는 아래 "app-scim 여러 대 띄우기" 절 참고.)

**인가 공백이 생긴다.** store를 재생성하는 순간부터 재적재가 끝날 때까지 **모든 인가 질의가
false**다. 조직 규모에 비례해 길어진다. 그동안 관리자 조회 API를 열어보면 모든 행이
`shouldHaveAccess: true` / `openFgaCheck: false`로 보이는데, 이는 실제 어긋남이 아니라
재적재 중이라는 뜻이다.

**중간에 실패하면 권한이 없는 채로 남는다.** store를 이미 비운 뒤 쓰기가 실패하면 그 상태로
끝나고 `SyncRun`에 `FAILED`로 남는다. 자동 롤백은 없다 — 되돌릴 이전 상태가 이미 지워졌기
때문이다. 운영자가 다시 실행해야 한다.

**요청이 그동안 매달려 있다.** 동기 호출이라 재적재가 끝나야 응답이 온다. 앞단 프록시의
타임아웃이 먼저 날 수 있는데, 그래도 재적재 자체는 계속 돌고 `GET /admin/sync/runs`로
결과를 확인할 수 있다.

### ⚠️ `mode=wipe`는 되돌릴 수 없다

`wipe`는 OpenFGA뿐 아니라 **DynamoDB의 직원·조직을 전부 지운다.** SCIM 배포에서 DynamoDB는
조직도의 **유일한 사본**이다. 스냅샷에는 튜플의 식별자만 있어 이름·이메일·계정명·재직 여부를
복원할 수 없다.

**실행 뒤 반드시 IdP 콘솔에서 전체 재프로비저닝을 걸어야 조직도가 돌아온다**(Okta의 Force
Sync, Entra의 프로비저닝 재시작). 그 절차는 이 API 밖에 있고, 우리가 시작할 수 없다. 잊거나
실패하면 조직도가 빈 채로 남는다.

그래서 `confirm`에 DynamoDB 테이블명을 그대로 적어야 실행된다. 불리언 플래그는 손가락이
미끄러지면 눌리지만, 테이블명은 관리자가 자기가 무엇을 지우는지 찾아보게 만든다.

지우는 순서는 **OpenFGA 먼저, DynamoDB 나중**이다. 중간에 실패하면 조직도는 온전하고 권한만
없는 상태로 남아 `mode=tuples` 한 번이면 복구된다. 순서를 뒤집으면 조직도가 사라진 채 낡은
권한만 살아남는다 — 지워진 사람들의 권한만 남는 셈이라 최악이다.

**감사 이력은 지우지 않는다.** 스냅샷과 실행 이력은 그대로 남는다. 사고 뒤에 무슨 일이
있었는지 볼 유일한 기록이기 때문이다.

`sync.cron`으로 지정한 주기마다 전체 동기화가 자동으로도 돈다. LDAP이 이상 응답(예: 필터 오류로
0건)을 주면 삭제 가드가 `ABORTED`로 막고, `force=true`로 사람이 확인한 뒤 우회할 수 있다.

### 조회 API

`admin-api` 모듈이 두 앱(`app-ldap`, `app-scim`) 모두에 공유 코드로 배선돼 있다. 읽기 전용이고
현재상태(DynamoDB)와 OpenFGA의 실제 판정을 나란히 보여준다.

| 요청 | 설명 |
|---|---|
| `GET /admin/employees?userName=` | 계정명 접두사로 직원 검색 |
| `GET /admin/employees?displayName=` | 표시명 접두사로 직원 검색 |
| `GET /admin/employees/{employeeId}` | 직원 상세 — 직속 소속과 상위 계층 전부, 각 줄에 실제 판정 포함 |
| `GET /admin/organizations?displayName=` | 표시명 접두사로 조직 검색 |
| `GET /admin/organizations/{orgCode}` | 조직 상세 — 상위 계층, 직속 하위 조직, 직속 소속 직원 첫 페이지 |
| `GET /admin/organizations/{orgCode}/members` | 조직의 직속 소속 직원 목록 (커서 페이징) |

검색은 `?cursor=`로 이어 읽고, `?limit=`(기본 20, 최대 100)로 페이지 크기를 조절한다.

**식별자 셋.** 직원에는 이름이 다른 세 값이 붙는다.

- `employeeId` — 정규화된 값. 실제로 OpenFGA 튜플(`user:{employeeId}`)에 실리는 값
- `userName` — IdP/LDAP이 보낸 원본 계정명. 정규화 전 형태
- `displayName` — 사람이 읽는 이름

예를 들어 SCIM이 `userName: "gd.hong"`, `displayName: "홍길동"`으로 사용자를 보내면,
`employeeId`도 `gd.hong`으로 정규화돼 튜플은 `user:gd.hong`이 된다. `/admin/employees/gd.hong`
(경로에는 `employeeId`)로 상세를 조회하면 `userName`과 `displayName`을 함께 볼 수 있다.

**검색은 접두사만 지원한다.** `displayName=홍`은 "홍"으로 시작하는 이름을 찾을 뿐, 부분일치나
전문 검색은 지원하지 않는다. 조직코드(`orgCode`) 자체의 접두사 검색도 없다 — 조직은
표시명으로만 검색하고, 정확한 코드를 안다면 `/admin/organizations/{orgCode}`로 바로 조회한다.

**`shouldHaveAccess`와 `openFgaCheck`가 갈리면.** 직원 상세(`paths`)의 각 줄은
`shouldHaveAccess`(현재상태가 요구하는 값)와 `openFgaCheck`(OpenFGA에 실제로 Check해 받은
판정)를 함께 싣는다. 조직 멤버 목록의 줄에는 `shouldHaveAccess`가 없고 `active`와
`openFgaCheck`가 있다 — 직속 멤버로 이미 걸러진 목록이라 파생값이 곧 `active`다. 이 둘이
다르면 어긋난 것이다.

어긋남은 SCIM/LDAP 쓰기 경로의 동시성 결함 등으로 상태와 실제 인가 튜플이 갈린 것이다 —
이 API는 그것을 **감지**할 뿐 고치지 않는다. 대응은 배포마다 다르다.

- **app-ldap** — `POST /admin/sync/rebuild`로 재적재해 튜플을 상태와 다시 맞춘다. 다음
  `sync.cron` 주기의 전체 동기화도 같은 일을 한다.
- **app-scim** — `POST /admin/sync/rebuild?mode=tuples`로 재적재한다. store를 비우고
  현재상태가 요구하는 튜플을 전부 다시 쓰므로, 튜플 쪽 어긋남은 무엇이든 사라진다.
  조직도는 건드리지 않는다.

**조직도 자체가 틀렸다면 재적재로 고쳐지지 않는다.** `mode=tuples`는 "상태가 진실"이라는
전제로 돌기 때문에, 상태가 틀렸으면 틀린 채로 다시 밀 뿐이다. 그 경우는 IdP 쪽에서 다시
push하게 하거나, 최후 수단으로 `mode=wipe` 뒤 전체 재프로비저닝을 해야 한다.

`openFgaCheck`가 `null`이면 Check 호출 자체가 실패한 것이지 판정이 false라는 뜻이 아니다 —
이때도 응답은 200이고 해당 칸만 비어 있다.

**순환은 드리프트가 아니다.** 저장된 계층에 순환이 있으면 `TupleMapper`가 간선 하나를 일부러
버리므로 파생값과 실제가 갈린다. 그 줄에는 `"cycle": true`가 붙고, 카운터도
`authz_drift_detected`가 아니라 `authz_cycle_divergence`로 간다. 재적재해도 같은 간선이 또
버려지므로 재적재의 근거가 될 수 없다 — 고쳐야 할 것은 조직도 쪽의 순환이다.

**인증이 없다.** `/admin/sync`와 마찬가지로 `/admin/employees`, `/admin/organizations`도
누구나 호출할 수 있게 열려 있다. 조직도와 소속 정보를 그대로 노출하므로, 실제 운영에
투입하기 전에 반드시 앞단에서 보호해야 한다.

## SCIM

SCIM은 push 모델이라 LDAP처럼 전체를 읽어 diff하지 않는다. IdP가 보내는 요청은 항상 리소스
하나의 변경이므로, 그 **영향 범위만 담은 최소 스냅샷**을 변경 전후로 각각 만들어 튜플로 바꾼 뒤
그 둘을 diff한다.

지원 엔드포인트:

| 리소스 | POST | GET (단건) | PUT | PATCH | DELETE |
|---|---|---|---|---|---|
| `/scim/v2/Users` | O | O | O | O | O |
| `/scim/v2/Groups` | O | O | O | O | O |
| `/scim/v2/ServiceProviderConfig` | - | O | - | - | - |

**목록 조회(`GET /Users`, `GET /Groups`)와 필터는 지원하지 않는다.**

지원하는 PATCH는 다음이 전부다.

| 대상 | `path` | 지원 `op` |
|---|---|---|
| Group | `members` | `add` / `remove`(전체 비움) / `replace` |
| Group | `members[value eq "..."]` | `remove` |
| Group | `displayName` | `replace` / `add` |
| Group | (path 없음) | `replace` / `add` — 본문을 부분 리소스로 보고 `displayName`·`members`만 병합 |
| User | `active` / `displayName` / `userName` | `replace` / `add` |
| User | (path 없음) | `replace` / `add` — `active`·`displayName`·`userName`만 병합 |

그 외 path는 조용히 무시하지 않고 `invalidPath`로 400을 돌려준다 — IdP가 실제로는 반영되지
않은 변경을 반영됐다고 오해하면 안 되기 때문이다. `path`는 대소문자를 구분한다.

`members[].value`는 `userName`·`externalId`과 같은 규칙(`IdNormalizer`)으로 정규화한다.
`members[].type`은 RFC 7643에서 선택 필드라 없을 수 있는데, 그때는 User로 단정하지 않고
현재상태에서 조직 → 직원 순으로 찾아 판정한다 — 조직코드와 직원 아이디는 네임스페이스가
달라 겹칠 수 있어서, 잘못 단정하면 IdP가 조직을 중첩하려던 요청이 엉뚱한 직원 소속 튜플이
된다.

SCIM push 요청은 `SyncRun`에 기록하지 않는다. 요청 단위로 남기면 이력이 금방 폭증한다. 이력으로
남는 것은 하루 1회 도는 아카이빙 배치(`trigger=ARCHIVE`)뿐이다.

실행:

```bash
./gradlew :app-scim:bootRun
```

포트는 8082다. **LDAP 인스턴스와 같은 DynamoDB 테이블·OpenFGA store를 동시에 쓰지 않는다** —
한 조직도는 하나의 소스로만 동기화한다는 것이 이 서버의 전제다. 그래서 app-scim의 기본값은
app-ldap과 겹치지 않게 `dynamodb.table-name: organization-scim`, `openfga.store-name:
organization-scim`으로 잡혀 있다. 다른 이름을 쓰려면 설정으로 덮어쓰되, app-ldap이 쓰는
테이블·store와는 항상 다르게 유지해야 한다.

### app-scim 여러 대 띄우기(동시성 제어)

**`app-scim`은 여러 인스턴스를 액티브-액티브로 띄울 수 있다.** IdP가 여러 인스턴스로 요청을
분산해도 안전하도록, SCIM 쓰기 하나(`upsertUser`/`upsertGroup`/`removeUser`/`removeGroup`)와
재적재(`POST /admin/sync/rebuild`)는 **같은 DynamoDB 조건부 쓰기 전역 락**을 잡은 뒤에만
진행한다. 인스턴스가 몇 대든, 그리고 그 인스턴스가 SCIM 쓰기든 재적재든, 서로 겹치지 않고
직렬화된다 — 인메모리 락(예전의 `MutationGate`)은 인스턴스 하나 안에서만 유효해 여러 대를
띄우는 순간 조용히 뚫렸는데, 이 락은 저장소를 공유하므로 그렇지 않다.

**락을 못 잡았을 때의 동작은 두 경로가 다르다 — 의도적인 비대칭이다.**

| | 락을 못 잡았을 때 |
|---|---|
| SCIM 쓰기 | `lock-acquire-timeout` 동안 짧게 재시도한 뒤에도 못 잡으면 503 |
| 재적재 | 재시도 없이 즉시 409 |

SCIM 쓰기는 기계(IdP)가 자주 보내고 밀리초 단위로 짧게 쥐는 락과 경합하므로, 잠깐 기다려보는
편이 IdP에게 불필요한 503 재시도를 덜 시킨다. 재적재는 사람이 실행하고 드물며, 무엇보다
**되돌릴 수 없는 파괴적 작업**(`resetStore()`)이라 — 무엇이 돌고 있는지 모른 채 뒤에서
조용히 대기하는 대신 즉시 409로 "지금 다른 작업이 돈다, 확인하고 다시 실행하라"고 알리는
편이 낫다. 이 비대칭은 실수가 아니라 결정이다.

락 관련 설정 세 개(`dynamodb` 아래):

| 설정 | 의미 |
|---|---|
| `dynamodb.lock-ttl` (기본 30초) | 락 리스 길이. SCIM 쓰기 p99보다 한참 길어야 한다 — 짧으면 아직 일하는 중인데 만료돼 다른 인스턴스가 가져간다. 두 경로 모두에 적용된다 |
| `dynamodb.lock-acquire-timeout` (기본 3초) | **SCIM 쓰기 경로에만 적용된다.** 락 획득 재시도 대기 한도 — 넘으면 503을 돌려주고 IdP가 재시도한다(재시도 시점에는 락이 풀린 상태 위에서 처리된다). 재적재는 이 설정을 전혀 보지 않는다 — 재시도 자체가 없어 즉시 409다 |
| `dynamodb.lock-renew-interval` (기본 10초) | 재적재처럼 오래 쥐는 작업이 리스를 갱신하는 주기. TTL보다 충분히 짧아야 한다 |

이 락은 완벽한 상호 배제를 보장하지 않는다(반납 자체의 실패, 구독 취소 등 좁은 틈이 있다) —
그래서 다음의 어긋남 지표가 따로 있다.

**`scim.drift.detected`(Counter, 태그 `kind=extra|missing`)** — SCIM 쓰기 경로는 델타를
계산할 때 이미 OpenFGA에 `Check`를 던져 **실제 있는 튜플**을 얻는다. 여기에 상태(DynamoDB)가
요구하는 **있어야 할 튜플**을 나란히 두면, 둘이 다른 것 자체가 어긋남이다 — 별도 스캔 없이
쓰기 경로가 지나가면서 알려준다. `kind=extra`는 있어선 안 될 튜플(예: 퇴사자의 잔여 권한),
`kind=missing`은 있어야 하는데 빠진 튜플이다.

이 값이 계속 오르면(0이 아니면) **`POST /admin/sync/rebuild?mode=tuples`로 재적재를 실행하라**는
신호다 — 다만 이 지표는 "누군가 다시 건드린 리소스"에서만 드러난다. 아무도 건드리지 않는
어긋남까지 잡는 주기적 대조는 아직 없다(아래 follow-ups 참고).

## 테스트

```bash
./gradlew test
```

Docker가 필요하다. DynamoDB Local과 OpenFGA는 Testcontainers로, LDAP은 UnboundID 임베디드
서버로 띄운다. `app-ldap`의 `LdapSyncEndToEndTest`는 이 셋을 모두 띄운 뒤 관리 API를 통해서만
시스템을 구동해, LDAP → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간이 실제로 이어지는지 확인한다 —
개별 모듈 단위 테스트가 전부 통과해도 결선이 틀리면 아무것도 동작하지 않기 때문이다.
`app-scim`의 `ScimEndToEndTest`도 같은 방식으로, SCIM 요청을 HTTP로 실제 보내 롤업·비활성화·
조직 삭제·아카이빙까지 순서에 의존하는 시나리오로 확인한다. `app-scim`의
`ScimDriftHealingEndToEndTest`는 경합이 남겼을 잔여 튜플(퇴사자의 잔여 권한)을 OpenFGA에
직접 심어 두고, 그 다음 SCIM 쓰기 한 번이 그 튜플을 실제로 걷어내는지 확인한다 — 타이밍에
기대 경합 자체를 재현하는 대신 경합이 남길 결과를 직접 심어 결정적으로 만든다. `app-scim`의
`AdminQueryEndToEndTest`는 SCIM으로 만든 데이터를 조회 API로 검증하고, OpenFGA 튜플을 직접
지워 `shouldHaveAccess`와 `openFgaCheck`가 실제로 갈리는지까지 확인한다 — 조회 API가 존재하는
이유 그 자체다. `app-ldap`의 `AdminQuerySmokeTest`는 같은 공유 모듈이 app-ldap 컨텍스트에서도
자동설정으로 잡히는지만 확인한다.

## 요구 버전

**OpenFGA 서버 v1.10.0 이상**이어야 한다. `on_duplicate` / `on_missing` 멱등 옵션이 그
버전부터 제공되며, 이것이 없으면 재적재와 재실행이 배치 단위로 통째로 실패한다.
