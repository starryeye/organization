# 외부 리뷰 검증 — SCIM 경로·설정·API (S-a … S-p)

- 대상: `spike-ldap-5k` 작업 트리(origin/main `7aadbb7` 과 내용 동일). 리뷰 넷(`1-backend-lead.md`·`2-authz-lead.md`·`3-solutions-architect-lead.md`·`4-iam-platform-lead.md`)의 주장을 코드와 `docs/superpowers/specs/`·`plans/`·`README.md` 로 대조했다.
- 방법: 읽기만 했다. Gradle·테스트·Docker·`javap` 는 돌리지 않았다. 벤더(IdP) 동작은 주장하지 않고 문서가 적은 것만 옮겼다.
- 경로 약어: `core/.../X.java` = `core/src/main/java/dev/starryeye/organization/core/usecase/X.java`(다른 패키지는 따로 적음), `specs/X.md` = `docs/superpowers/specs/X.md`.
- "원칙"은 의뢰서의 팀 원칙 여섯(10만 명+·요청당 전원 읽기 금지 / 배포 하나엔 커넥터 하나 / 운영 배포 전·초기화 가능 / 표준대로·한 규칙 / OpenFGA Read 는 재적재에서만·store 번호 고정 / 인증은 마지막)이다.

---

## S-a. SCIM 회수가 실패하면 상태를 옛 값으로 되돌린다 (authz H3, IAM H4)

**코드 사실: 부분적으로 맞음.**
- 맞음 — 비활성화: `reconcileUser` 는 실패가 하나라도 있으면 `requested.withActive(existing.active())` 를 저장한다(`core/.../IncrementalSyncUseCase.java:1024-1029`, 커밋 `:252-259`). 응답은 503(`connector-scim/.../ScimUserHandler.java:125-130`). 직원 삭제: 튜플을 못 지운 조직은 멤버십을 남기고, 하나라도 실패하면 레코드를 지우지 않는다(`IncrementalSyncUseCase.java:521-535`, `:544-546`), 응답 503(`ScimUserHandler.java:114-117`). 멤버 빼기: 지우기에 실패한 멤버는 계속 멤버다(`:1073-1083`). `mode=tuples` 재적재는 상태를 진실로 보고 `TupleMapper.toTuples(state)` 를 있어야 할 줄로 쓴다(`core/.../ScimRebuildUseCase.java:124-147`). 드리프트 지표·아카이빙의 후보는 상태에서 나온다(`IncrementalSyncUseCase.java:895-918`, `core/.../SnapshotArchiveUseCase.java:108-119`).
- 좁혀야 할 것 ① "재적재가 권한을 다시 쓴다"는 **비활성화가 일부만 반영된 경우**에만 맞다. 직원 삭제·멤버 빼기는 지워진 줄의 멤버십을 상태에서 빼므로(`:525`, `:1079`) 재적재가 되살리지 않는다 — 남은 줄을 지우지 않을 뿐이다.
- 좁혀야 할 것 ② "드리프트·아카이빙 어디에도 안 보인다"는 **전부 실패**한 경우에만 맞다. 한 직원의 줄은 대상별로 한 Write 요청에 담기므로(`authz-openfga/.../OpenFgaRelationTupleWriter.java:258-262`, 한도 100) 보통 전부/전무이고, 그래서 이 경우가 흔하다. 소속이 100개를 넘어 일부만 반영되면 아카이빙은 "빠진 튜플"로 WARN 을 남긴다(`SnapshotArchiveUseCase.java:140-146`) — 그 신호를 보고 재적재를 돌리면 회수된 줄을 다시 쓴다.
- 리뷰어가 빠뜨린 것: 재적재가 아니어도 **그 직원을 건드리는 다음 쓰기**(예: `active` 를 싣지 않은 이름 변경 PATCH)는 상태의 `active=true` 를 목표로 삼고, Check 기준선이 빠진 줄을 보고 다시 쓴다(`:244-261`, `:904-924`).
- Check 기준선 뒤에도 이 규칙이 필요한가: **`active` 에 대해서는 근거가 낡았다.** 재시도의 델타는 저장된 `active` 가 아니라 Check 기준선에서 나온다 — 후보는 `active` 필터 전의 멤버십이고(`:895-898`) 실제는 BatchCheck 다(`:904`). 요청값(`false`)을 저장해도 재시도는 남은 줄을 지우고, 재적재·아카이빙·드리프트 지표가 모두 회수 쪽으로 수렴·탐지한다(상태 기준선이 "없어야 함"이 되어 `extra` 로 센다). 코드에 남은 근거(`:204-206` "그대로 저장하면 다음 동기화가 '이미 목표 상태'라고 오판", `:1019-1023`)는 상태 기준선 시절의 논리다. 반면 **직원 삭제·멤버 빼기에서 멤버 줄을 남기는 것은 여전히 필요하다** — 재시도의 후보가 상태의 소속 줄(`:1165-1169`)과 요청에 나온 멤버 중 저장된 것(`:471`)에서만 나오기 때문이다.

**문서상 지위: 이미 결정·수용(전제: IdP 재시도) — 다만 "Check 기준선 뒤 근거가 낡음"과 "일부 실패 뒤 재적재·다음 쓰기가 다시 씀"은 처음 드러남.**
- "반영된 만큼만 저장"을 정한 곳: `specs/2026-08-14-organization-sync-design.md:499`(§7.2). 이유: 상태 기준선이라 저장하지 않으면 "OpenFGA 에는 반영됐는데 DynamoDB 는 모르는 상태가 되어 영구히 어긋난다", 그리고 "IdP 재시도는 같은 최종 상태를 목표로 하므로".
- IdP 재시도는 명시적 전제다: `specs/2026-09-01-scim-concurrency-design.md:34-60`(§1.1 "이 시스템의 수렴 기제는 재시도다"), `:216-218`("IdP 가 재시도한다는 전제 위에 있었다"). 아웃박스·주기 리컨실러는 §1.4(`:86-87`)에서 버렸다.
- 바로 이 시나리오(퇴사자 비활성화 일부 실패 → `active=true` 로 되돌림 → 재시도 없으면 권한 남음)는 `specs/2026-09-28-full-audit.md:718-720`(M4)이 짚었고, 처방은 500→503 + `Retry-After`(`specs/2026-10-05-scim-error-signals-design.md:95-102`)였다. 같은 문서 §10(`:195`)과 README:787-789 는 IdP 의 503 처리가 "문서·추정"(Okta 5xx 반응은 문서에 없음)이라 적는다.
- 다시 보지 않은 것: scim-concurrency §3.1(`:255`)은 기준선 전환으로 **쓰기 순서**의 근거가 무효가 됐다고 스스로 바로잡았지만 `reconcileUser` 의 근거는 다시 보지 않았다. 재적재가 "틀린 채로 굳는다"는 기록은 순서 뒤집힘에 대해서만 있다(`:119-121`).

