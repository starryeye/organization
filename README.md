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

OpenFGA에는 지금 어떤 튜플이 있는지 물어볼 수 있는 read API가 있지만, 이 서버는 평소에 그것을 **쓰지
않는다** — 재적재의 장부 훑기와, 지난 회차가 기록 전에 멈춘 뒤 첫 동기화(아래 "재적재·수동 동기화")만 예외다. 동기화의 diff는 "LDAP에서
방금 읽은 것"과 "직전 sync가 실제로 OpenFGA에 반영했다고 기록해 둔 것"(스냅샷)을 비교해서 계산된다.

이 결정은 파이프라인 전체를 단순하게 만든다.

1. 새 델타를 **OpenFGA에 먼저 적용**한다.
2. **실제로 성공한 튜플만** 새 스냅샷으로 커밋한다. 실패한 튜플은 새 스냅샷에 들어가지 않는다.
3. 다음 sync는 그 스냅샷을 기준으로 diff를 다시 계산하므로, 실패했던 튜플이 자동으로 다시 델타에
   잡힌다.

재시도 큐도, 실패 상태를 추적하는 별도 상태머신도 필요 없는 이유가 이것이다. 대가로 **드리프트를
감지할 수 없다** — 누군가 OpenFGA를 이 서버를 거치지 않고 직접 고치면 스냅샷과 실제가 어긋나도
알 방법이 없다. 이걸 되돌리는 수단이 `rebuild`(아래 관리 API 참고)다 — 재적재는 장부를 Read로 훑어 조직도가
요구하지 않는 줄을 지우므로, 스냅샷에 없는 찌꺼기까지 치운다.

현재상태(`DirectoryStateRepository`)와 스냅샷은 서로 다른 것을 기록한다는 점도 중요하다.
현재상태는 "LDAP/SCIM에서 읽은 사실 그대로", 스냅샷은 "OpenFGA에 실제로 반영된 것"이다. 부분
실패가 나면 둘이 갈린다.

**최신 스냅샷은 기간과 상관없이 남는다.** 스냅샷은 `dynamodb.snapshot-retention-days`(기본 7일) 동안 보관하고, 매일 정리 작업이
지난 것을 지운다 — 다만 **최신 포인터가 가리키는 스냅샷은 건너뛴다.** 새 스냅샷은 변경이 있고 끝까지 간 회차만 만들므로, 삭제 가드
중단이나 LDAP 장애가 보존 기간보다 길게 이어져도 비교 기준이 사라지지 않는다. 스냅샷에는 테이블 TTL 을 쓰지 않는다.
기준선을 온전히 읽지 못하면(포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 다르면) 빈 기준선으로 넘어가지 않고 그 회차를
FAILED 로 끝낸다 — `POST /admin/sync/rebuild` 로 복구한다 — 재적재는 기준선 스냅샷을 읽지 않는다.

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

튜플을 다루는 OpenFGA 호출은 세 가지뿐이다. 쓰기는 Write(쓰기·지우기), 판단은 Check·BatchCheck(점 조회), 그리고
**Read(목록 읽기)는 장부 훑기에서만** 쓴다(`RelationTupleScanner`) — 재적재와, 지난 회차가 기록 전에 멈춘 뒤 첫 동기화다. ListObjects는 쓰지 않는다.
`storeId`/`modelId`도 `authz-openfga` 밖의 코드는 다루지 않는다 — 설정에는 store 이름만 있고, 나머지는
런타임에 해석한다.

**store(장부) 번호는 한 번 만들어지면 바뀌지 않는다.** 재적재도 store를 지우고 다시 만들지 않는다. 다른
앱은 store를 이름으로 찾아도, 번호를 설정에 적어 두어도 된다. 빈 OpenFGA에 인스턴스 둘이 동시에 처음 떠 같은
이름 store를 둘 만들면, 둘 다 먼저 만들어진 쪽을 쓰고 늦게 만든 쪽이 자기 것을 지운다. 오래전부터 같은
이름이 둘이면 어느 쪽이 진짜인지 모르므로 멈추고 알린다.

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

**이 브랜치는 키 레이아웃을 바꾼다** — 멤버십 아이템이 GSI1 키를 잃고, 대신 멤버 쪽 파티션에
`BELONGS_TO#` 줄이 새로 생긴다(`2026-09-16-strong-membership-lookup-design.md`). 그래서 기존에
쓰던 테이블은 반드시 다시 만들어야 한다 — 비우지 않은 테이블을 그대로 쓰면, 이미 있던 멤버십은
전부 `BELONGS_TO#` 줄이 없는 채로 남고, 그 멤버에 대한 역참조(`findGroupIdsContaining`)는 빈
결과만 돌려주며, 그 상태에서 일어나는 삭제는 조용히 권한을 남긴다.

