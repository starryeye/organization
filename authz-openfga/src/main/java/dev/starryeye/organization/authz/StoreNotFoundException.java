package dev.starryeye.organization.authz;

/**
 * OpenFGA 에 연결해 store 목록을 읽었는데 {@code openfga.store-name} 의 store 가 없고, {@code openfga.create-store} 도 꺼져 있다
 * (설계 2026-10-27 §4). 이름 오타이거나 처음 설치다 — 다시 시도해서 낫는 일시 장애가 아니라 설정 오류라,
 * {@link dev.starryeye.organization.core.port.TemporaryFailureException} 가 아니다. 기동 때 나면 앱이 뜨지 않고({@link OpenFgaStoreInitializer}),
 * 시작 때 OpenFGA 에 닿지 못했다가 나중에 판명되면 그 요청이 500 으로 끝난다.
 */
public class StoreNotFoundException extends IllegalStateException {

    public StoreNotFoundException(String message) {
        super(message);
    }
}
