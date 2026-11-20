# 삭제 가드 멈춤 내역과 확인한 수로만 넘기는 force 설계 (2026-11-20)

온보딩 13 의 두 남김을 닫는다.
- 주: §3.9 "`force=true` 전에 무엇이 지워질지 볼 수 없다".
- 덤: §6.8 "app-ldap 에는 락 지표가 없다".

사용자 결정은 "추천대로"(2026-11-20)와 방향 요지 "진행"(2026-11-20)이다. 아래 결정은 모두 그 추천이다.

## 1. 문제

- **가드가 멈춘 회차가 주는 것은 수 두 개뿐이다.**
  - 실행 기록 메시지는 "삭제 대상 N건(기준 스냅샷 M건의 x%)이 … 초과했습니다" 한 줄이다(`DeletionGuard.evaluate`). 메시지 밖에 구조화된 칸이 없다(`SyncRun`, `SyncRunResponse`).
  - N 은 **줄** 수다. 런북은 "디렉터리 쪽 변경 수와 맞춰 보라"고 하지만 디렉터리가 세는 것은 **엔트리** 수다(12 §4.4). 한 직원이 조직 셋에 속하면 줄이 셋이라 둘이 맞지 않는다.
  - 무엇이 빠졌는지 — 퇴사인지, 검색 베이스 실수로 한 본부가 통째로 빠졌는지, 비활성 대량인지 — 를 가를 신호가 없다.
- **`force=true` 는 가드를 통째로 건너뛴다.**
  - `SyncTrigger.FORCED` 이면 비교 경로는 가드를 부르지 않는다(`FullSyncUseCase.비교해_맞춘다`). 장부 맞추기는 `DeleteCheck.NONE` 이다(`LedgerAlignment.align`).
  - 운영자가 메시지를 본 때와 `force` 를 건 때 사이에 디렉터리가 더 망가져도 그 상태대로 지운다. 확인한 것과 실행한 것을 잇는 값이 없다.
  - 확인한 값을 함께 받는 선례는 있다. SCIM `mode=wipe` 는 `confirm=<테이블명>` 이 맞지 않으면 400 이다(09 §9.3).
- **app-ldap 에 락 지표가 없다.**
  - `UseCaseConfig.syncJobs` 가 `LockObserver.NOOP` 을 넘긴다. LDAP 회차의 리스 상실(`작업 도중 리스 상실`)은 실행 기록 메시지와 ERROR 로그로만 보인다.
  - SCIM 의 `scim.lock.lease_lost`·`scim.lock.contended` 는 처음 오를 때 계열이 생긴다. 그래서 Prometheus `increase()` 가 기동 뒤 첫 상실을 증가로 세지 않는다. 회수 카운터는 같은 까닭으로 기동 때 0 으로 만든다(`ScimSyncMetrics` 생성자).
  - `SyncJobs` 는 락 획득의 **모든** 오류를 경합으로 센다(`doOnError(error -> acquireFinished(…, true))`). 그러나 DynamoDB 장애나 시도 한도 초과는 밀린 것이 아니다. 조건 실패만 `LockUnavailableException` 이다(`DynamoDbMutationLock`). SCIM 쓰기 쪽은 이미 플래그로 가른다(`IncrementalSyncUseCase`). 지금은 NOOP 이라 드러나지 않았다.

## 2. 결정

