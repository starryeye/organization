# 서버가 준 DN 으로 멤버를 대조한다 (H) — 설계

> 브랜치 `real-dn-matching` (origin/main `91df228` 에서 분기).
> 발단: [`2026-09-11-e2e-branch-review.md`](2026-09-11-e2e-branch-review.md) §4 의 H,
> [`2026-09-09-ad-range-retrieval-design.md`](2026-09-09-ad-range-retrieval-design.md) §6.

## 1. 문제 — 실제 AD 에서는 모든 그룹이 멤버 0명으로 읽힌다

`GroupOfNamesStrategy` 는 그룹의 `member` 값(서버가 준 DN)을 우리가 읽은 엔트리와 대조해 멤버십을 만든다.
그런데 대조 키로 쓰는 DN 을 **직접 조립한다.**

```java
private String dnOf(Attributes attributes, String idAttribute, String searchBase) {
    return idAttribute + "=" + required(attributes, idAttribute)
            + "," + searchBase + "," + properties.getBaseDn();
}
```

모든 엔트리가 검색 베이스 **바로 아래**에 있고 RDN 이 식별 속성이라고 가정한 것이다.
자바독은 "정확한 형태보다 일관성이 중요하다" 고 적혀 있다. `externalId` 보관에는 맞는 말이지만
**서버가 준 `member` 값과 대조하는 데에는 틀린 말이다** — 대조는 상대가 정한 형태를 따라야 한다.

| | 값 | 출처 |
|---|---|---|
| 대조 키 (`userIdByDn`, `groupIdByDn`) | `sAMAccountName=hgd,ou=users,dc=…` | `dnOf` 가 조립 |
| `member` 속성 값 | `CN=Hong Gildong,OU=Seoul,OU=Users,dc=…` | 서버가 준 절대 DN |

실제 AD 의 사용자는 조직 OU 아래에 있고 RDN 은 보통 `CN` 이다. 두 문자열은 **하나도 일치하지 않는다.**
그 결과 모든 그룹이 멤버 0명으로 읽힌다.

**지금까지 안 걸린 이유:** 테스트 픽스처가 평면 트리다. `LdifRenderer` 가 엔트리를 정확히
`아이디속성=값,검색베이스,베이스DN` 위치에 놓고 `member` 도 같은 문자열로 쓴다. 조립한 DN 이 우연히 맞는다.
**깊은 트리를 한 번도 읽어 본 적이 없다.**

### 조직 쪽에 이미 있는 진짜 DN 도 그대로는 못 쓴다

`groupMapper` 는 `ContextMapper` 라 `adapter.getDn()` 으로 서버가 준 DN 을 받아 `RawEntry.realDn` 에 담는다
(범위 검색 재요청 전용). 하지만 `LdapConfig` 가 `contextSource.setBase(baseDn)` 을 하므로 **그 DN 은 베이스
상대**다(`ou=개발팀,ou=groups`). `member` 값은 절대 DN 이라 베이스를 붙이지 않으면 역시 안 맞는다.

### 영향 범위

`GroupOfNamesStrategy` 하나다. `DitStrategy` 는 처음부터 `ContextMapper` 로 서버 DN 만 쓰고 부모도 DN 경로로
찾으므로 이 결함이 없다(확인함).

## 2. 목표와 비목표

**목표**

- 멤버 대조를 **서버가 준 DN** 으로 한다. DN 을 짐작하는 코드를 없앤다.
- 깊은 트리(실제 AD 모양)에서 멤버가 제대로 읽히는 것을 테스트로 못박는다.
- 대조가 **전부** 실패하는 상황을 조용히 넘기지 않는다.

**비목표**

- 실제 AD 서버로의 검증 — 인증 슬라이드가 선행 조건이다(§9).
- 범위 검색 이어받기의 "알아보지 못한 응답을 완료로 읽는" 구멍 — 다음 슬라이드(④)다.
- `primaryGroupID` 로만 표현되는 소속 — 별건으로 남는다.
- 기존 데이터 호환 — **배포 전이라 초기화한다**(사용자 확인, 2026-09-16).

## 3. 접근안 — 왜 다른 길을 안 갔나

