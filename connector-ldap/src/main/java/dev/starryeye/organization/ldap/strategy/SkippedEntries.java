package dev.starryeye.organization.ldap.strategy;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 한 검색에서 건너뛴 엔트리를 사유별로 모아 경고 한 줄로 남긴다(설계 2026-10-05 §3.4). 엔트리마다 한 줄을 남기면 10만 명 디렉터리에서 경고가 10만 줄이다.
 * 건너뛰는 사유는 셋이고 규칙은 하나다 — 그 엔트리는 자리를 정할 수 없으니 빼고 계속하되, 받은 것이 있는데 하나도 남지 않으면 멈춘다.
 */
@Slf4j
final class SkippedEntries {

    enum 사유 {
        식별_속성_없음, 아이디_겹침, 부모_조직_없음
    }

    static final int 예시_수 = 5;

    private final String 검색;
    private final String 식별속성;
    private final Map<사유, Integer> 건수 = new EnumMap<>(사유.class);
    private final Map<사유, List<String>> 예시 = new EnumMap<>(사유.class);

    /**
     * @param 검색     "직원"·"조직" — 메시지에 "직원 검색" 처럼 실린다
     * @param 식별속성 이 검색의 식별 속성 이름 — 없음 사유의 설명에 싣는다(이름이 틀린 설정을 바로 알아보게)
     */
    SkippedEntries(String 검색, String 식별속성) {
        this.검색 = 검색;
        this.식별속성 = 식별속성;
    }

    void 기록한다(사유 사유, String 예시문구) {
        건수.merge(사유, 1, Integer::sum);
        List<String> 목록 = 예시.computeIfAbsent(사유, k -> new ArrayList<>());
        if (목록.size() < 예시_수) {
            목록.add(예시문구);
        }
    }

    int 건수(사유 사유) {
        return 건수.getOrDefault(사유, 0);
    }

    String 요약() {
        int 합계 = 건수.values().stream().mapToInt(Integer::intValue).sum();
        String 내용 = 건수.entrySet().stream()
                .map(e -> {
                    int 남은예시 = e.getValue() - 예시.get(e.getKey()).size();
                    return "%s %d건(예: %s%s)".formatted(설명(e.getKey()), e.getValue(),
                            String.join("; ", 예시.get(e.getKey())), 남은예시 > 0 ? " 외 " + 남은예시 + "건" : "");
                })
                .collect(Collectors.joining(", "));
        return "%s 검색에서 엔트리 %d건을 건너뛰었다 — %s".formatted(검색, 합계, 내용);
    }

    /** 건너뛴 것이 있으면 경고 한 줄. 없으면 아무것도 남기지 않는다 */
    void 요약을_남긴다() {
        if (!건수.isEmpty()) {
            log.warn("{}", 요약());
        }
    }

    /** 받은 엔트리가 있는데 하나도 남지 않으면 데이터 오류다 — 그 회차는 권한이 0 이다. 일부 누락은 삭제 가드가 본다 */
    void 아무도_남지_않으면_멈춘다(int 받은_수, int 남은_수) {
        if (받은_수 > 0 && 남은_수 == 0) {
            throw new DirectoryDataException("%s 검색이 엔트리 %d건을 받았지만 하나도 남지 않았다(식별 속성 '%s') — %s"
                    .formatted(검색, 받은_수, 식별속성, 요약()));
        }
    }

    private String 설명(사유 사유) {
        return switch (사유) {
            case 식별_속성_없음 -> "식별 속성 '" + 식별속성 + "' 이 없거나 비었음";
            case 아이디_겹침 -> "아이디 겹침";
            case 부모_조직_없음 -> "부모 조직을 찾지 못해 소속 없이 적재";
        };
    }
}
