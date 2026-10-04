package dev.starryeye.organization.scim.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.scim.MemberTypeResolver;
import dev.starryeye.organization.scim.ScimMapper;
import dev.starryeye.organization.scim.ScimPatchApplier;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 렌더러가 만든 요청을 서버 쪽 해석기에 그대로 통과시켜, 조직도가 다시 나오는지 본다.
 *
 * <p>PATCH 는 <b>JSON 을 한 번 거쳐서</b> 확인한다. 서버는 {@code value} 를 {@code Object}
 * 로 받아 {@code Map} 으로 해석하므로, DTO 를 곧바로 넘기면 실제 요청과 다른 경로를 타고
 * {@code Operations} 대문자 규정 같은 것도 검증되지 않는다.
 */
class ScimRequestRendererTest {

    private static final OrgChart CHART = OrgChartFixture.오천명();
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 판정할 아이디가 넘어오면 실패한다(빈 집합이면 통과). 렌더러는 {@code type} 을 항상 명시하므로 서버가 현재상태를 추정할
     * 일이 없어야 한다 — 추정이 일어나면 테스트 결과에 추정의 정확도가 섞여 든다.
     */
    private final MemberTypeResolver 추정금지 = ids -> {
        if (!ids.isEmpty()) {
            fail("멤버 type 이 빠져 현재상태 추정이 일어났습니다: " + ids);
        }
        return Mono.just(Map.of());
    };

    /** PATCH 를 {@link dev.starryeye.organization.core.model.GroupChange} 로 정리해 before 에 적용한다. 이 조직도엔 직원·하위 조직 id 가 겹치지 않아 모호한 빼기가 없다. */
    private static DirectoryGroup 적용한다(DirectoryGroup before, ScimPatchOp patch, MemberTypeResolver resolver) {
        return ScimPatchApplier.toGroupChange(patch, resolver).block()
                .applyTo(before, id -> {
                    throw new AssertionError("모호하지 않은데 종류를 물었다: " + id);
                });
    }

    @Test
    @DisplayName("최초 싱크는 직원을 먼저, 조직을 깊은 곳부터 만든다")
    void 최초싱크_순서() {
        // when
        List<ScimRequest> requests = ScimRequestRenderer.최초싱크(CHART);

        // then
        assertThat(requests).hasSize(
                CHART.snapshot().users().size() + CHART.snapshot().groups().size());

        int 첫조직 = requests.indexOf(requests.stream()
                .filter(request -> request.path().equals(ScimRequestRenderer.GROUPS))
                .findFirst().orElseThrow());
        assertThat(requests.subList(0, 첫조직))
                .as("조직 요청 앞에는 직원 요청만 있어야 한다")
                .allSatisfy(request -> assertThat(request.path()).isEqualTo(ScimRequestRenderer.USERS));

        // 하위 조직은 자기 부모보다 먼저 만들어져야 한다 — 그래야 멤버가 가리키는 조직이 이미 있다
        var 조직순서 = requests.subList(첫조직, requests.size()).stream()
                .map(request -> ((ScimGroup) request.body()).externalId())
                .toList();
        for (DirectoryGroup group : CHART.snapshot().groups().values()) {
            String 부모 = CHART.부모(group.id());
            if (부모 != null) {
                assertThat(조직순서.indexOf(group.id()))
                        .as("조직 %s 는 부모 %s 보다 먼저 생성돼야 한다", group.id(), 부모)
                        .isLessThan(조직순서.indexOf(부모));
            }
        }
    }

    @Test
    @DisplayName("생성 요청만 조직도 아이디를 들고 간다 — 그 밖의 요청은 null 이다")
    void 생성_요청만_차트아이디를_든다() {
        // given
        var 직원 = CHART.snapshot().users().get(CHART.landmarks().L6직속직원());
        var 조직 = CHART.snapshot().groups().get(CHART.landmarks().대상팀());

        // when
        var 요청들 = List.of(
                ScimRequestRenderer.직원생성(직원), ScimRequestRenderer.조직생성(조직),
                ScimRequestRenderer.직원교체(직원), ScimRequestRenderer.직원비활성(직원.id()),
                ScimRequestRenderer.직원삭제(직원.id()), ScimRequestRenderer.조직교체(조직),
                ScimRequestRenderer.멤버추가(조직.id(), MemberRef.user("x")), ScimRequestRenderer.조직삭제(조직.id()));

        // then
        assertThat(요청들).extracting(ScimRequest::method, ScimRequest::차트아이디).containsExactly(
                tuple("POST", 직원.id()), tuple("POST", 조직.id()),
                tuple("PUT", null), tuple("PATCH", null),
                tuple("DELETE", null), tuple("PUT", null),
                tuple("PATCH", null), tuple("DELETE", null));
    }

