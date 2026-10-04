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
 * <p><b>서버가 id 를 발급하므로</b> 요청은 조직도 아이디로 만들고, 보낼 때 {@link ScimIdBook} 이 POST 응답의
 * id 로 번역한다. 조직도 아이디는 조직의 {@code externalId}, 직원의 {@code userName} 으로 간다 — 생성 요청이
 * 그 리소스의 조직도 아이디를 {@link ScimRequest#차트아이디()} 로 들고 있어야 응답의 id 를 묶을 수 있다.
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
     * 없는 조직을 가리키는 멤버가 생긴다. 서버가 id 를 발급하므로 {@link ScimIdBook} 은 아직 만들지
     * 않은 조직의 서버 id 를 모른다 — 하위 조직이 먼저 만들어져 있어야 부모 본문의 멤버 값이 서버 id 가 된다.
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
        return ScimRequest.post(USERS, scimUser(user), "직원 생성 " + user.id(), user.id());
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
        return ScimRequest.post(GROUPS, scimGroup(group), "조직 생성 " + group.id(), group.id());
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
     * 한 번에 여럿을 더한다. Entra 가 조직 멤버를 더하는 모양이다 — 서버가 id 를 발급하므로 조직을 멤버 없이
     * 먼저 만들고 직원을 만든 다음 이 PATCH 로 멤버를 채운다.
     */
    public static ScimRequest 멤버들추가(String orgCode, List<MemberRef> members) {
        return ScimRequest.patch(GROUPS + "/" + orgCode,
                patch(new ScimOperation("add", "members", members.stream()
                        .map(ScimRequestRenderer::scimMember)
                        .toList())),
                "멤버 " + members.size() + "명 추가 " + orgCode);
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
        // 조직도 아이디는 userName 으로 간다. id 를 따로 실어 보내지 않는 것은 서버가 발급하는
        // 값을 클라이언트가 정하는 모양이 되지 않게 하기 위해서다(본문의 id 는 서버가 무시한다).
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
                // 아이디 순으로 — Set 의 순회 순서는 JVM 실행마다 달라, 그대로 두면 같은 조직도로
                // 만든 시드 파일의 바이트가 매번 다르다
                group.members().stream()
                        .sorted(Comparator.comparing(MemberRef::id)
                                .thenComparing(MemberRef::type))
                        .map(ScimRequestRenderer::scimMember)
                        .toList(),
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
