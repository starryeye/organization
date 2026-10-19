# 외부 코드 리뷰 — 인증·인가 플랫폼 리드 관점 (2026-10-19)

- 검토 대상: `/Users/starryeye/study/organization/.claude/worktrees/spike-ldap-5k` (Java 17 / Gradle 멀티 모듈 8개)
- 읽은 것: `README.md`, `settings.gradle`, 각 모듈 `build.gradle`, 8개 모듈의 `src/main` 전부, 인가 모델(`authz-openfga/src/main/resources/authorization-model.fga`·`.json`), 판단에 필요한 일부 테스트
- 읽지 않은 것: `docs/superpowers/`, `.superpowers/`, git 이력 — 팀의 설계 근거 없이 코드만으로 판단했다. 의도된 트레이드오프로 보이는 것은 "질문"으로 남겼다.
- 실행하지 않음: Gradle·테스트·Docker. 모든 판단은 코드 읽기로 한 것이다.

---

## 1. 이 시스템을 어떻게 이해했나

1. LDAP(pull)·SCIM(push) 디렉터리의 "직원 ∈ 조직"과 "조직 ⊂ 상위 조직"을 OpenFGA 튜플 두 종류 `(user:U, direct_member, group:G)`·`(group:C, child, group:P)` 로 옮기는 동기화 서버다. 인가 판단은 하지 않는다 — 하위 앱이 OpenFGA 에 `Check(user:U, member, group:G)` 를 직접 묻는다.
2. 모델은 `member: direct_member or member from child` 하나라서 하위 조직 소속이 모든 상위 조직으로 롤업된다. 튜플 주체는 불변 id(entryUUID·objectGUID·SCIM 서버 발급 UUID)다.
3. LDAP: 전체를 읽고, "OpenFGA 에 반영됐다고 기록해 둔" 직전 스냅샷과 diff 해 쓰고, 성공한 줄만 새 스냅샷으로 남긴다. 평상시 OpenFGA 를 읽지 않는다(Read 는 재적재와 "기록 중" 복구 때만).
4. SCIM: 요청마다 DynamoDB 전역 리스 락 안에서 영향받는 줄만 HIGHER_CONSISTENCY Check 로 실제 값을 읽어 diff 하고, 쓴 뒤 DynamoDB 에 "반영된 만큼만" 커밋한다. SCIM 배포에선 DynamoDB 가 조직도의 유일한 사본이다.
5. 비활성 직원은 소속은 남고 튜플만 빠진다. 하위 앱은 로그인한 사용자를 관리 API(`/admin/employees?userName=|externalId=`)로 employeeId 로 바꾼 뒤 Check 한다.
6. 안전장치: 재적재는 "있어야 할 줄 쓰기 → 장부 훑기 → 나머지 지우기", 삭제 가드(30%), 3단계 쓰기 순서, "기록 중" 표시, 감사 이력. SCIM·`/admin`·`/actuator` 에는 인증이 없다.

---

## 2. 총평과 운영 승인 여부

**판정: 지금 상태로는 우리 회사의 인가 데이터 원천으로 승인하지 않는다.**

내부 구현의 질은 평균보다 분명히 높다. 쓰기 단계 순서와 단조성 논증, Check 기준선의 일관성 수준, BatchCheck 응답 대조, 토큰 조건이 걸린 리스 락, DN 정규화, 일시/영구 오류 분류, 키 길이 사전 검사까지 사고한 흔적이 있다. 그러나 인가 데이터 원천은 먼저 두 질문으로 판정한다.

- **"누가 이것을 바꿀 수 있는가"** — 네트워크에 닿는 누구나 바꿀 수 있다. SCIM 쓰기로 임의 사용자를 관리자 조직에 넣을 수 있고, 관리 API 로 조직도와 장부를 통째로 지울 수 있다. OpenFGA 클라이언트에는 자격 증명을 실을 자리가 없어, OpenFGA 를 인증 없이 띄워야 하고 그러면 Check 하러 붙는 모든 앱이 Write 도 할 수 있다.
- **"회수가 반드시 수렴하는가"** — 그렇지 않다. 디렉터리에서 회수된 권한이 영구히 남는 코드 경로가 셋 있다(H1·H2·H3). 그중 하나(H3)는 문서가 권하는 복구 도구(`mode=tuples` 재적재)가 오히려 권한을 다시 쓴다.

**운영 전 반드시 할 일**

1. SCIM(IdP 별 자격 증명), 관리 API(별도 포트 + RBAC + 감사), actuator(관리 포트 분리)에 인증·인가를 붙인다 (C1·C2).
2. OpenFGA 인증을 지원하고, 쓰기 권한을 이 서버로만 좁히고, storeId·modelId 를 고정한다 (H4).
3. 회수 손실 경로 셋을 닫고(H1·H2·H3), 주기적 장부 대조와 그 지표·경보를 둔다 (M5).
4. 로그인 주체 → employeeId 매핑 계약을 유일·정확·인증된 경로로 정한다 (H5).
5. 회수 SLO 를 정하고 신선도 지표·경보를 단다 (M3).

1~3 을 마치면 중요도가 낮은 소비 앱에 한정한 파일럿은 승인할 수 있다. 4~5 까지 마쳐야 운영 인가에 쓸 수 있다.

---

## 3. 잘한 점

