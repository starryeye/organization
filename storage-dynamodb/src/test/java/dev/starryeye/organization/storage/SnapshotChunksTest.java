package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotChunksTest {

    private static Set<RelationTuple> 튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV" + (i % 7)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("튜플을 압축했다가 풀면 그대로다 — 한글 조직코드와 하위 조직 튜플도")
    void 압축했다가_풀면_그대로다() throws IOException {
        // given
        var tuples = List.of(RelationTuple.child("백엔드팀", "개발본부"),
                RelationTuple.directMember("kim", "백엔드팀"));

        // when
        var chunks = SnapshotChunks.encode(tuples, 350_000);
        var decoded = SnapshotChunks.decode(chunks);

        // then
        assertThat(chunks).hasSize(1);
        assertThat(decoded).containsExactlyElementsOf(tuples);
    }

    @Test
    @DisplayName("조각 크기를 넘으면 여러 조각으로 나뉘고, 조각마다 크기 이하다")
    void 조각_크기를_넘으면_나뉜다() throws IOException {
        // given
        var tuples = 튜플들(500);

        // when
        var chunks = SnapshotChunks.encode(tuples, 64);

        // then
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length).isBetween(1, 64));
        assertThat(SnapshotChunks.decode(chunks)).containsExactlyElementsOf(tuples);
    }

    @Test
    @DisplayName("본문 길이가 조각 크기의 배수여도 빈 조각을 만들지 않는다")
    void 조각_크기의_배수여도_빈_조각이_없다() throws IOException {
        // given — 한 덩어리로 압축한 길이를 잰다. 같은 입력의 gzip 출력은 늘 같다(헤더 시각이 0)
        var tuples = 튜플들(100);
        int 길이 = SnapshotChunks.encode(tuples, Integer.MAX_VALUE).get(0).length;

        // when
        var 한_조각 = SnapshotChunks.encode(tuples, 길이);
        var 한_바이트씩 = SnapshotChunks.encode(tuples, 1);

        // then
        assertThat(한_조각).hasSize(1);
        assertThat(한_바이트씩).hasSize(길이);
        assertThat(SnapshotChunks.decode(한_바이트씩)).containsExactlyElementsOf(tuples);
    }

    @Test
    @DisplayName("튜플이 없어도 조각은 하나다 — 풀면 빈 목록이다")
    void 빈_목록도_조각_하나다() throws IOException {
        // when
        var chunks = SnapshotChunks.encode(List.of(), 350_000);

        // then
        assertThat(chunks).hasSize(1);
        assertThat(SnapshotChunks.decode(chunks)).isEmpty();
    }

    @Test
    @DisplayName("줄바꿈이 든 튜플은 적지 않는다 — 되읽을 때 다른 튜플 둘로 갈라지기 때문이다")
    void 줄바꿈이_든_튜플은_적지_않는다() {
        // given
        var tuples = List.of(new RelationTuple("user:kim\nTUPLE#user:lee", "direct_member", "group:DEV"));

        // when, then
        assertThatThrownBy(() -> SnapshotChunks.encode(tuples, 350_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("줄바꿈");
    }

    @Test
    @DisplayName("조각 크기가 0 이하면 나누지 않는다")
    void 조각_크기가_0_이하면_거절한다() {
        // when, then
        assertThatThrownBy(() -> SnapshotChunks.encode(튜플들(3), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("압축본이 아니면 풀지 못한다")
    void 압축본이_아니면_풀지_못한다() {
        // given
        var 깨진_본문 = List.of("깨진 본문".getBytes(StandardCharsets.UTF_8));

        // when, then
        assertThatThrownBy(() -> SnapshotChunks.decode(깨진_본문)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("마지막 조각이 빠지면 풀지 못한다 — 앞 조각만으로 반쪽 목록을 내지 않는다")
    void 마지막_조각이_빠지면_풀지_못한다() {
        // given
        var chunks = SnapshotChunks.encode(튜플들(500), 64);
        var 빠진 = chunks.subList(0, chunks.size() - 1);

        // when, then
        assertThatThrownBy(() -> SnapshotChunks.decode(빠진)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("본문이 없는 조각이 있으면 풀지 못한다")
    void 본문이_없는_조각이_있으면_풀지_못한다() {
        // given
        var chunks = new ArrayList<>(SnapshotChunks.encode(튜플들(500), 64));
        chunks.set(1, null);

        // when, then
        assertThatThrownBy(() -> SnapshotChunks.decode(chunks)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("풀린 줄이 튜플 줄이 아니면 풀지 못한다")
    void 튜플_줄이_아니면_풀지_못한다() throws IOException {
        // given — 올바른 gzip 이지만 첫 줄이 튜플 줄이 아니다
        var 압축 = new ByteArrayOutputStream();
        try (var out = new GZIPOutputStream(압축)) {
            out.write("META\nTUPLE#user:kim|direct_member|group:DEV".getBytes(StandardCharsets.UTF_8));
        }

        // when, then
        assertThatThrownBy(() -> SnapshotChunks.decode(List.of(압축.toByteArray())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("1번째 줄");
    }
}