| # | 결정 | 이유 |
|---|---|---|
| H1 | **가드가 걸린 회차는 실행 기록에 멈춤 내역(`guardHold`)을 구조화해 남긴다.** 전부 보류든 일부 반영이든, 비교 경로든 장부 맞추기든 같다. 강제 회차가 허용 수를 넘어 다시 멈춘 경우도 같다 | 운영자가 판단하는 곳은 실행 기록이다. 메시지는 사람이 읽는 결론으로 두고, 기계가 읽을 내역은 따로 둔다 |
| H2 | **내역은 이번에 지우려던 줄 전체를 한 번 훑어 센다.** 판정하는 순간 쥔 두 집합 — 지울 줄과 방금 읽은 디렉터리 그림 — 만 쓴다. 더 읽는 것은 없다. 비용은 지울 줄 수에 비례한다 | 10만 명 규모에서 요청마다 전원을 읽지 않는다는 규칙을 지킨다. 그림은 id 로 찾는 맵이라 줄마다 종류를 가르는 것이 해시 조회 하나다 |
| H3 | **세 묶음으로 나눈다.**<br>① 줄 — 직원 소속(`direct_member`), 하위 조직 연결(`child`), 그 밖. 셋의 합이 전체다.<br>② 직원 — 그림에서 사라진 직원, 비활성 직원, 남아 있고 활성인데 소속 줄을 잃는 직원.<br>③ 조직 — 그림에서 사라진 조직, 남아 있는데 상위를 잃는 조직.<br>각 갈래는 서로 다른 id 의 수와, 사전순 앞에서부터 id 10개를 예로 담는다 | 묶음 ②③이 런북의 "엔트리 수"와 단위가 같다. 갈래가 원인을 가른다 — 사라진 직원·조직이 크면 검색 실수나 큰 퇴사, 비활성이 크면 정책·시계, 소속을 잃는 활성 직원이 크면 개편이다. 예의 id 는 관리 API 상세로 바로 찾아볼 수 있다 — 가드가 걸린 회차는 현재상태를 바꾸지 않는다 |
| H4 | **단건 조회에만 싣고 목록에는 싣지 않는다.** `GET /admin/sync/runs/{runId}` 응답에 `guardHold` 칸을 둔다(없으면 칸이 없다). `GET /admin/sync/runs` 목록에는 싣지 않는다 | 멈춘 배포는 매시 ABORTED 를 쌓는다. 목록 20줄마다 예 50개씩 실을 까닭이 없다. 목록의 ABORTED 를 보고 그 회차를 연다 |
| H5 | **같은 요약을 WARN 한 줄로도 남긴다.** 수만 담고 id 는 담지 않는다 | 로그 경보·검색으로 보는 운영자에게도 닿게 한다. 예의 id 는 실행 기록에 있다 |
| F1 | **`force=true` 는 `maxDeletes=N`(1 이상)과 함께여야 한다.** N 은 운영자가 내역을 보고 허용하는 지울 **줄** 수다. 동기화(`POST /admin/sync/full`)와 재적재(`POST /admin/sync/rebuild`) 모두 같다 | 확인한 것과 실행하는 것을 수 하나로 잇는다 |
| F2 | **강제 회차도 가드를 본다 — 비율은 보지 않고, 상한만 N 으로 바꾼다.** 설정의 가드를 꺼 두었어도 강제 회차는 N 을 본다. 비활성 회수를 떼어 다시 보는 규칙(설계 2026-10-20 §5.2)도 그대로다 — 넘으면 N 안의 비활성 회수만 반영하고 나머지는 보류한다 | 한 규칙이다 — "강제"는 가드를 끄는 것이 아니라 운영자가 정한 상한으로 바꾸는 것이다. 확인한 뒤 디렉터리가 더 망가지면 다시 멈춘다 |
| F3 | **요청 검사.**<br>• `force=true` 의 범위 검사(`admin.destructive`, 403)가 무엇보다 먼저다(지금 그대로).<br>• 그다음 `force=true` 인데 `maxDeletes` 가 없거나 1 미만이면 400.<br>• `force` 없이 `maxDeletes` 만 오면 400.<br>• 숫자가 아니면 스프링의 변환 실패로 400 | 설정에서 `max-deletes: 0` 은 "끔"이다. 요청에서 0 을 받으면 같은 수가 두 뜻이 된다. 없는 force 에 붙은 수는 실수다 — 조용히 무시하지 않는다 |
| F4 | **강제 회차의 결론 앞에 허용 수를 적는다** — `강제 실행 — 지울 줄 허용 N건`. 끝나서 결론을 낸 강제 회차는 모두 붙인다 | 트리거 `FORCED` 만으로는 운영자가 무엇을 허용했는지 남지 않는다 |
| F5 | **가드 문구의 끝을 바꾼다.** `강제 실행하려면 force=true 로 재요청하세요` → `강제 실행하려면 실행 기록의 guardHold 를 확인한 뒤 force=true 와 maxDeletes(허용할 지울 줄 수)로 재요청하세요` | 문구가 이제 틀린 요청을 가르친다. 강제 회차가 다시 멈춘 경우에도 같은 문구가 맞다 |
| L1 | **app-ldap 에 `sync.lock.wait`(Timer)·`sync.lock.contended`(Counter)·`sync.lock.lease_lost`(Counter)를 둔다.** 태그는 없다. `SyncMetrics` 가 `LockObserver` 를 구현하고 `UseCaseConfig.syncJobs` 가 NOOP 대신 그것을 넘긴다. 리스를 잃으면 WARN 도 남긴다(SCIM 과 같다) | SCIM 의 `scim.lock.*` 와 같은 모양이다. 이름 앞머리는 app-ldap 지표의 `sync.` 를 따른다 |
| L2 | **두 앱 모두 락 카운터 둘(`contended`·`lease_lost`)을 기동 때 0 으로 만든다** | 회수 카운터와 같은 까닭이다(Prometheus `increase()` 의 첫 값) |
| L3 | **`SyncJobs` 는 `LockUnavailableException` 일 때만 경합으로 센다.** 다른 오류도 기다린 시간은 남긴다 | 경합은 "다른 쪽에 밀렸다"는 뜻이다. 장애를 경합으로 세면 경합 경보가 장애에 울린다 |

## 3. 동작

### 3.1 멈춤 내역 (`GuardHold`)

**어디서 만드나.** 가드 판정이 걸렸을 때(`GuardDecision.aborted()`)만 만든다. 만드는 곳은 두 군데다.
- 비교 경로(`FullSyncUseCase.비교해_맞춘다`): 지울 줄 = `delta.toDelete()`.
- 장부 맞추기(`LedgerAlignment.가드로_본다`): 지울 줄 = 훑어 나온 후보 `stale`.

두 곳 모두 그 회차의 디렉터리 그림(`DirectorySnapshot`)을 쥐고 있다.

**어떻게 세나.** 지울 줄 하나마다 아래 규칙을 따른다.

| 줄 | 줄 묶음 | 직원 갈래 | 조직 갈래 |
|---|---|---|---|
| `(user:U, direct_member, group:G)` | 직원 소속 | U 가 그림에 없으면 **사라진 직원**. 있고 비활성이면 **비활성 직원**. 있고 활성이면 **소속을 잃는 직원** | G 가 그림에 없으면 **사라진 조직** |
| `(group:C, child, group:P)` | 하위 조직 연결 | — | C 가 그림에 없으면 **사라진 조직**, 있으면 **상위를 잃는 조직**. P 가 그림에 없으면 **사라진 조직** |
| 그 밖(다른 관계, 접두사가 다른 자리) | 그 밖 | — | — |

- 각 갈래는 **서로 다른 id** 를 센다. 같은 직원의 소속 줄이 셋이어도 1명이다.
- 직원 갈래 셋은 서로 겹치지 않는다. 조직의 "사라진"과 "상위를 잃는"도 겹치지 않는다 — C 가 그림에 있느냐로 가른다.
- **소속을 잃는 직원**에는 이동자가 든다. 옛 소속 줄이 지워지고 새 소속 줄이 쓰이는 사람이다. 이름이 "소속을 잃는"인 까닭은 지울 줄만 보기 때문이다.
- **사라진 직원·조직**에는 이번 회차가 건너뛴 엔트리(설계 2026-11-19, `sync.entries.skipped`)도 든다. 그 회차의 그림에 없기 때문이다.
- 예는 갈래마다 사전순(`String` 자연 순서)으로 앞에서 10개다. 10개보다 적으면 있는 만큼이다.
- 직원 id 는 `DirectoryUser.id` 다 — `externalId`(DN)가 아니다. 조직 id 는 조직코드다.

