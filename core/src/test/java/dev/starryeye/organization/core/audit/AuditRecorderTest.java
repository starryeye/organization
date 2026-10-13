package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRecorderTest {

    private static final Instant 지금 = Instant.parse("2026-10-10T03:00:00Z");
    /** 부르는 쪽이 락 안에서 커밋한 직후 잡은 시각 — 시계의 지금과 일부러 다르게 둔다. */
    private static final Instant 커밋시각 = Instant.parse("2026-10-10T02:59:00Z");
    private static final AuditCause 원인 = AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, "run-1");

    private final FakeAuditLog auditLog = new FakeAuditLog();
    private final AtomicInteger 빠진_줄_지표 = new AtomicInteger();
    private final AuditRecorder recorder =
            new AuditRecorder(auditLog, 빠진_줄_지표::addAndGet, Clock.fixed(지금, ZoneOffset.UTC));

    private static AuditEntry 김의_줄() {
        return new AuditChanges().user(null, new DirectoryUser("kim", null, "kim", "김", null, true)).entries().get(0);
    }

    @Test
    @DisplayName("사건을 쓰는 때의 시계가 아니라 넘겨받은 커밋 시각과 원인으로 한 번에 쓴다")
    void 넘겨받은_시각과_원인으로_쓴다() {
        // given, when
        Integer 빠진 = recorder.record(원인, 커밋시각, List.of(김의_줄())).block();

        // then
        assertThat(빠진).isZero();
        assertThat(auditLog.events).singleElement().satisfies(event -> {
            assertThat(event.at()).isEqualTo(커밋시각);
            assertThat(event.cause()).isEqualTo(원인);
            assertThat(event.entries()).containsExactly(김의_줄());
        });
        assertThat(빠진_줄_지표).hasValue(0);
    }

    @Test
    @DisplayName("부르는 쪽이 커밋 시각을 잡을 지금은 서버 시계다")
    void 지금은_서버_시계다() {
        // given, when
        Instant now = recorder.now();

        // then
        assertThat(now).isEqualTo(지금);
    }

    @Test
    @DisplayName("빈 줄은 빼고, 남길 줄이 없으면 쓰지 않는다")
    void 남길_줄이_없으면_쓰지_않는다() {
        // given
        AuditEntry 빈_줄 = new AuditEntry(MemberRef.user("kim"), false, false, null, null, null, null, null, null, null);

        // when
        Integer 빠진 = recorder.record(원인, 커밋시각, List.of(빈_줄)).block();

        // then
        assertThat(빠진).isZero();
        assertThat(auditLog.events).isEmpty();
    }

    @Test
    @DisplayName("쓰기가 오류로 끝나도 오류를 내지 않고, 빠진 줄을 세어 지표로 알린다")
    void 오류를_삼키고_센다() {
        // given
        auditLog.failWith = new IllegalStateException("DynamoDB 장애");

        // when
        Integer 빠진 = recorder.record(원인, 커밋시각, List.of(김의_줄())).block();

        // then
        assertThat(빠진).isEqualTo(1);
        assertThat(빠진_줄_지표).hasValue(1);
    }

    @Test
    @DisplayName("지표로 알리다 던져도 오류를 내지 않고 빠진 줄 수를 돌려준다")
    void 지표가_던져도_빠진_줄을_돌려준다() {
        // given
        auditLog.missingLines = 1;
        AuditObserver 던지는_지표 = lines -> {
            throw new IllegalStateException("지표 장애");
        };
        AuditRecorder 던지는_지표의_기록기 = new AuditRecorder(auditLog, 던지는_지표, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        Integer 빠진 = 던지는_지표의_기록기.record(원인, 커밋시각, List.of(김의_줄())).block();

        // then
        assertThat(빠진).isEqualTo(1);
    }

    @Test
    @DisplayName("저장소가 빠진 줄을 알리면 회차 메시지 앞에 붙이고 결과는 그대로 둔다")
    void 회차_메시지에_붙인다() {
        // given
        auditLog.missingLines = 3;

        // when
        SyncOutcome outcome = recorder.recordRun(원인, 커밋시각, List.of(김의_줄()), SyncOutcome.noChange()).block();

        // then
        assertThat(outcome.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(outcome.message()).isEqualTo("감사 기록 3건 실패 / 변경 없음");
        assertThat(빠진_줄_지표).hasValue(3);
    }

    @Test
    @DisplayName("다 쓰면 회차 결과는 그대로다")
    void 다_쓰면_그대로() {
        // given, when
        SyncOutcome outcome = recorder.recordRun(원인, 커밋시각, List.of(김의_줄()), SyncOutcome.noChange()).block();

        // then
        assertThat(outcome).isEqualTo(SyncOutcome.noChange());
        assertThat(auditLog.events).singleElement().satisfies(event -> assertThat(event.at()).isEqualTo(커밋시각));
    }

    @Test
    @DisplayName("SCIM 요청의 원인은 traceId 이고, 모르면 임의 값을 쓴다")
    void SCIM_원인() {
        // given, when
        AuditCause 아는 = AuditCause.scimRequest("trace-1");
        AuditCause 모르는 = AuditCause.scimRequest(null);

        // then
        assertThat(아는).isEqualTo(new AuditCause(SyncSource.SCIM, AuditKind.SCIM_REQUEST, "trace-1"));
        assertThat(모르는.id()).isNotBlank();
        assertThat(모르는.kind()).isEqualTo(AuditKind.SCIM_REQUEST);
    }
}
