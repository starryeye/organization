# 조직/직원 관계 동기화 서버 설계

작성일: 2026-08-14

## 1. 개요

외부 디렉터리(LDAP, SCIM)로부터 조직·직원 정보를 받아 **OpenFGA에 인가 관계(튜플)로 반영**하고, 그 전체 상태를 **DynamoDB에 보관**하는 서버.

두 소스는 성격이 정반대다.

- **LDAP**: pull 모델. 서버가 하루 1회 전체를 읽어온다. 변경분을 알 수 없으므로 **직전 스냅샷과 diff**를 계산해 델타를 만든다.
- **SCIM**: push 모델. IdP가 변경 건별로 요청을 보낸다. 델타가 이미 주어진 셈이다.

이 둘을 커넥터로 분리하고, 내부 비즈니스 로직은 **공통 모델**만 다룬다.

### 목표

- LDAP 전체 동기화(스케줄 + 수동)와 SCIM 실시간 반영을 하나의 코드베이스로 처리
- 두 커넥터를 추상화해 동기화 파이프라인 후반부를 완전히 공유
- 스냅샷 기반 diff로 OpenFGA 쓰기를 최소화하고, 실패를 자동 복구
- 인스턴스를 LDAP용 / SCIM용으로 나누어 실행

### 비목표 (이번 범위 밖)

- 인증/인가 (SCIM 엔드포인트 보호, 관리 API 보호)
- 멀티 테넌시
- 직원/조직 조회 admin API — **다음 사이클**
- SCIM 필터 문법(`filter=userName eq "..."`) 및 페이징
- 자유로운 조건 검색

### 전제

한 조직도는 **하나의 소스로만** 동기화한다. LDAP 인스턴스와 SCIM 인스턴스를 같은 DynamoDB 테이블·같은 OpenFGA store에 대해 동시 운용하는 것은 지원하지 않는다. 두 소스를 함께 쓰려면 테이블과 store를 분리한다.

---

## 2. 결정 사항 요약

| 항목 | 결정 | 근거 |
|---|---|---|
| 프로젝트 성격 | 인증만 제외한 중간 수준 | 재시도·안전장치·이력·메트릭은 포함, 인증은 제외 |
| 모듈 구조 | Gradle 멀티모듈 (7개) | 컴파일 타임에 의존 방향이 강제되어 추상화 경계가 유지됨 |
| 저장소 | DynamoDB Local | 단일 테이블 설계 |
| 검색 범위 | 직원 아이디 / 조직코드 / 조직명 | 앞의 둘은 GetItem, 조직명은 GSI1 `GROUP_INDEX` (§6.1) |
| 튜플 식별자 | 직원 아이디, 조직코드만 | 조직명은 개편 시 바뀌므로 튜플에 넣지 않는다 (§4.3) |
| 필드 매핑 | 설정 가능, 관행적 기본값 | LDAP 조직명은 `description`, SCIM 조직코드는 `Group.externalId` |
| 테넌시 | 단일 테넌트 | |
| FGA 모델 | `direct_member` / `child` 분리 | 롤업 방향이 정확하고 SCIM `members`와 1:1 매핑 |
| LDAP 매핑 | `groupOfNames` / `DIT` 두 전략 모두 지원 | 설정으로 선택 |
| SCIM 범위 | 핵심 CRUD + PATCH | 필터/페이징 제외 |
| 스냅샷 | 현재상태와 분리, TTL 7일 | 현재상태는 "사실", 스냅샷은 "OpenFGA에 반영된 것" |
| 정합성 | OpenFGA 먼저 → 성공분만 스냅샷 커밋 | 실패가 다음 sync diff에서 자동 재시도됨 |
| 안전장치 | 삭제 임계치 가드 (기본 30%) | 필터 오류·부분 응답으로 전직원 권한 소실 방지 |
| SCIM 스냅샷 | 하루 1회 아카이빙만 (diff 미사용) | 감사·수동복구용 |
| FGA 호출 | 쓰기는 **Write/Delete만**. `Check`는 제한 없음 | **열거 API(`Read`/`ListObjects`)만 금지.** `Check`는 점 조회라 열거를 대체하지 못하므로 아래 설계들을 바꾸지 않는다 — 동기화 경로에서도 쓸 수 있다 |
| storeId/modelId | 앱이 다루지 않음 | store-name으로 런타임 해석, model id는 생략(서버가 최신 사용) |
| SCIM 이력 | SyncRun 미기록 | 요청 단위 이력 폭증 방지. 로그/메트릭만 |
| 관리 API | 4종 포함 | 수동 sync / 가드 강제 / rebuild(2방식) / 실행이력 조회 |
| 통합 테스트 | Testcontainers | DynamoDB Local, OpenFGA 공식 이미지 |
| LDAP 테스트 | UnboundID in-memory | 로컬 수동 확인은 docker-compose OpenLDAP |

### 감수하는 트레이드오프

열거 API(`Read`/`ListObjects`)를 쓰지 않으므로 **드리프트를 전수로 감지할 수 없다.** 누군가 OpenFGA를 직접 수정하면 스냅샷과 실제가 어긋나도 전체 대조가 불가능하고, diff는 계속 스냅샷 기준으로만 계산된다. 이를 되돌리는 수단이 `rebuild`(store 재생성 모드)다.

다만 `Check`는 허용되므로 **표본 검증은 가능하다** — 스냅샷에서 튜플 N개를 뽑아 `Check`로 확인하면 전수 열거 없이 확률적으로 어긋남을 잡는다. 아직 구현하지 않았다.

> **정정 이력 (2026-08-19).** 이 문서와 실행 완료된 계획서들은 한동안 이 제약을 "Write/Delete만 사용, Check는 테스트 전용"으로 잘못 적고 있었다. 실제 제약은 **열거 API만 금지**이며 `Check`는 동기화 경로를 포함해 어디서든 쓸 수 있다. 스펙과 코드 주석은 바로잡았고, 이미 실행된 계획서(`2026-08-14-foundation-and-ldap-sync.md`, `2026-08-15-scim-connector.md`)는 당시 실행 기록이므로 그대로 둔다 — 그 문서들의 해당 문장은 이 절이 대체한다. 잘못된 제약이 실제로 묶은 설계는 없었다. `Check`는 점 조회라 열거를 대체하지 못해, 스냅샷 기준선·diff·삭제 가드·rebuild는 어느 쪽 해석에서도 동일하다.

---

## 3. 아키텍처

### 3.1 핵심 아이디어 — 두 커넥터가 만나는 지점은 `TupleDelta`

입력 인터페이스를 억지로 하나로 묶지 않고, **출력을 통일**한다.

```
LDAP  : fetchAll() → 목표 튜플집합 ──┐
                     직전 스냅샷과 diff │
                                       ├─→ TupleDelta ─→ RelationTupleWriter ─→ OpenFGA
SCIM  : 변경요청 → 현재상태 로드 ─────┘                   └─→ DynamoDB (현재상태 + 스냅샷)
        → 상태 갱신 → 영향 튜플 재계산
```

