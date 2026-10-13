package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.audit.AuditEntry;
import dev.starryeye.organization.core.audit.AuditEvent;
import dev.starryeye.organization.core.audit.AuditRecord;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.AuditLog;
import dev.starryeye.organization.core.query.Page;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 가짜 이력 저장소. 받은 사건을 쌓아 두고, 대상의 줄을 최신순으로 돌려준다(커서는 보지 않는다). */
public class FakeAuditLog implements AuditLog {

    public final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    /** 조회가 받은 {@code limit} — 관리 API 의 기본값을 본다. */
    public final List<Integer> historyLimits = new CopyOnWriteArrayList<>();
    /** 있으면 쓰기가 이 오류로 끝난다. */
    public volatile RuntimeException failWith;
    /** 쓰기마다 빠졌다고 답할 줄 수. */
    public volatile int missingLines;
    /** 쓰는 순간 부른다 — 그때 락을 쥐고 있었는지 같은 것을 본다. */
    public volatile Runnable onWrite = () -> {
    };

    @Override
    public Mono<Integer> write(AuditEvent event) {
        return Mono.defer(() -> {
            onWrite.run();
            if (failWith != null) {
                return Mono.error(failWith);
            }
            events.add(event);
            return Mono.just(missingLines);
        });
    }

    @Override
    public Mono<Page<AuditRecord>> history(MemberRef subject, String cursor, int limit) {
        return Mono.fromSupplier(() -> {
            historyLimits.add(limit);
            List<AuditRecord> records = new ArrayList<>();
            for (AuditEvent event : events) {
                event.entries().stream()
                        .filter(entry -> entry.subject().equals(subject))
                        .forEach(entry -> records.add(new AuditRecord(event.at(), event.cause(), entry)));
            }
            Collections.reverse(records);
            return new Page<>(records.stream().limit(limit).toList(), null);
        });
    }

    /** 이 대상의 줄 — 쓴 순서대로. */
    public List<AuditEntry> entriesOf(MemberRef subject) {
        return events.stream()
                .flatMap(event -> event.entries().stream())
                .filter(entry -> entry.subject().equals(subject))
                .toList();
    }

    /** 이 대상의 마지막 줄. 없으면 {@link IllegalStateException}. */
    public AuditEntry lastOf(MemberRef subject) {
        List<AuditEntry> entries = entriesOf(subject);
        if (entries.isEmpty()) {
            throw new IllegalStateException("이 대상의 줄이 없다: " + subject);
        }
        return entries.get(entries.size() - 1);
    }
}
