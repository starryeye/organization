package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 기대 조직도를 편집한다. 시나리오가 데이터를 바꿀 때마다 <b>기대값도 같이</b> 움직여야
 * 하기 때문이다.
 *
 * <p>커넥터 쪽(LDIF·SCIM 요청)에 같은 편집을 가하는 짝이 각 커넥터 모듈에 있다. 편집을 한
 * 곳에서만 하고 다른 쪽을 손으로 맞추면, 검증이 실패했을 때 <b>구현이 틀린 건지 기대값이
 * 틀린 건지</b> 알 수 없다 — 그 순간 하네스는 쓸모가 없어진다.
 *
 * <p>편집할 때마다 새 {@link OrgChart} 를 만든다. 시나리오는 직전 결과 위에서 이어지므로
 * 각 단계의 기대값을 따로 들고 있어야 "이 단계에서 처음 어긋났다" 를 말할 수 있다.
 */
public final class OrgChartEditor {

    private final Map<String, DirectoryUser> users;
    private final Map<String, DirectoryGroup> groups;
    private final Landmarks landmarks;

    private OrgChartEditor(OrgChart chart) {
        this.users = new LinkedHashMap<>(chart.snapshot().users());
        this.groups = new LinkedHashMap<>(chart.snapshot().groups());
        this.landmarks = chart.landmarks();
    }

    public static OrgChartEditor 편집한다(OrgChart chart) {
        return new OrgChartEditor(chart);
    }

    public OrgChart 완성() {
        return new OrgChart(new DirectorySnapshot(users, groups), landmarks);
    }

    // ---------- 직원 ----------

    public OrgChartEditor 직원을_넣는다(String orgCode, String userId, String 표시명, String 메일) {
        users.put(userId, new DirectoryUser(userId, null, userId, 표시명, 메일, true));
        멤버를_더한다(orgCode, MemberRef.user(userId));
        return this;
    }

    /** 직원 엔트리 자체가 사라진다 — 모든 조직의 멤버 목록에서도 빠진다. */
    public OrgChartEditor 직원을_지운다(String userId) {
        users.remove(userId);
        MemberRef ref = MemberRef.user(userId);
        groups.keySet().forEach(code -> 멤버를_뺀다(code, ref));
        return this;
    }

    public OrgChartEditor 직원속성을_바꾼다(String userId, String 표시명, String 메일) {
        DirectoryUser 원본 = require(users.get(userId), "직원", userId);
        users.put(userId, new DirectoryUser(원본.id(), 원본.externalId(), 원본.userName(),
                표시명, 메일, 원본.active()));
        return this;
    }

    public OrgChartEditor 직원을_옮긴다(String userId, String 옛조직, String 새조직) {
        멤버를_뺀다(옛조직, MemberRef.user(userId));
        멤버를_더한다(새조직, MemberRef.user(userId));
        return this;
    }

    public OrgChartEditor 겸직을_더한다(String userId, String orgCode) {
        멤버를_더한다(orgCode, MemberRef.user(userId));
        return this;
    }

    public OrgChartEditor 겸직을_푼다(String userId, String orgCode) {
        멤버를_뺀다(orgCode, MemberRef.user(userId));
        return this;
    }

    // ---------- 조직 ----------

    public OrgChartEditor 조직을_넣는다(String code, String 이름, String 부모) {
        groups.put(code, new DirectoryGroup(code, null, 이름, Set.of()));
        멤버를_더한다(부모, MemberRef.group(code));
        return this;
    }

    /**
     * 조직 엔트리가 사라진다. <b>하위 조직은 남는다</b> — 아무도 참조하지 않게 되어 루트가
     * 된다. LDAP 에서 부모 엔트리가 지워졌을 때 실제로 벌어지는 일이고, 계층이 끊긴 상태를
     * 시스템이 어떻게 다루는지가 시나리오 L10 의 요점이다.
     */
    public OrgChartEditor 조직을_지운다(String code) {
        groups.remove(code);
        MemberRef ref = MemberRef.group(code);
        groups.keySet().forEach(parent -> 멤버를_뺀다(parent, ref));
        return this;
    }

    public OrgChartEditor 조직을_옮긴다(String code, String 옛부모, String 새부모) {
        멤버를_뺀다(옛부모, MemberRef.group(code));
        멤버를_더한다(새부모, MemberRef.group(code));
        return this;
    }

    public OrgChartEditor 조직명을_바꾼다(String code, String 이름) {
        DirectoryGroup 원본 = require(groups.get(code), "조직", code);
        groups.put(code, new DirectoryGroup(원본.id(), 원본.externalId(), 이름, 원본.members()));
        return this;
    }

    // ---------- 거들기 ----------

    private void 멤버를_더한다(String orgCode, MemberRef ref) {
        DirectoryGroup 원본 = require(groups.get(orgCode), "조직", orgCode);
        Set<MemberRef> members = new LinkedHashSet<>(원본.members());
        members.add(ref);
        groups.put(orgCode, new DirectoryGroup(원본.id(), 원본.externalId(), 원본.displayName(), members));
    }

    private void 멤버를_뺀다(String orgCode, MemberRef ref) {
        DirectoryGroup 원본 = groups.get(orgCode);
        if (원본 == null || !원본.members().contains(ref)) {
            return;
        }
        Set<MemberRef> members = new LinkedHashSet<>(원본.members());
        members.remove(ref);
        groups.put(orgCode, new DirectoryGroup(원본.id(), 원본.externalId(), 원본.displayName(), members));
    }

    /** 없는 대상을 조용히 넘기면 기대값이 틀린 채로 검증이 통과해 버린다. */
    private static <T> T require(T value, String 종류, String id) {
        if (value == null) {
            throw new IllegalArgumentException("조직도에 없는 %s 입니다: %s".formatted(종류, id));
        }
        return value;
    }
}
