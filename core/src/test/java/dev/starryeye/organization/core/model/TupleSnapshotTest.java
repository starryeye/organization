package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TupleSnapshotTest {

    private static final Instant 시각 = Instant.parse("2026-10-20T03:00:00Z");
    private static final RelationTuple 가 = RelationTuple.directMember("kim", "DEV001");
    private static final RelationTuple 나 = RelationTuple.directMember("lee", "DEV001");
    private static final RelationTuple 다 = RelationTuple.directMember("park", "DEV001");

    @Test
    @DisplayName("네 인자 생성자는 불확실 줄을 빈 집합으로 둔다")
    void 네_인자_생성자는_불확실_줄이_비어_있다() {
        // given, when
        var snapshot = new TupleSnapshot("S1", 시각, SyncSource.LDAP, Set.of(가, 나));

        // then
        assertThat(snapshot.tuples()).containsExactlyInAnyOrder(가, 나);
        assertThat(snapshot.uncertain()).isEmpty();
    }

    @Test
    @DisplayName("다섯 인자 생성자는 확실한 줄과 불확실한 줄을 따로 갖는다")
    void 다섯_인자_생성자는_두_집합을_따로_갖는다() {
        // given, when
        var snapshot = new TupleSnapshot("S1", 시각, SyncSource.LDAP, Set.of(가, 나), Set.of(다));

        // then
        assertThat(snapshot.tuples()).containsExactlyInAnyOrder(가, 나);
        assertThat(snapshot.uncertain()).containsExactly(다);
    }

    @Test
    @DisplayName("집합이 null 이면 빈 집합이고, 넘긴 집합을 나중에 바꿔도 스냅샷은 그대로다")
    void null_은_빈_집합이고_복사해_가진다() {
        // given
        Set<RelationTuple> 불확실 = new HashSet<>(Set.of(다));

        // when
        var 빈 = new TupleSnapshot("S0", 시각, SyncSource.LDAP, null, null);
        var snapshot = new TupleSnapshot("S1", 시각, SyncSource.LDAP, Set.of(가), 불확실);
        불확실.clear();

        // then
        assertThat(빈.tuples()).isEmpty();
        assertThat(빈.uncertain()).isEmpty();
        assertThat(snapshot.uncertain()).containsExactly(다);
    }

    @Test
    @DisplayName("메타의 튜플 수는 확실한 줄만 센다 — 불확실한 줄은 세지 않는다")
    void 메타의_튜플_수는_확실한_줄만_센다() {
        // given
        var snapshot = new TupleSnapshot("S1", 시각, SyncSource.LDAP, Set.of(가, 나), Set.of(다));

        // when
        SnapshotMeta meta = snapshot.meta();

        // then
        assertThat(meta.id()).isEqualTo("S1");
        assertThat(meta.tupleCount()).isEqualTo(2);
    }
}
