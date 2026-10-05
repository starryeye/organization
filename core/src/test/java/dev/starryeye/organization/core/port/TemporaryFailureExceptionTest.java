package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryFailureExceptionTest {

    @Test
    @DisplayName("락을 쥔 쪽이 SCIM 쓰기면 2초, 재적재·전체 동기화면 60초, 모르면 쓰기 경합으로 보고 2초다(설계 2026-10-05 §3.1)")
    void 락을_쥔_쪽의_용도로_기다릴_시간을_정한다() {
        // when, then
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.WRITE).retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.REBUILD).retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(LockUnavailableException.잡혀_있다(MutationLock.LockPurpose.SYNC).retryAfter()).isEqualTo(Duration.ofSeconds(60));
        assertThat(LockUnavailableException.잡혀_있다(null).retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("락 저장소 오류·리스 상실(용도를 모르는 락 실패)과 쓰기 차단기는 일시 장애이고 10초다")
    void 그_밖의_락_실패와_차단기는_10초다() {
        // given
        var 리스상실 = new LockUnavailableException("변경 락 리스를 잃었습니다");
        var 저장소오류 = new LockUnavailableException("변경 락을 얻는 중 오류가 발생했습니다", new RuntimeException("dynamo"));
        var 차단기 = new TupleWriteAbortedException("멈췄다", TupleWriteResult.empty());

        // when, then
        assertThat(List.<TemporaryFailureException>of(리스상실, 저장소오류, 차단기))
                .allSatisfy(e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(10)));
    }
}
