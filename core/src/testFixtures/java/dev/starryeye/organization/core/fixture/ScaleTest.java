package dev.starryeye.organization.core.fixture;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 규모 테스트 표시. 5,000명 조직도와 Testcontainers 를 띄워 클래스 하나에 수십 초에서 수 분이 걸린다.
 *
 * <p>이 표시가 붙은 클래스는 기본 {@code ./gradlew test} 에서 빠지고 {@code ./gradlew scaleTest} 에서만
 * 돈다(루트 {@code build.gradle}). <b>브랜치를 머지하기 전에는 {@code scaleTest} 까지 돌린다</b> — CI 가
 * 아직 없어 그 약속이 규모 테스트가 도는 유일한 자리다.
 *
 * <p>태그 문자열을 클래스마다 흩어 쓰지 않고 여기 한 곳에 둔다. 루트 {@code build.gradle} 의
 * {@code excludeTags}/{@code includeTags} 값과 반드시 같아야 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("scale")
public @interface ScaleTest {
}
