package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.port.DailyJobClaims;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeDailyJobClaims implements DailyJobClaims {

    private final Set<String> 잡힌_표지 = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Boolean> claim(String job, LocalDate day) {
        return Mono.fromSupplier(() -> 잡힌_표지.add(job + "#" + day));
    }
}
