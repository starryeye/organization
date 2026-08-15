package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbTupleSnapshotRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private DynamoDbTupleSnapshotRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        properties.setSnapshotRetentionDays(7);
        repository = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금, ZoneOffset.UTC));
    }

    private static TupleSnapshot 스냅샷(String id, Instant at, Set<RelationTuple> tuples) {
        return new TupleSnapshot(id, at, SyncSource.LDAP, tuples);
    }

    private static Set<RelationTuple> 튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("저장한 스냅샷이 최신 스냅샷으로 조회된다")
    void 저장한_스냅샷이_최신으로_조회된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(3));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260814T030000-LDAP");
        assertThat(latest.source()).isEqualTo(SyncSource.LDAP);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("스냅샷이 하나도 없으면 최신 조회는 빈 결과를 준다")
    void 스냅샷이_없으면_빈_결과다() {
        // given, when
        var latest = repository.findLatest().block();

        // then
        assertThat(latest).isNull();
    }

    @Test
    @DisplayName("나중에 저장한 스냅샷이 최신 스냅샷을 덮어쓴다")
    void 나중_스냅샷이_최신이_된다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        repository.save(스냅샷("20260815T030000-LDAP", 지금.plusSeconds(86400), 튜플들(5))).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260815T030000-LDAP");
        assertThat(latest.tuples()).hasSize(5);
    }

    @Test
    @DisplayName("DynamoDB 배치 한계인 25건을 넘는 튜플도 나누어 저장되고 전부 복원된다")
    void 배치_한계를_넘는_튜플도_저장된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(120));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).hasSize(120);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("한글 조직코드가 담긴 튜플도 저장 후 그대로 복원된다")
    void 한글_조직코드_튜플도_복원된다() {
        // given
        var tuples = Set.of(RelationTuple.child("백엔드팀", "개발본부"),
                            RelationTuple.directMember("kim", "백엔드팀"));

        // when
        repository.save(스냅샷("20260814T030000-LDAP", 지금, tuples)).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).isEqualTo(tuples);
    }

    @Test
    @DisplayName("아이디로 과거 스냅샷을 직접 조회할 수 있다")
    void 아이디로_과거_스냅샷을_조회한다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var old = repository.findById("20260813T030000-LDAP").block();

        // then
        assertThat(old.tuples()).hasSize(2);
    }

    @Test
    @DisplayName("최근 스냅샷 목록은 최신순으로 나온다")
    void 최근_목록은_최신순이다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var metas = repository.listRecent(7).collectList().block();

        // then
        assertThat(metas).extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP", "20260813T030000-LDAP");
        assertThat(metas.get(0).tupleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("리셋하면 모든 스냅샷과 최신 포인터가 사라진다")
    void 리셋하면_전부_사라진다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        repository.reset().block();

        // then
        assertThat(repository.findLatest().block()).isNull();
        assertThat(repository.listRecent(30).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("보존 기간이 지난 스냅샷만 정리되고 최근 것은 남는다")
    void 만료된_스냅샷만_정리된다() {
        // given — 보존 7일. 10일 전 것은 만료, 오늘 것은 유효
        var 만료된시각 = 지금.minusSeconds(10 * 86400);
        var 만료스냅샷 = new TupleSnapshot("20260804T030000-LDAP", 만료된시각, SyncSource.LDAP, 튜플들(2));
        var 유효스냅샷 = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(4));

        repository.saveWithCreatedAt(만료스냅샷).block();
        repository.save(유효스냅샷).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }
}
