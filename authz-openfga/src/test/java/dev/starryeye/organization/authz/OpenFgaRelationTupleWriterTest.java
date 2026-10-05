package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class OpenFgaRelationTupleWriterTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;

    @BeforeEach
    void 쓰기어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object).relation(relation).user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    @Test
    @DisplayName("빈 델타는 OpenFGA를 호출하지 않고 빈 결과를 준다")
    void 빈_델타는_아무것도_하지_않는다() {
        // given, when
        var result = writer.apply(TupleDelta.empty()).block();

        // then
        assertThat(result.written()).isEmpty();
        assertThat(result.deleted()).isEmpty();
        assertThat(result.hasFailure()).isFalse();
    }

    @Test
    @DisplayName("적용에 성공한 튜플이 결과의 written 에 담긴다")
    void 성공한_튜플이_결과에_담긴다() {
        // given
        var delta = TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("lee", "DEV002")));

        // when
        var result = writer.apply(delta).block();

        // then
        assertThat(result.written()).isEqualTo(delta.toWrite());
        assertThat(result.hasFailure()).isFalse();
    }

    @Test
    @DisplayName("삭제한 튜플은 더 이상 member 로 판정되지 않는다")
    void 삭제하면_판정에서_빠진다() {
        // given
        var tuple = RelationTuple.directMember("kim", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(tuple))).block();

        // when
        var result = writer.apply(TupleDelta.deleteOnly(Set.of(tuple))).block();

        // then
        assertThat(result.deleted()).containsExactly(tuple);
        assertThat(check("user:kim", "member", "group:DEV002")).isFalse();
    }

    @Test
    @DisplayName("이미 존재하는 튜플을 다시 써도 멱등 옵션 덕분에 실패하지 않는다")
    void 중복_생성은_멱등하게_흡수된다() {
        // given
        var delta = TupleDelta.writeOnly(Set.of(RelationTuple.directMember("kim", "DEV002")));
        writer.apply(delta).block();

        // when
        var result = writer.apply(delta).block();

        // then
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.written()).isEqualTo(delta.toWrite());
    }

    @Test
    @DisplayName("존재하지 않는 튜플을 삭제해도 멱등 옵션 덕분에 실패하지 않는다")
    void 없는_튜플_삭제는_멱등하게_흡수된다() {
        // given
        var delta = TupleDelta.deleteOnly(Set.of(RelationTuple.directMember("ghost", "DEV002")));

        // when
        var result = writer.apply(delta).block();

        // then
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.deleted()).isEqualTo(delta.toDelete());
    }

    @Test
    @DisplayName("배치 한계인 100건을 넘는 델타도 나누어 전부 반영된다")
    void 배치_한계를_넘는_델타도_반영된다() {
        // given
        var tuples = IntStream.range(0, 250)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());

        // when
        var result = writer.apply(TupleDelta.writeOnly(tuples)).block();

        // then
        assertThat(result.written()).hasSize(250);
        assertThat(result.hasFailure()).isFalse();
        assertThat(check("user:user249", "member", "group:DEV002")).isTrue();
    }

    @Test
    @DisplayName("한 델타에 생성과 삭제가 섞여 있어도 한 요청으로 반영된다")
    void 생성과_삭제가_섞여도_처리된다() {
        // given
        var 기존 = RelationTuple.directMember("lee", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(기존))).block();
        var 신규 = RelationTuple.directMember("park", "DEV002");

        // when
        var result = writer.apply(new TupleDelta(Set.of(신규), Set.of(기존))).block();

        // then
        assertThat(result.written()).containsExactly(신규);
        assertThat(result.deleted()).containsExactly(기존);
        assertThat(check("user:park", "member", "group:DEV002")).isTrue();
        assertThat(check("user:lee", "member", "group:DEV002")).isFalse();
    }

    @Test
    @DisplayName("조직을 옮기는 직원은 한 요청으로 옛 조직에서 빠지고 새 조직에 든다(점검 S20)")
    void 옮기는_직원은_한_요청으로_반영된다() {
        // given
        var 옛 = RelationTuple.directMember("kim", "DEV001");
        var 새 = RelationTuple.directMember("kim", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(옛))).block();

        // when
        var result = writer.apply(new TupleDelta(Set.of(새), Set.of(옛))).block();

        // then
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.written()).containsExactly(새);
        assertThat(result.deleted()).containsExactly(옛);
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();
    }

    @Test
    @DisplayName("하위 조직의 상위를 바꾸면 구성원은 옛 상위의 권한을 잃고 새 상위의 권한을 얻는다 — 두 단계로 나가도 끝 상태는 같다")
    void 조직의_상위를_바꾸면_권한이_따라온다() {
        // given
        var 소속 = RelationTuple.directMember("kim", "TEAM");
        var 옛 = RelationTuple.child("TEAM", "DEV001");
        var 새 = RelationTuple.child("TEAM", "DEV002");
        writer.apply(TupleDelta.writeOnly(Set.of(소속, 옛))).block();
        assertThat(check("user:kim", "member", "group:DEV001")).isTrue();

        // when
        var result = writer.apply(new TupleDelta(Set.of(새), Set.of(옛))).block();

        // then
        assertThat(result.hasFailure()).isFalse();
        assertThat(result.written()).containsExactly(새);
        assertThat(result.deleted()).containsExactly(옛);
        assertThat(check("user:kim", "member", "group:DEV002")).isTrue();
        assertThat(check("user:kim", "member", "group:DEV001")).isFalse();
    }

    @Test
    @DisplayName("없는 타입의 줄 하나가 섞인 배치는 나머지를 반영하고 그 줄만 실패로 남긴다(점검 M16)")
    void 나쁜_줄_하나는_나머지를_끌고_가지_않는다() {
        // given — 정상 5줄과 모델에 없는 타입의 줄 하나를 한 배치로 보낸다
        Set<RelationTuple> 정상 = IntStream.range(0, 5)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
        RelationTuple 나쁜_줄 = new RelationTuple("user:bad", "direct_member", "nosuchtype:x");
        Set<RelationTuple> 보낼것 = new HashSet<>(정상);
        보낼것.add(나쁜_줄);

        // when
        var 결과 = writer.apply(TupleDelta.writeOnly(보낼것)).block();

        // then
        assertThat(결과.written()).containsExactlyInAnyOrderElementsOf(정상);
        assertThat(결과.failures()).extracting(failure -> failure.tuple()).containsExactly(나쁜_줄);
        assertThat(check("user:user0", "member", "group:DEV002")).isTrue();
    }

    @Test
    @DisplayName("실제 OpenFGA 의 400 을 거절로 알아본다")
    void 실제_400을_거절로_알아본다() {
        // when
        Throwable thrown = catchThrowable(() -> bootstrapper.client().write(new ClientWriteRequest().writes(List.of(
                new ClientTupleKey().user("user:bad").relation("direct_member")._object("nosuchtype:x")))).get());

        // then
        assertThat(OpenFgaErrors.거절인가(thrown)).isTrue();
    }
}
