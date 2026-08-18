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
알 방법이 없다. 이걸 되돌리는 수단이 `rebuild`(아래 관리 API 참고)다.

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
띄우면 시작 시점에 OpenFGA store와 인가 모델, DynamoDB 테이블이 자동으로 준비된다.

| 서비스 | 주소 |
|---|---|
| OpenFGA | http://localhost:8080 (플레이그라운드 http://localhost:3000) |
| DynamoDB Local | http://localhost:8000 |
| OpenLDAP | ldap://localhost:1389 |
| app-ldap | http://localhost:8081 |

## 관리 API

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/full` | 즉시 전체 동기화 |
| `POST /admin/sync/full?force=true` | 삭제 가드를 건너뛰고 실행 |
| `POST /admin/sync/rebuild?mode=snapshot` | 직전 스냅샷으로 전부 지운 뒤 재적재 |
| `POST /admin/sync/rebuild?mode=store` | store를 재생성한 뒤 재적재 (재적재까지 인가 질의 실패) |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 |
| `GET /actuator/health` | DynamoDB / OpenFGA 연결 상태 포함 헬스체크 |

`sync.cron`으로 지정한 주기마다 전체 동기화가 자동으로도 돈다. LDAP이 이상 응답(예: 필터 오류로
0건)을 주면 삭제 가드가 `ABORTED`로 막고, `force=true`로 사람이 확인한 뒤 우회할 수 있다.

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

PATCH는 `members`의 `add`/`remove`/`replace`와 `members[value eq "..."]` 필터 패턴 하나만
지원한다. 그 외 path는 조용히 무시하지 않고 `invalidPath`로 400을 돌려준다 — IdP가 실제로는
반영되지 않은 변경을 반영됐다고 오해하면 안 되기 때문이다.

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

## 테스트

```bash
./gradlew test
```

Docker가 필요하다. DynamoDB Local과 OpenFGA는 Testcontainers로, LDAP은 UnboundID 임베디드
서버로 띄운다. `app-ldap`의 `LdapSyncEndToEndTest`는 이 셋을 모두 띄운 뒤 관리 API를 통해서만
시스템을 구동해, LDAP → 도메인 → 튜플 → OpenFGA/DynamoDB 전 구간이 실제로 이어지는지 확인한다 —
개별 모듈 단위 테스트가 전부 통과해도 결선이 틀리면 아무것도 동작하지 않기 때문이다.
`app-scim`의 `ScimEndToEndTest`도 같은 방식으로, SCIM 요청을 HTTP로 실제 보내 롤업·비활성화·
조직 삭제·아카이빙까지 순서에 의존하는 시나리오로 확인한다.

## 요구 버전

**OpenFGA 서버 v1.10.0 이상**이어야 한다. `on_duplicate` / `on_missing` 멱등 옵션이 그
버전부터 제공되며, 이것이 없으면 재적재와 재실행이 배치 단위로 통째로 실패한다.
