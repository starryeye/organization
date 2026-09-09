package dev.starryeye.organization.ldap.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapOperations;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Active Directory 의 범위 검색 응답을 해석하고 이어받는 부분을 검증한다.
 *
 * <p><b>실제 AD 로는 검증할 수 없다.</b> AD 인스턴스가 없고, 우리가 쓰는 임베디드 UnboundID
 * 서버는 범위 검색을 하지 않는다(그래서 이 결함이 규모 E2E 를 전부 통과하고도 살아 있었다).
 * 그래서 <b>응답을 합성해서</b> 기제만 검증한다 — "MS-ADTS 명세대로라면 맞게 동작한다" 까지가
 * 이 테스트가 말할 수 있는 전부다.
 *
 * <p>합성한 응답의 모양은 명세를 그대로 옮긴 것이다. 특히 <b>범위가 걸리면 원래 이름의 속성이
 * 값 없이 함께 온다</b> 는 점 — 그것이 이 결함의 원인이므로 모든 케이스에 그대로 넣는다.
 */
class RangedAttributeReaderTest {

    private static final String MEMBER = "member";

    @Test
    @DisplayName("범위 옵션이 없으면 그대로 읽는다 — 범위 검색을 안 하는 서버의 응답")
    void 평범한_속성을_그대로_읽는다() {
        // given — OpenLDAP·UnboundID 가 돌려주는 모양
        Attributes attributes = new BasicAttributes();
        attributes.put(값이_있는(MEMBER, "cn=a", "cn=b", "cn=c"));

        // when
        var chunk = RangedAttributeReader.읽는다(attributes, MEMBER);

        // then
        assertThat(chunk.values()).containsExactly("cn=a", "cn=b", "cn=c");
        assertThat(chunk.완료()).as("범위 옵션이 없으면 그것이 전부다").isTrue();
    }

    @Test
    @DisplayName("범위가 걸리면 빈 member 가 아니라 member;range 쪽을 읽는다 — 이 결함의 핵심")
    void 범위가_걸리면_range쪽을_읽는다() {
        // given — 명세: 범위 옵션 없는 속성을 "값 없이", 범위 옵션이 붙은 것을 "값과 함께".
        //
        // 두 순서를 다 만든다. Attributes 의 순회 순서는 임의라(내부가 해시테이블이다),
        // 한 순서로만 짜면 이 테스트가 결함을 잡을지 말지가 실행마다 달라진다 —
        // 처음 짰을 때 실제로 그랬다. 먼저 만나는 것을 그냥 쓰는 구현을 확실히 걸러내려면
        // 두 순서 모두에서 같은 답이 나와야 한다.
        Attributes 빈것이_먼저 = new BasicAttributes();
        빈것이_먼저.put(new BasicAttribute(MEMBER));
        빈것이_먼저.put(값이_있는("member;range=0-1499", "cn=a", "cn=b"));

        Attributes 범위가_먼저 = new BasicAttributes();
        범위가_먼저.put(값이_있는("member;range=0-1499", "cn=a", "cn=b"));
        범위가_먼저.put(new BasicAttribute(MEMBER));

        // when, then — 빈 쪽을 읽었다면 0개가 나온다. 그것이 6,000명 그룹을 0명으로 만들었다
        for (Attributes attributes : List.of(빈것이_먼저, 범위가_먼저)) {
            var chunk = RangedAttributeReader.읽는다(attributes, MEMBER);
            assertThat(chunk.values()).containsExactly("cn=a", "cn=b");
            assertThat(chunk.완료()).as("상한이 숫자면 더 남아 있다").isFalse();
        }
    }

    @Test
    @DisplayName("상한이 * 면 마지막 조각이다")
    void 별표는_마지막_조각이다() {
        // given
        Attributes attributes = new BasicAttributes();
        attributes.put(new BasicAttribute(MEMBER));
        attributes.put(값이_있는("member;range=1500-*", "cn=y", "cn=z"));

        // when
        var chunk = RangedAttributeReader.읽는다(attributes, MEMBER);

        // then
        assertThat(chunk.values()).containsExactly("cn=y", "cn=z");
        assertThat(chunk.완료()).isTrue();
    }

    @Test
    @DisplayName("속성 이름 대소문자가 달라도 알아본다 — 서버가 Member;Range 로 줄 수 있다")
    void 대소문자를_가리지_않는다() {
        // given
        Attributes attributes = new BasicAttributes();
        attributes.put(값이_있는("Member;Range=0-99", "cn=a"));

        // when
        var chunk = RangedAttributeReader.읽는다(attributes, MEMBER);

        // then
        assertThat(chunk.values()).containsExactly("cn=a");
        assertThat(chunk.완료()).isFalse();
    }

