package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 재활성 판정 — 직전 비활성 소속 줄(I)의 직원이 이번 그림에서 활성이면 재활성이다(설계 2026-11-24 R2~R4). */
class ReactivationGuardTest {

    private static final ReactivationGuardPolicy 기본 = new ReactivationGuardPolicy(true, 0.3, 10, 100);

    private static String id(int n) {
        return "E%02d".formatted(n);
    }

    private static Set<RelationTuple> 이전(int 기준) {
        Set<RelationTuple> i = new HashSet<>();
        for (int n = 1; n <= 기준; n++) {
            i.add(RelationTuple.directMember(id(n), "G"));
        }
        return i;
    }

    /** E01..E{기준} 가운데 앞 {활성}명은 활성, 나머지는 비활성인 그림. */
    private static DirectorySnapshot 그림(int 기준, int 활성) {
        Map<String, DirectoryUser> users = new HashMap<>();
        for (int n = 1; n <= 기준; n++) {
            users.put(id(n), new DirectoryUser(id(n), "dn" + n, "u" + n, "이름" + n, null, n <= 활성));
        }
        return new DirectorySnapshot(users, Map.of("G", new DirectoryGroup("G", "g", "조직", Set.of())), Set.of());
    }

    // 깨뜨릴 변경: 재활성이 비어도 그림′ 을 만들어 돌려주기
    @Test
    @DisplayName("재활성이 없으면 보류하지 않고 받은 그림을 그대로 준다")
    void 재활성이_없으면_보류하지_않는다() {
        // given
        DirectorySnapshot picture = 그림(3, 0);

        // when
        ReactivationCheck check = new ReactivationGuard(기본).check(이전(3), picture);

        // then
        assertThat(check.held()).isFalse();
        assertThat(check.picture()).isSameAs(picture);
        assertThat(check.hold()).isNull();
        assertThat(check.message()).isNull();
    }

    // 깨뜨릴 변경: 재활성을 active 가 아니라 존재로 세기, 문구 바꾸기
    @Test
    @DisplayName("직전 I 의 직원이 이번 그림에서 활성이면 재활성이고, 비율이 임계치를 넘으면 보류한다")
    void 직전_I_의_직원이_활성이면_재활성이다() {
        // when
        ReactivationCheck check = new ReactivationGuard(기본).check(이전(10), 그림(10, 4));

        // then
        assertThat(check.held()).isTrue();
        assertThat(check.hold().count()).isEqualTo(4);
        assertThat(check.message()).isEqualTo(
                "재활성 대상 4명(직전 비활성 소속 직원 10명의 40.0%)이 임계치 30.0%를 초과했습니다. "
                        + "강제 실행하려면 실행 기록의 reactivationHold 를 확인한 뒤 force=true 와 maxReactivations(허용할 재활성 직원 수)로 재요청하세요");
    }

    // 깨뜨릴 변경: 상한 검사를 지우거나 상한 문구 꼴을 바꾸기
    @Test
    @DisplayName("상한만 넘으면 상한 문구다")
    void 상한만_넘으면_상한_문구다() {
        // when
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(true, 0.9, 10, 2)).check(이전(10), 그림(10, 3));

