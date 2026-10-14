package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 유일 값(직원 userName, 조직 externalId) 표지판의 판단(설계 2026-10-14 §4). <b>락 안에서만 부른다.</b> 차지하기, 부딪쳤을 때의 판단, 끝난 뒤의
 * 정리를 한 곳에 둔다 — 두 종류가 같은 규칙을 쓴다. 주인 확인은 본 테이블 강한 읽기다 — GSI 를 보지 않는다.
 */
@Slf4j
final class UniqueMarkers {

    /** 다른 쓰기가 같은 값을 차지하고 아직 저장하지 않았을 수 있을 때의 재시도 대기 — 쓰기 락 경합(2초)과 같다. */
    static final Duration 쓰는_중_대기 = Duration.ofSeconds(2);

    private final DirectoryStateRepository state;
    /** 이만큼 지난 표지판은 주인이 그 값을 쥐지 않으면 넘겨받는다 — 락 TTL 이다(§4.1). */
    private final Duration 넘겨받기_기준;

    UniqueMarkers(DirectoryStateRepository state, Duration 넘겨받기_기준) {
        this.state = state;
        this.넘겨받기_기준 = 넘겨받기_기준;
    }

    /**
     * {@code 주인} 이 {@code 값} 을 쓰도록 표지판을 차지한다(§4.1). 값이 없거나 비면 아무것도 하지 않는다. 남은 표지판의 주인이 그 값을 쥐고 있으면
     * {@link DirectoryConflictException}(409)이고 — 주인이 나여도 그렇다(생성에는 자기 자신이 없다) — 주인이 나면 차지한 것으로 본다. 락 TTL 이
     * 지난 표지판은 넘겨받고, 그 밖이면 {@link TemporaryFailureException}(503)이다. 키가 한도를 넘으면 {@link UniqueValueTooLongException}(400)이다.
     */
    Mono<Void> 차지한다(UniqueKind 종류, String 값, String 주인) {
        if (값 == null || 값.isBlank()) {
            return Mono.empty();
        }
        String 키 = 종류.key(값);
        if (!UniqueKind.fits(키)) {
            return Mono.error(new UniqueValueTooLongException(
                    "%s 이(가) %d바이트를 넘습니다".formatted(이름(종류), UniqueKind.MAX_KEY_BYTES)));
        }
        return Mono.defer(() -> {
            Instant 지금 = 지금();
            return state.claimUnique(종류, 키, 주인, 지금)
                    .flatMap(있던것 -> 판단한다(종류, 값, 키, 주인, 있던것, 지금));
        });
    }

    private Mono<Void> 판단한다(UniqueKind 종류, String 값, String 키, String 주인, UniqueClaim 있던것, Instant 지금) {
        return 쥐고_있다(종류, 있던것.ownerId(), 키).flatMap(쥐었다 -> {
            if (쥐었다) {
                return Mono.<Void>error(new DirectoryConflictException(충돌_문구(종류, 값, 있던것.ownerId())));
            }
            if (있던것.ownerId().equals(주인)) {
                return Mono.<Void>empty();
            }
            if (!있던것.claimedAt().plus(넘겨받기_기준).isAfter(지금)) {
                return state.takeOverUnique(종류, 키, 주인, 있던것.ownerId(), 지금)
                        .flatMap(넘겨받았다 -> 넘겨받았다 ? Mono.<Void>empty() : Mono.<Void>error(쓰는_중(종류)));
            }
            return Mono.<Void>error(쓰는_중(종류));
        });
    }

    /** 주인을 다시 읽지 않고 놓는다 — 깨끗이 끝나 결과를 아는 경우(§4.5). 놓기는 조건부라 남의 것을 지우지 않는다. 실패해도 경고만 남긴다. */
    Mono<Void> 놓는다(UniqueKind 종류, String 값, String 주인) {
        if (값 == null || 값.isBlank()) {
            return Mono.empty();
        }
        return Mono.defer(() -> state.releaseUnique(종류, 종류.key(값), 주인))
                .onErrorResume(error -> {
                    log.warn("{} 표지판을 놓지 못했다 — 주인이 쥐지 않으면 {} 뒤 넘겨받는다: 주인={}", 이름(종류), 넘겨받기_기준, 주인, error);
                    return Mono.empty();
                });
    }

    /**
     * 주인이 지금 쥐고 있지 않은 값만 놓는다 — 깨끗이 끝나지 않아(오류·일부 반영) 무엇이 저장됐는지 모르는 경우(§4.4). 주인을 한 번 강하게 읽는다.
     * 실패해도 경고만 남긴다 — 남은 표지판은 나중에 넘겨받는다.
     */
    Mono<Void> 쥐지_않은_것을_놓는다(UniqueKind 종류, String 주인, Collection<String> 값들) {
        List<String> 볼것 = 값들.stream().filter(값 -> 값 != null && !값.isBlank()).distinct().toList();
        if (볼것.isEmpty()) {
            return Mono.empty();
        }
        return 지금_값(종류, 주인)
                .flatMap(쥔값 -> Flux.fromIterable(볼것)
                        .filter(값 -> 쥔값.map(v -> !종류.key(v).equals(종류.key(값))).orElse(true))
                        .concatMap(값 -> 놓는다(종류, 값, 주인))
                        .then())
                .onErrorResume(error -> {
                    log.warn("{} 표지판을 정리하려고 주인을 읽지 못했다 — 남은 표지판은 {} 뒤 넘겨받는다: 주인={}", 이름(종류), 넘겨받기_기준, 주인, error);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> 쥐고_있다(UniqueKind 종류, String 주인, String 키) {
        return 지금_값(종류, 주인).map(쥔값 -> 쥔값.map(v -> 종류.key(v).equals(키)).orElse(false));
    }

    /** 주인이 지금 쥔 값 — 본 테이블 강한 읽기다. 주인이 없거나 값이 비면 빈 값이다. */
    private Mono<Optional<String>> 지금_값(UniqueKind 종류, String 주인) {
        Mono<String> 값 = switch (종류) {
            case USER_NAME -> state.findUser(주인).mapNotNull(DirectoryUser::userName);
            case GROUP_EXTERNAL_ID -> state.findGroupHeader(주인).mapNotNull(GroupHeader::externalId);
        };
        return 값.filter(v -> !v.isBlank()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    private static String 이름(UniqueKind 종류) {
        return switch (종류) {
            case USER_NAME -> "userName";
            case GROUP_EXTERNAL_ID -> "externalId";
        };
    }

    private static String 충돌_문구(UniqueKind 종류, String 값, String 주인) {
        return switch (종류) {
            case USER_NAME -> "이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s".formatted(값, 주인);
            case GROUP_EXTERNAL_ID -> "이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s".formatted(값, 주인);
        };
    }

    private static TemporaryFailureException 쓰는_중(UniqueKind 종류) {
        return new TemporaryFailureException(
                "다른 쓰기가 같은 %s 을(를) 차지하고 아직 저장하지 않았다".formatted(이름(종류)), 쓰는_중_대기);
    }

    /** 스케줄러 시계 — 쓰기 락 백오프와 같은 시계라 가상 시간으로 시험한다({@link AcquireBackoff#지금}). */
    private static Instant 지금() {
        return Instant.ofEpochMilli(Schedulers.parallel().now(TimeUnit.MILLISECONDS));
    }
}
