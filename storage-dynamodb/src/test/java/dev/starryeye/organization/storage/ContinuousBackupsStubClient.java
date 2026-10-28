package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ContinuousBackupsDescription;
import software.amazon.awssdk.services.dynamodb.model.ContinuousBackupsStatus;
import software.amazon.awssdk.services.dynamodb.model.ContinuousBackupsUnavailableException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeContinuousBackupsRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeContinuousBackupsResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.PointInTimeRecoveryDescription;
import software.amazon.awssdk.services.dynamodb.model.PointInTimeRecoveryStatus;
import software.amazon.awssdk.services.dynamodb.model.RestoreSummary;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateContinuousBackupsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateContinuousBackupsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbAsyncWaiter;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PITR 두 API 만 흉내 내는 가짜 클라이언트(설계 2026-10-27 §3).
 *
 * <p>DynamoDB Local 2.5.3 은 {@code UpdateContinuousBackups}·{@code DescribeContinuousBackups} 를 UnknownOperation 으로
 * 거절한다(2026-10-27 확인). 그래서 PITR 갈래는 이 가짜로 시험한다. {@code TableInitializer} 가 쓰는 나머지 호출
 * ({@code describeTable}·{@code createTable}·{@code updateTable}·{@code waiter}·{@code updateTimeToLive}·
 * {@code describeTimeToLive}·{@code close})은 Local 로 넘긴다. 그 밖의 메서드는 SDK 인터페이스의 기본 구현이라
 * {@code UnsupportedOperationException} 이다 — 시험이 모르는 호출이 늘면 조용히 지나가지 않고 터진다.
 *
 * <p>PITR 의 처음 상태는 꺼짐이다. {@link #처음_n번_거절한다}·{@link #늘_거절한다} 로 막 만든 테이블을 흉내 내고,
 * 받아들인 켜기 요청은 상태를 켜짐으로 바꾼다.
 *
 * <p>{@link #처음_설명은_만드는_중으로_보이게_한다} 는 남이 막 만드는 테이블을 흉내 낸다 — 첫 {@code describeTable} 은 {@code CREATING} 이고,
 * 다음 {@code describeTable} 전까지는 TTL 도 아직 꺼짐으로 보인다. {@code waiter()} 는 Local 의 것이라 곧바로 ACTIVE 를 본다.
 */
final class ContinuousBackupsStubClient implements DynamoDbAsyncClient {

    private final DynamoDbAsyncClient local;
    private final AtomicInteger 켜기_요청 = new AtomicInteger();

    private volatile int 남은_거절 = 0;
    private volatile boolean 늘_거절 = false;
    private volatile PointInTimeRecoveryStatus pitr_상태 = PointInTimeRecoveryStatus.DISABLED;
    private volatile boolean 복원본 = false;
    private volatile boolean 만드는_중 = false;
    private final AtomicInteger 설명_요청 = new AtomicInteger();

    ContinuousBackupsStubClient(DynamoDbAsyncClient local) {
        this.local = local;
    }

    /** 막 만든 테이블이 PITR 을 잠시 거절하는 것을 흉내 낸다 — 앞의 {@code n}번은 {@code ContinuousBackupsUnavailableException} 이다. */
    void 처음_n번_거절한다(int n) {
        this.남은_거절 = n;
    }

    /** 끝내 켜지 못하는 테이블 — 켜기 요청이 늘 {@code ContinuousBackupsUnavailableException} 이다. */
    void 늘_거절한다() {
        this.늘_거절 = true;
    }

    /** 이미 있는 테이블의 PITR 상태. */
    void PITR_상태를_정한다(PointInTimeRecoveryStatus 상태) {
        this.pitr_상태 = 상태;
    }

    /** DescribeTable 응답에 {@code restoreSummary} 를 붙인다 — PITR 로 복원한 테이블이다. */
    void 복원본으로_보이게_한다() {
        this.복원본 = true;
    }

    /** 첫 DescribeTable 은 {@code CREATING} 이고, 다음 DescribeTable 전까지는 TTL 이 꺼짐으로 보인다 — 남이 막 만드는 테이블이다. */
    void 처음_설명은_만드는_중으로_보이게_한다() {
        this.만드는_중 = true;
    }

    /** {@code DescribeTable} 이 들어온 횟수. */
    int 설명_요청() {
        return 설명_요청.get();
    }

    /** {@code UpdateContinuousBackups} 가 들어온 횟수(거절한 것 포함). */
    int 켜기_요청() {
        return 켜기_요청.get();
    }

    @Override
    public CompletableFuture<UpdateContinuousBackupsResponse> updateContinuousBackups(
            UpdateContinuousBackupsRequest request) {
        켜기_요청.incrementAndGet();
        if (늘_거절 || 남은_거절 > 0) {
            남은_거절--;
            return CompletableFuture.failedFuture(ContinuousBackupsUnavailableException.builder()
                    .message("Backups are being enabled for the table - try again later").build());
        }
        if (request.pointInTimeRecoverySpecification().pointInTimeRecoveryEnabled()) {
            pitr_상태 = PointInTimeRecoveryStatus.ENABLED;
        }
        return CompletableFuture.completedFuture(UpdateContinuousBackupsResponse.builder().build());
    }

    @Override
    public CompletableFuture<DescribeContinuousBackupsResponse> describeContinuousBackups(
            DescribeContinuousBackupsRequest request) {
        return CompletableFuture.completedFuture(DescribeContinuousBackupsResponse.builder()
                .continuousBackupsDescription(ContinuousBackupsDescription.builder()
                        .continuousBackupsStatus(ContinuousBackupsStatus.ENABLED)
                        .pointInTimeRecoveryDescription(PointInTimeRecoveryDescription.builder()
                                .pointInTimeRecoveryStatus(pitr_상태).build())
                        .build())
                .build());
    }

    @Override
    public CompletableFuture<DescribeTableResponse> describeTable(DescribeTableRequest request) {
        boolean 첫_설명 = 설명_요청.incrementAndGet() == 1;
        if (만드는_중 && !첫_설명) {
            만드는_중 = false;
        }
        boolean 만드는_중으로_보인다 = 만드는_중 && 첫_설명;
        return local.describeTable(request).thenApply(response -> {
            if (만드는_중으로_보인다) {
                return response.toBuilder()
                        .table(response.table().toBuilder().tableStatus(TableStatus.CREATING).build())
                        .build();
            }
            if (!복원본) {
                return response;
            }
            return response.toBuilder()
                    .table(response.table().toBuilder()
                            .restoreSummary(RestoreSummary.builder()
                                    .sourceTableArn("arn:aws:dynamodb:ap-northeast-2:000000000000:table/organization")
                                    .restoreDateTime(Instant.parse("2026-10-27T00:00:00Z"))
                                    .restoreInProgress(false)
                                    .build())
                            .build())
                    .build();
        });
    }

    @Override
    public CompletableFuture<CreateTableResponse> createTable(CreateTableRequest request) {
        return local.createTable(request);
    }

    @Override
    public CompletableFuture<UpdateTableResponse> updateTable(UpdateTableRequest request) {
        return local.updateTable(request);
    }

    @Override
    public CompletableFuture<UpdateTimeToLiveResponse> updateTimeToLive(UpdateTimeToLiveRequest request) {
        return local.updateTimeToLive(request);
    }

    @Override
    public CompletableFuture<DescribeTimeToLiveResponse> describeTimeToLive(DescribeTimeToLiveRequest request) {
        if (만드는_중) {
            // 만드는 쪽은 ACTIVE 가 된 뒤에야 TTL 을 켠다 — 그 전에는 꺼짐으로 보인다
            return CompletableFuture.completedFuture(DescribeTimeToLiveResponse.builder()
                    .timeToLiveDescription(TimeToLiveDescription.builder()
                            .timeToLiveStatus(TimeToLiveStatus.DISABLED).build())
                    .build());
        }
        return local.describeTimeToLive(request);
    }

    @Override
    public DynamoDbAsyncWaiter waiter() {
        return local.waiter();
    }

    @Override
    public String serviceName() {
        return local.serviceName();
    }

    @Override
    public void close() {
        local.close();
    }
}
