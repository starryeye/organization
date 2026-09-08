package dev.starryeye.organization.scim.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 최초 싱크 요청 시퀀스를 <b>파일</b>로 떨군다. 사람이 실제 앱에 직접 쏴 보기 위한 것이다.
 *
 * <p>LDAP 은 원천 디렉터리를 띄우면 앱이 알아서 읽어 가지만, SCIM 은 <b>우리가 받는 쪽</b>이라
 * 누군가 IdP 역할로 요청을 보내 줘야 한다. 자동화 테스트는 그 역할을 테스트 코드가 하는데,
 * 실제 앱을 띄워 두고 만져 보려면 밖에서 쏠 수단이 필요하다.
 *
 * <p>NDJSON 한 줄이 요청 하나다 — {@code {"method":..,"path":..,"body":..}}.
 * 스트리밍으로 읽어 순차로 보내면 되고, 중간에 끊긴 지점부터 이어 보내기도 쉽다.
 *
 * <p>실행: {@code ./gradlew :connector-scim:generateScimSeed}
 */
public final class ScimSeedWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ScimSeedWriter() {
    }

    public static void main(String[] args) throws IOException {
        Path 출력 = Path.of(args.length > 0 ? args[0] : "docker/scim").resolve("initial-sync.ndjson");
        Files.createDirectories(출력.getParent());

        OrgChart chart = OrgChartFixture.오천명();
        List<ScimRequest> requests = ScimRequestRenderer.최초싱크(chart);

        StringBuilder sb = new StringBuilder(8 << 20);
        for (ScimRequest request : requests) {
            ObjectNode line = JSON.createObjectNode();
            line.put("method", request.method());
            line.put("path", request.path());
            line.set("body", JSON.valueToTree(request.body()));
            line.put("설명", request.설명());
            sb.append(JSON.writeValueAsString(line)).append('\n');
        }
        Files.writeString(출력, sb.toString(), StandardCharsets.UTF_8);

        System.out.printf("SCIM 최초 싱크 → %s (%d건 / %.1f MB / 직원 %d / 조직 %d)%n",
                출력, requests.size(), sb.length() / 1024.0 / 1024.0,
                chart.snapshot().users().size(), chart.snapshot().groups().size());
        System.out.println("""

                다음 단계:
                  docker compose up -d openfga dynamodb-local
                  ./gradlew :app-scim:bootRun
                  python3 docker/scim/replay.py            # 순차로 쏜다
                """);
    }
}
