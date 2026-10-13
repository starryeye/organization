package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.audit.AttributeChange;
import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.fake.FakeAuditLog;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 변경 이력 조회 API(설계 2026-10-10 §5). */
class AuditHistoryControllerTest {

    private static final Instant T1 = Instant.parse("2026-10-10T03:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-10T03:01:00Z");

    private final FakeAuditLog auditLog = new FakeAuditLog();
    private final WebTestClient client = WebTestClient.bindToController(new AuditHistoryController(auditLog)).build();

    private void 쓴다(Instant at, AuditCause cause, AuditEntry entry) {
        auditLog.write(new AuditEvent(at, cause, List.of(entry))).block();
    }

    @Test
    @DisplayName("직원 이력을 최신순으로 돌려준다 — 원인, 바뀐 속성, 소속, 권한. 직원 줄에는 멤버 칸이 없다")
    void 직원_이력() {
        // given
        쓴다(T1, AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, "run-1"),
                new AuditEntry(MemberRef.user("kim"), true, false, null, null, null, null, null, null, null));
        쓴다(T2, AuditCause.scimRequest("trace-2"), new AuditEntry(MemberRef.user("kim"), false, false,
                List.of(new AttributeChange("displayName", "김", "김철수")), List.of("DEV"), null, null, null,
                List.of(RelationTuple.directMember("kim", "DEV")), null));

        // when, then
        client.get().uri("/admin/employees/kim/history").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].at").isEqualTo("2026-10-10T03:01:00Z")
                .jsonPath("$.items[0].cause.source").isEqualTo("SCIM")
                .jsonPath("$.items[0].cause.kind").isEqualTo("SCIM_REQUEST")
                .jsonPath("$.items[0].cause.id").isEqualTo("trace-2")
                .jsonPath("$.items[0].attributes[0].name").isEqualTo("displayName")
                .jsonPath("$.items[0].attributes[0].before").isEqualTo("김")
                .jsonPath("$.items[0].attributes[0].after").isEqualTo("김철수")
                .jsonPath("$.items[0].joined[0]").isEqualTo("DEV")
                .jsonPath("$.items[0].granted[0]").isEqualTo("user:kim direct_member group:DEV")
                .jsonPath("$.items[0].membersAdded").doesNotExist()
                .jsonPath("$.items[1].created").isEqualTo(true)
                .jsonPath("$.items[1].cause.id").isEqualTo("run-1")
                .jsonPath("$.nextCursor").doesNotExist();
    }

    @Test
    @DisplayName("조직 이력은 멤버 들어옴·나감의 수와 목록을 싣는다")
    void 조직_이력() {
        // given
        쓴다(T1, AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, "run-1"), new AuditEntry(MemberRef.group("DEV"), true,
                false, null, null, null, List.of(MemberRef.user("kim"), MemberRef.user("lee")), null, null, null));

        // when, then
        client.get().uri("/admin/organizations/DEV/history").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].created").isEqualTo(true)
                .jsonPath("$.items[0].membersAdded.count").isEqualTo(2)
                .jsonPath("$.items[0].membersAdded.items[0].type").isEqualTo("USER")
                .jsonPath("$.items[0].membersAdded.items[0].id").isEqualTo("kim")
                .jsonPath("$.items[0].membersRemoved.count").isEqualTo(0);
    }

    @Test
    @DisplayName("기록이 없는 아이디는 404 가 아니라 빈 목록이다")
    void 기록이_없으면_빈_목록() {
        // when, then
        client.get().uri("/admin/employees/nobody/history").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("limit 은 기본 20 이고, 1 보다 작거나 100 보다 크면 400 이다")
    void limit_범위() {
        // when, then
        client.get().uri("/admin/employees/kim/history").exchange().expectStatus().isOk();
        client.get().uri("/admin/employees/kim/history?limit=0").exchange().expectStatus().isBadRequest();
        client.get().uri("/admin/organizations/DEV/history?limit=101").exchange().expectStatus().isBadRequest();

        // then
        assertThat(auditLog.historyLimits).containsExactly(20);
    }

    @Test
    @DisplayName("저장소가 커서를 거절하면 400 이다")
    void 위조_커서는_400이다() {
        // given
        AuditLog 커서를_거절하는 = new AuditLog() {
            @Override
            public Mono<Integer> write(AuditEvent event) {
                return Mono.just(0);
            }

            @Override
            public Mono<Page<AuditRecord>> history(MemberRef subject, String cursor, int limit) {
                return Mono.error(new IllegalArgumentException("이 대상의 이력 커서가 아니다"));
            }
        };

        // when, then
        WebTestClient.bindToController(new AuditHistoryController(커서를_거절하는)).build()
                .get().uri("/admin/employees/kim/history?cursor=forged").exchange()
                .expectStatus().isBadRequest();
    }
}
