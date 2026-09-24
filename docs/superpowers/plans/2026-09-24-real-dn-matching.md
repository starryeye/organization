# 서버가 준 DN 으로 멤버를 대조한다 (H) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GroupOfNamesStrategy` 가 DN 을 직접 조립하는 것을 멈추고, 서버가 준 DN 으로 멤버를 대조한다.

**Architecture:** 사용자 매퍼를 `AttributesMapper` 에서 `ContextMapper` 로 바꿔 서버가 준 DN 을 받고, 베이스를 붙인 **절대 DN 하나**를 대조 키와 `externalId` 양쪽에 쓴다. DN 비교는 문자열 다듬기 대신 `javax.naming.ldap.LdapName` 으로 파싱한 정규화 키로 한다. 멤버가 하나도 대조되지 않는 회차는 이름 있는 가드가 실패시킨다.

**Tech Stack:** Java 17, Spring LDAP(`ContextMapper`, `DirContextAdapter`), `javax.naming.ldap.LdapName`/`Rdn`, JUnit 5, AssertJ, Lombok, UnboundID in-memory LDAP.

**Spec:** [`docs/superpowers/specs/2026-09-24-real-dn-matching-design.md`](../specs/2026-09-24-real-dn-matching-design.md)

## Global Constraints

- **테스트는 한글 `@DisplayName`, AssertJ, BDD(given/when/then) 주석.** 기존 테스트의 형태를 그대로 따른다.
- **Lombok 을 쓴다.** 로깅은 `@Slf4j`.
- **OpenFGA 열거 API(`Read`/`ListObjects`) 금지.** 이 계획은 OpenFGA 를 건드리지 않으므로 해당 사항 없음.
- **인증은 범위 밖.** 이 브랜치에서 인증을 손대지 않는다.
- **운영 배포 전이라 데이터는 초기화 가능하다.** `externalId` 가 바뀌는 것을 이관 비용으로 세지 않는다.
- **긴 빌드는 컨트롤러(메인 세션)가 돌린다.** 서브에이전트는 `:connector-ldap:test` 까지만 돌린다. Gradle 을 둘 이상 동시에 돌리지 않는다. 전체 `test` 와 `scaleTest` 는 머지 전에 컨트롤러가 돌린다.
- **새 파일은 `connector-ldap` 모듈의 `strategy` 패키지에 둔다.** 기존 `DuplicateIdGuard`·`IncompleteAttributeReadException` 과 같은 자리다.
- **커밋 트레일러:** `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`

## File Structure

| 파일 | 책임 |
|---|---|
| `connector-ldap/src/main/java/.../strategy/LdapDns.java` (신규) | DN 을 표준 파서로 다룬다 — 대조키 만들기, 절대/상대 변환 |
| `connector-ldap/src/main/java/.../strategy/UnmatchedMemberGuard.java` (신규) | 멤버가 하나도 대조되지 않은 회차를 실패시킨다 |
| `connector-ldap/src/main/java/.../strategy/MemberMatchingFailedException.java` (신규) | 위 가드가 던지는 예외. 왜 이런 상황이 위험한지를 자바독이 담는다 |
| `connector-ldap/src/main/java/.../strategy/GroupOfNamesStrategy.java` (수정) | `dnOf`·`normalizeDn` 삭제, 사용자 매퍼를 `ContextMapper` 로, `RawEntry` 의 두 DN 칸 병합 |
| `connector-ldap/src/test/java/.../strategy/LdapDnsTest.java` (신규) | DN 도우미 단위 테스트 |
| `connector-ldap/src/test/java/.../GroupOfNamesDeepTreeTest.java` (신규) | **이 계획의 핵심 증명** — 깊은 트리에서 멤버가 대조된다 |
| `connector-ldap/src/test/java/.../GroupOfNamesUnmatchedMemberTest.java` (신규) | 전부 불일치 가드 |

## 알려진 함정 (구현자가 먼저 알아야 하는 것)

1. **`LdapConfig` 가 `contextSource.setBase(baseDn)` 를 한다.** 그래서 `DirContextAdapter.getDn()` 은 **베이스 상대 DN**(`ou=개발팀,ou=groups`)을 돌려준다. 그대로 쓰면 절대 DN 인 `member` 값과 여전히 안 맞는다. 반드시 베이스를 붙여야 한다.
2. **`RangedAttributeReader.전부_읽는다` 는 상대 DN 을 받는다.** 같은 `LdapTemplate` 커넥션으로 엔트리를 다시 지목하기 때문이다. 절대 DN 을 그대로 넘기면 범위 이어받기가 깨진다 — 반드시 베이스를 떼어 넘긴다.
3. **`LdifRenderer` 는 빈 조직에 존재하지 않는 `cn=placeholder,<baseDn>` 를 member 로 심는다.** 조직이 **전부** 빈 조직도를 읽는 테스트가 있다면 새 가드가 그 테스트를 깨뜨린다(멤버 값은 있는데 대조된 것이 0). Task 3 에 확인 단계를 두었다.
4. **`GroupOfNamesStrategy.java` 에 `import java.util.Map;` 이 두 번 있다.** Task 2 에서 정리한다.

