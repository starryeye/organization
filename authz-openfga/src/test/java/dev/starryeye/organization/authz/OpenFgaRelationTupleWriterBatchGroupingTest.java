package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Phase;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * apply() 가 실제로 OpenFGA 를 호출하기 전, 배치 짜기 자체를 고정한다(점검 S20, 설계 2026-10-05 §5).
 *
 * <p>컨테이너 없이 순수 단위 테스트로 검증한다. 옮기는 직원의 지우기와 쓰기가 한 배치에 드는지, 조직 연결의 지우기가 직원 배치보다 앞에 오고
 * 쓰기가 뒤에 오는지는 최종 상태만 보면 가려지므로(어느 쪽이든 결국 새 상태다), end-to-end Check 로는 회귀를 잡을 수 없다 — batchesFor() 가
 * 반환하는 리스트를 직접 검사한다.
 */
class OpenFgaRelationTupleWriterBatchGroupingTest {

    private final OpenFgaProperties properties = new OpenFgaProperties();

    private OpenFgaRelationTupleWriter writer(int 한도) {
        properties.setWriteBatchSize(한도);
        return new OpenFgaRelationTupleWriter(new StoreBootstrapper(properties), properties);
    }

    @Test
    @DisplayName("조직을 옮기는 직원의 지우기와 쓰기는 같은 배치에 든다 — 사이에 다른 직원이 끼어도 한 요청이라 원자적이다(점검 S20)")
    void 옮기는_직원은_한_배치다() {
        // given — 한도 2. 대상 이름순으로 choi(1줄), kim(지우기 1 + 쓰기 1), lee(1줄)
        var 옛 = RelationTuple.directMember("kim", "DEV001");
        var 새 = RelationTuple.directMember("kim", "DEV002");
        var 앞 = RelationTuple.directMember("choi", "DEV002");
        var 뒤 = RelationTuple.directMember("lee", "DEV002");

        // when
        var batches = writer(2).batchesFor(new TupleDelta(Set.of(앞, 새, 뒤), Set.of(옛)));

        // then — 대상을 모르고 지우기 먼저·쓰기 나중으로 채우면 kim 의 지우기와 쓰기가 갈라진다
        assertThat(batches).containsExactly(
                new Batch(List.of(앞), List.of()),
                new Batch(List.of(새), List.of(옛)),
                new Batch(List.of(뒤), List.of()));
    }

