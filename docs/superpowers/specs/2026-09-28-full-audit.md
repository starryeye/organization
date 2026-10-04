# 배포 전 전체 점검 (7-2) — 결함·성능·IdP 요청 대조

## 0. 머리말

**작성일:** 2026-09-28
**기준 코드:** 브랜치 `full-audit`, 커밋 `5efa74d`(main 과 같다)
**목적(사용자와 합의):** 배포 전에 결함을 찾는다. 성능은 매우 중요하게 본다. 대상은 모든 엔드포인트와 비즈니스 로직이다.
IdP 는 아직 정하지 않았으므로 모든 IdP 를 같은 비중으로 본다.

**방법 — 네 단계로 했다.**

1. **IdP 공식 문서 조사.** Entra·Okta, Google·AWS·Auth0·Keycloak, Ping·OneLogin·JumpCloud 와 AD·OpenLDAP 이 실제로 무엇을 보내고,
   우리 응답에 어떻게 반응하는지 문서로 모았다.
2. **코드 리뷰 네 영역 + 비용표 둘.** SCIM 쓰기 / SCIM 요청 해석·조회 / LDAP 동기화 / 재적재·관리·저장소 영역을 코드 전부 읽고 리뷰했다.
   따로 SCIM 엔드포인트 비용표와 배치·관리 비용표를 만들었다(호출 수를 코드로 셌다).
3. **오픈소스 대조.** 의심 지점에서만 SCIMple·UnboundID SCIM2·Goldfish SCIM-SDK·Charon·Keycloak·authentik·ConnId 와 비교했다.
4. **재현·측정.** 의심이 큰 것은 테스트로 재현하고, 규모 비용은 DynamoDB Local 에서 쟀다.

**재현 코드는 main 에 들어가지 않는다.** 재현 테스트는 버릴 브랜치 `spike-full-audit` 에만 있다(푸시하지 않았다). main 에는 이 문서만 들어간다.

| 재현·측정 | `spike-full-audit` 커밋 | 발견 |
|---|---|---|
| 최신 스냅샷 만료 → 빈 기준선 | `a90ab10` | C1 |
| 재적재 뒤 옛 storeId | `fa51a2c` | C2 |
| 요청이 끊기면 재적재가 멈춤 | `9b83825` | C3 |
| 작은따옴표 아이디 제거 | `9bcc4fa` | C4 |
| 큰 본문 500 / 속성 이름 대소문자 | `4fb7bf6` | M3, S6 |
| 순환이 풀려도 엣지를 안 씀 | `39a890e` | M1 |
| `type` 없는 1,000명 add · 10만 명 조직 DELETE 측정 | `5462671` | P1, C6 |

**하지 못한 것.** 실제 IdP·AD 에 붙여 보지 못했다. 인증이 없어서 붙일 수 없다(인증은 마지막 슬라이드). 한계 전체는 §8 에 있다.

### 읽는 법

**심각도**
- **치명** — 권한이 잘못 남거나 빠진다, 데이터가 사라진다, IdP 연동이 막힌다, 또는 10만 명에서 타임아웃·OOM·락 기아가 난다.
- **중** — 틀리지만 우회하거나 복구할 수 있다. 또는 눈에 띄게 느리다.
- **사소** — 관측성·메시지·드문 경우.

**증거**
- **재현됨** — 테스트로 틀린 결과를 확인했다.
- **측정** — 규모 테스트로 호출 수와 시간을 쟀다(DynamoDB Local).
- **코드로 확인 / 코드로 셈** — 코드를 따라가 그 경로가 있음을 확인했다, 또는 호출 수를 셌다.
- **문서로 확인** — 공식 문서에 적혀 있다. 커뮤니티·블로그면 그렇다고 적었다.
- **추정** — 외부 시스템 동작이나 시간·메모리를 가정해서 낸 값이다.

**자주 나오는 말**

| 말 | 뜻 |
|---|---|
| 튜플 | OpenFGA 에 저장하는 권한 한 줄. 예: `user:kim direct_member group:FIN` = "kim 은 FIN 조직의 직속 멤버" |
| store | OpenFGA 안에서 튜플을 담는 저장 공간 하나. `storeId` 로 가리킨다 |
| 기준선 | 변경을 계산할 때 "지금 OpenFGA 에 이미 있다"고 보는 튜플 목록. LDAP 은 지난 스냅샷, SCIM 은 Check 결과를 쓴다 |
| 튜플 스냅샷 | LDAP 회차가 OpenFGA 에 반영한 튜플 목록을 DynamoDB 에 적어 둔 것. 포인터가 최신 것을 가리킨다 |
| Check / BatchCheck | OpenFGA 에 "이 튜플이 있나"를 묻는 호출. BatchCheck 는 50개씩 묻는다 |
| 전역 락 / 리스 | SCIM 쓰기와 재적재를 한 줄로 세우는 DynamoDB 아이템(`LOCK#SCIM_WRITE`). 리스는 락을 쥘 수 있는 시간(TTL 30초) |
| 재적재 | store 를 비우고 DynamoDB 상태(app-scim) 또는 LDAP(app-ldap)에서 튜플을 전부 다시 쓰는 관리 작업 |
| 아카이빙 | app-scim 이 매일 03:00 "지금 OpenFGA 에 실제로 있는 것"을 관찰해 스냅샷으로 남기는 작업 |
| 삭제 가드 | LDAP 회차의 삭제가 기준선의 30% 를 넘으면 멈추는 안전장치. 기준선이 10건 미만이면 그냥 통과한다 |
| 실행 가드 | app-ldap 한 인스턴스 안에서 동기화가 겹치지 않게 막는 `AtomicBoolean` |
| 격리(quarantine) | Entra 가 실패가 많은 연동을 하루 1회로 늦추고, 4주가 지나면 멈추는 상태 |
| GetItem / Query / BatchGet / BatchWrite | DynamoDB 호출. 아이템 하나 읽기 / 파티션 읽기(1MB 씩) / 100개 묶어 읽기 / 25개 묶어 쓰기 |

---

## 1. 요약표

심각도 순이다. 사소 28건은 한 줄로 묶었다(§4.4).

| ID | 종류 | 심각도 | 증거 | 한 줄 설명 |
|---|---|---|---|---|
| C1 | 결함 | 치명 | 재현됨 | LDAP 기준선이 7일 넘게 안 바뀌면 정리 잡이 지운다. 다음 회차는 빈 기준선으로 돌아 삭제를 하나도 안 한다 — 예: 개편으로 가드가 8일 멈춘 뒤 퇴사자 권한이 영구히 남는다 **→ 해결(2026-09-29, 슬라이드 ①)** |
| C2 | 결함 | 치명 | 재현됨 | A 가 재적재로 store 를 새로 만들면 B 는 지워진 옛 store 에 계속 쓰고 "성공"으로 답한다 — 예: B 로 간 퇴사자 비활성화가 실제 store 에 반영되지 않는다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| C3 | 결함 | 치명 | 재현됨(유스케이스 수준) | 관리 API 재적재가 HTTP 요청에 묶여 있다. 프록시 60초 타임아웃에 끊기면 store 를 비운 채 멈추고, 실행 기록은 영원히 RUNNING 이다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| C4 | 결함 | 치명 | 재현됨 | `o'brien@corp.com` 처럼 작은따옴표가 든 직원은 `members[value eq "…"]` 로 조직에서 뺄 수 없다(400). 조직 권한이 남는다 **→ 해결(2026-09-29, 슬라이드 ①)** |
| C5 | 결함 | 치명 | 코드로 확인 | LDAP 연결·읽기 타임아웃이 없다. 응답 없는 연결 하나에 물리면 회차가 끝나지 않고 이후 매일 동기화가 건너뛰어진다. 헬스는 UP 이다 **→ 해결(2026-09-29, 슬라이드 ①)** |
| C6 | 성능 | 치명 | 측정 | 10만 명 조직 DELETE 가 락을 58.9초 쥔다(TTL 30초). 그동안 다른 SCIM 쓰기는 503 이고, 경쟁이 있으면 영원히 못 끝날 수 있다 **→ 해결(2026-10-02, 슬라이드 ③-1)** |
| C7 | 결함(조건부) | 치명 | 코드로 셈 | OpenFGA 가 느리거나 죽으면 SCIM 재적재가 기한 없이 재시도하며 락을 수십 분~수십 시간 쥔다. 그동안 모든 SCIM 쓰기가 503 이고 인가 공백이다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| C8 | 결함(조건부) | 치명 | 코드+문서로 확인 | OpenFGA Check 캐시를 켜면, 넣고 10초 안에 뺀 멤버의 튜플이 안 지워진다. 고치기는 한 줄이다 **→ 해결(2026-09-29, 슬라이드 ①)** |
| M1 | 결함 | 중 | 재현됨 | 조직 위아래를 바꾸는 개편에서, 순환이라 버린 엣지를 순환이 풀린 뒤에도 다시 쓰지 않는다 — 롤업 권한이 영구히 빠진다. 중첩 조직을 보내는 IdP 만 **→ 해결(2026-10-03, 슬라이드 ③-2)** |
| M2 | 결함 | 중 | 코드로 확인 | LDAP 회차가 OpenFGA 에 쓴 뒤 스냅샷 저장 전에 끊기고, 그 변경이 다음 회차 전에 되돌려지면 권한이 영구히 남거나 빠진다 **→ 해결(2026-09-30, 슬라이드 ②-2)** |
| M3 | 결함·표준 | 중 | 재현됨 | 256KB 넘는 요청 본문이 413 이 아니라 500 이다(511KB PATCH 로 확인) |
| M4 | 결함 | 중 | 코드로 확인 | 재시도하면 낫는 실패(부분 실패, OpenFGA·DynamoDB 장애)를 500 으로 준다. 코드 주석은 "IdP 는 500 을 영구 실패로 본다"고 전제한다 |
| M5 | 결함 | 중 | 알려진 것 — 새 근거 | Entra 매핑에 남은 전화번호·주소 path 연산이 섞이면 PATCH 전체가 400 이라, 같은 요청의 비활성화까지 막힌다 |
| M6 | 결함 | 중 | 코드로 확인 | 시작할 때 OpenFGA 가 안 닿았고 store 가 없으면, 재시작 전까지 조직 멤버 추가가 전부 500 이다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| M7 | 결함 | 중 | 알려진 것 — 새 근거 | 지운 직원의 아이디를 새 입사자가 다시 쓰면, 남아 있던 고아 튜플의 권한을 물려받는다 **→ 해결(2026-10-04, 슬라이드 ④-1)** |
| M8 | 결함 | 중 | 코드로 확인 | 30초 넘게 멈춘 요청이 리스를 다시 확인하지 않고 커밋해, 다른 인스턴스가 저장한 비활성화를 덮어쓸 수 있다 **→ 해결(2026-10-02, 슬라이드 ③-1)** |
| M9 | 결함 | 중 | 알려진 것 — 새 근거 | LDAP 아이디가 이름(`uid`/`cn`/`ou`)에서 온다. 개명이 삭제+생성이고, `objectGUID`·`entryUUID` 는 설정으로도 못 쓴다 **→ 해결(2026-10-04, 슬라이드 ④-1)** |
| M10 | 결함 | 중 | 코드+문서로 확인 | AD 에서 어떤 그룹을 사용자의 기본 그룹으로 바꾸면 그 그룹의 `member` 에서 빠진다. 우리는 그 조직 권한을 지운다 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| M11 | 결함 | 중 | 코드로 확인 | 아이디 속성이 없는 LDAP 엔트리 하나(예: 사번 없는 서비스 계정)가 10만 명 회차 전체를 실패시킨다 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| M12 | 결함 | 중 | 코드로 확인 | AD 에서 사용자 클래스를 `user` 로 두면 컴퓨터 계정까지 직원으로 읽는다 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| M13 | 결함 | 중 | 코드로 확인 | 재적재가 읽기 전에 store 를 비운다. 읽기가 실패하면 전사 권한이 0 이 된다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| M14 | 결함 | 중 | 코드로 확인 | app-ldap 여러 대를 막는 클러스터 락이 없다. 두 대면 03:00 cron 이 매일 정확히 겹친다 **→ 해결(2026-09-30, 슬라이드 ②-2)** |
| M15 | 결함·성능 | 중 | 코드로 확인 | 아카이빙이 인스턴스마다 락 없이 돈다. 비용이 대수만큼 들고, 재적재와 겹치면 거의 빈 "실제" 스냅샷이 남는다 **→ 해결(2026-09-30, 슬라이드 ②-2)** |
| M16 | 결함 | 중 | 코드로 확인 | 너무 긴 id 같은 잘못된 튜플 하나가 같은 배치 99개를 함께 실패시킨다. 다시 돌려도 같은 99명이 빠진다 **→ 해결(2026-09-30, 슬라이드 ②-2)** |
| M17 | 결함 | 중 | 코드로 확인 | 첫 배포에 두 인스턴스가 동시에 뜨면 같은 이름의 store 가 둘 생길 수 있다 **→ 해결(2026-09-29, 슬라이드 ②-1)** |
| M18 | 결함 | 중 | 추정 | Entra 기본 매핑의 `manager` 를 안 지우면, 10만 명 초기 동기화에서 참조 실패만으로 격리 상한(6만 건)에 닿을 수 있다 |
| P1 | 성능 | 중 | 측정 | `type` 없는 멤버(Entra·Okta 는 안 보낸다)는 한 명씩 차례로 읽는다. 1,000명 추가: 1.76초, GetItem 4,002 **→ 해결(2026-10-03, 슬라이드 ③-2)** |
| P2 | 성능 | 중 | 코드로 셈 | 하위 조직을 붙일 때 순환 검사가 자손 조직마다 Query 를 차례로 한다. 약 6천 개면 TTL 초과, 1만 개 넘으면 매번 500 **→ 해결(2026-10-03, 슬라이드 ③-2)** |
| P3 | 성능 | 중 | 추정 | 전역 락 재시도 간격이 200ms 고정이다. 전체 쓰기 상한이 초당 약 10~28건, 동시 40개면 약 18% 가 503 |
| P4 | 성능 | 중 | 코드로 셈 | 관리 API 조직 상세·멤버 목록이 페이지마다 조직 전체(10만 줄)를 읽는다. 끝까지 넘기면 1억 줄 |
| P5 | 성능 | 중 | 코드로 셈 | 멤버를 돌려주는 조직 조회(Okta 의 기본 GET)가 10만 명 조직을 통째로 메모리에 싣는다(요청당 수십~100MB 이상, 추정) |
| P6 | 성능 | 중 | 코드로 셈 | OpenFGA 쓰기 결과 합치기가 배치 수의 제곱이다. 튜플 11만이면 해시 삽입 약 1.2억 번 |
| P7 | 성능 | 중 | 추정 | LDAP 검색이 모든 속성을 받고, DIT 전략은 10만 엔트리를 끝까지 쥔다. AD 에서 1~2GB, 힙이 1GB 이하면 OOM **→ 해결(2026-10-04, 슬라이드 ④-1)** |
| P8 | 성능 | 중 | 코드로 셈 | SCIM 재적재가 스냅샷 저장(BatchWrite 약 4,400회)까지 락 안에서 한다. 그만큼 503 이 길어진다 **→ 해결(2026-10-02, 슬라이드 ③-1)** |
| S1–S28 | 표준·결함·관측성 | 사소 | 각각 | `Location` 헤더 없음, `Retry-After` 없음, 속성 이름 대소문자(재현됨) 등 — §4.4 표 |

---

## 2. 요청 카탈로그

### 2.1 누가 실제로 우리에게 요청을 보내나

조사한 SCIM 쪽 9곳과 LDAP 서버 둘이다. **Google·AWS·Auth0·Keycloak(코어)은 우리에게 요청을 보내지 않는다.** AWS·Auth0 는 참조 구현으로만 읽었다.

| 제품 | 우리에게 보내나 | 역할 | 근거 |
|---|---|---|---|
| Microsoft Entra ID | 예 | SCIM 클라이언트. 커스텀 앱에 아무 URL 이나 지정 | 문서로 확인 |
| Okta | 예 | SCIM 클라이언트(OIN 앱·AIW 커스텀 앱) | 문서로 확인 |
| PingOne | 예 | 아웃바운드 SCIM. 변경마다 즉시 보낸다 | 문서로 확인 |
| PingFederate | 예 | SCIM Provisioner. 직원은 PUT 만, 조직은 PUT 이 기본 | 문서로 확인 |
| OneLogin | 예 | 커스텀 SCIM 앱으로 프로비저닝 | 문서로 확인 |
| JumpCloud | 예 | 커스텀 SCIM 통합 | 문서로 확인 |
| Keycloak | 코어는 아니오, 비공식 확장만 예 | 코어는 SCIM **서버**(26.7 프리뷰). `keycloak-scim-outbound` 같은 커뮤니티 확장이 보낸다 | 문서 + 커뮤니티 |
| Google Workspace | 아니오 | 사전 등재 카탈로그 앱에만 보낸다. 그룹은 SCIM 으로 안 보낸다 | 문서 + 커뮤니티(스레드 제목만) |
| AWS IAM Identity Center | 아니오 | SCIM 서버만. 외부 IdP 가 여기로 보낸다 | 문서로 확인 |
| Auth0 | 아니오 | SCIM 서버만(엔터프라이즈 커넥션 전용) | 문서로 확인 |
| Active Directory | 우리가 읽는다 | LDAP 서버. app-ldap 이 클라이언트 | — |
| OpenLDAP | 우리가 읽는다 | LDAP 서버 | — |

### 2.2 요청 모양별 판정

✅ 맞게 처리 · ⚠️ 표준대로거나 동작은 하지만 주의(결정·한계·성능) · ❌ 틀리거나 막힘. 리뷰 두 곳(쓰기, 해석·조회)의 판정표를 합쳤다.

**Entra ID**

| 요청 | 결과 | 관련 |
|---|---|---|
| 연결 테스트 `GET /Users?filter=userName eq "<guid>"` | ✅ 200 + 빈 ListResponse. 404 가 아니라 격리 사유가 안 된다 | — |
| 연결 테스트 `GET /Groups?excludedAttributes=members&filter=displayName eq "…"` | ✅ 조직 META 만 읽는다 | — |
| 매칭 `filter=externalId eq "x"` | ✅ | — |
| 옛 예시 `filter=externalId eq jyoung`(따옴표 없음) | ⚠️ 400 `invalidFilter`. RFC 대로다. 실제 트래픽은 따옴표가 있을 것(추정) | — |
| 매칭을 이메일로: `emails[type eq "work"].value eq "x"` | ❌ 400 — 알려진 제한 | S12 |
| 참조 확인 `filter=id eq "a" and manager eq "b"` | ❌ 400 — 알려진 제한 | S12, M18 |
| `POST /Users`(enterprise URN, `null` 속성들, `roles`, `meta`) | ✅ 201 + `id`. 모르는 속성은 버린다 · ⚠️ `Location` 헤더 없음 | S1, S6 |
| 같은 POST 두 번 | ✅ 409 `uniqueness`(락 안에서 판단) | — |
| PATCH `Replace` 여러 path(`emails[type eq "work"].value`, `name.familyName`) | ✅ 200. 이메일이 없으면 400 `noTarget`(RFC 대로) | — |
| PATCH `Replace userName`(개명) | ✅ 아이디·튜플은 그대로, 겹치면 409 | — |
| PATCH `Replace active "False"`(기본 모드) / `replace active false`(옵션 모드) | ✅ 비활성, 모든 소속의 튜플 삭제, 멤버십은 유지 | S8 |
| PATCH `active=true`(복원) | ✅ 유지해 둔 소속 전부 튜플 복원 | — |
| PATCH `Add nickName` | ⚠️ 400 `invalidPath` — 저장 안 하는 속성, README 가 매핑 제거를 안내 | M5 |
| path 없는 값 객체(점 표기 키 + enterprise `employeeNumber`) | ✅ 200, `employeeNumber` 는 조용히 버림(문서화된 정책) | — |
| 기본 모드 path `urn:…:enterprise:2.0:User:employeeNumber` | ❌ 400 → 같은 요청 전체 실패 | M5 |
| 매핑에 남은 `phoneNumbers[…]`·`addresses[…]`·`emails[type eq "other"]` path 연산 + `active=false` | ❌ 400 → 비활성화도 안 된다 | **M5** |
| PATCH `Add manager` | ⚠️ 400 `invalidPath` — 10만 명이면 격리 위험 | M18 |
| `DELETE /Users/{id}` | ✅ 204, 조직 크기와 무관 · ⚠️ 부분 실패면 500 | M4 |
| `POST /Groups`(빈 멤버, 표준 밖 스키마 URN, `externalId`) | ✅ 201, 조직코드 = `externalId` | — |
| PATCH `Add members [{"$ref":null,"value":"…"}]` | ✅ 204 · ⚠️ `type` 이 없어 멤버마다 차례로 읽는다 | **P1** |
| PATCH `Remove members` + `value`(기본 모드) | ❌ 400 `invalidValue` — 알려진 결정(`?aadOptscim062020` 필수) | §5 |
| PATCH `remove members[value eq "…"]`(옵션 모드) | ✅ 204 / ❌ 아이디에 `'`·`"` 가 있으면 400 | **C4** |
| PATCH `Replace displayName` | ✅ 204(Entra 권고와 같다) | — |
| `GET /Groups/{id}?excludedAttributes=members` | ✅ META 1건 | — |
| `DELETE /Groups/{id}` | ✅ 204 · ⚠️ 큰 조직은 락 TTL 초과 | **C6** |
| `GET /Schemas`, `/ResourceTypes` | ⚠️ 404 — 부재는 알려진 것, 본문이 SCIM Error 가 아님 | S7 |
| 503·500 에 대한 반응 | ⚠️ 문서 없음. 우리 500/503 의 뜻이 섞여 있다 | M4, S2 |

**Okta**