| | 내용 | 판단 |
|---|---|---|
| **A. 양쪽 모두 서버가 준 DN 을 쓴다 — 채택** | 사용자 매퍼도 `ContextMapper` 로 바꾸고, 베이스를 붙여 절대 DN 을 만들어 대조 키와 `externalId` 로 쓴다. `dnOf` 는 지운다 | DN 을 짐작하는 코드가 사라진다. "조립한 DN" 과 "서버가 준 DN" 이 공존하던 혼란도 없어진다(리뷰 §5 가 함정이라고 지적한 자리) |
| B. `externalId` 는 조립한 DN 유지, 대조만 진짜 DN | 사용자 쪽에도 `realDn` 칸 추가 | 두 DN 개념이 계속 공존한다. 이름으로 구별되지 않아 다음 사람이 또 틀린 쪽을 쓴다 |
| C. `member` 값마다 서버에 되물어 신원 확인 | DN 으로 엔트리 조회 | 멤버 수만큼 왕복이 는다. 5,000명 규모에서 수천 번이다 |

**`externalId` 가 바뀌는 것을 이번에 받아들인다.** AD 설계 문서 §6 은 "진짜 DN 이 더 정확하지만 저장값이
전부 바뀌는 데이터 변경이라 별건" 으로 미뤄 뒀다. 초기화가 가능해지면서 그 부담이 사라졌다.

## 4. 바꾸는 것

| 대상 | 바뀌는 내용 |
|---|---|
| `userMapper` | `AttributesMapper` → `ContextMapper`. 서버가 준 DN 을 받는다 |
| `RawEntry` | `dn` 과 `realDn` 두 칸을 **하나로 합친다.** 값은 절대 DN |
| 대조 키 | `normalizeDn(조립한 DN)` → 정규화한 **절대 서버 DN**(§5) |
| `externalId` | 절대 서버 DN (사용자·조직 모두) |
| `dnOf` | **삭제** |
| 범위 검색 재요청 | 상대 DN 이 필요하므로 절대 DN 에서 베이스를 떼어 넘긴다(§5) |
| `DuplicateIdGuard` 신고에 실리는 DN | 진짜 DN 이 된다 — 로그가 정확해진다 |

## 5. DN 비교 규칙

지금 `normalizeDn` 은 소문자로 바꾸고 `", "` 를 `","` 로 줄이는 문자열 다듬기다. 평면 픽스처에는 충분했지만
실제 AD 의 DN 은 이렇게 흔들린다.

| 흔들림 | 예 |
|---|---|
| 속성 이름 대소문자 | `CN=` vs `cn=` |
| 쉼표·등호 주변 공백 | `OU=Seoul, DC=example` |
| 이스케이프 | `CN=Hong\, Gildong` |
| 다중값 RDN | `CN=hgd+OU=Seoul` |

문자열 다듬기로는 뒤의 둘을 다룰 수 없다. **표준 파서(`javax.naming.ldap.LdapName`)로 파싱해 정규화한 키를
만드는 도우미**를 `connector-ldap` 에 두고, 그 도우미가 세 가지를 맡는다.

1. **대조 키 만들기** — 파싱한 뒤 RDN 의 속성명과 값을 정규화해 비교 가능한 문자열로.
2. **절대 DN 만들기** — 서버가 준 상대 DN + 베이스. 상대 DN 이 비었으면(엔트리가 베이스 자신) 베이스만,
   베이스가 비었으면 상대 DN 그대로.
3. **상대 DN 되돌리기** — 절대 DN 에서 베이스를 떼어 범위 검색 재요청에 넘긴다.

**파싱 실패는 건너뛰지 않고 예외로 알린다 — `member` 값도 마찬가지다.** 대조할 DN 은 전부 서버가 준
것이므로 문법상 올바른 DN 이다. 파싱에 실패했다면 값이 이상한 것이 아니라 **우리가 DN 을 다루는 방식이
틀린 것**이고, 그때 문자열 비교로 물러나면 이 슬라이드가 고치는 문제가 다른 모양으로 돌아온다.
이것은 §6 의 "알아보지 못한 멤버는 경고 후 건너뛴다" 와 다른 사안이다 — 그쪽은 **파싱은 되지만 우리가 읽지
않은 엔트리**를 가리킨 경우다.

