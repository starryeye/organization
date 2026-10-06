package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ScimGroupStreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String 모은다(Flux<DataBuffer> body) {
        return DataBufferUtils.join(body).map(buffer -> {
            String text = buffer.toString(StandardCharsets.UTF_8);
            DataBufferUtils.release(buffer);
            return text;
        }).block();
    }

    /** 트리 방식(지금까지의 응답)과 흘려 쓴 응답을 값으로 견준다 — 필드 순서와 멤버 순서는 보지 않는다. */
    private static void 같은_값이다(String 흘려쓴, JsonNode 트리) throws Exception {
        JsonNode 받은 = JSON.readTree(흘려쓴);
        assertThat(멤버_없이(받은)).isEqualTo(멤버_없이(트리));
        assertThat(멤버들(받은)).containsExactlyInAnyOrderElementsOf(멤버들(트리));
    }

    private static JsonNode 멤버_없이(JsonNode node) {
        var copy = node.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("members");
        return copy;
    }

    private static List<JsonNode> 멤버들(JsonNode node) {
        List<JsonNode> list = new ArrayList<>();
        if (node.has("members")) node.get("members").forEach(list::add);
        return list;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "- | -",
            "members | -",
            "members.value | -",
            "displayName,members | -",
            "- | members.type",
            "- | externalId"
    })
    @DisplayName("흘려 쓴 조직 JSON 은 트리 방식과 같은 값이다 — 투영(attributes·excludedAttributes·members 하위 속성)까지(설계 2026-10-06 §4.2)")
    void 트리_방식과_같은_값이다(String attributes, String excluded) throws Exception {
        // given
        var state = new FakeStateRepository();
        var group = new DirectoryGroup("DEV", "ext-DEV", "개발본부",
                Set.of(MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("SUB1")));
        state.saveGroup(group).block();
        var projection = ScimAttributeProjection.of(ScimResourceType.GROUP,
                ScimAttributeProjection.split(attributes), ScimAttributeProjection.split(excluded));

        // when
        String 흘려쓴 = 모은다(new ScimGroupStream(state).group(new GroupHeader("DEV", "ext-DEV", "개발본부"), projection));

        // then
        같은_값이다(흘려쓴, projection.apply(ScimJson.tree(ScimMapper.toScimGroup(group))));
    }

    @Test
    @DisplayName("멤버가 0명인 조직은 \"members\":[] 다 — 지금과 같다")
    void 멤버가_없으면_빈_배열이다() throws Exception {
        // given
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("EMPTY", null, "빈 조직", Set.of())).block();

        // when
        String 흘려쓴 = 모은다(new ScimGroupStream(state).group(new GroupHeader("EMPTY", null, "빈 조직"),
                ScimAttributeProjection.all()));

        // then
        assertThat(JSON.readTree(흘려쓴).get("members").isArray()).isTrue();
        assertThat(JSON.readTree(흘려쓴).get("members")).isEmpty();
    }

    @Test
    @DisplayName("앞의 묶음만 받고 멈추면 멤버를 더 읽지 않는다 — 10만 명 조직도 통째로 꺼내지 않는다")
    void 받은_만큼만_멤버를_꺼낸다() {
        // given — 멤버 10만 명을 하나씩 꺼낼 때마다 센다
        AtomicInteger 꺼낸_수 = new AtomicInteger();
        var state = new FakeStateRepository() {
            @Override
            public Flux<MemberRef> findMemberRefs(String groupId) {
                return Flux.range(0, 100_000)
                        .doOnNext(i -> 꺼낸_수.incrementAndGet())
                        .map(i -> MemberRef.user("u%06d".formatted(i)));
            }
        };

        // when — 앞부분과 첫 묶음만 받는다
        new ScimGroupStream(state).group(new GroupHeader("ALL", null, "전 직원"), ScimAttributeProjection.all())
                .take(2)
                .doOnNext(DataBufferUtils::release)
                .blockLast();

        // then
        assertThat(꺼낸_수.get()).isLessThan(10 * ScimGroupStream.멤버_묶음);
    }

    @Test
    @DisplayName("멤버 도중 저장소가 실패하면 닫는 ]} 를 쓰지 않고 오류로 끝난다 — 완결된 JSON 이 나가지 않는다")
    void 도중_오류면_닫지_않는다() {
        // given
        var state = new FakeStateRepository() {
            @Override
            public Flux<MemberRef> findMemberRefs(String groupId) {
                return Flux.range(0, 2_500).map(i -> MemberRef.user("u%06d".formatted(i)))
                        .concatWith(Flux.error(new IllegalStateException("저장소 실패")));
            }
        };

        // when, then
        StepVerifier.create(new ScimGroupStream(state).group(new GroupHeader("ALL", null, "전 직원"),
                        ScimAttributeProjection.all()))
                .thenConsumeWhile(buffer -> {
                    String text = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return !text.endsWith("]}");
                })
                .verifyError(IllegalStateException.class);
    }

    @Test
    @DisplayName("목록은 조직을 차례로 흘려 쓰고, 그사이 지워진 조직은 건너뛰며 itemsPerPage 는 실제로 쓴 수다")
    void 목록은_지워진_조직을_건너뛴다() throws Exception {
        // given — 목록을 만들 때는 둘이었는데 GONE 은 그사이 지워졌다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("DEV", null, "개발", Set.of(MemberRef.user("kim")))).block();
        var query = new ScimQuery(null, 1, 100, false, ScimAttributeProjection.all());
        List<GroupHeader> headers = List.of(new GroupHeader("DEV", null, "개발"), new GroupHeader("GONE", null, "사라짐"));

        // when
        JsonNode 목록 = JSON.readTree(모은다(new ScimGroupStream(state).list(query, 2, headers)));

        // then
        assertThat(목록.get("schemas").get(0).asText()).isEqualTo(ScimSchemas.LIST_RESPONSE);
        assertThat(목록.get("totalResults").asLong()).isEqualTo(2);
        assertThat(목록.get("startIndex").asLong()).isEqualTo(1);
        assertThat(목록.get("itemsPerPage").asInt()).isEqualTo(1);
        assertThat(목록.get("Resources")).hasSize(1);
        assertThat(목록.get("Resources").get(0).get("id").asText()).isEqualTo("DEV");
        assertThat(목록.get("Resources").get(0).get("members")).hasSize(1);
    }

    @ParameterizedTest(name = "멤버 {0}명")
    @ValueSource(ints = {2 * ScimGroupStream.멤버_묶음 + 1, 2 * ScimGroupStream.멤버_묶음})
    @DisplayName("멤버가 여러 묶음에 걸쳐도 묶음 사이를 쉼표로 이어 완결된 JSON 이 된다 — 모든 멤버가 한 번씩, 차례대로")
    void 여러_묶음도_완결된_JSON_이다(int 멤버_수) throws Exception {
        // given
        var state = new FakeStateRepository() {
            @Override
            public Flux<MemberRef> findMemberRefs(String groupId) {
                return Flux.range(0, 멤버_수).map(i -> MemberRef.user("u%06d".formatted(i)));
            }
        };

        // when
        String 흘려쓴 = 모은다(new ScimGroupStream(state).group(new GroupHeader("ALL", null, "전 직원"),
                ScimAttributeProjection.all()));

        // then
        List<String> 받은_아이디 = new ArrayList<>();
        JSON.readTree(흘려쓴).get("members").forEach(member -> 받은_아이디.add(member.get("value").asText()));
        assertThat(받은_아이디).containsExactlyElementsOf(
                IntStream.range(0, 멤버_수).mapToObj(i -> "u%06d".formatted(i)).toList());
    }

    @Test
    @DisplayName("목록의 첫 조직이 그사이 지워져도 쉼표는 쓴 수를 따른다 — 완결된 JSON, 나머지 둘이 차례로, itemsPerPage 2")
    void 첫_조직이_지워져도_쉼표가_맞다() throws Exception {
        // given — 목록을 만들 때는 셋이었는데 맨 앞 GONE 이 그사이 지워졌다
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("A", null, "가", Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "나", Set.of())).block();
        var query = new ScimQuery(null, 1, 100, false, ScimAttributeProjection.all());
        List<GroupHeader> headers = List.of(new GroupHeader("GONE", null, "사라짐"),
                new GroupHeader("A", null, "가"), new GroupHeader("B", null, "나"));

        // when
        JsonNode 목록 = JSON.readTree(모은다(new ScimGroupStream(state).list(query, 3, headers)));

        // then
        List<String> 받은_아이디 = new ArrayList<>();
        목록.get("Resources").forEach(resource -> 받은_아이디.add(resource.get("id").asText()));
        assertThat(받은_아이디).containsExactly("A", "B");
        assertThat(목록.get("itemsPerPage").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("목록은 길이 0 인 버퍼를 내보내지 않는다 — 구분 쉼표는 조직 앞부분에 붙는다")
    void 목록에_빈_버퍼가_없다() {
        // given
        var state = new FakeStateRepository();
        state.saveGroup(new DirectoryGroup("A", null, "가", Set.of(MemberRef.user("kim")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "나", Set.of())).block();
        var query = new ScimQuery(null, 1, 100, false, ScimAttributeProjection.all());
        List<GroupHeader> headers = List.of(new GroupHeader("A", null, "가"), new GroupHeader("B", null, "나"));

        // when
        List<Integer> 길이들 = new ScimGroupStream(state).list(query, 2, headers)
                .map(buffer -> {
                    int length = buffer.readableByteCount();
                    DataBufferUtils.release(buffer);
                    return length;
                })
                .collectList().block();

        // then
        assertThat(길이들).isNotEmpty().allMatch(length -> length > 0);
    }
}
