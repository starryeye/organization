package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.WriteRequestDeletes;
import dev.openfga.sdk.api.model.WriteRequestWrites;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 델타를 OpenFGA 에 반영한다. 쓰기 전용 어댑터라 조회는 하지 않는다 — 인가 판정이
 * 필요하면 {@code RelationTupleChecker} 를 쓴다.
 *
 * <p>멱등 옵션(on_duplicate / on_missing = IGNORE)을 항상 켜므로 중복 write 나
 * 없는 튜플 delete 로 배치가 통째로 실패하지 않는다. 튜플 단위 보상 로직이 필요 없는 이유다.
 * 이 옵션은 OpenFGA 서버 v1.10.0 이상에서만 동작한다.
 *
 * <p>배치는 세 단계로 나가고 단계는 겹치지 않는다 — 조직 연결 지우기, 직원(지우기와 쓰기를 직원별 한 요청으로), 조직 연결 쓰기(점검 S20, 설계
 * 2026-10-05 §5). 인가 모델이 단조라서, 어느 단계에서 멈춰도 앞 상태와 뒤 상태 어느 쪽도 주지 않는 권한을 가진 사람이 없다. 까닭과 한계는
 * {@link #batchesFor} 에 있다.
 */
@Slf4j
@RequiredArgsConstructor
public class OpenFgaRelationTupleWriter implements RelationTupleWriter {

    private final StoreBootstrapper bootstrapper;
    private final OpenFgaProperties properties;

    /**
     * 이만큼 연달아 실패하면(배치마다 재시도한 뒤에도) 남은 배치를 보내지 않는다(점검 C7). 설정으로 두지 않는다 — 운영에서 바꿀
     * 이유가 보이면 그때 올린다.
     */
    static final int 연속_실패_한도 = 3;

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        if (delta.isEmpty()) {
            return Mono.just(TupleWriteResult.empty());
        }

        List<Batch> batches = batchesFor(delta);

        return bootstrapper.resolveStore()
                .then(보내되_연속_실패면_멈춘다(batches, this::applyBatch, properties.getRequestConcurrency()));
    }

    /**
     * 같은 {@link Phase 단계} 안에서 {@code 동시}개까지 동시에 보내고, 결과는 보낸 순서대로 센다(설계 2026-10-02 §4.3). 단계는 겹치지 않는다 —
     * 앞 단계의 묶음이 모두 끝난 뒤에야 다음 단계를 보낸다(순서의 까닭은 {@link #batchesFor}). {@value #연속_실패_한도}개가 연달아 실패하면 남은
     * 배치를 보내지 않고 {@link TupleWriteAbortedException} 으로 끝낸다(점검 C7, 설계 §5). 그래서 앞 단계에서 멈추면 뒤 단계는 하나도 나가지 않는다.
     * 그 {@code partial} 은 모든 묶음의 결과다 — 실제로 나간 묶음의 결과에 보내지 않은 묶음을 실패로 더한 것이다. 호출자가 이미 나간 쓰기로 기록 규칙을
     * 지킨다.
     *
     * <p>멈추면 이미 나간 묶음(최대 동시 수 − 1)은 결과를 기다려 실제 결과로 세고, 아직 안 나간 묶음만 보내지 않는다. 그 묶음들의 재시도
     * 시간만큼 더 걸리고, 그동안 하트비트가 리스를 지킨다. 그래서 락을 반납한 뒤에 늦게 떨어지는 쓰기가 없다 — 남아 있으면 상태가 모르는
     * 튜플이 생겨 다음 요청이 지우지 못한다. 멈춘 뒤 이미 나간 묶음이 성공해도 멈춤은 풀리지 않는다.
     *
     * <p>OpenFGA 가 느리거나 죽으면 배치마다 재시도를 거친 뒤 실패로 넘어가는데, 10만 명 재적재는 배치가 약 1,100개라 전부 그렇게 돌면
     * 수십 분 동안 락을 쥔다. 연달아 실패하는 것은 대개 한 배치가 아니라 OpenFGA 의 문제다. 드문 실패 한두 건은 지금처럼 결과의
     * {@code failures} 로 넘겨 PARTIAL 이 되게 둔다. 배치가 셋보다 적은 쓰기는 해당이 없다 — SCIM 요청 한 건은 대개 여기에 든다.
     * 멤버가 200명 넘게 바뀌는 조직 PATCH·PUT 은 예외로, 멈추면 5xx 가 되고 IdP 재시도가 Check 기준선으로 수렴한다.
     *
     * <p>패키지 전용 — 멈추는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     *
     * <p><b>세는 것은 "한 줄도 못 살린 묶음"뿐이다(설계 2026-09-30 §6.3).</b> 거절된 묶음은 쪼개 보내므로 일부라도 반영됐으면 OpenFGA 가 죽은 것이
     * 아니다 — 셈을 처음부터 다시 한다. 인가 모델이 통째로 없어 모든 줄이 거절되면 세 묶음 뒤에 멈춘다.
     */
    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send) {
        return 보내되_연속_실패면_멈춘다(batches, send, 1);
    }

    static Mono<TupleWriteResult> 보내되_연속_실패면_멈춘다(List<Batch> batches,
                                                     Function<Batch, Mono<TupleWriteResult>> send, int 동시) {
        int 동시_수 = Math.max(1, 동시);
        return Mono.defer(() -> {
            AtomicInteger 연속_실패 = new AtomicInteger();
            AtomicBoolean 멈춤 = new AtomicBoolean();
            AtomicReference<String> 멈춘_사유 = new AtomicReference<>();
            AtomicInteger 보내지_않은_수 = new AtomicInteger();
            Function<Batch, Mono<TupleWriteResult>> 멈췄으면_보내지_않는다 = batch -> Mono.defer(() -> {
                if (멈춤.get()) {
                    보내지_않은_수.incrementAndGet();
                    return Mono.just(batch.failed("연속 실패로 보내지 않음"));
                }
                return send.apply(batch);
            });
            return Flux.fromIterable(batches)
                    // 단계(조직 지우기 → 직원 → 조직 쓰기)는 겹치지 않는다 — 한 단계의 묶음이 모두 끝난 뒤에야 다음 단계가 나간다. 단계 안에서만 동시에 보낸다
                    .windowUntilChanged(Batch::단계)
                    .concatMap(같은_단계 -> 같은_단계.flatMapSequential(멈췄으면_보내지_않는다, 동시_수))
                    // 결과는 보낸 순서대로 센다. 앞 묶음을 센 뒤에야 다음 묶음이 나가므로, 멈춤 표시는 다음 묶음이 나가기 전에 선다
                    .reduce(TupleWriteResult.empty(), (누적, result) -> {
                        if (!멈춤.get()) {
                            if (!한_줄도_못_살렸다(result)) {
                                연속_실패.set(0);
                            } else if (연속_실패.incrementAndGet() >= 연속_실패_한도) {
                                멈춘_사유.set(result.failures().get(0).reason());
                                멈춤.set(true);
                            }
                        }
                        return merge(누적, result);
                    })
                    .flatMap(누적 -> 멈춤.get()
                            ? Mono.<TupleWriteResult>error(new TupleWriteAbortedException(
                                    "OpenFGA 쓰기 배치가 %d번 연달아 실패해 남은 %d개 배치를 보내지 않고 멈췄다 — 마지막 오류: %s"
                                            .formatted(연속_실패_한도, 보내지_않은_수.get(), 멈춘_사유.get()),
                                    누적))
                            : Mono.just(누적));
        });
    }

    /** 묶음에서 한 줄도 반영되지 못했다 — 일시 오류로 다 실패했거나, 쪼개도 전부 거절됐다. OpenFGA 가 죽었다는 신호다. */
    private static boolean 한_줄도_못_살렸다(TupleWriteResult result) {
        return result.hasFailure() && result.written().isEmpty() && result.deleted().isEmpty();
    }

    /**
     * 델타를 배치로 나눈다(설계 2026-10-05 §5, 점검 S20). 배치는 세 {@link Phase 단계}로 나가고 단계는 겹치지 않는다.
     * <ol>
     *   <li>조직 지우기 — 튜플의 {@code user} 칸이 조직({@code group:})인 지우기, 곧 상위 연결({@code child})을 지우는 줄을 크기로만 담는다.</li>
     *   <li>직원 — {@code user} 칸이 직원인 지우기와 쓰기를 대상(직원)별로 모으고, 한 대상의 묶음을 쪼개지 않고 배치에 담는다. 배치 하나가 Write
     *       요청 하나라 OpenFGA 가 원자적으로 반영한다 — 옮기는 직원은 옛 조직에서 새 조직으로 한 번에 넘어간다. 한도({@code write-batch-size})는
     *       지우기와 쓰기를 합친 수다. 한 대상의 변경이 한도보다 많으면 그 대상만 여러 배치로 나눈다 — 그 대상은 원자적이지 않다(설계 §11).
     *       대상 이름순이라 같은 델타는 같은 배치를 만든다.</li>
     *   <li>조직 쓰기 — 조직인 쓰기를 크기로만 담는다.</li>
     * </ol>
     *
     * <p>단계를 이 순서로 나누는 까닭: 인가 모델이 단조({@code member: direct_member or member from child})라서, 어느 단계에서 멈춰도 앞 상태와 뒤 상태
     * 어느 쪽도 주지 않는 권한을 가진 사람이 없게 하려는 것이다. 1단계 뒤 조직 그래프는 (앞 ∩ 뒤)이고, 2단계 동안 각 직원의 줄은 모두 앞이거나 모두 뒤라 권한이
     * 앞 상태의 것 이하이거나 뒤 상태의 것 이하이며, 3단계에서는 모든 직원이 이미 뒤에 있고 그래프는 뒤를 향해 자라기만 한다. 이름순으로 한꺼번에 보내면
     * 조직 C 의 새 상위 연결이 C 에서 빠지는 직원의 지우기보다 먼저 나가(group: 이 user: 보다 앞선다) 그 직원이 잠시 새 상위의 구성원이 되고, 그
     * 상태로 멈추면 다음 동기화까지 남는다. 두 조직이 상위를 맞바꿀 때 순환이 쓰이는 일도 없다. 조직의 상위를 바꾸는 줄은 1·3단계로 갈려 한 배치가
     * 아니다 — 그 사이 구성원이 새 상위의 권한을 얻기 전에 옛 상위의 권한을 잠시 잃는데, 줄어드는 쪽이라 새지 않는다.
     *
     * <p>한 배치가 일시 오류로 실패하면 그 배치는 통째로 반영되지 않는다. 400 거절이면 {@link #쪼개며_보낸다} 가 거절된 배치를 대상 경계로 반씩 나눠
     * 다시 보내 나쁜 줄을 한 줄까지 좁히므로, 그 직원의 요청도 줄 단위로 나뉠 수 있다 — 그때는 원자적이지 않다.
     *
     * <p>한 델타의 쓰기와 지우기는 겹치지 않는다(만드는 쪽이 지키는 약속). 겹치면 같은 줄이 쓰이고 지워지므로 {@link IllegalArgumentException} 으로
     * 거부한다. 패키지 전용 — 배치 짜기를 단위 테스트로 고정한다.
     */
    List<Batch> batchesFor(TupleDelta delta) {
        겹침을_거부한다(delta);
        int 한도 = properties.getWriteBatchSize();

        List<Batch> batches = new ArrayList<>();
        batches.addAll(크기로_담는다(줄들(delta.toDelete(), true), 한도, Phase.조직_지우기));
        batches.addAll(직원_배치들(대상별(줄들(delta.toDelete(), false)), 대상별(줄들(delta.toWrite(), false)), 한도));
        batches.addAll(크기로_담는다(줄들(delta.toWrite(), true), 한도, Phase.조직_쓰기));
        return batches;
    }

    private static void 겹침을_거부한다(TupleDelta delta) {
        Set<RelationTuple> 겹침 = new HashSet<>(delta.toWrite());
        겹침.retainAll(delta.toDelete());
        if (!겹침.isEmpty()) {
            throw new IllegalArgumentException("한 델타의 쓰기와 지우기가 겹친다(%d줄) — 예: %s"
                    .formatted(겹침.size(), 겹침.iterator().next()));
        }
    }

    /** 조직이 대상인 줄(또는 아닌 줄)만 정렬해서 모은다. 정렬해서 배치가 결정적이다 */
    private static List<RelationTuple> 줄들(Set<RelationTuple> tuples, boolean 조직) {
        return tuples.stream()
                .filter(tuple -> 조직인가(tuple) == 조직)
                .sorted(줄_순서)
                .toList();
    }

    private static boolean 조직인가(RelationTuple tuple) {
        return tuple.user().startsWith(RelationTuple.GROUP_TYPE + ":");
    }

    private static final Comparator<RelationTuple> 줄_순서 = Comparator.comparing(RelationTuple::user)
            .thenComparing(RelationTuple::relation)
            .thenComparing(RelationTuple::object);

    /** 조직 단계 — 대상과 상관없이 한도씩 자른다. 조직 지우기는 지우기만, 조직 쓰기는 쓰기만 담는다 */
    private static List<Batch> 크기로_담는다(List<RelationTuple> 줄들, int 한도, Phase phase) {
        List<Batch> batches = new ArrayList<>();
        for (int i = 0; i < 줄들.size(); i += 한도) {
            List<RelationTuple> 조각 = List.copyOf(줄들.subList(i, Math.min(i + 한도, 줄들.size())));
            batches.add(phase == Phase.조직_지우기
                    ? new Batch(List.of(), 조각, phase)
                    : new Batch(조각, List.of(), phase));
        }
        return batches;
    }

    /** 직원 단계 — 한 대상의 지우기와 쓰기를 한 배치에 담고, 대상 묶음은 쪼개지 않는다. 한도를 넘는 대상만 나눈다 */
    private static List<Batch> 직원_배치들(Map<String, List<RelationTuple>> 지울것, Map<String, List<RelationTuple>> 쓸것, int 한도) {
        SortedSet<String> 대상들 = new TreeSet<>(지울것.keySet());
        대상들.addAll(쓸것.keySet());

        List<Batch> batches = new ArrayList<>();
        List<RelationTuple> 쓰기 = new ArrayList<>();
        List<RelationTuple> 지우기 = new ArrayList<>();
        for (String 대상 : 대상들) {
            List<RelationTuple> 대상_지우기 = 지울것.getOrDefault(대상, List.of());
            List<RelationTuple> 대상_쓰기 = 쓸것.getOrDefault(대상, List.of());
            int 크기 = 대상_지우기.size() + 대상_쓰기.size();
            if (!쓰기.isEmpty() || !지우기.isEmpty()) {
                if (쓰기.size() + 지우기.size() + 크기 > 한도) {
                    batches.add(new Batch(List.copyOf(쓰기), List.copyOf(지우기), Phase.직원));
                    쓰기.clear();
                    지우기.clear();
                }
            }
            if (크기 > 한도) {
                Batch 대상_전체 = new Batch(대상_쓰기, 대상_지우기, Phase.직원);
                for (int i = 0; i < 크기; i += 한도) {
                    batches.add(대상_전체.구간(i, Math.min(i + 한도, 크기)));
                }
                continue;
            }
            지우기.addAll(대상_지우기);
            쓰기.addAll(대상_쓰기);
        }
        if (!쓰기.isEmpty() || !지우기.isEmpty()) {
            batches.add(new Batch(List.copyOf(쓰기), List.copyOf(지우기), Phase.직원));
        }
        return batches;
    }

    /** 이미 정렬된 줄들을 대상별로 모은다 */
    private static Map<String, List<RelationTuple>> 대상별(List<RelationTuple> 줄들) {
        Map<String, List<RelationTuple>> 결과 = new TreeMap<>();
        줄들.forEach(tuple -> 결과.computeIfAbsent(tuple.user(), k -> new ArrayList<>()).add(tuple));
        return 결과;
    }

    private Mono<TupleWriteResult> applyBatch(Batch batch) {
        return 쪼개며_보낸다(batch, this::보낸다, OpenFgaErrors::거절인가);
    }

    /** 한 배치를 보낸다. 일시 오류만 다시 시도한다 — 거절(400)은 다시 보내도 같다. */
    private Mono<Void> 보낸다(Batch batch) {
        return Mono.fromFuture(() -> {
                    try {
                        return bootstrapper.client().write(toRequest(batch), writeOptions());
                    } catch (Exception e) {
                        throw new IllegalStateException("OpenFGA write 호출 실패", e);
                    }
                })
                .retryWhen(OpenFgaErrors.일시_오류만_다시(properties.getMaxRetries()))
                .then();
    }

    /**
     * 배치를 보내고, OpenFGA 가 거절하면(400) 반으로 나눠 다시 보낸다 — 한 줄까지 좁혀도 거절되면 그 줄만 실패로 남긴다(점검 M16,
     * 설계 2026-09-30 §6.2). 요청 하나가 원자적이라 줄 하나가 걸리면 같은 배치 100줄이 함께 실패했고, 다시 돌려도 같은 99명이 빠졌다.
     * 일시 오류(재시도 뒤에도 실패)는 쪼개지 않는다 — 쪼개도 같이 실패한다.
     *
     * <p>패키지 전용 — 쪼개는 규칙을 OpenFGA 없이 단위 테스트로 고정한다.
     */
    static Mono<TupleWriteResult> 쪼개며_보낸다(Batch batch, Function<Batch, Mono<Void>> send,
                                            Predicate<Throwable> 거절인가) {
        return send.apply(batch)
                .thenReturn(batch.succeeded())
                .onErrorResume(error -> {
                    if (거절인가.test(error) && batch.size() > 1) {
                        log.warn("OpenFGA 가 배치 {}건을 거절했다 — 반으로 나눠 다시 보낸다: {}",
                                batch.size(), rootMessage(error));
                        return Flux.fromIterable(batch.halves())
                                .concatMap(half -> 쪼개며_보낸다(half, send, 거절인가))
                                .reduce(TupleWriteResult.empty(), OpenFgaRelationTupleWriter::merge);
                    }
                    if (거절인가.test(error)) {
                        // 한 줄까지 좁혔는데도 거절됐다 — 이 줄만의 문제이지 OpenFGA 가 죽은 게 아니므로 스택 트레이스 없이 남긴다
                        log.warn("OpenFGA 가 한 줄을 거절했다 — 실패로 남긴다: {} ({})",
                                batch.tuples().get(0), rootMessage(error));
                        return Mono.just(batch.failed(rootMessage(error)));
                    }
                    log.error("배치 {}건 적용 실패", batch.size(), error);
                    return Mono.just(batch.failed(rootMessage(error)));
                });
    }

    /** 패키지 전용 — 한 요청이 지우기와 쓰기를 함께 담는지 단위 테스트로 고정한다 */
    ClientWriteRequest toRequest(Batch batch) {
        ClientWriteRequest request = new ClientWriteRequest();
        if (!batch.deletes().isEmpty()) {
            request.deletes(batch.deletes().stream()
                    .map(tuple -> new ClientTupleKeyWithoutCondition()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object()))
                    .toList());
        }
        if (!batch.writes().isEmpty()) {
            request.writes(batch.writes().stream()
                    .map(tuple -> new ClientTupleKey()
                            .user(tuple.user())
                            .relation(tuple.relation())
                            ._object(tuple.object()))
                    .toList());
        }
        return request;
    }

    /**
     * 멱등 옵션. 이것이 없으면 rebuild 와 재실행이 배치 단위로 통째로 실패한다. 요청 하나가 트랜잭션 하나여야 지우기와 쓰기가 원자적이다 —
     * SDK 0.9.11 의 기본값이지만 못박는다(꺼지면 SDK 가 줄마다 나눠 보낸다).
     */
    private ClientWriteOptions writeOptions() {
        return new ClientWriteOptions()
                .transactions(true)
                .onDuplicate(WriteRequestWrites.OnDuplicateEnum.IGNORE)
                .onMissing(WriteRequestDeletes.OnMissingEnum.IGNORE);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    private static TupleWriteResult merge(TupleWriteResult a, TupleWriteResult b) {
        Set<RelationTuple> written = new HashSet<>(a.written());
        written.addAll(b.written());
        Set<RelationTuple> deleted = new HashSet<>(a.deleted());
        deleted.addAll(b.deleted());
        List<TupleFailure> failures = new ArrayList<>(a.failures());
        failures.addAll(b.failures());
        return new TupleWriteResult(written, deleted, failures);
    }

    /**
     * 배치가 나가는 단계(점검 S20, 설계 2026-10-05 §5). 단계는 이 순서로 나가고 겹치지 않는다 — 한 단계 안의 배치는 동시에 나갈 수 있다.
     * 단계를 나눈 까닭은 {@link #batchesFor} 에 있다.
     */
    enum Phase {
        /** 조직({@code group:})이 대상인 지우기 — 상위 연결을 먼저 뺀다 */
        조직_지우기,
        /** 직원이 대상인 지우기와 쓰기 — 한 직원의 묶음은 한 요청이다 */
        직원,
        /** 조직이 대상인 쓰기 — 새 상위 연결을 마지막에 쓴다 */
        조직_쓰기
    }

    /**
     * 패키지 전용. 한 Write 요청이다 — 쓰기와 지우기를 함께 담는다(설계 2026-10-05 §5). {@code 단계}는 {@link #batchesFor} 가 정하고 {@link #halves}
     * 와 {@link #구간} 이 물려받는다.
     *
     * <p>단계를 주지 않는 생성자와 {@link #writes}·{@link #deletes} 는 줄에서 단계를 짐작한다 — 지우기만 있고 모두 조직이 대상이면 조직 지우기, 쓰기만
     * 있고 모두 조직이 대상이면 조직 쓰기, 나머지(직원이 대상이거나 섞인 것)는 직원이다. {@link #batchesFor} 는 이 길을 쓰지 않는다.
     */
    record Batch(List<RelationTuple> writes, List<RelationTuple> deletes, Phase 단계) {

        Batch(List<RelationTuple> writes, List<RelationTuple> deletes) {
            this(writes, deletes, 단계를_짐작한다(writes, deletes));
        }

        static Batch writes(List<RelationTuple> tuples) {
            return new Batch(List.copyOf(tuples), List.of());
        }

        static Batch deletes(List<RelationTuple> tuples) {
            return new Batch(List.of(), List.copyOf(tuples));
        }

        private static Phase 단계를_짐작한다(List<RelationTuple> writes, List<RelationTuple> deletes) {
            if (!writes.isEmpty() && !deletes.isEmpty()) {
                return Phase.직원;
            }
            List<RelationTuple> 줄들 = writes.isEmpty() ? deletes : writes;
            if (줄들.isEmpty() || !줄들.stream().allMatch(OpenFgaRelationTupleWriter::조직인가)) {
                return Phase.직원;
            }
            return writes.isEmpty() ? Phase.조직_지우기 : Phase.조직_쓰기;
        }

        int size() {
            return writes.size() + deletes.size();
        }

        /** 지우기 다음 쓰기. 로그와 쪼개기에 쓴다 */
        List<RelationTuple> tuples() {
            List<RelationTuple> 전부 = new ArrayList<>(deletes);
            전부.addAll(writes);
            return 전부;
        }

        TupleWriteResult succeeded() {
            return new TupleWriteResult(Set.copyOf(writes), Set.copyOf(deletes), List.of());
        }

        TupleWriteResult failed(String reason) {
            return new TupleWriteResult(Set.of(), Set.of(),
                    tuples().stream().map(tuple -> new TupleFailure(tuple, reason)).toList());
        }

        /**
         * 반으로 나눈다 — 거절된 배치에서 나쁜 줄을 찾아 좁힐 때 쓴다(설계 2026-09-30 §6.2). 대상 묶음 경계로 나눠 옮기는 직원의 지우기와 쓰기가 갈라지지 않게 하고,
         * 대상이 하나뿐이면 줄 단위로 나눈다(한 줄까지 좁혀야 나쁜 줄을 찾는다). 쓰기인지 지우기인지는 줄이 든 목록으로 가른다 — 줄의 내용으로 짐작하지 않는다.
         */
        List<Batch> halves() {
            List<String> 대상들 = tuples().stream().map(RelationTuple::user).distinct().toList();
            if (대상들.size() > 1) {
                Set<String> 앞대상 = Set.copyOf(대상들.subList(0, 대상들.size() / 2));
                return List.of(대상만(앞대상::contains), 대상만(대상 -> !앞대상.contains(대상)));
            }
            int mid = size() / 2;
            return List.of(구간(0, mid), 구간(mid, size()));
        }

        private Batch 대상만(Predicate<String> 대상인가) {
            return new Batch(
                    writes.stream().filter(t -> 대상인가.test(t.user())).toList(),
                    deletes.stream().filter(t -> 대상인가.test(t.user())).toList(),
                    단계);
        }

        /** 지우기 다음 쓰기로 이어 붙인 줄들의 {@code [from, to)} 구간 — 쓰기인지 지우기인지는 자리로 정한다 */
        Batch 구간(int from, int to) {
            int 지우기_수 = deletes.size();
            return new Batch(
                    List.copyOf(writes.subList(Math.max(from, 지우기_수) - 지우기_수, Math.max(to, 지우기_수) - 지우기_수)),
                    List.copyOf(deletes.subList(Math.min(from, 지우기_수), Math.min(to, 지우기_수))),
                    단계);
        }
    }
}
