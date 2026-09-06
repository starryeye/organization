package dev.starryeye.organization.scim.fixture;

import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.scim.ScimSchemas;
import dev.starryeye.organization.scim.dto.ScimEmail;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 같은 조직도를 SCIM 요청 시퀀스로 옮긴다. LDIF 렌더러와 짝이다 — 두 커넥터가 같은 조직도로
 * 같은 결과에 도달해야 한다.
 *
 * <p><b>조직코드는 {@code externalId} 로 간다.</b> {@code ScimMapper} 가 조직코드를
 * {@code externalId → id} 순으로 채택하기 때문이다(설계 §4.3). 직원 쪽은 반대로
 * {@code userName} 이 아이디의 원천이다. 이 두 규칙이 어긋나면 요청은 2xx 로 성공하는데
 * 튜플만 안 생긴다 — 가장 알아채기 어려운 실패라서 여기 한 곳에 모아 둔다.
 *
 * <p>멤버에는 {@code type} 을 항상 명시한다. 생략하면 서버가 현재상태로 <b>추정</b>하고,
 * 그 추정이 맞았는지 아닌지가 테스트 결과에 섞여 들어온다. 추정 경로 자체를 시험하는
 * 시나리오는 그 시나리오에서 따로 {@code type} 을 빼면 된다.
 */
public final class ScimRequestRenderer {

    public static final String USERS = "/scim/v2/Users";
    public static final String GROUPS = "/scim/v2/Groups";

    private ScimRequestRenderer() {
    }

    /**
     * 최초 싱크. 직원을 먼저, 그다음 조직을 <b>깊은 곳부터</b> 만든다.
     *
     * <p>깊은 곳부터인 이유: 조직은 하위 조직을 멤버로 참조하므로, 얕은 곳부터 만들면 아직
     * 없는 조직을 가리키는 멤버가 생긴다. 서버는 그것을 받아주지만(SCIM 은 늦게 도착하는
     * 리소스를 허용한다) 그 상태는 이 시나리오가 재려는 것이 아니다 — 참조 순서가 어긋난
     * 경우를 보고 싶으면 그것만 따로 시험하는 편이 낫다.
     *
     * <p><b>아이디로 한 번 더 정렬한다.</b> {@code DirectorySnapshot} 은 {@code Map.copyOf} 로
     * 굳으므로 순회 순서가 JVM 실행마다 달라진다. 5천 건짜리 재생 시퀀스가 실행마다 순서를
     * 바꾸면, 순서에 얽힌 실패는 재현되지 않고 성공은 우연일 수 있다.
     */
    public static List<ScimRequest> 최초싱크(OrgChart chart) {
        List<ScimRequest> requests = new ArrayList<>();
        chart.snapshot().users().values().stream()
                .sorted(Comparator.comparing(DirectoryUser::id))
                .forEach(user -> requests.add(직원생성(user)));
        chart.snapshot().groups().values().stream()
                .sorted(Comparator.comparingInt((DirectoryGroup group) ->
                                chart.조상들(group.id()).size()).reversed()
                        .thenComparing(DirectoryGroup::id))
                .forEach(group -> requests.add(조직생성(group)));
        return requests;
    }

    // ---------- 직원 ----------

    public static ScimRequest 직원생성(DirectoryUser user) {
        return ScimRequest.post(USERS, scimUser(user), "직원 생성 " + user.id());
    }

    /** PUT 은 전체 교체다. 보내지 않은 필드는 지워진다 — PATCH 와 갈라 보는 시나리오가 여기 붙는다. */
    public static ScimRequest 직원교체(DirectoryUser user) {
        return ScimRequest.put(USERS + "/" + user.id(), scimUser(user), "직원 교체 " + user.id());
    }

    public static ScimRequest 직원비활성(String userId) {
        return ScimRequest.patch(USERS + "/" + userId,
                patch(new ScimOperation("replace", "active", false)),
                "직원 비활성 " + userId);
    }

    public static ScimRequest 직원활성(String userId) {
        return ScimRequest.patch(USERS + "/" + userId,
                patch(new ScimOperation("replace", "active", true)),
                "직원 활성 " + userId);
    }

    public static ScimRequest 직원표시명변경(String userId, String 표시명) {
        return ScimRequest.patch(USERS + "/" + userId,
                patch(new ScimOperation("replace", "displayName", 표시명)),
                "직원 표시명 변경 " + userId);
    }

    public static ScimRequest 직원삭제(String userId) {
        return ScimRequest.delete(USERS + "/" + userId, "직원 삭제 " + userId);
    }

    // ---------- 조직 ----------

    public static ScimRequest 조직생성(DirectoryGroup group) {
        return ScimRequest.post(GROUPS, scimGroup(group), "조직 생성 " + group.id());
    }

    public static ScimRequest 조직교체(DirectoryGroup group) {
        return ScimRequest.put(GROUPS + "/" + group.id(), scimGroup(group), "조직 교체 " + group.id());
    }

    public static ScimRequest 멤버추가(String orgCode, MemberRef member) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("add", "members", List.of(scimMember(member)))),
                "멤버 추가 " + orgCode + " ← " + member.id());
    }

    /**
     * 필터로 한 명만 뺀다. 서버는 {@code members} 필터를 {@code remove} 에만 허용한다.
     */
    public static ScimRequest 멤버제거(String orgCode, String memberId) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("remove", "members[value eq \"" + memberId + "\"]", null)),
                "멤버 제거 " + orgCode + " → " + memberId);
    }

    /**
     * 필터 없는 {@code remove members} 는 <b>전원</b>을 지운다. 한 명만 지우려던 IdP 가
     * 필터를 빠뜨리면 조직이 통째로 비는데, 그 형태를 일부러 만들어 보는 시나리오용이다.
     */
    public static ScimRequest 멤버전체제거(String orgCode) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("remove", "members", null)),
                "멤버 전체 제거 " + orgCode);
    }

    public static ScimRequest 멤버전체교체(String orgCode, List<MemberRef> members) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("replace", "members", members.stream()
                        .map(ScimRequestRenderer::scimMember)
                        .toList())),
                "멤버 전체 교체 " + orgCode);
    }

    public static ScimRequest 조직표시명변경(String orgCode, String 표시명) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("replace", "displayName", 표시명)),
                "조직 표시명 변경 " + orgCode);
    }

    public static ScimRequest 조직삭제(String orgCode) {
        return ScimRequest.delete(GROUPS + "/" + orgCode, "조직 삭제 " + orgCode);
    }

    // ---------- 본문 조립 ----------

    private static ScimUser scimUser(DirectoryUser user) {
        // userName 이 곧 아이디의 원천이다(ScimMapper). id 를 따로 실어 보내지 않는 것은
        // 서버가 발급하는 값을 클라이언트가 정하는 모양이 되지 않게 하기 위해서다.
        return new ScimUser(
                List.of(ScimSchemas.USER),
                null,
                null,
                user.userName(),
                null,
                user.displayName(),
                user.email() == null ? null : List.of(new ScimEmail(user.email(), "work", true)),
                user.active(),
                null);
    }

    private static ScimGroup scimGroup(DirectoryGroup group) {
        return new ScimGroup(
                List.of(ScimSchemas.GROUP),
                null,
                group.id(),
                group.displayName(),
                group.members().stream().map(ScimRequestRenderer::scimMember).toList(),
                null);
    }

    private static ScimMember scimMember(MemberRef member) {
        return new ScimMember(member.id(),
                member.type() == MemberType.GROUP ? "Group" : "User",
                null);
    }

    private static ScimPatchOp patch(ScimOperation operation) {
        return new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(operation));
    }
}
