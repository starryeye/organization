package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldif.LDIFReader;
import dev.starryeye.organization.ldap.LdapConfig;
import dev.starryeye.organization.ldap.fixture.EmbeddedLdapListener;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** 문맥의 전략이 관찰자(지표)에 알리는 결선(설계 2026-11-19 S6) — 임베디드 LDAP 을 실제로 읽는다. */
class SkippedEntryMetricsWiringTest {

    private static final String LDIF = """
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
            cn: Kim
            sn: Kim
            employeeNumber: E001
            displayName: 김철수

            dn: uid=nobody,ou=people,dc=example,dc=com
            objectClass: inetOrgPerson
            uid: nobody
            cn: Nobody
            sn: Nobody
            displayName: 번호없음

            dn: cn=DEV001,ou=groups,dc=example,dc=com
            objectClass: groupOfNames
            cn: DEV001
            description: 개발본부
            member: uid=kim,ou=people,dc=example,dc=com
            """;

    private static InMemoryDirectoryServer LDAP;

    @BeforeAll
    static void 서버를_연다() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpassword");
        config.setListenerConfigs(EmbeddedLdapListener.리스너("skipped-wiring"));
        config.setSchema(null);
        LDAP = new InMemoryDirectoryServer(config);
        LDAP.importFromLDIF(true, new LDIFReader(new ByteArrayInputStream(LDIF.getBytes(StandardCharsets.UTF_8))));
        LDAP.startListening();
    }

    @AfterAll
    static void 서버를_닫는다() {
        LDAP.shutDown(true);
    }

    @Configuration(proxyBeanMethods = false)
    static class 공통_빈 {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private ApplicationContextRunner 러너() {
        return new ApplicationContextRunner()
                .withUserConfiguration(LdapConfig.class, 공통_빈.class)
                .withPropertyValues(
                        "ldap.urls=" + EmbeddedLdapListener.접속_주소(LDAP),
                        "ldap.base-dn=dc=example,dc=com",
                        "ldap.bind-dn=cn=admin,dc=example,dc=com",
                        "ldap.bind-password=adminpassword",
                        "ldap.group-of-names.user-id-attribute=employeeNumber",
                        "ldap.group-of-names.group-id-attribute=cn");
    }

    @Test
    @DisplayName("문맥의 전략이 지표에 알린다 — 식별 속성이 없는 직원 하나가 sync.entries.skipped{user, missing_id} 로 센다")
    void 문맥의_전략이_지표에_알린다() {
        러너().withUserConfiguration(SkippedEntryMetrics.class).run(context -> {
            // when
            context.getBean(LdapMappingStrategy.class).read(context.getBean(LdapTemplate.class));

            // then
            assertThat(context.getBean(MeterRegistry.class)
                    .get("sync.entries.skipped").tag("search", "user").tag("reason", "missing_id").counter().count())
                    .isEqualTo(1.0);
        });
    }

    @Test
    @DisplayName("관찰자 빈이 없으면 NOOP 으로 만든다 — 문맥이 뜨고 읽기가 던지지 않는다")
    void 관찰자_빈이_없으면_NOOP_로_만든다() {
        러너().run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(LdapMappingStrategy.class).read(context.getBean(LdapTemplate.class));
        });
    }
}
