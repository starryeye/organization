package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.DailyJobClaims;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 표지를 메모리에 둔다. 조건은 {@code DynamoDbDailyJobClaims} 와 같다 — 잡기는 없거나 임대가 끝난 CLAIMED 만, 완료·해제는 쥔 owner 만.
 * 임대 판정은 {@code 지금} 이 주는 시각으로 한다.
 */
public class FakeDailyJobClaims implements DailyJobClaims {

    private record 표지(String state, String owner, Instant leaseUntil) {
    }

    private final Map<String, 표지> 표지들 = new ConcurrentHashMap<>();
    private final Supplier<Instant> 지금;

    /** 지금이 {@link Instant#EPOCH} 에 고정이라 임대가 끝나지 않는다. */
    public FakeDailyJobClaims() {
        this(() -> Instant.EPOCH);
    }

    public FakeDailyJobClaims(Supplier<Instant> 지금) {
        this.지금 = 지금;
    }

    @Override
    public Mono<Boolean> claim(String job, LocalDate day, String owner, Instant leaseUntil) {
        return Mono.fromSupplier(() -> {
            AtomicBoolean 잡았다 = new AtomicBoolean();
            표지들.compute(키(job, day), (key, 있던) -> {
                boolean 비었다 = 있던 == null
                        || ("CLAIMED".equals(있던.state()) && 있던.leaseUntil().isBefore(지금.get()));
                if (!비었다) {
                    return 있던;
                }
                잡았다.set(true);
                return new 표지("CLAIMED", owner, leaseUntil);
            });
            return 잡았다.get();
        });
    }

    @Override
    public Mono<Void> complete(String job, LocalDate day, String owner) {
        return Mono.fromRunnable(() -> 표지들.computeIfPresent(키(job, day), (key, 있던) ->
                있던.owner().equals(owner) ? new 표지("DONE", 있던.owner(), 있던.leaseUntil()) : 있던));
    }

    @Override
    public Mono<Void> release(String job, LocalDate day, String owner) {
        return Mono.fromRunnable(() -> 표지들.computeIfPresent(키(job, day), (key, 있던) ->
                있던.owner().equals(owner) ? null : 있던));
    }

    /** 그날 표지의 상태 — {@code "CLAIMED"}·{@code "DONE"}, 없으면 빈 값. */
    public Optional<String> 상태(String job, LocalDate day) {
        return Optional.ofNullable(표지들.get(키(job, day))).map(표지::state);
    }

    private static String 키(String job, LocalDate day) {
        return job + "#" + day;
    }
}
