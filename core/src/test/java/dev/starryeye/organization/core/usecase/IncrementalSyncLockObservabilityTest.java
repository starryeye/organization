package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 락에서 일어난 일이 지표로 나간다 (설계 §7).
 *
 * <p>리스를 잃는 세 갈래 — 재적재 중 상실, 반납 실패, 획득 도중 취소 — 는 <b>어느 것도 응답에
 * 나타나지 않는다</b>. 세지 않으면 로그를 사람이 읽을 때까지 아무도 모르고, 그동안 락은 TTL 이
 * 지날 때까지 묶여 있거나 두 인스턴스가 동시에 쓰고 있다.
 */
class IncrementalSyncLockObservabilityTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private 기록하는_관찰자 관찰자;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        관찰자 = new 기록하는_관찰자();
    }

    /** 관찰만 하는 가짜. 카운터가 아니라 호출을 그대로 담아 무엇이 왜 올랐는지 보게 한다. */
    private static final class 기록하는_관찰자 implements LockObserver {
        final List<Duration> 대기 = new ArrayList<>();
        final List<Boolean> 경합 = new ArrayList<>();
        final List<String> 리스상실 = new ArrayList<>();

        @Override
        public void acquireFinished(Duration waited, boolean contended) {
            대기.add(waited);
            경합.add(contended);
        }

        @Override
        public void leaseLost(String reason) {
            리스상실.add(reason);
        }
    }

    private IncrementalSyncUseCase 유스케이스(MutationLock lock, Duration 대기한도) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, 대기한도,
                IncrementalSyncUseCase.DriftObserver.NOOP, 관찰자);
    }

    private static DirectoryUser 직원(String id, boolean active) {
        return new DirectoryUser(id, "uid=" + id, id, id + " 님", id + "@example.com", active);
    }

    private void 지울_튜플이_있게_심는다() {
        state.users.put("kim", 직원("kim", true));
        state.groups.put("DEV001", new DirectoryGroup("DEV001", "cn=DEV001", "개발본부",
                Set.of(MemberRef.user("kim"))));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV001"));
    }

    @Test
    @DisplayName("경합 없이 잡으면 대기 시간만 남고 경합으로 세지 않는다")
    void 경합_없는_획득() {
        // given
        state.users.put("kim", 직원("kim", true));

        // when
        유스케이스(new FakeMutationLock(), Duration.ZERO).upsertUser(직원("kim", false)).block();

        // then
        assertThat(관찰자.대기).hasSize(1);
        assertThat(관찰자.경합).containsExactly(false);
        assertThat(관찰자.리스상실).isEmpty();
    }

    @Test
    @DisplayName("한 번이라도 밀렸으면 경합으로 센다")
    void 밀리면_경합이다() {
        // given — 첫 시도는 남이 쥐고 있고 두 번째에 풀린다. 마지막 시도만 보면 성공이라
        // 경합이 없던 것처럼 보인다 — 재시도 위에서 세야 하는 이유다.
        AtomicInteger 시도 = new AtomicInteger();
        MutationLock 한번_밀리는_락 = new MutationLock() {
            @Override
            public Mono<LockLease> acquire(LockPurpose purpose) {
                return Mono.defer(() -> 시도.getAndIncrement() == 0
                        ? Mono.error(new LockUnavailableException("남이 쥐고 있다(테스트)"))
                        : Mono.just(new LockLease(UUID.randomUUID().toString(), Instant.now().plusSeconds(30))));
            }

            @Override
            public Mono<Void> release(LockLease lease) {
                return Mono.empty();
            }

            @Override
            public Mono<LockLease> renew(LockLease lease) {
                return Mono.just(lease);
            }
        };
        state.users.put("kim", 직원("kim", true));

        // when — 200ms 간격으로 두 번 재시도할 수 있는 예산
        유스케이스(한번_밀리는_락, Duration.ofMillis(400)).upsertUser(직원("kim", false)).block();

        // then
        assertThat(시도).hasValue(2);
        assertThat(관찰자.경합).containsExactly(true);
    }

    @Test
    @DisplayName("락 획득이 저장소 장애로 실패하면 503 으로 나가되 경합으로 세지는 않는다")
    void 저장소_장애도_503이다() {
        // given — DynamoDB 부분 장애. 그대로 흘리면 ScimRouter 기본 분기가 500 을 내고,
        // IdP 는 500 을 영구 실패로 읽어 프로비저닝을 버린다 — 재시도해야 할 바로 그 순간에.
        RuntimeException 저장소장애 = new RuntimeException("DynamoDB 가 응답하지 않는다(테스트)");
        MutationLock 고장난_락 = new MutationLock() {
            @Override
            public Mono<LockLease> acquire(LockPurpose purpose) {
                return Mono.error(저장소장애);
            }

            @Override
            public Mono<Void> release(LockLease lease) {
                return Mono.empty();
            }

            @Override
            public Mono<LockLease> renew(LockLease lease) {
                return Mono.just(lease);
            }
        };

        // when, then
        assertThatThrownBy(() -> 유스케이스(고장난_락, Duration.ZERO).upsertUser(직원("kim", false)).block())
                .isInstanceOf(LockUnavailableException.class)
                .as("원인을 끊으면 DynamoDB 가 무엇을 던졌는지가 로그에서 사라진다")
                .hasCause(저장소장애);

        // 503 으로 나가는 것과 "경합했다" 는 별개다. 이 실패에는 밀린 순간이 한 번도 없었다 —
        // 여기서 경합을 세면 저장소 장애가 scim.lock.contended 를 밀어 올리고, README 가
        // 그 카운터를 "전역 락 결정을 다시 볼 신호"(설계 §4.1)라고 적어 두었으므로 장애
        // 대응 중인 운영자를 정확히 틀린 방향으로 보낸다.
        assertThat(관찰자.경합)
                .as("경합이 아닌 실패를 경합으로 세면 지표가 장애 중에 거짓말을 한다")
                .containsExactly(false);
        assertThat(관찰자.대기).as("기다린 시간 자체는 남는다").hasSize(1);
    }

    @Test
    @DisplayName("쓰기 직전 리스 재확인이 실패하면 리스 상실로 센다")
    void 재확인_실패는_리스_상실이다() {
        // given
        지울_튜플이_있게_심는다();
        var lock = new FakeMutationLock();
        lock.failRenew = true;

        // when
        assertThatThrownBy(() -> 유스케이스(lock, Duration.ZERO).upsertUser(직원("kim", false)).block())
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(관찰자.리스상실).hasSize(1);
    }

    @Test
    @DisplayName("반납이 실패하면 응답은 성공이지만 리스 상실로 센다")
    void 반납_실패는_지표로만_보인다() {
        // given — release(...).subscribe() 는 구독자가 없어 에러를 버린다.
        // 재시도하지 않으므로 TTL 이 지날 때까지 이 인스턴스도 남도 락을 잡지 못한다.
        state.users.put("kim", 직원("kim", true));
        var lock = new FakeMutationLock();
        lock.failRelease = true;

        // when — 일은 이미 끝났으므로 요청 자체는 성공한다
        var result = 유스케이스(lock, Duration.ZERO).upsertUser(직원("kim", false)).block();

        // then
        assertThat(result).isNotNull();
        assertThat(관찰자.리스상실)
                .as("응답에 아무 흔적이 없는 사건이라 지표가 유일한 신호다")
                .hasSize(1);
    }
}
