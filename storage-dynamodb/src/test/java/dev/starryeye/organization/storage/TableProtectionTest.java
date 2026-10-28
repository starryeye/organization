package dev.starryeye.organization.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.PointInTimeRecoveryStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 설정({@code dynamodb.require-protection=true})의 삭제 보호·PITR(설계 2026-10-27 §3).
 * 삭제 보호는 DynamoDB Local 이 지키므로 Local 로, PITR 은 Local 이 거절하므로 {@link ContinuousBackupsStubClient} 로 본다.
 */
class TableProtectionTest extends DynamoDbTestSupport {

    private static final Duration PITR_간격 = Duration.ofMillis(10);
    /** 넉넉히 둔다 — 재시도는 실제 병렬 스케줄러에서 돌아, 바쁜 전체 실행에서 200ms 로는 "잠시 거절" 시험이 두 번째 거절 뒤 한도를 넘길 수 있다. */
    private static final Duration PITR_한도 = Duration.ofSeconds(1);

    private ContinuousBackupsStubClient 스텁;

    @BeforeEach
    void 준비한다() {
        // DynamoDbTestSupport 가 보호 없이 만든 테이블이 이미 있다 — 각 시험이 지우거나 그대로 쓴다
        스텁 = new ContinuousBackupsStubClient(client);
        properties.setRequireProtection(true);
    }

    private TableInitializer 초기화기() {
        return new TableInitializer(스텁, properties, PITR_간격, PITR_한도);
    }

    @Test
    @DisplayName("보호를 요구하면 삭제 보호를 켜서 만들고 PITR 을 켠다")
    void 보호를_요구하면_삭제_보호와_PITR_을_켜서_만든다() {
        // given
        dropTableIfExists();

        // when
        초기화기().ensureTable().block();

        // then
        var table = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();
        assertThat(table.deletionProtectionEnabled()).isTrue();
        assertThat(스텁.켜기_요청()).isEqualTo(1);
    }

    @Test
    @DisplayName("막 만든 테이블이 PITR 을 잠시 거절하면 쉬었다가 다시 켠다 — 한도 안이면 기동한다")
    void 막_만든_테이블이_PITR_을_잠시_거절하면_다시_켠다() {
        // given
        dropTableIfExists();
        스텁.처음_n번_거절한다(2);

        // when, then
        assertThatCode(() -> 초기화기().ensureTable().block()).doesNotThrowAnyException();
        assertThat(스텁.켜기_요청()).isEqualTo(3);
    }

    @Test
    @DisplayName("한도 안에 PITR 을 켜지 못하면 기동을 멈춘다 — 만든 테이블은 남는다")
    void 한도_안에_켜지_못하면_기동을_멈추고_테이블은_남는다() {
        // given
        dropTableIfExists();
        스텁.늘_거절한다();

        // when, then
        assertThatThrownBy(() -> 초기화기().ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("테이블은 만들었으나 PITR 을 켜지 못했다 — 콘솔·CLI 로 켠 뒤 다시 띄운다");
        assertThat(스텁.켜기_요청()).as("한도 안에서 여러 번 다시 시도했다").isGreaterThan(1);
        var table = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();
        assertThat(table.deletionProtectionEnabled()).isTrue();
    }

    @Test
    @DisplayName("있는 테이블의 삭제 보호가 꺼져 있으면 기동을 멈춘다")
    void 있는_테이블의_삭제_보호가_꺼져_있으면_기동을_멈춘다() {
        // given — 준비에서 보호 없이 만든 테이블이 있고, PITR 은 켜져 있다
        스텁.PITR_상태를_정한다(PointInTimeRecoveryStatus.ENABLED);

        // when, then
        assertThatThrownBy(() -> 초기화기().ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("삭제 보호")
                .hasMessageNotContaining("PITR");
    }

    @Test
    @DisplayName("있는 테이블의 PITR 이 꺼져 있으면 기동을 멈춘다")
    void 있는_테이블의_PITR_이_꺼져_있으면_기동을_멈춘다() {
        // given — 삭제 보호는 켜져 있고, PITR 은 꺼져 있다
        삭제_보호를_켠다();
        스텁.PITR_상태를_정한다(PointInTimeRecoveryStatus.DISABLED);

        // when, then
        assertThatThrownBy(() -> 초기화기().ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PITR")
                .hasMessageNotContaining("삭제 보호");
    }

    @Test
    @DisplayName("보호가 둘 다 꺼져 있으면 꺼진 것을 모두 적는다")
    void 둘_다_꺼져_있으면_모두_적는다() {
        // given — 준비에서 보호 없이 만든 테이블, PITR 도 꺼짐
        스텁.PITR_상태를_정한다(PointInTimeRecoveryStatus.DISABLED);

        // when, then
        assertThatThrownBy(() -> 초기화기().ensureTable().block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("테이블 'organization-test' 의 보호가 꺼져 있다: 삭제 보호, PITR — 콘솔·CLI 로 켠 뒤 다시 띄운다");
    }

    @Test
    @DisplayName("PITR 로 복원한 테이블이면 경고만 한 번 남기고 기동한다")
    void 복원본이면_경고만_하고_기동한다() {
        // given — 보호는 모두 켜져 있고, DescribeTable 응답에 restoreSummary 가 있다
        삭제_보호를_켠다();
        스텁.PITR_상태를_정한다(PointInTimeRecoveryStatus.ENABLED);
        스텁.복원본으로_보이게_한다();
        Logger logger = (Logger) LoggerFactory.getLogger(TableInitializer.class);
        ListAppender<ILoggingEvent> 로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);

        try {
            // when, then
            assertThatCode(() -> 초기화기().ensureTable().block()).doesNotThrowAnyException();
            assertThat(로그.list).filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).isEqualTo(
                            "PITR 로 복원한 테이블이다 — 장부보다 낡은 기준선일 수 있어 재적재를 권한다(SCIM 은 mode=tuples)"));
        } finally {
            logger.detachAppender(로그);
            로그.stop();
        }
    }

    private void 삭제_보호를_켠다() {
        client.updateTable(UpdateTableRequest.builder()
                .tableName(properties.getTableName()).deletionProtectionEnabled(true).build()).join();
    }
}
