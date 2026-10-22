package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeysTest {

    @Test
    @DisplayName("직원과 조직은 서로 다른 파티션 접두사를 가져 한 테이블에서 구분된다")
    void 직원과_조직의_파티션키가_구분된다() {
        // given, when
        String userPk = Keys.userPk("kim");
        String groupPk = Keys.groupPk("DEV002");

        // then
        assertThat(userPk).isEqualTo("USER#kim");
        assertThat(groupPk).isEqualTo("GROUP#DEV002");
    }

    @Test
    @DisplayName("멤버십 정렬키는 타입을 포함해 직원 멤버와 하위 조직 멤버를 구분한다")
    void 멤버십_정렬키는_타입을_포함한다() {
        // given, when
        String userMember = Keys.memberSk(MemberRef.user("kim"));
        String groupMember = Keys.memberSk(MemberRef.group("DEV002"));

        // then
        assertThat(userMember).isEqualTo("MEMBER#USER#kim");
        assertThat(groupMember).isEqualTo("MEMBER#GROUP#DEV002");
    }

    @Test
    @DisplayName("소속 정렬키는 MEMBER# 로 시작하지 않는다 — 조직 파티션에서 멤버로 오인되면 안 된다")
    void 소속_정렬키는_멤버와_구분된다() {
        // given, when
        String belongsTo = Keys.belongsToSk("DEV002");

        // then
        assertThat(belongsTo).isEqualTo("BELONGS_TO#GROUP#DEV002");
        assertThat(Keys.isMemberSk(belongsTo)).isFalse();
        assertThat(Keys.isBelongsToSk(belongsTo)).isTrue();
        assertThat(Keys.isBelongsToSk(Keys.memberSk(MemberRef.user("kim")))).isFalse();
    }

    @Test
    @DisplayName("소속 정렬키는 왕복 변환해도 조직코드가 그대로다")
    void 소속_정렬키는_왕복_변환된다() {
        // given
        String sk = Keys.belongsToSk("DEV002");

        // when, then
        assertThat(Keys.parseBelongsToSk(sk)).isEqualTo("DEV002");
    }

    @Test
    @DisplayName("소속 정렬키가 아닌 값을 되돌리려 하면 조용히 자르지 않고 거부한다")
    void 잘못된_소속_정렬키는_거부한다() {
        // given, when, then — BELONGS_TO# 로 시작하는 것만 보고 곧장 GROUP# 길이만큼
        // substring 하면, "BELONGS_TO#USER#kim" 같은 잘못된 모양도 접두사 검사를 통과해
        // 뒷부분이 조용히 잘린 쓰레기 아이디("im")를 돌려준다.
        assertThatThrownBy(() -> Keys.parseBelongsToSk("BELONGS_TO#USER#kim"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.parseBelongsToSk(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("멤버의 파티션키는 직원이면 USER#, 하위 조직이면 GROUP# 이다")
    void 멤버의_파티션키() {
        // given, when, then
        assertThat(Keys.memberPk(MemberRef.user("kim"))).isEqualTo("USER#kim");
        assertThat(Keys.memberPk(MemberRef.group("DEV002"))).isEqualTo("GROUP#DEV002");
    }

    @Test
    @DisplayName("튜플 정렬키는 왕복 변환해도 원래 튜플과 같다")
    void 튜플_정렬키는_왕복_변환된다() {
        // given
        RelationTuple tuple = RelationTuple.directMember("kim", "DEV002");

        // when
        String sk = Keys.tupleSk(tuple);
        RelationTuple parsed = Keys.parseTupleSk(sk);

        // then
        assertThat(sk).isEqualTo("TUPLE#user:kim|direct_member|group:DEV002");
        assertThat(parsed).isEqualTo(tuple);
    }

    @Test
    @DisplayName("한글 조직코드가 담긴 튜플도 왕복 변환된다")
    void 한글_조직코드_튜플도_왕복_변환된다() {
        // given
        RelationTuple tuple = RelationTuple.child("백엔드팀", "개발본부");

        // when
        RelationTuple parsed = Keys.parseTupleSk(Keys.tupleSk(tuple));

        // then
        assertThat(parsed).isEqualTo(tuple);
    }

    @Test
    @DisplayName("실행 이력 파티션키는 월 단위로 나뉘어 한 파티션이 무한히 커지지 않는다")
    void 실행이력_파티션키는_월단위다() {
        // given
        Instant at = Instant.parse("2026-08-14T03:00:00Z");

        // when
        String pk = Keys.syncRunPk(at);

        // then
        assertThat(pk).isEqualTo("SYNCRUN#2026-08");
    }

    @Test
    @DisplayName("실행 이력 정렬키는 시각이 앞에 와서 역순 조회가 최신순이 된다")
    void 실행이력_정렬키는_시각이_앞에_온다() {
        // given
        Instant 이른시각 = Instant.parse("2026-08-14T03:00:00Z");
        Instant 늦은시각 = Instant.parse("2026-08-14T04:00:00Z");

        // when
        String 이른키 = Keys.syncRunSk(이른시각, "run-b");
        String 늦은키 = Keys.syncRunSk(늦은시각, "run-a");

        // then
        assertThat(이른키).isLessThan(늦은키);
    }

    @Test
    @DisplayName("같은 초 안에서도 밀리초 차이가 정렬 순서를 뒤집지 않는다")
    void 실행이력_정렬키는_같은_초_안에서도_밀리초로_정렬된다() {
        // given — Instant.toString() 은 나노초가 0 이면 소수점을 생략해서
        // "T03:00:00Z" 와 "T03:00:00.500Z" 를 문자열로 비교하면 '.'(0x2E) 이
        // 'Z'(0x5A) 보다 작아 더 이른 시각이 오히려 뒤로 밀린다.
        Instant 정각 = Instant.parse("2026-08-14T03:00:00Z");
        Instant 정각_500밀리초후 = Instant.parse("2026-08-14T03:00:00.500Z");

        // when
        String 정각키 = Keys.syncRunSk(정각, "run-a");
        String 이후키 = Keys.syncRunSk(정각_500밀리초후, "run-b");

        // then
        assertThat(정각키).isLessThan(이후키);
    }

    @Test
    @DisplayName("직원 파티션키가 아닌 값을 되돌리려 하면 조용히 자르지 않고 거부한다")
    void 잘못된_파티션키는_거부한다() {
        // given, when, then — 무조건 substring 하면 "GRO" 같은 쓰레기 아이디가 나오고,
        // 그 값이 스냅샷에 들어가면 다음 diff 의 기준선이 오염된다.
        assertThatThrownBy(() -> Keys.parseUserPk(Keys.groupPk("DEV001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.parseGroupPk(Keys.userPk("kim")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.parseUserPk(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("보류 연결의 정렬키를 되읽으면 같은 연결이다")
    void 보류_연결_정렬키() {
        // given
        var edge = new GroupEdge("개발본부", "백엔드팀");

        // when
        String sk = Keys.cutEdgeSk(edge);

        // then
        assertThat(sk).isEqualTo("EDGE#개발본부|백엔드팀");
        assertThat(Keys.parseCutEdgeSk(sk)).isEqualTo(edge);
    }

    @Test
    @DisplayName("묶음 정렬키는 네 자리 번호라 정렬키 순서가 번호 순서다")
    void 묶음_정렬키는_번호_순서로_정렬된다() {
        // given, when
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
        // given, when, then
        assertThatThrownBy(() -> Keys.chunkSk(10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.chunkSk(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("불확실 묶음 정렬키는 네 자리 번호라 정렬키 순서가 번호 순서고, 본문 묶음과 접두어가 겹치지 않는다")
    void 불확실_묶음_정렬키는_네_자리_번호다() {
        // given, when
        var 키들 = List.of(Keys.uncertainChunkSk(10), Keys.uncertainChunkSk(2), Keys.uncertainChunkSk(0), Keys.uncertainChunkSk(9_999));

        // then
        assertThat(키들).containsExactly("UNCERTAIN#0010", "UNCERTAIN#0002", "UNCERTAIN#0000", "UNCERTAIN#9999");
        assertThat(키들.stream().sorted().toList())
                .containsExactly("UNCERTAIN#0000", "UNCERTAIN#0002", "UNCERTAIN#0010", "UNCERTAIN#9999");
        assertThat(Keys.isUncertainChunkSk("UNCERTAIN#0000")).isTrue();
        assertThat(Keys.isUncertainChunkSk(Keys.chunkSk(0))).as("본문 묶음은 불확실 묶음이 아니다").isFalse();
        assertThat(Keys.isChunkSk(Keys.uncertainChunkSk(0))).as("불확실 묶음은 본문 묶음이 아니다").isFalse();
        assertThat(Keys.isUncertainChunkSk(Keys.META)).isFalse();
        assertThat(Keys.isUncertainChunkSk("TUPLE#user:kim|direct_member|group:DEV")).isFalse();

        // and — 번호가 네 자리를 넘거나 음수면 만들지 않는다
        assertThatThrownBy(() -> Keys.uncertainChunkSk(10_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Keys.uncertainChunkSk(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이력 키 — 대상 종류별 파티션과, 시각·원인·네 자리 조각 번호의 정렬키")
    void 이력_키() {
        // given
        Instant at = Instant.parse("2026-10-10T03:00:00Z");

        // when, then
        assertThat(Keys.auditPk(MemberRef.user("kim"))).isEqualTo("AUDIT#USER#kim");
        assertThat(Keys.auditPk(MemberRef.group("DEV"))).isEqualTo("AUDIT#GROUP#DEV");
        assertThat(Keys.auditSk(at, "run-1", 2)).isEqualTo("2026-10-10T03:00:00.000Z#run-1#0002");
        assertThat(Keys.auditRecordKey("2026-10-10T03:00:00.000Z#run-1#0002"))
                .isEqualTo("2026-10-10T03:00:00.000Z#run-1");
    }

    @Test
    @DisplayName("키 한도는 글자 수가 아니라 UTF-8 바이트로 잰다 — 한도와 같으면 들어가고 1바이트 넘으면 들어가지 않는다")
    void 키_한도는_바이트로_잰다() {
        // given, when, then — 정렬키 1024바이트
        assertThat(Keys.fitsSortKey("x".repeat(1024))).isTrue();
        assertThat(Keys.fitsSortKey("x".repeat(1025))).isFalse();
        assertThat(Keys.fitsSortKey("가".repeat(341) + "x")).isTrue();
        assertThat(Keys.fitsSortKey("가".repeat(342))).as("342자지만 1026바이트").isFalse();

        // then — 파티션키 2048바이트
        assertThat(Keys.fitsPartitionKey("x".repeat(2048))).isTrue();
        assertThat(Keys.fitsPartitionKey("x".repeat(2049))).isFalse();
        assertThat(Keys.fitsPartitionKey("가".repeat(682) + "xx")).isTrue();
        assertThat(Keys.fitsPartitionKey("가".repeat(683))).as("683자지만 2049바이트").isFalse();
    }

    @Test
    @DisplayName("쓰기 색인 키는 1024바이트를 넘지 않고 소문자로 바꾼 원래 값의 앞부분이다")
    void 쓰기_색인키는_1024바이트를_넘지_않는다() {
        // given — 400자지만 1200바이트
        String 긴_값 = "가".repeat(400) + "ABC";

        // when
        String 키 = Keys.indexKeyForWrite(긴_값);

        // then — 한도 안에서 가장 긴 앞부분(341자, 1023바이트)이다
        assertThat(키.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(Keys.indexKey(긴_값)).startsWith(키);
        assertThat(키).isEqualTo("가".repeat(341));
    }

    @Test
    @DisplayName("쓰기 색인 키는 코드 포인트를 가르지 않는다 — 4바이트 문자의 서로게이트 쌍이 반쪽으로 남지 않는다")
    void 쓰기_색인키는_코드포인트를_가르지_않는다() {
        // given — 앞의 'a' 때문에 한도(1024)가 4바이트 문자의 한가운데에 떨어진다: 1 + 4 x 255 = 1021바이트, 다음 문자는 1025바이트
        String 긴_값 = "a" + "😀".repeat(300);

        // when
        String 키 = Keys.indexKeyForWrite(긴_값);

        // then
        assertThat(키).isEqualTo("a" + "😀".repeat(255));
        assertThat(키.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(Character.isHighSurrogate(키.charAt(키.length() - 1))).as("끝이 쌍의 앞쪽 반이 아니다").isFalse();
        assertThat(키.codePoints().filter(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF))
                .as("홀로 남은 서로게이트가 없다").isEmpty();
    }

    @Test
    @DisplayName("한도 안의 값은 자르지 않고 indexKey 와 같다")
    void 한도_안의_값은_자르지_않는다() {
        // given, when, then
        assertThat(Keys.indexKeyForWrite("Kim.Lee")).isEqualTo(Keys.indexKey("Kim.Lee")).isEqualTo("kim.lee");
        assertThat(Keys.indexKeyForWrite("")).isEmpty();
        assertThat(Keys.indexKeyForWrite("x".repeat(1024))).as("정확히 한도").isEqualTo("x".repeat(1024));
        assertThat(Keys.indexKeyForWrite("가".repeat(341) + "X")).as("1024바이트").isEqualTo("가".repeat(341) + "x");
    }

    @Test
    @DisplayName("소문자로 바꾸면 길어지는 글자도 소문자로 바꾼 뒤의 바이트로 한도 안으로 자른다")
    void 소문자로_늘어나는_글자도_한도_안으로_자른다() {
        // given — U+0130 은 2바이트인데 소문자(i + U+0307)는 3바이트라, 원래 800바이트가 소문자로는 1200바이트다
        String 긴_값 = "İ".repeat(400);
        assertThat(긴_값.getBytes(StandardCharsets.UTF_8)).hasSize(800);

        // when
        String 키 = Keys.indexKeyForWrite(긴_값);

        // then — 원래 값을 먼저 자르고 소문자로 바꾸면 이 한도를 넘는다
        assertThat(키.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(Keys.MAX_SORT_KEY_BYTES);
        assertThat(Keys.indexKey(긴_값)).startsWith(키);
        assertThat(키.getBytes(StandardCharsets.UTF_8).length).as("한도 안에서 가장 긴 앞부분").isEqualTo(1024);
    }
}
