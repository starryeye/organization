package dev.starryeye.organization.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOutcomeTest {

    private static final String 문구 = "재활성 가드 발동";
    private static final ReactivationHold 보류 = ReactivationHold.of(Set.of("u2", "u1"));

    private static TupleWriteResult 결과() {
        RelationTuple a = RelationTuple.directMember("kim", "DEV001");
        RelationTuple b = RelationTuple.directMember("lee", "DEV001");
        return new TupleWriteResult(Set.of(a, b), Set.of(), List.of());
    }

    private static TupleWriteResult 실패있는_결과() {
        RelationTuple a = RelationTuple.directMember("kim", "DEV001");
        RelationTuple b = RelationTuple.directMember("lee", "DEV001");
        return new TupleWriteResult(Set.of(a), Set.of(), List.of(new TupleFailure(b, "거절")));
    }

    private static GuardHold 멈춤_내역() {
        return new GuardHold(1, 0, 0, new GuardHold.Sample(0, List.of()), new GuardHold.Sample(0, List.of()),
                new GuardHold.Sample(0, List.of()), new GuardHold.Sample(0, List.of()),
                new GuardHold.Sample(0, List.of()));
    }

    @Test
    @DisplayName("보류는 성공과 부분을 ABORTED 로 바꾼다 — 변경 없음 메시지는 뒤에 남는다(설계 2026-11-24 R6)")
    void 보류는_성공과_부분을_ABORTED_로_바꾼다() {
        // when, then
        assertThat(SyncOutcome.succeeded(결과(), "s1").withReactivationHold(보류, 문구).status())
                .isEqualTo(SyncStatus.ABORTED);
        assertThat(SyncOutcome.partial(실패있는_결과(), "s1").withReactivationHold(보류, 문구).status())
                .isEqualTo(SyncStatus.ABORTED);
        var 변경없음 = SyncOutcome.noChange().withReactivationHold(보류, 문구);
        assertThat(변경없음.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(변경없음.message()).isEqualTo(문구 + " / 변경 없음");
        assertThat(변경없음.reactivationHold()).isEqualTo(보류);
    }

    @Test
    @DisplayName("보류는 ABORTED 와 FAILED 를 그대로 둔다 — 칸과 앞 문구만 붙는다")
    void 보류는_ABORTED_와_FAILED_를_그대로_둔다() {
        // when
        var 중단 = SyncOutcome.aborted("m").withReactivationHold(보류, 문구);
        var 멈춤 = SyncOutcome.stopped(실패있는_결과(), "s1", "m").withReactivationHold(보류, 문구);
        var 실패 = SyncOutcome.failed("m").withReactivationHold(보류, 문구);

        // then
        assertThat(중단.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(멈춤.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(실패.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(List.of(중단, 멈춤, 실패)).allSatisfy(o -> {
            assertThat(o.message()).isEqualTo(문구 + " / m");
            assertThat(o.reactivationHold()).isEqualTo(보류);
        });
    }

    @Test
    @DisplayName("보류는 건수와 스냅샷과 멈춤 내역을 보존한다")
    void 보류는_건수와_스냅샷과_멈춤_내역을_보존한다() {
        // given
        var 원래 = SyncOutcome.partial(실패있는_결과(), "s1").withGuardHold(멈춤_내역());

        // when
        var 보류한 = 원래.withReactivationHold(보류, 문구);

        // then
        assertThat(보류한.writtenCount()).isEqualTo(원래.writtenCount());
        assertThat(보류한.deletedCount()).isEqualTo(원래.deletedCount());
        assertThat(보류한.failureCount()).isEqualTo(원래.failureCount());
        assertThat(보류한.snapshotId()).isEqualTo("s1");
        assertThat(보류한.guardHold()).isEqualTo(멈춤_내역());
    }

    @Test
    @DisplayName("withNote 와 withSuffix 와 withGuardHold 는 보류 칸을 보존한다")
    void withNote_와_withSuffix_와_withGuardHold_는_보류_칸을_보존한다() {
        // given
        var 보류한 = SyncOutcome.noChange().withReactivationHold(보류, 문구);

        // when, then
        assertThat(보류한.withNote("앞").reactivationHold()).isEqualTo(보류);
        assertThat(보류한.withSuffix("뒤").reactivationHold()).isEqualTo(보류);
        assertThat(보류한.withGuardHold(멈춤_내역()).reactivationHold()).isEqualTo(보류);
        assertThat(SyncOutcome.noChange().reactivationHold()).isNull();
    }

    @Test
    @DisplayName("결론의 보류 칸이 실행 기록으로 옮겨진다")
    void 결론의_보류_칸이_실행_기록으로_옮겨진다() {
        // given
        var 지금 = Instant.parse("2026-11-24T03:00:00Z");
        var 결론 = SyncOutcome.noChange().withReactivationHold(보류, 문구);

        // when
        var 기록 = SyncRun.started("run-1", SyncSource.LDAP, SyncTrigger.MANUAL, 지금, "client:ops")
                .finished(결론, 지금.plusSeconds(1));

        // then
        assertThat(기록.reactivationHold()).isEqualTo(보류);
        assertThat(기록.status()).isEqualTo(SyncStatus.ABORTED);
    }
}