**원칙과의 관계:** "OpenFGA Read 는 재적재에서만" — 리뷰어 처방 중 주기적 장부 대조(Read 훑기)는 원칙과 부딪힌다. 의도를 저장하는 처방(요청한 `active` 저장, 삭제 묘비·아웃박스)은 Read 가 필요 없어 부딪히지 않는다. "운영 배포 전" — 상태의 뜻을 바꿔도 이관 비용이 없다.

**심각도: High** (리뷰어와 같음). 10만 명 규모에서 OpenFGA 일시 장애는 반드시 생기고, 비활성화가 SCIM 의 핵심 회수 경로다. 회복이 IdP 재시도(Okta 미확인)에만 달려 있고, 흔한 "전부 실패" 경우는 어떤 신호로도 보이지 않는다.

---

## S-b. SCIM 경로의 "반영됐는지 모르는" 쓰기 (backend H1, SCIM 부분)

**코드 사실: 맞음(좁은 창).**
- 일시 오류로 재시도를 다 쓴 배치는 통째로 실패로 센다(`OpenFgaRelationTupleWriter.java:406-428`, 포트 계약 `core/.../port/RelationTupleWriter.java:23-27`). 쓰기는 중복 무시 옵션이라 "커밋 뒤 응답만 잃음"은 앱 재시도(`:395`) 하나만 성공해도 수렴한다 — 남는 것은 재시도가 전부 실패한 경우다.
- 새 멤버는 `written` 이나 Check 기준선(`beforeTuples`)에 없으면 멤버 줄을 저장하지 않는다(`IncrementalSyncUseCase.java:1065-1071`). 쓰기 뒤 리스 재확인이 실패하면 커밋 없이 503(`:974-978`).
- 이후 비활성화·삭제의 후보는 상태의 소속 줄(`:1165-1169`), 조직 삭제는 저장된 멤버(`:579-584`), 아카이빙도 상태(`SnapshotArchiveUseCase.java:112`)라 그 튜플은 후보가 되지 않는다.
- 덧붙임: IdP 가 같은 요청을 재시도하면 Check 기준선이 그 튜플을 "이미 있음"으로 보고 멤버 줄을 저장해 수렴한다(`:1067`). S-a 와 달리 `mode=tuples` 재적재는 장부를 훑어 이 줄을 지운다(`ScimRebuildUseCase.java:141`).

**문서상 지위: 이미 결정·수용.** `specs/2026-09-01-scim-concurrency-design.md:279-281`(§3.1 "두 순서 모두 재시도가 오지 않으면 멤버십 없는 튜플(§5.4)을 남길 수 있다 … 재적재로만 고쳐진다"), `:479-490`(§5.4 "멤버십 자체가 사라진 잘못된 튜플은 후보에 들어오지 않는다 … 재적재(`mode=tuples`)가 유일한 해결책"), `:80`(§1.3 표 "§5.4 고아 | 드묾 | 없음 — 재적재만 답"). 포트 자바독도 "쓰고 응답을 잃은 묶음은 반영됐는지 몰라 실패로 센다". ④-1 §11(`specs/2026-10-04-immutable-identifiers-design.md:215-217`)의 "조직 POST 5xx 재시도의 고아"는 새 UUID 로 생기는 다른 경우다(S-o).

**원칙과의 관계:** "OpenFGA Read 는 재적재에서만" — backend 처방 3(탐지 전용 야간 장부 훑기)은 원칙과 부딪힌다. 처방 2(의도 기록·보상 지우기)는 부딪히지 않는다.

**심각도: Low** (리뷰어 High 는 LDAP 포함 평가). 커밋 후 응답 유실 + 앱 재시도 전부 실패(또는 쓰기 직후 리스 확인 실패) + IdP 미재시도 + 그 사이 소속 변경이 겹쳐야 하고, 재적재가 지운다.

---

## S-c. `active` fail-open (backend M1, authz M1, IAM M1)

**코드 사실: 맞음.** POST·PUT 본문에 `active` 가 없으면 `true`(`connector-scim/.../ScimMapper.java:51-52`). PUT 은 본문으로 통째로 교체하고 기존 값을 보지 않는다(`ScimUserHandler.java:68-69`, `before -> user`). PATCH `remove active` 도 `true`(`ScimPatchApplier.java:367-368`). 값의 모양은 엄격하다 — boolean 또는 `"true"`/`"false"` 외에는 400(`ScimPatchApplier.java:527-540`).

**문서상 지위: 이미 결정·수용 — 근거는 일관성뿐이고 회수 방향 위험은 따진 적이 없다.**
- 2026-10-06 "active 엄격"(§5.2, `specs/2026-10-06-scim-request-interpretation-design.md:125-132`)은 **값의 모양**만 정했다 — 그 밖의 값은 400, 이유는 "조용히 비활성화하지 않는다"(S8). 없음·remove 의 기본값은 이 문서가 정하지 않았다.
- `remove active` = 활성: `specs/2026-09-26-scim-name-parts-design.md:90`(§7.2 표, 근거 "POST 에 `active` 가 없을 때와 같은 규칙"), README:666. POST·PUT 기본값은 코드 주석(`ScimMapper.java:51` "선택 필드다. 없으면 활성으로 본다")과 점검 표(`specs/2026-09-28-full-audit.md:175` Okta PUT "`active` 가 빠지면 활성(문서화된 규칙)" ✅).
- S8 은 반대 방향(조용히 비활성화)만 다뤘다. 권한을 여는 방향의 위험은 문서에 없다.

