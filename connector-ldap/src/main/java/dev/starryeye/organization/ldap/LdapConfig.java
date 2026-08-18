package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

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
        LdapTemplate template = new LdapTemplate(contextSource);
        template.setIgnorePartialResultException(true);
        // 기본값(true)으로 두면 서버가 관리 한도(sizeLimit)로 결과를 자른 뒤 예외 없이 조용히
        // 응답한다 — 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 지워버릴 수 있다. false 로 두면
        // 그 경우 예외가 올라와 FAILED 로 기록되므로, 페이징을 우회하는 잘림도 안전망으로 잡는다.
        template.setIgnoreSizeLimitExceededException(false);
        return template;
    }

    @Bean
    public LdapMappingStrategy ldapMappingStrategy(LdapProperties properties) {
        return "dit".equalsIgnoreCase(properties.getStrategy())
                ? new DitStrategy(properties)
                : new GroupOfNamesStrategy(properties);
    }

    @Bean
    public LdapDirectorySnapshotSource ldapDirectorySnapshotSource(
            LdapTemplate template, LdapMappingStrategy strategy) {
        return new LdapDirectorySnapshotSource(template, strategy);
    }
}
