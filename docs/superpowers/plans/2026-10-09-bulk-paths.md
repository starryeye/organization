# 대량 경로 비용 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 대량 경로 둘의 비용을 줄인다.
- 튜플 스냅샷: 튜플 한 줄이 아이템 하나이던 것을 gzip 압축 묶음 몇 개로 바꾼다.
- `loadAll`: 직원마다 GetItem 하던 것을 BatchGet(100개씩)으로 바꾼다.

**Architecture:**
- **storage-dynamodb.**
  - 새 `SnapshotChunks` 가 튜플 목록을 줄 → UTF-8 → gzip → 조각으로 바꾸고, 거꾸로 되읽는다. DynamoDB 를 모르는 순수 코드다.
  - `DynamoDbTupleSnapshotRepository` 는 메타(`chunkCount` 추가) → 묶음(하나씩 PutItem) → 포인터 순서로 쓴다. 읽을 때 묶음 수·번호·압축·튜플 수를 확인한다.
  - `DynamoDbDirectoryStateRepository.loadAll` 은 GSI1 로 훑은 직원 아이디를 기존 `findUsers` 에 넘긴다.
- 포트·부르는 쪽(core, app)은 고치지 않는다.

**Tech Stack:** Java 17, Reactor, AWS SDK v2 DynamoDB(2.28.29), `java.util.zip`, JUnit 5, AssertJ, Testcontainers(DynamoDB Local).

**Spec:** `docs/superpowers/specs/2026-10-09-bulk-paths-design.md`

## Global Constraints

- **테스트 표지.** 모든 테스트에 `// given`·`// when`·`// then` 표지를 둔다. 합친 `// when, then` 도 된다. 계획의 코드에 표지가 빠져 있으면 더한다.
- **이름과 글.**
  - 이름·주석·메시지는 한국어 평서문으로 쓴다. `@DisplayName` 은 한국어 문장이다.
  - 클래스 이름은 영어다.
  - 메서드 이름은 그 파일의 관례를 따른다. `Keys`·`Attrs`·`SnapshotChunks` 의 공개 도우미는 영어, 저장소 안 도우미와 테스트는 한국어다.
- **커밋.** 제목은 한국어로 쓰고, 빈 줄 다음에 자기 하네스가 주는 `Co-Authored-By:` 줄을 단다(heredoc). 커밋마다 `git push` 한다.
- **Gradle.**
  - 한 번에 하나씩 포그라운드로 돌린다.
  - 과제가 정한 모듈 테스트만 돌린다. `scaleTest`·전체 `test` 는 컨트롤러 몫이다.
- **금지.** 서브에이전트, 파일시스템 전체 검색, 백그라운드 프로세스, `git stash` 는 쓰지 않는다. 파일은 경로로 스테이징한다.
- **테스트 수.** 결과 XML(`storage-dynamodb/build/test-results/test/*.xml`)에서 옮긴다. 어림하지 않는다.
- **저장 모양(spec §3.1).**
  - 줄: `Keys.tupleSk(tuple)`. 줄 사이는 `\n`. 마지막 줄 뒤에는 `\n` 이 없다.
  - 본문: 목록 전체를 UTF-8 로 바꿔 `GZIPOutputStream` 으로 압축한다.
  - 조각: 압축 바이트를 앞에서부터 `chunkSize` 바이트씩 자른다. 기본값 `CHUNK_SIZE = 350_000`.
  - 묶음 아이템: `PK = Keys.snapshotPk(id)`, `SK = Keys.chunkSk(i)` = `"CHUNK#%04d"`(0~9999), 이진 속성 `data`.
  - 튜플 0개도 묶음 하나다(빈 목록의 압축본).
  - 메타: 지금 속성에 숫자 속성 `chunkCount` 를 더한다.
- **쓰는 순서.** 메타 → 묶음(번호 순서로 하나씩 `PutItem`, BatchWriteItem 아님) → 포인터.
- **무결성 오류(spec §3.2).** 모두 `SnapshotIntegrityException` 이고 메시지는 `"스냅샷 %s 를 온전히 읽지 못했습니다(%s) — POST /admin/sync/rebuild 로 복구하세요"` 다. 괄호 안의 사정은 확인 순서대로:
  1. 메타에 `chunkCount` 가 없음: `메타에 묶음 수가 없다 — 옛 형식`
  2. 묶음 수가 다름: `메타 묶음 %d · 읽음 %d`
  3. i 번째 묶음의 정렬키가 `Keys.chunkSk(i)` 가 아님: `묶음 번호가 %d 에서 끊긴다`
  4. 풀 수 없음(`IOException`): `본문을 풀 수 없다: ` + `e.toString()`
  5. 튜플 수가 다름: `메타 튜플 %d · 읽음 %d`
  - 메타가 없으면 지금처럼 빈 결과다(`findLatest` 는 지금처럼 "메타가 없습니다" 오류).
- **정리(`purgeExpired`)는 코드를 고치지 않는다.** 파티션에서 메타가 아닌 줄(묶음·옛 형식 튜플 줄) 전부를 묶음 요청으로 지우고 메타를 마지막에 지운다.
- **`loadAll`(spec §4).** GSI1 `USER_INDEX` 로 훑은 아이디를 `LinkedHashSet` 에 모아 `findUsers` 에 넘긴다. 조직은 지금 그대로다.

## Review Focus

- **AWS 의 쓰기 스로틀.** 묶음 하나가 최대 350 WCU 라 한 파티션 한도(초당 1,000)에 걸린다. 묶음마다 PutItem 하나여야 스로틀이 요청 전체의 오류로 와 SDK 재시도(최대 9번)가 받는다 — Task 2 가 "PutItem 수 = 묶음 수 + 2" 와 "두 번째 묶음이 실패하면 세 번째는 보내지 않는다" 로 고정한다.
- **줄바꿈이 든 튜플.** 아이디에는 없지만(`IdNormalizer`), 섞이면 되읽을 때 다른 튜플 둘로 갈라진다. 저장을 실패시켜야 한다 — Task 1 테스트.
- **조각 경계.** 압축 길이가 조각 크기의 배수일 때 빈 조각이 생기면 묶음 수가 어긋난다 — Task 1 테스트.
- **옛 형식이 남은 테이블.** 읽으면 "옛 형식" 무결성 오류, 정리하면 튜플 줄까지 지워져야 한다 — Task 2 테스트 둘.
- **저장 도중 실패.** 메타와 앞 묶음만 남은 반쪽 스냅샷은 기준선이 되지 않고, 기한 뒤 정리가 묶음까지 지워야 한다 — Task 2 테스트.

---

### Task 1: 스냅샷 본문 코덱(`SnapshotChunks`)과 키·속성 도우미

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/SnapshotChunks.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java` (상수 `TUPLE_PREFIX` 옆, `parseTupleSk` 뒤)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Attrs.java`
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/SnapshotChunksTest.java`
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`

