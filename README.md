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
기준선을 온전히 읽지 못하면(포인터가 가리키는 스냅샷의 메타가 없거나, 묶음이 빠졌거나 풀리지 않거나, 튜플 수가 다르면) 빈 기준선으로
넘어가지 않고 그 회차를 FAILED 로 끝낸다 — `POST /admin/sync/rebuild` 로 복구한다 — 재적재는 기준선 스냅샷을 읽지 않는다.

**스냅샷 본문은 압축 묶음이다.** 튜플을 한 줄에 하나씩 적어 gzip 으로 압축하고, 350KB 이하 묶음 아이템(`SNAPSHOT#<id>` 파티션의
`CHUNK#0000`, `CHUNK#0001`, …)으로 나눈다. 메타는 튜플 수와 묶음 수를 갖는다. 튜플 15만 줄이면 아이템 15만 개(쓰기 약 15만 WCU)가
묶음 열몇 개(약 6천 WCU — UUID 아이디 규모 테스트에서 압축본 6.0MB·묶음 18개)가 된다. 묶음은 하나씩 차례로 PutItem 으로 보낸다 — 한 파티션의 쓰기 한도(초당 1,000)에 걸리면 SDK 재시도가
받는다. 이 형식 전에 저장한 스냅샷(튜플 한 줄이 아이템 하나)은 묶음 수가 없어 기준선으로 읽지 않는다. 운영 배포 전이라 이관하지 않는다 —
`POST /admin/sync/rebuild` 로 새 스냅샷을 남기거나 테이블을 다시 만든다. 옛 형식 줄은 보관 기한이 지나면 정리 작업이 함께 지운다.
설계: `docs/superpowers/specs/2026-10-09-bulk-paths-design.md`.

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
이것이 이 인가 모델이 존재하는 이유다. (읽기 쉽도록 이름으로 적었다 — 실제 튜플의 `user:`·`group:` 뒤에는 아래의 불변 id가 온다.)

**조직명은 튜플에 절대 넣지 않는다.** 조직명은 개편 때마다 바뀌지만 튜플은 그 시점의 사실을
영구히 기록하는 것이 아니라 지금 참인 관계를 표현하는 것이라, 이름이 바뀔 때마다 튜플을 다시
쓰는 것은 사고를 부른다. 튜플의 식별자는 **직원의 불변 id와 조직의 불변 id뿐**이다. 조직명은 DynamoDB의
현재상태에만 보관되고, 조회가 필요하면 거기서 가져온다.

**불변 id는 디렉터리가 정한다.** SCIM은 서버가 직원·조직을 만들 때 발급하는 UUID, LDAP은 엔트리마다 서버가 만들어 두는
`entryUUID`(AD는 `objectGUID`)다. 계정명이나 조직명이 바뀌어도 id는 그대로라 튜플이 바뀌지 않고, 지운 직원의 이름을 새 입사자가
쓰더라도 옛 id로 남은 권한을 물려받지 않는다. 대신 **권한을 묻는 앱은 로그인한 사용자를 우리 id로 바꿔야 한다** — 아래 "조회 API"의
`?userName=`·`?externalId=`로 찾는다. 튜플이 UUID 라 OpenFGA 를 직접 열어 보면 읽기 어렵다 — 누구의 튜플인지는 관리 API 로 본다.

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

**⑥-3(관리자 표시명 검색)도 키를 바꾼다** — GSI2 의 정렬키가 직원 META 에만 쓰는 소문자 표시명(`displayNameKey`)이 되고, 조직은 GSI2 에
실리지 않는다(`2026-10-08-audit-finish-design.md`). 기존 테이블은 다시 만들어야 한다. `create-table-on-startup` 이 켜져 있으면 GSI2 가
옛 모양(정렬키 `displayName`)이거나 없는 테이블에서 서버가 "테이블을 다시 만들어야 한다" 며 기동을 멈춘다.

