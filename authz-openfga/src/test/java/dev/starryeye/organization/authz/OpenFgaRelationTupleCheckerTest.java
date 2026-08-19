package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class OpenFgaRelationTupleCheckerTest extends OpenFgaTestSupport {

    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 어댑터를_준비한다() {
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    @Test
    @DisplayName("쓴 튜플은 Check 가 true 로 답한다")
    void 쓴_튜플은_true다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("하위 조직을 통한 롤업도 한 번의 Check 로 true 가 된다")
    void 롤업도_한_번의_Check로_답한다() {
        // given — DEV001 ⊇ DEV002 ⊇ kim
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:kim", "member", "group:DEV001")).block();

        // then — member 는 direct_member or member from child 로 정의돼 있다
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("없는 튜플은 false 다")
    void 없는_튜플은_false다() {
        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:nobody", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("상속은 상위로만 향한다 — 상위 직속은 하위의 멤버가 아니다")
    void 상속은_상위로만_향한다() {
        // given
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("park", "DEV001"),
                RelationTuple.child("DEV002", "DEV001")))).block();

        // when
        Boolean allowed = checker.check(
                new RelationTuple("user:park", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("resolveStore 를 부른 적 없는 새 StoreBootstrapper 로도 이미 있는 store 를 Check 할 수 있다")
    void resolve_안한_새_부트스트래퍼로도_Check가_된다() {
        // given — 기존 bootstrapper 로 store 를 만들고 튜플을 쓴다
        writer.apply(TupleDelta.writeOnly(Set.of(
                RelationTuple.directMember("kim", "DEV002")))).block();

        // 이 프로세스 안에서 resolveStore()/recreateStore() 를 한 번도 부른 적 없는
        // 새 StoreBootstrapper — clientRef 가 비어 있는 상태에서 findExistingStore() 만으로
        // Check 가 성립해야 한다
        StoreBootstrapper 새_부트스트래퍼 = new StoreBootstrapper(properties);
        OpenFgaRelationTupleChecker 새_체커 = new OpenFgaRelationTupleChecker(새_부트스트래퍼);

        // when
        Boolean allowed = 새_체커.check(
                new RelationTuple("user:kim", "member", "group:DEV002")).block();

        // then
        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("store 가 아예 없으면 Check 는 에러로 끝난다")
    void store가_없으면_에러로_끝난다() {
        // given — 이 컨테이너에 존재한 적 없는 store 이름
        OpenFgaProperties 없는_store_속성 = new OpenFgaProperties();
        없는_store_속성.setApiUrl(properties.getApiUrl());
        없는_store_속성.setStoreName("missing-" + UUID.randomUUID());
        없는_store_속성.setWriteBatchSize(100);
        없는_store_속성.setMaxRetries(3);
        StoreBootstrapper 없는_store_부트스트래퍼 = new StoreBootstrapper(없는_store_속성);
        OpenFgaRelationTupleChecker 없는_store_체커 = new OpenFgaRelationTupleChecker(없는_store_부트스트래퍼);

        // when
        Throwable thrown = catchThrowable(() -> 없는_store_체커.check(
                new RelationTuple("user:kim", "member", "group:DEV002")).block());

        // then
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
    }
}