`TupleDelta` 이후의 파이프라인은 두 인스턴스가 **동일한 코드**를 쓴다. 비즈니스 로직은 LDAP도 SCIM도 모른다.

### 3.2 모듈 구조

```
organization/
├── settings.gradle
├── build.gradle                 # 공통 플러그인/버전/Lombok/테스트 설정
├── docker-compose.yml
├── core/
├── storage-dynamodb/
├── authz-openfga/
├── connector-ldap/
├── connector-scim/
├── app-ldap/
└── app-scim/
```

| 모듈 | 책임 | 의존 |
|---|---|---|
| `core` | 도메인 모델, 포트 인터페이스, 유스케이스, `TupleMapper`, `TupleDiff`, `DeletionGuard` | reactor-core만 (스프링 컨텍스트 없음) |
| `storage-dynamodb` | `DirectoryStateRepository`, `TupleSnapshotRepository`, `SyncRunRepository` 구현 | core, AWS SDK v2 |
| `authz-openfga` | `RelationTupleWriter` 구현. store 해석, 모델 부트스트랩, 배치 분할, 재시도 | core, OpenFGA SDK |
| `connector-ldap` | `DirectorySnapshotSource` 구현. 두 매핑 전략 | core, Spring LDAP |
| `connector-scim` | SCIM 2.0 라우터·핸들러, DTO ↔ 도메인 변환 | core, WebFlux |
| `app-ldap` | 부트. core + storage + authz + connector-ldap + 스케줄러 + 관리 API | 위 4개 |
| `app-scim` | 부트. core + storage + authz + connector-scim + 아카이빙 스케줄러 | 위 4개 |

의존 방향은 항상 `app-*` → `adapter-*` → `core`. `core`는 어떤 어댑터도 모른다.

### 3.3 기술 스택

- Spring Boot 3.3.x / Java 17 / WebFlux
- Lombok
- AWS SDK v2 **`DynamoDbAsyncClient` (저수준)** — 단일 테이블에 PK/SK를 오버로딩해 여러 아이템 타입을 담으므로, 타입당 테이블 스키마를 가정하는 Enhanced Client보다 저수준 클라이언트 + 명시적 매퍼가 더 단순하다
- OpenFGA Java SDK (`dev.openfga:openfga-sdk`) — `CompletableFuture` 기반이므로 `Mono.fromFuture`로 감싼다
- Spring LDAP — 블로킹 프로토콜이므로 `Schedulers.boundedElastic()`로 격리
- UnboundID LDAP SDK (테스트)
- Testcontainers (DynamoDB Local, OpenFGA)
- JUnit 5 + AssertJ

**Lombok과 record 사용 방침**: 불변 값 객체(`RelationTuple`, `DirectoryUser`, `TupleDelta` 등)는 `record`. 필드가 많거나 빌더가 필요한 것(`SyncRun`, 설정 프로퍼티), 스프링 빈(`@RequiredArgsConstructor`, `@Slf4j`)은 Lombok을 쓴다.

---

## 4. 도메인 모델과 포트 (`core`)

### 4.1 공통 도메인 모델

```java
public record DirectoryUser(
    String id,            // 직원 아이디 (정규화된 안정 식별자, 튜플에 사용)
    String externalId,    // LDAP DN 또는 SCIM externalId (원본 보관)
    String userName,
    String displayName,
    String email,
    boolean active
) {}

public record DirectoryGroup(
    String id,            // 조직코드 (튜플에 사용)
    String externalId,
    String displayName,   // 조직명 (튜플에 절대 사용하지 않음 — §4.3)
    Set<MemberRef> members
) {}

public record MemberRef(MemberType type, String id) {}   // USER | GROUP

public record DirectorySnapshot(
    Map<String, DirectoryUser> users,
    Map<String, DirectoryGroup> groups
) {}
```

### 4.2 튜플 모델

```java
public record RelationTuple(String user, String relation, String object) {}

public record TupleDelta(Set<RelationTuple> toWrite, Set<RelationTuple> toDelete) {
    public boolean isEmpty() { return toWrite.isEmpty() && toDelete.isEmpty(); }
}
```

### 4.3 식별자와 필드 매핑

도메인 모델의 `DirectoryUser.id`는 **직원 아이디**, `DirectoryGroup.id`는 **조직코드**, `DirectoryGroup.displayName`은 **조직명**이다.

#### 왜 조직명은 튜플에 들어가면 안 되는가

조직명은 개편 때마다 바뀐다. "개발본부"를 "플랫폼본부"로 개명했을 때 튜플에 이름을 썼다면 `group:개발본부` 관련 튜플을 전부 지우고 다시 써야 한다. 산하 직원이 500명이면 튜플 500개 재작성이고, 그 사이 인가 질의가 깨지고, 개명 전후로 감사 이력이 끊긴다. 코드를 쓰면 `displayName` 속성 하나만 바뀌고 튜플은 손대지 않는다.

**따라서 `TupleMapper`는 `id`(직원 아이디 / 조직코드)만 사용한다.** 조직명은 DynamoDB에만 존재한다.

#### 소스 필드 매핑

모두 설정으로 교체 가능하며, 아래는 관행적 기본값이다.

| 개념 | LDAP `group-of-names` | LDAP `dit` | SCIM 2.0 |
|---|---|---|---|
| 직원 아이디 | `uid` | `uid` | `User.userName` |
| 조직코드 | `cn` (groupOfNames) | `ou` | `Group.externalId` → 없으면 `Group.id` |
| 조직명 | `description` | `description` → 없으면 `ou` | `Group.displayName` |
| 직원 표시명 | `displayName` → 없으면 `cn` | 동일 | `User.displayName` → 없으면 `name.formatted` |
| 이메일 | `mail` | `mail` | `User.emails[primary].value` |

두 프로토콜의 비대칭에 주의할 점이 있다.

- **LDAP 그룹/OU에는 표시명 전용 표준 속성이 없다.** `displayName`은 `inetOrgPerson`(사람)에만 있다. 그래서 `groupOfNames`/`organizationalUnit`의 `description`을 조직명으로 쓰는 것이 관행이다(`cn=DEV001`, `description=개발본부`). 커스텀 스키마를 쓰는 환경을 위해 `group-name-attribute`로 교체할 수 있게 한다.
- **SCIM `Group`에는 코드와 이름을 나눌 칸이 없다.** 필드가 `id`, `externalId`, `displayName`, `members` 넷뿐이다. Okta/Azure AD가 `externalId`로 소스 시스템의 식별자를 보내므로 이를 조직코드로 채택한다. `POST /Groups`에 `externalId`가 있으면 그 값을 리소스 `id`로 발급하고, 없으면 IdP가 보낸 `Group.id`를 쓰고, 그것마저 없을 때만 UUID를 발급한 뒤 경고 로그를 남긴다(이후 IdP는 발급된 `id`로 호출하므로 일관성은 유지되지만, 코드가 우리 쪽에서 만들어진 값이 된다). `Group.id`를 건너뛰고 바로 UUID로 가면, IdP가 이미 알고 있는 `id`를 다시 보냈을 때 같은 조직에 새 코드를 발급해 중복 조직을 만든다.
- 사번(`employeeNumber`)을 직원 아이디로 쓰고 싶은 환경을 위해 LDAP은 `user-id-attribute`로 교체 가능하다. SCIM에서는 Enterprise User 확장(`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`)의 `employeeNumber`를 읽어야 하는데, **이번 범위에서는 확장 스키마를 파싱하지 않는다.** 필요해지면 매핑 설정에 추가한다.

