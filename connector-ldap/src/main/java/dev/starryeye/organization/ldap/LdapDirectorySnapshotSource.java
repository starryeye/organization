package dev.starryeye.organization.ldap;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.ldap.strategy.LdapMappingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.LdapTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * LDAP 은 블로킹 프로토콜이므로 boundedElastic 으로 격리한다.
 * 이벤트 루프에서 직접 호출하면 전체 애플리케이션이 멈춘다.
 */
@Slf4j
@RequiredArgsConstructor
public class LdapDirectorySnapshotSource implements DirectorySnapshotSource {

    private final LdapTemplate template;
    private final LdapMappingStrategy strategy;

    @Override
    public Mono<DirectorySnapshot> fetchAll() {
        return Mono.fromCallable(() -> strategy.read(template))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(snapshot -> log.info("LDAP 에서 직원 {}명, 조직 {}개를 읽었다",
                        snapshot.users().size(), snapshot.groups().size()));
    }
}
