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

    private static final String TUPLE_SEPARATOR = "|";

    /**
     * {@link Instant#toString()} 은 나노초가 정확히 0 이면 소수점 이하를 아예 생략하고,
     * 그렇지 않으면 3/6/9 자리를 가변적으로 출력한다. 이 가변폭 때문에 같은 초 안에서
     * 정렬 순서가 뒤집힐 수 있어, 정렬키에는 항상 밀리초 3자리를 고정 출력하는
     * 포맷터를 쓴다.
     */
    private static final DateTimeFormatter SYNC_RUN_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Keys() {
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
        return "SYNCRUN#" + YearMonth.from(at.atZone(ZoneOffset.UTC));
    }

    public static String syncRunSk(Instant startedAt, String runId) {
        return SYNC_RUN_TIME.format(startedAt) + "#" + runId;
    }
}
