# LDAP 비활성 계정 (⑥) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AD 가 막은 계정(비활성화 비트 또는 지난 만료일)을 LDAP 동기화가 `active = false` 로 읽어 권한 튜플이 사라지게 한다.

**Architecture:** 판정 규칙은 `connector-ldap` 의 도우미 `AdAccountStatus` 한 곳에 둔다. 두 전략(`GroupOfNamesStrategy`, `DitStrategy`)이 `Clock` 을 받아 동기화 시각을 한 번 잡고, 직원마다 이 도우미로 `active` 를 정한다. 그 뒤는 이미 있는 경로(`TupleMapper` 의 비활성 건너뛰기)가 처리한다.

**Tech Stack:** Java 17, Spring LDAP, `javax.naming.directory.Attributes`, `java.time.Clock`, JUnit 5, AssertJ, UnboundID in-memory LDAP.

**Spec:** [`docs/superpowers/specs/2026-09-25-ldap-disabled-account-design.md`](../specs/2026-09-25-ldap-disabled-account-design.md)

## Global Constraints

- 테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석. 기존 테스트의 형태를 그대로 따른다.
- Lombok 을 쓴다. 로깅은 `@Slf4j`.
- **판정 규칙(스펙 §3):** `userAccountControl` 의 `0x2` 비트가 켜졌거나 `accountExpires` 가 지금 이전(같은 시각 포함)이면 비활성. 속성이 없으면 막히지 않음. `accountExpires` 의 `0` 과 `9223372036854775807` 은 "만료 없음". 정수가 아니면 그 회차를 실패시킨다.
- **속성 이름은 설정으로 빼지 않는다**(AD 표준). **설정 스위치를 두지 않는다.**
- **검색은 바꾸지 않는다.** 속성 목록을 지정하지 않는 검색이라 두 속성은 이미 온다.
- **Gradle 규칙:** 서브에이전트는 자기 과제의 모듈 테스트만 포그라운드로 돌린다. 루트 `test`·`build`·`check`·`scaleTest` 전체는 돌리지 않는다. Gradle 을 둘 이상 동시에 돌리지 않는다.
- 커밋 트레일러: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## Review Focus

1. **여러 플래그가 더해진 값**(`66050` = 비활성 + 암호 만료 없음) — 값 비교가 아니라 비트 검사여야 한다. Task 1 이 테스트한다.
2. **`accountExpires` 의 "만료 없음" 두 값**(`0`, `9223372036854775807`) — 막힘으로 읽으면 전원이 권한을 잃는다. Task 1 이 테스트한다.
3. **만료 시각이 정확히 지금** — 막힘(스펙 §3). Task 1 이 테스트한다.
4. **막힌 직원도 그룹 멤버로는 남는다** — 멤버십까지 지우면 SCIM 과 의미가 갈린다. Task 2 가 두 전략 모두 테스트한다.
5. **다시 풀면 권한이 돌아온다** — 비활성이 일방통행이면 안 된다. Task 3 이 E2E 로 테스트한다.

## File Structure

| 파일 | 과제 | 책임 |
|---|---|---|
| `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/AdAccountStatus.java` (신규) | 1 | 판정 규칙 |
| `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/AdAccountStatusTest.java` (신규) | 1 | 규칙 단위 테스트 |
| `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java` | 2 | `Clock`, 직원 `active` |
| `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java` | 2 | `Clock`, 직원 `active` |
| `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java` | 2 | 전략에 `Clock` 빈 전달 |
| `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesAccountStatusTest.java` (신규) | 2 | groupOfNames 적용 |
| `connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitAccountStatusTest.java` (신규) | 2 | DIT 적용 |
| `connector-ldap/src/test/java/dev/starryeye/organization/ldap/AccountStatusInvalidValueTest.java` (신규) | 2 | 정수 아닌 값이면 두 전략 모두 실패 |
| `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java` | 3 | E2E 시나리오 |
| `README.md` | 3 | 운영자 안내 |

