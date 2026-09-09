package dev.starryeye.organization.ldap;

import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;

/**
 * {@link LdapTemplate} 설정을 <b>한 곳에서</b> 만든다.
 *
 * <p>페이징은 커넥션 하나를 붙잡고 돌아야 해서 별도의 {@code ContextSource} 로 템플릿을 하나
 * 더 만든다. 그 템플릿의 설정을 저쪽에서 따로 적으면 두 벌이 되고, 한쪽만 바뀌어도 컴파일은
 * 통과한다 — 그리고 잘못된 쪽이 하필 <b>실제로 디렉터리를 읽는 쪽</b>이다.
 *
 * <p>{@code LdapTemplate} 의 {@code isIgnore*} 게터는 패키지 전용이라 기존 템플릿에서 값을
 * 복제할 수도 없다. 그래서 값을 읽어 옮기는 대신 만드는 자리를 하나로 모은다.
 */
public final class LdapTemplates {

    private LdapTemplates() {
    }

    public static LdapTemplate configured(ContextSource contextSource) {
        LdapTemplate template = new LdapTemplate(contextSource);
        template.setIgnorePartialResultException(true);
        // 기본값(true)으로 두면 서버가 관리 한도(sizeLimit)로 결과를 자른 뒤 예외 없이 조용히
        // 응답한다 — 잘린 목록이 대량 퇴사처럼 보여 실제 소속을 지워버릴 수 있다. false 로 두면
        // 그 경우 예외가 올라와 FAILED 로 기록되므로, 페이징을 우회하는 잘림도 안전망으로 잡는다.
        template.setIgnoreSizeLimitExceededException(false);
        return template;
    }
}
