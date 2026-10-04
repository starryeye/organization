package dev.starryeye.organization.scim;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StateMemberTypeResolverTest {

    private FakeStateRepository state;
    private StateMemberTypeResolver resolver;
    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        resolver = new StateMemberTypeResolver(state);
        logger = (Logger) LoggerFactory.getLogger(StateMemberTypeResolver.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
    }

    @AfterEach
    void 정리한다() {
        logger.detachAppender(로그);
    }

    @Test
    @DisplayName("여러 아이디를 저장소 한 번으로 판정한다 — 조직·직원·없음(직원으로 봄)")
    void 한_번에_판정한다() {
        // given
        state.groups.put("DEV", new DirectoryGroup("DEV", "DEV", "개발", Set.of()));
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));

        // when
        var 종류 = resolver.resolveAll(Set.of("DEV", "kim", "ghost")).block();

        // then
        assertThat(종류).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DEV", MemberType.GROUP, "kim", MemberType.USER, "ghost", MemberType.USER));
        assertThat(state.findMemberTypesCalls).containsExactly(Set.of("DEV", "kim", "ghost"));
    }

    @Test
    @DisplayName("있는 직원으로 판정되면 경고하지 않는다 — type 은 선택 필드라 Entra·Okta 의 정상 경로다")
    void 있는_직원이면_경고하지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));

        // when
        resolver.resolveAll(Set.of("kim")).block();

        // then
        assertThat(로그.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    @DisplayName("조직으로 추정했거나 없는 아이디면 요청당 경고 한 줄을 남긴다")
    void 추정이_위험하면_한_줄로_경고한다() {
        // given
        state.groups.put("DEV", new DirectoryGroup("DEV", "DEV", "개발", Set.of()));

        // when
        resolver.resolveAll(new LinkedHashSet<>(List.of("DEV", "ghost1", "ghost2"))).block();

        // then
        assertThat(로그.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("조직 1명", "DEV", "없음 2명", "ghost1", "ghost2");
    }

    @Test
    @DisplayName("판정할 아이디가 없으면 저장소를 읽지 않는다")
    void 비면_읽지_않는다() {
        // when
        var 종류 = resolver.resolveAll(Set.of()).block();

        // then
        assertThat(종류).isEmpty();
        assertThat(state.findMemberTypesCalls).isEmpty();
    }
}