---

### Task 1: 판정 규칙 `AdAccountStatus`

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/AdAccountStatus.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/AdAccountStatusTest.java`

**Interfaces:**
- Produces: 패키지 전용 `final class AdAccountStatus`
  - `static boolean 막혔는가(Attributes attributes, Instant 지금)` — 두 신호 중 하나라도 막혔으면 `true`
  - `static final String USER_ACCOUNT_CONTROL = "userAccountControl"`, `static final String ACCOUNT_EXPIRES = "accountExpires"`
  - 정수가 아닌 값이면 `IllegalStateException`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/AdAccountStatusTest.java`:

```java
package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AD 가 계정을 막았다고 알리는 표준 신호 둘을 읽는다 (스펙 §3).
 *
 * <p>{@code accountExpires} 는 1601-01-01 UTC 부터 100나노초 단위로 센 정수다. 아래 상수는
 * 고정한 "지금"(2026-01-01T00:00:00Z) 앞뒤의 값이다.
 */
class AdAccountStatusTest {

    private static final Instant 지금 = Instant.parse("2026-01-01T00:00:00Z");
    /** 2026-01-01T00:00:00Z = (1767225600 + 11644473600) 초 × 10^7 */
    private static final String 지금의_FILETIME = "134116992000000000";
    private static final String 일초_전 = "134116991990000000";
    private static final String 일초_후 = "134116992010000000";

    @Test
    @DisplayName("두 속성이 모두 없으면 막히지 않은 것이다 — OpenLDAP 과 지금의 테스트 서버")
    void 속성이_없으면_막히지_않았다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들(), 지금)).isFalse();
    }

    @Test
    @DisplayName("보통 계정(512)은 막히지 않았다")
    void 보통_계정() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("userAccountControl", "512"), 지금)).isFalse();
    }

    @Test
    @DisplayName("비활성화 비트가 켜진 계정(514)은 막혔다")
    void 비활성화된_계정() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("userAccountControl", "514"), 지금)).isTrue();
    }

    @Test
    @DisplayName("다른 플래그와 더해진 값도 비트로 읽는다 — 66050 은 비활성 + 암호 만료 없음")
    void 플래그가_더해져도_비트로_읽는다() {
        // given — 514 와 같은지 비교하면 이 계정을 놓친다
        // when, then
        assertThat(AdAccountStatus.막혔는가(속성들("userAccountControl", "66050"), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료일이 지났으면 막혔다")
    void 만료일이_지났다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("accountExpires", 일초_전), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료 시각이 정확히 지금이면 막혔다 — 그 시각에 만료된다")
    void 만료_시각이_지금이다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("accountExpires", 지금의_FILETIME), 지금)).isTrue();
    }

    @Test
    @DisplayName("만료일이 아직 오지 않았으면 막히지 않았다")
    void 만료일이_아직이다() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("accountExpires", 일초_후), 지금)).isFalse();
    }

    @Test
    @DisplayName("만료일 0 과 최댓값은 '만료 없음' 이다 — 막힘으로 읽으면 전원이 권한을 잃는다")
    void 만료_없음_두_값() {
        // given, when, then
        assertThat(AdAccountStatus.막혔는가(속성들("accountExpires", "0"), 지금)).isFalse();
        assertThat(AdAccountStatus.막혔는가(속성들("accountExpires", "9223372036854775807"), 지금)).isFalse();
    }

    @Test
    @DisplayName("둘 중 하나만 막혀도 막혔다")
    void 둘_중_하나만_막혀도_막혔다() {
        // given — 비활성화는 아니지만 만료됐다
        var 만료만 = 속성들("userAccountControl", "512", "accountExpires", 일초_전);
        // 비활성화됐지만 만료 없음
        var 비활성화만 = 속성들("userAccountControl", "514", "accountExpires", "0");

        // when, then
        assertThat(AdAccountStatus.막혔는가(만료만, 지금)).isTrue();
        assertThat(AdAccountStatus.막혔는가(비활성화만, 지금)).isTrue();
    }

    @Test
    @DisplayName("정수가 아닌 값은 짐작하지 않고 실패한다 — 표준 밖이다")
    void 정수가_아니면_실패한다() {
        // given, when, then
        assertThatThrownBy(() -> AdAccountStatus.막혔는가(속성들("userAccountControl", "abc"), 지금))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userAccountControl")
                .hasMessageContaining("abc");
        assertThatThrownBy(() -> AdAccountStatus.막혔는가(속성들("accountExpires", "내일"), 지금))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accountExpires");
    }

    /** 서버가 주는 것처럼 대소문자를 가리지 않는 속성 집합을 만든다. */
    private static Attributes 속성들(String... 이름과_값) {
        Attributes attributes = new BasicAttributes(true);
        for (int i = 0; i < 이름과_값.length; i += 2) {
            attributes.put(이름과_값[i], 이름과_값[i + 1]);
        }
        return attributes;
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*AdAccountStatusTest'`
Expected: 컴파일 실패 — `cannot find symbol: class AdAccountStatus`

