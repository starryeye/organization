package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import org.springframework.ldap.core.LdapTemplate;

/**
 * LDAP 은 소속을 표현하는 방법이 두 가지다.
 * 어느 쪽을 쓰든 같은 {@link DirectorySnapshot} 을 만들어 반환하므로 이후 로직은 전략을 모른다.
 */
public interface LdapMappingStrategy {

    DirectorySnapshot read(LdapTemplate template);
}
