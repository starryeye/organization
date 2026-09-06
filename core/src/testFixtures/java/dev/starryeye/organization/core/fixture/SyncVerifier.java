package dev.starryeye.organization.core.fixture;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.tuple.TupleMapper;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 모든 시나리오가 매 단계 끝에 도는 공통 검증 (시나리오 문서 §1).
 *
 * <pre>
 * ① DynamoDB      state.loadAll() 이 기대 조직도와 같은가
 * ② OpenFGA 양성   기대 튜플이 전부 성립하는가
 * ③ OpenFGA 음성   후보 − 기대 튜플이 전부 성립하지 않는가
 * ④ 롤업          조상 체인이 전부 member 이고 자손·형제 가지는 아닌가
 * </pre>
 *
 * <p><b>③ 이 가장 중요하다.</b> "있어야 할 것이 있다" 만 보면 잘못 남은 튜플을 영원히 못
 * 잡는다 — 이 프로젝트가 처음부터 위험하다고 본 것(퇴사자 권한 생존)이 정확히 그 모양이다.
 * 음성 후보는 {@link TupleMapper#candidateTuples} 로 뽑는다. {@code active}·순환 필터를
 * 적용하기 <b>전</b>의 멤버십 전체라, 비활성 직원의 잔여 튜플이 여기 걸린다.
 *
 * <p><b>한계를 알고 쓴다.</b> 후보가 멤버십에서 나오므로 <b>멤버십이 아예 사라진 튜플</b>은
 * 이 검증으로도 안 잡힌다(설계 §5.4). 그것을 잡으려면 열거가 필요한데 금지돼 있다.
 *
 * <p>포트({@link DirectoryStateRepository}, {@link RelationTupleChecker})에만 의존하므로
 * LDAP·SCIM 양쪽 E2E 가 같은 것을 쓴다 — 두 커넥터를 서로 다른 잣대로 재면 "같은 조직도로
 * 같은 결과에 도달하는가" 를 물을 수 없다.
 */
public final class SyncVerifier {

    private final DirectoryStateRepository state;
    private final RelationTupleChecker checker;
    private final RollupSampling 롤업표본;

    public SyncVerifier(DirectoryStateRepository state, RelationTupleChecker checker) {
        this(state, checker, RollupSampling.기본값());
    }

    public SyncVerifier(DirectoryStateRepository state, RelationTupleChecker checker,
                        RollupSampling 롤업표본) {
        this.state = state;
        this.checker = checker;
        this.롤업표본 = 롤업표본;
    }

    /** ①~④ 를 전부 돈다. */
    public Mono<VerificationResult> 검증한다(OrgChart 기대) {
        return 상태를_대조한다(기대)
                .flatMap(상태결과 -> 튜플을_대조한다(기대)
                        .flatMap(튜플결과 -> 롤업을_대조한다(기대)
                                .map(롤업결과 -> 상태결과.합친다(튜플결과).합친다(롤업결과))));
    }

    // ---------- ① DynamoDB ----------

    /**
     * {@code externalId} 는 비교하지 않는다. LDAP 은 DN 을, SCIM 은 그쪽 식별자를 채우므로
     * 조직도가 알 수 없는 값이고, 이 값을 읽는 코드도 없다(follow-ups §4).
     */
    private Mono<VerificationResult> 상태를_대조한다(OrgChart 기대) {
        return state.loadAll().map(실제 -> {
            List<String> 어긋남 = new ArrayList<>();
            어긋남.addAll(직원을_대조한다(기대.snapshot(), 실제));
            어긋남.addAll(조직을_대조한다(기대.snapshot(), 실제));
            return new VerificationResult(어긋남);
        });
    }

    private List<String> 직원을_대조한다(DirectorySnapshot 기대, DirectorySnapshot 실제) {
        List<String> 어긋남 = new ArrayList<>();
        for (String id : 빠진것(기대.users().keySet(), 실제.users().keySet())) {
            어긋남.add("① 직원이 상태에 없다: " + id);
        }
        for (String id : 빠진것(실제.users().keySet(), 기대.users().keySet())) {
            어긋남.add("① 상태에 남아 있으면 안 되는 직원: " + id);
        }
        기대.users().forEach((id, 기대값) -> {
            DirectoryUser 실제값 = 실제.users().get(id);
            if (실제값 == null) {
                return;
            }
            어긋남.addAll(다른값(("① 직원 " + id), "userName", 기대값.userName(), 실제값.userName()));
            어긋남.addAll(다른값(("① 직원 " + id), "displayName",
                    기대값.displayName(), 실제값.displayName()));
            어긋남.addAll(다른값(("① 직원 " + id), "email", 기대값.email(), 실제값.email()));
            어긋남.addAll(다른값(("① 직원 " + id), "active", 기대값.active(), 실제값.active()));
        });
        return 어긋남;
    }

    private List<String> 조직을_대조한다(DirectorySnapshot 기대, DirectorySnapshot 실제) {
        List<String> 어긋남 = new ArrayList<>();
        for (String code : 빠진것(기대.groups().keySet(), 실제.groups().keySet())) {
            어긋남.add("① 조직이 상태에 없다: " + code);
        }
        for (String code : 빠진것(실제.groups().keySet(), 기대.groups().keySet())) {
            어긋남.add("① 상태에 남아 있으면 안 되는 조직: " + code);
        }
        기대.groups().forEach((code, 기대값) -> {
            DirectoryGroup 실제값 = 실제.groups().get(code);
            if (실제값 == null) {
                return;
            }
            어긋남.addAll(다른값(("① 조직 " + code), "displayName",
                    기대값.displayName(), 실제값.displayName()));
            // 멤버십은 집합으로 본다 — 순서는 의미가 없고 저장소마다 다르다
            어긋남.addAll(다른값(("① 조직 " + code), "members",
                    기대값.members(), 실제값.members()));
        });
        return 어긋남;
    }

    // ---------- ②③ OpenFGA ----------

    /**
     * 양성과 음성을 <b>한 번의 BatchCheck 통과</b>로 함께 본다. 기대 튜플과 음성 후보를 합쳐
     * 물으면 "실제로 있는 것" 한 집합이 나오고, 거기서 양쪽 방향의 차집합이 바로 나온다.
     * 두 번 물으면 그 사이에 상태가 변할 여지가 생기고 비용도 두 배다.
     */
    private Mono<VerificationResult> 튜플을_대조한다(OrgChart 기대) {
        Set<RelationTuple> 기대튜플 = TupleMapper.toTuples(기대.snapshot()).tuples();
        Set<RelationTuple> 후보 = TupleMapper.candidateTuples(기대.snapshot());

        Set<RelationTuple> 물어볼것 = new LinkedHashSet<>(후보);
        물어볼것.addAll(기대튜플);

        return checker.existing(물어볼것).map(실제 -> {
            List<String> 어긋남 = new ArrayList<>();
            for (RelationTuple tuple : 기대튜플) {
                if (!실제.contains(tuple)) {
                    어긋남.add("② 있어야 할 튜플이 없다: " + 읽기쉽게(tuple));
                }
            }
            for (RelationTuple tuple : 실제) {
                if (!기대튜플.contains(tuple)) {
                    어긋남.add("③ 남아 있으면 안 되는 튜플: " + 읽기쉽게(tuple));
                }
            }
            return new VerificationResult(어긋남);
        });
    }

    // ---------- ④ 롤업 ----------

    /**
     * {@code member} 관계로 따로 묻는다. ②③ 은 {@code direct_member}/{@code child} 튜플의
     * <b>존재</b>만 보므로, {@code member(P) = direct_member(P) ∪ member(자식들)} 이 실제로
     * 성립하는지는 답하지 못한다.
     *
     * <p>음성 쪽이 이 단계의 핵심이다 — <b>멤버십은 위로만 흐른다.</b> 어떤 직원이 자기 조직의
     * <b>자손</b>에 대해 {@code member} 가 되면 권한이 아래로 새는 것인데, 양성 검증만으로는
     * 절대 안 잡힌다. 있어야 할 것은 그대로 다 있기 때문이다.
     *
     * <p>전 직원을 다 도는 대신 표본을 쓴다. 5,024명 × 조상 체인이면 Check 가 수만 번이라
     * 매 단계 도는 검증으로 감당이 안 된다. 표본은 {@link RollupSampling} 이 정하고,
     * 깊이별 대표와 겸직은 <b>항상</b> 들어간다.
     */
    private Mono<VerificationResult> 롤업을_대조한다(OrgChart 기대) {
        List<String> 표본 = 롤업표본.표본을_고른다(기대);
        if (표본.isEmpty()) {
            return Mono.just(VerificationResult.통과());
        }

        Set<RelationTuple> 참이어야 = new LinkedHashSet<>();
        Set<RelationTuple> 거짓이어야 = new LinkedHashSet<>();

        for (String userId : 표본) {
            Set<String> 기대소속 = 기대.기대소속(userId);
            기대소속.forEach(org -> 참이어야.add(RelationTuple.member(userId, org)));
            새면_안되는_조직들(기대, userId, 기대소속)
                    .forEach(org -> 거짓이어야.add(RelationTuple.member(userId, org)));
        }

        Set<RelationTuple> 물어볼것 = new LinkedHashSet<>(참이어야);
        물어볼것.addAll(거짓이어야);

        return checker.existing(물어볼것).map(성립하는것 -> {
            List<String> 어긋남 = new ArrayList<>();
            for (RelationTuple tuple : 참이어야) {
                if (!성립하는것.contains(tuple)) {
                    어긋남.add("④ 롤업이 위로 안 닿는다: " + 읽기쉽게(tuple));
                }
            }
            for (RelationTuple tuple : 거짓이어야) {
                if (성립하는것.contains(tuple)) {
                    어긋남.add("④ 권한이 아래로 샌다: " + 읽기쉽게(tuple));
                }
            }
            return new VerificationResult(어긋남);
        });
    }

    /**
     * 이 직원이 {@code member} 면 안 되는 조직들.
     *
     * <p>자기 직속 조직의 <b>자손</b>이 1순위다 — 롤업 방향이 뒤집히면 정확히 여기서 터진다.
     * 형제 가지도 몇 개 넣는다. 자손만 보면 "엉뚱한 부문으로 새는" 경우를 놓친다.
     */
    private Set<String> 새면_안되는_조직들(OrgChart 기대, String userId, Set<String> 기대소속) {
        Set<String> 후보 = new LinkedHashSet<>();
        for (String org : 기대.직속조직들(userId)) {
            후보.addAll(기대.자손들(org));
            String 부모 = 기대.부모(org);
            if (부모 != null) {
                후보.addAll(기대.자식조직들(부모));
            }
        }
        후보.removeAll(기대소속);
        return 후보;
    }

    // ---------- 거들기 ----------

    /** 정렬해서 돌려준다 — 어긋남 메시지의 순서가 실행마다 달라지면 diff 로 못 본다. */
    private static Set<String> 빠진것(Set<String> 이쪽, Set<String> 저쪽) {
        Set<String> 결과 = new TreeSet<>(이쪽);
        결과.removeAll(저쪽);
        return 결과;
    }

    private static List<String> 다른값(String 대상, String 필드, Object 기대, Object 실제) {
        if (기대 == null ? 실제 == null : 기대.equals(실제)) {
            return List.of();
        }
        return List.of("%s 의 %s 가 다르다: 기대=%s 실제=%s".formatted(대상, 필드, 기대, 실제));
    }

    private static String 읽기쉽게(RelationTuple tuple) {
        return "(%s, %s, %s)".formatted(tuple.user(), tuple.relation(), tuple.object());
    }
}
