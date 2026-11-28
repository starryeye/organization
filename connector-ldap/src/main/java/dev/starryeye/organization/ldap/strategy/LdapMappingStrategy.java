package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectoryAccountSource.AccountState;
import org.springframework.ldap.core.LdapTemplate;

/**
 * LDAP 은 소속을 표현하는 방법이 두 가지다.
 * 어느 쪽을 쓰든 같은 {@link DirectorySnapshot} 을 만들어 반환하므로 이후 로직은 전략을 모른다.
 */
public interface LdapMappingStrategy {

    DirectorySnapshot read(LdapTemplate template);

    /**
     * 직원 한 사람의 계정 상태를 읽는다(설계 2026-11-28 V3·§5.2). 정기 읽기와 같은 베이스·필터·속성·매퍼에 식별 속성을 AND 로 더하고,
     * 못 찾으면 현재상태의 {@code externalId} 를 base 범위로 다시 읽는다. 두 전략이 구현한다 — 시험 대역이 이 인터페이스를 람다로 구현하므로
     * 기본 구현은 던진다.
     *
     * @param employeeId 직원 id(정규화한 값)
     * @param externalId 현재상태가 가진 그 직원의 DN. 없으면 null
     */
    default AccountState readAccount(LdapTemplate template, String employeeId, String externalId) {
        throw new UnsupportedOperationException("이 전략은 한 사람 읽기를 하지 않는다");
    }
}
