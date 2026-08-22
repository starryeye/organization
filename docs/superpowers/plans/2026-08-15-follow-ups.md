# 후속 과제 — LDAP 동기화 브랜치 병합 이후

이 목록은 `2026-08-14-foundation-and-ldap-sync.md` 계획을 실행하는 동안
태스크별 리뷰·최종 전체 브랜치 리뷰가 제기했으나 병합을 막지 않는다고
판단해 미뤄둔 항목이다. 병합 판정 근거는 각 항목의 "왜 안 막았나"에 있다.

Critical/Important 로 분류돼 병합 전에 처리된 6건은 여기 없다 — 커밋
`0c6cd1f`, `48c914c`, `7bc1a85`, `bb68139`, `cd30562`, `591253a` 참조.

## 1. 테스트가 결함을 재현하지 못하는 곳

**LDAP 페이징 테스트가 원래 결함을 재현하지 않는다.** 최종 재리뷰어가
저장소 사본을 만들어 두 전략을 수정 전으로 되돌린 뒤 새 페이징 테스트를
그대로 돌렸는데 **통과했다**. UnboundID 인메모리 서버에 서버측 크기 제한이
없어서, 페이징 없는 평범한 검색도 전체를 반환하기 때문이다. 테스트는
페이징 *기계장치*가 동작함(`page-size=1` 로 실제 다중 왕복)을 증명할 뿐,
"조용한 잘림"이라는 원래 결함이 닫혔음은 증명하지 못한다.
프로덕션 코드는 독립 검증됨(코드 독해 + spring-ldap 바이트코드 분석).
→ UnboundID 에 서버측 엔트리 상한을 건 픽스처를 추가해 공백을 닫을 것.
`ignoreSizeLimitExceededException(false)` 방어선도 현재 어떤 테스트도 안 탄다.

~~**순환/self-loop 테스트가 "간선 몇 개 남았나"만 단언한다.**~~ 해결됨 — 순환
픽스처에 **순환 밖 간선**(`B -> D`)을 더해 "너무 많이 지운다"가 드러나게 했다.
살아남은 집합을 통째로 못박는 대신 그 방식을 택한 이유는, 순환 간선 셋 중
*어느* 하나가 버려지는지는 `Set.copyOf` 반복 순서에 달려 있고 그 순서는 JDK 가
보장하지 않기 때문이다 — 셋 중 무엇을 버려도 정답이므로 특정 간선을 못박으면
코드가 멀쩡한데도 JDK 판올림에 깨진다. 대신 순환 밖 간선의 생존, 순환 간선
정확히 2개 생존, 총 3개를 단언한다. `TupleMapper` 를 "간선 하나 더 버리게"
변이시켜 실제로 실패하는 것을 확인했다. self-loop 테스트는 원래 정확했다.

**§14.2 가 요구한 음성 테스트 미작성** — "rebuild(snapshot) 후 스냅샷에 없던
잔여 튜플이 남는다". 알려진 한계가 문서화만 되고 고정되지 않았다.

~~**`두_전략은_같은_모양의_스냅샷을_만든다`(DitStrategyTest) 가 이름만 그렇고
DIT 만 실행한다.**~~ 해결됨 — 같은 조직도를 DIT 와 groupOfNames 두 모양으로 담은
LDIF 로 `TwoStrategiesSameShapeTest` 를 만들어 **실제로 두 전략을 돌려 비교**한다.
비교 대상은 `TupleMapper` 의 출력이다. 원본 레코드는 `externalId` 가 상대/절대 DN
으로 갈리는데(§4 의 별도 항목), 정말 지켜져야 하는 계약은 "이후 로직이 전략을
구분하지 않아도 된다" 이고 그것을 결정하는 것은 튜플이기 때문이다. 옛 테스트는
`DIT_스냅샷은_TupleMapper가_소화한다` 로 이름을 고쳐 실제로 하는 일과 맞췄다.

**미검증 분기들**: `TupleDiff.between` 의 null 폴백, `DeletionGuard` 의 null
baseline 분기, `GroupOfNamesStrategy` 의 중복 id 경로(DIT 만 픽스처 있음),
`DuplicateIdGuard` 경고 로그가 두 DN 을 모두 담는지, store 모드 rebuild 의
튜플 개수, 실행 이력의 트리거 종류.

## 2. 실패가 조용한 곳

~~**`OpenFgaHealthIndicator` 가 아직 `resolveStore()` 를 직접 부른다.**~~ 해결됨 —
SCIM 브랜치에서 `StoreBootstrapper.findExistingStore()` (read-only) 를 추가하고
app-scim·app-ldap 두 인디케이터를 모두 그쪽으로 옮겼다. store 가 없으면
아무것도 만들지 않고 DOWN 을 보고한다.

