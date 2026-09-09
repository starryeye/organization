package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.starryeye.organization.ldap.LdapTemplates;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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

        List<RawEntry> groupEntries = 범위가_잘린_멤버를_이어받는다(template, config,
                PagedLdapSearch.search(template,
                        LdapQueryBuilder.query()
                                .base(config.getGroupSearchBase())
                                .where("objectClass").is(config.getGroupObjectClass()),
                        pageSize, groupMapper(config)));

        Map<String, String> userIdByDn = new LinkedHashMap<>();
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        Map<String, String> userDnById = new LinkedHashMap<>();
        for (RawEntry entry : userEntries) {
            if (DuplicateIdGuard.isDuplicate("직원 아이디", entry.id(), entry.dn(), userDnById)) {
                continue;
            }
            userIdByDn.put(normalizeDn(entry.dn()), entry.id());
            users.put(entry.id(), new DirectoryUser(
                    entry.id(), entry.dn(), entry.id(), entry.displayName(), entry.email(), true));
        }

        Map<String, String> groupIdByDn = new LinkedHashMap<>();
        Map<String, String> groupDnById = new LinkedHashMap<>();
        Map<String, RawEntry> survivingGroupEntries = new LinkedHashMap<>();
        for (RawEntry entry : groupEntries) {
            if (DuplicateIdGuard.isDuplicate("조직코드", entry.id(), entry.dn(), groupDnById)) {
                continue;
            }
            groupIdByDn.put(normalizeDn(entry.dn()), entry.id());
            survivingGroupEntries.put(entry.id(), entry);
        }

        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (RawEntry entry : survivingGroupEntries.values()) {
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

    /**
     * <b>{@code ContextMapper} 다.</b> {@code AttributesMapper} 에는 DN 이 넘어오지 않는데,
     * 범위 검색으로 잘린 멤버를 이어받으려면 그 엔트리를 <b>다시 지목해 물어야</b> 하고
     * 그러려면 서버가 알려준 진짜 DN 이 필요하다. {@link #dnOf} 의 재구성은 조직이 검색
     * 베이스 바로 아래 있다고 가정하므로 트리가 깊으면 틀린 DN 이 된다.
     */
    private ContextMapper<RawEntry> groupMapper(LdapProperties.GroupOfNames config) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            String code = IdNormalizer.normalize(required(attributes, config.getGroupIdAttribute()));
            RangedAttributeReader.Chunk 멤버 =
                    RangedAttributeReader.읽는다(attributes, config.getMemberAttribute());
            return new RawEntry(
                    code,
                    // externalId 는 지금 형태를 유지한다. 진짜 DN 이 더 정확하지만 저장된 값이
                    // 전부 바뀌는 데이터 변경이라 이번 범위 밖이다 — 진짜 DN 은 재요청에만 쓴다.
                    dnOf(attributes, config.getGroupIdAttribute(), config.getGroupSearchBase()),
                    // 폴백은 정규화된 code 가 아니라 원본이다 — 금지 문자가 있으면 code 에는
                    // 밑줄이 들어가고, 그것이 사람이 읽는 표시명 칸에 그대로 새어 나온다
                    firstNonBlank(value(attributes, config.getGroupNameAttribute()),
                            required(attributes, config.getGroupIdAttribute())),
                    null,
                    멤버.values(),
                    adapter.getDn().toString(),
                    멤버.완료());
        };
    }

    /**
     * 멤버가 범위 검색으로 잘린 조직만 골라 <b>커넥션 하나 안에서</b> 끝까지 이어받는다.
     *
     * <p>잘린 조직이 없으면 — 범위 검색을 하지 않는 서버(OpenLDAP, 임베디드 UnboundID)이거나
     * 모든 조직이 한계선 아래이면 — 커넥션을 열지도 않는다.
     */
    private List<RawEntry> 범위가_잘린_멤버를_이어받는다(LdapTemplate template,
                                              LdapProperties.GroupOfNames config,
                                              List<RawEntry> entries) {
        List<RawEntry> 잘린것 = entries.stream().filter(entry -> !entry.membersComplete()).toList();
        if (잘린것.isEmpty()) {
            return entries;
        }
        log.info("멤버가 범위 검색으로 잘린 조직 {}개를 이어받는다", 잘린것.size());

        Map<String, List<String>> 이어받은것 = LdapTemplates.한_커넥션에서(template, 한커넥션 -> {
            Map<String, List<String>> 결과 = new LinkedHashMap<>();
            for (RawEntry entry : 잘린것) {
                List<String> 전부 = RangedAttributeReader.전부_읽는다(
                        한커넥션, entry.realDn(), config.getMemberAttribute());
                log.info("조직 '{}' 의 멤버를 {}개까지 이어받았다 (첫 조각 {}개)",
                        entry.id(), 전부.size(), entry.members().size());
                결과.put(entry.id(), 전부);
            }
            return 결과;
        });

        // 마지막 인자가 무조건 true 인 것은 낙관이 아니다 — 끝까지 못 읽으면
        // 전부_읽는다 가 IncompleteAttributeReadException 을 던지므로 여기 도달하지 못한다.
        return entries.stream()
                .map(entry -> 이어받은것.containsKey(entry.id())
                        ? new RawEntry(entry.id(), entry.dn(), entry.displayName(), entry.email(),
                                이어받은것.get(entry.id()), entry.realDn(), true)
                        : entry)
                .toList();
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

    /**
     * @param realDn          서버가 알려준 DN. 범위 검색 재요청에만 쓴다. 직원 쪽은 쓰지 않는다
     * @param membersComplete 멤버 목록이 잘리지 않고 다 왔는가
     */
    private record RawEntry(String id, String dn, String displayName, String email,
                            List<String> members, String realDn, boolean membersComplete) {

        /** 직원 엔트리용. 다중값 속성을 읽지 않으므로 언제나 완결이다. */
        RawEntry(String id, String dn, String displayName, String email, List<String> members) {
            this(id, dn, displayName, email, members, dn, true);
        }
    }
}
