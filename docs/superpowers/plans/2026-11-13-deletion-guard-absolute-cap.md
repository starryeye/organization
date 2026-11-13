# 삭제 가드의 절대 수 상한 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LDAP 삭제 가드가 지울 튜플 줄 수에 절대 상한(`sync.deletion-guard.max-deletes`, 기본 5000)을 비율과 OR 로 보게 하고, 넘은 한도를 문구에 적으며, README 와 온보딩을 그 동작대로 고친다.

**Architecture:** 판정은 `DeletionGuard.evaluate(int, int, String)` 한 곳에서만 바뀐다 — 비교 경로·장부 맞추기·재적재·비활성 회수 재판정이 모두 이 함수를 부르므로 G4 가 저절로 지켜진다. `DeletionGuardPolicy` 가 구성 요소 `maxDeletes` 를 얻고, app-ldap 의 `SyncProperties` → `UseCaseConfig.deletionGuard` 가 그 값을 넘긴다. `GuardDecision`·두 호출 자리(`FullSyncUseCase`·`LedgerAlignment`)·SCIM 은 바꾸지 않는다.

**Tech Stack:** Java 17, Spring Boot(WebFlux) 속성 바인딩, Lombok, JUnit 5 + AssertJ, Gradle 멀티 모듈(core, app-ldap).

**Spec:** `docs/superpowers/specs/2026-11-13-deletion-guard-absolute-cap-design.md`

## Global Constraints

- 결정은 설계 §2 의 G1~G7 이다. 설계와 이 계획이 어긋나면 설계가 이긴다.
- 지울 줄 수가 상한을 **넘으면**(같으면 통과) 걸린다. 비율과 상한 가운데 하나라도 넘으면 걸린다(G1).
- 기본 상한은 `5000`, 켜짐. `0` 이하면 상한을 보지 않는다(G2).
- 상한은 `min-baseline` 과 상관없이 본다. `enabled=false` 면 상한도 보지 않는다(G3).
- 비율은 기준 수 ≥ `min-baseline` 일 때만 "넘은 것"이 된다(G5).
- 문구 틀(G5, 그대로 쓴다): `삭제 대상 %d건(%s %d건의 %.1f%%)이 <넘은 것>을 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요` 의 `<넘은 것>을` 자리 —
  - 비율만: `임계치 30.0%를` (지금 문구와 한 글자도 다르지 않다)
  - 상한만: `상한 5000건을`
  - 둘 다: `임계치 30.0%와 상한 5000건을`
- 새 지표를 두지 않는다(G6). `force=true`·`FORCED` 는 지금처럼 가드 전체를 건너뛴다(G4).
- 설정 키는 `sync.deletion-guard.max-deletes` 이고 app-ldap 의 `application.yml`·`application-test.yml` 에 `5000` 으로 적는다(G7).
- 서브에이전트는 모듈 시험만 돌린다: `./gradlew :core:test …` 또는 `./gradlew :app-ldap:test --tests <클래스>`. Gradle 은 한 번에 하나만 돈다. 전체 `cleanTest test`·`cleanScaleTest scaleTest` 는 메인 세션이 머지 전에 돌린다.
- git 은 꾸밈 없는 단일 명령으로 쓴다(`&&`·`$(...)` 금지 — 작업 트리 가드가 거절한다). `git stash` 금지.
- 커밋 메시지는 `<종류>: <한국어 요약> (가드 상한)` 이고, 끝 줄은 `Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>` 이다.
- 식별자·자바독·주석은 이 저장소처럼 한국어로 쓴다. 자바독에 근거 설계를 `설계 2026-11-13 G<n>` 꼴로 적는다.

## Review Focus

