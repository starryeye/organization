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
