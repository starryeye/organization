package dev.starryeye.organization.security;

/**
 * 범위(scope) 일곱(설계 2026-10-28 §4.2, A3, 설계 2026-11-04 §8 H8). 토큰의 {@code scope} 클레임에 실리고, 리소스 서버는 범위마다 {@code SCOPE_<범위>} 권한을 만든다(스프링 기본).
 * 범위 하나가 다른 범위를 품지 않는다 — 클라이언트에 필요한 범위를 모두 준다.
 */
public final class Scopes {

    /** SCIM 전부 — 쓰기와 발견({@code ServiceProviderConfig}·{@code ResourceTypes}·{@code Schemas}). IdP 가 받는다. */
    public static final String SCIM = "scim";

    /** 관리 API 의 읽기({@code GET /admin/**}) — 직원·조직·이력·회차. PII 를 훑을 수 있다. */
    public static final String ADMIN_READ = "admin.read";

    /** 동기화·재적재를 건다({@code POST /admin/sync/**}). */
    public static final String ADMIN_SYNC = "admin.sync";

    /** 위험한 요청({@code force=true}·{@code mode=wipe}) — {@link #ADMIN_SYNC} 에 더해 컨트롤러가 요구한다({@link Callers}). */
    public static final String ADMIN_DESTRUCTIVE = "admin.destructive";

    /** 정확 조회 둘({@code GET /admin/employees/lookup}·{@code /admin/organizations/lookup})만 — 권한을 묻는 앱이 받는다. */
    public static final String DIRECTORY_LOOKUP = "directory.lookup";

    /** 프로브 밖의 actuator 와 헬스 세부. Prometheus 가 받는다. */
    public static final String ACTUATOR = "actuator";

    /**
     * 조직의 지정 상위를 두고 없앤다({@code PUT}·{@code DELETE /admin/organizations/{orgCode}/parent}). 계층이 바뀌면 하위 조직 전체의 롤업 권한이 생기거나
     * 사라지므로 읽기({@link #ADMIN_READ})·동기화({@link #ADMIN_SYNC})와 가른다. 스크립트가 조직을 찾으려면 {@link #ADMIN_READ} 도 받아야 한다.
     */
    public static final String ADMIN_HIERARCHY = "admin.hierarchy";

    private static final String 권한_접두사 = "SCOPE_";

    private Scopes() {
    }

    /** 그 범위로 리소스 서버가 만드는 권한 이름 — {@code SCOPE_<범위>}. */
    public static String 권한(String scope) {
        return 권한_접두사 + scope;
    }
}
