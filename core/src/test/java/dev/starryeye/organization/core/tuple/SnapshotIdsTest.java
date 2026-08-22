package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.SyncSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotIdsTest {

    @Test
    @DisplayName("스냅샷 아이디는 UTC 기준 시각과 소스를 담아 사전순 정렬이 시간순과 일치한다")
    void 스냅샷_아이디는_시각과_소스를_담는다() {
        // given
        var at = Instant.parse("2026-08-14T03:00:00Z");

        // when
        var id = SnapshotIds.generate(at, SyncSource.LDAP);

        // then
        assertThat(id).isEqualTo("20260814T030000000-LDAP");
    }

    @Test
    @DisplayName("시각이 뒤인 스냅샷 아이디가 사전순으로도 뒤에 온다")
    void 아이디_사전순은_시간순과_일치한다() {
        // given
        var 이른시각 = Instant.parse("2026-08-14T03:00:00Z");
        var 늦은시각 = Instant.parse("2026-08-15T03:00:00Z");

        // when
        var 이른아이디 = SnapshotIds.generate(이른시각, SyncSource.LDAP);
        var 늦은아이디 = SnapshotIds.generate(늦은시각, SyncSource.LDAP);

        // then
        assertThat(이른아이디).isLessThan(늦은아이디);
    }

    @Test
    @DisplayName("같은 초 안의 두 실행도 서로 다른 아이디를 받는다")
    void 같은_초라도_아이디가_갈린다() {
        // given — 초 단위였을 때 두 실행이 한 스냅샷 파티션을 공유하던 상황이다.
        // 합쳐진 튜플 집합은 어느 실행의 결과도 아니라, 다음 diff 의 기준선을 통째로 망친다.
        var 앞선실행 = Instant.parse("2026-08-14T03:00:00.120Z");
        var 뒤이은실행 = Instant.parse("2026-08-14T03:00:00.870Z");

        // when
        var 앞선아이디 = SnapshotIds.generate(앞선실행, SyncSource.LDAP);
        var 뒤이은아이디 = SnapshotIds.generate(뒤이은실행, SyncSource.LDAP);

        // then
        assertThat(앞선아이디).isNotEqualTo(뒤이은아이디);
        assertThat(앞선아이디).isLessThan(뒤이은아이디);
    }
}
