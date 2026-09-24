package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(LdapProperties.class)
public class LdapConfig {

    @Bean
    public LdapContextSource ldapContextSource(LdapProperties properties) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(properties.getUrl());
        contextSource.setBase(properties.getBaseDn());
        contextSource.setUserDn(properties.getBindDn());
        contextSource.setPassword(properties.getBindPassword());
        return contextSource;
    }

    @Bean
    public LdapTemplate ldapTemplate(LdapContextSource contextSource) {
        // 설정은 LdapTemplates 한 곳에만 있다 — 페이징이 커넥션 하나를 붙잡으려고 템플릿을
        // 하나 더 만들기 때문이다. 두 벌이 되면 한쪽만 바뀌어도 컴파일은 통과한다.
        return LdapTemplates.configured(contextSource);
    }

    @Bean
    public LdapMappingStrategy ldapMappingStrategy(LdapProperties properties, Clock clock) {
        // 계정 만료를 동기화 시각과 비교한다 — 앱의 Clock 빈(DynamoDbConfig)을 쓴다
        return "dit".equalsIgnoreCase(properties.getStrategy())
                ? new DitStrategy(properties, clock)
                : new GroupOfNamesStrategy(properties, clock);
    }

    @Bean
    public LdapDirectorySnapshotSource ldapDirectorySnapshotSource(
            LdapTemplate template, LdapMappingStrategy strategy, LdapProperties properties) {
        return new LdapDirectorySnapshotSource(template, strategy, properties);
    }
}
