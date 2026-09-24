package dev.starryeye.organization.authz.fixture;

import dev.starryeye.organization.authz.StoreBootstrapper;
import dev.starryeye.organization.core.fixture.ChartExpectation;
import dev.starryeye.organization.core.fixture.OrgChart;
import dev.starryeye.organization.core.fixture.RollupSampling;
import dev.starryeye.organization.core.fixture.SyncVerifier;
import dev.starryeye.organization.core.fixture.VerificationResult;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleChecker;

import java.time.Duration;

/**
 * 규모 테스트들이 한 벌씩 들고 있던 검증 도우미.
 *
 * <p><b>두 경로로 묻는 이유.</b> 하네스는 {@code RelationTupleChecker} 포트를 타고, 프로브는
 * OpenFGA SDK 를 그대로 쓴다. 어댑터에 결함이 있으면 하네스는 그 결함에 같이 속는다 — 같은
 * 사실을 서로 다른 경로로 두 번 물어 답이 갈리면, 갈렸다는 것 자체가 결함이다.
 */
public final class ScaleVerification {

    private ScaleVerification() {
    }

    /** 하네스로만 묻는다. 조회 API·시간 측정처럼 교차 검증이 목적이 아닌 클래스용. */
    public static void 하네스로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                 OrgChart 기대) {
        확인한다("하네스", new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10)));
    }

    public static void 두_경로로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                  StoreBootstrapper bootstrapper, OrgChart 기대) {
        두_경로로_검증한다(state, checker, bootstrapper, ChartExpectation.of(기대));
    }

    public static void 두_경로로_검증한다(DirectoryStateRepository state, RelationTupleChecker checker,
                                  StoreBootstrapper bootstrapper, ChartExpectation 기대) {
        확인한다("하네스", new SyncVerifier(state, checker).검증한다(기대).block(Duration.ofMinutes(10)));
        확인한다("OpenFGA 직접 질의", new OpenFgaProbe(bootstrapper)
                .직접_대조한다(기대, RollupSampling.기본값().표본을_고른다(기대.chart())));
    }

    public static boolean 성립하는가(RelationTupleChecker checker, RelationTuple tuple) {
        return Boolean.TRUE.equals(checker.check(tuple).block(Duration.ofSeconds(30)));
    }

    private static void 확인한다(String 경로, VerificationResult 결과) {
        if (결과 == null) {
            throw new AssertionError(경로 + " 가 결과를 돌려주지 않았습니다");
        }
        if (결과.어긋났는가()) {
            throw new AssertionError(경로 + ": " + 결과.요약());
        }
    }
}
