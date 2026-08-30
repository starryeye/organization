package dev.starryeye.organization.ldap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * groupOfNames 전략의 중복 아이디 경로 (설계 §4.3 / §9).
 *
 * <p>지금까지 이 경로는 DIT 전략에만 픽스처가 있었다. 두 전략이 같은 가드를 쓰지만
 * <b>충돌이 생기는 이유가 다르다</b> — DIT 은 형제 사이에서만 RDN 이 유일해서고,
 * groupOfNames 는 정규화가 금지 문자를 {@code _} 로 바꾸면서 서로 다른 값이 뭉개져서다.
 * 후자를 아무도 확인하지 않고 있었다.
 *
 * <p>가드가 없으면 두 사람이 하나의 아이디로 합쳐져, 한쪽의 소속이 다른 쪽에게도
 * 붙는 조용한 권한 확대가 된다.
 */
class GroupOfNamesDuplicateIdTest extends EmbeddedLdapSupport {

    private ListAppender<ILoggingEvent> logAppender;

    /** {@code kim lee} 와 {@code kim*lee} 는 둘 다 {@code kim_lee} 로 정규화된다. */
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

                dn: uid=kim lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim lee
                cn: Kim Lee
                sn: Kim
                displayName: 김리
                mail: kim.lee@example.com

                dn: uid=kim*lee,ou=people,dc=example,dc=com
                objectClass: inetOrgPerson
                uid: kim*lee
                cn: Kim Star Lee
                sn: Kim
                displayName: 김별리
                mail: kim.star@example.com

                dn: cn=DEV001,ou=groups,dc=example,dc=com
                objectClass: groupOfNames
                cn: DEV001
                description: 개발본부
                member: uid=kim lee,ou=people,dc=example,dc=com
                """;
    }

    @BeforeEach
    void 로그_수집기를_등록한다() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy"))
                .addAppender(logAppender);
    }

    @AfterEach
    void 로그_수집기를_해제한다() {
        ((Logger) LoggerFactory.getLogger("dev.starryeye.organization.ldap.strategy"))
                .detachAppender(logAppender);
    }

    private LdapProperties 기본설정() {
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

    /** 경고 메시지에서 작은따옴표로 감싼 dn 들을 뽑는다. */
    private static List<String> dn들(String message) {
        var matcher = Pattern.compile("dn='([^']*)'").matcher(message);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("정규화 후 직원 아이디가 충돌하면 건너뛴 쪽이 아니라 유지한 쪽이 스냅샷에 남는다")
    void 직원_아이디가_충돌하면_유지한_쪽이_남는다() {
        // given
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        var snapshot = strategy.read(ldapTemplate);

        // then — 하나만 남는 것으로는 부족하다. 가드가 없어도 뒤 엔트리가 앞을 덮어써서
        // 키는 어차피 하나이기 때문이다(이 단언만 두면 가드를 걷어내도 통과한다).
        // 진짜 계약은 "건너뛰기로 한 쪽이 스냅샷에 없다" 이다.
        assertThat(snapshot.users()).containsOnlyKeys("kim_lee");

        var warning = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("직원 아이디"))
                .findFirst();
        assertThat(warning).as("충돌했는데 경고가 없다면 가드가 동작하지 않은 것이다").isPresent();

        List<String> dns = dn들(warning.get().getFormattedMessage());
        assertThat(dns).hasSize(2);
        String 유지된dn = dns.get(0);
        String 건너뛴dn = dns.get(1);

        String 살아남은dn = snapshot.users().get("kim_lee").externalId();
        assertThat(살아남은dn)
                .as("가드가 유지하기로 한 엔트리가 실제로 남아야 한다")
                .contains(유지된dn);
        assertThat(살아남은dn)
                .as("건너뛰기로 한 엔트리가 남으면 가드 판단과 결과가 어긋난 것이다")
                .doesNotContain(건너뛴dn);
    }

    @Test
    @DisplayName("충돌 경고는 유지된 dn 과 건너뛴 dn 을 모두 담는다 — 어느 쪽이 사라졌는지 알아야 고친다")
    void 경고가_두_dn을_모두_담는다() {
        // given
        var strategy = new GroupOfNamesStrategy(기본설정());

        // when
        strategy.read(ldapTemplate);

        // then
        List<ILoggingEvent> warnings = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("직원 아이디"))
                .toList();
        assertThat(warnings).hasSize(1);

        // 운영자가 이 로그만 보고 어느 엔트리를 고쳐야 하는지 알 수 있어야 한다.
        // 한쪽만 담으면 "무엇이 사라졌는지" 또는 "무엇과 부딪혔는지" 중 하나를 모른다.
        String message = warnings.get(0).getFormattedMessage();
        assertThat(message).contains("kim_lee");
        assertThat(message)
                .as("유지된 dn 과 건너뛴 dn 이 모두 있어야 한다")
                .contains("uid=kim lee")
                .contains("uid=kim*lee");
    }
}
