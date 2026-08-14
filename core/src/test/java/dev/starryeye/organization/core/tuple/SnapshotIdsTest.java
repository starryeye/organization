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
        assertThat(id).isEqualTo("20260814T030000-LDAP");
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
}