**Interfaces:**
- Consumes: `Keys.tupleSk(RelationTuple)`, `Keys.isTupleSk(String)`, `Keys.parseTupleSk(String)` (이미 있다).
- Produces:
  - `static List<byte[]> SnapshotChunks.encode(Collection<RelationTuple> tuples, int chunkSize)` — 조각은 늘 하나 이상. `chunkSize <= 0` 이나 줄바꿈이 든 튜플이면 `IllegalArgumentException`.
  - `static List<RelationTuple> SnapshotChunks.decode(List<byte[]> chunks) throws IOException` — 조각이 null 이거나, 압축본이 아니거나, 잘렸거나, 튜플 줄이 아닌 줄이 있으면 `IOException`.
  - `public static final String Keys.CHUNK_PREFIX = "CHUNK#"`, `public static String Keys.chunkSk(int index)`, `public static boolean Keys.isChunkSk(String sk)`.
  - `public static AttributeValue Attrs.b(byte[] value)`, `public static byte[] Attrs.bytes(Map<String, AttributeValue> item, String name)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`SnapshotChunksTest.java` 를 만든다.

```java
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
```

`KeysTest.java` 끝에 두 테스트를 더한다. `import java.util.List;` 를 더한다(`assertThatThrownBy` 는 이미 있다).

```java
    @Test
    @DisplayName("묶음 정렬키는 네 자리 번호라 정렬키 순서가 번호 순서다")
    void 묶음_정렬키는_번호_순서로_정렬된다() {
        // when
        var 키들 = List.of(Keys.chunkSk(10), Keys.chunkSk(2), Keys.chunkSk(0), Keys.chunkSk(9_999));

        // then
        assertThat(키들).containsExactly("CHUNK#0010", "CHUNK#0002", "CHUNK#0000", "CHUNK#9999");
        assertThat(키들.stream().sorted().toList())
                .containsExactly("CHUNK#0000", "CHUNK#0002", "CHUNK#0010", "CHUNK#9999");
        assertThat(Keys.isChunkSk("CHUNK#0000")).isTrue();
        assertThat(Keys.isChunkSk(Keys.META)).isFalse();
        assertThat(Keys.isChunkSk("TUPLE#user:kim|direct_member|group:DEV")).isFalse();
    }

    @Test
    @DisplayName("묶음 번호가 네 자리를 넘거나 음수면 정렬키를 만들지 않는다")
    void 네_자리를_넘는_묶음_번호는_거절한다() {
        // when, then
        assertThatThrownBy(() -> Keys.chunkSk(10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.chunkSk(-1)).isInstanceOf(IllegalArgumentException.class);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.SnapshotChunksTest' --tests 'dev.starryeye.organization.storage.KeysTest'`
Expected: 컴파일 실패 — `SnapshotChunks`·`Keys.chunkSk`·`Keys.isChunkSk` 를 찾지 못한다.

- [ ] **Step 3: 구현한다**

`Keys.java` — `TUPLE_PREFIX` 다음 줄에 상수를 더한다.

```java
    /** 튜플 스냅샷 본문 묶음의 정렬키 접두사(설계 2026-10-09 §3.1). */
    public static final String CHUNK_PREFIX = "CHUNK#";
```

`Keys.java` — `parseTupleSk` 다음에 더한다.

```java
    /**
     * 튜플 스냅샷 본문 묶음의 정렬키(설계 2026-10-09 §3.1). 번호가 네 자리라 정렬키 순서가 곧 이어 붙일 순서다 — 묶음 하나가 350KB 이하라
     * 1만 개(3.5GB)를 넘을 일은 없다.
     */
    public static String chunkSk(int index) {
        if (index < 0 || index > 9_999) {
            throw new IllegalArgumentException("묶음 번호는 0~9999 다: " + index);
        }
        return CHUNK_PREFIX + "%04d".formatted(index);
    }

    /** 정렬키가 {@link #chunkSk} 로 만들어진 묶음 아이템인지 판별한다. */
    public static boolean isChunkSk(String sk) {
        return sk.startsWith(CHUNK_PREFIX);
    }
```

`Attrs.java` — `bool` 다음과 `instant` 다음에 하나씩 더한다. `import software.amazon.awssdk.core.SdkBytes;` 를 더한다.

```java
    public static AttributeValue b(byte[] value) {
        return AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build();
    }
```

```java
    /** 이진 속성. 없거나 이진이 아니면 null. */
    public static byte[] bytes(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.b() == null ? null : value.b().asByteArray();
    }
```

`SnapshotChunks.java` 를 만든다.

```java
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
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.SnapshotChunksTest' --tests 'dev.starryeye.organization.storage.KeysTest'`
Expected: PASS. `SnapshotChunksTest` 10개, `KeysTest` 는 기존 수 + 2개.

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/SnapshotChunks.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Attrs.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/SnapshotChunksTest.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java
git commit -m "$(cat <<'EOF'
feat: 튜플 스냅샷 본문 코덱 — 줄(tupleSk)을 UTF-8·gzip 으로 압축해 조각으로 나누고 되읽는다, 묶음 정렬키 CHUNK#0000

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 2: 튜플 스냅샷을 압축 묶음으로 저장·읽기·무결성

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java` (파일 전체를 아래로 바꾼다)
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`
- Modify: `README.md` ("스냅샷이 왜 있는가" 절, "튜플 스냅샷은 락을 푼 뒤 저장한다" 문단)

**Interfaces:**
- Consumes (Task 1): `SnapshotChunks.encode(Collection<RelationTuple>, int) → List<byte[]>`, `SnapshotChunks.decode(List<byte[]>) throws IOException → List<RelationTuple>`, `Keys.chunkSk(int)`, `Keys.isChunkSk(String)`, `Attrs.b(byte[])`, `Attrs.bytes(Map, String)`.
- Produces:
  - `static final int DynamoDbTupleSnapshotRepository.CHUNK_SIZE = 350_000` (패키지 공개).
  - 패키지 공개 생성자 `DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient, DynamoDbProperties, Clock, int chunkSize)`. 공개 3인자 생성자는 그대로 있고 `CHUNK_SIZE` 를 넘긴다(`DynamoDbConfig` 는 고치지 않는다).
  - 메타 속성 `chunkCount`, 묶음 이진 속성 `data`.

- [ ] **Step 1: 테스트를 고치고 더한다**

`DynamoDbTupleSnapshotRepositoryTest.java` 에 import 를 더한다.

```java
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
```

`튜플들(int)` 다음에 도우미를 더한다.

```java
    /** 실제 아이디처럼 UUID 로 만든 튜플. 압축이 잘 안 되는 모양이라 묶음이 여럿 생긴다. */
    private static Set<RelationTuple> UUID_튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember(
                        UUID.nameUUIDFromBytes(("user" + i).getBytes(StandardCharsets.UTF_8)).toString(),
                        UUID.nameUUIDFromBytes(("group" + (i % 1_000)).getBytes(StandardCharsets.UTF_8)).toString()))
                .collect(Collectors.toSet());
    }

    /** 묶음을 작게(바이트) 잘라 작은 스냅샷도 여러 묶음이 되게 하는 저장소. */
    private DynamoDbTupleSnapshotRepository 작은_묶음_저장소(int chunkSize) {
        return new DynamoDbTupleSnapshotRepository(client, properties, Clock.fixed(지금, ZoneOffset.UTC), chunkSize);
    }
```

기존 `파티션` 도우미를 아래로 바꾼다 — 묶음이 커서 한 쪽(1MB)을 넘을 수 있어 끝까지 읽는다. 그 아래에 도우미를 더한다.

```java
    /** 이 저장소가 쓴 한 스냅샷 파티션의 원본 아이템(강한 일관성, 끝까지). 정렬키 순서다. */
    private List<Map<String, AttributeValue>> 파티션(String snapshotId) {
        return Paginator.queryAll(client, QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshotId))))
                        .consistentRead(true)
                        .build())
                .collectList().block();
    }

    private static List<String> 묶음_키들(List<Map<String, AttributeValue>> items) {
        return items.stream().map(item -> item.get(Keys.SK).s()).filter(Keys::isChunkSk).toList();
    }

    private static Map<String, AttributeValue> 메타(List<Map<String, AttributeValue>> items) {
        return items.stream().filter(item -> Keys.META.equals(item.get(Keys.SK).s())).findFirst().orElseThrow();
    }

    private void 아이템을_쓴다(Map<String, AttributeValue> item) {
        client.putItem(PutItemRequest.builder().tableName(properties.getTableName()).item(item).build()).join();
    }

    /** 메타를 읽어 고친 뒤 통째로 다시 쓴다. */
    private void 메타를_고친다(String snapshotId, Consumer<Map<String, AttributeValue>> 고치기) {
        Map<String, AttributeValue> meta = new HashMap<>(메타(파티션(snapshotId)));
        고치기.accept(meta);
        아이템을_쓴다(meta);
    }

    /** 묶음을 지우고, 메타의 묶음 수를 빼고, 튜플 줄을 아이템 하나씩 쓴다 — 이 설계(2026-10-09) 전의 저장 모양이다. */
    private void 옛_형식으로_바꾼다(String snapshotId, Set<RelationTuple> tuples) {
        묶음_키들(파티션(snapshotId)).forEach(sk -> 아이템을_지운다(snapshotId, sk));
        메타를_고친다(snapshotId, meta -> meta.remove("chunkCount"));
        tuples.forEach(tuple -> 아이템을_쓴다(Map.of(
                Keys.PK, AttributeValue.fromS(Keys.snapshotPk(snapshotId)),
                Keys.SK, AttributeValue.fromS(Keys.tupleSk(tuple)))));
    }

    /** 두 번째 묶음(정렬키 CHUNK#) PutItem 만 실패시키고 나머지는 진짜 클라이언트로 보낸다. */
    private DynamoDbAsyncClient 두번째_묶음_쓰기가_실패하는_클라이언트() {
        AtomicInteger 묶음_쓰기_수 = new AtomicInteger();
        return (DynamoDbAsyncClient) Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(), new Class<?>[]{DynamoDbAsyncClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putItem") && args != null && args[0] instanceof PutItemRequest request
                            && Keys.isChunkSk(request.item().get(Keys.SK).s())
                            && 묶음_쓰기_수.incrementAndGet() == 2) {
                        return CompletableFuture.failedFuture(new IllegalStateException("묶음 쓰기 실패(테스트)"));
                    }
                    return method.invoke(client, args);
                });
    }
```

기존 `배치_한계를_넘는_튜플도_저장된다` 를 아래로 바꾼다(25건 배치는 더는 이 저장소의 일이 아니다).

```java
    @Test
    @DisplayName("튜플 120개도 묶음 하나로 저장되고 전부 복원된다")
    void 튜플이_많아도_전부_복원된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(120));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
    }
```

그 다음에 새 테스트를 더한다.

```java
    @Test
    @DisplayName("UUID 튜플 4만 줄은 묶음 여럿으로 나뉘어 저장되고 그대로 복원된다 — 묶음마다 PutItem 하나다")
    void 여러_묶음으로_나뉘어도_그대로_복원된다() {
        // given
        var tuples = UUID_튜플들(40_000);
        WriteCounter writes = new WriteCounter();
        var 세는_저장소 = new DynamoDbTupleSnapshotRepository(writes.wrap(client), properties, Clock.fixed(지금, ZoneOffset.UTC));

        // when
        세는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, tuples)).block();
        var latest = repository.findLatest().block();

        // then — 메타의 묶음 수 = 묶음 아이템 수 > 1, 번호는 0부터 이어지고, 묶음마다 350,000바이트 이하, 튜플 줄은 없다
        var items = 파티션("20260814T030000-LDAP");
        var 묶음_키 = 묶음_키들(items);
        assertThat(latest.tuples()).isEqualTo(tuples);
        assertThat(묶음_키).hasSizeGreaterThan(1);
        assertThat(메타(items).get("chunkCount").n()).isEqualTo(String.valueOf(묶음_키.size()));
        assertThat(묶음_키).isEqualTo(IntStream.range(0, 묶음_키.size()).mapToObj(Keys::chunkSk).toList());
        assertThat(items).filteredOn(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .allSatisfy(item -> assertThat(item.get("data").b().asByteArray().length).isLessThanOrEqualTo(350_000));
        assertThat(items).noneMatch(item -> Keys.isTupleSk(item.get(Keys.SK).s()));

        // and — 메타 1 + 묶음마다 1 + 포인터 1. 묶음을 BatchWriteItem 에 담지 않는다(spec §3.3)
        assertThat(writes.puts()).isEqualTo(묶음_키.size() + 2);
    }
```

`스냅샷_아이템에는_TTL이_없다` 의 then 첫 줄과 주석을 바꾼다.

```java
        // then — 메타 1 + 묶음 1, 아무도 expiresAt 을 갖지 않고 메타만 보관 기한을 갖는다
        assertThat(items).hasSize(2);
```

`메타를_먼저_쓴다` 를 아래로 바꾼다.

```java
    @Test
    @DisplayName("메타를 먼저 쓴다 — 묶음 쓰기가 도중에 실패해도 메타가 있어 정리 대상이고, 포인터는 직전 스냅샷 그대로다")
    void 메타를_먼저_쓴다() {
        // given — 직전 스냅샷 S1 이 최신이다. S2 를 묶음 여럿으로 저장하다 두 번째 묶음 쓰기가 실패한다
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        var 실패하는_저장소 = new DynamoDbTupleSnapshotRepository(
                두번째_묶음_쓰기가_실패하는_클라이언트(), properties, Clock.fixed(지금, ZoneOffset.UTC), 16);

        // when
        assertThatThrownBy(() -> 실패하는_저장소.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block());

        // then — 묶음은 차례로 하나씩 가므로 첫 묶음만 있다. 메타는 목록에 있고(정리 작업이 찾는다), 포인터는 S1 이다
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .contains("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");

        // and — 보존 기간 뒤 정리하면 반쪽 S2 는 메타·묶음까지 지워지고 최신 S1 은 남는다
        var 여드레_뒤 = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금.plusSeconds(8 * 86400), ZoneOffset.UTC));
        assertThat(여드레_뒤.purgeExpired().block()).isEqualTo(1);
        assertThat(파티션("20260814T030000-LDAP")).isEmpty();
        assertThat(여드레_뒤.findLatest().block().id()).isEqualTo("20260813T030000-LDAP");
    }
```

기존 `튜플_수가_다르면_오류다` 를 아래로 바꾸고, 그 다음에 무결성 테스트 넷을 더한다.

```java
    @Test
    @DisplayName("풀어서 센 튜플 수가 메타와 다르면 오류다 — 반쪽 기준선으로 삭제를 놓치지 않는다")
    void 튜플_수가_다르면_오류다() {
        // given — 메타는 튜플 5개라는데 본문에는 4개다
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();
        메타를_고친다("20260814T030000-LDAP", meta -> meta.put("tupleCount", AttributeValue.fromN("5")));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 튜플 5 · 읽음 4");
    }

    @Test
    @DisplayName("묶음 하나가 빠지면 오류다 — 반쪽 본문으로 기준선을 만들지 않는다")
    void 묶음이_빠지면_오류다() {
        // given — 묶음 여럿인 스냅샷에서 두 번째 묶음이 사라졌다
        작은_묶음_저장소(16).save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block();
        int 묶음_수 = 묶음_키들(파티션("20260814T030000-LDAP")).size();
        assertThat(묶음_수).isGreaterThan(2);
        아이템을_지운다("20260814T030000-LDAP", Keys.chunkSk(1));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("20260814T030000-LDAP")
                .hasMessageContaining("메타 묶음 %d · 읽음 %d".formatted(묶음_수, 묶음_수 - 1))
                .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
    }

    @Test
    @DisplayName("묶음 번호가 0부터 이어지지 않으면 오류다")
    void 묶음_번호가_건너뛰면_오류다() {
        // given — 마지막 묶음이 다른 번호로 옮겨졌다(묶음 수는 그대로)
        작은_묶음_저장소(16).save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(30))).block();
        var 묶음들 = 파티션("20260814T030000-LDAP").stream()
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .toList();
        int 마지막 = 묶음들.size() - 1;
        Map<String, AttributeValue> 옮긴_묶음 = new HashMap<>(묶음들.get(마지막));
        옮긴_묶음.put(Keys.SK, AttributeValue.fromS(Keys.chunkSk(9_999)));
        아이템을_쓴다(옮긴_묶음);
        아이템을_지운다("20260814T030000-LDAP", Keys.chunkSk(마지막));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("묶음 번호가 %d 에서 끊긴다".formatted(마지막));
    }

    @Test
    @DisplayName("본문이 깨지면 오류다")
    void 본문이_깨지면_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        Map<String, AttributeValue> 묶음 = new HashMap<>(파티션("20260814T030000-LDAP").stream()
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .findFirst().orElseThrow());
        묶음.put("data", AttributeValue.fromB(SdkBytes.fromUtf8String("깨진 본문")));
        아이템을_쓴다(묶음);

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("본문을 풀 수 없다");
    }

    @Test
    @DisplayName("옛 형식(튜플 한 줄이 아이템 하나) 스냅샷은 오류다 — 이관하지 않고 재적재로 고친다")
    void 옛_형식은_오류다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();
        옛_형식으로_바꾼다("20260814T030000-LDAP", 튜플들(3));

        // when, then
        assertThatThrownBy(() -> repository.findLatest().block())
                .isInstanceOf(SnapshotIntegrityException.class)
                .hasMessageContaining("옛 형식")
                .hasMessageContaining("POST /admin/sync/rebuild 로 복구하세요");
    }

    @Test
    @DisplayName("옛 형식 스냅샷도 기한이 지나면 정리되고 파티션에 아무것도 남지 않는다")
    void 옛_형식도_정리된다() {
        // given — 10일 전 옛 형식 스냅샷(보존 7일 지남)과 오늘 스냅샷(최신)
        repository.saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(3))).block();
        옛_형식으로_바꾼다("20260804T030000-LDAP", 튜플들(3));
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(2))).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(파티션("20260804T030000-LDAP")).isEmpty();
        assertThat(repository.findLatest().block().tuples()).hasSize(2);
    }
```

`빈_스냅샷도_정상_기준선이다` 의 then 끝에 한 줄을 더한다.

```java
        assertThat(묶음_키들(파티션("20260814T030000-LDAP"))).containsExactly(Keys.chunkSk(0));
```

`포인터를_못_읽으면_정리하지_않는다` 의 마지막 줄을 바꾼다(메타 1 + 묶음 1).

```java
        assertThat(파티션("20260806T030000-LDAP")).hasSize(2);
```

`삭제_중_실패해도_메타는_남는다` 의 given 을 아래로 바꾼다(나머지는 그대로).

```java
        // given — 만료된 후보(묶음 25개 넘게 → 지우기 배치 둘 이상)와, 정리 대상이 아닌 최신 스냅샷을 따로 둔다
        작은_묶음_저장소(4).saveWithCreatedAt(new TupleSnapshot("20260804T030000-LDAP", 지금.minusSeconds(10 * 86400), SyncSource.LDAP, 튜플들(60))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(1))).block();
        assertThat(묶음_키들(파티션("20260804T030000-LDAP"))).hasSizeGreaterThan(BatchRequests.WRITE_LIMIT);
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest'`
Expected: 컴파일 실패 — 4인자 생성자 `DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient, DynamoDbProperties, Clock, int)` 가 없다.

- [ ] **Step 3: 구현한다**

`DynamoDbTupleSnapshotRepository.java` 를 통째로 아래로 바꾼다.

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.SnapshotIntegrityException;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p><b>본문은 압축 묶음이다</b>(설계 2026-10-09 §3). 튜플 목록을 gzip 으로 압축해 {@value #CHUNK_SIZE}바이트 이하 묶음 아이템 몇 개로
 * 나눈다({@link SnapshotChunks}). 튜플 한 줄을 아이템 하나로 쓰던 때는 10만 명(튜플 약 15만 줄)에 한 파티션으로 아이템 약 15만 개,
 * 쓰기 약 15만 WCU 가 몰렸다. 메타는 튜플 수({@code tupleCount})와 묶음 수({@code chunkCount})를 갖는다.
 *
 * <p>저장 순서는 메타 → 묶음 → 포인터다. 메타가 먼저라 묶음을 쓰다 죽어도 정리 작업이 그 조각을 찾아 지우고,
 * 포인터가 마지막이라 반쪽 스냅샷이 기준선이 되지 않는다.
 *
 * <p><b>스냅샷은 테이블 TTL({@link Keys#EXPIRES_AT})을 쓰지 않는다.</b> TTL 은 아이템마다 붙은 시각만 보고 지워 최신인지
 * 모른다 — 최신(비교 기준)까지 지워지면 다음 회차가 빈 기준선으로 돌아 삭제를 하나도 안 한다(점검 C1). 보관 기한은 메타의
 * {@code retainUntil} 에만 적고, {@link #purgeExpired()} 가 최신을 건너뛰며 지운다.
 */
@Slf4j
public class DynamoDbTupleSnapshotRepository implements TupleSnapshotRepository {

    /** 묶음 하나의 본문 상한(바이트). 아이템 한도(400KB)에서 키와 속성 이름이 들어갈 자리를 남긴다. */
    static final int CHUNK_SIZE = 350_000;

    private static final int BATCH_SIZE = BatchRequests.WRITE_LIMIT;
    private static final int DELETE_CONCURRENCY = 4;

    private static final String CREATED_AT = "createdAt";
    private static final String SOURCE = "source";
    private static final String TUPLE_COUNT = "tupleCount";
    private static final String CHUNK_COUNT = "chunkCount";
    private static final String DATA = "data";
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String RETAIN_UNTIL = "retainUntil";
    private static final String WRITING_SINCE = "writingSince";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;
    private final int chunkSize;

    public DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        this(client, properties, clock, CHUNK_SIZE);
    }

    /** 묶음 크기를 줄여 작은 스냅샷도 여러 묶음으로 만드는 테스트용. */
    DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock, int chunkSize) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.chunkSize = chunkSize;
    }

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return doSave(snapshot, clock.instant());
    }

    /** 테스트에서 과거 시각의 스냅샷을 만들기 위한 변형. 보관 기한을 snapshot.createdAt 기준으로 잡는다. */
    public Mono<Void> saveWithCreatedAt(TupleSnapshot snapshot) {
        return doSave(snapshot, snapshot.createdAt());
    }

    private Mono<Void> doSave(TupleSnapshot snapshot, Instant retentionBase) {
        long retainUntil = retentionBase.plus(Duration.ofDays(properties.getSnapshotRetentionDays())).getEpochSecond();

        return Mono.fromCallable(() -> SnapshotChunks.encode(snapshot.tuples(), chunkSize))
                .flatMap(chunks -> writeMeta(snapshot, retainUntil, chunks.size())
                        .then(writeChunks(snapshot.id(), chunks))
                        .then(writePointer(snapshot.id())));
    }

    /**
     * 묶음은 번호 순서로 하나씩 PutItem 으로 보낸다(설계 2026-10-09 §3.3). 묶음 하나가 최대 350 WCU 라, 여러 개를 BatchWriteItem 에 담으면
     * 한 파티션의 쓰기 한도(초당 1,000)에 걸려 일부만 처리되고 {@link BatchRequests} 의 재시도 예산(약 1.5초)이 모자랄 수 있다. 하나씩이면
     * 스로틀이 요청 전체의 오류로 와 SDK 재시도(이 클라이언트의 DynamoDB 기본값, 최대 9번·지수 백오프)가 받는다.
     */
    private Mono<Void> writeChunks(String snapshotId, List<byte[]> chunks) {
        return Flux.range(0, chunks.size())
                .concatMap(index -> putItem(chunkItem(snapshotId, index, chunks.get(index))))
                .then();
    }

    private static Map<String, AttributeValue> chunkItem(String snapshotId, int index, byte[] data) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)));
        item.put(Keys.SK, Attrs.s(Keys.chunkSk(index)));
        item.put(DATA, Attrs.b(data));
        return item;
    }

    private Mono<Void> writeMeta(TupleSnapshot snapshot, long retainUntil, int chunkCount) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshot.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.SNAPSHOT_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(Keys.sortableTimestamp(snapshot.createdAt())));
        item.put(CREATED_AT, Attrs.s(snapshot.createdAt().toString()));
        item.put(SOURCE, Attrs.s(snapshot.source().name()));
        item.put(TUPLE_COUNT, Attrs.n(snapshot.tuples().size()));
        item.put(CHUNK_COUNT, Attrs.n(chunkCount));
        item.put(RETAIN_UNTIL, Attrs.n(retainUntil));
        return putItem(item);
    }

    /** 줄을 통째로 새로 써 "기록 중" 칸도 지운다(설계 2026-09-30 §4.1). */
    private Mono<Void> writePointer(String snapshotId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER));
        item.put(Keys.SK, Attrs.s(Keys.LATEST));
        item.put(SNAPSHOT_ID, Attrs.s(snapshotId));
        return putItem(item);
    }

    @Override
    public Mono<TupleSnapshot> findLatest() {
        return latestId().flatMap(id -> findById(id)
                .switchIfEmpty(Mono.error(() -> new SnapshotIntegrityException(
                        "기준선 스냅샷 %s 의 메타가 없습니다 — POST /admin/sync/rebuild 로 복구하세요".formatted(id)))));
    }

    /** 최신 포인터가 가리키는 스냅샷 id. 강한 일관성으로 읽는다 — 정리 작업이 이 값으로 최신을 건너뛴다. 포인터가 없거나 "기록 중" 표시만 있으면 빈 Mono. */
    private Mono<String> latestId() {
        return 포인터().flatMap(item -> Mono.justOrEmpty(Attrs.str(item, SNAPSHOT_ID)));
    }

    private Mono<Map<String, AttributeValue>> 포인터() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .consistentRead(true)
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> response.item());
    }

    /** 포인터 줄에 칸 하나를 붙인다(UpdateItem) — 스냅샷 번호는 그대로 둔다. 포인터가 없으면 칸만 있는 줄이 생긴다. */
    @Override
    public Mono<Void> markWriting() {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .updateExpression("SET #writingSince = :now")
                        .expressionAttributeNames(Map.of("#writingSince", WRITING_SINCE))
                        .expressionAttributeValues(Map.of(":now", Attrs.s(clock.instant().toString())))
                        .build()))
                .then();
    }

    @Override
    public Mono<Boolean> isWriting() {
        return 포인터()
                .map(item -> item.containsKey(WRITING_SINCE))
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<TupleSnapshot> findById(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .collectList()
                .flatMap(items -> Mono.justOrEmpty(toSnapshot(snapshotId, items)));
    }

    private TupleSnapshot toSnapshot(String snapshotId, List<Map<String, AttributeValue>> items) {
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return null;
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>(본문(snapshotId, meta, items));
        int expected = Attrs.integer(meta, TUPLE_COUNT);
        if (tuples.size() != expected) {
            throw 온전하지_않다(snapshotId, "메타 튜플 %d · 읽음 %d".formatted(expected, tuples.size()));
        }
        return new TupleSnapshot(
                snapshotId,
                Attrs.instant(meta, CREATED_AT),
                SyncSource.valueOf(Attrs.str(meta, SOURCE)),
                tuples);
    }

    /**
     * 묶음을 번호 순서로 모아 푼다(설계 2026-10-09 §3.2). Query 는 정렬키 순서로 주고, 번호가 네 자리라 그 순서가 곧 번호 순서다.
     * 메타에 묶음 수가 없으면 옛 형식(튜플 한 줄이 아이템 하나)이다 — 운영 배포 전이라 이관하지 않고 재적재로 고친다.
     */
    private List<RelationTuple> 본문(String snapshotId, Map<String, AttributeValue> meta, List<Map<String, AttributeValue>> items) {
        if (!meta.containsKey(CHUNK_COUNT)) {
            throw 온전하지_않다(snapshotId, "메타에 묶음 수가 없다 — 옛 형식");
        }
        int expected = Attrs.integer(meta, CHUNK_COUNT);
        List<Map<String, AttributeValue>> chunks = items.stream()
                .filter(item -> Keys.isChunkSk(Attrs.str(item, Keys.SK)))
                .toList();
        if (chunks.size() != expected) {
            throw 온전하지_않다(snapshotId, "메타 묶음 %d · 읽음 %d".formatted(expected, chunks.size()));
        }
        List<byte[]> data = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            if (!Keys.chunkSk(index).equals(Attrs.str(chunks.get(index), Keys.SK))) {
                throw 온전하지_않다(snapshotId, "묶음 번호가 %d 에서 끊긴다".formatted(index));
            }
            data.add(Attrs.bytes(chunks.get(index), DATA));
        }
        try {
            return SnapshotChunks.decode(data);
        } catch (IOException e) {
            throw 온전하지_않다(snapshotId, "본문을 풀 수 없다: " + e);
        }
    }

    private static SnapshotIntegrityException 온전하지_않다(String snapshotId, String 사정) {
        return new SnapshotIntegrityException(
                "스냅샷 %s 를 온전히 읽지 못했습니다(%s) — POST /admin/sync/rebuild 로 복구하세요".formatted(snapshotId, 사정));
    }

    @Override
    public Flux<SnapshotMeta> listRecent(int days) {
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return snapshotMetas()
                .filter(meta -> !meta.createdAt().isBefore(from));
    }

    /**
     * GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑어 <b>원본 아이템</b>을 돌려준다.
     * 이 인덱스는 {@code ProjectionType.ALL} 이라 {@code retainUntil} 을 포함한 모든 속성이
     * 이미 실려 온다 — 그것을 쓰는 곳은 다시 읽지 않아도 된다.
     */
    private Flux<Map<String, AttributeValue>> snapshotIndexItems() {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.SNAPSHOT_INDEX)))
                .scanIndexForward(false)
                .build();

        return Paginator.queryAll(client, request);
    }

    /** GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑는다. */
    private Flux<SnapshotMeta> snapshotMetas() {
        return snapshotIndexItems().map(DynamoDbTupleSnapshotRepository::toMeta);
    }

    private static SnapshotMeta toMeta(Map<String, AttributeValue> item) {
        return new SnapshotMeta(
                Keys.parseSnapshotPk(Attrs.str(item, Keys.PK)),
                Attrs.instant(item, CREATED_AT),
                SyncSource.valueOf(Attrs.str(item, SOURCE)),
                Attrs.integer(item, TUPLE_COUNT));
    }

    /**
     * 보존 기한({@code retainUntil})이 지난 스냅샷을 지운다. <b>최신 포인터가 가리키는 스냅샷은 건너뛴다</b> — 기간과 상관없이
     * 비교 기준이다(점검 C1). 포인터를 못 읽으면 "최신 없음"으로 보지 않고 정리 전체를 멈춘다.
     *
     * <p>후보마다 {@code GetItem} 으로 기한을 다시 읽지 않는다. GSI 가 {@code ProjectionType.ALL} 이라 그 값은 이미 손에 있다.
     */
    @Override
    public Mono<Integer> purgeExpired() {
        long now = clock.instant().getEpochSecond();
        return latestId()
                .defaultIfEmpty("")
                .flatMapMany(latest -> snapshotIndexItems()
                        .filter(item -> Attrs.longValue(item, RETAIN_UNTIL) <= now)
                        .map(item -> Keys.parseSnapshotPk(Attrs.str(item, Keys.PK)))
                        .filter(id -> !id.equals(latest)))
                .flatMap(id -> deleteSnapshot(id).thenReturn(1), DELETE_CONCURRENCY)
                .reduce(0, Integer::sum)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("보존 기간이 지난 스냅샷 {}건을 정리했다", count);
                    }
                });
    }

    /**
     * 메타가 아닌 줄(묶음, 옛 형식의 튜플 줄) 먼저, 메타 마지막 — 중간에 실패해도 메타가 남아 다음 정리가 다시 찾는다. 저장 순서
     * (메타 → 묶음 → 포인터)를 그대로 뒤집은 순서다 — 메타를 먼저 지우면 배치 도중 실패했을 때 그 조각을 아무도 다시 찾지 못한다.
     *
     * <p>묶음을 지우는 것도 묶음 크기만큼 쓰기 용량을 쓴다. 배치가 한 파티션 한도에 걸려 재시도 끝에 실패해도 메타가 남으므로, 다음 정리가
     * 남은 것을 이어 지운다(설계 2026-10-09 §8).
     */
    private Mono<Void> deleteSnapshot(String snapshotId) {
        BatchRequests 묶음 = new BatchRequests(client, properties.getTableName());
        return queryPartition(Keys.snapshotPk(snapshotId))
                .filter(item -> !Keys.META.equals(Attrs.str(item, Keys.SK)))
                .map(item -> WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)),
                                        Keys.SK, Attrs.s(Attrs.str(item, Keys.SK))))
                                .build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(묶음::write)
                .then(Mono.defer(() -> deleteItem(Keys.snapshotPk(snapshotId), Keys.META)));
    }

    // ---------- 공통 ----------

    private Flux<Map<String, AttributeValue>> queryPartition(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .consistentRead(true)
                .build();
        return Paginator.queryAll(client, request);
    }

    private Mono<Void> putItem(Map<String, AttributeValue> item) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    private Mono<Void> deleteItem(String pk, String sk) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build())).then();
    }
}
```

`README.md` 를 고친다(두 곳).

(1) "스냅샷이 왜 있는가" 절의 마지막 문단 — 바꿀 문장:

```
기준선을 온전히 읽지 못하면(포인터가 가리키는 스냅샷의 메타가 없거나 튜플 수가 다르면) 빈 기준선으로 넘어가지 않고 그 회차를
FAILED 로 끝낸다 — `POST /admin/sync/rebuild` 로 복구한다 — 재적재는 기준선 스냅샷을 읽지 않는다.
```

새 글(문단 하나를 뒤에 더한다):

```
기준선을 온전히 읽지 못하면(포인터가 가리키는 스냅샷의 메타가 없거나, 묶음이 빠졌거나 풀리지 않거나, 튜플 수가 다르면) 빈 기준선으로
넘어가지 않고 그 회차를 FAILED 로 끝낸다 — `POST /admin/sync/rebuild` 로 복구한다 — 재적재는 기준선 스냅샷을 읽지 않는다.