#### 정규화

OpenFGA object id에는 파싱을 깨는 문자를 쓸 수 없다 — `:`(타입 구분자), `#`(userset 구분자), `*`(와일드카드), 공백, `,`. LDAP DN(`cn=김철수,ou=백엔드,dc=example,dc=com`)을 그대로 쓸 수 없으므로, 위 표의 속성값을 뽑은 뒤 `IdNormalizer`가 **금지 문자 `[\s:#*,\\]`만** `_`로 치환한다.

허용 목록이 아니라 금지 목록을 쓰는 이유는 **한글 조직코드를 보존하기 위해서**다. `[A-Za-z0-9._@\-]` 허용 목록을 쓰면 `개발본부`가 `____`가 되어 서로 다른 조직이 같은 id로 뭉갠다.

원본 DN(LDAP) 또는 IdP 식별자(SCIM)는 `externalId`에 보관한다.

치환 후 충돌이 발생하면 해당 엔트리를 스킵하고 경고 로그를 남긴다. 동기화 전체를 실패시키지 않는다.

### 4.4 포트

#### `DirectorySnapshotSource` — 외부 디렉터리에서 전체 상태를 읽는다

```java
public interface DirectorySnapshotSource {
    Mono<DirectorySnapshot> fetchAll();
}
```

- 구현: `connector-ldap`만. `app-scim`에는 이 빈이 없다.
- 호출자: `FullSyncUseCase`
- 페이지 단위로 나눠 읽고, 블로킹 호출이므로 `boundedElastic`에서 실행한다.
- **이 인터페이스에는 증분이라는 개념이 없다.** LDAP이 pull 모델이라는 사실이 여기에 박혀 있다.

#### `RelationTupleWriter` — 델타를 인가 시스템에 반영한다

```java
public interface RelationTupleWriter {
    Mono<TupleWriteResult> apply(TupleDelta delta);
    Mono<Void> resetStore();   // rebuild(store 모드) 전용
}

public record TupleWriteResult(
    Set<RelationTuple> written,
    Set<RelationTuple> deleted,
    List<TupleFailure> failures
) {
    public boolean hasFailure() { return !failures.isEmpty(); }
}

public record TupleFailure(RelationTuple tuple, String reason) {}
```

- 구현: `authz-openfga`
- 호출자: `FullSyncUseCase`, `IncrementalSyncUseCase` — 유일한 쓰기 경로
- 동작:
  1. 부팅 시 store-name으로 `ListStores` → 없으면 `CreateStore`
  2. `authorization-model.fga` 해시가 바뀌었으면 `WriteAuthorizationModel`
  3. 델타를 배치(기본 100개)로 쪼개 `Write` 호출 — OpenFGA 트랜잭션 모드 한계
  4. 배치 실패 시 지수 백오프 3회 재시도
- **`written`/`deleted`가 결과에 필요한 이유**: "OpenFGA 먼저, 성공분만 스냅샷 커밋" 원칙의 실행부다. 배치 5개 중 3개만 성공하면 성공한 것만 새 스냅샷에 들어가고, 다음 sync의 diff가 실패분을 자동으로 다시 잡는다. 별도 재시도 큐나 상태머신이 필요 없다.
- `read`, `check`, `listObjects` 없음. `storeId`/`modelId`는 이 모듈 밖으로 새어나가지 않는다.

#### `DirectoryStateRepository` — 현재 조직도의 사실

```java
public interface DirectoryStateRepository {
    Mono<DirectoryUser>  findUser(String userId);
    Mono<DirectoryGroup> findGroup(String groupId);
    Mono<Void> saveUser(DirectoryUser user);
    Mono<Void> saveGroup(DirectoryGroup group);          // 멤버십 포함 교체
    Mono<Void> deleteUser(String userId);
    Mono<Void> deleteGroup(String groupId);

    Flux<String> findGroupIdsContaining(MemberRef ref);  // 역참조

    Mono<Void> replaceWith(DirectorySnapshot snapshot);  // LDAP full sync
    Mono<DirectorySnapshot> loadAll();                   // 아카이빙
}
```

- 튜플이 아니라 **도메인 상태**를 담는다.
- SCIM에게 필수 전제다. SCIM은 "그룹 X에 김철수 추가" 같은 부분 정보만 오므로, 델타를 만들려면 X의 현재 멤버를 알아야 한다. `DELETE /Users/kim`이 오면 김철수가 속한 모든 그룹을 찾아야 하므로 `findGroupIdsContaining`이 필요하고, GSI1이 이를 받친다.
- LDAP에게는 `replaceWith(snapshot)` 한 번. 내부적으로 기존 키를 읽어 사라진 엔트리는 삭제하고 나머지는 upsert한다.

#### `TupleSnapshotRepository` — OpenFGA에 반영된 튜플의 기록

```java
public interface TupleSnapshotRepository {
    Mono<TupleSnapshot> findLatest();
    Mono<Void> save(TupleSnapshot snapshot);       // TTL 부여 + 포인터 갱신
    Flux<SnapshotMeta> listRecent(int days);
    Mono<TupleSnapshot> findById(String snapshotId);
    Mono<Void> reset();                            // rebuild 전용: 전체 삭제 + 포인터 초기화
    Mono<Integer> purgeExpired();
}

public record TupleSnapshot(String id, Instant createdAt,
                            SyncSource source, Set<RelationTuple> tuples) {}
```

- **`DirectoryStateRepository`와의 차이**: 전자는 도메인 상태("백엔드팀에 김철수가 있다"), 후자는 튜플 상태(`user:kim / direct_member / group:backend`). 같은 사실의 다른 표현이지만 **시점이 다르다.** 현재상태는 항상 최신이고, 스냅샷은 마지막으로 OpenFGA에 성공 반영된 시점이다.
- OpenFGA read API를 쓰지 않으므로 **이것이 OpenFGA 상태를 대신하는 유일한 기록**이다.
- LDAP: `findLatest()`가 diff의 좌변.
- SCIM: diff에는 쓰지 않고 하루 1회 아카이빙만.

#### `SyncRunRepository` — 동기화 1회의 실행 이력

```java
public interface SyncRunRepository {
    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger);
    Mono<Void> finish(SyncRun run, SyncOutcome outcome);
    Flux<SyncRun> findRecent(int limit);
}
```

