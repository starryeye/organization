package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeQueryRepository;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.Timestamped;
import dev.starryeye.organization.core.query.OrganizationLookup;
import dev.starryeye.organization.core.query.UserSummary;
import dev.starryeye.organization.core.usecase.DirectoryLookupUseCase.결과;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectoryLookupUseCaseTest {

    private FakeStateRepository state;
    private FakeQueryRepository query;
    private DirectoryLookupUseCase useCase;

    @BeforeEach
    void setUp() {
        state = new FakeStateRepository();
        query = new FakeQueryRepository(state);
        useCase = new DirectoryLookupUseCase(state, query);
    }

    private static DirectoryUser 직원(String id, String externalId, String userName, boolean active) {
        return new DirectoryUser(id, externalId, userName, userName + "-이름", null, active);
    }

    private static DirectoryGroup 조직(String code, String externalId, String displayName) {
        return new DirectoryGroup(code, externalId, displayName, Set.of());
    }

    @Test
    @DisplayName("userName 은 대소문자를 무시하고 정확히 찾는다 — 접두사는 찾지 않는다")
    void userName_은_대소문자를_무시하고_정확히_찾는다() {
        // given
        state.users.put("u-1", 직원("u-1", "okta-00u1", "kim", true));
        state.users.put("u-2", 직원("u-2", "okta-00u2", "lee", true));

        // when
        var 대문자 = useCase.직원을_찾는다("KIM", null).block();
        var 접두사 = useCase.직원을_찾는다("ki", null).block();

        // then
        assertThat(대문자).isEqualTo(new 결과.하나<>(new UserSummary("u-1", "kim", "kim-이름", true, "okta-00u1")));
        assertThat(접두사).isEqualTo(new 결과.없음<UserSummary>());
    }

    @Test
    @DisplayName("저장된 userName 에 대문자가 섞여 있어도 소문자·대문자로 물어 찾고, 돌려주는 userName 은 저장된 그대로다")
    void 저장된_userName_에_대문자가_섞여_있어도_찾는다() {
        // given — userName 은 받은 그대로(Kim.Lee) 저장되고 색인 키만 소문자다. 후보의 저장된 값을 소문자로 바꾸지 않고 견주면 물은 쪽만
        // 소문자라 어긋난다
        state.users.put("u-1", 직원("u-1", "okta-00u1", "Kim.Lee", true));

        // when
        var 소문자로 = useCase.직원을_찾는다("kim.lee", null).block();
        var 대문자로 = useCase.직원을_찾는다("KIM.LEE", null).block();

        // then
        var 기대 = new 결과.하나<>(new UserSummary("u-1", "Kim.Lee", "Kim.Lee-이름", true, "okta-00u1"));
        assertThat(소문자로).isEqualTo(기대);
        assertThat(대문자로).isEqualTo(기대);
    }

    @Test
    @DisplayName("userName 이 같은 직원이 둘이면 여럿(2)이다 — LDAP 은 userName 유일성을 지키지 않는다")
    void 둘이면_여럿_2() {
        // given — 다른 OU 에 같은 uid 가 있다
        state.users.put("u-1", 직원("u-1", "uid=kim,ou=a,dc=x", "kim", true));
        state.users.put("u-2", 직원("u-2", "uid=kim,ou=b,dc=x", "kim", true));

        // when
        var 판정 = useCase.직원을_찾는다("kim", null).block();

        // then
        assertThat(판정).isEqualTo(new 결과.여럿<UserSummary>(2));
    }

    @Test
    @DisplayName("userName 인덱스 후보라도 userName 이 비어 id 로 색인된 직원은 userName 으로 찾지 않는다")
    void userName_이_비어_id_로_색인된_직원은_찾지_않는다() {
        // given — 저장소는 userName 이 비면 id 를 색인 키로 쓴다. 그 id 를 userName 으로 물으면 후보로 올라온다
        var 색인이_넓은 = new FakeQueryRepository(state) {
            @Override
            public Flux<Timestamped<DirectoryUser>> findUsersByUserName(String userName) {
                return Flux.just(new Timestamped<>(직원("u-9", "okta-00u9", "", true), null));
            }
        };
        var useCase = new DirectoryLookupUseCase(state, 색인이_넓은);

        // when
        var 판정 = useCase.직원을_찾는다("u-9", null).block();

        // then — 후보에는 올랐지만 userName 이 같지 않아 빠진다
        assertThat(판정).isEqualTo(new 결과.없음<UserSummary>());
    }

    @Test
    @DisplayName("externalId 는 후보를 본 테이블에서 다시 읽어 externalId 가 같은 것만 남긴다")
    void externalId_는_다시_읽어_같은_것만() {
        // given — 후보 목록이 externalId 가 이미 바뀐 u-2 도 돌려준다(GSI3 는 최종 일관성이다).
        // 가짜 저장소가 알아서 걸러 주면 이 테스트는 유스케이스가 아니라 가짜를 보게 된다
        var 후보가_넓은 = new FakeStateRepository() {
            @Override
            public Flux<String> findUserIdsByExternalId(String externalId) {
                return Flux.just("u-1", "u-2", "u-없음");
            }
        };
        후보가_넓은.users.put("u-1", 직원("u-1", "okta-00u1", "kim", true));
        후보가_넓은.users.put("u-2", 직원("u-2", "okta-바뀜", "lee", true));
        var useCase = new DirectoryLookupUseCase(후보가_넓은, new FakeQueryRepository(후보가_넓은));

        // when
        var 판정 = useCase.직원을_찾는다(null, "okta-00u1").block();

        // then — 하나로 센다. 바뀐 후보와 사라진 후보는 세지 않는다
        assertThat(판정).isEqualTo(new 결과.하나<>(new UserSummary("u-1", "kim", "kim-이름", true, "okta-00u1")));
    }

    @Test
    @DisplayName("externalId 가 같은 직원이 없으면 없음이고, 둘이면 여럿이다")
    void externalId_는_없음과_여럿도_가른다() {
        // given
        state.users.put("u-1", 직원("u-1", "dn-같음", "kim", true));
        state.users.put("u-2", 직원("u-2", "dn-같음", "lee", true));
        state.users.put("u-3", 직원("u-3", "dn-다름", "park", true));

        // when
        var 없음 = useCase.직원을_찾는다(null, "dn-없음").block();
        var 여럿 = useCase.직원을_찾는다(null, "dn-같음").block();
        var 대소문자 = useCase.직원을_찾는다(null, "DN-다름").block();

        // then — externalId 는 대소문자를 가린다
        assertThat(없음).isEqualTo(new 결과.없음<UserSummary>());
        assertThat(여럿).isEqualTo(new 결과.여럿<UserSummary>(2));
        assertThat(대소문자).isEqualTo(new 결과.없음<UserSummary>());
    }

    @Test
    @DisplayName("비활성 직원도 돌려준다 — 묻는 앱이 active 를 본다")
    void 비활성_직원도_돌려준다() {
        // given
        state.users.put("u-1", 직원("u-1", "okta-00u1", "kim", false));

        // when
        var userName으로 = useCase.직원을_찾는다("kim", null).block();
        var externalId로 = useCase.직원을_찾는다(null, "okta-00u1").block();

        // then
        var 기대 = new 결과.하나<>(new UserSummary("u-1", "kim", "kim-이름", false, "okta-00u1"));
        assertThat(userName으로).isEqualTo(기대);
        assertThat(externalId로).isEqualTo(기대);
    }

    @Test
    @DisplayName("userName 과 externalId 를 둘 다 주거나 둘 다 주지 않으면 거절한다")
    void 둘_다_주거나_둘_다_없으면_거절한다() {
        // when, then
        assertThatThrownBy(() -> useCase.직원을_찾는다("kim", "okta-00u1").block())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.직원을_찾는다(null, null).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조직 externalId 가 하나와 맞으면 하나이고 orgCode·조직명·externalId 를 싣는다")
    void 조직_externalId_하나() {
        // given
        state.groups.put("g-1", 조직("g-1", "okta-00g1", "개발"));
        state.groups.put("g-2", 조직("g-2", "okta-00g2", "영업"));

        // when
        var 판정 = useCase.조직을_찾는다("okta-00g1").block();

        // then — orgCode/externalId/displayName 이 뒤바뀌면(셋 다 String 이다) 여기서 잡힌다
        assertThat(판정).isEqualTo(new 결과.하나<>(new OrganizationLookup("g-1", "개발", "okta-00g1")));
    }

    @Test
    @DisplayName("조직 externalId 가 맞는 조직이 없으면 없음이다")
    void 조직_externalId_없음() {
        // given
        state.groups.put("g-1", 조직("g-1", "okta-00g1", "개발"));

        // when
        var 판정 = useCase.조직을_찾는다("okta-없음").block();

        // then
        assertThat(판정).isEqualTo(new 결과.없음<OrganizationLookup>());
    }

    @Test
    @DisplayName("조직 externalId 가 같은 조직이 둘이면 여럿(2)이다 — LDAP 에서만 난다")
    void 조직_externalId_여럿() {
        // given
        state.groups.put("g-1", 조직("g-1", "dn-같음", "개발"));
        state.groups.put("g-2", 조직("g-2", "dn-같음", "영업"));

        // when
        var 판정 = useCase.조직을_찾는다("dn-같음").block();

        // then
        assertThat(판정).isEqualTo(new 결과.여럿<OrganizationLookup>(2));
    }

    @Test
    @DisplayName("조직 externalId 도 후보를 다시 읽어 externalId 가 같은 것만 남긴다")
    void 조직_externalId_도_다시_읽어_같은_것만() {
        // given — 후보가 externalId 가 이미 바뀐 g-2 와 사라진 g-3 도 돌려준다
        var 후보가_넓은 = new FakeStateRepository() {
            @Override
            public Flux<String> findGroupIdsByExternalId(String externalId) {
                return Flux.just("g-1", "g-2", "g-3");
            }
        };
        후보가_넓은.groups.put("g-1", 조직("g-1", "okta-00g1", "개발"));
        후보가_넓은.groups.put("g-2", 조직("g-2", "okta-바뀜", "영업"));
        var useCase = new DirectoryLookupUseCase(후보가_넓은, new FakeQueryRepository(후보가_넓은));

        // when
        var 판정 = useCase.조직을_찾는다("okta-00g1").block();

        // then
        assertThat(판정).isEqualTo(new 결과.하나<>(new OrganizationLookup("g-1", "개발", "okta-00g1")));
    }
}