- [ ] **Step 3: 규칙을 구현한다**

`connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/AdAccountStatus.java`:

```java
package dev.starryeye.organization.ldap.strategy;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.time.Duration;
import java.time.Instant;

/**
 * AD 가 이 계정을 <b>막았는가</b>. 표준(MS-ADTS)이 정한 신호 둘 중 하나라도 막혔다고 하면 막힌 것이다.
 *
 * <ul>
 *   <li>{@code userAccountControl} 의 {@code ACCOUNTDISABLE} 비트({@code 0x2}) — <b>비트로</b> 검사한다.
 *       AD 는 여러 플래그를 더한 값을 주므로({@code 66050} = 비활성 + 암호 만료 없음) 값 비교는 틀린다.</li>
 *   <li>{@code accountExpires} — 1601-01-01 UTC 부터 100나노초 단위. {@code 0} 과 {@link Long#MAX_VALUE} 는
 *       "만료 없음" 이다. 그 밖의 값이 {@code 지금} 이전(같은 시각 포함)이면 막혔다.</li>
 * </ul>
 *
 * <p><b>속성이 없으면 막히지 않은 것이다.</b> OpenLDAP 등 AD 가 아닌 디렉터리에는 두 속성이 없다.
 * <b>정수가 아니면 던진다</b> — 표준 밖의 값을 짐작해 읽으면, 막힌 퇴사자를 활성으로 두거나 멀쩡한 직원의
 * 권한을 지운다. 예외는 그 회차의 동기화를 실패시킨다.
 *
 * <p>막힌 계정은 SCIM 과 같은 의미의 비활성이다 — 멤버십은 남고 권한 튜플만 사라진다.
 * 설계: {@code docs/superpowers/specs/2026-09-25-ldap-disabled-account-design.md}.
 */
final class AdAccountStatus {

    static final String USER_ACCOUNT_CONTROL = "userAccountControl";
    static final String ACCOUNT_EXPIRES = "accountExpires";

    private static final long ACCOUNTDISABLE = 0x2;
    private static final Instant FILETIME_기원 = Instant.parse("1601-01-01T00:00:00Z");
    private static final long 초당_틱 = 10_000_000L;

    private AdAccountStatus() {
    }

    static boolean 막혔는가(Attributes attributes, Instant 지금) {
        return 비활성화됐는가(attributes) || 만료됐는가(attributes, 지금);
    }

    private static boolean 비활성화됐는가(Attributes attributes) {
        Long 값 = 정수(attributes, USER_ACCOUNT_CONTROL);
        return 값 != null && (값 & ACCOUNTDISABLE) != 0;
    }

    private static boolean 만료됐는가(Attributes attributes, Instant 지금) {
        Long 값 = 정수(attributes, ACCOUNT_EXPIRES);
        if (값 == null || 값 == 0 || 값 == Long.MAX_VALUE) {
            return false;
        }
        Instant 만료 = FILETIME_기원.plus(Duration.ofSeconds(값 / 초당_틱, (값 % 초당_틱) * 100));
        return !만료.isAfter(지금);
    }

    private static Long 정수(Attributes attributes, String 이름) {
        Attribute attribute = attributes.get(이름);
        if (attribute == null) {
            return null;
        }
        Object 원본;
        try {
            원본 = attribute.get();
        } catch (NamingException e) {
            throw new IllegalStateException("속성 '" + 이름 + "' 을 읽지 못했습니다", e);
        }
        try {
            return Long.parseLong(String.valueOf(원본).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("속성 '" + 이름 + "' 의 값 '" + 원본 + "' 가 정수가 아닙니다"
                    + " — AD 표준 밖이라 계정이 막혔는지 판단할 수 없습니다", e);
        }
    }
}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*AdAccountStatusTest'`
Expected: 10개 전부 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/AdAccountStatus.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/AdAccountStatusTest.java
git commit -m "feat: AD 가 계정을 막았는지 판정하는 규칙을 더한다