**S-1(SCIM 목록·필터)도 키를 바꾼다** — GSI1 정렬키가 소문자가 되고(`userName`·조직명을 대소문자 없이 찾기 위해)
`externalId` 로 찾는 GSI3 가 생기고, 테이블 TTL(`expiresAt`)이 켜진다. 이 TTL 은 책갈피 전용이 아니다 —
페이지 책갈피뿐 아니라 동기화 실행 이력·쓰기 락 아이템도 함께 만료시킨다(튜플 스냅샷은 TTL 을 쓰지 않는다 — 위 "스냅샷이 왜 있는가").
기존 테이블은 다시 만들어야 한다.

직접 만든 AWS 테이블이라면 다음을 갖춰야 한다: GSI1(파티션키 `GSI1PK`, 정렬키 `GSI1SK`, 프로젝션 `ALL`),
GSI2(파티션키 `GSI1PK`, 정렬키 `displayName`, 프로젝션 `INCLUDE` — `userName`·`active`), GSI3(파티션키
`externalId`, 정렬키 `PK`, 프로젝션 `KEYS_ONLY`), 그리고 `expiresAt` 속성에 켠 TTL.

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
| `POST /admin/sync/full` | 전체 동기화를 건다 — 202 + 실행 기록(`runId`, `RUNNING`) |
| `POST /admin/sync/full?force=true` | 삭제 가드를 건너뛰고 건다 |
| `POST /admin/sync/rebuild` | 재적재를 건다 — LDAP을 다시 읽어 장부와 현재상태를 맞춘다(아래 "재적재·수동 동기화") — 가드에 걸리면 `?force=true` |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 |
| `GET /admin/sync/runs/{runId}` | 실행 기록 하나 — 건 작업의 결과를 여기서 본다. 없으면 404 |

**app-scim**

| 요청 | 설명 |
|---|---|
| `POST /admin/sync/rebuild?mode=tuples` | 재적재를 건다 — **현재상태(DynamoDB)가 요구하는 튜플을 전부 쓰고, 장부를 훑어 요구하지 않는 줄을 지운다.** 조직도는 건드리지 않는다 |
| `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` | 장부를 비운 뒤 **조직도까지 전부 지운다.** 되돌릴 수 없다 — 아래 경고 참고 |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 (재적재 + 하루 1회 아카이빙) |
| `GET /admin/sync/runs/{runId}` | 실행 기록 하나. 없으면 404 |

`GET /actuator/health`는 두 앱 공통이며 DynamoDB / OpenFGA 연결 상태를 포함한다.
app-ldap 은 LDAP 연결까지 함께 본다 — 이 앱의 파이프라인이 거기서 시작하기 때문이다.

### 재적재·수동 동기화 (두 앱 공통)

**요청은 곧바로 답하고 작업은 따로 돈다.** `POST /admin/sync/rebuild`(두 앱)와 `POST /admin/sync/full`(app-ldap)은
겹치는 작업이 없으면 실행 기록을 열고 **202 Accepted**와 그 기록(`runId`, `status: RUNNING`)을 곧바로 돌려준다.
결과는 `GET /admin/sync/runs/{runId}`로 본다 — `status`가 `RUNNING`에서 `SUCCEEDED`·`PARTIAL`·`FAILED`·`ABORTED`(전체
동기화의 삭제 가드) 중 하나로 바뀐다. 작업은 앱 안에서 요청과 따로 돌아, 앞단 프록시가 연결을 끊어도 멈추지 않는다. 겹치면 곧바로 **409**다 —
두 앱 모두 DynamoDB 작업 락 하나로 클러스터 전체에서 한 번에 하나만 돈다(아래 "여러 대 띄우기").

**재적재는 장부를 버리지 않는다.** 순서는 이렇다.

1. 있어야 할 줄을 먼저 다 읽는다(app-ldap은 LDAP 전체, app-scim은 DynamoDB 현재상태). 여기서 실패하면 장부에
   아무것도 하지 않고 `FAILED`다.
2. 있어야 할 줄을 전부 쓴다. 이미 있는 줄은 OpenFGA가 무시한다.
3. 장부를 Read로 훑어 있어야 할 줄에 없는 것을 지운다 — 스냅샷에 없는 찌꺼기(중단으로 못 지운 줄, 직접 써 넣은
   줄)까지 지운다.
4. 새 스냅샷에는 장부에 실제로 있다고 볼 줄(쓴 줄 + 지우지 못한 줄)을 담는다. 지우지 못한 줄은 다음 동기화가 다시 지운다.

그래서 **인가 공백이 없다** — 재적재 동안에도 권한 질의는 정상으로 답하고, **장부 번호(storeId)도 바뀌지 않는다.**
전에는 store를 지우고 다시 만들어 그동안 모든 질의가 false였고 번호가 바뀌었다. 장부는 이 서버만 쓴다는 전제다 —
다른 앱이 이 장부에 직접 쓴 줄은 재적재가 지운다.

