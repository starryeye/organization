package dev.starryeye.organization.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDailyJobClaimsTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-09-30T18:00:00Z");
    private static final LocalDate 오늘 = LocalDate.parse("2026-09-30");

    private DynamoDbDailyJobClaims claims;

    @BeforeEach
    void 준비한다() {
        claims = new DynamoDbDailyJobClaims(client, properties, Clock.fixed(지금, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("그날 표지는 한 번만 잡힌다 — 두 번째는 거짓이다")
    void 한_번만_잡힌다() {
        // when
        var 처음 = claims.claim("scim-archive", 오늘).block();
        var 두번째 = claims.claim("scim-archive", 오늘).block();

        // then
        assertThat(처음).isTrue();
        assertThat(두번째).isFalse();
    }

    @Test
    @DisplayName("다른 날·다른 작업의 표지는 따로다")
    void 날짜와_작업마다_따로다() {
        // given
        claims.claim("scim-archive", 오늘).block();

        // when, then
        assertThat(claims.claim("scim-archive", 오늘.plusDays(1)).block()).isTrue();
        assertThat(claims.claim("scim-purge", 오늘).block()).isTrue();
    }

    @Test
    @DisplayName("표지는 사흘 뒤 테이블 TTL 로 사라지도록 만료 시각을 단다")
    void 사흘_뒤_만료된다() {
        // when
        claims.claim("ldap-purge", 오늘).block();

        // then
        var item = client.getItem(GetItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s("DAILY#ldap-purge#2026-09-30"), Keys.SK, Attrs.s(Keys.META)))
                .build()).join().item();
        assertThat(Attrs.longValue(item, Keys.EXPIRES_AT)).isEqualTo(지금.plusSeconds(3 * 86400).getEpochSecond());
    }
}