~~**두 헬스 인디케이터에 `.timeout()` 없음**~~ 해결됨 — 두 앱의 네 인디케이터
모두 `PROBE_TIMEOUT`(2초)을 걸었고, 상한 초과가 기존 `onErrorResume` 을 타고
DOWN 으로 흐른다. 응답하지 않는 클라이언트를 물린 `DynamoDbHealthIndicatorTest`
로 못박았으며, 타임아웃을 제거하면 그 테스트만 실패하는 것을 확인했다.

~~**LDAP 읽기에 재시도 없음.**~~ 해결됨 — `LdapDirectorySnapshotSource` 가
`Retry.backoff(ldap.max-retries, 200ms)` 로 다시 읽는다. OpenFGA 어댑터와 같은
이름·같은 기본값(3)을 쓴다. 재시도가 걸리는 것은 **예외**뿐이라, LDAP 이 성공적으로
잘못된 답(필터 오류로 0건 등)을 주는 경우는 여전히 삭제 가드가 잡는다 — 두 방어선의
경계를 흐리지 않는다.

**§12.3 의 LDAP 헬스 인디케이터 미구현.** E2E 가 존재하는 둘만 단언해
그 공백을 고착시켰다.

~~**§12.4 MDC runId 가 수동 접두사 4곳뿐.**~~ 해결됨 — runId 를 MDC 에 넣는 대신
표준 트레이싱(`traceId`/`spanId`)을 모든 로그 줄에 붙였다. runId 안은 폐기했다:
세 실행 경로가 모두 한 번에 하나씩만 돌아 시간만으로 구분되는 반면, 정작 동시에
들어오는 SCIM push 에는 설계상 runId 가 없어 그 경로를 못 덮기 때문이다.
설계 §12.4 참고. 남은 것은 아래 두 함정이고 둘 다 회귀 테스트로 고정했다 —
`spring.reactor.context-propagation: auto` 가 없으면 한 줄도 안 찍히고,
예약 작업은 스케줄러가 직접 관측을 열어야 한다.

## 3. 데이터 무결성 여지

~~**`IdNormalizer` 금지 목록에 `|` 없음.**~~ 해결됨 — 금지 목록에 `|` 를 더했다.
그대로 두었다면 `userName = "a|b"` 하나로 정렬키의 경계가 밀려 전혀 다른 튜플로
되읽히고, 그 값이 스냅샷에 들어가는 순간 다음 diff 의 기준선이 오염돼 쓰기·삭제가
엉뚱한 대상으로 갔다. 이 목록에서 가장 위험한 항목이었다.

**`SnapshotIds` 가 초 단위**. 같은 초의 두 실행이 한 스냅샷 파티션에 두 튜플
집합의 합집합을 만든다. 현재는 `SyncExecutionGuard` 가 막지만, 그 id 가
유일한 방어선이다.

**`Keys.parseUserPk`/`parseGroupPk` 가 무조건 `substring`** — 제거된
`stripPrefix` 는 `startsWith` 로 방어했다. 현 호출부는 GSI1 파티션에서만
값을 받아 도달 불가.

## 4. 일관성·유지보수

~~**`mode=snapshot` 의 인가 공백이 문서화되지 않음.**~~ 해결됨 — 설계 §8.2 에
공백이 store 모드와 같다는 것을 명시하고, "안전하고 되돌리기 쉽다"가 뜻하는 바
(storeId 유지, 되돌릴 범위가 `T_old` 로 한정)를 공백 유무와 분리해 적었다.

**`DynamoDbDirectoryStateRepository` 만 `Clock` 미주입** — `Instant.now()` 직접
호출이라 `updatedAt`/`addedAt` 이 고정 시계로 테스트 불가. 형제 저장소 둘은
주입받는다. `Clock` 빈이 `storage-dynamodb` 에 있는데 `app-ldap` 이 그걸로
`core` 유스케이스를 만드는 구조도 함께 볼 것.

~~**`externalId` 형식이 두 전략 간 불일치**~~ 해당 없음으로 종결 — "SCIM 착수 전에
계약을 정할 것" 이 조건이었는데 SCIM 이 완료됐고, 두 배포가 서로 다른 테이블·store
를 쓰므로 두 형식이 한 데이터에서 섞일 일이 없다. LDAP 두 전략 사이의 불일치는
남아 있으나 이 값을 읽는 코드가 없다(`TwoStrategiesSameShapeTest` 가 튜플만
비교하는 이유이기도 하다).

**`saveGroup` 이 변경 없는 멤버의 `addedAt` 을 매번 갱신** — "최초 합류"가
아니라 "마지막 전체 동기화"를 의미하게 된다.

**`findUser` 가 PK+SK 를 다 아는데 `GetItem` 대신 Query + 클라이언트 필터.**

