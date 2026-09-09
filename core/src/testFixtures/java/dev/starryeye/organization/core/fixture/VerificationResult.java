package dev.starryeye.organization.core.fixture;

import java.util.ArrayList;
import java.util.List;

/**
 * 검증 하네스가 찾아낸 어긋남. 비어 있으면 통과다.
 *
 * <p>첫 어긋남에서 멈추지 않고 <b>전부 모아서</b> 돌려준다. 5,000명 규모에서 한 건씩
 * 고쳐가며 다시 돌리면 한 시나리오를 통과시키는 데만 여러 번을 돌려야 한다. 그리고 어긋남이
 * 한 건인지 삼천 건인지는 그 자체로 다른 진단이다 — 전자는 특정 엔티티의 문제고 후자는
 * 경로 전체가 안 돈 것이다.
 */
public record VerificationResult(List<String> 어긋남) {

    /** 메시지를 이만큼만 보여준다. 3,000건을 다 찍으면 로그에서 원인을 못 찾는다. */
    private static final int 표시한도 = 20;

    public VerificationResult {
        어긋남 = List.copyOf(어긋남);
    }

    public static VerificationResult 통과() {
        return new VerificationResult(List.of());
    }

    public boolean 어긋났는가() {
        return !어긋남.isEmpty();
    }

    public VerificationResult 합친다(VerificationResult 다른것) {
        List<String> 전부 = new ArrayList<>(어긋남);
        전부.addAll(다른것.어긋남());
        return new VerificationResult(전부);
    }

    /** 사람이 읽을 요약. 어긋남이 많으면 앞의 일부만 보이고 나머지는 건수로 남는다. */
    public String 요약() {
        if (어긋남.isEmpty()) {
            return "검증 통과";
        }
        StringBuilder sb = new StringBuilder("어긋남 %d건:".formatted(어긋남.size()));
        어긋남.stream().limit(표시한도).forEach(message -> sb.append("\n  - ").append(message));
        if (어긋남.size() > 표시한도) {
            sb.append("\n  ... 그리고 %d건 더".formatted(어긋남.size() - 표시한도));
        }
        return sb.toString();
    }
}