**모양.**

```json
"guardHold": {
  "lines":  { "total": 12000, "directMember": 11800, "child": 200, "other": 0 },
  "users":  {
    "gone":             { "count": 9500, "sample": ["E00001", "E00002", "…"] },
    "inactive":         { "count": 30,   "sample": ["…"] },
    "losingMembership": { "count": 2000, "sample": ["…"] }
  },
  "groups": {
    "gone":         { "count": 150, "sample": ["…"] },
    "losingParent": { "count": 20,  "sample": ["…"] }
  }
}
```

- `lines.total` 은 셋의 합이다(저장하지 않고 계산한다). 메시지의 "삭제 대상 N건"과 같다.
- 일부 반영 회차의 내역도 **지우려던 전체**다. 그 가운데 반영한 몫은 메시지에 있다("비활성 회수 a건만 반영하고 나머지 지우기 b건…"). 직원 갈래의 비활성 직원 수와 비교하면 된다.

**로그.** 내역을 만든 곳에서 WARN 한 줄을 남긴다. 수만 담는다. 예:
`삭제 가드 내역: 지울 줄 12000건(소속 11800·하위 조직 연결 200·그 밖 0) — 직원 사라짐 9500·비활성 30·소속 잃음 2000 / 조직 사라짐 150·상위 잃음 20`.
문구는 구현 때 정한다(테스트가 문구 전체를 단언하지 않는다).

**저장.**
- `SyncOutcome` 과 `SyncRun` 에 칸 `guardHold` 를 더한다. 없으면 null 이다. `SyncRun.finished` 가 결론의 칸을 옮긴다.
- DynamoDB 실행 기록 아이템에는 이름 `guardHold` 의 M 속성으로 둔다. 없으면 속성이 없다. 예는 S 의 L 이다(빈 L 도 된다). 수는 N 이다.
- 크기는 예 50개다. 최악은 조직 id 250바이트·직원 id 500바이트 한도에서 약 25KB 다. 아이템 한도 400KB 안이다.
- 운영 배포 전이라 옛 아이템은 이관하지 않는다. 속성이 없으면 null 로 읽는다.
- **저장소는 목록 읽기에서도 내역째 읽는다(판단).** 목록(`findRecent`)·실행 중(`findRunning`)·단건(`findById`) 모두 지금처럼 아이템 전체를 읽고, 칸은 응답 변환에서만 뺀다.
  - DynamoDB 는 읽기 용량을 **아이템 크기**로 매긴다. 그래서 `ProjectionExpression` 으로 칸을 빼도 용량은 줄지 않고 전송량만 준다.
  - 용량까지 줄이려면 내역을 별도 아이템으로 떼어야 한다. 실행 기록의 월 파티션 Query 가 그 아이템을 함께 줍지 않게 키를 다시 짜야 해서 이번 범위가 아니다.
  - 어림: 기본 식별 속성(`entryUUID`·`objectGUID`, 36자)이면 내역은 회차당 약 2KB 다. `sync.last.success` 게이지(`LastSuccessGauge`)는 인스턴스마다 1분에 한 번 이번 달 파티션을 최신부터 읽는다.
    - Query 에 `Limit` 이 없어(`Paginator.queryAll`) 첫 쪽(최대 1MB) 전체가 과금된다. 게이지는 첫 성공에서 멈추지만, 그 쪽은 이미 다 읽은 뒤다.
    - 그래서 이번 달 파티션에 쌓인 내역 바이트는 첫 쪽에 들어 있는 동안 1분마다 다시 읽힌다. 멈춤이 풀린 뒤에도 달이 바뀔 때까지 그렇다.
    - 상한은 인스턴스마다 1분에 약 128 RCU 다(1MB, 결과적 일관 읽기). 내역이 없으면 한 달치(약 720건, 약 200KB)로 약 27 RCU 다.
    - 첫 쪽에 성공이 없으면 다음 쪽도 읽는다. 쪽마다 같은 상한이다.
    - 멈춤은 사람이 풀어야 하는 상태이고 경보(`sync.guard.aborted`)가 울린다. 돈으로는 작다고 보고 받아들인다.
    - (처음 적은 어림 "최신부터 마지막 성공까지, 일주일 약 42 RCU·최악 약 500 RCU"는 쪽 단위 과금을 빠뜨린 것이라 최종 리뷰 뒤 고쳤다 — §9.4.)

**응답.**
- `SyncRunResponse` 에 `guardHold` 칸을 둔다. `@JsonInclude(NON_NULL)` 이다.
- 단건 조회(`GET /admin/sync/runs/{runId}`)와 202 응답이 쓰는 변환은 칸을 싣는다. 202 는 RUNNING 이라 늘 없다.
- 목록(`GET /admin/sync/runs`)이 쓰는 변환은 칸을 싣지 않는다.
- app-scim 의 실행 기록에는 가드가 없어 칸이 늘 없다. 그래도 app-scim 의 목록도 같은 목록 변환을 쓴다 — 두 앱의 같은 경로가 같은 규칙이다(지금 두 앱 모두 `SyncRunResponse::from` 을 쓴다).

**결론까지 옮기기.**
- 비교 경로는 결론을 그 자리에서 만든다. 전부 보류는 `SyncOutcome.aborted`, 일부 반영은 `revokedOnly`, 일부 반영 중 차단기로 멈추면 `stopped` 다. 세 결론 모두에 내역을 붙인다.
- 장부 맞추기는 판정이 `TupleReconciler` 의 콜백 안에서 일어나고, 결론은 그 뒤에 만들어진다. 콜백이 만든 내역을 그 `align` 호출의 지역 그릇에 담고, 결론(`held`·`revokedOnly`·`stopped`)에 붙인다.
  - 판단: `TupleReconciler`(`DeleteVerdict`·`Reconciliation`)는 바꾸지 않는다. 내역은 가드의 일이고, 맞추기 기계는 사유 문자열만 안다.
