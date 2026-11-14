package dev.starryeye.organization.ldap;

import dev.starryeye.organization.ldap.strategy.ActiveDirectoryPrimaryGroup;
import dev.starryeye.organization.ldap.strategy.DitStrategy;
import dev.starryeye.organization.ldap.strategy.GroupOfNamesStrategy;
import dev.starryeye.organization.ldap.strategy.LdapIdentifiers;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(LdapProperties.class)
public class LdapConfig {

    /**
     * 기동 때 설정을 검사한다(설계 2026-10-27 §5) — {@link #ldapContextSource}·{@link #ldapMappingStrategy} 가 맨 먼저 부른다. 비밀번호는 오류 문구에
     * 싣지 않는다.
     *
     * <ul>
     *   <li><b>전략.</b> {@code group-of-names}·{@code dit} 만 받는다(대소문자 무시). 예전에는 {@code dit} 가 아니면 오타까지 모두 groupOfNames 로 읽었다.
     *   <li><b>바인드 비밀번호.</b> 부트의 {@code @ConfigurationProperties} 바인딩은 풀리지 않은 자리표시자를 무시하고 {@code "${LDAP_BIND_PASSWORD}"}
     *       문자열을 그대로 넣는다. 그 값으로 바인드하면 계정 잠금만 부르므로, 바인드 DN 이 있는데 비밀번호가 비었거나 <code>&#36;&#123;</code> 로 시작하면 멈춘다.
     *       바인드 DN 이 없는 익명 바인드는 비밀번호를 보지 않는다.
     *   <li><b>주소 목록.</b> {@code ldap.urls} 가 비었거나, 주소 하나라도 모양이 틀리면 멈춘다 — 스킴이 {@code ldap}·{@code ldaps}(대소문자 무시)가 아니거나,
     *       호스트가 없거나, 경로({@code /} 하나는 허용)·질의·조각이 있는 경우다. 검색 베이스는 주소가 아니라 {@code ldap.base-dn} 에 준다(설계 2026-11-14 F3).
     *   <li><b>TLS.</b> {@code ldap.require-tls} 인데 {@code ldap.urls} 에 {@code ldaps://} 가 아닌 주소가 하나라도 있으면 멈춘다(설계 2026-11-14 F4).
     * </ul>
     */
    static void 설정을_검사한다(LdapProperties properties) {
        String 전략 = properties.getStrategy();
        if (!"group-of-names".equalsIgnoreCase(전략) && !"dit".equalsIgnoreCase(전략)) {
            throw new IllegalStateException(
                    "ldap.strategy 는 group-of-names 또는 dit 다(받은 값: %s)".formatted(전략));
        }
        String 바인드_DN = properties.getBindDn();
        String 비밀번호 = properties.getBindPassword();
        if (바인드_DN != null && !바인드_DN.isBlank()
                && (비밀번호 == null || 비밀번호.isBlank() || 비밀번호.startsWith("${"))) {
            throw new IllegalStateException("ldap.bind-dn 이 있는데 ldap.bind-password 가 비었거나 풀리지 않은 자리표시자다"
                    + " — 환경 변수 LDAP_BIND_PASSWORD 를 준다");
        }
        List<String> 주소들 = properties.getUrls();
        if (주소들 == null || 주소들.isEmpty()) {
            throw new IllegalStateException("ldap.urls 가 비었다 — LDAP 주소를 하나 이상 준다");
        }
        for (String 주소 : 주소들) {
            if (!주소_모양이_맞다(주소)) {
                throw new IllegalStateException(
                        "ldap.urls 의 주소는 ldap://호스트[:포트] 또는 ldaps://호스트[:포트] 다 — 검색 베이스는 ldap.base-dn 에 준다(받은 값: %s)"
                                .formatted(주소));
            }
        }
        if (properties.isRequireTls()) {
            for (String 주소 : 주소들) {
                if (!"ldaps".equalsIgnoreCase(스킴(주소))) {
                    throw new IllegalStateException(
                            "ldap.require-tls 인데 ldap.urls 에 ldaps:// 가 아닌 주소가 있다 — StartTLS 는 지원하지 않는다(받은 값: %s)"
                                    .formatted(주소));
                }
            }
        }
    }

