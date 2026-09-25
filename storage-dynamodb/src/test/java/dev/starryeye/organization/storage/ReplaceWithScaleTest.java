package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.fixture.ScaleTest;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 직원 10만 명의 전체 교체 (GSI 설계 §6). 실제 운영 규모가 10만 명 이상이라, 매 동기화가 GSI1 한 파티션키로 몰아
 * 쓰던 양이 변경 수로 줄었는지를 PutItem 수로 단정한다. 변경은 저장소 한 곳이라 LDAP 서버 없이 저장소 수준에서 본다.
 */
@ScaleTest
class ReplaceWithScaleTest extends DynamoDbTestSupport {

    private static final int 전체 = 100_000;

    private static DirectorySnapshot 조직도(Map<String, DirectoryUser> 바뀐직원) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        for (int i = 0; i < 전체; i++) {
            String id = "u%06d".formatted(i);
            users.put(id, new DirectoryUser(id, "ext-" + id, id, "직원 " + i, null, true));
        }
        users.putAll(바뀐직원);
        Map<String, DirectoryGroup> groups = new LinkedHashMap<>();
        for (int g = 0; g < 100; g++) {
            Set<MemberRef> members = new LinkedHashSet<>();
            for (int m = 0; m < 10; m++) {
                members.add(MemberRef.user("u%06d".formatted(g * 10 + m)));
            }
            groups.put("G%03d".formatted(g), new DirectoryGroup("G%03d".formatted(g), "g" + g, "조직 " + g, members));
        }
        return new DirectorySnapshot(users, groups);
    }

    @Test
    @DisplayName("10만 명을 적재한 뒤 같은 조직도는 PutItem 0번, 100명을 바꾸면 PutItem 100번이다")
    void 바뀐_만큼만_쓴다() {
        // given — 스냅샷 구성은 시계에 넣지 않는다. replaceWith 만 잰다
        WriteCounter counter = new WriteCounter();
        var repository = new DynamoDbDirectoryStateRepository(counter.wrap(client), properties, Clock.systemUTC());
        DirectorySnapshot 최초 = 조직도(Map.of());
        long 시작 = System.currentTimeMillis();
        repository.replaceWith(최초).block(Duration.ofMinutes(30));
        long 적재 = System.currentTimeMillis() - 시작;
        long 적재쓰기 = counter.puts();

        // when — 같은 조직도
        DirectorySnapshot 그대로 = 조직도(Map.of());
        counter.reset();
        시작 = System.currentTimeMillis();
        repository.replaceWith(그대로).block(Duration.ofMinutes(30));
        long 같음 = System.currentTimeMillis() - 시작;
        long 같음쓰기 = counter.puts();

        // when — 100명만 바뀜
        Map<String, DirectoryUser> 바뀐직원 = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            String id = "u%06d".formatted(i * 1_000);
            바뀐직원.put(id, new DirectoryUser(id, "ext-" + id, id, "이름 바뀜 " + i, null, true));
        }
        DirectorySnapshot 일부변경 = 조직도(바뀐직원);
        counter.reset();
        시작 = System.currentTimeMillis();
        repository.replaceWith(일부변경).block(Duration.ofMinutes(30));
        long 일부 = System.currentTimeMillis() - 시작;
        long 일부쓰기 = counter.puts();

        // then — 최초 적재는 직원 10만 + 조직 META 100 + 소속 줄 1,000 + 멤버 줄 1,000 = 102,100건
        System.out.printf("적재: %,dms PutItem %,d / 같은 조직도: %,dms PutItem %,d / 100명 변경: %,dms PutItem %,d%n",
                적재, 적재쓰기, 같음, 같음쓰기, 일부, 일부쓰기);
        assertThat(적재쓰기).isEqualTo(102_100);
        assertThat(같음쓰기).isZero();
        assertThat(일부쓰기).isEqualTo(100);
    }
}