**기한이 있다.** `sync.job-timeout`(기본 30분, 두 앱)을 넘기면 남은 일을 멈추고 `FAILED`("기한 초과 — 30분")로
기록한 뒤 락·가드를 푼다. OpenFGA 쓰기 배치가 **3번 연달아 한 줄도 반영되지 못하면**(배치마다 재시도한 뒤에도) 남은 배치를 보내지
않고 `FAILED`로 끝낸다 — OpenFGA가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 쥐지 않는다. 드문 실패 한두
건은 지금처럼 `PARTIAL`이다. 멈춰도 이미 나간 쓰기는 되돌리지 않는다. 차단기로 멈추면 그때까지 나간 쓰기로 새 스냅샷을
남긴 뒤 `FAILED`로 기록한다(LDAP 기준선이 장부를 따라간다). 기한·서버 종료로 멈추면(재적재가 쓰기 단계에서 차단기로 멈춰도)
새 스냅샷을 남기지 않는다 — 그 사이 조직도가 바뀌어 기준선이 장부와 어긋나면 재적재로 맞춘다.

OpenFGA가 요청 내용을 거절하면(400 — 없는 타입, 너무 긴 아이디 등) 다시 보내도 같으므로 재시도하지 않는다. 대신 그 배치를 반씩 쪼개
다시 보내 나쁜 줄만 실패로 남기고 나머지는 반영한다(나쁜 줄 하나가 100줄을 끌고 가지 않는다).

**서버가 내려가면** 도는 작업을 멈추고 `FAILED`("서버 종료로 중단")로 기록한 뒤 락을 푼다(최대 10초 기다린다).
강제로 죽으면(kill -9, 정전) 그 기록은 `RUNNING`으로 남고 락은 TTL(30초) 뒤 풀린다. 다음에 락을 잡은 작업이 시작하면서 그 기록을
`FAILED`("비정상 종료로 중단")로 닫는다 — 락을 쥐었으니 남은 `RUNNING`은 죽은 작업이다(SCIM 아카이빙 기록은 건드리지 않는다).
닫기는 아직 RUNNING 일 때만 한다 — 방금 끝난 실행의 결과를 덮어쓰지 않는다.

**app-ldap 재적재는 모드가 하나다.** 옛 `mode=snapshot`·`mode=store`를 합쳤다 — 위 방식이 스냅샷에 없는 줄까지
치우면서 인가 공백도 없다. `mode`를 주면 400이다. 기준선 스냅샷이 깨졌어도 재적재는 스냅샷을 읽지 않으므로 이것으로
복구한다.

**기록 전에 멈춘 동기화는 다음 회차가 훑어 맞춘다.** app-ldap 동기화는 OpenFGA에 쓰기 직전 스냅샷 포인터에 "기록 중" 표시를 남기고,
스냅샷 저장이 그것을 지운다. 표시가 남아 있으면(저장 실패·강제 종료·기한 초과·서버 종료·재적재 중단) 지난 회차가 쓴 뒤·기록 전에 멈춘 것이라
스냅샷을 믿을 수 없다. 그래서 다음 동기화는 스냅샷 비교 대신 재적재와 같은 청소(있어야 할 줄을 쓰고, 장부를 훑어 나머지를 지움)를 한 번
하고, 실행 기록에 "기준선 의심(지난 회차가 기록 전에 멈춤) — 장부를 훑어 맞춤"을 남긴다. 그 사이 되돌려진 사람의 권한이 영원히 남거나 빠지는 일을 막는다.

**삭제 가드는 훑어 맞추기와 재적재에도 걸린다.** 지울 줄이 훑은 장부의 30%(`sync.deletion-guard`)를 넘으면 지우지 않고 `ABORTED`다 —
LDAP이 설정 실수로 0명을 돌려주면 장부 전체가 지워지던 것을 막는다. 사람이 확인한 뒤 `POST /admin/sync/full?force=true` 또는
`POST /admin/sync/rebuild?force=true`로 넘긴다. 훑어 맞추기가 가드에 걸리면 "기록 중" 표시가 남아 다음 회차도 다시 확인한다.
app-scim `mode=tuples`는 조직도가 비었는데 장부에 줄이 있으면 지우지 않고 `FAILED`다("조직도가 비어 있다 — 장부를 비우려면 mode=wipe").

### app-scim 재적재를 부를 때 알아야 할 것

