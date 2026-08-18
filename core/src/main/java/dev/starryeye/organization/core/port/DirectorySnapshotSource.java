package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import reactor.core.publisher.Mono;

/**
 * 외부 디렉터리에서 전체 상태를 읽어온다.
 *
 * <p>이 인터페이스에는 증분이라는 개념이 없다. LDAP 이 pull 모델이라는 사실이 여기에 박혀 있다.
 * SCIM 인스턴스에는 이 빈이 존재하지 않는다.
 */
public interface DirectorySnapshotSource {

    Mono<DirectorySnapshot> fetchAll();
}
