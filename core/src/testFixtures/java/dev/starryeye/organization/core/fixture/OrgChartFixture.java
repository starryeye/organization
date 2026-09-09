package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 규모 E2E 용 조직도 생성기 (테스트 시나리오 문서 §2).
 *
 * <p><b>부문마다 깊이가 다르다.</b> 균일 깊이면 롤업 체인이 전부 같은 길이라, 짧은 체인이
 * 제대로 끝나는지를 한 번도 검증하지 못한다. 여기서는 L3(경영지원)부터 L6(제조)까지 흩어진다.
 *
 * <p><b>모든 중간 조직이 직속 직원과 하위 조직을 동시에 갖는다.</b> 그래야 그 조직의
 * {@code member} 에 {@code direct_member} 와 자식 롤업이 <b>둘 다</b> 기여하는 경우가 생긴다 —
 * 말단에만 직원을 두면 나오지 않는 형태다.
 *
 * <p>결정론적이다. 같은 인자면 항상 같은 조직도가 나온다 — 난수를 쓰지 않는다.
 */
public final class OrgChartFixture {

    /** 부문 하나의 층별 (조직 수, 직속 직원 수). 0 이면 그 층에서 끝난다. */
    private record 가지설계(String prefix, String 이름,
                        int l3, int l3직속, int l4, int l4직속,
                        int l5, int l5직속, int l6, int l6직속) {
    }

    private static final List<가지설계> 가지들 = List.of(
            new 가지설계("DEV", "개발부문", 4, 8, 12, 6, 40, 12, 80, 8),
            new 가지설계("MFG", "제조부문", 3, 5, 9, 5, 30, 20, 90, 18),
            new 가지설계("BIZ", "사업부문", 3, 8, 10, 7, 35, 14, 0, 0),
            new 가지설계("NEW", "신사업부문", 2, 6, 12, 18, 0, 0, 0, 0),
            new 가지설계("MGT", "경영지원부문", 10, 15, 0, 0, 0, 0, 0, 0));

    private static final int 회사직속 = 8;
    private static final int 부문직속 = 10;
    /**
     * 한 조직에 몰린 직속 직원. <b>AD 의 범위 검색을 밟히려고 일부러 크게 잡았다.</b>
     *
     * <p>Active Directory 는 다중값 속성 하나가 {@code MaxValRange} 를 넘으면 값을 나눠
     * 주는데, 그 한계선이 버전·정책에 따라 1,000/1,500/5,000 이다. 1,600 은 아래 두 개를
     * 넘긴다. 5,000 까지 넘기려면 직원이 4,500명 더 필요해 전체 테스트가 두 배로 느려지므로,
     * 실제 AD 로 검증할 때는 픽스처를 키우는 대신 <b>서버의 {@code MaxValRange} 를 낮춘다</b>
     * — 그쪽이 LDAP 정책 한 줄이다.
     *
     * <p>실제 회사의 "전사 임직원", "정규직 전체" 같은 그룹이 이 모양이다 — 조직 계층이
     * 아니라 평평한 명단이라 수천 명이 한 엔트리에 붙는다.
     */
    private static final int 대형조직직속 = 1_600;
    private static final int 빈조직수 = 5;
    /** 전체 직원의 약 3%. 깊이가 다른 두 부문에 걸친다. */
    private static final int 겸직수 = 166;
    /** 겸직에 쓰지 않고 남겨 두는 앞번호 — 소속이 하나뿐인 대표 직원 자리다. */
    private static final int 겸직_시작번호 = 5;

    private final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    private final Map<String, List<MemberRef>> members = new LinkedHashMap<>();
    private final Map<String, String> 조직명 = new LinkedHashMap<>();

    private OrgChartFixture() {
    }

    public static OrgChart 오천명() {
        return new OrgChartFixture().build();
    }

    private OrgChart build() {
        List<String> 부문코드 = new ArrayList<>();
        Map<String, List<String>> 부문별말단직원 = new LinkedHashMap<>();

        for (가지설계 설계 : 가지들) {
            부문코드.add(가지를_만든다(설계, 부문별말단직원));
        }

        // "전사공통TF" 가 아니라 "제1공장" 이다. 이름이 모델을 정확히 말해야 한다 —
        // TF 라고 하면 여러 부서에서 차출돼 <b>겸직</b>하는 조직으로 읽히는데, 여기 붙은
        // 1,600명은 이 조직에만 속한 <b>전속</b> 직원이다. 공장·콜센터처럼 하위 조직 없이
        // 직속만 많은 단위가 현실의 이 모양이고, 그것이 AD 범위 검색을 밟는 자리다.
        //
        // 모두가 멤버인 "전 임직원" 같은 평평한 명단은 이 픽스처에 없다 — 그것은 수천 명을
        // 겸직으로 만들어 랜드마크의 "소속이 하나" 전제와 DIT 의 주소속 선택을 흔든다.
        // AD 범위 검색은 두 모양 다 때리므로 결함 자체는 이것으로 재현된다.
        String 대형조직 = 조직("PLANT", "제1공장");
        직속직원을_붙인다(대형조직, 대형조직직속);
        부문코드.add(대형조직);

        List<String> 빈조직 = new ArrayList<>();
        for (int i = 0; i < 빈조직수; i++) {
            빈조직.add(조직("EMPTY_" + i, "빈조직" + i));
        }
        부문코드.addAll(빈조직);

        String 회사 = 조직("CORP", "회사");
        직속직원을_붙인다(회사, 회사직속);
        부문코드.forEach(code -> members.get(회사).add(MemberRef.group(code)));

        String 겸직 = 겸직을_만든다();

        return new OrgChart(스냅샷(), new Landmarks(
                회사,
                "DEV", "MFG", "BIZ", "NEW", "MGT",
                대형조직,
                "corp.u0",
                "mgt3_0.u0",
                "new4_0.u0",
                "biz5_0.u0",
                "mfg6_0.u0",
                겸직,
                "DEV5_0",
                "MFG6_0",
                // 이동할팀 과 이동목적지실 은 서로 부모-자식이면 안 된다 — 이동이 무의미해진다.
                // 자식은 i % 부모수 로 분배되므로 DEV5_1 의 부모는 DEV4_1 이다.
                "DEV5_1",
                "DEV4_2",
                "DEV4_0",
                빈조직));
    }

