package dev.starryeye.organization.core.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행위자 값과, Reactor Context 에서 읽는 도우미(설계 2026-10-28 인증 §5). 웹 필터와 예약 작업이 Context 에 싣고, core 가 이 도우미로 읽는다.
 */
class ActorTest {

    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;

    @BeforeEach
    void 준비한다() {
        logger = (Logger) LoggerFactory.getLogger(Actor.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
    }

    @AfterEach
    void 정리한다() {
        logger.detachAppender(로그);
    }

    @Test
    @DisplayName("client 는 토큰의 sub 에 접두사를 붙인다")
    void client_는_접두사를_붙인다() {
        // given, when
        String actor = Actor.client("ops");

        // then
        assertThat(actor).isEqualTo("client:ops");
    }

    @Test
    @DisplayName("Context 에 있으면 그 값이다")
    void Context_에_있으면_그_값이다() {
        // given
        Context context = Context.of(Actor.KEY, Actor.client("ops"));

        // when
        String actor = Actor.from(context);

        // then
        assertThat(actor).isEqualTo("client:ops");
        assertThat(로그.list).isEmpty();
    }

    @Test
    @DisplayName("Context 에 없으면 system:unknown 이다")
    void 없으면_unknown_이다() {
        // given, when
        String actor = Actor.from(Context.empty());

        // then
        assertThat(actor).isEqualTo("system:unknown");
    }

    @Test
    @DisplayName("Context 에 없으면 오류를 내지 않고 WARN 을 남긴다 — 감사가 일을 막지 않는다")
    void 없으면_경고를_남긴다() {
        // given, when
        Actor.from(Context.empty());

        // then
        assertThat(로그.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(Actor.UNKNOWN);
        });
    }

    @Test
    @DisplayName("값이 문자열이 아니거나 비었으면 없는 것과 같다")
    void 쓸_수_없는_값은_없는_것과_같다() {
        // given, when, then
        assertThat(Actor.from(Context.of(Actor.KEY, 42))).isEqualTo(Actor.UNKNOWN);
        assertThat(Actor.from(Context.of(Actor.KEY, " "))).isEqualTo(Actor.UNKNOWN);
    }

    @Test
    @DisplayName("고정된 행위자 값은 설계가 정한 문자열이다")
    void 고정_값은_설계가_정한_문자열이다() {
        // given, when, then
        assertThat(Actor.SCHEDULER).isEqualTo("system:scheduler");
        assertThat(Actor.ARCHIVE).isEqualTo("system:archive");
        assertThat(Actor.UNKNOWN).isEqualTo("system:unknown");
        assertThat(Actor.KEY).isEqualTo("dev.starryeye.organization.core.audit.Actor");
    }
}