```java
@Builder
public record SyncRun(String runId, SyncSource source, SyncTrigger trigger,
                      Instant startedAt, Instant finishedAt, SyncStatus status,
                      int writtenCount, int deletedCount, int failureCount,
                      String snapshotId, String message) {}

enum SyncSource  { LDAP, SCIM }
enum SyncTrigger { SCHEDULED, MANUAL, FORCED, REBUILD, ARCHIVE }
enum SyncStatus  { RUNNING, SUCCEEDED, PARTIAL, ABORTED, FAILED }
```

- **SCIM push 요청은 기록하지 않는다.** 요청 단위 이력이 폭증하기 때문. SCIM 인스턴스에서 기록하는 것은 하루 1회 스냅샷 아카이빙 배치(`trigger=ARCHIVE`)뿐이다.
- `ABORTED`가 핵심이다. 삭제 가드가 발동하면 OpenFGA를 건드리지 않고 이 상태로 기록하며, `message`에 `"삭제 대상 412건(전체의 68%)이 임계치 30%를 초과"` 같은 사유가 들어간다. 사람이 강제 실행을 승인할 판단 근거가 된다.
- `PARTIAL`은 `TupleWriteResult.hasFailure()`가 true일 때. 다음 sync가 자동 재시도하므로 실패가 아니라 부분 성공이다.

### 4.5 포트 사용 관계

```
FullSyncUseCase (app-ldap)          IncrementalSyncUseCase (app-scim)
  ├─ DirectorySnapshotSource          ├─ (SCIM 요청이 입력)
  ├─ TupleSnapshotRepository  ←diff   ├─ DirectoryStateRepository ←현재멤버
  ├─ DeletionGuard                    ├─ RelationTupleWriter
  ├─ RelationTupleWriter              └─ DirectoryStateRepository ←갱신
  ├─ DirectoryStateRepository
  ├─ TupleSnapshotRepository  ←저장   SnapshotArchiveUseCase (app-scim)
  └─ SyncRunRepository                ├─ DirectoryStateRepository ←loadAll
                                      ├─ TupleSnapshotRepository  ←저장
                                      └─ SyncRunRepository
```

---

## 5. OpenFGA 인가 모델

`authz-openfga/src/main/resources/authorization-model.fga`

```
model
  schema 1.1

type user

type group
  relations
    define direct_member: [user]
    define child: [group]
    define member: direct_member or member from child
```

### 5.1 튜플 매핑 규칙 (`TupleMapper`)

두 커넥터가 공유하는 유일한 변환 규칙이다.

| 도메인 사실 | 튜플 (user, relation, object) |
|---|---|
| 그룹 G의 멤버가 유저 U | `(user:U, direct_member, group:G)` |
| 그룹 G의 멤버가 그룹 C | `(group:C, child, group:G)` |

`member`는 파생 relation이라 튜플을 쓰지 않는다.

### 5.2 검증

`Check(user:kim, member, group:개발본부)`가 다음 튜플들로 true가 된다.

```
(group:백엔드팀, child, group:개발본부)
(user:kim, direct_member, group:백엔드팀)
```

### 5.3 순환 참조

LDAP이나 SCIM이 순환(A가 B의 자식이면서 B가 A의 자식)을 만들 수 있다. `TupleMapper`가 그룹 그래프를 DFS로 검사해 순환을 발견하면 해당 간선을 제외하고 경고 로그를 남긴다. 동기화 전체를 실패시키지 않는다.

---

## 6. DynamoDB 테이블 설계

테이블 `organization` — PK/SK + GSI1(GSI1PK/GSI1SK) 하나.

| 아이템 | PK | SK | GSI1PK | GSI1SK | 주요 속성 |
|---|---|---|---|---|---|
| 직원 | `USER#<empId>` | `META` | `USER_INDEX` | `<userName>` | externalId, userName, displayName, email, active, updatedAt |
| 조직 | `GROUP#<orgCode>` | `META` | `GROUP_INDEX` | `<displayName>` | externalId, displayName, updatedAt |
| 멤버십(유저) | `GROUP#<gid>` | `MEMBER#USER#<uid>` | `MEMBER#USER#<uid>` | `GROUP#<gid>` | addedAt |
| 멤버십(하위조직) | `GROUP#<gid>` | `MEMBER#GROUP#<cid>` | `MEMBER#GROUP#<cid>` | `GROUP#<gid>` | addedAt |
| 스냅샷 메타 | `SNAPSHOT#<sid>` | `META` | `SNAPSHOT_INDEX` | `<createdAt ISO>` | source, tupleCount, expiresAt |
| 스냅샷 튜플 | `SNAPSHOT#<sid>` | `TUPLE#<user>\|<rel>\|<obj>` | – | – | expiresAt |
| 최신 포인터 | `SNAPSHOT_POINTER` | `LATEST` | – | – | snapshotId |
| 실행 이력 | `SYNCRUN#<yyyy-MM>` | `<startedAt ISO>#<runId>` | – | – | source, trigger, status, counts, message, expiresAt |

`snapshotId` 형식: `<yyyyMMdd'T'HHmmss>-<SOURCE>` (예: `20260814T030000-LDAP`)

### 6.1 접근 패턴

| 필요 | 쿼리 | 사용처 |
|---|---|---|
| 직원 아이디로 조회 | `GetItem PK = USER#<empId>, SK = META` | admin API |
| 조직코드로 조회 | `GetItem PK = GROUP#<orgCode>, SK = META` | admin API |
| 조직 + 소속 멤버 전체 | `PK = GROUP#<orgCode>` (SK 전체 — META와 MEMBER가 한 번에) | admin API, SCIM |
| 어떤 멤버가 속한 조직들 | GSI1 `GSI1PK = MEMBER#USER#<empId>` | SCIM 삭제, admin API |
| 조직명 prefix 검색 | GSI1 `GSI1PK = GROUP_INDEX AND begins_with(GSI1SK, "개발")` | admin API |
| 조직명 부분일치 / 조직 전체 목록 | GSI1 `GSI1PK = GROUP_INDEX` Query → 앱에서 `contains` 필터 | admin API |
| 직전 스냅샷 로드 | 포인터 읽고 → `PK = SNAPSHOT#<sid>, SK begins_with TUPLE#` | LDAP diff |
| 최근 스냅샷 목록 | GSI1 `GSI1PK = SNAPSHOT_INDEX`, ScanIndexForward=false | 감사 |
| 최근 실행 이력 | `PK = SYNCRUN#<이번달>`, ScanIndexForward=false. 부족하면 지난달까지 | 관리 API |
| 전체 직원 열거 | GSI1 `GSI1PK = USER_INDEX` | `loadAll`, `replaceWith` |
| 전체 조직 열거 | GSI1 `GSI1PK = GROUP_INDEX` | `loadAll`, `replaceWith` |

**Scan은 쓰지 않는다.** 직원·조직 META 아이템에 각각 `USER_INDEX` / `GROUP_INDEX` 파티션 키를 달아 GSI1 Query로 전체를 열거한다. Scan은 같은 테이블에 있는 스냅샷 튜플(수만 건 가능)까지 읽으므로 열거 용도로는 부적절하다. 멤버십은 조직별 `PK = GROUP#<orgCode>` Query로 모으며, 조직 수만큼의 Query가 발생하지만 제한된 동시성으로 병렬 실행한다.

