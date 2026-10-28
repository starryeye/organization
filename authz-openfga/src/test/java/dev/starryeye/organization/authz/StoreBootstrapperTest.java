package dev.starryeye.organization.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientReadAuthorizationModelResponse;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StoreBootstrapper 의 최초 resolveStore() 동시 호출 안전성을 검증한다.
 *
 * <p>OpenFGA 는 store 이름 유일성을 강제하지 않으므로, check-then-act 로 구현하면
 * 동시에 도착한 첫 호출들이 각자 store 를 만들어 이름이 같은 store 가 여러 개
 * 생기고 호출자마다 다른 storeId 에 바인딩될 수 있다. 이 테스트는 그런 회귀가
 * 생기면 실패한다.
 *
 * <p>Check 를 쓰지 않으므로 no-read 규칙과 무관하지만, listStores() 로 store 개수를
 * 세는 것은 테스트 전용 검증이며 프로덕션 코드는 이 메서드를 호출하지 않는다.
 */
class StoreBootstrapperTest extends OpenFgaTestSupport {

    @Test
    @DisplayName("동시에 처음 resolveStore 를 호출해도 같은 이름의 store 는 하나만 생긴다")
    void 동시_최초_해석은_store_를_하나만_만든다() {
        // given — 아직 아무도 해석하지 않은 새 이름의 store 를 노리는 여러 호출자
        OpenFgaProperties freshProperties = new OpenFgaProperties();
        freshProperties.setApiUrl(properties.getApiUrl());
        freshProperties.setStoreName("concurrent-" + UUID.randomUUID());
        freshProperties.setCreateStore(true);
        freshProperties.setWriteBatchSize(properties.getWriteBatchSize());
        freshProperties.setMaxRetries(properties.getMaxRetries());
        StoreBootstrapper freshBootstrapper = new StoreBootstrapper(freshProperties);

        // when — 20개의 구독자가 동시에 최초 resolveStore() 를 호출한다
        List<String> storeIds = Flux.range(0, 20)
                .flatMap(i -> freshBootstrapper.resolveStore().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();

        // then — 모두 같은 storeId 를 받았고, 실제로 그 이름의 store 는 하나뿐이다
        assertThat(storeIds).isNotNull().hasSize(20);
        assertThat(new HashSet<>(storeIds)).as("모든 호출자가 같은 storeId 를 공유해야 한다").hasSize(1);

        long matching = countStoresNamed(freshProperties.getStoreName());
        assertThat(matching).as("같은 이름의 store 가 정확히 하나만 존재해야 한다").isEqualTo(1);
    }

    /**
     * findStoreIdByName() 이 listStores() 의 첫 페이지(OpenFGA 기본 50개)만 보고 판단하면,
     * 그보다 많은 store 가 있는 공유 서버에서 이미 존재하는 store 를 못 찾고 지나쳐
     * createStore 로 넘어가 같은 이름의 store 를 하나 더 만든다. continuation token 을
     * 끝까지 따라가야 이 회귀를 잡는다.
     */
    @Test
    @DisplayName("store 가 한 페이지보다 많아도 뒤 페이지의 기존 store 를 찾아내 중복 생성하지 않는다")
    void 여러_페이지에_걸쳐_있어도_기존_store_를_찾는다() throws Exception {
        // given — 다른 이름의 store 를 한 페이지를 넘길 만큼 잔뜩 먼저 만들어 둔 뒤,
        // 대상 store 를 맨 마지막에 만든다. OpenFGA store id 는 시간순으로 정렬되는
        // ULID 이므로, 목록이 생성 순으로 나온다면 대상이 가장 최근 것이라 앞쪽이 아니라
        // 뒤쪽 페이지에 위치하게 된다.
        OpenFgaProperties targetProperties = new OpenFgaProperties();
        targetProperties.setApiUrl(properties.getApiUrl());
        targetProperties.setStoreName("paged-target-" + UUID.randomUUID());
        targetProperties.setWriteBatchSize(properties.getWriteBatchSize());
        targetProperties.setMaxRetries(properties.getMaxRetries());

        OpenFgaClient rawClient = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        String fillerPrefix = "filler-" + UUID.randomUUID() + "-";
        for (int i = 0; i < 150; i++) {
            createStoreDirectly(rawClient, fillerPrefix + i);
        }
        String preCreatedStoreId = createStoreDirectly(rawClient, targetProperties.getStoreName());

        // when
        StoreBootstrapper targetBootstrapper = new StoreBootstrapper(targetProperties);
        String resolvedStoreId = targetBootstrapper.resolveStore().block();

        // then — 새로 만든 게 아니라 미리 만들어 둔 store 를 그대로 찾아 썼어야 한다
        assertThat(resolvedStoreId).isEqualTo(preCreatedStoreId);
        assertThat(countStoresNamed(targetProperties.getStoreName()))
                .as("페이지를 넘겨서라도 기존 store 를 찾았어야 하므로 중복이 생기면 안 된다")
                .isEqualTo(1);
    }

    /**
     * 같은 이름의 store 가 이미 둘 이상이면(과거에 이 경쟁 문제를 이미 한 번 겪었다는 뜻)
     * findFirst() 로 아무거나 골라 쓰는 대신 에러로 멈춰야 한다. 임의로 고르면 소비자가
     * 조회하는 store 와 이 앱이 쓰는 store 가 어긋날 수 있어 상황을 더 악화시킨다.
     */
    @Test
    @DisplayName("clientFor 는 같은 storeId 에 대해 client 를 다시 만들지 않는다")
    void clientFor_는_storeId_별로_client_를_재사용한다() {
        // given — Check 는 응답 한 줄마다 이 메서드를 부른다. 경로 200개짜리 직원 상세
        // 하나가 커넥션 풀과 셀렉터 스레드를 200벌 만들던 자리다.
        StoreBootstrapper bootstrapper = new StoreBootstrapper(properties);

        // when
        OpenFgaClient first = bootstrapper.clientFor("store-a");
        OpenFgaClient again = bootstrapper.clientFor("store-a");
        OpenFgaClient other = bootstrapper.clientFor("store-b");

        // then — storeId 로 키를 잡으므로 격리 성질은 그대로다. 다른 store 는 다른 client 다
        assertThat(again).isSameAs(first);
        assertThat(other).isNotSameAs(first);
    }

    @Test
    @DisplayName("인가 모델은 clientFor 가 주는 client 로 등록한다 — 모델 쓰기가 거듭 실패해도 시도마다 HTTP client 를 새로 만들지 않는다")
    void 모델_등록은_storeId_별_client_를_쓴다() {
        // given
        StoreBootstrapper 부트스트래퍼 = new StoreBootstrapper(새_속성("shared-client-"));

        // when
        String 번호 = 부트스트래퍼.resolveStore().block(Duration.ofSeconds(10));

        // then — Check 가 요청마다 준비 과정을 타므로, 모델 등록이 실패하는 동안 시도마다 client 가 하나씩 쌓이던 자리다
        assertThat(부트스트래퍼.client()).isSameAs(부트스트래퍼.clientFor(번호));
    }

    @Test
    @DisplayName("같은 이름의 store 가 이미 둘 이상이면 임의로 고르지 않고 에러로 멈춘다")
    void 같은_이름의_store_가_이미_여러개면_에러로_멈춘다() throws Exception {
        // given — 이름이 같은 store 두 개를 미리 만들어 경쟁으로 이미 어긋난 상태를 흉내낸다
        OpenFgaProperties duplicateProperties = new OpenFgaProperties();
        duplicateProperties.setApiUrl(properties.getApiUrl());
        duplicateProperties.setStoreName("duplicate-" + UUID.randomUUID());
        duplicateProperties.setWriteBatchSize(properties.getWriteBatchSize());
        duplicateProperties.setMaxRetries(properties.getMaxRetries());

        OpenFgaClient rawClient = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        createStoreDirectly(rawClient, duplicateProperties.getStoreName());
        createStoreDirectly(rawClient, duplicateProperties.getStoreName());

        // when, then
        StoreBootstrapper duplicateBootstrapper = new StoreBootstrapper(duplicateProperties);
        assertThatThrownBy(() -> duplicateBootstrapper.resolveStore().block())
                .hasMessageContaining(duplicateProperties.getStoreName());
    }

    // ---------- 동시 첫 생성 수렴(점검 M17) ----------

    @Test
    @DisplayName("store 를 만든 뒤 같은 이름이 먼저 있으면, 먼저 만든 것을 쓰고 방금 만든 자기 store 를 지운다")
    void 먼저_만든_store_가_이긴다() throws Exception {
        // given — 다른 인스턴스가 먼저 만들고, 내가 곧이어 만든 순간
        OpenFgaProperties 속성 = 새_속성("converge-");
        OpenFgaClient raw = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        String 먼저 = createStoreDirectly(raw, 속성.getStoreName());
        Thread.sleep(5);
        String 나중 = createStoreDirectly(raw, 속성.getStoreName());

        // when
        String 고른것 = new StoreBootstrapper(속성).convergeAfterCreate(나중).block(Duration.ofSeconds(10));

        // then
        assertThat(고른것).isEqualTo(먼저);
        assertThat(countStoresNamed(속성.getStoreName()))
                .as("늦게 만든 쪽이 자기 store 를 지워야 하나로 모인다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("내가 가장 먼저 만들었으면 그대로 쓰고, 남이 만든 store 는 지우지 않는다")
    void 내가_먼저면_그대로_쓴다() throws Exception {
        // given
        OpenFgaProperties 속성 = 새_속성("converge-first-");
        OpenFgaClient raw = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()));
        String 먼저 = createStoreDirectly(raw, 속성.getStoreName());
        Thread.sleep(5);
        createStoreDirectly(raw, 속성.getStoreName());

        // when
        String 고른것 = new StoreBootstrapper(속성).convergeAfterCreate(먼저).block(Duration.ofSeconds(10));

        // then — 남의 것은 그쪽이 지운다. 여기서 지우면 그쪽이 쓰려던 store 가 사라진다
        assertThat(고른것).isEqualTo(먼저);
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 OpenFGA 에 부트스트래퍼 둘이 동시에 떠도 store 하나로 모인다")
    void 두_인스턴스가_동시에_떠도_하나로_모인다() {
        // given — 같은 이름을 노리는 서로 다른 두 인스턴스
        OpenFgaProperties 속성 = 새_속성("twin-");
        StoreBootstrapper 가 = new StoreBootstrapper(속성);
        StoreBootstrapper 나 = new StoreBootstrapper(속성);

        // when
        List<String> 번호들 = Flux.merge(
                        가.resolveStore().subscribeOn(Schedulers.parallel()),
                        나.resolveStore().subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(Duration.ofSeconds(30));

        // then
        assertThat(new HashSet<>(번호들)).as("두 인스턴스가 같은 장부를 써야 한다").hasSize(1);
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);
    }

    // ---------- store 생성 스위치·이름 필수·모델 비교(설계 2026-10-27 D2) ----------

    @Test
    @DisplayName("같은 store 로 두 번 부트스트랩하면 인가 모델은 하나다 — 기동마다 모델 버전이 쌓이지 않는다")
    void 같은_store_로_두_번_부트스트랩하면_모델은_하나다() {
        // given — 시험 도우미가 이미 store 를 만들고 모델을 한 번 썼다
        String 번호 = bootstrapper.resolveStore().block(Duration.ofSeconds(10));

        // when — 재시작한 앱처럼 새 부트스트래퍼가 같은 이름을 다시 해석한다
        String 다시 = new StoreBootstrapper(properties).resolveStore().block(Duration.ofSeconds(10));

        // then
        assertThat(다시).isEqualTo(번호);
        assertThat(모델_수(번호)).as("내용이 같은 모델을 다시 쓰면 기동마다 버전이 하나씩 쌓인다").isEqualTo(1);
    }

    @Test
    @DisplayName("최신 모델이 우리 모델과 다르면 새로 쓴다")
    void 모델이_다르면_새로_쓴다() throws Exception {
        // given — 누군가 타입 하나짜리 다른 모델을 최신으로 써 두었다(도우미가 쓴 것과 합쳐 둘)
        String 번호 = bootstrapper.resolveStore().block(Duration.ofSeconds(10));
        OpenFgaClient raw = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()).storeId(번호));
        raw.writeAuthorizationModel(new WriteAuthorizationModelRequest()
                .schemaVersion("1.1")
                .typeDefinitions(List.of(new TypeDefinition().type("user")))).get();
        assertThat(모델_수(번호)).isEqualTo(2);

        // when
        new StoreBootstrapper(properties).resolveStore().block(Duration.ofSeconds(10));

        // then — 우리 모델이 최신이 되도록 하나 더 썼다
        assertThat(모델_수(번호)).isEqualTo(3);
    }