        // then
        assertThat(check.held()).isTrue();
        assertThat(check.message()).contains("이 상한 2명을 초과했습니다");
    }

    // 깨뜨릴 변경: 둘 다 넘을 때 하나만 적기
    @Test
    @DisplayName("둘 다 넘으면 둘 다 적는다")
    void 둘_다_넘으면_둘_다_적는다() {
        // when
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 10, 2)).check(이전(10), 그림(10, 5));

        // then
        assertThat(check.held()).isTrue();
        assertThat(check.message()).contains("이 임계치 30.0%와 상한 2명을 초과했습니다");
    }

    // 깨뜨릴 변경: > 를 >= 로, minBaseline 비교의 >= 를 > 로 바꾸기
    @Test
    @DisplayName("경계는 초과만 걸린다 — 비율이 임계치와, 수가 상한과 같으면 통과하고, 기준이 min-baseline 과 같으면 비율을 본다")
    void 경계는_초과만_걸린다() {
        // when, then
        assertThat(new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 10, 3)).check(이전(10), 그림(10, 3)).held()).isFalse();
        assertThat(new ReactivationGuard(기본).check(이전(10), 그림(10, 4)).held()).isTrue();
        assertThat(new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 10, 3)).check(이전(10), 그림(10, 4)).held()).isTrue();
    }

    // 깨뜨릴 변경: 기준 수와 상관없이 비율을 보거나 상한을 min-baseline 뒤로 숨기기
    @Test
    @DisplayName("기준 수가 min-baseline 미만이면 비율은 보지 않고 상한은 본다")
    void 기준_수가_min_baseline_미만이면_비율은_보지_않고_상한은_본다() {
        // when, then
        assertThat(new ReactivationGuard(기본).check(이전(9), 그림(9, 9)).held()).isFalse();
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 10, 5)).check(이전(9), 그림(9, 9));
        assertThat(check.held()).isTrue();
        assertThat(check.message()).contains("이 상한 5명을 초과했습니다");
    }

    // 깨뜨릴 변경: 상한 0 이하를 "항상 넘음" 으로 읽기
    @Test
    @DisplayName("상한이 0 이하면 상한을 보지 않는다")
    void 상한_0_이하면_상한을_보지_않는다() {
        // when, then
        assertThat(new ReactivationGuard(new ReactivationGuardPolicy(true, 1.0, 10, 0)).check(이전(10), 그림(10, 10)).held()).isFalse();
        assertThat(new ReactivationGuard(new ReactivationGuardPolicy(true, 1.0, 10, -1)).check(이전(10), 그림(10, 10)).held()).isFalse();
    }

    // 깨뜨릴 변경: enabled 검사를 지우기
    @Test
    @DisplayName("가드를 끄면 보류하지 않는다")
    void 가드를_끄면_보류하지_않는다() {
        // given
        DirectorySnapshot picture = 그림(10, 10);

        // when
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(false, 0.3, 10, 1)).check(이전(10), picture);

        // then
        assertThat(check.held()).isFalse();
        assertThat(check.picture()).isSameAs(picture);
    }

    // 깨뜨릴 변경: forced 가 비율을 보게 하거나 M 을 다르게 읽기
    @Test
    @DisplayName("forced 는 M명까지 통과하고 넘으면 보류한다 — 기준 수가 1이어도 비율은 보지 않는다")
    void forced_는_M명까지_통과하고_넘으면_보류한다() {
        // when, then
        assertThat(ReactivationGuard.forced(3).check(이전(10), 그림(10, 3)).held()).isFalse();
        assertThat(ReactivationGuard.forced(3).check(이전(10), 그림(10, 4)).held()).isTrue();
        assertThat(ReactivationGuard.forced(3).check(이전(1), 그림(1, 1)).held()).isFalse();
        assertThat(ReactivationGuard.forced(3).check(이전(3), 그림(3, 3)).held()).isFalse();
    }

    // 깨뜨릴 변경: 그림에 없는 직원을 기준에서 빼기 — 그러면 기준이 8 이 되어 3/8 로 걸린다
    @Test
    @DisplayName("그림에 없는 직원은 재활성이 아니고 기준에는 든다")
    void 그림에_없는_직원은_재활성이_아니고_기준에는_든다() {
        // given — I 의 10명 가운데 E09·E10 은 그림에 없고, E01~E03 은 활성, E04~E08 은 비활성
        DirectorySnapshot picture = 그림(8, 3);

        // when
        ReactivationCheck check = new ReactivationGuard(기본).check(이전(10), picture);

        // then — 기준 10, 재활성 3(30%) 이라 임계치 초과가 아니다
        assertThat(check.held()).isFalse();
    }

    // 깨뜨릴 변경: 접두사 없이 자르거나 group: 줄을 기준에 넣기
    @Test
    @DisplayName("user: 접두사가 아닌 줄은 기준 수에 들지 않고 예외도 없다")
    void user_접두사가_아닌_줄은_세지_않는다() {
        // given
        Set<RelationTuple> i = 이전(1);
        i.add(new RelationTuple("group:X", "direct_member", "group:G"));

        // when
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 1, 0)).check(i, 그림(1, 1));

        // then
        assertThat(check.held()).isTrue();
        assertThat(check.message()).contains("직전 비활성 소속 직원 1명의 100.0%");
    }

    // 깨뜨릴 변경: 줄 수로 세기
    @Test
    @DisplayName("같은 직원의 줄이 여럿이어도 한 명이다")
    void 같은_직원의_줄_여럿은_한_명이다() {
        // given
        Set<RelationTuple> i = new HashSet<>();
        i.add(RelationTuple.directMember("E01", "G1"));
        i.add(RelationTuple.directMember("E01", "G2"));
        i.add(RelationTuple.directMember("E01", "G3"));

        // when
        ReactivationCheck check = new ReactivationGuard(new ReactivationGuardPolicy(true, 0.3, 1, 0)).check(i, 그림(1, 1));

        // then
        assertThat(check.hold().count()).isEqualTo(1);
        assertThat(check.message()).contains("직전 비활성 소속 직원 1명의 100.0%");
    }

    // 깨뜨릴 변경: 그림′ 에서 다른 직원까지 바꾸거나 조직·지정 연결을 지우기
    @Test
    @DisplayName("그림′ 은 재활성 직원만 비활성이고 나머지는 그대로다")
    void 그림_프라임은_재활성_직원만_비활성이다() {
        // given
        DirectorySnapshot base = 그림(10, 4);
        Map<String, DirectoryUser> users = new HashMap<>(base.users());
        users.put("OTHER", new DirectoryUser("OTHER", "dnX", "x", "다른", null, true));
        DirectorySnapshot picture = new DirectorySnapshot(users, base.groups(), base.assignedEdges());

        // when
        ReactivationCheck check = new ReactivationGuard(기본).check(이전(10), picture);

        // then
        assertThat(check.held()).isTrue();
        for (int n = 1; n <= 4; n++) {
            assertThat(check.picture().users().get(id(n)).active()).isFalse();
        }
        assertThat(check.picture().users().get("OTHER").active()).isTrue();
        assertThat(check.picture().users().get(id(5))).isEqualTo(picture.users().get(id(5)));
        assertThat(check.picture().groups()).isEqualTo(picture.groups());
        assertThat(check.picture().assignedEdges()).isEqualTo(picture.assignedEdges());
    }

    // 깨뜨릴 변경: 정렬을 빼거나 SAMPLE_SIZE 를 바꾸기
    @Test
    @DisplayName("내역은 재활성 직원 수와 사전순 앞 10개 id 다")
    void 내역은_사전순_앞_10개다() {
        // given — E15…E01 순서로 넣는다
        Set<RelationTuple> i = new LinkedHashSet<>();
        Map<String, DirectoryUser> users = new HashMap<>();
        for (int n = 15; n >= 1; n--) {
            i.add(RelationTuple.directMember(id(n), "G"));
            users.put(id(n), new DirectoryUser(id(n), "dn", "u", "n", null, true));
        }

        // when
        ReactivationCheck check = new ReactivationGuard(기본).check(i, new DirectorySnapshot(users, Map.of(), Set.of()));

        // then
        assertThat(check.hold().count()).isEqualTo(15);
        assertThat(check.hold().ids()).isEqualTo(List.of("E01", "E02", "E03", "E04", "E05", "E06", "E07", "E08", "E09", "E10"));
    }
}
