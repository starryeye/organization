package dev.starryeye.organization.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.Timestamped;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
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

    /**
     * 멤버를 이만큼씩 묶어 버퍼 하나로 내보낸다. 멤버 하나가 약 40~60바이트라 묶음 하나가 5KB 안팎이다.
     * 묶음은 Netty 보내기 창(버퍼 128개, {@code reactor.netty.send.maxPrefetchSize})에 견줘 작아야 한다 —
     * 느린 클라이언트가 받아 가지 않아도 보내기 창 몫이 조직이 얼마나 크든 약 0.6MB(128 x 5KB)로 묶인다.
     * 묶음이 1,000개(약 50KB)면 10만 명 응답 전체(약 6MB)가 연결 하나에 쌓일 수 있다. Netty 속성은 전역이라 건드리지 않는다.
     * 상수는 이 빌드의 Reactor Netty 1.2.18(Spring Boot 3.5.16 → reactor-bom 2024.0.18)에서 확인했다.
     *
     * <p>0.6MB 는 연결이 쥐는 양의 일부일 뿐이다. 멈춘 연결은 이 위에 DynamoDB 쪽 둘(읽는 중인 쪽과 앞서 받은 쪽, 각각 투영 전 1MB 한도 —
     * SDK 객체로 들면 수 MB)을 더 쥐어 대략 5~10MB 로 셈한다(힙을 재지는 않았다). DynamoDB 쪽 둘은 이 묶음 크기와 별개이고
     * 조직 크기와도 무관하다(설계 2026-10-06 §10).
     */
    static final int 멤버_묶음 = 100;

    private static final DataBufferFactory BUFFERS = DefaultDataBufferFactory.sharedInstance;

    private final DirectoryStateRepository state;
    /** 머리(이름표와 시각)를 읽는다 — 응답의 meta 가 생성·변경 시각을 싣는다(설계 2026-10-09 §4.3). 멤버 줄은 상태 저장소에서 읽는다. */
    private final DirectoryQueryRepository query;

    ScimGroupStream(DirectoryStateRepository state, DirectoryQueryRepository query) {
        this.state = state;
        this.query = query;
    }

    /**
     * 조직 하나. 헤더는 호출자가 이미 읽었다 — 없는 조직이면 응답을 쓰기 전에 404 를 냈다.
     * 이 부품은 늘 {@code members} 를 쓴다 — 부른 쪽이 {@code projection.includes("members")} 일 때만 부른다. 아니면 이름표 트리 응답을 쓴다.
     */
    Flux<DataBuffer> group(Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        return group("", header, projection);
    }

    /** {@code prefix} 는 응답 앞에 붙는 글자다 — 목록에서 앞 조직과 이을 쉼표. 빈 버퍼를 따로 내보내지 않으려고 앞부분에 합친다. */
    private Flux<DataBuffer> group(String prefix, Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        return Flux.concat(
                Mono.fromSupplier(() -> buffer(prefix + head(header, projection))),
                members(header.value().id(), projection),
                Mono.fromSupplier(() -> buffer("]}")));
    }

    /**
     * 목록(RFC 7644 §3.4.2). 조직을 하나씩 차례로 쓴다 — 앞 조직을 다 쓰기 전에 뒤 조직을 미리 읽지 않는다(미리 읽으면 큰 조직 하나를 통째로 쥔다).
     * 목록을 만든 뒤 지워진 조직은 헤더가 비어 건너뛴다. 조직 사이의 쉼표는 입력 순번이 아니라 실제로 쓴 수를 따른다.
     * {@link #group} 처럼 늘 {@code members} 를 쓴다 — 부른 쪽이 {@code projection.includes("members")} 일 때만 부른다. 아니면 이름표 트리 응답을 쓴다.
     */
    Flux<DataBuffer> list(ScimQuery request, long totalResults, List<Timestamped<GroupHeader>> headers) {
        return Flux.defer(() -> {
            AtomicInteger 쓴_수 = new AtomicInteger();
            Flux<DataBuffer> resources = Flux.fromIterable(headers)
                    .concatMap(listed -> query.findGroupHeader(listed.value().id())
                            .flatMapMany(header -> group(쓴_수.getAndIncrement() == 0 ? "" : ",", header, request.projection())));
            return Flux.concat(
                    Mono.fromSupplier(() -> buffer(listHead(request, totalResults))),
                    resources,
                    Mono.fromSupplier(() -> buffer("],\"itemsPerPage\":" + 쓴_수.get() + "}")));
        });
    }

    private static String head(Timestamped<GroupHeader> header, ScimAttributeProjection projection) {
        // 헤더로 만든 조직에는 members 가 없다(null 은 쓰지 않는다). id·schemas 는 늘 남아 객체가 비지 않는다
        String json = ScimJson.string(projection.apply(ScimJson.tree(ScimMapper.toScimGroup(header.value(), header.times()))));
        return json.substring(0, json.length() - 1) + ",\"members\":[";
    }

    private static String listHead(ScimQuery request, long totalResults) {
        ObjectNode head = JsonNodeFactory.instance.objectNode();
        head.putArray("schemas").add(ScimSchemas.LIST_RESPONSE);
        head.put("totalResults", totalResults);
        head.put("startIndex", request.startIndex());
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
