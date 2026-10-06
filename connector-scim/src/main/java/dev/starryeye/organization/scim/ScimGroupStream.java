package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 멤버를 싣는 조직 응답을 흘려 쓴다(설계 2026-10-06 §4, 점검 P5). 멤버 줄을 DynamoDB 한 쪽씩 이어 읽어 JSON 으로 바로 내보낸다 —
 * 10만 명 조직도 메모리에 통째로 들지 않는다. 앞부분(이름표·투영)은 트리로 만들고 {@code members} 배열만 흘린다.
 *
 * <p>JSON 객체의 필드 순서에는 뜻이 없다(RFC 8259 §4). 그래서 {@code members} 는 맨 뒤에, 목록의 {@code itemsPerPage} 는 실제로 쓴 수를
 * 안 뒤 맨 끝에 쓴다. 첫 바이트가 나간 뒤 저장소가 실패하면 닫는 괄호를 쓰지 않고 오류로 끝난다 — 상태 코드를 바꿀 수 없으니 연결이 끊긴다(§10).
 */
final class ScimGroupStream {

    /** 멤버를 이만큼씩 묶어 버퍼 하나로 내보낸다. 멤버 하나가 약 40~60바이트라 묶음 하나가 수십 KB 다. */
    static final int 멤버_묶음 = 1_000;

    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private final DirectoryStateRepository state;

    ScimGroupStream(DirectoryStateRepository state) {
        this.state = state;
    }

    /** 조직 하나. 헤더는 호출자가 이미 읽었다 — 없는 조직이면 응답을 쓰기 전에 404 를 냈다. */
    Flux<DataBuffer> group(GroupHeader header, ScimAttributeProjection projection) {
        return Flux.concat(
                Mono.fromSupplier(() -> buffer(head(header, projection))),
                members(header.id(), projection),
                Mono.fromSupplier(() -> buffer("]}")));
    }

    /**
     * 목록(RFC 7644 §3.4.2). 조직을 하나씩 차례로 쓴다 — 앞 조직을 다 쓰기 전에 뒤 조직을 미리 읽지 않는다(미리 읽으면 큰 조직 하나를 통째로 쥔다).
     * 목록을 만든 뒤 지워진 조직은 헤더가 비어 건너뛴다.
     */
    Flux<DataBuffer> list(ScimQuery query, long totalResults, List<GroupHeader> headers) {
        return Flux.defer(() -> {
            AtomicInteger 쓴_수 = new AtomicInteger();
            Flux<DataBuffer> resources = Flux.fromIterable(headers)
                    .concatMap(listed -> state.findGroupHeader(listed.id())
                            .flatMapMany(header -> Flux.concat(
                                    Mono.fromSupplier(() -> buffer(쓴_수.getAndIncrement() == 0 ? "" : ",")),
                                    group(header, query.projection()))));
            return Flux.concat(
                    Mono.fromSupplier(() -> buffer(listHead(query, totalResults))),
                    resources,
                    Mono.fromSupplier(() -> buffer("],\"itemsPerPage\":" + 쓴_수.get() + "}")));
        });
    }

    private static String head(GroupHeader header, ScimAttributeProjection projection) {
        // 헤더로 만든 조직에는 members 가 없다(null 은 쓰지 않는다). id·schemas 는 늘 남아 객체가 비지 않는다
        String json = ScimJson.string(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header))));
        return json.substring(0, json.length() - 1) + ",\"members\":[";
    }

    private static String listHead(ScimQuery query, long totalResults) {
        ObjectNode head = JsonNodeFactory.instance.objectNode();
        head.putArray("schemas").add(ScimSchemas.LIST_RESPONSE);
        head.put("totalResults", totalResults);
        head.put("startIndex", query.startIndex());
        String json = ScimJson.string(head);
        return json.substring(0, json.length() - 1) + ",\"Resources\":[";
    }

    private Flux<DataBuffer> members(String groupId, ScimAttributeProjection projection) {
        return state.findMemberRefs(groupId)
                .map(ref -> ScimJson.string(projection.applyToElement("members",
                        ScimJson.tree(ScimMapper.toScimMember(ref)))))
                .buffer(멤버_묶음)
                .index()
                .map(묶음 -> buffer((묶음.getT1() == 0 ? "" : ",") + String.join(",", 묶음.getT2())));
    }

    private static DataBuffer buffer(String json) {
        return BUFFERS.wrap(json.getBytes(StandardCharsets.UTF_8));
    }
}
