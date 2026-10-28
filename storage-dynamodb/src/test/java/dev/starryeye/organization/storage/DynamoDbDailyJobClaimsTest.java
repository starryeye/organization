package dev.starryeye.organization.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루 1회 표지의 조건식을 DynamoDB Local 로 본다(설계 2026-10-27 §9). 잡기는 없거나 임대가 끝난 CLAIMED 만, 완료·해제는 쥔 owner 만이다.
 */
class DynamoDbDailyJobClaimsTest extends DynamoDbTestSupport {

    private static final Instant 처음 = Instant.parse("2026-09-30T18:00:00Z");
    private static final LocalDate 오늘 = LocalDate.parse("2026-09-30");
    /** 잡은 때 + job-timeout(30분) + 5분. */
    private static final Instant 임대 = 처음.plus(Duration.ofMinutes(35));

    /** 시험이 손으로 옮겨 임대가 끝나는 것을 재현한다. */
    private static final class 옮기는_시계 extends Clock {

        private Instant 지금 = 처음;

        void 앞으로(Duration 만큼) {
            지금 = 지금.plus(만큼);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return 지금;
        }
    }

    private 옮기는_시계 clock;
    private DynamoDbDailyJobClaims claims;

    @BeforeEach
    void 준비한다() {
        clock = new 옮기는_시계();
        claims = new DynamoDbDailyJobClaims(client, properties, clock);
    }

    private Map<String, AttributeValue> 표지(String job, LocalDate day) {
        return client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(Keys.dailyJobPk(job, day)), Keys.SK, Attrs.s(Keys.META)))
                .consistentRead(true)
                .build()).join().item();
    }

    @Test
    @DisplayName("그날 표지는 한 번만 잡힌다 — 임대 안에서 두 번째는 거짓이다")
    void 한_번만_잡힌다() {
        // when
        var 처음_잡기 = claims.claim("scim-archive", 오늘, "owner-a", 임대).block();
        var 두번째 = claims.claim("scim-archive", 오늘, "owner-b", 임대).block();

        // then
        assertThat(처음_잡기).isTrue();
        assertThat(두번째).isFalse();
    }

    @Test
    @DisplayName("다른 날·다른 작업의 표지는 따로다")
    void 날짜와_작업마다_따로다() {
        // given
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();

        // when, then
        assertThat(claims.claim("scim-archive", 오늘.plusDays(1), "owner-b", 임대).block()).isTrue();
        assertThat(claims.claim("scim-purge", 오늘, "owner-c", 임대).block()).isTrue();
    }

    @Test
    @DisplayName("표지는 사흘 뒤 테이블 TTL 로 사라지도록 만료 시각을 단다")
    void 사흘_뒤_만료된다() {
        // when
        claims.claim("ldap-purge", 오늘, "owner-a", 임대).block();

        // then
        assertThat(Attrs.longValue(표지("ldap-purge", 오늘), Keys.EXPIRES_AT))
                .isEqualTo(처음.plusSeconds(3 * 86400).getEpochSecond());
    }

    @Test
    @DisplayName("표지에 상태(CLAIMED)·owner·임대(epoch 초)·만료를 단다")
    void 표지에_상태_owner_임대_만료를_단다() {
        // when
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();

        // then
        var item = 표지("scim-archive", 오늘);
        assertThat(Attrs.str(item, "state")).isEqualTo("CLAIMED");
        assertThat(Attrs.str(item, "owner")).isEqualTo("owner-a");
        assertThat(Attrs.longValue(item, "leaseUntil")).isEqualTo(임대.getEpochSecond());
        assertThat(Attrs.longValue(item, Keys.EXPIRES_AT)).isEqualTo(처음.plus(Duration.ofDays(3)).getEpochSecond());
    }

    @Test
    @DisplayName("DONE 이면 임대가 끝난 뒤에도 그날은 다시 잡지 못한다")
    void DONE_이면_다시_잡지_못한다() {
        // given
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();
        claims.complete("scim-archive", 오늘, "owner-a").block();
        clock.앞으로(Duration.ofHours(2));

        // when
        var 다시 = claims.claim("scim-archive", 오늘, "owner-b", clock.instant().plus(Duration.ofMinutes(35))).block();

        // then
        assertThat(다시).isFalse();
        var item = 표지("scim-archive", 오늘);
        assertThat(Attrs.str(item, "state")).isEqualTo("DONE");
        assertThat(Attrs.str(item, "owner")).isEqualTo("owner-a");
    }

    @Test
    @DisplayName("임대가 끝난 CLAIMED 는 다른 owner 가 잡는다 — 임대 안에서는 못 잡는다")
    void 임대가_끝난_CLAIMED_는_다른_owner_가_잡는다() {
        // given
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();

        // when
        clock.앞으로(Duration.ofMinutes(34));
        var 임대_안 = claims.claim("scim-archive", 오늘, "owner-b", clock.instant().plus(Duration.ofMinutes(35))).block();
        clock.앞으로(Duration.ofMinutes(2));
        var 임대_뒤 = claims.claim("scim-archive", 오늘, "owner-b", clock.instant().plus(Duration.ofMinutes(35))).block();

        // then
        assertThat(임대_안).isFalse();
        assertThat(임대_뒤).isTrue();
        var item = 표지("scim-archive", 오늘);
        assertThat(Attrs.str(item, "state")).isEqualTo("CLAIMED");
        assertThat(Attrs.str(item, "owner")).isEqualTo("owner-b");
    }

    @Test
    @DisplayName("쥔 owner 가 풀면 표지가 사라져 같은 날 다시 잡힌다")
    void 쥔_owner_가_풀면_같은_날_다시_잡힌다() {
        // given
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();

        // when
        claims.release("scim-archive", 오늘, "owner-a").block();
        var 다시 = claims.claim("scim-archive", 오늘, "owner-b", 임대).block();

        // then
        assertThat(다시).isTrue();
        assertThat(Attrs.str(표지("scim-archive", 오늘), "owner")).isEqualTo("owner-b");
    }

    @Test
    @DisplayName("임대를 잃은 owner 의 늦은 완료와 해제는 새로 잡은 표지를 바꾸지 않는다")
    void 죽은_owner_의_늦은_완료와_해제는_새_표지를_바꾸지_않는다() {
        // given — A 가 T+35분 임대로 잡고, T+36분에 B 가 잡는다
        claims.claim("scim-archive", 오늘, "owner-a", 임대).block();
        clock.앞으로(Duration.ofMinutes(36));
        var B가_잡았다 = claims.claim("scim-archive", 오늘, "owner-b", clock.instant().plus(Duration.ofMinutes(35))).block();

        // when — A 가 늦게 완료하고 해제한다
        claims.complete("scim-archive", 오늘, "owner-a").block();
        var A_완료_뒤 = 표지("scim-archive", 오늘);
        claims.release("scim-archive", 오늘, "owner-a").block();
        var A_해제_뒤 = 표지("scim-archive", 오늘);
        claims.complete("scim-archive", 오늘, "owner-b").block();

        // then
        assertThat(B가_잡았다).isTrue();
        assertThat(Attrs.str(A_완료_뒤, "state")).isEqualTo("CLAIMED");
        assertThat(Attrs.str(A_완료_뒤, "owner")).isEqualTo("owner-b");
        assertThat(A_해제_뒤).as("A 의 해제가 B 의 표지를 지우지 않는다").isNotEmpty();
        assertThat(Attrs.str(A_해제_뒤, "owner")).isEqualTo("owner-b");
        assertThat(Attrs.str(표지("scim-archive", 오늘), "state")).isEqualTo("DONE");
    }
}
