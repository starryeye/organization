package dev.starryeye.organization.ldap.fixture;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 테스트용 임베디드 LDAP(UnboundID)의 리스너와 접속 주소. 리스너는 127.0.0.1 에만 뜨고, 접속도 127.0.0.1 로 한다.
 *
 * <p>모든 주소(`*`)에 뜨면 macOS 는 같은 포트 번호를 127.0.0.1 전용 리스너(IntelliJ 등)와 함께 쓰게 두고, 127.0.0.1 연결은 전용 쪽이 받는다 —
 * 바인드 요청이 엉뚱한 프로세스로 가 "LDAP connection has been closed" 로 끊기던 간헐 실패다. 127.0.0.1 에 뜨면 쓰이는 포트에는 뜨지 못하므로
 * 포트 0 이면 운영체제가 겹치지 않는 포트를 고른다. 테스트 서버·컨테이너를 127.0.0.1 로 묶은 것(core testFixtures {@code Containers})과 같은 이유다.
 */
public final class EmbeddedLdapListener {

    private static final InetAddress 루프백;

    static {
        try {
            루프백 = InetAddress.getByAddress("localhost", new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private EmbeddedLdapListener() {
    }

    /** 127.0.0.1 의 빈 포트에 뜨는 리스너. */
    public static InMemoryListenerConfig 리스너(String 이름) throws LDAPException {
        return 리스너(이름, 0);
    }

    /** 127.0.0.1 의 정한 포트에 뜨는 리스너. 그 포트를 남이 쓰고 있으면 {@code startListening} 이 실패한다. */
    public static InMemoryListenerConfig 리스너(String 이름, int 포트) throws LDAPException {
        return InMemoryListenerConfig.createLDAPConfig(이름, 루프백, 포트, null);
    }

    /** 리스너가 뜬 곳의 접속 주소 — {@code localhost} 가 아니라 127.0.0.1 이다(이름이 다른 주소로 풀려도 엉뚱한 곳에 붙지 않게). */
    public static String 접속_주소(InMemoryDirectoryServer server) {
        return "ldap://127.0.0.1:" + server.getListenPort();
    }
}
