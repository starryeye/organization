package dev.starryeye.organization.core.usecase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.BaselineMovedException;
import dev.starryeye.organization.core.port.DirectoryAccountSource;
import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import dev.starryeye.organization.core.port.DirectoryReadException;
import dev.starryeye.organization.core.port.DirectoryUnavailableException;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RevocationMarksFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 한 사람 즉시 회수 유스케이스(설계 2026-11-28 V2·V4~V9·V11, §5.3). kim 은 be·pf·qa 소속이고 장부에는 be·pf 만 있다. */
class ImmediateRevocationUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-11-28T03:00:00Z");
    private static final String 막힘 = "즉시 회수 — 디렉터리에서 막힘";
    private static final String 없음 = "즉시 회수 — 디렉터리에 없음";
    private static final String 줄_없음 = "즉시 회수 — 장부에 거둘 줄이 없습니다";
    private static final String 정리_실패_꼬리 = " — 즉시 회수 표시를 정리하지 못했습니다. 다음 동기화가 그 줄을 다시 지웁니다";

    private String 직원 = "kim";
    private RelationTuple 직원_be;
    private RelationTuple 직원_pf;
    private Set<RelationTuple> 거둘_줄;

    private FakeStateRepository state;
    private FakeTupleChecker checker;
    private FakeTupleWriter writer;
    private FakeSnapshotRepository snapshots;
    private FakeSyncRunRepository runs;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private ImmediateRevocationUseCase useCase;
    private ListAppender<ILoggingEvent> 로그;
    private Logger 로거;

    private AccountState 디렉터리_응답 = AccountState.BLOCKED;
    private RuntimeException 디렉터리_오류;
    private final List<String> 읽은_직원 = new ArrayList<>();
    private final List<String> 넘긴_externalId = new ArrayList<>();

    @BeforeEach
    void setUp() {
        로거 = (Logger) LoggerFactory.getLogger(ImmediateRevocationUseCase.class);
        로그 = new ListAppender<>();
        로그.start();
        로거.addAppender(로그);
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        snapshots = new FakeSnapshotRepository();
        runs = new FakeSyncRunRepository(고정시각);
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        직원을_심는다("kim");
        useCase = 유스케이스();
    }

    @AfterEach
    void tearDown() {
        로거.detachAppender(로그);
        로그.stop();
    }

    /** 직원이 be·pf·qa 에 속하고 장부에는 be·pf 만 있게 심는다 — qa 줄은 장부에 없다. */
    private void 직원을_심는다(String id) {
        직원 = id;
        state.saveUser(new DirectoryUser(id, "uid=" + id + ",ou=people", id, id, null, false)).block();
        MemberRef 나 = MemberRef.user(id);
        for (String 조직 : List.of("be", "pf", "qa")) {
            state.saveGroup(new DirectoryGroup(조직, "cn=" + 조직, 조직, Set.of(나))).block();
        }
        직원_be = RelationTuple.directMember(id, "be");
        직원_pf = RelationTuple.directMember(id, "pf");
        checker = new FakeTupleChecker(new LinkedHashSet<>(Set.of(직원_be, 직원_pf)));
        거둘_줄 = Set.of(직원_be, 직원_pf);
    }

    private ImmediateRevocationUseCase 유스케이스() {
        DirectoryAccountSource accounts = (employeeId, externalId) -> Mono.defer(() -> {
            읽은_직원.add(employeeId);
            넘긴_externalId.add(externalId);
            return 디렉터리_오류 != null ? Mono.error(디렉터리_오류) : Mono.just(디렉터리_응답);
        });
        return new ImmediateRevocationUseCase(state, accounts, checker, writer, snapshots,
                new SyncJobs(runs, lock, Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1),
                        new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC))));
    }

    private SyncRun 거둔다() {
        SyncRun started = useCase.start(직원, run -> {
        }).block();
        return runs.awaitFinished(started.runId());
    }

    /** 기준선(스냅샷)이 있는 상태로 만든다 — B 에 거둘 줄과 다른 줄이 든다. */
    private void 기준선을_심는다(String snapshotId) {
        snapshots.save(new TupleSnapshot(snapshotId, 고정시각, SyncSource.LDAP,
                Set.of(직원_be, 직원_pf, RelationTuple.directMember("lee", "be")))).block();
    }

    private List<String> 경고들() {
        return 로그.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("현재상태에 없는 직원은 UnknownEmployeeException 이고 디렉터리를 읽지 않는다")
    // 깨뜨릴 변경: 404 를 디렉터리 읽기 뒤로 미루거나 없는 직원을 그냥 읽는다
    void 현재상태에_없는_직원은_UnknownEmployeeException_이고_디렉터리를_읽지_않는다() {
        // given
        String 없는_직원 = "ghost";

        // when & then
        assertThatThrownBy(() -> useCase.start(없는_직원, run -> {
        }).block()).isInstanceOf(UnknownEmployeeException.class)
                .hasMessage("현재상태에 그 직원이 없습니다 — 직원 id 를 확인하세요");
        assertThat(읽은_직원).isEmpty();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
        assertThat(lock.acquired.get()).isZero();
    }

    @Test
    @DisplayName("디렉터리에서 활성이면 StillActiveInDirectoryException 이고 실행 기록이 없다")
    // 깨뜨릴 변경: 활성인데도 작업을 띄우거나, 409 를 락·실행 기록을 연 뒤에 낸다
    void 디렉터리에서_활성이면_StillActiveInDirectoryException_이고_실행_기록이_없다() {
        // given
        디렉터리_응답 = AccountState.ACTIVE;

        // when & then
        assertThatThrownBy(() -> useCase.start(직원, run -> {
        }).block()).isInstanceOf(StillActiveInDirectoryException.class)
                .hasMessage("디렉터리에서 활성인 직원은 거두지 않습니다 — 디렉터리에서 먼저 막으세요");
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
        assertThat(lock.acquired.get()).isZero();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("디렉터리 읽기 실패는 그대로 올리고 실행 기록이 없다")
    // 깨뜨릴 변경: 읽기 실패를 삼키거나 다른 예외로 감싸거나, 실패해도 작업을 띄운다
    void 디렉터리_읽기_실패는_그대로_올리고_실행_기록이_없다() {
        // given
        DirectoryUnavailableException 일시 = new DirectoryUnavailableException("일시 장애", new RuntimeException("연결"));
        DirectoryReadException 그밖 = new DirectoryReadException("읽기 실패", new RuntimeException("검색"));

        for (RuntimeException 오류 : List.of(일시, 그밖)) {
            디렉터리_오류 = 오류;

            // when & then
            assertThatThrownBy(() -> useCase.start(직원, run -> {
            }).block()).isSameAs(오류);
        }
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
        assertThat(lock.acquired.get()).isZero();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("막힌 직원의 장부 줄만 지운다")
    // 깨뜨릴 변경: 장부에 없는 소속(qa)까지 지우거나, 후보를 Check 로 거르지 않는다
    void 막힌_직원의_장부_줄만_지운다() {
        // given
        디렉터리_응답 = AccountState.BLOCKED;

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.REVOCATION);
        assertThat(run.source()).isEqualTo(SyncSource.LDAP);
        assertThat(run.deletedCount()).isEqualTo(2);
        assertThat(run.writtenCount()).isZero();
        assertThat(run.snapshotId()).isNull();
        assertThat(run.message()).isEqualTo(막힘);
        assertThat(writer.deleted).containsExactlyInAnyOrder(직원_be, 직원_pf);
        assertThat(writer.appliedDeltas).singleElement().satisfies(delta -> {
            assertThat(delta.toWrite()).isEmpty();
            assertThat(delta.toDelete()).containsExactlyInAnyOrder(직원_be, 직원_pf);
        });
        assertThat(넘긴_externalId).containsExactly("uid=kim,ou=people");
        assertThat(lock.시도한_용도).containsExactly(MutationLock.LockPurpose.REVOCATION);
    }

    @Test
    @DisplayName("없는 직원은 앞머리가 디렉터리에 없음이다")
    // 깨뜨릴 변경: ABSENT 도 막힘 앞머리를 쓰거나 ABSENT 를 지우지 않는다
    void 없는_직원은_앞머리가_디렉터리에_없음이다() {
        // given
        디렉터리_응답 = AccountState.ABSENT;

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo(없음);
        assertThat(run.deletedCount()).isEqualTo(2);
        assertThat(writer.deleted).containsExactlyInAnyOrder(직원_be, 직원_pf);
    }

    @Test
    @DisplayName("장부에 거둘 줄이 없으면 쓰지도 표시하지도 않는다")
    // 깨뜨릴 변경: 거둘 줄이 없는데도 빈 델타를 쓰거나 표시를 남긴다
    void 장부에_거둘_줄이_없으면_쓰지도_표시하지도_않는다() {
        // given
        checker.allowed.clear();
        기준선을_심는다("snap-1");

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo(줄_없음);
        assertThat(run.deletedCount()).isZero();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("이미 거둔 사람을 다시 부르면 거둘 줄이 없고 앞 표시는 그대로다")
    // 깨뜨릴 변경: 두 번째 호출이 표시를 지우거나 덮어쓴다
    void 이미_거둔_사람을_다시_부르면_거둘_줄이_없고_앞_표시는_그대로다() {
        // given
        기준선을_심는다("snap-1");
        거둔다();
        checker.allowed.removeAll(Set.of(직원_be, 직원_pf));
        int 쓴_횟수 = writer.appliedDeltas.size();

        // when
        SyncRun 다시 = 거둔다();

        // then
        assertThat(다시.message()).isEqualTo(줄_없음);
        assertThat(writer.appliedDeltas).hasSize(쓴_횟수);
        assertThat(snapshots.marks().gone()).containsExactlyInAnyOrder(직원_be, 직원_pf);
        assertThat(snapshots.marks().unknown()).isEmpty();
    }

    @Test
    @DisplayName("소속이 없는 직원은 Check 없이 거둘 줄 없음이다")
    // 깨뜨릴 변경: 후보가 비어도 Check 를 부르거나 빈 후보로 쓰기를 한다
    void 소속이_없는_직원은_Check_없이_거둘_줄_없음이다() {
        // given
        state.saveUser(new DirectoryUser("solo", "uid=solo", "solo", "solo", null, false)).block();

        // when
        SyncRun started = useCase.start("solo", run -> {
        }).block();
        SyncRun run = runs.awaitFinished(started.runId());

        // then
        assertThat(run.message()).isEqualTo(줄_없음);
        assertThat(checker.checked).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("기준선이 있으면 지우기 전에 표시하고 지운 뒤 정리한다")
    // 깨뜨릴 변경: 지운 뒤에 표시하거나 정리하지 않아 unknown 이 남는다
    void 기준선이_있으면_지우기_전에_표시하고_지운_뒤_정리한다() {
        // given
        기준선을_심는다("snap-1");
        AtomicReference<Set<RelationTuple>> 지우기_전_unknown = new AtomicReference<>();
        writer.onApply(() -> 지우기_전_unknown.set(snapshots.marks().unknown()));

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(지우기_전_unknown.get()).isEqualTo(거둘_줄);
        assertThat(snapshots.marks().gone()).isEqualTo(거둘_줄);
        assertThat(snapshots.marks().unknown()).isEmpty();
    }

    @Test
    @DisplayName("결과를 모르는 줄은 unknown 에 남고 확정 실패는 빠진다")
    // 깨뜨릴 변경: 모르는 줄까지 gone 으로 옮기거나, 확정 실패를 unknown 에 남긴다
    void 결과를_모르는_줄은_unknown_에_남고_확정_실패는_빠진다() {
        // given
        기준선을_심는다("snap-1");
        writer.lostResponseFor(tuple -> tuple.equals(직원_pf));

        // when
        SyncRun 모름 = 거둔다();

        // then
        assertThat(모름.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(모름.message()).isEqualTo(막힘 + " / 1건의 튜플 적용에 실패했습니다. 다음 동기화가 다시 시도합니다");
        assertThat(snapshots.marks().gone()).containsExactly(직원_be);
        assertThat(snapshots.marks().unknown()).containsExactly(직원_pf);

        // given
        writer.lostResponseFor(tuple -> false);
        writer.failFor(tuple -> tuple.equals(직원_pf));
        기준선을_심는다("snap-2");

        // when
        SyncRun 확정 = 거둔다();

        // then
        assertThat(확정.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(snapshots.marks().gone()).containsExactly(직원_be);
        assertThat(snapshots.marks().unknown()).isEmpty();
    }

    @Test
    @DisplayName("기록 중이거나 기준선이 없으면 표시하지 않고 지운다")
    // 깨뜨릴 변경: PRESENT 가 아닌데도 표시를 시도해 거두지 못하거나 표시를 남긴다
    void 기록_중이거나_기준선이_없으면_표시하지_않고_지운다() {
        // given — 기준선 없음

        // when
        SyncRun 기준선_없음 = 거둔다();

        // then
        assertThat(기준선_없음.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).containsExactlyInAnyOrder(직원_be, 직원_pf);
        assertThat(snapshots.marks().isEmpty()).isTrue();

        // given — 기록 중
        기준선을_심는다("snap-1");
        snapshots.writing.set(true);

        // when
        SyncRun 기록_중 = 거둔다();

        // then
        assertThat(기록_중.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(기록_중.deletedCount()).isEqualTo(2);
        assertThat(snapshots.marks().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("표시를 남기지 못하면 지우지 않는다")
    // 깨뜨릴 변경: 표시 실패를 무시하고 지우거나, 한도 문구를 감싸 버린다
    void 표시를_남기지_못하면_지우지_않는다() {
        // given
        기준선을_심는다("snap-1");
        snapshots.failMarkRevoking(new RevocationMarksFullException());

        // when
        SyncRun 한도 = 거둔다();

        // then
        assertThat(한도.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(한도.message()).isEqualTo("즉시 회수 표시가 한도(256KB)를 넘습니다 — 다음 동기화가 기준선을 새로 남긴 뒤 다시 거세요. "
                + "여럿이면 디렉터리에서 막은 뒤 POST /admin/sync/full 을 쓰세요");
        assertThat(writer.appliedDeltas).isEmpty();

        // given
        snapshots.failMarkRevoking(new BaselineMovedException("x"));

        // when
        SyncRun 움직임 = 거둔다();

        // then
        assertThat(움직임.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(움직임.message()).startsWith("즉시 회수 표시를 남기지 못해 거두지 않았습니다");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("정리에 실패하면 결론은 그대로이고 메시지 끝에 알린다")
    // 깨뜨릴 변경: 정리 실패가 회차를 FAILED 로 만들거나 꼬리를 빠뜨리거나 WARN 에 줄·직원을 싣는다
    void 정리에_실패하면_결론은_그대로이고_메시지_끝에_알린다() {
        // given
        기준선을_심는다("snap-1");
        snapshots.failSettleRevocation(new IllegalStateException("저장소 오류"));

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.deletedCount()).isEqualTo(2);
        assertThat(run.message()).isEqualTo(막힘 + 정리_실패_꼬리);
        assertThat(경고들()).singleElement().satisfies(경고 ->
                assertThat(경고).contains("2").doesNotContain(직원).doesNotContain("direct_member"));
    }

    @Test
    @DisplayName("차단기로 멈추면 그때까지로 정리하고 FAILED")
    // 깨뜨릴 변경: 차단기 예외를 정리 없이 던지거나 멈추기 전에 나간 줄을 정리에서 빼 버린다
    void 차단기로_멈추면_그때까지로_정리하고_FAILED() {
        // given
        기준선을_심는다("snap-1");
        writer.failFor(tuple -> tuple.equals(직원_pf));
        writer.abortWhen(delta -> true);

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo(막힘 + " / 테스트용 차단 — 연속 실패로 멈췄다");
        assertThat(run.deletedCount()).isEqualTo(1);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(snapshots.marks().gone()).containsExactly(직원_be);
        assertThat(snapshots.marks().unknown()).isEmpty();
    }

    @Test
    @DisplayName("차단기가 아닌 쓰기 오류는 정리하지 않고 FAILED 이며 표시는 모름으로 남는다")
    // 깨뜨릴 변경: 모르는 오류에도 정리해 unknown 을 비운다
    void 차단기가_아닌_쓰기_오류는_정리하지_않고_FAILED() {
        // given
        기준선을_심는다("snap-1");
        writer.onApply(() -> {
            throw new IllegalStateException("쓰기 오류");
        });

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).isEqualTo("쓰기 오류");
        assertThat(snapshots.marks().unknown()).isEqualTo(거둘_줄);
        assertThat(snapshots.marks().gone()).isEmpty();
    }

    @Test
    @DisplayName("락을 못 잡으면 LockUnavailableException")
    // 깨뜨릴 변경: 락 경합을 삼키고 실행 기록을 열거나 쓰기를 한다
    void 락을_못_잡으면_LockUnavailableException() {
        // given
        lock.acquireOnce(MutationLock.LockPurpose.SYNC).block();

        // when & then
        assertThatThrownBy(() -> useCase.start(직원, run -> {
        }).block()).isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(runs.findRecent(10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("이력에 REVOCATION 종류로 지운 줄이 남는다")
    // 깨뜨릴 변경: 이력 종류를 SYNC 로 두거나 지운 줄을 이력에 더하지 않는다
    void 이력에_REVOCATION_종류로_지운_줄이_남는다() {
        // given
        디렉터리_응답 = AccountState.BLOCKED;

        // when
        SyncRun run = 거둔다();

        // then
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.cause()).isEqualTo(
                    AuditCause.run(SyncSource.LDAP, AuditKind.REVOCATION, run.runId(), event.cause().actor()));
            assertThat(event.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.subject()).isEqualTo(MemberRef.user(직원));
                assertThat(entry.revoked()).containsExactlyInAnyOrder(직원_be, 직원_pf);
                assertThat(entry.granted()).isEmpty();
            });
        });
    }

    @Test
    @DisplayName("실행 기록 메시지에 직원 id 가 없다")
    // 깨뜨릴 변경: 메시지나 오류 문구에 직원 id 를 싣는다
    void 실행_기록_메시지에_직원_id_가_없다() {
        // given
        String 남다른_id = "emp-7f3a";
        직원을_심는다(남다른_id);
        useCase = 유스케이스();
        기준선을_심는다("snap-1");
        List<String> 메시지들 = new ArrayList<>();

        // when — 성공, 부분, 한도 실패, 표시 실패, 거둘 줄 없음
        메시지들.add(거둔다().message());

        checker.allowed.addAll(Set.of(직원_be, 직원_pf));
        기준선을_심는다("snap-2");
        writer.failFor(tuple -> tuple.equals(직원_pf));
        메시지들.add(거둔다().message());
        writer.failFor(tuple -> false);

        checker.allowed.addAll(Set.of(직원_be, 직원_pf));
        snapshots.failMarkRevoking(new RevocationMarksFullException());
        메시지들.add(거둔다().message());
        snapshots.failMarkRevoking(new BaselineMovedException("기준선이 움직였습니다"));
        메시지들.add(거둔다().message());
        snapshots.failMarkRevoking(null);

        checker.allowed.clear();
        메시지들.add(거둔다().message());

        // then
        assertThat(메시지들).hasSize(5).allSatisfy(message -> assertThat(message).doesNotContain(남다른_id));
        assertThat(경고들()).allSatisfy(경고 -> assertThat(경고).doesNotContain(남다른_id));
    }
}