직접 만든 AWS 테이블이라면 다음을 갖춰야 한다: GSI1(파티션키 `GSI1PK`, 정렬키 `GSI1SK`, 프로젝션 `ALL`),
GSI2(파티션키 `GSI1PK`, 정렬키 `displayNameKey`, 프로젝션 `INCLUDE` — `userName`·`displayName`·`active`), GSI3(파티션키
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
결과는 `GET /admin/sync/runs/{runId}`로 본다 — `status`가 `RUNNING`에서 `SUCCEEDED`·`PARTIAL`·`FAILED`·`ABORTED`(삭제
가드에 걸림, 또는 재적재 중이라 건너뛴 아카이빙) 중 하나로 바뀐다. 작업은 앱 안에서 요청과 따로 돌아, 앞단 프록시가 연결을 끊어도 멈추지 않는다. 겹치면 곧바로 **409**다 —
두 앱 모두 DynamoDB 작업 락 하나로 클러스터 전체에서 한 번에 하나만 돈다(아래 "여러 대 띄우기").

**재적재는 장부를 버리지 않는다.** 순서는 이렇다.

1. 있어야 할 줄을 먼저 다 읽는다(app-ldap은 LDAP 전체, app-scim은 DynamoDB 현재상태 — 직원 아이디를 GSI1 로 훑고 직원은
   BatchGet(100명씩, 강한 일관성)으로, 조직은 조직마다 파티션을 읽는다). 여기서 실패하면 장부에 아무것도 하지 않고 `FAILED`다.
2. 있어야 할 줄을 전부 쓴다. 이미 있는 줄은 OpenFGA가 무시한다.
3. 장부를 Read로 훑어 있어야 할 줄에 없는 것을 지운다 — 스냅샷에 없는 찌꺼기(중단으로 못 지운 줄, 직접 써 넣은
   줄)까지 지운다.
4. 새 스냅샷에는 장부에 실제로 있다고 볼 줄(훑을 때 장부에 있던 있어야 할 줄 + 지우지 못한 줄)을 담는다. 다시 쓰기가 실패한 줄도 장부에
   있으면 담는다 — 빼면 그 사람이 나중에 빠져도 지울 기준이 없다. 지우지 못한 줄은 다음 동기화가 다시 지운다.

그래서 **인가 공백이 없다** — 재적재 동안에도 권한 질의는 정상으로 답하고, **장부 번호(storeId)도 바뀌지 않는다.**
전에는 store를 지우고 다시 만들어 그동안 모든 질의가 false였고 번호가 바뀌었다. 장부는 이 서버만 쓴다는 전제다 —
다른 앱이 이 장부에 직접 쓴 줄은 재적재가 지운다.

**기한이 있다.** `sync.job-timeout`(기본 30분, 두 앱)을 넘기면 남은 일을 멈추고 `FAILED`("기한 초과 — 30분")로
기록한 뒤 락·가드를 푼다. OpenFGA 쓰기 배치가 **3번 연달아 한 줄도 반영되지 못하면**(배치마다 재시도한 뒤에도) 남은 배치를 보내지
않고 `FAILED`로 끝낸다 — OpenFGA가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 쥐지 않는다. 드문 실패 한두
건은 지금처럼 `PARTIAL`이다. 멈춰도 이미 나간 쓰기는 되돌리지 않는다. 차단기로 멈추면 그때까지 나간 쓰기로 새 스냅샷을
남긴 뒤 `FAILED`로 기록한다(LDAP 기준선이 장부를 따라간다). 기한·서버 종료로 멈추면(재적재가 쓰기 단계에서 차단기로 멈춰도)
새 스냅샷을 남기지 않는다 — app-ldap 은 "기록 중" 표시가 남아 다음 동기화가 장부를 훑어 맞추고(아래), app-scim 은 필요하면 재적재를 다시 건다.

OpenFGA가 요청 내용을 거절하면(400 — 없는 타입, 너무 긴 아이디 등) 다시 보내도 같으므로 재시도하지 않는다. 대신 그 배치를 반씩(대상 — 옮겨 가는 직원·하위 조직 — 경계로, 한 대상까지 좁혀도 거절되면 줄 단위로)
쪼개 다시 보내 나쁜 줄만 실패로 남기고 나머지는 반영한다(나쁜 줄 하나가 100줄을 끌고 가지 않는다). 다만 그 줄이 앞 단계에서 실패했으면 같은 회차의
뒤 단계 쓰기는 보내지 않는다 — 지우기는 보낸다(아래 "쓰기는 세 단계로 나가고 단계는 겹치지 않는다").

**서버가 내려가면** 도는 작업을 멈추고 락을 푼 뒤 `FAILED`("서버 종료로 중단")로 기록한다(최대 10초 기다린다).
강제로 죽으면(kill -9, 정전) 그 기록은 `RUNNING`으로 남고 락은 TTL(30초) 뒤 풀린다. 다음에 락을 잡은 작업이 시작하면서 그 기록을
`FAILED`("비정상 종료로 중단")로 닫는다 — 락을 쥐었으니 남은 `RUNNING`은 죽은 작업이다(SCIM 아카이빙 기록은 건드리지 않는다).
닫기는 아직 RUNNING 일 때만 한다 — 방금 끝난 실행의 결과를 덮어쓰지 않는다. 락을 푼 뒤에 하는 일(app-scim 재적재의 스냅샷 저장 —
10만 명이면 몇 초, 압축 묶음 열몇 개)은 이 멈춤과 기한 밖이다. 서버가 그 사이 내려가면 DynamoDB 클라이언트가 닫히며 저장이 실패하고 기록은
`RUNNING`으로 남는다 — 강제로 죽은 경우처럼 다음 락 작업이 "비정상 종료로 중단"으로 닫는다.

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

**재적재가 도는 동안 SCIM 변경 요청은 503(`Retry-After: 60`)이다.** IdP는 503을 재시도 신호로 보므로
프로비저닝이 유실되지 않고, 재시도 시점에는 재적재가 끝난 상태 위에서 처리된다. 조회
(SCIM GET, 관리자 조회 API)는 그대로 통과한다 — 무슨 일이 벌어지는지 들여다보는 것이 그
순간 가장 필요한 일이기 때문이다. 재적재끼리 겹치면 두 번째 요청이 409로 거절된다.
(두 경로 모두 같은 전역 락을 쓰기 때문이다 — 락을 못 잡았을 때 SCIM 쓰기와 재적재가 서로
다르게 반응하는 이유는 아래 "app-scim 여러 대 띄우기" 절 참고.) 쓰기를 막는 이유는 장부 훑기가 도중에 들어온
정당한 줄을 '있어야 할 줄에 없는 줄'로 잘못 지우지 않게 하려는 것이다.

**튜플 스냅샷은 락을 푼 뒤 저장한다.** `mode=tuples`는 장부(OpenFGA)를 맞춘 뒤 락을 먼저 풀고 스냅샷을 저장한다 — 10만 명이면
저장에 몇 초가 걸리는데(압축 묶음 열몇 개 — 위 "스냅샷이 왜 있는가"), 그동안 SCIM 쓰기가 받아진다(SCIM 쓰기는 이 스냅샷을 읽지 않는다). `FAILED`의 사유가
"장부는 맞췄다 — 스냅샷 저장 실패: …"이면 튜플은 맞았고 스냅샷만 못 남겼다는 뜻이다. 재적재를 다시 돌릴 필요는 없다 — 다음 재적재나
하루 1회 아카이빙(`sync.archive-cron`, 기본 새벽 3시)이 새 스냅샷을 남긴다.

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

`sync.cron`으로 지정한 주기마다 전체 동기화가 자동으로도 돈다. LDAP이 이상 응답(예: 필터가 아무도
고르지 못해 0건)을 주면 삭제 가드가 `ABORTED`로 막고, `force=true`로 사람이 확인한 뒤 우회할 수 있다.

### 조회 API

`admin-api` 모듈이 두 앱(`app-ldap`, `app-scim`) 모두에 공유 코드로 배선돼 있다. 읽기 전용이고
현재상태(DynamoDB)와 OpenFGA의 실제 판정을 나란히 보여준다.

| 요청 | 설명 |
|---|---|
| `GET /admin/employees?userName=` | 계정명 접두사로 직원 검색 |
| `GET /admin/employees?displayName=` | 표시명 접두사로 직원 검색 |
| `GET /admin/employees?externalId=` | `externalId`가 정확히 같은 직원 검색 — 권한을 묻는 앱이 IdP의 사용자 id로 우리 id를 얻는 길 |
| `GET /admin/employees/{employeeId}` | 직원 상세 — 직속 소속과 상위 계층 전부, 각 줄에 실제 판정 포함 |
| `GET /admin/organizations?displayName=` | 표시명 접두사로 조직 검색 |
| `GET /admin/organizations?externalId=` | `externalId`가 정확히 같은 조직 검색 |
| `GET /admin/organizations/{orgCode}` | 조직 상세 — 상위 계층, 직속 하위 조직, 직속 소속 직원 첫 페이지 |
| `GET /admin/organizations/{orgCode}/members` | 조직의 직속 소속 직원 목록 (커서 페이징) |

검색은 `?cursor=`로 이어 읽고, `?limit=`(기본 20, 최대 100)로 페이지 크기를 조절한다.
직원 검색은 `userName`·`displayName`·`externalId` 중 **정확히 하나**를, 조직 검색은 `displayName`·`externalId` 중
**정확히 하나**를 줘야 한다. 없거나 둘 이상이면 400이다.

**조직 멤버 목록의 커서.** `GET /admin/organizations/{orgCode}/members` 의 `cursor` 는 불투명 문자열이다 — 응답의 `nextCursor` 를 그대로 다음
요청의 `?cursor=` 에 넘긴다. 직원 아이디 순으로 조직 파티션에서 한 쪽(`limit`, 기본 20)씩만 읽고, 쪽 사이에 멤버가 바뀌어도 이미 준 직원을 다시 주거나
그대로 있는 직원을 건너뛰지 않는다. `nextCursor` 는 저장소(DynamoDB)가 다음 쪽이 있을 수 있다고 알릴 때만 온다 — 마지막 쪽이 정확히 `limit` 명으로
끝나면 `items` 가 빈 쪽이 한 번 더 오고, 거기서 `nextCursor` 가 null 이다. 다른 조직·다른 검색이 발급한 커서, 형식이 깨진 커서, 이 조직의 직원
멤버 키가 아닌 값으로 고친 커서(정렬키가 DynamoDB 한도인 1024바이트를 넘는 것 포함)는 400 이다. 커서에 서명하지는 않으므로 같은 조직 안의 다른 위치로
고친 커서는 그 위치부터 읽는다. **조직 상세는 멤버 첫 쪽만 읽는다** — 조직 파티션 전체를 읽지 않으므로 멤버가 10만 명인 조직도 멤버는 쪽 크기(20명)만큼만 읽는다.
직원·조직 검색의 `cursor` 도 같다 — 다른 검색이 발급한 커서, 이번 검색어(접두사) 밖을 가리키는 커서(검색어를 바꾼 채 다시 보낸 경우 등), 이 검색의 키가 아닌 값으로 고친 커서는 400 이다.

**식별자 셋.** 직원에는 이름이 다른 세 값이 붙는다.

- `employeeId` — **불변 id**. 실제로 OpenFGA 튜플(`user:{employeeId}`)에 실리는 값이다. SCIM은 서버가 발급한 UUID, LDAP은
  `entryUUID`(AD는 `objectGUID`)이고, 이름이 바뀌어도 변하지 않는다
- `userName` — 원본 계정명. SCIM은 IdP가 보낸 `userName`, LDAP은 `user-login-attribute`(기본 `uid`)의 값을 정규화하지 않고
  그대로 싣는다
- `displayName` — 사람이 읽는 이름

조직도 같다. `orgCode`가 불변 id이고 튜플의 `group:{orgCode}`에 실린다. 조직에는 `userName`이 없고 `displayName`이 이름이다.

예를 들어 SCIM으로 `userName: "gd.hong"`, `displayName: "홍길동"`인 사용자를 만들면 서버가 `id`를 발급해 돌려준다.
`employeeId`와 튜플은 그 값이다.

```
employeeId   3f6c1d52-8a0e-4b7d-9c21-5e0f7a4d2b18     → 튜플 user:3f6c1d52-8a0e-4b7d-9c21-5e0f7a4d2b18
userName     gd.hong
displayName  홍길동
```

`/admin/employees/3f6c1d52-8a0e-4b7d-9c21-5e0f7a4d2b18`(경로에는 `employeeId`)로 상세를 조회하면 `userName`과
`displayName`을 함께 볼 수 있다.

**권한을 묻는 앱은 로그인한 사용자를 우리 id로 바꿔야 한다.** 토큰의 사용자 식별과 `employeeId`를 자동으로 잇지는 않는다.
앱이 아는 값으로 한 번 조회해 `employeeId`를 얻는다.

- `GET /admin/employees?userName=gd.hong` — 계정명으로 찾는다. 접두사 검색이라 더 긴 계정명도 함께 올라오므로 `userName`이 같은 줄을 고른다
- `GET /admin/employees?externalId=<IdP의 사용자 id>` — 정확히 일치한다. SCIM이면 IdP가 보낸 `externalId`(Okta의 사용자 id, Entra에서
  `objectId`를 `externalId`로 매핑했다면 그 값)이고, LDAP이면 서버가 준 절대 DN이다. **LDAP은 관리 API가 보여 주는 DN을 그대로 쓴다** — 저장된
  값은 서버가 준 DN을 `LdapName`으로 다시 쓴 문자열이라, 앱이 따로 적은 표기(공백·이스케이프·대소문자)는 맞지 않을 수 있다

`Check(user:<얻은 employeeId>, member, group:<조직 id>)`로 묻는다. app-scim은 `GET /scim/v2/Users?filter=userName eq "…"`·
`filter=externalId eq "…"`로도 찾을 수 있다.

**`?externalId=`는 정확히 일치만 찾고 한 페이지로 끝난다**(`nextCursor`는 null). 같은 `externalId`를 가진 리소스가 둘이면 줄이 여럿 온다 —
SCIM 조직의 `externalId`는 겹치면 409지만(아래 SCIM 절) 직원의 `externalId`는 겹침을 막지 않는다. 이 조회는 GSI3(`externalId`)로
후보를 찾은 뒤 본 테이블에서 다시 확인한다. GSI는 읽기가 조금 늦게 따라오므로, **방금 만든 리소스는 잠깐(보통 1초 미만) 나오지
않을 수 있다.**

**검색은 접두사만 지원한다.** `displayName=홍`은 "홍"으로 시작하는 이름을 찾을 뿐, 부분일치나
전문 검색은 지원하지 않는다(`externalId`만 정확히 일치다). 조직 id(`orgCode`) 자체의 접두사 검색도 없다 — 조직은
표시명 접두사나 `externalId`로 찾고, 정확한 id를 안다면 `/admin/organizations/{orgCode}`로 바로 조회한다.
세 검색(`userName`·직원 `displayName`·조직 `displayName`) 모두 대소문자를 가리지 않는다 — `displayName=KIM` 이 `Kim Chulsoo` 를 찾는다. 결과의 값은 저장한 그대로다.

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
버려지므로 재적재의 근거가 될 수 없다 — 고쳐야 할 것은 조직도 쪽의 순환이다. SCIM 이면 버려진 간선이 보류 목록에 남는다(SCIM 절의
"중첩 조직과 순환").

**인증이 없다.** `/admin/sync`와 마찬가지로 `/admin/employees`, `/admin/organizations`도
누구나 호출할 수 있게 열려 있다. 조직도와 소속 정보를 그대로 노출하므로, 실제 운영에
투입하기 전에 반드시 앞단에서 보호해야 한다.

## LDAP

**id 는 엔트리의 불변 값이다.** 식별 속성(groupOfNames 의 `user-id-attribute`·`group-id-attribute`, DIT 의 `user-id-attribute`·
`group-id-attribute`(OU))의 기본값은 `entryUUID` 다(RFC 4530). OpenLDAP·389DS·UnboundID 가 엔트리마다 만들어 두고 이름이 바뀌어도
그대로 둔다. 이 값이 튜플의 `user:`·`group:` 뒤에 실린다.

**AD 는 `objectGUID` 로 설정한다.** `user-login-attribute` 도 `sAMAccountName` 으로 바꾼다.

```yaml
ldap:
  group-of-names:            # DIT 는 dit: — 이름이 같다
    user-id-attribute: objectGUID
    group-id-attribute: objectGUID
    user-login-attribute: sAMAccountName
```

식별 속성 이름이 `objectGUID`(대소문자 무시)면 이진 속성으로 자동으로 선언하고(JNDI `java.naming.ldap.attributes.binary` — 따로 설정하지
않는다) 16바이트를 표준 GUID 문자열로 바꾼다. 소문자이고 앞 세 묶음은 바이트 순서를 뒤집는다(리틀 엔디언). AD 도구(`Get-ADUser`)가
보여 주는 값과 같다. 16바이트가 아니면 데이터 오류다. 이진 선언과, `member` 를 이름을 대 요청할 때의 `member;range=` 동작은 임베디드 서버로 볼 수
없어 **실제 AD 에서는 확인하지 못했다.**

**이름 기반(`uid`/`cn`/`ou`/`employeeNumber`) id 도 설정으로 쓸 수 있지만 개명이 삭제+생성이다.** 이름이 바뀌면 새 id 의 직원·조직이
생기고 옛 것의 튜플은 지워진다. DIT 에서 OU 하나를 개명하면 그 아래 직원 전원의 튜플이 지워졌다가 다시 쓰이고, 큰 조직을 개명하면 지울
줄이 장부의 30% 를 넘어 삭제 가드가 그 회차를 `ABORTED` 로 멈춘다 — 그동안 퇴사도 반영되지 않는다. 대소문자만 바꿔도(`JKim` →
`jkim`) 새 사람이다. `entryUUID`·`objectGUID` 에서는 개명해도 같은 id 라서 이런 일이 없다(큰 조직 개명이 삭제 가드에 걸리지 않는 것을
e2e 로 확인했다).

**`userName` 은 `user-login-attribute`(기본 `uid`)의 원본 값이다.** 정규화하지 않는다 — `uid: hong gd` 는 `hong gd` 그대로다. 그 속성이
없으면 식별 값으로 대신한다. 표시명은 이렇게 채운다 — 직원은 `user-name-attribute`(기본 `displayName`) → `cn` → `userName`, 조직은
`group-name-attribute`(기본 `description`) → DN 의 첫 RDN 값(`cn`/`ou`)이다. 보통의 엔트리는 이 사슬에서 이름이 나오므로 UUID 가 표시명으로
보이지 않는다. 다만 `userName` 은 로그인 속성이 없으면 id 로 대신하므로, 로그인 속성·표시명 속성·`cn` 이 모두 없는 직원 엔트리는 UUID 가 그대로
표시명이 된다. `externalId` 는 두 전략 모두 서버가 준 절대 DN 이다.

**어떤 엔트리를 직원·조직으로 읽을지는 필터로 정한다.** 필터 전체(RFC 4515)를 설정 하나로 받아 그대로 서버에 보낸다 — 우리가 해석하지 않는다.

| 설정 | 기본값 |
|---|---|
| `ldap.group-of-names.user-filter` | `(objectClass=inetOrgPerson)` |
| `ldap.group-of-names.group-filter` | `(objectClass=groupOfNames)` |
| `ldap.dit.org-unit-filter` | `(objectClass=organizationalUnit)` |
| `ldap.dit.user-filter` | `(objectClass=inetOrgPerson)` |

AD 는 사람을 `(&(objectCategory=person)(objectClass=user))`, 그룹을 `(objectClass=group)` 으로 바꾼다. DIT 에서 `CN=Users` 같은 기본
컨테이너(OU 가 아니다)도 조직으로 읽으려면 조직 필터를 `(|(objectClass=organizationalUnit)(&(objectClass=container)(cn=Users)))` 로 둔다.
맨 `(objectClass=container)` 를 더하면 `root-dn` 이 도메인 루트일 때 `CN=System`·GPO 같은 시스템 컨테이너까지 조직이 된다 — 읽을 컨테이너를 이름으로 고른다.

```yaml
ldap:
  group-of-names:            # DIT 는 dit: — user-filter 가 같은 이름이고, 그룹 대신 org-unit-filter 가 있다
    user-filter: "(&(objectCategory=person)(objectClass=user))"
    group-filter: "(objectClass=group)"
```

**`(objectClass=user)` 만 쓰면 컴퓨터 계정이 직원이 된다.** AD 에서 `computer` 는 `user` 의 하위 클래스라 서버 계정(gMSA 포함)이 모두 걸린다.
사람만 고르려면 `objectCategory=person` 을 함께 건다. 임베디드 서버는 `objectCategory` 의 값을 글자 그대로 비교하는데 실제 AD 는 클래스 이름을 스키마
DN 으로 풀어 비교하므로, 이 필터는 **실제 AD 에서는 확인하지 못했다.**

옛 `user-object-class`·`group-object-class`·`org-unit-object-class` 설정은 없어졌고 받아 주지 않는다. Spring 은 모르는 설정 이름을 조용히 무시하므로,
옛 이름이 설정에 남아 있으면 오류 없이 기본 필터가 쓰여 읽으려던 엔트리를 읽지 못한다. 문법이 틀린 필터(괄호 짝이 안 맞는 등)는 JNDI 가 서버에 보내기 전에
거절하고, 다시 읽어도 같으므로 재시도하지 않는다.

**AD 의 기본 그룹 소속도 더한다(groupOfNames).** AD 는 사용자의 기본 그룹(대개 Domain Users)을 그 그룹의 `member` 에 적지 않고 사용자의
`primaryGroupID`(그룹의 RID)로만 표현한다(MS-ADA3). 그래서 관리자가 어떤 그룹을 사용자의 기본 그룹으로 바꾸면 `member` 만 보는 동기화가 그 조직 권한을
지운다. 직원 검색이 `primaryGroupID` 를, 그룹 검색이 `objectSid` 를 함께 요청하고, 그룹 SID 의 마지막 하위 권한(RID, MS-DTYP §2.4.2.2)이 직원의
`primaryGroupID` 와 같으면 그 직원을 그 조직의 멤버로 더한다. 설정은 없고 늘 켜져 있다 — `objectSid` 는 설정과 상관없이 늘 이진 속성으로 선언한다(`objectGUID` 는 식별 속성 설정이 그 이름을 쓸 때만 선언한다).
두 속성이 없는 디렉터리(OpenLDAP)에서는 아무 일도 없다.

- **직원과 그룹은 한 도메인 안에서 검색해야 한다.** RID 는 한 도메인 안에서만 유일하고 우리는 RID 만 맞춘다(도메인 SID 는 보지 않는다). 글로벌 카탈로그(포트
  3268)는 여러 도메인의 엔트리를 referral 없이 한꺼번에 돌려주므로 다른 도메인의 같은 RID 가 맞아 버린다 — 도메인 컨트롤러의 LDAP 포트(389/636)에, 그 도메인 안의
  검색 베이스로 읽는다. 같은 RID 의 그룹이 둘이면 데이터 오류로 멈추지만, 다른 도메인의 직원이 이 도메인 그룹의 RID 를 가리키는 것은 막지 못한다.
- 기본 그룹이 그룹 검색 범위 안에 있으면 **전원이 그 조직의 멤버가 된다.** Domain Users 를 읽는 범위에 두면 모든 직원이 그 조직과 그 상위의 구성원이다 — AD 가
  말하는 소속과 같다. 원치 않으면 그 그룹을 `group-search-base`·`group-filter` 밖에 둔다. 읽지 않은 그룹을 가리키는 `primaryGroupID` 는 아무 소속도 더하지 않는다.
- `primaryGroupID` 가 정수가 아니거나, `objectSid` 가 SID 형식이 아니거나, 같은 RID 의 그룹이 둘이면 데이터 오류다(아래 재시도 없는 실패). 식별 속성이 없는 엔트리는 다른 값을 읽지
  않는다. id 가 겹쳐 건너뛰는 엔트리는 값을 이미 읽은 뒤에 가려내므로, 그 엔트리의 `primaryGroupID`·`objectSid` 가 표준 밖이어도 읽기가 실패한다.
- DIT 에는 없다 — DIT 의 소속은 부모 OU 로 정한다.
- 이진 선언과 RID 해석은 임베디드 서버에 합성한 SID 로만 보았다 — **실제 AD 에서는 확인하지 못했다.**

**검색은 쓰는 속성만 이름을 대 요청한다.** 운영 속성인 `entryUUID` 는 이름을 대야 오기도 하고, AD 사용자 하나에는 `memberOf`·
`proxyAddresses`·`thumbnailPhoto` 등이 10~20KB 붙어 10만 명이면 한 회차에 1~2GB 였다(추정). 요청하는 속성은 직원의 식별·로그인·표시명·메일
속성과 `cn`, 계정 상태(`userAccountControl`·`accountExpires`·`pwdAccountLockedTime`), 이름 속성, 그룹의 식별·이름·멤버 속성, OU 의 식별·이름 속성이다.
groupOfNames 는 직원 검색에 `primaryGroupID` 를, 그룹 검색에 `objectSid` 를 더한다. DIT 전략도 엔트리
원본이 아니라 필요한 값만 담은 작은 레코드를 들고 간다. 줄어든 양은 추정이다 — 임베디드 서버는 속성이 몇 개뿐이라 AD 크기를 재지 못한다.

**디렉터리가 막은 계정은 비활성으로 읽는다.** AD 속성 둘과 OpenLDAP ppolicy 속성 하나, 다음 셋 중 하나라도 해당하면 그 직원은 `active=false` 다 — 소속은
남고 권한 튜플만 사라진다(SCIM 의 비활성과 같다). 다시 풀면 다음 동기화에서 권한이 돌아온다.

| 속성 | 막힘 |
|---|---|
| `userAccountControl` | 비활성화 비트(`0x2`)가 켜짐. 여러 플래그가 더해진 값(`66050` 등)도 비트로 본다 |
| `accountExpires` | 동기화 시각 이전이거나 **같은 시각**. `0` 과 `9223372036854775807` 은 "만료 없음" 이다 |
| `pwdAccountLockedTime` (ppolicy) | `000001010000Z` — 관리자만 풀 수 있는 영구 잠금. 다른 값은 잠긴 시각(일시 잠금)이라 **활성**이다 |

- 속성이 없는 디렉터리에서는 전원 활성이다 — AD 의 둘은 OpenLDAP 에, ppolicy 는 AD 에 없다.
- AD 두 속성의 값이 정수가 아니면(공백이 섞여도) 그 회차는 실패하고, 오류에 그 엔트리의 DN 이 실린다.
- 동기화 계정이 세 속성을 읽을 수 있어야 한다 — 못 읽으면 전원이 활성으로 읽힌다. `pwdAccountLockedTime` 은 운영 속성이라 이름을 대 요청한다.
- ppolicy 는 표준 영구 잠금 값만 본다. `pwdLockoutDuration: 0` 정책 아래의 다른 값(사실상 영구)은 정책 엔트리를 읽어야 알 수 있어 일시 잠금(활성)으로 읽는다.
- 막힌 직후부터 다음 동기화까지는 권한이 남는다. LDAP은 주기 동기화다.

**일부 엔트리는 건너뛰고, 아무도 남지 않으면 실패한다.** 엔트리 하나 때문에 10만 명 회차가 멈추지 않는다. 다음은 건너뛰거나 따로 세고, 회차마다 검색별로 건수와
예시 DN(다섯 개까지)을 **경고 한 줄**로 남긴다 — 엔트리마다 한 줄을 남기지 않는다.

- 식별 속성이 없거나 비어 있는 엔트리(직원·그룹·OU) — 건너뛴다. 사번처럼 일부 엔트리에만 있는 속성을 id 로 쓰면 생긴다.
- 정규화 뒤 같은 id — 먼저 읽은 쪽을 두고 나머지를 건너뛴다. 식별 속성이 없는 엔트리는 다른 값을 읽지 않지만, 건너뛰는 쪽의 값은 이미 읽은 뒤라 그 엔트리의 계정 상태(groupOfNames 는 `primaryGroupID`·`objectSid` 도)가 표준 밖이면 읽기가 실패한다.
- DIT 에서 부모 조직을 찾지 못한 직원 — 직원으로는 적재하되 소속이 없다.
- groupOfNames 에서 그룹의 `member` 값이 읽은 직원도 조직도 아닌 것(컴퓨터·연락처, 위에서 건너뛴 엔트리를 가리키는 값) — 값마다가 아니라 요약 한 줄이다. 이것으로는 멈추지 않는다.

**실패는 아무도 남지 않을 때뿐이다**(재시도 없음). 직원 검색이 엔트리를 받았는데 남은 직원이 0명, 그룹(OU) 검색이 엔트리를 받았는데 남은 조직이 0개,
DIT 에서 직원이 남았는데 부모 조직을 가진 직원이 0명이면 데이터 오류다. 마지막은 `root-dn` 아래 컨테이너가 OU 가 아닌 경우(AD 의 `CN=Users`)이고,
`org-unit-filter` 에 그 컨테이너를 더해 답한다(위 권장값). 일부만 빠지는 경우는 막지 않는다 — 이미 적재된 장부가 있으면 삭제 가드(30%)가 큰 빠짐을 막고, 첫 적재에서는 경고 한 줄이
유일한 신호다.

**다시 읽어도 결과가 같은 실패는 재시도하지 않는다.** 데이터나 설정을 고쳐야 하는 문제라 곧바로 실패로 기록된다.

- 데이터·설정 오류 — 정수가 아닌 계정 상태 값이나 `primaryGroupID`, 16바이트가 아닌 `objectGUID`, SID 형식이 아닌 `objectSid`, 같은 RID 의 그룹 둘, 해석할 수 없는 DN,
  멤버가 하나도 대조되지 않는 설정, 아무도 남지 않는 읽기(위).
- 요청 자체가 틀렸다는 LDAP 결과 코드(RFC 4511) — 49 인증 실패(비밀번호 오류·잠긴 서비스 계정), 50 권한 없음, 32 없는 이름(검색 베이스 오타), 34 DN 문법,
  4 크기 한도 — 와 서버에 보내기 전에 거절된 필터 문법.

그 밖은 — 모르는 종류까지 — 연결 끊김, 서버 busy·unavailable, 응답 시간 초과 같은 일시적 실패로 보고 설정한 횟수(`ldap.max-retries`)만큼 다시 읽는다.

**referral 은 따라가지 않되 경고한다.** 검색 범위가 자식 도메인이나 위임된 서브트리를 걸치면 서버가 그 부분을 검색 결과 참조(referral, RFC 4511 §4.5.3)로 돌려준다.
따라가지 않는다 — 다른 DC 의 주소·자격 증명·DNS 가 필요하고, AD 도메인 루트에서는 DNS 파티션 같은 엉뚱한 영역까지 읽는다. 대신 조용히 넘기지 않는다 — 검색마다 경고 한 줄
(검색 베이스, 필터, JNDI 가 준 메시지)을 남기고, 그때까지 받은 엔트리와 다음 페이지는 그대로 읽는다. 엔트리를 받은 검색은 실패로 만들지 않는다 — AD 에서 도메인 루트를 검색 베이스로 쓰면
참조가 늘 온다. **참조를 만났는데 엔트리를 하나도 받지 못한 검색은 실패한다**(데이터 오류, 재시도 없음) — 검색 베이스 자체가 참조, 대개 이 DC 의 이름 공간 밖(다른 도메인)을
가리킨다. 오류에 검색 베이스·필터·JNDI 메시지가 실린다. 빈 결과로 읽으면 전원이 빠진 회차가 되기 때문이다. 이 경고가 보이면 그 부분의 직원은 이번 회차에 없는 것과 같다 — 검색 베이스를 한 도메인 안, 위임되지 않은 서브트리로 좁힌다. 큰 빠짐은 이미 적재된 장부가 있을 때 삭제 가드가
막는다. 임베디드 서버로는 검색 요청 단계에서 참조를 끼워 시험했고, **실제 AD 에서는 확인하지 못했다.**

**헬스 프로브는 인증이 실패한 뒤 30분 동안 바인드하지 않는다.** `GET /actuator/health` 의 LDAP 항목은 바인드로 확인한다. 비밀번호가 틀린 채 프로브가 몇 초마다 바인드하면
AD 의 잠금 기준(관찰 창 15~30분 안에 여러 번 실패)에 닿아 서비스 계정이 잠기고 동기화까지 멈춘다. 그래서 인증 실패를 받으면 30분 동안은 바인드하지 않고 기억한 오류로 DOWN 을
답하며, 30분이 지나면 한 번 다시 바인드한다 — 비밀번호를 고쳤으면 재시작 없이 UP 으로 돌아온다. 인증이 아닌 실패(연결 끊김 등)는 매번 다시 확인한다. 30분은 상수다.
쉬는 것은 프로브뿐이다 — 정기 동기화와 수동 동기화는 그때마다 한 번 바인드한다.

**OpenFGA 에 쓰는 순서.** 조직을 옮기는 직원과 하위 조직의 상위 변경은 LDAP 동기화에서도 생긴다. 두 앱이 같은 어댑터를 쓰므로 쓰기는 세 단계(조직의 상위 연결 지우기 → 직원 줄 → 조직의 상위 연결 쓰기)로 나가며,
규칙과 한계는 아래 SCIM 절 "app-scim 여러 대 띄우기(동시성 제어)" 의 "쓰기는 세 단계로 나가고 단계는 겹치지 않는다" 에 있다. 세 단계의 보장은 쓰기 한 번(`apply`) 안의
것이다 — 재적재와 기록 전에 멈춘 뒤의 첫 동기화는 있어야 할 줄을 먼저 다 쓰고 나서 장부의 나머지를 지우므로(위 "재적재는 장부를 버리지 않는다"), 그 사이에는 조직을 옮기는
직원이 옛 조직과 새 조직의 권한을 함께 갖는다. 인가 공백을 두지 않으려고 고른 틈이다.

**연결·응답에 타임아웃이 있다.** `ldap.connect-timeout`(기본 10초)과 `ldap.read-timeout`(기본 150초)이다. **인증(bind) 응답
대기는 connect-timeout 이 끊는다** — bindDn 을 쓰는 컨텍스트 생성은 곧 동기 bind 라, JNDI LDAP provider 가 그 응답을
read-timeout 이 아니라 connect-timeout 으로 재기 때문이다. **인증 이후 응답(예: 페이징 중 다음 페이지) 대기는 read-timeout
이 끊는다.** 어느 쪽이든 죽은 연결에 물리면 타임아웃으로 실패하고, 일시 장애로 보고 `ldap.max-retries` 만큼 처음부터 다시
읽는다 — 그래도 안 되면 그 회차는 FAILED 이고 다음 회차는 정상으로 돈다. 읽기 기본값은 AD 가 검색 하나에 허용하는 최대 시간
(120초, `MaxQueryDuration`)보다 길게 잡았다. 한 페이지 응답이 이보다 오래 걸리는 디렉터리라면 늘린다.

**페이징 응답 컨트롤이 없으면 회차를 멈춘다.** 페이징(`ldap.page-size`, 기본 500)은 paged results 컨트롤(RFC 2696)을 critical 로 보낸다. 표준을 지키는
서버는 응답에 컨트롤을 붙이거나 오류로 답한다(RFC 4511 §4.1.11). 컨트롤 없이 답하는 서버·프록시를 만나면 받은 목록이 전부인지 알 수 없으므로 그 회차는
재시도 없이 FAILED(데이터 오류)다 — 전에는 같은 요청을 작업 기한까지 끝없이 되풀이하며 같은 엔트리를 쌓았다. 그런 서버라면 `ldap.page-size=0` 으로 페이징을 끈다.

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

**멤버를 싣는 조직 응답은 흘려 쓴다.** `members` 가 응답에 남는 조직 응답 — `GET /Groups/{id}`, `GET /Groups`·`POST /Groups/.search` 의 목록,
POST·PUT 의 응답, `attributes` 를 붙인 PATCH 의 응답 — 은 본문을 `Content-Length` 없이 청크로 보낸다. 멤버를 DynamoDB 에서 한 쪽씩 읽는 대로
JSON 으로 써 내보내고 메모리에 모으지 않으므로, 10만 명 조직 하나가 힙을 조직 크기만큼 차지하지 않는다. 클라이언트가 덜 받으면 많아야 DynamoDB 한 쪽 앞서 읽고 멈춘다.
읽는 양은 그대로 조직 전체다 — 멤버 전체를 달라는 요청이기 때문이다. Okta 가 그룹을 연결·푸시할 때 보내는 파라미터 없는 `GET /Groups/{id}` 와
`excludedAttributes` 없는 `filter=displayName eq` 가 이 길이다. `excludedAttributes=members`(Entra 는 늘 붙인다)나 `members` 를 뺀 `attributes`
를 붙이면 멤버 줄을 읽지 않고 이름표만 한 번에 보낸다. JSON 값은 예전과 같고 필드 순서만 다르다 — `members` 가 맨 뒤이고, 목록은 `itemsPerPage`
가 맨 끝이다(실제로 쓴 조직 수 — 목록을 만든 뒤 지워진 조직은 건너뛴다). 멤버는 하위 조직이 먼저, 그다음 직원이고 각각 아이디 순이다. 조직 헤더를
먼저 읽으므로 없는 조직의 404 와 이 단계의 저장소 장애(503)는 첫 바이트 전에 나간다. **첫 바이트가 나간 뒤 저장소가 실패하면 상태 코드를 바꿀 수 없어 연결이
끊긴다** — 닫는 괄호를 쓰지 않으므로 완결된 200 JSON 은 나가지 않고, IdP 에는 네트워크 오류로 보인다. 설계: `docs/superpowers/specs/2026-10-06-read-paths-design.md`.

**거절하는 조회 모양.** 위 표 밖의 필터는 400 `invalidFilter` 다. "표준이 정한 신호만 받는다" 는 원칙에 따른 알려진 제한이고, 다음 IdP 요청이 여기에 걸린다.

- `manager` 필터 — Entra 의 참조 확인 `filter=id eq "a" and manager eq "b"`. `manager` 를 PATCH path 로 보내는 요청은 받아서 버려 200 이지만(아래) 이 필터는 여전히 400 이다.
- 이메일 매칭 — Entra 의 `filter=emails[type eq "work"].value eq "x"`.
- `co` 연산자 — Ping 의 관리자 필터 `email Co "…"`.
- JumpCloud 가 재연결 때 이메일로 하는 조회(요청 모양은 확인하지 못했다).

IdP 의 매칭 속성은 `userName` 이나 `externalId` 를 쓴다.

적용하는 PATCH는 다음이 전부다. 저장하지 않는 직원 속성은 이 표 밖이지만 받아서 버린다(아래 "우리가 저장하는 직원 속성은").

| 대상 | `path` | 지원 `op` |
|---|---|---|
| Group | `members` | `add` / `replace` / `remove` — `value` 가 없으면 전원 빼기(RFC 7644 §3.5.2.2), **`value` 가 있으면 400 `invalidValue`** |
| Group | `members[value eq "..."]` | `remove` — 값은 큰따옴표 JSON 문자열(이스케이프 풀림). 작은따옴표로 감싸면 400 `invalidFilter`. 토큰 사이 공백은 한 칸 |
| Group | `displayName` | `replace` / `add` |
| Group | `externalId` | `replace` / `add` / `remove`(비움) — PUT 과 같은 중복 판정이라 다른 조직과 겹치면 409 `uniqueness` |
| Group | (path 없음) | `replace` / `add` — `displayName`·`externalId` 는 바꾸고, `members` 는 `add` 면 추가·`replace` 면 교체(RFC 7644 §3.5.2.1·§3.5.2.3) |
| User | `userName` | `replace` / `add`(null·빈 문자열·공백만이면 400 `invalidValue`) (`remove` 는 400 `mutability` — 필수 속성) |
| User | `displayName` / `externalId` / `active` | `replace` / `add` / `remove`(비움. `active` 는 "없음" = 활성) |
| User | `name`, `name.givenName`·`familyName`·`middleName`·`formatted`·`honorificPrefix`·`honorificSuffix` | `replace` / `add`(`name` 은 준 하위 속성만 바꿈) / `remove` |
| User | `emails` | `replace` / `add`(목록 중 primary, 없으면 첫째를 이메일로) / `remove`(비움) |
| User | `emails[type eq "work"]` / `emails[type eq "work"].value` | `add` / `replace`(이메일이 없으면 400 `noTarget`) / `remove`(비움) |
| User | (path 없음) | `replace` / `add` — 위 속성 전부를 병합 |

조직 PATCH 는 `path` 와 경로 없는 값의 키 모두 코어 Group URN 접두(`urn:ietf:params:scim:schemas:core:2.0:Group:`, 대소문자 무시)를 붙여도
받는다 — `…:Group:displayName` 은 `displayName` 과 같다. 표에 없는 조직 path 는 400 `invalidPath` 다. 직원의 표 밖 path 는 RFC 가
정의한 속성이면 받아서 버리고 RFC 에 없는 것만 400 이다.

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
WebFlux 기본 한도(256KB, 멤버 약 7천 명)를 넘으면 받지 못하고 413 이다(아래 "오류 응답").

**요청 형식.**

- 본문의 속성 이름은 대소문자를 가리지 않는다(RFC 7643 §2.1) — `"Members"`·`"Active"`·`"Filter"` 도 읽는다. `op`·`path`·PATCH 값 객체의 키도 같다. 같은 속성이
  대소문자만 달리 두 번 오면 어느 값이 쓰이는지는 정하지 않았다. 응답의 속성 이름은 RFC 표기 그대로다.
- PATCH 의 `active` 는 JSON boolean 이나 문자열 `"true"`/`"false"`(대소문자 무관 — Entra 가 문자열로 보낸다는 문서 근거가 있다)만 받는다. 그 밖(`"yes"`·`"1"`·`" true"`)은 400
  `invalidValue` 이고 비활성화하지 않는다. POST·PUT 본문의 `active` 가 `"yes"` 처럼 boolean 으로 읽히지 않는 문자열이어도 400 이다.
- 직원·조직 POST 의 201 에는 `Location` 헤더가 붙는다. 값은 본문 `meta.location` 과 같은 상대 경로(`/scim/v2/Users/<id>`, `/scim/v2/Groups/<id>`)다(RFC 7644 §3.3).

**`id` 는 서버가 발급한다**(RFC 7643 §3.1). 직원·조직 POST 마다 무작위 UUID(v4, 소문자 하이픈)를 새로 만들어 응답으로 돌려주고,
요청 본문의 `id` 는 무시한다. IdP 는 이 `id` 를 저장해 PATCH·PUT·DELETE 의 경로와 조직의 `members[].value` 에 쓴다. PUT 도 경로의 `id` 가
기준이다. 이 `id` 가 곧 `employeeId`·`orgCode` 이고 튜플에 실린다. 그래서 지운 직원과 같은 `userName` 으로 다시 만들어도 새 `id` 라 옛 `id` 로
남은 권한을 물려받지 않고, 이름을 바꾼 직원의 옛 `userName` 을 새 입사자가 쓸 수 있다. **IdP 는 아직 받지 못한 `id` 를 알 수 없다** — 그래서
실제로는 자기가 만들어 `id` 를 받은 리소스만 가리키고, 조직도 직원도 먼저 만든 뒤에 멤버를 PATCH 하는 순서가 된다(서버가 모르는 `id` 를 멤버로
받으면 막지는 않는다 — 멤버 줄만 저장하고, 그 직원이 실제로 있기 전에는 튜플을 만들지 않는다. 아래 `members[].value` 문단). 조직의 `externalId`(IdP 가 정하는 값)는 속성으로만
남고 id 를 만들지 않는다.

**`userName` 은 POST·PUT·PATCH 모두에서 대소문자를 무시하고 유일하다**(RFC 7643 `uniqueness: server`, `caseExact: false`). 겹치면
409 `uniqueness` 다. **조직은 `externalId` 가 비어 있지 않고 다른 조직과 같으면 409 `uniqueness` 다** — POST 는 물론, `externalId` 를 바꾸는
PUT 도 같다(PATCH 도 같다 — path `externalId` 와 경로 없는 값의 `externalId` 키 모두. remove 는 비운다). RFC 핵심 스키마에서 `id` 말고는 유일한 속성이 없으므로 이것은 우리 규칙이다. `externalId` 가 있는 조직이면 응답을 잃은 POST 를 IdP 가 재시도해 같은 조직이
둘 생기고 멤버가 갈리는 것을 막는다. `externalId` 는 대소문자를 가리고(`caseExact: true`), 비어 있으면 판정하지 않으며, `externalId` 를 일부러 겹치게
보내는 IdP 설정이면 409 가 난다. 직원의 `externalId` 는 중복을 확인하지 않는다. 두 확인 모두 전역 쓰기 락 안에서 GSI 로 후보를 찾고 본
테이블에서 다시 읽는다 — 방금(GSI 반영 전, 보통 1초 미만) 저장된 직원과 대소문자만 다른 이름, 방금 저장된 조직과 같은 `externalId` 는 드물게 통과할 수 있다.
설계: `docs/superpowers/specs/2026-09-28-scim-write-lock-design.md`, `docs/superpowers/specs/2026-10-04-immutable-identifiers-design.md`.

**`externalId` 없는 조직 POST 는 재시도를 막지 못한다.** Okta 식으로 `externalId` 없이 조직을 만들고(서버가 새 `id` 를 발급한다) IdP 가 응답을 잃어
같은 POST 를 재시도하면 같은 이름의 조직이 둘 생길 수 있다. 조직 `displayName` 은 겹쳐도 되는 속성(RFC 7643 §8.7.1 Group 스키마, `uniqueness: none`)이고 POST 는
멱등이 아니라서 표준에 막을 신호가 없다 — 이름으로 막으면 이름이 같은 정상 조직도 막힌다. 응답을 잃은 쪽이 재시도 찌꺼기이고 멤버가 없다(Okta 는 빈 조직을 만들고 PATCH 로
채운다). 관리 API `GET /admin/organizations?displayName=…` 로 같은 이름의 조직을 찾고(접두사 검색이라 이름이 더 긴 조직도 함께 나온다) 조직 상세로 멤버 없는 쪽을 가려낸다. IdP 가 조직 `externalId` 를
보낼 수 있으면 매핑한다 — 그러면 재시도가 409 로 막힌다.

**경로 없는(path 없이 값 객체를 보내는) add/replace 도 같은 규칙으로 푼다.** 값 객체의 키 하나하나를
`path`로 봐서 위 표와 똑같이 해석한다 — `name.givenName`, `emails[type eq "work"].value`, 코어 스키마
URN 접두(`urn:ietf:params:scim:schemas:core:2.0:User:`, 대소문자 무시)까지 그대로 받는다. 다른 점은 하나다 — RFC 에 없는 키(오타, 커스텀 확장 키)는
조용히 무시한다. `invalidPath` 400 은 **path 형식에서만** 난다.

**우리가 저장하는 직원 속성은** `userName`, `displayName`, `externalId`, `active`, `name`(여섯 칸), 이메일 하나(`type: "work"`)
뿐이다. 이 속성들은 적용할 수 있는 모양이면 엄격하게 적용한다(예: `userName` remove·빈 값은 400). 이메일의 다른 모양(work 가 아닌 type, 필터 없는 `emails.value`, `.display`)은 아래처럼 받아서 버린다. 나머지는 RFC 가 정의했는지로 가른다.

- **RFC 7643 이 정의했지만 저장하지 않는 속성은 path 로 와도, 경로 없는 값으로 와도 받아서 버린다**(직원 PATCH 는 200 이다). 코어 User 의
  `title`·`phoneNumbers`·`addresses`·`nickName`·work 가 아닌 이메일 등, enterprise 확장(`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:`)의
  `department`·`employeeNumber`·`manager` 등이다. 거절하지 않는 까닭은 PATCH 가 원자적이라(RFC 7644 §3.5.2) 이 속성 하나를 400 으로 거절하면 같은 요청의
  `active=false` 까지 반영되지 않아 퇴사자 권한이 남기 때문이다. 그래서 IdP 매핑에 이 속성들이 남아 있어도 같은 요청의 비활성화를 막지 않는다. 대신 응답에는 "반영되지 않았다" 는 흔적이 없다 —
  아래 메트릭으로 본다. PATCH 의 연산이 모두 이런 속성이면(예: Entra 의 `manager`) 락을 잡지 않고 지금 모습을 200 으로 돌려준다 — 저장하는 속성이 하나라도 섞이면(같은 값 포함) 락 안에서 처리한다.
- **RFC 에 없는 path 는 400 `invalidPath` 다** — 오타(`name.givenNmae`), 커스텀 확장(`urn:…:extension:<이름>:2.0:User:…`), URN 없는 enterprise 이름(`department`).
  커스텀 확장을 path 로 보내는 테넌트는 그 요청 전체가 실패하므로, Entra 는 `aadOptscim062020` 을 켜거나 커스텀 확장 매핑을 뺀다. 같은 키가 경로 없는 값으로 오면 무시한다.
- **이메일은 `emails`, `emails[type eq "work"]`, `emails[type eq "work"].value` 만 적용한다.** `emails[primary eq true].value` 처럼 저장된 이메일을 고르는 다른 필터나 필터 없는
  `emails.value` 는 적용하지 못해 받아서 버린다 — 그 모양으로 보내는 IdP 는 이메일 갱신이 조용히 빠진다(메트릭 `attribute=emails` 로 보인다).
- **받아서 버린 속성은 `scim_patch_ignored_total{attribute}` 로 본다**(Micrometer 이름 `scim.patch.ignored`). 태그는 RFC 이름(`phoneNumbers`, `manager`)이나 `other`(표에 없는
  경로 없는 값의 키)뿐이고 요청 문자열은 태그가 되지 않는다. 한 요청에서 이름마다 한 번 센다. 로그는 직원 PATCH 한 건에 DEBUG 한 줄이고 아이디와 속성 이름만 남는다 —
  값(전화번호·주소)은 개인정보라 남기지 않는다. WARN 이 아닌 까닭은 IdP 가 갱신마다 같은 연산을 다시 보낼 수 있어서다.

`members[].value`는 IdP 가 받은 `id` 다. 지금도 `IdNormalizer` 를 거치지만 UUID 에는 바꿀 글자가 없어 그대로다.
`members[].type`은 RFC 7643에서 선택 필드라 없을 수 있는데, 그때는 User로 단정하지 않고
현재상태에서 조직 → 직원 순으로 찾아 판정한다 — 조직 id 와 직원 id 는 네임스페이스가
달라 겹칠 수 있어서, 잘못 단정하면 IdP가 조직을 중첩하려던 요청이 엉뚱한 직원 소속 튜플이
된다. 판정은 요청에서 `type` 없는 아이디를 모아 **한 번에**(BatchGet, 키 100개씩) 한다 — 1,000명이어도 읽기는 스무 번
안팎이다. 조직도 직원도 없는 아이디는 직원으로 본다(IdP 는 받은 `id` 로만 가리키므로 정상 흐름에서는 생기지 않는다). **있는 직원으로 판정되면
로그가 없다**(Entra·Okta 의 정상 경로). 조직으로 추정했거나 없는 아이디가 있으면 요청당 경고 한 줄로, 몇 명인지와 아이디 앞
10개를 남긴다.

**중첩 조직과 순환.** 조직 A 의 멤버로 조직 B 를 넣는 연결이 순환을 닫으면(B 가 A 자신이거나 A 의 상위 조직이면) 그 연결의
멤버십은 저장하지만 OpenFGA 튜플은 쓰지 않고 **보류 목록**(`CYCLE_CUT` 파티션)에 적는다. 연결마다 경고 로그가 한 줄 남는다. 순환 검사는
새 연결의 부모에서 **위로** 올라가며 튜플 그래프 — 멤버 줄의 하위 조직 연결에서 "보류 목록 중 OpenFGA 에 없는 줄"을 뺀 것 —
를 본다. **먼저 저장된 연결이 이긴다.** 새 연결이 순환을 닫으면 새 연결이 보류되고, 조직 id 순서와는 상관이 없다.

하위 조직 연결을 지운 요청(조직 PATCH·PUT 의 하위 조직 빼기, 조직 삭제)은 커밋한 뒤 같은 락 안에서 보류 목록을 다시 본다. 순환이
풀린 연결은 튜플을 쓰고 목록에서 뺀다(INFO 로그) — 본부 ⊃ A ⊃ B 에서 B 를 A 위로 올리는 개편이 `PATCH B add A` → `PATCH A
remove B` 순서로 와도 둘째 요청 뒤에 롤업이 돌아온다. 멤버 줄이 없는 보류 줄은 지운다. 다시 보기가 실패해도 원래 요청은 성공하고,
목록은 다음 지우기 요청이나 재적재가 다시 본다. SCIM 재적재(`mode=tuples`)는 보류 목록을 처음부터 다시 정한다 — 어느 연결이
남을지가 바뀔 수 있다. `mode=wipe` 는 목록도 비운다. 순환을 검사하려고 펼친 조직이 1만 개를 넘는 요청(조직 계층이 너무 큼)은
400 `invalidValue` 다. 설계: `docs/superpowers/specs/2026-10-03-read-cost-org-graph-design.md`.

**오류 응답.** 모든 오류는 SCIM Error(`schemas`·`status`·`scimType`·`detail`, RFC 7644 §3.12)로 나간다 — 라우트 밖 요청과 Content-Type
불일치처럼 WebFlux 가 정한 오류도 같다. IdP 가 재시도할지 고쳐 보낼지는 상태 코드가 정한다.

| 상태 | 언제 | IdP 는 |
|---|---|---|
| 400 (`invalidSyntax`·`invalidPath`·`invalidValue`·`invalidFilter`·`noTarget`·`mutability`) | 요청이 틀렸다 | 고쳐 보낸다 |
| 404 | 없는 리소스·경로 | — |
| 405 + `Allow` | 경로가 받지 않는 메서드(`Allow` 는 그 경로가 받는 메서드, 이름순) | — |
| 409 `uniqueness` | `userName`·조직 `externalId` 가 겹친다 | — |
| 413 | 본문이 한도(기본 256KB, `spring.codec.max-in-memory-size`)를 넘었다 | 멤버를 PATCH 로 나눠 보낸다 |
| 415 | Content-Type 이 `application/scim+json`·`application/json` 이 아니다 | — |
| 501 | 지원하지 않는 기능 — Bulk, `/Me`, `/Schemas`, `/ResourceTypes`, 서버 루트 조회 | — |
| 503 + `Retry-After` | 일시 장애 — 다른 SCIM 쓰기가 락을 쥐고 있다(2초), 재적재가 락을 쥐고 있다(60초), OpenFGA·DynamoDB 장애·리스 상실·쓰기 차단·부분 실패(10초) | `Retry-After` 뒤에 같은 요청을 다시 보낸다 |
| 500 | 우리 버그 | 로그를 본다 |

`noTarget` 은 값 경로 필터가 가리킨 값이 없을 때와, `path` 없는 `remove` 일 때(무엇을 지울지 가리키지 않았다, RFC 7644 §3.5.2.2)다.
`Retry-After` 는 정수 초(RFC 9110 §10.2.3)로 올림하며 최소 1초다. 429 는 쓰지 않는다 — HTTP 에서 429 는 속도 제한이다. 503 의 `detail` 은 우리가
쓴 문구뿐이다. OpenFGA 서버 메시지·라이브러리 예외 문자열·락을 쥔 쪽의 용도 같은 내부 원인은 인증 없는 엔드포인트로 내보내지 않고 서버 로그에만 남긴다.

**500 은 버그에만 남는다.** 재시도하면 낫는 실패는 503, 요청이 틀렸으면 4xx 이고, 어느 쪽도 아닌 예기치 않은 예외만 500 과 ERROR 로그다. 튜플 일부만
반영된 **부분 실패도 503** 이다 — 상태는 이미 커밋됐지만 IdP 가 같은 요청을 다시 보내면 같은 최종 상태를 목표로 다시 계산해 나머지를 반영한다. 다만
IdP 가 503 과 `Retry-After` 를 어떻게 다루는지는 문서·추정이다(Entra 는 개별 오류를 다음 주기에 재시도한다고 문서화했고, Okta 의 5xx 반응은 문서에 없다).
AWS SDK(`SdkClientException`·스로틀링·5xx·SDK 가 재시도 가능하다고 표시한 것)와
OpenFGA SDK(`FgaError.isRetryable()` — 429, 501 을 뺀 5xx)의 예외는 각 SDK 가 정한 재시도 분류를 따라 알아본다. 그 밖에 원인 사슬에
I/O 실패·시간 초과가 있으면(Jackson 해석 실패는 빼고) 일시 장애다 — OpenFGA SDK 가 감싼 네트워크 실패가 여기서 잡힌다. 조건 실패·검증 오류·OpenFGA 가 요청 내용을
거절한 것(400)·인증 오류·store 없음·응답 해석 실패는 다시 보내도 같으므로 일시 장애가 아니다. 영구히 거절되는 튜플이 섞인 부분 실패도 지금은 503 이라 IdP 가 되풀이할 수 있다(서버 발급 UUID 아래에서는
OpenFGA 가 거절할 아이디가 거의 없다). 설계: `docs/superpowers/specs/2026-10-05-scim-error-signals-design.md`.

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

**쓰기 요청의 판단은 전부 락 안에서 일어난다.** 직원·조직을 읽고, 존재를 확인하고, `userName`·조직 `externalId` 중복을 확인하는 일을 모두 락을
잡은 뒤에 한다 — 동시에 온 PATCH 가 서로의 변경을 지우거나(비활성화가 되돌려져 퇴사자 권한이 되살아나는 것 포함) 방금 지운 직원을
되살리지 않는다. 단, 연산이 모두 저장하지 않는 속성인 직원 PATCH 는 우리 상태에 아무 말도 하지 않으므로 락 없이 지금 모습을 돌려준다(위 SCIM 절).
직원 삭제는 소속 조직의 이름표만 읽고 그 직원의 줄만 지운다 — 조직 크기와 무관하다.

**락을 못 잡았을 때의 동작은 두 경로가 다르다 — 의도적인 비대칭이다.**

| | 락을 못 잡았을 때 |
|---|---|
| SCIM 쓰기 | 서버 안에서 온 순서대로 줄을 서고, 차례가 된 요청 하나만 락을 시도한다. 다른 서버가 쥐었으면 짧고 무작위한 간격(10ms 에서 두 배씩 100ms 까지, full jitter)으로 다시 시도한다. `lock-acquire-timeout` 안에 못 잡으면 503. 쥔 쪽이 재적재·동기화면 다시 시도하지 않고 바로 503(`Retry-After` 60초) |
| 재적재 | 재시도 없이 즉시 409 |

SCIM 쓰기는 기계(IdP)가 자주 보내고 밀리초 단위로 짧게 쥐는 락과 경합하므로, 잠깐 기다려보는
편이 IdP에게 불필요한 503 재시도를 덜 시킨다. 재적재는 사람이 실행하고 드물며, 무엇보다
장부 전체를 훑어 지우는 작업이라 — 무엇이 돌고 있는지 모른 채 뒤에서
조용히 대기하는 대신 즉시 409로 "지금 다른 작업이 돈다, 확인하고 다시 실행하라"고 알리는
편이 낫다. 이 비대칭은 실수가 아니라 결정이다.

**같은 서버의 SCIM 쓰기는 줄을 선다(설계 2026-10-07 §3).** 앞 요청이 락을 반납하면 줄의 다음 요청이 곧바로 시도한다 — 예전처럼 200ms 고정 간격으로
다시 두드리느라 락이 비는 시간이 없고, 먼저 온 요청이 먼저 잡는다. DynamoDB 락 항목을 두드리는 것은 서버마다 많아야 하나다. 서버끼리는 순서를
보장하지 않는다. 전역 락 하나라 쓰기 처리량의 상한(락을 쥐는 시간의 역수)은 서버 수와 무관하다.

락 관련 설정 세 개(`dynamodb` 아래):

| 설정 | 의미 |
|---|---|
| `dynamodb.lock-ttl` (기본 30초) | 락 리스 길이. SCIM 쓰기 p99보다 한참 길어야 한다 — 짧으면 아직 일하는 중인데 만료돼 다른 인스턴스가 가져간다. 두 경로 모두에 적용된다 |
| `dynamodb.lock-acquire-timeout` (기본 3초) | **SCIM 쓰기 경로에만 적용된다.** 락 획득 대기 한도 — 서버 안 줄에서 기다린 시간과 다른 서버와 겨룬 시간이 모두 든다. 넘으면 503을 돌려주고(`Retry-After` 는 쥔 쪽이 SCIM 쓰기면 2초, 재적재·동기화면 재시도 없이 바로 60초) IdP가 재시도한다(재시도 시점에는 락이 풀린 상태 위에서 처리된다). 재적재는 이 설정을 전혀 보지 않는다 — 재시도 자체가 없어 즉시 409다 |
| `dynamodb.lock-renew-interval` (기본 10초) | 락을 쥔 동안 리스를 갱신하는 주기 — SCIM 쓰기·재적재·동기화 모두. TTL보다 충분히 짧아야 한다 |

**큰 변경도 락을 지키며 끝까지 간다.** 10만 명 조직 삭제처럼 오래 걸리는 SCIM 쓰기는 락을 쥔 동안 리스를 `lock-renew-interval`마다
갱신해 TTL(30초)을 넘겨도 끝난다. 그동안 다른 SCIM 쓰기는 503이고 IdP가 재시도한다. OpenFGA 쓰기 직전과 DynamoDB 커밋 직전에 리스를 늘
다시 확인하고(바뀐 튜플이 없어도), 확인이 실패하면 저장하지 않고 503이다 — 오래 멈춘 요청이 다른 인스턴스가 저장한 비활성화를 덮지 않는다.
IdP가 연결을 끊어도 그 변경은 커밋까지 마치고 락을 반납한다. 락 획득 응답을 잃어 SDK가 같은 요청을 다시 보내도 자기 락에 막히지 않는다.

**조직 삭제는 계산하지 않는다.** 조직 파티션을 한 번 읽어 멤버를 얻고, 상위 조직은 아이디만 읽는다. 그 조직을 언급하는 튜플을 Check 없이
"없으면 무시"로 지우고, DynamoDB는 묶어서(25개씩) 지우며 조직 META를 맨 마지막에 지운다 — 중간에 멈춰도 IdP의 DELETE 재시도가 남은 것을
마저 지운다. 멤버 전원 빼기·빈 교체도 빠지는 멤버는 Check·직원 읽기 없이 지운다.

**OpenFGA 요청은 동시에 보내되, 쓰기는 같은 단계 안에서만 동시에 보낸다.** Check·Write 묶음을 `openfga.request-concurrency`(기본 4)만큼 동시에 보낸다 —
Check 에는 단계가 없고, 쓰기 묶음만 아래 단계를 지킨다. 재적재·LDAP
동기화도 함께 빨라진다. OpenFGA가 버거우면 낮춘다. 차단기로 멈출 때 이미 나간 묶음은 결과를
기다려 세고 아직 안 나간 묶음만 보내지 않는다 — 락을 반납한 뒤에 늦게 떨어지는 쓰기가 없다. DynamoDB 묶음 요청(BatchGet·BatchWrite)은
처리 못 한 키를 다시 보내되 첫 요청을 포함해 5번까지 보내고(다시 보내기 4번, 100ms부터 두 배씩 쉰다), 그래도 남으면 실패한다.

**쓰기는 세 단계로 나가고 단계는 겹치지 않는다.** ① 조직의 상위 연결(`child`) 지우기, ② 직원 줄 — 한 직원의 지우기와 쓰기(옮기기·추가·빼기)를 같은 Write 요청에
담고, 한 요청에는 한도(`openfga.write-batch-size`, 100)까지 여러 직원이 들어간다, ③ 조직의 상위 연결 쓰기 순서다. 앞 단계의 묶음이 모두 끝난 뒤에야 다음 단계가 나간다. 인가 모델이 단조라서, 어느 시점에 멈춰도 앞 상태나 뒤 상태 어느 쪽도 주지 않는
권한을 가진 사람이 없다 — ①이 끝나면 조직 그래프는 (앞 ∩ 뒤)이고, ② 동안 각 직원은 줄이 모두 앞이거나 모두 뒤라 권한이 앞 이하이거나 뒤 이하이며, ③에서는 모든 직원이 이미 뒤에 있고 그래프는 뒤를
향해 자라기만 한다. 한 요청은 OpenFGA 가 원자적으로 반영하므로 조직을 옮기는 직원은 옛 조직에서 새 조직으로 한 번에 넘어간다(권한 공백도, 두 조직 동시 권한도 없다).
직원 한 명의 변경이 한도보다 많으면 그 직원만 여러 요청으로 나누는데, 그 직원의 지우기 조각은 ②에 두고 쓰기 조각은 ② 뒤·③ 앞에 따로 보낸다 — 지우기 조각이 모두 끝난 뒤에야
쓰기 조각이 나가고, ②에 실패가 있으면 쓰기 조각은 나가지 않으므로(아래) 옛 소속과 새 소속을 함께 갖는 순간이 없다.
이 보장은 **쓰기 한 번(`apply`) 안의 것이다.** 서로 다른 쓰기 사이에는 단계가 없다 — 재적재와 기록 전에 멈춘 뒤의 첫 LDAP 동기화는 있어야 할 줄을 먼저 다 쓰고 나서 장부의
나머지를 지우므로 그 사이 옮기는 직원이 두 조직의 권한을 함께 갖는다(인가 공백을 두지 않으려고 고른 틈이다). SCIM 커밋의 "보류 줄 → 멤버 줄 → 풀린 줄"도 서로 다른 쓰기다.

**앞 단계에서 줄이 하나라도 실패하면(차단기가 서지 않았어도) 뒤 단계는 지우기만 보내고 쓰기는 보내지 않는다.** 실패한 배치는 줄이 하나라도 실패한 배치다 — 거절(400)을
쪼개다 한 줄만 거절된 배치도 포함한다. 실패는 제 단계의 나머지를 막지 않는다. 뒤 단계의 지우기는 늘 보낸다 — 인가 모델이 단조라 지우기는 권한을 줄이기만 하고, 막으면 사용
중지·퇴사자의 회수가 한 회차 늦는다. 보내지 않은 쓰기는 "앞 단계 실패로 보내지 않음"으로 실패에 세고, 쓰기 한 번에 경고 한 줄(실패한 단계, 보내지 않은 쓰기 줄 수)을 남긴다.
차단기는 이것을 세지 않는다 — 실패 하나 뒤에 쓰기만인 묶음이 여럿 와도 `FAILED` 로 바뀌지 않는다. 차단기가 섰으면 차단기가 이긴다 — 남은 묶음은 지우기까지 "연속 실패로 보내지 않음"이고
차단기의 "보내지 않은 수"에 든다. 차단기가 서지 않았으면 그 회차는 실패가 담긴 결과로 끝나 실행 기록이 `PARTIAL` 이고, 차단기가 서면 지금처럼 중단되어 `FAILED` 다.
까닭: 남은 ① 지우기 위에 ② 직원 이동이 떨어지거나, 남은 ② 위에 ③이 아직 옮기지 못한 직원에게 권한을 주지 않게 한다. **비용이 있다.** 영구히 거절되는 줄 하나(예: 형식이 틀린 id)가
데이터를 고치기 전까지 매 동기화마다 뒤 단계의 부여를 막는다 — 첫 적재라면 하위 조직 연결 쓰기 전부, 곧 상위 조직으로의 롤업 전체다. 회수는 막지 않는다. 일부러 fail-closed 로 골랐다.
한계가 둘이다. 조직의 상위를 바꾸는 줄은 ①·③으로 갈려 한 요청이 아니라서 그 사이 구성원이 새 상위의 권한을 얻기 전에 옛 상위의 권한을
잠시 잃는다(줄어드는 쪽이라 새지 않는다). 직원 한 명의 변경이 `openfga.write-batch-size`(100)보다 많거나 OpenFGA 가 요청을 거절(400)해 쪼개 보내면 그 직원은 원자적이지 않다 —
나뉜 직원은 지우기 조각이 먼저 나가 잠시 옛 소속도 새 소속도 없을 수 있다(줄어드는 쪽이다).

**재적재가 도중에 리스를 잃으면 중단하고 `FAILED`로 기록한다.** 갱신(`lock-renew-interval`)이
실패했다는 것은 이미 다른 인스턴스가 락을 가져갔다는 뜻이다. 이미 나간 쓰기·지우기를 무를 방법은 없지만,
실행 기록은 사실대로 남긴다 — `SyncRun`은 운영자가 가진 유일한 신호이고,
반쯤 맞춘 장부 위로 남의 쓰기가 들어왔을지 모르는 실행을 `SUCCEEDED`로 남기면
"`mode=tuples`를 한 번 더 돌려야 한다"와 "할 일 없다"가 구별되지 않는다.

이 락은 완벽한 상호 배제를 보장하지 않는다(반납 자체의 실패, 커밋 직전 확인과 커밋 사이에 30초 넘게 멈추는 경우 등 좁은 틈이 있다) —
그 틈은 막지 못해도 **세기는 한다**. 아래 락 지표와 어긋남 지표가 그 역할이다.

| 지표 | 종류 | 의미 |
|---|---|---|
| `scim.lock.wait` | Timer | 락을 잡거나 포기하기까지 기다린 시간(서버 안 줄에서 기다린 시간 포함). 꼬리가 길어지면 `lock-acquire-timeout`을 다시 볼 때다 |
| `scim.lock.contended` | Counter | 한 번이라도 다른 쪽에 밀린 획득. 꾸준히 오르면 전역 락의 직렬화 비용이 실제로 발생하고 있다는 뜻이다(설계 §4.1의 재검토 신호) |
| `scim.lock.lease_lost` | Counter | 쥐고 있어야 할 리스를 잃었다 — 작업·쓰기 도중 갱신 실패, 쓰기 직전·커밋 직전 재확인 실패, 반납 실패. **응답에 흔적이 없거나(반납 실패) 503 뿐이다.** 0이 아니면 락이 TTL만큼 묶였거나 두 인스턴스가 겹쳤을 수 있다 |

**`scim.drift.detected`(Counter, 태그 `kind=extra|missing`)** — SCIM 쓰기 경로는 델타를
계산할 때 이미 OpenFGA에 `Check`를 던져 **실제 있는 튜플**을 얻는다. 여기에 상태(DynamoDB)가
요구하는 **있어야 할 튜플**을 나란히 두면, 둘이 다른 것 자체가 어긋남이다 — 별도 스캔 없이
쓰기 경로가 지나가면서 알려준다. `kind=extra`는 있어선 안 될 튜플(예: 퇴사자의 잔여 권한),
`kind=missing`은 있어야 하는데 빠진 튜플이다.

이 값이 계속 오르면(0이 아니면) **`POST /admin/sync/rebuild?mode=tuples`로 재적재를 실행하라**는
신호다 — 다만 이 지표는 "누군가 다시 건드린 리소스"에서만 드러난다. 아무도 건드리지 않는
어긋남까지 잡는 주기적 대조는 아직 없다(아래 follow-ups 참고).

**`scim.patch.ignored`(Counter, 태그 `attribute`)** — 직원 PATCH 가 받아서 버린 RFC 속성이다. 태그 `attribute` 는 RFC 이름 또는 `other` 다. 꾸준히 오르는 이름이 있으면 IdP 매핑에서 빼도 된다는 신호다.

## 불변 id 로 옮기기

튜플의 id 가 이름에서 불변 id 로 바뀌었다. 옛 id(`user:kim`)와 새 id(`user:<UUID>`)는 전혀 달라 기존 배포는 한 번 다시 시작해야 한다.
운영 배포 전이라 이관 코드는 만들지 않았다.

- **app-scim** — 기존 데이터의 id 는 `userName` 에서 온 값이라 쓸 수 없다. `POST /admin/sync/rebuild?mode=wipe&confirm=<테이블명>` 으로
  장부와 조직도를 비운 뒤 IdP 에서 프로비저닝을 처음부터 다시 건다(위 "⚠️ `mode=wipe`는 되돌릴 수 없다"). DynamoDB 테이블과 OpenFGA store 를 새로
  만들어도 된다.
- **app-ldap** — 첫 동기화에서 모든 id 가 바뀌어 옛 튜플 전부가 지울 대상이 되고 삭제 가드가 멈춘다. 첫 동기화를
  `POST /admin/sync/full?force=true` 로 한 번 넘긴다. 테이블과 store 를 새로 만들어도 된다.

권한을 묻는 앱이 옛 id 를 저장해 두었다면 위 "조회 API"로 새 id 를 다시 얻는다.

## 테스트

```bash
./gradlew test        # 규모 테스트를 뺀 전부 — 평소에 돌린다
./gradlew scaleTest   # 규모 테스트만 — 머지 전에 돌린다
```

**규모 테스트는 기본 `test` 에서 빠진다.** 5,000명 조직도와 Testcontainers 를 띄우는 17개 클래스(`@ScaleTest`
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

**id 가 바뀐 뒤의 테스트.** app-ldap 의 test 프로필은 이름 기반 id(groupOfNames `uid`/`cn`, DIT `uid`/`ou`)를 명시해 기존 e2e·규모 테스트가
그대로 돈다. 기본값 `entryUUID` 는 connector-ldap 의 `ImmutableIdentifierTest`, app-ldap 의 `LdapImmutableIdEndToEndTest`(groupOfNames)·
`LdapDitImmutableIdEndToEndTest`(DIT)·`LdapEntryUuidScaleTest` 가 본다(개명이 같은 id 라 삭제 가드에 걸리지 않는 것 포함). 규모 테스트는
groupOfNames 만 `entryUUID` 로 돈다 — DIT 의 `entryUUID` 는 e2e 까지만 있다. SCIM 요청 하네스(connector-scim 의 testFixtures)는 `ScimIdBook` 으로 OrgChart 의 id 와
서버가 발급한 id 를 번역한다. SCIM 에서는 IdP 가 받은 `id` 로만 참조할 수 있어, 멤버를 늦게 도착시키던 시나리오는 Entra 의 순서(빈 조직 → 직원 →
멤버 PATCH)로 다시 썼다.

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