**재적재가 도는 동안 SCIM 변경 요청은 503이다.** IdP는 503을 재시도 신호로 보므로
프로비저닝이 유실되지 않고, 재시도 시점에는 재적재가 끝난 상태 위에서 처리된다. 조회
(SCIM GET, 관리자 조회 API)는 그대로 통과한다 — 무슨 일이 벌어지는지 들여다보는 것이 그
순간 가장 필요한 일이기 때문이다. 재적재끼리 겹치면 두 번째 요청이 409로 거절된다.
(두 경로 모두 같은 전역 락을 쓰기 때문이다 — 락을 못 잡았을 때 SCIM 쓰기와 재적재가 서로
다르게 반응하는 이유는 아래 "app-scim 여러 대 띄우기" 절 참고.) 쓰기를 막는 이유는 장부 훑기가 도중에 들어온
정당한 줄을 '있어야 할 줄에 없는 줄'로 잘못 지우지 않게 하려는 것이다.

### ⚠️ `mode=wipe`는 되돌릴 수 없다

`wipe`는 OpenFGA뿐 아니라 **DynamoDB의 직원·조직을 전부 지운다.** SCIM 배포에서 DynamoDB는
조직도의 **유일한 사본**이다. 스냅샷에는 튜플의 식별자만 있어 이름·이메일·계정명·재직 여부를
복원할 수 없다.

**실행 뒤 반드시 IdP 콘솔에서 전체 재프로비저닝을 걸어야 조직도가 돌아온다**(Okta의 Force
Sync, Entra의 프로비저닝 재시작). 그 절차는 이 API 밖에 있고, 우리가 시작할 수 없다. 잊거나
실패하면 조직도가 빈 채로 남는다.

그래서 `confirm`에 DynamoDB 테이블명을 그대로 적어야 실행된다. 불리언 플래그는 손가락이
미끄러지면 눌리지만, 테이블명은 관리자가 자기가 무엇을 지우는지 찾아보게 만든다.

지우는 순서는 **장부 먼저, 조직도 나중**이다. 장부 청소가 한 줄이라도 실패하면 조직도를 건드리지 않고
`FAILED`로 끝난다 — 다시 실행하면 남은 줄부터 지운다. 순서를 뒤집으면 조직도가 사라진 채 낡은 권한만
살아남는다 — 지워진 사람들의 권한만 남는 셈이라 최악이다.

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
- **app-scim** — `POST /admin/sync/rebuild?mode=tuples`로 재적재한다.
  현재상태가 요구하는 튜플을 전부 쓰고 장부를 훑어 요구하지 않는 줄을 지우므로, 튜플 쪽 어긋남은 무엇이든 사라진다.
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

## LDAP

**AD 가 막은 계정은 비활성으로 읽는다.** 다음 둘 중 하나라도 해당하면 그 직원은 `active=false` 다 — 소속은
남고 권한 튜플만 사라진다(SCIM 의 비활성과 같다). 다시 풀면 다음 동기화에서 권한이 돌아온다.

| 속성 | 막힘 |
|---|---|
| `userAccountControl` | 비활성화 비트(`0x2`)가 켜짐. 여러 플래그가 더해진 값(`66050` 등)도 비트로 본다 |
| `accountExpires` | 동기화 시각 이전이거나 **같은 시각**. `0` 과 `9223372036854775807` 은 "만료 없음" 이다 |

- 두 속성이 없는 디렉터리(OpenLDAP 등)에서는 전원 활성이다.
- 값이 정수가 아니면(공백이 섞여도) 그 회차는 실패하고, 오류에 그 엔트리의 DN 이 실린다.
- 동기화 계정이 두 속성을 읽을 수 있어야 한다 — 못 읽으면 전원이 활성으로 읽힌다.
- 막힌 직후부터 다음 동기화까지는 권한이 남는다. LDAP 은 주기 동기화다.

**다시 읽어도 결과가 같은 실패는 재시도하지 않는다.** 정수가 아닌 계정 상태 값, 없는 필수 속성, 해석할 수 없는
DN, 멤버가 하나도 대조되지 않는 설정이 여기 속한다 — 데이터나 설정을 고쳐야 하는 문제라 곧바로 실패로 기록된다.
통신이 끊기는 것 같은 일시적 실패만 설정한 횟수(`ldap.max-retries`)만큼 다시 읽는다.

**연결·응답에 타임아웃이 있다.** `ldap.connect-timeout`(기본 10초)과 `ldap.read-timeout`(기본 150초)이다. **인증(bind) 응답
대기는 connect-timeout 이 끊는다** — bindDn 을 쓰는 컨텍스트 생성은 곧 동기 bind 라, JNDI LDAP provider 가 그 응답을
read-timeout 이 아니라 connect-timeout 으로 재기 때문이다. **인증 이후 응답(예: 페이징 중 다음 페이지) 대기는 read-timeout
이 끊는다.** 어느 쪽이든 죽은 연결에 물리면 타임아웃으로 실패하고, 일시 장애로 보고 `ldap.max-retries` 만큼 처음부터 다시
읽는다 — 그래도 안 되면 그 회차는 FAILED 이고 다음 회차는 정상으로 돈다. 읽기 기본값은 AD 가 검색 하나에 허용하는 최대 시간
(120초, `MaxQueryDuration`)보다 길게 잡았다. 한 페이지 응답이 이보다 오래 걸리는 디렉터리라면 늘린다.