**RFC:** RFC 7643 §4.1.1 은 `active` 의 확정적 의미를 서비스 제공자가 정한다고만 하고 기본값을 두지 않는다. RFC 7644 §3.5.1(PUT)은 본문에서 빠진 readWrite 속성을 "클라이언트가 주장하지 않은 것"으로 볼 수 있고(MAY) 제공자가 기본값을 줄 수 있다(MAY)고 한다. §3.5.2.2 는 단일 값 `remove` 뒤 그 속성을 "할당되지 않음"으로 본다. 그러므로 "없음 = 활성"도 "없음 = 비활성"도 표준 안이다. "PUT 에서 빠지면 기존 값 유지"는 PUT 의 전체 교체 뜻과 맞지 않고, "`remove active` 는 400"은 표준 근거가 없다(`active` 는 required 가 아니다).

**원칙과의 관계:** "표준대로, 한 규칙" — 지금 코드는 POST·PUT·remove 모두 "없음 = 활성" 한 규칙이다. 리뷰어 처방(PUT 은 유지, POST 는 활성, remove 는 비활성 또는 400)은 경우를 셋으로 나눠 원칙과 부딪힌다. 원칙 안의 대안은 한 규칙의 방향을 바꾸는 것("없음 = 비활성")뿐이고, 대가는 `active` 를 싣지 않는 POST 가 권한 없는 직원을 만드는 것이다(어느 IdP 가 그렇게 보내는지는 확인하지 않았다).

**심각도: Low** (리뷰어 Medium 보다 낮음). 표준이 허용한 선택이고, 비활성 직원에게 `active` 를 뺀 PUT 이나 `remove active` 를 보내는 클라이언트가 있을 때만 생긴다. 다만 근거가 "POST 와 같은 규칙"뿐이니 회수 방향의 판단을 한 줄 남길 만하다.

---

## S-d. 비활성 동안 멤버십 유지, 재활성화 때 복원 (IAM M2)

**코드 사실: 맞음.** 비활성 직원은 `direct_member` 를 만들지 않고 멤버 줄은 그대로다(`core/.../tuple/TupleMapper.java:131-133`). 재활성화는 `affectedGroupHeadersOf`(`IncrementalSyncUseCase.java:1165-1169`)로 소속 전부를 찾아 다시 쓴다(`:244-261`). 리뷰어의 (b) 로그인 재사용: 비활성 직원도 `userName` 표지판을 쥐므로 같은 이름의 새 POST 는 409 다(`:275-279`, `specs/2026-10-14-unique-markers-design.md:166` "비활성화는 표지판 그대로"). 그 뒤 IdP 가 기존 리소스에 연결하는지는 IdP 동작이라 확인하지 않았다.

**문서상 지위: 이미 결정·수용(의미) — "오래 비활성이던 동안 IdP 가 그룹 변경을 보내지 않아 낡은 소속이 복원된다"는 위험은 처음 드러남.** `specs/2026-08-14-organization-sync-design.md:719`(비활성 유저는 튜플을 만들지 않는다), `specs/2026-09-25-ldap-disabled-account-design.md:20-22`("SCIM 과 같은 의미의 비활성 … 멤버십은 남고 튜플만 사라진다 … 다시 풀면 다음 동기화에서 권한이 돌아온다"), `specs/2026-09-28-full-audit.md:150`, `:1321`, `:1329`("비활성은 멤버십을 유지한다(Entra 요구)").

**원칙과의 관계:** "표준대로" — 멤버십은 표준 신호(그룹 멤버 변경·DELETE)가 올 때만 바꾸는 선택이라 원칙과 맞는다. 리뷰어 처방(N일 뒤 멤버십 정리, 재활성화 때 "미확인")은 표준 밖 규칙을 더하는 쪽이다.

**심각도: Low** (리뷰어 Medium 보다 낮음). 복원되는 것은 IdP 가 마지막으로 알려 준 소속이고, 그것이 낡았는지는 IdP 동작에 달렸다.

---

## S-e. Entra 레거시 `remove members` + `value` → 400 (IAM H5)

**코드 사실: 맞음.** `ScimPatchApplier.java:207-216` 가 400 `invalidValue` 로 거절하고 안내 문구는 `:45-47`. 같은 요청의 다른 연산도 반영되지 않는다(README:685). 이 원인 전용 지표는 없다(SCIM 지표는 `app-scim/.../ScimSyncMetrics.java` 의 넷 — drift·lock·lease_lost·patch.ignored).

**문서상 지위: 이미 결정·수용.** `specs/2026-09-26-group-member-patch-design.md:45-58`(§2 결정표 "값 붙은 `remove members` | 400 `invalidValue`. Entra 는 `aadOptscim062020` 을 켜야 한다", 버린 안 "적힌 멤버만 삭제(AWS 식)"). 이유: 우리 회사 디렉터리라 Entra 설정을 우리가 바꿀 수 있고, 거절은 Entra 로그에 보이는 큰 실패이며, 추측해 받으면 다른 IdP 의 다른 뜻을 알 길이 없다. 대가도 적었다 — "옵션을 켜기 전까지 Entra 의 멤버 빼기는 반영되지 않는다(옛 소속 권한이 남음)". README:680-686("반드시 붙인다"), `specs/2026-09-28-full-audit.md:157`("알려진 결정"), `:208`(AWS·Auth0 "새 근거").

**원칙과의 관계:** "표준대로" — 이 결정이 원칙의 적용이다. 리뷰어 처방 1(값 붙은 remove 를 "열거한 멤버만"으로 받기)은 원칙과 정면으로 부딪힌다. 처방 2(연결 검증·이 400 을 경보)는 부딪히지 않는다.

**심각도: Low** (리뷰어 High 보다 낮음) — 문서의 전제("Entra 설정을 우리가 통제한다")가 지켜지는 한. 그 전제가 깨지는 배포라면 Medium.

