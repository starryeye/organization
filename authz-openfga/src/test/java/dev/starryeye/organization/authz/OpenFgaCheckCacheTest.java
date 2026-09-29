package dev.starryeye.organization.authz;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenFGA Check 캐시를 켠 채로 쓰기 경로의 기준선을 본다(점검 C8). 캐시는 기본 꺼짐이라 다른 테스트는 이 문제를 드러낼 수 없다.
 * "없음"을 한 번 물어 캐시에 남긴 뒤 튜플을 쓰고 곧바로 다시 물었을 때 "있음"이어야 한다 — 캐시된 "없음"을 받으면 다음 연산이
 * 이미 쓴 튜플을 없다고 보고 지우지 않는다.
 */
@Testcontainers
class OpenFgaCheckCacheTest {

    @Container
    static final GenericContainer<?> CACHED_OPENFGA = new GenericContainer<>(
            DockerImageName.parse("openfga/openfga:v1.10.2"))
            .withCommand("run")
            .withEnv("OPENFGA_DATASTORE_ENGINE", "memory")
            .withEnv("OPENFGA_CHECK_QUERY_CACHE_ENABLED", "true")
            .withEnv("OPENFGA_CHECK_QUERY_CACHE_TTL", "60s")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private StoreBootstrapper bootstrapper;
    private OpenFgaRelationTupleWriter writer;
    private OpenFgaRelationTupleChecker checker;

    @BeforeEach
    void 캐시를_켠_OpenFGA를_준비한다() {
        OpenFgaProperties properties = new OpenFgaProperties();
        properties.setApiUrl("http://" + CACHED_OPENFGA.getHost() + ":" + CACHED_OPENFGA.getMappedPort(8080));
        properties.setStoreName("cache-test-" + UUID.randomUUID());
        properties.setWriteBatchSize(100);
        properties.setMaxRetries(3);
        bootstrapper = new StoreBootstrapper(properties);
        bootstrapper.resolveStore().block();
        writer = new OpenFgaRelationTupleWriter(bootstrapper, properties);
        checker = new OpenFgaRelationTupleChecker(bootstrapper);
    }

    @Test
    @DisplayName("BatchCheck 기준선은 캐시를 우회한다 — 없음을 물은 뒤 쓴 튜플을 곧바로 다시 물으면 있음이다")
    void BatchCheck는_캐시를_우회한다() {
        // given — 없는 튜플을 물어 캐시에 "없음"을 남긴다
        var 튜플 = RelationTuple.directMember("kim", "DEV002");
        assertThat(checker.existing(Set.of(튜플)).block()).isEmpty();

        // when — 튜플을 쓰고 곧바로 다시 묻는다
        writer.apply(TupleDelta.writeOnly(Set.of(튜플))).block();
        var 다시 = checker.existing(Set.of(튜플)).block();

        // then
        assertThat(다시).containsExactly(튜플);
    }

    @Test
    @DisplayName("단건 Check 도 캐시를 우회한다 — 관리 조회의 '실제' 칸이 거짓 어긋남을 보이지 않는다")
    void Check도_캐시를_우회한다() {
        // given
        var 물음 = new RelationTuple("user:lee", "member", "group:DEV002");
        assertThat(checker.check(물음).block()).isFalse();

        // when
        writer.apply(TupleDelta.writeOnly(Set.of(RelationTuple.directMember("lee", "DEV002")))).block();
        var 다시 = checker.check(물음).block();

        // then
        assertThat(다시).isTrue();
    }
}