**이름은 표준 속성에서 읽는다** — `givenName`→이름, `sn`→성, `generationQualifier`→접미(Jr. 등), AD 의 `middleName`→중간
이름. 속성이 없으면 빈칸이다. admin 직원 상세(`GET /admin/employees/{employeeId}`)의 `name` 에 나온다.

## SCIM

SCIM은 push 모델이라 LDAP처럼 전체를 읽어 diff하지 않는다. IdP가 보내는 요청은 항상 리소스
하나의 변경이므로, 그 **영향 범위만 담은 최소 스냅샷**을 변경 전후로 각각 만들어 튜플로 바꾼 뒤
그 둘을 diff한다.

지원 엔드포인트:

| 리소스 | POST | GET (단건) | GET (목록·필터) | PUT | PATCH | DELETE |
|---|---|---|---|---|---|---|
| `/scim/v2/Users` | O | O | O | O | O | O |
| `/scim/v2/Groups` | O | O | O | O | O | O |
| `/scim/v2/ServiceProviderConfig` | - | O | - | - | - | - |

목록·필터 조회(RFC 7644 §3.4.2)와, 같은 조회를 본문으로 보내는 `POST /scim/v2/Users/.search`·`/Groups/.search` 를
지원한다.

| | 지원 범위 |
|---|---|
| `filter` | `eq` 와 `and` 만. 직원은 `id`·`userName`·`externalId`, 조직은 `id`·`displayName`·`externalId` 중 하나의 `eq` 가 있어야 한다(`and` 뒤에는 직원 `displayName`·`active` 도 온다). 그 밖은 400 `invalidFilter` |
| 대소문자 | `userName`·조직 `displayName` 은 가리지 않는다(RFC 7643 `caseExact=false`) — `Kim` 이 있으면 `kim` 생성은 409 다. `id`·`externalId` 는 가린다 |
| `startIndex`·`count` | `count` 기본값·상한 100 |
| `sortBy`·`sortOrder` | 직원 `userName`, 조직 `displayName` 만 |
| `attributes`·`excludedAttributes` | 리소스를 돌려주는 모든 응답. 조직에서 `members` 를 빼면 멤버를 읽지 않는다 |

필터 없는 목록은 IdP 가 페이지를 순서대로 부른다는 점을 이용해, 다음 페이지를 이어 읽을 위치를 DynamoDB 에 15분
동안 책갈피로 둔다 — 직원이 10만 명이어도 페이지마다 100건만 읽는다. `totalResults` 는 가져오기 첫 페이지에서 센
값이다. 서버 루트 조회(`GET /scim/v2?filter=`)는 501 이다. 설계: `docs/superpowers/specs/2026-09-25-scim-list-filter-design.md`.

지원하는 PATCH는 다음이 전부다.

| 대상 | `path` | 지원 `op` |
|---|---|---|
| Group | `members` | `add` / `replace` / `remove` — `value` 가 없으면 전원 빼기(RFC 7644 §3.5.2.2), **`value` 가 있으면 400 `invalidValue`** |
| Group | `members[value eq "..."]` | `remove` — 값은 큰따옴표 JSON 문자열(이스케이프 풀림). 작은따옴표로 감싸면 400 `invalidFilter`. 토큰 사이 공백은 한 칸 |
| Group | `displayName` | `replace` / `add` |
| Group | (path 없음) | `replace` / `add` — `displayName` 은 바꾸고, `members` 는 `add` 면 추가·`replace` 면 교체(RFC 7644 §3.5.2.1·§3.5.2.3) |
| User | `userName` | `replace` / `add`(null·빈 문자열·공백만이면 400 `invalidValue`) (`remove` 는 400 `mutability` — 필수 속성) |
| User | `displayName` / `externalId` / `active` | `replace` / `add` / `remove`(비움. `active` 는 "없음" = 활성) |
| User | `name`, `name.givenName`·`familyName`·`middleName`·`formatted`·`honorificPrefix`·`honorificSuffix` | `replace` / `add`(`name` 은 준 하위 속성만 바꿈) / `remove` |
| User | `emails` | `replace` / `add`(목록 중 primary, 없으면 첫째를 이메일로) / `remove`(비움) |
| User | `emails[type eq "work"]` / `emails[type eq "work"].value` | `add` / `replace`(이메일이 없으면 400 `noTarget`) / `remove`(비움) |
| User | (path 없음) | `replace` / `add` — 위 속성 전부를 병합 |

그 외 path는 조용히 무시하지 않고 `invalidPath`로 400을 돌려준다 — IdP가 실제로는 반영되지
않은 변경을 반영됐다고 오해하면 안 되기 때문이다.

