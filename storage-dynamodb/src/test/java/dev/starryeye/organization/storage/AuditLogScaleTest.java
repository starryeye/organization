package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.audit.AuditCause;
import dev.starryeye.organization.core.audit.AuditChanges;
import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditKind;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직원 10만 명 초기 적재의 이력 쓰기(설계 2026-10-10 §3.6·§9 규모). 초기 적재가 돌려줄 비교 결과를 바로 만들어 이력만 잰다 — 전체 교체의 쓰기 수와
 * "같은 조직도면 빈 결과(이력 쓰기 0)" 는 {@link ReplaceWithScaleTest} 가 본다. 10만 명 조직 하나를 넣어 실제 조각 한도로 나누고 합치는 것도 본다.
 */
@ScaleTest
class AuditLogScaleTest extends DynamoDbTestSupport {

    private static final int 전체 = 100_000;

    @Test
    @DisplayName("10만 명 초기 적재의 이력은 대상마다 한 줄이고, 10만 명 조직의 줄은 조각으로 나뉘어 읽을 때 합쳐진다")
    void 초기_적재의_이력() {
        // given — 직원 10만 명 생성, 10명 조직 100개, 전원이 든 조직 ALL 하나
        List<DirectoryChanges.UserDiff> users = new ArrayList<>();
        Set<MemberRef> 전원 = new LinkedHashSet<>();
        for (int i = 0; i < 전체; i++) {
            String id = "u%06d".formatted(i);
            users.add(new DirectoryChanges.UserDiff(null, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true)));
            전원.add(MemberRef.user(id));
        }
        List<DirectoryChanges.GroupDiff> groups = new ArrayList<>();
        for (int g = 0; g < 100; g++) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (int m = 0; m < 10; m++) {
                members.add(MemberRef.user("u%06d".formatted(g * 10 + m)));
            }
            String code = "G%03d".formatted(g);
            groups.add(new DirectoryChanges.GroupDiff(null, new GroupHeader(code, "g" + g, "조직 " + g), members, Set.of()));
        }
        groups.add(new DirectoryChanges.GroupDiff(null, new GroupHeader("ALL", "all", "전사"), 전원, Set.of()));
        List<AuditEntry> entries = new AuditChanges().directory(new DirectoryChanges(users, groups)).entries();

        WriteCounter counter = new WriteCounter();
        DynamoDbAuditLog auditLog = new DynamoDbAuditLog(counter.wrap(client), properties);
        AuditEvent event = new AuditEvent(Instant.now(), AuditCause.run(SyncSource.LDAP, AuditKind.SYNC, "run-1"), entries);

        // when
        long 시작 = System.currentTimeMillis();
        Integer 빠진 = auditLog.write(event).block(Duration.ofMinutes(30));
        long 걸린 = System.currentTimeMillis() - 시작;
        Page<AuditRecord> 전사 = auditLog.history(MemberRef.group("ALL"), null, 1).block(Duration.ofMinutes(5));
        Page<AuditRecord> 직원 = auditLog.history(MemberRef.user("u000000"), null, 20).block(Duration.ofMinutes(1));

        // then — 줄은 직원 10만 + 조직 101. ALL 의 멤버 값 "USER#u000000" 은 12바이트 + 4 = 16 이라 조각마다 18,750개, 조각 6개.
        // 그래서 아이템은 100,100 + 6 = 100,106개다
        System.out.printf("감사 기록: 줄 %,d / 아이템 %,d / %,dms%n", entries.size(), counter.batchItems(), 걸린);
        assertThat(entries).hasSize(전체 + 101);
        assertThat(빠진).isZero();
        assertThat(counter.batchItems()).isEqualTo(100_106);
        assertThat(전사.items()).singleElement()
                .satisfies(record -> assertThat(record.entry().membersAdded()).hasSize(전체));
        assertThat(직원.items()).singleElement()
                .satisfies(record -> assertThat(record.entry().joined()).containsExactly("ALL", "G000"));
    }
}
