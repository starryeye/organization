# 5,000명 규모 로컬 실측 — 절차와 결과

**작성일:** 2026-09-07
**관련:** `2026-09-06-scale-e2e-scenarios.md`

---

## 0. 왜 자동화 테스트로 충분하지 않았나

자동화 E2E 는 임베디드 UnboundID LDAP 서버를 쓴다. 빠르고 격리되지만 **실제 서버보다
관대하다.** 그 차이가 만든 결함 둘을 이 실측이 잡았고, 둘 다 자동화 테스트는 전부 통과하는
상태였다.

| 잡힌 것 | 자동화가 못 본 이유 |
|---|---|
| `groupOfNames` 는 `member` 필수 — 빈 조직이 실제 서버에 안 올라감 | 테스트가 `setSchema(null)` 로 스키마 검사를 꺼놨다 |
| paged results 쿠키가 커넥션에 묶여 있음 — **2페이지부터 실패** | UnboundID 는 다른 커넥션의 쿠키를 받아준다 |

**두 번째가 심각했다.** 한 페이지(500)를 넘는 디렉터리는 실제 OpenLDAP 에서 전체 동기화가
통째로 실패한다. 페이징 코드가 존재하는 이유가 정확히 그 상황인데도. 5,024명을 실제 서버에
올려 앱을 돌려보기 전까지는 드러나지 않았다.

**그래서 자동화 테스트도 스키마 검사를 켜도록 바꿨다.** 임베디드 서버가 실제 서버보다
관대하면, 테스트는 존재할 수 없는 형태를 검증하게 된다.

---

## 1. 절차

```bash
# 1) 5,000명 시드를 만든다 (생성물이라 저장소에 없다)
./gradlew :connector-ldap:generateScaleSeed

# 2) 인프라를 띄운다 — OpenFGA / DynamoDB Local / OpenLDAP
LDAP_SEED_DIR=./docker/ldap/scale-groupofnames docker compose up -d

# 3) 적재 확인 (직원 5024 / 조직 352 가 나와야 한다)
docker compose exec openldap ldapsearch -x -H ldap://localhost:1389 \
  -D "cn=admin,dc=example,dc=com" -w adminpassword \
  -b "ou=people,dc=example,dc=com" "(objectClass=inetOrgPerson)" dn | grep -c "^dn:"

# 4) 앱을 띄운다
./gradlew :app-ldap:bootRun

# 5) 동기화
curl -XPOST localhost:8081/admin/sync/full
```

DIT 전략으로 보려면 `LDAP_SEED_DIR=./docker/ldap/scale-dit` 에 `ldap.strategy=dit` 을 준다.

---

## 2. 실측 결과 (2026-09-07, 로컬 macOS)

**규모:** 직원 5,024 / 조직 352 / 튜플 5,541 (`direct_member` 5,190 + `child` 351)

### 동기화

| | 소요 | 결과 |
|---|---|---|
| 최초 전체 동기화 | **6.0초** | `SUCCEEDED`, written 5,541 |
| 무변경 재동기화 | **1.8초** | `SUCCEEDED`, written 0, `"변경 없음"` |

무변경이 1.8초라는 것이 중요하다 — 1년에 364번은 이 경로를 탄다. LDAP 전체 읽기와 스냅샷
기준선 읽기가 그 비용의 전부다.

### admin 조회 (5,000명이 적재된 상태)

| 엔드포인트 | 응답 |
|---|---|
| `GET /admin/employees/{id}` (6단 깊이) | 57ms |
| `GET /admin/organizations/{code}/members?limit=100` (500명 조직) | 85ms |
| `GET /admin/organizations/{code}` | 24ms |
| `GET /admin/employees?displayName=...&limit=100` | 11ms |

직원 상세가 가장 느린 것은 경로마다 Check 를 묻기 때문이다 — 6단이면 6번이다.

### 이력과 메트릭

`GET /admin/sync/runs` 가 세 회차를 순서대로 돌려준다. 실패한 회차의 메시지가
`"[LDAP: error code 2 - paged results cookie is invalid]"` 로 남아 **원인을 그대로 말해
줬다** — 이것이 이번 슬라이드에서 고친 재시도 원인 유실 수정의 효과다. 고치기 전이었다면
`"Retries exhausted: 3/3"` 만 남아 진단에 한참 걸렸을 것이다.

```
sync_duration_seconds_count{source="LDAP",status="SUCCEEDED",trigger="MANUAL"} 2
sync_duration_seconds_max{...}                                                 5.99
sync_tuples_written_total{source="LDAP"}                                       5541.0
sync_tuples_deleted_total{source="LDAP"}                                       0.0
sync_tuples_failed_total{source="LDAP"}                                        0.0
authz_drift_detected_total                                                     0.0
```

JVM 힙 사용 **233MB**.

---

## 3. 남은 것

- **SCIM 쪽 실측은 아직이다.** 자동화로는 5,376건 41.6초를 쟀지만, 실제 앱에 IdP 처럼
  요청을 쏘고 엔드포인트로 확인한 적은 없다.
- 시간 의존 결함(락 TTL)은 데이터로 재현되지 않는다. 로컬 OpenFGA 는 응답이 밀리초라
  아무리 키워도 TTL 근처에 못 간다 — 설정으로 따로 재현해야 한다(시나리오 문서 §0).
