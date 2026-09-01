package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * OpenFGA 에 인가 판정을 묻는다.
 *
 * <p>{@link RelationTupleWriter} 에 얹지 않는다. 얹으면 동기화 경로가 의도치 않게
 * 판정에 의존하기 쉬워지고 "쓰기 어댑터" 라는 이름이 거짓이 된다.
 *
 * <p>열거 API(Read/ListObjects)는 여전히 쓰지 않는다. {@code Check} 는 점 조회라
 * 열거를 대체하지 못하므로, 스냅샷 기준선과 diff 는 이 포트가 생겨도 그대로다.
 */
public interface RelationTupleChecker {

    /** 이 튜플이 성립하는가. 롤업 관계(`member`)도 서버가 해석해 답한다. */
    Mono<Boolean> check(RelationTuple tuple);

    /**
     * 후보 중 OpenFGA 에 <b>실제로 있는 것만</b> 돌려준다 (설계 §5.3).
     *
     * <p>diff 의 기준선을 DynamoDB 상태가 아니라 OpenFGA 실제 상태로 삼기 위한 것이다.
     * 상태에서 유도한 기준선은 "있어야 했던 것" 이라, 어긋난 튜플을 영원히 못 본다.
     *
     * <p>후보가 비면 호출 없이 빈 집합을 돌려준다.
     */
    Mono<Set<RelationTuple>> existing(Set<RelationTuple> candidates);
}
