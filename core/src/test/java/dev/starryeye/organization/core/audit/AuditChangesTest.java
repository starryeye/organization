package dev.starryeye.organization.core.audit;

import dev.starryeye.organization.core.model.DirectoryChanges;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.PersonName;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChangesTest {

    private static final MemberRef 김 = MemberRef.user("kim");
    private static final MemberRef 이 = MemberRef.user("lee");
    private static final MemberRef 개발 = MemberRef.group("DEV");
    private static final MemberRef 팀 = MemberRef.group("TEAM");

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "emp-" + id, id, id + " 님", id + "@example.com", true);
    }

    private static GroupHeader 이름표(String id, String name) {
        return new GroupHeader(id, "cn=" + id, name);
    }

    private static AuditEntry 줄(List<AuditEntry> entries, MemberRef subject) {
        return entries.stream().filter(entry -> entry.subject().equals(subject)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("직원을 만들면 생성과, 있는 속성 전부를 이전 없음으로 적는다")
    void 직원_생성() {
        // given, when
        List<AuditEntry> entries = new AuditChanges().user(null, 직원("kim")).entries();

        // then
        AuditEntry 김의_줄 = 줄(entries, 김);
        assertThat(김의_줄.created()).isTrue();
        assertThat(김의_줄.deleted()).isFalse();
        assertThat(김의_줄.attributes()).containsExactly(
                new AttributeChange("externalId", null, "emp-kim"),
                new AttributeChange("userName", null, "kim"),
                new AttributeChange("displayName", null, "kim 님"),
                new AttributeChange("email", null, "kim@example.com"),
                new AttributeChange("active", null, "true"));
    }

    @Test
    @DisplayName("직원이 바뀌면 바뀐 속성만 이전 → 이후로 적는다 — 이름의 하위 속성도 따로")
    void 직원_변경() {
        // given
        DirectoryUser 전 = 직원("kim");
        DirectoryUser 후 = 전.withDisplayName("김철수").withName(PersonName.EMPTY.withGivenName("철수"));

        // when
        List<AuditEntry> entries = new AuditChanges().user(전, 후).entries();

        // then
        AuditEntry 김의_줄 = 줄(entries, 김);
        assertThat(김의_줄.created()).isFalse();
        assertThat(김의_줄.attributes()).containsExactly(
                new AttributeChange("displayName", "kim 님", "김철수"),
                new AttributeChange("name.givenName", null, "철수"));
    }

    @Test
    @DisplayName("직원을 지우면 삭제와, 있던 속성 전부를 이후 없음으로 적는다")
    void 직원_삭제() {
        // given, when
        AuditEntry 김의_줄 = 줄(new AuditChanges().user(직원("kim"), null).entries(), 김);

        // then
        assertThat(김의_줄.deleted()).isTrue();
        assertThat(김의_줄.created()).isFalse();
        assertThat(김의_줄.attributes()).contains(new AttributeChange("userName", "kim", null));
    }

    @Test
    @DisplayName("값이 같거나, 빈 문자열과 없는 값만 다르면 줄이 없다")
    void 바뀐_것이_없으면_줄이_없다() {
        // given
        DirectoryUser 빈_메일 = 직원("kim").withEmail("");
        DirectoryUser 메일_없음 = 직원("kim").withEmail(null);

        // when
        List<AuditEntry> entries = new AuditChanges()
                .user(직원("lee"), 직원("lee"))
                .user(빈_메일, 메일_없음)
                .entries();

        // then
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("한 대상이 여러 번 바뀌면 처음의 이전과 마지막의 이후를 비교한다")
    void 처음_이전과_마지막_이후() {
        // given
        DirectoryUser 처음 = 직원("kim");
        DirectoryUser 중간 = 처음.withDisplayName("중간");
        DirectoryUser 마지막 = 처음.withDisplayName("마지막");

        // when
        AuditEntry 김의_줄 = 줄(new AuditChanges().user(처음, 중간).user(중간, 마지막).entries(), 김);

        // then
        assertThat(김의_줄.attributes()).containsExactly(new AttributeChange("displayName", "kim 님", "마지막"));
    }

    @Test
    @DisplayName("조직의 멤버가 바뀌면 조직 줄에는 멤버 들어옴·나감을, 멤버 줄에는 소속 들어옴·나감을 함께 적는다")
    void 멤버_변경() {
        // given
        GroupHeader 개발본부 = 이름표("DEV", "개발본부");

        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(개발본부, 개발본부, Set.of(김, 팀), Set.of(이))
                .entries();

        // then — 멤버는 종류(직원 먼저)·id 순이다
        AuditEntry 개발의_줄 = 줄(entries, 개발);
        assertThat(개발의_줄.attributes()).isEmpty();
        assertThat(개발의_줄.membersAdded()).containsExactly(김, 팀);
        assertThat(개발의_줄.membersRemoved()).containsExactly(이);
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
        assertThat(줄(entries, 팀).joined()).containsExactly("DEV");
        assertThat(줄(entries, 이).left()).containsExactly("DEV");
    }

    @Test
    @DisplayName("이름표가 그대로인 멤버 변경도 같은 규칙으로 적는다")
    void 멤버만_변경() {
        // given, when
        List<AuditEntry> entries = new AuditChanges().membership("DEV", Set.of(), Set.of(팀)).entries();

        // then
        assertThat(줄(entries, 개발).membersRemoved()).containsExactly(팀);
        assertThat(줄(entries, 개발).created()).isFalse();
        assertThat(줄(entries, 팀).left()).containsExactly("DEV");
    }

    @Test
    @DisplayName("조직을 만들고 지우면 이름표 속성과 생성·삭제를 적는다")
    void 조직_생성과_삭제() {
        // given, when
        List<AuditEntry> entries = new AuditChanges()
                .group(null, 이름표("DEV", "개발본부"), Set.of(김), Set.of())
                .group(이름표("TEAM", "옛팀"), null, Set.of(), Set.of(이))
                .entries();

        // then
        assertThat(줄(entries, 개발).created()).isTrue();
        assertThat(줄(entries, 개발).attributes()).containsExactly(
                new AttributeChange("externalId", null, "cn=DEV"),
                new AttributeChange("displayName", null, "개발본부"));
        assertThat(줄(entries, 팀).deleted()).isTrue();
        assertThat(줄(entries, 팀).membersRemoved()).containsExactly(이);
    }

    @Test
    @DisplayName("튜플은 user 쪽 대상의 줄에 적는다 — 쓴 것은 얻음, 지운 것은 잃음")
    void 튜플은_user_쪽_대상에() {
        // given
        RelationTuple 김_개발 = RelationTuple.directMember("kim", "DEV");
        RelationTuple 팀_개발 = RelationTuple.child("TEAM", "DEV");
        RelationTuple 이_개발 = RelationTuple.directMember("lee", "DEV");
        TupleWriteResult result = new TupleWriteResult(Set.of(김_개발, 팀_개발), Set.of(이_개발), List.of());

        // when
        List<AuditEntry> entries = new AuditChanges().tuples(result).entries();

        // then
        assertThat(줄(entries, 김).granted()).containsExactly(김_개발);
        assertThat(줄(entries, 팀).granted()).containsExactly(팀_개발);
        assertThat(줄(entries, 이).revoked()).containsExactly(이_개발);
        assertThat(entries).extracting(AuditEntry::subject).doesNotContain(개발);
    }

    @Test
    @DisplayName("지운 튜플만 받으면 쓴 줄은 남지 않는다 — 재적재")
    void 지운_줄만() {
        // given
        RelationTuple 유령 = RelationTuple.directMember("ghost", "DEV");

        // when
        List<AuditEntry> entries = new AuditChanges().revoked(Set.of(유령)).entries();

        // then
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.subject()).isEqualTo(MemberRef.user("ghost"));
            assertThat(entry.revoked()).containsExactly(유령);
            assertThat(entry.granted()).isEmpty();
        });
    }

    @Test
    @DisplayName("전체 교체의 비교 결과를 대상별 줄로 옮긴다")
    void 전체_교체_결과() {
        // given
        DirectoryChanges changes = new DirectoryChanges(
                List.of(new DirectoryChanges.UserDiff(null, 직원("kim"))),
                List.of(new DirectoryChanges.GroupDiff(null, 이름표("DEV", "개발본부"), Set.of(김), Set.of())));

        // when
        List<AuditEntry> entries = new AuditChanges().directory(changes).entries();

        // then
        assertThat(줄(entries, 김).created()).isTrue();
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
        assertThat(줄(entries, 개발).created()).isTrue();
        assertThat(줄(entries, 개발).membersAdded()).containsExactly(김);
    }

    @Test
    @DisplayName("이름표가 그대로인 결과 뒤에 그 조직의 삭제가 오면 삭제 줄에 지우기 전 이름표가 남는다")
    void 멤버만_빠짐_뒤의_삭제는_이름표를_남긴다() {
        // given — 전체 교체가 GSI 밖 조직의 "멤버만 빠짐"(이름표를 모름)을 먼저 알리고, 그 조직의 삭제가 뒤에 온다
        GroupHeader 모르는_이름표 = new GroupHeader("TEAM", null, null);

        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(모르는_이름표, 모르는_이름표, Set.of(), Set.of(김))
                .group(이름표("TEAM", "옛팀"), null, Set.of(), Set.of(이))
                .entries();

        // then
        AuditEntry 팀의_줄 = 줄(entries, 팀);
        assertThat(팀의_줄.deleted()).isTrue();
        assertThat(팀의_줄.attributes()).containsExactly(
                new AttributeChange("externalId", "cn=TEAM", null),
                new AttributeChange("displayName", "옛팀", null));
        assertThat(팀의_줄.membersRemoved()).containsExactly(김, 이);
    }

    @Test
    @DisplayName("삭제가 먼저 오고 이름표가 그대로인 결과가 뒤에 와도 삭제 줄이다")
    void 삭제_뒤의_멤버만_빠짐도_삭제_줄이다() {
        // given
        GroupHeader 옛팀 = 이름표("TEAM", "옛팀");

        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(옛팀, null, Set.of(), Set.of(이))
                .group(옛팀, 옛팀, Set.of(), Set.of(김))
                .entries();

        // then
        AuditEntry 팀의_줄 = 줄(entries, 팀);
        assertThat(팀의_줄.deleted()).isTrue();
        assertThat(팀의_줄.attributes()).containsExactly(
                new AttributeChange("externalId", "cn=TEAM", null),
                new AttributeChange("displayName", "옛팀", null));
        assertThat(팀의_줄.membersRemoved()).containsExactly(김, 이);
    }

    @Test
    @DisplayName("이름표가 그대로인 결과 하나만 오면 지금과 같다 — 생성·삭제·속성 없이 멤버 변화만 적는다")
    void 이름표가_그대로인_결과_하나는_멤버만_적는다() {
        // when
        List<AuditEntry> entries = new AuditChanges()
                .group(이름표("DEV", "개발"), 이름표("DEV", "개발"), Set.of(김), Set.of())
                .entries();

        // then
        AuditEntry 개발의_줄 = 줄(entries, 개발);
        assertThat(개발의_줄.created()).isFalse();
        assertThat(개발의_줄.deleted()).isFalse();
        assertThat(개발의_줄.attributes()).isEmpty();
        assertThat(개발의_줄.membersAdded()).containsExactly(김);
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
    }

    @Test
    @DisplayName("전체 교체가 변경마다 알리는 것을 받아 같은 규칙으로 적는다")
    void 전체_교체의_알림을_받는다() {
        // given
        AuditChanges 모음 = new AuditChanges();
        DirectoryChanges.Listener 듣는다 = 모음.directoryListener();

        // when
        듣는다.user(new DirectoryChanges.UserDiff(null, 직원("kim")));
        듣는다.group(new DirectoryChanges.GroupDiff(null, 이름표("DEV", "개발본부"), Set.of(김), Set.of()));

        // then
        List<AuditEntry> entries = 모음.entries();
        assertThat(줄(entries, 김).created()).isTrue();
        assertThat(줄(entries, 김).joined()).containsExactly("DEV");
        assertThat(줄(entries, 개발).created()).isTrue();
        assertThat(줄(entries, 개발).membersAdded()).containsExactly(김);
    }
}