    @Test
    @DisplayName("최신 모델 읽기가 값 없이 끝나면 견줄 수 없으니 쓴다 — 읽기를 건너뛰고 번호만 기억하는 쪽으로 틀리지 않는다")
    void 최신_모델_읽기가_비어_끝나면_쓴다() {
        // given — 모델이 이미 하나 있고, 최신 모델 읽기가 응답 없이 끝나는 클라이언트를 쓰는 부트스트래퍼
        String 번호 = bootstrapper.resolveStore().block(Duration.ofSeconds(10));
        assertThat(모델_수(번호)).isEqualTo(1);
        StoreBootstrapper 비어_읽는_부트스트래퍼 = new StoreBootstrapper(properties) {
            @Override
            public OpenFgaClient clientFor(String storeId) {
                try {
                    return new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()).storeId(storeId)) {
                        @Override
                        public CompletableFuture<ClientReadAuthorizationModelResponse> readLatestAuthorizationModel() {
                            return CompletableFuture.completedFuture(null);
                        }
                    };
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        // when
        비어_읽는_부트스트래퍼.resolveStore().block(Duration.ofSeconds(10));

        // then — 같은 모델이어도 견주지 못했으니 썼다. 건너뛰었다면 1 이다
        assertThat(모델_수(번호)).isEqualTo(2);
    }

    @Test
    @DisplayName("새 이름인데 생성이 꺼져 있으면 오류이고 store 를 만들지 않는다 — 이름 오타가 빈 store 를 새로 만들지 않는다")
    void 새_이름에_생성이_꺼져_있으면_오류이고_store_를_만들지_않는다() {
        // given — 운영 기본값(생성 꺼짐)과 이 컨테이너에 없는 이름
        OpenFgaProperties 속성 = 새_속성("no-create-");
        속성.setCreateStore(false);

        // when, then
        assertThatThrownBy(() -> new StoreBootstrapper(속성).resolveStore().block(Duration.ofSeconds(10)))
                .isInstanceOf(StoreNotFoundException.class)
                .hasMessage("OpenFGA store %s 이 없다 — 처음 설치라면 openfga.create-store=true 로 한 번 띄운다"
                        .formatted(속성.getStoreName()));
        assertThat(countStoresNamed(속성.getStoreName())).isZero();
    }

    @Test
    @DisplayName("store 이름이 비어 있으면 부트스트래퍼를 만들 수 없다 — 이름 기본값이 없다")
    void store_이름이_비어_있으면_만들_수_없다() {
        // given — 이름을 적지 않은 설정과 빈칸만 적은 설정
        OpenFgaProperties 없음 = new OpenFgaProperties();
        OpenFgaProperties 빈칸 = new OpenFgaProperties();
        빈칸.setStoreName("  ");

        // when, then
        assertThat(없음.getStoreName()).as("클래스 기본값이 없어야 이름을 빠뜨린 배포가 조용히 지나가지 않는다").isNull();
        assertThatThrownBy(() -> new StoreBootstrapper(없음))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("openfga.store-name 이 비어 있다 — 설정에 store 이름을 적는다");
        assertThatThrownBy(() -> new StoreBootstrapper(빈칸))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("openfga.store-name 이 비어 있다 — 설정에 store 이름을 적는다");
    }

    // ---------- 모델 등록 뒤에만 번호를 기억한다(점검 M6) ----------

    @Test
    @DisplayName("인가 모델 등록이 실패하면 번호를 기억하지 않고, 다음 시도가 같은 store 에 모델을 등록한다")
    void 모델_등록이_실패하면_번호를_기억하지_않는다() {
        // given — 첫 모델 등록만 실패하는 부트스트래퍼. 빈 타입 정의는 OpenFGA 가 거절한다
        OpenFgaProperties 속성 = 새_속성("model-fail-");
        AtomicBoolean 실패시킨다 = new AtomicBoolean(true);
        StoreBootstrapper 부트스트래퍼 = new StoreBootstrapper(속성) {
            @Override
            WriteAuthorizationModelRequest authorizationModel() {
                return 실패시킨다.getAndSet(false)
                        ? new WriteAuthorizationModelRequest().schemaVersion("1.1").typeDefinitions(List.of())
                        : super.authorizationModel();
            }
        };

        // when — 첫 해석은 모델 등록에서 실패한다
        assertThatThrownBy(() -> 부트스트래퍼.resolveStore().block()).isInstanceOf(RuntimeException.class);

        // then — 번호를 기억하지 않았다. 기억했다면 이 프로세스는 재시작 전까지 모델 없는 store 를 쓴다
        assertThatThrownBy(부트스트래퍼::client).hasMessageContaining("해석되지 않았다");
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);

        // when — 다시 해석하면
        String 번호 = 부트스트래퍼.resolveStore().block(Duration.ofSeconds(10));

        // then — 새로 만들지 않고 아까 만든 store 에 모델을 등록했다
        assertThat(번호).isNotBlank();
        assertThat(countStoresNamed(속성.getStoreName())).isEqualTo(1);
        assertThat(부트스트래퍼.client()).isNotNull();
    }

