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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    @DisplayName("모르는 아이디는 그대로 둔다 — 아직 없거나 지워진 리소스를 가리키는 멤버")
    void 모르는_아이디는_그대로_둔다() {
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
    @DisplayName("조직도를 서버 아이디로 바꾼다 — 직원·조직 키와 멤버, 랜드마크, 지워진 멤버십까지")
    void 조직도를_번역한다() {
        // given
        var 번역부 = new ScimIdBook();
        번역부.기록한다("kim", "u-1");
        번역부.기록한다("lee", "u-2");
        번역부.기록한다("DEV", "g-1");
        번역부.기록한다("SUB", "g-2");
        번역부.기록한다("EMPTY", "g-3");
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
        assertThat(바뀐.landmarks().회사()).isEqualTo("g-1");
        assertThat(바뀐.landmarks().L2직속직원()).isEqualTo("u-1");
        assertThat(바뀐.landmarks().빈조직들()).containsExactly("g-3");
        assertThat(바뀐.지워진멤버십()).containsExactly(new Membership("g-1", MemberRef.user("u-2")));
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

    private static OrgChart 작은_조직도() {
        var 직원들 = Map.of(
                "kim", new DirectoryUser("kim", null, "kim", "김", null, true),
                "lee", new DirectoryUser("lee", null, "lee", "이", null, true));
        var 조직들 = Map.of(
                "DEV", new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim"), MemberRef.group("SUB"))),
                "SUB", new DirectoryGroup("SUB", null, "하위", Set.of()),
                "EMPTY", new DirectoryGroup("EMPTY", null, "빈", Set.of()));
        var 랜드마크 = new Landmarks("DEV", "-", "-", "-", "-", "-", "SUB",
                "kim", "-", "-", "-", "-", "-",
                "-", "-", "-", "-", "-", List.of("EMPTY"));
        return new OrgChart(new DirectorySnapshot(직원들, 조직들), 랜드마크,
                Set.of(new Membership("DEV", MemberRef.user("lee"))));
    }
}
