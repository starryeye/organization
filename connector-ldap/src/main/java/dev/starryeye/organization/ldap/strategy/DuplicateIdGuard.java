package dev.starryeye.organization.ldap.strategy;

import java.util.Map;

/**
 * design §4.3 / §9: 정규화 후 아이디가 충돌하면 해당 엔트리를 스킵한다. 동기화 전체를 실패시키지 않는다.
 * 판정만 한다 — 건너뛴 것을 알리는 일은 호출자가 {@link SkippedEntries} 에 기록해 검색당 경고 한 줄로 남긴다.
 *
 * <p>DIT 전략에서는 이게 드문 경우가 아니다. DIT 은 형제 사이에서만 RDN 유일성을 보장하므로
 * {@code ou=support,ou=DEV001,...} 와 {@code ou=support,ou=OPS001,...} 가 모두 {@code support}
 * 로 정규화될 수 있다. 이걸 그대로 두면 두 DN 이 하나의 코드로 뭉개져 멤버 집합이 합쳐지고,
 * 계층 롤업이 두 부모 모두에 {@code child} 간선을 만들어 어느 한쪽 소속 직원이 다른 쪽에도
 * {@code member} 가 되는 조용한 권한 확대로 이어진다.
 */
final class DuplicateIdGuard {

    private DuplicateIdGuard() {
    }

    /**
     * {@code id} 가 이미 {@code dnById} 에 다른 dn 으로 등록돼 있으면 {@code true} 를 반환한다(호출자는 이 엔트리를
     * 스킵하고 {@link SkippedEntries} 에 기록해야 한다. 유지된 dn 은 {@code dnById.get(id)} 다). 처음 보는 id 면
     * {@code dnById} 에 등록하고 {@code false} 를 반환한다.
     */
    static boolean isDuplicate(String id, String dn, Map<String, String> dnById) {
        if (dnById.containsKey(id)) {
            return true;
        }
        dnById.put(id, dn);
        return false;
    }
}
