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
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import dev.starryeye.organization.scim.dto.ScimUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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
     * 호출되면 실패한다. 렌더러는 {@code type} 을 항상 명시하므로 서버가 현재상태를 추정할
     * 일이 없어야 한다 — 추정이 일어나면 테스트 결과에 추정의 정확도가 섞여 든다.
     */
    private final MemberTypeResolver 추정금지 = id -> {
        fail("멤버 type 이 빠져 현재상태 추정이 일어났습니다: " + id);
        return Mono.empty();
    };

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
    @DisplayName("직원 생성 본문을 서버가 해석하면 조직도의 직원이 그대로 나온다")
    void 직원_본문이_왕복한다() {
        // given
        CHART.snapshot().users().values().forEach(심은것 -> {
            // when
            var 읽힌것 = ScimMapper.toDirectoryUser(
                    (ScimUser) ScimRequestRenderer.직원생성(심은것).body());

            // then — userName 이 아이디의 원천이라는 규칙이 여기서 검증된다
            assertThat(읽힌것.id()).isEqualTo(심은것.id());
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            assertThat(읽힌것.email()).isEqualTo(심은것.email());
            assertThat(읽힌것.active()).isEqualTo(심은것.active());
        });
    }

    @Test
    @DisplayName("조직 생성 본문을 서버가 해석하면 조직코드와 멤버십이 그대로 나온다")
    void 조직_본문이_왕복한다() {
        // given
        CHART.snapshot().groups().values().forEach(심은것 -> {
            // when
            var 읽힌것 = ScimMapper.toDirectoryGroup(
                    (ScimGroup) ScimRequestRenderer.조직생성(심은것).body(), 추정금지).block();

            // then — externalId 가 조직코드가 된다는 규칙(설계 §4.3)
            assertThat(읽힌것).isNotNull();
            assertThat(읽힌것.id()).isEqualTo(심은것.id());
            assertThat(읽힌것.displayName()).isEqualTo(심은것.displayName());
            assertThat(읽힌것.members()).isEqualTo(심은것.members());
        });
    }

    @Test
    @DisplayName("멤버 추가 PATCH 가 JSON 을 거쳐도 그 한 명만 늘어난다")
    void 멤버추가가_한명만_늘린다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        MemberRef 신입 = MemberRef.user("new.hire");

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                JSON을_거친다(ScimRequestRenderer.멤버추가(before.id(), 신입)), 추정금지).block();

        // then
        assertThat(after).isNotNull();
        assertThat(after.members()).containsAll(before.members()).contains(신입);
        assertThat(after.members()).hasSize(before.members().size() + 1);
    }

    @Test
    @DisplayName("필터 있는 멤버 제거는 그 한 명만 빠진다")
    void 멤버제거가_한명만_뺀다() throws Exception {
        // given
        DirectoryGroup before = CHART.snapshot().groups().get(CHART.landmarks().대상팀());
        MemberRef 뺄사람 = before.members().iterator().next();

        // when
        var after = ScimPatchApplier.applyToGroup(before,
                JSON을_거친다(ScimRequestRenderer.멤버제거(before.id(), 뺄사람.id())), 추정금지).block();

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
        var after = ScimPatchApplier.applyToGroup(before,
                JSON을_거친다(ScimRequestRenderer.멤버전체제거(before.id())), 추정금지).block();

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
        var after = ScimPatchApplier.applyToGroup(before,
                JSON을_거친다(ScimRequestRenderer.멤버전체교체(before.id(), 새목록)), 추정금지).block();

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