userAccountControl 의 비활성화 비트와 accountExpires 의 만료일, 표준
신호 둘 중 하나라도 막혔다고 하면 막힌 것이다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 두 전략이 막힌 계정을 비활성으로 읽는다

**Files:**
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/DitStrategy.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/LdapConfig.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesAccountStatusTest.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitAccountStatusTest.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/AccountStatusInvalidValueTest.java`

**Interfaces:**
- Consumes: `AdAccountStatus.막혔는가(Attributes, Instant)` (Task 1)
- Produces:
  - `public GroupOfNamesStrategy(LdapProperties properties, Clock clock)` 와 기존 `public GroupOfNamesStrategy(LdapProperties properties)`(시스템 UTC 시계)
  - `public DitStrategy(LdapProperties properties, Clock clock)` 와 기존 `public DitStrategy(LdapProperties properties)`(시스템 UTC 시계)

- [ ] **Step 1: groupOfNames 의 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesAccountStatusTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AD 가 막은 계정은 비활성으로 읽힌다 — 멤버십은 남고 권한 튜플만 사라진다(스펙 §2).
 * 지금 이 전략은 모든 직원을 활성으로 만들어, 막힌 퇴사자가 권한을 유지한다.
 */
class GroupOfNamesAccountStatusTest extends EmbeddedLdapSupport {

    /** 2026-01-01T00:00:00Z. 아래 만료일(2020-01-01)은 그보다 과거다. */
    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=people,dc=example,dc=com
                objectClass: organizationalUnit
                ou: people

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=normal,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: normal
                cn: normal
                sn: normal
                userAccountControl: 512

                dn: uid=disabled,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: disabled
                cn: disabled
                sn: disabled
                userAccountControl: 514

                dn: uid=expired,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: expired
                cn: expired
                sn: expired
                userAccountControl: 512
                accountExpires: 132223104000000000

                dn: uid=plain,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: plain
                cn: plain
                sn: plain

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=normal,ou=people,dc=example,dc=com
                member: uid=disabled,ou=people,dc=example,dc=com
                member: uid=expired,ou=people,dc=example,dc=com
                member: uid=plain,ou=people,dc=example,dc=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("비활성화됐거나 만료된 직원은 비활성으로 읽힌다")
    void 막힌_직원은_비활성이다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var users = strategy.read(ldapTemplate).users();

        // then
        assertThat(users.get("disabled").active()).as("userAccountControl 514").isFalse();
        assertThat(users.get("expired").active()).as("accountExpires 가 2020-01-01").isFalse();
        assertThat(users.get("normal").active()).as("userAccountControl 512").isTrue();
        assertThat(users.get("plain").active()).as("두 속성이 없는 직원").isTrue();
    }

    @Test
    @DisplayName("막힌 직원도 그룹 멤버로는 남는다 — 멤버십은 두고 권한만 사라지는 것이 비활성이다")
    void 막힌_직원도_멤버로_남는다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정(), 고정시계);

        // when
        var members = strategy.read(ldapTemplate).groups().get("DEV").members();

        // then
        assertThat(members).contains(MemberRef.user("disabled"), MemberRef.user("expired"));
    }
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesAccountStatusTest'`
Expected: 컴파일 실패 — `GroupOfNamesStrategy(LdapProperties, Clock)` 생성자가 없다. 생성자만 먼저 더해 컴파일을 통과시키면(Step 3-1) `막힌_직원은_비활성이다` 가 `disabled` 의 `active` 가 `true` 라서 FAIL 해야 한다 — **그 실패 출력을 기록한다.** 이것이 이 결함의 RED 다.