**스냅샷 본문은 압축 묶음이다.** 튜플을 한 줄에 하나씩 적어 gzip 으로 압축하고, 350KB 이하 묶음 아이템(`SNAPSHOT#<id>` 파티션의
`CHUNK#0000`, `CHUNK#0001`, …)으로 나눈다. 메타는 튜플 수와 묶음 수를 갖는다. 튜플 15만 줄이면 아이템 15만 개(쓰기 약 15만 WCU)가
묶음 열몇 개(약 5천 WCU)가 된다. 묶음은 하나씩 차례로 PutItem 으로 보낸다 — 한 파티션의 쓰기 한도(초당 1,000)에 걸리면 SDK 재시도가
받는다. 이 형식 전에 저장한 스냅샷(튜플 한 줄이 아이템 하나)은 묶음 수가 없어 기준선으로 읽지 않는다. 운영 배포 전이라 이관하지 않는다 —
`POST /admin/sync/rebuild` 로 새 스냅샷을 남기거나 테이블을 다시 만든다. 옛 형식 줄은 보관 기한이 지나면 정리 작업이 함께 지운다.
설계: `docs/superpowers/specs/2026-10-09-bulk-paths-design.md`.
```

(2) "튜플 스냅샷은 락을 푼 뒤 저장한다" 문단 — 바꿀 글 `저장에 수십 초~2분이 걸리는데, 그동안` 을 `저장에 몇 초가 걸리는데(압축 묶음 열몇 개 — 위 "스냅샷이 왜 있는가"), 그동안` 으로 바꾼다.

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbTupleSnapshotRepositoryTest'`
Expected: PASS(전부).

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS(모듈 전체). 수는 결과 XML 에서 옮긴다.

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java README.md
git commit -m "$(cat <<'EOF'
feat: 튜플 스냅샷을 압축 묶음으로 저장한다 — 메타(chunkCount) → 묶음 하나씩 PutItem → 포인터, 읽을 때 묶음 수·번호·압축·튜플 수를 확인하고 옛 형식은 무결성 오류

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 3: `loadAll` 의 직원을 BatchGet 으로 읽는다

