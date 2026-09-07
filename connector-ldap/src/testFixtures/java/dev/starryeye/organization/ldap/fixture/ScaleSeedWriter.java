package dev.starryeye.organization.ldap.fixture;

import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartEditor;
import dev.starryeye.organization.core.fixture.OrgChartFixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 규모 조직도를 <b>파일</b>로 떨군다. {@code docker-compose} 의 OpenLDAP 이 이것을 먹고
 * 5,000명짜리 디렉터리로 뜬다.
 *
 * <p>자동화 테스트는 임베디드 UnboundID 를 쓰므로 이 파일이 없어도 돈다. 이것은 <b>사람이
 * 직접 띄워 두드려 보기 위한</b> 것이다 — 실제 앱을 기동해 동기화하고 엔드포인트를 눌러
 * 보는 판이 없으면, 통과하는 테스트만 쌓이고 운영자가 실제로 무엇을 보게 되는지는 아무도
 * 모른다.
 *
 * <p>실행: {@code ./gradlew :connector-ldap:generateScaleSeed}
 */
public final class ScaleSeedWriter {

    private static final String BASE_DN = "dc=example,dc=com";

    private ScaleSeedWriter() {
    }

    public static void main(String[] args) throws IOException {
        // 전략마다 디렉터리를 나눈다. OpenLDAP 은 지정된 디렉터리의 LDIF 를 <b>전부</b>
        // 먹으므로, 한 곳에 두면 두 형식이 같은 dn 을 두고 충돌한다.
        Path 뿌리 = Path.of(args.length > 0 ? args[0] : "docker/ldap");

        OrgChart chart = OrgChartFixture.오천명();
        쓴다(뿌리.resolve("scale-groupofnames").resolve("scale.ldif"),
                new LdifRenderer(BASE_DN).render(chart), chart, "groupOfNames");

        // DIT 은 겸직을 표현하지 못한다 — 형식의 한계이므로 먼저 푼다
        OrgChart 단일소속 = OrgChartEditor.편집한다(chart).겸직을_모두_푼다().완성();
        쓴다(뿌리.resolve("scale-dit").resolve("scale.ldif"),
                new DitLdifRenderer(BASE_DN, chart.landmarks().회사()).render(단일소속),
                단일소속, "DIT");

        System.out.println("""

                다음 단계 (groupOfNames 기준):
                  LDAP_SEED_DIR=./docker/ldap/scale-groupofnames docker compose up -d
                  ./gradlew :app-ldap:bootRun
                  curl -XPOST localhost:8081/admin/sync/full
                """);
    }

    private static void 쓴다(Path path, String ldif, OrgChart chart, String 전략) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, ldif, StandardCharsets.UTF_8);
        System.out.printf("%s → %s (%.1f MB / 조직 %d / 직원 %d / 멤버십 %d)%n",
                전략, path, ldif.length() / 1024.0 / 1024.0,
                chart.snapshot().groups().size(), chart.snapshot().users().size(),
                chart.멤버십수());
    }
}
