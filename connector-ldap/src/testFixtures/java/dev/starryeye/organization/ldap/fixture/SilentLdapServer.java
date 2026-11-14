package dev.starryeye.organization.ldap.fixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 접속은 받아 두기만 하고 아무것도 쓰지 않는 서버 — 바인드 응답이 오지 않는 죽은 도메인 컨트롤러를 흉내 낸다. 127.0.0.1 에만 뜬다 — 모든 주소에 뜨면
 * 127.0.0.1 전용 리스너(IntelliJ 등)와 포트를 함께 쓸 수 있고, 그쪽이 접속을 받아 닫으면 타임아웃이 아닌 다른 실패가 난다({@link EmbeddedLdapListener}
 * 와 같은 이유).
 *
 * <p>{@link #닫힌_주소()} 는 반대로 연결을 거절하는 주소다.
 */
public final class SilentLdapServer implements AutoCloseable {

    private final ServerSocket 서버;
    private final List<Socket> 받은_접속 = new CopyOnWriteArrayList<>();

    private SilentLdapServer(ServerSocket 서버) {
        this.서버 = 서버;
    }

    /** 127.0.0.1 의 빈 포트에서 접속을 받기 시작한다. */
    public static SilentLdapServer 연다() throws IOException {
        SilentLdapServer 침묵 = new SilentLdapServer(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
        Thread 받기 = new Thread(() -> {
            try {
                while (true) {
                    침묵.받은_접속.add(침묵.서버.accept());
                }
            } catch (IOException ignored) {
                // 서버 소켓이 닫혔다
            }
        });
        받기.setDaemon(true);
        받기.start();
        return 침묵;
    }

    public String 접속_주소() {
        return "ldap://127.0.0.1:" + 서버.getLocalPort();
    }

    /** 지금까지 받은 접속 수 — 시도마다 연결 하나가 생기는지 센다. */
    public int 받은_접속_수() {
        return 받은_접속.size();
    }

    /** 빈 포트를 잡았다 놓은 주소 — 그 포트에 듣는 이가 없어 연결이 거절된다. */
    public static String 닫힌_주소() throws IOException {
        try (ServerSocket 소켓 = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return "ldap://127.0.0.1:" + 소켓.getLocalPort();
        }
    }

    @Override
    public void close() throws IOException {
        서버.close();
        for (Socket 접속 : 받은_접속) {
            접속.close();
        }
    }
}