- **이름이 아니라 불변 id 를 튜플 주체로 쓴다.** 개명·재입사·이름 재사용으로 남의 권한을 물려받는 고전적 사고를 구조적으로 막았다. SCIM 은 `id` 를 서버가 발급하고 본문 `id` 를 무시한다 — `connector-scim/.../ScimUserHandler.java:48`, `ScimGroupHandler.java:42`, `core/.../tuple/TupleMapper.java:20-25`.
- **튜플 식별자 주입을 막는다.** `IdNormalizer` 가 `:` `#` `*` `,` `\` `|` 와 공백을 지워, `user:*` 와일드카드나 `group:X#member` 같은 userset 을 끼워 넣을 수 없다 — `core/.../tuple/IdNormalizer.java:26`. 경로 id 로 Check 하는 관리 조회도 저장된 레코드의 id 를 쓴다 — `core/.../usecase/AdminQueryUseCase.java:253`.
- **단조 모델 위에서 쓰기 순서를 설계했다.** 조직 연결 지우기 → 직원 줄(직원별 한 Write 요청, 원자적 이동) → 조직 연결 쓰기 순서이고, 앞 단계가 실패하면 뒤 단계의 *쓰기*만 막고 *지우기*는 보낸다 — `authz-openfga/.../OpenFgaRelationTupleWriter.java:151-158, 255-296`. 중간에 멈춰도 앞·뒤 어느 상태보다 큰 권한이 생기지 않는다는 논증이 코드와 맞다.
- **SCIM 기준선을 상태가 아니라 OpenFGA 실제 값(HIGHER_CONSISTENCY)으로 잡는다.** 응답의 correlationId 를 하나씩 소진하며 대조하고, 빠지거나 겹치면 "없음"으로 떨어뜨리지 않고 실패시킨다 — `OpenFgaRelationTupleChecker.java:50, 159-210`, `IncrementalSyncUseCase.java:884-937`.
- **SCIM 쓰기의 판단 읽기가 전부 락 안에 있다.** 동시에 온 비활성화와 이름 변경이 서로를 덮어 퇴사자 권한이 되살아나는 lost update 를 막는다 — `IncrementalSyncUseCase.java:292-305, 737-768`. 쓰기 직전·커밋 직전에 리스를 다시 확인한다 — `:967-988`.
- **락 구현이 정석이다.** 반납·갱신에 토큰 조건을 걸었고, 응답을 잃은 획득 재시도가 자기 락에 막히지 않으며, 멈춤 한도를 단조 타이머로 잰다 — `storage-dynamodb/.../DynamoDbMutationLock.java:63-151`, `core/.../usecase/LeaseKeeper.java:58-118`.
- **삭제에 강한 일관성 역참조를 쓴다.** GSI 가 늦어 소속을 못 찾아 권한이 남는 일을 막으려고 멤버 쪽 파티션에 `BELONGS_TO#` 줄을 두고, 쓰기 순서를 "소속 줄이 늘 멤버 줄 이상"으로 맞췄다 — `DynamoDbDirectoryStateRepository.java:453-472, 695-713`.
- **재적재가 store 를 버리지 않는다.** 쓰기 → 훑기 → 지우기 순서라 인가 공백이 없고, 읽기 실패 시 장부를 건드리지 않으며, `wipe` 는 장부를 먼저 비우고 조직도를 나중에 지운다 — `core/.../usecase/TupleReconciler.java:95-114`, `ScimRebuildUseCase.java:217-240`.
- **LDAP 계정 상태를 표준대로 읽는다.** `userAccountControl` 은 비트로 보고, `accountExpires` 경계를 포함하며, ppolicy 는 영구 잠금만 비활성으로 본다. 표준 밖 값은 짐작하지 않고 회차를 실패시킨다 — `connector-ldap/.../strategy/AccountStatus.java:49-100`. DN 은 `LdapName` 으로 정규화해 대조한다 — `LdapDns.java:93-123`.
- **조용한 부분 읽기를 막는 가드가 여럿이다.** 페이징 응답 컨트롤이 없으면 실패, referral 만 받고 엔트리 0건이면 실패, 사람 대조 0건이면 실패, 기준선 메타가 깨졌으면 빈 기준선으로 넘어가지 않는다 — `PagedLdapSearch.java:80, 153-170`, `UnmatchedMemberGuard.java:41-50`, `DynamoDbTupleSnapshotRepository.java:144-149`.
- **오류 응답이 내부 사정을 흘리지 않는다.** 503·4xx·500 을 가르고, OpenFGA·SDK 예외 메시지를 SCIM 응답에 싣지 않는다 — `connector-scim/.../ScimRouter.java:172-193`. 헬스 프로브는 store 를 만들지 않고(`StoreBootstrapper.java:94-100`), LDAP 인증 실패 뒤 30분 바인드를 쉬어 AD 서비스 계정 잠금을 막는다(`app-ldap/.../LdapHealthIndicator.java:45-79`).

---

## 4. 발견 사항

표기: **확인됨** = 코드로 끝까지 따라갔다. **의심** = 그럴 법하지만 끝까지 따라가지 못했거나, 외부 시스템(IdP·OpenFGA)의 동작에 달렸다.

### Critical

#### C1. SCIM 쓰기 엔드포인트에 인증이 없다 — 누구나 아무 직원을 아무 조직(관리자 조직 포함)에 넣을 수 있다
- **상태:** 확인됨
- **근거**
  - `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java:89-117` — 라우트에 필터나 인증 단계가 없다.
  - `ScimRouter.java:252` — `"authenticationSchemes", List.of()`.
  - `ScimRouter.java:175` — 주석이 "이 엔드포인트는 인증이 없다"라고 적는다.
  - `app-scim/build.gradle`, `connector-scim/build.gradle` — `spring-boot-starter-security` 가 없고, `WebFilter` 도 없다(코드 전체 grep 으로 확인).
  - 쓰기 경로: `ScimGroupHandler.java:73-87`(PATCH) → `IncrementalSyncUseCase.changeGroup` `:410-453` → OpenFGA Write.
- **시나리오**
  1. 인증 없이 `GET /scim/v2/Groups?filter=displayName eq "Platform-Admins"` 로 조직 id `G` 를 얻는다.
  2. `GET /scim/v2/Users?filter=userName eq "me"` 로 내 id `U` 를 얻는다(또는 `POST /scim/v2/Users` 로 새로 만든다).
  3. `PATCH /scim/v2/Groups/G` 에 `{"Operations":[{"op":"add","path":"members","value":[{"value":"U"}]}]}` 를 보낸다.
  4. 튜플 `(user:U, direct_member, group:G)` 가 쓰이고, `Check(user:U, member, G 와 그 모든 상위)` 가 true 가 된다.
  - 같은 방법으로 퇴사자를 되살리거나(`PATCH /Users/{id}` 로 `active=true`), 조직을 지워 전사 권한을 끊을 수 있다(`DELETE /Groups/{id}`).
  - 직원 이메일·이름 등 PII 도 `GET /scim/v2/Users` 로 그대로 읽힌다.
