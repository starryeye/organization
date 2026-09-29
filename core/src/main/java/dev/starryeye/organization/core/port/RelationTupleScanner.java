package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.RelationTuple;
import reactor.core.publisher.Flux;

/**
 * 장부(OpenFGA store)의 모든 줄을 흘려 준다 — 재적재의 "장부 훑기" 전용 (설계 2026-09-29 §3.2).
 *
 * <p><b>OpenFGA Read API 를 쓰는 유일한 자리다.</b> 평소 쓰기·판단 경로는 Check·BatchCheck({@link RelationTupleChecker})만 쓴다.
 * 재적재는 사람이 거는 드문 백그라운드 작업이라 여기서만 장부 전체를 열거해, 스냅샷에 없는 찌꺼기까지 찾는다. 판단·쓰기 경로에서
 * 부르면 안 된다 — 요청마다 장부 전체를 읽게 된다.
 */
public interface RelationTupleScanner {

    /** 장부의 모든 줄. 캐시가 아니라 지금 있는 것을 읽는다 — 방금 쓴 줄도 나온다. */
    Flux<RelationTuple> scanAll();
}
