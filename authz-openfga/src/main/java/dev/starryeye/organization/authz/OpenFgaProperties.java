package dev.starryeye.organization.authz;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("openfga")
public class OpenFgaProperties {

    private String apiUrl = "http://localhost:8080";

    /**
     * OpenFGA 사전 공유 키(설계 2026-10-28 §7). 있으면 {@link StoreBootstrapper} 가 만드는 모든 클라이언트가 {@code Authorization: Bearer} 로
     * 싣는다. 기본값이 없다 — 로컬 {@code application.yml} 이 로컬 키를, 운영 {@code application-prod.yml} 이 환경 변수
     * {@code OPENFGA_API_TOKEN} 을 적는다. 키는 로그·오류 문구에 싣지 않는다.
     */
    private String apiToken;

    /**
     * 키가 없거나 풀리지 않은 자리표시자면 기동을 멈춘다({@link OpenFgaSettingsCheck}). 기본값은 꺼짐(로컬 개발), 운영만 켠다. 부트의
     * {@code @ConfigurationProperties} 바인딩은 풀리지 않은 {@code ${OPENFGA_API_TOKEN}} 을 문자열 그대로 넣기 때문에 명시적으로 본다.
     */
    private boolean requireApiToken = false;

    /** {@code openfga.api-url} 이 {@code https://} 가 아니면 기동을 멈춘다. 키가 평문으로 다니지 않게 운영만 켠다. */
    private boolean requireTls = false;

    /**
     * 앱이 아는 유일한 식별자. storeId 는 런타임에 해석한다. 기본값이 없다(설계 2026-10-27 §4) — 키를 빠뜨리거나 비워 두면
     * {@link StoreBootstrapper} 를 만들 때 기동을 멈춘다. 두 앱의 {@code application.yml} 이 각자 값을 적는다.
     */
    private String storeName;

    /**
     * 기동 때 store 가 없으면 만든다(설계 2026-10-27 §4). 기본값은 꺼짐이다 — 이름 오타가 빈 store 를 새로 만들어 쓰기가 엉뚱한 곳으로
     * 가지 않게, 운영은 만들지 않고 멈춘다. 로컬 {@code application.yml} 만 켠다.
     */
    private boolean createStore = false;

    /** OpenFGA 트랜잭션 모드의 배치 한계 */
    private int writeBatchSize = 100;

    private int maxRetries = 3;

    /** Check·Write 묶음을 동시에 이만큼 보낸다(설계 2026-10-02 §4.3). OpenFGA 가 버거우면 낮춘다. */
    private int requestConcurrency = 4;
}
