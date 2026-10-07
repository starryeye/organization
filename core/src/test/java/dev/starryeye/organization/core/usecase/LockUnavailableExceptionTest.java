package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 쥔 쪽의 용도가 획득 재시도를 정한다(설계 2026-10-07 §3.3). */
class LockUnavailableExceptionTest {

    @ParameterizedTest
    @EnumSource(value = LockPurpose.class, names = {"REBUILD", "SYNC"})
    @DisplayName("재적재·동기화가 쥐었으면 긴 작업이고 60초다")
    void 재적재_동기화는_긴_작업이다(LockPurpose 용도) {
        // when
        LockUnavailableException 실패 = LockUnavailableException.잡혀_있다(용도);

        // then
        assertThat(실패.긴_작업이_쥐었다()).isTrue();
        assertThat(실패.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("쓰기가 쥐었거나 용도를 모르면 긴 작업이 아니다 — 쓰기 경합으로 보고 다시 시도한다")
    void 쓰기와_모름은_긴_작업이_아니다() {
        // when, then
        assertThat(LockUnavailableException.잡혀_있다(LockPurpose.WRITE).긴_작업이_쥐었다()).isFalse();
        assertThat(LockUnavailableException.잡혀_있다(null).긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("리스를 잃었다").긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("저장소 오류", new RuntimeException()).긴_작업이_쥐었다()).isFalse();
        assertThat(new LockUnavailableException("못 잡았다", Duration.ofSeconds(60)).긴_작업이_쥐었다())
                .as("대기 시간이 아니라 용도로 가른다").isFalse();
    }
}
