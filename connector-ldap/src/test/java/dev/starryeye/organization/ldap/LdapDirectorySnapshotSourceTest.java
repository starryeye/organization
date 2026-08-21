package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.LdapTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LDAP 읽기 재시도. 설계 §9 는 "LDAP 연결/조회 실패 → 백오프 3회 재시도 → FAILED" 를
 * 지시하는데, 파이프라인의 다른 외부 호출(OpenFGA·DynamoDB)과 달리 LDAP 만 보호되지
 * 않고 있었다. 하루 1회 스케줄이라 한 번 실패하면 다음 기회가 24시간 뒤다.
 */
class LdapDirectorySnapshotSourceTest {

    private static final LdapTemplate 안_쓰는_템플릿 = null;

    /** N 번 실패한 뒤 성공하는 전략. 호출 횟수를 세어 재시도가 실제로 일어났는지 본다. */
    private static class 흔들리는전략 implements LdapMappingStrategy {

        private final int 실패횟수;
        final AtomicInteger 호출수 = new AtomicInteger();

        흔들리는전략(int 실패횟수) {
            this.실패횟수 = 실패횟수;
        }

        @Override
        public DirectorySnapshot read(LdapTemplate template) {
            if (호출수.incrementAndGet() <= 실패횟수) {
                throw new IllegalStateException("LDAP 접속 실패(테스트)");
            }
            return new DirectorySnapshot(
                    Map.of("kim", new DirectoryUser("kim", "uid=kim", "kim", "김철수", null, true)),
                    Map.of());
        }
    }

    private static LdapProperties 설정(int maxRetries) {
        LdapProperties properties = new LdapProperties();
        properties.setMaxRetries(maxRetries);
        return properties;
    }

    @Test
    @DisplayName("일시적으로 실패해도 상한 안에서 다시 시도해 스냅샷을 읽어온다")
    void 일시적_실패는_재시도로_넘긴다() {
        // given — 두 번 실패한 뒤 세 번째에 성공한다
        var strategy = new 흔들리는전략(2);
        var source = new LdapDirectorySnapshotSource(안_쓰는_템플릿, strategy, 설정(3));

        // when
        var snapshot = source.fetchAll().block();

        // then
        assertThat(snapshot.users()).containsOnlyKeys("kim");
        assertThat(strategy.호출수).hasValue(3);
    }

    @Test
    @DisplayName("상한을 넘게 실패하면 에러로 끝난다 — 그 위에서 FAILED 로 기록된다")
    void 상한을_넘으면_에러로_끝난다() {
        // given — 항상 실패한다
        var strategy = new 흔들리는전략(Integer.MAX_VALUE);
        var source = new LdapDirectorySnapshotSource(안_쓰는_템플릿, strategy, 설정(3));

        // when, then
        assertThatThrownBy(() -> source.fetchAll().block()).isInstanceOf(Exception.class);

        // 최초 1회 + 재시도 3회
        assertThat(strategy.호출수).hasValue(4);
    }

    @Test
    @DisplayName("재시도 상한은 설정을 따른다")
    void 재시도_상한은_설정을_따른다() {
        // given
        var strategy = new 흔들리는전략(Integer.MAX_VALUE);
        var source = new LdapDirectorySnapshotSource(안_쓰는_템플릿, strategy, 설정(1));

        // when
        assertThatThrownBy(() -> source.fetchAll().block()).isInstanceOf(Exception.class);

        // then — 최초 1회 + 재시도 1회
        assertThat(strategy.호출수).hasValue(2);
    }

    @Test
    @DisplayName("한 번에 성공하면 다시 읽지 않는다")
    void 성공하면_한_번만_읽는다() {
        // given
        var strategy = new 흔들리는전략(0);
        var source = new LdapDirectorySnapshotSource(안_쓰는_템플릿, strategy, 설정(3));

        // when
        source.fetchAll().block();

        // then
        assertThat(strategy.호출수).hasValue(1);
    }

    @Test
    @DisplayName("재시도는 매번 전체를 처음부터 다시 읽는다 — 반쪽 스냅샷이 남지 않는다")
    void 재시도는_처음부터_다시_읽는다() {
        // given — 페이징 도중 끊긴 상황을 흉내낸다. 재시도가 이어읽기라면 첫 시도의
        // 부분 결과가 섞이겠지만, 매번 read 를 새로 부르므로 그럴 수 없다.
        var strategy = new 흔들리는전략(1);
        var source = new LdapDirectorySnapshotSource(안_쓰는_템플릿, strategy, 설정(3));

        // when
        var snapshot = source.fetchAll().block();

        // then — 성공한 시도의 결과만 온전히 담긴다
        assertThat(snapshot.users()).containsOnlyKeys("kim");
        assertThat(snapshot.groups()).isEmpty();
    }
}