---

## S-f. SCIM 대량 회수 속도 가드 없음 (SA M-6)

**코드 사실: 맞음.** `core/.../guard/DeletionGuard.java:8-13` 이 "SCIM 의 의도된 단건 삭제에는 적용하지 않는다"고 적고, `removeUser`·`removeGroup`(`IncrementalSyncUseCase.java:508-576`)과 비활성화(`changeUser`, `:292-305`)에 비율·속도 검사가 없다. 여러 요청에 걸친 누적(시간당 비활성화·삭제 수)을 보는 장치도 없다.

**문서상 지위: 가드를 SCIM 에 걸지 않는 것은 이미 결정 — 여러 요청에 걸친 누적 상한은 처음 드러남.** `specs/2026-08-14-organization-sync-design.md:634-648`(§9.2 "LDAP full sync 에만 적용한다. SCIM(의도된 단건 삭제)과 `rebuild`(의도된 전체 삭제)에는 적용하지 않는다"). SCIM 쪽 가드는 `mode=tuples` 의 빈 조직도 가드뿐이다(`specs/2026-09-30-cluster-jobs-baseline-design.md:84`). 누적 속도·비율은 어느 문서에도 없다.

**원칙과의 관계:** "표준이 정한 신호만 받는다"와 긴장한다 — DELETE·`active=false` 는 표준 신호이고, 비율 가드는 그 신호를 휴리스틱으로 의심한다. push 모델이라 LDAP 가드의 분모(기준 튜플 수) 같은 "전체 그림"이 요청 하나에 없다.

**심각도: Medium** (리뷰어와 같음). 잘못된 IdP 범위 변경은 권한을 빼는 쪽(가용성 사고)이지만, DELETE 였다면 복구가 재프로비저닝이고 그때 모든 id 가 바뀐다(S-m).

---

## S-g. 전역 락 안의 SCIM 쓰기에 전체 기한이 없고 재시도 계층이 겹친다 (backend M4)

**코드 사실: 부분적으로 맞음.**
- 맞음: SCIM 쓰기(`IncrementalSyncUseCase.java:737-809`)에 전체 기한이 없다. main 코드의 `.timeout(` 은 `LeaseKeeper`(`core/.../LeaseKeeper.java:105,114`), `LocalWriteQueue`(`:59`, 줄 대기 3초), `SyncJobs`(`:253`, 회차 기한)와 헬스 인디케이터뿐이다. **`LeaseKeeper` 는 일의 길이를 묶지 않는다** — 갱신이 토큰 조건으로 실패하거나, 마지막 성공 갱신 뒤 2×주기 안에 갱신이 성공하지 못할 때만 일을 취소한다(`LeaseKeeper.java:58-79`, `:89-117`). DynamoDB 가 건강하면 매달린 OpenFGA 호출 동안 리스는 계속 늘어난다. OpenFGA 클라이언트는 `ClientConfiguration().apiUrl(...)` 만 쓴다(`authz-openfga/.../StoreBootstrapper.java:302-311`). 앱 재시도 3회(`OpenFgaErrors.java:38-42`, `OpenFgaProperties.java:20`, `OpenFgaRelationTupleWriter.java:395`). DynamoDB 클라이언트에는 `overrideConfiguration`·`apiCallTimeout` 이 없다(`storage-dynamodb/.../DynamoDbConfig.java:26-36`).
- 틀린 부분: 시나리오의 "BatchCheck 와 Write 재시도(앱 4번 × SDK 시도 × 10초)" 중 **BatchCheck 에는 앱 재시도가 없다** — `OpenFgaRelationTupleChecker` 에 `retryWhen` 이 없다(`:108-128`). SDK 재시도만 붙는다. 쓰기의 연속 실패 차단기(3배치)는 대개 한 배치인 SCIM 한 건에는 걸리지 않는다(`specs/2026-09-29-rebuild-jobs-fixed-store-design.md:90`).
- SDK 기본값(요청 10초·재시도 3회)은 이 검증에서 다시 확인하지 않았다. 프로젝트 문서도 같은 값을 바이트코드로 확인했다고 적는다(`specs/2026-09-28-full-audit.md:597`).

**문서상 지위: 알려졌으나 미결정 — DynamoDB `apiCallTimeout` 부재 한 조각만 처음 드러남.**
- 처리량(전역 락): README:850, `specs/2026-10-14-unique-markers-design.md:219`, `specs/2026-10-13-job-robustness-design.md:220`.
- 기한 없음은 따로 알려져 있다: `specs/2026-10-02-big-change-lock-design.md:186`(§11 "떼어 낸 SCIM 쓰기에는 전체 시간 상한이 없다. 하위 호출이 매달린 동안에도 하트비트가 락을 지킨다(단일 호출은 SDK 타임아웃이 묶는다)"). 재시도 계층의 겹침은 `specs/2026-09-28-full-audit.md:590`(C7 "같은 뿌리의 작은 것 — SCIM 쓰기 한 건도 … Write 배치 하나에 최대 약 160초"). C7 의 처방(회차 기한 30분·연속 실패 차단기, rebuild-jobs §5 `:84-95`)은 회차에만 적용된다. 문서의 "단일 호출은 SDK 타임아웃이 묶는다"는 DynamoDB 쪽에서는 `apiCallTimeout` 없이 HTTP 클라이언트·SDK 재시도 기본값에 맡겨진다는 점을 적지 않았다.

**원칙과의 관계:** 직접 부딪히는 것은 없다(10만 규모의 쓰기 처리량과 간접).

**심각도: Medium** (리뷰어와 같음). OpenFGA 가 느린 동안 SCIM 쓰기 한 건이 전역 락을 수십 초~수 분 쥐고, 그동안 모든 SCIM 쓰기(비활성화 포함)가 503 이다.

---

## S-h. 운영 프로필이 개발 기본값을 물려받는다 (backend M5, authz M8, SA M-5, IAM M10)

