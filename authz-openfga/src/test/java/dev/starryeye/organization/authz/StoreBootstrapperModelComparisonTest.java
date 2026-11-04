package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.Condition;
import dev.openfga.sdk.api.model.Metadata;
import dev.openfga.sdk.api.model.RelationMetadata;
import dev.openfga.sdk.api.model.RelationReference;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.Userset;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버의 최신 인가 모델과 우리 모델을 견주는 규칙(설계 2026-10-27 §4). 컨테이너 없이 순수 단위로 본다 —
 * 같은 모델이면 쓰지 않아야 기동마다 버전이 쌓이지 않고, 다른 모델이면 쓰지 않는 쪽으로 틀리면 안 된다.
 * 견줄 우리 모델을 클래스패스에서 읽는 길도 여기서 본다.
 */
class StoreBootstrapperModelComparisonTest {

    private static WriteAuthorizationModelRequest 우리() {
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setStoreName("model-comparison-test");
        return new StoreBootstrapper(properties).authorizationModel();
    }

    /** 서버가 돌려주는 모양: 우리 요청과 같은 세 칸에 서버가 매긴 id 가 더해진다. */
    private static AuthorizationModel 서버의(WriteAuthorizationModelRequest 요청) {
        return new AuthorizationModel()
                .id("01HXTESTMODEL")
                .schemaVersion(요청.getSchemaVersion())
                .typeDefinitions(요청.getTypeDefinitions())
                .conditions(요청.getConditions());
    }

    /**
     * 한 타입에 직접 관계 하나를 둔 요청. {@code this}(직접 관계)와 {@code directly_related_user_types} 의 {@code wildcard} 는
     * 값이 빈 객체 {@code {}} 하나뿐인 표지 칸이다 — 있고 없고가 곧 모델의 내용이다.
     */
    private static WriteAuthorizationModelRequest 직접_관계_모델(Userset 관계, RelationReference 참조) {
        return new WriteAuthorizationModelRequest()
                .schemaVersion("1.1")
                .typeDefinitions(List.of(new TypeDefinition()
                        .type("group")
                        .relations(Map.of("viewer", 관계))
                        .metadata(new Metadata().relations(Map.of("viewer",
                                new RelationMetadata().directlyRelatedUserTypes(List.of(참조)))))));
    }

    private static Userset 직접_관계() {
        return new Userset()._this(Map.of());
    }

    @Test
    @DisplayName("스레드의 문맥 클래스 로더가 이 모듈을 보지 못해도 인가 모델을 읽는다 — 부트 fat jar 에서 ForkJoinPool 스레드가 읽는 경우")
    void 문맥_클래스_로더가_이_모듈을_보지_못해도_인가_모델을_읽는다() {
        // given — 부트 fat jar(java -jar)에서 모델을 읽는 ForkJoinPool 스레드의 문맥 클래스 로더는 안쪽 jar 를 보지 못한다. 그 로더로 자원을 찾으면
        // 기동 때 WARN 과 SCIM 503 이 났다(bootRun·시험에서는 보이지 않는다). 부모가 부트스트랩 로더뿐인 빈 로더로 흉내 낸다
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setStoreName("model-resource-test");
        var 부트스트래퍼 = new StoreBootstrapper(properties);
        Thread 스레드 = Thread.currentThread();
        ClassLoader 원래_로더 = 스레드.getContextClassLoader();
        스레드.setContextClassLoader(new URLClassLoader(new URL[0], null));
        WriteAuthorizationModelRequest 모델;
        try {
            // when
            모델 = 부트스트래퍼.authorizationModel();
        } finally {
            스레드.setContextClassLoader(원래_로더);
        }

        // then
        assertThat(모델.getSchemaVersion()).isEqualTo("1.1");
        assertThat(모델.getTypeDefinitions()).isNotEmpty();
    }

    @Test
    @DisplayName("최신 모델이 없으면 다르다 — 모델이 한 번도 쓰이지 않은 store 는 쓴다")
    void 최신_모델이_없으면_다르다() {
        // given, when, then
        assertThat(StoreBootstrapper.같은_모델인가(null, 우리())).isFalse();
    }

    @Test
    @DisplayName("세 칸이 같으면 서버가 매긴 id 가 있어도 같고, 없는 칸과 빈 칸은 구별하지 않는다")
    void 세_칸이_같으면_같다() {
        // given — 서버 모델은 id 가 있고 conditions 가 빈 맵이다. 우리 요청은 conditions 가 없다
        WriteAuthorizationModelRequest 요청 = 우리();
        AuthorizationModel 서버 = 서버의(요청).conditions(Map.of());

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isTrue();
    }

    @Test
    @DisplayName("타입 정의가 다르면 다르다")
    void 타입_정의가_다르면_다르다() {
        // given
        WriteAuthorizationModelRequest 요청 = 우리();
        AuthorizationModel 서버 = 서버의(요청).typeDefinitions(List.of(new TypeDefinition().type("user")));

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isFalse();
    }

    @Test
    @DisplayName("조건만 다르면 다르다")
    void 조건만_다르면_다르다() {
        // given — 타입 정의와 스키마 버전은 같고 서버 모델에만 조건이 있다
        WriteAuthorizationModelRequest 요청 = 우리();
        AuthorizationModel 서버 = 서버의(요청)
                .conditions(Map.of("late", new Condition().name("late").expression("elapsed > 3")));

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isFalse();
    }

    @Test
    @DisplayName("스키마 버전만 다르면 다르다")
    void 스키마_버전만_다르면_다르다() {
        // given
        WriteAuthorizationModelRequest 요청 = 우리();
        AuthorizationModel 서버 = 서버의(요청).schemaVersion("1.0");

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isFalse();
    }

    @Test
    @DisplayName("와일드카드가 있고 없고만 다르면 다르다 — [user] 를 [user:*] 로 바꾼 모델을 같다고 보고 건너뛰지 않는다")
    void 와일드카드의_유무만_다르면_다르다() {
        // given — 서버에는 user 만, 우리 요청에는 user:* 가 있다. wildcard 의 값은 빈 객체뿐이다
        WriteAuthorizationModelRequest 요청 = 직접_관계_모델(직접_관계(),
                new RelationReference().type("user").wildcard(Map.of()));
        AuthorizationModel 서버 = 서버의(직접_관계_모델(직접_관계(), new RelationReference().type("user")));

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isFalse();
    }

    @Test
    @DisplayName("직접 관계 표지(this)가 있고 없고만 다르면 다르다")
    void 직접_관계_표지의_유무만_다르면_다르다() {
        // given — 서버 모델의 viewer 에는 풀이가 없다
        WriteAuthorizationModelRequest 요청 = 직접_관계_모델(직접_관계(), new RelationReference().type("user"));
        AuthorizationModel 서버 = 서버의(직접_관계_모델(new Userset(), new RelationReference().type("user")));

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isFalse();
    }

    @Test
    @DisplayName("직접 관계 표지(this)와 와일드카드가 양쪽에 똑같이 있으면 같다")
    void 빈_객체_표지가_양쪽에_있으면_같다() {
        // given
        WriteAuthorizationModelRequest 요청 = 직접_관계_모델(직접_관계(),
                new RelationReference().type("user").wildcard(Map.of()));
        AuthorizationModel 서버 = 서버의(직접_관계_모델(직접_관계(),
                new RelationReference().type("user").wildcard(Map.of())));

        // when, then
        assertThat(StoreBootstrapper.같은_모델인가(서버, 요청)).isTrue();
    }
}
