package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * apply() 가 실제로 OpenFGA 를 호출하기 전, 배치 짜기 자체를 고정한다(점검 S20, 설계 2026-10-05 §5).
 *
 * <p>컨테이너 없이 순수 단위 테스트로 검증한다. 옮기는 직원의 지우기와 쓰기가 한 배치에 드는지는 최종 상태만 보면 가려지므로
 * (어느 쪽이든 결국 새 조직에 있다), end-to-end Check 로는 회귀를 잡을 수 없다 — batchesFor() 가 반환하는 리스트를 직접 검사한다.
 */
class OpenFgaRelationTupleWriterBatchGroupingTest {

    private final OpenFgaProperties properties = new OpenFgaProperties();

    private OpenFgaRelationTupleWriter writer(int 한도) {
        properties.setWriteBatchSize(한도);
        return new OpenFgaRelationTupleWriter(new StoreBootstrapper(properties), properties);
    }

    @Test
    @DisplayName("조직을 옮기는 직원의 지우기와 쓰기는 같은 배치에 든다 — 한 요청이라 원자적이다(점검 S20)")
    void 옮기는_직원은_한_배치다() {
        // given
        var 옛 = RelationTuple.directMember("kim", "DEV001");
        var 새 = RelationTuple.directMember("kim", "DEV002");
        var 다른 = RelationTuple.directMember("lee", "DEV002");

        // when
        var batches = writer(100).batchesFor(new TupleDelta(Set.of(새, 다른), Set.of(옛)));

        // then
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).deletes()).containsExactly(옛);
        assertThat(batches.get(0).writes()).containsExactlyInAnyOrder(새, 다른);
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
    @DisplayName("한 대상이 한도보다 많이 바뀌면 그 대상만 여러 배치로 나뉘고, 모든 줄이 정확히 한 번 나간다")
    void 넘치는_대상만_나뉜다() {
        // given — 한도 3, kim 이 5줄(지우기 2 + 쓰기 3), lee 가 1줄
        var 쓰기 = Set.of(RelationTuple.directMember("kim", "A"), RelationTuple.directMember("kim", "B"),
                RelationTuple.directMember("kim", "C"), RelationTuple.directMember("lee", "A"));
        var 지우기 = Set.of(RelationTuple.directMember("kim", "D"), RelationTuple.directMember("kim", "E"));

        // when
        var batches = writer(3).batchesFor(new TupleDelta(쓰기, 지우기));

        // then
        assertThat(batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(3));
        assertThat(batches.stream().flatMap(b -> b.writes().stream())).containsExactlyInAnyOrderElementsOf(쓰기);
        assertThat(batches.stream().flatMap(b -> b.deletes().stream())).containsExactlyInAnyOrderElementsOf(지우기);
        assertThat(batches).filteredOn(b -> b.tuples().stream().anyMatch(t -> t.user().equals("user:lee")))
                .singleElement()
                .satisfies(b -> assertThat(b.tuples()).extracting(RelationTuple::user).containsOnly("user:lee"));
    }

    @Test
    @DisplayName("하위 조직의 상위 바꾸기도 대상(하위 조직)이 같아 한 배치다")
    void 하위_조직_옮기기도_한_배치다() {
        // given
        var 옛 = RelationTuple.child("TEAM", "DEV001");
        var 새 = RelationTuple.child("TEAM", "DEV002");

        // when
        var batches = writer(100).batchesFor(new TupleDelta(Set.of(새), Set.of(옛)));

        // then
        assertThat(batches).singleElement().satisfies(b -> {
            assertThat(b.writes()).containsExactly(새);
            assertThat(b.deletes()).containsExactly(옛);
        });
    }

    @Test
    @DisplayName("같은 델타는 같은 배치를 만든다 — 대상 이름순")
    void 배치는_결정적이다() {
        // given
        var delta = new TupleDelta(
                IntStream.range(0, 250).mapToObj(i -> RelationTuple.directMember("u" + i, "DEV")).collect(Collectors.toSet()),
                IntStream.range(0, 50).mapToObj(i -> RelationTuple.directMember("u" + i, "OPS")).collect(Collectors.toSet()));

        // when
        var 첫번째 = writer(100).batchesFor(delta);
        var 두번째 = writer(100).batchesFor(delta);

        // then
        assertThat(첫번째).isEqualTo(두번째);
        assertThat(첫번째.stream().mapToInt(Batch::size).sum()).isEqualTo(300);
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