- **왜 중요한가:** 이 서비스를 신뢰하는 모든 하위 앱에서 즉시 권한 상승이 가능하다. 인가 원천의 무결성이 네트워크 경계 하나에 달려 있다.
- **권고**
  - IdP 마다 자격 증명을 준다(OAuth2 bearer 에 audience·scope 검증, 또는 mTLS + 클라이언트 인증서 고정). 쓰기는 그 IdP 테넌트 하나로 묶는다.
  - SCIM 을 관리 API·actuator 와 다른 포트·리스너로 분리한다.
  - "앞단에서 보호" 같은 운영 가정에 맡기지 말고, 인증이 없으면 기동을 거부하는 fail-closed 설정으로 만든다.

#### C2. 관리 API 와 actuator 에 인증이 없다 — 익명 호출자가 장부·조직도를 지우고, 삭제 가드를 우회하고, 회수를 막고, PII 를 읽는다
- **상태:** 확인됨
- **근거**
  - `app-scim/.../AdminSyncController.java:49-86` — `wipe` 의 확인값은 테이블명뿐이다. 기본값은 `organization-scim` 이다(`app-scim/src/main/resources/application.yml:29`, README 에 공개).
  - `ScimRebuildUseCase.java:217-240` — 장부 전체를 지우고 조직도를 비운다.
  - `app-ldap/.../AdminSyncController.java:48-71` — `force=true` 로 삭제 가드를 건너뛴다.
  - 조회·이력: `admin-api/.../AdminQueryController.java:37-101`, `AuditHistoryController.java:26, 38-50` — 이전 값(이름·이메일)이 담긴다.
  - actuator: `app-*/src/main/resources/application.yml:46/89` — `health,info,metrics,prometheus` 를 앱 포트로 노출한다.
  - README.md:385-387 이 이 상태를 인정한다.
- **시나리오**
  1. `POST /admin/sync/rebuild?mode=wipe&confirm=organization-scim` 한 번이면 OpenFGA 튜플 전부와 SCIM 조직도 전부가 사라진다. 전사 Check 가 false 가 되고, IdP 전체 재프로비저닝 전까지 복구할 수 없다.
  2. `POST /admin/sync/rebuild`(mode=tuples)를 반복해 REBUILD 락을 계속 쥐면, 그동안 모든 SCIM 쓰기가 503(Retry-After 60)이다(README.md:243). IdP 의 비활성화·삭제가 반영되지 않아 **회수가 무기한 지연**된다.
  3. app-ldap 에서는 `POST /admin/sync/full?force=true` 로 삭제 가드를 건너뛴다. LDAP 이 일시적으로 일부만 돌려주는 순간을 노리면 대량 회수가 일어난다.
  4. `GET /admin/employees/{id}` 한 번이 최대 200건의 HIGHER_CONSISTENCY Check(캐시 우회)를 공유 OpenFGA 에 보낸다(`AdminQueryUseCase.java:42, 251-262`, `OpenFgaRelationTupleChecker.java:50`). 다른 모든 앱의 인가 지연을 끌어올릴 수 있다.
- **왜 중요한가**
  - 가용성(전사 거부)과 무결성(가드 우회), 기밀성(조직도·PII·이력 노출)이 모두 익명에게 열려 있다.
  - 게다가 README 는 하위 앱이 이 관리 API 로 로그인 사용자를 매핑하라고 안내한다(README.md:86, 336-345). 그래서 소비 앱이 관리 API 에 네트워크로 닿아야 하고, "앞단에서 보호"하기도 어렵다.
- **권고**
  - 관리 API 를 별도 포트·별도 배포(또는 최소한 별도 리스너)로 나누고, 운영자 SSO + RBAC + 사유 입력 + 감사 로그를 단다.
  - `wipe`·`force` 는 두 사람 승인이나 시간 지연 같은 안전 장치 뒤에 둔다.
  - 소비 앱용 "id 매핑 조회"는 읽기 전용·인증된 별도 API 로 떼어 낸다(H5).
  - actuator 는 `management.server.port` 로 분리한다.

### High

#### H1. LDAP 동기화가 "결과를 모르는 쓰기"를 "안 쓴 것"으로 기록하고 "기록 중" 표시까지 지운다 — 그 뒤 빠진 사람의 권한이 영구히 남는다
- **상태:** 확인됨
- **근거**
  - `core/.../usecase/FullSyncUseCase.java:133-140` — 쓰기 → 커밋, 차단기로 멈춰도 `saveSnapshotAndState`.
  - `FullSyncUseCase.java:166-182` — `committed = baseline − deleted + written`. 실패한 쓰기는 기준선에 들어가지 않는다.
  - `authz-openfga/.../OpenFgaRelationTupleWriter.java:386-397, 406-428` — 일시 오류(시간 초과 포함)로 재시도를 다 쓰면 배치 전체를 `failed` 로 센다. 서버가 실제로 반영했는지는 모른다.
  - `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:134-141` — 스냅샷 저장이 포인터를 통째로 다시 써 "기록 중" 표시를 지운다.
  - 테스트의 가짜 writer 도 "실패 = 반영 안 됨"만 흉내 낸다 — `FullSyncUseCaseTest.java:147-157`.
- **시나리오**
  1. OpenFGA 가 흔들리는 날(차단기가 서는 바로 그 상황), 직원 K 의 `direct_member(K, G)` 쓰기 배치가 서버에서는 커밋됐는데 응답이 시간 초과로 끊기고, 재시도도 모두 시간 초과로 끝난다.
  2. 회차는 PARTIAL 이나 FAILED(차단기)로 끝나고, 새 스냅샷에는 그 줄이 없으며, "기록 중" 표시는 지워진다.
  3. 다음 회차 전에 K 가 G 에서 빠지거나 퇴사한다.
  4. 다음 diff 는 기준선에도 없고 목표에도 없는 줄이라 아무것도 하지 않는다. `(K, direct_member, G)` 는 수동 재적재 전까지 영원히 남는다.
