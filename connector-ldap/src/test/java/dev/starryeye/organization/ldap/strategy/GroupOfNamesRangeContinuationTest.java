package dev.starryeye.organization.ldap.strategy;

import dev.starryeye.organization.core.tuple.IdNormalizer;
import dev.starryeye.organization.ldap.LdapProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GroupOfNamesStrategy.범위가_잘린_멤버를_이어받는다} 가 이어받은 멤버를 <b>엔트리</b>에게
 * 돌려주는지, 아니면 <b>정규화된 조직코드</b>에게 돌려주는지를 검증한다.
 *
 * <p>임베디드 UnboundID 서버는 범위 검색을 하지 않아서({@link RangedAttributeReaderTest} 의
 * 클래스 설명 참고) 이 메서드를 {@link GroupOfNamesStrategy#read} 전체로 구동해 검증할 수
 * 없다 — paged 검색과 범위 이어받기를 동시에 실서버 프로토콜로 흉내 내야 하는데 뒤쪽만
 * 합성 가능하다. 그래서 <b>이 메서드 하나를 리플렉션으로 직접 호출</b>해 가장 좁은 이음매에서
 * 검증한다. {@code LdapTemplates.한_커넥션에서} 가 {@code ContextSource.getReadOnlyContext()} 로
 * 얻은 {@code DirContext} 를 그대로 감싸 재사용하므로, {@link RangedAttributeReaderTest} 처럼
 * {@code LdapOperations} 를 통째로 모킹하는 대신 그보다 한 단 아래인 {@code DirContext} 를
 * 모킹해 실제 {@code LdapTemplate.lookup} 경로를 그대로 태운다.
 *
 * <p>재현하는 사고: {@code cn=제1공장 A} (범위 검색으로 잘림, 이어받는 중)와
 * {@code cn=제1공장:A} (잘리지 않음, 이미 3명 전부)가 {@link IdNormalizer} 를 거치며 같은
 * {@code 제1공장_A} 로 뭉개진다. 이어받기를 <b>아이디</b>로 색인하면 뭉개진 두 엔트리가
 * 같은 키를 갖게 되어, 잘리지 않은 쪽까지 잘린 쪽의 이어받은 목록을 덮어쓴다 — 3명짜리
 * 조직이 남의 멤버 목록을 받는 조용한 권한 확대다. {@code realDn} 은 서버가 돌려준 진짜
 * DN 이라 절대 충돌하지 않으므로 색인은 그쪽이어야 한다.
 */
class GroupOfNamesRangeContinuationTest {

    private static final String 진짜DN_A = "cn=제1공장 A,ou=groups,dc=example,dc=com";
    private static final String 진짜DN_B = "cn=제1공장:A,ou=groups,dc=example,dc=com";

    @Test
    @DisplayName("정규화된 조직코드가 충돌해도 이어받은 멤버는 realDn 으로 자기 엔트리에만 돌아간다")
    void 코드가_충돌해도_멤버는_각자에게_돌아간다() throws Exception {
        // given — "제1공장 A" 와 "제1공장:A" 는 IdNormalizer 를 거치면 같은 코드로 뭉개진다.
        // 이 전제가 깨지면 이 테스트는 아무것도 증명하지 못하므로 먼저 확인해 둔다.
        String 아이디A = IdNormalizer.normalize("제1공장 A");
        String 아이디B = IdNormalizer.normalize("제1공장:A");
        assertThat(아이디A).as("정규화 충돌 전제").isEqualTo(아이디B);

        // given — A 는 범위 검색으로 첫 조각(2명)만 온 채 잘렸고, B 는 처음부터 3명 전부다.
        Class<?> rawEntryClass = Class.forName(GroupOfNamesStrategy.class.getName() + "$RawEntry");
        Constructor<?> rawEntryCtor = rawEntryClass.getDeclaredConstructor(
                String.class, String.class, String.class, String.class,
                List.class, String.class, boolean.class);
        rawEntryCtor.setAccessible(true);

        Object 엔트리A = rawEntryCtor.newInstance(
                아이디A, 진짜DN_A, "제1공장 A", null, List.of("cn=u0", "cn=u1"), 진짜DN_A, false);
        Object 엔트리B = rawEntryCtor.newInstance(
                아이디B, 진짜DN_B, "제1공장:A", null, List.of("cn=x1", "cn=x2", "cn=x3"), 진짜DN_B, true);

        // given — A 의 realDn 으로 재조회하면 처음(0)부터 다시 받는다({@code 전부_읽는다} 의
        // 계약: 첫 조각은 검색을 돌린 다른 커넥션에서 왔으니 버리고 새 커넥션에서 처음부터
        // 다시 받는다). 서버는 2명씩 두 조각(u0,u1 / u2,u3)으로 잘라 준다고 가정한다.
        // 명세대로 원래 이름(member)은 값 없이 함께 온다.
        DirContext dirContext = mock(DirContext.class);
        List<String> A의전체멤버 = List.of("cn=u0", "cn=u1", "cn=u2", "cn=u3");
        when(dirContext.getAttributes(eq(진짜DN_A), any(String[].class))).thenAnswer(invocation -> {
            String[] 요청속성 = invocation.getArgument(1);
            String 요청이름 = 요청속성[0]; // "member;range=<시작>-*"
            int 시작 = Integer.parseInt(
                    요청이름.substring(요청이름.indexOf('=') + 1, 요청이름.indexOf('-')));
            int 끝 = Math.min(시작 + 2, A의전체멤버.size());
            boolean 마지막 = 끝 >= A의전체멤버.size();

            Attributes 응답 = new BasicAttributes();
            응답.put(new BasicAttribute("member"));
            BasicAttribute 조각 = new BasicAttribute(
                    "member;range=" + 시작 + "-" + (마지막 ? "*" : (끝 - 1)));
            A의전체멤버.subList(시작, 끝).forEach(조각::add);
            응답.put(조각);
            return 응답;
        });

        ContextSource contextSource = mock(ContextSource.class);
        when(contextSource.getReadOnlyContext()).thenReturn(dirContext);
        LdapTemplate template = new LdapTemplate(contextSource);

        LdapProperties.GroupOfNames config = new LdapProperties.GroupOfNames();
        config.setMemberAttribute("member");

        GroupOfNamesStrategy strategy = new GroupOfNamesStrategy(new LdapProperties());
        Method 이어받는다 = GroupOfNamesStrategy.class.getDeclaredMethod(
                "범위가_잘린_멤버를_이어받는다", LdapTemplate.class,
                LdapProperties.GroupOfNames.class, List.class);
        이어받는다.setAccessible(true);

        // when
        @SuppressWarnings("unchecked")
        List<Object> 결과 = (List<Object>) 이어받는다.invoke(
                strategy, template, config, List.of(엔트리A, 엔트리B));

        // then — A 는 이어받은 4명이 되고, B 는 원래의 3명을 그대로 지킨다.
        Object 결과A = 찾는다(결과, 진짜DN_A);
        Object 결과B = 찾는다(결과, 진짜DN_B);

        assertThat(멤버(결과A)).containsExactly("cn=u0", "cn=u1", "cn=u2", "cn=u3");
        assertThat(완료(결과A)).isTrue();

        assertThat(멤버(결과B))
                .as("잘리지 않은 조직이 남의(A의) 이어받은 멤버 목록을 받으면 안 된다")
                .containsExactly("cn=x1", "cn=x2", "cn=x3");
        assertThat(완료(결과B)).isTrue();

        // then — B 는 애초에 잘리지 않았으니 재조회 자체가 없어야 한다.
        verify(dirContext, never()).getAttributes(eq(진짜DN_B), any(String[].class));
    }

    private static Object 찾는다(List<Object> 결과, String realDn) throws Exception {
        for (Object entry : 결과) {
            if (realDn.equals(호출(entry, "realDn"))) {
                return entry;
            }
        }
        throw new AssertionError("realDn '" + realDn + "' 을 가진 엔트리를 찾지 못했다");
    }

    @SuppressWarnings("unchecked")
    private static List<String> 멤버(Object entry) throws Exception {
        return (List<String>) 호출(entry, "members");
    }

    private static boolean 완료(Object entry) throws Exception {
        return (boolean) 호출(entry, "membersComplete");
    }

    private static Object 호출(Object entry, String accessor) throws Exception {
        Method method = entry.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(entry);
    }
}
