package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.usecase.IncrementalSyncResult;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
import dev.starryeye.organization.core.usecase.LockUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * app-scim 두 대를 흉내 낸다 (SCIM 쓰기 락 설계 §7 (c)). 유스케이스 둘이 락 객체를 따로, 테이블은 같이 쓴다 — 두 인스턴스가
 * 같은 직원을 동시에 바꿔도 락 안에서 읽고 계산하므로 서로의 변경을 지우지 않는다. 분산 락 자체의 계약은
 * {@link DynamoDbMutationLockTest} 가 본다.
 */
class TwoInstanceWriteTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository 저장소;
    private IncrementalSyncUseCase 인스턴스1;
    private IncrementalSyncUseCase 인스턴스2;

    @BeforeEach
    void 두_인스턴스를_띄운다() {
        properties.setLockTtl(Duration.ofSeconds(30));
        Clock clock = Clock.systemUTC();
        저장소 = new DynamoDbDirectoryStateRepository(client, properties, clock);
        // OpenFGA 가짜는 두 인스턴스가 함께 쓴다 — 같은 store 를 흉내 낸다. 쓰기는 락 안이라 한 번에 하나다
        FakeTupleWriter writer = new FakeTupleWriter();
        FakeTupleChecker checker = new FakeTupleChecker();
        인스턴스1 = new IncrementalSyncUseCase(new DynamoDbDirectoryStateRepository(client, properties, clock), writer, checker,
                new DynamoDbMutationLock(client, properties, clock, "instance-1"),
                Duration.ofSeconds(30), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        인스턴스2 = new IncrementalSyncUseCase(new DynamoDbDirectoryStateRepository(client, properties, clock), writer, checker,
                new DynamoDbMutationLock(client, properties, clock, "instance-2"),
                Duration.ofSeconds(30), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
    }

    @Test
    @DisplayName("두 인스턴스가 같은 직원의 서로 다른 속성을 동시에 바꿔도 모든 변경이 남는다")
    void 동시_변경이_모두_남는다() {
        // given
        인스턴스1.createUser(new DirectoryUser("kim", "ext-kim", "kim", "김철수", "kim@example.com", true))
                .block(Duration.ofSeconds(30));
        List<UnaryOperator<DirectoryUser>> 변경 = List.of(
                u -> u.withDisplayName("표시명 바뀜"),
                u -> u.withEmail("new@example.com"),
                u -> u.withExternalId("ext-new"),
                u -> u.withName(u.name().withGivenName("길동")),
                u -> u.withName(u.name().withFamilyName("홍")),
                u -> u.withName(u.name().withMiddleName("중")),
                u -> u.withName(u.name().withHonorificPrefix("Mr.")),
                u -> u.withName(u.name().withHonorificSuffix("Jr.")));

        // when — 두 인스턴스가 번갈아, 한꺼번에 보낸다. 락을 못 잡은 쪽은 기다렸다 다시 잡는다(대기 한도 30초)
        Flux.range(0, 변경.size())
                .flatMap(i -> (i % 2 == 0 ? 인스턴스1 : 인스턴스2).changeUser("kim", 변경.get(i))
                        .subscribeOn(Schedulers.boundedElastic()), 변경.size())
                .blockLast(Duration.ofMinutes(2));

        // then
        DirectoryUser kim = 저장소.findUser("kim").block();
        assertThat(kim.displayName()).isEqualTo("표시명 바뀜");
        assertThat(kim.email()).isEqualTo("new@example.com");
        assertThat(kim.externalId()).isEqualTo("ext-new");
        assertThat(kim.name().givenName()).isEqualTo("길동");
        assertThat(kim.name().familyName()).isEqualTo("홍");
        assertThat(kim.name().middleName()).isEqualTo("중");
        assertThat(kim.name().honorificPrefix()).isEqualTo("Mr.");
        assertThat(kim.name().honorificSuffix()).isEqualTo("Jr.");
    }

    @Test
    @DisplayName("두 인스턴스가 서로 다른 직원 40명을 한꺼번에 바꿔도 실패 없이 모두 남는다 — 서버마다 줄, 서버끼리 백오프(설계 2026-10-07 §3)")
    void 두_인스턴스의_동시_쓰기가_모두_남는다() {
        // given
        Flux.range(0, 40)
                .concatMap(i -> 인스턴스1.createUser(new DirectoryUser("u" + i, "ext-u" + i, "user" + i, "직원 " + i, null, true)))
                .blockLast(Duration.ofMinutes(1));

        // when — 짝수는 인스턴스1, 홀수는 인스턴스2 로 한꺼번에 보낸다
        List<IncrementalSyncResult> 결과 = Flux.range(0, 40)
                .flatMap(i -> (i % 2 == 0 ? 인스턴스1 : 인스턴스2).changeUser("u" + i, u -> u.withDisplayName("바뀐 " + i))
                        .subscribeOn(Schedulers.boundedElastic()), 40)
                .collectList()
                .block(Duration.ofMinutes(2));

        // then
        assertThat(결과).hasSize(40).allSatisfy(r -> assertThat(r.fullyApplied()).isTrue());
        for (int i = 0; i < 40; i++) {
            assertThat(저장소.findUser("u" + i).block().displayName()).isEqualTo("바뀐 " + i);
        }
    }

    @Test
    @DisplayName("다른 인스턴스의 재적재가 락을 쥐고 있으면 쓰기는 한 번 시도하고 바로 503(60초)이다 — 한도 3초를 기다리지 않는다(설계 2026-10-07 §3.3)")
    void 재적재_중이면_바로_503이다() {
        // given — 인스턴스1 이 재적재로 락을 쥔다. 쓰는 쪽의 한도는 운영 기본값 3초다
        Clock clock = Clock.systemUTC();
        DynamoDbMutationLock 인스턴스1의_락 = new DynamoDbMutationLock(client, properties, clock, "instance-1");
        var 재적재_리스 = 인스턴스1의_락.acquire(MutationLock.LockPurpose.REBUILD).block(Duration.ofSeconds(10));
        IncrementalSyncUseCase 운영_한도_인스턴스 = new IncrementalSyncUseCase(
                new DynamoDbDirectoryStateRepository(client, properties, clock), new FakeTupleWriter(), new FakeTupleChecker(),
                new DynamoDbMutationLock(client, properties, clock, "instance-2"),
                Duration.ofSeconds(3), IncrementalSyncUseCase.DriftObserver.NOOP, LockObserver.NOOP);
        long 시작 = System.nanoTime();

        // when
        Throwable 실패 = catchThrowable(() -> 운영_한도_인스턴스
                .upsertUser(new DirectoryUser("kim", "ext-kim", "kim", "김철수", null, true))
                .block(Duration.ofSeconds(10)));
        Duration 걸린_시간 = Duration.ofNanos(System.nanoTime() - 시작);
        인스턴스1의_락.release(재적재_리스).block(Duration.ofSeconds(10));

        // then
        assertThat(실패).isInstanceOfSatisfying(LockUnavailableException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
        assertThat(걸린_시간).as("재시도 없이 끝난다").isLessThan(Duration.ofSeconds(1));
    }
}