    @Test
    @DisplayName("직원 생성 요청을 서버가 해석하면 조직도의 직원이 그대로 나온다 — 조직도 아이디는 요청이 들고 간다")
    void 직원_본문이_왕복한다() {
        // given
        CHART.snapshot().users().values().forEach(심은것 -> {
            // when
            var 요청 = ScimRequestRenderer.직원생성(심은것);
            var 읽힌것 = ScimMapper.toDirectoryUser((ScimUser) 요청.body(), 요청.차트아이디());

            // then — 서버가 id 를 발급하므로 아이디는 본문에서 오지 않고 요청이 따로 들고 간다
            assertThat(읽힌것.id()).isEqualTo(심은것.id());
            assertThat(읽힌것.userName()).isEqualTo(심은것.userName());
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            assertThat(읽힌것.email()).isEqualTo(심은것.email());
            assertThat(읽힌것.active()).isEqualTo(심은것.active());
        });
    }

    @Test
    @DisplayName("조직 생성 요청을 서버가 해석하면 조직코드와 멤버십이 그대로 나온다 — 조직도 아이디는 요청이 들고 가고 externalId 로도 간다")
    void 조직_본문이_왕복한다() {
        // given
        CHART.snapshot().groups().values().forEach(심은것 -> {
            // when
            var 요청 = ScimRequestRenderer.조직생성(심은것);
            var 읽힌것 = ScimMapper.toDirectoryGroup((ScimGroup) 요청.body(), 요청.차트아이디(), 추정금지).block();

            // then — 조직도 아이디는 externalId 속성으로도 실려 간다(서버 id 는 따로 발급된다)
            assertThat(읽힌것).isNotNull();
            assertThat(읽힌것.id()).isEqualTo(심은것.id());
            assertThat(읽힌것.externalId()).isEqualTo(심은것.id());
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            assertThat(읽힌것.members()).isEqualTo(심은것.members());
        });
    }

    @Test
    @DisplayName("조직 본문의 멤버는 아이디 순으로 나간다 — 시드 파일 바이트가 실행마다 같아야 한다")
    void 멤버를_정렬해서_내보낸다() {
        // given — DirectoryGroup 은 멤버를 Set.copyOf 로 담아 순회 순서가 JVM 실행마다 다르다.
        // 20명이면 정렬 없이 우연히 정렬된 순서가 나올 일은 없다
        List<String> 아이디들 = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> "u%02d".formatted(i)).toList();
        var group = new DirectoryGroup("DEV", null, "개발",
                아이디들.stream().map(MemberRef::user).collect(java.util.stream.Collectors.toSet()));

        // when
        var body = (ScimGroup) ScimRequestRenderer.조직생성(group).body();