## 6. 대조 실패 처리

**부분 불일치는 지금처럼 경고만 남기고 건너뛴다.** 실제 AD 의 그룹에는 연락처·컴퓨터 계정처럼 우리가 읽지
않는 엔트리가 섞여 있다. 정상 상황이다.

**가드가 지켜보는 것은 사람 쪽 대조 하나다.** 조직이 하나라도 있고 `member` 값이 하나 이상 있는데
**사람으로 대조된 것이 단 하나도** 없으면 그 회차를 실패시킨다 — 그룹(하위 조직)으로 대조된 것이
있어도 마찬가지다. H 는 처음부터 **사용자 매퍼만의 결함**이었다: 그룹 매퍼는 그때도 `ContextMapper` 로
서버가 준 DN 을 그대로 썼으므로 하위 조직을 가리키는 `member` 값은 한 번도 어긋난 적이 없다. 그래서
사람·그룹 대조를 하나로 합쳐 "대조된 것이 하나라도 있으면 통과" 로 판단하면, 그룹이 검색 베이스 바로
아래 평평하게 있고 사용자만 조직 OU 아래 깊이 있는 트리에서는 하위 조직을 가리키는 값만 전부 맞고
사람을 가리키는 값은 전부 어긋나는데도 가드가 조용히 지나간다 — 모든 그룹이 하위 조직만 안은 채 사람은
한 명도 없이 적재되고 동기화는 성공으로 끝난다. 결말은 "권한 0" 으로 H 와 같으니, 가드는 사람 대조수만
따로 세어 그것이 0 인지를 본다.

| 상황 | 방어가 없을 때 |
|---|---|
| 첫 적재(기준선 없음) | 삭제할 것이 없어 삭제 가드가 안 걸린다. 모든 그룹이 멤버 0명으로 **조용히 적재**되고 아무도 권한을 못 받는다 |
| 이미 적재된 뒤 DN 형태가 바뀜 | 삭제 가드가 중단시킬 가능성이 높지만 메시지는 "임계치 초과" 라 **원인을 말해 주지 않는다** |

### 읽는 사람이 "이게 왜 필요하지?" 라고 묻지 않도록

방어 코드는 이유가 보이지 않으면 지워진다. 그래서 네 가지를 함께 둔다.

1. **이름 있는 가드 클래스로 만든다.** 이 커넥터에는 이미 `DuplicateIdGuard` 가 있다. 새 가드도 그 옆에 두어
   전략 한가운데의 낯선 `if` 가 아니라 **이 프로젝트가 쓰는 개념 하나**로 읽히게 한다.
2. **자바독이 증상과 사건을 적는다** — "H(2026-09-11 코드리뷰): 사용자 DN 형태가 `member` 값과 어긋나 모든
   그룹이 멤버 0명으로 읽혔다. 첫 적재라면 삭제 가드도 걸리지 않아 아무도 권한을 못 받은 채 성공으로 끝난다."
   이 스펙 경로를 함께 적는다.
3. **예외 메시지가 운영자에게 할 일을 말한다** — 로그를 읽는 사람은 코드를 보지 않는다. "사용자 검색 베이스와
   그룹 `member` 값의 DN 형태를 확인하라" 까지 문장에 넣는다.
4. **테스트 이름이 같은 문장을 말한다** — `DN 형태가 어긋나 멤버가 하나도 대조되지 않으면 그 회차를 실패시킨다`.
   가드를 지우려는 사람이 이 테스트를 먼저 만난다.

**알면서 받아들이는 오탐 두 가지:**

1. 사용자 검색 베이스를 좁게 잡아 그룹 멤버가 **전부 범위 밖**인 설정은 고장이 아닌데도 막힌다. 그 상태의
   결과가 "권한 0" 이라 어차피 사람이 봐야 하므로 받아들인다.
2. (2026-09-24 리뷰로 넓어진 범위) **어느 그룹에도 사람이 직접 속하지 않고, 모든 소속이 하위 조직을
   거쳐서만 표현되는** 디렉터리도 막힌다. 이 판단은 사람 대조수만 보므로, 그런 디렉터리라면 실제로는
   DN 형태가 멀쩡해도 걸린다. 이 경우도 결과는 "권한 0" 과 같아 어차피 사람이 봐야 하므로 받아들인다.

