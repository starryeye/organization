package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 그룹 엔트리의 member 속성을 읽는다. SCIM 의 members 배열과 구조가 같아 변환이 자연스럽다.
 *
 * <p>member DN 이 사람인지 그룹인지는 미리 읽어둔 DN 집합으로 판별한다.
 * DN 마다 추가 조회를 하면 조직 규모에 비례해 왕복이 폭증한다.
 */
@Slf4j
@RequiredArgsConstructor
public class GroupOfNamesStrategy implements LdapMappingStrategy {

    private final LdapProperties properties;

    @Override
    public DirectorySnapshot read(LdapTemplate template) {
        LdapProperties.GroupOfNames config = properties.getGroupOfNames();
        int pageSize = properties.getPageSize();

        List<RawEntry> userEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getUserSearchBase())
                        .where("objectClass").is(config.getUserObjectClass()),
                pageSize, userMapper(config));

        List<RawEntry> groupEntries = PagedLdapSearch.search(template,
                LdapQueryBuilder.query()
                        .base(config.getGroupSearchBase())
                        .where("objectClass").is(config.getGroupObjectClass()),
                pageSize, groupMapper(config));

        Map<String, String> userIdByDn = new LinkedHashMap<>();
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (RawEntry entry : userEntries) {
            userIdByDn.put(normalizeDn(entry.dn()), entry.id());
            users.put(entry.id(), new DirectoryUser(
                    entry.id(), entry.dn(), entry.id(), entry.displayName(), entry.email(), true));
        }

        Map<String, String> groupIdByDn = new LinkedHashMap<>();
        for (RawEntry entry : groupEntries) {
            groupIdByDn.put(normalizeDn(entry.dn()), entry.id());
        }

        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (RawEntry entry : groupEntries) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (String memberDn : entry.members()) {
                String key = normalizeDn(memberDn);
                String userId = userIdByDn.get(key);
                if (userId != null) {
                    members.add(MemberRef.user(userId));
                    continue;
                }
                String groupId = groupIdByDn.get(key);
                if (groupId != null) {
                    members.add(MemberRef.group(groupId));
                    continue;
                }
                log.warn("조직 '{}' 의 member '{}' 가 사람도 그룹도 아니어서 건너뜁니다", entry.id(), memberDn);
            }
            groups.put(entry.id(), new DirectoryGroup(entry.id(), entry.dn(), entry.displayName(), members));
        }

        return new DirectorySnapshot(users, groups);
    }

    private AttributesMapper<RawEntry> userMapper(LdapProperties.GroupOfNames config) {
        return attributes -> new RawEntry(
                IdNormalizer.normalize(required(attributes, config.getUserIdAttribute())),
                dnOf(attributes, config.getUserIdAttribute(), config.getUserSearchBase()),
                firstNonBlank(value(attributes, config.getUserNameAttribute()),
                        value(attributes, "cn"),
                        required(attributes, config.getUserIdAttribute())),
                value(attributes, config.getUserMailAttribute()),
                List.of());
    }

    private AttributesMapper<RawEntry> groupMapper(LdapProperties.GroupOfNames config) {
        return attributes -> {
            String code = IdNormalizer.normalize(required(attributes, config.getGroupIdAttribute()));
            return new RawEntry(
                    code,
                    dnOf(attributes, config.getGroupIdAttribute(), config.getGroupSearchBase()),
                    firstNonBlank(value(attributes, config.getGroupNameAttribute()), code),
                    null,
                    values(attributes, config.getMemberAttribute()));
        };
    }

    /**
     * AttributesMapper 에는 DN 이 넘어오지 않으므로 검색 베이스와 식별 속성으로 재구성한다.
     * externalId 보관과 member DN 대조에만 쓰이므로 정확한 형태보다 일관성이 중요하다.
     */
    private String dnOf(Attributes attributes, String idAttribute, String searchBase) {
        return idAttribute + "=" + required(attributes, idAttribute)
                + "," + searchBase + "," + properties.getBaseDn();
    }

    /** 대소문자와 공백 차이로 DN 대조가 어긋나지 않게 정규화한다. */
    private static String normalizeDn(String dn) {
        return dn.toLowerCase(Locale.ROOT).replace(", ", ",").trim();
    }

    private static String required(Attributes attributes, String name) {
        String value = value(attributes, name);
        if (value == null) {
            throw new IllegalStateException("필수 속성 '" + name + "' 가 없습니다");
        }
        return value;
    }

    private static String value(Attributes attributes, String name) {
        try {
            Attribute attribute = attributes.get(name);
            return attribute == null ? null : (String) attribute.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> values(Attributes attributes, String name) {
        List<String> result = new ArrayList<>();
        try {
            Attribute attribute = attributes.get(name);
            if (attribute == null) {
                return result;
            }
            NamingEnumeration<?> enumeration = attribute.getAll();
            while (enumeration.hasMore()) {
                result.add((String) enumeration.next());
            }
        } catch (Exception e) {
            log.warn("속성 '{}' 을 읽지 못했습니다", name, e);
        }
        return result;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private record RawEntry(String id, String dn, String displayName, String email, List<String> members) {
    }
}
