package dev.starryeye.organization.ldap.fixture;

import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 같은 조직도를 <b>DIT</b> 형식으로 옮긴다 — 계층이 {@code member} 속성이 아니라
 * <b>dn 경로 자체</b>로 표현된다.
 *
 * <p>{@link LdifRenderer} 와 짝이다. 두 렌더러가 같은 조직도에서 전혀 다른 모양의 디렉터리를
 * 만들고, 두 전략이 그것을 읽어 <b>같은 스냅샷</b>에 도달해야 한다. 그것이 이 프로젝트가
 * 매핑 전략을 둘 두는 이유이고, 규모에서 확인되지 않으면 둘 중 하나는 믿을 수 없다.
 *
 * <p><b>겸직은 표현할 수 없다.</b> 직원 엔트리는 ou 하나 아래에만 살 수 있다. 겸직이 남아
 * 있는 조직도를 넘기면 조용히 한쪽을 버리는 대신 즉시 깨뜨린다 — 그렇게 버리면 검증이
 * 형식의 한계를 <b>구현의 결함</b>으로 보고하게 되고, 그 오진을 쫓는 데 시간이 든다.
 * {@code OrgChartEditor.겸직을_모두_푼다()} 로 먼저 푼 조직도를 넘겨야 한다.
 */
public final class DitLdifRenderer {

    private final String baseDn;
    private final String 루트조직;

    /**
     * @param 루트조직 트리의 꼭대기가 될 조직코드. {@code ldap.dit.root-dn} 이
     *              {@code ou=<이 코드>} 여야 한다 — 전략의 검색 베이스가 그것이다
     */
    public DitLdifRenderer(String baseDn, String 루트조직) {
        this.baseDn = baseDn;
        this.루트조직 = 루트조직;
    }

    public String rootDn() {
        return "ou=" + 루트조직;
    }

    public String render(OrgChart chart) {
        겸직이_없는지_확인한다(chart);
        Map<String, String> dnByCode = 조직dn을_계산한다(chart);

        StringBuilder sb = new StringBuilder(4 << 20);
        attr(sb, "dn", baseDn);
        attr(sb, "objectClass", "top");
        attr(sb, "objectClass", "domain");
        attr(sb, "dc", baseDn.split(",")[0].substring("dc=".length()));
        sb.append('\n');

        // 부모가 자식보다 먼저 나와야 한다 — LDIF 임포트는 부모 없는 엔트리를 거부한다.
        // dnByCode 를 너비 우선으로 만들었으므로 그 순서가 곧 그 보장이다.
        for (Map.Entry<String, String> entry : dnByCode.entrySet()) {
            DirectoryGroup group = chart.snapshot().groups().get(entry.getKey());
            attr(sb, "dn", entry.getValue());
            attr(sb, "objectClass", "organizationalUnit");
            attr(sb, "ou", group.id());
            attr(sb, "description", group.displayName());
            sb.append('\n');
        }

        for (Map.Entry<String, String> entry : dnByCode.entrySet()) {
            DirectoryGroup group = chart.snapshot().groups().get(entry.getKey());
            for (MemberRef member : group.members()) {
                if (member.type() != MemberType.USER) {
                    continue;
                }
                DirectoryUser user = chart.snapshot().users().get(member.id());
                attr(sb, "dn", "uid=" + user.id() + "," + entry.getValue());
                attr(sb, "objectClass", "inetOrgPerson");
                attr(sb, "uid", user.id());
                attr(sb, "cn", user.id());
                attr(sb, "sn", user.id());
                attr(sb, "displayName", user.displayName());
                attr(sb, "mail", user.email());
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 루트에서 너비 우선으로 내려가며 각 조직의 dn 을 만든다.
     *
     * <p>루트에서 닿지 않는 조직 — 부모가 지워져 고아가 된 것 — 은 <b>넣지 않는다.</b>
     * DIT 에는 그것을 놓을 자리가 없기 때문이다. groupOfNames 는 고아 조직을 그대로
     * 표현할 수 있으므로, 두 형식이 같은 조직도를 담을 수 있는 범위가 여기서 갈린다.
     */
    private Map<String, String> 조직dn을_계산한다(OrgChart chart) {
        Map<String, String> dnByCode = new LinkedHashMap<>();
        dnByCode.put(루트조직, "ou=" + 루트조직 + "," + baseDn);

        Deque<String> 남은것 = new ArrayDeque<>();
        남은것.add(루트조직);
        while (!남은것.isEmpty()) {
            String 부모 = 남은것.poll();
            for (String 자식 : chart.자식조직들(부모)) {
                if (dnByCode.containsKey(자식)) {
                    continue;
                }
                dnByCode.put(자식, "ou=" + 자식 + "," + dnByCode.get(부모));
                남은것.add(자식);
            }
        }

        int 못담은것 = chart.snapshot().groups().size() - dnByCode.size();
        if (못담은것 > 0) {
            throw new IllegalArgumentException(
                    "루트 '%s' 에서 닿지 않는 조직이 %d개 있습니다. DIT 에는 고아 조직을 놓을 자리가 없습니다"
                            .formatted(루트조직, 못담은것));
        }
        return dnByCode;
    }

    private static void 겸직이_없는지_확인한다(OrgChart chart) {
        for (String userId : chart.snapshot().users().keySet()) {
            if (chart.직속조직들(userId).size() > 1) {
                throw new IllegalArgumentException(
                        "DIT 은 겸직을 표현할 수 없습니다. 먼저 겸직을_모두_푼다() 를 부르세요: " + userId);
            }
        }
    }

    private static void attr(StringBuilder sb, String name, String value) {
        if (value == null) {
            return;
        }
        if (value.startsWith(" ") || value.startsWith(":") || value.startsWith("<")
                || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(
                    "LDIF 로 그대로 쓸 수 없는 값입니다(base64 인코딩이 필요): " + name + "=" + value);
        }
        sb.append(name).append(": ").append(value).append('\n');
    }
}
