package dev.starryeye.organization.ldap.strategy;

import java.util.stream.Stream;

/**
 * 두 전략이 직원 엔트리에서 읽는 <b>고정 속성</b> — 설정으로 바뀌지 않는 것들이다. 설정으로 바뀌는 것(식별·로그인·표시명·메일 속성)은
 * 전략마다 설정 객체에서 꺼내 {@link #요청}에 넘긴다. 요청하지 않은 속성은 서버가 보내지 않으므로(설계 2026-10-04 §4.3) 매퍼가 읽는 속성과
 * 요청 목록이 어긋나면 안 된다 — 한 곳에 둬서 두 전략이 같이 바뀐다.
 */
final class UserAttributes {

    /** 일반 이름. 표시명 속성이 없을 때 표시명을 대신한다. */
    static final String CN = "cn";

    /** 계정 상태(AD 둘, ppolicy 하나 — 운영 속성이라 이름을 대야 온다)와 이름 여섯 칸 중 직원에게 달린 넷. */
    private static final String[] 고정 = {
            CN,
            AccountStatus.USER_ACCOUNT_CONTROL, AccountStatus.ACCOUNT_EXPIRES, AccountStatus.PWD_ACCOUNT_LOCKED_TIME,
            LdapPersonName.SN, LdapPersonName.GIVEN_NAME, LdapPersonName.MIDDLE_NAME, LdapPersonName.GENERATION_QUALIFIER};

    private UserAttributes() {
    }

    /**
     * 직원 검색이 요청하는 속성 — 설정으로 정한 속성에 고정 속성을 더한다. 비었거나 겹친 이름은 뺀다
     * ({@link PagedLdapSearch#속성목록}).
     */
    static String[] 요청(String... 설정한_속성들) {
        return PagedLdapSearch.속성목록(Stream.concat(Stream.of(설정한_속성들), Stream.of(고정)).toArray(String[]::new));
    }
}