두 경우 모두 설정 스위치는 두지 않는다 — 지금 필요하다는 근거가 없다.

## 7. 검증

### 핵심 증명 — 깊은 트리 픽스처

지금 테스트는 전부 평면 트리라 이 버그를 못 잡는다. 임베디드 LDAP 에 실제 AD 모양의 작은 트리를 심는 테스트를
새로 만든다.

```
dc=example,dc=com
├─ ou=users
│  └─ ou=Seoul
│     └─ cn=Hong Gildong        ← 식별 속성은 uid, RDN 은 cn
└─ ou=groups
   └─ cn=dev   (member: cn=Hong Gildong,ou=Seoul,ou=users,dc=example,dc=com)
```

- **고치기 전:** 그룹 `dev` 의 멤버가 0명. 이것이 RED 다.
- **고친 뒤:** 멤버 1명.

### 그 밖의 테스트

| 테스트 | 확인 |
|---|---|
| DN 도우미 단위 테스트 | §5 의 네 가지 흔들림, 절대 DN 만들기, 상대 DN 되돌리기, 빈 베이스, 파싱 실패 시 예외 |
| 전부 불일치 가드 | 아무 멤버도 대조되지 않는 픽스처에서 예외. 부분 불일치는 경고만 |
| 기존 회귀 | 평면 픽스처 기반 테스트 전부(전략·중복 아이디·두 전략 동형성·페이징) |
| `externalId` 단언 세 곳 | `GroupOfNamesStrategyTest:101`, `GroupOfNamesDuplicateIdTest:143`, `LdifRendererTest:118-126` — 값이 바뀌면 진짜 DN 기준으로 고친다 |

### 변이로 확인

| 변이 | 실패해야 하는 테스트 |
|---|---|
| 대조 키를 다시 조립한 DN 으로 되돌린다 | 깊은 트리 |
| 절대 DN 을 만들 때 베이스를 안 붙인다 | 깊은 트리 |
| 전부 불일치 가드를 뺀다 | 가드 테스트 |
| DN 파싱 실패를 조용히 넘긴다 | 도우미 단위 테스트 |

### 규모 테스트

평면 픽스처라 동작은 그대로여야 한다. 머지 전에 `./gradlew test` 와 `./gradlew scaleTest` 를 둘 다 돌린다.

## 8. 범위 밖

- ④ 범위 검색 이어받기에서 알아보지 못한 응답을 "완료" 로 읽는 구멍.
- `primaryGroupID` 로만 표현되는 소속.
- 인증(S-2) — 실제 AD 검증의 선행 조건.
- `DitStrategy` 자신의 문자열 기반 DN 처리(`parentDn`, `normalize`) — 이쪽도 이스케이프된 쉼표가 든
  OU 의 RDN 에서는 부모 조회가 깨지는 같은 종류의 약점을 안고 있다(2026-09-24 종합 리뷰에서 확인).
  `GroupOfNamesStrategy` 처럼 지금 고치지 않고, 뒤이은 정리 슬라이드로 미룬다.

## 9. 이 설계가 말할 수 없는 것

- **실제 AD 로는 여전히 검증하지 못한다.** 임베디드 UnboundID 서버에 AD 모양을 흉내 낸 트리까지가 한계다.
  실제 AD 의 DN 표기(이스케이프, 정규화 규칙)가 그 흉내와 다를 여지가 남는다.
- **`externalId` 가 바뀌므로 기존 테이블은 재생성해야 한다.** 초기화를 전제로 한 결정이다.
- 전부 불일치 가드는 "전부" 일 때만 걸린다. **부분 불일치는 여전히 경고로만 흐른다** — 임계값을 두는 것은
  임의적이라 하지 않았다.

## 10. 구현 후 기록

변이 네 가지를 실제 소스에 넣고 `./gradlew :connector-ldap:test` 를 돌린 뒤 되돌렸다. 결과:

