package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.guard.ReactivationGuard;
import dev.starryeye.organization.core.guard.ReactivationGuardPolicy;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.RevocationMarks;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 다음 회차가 즉시 회수 표시를 반영한 기준선(B′·U′)으로 비교한다(설계 2026-11-28 V10·V15, §5.5·§6). */
class FullSyncRevocationMarksTest {

    private static final Instant 고정시각 = Instant.parse("2026-11-28T03:00:00Z");

    private static final RelationTuple 김_be = RelationTuple.directMember("kim", "be");
    private static final RelationTuple 김_pf = RelationTuple.directMember("kim", "pf");
    private static final RelationTuple 박_be = RelationTuple.directMember("park", "be");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FullSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = 유스케이스(DeletionGuardPolicy.defaults());
    }

    private FullSyncUseCase 유스케이스(DeletionGuardPolicy 정책) {
        return new FullSyncUseCase(source, snapshots, state, writer, new FakeTupleScanner(writer),
                new DeletionGuard(정책), new ReactivationGuard(ReactivationGuardPolicy.defaults()),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    /** 조직 코드 → 소속 직원 id. 활성·비활성 직원이 모두 소속에 든다 — 비활성의 줄은 매핑이 뺀다. */
    private static DirectorySnapshot 조직도(Map<String, Set<String>> 조직별_소속, Set<String> 비활성) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        조직별_소속.forEach((code, ids) -> {
            ids.forEach(id -> users.putIfAbsent(id,
                    new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", !비활성.contains(id))));
            Set<MemberRef> members = ids.stream().map(MemberRef::user).collect(Collectors.toSet());
            groups.put(code, new DirectoryGroup(code, "cn=" + code, code, members));
        });
        return new DirectorySnapshot(users, groups);
    }

    /** 직전 스냅샷(B)을 저장하고 표시를 심는다 — gone 은 확실히 지운 줄, unknown 은 결과를 모르는 줄. */
    private void 기준선과_표시를_심는다(Set<RelationTuple> b, Set<RelationTuple> gone, Set<RelationTuple> unknown) {
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, b)).block();
        Set<RelationTuple> 모두 = new LinkedHashSet<>(gone);
        모두.addAll(unknown);
        snapshots.markRevoking("이전", 모두).block();
        snapshots.settleRevocation("이전", gone, Set.of()).block();
    }

    private SyncRun 동기화한다() {
        SyncRun started = useCase.start(SyncTrigger.SCHEDULED, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    private TupleSnapshot 마지막_스냅샷() {
        return snapshots.saved.get(snapshots.saved.size() - 1);
    }

    @Test
    @DisplayName("회수 뒤 디렉터리에서 다시 풀린 직원의 줄은 다음 회차가 다시 쓴다 — 기준선이 회수한 줄을 있다고 믿지 않는다")
    // 깨뜨릴 변경: 기준선을 B − gone − unknown 대신 B 그대로 쓰면 D − B 가 비어 kim 의 줄이 영영 돌아오지 않는다
    void 회수_뒤_다시_풀리면_다음_회차가_줄을_다시_쓴다() {
        // given — B = {kim-be, kim-pf, park-be}, 회수가 kim-be 는 확실히, kim-pf 는 결과를 모른 채 남겼다
        기준선과_표시를_심는다(Set.of(김_be, 김_pf, 박_be), Set.of(김_be), Set.of(김_pf));
        source.willReturn(조직도(Map.of("be", Set.of("kim", "park"), "pf", Set.of("kim")), Set.of()));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.written).contains(김_be, 김_pf);
        assertThat(마지막_스냅샷().tuples()).contains(김_be, 김_pf);
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("디렉터리에서 여전히 막힌 직원은 결과를 몰랐던 줄만 다시 지우고, 확실히 지운 줄은 다시 지우지 않는다")
    // 깨뜨릴 변경: U′ 에 unknown 을 더하지 않으면 모르는 줄을 지우지 않고, gone 을 B 에서 빼지 않으면 지운 줄을 또 지운다
    void 결과를_몰랐던_줄만_다시_지우고_지운_줄은_다시_지우지_않는다() {
        // given
        기준선과_표시를_심는다(Set.of(김_be, 김_pf, 박_be), Set.of(김_be), Set.of(김_pf));
        source.willReturn(조직도(Map.of("be", Set.of("kim", "park"), "pf", Set.of("kim")), Set.of("kim")));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toDelete()).containsExactly(김_pf);
        assertThat(writer.appliedDeltas.get(0).toWrite()).isEmpty();
    }

    @Test
    @DisplayName("삭제 가드의 기준 스냅샷 수는 |B′| + |U′| 이다")
    // 깨뜨릴 변경: 가드의 기준 수를 B′ 가 아닌 원래 B 의 크기(10)로 두면 메시지가 기준 스냅샷 10건이 된다
    void 가드의_기준_수는_회수를_뺀_B와_U의_합이다() {
        // given — B 는 10줄이고 그 가운데 5줄이 이미 회수됐다. 이번 회차는 남은 5줄 가운데 둘(디렉터리에서 사라짐)을 지우려 한다
        기준선과_표시를_심는다(줄들(0, 10), 줄들(0, 5), Set.of());
        source.willReturn(조직도(Map.of("be", 직원들(5, 8)), Set.of()));
        useCase = 유스케이스(new DeletionGuardPolicy(true, 0.3, 1, 0));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("기준 스냅샷 5건");
    }

    @Test
    @DisplayName("변경이 없어도 표시가 있었으면 표시를 반영한 스냅샷을 새로 남겨 표시를 지운다")
    // 깨뜨릴 변경: 변경 없음 갈래가 저장을 건너뛰면 표시가 남아 다음 회차마다 같은 계산을 되풀이하고 표시 칸이 한도를 향해 쌓인다
    void 변경_없음이어도_표시가_있으면_스냅샷을_새로_남긴다() {
        // given — kim-be 는 이미 회수됐고 디렉터리에서도 kim 이 막혀 있어 비교하면 델타가 없다
        기준선과_표시를_심는다(Set.of(김_be, 박_be), Set.of(김_be), Set.of());
        source.willReturn(조직도(Map.of("be", Set.of("kim", "park")), Set.of("kim")));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("변경 없음 — 즉시 회수를 기준선에 반영해 스냅샷을 새로 남김");
        assertThat(run.snapshotId()).isNotNull();
        assertThat(마지막_스냅샷().tuples()).containsExactly(박_be);
        assertThat(마지막_스냅샷().uncertain()).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("표시가 없으면 변경 없음은 스냅샷을 남기지 않는다")
    // 깨뜨릴 변경: 표시 유무를 보지 않고 변경 없음마다 저장하면 매 시간 스냅샷이 쌓인다
    void 표시가_없으면_변경_없음은_스냅샷을_남기지_않는다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, Set.of(김_be, 박_be))).block();
        source.willReturn(조직도(Map.of("be", Set.of("kim", "park")), Set.of()));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.message()).isEqualTo("변경 없음");
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("가드가 통째로 멈춘 회차는 스냅샷을 남기지 않아 표시가 그대로 남는다")
    // 깨뜨릴 변경: 멈춘 회차가 표시를 지우면 다음 회차의 기준선이 회수를 잊는다
    void 전부_보류면_표시가_남는다() {
        // given
        기준선과_표시를_심는다(줄들(0, 10), 줄들(0, 5), Set.of());
        source.willReturn(조직도(Map.of("be", 직원들(5, 8)), Set.of()));
        useCase = 유스케이스(new DeletionGuardPolicy(true, 0.3, 1, 0));

        // when
        var run = 동기화한다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.marks()).isEqualTo(new RevocationMarks(줄들(0, 5), Set.of()));
    }

    @Test
    @DisplayName("기록 중 회차는 표시를 읽지 않고 장부를 훑어 맞추며, 스냅샷 저장이 표시를 지운다")
    // 깨뜨릴 변경: 기록 중 회차가 표시를 반영하면 훑은 장부와 다른 줄을 지우거나 남긴다
    void 기록_중_회차는_표시를_읽지_않고_저장이_지운다() {
        // given — 표시를 심은 뒤 지난 회차가 기록 전에 멈췄다. 장부에는 kim-be 와 park-be 가 있다
        기준선과_표시를_심는다(Set.of(김_be, 박_be), Set.of(김_be), Set.of());
        writer.stored.addAll(Set.of(김_be, 박_be));
        snapshots.writing.set(true);
        source.willReturn(조직도(Map.of("be", Set.of("kim", "park")), Set.of()));

        // when
        var run = 동기화한다();

        // then — 훑은 장부가 정한다: kim 은 디렉터리에서 활성이라 장부에 그대로 남는다
        assertThat(run.message()).contains("기준선 의심");
        assertThat(writer.stored).containsExactlyInAnyOrder(김_be, 박_be);
        assertThat(writer.deleted).isEmpty();
        assertThat(snapshots.marks().isEmpty()).isTrue();
        assertThat(snapshots.writing).isFalse();
    }

    private static Set<String> 직원들(int from, int to) {
        return IntStream.range(from, to).mapToObj(i -> "u" + i).collect(Collectors.toSet());
    }

    private static Set<RelationTuple> 줄들(int from, int to) {
        return 직원들(from, to).stream().map(id -> RelationTuple.directMember(id, "be")).collect(Collectors.toSet());
    }
}
