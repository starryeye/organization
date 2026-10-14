package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.UniqueClaim;
import dev.starryeye.organization.core.model.UniqueKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 유일 값 표지판 줄의 조건부 쓰기(설계 2026-10-14 §3.2) — DynamoDB Local 로 조건과 돌려받는 값을 본다. */
class DynamoDbUniqueMarkersTest extends DynamoDbTestSupport {

    private static final Instant T1 = Instant.parse("2026-10-14T00:00:00.123Z");
    private static final Instant T2 = Instant.parse("2026-10-14T00:01:00Z");

    private DynamoDbDirectoryStateRepository repository;

    @BeforeEach
    void 준비한다() {
        repository = new DynamoDbDirectoryStateRepository(client, properties, Clock.fixed(T1, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("없으면 차지하고 빈 값이다 — 있으면 주인이 나여도 아무것도 쓰지 않고 그 표지판(주인·시각)을 돌려준다")
    void 없으면_차지하고_있으면_돌려준다() {
        // when
        UniqueClaim 처음 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();
        UniqueClaim 남 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-2", T2).block();
        UniqueClaim 나 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T2).block();

        // then
        assertThat(처음).isNull();
        assertThat(남).isEqualTo(new UniqueClaim("u-1", T1));
        assertThat(나).as("주인이 나여도 차지하지 않는다 — 생성에는 자기 자신이 없다").isEqualTo(new UniqueClaim("u-1", T1));
    }

    @Test
    @DisplayName("종류마다 파티션이 따로다 — 같은 키라도 직원 userName 과 조직 externalId 는 부딪치지 않는다")
    void 종류마다_따로다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "ext-1", "u-1", T1).block();

        // when
        UniqueClaim 조직 = repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-1", T1).block();

        // then
        assertThat(조직).isNull();
    }

    @Test
    @DisplayName("저장소는 키를 바꾸지 않는다 — 대소문자 규칙은 core 가 키를 만들 때 적용한다")
    void 키를_그대로_쓴다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "Kim", "u-1", T1).block();

        // when
        UniqueClaim 소문자 = repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-2", T1).block();

        // then
        assertThat(소문자).isNull();
    }

    @Test
    @DisplayName("넘겨받기는 주인이 아직 옛 주인일 때만 된다")
    void 넘겨받기는_옛_주인일_때만이다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "ghost", T1).block();

        // when
        Boolean 넘겨받았다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-2", "ghost", T2).block();
        Boolean 늦었다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-3", "ghost", T2).block();

        // then
        assertThat(넘겨받았다).isTrue();
        assertThat(늦었다).isFalse();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block())
                .isEqualTo(new UniqueClaim("u-2", T2));
    }

    @Test
    @DisplayName("없는 표지판은 넘겨받지 못한다")
    void 없는_표지판은_넘겨받지_못한다() {
        // when
        Boolean 넘겨받았다 = repository.takeOverUnique(UniqueKind.USER_NAME, "kim", "u-2", "ghost", T2).block();

        // then
        assertThat(넘겨받았다).isFalse();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
    }

    @Test
    @DisplayName("놓기는 내 것만 지운다 — 남의 것이거나 없으면 조용히 넘어간다")
    void 놓기는_내_것만_지운다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();

        // when
        repository.releaseUnique(UniqueKind.USER_NAME, "kim", "u-2").block();
        repository.releaseUnique(UniqueKind.USER_NAME, "없음", "u-2").block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block())
                .isEqualTo(new UniqueClaim("u-1", T1));

        // when
        repository.releaseUnique(UniqueKind.USER_NAME, "kim", "u-1").block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
    }

    @Test
    @DisplayName("비우기는 두 종류의 표지판만 지운다 — 직원 META 와 보류 목록은 그대로다")
    void 비우기는_표지판만_지운다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();
        repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-1", T1).block();
        repository.saveUser(new DirectoryUser("u-1", null, "kim", "김", null, true)).block();
        repository.replaceCutEdges(Set.of(new GroupEdge("g-1", "g-2"))).block();

        // when
        repository.clearUniqueMarkers().block();

        // then
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-9", T2).block()).isNull();
        assertThat(repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-9", T2).block()).isNull();
        assertThat(repository.findUser("u-1").block()).isNotNull();
        assertThat(repository.findCutEdges().collectList().block()).containsExactly(new GroupEdge("g-1", "g-2"));
    }

    @Test
    @DisplayName("주인 읽기는 그 종류 파티션의 주인만 모두 읽는다 — 다른 종류와 직원 META 는 섞이지 않고, 비운 뒤에는 없다")
    void 주인은_종류별로_모두_읽는다() {
        // given
        repository.claimUnique(UniqueKind.USER_NAME, "kim", "u-1", T1).block();
        repository.claimUnique(UniqueKind.USER_NAME, "lee", "u-2", T1).block();
        repository.claimUnique(UniqueKind.GROUP_EXTERNAL_ID, "ext-1", "g-1", T1).block();
        repository.saveUser(new DirectoryUser("u-3", null, "park", "박", null, true)).block();

        // when
        List<String> 직원 = repository.findUniqueOwners(UniqueKind.USER_NAME).collectList().block();
        List<String> 조직 = repository.findUniqueOwners(UniqueKind.GROUP_EXTERNAL_ID).collectList().block();
        repository.clearUniqueMarkers().block();
        List<String> 비운_뒤 = repository.findUniqueOwners(UniqueKind.USER_NAME).collectList().block();

        // then
        assertThat(직원).containsExactlyInAnyOrder("u-1", "u-2");
        assertThat(조직).containsExactly("g-1");
        assertThat(비운_뒤).isEmpty();
    }

    @Test
    @DisplayName("1024바이트 키는 쓸 수 있다 — 포트 계약의 한도가 DynamoDB 정렬키 한도와 같다")
    void 한도_길이의_키를_쓴다() {
        // given — 한글 341자(1023바이트) + 1바이트
        String 키 = "가".repeat(341) + "a";

        // when
        UniqueClaim 처음 = repository.claimUnique(UniqueKind.USER_NAME, 키, "u-1", T1).block();

        // then
        assertThat(처음).isNull();
        assertThat(repository.claimUnique(UniqueKind.USER_NAME, 키, "u-2", T1).block())
                .isEqualTo(new UniqueClaim("u-1", T1));
    }
}