- **왜 중요한가:** 회수가 수렴하지 않는 경로다. 장애 날에 정확히 발생하고, 드리프트 감지도 없어(M5) 누구도 모른다.
- **권고(작은 것부터)**
  - 400 거절이 아닌 실패가 하나라도 있으면 스냅샷을 남기되 **"기록 중" 표시를 지우지 않는다** → 다음 회차가 장부를 훑어 맞춘다.
  - 또는 스냅샷에 "불확실" 집합을 따로 두고, 다음 회차에서 불확실 ∩ 목표는 다시 쓰고 불확실 − 목표는 지운다(멱등 옵션이 있어 안전하다).
  - 가짜 writer 에 "반영됐지만 실패로 보고" 모드를 추가해 회귀 시험으로 고정한다.

#### H2. 기준선 스냅샷이 없으면(테이블 재생성·복원·포인터 소실) 빈 기준선으로 diff 한다 — 장부에 이미 있던 줄은 영원히 지워지지 않는다
- **상태:** 확인됨
- **근거**
  - `FullSyncUseCase.java:144-148` — `findLatest().defaultIfEmpty(Set.of())`.
  - `DynamoDbTupleSnapshotRepository.java:151-153, 179-183` — 포인터가 없으면 기준선은 빈 값, "기록 중"은 false.
  - `DeletionGuard.java:36-38` — 기준선이 10 미만이면 가드를 건너뛴다.
  - 반면 README.md:53-54 는 "기준선을 온전히 읽지 못하면 빈 기준선으로 넘어가지 않는다"고 적는다. 포인터가 *없는* 경우만 이 원칙에서 빠져 있다.
  - store 번호는 영구 고정이고(README.md:94-97), README 는 키 레이아웃 변경 때마다 "기존 테이블은 다시 만들어야 한다"고 지시한다(README.md:129-146).
- **시나리오**
  1. 운영자가 README 지시대로 DynamoDB 테이블을 다시 만든다(또는 PITR 로 새 테이블에 복원하거나 다른 리전으로 옮긴다). OpenFGA store 는 그대로다.
  2. 그 사이 퇴사한 L 의 `direct_member(L, …)` 가 store 에 남아 있다.
  3. 첫 동기화는 기준선 ∅ 이므로 쓰기만 하고 지우기는 0건이다. L 의 권한은 수동 재적재 전까지 남는다.
  - PITR 로 과거 시점에 복원해도 같다 — 복원 시점 뒤에 쓰인 줄은 기준선에 없어 지워지지 않는다.
  - 결합 시나리오: OpenFGA 에 인증이 없으니(H4) 첫 배포 전에 누군가 같은 이름의 store 를 만들어 튜플을 심어 두면, 이 서버는 그 store 를 그대로 쓰고(`StoreBootstrapper.java:233-244`) 첫 동기화가 심어 둔 줄을 지우지 않는다.
- **왜 중요한가:** DR·이관·재배포는 반드시 일어난다. 그때마다 조용히 회수가 끊긴다.
- **권고**
  - "포인터 없음"을 "기록 중"과 같은 **신뢰할 수 없는 기준선**으로 보고 장부를 훑어 맞춘다(`LedgerAlignment`).
  - 스냅샷 메타에 storeId 를 적고, 현재 store 와 다르면 역시 훑어 맞춘다.
  - 주기적 장부 대조(M5)로 이 종류의 결함을 근본적으로 덮는다.

#### H3. SCIM 회수(비활성화·삭제·멤버 빼기)가 일부라도 실패하면 현재상태를 "권한 있음"으로 되돌려 저장한다 — IdP 가 재시도하지 않으면 권한이 남고, 재적재는 오히려 다시 쓰고, 드리프트 지표에도 안 보인다
- **상태:** 확인됨(코드 경로). 실제 빈도는 IdP 의 503 재시도 동작에 달렸다 — 그 부분은 의심.
- **근거**
  - 비활성화: `core/.../usecase/IncrementalSyncUseCase.java:1024-1029` — 실패가 있으면 `requested.withActive(existing.active())`, 즉 active=true 로 저장한다. 커밋 지점은 `:252-259`.
  - 직원 삭제: `:521-535` — 하나라도 실패하면 직원 레코드를 지우지 않고, 원래 active 그대로 남긴다.
  - 멤버 빼기: `:1073-1083` — 지우기 실패 멤버를 계속 멤버로 둔다. 재시도 대상이 저장된 멤버 ∩ 요청에서만 정해진다(`:462-474`).
  - 재적재는 현재상태를 진실로 본다 — `ScimRebuildUseCase.java:124-147`.
  - 드리프트 비교의 기준이 현재상태다 — `IncrementalSyncUseCase.java:911-918`, `SnapshotArchiveUseCase.java:140-147`.
  - 응답은 503 이고, IdP 가 다시 보내야만 고쳐진다 — `ScimUserHandler.java:109-134`.
- **시나리오**
  1. Okta/Entra 가 퇴사자 X 에 `PATCH active=false` 를 보낸다. X 의 조직 30곳 중 한 Write 배치가 실패해 응답은 503 이 나가고, 상태에는 active=true 가 남는다.
  2. IdP 가 재시도하지 않거나 재시도가 늦는다. README.md:789 는 Okta 의 5xx 동작이 문서에 없다고 적는다.
  3. X 는 남은 튜플로 계속 접근한다. 운영자가 어긋남을 의심해 `mode=tuples` 재적재를 돌리면 상태(active=true)가 요구하는 튜플을 **다시 쓴다** — 이미 지워졌던 줄까지 되살아난다.
  4. 상태와 OpenFGA 가 "둘 다 권한 있음"으로 일치하므로 `scim.drift.detected` 와 아카이빙 비교 어디에도 나타나지 않는다.
- **왜 중요한가:** 회수 의도가 저장소에서 사라지는 설계라, 이 서버 쪽 복구 수단이 모두 잘못된 방향으로 수렴한다. 이 규칙은 상태를 기준선으로 쓰던 옛 설계에서는 필요했지만, 지금은 Check 기준선이라 더 이상 필요 없어 보인다.
- **권고**
  - 상태에는 IdP 의 **의도**를 저장한다: active=false 를 즉시 저장하고, 삭제는 삭제 표시(tombstone)로 남긴다. 반영 여부는 별도의 "대기 중 회수" 목록(outbox)으로 추적하고, **서버가 스스로 재시도**한다.
  - 멤버 빼기의 지울 줄은 상태 ∩ 요청이 아니라 요청에서 만든다 — "없으면 무시" 옵션이 있어 안전하다.
  - 그러면 재적재·드리프트 감지·아카이빙이 모두 회수 쪽으로 수렴한다.