| # | 변이 | 실패한 테스트 | 메시지 첫 줄 |
|---|---|---|---|
| 1 | `절대DN(adapter)` 을 `adapter.getDn().toString()` 으로 바꾼다(베이스를 안 붙인다) | `GroupOfNamesDeepTreeTest.깊은_트리의_사용자가_멤버로_대조된다`, `GroupOfNamesDeepTreeTest.externalId가_서버가_준_DN이다` (그 밖에 `PagingUnderServerSizeLimitTest`, `TwoStrategiesSameShapeTest`, `LdifRendererTest` 등 17개도 함께 실패했다 — 대조가 전멸해 Task 3 의 전부 불일치 가드까지 걸렸다) | `dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException: 조직 1개의 member 값 1개가 하나도 대조되지 않았습니다. 사용자 검색 베이스와 그룹 member 값의 DN 형태가 어긋났는지 확인하십시오.` |
| 2 | `LdapDns.대조키` 의 구현을 `dn.toLowerCase(Locale.ROOT).replace(", ", ",").trim()` 로 되돌린다 | `LdapDnsTest.이스케이프된_쉼표를_값으로_다룬다`, `LdapDnsTest.다중값_RDN의_순서를_흡수한다` (예상 밖으로 `LdapDnsTest.해석할_수_없는_DN은_예외다` 도 함께 실패했다 — 이 테스트가 예외를 일으키는 통로로 `대조키` 를 쓰기 때문이다) | 첫 번째: `org.opentest4j.AssertionFailedError: [백슬래시 이스케이프와 따옴표 표기법은 같은 엔트리] \nexpected: "cn="hong,gildong",ou=seoul,dc=example,dc=com"` / 두 번째: `org.opentest4j.AssertionFailedError: \nexpected: "ou=seoul+cn=hgd,dc=example,dc=com"` / 세 번째(예상 밖): `java.lang.AssertionError: \nExpecting code to raise a throwable.` |
| 3 | `UnmatchedMemberGuard.확인한다(...)` 호출을 지운다 | `GroupOfNamesUnmatchedMemberTest.전부_대조되지_않으면_실패시킨다`(`@DisplayName`: "DN 형태가 어긋나 멤버가 하나도 대조되지 않으면 그 회차를 실패시킨다") | `java.lang.AssertionError: \nExpecting code to raise a throwable.` |
| 4 | `LdapDns.파싱한다` 의 예외를 `return new LdapName("")` 로 바꿔 조용히 넘긴다 | `LdapDnsTest.해석할_수_없는_DN은_예외다` | `java.lang.AssertionError: \nExpecting code to raise a throwable.` |

네 변이 모두 되돌린 뒤 `./gradlew :connector-ldap:test` 를 다시 돌려 전부 통과(BUILD SUCCESSFUL)를 확인했다.

**우리 테스트가 못 잡는 것.** 전략의 `GroupOfNamesStrategy` 안에서 `LdapDns.대조키(...)` **호출부 세 곳만**(사용자 색인, 그룹 색인, member 대조) `dn.toLowerCase(Locale.ROOT).replace(", ", ",").trim()` 인라인 문자열 다듬기로 바꾸고 `LdapDns` 자체는 그대로 둔 뒤 돌려 봤다 — `BUILD SUCCESSFUL`, 실패한 테스트가 하나도 없었다. `GroupOfNamesDeepTreeTest` 를 포함한 깊은 트리 픽스처의 DN 차이가 대소문자와 쉼표 뒤 공백뿐이라, 문자열 다듬기로도 우연히 같은 키가 나오기 때문이다. 즉 `대조키` 가 `LdapName` 파서로 얻는 추가 능력 — 이스케이프된 쉼표를 RDN 경계로 보지 않는 것, 다중값 RDN 의 순서를 흡수하는 것 — 은 전략을 통한 통합 테스트로는 전혀 증명되지 않고, 오직 `LdapDnsTest` 의 단위 테스트만이 이를 잡아낸다. 다음에 `GroupOfNamesStrategy` 의 호출부를 건드리는 사람은 이 사실을 모르고 통합 테스트가 초록불이라는 이유로 안심할 수 있다 — 실제로는 `LdapDnsTest` 가 무너지지 않았는지를 따로 봐야 한다.