- 회차가 예외로 끝나면(결론이 없다) 내역은 WARN 로그에만 남는다.

### 3.2 확인한 수로만 넘기는 `force`

**요청.**

| 요청 | 결과 |
|---|---|
| `POST /admin/sync/full` | 지금 그대로(MANUAL) |
| `POST /admin/sync/full?force=true&maxDeletes=12500` | FORCED, 상한 12500 의 가드 |
| `POST /admin/sync/full?force=true` | 400 |
| `POST /admin/sync/full?force=true&maxDeletes=0` | 400 |
| `POST /admin/sync/full?maxDeletes=12500` | 400 |
| `POST /admin/sync/rebuild?force=true&maxDeletes=12500` | FORCED_REBUILD, 상한 12500 의 가드 |
| (위 어느 것이든) `force=true` 인데 `admin.destructive` 없음 | 403(400 보다 먼저) |

400 문구 두 가지:
- `force=true 에는 maxDeletes(허용할 지울 줄 수, 1 이상)가 함께 와야 합니다`
- `maxDeletes 는 force=true 와 함께만 씁니다`

재적재의 옛 `mode` 파라미터 400 은 그대로다. 순서는 범위(403) → `maxDeletes`(400) → `mode`(400)다.

**숫자가 아닌 `maxDeletes` 는 403 보다 먼저 400 이다(판단).**
- `maxDeletes` 는 스프링이 정수로 바꿔 받는다. 그 변환은 컨트롤러 본문의 범위 검사보다 먼저 일어난다. 그래서 `admin.destructive` 가 없는 호출의 `force=true&maxDeletes=abc` 도 400 이다.
- 지금의 `force=abc` 와 같은 길이다. "403 이 400 보다 먼저"는 위 표의 의미 검사(없음·1 미만·force 없이 옴)에만 해당한다.
- 문자열로 받아 직접 바꾸면 순서를 뒤집을 수 있다. 그러나 형식 오류는 권한과 상관없는 요청의 잘못이고, 알려 줘도 새는 것이 없다.

**강제 회차의 가드.**
- 정책은 `(enabled=true, 비율 보지 않음, maxDeletes=N)` 이다. 비율을 끄는 방법은 구현 때 정한다. 예: `minBaseline` 을 `Integer.MAX_VALUE` 로 두면 비율 판정에 닿지 않는다.
- 판정은 `DeletionGuard.evaluate(deletes, inactive, baseline, label)` 그대로다. 그래서 N 을 넘으면 N 안의 비활성 회수만 반영하거나 전부 보류한다. 결론은 ABORTED 이고 H1 의 내역이 붙는다.
- 메시지는 지금의 상한 문구다 — `… 상한 N건을 초과했습니다. 강제 실행하려면 …`.
- 비교 경로와 장부 맞추기(기준선 없음·기록 중·재적재) 모두 같은 가드 하나를 쓴다. `LedgerAlignment.align` 은 `boolean force` 대신 **이번 회차에 쓸 가드**를 받는다. 그래서 강제 회차의 장부 맞추기도 `DeleteCheck.NONE` 이 아니다.

**결론의 허용 수.**
- 강제 회차의 결론 앞에 `강제 실행 — 지울 줄 허용 N건` 을 `withNote` 로 붙인다. 장부 맞추기의 사정(`기준선 의심 …`·`기준선 없음 …`)보다 바깥이다.
  - 예: `강제 실행 — 지울 줄 허용 12500건 / 기준선 없음(첫 회차 또는 새 테이블) — 장부를 훑어 맞춤 / …`.
- 변경 없음도 붙는다 — `강제 실행 — 지울 줄 허용 12500건 / 변경 없음`.

**유스케이스 입구.**
- 강제는 "허용 수가 있는 실행" 하나로만 들어온다.
  - `FullSyncUseCase`: 일반 `start(SyncTrigger, …)` 는 `SCHEDULED`·`MANUAL` 만 받고 `FORCED` 는 거절한다(`IllegalArgumentException`). 강제는 허용 수를 받는 입구로 따로 연다.
  - `RebuildUseCase` 도 `start(boolean force, …)` 를 일반 입구와 허용 수를 받는 입구로 가른다.
  - 허용 수는 1 이상만 만들 수 있는 작은 값이다. 0 이하면 만들 때 `IllegalArgumentException` 이다 — 컨트롤러의 400 과 별개로 core 가 스스로 지킨다.
- 정확한 이름과 시그니처는 계획이 정한다.

**문구(F5).** `DeletionGuard.evaluate` 의 끝 문장을 바꾼다. 바꾼 문장은 비교 경로·장부 맞추기·강제 회차 모두에 나온다.

### 3.3 app-ldap 락 지표

| 지표 | 종류 | 언제 |
|---|---|---|
| `sync.lock.wait` | Timer | 동기화·재적재의 작업 락 획득이 성공하거나 포기할 때, 걸린 시간 |
| `sync.lock.contended` | Counter | 획득이 `LockUnavailableException` 으로 끝났을 때 — 다른 인스턴스가 쥐고 있었다 |
| `sync.lock.lease_lost` | Counter | 작업 도중 리스를 잃었을 때(`작업 도중 리스 상실`) |

