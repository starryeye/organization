package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역참조가 <b>본문 테이블을 강한 일관성으로</b> 읽는지 요청 자체로 확인한다.
 *
 * <p>DynamoDB Local 은 GSI 를 즉시 반영하므로 "인덱스가 늦어서 못 봤다" 를 재현할 수 없다.
 * 재현 대신 요청의 모양을 못박는다 — 누군가 GSI 조회로 되돌리면 여기서 걸린다.
 */
class ReverseLookupQueryShapeTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("역참조 조회는 인덱스를 지정하지 않고 강한 일관성으로 읽는다")
    void 역참조는_색인을_쓰지_않는다() {
        // given
        List<QueryRequest> 보낸것 = new CopyOnWriteArrayList<>();
        DynamoDbAsyncClient 기록하는클라이언트 = 기록하는_클라이언트(보낸것);
        var repository = new DynamoDbDirectoryStateRepository(
                기록하는클라이언트, properties, Clock.systemUTC());
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();
        보낸것.clear();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then
        assertThat(groupIds).containsExactly("DEV002");
        assertThat(보낸것).isNotEmpty();
        assertThat(보낸것).allSatisfy(request -> {
            assertThat(request.indexName()).as("역참조가 인덱스를 쓰면 최종 일관성으로 돌아간다").isNull();
            assertThat(request.consistentRead()).as("강한 일관성으로 읽어야 한다").isTrue();
        });
    }

    @Test
    @DisplayName("멤버를 넣을 때는 소속 줄이 먼저, 뺄 때는 멤버 줄이 먼저다")
    void 쓰기_순서가_고정된다() {
        // given
        List<String> 순서 = new CopyOnWriteArrayList<>();
        DynamoDbAsyncClient 기록하는클라이언트 = 정렬키를_기록하는_클라이언트(순서);
        var repository = new DynamoDbDirectoryStateRepository(
                기록하는클라이언트, properties, Clock.systemUTC());

        // when — 넣는다
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀",
                Set.of(MemberRef.user("kim")))).block();

        // then — 소속 줄이 멤버 줄보다 먼저 쓰인다. 반대면 삭제가 조직을 못 찾아 권한이 남는다
        assertThat(순서).containsSubsequence("BELONGS_TO#GROUP#DEV002", "MEMBER#USER#kim");

        // when — 뺀다
        순서.clear();
        repository.saveGroup(new DirectoryGroup("DEV002", "cn=DEV002", "백엔드팀", Set.of())).block();

        // then — 멤버 줄이 소속 줄보다 먼저 지워진다
        assertThat(순서).containsSubsequence("MEMBER#USER#kim", "BELONGS_TO#GROUP#DEV002");
    }

    private DynamoDbAsyncClient 기록하는_클라이언트(List<QueryRequest> 보낸것) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(builder -> builder.addExecutionInterceptor(
                        new ExecutionInterceptor() {
                            @Override
                            public void beforeExecution(Context.BeforeExecution context,
                                                        ExecutionAttributes attributes) {
                                if (context.request() instanceof QueryRequest query) {
                                    보낸것.add(query);
                                }
                            }
                        }))
                .build();
    }

    private DynamoDbAsyncClient 정렬키를_기록하는_클라이언트(List<String> 순서) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(builder -> builder.addExecutionInterceptor(
                        new ExecutionInterceptor() {
                            @Override
                            public void beforeExecution(Context.BeforeExecution context,
                                                        ExecutionAttributes attributes) {
                                if (context.request() instanceof PutItemRequest put) {
                                    순서.add(Attrs.str(put.item(), Keys.SK));
                                } else if (context.request() instanceof DeleteItemRequest delete) {
                                    순서.add(Attrs.str(delete.key(), Keys.SK));
                                }
                            }
                        }))
                .build();
    }
}