    @Test
    @DisplayName("한 대상의 묶음은 배치 사이에서 쪼개지지 않는다 — 한도를 넘기면 다음 배치로 통째 넘어간다")
    void 대상_묶음은_쪼개지지_않는다() {
        // given — 한도 4, 대상 kim·lee 가 각각 3줄
        var delta = new TupleDelta(
                Set.of(RelationTuple.directMember("kim", "A"), RelationTuple.directMember("kim", "B"),
                        RelationTuple.directMember("lee", "A"), RelationTuple.directMember("lee", "B")),
                Set.of(RelationTuple.directMember("kim", "C"), RelationTuple.directMember("lee", "C")));

        // when
        var batches = writer(4).batchesFor(delta);

        // then
        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch ->
                assertThat(batch.tuples()).extracting(RelationTuple::user).containsOnly(batch.tuples().get(0).user()));
    }

    @Test
    @DisplayName("한 대상이 한도보다 많이 바뀌면 그 대상만 여러 배치로 나뉜다 — 지우기 조각은 직원 단계에, 쓰기 조각은 그 뒤 넘침 쓰기 단계에 들고, 모든 줄이 정확히 한 번 나간다")
    void 넘치는_대상만_나뉜다() {
        // given — 한도 3, kim 이 5줄(지우기 2 + 쓰기 3), lee 가 1줄
        var 쓰기 = Set.of(RelationTuple.directMember("kim", "A"), RelationTuple.directMember("kim", "B"),
                RelationTuple.directMember("kim", "C"), RelationTuple.directMember("lee", "A"));
        var 지우기 = Set.of(RelationTuple.directMember("kim", "D"), RelationTuple.directMember("kim", "E"));

        // when
        var batches = writer(3).batchesFor(new TupleDelta(쓰기, 지우기));

        // then — 같은 단계면 조각들이 동시에 나가, 지우기 조각이 실패하고 쓰기 조각이 떨어진 kim 이 옛 소속과 새 소속을 함께 갖는다
        assertThat(batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(3));
        assertThat(batches).extracting(Batch::단계).containsExactly(Phase.직원, Phase.직원, Phase.직원_넘침_쓰기);
        assertThat(batches).filteredOn(b -> b.단계() == Phase.직원)
                .allSatisfy(b -> assertThat(b.writes()).extracting(RelationTuple::user).doesNotContain("user:kim"));
        assertThat(batches).filteredOn(b -> b.단계() == Phase.직원_넘침_쓰기).allSatisfy(b -> {
            assertThat(b.deletes()).isEmpty();
            assertThat(b.writes()).extracting(RelationTuple::user).containsOnly("user:kim");
        });
        assertThat(batches.stream().flatMap(b -> b.writes().stream())).containsExactlyInAnyOrderElementsOf(쓰기);
        assertThat(batches.stream().flatMap(b -> b.deletes().stream())).containsExactlyInAnyOrderElementsOf(지우기);
        assertThat(batches).filteredOn(b -> b.tuples().stream().anyMatch(t -> t.user().equals("user:lee")))
                .singleElement()
                .satisfies(b -> assertThat(b.tuples()).extracting(RelationTuple::user).containsOnly("user:lee"));
    }

    @Test
    @DisplayName("하위 조직의 상위 바꾸기는 조직 지우기 단계와 조직 쓰기 단계로 나뉜다 — 직원과 달리 한 배치가 아니다")
    void 하위_조직_옮기기는_두_단계로_나뉜다() {
        // given
        var 옛 = RelationTuple.child("TEAM", "DEV001");
        var 새 = RelationTuple.child("TEAM", "DEV002");

        // when
        var batches = writer(100).batchesFor(new TupleDelta(Set.of(새), Set.of(옛)));

        // then — 일부러 갈린다. 옛 연결을 먼저 빼 두면 어느 시점에 멈춰도 권한이 늘어나는 쪽으로 새지 않는다
        assertThat(batches).containsExactly(
                new Batch(List.of(), List.of(옛), Phase.조직_지우기),
                new Batch(List.of(새), List.of(), Phase.조직_쓰기));
    }

    @Test
    @DisplayName("조직 단계는 대상과 상관없이 크기로만 담는다 — 서로 다른 조직의 연결이 한 배치에 든다")
    void 조직_단계는_크기로만_담는다() {
        // given — 한도 2, 조직 지우기 5줄과 조직 쓰기 3줄
        var 지우기 = IntStream.range(0, 5).mapToObj(i -> RelationTuple.child("T" + i, "OLD")).collect(Collectors.toSet());
        var 쓰기 = IntStream.range(0, 3).mapToObj(i -> RelationTuple.child("T" + i, "NEW")).collect(Collectors.toSet());

        // when
        var batches = writer(2).batchesFor(new TupleDelta(쓰기, 지우기));

        // then
        assertThat(batches).extracting(Batch::단계).containsExactly(
                Phase.조직_지우기, Phase.조직_지우기, Phase.조직_지우기, Phase.조직_쓰기, Phase.조직_쓰기);
        assertThat(batches).extracting(Batch::size).containsExactly(2, 2, 1, 2, 1);
        assertThat(batches.stream().flatMap(b -> b.deletes().stream())).containsExactlyInAnyOrderElementsOf(지우기);
        assertThat(batches.stream().flatMap(b -> b.writes().stream())).containsExactlyInAnyOrderElementsOf(쓰기);
    }

    @Test
    @DisplayName("조직의 상위 바꾸기와 직원의 소속 바꾸기가 한 델타에 섞이면 조직 지우기, 직원, 조직 쓰기 순으로 나간다")
    void 조직_지우기_직원_조직_쓰기_순이다() {
        // given — 조직 C 가 X 에서 B 로 옮기고, kim 이 C 에서 빠지고, lee 가 DEV001 에서 DEV002 로 옮긴다. 한도 2
        var C의_옛_상위 = RelationTuple.child("C", "X");
        var C의_새_상위 = RelationTuple.child("C", "B");
        var kim이_빠진다 = RelationTuple.directMember("kim", "C");
        var lee의_옛 = RelationTuple.directMember("lee", "DEV001");
        var lee의_새 = RelationTuple.directMember("lee", "DEV002");

        // when
        var batches = writer(2).batchesFor(new TupleDelta(
                Set.of(C의_새_상위, lee의_새), Set.of(C의_옛_상위, kim이_빠진다, lee의_옛)));

        // then — group: 이 user: 보다 이름순으로 앞서지만 단계가 이름순을 이긴다. 그렇지 않으면 C 의 새 연결이 kim 이 빠지기 전에 먼저 나가
        // 그 사이 kim 이 B 의 구성원이 된다
        assertThat(batches).extracting(Batch::단계)
                .containsExactly(Phase.조직_지우기, Phase.직원, Phase.직원, Phase.조직_쓰기);
        assertThat(batches).containsExactly(
                new Batch(List.of(), List.of(C의_옛_상위), Phase.조직_지우기),
                new Batch(List.of(), List.of(kim이_빠진다), Phase.직원),
                new Batch(List.of(lee의_새), List.of(lee의_옛), Phase.직원),
                new Batch(List.of(C의_새_상위), List.of(), Phase.조직_쓰기));
    }

    @Test
    @DisplayName("같은 원소를 다른 순서로 넣은 델타도 같은 배치를 만들고, 각 단계는 이름이 가장 앞선 대상에서 시작한다")
    void 배치는_결정적이다() {
        // given — 같은 줄들을 앞에서부터, 뒤에서부터 넣은 두 델타
        List<RelationTuple> 쓰기 = new ArrayList<>(IntStream.range(0, 250).mapToObj(i -> RelationTuple.directMember("u" + i, "DEV")).toList());
        쓰기.addAll(IntStream.range(0, 30).mapToObj(i -> RelationTuple.child("T" + i, "DEV")).toList());
        List<RelationTuple> 지우기 = new ArrayList<>(IntStream.range(0, 50).mapToObj(i -> RelationTuple.directMember("u" + i, "OPS")).toList());
        지우기.addAll(IntStream.range(0, 30).mapToObj(i -> RelationTuple.child("T" + i, "OPS")).toList());
        var 앞에서부터 = new TupleDelta(new LinkedHashSet<>(쓰기), new LinkedHashSet<>(지우기));
        var 뒤에서부터 = new TupleDelta(new LinkedHashSet<>(역순(쓰기)), new LinkedHashSet<>(역순(지우기)));

        // when
        var 첫번째 = writer(100).batchesFor(앞에서부터);
        var 두번째 = writer(100).batchesFor(뒤에서부터);

        // then
        assertThat(첫번째).isEqualTo(두번째);
        assertThat(첫번째.stream().mapToInt(Batch::size).sum()).isEqualTo(360);
        assertThat(첫째_대상(첫번째, Phase.조직_지우기)).isEqualTo("group:T0");
        assertThat(첫째_대상(첫번째, Phase.직원)).isEqualTo("user:u0");
        assertThat(첫째_대상(첫번째, Phase.조직_쓰기)).isEqualTo("group:T0");
    }

    private static <T> List<T> 역순(List<T> 목록) {
        List<T> 결과 = new ArrayList<>(목록);
        Collections.reverse(결과);
        return 결과;
    }

    /** 그 단계의 첫 배치의 첫 줄의 대상 */
    private static String 첫째_대상(List<Batch> batches, Phase 단계) {
        return batches.stream().filter(b -> b.단계() == 단계).findFirst().orElseThrow().tuples().get(0).user();
    }

    @Test
    @DisplayName("한 델타의 쓰기와 지우기가 겹치면 거부한다 — 만드는 쪽이 지키는 약속이고, 어기면 같은 줄이 쓰이고 지워진다")
    void 쓰기와_지우기가_겹치면_거부한다() {
        // given
        var 겹침 = RelationTuple.directMember("kim", "DEV001");
        var delta = new TupleDelta(Set.of(겹침, RelationTuple.directMember("lee", "DEV001")), Set.of(겹침));

        // when, then
        assertThatThrownBy(() -> writer(100).batchesFor(delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("겹친")
                .hasMessageContaining("user:kim");
    }

    @Test
    @DisplayName("겹치는 델타를 받은 apply 는 던지지 않고 오류 신호를 돌려준다 — 호출자의 오류 처리(onError)를 지난다")
    void 겹치면_apply_는_오류_신호를_돌려준다() {
        // given
        var 겹침 = RelationTuple.directMember("kim", "DEV001");
        var delta = new TupleDelta(Set.of(겹침), Set.of(겹침));
        AtomicReference<Mono<TupleWriteResult>> 결과 = new AtomicReference<>();

        // when
        Throwable 던진것 = catchThrowable(() -> 결과.set(writer(100).apply(delta)));

        // then
        assertThat(던진것).isNull();
        StepVerifier.create(결과.get())
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("겹친"))
                .verify();
    }

    @Test
    @DisplayName("지우기와 쓰기가 섞인 배치의 요청은 둘 다 담는다 — 한 Write 요청이라 원자적이다")
    void 섞인_배치의_요청은_지우기와_쓰기를_함께_담는다() {
        // given
        var 옛 = RelationTuple.directMember("kim", "DEV001");
        var 새 = RelationTuple.directMember("kim", "DEV002");

        // when
        var request = writer(100).toRequest(new Batch(List.of(새), List.of(옛)));

        // then
        assertThat(request.getWrites()).singleElement().satisfies(key -> assertThat(key)
                .extracting(ClientTupleKey::getUser, ClientTupleKey::getRelation, ClientTupleKey::getObject)
                .containsExactly(새.user(), 새.relation(), 새.object()));
        assertThat(request.getDeletes()).singleElement().satisfies(key -> assertThat(key)
                .extracting(ClientTupleKeyWithoutCondition::getUser, ClientTupleKeyWithoutCondition::getRelation,
                        ClientTupleKeyWithoutCondition::getObject)
                .containsExactly(옛.user(), 옛.relation(), 옛.object()));
    }
}
