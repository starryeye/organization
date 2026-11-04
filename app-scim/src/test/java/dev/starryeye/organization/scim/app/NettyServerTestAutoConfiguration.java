package dev.starryeye.organization.scim.app;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.reactor.netty.ReactorNettyConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.embedded.netty.NettyRouteProvider;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.ReactorResourceFactory;
import reactor.netty.http.server.HttpServer;

/**
 * 시험에서도 app-scim 을 운영과 같은 Reactor Netty 위에 띄운다. 진짜 인가 서버로 하는 시험({@link RealAuthServerEndToEndTest})을 위해 app-auth 가 이
 * 모듈의 시험 클래스패스에 올라오며 Tomcat 도 함께 온다. 부트의 리액티브 웹 서버 자동 구성은 Tomcat → Jetty → Undertow → Netty 순으로 클래스패스에 있는 첫
 * 서버를 고르므로, 그대로 두면 이 모듈의 모든 {@code @SpringBootTest} 가 Netty 가 아니라 Tomcat 위에서 WebFlux 를 돌린다 — 운영의 app-scim 클래스패스에는
 * Tomcat 이 없다. 그래서 그 자동 구성보다 먼저 Netty 서버 팩토리를 둔다. 내용은 부트의 Netty 구성({@code ReactiveWebServerFactoryConfiguration.EmbeddedNetty},
 * 패키지 밖에서 쓸 수 없다)과 같고, 부트의 서버 설정({@code server.*}·{@code server.netty.*})은 팩토리를 누가 만들었든 그대로 입혀진다.
 *
 * <p>app-auth(서블릿)에는 걸리지 않는다 — 리액티브 앱에서만 켜지고, 그 시험은 app-auth 를 띄울 때 우리 모듈의 자동 구성을 모두 뺀다.
 */
@AutoConfiguration(before = ReactiveWebServerFactoryAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(HttpServer.class)
@Import(ReactorNettyConfigurations.ReactorResourceFactoryConfiguration.class)
public class NettyServerTestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReactiveWebServerFactory.class)
    NettyReactiveWebServerFactory Netty_서버_팩토리(ReactorResourceFactory 자원, ObjectProvider<NettyRouteProvider> 경로,
                                              ObjectProvider<NettyServerCustomizer> 서버_조정) {
        NettyReactiveWebServerFactory 팩토리 = new NettyReactiveWebServerFactory();
        팩토리.setResourceFactory(자원);
        경로.orderedStream().forEach(팩토리::addRouteProviders);
        팩토리.getServerCustomizers().addAll(서버_조정.orderedStream().toList());
        return 팩토리;
    }
}
