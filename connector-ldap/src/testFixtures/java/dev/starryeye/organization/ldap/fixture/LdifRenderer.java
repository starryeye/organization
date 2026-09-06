package dev.starryeye.organization.ldap.fixture;

import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;

/**
 * 조직도를 {@code groupOfNames} 형태의 LDIF 로 옮긴다.
 *
 * <p>DN 규칙은 {@code GroupOfNamesStrategy} 가 {@code externalId} 를 재구성하는 방식과
 * <b>같아야 한다</b> — 전략은 검색 베이스와 식별 속성으로 DN 을 조립하므로, 여기서 다른
 * 모양으로 심으면 읽어들인 {@code externalId} 가 심은 것과 어긋난다. 그래서 DN 조립을
 * {@link #userDn}/{@link #groupDn} 한 곳에 모아두고, 시나리오가 LDAP 을 직접 수정할 때도
 * 이 메서드를 쓰게 한다.
 *
 * <p>LDIF 특수 문법은 피한다. 값이 공백이나 {@code :} 로 시작하면 base64 로 감싸야 하는데,
 * 조직도 생성기가 만드는 값에는 그런 것이 없다 — 대신 그런 값이 들어오면
 * {@link IllegalArgumentException} 으로 즉시 깨뜨려, 조용히 잘못 심는 일이 없게 한다.
 */
public final class LdifRenderer {

    public static final String USER_OU = "ou=people";
    public static final String GROUP_OU = "ou=groups";

    private final String baseDn;

    public LdifRenderer(String baseDn) {
        this.baseDn = baseDn;
    }

    public String userDn(String userId) {
        return "uid=" + userId + "," + USER_OU + "," + baseDn;
    }

    public String groupDn(String orgCode) {
        return "cn=" + orgCode + "," + GROUP_OU + "," + baseDn;
    }

    /** 조직도 전체를 LDIF 한 덩이로. 서버 최초 임포트가 이것을 먹는다. */
    public String render(OrgChart chart) {
        StringBuilder sb = new StringBuilder(4 << 20);
        골격을_쓴다(sb);
        for (DirectoryUser user : chart.snapshot().users().values()) {
            사람을_쓴다(sb, user);
        }
        for (DirectoryGroup group : chart.snapshot().groups().values()) {
            조직을_쓴다(sb, group);
        }
        return sb.toString();
    }

    private void 골격을_쓴다(StringBuilder sb) {
        String dc = baseDn.split(",")[0].substring("dc=".length());
        attr(sb, "dn", baseDn);
        attr(sb, "objectClass", "top");
        attr(sb, "objectClass", "domain");
        attr(sb, "dc", dc);
        sb.append('\n');

        for (String ou : new String[]{USER_OU, GROUP_OU}) {
            attr(sb, "dn", ou + "," + baseDn);
            attr(sb, "objectClass", "organizationalUnit");
            attr(sb, "ou", ou.substring("ou=".length()));
            sb.append('\n');
        }
    }

    private void 사람을_쓴다(StringBuilder sb, DirectoryUser user) {
        attr(sb, "dn", userDn(user.id()));
        attr(sb, "objectClass", "inetOrgPerson");
        attr(sb, "uid", user.id());
        // inetOrgPerson 은 cn 과 sn 을 요구한다. 표시명은 displayName 에서 오므로
        // cn 은 식별자 그대로 둔다 — 전략의 폴백 순서(displayName → cn → uid)를
        // 사람 이름이 아닌 값으로 덮지 않기 위해서다.
        attr(sb, "cn", user.id());
        attr(sb, "sn", user.id());
        attr(sb, "displayName", user.displayName());
        attr(sb, "mail", user.email());
        sb.append('\n');
    }

    private void 조직을_쓴다(StringBuilder sb, DirectoryGroup group) {
        attr(sb, "dn", groupDn(group.id()));
        attr(sb, "objectClass", "groupOfNames");
        attr(sb, "cn", group.id());
        attr(sb, "description", group.displayName());
        for (MemberRef member : group.members()) {
            attr(sb, "member", member.type() == MemberType.GROUP
                    ? groupDn(member.id())
                    : userDn(member.id()));
        }
        // groupOfNames 는 member 를 최소 하나 요구한다. 빈 조직은 스키마 검사를 끈
        // 서버에서만 이 형태로 살아남는다 — 시나리오의 빈 델타 경로가 그것을 노린다.
        sb.append('\n');
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
