package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.GroupChange;
import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.GroupHeader;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.LockLease;
import dev.starryeye.organization.core.port.MutationLock;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * SCIM 이 보낸 단건 변경을 튜플에 반영한다.
 *
 * <p>LDAP 은 전체를 읽어 직전 스냅샷과 diff 하지만, SCIM 은 리소스 하나의 변경만 온다.
 * 그래서 <b>영향 범위만 담은 최소 스냅샷</b>을 변경 전후로 각각 만들어 {@link TupleMapper} 에
 * 통과시키고, 그 둘을 {@link TupleDiff} 로 비교한다. 튜플 생성 규칙(비활성 유저 제외,
 * 없는 멤버 스킵)이 한 곳에만 있게 하려는 것이다 — 손으로 계산하면 규칙이 두 벌이 되고
 * 언젠가 어긋난다.
 *
 * <p>영향 범위:
 * <ul>
 *   <li>조직 변경 — 그 조직 + <b>그 조직을 하위 조직으로 갖는 상위 조직들</b>(헤더만, 멤버는
 *       이 조직 하나로. {@link #upsertGroup} 참고) + 그 조직들의 멤버 유저들(활성 여부 판정에 필요) +
 *       멤버로 참조된 하위 조직의 <b>존재</b>(존재 확인에 필요, {@link TupleMapper} 가 child
 *       엣지를 만들려면 그 하위 조직이 스냅샷에 있어야 한다 — 단, 그 하위 조직 자신의 멤버까지
 *       실으면 안 된다. {@link #expandWithReferencedGroups} 참고). 조직 PATCH·PUT
 *       ({@link #changeGroup})은 이와 달리 <b>바뀌는 멤버만 담은 그림</b>을 쓰고 상위 조직을
 *       싣지 않는다(이미 있는 조직이라 상위와의 child 엣지가 바뀌지 않아서다) — 자세한 이유는
 *       {@link #changeGroup} 참고</li>
 *   <li>유저 변경 — 그 유저가 속한 모든 조직을 찾는 것은 {@code findGroupIdsContaining}
 *       (강한 일관성, 정확함) 하나지만 그 뒤가 갈린다. {@link #upsertUser} 는 조직마다
 *       {@link #affectedGroupHeadersOf} 로 <b>헤더만</b> 읽는다(멤버 목록은 필요 없다 — 커밋이
 *       {@code saveUser} 뿐이라서다) — 역참조가 강한 일관성이라 그대로 믿는다.
 *       {@link #removeUser} 도 헤더만 읽고, 커밋은 {@code saveGroupChange} 로 그 직원의 줄만
 *       지운다(SCIM 쓰기 락 설계 §5). 어느 경로든 {@code active} 가 뒤집히거나(또는 유저가
 *       삭제되면) 그 유저의 모든 {@code direct_member} 튜플이 생기거나 사라진다</li>
 * </ul>
 *
 * <p><b>최소 스냅샷이 볼 수 있는 규칙과 볼 수 없는 규칙(설계의 경계).</b>
 * "튜플 규칙은 {@link TupleMapper} 한 곳에만 있다"는 명제는 <i>멤버 단위</i> 규칙에 대해서만
 * 무조건 참이다. 최소 스냅샷은 그래프 전체를 싣지 않으므로 <i>그래프 전역</i> 규칙은 그대로는
 * 성립하지 않는다.
 * <ul>
 *   <li><b>스냅샷이 볼 수 있는 것(멤버 단위 규칙, {@link TupleMapper} 가 그대로 담당).</b>
 *       비활성 유저는 튜플을 만들지 않는다 / 스냅샷에 없는 유저·조직은 경고하고 건너뛴다 /
 *       조직명은 튜플에 넣지 않는다. 이 규칙들은 한 조직과 그 직속 멤버만 보면 판정되고,
 *       최소 스냅샷은 언제나 그만큼은 싣는다.</li>
 *   <li><b>스냅샷이 볼 수 없는 것 1 — child 엣지의 존재 조건.</b> 엣지 {@code (child, parent)}
 *       는 부모 쪽 멤버 목록에서 나오므로, 자식만 실은 스냅샷에는 아예 나타나지 않는다.
 *       → {@link #upsertGroup} 이 {@link #상위_조직들} 로 <b>상위 조직들을 헤더만 읽고 멤버는 이 조직 하나로</b>
 *       both 스냅샷에 싣는 것으로 해결한다. 그래야 "부모가 먼저 참조해 둔 자식이 나중에 도착"
 *       하는 순서에서도 엣지가 만들어진다. 이때 <b>없던 조직은 before 스냅샷에서 완전히
 *       빼야</b> 한다 — 멤버 0개짜리 대역을 넣으면 before 에도 엣지가 생겨 델타가 비어버린다.</li>
 *   <li><b>스냅샷이 볼 수 없는 것 2 — 비순환 보장(설계 §5.3, 2026-10-03 §4.2·§4.3).</b>
 *       {@link TupleMapper#toTuples} 의 DFS 는 스냅샷 안의 그래프만 훑는다. 참조로 딸려온
 *       하위 조직은 일부러 멤버를 비워 싣기 때문에({@link #expandWithReferencedGroups})
 *       두 홉 이상 떨어진 순환은 최소 스냅샷에서 보이지 않는다. 한 홉 순환도 그 DFS 는 조직코드
 *       순서로 버릴 쪽을 골라 두 연결을 다 버릴 수 있다(점검 S4). 그래서 순증 경로는 목표를 순환 제거 없이 만든다.
 *       → 새로 생기는 child 엣지마다 {@link OrgGraph} 가 저장소의 <b>튜플 그래프(멤버 줄 − 보류 목록(OpenFGA 에 없는 줄))</b>를
 *       부모에서 위로 올라가 확인하고, 순환을 닫는 엣지는 쓰지 않고 보류 목록에 적는다 — 먼저 저장된
 *       연결이 이긴다. 멤버 줄은 남긴다. 조상·자손 전체를 스냅샷에 싣는 방법도 있지만 요청 한 건마다
 *       비용이 훨씬 크다.</li>
 * </ul>
 *
 * <p><b>손으로 만드는 튜플 — 지울 줄만(설계 2026-10-02 §4.1·§4.2).</b> 조직 삭제({@link #removeGroup})의 줄과 조직 PATCH·PUT 에서
 * 빠지는 멤버의 줄은 {@link TupleMapper} 를 거치지 않고 {@link #tupleFor} 로 바로 만든다 — "튜플 규칙은 한 곳에만"의 예외다.
 * 지우기뿐이라 안전하다. 비활성 직원처럼 줄이 없어야 할 멤버의 줄도 함께 넣고 "없으면 무시"로 지우므로 활성 여부·존재 같은 규칙을
 * 알 필요가 없고, 만드는 모양은 멤버십의 두 가지({@code direct_member}·{@code child})뿐이다.
 *
 * <p>이 유스케이스는 {@code SyncRun} 을 기록하지 않는다. SCIM 은 요청 단위라 이력이 폭증한다.
 *
 * <p><b>부분 실패(design §7.2).</b> OpenFGA 배치는 트랜잭션이므로 SCIM 단건 변경은 대개
 * 전부 성공이거나 전부 실패다. 대형 그룹 PUT 으로 배치가 쪼개질 때만 부분 성공이 가능한데,
 * 실패한 부분을 그대로 반영된 것처럼 저장하면 OpenFGA 에는 없는데 DynamoDB 에는 있는(또는
 * 그 반대인) 상태가 되고, 다음 동기화의 diff는 "이미 같다"고 판단해 이 불일치를 영원히
 * 다시 잡지 못한다 — 재시도가 diff 할 "이전" 자체가 이미 목표값으로 오염됐기 때문이다.
 * 그래서 모든 커밋은 반드시 {@code TupleWriteResult} 를 보고 실제로 반영된 만큼만 상태에
 * 남긴다({@link #diffAndApply}, {@link #reconcileGroupMembers}).
 */
@Slf4j
@RequiredArgsConstructor
public class IncrementalSyncUseCase {

    private static final int LOAD_CONCURRENCY = 8;

    /** 획득 재시도 간격. 대기 한도를 이 값으로 나눈 횟수가 재시도 횟수다. */
    private static final Duration ACQUIRE_RETRY_DELAY = Duration.ofMillis(200);

    /** 갱신 주기를 넘기지 않는 생성자가 쓰는 값 — 운영 결선은 {@code dynamodb.lock-renew-interval} 을 넘긴다. */
    static final Duration DEFAULT_RENEW_INTERVAL = Duration.ofSeconds(10);

    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final RelationTupleChecker checker;
    private final MutationLock lock;
    /**
     * 락 획득을 포기하기까지의 대기 한도 (설계 §4.4). 재시도 <b>횟수</b>가 아니라 한도를
     * 받는다 — 횟수를 받으면 그것을 계산한 쪽이 {@link #ACQUIRE_RETRY_DELAY} 를 따로 알고
     * 있어야 하고, 컴파일러가 이어주지 않는 그 중복 때문에 한쪽만 바뀌면 획득 예산이
     * 조용히 달라진다.
     */
    private final Duration acquireTimeout;
    /** 락을 쥔 동안 리스를 갱신하는 주기(설계 2026-10-02 §3.1). */
    private final Duration renewInterval;
    private final DriftObserver driftObserver;
    private final LockObserver lockObserver;

    /** 갱신 주기를 {@link #DEFAULT_RENEW_INTERVAL} 로 둔다 — 테스트용. 운영 결선은 8인자 생성자로 {@code dynamodb.lock-renew-interval} 을 넘긴다. */
    public IncrementalSyncUseCase(DirectoryStateRepository state, RelationTupleWriter writer, RelationTupleChecker checker,
                                  MutationLock lock, Duration acquireTimeout, DriftObserver driftObserver,
                                  LockObserver lockObserver) {
        this(state, writer, checker, lock, acquireTimeout, DEFAULT_RENEW_INTERVAL, driftObserver, lockObserver);
    }

    private long acquireRetries() {
        return acquireTimeout.toMillis() / ACQUIRE_RETRY_DELAY.toMillis();
    }

    /**
     * 어긋남을 발견했을 때 부른다. 기본은 아무것도 하지 않는다.
     *
     * <p>{@code core} 가 Micrometer 를 알지 않게 하려고 콜백으로 받는다 — 이 모듈의 의존성은
     * reactor 와 slf4j 뿐이고, 그 경계를 지표 때문에 허물지 않는다.
     */
    public interface DriftObserver {
        void observed(int extra, int missing);

        DriftObserver NOOP = (extra, missing) -> {
        };
    }

    /**
     * 직원 생성·수정. 활성 여부가 바뀌면 그 직원이 속한 모든 조직의 튜플이 함께 움직인다.
     *
     * <p><b>SCIM 입구가 아니다.</b> 생성·중복 판단 없이 그대로 덮어쓴다 — 테스트와 등가 비교용이다. SCIM 쓰기는
     * {@link #createUser}·{@link #changeUser} 를 쓴다(판단 읽기가 락 안).
     *
     * <p>반영이 실패하면 {@code active} 를 요청값 그대로 저장하지 않고 이전 값으로 되돌린다.
     * 그대로 저장하면 다음 동기화가 "이미 목표 상태"라고 오판해 실패한 튜플을 영원히
     * 다시 시도하지 못한다.
     *
     * <p>아직 한 번도 저장된 적 없는 직원은 <b>비활성으로 취급</b>해 "이전"을 구성한다 —
     * 존재하지 않는 직원은 튜플을 만들지 않는다는 점에서 비활성 직원과 같다. 이 자리에
     * 요청값(={@code user}) 자체를 fallback 으로 쓰면 before/after 가 똑같아져 델타가
     * 비어버리고, 조직이 먼저 참조해 둔 신규 직원의 첫 튜플이 영원히 만들어지지 않는다.
     *
     * <p>조직 쪽({@link #upsertGroup})과 달리 여기서는 "없는 직원"과 "멤버가 될 수 없는 직원"을
     * 같은 대역으로 뭉뚱그려도 안전하다 — 비활성 유저는
     * {@link TupleMapper#toTuples} 에서 튜플 기여가 0 이고, 스냅샷에 아예 없는 유저도(경고만
     * 남기고) 기여가 0 이라 두 상태가 튜플 관점에서 구별되지 않기 때문이다. 조직은 그렇지
     * 않다: 멤버 0개인 조직은 <b>상위 조직의 child 엣지</b>를 성립시키지만 없는 조직은 그렇지
     * 않아, 같은 대역을 쓰면 델타가 사라진다.
     *
     * <p><b>아직 없던 직원은 하나라도 실패하면 레코드를 만들지 않는다.</b> 만들어 두면 IdP 의
     * 재시도가 {@code POST} 로 오는데 이미 존재해서 {@code 409 uniqueness} 로 막힌다. IdP 는
     * 409 를 영구 충돌로 읽어 재시도를 멈추므로, 실패한 튜플을 다시 잡을 기회가 사라진다 —
     * 그 사람은 {@code active=false} 로 남는다. 권한이 없는 안전한 방향이지만
     * <b>프로비저닝이 조용히 실패한 상태</b>이고, 다음 {@code PUT} 이 올 때까지 그대로다.
     *
     * <p>레코드를 만들지 않아도 안전한 이유: 재시도가 {@link #affectedGroupHeadersOf} 로 소속을
     * 다시 찾고(역참조 자체가 강한 일관성이고 정확하다), Check 기준선이 이미 쓰인 튜플을 보므로
     * 남은 것만 정확히 다시 쓴다.
     * {@link #upsertGroup} 이 같은 가드를 갖는다 — 다만 그쪽은 레코드를 만들면 부모의 child
     * 엣지를 <b>영원히</b> 못 쓰게 되므로 더 심각하다.
     */
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
        return withLock(lease -> state.findUser(user.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> upsertUserInternal(user, existing, lease)));
    }

    /** {@code existing} 은 부르는 쪽이 락 안에서 읽은 저장본이다 — 저장소에 그대로 넘겨 META 를 다시 읽지 않게 한다(설계 2026-10-03 §3.5). */
    private Mono<IncrementalSyncResult> upsertUserInternal(DirectoryUser user, Optional<DirectoryUser> existing, LockLease lease) {
        DirectoryUser neverStored = user.withActive(false);
        return affectedGroupHeadersOf(user.id()).flatMap(headers -> {
            DirectoryUser existingUser = existing.orElse(neverStored);
            Mono<DirectorySnapshot> before = 직원한명_그림(headers, user.id(), Mono.just(existingUser));
            Mono<DirectorySnapshot> after = 직원한명_그림(headers, user.id(), Mono.just(user));

            Commit commit = (result, beforeTuples, afterTuples) -> {
                if (existing.isEmpty() && result.hasFailure()) {
                    return Mono.empty();
                }
                return Mono.defer(() -> state.saveUser(existing.orElse(null), reconcileUser(existingUser, user, result)));
            };

            return diffAndApply(before, after, RelationTuple.userRef(user.id()), Set.of(), lease, commit);
        });
    }

    /**
     * 직원 생성(POST). {@code userName} 중복을 <b>락 안에서</b> 확인한다(SCIM 쓰기 락 설계 §4). 아이디는 서버가 발급한 UUID 라 겹칠 일이 없어 보지 않는다
     * (설계 2026-10-04 §3.2) — 지운 직원의 아이디가 다시 쓰이지 않아 남은 권한을 물려받지 않는다(점검 M7).
     *
     * <p>생성에는 "자기 자신"이 없다 — 이미 있는 직원은 모두 남이므로 중복 확인에서 아무도 빼지 않는다(자기 아이디를 넘기면 같은 아이디로 다시 온 POST 가 기존
     * 직원을 덮어쓴다).
     */
    public Mono<IncrementalSyncResult> createUser(DirectoryUser user) {
        return withLock(lease -> userName을_확인한다(user.userName(), null)
                .then(Mono.defer(() -> upsertUserInternal(user, Optional.empty(), lease))));
    }

    /**
     * 직원 PATCH·PUT. 락을 잡은 뒤 직원을 읽어 {@code 계산} 을 적용한다(SCIM 쓰기 락 설계 §3). 직원이 없으면 빈 {@code Mono} 다.
     *
     * <p>전에는 핸들러가 락 밖에서 읽은 직원으로 계산해, 동시에 온 비활성화와 이름 변경 중 늦게 저장된 쪽이 비활성화를
     * 되돌렸고(퇴사자 권한 부활), 그 사이 DELETE 가 끝났으면 지운 직원을 다시 만들었다(설계 §1.1·§1.2). 계산이 던지는
     * 예외는 그대로 나오고 아무것도 쓰지 않는다. 계산 결과의 아이디는 무시하고 {@code userId} 로 저장한다 — 경로가 정본이다.
     * {@code userName} 이 바뀌었으면 중복을 확인한다(§4).
     */
    public Mono<IncrementalSyncResult> changeUser(String userId, UnaryOperator<DirectoryUser> 계산) {
        return withLock(lease -> state.findUser(userId)
                .flatMap(before -> {
                    DirectoryUser after = 계산.apply(before).withId(userId);
                    Mono<Void> 확인 = Objects.equals(before.userName(), after.userName())
                            ? Mono.empty()
                            : userName을_확인한다(after.userName(), userId);
                    return 확인.then(Mono.defer(() -> upsertUserInternal(after, Optional.of(before), lease)));
                }));
    }

    /**
     * {@code userName} 이 다른 직원과 겹치는지 확인한다(SCIM 쓰기 락 설계 §4). GSI 로 후보를 찾고(대소문자 무시), 자기 자신({@code selfId}, 생성이면 null)을
     * 뺀 뒤, 후보마다 본 테이블을 강한 일관성으로 다시 읽어 여전히 같은 {@code userName} 일 때만 충돌이다 — GSI 에 잠깐 남은
     * 옛 값(방금 지웠거나 이름을 바꾼 직원) 때문에 잘못 거절하지 않는다. 남는 틈은 방금 저장돼 아직 GSI 에 없는 직원뿐이다(설계 §10).
     */
    private Mono<Void> userName을_확인한다(String userName, String selfId) {
        if (userName == null) {
            return Mono.empty();
        }
        return state.findUserIdsByUserName(userName)
                .filter(id -> !id.equals(selfId))
                .concatMap(state::findUser)
                .filter(other -> userName.equalsIgnoreCase(other.userName()))
                .next()
                .flatMap(other -> Mono.error(new DirectoryConflictException(
                        "이미 같은 userName 을 쓰는 직원이 있습니다: userName=%s, id=%s".formatted(userName, other.id()))));
    }

    /**
     * 멤버 목록을 통째로 교체한다.
     *
     * <p><b>SCIM 입구가 아니다.</b> 존재 판단 없이 그대로 덮어쓴다 — 테스트와 등가 비교용이다. SCIM 쓰기는
     * {@link #createGroup}·{@link #changeGroup} 을 쓴다(판단 읽기가 락 안).
     *
     * <p><b>상위 조직도 함께 싣는다.</b> child 엣지 {@code (group:자식, child, group:부모)} 는
     * 부모의 멤버 목록에서 나오므로, 이 조직만 실은 스냅샷에는 그 엣지가 아예 등장하지 않는다.
     * 그래서 부모가 이미 이 조직을 멤버로 적어 둔 채 이 조직이 뒤늦게 도착하면
     * ({@link TupleMapper} 가 "스냅샷에 없어 건너뜁니다" 로 미뤄 뒀던 경우) 그 엣지를 영원히
     * 쓰지 못했다. {@link #상위_조직들} 로 상위 조직들을 <b>헤더만 읽고 멤버는 이 조직 하나로</b> before/after
     * 양쪽에 실어 기여를 대칭으로 만든다 — 이미 존재하던 조직이면 엣지가 양쪽에 다 있어
     * 델타에 나타나지 않고, 새로 생긴 조직이면 after 에만 있어 정확히 그 엣지만 새로 쓰인다.
     * 이 연산은 이 조직을 언급하는 튜플만 보므로 상위 조직의 다른 멤버는 결과에 기여하지 않는다(설계 2026-10-03 §3.4).
     *
     * <p><b>없던 조직은 before 에서 통째로 뺀다.</b> "멤버 0개인 조직이 있다" 와 "조직이 없다"
     * 는 서로 다른 상태다. 없는 조직 자리에 멤버 0개짜리 대역을 넣으면
     * {@link TupleMapper#toTuples} 가 before 에서도 부모의 child 엣지를 만들어 버려 위 델타가
     * 다시 비어버린다. (유저 쪽 {@link #upsertUser} 의 대역은 <b>비활성</b> 유저라 어느 쪽
     * 스냅샷에서도 튜플 기여가 0 이므로 같은 문제가 없다 — 확인함.)
     *
     * <p>부분 실패 시에는 요청된 멤버 목록을 그대로 저장하지 않는다. 실제로 튜플이 반영된
     * 멤버만 추가되고, 실제로 튜플이 지워진 멤버만 제외된다({@link #reconcileGroupMembers}).
     * 다만 <b>아직 없던 조직</b>은 하나라도 실패하면 레코드 자체를 만들지 않는다. 만들어 두면
     * 다음 diff 의 "이전"에 부모의 child 엣지가 이미 포함돼 그 엣지를 영원히 다시 쓰지 못한다
     * ({@link #removeUser} 가 실패 시 직원 레코드를 지우지 않는 것과 같은 이유다).
     */
    public Mono<IncrementalSyncResult> upsertGroup(DirectoryGroup group) {
        return withLock(lease -> upsertGroupInternal(group, lease));
    }

    private Mono<IncrementalSyncResult> upsertGroupInternal(DirectoryGroup group, LockLease lease) {
        return state.findGroup(group.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> 상위_조직들(group.id()).flatMap(parents -> {
                    Set<DirectoryGroup> beforeGroups = new LinkedHashSet<>(parents);
                    existing.ifPresent(beforeGroups::add);
                    Set<DirectoryGroup> afterGroups = new LinkedHashSet<>(parents);
                    afterGroups.add(group);

                    Mono<DirectorySnapshot> before = snapshotOfGroups(beforeGroups);
                    Mono<DirectorySnapshot> after = snapshotOfGroups(afterGroups);

                    DirectoryGroup existingOrEmpty = existing.orElseGet(() -> new DirectoryGroup(
                            group.id(), group.externalId(), group.displayName(), Set.of()));

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        if (existing.isEmpty() && result.hasFailure()) {
                            return Mono.empty();
                        }
                        DirectoryGroup reconciled = reconcileGroupMembers(
                                existingOrEmpty, group, beforeTuples, afterTuples, result);
                        return Mono.defer(() -> state.saveGroup(reconciled));
                    };

                    return diffAndApply(before, after, RelationTuple.groupRef(group.id()), Set.of(), lease, commit);
                }));
    }

    /**
     * 조직 생성(POST). {@code externalId} 가 있으면 다른 조직과 겹치는지 <b>락 안에서</b> 확인한다(설계 2026-10-04 §3.2). 겹치면 {@link DirectoryConflictException}.
     * 생성에는 "자기 자신"이 없어 이미 있는 조직을 하나도 빼지 않는다 — 같은 아이디·같은 {@code externalId} 로 다시 온 POST 도 409 이고 기존 조직을 덮어쓰지 않는다.
     */
    public Mono<IncrementalSyncResult> createGroup(DirectoryGroup group) {
        return withLock(lease -> externalId를_확인한다(group.externalId(), null)
                .then(Mono.defer(() -> upsertGroupInternal(group, lease))));
    }

    /**
     * 조직 {@code externalId} 가 다른 조직과 겹치는지 — {@link #userName을_확인한다} 와 같은 방식이다. GSI3 로 후보를 찾고 자기 자신({@code selfId}, 생성이면 null)을
     * 뺀 뒤 본 테이블을 강한 일관성으로 다시 읽어 여전히 같은 값일 때만 충돌이다. 빈 값은 보지 않는다. RFC 핵심 스키마에는 조직의 유일 속성이 없지만, 응답을 잃은 POST 의
     * 재시도가 같은 조직을 둘 만들지 않게 막는다(설계 §3.2).
     */
    private Mono<Void> externalId를_확인한다(String externalId, String selfId) {
        if (externalId == null || externalId.isBlank()) {
            return Mono.empty();
        }
        return state.findGroupIdsByExternalId(externalId)
                .filter(id -> !id.equals(selfId))
                .concatMap(state::findGroupHeader)
                .filter(other -> externalId.equals(other.externalId()))
                .next()
                .flatMap(other -> Mono.error(new DirectoryConflictException(
                        "이미 같은 externalId 를 쓰는 조직이 있습니다: externalId=%s, id=%s".formatted(externalId, other.id()))));
    }

    /**
     * 조직 PATCH·PUT (조직 멤버 PATCH 설계 §5). 조직이 없으면 빈 {@code Mono} 다.
     *
     * <p><b>모든 판단을 락을 잡은 뒤 읽은 값으로 한다.</b> 전에는 핸들러가 락 밖에서 읽은 멤버 목록으로 목표를 계산해, 동시에 온 두
     * PATCH 가 서로가 넣은 멤버를 지웠다(설계 §1.3). {@link GroupChange} 는 저장소를 읽지 않고 만들어진다.
     *
     * <p><b>바뀌는 멤버만 담은 조직</b>을 {@link #diffAndApply} 에 넣는다. 멤버십에서 나오는 튜플은
     * {@code direct_member(user:X, group:G)}·{@code child(group:S, group:G)} 뿐이라 한 멤버와 이 조직만 언급한다 — 그림에 없는 멤버의
     * 튜플은 델타에 들어오지 않는다. 이미 있는 조직이라 상위 조직과의 child 엣지도 바뀌지 않으므로 상위 조직을 싣지 않는다
     * ({@link #upsertGroup} 이 싣는 이유는 새로 생기는 조직이다). 그래서 비용이 조직 크기가 아니라 바뀌는 멤버 수를 따른다.
     * {@link #직원한명_그림} 과 같은 논리다.
     *
     * <p>증분은 요청에 나온 멤버를 전후가 같아도 그림에 남긴다 — 그 멤버의 어긋남은 지금처럼 고친다. 요청에 나오지 않은 멤버는
     * 점검하지 않는다(설계 §11). 전체 교체는 목록이 전원을 가리키므로 바뀌는 멤버만 싣는다.
     *
     * <p>빠지는 멤버의 줄은 Check·직원 읽기 없이 "없으면 무시"로 지운다(설계 2026-10-02 §4.2) — 멤버 전원 빼기·빈 교체가 조직 크기만큼 읽지 않는다.
     *
     * <p>하위 조직 연결을 지우면 끝에서 보류 목록을 다시 본다(설계 2026-10-03 §4.5).
     *
     * <p>{@code externalId} 가 바뀌면 다른 조직과 겹치는지 먼저 확인하고, 겹치면 아무것도 쓰지 않고 {@link DirectoryConflictException} 이다(설계 2026-10-04 §3.2).
     */
    public Mono<IncrementalSyncResult> changeGroup(String groupId, GroupChange change) {
        return withLock(lease -> changeGroupInternal(groupId, change, lease));
    }

    private Mono<IncrementalSyncResult> changeGroupInternal(String groupId, GroupChange change, LockLease lease) {
        return state.findGroupHeader(groupId)
                .flatMap(header -> {
                    GroupHeader 바뀐헤더 = change.applyTo(header);
                    Mono<Void> 확인 = Objects.equals(header.externalId(), 바뀐헤더.externalId())
                            ? Mono.empty()
                            : externalId를_확인한다(바뀐헤더.externalId(), groupId);
                    return 확인.then(Mono.defer(() -> 바뀌는_멤버(groupId, change).flatMap(전후 -> {
                        Set<MemberRef> 빠질것 = 차집합(전후.전(), 전후.후());
                        DirectoryGroup 전 = new DirectoryGroup(groupId, header.externalId(), header.displayName(), 전후.전());
                        // 그림에는 빠지는 멤버를 싣지 않는다 — 그 멤버의 직원을 읽지도, 그 줄을 Check 하지도 않는다(설계 2026-10-02 §4.2)
                        DirectoryGroup 그림_전 = new DirectoryGroup(groupId, header.externalId(), header.displayName(),
                                차집합(전후.전(), 빠질것));
                        DirectoryGroup 후 = new DirectoryGroup(groupId, 바뀐헤더.externalId(), 바뀐헤더.displayName(), 전후.후());
                        Set<RelationTuple> 확인없이_지울것 = 빠질것.stream()
                                .map(member -> tupleFor(member, groupId))
                                .collect(Collectors.toCollection(LinkedHashSet::new));

                        Commit commit = (result, beforeTuples, afterTuples) -> {
                            DirectoryGroup reconciled = reconcileGroupMembers(전, 후, beforeTuples, afterTuples, result);
                            Set<MemberRef> 넣을것 = 차집합(reconciled.members(), 전.members());
                            Set<MemberRef> 뺄것 = 차집합(전.members(), reconciled.members());
                            return Mono.defer(() -> state.saveGroupChange(header, 바뀐헤더, 넣을것, 뺄것));
                        };

                        boolean 하위_조직을_뺀다 = 빠질것.stream().anyMatch(member -> member.type() == MemberType.GROUP);
                        return diffAndApply(snapshotOfGroups(Set.of(그림_전)), snapshotOfGroups(Set.of(후)),
                                RelationTuple.groupRef(groupId), 확인없이_지울것, lease, commit)
                                .flatMap(result -> 하위_조직을_뺀다
                                        ? 보류를_다시_본다(lease).thenReturn(result)
                                        : Mono.just(result));
                    })));
                });
    }

    /**
     * 이번 변경이 닿는 멤버의 변경 전·후 소속. 전 은 지금 멤버 중 변경이 닿는 것(전체 교체면 빠질 멤버), 후 는 반영 뒤 멤버가 될 것(아직 멤버가
     * 아닌 넣을 멤버 포함). 전 에만 있는 멤버(빠질 멤버)는 그림에 싣지 않고 Check·직원 읽기 없이 지운다(설계 2026-10-02 §4.2).
     */
    private record 멤버전후(Set<MemberRef> 전, Set<MemberRef> 후) {
    }

    private Mono<멤버전후> 바뀌는_멤버(String groupId, GroupChange change) {
        if (change.replacesMembers()) {
            return state.findMemberRefs(groupId)
                    .collect(LinkedHashSet<MemberRef>::new, Set::add)
                    .flatMap(지금 -> 종류판정(groupId, change, change.base()).map(조직이면 -> {
                        Set<MemberRef> 목표 = change.replay(change.base(), 조직이면);
                        return new 멤버전후(차집합(지금, 목표), 차집합(목표, 지금));
                    }));
        }
        return state.findMembers(groupId, change.mentioned())
                .flatMap(지금 -> 종류판정(groupId, change, 지금)
                        .map(조직이면 -> new 멤버전후(지금, change.replay(지금, 조직이면))));
    }

    /**
     * 직원·하위 조직이 같은 id 로 둘 다 멤버일 때 id 로 빼면 — 전처럼 현재상태에 그 조직이 있으면 하위 조직을 뺀다
     * ({@code StateMemberTypeResolver} 의 순서). 모호할 수 있는 id 만 묻는다.
     */
    private Mono<Predicate<String>> 종류판정(String groupId, GroupChange change, Set<MemberRef> start) {
        Set<String> ids = change.ambiguousIds(start);
        if (ids.isEmpty()) {
            return Mono.<Predicate<String>>just(id -> false);
        }
        ids.forEach(id -> log.warn("members[value eq \"{}\"] 가 직원과 하위 조직 양쪽에 걸립니다. 현재상태로 한쪽만 지웁니다: 조직={}",
                id, groupId));
        return Flux.fromIterable(ids)
                .filterWhen(id -> state.findGroupHeader(id).hasElement())
                .collect(Collectors.toSet())
                .<Predicate<String>>map(조직 -> 조직::contains);
    }

    private static Set<MemberRef> 차집합(Set<MemberRef> from, Set<MemberRef> minus) {
        Set<MemberRef> result = new LinkedHashSet<>(from);
        result.removeAll(minus);
        return result;
    }

    /**
     * 직원 삭제. 그 직원이 속한 모든 조직에서 멤버십도 함께 지운다.
     *
     * <p>삭제 튜플이 실패한 조직은 멤버십을 그대로 둔다.
     * 하나라도 실패하면 직원 레코드 자체도 지우지 않는다 — 지워버리면 다음 재시도가 diff 할
     * "이전"이 사라져 남은 튜플을 영원히 다시 잡지 못한다.
     *
     * <p>대상이 없으면 빈 {@code Mono} 다 — 존재 확인도 락 안이다(SCIM 쓰기 락 설계 §3).
     */
    public Mono<IncrementalSyncResult> removeUser(String userId) {
        return withLock(lease -> removeUserInternal(userId, lease));
    }

    private Mono<IncrementalSyncResult> removeUserInternal(String userId, LockLease lease) {
        MemberRef 이직원 = MemberRef.user(userId);
        return state.findUser(userId)
                .flatMap(user -> affectedGroupHeadersOf(userId).flatMap(headers -> {
                    // 스냅샷은 좁힌다 — 델타에는 이 직원의 튜플만 남으므로 동료가 필요 없다.
                    Mono<DirectorySnapshot> before = 직원한명_그림(headers, userId, Mono.just(user));
                    // 삭제 후에는 어느 조직에도 속하지 않으므로 조직이 하나도 없는 그림이 맞다.
                    Mono<DirectorySnapshot> after = 직원한명_그림(Set.of(), userId, Mono.empty());

                    Commit commit = (result, beforeTuples, afterTuples) -> {
                        // 튜플이 원래 있었는데 지워지지 않은 조직은 멤버십을 남긴다(아래 멤버십을_지운다).
                        // 나머지는 그 직원의 멤버 줄·소속 줄만 지운다 — 조직 멤버 목록 전체를 읽고 쓰지 않는다(설계 §5).
                        Mono<Void> saveGroups = Flux.fromIterable(headers)
                                .filter(header -> 멤버십을_지운다(tupleFor(이직원, header.id()), beforeTuples, result))
                                .flatMap(header -> state.saveGroupChange(header, header, Set.of(), Set.of(이직원)), LOAD_CONCURRENCY)
                                .then();
                        if (result.hasFailure()) {
                            return saveGroups;
                        }
                        return saveGroups.then(Mono.defer(() -> state.deleteUser(userId)));
                    };

                    return diffAndApply(before, after, RelationTuple.userRef(userId), Set.of(), lease, commit);
                }));
    }

    /** 튜플이 원래 없었거나 이번에 지워졌으면 멤버십도 지운다. 원래 있었는데 지우지 못했으면 남겨 재시도가 다시 보게 한다. */
    private static boolean 멤버십을_지운다(RelationTuple tuple, Set<RelationTuple> beforeTuples, TupleWriteResult result) {
        return !beforeTuples.contains(tuple) || result.deleted().contains(tuple);
    }

    /**
     * 조직 삭제 (설계 2026-10-02 §4.1). 삭제 뒤의 모습은 "이 조직을 언급하는 줄이 하나도 없음"으로 정해져 있어 계산(직원 읽기·Check·diff)을 하지 않는다.
     * 조직 파티션을 한 번 읽어 멤버를 얻고, 상위 조직은 아이디만 읽는다(소속 줄). 그 조직을 언급하는 줄을 "없으면 무시"로 지운다.
     *
     * <p>다 지웠으면 조직을 지운다(META 맨 마지막 — 저장소 계약). 일부를 못 지웠으면 조직을 남기고 지운 멤버·상위 조직 줄만 뺀다 — 응답은 5xx 이고
     * IdP 의 재시도가 남은 것을 지운다. 대상이 없으면 빈 {@code Mono} 다 — 존재 확인도 락 안이다(SCIM 쓰기 락 설계 §3).
     *
     * <p>하위 조직 연결을 지우면 끝에서 보류 목록을 다시 본다(설계 2026-10-03 §4.5).
     */
    public Mono<IncrementalSyncResult> removeGroup(String groupId) {
        return withLock(lease -> removeGroupInternal(groupId, lease));
    }

    private Mono<IncrementalSyncResult> removeGroupInternal(String groupId, LockLease lease) {
        return state.findGroup(groupId)
                .flatMap(group -> state.findGroupIdsContaining(MemberRef.group(groupId))
                        .collect(LinkedHashSet<String>::new, Set::add)
                        .flatMap(parentIds -> {
                            boolean 연결을_지운다 = !parentIds.isEmpty()
                                    || group.members().stream().anyMatch(member -> member.type() == MemberType.GROUP);
                            return 반영하고_커밋한다(TupleDelta.deleteOnly(조직을_언급하는_튜플(group, parentIds)), lease,
                                    result -> 조직_삭제를_커밋한다(group, parentIds, result))
                                    .flatMap(result -> 연결을_지운다
                                            ? 보류를_다시_본다(lease).thenReturn(result)
                                            : Mono.just(result));
                        }));
    }

    /** 조직이 사라지면 없어야 할 줄 — 직원→조직, 하위 조직→조직, 조직→상위 조직. 비활성 직원의 줄도 넣는다 — 있으면 지워야 하고 없으면 무시된다. */
    private static Set<RelationTuple> 조직을_언급하는_튜플(DirectoryGroup group, Set<String> parentIds) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        group.members().forEach(member -> tuples.add(tupleFor(member, group.id())));
        parentIds.forEach(parent -> tuples.add(RelationTuple.child(group.id(), parent)));
        return tuples;
    }

    private Mono<Void> 조직_삭제를_커밋한다(DirectoryGroup group, Set<String> parentIds, TupleWriteResult result) {
        if (!result.hasFailure()) {
            return state.deleteGroup(group.id(), group.members());
        }
        MemberRef 이조직 = MemberRef.group(group.id());
        Set<MemberRef> 지운멤버 = group.members().stream()
                .filter(member -> result.deleted().contains(tupleFor(member, group.id())))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        GroupHeader header = new GroupHeader(group.id(), group.externalId(), group.displayName());
        Mono<Void> 상위에서_뺀다 = Flux.fromIterable(parentIds)
                .filter(parent -> result.deleted().contains(RelationTuple.child(group.id(), parent)))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .flatMap(parent -> state.saveGroupChange(parent, parent, Set.of(), Set.of(이조직)), LOAD_CONCURRENCY)
                .then();
        return 상위에서_뺀다.then(Mono.defer(() -> state.saveGroupChange(header, header, Set.of(), 지운멤버)));
    }

    /**
     * 하위 조직 연결을 지운 요청 끝에서 보류 목록을 다시 본다(설계 2026-10-03 §4.5, 점검 M1). 순환은 연결을 지울 때만 풀린다. 튜플이 이미 OpenFGA 에 있는 줄과
     * 멤버 줄이 없는 보류 줄은 지우고, 이제 순환이 아닌 연결은 리스를 확인한 뒤 튜플을 쓰고 목록에서 뺀다. 보류 목록은 보통 비어 있어 읽기 1번으로 끝난다.
     *
     * <p><b>원래 요청을 실패시키지 않는다.</b> 원래 연산은 이미 커밋됐다. 여기서 난 오류(OpenFGA·DynamoDB·리스 확인)는 경고만 남기고 목록을 그대로 둔다 — 다음 지우기
     * 요청이나 재적재가 다시 본다.
     */
    private Mono<Void> 보류를_다시_본다(LockLease lease) {
        OrgGraph 그래프 = new OrgGraph(state, checker);
        return 그래프.보류()
                // 튜플이 이미 있는 줄은 보류가 아니다 — 저장된 목록에서 먼저 지우고, 남은 줄만 본다
                .flatMap(cut -> state.changeCutEdges(Set.of(), 그래프.이미_있던_보류()).thenReturn(cut))
                .flatMapMany(cut -> Flux.fromIterable(cut.stream().sorted(OrgGraph.아이디순).toList()))
                .concatMap(edge -> 한_줄을_다시_본다(edge, 그래프, lease))
                .then()
                .onErrorResume(error -> {
                    log.warn("보류 목록을 다시 보지 못했다 — 다음 지우기 요청이나 재적재가 다시 본다", error);
                    return Mono.empty();
                });
    }

    private Mono<Void> 한_줄을_다시_본다(GroupEdge edge, OrgGraph 그래프, LockLease lease) {
        return state.findMembers(edge.parent(), Set.of(MemberRef.group(edge.child()))).flatMap(멤버 -> {
            if (멤버.isEmpty()) {
                그래프.보류에서_뺀다(edge);
                return state.changeCutEdges(Set.of(), Set.of(edge));
            }
            return 그래프.순환인가(edge).flatMap(순환 -> 순환
                    ? Mono.<Void>empty()
                    : 리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")
                            .then(Mono.defer(() -> writer.apply(new TupleDelta(Set.of(edge.tuple()), Set.of()))))
                            .flatMap(result -> {
                                if (!result.written().contains(edge.tuple())) {
                                    return Mono.empty(); // 쓰기 실패 — 목록에 남겨 다음에 다시 본다
                                }
                                그래프.보류에서_뺀다(edge);
                                log.info("순환이 풀려 보류했던 연결을 썼다: 조직 '{}' → '{}'", edge.parent(), edge.child());
                                return state.changeCutEdges(Set.of(), Set.of(edge));
                            }));
        });
    }

    // ---------- 공통 ----------

    /** {@code before}/{@code after} 튜플 집합과 반영 결과를 받아 실제 커밋을 수행한다. */
    @FunctionalInterface
    private interface Commit {
        Mono<Void> apply(TupleWriteResult result, Set<RelationTuple> before, Set<RelationTuple> after);
    }

    /**
     * 변경 하나를 락 안에서 실행한다 (설계 §4, 2026-10-02 §3).
     *
     * <p><b>왜 유스케이스가 잡나.</b> 핸들러마다 넣으면 나중에 경로가 하나 늘 때 조용히 빠지고,
     * 그 빠진 곳이 하필 다른 인스턴스와 경합한다. 여기 두면 여덟 경로({@link #upsertUser}·
     * {@link #createUser}·{@link #changeUser}·{@link #removeUser}·{@link #upsertGroup}·
     * {@link #createGroup}·{@link #changeGroup}·{@link #removeGroup})가 빠짐없이 덮인다.
     *
     * <p><b>요청과 떼어 돈다(점검 S3).</b> 락 잡기부터 반납까지를 요청의 구독과 따로 돌린다. IdP 가 연결을 끊어도 커밋까지 마치고
     * 반납한다 — 끊기는 순간 반납하면 이미 보낸 OpenFGA 쓰기가 다음 요청의 Check 뒤에 떨어져 그 요청의 기준선이 틀린다. 락을 잡는 도중에
     * 끊겨 리스가 새는 일도 없어진다. 요청의 Reactor Context(traceId)는 이어받는다. 요청이 떠난 뒤의 실패·부분 반영은 받을 곳이 없으므로
     * 경고 로그로 남긴다 — 요청이 남아 있으면 응답과 {@code ScimRouter} 가 알리므로 남기지 않는다.
     *
     * <p><b>리스를 지킨다.</b> 쥔 동안 {@link LeaseKeeper} 가 리스를 갱신하고, 잃으면 멈춰 503 이다. OpenFGA 쓰기 직전·DynamoDB 커밋 직전에
     * 다시 확인한다({@link #반영하고_커밋한다}). <b>반납한 뒤 응답한다</b> — IdP 의 다음 요청이 이 요청의 락에 막히지 않는다.
     *
     * <p><b>반납이 실패하면</b>(스로틀, 네트워크) 리스가 만료될 때까지 이 인스턴스도 남도 다시 잡지 못한다. 응답은 성공이다 — 일은 끝났다.
     * 대신 {@link LockObserver#leaseLost} 를 올려 {@code scim.lock.lease_lost} 에 나타난다.
     *
     * <p><b>획득이 예외로 끝나면 그것도 503 이다 (설계 §6 두 번째 행).</b> DynamoDB 부분 장애로
     * {@code putItem} 이 {@code SdkException} 을 던지면 그대로 흘려보낼 수 없다 —
     * {@code ScimRouter} 의 기본 분기가 500 을 내고, IdP 는 500 을 <b>영구 실패</b>로 읽어
     * 프로비저닝을 버린다. 재시도해야 할 바로 그 순간에. 그래서 획득 구간의 모든 에러를
     * {@link LockUnavailableException} 으로 옮긴다 — "어차피 커밋도 못 한다".
     */
    private Mono<IncrementalSyncResult> withLock(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return Mono.deferContextual(context -> {
            Sinks.One<IncrementalSyncResult> 결과 = Sinks.one();
            AtomicBoolean 요청이_떠났다 = new AtomicBoolean();
            // 알리기 전에 본다 — 값을 받은 뒤에 오는 취소는 떠난 것이 아니다.
            잡고_돌린다(work)
                    .contextWrite(context)
                    .subscribe(value -> {
                        if (요청이_떠났다.get() && !value.fullyApplied()) {
                            log.warn("요청이 떠난 뒤 SCIM 쓰기가 부분 반영으로 끝났다 — 실패한 튜플 {}개. IdP 는 이 결과를 받지 못했다",
                                    value.writeResult().failures().size());
                        }
                        결과.tryEmitValue(value);
                    }, error -> {
                        if (요청이_떠났다.get()) {
                            log.warn("요청이 떠난 뒤 SCIM 쓰기가 실패했다 — IdP 는 이 결과를 받지 못했다", error);
                        }
                        결과.tryEmitError(error);
                    }, 결과::tryEmitEmpty);
            return 결과.asMono().doOnCancel(() -> 요청이_떠났다.set(true));
        });
    }

    private Mono<IncrementalSyncResult> 잡고_돌린다(Function<LockLease, Mono<IncrementalSyncResult>> work) {
        return Mono.defer(() -> {
            long 시작 = System.nanoTime();
            AtomicBoolean 경합했다 = new AtomicBoolean();

            return lock.acquire(MutationLock.LockPurpose.WRITE)
                    // retryWhen 위에 둬야 시도마다 불린다 — 아래에 두면 마지막 실패만 본다.
                    .doOnError(LockUnavailableException.class, error -> 경합했다.set(true))
                    // 밀리초 단위로 쥐는 락이라 즉시 503 을 내면 재시도만 늘어난다. 짧게 기다려보고
                    // 그래도 안 되면 그때의 503 이 IdP 에게 의미 있는 신호가 된다 (설계 §4.4).
                    .retryWhen(Retry.fixedDelay(acquireRetries(), ACQUIRE_RETRY_DELAY)
                            .filter(LockUnavailableException.class::isInstance))
                    .onErrorMap(Exceptions::isRetryExhausted,
                            error -> new LockUnavailableException("변경 락을 얻지 못했습니다"))
                    // DynamoDB 장애 등 락 이외의 예외도 503 으로 옮긴다 (설계 §6).
                    .onErrorMap(error -> !(error instanceof LockUnavailableException),
                            error -> new LockUnavailableException("변경 락을 얻는 중 오류가 발생했습니다", error))
                    // 실패했다고 다 경합은 아니다. 위 onErrorMap 이 DynamoDB 장애도
                    // LockUnavailableException 으로 옮기므로 예외 타입으로는 구별할 수 없고,
                    // 실제로 밀렸을 때만 켜지는 이 플래그로 봐야 한다.
                    .doOnSuccess(lease -> lockObserver.acquireFinished(경과(시작), 경합했다.get()))
                    .doOnError(error -> lockObserver.acquireFinished(경과(시작), 경합했다.get()))
                    .flatMap(lease -> new LeaseKeeper(lock, renewInterval, lockObserver)
                            .keep(lease, Mono.defer(() -> work.apply(lease)), "쓰기 도중 리스 상실")
                            .materialize()
                            .flatMap(끝 -> 반납한다(lease).thenReturn(끝))
                            .<IncrementalSyncResult>dematerialize());
        });
    }

    /** 반납 실패는 요청을 실패시키지 않는다 — 일은 이미 끝났다. 리스가 만료될 때까지 아무도 잡지 못하므로 지표로 남긴다. */
    private Mono<Void> 반납한다(LockLease lease) {
        return lock.release(lease)
                .onErrorResume(error -> {
                    log.warn("변경 락 반납이 실패했다. 리스가 만료될 때까지 아무도 잡지 못한다", error);
                    lockObserver.leaseLost("반납 실패");
                    return Mono.empty();
                });
    }

    private static Duration 경과(long 시작나노) {
        return Duration.ofNanos(System.nanoTime() - 시작나노);
    }

    /**
     * 기준선을 <b>OpenFGA 에 물어서</b> 만든다 (설계 §5).
     *
     * <p>전에는 {@code TupleMapper(변경 전 상태)} 를 기준선으로 썼다. 그것은 "있어야 했던 것"
     * 이라, 어긋난 튜플이 있어도 양쪽에서 똑같이 빠져 델타가 비었다 — 계산은 매번 정확하고
     * 틀린 곳을 볼 방법만 없었다.
     *
     * <p>후보는 {@code TupleMapper.candidateTuples} 로 뽑는다. `active` 필터를 적용하기 전의
     * 멤버십이어야 비활성 직원의 잘못 남은 튜플이 확인 대상에 들어온다. 거기서 다시
     * {@code focus}(이번 연산의 초점 엔티티)를 언급하는 것만 남긴다 — {@link #mentioning} 참고.
     *
     * <p><b>Check 가 실패하면 폴백하지 않는다.</b> 상태 기준선으로 돌아가면 조용히 옛 동작이
     * 되고, 그게 하필 어긋남이 생기는 순간이다. 실패시켜 IdP 가 재시도하게 둔다.
     *
     * <p><b>리스는 OpenFGA 쓰기 직전과 커밋 직전에 늘 확인한다</b> — {@link #반영하고_커밋한다} 참고(설계 2026-10-02 §3.2).
     *
     * <p><b>설계 §7.2 와의 의도적 차이(버그가 아니다).</b> 스펙 표는 "전부 실패 → 저장하지 않음"
     * 이라고 적었지만 여기서는 실패해도 {@code commit} 을 부른다. 각 연산의 커밋 로직이
     * 이미 "실제로 반영된 만큼만" 상태에 남기도록 되어 있어 <b>멤버십은 어차피 그대로 유지</b>
     * 되고, 그렇게 해서 남는 것은 조직/직원의 META 속성(displayName, email 같은 것)뿐이다.
     * 이 필드들은 어느 것도 튜플 식별자가 아니라서 저장돼도 다음 diff 를 오염시키지 않는다.
     * 오히려 이름 변경 같은 튜플과 무관한 수정이 튜플 실패에 발목잡히지 않아 스펙보다 낫다.
     * 단 하나의 예외가 <b>레코드의 존재 자체</b>다 — 그것은 부모의 child 엣지가 성립하는
     * 조건이므로 튜플 식별자에 해당한다. 그래서 {@link #upsertGroup} 은 새 조직에 한해,
     * {@link #removeUser}/{@link #removeGroup} 은 삭제에 한해 실패 시 존재 여부를 건드리지 않는다.
     *
     * <p>보류 목록은 커밋 앞뒤로 맞춘다(설계 2026-10-03 §4.4) — {@link #보류와_함께_커밋한다} 참고.
     *
     * @param 확인없이_지울것 Check 없이 지울 줄(빠지는 멤버). 드리프트 지표는 이 줄을 재지 않는다.
     */
    private Mono<IncrementalSyncResult> diffAndApply(Mono<DirectorySnapshot> beforeMono,
                                                      Mono<DirectorySnapshot> afterMono,
                                                      String focus,
                                                      Set<RelationTuple> 확인없이_지울것,
                                                      LockLease lease,
                                                      Commit commit) {
        return Mono.zip(beforeMono, afterMono).flatMap(both -> {
            DirectorySnapshot beforeSnapshot = both.getT1();
            DirectorySnapshot afterSnapshot = both.getT2();

            Set<RelationTuple> 모든후보 = new LinkedHashSet<>();
            모든후보.addAll(TupleMapper.candidateTuples(beforeSnapshot));
            모든후보.addAll(TupleMapper.candidateTuples(afterSnapshot));
            Set<RelationTuple> candidates = mentioning(모든후보, focus);
            // 순환을 버리지 않은 목표 — 순환 판단은 OrgGraph 가 튜플 그래프로 한다(설계 2026-10-03 §4.3)
            Set<RelationTuple> 있어야했던것 = mentioning(순환을_버리지_않고(beforeSnapshot), focus);
            Set<RelationTuple> 원하는것 = mentioning(순환을_버리지_않고(afterSnapshot), focus);
            OrgGraph 그래프 = new OrgGraph(state, checker);

            return checker.existing(candidates).flatMap(actual -> 그래프.필요하면_보류(있어야했던것, 원하는것).flatMap(보류 -> {
                // 상태 기준선(있어야 했던 것)과 Check 기준선(실제 있는 것)을 비교한다 —
                // 이 둘이 다르면 그것이 곧 어긋남이다(설계 §7). 델타 계산 자체는 여전히
                // Check 기준선(actual)을 쓴다; 여기서는 오직 관측만 한다.
                // 상태 기준선도 같은 술어로 좁힌다 — actual 이 초점 밖 튜플을 아예 담지
                // 않으므로, 좁히지 않으면 이 연산이 보지도 않은 튜플이 전부 missing 으로
                // 세어져 지표가 거짓말을 한다. 보류한 연결은 상태 기준선에서 뺀다 — OpenFGA 에 없는 것이 맞다.
                Set<RelationTuple> 상태기준선 = new LinkedHashSet<>(있어야했던것);
                보류.forEach(edge -> 상태기준선.remove(edge.tuple()));
                int extra = (int) actual.stream().filter(t -> !상태기준선.contains(t)).count();
                int missing = (int) 상태기준선.stream().filter(t -> !actual.contains(t)).count();
                if (extra > 0 || missing > 0) {
                    log.warn("OpenFGA 어긋남 발견: 있어선 안 될 튜플 {}건, 빠진 튜플 {}건", extra, missing);
                    driftObserver.observed(extra, missing);
                }

                return 그래프.거른다(actual, 원하는것, focus).flatMap(거름 -> {
                    Set<RelationTuple> after = 거름.남길것();
                    TupleDelta 계산 = TupleDiff.between(actual, after);
                    if (확인없이_지울것.isEmpty()) {
                        return 반영하고_커밋한다(계산, lease, result -> 보류와_함께_커밋한다(거름, result, actual, after, commit));
                    }
                    // 빠지는 멤버의 줄은 Check 없이 "없으면 무시"로 지운다(설계 2026-10-02 §4.2). 커밋의 재조정은 그 줄이 있었다고 본다 —
                    // 지우기가 실패한 멤버만 남는다.
                    Set<RelationTuple> 지울것 = new LinkedHashSet<>(계산.toDelete());
                    지울것.addAll(확인없이_지울것);
                    Set<RelationTuple> 있다고_볼것 = new LinkedHashSet<>(actual);
                    있다고_볼것.addAll(확인없이_지울것);
                    return 반영하고_커밋한다(new TupleDelta(계산.toWrite(), 지울것), lease,
                            result -> 보류와_함께_커밋한다(거름, result, 있다고_볼것, after, commit));
                });
            }));
        });
    }

    /**
     * 커밋 앞뒤로 보류 목록을 맞춘다(설계 2026-10-03 §4.4). 새로 보류할 줄을 <b>멤버 줄보다 먼저</b> 쓴다 — 거꾸로면 멈춘 뒤 "멤버 줄은 있는데 튜플도 보류 기록도
     * 없는" 연결이 남아 영영 쓰이지 않는다(점검 M1 과 같은 누락). 풀린 줄은 OpenFGA 에 실제로 있게 된 것만, 목록을 읽을 때 이미 튜플이 있던 줄은 모두 커밋 뒤에 뺀다.
     */
    private Mono<Void> 보류와_함께_커밋한다(OrgGraph.거른결과 거름, TupleWriteResult result,
                                    Set<RelationTuple> 기준, Set<RelationTuple> 목표, Commit commit) {
        return state.changeCutEdges(거름.새로_보류(), Set.of())
                .then(Mono.defer(() -> commit.apply(result, 기준, 목표)))
                .then(Mono.defer(() -> state.changeCutEdges(Set.of(), 거름.뺄것(result))));
    }

    /** 순환을 버리지 않은 튜플. 순환이 아닌 경고(없는 멤버 등)는 지금처럼 남긴다. */
    private Set<RelationTuple> 순환을_버리지_않고(DirectorySnapshot snapshot) {
        var mapping = TupleMapper.toTuplesKeepingCycles(snapshot);
        mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
        return mapping.tuples();
    }

    /**
     * 델타를 OpenFGA 에 반영하고 DynamoDB 에 커밋한다. <b>OpenFGA 쓰기 직전과 커밋 직전에 늘 리스를 확인한다</b>(설계 2026-10-02 §3.2, 점검 M8) —
     * 바뀐 튜플이 없을 때도. 커밋은 조건 없는 덮어쓰기라, 30초 넘게 멈춘 요청이 확인 없이 커밋하면 다른 인스턴스가 저장한 비활성화를 되돌린다.
     * 확인이 실패하면 쓰지 않고 503 이다. renew 는 토큰 조건이 걸린 조건부 쓰기라 성공했다는 것이 곧 "아직 내가 쥐고 있다"는 증거다.
     *
     * <p>{@code writer.apply}·{@code 커밋} 을 {@code Mono.defer} 로 감싼다 — 감싸지 않으면 확인이 실패해도 그 표현식이 이미 평가된 뒤다.
     */
    private Mono<IncrementalSyncResult> 반영하고_커밋한다(TupleDelta delta, LockLease lease,
                                                   Function<TupleWriteResult, Mono<Void>> 커밋) {
        if (delta.isEmpty()) {
            return 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                    .then(Mono.defer(() -> 커밋.apply(TupleWriteResult.empty())))
                    .thenReturn(IncrementalSyncResult.noChange());
        }
        return 리스를_확인한다(lease, "쓰기 직전 리스 재확인 실패")
                .then(Mono.defer(() -> writer.apply(delta)))
                .flatMap(result -> 리스를_확인한다(lease, "커밋 직전 리스 재확인 실패")
                        .then(Mono.defer(() -> 커밋.apply(result)))
                        .thenReturn(IncrementalSyncResult.of(result)));
    }

    /** 리스를 확인(갱신)한다. 실패하면 {@link LockUnavailableException} — 저장소 장애도 503 으로 옮긴다(획득과 같은 이유). */
    private Mono<Void> 리스를_확인한다(LockLease lease, String 실패_사유) {
        return lock.renew(lease)
                .doOnError(error -> lockObserver.leaseLost(실패_사유))
                .onErrorMap(error -> !(error instanceof LockUnavailableException),
                        error -> new LockUnavailableException("변경 락 리스를 확인하지 못했습니다", error))
                .then();
    }

    /**
     * 이번 연산의 <b>초점 엔티티</b>를 언급하는 튜플만 남긴다 (설계 §5.2).
     *
     * <p>최소 스냅샷이 싣는 영향 조직은 대개 이미 좁혀져 있다 — 상위 조직은 이 조직 하나만({@link #상위_조직들}),
     * 직원 연산의 소속 조직은 그 직원 하나만({@link #직원한명_그림}) 멤버로 싣는다. 그래도 이 술어로 한 번 더 좁힌다.
     * 영향 조직을 멤버 목록째로 실으면 {@code candidateTuples} 를 그대로 쓸 때 그 조직의 <i>모든</i> 멤버가
     * 후보가 되고, {@code PUT /Users/kim} 한 번이 5000명 조직 전체를 BatchCheck 하게 된다 — 전역 락을 쥔 채로.
     * 그림을 넓히는 변경이 생겨도 그 비용이 돌아오지 않게 하는 마지막 울타리다. 설계 §5.2 는 정반대를 요구한다:
     * <i>"upsertUser 가 소속 조직의 전체 멤버를 확인하지 않는 것이 중요하다"</i>,
     * <i>"무관한 튜플까지 확인하면 비용만 늘고 삭제 범위만 위험해진다"</i>.
     *
     * <p><b>후보와 목표를 같은 술어로 좁혀야 한다.</b> 후보만 좁히면 {@code actual} 에는 없고
     * {@code after} 에는 있는 튜플이 생겨, 실제로는 멀쩡히 있는 튜플을 델타가 매번 다시 쓴다.
     * 관측용 상태 기준선도 같이 좁힌다 — 그래야 {@code extra}/{@code missing} 이 이 연산이
     * 실제로 본 범위를 뜻한다.
     *
     * <p>양쪽 자리를 다 보는 이유는 {@link RelationTuple#mentions} 참고 — {@code upsertGroup}
     * 의 후보에는 {@code child(DEV001, 상위조직)} 처럼 초점이 user 자리인 것도 들어간다.
     */
    private static Set<RelationTuple> mentioning(Set<RelationTuple> tuples, String focus) {
        Set<RelationTuple> narrowed = new LinkedHashSet<>();
        for (RelationTuple tuple : tuples) {
            if (tuple.mentions(focus)) {
                narrowed.add(tuple);
            }
        }
        return narrowed;
    }

    /**
     * 반영이 실패하면 {@code active} 를 이전 값으로 되돌린 유저를 돌려준다. 다른 필드는
     * 요청값을 그대로 쓴다 — 튜플에 영향을 주는 것은 {@code active} 뿐이라, 실패했을 때
     * 그것만 되돌리면 다음 동기화가 같은 델타를 다시 계산해 재시도한다.
     */
    private static DirectoryUser reconcileUser(DirectoryUser existing, DirectoryUser requested, TupleWriteResult result) {
        if (!result.hasFailure()) {
            return requested;
        }
        return requested.withActive(existing.active());
    }

    /**
     * 조직의 최종 멤버 목록을, 실제로 반영된 만큼만 계산한다.
     *
     * <p>새로 추가된 멤버는 <b>그 튜플이 지금 OpenFGA 에 있을 때</b> 저장된다 — 이번에 우리가
     * 썼거나({@code result.written()}), 이미 있어서 쓸 필요가 없었거나({@code beforeTuples},
     * 즉 Check 기준선), 애초에 튜플이 필요 없는 멤버거나(비활성 유저, 존재하지 않는 하위 조직).
     * 빠진 멤버는 그 튜플이 실제로 지워졌을 때만(또는 원래 튜플이 없었을 때) 제외된다 —
     * 삭제가 실패하면 여전히 멤버로 남아, 다음 동기화가 다시 지우려 시도한다. {@link #changeGroup} 은 빠지는 멤버의 줄을 Check 없이
     * 지우므로 {@code beforeTuples} 에 그 줄이 있다고 넣어 준다({@link #diffAndApply}) — 지우기가 실패한 멤버만 남는다.
     *
     * <p><b>"이미 있음" 을 빠뜨리면 안 된다.</b> 기준선이 상태였을 때는 새 멤버의 튜플이 언제나
     * 델타에 들어가 {@code written} 에 나타났다. 기준선이 OpenFGA 로 바뀐 지금은 <b>이미 있는
     * 튜플이 델타에서 빠지므로</b> {@code written} 에도 없다. {@code written} 만 보면 그 멤버가
     * 조용히 누락되고, 멤버십이 없으니 다음 연산의 후보에도 들어오지 않아 영원히 고쳐지지
     * 않는다 — OpenFGA 쓰기 성공 뒤 DynamoDB 커밋이 실패해 IdP 가 같은 요청을 재시도하는,
     * 이 기능이 없애려는 바로 그 경로다.
     *
     * <p>부르는 곳은 {@link #upsertGroup} 과 {@link #changeGroup} 이다. 조직 삭제({@link #removeGroup})는 이 계산을 쓰지 않는다 —
     * 전용 경로가 지운 줄로 바로 정한다(설계 2026-10-02 §4.1).
     */
    private DirectoryGroup reconcileGroupMembers(DirectoryGroup existing,
                                                 DirectoryGroup requested,
                                                 Set<RelationTuple> beforeTuples,
                                                 Set<RelationTuple> afterTuples,
                                                 TupleWriteResult result) {
        Set<MemberRef> beforeMembers = existing.members();
        Set<MemberRef> requestedMembers = requested.members();
        Set<MemberRef> persisted = new LinkedHashSet<>();

        for (MemberRef member : requestedMembers) {
            if (beforeMembers.contains(member)) {
                persisted.add(member); // 변경 없는 멤버
                continue;
            }
            RelationTuple tuple = tupleFor(member, requested.id());
            boolean expected = afterTuples.contains(tuple);
            boolean inOpenFga = result.written().contains(tuple) || beforeTuples.contains(tuple);
            if (!expected || inOpenFga) {
                persisted.add(member);
            }
            // else: 튜플 반영 실패 -> 제외한 채로 둬서 다음 동기화가 재시도하게 한다
        }
        for (MemberRef member : beforeMembers) {
            if (requestedMembers.contains(member)) {
                continue; // 위에서 이미 유지됨
            }
            RelationTuple tuple = tupleFor(member, requested.id());
            boolean existedBefore = beforeTuples.contains(tuple);
            if (existedBefore && !result.deleted().contains(tuple)) {
                persisted.add(member); // 삭제 반영 실패 -> 여전히 멤버
            }
            // else: 원래 튜플이 없었거나(비활성 유저 등) 삭제가 성공 -> 제외 유지
        }

        return new DirectoryGroup(requested.id(), requested.externalId(), requested.displayName(), persisted);
    }

    private static RelationTuple tupleFor(MemberRef member, String groupId) {
        return member.type() == MemberType.USER
                ? RelationTuple.directMember(member.id(), groupId)
                : RelationTuple.child(member.id(), groupId);
    }

    /**
     * 이 조직을 하위 조직으로 적어 둔 상위 조직들 — <b>헤더만</b> 읽고 멤버는 이 조직 하나로만 싣는다(설계 2026-10-03 §3.4). 이 연산은 후보·목표·상태 기준선을
     * 모두 이 조직을 언급하는 튜플로 좁히므로({@link #mentioning}) 상위 조직의 다른 멤버는 결과에 기여하지 않는다 — {@link #직원한명_그림} 과 같은 논리다.
     * 목표는 순환을 버리지 않고 만들고 순환 판단은 {@link OrgGraph} 가 저장소의 튜플 그래프로 하므로(설계 2026-10-03 §4.3), 그림 안의 DFS 가 상위 조직의
     * 다른 멤버를 보고 버릴 연결을 고르는 일이 없다 — 상위 조직을 통째로 읽은 것과 결과가 정확히 같다.
     */
    private Mono<Set<DirectoryGroup>> 상위_조직들(String groupId) {
        Set<MemberRef> 이조직만 = Set.of(MemberRef.group(groupId));
        return state.findGroupIdsContaining(MemberRef.group(groupId))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .map(header -> new DirectoryGroup(header.id(), header.externalId(), header.displayName(), 이조직만))
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add);
    }

    private Mono<DirectorySnapshot> snapshotOfGroups(Set<DirectoryGroup> groups) {
        return snapshotOf(groups, Mono.empty());
    }

    /**
     * 조직 집합과 (선택적) 변경된 유저 하나로 최소 스냅샷을 만든다. 조직의 멤버 유저를
     * 모두 실어야 {@link TupleMapper} 가 활성 여부를 판정할 수 있고, 멤버로 참조된
     * 하위 조직의 존재도 실어야 {@link TupleMapper} 가 child 엣지를 만들 수 있다.
     */
    private Mono<DirectorySnapshot> snapshotOf(Set<DirectoryGroup> groups, Mono<DirectoryUser> changed) {
        return expandWithReferencedGroups(groups)
                .flatMap(allGroups -> changed.map(Set::of).defaultIfEmpty(Set.of())
                        .flatMap(overrides -> loadMemberUsers(allGroups, overrides)
                                .map(users -> new DirectorySnapshot(users, byId(allGroups)))));
    }

    /**
     * <b>직원 한 명에 대한 연산을 위한 스냅샷.</b> 조직의 멤버 목록을 그 직원 하나로 바꾸고,
     * 유저도 그 한 명만 싣는다. {@link #loadMemberUsers} 를 부르지 않는다.
     *
     * <p><b>왜 동료를 안 실어도 결과가 같은가.</b> {@link #diffAndApply} 가 후보·목표·상태
     * 기준선 셋 모두를 {@code mentioning(user:그사람)} 으로 좁힌다. 동료의 튜플은
     * {@code direct_member(user:X, group:G)} 라 어느 자리도 그 직원이 아니므로 <b>세 집합
     * 전부에서 사라진다.</b> 좁힌 스냅샷은 그 튜플들을 애초에 만들지 않을 뿐, 걸러진 결과가
     * 같다. child 간선은 {@code group:} 둘로만 이루어져 역시 언급되지 않고, 사용자는 조직
     * 그래프에 순환을 만들 수 없다.
     *
     * <p><b>여기서 만든 조직을 {@code saveGroup} 에 넘기면 안 된다.</b> 멤버 목록이 한 명뿐이라
     * 그 조직의 나머지 멤버 줄이 전부 삭제된다 — 저장에는 반드시 전체 목록을 쓴다.
     */
    private Mono<DirectorySnapshot> 직원한명_그림(Set<GroupHeader> headers,
                                             String userId,
                                             Mono<DirectoryUser> user) {
        Set<MemberRef> 그사람만 = Set.of(MemberRef.user(userId));
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        headers.forEach(header -> groups.put(header.id(), new DirectoryGroup(
                header.id(), header.externalId(), header.displayName(), 그사람만)));

        return user.map(Set::of).defaultIfEmpty(Set.<DirectoryUser>of())
                .map(users -> new DirectorySnapshot(byUserId(users), groups));
    }

    /**
     * 이 직원이 속한 모든 조직의 헤더. 멤버 목록이 필요 없는 {@link #upsertUser}·{@link #removeUser} 가 쓴다.
     *
     * <p>역참조가 강한 일관성이고 정확하므로(포트 계약 참고) 여기서 멤버십을 다시 확인하지 않는다.
     * 확인은 저장소 안에서 이미 끝났다.
     */
    private Mono<Set<GroupHeader>> affectedGroupHeadersOf(String userId) {
        return state.findGroupIdsContaining(MemberRef.user(userId))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .collect(LinkedHashSet<GroupHeader>::new, Set::add);
    }

    /**
     * {@code groups} 의 멤버 중 GROUP 타입인데 집합에 없는 것을 현재상태에서 읽어와 채운다.
     * {@link TupleMapper} 는 하위 조직이 스냅샷의 groups 맵에 없으면 child 엣지를 건너뛰고
     * 경고만 남기기 때문이다.
     *
     * <p><b>멤버는 비운 채로 채운다.</b> 읽어온 하위 조직을 실제 멤버 목록 그대로 채우면,
     * {@link TupleMapper#collectDirectMembers} 가 스냅샷에 있는 <i>모든</i> 조직의 direct_member
     * 튜플을 만든다는 사실과 부딪힌다 — 이 하위 조직이 참조 대상으로 딸려 들어오는지 여부는
     * 정확히 지금 바뀌는 것(부모의 멤버 목록)에 달려 있으므로, before/after 스냅샷 중 한쪽에만
     * 나타나면 그 하위 조직 <b>자신의</b>, 이 연산과 무관한 멤버 튜플이 델타에 새어 들어가
     * 지워지거나(또는 스푸리어스하게 다시 쓰이고) 만다. 존재만 확인하면 되므로 멤버를 비워서
     * {@code containsKey} 는 통과시키되 {@code collectDirectMembers}/{@code collectChildEdges} 가
     * 이 하위 조직으로부터는 아무 튜플도 만들지 않게 한다 — 그러면 어느 쪽 스냅샷에 들어있든
     * 기여가 0이라 결과가 대칭이다. 하위 구조를 재귀적으로 채우지 않는 것도 같은 이유다.
     *
     * <p>존재만 확인하므로 헤더만 읽는다 — 파티션을 통째로 읽으면 하위 조직의 직원 줄까지 읽는다(조직 멤버 PATCH 설계 §1.4).
     */
    private Mono<Set<DirectoryGroup>> expandWithReferencedGroups(Set<DirectoryGroup> groups) {
        Set<String> knownIds = new LinkedHashSet<>();
        groups.forEach(g -> knownIds.add(g.id()));

        Set<String> missingIds = new LinkedHashSet<>();
        for (DirectoryGroup g : groups) {
            for (MemberRef member : g.members()) {
                if (member.type() == MemberType.GROUP && !knownIds.contains(member.id())) {
                    missingIds.add(member.id());
                }
            }
        }
        if (missingIds.isEmpty()) {
            return Mono.just(groups);
        }
        return Flux.fromIterable(missingIds)
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .map(header -> new DirectoryGroup(header.id(), header.externalId(), header.displayName(), Set.of()))
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add)
                .map(loaded -> {
                    Set<DirectoryGroup> merged = new LinkedHashSet<>(groups);
                    merged.addAll(loaded);
                    return merged;
                });
    }

    /**
     * 조직들의 멤버 유저를 현재상태에서 <b>묶어</b> 읽는다(설계 2026-10-03 §3.3). {@code overrides} 에 있는 유저는 저장된 값 대신 그 값을 쓴다 — 아직 저장 전인
     * 변경 후 상태를 반영하기 위해서다.
     */
    private Mono<Map<String, DirectoryUser>> loadMemberUsers(Set<DirectoryGroup> groups, Set<DirectoryUser> overrides) {
        Map<String, DirectoryUser> overrideById = byUserId(overrides);
        Set<String> 읽을것 = new LinkedHashSet<>();
        for (DirectoryGroup group : groups) {
            for (MemberRef member : group.members()) {
                if (member.type() == MemberType.USER && !overrideById.containsKey(member.id())) {
                    읽을것.add(member.id());
                }
            }
        }
        Mono<Map<String, DirectoryUser>> 읽은것 = 읽을것.isEmpty()
                ? Mono.just(Map.of())
                : state.findUsers(읽을것).collectMap(DirectoryUser::id);
        return 읽은것.map(read -> {
            Map<String, DirectoryUser> users = new LinkedHashMap<>(read);
            users.putAll(overrideById);
            return users;
        });
    }

    private static Map<String, DirectoryGroup> byId(Set<DirectoryGroup> groups) {
        Map<String, DirectoryGroup> map = new LinkedHashMap<>();
        groups.forEach(group -> map.put(group.id(), group));
        return map;
    }

    private static Map<String, DirectoryUser> byUserId(Set<DirectoryUser> users) {
        Map<String, DirectoryUser> map = new LinkedHashMap<>();
        users.forEach(user -> map.put(user.id(), user));
        return map;
    }
}