**`purgeExpired` 가 후보마다 `GetItem` 재조회** — GSI 가 `ProjectionType.ALL`
이라 이미 `expiresAt` 을 갖고 있는데 버린다.

**`findStoreIdByName`/`createStore` 가 일회용 클라이언트 생성** — 첫
부트스트랩에 클라이언트 3개. 누수는 아님(이 SDK 버전은 `Closeable` 미구현).

~~**그룹 표시명 폴백이 원본 `cn` 이 아니라 정규화된 코드를 쓴다**~~ 해결됨 —
두 전략 모두 폴백을 원본 속성으로 바꿨다. DIT 는 조직명뿐 아니라 **직원 표시명**
폴백도 같은 문제였다(정규화된 `userId` 로 폴백). `DisplayNameFallbackTest` 가
세 경로를 모두 덮는다 — 처음 쓴 픽스처는 금지 문자가 없거나 `cn` 이 있어 폴백까지
도달하지 못해 통과해버렸고, 그것을 고쳐 셋 다 RED 로 만든 뒤 수정했다.

## 5. 미관

`Flux.defer(() -> queryMonth(lastMonth))` 중복(무해), 루트 `build.gradle` 이
`java-library` 를 app 모듈에도 적용, `TupleMapper.visit` 파라미터 들여쓰기,
`TupleMapperTest` 불필요한 빈 줄, `DitStrategyTest`/`RebuildUseCaseTest` 의
정규화되지 않은 FQN, `TableInitializer` 임포트 정렬, README 의 테이블 생성
서술이 `create-table-on-startup` 게이트를 반영하지 않음, `SyncMetrics` 가
미완료 실행에도 튜플 카운터 증가(값이 0 이라 무해), `paginate` 재귀 깊이.

## 6. 의도적으로 미룬 설계 결정 — SCIM 쓰기 경로의 동시성

이 항목은 결함 목록이 아니라 **결정 기록**이다. 2026-08-19 브레인스토밍에서
"지금은 하지 않는다"로 닫았고, 스케일 아웃 시점에 다시 열어야 한다.

**문제.** app-scim 을 N대 액티브-액티브로 띄우면 lost update 가 생기고,
**오류 신호가 전혀 남지 않는다.** 두 요청이 각자 자기 시점에서 올바르게
동작하고 둘 다 200 을 반환하는데도 결과가 틀린다:

```
        A: upsertUser(kim, 비활성)          B: upsertGroup(DEV001 + kim)
 t1  읽기: kim 활성
 t2                                      읽기: kim 활성, DEV001 에 kim 없음
 t3  계산: 지울 튜플 없음
 t4                                      계산: dm(kim,DEV001) 추가
 t5  커밋: kim 비활성                        200
 t6                                      OpenFGA: dm(kim,DEV001) 씀
 t7                                      커밋: DEV001 에 kim         200
```

최종 상태("kim 비활성 + DEV001 이 kim 포함")가 요구하는 튜플은 없음인데
OpenFGA 에는 `dm(kim,DEV001)` 이 남는다. 퇴사자 권한이 살아남는 방향이다.

**애플리케이션 수준 락으로는 안 된다.** 프로세스 내 직렬화는 인스턴스가
둘이 되는 순간 아무것도 막지 못하면서 막고 있다고 믿게 만든다. ShedLock 이나
leader election 도 이 문제를 풀지 못한다 — 그것들은 `@Scheduled` 중복 실행을
막는 도구이고, 여기 문제는 HTTP 요청 경로다.

**흔한 낙관적 락으로도 안 잡힌다.** 아이템 버전 검사는 write-write 충돌을
잡는다. 그런데 위 시나리오에서 B 가 *쓴* 것은 DEV001 이고 충돌의 원인은 B 가
*읽기만 한* kim 이다. 읽은 것까지 검증하려면 커밋을 `TransactWriteItems` 로
바꾸고 읽은 아이템마다 `ConditionCheck` 를 걸어야 하는데, **트랜잭션 100개
아이템 제한** 때문에 멤버가 수백인 조직 PUT 은 애초에 들어가지 않는다.

**장애는 이미 안전하다(혼동 주의).** OpenFGA 쓰기 성공 후 DynamoDB 커밋이
실패하면 500 이 나가고, 상태가 안 바뀌었으므로 재시도가 같은 델타를 다시
계산해 `onDuplicate(IGNORE)` 로 수렴한다. 여기서 미뤄둔 것은 **동시성**이지
장애 복구가 아니다.

