package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 요청 하나 동안 보는 <b>튜플 그래프</b> — 하위 조직 멤버 줄에서 보류 목록을 뺀 것(설계 2026-10-03 §4.1). 순환 검사(§4.2)와 보류 판단(§4.3)을 한다.
 *
 * <p><b>왜 튜플 그래프인가.</b> 순환이라 쓰지 않은 연결도 멤버 줄은 남는다 — 멤버십은 IdP 가 보낸 사실이다. 그래서 멤버 줄의 그래프에는 순환이 있고 OpenFGA 에는
 * 없다. 멤버 줄로 순환을 보면 이미 보류한 연결을 지나는 경로 때문에 멀쩡한 새 연결까지 버린다.
 *
 * <p><b>위로 올라간다(점검 P2).</b> 새 연결 P ⊃ c 는 c 가 P 자신이거나 P 의 조상이면 순환이다. 조상은 보통 조직도 깊이 × 다중 부모 수라, 자손 수천 개를
 * 훑던 옛 검사보다 훨씬 적게 읽는다. 펼친 조직 수가 {@value #MAX_EXPANSIONS} 를 넘으면 {@link GroupGraphTooLargeException} 이다.
 *
 * <p>요청 하나에 하나 만든다. 보류 목록과 조직마다의 부모 목록을 요청 안에서 한 번만 읽는다 — 요청 안에서는 락이 상태를 고정한다. 요청 안에서 보류·해제한
 * 연결은 같은 요청의 뒤 검사에 반영된다.
 */
@Slf4j
final class OrgGraph {

    static final int MAX_EXPANSIONS = 10_000;

    static final Comparator<GroupEdge> 아이디순 = Comparator.comparing(GroupEdge::parent).thenComparing(GroupEdge::child);

    private static final String GROUP_PREFIX = RelationTuple.GROUP_TYPE + ":";

    /**
     * @param 남길것   순환을 만드는 새 연결을 뺀 목표
     * @param 새로_보류 이번에 보류 목록에 넣을 연결(이미 목록에 있던 것은 빼고)
     * @param 풀린_보류 목록에 있었지만 이제 목표에 남는 연결 — OpenFGA 에 실제로 있게 된 것만 목록에서 뺀다({@link #뺄것})
     */
    record 거른결과(Set<RelationTuple> 남길것, Set<GroupEdge> 새로_보류, Set<GroupEdge> 풀린_보류) {

        /** 풀린 보류 중 OpenFGA 에 이제 있는 것 — 원래 있었거나 이번에 썼다. 쓰기가 실패한 연결은 목록에 남긴다(설계 §4.4). */
        Set<GroupEdge> 뺄것(Set<RelationTuple> actual, TupleWriteResult result) {
            return 풀린_보류.stream()
                    .filter(edge -> actual.contains(edge.tuple()) || result.written().contains(edge.tuple()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private final DirectoryStateRepository state;
    private final Map<String, Set<String>> 부모들 = new HashMap<>();
    private Mono<Set<GroupEdge>> 보류;
    /** 읽은 보류 목록 — {@link #보류에서_뺀다} 가 고친다. 아직 안 읽었으면 null. */
    private Set<GroupEdge> 읽은보류;
    private int budget = MAX_EXPANSIONS;

    OrgGraph(DirectoryStateRepository state) {
        this.state = state;
    }

    /** 보류 목록. 요청 안에서 한 번만 읽고, 같은 요청의 보류·해제가 이 집합에 반영된다. */
    Mono<Set<GroupEdge>> 보류() {
        if (보류 == null) {
            보류 = state.findCutEdges()
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .<Set<GroupEdge>>map(set -> set)
                    .doOnNext(set -> 읽은보류 = set)
                    .cache();
        }
        return 보류;
    }

    /** 이 튜플들에 하위 조직 연결이 있을 때만 보류 목록을 읽는다 — 직원 연산·직원만 바뀌는 조직 연산은 읽지 않는다. */
    Mono<Set<GroupEdge>> 필요하면_보류(Set<RelationTuple> 앞, Set<RelationTuple> 뒤) {
        boolean 연결있음 = Stream.concat(앞.stream(), 뒤.stream())
                .anyMatch(tuple -> RelationTuple.CHILD.equals(tuple.relation()));
        return 연결있음 ? 보류() : Mono.just(Set.of());
    }

    /** {@code edge} 가 튜플 그래프에 순환을 만드는가 — 자식이 부모 자신이거나 부모의 조상이다. */
    Mono<Boolean> 순환인가(GroupEdge edge) {
        if (edge.parent().equals(edge.child())) {
            return Mono.just(true);
        }
        return 보류().flatMap(cut -> {
            Set<String> visited = new HashSet<>();
            visited.add(edge.parent());
            // 너비 우선 확장을 Flux.expand 에 맡긴다 — 깊은 사슬에서도 스택을 쓰지 않고, any 가 목표를 만나는 즉시 멈춘다.
            return Flux.just(edge.parent())
                    .expand(id -> 튜플_그래프의_부모들(id, cut).flatMapIterable(ids -> ids).filter(visited::add))
                    .any(edge.child()::equals);
        });
    }

    /** 보류 목록에서 뺀다 — 같은 요청의 뒤 검사가 이 연결을 그래프에 있는 것으로 본다. */
    void 보류에서_뺀다(GroupEdge edge) {
        if (읽은보류 != null) {
            읽은보류.remove(edge);
        }
    }

    /**
     * {@code after} 의 하위 조직 연결 중 OpenFGA 에 아직 없는 것을 순서대로 검사한다(설계 2026-10-03 §4.3) — 순환을 만들면 남길 것에서 빼고 보류한다.
     * 이미 OpenFGA 에 있는 연결은 검사하지 않는다(먼저 저장된 연결이 이긴다). 하위 조직 연결이 없으면 보류 목록을 읽지 않는다.
     *
     * <p>순서: 자식이 초점 조직인 연결(상위 조직이 먼저 적어 둔 연결)을 먼저, 그다음 나머지. 각각 아이디 순.
     */
    Mono<거른결과> 거른다(Set<RelationTuple> actual, Set<RelationTuple> after, String focus) {
        List<GroupEdge> 연결 = after.stream().map(GroupEdge::of).flatMap(Optional::stream).toList();
        if (연결.isEmpty()) {
            return Mono.just(new 거른결과(after, Set.of(), Set.of()));
        }
        String 초점조직 = focus.startsWith(GROUP_PREFIX) ? focus.substring(GROUP_PREFIX.length()) : "";
        return 보류().flatMap(cut -> {
            Set<GroupEdge> 풀린 = new LinkedHashSet<>();
            List<GroupEdge> 새연결 = new ArrayList<>();
            for (GroupEdge edge : 연결) {
                if (!actual.contains(edge.tuple())) {
                    새연결.add(edge);
                } else if (cut.contains(edge)) {
                    풀린.add(edge);
                }
            }
            새연결.sort(Comparator.comparing((GroupEdge edge) -> edge.child().equals(초점조직) ? 0 : 1).thenComparing(아이디순));

            Set<RelationTuple> 남길것 = new LinkedHashSet<>(after);
            Set<GroupEdge> 새로_보류 = new LinkedHashSet<>();
            return Flux.fromIterable(새연결)
                    .concatMap(edge -> 순환인가(edge).doOnNext(순환 -> {
                        if (순환) {
                            log.warn("튜플 변환 경고: 조직 '{}' → '{}' 간선이 순환을 만들어 보류합니다(보류 목록에 적음)", edge.parent(), edge.child());
                            남길것.remove(edge.tuple());
                            if (cut.add(edge)) {
                                새로_보류.add(edge);
                            }
                        } else if (cut.remove(edge)) {
                            풀린.add(edge);
                        }
                    }))
                    .then(Mono.fromSupplier(() -> new 거른결과(남길것, 새로_보류, 풀린)));
        });
    }

    /** 튜플 그래프에서 {@code id} 의 부모들 — 소속 줄(강한 일관성)에서 보류 연결을 뺀다. 요청 안에서 조직마다 한 번만 읽고, 읽을 때마다 예산을 쓴다. */
    private Mono<List<String>> 튜플_그래프의_부모들(String id, Set<GroupEdge> cut) {
        Set<String> cached = 부모들.get(id);
        Mono<Set<String>> 멤버_줄의_부모들;
        if (cached != null) {
            멤버_줄의_부모들 = Mono.just(cached);
        } else {
            if (budget-- <= 0) {
                return Mono.error(new GroupGraphTooLargeException(
                        "조직 계층이 너무 크다 — 순환 검사가 %d개 조직을 넘겼습니다: %s".formatted(MAX_EXPANSIONS, id)));
            }
            멤버_줄의_부모들 = state.findGroupIdsContaining(MemberRef.group(id))
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .<Set<String>>map(set -> set)
                    .doOnNext(set -> 부모들.put(id, set));
        }
        return 멤버_줄의_부모들.map(parents -> parents.stream()
                .filter(parent -> !cut.contains(new GroupEdge(parent, id)))
                .toList());
    }
}
