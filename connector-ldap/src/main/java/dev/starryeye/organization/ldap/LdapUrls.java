package dev.starryeye.organization.ldap;

import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.support.AbstractContextSource;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * LDAP 주소 목록에서 "어디에 붙었나" 를 읽는 도우미(설계 2026-11-14 F5·F7).
 *
 * <p>JNDI 는 주소 목록(공백으로 이은 {@code java.naming.provider.url})을 앞에서부터 시도하고, 붙은 커넥션의 환경에서 그 키를 <b>붙은 주소 하나로
 * 덮어쓴다</b>. 그 덮어쓴 값을 읽어 첫 주소와 다르면 운영자에게 알린다.
 */
public final class LdapUrls {

    private LdapUrls() {
    }

    /**
     * {@code 스킴://권한} 만 소문자로 남긴다 — 베이스 DN 경로와 대소문자 차이를 비교에서 뺀다. 읽을 수 없는 주소(문법 오류, 스킴·권한 없음)는 비어 있다 —
     * 이 값은 진단용이라 던져서 읽기를 실패시키지 않는다.
     */
    public static Optional<String> 주소_부분(String url) {
        try {
            URI uri = URI.create(url.strip());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return Optional.empty();
            }
            return Optional.of((uri.getScheme() + "://" + uri.getRawAuthority()).toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 커넥션이 붙은 주소. 환경에 없거나 덮어쓰이지 않은 목록(공백이 든 값)이거나 읽을 수 없으면 비어 있다. */
    public static Optional<String> 붙은_주소(DirContext ctx) {
        try {
            var env = ctx.getEnvironment();
            Object value = env == null ? null : env.get(Context.PROVIDER_URL);
            if (value instanceof String url && !url.isBlank() && url.strip().indexOf(' ') < 0) {
                return 주소_부분(url);
            }
            return Optional.empty();
        } catch (NamingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 주소 목록을 {@code k % n} 번째부터 돌린다(불변). 재시도 k 번째는 이 목록으로 읽어, 바인드는 받고 검색에서 실패한 DC 를 넘긴다 — JNDI 는 바인드 뒤의
     * 실패를 다음 주소로 넘기지 않는다(설계 2026-11-14 F6). 빈 목록은 그대로다.
     */
    public static List<String> 돌린다(List<String> urls, int k) {
        if (urls.isEmpty()) {
            return List.of();
        }
        List<String> 돌린 = new ArrayList<>(urls);
        Collections.rotate(돌린, -Math.floorMod(k, urls.size()));
        return List.copyOf(돌린);
    }

    /** 컨텍스트 소스에 설정된 첫 주소. */
    public static Optional<String> 첫_주소(ContextSource source) {
        if (source instanceof AbstractContextSource abstractSource && abstractSource.getUrls().length > 0) {
            return 주소_부분(abstractSource.getUrls()[0]);
        }
        return Optional.empty();
    }
}