**코드 사실: 부분적으로 맞음.**
- 맞음: 두 앱의 `application-prod.yml` 은 로그 형식(ECS)과 로그 레벨만 바꾼다(`app-ldap/src/main/resources/application-prod.yml:10-15`, app-scim 같음). 기본 `application.yml` 에 DynamoDB `endpoint: http://localhost:8000`·`create-table-on-startup: true`(app-ldap `:75-79`, app-scim `:26-30`), OpenFGA `api-url: http://localhost:8080`(app-ldap `:69`, app-scim `:20`), LDAP `url: ldap://localhost:1389`·`bind-password: adminpassword`(app-ldap `:30-33`)가 있다. 속성 기본값도 `createTableOnStartup = true`(`storage-dynamodb/.../DynamoDbProperties.java:18`)이고, 테이블이 없으면 만든다(`TableInitializer.java:34-49`). app-ldap 은 `show-details: always`(`app-ldap/src/main/resources/application.yml:92`).
- 틀린 것 ① **DEBUG 로그는 prod 프로필이 `INFO` 로 덮는다**(`application-prod.yml:14-15`). authz M8 의 "DEBUG 로그를 덮지 않는다"는 틀렸다.
- 틀린 것 ② **app-scim 은 `show-details: when-authorized`** 다(`app-scim/src/main/resources/application.yml:48-52`). 헬스 상세 공개는 app-ldap 만이다.
- 좁혀야 할 것 ③ 정적 자격 `local/local` 은 `endpoint` 가 비어 있지 않을 때만 쓴다(`DynamoDbConfig.java:29-34`). 실제 AWS 로 가려면 endpoint 를 비워야 하므로 그때는 기본 자격 사슬이다. endpoint 를 그대로 두면 localhost 로 붙다 실패한다 — `create-table-on-startup` 이 켜져 있으면 `ensureTable().block()` 이 `ResourceNotFoundException` 이 아닌 연결 오류로 기동을 멈춘다(`TableInitializer.java:34-48`). LDAP 기본 URL·비밀번호도 덮지 않으면 바인드가 실패한다. **조용히 위험해지는 것은 `create-table-on-startup: true`, app-ldap `show-details: always`, URL 만 바꾸고 `ldap://` 를 둔 평문 바인드**다.
- 테이블명 오타 시나리오: 우리 쪽 코드로는 맞다 — 새 빈 테이블을 만들고 정상 기동한다. store 는 이름(`openfga.store-name`)으로 찾으므로 옛 튜플은 그대로이고, 옛 id 의 GET 은 404 다. 기동 때 "테이블은 비었는데 장부에 줄이 있음"을 보는 확인은 없다. 빈 조직도에서 `mode=tuples` 는 빈 조직도 가드로 장부를 지우지 않는다(`ScimRebuildUseCase.java:111-115`). IdP 가 404 뒤 다시 POST 해 새 UUID 를 받는지는 IdP 동작이라 확인하지 않았다.

**문서상 지위: 일부 이미 결정(개발 기본값), 일부 처음 드러남.**
- `create-table-on-startup`: README:124-127 "로컬 기본값은 `true` 이고, **실제 AWS 에서는 끄는 것을 전제로 한다** — 테이블 수명주기는 배포 도구가 관리해야" — 개발 기본값이라는 결정은 있으나 prod 프로필로 강제하지 않는다.
- app-scim 헬스 상세는 커밋 `b3c96e2` 에서 "인증 없는 포트에 openfga.api-url·table-name·예외 메시지가 나간다"는 이유로 `when-authorized` 로 바꿨다(그 yml 주석 `:49-51`). 같은 이유가 app-ldap(인증 없음, LDAP URL·base DN 까지 나감)에도 해당하지만 바꾸지 않았고 까닭은 문서에 없다 → **처음 드러남(두 앱의 불일치)**.
- LDAP 평문·기본 비밀번호, 테이블명 오타가 SCIM 신원을 가르는 결과 → 문서 없음, 처음 드러남.

**원칙과의 관계:** "운영 배포 전" — 지금 바꿔도 이관 비용이 없다. "인증은 마지막" — `when-authorized` 는 인증이 생겨야 뜻이 생긴다.

**심각도: Medium** (리뷰어와 같음). 첫 배포 전 설정 위생이고, 가장 실질적인 것은 SCIM 의 테이블 자동 생성(오타 하나가 신원 분리·권한 공백으로 이어짐)이다.

---

## S-i. 정보 노출 (authz L2)

**코드 사실: 부분적으로 맞음.**
- 409 본문에 기존 리소스 id — 맞음. 문구가 `userName=%s, id=%s`·`externalId=%s, id=%s` 이고(`core/.../UniqueMarkers.java:132-137`), 라우터가 메시지를 그대로 `detail` 로 낸다(`connector-scim/.../ScimRouter.java:156-158`).
- 필터 오류가 입력 전체를 되돌림 — 맞음(`ScimFilter.java:182-184`, `detail + " — filter: " + text`). 쿼리 문자열은 Netty 시작 줄 한도(약 4KB)까지지만, `POST …/.search` 본문(`ScimListHandler.java:57`)이면 본문 한도(256KB)까지 되돌린다.
- WARN 로그의 DN — 맞음(일부). `SkippedEntries` 의 요약 경고가 사유별 예시 DN 다섯 개를 싣는다(`connector-ldap/.../strategy/SkippedEntries.java:57-68`, 기록 자리 `GroupOfNamesStrategy.java:144-145`). 리뷰어가 함께 든 `:295-296` 은 WARN 이 아니라 INFO(`log.info`)다.

**문서상 지위:**
- 409 문구: 이미 결정 — `specs/2026-09-28-scim-write-lock-design.md:86-87` 이 문구(`… userName=<이름>, id=<아이디>`)를 정했다. 노출 관점은 논의 없음.
- 필터 되돌림: 문서 없음 → 처음 드러남.
- DN 예시: 이미 결정 — `specs/2026-10-05-ldap-read-design.md:92-94`, `:100`("예시 DN 다섯 개를 경고 한 줄로", "어느 쪽이 사라졌는지 알아야 고친다"). 개인정보 관점은 논의 없음(대조: SCIM 쪽은 값을 로그에 남기지 않는다고 정했다 — README:745).