    /** 스킴이 {@code ldap}·{@code ldaps}(대소문자 무시)이고 호스트가 있으며 경로·질의·조각이 없다(경로는 비거나 {@code /}). */
    private static boolean 주소_모양이_맞다(String 주소) {
        if (주소 == null || 주소.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(주소.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String 스킴 = uri.getScheme();
        if (스킴 == null || (!"ldap".equalsIgnoreCase(스킴) && !"ldaps".equalsIgnoreCase(스킴))) {
            return false;
        }
        // getHost() 는 밑줄 든 호스트(dc_1.corp)에서 null 이라 쓰지 않는다 — 권한부가 있는지만 본다
        String 권한 = uri.getRawAuthority();
        if (권한 == null || 권한.isBlank()) {
            return false;
        }
        String 경로 = uri.getRawPath();
        return (경로 == null || 경로.isEmpty() || "/".equals(경로)) && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    private static String 스킴(String 주소) {
        int 끝 = 주소.indexOf("://");
        return 끝 < 0 ? "" : 주소.substring(0, 끝).trim();
    }

    @Bean
    public LdapContextSource ldapContextSource(LdapProperties properties) {
        return 컨텍스트_소스(properties, properties.getUrls());
    }

    /**
     * {@link #ldapContextSource} 와 같은 설정(검사·베이스·자격·JNDI 환경)에 주소 목록만 {@code urls} 인 컨텍스트 소스. {@code afterPropertiesSet()} 은 부르지
     * 않는다 — 빈은 스프링이 부른다.
     */
    static LdapContextSource 컨텍스트_소스(LdapProperties properties, List<String> urls) {
        설정을_검사한다(properties);
        LdapContextSource contextSource = new LdapContextSource();
        // 주소 목록은 JNDI 에 그대로 넘긴다 — 새 커넥션마다 앞에서부터 시도해 연결·접속 실패면 다음 주소로 간다(설계 2026-11-14 F2)
        contextSource.setUrls(urls.toArray(String[]::new));
        contextSource.setBase(properties.getBaseDn());
        contextSource.setUserDn(properties.getBindDn());
        contextSource.setPassword(properties.getBindPassword());
        contextSource.setBaseEnvironmentProperties(jndiEnvironment(properties));
        return contextSource;
    }

    /**
     * JNDI 환경. 타임아웃(밀리초 문자열)과, 식별 속성의 {@code objectGUID} 와 AD 기본 그룹의 {@code objectSid} 이진 선언을 싣는다. 페이징·범위 검색용
     * {@code SingleContextSource}({@link LdapTemplates#한_커넥션에서})도 이 컨텍스트 소스에서 커넥션을 얻으므로 같은 값을 탄다.
     *
     * <p>이진 선언({@code java.naming.ldap.attributes.binary})이 없으면 JNDI 는 {@code objectGUID} 바이트를 문자열로 뭉개
     * 16바이트를 되살릴 수 없다. 두 전략의 식별 속성 넷 중 이진인 것의 이름을 공백으로 이어 넣는다. {@code objectSid} 는 식별 속성과 상관없이
     * 늘 더한다 — 그룹의 RID 를 읽는 데({@link ActiveDirectoryPrimaryGroup}) 쓰고, 그 속성이 없는 디렉터리(OpenLDAP)에는 해가 없다.
     */
    static Map<String, Object> jndiEnvironment(LdapProperties properties) {
        Map<String, Object> 환경 = 타임아웃_환경(properties);
        환경.put("java.naming.ldap.attributes.binary", 이진_속성들(properties));
        return 환경;
    }

    /**
     * 접속·읽기 한도(밀리초 문자열)만 담은 JNDI 환경. 운영 결선과 테스트의 임베디드 LDAP 템플릿이 함께 쓴다 — 테스트에 한도가 없으면 응답이 오지 않는
     * 읽기 하나가 테스트를 끝없이 붙잡는다(2026-10-08 전체 test 가 connector-ldap 에서 한 시간 멈춘 적이 있다).
     */
    static Map<String, Object> 타임아웃_환경(LdapProperties properties) {
        Map<String, Object> 환경 = new LinkedHashMap<>();
        환경.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(properties.getConnectTimeout().toMillis()));
        환경.put("com.sun.jndi.ldap.read.timeout", String.valueOf(properties.getReadTimeout().toMillis()));
        return 환경;
    }

    /** 이진으로 읽을 이름들 — 식별 속성 넷 중 이진인 것과 AD 기본 그룹의 {@code objectSid}. 대소문자만 다른 이름은 처음 것만 남긴다. */
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
        // AD 기본 그룹(점검 M10)의 RID 를 읽으려면 그룹 objectSid 는 늘 이진이다 — 그 속성이 없는 디렉터리(OpenLDAP)에는 해가 없다
        처음나온것.putIfAbsent(ActiveDirectoryPrimaryGroup.OBJECT_SID.toLowerCase(Locale.ROOT), ActiveDirectoryPrimaryGroup.OBJECT_SID);
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
        설정을_검사한다(properties);
        // 계정 만료를 동기화 시각과 비교한다 — 앱의 Clock 빈(DynamoDbConfig)을 쓴다
        return "dit".equalsIgnoreCase(properties.getStrategy())
                ? new DitStrategy(properties, clock)
                : new GroupOfNamesStrategy(properties, clock);
    }

    @Bean
    public LdapDirectorySnapshotSource ldapDirectorySnapshotSource(
            LdapTemplate template, LdapMappingStrategy strategy, LdapProperties properties) {
        return new LdapDirectorySnapshotSource(시작_주소별_템플릿(properties, template), strategy, properties);
    }

    /**
     * 재시도 k 번째가 쓸 템플릿들(설계 2026-11-14 F6). {@code [첫_템플릿, k = 1 … n-1 번째 주소부터 돈 목록의 템플릿]} — 주소가 하나면 {@code [첫_템플릿]}.
     * 돌린 컨텍스트 소스는 스프링 빈이 아니라 여기서 {@code afterPropertiesSet()} 을 부른다. 상태 점검은 설정 순서의 {@code ldapContextSource} 빈을 그대로 쓴다.
     */
    static List<LdapTemplate> 시작_주소별_템플릿(LdapProperties properties, LdapTemplate 첫_템플릿) {
        List<String> 주소들 = properties.getUrls();
        List<LdapTemplate> 템플릿들 = new ArrayList<>();
        템플릿들.add(첫_템플릿);
        for (int k = 1; k < 주소들.size(); k++) {
            LdapContextSource 소스 = 컨텍스트_소스(properties, LdapUrls.돌린다(주소들, k));
            소스.afterPropertiesSet();
            템플릿들.add(LdapTemplates.configured(소스));
        }
        return List.copyOf(템플릿들);
    }
}
