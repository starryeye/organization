package dev.starryeye.organization.ldap.app;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.sdk.LDAPException;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.RelationTupleChecker;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 불변 id e2e 두 클래스({@link LdapImmutableIdEndToEndTest}, {@link LdapDitImmutableIdEndToEndTest})가 함께 쓰는 도우미.
 * 두 전략이 같은 단언을 쓰므로 한 곳에 둔다 — 한쪽만 고쳐져 어긋나지 않게.
 */
final class ImmutableIdEndToEndSupport {

    private static final Duration 대기 = Duration.ofSeconds(30);

    private ImmutableIdEndToEndSupport() {
    }

    /** 서버가 엔트리마다 만들어 개명에도 유지하는 운영 속성 {@code entryUUID}. */
    static String entryUUID(InMemoryDirectoryServer server, String dn) {
        try {
            var entry = server.getEntry(dn, "entryUUID");
            assertThat(entry).as("엔트리 %s", dn).isNotNull();
            String 값 = entry.getAttributeValue("entryUUID");
            assertThat(값).as("%s 의 entryUUID", dn).isNotBlank();
            return 값;
        } catch (LDAPException e) {
            throw new IllegalStateException("entryUUID 조회 실패: " + dn, e);
        }
    }

    /** {@code user:U} 가 {@code group:G} 의 member 인가(롤업 포함). Check 가 비어 끝나면 거짓이 아니라 실패다. */
    static boolean 소속인가(RelationTupleChecker checker, String userId, String groupId) {
        Boolean 결과 = checker.check(RelationTuple.member(userId, groupId)).block(대기);
        assertThat(결과).as("Check 가 답 없이 끝났다: user:%s member group:%s", userId, groupId).isNotNull();
        return 결과;
    }

    /** 가장 최근 튜플 스냅샷의 튜플. 스냅샷이 없으면 첫 동기화가 아직 안 돈 것이다 — 순서 의존(Order 1) 테스트가 그것을 알려 준다. */
    static Set<RelationTuple> 기준선을_읽는다(TupleSnapshotRepository snapshots) {
        var 스냅샷 = snapshots.findLatest().block(대기);
        assertThat(스냅샷).as("튜플 스냅샷이 없다 — 첫 동기화(Order 1)가 먼저 성공해야 한다").isNotNull();
        return 스냅샷.tuples();
    }

    /**
     * 이 픽스처의 개명이 <b>이름 기반 id 였다면</b> 삭제 가드에 걸리는 크기인지 본다. 이름 기반이면 개명은 그 id 를 언급하는 튜플을 모두 지우고 새로
     * 쓰는 일이다 — 기준선에서 {@code 개명하는_참조} 를 언급하는 튜플을 세어, 앱이 쓰는 가드에게 그만큼의 삭제를 중단시키는지 묻는다.
     * 임계 비율과 최소 기준선은 가드에서 오므로 여기에 다시 적지 않는다. 가드가 적용되지 않는 작은 기준선이면 이 단언이 실패한다.
     *
     * @param 개명하는_참조 튜플에 쓰이는 참조({@link RelationTuple#groupRef}, {@link RelationTuple#userRef})
     * @param 설명         실패 메시지에 쓸 개명 대상 이름
     */
    static void 이름_기반이면_삭제_가드에_걸리는_픽스처다(DeletionGuard 가드, Set<RelationTuple> 기준선, String 설명,
                                      String... 개명하는_참조) {
        long 지울_수 = 기준선.stream()
                .filter(tuple -> Arrays.stream(개명하는_참조).anyMatch(tuple::mentions))
                .count();
        assertThat(가드.evaluate((int) 지울_수, 기준선.size(), "기준 스냅샷").aborted())
                .as("이름 기반이면 %s 개명으로 지울 튜플 %d건 / 기준선 %d건 — 삭제 가드의 임계를 넘는 픽스처여야 한다",
                        설명, 지울_수, 기준선.size())
                .isTrue();
    }

    /** 개명만 있었던 동기화의 결과 — 가드에 걸리지 않고 성공했으며 튜플은 하나도 쓰거나 지우지 않는다. */
    static void 튜플을_건드리지_않고_성공했다(WebTestClient.BodyContentSpec 동기화) {
        동기화.jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.deletedCount").isEqualTo(0)
                .jsonPath("$.writtenCount").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("변경 없음");
    }
}
