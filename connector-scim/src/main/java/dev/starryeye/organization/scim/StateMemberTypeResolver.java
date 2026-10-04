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
 * <p>둘 다 없으면 User 로 둔다 — id 를 서버가 발급하므로 IdP 는 받은 id 로만 가리키고, 정상 흐름에서는
 * 이 갈래가 생기지 않는다. 그래도 서버가 모르는 id 가 오면 거절하지 않고 직원으로 받아 둔다
 * (멤버 줄만 남고, 그 직원이 실제로 생기기 전에는 튜플이 없다).
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