1. **큰 개편과 퇴사가 같은 회차** — 상한으로 걸렸지만 비활성 부분이 상한 아래면 퇴사자 회수는 나가고(`ABORTED`), 문구 " / " 뒤가 상한 문구다. → Task 1 의 `상한으로_걸려도_비활성_회수가_상한_아래면_그것만_반영한다`.
2. **운영자가 `max-deletes` 를 음수로 준 배포** — `0` 과 같이 "보지 않음"이다(예외도, 전부 막힘도 아니다). → Task 1 의 `상한이_0_이하면_상한을_보지_않는다`.
3. **스프링 문맥(e2e 시험·운영)의 값은 yml 에서 온다** — 두 yml 의 값이 속성 클래스 기본값과 어긋나면 시험과 운영이 다른 가드를 본다. → Task 1 의 `두_yml_의_가드_상한은_속성_기본값과_같다`.
4. **작은 기준(< `min-baseline`)에서 비율은 높지만 상한만 넘는 그림** — 문구에 "임계치"가 없다(비율은 넘은 것이 아니다). → Task 1 의 `기준이_최소_기준_미만이어도_상한은_본다`.
5. **상한을 넘는 강제 실행** — `FORCED` 회차는 상한을 넘어도 지운다. → Task 1 의 `강제면_비교_경로가_상한을_넘어도_지운다`.

---

### Task 1: 판정·문구·결선 (G1~G7 의 코드)

레코드 모양이 바뀌면 app-ldap 의 `UseCaseConfig` 가 함께 바뀌어야 컴파일되므로, core 판정과 app-ldap 결선을 한 과제·한 커밋으로 둔다.

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuardPolicy.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java` — `evaluate(int, int, String)`, 클래스 자바독, `evaluate(Set, Set, int, String)` 자바독
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncProperties.java` — `DeletionGuardConfig`
- Modify: `app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java:39-44` — `deletionGuard`
- Modify: `app-ldap/src/main/resources/application.yml:32-35`, `app-ldap/src/test/resources/application-test.yml:25-28`
- Test: `core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncPropertiesTest.java`

**Interfaces:**
- Produces:
  - `record DeletionGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline, int maxDeletes)`; `defaults()` = `(true, 0.3, 10, 5000)`. 3인자 생성자는 두지 않는다(두면 상한이 조용히 꺼진 정책이 생길 길이 남는다).
  - `SyncProperties.DeletionGuardConfig#getMaxDeletes()` / `setMaxDeletes(int)` — 기본 `5000`.
  - `FullSyncUseCaseTest` 의 `private FullSyncUseCase 유스케이스(DeletionGuardPolicy 정책)`; 인자 없는 `유스케이스()` 는 `유스케이스(DeletionGuardPolicy.defaults())` 를 부른다.

- [ ] **Step 1: 레코드 모양을 바꾼다 (판정은 아직 그대로)**

`DeletionGuardPolicy` 에 넷째 구성 요소 `int maxDeletes` 를 더하고 `defaults()` 를 `(true, 0.3, 10, 5000)` 으로. 자바독에 `@param maxDeletes 한 회차에 지울 줄 수의 상한. 이 값을 <b>초과</b>하면 비율과 상관없이 중단한다. 0 이하면 보지 않는다. min-baseline 과 상관없이 본다(설계 2026-11-13 G1~G3)`.
`DeletionGuardTest` 의 가드 끔 시험(약 99행)은 `new DeletionGuardPolicy(false, 0.3, 10, 5)` 로 바꾸고 DisplayName 을 `"가드를 비활성화하면 전건 삭제도 그대로 진행한다 — 상한도 보지 않는다"` 로 바꾼다(설계 §11: 가드 끔 시험에 상한을 더해 둔다).

- [ ] **Step 2: `DeletionGuardTest` 에 실패하는 시험을 더한다**

새 절 `// ---------- 절대 수 상한 (설계 2026-11-13 G1~G5) ----------` 아래에. 기존 도우미 `튜플들`·`앞에서` 를 쓴다. 정책 `상한5 = new DeletionGuardPolicy(true, 0.3, 10, 5)`.

```java
@Test
@DisplayName("지울 줄이 상한과 같으면 통과하고, 하나 넘으면 비율이 임계치 아래여도 중단한다")
void 상한과_같으면_통과하고_하나_넘으면_중단한다() {
    // given — 기준 100건, 상한 5
    var guard = new DeletionGuard(new DeletionGuardPolicy(true, 0.3, 10, 5));
    var baseline = 튜플들(100);
    // when
    var 같음 = guard.evaluate(앞에서(baseline, 5), Set.of(), baseline.size(), "기준 스냅샷");
    var 넘음 = guard.evaluate(앞에서(baseline, 6), Set.of(), baseline.size(), "기준 스냅샷");
    // then
    assertThat(같음.aborted()).isFalse();
    assertThat(넘음.aborted()).isTrue();
    assertThat(넘음.message()).isEqualTo(
            "삭제 대상 6건(기준 스냅샷 100건의 6.0%)이 상한 5건을 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요");
}
```