- 둘 이상 띄운 배포는 매시 스케줄러가 모든 인스턴스에서 돈다. 그래서 `sync.lock.contended` 는 매시 (인스턴스 수 − 1)만큼 오르는 것이 정상이다. 경보는 `lease_lost` 에 건다.
- README 경보 예에 `increase(sync_lock_lease_lost_total[1h]) > 0` 을 더한다.
- `ScimSyncMetrics` 도 `scim.lock.contended`·`scim.lock.lease_lost` 를 생성자에서 0 으로 만든다(L2).
- `SyncJobs` 의 획득 관찰(L3): 성공은 `(경과, false)`, `LockUnavailableException` 은 `(경과, true)`, 그 밖의 오류는 `(경과, false)` 다.
  - SCIM 의 동기화형 작업 가운데 락을 잡는 재적재·초기화도 `SyncJobs` 를 지나므로 `scim.lock.contended` 의 뜻이 같이 바로잡힌다. (아카이빙은 락을 잡지 않고 `lock.peek()` 만 본다 — 처음 적은 "재적재·아카이빙·초기화"는 틀려 고쳤다, §9.3.)

## 4. 시험

### 4.1 core — 내역(`GuardHoldTest`, 순수 함수)

- 줄 묶음 셋의 수와 합. `direct_member`·`child`·다른 관계 한 줄씩이면 1·1·1, 합 3.
- 직원 갈래: 그림에 없는 직원 → 사라짐. 비활성 → 비활성. 활성 → 소속 잃음. 같은 직원의 소속 줄 셋은 1명이다.
- 조직 갈래: `child(C,P)` 에서 C 가 없으면 사라짐, 있으면 상위 잃음. P 가 없으면 사라짐. 소속 줄의 G 가 없으면 사라짐. 같은 조직이 여러 줄에 나와도 한 번이다.
- 예: 15명이 사라지면 수 15, 예는 사전순 앞 10개다. 넣은 순서와 상관없다.
- 빈 지울 줄 → 모든 수 0, 예는 빈 목록.

### 4.2 core — 가드와 강제

- `DeletionGuardTest`: 끝 문장이 F5 의 새 문구다(세 꼴 모두).
- 강제 가드: 비율 90% 이고 N 이상의 줄은 진행한다. N+1 줄이면 상한 문구로 중단한다. 설정 가드가 꺼져 있어도 N 을 본다. N 을 넘었지만 비활성 회수가 N 안이면 일부 반영이다.
- 허용 수 값: 0·음수는 `IllegalArgumentException`, 1 은 된다.

### 4.3 core — 유스케이스

- 지금의 `FORCED` 테스트(약 20곳)는 허용 수 입구로 옮긴다. 허용 수는 그 테스트의 지울 줄 수 이상으로 준다.
- 비교 경로:
  - 가드가 걸리면(전부 보류·일부 반영 모두) 실행 기록에 내역이 있다. 직원 갈래의 수가 그림대로다.
  - 가드를 지나면 내역이 없다.
- 장부 맞추기(기준선 없음·기록 중·재적재):
  - 전부 보류·일부 반영 모두 내역이 있다.
  - 강제 재적재가 N 을 넘으면 ABORTED 이고, 장부의 후보를 지우지 않는다(지금은 `NONE` 이라 다 지운다).
- 강제 회차의 결론 앞에 `강제 실행 — 지울 줄 허용 N건` 이 있다. 변경 없음도 같다.
- `FullSyncUseCase.start(SyncTrigger.FORCED, …)` 는 `IllegalArgumentException` 이다.
- `SyncJobs`:
  - 획득이 `LockUnavailableException` 이면 관찰자가 `contended=true` 를 받는다.
  - 다른 오류(예: `IllegalStateException`)면 `false` 를 받는다.

### 4.4 storage-dynamodb — 왕복

내역이 있는 실행 기록을 저장하고 읽으면 같다 — 빈 예 목록을 포함해서. 내역이 없으면 속성이 없고 null 로 읽는다.

### 4.5 admin-api·app-ldap — API

- `SyncRunResponse` 직렬화:
  - 내역이 있으면 3.1 의 JSON 모양이고 `lines.total` 이 합이다.
  - 없으면 칸이 없다.
  - 목록 변환은 내역이 있어도 칸이 없다.
- 컨트롤러(웹 테스트):
  - F3 표의 400 다섯 갈래, 403 이 400 보다 먼저.
  - `force=true&maxDeletes=N` 이 허용 수 입구에 N 을 넘긴다. 재적재도 같다.

### 4.6 app-ldap·app-scim — 지표

- `SyncMetrics` 를 `SimpleMeterRegistry` 로 만들면 `sync.lock.contended`·`sync.lock.lease_lost` 가 0 으로 있다.
  - `acquireFinished(d, true)` 는 timer 1회와 contended 1 이다.
  - `leaseLost` 는 lease_lost 1 이다.
- `ScimSyncMetrics` 를 만들면 `scim.lock.contended`·`scim.lock.lease_lost` 가 0 으로 있다.
- 결선: app-ldap 컨텍스트에서 `SyncJobs` 가 받은 관찰자가 `SyncMetrics` 다. 어떻게 확인할지는 구현 때 정한다.

## 5. 바뀌는 곳 (어림)

| 모듈 | 파일 | 바뀌는 것 |
|---|---|---|
| core | `model/GuardHold`(새) | 내역 값과 `of(deletes, directory)`, 요약 문장 |
| core | `model/SyncOutcome`, `model/SyncRun` | 칸 `guardHold`, `withGuardHold` |
| core | `guard/DeletionGuard`, 새 허용 수 값 | F5 문구, 강제 가드 |
| core | `usecase/FullSyncUseCase`, `LedgerAlignment`, `RebuildUseCase` | 내역 붙이기, 허용 수 입구, `align` 이 가드를 받음, 허용 수 문구 |
| core | `usecase/SyncJobs` | L3 |
| storage-dynamodb | `DynamoDbSyncRunRepository`(필요하면 `Attrs`) | M 속성 쓰기·읽기 |
| admin-api | `SyncRunResponse`(+내역 응답 레코드) | 칸, 목록 변환 |
| app-ldap | `AdminSyncController`, `SyncMetrics`, `UseCaseConfig` | F3, L1 |
| app-scim | `ScimSyncMetrics`, 실행 기록 목록(같은 응답을 쓰면) | L2 |
| 문서 | README, 온보딩 02·05·07·09·11·12·13·15 와 `force=true` 를 적은 나머지(01·03·08·10·14·온보딩 README) | `force=true&maxDeletes=N`, 내역 읽는 법, 락 지표, 13 §3.9·§6.8 을 고친 항목으로, 기준 커밋 |