#### H4. OpenFGA 연결에 인증 수단이 없고 store 를 이름으로 찾는다 — 장부를 읽는 모든 앱이 장부에 쓸 수 있고, 같은 이름 store 하나로 서버를 세울 수 있다
- **상태:** 확인됨
- **근거**
  - `authz-openfga/.../StoreBootstrapper.java:302-312` — `ClientConfiguration().apiUrl(...)` 만 있고 `credentials` 가 없다.
  - `OpenFgaProperties.java:10-24` — 토큰·클라이언트 자격 설정이 없다.
  - 이름 해석: `StoreBootstrapper.java:184-244` — 같은 이름이 둘 이상이면 오류로 멈춘다.
  - 기동 때 모델을 등록한다: `StoreBootstrapper.java:266-281`.
  - README.md:94-97 — 다른 앱은 store 를 이름으로 찾아도 된다.
- **시나리오**
  1. 이 서버가 동작하려면 OpenFGA 를 인증 없이 띄워야 한다.
  2. 하위 앱은 Check 하러 같은 API 에 붙으므로, 침해되거나 실수한 앱 하나가 `Write(user:me, direct_member, group:Admins)` 를 할 수 있다.
  3. 다음 LDAP 동기화는 장부를 읽지 않으므로(M5) 그 줄을 지우지 않는다.
  4. 누군가 `CreateStore(name="organization")` 를 하면, 이 서버는 다음 재시작 때 store 를 해석하지 못해 모든 쓰기를 멈춘다(회수 정지). store 를 이름으로 찾는 소비 앱은 어느 쪽을 볼지 모른다.
- **왜 중요한가:** 인가 저장소에서 쓰기 권한과 읽기 권한이 분리되지 않으면, 이 서버가 지키는 모든 규칙이 우회된다.
- **권고**
  - OpenFGA preshared key 나 OIDC client credentials 를 지원한다(SDK 의 `Credentials`).
  - OpenFGA 앞에 게이트웨이나 OpenFGA 접근 제어를 두어 **Write 는 이 서버만**, 소비 앱은 Check 만 하게 한다.
  - storeId·authorization_model_id 를 설정으로 고정해 모든 소비 앱에 배포한다(이름 해석 금지).

#### H5. 로그인 주체 → employeeId 매핑을 무인증·접두사·비유일·최종 일관 조회에 맡긴다 — 다른 사람의 권한으로 판정될 수 있다
- **상태:** 확인됨(매핑 구조와 비유일성). 소비 앱이 어떻게 고르는지는 코드 밖이라 영향은 의심.
- **근거**
  - README.md:84-87, 336-347 — 앱이 `?userName=`(접두사) 또는 `?externalId=` 로 찾아 "같은 줄을 고른다".
  - `admin-api/.../AdminQueryController.java:37-55`.
  - `storage-dynamodb/.../DynamoDbDirectorySearchRepository.java:39-44` — 소문자 접두사 질의다.
  - LDAP 의 userName 은 로그인 속성 원문이고 유일성 검사가 없다. 없으면 id 로 대신한다 — `connector-ldap/.../GroupOfNamesStrategy.java:219`, `DitStrategy.java:204`.
  - SCIM 직원의 `externalId` 는 중복을 막지 않는다 — README.md:347-348, `AdminQueryUseCase.java:67-74`(여러 줄이 올 수 있다).
  - externalId 조회는 GSI(최종 일관)다 — README.md:349-351.
- **시나리오**
  - LDAP 에서 두 도메인(또는 두 OU)에 `uid=jkim` 이 있으면 `?userName=jkim` 이 두 줄을 돌려준다. 소비 앱이 첫 줄을 고르면 다른 jkim 의 조직 권한으로 판정된다.
  - SCIM 에서 IdP 매핑 실수로 두 직원이 같은 externalId 를 가지면 같은 일이 생긴다.
  - LDAP 의 externalId 는 DN 이라, 개명이나 OU 이동 때 매핑이 깨진다(이건 거부 쪽이다).
- **왜 중요한가:** 인가 판정의 주체를 잘못 고르면 모델과 튜플이 아무리 정확해도 소용없다. 이 연결을 계약 없이 각 소비 앱에 맡겼다.
- **권고**
  - 토큰에 실리는 불변 식별자(OIDC `sub`, AD `objectGUID`, IdP 사용자 id)를 그대로 튜플 주체로 쓰면 매핑 단계가 사라진다.
  - 그게 안 되면 유일성을 강제한 정확 일치 매핑 API 를 인증해서 제공하고, 0건이나 2건 이상이면 거부를 계약으로 정한다.

### Medium

#### M1. 비활성 신호가 없으면 "활성"으로 본다 — 회수 쪽에서 fail-open 기본값
- **상태:** 확인됨
- **근거**
  - `connector-scim/.../ScimPatchApplier.java:367-368` — `remove active` 이면 `active=true`. 테스트 `ScimPatchApplierTest.java:701-702` 가 이를 고정한다.
  - `ScimMapper.java:51-52` — POST·**PUT** 에 `active` 가 없으면 true.
  - `connector-ldap/.../AccountStatus.java:22-24, 53-56` — 속성이 없으면 막히지 않은 것으로 본다. README.md:533 — 동기화 계정이 세 속성을 못 읽으면 전원 활성.
- **시나리오**
  - 비활성 직원에 `PATCH {"op":"remove","path":"active"}` 를 보내거나 `active` 를 뺀 PUT(전체 교체)을 보내면 그 직원이 다시 활성이 된다.
  - AD ACL 변경으로 동기화 계정이 `userAccountControl` 을 못 읽게 되면 비활성 계정 전원이 다음 회차에 권한을 되찾는다. 경고도 없다.
- **권고**
  - `remove active` 와 PUT 의 누락은 "변경 없음(기존 값 유지)"이나 false 로 둔다.
  - AD(objectGUID) 설정에서 `userAccountControl` 이 한 명이라도 없으면 데이터 오류로 회차를 멈춘다.