나머지 시험(이름 · 준비 · 단언):

| 시험 | 준비 | 단언 |
|---|---|---|
| `기준이_최소_기준_미만이어도_상한은_본다` | 정책 `(true, 0.3, 10, 3)`; `evaluate(4, 9, "훑은 장부")`, `evaluate(3, 9, "훑은 장부")` | 앞은 `aborted()` 참, 문구가 `contains("훑은 장부 9건").contains("상한 3건").doesNotContain("임계치")`; 뒤는 `aborted()` 거짓 |
| `상한이_0_이하면_상한을_보지_않는다` | 정책 `(true, 0.3, 10, 0)` 과 `(true, 0.3, 10, -1)`; 각각 `evaluate(20, 100, "기준 스냅샷")` | 둘 다 `aborted()` 거짓 |
| `비율과_상한을_모두_넘으면_문구에_둘_다_적는다` | 정책 상한 5; 기준 100건 중 40건 | 문구 `isEqualTo("삭제 대상 40건(기준 스냅샷 100건의 40.0%)이 임계치 30.0%와 상한 5건을 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요")` |
| `비율만_넘으면_문구가_지금과_같다` | `defaults()`; 기준 100건 중 68건 | 문구 `isEqualTo("삭제 대상 68건(기준 스냅샷 100건의 68.0%)이 임계치 30.0%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요")` |
| `비활성_회수의_재판정도_상한을_본다` | 정책 상한 5; 기준 100건, 지울 `앞에서(baseline, 20)`(20% — 비율 아래, 상한 위), 비활성 `앞에서(baseline, 8)`(8% — 비율 아래, 상한 위) | `aborted()` 참, `partial()` 거짓, `revocationsOnly()` 비어 있음, 문구 `contains("삭제 대상 20건").contains("상한 5건")` |
| `비활성_부분이_상한_아래면_그것만_반영한다` | 정책 상한 5; 지울 `앞에서(baseline, 20)`, 비활성 `앞에서(baseline, 4)` | `aborted()` 참, `partial()` 참, `revocationsOnly()` 가 `containsExactlyInAnyOrderElementsOf(앞에서(baseline, 4))`, 문구 `contains("삭제 대상 20건").contains("상한 5건")` |
| `기본_정책의_상한은_5000줄이다` | `defaults()`; `evaluate(5000, 200_000, "기준 스냅샷")`, `evaluate(5001, 200_000, "기준 스냅샷")` | `defaults()` 가 `isEqualTo(new DeletionGuardPolicy(true, 0.3, 10, 5000))`; 앞은 진행, 뒤는 걸림이고 문구 `contains("상한 5000건").doesNotContain("임계치")` |

각 시험 위에 "이 시험을 깨뜨릴 운영 코드 변경"을 설계 §4.1 의 표대로 한 줄 주석으로 적는다(`기본_정책의_상한은_5000줄이다` 는 "`defaults()` 의 상한을 0 으로 두기").

- [ ] **Step 3: `FullSyncUseCaseTest` 에 경로 시험을 더한다**

`유스케이스()` 를 `유스케이스(DeletionGuardPolicy 정책)` 로 나눈다(Interfaces). 새 절 `// ---------- 절대 수 상한 (설계 2026-11-13 G4) ----------` 아래 네 시험. 각 시험은 `useCase = 유스케이스(new DeletionGuardPolicy(true, 0.3, 10, <상한>));` 으로 시작한다.

