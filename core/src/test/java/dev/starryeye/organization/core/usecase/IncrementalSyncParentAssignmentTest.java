package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.audit.Actor;
import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditObserver;
import dev.starryeye.organization.core.audit.AuditRecorder;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 관리 API 의 지정 상위 두기·없애기 — SCIM 쓰기와 같은 락·Check 기준선·diff·감사, 순환은 거절(설계 2026-11-04 H1·H4·H5·H7, §5.4·§6). */
class IncrementalSyncParentAssignmentTest {

    private static final Instant 고정시각 = Instant.parse("2026-11-04T03:00:00Z");
    private static final MemberRef 하위 = MemberRef.group("C");

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private FakeAuditLog auditLog;
    private IncrementalSyncUseCase useCase;

    /** 조직 P·P0·C 가 있다. 각 테스트가 지정(state.assignedParents, 하위 → 상위)과 OpenFGA 에 있다고 볼 튜플(checker.allowed)을 더한다. */
    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        auditLog = new FakeAuditLog();
        AuditRecorder audit = new AuditRecorder(auditLog, AuditObserver.NOOP, Clock.fixed(고정시각, ZoneOffset.UTC));
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, Duration.ofSeconds(10),
                IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP, audit, context -> "trace-1");
        state.groups.put("P", 조직("P"));
        state.groups.put("P0", 조직("P0"));
        state.groups.put("C", 조직("C"));
    }

    private static DirectoryGroup 조직(String code, MemberRef... members) {
        return new DirectoryGroup(code, code, "백엔드팀", Set.of(members));
    }

    /** 운영 도구(개발 클라이언트 org-chart)의 토큰으로 온 요청이다. */
    private static Mono<IncrementalSyncResult> 운영자가(Mono<IncrementalSyncResult> 요청) {
        return 요청.contextWrite(Context.of(Actor.KEY, Actor.client("org-chart")));
    }

    private IncrementalSyncResult 지정한다(String orgCode, String parentOrgCode) {
        return 운영자가(useCase.assignParent(orgCode, parentOrgCode)).block();
    }

    private IncrementalSyncResult 없앤다(String orgCode) {
        return 운영자가(useCase.clearParent(orgCode)).block();
    }

    private HierarchyRejectedException 거절(String orgCode, String parentOrgCode) {
        return catchThrowableOfType(() -> 지정한다(orgCode, parentOrgCode), HierarchyRejectedException.class);
    }

    @Test
    @DisplayName("지정하면 child 줄을 쓰고, 지정을 저장하고, 하위 조직의 이력에 지정 요청을 원인으로 남긴다")
    void 지정하면_child_를_쓰고_저장하고_남긴다() {
        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).containsExactly(RelationTuple.child("C", "P"));
        assertThat(state.assignedParents).containsExactly(Map.entry("C", "P"));
        assertThat(state.saveAssignedParentCalls).containsExactly(Arrays.asList("C", "P", null));
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.hierarchyRequest("trace-1", "client:org-chart")));
        AuditEntry 하위의_줄 = auditLog.lastOf(하위);
        assertThat(하위의_줄.attributes()).containsExactly(new AttributeChange("assignedParent", null, "P"));
        assertThat(하위의_줄.granted()).contains(RelationTuple.child("C", "P"));
    }

    @Test
    @DisplayName("같은 상위로 다시 지정하고 줄도 있으면 아무것도 쓰지 않고 이력도 남기지 않는다")
    void 같은_지정을_다시_하면_아무것도_하지_않는다() {
        // given
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.saveAssignedParentCalls).isEmpty();
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("같은 상위로 다시 지정했는데 OpenFGA 에 줄이 없으면(어긋남) 줄을 쓴다 — 지정은 그대로라 속성 변경은 남기지 않는다")
    void 같은_지정인데_줄이_없으면_쓴다() {
        // given
        state.assignedParents.put("C", "P");

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.acquired).hasValue(1);
        assertThat(writer.written).containsExactly(RelationTuple.child("C", "P"));
        assertThat(auditLog.lastOf(하위).attributes()).extracting(AttributeChange::name).doesNotContain("assignedParent");
    }

    @Test
    @DisplayName("다른 상위로 바꾸면 옛 줄을 지우고 새 줄을 쓰고, 이력에 이전 → 이후를 남긴다")
    void 바꾸면_옛_줄을_지우고_새_줄을_쓴다() {
        // given
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).contains(RelationTuple.child("C", "P0"));
        assertThat(writer.written).contains(RelationTuple.child("C", "P"));
        assertThat(state.assignedParents).containsExactly(Map.entry("C", "P"));
        assertThat(state.saveAssignedParentCalls).containsExactly(Arrays.asList("C", "P", "P0"));
        assertThat(auditLog.lastOf(하위).attributes()).containsExactly(new AttributeChange("assignedParent", "P0", "P"));
    }

    @Test
    @DisplayName("옛 상위에 IdP 가 보낸 멤버 행이 같은 쌍을 받치면 바꿔도 옛 줄은 지우지 않는다")
    void IdP_멤버가_옛_쌍을_받치면_옛_줄은_남는다() {
        // given
        state.groups.put("P0", 조직("P0", 하위));
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).doesNotContain(RelationTuple.child("C", "P0"));
        assertThat(writer.written).contains(RelationTuple.child("C", "P"));
        assertThat(state.assignedParents).containsExactly(Map.entry("C", "P"));
    }

    @Test
    @DisplayName("지정을 없애면 줄을 지우고 지정을 지우고, 이력에 이전 → 없음을 남긴다")
    void 없애면_줄을_지운다() {
        // given
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));

        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).contains(RelationTuple.child("C", "P0"));
        assertThat(lock.acquired).hasValue(1);
        assertThat(state.assignedParents).isEmpty();
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.cause())
                .isEqualTo(AuditCause.hierarchyRequest("trace-1", "client:org-chart")));
        assertThat(auditLog.lastOf(하위).attributes()).containsExactly(new AttributeChange("assignedParent", "P0", null));
    }

    @Test
    @DisplayName("지정을 없애도 IdP 가 보낸 멤버 행이 같은 쌍을 받치면 줄은 남는다 — 지정만 지운다")
    void 없애도_IdP_멤버가_받치면_줄이_남는다() {
        // given
        state.groups.put("P0", 조직("P0", 하위));
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));

        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.deleted).doesNotContain(RelationTuple.child("C", "P0"));
        assertThat(state.assignedParents).isEmpty();
        assertThat(auditLog.lastOf(하위).attributes()).containsExactly(new AttributeChange("assignedParent", "P0", null));
    }

    @Test
    @DisplayName("지정이 없는 조직의 지정을 없애면 아무것도 하지 않고 이력도 없다")
    void 지정이_없는데_없애면_아무것도_하지_않는다() {
        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("지정을 없애 순환이 풀리면 보류했던 IdP 연결을 쓰고 보류 목록에서 뺀다")
    void 없앤_뒤_보류_줄이_돌아온다() {
        // given — 지정 P ⊃ C 가 먼저 저장돼, IdP 가 보낸 C ⊃ P 는 보류돼 있다
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));
        state.groups.put("C", 조직("C", MemberRef.group("P")));
        state.cutEdges.add(new GroupEdge("C", "P"));

        // when
        없앤다("C");

        // then
        assertThat(writer.written).contains(RelationTuple.child("P", "C"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("지정이 순환을 닫으면 거절하고 아무것도 쓰지 않는다 — OpenFGA·지정·보류 목록·이력 모두 그대로다")
    void 순환이면_거절하고_아무것도_쓰지_않는다() {
        // given — IdP 가 보낸 C ⊃ P 가 이미 있다
        state.groups.put("C", 조직("C", MemberRef.group("P")));
        checker.allowed.add(RelationTuple.child("P", "C"));

        // when
        HierarchyRejectedException 거절 = 거절("C", "P");

        // then
        assertThat(거절).isNotNull();
        assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.CYCLE);
        assertThat(거절).hasMessage("P 를 C 의 상위로 두면 순환이 된다");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.assignedParents).isEmpty();
        assertThat(state.cutEdges).isEmpty();
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("자기 자신을 상위로 지정하면 순환으로 거절한다")
    void 자기_자신은_순환이다() {
        // when
        HierarchyRejectedException 거절 = 거절("C", "C");

        // then
        assertThat(거절).isNotNull();
        assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.CYCLE);
        assertThat(state.assignedParents).isEmpty();
    }

    @Test
    @DisplayName("같은 상위로 이미 지정돼 있어도 그 연결이 보류 중이면 거절한다 — 이 요청 뒤에도 반영되지 않는다")
    void 보류_중인_같은_지정은_거절한다() {
        // given — 재적재가 지정 P ⊃ C 를 잘라 보류했고, IdP 의 C ⊃ P 가 OpenFGA 에 있다
        state.assignedParents.put("C", "P");
        state.cutEdges.add(new GroupEdge("P", "C"));
        state.groups.put("C", 조직("C", MemberRef.group("P")));
        checker.allowed.add(RelationTuple.child("P", "C"));

        // when
        HierarchyRejectedException 거절 = 거절("C", "P");

        // then
        assertThat(거절).isNotNull();
        assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.CYCLE);
        assertThat(state.assignedParents).containsExactly(Map.entry("C", "P"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("P", "C"));
    }

    @Test
    @DisplayName("순환 판정이 걷기 예산을 넘으면 거절이 아니라 GroupGraphTooLargeException 이고 아무것도 쓰지 않는다")
    void 걷기_예산을_넘으면_예외다() {
        // given — IdP 가 보낸 G1 ⊃ G2 ⊃ … ⊃ G10101 (G10101 의 조상이 10,100개)
        int 깊이 = OrgGraph.MAX_EXPANSIONS + 100;
        for (int i = 1; i <= 깊이; i++) {
            state.groups.put("G" + i, 조직("G" + i, MemberRef.group("G" + (i + 1))));
        }
        state.groups.put("G" + (깊이 + 1), 조직("G" + (깊이 + 1)));

        // when
        GroupGraphTooLargeException 예외 = catchThrowableOfType(() -> 지정한다("C", "G" + (깊이 + 1)), GroupGraphTooLargeException.class);

        // then
        assertThat(예외).isNotNull();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.assignedParents).isEmpty();
    }

    @Test
    @DisplayName("상위 조직이 없으면 거절한다")
    void 상위_조직이_없으면_거절한다() {
        // when
        HierarchyRejectedException 거절 = 거절("C", "NONE");

        // then
        assertThat(거절).isNotNull();
        assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.PARENT_NOT_FOUND);
        assertThat(거절).hasMessage("상위 조직이 없다: NONE");
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("대상 조직이 없으면 두기도 없애기도 빈 결과다")
    void 대상_조직이_없으면_빈_결과다() {
        // when, then
        assertThat(운영자가(useCase.assignParent("NONE", "P")).blockOptional()).isEmpty();
        assertThat(운영자가(useCase.clearParent("NONE")).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("대상 조직이 없는데 저장된 지정이 남아 있어도 앞 확인이 넘기고 빈 결과다 — 락 안에서 지금처럼 끝난다")
    void 대상_조직이_없고_지정이_남아_있으면_앞_확인이_넘기고_빈_결과다() {
        // given — NONE 의 이름표는 없는데 지정과 줄은 남아 있다
        state.assignedParents.put("NONE", "P");
        checker.allowed.add(RelationTuple.child("NONE", "P"));

        // when, then
        assertThat(운영자가(useCase.assignParent("NONE", "P")).blockOptional()).isEmpty();
        assertThat(lock.acquired).hasValue(1);
    }

    @Test
    @DisplayName("줄 쓰기가 실패하면 지정을 저장하지 않는다 — 다시 보내면 된다")
    void 쓰기가_실패하면_저장을_바꾸지_않는다() {
        // given
        writer.failFor(tuple -> tuple.equals(RelationTuple.child("C", "P")));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isFalse();
        assertThat(state.assignedParents).isEmpty();
        assertThat(state.saveAssignedParentCalls).isEmpty();
    }

    @Test
    @DisplayName("IdP 가 조직을 지우면 그 조직을 상위로 지정했던 하위 조직의 이력에 지정 상위 → 없음을, 지운 조직의 줄에도 제 지정 상위 → 없음을 남긴다")
    void 조직을_지우면_지정_하위의_이력에_남는다() {
        // given — 멤버 행 없이 지정만: P ⊃ G ⊃ C
        state.groups.put("G", 조직("G"));
        state.assignedParents.put("G", "P");
        state.assignedParents.put("C", "G");
        checker.allowed.addAll(Set.of(RelationTuple.child("G", "P"), RelationTuple.child("C", "G")));

        // when
        IncrementalSyncResult result = useCase.removeGroup("G").block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(auditLog.lastOf(하위).attributes()).containsExactly(new AttributeChange("assignedParent", "G", null));
        assertThat(auditLog.lastOf(MemberRef.group("G")).attributes()).contains(new AttributeChange("assignedParent", "P", null));
    }

    @Test
    @DisplayName("조직 지우기가 일부 실패하면 아이템을 지운 지정 연결만 이력에 남긴다")
    void 조직_지우기가_일부_실패하면_지운_지정만_남는다() {
        // given — C ⊃ G 의 줄을 지우지 못한다
        state.groups.put("G", 조직("G"));
        state.assignedParents.put("G", "P");
        state.assignedParents.put("C", "G");
        checker.allowed.addAll(Set.of(RelationTuple.child("G", "P"), RelationTuple.child("C", "G")));
        writer.failFor(tuple -> tuple.equals(RelationTuple.child("C", "G")));

        // when
        IncrementalSyncResult result = useCase.removeGroup("G").block();

        // then — 남은 G 의 지정은 지워졌고 C 의 지정은 남았다
        assertThat(result.fullyApplied()).isFalse();
        assertThat(auditLog.lastOf(MemberRef.group("G")).attributes())
                .containsExactly(new AttributeChange("assignedParent", "P", null));
        assertThat(auditLog.entriesOf(하위)).flatExtracting(AuditEntry::attributes).isEmpty();
    }

    @Test
    @DisplayName("같은 상위로 다시 지정하고 줄도 있으면 락을 시도하지 않고 끝난다 — Check 는 그 줄 하나다")
    void 같은_지정이고_줄이_있으면_락_없이_끝난다() {
        // given
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.시도한_토큰).isEmpty();
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(auditLog.events).isEmpty();
        assertThat(checker.checked).containsExactly(RelationTuple.child("C", "P"));
    }

    @Test
    @DisplayName("다른 요청이 락을 쥔 동안에도 바뀐 것 없는 지정은 기다리지 않고 끝난다")
    void 다른_요청이_락을_쥔_동안에도_같은_지정은_끝난다() {
        // given — 획득 한도가 0 이라 락을 시도하면 곧바로 LockUnavailableException 이다
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));
        lock.acquire(MutationLock.LockPurpose.WRITE, "다른-요청", null).block();

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.시도한_토큰).containsExactly("다른-요청");
    }

    @Test
    @DisplayName("다른 상위로 바꾸는 요청은 앞 확인에서 Check 하지 않고 락 안으로 간다")
    void 다른_상위면_앞_확인이_Check_하지_않는다() {
        // given
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));

        // when
        지정한다("C", "P");

        // then — 락 안의 Check 기준선이 child(C,P) 를 한 번 본다. 앞 확인이 보면 두 번이다
        assertThat(lock.acquired).hasValue(1);
        assertThat(checker.checked).containsOnlyOnce(RelationTuple.child("C", "P"));
    }

    @Test
    @DisplayName("지정이 없는 조직을 가리키면 앞 확인이 넘기고 지금처럼 상위 없음으로 거절한다")
    void 지정이_없는_조직을_가리키면_지금처럼_거절한다() {
        // given
        state.assignedParents.put("C", "GONE");
        checker.allowed.add(RelationTuple.child("C", "GONE"));

        // when
        HierarchyRejectedException 거절 = 거절("C", "GONE");

        // then
        assertThat(거절.reason()).isEqualTo(HierarchyRejectedException.Reason.PARENT_NOT_FOUND);
        assertThat(lock.acquired).hasValue(1);
    }

    @Test
    @DisplayName("앞 확인의 Check 가 실패하면 락 안에서 지금처럼 끝낸다")
    void 앞_확인의_Check_가_실패하면_락_안에서_끝낸다() {
        // given — 첫 Check 만 실패한다
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));
        AtomicBoolean 처음 = new AtomicBoolean(true);
        checker.failFor(tuple -> 처음.getAndSet(false));

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.acquired).hasValue(1);
        assertThat(writer.appliedDeltas).isEmpty();
    }

    @Test
    @DisplayName("앞 확인의 지정 읽기가 실패하면 락 안에서 지금처럼 끝낸다")
    void 앞_확인의_지정_읽기가_실패하면_락_안에서_끝낸다() {
        // given
        state.assignedParents.put("C", "P");
        checker.allowed.add(RelationTuple.child("C", "P"));
        state.findAssignedParentFailsOnce.add("C");

        // when
        IncrementalSyncResult result = 지정한다("C", "P");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.acquired).hasValue(1);
    }

    @Test
    @DisplayName("앞 확인의 지정 읽기가 실패하면 지정 없애기도 락 안에서 지금처럼 끝낸다")
    void 앞_확인의_지정_읽기가_실패하면_없애기도_락_안에서_끝낸다() {
        // given
        state.assignedParents.put("C", "P0");
        checker.allowed.add(RelationTuple.child("C", "P0"));
        state.findAssignedParentFailsOnce.add("C");

        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.acquired).hasValue(1);
        assertThat(state.assignedParents).isEmpty();
    }

    @Test
    @DisplayName("지정이 없는 조직의 지정을 없애면 락을 시도하지 않고 끝난다")
    void 지정이_없으면_없애기는_락_없이_끝난다() {
        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.시도한_토큰).isEmpty();
    }

    @Test
    @DisplayName("지정이 없는 조직을 가리키면 없애기는 락 없이 끝나고 그 지정 줄은 지금처럼 그대로다")
    void 지정이_없는_조직을_가리키면_없애기는_락_없이_끝난다() {
        // given
        state.assignedParents.put("C", "GONE");

        // when
        IncrementalSyncResult result = 없앤다("C");

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.시도한_토큰).isEmpty();
        assertThat(state.assignedParents).containsEntry("C", "GONE");
    }
}