**원칙과의 관계:** "인증은 마지막" — 409 id·필터 되돌림은 익명 호출자에게 나가는 것이 문제의 대부분이다. 409 의 id 는 지금도 익명 `GET /Users?filter=userName eq "…"` 로 얻을 수 있다.

**심각도: Low** (리뷰어와 같음).

---

## S-j. SCIM 목록 책갈피를 호출자끼리 공유 (authz L3, 의심)

**코드 사실: 맞음(메커니즘) — 영향은 좁다.** 책갈피 키는 (종류, 정렬 방향, startIndex)뿐이고(`storage-dynamodb/.../DynamoDbPageBookmarkRepository.java:69-73`), 마지막 쓰기가 이긴다(`:57-67`, `ScimPager.java:66`). 호출자 구분은 없다. 위치는 서버가 실제 데이터로 계산한 키라 다른 호출자가 엉뚱한 위치를 심을 수는 없고, 그 사이 데이터가 바뀌지 않으면 같은 startIndex 의 책갈피는 같은 위치다. **데이터가 바뀌는 중이면** 다른 호출자가 나중에 계산한 위치가 IdP 의 다음 쪽 경계가 되어, 앞에서 지워진 수만큼 건너뛰거나 더해진 수만큼 겹칠 수 있다 — IdP 자신이 책갈피 없이 건너뛰기로 읽을 때와 같은 종류의 흔들림이다. 정렬 속성은 종류마다 하나라(`ScimQuery.java:65-73`) 다른 정렬의 책갈피가 섞이지는 않는다.

**문서상 지위: 이미 결정·수용.** `specs/2026-09-25-scim-list-filter-design.md:109-113`(§4.4 "책갈피 키는 `(리소스 종류, 정렬 방향, startIndex)` … 두 가져오기가 동시에 같은 책갈피를 써도 둘 다 올바른 위치라, 마지막 쓰기가 이겨도 된다", "RFC 7644 §3.4.2.4 는 페이지 요청 사이에 결과가 달라질 수 있음을 인정한다"). "둘 다 올바른 위치"는 두 계산 사이에 데이터가 바뀌지 않을 때만 엄밀히 참이라는 단서는 적혀 있지 않다.

**원칙과의 관계:** "표준대로" — RFC 7644 의 index 페이징을 따른 결과다.

**심각도: Low** (리뷰어와 같음).

---

## S-k. 익명 비용 증폭 (authz M9, backend L3, SA M-7)

**코드 사실: 맞음.**
- 필터 없는 목록의 startIndex=1 은 책갈피를 보지 않고 COUNT 를 한다(`ScimPager.java:42-56`). COUNT 는 GSI1 파티션(ALL 프로젝션) 전체를 1MB 씩 끝까지 훑는다(`DynamoDbDirectoryQueryRepository.java:187-200`). 책갈피 없는 큰 startIndex 는 N−1 건을 키만 읽으며 건너뛰고(`:206-224`) COUNT 도 한다.
- 직원 상세는 경로 최대 200(`AdminQueryUseCase.java:42`) 각각에 단건 Check 를 보낸다(`:251-262`, 동시 8 `:45`). 일관성은 `HIGHER_CONSISTENCY`(`OpenFgaRelationTupleChecker.java:50`, `:66-79`)이고 관계가 `member`(롤업)라 그래프를 따라간다.

**문서상 지위: 이미 결정·수용(인증 슬라이드로 미룸).** COUNT·건너뛰기 비용은 `specs/2026-09-25-scim-list-filter-design.md:263-266`(§9 비용표)와 `:335-336`(§12 "인증이 없다. 누구나 필터 없는 목록 첫 페이지를 반복 호출해 매번 COUNT … 인증 슬라이드에서 함께 막힌다"). 직원 상세 200 상한은 `specs/2026-08-19-admin-query-api-design.md:216`(§7.2). 관리 조회의 익명 증폭은 README:385-387, `specs/2026-10-06-read-paths-design.md:23`. Check 200건이 **공유 OpenFGA** 에 주는 부하는 따로 논의되지 않았다.

**원칙과의 관계:** "요청당 전원 읽기 금지"와 직접 부딪힌다 — 첫 페이지 COUNT 와 책갈피 없는 건너뛰기는 요청 하나가 10만 건을 읽는다. S-1 은 RFC 7644 §3.4.2 의 `totalResults`(필수)를 채우려고 "가져오기당 한 번"으로 받아들였다(§10). "인증은 마지막" — 익명 반복은 그 슬라이드로 미뤘다.

**심각도: Medium(인증 전) / Low(인증 뒤)** — 리뷰어 범위(Medium·Low) 안.

---

## S-l. 로그인 → employeeId 매핑이 무인증 접두사 검색에 기대고, LDAP `userName`·SCIM 직원 `externalId` 는 유일하지 않다 (authz H5, backend M7, SA M-9, IAM M7)

**코드 사실: 맞음.** `?userName=` 은 소문자 접두사로 GSI1(최종 일관)을 찾는다(`storage-dynamodb/.../DynamoDbDirectorySearchRepository.java:38-43`, `admin-api/.../AdminQueryController.java:37-54`). LDAP `userName` 은 로그인 속성 원문이고 없으면 id 로 대신하며 유일성 검사가 없다(`connector-ldap/.../strategy/GroupOfNamesStrategy.java:218-219`). LDAP `externalId` 는 DN 이다(`:91`). SCIM 직원 `externalId` 는 중복을 막지 않아 `?externalId=` 가 여러 줄을 줄 수 있다(`AdminQueryUseCase.java:76-83`). 반면 SCIM `userName` 은 표지판으로 대소문자 무시 유일이다 — **SCIM 배포의 `userName` 매핑은 유일하고, LDAP 배포는 아니다.** 관리 API 에 인증은 없다.