| 시험 | 준비 | 단언 |
|---|---|---|
| `비교_경로는_비율_아래라도_상한을_넘으면_중단한다` | 상한 5. `snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 소속튜플(100, "DEV002")))`, `source.willReturn(조직도(직원들(0, 90), "DEV002"))` — 지울 10줄(10%), `SCHEDULED` | `ABORTED`; 문구 `contains("삭제 대상 10건(기준 스냅샷 100건의 10.0%)이 상한 5건을 초과했습니다").doesNotContain("임계치")`; `writer.appliedDeltas` 비어 있음; `snapshots.saved` 크기 1 |
| `강제면_비교_경로가_상한을_넘어도_지운다` | 같은 준비, `FORCED` | `SUCCEEDED`; `deletedCount()` 10 |
| `상한으로_걸려도_비활성_회수가_상한_아래면_그것만_반영한다` | 상한 5. 같은 기준 100줄, `source.willReturn(조직도(직원들(2, 90), Set.of("user0", "user1"), "DEV002"))` — 지울 12줄(12%) 중 비활성 2줄 | `ABORTED`; 문구 `contains("비활성 회수 2건만 반영하고 나머지 지우기 10건·쓰기 0건은 보류").contains(" / 삭제 대상 12건(기준 스냅샷 100건의 12.0%)이 상한 5건을 초과했습니다")`; `deletedCount()` 2; `writer.appliedDeltas` 크기 1, 그 `toDelete()` 가 `user0`·`user1` 의 `RelationTuple.directMember(…, "DEV002")` 둘과 `containsExactlyInAnyOrder` |
| `장부_맞추기도_상한을_본다` | 상한 3. 저장한 스냅샷 없음. `writer.stored.addAll(소속튜플(20, "DEV002"))`, `source.willReturn(조직도(직원들(0, 15), "DEV002"))` — 훑은 20줄 중 지울 5줄(25%) | `ABORTED`; 문구 `contains("기준선 없음").contains("삭제 대상 5건(훑은 장부 20건의 25.0%)이 상한 3건을 초과했습니다")`; `writer.deleted` 비어 있음; `writer.stored` 크기 20 |

주석의 "깨뜨릴 변경": 그 경로가 주입받은 가드가 아닌 다른 판정을 부르거나, 재판정에서 상한을 빼기.

- [ ] **Step 4: 시험이 실패하는지 본다**

Run: `./gradlew :core:test --tests "dev.starryeye.organization.core.guard.DeletionGuardTest" --tests "dev.starryeye.organization.core.usecase.FullSyncUseCaseTest"`
Expected: 컴파일은 되고, 상한을 보는 새 시험이 FAIL(가드가 상한을 보지 않아 진행). `비율만_넘으면_문구가_지금과_같다`·`상한이_0_이하면_상한을_보지_않는다`·`강제면_비교_경로가_상한을_넘어도_지운다` 는 지금도 통과한다 — 바뀌지 않아야 할 동작을 고정하는 시험이다.

- [ ] **Step 5: `DeletionGuard.evaluate(int deleteCount, int baselineCount, String baselineLabel)` 를 고친다**

판정 순서는 설계 §3.1 그대로다. 문구는 정확한 글이라 모양을 적는다.

```java
if (!policy.enabled()) return GuardDecision.proceed();
double ratio = (double) deleteCount / baselineCount;   // 문구에도 쓴다
boolean 상한_넘음 = policy.maxDeletes() > 0 && deleteCount > policy.maxDeletes();
boolean 임계치_넘음 = baselineCount >= policy.minBaseline() && ratio > policy.thresholdRatio();
if (!상한_넘음 && !임계치_넘음) return GuardDecision.proceed();
double 임계치 = policy.thresholdRatio() * 100;
String 넘은_것 = 임계치_넘음 && 상한_넘음 ? "임계치 %.1f%%와 상한 %d건을".formatted(임계치, policy.maxDeletes())
              : 임계치_넘음 ? "임계치 %.1f%%를".formatted(임계치)
              : "상한 %d건을".formatted(policy.maxDeletes());
return GuardDecision.abort("삭제 대상 %d건(%s %d건의 %.1f%%)이 %s 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
        .formatted(deleteCount, baselineLabel, baselineCount, ratio * 100, 넘은_것));
```

기준 수가 `min-baseline` 미만이면 지금은 비율을 계산하기 전에 진행했다. 이제는 상한을 봐야 하므로 그 이른 반환을 없앤다. 기준 수 0 은 지울 수도 0 이라(G3) 상한에 닿지 않는다.
자바독: 메서드에 판정 순서와 세 문구 꼴을 적는다. 클래스 자바독 셋째 문단의 "같은 가드(같은 기준 수, 같은 임계치)" 를 "같은 가드(같은 기준 수, 같은 임계치, 같은 상한)" 로. 클래스 자바독에 한 문단을 더한다: 비율만으로는 큰 배포에서 막는 양이 수만 줄이라 절대 상한을 OR 로 본다, 상한은 `min-baseline` 과 상관없다, `0` 이하면 끈다(설계 2026-11-13 G1~G3). `evaluate(Set, Set, int, String)` 자바독의 "같은 가드로 본다" 에도 같은 상한임을 적는다.