- [ ] **Step 3: groupOfNames 를 고친다**

3-1. `GroupOfNamesStrategy` 에 시계를 받는다. `private final LdapProperties properties;` 다음에:

```java
    /** 계정 만료를 판정하는 "지금". 동기화마다 한 번 잡는다. */
    private final Clock clock;

    /** 시스템 UTC 시계를 쓴다. */
    public GroupOfNamesStrategy(LdapProperties properties) {
        this(properties, Clock.systemUTC());
    }
```

`@RequiredArgsConstructor` 가 `(LdapProperties, Clock)` 생성자를 만든다. `import java.time.Clock;` 와 `import java.time.Instant;` 를 더한다.

3-2. 직원 전용 레코드를 둔다. 파일 끝의 `RawEntry` 앞에:

```java
    /** 직원 엔트리. 그룹과 달리 멤버를 읽지 않고, AD 가 막았는지를 싣는다. */
    private record UserEntry(String id, String dn, String displayName, String email, boolean active) {
    }
```

3-3. `read` 의 맨 앞(`LdapProperties.GroupOfNames config = ...` 다음 줄)에 `Instant 지금 = clock.instant();` 를 두고, 직원 검색을 `List<UserEntry> userEntries = PagedLdapSearch.search(..., userMapper(config, 지금));` 으로 바꾼다.

3-4. `userMapper` 를 교체한다:

```java
    private ContextMapper<UserEntry> userMapper(LdapProperties.GroupOfNames config, Instant 지금) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            return new UserEntry(
                    IdNormalizer.normalize(required(attributes, config.getUserIdAttribute())),
                    절대DN(adapter),
                    firstNonBlank(value(attributes, config.getUserNameAttribute()),
                            value(attributes, "cn"),
                            required(attributes, config.getUserIdAttribute())),
                    value(attributes, config.getUserMailAttribute()),
                    // AD 가 막은 계정은 비활성이다 — 멤버십은 두고 권한 튜플만 사라진다
                    !AdAccountStatus.막혔는가(attributes, 지금));
        };
    }
```

(기존 자바독은 그대로 둔다.)

3-5. 직원 루프의 `for (RawEntry entry : userEntries)` 를 `for (UserEntry entry : userEntries)` 로, `DirectoryUser` 생성의 마지막 인자 `true` 를 `entry.active()` 로 바꾼다.

3-6. `RawEntry` 의 5인자 편의 생성자(`/** 직원 엔트리용. ... */ RawEntry(String id, ..., List<String> members)`)가 더 쓰이지 않으면 지운다 — `grep -rn "new RawEntry(" connector-ldap/src` 로 5인자 호출이 없는지 먼저 확인한다(`GroupOfNamesRangeContinuationTest` 는 6인자를 리플렉션으로 쓴다).

- [ ] **Step 4: groupOfNames 통과를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesAccountStatusTest'`
Expected: 2개 PASS

