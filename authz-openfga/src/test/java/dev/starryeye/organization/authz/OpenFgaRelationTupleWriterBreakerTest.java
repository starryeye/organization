package dev.starryeye.organization.authz;

import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Batch;
import dev.starryeye.organization.authz.OpenFgaRelationTupleWriter.Phase;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.TupleWriteAbortedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenFGA 가 죽은 채로 배치 1,100개를 하나하나 재시도하며 락을 수십 분 쥐지 않는다 (점검 C7, 설계 2026-09-29 §5).
 */
class OpenFgaRelationTupleWriterBreakerTest {

    private static List<Batch> 배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("user" + i, "DEV002"))))
                .toList();
    }

    /** 조직 지우기 단계 배치 — 조직 연결(child)을 지운다 */
    private static List<Batch> 조직_지우기_배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.deletes(List.of(RelationTuple.child("t" + i, "OLD"))))
                .toList();
    }

    /** 조직 쓰기 단계 배치 — 조직 연결(child)을 쓴다 */
    private static List<Batch> 조직_쓰기_배치들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.child("t" + i, "NEW"))))
                .toList();
    }

    @Test
    @DisplayName("배치가 3번 연달아 실패하면 남은 배치를 보내지 않고 멈추며, 그때까지 나간 것과 보내지 않은 것을 함께 넘긴다")
    void 세번_연달아_실패하면_멈춘다() {
        // given — 첫 배치는 나가고, 그 뒤로 OpenFGA 가 죽는다
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 첫_배치만_성공 = batch -> {
            보낸것.add(batch);
            return Mono.just(보낸것.size() == 1 ? batch.succeeded() : batch.failed("연결 거부"));
        };

        // when, then — 호출자는 partial 로 기록 규칙을 지킨다. 나간 첫 배치를 모른 척하면 기준선이 장부와 어긋난다
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(10), 첫_배치만_성공).block())
                .isInstanceOfSatisfying(TupleWriteAbortedException.class, 멈춤 -> {
                    assertThat(멈춤).hasMessageContaining("3번 연달아")
                            .hasMessageContaining("남은 6개")
                            .hasMessageContaining("연결 거부");
                    assertThat(멈춤.partial().written()).hasSize(1);
                    assertThat(멈춤.partial().failures()).as("실패한 3개 + 보내지 않은 6개").hasSize(9)
                            .filteredOn(failure -> failure.reason().equals("연속 실패로 보내지 않음"))
                            .hasSize(6);
                });
        assertThat(보낸것).hasSize(4);
    }

    @Test
    @DisplayName("실패가 연달아 셋이 아니면 끝까지 보내고 실패는 결과에 담는다 — 드문 실패는 지금처럼 PARTIAL 로 넘긴다")
    void 연달아_셋이_아니면_끝까지_보낸다() {
        // given — 실패, 실패, 성공이 되풀이된다. 성공이 끼면 연속 횟수는 처음부터 다시 센다
        AtomicInteger 차례 = new AtomicInteger();
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> 셋째마다_성공 = batch -> {
            보낸것.add(batch);
            return Mono.just(차례.incrementAndGet() % 3 == 0 ? batch.succeeded() : batch.failed("일시 오류"));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치들(9), 셋째마다_성공).block();

        // then
        assertThat(보낸것).hasSize(9);
        assertThat(결과.failures()).hasSize(6);
        assertThat(결과.written()).hasSize(3);
    }

    @Test
    @DisplayName("배치가 셋보다 적으면 모두 실패해도 오류가 아니라 결과의 실패로 남는다 — SCIM 요청 한 건은 대개 여기에 든다")
    void 배치가_적으면_차단기와_무관하다() {
        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(
                배치들(2), batch -> Mono.just(batch.failed("x"))).block();

        // then
        assertThat(결과.failures()).hasSize(2);
    }

    @Test
    @DisplayName("쪼개서 일부라도 살린 배치는 연속 실패로 세지 않는다 — 나쁜 줄이 여기저기 있어도 끝까지 보낸다")
    void 일부라도_살린_배치는_세지_않는다() {
        // given — 한 줄도 못 살린 배치와 일부 살린 배치가 번갈아 온다. 옛 규칙("실패가 하나라도 있으면 셈")이면 세 번째에서 멈춘다
        List<Batch> 배치 = IntStream.range(0, 6)
                .mapToObj(i -> Batch.writes(List.of(
                        RelationTuple.directMember("a" + i, "DEV002"), RelationTuple.directMember("b" + i, "DEV002"))))
                .toList();
        AtomicInteger 차례 = new AtomicInteger();
        List<Batch> 보낸것 = new ArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> {
            보낸것.add(batch);
            if (차례.getAndIncrement() % 2 == 0) {
                return Mono.just(batch.failed("일시 오류"));
            }
            return Mono.just(new TupleWriteResult(Set.of(batch.tuples().get(0)), Set.of(),
                    List.of(new TupleFailure(batch.tuples().get(1), "거절"))));
        };

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send).block();

        // then
        assertThat(보낸것).hasSize(6);
        assertThat(결과.written()).hasSize(3);
    }

    @Test
    @DisplayName("묶음을 설정한 수만큼 동시에 보내되 넘지 않는다 — 지우기 묶음과 쓰기 묶음이 번갈아 와도 서로 기다리지 않는다")
    void 설정한_수만큼_동시에_보내되_넘지_않는다() {
        // given — 지우기 묶음과 쓰기 묶음이 번갈아 온다
        List<Batch> 배치 = IntStream.range(0, 8)
                .mapToObj(i -> i % 2 == 0
                        ? Batch.deletes(List.of(RelationTuple.directMember("d" + i, "DEV002")))
                        : Batch.writes(List.of(RelationTuple.directMember("w" + i, "DEV002"))))
                .toList();
        AtomicInteger 지금 = new AtomicInteger();
        AtomicInteger 최대 = new AtomicInteger();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            최대.accumulateAndGet(지금.incrementAndGet(), Math::max);
            return Mono.delay(Duration.ofMillis(50))
                    .map(tick -> {
                        지금.decrementAndGet();
                        return batch.succeeded();
                    });
        });

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 2).block();

        // then
        assertThat(최대.get()).isEqualTo(2);
        assertThat(결과.deleted()).hasSize(4);
        assertThat(결과.written()).hasSize(4);
    }

    @Test
    @DisplayName("동시에 보내도 차단기는 보낸 순서대로 세어 세 묶음째에서 멈추고, 이미 나간 묶음은 결과를 기다려 센다")
    void 동시에_보내도_순서대로_센다() {
        // given — 쓰기 묶음 여덟 개가 모두 일시 오류다
        List<Batch> 배치 = IntStream.range(0, 8)
                .mapToObj(i -> Batch.writes(List.of(RelationTuple.directMember("u" + i, "DEV002"))))
                .toList();
        AtomicInteger 보낸 = new AtomicInteger();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            보낸.incrementAndGet();
            return Mono.delay(Duration.ofMillis(10)).thenReturn(batch.failed("일시 오류"));
        });

        // when, then — 앞 묶음을 센 뒤에야 다음 묶음이 나가므로, 셋째를 셀 때 나가 있던 것은 넷째~여섯째다
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 4).block())
                .isInstanceOfSatisfying(TupleWriteAbortedException.class, 멈춤 -> {
                    assertThat(멈춤).hasMessageContaining("남은 2개");
                    assertThat(멈춤.partial().failures()).as("실패한 셋 + 이미 나가 실패한 셋 + 보내지 않은 둘").hasSize(8)
                            .filteredOn(failure -> failure.reason().equals("연속 실패로 보내지 않음"))
                            .hasSize(2);
                });
        assertThat(보낸.get()).isEqualTo(6);
    }

    @Test
    @DisplayName("멈출 때 이미 나간 묶음은 결과를 기다려 실제 결과로 세고, 아직 안 나간 묶음만 보내지 않는다 — 반납 뒤에 늦게 떨어지는 쓰기가 없다")
    void 멈출_때_이미_나간_묶음은_결과를_기다려_실제_결과로_센다() {
        // given — 동시 4. 묶음마다 응답을 손으로 준다
        List<Batch> 배치 = 배치들(8);
        List<Sinks.One<TupleWriteResult>> 응답 = new CopyOnWriteArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            Sinks.One<TupleWriteResult> sink = Sinks.one();
            응답.add(sink);
            return sink.asMono();
        });
        CompletableFuture<TupleWriteResult> 끝 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 4).toFuture();
        assertThat(응답).hasSize(4);

        // when — 앞 셋이 실패한다. 첫째·둘째를 세는 사이 다섯째·여섯째가 나가고, 셋째에서 멈춘다
        응답.get(0).tryEmitValue(배치.get(0).failed("연결 거부"));
        응답.get(1).tryEmitValue(배치.get(1).failed("연결 거부"));
        응답.get(2).tryEmitValue(배치.get(2).failed("연결 거부"));
        assertThat(응답).as("멈춘 뒤에는 새로 보내지 않는다").hasSize(6);

        // then — 이미 나간 넷째~여섯째의 결과가 나올 때까지 끝나지 않는다. 넷째가 마지막이다
        응답.get(4).tryEmitValue(배치.get(4).succeeded());
        응답.get(5).tryEmitValue(배치.get(5).succeeded());
        assertThat(끝).as("넷째가 아직 나가 있다").isNotDone();
        응답.get(3).tryEmitValue(배치.get(3).succeeded());

        assertThat(끝).isCompletedExceptionally();
        assertThatThrownBy(끝::join)
                .cause()
                .isInstanceOfSatisfying(TupleWriteAbortedException.class, 멈춤 -> {
                    assertThat(멈춤).hasMessageContaining("남은 2개")
                            .hasMessageContaining("연결 거부");
                    assertThat(멈춤.partial().written()).as("멈춘 뒤 성공해도 멈춤은 풀리지 않고, 성공은 성공으로 센다")
                            .containsExactlyInAnyOrder(
                                    배치.get(3).tuples().get(0), 배치.get(4).tuples().get(0), 배치.get(5).tuples().get(0));
                    assertThat(멈춤.partial().failures()).hasSize(5)
                            .filteredOn(failure -> failure.reason().equals("연속 실패로 보내지 않음"))
                            .extracting(TupleFailure::tuple)
                            .containsExactly(배치.get(6).tuples().get(0), 배치.get(7).tuples().get(0));
                });
        assertThat(응답).as("일곱째·여덟째는 보내지 않는다").hasSize(6);
    }

    @Test
    @DisplayName("단계는 겹치지 않는다 — 앞 단계 묶음이 모두 끝난 뒤에야 다음 단계 묶음이 나가고, 한 단계 안에서는 설정한 수만큼 동시에 나간다")
    void 단계는_겹치지_않고_단계_안에서는_동시에_보낸다() {
        // given — 조직 지우기 셋, 직원 셋, 조직 쓰기 셋. 동시 2
        List<Batch> 배치 = new ArrayList<>(조직_지우기_배치들(3));
        배치.addAll(배치들(3));
        배치.addAll(조직_쓰기_배치들(3));
        AtomicInteger 지금 = new AtomicInteger();
        AtomicInteger 최대 = new AtomicInteger();
        AtomicInteger 끝난 = new AtomicInteger();
        List<Phase> 앞이_안_끝났는데_나간_단계 = new CopyOnWriteArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            최대.accumulateAndGet(지금.incrementAndGet(), Math::max);
            if (끝난.get() < batch.단계().ordinal() * 3) {
                앞이_안_끝났는데_나간_단계.add(batch.단계());
            }
            return Mono.delay(Duration.ofMillis(50))
                    .map(tick -> {
                        지금.decrementAndGet();
                        끝난.incrementAndGet();
                        return batch.succeeded();
                    });
        });

        // when
        TupleWriteResult 결과 = OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 2).block();

        // then
        assertThat(앞이_안_끝났는데_나간_단계).isEmpty();
        assertThat(최대.get()).isEqualTo(2);
        assertThat(결과.deleted()).hasSize(3);
        assertThat(결과.written()).hasSize(6);
    }

    @Test
    @DisplayName("조직 지우기 단계에서 멈추면 뒤따르는 직원·조직 쓰기 묶음은 하나도 보내지 않는다")
    void 조직_지우기에서_멈추면_뒤_단계는_보내지_않는다() {
        // given — 조직 지우기 묶음 셋이 모두 실패한다
        List<Batch> 배치 = new ArrayList<>(조직_지우기_배치들(3));
        배치.addAll(배치들(4));
        배치.addAll(조직_쓰기_배치들(2));
        List<Batch> 보낸것 = new CopyOnWriteArrayList<>();
        // 응답이 늦어야 동시 4 만큼 한꺼번에 나간다 — 단계로 막지 않으면 직원 묶음이 조직 지우기 묶음과 함께 나간다
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            보낸것.add(batch);
            return Mono.delay(Duration.ofMillis(10)).thenReturn(batch.failed("연결 거부"));
        });

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 4).block())
                .isInstanceOfSatisfying(TupleWriteAbortedException.class, 멈춤 -> {
                    assertThat(멈춤).hasMessageContaining("남은 6개");
                    assertThat(멈춤.partial().failures()).hasSize(9);
                });
        assertThat(보낸것).extracting(Batch::단계).containsOnly(Phase.조직_지우기).hasSize(3);
    }

    @Test
    @DisplayName("직원 단계에서 멈추면 뒤따르는 조직 쓰기 묶음은 하나도 보내지 않는다 — 앞 단계에서 나간 지우기는 결과에 남는다")
    void 직원에서_멈추면_조직_쓰기는_보내지_않는다() {
        // given — 조직 지우기 둘은 성공하고, 직원 묶음 셋이 모두 실패한다
        List<Batch> 배치 = new ArrayList<>(조직_지우기_배치들(2));
        배치.addAll(배치들(3));
        배치.addAll(조직_쓰기_배치들(3));
        List<Batch> 보낸것 = new CopyOnWriteArrayList<>();
        Function<Batch, Mono<TupleWriteResult>> send = batch -> Mono.defer(() -> {
            보낸것.add(batch);
            return Mono.delay(Duration.ofMillis(10))
                    .thenReturn(batch.단계() == Phase.직원 ? batch.failed("연결 거부") : batch.succeeded());
        });

        // when, then
        assertThatThrownBy(() -> OpenFgaRelationTupleWriter.보내되_연속_실패면_멈춘다(배치, send, 4).block())
                .isInstanceOfSatisfying(TupleWriteAbortedException.class, 멈춤 -> {
                    assertThat(멈춤).hasMessageContaining("남은 3개");
                    assertThat(멈춤.partial().deleted()).hasSize(2);
                    assertThat(멈춤.partial().failures()).hasSize(6);
                });
        assertThat(보낸것).hasSize(5).extracting(Batch::단계).doesNotContain(Phase.조직_쓰기);
    }
}