**Files:**
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`loadAll`, 약 687행)
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java` (`전체_조회가_스냅샷을_복원한다` 다음)
- Modify: `README.md` ("재적재는 장부를 버리지 않는다" 의 1번)

**Interfaces:**
- Consumes: `findUsers(Set<String>) → Flux<DirectoryUser>`(이미 있다 — BatchGet 100개씩, 강한 일관성, 동시 8), `enumerateIds(String, Function<String, String>) → Flux<String>`, 테스트 도구 `GetCounter`(`wrap`, `gets()`, `batchGets()`).
- Produces: 없음(포트 그대로).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest.java` 의 `전체_조회가_스냅샷을_복원한다` 다음에 더한다.

```java
    @Test
    @DisplayName("전체 조회는 직원을 한 명씩 GetItem 하지 않고 100명씩 BatchGet 으로 읽는다")
    void 전체_조회는_직원을_묶어_읽는다() {
        // given — 직원 250명(묶음 셋), 조직 둘
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        IntStream.range(0, 250).forEach(i -> users.put("u" + i, 직원("u" + i)));
        var snapshot = new DirectorySnapshot(users,
                Map.of("DEV001", 조직("DEV001", "개발본부", MemberRef.group("DEV002"), MemberRef.user("u0")),
                       "DEV002", 조직("DEV002", "백엔드팀", MemberRef.user("u1"))));
        repository.replaceWith(snapshot).block();
        GetCounter gets = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(gets.wrap(client), properties, clock);

        // when
        var loaded = 세는.loadAll().block();

        // then
        assertThat(loaded).isEqualTo(snapshot);
        assertThat(gets.gets()).isZero();
        assertThat(gets.batchGets()).isEqualTo(3);
    }
```