**조직 PATCH 는 `attributes` 가 없으면 성공 시 `204 No Content` 다**(본문 없음). RFC 7644 §3.5.2 가 허용하고, Entra 는 조직 PATCH 에
멤버 전체를 담아 돌려주는 것을 권하지 않으며 Okta 도 204 를 받는다. `attributes` 를 붙이면 RFC 가 MUST 로 정한 대로 200 과 요청한
속성을 돌려준다. PUT 과 직원 PATCH 는 200 과 리소스다.

**Microsoft Entra ID 로 연결할 때는 SCIM 테넌트 URL 끝에 `?aadOptscim062020` 을 반드시 붙인다.** 이 옵션이 없으면 Entra 는 멤버
한 명을 `{"op":"Remove","path":"members","value":[{"value":"…"}]}` 로 빼는데, RFC 7644 로 읽으면 이것은 "멤버 전원 삭제" 이고
`value` 는 remove 에 정의되지 않은 칸이다. 추측하지 않고 400 `invalidValue` 로 거절한다 — 그 멤버는 빠지지 않고 Entra 프로비저닝
로그에 실패로 남는다. 옵션을 켜면 Entra 는 `members[value eq "…"]` 로 보낸다(Okta 는 원래 이 모양이다). 이 옵션은 비활성화·경로
없는 PATCH 의 모양도 표준으로 바꾸는데, 그 모양들은 이미 받는다. 설계: `docs/superpowers/specs/2026-09-26-group-member-patch-design.md`.
요청 하나의 연산은 모두 반영되거나 모두 거절된다 — 값 붙은 remove 가 섞인 요청은 같은 요청의 add 도 반영되지 않는다.

**조직 멤버 변경의 비용.** 멤버 추가·빼기와 이름 변경은 조직 크기와 무관하게 요청에 나온 멤버만 읽고 쓴다. 전체 교체(`replace
members`, 경로 없는 `members`, `PUT`)는 저장된 멤버 아이디를 한 번 훑고 바뀐 멤버만 처리한다. 조직 PATCH·PUT 은 요청에 나온(전체
교체는 바뀐) 멤버의 권한만 OpenFGA 와 맞춰 본다 — 조직 전원을 맞추려면 `POST /admin/sync/rebuild?mode=tuples` 다. 요청 본문은
WebFlux 기본 한도(256KB, 멤버 약 7천 명)를 넘으면 받지 못한다.

`op` 와 `path` 의 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1).

**`userName` 은 POST·PUT·PATCH 모두에서 대소문자를 무시하고 유일하다**(RFC 7643 `uniqueness: server`, `caseExact: false`). 겹치면
409 `uniqueness` 다. 확인은 전역 쓰기 락 안에서 GSI 로 후보를 찾고 본 테이블에서 다시 읽는다 — 방금(GSI 반영 전, 보통 1초 미만)
저장된 직원과 대소문자만 다른 이름은 드물게 통과할 수 있다. 설계: `docs/superpowers/specs/2026-09-28-scim-write-lock-design.md`.

**경로 없는(path 없이 값 객체를 보내는) add/replace 도 같은 규칙으로 푼다.** 값 객체의 키 하나하나를
`path`로 봐서 위 표와 똑같이 해석한다 — `name.givenName`, `emails[type eq "work"].value`, 코어 스키마
URN 접두(`urn:ietf:params:scim:schemas:core:2.0:User:`, 대소문자 무시)까지 그대로 받는다. 다만 저장하지
않는 속성 키는 POST 본문과 똑같이 조용히 무시한다 — `invalidPath` 400 은 **path 형식에서만** 난다. 그래서
Entra 의 표준 호환 모드(`aadOptscim062020`)로 지우지 않고 남겨 둔 속성 매핑은 400 이 아니라 조용히
버려진다 — 운영자가 "반영은 안 됐지만 오류도 안 났다"는 것을 알고 있어야 한다.

**우리가 저장하는 직원 속성은** `userName`, `displayName`, `externalId`, `active`, `name`(여섯 칸), 이메일 하나(`type: "work"`)
뿐이다. `title`, `phoneNumbers`, `addresses`, 엔터프라이즈 확장(`department`, `manager` 등)을 경로로 PATCH 하면 400
`invalidPath` 다 — 반영되지 않은 변경을 반영됐다고 IdP 가 오해하지 않게 하려는 것이다. **IdP 의 속성 매핑에서 이 속성들을 뺀다**
(Entra 는 기본 매핑에 넣을 수 있다).

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

### 여러 대 띄우기 — 작업은 한 번에 하나, 하루 한 번 작업은 한 번만