| 요청 | 결과 | 관련 |
|---|---|---|
| `GET /Users?filter=userName eq "x"&startIndex=1&count=100` | ✅ | — |
| 가져오기 `GET /Users?startIndex=N&count=100` | ✅ 책갈피로 이어 읽기. 첫 페이지는 전원을 센다(알려진 것) | — |
| `POST /Users`(`password` 자리표시, `groups`, `locale`) | ✅ 무시 | — |
| 비활성화 `{"op":"replace","value":{"active":false}}` | ✅ | — |
| 직원 `PUT`(`id`·`groups`·`meta` 포함) | ✅ 경로 아이디가 정본. `active` 가 빠지면 활성(문서화된 규칙) | S6 |
| `POST /Groups {"displayName","members":[]}`(externalId 없음) | ⚠️ 201, 조직코드 = 새 UUID. 응답을 잃은 재시도면 조직이 둘 | S5 |
| 한 PATCH 에 `remove members[value eq]` + `add members` | ✅ 순서대로, 원자적 / ❌ 아이디에 `'` 가 있으면 둘 다 실패 | C4 |
| `replace members [...]` | ✅ 바뀐 멤버만 처리 | — |
| 조직 `PUT`(멤버 `type` 없음) | ✅ 약 7천 명까지 / ❌ 넘으면 500 | M3, P1 |
| `GET /Groups/{id}`(파라미터 없음) → 멤버 전체 | ✅ · ⚠️ 조직 파티션을 통째로 메모리에 | **P5** |
| `GET /Groups?filter=displayName eq "x"&startIndex=1&count=100` | ✅ 멤버 포함 · ⚠️ 같은 비용 | P5 |
| 조직 PATCH 1,000명/요청(커뮤니티 수치) | ✅ 256KB 안 · ⚠️ 판정 GetItem 2,000번 차례 | P1 |
| PATCH `replace displayName` / `DELETE /Groups/{id}` | ✅ 204 / 204 | — |
| POST 409 → 작업을 멈춤 | ✅ RFC 대로 · ⚠️ 이름 바꾼 사람의 옛 `userName` 재사용은 늘 409(알려진 것) | — |
| "빈 응답 본문 = 무효" | ✅ 204 는 PATCH·DELETE 에만, 오류는 항상 SCIM Error 본문 | — |
| 503 에 대한 반응 | ⚠️ 문서로 확인 못 함 | M4, S2, §5 |

**Ping · OneLogin · JumpCloud · Keycloak 확장**

| IdP | 요청 | 결과 | 관련 |
|---|---|---|---|
| Ping | 매칭 `userName Eq "%s"` | ✅ 연산자 대소문자 무시 | — |
| Ping | 관리자 필터 `email Co "%s"` | ⚠️ 400 — 설계상 `eq`/`and` 만 | S12 |
| Ping | `count=1000` | ✅ 100 으로 줄여 준다(RFC 허용) | — |
| Ping | `count=-1`("서버 최대") | ⚠️ 0 으로 보고 `totalResults` 만 준다(RFC 대로). Ping 이 실제로 보내는지는 추정 | — |
| PingFederate | 직원 갱신은 항상 PUT, 비활성화는 PUT `active=false` 또는 DELETE | ✅ | — |
| PingFederate | 조직 갱신 기본 PUT(전체 멤버) | ✅ / ❌ 7천 명 넘으면 500 | M3, P1 |
| PingFederate | 조직 PATCH 모드의 remove 모양 | ⚠️ 모양 미확인. 값 붙은 remove 면 400 | §5 |
| PingOne | 변경마다 실시간 | ✅ 모델과 맞다 · ⚠️ 폭주하면 전역 락 | P3 |
| OneLogin | 없는 사용자 `GET /Users/{id}` → 404 기대 | ✅ 404 SCIM Error | — |
| OneLogin | 생성·수정·비활성화(`active`) | ✅(정확한 PATCH 모양은 미확인) | — |
| JumpCloud | 연결 테스트 `GET /Users` | ✅ 첫 페이지가 전원을 센다(알려진 것) | — |
| JumpCloud | 재연결 때 이메일로 조회(모양 미확인) | ❌ 400(추정) | S12 |
| JumpCloud | 활성화: 생성 → GET → 갱신 → DELETE | ✅ · ⚠️ 갱신이 저장 안 하는 속성(`jobTitle` 등)을 path 로 보내면 400 → 활성화 실패(모양 미확인) | M5 |
| JumpCloud | 관리형 그룹 삭제 → `DELETE /Groups` | ✅ | — |
| Keycloak 확장 | 직원 POST, 속성별 PATCH, `active=false`·DELETE | ✅ | — |
| Keycloak 확장 | 조직 POST·PATCH(`members[value eq]`)·DELETE | ✅(C4 예외) | C4 |
| Keycloak 플러그인(mitodl) | 매번 `replace members [전체]`, 비울 때 `remove members` + `value:null` | ✅ | — |
| AWS·Auth0(참조, 클라이언트 아님) | 값 붙은 `remove members [{value}, …]` | ❌ 400 — 알려진 결정, 새 근거 | §5 |
| Auth0(참조) | 조직 PUT/POST 1만 명 | ❌ 500 | M3 |

**공통**

| 요청 | 결과 | 관련 |
|---|---|---|
| `Content-Type: application/scim+json` | ✅ | — |
| `Content-Type: text/plain` | ✅ 415 SCIM Error · ⚠️ `detail` 에 자바 클래스 이름 | S10 |
| 깨진 JSON | ✅ 400 `invalidSyntax` | — |
| 본문 256KB 초과 | ❌ 500 | **M3** |
| 없는 경로·틀린 메서드 | ❌ Spring Boot 기본 JSON(SCIM Error 아님), 405 대신 404 | S7 |
| `GET /scim/v2`, `POST /scim/v2/.search` | ✅ 501 | — |
| `GET /ServiceProviderConfig` | ✅ | — |

### 2.3 규모와 관련된 수치

| 수치 | 값 | 근거 |
|---|---|---|
| Entra 초기 동기화 시간 | 대상 1건당 0.01~0.08분. 사용자 10만만 넣어도 약 16.7시간~5.6일 | 문서로 확인(공식 공식), 계산은 이 감사 |
| Entra 증분 주기 | 약 40분 | 문서로 확인 |
| Entra 격리 | 실패 5,000건부터 평가. 비참조 실패 40% 초과 또는 40,000건 초과면 격리. 참조 실패 포함 60,000건 초과면 격리 | 문서로 확인 |
| Entra 격리 뒤 | 하루 1회로 줄고, 28일 넘으면 작업 중단. 재시도는 6시간·12시간·24시간 뒤, 이후 24시간마다 | 문서로 확인 |
| Entra 동시성 | 공식 수치 없음. Q&A 답변은 "피크 100+ req/s" | 커뮤니티(공식 아님) |
| Entra 조직 PATCH 한 번의 멤버 수 | 미확인 | — |
| Okta 조직 PATCH 한 번의 멤버 수 | 1,000명 | 커뮤니티(devforum), 공식 문서엔 없음 |
| Okta 빈도·동시성 | 공식 수치 없음 | — |
| AWS `PatchGroup` | 한 요청의 멤버 변경 최대 100건. 쓰기 25 TPS·읽기 40 TPS. 기본 할당 사용자 20만·그룹 10만 | 문서로 확인 |
| Auth0 조직 멤버 한도 | POST·PUT 1만 명, PATCH 1,000명 | 문서로 확인 |
| PingFederate 조회 페이지 | "Results Per Page" 기본 1,000, `-1` 이면 서버 최대 | 문서로 확인 |
| OneLogin API | 초당 4요청 | 제3자 문서(Talkdesk), 확인 필요 |
| 우리 한도 | 본문 256KB(멤버 약 7천 명), 목록 `count` 최대 100, 락 대기 3초(200ms × 15), 락 TTL 30초, 재적재 하트비트 10초 | 코드로 확인 |
| AD | `MaxPageSize` 1,000(우리 page-size 500), `MaxValRange` 1,500, `MaxQueryDuration` 120초, `MaxActiveQueries` 20, `MaxConnIdleTime` 900초 | 문서로 확인 |
| OpenFGA 서버 기본값 | 한 번 쓰기 100건, BatchCheck 50건, 요청 타임아웃 3초 | 문서로 확인 |
| OpenFGA Java SDK 0.9.11 | 요청·연결 타임아웃 10초, 429·5xx·네트워크 오류 3회 재시도 | 코드로 확인(바이트코드) |
| DynamoDB | 파티션 하나 초당 쓰기 1,000 WCU·읽기 3,000 RCU. TTL 은 만료 뒤 "며칠 안에" 지운다 | 문서로 확인 |
| AWS ALB 유휴 타임아웃 | 기본 60초 | 문서로 확인 |

---

## 3. 엔드포인트별 비용표

**변수.** N = 전체 직원(10만), M = 대상 조직의 멤버 수, K = 요청 본문의 멤버 수, G = 한 직원이 속한 조직 수, Δ = 실제로 바뀌는 멤버 수,
C = 페이지 크기(최대 100), D = 순환 검사가 훑는 자손 조직 수, T = 전체 튜플 수(약 11만으로 가정 — 추정, §8), O = 조직 수(약 6천, 추정).

**읽는 법.** "락 안의 일"은 락을 쥔 채 앞 호출이 끝나야 다음이 나가는 일이다. 락 보유 시간을 정한다.
**측정은 DynamoDB Local 값이다.** AWS 에서는 시간이 다르다. 호출 수만 믿을 수 있다. 시간 환산은 GetItem 한 번 약 5ms 등을 가정한 추정이다.

### 3.1 SCIM 직원

| 엔드포인트 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| `GET /Users/{id}` | GetItem 1 | 0 | 락 없음 | 작음 | O(1) | 코드로 셈 |
| `POST /Users`(조직 없음, 보통) | GetItem 3 + GSI1 Query 1 + Query 1 + PutItem 1 | 0 | 차례 왕복 약 7(≈35ms, 추정) | 작음 | O(1) | **측정**(5천 명): 6,476건 42.5초, 건당 6.6ms |
| `POST /Users`(조직이 먼저 참조, G개) | 위 + GetItem 2G | BatchCheck ⌈G/50⌉, Write ⌈G/100⌉ | 위 + G 에 비례 | O(G) | O(G) | 코드로 셈 |
| `PUT`·`PATCH /Users/{id}` | GetItem 3 + Query 1 + GetItem 2G(+ `userName` 이 바뀌면 GSI1 Query 1) | BatchCheck ⌈G/50⌉(active 가 그대로여도), active 가 바뀌면 Write | 차례 왕복 약 8~10(≈50~75ms, 추정) | O(G) | O(G) | 코드로 셈 |
| `DELETE /Users/{id}` | Query 2 + GetItem 2G+ + DeleteItem | BatchCheck, 튜플이 있으면 Write | 차례 왕복 약 12(≈95ms, 추정) | O(G) | O(G), 조직 크기와 무관 | **측정**: 10만 명 조직 소속 1명 38ms · Query 2 · 훑은 2 · GetItem 4 · Check 1 |

### 3.2 SCIM 조직 쓰기

| 엔드포인트 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| **공통: `type` 없는 멤버 판정** | 판정에 멤버마다 GetItem 2, 한 명씩 차례(측정에서는 락 안 처리까지 합쳐 멤버당 약 4) | 0 | 판정은 락 밖 | O(K) | O(K) 차례 | **새 측정**: 1,000명 추가 1,762ms · GetItem 4,002 · BatchGet 키 1,000 · Check 1,000. `type` 있는 1명은 236ms · GetItem 3 |
| `POST /Groups`(부모 없음, 보통) | GetItem 1 + Query 2 + GetItem K + [순환 검사 Query D] + PutItem 2K | BatchCheck ⌈K/50⌉, Write ⌈K/100⌉ | K × 약 2.2ms + D × 약 5ms(추정) | O(K) | O(K + D) | 코드로 셈 |
| `POST /Groups`(부모 P개가 먼저 참조) | 위 + 부모 파티션 통째 Query + GetItem 2 × 부모 멤버 수 | 같음 | 부모가 10만 명이면 GetItem 20만 ≈ 60초(추정) | O(부모 멤버) | O(부모 멤버) | 코드로 셈 · 알려진 것(`parentsOf`) |
| `PUT /Groups/{id}` | 멤버 키 Query(M건) + GetItem Δ + 쓰기 2Δ | BatchCheck ⌈Δ/50⌉, Write ⌈Δ/100⌉ | 키 Query 약 7쪽 + Δ 에 비례 | 멤버 키 O(M), 10만이면 10~20MB(추정) | O(M + Δ) | **측정**: 10만 명 조직 1명 교체 855~877ms · Query 7 · 훑은 100,000 · GetItem 4 · Check 2 |
| `PATCH` add `members` K명 | BatchGet ⌈K/100⌉ + GetItem 최대 2K + PutItem 2K | BatchCheck ⌈K/50⌉, Write ⌈Δ/100⌉ | K=1 ≈ 75ms. K ≈ 1,400명부터 3초 초과(추정) | O(K) | O(K), 조직 크기와 무관 | **측정**(`type` 있음): 10만 명 조직에 1명 207~236ms · GetItem 3 · BatchGet 키 1 · Check 1 |
| `PATCH` remove `members[value eq "x"]` K명 | BatchGet 2K 키 + GetItem + DeleteItem 2K | BatchCheck ⌈K/50⌉, Write | K=1 ≈ 60ms(추정) | O(K) | O(K) | **측정**: 1명 26~31ms · GetItem 3 · BatchGet 키 2 · Check 1 |
| `PATCH` 전원 빼기·`replace members` | 멤버 키 Query M건 + GetItem Δ + DeleteItem 2Δ | 10만 명 비우기: BatchCheck 2,000번·Write 1,000번 차례 | 분 단위(3~4분, 추정) | O(M) | O(M) | 코드로 셈 · 알려진 것 |
| `PATCH` displayName 만 | GetItem 2 + PutItem 1 | 0 | 차례 3 | O(1) | O(1) | 코드로 셈 |
| 하위 조직 붙이기(순환 검사) | 자손 조직마다 Query 1, 차례 | 0 | D × 약 5ms(추정). 1만 넘으면 500 | O(D) | O(D) | 코드로 셈 — P2 |
| `DELETE /Groups/{id}` | 파티션 Query 두 번 + GetItem M + DeleteItem 약 2M | BatchCheck ⌈M/50⌉, Write ⌈M/100⌉ | 10만 명 조직이면 락을 1분 가까이 | 파티션 아이템·직원·후보 집합 각 10만 | O(M + 부모 멤버) | **새 측정**: 10만 명 조직 58,923ms · Query 15 · 훑은 200,002 · GetItem 100,000 · Check 100,000 · 204. 픽스처라 쓰기는 실제보다 적다 |

### 3.3 SCIM 조회 — 락 없음

| 엔드포인트 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| `GET /Users`(필터 없음) 첫 페이지 | GSI1 COUNT(N건 훑음) + Query C + 책갈피 PutItem | 0 | 없음 | O(C) | O(N) | **측정**(10만): Okta 식 100명 × 1,000쪽 가져오기 12.7~14.4초 · Query 1,017 · 훑은 200,000 |
| `GET /Users` 다음 페이지(책갈피 15분 안) | GetItem 1 + Query C + PutItem 1 | 0 | 없음 | O(C) | O(C) | 위 측정에 포함 |
| 책갈피 없음·만료, `count=0` | COUNT N + 건너뛰기 Query N | 0 | 없음 | O(C) | O(N) | 코드로 셈 · 알려진 것 |
| `GET /Users?filter=` · `POST /Users/.search` | GetItem 1 또는 GSI Query 1(+ GetItem) | 0 | 없음 | O(결과) | O(1) | **측정**(10만): 무작위 100명 필터 0.49~0.55초 |
| `GET /Groups/{id}?excludedAttributes=members` | GetItem 1 | 0 | 없음 | O(1) | O(1) | 코드로 셈 |
| `GET /Groups/{id}` 멤버 포함(Okta 기본) | 파티션 통째 Query(10만이면 1MB 쪽 약 7~13번) | 0 | 없음 | 수십~100MB 이상(추정) | O(M) | 코드로 셈 — P5 |
| `GET /Groups` 목록·필터, 멤버 포함 | COUNT(조직 수) + 페이지의 조직마다 파티션 통째 | 0 | 없음 | 페이지 멤버 합 | O(조직 수 + 페이지 멤버) | 코드로 셈 |
| `GET /ServiceProviderConfig`, 루트(501) | 0 | 0 | 없음 | — | O(1) | 코드로 셈 |

### 3.4 app-scim 관리 API·스케줄러

| 작업 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| `POST /admin/sync/rebuild?mode=tuples` | 락 1 + 10초마다 갱신 · `loadAll`(GSI1 N건 + GetItem N + 조직 파티션 O개) · 스냅샷 BatchWrite 약 4,400 차례 | store 재생성 약 4호출 + Write 약 1,100 차례 | **전부 락 안.** 10만 명 약 2~6분(추정) | 수백 MB(추정) | O(N + T) | **측정**(6,124명): 3.1~3.6초 |
| `…?mode=wipe` | Query 약 10.6만, DeleteItem 약 33만 | store 재생성 | 전부 락 안, 약 2~3분(추정) | 약 60MB | O(N + 멤버십) | 코드로 셈 |
| `GET /admin/sync/runs` | Query 1~2 | 0 | 없음 | 작음 | O(1) | 코드로 셈 |
| 아카이빙(03:00) | `loadAll` + 스냅샷 BatchWrite 약 4,400 | BatchCheck 약 2,200 차례 | 락 없음. **인스턴스마다 돈다** | 약 150MB | O(N + T) | **측정**(5천 명): 2.5초. 10만 명 셈 2.5~4.5분 |
| 만료 스냅샷 정리(04:00, 두 앱) | 만료 스냅샷마다 Query + BatchWrite 약 4,400 | 0 | 없음, 인스턴스마다 | 흘려보냄 | O(T × 만료 개수) | 코드로 셈 |

### 3.5 admin-api 조회

| 엔드포인트 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| `GET /admin/employees?userName=` 등 검색 | GSI Query 1, `Limit` ≤ 100 | 0 | 없음 | 작음 | O(limit) | 코드로 셈 |
| `GET /admin/employees/{id}` | 흔한 경우 Query 약 7 + GetItem 약 15 | Check 경로 수만큼(≤ 200, 흔하면 약 7) | 없음 | 작음 | O(경로), 상한 200 | 코드로 셈 |
| `GET /admin/organizations/{code}` | 조직 파티션 통째(10만이면 8쪽·7.5MB·약 1,900 RCU) + 하위 조직 이름표 GetItem ≤ 200 차례 | Check 20 | 없음 | 약 65MB 순간 | 요청마다 O(M) | 코드로 셈 — P4 |
| `GET /admin/organizations/{code}/members` | 페이지마다 조직 파티션 통째 | Check limit | 없음 | 페이지마다 약 65MB | 끝까지 넘기면 O(M²/limit) | 코드로 셈 — P4 |

### 3.6 app-ldap

| 작업 | DynamoDB | OpenFGA | 락 안의 일 | 메모리 | 규모 | 측정 / 셈 |
|---|---|---|---|---|---|---|
| 전체 동기화(03:00·`/admin/sync/full`), 변경 D건 | 스냅샷 Query 약 11쪽 + 상태 비교(GSI1 약 35쪽 + 조직 Query 약 6천) + **변경이 1건이어도 스냅샷 BatchWrite 약 4,400 차례** | Write 약 2~6 | 전역 락 없음. 실행 가드를 끝까지 쥔다 | 약 170MB(DIT 는 +0.2~2GB) | 읽기 O(N + T + O), 쓰기 O(T) | 셈 3~7분(추정). 10만 명 측정 없음 |
| 전체 동기화, 변경 없음 | 위에서 스냅샷 쓰기가 빠진다 | 0 | 가드 | 약 150MB | O(N + T + O) | 셈 2~5분(추정) |
| 최초 적재·대개편 | + 직원 PutItem 10만 + 멤버십 PutItem 약 22만 | Write 약 1,100 차례 | 가드 | + 결과 합치기 누적 할당 약 3GB | O(N + T), 합치기 O(T²/100) | **측정**: 6,124명 7.3초. 상태 저장만 10만 명 첫 적재 26.6초·PutItem 102,100 |
| `/admin/sync/rebuild?mode=snapshot` | + 보관 스냅샷 전부 삭제(7개면 BatchWrite 약 3.1만) | 삭제 약 1,100 + 쓰기 약 1,100 | 인가 공백 4~9분(추정) | 약 200MB | O(보관 수 × T) | 측정 없음 |
| `…?mode=store` | 위와 같은 스냅샷 삭제 | store 재생성 + Write 약 1,100 | 공백 3~8분(추정) | 약 200MB | O(보관 수 × T) | 측정 없음 |
| `GET /admin/sync/runs` | Query 1~2 | 0 | 없음 | 작음 | O(1) | 코드로 셈 |

### 3.7 재시도와 타임아웃 — 락 보유 시간을 늘리는 곳

| 대상 | 재시도 | 타임아웃 | 근거 |
|---|---|---|---|
| 전역 락 획득 | 200ms 고정 간격, 최대 15번(3초) 뒤 503 | 없음 | `IncrementalSyncUseCase.java:115,553-556` |
| OpenFGA Write | SDK 3회 × 앱 `Retry.backoff(3)` → 배치 하나에 최대 16번 시도. 실패한 배치 뒤에도 다음 배치로 간다 | 요청 10초 | `OpenFgaRelationTupleWriter.java:90-95`, SDK 바이트코드 |
| OpenFGA BatchCheck | SDK 3회 | 10초 | `OpenFgaRelationTupleChecker.java:99-107` |
| DynamoDB | SDK 기본 8회(지연 25ms, 지터) | 호출 타임아웃 설정 없음 | SDK 바이트코드, `DynamoDbConfig.java:24-33` |
| BatchGet 미처리 키 | 50ms 쉬고 상한 없이 반복 | 없음 | 알려진 백로그 |
| 스냅샷 BatchWrite | 5회, 100ms × 2ⁿ | 없음 | `DynamoDbTupleSnapshotRepository.java:246-268` |
| LDAP | 예외에만 재시도(최대 4번 읽기) | **없음**(C5) | `LdapConfig.java:18-26` |
| SCIM 요청 전체 | 없음 | 없음 | — |

---

## 4. 발견 상세

