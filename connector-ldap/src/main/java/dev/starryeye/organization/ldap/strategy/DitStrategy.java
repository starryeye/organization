package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import javax.naming.directory.Attributes;

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
                        .filter(config.getOrgUnitFilter()),
                pageSize, orgMapper(config));

        List<UserEntry> userEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getRootDn())
                        .attributes(직원_속성(config))
                        .filter(config.getUserFilter()),
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
        SkippedEntries 건너뛴_조직 = new SkippedEntries("조직", config.getGroupIdAttribute());
        Map<String, String> groupDnByCode = new LinkedHashMap<>();
        for (OrgEntry entry : orgEntries) {
            String code = entry.code();
            if (code == null) {
                건너뛴_조직.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='" + entry.dn() + "'");
                continue;
            }
            if (DuplicateIdGuard.isDuplicate(code, entry.dn(), groupDnByCode)) {
                건너뛴_조직.기록한다(SkippedEntries.사유.아이디_겹침,
                        "%s(건너뛴 dn='%s', 유지된 dn='%s')".formatted(code, entry.dn(), groupDnByCode.get(code)));
                continue;
            }
            codeByRdnPath.put(LdapDns.대조키(entry.dn()), code);
            membersByCode.putIfAbsent(code, new LinkedHashSet<>());
            // externalId 는 서버가 준 절대 DN 이다(설계 2026-10-04 §4.2) — groupOfNames 와 같다. 안쪽 키·로그는 베이스 상대 DN 그대로 쓴다
            groups.put(code, new DirectoryGroup(
                    code, LdapDns.절대로(entry.dn(), properties.getBaseDn()), entry.name(), Set.of()));
        }

        // 조직 계층: 각 조직의 부모 dn 을 조직코드로 되짚어 하위 조직 멤버로 등록한다.
        // code 가 null 이면 이 엔트리는 위에서 식별 속성이 없거나 코드가 충돌해 스킵된 것이므로 함께 건너뛴다 —
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
        SkippedEntries 건너뛴_직원 = new SkippedEntries("직원", config.getUserIdAttribute());
        int 소속수 = 0;
        for (UserEntry entry : userEntries) {
            String userId = entry.id();
            if (userId == null) {
                건너뛴_직원.기록한다(SkippedEntries.사유.식별_속성_없음, "dn='" + entry.dn() + "'");
                continue;
            }
            if (DuplicateIdGuard.isDuplicate(userId, entry.dn(), userDnById)) {
                건너뛴_직원.기록한다(SkippedEntries.사유.아이디_겹침,
                        "%s(건너뛴 dn='%s', 유지된 dn='%s')".formatted(userId, entry.dn(), userDnById.get(userId)));
                continue;
            }
            users.put(userId, new DirectoryUser(
                    userId, LdapDns.절대로(entry.dn(), properties.getBaseDn()), entry.userName(), entry.displayName(),
                    entry.email(), entry.active(), entry.name()));

            String parentCode = codeByRdnPath.get(LdapDns.대조키(LdapDns.부모(entry.dn())));
            if (parentCode == null) {
                // 직원으로는 그대로 적재한다 — 소속만 없다(점검 S22)
                건너뛴_직원.기록한다(SkippedEntries.사유.부모_조직_없음, "dn='" + entry.dn() + "'");
                continue;
            }
            membersByCode.get(parentCode).add(MemberRef.user(userId));
            소속수++;
        }

        건너뛴_조직.요약을_남긴다();
        건너뛴_조직.아무도_남지_않으면_멈춘다(orgEntries.size(), groups.size());
        건너뛴_직원.요약을_남긴다();
        건너뛴_직원.아무도_남지_않으면_멈춘다(userEntries.size(), users.size());
        if (!users.isEmpty() && 소속수 == 0) {
            throw new DirectoryDataException(("DIT 직원 %d명 중 부모 조직을 찾은 직원이 없다 — root-dn 과 org-unit-filter 가 직원이 있는 곳을 조직으로 읽는지"
                    + " 확인하라(AD 의 CN=Users 는 OU 가 아니다, 점검 S22) — %s").formatted(users.size(), 건너뛴_직원.요약()));
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
        return UserAttributes.요청(config.getUserIdAttribute(), config.getUserLoginAttribute(),
                config.getUserNameAttribute(), config.getUserMailAttribute());
    }

    /**
     * ContextMapper 를 쓰는 이유는 dn 이 필요하기 때문이다. AttributesMapper 에는 dn 이 오지 않는다.
     * 원본 엔트리는 들고 가지 않고 필요한 값만 뽑는다 — 회차 끝까지 쥐는 것은 작은 레코드뿐이다.
     */
    private ContextMapper<OrgEntry> orgMapper(LdapProperties.Dit config) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            String dn = adapter.getDn().toString();
            String 식별값 = LdapIdentifiers.있으면(adapter.getAttributes(), config.getGroupIdAttribute(), dn);
            if (식별값 == null) {
                return OrgEntry.식별_속성_없음(dn);
            }
            return new OrgEntry(
                    dn,
                    IdNormalizer.normalize(식별값),
                    // 이름 속성이 없으면 DN 의 첫 RDN 값이다(설계 2026-10-04 §4.2) — id 는 entryUUID 라 사람이 읽을 수 없고,
                    // 정규화된 code 도 아니다: 금지 문자가 있으면 code 에는 밑줄이 들어가고, 그것이 표시명 칸에 그대로 새어 나온다.
                    // 상대 DN 이 아니라 절대 DN 에서 뽑는다 — root-dn 이 비어 있으면 루트 OU 의 상대 DN 이 빈 문자열이다
                    firstNonBlank(adapter.getStringAttribute(config.getGroupNameAttribute()),
                            LdapDns.첫_RDN_값(LdapDns.절대로(dn, properties.getBaseDn()))));
        };
    }

    /** 계정 상태·이름은 여기서 계산한다. 동기화 시각 {@code 지금} 은 매퍼를 만들 때 한 번 잡은 값이다. */
    private ContextMapper<UserEntry> userMapper(LdapProperties.Dit config, Instant 지금) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            String dn = adapter.getDn().toString();
            String 식별값 = LdapIdentifiers.있으면(attributes, config.getUserIdAttribute(), dn);
            if (식별값 == null) {
                // 건너뛸 엔트리다 — 계정 상태 같은 나머지 속성은 읽지 않는다. 표준 밖의 값이 있어도 회차를 멈추지 않는다
                return UserEntry.식별_속성_없음(dn);
            }
            // userName 은 로그인 속성의 원본 값이다(점검 S25) — 정규화하지 않는다. 없으면 식별 값으로 대신한다
            String userName = firstNonBlank(adapter.getStringAttribute(config.getUserLoginAttribute()), 식별값);
            return new UserEntry(
                    dn,
                    IdNormalizer.normalize(식별값),
                    userName,
                    // 마지막 폴백은 id 가 아니라 원본 userName 이다 — id 는 UUID 라 표시명으로 보이면 안 되고, 정규화된 id 라면
                    // 금지 문자가 밑줄로 바뀐 채 표시명 칸에 그대로 새어 나온다 (위 조직명과 같은 이유)
                    firstNonBlank(adapter.getStringAttribute(config.getUserNameAttribute()),
                            adapter.getStringAttribute(UserAttributes.CN),
                            userName),
                    adapter.getStringAttribute(config.getUserMailAttribute()),
                    // 디렉터리가 막은 계정은 비활성이다 — 소속은 두고 권한 튜플만 사라진다
                    !AccountStatus.막혔는가(dn, attributes, 지금),
                    LdapPersonName.from(attributes));
        };
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
     * @param dn   서버가 준 DN(검색 베이스에 상대적). 조직 계층을 되짚는 키다. externalId 는 여기에 베이스를 붙인 절대 DN 이다
     * @param code 정규화된 조직 id(기본은 entryUUID). 식별 속성이 없는 엔트리는 null 이다
     * @param name 조직명. 없으면 DN 의 첫 RDN 값이다
     */
    private record OrgEntry(String dn, String code, String name) {

        /** 식별 속성이 없어 건너뛸 엔트리. 전략이 읽는 것은 dn 뿐이다 */
        static OrgEntry 식별_속성_없음(String dn) {
            return new OrgEntry(dn, null, null);
        }
    }

    /**
     * 직원 엔트리에서 뽑은 값.
     *
     * @param dn          서버가 준 DN(검색 베이스에 상대적). 소속 조직을 되짚는 키다. externalId 는 여기에 베이스를 붙인 절대 DN 이다
     * @param id          정규화된 직원 id(기본은 entryUUID). 식별 속성이 없는 엔트리는 null 이다
     * @param userName    로그인 속성의 원본 값. 없으면 식별 값이다
     * @param displayName 표시명. 없으면 {@code cn}, 그것도 없으면 {@code userName} 이다
     * @param email       메일. 없으면 null
     * @param active      디렉터리가 막은 계정이 아니면 true
     * @param name        RFC 이름 여섯 칸 중 직원에게 달린 것
     */
    private record UserEntry(String dn, String id, String userName, String displayName, String email,
                             boolean active, PersonName name) {

        /** 식별 속성이 없어 건너뛸 엔트리. 전략이 읽는 것은 dn 뿐이라 나머지 칸은 자리만 채운다 */
        static UserEntry 식별_속성_없음(String dn) {
            return new UserEntry(dn, null, null, null, null, true, null);
        }
    }
}