        // then
        assertThat(body.members()).extracting(ScimMember::value).containsExactlyElementsOf(아이디들);
    }

    @Test
    @DisplayName("멤버 추가 PATCH 가 JSON 을 거쳐도 그 한 명만 늘어난다")
    void 멤버추가가_한명만_늘린다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        MemberRef 신입 = MemberRef.user("new.hire");

        // when
        var after = 적용한다(before,
                JSON을_거친다(ScimRequestRenderer.멤버추가(before.id(), 신입)), 추정금지);

        // then
        assertThat(after).isNotNull();
        assertThat(after.members()).containsAll(before.members()).contains(신입);
        assertThat(after.members()).hasSize(before.members().size() + 1);
    }

    @Test
    @DisplayName("멤버 여럿 추가 PATCH 가 JSON 을 거쳐도 그 목록만큼만 늘어난다 — 직원과 하위 조직을 함께")
    void 멤버들추가가_목록만큼_늘린다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        List<MemberRef> 신입들 = List.of(MemberRef.user("new.a"), MemberRef.user("new.b"), MemberRef.group("NEW_SUB"));

        // when
        var 요청 = ScimRequestRenderer.멤버들추가(before.id(), 신입들);
        var after = 적용한다(before, JSON을_거친다(요청), 추정금지);

        // then
        assertThat(요청.method()).isEqualTo("PATCH");
        assertThat(요청.path()).isEqualTo(ScimRequestRenderer.GROUPS + "/" + before.id());
        assertThat(요청.차트아이디()).isNull();
        assertThat(after).isNotNull();
        assertThat(after.members()).containsAll(before.members()).containsAll(신입들);
        assertThat(after.members()).hasSize(before.members().size() + 신입들.size());
    }

    @Test
    @DisplayName("필터 있는 멤버 제거는 그 한 명만 빠진다")
    void 멤버제거가_한명만_뺀다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        MemberRef 뺄사람 = before.members().iterator().next();

        // when
        var after = 적용한다(before,
                JSON을_거친다(ScimRequestRenderer.멤버제거(before.id(), 뺄사람.id())), 추정금지);

        // then
        assertThat(after).isNotNull();
        assertThat(after.members()).doesNotContain(뺄사람);
        assertThat(after.members()).hasSize(before.members().size() - 1);
    }

    @Test
    @DisplayName("필터 없는 멤버 제거는 조직을 통째로 비운다 — 일부러 만드는 형태다")
    void 필터없는_제거는_전원을_지운다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());

        // when
        var after = 적용한다(before,
                JSON을_거친다(ScimRequestRenderer.멤버전체제거(before.id())), 추정금지);

        // then
        assertThat(after).isNotNull();
        assertThat(after.members()).isEmpty();
    }

    @Test
    @DisplayName("멤버 전체 교체는 보낸 목록으로 갈아치운다")
    void 전체교체가_갈아치운다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        List<MemberRef> 새목록 = List.of(MemberRef.user("only.one"), MemberRef.group("SUB"));

        // when
        var after = 적용한다(before,
                JSON을_거친다(ScimRequestRenderer.멤버전체교체(before.id(), 새목록)), 추정금지);

        // then
        assertThat(after).isNotNull();
        assertThat(after.members()).isEqualTo(Set.copyOf(새목록));
    }

    @Test
    @DisplayName("직원 비활성 PATCH 가 active 만 내린다")
    void 비활성이_active만_내린다() throws Exception {
        // given
        var before = CHART.snapshot().users().get(CHART.landmarks().L6직속직원());

        // when
        var after = ScimPatchApplier.applyToUser(before,
                JSON을_거친다(ScimRequestRenderer.직원비활성(before.id())));

        // then
        assertThat(after.active()).isFalse();
        assertThat(after.displayName()).isEqualTo(before.displayName());
        assertThat(after.email()).isEqualTo(before.email());
    }

    @Test
    @DisplayName("같은 조직도면 같은 순서가 나온다 — 재생 시퀀스는 재현 가능해야 한다")
    void 시퀀스가_재현된다() {
        // given — DirectorySnapshot 은 Map.copyOf 로 굳어 순회 순서가 JVM 실행마다 다르다.
        // 렌더러가 그 순서를 그대로 쓰면 5천 건 재생이 실행마다 달라져, 순서에 얽힌 실패는
        // 재현되지 않고 성공은 우연일 수 있다.
        var 한번 = ScimRequestRenderer.최초싱크(CHART);

        // when
        var 두번 = ScimRequestRenderer.최초싱크(OrgChartFixture.오천명());

        // then
        assertThat(두번.stream().map(ScimRequest::설명).toList())
                .isEqualTo(한번.stream().map(ScimRequest::설명).toList());
    }

    /** 실제 요청과 같은 경로를 태운다 — 직렬화했다가 다시 읽는다. */
    private static ScimPatchOp JSON을_거친다(ScimRequest request) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(request.body()), ScimPatchOp.class);
    }
}
