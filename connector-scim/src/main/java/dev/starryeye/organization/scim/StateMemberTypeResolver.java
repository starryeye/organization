package dev.starryeye.organization.scim;

import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 현재상태를 조회해 멤버의 종류를 한 번에 판정한다(점검 P1). 조직이 있으면 조직, 직원이 있으면 직원이다.
 *
 * <p>둘 다 없으면 User 로 둔다 — SCIM 은 아직 도착하지 않은 리소스를 먼저 참조할 수 있고,
 * 그 경우 뒤늦게 도착한 쪽이 {@code type} 을 명시하는 것이 정상적인 흐름이기 때문이다.
 * 경고는 추측이 위험할 때만 요청당 한 줄 남긴다. {@code type} 은 선택 필드라 없어도 <b>있는 직원</b>으로
 * 판정되면 정상 경로다(Entra·Okta 는 늘 이렇게 보낸다). 조직으로 추정했거나 둘 다 없으면 IdP 는 성공 응답을 받고도
 * 의도한 것과 다른 튜플을 얻을 수 있으므로, 추측했다는 사실이 로그에 남아야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class StateMemberTypeResolver implements MemberTypeResolver {

    private static final int 로그에_남길_아이디 = 10;

    private final DirectoryStateRepository state;

    @Override
    public Mono<Map<String, MemberType>> resolveAll(Set<String> ids) {
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return state.findMemberTypes(ids).map(found -> {
            Map<String, MemberType> resolved = new LinkedHashMap<>();
            List<String> 조직 = new ArrayList<>();
            List<String> 없음 = new ArrayList<>();
            for (String id : ids) {
                MemberType type = found.get(id);
                if (type == null) {
                    없음.add(id);
                    resolved.put(id, MemberType.USER);
                    continue;
                }
                if (type == MemberType.GROUP) {
                    조직.add(id);
                }
                resolved.put(id, type);
            }
            if (!조직.isEmpty() || !없음.isEmpty()) {
                log.warn("SCIM 멤버에 type 이 없어 현재상태로 추정했습니다: 조직 {}명 {}, 없음 {}명 {}(직원으로 봄)",
                        조직.size(), 앞부분(조직), 없음.size(), 앞부분(없음));
            }
            return resolved;
        });
    }

    private static List<String> 앞부분(List<String> ids) {
        return ids.subList(0, Math.min(로그에_남길_아이디, ids.size()));
    }
}