- [ ] **Step 2: 실패를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: FAIL — `전체_조회는_직원을_묶어_읽는다` 에서 `gets.gets()` 가 0 이 아니라 250 이다.

- [ ] **Step 3: 구현한다**

`loadAll` 을 아래로 바꾼다(필요한 import `LinkedHashSet`·`Collectors` 는 이미 있다).

```java
    /**
     * 직원은 GSI1 로 아이디를 훑은 뒤 {@link #findUsers}(BatchGet 100개씩, 강한 일관성)로 읽는다 — 직원마다 GetItem 을 하면 10만 명에
     * 10만 번 왕복이었다(설계 2026-10-09 §4). 훑은 뒤 읽기 전에 지워진 직원은 결과에 없다. 조직은 멤버 목록이 필요해 조직마다 파티션을 읽는다.
     */
    @Override
    public Mono<DirectorySnapshot> loadAll() {
        Mono<Map<String, DirectoryUser>> users = enumerateIds(Keys.USER_INDEX, Keys::parseUserPk)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .flatMapMany(this::findUsers)
                .collect(LinkedHashMap::new, (map, user) -> map.put(user.id(), user));

        Mono<Map<String, DirectoryGroup>> groups = enumerateIds(Keys.GROUP_INDEX, Keys::parseGroupPk)
                .flatMap(this::findGroup, QUERY_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, group) -> map.put(group.id(), group));

        return Mono.zip(users, groups)
                .map(both -> new DirectorySnapshot(both.getT1(), both.getT2()));
    }
```