---

### Task 1: DN 도우미 `LdapDns`

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java`
- Test: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/LdapDnsTest.java`

**Interfaces:**
- Consumes: 없음(순수 유틸리티, LDAP 서버 불필요)
- Produces: 패키지 전용 `final class LdapDns` 의 static 메서드 셋 —
  `String 절대로(String 상대DN, String baseDn)`,
  `String 상대로(String 절대DN, String baseDn)`,
  `String 대조키(String dn)`. 모두 해석 실패 시 `IllegalStateException`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/LdapDnsTest.java`:

```java
package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LdapDnsTest {

    private static final String BASE = "dc=example,dc=com";

    @Test
    @DisplayName("속성 이름 대소문자와 쉼표 주변 공백이 달라도 같은 엔트리로 대조된다")
    void 대소문자와_공백을_흡수한다() {
        // given
        String 서버가준것 = "CN=Hong Gildong, OU=Seoul, DC=example, DC=com";
        String 우리가읽은것 = "cn=hong gildong,ou=seoul,dc=example,dc=com";

        // when, then
        assertThat(LdapDns.대조키(서버가준것)).isEqualTo(LdapDns.대조키(우리가읽은것));
    }

    @Test
    @DisplayName("이스케이프된 쉼표는 값의 일부다 — RDN 경계로 잘리지 않는다")
    void 이스케이프된_쉼표를_값으로_다룬다() {
        // given — 이름에 쉼표가 든 사람. 문자열로 자르면 RDN 이 하나 더 생긴다
        String 쉼표가든이름 = "CN=Hong\\, Gildong,OU=Seoul," + BASE;
        String 쉼표가없는동명이인 = "CN=Hong,OU=Gildong,OU=Seoul," + BASE;

        // when, then
        assertThat(LdapDns.대조키(쉼표가든이름))
                .as("쉼표를 값으로 읽으면 RDN 은 3개(cn, ou, dc 둘)다")
                .isNotEqualTo(LdapDns.대조키(쉼표가없는동명이인));
    }

    @Test
    @DisplayName("다중값 RDN 은 적힌 순서가 달라도 같은 엔트리로 대조된다")
    void 다중값_RDN의_순서를_흡수한다() {
        // given
        String 이쪽순서 = "CN=hgd+OU=Seoul," + BASE;
        String 저쪽순서 = "OU=Seoul+CN=hgd," + BASE;

        // when, then
        assertThat(LdapDns.대조키(이쪽순서)).isEqualTo(LdapDns.대조키(저쪽순서));
    }

    @Test
    @DisplayName("서로 다른 엔트리는 다른 키가 된다 — 무엇이든 같게 만드는 정규화는 쓸모가 없다")
    void 다른_엔트리는_다른_키다() {
        // given, when, then
        assertThat(LdapDns.대조키("uid=kim,ou=people," + BASE))
                .isNotEqualTo(LdapDns.대조키("uid=lee,ou=people," + BASE));
    }

    @Test
    @DisplayName("베이스 상대 DN 에 베이스를 붙여 절대 DN 을 만든다")
    void 절대DN을_만든다() {
        // given, when
        String 절대 = LdapDns.절대로("cn=Hong Gildong,ou=Seoul,ou=users", BASE);

        // then
        assertThat(LdapDns.대조키(절대))
                .isEqualTo(LdapDns.대조키("cn=Hong Gildong,ou=Seoul,ou=users," + BASE));
    }

    @Test
    @DisplayName("엔트리가 베이스 자신이면 베이스가, 베이스가 비면 상대 DN 이 그대로 절대 DN 이다")
    void 절대DN의_경계를_다룬다() {
        // given, when, then
        assertThat(LdapDns.대조키(LdapDns.절대로("", BASE))).isEqualTo(LdapDns.대조키(BASE));
        assertThat(LdapDns.대조키(LdapDns.절대로("cn=dev,ou=groups", "")))
                .isEqualTo(LdapDns.대조키("cn=dev,ou=groups"));
    }

    @Test
    @DisplayName("절대 DN 에서 베이스를 떼어 낸다 — 범위 재요청은 상대 DN 을 받는다")
    void 상대DN으로_되돌린다() {
        // given, when
        String 상대 = LdapDns.상대로("cn=dev,ou=groups," + BASE, BASE);

        // then
        assertThat(LdapDns.대조키(상대)).isEqualTo(LdapDns.대조키("cn=dev,ou=groups"));
    }

    @Test
    @DisplayName("베이스 아래에 없는 DN 을 상대 DN 으로 바꾸려 하면 조용히 넘어가지 않고 깨진다")
    void 베이스_밖의_DN은_거부한다() {
        // given, when, then
        assertThatThrownBy(() -> LdapDns.상대로("cn=dev,ou=groups,dc=other,dc=com", BASE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("베이스");
    }

    @Test
    @DisplayName("해석할 수 없는 DN 은 문자열 비교로 물러나지 않고 예외로 알린다")
    void 해석할_수_없는_DN은_예외다() {
        // given — 대조하는 DN 은 모두 서버가 준 값이다. 해석에 실패했다면 값이 아니라
        // 우리가 DN 을 다루는 방식이 틀린 것이다
        // when, then
        assertThatThrownBy(() -> LdapDns.대조키("이건 DN 이 아니다"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("해석하지 못했습니다");
    }
}
```

- [ ] **Step 2: 컴파일 실패를 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*LdapDnsTest'`
Expected: 컴파일 실패 — `cannot find symbol: class LdapDns`

- [ ] **Step 3: `LdapDns` 를 구현한다**

`connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java`:

```java
package dev.starryeye.organization.ldap.strategy;

import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * DN 을 표준 파서({@link LdapName})로 다룬다. 문자열로 이어 붙이거나 자르지 않는다.
 *
 * <p>실제 디렉터리의 DN 은 속성 이름 대소문자({@code CN=} vs {@code cn=}), 쉼표 주변 공백,
 * 이스케이프({@code CN=Hong\, Gildong}), 다중값 RDN({@code CN=hgd+OU=Seoul}) 으로 흔들린다.
 * 문자열 다듬기로는 뒤의 둘을 다룰 수 없다 — 이스케이프된 쉼표를 RDN 경계로 잘못 자르면
 * 그 사람은 어느 그룹의 멤버로도 대조되지 않는다.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-09-24-real-dn-matching-design.md} §5.
 */
final class LdapDns {

    private LdapDns() {
    }

    /**
     * 서버가 준 베이스 상대 DN 앞에 베이스를 붙여 절대 DN 으로 만든다.
     * 그룹의 {@code member} 값은 절대 DN 이므로 대조하려면 이쪽을 맞춰야 한다.
     */
    static String 절대로(String 상대DN, String baseDn) {
        LdapName 절대 = 파싱한다(baseDn);
        try {
            절대.addAll(파싱한다(상대DN));
        } catch (InvalidNameException e) {
            throw new IllegalStateException(
                    "DN 을 합치지 못했습니다: '" + 상대DN + "' 아래 '" + baseDn + "'", e);
        }
        return 절대.toString();
    }

    /**
     * 절대 DN 에서 베이스를 떼어 낸다. 범위 검색 재요청은 {@code ContextSource} 의 베이스에
     * 상대적인 DN 을 받으므로 절대 DN 을 그대로 넘기면 엔트리를 찾지 못한다.
     */
    static String 상대로(String 절대DN, String baseDn) {
        LdapName 절대 = 파싱한다(절대DN);
        LdapName 베이스 = 파싱한다(baseDn);
        if (!절대.startsWith(베이스)) {
            throw new IllegalStateException(
                    "DN '" + 절대DN + "' 이 베이스 '" + baseDn + "' 아래에 있지 않습니다");
        }
        return 절대.getSuffix(베이스.size()).toString();
    }

    /**
     * 대조용 키. 같은 엔트리를 가리키는 두 DN 은 표기가 달라도 같은 키가 된다.
     * 값은 소문자로 맞춘다 — LDAP 의 이름 속성은 대개 대소문자를 가리지 않는다.
     */
    static String 대조키(String dn) {
        LdapName name = 파싱한다(dn);
        List<String> rdn들 = new ArrayList<>();
        // LdapName 은 0 이 뿌리 쪽이다. 사람이 읽는 순서로 되돌려 키를 만든다
        for (int i = name.size() - 1; i >= 0; i--) {
            rdn들.add(정규화한다(name.getRdn(i)));
        }
        return String.join(",", rdn들);
    }

    /** 다중값 RDN 은 적힌 순서가 달라도 같은 엔트리다 — 정렬해 한 모양으로 만든다. */
    private static String 정규화한다(Rdn rdn) {
        List<String> 쌍들 = new ArrayList<>();
        try {
            NamingEnumeration<? extends Attribute> 속성들 = rdn.toAttributes().getAll();
            while (속성들.hasMore()) {
                Attribute 속성 = 속성들.next();
                for (int i = 0; i < 속성.size(); i++) {
                    쌍들.add(속성.getID().toLowerCase(Locale.ROOT) + "="
                            + Rdn.escapeValue(속성.get(i)).toLowerCase(Locale.ROOT));
                }
            }
        } catch (NamingException e) {
            throw new IllegalStateException("RDN '" + rdn + "' 을 읽지 못했습니다", e);
        }
        Collections.sort(쌍들);
        return String.join("+", 쌍들);
    }

    private static LdapName 파싱한다(String dn) {
        try {
            return new LdapName(dn == null ? "" : dn);
        } catch (InvalidNameException e) {
            throw new IllegalStateException("DN 을 해석하지 못했습니다: '" + dn + "'."
                    + " 대조하는 DN 은 모두 서버가 준 값이라 문법은 올바를 것이다 —"
                    + " 우리가 DN 을 다루는 방식이 틀렸을 수 있다", e);
        }
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*LdapDnsTest'`
Expected: 9개 전부 PASS

- [ ] **Step 5: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/LdapDns.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/strategy/LdapDnsTest.java
git commit -m "feat: DN 을 표준 파서로 다루는 도우미를 더한다

문자열 다듬기로는 이스케이프된 쉼표와 다중값 RDN 을 다룰 수 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 서버가 준 DN 으로 대조한다

**Files:**
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesDeepTreeTest.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java`

**Interfaces:**
- Consumes: `LdapDns.절대로`, `LdapDns.상대로`, `LdapDns.대조키` (Task 1)
- Produces: `GroupOfNamesStrategy` 의 `RawEntry` 가 6칸이 된다 —
  `record RawEntry(String id, String dn, String displayName, String email, List<String> members, boolean membersComplete)`.
  `dn` 은 **절대 DN** 이고 `externalId` 로도 쓰인다. `realDn` 칸은 사라진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다 — 이 계획의 핵심 증명**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesDeepTreeTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Active Directory 의 모양 — 사용자가 검색 베이스 <b>바로 아래가 아니라</b> 조직 OU
 * 아래에 있고, RDN 이 식별 속성({@code uid})이 아니라 {@code cn} 이다.
 *
 * <p>다른 테스트들은 모두 평면 트리라, DN 을 조립해 대조하던 예전 코드가 우연히 맞아
 * 떨어졌다. 이 결함(2026-09-11 코드리뷰 H)은 깊은 트리를 읽어야만 드러난다.
 */
class GroupOfNamesDeepTreeTest extends EmbeddedLdapSupport {

    @Override
    protected String ldif() {
        return """
                dn: dc=example,dc=com
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=users,dc=example,dc=com
                objectClass: organizationalUnit
                ou: users

                dn: ou=Seoul,ou=users,dc=example,dc=com
                objectClass: organizationalUnit
                ou: Seoul

                dn: cn=Hong Gildong,ou=Seoul,ou=users,dc=example,dc=com
                objectClass: inetOrgPerson
                cn: Hong Gildong
                sn: Hong
                uid: hgd
                displayName: 홍길동
                mail: hgd@example.com

                dn: ou=groups,dc=example,dc=com
                objectClass: organizationalUnit
                ou: groups

                dn: cn=dev,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: dev
                description: 개발팀
                member: CN=Hong Gildong,OU=Seoul,OU=users,DC=example,DC=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=users");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("사용자가 검색 베이스 바로 아래가 아니어도 그룹 멤버로 대조된다")
    void 깊은_트리의_사용자가_멤버로_대조된다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users()).containsOnlyKeys("hgd");
        assertThat(snapshot.groups().get("dev").members())
                .as("member 값은 서버가 준 DN 이다 — 우리가 조립한 DN 으로 대조하면 0명이 된다")
                .containsExactly(MemberRef.user("hgd"));
    }

    @Test
    @DisplayName("externalId 는 서버가 준 DN 이다 — 식별 속성으로 조립한 DN 이 아니다")
    void externalId가_서버가_준_DN이다() {
        // given
        var strategy = new GroupOfNamesStrategy(설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then
        assertThat(snapshot.users().get("hgd").externalId())
                .containsIgnoringCase("cn=Hong Gildong")
                .containsIgnoringCase("ou=Seoul")
                .containsIgnoringCase(BASE_DN)
                .as("uid 로 조립한 DN 은 이 디렉터리에 존재하지 않는 엔트리를 가리킨다")
                .doesNotContainIgnoringCase("uid=hgd");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesDeepTreeTest'`
Expected: 둘 다 FAIL. 첫 번째는 멤버가 비어 있어서(`expected: [user:hgd] but was: []`), 두 번째는 `externalId` 가 `uid=hgd,ou=users,dc=example,dc=com` 이라서.
**이 실패 메시지를 확인하지 않고 넘어가지 말 것** — 이것이 H 를 눈으로 보는 유일한 자리다.

- [ ] **Step 3: 사용자 매퍼를 `ContextMapper` 로 바꾸고 DN 을 하나로 통일한다**

`GroupOfNamesStrategy.java` 를 다음과 같이 고친다.

3-1. `userMapper` 를 통째로 교체한다:

```java
    /**
     * <b>{@code ContextMapper} 다.</b> {@code AttributesMapper} 에는 DN 이 넘어오지 않아
     * 예전에는 검색 베이스와 식별 속성으로 DN 을 조립했는데, 실제 디렉터리는 사용자를 조직
     * OU 아래에 두고 RDN 도 {@code cn} 인 경우가 많아 서버가 준 {@code member} 값과 하나도
     * 맞지 않았다(2026-09-11 코드리뷰 H).
     */
    private ContextMapper<RawEntry> userMapper(LdapProperties.GroupOfNames config) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            return new RawEntry(
                    IdNormalizer.normalize(required(attributes, config.getUserIdAttribute())),
                    절대DN(adapter),
                    firstNonBlank(value(attributes, config.getUserNameAttribute()),
                            value(attributes, "cn"),
                            required(attributes, config.getUserIdAttribute())),
                    value(attributes, config.getUserMailAttribute()),
                    List.of());
        };
    }
```

3-2. `groupMapper` 의 자바독과 DN 칸을 고친다. 자바독 전체를 다음으로 바꾼다:

```java
    /**
     * <b>{@code ContextMapper} 다.</b> 서버가 준 DN 이 두 곳에 필요하다 — 그룹의
     * {@code member} 값과 대조할 키를 만들 때, 그리고 범위 검색으로 잘린 멤버를 이어받으려
     * 그 엔트리를 다시 지목해 물을 때.
     */
```

이어서 본문의 `dnOf(...)` 호출과 그 위 주석 두 줄, 그리고 마지막 두 인자를 고친다:

```java
            return new RawEntry(
                    code,
                    절대DN(adapter),
                    // 폴백은 정규화된 code 가 아니라 원본이다 — 금지 문자가 있으면 code 에는
                    // 밑줄이 들어가고, 그것이 사람이 읽는 표시명 칸에 그대로 새어 나온다
                    firstNonBlank(value(attributes, config.getGroupNameAttribute()),
                            required(attributes, config.getGroupIdAttribute())),
                    null,
                    멤버.values(),
                    멤버.완료());
```

3-3. `dnOf` 와 `normalizeDn` 을 **지우고** 그 자리에 `절대DN` 을 둔다:

```java
    /**
     * 서버가 준 DN 은 {@code ContextSource} 의 베이스에 상대적이다. 그룹의 {@code member}
     * 값은 절대 DN 이므로 베이스를 붙여야 같은 좌표계에 놓인다.
     */
    private String 절대DN(DirContextAdapter adapter) {
        return LdapDns.절대로(adapter.getDn().toString(), properties.getBaseDn());
    }
```

3-4. `read` 의 대조 키를 바꾼다. `normalizeDn(...)` 을 쓰는 세 곳을 `LdapDns.대조키(...)` 로 바꾼다:

```java
            userIdByDn.put(LdapDns.대조키(entry.dn()), entry.id());
```
```java
            groupIdByDn.put(LdapDns.대조키(entry.dn()), entry.id());
```
```java
                String key = LdapDns.대조키(memberDn);
```

3-5. `범위가_잘린_멤버를_이어받는다` 에서 `realDn` 을 `dn` 으로 바꾸고, 재요청에는 상대 DN 을 넘긴다. 맵 색인 주석의 마지막 문장과 본문을 다음으로 바꾼다:

```java
        // dn 으로 색인한다 — entry.id() 는 안 된다. IdNormalizer 가 금지 문자를 뭉개
        // 서로 다른 조직코드를 같은 값으로 만들 수 있고(DuplicateIdGuard 가 막는 바로 그
        // 충돌), 그 상태에서 아이디로 색인하면 잘리지 않은 형제 조직까지 이 맵에 걸려
        // 남의 이어받은 멤버 목록을 받는다 — 3명짜리 조직이 조용히 1,600명을 떠안는 권한
        // 확대다. dn 은 서버가 돌려준 진짜 DN 이라 엔트리마다 유일하다.
        Map<String, List<String>> 이어받은것 = LdapTemplates.한_커넥션에서(template, 한커넥션 -> {
            Map<String, List<String>> 결과 = new LinkedHashMap<>();
            for (RawEntry entry : 잘린것) {
                // 재요청은 ContextSource 의 베이스에 상대적인 DN 을 받는다 —
                // 절대 DN 을 그대로 넘기면 엔트리를 찾지 못한다
                List<String> 전부 = RangedAttributeReader.전부_읽는다(한커넥션,
                        LdapDns.상대로(entry.dn(), properties.getBaseDn()),
                        config.getMemberAttribute());
                log.info("조직 '{}' 의 멤버를 {}개까지 이어받았다 (첫 조각 {}개)",
                        entry.id(), 전부.size(), entry.members().size());
                결과.put(entry.dn(), 전부);
            }
            return 결과;
        });

        // 마지막 인자가 무조건 true 인 것은 낙관이 아니다 — 끝까지 못 읽으면
        // 전부_읽는다 가 IncompleteAttributeReadException 을 던지므로 여기 도달하지 못한다.
        return entries.stream()
                .map(entry -> 이어받은것.containsKey(entry.dn())
                        ? new RawEntry(entry.id(), entry.dn(), entry.displayName(), entry.email(),
                                이어받은것.get(entry.dn()), true)
                        : entry)
                .toList();
```

3-6. `RawEntry` 를 6칸으로 줄인다:

```java
    /**
     * @param dn              <b>서버가 준 절대 DN.</b> member 대조 키이자 externalId 이고,
     *                        범위 검색 재요청 때 엔트리를 다시 지목하는 좌표이기도 하다
     * @param membersComplete 멤버 목록이 잘리지 않고 다 왔는가
     */
    private record RawEntry(String id, String dn, String displayName, String email,
                            List<String> members, boolean membersComplete) {

        /** 직원 엔트리용. 다중값 속성을 읽지 않으므로 언제나 완결이다. */
        RawEntry(String id, String dn, String displayName, String email, List<String> members) {
            this(id, dn, displayName, email, members, true);
        }
    }
```

3-7. 쓰이지 않게 된 import 를 지운다: `org.springframework.ldap.core.AttributesMapper`, `java.util.Locale`. **중복된 `import java.util.Map;` 도 한 줄 지운다.**

- [ ] **Step 4: 테스트가 통과하는지 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesDeepTreeTest'`
Expected: 둘 다 PASS

- [ ] **Step 5: 모듈 전체 회귀를 확인한다**

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS. 실패한다면 **테스트를 고치기 전에 왜 실패하는지 보고한다** — `externalId` 값이 바뀌는 것은 의도된 변화이지만, 멤버 대조가 깨지는 것은 결함이다.

`externalId` 를 단언하는 곳이 셋 있다. 평면 픽스처에서는 조립한 DN 과 서버가 준 DN 이 같은 문자열이라 **그대로 통과해야 한다.** 통과하지 않으면 대소문자나 공백이 달라진 것이니, 테스트를 느슨하게 고치지 말고 보고한다.

| 자리 | 단언 |
|---|---|
| `GroupOfNamesStrategyTest:101` | `kim.externalId()).contains("uid=kim")` |
| `GroupOfNamesDuplicateIdTest:143` | 가드 경고의 dn 과 살아남은 `externalId` 가 일치 |
| `LdifRendererTest:118-126` | `externalId` 가 `RENDERER.userDn(...)`/`groupDn(...)` 과 정확히 일치 |

- [ ] **Step 6: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesDeepTreeTest.java
git commit -m "fix: 서버가 준 DN 으로 그룹 멤버를 대조한다

사용자 매퍼를 ContextMapper 로 바꾸고, 대조 키와 externalId 를 서버가 준
절대 DN 하나로 통일한다. 조립한 DN 을 쓰던 dnOf 를 지운다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: 멤버가 하나도 대조되지 않으면 실패시킨다

**Files:**
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/UnmatchedMemberGuard.java`
- Create: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/MemberMatchingFailedException.java`
- Create: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesUnmatchedMemberTest.java`
- Modify: `connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java` (`read` 끝에서 가드 호출)

**Interfaces:**
- Consumes: Task 2 가 만든 대조 루프
- Produces: `static void UnmatchedMemberGuard.확인한다(int 조직수, int 멤버값수, int 대조된수)`,
  `public class MemberMatchingFailedException extends RuntimeException`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesUnmatchedMemberTest.java`:

```java
package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.MemberMatchingFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 부분 불일치는 정상이다 — 연락처나 컴퓨터 계정처럼 우리가 읽지 않는 엔트리가 그룹에
 * 섞여 있을 수 있고, 그것은 {@code GroupOfNamesStrategyTest} 가 이미 덮는다.
 *
 * <p>여기서 보는 것은 <b>전부</b> 어긋난 경우다. 그때는 값이 이상한 것이 아니라 DN 형태
 * 자체가 어긋난 것이고, 그대로 진행하면 아무도 권한을 받지 못한 채 동기화가 성공으로 끝난다.
 */
class GroupOfNamesUnmatchedMemberTest extends EmbeddedLdapSupport {

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

                dn: uid=kim,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim
                cn: Kim Chulsoo
                sn: Kim
                displayName: 김철수

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: CN=Kim Chulsoo,OU=Seoul,OU=Users,DC=corp,DC=example,DC=com
                """;
    }

    private LdapProperties 설정() {
        var properties = new LdapProperties();
        properties.setBaseDn(BASE_DN);
        var g = properties.getGroupOfNames();
        g.setUserSearchBase("ou=people");
        g.setUserObjectClass("inetOrgPerson");
        g.setUserIdAttribute("uid");
        g.setUserNameAttribute("displayName");
        g.setUserMailAttribute("mail");
        g.setGroupSearchBase("ou=groups");
        g.setGroupObjectClass("groupOfNames");
        g.setGroupIdAttribute("cn");
        g.setGroupNameAttribute("description");
        g.setMemberAttribute("member");
        return properties;
    }

    @Test
    @DisplayName("DN 형태가 어긋나 멤버가 하나도 대조되지 않으면 그 회차를 실패시킨다")
    void 전부_대조되지_않으면_실패시킨다() {
        // given — 사용자는 읽히지만 member 값은 전혀 다른 트리를 가리킨다
        var strategy = new GroupOfNamesStrategy(설정());

        // when, then
        assertThatThrownBy(() -> strategy.read(ldapTemplate))
                .as("조용히 멤버 0명으로 적재하면 아무도 권한을 받지 못한 채 성공으로 끝난다")
                .isInstanceOf(MemberMatchingFailedException.class)
                .hasMessageContaining("검색 베이스");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesUnmatchedMemberTest'`
Expected: 컴파일 실패 — `cannot find symbol: class MemberMatchingFailedException`

- [ ] **Step 3: 예외와 가드를 만든다**

`MemberMatchingFailedException.java`:

```java
package dev.starryeye.organization.ldap.strategy;

/**
 * 그룹의 {@code member} 값이 <b>하나도</b> 우리가 읽은 엔트리와 대조되지 않았다.
 * {@link UnmatchedMemberGuard} 가 던진다 — 그 회차는 아무것도 쓰지 않고 실패로 끝난다.
 */
public class MemberMatchingFailedException extends RuntimeException {

    public MemberMatchingFailedException(String message) {
        super(message);
    }
}
```

`UnmatchedMemberGuard.java`:

```java
package dev.starryeye.organization.ldap.strategy;

/**
 * 멤버 대조가 <b>전부</b> 실패한 회차를 중단시킨다.
 *
 * <p><b>왜 이 가드가 있는가 — 2026-09-11 코드리뷰 H.</b> 예전 전략은 대조에 쓸 DN 을 직접
 * 조립했다(검색 베이스 바로 아래 + RDN 이 식별 속성). 실제 Active Directory 처럼 사용자가
 * 조직 OU 아래 있고 RDN 이 {@code cn} 인 트리에서는 서버가 준 {@code member} 값과 하나도
 * 맞지 않는다. 증상은 <b>"모든 그룹이 멤버 0명"</b> 이다.
 *
 * <p><b>조용히 지나가는 것이 문제다.</b> 첫 적재라면 지울 것이 없어 삭제 가드(30%)도 걸리지
 * 않는다. 아무도 권한을 받지 못한 채 동기화는 성공으로 끝난다. 이미 적재된 뒤라면 삭제
 * 가드가 멈출 가능성이 높지만, 그 메시지는 "임계치 초과" 라 원인을 말해 주지 않는다.
 *
 * <p><b>부분 불일치는 막지 않는다.</b> 연락처나 컴퓨터 계정처럼 우리가 읽지 않는 엔트리가
 * 그룹에 섞여 있는 것은 정상이다 — 그쪽은 경고를 남기고 건너뛴다.
 *
 * <p>설계: {@code docs/superpowers/specs/2026-09-24-real-dn-matching-design.md} §6.
 */
final class UnmatchedMemberGuard {

    private UnmatchedMemberGuard() {
    }

    /**
     * 조직이 하나라도 있고 {@code member} 값이 하나 이상인데 대조된 것이 0 이면
     * {@link MemberMatchingFailedException} 을 던진다.
     */
    static void 확인한다(int 조직수, int 멤버값수, int 대조된수) {
        if (조직수 == 0 || 멤버값수 == 0 || 대조된수 > 0) {
            return;
        }
        throw new MemberMatchingFailedException(
                "조직 " + 조직수 + "개의 member 값 " + 멤버값수 + "개가 하나도 대조되지 않았습니다."
                        + " 사용자 검색 베이스와 그룹 member 값의 DN 형태가 어긋났는지"
                        + " 확인하십시오.");
    }
}
```

- [ ] **Step 4: `read` 에서 가드를 부른다**

`GroupOfNamesStrategy.read` 의 멤버 대조 루프를 다음으로 바꾼다(세는 것만 더한다):

```java
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        int 멤버값수 = 0;
        int 대조된수 = 0;
        for (RawEntry entry : survivingGroupEntries.values()) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (String memberDn : entry.members()) {
                멤버값수++;
                String key = LdapDns.대조키(memberDn);
                String userId = userIdByDn.get(key);
                if (userId != null) {
                    members.add(MemberRef.user(userId));
                    대조된수++;
                    continue;
                }
                String groupId = groupIdByDn.get(key);
                if (groupId != null) {
                    members.add(MemberRef.group(groupId));
                    대조된수++;
                    continue;
                }
                log.warn("조직 '{}' 의 member '{}' 가 사람도 그룹도 아니어서 건너뜁니다", entry.id(), memberDn);
            }
            groups.put(entry.id(), new DirectoryGroup(entry.id(), entry.dn(), entry.displayName(), members));
        }
        UnmatchedMemberGuard.확인한다(groups.size(), 멤버값수, 대조된수);

        return new DirectorySnapshot(users, groups);
```

- [ ] **Step 5: 테스트가 통과하는지 확인한다**

Run: `./gradlew :connector-ldap:test --tests '*GroupOfNamesUnmatchedMemberTest'`
Expected: PASS

- [ ] **Step 6: 함정 확인 — 전부 빈 조직도**

`LdifRenderer` 는 빈 조직에 존재하지 않는 `cn=placeholder,<baseDn>` 를 member 로 심는다. **조직이 전부 빈 조직도**를 읽는 테스트가 있으면 새 가드가 그것을 깨뜨린다.

Run: `./gradlew :connector-ldap:test`
Expected: 전부 PASS.

`MemberMatchingFailedException` 때문에 깨지는 테스트가 있으면 **고치기 전에 보고한다.** 판단 기준: 그 조직도가 **현실에서 있을 수 있는 디렉터리**(모든 그룹이 비어 있음)라면 가드의 오탐이므로 픽스처가 아니라 가드를 고쳐야 한다. 픽스처의 자리 채우기 때문에 생긴 것이라면 그 테스트의 조직도에 멤버가 있는 조직을 하나 더해 고친다.

- [ ] **Step 7: 커밋한다**

```bash
git add connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/UnmatchedMemberGuard.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/MemberMatchingFailedException.java connector-ldap/src/main/java/dev/starryeye/organization/ldap/strategy/GroupOfNamesStrategy.java connector-ldap/src/test/java/dev/starryeye/organization/ldap/GroupOfNamesUnmatchedMemberTest.java
git commit -m "fix: 멤버가 하나도 대조되지 않은 회차를 실패시킨다

첫 적재라면 삭제 가드도 걸리지 않아, 아무도 권한을 받지 못한 채
동기화가 성공으로 끝난다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 낡은 설명을 고치고 변이로 확인한다

**Files:**
- Modify: `connector-ldap/src/test/java/dev/starryeye/organization/ldap/fixture/LdifRendererTest.java:117-126`
- Modify: `docs/superpowers/specs/2026-09-24-real-dn-matching-design.md` (§10)

**Interfaces:**
- Consumes: Task 1~3 의 결과
- Produces: 없음(문서와 설명만)

- [ ] **Step 1: 낡아진 테스트 이름을 고친다**

`LdifRendererTest` 의 `DN_규칙이_전략과_일치한다` 는 `@DisplayName` 이 "전략이 **재구성**하는 형태와 같아야 한다" 인데, 전략은 더 이상 DN 을 재구성하지 않는다. 다음으로 바꾼다:

```java
    @Test
    @DisplayName("픽스처가 심는 DN 과 전략이 읽어 온 DN 이 같아야 한다 — externalId 가 어긋나면 안 된다")
    void DN_규칙이_전략과_일치한다() {
```

같은 파일에서 `재구성` 이라는 낱말이 더 나오면 같은 이유로 고친다.

- [ ] **Step 2: `재구성` 이 남아 있는 곳을 찾아 정리한다**

Run: `grep -rn "재구성" connector-ldap/src README.md`
Expected: `GroupOfNamesStrategy` 와 `LdifRendererTest` 에는 더 이상 없어야 한다. 다른 파일에서 이 결함을 설명하며 남은 것은 그대로 둔다(예: 리뷰 문서는 당시 기록이다).

- [ ] **Step 3: 변이 네 가지로 테스트가 결함을 잡는지 확인한다**

하나씩 넣고 → 돌리고 → **되돌린다.** 각 변이의 실패 테스트 이름과 메시지 첫 줄을 적어 둔다.

| # | 변이 | 실패해야 하는 테스트 |
|---|---|---|
| 1 | `절대DN(adapter)` 을 `adapter.getDn().toString()` 으로 바꾼다(베이스를 안 붙인다) | `GroupOfNamesDeepTreeTest` |
| 2 | `LdapDns.대조키(...)` 를 `dn.toLowerCase(Locale.ROOT).replace(", ", ",")` 로 되돌린다 | `LdapDnsTest`(이스케이프·다중값), `GroupOfNamesDeepTreeTest` 는 통과할 수도 있다 — 통과한다면 그 사실을 적는다 |
| 3 | `UnmatchedMemberGuard.확인한다(...)` 호출을 지운다 | `GroupOfNamesUnmatchedMemberTest` |
| 4 | `LdapDns.파싱한다` 의 예외를 `return new LdapName("")` 로 바꿔 조용히 넘긴다 | `LdapDnsTest.해석할_수_없는_DN은_예외다` |

각 변이마다:
```bash
./gradlew :connector-ldap:test
```
그리고 되돌린 뒤 다시 한 번 돌려 **원상복구를 확인한다.**

- [ ] **Step 4: 스펙 §10 에 변이 결과를 적는다**

`docs/superpowers/specs/2026-09-24-real-dn-matching-design.md` 의 `## 10. 구현 후 기록` 아래에 표를 채운다. 변이 2 가 깊은 트리 테스트를 잡지 못했다면 **그대로 적는다** — 어떤 테스트가 무엇을 못 잡는지가 다음 사람에게 필요한 정보다.

- [ ] **Step 5: 커밋한다**

```bash
git add -A
git commit -m "docs: 낡아진 설명을 고치고 변이 결과를 기록한다

전략은 더 이상 DN 을 재구성하지 않는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## 머지 전 (컨트롤러가 한다)

- [ ] `./gradlew test` — 규모 테스트를 뺀 전부
- [ ] `./gradlew scaleTest` — 규모 테스트 12개. 평면 픽스처라 동작은 그대로여야 한다
- [ ] 최종 리뷰 → PR → main 머지