각 발견은 이 순서로 적는다: 무슨 일이 생기나(구체적인 예) · 누가 겪나 · 위치 · 증거 · 다른 오픈소스는 · 고치는 방향과 크기.
경로는 모듈 이름부터 줄였다(예: `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java`).

### 4.1 치명

#### C1. 최신 튜플 스냅샷(기준선)도 7일 뒤 지워진다 → 다음 회차가 삭제를 하나도 안 한다

**결함 · 치명 · 재현됨** · 원: R-3, L-4, cost-batch D1

**무슨 일이 생기나.** LDAP 동기화는 "지난번 OpenFGA 에 반영한 튜플 목록"(기준선)과 이번에 읽은 디렉터리를 비교해 쓸 것과 지울 것을 정한다.
기준선은 스냅샷으로 DynamoDB 에 있고, 모든 스냅샷은 저장 뒤 7일에 만료된다. 새 스냅샷은 **변경이 있고 끝까지 간 회차만** 만든다.

예를 들어 보자.
1. 월요일 03:00 회차가 스냅샷 S0 를 남긴다.
2. 화요일부터 대규모 개편으로 삭제가 30% 를 넘어, 삭제 가드가 매일 회차를 멈춘다(ABORTED). 사람이 보고 `force` 를 결정하라는 설계다. 검토에 8일이 걸린다.
3. 멈춘 회차는 새 스냅샷을 만들지 않는다. 7일째 04:00, 정리 잡이 **포인터가 가리키는 S0 까지** 지운다. AWS 에서는 TTL 도 같은 일을 한다.
4. 8일째 03:00: 포인터는 있는데 S0 가 없다 → 기준선이 빈 목록이 된다 → 가드는 "기준선 10건 미만"이라 그냥 통과한다.
5. 결과: 쓸 것 = 목표 전부(이미 있어 해는 없다), **지울 것 = 없음.** 개편으로 빠져야 할 수만 명의 조직 권한이 하나도 안 지워진다.
6. 새 스냅샷은 목표 전부로 저장되므로, 이후 어떤 회차도 그 튜플을 다시 지우지 않는다. `rebuild?mode=snapshot` 도 못 지운다. `mode=store` 만 지운다. 실행 기록은 SUCCEEDED 다.

7일 넘게 튜플 변화가 없는 연휴, LDAP 8일 이상 장애, C5(동기화 정지), M11(매일 실패) 뒤에도 같은 길로 간다.
AWS TTL 은 아이템을 제각각 지우므로, 만료 뒤 며칠은 "메타는 있고 튜플 일부만 남은" 반쪽 기준선을 읽을 수도 있다(추정). 방향은 같다 — 덜 지운다.
**삭제 가드는 지나친 삭제만 막는다. 기준선이 줄어드는 쪽(덜 지움)은 아무것도 막지 않는다.**

**누가 겪나.** app-ldap 을 쓰는 모든 배포. 퇴사자·조직 이탈자의 권한이 영구히 남는다.

**위치**
- `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:68-74` — 최신을 포함한 모든 스냅샷에 같은 만료
- 같은 파일 `:107-113` — 포인터에는 만료가 없다("포인터는 있는데 스냅샷은 없다"가 생긴다)
- 같은 파일 `:116-139` — 메타가 없으면 빈 결과. "스냅샷 없음"과 구별하지 않는다
- 같은 파일 `:206-218` — `purgeExpired` 가 최신도 지운다
- `core/.../usecase/FullSyncUseCase.java:69-79`(변경 없음·ABORTED 는 저장 안 함), `:86-90`(빈 결과 → 빈 기준선)
- `core/.../guard/DeletionGuard.java:26-29` — 기준선이 작으면 통과
- `app-ldap/.../SyncScheduler.java:47-53` — 04:00 정리

**증거.** 재현 테스트 둘. 둘 다 "맞는 동작"을 단언하고, 지금 실패한다.
- `DynamoDbTupleSnapshotRepositoryTest.점검_재현_최신_스냅샷도_정리된다` — 8일 전 스냅샷 하나(최신)를 `purgeExpired` 하면 `findLatest` 가 빈다.
- `FullSyncUseCaseTest.점검_재현_기준선이_비면_삭제를_놓친다` — 기준선이 비면 지울 것이 `[]` 이고, 사라진 lee 의 튜플이 남는다.

**다른 오픈소스는.** 기준선 만료는 우리에게만 있는 결함이다. authentik 은 로컬 DB, Gluu 는 디스크 파일을 기준으로 삼아 "만료" 개념이 없다.
Keycloak 은 LDAP 에서 사라진 사용자를 아예 지우지 않는다(TODO 로 남아 있다). 가드가 있는 diff 삭제 자체는 우리가 앞서 있다.

**고치는 방향.** (1) 포인터가 가리키는 스냅샷은 만료·정리에서 뺀다 — 새 스냅샷을 저장할 때 이전 것에만 만료를 붙이거나, 매 회차(변경 없음·ABORTED 포함) 만료를 늘리거나, 정리 잡이 최신을 건너뛴다.
(2) 포인터가 있는데 스냅샷이 없으면 빈 기준선이 아니라 **오류로 회차를 멈춘다.** 크기 **작음~중간.**

---

#### C2. 재적재가 store 를 새로 만들면, 다른 인스턴스는 옛 storeId 로 계속 쓰고 "성공"이라 답한다

**결함 · 치명 · 재현됨** · 원: R-2, L-6(storeId 부분), cost-scim 지나가며 본 것

**무슨 일이 생기나.** README 가 권하는 대로 app-scim 을 A·B 두 대로 띄운다. 드리프트를 보고 A 에서 `rebuild?mode=tuples` 를 돌린다.
A 는 store X 를 지우고 같은 이름으로 Y 를 만들어 채운다. **B 는 여전히 X 를 가리킨다** — storeId 를 프로세스가 살아 있는 동안 한 번도 다시 확인하지 않는다.
재적재가 끝나 락이 풀리면 IdP 요청의 절반이 B 로 간다.

- 퇴사자 kim 의 `active=false` PATCH 가 B 로 가면: B 는 DynamoDB 에는 비활성으로 저장하고, 튜플 삭제는 **X 에** 한다. 실제로 쓰는 Y 에는 kim 의 권한이 남는다.
- 신규 입사자 POST 가 B 로 가면: 튜플이 X 에만 생긴다. 있어야 할 권한이 없다.
- B 의 관리 조회도 X 를 보므로 "어긋남 없음"으로 보이고, B 의 OpenFGA 헬스도 UP 이다. B 를 재시작할 때까지 아무 신호가 없다.

app-ldap 을 여러 대 띄워 한 대가 `rebuild?mode=store` 를 해도 같다. storeId 를 설정으로 고정한 **이 서버 밖의 소비자 앱**도 재적재 뒤 옛 store 를 본다 — README 는 이것을 말하지 않는다.

**누가 겪나.** app-scim 을 액티브-액티브로 띄운 배포, app-ldap 을 여러 대 띄운 배포, storeId 를 고정한 소비자 앱.

**위치**
- `authz-openfga/.../StoreBootstrapper.java:69-75, 90-96` — 캐시된 storeId 를 계속 쓴다
- 같은 파일 `:155-175` — `recreateStore` 는 자기 프로세스의 캐시만 바꾼다
- `authz-openfga/.../OpenFgaRelationTupleWriter.java:49`, `OpenFgaRelationTupleChecker.java:43, 66` — 캐시를 거친다
- `core/.../usecase/ScimRebuildUseCase.java:144-147`, `core/.../usecase/RebuildUseCase.java:70-73` — store 재생성
- `app-scim/.../OpenFgaHealthIndicator.java:42`

**증거.** 재현됨 — `AuditStoreRecreateReproTest`(OpenFGA v1.10.2, 메모리 엔진). B 가 옛 storeId 를 그대로 쥐고, 지워진 store 에 쓴 결과가
`hasFailure=false, written=[lee]` 인데 새 store 에는 lee 가 없다 — **조용히 틀린다.**
Postgres 엔진에서는 "조용히 틀림" 대신 "B 로 간 쓰기가 전부 실패"일 수 있다(확인 못 함). 어느 쪽이든 결함이다 — 실패면 IdP 가 재시도하다 격리될 수 있다.

**고치는 방향.** 두 방향이 있다.
(a) 원 리뷰안 — "지금 쓰는 storeId" 를 DynamoDB 한 줄에 두고(재적재가 락 안에서 갱신), SCIM 쓰기가 락을 잡은 직후 캐시와 비교해 다르면 다시 찾는다. 관리 조회·아카이빙도 같은 값을 본다. 크기 **중간.**
(b) 권고 슬라이드 2 — 재적재가 store 를 다시 만들지 않게 해서 캐시가 낡을 일 자체를 없앤다. 구체 방법은 설계 거리다(OpenFGA Read API 금지 전제와 함께 본다).
어느 쪽이든 README 에 "재적재 뒤 storeId 가 바뀐다"(또는 안 바뀐다)를 적는다.

---

#### C3. 관리 API 재적재가 HTTP 연결에 묶여 있다 — 끊기면 store 를 비운 채 멈춘다

**결함 · 치명 · 재현됨(유스케이스 수준)** · 원: R-1, L-3, cost-batch P4

**무슨 일이 생기나.** 운영자가 ALB 뒤에서 `POST /admin/sync/rebuild?mode=tuples` 를 부른다. 10만 명이면 재적재는 몇 분 걸린다(store 비우기 → 직원 10만 읽기 → 튜플 1,100 배치 쓰기 → 스냅샷 저장).
ALB 유휴 타임아웃 기본값은 60초다. 60초 동안 응답 바이트가 없으면 ALB 가 연결을 닫고, Reactor Netty 는 응답 구독을 **취소**한다. 그러면:

1. 재적재 체인이 멈춘다. 이미 비운 store 는 반쯤만 차 있다(예: 10만 명 중 3만 명만 권한).
2. 락이 **즉시 반납**되고 하트비트가 멈춘다. 다른 인스턴스의 SCIM 쓰기가 반쯤 찬 store 위로 바로 들어온다.
3. 실행 기록(`SyncRun`)은 끝을 기록하지 않아 **영원히 RUNNING** 이다. 운영자는 `/admin/sync/runs` 를 보고 "아직 도는 중"이라 믿는다.
4. 다시 돌려도 같은 프록시에서 같은 자리에 끊긴다.

README 172~174행은 "앞단 프록시의 타임아웃이 먼저 나도 재적재 자체는 계속 돈다"고 약속하는데, 코드는 그렇지 않다.

app-ldap 쪽은 더 나쁘다(같은 구조임을 코드로 확인, 재현은 안 함).
- `mode=snapshot`: 직전 스냅샷의 튜플을 지우는 도중 끊기면, OpenFGA 에서는 예컨대 3만 건이 지워졌는데 스냅샷은 그대로다. 다음 날 03:00 회차는 "이미 있다"고 보고 다시 쓰지 않는다. **정기 동기화로도 낫지 않는다.**
- `mode=store`: store 재생성 직후 끊기면 다음 날 03:00 까지 전사 권한 0.
- `mode=wipe`(app-scim): DynamoDB 직원·조직이 반만 지워진 채 끝난다.
- `/admin/sync/full`: OpenFGA 배치 몇 개만 반영되고 스냅샷은 안 남는다 — M2 의 창을 거의 확실히 밟는다. 실행 가드도 취소 신호에 풀려 다음 실행이 겹칠 수 있다.

**누가 겪나.** 프록시·로드밸런서 뒤에서 관리 API 로 재적재·전체 동기화를 부르는 모든 운영자. 10만 명 규모에서는 거의 매번이다.

**위치**
- `app-scim/.../AdminSyncController.java:61-66` — 유스케이스 Mono 를 응답으로 그대로 돌려준다(따로 구독하지 않는다)
- `core/.../usecase/ScimRebuildUseCase.java:77-87` — 끝 기록은 완료·오류에서만, `doFinally` 는 취소에도 락을 반납
- `app-ldap/.../AdminSyncController.java:41-46, 51-61, 70-79` — 같은 구조
- `core/.../usecase/RebuildUseCase.java:53-62, 70-73, 96-104`, `core/.../usecase/FullSyncUseCase.java:51-60`
- `README.md:172-174` — 사실과 다른 안내