#### M2. 삭제 가드가 걸리면 회수(명시적 비활성화 포함)와 부여가 함께 멈추고, 30% 미만의 대량 부당 회수는 그대로 통과한다
- **상태:** 확인됨
- **근거**
  - `FullSyncUseCase.java:126-131` — 가드에 걸리면 아무것도 쓰지 않고 ABORTED.
  - `LedgerAlignment.java:49, 58-61`.
  - `core/.../guard/DeletionGuard.java:32-48` — 지울 튜플 수 / 기준 튜플 수의 단일 비율.
  - `app-ldap/src/main/resources/application.yml` — `threshold-ratio: 0.3`.
- **시나리오**
  - 큰 조직 개편 날 가드가 걸린다. 사람이 `force` 하기 전까지 그날 퇴사·비활성화된 사람 전원이 권한을 유지한다. 매일 cron 이 다시 ABORT 한다.
  - 반대로 LDAP ACL 실수로 OU 하나(직원 25%)가 빠지면 가드를 통과해 2.5만 명이 한 번에 권한을 잃는다.
- **권고**
  - 델타를 나눈다. "엔트리는 있는데 막힘·소속 변경"처럼 디렉터리가 **명시적으로 말한 회수**와 부여는 항상 적용하고, "엔트리가 사라져서 생긴 삭제"만 가드에 건다.
  - 절대 수 상한과 비율을 함께 쓴다.

#### M3. 회수 지연에 상한·지표·긴급 경로가 없다
- **상태:** 확인됨
- **근거**
  - `app-ldap/src/main/resources/application.yml:20` — `sync.cron` 기본값이 하루 1회.
  - README.md:535 — 막힌 직후부터 다음 동기화까지는 권한이 남는다.
  - `app-ldap/.../SyncMetrics.java:20-43` — 마지막 성공 시각 게이지가 없다.
  - README.md:982-983 — 소비 앱은 Check 캐시를 그대로 쓴다.
- **시나리오:** AD 에서 퇴사 처리된 계정이 최대 약 24시간 + 동기화 소요 + 소비 앱 Check 캐시 TTL 동안 권한을 유지한다. 실패나 ABORT 가 이어지면 상한이 없다. 개인 한 명을 즉시 회수할 수단은 전체 동기화뿐이다.
- **권고**
  - 회수 SLO(예: 1시간)를 정하고, cron 을 그에 맞춘다.
  - `sync_last_success_timestamp` 게이지와 "N시간 무성공" 경보를 단다.
  - 개별 직원 즉시 회수 엔드포인트를 두고(인증 필수), 소비 앱 캐시 TTL 을 SLO 에 넣는다.

#### M4. OpenFGA 쓰기에 펜싱이 없다 — 리스를 잃은 인스턴스의 늦은 쓰기가 다음 회차의 장부 훑기 뒤에 떨어지면 영구히 남는다
- **상태:** 의심(시간 창이 좁고, 끝까지 재현하지는 못했다)
- **근거**
  - `DynamoDbMutationLock.java:34-36` — 완벽한 배제가 아니라고 스스로 적는다.
  - `LeaseKeeper.java:58-79` — 리스를 잃으면 Mono 를 취소한다. 그러나 `Mono.fromFuture` 로 이미 나간 HTTP 요청은 취소되지 않는다(`OpenFgaRelationTupleWriter.java:386-397`).
  - `TupleReconciler.java:98-113, 187-206` — 쓰기 → 훑기 → 지우기 순서.
- **시나리오**
  1. 인스턴스 A 가 GC 정지나 네트워크 정체로 리스를 잃는다.
  2. B 가 "기록 중" 표시를 보고 장부 맞추기를 시작한다 — 쓰기 → 훑기.
  3. 훑기가 끝난 뒤 A 가 이미 보냈던 `direct_member(K, G)`(그 사이 K 는 G 에서 빠졌다)가 OpenFGA 에 도착한다.
  4. B 의 스냅샷에는 그 줄이 없으니 이후 diff 가 영원히 지우지 않는다.
- **권고**
  - 펜싱 토큰을 OpenFGA 쓰기 앞단(게이트웨이)에서 검증한다.
  - 또는 OpenFGA ReadChanges(변경 로그)로 회차 시작 이후 들어온 쓰기를 확인한다.
  - 최소한 주기적 장부 대조(M5)로 덮는다.

#### M5. 주기적 장부 대조와 자가 치유가 없다 — 드리프트는 "건드린 리소스"에서만 보이고, 아카이빙은 WARN 로그뿐이다
- **상태:** 확인됨
- **근거**
  - README.md:30-44 — 평상시 OpenFGA 를 읽지 않고, 드리프트를 감지할 수 없다고 적는다.
  - `IncrementalSyncUseCase.java:904-918` — 드리프트는 요청이 건드린 범위만 본다.
  - `SnapshotArchiveUseCase.java:140-147` — 하루 한 번 비교하지만 로그만 남기고, 지표도 복구도 없다.
- **시나리오:** H1·H2·H4·M4 로 생긴 잉여 튜플이 사람이 수동 재적재를 떠올릴 때까지 남는다. LDAP 앱에는 아카이빙 비교조차 없다.
- **권고**
  - 주 1회(규모에 따라 일 1회) `LedgerAlignment` 를 스케줄한다(가드 포함).
  - 잉여·누락 수를 지표로 내보내고, 잉여(over-grant) > 0 이면 경보를 띄운다.
  - 아카이빙 비교 결과도 지표로 낸다.

#### M6. 손실 정규화(비단사)와 "먼저 읽은 쪽이 이긴다" 중복 해소 — 이름 기반 id 설정에서 서로 다른 사람이 한 튜플 주체를 나눠 가질 수 있다. AD 기본 그룹은 도메인을 보지 않고 RID 만 맞춘다
- **상태:** 확인됨(코드). 영향은 설정에 달려 있다 — 기본값 entryUUID 에서는 발생하지 않는다.
- **근거**
  - `core/.../tuple/IdNormalizer.java:26, 31-36` — `john doe` 와 `john_doe` 가 같은 값이 된다.
  - `connector-ldap/.../DuplicateIdGuard.java:25-31`, `GroupOfNamesStrategy.java:85-88` — 겹치면 먼저 읽은 엔트리가 이긴다. LDAP 반환 순서는 보장되지 않는다.
  - `GroupOfNamesStrategy.java:162-183` — RID 만 비교한다. README.md:506-508 이 이 제한을 인정한다.