`force=true` 는 문서에 약 100곳 나온다(README 16, 12 16, 09 15, 05 14, 07 14, 02 9 등). 모두 현재 동작으로 고친다.

## 6. 이 설계가 말할 수 없는 것

- **예는 사전순 앞 10개다 — 무작위 표본이 아니다.**
  - 한 접두어(같은 본부 코드)에 쏠릴 수 있다. 판단의 주 신호는 갈래의 수다. 예는 "정말 그 사람이 빠졌나"를 상세로 확인하는 데 쓴다.
  - 무작위로 뽑으면 같은 회차를 두 번 보고 다른 예가 나와 비교가 어렵다. 그래서 고정된 순서를 골랐다.
- **상위를 잃는 조직 아래 몇 명이 롤업을 잃는지는 세지 않는다.** 옛 조직 그래프를 따라 걸어야 한다. 상위를 잃는 조직의 수와 예로 대신한다.
- **`maxDeletes` 는 줄 수다 — 직원 수가 아니다.** 내역의 `lines.total` 을 보고 정한다.
- **확인과 실행 사이 틈을 없애지는 않는다 — N 으로 묶는다.**
  - 그 사이 디렉터리가 바뀌어도 지울 줄이 N 안이면 넘어간다.
  - 딱 맞춘 N 은 그 사이 퇴사 몇 건으로도 다시 멈춘다. 문서는 "본 수에 작은 여유(예: 몇 %)"를 권한다. 정확한 여유는 운영자의 판단이다.
- **강제 회차가 예외로 끝나면 허용 수가 실행 기록에 남지 않는다.** 트리거 `FORCED`·`FORCED_REBUILD` 와 요청한 행위자(`requestedBy`)는 남는다. 실제로 지운 줄은 감사 이력에 남는다.
- **일부 반영 회차의 내역은 지우려던 전체다.** 다음 회차의 지울 줄은 반영한 비활성 몫만큼 적고, 그 사이 디렉터리 변화만큼 다르다.
- **목록 읽기는 내역째 읽는다.** 이번 달 파티션의 첫 쪽(최대 1MB)이 1분마다 과금되므로, 내역 바이트는 멈춤이 풀린 뒤에도 달이 바뀔 때까지 읽힌다. 3.1 "저장"의 어림대로다. 실제 DynamoDB 로 재지 않았다.
- **비교 경로의 지울 줄에는 불확실 줄(U − D)이 든다.** 장부에 실제로 없을 수도 있는 줄이다. 내역은 그것도 지울 줄로 센다 — 가드가 세는 것과 같다.
- **실제 Prometheus·실제 다중 인스턴스에서 지표를 보지 않았다.** 0 으로 미리 만든 것은 `SimpleMeterRegistry` 로만 확인한다. `increase()` 의 첫 값 동작은 이전 설계(회수 카운터)의 판단을 따른다.
- **10만 명 규모의 내역 계산 시간은 재지 않는다.** 시험은 벽시계를 단언하지 않는다. 비용은 지울 줄 수에 비례하고 집합 복사 한 번 수준이라는 논증뿐이다.

## 7. 왜 다른 길을 안 갔나

| 버린 길 | 까닭 |
|---|---|
| 메시지 문자열에 내역을 덧붙이기 | 기계가 못 읽는다. 목록 응답에 그대로 실려 무거워진다. 결론 문장과 내역이 섞인다 |
| 로그로만 남기기 | 운영자는 실행 기록을 보며 판단한다. 로그 보존·검색 권한이 없는 운영자도 있다 |
| 지울 줄 전체를 저장하고 페이지로 보여 주기 | 회차마다 수만 줄을 저장해야 한다(아이템 한도를 넘어 별도 저장이 필요하다). 새 API 와 보관 규칙이 생긴다. 판단에 필요한 것은 갈래별 수와 확인용 예다 |
| 미리 돌려 보기(dry-run) 엔드포인트 | 10만 명을 한 번 더 읽는다. 미리 본 결과와 실행 사이 틈도 그대로다 — 멈춘 회차가 이미 같은 내역을 계산했다 |
| `force` 는 지금대로 두기 | 확인한 것과 실행한 것 사이 틈이 남는다. 13 §3.9 의 "잘못 넘기면 전원의 권한이 지워질 수 있다"가 그대로다 |
| 멈춘 회차 번호(`runId`)를 받아 같은 집합일 때만 넘기기 | 집합(또는 그 해시)을 저장해야 한다. 매시 퇴사·입사로 집합이 조금씩 달라져 늘 어긋난다 |
| 허용 수를 직원 수로 받기 | 가드는 줄을 센다. 직원 수로 받으면 가드와 다른 단위를 하나 더 만든다 |
| 강제 회차는 비율도 보기 | 큰 개편은 비율을 넘는 것이 정상이다 — 그래서 멈췄고 운영자가 확인했다. 비율을 남기면 강제가 다시 멈출 뿐이다 |
| 롤업 영향(아래 몇 명) 세기 | 옛 그래프를 걸어야 한다. 비교 경로에는 옛 그래프가 스냅샷 줄로만 있어 걷는 비용이 크다. 상위를 잃는 조직의 예로 상세에서 확인할 수 있다 |

## 8. 검증

