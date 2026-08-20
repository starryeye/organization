package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 단일 테이블 설계의 PK/SK/GSI 키를 만들고 파싱한다.
 * 키 규칙이 여기 한 곳에만 있어야 저장소 구현들이 어긋나지 않는다.
 */
public final class Keys {

    public static final String PK = "PK";
    public static final String SK = "SK";
    public static final String GSI1PK = "GSI1PK";
    public static final String GSI1SK = "GSI1SK";
    public static final String GSI1 = "GSI1";

    public static final String GSI2 = "GSI2";

    /**
     * GSI2 는 <b>자기만의 키 속성을 만들지 않는다</b>. 파티션키로 {@link #GSI1PK} 를 그대로
     * 쓰고, 정렬키로 아이템이 이미 갖고 있는 {@code displayName} 속성을 그대로 쓴다.
     *
     * <p><b>왜 전용 속성(GSI2PK/GSI2SK)을 쓰지 않나.</b> DynamoDB 는 인덱스의 키 속성을
     * <b>전부</b> 가진 아이템만 그 인덱스에 싣는다. 전용 속성을 새로 도입하면 그 속성을 쓰기
     * 시작하기 <b>전에</b> 저장된 아이템은 인덱스 추가 시점의 백필에서도 통째로 빠진다 —
     * 즉 이 인덱스가 배포되는 순간 기존 직원 전원이 표시명 검색에서 사라진다. app-ldap 은
     * 주기적 전체 동기화가 전원을 다시 써서 저절로 나아지지만, <b>app-scim 에는 전량 재기록
     * 경로 자체가 없어</b>({@code FullSyncUseCase} 도 {@code RebuildUseCase} 도 배선돼 있지
     * 않다) 영영 회복되지 않는다. 이미 모든 아이템이 갖고 있는 속성을 키로 삼으면 DynamoDB
     * 자신의 백필이 기존 아이템을 그대로 실어 주고, 마이그레이션이 필요 없다는 설계의 주장이
     * 실제로 참이 된다.
     *
     * <p>대가는 쓰기 증폭이다. {@code GSI1PK} 파티션에는 {@link #GROUP_INDEX}(조직 META)도
     * 있고 그쪽도 {@code displayName} 을 가지므로 조직 아이템이 GSI2 에 함께 실린다. 조회는
     * 파티션으로 갈리므로 표시명 직원 검색은 {@link #USER_INDEX} 파티션만 본다.
     *
     * <p>{@code displayName} 이 없는 직원이 표시명 검색에 안 잡히는 성질은 그대로다 —
     * 정렬키 속성이 없으면 여전히 인덱스에 실리지 않는다.
     */
    public static final String GSI2PK = GSI1PK;
    /** @see #GSI2PK — 아이템 속성 {@code displayName} 그 자체다. */
    public static final String GSI2SK = "displayName";

    public static final String META = "META";

    /** 전체 직원 열거용 GSI 파티션 */
    public static final String USER_INDEX = "USER_INDEX";
    /** 전체 조직 열거 + 조직명 검색용 GSI 파티션 */
    public static final String GROUP_INDEX = "GROUP_INDEX";
    /** 스냅샷 목록 조회용 GSI 파티션 */
    public static final String SNAPSHOT_INDEX = "SNAPSHOT_INDEX";

    public static final String SNAPSHOT_POINTER = "SNAPSHOT_POINTER";
    public static final String LATEST = "LATEST";

    /** 파티션/정렬키 접두사. 형식이 바뀌어도 이 다섯 상수만 고치면 되도록 여기 한 곳에 모은다. */
    public static final String USER_PREFIX = "USER#";
    public static final String GROUP_PREFIX = "GROUP#";
    public static final String MEMBER_PREFIX = "MEMBER#";
    public static final String SNAPSHOT_PREFIX = "SNAPSHOT#";
    public static final String TUPLE_PREFIX = "TUPLE#";
    public static final String SYNCRUN_PREFIX = "SYNCRUN#";

    private static final String TUPLE_SEPARATOR = "|";

