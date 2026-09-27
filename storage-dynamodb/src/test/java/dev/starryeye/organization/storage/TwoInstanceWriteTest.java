package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.usecase.IncrementalSyncUseCase;
import dev.starryeye.organization.core.usecase.LockObserver;
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
}