    @Test
    @DisplayName("다른 속성의 range 는 건드리지 않는다")
    void 다른_속성의_범위는_무시한다() {
        // given
        Attributes attributes = new BasicAttributes();
        attributes.put(값이_있는("memberOf;range=0-99", "cn=other"));
        attributes.put(값이_있는(MEMBER, "cn=a"));

        // when
        var chunk = RangedAttributeReader.읽는다(attributes, MEMBER);

        // then
        assertThat(chunk.values()).containsExactly("cn=a");
    }

    @Test
    @DisplayName("속성이 아예 없으면 빈 목록으로 끝낸다")
    void 없으면_빈_목록이다() {
        // when
        var chunk = RangedAttributeReader.읽는다(new BasicAttributes(), MEMBER);

        // then
        assertThat(chunk.values()).isEmpty();
        assertThat(chunk.완료()).isTrue();
    }

    @Test
    @DisplayName("6,000명을 1,500개씩 네 조각으로 끝까지 이어받는다")
    void 끝까지_이어받는다() {
        // given — 조각마다 1,500개, 마지막만 * 로 끝난다
        LdapOperations 서버 = 조각을_돌려주는_서버(6_000, 1_500);

        // when
        List<String> 전부 = RangedAttributeReader.전부_읽는다(서버, "cn=전사", MEMBER);

        // then
        assertThat(전부).hasSize(6_000);
        assertThat(전부).doesNotHaveDuplicates();
        assertThat(전부.get(0)).isEqualTo("cn=u0");
        assertThat(전부.get(5_999)).isEqualTo("cn=u5999");
    }

    @Test
    @DisplayName("마지막 조각이 딱 떨어져도 끝난다 — 경계에서 한 번 더 묻지 않는다")
    void 경계에서_멈춘다() {
        // given — 3,000개를 1,500씩. 두 번째 조각이 정확히 끝이다
        var 서버 = 조각을_돌려주는_서버(3_000, 1_500);

        // when
        List<String> 전부 = RangedAttributeReader.전부_읽는다(서버, "cn=전사", MEMBER);

        // then
        assertThat(전부).hasSize(3_000);
    }

    @Test
    @DisplayName("서버가 값을 더 주지 않는데 완료 표시도 없으면 무한 루프 대신 멈춘다")
    void 이상한_응답에서_멈춘다() {
        // given — 늘 "미완료 + 0개" 를 돌려주는 고장난 서버
        LdapOperations 고장난서버 = mock(LdapOperations.class);
        when(고장난서버.lookup(eq("cn=x"), any(String[].class), any(ContextMapper.class)))
                .thenAnswer(invocation -> new RangedAttributeReader.Chunk(List.of(), false));

        // when — 여기서 안 멈추면 테스트가 영영 끝나지 않는다
        List<String> 전부 = RangedAttributeReader.전부_읽는다(고장난서버, "cn=x", MEMBER);

        // then
        assertThat(전부).isEmpty();
    }

    // ---------- 거들기 ----------

    /** 명세대로 조각을 돌려주는 가짜 서버. 요청한 시작 위치를 읽어 그 다음 조각을 준다. */
    private static LdapOperations 조각을_돌려주는_서버(int 전체, int 조각크기) {
        LdapOperations 서버 = mock(LdapOperations.class);
        when(서버.lookup(any(String.class), any(String[].class), any(ContextMapper.class)))
                .thenAnswer(invocation -> {
                    String 요청이름 = ((String[]) invocation.getArgument(1))[0];
                    int 시작 = Integer.parseInt(
                            요청이름.substring(요청이름.indexOf('=') + 1, 요청이름.indexOf('-')));
                    int 끝 = Math.min(시작 + 조각크기, 전체);
                    boolean 마지막 = 끝 >= 전체;

                    Attributes attributes = new BasicAttributes();
                    // 명세대로 원래 이름을 값 없이 함께 넣는다
                    attributes.put(new BasicAttribute(MEMBER));
                    attributes.put(값이_있는(
                            MEMBER + ";range=" + 시작 + "-" + (마지막 ? "*" : (끝 - 1)),
                            IntStream.range(시작, 끝).mapToObj(i -> "cn=u" + i).toArray(String[]::new)));

                    ContextMapper<?> mapper = invocation.getArgument(2);
                    return mapper.mapFromContext(컨텍스트(attributes));
                });
        return 서버;
    }

    private static DirContextAdapter 컨텍스트(Attributes attributes) {
        return new DirContextAdapter(attributes, null);
    }

    private static BasicAttribute 값이_있는(String id, String... values) {
        BasicAttribute attribute = new BasicAttribute(id);
        List<String> 목록 = new ArrayList<>(List.of(values));
        목록.forEach(attribute::add);
        return attribute;
    }
}
