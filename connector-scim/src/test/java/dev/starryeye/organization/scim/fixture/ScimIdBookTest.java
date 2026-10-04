package dev.starryeye.organization.scim.fixture;

import dev.starryeye.organization.core.fixture.Landmarks;
import dev.starryeye.organization.core.fixture.Membership;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.OrgChartFixture;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.scim.dto.ScimGroup;
import dev.starryeye.organization.scim.dto.ScimMember;
import dev.starryeye.organization.scim.dto.ScimPatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ScimIdBookTest {

    @Test
    @DisplayName("경로의 아이디와 조직 멤버 값, PATCH 의 멤버 값·필터를 서버 아이디로 바꾼다")
    void 요청을_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("DEV", "g-1");

        // when
        var 경로 = 번역부.번역한다(ScimRequestRenderer.직원비활성("kim"));
        var 추가 = 번역부.번역한다(ScimRequestRenderer.멤버추가("DEV", MemberRef.user("kim")));
        var 제거 = 번역부.번역한다(ScimRequestRenderer.멤버제거("DEV", "kim"));

        // then
        assertThat(경로.path()).isEqualTo("/scim/v2/Users/u-1");
        assertThat(추가.path()).isEqualTo("/scim/v2/Groups/g-1");
        assertThat(((ScimPatchOp) 추가.body()).operations().get(0).value())
                .asList().extracting("value").containsExactly("u-1");
        assertThat(((ScimPatchOp) 제거.body()).operations().get(0).path()).isEqualTo("members[value eq \"u-1\"]");
    }

    @Test
    @DisplayName("조직 본문의 멤버 값도 바꾼다 — 멤버 종류는 그대로, 조직 자신의 externalId 는 건드리지 않는다")
    void 조직_본문의_멤버를_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("SUB", "g-2");
        var 조직 = new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim"), MemberRef.group("SUB")));

        // when
        var 교체 = 번역부.번역한다(ScimRequestRenderer.조직교체(조직));

        // then
        var 본문 = (ScimGroup) 교체.body();
        assertThat(교체.path()).as("DEV 는 아직 모르는 아이디라 그대로다").isEqualTo("/scim/v2/Groups/DEV");
        assertThat(본문.externalId()).isEqualTo("DEV");
        assertThat(본문.members()).extracting(ScimMember::value, ScimMember::type)
                .containsExactly(tuple("g-2", "Group"),
                        tuple("u-1", "User"));
    }

    @Test
    @DisplayName("멤버 전체 교체·전체 제거도 번역한다 — 값이 없는 연산은 그대로 둔다")
    void 전체_교체와_전체_제거를_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("SUB", "g-2");
        번역부.기록한다("DEV", "g-1");

        // when
        var 교체 = 번역부.번역한다(ScimRequestRenderer.멤버전체교체("DEV",
                List.of(MemberRef.user("kim"), MemberRef.group("SUB"))));
        var 제거 = 번역부.번역한다(ScimRequestRenderer.멤버전체제거("DEV"));

        // then
        assertThat(교체.path()).isEqualTo("/scim/v2/Groups/g-1");
        assertThat(((ScimPatchOp) 교체.body()).operations().get(0).value())
                .asList().extracting("value").containsExactly("u-1", "g-2");
        assertThat(제거.path()).isEqualTo("/scim/v2/Groups/g-1");
        assertThat(((ScimPatchOp) 제거.body()).operations().get(0).value()).isNull();
    }

    @Test
    @DisplayName("생성 요청은 번역해도 조직도 아이디를 그대로 들고 있다 — 응답을 받아 기록하는 열쇠다")
    void 생성_요청의_차트아이디는_남는다() {
        // given
        var 번역부 = new ScimIdBook();
        var 직원 = new DirectoryUser("kim", null, "kim", "김", null, true);

        // when
        var 요청 = 번역부.번역한다(ScimRequestRenderer.직원생성(직원));

        // then
        assertThat(요청.method()).isEqualTo("POST");
        assertThat(요청.path()).isEqualTo("/scim/v2/Users");
        assertThat(요청.차트아이디()).isEqualTo("kim");
    }

    @Test
    @DisplayName("POST 로 만든 적 없는 아이디는 그대로 둔다 — 서버 아이디를 모르는 리소스를 가리키는 멤버")
    void 만든_적_없는_아이디는_그대로_둔다() {
        // given
        var 번역부 = new ScimIdBook();

        // when
        var 요청 = 번역부.번역한다(ScimRequestRenderer.멤버추가("DEV", MemberRef.user("ghost")));

        // then
        assertThat(요청.path()).isEqualTo("/scim/v2/Groups/DEV");
        assertThat(((ScimPatchOp) 요청.body()).operations().get(0).value())
                .asList().extracting("value").containsExactly("ghost");
    }

    @Test
    @DisplayName("지운 리소스도 표에 남는다 — 지운 뒤에도 같은 서버 아이디로 경로를 만든다")
    void 지운_리소스의_아이디는_남는다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");

        // when — 삭제 요청을 번역한 뒤 한 번 더 번역한다
        var 삭제 = 번역부.번역한다(ScimRequestRenderer.직원삭제("kim"));
        var 그뒤 = 번역부.번역한다(ScimRequestRenderer.직원비활성("kim"));

        // then — 지웠다고 표에서 빠지지 않는다. 지운 직원의 튜플·404 를 서버 아이디로 물을 수 있어야 한다
        assertThat(삭제.method()).isEqualTo("DELETE");
        assertThat(삭제.path()).isEqualTo("/scim/v2/Users/u-1");
        assertThat(그뒤.path()).isEqualTo("/scim/v2/Users/u-1");
        assertThat(번역부.서버("kim")).isEqualTo("u-1");
    }

    @Test
    @DisplayName("생성 요청에 201 응답이 오면 본문의 id 를 그 요청의 조직도 아이디에 묶는다")
    void 생성_응답의_id를_묶는다() {
        // given
        var 번역부 = new ScimIdBook();
        var 요청 = ScimRequestRenderer.직원생성(new DirectoryUser("kim", null, "kim", "김", null, true));

        // when
        번역부.기록한다(요청, 201, "{\"id\":\"u-1\",\"userName\":\"kim\"}");

        // then
        assertThat(번역부.서버("kim")).isEqualTo("u-1");
    }

    @Test
    @DisplayName("201 인데 id 가 없거나 JSON 이 아니면 어느 요청인지 알려 주며 멈춘다")
    void 아이디_없는_201은_멈춘다() {
        // given
        var 번역부 = new ScimIdBook();
        var 요청 = ScimRequestRenderer.직원생성(new DirectoryUser("kim", null, "kim", "김", null, true));

        // when, then
        for (String 본문 : Arrays.asList(null, "", "{}", "{\"id\":\"\"}", "{\"id\":null}", "not json")) {
            assertThatThrownBy(() -> 번역부.기록한다(요청, 201, 본문))
                    .as("본문: %s", 본문)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("직원 생성 kim");
        }
        assertThat(번역부.서버("kim")).as("멈춘 기록은 남지 않는다").isEqualTo("kim");
    }

    @Test
    @DisplayName("201 이 아니거나 생성 요청이 아니면 본문을 읽지 않는다 — 실패 본문이 JSON 이 아니어도 멈추지 않는다")
    void 생성_성공이_아니면_기록하지_않는다() {
        // given
        var 번역부 = new ScimIdBook();
        var 생성 = ScimRequestRenderer.직원생성(new DirectoryUser("kim", null, "kim", "김", null, true));
        var 차트아이디_없음 = ScimRequest.post(ScimRequestRenderer.USERS, null, "차트아이디 없는 생성");

        // when
        번역부.기록한다(생성, 409, "<html>conflict</html>");
        번역부.기록한다(생성, 503, null);
        번역부.기록한다(차트아이디_없음, 201, "{\"id\":\"u-9\"}");

        // then
        assertThat(번역부.서버("kim")).isEqualTo("kim");
        assertThat(번역부.서버("u-9")).isEqualTo("u-9");
    }

    @Test
    @DisplayName("한 조직도 아이디가 두 서버 아이디로 기록되면 멈춘다 — 직원·조직 아이디가 겹친 하네스 오류")
    void 겹친_기록은_멈춘다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");

        // when, then
        assertThatThrownBy(() -> 번역부.기록한다("kim", "u-2")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 서버 아이디를 다시 기록하는 것은 허용한다 — 재시도가 같은 답을 받는 경우")
    void 같은_기록은_허용한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");

        // when
        번역부.기록한다("kim", "u-1");

        // then
        assertThat(번역부.서버("kim")).isEqualTo("u-1");
    }

    @Test
    @DisplayName("튜플을 서버 아이디로 바꾼다 — 직원·조직 어느 자리든")
    void 튜플을_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("DEV", "g-1");
        번역부.기록한다("SUB", "g-2");

        // when
        var 멤버 = 번역부.번역한다(RelationTuple.member("kim", "DEV"));
        var 하위 = 번역부.번역한다(RelationTuple.child("SUB", "DEV"));
        var 모름 = 번역부.번역한다(RelationTuple.directMember("ghost", "DEV"));

        // then
        assertThat(멤버).isEqualTo(RelationTuple.member("u-1", "g-1"));
        assertThat(하위).isEqualTo(RelationTuple.child("g-2", "g-1"));
        assertThat(모름).isEqualTo(RelationTuple.directMember("ghost", "g-1"));
    }

    @Test
    @DisplayName("조직도를 서버 아이디로 바꾼다 — 직원·조직 키와 멤버, 랜드마크 19칸 전부, 지워진 멤버십까지")
    void 조직도를_번역한다() {
        // given — 랜드마크 칸마다 서로 다른 아이디를 두고 전부 기록한다. 칸이 서로 바뀌어도 알아챌 수 있게
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("lee", "u-2");
        번역부.기록한다("DEV", "g-1");
        번역부.기록한다("SUB", "g-2");
        번역부.기록한다("EMPTY", "g-3");
        랜드마크_아이디들.forEach(id -> 번역부.기록한다(id, "서버:" + id));
        var 조직도 = 작은_조직도();

        // when
        var 바뀐 = 번역부.번역한다(조직도);

        // then
        assertThat(바뀐.snapshot().users()).containsOnlyKeys("u-1", "u-2");
        assertThat(바뀐.snapshot().users().get("u-1").id()).isEqualTo("u-1");
        assertThat(바뀐.snapshot().users().get("u-1").userName()).as("userName 은 속성이라 그대로다").isEqualTo("kim");
        assertThat(바뀐.snapshot().groups()).containsOnlyKeys("g-1", "g-2", "g-3");
        assertThat(바뀐.snapshot().groups().get("g-1").id()).isEqualTo("g-1");
        assertThat(바뀐.snapshot().groups().get("g-1").members())
                .containsExactlyInAnyOrder(MemberRef.user("u-1"), MemberRef.group("g-2"));
        assertThat(바뀐.landmarks()).as("랜드마크 칸마다 자기 아이디의 서버 아이디").isEqualTo(랜드마크(id -> "서버:" + id));
        assertThat(바뀐.지워진멤버십()).containsExactly(new Membership("g-1", MemberRef.user("u-2")));
        assertThat(조직도.landmarks()).as("원래 조직도는 건드리지 않는다").isEqualTo(랜드마크(id -> id));
        assertThat(조직도.snapshot().users()).as("원래 조직도는 건드리지 않는다").containsOnlyKeys("kim", "lee");
    }

    @Test
    @DisplayName("최초 싱크를 서버 역할로 받아 가며 번역하면 조직 본문의 멤버 값이 모두 이미 만든 리소스의 서버 아이디다")
    void 최초싱크의_모든_참조가_서버_아이디가_된다() {
        // given
        var 번역부 = new ScimIdBook();
        var 만든것 = new HashSet<String>();
        var 만들기_전_참조 = new ArrayList<String>();

        // when — 서버 역할: POST 마다 UUID 를 발급해 기록한다
        for (var 요청 : ScimRequestRenderer.최초싱크(OrgChartFixture.오천명())) {
            var 번역 = 번역부.번역한다(요청);
            if (번역.body() instanceof ScimGroup 조직) {
                조직.members().stream().map(ScimMember::value)
                        .filter(값 -> !만든것.contains(값)).forEach(만들기_전_참조::add);
            }
            var 서버아이디 = UUID.randomUUID().toString();
            번역부.기록한다(요청.차트아이디(), 서버아이디);
            만든것.add(서버아이디);
        }

        // then
        assertThat(만들기_전_참조).isEmpty();
    }

    /** {@link Landmarks} 의 칸 순서대로 — 문자열 18칸, 이어서 빈조직들 둘. 칸마다 아이디가 다르다. */
    private static final List<String> 랜드마크_아이디들 = List.of(
            "랜드.회사", "랜드.개발부문", "랜드.제조부문", "랜드.사업부문", "랜드.신사업부문", "랜드.경영지원부문", "랜드.대형조직",
            "랜드.L2직속직원", "랜드.L3직속직원", "랜드.L4직속직원", "랜드.L5직속직원", "랜드.L6직속직원", "랜드.겸직직원",
            "랜드.대상팀", "랜드.대상파트", "랜드.이동할팀", "랜드.이동목적지실", "랜드.삭제할실",
            "랜드.빈조직1", "랜드.빈조직2");

    /** 위 아이디들을 {@code 바꾼다} 로 바꿔 {@link Landmarks} 칸에 차례로 넣는다. */
    private static Landmarks 랜드마크(UnaryOperator<String> 바꾼다) {
        var 아이디 = 랜드마크_아이디들.stream().map(바꾼다).toList();
        return new Landmarks(아이디.get(0), 아이디.get(1), 아이디.get(2), 아이디.get(3), 아이디.get(4), 아이디.get(5), 아이디.get(6),
                아이디.get(7), 아이디.get(8), 아이디.get(9), 아이디.get(10), 아이디.get(11), 아이디.get(12),
                아이디.get(13), 아이디.get(14), 아이디.get(15), 아이디.get(16), 아이디.get(17),
                List.of(아이디.get(18), 아이디.get(19)));
    }

    private static OrgChart 작은_조직도() {
        var 직원들 = Map.of(
                "kim", new DirectoryUser("kim", null, "kim", "김", null, true),
                "lee", new DirectoryUser("lee", null, "lee", "이", null, true));
        var 조직들 = Map.of(
                "DEV", new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim"), MemberRef.group("SUB"))),
                "SUB", new DirectoryGroup("SUB", null, "하위", Set.of()),
                "EMPTY", new DirectoryGroup("EMPTY", null, "빈", Set.of()));
        return new OrgChart(new DirectorySnapshot(직원들, 조직들), 랜드마크(id -> id),
                Set.of(new Membership("DEV", MemberRef.user("lee"))));
    }
}