    // ---------- findExistingStore: 캐시한 store 를 실제로 묻는다(설계 2026-10-27 §7) ----------

    @Test
    @DisplayName("해석한 store 가 있으면 findExistingStore 는 그 번호를 준다")
    void 해석한_store_가_있으면_findExistingStore_가_그_번호를_준다() {
        // given — OpenFGA를_준비한다() 가 이미 해석했다. 캐시한 번호가 있다
        String 번호 = bootstrapper.resolveStore().block(Duration.ofSeconds(10));

        // when
        String 찾은_번호 = bootstrapper.findExistingStore().block(Duration.ofSeconds(10));

        // then
        assertThat(찾은_번호).isEqualTo(번호);
    }

    @Test
    @DisplayName("해석한 store 가 지워지면 findExistingStore 가 빈다 — 캐시한 번호를 묻지 않고 돌려주지 않는다")
    void 해석한_store_가_지워지면_findExistingStore_가_빈다() throws Exception {
        // given — 이미 해석했고(캐시한 번호가 있다), 누군가 부트스트래퍼를 거치지 않고 그 store 를 지웠다
        String 번호 = bootstrapper.resolveStore().block(Duration.ofSeconds(10));
        new OpenFgaClient(new ClientConfiguration().apiUrl(properties.getApiUrl()).storeId(번호)).deleteStore().get();

        // when
        String 찾은_번호 = bootstrapper.findExistingStore().block(Duration.ofSeconds(10));

        // then — 캐시를 믿고 번호를 주면 지운 store 도 있다고 읽힌다
        assertThat(찾은_번호).isNull();
    }

