package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fake.FakePageBookmarkRepository;
import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.dto.ScimListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupListingTest {

    private FakeStateRepository state;
    private FakeQueryRepository query;
    private ScimGroupListing listing;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        query = new FakeQueryRepository(state);
        listing = new ScimGroupListing(state, query, new FakePageBookmarkRepository());
        state.saveGroup(new DirectoryGroup("DEV001", "grp-dev", "Dev Team",
                Set.of(MemberRef.user("kim"), MemberRef.group("DEV002")))).block();
        state.saveGroup(new DirectoryGroup("DEV002", "grp-dev2", "Backend", Set.of(MemberRef.user("park")))).block();
        state.findGroupCalls.clear();
    }

    private ScimListResponse 조회(String filter, List<String> excluded) {
        return listing.list(ScimQuery.of(ScimResourceType.GROUP, filter, 1L, 100L, null, null, null, excluded)).block();
    }

    @Test
    @DisplayName("excludedAttributes=members 면 조직 파티션을 읽지 않고 members 를 담지 않는다")
    void members_를_빼면_읽지_않는다() {
        // when
        ScimListResponse page = 조회(null, List.of("members"));

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(page.resources()).allSatisfy(group -> assertThat(group.has("members")).isFalse());
    }

    @Test
    @DisplayName("members 가 응답에 남으면 조직 파티션을 통째로 읽지 않고 흘려 쓴다 — 멤버 줄만 이어 읽는다(설계 2026-10-06 §4.3)")
    void members_가_남으면_흘려_쓴다() throws Exception {
        // given
        ScimQuery query = ScimQuery.of(ScimResourceType.GROUP, null, 1L, 100L, null, null, null, null);

        // when
        String body = listing.streamed(query)
                .flatMap(DataBufferUtils::join)
                .map(buffer -> {
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return text;
                })
                .block();
        JsonNode page = new ObjectMapper().readTree(body);

        // then
        assertThat(state.findGroupCalls).isEmpty();
        assertThat(state.findMemberRefsCalls).containsExactlyInAnyOrder("DEV001", "DEV002");
        assertThat(page.get("itemsPerPage").asInt()).isEqualTo(2);
        assertThat(page.get("Resources").get(0).get("id").asText()).isEqualTo("DEV002");
        assertThat(page.get("Resources").get(1).get("members")).hasSize(2);
    }

    @Test
    @DisplayName("displayName eq 는 대소문자를 가리지 않고, externalId eq 는 가린다")
    void 조직_필터() {
        // when, then
        assertThat(조회("displayName eq \"dev team\"", List.of("members")).totalResults()).isEqualTo(1);
        assertThat(조회("externalId eq \"grp-dev\"", List.of("members")).totalResults()).isEqualTo(1);
        assertThat(조회("externalId eq \"GRP-DEV\"", List.of("members")).totalResults()).isZero();
    }

    @Test
    @DisplayName("displayName 이 externalId 보다 먼저 인덱스 조건이 된다")
    void 인덱스_조건_우선순위() {
        // when
        조회("externalId eq \"grp-dev\" and displayName eq \"Dev Team\"", List.of("members"));

        // then
        assertThat(query.calls).containsExactly("findGroupHeadersByDisplayName:Dev Team");
    }
}