**문서상 지위: 매핑 경로는 이미 결정·수용 — LDAP `userName` 중복의 영향과 "0건·2건 이상" 계약은 처음 드러남.**
- ④-1: `specs/2026-10-04-immutable-identifiers-design.md:104-111`(§5 "권한을 묻는 앱이 id 를 찾는 길" — `?userName=` 접두사 + 새 `?externalId=` 정확 일치), `:51-52`(§3.2 "직원의 `externalId` 는 중복을 판정하지 않는다 … `?externalId=` 가 여러 줄"), `:212-213`(§11 "토큰 클레임과의 자동 연결은 하지 않는다"), `:237`(GSI3 지연). README:336-351.
- LDAP `userName`: `specs/2026-09-28-scim-write-lock-design.md:89` 가 "LDAP 전체 동기화는 이 확인을 거치지 않는다"고만 적는다. README 는 "`userName` 이 같은 줄을 고른다"(:339)만 안내하고, 같은 `userName` 이 둘일 때의 계약은 없다.

**원칙과의 관계:** "인증은 마지막" — 무인증 부분은 의도적으로 미뤘다. "표준대로" — RFC 7643 은 `externalId` 의 유일성을 요구하지 않으므로 SCIM 직원 `externalId` 를 막지 않는 것은 표준과 맞다. "배포 하나엔 커넥터 하나" — 매핑의 유일성이 배포 종류마다 다르다(SCIM 유일, LDAP 아님).

**심각도: Medium** (authz High, 나머지 Medium). 매핑이 틀리면 튜플이 맞아도 판정이 틀린다. LDAP 배포에서 디렉터리가 로그인 속성의 유일성을 지키지 않으면 High 다.

---

## S-m. SCIM 배포에서 DynamoDB 가 id 발급처인데 PITR·삭제 보호·IaC 가 없고, 문서화된 복구는 모든 id 를 바꾼다 (SA H-6)

**코드 사실: 맞음.** POST 마다 무작위 UUID(`ScimUserHandler.java:47-48`, `ScimGroupHandler.java:41-42`). 테이블 생성은 PAY_PER_REQUEST·GSI·TTL 만이고 PITR·삭제 보호·CMK 가 없다(`storage-dynamodb/.../TableInitializer.java:51-92`; 저장소 어디에도 `PointInTimeRecovery`·`deletionProtection` 이 없다). 저장소에 IaC 가 없다(`git ls-files` 에 Terraform·CDK·CloudFormation·Helm·Dockerfile 없음). `wipe` 는 조직도·표지판을 비우고(`ScimRebuildUseCase.java:217-240`), 이어지는 재프로비저닝의 POST 는 새 UUID 를 받는다.

**문서상 지위: "유일한 사본·wipe 뒤 재프로비저닝"은 이미 결정, "복구가 모든 id 를 바꿈"과 백업·PITR 은 처음 드러남.**
- README:261-271("SCIM 배포에서 DynamoDB 는 조직도의 **유일한 사본**", "IdP 콘솔에서 전체 재프로비저닝을 걸어야 조직도가 돌아온다"). `specs/2026-09-01-scim-concurrency-design.md:172-189`(§1.5 "어긋남이 의심될 때 확실히 되돌리는 방법은 `mode=wipe` + IdP 전체 재프로비저닝 하나뿐"). 테이블 수명주기는 배포 도구 몫(README:124-127).
- ④-1 §10(`specs/2026-10-04-immutable-identifiers-design.md:205`)은 `externalId` 기반 결정적 UUID(v5)를 "IdP 가 재사용할 수 있어 M7 이 돌아온다"는 이유로 버렸다 — 그 결정에서 "복구하면 모든 id 가 바뀐다"는 대가는 따지지 않았다. README:931 은 일회성 이관에서만 "옛 id 를 저장해 두었다면 새 id 를 다시 얻는다"고 적는다.
- PITR·백업·삭제 보호·RPO/RTO 는 문서에 없다.

**원칙과의 관계:** "store 번호 영구 고정"과 비대칭이다 — OpenFGA store id 는 영구히 고정하면서, 소비 앱이 저장하는 SCIM 직원·조직 id 는 문서화된 복구 절차가 모두 바꾼다. "운영 배포 전" — 지금 정하면 이관 비용이 없다.

**심각도: High** (리뷰어와 같음). 테이블 사고나 `wipe` 한 번이 모든 신원을 바꾸고, 그 `wipe` 가 SCIM 배포의 문서화된 drift 복구 수단이다.

---

## S-n. SCIM 배포에는 조직 계층이 사실상 생기지 않는다 (IAM H6)

**코드 사실: 맞음.** `child` 튜플은 GROUP 타입 멤버에서만 나온다(`core/.../tuple/TupleMapper.java:90`, `:98-114`). 멤버 타입은 본문 `type` 이나 상태 판정으로 정해지므로(`ScimMapper.java:111-125`), IdP 가 조직 id 를 다른 조직의 멤버로 보내야만 계층이 생긴다. enterprise `department`·`organization`·`division`·`costCenter`·`manager` 는 RFC 표에 있어 받아서 버린다(`connector-scim/.../ScimRfcAttributes.java:47-49`, `ScimPatchApplier.java:326-333`).

**문서상 지위: 처음 드러남(제품 범위).** 관련 사실만 있다.
- `specs/2026-09-28-full-audit.md:647` — M1(순환 연결 영구 누락)의 심각도를 "중첩 조직(`type:"Group"` 멤버)을 보내는 IdP 만. **조사한 카탈로그에서 중첩을 보낸다고 확인된 IdP 는 없다** — 그래서 치명이 아니라 중이다"로 낮췄다. 같은 사실에서 "그러면 SCIM 배포의 계층은 어디서 오나"로 이어진 논의는 없다.
- `specs/2026-09-26-group-member-patch-design.md:73-74`(Google Workspace "그룹을 보내지 않음", PingOne "그룹 미지원").
- `specs/2026-09-09-idp-conformance-audit.md` 에는 계층·중첩 논의가 없다.
- enterprise 확장 미파싱은 원 설계의 결정이다(`specs/2026-08-14-organization-sync-design.md:196`, `:717`).