    /**
     * {@link Instant#toString()} 은 나노초가 정확히 0 이면 소수점 이하를 아예 생략하고,
     * 그렇지 않으면 3/6/9 자리를 가변적으로 출력한다. 이 가변폭 때문에 같은 초 안에서
     * 정렬 순서가 뒤집힐 수 있어, 정렬키에는 항상 밀리초 3자리를 고정 출력하는
     * 포맷터를 쓴다.
     */
    private static final DateTimeFormatter SORTABLE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Keys() {
    }

    /**
     * 정렬키(혹은 GSI 정렬키)에 시각을 넣을 때 반드시 이 메서드를 거친다.
     * {@link Instant#toString()} 을 직접 쓰면 나노초 자릿수가 가변이라 문자열
     * 정렬 순서가 실제 시각 순서와 어긋날 수 있다 — 예를 들어 "...T03:00:00Z" 는
     * 소수점이 없어 "...T03:00:00.500Z" 보다 사전식으로 뒤에 오지만('.' &lt; 'Z'),
     * 실제로는 더 이른 시각이다. 이 메서드는 항상 밀리초 3자리를 고정 출력해
     * 그런 역전을 막는다. 원본 정밀도가 필요한 속성값(예: createdAt 속성)에는
     * 여전히 {@link Instant#toString()} 을 그대로 쓴다 — 정밀도를 잃으면 안 된다.
     */
    public static String sortableTimestamp(Instant at) {
        return SORTABLE_TIMESTAMP.format(at);
    }

    public static String userPk(String userId) {
        return USER_PREFIX + userId;
    }

    /** {@link #userPk} 로 만든 파티션키에서 직원 아이디만 되돌린다. */
    public static String parseUserPk(String pk) {
        return pk.substring(USER_PREFIX.length());
    }

    public static String groupPk(String groupId) {
        return GROUP_PREFIX + groupId;
    }

    /** {@link #groupPk} 로 만든 파티션키에서 조직코드만 되돌린다. */
    public static String parseGroupPk(String pk) {
        return pk.substring(GROUP_PREFIX.length());
    }

    public static String memberSk(MemberRef ref) {
        return MEMBER_PREFIX + ref.type().name() + "#" + ref.id();
    }

    /** 멤버십 아이템의 GSI 파티션키. 정렬키와 같은 문자열이라 역참조가 성립한다. */
    public static String memberGsi1Pk(MemberRef ref) {
        return memberSk(ref);
    }

    /** 정렬키가 {@link #memberSk} 로 만들어진 멤버십 아이템인지 판별한다. */
    public static boolean isMemberSk(String sk) {
        return sk.startsWith(MEMBER_PREFIX);
    }

    public static MemberRef parseMemberSk(String sk) {
        String[] parts = sk.split("#", 3);
        return new MemberRef(MemberType.valueOf(parts[1]), parts[2]);
    }

    public static String snapshotPk(String snapshotId) {
        return SNAPSHOT_PREFIX + snapshotId;
    }

    /** {@link #snapshotPk} 로 만든 파티션키에서 스냅샷 아이디만 되돌린다. */
    public static String parseSnapshotPk(String pk) {
        return pk.substring(SNAPSHOT_PREFIX.length());
    }

    public static String tupleSk(RelationTuple tuple) {
        return TUPLE_PREFIX + tuple.user() + TUPLE_SEPARATOR + tuple.relation()
                + TUPLE_SEPARATOR + tuple.object();
    }

    /** 정렬키가 {@link #tupleSk} 로 만들어진 튜플 아이템인지 판별한다. */
    public static boolean isTupleSk(String sk) {
        return sk.startsWith(TUPLE_PREFIX);
    }

    public static RelationTuple parseTupleSk(String sk) {
        String body = sk.substring(TUPLE_PREFIX.length());
        String[] parts = body.split("\\" + TUPLE_SEPARATOR, 3);
        return new RelationTuple(parts[0], parts[1], parts[2]);
    }

    public static String syncRunPk(Instant at) {
        return syncRunPk(YearMonth.from(at.atZone(ZoneOffset.UTC)));
    }

    /** {@link #findRecent} 가 월 파티션을 직접 조회할 때 쓴다. */
    public static String syncRunPk(YearMonth month) {
        return SYNCRUN_PREFIX + month;
    }

    public static String syncRunSk(Instant startedAt, String runId) {
        return sortableTimestamp(startedAt) + "#" + runId;
    }
}
