package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.query.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한도를 넘는 키는 DynamoDB 를 부르지 않고 "없음" 이다(설계 2026-10-17 §4).
 *
 * <p>DynamoDB Local 은 일부 호출만 키 길이를 거절해(GetItem·BatchGet·GSI 정렬키), 이 시험은 요청을 보내지 않는지를 본다. 부르면 곧바로 실패하는
 * 클라이언트로 저장소를 만들어, 요청이 하나도 나가지 않음을 고정한다.
 */
class KeyLimitReadTest extends DynamoDbTestSupport {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** {@code USER#} 와 합쳐 2049바이트 — 파티션키 한도(2048)를 1바이트 넘는다. */
    private static final String 긴_아이디 = "x".repeat(2044);

    @Test
    @DisplayName("한도를 넘는 아이디는 DynamoDB 를 부르지 않고 없음으로 답한다")
    void 넘는_아이디는_부르지_않고_없음이다() {
        // given
        DynamoDbAsyncClient 부르면_실패 = 부르면_실패하는_클라이언트();
        var 상태 = new DynamoDbDirectoryStateRepository(부르면_실패, properties, CLOCK);
        var 조회 = new DynamoDbDirectoryQueryRepository(부르면_실패, properties, 상태);
        var 검색 = new DynamoDbDirectorySearchRepository(부르면_실패, properties);
        var 이력 = new DynamoDbAuditLog(부르면_실패, properties);

        // when, then — 상태
        assertThat(상태.findUser(긴_아이디).block()).isNull();
        assertThat(상태.findGroup(긴_아이디).block()).isNull();
        assertThat(상태.findGroupHeader(긴_아이디).block()).isNull();
        assertThat(상태.findMemberRefs(긴_아이디).collectList().block()).isEmpty();
        assertThat(상태.findGroupIdsContaining(MemberRef.user(긴_아이디)).collectList().block()).isEmpty();
        assertThat(상태.findGroupIdsContaining(MemberRef.group(긴_아이디)).collectList().block()).isEmpty();

        // then — 조회
        assertThat(조회.findUser(긴_아이디).block()).isNull();
        assertThat(조회.findGroupHeader(긴_아이디).block()).isNull();

        // then — 검색
        assertThat(검색.findGroupSummary(긴_아이디).block()).isNull();
        assertThat(검색.findGroupUserMemberIds(긴_아이디, null, 10).block()).isEqualTo(Page.empty());
        assertThat(검색.findChildOrgCodes(긴_아이디).collectList().block()).isEmpty();

        // then — 이력은 커서를 보기 전에 답한다
        assertThat(이력.history(MemberRef.user(긴_아이디), null, 10).block()).isEqualTo(Page.empty());
        assertThat(이력.history(MemberRef.group(긴_아이디), null, 10).block()).isEqualTo(Page.empty());
    }

