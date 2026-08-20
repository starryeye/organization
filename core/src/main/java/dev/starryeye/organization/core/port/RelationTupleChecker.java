package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import reactor.core.publisher.Mono;

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
}
