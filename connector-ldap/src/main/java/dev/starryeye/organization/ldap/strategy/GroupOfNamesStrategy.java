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
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQueryBuilder;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
            userIdByDn.put(LdapDns.대조키(entry.dn()), entry.id());
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
            groupIdByDn.put(LdapDns.대조키(entry.dn()), entry.id());
            survivingGroupEntries.put(entry.id(), entry);
        }

        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        int 멤버값수 = 0;
        int 사용자대조수 = 0;
        int 조직대조수 = 0;
        for (RawEntry entry : survivingGroupEntries.values()) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (String memberDn : entry.members()) {
                멤버값수++;
                String key = 대조키(entry, memberDn);
                String userId = userIdByDn.get(key);
                if (userId != null) {
                    members.add(MemberRef.user(userId));
                    사용자대조수++;
                    continue;
                }
                String groupId = groupIdByDn.get(key);
                if (groupId != null) {
                    members.add(MemberRef.group(groupId));
                    조직대조수++;
                    continue;
                }
                log.warn("조직 '{}' 의 member '{}' 가 사람도 그룹도 아니어서 건너뜁니다", entry.id(), memberDn);
            }
            groups.put(entry.id(), new DirectoryGroup(entry.id(), entry.dn(), entry.displayName(), members));
        }
        UnmatchedMemberGuard.확인한다(groups.size(), 멤버값수, 사용자대조수, 조직대조수);

        return new DirectorySnapshot(users, groups);
    }

    /**
     * <b>{@code ContextMapper} 다.</b> {@code AttributesMapper} 에는 DN 이 넘어오지 않아
     * 예전에는 검색 베이스와 식별 속성으로 DN 을 조립했는데, 실제 디렉터리는 사용자를 조직
     * OU 아래에 두고 RDN 도 {@code cn} 인 경우가 많아 서버가 준 {@code member} 값과 하나도
     * 맞지 않았다(2026-09-11 코드리뷰 H).
     */
    private ContextMapper<RawEntry> userMapper(LdapProperties.GroupOfNames config) {
        return context -> {
            DirContextAdapter adapter = (DirContextAdapter) context;
            Attributes attributes = adapter.getAttributes();
            return new RawEntry(
                    IdNormalizer.normalize(required(attributes, config.getUserIdAttribute())),
                    절대DN(adapter),
                    firstNonBlank(value(attributes, config.getUserNameAttribute()),
                            value(attributes, "cn"),
                            required(attributes, config.getUserIdAttribute())),
                    value(attributes, config.getUserMailAttribute()),
                    List.of());
        };
    }

    /**
     * <b>{@code ContextMapper} 다.</b> 서버가 준 DN 이 두 곳에 필요하다 — 그룹의
     * {@code member} 값과 대조할 키를 만들 때, 그리고 범위 검색으로 잘린 멤버를 이어받으려
     * 그 엔트리를 다시 지목해 물을 때.
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
                    절대DN(adapter),
                    // 폴백은 정규화된 code 가 아니라 원본이다 — 금지 문자가 있으면 code 에는
                    // 밑줄이 들어가고, 그것이 사람이 읽는 표시명 칸에 그대로 새어 나온다
                    firstNonBlank(value(attributes, config.getGroupNameAttribute()),
                            required(attributes, config.getGroupIdAttribute())),
                    null,
                    멤버.values(),
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

        // dn 으로 색인한다 — entry.id() 는 안 된다. IdNormalizer 가 금지 문자를 뭉개
        // 서로 다른 조직코드를 같은 값으로 만들 수 있고(DuplicateIdGuard 가 막는 바로 그
        // 충돌), 그 상태에서 아이디로 색인하면 잘리지 않은 형제 조직까지 이 맵에 걸려
        // 남의 이어받은 멤버 목록을 받는다 — 3명짜리 조직이 조용히 1,600명을 떠안는 권한
        // 확대다. dn 은 서버가 돌려준 진짜 DN 이라 엔트리마다 유일하다.
        Map<String, List<String>> 이어받은것 = LdapTemplates.한_커넥션에서(template, 한커넥션 -> {
            Map<String, List<String>> 결과 = new LinkedHashMap<>();
            for (RawEntry entry : 잘린것) {
                // 재요청은 ContextSource 의 베이스에 상대적인 DN 을 받는다 —
                // 절대 DN 을 그대로 넘기면 엔트리를 찾지 못한다
                List<String> 전부 = RangedAttributeReader.전부_읽는다(한커넥션,
                        LdapDns.상대로(entry.dn(), properties.getBaseDn()),
                        config.getMemberAttribute());
                log.info("조직 '{}' 의 멤버를 {}개까지 이어받았다 (첫 조각 {}개)",
                        entry.id(), 전부.size(), entry.members().size());
                결과.put(entry.dn(), 전부);
            }
            return 결과;
        });

        // 마지막 인자가 무조건 true 인 것은 낙관이 아니다 — 끝까지 못 읽으면
        // 전부_읽는다 가 IncompleteAttributeReadException 을 던지므로 여기 도달하지 못한다.
        return entries.stream()
                .map(entry -> 이어받은것.containsKey(entry.dn())
                        ? new RawEntry(entry.id(), entry.dn(), entry.displayName(), entry.email(),
                                이어받은것.get(entry.dn()), true)
                        : entry)
                .toList();
    }

    /**
     * 서버가 준 DN 은 {@code ContextSource} 의 베이스에 상대적이다. 그룹의 {@code member}
     * 값은 절대 DN 이므로 베이스를 붙여야 같은 좌표계에 놓인다.
     */
    private String 절대DN(DirContextAdapter adapter) {
        return LdapDns.절대로(adapter.getDn().toString(), properties.getBaseDn());
    }

    /**
     * {@code member} 값 하나를 대조 키로 바꾼다. {@link LdapDns#대조키} 가 파싱에 실패하면
     * 어느 DN 인지는 이미 담겨 있지만 <b>어느 조직의 member 인지</b>는 모른다 — 그 문맥을
     * 여기서 실어 다시 던진다. 대조는 계속 실패로 끝난다(설계 §5) — 문자열 비교로 물러나지
     * 않는다.
     */
    private static String 대조키(RawEntry entry, String memberDn) {
        try {
            return LdapDns.대조키(memberDn);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "조직 '" + entry.id() + "'(dn=" + entry.dn() + ") 의 member '" + memberDn
                            + "' 를 해석하지 못했습니다", e);
        }
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
     * @param dn              <b>서버가 준 절대 DN.</b> member 대조 키이자 externalId 이고,
     *                        범위 검색 재요청 때 엔트리를 다시 지목하는 좌표이기도 하다
     * @param membersComplete 멤버 목록이 잘리지 않고 다 왔는가
     */
    private record RawEntry(String id, String dn, String displayName, String email,
                            List<String> members, boolean membersComplete) {

        /** 직원 엔트리용. 다중값 속성을 읽지 않으므로 언제나 완결이다. */
        RawEntry(String id, String dn, String displayName, String email, List<String> members) {
            this(id, dn, displayName, email, members, true);
        }
    }
}
