package dev.starryeye.organization.authz;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("openfga")
public class OpenFgaProperties {

    private String apiUrl = "http://localhost:8080";

    /** 앱이 아는 유일한 식별자. storeId 는 런타임에 해석한다 */
    private String storeName = "organization";

    /** OpenFGA 트랜잭션 모드의 배치 한계 */
    private int writeBatchSize = 100;

    private int maxRetries = 3;
}
