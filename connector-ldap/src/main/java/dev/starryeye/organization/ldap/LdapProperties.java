package dev.starryeye.organization.ldap;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("ldap")
public class LdapProperties {

    private String url = "ldap://localhost:1389";
    private String baseDn = "dc=example,dc=com";
    private String bindDn;
    private String bindPassword;
    private int pageSize = 500;

    /**
     * LDAP 읽기 실패 시 재시도 횟수 (설계 §9). OpenFGA 어댑터와 같은 이름·같은 기본값을 쓴다.
     *
     * <p>재시도가 걸리는 것은 <b>예외</b>뿐이다. LDAP 이 성공적으로 잘못된 답을 주는 경우
     * (필터 오류로 0건을 반환하는 것 같은)는 정상 응답이라 여기 걸리지 않는다 — 그건
     * 삭제 가드가 잡을 일이고, 이 재시도가 그 경계를 흐리지 않는다.
     */
    private int maxRetries = 3;

    /** 접속 타임아웃. 이보다 오래 걸리면 실패한다(JNDI {@code com.sun.jndi.ldap.connect.timeout}). */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 응답 타임아웃. 요청 뒤 응답을 이보다 오래 못 받으면 실패한다(JNDI {@code com.sun.jndi.ldap.read.timeout}).
     *
     * <p>없으면 JNDI 는 응답이 올 때까지 기다린다 — 조용히 죽은 연결 하나에 회차가 끝나지 않아 실행 가드가 안 풀리고, 이후 매일 동기화가
     * 건너뛰어진다(점검 C5). AD 는 검색 하나를 최대 120초({@code MaxQueryDuration})까지 허용하므로, 서버가 정상적으로 오래 일하는 경우를
     * 먼저 끊지 않게 그보다 길게 둔다. 타임아웃은 일시 장애로 분류돼 {@link #maxRetries} 만큼 처음부터 다시 읽는다.
     */
    private Duration readTimeout = Duration.ofSeconds(150);

    /** group-of-names | dit */
    private String strategy = "group-of-names";

    private GroupOfNames groupOfNames = new GroupOfNames();
    private Dit dit = new Dit();

    @Getter
    @Setter
    public static class GroupOfNames {
        private String userSearchBase = "ou=people";
        private String userObjectClass = "inetOrgPerson";
        /** 직원 아이디. employeeNumber 등으로 교체 가능 */
        private String userIdAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
        private String groupSearchBase = "ou=groups";
        private String groupObjectClass = "groupOfNames";
        /** 조직코드 */
        private String groupIdAttribute = "cn";
        /** 조직명. LDAP 그룹에는 표시명 표준 속성이 없어 description 을 쓴다 */
        private String groupNameAttribute = "description";
        private String memberAttribute = "member";
    }

    @Getter
    @Setter
    public static class Dit {
        private String rootDn = "ou=company";
        private String orgUnitObjectClass = "organizationalUnit";
        /** 조직코드 */
        private String groupIdAttribute = "ou";
        /** 조직명. 없으면 조직코드로 대체 */
        private String groupNameAttribute = "description";
        private String userObjectClass = "inetOrgPerson";
        private String userIdAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
    }
}
