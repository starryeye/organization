package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapIdentifiers;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
        contextSource.setBaseEnvironmentProperties(jndiEnvironment(properties));
        return contextSource;
    }

    /**
     * JNDI 환경. 타임아웃(밀리초 문자열)과, 식별 속성이 {@code objectGUID} 면 그 이진 선언을 싣는다. 페이징·범위 검색용
     * {@code SingleContextSource}({@link LdapTemplates#한_커넥션에서})도 이 컨텍스트 소스에서 커넥션을 얻으므로 같은 값을 탄다.
     *
     * <p>이진 선언({@code java.naming.ldap.attributes.binary})이 없으면 JNDI 는 {@code objectGUID} 바이트를 문자열로 뭉개
     * 16바이트를 되살릴 수 없다. 두 전략의 식별 속성 넷 중 이진인 것의 이름을 공백으로 이어 넣는다.
     */
    static Map<String, Object> jndiEnvironment(LdapProperties properties) {
        Map<String, Object> 환경 = new LinkedHashMap<>();
        환경.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        환경.put("com.sun.jndi.ldap.read.timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        String 이진 = 이진_속성들(properties);
        if (!이진.isEmpty()) {
            환경.put("java.naming.ldap.attributes.binary", 이진);
        }
        return 환경;
    }

    /** 식별 속성 넷 중 이진으로 읽을 이름들. 대소문자만 다른 이름은 처음 것만 남긴다. */
    private static String 이진_속성들(LdapProperties properties) {
        var g = properties.getGroupOfNames();
        var d = properties.getDit();
        Map<String, String> 처음나온것 = new LinkedHashMap<>();
        for (String 이름 : new String[]{g.getUserIdAttribute(), g.getGroupIdAttribute(),
                d.getGroupIdAttribute(), d.getUserIdAttribute()}) {
            if (LdapIdentifiers.이진인가(이름)) {
                처음나온것.putIfAbsent(이름.toLowerCase(Locale.ROOT), 이름);
            }
        }
        return String.join(" ", 처음나온것.values());
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
