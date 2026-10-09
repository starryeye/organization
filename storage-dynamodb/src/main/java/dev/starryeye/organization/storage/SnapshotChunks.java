package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 튜플 스냅샷 본문의 저장 모양(설계 2026-10-09 §3.1). 튜플을 한 줄에 하나({@link Keys#tupleSk}) 적고 줄 사이는 {@code \n} 이다.
 * 목록 전체를 UTF-8 로 바꿔 gzip 으로 압축하고, 정해진 크기 이하 조각으로 나눈다.
 *
 * <p>아이디에는 줄바꿈이 없다 — {@code IdNormalizer} 가 공백류({@code \s})를 걸러낸다. 그래도 섞이면 되읽을 때 다른 튜플 둘로 갈라지므로
 * 적지 않고 실패한다.
 */
final class SnapshotChunks {

    private static final int BUFFER = 64 * 1024;

    private SnapshotChunks() {
    }

    /** 튜플이 없어도 조각은 하나다(빈 목록의 압축본) — 그래야 "묶음이 없다" 를 늘 무결성 오류로 볼 수 있다. */
    static List<byte[]> encode(Collection<RelationTuple> tuples, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("조각 크기는 1바이트 이상이다: " + chunkSize);
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(compressed, BUFFER), StandardCharsets.UTF_8)) {
            boolean first = true;
            for (RelationTuple tuple : tuples) {
                String line = Keys.tupleSk(tuple);
                if (line.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("줄바꿈이 든 튜플은 스냅샷에 적지 않는다: " + line.replace("\n", "\\n"));
                }
                if (!first) {
                    writer.write('\n');
                }
                writer.write(line);
                first = false;
            }
        } catch (IOException e) {
            // 메모리에 쓰므로 일어나지 않는다
            throw new UncheckedIOException(e);
        }
        byte[] all = compressed.toByteArray();
        List<byte[]> chunks = new ArrayList<>();
        for (int from = 0; from < all.length; from += chunkSize) {
            chunks.add(Arrays.copyOfRange(all, from, Math.min(all.length, from + chunkSize)));
        }
        return chunks;
    }

    /**
     * 조각을 순서대로 이어 붙여 풀고 줄마다 튜플로 되읽는다. 조각이 빠졌거나 깨졌으면 gzip 이 알아챈다(끝이 잘림·CRC 불일치).
     * 그때와, 본문이 없는 조각이나 튜플 줄이 아닌 줄이 있을 때 {@link IOException} 이다.
     */
    static List<RelationTuple> decode(List<byte[]> chunks) throws IOException {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            if (chunk == null) {
                throw new IOException("본문이 없는 조각이 있다");
            }
            joined.writeBytes(chunk);
        }
        String text;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(joined.toByteArray()), BUFFER)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.split("\n", -1);
        List<RelationTuple> tuples = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            if (!Keys.isTupleSk(lines[i])) {
                throw new IOException("튜플 줄이 아니다(%d번째 줄)".formatted(i + 1));
            }
            tuples.add(Keys.parseTupleSk(lines[i]));
        }
        return tuples;
    }
}
