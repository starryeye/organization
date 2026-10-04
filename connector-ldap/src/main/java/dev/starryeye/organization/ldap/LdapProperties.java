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

    /**
     * 접속 타임아웃. 이보다 오래 걸리면 실패한다(JNDI {@code com.sun.jndi.ldap.connect.timeout}).
     *
     * <p>TCP 접속뿐 아니라 <b>인증(bind) 응답 대기</b>도 이 값이 잰다 — JNDI LDAP provider 는 bind 응답을
     * {@link #readTimeout} 이 아니라 이 값으로 기다린다(JDK-8194264). {@code bindDn} 이 설정된 컨텍스트 생성은
     * 곧 동기 bind 라, 인증 단계가 죽은 연결에 물리는 경우는 이 값이 끊는다.
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * 응답 타임아웃. <b>인증(bind) 이후</b>, 요청 뒤 응답을 이보다 오래 못 받으면 실패한다(JNDI
     * {@code com.sun.jndi.ldap.read.timeout}) — 예를 들어 페이징 중 다음 페이지 응답을 기다리는 동안.
     *
     * <p>없으면 JNDI 는 응답이 올 때까지 기다린다 — 조용히 죽은 연결 하나에 회차가 끝나지 않아 작업 락이 안 풀리고, 이후 매일 동기화가
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
        /**
         * 직원 검색 필터(RFC 4515). AD 는 사람만 고르는 {@code (&(objectCategory=person)(objectClass=user))} — {@code user} 는 {@code computer} 의 상위
         * 클래스라 {@code (objectClass=user)} 만 쓰면 컴퓨터 계정까지 직원이 된다(점검 M12). 필터 문자열은 그대로 서버에 간다
         */
        private String userFilter = "(objectClass=inetOrgPerson)";
        /**
         * 직원 id. 불변 id(설계 2026-10-04 §4.1) — 서버가 엔트리마다 만들어 이름이 바뀌어도 유지하는 {@code entryUUID} 가 기본이다.
         * AD 는 {@code objectGUID}. 이름 기반(uid/employeeNumber)도 쓸 수 있지만 개명이 삭제+생성이다.
         */
        private String userIdAttribute = "entryUUID";
        /** 직원 userName 으로 쓸 로그인 속성. 원본 값 그대로 쓴다. AD 는 {@code sAMAccountName}. 값이 없으면 식별 값으로 대신한다 */
        private String userLoginAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
        private String groupSearchBase = "ou=groups";
        /** 그룹 검색 필터(RFC 4515). AD 는 {@code (objectClass=group)} */
        private String groupFilter = "(objectClass=groupOfNames)";
        /** 조직 id. 불변 id(설계 2026-10-04 §4.1). AD 는 {@code objectGUID}. 이름 기반(cn)도 쓸 수 있지만 개명이 삭제+생성이다 */
        private String groupIdAttribute = "entryUUID";
        /** 조직명. LDAP 그룹에는 표시명 표준 속성이 없어 description 을 쓴다. 없으면 DN 의 첫 RDN 값으로 대신한다 */
        private String groupNameAttribute = "description";
        private String memberAttribute = "member";
    }

    @Getter
    @Setter
    public static class Dit {
        private String rootDn = "ou=company";
        /**
         * 조직 검색 필터(RFC 4515). AD 의 기본 컨테이너({@code CN=Users} — OU 가 아니다)도 조직으로 읽으려면
         * {@code (|(objectClass=organizationalUnit)(&(objectClass=container)(cn=Users)))}. 맨 {@code (objectClass=container)} 를 더하면
         * {@code root-dn} 이 도메인 루트일 때 {@code CN=System}·GPO 같은 시스템 컨테이너까지 조직이 된다
         */
        private String orgUnitFilter = "(objectClass=organizationalUnit)";
        /** 조직 id. 불변 id(설계 2026-10-04 §4.1). AD 는 {@code objectGUID}. 이름 기반(ou)도 쓸 수 있지만 개명이 삭제+생성이다 */
        private String groupIdAttribute = "entryUUID";
        /** 조직명. 없으면 DN 의 첫 RDN 값으로 대신한다 */
        private String groupNameAttribute = "description";
        /** 직원 검색 필터(RFC 4515). AD 는 {@code (&(objectCategory=person)(objectClass=user))} */
        private String userFilter = "(objectClass=inetOrgPerson)";
        /** 직원 id. 불변 id(설계 2026-10-04 §4.1). AD 는 {@code objectGUID}. 이름 기반(uid)도 쓸 수 있지만 개명이 삭제+생성이다 */
        private String userIdAttribute = "entryUUID";
        /** 직원 userName 으로 쓸 로그인 속성. 원본 값 그대로 쓴다. AD 는 {@code sAMAccountName}. 값이 없으면 식별 값으로 대신한다 */
        private String userLoginAttribute = "uid";
        private String userNameAttribute = "displayName";
        private String userMailAttribute = "mail";
    }
}