**두 앱 모두 여러 대를 띄울 수 있다.** 동기화·재적재는 DynamoDB 조건부 쓰기 락 하나(`LOCK#MUTATION`, 앱마다 테이블이 다르다)를 잡은
인스턴스 하나만 돈다. 매일 `sync.cron`에 여러 대가 같은 초에 불러도 한 대만 돌고, 나머지는 로그만 남기고 건너뛴다. 관리 API는 곧바로 409다.
작업 동안 리스를 `dynamodb.lock-renew-interval`마다 갱신하고, 갱신이 실패하면(=남이 가져갔으면) 멈추고 `FAILED`로 남긴다.

**하루 한 번 작업(app-scim 아카이빙·만료 스냅샷 정리, app-ldap 만료 스냅샷 정리)은 `DAILY#<작업>#<날짜>` 표지를 먼저 잡은 인스턴스만
돈다**(날짜는 UTC, 표지는 사흘 뒤 TTL로 사라진다). 아카이빙은 재적재가 락을 쥐고 있으면 그날은 건너뛰고 `ABORTED`("재적재 중이라 건너뜀")로
남긴다 — 재적재 도중의 잠깐 어긋난 장부를 "실제"로 찍지 않기 위해서다. 락은 들여다보기만 한다(잡으면 그동안 SCIM 쓰기가 503이다).

### app-scim 여러 대 띄우기(동시성 제어)

**`app-scim`은 여러 인스턴스를 액티브-액티브로 띄울 수 있다.** IdP가 여러 인스턴스로 요청을
분산해도 안전하도록, SCIM 쓰기 하나(`createUser`/`changeUser`/`removeUser`/`createGroup`/`changeGroup`/`removeGroup`)와
재적재(`POST /admin/sync/rebuild`)는 **같은 DynamoDB 조건부 쓰기 전역 락(`LOCK#MUTATION`)**을 잡은 뒤에만
진행한다. 인스턴스가 몇 대든, 그리고 그 인스턴스가 SCIM 쓰기든 재적재든, 서로 겹치지 않고
직렬화된다 — 인메모리 락(예전의 `MutationGate`)은 인스턴스 하나 안에서만 유효해 여러 대를
띄우는 순간 조용히 뚫렸는데, 이 락은 저장소를 공유하므로 그렇지 않다.

**쓰기 요청의 판단은 전부 락 안에서 일어난다.** 직원·조직을 읽고, 존재를 확인하고, 아이디·`userName` 중복을 확인하는 일을 모두 락을
잡은 뒤에 한다 — 동시에 온 PATCH 가 서로의 변경을 지우거나(비활성화가 되돌려져 퇴사자 권한이 되살아나는 것 포함) 방금 지운 직원을
되살리지 않는다. 직원 삭제는 소속 조직의 이름표만 읽고 그 직원의 줄만 지운다 — 조직 크기와 무관하다.

**락을 못 잡았을 때의 동작은 두 경로가 다르다 — 의도적인 비대칭이다.**

| | 락을 못 잡았을 때 |
|---|---|
| SCIM 쓰기 | `lock-acquire-timeout` 동안 짧게 재시도한 뒤에도 못 잡으면 503 |
| 재적재 | 재시도 없이 즉시 409 |

SCIM 쓰기는 기계(IdP)가 자주 보내고 밀리초 단위로 짧게 쥐는 락과 경합하므로, 잠깐 기다려보는
편이 IdP에게 불필요한 503 재시도를 덜 시킨다. 재적재는 사람이 실행하고 드물며, 무엇보다
장부 전체를 훑어 지우는 작업이라 — 무엇이 돌고 있는지 모른 채 뒤에서
조용히 대기하는 대신 즉시 409로 "지금 다른 작업이 돈다, 확인하고 다시 실행하라"고 알리는
편이 낫다. 이 비대칭은 실수가 아니라 결정이다.

락 관련 설정 세 개(`dynamodb` 아래):

| 설정 | 의미 |
|---|---|
| `dynamodb.lock-ttl` (기본 30초) | 락 리스 길이. SCIM 쓰기 p99보다 한참 길어야 한다 — 짧으면 아직 일하는 중인데 만료돼 다른 인스턴스가 가져간다. 두 경로 모두에 적용된다 |
| `dynamodb.lock-acquire-timeout` (기본 3초) | **SCIM 쓰기 경로에만 적용된다.** 락 획득 재시도 대기 한도 — 넘으면 503을 돌려주고 IdP가 재시도한다(재시도 시점에는 락이 풀린 상태 위에서 처리된다). 재적재는 이 설정을 전혀 보지 않는다 — 재시도 자체가 없어 즉시 409다 |
| `dynamodb.lock-renew-interval` (기본 10초) | 재적재처럼 오래 쥐는 작업이 리스를 갱신하는 주기. TTL보다 충분히 짧아야 한다 |