**원칙과의 관계:** "배포 하나엔 커넥터 하나" — SCIM 배포는 계층을 LDAP 에서 빌릴 수 없어, 계층의 원천이 SCIM IdP 하나로 고정된다. "표준대로" — RFC 7643 Group 의 `members` 에 Group 을 넣는 것은 표준 신호라 받을 수 있지만, `department` 같은 문자열에서 계층을 만드는 것은 표준 밖 해석이다.

**심각도: High(제품 범위)** (리뷰어와 같음). 보안 결함이 아니라 거부 쪽이지만, README 의 핵심 약속(롤업, README:73-77)이 SCIM 배포에서 비게 될 수 있음을 프로젝트 자신의 IdP 조사(full-audit:647)가 시사한다.

---

## S-o. 실패한 `POST /Groups` 의 고아 튜플 (authz L1)

**코드 사실: 맞음.** 새 조직은 실패가 하나라도 있으면 레코드를 만들지 않고 이미 쓴 튜플은 남는다(`IncrementalSyncUseCase.java:355-358`). 재시도 POST 는 새 UUID 다(`ScimGroupHandler.java:42`; 핸들러 주석 `:100-105` 가 같은 내용을 적는다). 그 id 는 상태에 없어 아카이빙 후보에도 들지 않는다(`SnapshotArchiveUseCase.java:42-44`, `:112`).

**문서상 지위: 이미 결정·수용.** `specs/2026-10-04-immutable-identifiers-design.md:214-217`(§11 "첫 번째 id 로 이미 써 둔 튜플은 고아가 되어 `mode=tuples` 재적재만 지운다. 첫 번째 id 는 어떤 상태·응답에도 없으므로 권한이 새는 것은 아니다. Entra 가 조직을 빈 채로 만들어 드문 경우다"). 레코드를 만들지 않는 까닭은 `IncrementalSyncUseCase.java:328-332`, `:875-877` 자바독.

**원칙과의 관계:** "Read 는 재적재에서만" — 이 고아를 지우는 것이 재적재뿐인 것은 그 원칙의 결과다. 리뷰어 처방(같은 요청 안의 보상 삭제)은 Read 가 필요 없어 원칙과 부딪히지 않는다.

**심각도: Low** (리뷰어와 같음).

---

## S-p. 감사 — 행위자 없음, 30일, 최선 노력, 같은 테이블, 재적재·장부 맞추기의 부여 미기록 (SA M-3, IAM H7)

**코드 사실: 맞음.** 원인은 source·kind·id(runId·traceId)뿐이고 행위자 칸이 없다(`core/.../audit/AuditCause.java:12`). 보관 30일(app-ldap yml `:83`, app-scim `:34`, `DynamoDbProperties.java:23`). 이력 쓰기 실패는 결론·응답을 바꾸지 않는다(`core/.../SyncJobs.java:279-290`, `IncrementalSyncUseCase.java:800-805`). 앱 테이블에 쓴다(`storage-dynamodb/.../DynamoDbAuditLog.java:99`). 재적재·장부 맞추기가 쓴 줄은 남기지 않는다(`core/.../LedgerAlignment.java:31-32`, `ScimRebuildUseCase.java:67`).

**문서상 지위: 이미 결정·수용(행위자·변경 불가 저장소는 논의 없음).** `specs/2026-10-10-audit-log-design.md:26-33`(§2 사용자 확인 — "동기화 이력과 같은 30일, 설정값", 버린 안 "감사만 더 길게(1년), 지우지 않음"), `:214-222`(§11 이유 — 원인 회차 기록이 30일 뒤 사라져 거슬러 갈 곳이 없어지고, 지우지 않으면 개인정보가 끝없이 남는다; DynamoDB Streams 도 버림), `:75-79`(§3.5 재적재가 쓴 튜플은 중복 무시라 가를 수 없어 남기지 않음), `:125-135`(§4.5 빠질 수 있어도 틀린 줄은 남기지 않음), `:185-212`(§10). 행위자: §10(`:205`)은 "관리 API 는 아직 인증이 없다"를 노출 관점으로만 적었다. 변경 불가 저장소(S3 Object Lock·SIEM 내보내기)는 논의가 없다.

**원칙과의 관계:** "인증은 마지막" — 행위자는 인증이 있어야 생긴다. 30일은 사용자 결정이다.

**심각도: Medium** (IAM High 보다 낮고 SA Medium 과 같음). 회수 시각 증명·특권 작업 추적은 감사의 기본 질문이지만, 대부분 의도된 결정이고 행위자는 인증 슬라이드의 일이다.

---

## 요약 — 확인된 새 문제(순위)

1. **S-a(High)**: IdP 재시도 전제는 알려진 결정이지만, Check 기준선 전환 뒤 `active` 되돌림의 근거가 낡았고(요청값을 저장해도 재시도는 수렴), 일부 실패 뒤에는 재적재·다음 쓰기가 회수된 줄을 다시 쓴다 — 문서에 없다.
2. **S-m(High)**: SCIM 의 문서화된 drift 복구(`wipe` + 재프로비저닝)와 테이블 사고 복구가 모든 직원·조직 id 를 바꾼다. PITR·삭제 보호·RPO/RTO 논의가 없고, "store 번호 고정"과 비대칭이다.
3. **S-n(High, 제품 범위)**: SCIM 배포의 계층 원천이 정의돼 있지 않다 — full-audit:647 이 "중첩을 보내는 IdP 확인 없음"을 적고도 롤업 공백으로 잇지 않았다.
4. **S-h(Medium)**: prod 프로필이 `create-table-on-startup` 을 끄지 않아 테이블명 오타가 SCIM 신원을 가른다. app-ldap 헬스 상세는 app-scim 과 같은 이유가 있는데도 `always` 다.
5. **나머지 새 조각(Low~Medium)**: S-l(LDAP `userName` 비유일 + 0건·2건 계약 없음), S-f(여러 요청 누적 회수 상한 — 원칙과 긴장), S-g(DynamoDB `apiCallTimeout` 부재), S-i(필터 입력 전체 되돌림). S-b·S-c·S-d·S-e·S-j·S-k·S-o·S-p 의 핵심은 이미 결정·수용이다.