**LDAP 이 안전한 이유(SCIM 에 없는 것).** `FullSyncUseCase` 는 기준선을 상태에서
유도하지 않고 `TupleSnapshotRepository` 에서 읽으며, 커밋할 스냅샷을
`baseline - result.deleted() + result.written()` 으로 만든다 — 즉 **OpenFGA 에
실제 반영된 것**을 기록한다. 게다가 매일 전체를 다시 돌려 차이를 재적용한다.
SCIM 은 기준선을 상태에서 유도하고(`tuplesOf(snapshotOf(...))`) 전체 재동기화가
없어서, 한 번 어긋나면 다음 diff 가 "상태 vs 상태"를 비교해 차이를 못 본다.

**딸린 불일치.** `SnapshotArchiveUseCase` 는 `loadAll → TupleMapper` 결과를
저장하므로 SCIM 스냅샷은 **의도한 튜플**이고, LDAP 스냅샷은 **실제 반영된
튜플**이다. 같은 `TupleSnapshotRepository` 에 같은 `TupleSnapshot` 타입으로
들어가는데 의미가 다르다. 설계 §4.4 는 스냅샷을 "OpenFGA 상태를 대신하는 유일한
기록"이라 부르는데 SCIM 쪽은 그 약속을 지키지 않는다.

**현재 대응.** 관리자가 어긋났다고 판단하면 **OpenFGA store 초기화 + DynamoDB
상태 전체 재적재**를 수동 실행한다. 자동 감지도, 주기적 대조도 두지 않는다.
따라서 **SCIM 경로는 수렴을 보장하지 않는다** — 운영자가 실행할 때만 수렴한다.
실행 트리거는 대체로 사고 이후("권한이 안 나온다"는 문의, 감사에서 퇴사자
권한 발견)가 된다는 것을 감수한 선택이다.

`RebuildUseCase` 는 그대로 못 쓴다 — `DirectorySnapshotSource`(LDAP 리더)에
의존하는데 SCIM 의 진실은 DynamoDB 상태 자체다. `state.loadAll()` 을 읽는
변형이 필요하다. `resetStore()` 는 재적재가 끝날 때까지 **인가 공백**을 만든다
(설계 §8.2 가 LDAP 쪽에서 다루는 것과 같은 성질).

**다시 열어야 할 시점.** `replicas` 를 2 이상으로 올릴 때. 그때의 후보는
(1) 주기적 대조로 감지·수렴 — LDAP 이 이미 쓰는 모델, (2) 아웃박스 —
변경 의도를 상태와 원자적으로 기록하고 별도 적용기가 반영, (3) 저장소 분산 락.

**후보 목록 정정 (2026-08-19).** 위 "다시 열어야 할 시점"의 후보 셋은 `Check` 를
쓸 수 없다는 잘못된 전제 아래 작성됐다. `Check` 는 실제로는 제한 없이 쓸 수 있으므로
네 번째 후보가 있다 — **표본 검증**: 스냅샷에서 튜플 N개를 뽑아 `Check` 로 확인한다.
전수 대조(`loadAll` + 전체 비교)보다 훨씬 싸고, 어긋남이 있으면 확률적으로 걸린다.
감지만 하고 고치지는 않으므로 "배경에서 자동으로 쓰지는 않는다"는 결정과도 양립한다.

## 7. 알려진 함정 — 인덱스 키 구성 변경

`TableInitializer.addMissingIndex` 는 인덱스가 있는지를 **이름으로만** 판단한다.
같은 이름의 인덱스가 이미 있으면 **키 구성이 달라도 그냥 넘어간다.**

DynamoDB 는 GSI 의 키 스키마를 제자리에서 바꿀 수 없다. 그래서 기존 인덱스의 키 설계를
바꾸는 변경을 배포하면, 이미 옛 키로 만들어진 인덱스가 남은 채 코드만 새 키로 질의해
**그 인덱스를 쓰는 조회만 영구히 `ValidationException`(500)으로 죽는다.** 다른 엔드포인트는
정상 동작하므로 원인 파악이 어렵다. 복구하려면 인덱스를 지우고 다시 만드는 수밖에 없다.

**지금은 물지 않는다.** 이 결함은 admin 조회 API 개발 중 GSI2 의 키 설계를 한 번 바꾸면서
드러났는데(전용 속성 → 기존 `GSI1PK` 위에 얹기, 커밋 `bb59181`), 당시 옛 인덱스가 있던 곳은
로컬 DynamoDB Local 뿐이었고 그것은 `-inMemory` 라 컨테이너 재시작으로 사라졌다. 프로덕션에는
GSI2 자체가 배포된 적이 없어 첫 배포 때 올바른 키로 만들어진다.

**언제 다시 볼 것인가.** 이미 배포된 뒤에 **기존 인덱스의 키 구성을 바꾸는** 변경을 할 때.
새 인덱스를 *추가*하는 것은 이름이 다르므로 해당 없다. 그때는 시작 시 기존 인덱스의
키 스키마를 기대값과 대조해 다르면 명시적으로 알리는 가드를 함께 넣을 것.