- 머지 전 메인 세션이 전체 `cleanTest test` 와 `cleanScaleTest scaleTest` 를 돌린다.
- 서브에이전트는 자기 모듈 테스트만 돌린다.

## 9. 결과 (구현 후 기록)

### 9.1 머지 전 검증

메인 세션이 차례로 돌렸다(Gradle 은 한 번에 하나).

| 무엇 | 트리 | 결과 |
|---|---|---|
| `./gradlew cleanTest test` | 코드 `0b102e3` | 2,638건 통과, 실패·오류·건너뜀 0 (10분 29초). 앞 슬라이드(건너뛴 엔트리 지표)의 2,585건보다 53건 많다 |
| `./gradlew cleanScaleTest scaleTest` | 코드 `0b102e3` | 89건 통과, 실패·오류·건너뜀 0 (46분 18초) |
| `:app-ldap:test --tests AdminSyncControllerTest` | 최종 `a553dba` | 통과(수정 물결의 서브에이전트) |
| `:app-ldap:scaleTest --tests LdapDeletionGuardScaleTest` | 최종 `a553dba` | 4건 통과 — L12-b 가 실제 스택(LDAP → DynamoDB → 단건 API)에서 내역을 단언한다 |

최종 리뷰 뒤의 수정 물결은 시험 파일 둘과 문서만 바꿨다(`src/main` 없음). 그래서 전체 두 벌을 다시 돌리지 않고, 바뀐 시험 둘만 다시 돌렸다(판정 R7).

### 9.2 바뀐 곳

| 커밋 | 무엇 |
|---|---|
| `628a47b` | `GuardHold`(줄 셋·직원 셋·조직 둘, 사전순 예 10개, 수만 담은 `summary()`), `SyncOutcome`·`SyncRun` 의 칸, 6인자 보조 생성자 |
| `fe5b99e` | 비교 경로(aborted·revokedOnly·stopped)와 장부 맞추기(held·commit, 지역 `AtomicReference`)에 내역을 붙이고 WARN 한 줄 |
| `5debc81` | `ForceAllowance`, `DeletionGuard.forced`(비율 끔·상한 N), F5 문구, `FullSyncUseCase.start`/`startForced`, `RebuildUseCase.start`/`startForced`, `LedgerAlignment.align` 이 가드를 받음(`DeleteCheck.NONE` 제거), F4 문구, 컨트롤러 `허용_수를_정한다`(403 → 400 → mode 400), 기존 시험 약 20곳과 규모 시험 L13 |
| `bcdfa3f` | DynamoDB `guardHold` M 속성 쓰기·읽기, `GuardHoldResponse`, `SyncRunResponse.listed`, 두 앱 목록이 `listed` |
| `0b102e3` | `SyncJobs` 는 `LockUnavailableException` 만 경합, app-ldap `SyncMetrics implements LockObserver`(`sync.lock.*`), 두 앱 락 카운터 기동 때 0, `UseCaseConfig` 결선과 결선 시험 |
| `fb8d64e`·`182e13d` | README·온보딩 01~05·07~15·README — `force=true&maxDeletes=N`, 내역 읽는 법(12 §4.4 런북), 락 지표, 13 §3.9·§6.8 을 "고친 것" 표로, 새 13 §3.11·§6.10 |
| `b1e8dc7`·`a553dba` | 최종 리뷰 반영(§9.4) |

### 9.3 구현 중 판정

- **R1 — 규모 시험 L13 도 옮긴다(사전 점검).** 계획의 과제 3 파일 목록에 `LdapDeletionGuardScaleTest` 가 없었다. `?force=true` 만 보내면 F1 로 400 이 된다. L13 은 `maxDeletes=추가로지운수` 로 보낸다 — 단일 소속 직원이라 줄 수와 같다.
- **R2 — `FullSyncUseCase.start` 는 `SCHEDULED`·`MANUAL` 밖은 모두 거절한다.** 계획은 "FORCED 면 거절"이었지만 §3.2 의 "SCHEDULED·MANUAL 만 받고" 를 따랐다. 지금 다른 트리거를 넘기는 호출자는 없다.
- **R3 — 감사 기록 실패 메모는 허용 수 문구보다 바깥이다.** `AuditRecorder` 가 결론이 정해진 뒤에 붙인다. 그래서 `감사 기록 N건 실패 / 강제 실행 — 지울 줄 허용 N건 / …` 이 된다. 감사 메모는 기록에 관한 것이라 그대로 두고 문서(README·05·09·15)에 한 줄 적었다.
- **R4 — 온보딩 04 데이터 모델도 고쳤다.** 계획의 목록에 없었다. 실행 기록 아이템 속성 표가 거기에만 있다.
- **R5 — §3.3 의 두 사실을 고쳤다.** 문서 과제가 코드에서 확인했다.
  - SCIM 아카이빙은 락을 잡지 않고 `lock.peek()` 만 본다. 그래서 L3 로 뜻이 바로잡히는 것은 재적재·초기화다.
  - app-ldap 의 `acquireOnce` 에는 시도 한도가 없다(`null`). 그래서 "시도 한도 초과는 경합이 아니다"는 app-ldap 에서는 생기지 않는 경우다. SCIM 쓰기 쪽에만 해당한다.
- **R6 — 시험만 바꾼 커밋은 온보딩 머리의 `기준 커밋:` 을 옮기지 않는다.** 머리는 동작을 가리킨다. 그래서 `0b102e3` 그대로다.
- **R7 — 수정 물결 뒤에는 바뀐 시험만 다시 돌린다.** §9.1 대로다. 운영 코드와 다른 시험은 녹색이던 `0b102e3` 그대로다.
- F4 문구 도우미는 `FullSyncUseCase` 의 패키지 정적 메서드이고 `RebuildUseCase` 도 부른다. 가드를 고르는 한 줄(`allowance == null ? guard : DeletionGuard.forced(allowance)`)이 두 유스케이스에 있다. 각각 한두 줄이라 그대로 두었다.

