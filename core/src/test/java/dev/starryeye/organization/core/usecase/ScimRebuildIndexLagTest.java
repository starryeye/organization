package dev.starryeye.organization.core.usecase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleScanner;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncStatus;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * SCIM 재적재가 GSI 열거를 장부로 보강한다(설계 2026-10-13 §4). 락 직전에 커밋돼 아직 GSI 에 없는 대상의 권한을 지우지 않는다. DynamoDB Local 은 GSI 가
 * 즉시 반영돼 지연을 재현할 수 없어(§7) 가짜 저장소로 본다.
 */
class ScimRebuildIndexLagTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 이 = MemberRef.user("lee");
    private static final MemberRef 박 = MemberRef.user("park");
    private static final MemberRef 팀 = MemberRef.group("TEAM");
    private static final MemberRef 백엔드 = MemberRef.group("DEV002");
    private static final RelationTuple 김_백엔드 = RelationTuple.directMember("kim", "DEV002");
    private static final RelationTuple 이_백엔드 = RelationTuple.directMember("lee", "DEV002");
    private static final RelationTuple 백엔드_개발본부 = RelationTuple.child("DEV002", "DEV001");
    private static final RelationTuple 김_팀 = RelationTuple.directMember("kim", "TEAM");
    private static final RelationTuple 박_팀 = RelationTuple.directMember("park", "TEAM");
    private static final RelationTuple 팀_개발본부 = RelationTuple.child("TEAM", "DEV001");
    private static final RelationTuple 유령_백엔드 = RelationTuple.directMember("ghost", "DEV002");
    private static final RelationTuple 이_팀 = RelationTuple.directMember("lee", "TEAM");

    /** GSI 지연을 흉내 낸다 — 숨긴 대상은 열거(loadAll)에서만 빠지고 강한 읽기(findUsers·findGroup)에서는 보인다. 조직 파티션은 강한 읽기라 멤버로는 보인다. */
    static class 열거가_늦은_저장소 extends FakeStateRepository {

        final Set<String> 숨긴_직원 = new LinkedHashSet<>();
        final Set<String> 숨긴_조직 = new LinkedHashSet<>();

        @Override
        public Mono<DirectorySnapshot> loadAll() {
            return super.loadAll().map(all -> new DirectorySnapshot(
                    보이는_것(all.users(), 숨긴_직원), 보이는_것(all.groups(), 숨긴_조직)));
        }

        /**
         * 실제 저장소처럼 숨긴 대상은 열거되지 않아 비교·삭제에서 빠지고, 끝난 뒤에도 남는다. 보이는 직원을 지우면 실제 저장소(직원을_지운다)처럼 그 직원의
         * 멤버 줄이 있던 조직의 멤버 줄도 지우고, 그 조직마다 이름표가 그대로인 "멤버만 빠짐" 비교 결과를 더한다 — 숨은 조직은 저장본에 없어 이름표가 비어 있다.
         */
        @Override
        public Mono<DirectoryChanges> replaceWith(DirectorySnapshot snapshot) {
            Map<String, DirectoryUser> 숨은_직원 = new LinkedHashMap<>(users);
            숨은_직원.keySet().retainAll(숨긴_직원);
            Map<String, DirectoryGroup> 숨은_조직 = new LinkedHashMap<>(groups);
            숨은_조직.keySet().retainAll(숨긴_조직);
            users.keySet().removeAll(숨긴_직원);
            groups.keySet().removeAll(숨긴_조직);
            DirectoryChanges changes = super.replaceWith(snapshot).block();
            List<DirectoryChanges.GroupDiff> 조직변경 = new ArrayList<>(changes.groups());
            for (DirectoryChanges.UserDiff diff : changes.users()) {
                if (diff.after() != null) {
                    continue;
                }
                MemberRef 지운_직원 = MemberRef.user(diff.id());
                for (Map.Entry<String, DirectoryGroup> hidden : 숨은_조직.entrySet()) {
                    DirectoryGroup group = hidden.getValue();
                    if (!group.members().contains(지운_직원)) {
                        continue;
                    }
                    Set<MemberRef> 남은_멤버 = new LinkedHashSet<>(group.members());
                    남은_멤버.remove(지운_직원);
                    hidden.setValue(new DirectoryGroup(group.id(), group.externalId(), group.displayName(), 남은_멤버));
                    GroupHeader 이름표 = new GroupHeader(group.id(), null, null);
                    조직변경.add(new DirectoryChanges.GroupDiff(이름표, 이름표, Set.of(), Set.of(지운_직원)));
                }
            }
            users.putAll(숨은_직원);
            groups.putAll(숨은_조직);
            return Mono.just(new DirectoryChanges(changes.users(), 조직변경));
        }

        /** 실제 저장소처럼 직원을 지우면 그 직원이 멤버인 조직의 멤버 줄도 지운다 — 조직을 먼저 지우는지 순서를 가린다. */
        @Override
        public Mono<Void> deleteUser(String userId) {
            return super.deleteUser(userId).then(Mono.fromRunnable(() -> {
                MemberRef 지운_직원 = MemberRef.user(userId);
                groups.replaceAll((id, group) -> {
                    if (!group.members().contains(지운_직원)) {
                        return group;
                    }
                    Set<MemberRef> 남은_멤버 = new LinkedHashSet<>(group.members());
                    남은_멤버.remove(지운_직원);
                    return new DirectoryGroup(group.id(), group.externalId(), group.displayName(), 남은_멤버);
                });
            }));
        }

        private static <T> Map<String, T> 보이는_것(Map<String, T> all, Set<String> hidden) {
            Map<String, T> visible = new LinkedHashMap<>(all);
            visible.keySet().removeAll(hidden);
            return visible;
        }
    }

    private 열거가_늦은_저장소 state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FakeAuditLog auditLog;
    private ScimRebuildUseCase useCase;
    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ScimRebuildUseCase.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
        state = new 열거가_늦은_저장소();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(NOW);
        auditLog = new FakeAuditLog();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        useCase = new ScimRebuildUseCase(state, writer, new FakeTupleScanner(writer), new FakeSnapshotRepository(),
                new SyncJobs(runs, new FakeMutationLock(), Duration.ofSeconds(10), LockObserver.NOOP, Duration.ofMinutes(1)),
                clock, new AuditRecorder(auditLog, AuditObserver.NOOP, clock));
    }

    @AfterEach
    void 원복한다() {
        logger.detachAppender(로그);
        로그.stop();
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "emp-" + id, id, id, null, true);
    }

    private void 조직을_심는다(String id, MemberRef... members) {
        state.saveGroup(new DirectoryGroup(id, id, id + "-조직", Set.of(members))).block();
    }

    private SyncRun 재적재한다(ScimRebuildMode mode) {
        SyncRun started = useCase.start(mode).block();
        return runs.awaitFinished(started.runId());
    }

    @Test
    @DisplayName("tuples — 락 직전에 만들어져 GSI 에 아직 없는 직원의 권한을 지우지 않는다")
    void 막_만든_직원의_권한을_지우지_않는다() {
        // given — kim 은 오래된 직원, lee 는 GSI 에 아직 없다. 둘 다 DEV002 의 멤버고 장부에 권한이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("DEV002", 김, 이);
        조직을_심는다("DEV001", 백엔드);
        writer.stored.addAll(Set.of(김_백엔드, 이_백엔드, 백엔드_개발본부));
        state.숨긴_직원.add("lee");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).doesNotContain(이_백엔드);
        assertThat(writer.stored).contains(김_백엔드, 이_백엔드, 백엔드_개발본부);
    }

    @Test
    @DisplayName("tuples — GSI 에 아직 없는 조직의 멤버 권한과 상위 연결을 지우지 않는다")
    void 막_만든_조직의_권한을_지우지_않는다() {
        // given — TEAM 은 GSI 에 아직 없다. DEV001 은 TEAM 을 하위 조직으로 적었고, 장부에 kim→TEAM, TEAM→DEV001 이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("TEAM", 김);
        조직을_심는다("DEV001", 팀);
        조직을_심는다("DEV002", 이);
        writer.stored.addAll(Set.of(김_팀, 팀_개발본부, 이_백엔드));
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).doesNotContain(김_팀, 팀_개발본부);
        assertThat(writer.stored).contains(김_팀, 팀_개발본부, 이_백엔드);
    }

    @Test
    @DisplayName("tuples — 다시 읽은 조직의 새 멤버 줄이 장부에 아직 없으면 써 준다")
    void 새_멤버의_줄은_써진다() {
        // given — TEAM 과 park 이 GSI 에 아직 없다. 장부에는 kim→TEAM 만 있고 park→TEAM 은 아직 없다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("park")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("TEAM", 김, 박);
        조직을_심는다("DEV002", 이);
        writer.stored.addAll(Set.of(김_팀, 이_백엔드));
        state.숨긴_조직.add("TEAM");
        state.숨긴_직원.add("park");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.stored).contains(김_팀, 박_팀, 이_백엔드);
        assertThat(writer.written).contains(박_팀);
        // 재적재가 쓴 줄은 이력에 남기지 않는다 — 지운 줄만 남긴다
        List<AuditEntry> 이력의_줄 = auditLog.events.stream().flatMap(event -> event.entries().stream()).toList();
        assertThat(이력의_줄).flatExtracting(AuditEntry::granted).doesNotContain(박_팀);
        assertThat(이력의_줄).flatExtracting(AuditEntry::revoked).doesNotContain(박_팀);
    }

    @Test
    @DisplayName("tuples — 다시 계산한 조직도가 순환이라 버린 연결은 장부에서도 지우고, 보류 목록도 다시 계산한 조직도를 따른다")
    void 다시_계산한_조직도의_순환_연결을_장부와_보류_목록이_함께_따른다() {
        // given — TEAM 이 GSI 에 아직 없다. 조직 0 ∋ B, A ∋ B, B ∋ TEAM, TEAM ∋ A 이고 장부에는 네 연결이 다 있다.
        //         TEAM 없이 정한 D 에는 순환이 없어 child(B,A) 가 들어 있다. TEAM 을 넣은 D' 는 DFS(0→B→TEAM→A)가 A→B 를 순환으로 버린다
        MemberRef 에이 = MemberRef.group("A");
        MemberRef 비 = MemberRef.group("B");
        조직을_심는다("0", 비);
        조직을_심는다("A", 비);
        조직을_심는다("B", 팀);
        조직을_심는다("TEAM", 에이);
        RelationTuple 비_영 = RelationTuple.child("B", "0");
        RelationTuple 비_에이 = RelationTuple.child("B", "A");
        RelationTuple 팀_비 = RelationTuple.child("TEAM", "B");
        RelationTuple 에이_팀 = RelationTuple.child("A", "TEAM");
        writer.stored.addAll(Set.of(비_영, 비_에이, 팀_비, 에이_팀));
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 장부와 보류 목록이 함께 D' 를 따른다. 버린 A→B 는 장부에 없고 보류 목록에 있다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).containsExactly(비_에이);
        assertThat(writer.stored).containsExactlyInAnyOrder(비_영, 팀_비, 에이_팀);
        assertThat(state.cutEdges).containsExactly(new GroupEdge("A", "B"));
        // 다시 계산한 조직도의 변환 경고도 남긴다 — 보강으로 새로 끊긴 순환 연결은 여기서만 보이고, 처음 변환의 경고와 앞말로 가른다
        assertThat(로그.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).startsWith("튜플 변환 경고: 다시 계산한 조직도 — ").contains("A", "B"));
    }

    @Test
    @DisplayName("tuples — 다시 읽어도 없는 대상(찌꺼기)의 줄은 지운다")
    void 다시_읽어도_없는_대상의_줄은_지운다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.addAll(Set.of(김_백엔드, 유령_백엔드));

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(writer.deleted).containsExactly(유령_백엔드);
        assertThat(state.findUsersCalls).contains(Set.of("ghost"));
    }

    @Test
    @DisplayName("tuples — 열거에서 빠진 것이 없으면 다시 읽지 않는다")
    void 평소에는_다시_읽지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.add(김_백엔드);

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.findUsersCalls).isEmpty();
        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("wipe — GSI 열거에서 빠진 직원·조직도 장부로 찾아 지우고, 이력에 삭제로 남긴다")
    void wipe_는_빠진_대상도_지운다() {
        // given — lee 와 TEAM 은 락 직전에 만들어져 GSI 에 없다. 장부에는 둘의 권한이 있다
        state.saveUser(직원("kim")).block();
        state.saveUser(직원("lee")).block();
        조직을_심는다("DEV002", 김);
        조직을_심는다("TEAM", 이);
        writer.stored.addAll(Set.of(김_백엔드, 이_팀));
        state.숨긴_직원.add("lee");
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.users).isEmpty();
        assertThat(state.groups).isEmpty();
        assertThat(auditLog.lastOf(이).deleted()).isTrue();
        assertThat(auditLog.lastOf(팀).deleted()).isTrue();
        assertThat(auditLog.lastOf(팀).membersRemoved()).containsExactly(이);
        // 다시 읽은 것은 빠진 대상뿐이다 — 보이는 kim 과 DEV002 는 읽지 않는다
        assertThat(state.findUsersCalls).containsExactly(Set.of("lee"));
        assertThat(state.findGroupCalls).containsExactly("TEAM");
    }

    @Test
    @DisplayName("wipe — 열거에서 빠진 것이 없으면 다시 읽지 않는다")
    void 평소_wipe_는_다시_읽지_않는다() {
        // given
        state.saveUser(직원("kim")).block();
        조직을_심는다("DEV002", 김);
        writer.stored.add(김_백엔드);

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.users).isEmpty();
        assertThat(state.findUsersCalls).isEmpty();
        assertThat(state.findGroupCalls).isEmpty();
    }

    @Test
    @DisplayName("wipe — 보이는 직원을 지우며 멤버 줄이 빠진 GSI 밖 조직도 멤버만 빠진 것이 아니라 삭제로 남긴다")
    void wipe_는_멤버만_빠진_조직도_삭제로_남긴다() {
        // given — TEAM 은 GSI 에 없고 멤버는 보이는 kim 하나다. 장부에는 kim→TEAM 이 있다
        state.saveUser(직원("kim")).block();
        조직을_심는다("TEAM", 김);
        writer.stored.add(김_팀);
        state.숨긴_조직.add("TEAM");

        // when
        SyncRun run = 재적재한다(ScimRebuildMode.WIPE);

        // then — kim 을 지우며 TEAM 의 멤버 줄도 빠졌지만, TEAM 은 남지 않고 삭제로 남는다. 이름표는 지우기 전 값이고 나간 멤버는 kim 이다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.users).isEmpty();
        assertThat(state.groups).doesNotContainKey("TEAM");
        AuditEntry 팀_줄 = auditLog.lastOf(팀);
        assertThat(팀_줄.deleted()).isTrue();
        assertThat(팀_줄.attributes())
                .extracting(AttributeChange::name, AttributeChange::before, AttributeChange::after)
                .containsExactly(tuple("externalId", "TEAM", null), tuple("displayName", "TEAM-조직", null));
        assertThat(팀_줄.membersRemoved()).containsExactly(김);
    }
}
