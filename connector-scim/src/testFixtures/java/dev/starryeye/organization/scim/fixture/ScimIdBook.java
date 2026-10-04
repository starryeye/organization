package dev.starryeye.organization.scim.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimOperation;
import dev.starryeye.organization.scim.dto.ScimPatchOp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 조직도 아이디 → 서버 발급 id(설계 2026-10-04 §3.1). SCIM 은 서버가 id 를 정하므로 테스트는 POST 응답의 id 를 받아 두었다가
 * 뒤 요청의 경로·멤버 값과 기대값·Check 를 서버 id 로 바꾼다.
 *
 * <p><b>"모르는 아이디" 는 POST 로 만든 적이 없는 아이디다.</b> 그런 아이디는 그대로 둔다. <b>지운 리소스는 표에 남는다</b> —
 * 지운 뒤에도 같은 서버 id 로 404 를 묻고 튜플이 없음을 묻는다(S7·S14). 빠지면 조직도 아이디 그대로 물어 무조건 404·false 로 공허하게 통과한다.
 *
 * <p>조직도·렌더러·시나리오 코드는 조직도 아이디로 계속 쓴다. 요청은 <b>보낼 때</b>, 기대값·Check 는 <b>볼 때</b> 번역한다.
 *
 * <p>직원과 조직의 조직도 아이디는 서로 달라야 한다(한 표에 같이 담긴다). 겹치면 {@link #기록한다} 가 멈춘다.
 */
public final class ScimIdBook {

    private static final Pattern 멤버필터 = Pattern.compile("^members\\[value eq \"(.*)\"]$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> 서버아이디 = new ConcurrentHashMap<>();

    /** 같은 조직도 아이디를 같은 서버 id 로 다시 기록하는 것은 괜찮다. 다른 서버 id 로 기록하면 하네스 오류다. */
    public void 기록한다(String 차트아이디, String 발급받은아이디) {
        String 전 = 서버아이디.putIfAbsent(차트아이디, 발급받은아이디);
        if (전 != null && !전.equals(발급받은아이디)) {
            throw new IllegalStateException("조직도 아이디 %s 가 두 서버 id 로 기록됐다: %s, %s".formatted(차트아이디, 전, 발급받은아이디));
        }
    }

    /**
     * 보낸 요청의 응답을 보고 기록한다. 생성(POST)이라 {@link ScimRequest#차트아이디()} 가 있고 응답이 201 이면 본문의 {@code id} 를
     * 그 조직도 아이디에 묶는다. 그 밖(다른 요청, 201 이 아닌 응답)은 본문을 읽지 않는다 — 실패 응답이 JSON 이 아니어도 여기서 멈추지 않는다.
     * 201 인데 본문에 {@code id} 가 없거나 JSON 이 아니면 어느 요청인지 담아 멈춘다.
     *
     * @param 응답본문 응답 본문 그대로의 문자열. 없으면 null
     */
    public void 기록한다(ScimRequest 보낸요청, int 상태, String 응답본문) {
        if (상태 != 201 || 보낸요청.차트아이디() == null) {
            return;
        }
        기록한다(보낸요청.차트아이디(), 응답의_아이디(보낸요청, 응답본문));
    }

    private static String 응답의_아이디(ScimRequest 보낸요청, String 응답본문) {
        String id = null;
        if (응답본문 != null && !응답본문.isBlank()) {
            try {
                JsonNode 노드 = JSON.readTree(응답본문).get("id");
                id = 노드 == null || 노드.isNull() ? null : 노드.asText();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("201 응답 본문이 JSON 이 아니다: " + 보낸요청.설명(), e);
            }
        }
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("201 응답에 id 가 없다: " + 보낸요청.설명());
        }
        return id;
    }

    /** 조직도 아이디의 서버 id. POST 로 만든 적이 없어 모르면 그대로 돌려준다. 지운 리소스의 것은 남아 있다. */
    public String 서버(String 차트아이디) {
        return 서버아이디.getOrDefault(차트아이디, 차트아이디);
    }

    public ScimRequest 번역한다(ScimRequest request) {
        return new ScimRequest(request.method(), 경로(request.path()), 본문(request.body()), request.설명(), request.차트아이디());
    }

    public RelationTuple 번역한다(RelationTuple tuple) {
        return new RelationTuple(타입아이디(tuple.user()), tuple.relation(), 타입아이디(tuple.object()));
    }

    public OrgChart 번역한다(OrgChart chart) {
        return chart.아이디를_바꾼다(this::서버);
    }

    private String 경로(String path) {
        for (String 앞 : List.of(ScimRequestRenderer.USERS + "/", ScimRequestRenderer.GROUPS + "/")) {
            if (path.startsWith(앞)) {
                return 앞 + 서버(path.substring(앞.length()));
            }
        }
        return path;
    }

    private Object 본문(Object body) {
        if (body instanceof ScimGroup group && group.members() != null) {
            return new ScimGroup(group.schemas(), group.id(), group.externalId(), group.displayName(),
                    group.members().stream().map(this::멤버).toList(), group.meta());
        }
        if (body instanceof ScimPatchOp patch) {
            return new ScimPatchOp(patch.schemas(), patch.operations().stream().map(this::연산).toList());
        }
        return body;
    }

    private ScimOperation 연산(ScimOperation operation) {
        String path = operation.path();
        if (path != null) {
            Matcher 필터 = 멤버필터.matcher(path);
            if (필터.matches()) {
                path = "members[value eq \"" + 서버(필터.group(1)) + "\"]";
            }
        }
        Object value = operation.value() instanceof List<?> list
                ? list.stream().map(element -> element instanceof ScimMember member ? 멤버(member) : element).toList()
                : operation.value();
        return new ScimOperation(operation.op(), path, value);
    }

    private ScimMember 멤버(ScimMember member) {
        return new ScimMember(서버(member.value()), member.type(), member.display());
    }

    /** {@code user:kim} → {@code user:<서버 id>}. 접두사가 없으면 아이디만 본다. */
    private String 타입아이디(String typed) {
        int 구분 = typed.indexOf(':');
        return 구분 < 0 ? 서버(typed) : typed.substring(0, 구분 + 1) + 서버(typed.substring(구분 + 1));
    }
}