**`GROUP_INDEX` 파티션에 대하여.** 조직 전부가 한 파티션에 몰리므로 이론상 핫 파티션이다. 조직은 직원보다 한두 자릿수 적어(대기업도 보통 수천 개) 조직 수가 수만을 넘기 전에는 문제가 되지 않는다. 그 규모에 도달하면 검색엔진을 붙여야 하며, 그것은 그 시점의 문제다.

**조직명 부분일치는 DynamoDB가 못 하는 일이다.** `GROUP_INDEX` 파티션을 Query로 훑어(Scan이 아니라 Query라 조직만 읽는다) 앱에서 필터하는 것이 이 설계의 답이다. `GSI1SK = displayName` 정렬 덕분에 prefix 검색은 인덱스만으로 처리된다.

이 인덱스는 admin API를 만들 때 필요하지만 **키는 지금 넣는다.** 나중에 추가하면 기존 조직 아이템 전부에 GSI 키를 백필해야 한다.

### 6.2 쓰기 순서와 원자성

스냅샷 저장은 **포인터를 마지막에** 갱신한다.

```
1. 튜플 아이템 BatchWriteItem (25개 단위)
2. 스냅샷 메타 저장
3. SNAPSHOT_POINTER 갱신   ← 마지막
```

중간에 죽으면 포인터가 여전히 직전 스냅샷을 가리키므로 다음 sync가 정상 동작한다. 고아가 된 부분 스냅샷은 `purgeExpired()`가 정리한다.

### 6.3 TTL

DynamoDB Local은 TTL 속성을 저장만 하고 실제 만료 삭제를 하지 않는다. 따라서 `expiresAt`은 기록하되 실제 삭제는 `purgeExpired()`를 일 1회 호출해 처리한다. 실제 AWS로 옮기면 TTL이 처리하고 이 잡은 무해하게 0건을 반환한다.

- 스냅샷: 7일
- 실행 이력: 30일

### 6.4 테이블 생성

`dynamodb.create-table-on-startup: true`면 부팅 시 테이블·GSI가 없을 때 생성한다(로컬 편의). 운영에서는 false.

---

## 7. 동기화 흐름

### 7.1 LDAP Full Sync — `app-ldap`, 매일 03:00 (설정 가능)

```
 1. SyncRun.start(LDAP, SCHEDULED)                       → RUNNING
 2. DirectorySnapshotSource.fetchAll()                   → snapshot
 3. TupleMapper.toTuples(snapshot)                       → T_new
 4. TupleSnapshotRepository.findLatest()                 → T_old (없으면 ∅)
 5. TupleDiff.between(T_old, T_new)                      → delta
 6. DeletionGuard.check(delta, T_old)                    → 초과 시 ABORTED 후 종료 ⛔
 7. RelationTupleWriter.apply(delta)                     → result
 8. T_committed = (T_old − result.deleted) ∪ result.written
 9. TupleSnapshotRepository.save(TupleSnapshot(T_committed)) + 포인터 갱신
10. DirectoryStateRepository.replaceWith(snapshot)
11. SyncRun.finish(SUCCEEDED | PARTIAL)
```

**8번이 이 설계에서 가장 중요한 줄이다.** 실패한 튜플은 새 스냅샷에 들어가지 않으므로 다음 sync의 diff가 자동으로 다시 잡는다.

**9번과 10번은 기준이 다르다.** 튜플 스냅샷은 OpenFGA에 실제 반영된 것(`T_committed`)이고, 현재상태는 LDAP에서 읽은 사실 그대로(`snapshot`)다. 전자는 "우리가 밀어넣은 것"의 기록이고 후자는 "조직도가 이렇다"는 사실이므로 일부러 다르게 둔다.

`delta.isEmpty()`면 7~9를 건너뛰고 `SUCCEEDED`로 종료한다(스냅샷도 새로 만들지 않는다).

### 7.2 SCIM Incremental — `app-scim`

예: `PATCH /scim/v2/Groups/backend`
```json
{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
 "Operations":[{"op":"add","path":"members","value":[{"value":"kim","type":"User"}]}]}
```

```
1. DirectoryStateRepository.findGroup("backend")              → oldGroup
2. ScimPatchApplier.apply(oldGroup, patch)                    → newGroup
3. TupleDiff.between(toTuples(oldGroup), toTuples(newGroup))  → delta
4. RelationTupleWriter.apply(delta)                           → result
5. 결과에 따라 분기 (아래 표)
```

| `result` | 상태 저장 | 응답 |
|---|---|---|
| 전부 성공 | `saveGroup(newGroup)` | 200 |
| 전부 실패 | **저장하지 않음** | 500 |
| 부분 성공 | `result`에 반영된 튜플만 되돌려 만든 중간 상태를 저장 | 500 |

SCIM 단건 변경은 튜플이 대개 100개 미만이라 `Write` 한 배치에 들어가고, 배치는 트랜잭션이므로 **원자적**이다. 즉 정상적인 PATCH/DELETE에서는 "전부 성공" 또는 "전부 실패"만 나온다. 대형 그룹 `PUT`으로 배치가 여러 개로 쪼개질 때만 부분 성공이 가능하다.

부분 성공에서 상태를 저장하지 않으면 OpenFGA에는 반영됐는데 DynamoDB는 모르는 상태가 되어 영구히 어긋난다. 그래서 **반영된 만큼만 저장**하고 500을 반환해 IdP가 나머지를 재시도하게 한다. IdP 재시도는 같은 최종 상태를 목표로 하므로, 이미 반영된 부분은 다음 diff에서 자연히 제외된다.

`DELETE /scim/v2/Users/kim`:
```
1. findGroupIdsContaining(USER:kim) → [backend, ops]
2. delta.toDelete = { (user:kim, direct_member, group:backend),
                      (user:kim, direct_member, group:ops) }
3. apply → 성공 시 멤버십 아이템 + 유저 아이템 삭제
```

`DELETE /scim/v2/Groups/backend`:
```
1. backend의 멤버 튜플 전부 + backend가 다른 그룹의 child인 튜플(GSI1 역참조) 전부를 toDelete로
2. apply → 성공 시 그룹 아이템 + 멤버십 아이템 삭제
```

### 7.3 스냅샷 아카이빙 — `app-scim`, 매일 03:00

```
1. SyncRun.start(SCIM, ARCHIVE)
2. DirectoryStateRepository.loadAll()  → snapshot
3. TupleMapper.toTuples(snapshot)      → T
4. TupleSnapshotRepository.save(TupleSnapshot(T, source=SCIM))
5. SyncRun.finish(SUCCEEDED)
```

diff에는 쓰지 않고 감사·수동복구용이다.

### 7.4 스냅샷 정리 — 양쪽, 매일 04:00

