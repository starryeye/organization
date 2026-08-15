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

    private static final String TUPLE_PREFIX = "TUPLE#";
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
        return "USER#" + userId;
    }

    public static String groupPk(String groupId) {
        return "GROUP#" + groupId;
    }

    public static String memberSk(MemberRef ref) {
        return "MEMBER#" + ref.type().name() + "#" + ref.id();
    }

    /** 멤버십 아이템의 GSI 파티션키. 정렬키와 같은 문자열이라 역참조가 성립한다. */
    public static String memberGsi1Pk(MemberRef ref) {
        return memberSk(ref);
    }

    public static MemberRef parseMemberSk(String sk) {
        String[] parts = sk.split("#", 3);
        return new MemberRef(MemberType.valueOf(parts[1]), parts[2]);
    }

    public static String snapshotPk(String snapshotId) {
        return "SNAPSHOT#" + snapshotId;
    }

    public static String tupleSk(RelationTuple tuple) {
        return TUPLE_PREFIX + tuple.user() + TUPLE_SEPARATOR + tuple.relation()
                + TUPLE_SEPARATOR + tuple.object();
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
