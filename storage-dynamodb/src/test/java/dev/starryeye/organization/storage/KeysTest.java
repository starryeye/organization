package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("멤버십 정렬키를 그대로 GSI 파티션키로 써서 역참조가 가능해진다")
    void 멤버십_역참조_키는_정렬키와_같다() {
        // given
        MemberRef ref = MemberRef.user("kim");

        // when, then
        assertThat(Keys.memberGsi1Pk(ref)).isEqualTo(Keys.memberSk(ref));
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
}