- **시나리오**
  - `user-id-attribute: uid` 설정에서 `john doe`(관리자)와 `john_doe`(일반)가 공존하면, 회차마다 어느 쪽이 `user:john_doe` 의 주인이 될지 바뀐다. 소비 앱이 id 를 직접 계산하면 일반 사용자가 관리자 소속으로 판정될 수 있다.
  - 검색 범위가 두 도메인에 걸치면, 다른 도메인 사용자의 `primaryGroupID=512` 가 이 도메인의 Domain Admins(RID 512) 그룹 멤버로 더해진다.
- **권고**
  - 정규화를 단사 인코딩(퍼센트 인코딩 등)으로 바꾸거나, 금지 문자가 있는 id 는 데이터 오류로 거부한다.
  - id 겹침은 건너뛰지 말고 회차를 실패시킨다.
  - 사용자 `objectSid` 의 도메인 부분을 읽어 그룹 SID 의 도메인과 같을 때만 기본 그룹을 더한다.

#### M7. 인가 모델 운영: 기동마다 모델을 새로 등록하고 latest 에 기대며, store 를 독점하고, 계층 깊이·비용을 지키지 않는다
- **상태:** 확인됨(모델 등록·고정 없음). 깊이·비용은 의심.
- **근거**
  - `StoreBootstrapper.java:26, 266-281` — store 해석 때마다 `writeAuthorizationModel`, 쓰기·Check 에 modelId 를 넘기지 않는다.
  - `authorization-model.fga` — 타입 두 개뿐이다. README.md:205-207 — 장부는 이 서버만 쓴다.
  - 계층 깊이 상한은 SCIM 순환 검사 예산(`OrgGraph.java:49`)뿐이다.
  - 관리 조회의 Check 도 `resolveStore()` 를 타서, store 가 없으면 GET 이 store 를 만들고 모델을 쓴다(`OpenFgaRelationTupleChecker.java:66-83`).
- **시나리오**
  - 잘못된 모델이 든 빌드가 뜨는 순간 모든 소비 앱의 latest 모델이 바뀐다.
  - 소비 앱이 같은 store 에 자기 타입(`document#viewer: [group#member]`)을 더하면, 다음 기동이 2타입 모델로 덮어 그 앱의 Check 가 실패한다(거부).
  - OpenFGA 해석 깊이 한도(기본 25)를 넘는 계층이나, 하위 조직 수천 개인 루트 조직에 대한 부정 Check 는 오류나 지연으로 거부될 수 있다.
- **권고**
  - 모델은 배포 파이프라인이 등록하고 modelId 를 설정에 고정한다. 앱은 모델이 다르면 기동을 멈춘다.
  - 깊이 상한을 데이터 검증에 넣고, 최악 부정 Check 를 규모 시험에 넣는다.
  - 소비 앱 조합 방식(별도 store + 두 번 Check, 또는 공유 모델 거버넌스)을 정한다.

#### M8. 운영 기본값이 위험하다 — app-ldap 헬스 상세 공개, 평문 LDAP·기본 비밀번호, prod 프로필이 덮지 않는 값들
- **상태:** 확인됨
- **근거**
  - `app-ldap/src/main/resources/application.yml:92` — `show-details: always`. 이로써 LDAP url·baseDn·strategy(`LdapHealthIndicator.java:88-92`), OpenFGA apiUrl·storeName(`OpenFgaHealthIndicator.java:43-51`), 테이블명·itemCount(`DynamoDbHealthIndicator.java:35-38`), 예외 메시지(`Health.down(error)`, LDAP 오류 data 코드 포함)가 익명에게 나간다.
  - `application.yml:30, 33` — `ldap://`, `bind-password: adminpassword`.
  - `application-prod.yml` 는 로그 형식만 바꾼다. `dynamodb.endpoint`, `create-table-on-startup: true`, DEBUG 로그(`:102`, scim `:62`), `show-details` 를 덮지 않는다.
- **권고**
  - 헬스 상세는 `when-authorized` 와 관리 포트로 둔다.
  - 비밀번호는 시크릿 매니저로 옮기고 기본값을 지운다. 기본값이 남아 있으면 prod 에서 기동을 실패시킨다.
  - `ldaps://` 나 StartTLS 를 기본으로 하고 인증서를 검증한다.
  - prod 프로필에서 위험 기본값을 명시적으로 덮는다.

#### M9. 익명 호출자가 비싼 요청으로 전역 락·DynamoDB·공유 OpenFGA 를 소모시켜 회수를 늦출 수 있다
- **상태:** 확인됨(비용 구조). 실제 포화 정도는 의심.
- **근거**
  - `ScimGroupHandler.java:120-128` — 파라미터 없는 `GET /Groups/{id}` 가 멤버 전원(10만 명)을 스트리밍한다.
  - `ScimPager.java:47-56` — 책갈피가 없는 큰 `startIndex` 는 N−1건을 건너뛰며 읽는다.
  - `AdminQueryUseCase.java:42-45, 251-262, 334-347` — 요청당 최대 200·100건의 HIGHER_CONSISTENCY Check.
  - SCIM 쓰기는 클러스터 전역 단일 락으로 직렬화된다 — `IncrementalSyncUseCase.java:773-809`. 큰 PUT 하나가 락을 수 초 쥔다.
- **시나리오:** 익명 루프가 RCU 와 OpenFGA 용량을 태우면 SCIM 쓰기(IdP 의 비활성화 포함)가 스로틀과 락 대기 끝에 503 이 되고, 다른 앱의 Check 도 느려진다.
- **권고**
  - 인증(C1·C2)이 1차 해법이다.
  - 그다음 호출자별 속도 제한, 관리 조회의 Check 는 기본 일관성(캐시)으로 두고 필요할 때만 HIGHER, 큰 조직 GET 은 `excludedAttributes=members` 를 기본으로 한다.
  - 장기적으로 전역 단일 락 대신 리소스 단위 직렬화를 검토한다.

### Low

