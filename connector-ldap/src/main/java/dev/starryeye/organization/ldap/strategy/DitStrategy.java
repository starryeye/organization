package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    @Override
    public DirectorySnapshot read(LdapTemplate template) {
        LdapProperties.Dit config = properties.getDit();
        int pageSize = properties.getPageSize();

        List<Entry> orgEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getRootDn())
                        .where("objectClass").is(config.getOrgUnitObjectClass()),
                pageSize, entryMapper());

        List<Entry> userEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getRootDn())
                        .where("objectClass").is(config.getUserObjectClass()),
                pageSize, entryMapper());

        // 조직코드 → 상대 DN, 상대 DN → 조직코드 양방향 색인
        Map<String, String> codeByRdnPath = new LinkedHashMap<>();
        Map<String, Set<MemberRef>> membersByCode = new LinkedHashMap<>();
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

        for (Entry entry : orgEntries) {
            String code = IdNormalizer.normalize(entry.attribute(config.getGroupIdAttribute()));
            codeByRdnPath.put(normalize(entry.dn()), code);
            membersByCode.putIfAbsent(code, new LinkedHashSet<>());
            String name = firstNonBlank(entry.attribute(config.getGroupNameAttribute()), code);
            groups.put(code, new DirectoryGroup(code, entry.dn(), name, Set.of()));
        }

        // 조직 계층: 각 조직의 부모 dn 을 조직코드로 되짚어 하위 조직 멤버로 등록한다
        for (Entry entry : orgEntries) {
            String code = codeByRdnPath.get(normalize(entry.dn()));
            String parentCode = codeByRdnPath.get(normalize(parentDn(entry.dn())));
            if (parentCode != null && !parentCode.equals(code)) {
                membersByCode.get(parentCode).add(MemberRef.group(code));
            }
        }

        // 직원 소속: 사용자 엔트리의 부모 dn 이 곧 소속 조직이다
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (Entry entry : userEntries) {
            String userId = IdNormalizer.normalize(entry.attribute(config.getUserIdAttribute()));
            users.put(userId, new DirectoryUser(
                    userId,
                    entry.dn(),
                    userId,
                    firstNonBlank(entry.attribute(config.getUserNameAttribute()), entry.attribute("cn"), userId),
                    entry.attribute(config.getUserMailAttribute()),
                    true));

            String parentCode = codeByRdnPath.get(normalize(parentDn(entry.dn())));
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

    /** ContextMapper 를 쓰는 이유는 dn 이 필요하기 때문이다. AttributesMapper 에는 dn 이 오지 않는다. */
    private ContextMapper<Entry> entryMapper() {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            return new Entry(adapter.getDn().toString(), adapter);
        };
    }

    /** 첫 RDN 을 떼어 부모 dn 을 만든다. 최상위면 빈 문자열이 된다. */
    private static String parentDn(String dn) {
        int comma = dn.indexOf(',');
        return comma < 0 ? "" : dn.substring(comma + 1);
    }

    private static String normalize(String dn) {
        return dn.toLowerCase(Locale.ROOT).replace(", ", ",").trim();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private record Entry(String dn, DirContextAdapter adapter) {

        String attribute(String name) {
            return adapter.getStringAttribute(name);
        }
    }
}