**증거.** 재현됨 — `ScimRebuildUseCaseTest.점검_재현_요청이_끊기면_재적재가_멈춘다`. 취소 뒤 store 비움 1회, 결과 기록 0, 스냅샷 0, 락 반납 1.
"HTTP 끊김 → 구독 취소"는 WebFlux/Reactor Netty 의 표준 동작이다(spring-framework 이슈 #34005 — 공식 문서 아님). E2E 로 재지는 않았다.
ALB 기본 60초는 AWS 공식 문서.

**다른 오픈소스는.** Keycloak 의 수동 동기화 REST 도 요청 스레드에서 끝까지 돈다(같은 모양). authentik 은 수동·주기 모두 백그라운드 작업(dramatiq)으로 돌린다.

**고치는 방향.** 회차를 요청 수명에서 떼어낸다 — 스케줄러처럼 따로 구독하고, 곧바로 202 + runId 를 돌려주고, 결과는 `/runs` 로 본다.
취소·중단은 FAILED 로 기록한다. LDAP `mode=snapshot` 은 지우기 **전에** 포인터를 먼저 바꿔, 중간에 끊겨도 다음 회차가 전부 다시 쓰게 한다.
크기 **작음(분리만) ~ 중간(snapshot 모드 복구까지).**

---

#### C4. 작은따옴표가 든 아이디는 `members[value eq "…"]` 로 뺄 수 없다

**결함 · 치명 · 재현됨** · 원: F1

**무슨 일이 생기나.** 직원 `userName` 이 `o'brien@corp.com` 이면 직원 아이디도 `o'brien@corp.com` 이다 — 아이디는 `userName` 에서 오고, 정규화는 작은따옴표를 그대로 둔다.
Okta(기본)와 Entra(옵션 모드 — README 가 필수로 안내)는 멤버 한 명을 이렇게 뺀다:

```json
{"op":"remove","path":"members[value eq \"o'brien@corp.com\"]"}
```

우리 정규식은 값 안의 `'` 에서 값이 끝났다고 보고 매치에 실패한다. 그러면 경로가 `members`·`displayName` 도 아니라서 **400 `invalidPath`** 다.
IdP 는 같은 요청을 계속 재시도하고, 그 직원의 조직 소속과 OpenFGA 튜플(조직 권한)은 **그대로 남는다.**
Okta 처럼 한 요청에 `remove` + `add` 를 담으면 같은 요청의 `add` 도 반영되지 않는다(원자성).
우리가 받는 멤버 빼기 모양은 이것 하나뿐이라(값 붙은 remove 는 결정상 400), 이 사람을 조직에서 뺄 PATCH 방법이 없다.

덧붙여 JSON 문자열 이스케이프(`\"`)도 풀지 않는다. 반대로 RFC 에 없는 작은따옴표 감싸기(`'kim'`)는 받는다 — 표준 밖은 받고 표준 안은 못 받는다.

**누가 겪나.** Okta, Entra(옵션 모드), Keycloak 확장 — 조직에서 멤버를 빼는 모든 IdP. O'Brien·O'Connor·D'Souza 같은 이름은 10만 명 규모에서 반드시 있다(추정이지만 흔하다).

**위치**
- `connector-scim/.../ScimPatchApplier.java:35-36` — 패턴 `[\"'](?<value>[^\"']+)[\"']`
- 같은 파일 `:85-92`(매칭), `:114`(맞지 않으면 `invalidPath`)
- `core/.../tuple/IdNormalizer.java:26` — 금지 문자에 `'` 가 없다
- `connector-scim/.../ScimMapper.java:45` — 아이디가 `userName` 에서 온다

**증거.** 재현됨 — `ScimPatchApplierTest.점검_재현_작은따옴표_아이디_제거`: `ScimException invalidPath '지원하지 않는 path 입니다: members[value eq "o'brien@corp.com"]'`.

**다른 오픈소스는.** SCIMple·Goldfish 는 ANTLR 문법으로, UnboundID 는 Jackson JSON 파서에 맡겨 값을 읽는다. 셋 다 RFC 의 "큰따옴표 JSON 문자열, 이스케이프 포함"을 따른다.
따옴표를 정규식으로 흉내 내는 것은 **우리뿐이다.**

**고치는 방향.** 대괄호 안을 이미 있는 `ScimFilter` 의 JSON 문자열 파서(`ScimFilter.java:139-167`)로 읽는다(`value eq "<JSON 문자열>"` 한 항). 작은따옴표 허용은 표준 밖이라 뺀다. 크기 **작음.**

---

#### C5. LDAP 연결·읽기 타임아웃이 없다 — 동기화가 조용히 영원히 멈춘다

**결함 · 치명 · 코드로 확인**(끊김이 얼마나 자주 나는지는 추정) · 원: L-1, cost-batch P2

**무슨 일이 생기나.** 03:00 회차가 사용자 검색 120번째 페이지를 기다리는 사이, DC 가 있는 VM 호스트가 죽거나 중간 방화벽이 연결 상태를 버린다. RST 가 오지 않는다.
JNDI 는 읽기 타임아웃이 없으면 **응답이 올 때까지 기다린다**(Oracle JNDI 문서).

1. 읽기 스레드가 영원히 막힌다. 회차 전체에도 `.timeout` 이 없다.
2. 회차가 끝나지 않으니 실행 가드가 **영영 풀리지 않는다.**
3. 다음 날부터 매 cron 이 "이전 동기화가 아직 진행 중" 경고만 남기고 건너뛴다. `POST /admin/sync/full` 은 409 다.
4. 실행 기록은 RUNNING 으로 남고, 소요 시간 지표는 찍히지 않는다(끝난 실행만 기록한다).
5. LDAP 헬스는 **새 연결**로 바인드만 해 보므로 UP 이다. 오케스트레이터도 재시작하지 않는다.
6. 그동안 AD 에서 막은 퇴사자는 전부 권한을 유지한다. 7일을 넘기면 C1 로 이어진다.

AD 의 `MaxQueryDuration`(120초)은 **살아 있는** 연결에서 서버가 끊어 주는 장치라, 연결 자체가 죽은 경우는 못 막는다.
연결 타임아웃도 없어 응답 없는 주소면 OS 기본(리눅스 약 2분)까지 매달린다(추정).
OS 의 TCP keepalive 가 끊어 줄지는 모른다 — 켜져 있어도 리눅스 기본이면 첫 탐지까지 2시간이 넘는다.

**누가 겪나.** app-ldap 을 쓰는 모든 배포. 10만 명 회차는 커넥션 하나로 몇 분 동안 읽으므로 창이 넓다.

**위치**
- `connector-ldap/.../LdapConfig.java:18-26` — JNDI 타임아웃 설정이 없다
- `connector-ldap/.../strategy/PagedLdapSearch.java:75-81` — `SearchControls` 에 `timeLimit` 도 없다
- `connector-ldap/.../LdapDirectorySnapshotSource.java:58-68` — `.timeout` 없음, 재시도는 예외에만
- `app-ldap/.../SyncScheduler.java:28-40`, `app-ldap/.../AdminSyncController.java:70-79`, `app-ldap/.../SyncExecutionGuard.java:13-19` — 가드는 종료 신호에서만 풀린다
- `app-ldap/.../LdapHealthIndicator.java:44-57`

**증거.** 코드로 확인. 재현 스케치(실행하지 않음): 접속만 받고 아무것도 쓰지 않는 소켓에 붙여 `fetchAll()` 을 30초 기다리면 끝나지 않는다.
기존 `SyncSchedulerGuardReleaseTest` 는 동기 예외만 다루고 "끝나지 않는 회차"는 다루지 않는다.

**다른 오픈소스는.** Keycloak 은 연결 타임아웃 5초를 코드 기본값으로 넣는다(읽기 타임아웃은 기본 없음). authentik 은 연결·읽기 모두 15초를 늘 건다.
ConnId(Syncope)는 기본 0(무제한)이라 우리와 같다. 코드가 기본값을 박아 주는 둘에 비하면 우리가 더 위험하다.

**고치는 방향.** `com.sun.jndi.ldap.connect.timeout`·`read.timeout` 을 설정으로 노출하고(읽기는 AD 한 페이지 처리 시간보다 넉넉히), 회차 전체에 `.timeout` 백스톱을 두고,
"마지막 성공 시각" 게이지로 정체를 경보한다. 크기 **작음.**

---

#### C6. 큰 조직 변경이 락을 TTL(30초)보다 오래 쥔다

**성능 · 치명 · 측정** · 원: cost-scim 후보 2, W-8 · **알려진 것(큰 변경의 리스 갱신 백로그) — 새 근거는 측정값**

**무슨 일이 생기나.** "전 직원" 같은 10만 명 조직을 IdP 가 `DELETE /Groups/{id}` 하면, 우리는 락을 잡은 채 이 일을 한다:
조직 파티션 전체를 읽고, 멤버 10만 명을 한 명씩 GetItem 으로 읽고, 후보 튜플 10만 개를 BatchCheck 로 50개씩 **차례로** 묻는다.
**DynamoDB Local 에서 58.9초 걸렸다.** 락 TTL 은 30초다.

- 그 사이 다른 모든 SCIM 쓰기는 3초 기다린 뒤 503 이다.
- 이번 측정은 경쟁이 없어서 성공했다. 경쟁이 있으면: 30초가 지나는 순간 다른 인스턴스가 락을 가져간다 → 이 DELETE 는 OpenFGA 쓰기 직전 리스 확인에서 실패해 503 →
  IdP 가 재시도 → 같은 60초 → 또 실패. **다른 쓰기가 계속 들어오는 한 이 삭제는 끝나지 않는다**(경로는 코드로 확인, 반복은 추정). 쓰기 전에 멈추므로 데이터는 안 망가진다.
- 리스 확인이 통과해도, 그 뒤의 OpenFGA 쓰기 약 1,000 배치와 DeleteItem 약 20만은 리스 갱신 없이 돈다. 중간에 만료되면 다른 인스턴스와 **동시에** 쓴다.
- 삭제에는 직원의 활성 여부가 필요 없다(삭제 후 = 빈 목록이라 지금 있는 것을 전부 지우면 된다). 멤버 10만 명 `findUser` 는 필요 없는 읽기다.
- `remove members`(값 없음 = 전원 빼기)·빈 `replace members` 도 같은 길이다. 대량 추가도 명당 약 2.2ms 라 **약 1,400명부터 3초를 넘는다**(추정).

**누가 겪나.** 큰 조직을 지우거나 비우는 모든 IdP. 그 시간 동안 전체 IdP 트래픽.

**위치**
- `core/.../usecase/IncrementalSyncUseCase.java:469-498` — 조직 삭제
- 같은 파일 `:620-674` — 변경 적용. 리스 확인은 쓰기 직전 한 번(`:666`)
- 같은 파일 `:1027-1045` — 멤버 직원 전원 `findUser`
- `storage-dynamodb/.../DynamoDbDirectoryStateRepository.java:463-479` — 같은 파티션을 한 번 더 읽는다
- `authz-openfga/.../OpenFgaRelationTupleChecker.java:69-73` — BatchCheck 차례

**증거.** 측정(`ScimGroupMemberPatchScaleTest.점검_측정_큰_조직_삭제`, DynamoDB Local): 58,923ms, Query 15, 훑은 아이템 200,002, GetItem 100,000, Check 튜플 100,000, 응답 204.
픽스처의 멤버 10만 명은 직원 레코드·튜플 없이 멤버 줄만 있다 — 실제에서는 OpenFGA 쓰기(약 1,000 배치)와 직원 쪽 소속 줄 삭제가 더 붙는다(코드로 셈). AWS 에서의 시간은 모른다.

**다른 오픈소스는.** 큰 그룹 삭제를 배치·비동기로 처리하는 사례를 세 프로젝트에서 찾지 못했다(부재 증명이라 약하다).

**고치는 방향.** 삭제는 멤버 키만 읽고 직원을 읽지 않는다. 멤버 튜플은 Check 없이 "없으면 무시" 옵션으로 지운다. 큰 변경은 배치마다 리스를 갱신하거나 나눠 처리한다.
알려진 백로그(리스 갱신·BatchGet 재시도)와 합친다. 크기 **중간~큼.**

---

#### C7. OpenFGA 가 느리거나 죽으면 SCIM 재적재가 락을 수십 분~수십 시간 쥔다

**결함 · 치명(조건부 — OpenFGA 장애 때) · 코드로 셈** · 원: cost-batch P1

**무슨 일이 생기나.** 재적재는 튜플 배치를 **차례로** 쓴다. 배치 하나가 실패하면 앱이 3번 더, SDK 가 그 안에서 3번 더 시도한다(배치당 최대 16번, 번마다 10초 타임아웃).
그래도 실패하면 "실패 결과"로 바꾸고 **다음 배치를 계속** 보낸다. 전체 기한이 없다.
그 사이 하트비트는 DynamoDB 만 살아 있으면 10초마다 리스를 갱신한다 — 락을 끝까지 놓지 않는다.

10만 명(배치 약 1,100개) 기준 추정:
- OpenFGA 가 즉시 5xx 를 줄 때 — 약 75분
- 서버가 3초 타임아웃으로 답할 때 — 약 16시간
- 네트워크가 무응답일 때(10초 타임아웃) — 약 50시간
- OpenFGA 가 과부하로 Write 하나에 2초씩만 걸려도 — 약 37분

이 시간 내내 **모든 SCIM 쓰기가 503** 이다. store 는 이미 비웠으므로 **인가 공백도 같은 길이**다.
LDAP 쪽은 전역 락은 없지만 실행 가드를 그만큼 쥔다 — 다음 cron 은 건너뛰고 `/full` 은 409. 매일 도는 작은 회차는 배치가 몇 개라 몇 분에 그치고, 최초 적재·개편·재적재가 위 숫자를 탄다.

알려진 백로그 "큰 변경의 락 리스 갱신"은 갱신을 **못 해서** 락을 잃는 문제다. 이것은 반대로 갱신을 **계속 해서** 락을 놓지 않는 문제다.

**같은 뿌리의 작은 것(cost-scim 후보 9).** SCIM 쓰기 한 건도 OpenFGA 가 타임아웃을 내면 Write 배치 하나에 최대 약 160초 락을 쥔다. 리스는 30초에 풀리므로 그 뒤 다른 인스턴스의 쓰기와 겹친다.

**누가 겪나.** OpenFGA 장애·과부하 중에 재적재·최초 적재를 돌리는 배포. 그동안의 모든 IdP 트래픽.

**위치**
- `authz-openfga/.../OpenFgaRelationTupleWriter.java:50`(배치를 차례로), `:90`(앱 재시도 3회), `:92-95`(실패 배치 뒤에도 계속)
- `core/.../usecase/ScimRebuildUseCase.java:129-138` — 하트비트
- SDK 0.9.11 — 요청 10초, 429·5xx·네트워크 오류 3회 재시도(바이트코드로 확인). OpenFGA 서버 기본 요청 타임아웃 3초(문서)

**증거.** 경로는 코드로 확인. 시간은 SDK·서버 기본값과 배치 수로 센 추정이다. 재현하지 않았다.

**고치는 방향.** 재적재·동기화에 전체 기한을 두고, 배치가 잇달아 실패하면 멈춘다. 검증 오류 같은 4xx 는 재시도하지 않는다(M16 과 같이). 크기 **중간**(추정).

---

#### C8. 쓰기 경로의 Check 가 OpenFGA 캐시를 우회하지 않는다

**결함 · 치명(조건부 — OpenFGA Check 캐시를 켤 때) · 코드+문서로 확인** · 원: W-1, R-7

**무슨 일이 생기나.** 우리 설계는 "Check 결과 = 지금 실제로 있는 것"을 전제로 무엇을 쓰고 지울지 정한다. 이 전제는 OpenFGA 캐시가 꺼져 있을 때만 참이다.
운영에서 성능을 위해 Check 캐시를 켜면(`OPENFGA_CHECK_QUERY_CACHE_ENABLED=true`, TTL 기본 10초):

1. `t=0초` kim 을 FIN 에 추가하는 PATCH → Check 가 "없음"을 받고 튜플을 쓴다. **OpenFGA 가 이 "없음"을 10초 캐시한다.**
2. `t=3초` kim 을 FIN 에서 빼는 PATCH → Check 가 **캐시된 "없음"**을 받는다 → 지울 것이 없다 → 멤버 줄만 지운다.
3. 결과: 상태에서는 kim 이 FIN 멤버가 아닌데 OpenFGA 에는 kim 의 FIN 권한이 남는다. 이후 어떤 연산도 이 튜플을 후보로 삼지 않아 **재적재 전까지 영구히 남는다.**

같은 모양으로: 직원 생성·조직 추가 직후 10초 안의 `active=false` 는 퇴사자 권한을 남긴다. 반대로 멤버를 빼고 10초 안에 다시 넣으면 캐시된 "있음" 때문에 안 써서 권한이 빠진다.
**지금은 나지 않는다** — 캐시는 기본 꺼짐이고, 테스트·docker-compose 도 켜지 않는다. README 요구 버전 절에도 이 제약이 없다.

**누가 겪나.** OpenFGA 캐시를 켜는 모든 운영 배포.

**위치.** `authz-openfga/.../OpenFgaRelationTupleChecker.java:99-102`(BatchCheck, 옵션 없음), `:46-51`(Check).

**증거.** 코드로 확인 — 일관성 옵션을 넘기지 않는다. 쓰는 SDK 0.9.11 에는 `HIGHER_CONSISTENCY` 옵션이 있다(`javap` 로 확인).
문서로 확인 — "MINIMIZE_LATENCY … serve queries from the cache", "HIGHER_CONSISTENCY … skip the cache", 캐시 기본 꺼짐
(https://openfga.dev/docs/interacting/consistency, https://openfga.dev/docs/getting-started/setup-openfga/configuration). 재현하지 않았다.

**고치는 방향.** 쓰기 경로의 Check(`existing`)에 `HIGHER_CONSISTENCY` 를 넘긴다. 관리 조회의 Check 는 지연 우선이어도 된다.
README 에 "캐시를 켜도 쓰기 기준선은 우회한다" 한 줄. 크기 **작음(한 줄).**

---

### 4.2 중 — 결함·표준

#### M1. 순환 때문에 버린 child 엣지를, 순환이 풀린 뒤 다시 쓰지 않는다

**결함 · 중 · 재현됨** · 원: W-2

**무슨 일이 생기나.** 지금 조직도: 본부 ⊃ A ⊃ B. 개편으로 B 를 A 위로 올린다. IdP 는 조직 간 요청 순서를 보장하지 않으므로 이 순서로 올 수 있다.

1. `PATCH /Groups/B add members [{"value":"A","type":"Group"}]` → 새 엣지 "A 는 B 의 하위". 저장소를 보니 아직 A ⊃ B 라 순환이다 → **엣지를 버린다.** 멤버 줄 "B ⊃ A" 는 저장된다.
2. `PATCH /Groups/A remove members[value eq "B"]` → "B 는 A 의 하위" 삭제. 이제 순환이 없다.
3. **"A 는 B 의 하위" 튜플은 영영 안 쓰인다.** 조직 PATCH 는 요청에 나온 멤버만, PUT 은 바뀐 멤버만 보는데 A 는 이미 B 의 멤버라 어디서도 안 걸린다.
   A 의 직원 전원이 B 와 B 의 상위 조직으로 롤업되지 않는다 — **있어야 할 권한이 빠진다.** 드리프트 지표도 안 오른다. 관리 조회에서 "있어야 함/실제" 가 갈리는 것으로만 보인다.

LDAP 은 매 회차 전체를 다시 계산해 다음 회차에 저절로 낫는다. SCIM 에는 "다시 계산된다"는 성질이 없다.

**누가 겪나.** 중첩 조직(`type:"Group"` 멤버)을 보내는 IdP 만. 조사한 카탈로그에서 중첩을 보낸다고 확인된 IdP 는 없다 — 그래서 치명이 아니라 중이다.

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:689-718`(순환 엣지 버림), `:383-385`·`:376-380`(요청에 나온·바뀐 멤버만), `:866-870`(멤버 줄은 저장), `:685-687`(자바독).

**증거.** 재현됨 — `IncrementalSyncUseCaseTest.점검_재현_순환이_풀려도_엣지를_안_쓴다`: 상태가 요구하는 "A 는 B 의 하위" = 참, 쓴 적 = 없음.

**고치는 방향.** 순환으로 버린 엣지를 멤버 줄에 표시하고, child 엣지를 지우는 연산 끝에서 표시된 엣지만 다시 검사해 쓴다(드물어 싸다).
쉬운 절반: 조직 쓰기마다 그 조직의 하위 조직 멤버를 계산에 싣는다(B 를 다시 건드릴 때만 낫는다). 크기 **중간.** 한 홉 순환의 비슷한 문제는 S4.

---

#### M2. LDAP 회차가 OpenFGA 에 쓴 뒤·스냅샷 저장 전에 끊기면 기준선이 어긋난 채 굳는다

**결함 · 중 · 코드로 확인**(빈도는 추정) · 원: L-2 · 원 리뷰는 치명으로 봤으나 "중단 + 되돌림" 두 조건이 필요해 중으로 둔다

**무슨 일이 생기나 — 권한이 남는 쪽.**
1. 월요일 스냅샷 S0 에 park 이 없다. 화요일 03:00, 신규 입사자 park 의 권한을 OpenFGA 에 쓴다.
2. 이어 스냅샷 저장이 실패한다. 예: 10만 튜플을 한 파티션에 25개씩 차례로 **통째로** 다시 쓰다 스로틀로 5회 재시도를 넘김, 배포로 프로세스가 내려감, 또는 C3 의 취소. 포인터는 여전히 S0.
3. 수요일 park 의 입사가 취소돼 AD 에서 삭제된다. 목요일 회차: 기준선 S0 에도, 목표에도 park 이 없다 → 지울 것에 park 이 없다. **park 의 권한은 영원히 남는다.**

**권한이 빠지는 쪽도 대칭이다.** S0 에 kim 있음 → kim 이 실수로 비활성화돼 삭제가 반영됨 → 스냅샷 저장 실패 → 오후에 재활성 → 목요일: 기준선에도 목표에도 kim 있음 → 변화 없음 → **kim 은 권한 없이 굳는다.**

코드와 설계는 "스냅샷 저장이 실패하면 다음 회차가 이전 스냅샷 기준으로 다시 계산한다"고 전제하지만, 그 사이 OpenFGA 는 이미 바뀌었다.
같은 튜플이 다음 목표에도 있으면 우연히 맞지만, 디렉터리가 그 튜플에 대해 **도로 바뀌면** 영영 안 맞는다.
10만 명에서는 창이 넓다 — 창 = 첫 OpenFGA 배치부터 포인터 쓰기까지, 곧 스냅샷 전체 재기록(약 4,400번의 차례 BatchWrite)을 포함한다. C3 와 겹치면 자주 밟는다.

**누가 겪나.** app-ldap. 스냅샷 저장이 실패하고, 그 사이 같은 사람이 되돌려지는 경우.

**위치.** `core/.../usecase/FullSyncUseCase.java:80-81, 96-115`, `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:68-84`(통째 재기록), `:239-262`(재시도 5회와 그 전제의 주석).
설계 `2026-08-14-organization-sync-design.md` §6.2.

**증거.** 코드로 확인. 기존 `LdapInterruptedSyncScaleTest` 는 "스냅샷 뒤·상태 앞" 끊김만 다룬다(그쪽은 수렴한다). "OpenFGA 뒤·스냅샷 앞"은 테스트가 없다. 재현하지 않았다.

**고치는 방향.** 쓰기 **전에** 이번 회차의 델타(의도)를 저장하고, 다음 회차가 미커밋 의도를 보면 기준선을 보수적으로 넓힌다 —
지울 판단은 "S0 + 의도한 쓰기", 쓸 판단은 "S0 − 의도한 삭제"(쓰기·삭제가 멱등이라 넓혀도 안전). 또는 직전 델타의 튜플만 BatchCheck 로 관찰한다(SCIM 이 이미 이 방식). 크기 **중간.**

---

#### M3. 요청 본문이 256KB 를 넘으면 413 이 아니라 500 이다

**결함·표준 · 중 · 재현됨** · 원: F3, W-13 · **알려진 백로그 "넘으면 어떤 상태코드?"의 답**

**무슨 일이 생기나.** 조직 PUT·POST·PATCH 에 멤버 약 7천 명 이상을 담으면(PingFederate 의 기본 조직 PUT, Auth0 가 받는 1만 명 한도) 본문 한도 예외가 난다.
WebFlux 는 이 예외를 번역하지 않고, 우리 오류 번역도 어느 분기에도 안 맞아 **500 "내부 오류가 발생했습니다"** 가 나간다. RFC 7644 §3.12 에는 413 이 있다.
IdP 는 500 을 일시 오류로 보고 **같은 요청을 계속 재시도**할 수 있다(Entra 는 실패 건수에 쌓여 격리 판단에 들어간다). 반대로 영구 실패로 보면 그 조직은 영영 안 바뀐다.

**누가 겪나.** 큰 조직을 한 번에 보내는 IdP — PingFederate(PUT 기본), Okta 조직 PUT, 큰 POST.

**위치.** `connector-scim/.../ScimRouter.java:82-91`, 본문을 읽는 곳 `ScimGroupHandler.java:25,44,63`, `ScimUserHandler.java:23,42,57`, `ScimListHandler.java:48-51`.
Spring 6.2.19 `DefaultServerRequest.bodyToMono` 는 두 예외만 번역한다(바이트코드로 확인).

**증거.** 재현됨 — 511,006바이트 PATCH 가 500(`ScimGroupHandlerTest.점검_재현_큰_본문은_413`, 413 을 기대하고 지금 실패).

**다른 오픈소스는.** SCIMple·Charon·Goldfish 는 본문 한도를 Bulk 전용으로 두고 넘으면 413 + SCIM Error 다. 단일 리소스 요청은 서블릿 컨테이너가 413 을 낸다.
우리 스택(WebFlux/Netty)은 컨테이너가 413 을 만들어 주지 않고 예외를 던지는데 그것을 잡는 곳이 없다 — **우리가 예외다.**

**고치는 방향.** 한도 예외(원인 체인 포함)를 413 SCIM Error 로 번역하고 한도를 `detail` 에 적는다. 한도를 올릴지는 별도 결정(인증 전 노출). 크기 **작음.**

---

#### M4. 재시도하면 낫는 실패를 500 으로 준다

**결함 · 중 · 코드로 확인**(IdP 반응은 문서·추정) · 원: W-4

**무슨 일이 생기나.** 코드가 500 을 두 가지 뜻으로 쓴다. 락 쪽 주석은 "IdP 는 500 을 영구 실패로 읽어 프로비저닝을 버린다 — 그래서 락 실패·DynamoDB 장애는 503 으로 옮긴다"고 적는다.
그런데 **재시도해야만 낫는** 경로는 전부 500 이다.
- 튜플 부분 실패(일부 배치만 성공)
- OpenFGA Check 실패(서버 장애, 개별 오류, 응답 누락)
- 락 **갱신** 중 DynamoDB 장애(조건 실패가 아닌 것)
- 락 안의 DynamoDB 읽기·쓰기 장애

예: 퇴사자 비활성화 PATCH 에서 조직 150개 중 앞 100개 튜플 삭제만 성공한다. 상태는 `active=true` 로 되돌려 저장되고(재시도가 같은 델타를 다시 계산하게 하려는 의도) 응답은 500 이다.
코드 스스로의 전제대로 IdP 가 500 을 영구 실패로 읽으면 **아무도 재시도하지 않고, 남은 50개 조직 권한이 퇴사자에게 남는다.**
Entra 는 "개별 오류는 다음 주기에 재시도"라고 문서화해 영향이 작다. Okta 는 5xx 에 대한 반응이 문서에 없다 — 실패가 Tasks 큐로 가 사람이 "Retry Selected" 를 눌러야 하는지 확인하지 못했다.

**누가 겪나.** OpenFGA·DynamoDB 장애 중의 모든 IdP. 특히 Okta(반응 미확인).

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:537-541`, `:824-829`, `connector-scim/.../ScimRouter.java:70-73, 90-91`,
`ScimUserHandler.java:69-72, 81-84`, `ScimGroupHandler.java:70-73, 81-84, 93-96`, `ScimException.java:64-67`,
`authz-openfga/.../OpenFgaRelationTupleChecker.java:44-45, 104, 146-148, 151-154, 164-166`, `storage-dynamodb/.../DynamoDbMutationLock.java:116-117`.

**증거.** 코드로 확인. Entra 재시도는 문서로 확인(how-provisioning-works §Errors and retries). Okta 반응은 미확인. 재현하지 않았다.

**고치는 방향.** 부분 실패와 하위 시스템 장애(OpenFGA 호출 실패, DynamoDB SDK 예외, 갱신의 비조건 오류)는 503 + `Retry-After`(S2)로. 500 은 진짜 버그에만. 크기 **작음.**

---

#### M5. 저장하지 않는 속성이 path 로 섞이면 비활성화까지 통째로 거절된다

**결함 · 중 · 알려진 것 — 새 근거**(우리 쪽은 코드로 확인, Entra 쪽은 문서로 확인 + 추정) · 원: F2

**무슨 일이 생기나.** Entra 옵션 모드는 "여러 속성 교체" 때 **필터가 든 경로는 따로 path 연산으로**, 나머지는 path 없는 값 객체로 보낸다(호환성 문서).
Entra 기본 매핑에는 `phoneNumbers[type eq "work"].value`·`addresses[type eq "work"].streetAddress` 같은 속성이 있다. 전화번호가 있는 직원이 퇴사하면 Entra 가 이렇게 보낸다:

```json
[{"op":"replace","path":"phoneNumbers[type eq \"work\"].value","value":"010-…"},
 {"op":"replace","value":{"active":false}}]
```

첫 연산이 400 `invalidPath` 이고, PATCH 는 원자적이라(RFC 7644 §3.5.2) 요청 전체가 실패한다 → **`active=false` 도 반영되지 않는다. 퇴사자 권한이 남는다.**
우리 GET 은 전화번호를 돌려주지 않으므로, Entra 는 갱신 전 현재 값을 확인할 때마다 차이를 보고 **그 직원의 모든 갱신에 같은 연산을 다시 실을 것이다**(추정).
즉 전화번호가 바뀔 때만이 아니라 그 직원의 비활성화·개명·이메일 변경이 전부 막힌다. 옵션(`?aadOptscim062020`)을 빠뜨리면 `title`·`department`·`employeeNumber` 까지 전부 path 로 온다.

README 는 "path 로 오면 400, IdP 매핑에서 빼라"까지만 적는다. **새로 짚는 것은 막히는 것이 그 속성만이 아니라 같은 요청의 비활성화라는 점이다.**
JumpCloud 활성화 단계의 갱신이 `jobTitle` 등을 path 로 보내면 활성화 자체가 실패할 수 있다(모양 미확인).

**누가 겪나.** 기본 매핑을 지우지 않은 Entra 테넌트. 저장 안 하는 속성을 path 로 보내는 다른 IdP.

**위치.** `connector-scim/.../ScimPatchApplier.java:151-162`(path 형 → `invalidPath`), `:169-198`(모르는 경로), `:173-175`(`work` 가 아닌 이메일 필터), `ScimUserHandler.java:55-62`.

**증거.** 우리 동작은 코드로 확인. Entra 요청 모양은 문서로 확인([호환성 문서](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-config-problem-scim-compatibility),
[튜토리얼 속성 표](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)). "매번 다시 싣는다"는 추정. 재현하지 않았다.

**고치는 방향(결정 필요).** (a) RFC 코어·enterprise 스키마에 **정의된** 속성인데 우리가 저장하지 않는 것은 path 형도 path 없는 값처럼 무시한다 — 두 모양이 한 규칙이 되고, 스키마에 없는 이름만 400.
(b) 400 을 유지하되 README 에 "비활성화까지 막힌다"를 적고 연결 체크리스트로 강제한다. 크기 **(a) 작음 / (b) 문서.**

---

#### M6. 시작 때 OpenFGA 가 안 닿았고 store 가 없으면, 재시작 전까지 멤버 추가가 전부 500

**결함 · 중 · 코드로 확인** · 원: W-5

**무슨 일이 생기나.** 첫 배포에서 k8s 가 app-scim 을 OpenFGA 보다 먼저 띄운다(초기화 코드의 주석이 직접 예로 든 상황). 초기화는 경고만 남기고 넘어간다.
app-scim 에서 store 를 **만드는** 곳은 시작 시 한 번과 튜플 쓰기뿐인데, 쓰기 경로는 **Check 를 먼저** 하고, Check 는 store 를 만들지 않으며 없으면 오류다.

- 연결 테스트, 직원 POST(조직 없음), 조직 POST(Entra 는 빈 멤버로 만든다)는 후보 튜플이 없어 Check 를 건너뛰므로 성공한다.
- **첫 멤버 추가 PATCH 부터 전부 500** 이다. 앱을 재시작하거나 재적재를 돌리기 전까지 계속된다.
- 초기화 주석의 "이후의 동기화가 자연스럽게 재시도한다"는 app-scim 에서는 거짓이다. Entra 초기 동기화 중이면 그룹 멤버 실패가 통째로 쌓인다(격리 상한 6만 쪽).

**같은 뿌리의 작은 것.** 인가 모델을 쓰기 **전에** storeId 캐시를 채운다 — 모델 쓰기가 실패하면 모델 없는 store 를 캐시한 채 재시작 전까지 Check·Write 가 실패한다.

**누가 겪나.** 배포 순서가 겹치는 첫 배포. app-scim 을 쓰는 모든 IdP.

**위치.** `authz-openfga/.../OpenFgaStoreInitializer.java:36-43`(실패를 삼킨다), `:22-26`(주석), `OpenFgaRelationTupleWriter.java:49`,
`core/.../usecase/IncrementalSyncUseCase.java:634`(Check 먼저), `OpenFgaRelationTupleChecker.java:63-68`, `StoreBootstrapper.java:303-306`.

**증거.** 코드로 확인. 재현하지 않았다.

**고치는 방향.** 쓰기 경로에서 Check 전에 store 를 보장하거나, 초기화를 성공할 때까지 백그라운드로 재시도한다. storeId 캐시는 모델 쓰기 성공 뒤에 채운다. 크기 **작음.**

---

#### M7. 지운 직원의 아이디를 새 입사자가 다시 쓰면 고아 튜플의 권한을 물려받는다

**결함 · 중 · 알려진 것 — 새 근거**(코드로 확인) · 원: W-6

**무슨 일이 생기나.** `kim@corp.com` 에게 상태에 없는 튜플 "kim 은 FIN 멤버"가 남아 있다고 하자(원인: C8 캐시, M8, "비멤버의 고아 튜플은 고치지 않는다"는 알려진 한계, 락 틈).
퇴사로 `DELETE /Users/kim@corp.com` 이 오면, 삭제는 **상태에 있는 소속 조직의 튜플만** 지운다 → FIN 튜플은 남고 레코드는 지워진다.
몇 달 뒤 같은 메일 주소의 신규 입사자를 IdP 가 POST 한다 → 아이디는 `userName` 에서 그대로 `kim@corp.com` → 소속 줄이 없으니 아무것도 확인하지 않는다 →
**새 입사자가 FIN 권한을 갖는다.** 상태에 FIN 소속이 없어 드리프트 지표에도, 관리 조회에도 안 보인다.

OpenFGA Read API 가 금지라 삭제 때 그 직원의 튜플을 전부 열거할 수 없다. 그래서 **아이디 재사용 자체가 증폭기**다.
이름을 바꾼 사람의 옛 이름 재사용은 409 로 막히지만(알려진 것), **삭제된 사람의 이름 재사용은 안 막힌다** — 오히려 이쪽이 권한 상속 위험을 갖는다.

**누가 겪나.** 메일 주소·계정명을 재사용하는 회사. SCIM 모든 IdP. LDAP 쪽도 같은 성격이다(M9).

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:424-448`(삭제 후보는 상태의 소속뿐), `connector-scim/.../ScimMapper.java:45`.

**증거.** 코드로 확인. 알려진 백로그 "userName 에서 만든 직원 아이디"의 새 근거(삭제 후 재사용의 보안 결과). 재현하지 않았다.

**고치는 방향.** 직원 아이디를 재사용되지 않는 값(서버 발급 UUID 또는 IdP `externalId`)으로 — 백로그의 `USERNAME#` 표지판 작업과 같은 판.
차선: 지운 아이디를 묘비로 남겨 재생성을 409 로 막기(운영 부담). 크기 **중간~큼.**

---

#### M8. 빈 델타 커밋과 쓰기 뒤 커밋이 리스를 다시 확인하지 않는다

**결함 · 중 · 코드로 확인**(빈도는 추정) · 원: W-3

**무슨 일이 생기나.** 리스 재확인은 OpenFGA 쓰기 직전 한 번뿐이다. 바꿀 튜플이 없으면 재확인 없이 바로 커밋하고, 튜플을 쓴 **뒤의** DynamoDB 커밋도 재확인하지 않는다. 커밋은 조건 없는 덮어쓰기다.

예 1(빈 델타). 입사 직후라 아직 조직이 없는 kim(Entra 는 직원을 먼저 만들고 멤버십은 뒤 주기에 보낸다).
1. 인스턴스 A: `PATCH /Users/kim displayName` — 락 안에서 kim(활성)을 읽은 뒤 DynamoDB 스로틀 재시도·GC 로 30초 넘게 멈춘다 → 리스 만료.
2. 인스턴스 B: 락을 가져가 `active=false` 를 저장한다.
3. A 재개: 후보 튜플이 없어 델타가 비었다 → 재확인 없이 옛 값(`active=true`)을 저장 → **비활성화가 되돌려진다.**
4. 뒤이어 조직 PATCH 로 kim 이 조직에 들어가면 kim 이 활성이라 튜플이 생긴다 — IdP 는 비활성으로 아는 사람에게 권한이 생긴다.

예 2(쓰기 뒤). A 가 재입사 `active=true` → 재확인 성공 → 튜플 씀 → 커밋이 30초 넘게 멈춤. B 가 `active=false` → 튜플 지움 → 비활성 저장.
A 의 커밋이 늦게 떨어져 `active=true` 저장. 다음에 kim 을 건드리는 연산이 "활성인데 튜플이 없다"고 보고 다시 쓴다 → 퇴사자 권한 부활.

주석은 "델타가 비면 재확인하지 않는다"는 의도를 적었고, 큰 변경의 리스 갱신은 백로그다. 그러나 **작은 요청이 오래 멈췄을 때 남의 비활성화를 덮는다**는 결과는 어디에도 없다.
OpenFGA 는 펜싱(늦은 쓰기 막기)이 안 되지만, **DynamoDB 상태 쓰기는 조건부 쓰기로 펜싱할 수 있다** — 설계에서 빠진 점이다.

**누가 겪나.** app-scim 을 여러 대 띄우고, 한 요청이 30초 넘게 멈추는 경우(드묾, 추정).

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:653-656`(빈 델타 → 바로 커밋), `:666-670`(쓰기 뒤 커밋), `:603-608`(주석),
`storage-dynamodb/.../DynamoDbDirectoryStateRepository.java:128-150`(조건 없는 `putItem`), `DynamoDbMutationLock.java:32-34`.

**증거.** 코드로 확인. 재현하지 않았다.

**고치는 방향.** 작게: 델타가 비어도 커밋 직전 리스를 갱신한다(요청당 UpdateItem 1번). 근본: 커밋 쓰기를 `TransactWriteItems` 로 묶어 락 토큰에 조건을 건다(상태 쓰기 펜싱).
크기 **작음(갱신) / 중간(펜싱).**

---

#### M9. LDAP 아이디가 이름 속성에서 오고, 안정 식별자는 설정으로도 못 쓴다

**결함 · 중 · 알려진 것 — 새 근거**(코드·문서로 확인) · 원: L-7

**무슨 일이 생기나.** 직원 아이디·조직코드 기본값이 `uid`/`cn`/`ou` 다. AD 에서 그룹의 `cn`, OU 의 `ou` 는 곧 **이름**이다.
- **개명 = 삭제 + 생성.** DIT 에서 `OU=개발1팀` 을 `OU=플랫폼팀` 으로 바꾸면 조직코드가 바뀐다 → 그 OU 직속 5,000명의 튜플을 지우고 새 코드로 다시 쓴다.
  삭제가 기준선의 30% 를 넘으면 **가드가 매일 멈추고**, 그동안 다른 모든 변경(퇴사)도 반영되지 않는다. 다른 앱이 옛 조직 이름으로 준 권한은 끊긴다. 옛·새를 잇는 기록도 없다(감사 로그 없음은 백로그).
- **대소문자만 바꿔도 새 사람.** AD 관리자가 `JKim` 을 `jkim` 으로 고치면 `user:JKim` 삭제 + `user:jkim` 생성이다.
- **이름 재사용이 권한을 물려받는다.** 퇴사한 `jkim` 이 지워진 뒤 신규 입사자가 `jkim` 을 받으면, 다른 앱이 `user:jkim` 으로 준 권한을 물려받는다(추정).
- **DIT 는 이름 충돌이 흔하고, 충돌하면 팀이 통째로 소속 0.** `OU=Sales,OU=Seoul` 과 `OU=Sales,OU=Busan` 은 둘 다 `Sales` → 뒤에 읽힌 쪽은 건너뛰고 그 아래 직원은 경고만 남긴 채 조직 권한을 못 받는다.
- **안정 식별자는 설정으로도 못 쓴다.** `objectGUID` 는 이진 값인데 JNDI 기본 이진 목록에 없어 **깨진 문자열**로 읽힌다. `entryUUID` 는 운영 속성이라 이름을 대 요청해야 오는데, 우리 검색은 속성 목록을 안 보낸다 → "필수 속성 'entryUUID' 가 없습니다"로 회차 실패.

**누가 겪나.** app-ldap 을 쓰는 모든 배포. 특히 DIT 전략과 AD.

**위치.** `connector-ldap/.../LdapProperties.java:39,45,57,61`(기본값), `.../strategy/GroupOfNamesStrategy.java:133, 154, 247-254`, `.../strategy/DitStrategy.java:75, 106, 169-171`,
`.../strategy/PagedLdapSearch.java:75-81`, `core/.../tuple/IdNormalizer.java:31-36`, `core/.../guard/DeletionGuard.java:31-39`.

**증거.** 코드로 확인. JNDI 이진 속성 목록·`entryUUID` 운영 속성은 문서로 확인(Oracle JNDI 가이드, RFC 4512 §3.4, RFC 4530 §2). 재현하지 않았다.
알려진 백로그 "userName 에서 만든 직원 아이디"와 같은 성격의 문제가 LDAP 에도 있다는 것이 새 근거다.

**다른 오픈소스는.** Keycloak(AD 는 `objectGUID`, 그 밖은 `entryUUID`), authentik(`objectSid`), ConnId(`entryUUID`/`objectGUID`), Gluu(`objectGUID`) — **넷 다 불변 id 를 쓴다.** 우리가 뚜렷한 예외다.

**고치는 방향.** 식별 속성으로 `objectGUID`(이진으로 선언하고 16진 문자열로)·`entryUUID`(명시 요청)를 쓸 수 있게 하고 AD 권장값으로 문서화한다.
이름 기반을 유지하면 README 에 "개명 = 삭제+생성, 큰 조직 개명은 가드가 멈춘다"를 적는다. 크기 **중간.**

---

#### M10. AD `primaryGroupID` 소속을 못 본다 — 평범한 관리 조작이 권한을 지운다

**결함 · 중 · 코드+문서로 확인** · 원: L-8 · 알려진 것(Domain Users 같은 기본 그룹 문제) — 새 근거

**무슨 일이 생기나.** 지금까지는 "Domain Users 같은 기본 그룹"의 문제로만 적혀 있었다. 새 근거는 **어떤 그룹이든 사용자의 기본 그룹으로 지정하는 순간 AD 가 그 그룹의 `member` 에서 사용자를 뺀다**는 것이다(Microsoft 문서의 예: User1 의 기본 그룹을 Group1 으로 바꾸면 Group1 의 Members 에서 빠진다).

예: macOS/Unix 연동에서 GID 를 맞추려고 관리자가 `jkim` 의 기본 그룹을 `DEV002` 로 바꾼다 → 다음 회차에 `DEV002` 의 `member` 에 jkim 이 없다 →
jkim 의 DEV002 권한이 지워지고 상위 조직 롤업도 잃는다. 오류도 경고도 없다. (Microsoft 의 Entra Connect 도 같은 방식으로 기본 그룹을 뺀다 — 업계 관행이라는 완화 사정.)

**누가 겪나.** AD 를 groupOfNames 전략(그룹 `member` 기반)으로 붙인 배포.

**위치.** `connector-ldap/.../strategy/GroupOfNamesStrategy.java:96-113` — `member` 만 대조한다. 코드 전체에 `primaryGroupID`·`objectSid` 참조가 없다.

**증거.** 코드로 확인. AD 동작은 문서로 확인([MS-ADA3 primaryGroupID](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-ada3/e12954a4-6865-4432-94e6-00c310ca87c0),
[Entra Connect excludes primary group](https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/user-prov-sync/exclude-user-primary-group)). 실제 AD 로는 확인 못 했다.

**다른 오픈소스는.** Keycloak·authentik 에서도 `primaryGroupID` 전용 처리를 찾지 못했다 — 우리만의 결함은 아니다.

**고치는 방향.** 사용자 `primaryGroupID` 와 그룹 `objectSid` 의 RID 를 맞춰 소속에 더한다(이진 선언 필요, 크기 **중간**). 또는 README 에 "조직 그룹을 기본 그룹으로 쓰지 않는다"를 운영 조건으로 적는다(크기 **작음**).

---

#### M11. 식별 속성이 없는 엔트리 하나가 10만 명 회차 전체를 멈춘다

**결함 · 중 · 코드로 확인** · 원: L-9 · 알려진 결정(README 275~277) — 새 근거(10만 명에서의 결과)

**무슨 일이 생기나.** 10만 명 AD 에서 `user-id-attribute: employeeNumber`(코드 주석이 권하는 교체값)를 쓴다. IT 가 `OU=People` 에 백업용 서비스 계정 하나를 만들었는데 사번이 없다.
다음 회차: "필수 속성 'employeeNumber' 가 없습니다" → 재시도 없이 FAILED → 누가 그 계정을 고칠 때까지 **매일 전사 동기화가 실패하고, 그동안 AD 에서 막은 퇴사자 전원이 권한을 유지한다.**
7일을 넘기면 C1 로 이어진다. 신규 계정이 HR 데이터보다 먼저 만들어지는 흔한 순서만으로도 생긴다(추정).

같은 코드 안에서 규칙이 갈린다 — 정규화 뒤 **중복된** 아이디는 경고하고 건너뛰며 계속하는데, **없는** 아이디는 전체를 실패시킨다. 둘 다 "이 엔트리는 아이디를 가질 수 없다"는 같은 상황이다.

**누가 겪나.** app-ldap. 식별 속성을 모든 엔트리가 갖지는 않는 디렉터리(서비스 계정, 컴퓨터 계정 — M12).

**위치.** `connector-ldap/.../strategy/GroupOfNamesStrategy.java:239-245`, `.../strategy/DitStrategy.java:150-156`, `.../LdapDirectorySnapshotSource.java:62`, `.../strategy/DuplicateIdGuard.java:28-37`.

**증거.** 코드로 확인. 현재 동작을 `RequiredAttributeMissingTest`(:46, :63)가 고정하고 있다.

**고치는 방향(사용자 결정).** 중복과 같은 규칙(건너뛰고 경고 + 건수 지표)으로 맞추되, 건너뛴 수가 전체의 일정 비율을 넘으면 실패시킨다. 크기 **작음.**

---

#### M12. 사용자 검색 필터가 `objectClass` 한 값뿐 — AD 의 `user` 는 컴퓨터를 포함한다

**결함 · 중 · 코드로 확인**(컴퓨터 계정 위치는 배포마다 다름 — 추정) · 원: L-10

**무슨 일이 생기나.** AD 의 `computer` 클래스는 `user` 의 하위 클래스다. 그래서 `user-object-class: user` 는 컴퓨터(그리고 gMSA 등)까지 잡고, `person` 으로 바꾸면 연락처(contact)까지 잡는다.
AD 에서 사람만 고르는 관용 필터 `(&(objectCategory=person)(objectClass=user))` 는 지금 설정으로 표현할 수 없다.

예(DIT): `OU=DEV` 아래에 직원과 업무 PC(`CN=DEV-PC01`)가 함께 있다. 아이디 속성이 `sAMAccountName` 이면 `DEV-PC01$` 가 활성 직원이 되고 DEV 조직 권한을 받는다 —
관리 조회·직원 수·삭제 가드 기준선이 오염된다. 아이디 속성이 `uid`/`employeeNumber` 면 컴퓨터에 그 속성이 없어 M11 로 회차가 매일 실패한다.
어느 쪽이든 설정으로 고칠 수 없고 디렉터리 구조를 바꿔야 한다. 이전 감사(2026-09-09 §3.2)는 "값만 바꾸면 된다 ✓"로 평가했었다.

**누가 겪나.** AD 를 붙이는 배포.

**위치.** `connector-ldap/.../strategy/GroupOfNamesStrategy.java:54-58`, `.../strategy/DitStrategy.java:56-60`, `.../LdapProperties.java:37,60`.

**증거.** 코드로 확인. AD 클래스 관계는 문서로 확인(https://learn.microsoft.com/en-us/windows/win32/adschema/c-computer). 재현하지 않았다.

**다른 오픈소스는.** authentik 은 기본 필터부터 `person`(처음엔 `objectCategory=Person`)이라 컴퓨터 계정을 뺀다. Keycloak 은 기본 필터가 없고 관리자가 추가 필터를 넣는다(추정).

**고치는 방향.** 사용자·그룹 검색에 완전한 LDAP 필터 설정(`user-filter`)을 허용하고 AD 권장값을 README 에 적는다. 크기 **작음.**

---

#### M13. 재적재가 읽기 전에 store 를 비운다 — 읽기가 실패하면 전사 권한 0

**결함 · 중 · 코드로 확인** · 원: R-5, L-5, cost-scim 후보 6, cost-batch P8

**무슨 일이 생기나.**
- app-scim `mode=tuples`: store 비우기 **다음에** DynamoDB 에서 직원 10만 명을 읽는다. DynamoDB 스로틀·네트워크 오류로 읽기가 실패하면 FAILED 로 끝나고, store 는 **빈 채로** 락이 풀린다 → 운영자가 다시 돌릴 때까지 모든 인가가 false.
- app-ldap `mode=snapshot`/`store`: 직전 튜플을 전부 지우거나 store 를 비우고, 보관 스냅샷을 **전부**(7일치, 각 10만 아이템) 지운 **다음에** LDAP 을 읽는다.
  LDAP 이 불안정해서(재적재를 부르는 흔한 이유) 읽기가 실패하면 다음 날 03:00 까지 전사 권한이 없다. 데이터 오류(M11 같은 엔트리 하나)면 누가 그것을 고칠 때까지 **무기한**이다.
- 성공해도 인가 공백이 "읽는 시간 + 쓰는 시간"이 된다. 읽기(약 60초, 추정)와 스냅샷 전량 삭제(7개면 90~180초, 추정)는 공백과 무관하게 미리 하거나 뒤로 미룰 수 있다.

README 와 설계는 "재적재가 끝날 때까지의 공백"을 받아들였다. **읽기 실패로 공백이 무기한 이어지는 것, 그리고 순서만 바꾸면 사라진다는 것은 새 근거다.**

**누가 겪나.** 재적재를 돌리는 모든 운영자. 특히 LDAP 이 불안정할 때.

**위치.** `core/.../usecase/ScimRebuildUseCase.java:144-159`, `core/.../usecase/RebuildUseCase.java:64-73, 96-104, 128-136`, `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:193-198`(모든 스냅샷 삭제).

**증거.** 코드로 확인. 재현하지 않았다.

**고치는 방향.** 읽기(`loadAll`/`fetchAll` + 튜플 계산)를 먼저 끝내고, 성공했을 때만 비우기 → 쓰기. 스냅샷 전량 삭제는 재적재 뒤·락 밖으로(정합성에 필요한 것은 포인터를 끊는 것뿐으로 보인다 — 추정). 크기 **작음.**

---

#### M14. app-ldap 여러 대를 막는 클러스터 락이 없다

**결함 · 중 · 코드로 확인**(피해 순서는 추정) · 원: L-6

**무슨 일이 생기나.** 가용성 때문에 app-ldap 을 replica 2 로 띄운다. README 에 "app-ldap 은 한 대여야 한다"는 말이 없다 — 오히려 "app-scim 여러 대 띄우기" 절이 있다. 실행 가드는 프로세스 안의 `AtomicBoolean` 이다.
- 두 대의 cron 이 **같은 초(03:00:00)에** 돈다 — 우연이 아니라 매일 겹친다. LDAP 전체 읽기 두 번, 스냅샷 10만 아이템 재기록 두 번(같은 파티션 → 스로틀 → M2).
- 한 대가 `rebuild?mode=store` 를 하면 다른 대는 옛 storeId 로 계속 쓴다(C2).
- 한 대의 재적재와 다른 대의 전체 동기화가 섞이면, 포인터가 OpenFGA 에 없는 튜플을 담은 스냅샷을 가리킬 수 있다 → 권한 소실(추정, 드묾).
- AD `MaxActiveQueries`(기본 20) 같은 DC 쪽 한도에도 같이 부딪힐 수 있다.

**누가 겪나.** app-ldap 을 두 대 이상 띄우는 배포.

**위치.** `app-ldap/.../SyncExecutionGuard.java:5-8`("인스턴스가 하나라는 전제"), `app-ldap/.../UseCaseConfig.java:29-32`, `authz-openfga/.../StoreBootstrapper.java:69-72`, 설계 §8(인스턴스 1개 전제).

**증거.** 코드로 확인. 단일 인스턴스 전제는 설계·주석에 있지만 운영자가 보는 README 에 없고 막는 장치가 없다. 재현하지 않았다.

**다른 오픈소스는.** Keycloak(`ClusterProvider` 로 한 노드만)과 authentik(PostgreSQL advisory lock)은 클러스터 전역 락이 있다. **인스턴스가 둘 이상이면 우리만 예외다.**

**고치는 방향.** 회차 전체를 이미 있는 DynamoDB 조건부 쓰기 락(다른 키)으로 감싸고 README 에 적는다. storeId 캐시는 C2 와 함께. 크기 **중간**(리스 갱신은 알려진 백로그와 같은 문제).

---

#### M15. 아카이빙이 인스턴스마다 락 없이 돈다

**결함·성능 · 중 · 코드로 확인**(메모리 크기는 추정) · 원: R-6, cost-scim 후보 7, cost-batch P9

**무슨 일이 생기나.** app-scim 을 세 대 띄우면 매일 03:00 아카이빙이 **세 번** 돈다(리더 선출·락 없음). 한 번이 직원 10만 GetItem + BatchCheck 약 2,200번 차례 + 스냅샷 10만 아이템 한 파티션 저장이다(각 수 분, 추정).
- 같은 날 스냅샷이 세 개 생기고, 7일이면 21개(약 230만 아이템)를 보관한다. 포인터는 마지막에 쓴 쪽이 이긴다. 04:00 정리도 세 대가 같은 스냅샷을 지운다.
- 03:00 에 누가 `mode=tuples` 재적재를 돌리고 있으면, **반쯤 찬 store 를 "실제"로 관찰**해 스냅샷으로 남기고 "빠진 튜플 수만 건" 경고를 찍는다 — 감사 기록이 오해를 부른다.
- 재적재가 store 를 다시 만들면 아카이빙의 BatchCheck 가 지워진 store 로 간다(C2).
- 락이 없으니 아카이브는 한 시점의 사진도 아니다 — 읽기와 BatchCheck 사이에 쓰기가 끼면 "어긋남"이 거짓으로 나온다.
- 전역 쓰기 락과는 무관해 503 은 안 만든다.

**누가 겪나.** app-scim 을 여러 대 띄운 배포.

**위치.** `app-scim/.../ArchiveScheduler.java:27-43`, `core/.../usecase/SnapshotArchiveUseCase.java:71-92, 99-106`, `authz-openfga/.../OpenFgaRelationTupleChecker.java:69-71`.

**증거.** 코드로 확인. 재현하지 않았다.

**고치는 방향.** 하루 1회 표지(`ARCHIVE#날짜` 에 `attribute_not_exists` 조건부 쓰기)를 먼저 잡은 인스턴스만 돈다. 재적재 중이면 건너뛴다. 크기 **작음.**

---

#### M16. 잘못된 튜플 하나가 100개 배치를 통째로 실패시키고, 다시 돌려도 같은 99개가 빠진다

**결함 · 중 · 코드로 확인**(OpenFGA 길이 한도는 추정) · 원: R-9

**무슨 일이 생기나.** OpenFGA Write 는 요청 하나가 원자적이라 튜플 하나가 검증에 걸리면 같은 요청의 100개가 모두 실패한다. 아이디 정규화는 금지 문자만 바꾸고 **길이를 보지 않는다.**
조직 `externalId`(SCIM)나 LDAP `ou` 가 OpenFGA object 길이 한도(약 256바이트 — 한글 약 85자, 추정)를 넘는 조직이 하나 있으면,
재적재에서 그 튜플과 같은 배치에 든 **다른 직원 99명의 권한**이 함께 빠진다. 결과는 PARTIAL 이다.
다시 돌려도 배치 순서가 같아 같은 99명이 또 빠진다. LDAP 은 다음 회차가 실패분만 다시 보내는데 그것들이 한 배치에 모여 **계속** 같이 실패한다. 검증 오류(영구)도 세 번 더 재시도한다.

**누가 겪나.** 긴 조직 이름·코드를 가진 회사. 재적재·LDAP 최초 적재.

**위치.** `authz-openfga/.../OpenFgaRelationTupleWriter.java:66-96`, `core/.../tuple/IdNormalizer.java:26-36`.

**증거.** 코드로 확인. 길이 한도 수치는 GitHub PR(openfga/openfga#3301, 공식 문서 아님) 근거라 추정. 재현하지 않았다.

**고치는 방향.** 4xx(검증 오류)는 재시도하지 않고 배치를 반씩 쪼개 다시 보내 범인만 실패로 남긴다. 입구(SCIM·LDAP)에서 길이를 검증해 거절하면 더 좋다. 크기 **작음.**

---

#### M17. 첫 배포에 두 인스턴스가 동시에 뜨면 같은 이름의 store 가 둘 생길 수 있다

**결함 · 중 · 코드로 확인**(확률은 추정) · 원: R-10

**무슨 일이 생기나.** 빈 OpenFGA 에 app-scim 두 대가 동시에 뜬다. 둘 다 "store 목록 → 없음 → 만들기"를 해 같은 이름의 store 가 둘 생긴다(OpenFGA 는 이름 유일성을 강제하지 않는다 — 코드 주석도 인정).
A 는 X, B 는 Y 에 쓴다 → C2 와 같은 갈라짐. 다음 재시작부터는 "같은 이름 store 2개"로 멈춰 그 인스턴스의 쓰기가 전부 실패한다.
창은 목록 조회와 생성 사이 수십 ms 라 드물지만, 첫 배포의 기본 모양(replicas: 2)에서 생긴다.

**누가 겪나.** 여러 대로 첫 배포하는 경우.

**위치.** `authz-openfga/.../StoreBootstrapper.java:103-119, 271-286`, `OpenFgaStoreInitializer.java:36-43`.

**증거.** 코드로 확인(중복 방지는 한 프로세스 안에만 있다). 재현하지 않았다.

**고치는 방향.** C2 의 "storeId 한 줄"을 DynamoDB 조건부 쓰기(`attribute_not_exists`)로 먼저 차지한 쪽만 store 를 만든다. 크기 **중간**(C2 와 함께).

---

#### M18. Entra 기본 매핑의 `manager` 를 안 지우면 10만 명 초기 동기화가 격리될 수 있다

**결함 · 중 · 추정** · 원: review-scim-write §2

**무슨 일이 생기나.** 우리는 `manager` 를 저장하지 않아 `manager` path PATCH 를 400 `invalidPath` 로 거절한다(알려진 제한, README 는 "매핑에서 빼라"고 안내).
Entra 기본 매핑에 `manager` 가 있으면, 관리자가 있는 직원마다 manager 갱신이 1건씩 실패한다. Entra 는 이것을 참조 실패로 센다.
참조 실패는 40%·40,000 계산에서는 빠지지만, **참조 실패를 합친 60,000건 절대 상한**에는 들어간다. 10만 명 초기 동기화에서 관리자가 있는 직원이 6만 명을 넘으면 그것만으로 격리될 수 있다.
격리되면 하루 1회로 줄고 4주 뒤 작업이 멈춘다.

`manager` 를 따로 PATCH 한다는 근거는 Entra 문서의 옛(2018년대) 참조 코드 예시뿐이라 추정이다. 같은 이유로 `filter=id eq … and manager eq …` 조회도 400 이다.

**누가 겪나.** 10만 명 규모 Entra 테넌트에서 매핑을 정리하지 않은 경우.

**위치.** `connector-scim/.../ScimPatchApplier.java:188-197`, `connector-scim/.../ScimUserListing.java:27-32`.

**증거.** 격리 임계값은 문서로 확인(https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-quarantine-status). 요청 모양과 규모 영향은 추정.

**고치는 방향.** README 의 매핑 안내에 "지우지 않으면 10만 명 규모에서 격리까지 간다"를 한 줄 더한다. 크기 **작음(문서).**

---

### 4.3 중 — 성능

#### P1. `type` 없는 멤버는 한 명씩 차례로 읽는다 — Entra·Okta 는 `type` 을 보내지 않는다

**성능 · 중 · 측정** · 원: W-7, F4, cost-scim 후보 1

**무슨 일이 생기나.** SCIM 에서 멤버의 `type`(직원/조직)은 선택 필드다. Entra 는 `{"$ref":null,"value":"…"}`, Okta 는 `{"value":"…","display":"…"}` 로 보낸다 — **둘 다 `type` 이 없다.**
그러면 멤버마다 "조직인가?"(GetItem) → 아니면 "직원인가?"(GetItem)를 **한 명씩 차례로** 묻고, 매번 경고 로그를 한 줄 남긴다. 락 밖이라 남을 막지는 않지만 요청 하나의 지연이 멤버 수에 비례한다.

- **측정:** 1,000명 add — 1.76초, GetItem 4,002, BatchGet 키 1,000, Check 1,000. `type` 있는 1명 add 는 GetItem 3 이다.
- AWS 에서 GetItem 한 번을 3~8ms 로 잡으면, 1,000명이면 판정만 약 6~16초, 본문 한도 근처 7천 명이면 약 42~112초다(추정). IdP 요청 타임아웃(미확인)을 넘으면 IdP 가 재시도하고, 재시도도 같은 시간이 걸린다.
- 직원 10만 명의 멤버십을 처음 채우면 경고가 **10만 줄 이상**이다. Entra 에서는 이것이 정상 경로라 경고의 뜻이 사라진다.
- 기존 규모 테스트는 전부 `"type":"User"` 를 넣어 이 비용을 재지 않았다.

**누가 겪나.** Entra, Okta — 조직 멤버를 보내는 거의 모든 요청.

**위치.** `connector-scim/.../ScimPatchApplier.java:320-331`(`concatMap` 328), `:344-348`, `ScimMapper.java:102-120`(`concatMap` 107), `StateMemberTypeResolver.java:24-32`.

**증거.** 측정(`ScimGroupMemberPatchScaleTest.점검_측정_type_없는_멤버_추가`, DynamoDB Local). AWS 시간은 추정.

**다른 오픈소스는.** 선례가 없다. SDK 들(UnboundID·SCIMple·Charon)은 판정을 구현자에게 넘기고, Keycloak 서버 플러그인은 중첩 그룹을 포기해 멤버를 항상 직원으로 본다.
우리는 중첩 조직을 지원하므로 그 회피는 못 쓴다.

**고치는 방향.** 한 요청의 `type` 없는 id 를 모아 BatchGetItem(조직 META·직원 META 키, 100개씩) 두어 번으로 판정한다. 경고는 요청당 한 줄 요약. 크기 **작음~중간.**

---

#### P2. 하위 조직을 붙일 때 순환 검사가 자손 조직마다 Query 를 차례로 한다

**성능 · 중 · 코드로 셈** · 원: cost-scim 후보 3(같은 자리의 500 은 cost-scim §4-4)

**무슨 일이 생기나.** 부서 개편으로 자손 조직 2,000개짜리 본부를 새 상위 조직 밑으로 옮기는 PATCH(`add members [{"value":"본부","type":"Group"}]`)는
락 안에서 강한 일관성 Query 2,000번을 **차례로** 한다 — 약 10초(추정). 리스 갱신 **전**이라 자손이 약 6,000개를 넘으면(30초 ÷ 5ms) TTL 을 넘겨 C6 와 같은 반복 실패에 빠진다.
조직을 아래에서 위로 만들며 **최상위 조직을 마지막에 POST** 하면 조직도 전체를 훑는다.
자손이 1만을 넘으면 `IllegalStateException` → **500** 이다. 요청마다 결정적으로 다시 넘으므로 재시도해도 늘 실패한다.

**누가 겪나.** 중첩 조직을 보내는 IdP 만(M1 과 같은 조건). 그런 IdP 에서는 치명이 될 수 있다.

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:689-718`(엣지마다), `:743-758`(`expand`, 하나씩), `:765-778`(예산), `:112`(1만), `connector-scim/.../ScimRouter.java:90-91`(500).

**증거.** 코드로 셈. 측정하지 않았다.

**고치는 방향.** 예산 초과는 500 대신 400 `invalidValue`(영구 거절)로 — 뜻에 맞는 신호다. 차례 Query 를 줄이는 구체안은 재료에 없다 — 큰 변경의 리스 갱신(C6)과 함께 설계한다.

---

#### P3. 전역 락 재시도 간격 200ms 고정이 락을 비워 두고, 전체 쓰기 처리량의 상한을 정한다

**성능 · 중 · 추정**(구조는 코드로 확인) · 원: cost-scim 후보 4

**무슨 일이 생기나.** 모든 SCIM 쓰기는 전역 락 하나를 거친다. 요청 하나가 락을 쥐는 시간은 직원 POST 약 35ms ~ 직원 DELETE 약 95ms 다(추정).
그래서 **인스턴스를 몇 대 띄워도** 쓰기 처리량은 초당 약 10~28건을 넘지 못한다.
락을 못 잡은 요청은 200ms 뒤에야 다시 시도한다. 앞 요청이 35ms 만에 끝나도 기다리던 요청은 최대 200ms 뒤에 온다.

| 동시 요청 | 처리량(락 보유 40ms 가정, 추정) |
|---|---|
| 1 | 경합 없음 — IdP 왕복이 병목 |
| 2 | 약 7건/초 — **하나씩 보낼 때보다 느려진다** |
| 10 | 약 17건/초 |
| 20 | 약 20건/초, 약 4% 가 503 |
| 40 | 약 18% 가 503 |
| 80 | 약 40% 가 503. 대기자들의 획득 시도만 초당 약 400번 |

획득이 공정하지 않다(먼저 온 요청이 아니라 반납 직후 먼저 시도한 요청이 잡는다). 10만 명 초기 POST 는 락 시간만으로 **최소 약 1시간**이다(10만 × 35ms, 하한).
멤버십 15만 건(직원 1인당 1.5개 가정)을 한 명씩 PATCH 로 받으면 락 시간만 약 3시간이다(추정). 실측은 5천 명 규모에서 16스레드로 200건을 보내 503 이 4건 난 것 하나뿐이다.

**누가 겪나.** 동시에 여러 요청을 보내는 IdP(동시성 수치는 Entra·Okta 모두 공식 문서에 없다). 초기 동기화, 대규모 개편.

**위치.** `core/.../usecase/IncrementalSyncUseCase.java:115, 553-556`, `storage-dynamodb/.../DynamoDbMutationLock.java:51-77`.

**증거.** 구조는 코드로 확인. 처리량·503 비율은 추정 모형이다. IdP 의 실제 동시 요청 수를 모른다.

**다른 오픈소스는.** Goldfish SCIM-SDK 는 RFC 7644 §3.14 대로 **리소스별** ETag + 412 로 충돌을 알린다. UnboundID·Charon 도 리소스별 충돌 어휘(409/412)를 갖는다.
우리는 모든 쓰기를 막는 전역 락 하나에 "대기 실패"를 503 으로 알리는 **정반대 극단**이다.

**고치는 방향.** 재료에 구체안은 없다. 먼저 할 수 있는 것: 락 안의 중복 읽기 제거(S28, 처리량 약 30~40% 증가 추정), 재시도 간격이 만드는 빈 시간 줄이기.
근본(락 범위를 리소스별로)은 동시성 설계 §4.1 이 "처리량 때문에 스케일 아웃하면 다시 연다"고 예고한 재설계다. 크기 **중간**(근본은 큼).

---

#### P4. 관리 API 조직 상세·멤버 목록이 페이지마다 조직 파티션 전체를 읽는다

**성능 · 중 · 코드로 셈** · 원: R-4, cost-batch P6

**무슨 일이 생기나.** 멤버 10만 명 조직에서 `GET /admin/organizations/ROOT/members?limit=20` 을 부르면, 조직 파티션을 강한 일관성으로 **통째로** 읽고(약 8쪽, 7.5MB, 약 1,900 RCU),
10만 id 를 메모리에서 정렬한 뒤 20개만 잘라 쓴다. 원본 아이템 약 65MB 가 순간 힙에 오른다.
관리 화면이 끝까지 넘기면(`limit=100`) 1,000번 요청 × 10만 줄 = **1억 줄**을 읽는다(`limit=20` 이면 5억). 조직 상세도 첫 페이지 20명을 위해 같은 전체 읽기를 한다.
인증이 없는 GET 이라 누구나 반복 호출로 증폭할 수 있다. admin-api 는 app-scim 과 같은 힙과 같은 DynamoDB 연결 풀을 쓴다 — 동시 10개면 순간 힙 약 0.6GB(추정).
관리자 조회 설계는 커서 페이징을 약속했지만 구현은 "전부 읽고 오프셋으로 자르기"다.

**누가 겪나.** 관리 화면을 쓰는 운영자, 그리고 같은 프로세스의 SCIM 트래픽.

**위치.** `core/.../usecase/AdminQueryUseCase.java:241-252`(두 엔드포인트 모두 `findGroup`), `:290-303`(하위 조직), `:305-329`(전체 정렬 뒤 자르기),
`storage-dynamodb/.../DynamoDbDirectoryStateRepository.java:265-270`, `admin-api/.../AdminQueryController.java:75-93`.

**증거.** 코드로 셈. 측정하지 않았다.

**고치는 방향.** 조직 상세는 조직 헤더 + 하위 조직 줄만 읽는다. 멤버 목록은 `MEMBER#USER#` 접두 Query 에 `Limit` 과 시작 키를 쓰고 커서로 감싼다 — 재료(`querySortKeys`)가 이미 있다. 크기 **중간.** 오프셋 커서가 흔들리는 문제(S16)도 함께 풀린다.

---

#### P5. 멤버를 돌려주는 조직 조회가 조직 파티션 전체를 메모리에 싣는다

**성능 · 중 · 코드로 셈**(메모리 수치는 추정) · 원: F5, cost-scim 후보 8

**무슨 일이 생기나.** Okta 는 "쿼리 파라미터 없는 `GET /Groups/{id}` 에는 멤버 전체를 돌려줘야 한다"고 요구하고, 존재 확인 `GET /Groups?filter=displayName eq "…"` 에도 `excludedAttributes` 를 붙이지 않는다.
"전 직원" 같은 10만 명 조직이면, 파티션 전체를 강한 일관성으로 읽어 모으고(1MB 쪽 약 7~13번) → 도메인 객체 → SCIM 객체 → JSON 트리로 **여러 벌을 동시에** 든다.
요청 하나에 수십~100MB 이상, 응답 약 3.5~5MB(추정). 필터 없는 `GET /Groups` 는 한 페이지 100개 조직을 읽어 모은 뒤 응답한다. 이런 요청이 몇 개 겹치면 OOM 위험이다(몇 개부터인지는 힙 설정이 없어 모른다).
`PATCH …?attributes=members` 도 쓰기 뒤에 같은 읽기를 한다.

**누가 겪나.** Okta(기본 GET 이 이 경로), PingFederate·JumpCloud(그룹을 다시 읽는 경로가 있다). Entra 는 `excludedAttributes=members` 를 붙여 해당 없음.

**위치.** `connector-scim/.../ScimGroupHandler.java:33-39, 68-69, 108-112`, `ScimGroupListing.java:46-57`, `storage-dynamodb/.../DynamoDbDirectoryStateRepository.java:266-270, 718-727`,
`ScimMapper.java:159-171`, `ScimJson.java:17-19`.

**증거.** 읽는 양은 코드로 셈. 메모리는 객체 크기 어림(추정). 알려진 것(S-1 설계 §9 "멤버십 총수에 선형")의 새 근거 — 요청 **하나가** 10만 명 조직을 통째로 힙에 올린다는 점.

**다른 오픈소스는.** Keycloak SCIM 서버도 멤버를 상한 없이 전부 돌려준다 — 우리가 예외는 아니다. 다만 우리는 10만 명+ 규모를 전제로 한다.

**고치는 방향.** 멤버를 모으지 않고 Query 페이지를 흘려 JSON 으로 바로 쓰는 스트리밍 응답. 또는 멤버 수 상한을 두고 넘으면 `excludedAttributes=members` 를 쓰라고 안내 — Okta 요구와 충돌해 결정이 필요하다. 크기 **중간~큼.**

---

#### P6. OpenFGA 쓰기 결과를 합칠 때 배치마다 통째로 복사한다 — 비용이 제곱으로 는다

**성능 · 중 · 코드로 셈** · 원: R-8, cost-scim 후보 5, cost-batch P7

**무슨 일이 생기나.** 배치 결과를 합칠 때 매번 지금까지의 누적 집합 전체를 새 집합에 복사하고, 결과 객체 생성자가 **한 번 더** 복사한다.
최초 적재·재적재에서 튜플 11만(배치 약 1,100개)이면 해시 삽입이 약 **1.2억 번**, 버려지는 할당이 누적 약 3GB, CPU 2.5~6초다(추정).
11만 칸짜리 테이블이 배치마다 새로 생겨 G1 의 거대 객체 할당이 된다. SCIM 재적재와 10만 명 조직 삭제에서는 이 CPU 시간이 **락 안**이다.
AD 보안 그룹이 검색 범위에 섞여 튜플이 200만이 되면 삽입이 약 4×10¹⁰ 번 — 첫 적재가 사실상 멈춘다(추정).

**누가 겪나.** 튜플을 많이 쓰는 작업(최초 적재, 재적재, 큰 조직 변경). 작은 SCIM 요청은 배치가 한두 개라 무관하다.

**위치.** `authz-openfga/.../OpenFgaRelationTupleWriter.java:51, 133-141`, `core/.../model/TupleWriteResult.java:16-20`.

**증거.** 코드로 셈. 시간·할당은 추정. 측정하지 않았다.

**고치는 방향.** 가변 누적기(written·deleted·failures)에 모은 뒤 마지막에 한 번 불변으로 만든다. 크기 **작음.**

---

#### P7. LDAP 검색이 모든 속성을 받고, DIT 전략은 10만 엔트리 원본을 끝까지 쥔다

**성능 · 중 · 추정**(구조는 코드로 확인) · 원: L-11, cost-batch P3

**무슨 일이 생기나.** 검색이 반환 속성을 지정하지 않아 서버가 **모든 사용자 속성**을 준다. AD 사용자에는 `memberOf`(소속 그룹마다 DN 하나), `proxyAddresses`, `userCertificate`,
`thumbnailPhoto`(수~수십 KB) 등이 붙는다. 우리가 쓰는 것은 아이디·표시명·메일·이름·계정 상태 몇 개뿐이다.
- DIT 전략은 모든 엔트리의 원본 객체를 읽기가 끝날 때까지 들고 있다. OpenLDAP(속성 약 12개)은 0.2~0.3GB, **AD 는 10~20KB/명이라 1~2GB** — 힙이 1GB 이하면 OOM(추정).
- groupOfNames 전략은 매퍼에서 바로 작은 레코드로 바꿔 메모리는 작지만, **전송량은 같다** — AD 10만 명 × 10~20KB ≈ 매 회차 1~2GB. 속성을 지정하면 5~10배 준다(추정).
- 부수 효과로, OpenLDAP 에서 바인드 계정이 `userPassword` 를 읽을 수 있으면 그 해시도 힙에 올라온다.
- 5천 명 임베디드 테스트는 속성이 몇 개뿐이라 이 문제를 드러낼 수 없다.

**누가 겪나.** app-ldap, 특히 AD + DIT 전략.

**위치.** `connector-ldap/.../strategy/PagedLdapSearch.java:75-81`(범위만 설정), `.../strategy/DitStrategy.java:56-60, 119-120, 139-144`, `.../strategy/GroupOfNamesStrategy.java:127-143`.

**증거.** 구조는 코드로 확인. 크기는 AD 인스턴스가 없어 추정.

**다른 오픈소스는.** Keycloak 은 필요한 속성만 `setReturningAttributes` 로 요청한다. authentik 은 보조 패스만 제한하고 본 동기화는 전부 요청한다 — 우리만의 문제는 아니지만, Keycloak 쪽이 안전하다.

**고치는 방향.** 사용자·그룹 검색에 필요한 속성 목록을 명시하고(`userAccountControl`·`accountExpires`·`member` 포함), DIT 도 매퍼에서 값만 뽑는다. 크기 **작음.** M9 의 `entryUUID` 명시 요청과 같은 자리다.

---

#### P8. SCIM 재적재가 튜플 스냅샷 저장까지 전역 락 안에서 한다

**성능 · 중 · 코드로 셈** · 원: cost-batch P5 · 알려진 것("튜플 스냅샷 한 파티션 쏠림")의 새 근거

**무슨 일이 생기나.** SCIM 재적재는 튜플을 다 쓴 뒤 스냅샷을 저장하는데, 이것이 **락 안**이다. 10만 명이면 한 파티션에 25개씩 **차례로** BatchWrite 약 4,400번이다.
파티션 쓰기 한도(초당 1,000 WCU) 때문에 약 45~110초가 걸린다(추정). 이 구간에 들어온 IdP 쓰기는 모두 503 이다.
그런데 SCIM 쓰기 경로는 스냅샷을 읽지 않는다 — 최신 스냅샷을 읽는 곳은 LDAP 전체 동기화와 LDAP 재적재뿐이다.
락 안의 OpenFGA 쓰기 약 1,100번도 차례다. 이미 있는 튜플은 무시하는 옵션이라 병렬로 보내도 결과는 같다 — 22~66초가 줄 여지가 있다(추정).

**누가 겪나.** SCIM 재적재 중의 모든 IdP 트래픽.

**위치.** `core/.../usecase/ScimRebuildUseCase.java:165-176`(락 안의 스냅샷 저장), `:84-87`(반납), `storage-dynamodb/.../DynamoDbTupleSnapshotRepository.java:76-84`, `authz-openfga/.../OpenFgaRelationTupleWriter.java:50`.

**증거.** 코드로 셈. 측정하지 않았다.

**고치는 방향.** 스냅샷 저장을 락을 반납한 뒤로 옮긴다. OpenFGA 쓰기를 병렬로 보낸다. 크기는 재료에 없다 — 순서만 바꾸는 일이라 작을 것으로 본다(추정).

---

### 4.4 사소

중복은 합쳤다. "원"은 원 리뷰 ID 다.

| ID | 무엇 (예) | 위치 | 증거 | 원 |
|---|---|---|---|---|
| S1 | POST 201 에 `Location` 헤더가 없다(RFC 7644 §3.3 SHALL). `meta.location` 은 아이디를 인코딩하지 않아 `?`·`%`·`/` 가 든 아이디면 깨진 URL. Entra·Okta 는 본문의 `id` 를 읽어 영향은 작다(추정) | `ScimUserHandler.java:79-90`, `ScimGroupHandler.java:91-102`, `ScimMapper.java:156,170,181` | 코드로 확인 | W-9, F10 |
| S2 | 503 에 `Retry-After` 가 없고, 429 를 쓰지 않는다. 락 대기 3초 뒤 503 과 재적재 중 503 은 기다릴 시간이 크게 다른데 구분해 주지 않는다. AWS·Auth0 는 429 를 쓴다 | `ScimRouter.java:74-75, 94-100` | 코드로 확인 | W-11, 카탈로그 |
| S3 | IdP 가 큰 요청에서 연결을 끊으면 락을 곧바로 반납한다. 이미 보낸 OpenFGA 쓰기가 다음 요청의 Check 뒤에 떨어지면 다음 요청의 기준선이 틀린다(창은 밀리초) | `IncrementalSyncUseCase.java:574-581` | 추정 | W-12 **→ 해결(2026-10-02, 슬라이드 ③-1)** |
| S4 | 한 홉 순환이 있는 새 조직 POST 에서 두 엣지가 다 버려질 수 있다. 어느 쪽이 남는지가 조직코드 정렬 순서에 달린다(IdP 데이터 오류일 때만) | `IncrementalSyncUseCase.java:295-322`, `TupleMapper.java:120-151` | 코드로 확인 | W-10 **→ 해결(2026-10-03, 슬라이드 ③-2)** |
| S5 | Okta 식 조직 POST(`externalId` 없음)는 매번 새 UUID 를 조직코드로 쓴다. 응답을 잃은 재시도면 같은 이름의 조직이 둘. 본문의 `id` 를 코드로 쓰는 것도 RFC 7643 §3.1 위반(클라이언트가 정하면 안 된다) | `ScimMapper.java:83-92`, `IncrementalSyncUseCase.java:325-330` | 코드로 확인(Okta 재시도는 추정) | W-14 **→ 뒤쪽(본문 `id` 를 코드로 씀) 해결(2026-10-04, 슬라이드 ④-1)** — 서버가 UUID 를 발급하고 본문의 `id` 는 무시한다. **앞쪽(`externalId` 가 없는 Okta 식 조직 POST 를 재시도하면 같은 이름의 조직이 둘)은 남는다** — `externalId` 가 있으면 409 로 막는다 |
| S6 | JSON 본문의 속성 이름을 대소문자 구분해 읽고, 다르면 조용히 버린다(RFC 7643 §2.1 위반). 조직 PUT 의 `"Members"` → 멤버 전원 삭제, 직원 PUT 의 `"Active":false` → 활성, `.search` 의 `"Filter"` → 필터 없는 첫 페이지. 카탈로그 IdP 는 camelCase 라 가능성은 낮다(추정) | `dto/ScimUser.java:8-20`, `ScimGroup.java:8-17` 외 DTO | **재현됨**(`ScimGroupHandlerTest.점검_재현_속성_이름_대소문자`) | F6 |
| S7 | 라우트 밖 요청(`/Schemas`, `/ResourceTypes`, `/Bulk`, `/Me`, 틀린 메서드)의 오류가 Spring Boot 기본 JSON 이다(SCIM Error 아님, 405 대신 404). SCIMple 은 전역 매퍼로 모든 오류를 SCIM 형식으로 낸다 | `ScimRouter.java:37-58` | 코드로 확인 | F7 |
| S8 | PATCH `active` 의 문자열은 `"true"` 만 참이다. `"yes"`·`"1"`·`" true"` 는 조용히 비활성화한다. 방향이 "권한 사라짐"이라 위험은 낮다 | `ScimPatchApplier.java:366-374` | 코드로 확인 | F8 |
| S9 | 조직 PATCH 는 코어 스키마 URN 접두(`urn:…:core:2.0:Group:displayName`)를 모른다. 값 객체면 204 인데 이름이 안 바뀐다, path 면 400. 이 모양을 보내는 IdP 근거는 없다 | `ScimPatchApplier.java:94,109,121-131` | 코드로 확인 | F9 |
| S10 | 415 오류 `detail` 에 내부 자바 클래스 이름이 실려 인증 없는 엔드포인트로 나간다 | `ScimRouter.java:87-89` | 코드로 확인(문구는 추정) | F11 |
| S11 | path 없는 `remove` 의 `scimType` 이 RFC 가 정한 `noTarget` 이 아니라 `invalidSyntax` 다(상태코드는 맞다) | `ScimPatchApplier.java:80-82,155-157,310-314` | 코드로 확인 | F12 |
| S12 | 우리가 거절하는 조회·참조 모양 — `manager` 필터·PATCH, Entra 이메일 매칭 `emails[type eq "work"]`, Ping 관리자 필터 `co`, JumpCloud 이메일 재연결 — 모두 400. "표준 신호만 받는다" 원칙에 따른 알려진 제한. 격리 영향은 M18 | `ScimUserListing.java:27-32`, `ScimFilter.java:87,107-118` | 코드로 확인 | 카탈로그 |
| S13 | 재적재 하트비트가 일시 오류(스로틀·네트워크) 한 번에 재적재를 중단한다. 리스가 20초 남았는데도 store 를 반쯤 채운 채 FAILED, 락 반납. ③-1(2026-10-02) 뒤로 같은 성질이 큰 SCIM 쓰기(조직 삭제 등)에도 적용된다 — 갱신 한 번 실패로 멈추지만 META 가 마지막이라 재시도로 회복된다 | `ScimRebuildUseCase.java:129-138`, `DynamoDbMutationLock.java:101-119` | 코드로 확인 | R-11 |
| S14 | 락 획득 PutItem 이 서버에서 성공했는데 응답이 유실돼 SDK 가 재시도하면, 자기 락에 막혀 30초 동안 모든 SCIM 쓰기 503·재적재 409 | `DynamoDbMutationLock.java:65-75` | 코드로 확인(SDK 재시도 경로는 추정) | R-12 **→ 해결(2026-10-02, 슬라이드 ③-1)** |
| S15 | 락 만료 판단이 각 인스턴스의 로컬 시계다. 시계가 30초 이상 어긋나면 남의 락을 가져간다(NTP 환경이면 드묾) | `DynamoDbMutationLock.java:53-54,69-71,104` | 코드로 확인 | R-13 |
| S16 | 형식만 맞춘 위조 커서가 400 이 아니라 500 이다(파티션 밖을 읽지는 못한다). 조직 멤버 목록 커서는 오프셋이라 페이지 사이에 멤버가 바뀌면 건너뛰거나 두 번 준다 | `Cursor.java:50-78`, `AdminQueryUseCase.java:305-348` | 코드로 확인 | R-14 |
| S17 | 재적재·wipe 가 최종 일관성 GSI 로 대상을 열거한다. 락 직전(1초 안) 만든 직원이 빠질 수 있다 — `tuples` 는 튜플을 안 쓰고, `wipe` 는 안 지운다. DynamoDB Local 로는 재현 불가 | `DynamoDbDirectoryStateRepository.java:591-659` | 코드로 확인(GSI 지연은 추정) | R-15 |
| S18 | 스냅샷 포인터 GetItem 과 스냅샷 Query 가 최종 일관성이다. 직전 회차 직후 `/full` 을 누르면 약 1초 창에서 낡은 기준선을 볼 수 있다 | `DynamoDbTupleSnapshotRepository.java:117-120,270-277` | 코드로 확인 | cost-batch D2 |
| S19 | 관리자 검색 `?displayName=` 이 대소문자를 가린다(`userName`·조직명은 소문자 키로 찾는다) | `DynamoDbDirectorySearchRepository.java:44-45` | 코드로 확인 | cost-batch D3 |
| S20 | LDAP 쓰기 순서가 "삭제 배치 전부 → 쓰기 배치 전부"라, 5,000명 OU 개명 때 그 사이 5,000명이 어느 조직에도 없다. 쓰기가 실패하면 다음 회차(최대 하루)까지. 주석도 "순서가 뒤집히면 결과가 달라진다"고 틀리게 적는다 | `OpenFgaRelationTupleWriter.java:60-71` | 코드로 확인 | L-12 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| S21 | referral 로 인한 "불완전한 결과"를 DEBUG 로 삼킨다. 검색 범위가 자식 도메인·위임 서브트리를 걸치면 그 부분이 **항상** 빠지는데 운영자는 알 수 없다 | `LdapTemplates.java:52` | 코드·문서로 확인 | L-13 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| S22 | DIT: AD 기본 컨테이너 `CN=Users`(OU 가 아니다) 아래 사용자는 경고만 남기고 소속 없이 적재된다. 전원이 그래도 막는 가드가 없어 "아무도 권한 없이 SUCCEEDED"가 가능하다 | `DitStrategy.java:50-54,122-126` | 코드로 확인 | L-14 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| S23 | 다시 읽어도 같은 실패(크기 한도, 잘못된 비밀번호, 없는 검색 베이스, 공백 아이디)를 재시도해 10만 명을 4번 읽는다. 비밀번호가 틀리면 헬스 프로브까지 바인드해 서비스 계정이 잠길 수 있다(추정) | `LdapDirectorySnapshotSource.java:61-65`, `LdapHealthIndicator.java:44-48` | 코드로 확인 | L-15 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| S24 | OpenLDAP ppolicy 의 관리자 영구 잠금(`pwdAccountLockedTime: 000001010000Z`)을 활성으로 읽는다. 이 값은 일시 잠금이 아니라 사실상 비활성화다 | `AdAccountStatus.java:28-29,42-44` | 문서로 확인 · 알려진 것(README 270) | L-16 **→ 해결(2026-10-05, 슬라이드 ④-2)** |
| S25 | LDAP 직원의 `userName` 이 원본이 아니라 정규화된 아이디다(`uid: hong gd` → `hong_gd`). README 설명과 다르다 | `GroupOfNamesStrategy.java:75-77`, `DitStrategy.java:110-113` | 코드로 확인 | L-17 **→ 해결(2026-10-04, 슬라이드 ④-1)** |
| S26 | 만료 스냅샷 정리 잡은 AWS 에서도 일을 한다(TTL 이 "며칠 안에" 지우므로 먼저 지운다, 하루 약 11만 WRU 추정). 설계·주석의 "0건" 설명과 어긋날 뿐 해는 없다. 포인터 대상까지 지우는 것은 C1 | `SyncScheduler.java:43-53`, `ArchiveScheduler.java:36-43` | 코드로 확인 | cost-batch P13 |
| S27 | 아카이빙의 BatchCheck 약 2,200번과 조직 상세의 하위 조직 이름표 GetItem 최대 200번이 차례다(SDK 는 병렬 10 지원) | `OpenFgaRelationTupleChecker.java:71`, `AdminQueryUseCase.java:300-302` | 코드로 셈 | cost-batch P14 |
| S28 | 직원 쓰기가 락 안에서 같은 직원 META 를 세 번 읽는다(조직 PATCH 도 두 번). 차례 왕복 6~8개 중 2개라, 없애면 직원 쓰기 처리량이 약 30~40% 는다(추정) | `IncrementalSyncUseCase.java:190,221,237`, `DynamoDbDirectoryStateRepository.java:129,321` | 코드로 셈 | cost-scim 후보 4 **→ 해결(2026-10-03, 슬라이드 ③-2)** |

---

## 5. 알려진 결정·백로그에 대한 새 근거

이미 정했거나 백로그에 있던 것이다. 발견으로 새로 세지 않고, 이번에 생긴 근거만 적는다.

| 알려진 것 | 새 근거 | 확신 | 관련 |
|---|---|---|---|
| Entra 기본 모드의 값 붙은 `remove members` 는 400, `?aadOptscim062020` 필수(결정) | AWS `PatchGroup` **공식 개발자 가이드 예제**가 값 붙은 remove(필터 없이 `path:"members"` + `value:[…]`)를 표준 모양으로 싣는다 — SDK 기본값보다 무거운 근거다. AWS 는 서버라 우리에게 보내지는 않는다. Entra 튜토리얼 본문도 이 모양을 "Remove Members" 예시로 싣는다. PingFederate PATCH 모드의 remove 모양은 미확인. 재검토 트리거로 쓸 만하다(당장 바꾸자는 것은 아니다) | 문서로 확인 | — |
| 같은 결정 | Auth0 의 Entra 연동 가이드도 같은 `?aadOptscim062020` 을 같은 이유로 요구한다 — 우리 결정이 고립된 임기응변이 아니라는 교차 확인 | 문서로 확인 | — |
| 요청 본문 256KB 한도(멤버 약 7천 명) | 넘으면 **500** 이다(M3, 재현). Auth0 는 조직 POST·PUT 1만 명까지 받는다고 문서화한다. PingFederate 조직 갱신 기본값이 PUT(전체 멤버)이다. Okta 의 1,000명/PATCH(커뮤니티)는 한도 안이다 | 재현됨 + 문서 | M3 |
| 큰 변경의 락 리스 갱신 | 10만 명 조직 DELETE 가 **58.9초**(DynamoDB Local, 경쟁 없음). 삭제에는 필요 없는 멤버 전원 `findUser`. 커밋 단계는 리스 갱신 없이 돈다. 대량 추가는 약 1,400명부터 3초 초과(추정) | 측정 | C6 |
| BatchGet 재시도 상한·백오프 | 10만 명 조직 비우기는 BatchGet 을 안 쓴다(키 Query). 상한 문제는 K 가 큰 증분 PATCH(최대 약 70번 차례)에만 걸린다. `type` 없는 1,000명 add 측정에서 BatchGet 키 1,000 | 코드로 셈 + 측정 | P1 |
| 조직 POST·DELETE 의 `parentsOf` 통째 읽기 | 부모 직원 `findUser` 가 전·후 그림에서 **두 번**이다 — 부모 10만 명이면 GetItem 20만(약 60초, 추정). 없는 직원마다 경고가 그림마다 1줄(최대 20만 줄). DELETE 는 커밋에서 부모 멤버 키를 또 훑는다 | 코드로 셈 | — |
| userName GSI 지연 틈 | 재적재·wipe 도 GSI 로 대상을 열거해 비슷한 틈이 있다 — 효과가 다르다(S17) | 코드로 확인 | S17 |
| userName 에서 만든 직원 아이디(옛 이름 재사용 불가) | **삭제된** 사람의 아이디 재사용은 막히지 않고 고아 튜플 권한을 물려받는다(M7). LDAP 도 같은 성격이다(M9) | 코드로 확인 | M7, M9 |
| 튜플 스냅샷 한 파티션 쏠림 | 쏠림이 없어도 변경 1건마다 차례 BatchWrite 약 4,400번(45~90초 바닥). SCIM 재적재에서는 락 안(P8). 아카이빙은 인스턴스 수만큼(M15). 저장 실패가 느림이 아니라 **기준선 어긋남**으로 이어진다(M2) | 코드로 셈 | P8, M15, M2 |
| `loadAll` 직원당 GetItem | 같은 메서드가 먼저 GSI(ALL 프로젝션)로 직원 아이템 전체를 받아 놓고 id 만 뽑아 버린 뒤 GetItem 10만 번으로 다시 읽는다. SCIM 재적재에서는 **락 안** 50~60초 | 코드로 셈 | M13 |
| GSI1 `USER_INDEX` 쏠림(문서화된 한계) | SCIM `wipe` 도 락 안에서 같은 쏠림을 탄다(직원 10만 삭제 → 인덱스 삭제 각 10만). 10만 명 조직의 멤버 줄도 한 파티션으로 몰린다 | 코드로 셈 | — |
| `updatedAt` 을 `meta.lastModified` 로 내보내지 않음 | Entra 문서의 **모든** 요청·응답 예시가 `meta.created`·`meta.lastModified` 를 담는다. 그 필드를 참조하는 매핑을 걸면 빈 값을 받는다 | 문서로 확인 | — |
| 감사 로그 없음 | LDAP 개명이 삭제+생성이라 옛·새 조직을 잇는 기록이 전혀 없다(M9) | 코드로 확인 | M9 |
| `/Schemas`·`/ResourceTypes` 없음 | 없는 경로의 404 본문이 SCIM Error 가 아니다(S7). Ping·OneLogin·JumpCloud 의 커스텀 흐름은 스키마 발견을 강제하지 않는다. Entra 는 커스텀 앱에서 스키마 발견을 지원하지 않는다 | 문서로 확인 | S7 |
| IdP 는 503 을 재시도 신호로 본다(README 전제) | **Okta 의 503 재시도 여부는 문서로 확인되지 않았다.** Okta 는 412/429/503/500 반응을 문서화하지 않고, 실패 작업의 Tasks 큐와 수동 "Retry Selected" 만 문서화한다. Entra 는 "개별 오류는 다음 주기(약 40분)에 재시도" | 미확인 | M4, S2 |
| 잠김(lockout) 계정은 읽지 않는다(설계 `2026-09-25-ldap-disabled-account-design.md` §6 에서 의도적으로 뺌) | authentik(LOCKOUT 비트)·ConnId AD(`lockoutTime`)는 잠금을 읽는다. Keycloak 은 안 읽는다. Microsoft 문서는 `userAccountControl` 의 LOCKOUT 비트가 2003 이후 신뢰할 수 없고 `lockoutTime`/계산 속성으로 봐야 한다고 적는다. OpenLDAP 영구 잠금 특수값은 일시 잠금이 아니다(S24) | 코드·문서로 확인 | S24 |
| `accountExpires` 를 읽는다 | 비교한 셋(Keycloak·authentik·ConnId) 어디에도 없는, 우리만의 추가 커버리지다 | 코드로 확인 | — |
| `manager` 는 저장하지 않는다(매핑에서 빼라) | 10만 명이면 빼지 않을 때 격리 상한까지 간다(M18) | 추정 | M18 |
| 저장 안 하는 속성의 path 연산은 400(매핑에서 빼라) | 같은 요청의 **비활성화까지** 막힌다(M5) | 코드 + 문서 | M5 |
| `employeeNumber` 의 path(400)/path 없음(조용히 버림) 비대칭 | 새 근거 없음 — 재확인만 했다 | — | — |
| Entra 이메일 매칭은 400 | JumpCloud 재연결(이메일 조회), Ping 관리자 필터(`co`)도 같은 400 이다(S12) | 문서로 확인 | S12 |
| AD `primaryGroupID` 는 못 본다 | 어떤 그룹이든 기본 그룹으로 지정하면 `member` 에서 빠진다 — 평범한 관리 조작이 권한을 지운다(M10) | 문서로 확인 | M10 |
| 식별 속성 없는 엔트리 → 회차 실패(README 275~277 결정) | 10만 명에서는 엔트리 하나가 전사 퇴사 반영을 멈춘다(M11) | 코드로 확인 | M11 |
| 재적재 중 SCIM 쓰기는 503(README) | 10만 명에서 그 시간이 약 2~6분(추정). OpenFGA 장애 때는 무기한(C7). 스냅샷 저장까지 괜히 락 안(P8). Entra 초기 동기화 창과 겹치면 실패율이 순간 치솟을 수 있어, 최초 연동 직후 재적재 금지를 운영 절차로 둘 만하다 | 코드로 셈 | C7, P8 |
| 재적재 중 인가 공백(README·설계 §8.2) | 읽기가 실패하면 공백이 무기한이고, 순서만 바꾸면 사라진다(M13) | 코드로 확인 | M13 |
| ETag 를 쓰지 않는다 | AWS 는 자기 `ServiceProviderConfig` 에 `etag.supported=false` 를 명문화한다 — Entra·Okta 가 `If-Match` 를 보내는지에 대한 직접 답은 아니지만 보강 근거 | 문서로 확인 | — |
| 필터 없는 목록 첫 페이지의 전원 COUNT | 새 근거 없음(Okta 가져오기당 한 번) | — | — |
| (정책 확인거리) 이름 없는 사용자 | AWS 는 `givenName`·`familyName`·`userName`·`displayName` 중 하나라도 없으면 그 사용자를 조용히 건너뛴다. 우리 POST 는 `userName` 만 필수다 — 이름 없는 직원을 받는 것이 맞는지 정책 판단거리(결함 아님) | 문서로 확인 | — |

---

## 6. 확인함 — 문제없음

리뷰가 단서를 확인해 보니 괜찮았던 것이다. 다음에 같은 것을 다시 뒤지지 않게 적는다.

**SCIM 쓰기**
- 조직이 먼저·직원이 나중에 와도, 부모가 먼저·자식이 나중에 와도 튜플이 맞게 생긴다.
- 비활성 직원은 멤버 줄만 남기고, 재활성화 때 모든 소속에 튜플을 복원한다. 동시 요청은 락 안에서 판단한다(경합 E2E 시나리오 6).
- 개명은 아이디·튜플을 그대로 두고, `userName` 이 실제로 바뀔 때만 중복을 확인한다.
- 자식이 있는 조직을 지우면 자식의 child 튜플과 소속 줄도 지운다. 직원 삭제는 파티션 전체를 지워 새 레코드가 옛 소속을 물려받지 않는다(튜플 쪽 고아는 M7).
- 부분 실패 뒤 커밋은 반영된 만큼만 한다. OpenFGA 는 성공하고 DynamoDB 커밋이 실패해도 재시도의 Check 가 빈 델타로 커밋만 한다.
- OpenFGA 쓰기는 늘 "있으면·없으면 무시"(v1.10+) 옵션이라 배치가 통째로 실패하지 않는다. 배치 100 = 서버 기본, BatchCheck 50 = 서버 기본. 삭제 배치가 먼저다.
- BatchCheck 응답의 개별 오류·누락·중복 id 는 폴백 없이 실패로 멈춘다.
- 락 반납·갱신은 토큰 조건이라 남의 락을 건드리지 않는다. 획득 중 DynamoDB 장애도 503. 쓰기 직전 리스를 잃으면 아무것도 쓰지 않고 503.
- 판단 읽기는 전부 락 안이다(예외는 설계대로 `type` 판정뿐 — P1).
- DELETE 와 `active=false` 둘 다 권한을 없앤다. 비활성은 멤버십을 유지한다(Entra 요구).
- 응답 코드·본문: POST 201, PUT 200, 직원 PATCH 200, 조직 PATCH 204(`attributes` 있으면 200), DELETE 204, 없는 리소스 404, 중복 409, 모든 오류가 `application/scim+json` SCIM Error. Okta 의 "빈 본문 = 무효"와 충돌 없음.
- 순환 검사: 두 홉 이상은 저장소를 내려가 막고, 자기 자신 엣지도 막고, 예산을 넘으면 추측하지 않고 실패한다(남은 것은 M1·S4·P2).
- 삭제는 "멤버 줄 먼저, 소속 줄 나중" 순서라 중간 실패의 잔여물이 안전한 방향뿐이다.

**SCIM 요청 해석·조회**
- PATCH `op` 대소문자(`Replace`/`Add`/`Remove`)를 무시한다. 문자열 불리언 `"False"`/`"false"` 를 받는다(PATCH·POST·PUT 모두).
- path 없는 값 객체의 점 표기 키·코어 URN 키를 path 와 같은 해석기로 푼다.
- `active eq "false"`(따옴표)는 400 — RFC 의 JSON 리터럴 규칙대로다.
- `count` 100 초과 → 100, 음수 → 0, `count=0` → `totalResults` 만. RFC 7644 §3.4.2.4 그대로.
- 필터 있는 조회의 `startIndex` 는 인덱스로 찾은 몇 건을 메모리에서 자른다 — 전원 스캔이 아니다.
- 필터 없는 목록은 책갈피(강한 일관성, 15분)로 페이지당 `count` 건만 읽는다.
- `totalResults`·`itemsPerPage`·`startIndex` 가 정수로 나간다. 0건이면 `Resources: []`.
- Okta 의 "파라미터 없으면 멤버 전체"와 Entra 의 `excludedAttributes=members` 를 한 코드로 만족한다(비용은 P5).
- 모르는 스키마 URN(Entra 의 ADSCIM URN)·모르는 속성(`password`·`groups`·`locale`)을 받아 버린다.
- `externalId` 는 대소문자를 가린다(RFC `caseExact=true`). 단건 GET 은 강한 일관성.
- 필터 문법 오류와 모르는 속성은 둘 다 `invalidFilter` 지만 메시지가 다르다.
- 필터 연산자·속성 이름의 대소문자, 코어 URN 접두를 무시한다.
- 라우트 안의 오류 본문은 SCIM Error 모양이다(`schemas`, 문자열 `status`, `scimType`, `detail`).
- `emails[type eq "work"].value` replace 인데 이메일이 없으면 400 `noTarget` — RFC 7644 §3.5.2.3 대로다.
- `?aadOptscim062020` 쿼리 파라미터는 모르는 값이라 무시된다.
- Entra 연결 테스트의 0건 조회가 200 + 빈 목록이다 — 404 가 아니라 격리 사유가 안 된다.

**LDAP**
- 비활성·만료 판정: `userAccountControl` 은 비트로, `accountExpires` 는 `0`·최대값을 "만료 없음"으로. 값이 정수가 아니면 DN 을 싣고 재시도 없이 실패.
- 시간 한도 초과·크기 한도 초과는 예외로 올라와 조용한 부분 결과가 되지 않는다.
- 페이징은 쿠키를 한 커넥션에 묶는다. page-size 500 < AD 기본 1,000.
- 범위 검색(range retrieval)은 한 커넥션에서 이어받고, 완료 신호는 `*` 뿐, 못 읽으면 예외. 최대 1,000조각이면 한 그룹 10만 명도 67조각(실제 AD 검증은 알려진 한계).
- 삭제된 객체(tombstone)는 스냅샷 diff 라 일반 검색에서 빠지는 것으로 충분하다.
- 중첩은 child 튜플로 롤업, 순환은 조직코드 사전순으로 back edge 만 결정적으로 버린다(README 가 `cycle: true` 로 안내).
- 한 인스턴스 안에서 스케줄러·수동 full·rebuild 는 같은 실행 가드로 겹치지 않는다(예외: C5 의 끝나지 않는 회차, C3 의 취소).
- 스냅샷 저장 뒤·상태 저장 전 끊김은 다음 회차가 메운다. 스냅샷은 튜플 → 메타 → 포인터 순이라 반쪽을 읽지 않는다(OpenFGA 뒤·스냅샷 앞은 M2).
- 예외가 나는 부분 읽기(크기 한도·범위 미완·시간 한도·필수 속성·DN 해석·전부 불일치)는 아무것도 쓰지 않고 끝난다.
- 낡은 DC: 복제 지연으로 생기는 어긋남은 그 회차만 빠지고 다음 회차에 수렴한다.
- 글로벌 카탈로그: 우리는 `memberOf` 가 아니라 그룹의 `member` 를 읽는다(GC 포트로 붙이지 않도록 README 에 한 줄 권고할 만하다).
- DN 대조는 표준 파서(`LdapName`)로 이스케이프·다중값 RDN·대소문자를 흡수한다. 범위 이어받기는 서버가 준 DN 으로 색인한다.
- 재시도 분류: 데이터 오류는 재시도하지 않고 범위 미완은 재시도한다(예외는 S23).
- ABORTED 는 OpenFGA·스냅샷·상태를 건드리지 않는다.
- `MaxConnIdleTime`(900초): 검색마다 짧게 연결을 열고 닫아 걸리지 않는다.

**재적재·저장소·인가**
- SDK 가 쓰기 묶음을 더 쪼개거나 합치지 않는다(한 호출 = 한 요청).
- BatchWrite 25개 한도, 미처리 항목은 지수 백오프 최대 5회 뒤 실패로 올린다(조용히 버리지 않는다). BatchGet 100개 한도.
- 모든 전체 읽기가 Query 1MB 잘림을 따라간다. 400KB 아이템 한도에 걸리는 곳이 없다.
- 락 아이템이 TTL 로 지워져도 안전하다. 락 아이템 한 줄에 쓰기가 몰려도 파티션 한도에 한참 못 미친다.
- wipe 는 락·이력·스냅샷을 지우지 않고, OpenFGA 를 먼저 비운다. wipe 확인값은 테이블명과 정확히 같아야 한다.
- 스냅샷 정렬키의 구분자 문자는 정규화가 `_` 로 바꾼다. 스냅샷 id 는 밀리초까지라 충돌하지 않는다.
- 관리 검색 커서는 인덱스+파티션 스코프를 담아 다른 검색으로 새지 않는다. 빈 접두사 400, limit 1~100.
- 직원 상세는 소속 줄만 키 조건으로 읽는다(큰 조직 파티션을 훑지 않는다). 경로 200개 상한.
- Check 실패는 드리프트가 아니라 "보류"로 센다. SyncRun 은 월 파티션, 30일 TTL.
- 재적재 동시 실행은 전역 락을 재시도 없이 잡아 409. LDAP `mode=snapshot` 의 **실패** 경로는 남은 튜플만 담은 스냅샷을 저장한다(취소·크래시는 C3).
- store 목록은 끝까지 넘기고, 같은 이름이 둘이면 멈춘다. 인가 모델은 최신을 쓴다.

---

## 7. 권고 순서

권고 슬라이드 여섯이다. M1·M6·M7·M8·M16 은 고치는 곳이 겹치는 슬라이드에 넣었다. 사소는 같은 코드를 만지는 슬라이드에 붙였다.

| 순서 | 슬라이드 | 담는 발견 | 왜 이 순서인가 | 크기 |
|---|---|---|---|---|
| 1 | **권한 정합성 급한 불(작은 수정 묶음)** | C1, C4, C8, C5 (+ S18, S26) | 권한이 **조용히** 틀리는 것 가운데 고치기가 작은 것부터. 각각 한 줄~작은 수정이다 | 작음 × 4 |
| 2 | **재적재·동기화를 백그라운드 작업으로 + store 재생성 없애기** | C3, C2, C7, M13, M14, M15, M17, M2, M6, M16 (+ S13, S17) | 재적재·store 관리가 한 뿌리다 — 요청 수명, storeId 공유, 클러스터 락, "읽고 나서 비우기" 순서, 전체 기한이 같은 코드를 만진다. 따로 고치면 두 번 뜯는다 | 큼 |
| 3 | **큰 변경과 락** | C6, P1, P2, P8, M8, M1 (+ S3, S4, S14, S15, S28) + 알려진 백로그(리스 갱신, BatchGet 재시도) | 10만 명에서 락 기아를 만드는 것들이다. 리스 갱신·나눠 처리·`type` 일괄 판정·순환 검사가 같은 흐름(`IncrementalSyncUseCase`)에 있다 | 중간~큼 |
| 4 | **식별자와 LDAP 필터** | M9, M7, M12, M10, M11, P7 (+ S20~S25) | 불변 id(LDAP `objectGUID`/`entryUUID`, SCIM 서버 발급 id)는 저장 형식을 바꾼다 — 운영 배포 전(초기화 가능)일 때 해야 싸다. 반환 속성 지정은 `entryUUID` 명시 요청과 같은 자리 | 중간 |
| 5 | **SCIM 오류 신호** | M4, M3, M5, M18 (+ S1, S2, S5~S12) | IdP 가 재시도할지 포기할지를 정하는 신호다. 503 + `Retry-After`, 413, 매핑 안내. 대부분 번역 한 곳과 README | 작음~중간 |
| 6 | **나머지 성능** | P3, P4, P5, P6 (+ S16, S19, S27) | 동작은 하지만 10만 명에서 느리거나 메모리를 크게 쓴다. 앞 슬라이드가 락·재적재 구조를 바꾼 뒤 재는 것이 맞다 | 중간 |

그다음 기존 백로그 — 튜플 스냅샷 쏠림, `loadAll` 직원당 GetItem, `updatedAt` 내보내기, 감사 로그. **인증은 마지막**이다. 인증이 끝나면 실제 IdP·AD 로 이 문서의 추정들을 확인한다(§8).

---

## 8. 이 점검이 말할 수 없는 것

- **실제 IdP·AD 로 확인하지 못했다.** 인증이 없어 붙일 수 없다. IdP 동작은 전부 문서 기준이고, 문서끼리 어긋나는 곳(Okta 의 OIN/AIW 앱별 PUT·PATCH, Entra 매칭 속성)은 그대로 남겼다.
  Entra 의 PATCH 한 번의 멤버 수, IdP 들의 동시 요청 수·요청 타임아웃, Okta 의 503 반응은 모른다 — P1·P3 의 심각도는 이 값들에 달려 있다.
- **시간은 DynamoDB Local 기준이다.** AWS 에서는 다르다. 호출 수만 믿을 수 있다. 10만 명 규모 LDAP 전체 동기화 시간은 측정이 없다.
- **OpenFGA 는 메모리 엔진(v1.10.2)으로 재현했다.** Postgres 엔진에서는 C2 가 "조용히 틀림" 대신 "전부 실패"일 수 있다. 어느 쪽이든 결함이다.
- **재적재 취소(C3)는 유스케이스 수준 재현이다.** "HTTP 끊김 → 구독 취소"는 WebFlux 표준 동작이고 E2E 로 재지는 않았다. app-ldap 쪽은 같은 구조임을 코드로만 확인했다.
- **측정하지 않은 성능 발견(P2~P8, C7)은 코드로 센 추정이다.** 시간 환산은 GetItem 약 5ms 같은 가정 위에 있다.
- **규모 측정 픽스처는 실제보다 쓰기가 적다.** 10만 명 조직의 멤버는 직원 레코드·튜플 없이 멤버 줄만 있다. C6 의 실제 비용에는 OpenFGA 쓰기와 직원 쪽 삭제가 더 붙는다.
- **메모리 수치는 객체 크기를 어림한 값이다.** 힙 덤프로 확인하지 않았고, 저장소에 힙 설정(JVM 옵션·Dockerfile)이 없어 "몇 개 동시 요청에서 넘치는가"는 모른다.
- **T(전체 튜플 수)는 가정이다.** 약 11만으로 잡았다. AD 보안·배포 그룹이 검색 범위에 섞이면 100만~500만이 되어 O(T) 비용이 10~50배가 된다.
- **오픈소스 비교는 의심 지점만 봤다.** 커밋을 고정해 읽었고 최신과 다를 수 있다. "못 찾음"은 부재 증명이 아니다.

### 재료끼리 어긋난 곳과 이 문서가 고른 쪽

| 어긋난 곳 | 이 문서가 고른 쪽 |
|---|---|
| JSON 본문 속성 이름 대소문자: IdP 조사는 "이미 무시한다", 해석 리뷰는 "구분한다" | 재현 결과(구분한다, S6). IdP 조사가 본 것은 필터·PATCH 경로 쪽이다 |
| AWS `PatchGroup` 멤버 상한: IdP 조사는 "요청당 최대 100건", 오픈소스 비교는 "한 요청에 멤버 하나만" | IdP 조사의 100건(AWS 문서 인용). 확인 필요 |
| 10만 명 SCIM 재적재 락 보유: SCIM 비용표 4~6분(T=15만 가정), 배치 비용표 2~4분(T=11만 가정) | 둘 다 추정이라 "약 2~6분"으로 적었다 |
| 10만 명 조직 파티션 크기: 7.5MB·8쪽(줄당 75B) 대 13MB·13쪽(줄당 130B) | 범위로 적었다(약 7~13쪽) |
| `type` 판정 시간(1,000명): 재료는 6~16초(GetItem 3~8ms 가정) | 재료 값을 썼다 |
| 심각도: L-2 치명 → M2 중, R-7 중·W-1 치명 → C8 치명(조건부) | 통합 목록을 따랐다. 이유는 각 발견에 적었다 |
| C5 오픈소스: Keycloak "5초" | 연결 타임아웃만 5초이고 읽기 타임아웃은 기본이 없다고 정확히 적었다 |

---

## 출처

**재료(이 점검의 작업 파일, 저장소에는 들어가지 않는다):** `.superpowers/audit-2026-09-28/` 의 `context.md`, `progress.md`, IdP 카탈로그 셋, 비용표 둘, 결함 리뷰 넷, 오픈소스 비교 둘.

**IdP 공식 문서(대표)**
- Entra: [Develop a SCIM endpoint](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups),
  [SCIM 2.0 compliance issues(aadOptscim062020)](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-config-problem-scim-compatibility),
  [How provisioning works](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works),
  [Quarantine status](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-quarantine-status),
  [When will provisioning finish](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-when-will-provisioning-finish-specific-user)
- Okta: [SCIM 2.0 implementation](https://developer.okta.com/docs/api/openapi/okta-scim/guides/scim-20), [SCIM FAQ](https://developer.okta.com/docs/concepts/scim/faqs/),
  [Troubleshoot provisioning](https://help.okta.com/en-us/content/topics/provisioning/lcm/troubleshooting.htm), 커뮤니티 [devforum 1,000명/PATCH](https://devforum.okta.com/t/oin-scim-app-max-memberships-in-group-patch-api/34187)
- AWS: [PatchGroup](https://docs.aws.amazon.com/singlesignon/latest/developerguide/patchgroup.html), [Quotas](https://docs.aws.amazon.com/singlesignon/latest/userguide/limits.html),
  [ServiceProviderConfig](https://docs.aws.amazon.com/singlesignon/latest/developerguide/serviceproviderconfig.html),
  [ALB attributes(유휴 타임아웃)](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-load-balancer-attributes.html)
- Auth0: [Entity Limit Policy](https://auth0.com/docs/troubleshoot/customer-support/operational-policies/entity-limit-policy),
  [Inbound SCIM for Azure AD](https://auth0.com/docs/authenticate/protocols/scim/inbound-scim-for-azure-ad-saml-connections)
- Ping·OneLogin·JumpCloud: [PingFederate SCIM Provisioner](https://docs.pingidentity.com/integrations/scim/pf_scim_connector.html),
  [OneLogin Create a SCIM Test App](https://developers.onelogin.com/scim/create-app),
  [JumpCloud Custom SCIM](https://jumpcloud.com/support/provision-and-manage-users-and-groups-in-apps-using-custom-scim-identity-management-integration)
- Keycloak: [SCIM Realm API](https://www.keycloak.org/2026/04/scim-as-experimental-feature), 커뮤니티 [keycloak-scim-outbound](https://github.com/Termindiego25/keycloak-scim-outbound)
- AD·LDAP: [MS-ADTS LDAP Policies](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-adts/3f0137a1-63df-400c-bf97-e1040f055a99),
  [MS-ADA3 primaryGroupID](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-ada3/e12954a4-6865-4432-94e6-00c310ca87c0),
  [UserAccountControl flags](https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/useraccountcontrol-manipulate-account-properties),
  [Oracle JNDI LDAP(타임아웃)](https://docs.oracle.com/javase/8/docs/technotes/guides/jndi/jndi-ldap.html), [slapo-ppolicy(5)](https://man.archlinux.org/man/core/openldap/slapo-ppolicy.5.en)
- OpenFGA: [Consistency](https://openfga.dev/docs/interacting/consistency), [Configuration](https://openfga.dev/docs/getting-started/setup-openfga/configuration)
- DynamoDB: [Partition key design](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-partition-key-design.html), [TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/howitworks-ttl.html)
- 이슈 트래커(공식 문서 아님): [spring-framework #34005](https://github.com/spring-projects/spring-framework/issues/34005), [openfga #3301](https://github.com/openfga/openfga/pull/3301)
- RFC: 7643 §2.1·§3.1, 7644 §3.3·§3.4.2.4·§3.5.2·§3.10·§3.12·§3.14, 4511, 4512 §3.4, 4514, 4530

**오픈소스(커밋 고정):** Apache SCIMple `6e3c927`, UnboundID SCIM2 `cfe7b2f`, Goldfish SCIM-SDK `4614a43`, WSO2 Charon `6b2a687`,
mitodl/keycloak-scim `eec8ecd`, Metatavu/keycloak-scim-server `c7962f6`, Keycloak·authentik·ConnId(main, 링크는 재료 파일 `oss-ldap.md`).

**이전 문서:** [`2026-09-09-idp-conformance-audit.md`](2026-09-09-idp-conformance-audit.md), [`2026-09-28-scim-write-lock-design.md`](2026-09-28-scim-write-lock-design.md) §8·§10·§11,
[`2026-09-26-group-member-patch-design.md`](2026-09-26-group-member-patch-design.md) §9·§12.
