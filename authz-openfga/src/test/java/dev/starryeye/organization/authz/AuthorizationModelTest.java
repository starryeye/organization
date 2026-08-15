package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가 모델 JSON 이 의도대로 동작하는지 실제 Check 로 검증한다.
 *
 * <p>Check 는 <b>이 테스트에서만</b> 쓴다. 프로덕션 코드는 Write/Delete 만 호출한다.
 */
class AuthorizationModelTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;

    @BeforeEach
    void 쓰기어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
    }

    private boolean check(String user, String relation, String object) {
        try {
            return bootstrapper.client().check(new ClientCheckRequest()
                    ._object(object)
                    .relation(relation)
                    .user(user)).get().getAllowed();
        } catch (Exception e) {
            throw new IllegalStateException("Check 호출 실패", e);
        }
    }

    @Test
    @DisplayName("조직에 직접 속한 직원은 그 조직의 member 로 판정된다")
    void 직속_직원은_member다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        boolean allowed = check("user:kim", "member", "group:DEV002");

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("하위 조직의 직원은 상위 조직의 member 로 롤업된다")
    void 하위조직_직원은_상위조직_member로_롤업된다() {
        // given — DEV002 는 DEV001 의 하위 조직, kim 은 DEV002 소속
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.child("DEV002", "DEV001"),
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        boolean allowed = check("user:kim", "member", "group:DEV001");

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("세 단계로 중첩된 조직에서도 최상위까지 롤업된다")
    void 세단계_중첩도_롤업된다() {
        // given — C ⊂ B ⊂ A, kim 은 C 소속
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.child("C", "B"),
                RelationTuple.child("B", "A"),
                RelationTuple.directMember("kim", "C")))).block();

        // when
        boolean allowed = check("user:kim", "member", "group:A");

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("상위 조직의 직원이 하위 조직의 member 가 되지는 않는다")
    void 상속은_상위로만_향한다() {
        // given — park 은 상위 조직 DEV001 직속
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.child("DEV002", "DEV001"),
                RelationTuple.directMember("park", "DEV001")))).block();

        // when
        boolean allowed = check("user:park", "member", "group:DEV002");

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("direct_member 는 직속만 판정해 산하 전체와 구분된다")
    void direct_member는_직속만_판정한다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.child("DEV002", "DEV001"),
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        boolean 산하 = check("user:kim", "member", "group:DEV001");
        boolean 직속 = check("user:kim", "direct_member", "group:DEV001");

        // then
        assertThat(산하).isTrue();
        assertThat(직속).isFalse();
    }

    @Test
    @DisplayName("한글 조직코드로도 롤업이 성립한다")
    void 한글_조직코드도_롤업된다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.child("백엔드팀", "개발본부"),
                RelationTuple.directMember("kim", "백엔드팀")))).block();

        // when
        boolean allowed = check("user:kim", "member", "group:개발본부");

        // then
        assertThat(allowed).isTrue();
    }
}
