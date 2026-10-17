package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import dev.starryeye.organization.core.port.TemporaryFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 유일 값 표지판의 판단(설계 2026-10-14 §4.1·§4.4) — 가짜 저장소와 가상 시간으로 본다. */
class UniqueMarkersTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private FakeStateRepository state;
    private UniqueMarkers 표지판;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        표지판 = new UniqueMarkers(state, TTL);
    }

    @AfterEach
    void 가상_시간을_되돌린다() {
        VirtualTimeScheduler.reset();
    }

    private static DirectoryUser 직원(String id, String userName) {
        return new DirectoryUser(id, null, userName, userName, null, true);
    }

    private void 적는다(UniqueKind 종류, String 키, String 주인, Instant 시각) {
        state.uniqueMarkers.computeIfAbsent(종류, ignored -> new LinkedHashMap<>()).put(키, new UniqueClaim(주인, 시각));
    }

    private Map<String, UniqueClaim> 적은것(UniqueKind 종류) {
        return state.uniqueMarkers.getOrDefault(종류, Map.of());
    }

    @Test
    @DisplayName("비어 있으면 소문자 키로 차지한다")
    void 비어_있으면_소문자_키로_차지한다() {
        // when
        표지판.차지한다(UniqueKind.USER_NAME, "Kim.Lee", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim.lee");
        assertThat(적은것(UniqueKind.USER_NAME).get("kim.lee").ownerId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("주인이 그 값을 쥐고 있으면 409 다 — 대소문자만 달라도 그렇다")
    void 주인이_쥐면_409다() {
        // given
        state.users.put("u-1", 직원("u-1", "kim"));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "KIM", "u-2").block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 userName 을 쓰는 직원이 있습니다: userName=KIM, id=u-1");
    }

    @Test
    @DisplayName("같은 아이디로 다시 와도 주인이 그 값을 쥐고 있으면 409 다 — 생성에는 자기 자신이 없다")
    void 같은_아이디라도_쥐고_있으면_409다() {
        // given
        state.users.put("u-1", 직원("u-1", "kim"));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block())
                .isInstanceOf(DirectoryConflictException.class);
    }

    @Test
    @DisplayName("주인이 나면 새 표지판도 차지한 것으로 본다 — 내가 전에 차지하고 놓지 못한 것이다")
    void 주인이_나면_차지한_것으로_본다() {
        // given — u-1 은 아직 old 이고, 방금(가상 시각 0) 남긴 kim 표지판이 u-1 의 것이다
        VirtualTimeScheduler.getOrSet();
        state.users.put("u-1", 직원("u-1", "old"));
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isEqualTo(new UniqueClaim("u-1", Instant.EPOCH));
    }

    @Test
    @DisplayName("주인이 나여도 락 TTL 이 지난 표지판은 시각을 새로 쓴다 — 아니면 META 를 저장하기 전에 남이 넘겨받는다")
    void 내_오래된_표지판은_시각을_새로_쓴다() {
        // given — u-1 은 아직 old 이고, kim 표지판은 u-1 의 것이지만 락 TTL 만큼 지났다
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        state.users.put("u-1", 직원("u-1", "old"));
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);
        시간.advanceTimeBy(TTL);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isEqualTo(new UniqueClaim("u-1", Instant.EPOCH.plus(TTL)));
    }

    @Test
    @DisplayName("주인이 나인 오래된 표지판의 시각을 새로 쓰다가 조건에서 막히면(그 사이 남이 넘겨받았다) 503 이다")
    void 내_오래된_표지판의_새로_쓰기가_막히면_503이다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        state = new FakeStateRepository() {
            @Override
            public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
                return Mono.just(false);
            }
        };
        표지판 = new UniqueMarkers(state, TTL);
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);
        시간.advanceTimeBy(TTL);

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-1").block())
                .isInstanceOf(TemporaryFailureException.class);
    }

    @Test
    @DisplayName("주인이 그 값을 쥐지 않고 락 TTL 이 지난 표지판은 넘겨받는다")
    void 오래된_표지판은_넘겨받는다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        적는다(UniqueKind.USER_NAME, "kim", "ghost", Instant.EPOCH);
        시간.advanceTimeBy(TTL);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim")).isEqualTo(new UniqueClaim("u-2", Instant.EPOCH.plus(TTL)));
    }

    @Test
    @DisplayName("주인이 그 값을 쥐지 않아도 락 TTL 안의 새 표지판이면 503(2초)이다 — 다른 쓰기가 아직 저장하지 않았을 수 있다")
    void 새_표지판은_503이다() {
        // given
        VirtualTimeScheduler 시간 = VirtualTimeScheduler.getOrSet();
        적는다(UniqueKind.USER_NAME, "kim", "in-flight", Instant.EPOCH);
        시간.advanceTimeBy(TTL.minusMillis(1));

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block())
                .isInstanceOfSatisfying(TemporaryFailureException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2)));
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("in-flight");
    }

    @Test
    @DisplayName("주인이 이름을 바꿔 그 값을 쥐지 않는 오래된 표지판도 넘겨받는다")
    void 이름을_바꾼_주인의_표지판을_넘겨받는다() {
        // given — 실제 시계(지금)로 보면 EPOCH 표지판은 오래됐다
        state.users.put("u-1", 직원("u-1", "lee"));
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("넘겨받기가 조건에서 막히면(그 사이 남이 넘겨받았다) 503 이다")
    void 넘겨받기가_막히면_503이다() {
        // given
        state = new FakeStateRepository() {
            @Override
            public Mono<Boolean> takeOverUnique(UniqueKind kind, String key, String ownerId, String previousOwnerId, Instant at) {
                return Mono.just(false);
            }
        };
        표지판 = new UniqueMarkers(state, TTL);
        적는다(UniqueKind.USER_NAME, "kim", "ghost", Instant.EPOCH);

        // when, then
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, "kim", "u-2").block())
                .isInstanceOf(TemporaryFailureException.class);
    }

    @Test
    @DisplayName("키가 1024바이트를 넘으면 400 이고, 1024바이트는 차지한다")
    void 긴_키는_400이다() {
        // given — 한글 한 자는 3바이트다
        String 한도 = "가".repeat(341) + "a";
        String 넘침 = "가".repeat(341) + "ab";

        // when
        표지판.차지한다(UniqueKind.USER_NAME, 한도, "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys(한도);
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.USER_NAME, 넘침, "u-2").block())
                .isInstanceOf(ValueTooLongException.class)
                .hasMessage("userName 이(가) 1024바이트를 넘습니다");
    }

    @Test
    @DisplayName("값이 없거나 비면 아무것도 하지 않는다")
    void 빈_값은_차지하지_않는다() {
        // when
        표지판.차지한다(UniqueKind.USER_NAME, null, "u-1").block();
        표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, " ", "g-1").block();

        // then
        assertThat(state.uniqueMarkers.values()).allMatch(Map::isEmpty);
    }

    @Test
    @DisplayName("조직 externalId 는 대소문자를 가린다 — 다른 대소문자는 다른 값이다")
    void 조직_externalId_는_대소문자를_가린다() {
        // given
        state.groups.put("g-1", new DirectoryGroup("g-1", "EXT-1", "개발", Set.of()));

        // when
        표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-2").block();

        // then
        assertThat(적은것(UniqueKind.GROUP_EXTERNAL_ID)).containsOnlyKeys("ext-1");
        assertThatThrownBy(() -> 표지판.차지한다(UniqueKind.GROUP_EXTERNAL_ID, "EXT-1", "g-3").block())
                .isInstanceOf(DirectoryConflictException.class)
                .hasMessage("이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=EXT-1, id=g-1");
    }

    @Test
    @DisplayName("정리는 주인이 지금 쥐지 않은 값만 놓는다")
    void 쥐지_않은_것만_놓는다() {
        // given — u-1 은 new 를 쥐고 있다. old·new 표지판이 둘 다 u-1 의 것이다
        state.users.put("u-1", 직원("u-1", "new"));
        적는다(UniqueKind.USER_NAME, "old", "u-1", Instant.EPOCH);
        적는다(UniqueKind.USER_NAME, "new", "u-1", Instant.EPOCH);

        // when
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("Old", "new")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("new");
    }

    @Test
    @DisplayName("주인이 없으면 건드린 값을 모두 놓는다")
    void 주인이_없으면_모두_놓는다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);

        // when
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("kim")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).isEmpty();
    }

    @Test
    @DisplayName("남의 표지판은 놓지 않는다")
    void 남의_표지판은_놓지_않는다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-2", Instant.EPOCH);

        // when
        표지판.놓는다(UniqueKind.USER_NAME, "Kim", "u-1").block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME).get("kim").ownerId()).isEqualTo("u-2");
    }

    @Test
    @DisplayName("놓기가 실패해도 오류를 내지 않는다 — 경고만 남기고, 남은 표지판은 나중에 넘겨받는다")
    void 놓기_실패는_오류가_아니다() {
        // given
        적는다(UniqueKind.USER_NAME, "kim", "u-1", Instant.EPOCH);
        state.failReleaseUnique = new IllegalStateException("놓기 실패(테스트)");

        // when
        표지판.놓는다(UniqueKind.USER_NAME, "kim", "u-1").block();
        표지판.쥐지_않은_것을_놓는다(UniqueKind.USER_NAME, "u-1", List.of("kim")).block();

        // then
        assertThat(적은것(UniqueKind.USER_NAME)).containsOnlyKeys("kim");
    }
}