- [ ] **Step 6: 시험이 통과하는지 본다**

Run: `./gradlew :core:test --tests "dev.starryeye.organization.core.guard.DeletionGuardTest" --tests "dev.starryeye.organization.core.usecase.FullSyncUseCaseTest"`
Expected: PASS

Run: `./gradlew :core:test`
Expected: PASS(기존 시험은 모두 `defaults()` 이고 지울 줄이 5000 아래다 — 설계 §12).

- [ ] **Step 7: `SyncPropertiesTest` 에 실패하는 시험을 더한다**

스프링 문맥 없이 본다. 바인딩은 `org.springframework.boot.context.properties.bind.Binder` + `MapConfigurationPropertySource` 로 하고, 결선은 `new UseCaseConfig().deletionGuard(properties)` 를 직접 불러 가드로 판정한다.

```java
@Test
@DisplayName("sync.deletion-guard.max-deletes 를 주면 가드가 그 값을 쓴다")
void 가드_상한을_설정하면_가드가_그_값을_쓴다() {
    // given — 깨뜨릴 변경: UseCaseConfig.deletionGuard 가 maxDeletes 를 넘기지 않음
    var properties = new SyncProperties();
    new Binder(new MapConfigurationPropertySource(Map.of("sync.deletion-guard.max-deletes", "3")))
            .bind("sync", Bindable.ofInstance(properties));
    var guard = new UseCaseConfig().deletionGuard(properties);
    // when, then — 기준 9 는 min-baseline 미만이라 비율은 보지 않는다. 상한만 본다
    assertThat(guard.evaluate(3, 9, "기준 스냅샷").aborted()).isFalse();
    assertThat(guard.evaluate(4, 9, "기준 스냅샷").aborted()).isTrue();
}
```

| 시험 | 단언 |
|---|---|
| `가드_상한의_기본값은_5000줄이다` | `new SyncProperties().getDeletionGuard().getMaxDeletes()` 가 5000; `new UseCaseConfig().deletionGuard(new SyncProperties())` 로 `evaluate(5000, 200_000, "기준 스냅샷")` 진행, `evaluate(5001, 200_000, "기준 스냅샷")` 걸림. 깨뜨릴 변경: 기본값을 0 으로 둠 |
| `두_yml_의_가드_상한은_속성_기본값과_같다` | `YamlPropertiesFactoryBean` 으로 `application.yml`·`application-test.yml` 을 각각 읽어 `getProperty("sync.deletion-guard.max-deletes")` 가 `String.valueOf(new SyncProperties().getDeletionGuard().getMaxDeletes())` 와 같다. 깨뜨릴 변경: 한쪽 yml 에서 키를 빼거나 다른 값을 적음 |

- [ ] **Step 8: 시험이 실패하는지 본다**

Run: `./gradlew :app-ldap:test --tests "dev.starryeye.organization.ldap.app.SyncPropertiesTest"`
Expected: FAIL — `getMaxDeletes` 가 없어 컴파일되지 않는다(Step 1 뒤로 `UseCaseConfig` 도 3인자 생성자를 불러 컴파일되지 않는다).

- [ ] **Step 9: 설정과 결선을 더한다**

- `SyncProperties.DeletionGuardConfig` 에 `private int maxDeletes = 5000;` 과 자바독(한 회차에 지울 튜플 줄 수의 상한, 넘으면 비율과 상관없이 걸린다, 같으면 통과, `0` 이하면 보지 않는다, 10만 명 규모의 평소 변동보다 훨씬 크고 비율 30%보다 훨씬 작은 수 — 설계 2026-11-13 G1·G2).
- `UseCaseConfig.deletionGuard` 가 `config.getMaxDeletes()` 를 넷째 인자로 넘긴다.
- `application.yml`·`application-test.yml` 의 `sync.deletion-guard` 아래 `min-baseline: 10` 다음 줄에 `max-deletes: 5000`.

- [ ] **Step 10: 시험이 통과하는지 본다**

