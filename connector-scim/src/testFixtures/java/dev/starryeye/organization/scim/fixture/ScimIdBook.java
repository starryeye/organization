package dev.starryeye.organization.scim.fixture;

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
 * 뒤 요청의 경로·멤버 값과 기대값·Check 를 서버 id 로 바꾼다. 모르는 아이디(아직 없거나 지워진 리소스)는 그대로 둔다.
 *
 * <p>조직도·렌더러·시나리오 코드는 조직도 아이디로 계속 쓴다. 요청은 <b>보낼 때</b>, 기대값·Check 는 <b>볼 때</b> 번역한다.
 *
 * <p>직원과 조직의 조직도 아이디는 서로 달라야 한다(한 표에 같이 담긴다). 겹치면 {@link #기록한다} 가 멈춘다.
 */
public final class ScimIdBook {

    private static final Pattern 멤버필터 = Pattern.compile("^members\\[value eq \"(.*)\"]$");

    private final Map<String, String> 서버아이디 = new ConcurrentHashMap<>();

    /** 같은 조직도 아이디를 같은 서버 id 로 다시 기록하는 것은 괜찮다. 다른 서버 id 로 기록하면 하네스 오류다. */
    public void 기록한다(String 차트아이디, String 서버아이디) {
        String 전 = this.서버아이디.putIfAbsent(차트아이디, 서버아이디);
        if (전 != null && !전.equals(서버아이디)) {
            throw new IllegalStateException("조직도 아이디 %s 가 두 서버 id 로 기록됐다: %s, %s".formatted(차트아이디, 전, 서버아이디));
        }
    }

    /** 조직도 아이디의 서버 id. 모르면 그대로 돌려준다. */
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
