package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbSyncRunRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private DynamoDbSyncRunRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        repository = new DynamoDbSyncRunRepository(client, properties, Clock.fixed(지금, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("시작한 실행은 RUNNING 상태로 기록되고 고유한 아이디를 받는다")
    void 시작하면_RUNNING으로_기록된다() {
        // given, when
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(run.runId()).isNotBlank();
        assertThat(run.source()).isEqualTo(SyncSource.LDAP);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.SCHEDULED);
        assertThat(run.startedAt()).isEqualTo(지금);
    }

    @Test
    @DisplayName("완료 처리하면 상태와 집계값이 반영된 실행 이력이 조회된다")
    void 완료하면_집계값이_반영된다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
        var outcome = new SyncOutcome(SyncStatus.SUCCEEDED, 12, 3, 0, "20260814T030000-LDAP", null);

        // when
        var finished = repository.finish(run, outcome).block();
        var recent = repository.findRecent(10).collectList().block();

        // then — finish() 의 반환값 자체도 맞아야 하지만,
        assertThat(finished.status()).isEqualTo(SyncStatus.SUCCEEDED);
        // 진짜 검증은 DynamoDB 에서 다시 읽은 값이 맞는지다
        assertThat(recent).hasSize(1);
        var 조회된_실행 = recent.get(0);
        assertThat(조회된_실행.runId()).isEqualTo(run.runId());
        assertThat(조회된_실행.source()).isEqualTo(SyncSource.LDAP);
        assertThat(조회된_실행.trigger()).isEqualTo(SyncTrigger.SCHEDULED);
        assertThat(조회된_실행.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(조회된_실행.writtenCount()).isEqualTo(12);
        assertThat(조회된_실행.deletedCount()).isEqualTo(3);
        assertThat(조회된_실행.snapshotId()).isEqualTo("20260814T030000-LDAP");
        assertThat(조회된_실행.finishedAt()).isEqualTo(finished.finishedAt());
        assertThat(조회된_실행.message()).isNull();
    }

    @Test
    @DisplayName("가드가 발동해 중단된 실행은 ABORTED 상태와 사유가 함께 남는다")
    void 중단된_실행은_사유가_남는다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
        var 사유 = "삭제 대상 412건(기준 스냅샷 606건의 68.0%)이 임계치 30.0%를 초과했습니다";

        // when
        repository.finish(run, SyncOutcome.aborted(사유)).block();
        var recent = repository.findRecent(10).collectList().block();

        // then
        assertThat(recent.get(0).status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(recent.get(0).message()).isEqualTo(사유);
    }

    @Test
    @DisplayName("최근 실행 이력은 최신순으로 나오고 limit 만큼만 반환된다")
    void 최근_이력은_최신순이고_개수가_제한된다() {
        // given — 시각을 다르게 해서 3건 기록
        for (int i = 0; i < 3; i++) {
            var repo = new DynamoDbSyncRunRepository(client, properties,
                    Clock.fixed(지금.plusSeconds(i * 60L), ZoneOffset.UTC));
            var run = repo.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
            repo.finish(run, SyncOutcome.noChange()).block();
        }

        // when
        var recent = repository.findRecent(2).collectList().block();

        // then
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).startedAt()).isAfter(recent.get(1).startedAt());
    }

    @Test
    @DisplayName("이력이 없으면 빈 목록을 반환한다")
    void 이력이_없으면_빈_목록이다() {
        // given, when
        var recent = repository.findRecent(10).collectList().block();

        // then
        assertThat(recent).isEmpty();
    }
}