Run: `./gradlew :app-ldap:test --tests "dev.starryeye.organization.ldap.app.SyncPropertiesTest" --tests "dev.starryeye.organization.ldap.app.SyncSchedulerTest" --tests "dev.starryeye.organization.ldap.app.SyncSchedulerCronTest"`
Expected: PASS(뒤 둘은 `defaults()` 로 가드를 만드는 컴파일 확인용이다).

- [ ] **Step 11: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/guard core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/SyncProperties.java app-ldap/src/main/java/dev/starryeye/organization/ldap/app/UseCaseConfig.java app-ldap/src/main/resources/application.yml app-ldap/src/test/resources/application-test.yml app-ldap/src/test/java/dev/starryeye/organization/ldap/app/SyncPropertiesTest.java
```

```bash
git commit -m "feat: 삭제 가드에 지울 줄 수 상한(sync.deletion-guard.max-deletes, 기본 5000)을 비율과 OR 로 더하고, 넘은 한도를 문구에 적는다 (가드 상한)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

### Task 2: README 와 온보딩을 코드대로 고친다 (G7)

**Files:**
- Modify: `README.md`, `docs/onboarding/02-용어집.md`, `03-아키텍처.md`, `05-LDAP-동기화.md`, `07-재적재-초기화-아카이빙.md`, `11-운영.md`, `12-장애와-복구.md`, `13-한계와-알려진-문제.md`, `15-결정-색인.md`

**Interfaces:**
- Consumes: Task 1 의 동작(Global Constraints 의 판정·문구·설정 키와 기본값)과 Task 1 커밋의 해시(`git log -1 --format=%h` 를 단일 명령으로).

규칙: 온보딩은 늘 현재 동작이다(설계 §2 G7). 숫자가 바뀌는 곳만 고치고 문장 모양은 그 문서의 것을 따른다. "30%" 를 적은 곳은 상한도 함께 보는지 판단해 고친다 — 상한 아래의 작은 예(1,000 줄 기준의 413줄 등)는 그대로 두어도 된다. 지울 줄이 5000 을 넘는 예시 문구는 둘 다 넘은 꼴(`임계치 30.0%와 상한 5000건을`)로 고친다.

