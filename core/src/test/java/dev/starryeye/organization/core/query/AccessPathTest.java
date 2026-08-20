package dev.starryeye.organization.core.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPathTest {

    @Test
    @DisplayName("Check 를 못 했으면 drifted 는 false")
    void check_실패시_drifted_false() {
        // given
        AccessPath path = new AccessPath("ORG001", "Engineering", AccessPath.DIRECT,
                true, null, false);

        // when, then
        assertThat(path.drifted()).isFalse();
    }

    @Test
    @DisplayName("파생값과 Check 판정이 일치하면 drifted 는 false")
    void 값이_일치하면_drifted_false() {
        // given
        AccessPath pathTrue = new AccessPath("ORG001", "Engineering", AccessPath.DIRECT,
                true, true, false);
        AccessPath pathFalse = new AccessPath("ORG002", "Sales", AccessPath.ROLLUP,
                false, false, false);

        // when, then
        assertThat(pathTrue.drifted()).isFalse();
        assertThat(pathFalse.drifted()).isFalse();
    }

    @Test
    @DisplayName("파생값과 Check 판정이 갈리면 drifted 는 true")
    void 값이_갈리면_drifted_true() {
        // given
        AccessPath driftedTrue = new AccessPath("ORG001", "Engineering", AccessPath.DIRECT,
                true, false, false);
        AccessPath driftedFalse = new AccessPath("ORG002", "Sales", AccessPath.ROLLUP,
                false, true, false);

        // when, then
        assertThat(driftedTrue.drifted()).isTrue();
        assertThat(driftedFalse.drifted()).isTrue();
    }
}
