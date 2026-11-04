package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.Timestamped;
import dev.starryeye.organization.core.port.DirectoryQueryRepository;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.query.OrganizationLookup;
import dev.starryeye.organization.core.query.UserSummary;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * 정확 조회(설계 2026-10-28 §6) — 권한을 묻는 앱이 로그인 이름이나 IdP 의 식별자로 우리 직원 아이디(employeeId)와 조직코드(orgCode)를 얻는 길이다.
 * 결과는 하나·없음·여럿 셋뿐이다. 여럿일 때 목록을 돌려주지 않고 수만 센다 — 이 길은 개인 정보를 훑는 길이 아니다.
 *
 * <p>읽는 양은 인덱스 한 번과 본 테이블 몇 번이다. 요청마다 전원을 읽지 않는다.
 *
 * <p>비활성 직원도 그대로 돌려준다. 묻는 앱이 {@code active} 를 본다.
 */
@RequiredArgsConstructor
public class DirectoryLookupUseCase {

    /** 정확 조회의 결과. 여럿은 목록이 아니라 수다. */
    public sealed interface 결과<T> {

        record 하나<T>(T 값) implements 결과<T> {
        }

        record 없음<T>() implements 결과<T> {
        }

        record 여럿<T>(int 수) implements 결과<T> {
        }
    }

    private final DirectoryStateRepository state;
    private final DirectoryQueryRepository query;

    /**
     * 직원을 {@code userName}(대소문자 무시) 또는 {@code externalId}(대소문자 구분)로 정확히 찾는다. 둘 중 정확히 하나만 널이 아니어야 한다.
     *
     * <p>두 길 모두 인덱스가 준 후보를 그대로 믿지 않는다. {@code externalId} 의 GSI3 는 최종 일관성이라 후보일 뿐이고, {@code userName} 의 GSI1 은
     * 이름이 비면 아이디로, 1024바이트를 넘으면 잘라 색인하므로 같은 이름이 아닌 직원이 후보에 오를 수 있다. 후보의 값이 물은 값과 같은 것만 센다.
     */
    public Mono<결과<UserSummary>> 직원을_찾는다(String userName, String externalId) {
        if ((userName == null) == (externalId == null)) {
            return Mono.error(new IllegalArgumentException("userName 과 externalId 중 정확히 하나만 준다"));
        }
        Flux<DirectoryUser> 후보 = userName != null
                ? query.findUsersByUserName(userName).map(Timestamped::value)
                        .filter(user -> 소문자(userName).equals(소문자(user.userName())))
                : state.findUserIdsByExternalId(externalId).concatMap(state::findUser)
                        .filter(user -> externalId.equals(user.externalId()));
        return 후보
                .map(user -> new UserSummary(user.id(), user.userName(), user.displayName(), user.active(),
                        user.externalId()))
                .collectList()
                .map(DirectoryLookupUseCase::판정한다);
    }

    /** 조직을 {@code externalId}(대소문자 구분)로 정확히 찾는다. 후보를 본 테이블에서 다시 읽어 {@code externalId} 가 같은 것만 센다. */
    public Mono<결과<OrganizationLookup>> 조직을_찾는다(String externalId) {
        return state.findGroupIdsByExternalId(externalId)
                .concatMap(state::findGroupHeader)
                .filter(header -> externalId.equals(header.externalId()))
                .map(header -> new OrganizationLookup(header.id(), header.displayName(), header.externalId()))
                .collectList()
                .map(DirectoryLookupUseCase::판정한다);
    }

    private static <T> 결과<T> 판정한다(List<T> 찾은것) {
        return switch (찾은것.size()) {
            case 0 -> new 결과.없음<>();
            case 1 -> new 결과.하나<>(찾은것.get(0));
            default -> new 결과.여럿<>(찾은것.size());
        };
    }

    /** 색인이 쓰는 것과 같은 소문자 규칙(Locale.ROOT). 이름이 없으면 빈 문자열이라 어떤 물은 값과도 같지 않다. */
    private static String 소문자(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