| 곳 | 고칠 것 |
|---|---|
| README 약 179·548·556·879·1014행 | 가드가 "훑은 장부의 30%" 또는 "30%" 만 본다고 적은 곳에 "또는 지울 줄 5,000(`max-deletes`)" 을 더한다. 879행의 큰 변경 예는 상한이 먼저 닿는지 확인해 고친다 |
| 02 §6 "삭제 가드"(약 893·894·922행) | 판정 줄에 상한(`max-deletes`, 기본 5000, `min-baseline` 과 상관없음, `0` 이하면 끔)을 더하고, 걸린 메시지 줄에 세 꼴을 적는다. 재판정 줄은 "같은 기준 수, 같은 임계치, 같은 상한" |
| 03 약 180행 | 설정 목록에 `max-deletes` |
| 05 §6.4(약 566~595행) | 흐름도: 가드 꺼짐이면 진행 → "지울 수 > max-deletes(>0)?" 또는 "기준 수 ≥ min-baseline 이고 비율 > 임계치?" 가운데 하나면 떼어 낸다 → 재판정 마름모도 "같은 판정을 통과하나?" 로. 설정 표에 `max-deletes` 행. 판정 순서 2 의 "같은 기준 수, 같은 임계치" 에 "같은 상한". 문구 예에 상한만·둘 다 꼴 둘을 더한다(설계 §3.1 의 예). 1182행 설정 요약에 `max-deletes` / `5000` |
| 05 §7.2·1012행 표 | 장부 맞추기 쪽 설명에 상한이 분모를 보지 않아 이 경로에서 더 자주 걸리고, 걸린 장부 맞추기는 `force` 전까지 매시 D 쓰기와 훑기를 되풀이한다는 것(설계 §3.3). 1012행 메시지 칸은 "임계치 30.0%를 / 상한 …건을 / 둘 다" 를 가리키게 |
| 07 "삭제 가드"(약 192~221행) | 기준 수 문단의 44k/154k 예는 이제 상한에 걸린다고 고친다(설계 §3.3). 판정 표의 "통과" 조건에 상한. 221행의 실행 기록 예(61015건)는 둘 다 넘은 꼴 문구로 |
| 11 설정 표(약 113·114행 뒤) | `sync.deletion-guard.max-deletes` 행: 기본 `5000`, 뜻, 언제 바꾸나(큰 개편·대량 퇴사 기간에 올리거나 그 회차만 `force`, `0` 은 끔). 아래 "셋은 yml 에도" 를 "넷은" 으로 |
| 12 약 317·320·330·350·360·361·373·720행 | "30% 를 넘는 빠짐만" 을 "30% 또는 5,000줄을 넘는 빠짐만" 꼴로. 350·720행 문구 칸. 끊긴 회차 절에 한 줄: `force` 로 넘긴 큰 회차가 기록 전에 끊기면 다음 정각은 장부 맞추기이고 남은 지우기가 상한을 넘으면 다시 걸리므로, 실행 기록을 보고 다시 `force` 한다(설계 §3.3) |
| 13 §3.3(약 228~236행) | 제목에서 "절대 수 상한도 없다" 를 뺀다. 무엇: 비율 또는 절대 상한(기본 5000)을 본다. 영향: 10만 명 중 2만 명 예는 이제 걸린다 — 대신 `child` 한 줄이 지우는 롤업은 줄 수로 보이지 않는다(설계 §2·§6), 기본 5000 은 잰 값이 아니다. 상태: 부여 쪽 가드만 남김, 절대 수 상한은 설계 2026-11-13 이 더했다 |
| 13 §3.4(약 240~244행) | 44k 예는 이제 상한에 걸린다. 약점은 상한 아래의 변경에서만 남는다. 걸린 장부 맞추기의 비싼 되풀이가 상한 때문에 10만 명 규모에서 늘어난다(설계 §3.3) |
| 13 약 290·329·519행 | 290·329: "30% 이하면" 에 상한을 더한다. 519 색인 행: "부여 가드 없음(SCIM 대량 회수는 세기만)" |
| 15 "삭제 가드"(약 174·177행) | 174 행에 상한을 더하거나 새 행: "지울 줄이 `max-deletes`(기본 5000)를 넘어도 멈춘다. 비율과 OR, `min-baseline` 과 상관없음, 비활성 회수 재판정에도 같은 상한" — 까닭: 큰 배포에서 비율만으로는 수만 줄이 통과한다 — 근거: [가드 상한 설계](../superpowers/specs/2026-11-13-deletion-guard-absolute-cap-design.md). 177 행은 "부여 쪽 가드는 두지 않았다" 로 |

- [ ] **Step 1: 위 표대로 고친다.** 고친 뒤 `grep -n '30%\|threshold-ratio\|임계치 30' README.md docs/onboarding/*.md` 로 남은 곳을 하나씩 보고, 상한을 빠뜨린 설명이 없는지 확인한다.
- [ ] **Step 2: 고친 온보딩 문서 머리의 기준 커밋 줄을 `<Task 1 커밋 해시> (deletion-guard-absolute-cap, main 에 머지됨)` 으로 올린다.** 건드리지 않은 문서는 그대로 둔다.
- [ ] **Step 3: 링크를 확인한다.** 새로 단 링크의 대상 파일과 앵커가 있는지 `grep` 으로 본다.
- [ ] **Step 4: 커밋**

```bash
git add README.md docs/onboarding
```

```bash
git commit -m "docs: 삭제 가드의 절대 수 상한(max-deletes, 기본 5000)을 README 와 온보딩에 반영한다 — 판정·문구·설정·장부 맞추기에서 달라지는 것·13 §3.3·§3.4 (가드 상한)" -m "Co-Authored-By: <자기 모델 이름> <noreply@anthropic.com>"
```

---

## 마무리 (메인 세션)

- 전체 `./gradlew cleanTest test` 와 `./gradlew cleanScaleTest scaleTest` 를 차례로 돌린다. `LdapDeletionGuardScaleTest`(L12·L13)는 5천 명 기준에서 30% 가 상한보다 먼저 닿아 그대로 통과해야 한다.
- 설계 §9 에 결과(검증 수, 커밋, 구현 중 판정, 최종 리뷰, 남긴 것)를 적고 커밋한다.
- 머지·푸시는 사용자의 말이 있을 때만.