- [ ] **Step 5: DIT 의 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/DitAccountStatusTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIT 전략도 AD 가 막은 계정을 비활성으로 읽는다. 두 전략은 같은 디렉터리를 같은 스냅샷으로 읽기로 돼 있어
 * 규칙이 한쪽에만 있으면 안 된다.
 */
class DitAccountStatusTest extends EmbeddedLdapSupport {

    private static final Clock 고정시계 = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: uid=normal,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: normal
                cn: normal
                sn: normal
                userAccountControl: 512

                dn: uid=disabled,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: disabled
                cn: disabled
                sn: disabled
                userAccountControl: 66050

                dn: uid=expired,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: expired
                cn: expired
                sn: expired
                accountExpires: 132223104000000000
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        var d = properties.getDit();
        d.setRootDn("ou=company");
        d.setOrgUnitObjectClass("organizationalUnit");
        d.setGroupIdAttribute("ou");
        d.setUserObjectClass("inetOrgPerson");
        d.setUserIdAttribute("uid");
        return properties;
    }

    @Test
    @DisplayName("비활성화됐거나 만료된 직원은 비활성으로 읽히고, 소속은 그대로다")
    void 막힌_직원은_비활성이고_소속은_남는다() {
        // given
        var strategy = new DitStrategy(설정(), 고정시계);

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("disabled").active()).as("66050 = 비활성 + 암호 만료 없음").isFalse();
        assertThat(snapshot.users().get("expired").active()).as("accountExpires 가 2020-01-01").isFalse();
        assertThat(snapshot.users().get("normal").active()).isTrue();
        assertThat(snapshot.groups().get("company").members())
                .contains(MemberRef.user("disabled"), MemberRef.user("expired"));
    }
}
```

- [ ] **Step 6: 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*DitAccountStatusTest'`
Expected: 컴파일 실패(2인자 생성자 없음). 생성자만 더하면 `disabled` 가 활성이라 FAIL — 기록한다.

- [ ] **Step 7: DIT 를 고친다**

7-1. `DitStrategy` 에 같은 방식으로 시계를 받는다. `private final LdapProperties properties;` 다음에:

```java
    /** 계정 만료를 판정하는 "지금". 동기화마다 한 번 잡는다. */
    private final Clock clock;

    /** 시스템 UTC 시계를 쓴다. */
    public DitStrategy(LdapProperties properties) {
        this(properties, Clock.systemUTC());
    }
```

`import java.time.Clock;` 와 `import java.time.Instant;` 를 더한다.

7-2. `read` 의 맨 앞(`LdapProperties.Dit config = ...` 다음 줄)에 `Instant 지금 = clock.instant();` 를 둔다.

7-3. 직원을 만드는 `new DirectoryUser(...)` 의 마지막 인자 `true` 를 다음으로 바꾼다:

```java
                    // AD 가 막은 계정은 비활성이다 — 소속은 두고 권한 튜플만 사라진다
                    !AdAccountStatus.막혔는가(entry.adapter().getAttributes(), 지금)));
```

- [ ] **Step 8: 정수가 아닌 값의 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/AccountStatusInvalidValueTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 계정 상태 속성의 값이 정수가 아니면 두 전략 모두 읽기를 실패시킨다 — 짐작해 읽으면 막힌 퇴사자를 활성으로
 * 두거나 멀쩡한 직원의 권한을 지운다. 예외는 그 회차의 동기화를 실패시킨다.
 */
class AccountStatusInvalidValueTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=company,dc=example,dc=com
                objectClass: organizationalUnit
                ou: company

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: uid=odd,ou=company,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: odd
                cn: odd
                sn: odd
                userAccountControl: abc

                dn: cn=DEV,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV
                member: uid=odd,ou=company,dc=example,dc=com
                """;
    }

    @Test
    @DisplayName("groupOfNames — 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void groupOfNames는_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=company");
        g.setGroupSearchBase("ou=groups");

        // when, then
        assertThatThrownBy(() -> new GroupOfNamesStrategy(properties).read(ldapTemplate))
                .hasStackTraceContaining("userAccountControl");
    }

    @Test
    @DisplayName("DIT — 계정 상태 값이 정수가 아니면 읽기가 실패한다")
    void dit는_실패한다() {
        // given
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        properties.setStrategy("dit");
        properties.getDit().setRootDn("ou=company");

        // when, then
        assertThatThrownBy(() -> new DitStrategy(properties).read(ldapTemplate))
                .hasStackTraceContaining("userAccountControl");
    }
}
```

(`hasStackTraceContaining` 을 쓰는 이유: Spring LDAP 의 매퍼 안에서 던진 예외가 감싸여 올라올 수 있다.)

- [ ] **Step 9: 운영 설정이 앱의 시계를 넘기게 한다**

`LdapConfig.java` 의 `ldapMappingStrategy` 를 교체한다:

```java
    @Bean
    public LdapMappingStrategy ldapMappingStrategy(LdapProperties properties, Clock clock) {
        // 계정 만료를 동기화 시각과 비교한다 — 앱의 Clock 빈(DynamoDbConfig)을 쓴다
        return "dit".equalsIgnoreCase(properties.getStrategy())
                ? new DitStrategy(properties, clock)
                : new GroupOfNamesStrategy(properties, clock);
    }
```

`import java.time.Clock;` 를 더한다.

- [ ] **Step 10: 통과를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS (새 세 클래스 포함). 기존 테스트는 1인자 생성자를 그대로 쓰고, 그 디렉터리들에는 두 속성이 없어 전원 활성이라 결과가 바뀌지 않아야 한다. 깨지는 기존 테스트가 있으면 **고치기 전에 보고한다.**

- [ ] **Step 11: 커밋한다**

```bash
git add connector-ldap/src
git commit -m "fix: LDAP 전략이 AD 가 막은 계정을 비활성으로 읽는다

두 전략이 직원을 늘 active=true 로 만들어, AD 에서 비활성화하거나 만료일이
지난 계정도 권한을 유지했다. 동기화 시각을 한 번 잡아 직원마다 판정한다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: E2E — 막으면 권한이 사라지고, 풀면 돌아온다

**Files:**
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 2 의 운영 동작(앱 컨텍스트에서 `LdapConfig` 가 시스템 시계로 전략을 만든다)

- [ ] **Step 1: 시나리오를 쓴다**

`LdapSyncEndToEndTest.java` 의 마지막 테스트(`@Order(7)` 헬스체크) 뒤에 더한다. 필요한 import: `com.unboundid.ldap.sdk.Modification`, `com.unboundid.ldap.sdk.ModificationType`, `dev.starryeye.organization.core.model.MemberRef`, `java.time.Duration`.

```java
    // ---------- 계정 막힘 (⑥) ----------
    //
    // 이 조직도의 튜플은 셋뿐이다(kim·park 의 direct_member, DEV001→DEV002 의 child). 한 명을 막으면 1/3 이
    // 지워져 삭제 가드(30%)에 걸리므로 이 구간의 동기화는 force=true 로 돌린다.

    @Test
    @Order(8)
    @DisplayName("AD 에서 비활성화한 직원은 권한을 잃지만 소속은 남는다")
    void 비활성화하면_권한이_사라진다() throws Exception {
        // given — kim 의 계정을 비활성화한다. 그룹에서는 빼지 않는다
        LDAP.modify("uid=kim,ou=people,dc=example,dc=com",
                new Modification(ModificationType.REPLACE, "userAccountControl", "514"));

        // when
        강제로_동기화한다();

        // then — 권한은 없다
        assertThat(check("user:kim", "direct_member", "group:DEV002")).isFalse();
        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();
        // 소속은 남고 비활성으로 기록된다 — 멤버십은 두고 권한만 사라지는 것이 비활성이다
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.users().get("kim").active()).isFalse();
        assertThat(상태.groups().get("DEV002").members()).contains(MemberRef.user("kim"));
    }

    @Test
    @Order(9)
    @DisplayName("다시 활성화하면 다음 동기화에서 권한이 돌아온다")
    void 다시_활성화하면_권한이_돌아온다() throws Exception {
        // given
        LDAP.modify("uid=kim,ou=people,dc=example,dc=com",
                new Modification(ModificationType.REPLACE, "userAccountControl", "512"));

        // when
        강제로_동기화한다();

        // then
        assertThat(check("user:kim", "direct_member", "group:DEV002")).isTrue();
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("만료일이 지난 직원은 권한을 잃는다")
    void 만료되면_권한이_사라진다() throws Exception {
        // given — 2020-01-01T00:00:00Z. 1601-01-01 부터 100나노초 단위
        LDAP.modify("uid=park,ou=people,dc=example,dc=com",
                new Modification(ModificationType.REPLACE, "accountExpires", "132223104000000000"));

        // when
        강제로_동기화한다();

        // then
        assertThat(check("user:park", "direct_member", "group:DEV001")).isFalse();
        var 상태 = state.loadAll().block(Duration.ofSeconds(30));
        assertThat(상태).isNotNull();
        assertThat(상태.users().get("park").active()).isFalse();
    }

    /** 이 조직도는 튜플이 셋뿐이라 한 명만 막아도 삭제 가드(30%)를 넘는다. */
    private void 강제로_동기화한다() {
        client.post().uri("/admin/sync/full?force=true").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED");
    }
```

`check(...)` 도우미가 받는 인자의 모양(`"user:kim"` 처럼 타입 접두사를 붙이는지)을 이 파일의 `@Order(1)` 테스트에서 확인하고 그대로 따른다. `DirectoryStateRepository.loadAll()` 이 돌려주는 타입의 `users()`·`groups()` 접근자도 확인한다.

- [ ] **Step 2: 돌린다**

Run: `./gradlew :app-ldap:test --tests '*LdapSyncEndToEndTest'`
Expected: 10개 전부 PASS. (이 과제는 Task 2 뒤에 오므로 RED 가 따로 없다 — Task 2 를 빼고 돌리면 `@Order(8)` 이 실패해야 한다. 확인하려면 `git stash` 대신 Task 2 의 `DirectoryUser` 마지막 인자를 잠시 `true` 로 되돌려 돌려 보고, 되돌린 뒤 `git diff connector-ldap/src/main` 이 비었는지 확인한다.)

- [ ] **Step 3: README 에 운영자 안내를 더한다**

`README.md` 의 LDAP 절에서 동기화가 무엇을 읽는지 설명하는 자리(전략·속성 설명 근처)에 다음 문단을 더한다:

```markdown
**AD 가 막은 계정은 비활성으로 읽는다.** `userAccountControl` 의 비활성화 비트(`0x2`)가 켜졌거나
`accountExpires` 가 동기화 시각 이전이면 그 직원은 `active=false` 다 — 소속은 남고 권한 튜플만 사라진다
(SCIM 의 비활성과 같다). 두 속성이 없는 디렉터리(OpenLDAP 등)에서는 전원 활성이다. 값이 정수가 아니면 그
회차는 실패한다. 동기화 계정이 두 속성을 읽을 수 있어야 한다 — 못 읽으면 전원이 활성으로 읽힌다.
```

- [ ] **Step 4: 커밋한다**

```bash
git add app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapSyncEndToEndTest.java README.md
git commit -m "test: 막힌 계정은 권한을 잃고, 풀면 돌아오는 것을 E2E 로 확인한다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 머지 전 (컨트롤러)

- [ ] `./gradlew cleanTest test`
- [ ] `./gradlew cleanScaleTest scaleTest` — 규모 테스트는 이 변경의 영향을 받지 않아야 한다(두 속성이 없어 전원 활성)
- [ ] 최종 리뷰 → PR → main 머지