`TupleSnapshotRepository.purgeExpired()` — 만료된 스냅샷의 메타·튜플 아이템 삭제. 멱등하므로 양쪽에서 돌아도 무방하다.

---

## 8. 관리 엔드포인트 (`app-ldap`)

| 엔드포인트 | 동작 |
|---|---|
| `POST /admin/sync/full` | 스케줄과 동일 경로를 즉시 실행. `trigger=MANUAL` |
| `POST /admin/sync/full?force=true` | `DeletionGuard`를 건너뜀. `trigger=FORCED` |
| `POST /admin/sync/rebuild?mode=snapshot\|store` | 전체 재적재. 기본 `snapshot` |
| `GET /admin/sync/runs?limit=20` | 최근 실행 이력 |

동시 실행 방지: 프로세스 내 `AtomicBoolean` 가드로 sync가 진행 중이면 `409 Conflict`를 반환한다(인스턴스 1개 전제).

### 8.1 `rebuild`의 함정

"스냅샷을 지우고 전체를 밀어넣는다"를 순진하게 구현하면 `T_old = ∅`이 되어 `toDelete`가 비고 `toWrite`가 전체가 된다. 그런데 OpenFGA에는 기존 튜플이 남아 있다.

- 여전히 유효한 튜플 → 중복 write. `on_duplicate: ignore`가 흡수하므로 **문제 없다**(§9.1).
- **이제는 없어야 할 튜플 → 영원히 남는다.** 지울 근거(스냅샷)를 방금 버렸고 read API는 쓰지 않으므로 되찾을 수 없다. 이것이 진짜 문제다.

따라서 순서를 뒤집는다.

### 8.2 `mode=snapshot` — 스냅샷 기반 전체 삭제 후 재적재

```
1. SyncRun.start(LDAP, REBUILD)
2. TupleSnapshotRepository.findLatest()          → T_old
3. RelationTupleWriter.apply(delete = T_old)     ← 먼저 전부 지운다
4. TupleSnapshotRepository.reset()               ← 스냅샷 전체 삭제 + 포인터 초기화
5. DirectorySnapshotSource.fetchAll()            → snapshot
6. RelationTupleWriter.apply(write = toTuples(snapshot))
7. 새 스냅샷 저장 + replaceWith(snapshot)
8. SyncRun.finish(...)
```

**인가 공백은 store 모드와 같다.** 3단계에서 `T_old` 를 전부 지운 뒤 6단계에서 다시 쓸 때까지, 그 튜플들이 담당하던 인가 질의는 전부 false 다. store 재생성이 없을 뿐 "지우고 다시 쓴다"는 구조가 같기 때문이다. 이 절이 오래 "안전하고 되돌리기 쉽다"고만 적어 두어 공백이 없는 것처럼 읽혔는데, 그 대비는 store 모드와의 **차이가 아니라** 아래 두 가지를 뜻한다.

- `store` 모드와 달리 store 자체를 재생성하지 않으므로 storeId 가 유지되고, 실패해도 인가 모델이 남는다
- 지우는 대상이 `T_old` 로 한정돼 되돌릴 범위가 명확하다

대신 **스냅샷에 없는 튜플(외부에서 직접 넣은 것)은 지우지 못한다.** 삭제 가드는 적용하지 않는다(전체 삭제가 의도된 동작이므로).

3번이 **하나라도 실패하면** 4번 이후로 진행하지 않고 `FAILED`로 종료한다. 스냅샷을 버리지 않았으므로 다음 정기 sync가 정상 동작한다. 부분 삭제된 상태는 남지만, 스냅샷을 `T_old − result.deleted`로 갱신해 두면 다음 sync가 정확한 기준에서 출발한다.

### 8.3 `mode=store` — store 재생성 (완전 초기화)

```
1. SyncRun.start(LDAP, REBUILD)
2. RelationTupleWriter.resetStore()   // DeleteStore → CreateStore(같은 이름) → 모델 write
3. TupleSnapshotRepository.reset()
4. fetchAll() → 전체 write → 스냅샷 저장 → replaceWith
5. SyncRun.finish(...)
```

read API 없이도 진짜로 깨끗해진다. 드리프트를 되돌리는 유일한 수단이다.

**대가**: 2번과 4번 사이에는 튜플이 하나도 없어 **모든 인가 질의가 실패하는 공백**이 생긴다. 응답에 이 경고를 포함하고, 문서에 명시한다.

---

## 9. 에러 처리

| 상황 | 처리 |
|---|---|
| LDAP 연결/조회 실패 | 백오프 3회 재시도 → `FAILED` 기록, 다음 스케줄에 재시도 |
| OpenFGA 배치 실패 | 배치 단위 백오프 3회 → 최종 실패는 `failures`에 담아 `PARTIAL` |
| 중복 튜플 write | `on_duplicate: ignore`로 OpenFGA가 흡수 (§9.1) |
| 없는 튜플 delete | `on_missing: ignore`로 OpenFGA가 흡수 (§9.1) |
| 삭제 가드 발동 | OpenFGA 미접촉, `ABORTED` + 사유 기록 |
| ID 정규화 충돌 | 해당 엔트리 스킵 + 경고 로그. 동기화는 계속 |
| LDAP `member` DN이 유저·그룹 어느 쪽도 아님 | 해당 멤버 스킵 + 경고 로그. 동기화는 계속 |
| 그룹 순환 참조 | 해당 간선 제외 + 경고 로그. 동기화는 계속 |
| SCIM 검증 실패 | SCIM Error 스키마로 400/404/409 |
| SCIM 처리 중 OpenFGA 실패 | 성공분만 상태 반영 후 500 (IdP 재시도 유도) |
| DynamoDB 실패 | SDK 재시도 + 최종 실패 시 `FAILED` |
| sync 중복 실행 요청 | `409 Conflict` |

### 9.1 멱등성

OpenFGA `Write`는 기본적으로 멱등하지 않다 — 이미 존재하는 튜플의 write와 존재하지 않는 튜플의 delete 모두 에러이고, 배치는 트랜잭션이므로 그 하나 때문에 **배치 전체가 실패**한다.

OpenFGA **v1.10.0+** 는 이를 요청 단위 옵션으로 해결한다.

```json
{
  "writes":  { "tuple_keys": [ ... ], "on_duplicate": "ignore" },
  "deletes": { "tuple_keys": [ ... ], "on_missing":  "ignore" }
}
```

**어댑터는 항상 이 옵션을 켠다.** 따라서 튜플 단위 재시도 같은 보상 로직은 필요 없다.

주의사항:

- 한 요청에 `ignore`와 `error`가 섞이면 **더 엄격한 쪽(`error`)이 우선**한다. 우리는 양쪽 모두 `ignore`로 통일한다.
- 튜플 키가 같아도 **condition 이름이나 파라미터가 다르면 여전히 충돌**이다. 이 설계는 conditional tuple을 쓰지 않으므로 해당되지 않는다.
- `docker-compose.yml`의 OpenFGA 이미지는 **v1.10.0 이상으로 고정**한다. 그 미만에서는 이 옵션이 무시되거나 거부되므로 `rebuild`가 깨진다.

