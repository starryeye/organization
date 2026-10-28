package dev.starryeye.organization.core.usecase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotArchiveUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-15T03:00:00Z");
    private static final Duration 기한 = Duration.ofMinutes(30);

    private static final DirectoryUser 퇴사한_김 = new DirectoryUser("kim", null, "kim", "김철수", null, false);
    private static final DirectoryUser 활성_김 = new DirectoryUser("kim", null, "kim", "김철수", null, true);
    private static final RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV002");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private SnapshotArchiveUseCase useCase;
    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;
    /** 아카이빙이 알린 어긋남 수 — 알린 순서대로 쌓는다. */
    private List<어긋남> 알린_어긋남;
    /** 어긋남을 알린 직후(비교가 끝나고 스냅샷 저장·복구 전)에 한다 — 비교와 복구 사이에 일어나는 일을 흉내 낸다. */
    private Runnable 복구_전에;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(SnapshotArchiveUseCase.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        // OpenFGA 는 가짜 장부 하나다 — 아카이빙의 비교와 복구의 Check 가 같은 장부를 본다. failFor 는 그대로 따른다
        checker = new FakeTupleChecker() {
            @Override
            public Mono<Boolean> check(RelationTuple tuple) {
                return super.check(tuple).map(ignored -> writer.stored.contains(tuple));
            }
        };
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        알린_어긋남 = new ArrayList<>();
        복구_전에 = () -> {
        };
        IncrementalSyncUseCase incremental = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ofSeconds(1),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        useCase = new SnapshotArchiveUseCase(state, checker, snapshots, runs, lock,
                Clock.fixed(고정시각, ZoneOffset.UTC), (extra, missing) -> {
            알린_어긋남.add(new 어긋남(extra, missing));
            복구_전에.run();
        }, incremental::revokeLeftovers, 기한);
    }

    @AfterEach
    void 원복한다() {
        logger.detachAppender(로그);
        로그.stop();
    }

    @Test
    @DisplayName("OpenFGA 에 실제로 있는 튜플을 SCIM 소스 스냅샷으로 적재한다")
    void 실제_튜플을_스냅샷으로_적재한다() {
        // given — 상태와 OpenFGA 가 일치하는 평상시
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(RelationTuple.directMember("kim", "DEV002"));

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.SCIM);
        assertThat(snapshots.saved.get(0).id()).isEqualTo("20260815T030000000-SCIM");
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("상태가 요구하는 것이 아니라 실제 있는 것을 적재한다 — 둘이 어긋나 있어도")
    void 어긋나면_실제를_적재한다() {
        // given — 상태는 kim 의 소속을 말하지만 OpenFGA 에는 그 튜플이 없다.
        // 전에는 상태로부터 유도해 저장했으므로 이 스냅샷이 "있었다" 고 거짓말했다.
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        // writer.stored 가 비어 있다 = OpenFGA 에 아무것도 없다

        // when
        var run = useCase.execute().block();

        // then — 감사 기록은 관찰이어야 한다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("BatchCheck 가 실패하면 의도한 튜플로 폴백하지 않고 아카이빙을 실패로 끝낸다")
    void Check_실패는_폴백하지_않는다() {
        // given — 폴백하면 그 스냅샷이 관찰인지 유도인지 구분되지 않는다
        state.saveUser(new DirectoryUser("kim", null, "kim", "김철수", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        checker.failFor(tuple -> true);

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(snapshots.saved).isEmpty();
        assertThat(알린_어긋남).as("관찰하지 못했으면 수도 알리지 않는다 — 게이지는 마지막 값을 쥔다").isEmpty();
    }

    @Test
    @DisplayName("아카이빙은 ARCHIVE 트리거로 이력에 남는다")
    void ARCHIVE_트리거로_기록된다() {
        // given, when
        var run = useCase.execute().block();

        // then
        assertThat(run.trigger()).isEqualTo(SyncTrigger.ARCHIVE);
        assertThat(run.source()).isEqualTo(SyncSource.SCIM);
        assertThat(runs.finished).hasSize(1);
    }

    @Test
    @DisplayName("현재상태가 비어 있어도 빈 스냅샷을 남긴다")
    void 비어_있어도_스냅샷을_남긴다() {
        // given, when
        var run = useCase.execute().block();

        // then — 그날 조직이 비었다는 사실 자체가 기록이다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).tuples()).isEmpty();
    }

    @Test
    @DisplayName("재적재가 락을 쥐고 있으면 오늘 아카이빙을 건너뛰고 ABORTED 로 남긴다 — 잠깐 어긋난 장부를 '실제'로 찍지 않게")
    void 재적재_중이면_건너뛴다() {
        // given
        lock.acquireOnce(MutationLock.LockPurpose.REBUILD).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).isEqualTo("재적재 중이라 건너뜀");
        assertThat(snapshots.saved).isEmpty();
    }

    @Test
    @DisplayName("SCIM 쓰기가 잠깐 쥔 락은 건너뛸 이유가 아니다")
    void 쓰기_락은_건너뛰지_않는다() {
        // given
        lock.acquireOnce(MutationLock.LockPurpose.WRITE).block();

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("아카이빙은 보류 목록을 따른다 — 순증이 고른 쪽을 어긋남으로 세지 않는다")
    void 보류_목록을_따른다() {
        // given — A ⊃ B, B ⊃ A. 순증은 "B 는 A 의 하위"(A ⊃ B)를 보류하고 "A 는 B 의 하위"를 썼다
        state.saveGroup(new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A")))).block();
        state.cutEdges.add(new GroupEdge("A", "B"));
        writer.stored.add(RelationTuple.child("A", "B"));

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(로그.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(message -> message.contains("어긋남"));
    }

    @Test
    @DisplayName("OpenFGA 에 튜플이 있는 낡은 보류 줄은 어긋남으로 세지 않는다 — 튜플 그래프에 있는 연결로 본다")
    void 낡은_보류_줄은_어긋남이_아니다() {
        // given — 위와 같은 순환인데 보류 목록에 A ⊃ B 와 함께 B ⊃ A 도 남아 있다.
        // B ⊃ A 는 튜플이 이미 OpenFGA 에 있다(쓴 뒤 목록에서 빼기 전에 멈췄다)
        state.saveGroup(new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A")))).block();
        state.cutEdges.add(new GroupEdge("A", "B"));
        state.cutEdges.add(new GroupEdge("B", "A"));
        writer.stored.add(RelationTuple.child("A", "B"));

        // when
        var run = useCase.execute().block();

        // then — 낡은 줄도 목록 그대로 빼면 "A 는 B 의 하위" 튜플이 있어선 안 될 것으로 세어진다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(로그.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(message -> message.contains("어긋남"));
        assertThat(snapshots.saved.get(0).tuples()).containsExactly(RelationTuple.child("A", "B"));
    }

    @Test
    @DisplayName("어긋난 직원을 다시 맞춰 남은 줄을 지운다 — 스냅샷에는 지우기 전에 본 실제가 남는다(설계 2026-10-20 §6.4)")
    void 어긋난_직원을_다시_맞춰_남은_줄을_지운다() {
        // given — 비활성으로 저장된 kim 의 줄이 남았다. 동료 lee 는 상태와 장부가 맞는다
        state.saveUser(퇴사한_김).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();
        writer.stored.addAll(Set.of(김_개발, RelationTuple.directMember("lee", "DEV002")));

        // when
        var run = useCase.execute().block();

        // then — kim 의 줄만 지웠고(락 안에서 한 번), lee 는 건드리지 않았다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("어긋난 직원 1명 다시 맞춤(실패 0명)");
        assertThat(writer.stored).containsExactly(RelationTuple.directMember("lee", "DEV002"));
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(lock.acquired).hasValue(1);
        // 스냅샷은 복구 전에 본 실제다 — 지우기 전의 모습이 감사 기록에 남는다
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactlyInAnyOrder(김_개발, RelationTuple.directMember("lee", "DEV002"));
        assertThat(state.users.get("kim").active()).as("상태는 바꾸지 않는다").isFalse();
    }

    @Test
    @DisplayName("빠진 줄은 지표로만 내고 다시 쓰지 않는다 — 회수 쪽으로만 맞춘다")
    void 빠진_줄은_지표로만_내고_다시_쓰지_않는다() {
        // given — 상태는 활성 kim 이 DEV002 라 말하지만 장부에는 줄이 없다
        state.saveUser(활성_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();

        // when
        var run = useCase.execute().block();

        // then — 빠진 줄은 그대로 빠져 있고, 수만 알렸다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(알린_어긋남).containsExactly(new 어긋남(0, 1));
    }

    @Test
    @DisplayName("상태가 낡아도 지운 직원의 권한을 되살리지 않는다 — OpenFGA 에서는 지웠고 DynamoDB 커밋이 실패한 모양")
    void 상태가_낡아도_지운_직원의_권한을_되살리지_않는다() {
        // given — 직원 삭제가 OpenFGA 에서 끝났는데 레코드 삭제 커밋이 실패했다. 상태는 활성에 소속이 그대로이고 장부에는 줄이 없다
        state.saveUser(활성_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();

        // when
        var run = useCase.execute().block();

        // then — 낡은 상태를 믿고 다시 쓰면 지운 직원이 되살아난다. 쓰지 않는다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).doesNotContain(김_개발);
        assertThat(writer.written).isEmpty();
        assertThat(알린_어긋남).containsExactly(new 어긋남(0, 1));
    }

    @Test
    @DisplayName("남은 줄과 빠진 줄의 수를 관찰자에 알린다 — 어긋남이 없을 때도 0 으로 알려 게이지가 마지막 값을 쥔다")
    void 어긋남_수를_관찰자에_알린다() {
        // given — kim 은 비활성인데 줄이 남았고(extra 1), lee 는 활성인데 줄이 없다(missing 1)
        state.saveUser(퇴사한_김).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, true)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();
        writer.stored.add(김_개발);

        // when
        useCase.execute().block();

        // then
        assertThat(알린_어긋남).containsExactly(new 어긋남(1, 1));

        // when — 다음 날: 남은 줄은 지워졌고 빠진 줄은 그대로다. 그다음 날 lee 의 줄이 다른 길로 채워졌다
        useCase.execute().block();
        writer.stored.add(RelationTuple.directMember("lee", "DEV002"));
        useCase.execute().block();

        // then
        assertThat(알린_어긋남).containsExactly(new 어긋남(1, 1), new 어긋남(0, 1), new 어긋남(0, 0));
    }

    @Test
    @DisplayName("어긋남이 없으면 복구하지 않고 결론 메시지도 남기지 않는다")
    void 어긋남이_없으면_복구하지_않는다() {
        // given
        state.saveUser(활성_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(김_개발);

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.message()).isNull();
        assertThat(lock.acquired).hasValue(0);
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("조직 연결 줄의 어긋남은 수에만 들고 다시 맞추지 않는다 — 직원 회수가 아니다")
    void 조직_연결_줄의_어긋남은_다시_맞추지_않는다() {
        // given — A ⊃ B, B ⊃ A 순환. 보류 목록은 비었고 장부에는 두 연결이 다 있다. 목표는 한쪽만 남기므로 다른 쪽이 여분이다
        state.saveGroup(new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A")))).block();
        writer.stored.addAll(Set.of(RelationTuple.child("A", "B"), RelationTuple.child("B", "A")));

        // when
        var run = useCase.execute().block();

        // then — 수에는 들었고 복구는 하지 않았다
        assertThat(알린_어긋남).containsExactly(new 어긋남(1, 0));
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(writer.stored).hasSize(2);
        assertThat(lock.acquired).hasValue(0);
        assertThat(run.message()).isNull();
    }

    @Test
    @DisplayName("다시 맞추기 전에 지워진 직원은 건너뛰고 실패로 세지 않는다")
    void 다시_맞추기_전에_지워진_직원은_건너뛰고_실패로_세지_않는다() {
        // given — 비교에서 남은 줄이 잡혔는데, 복구 전에 그 직원이 지워졌다(지우기는 줄도 함께 지웠다)
        state.saveUser(퇴사한_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(김_개발);
        복구_전에 = () -> {
            state.users.remove("kim");
            writer.stored.remove(김_개발);
        };

        // when
        var run = useCase.execute().block();

        // then — 건너뛰었다: 쓰지도 지우지도 않았고 실패도 아니다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("어긋난 직원 0명 다시 맞춤(실패 0명)");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("비교 뒤 다시 활성이 된 직원도 지우기만 한다 — 락 안에서 새로 보인 빠진 줄을 쓰지 않는다")
    void 비교_뒤_다시_활성이_된_직원도_지우기만_한다() {
        // given — 비교 때 kim 은 비활성이고 DEV002 의 줄이 남았다. 복구 전에 활성으로 돌아와 OPS001 의 줄이 빠진 상태가 됐다
        state.saveUser(퇴사한_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("OPS001", null, "운영본부", Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(김_개발);
        복구_전에 = () -> state.saveUser(활성_김).block();

        // when
        var run = useCase.execute().block();

        // then — 이제 DEV002 의 줄은 목표 안이라 지울 것이 없고, 빠진 OPS001 의 줄은 쓰지 않는다
        assertThat(run.message()).isEqualTo("어긋난 직원 1명 다시 맞춤(실패 0명)");
        assertThat(writer.stored).containsExactly(김_개발);
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("다시 맞추기가 일부만 반영된 직원을 실패로 센다")
    void 다시_맞추기가_실패한_직원을_센다() {
        // given — 남은 줄을 지우지 못한다
        state.saveUser(퇴사한_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(김_개발);
        writer.failFor(김_개발::equals);

        // when
        var run = useCase.execute().block();

        // then — 아카이빙은 끝났고 실패는 메시지에 센다. 줄은 남아 다음 날 다시 잡힌다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("어긋난 직원 1명 다시 맞춤(실패 1명)");
        assertThat(writer.stored).containsExactly(김_개발);
    }

    @Test
    @DisplayName("다시 맞추기가 오류로 끝난 직원도 실패로 세고 나머지 직원은 이어서 맞춘다")
    void 다시_맞추기가_오류로_끝난_직원도_실패로_세고_나머지는_이어서_맞춘다() {
        // given — 비교는 끝났고, 복구에서 kim 의 Check 만 오류가 난다
        state.saveUser(퇴사한_김).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, false)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();
        writer.stored.addAll(Set.of(김_개발, RelationTuple.directMember("lee", "DEV002")));
        복구_전에 = () -> checker.failFor(김_개발::equals);

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("어긋난 직원 2명 다시 맞춤(실패 1명)");
        assertThat(writer.stored).containsExactly(김_개발);
    }

    @Test
    @DisplayName("관찰자는 스냅샷 저장보다 먼저 불린다 — 관찰 시각이 비교한 시각이다")
    void 관찰자는_스냅샷_저장보다_먼저_불린다() {
        // given
        AtomicInteger 알릴_때_저장된_수 = new AtomicInteger(-1);
        복구_전에 = () -> 알릴_때_저장된_수.set(snapshots.saved.size());

        // when
        useCase.execute().block();

        // then
        assertThat(알릴_때_저장된_수).hasValue(0);
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("스냅샷 저장이 실패해도 관찰자와 남은 회수 지우기는 돈다 — FAILED 에 복구 결과를 잇는다")
    void 스냅샷_저장이_실패해도_관찰자와_남은_회수_지우기는_돈다() {
        // given — kim 의 줄이 남았고 스냅샷 저장은 실패한다
        state.saveUser(퇴사한_김).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀", Set.of(MemberRef.user("kim")))).block();
        writer.stored.add(김_개발);
        snapshots.failSave(new IllegalStateException("디스크"));

        // when
        var run = useCase.execute().block();

        // then — 저장이 실패해도 수는 알렸고 남은 줄은 지웠다
        assertThat(알린_어긋남).containsExactly(new 어긋남(1, 0));
        assertThat(writer.stored).isEmpty();
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("스냅샷 저장 실패 — 디스크 / 어긋난 직원 1명 다시 맞춤(실패 0명)");
        assertThat(run.writtenCount()).as("저장하지 못했으니 쓴 수는 0").isZero();
        assertThat(run.snapshotId()).isNull();
        assertThat(run.deletedCount()).as("지운 줄은 저장 실패와 상관없이 센다").isEqualTo(1);
    }

    @Test
    @DisplayName("지운 줄과 실패한 줄을 센다 — 쓴 수는 스냅샷에 담은 실제 줄 수다")
    void 지운_줄과_실패한_줄을_센다() {
        // given — kim 의 남은 줄 둘은 지워지고, lee 의 남은 줄 하나는 지우기가 실패한다
        var 이_개발 = RelationTuple.directMember("lee", "DEV002");
        state.saveUser(퇴사한_김).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, false)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();
        state.saveGroup(new DirectoryGroup("OPS001", null, "운영본부", Set.of(MemberRef.user("kim")))).block();
        writer.stored.addAll(Set.of(김_개발, RelationTuple.directMember("kim", "OPS001"), 이_개발));
        writer.failFor(이_개발::equals);

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.writtenCount()).isEqualTo(3);
        assertThat(run.deletedCount()).isEqualTo(2);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(run.message()).isEqualTo("어긋난 직원 2명 다시 맞춤(실패 1명)");
        assertThat(writer.stored).containsExactly(이_개발);
    }

    @Test
    @DisplayName("다시 맞추기가 오류로 끝난 직원의 남은 줄은 실패로 센다 — 그 직원의 줄만")
    void 다시_맞추기가_오류로_끝난_직원의_남은_줄은_실패로_센다() {
        // given — 비교 뒤 kim 의 Check 가 오류로 끝난다. kim 의 남은 줄은 둘이고, lee 의 남은 줄 하나는 지워진다
        state.saveUser(퇴사한_김).block();
        state.saveUser(new DirectoryUser("lee", null, "lee", "이영희", null, false)).block();
        state.saveGroup(new DirectoryGroup("DEV002", null, "백엔드팀",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee")))).block();
        state.saveGroup(new DirectoryGroup("OPS001", null, "운영본부", Set.of(MemberRef.user("kim")))).block();
        writer.stored.addAll(Set.of(김_개발, RelationTuple.directMember("kim", "OPS001"),
                RelationTuple.directMember("lee", "DEV002")));
        복구_전에 = () -> checker.failFor(tuple -> tuple.directMemberUserId().filter("kim"::equals).isPresent());

        // when
        var run = useCase.execute().block();

        // then — 오류로 끝난 직원은 지운 줄이 없으니 남은 줄 둘이 모두 실패다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.failureCount()).isEqualTo(2);
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(run.message()).isEqualTo("어긋난 직원 2명 다시 맞춤(실패 1명)");
    }

    @Test
    @DisplayName("아카이빙을 시작할 때 시작한 지 기한 + 10분이 지난 RUNNING 기록을 닫는다 — 최근 것은 둔다")
    void 아카이빙을_시작할_때_오래된_기록을_닫는다() {
        // given — 기한 30분, 닫는 나이는 40분. 41분 전의 아카이빙과 5분 전의 아카이빙
        runs.seed(SyncRun.started("오래된-아카이빙", SyncSource.SCIM, SyncTrigger.ARCHIVE, 고정시각.minus(Duration.ofMinutes(41))));
        runs.seed(SyncRun.started("최근-아카이빙", SyncSource.SCIM, SyncTrigger.ARCHIVE, 고정시각.minus(Duration.ofMinutes(5))));

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        SyncRun 오래된것 = runs.findById("오래된-아카이빙").block();
        assertThat(오래된것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(오래된것.message()).isEqualTo(StaleRuns.기한_경과_사유);
        assertThat(runs.findById("최근-아카이빙").block().status()).isEqualTo(SyncStatus.RUNNING);
    }

    @Test
    @DisplayName("재적재 중이라 건너뛰는 날에도 오래된 RUNNING 기록을 닫는다")
    void 건너뛰는_날에도_오래된_기록을_닫는다() {
        // given
        lock.acquireOnce(MutationLock.LockPurpose.REBUILD).block();
        runs.seed(SyncRun.started("오래된-재적재", SyncSource.SCIM, SyncTrigger.REBUILD, 고정시각.minus(Duration.ofMinutes(41))));

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        SyncRun 오래된것 = runs.findById("오래된-재적재").block();
        assertThat(오래된것.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(오래된것.message()).isEqualTo(StaleRuns.기한_경과_사유);
    }

    @Test
    @DisplayName("오래된 기록을 찾지 못해도 아카이빙은 계속한다 — 기록 정리는 부가 일이다")
    void 오래된_기록을_찾지_못해도_아카이빙은_계속한다() {
        // given — findRunning 이 실패하는 저장소
        var 찾지_못하는_저장소 = new FakeSyncRunRepository(고정시각) {
            @Override
            public Flux<SyncRun> findRunning() {
                return Flux.error(new IllegalStateException("DynamoDB 가 답하지 않는다"));
            }
        };
        var 아카이빙 = new SnapshotArchiveUseCase(state, checker, snapshots, 찾지_못하는_저장소, lock,
                Clock.fixed(고정시각, ZoneOffset.UTC), (extra, missing) -> {
        }, 직원 -> Mono.empty(), 기한);

        // when
        var run = 아카이빙.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("기한(sync.job-timeout)을 넘기면 FAILED '기한 초과' 로 끝낸다 — 하루 1회 표지의 임대보다 먼저 끝난다")
    void 기한을_넘기면_FAILED_기한_초과로_끝낸다() {
        // given — 체커가 답하지 않는다
        var 답하지_않는_체커 = new FakeTupleChecker() {
            @Override
            public Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates) {
                return Mono.never();
            }
        };
        var 멈춘_아카이빙 = new SnapshotArchiveUseCase(state, 답하지_않는_체커, snapshots, runs, lock,
                Clock.fixed(고정시각, ZoneOffset.UTC), (extra, missing) -> {
        }, 직원 -> Mono.empty(), 기한);

        // when, then
        StepVerifier.withVirtualTime(멈춘_아카이빙::execute)
                .expectSubscription()
                .expectNoEvent(기한.minusSeconds(1))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(run -> {
                    assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
                    assertThat(run.message()).isEqualTo("기한 초과 — 30분");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(runs.finished).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(SyncStatus.FAILED));
        assertThat(snapshots.saved).isEmpty();
    }

    private record 어긋남(long extra, long missing) {
    }
}