### 9.4 최종 리뷰와 수정

브랜치 전체 리뷰(가장 강한 모델)는 "머지 가능 — scaleTest 녹색과 §9 를 전제로" 였다. Critical·Important 는 없었다.
- 강제 회차가 지우는 길(비교 경로, 장부 맞추기·재적재의 `DeleteCheck` 콜백)이 모두 상한 N 인 같은 가드를 지난다. 확인한 N 을 넘어 지우는 길이 없다.
- 가드가 걸린 결론 다섯 꼴 모두에 내역이 붙어 단건 응답까지 간다.

수정 물결 하나로 고친 것:
- **L12-b 가 내역을 단언한다** — `lines.total`·`lines.directMember`·`users.gone.count` 가 `지울건수`, 예 크기 min(10, 지울건수), `sample[0]` 이 사전순 첫 직원. 실제 스택에서 가드가 멈추는 회차는 이것뿐이다.
- **재적재의 `maxDeletes` 400 이 `mode` 400 보다 먼저임을 고정** — 둘을 바꾸면 `$.detail` 이 달라 실패한다(F3 순서).
- **목록 읽기 비용의 모델을 고쳤다**(13 §6.10, 11, 이 설계 §3.1·§6) — 처음 어림은 "마지막 성공까지 읽는다"였다. 실제로는 Query 에 `Limit` 이 없어 첫 쪽(최대 1MB)이 1분마다 과금된다. 그래서 내역 바이트는 멈춤이 풀린 뒤에도 달이 바뀔 때까지 읽힌다(상한 약 128 RCU/분/인스턴스, 전에는 약 27).
- 문서 문구 셋 — 05 장부 맞추기 도식의 "+ 멈춤 내역", README·11 의 `sync.lock.contended` 를 "다른 쪽(다른 인스턴스, 또는 같은 인스턴스의 다른 작업)"으로, 13 머리말.

수정 물결의 범위 재리뷰는 일곱 항목 모두 반영, 새로 깨진 것 없음이었다.

### 9.5 남긴 것

- **`GuardHold` 는 갈래 전체를 정렬해 앞 10개를 뽑는다** — O(k log k)(k 는 서로 다른 id 수)다. 10만 명에서도 수십 ms 라 그대로 둔다. 필요하면 크기 10 의 우선순위 큐로 바꾼다.
- **저장된 `guardHold` 의 `ids` 에 S 가 아닌 원소가 있으면 읽기가 실패한다**(`List.copyOf` 의 NPE). 앱은 S 만 쓴다. 손으로 고친 아이템에서만 생기고 운영 배포 전이라 그대로 둔다.
- **사소한 것(머지 전에 고치지 않음).**
  - 시험:
    - 모양이 다른 두 줄을 한 시험에서 함께 단언한다.
    - 그림에 있는 P·G 가 `groupsGone` 에 들지 않는지 보지 않는다.
    - 장부 경로의 차단기 중단과 가드 통과 시 null 을 따로 보지 않는다.
    - 강제 비교 경로가 N 을 넘고 비활성 회수만 반영하는 유스케이스 시험이 없다(가드 단위 시험과 반영 코드로 성립).
    - app-scim 목록 시험이 없다.
    - `findRunning` 의 내역 읽기를 보지 않는다.
    - `contended=false` 의 지표 시험이 없다.
  - 코드 모양: 컨트롤러가 MANUAL 을 계산된 변수로 넘긴다. import 순서, 시험 안의 FQN.
  - 문서: 06 의 SCIM 지표 행에 "기동 때 0" 이 없다(11·README 에는 있다). 15 "바뀐 결정 모아 보기" 한 칸의 문장.
- §6 의 말할 수 없는 것은 그대로다.
  - 실제 Prometheus·다중 인스턴스에서 지표를 보지 않았다.
  - 10만 명 규모의 내역 계산 시간은 재지 않았다.
  - 읽기 비용은 실제 DynamoDB 로 재지 않았다.

## 10. 범위 밖

- SCIM — 삭제 가드가 없다. 단건 삭제는 의도된 것이다.
- 내역의 지표화(갈래별 게이지 등) — `sync.guard.aborted` 와 실행 기록으로 충분하다.
- 강제 회차가 예외로 끝났을 때 허용 수를 남기기 — §6.

## 11. 확인할 것 (구현 때 사실로 정한다)

- storage-dynamodb 테스트가 실행 기록 왕복을 어디서 보는지(DynamoDB Local 또는 가짜). 그 자리에 4.4 를 더한다.
- `force=true` 를 적은 문서 문장 가운데 슬라이드 기록(설계 문서)이 아닌 온보딩·README 만 고친다. 옛 설계 문서는 그때의 기록이라 고치지 않는다.

## 12. 구현 전 점검

빌더 점검(2026-11-20)이 질문 둘을 냈다. 둘 다 짓는 것을 바꾸지 않는 작은 것이라 직접 정했다.

| 질문 | 판단 | 적은 곳 |
|---|---|---|
| 목록 읽기에서 내역을 저장소 단계(`ProjectionExpression`)에서 뺄 것인가, 응답에서만 뺄 것인가. `LastSuccessGauge` 가 1분마다 마지막 성공까지 읽는다 | 응답에서만 뺀다. projection 은 읽기 용량을 줄이지 않는다. 용량을 줄이려면 별도 아이템이 필요하고, 그것은 범위 밖이다. 어림을 남긴다 | 3.1 "저장", §6 |
| 숫자가 아닌 `maxDeletes` 에서 403 과 400 중 무엇이 먼저인가 | 스프링 변환의 400 이 먼저다(지금의 `force=abc` 와 같다). "403 먼저"는 의미 검사에만 해당한다 | 3.2 "요청" |