    @Test
    @DisplayName("store 를 묻는 호출이 not found 가 아닌 오류로 끝나면 빈 결과가 아니라 오류다 — 닿지 않는 OpenFGA 를 store 가 없다고 읽지 않는다")
    void 그_밖의_오류면_findExistingStore_는_오류다() {
        // given — 해석을 마친 뒤로는 닿지 않는 주소를 보는 client 를 주는 부트스트래퍼
        AtomicBoolean 닿지_않는다 = new AtomicBoolean(false);
        StoreBootstrapper 부트스트래퍼 = new StoreBootstrapper(새_속성("unreachable-")) {
            @Override
            public OpenFgaClient clientFor(String storeId) {
                if (!닿지_않는다.get()) {
                    return super.clientFor(storeId);
                }
                try {
                    return new OpenFgaClient(new ClientConfiguration().apiUrl("http://127.0.0.1:1").storeId(storeId));
                } catch (Exception e) {
                    throw new IllegalStateException("테스트용 client 생성 실패", e);
                }
            }
        };
        부트스트래퍼.resolveStore().block(Duration.ofSeconds(10));
        닿지_않는다.set(true);

        // when, then — 오류가 그대로 나온다. 빈 결과였다면 block 은 null 을 주고 던지지 않는다
        assertThatThrownBy(() -> 부트스트래퍼.findExistingStore().block(Duration.ofSeconds(10)))
                .isInstanceOf(RuntimeException.class);
    }
}
