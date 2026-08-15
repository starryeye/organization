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