이 옵션 덕분에 `rebuild`, 예기치 못한 재실행, 스냅샷과 실제의 경미한 어긋남이 모두 자연스럽게 흡수된다.

### 9.2 삭제 임계치 가드 (`DeletionGuard`)

```java
boolean shouldAbort(TupleDelta delta, Set<RelationTuple> baseline) {
    if (!enabled) return false;
    if (baseline.size() < minBaseline) return false;   // 기준이 너무 작으면 비율이 무의미
    double ratio = (double) delta.toDelete().size() / baseline.size();
    return ratio > thresholdRatio;
}
```

- 기본값: `thresholdRatio = 0.3`, `minBaseline = 10`
- LDAP full sync에만 적용한다. SCIM(의도된 단건 삭제)과 `rebuild`(의도된 전체 삭제)에는 적용하지 않는다.
- LDAP이 0건을 반환하면 `toDelete`가 100%가 되어 반드시 발동한다 — 가드의 주 목적이다.
- 우회는 `POST /admin/sync/full?force=true`.

### 9.3 SCIM 에러 응답

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
  "status": "404",
  "detail": "Group not found: backend"
}
```

| 상황 | status | scimType |
|---|---|---|
| 리소스 없음 | 404 | – |
| 잘못된 요청 본문 | 400 | `invalidSyntax` |
| 지원하지 않는 PATCH path | 400 | `invalidPath` |
| userName 중복 | 409 | `uniqueness` |
| 하위 시스템 실패 | 500 | – |

---

## 10. SCIM API 범위 (`connector-scim`)

| 메서드 | 경로 | 지원 |
|---|---|---|
| POST | `/scim/v2/Users` | O |
| GET | `/scim/v2/Users/{id}` | O (단건만) |
| PUT | `/scim/v2/Users/{id}` | O |
| PATCH | `/scim/v2/Users/{id}` | O |
| DELETE | `/scim/v2/Users/{id}` | O |
| POST | `/scim/v2/Groups` | O |
| GET | `/scim/v2/Groups/{id}` | O (단건만) |
| PUT | `/scim/v2/Groups/{id}` | O |
| PATCH | `/scim/v2/Groups/{id}` | O |
| DELETE | `/scim/v2/Groups/{id}` | O |
| GET | `/scim/v2/Users`, `/Groups` (목록) | X — 필터/페이징 비목표 |
| GET | `/scim/v2/ServiceProviderConfig` | O (지원 기능 광고) |

### 10.1 PATCH 지원 범위

IdP가 그룹 멤버 변경에 실제로 쓰는 경로만 구현한다.

| op | path | 동작 |
|---|---|---|
| `add` | `members` | 멤버 추가 |
| `remove` | `members` | 전체 제거 |
| `remove` | `members[value eq "kim"]` | 특정 멤버 제거 |
| `replace` | `members` | 멤버 전체 교체 |
| `replace` | `displayName`, `active` 등 단순 속성 | 값 교체 |
| `add`/`replace` | path 없음 (본문이 부분 리소스) | 속성 병합 |

`members[value eq "..."]` 형태의 필터는 이 한 가지 패턴만 파싱한다. 일반적인 SCIM 필터 문법 파서는 만들지 않는다. 지원하지 않는 path는 400 `invalidPath`로 거절한다.

### 10.2 SCIM ↔ 도메인 매핑

| SCIM | 도메인 | 비고 |
|---|---|---|
| `User.id` | `DirectoryUser.id` | 직원 아이디. `POST` 시 `userName`으로 발급 |
| `User.externalId` | `DirectoryUser.externalId` | |
| `User.userName` | `DirectoryUser.userName` | |
| `User.displayName` / `name.formatted` | `DirectoryUser.displayName` | |
| `User.emails[primary].value` | `DirectoryUser.email` | |
| `User.active` | `DirectoryUser.active` | |
| `Group.id` | `DirectoryGroup.id` | **조직코드**. `Group.externalId` → 없으면 `Group.id` → 둘 다 없으면 UUID + 경고 (§4.3 과 동일) |
| `Group.externalId` | `DirectoryGroup.externalId` | |
| `Group.displayName` | `DirectoryGroup.displayName` | **조직명**. 튜플에 사용하지 않음 |
| `Group.members[].value` + `type` | `Set<MemberRef>` | |

Enterprise User 확장(`urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`)은 파싱하지 않는다. 요청에 포함되면 무시하고 `ServiceProviderConfig`에도 광고하지 않는다.

`active: false`인 유저는 튜플을 생성하지 않는다(비활성 직원에게 권한이 남지 않도록). LDAP도 동일 규칙을 적용한다.

---

## 11. LDAP 매핑 전략 (`connector-ldap`)

`ldap.strategy` 설정으로 선택한다.

### 11.1 `group-of-names` (기본)

그룹 엔트리의 `member` 속성을 읽는다.

```
dn: cn=dev-hq,ou=groups,dc=example,dc=com
objectClass: groupOfNames
cn: dev-hq
member: cn=backend,ou=groups,dc=example,dc=com     → MemberRef(GROUP, "backend")
member: uid=kim,ou=people,dc=example,dc=com        → MemberRef(USER, "kim")
```

각 `member` DN이 사람인지 그룹인지는 **미리 읽어둔 유저 DN 집합/그룹 DN 집합으로 판별**한다(DN마다 추가 조회하지 않는다). 어느 쪽에도 없는 DN은 스킵 + 경고 로그.

SCIM의 `members` 배열과 구조가 동일해 변환이 자연스럽다.

### 11.2 `dit`

`ou` 트리를 조직 계층으로, 사용자 엔트리의 부모 `ou`를 소속으로 본다.

```
ou=dev-hq,ou=company,dc=example,dc=com          → group:dev-hq
ou=backend,ou=dev-hq,ou=company,...             → group:backend, child of dev-hq
uid=kim,ou=backend,ou=dev-hq,ou=company,...     → user:kim, direct_member of backend
```

- 직원은 **하나의 조직에만** 속한다(DIT 위치가 곧 소속이므로).
- 그룹 계층은 dn 경로에서 도출한다.
- `dit.root-dn` 아래만 스캔한다.

두 전략 모두 동일한 `DirectorySnapshot`을 만들어 반환하므로, 그 이후 로직은 전략을 모른다.

---

## 12. 관측성

### 12.1 SyncRun 이력

`GET /admin/sync/runs`로 조회. 관측성의 실체다.

### 12.2 메트릭 (Micrometer)

| 메트릭 | 타입 | 태그 |
|---|---|---|
| `sync.duration` | Timer | `source`, `trigger`, `status` |
| `sync.tuples.written` | Counter | `source` |
| `sync.tuples.deleted` | Counter | `source` |
| `sync.tuples.failed` | Counter | `source` |
| `sync.guard.aborted` | Counter | – |
| `scim.request` | Timer | `resource`, `operation`, `status` |

### 12.3 헬스체크

Actuator `/actuator/health`에 DynamoDB 연결, OpenFGA 연결, (app-ldap만) LDAP 연결 인디케이터를 등록한다.

### 12.4 로깅

동기화 1회에 `runId`를 MDC에 넣어 전 로그에 붙인다.

---

## 13. 설정

### `app-ldap/src/main/resources/application.yml`

```yaml
server:
  port: 8081