**재적재가 도중에 리스를 잃으면 중단하고 `FAILED`로 기록한다.** 갱신(`lock-renew-interval`)이
실패했다는 것은 이미 다른 인스턴스가 락을 가져갔다는 뜻이다. 이미 나간 쓰기·지우기를 무를 방법은 없지만,
실행 기록은 사실대로 남긴다 — `SyncRun`은 운영자가 가진 유일한 신호이고,
반쯤 맞춘 장부 위로 남의 쓰기가 들어왔을지 모르는 실행을 `SUCCEEDED`로 남기면
"`mode=tuples`를 한 번 더 돌려야 한다"와 "할 일 없다"가 구별되지 않는다.

이 락은 완벽한 상호 배제를 보장하지 않는다(반납 자체의 실패, 구독 취소 등 좁은 틈이 있다) —
그 틈은 막지 못해도 **세기는 한다**. 아래 락 지표와 어긋남 지표가 그 역할이다.

| 지표 | 종류 | 의미 |
|---|---|---|
| `scim.lock.wait` | Timer | 락을 잡거나 포기하기까지 기다린 시간. 꼬리가 길어지면 `lock-acquire-timeout`을 다시 볼 때다 |
| `scim.lock.contended` | Counter | 한 번이라도 다른 쪽에 밀린 획득. 꾸준히 오르면 전역 락의 직렬화 비용이 실제로 발생하고 있다는 뜻이다(설계 §4.1의 재검토 신호) |
| `scim.lock.lease_lost` | Counter | 쥐고 있어야 할 리스를 잃었다 — 재적재 중 상실, 쓰기 직전 재확인 실패, 반납 실패, 획득 도중 취소. **넷 다 응답에 아무 흔적을 남기지 않는다.** 0이 아니면 락이 TTL만큼 묶였거나 두 인스턴스가 동시에 썼을 수 있다 |

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
./gradlew test        # 규모 테스트를 뺀 전부 — 평소에 돌린다
./gradlew scaleTest   # 규모 테스트만 — 머지 전에 돌린다
```

**규모 테스트는 기본 `test` 에서 빠진다.** 5,000명 조직도와 Testcontainers 를 띄우는 12개 클래스(`@ScaleTest`
가 붙은 것)가 전체 시간의 대부분(약 9분)을 차지해서다. 그래서 `./gradlew build`·`check` 도 규모 테스트를 돌리지
않는다. **대신 브랜치를 머지하기 전에는 `scaleTest` 까지 반드시 돌린다** — CI 가 아직 없어 이 약속이 규모
테스트가 도는 유일한 자리다. 새 규모 테스트를 만들면 클래스에 `@ScaleTest` 를 붙인다.

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

**포트가 겹쳐도 엉뚱한 곳과 통신하지 않는다.** macOS 에서는 같은 포트 번호를 "127.0.0.1 전용" 리스너(IntelliJ 등
개발 도구가 여럿 연다)와 "모든 주소" 리스너가 함께 쓸 수 있고, `localhost` 로 가는 연결은 전용 쪽이 받는다. 그래서
테스트가 가끔 IntelliJ 의 404 나 엉뚱한 프로그램의 응답(DynamoDB 가 HTTP 999)을 받고 실패했다. 두 가지로 막는다.
테스트 서버는 `127.0.0.1` 에만 연다(`application-test.yml` 의 `server.address`) — 운영체제가 이미 쓰이는 번호를 주지 않는다.
컨테이너는 한 정의(core testFixtures 의 `Containers`)만 쓰고, 그 서비스만 주는 응답(OpenFGA `SERVING`, DynamoDB
`MissingAuthenticationToken`)을 확인한 뒤에 시작된 것으로 본다 — Docker Desktop 은 호스트 포트를 VM 안에서 골라 macOS
쪽 사용 여부를 모르므로, 가짜가 답하면 컨테이너를 새로 띄워 새 포트를 받는다(최대 3번). 새 컨테이너 정의를 만들지 말고
`Containers.openFga()`·`Containers.dynamoDb()` 를 쓴다.

## 요구 버전

**OpenFGA 서버 v1.10.0 이상**이어야 한다. `on_duplicate` / `on_missing` 멱등 옵션이 그
버전부터 제공되며, 이것이 없으면 재적재와 재실행이 배치 단위로 통째로 실패한다.

**OpenFGA Check 캐시를 켜도 된다.** 이 서버가 부르는 Check·BatchCheck 는 `HIGHER_CONSISTENCY` 로 캐시를 우회한다 — 쓰기 전 기준선이
캐시된 답이면 넣고 곧바로 뺀 멤버의 튜플이 안 지워지기 때문이다. 권한을 묻는 다른 앱의 Check 는 캐시를 그대로 쓴다.
(`HIGHER_CONSISTENCY` 는 OpenFGA v1.5.7 부터 있다 — 위 v1.10.0 요구에 포함된다.)
