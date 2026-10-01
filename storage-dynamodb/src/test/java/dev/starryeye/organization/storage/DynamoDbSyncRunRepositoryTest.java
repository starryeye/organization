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

    @Test
    @DisplayName("시작한 기록을 번호로 찾으면 RUNNING 이다")
    void 시작한_기록을_번호로_찾는다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.MANUAL).block();

        // when
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found).isNotNull();
        assertThat(found.runId()).isEqualTo(run.runId());
        assertThat(found.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(found.finishedAt()).isNull();
    }

    @Test
    @DisplayName("끝낸 기록을 번호로 찾으면 끝난 상태와 사유가 보인다")
    void 끝낸_기록을_번호로_찾는다() {
        // given
        var run = repository.start(SyncSource.SCIM, SyncTrigger.REBUILD).block();
        repository.finish(run, SyncOutcome.failed("기한 초과 — 30분")).block();

        // when
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(found.message()).isEqualTo("기한 초과 — 30분");
        assertThat(found.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("지난달에 시작한 기록도 번호로 찾는다 — 달이 바뀐 직후에 결과를 보러 와도 404 가 아니다")
    void 지난달_기록도_찾는다() {
        // given — 7월 31일 밤에 시작한 기록
        var 지난달_저장소 = new DynamoDbSyncRunRepository(client, properties,
                Clock.fixed(Instant.parse("2026-07-31T23:59:00Z"), ZoneOffset.UTC));
        var run = 지난달_저장소.start(SyncSource.LDAP, SyncTrigger.REBUILD).block();

        // when — 8월 14일에 찾는다
        var found = repository.findById(run.runId()).block();

        // then
        assertThat(found).isNotNull();
        assertThat(found.runId()).isEqualTo(run.runId());
    }

    @Test
    @DisplayName("없는 번호는 빈 결과다")
    void 없는_번호는_빈_결과다() {
        // given
        repository.start(SyncSource.LDAP, SyncTrigger.MANUAL).block();

        // when, then
        assertThat(repository.findById("없는-번호").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("아직 RUNNING 인 기록은 abandon 으로 사유와 함께 FAILED 로 닫힌다")
    void 아직_RUNNING인_기록은_abandon으로_닫힌다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();

        // when
        var closed = repository.abandon(run, "비정상 종료로 중단").block();

        // then — 반환값도, 다시 읽은 값도 닫혀 있어야 한다
        assertThat(closed.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(closed.message()).isEqualTo("비정상 종료로 중단");
        var found = repository.findById(run.runId()).block();
        assertThat(found.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(found.message()).isEqualTo("비정상 종료로 중단");
    }

    @Test
    @DisplayName("이미 끝난 기록은 abandon 이 손대지 않는다 — 조건이 깨져 빈 결과이고 저장된 결과는 그대로다")
    void 이미_끝난_기록은_abandon이_손대지_않는다() {
        // given — 앞 작업이 이미 SUCCEEDED 로 끝냈다
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
        var outcome = new SyncOutcome(SyncStatus.SUCCEEDED, 12, 3, 0, "20260814T030000-LDAP", null);
        repository.finish(run, outcome).block();

        // when — 반납과 기록 사이의 틈에 낡은 RUNNING 사본을 쥔 다음 작업이 닫으려 든다
        var result = repository.abandon(run, "비정상 종료로 중단").blockOptional();

        // then — 조건이 깨져 아무것도 바뀌지 않는다
        assertThat(result).isEmpty();
        var found = repository.findById(run.runId()).block();
        assertThat(found.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(found.writtenCount()).isEqualTo(12);
        assertThat(found.deletedCount()).isEqualTo(3);
        assertThat(found.snapshotId()).isEqualTo("20260814T030000-LDAP");
    }
}