spring:
  application.name: organization-ldap

sync:
  cron: "0 0 3 * * *"
  purge-cron: "0 0 4 * * *"
  deletion-guard:
    enabled: true
    threshold-ratio: 0.3
    min-baseline: 10

ldap:
  url: ldap://localhost:1389
  base-dn: dc=example,dc=com
  bind-dn: cn=admin,dc=example,dc=com
  bind-password: adminpassword
  page-size: 500
  strategy: group-of-names          # group-of-names | dit
  group-of-names:
    user-search-base: ou=people
    user-object-class: inetOrgPerson
    user-id-attribute: uid              # 직원 아이디. employeeNumber 등으로 교체 가능
    user-name-attribute: displayName    # 없으면 cn으로 폴백
    user-mail-attribute: mail
    group-search-base: ou=groups
    group-object-class: groupOfNames
    group-id-attribute: cn              # 조직코드
    group-name-attribute: description   # 조직명 (LDAP 그룹에 표시명 표준 속성이 없음)
    member-attribute: member
  dit:
    root-dn: ou=company
    org-unit-object-class: organizationalUnit
    group-id-attribute: ou              # 조직코드
    group-name-attribute: description   # 없으면 ou로 폴백
    user-object-class: inetOrgPerson
    user-id-attribute: uid
    user-name-attribute: displayName
    user-mail-attribute: mail

openfga:
  api-url: http://localhost:8080
  store-name: organization
  write-batch-size: 100
  max-retries: 3

dynamodb:
  endpoint: http://localhost:8000
  region: ap-northeast-2
  table-name: organization
  create-table-on-startup: true
  snapshot-retention-days: 7
  syncrun-retention-days: 30
```

### `app-scim/src/main/resources/application.yml`

`server.port: 8082`, `ldap` 블록 없음, `sync`는 `archive-cron`과 `purge-cron`만 둔다(`deletion-guard`는 LDAP 전용이므로 없다). `openfga`/`dynamodb` 블록은 동일하다.

---

## 14. 테스트 전략

| 계층 | 방식 |
|---|---|
| `core` | 순수 단위 테스트. `TupleMapper`, `TupleDiff`, `DeletionGuard`, `IdNormalizer`, 유스케이스(포트는 fake). **가장 촘촘하게** — 실제 로직 전부가 여기 있다 |
| `connector-ldap` | UnboundID in-memory LDAP + LDIF 시드. 두 전략 각각 |
| `storage-dynamodb` | Testcontainers `amazon/dynamodb-local` |
| `authz-openfga` | Testcontainers `openfga/openfga`. 인가 모델 검증은 실제 `Check`로 한다 (프로덕션 코드에서도 `Check`는 허용 — 위 제약 표 참고) |
| `connector-scim` | `WebTestClient` + fake 포트 |
| `app-ldap` / `app-scim` | 전 컨테이너 띄운 end-to-end 각 1~2개 |

### 14.1 스타일

- AssertJ, BDD 주석(`// given` / `// when` / `// then`)
- `@DisplayName`에 한글로 무엇을 검증하는지 서술
- 메서드명도 한글 가능

```java
@DisplayName("직전 스냅샷에 없던 튜플은 생성 대상으로 분류된다")
@Test
void 신규_튜플은_생성_대상이_된다() {
    // given
    var 직전 = Set.of(tuple("user:kim", "direct_member", "group:backend"));
    var 목표 = Set.of(tuple("user:kim", "direct_member", "group:backend"),
                      tuple("user:lee", "direct_member", "group:backend"));

    // when
    var delta = TupleDiff.between(직전, 목표);

    // then
    assertThat(delta.toWrite())
            .containsExactly(tuple("user:lee", "direct_member", "group:backend"));
    assertThat(delta.toDelete()).isEmpty();
}
```

### 14.2 반드시 다뤄야 할 케이스

- LDAP이 0건을 반환하면 가드가 발동해 OpenFGA를 건드리지 않는다
- 부분 실패 시 성공분만 새 스냅샷에 들어가고, 다음 sync가 실패분을 다시 잡는다
- 중첩 그룹의 `member` 롤업이 실제 `Check`로 true가 된다
- SCIM `DELETE /Users/{id}`가 그 유저의 모든 소속 그룹에서 튜플을 지운다
- SCIM `PATCH remove members[value eq "..."]`가 해당 멤버만 지운다
- `rebuild(snapshot)` 후 스냅샷에 없던 잔여 튜플이 남는다 (알려진 한계의 명시적 검증)
- `rebuild(store)` 후 store가 비었다가 전체가 다시 채워진다
- 순환 참조가 있어도 동기화가 완주한다
- `active: false` 유저는 튜플이 생성되지 않는다

---

## 15. 로컬 실행

`docker-compose.yml`

| 서비스 | 이미지 | 포트 | 비고 |
|---|---|---|---|
| openfga | `openfga/openfga:v1.10.x` 이상 | 8080(http), 3000(playground) | `OPENFGA_DATASTORE_ENGINE=memory` — Postgres 불필요. **v1.10.0 미만이면 `on_duplicate`/`on_missing`이 없어 §9.1이 깨진다** |
| dynamodb-local | `amazon/dynamodb-local` | 8000 | `-inMemory` |
| openldap | `bitnami/openldap` | 1389 | 샘플 조직도 LDIF 시드 |

```bash
docker compose up -d
./gradlew :app-ldap:bootRun     # LDAP 인스턴스 (8081)
./gradlew :app-scim:bootRun     # SCIM 인스턴스 (8082)
```

---

## 16. 향후 과제

1. **Admin 조회 API** — 검색 기준은 **직원 아이디 / 조직코드 / 조직명** 세 가지. DynamoDB 현재상태 기반이라 커넥터와 무관하며, `core`의 공통 기능으로 두면 어느 인스턴스에서도 동작한다. 필요한 인덱스는 이번 사이클에 이미 넣어두므로(§6.1) 백필이 필요 없다.
   - 직원 아이디 → `GetItem PK=USER#<empId>`
   - 조직코드 → `GetItem PK=GROUP#<orgCode>`, 소속 멤버까지 한 쿼리로
   - 조직명 prefix → GSI1 `begins_with`
   - 조직명 부분일치 → GSI1 `GROUP_INDEX` Query 후 앱 필터
   - 조직 트리 / 산하 직원 목록 → 멤버십 아이템과 GSI1 역참조 조합
2. 인증 — SCIM 엔드포인트 Bearer 토큰, 관리 API 보호
3. 멀티 테넌시
4. 드리프트 감지 — read API를 허용한다면 주기적 reconcile
5. 이름 prefix 검색 — GSI 추가