    /**
     * 한 부문 가지. 자식은 상위 층에 <b>고르게 분배</b>한다 — 한 부모에 몰리면 나머지가
     * 자식 없는 중간 조직이 되어 층 구조가 무너진다.
     */
    private String 가지를_만든다(가지설계 설계, Map<String, List<String>> 부문별말단직원) {
        List<String> l6 = 층을_만든다(설계.prefix() + "6_", 설계.이름() + " 파트", 설계.l6(), 설계.l6직속());
        List<String> l5 = 층을_만든다(설계.prefix() + "5_", 설계.이름() + " 팀", 설계.l5(), 설계.l5직속());
        List<String> l4 = 층을_만든다(설계.prefix() + "4_", 설계.이름() + " 실", 설계.l4(), 설계.l4직속());
        List<String> l3 = 층을_만든다(설계.prefix() + "3_", 설계.이름() + " 본부", 설계.l3(), 설계.l3직속());

        분배한다(l6, l5);
        분배한다(l5, l4);
        분배한다(l4, l3);

        String 부문 = 조직(설계.prefix(), 설계.이름());
        직속직원을_붙인다(부문, 부문직속);
        l3.forEach(code -> members.get(부문).add(MemberRef.group(code)));
        return 부문;
    }

    private List<String> 층을_만든다(String 접두, String 이름, int 개수, int 직속수) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 개수; i++) {
            String code = 조직(접두 + i, 이름 + i);
            직속직원을_붙인다(code, 직속수);
            codes.add(code);
        }
        return codes;
    }

    private void 분배한다(List<String> 자식들, List<String> 부모들) {
        if (부모들.isEmpty()) {
            return;
        }
        for (int i = 0; i < 자식들.size(); i++) {
            members.get(부모들.get(i % 부모들.size())).add(MemberRef.group(자식들.get(i)));
        }
    }

    private String 조직(String code, String name) {
        조직명.put(code, name);
        members.put(code, new ArrayList<>());
        return code;
    }

    private void 직속직원을_붙인다(String orgCode, int 수) {
        for (int i = 0; i < 수; i++) {
            String id = orgCode.toLowerCase() + ".u" + i;
            users.put(id, new DirectoryUser(id, null, id, "직원 " + id, id + "@example.com", true));
            members.get(orgCode).add(MemberRef.user(id));
        }
    }

    /**
     * 겸직을 만든다. <b>깊이가 다른 두 부문에 걸치게</b> 한다 — 같은 깊이끼리 걸치면
     * 롤업 체인 길이가 같아 다중 경로 검증의 값이 떨어진다.
     *
     * <p>가장 짧은 체인(경영지원 L3)과 가장 긴 체인(제조 L6)을 잇는다.
     *
     * <p>{@link #겸직_시작번호} 만큼 앞을 비워 둔다. 각 조직의 앞번호 직원을 겸직에서 빼두면
     * "이 부문 깊이의 대표 직원" 자리에 소속이 하나뿐인 직원을 세울 수 있다 — 겸직 직원을
     * 대표로 쓰면 그 직원의 롤업 체인이 둘이라 "가장 긴 체인" 같은 말이 성립하지 않는다.
     *
     * @return 대표 겸직 직원 하나 (시나리오가 잡을 손잡이)
     */
    private String 겸직을_만든다() {
        List<String> 제조파트 = members.keySet().stream()
                .filter(code -> code.startsWith("MFG6_"))
                .sorted()
                .toList();
        List<String> 경영지원팀 = members.keySet().stream()
                .filter(code -> code.startsWith("MGT3_"))
                .sorted()
                .toList();

        String 대표 = null;
        for (int i = 0; i < 겸직수; i++) {
            String 원소속 = 제조파트.get(i % 제조파트.size());
            String userId = 원소속.toLowerCase() + ".u" + (겸직_시작번호 + i / 제조파트.size());
            if (!users.containsKey(userId)) {
                throw new IllegalStateException("겸직 대상 직원이 없습니다: " + userId);
            }
            String 겸직조직 = 경영지원팀.get(i % 경영지원팀.size());
            members.get(겸직조직).add(MemberRef.user(userId));
            if (대표 == null) {
                대표 = userId;
            }
        }
        return 대표;
    }

    private DirectorySnapshot 스냅샷() {
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        members.forEach((code, refs) -> groups.put(code,
                new DirectoryGroup(code, null, 조직명.get(code), new LinkedHashSet<>(refs))));
        return new DirectorySnapshot(Map.copyOf(users), Map.copyOf(groups));
    }

    /** 조직도가 실제로 몇 명·몇 개인지. 문서의 숫자를 이것으로 맞춘다. */
    public static String 요약(OrgChart chart) {
        return "조직 %d개 / 직원 %d명 / 멤버십 %d / child 간선 %d / 튜플 %d".formatted(
                chart.snapshot().groups().size(),
                chart.snapshot().users().size(),
                chart.멤버십수(),
                chart.child간선수(),
                chart.멤버십수() + chart.child간선수());
    }
}
