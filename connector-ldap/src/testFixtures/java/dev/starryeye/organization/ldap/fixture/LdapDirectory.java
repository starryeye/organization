package dev.starryeye.organization.ldap.fixture;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;

/**
 * 살아 있는 LDAP 서버를 시나리오가 바꿀 때 쓰는 손잡이.
 *
 * <p>{@code OrgChartEditor} 의 짝이다 — 같은 편집을 한쪽은 기대 조직도에, 한쪽은 실제 서버에
 * 가한다. 시나리오는 <b>두 편집을 나란히</b> 호출하고, 그다음 하네스가 둘이 만난다고 말하는지
 * 확인한다.
 *
 * <p>DN 조립은 {@link LdifRenderer} 것을 그대로 쓴다. 여기서 따로 문자열을 이으면 최초
 * 임포트와 이후 수정이 서로 다른 DN 을 쓰게 되고, 그 어긋남은 "왜 이 직원만 안 잡히지" 로
 * 나타나 원인을 찾기 어렵다.
 */
public final class LdapDirectory {

    private final LDAPInterface connection;
    private final LdifRenderer dn;

    public LdapDirectory(InMemoryDirectoryServer server, String baseDn) {
        this.connection = server;
        this.dn = new LdifRenderer(baseDn);
    }

    // ---------- 직원 ----------

    public void 직원을_넣는다(String orgCode, String userId, String 표시명, String 메일) {
        add(dn.userDn(userId),
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", userId),
                new Attribute("cn", userId),
                new Attribute("sn", userId),
                new Attribute("displayName", 표시명),
                new Attribute("mail", 메일));
        멤버를_더한다(orgCode, dn.userDn(userId));
    }

    /**
     * 엔트리를 지우고 <b>참조도 지운다</b>. 참조만 남기면 전략이 "사람도 그룹도 아니다" 로
     * 건너뛰는 다른 상황이 되어, 이 시나리오가 재려던 것과 달라진다.
     */
    public void 직원을_지운다(String userId, String... 속한조직들) {
        for (String orgCode : 속한조직들) {
            멤버를_뺀다(orgCode, dn.userDn(userId));
        }
        delete(dn.userDn(userId));
    }

    public void 직원속성을_바꾼다(String userId, String 표시명, String 메일) {
        modify(dn.userDn(userId),
                new Modification(ModificationType.REPLACE, "displayName", 표시명),
                new Modification(ModificationType.REPLACE, "mail", 메일));
    }

    public void 직원을_옮긴다(String userId, String 옛조직, String 새조직) {
        멤버를_뺀다(옛조직, dn.userDn(userId));
        멤버를_더한다(새조직, dn.userDn(userId));
    }

    public void 겸직을_더한다(String userId, String orgCode) {
        멤버를_더한다(orgCode, dn.userDn(userId));
    }

    public void 겸직을_푼다(String userId, String orgCode) {
        멤버를_뺀다(orgCode, dn.userDn(userId));
    }

    // ---------- 조직 ----------

    public void 조직을_넣는다(String code, String 이름, String 부모) {
        add(dn.groupDn(code),
                new Attribute("objectClass", "groupOfNames"),
                new Attribute("cn", code),
                new Attribute("description", 이름));
        멤버를_더한다(부모, dn.groupDn(code));
    }

    /** 하위 조직은 건드리지 않는다 — 참조가 사라져 루트가 되는 것이 이 편집의 요점이다. */
    public void 조직을_지운다(String code, String 부모) {
        if (부모 != null) {
            멤버를_뺀다(부모, dn.groupDn(code));
        }
        delete(dn.groupDn(code));
    }

    public void 조직을_옮긴다(String code, String 옛부모, String 새부모) {
        멤버를_뺀다(옛부모, dn.groupDn(code));
        멤버를_더한다(새부모, dn.groupDn(code));
    }

    public void 조직명을_바꾼다(String code, String 이름) {
        modify(dn.groupDn(code), new Modification(ModificationType.REPLACE, "description", 이름));
    }

    /** 순환을 만든다 — 자손을 조상의 부모로 붙인다. 시나리오 L16 이 쓴다. */
    public void 하위조직으로_붙인다(String 부모코드, String 자식코드) {
        멤버를_더한다(부모코드, dn.groupDn(자식코드));
    }

    // ---------- 원시 연산 ----------

    public void 멤버를_더한다(String orgCode, String memberDn) {
        modify(dn.groupDn(orgCode), new Modification(ModificationType.ADD, "member", memberDn));
    }

    public void 멤버를_뺀다(String orgCode, String memberDn) {
        modify(dn.groupDn(orgCode), new Modification(ModificationType.DELETE, "member", memberDn));
    }

    public String userDn(String userId) {
        return dn.userDn(userId);
    }

    public String groupDn(String orgCode) {
        return dn.groupDn(orgCode);
    }

    private void add(String entryDn, Attribute... attributes) {
        try {
            connection.add(entryDn, attributes);
        } catch (LDAPException e) {
            throw new IllegalStateException("엔트리 추가 실패: " + entryDn, e);
        }
    }

    private void delete(String entryDn) {
        try {
            connection.delete(entryDn);
        } catch (LDAPException e) {
            throw new IllegalStateException("엔트리 삭제 실패: " + entryDn, e);
        }
    }

    private void modify(String entryDn, Modification... modifications) {
        try {
            connection.modify(entryDn, modifications);
        } catch (LDAPException e) {
            throw new IllegalStateException("엔트리 수정 실패: " + entryDn, e);
        }
    }
}
