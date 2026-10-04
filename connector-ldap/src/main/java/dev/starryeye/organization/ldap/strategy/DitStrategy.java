package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ou 트리를 조직 계층으로, 사용자 엔트리의 부모 ou 를 소속으로 본다.
 *
 * <p>DIT 위치가 곧 소속이므로 직원은 하나의 조직에만 속한다.
 * groupOfNames 전략과 다른 방식으로 읽지만 같은 {@link DirectorySnapshot} 을 만든다.
 */
@Slf4j
@RequiredArgsConstructor
public class DitStrategy implements LdapMappingStrategy {

    private final LdapProperties properties;

    /** 계정 만료를 판정할 "지금" 의 출처. {@code read} 마다 한 번 읽어 그 회차의 모든 직원에게 같은 시각을 쓴다. */
    private final Clock clock;

    /** 시스템 UTC 시계를 쓴다. */
    public DitStrategy(LdapProperties properties) {
        this(properties, Clock.systemUTC());
    }

    @Override
    public DirectorySnapshot read(LdapTemplate template) {
        LdapProperties.Dit config = properties.getDit();
        Instant 지금 = clock.instant();
        int pageSize = properties.getPageSize();

        List<OrgEntry> orgEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getRootDn())
                        .attributes(OU_속성(config))
                        .where("objectClass").is(config.getOrgUnitObjectClass()),
                pageSize, orgMapper(config));

        List<UserEntry> userEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getRootDn())
                        .attributes(직원_속성(config))
                        .where("objectClass").is(config.getUserObjectClass()),
                pageSize, userMapper(config, 지금));

        // 조직코드 → 상대 DN, 상대 DN → 조직코드 양방향 색인
        Map<String, String> codeByRdnPath = new LinkedHashMap<>();
        Map<String, Set<MemberRef>> membersByCode = new LinkedHashMap<>();
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

        // DIT 은 형제 사이에서만 RDN 유일성을 보장하므로, 서로 다른 부모 아래의 ou 가 같은
        // 코드로 정규화될 수 있다(예: ou=support,ou=DEV001 과 ou=support,ou=OPS001). 충돌한
        // 뒤의 엔트리를 그대로 두면 codeByRdnPath 가 두 dn 을 한 코드로 묶어 멤버 집합이
        // 합쳐지고, 계층 롤업이 양쪽 부모 모두에 child 간선을 만들어 조용한 권한 확대로
        // 이어진다. 충돌한 엔트리는 스킵한다 — 그 dn 을 codeByRdnPath 에 넣지 않으므로
        // 산하 엔트리는 "부모를 찾지 못함"으로 자연히 스킵된다.
        Map<String, String> groupDnByCode = new LinkedHashMap<>();
        for (OrgEntry entry : orgEntries) {
            String code = entry.code();
            if (DuplicateIdGuard.isDuplicate("조직코드", code, entry.dn(), groupDnByCode)) {
                continue;
            }
            codeByRdnPath.put(LdapDns.대조키(entry.dn()), code);
            membersByCode.putIfAbsent(code, new LinkedHashSet<>());
            groups.put(code, new DirectoryGroup(code, entry.dn(), entry.name(), Set.of()));
        }

        // 조직 계층: 각 조직의 부모 dn 을 조직코드로 되짚어 하위 조직 멤버로 등록한다.
        // code 가 null 이면 이 엔트리는 위에서 코드 충돌로 스킵된 것이므로 함께 건너뛴다 —
        // 그러지 않으면 부모의 멤버 집합에 id 가 null 인 MemberRef 가 들어간다.
        for (OrgEntry entry : orgEntries) {
            String code = codeByRdnPath.get(LdapDns.대조키(entry.dn()));
            if (code == null) {
                continue;
            }
            String parentCode = codeByRdnPath.get(LdapDns.대조키(LdapDns.부모(entry.dn())));
            if (parentCode != null && !parentCode.equals(code)) {
                membersByCode.get(parentCode).add(MemberRef.group(code));
            }
        }

        // 직원 소속: 사용자 엔트리의 부모 dn 이 곧 소속 조직이다
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        Map<String, String> userDnById = new LinkedHashMap<>();
        for (UserEntry entry : userEntries) {
            String userId = entry.id();
            if (DuplicateIdGuard.isDuplicate("직원 아이디", userId, entry.dn(), userDnById)) {
                continue;
            }
            users.put(userId, new DirectoryUser(
                    userId, entry.dn(), userId, entry.displayName(), entry.email(), entry.active(),
                    entry.name()));

            String parentCode = codeByRdnPath.get(LdapDns.대조키(LdapDns.부모(entry.dn())));
            if (parentCode == null) {
                log.warn("직원 '{}' 의 부모 조직을 찾지 못해 소속을 건너뜁니다 (dn={})", userId, entry.dn());
                continue;
            }
            membersByCode.get(parentCode).add(MemberRef.user(userId));
        }

        membersByCode.forEach((code, members) -> {
            DirectoryGroup base = groups.get(code);
            groups.put(code, new DirectoryGroup(base.id(), base.externalId(), base.displayName(), members));
        });

        return new DirectorySnapshot(users, groups);
    }

    /** OU 검색이 요청하는 속성. {@link #orgMapper} 가 읽는 속성과 같아야 한다 — 요청하지 않은 속성은 오지 않는다. */
    static String[] OU_속성(LdapProperties.Dit config) {
        return PagedLdapSearch.속성목록(config.getGroupIdAttribute(), config.getGroupNameAttribute());
    }

    /**
     * 직원 검색이 요청하는 속성. {@link #userMapper} 가 읽는 속성과 같아야 한다. 설정 값에서 만들고 비었거나
     * 겹친 이름은 뺀다.
     */
    static String[] 직원_속성(LdapProperties.Dit config) {
        return PagedLdapSearch.속성목록(
                config.getUserIdAttribute(), config.getUserNameAttribute(), config.getUserMailAttribute(), "cn",
                AdAccountStatus.USER_ACCOUNT_CONTROL, AdAccountStatus.ACCOUNT_EXPIRES,
                LdapPersonName.SN, LdapPersonName.GIVEN_NAME, LdapPersonName.MIDDLE_NAME,
                LdapPersonName.GENERATION_QUALIFIER);
    }

    /**
     * ContextMapper 를 쓰는 이유는 dn 이 필요하기 때문이다. AttributesMapper 에는 dn 이 오지 않는다.
     * 원본 엔트리는 들고 가지 않고 필요한 값만 뽑는다 — 회차 끝까지 쥐는 것은 작은 레코드뿐이다.
     */
    private ContextMapper<OrgEntry> orgMapper(LdapProperties.Dit config) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            String dn = adapter.getDn().toString();
            return new OrgEntry(
                    dn,
                    IdNormalizer.normalize(필수(adapter, dn, config.getGroupIdAttribute())),
                    // 폴백은 정규화된 code 가 아니라 원본 속성이다 — 금지 문자가 있으면 code 에는
                    // 밑줄이 들어가고, 그것이 사람이 읽는 표시명 칸에 그대로 새어 나온다
                    firstNonBlank(adapter.getStringAttribute(config.getGroupNameAttribute()),
                            adapter.getStringAttribute(config.getGroupIdAttribute())));
        };
    }

    /** 계정 상태·이름은 여기서 계산한다. 동기화 시각 {@code 지금} 은 매퍼를 만들 때 한 번 잡은 값이다. */
    private ContextMapper<UserEntry> userMapper(LdapProperties.Dit config, Instant 지금) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            String dn = adapter.getDn().toString();
            return new UserEntry(
                    dn,
                    IdNormalizer.normalize(필수(adapter, dn, config.getUserIdAttribute())),
                    // 마지막 폴백도 정규화된 userId 가 아니라 원본 uid 다 (위 조직명과 같은 이유)
                    firstNonBlank(adapter.getStringAttribute(config.getUserNameAttribute()),
                            adapter.getStringAttribute("cn"),
                            adapter.getStringAttribute(config.getUserIdAttribute())),
                    adapter.getStringAttribute(config.getUserMailAttribute()),
                    // AD 가 막은 계정은 비활성이다 — 소속은 두고 권한 튜플만 사라진다
                    !AdAccountStatus.막혔는가(dn, adapter.getAttributes(), 지금),
                    LdapPersonName.from(adapter.getAttributes()));
        };
    }

    /**
     * 식별 속성. 없으면 그 엔트리는 조직코드도 직원 아이디도 가질 수 없다 — 같은 디렉터리를 다시 읽어도 생기지
     * 않으므로 재시도하지 않는 종류로 던진다.
     */
    private static String 필수(DirContextAdapter adapter, String dn, String 이름) {
        String 값 = adapter.getStringAttribute(이름);
        if (값 == null || 값.isBlank()) {
            throw new DirectoryDataException("필수 속성 '" + 이름 + "' 가 없습니다: dn=" + dn);
        }
        return 값;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 조직 엔트리에서 뽑은 값.
     *
     * @param dn   서버가 준 DN(검색 베이스에 상대적). 조직 계층을 되짚는 키이자 externalId 다
     * @param code 정규화된 조직코드
     * @param name 조직명. 없으면 원본 식별 속성 값이다
     */
    private record OrgEntry(String dn, String code, String name) {
    }

    /**
     * 직원 엔트리에서 뽑은 값.
     *
     * @param id          정규화된 직원 아이디
     * @param displayName 표시명. 없으면 {@code cn}, 그것도 없으면 원본 식별 속성 값이다
     */
    private record UserEntry(String dn, String id, String displayName, String email, boolean active,
                             PersonName name) {
    }
}
