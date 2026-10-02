package dev.starryeye.organization.core.usecase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.fake.FakeMutationLock;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeTupleChecker;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * SCIM 쓰기가 락 리스를 지킨다 (설계 2026-10-02 §3, 점검 C6·M8·S3).
 */
class IncrementalSyncLeaseTest {

    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeTupleChecker checker;
    private FakeMutationLock lock;
    private final List<String> 상실 = new CopyOnWriteArrayList<>();
    private LockObserver observer;
    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;

    @BeforeEach
    void 준비한다() {
        logger = (Logger) LoggerFactory.getLogger(IncrementalSyncUseCase.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        checker = new FakeTupleChecker();
        lock = new FakeMutationLock();
        observer = new LockObserver() {
            @Override
            public void acquireFinished(Duration waited, boolean contended) {
            }

            @Override
            public void leaseLost(String reason) {
                상실.add(reason);
            }
        };
        state.saveUser(new DirectoryUser("kim", "emp-kim", "kim", "kim", "kim@example.com", true)).block();
        state.saveGroup(new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of())).block();
    }

    @AfterEach
    void 원복한다() {
        logger.detachAppender(로그);
        로그.stop();
    }

    private List<ILoggingEvent> 요청이_떠난_뒤_경고() {
        return 로그.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().startsWith("요청이 떠난 뒤"))
                .toList();
    }

    private IncrementalSyncUseCase 유스케이스(Duration 갱신주기) {
        return new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO, 갱신주기,
                IncrementalSyncUseCase.DriftObserver.NOOP, observer);
    }

    private static DirectoryGroup kim이_든_DEV001() {
        return new DirectoryGroup("DEV001", "DEV001", "개발본부", Set.of(MemberRef.user("kim")));
    }

    @Test
    @DisplayName("갱신 주기보다 오래 걸리는 쓰기는 도중에 리스를 갱신하고 끝까지 간다")
    void 오래_걸리는_쓰기는_리스를_갱신한다() {
        // given — Check 가 300ms 걸린다
        checker.delayBy(tuple -> Duration.ofMillis(300));

        // when
        var result = 유스케이스(Duration.ofMillis(50)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5));

        // then — 하트비트 갱신 + 쓰기 직전 확인 + 커밋 직전 확인
        assertThat(result.fullyApplied()).isTrue();
        assertThat(lock.renewed.get()).isGreaterThanOrEqualTo(4);
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
        assertThat(lock.released).hasValue(1);
    }

    @Test
    @DisplayName("도중에 리스 갱신이 실패하면 멈추고 503 이며, OpenFGA 에도 DynamoDB 에도 쓰지 않는다")
    void 갱신이_실패하면_멈춘다() {
        // given
        checker.delayBy(tuple -> Duration.ofMillis(300));
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofMillis(50)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class)
                .hasMessageContaining("리스");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(state.groups.get("DEV001").members()).isEmpty();
        assertThat(상실).contains("쓰기 도중 리스 상실");
    }

    @Test
    @DisplayName("바뀐 튜플이 없어도 커밋 직전에 리스를 확인한다 — 확인이 실패하면 저장하지 않는다(점검 M8)")
    void 빈_델타도_커밋_직전에_확인한다() {
        // given — 소속 조직이 없는 kim 의 메일만 바꾼다(튜플 변화 없음). 그사이 리스를 잃었다
        lock.failRenew = true;

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofSeconds(10))
                .changeUser("kim", user -> user.withEmail("new@example.com")).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(state.users.get("kim").email()).as("남이 저장했을지 모르는 값을 덮지 않는다").isEqualTo("kim@example.com");
        assertThat(상실).contains("커밋 직전 리스 재확인 실패");
    }

    @Test
    @DisplayName("OpenFGA 에 쓴 뒤 커밋 직전 확인이 실패하면 DynamoDB 에 저장하지 않는다")
    void 쓴_뒤에도_커밋_직전에_확인한다() {
        // given — 쓰기 직전 확인은 통과하고, 쓰는 사이 리스를 잃는다
        writer.onApply(() -> lock.failRenew = true);

        // when, then
        assertThatThrownBy(() -> 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV001").members()).isEmpty();
    }

    @Test
    @DisplayName("요청이 끊겨도 커밋까지 하고 그다음 반납한다 — 반쯤 반영된 채 락이 풀리지 않는다(점검 S3)")
    void 요청이_끊겨도_커밋까지_하고_반납한다() throws InterruptedException {
        // given — OpenFGA 쓰기가 300ms 걸린다. 반납이 불릴 때마다 그 순간 커밋이 이미 있었는지 적어 둔다
        writer.delay = Duration.ofMillis(300);
        List<Boolean> 반납할_때_커밋돼_있었나 = new CopyOnWriteArrayList<>();
        lock = new FakeMutationLock() {
            @Override
            public Mono<Void> release(LockLease lease) {
                return Mono.defer(() -> {
                    반납할_때_커밋돼_있었나.add(state.groups.get("DEV001").members().contains(MemberRef.user("kim")));
                    return super.release(lease);
                });
            }
        };

        // when — 쓰는 도중 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then — 반납은 커밋 뒤에 일어난다
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.released.get() == 1);
        assertThat(반납할_때_커밋돼_있었나).as("반납은 한 번, 커밋 뒤에").containsExactly(true);
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("락을 잡는 도중에 끊겨도 리스가 새지 않는다 — 잡은 락으로 일을 마치고 반납한다")
    void 락을_잡는_도중_끊겨도_리스가_새지_않는다() throws InterruptedException {
        // given — 저장소에서는 이미 잡혔는데 응답이 200ms 늦게 온다
        lock = new FakeMutationLock() {
            @Override
            public reactor.core.publisher.Mono<dev.starryeye.organization.core.port.LockLease> acquire(LockPurpose purpose) {
                return super.acquire(purpose).delayElement(Duration.ofMillis(200));
            }
        };

        // when — 응답을 기다리는 사이 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> lock.released.get() == 1);
        assertThat(lock.isHeld()).isFalse();
        assertThat(state.groups.get("DEV001").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("요청이 끊긴 뒤 실패하면 경고를 남긴다 — 응답이 없으니 로그로만 보인다")
    void 요청이_끊긴_뒤_실패하면_경고를_남긴다() throws InterruptedException {
        // given — OpenFGA 쓰기가 300ms 걸리고, 쓰는 사이 리스를 잃어 커밋 직전 확인이 실패한다
        writer.delay = Duration.ofMillis(300);
        writer.onApply(() -> lock.failRenew = true);

        // when — 쓰는 도중 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> !요청이_떠난_뒤_경고().isEmpty());
        assertThat(요청이_떠난_뒤_경고()).singleElement()
                .satisfies(event -> assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo(LockUnavailableException.class.getName()));
        assertThat(state.groups.get("DEV001").members()).isEmpty();
    }

    @Test
    @DisplayName("요청이 끊긴 뒤 일부만 반영되면 경고를 남긴다")
    void 요청이_끊긴_뒤_부분_반영이면_경고를_남긴다() throws InterruptedException {
        // given — OpenFGA 쓰기가 300ms 걸리고 튜플이 하나도 써지지 않는다
        writer.delay = Duration.ofMillis(300);
        writer.failFor(tuple -> true);

        // when — 쓰는 도중 IdP 가 연결을 끊는다
        Disposable 요청 = 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).subscribe();
        Thread.sleep(50);
        요청.dispose();

        // then
        await().atMost(Duration.ofSeconds(5)).until(() -> !요청이_떠난_뒤_경고().isEmpty());
        assertThat(요청이_떠난_뒤_경고()).singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("부분 반영", "1개"));
    }

    @Test
    @DisplayName("요청이 남아 있으면 실패해도 여기서 경고하지 않는다 — 응답과 ScimRouter 가 알린다")
    void 요청이_남아_있으면_경고하지_않는다() {
        // given — 쓰는 사이 리스를 잃어 커밋 직전 확인이 실패한다
        writer.onApply(() -> lock.failRenew = true);

        // when
        assertThatThrownBy(() -> 유스케이스(Duration.ofSeconds(10)).upsertGroup(kim이_든_DEV001()).block(Duration.ofSeconds(5)))
                .isInstanceOf(LockUnavailableException.class);

        // then
        assertThat(요청이_떠난_뒤_경고()).isEmpty();
    }
}
