package dev.starryeye.organization.ldap;

import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.SingleContextSource;

import java.util.function.Function;

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

    /**
     * {@code work} 를 <b>커넥션 하나</b> 위에서 실행한다.
     *
     * <p>LDAP 에는 커넥션에 묶인 상태가 둘 있고 우리는 둘 다 쓴다:
     *
     * <ul>
     *   <li><b>페이징 쿠키</b> — 다른 커넥션에서 내밀면 실제 OpenLDAP·AD 가 거절한다
     *       ({@code "paged results cookie is invalid"}).</li>
     *   <li><b>범위 검색의 값 순서</b> — 명세가 "같은 커넥션에서는 일관된다" 까지만
     *       보장하므로, 조각을 섞어 받으면 누락과 중복이 난다.</li>
     * </ul>
     *
     * <p>{@code SingleContextSource.doWithSingleContext} 를 쓰지 않는 이유는 그쪽이 넘겨주는
     * {@code LdapOperations} 가 기본 설정으로 만들어져 {@code ignoreSizeLimitExceededException}
     * 이 {@code true} 로 돌아가기 때문이다 — 서버가 자른 결과를 조용히 삼키게 된다.
     */
    public static <T> T 한_커넥션에서(LdapTemplate template, Function<LdapTemplate, T> work) {
        SingleContextSource single = new SingleContextSource(
                template.getContextSource().getReadOnlyContext());
        try {
            return work.apply(configured(single));
        } finally {
            single.destroy();
        }
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