    @Test
    @DisplayName("한도를 넘는 색인 값은 DynamoDB 를 부르지 않고 없음으로 답한다")
    void 넘는_색인_값은_부르지_않고_없음이다() {
        // given — GSI1·GSI2 정렬키는 1024바이트, GSI3 파티션키(externalId)는 2048바이트가 한도다
        DynamoDbAsyncClient 부르면_실패 = 부르면_실패하는_클라이언트();
        var 상태 = new DynamoDbDirectoryStateRepository(부르면_실패, properties, CLOCK);
        var 조회 = new DynamoDbDirectoryQueryRepository(부르면_실패, properties, 상태);
        var 검색 = new DynamoDbDirectorySearchRepository(부르면_실패, properties);
        String 긴_정렬키_값 = "x".repeat(1025);
        String 긴_외부_아이디 = "x".repeat(2049);

        // when, then — 정렬키 쪽
        assertThat(조회.findUsersByUserName(긴_정렬키_값).collectList().block()).isEmpty();
        assertThat(조회.findGroupHeadersByDisplayName(긴_정렬키_값).collectList().block()).isEmpty();
        assertThat(검색.searchUsersByUserName(긴_정렬키_값, null, 10).block()).isEqualTo(Page.empty());
        assertThat(검색.searchUsersByDisplayName(긴_정렬키_값, null, 10).block()).isEqualTo(Page.empty());
        assertThat(검색.searchGroupsByDisplayName(긴_정렬키_값, null, 10).block()).isEqualTo(Page.empty());

        // then — externalId 는 상태 저장소의 검사로 충분하고, 조회 저장소는 자기 키를 먼저 만들지 않는다
        assertThat(상태.findUserIdsByExternalId(긴_외부_아이디).collectList().block()).isEmpty();
        assertThat(상태.findGroupIdsByExternalId(긴_외부_아이디).collectList().block()).isEmpty();
        assertThat(조회.findUsersByExternalId(긴_외부_아이디).collectList().block()).isEmpty();
        assertThat(조회.findGroupHeadersByExternalId(긴_외부_아이디).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("정렬키 한도를 넘는 멤버 후보는 DynamoDB 를 부르지 않고 없음으로 답한다")
    void 넘는_멤버_후보는_부르지_않고_없음이다() {
        // given — MEMBER#USER# 12바이트, MEMBER#GROUP# 13바이트와 합쳐 1025바이트
        var 상태 = new DynamoDbDirectoryStateRepository(부르면_실패하는_클라이언트(), properties, CLOCK);
        Set<MemberRef> 후보 = Set.of(MemberRef.user("u".repeat(1013)), MemberRef.group("g".repeat(1012)));

        // when
        Set<MemberRef> 찾은것 = 상태.findMembers("DEV", 후보).block();

        // then
        assertThat(찾은것).isEmpty();
    }

    @Test
    @DisplayName("소문자로 바꾸면 한도를 넘는 접두어도 DynamoDB 를 부르지 않고 없음으로 답한다")
    void 소문자로_길어지는_접두어도_잰다() {
        // given — U+0130 은 2바이트인데 소문자(i + U+0307)는 3바이트라, 원래는 1024바이트지만 소문자로는 1536바이트다
        var 검색 = new DynamoDbDirectorySearchRepository(부르면_실패하는_클라이언트(), properties);
        String 접두어 = "İ".repeat(512);
        assertThat(접두어.getBytes(StandardCharsets.UTF_8)).hasSize(1024);

        // when
        Page<?> 쪽 = 검색.searchUsersByUserName(접두어, null, 10).block();

        // then
        assertThat(쪽).isEqualTo(Page.empty());
    }

    @Test
    @DisplayName("묶음 읽기는 한도를 넘는 아이디만 빼고 나머지를 읽는다")
    void 묶음_읽기는_넘는_아이디만_뺀다() {
        // given
        var 심는_저장소 = new DynamoDbDirectoryStateRepository(client, properties, CLOCK);
        심는_저장소.saveUser(new DirectoryUser("kim", null, "kim", "김", null, true)).block();
        List<Object> 요청 = new CopyOnWriteArrayList<>();
        var 상태 = new DynamoDbDirectoryStateRepository(세는_클라이언트(client, 요청), properties, CLOCK);

        // when
        List<DirectoryUser> 직원들 = 상태.findUsers(Set.of("kim", 긴_아이디)).collectList().block();
        Map<String, MemberType> 종류들 = 상태.findMemberTypes(Set.of("kim", 긴_아이디)).block();

        // then — 결과는 kim 뿐이고, 보낸 키 중 한도를 넘는 것이 없다
        assertThat(직원들).extracting(DirectoryUser::id).containsExactly("kim");
        assertThat(종류들).containsExactly(Map.entry("kim", MemberType.USER));
        assertThat(보낸_파티션키들(요청))
                .isNotEmpty()
                .allSatisfy(pk -> assertThat(pk.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Keys.MAX_PARTITION_KEY_BYTES));
    }

    @Test
    @DisplayName("한 종류의 키만 한도를 넘는 아이디는 넘지 않는 종류의 키만 읽는다")
    void 종류마다_최종_키를_잰다() {
        // given — USER# 와 합쳐 정확히 2048바이트, GROUP# 와 합쳐서는 2049바이트다
        List<Object> 요청 = new CopyOnWriteArrayList<>();
        var 상태 = new DynamoDbDirectoryStateRepository(세는_클라이언트(client, 요청), properties, CLOCK);

        // when
        Map<String, MemberType> 종류들 = 상태.findMemberTypes(Set.of("x".repeat(2043))).block();

        // then
        assertThat(종류들).isEmpty();
        assertThat(보낸_파티션키들(요청)).containsExactly(Keys.userPk("x".repeat(2043)));
    }

    @Test
    @DisplayName("한도와 같은 바이트의 키는 DynamoDB 를 부른다")
    void 한도_바이트의_키는_부른다() {
        // given
        List<Object> 요청 = new CopyOnWriteArrayList<>();
        DynamoDbAsyncClient 세는_클라이언트 = 세는_클라이언트(client, 요청);
        var 상태 = new DynamoDbDirectoryStateRepository(세는_클라이언트, properties, CLOCK);
        var 검색 = new DynamoDbDirectorySearchRepository(세는_클라이언트, properties);

        // when, then — USER# 와 합쳐 정확히 2048바이트
        assertThat(상태.findUser("x".repeat(2043)).block()).isNull();
        assertThat(요청).as("파티션키 한도 바이트").hasSize(1);

        // when, then — 정렬키(GSI1) 접두어 정확히 1024바이트
        assertThat(검색.searchUsersByUserName("x".repeat(1024), null, 10).block()).isEqualTo(Page.empty());
        assertThat(요청).as("정렬키 한도 바이트").hasSize(2);

        // when, then — MEMBER#USER# 12바이트와 합쳐 정확히 1024바이트
        assertThat(상태.findMembers("DEV", Set.of(MemberRef.user("u".repeat(1012)))).block()).isEmpty();
        assertThat(요청).as("멤버 줄 정렬키 한도 바이트").hasSize(3);
    }

    /** 서비스 이름과 닫기 말고는 무엇이든 부르면 실패로 답한다 — 저장소가 요청을 보냈다는 증거다. */
    private static DynamoDbAsyncClient 부르면_실패하는_클라이언트() {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "serviceName" -> "dynamodb";
                    case "close" -> null;
                    case "toString" -> "부르면 실패하는 클라이언트";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> CompletableFuture.failedFuture(new AssertionError("DynamoDB 를 불렀다: " + method.getName()));
                });
    }

    /** 진짜 클라이언트를 감싸 보낸 요청을 모은다. */
    private static DynamoDbAsyncClient 세는_클라이언트(DynamoDbAsyncClient real, List<Object> 요청) {
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (args != null && args.length == 1) {
                        요청.add(args[0]);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** 묶음 읽기 요청들이 담은 파티션키 값 전부. */
    private static List<String> 보낸_파티션키들(List<Object> 요청) {
        return 요청.stream()
                .filter(BatchGetItemRequest.class::isInstance)
                .map(BatchGetItemRequest.class::cast)
                .flatMap(request -> request.requestItems().values().stream())
                .map(KeysAndAttributes::keys)
                .flatMap(List::stream)
                .map(key -> key.get(Keys.PK))
                .map(AttributeValue::s)
                .toList();
    }
}
