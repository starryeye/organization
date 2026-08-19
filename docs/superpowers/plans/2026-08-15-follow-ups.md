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

**순환/self-loop 테스트가 "간선 몇 개 남았나"만 단언한다.** 어느 간선이
빠졌는지는 단언하지 않는다. `containsExactlyInAnyOrder` 로 살아남은 튜플
집합을 못박으면, 정렬 순회 계약을 `ImmutableCollections` 내부 구현에 기대는
현재 해시 충돌 기법(`"Aa"`/`"BB"`)의 단일 실패점을 없앨 수 있다.

**§14.2 가 요구한 음성 테스트 미작성** — "rebuild(snapshot) 후 스냅샷에 없던
잔여 튜플이 남는다". 알려진 한계가 문서화만 되고 고정되지 않았다.

**`두_전략은_같은_모양의_스냅샷을_만든다`(DitStrategyTest) 가 이름만 그렇고
DIT 만 실행한다.** 두 전략을 비교하지 않으므로 그 불변식은 이름으로만 단언된 상태.

**미검증 분기들**: `TupleDiff.between` 의 null 폴백, `DeletionGuard` 의 null
baseline 분기, `GroupOfNamesStrategy` 의 중복 id 경로(DIT 만 픽스처 있음),
`DuplicateIdGuard` 경고 로그가 두 DN 을 모두 담는지, store 모드 rebuild 의
튜플 개수, 실행 이력의 트리거 종류.

## 2. 실패가 조용한 곳

~~**`OpenFgaHealthIndicator` 가 아직 `resolveStore()` 를 직접 부른다.**~~ 해결됨 —
SCIM 브랜치에서 `StoreBootstrapper.findExistingStore()` (read-only) 를 추가하고
app-scim·app-ldap 두 인디케이터를 모두 그쪽으로 옮겼다. store 가 없으면
아무것도 만들지 않고 DOWN 을 보고한다.

**두 헬스 인디케이터에 `.timeout()` 없음** — DynamoDB/OpenFGA 연결이 매달리면
프로브가 DOWN 을 보고하는 대신 함께 매달린다.

**LDAP 읽기에 재시도 없음.** 설계 §9 는 "백오프 3회 → FAILED" 를 지시하는데,
`authz-openfga` 와 AWS SDK 는 재시도가 있고 LDAP 만 없다. 파이프라인에서
유일하게 보호되지 않은 외부 호출이며, 하필 삭제 가드가 존재하는 이유가 되는
실패 양상을 가진 호출이다.

**§12.3 의 LDAP 헬스 인디케이터 미구현.** E2E 가 존재하는 둘만 단언해
그 공백을 고착시켰다.

**§12.4 MDC runId 가 수동 접두사 4곳뿐.** 상관관계가 가장 필요한 로그
(`OpenFgaRelationTupleWriter` 배치 실패, `TupleMapper` 스킵 경고)에 runId 가 없다.

## 3. 데이터 무결성 여지

**`IdNormalizer` 금지 목록에 `|` 없음.** `Keys.tupleSk` 가 `|` 를 구분자로
쓰고 `parseTupleSk` 의 `split(..., 3)` 은 **마지막** 컴포넌트만 보호한다.
`RelationTuple.user()` 값에 `|` 가 들어가면 파싱이 조용히 다른 튜플을 만든다.
가장 싼 봉쇄는 금지 목록에 `|` 추가.

**`SnapshotIds` 가 초 단위**. 같은 초의 두 실행이 한 스냅샷 파티션에 두 튜플
집합의 합집합을 만든다. 현재는 `SyncExecutionGuard` 가 막지만, 그 id 가
유일한 방어선이다.

**`Keys.parseUserPk`/`parseGroupPk` 가 무조건 `substring`** — 제거된
`stripPrefix` 는 `startsWith` 로 방어했다. 현 호출부는 GSI1 파티션에서만
값을 받아 도달 불가.

## 4. 일관성·유지보수

**`mode=snapshot` 의 인가 공백이 문서화되지 않음.** snapshot 모드도 `T_old`
전체를 지운 뒤 다시 쓰므로 store 모드와 같은 공백이 있는데, 경고는 store
모드에만 있다(§8.2 는 snapshot 을 "안전하고 되돌리기 쉽다"고만 서술).

**`DynamoDbDirectoryStateRepository` 만 `Clock` 미주입** — `Instant.now()` 직접
호출이라 `updatedAt`/`addedAt` 이 고정 시계로 테스트 불가. 형제 저장소 둘은
주입받는다. `Clock` 빈이 `storage-dynamodb` 에 있는데 `app-ldap` 이 그걸로
`core` 유스케이스를 만드는 구조도 함께 볼 것.

**`externalId` 형식이 두 전략 간 불일치** — DIT 는 상대 DN, groupOfNames 는
절대 DN. 지금은 아무도 안 읽지만, SCIM 의 `externalId` 는 조직코드로 쓰이므로
(§10.2) SCIM 착수 전에 계약을 정할 것.

**`saveGroup` 이 변경 없는 멤버의 `addedAt` 을 매번 갱신** — "최초 합류"가
아니라 "마지막 전체 동기화"를 의미하게 된다.

**`findUser` 가 PK+SK 를 다 아는데 `GetItem` 대신 Query + 클라이언트 필터.**

**`purgeExpired` 가 후보마다 `GetItem` 재조회** — GSI 가 `ProjectionType.ALL`
이라 이미 `expiresAt` 을 갖고 있는데 버린다.

**`findStoreIdByName`/`createStore` 가 일회용 클라이언트 생성** — 첫
부트스트랩에 클라이언트 3개. 누수는 아님(이 SDK 버전은 `Closeable` 미구현).

**그룹 표시명 폴백이 원본 `cn` 이 아니라 정규화된 코드를 쓴다** — 금지 문자가
있으면 사람이 읽는 필드에 밑줄이 노출된다.

## 5. 미관

`Flux.defer(() -> queryMonth(lastMonth))` 중복(무해), 루트 `build.gradle` 이
`java-library` 를 app 모듈에도 적용, `TupleMapper.visit` 파라미터 들여쓰기,
`TupleMapperTest` 불필요한 빈 줄, `DitStrategyTest`/`RebuildUseCaseTest` 의
정규화되지 않은 FQN, `TableInitializer` 임포트 정렬, README 의 테이블 생성
서술이 `create-table-on-startup` 게이트를 반영하지 않음, `SyncMetrics` 가
미완료 실행에도 튜플 카운터 증가(값이 0 이라 무해), `paginate` 재귀 깊이.
