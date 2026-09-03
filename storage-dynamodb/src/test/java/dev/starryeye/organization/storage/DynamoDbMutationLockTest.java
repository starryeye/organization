package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock.LockPurpose;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분산 락의 계약 (설계 §4.3).
 *
 * <p>가장 중요한 것은 <b>토큰 조건</b>이다. 없으면 내 리스가 만료돼 남이 가져간 뒤에
 * 내가 반납하면서 남의 락을 풀어버린다 — 그 순간 두 인스턴스가 동시에 쓴다.
 */
class DynamoDbMutationLockTest extends DynamoDbTestSupport {

    /** 테스트가 시간을 손으로 옮겨 만료를 재현한다. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-01T00:00:00Z");

        void 앞으로(Duration amount) {
            now = now.plus(amount);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;
    private DynamoDbMutationLock 인스턴스1;
    private DynamoDbMutationLock 인스턴스2;

    @BeforeEach
    void 락을_준비한다() {
        clock = new MutableClock();
        properties.setLockTtl(Duration.ofSeconds(30));
        인스턴스1 = new DynamoDbMutationLock(client, properties, clock, "instance-1");
        인스턴스2 = new DynamoDbMutationLock(client, properties, clock, "instance-2");
    }

    @Test
    @DisplayName("한쪽이 쥐고 있으면 다른 인스턴스는 획득하지 못한다")
    void 쥐고_있으면_다른_쪽은_못_잡는다() {
        // given
        인스턴스1.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("반납하면 다른 인스턴스가 곧바로 획득한다")
    void 반납하면_다른_쪽이_잡는다() {
        // given
        var lease = 인스턴스1.acquire(LockPurpose.WRITE).block();

        // when
        인스턴스1.release(lease).block();

        // then
        assertThat(인스턴스2.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("리스가 만료되면 반납하지 않았어도 다른 인스턴스가 가져간다")
    void 만료되면_다른_쪽이_가져간다() {
        // given — 쥔 인스턴스가 죽어 반납하지 못한 상황이다
        인스턴스1.acquire(LockPurpose.WRITE).block();

        // when
        clock.앞으로(Duration.ofSeconds(31));

        // then
        assertThat(인스턴스2.acquire(LockPurpose.WRITE).block()).isNotNull();
    }

    @Test
    @DisplayName("남의 토큰으로 반납하면 남의 락이 풀리지 않는다")
    void 남의_락은_풀지_못한다() {
        // given — 1이 만료돼 2가 가져갔다. 1은 그 사실을 모른 채 반납하러 온다.
        var 낡은리스 = 인스턴스1.acquire(LockPurpose.WRITE).block();
        clock.앞으로(Duration.ofSeconds(31));
        인스턴스2.acquire(LockPurpose.WRITE).block();

        // when — 1이 자기 토큰으로 반납을 시도한다
        인스턴스1.release(낡은리스).block();

        // then — 2의 락은 그대로여야 한다. 풀렸다면 세 번째가 들어와 동시에 쓴다.
        assertThatThrownBy(() -> 인스턴스1.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("갱신하면 만료가 미뤄져 다른 인스턴스가 가져가지 못한다")
    void 갱신하면_만료가_미뤄진다() {
        // given
        var lease = 인스턴스1.acquire(LockPurpose.WRITE).block();

        // when — 만료 직전에 갱신한다
        clock.앞으로(Duration.ofSeconds(25));
        LockLease 갱신됨 = 인스턴스1.renew(lease).block();
        clock.앞으로(Duration.ofSeconds(20));

        // then — 원래 만료(30초)는 지났지만 갱신했으므로 아직 유효하다
        assertThat(갱신됨).isNotNull();
        assertThatThrownBy(() -> 인스턴스2.acquire(LockPurpose.WRITE).block())
                .isInstanceOf(LockUnavailableException.class);
    }

    @Test
    @DisplayName("리스를 잃은 뒤 갱신하려 하면 실패한다 — 재적재가 이걸 보고 멈춘다")
    void 잃은_리스는_갱신되지_않는다() {
        // given
        var 낡은리스 = 인스턴스1.acquire(LockPurpose.WRITE).block();
        clock.앞으로(Duration.ofSeconds(31));
        인스턴스2.acquire(LockPurpose.WRITE).block();

        // when, then
        assertThatThrownBy(() -> 인스턴스1.renew(낡은리스).block())
                .isInstanceOf(LockUnavailableException.class);
    }
}
