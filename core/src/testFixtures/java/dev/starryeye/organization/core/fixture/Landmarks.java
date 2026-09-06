package dev.starryeye.organization.core.fixture;

import java.util.List;

/**
 * 시나리오가 잡을 손잡이. 5,000명 안에서 "어느 팀" 을 매번 찾아 헤매지 않도록 이름을 붙여 둔다.
 *
 * <p>깊이별 대표 직원이 따로 있는 이유: 인가 모델은 <b>멤버십이 위로만 흐른다</b>. 말단
 * 직원만 검증하면 "위로 롤업된다" 만 보고 <b>"아래로 안 샌다" 를 한 번도 안 본다.</b>
 * {@link #L2직속직원} 이 그 자리를 맡는다 — 부문 직속이라 하위 본부·팀에는 {@code member}
 * 가 아니어야 한다.
 *
 * @param L2직속직원 부문 직속. 아래로 새지 않는지 확인하는 대표
 * @param L3직속직원 경영지원 부문(L3에서 끝남). 가장 짧은 롤업 체인
 * @param L4직속직원 신사업 부문(L4에서 끝남)
 * @param L5직속직원 사업 부문(L5에서 끝남)
 * @param L6직속직원 제조 부문(L6까지). 가장 긴 롤업 체인
 * @param 겸직직원 깊이가 다른 두 부문에 걸친 직원. 경로 둘이 동시에 성립해야 한다
 * @param 대상팀 직원 추가·삭제를 가할 L5 팀
 * @param 대상파트 직원 삭제를 가할 L6 파트
 * @param 이동할팀 부모를 바꿔 볼 L5 팀
 * @param 이동목적지실 {@code 이동할팀} 을 옮겨 붙일 다른 L4 실
 * @param 삭제할실 통째로 지워 계층을 끊을 L4 실. 그 아래 팀들이 고아가 된다
 * @param 대형조직 직속 500명. BatchCheck 청크(50)를 여러 번 넘긴다
 * @param 빈조직들 멤버가 없는 조직. 빈 델타 경로를 탄다
 */
public record Landmarks(
        String 회사,
        String 개발부문,
        String 제조부문,
        String 사업부문,
        String 신사업부문,
        String 경영지원부문,
        String 대형조직,

        String L2직속직원,
        String L3직속직원,
        String L4직속직원,
        String L5직속직원,
        String L6직속직원,
        String 겸직직원,

        String 대상팀,
        String 대상파트,
        String 이동할팀,
        String 이동목적지실,
        String 삭제할실,
        List<String> 빈조직들
) {

    public Landmarks {
        빈조직들 = List.copyOf(빈조직들);
    }
}