`README.md` — "재적재는 장부를 버리지 않는다" 의 1번을 고친다. 바꿀 글:

```
1. 있어야 할 줄을 먼저 다 읽는다(app-ldap은 LDAP 전체, app-scim은 DynamoDB 현재상태). 여기서 실패하면 장부에
   아무것도 하지 않고 `FAILED`다.
```

새 글:

```
1. 있어야 할 줄을 먼저 다 읽는다(app-ldap은 LDAP 전체, app-scim은 DynamoDB 현재상태 — 직원 아이디를 GSI1 로 훑고 직원은
   BatchGet(100명씩, 강한 일관성)으로, 조직은 조직마다 파티션을 읽는다). 여기서 실패하면 장부에 아무것도 하지 않고 `FAILED`다.
```

- [ ] **Step 4: 통과를 확인한다**

Run: `./gradlew :storage-dynamodb:test --tests 'dev.starryeye.organization.storage.DynamoDbDirectoryStateRepositoryTest'`
Expected: PASS(전부).

Run: `./gradlew :storage-dynamodb:test`
Expected: PASS(모듈 전체).

- [ ] **Step 5: 커밋한다**

```bash
git add storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java README.md
git commit -m "$(cat <<'EOF'
perf: loadAll 의 직원은 GSI1 로 훑은 아이디를 findUsers(BatchGet 100개씩, 강한 일관성)로 읽는다 — 직원마다 GetItem 하던 것을 없앤다

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```

