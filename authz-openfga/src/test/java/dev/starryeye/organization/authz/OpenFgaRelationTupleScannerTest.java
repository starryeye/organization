package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재적재의 장부 훑기 — 이 서버에서 OpenFGA Read 를 부르는 유일한 자리다 (설계 2026-09-29 §3.2).
 */
class OpenFgaRelationTupleScannerTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleScanner scanner;

    @BeforeEach
    void 어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        scanner = new OpenFgaRelationTupleScanner(bootstrapper, properties);
    }

    @Test
    @DisplayName("장부의 모든 줄을 페이지를 넘겨 빠짐없이 읽는다")
    void 페이지를_넘겨_모두_읽는다() {
        // given — 한 페이지(100줄)를 두 번 넘길 만큼 쓴다
        Set<RelationTuple> 쓴것 = IntStream.range(0, 250)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.apply(TupleDelta.writeOnly(쓴것)).block();

        // when
        List<RelationTuple> 읽은것 = scanner.scanAll().collectList().block();

        // then — 개수까지 본다. 같은 줄을 두 번 읽어도 집합 비교만으로는 모른다
        assertThat(읽은것).hasSize(250);
        assertThat(Set.copyOf(읽은것)).isEqualTo(쓴것);
    }

    @Test
    @DisplayName("페이지 하나를 읽다 한 번 실패해도 그 페이지만 다시 읽고 끝까지 간다 — 약 1,100번 중 한 번의 흔들림으로 재적재 전체가 실패하지 않는다")
    void 페이지_읽기가_한번_실패해도_다시_읽는다() {
        // given — 세 페이지 중 둘째 페이지를 처음 읽을 때만 실패한다
        Set<RelationTuple> 쓴것 = IntStream.range(0, 250)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        writer.apply(TupleDelta.writeOnly(쓴것)).block();
        AtomicInteger 읽기 = new AtomicInteger();
        StoreBootstrapper 흔들리는_부트스트래퍼 = new StoreBootstrapper(properties) {
            @Override
            public OpenFgaClient clientFor(String storeId) {
                if (읽기.incrementAndGet() == 2) {
                    throw new IllegalStateException("일시적인 연결 끊김");
                }
                return super.clientFor(storeId);
            }
        };
        흔들리는_부트스트래퍼.resolveStore().block();
        읽기.set(0);

        // when
        List<RelationTuple> 읽은것 = new OpenFgaRelationTupleScanner(흔들리는_부트스트래퍼, properties)
                .scanAll().collectList().block(Duration.ofSeconds(30));

        // then — 개수까지 본다. 처음부터 다시 읽으면 첫 페이지가 두 번 나온다
        assertThat(읽은것).hasSize(250);
        assertThat(Set.copyOf(읽은것)).isEqualTo(쓴것);
    }

    @Test
    @DisplayName("이 서버를 거치지 않고 직접 써 넣은 줄도 나온다 — 스냅샷에 없는 찌꺼기를 찾는 이유다")
    void 직접_써넣은_줄도_나온다() throws Exception {
        // given
        bootstrapper.client().writeTuples(List.of(new ClientTupleKey()
                .user("user:ghost").relation("direct_member")._object("group:DEV001"))).get();
        writer.apply(TupleDelta.writeOnly(Set.of(RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        List<RelationTuple> 읽은것 = scanner.scanAll().collectList().block();

        // then
        assertThat(읽은것).containsExactlyInAnyOrder(
                RelationTuple.directMember("ghost", "DEV001"),
                RelationTuple.child("DEV002", "DEV001"));
    }

    @Test
    @DisplayName("방금 지운 줄은 나오지 않는다 — 캐시가 아니라 지금 있는 것을 읽는다")
    void 지운_줄은_나오지_않는다() {
        // given
        RelationTuple 남는것 = RelationTuple.directMember("kim", "DEV002");
        RelationTuple 지운것 = RelationTuple.directMember("lee", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(남는것, 지운것))).block();
        writer.apply(TupleDelta.deleteOnly(Set.of(지운것))).block();

        // when, then
        assertThat(scanner.scanAll().collectList().block()).containsExactly(남는것);
    }

    @Test
    @DisplayName("빈 장부는 아무 줄도 없다")
    void 빈_장부는_비어있다() {
        // when, then
        assertThat(scanner.scanAll().collectList().block()).isEmpty();
    }
}