#### L1. 실패한 `POST /Groups` 가 남긴 고아 튜플은 재적재만 지운다
- **상태:** 확인됨
- **근거:** `IncrementalSyncUseCase.java:355-358`(새 조직은 실패하면 레코드를 만들지 않는다), `ScimGroupHandler.java:100-105`(재시도는 새 UUID, 첫 id 의 튜플은 재적재만 지운다).
- **영향:** 아무도 모르는 id 의 조직이라 당장 권한 문제는 작다. 다만 아카이빙 비교(후보가 상태에서 나온다)에도 보이지 않는 잉여 튜플이 쌓인다.
- **권고:** 실패한 새 조직이 쓴 줄을 같은 요청 안에서 보상 삭제한다.

#### L2. 응답·로그로 새는 정보
- **상태:** 확인됨
- **근거**
  - 409 본문에 기존 직원·조직의 id 가 실린다 — `UniqueMarkers.java:132-137`. 익명 사용자 열거와 튜플 주체 노출이다.
  - 필터 오류가 입력 전체를 되돌린다 — `ScimFilter.java:182-184`.
  - 경고 로그에 DN(실명 포함)이 남는다 — `GroupOfNamesStrategy.java:144-145, 295-296`.
  - 기본 로그 레벨이 DEBUG 다(prod 프로필을 켜지 않으면).
- **권고:** 409 에서 id 를 빼고, 필터를 일정 길이로 잘라 되돌리고, DN 은 해시나 id 로 남긴다.

#### L3. SCIM 목록 책갈피가 호출자 구분 없이 공유된다
- **상태:** 의심
- **근거:** `DynamoDbPageBookmarkRepository.java:69-73`(키가 종류·방향·startIndex 뿐), `ScimPager.java:45-73`.
- **시나리오:** 데이터가 바뀌는 중에 다른 호출자가 같은 startIndex 의 책갈피를 덮어 쓰면, IdP 의 다음 쪽 경계가 옮겨져 직원 일부를 건너뛸 수 있다. 인가에 직접 닿지는 않고, IdP 의 매칭·가져오기 정확도 문제다.
- **권고:** 책갈피 키에 호출자(인증 주체)를 넣거나 커서를 서명해 응답에 싣는다.

#### L4. 영구히 거절되는 줄 하나가 이후 모든 회차의 뒤 단계 부여(롤업)를 막는다
- **상태:** 확인됨
- **근거:** `OpenFgaRelationTupleWriter.java:151-158`, README.md:889-890(일부러 fail-closed 로 골랐다고 적는다).
- **영향:** 부당 거부 쪽이다. 예컨대 LDAP 에서 온 긴 id 하나(README.md:630, LDAP 값은 길이를 막지 않는다) 때문에, 데이터를 고치기 전까지 새 조직 연결이 매일 쓰이지 않는다.
- **권고:** 영구 거절(400)된 줄은 단계 실패에서 빼고 따로 경보한다. 일시 실패만 뒤 단계를 막게 한다.

---

## 5. 질문 (답에 따라 판단이 바뀐다)

1. 운영에서 SCIM·`/admin`·`/actuator` 앞에는 무엇이 있나? 게이트웨이, mTLS, 메시, IdP IP 허용 목록 중 무엇이고, 소비 앱이 id 매핑 때문에 관리 API 에 닿아야 하나? (C1·C2·H5 의 실제 노출 범위)
2. OpenFGA 는 인증을 켜고 운영할 계획인가? 소비 앱에 Write 를 막는 수단이 있나? 다른 앱이 store 를 이름으로 찾나? (H4)
3. 소비 앱은 토큰의 어떤 값으로 employeeId 를 얻나? 0건·2건 이상일 때 어떻게 하나? 매핑을 캐시하나? (H5)
4. 퇴사자 회수 SLO 는 얼마인가? 운영 `sync.cron` 은? 소비 앱 Check 캐시 TTL 은? (M3)
5. 이 회사가 쓰는 Okta·Entra 테넌트는 503 + Retry-After 를 실제로 재시도하나? 몇 번, 언제까지? (H3 의 빈도)
6. DynamoDB 테이블 재생성·PITR 복원·리전 이관이 DR 계획에 있나? 그때 OpenFGA store 는 어떻게 하나? (H2)
7. 최대 계층 깊이와 루트 조직의 하위 조직 수, OpenFGA 버전과 `resolve` 깊이 설정은? 부정 Check 의 p99 를 재 봤나? (M7)
8. LDAP 배포에서 이름 기반 id 나 여러 도메인 검색 범위를 쓸 가능성이 있나? (M6)
9. 소비 앱이 같은 store 에 자기 타입을 더할 계획이 있나, 아니면 org store 와 앱 store 를 따로 묻는 구조인가? (M7)
10. 삭제 가드 ABORT 와 PARTIAL·FAILED 회차에 누가, 몇 분 안에 대응하나? 당번 경보가 연결돼 있나? (M2·M3)

---

## 6. 상위 5개 (고칠 순서)

1. **모든 HTTP 표면에 인증·인가 (C1·C2)** — SCIM 은 IdP 별 자격 증명, 관리 API 는 별도 포트 + 운영자 SSO·RBAC·감사(`wipe`·`force` 는 이중 승인), actuator 는 관리 포트로 분리한다.
2. **OpenFGA 인증 + 쓰기 권한 분리 + storeId·modelId 고정 (H4, M7)** — 장부에 쓰는 주체를 이 서버 하나로 좁힌다.
3. **SCIM 회수 의도를 저장하고 서버가 재시도한다 (H3)** — active=false·삭제 표시를 즉시 저장하고, 대기 중 회수 목록을 두며, 멤버 빼기는 요청 기준으로 지운다. 그래야 재적재·드리프트 감지가 회수 쪽으로 수렴한다.
4. **LDAP 기준선 신뢰 규칙 + 정기 장부 대조 (H1·H2·M5)** — 일시 실패가 있는 회차와 포인터 없음·store 불일치는 "기록 중"과 같이 다음 회차에 장부를 훑어 맞춘다. 주기 `LedgerAlignment` 와 잉여 튜플 경보를 둔다.
5. **로그인 주체 → employeeId 매핑 계약 (H5)** — 토큰의 불변 식별자를 튜플 주체로 쓰거나, 유일성을 강제한 정확 일치 매핑 API(인증, 0건·다건이면 거부)를 제공한다.