---

### Task 4: 10만 명 규모 테스트(저장소 수준)

**Files:**
- Create: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BulkPathScaleTest.java`

**Interfaces:**
- Consumes: `DynamoDbTupleSnapshotRepository.CHUNK_SIZE`(Task 2), `DynamoDbDirectoryStateRepository.loadAll`(Task 3), 테스트 도구 `GetCounter`·`WriteCounter`, `Paginator.queryAll(DynamoDbAsyncClient, QueryRequest)`, `BatchRequests.GET_LIMIT`, `@ScaleTest`(`dev.starryeye.organization.core.fixture.ScaleTest`).
- Produces: 규모 테스트 출력 두 줄("전체 조회: …", "스냅샷: …") — 컨트롤러가 spec §10 에 옮긴다.

- [ ] **Step 1: 규모 테스트를 쓴다**

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대량 경로 두 곳을 실제 규모로 잰다(설계 2026-10-09 §7). 앱 규모 테스트(S18·S19 등)는 5천 명 조직도라, 10만 명의 요청 수와
 * 압축률은 저장소 수준에서 본다. DynamoDB Local 의 시간은 AWS 와 다르다 — 요청 수와 바이트만 믿는다.
 */
@ScaleTest
class BulkPathScaleTest extends DynamoDbTestSupport {

    private static final int 직원_수 = 100_000;
    private static final int 조직_수 = 1_000;

    private static String uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Test
    @DisplayName("직원 10만 명 전체 조회는 GetItem 없이 BatchGet 1,000번으로 읽는다")
    void 전체_조회는_BatchGet_으로_읽는다() {
        // given — 직원 10만, 조직 100(조직마다 직원 10명)
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (int i = 0; i < 직원_수; i++) {
            String id = "u%06d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true));
        }
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (int g = 0; g < 100; g++) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (int m = 0; m < 10; m++) {
                members.add(MemberRef.user("u%06d".formatted(g * 10 + m)));
            }
            groups.put("G%03d".formatted(g), new DirectoryGroup("G%03d".formatted(g), "g" + g, "조직 " + g, members));
        }
        var snapshot = new DirectorySnapshot(users, groups);
        new DynamoDbDirectoryStateRepository(client, properties, Clock.systemUTC())
                .replaceWith(snapshot).block(Duration.ofMinutes(30));
        GetCounter gets = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(gets.wrap(client), properties, Clock.systemUTC());

        // when
        long 시작 = System.currentTimeMillis();
        var loaded = 세는.loadAll().block(Duration.ofMinutes(10));
        long 걸림 = System.currentTimeMillis() - 시작;

        // then
        System.out.printf("전체 조회: 직원 %,d · 조직 %,d — %,dms, GetItem %,d, BatchGet %,d%n",
                loaded.users().size(), loaded.groups().size(), 걸림, gets.gets(), gets.batchGets());
        assertThat(loaded).isEqualTo(snapshot);
        assertThat(gets.gets()).isZero();
        assertThat(gets.batchGets()).isEqualTo(직원_수 / BatchRequests.GET_LIMIT);
    }

    @Test
    @DisplayName("UUID 아이디 튜플 15만 줄 스냅샷은 압축 묶음 몇 개로 저장되고 그대로 읽힌다")
    void 튜플_15만_줄_스냅샷은_압축_묶음으로_왕복한다() {
        // given — 직원 10만이 조직 1,000곳에 하나씩, 그중 5만 명은 한 곳 더(직원 1인당 소속 약 1.5)
        Set<RelationTuple> tuples = new HashSet<>();
        for (int i = 0; i < 직원_수; i++) {
            tuples.add(RelationTuple.directMember(uuid("user" + i), uuid("group" + (i % 조직_수))));
        }
        for (int i = 0; i < 직원_수 / 2; i++) {
            tuples.add(RelationTuple.directMember(uuid("user" + i), uuid("group" + ((i + 500) % 조직_수))));
        }
        long 원본_바이트 = tuples.stream()
                .mapToLong(tuple -> Keys.tupleSk(tuple).getBytes(StandardCharsets.UTF_8).length + 1)
                .sum();
        WriteCounter writes = new WriteCounter();
        var repository = new DynamoDbTupleSnapshotRepository(writes.wrap(client), properties, Clock.systemUTC());
        var snapshot = new TupleSnapshot("20261009T030000-SCIM", Instant.now(), SyncSource.SCIM, tuples);

        // when
        long 시작 = System.currentTimeMillis();
        repository.save(snapshot).block(Duration.ofMinutes(10));
        long 저장 = System.currentTimeMillis() - 시작;
        시작 = System.currentTimeMillis();
        var latest = repository.findLatest().block(Duration.ofMinutes(10));
        long 읽기 = System.currentTimeMillis() - 시작;

        // then
        List<Map<String, AttributeValue>> 묶음들 = Paginator.queryAll(client, QueryRequest.builder()
                        .tableName(properties.getTableName())
                        .keyConditionExpression("#pk = :pk")
                        .expressionAttributeNames(Map.of("#pk", Keys.PK))
                        .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(Keys.snapshotPk(snapshot.id()))))
                        .consistentRead(true)
                        .build())
                .filter(item -> Keys.isChunkSk(item.get(Keys.SK).s()))
                .collectList().block();
        long 압축_바이트 = 묶음들.stream().mapToLong(item -> item.get("data").b().asByteArray().length).sum();
        System.out.printf("스냅샷: 튜플 %,d · 원본 %,d바이트 → 압축 %,d바이트(%.1f배) · 묶음 %d — 저장 %,dms(PutItem %d) · 읽기 %,dms%n",
                tuples.size(), 원본_바이트, 압축_바이트, (double) 원본_바이트 / 압축_바이트, 묶음들.size(), 저장, writes.puts(), 읽기);
        assertThat(tuples).hasSize(150_000);
        assertThat(latest.tuples()).isEqualTo(tuples);
        assertThat(writes.puts()).isEqualTo(묶음들.size() + 2);
        assertThat(묶음들.size()).isLessThan((int) (원본_바이트 / DynamoDbTupleSnapshotRepository.CHUNK_SIZE));
    }
}
```

- [ ] **Step 2: 컴파일을 확인한다**

Run: `./gradlew :storage-dynamodb:compileTestJava`
Expected: BUILD SUCCESSFUL. 규모 테스트는 돌리지 않는다 — 10만 명 적재가 몇 분 걸려 컨트롤러가 돌린다:
`./gradlew :storage-dynamodb:scaleTest --tests 'dev.starryeye.organization.storage.BulkPathScaleTest'`
Expected(컨트롤러): PASS 2개, 출력 두 줄을 spec §10 에 옮긴다.

- [ ] **Step 3: 커밋한다**

```bash
git add storage-dynamodb/src/test/java/dev/starryeye/organization/storage/BulkPathScaleTest.java
git commit -m "$(cat <<'EOF'
test: 대량 경로 규모 테스트 — 직원 10만 명 loadAll 은 GetItem 0·BatchGet 1,000번, UUID 튜플 15만 줄 스냅샷은 압축 묶음으로 왕복(바이트·묶음 수·시간 기록)

Co-Authored-By: <하네스가 주는 줄>
EOF
)"
git push
```
