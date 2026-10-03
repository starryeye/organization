# 조직 쓰기의 읽기 비용과 조직 그래프 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 점검 ③-2 — SCIM 조직 쓰기의 읽기 비용이 조직 크기가 아니라 요청 크기를 따르게 하고(P1 `type` 판정 묶음, 들어오는 멤버 묶음 읽기, POST 상위 조직 헤더만,
S28 값 넘기기), 중첩 조직의 순환 처리를 튜플 그래프 기준으로 바로잡는다(P2 위로 올라가는 순환 검사·한도 400, S4 먼저 저장된 연결이 이김, M1 보류 목록과 다시 검사).

**Architecture:** 저장소 포트에 묶음 읽기 둘(`findMemberTypes`·`findUsers`), 이전 값을 받는 저장(`saveUser(이전, 이후)`·`saveGroupChange(이전, 이후, …)`),
보류 목록 셋(`findCutEdges`·`changeCutEdges`·`replaceCutEdges`)을 더하고 `findChildGroupIds` 를 없앤다. connector-scim 은 요청의 `type` 없는 아이디를 먼저 모아
한 번에 판정한다. core 는 새 부품 `OrgGraph`(요청 하나 동안의 튜플 그래프 = 멤버 줄 − 보류 목록)가 순환 검사와 보류 판단을 하고, `IncrementalSyncUseCase` 는
순환을 버리지 않은 목표(`TupleMapper.toTuplesKeepingCycles`)를 `OrgGraph` 로 거르고, 보류 줄을 멤버 줄보다 먼저 쓰고, 하위 조직 연결을 지운 요청 끝에서 보류
목록을 다시 본다. `TupleMapper.toTuples(그림, 보류)` 가 버린 연결을 돌려주어 SCIM 재적재가 목록을 다시 쓰고 아카이빙이 목록을 따른다.

**Tech Stack:** Java 17, Spring Boot 3.5 / WebFlux, Reactor, AWS SDK v2 DynamoDB(비동기), OpenFGA Java SDK 0.9.11, JUnit 5, AssertJ, Awaitility, Logback(ListAppender),
Testcontainers(DynamoDB Local, OpenFGA v1.10.2), Lombok.

**Spec:** `docs/superpowers/specs/2026-10-03-read-cost-org-graph-design.md`

## Global Constraints

- 테스트: Lombok, AssertJ, BDD(`// given` `// when` `// then`), 한글 `@DisplayName`, 한글 메서드 이름. 주변 코드의 주석 밀도·문체(평서형 "~다")를 따른다.
- 각 테스트는 **먼저 지금 코드로 실패하는 것을 보고**(컴파일 실패 포함) 고친다.
- 묶음 읽기: BatchGet 키 100개씩(`BatchRequests.GET_LIMIT`), **강한 일관성**, `BatchRequests` 재시도 규칙(첫 요청 포함 5번, 100ms 부터 두 배).
- `type` 판정 규칙: 조직이 있으면 조직, 직원이 있으면 직원, 둘 다 없으면 직원. 있는 직원으로 판정되면 경고하지 않는다. 조직·없음이면 요청당 한 줄:
  `SCIM 멤버에 type 이 없어 현재상태로 추정했습니다: 조직 {}명 {}, 없음 {}명 {}(직원으로 봄)` (아이디는 각각 앞 10개까지).
- 순환 검사 한도 10,000(`OrgGraph.MAX_EXPANSIONS`). 넘기면 `GroupGraphTooLargeException("조직 계층이 너무 크다 — 순환 검사가 %d개 조직을 넘겼습니다: %s")` →
  SCIM 400 `invalidValue`.
- 보류 목록: 파티션 키 `CYCLE_CUT`, 정렬키 `EDGE#<부모>|<자식>`(아이디에는 `|` 가 없다 — `IdNormalizer`). 속성 `parent`·`child`.
- 보류 로그: `튜플 변환 경고: 조직 '{}' → '{}' 간선이 순환을 만들어 보류합니다(보류 목록에 적음)` (WARN). 되살림: `순환이 풀려 보류했던 연결을 썼다: 조직 '{}' → '{}'` (INFO).
  다시 검사 실패: `보류 목록을 다시 보지 못했다 — 다음 지우기 요청이나 재적재가 다시 본다` (WARN, 원인 포함).
- 검사 순서: 새 연결 중 **자식이 초점 조직인 것**(상위 조직이 먼저 적어 둔 연결)을 먼저, 그다음 나머지. 각각 (부모, 자식) 아이디 순.
- 커밋 순서: 새로 보류할 줄 → 원래 커밋(멤버 줄) → 풀린 줄 빼기(OpenFGA 에 있거나 이번에 쓴 것만).
- 운영 배포 전이라 이관·하위호환을 만들지 않는다.
- 서브에이전트는 과제에 적힌 **모듈 테스트나 테스트 클래스만** 돌린다. 앱 모듈 전체 `test`·`scaleTest` 는 돌리지 않는다(컨트롤러가 돌린다). Gradle 은 한 번에 하나, 포그라운드.
  파일 시스템 전체를 훑는 검색(`find /`)을 하지 않는다. `git stash` 를 쓰지 않는다. 백그라운드 프로세스를 띄우지 않는다.
- 커밋마다 `git push`(브랜치 `audit-read-cost`, 업스트림 설정돼 있음). 커밋 메시지는 제목 → 빈 줄 → `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  (heredoc `git commit -F - <<'EOF' … EOF`). 경로를 지정해 스테이징한다.
- 서브에이전트를 띄우지 않는다.

## Review Focus

1. **조직이 자기 자신을 하위 조직으로 넣는다** — 200 이고, 그 연결은 쓰지 않고 보류 목록에 적힌다(Task 7 `자기_자신을_넣는_연결은_보류한다`).
2. **한 PATCH 가 하위 조직 하나를 빼고 다른 하나를 넣는다** — 넣는 쪽은 지금 그래프로 검사되고, 빼기로 순환이 풀린 보류 연결은 같은 요청 끝에서 쓰인다
   (Task 8 `빼고_넣는_요청도_끝에서_다시_본다`).
3. **보류 목록에 이미 지워진 조직의 줄이 남아 있다** — 다음 지우기 요청 끝에서 지워지고, 그 사이 순환 검사를 막지 않는다(Task 8 `멤버_줄이_없는_보류_줄은_지운다`).
4. **SCIM 재적재 때 조직도에 순환이 더는 없다** — 보류 목록이 비워진다(Task 9 `순환이_없으면_보류_목록을_비운다`).
5. **같은 `type` 없는 아이디가 한 요청에 여러 번 나온다(여러 operation 에 걸쳐)** — 한 번만 묻고 멤버도 한 번만 들어간다(Task 2 `같은_아이디는_한_번만_묻는다`).

---

## Task 1: 저장소의 묶음 읽기 둘 — `findMemberTypes`, `findUsers`

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/GetCounter.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java` (위임 두 줄)
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java` (감시 두 줄)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Produces: `Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids)`, `Flux<DirectoryUser> findUsers(Set<String> userIds)` (포트).
  가짜: `public final List<Set<String>> findMemberTypesCalls`, `public final List<Set<String>> findUsersCalls`.

- [ ] **Step 1: 실패하는 저장소 테스트를 쓴다**

`GetCounter` 에 BatchGetItem 호출 수를 더한다:

```java
    private final AtomicLong batchGets = new AtomicLong();
    // wrap 의 핸들러 안, getItem 분기 옆에:
                    if (method.getName().equals("batchGetItem")) {
                        batchGets.incrementAndGet();
                    }
    long batchGets() {
        return batchGets.get();
    }
    // reset() 에 batchGets.set(0); 추가
```

`DynamoDbDirectoryStateRepositoryTest` 에 더한다(클래스의 `세는_저장소` 처럼 `GetCounter` 로 감싼 저장소를 쓴다 — 없으면 같은 모양의 도우미를 둔다):

```java
    @Test
    @DisplayName("아이디마다 조직인지 직원인지 한 번에 판정한다 — 둘 다 있으면 조직, 없으면 결과에 없다")
    void 종류를_한_번에_판정한다() {
        // given
        repository.saveGroup(조직("G1", "팀")).block();
        repository.saveUser(직원("u1")).block();
        repository.saveUser(직원("both")).block();
        repository.saveGroup(조직("both", "겹치는 조직")).block();

        // when
        var 종류 = repository.findMemberTypes(Set.of("G1", "u1", "both", "ghost")).block();

        // then
        assertThat(종류).containsExactlyInAnyOrderEntriesOf(Map.of(
                "G1", MemberType.GROUP, "u1", MemberType.USER, "both", MemberType.GROUP));
    }

    @Test
    @DisplayName("종류 판정과 직원 묶음 읽기는 BatchGet 으로 100개씩 읽는다 — GetItem 을 하지 않는다")
    void 묶음으로_읽는다() {
        // given — 직원 150명
        Set<String> 아이디 = new LinkedHashSet<>();
        for (int i = 0; i < 150; i++) {
            repository.saveUser(직원("b" + i)).block();
            아이디.add("b" + i);
        }
        GetCounter counter = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(counter.wrap(client), properties, clock);

        // when
        var 종류 = 세는.findMemberTypes(아이디).block();
        long 판정_묶음 = counter.batchGets();
        counter.reset();
        var 직원들 = 세는.findUsers(Set.copyOf(아이디)).collectList().block();

        // then — 판정은 키 300개(조직 META·직원 META) = 3묶음, 직원은 150개 = 2묶음
        assertThat(종류).hasSize(150).containsValue(MemberType.USER).doesNotContainValue(MemberType.GROUP);
        assertThat(판정_묶음).isEqualTo(3);
        assertThat(직원들).hasSize(150).extracting(DirectoryUser::id).containsExactlyInAnyOrderElementsOf(아이디);
        assertThat(counter.batchGets()).isEqualTo(2);
        assertThat(counter.gets()).isZero();
    }

    @Test
    @DisplayName("직원 묶음 읽기는 없는 아이디를 빼고 돌려준다")
    void 없는_직원은_빠진다() {
        // given
        repository.saveUser(직원("kim")).block();

        // when
        var 직원들 = repository.findUsers(Set.of("kim", "ghost")).collectList().block();

        // then
        assertThat(직원들).containsExactly(직원("kim"));
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
Expected: 컴파일 실패 — `findMemberTypes`·`findUsers` 가 없다.

- [ ] **Step 3: 포트에 둘을 더한다**

`DirectoryStateRepository` 의 `findUser` 아래:

```java
    /**
     * 직원 여러 명을 한 번에 읽는다(설계 2026-10-03 §3.1). 없는 아이디는 결과에 없다. BatchGet(키 100개씩), 강한 일관성 — 조직 PATCH·PUT·POST 의
     * 멤버 직원을 한 명씩 GetItem 으로 읽지 않으려고 쓴다.
     */
    Flux<DirectoryUser> findUsers(Set<String> userIds);

    /**
     * 아이디마다 조직인지 직원인지 — 조직 META 와 직원 META 를 한 번에 묻는다(설계 2026-10-03 §3.1, 점검 P1). 둘 다 있으면 조직이다(조직을 먼저 찾던
     * 판정 순서). 없는 아이디는 결과에 없다. BatchGet(키 100개씩), 강한 일관성.
     */
    Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids);
```

(import `java.util.Map`, `dev.starryeye.organization.core.model.MemberType`.)

- [ ] **Step 4: DynamoDB 구현**

`DynamoDbDirectoryStateRepository` 의 `findMembers` 근처:

```java
    /** 직원 META 키를 {@link BatchRequests} 로 묶어 강한 일관성으로 읽는다(설계 2026-10-03 §3.1). */
    @Override
    public Flux<DirectoryUser> findUsers(Set<String> userIds) {
        return Flux.fromIterable(userIds)
                .map(id -> Map.of(Keys.PK, Attrs.s(Keys.userPk(id)), Keys.SK, Attrs.s(Keys.META)))
                .buffer(BatchRequests.GET_LIMIT)
                .flatMap(this::batchGet, QUERY_CONCURRENCY)
                .map(item -> toUser(Keys.parseUserPk(Attrs.str(item, Keys.PK)), item));
    }

    /** 아이디마다 조직 META·직원 META 키를 함께 묻는다 — 키가 아이디의 두 배라 100개 묶음에 50명씩 든다(설계 2026-10-03 §3.1). */
    @Override
    public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) {
        return Flux.fromIterable(ids)
                .flatMapIterable(id -> List.of(
                        Map.of(Keys.PK, Attrs.s(Keys.groupPk(id)), Keys.SK, Attrs.s(Keys.META)),
                        Map.of(Keys.PK, Attrs.s(Keys.userPk(id)), Keys.SK, Attrs.s(Keys.META))))
                .buffer(BatchRequests.GET_LIMIT)
                .flatMap(this::batchGet, QUERY_CONCURRENCY)
                .map(item -> Attrs.str(item, Keys.PK))
                .collect(HashMap<String, MemberType>::new, (found, pk) -> {
                    if (pk.startsWith(Keys.GROUP_PREFIX)) {
                        found.put(Keys.parseGroupPk(pk), MemberType.GROUP);
                    } else {
                        found.putIfAbsent(Keys.parseUserPk(pk), MemberType.USER);
                    }
                })
                .map(Map::copyOf);
    }
```

- [ ] **Step 5: 가짜와 위임을 맞춘다**

`FakeStateRepository`:

```java
    /** {@link #findUsers} 가 받은 아이디 묶음 — 멤버 직원을 묶어 읽는지 단언한다. */
    public final List<Set<String>> findUsersCalls = new ArrayList<>();

    /** {@link #findMemberTypes} 가 받은 아이디 묶음. */
    public final List<Set<String>> findMemberTypesCalls = new ArrayList<>();

    @Override
    public Flux<DirectoryUser> findUsers(Set<String> userIds) {
        return Flux.defer(() -> {
            findUsersCalls.add(Set.copyOf(userIds));
            return Flux.fromIterable(userIds).flatMap(id -> Mono.justOrEmpty(users.get(id)));
        });
    }

    @Override
    public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) {
        return Mono.fromCallable(() -> {
            findMemberTypesCalls.add(Set.copyOf(ids));
            Map<String, MemberType> found = new LinkedHashMap<>();
            for (String id : ids) {
                if (groups.containsKey(id)) {
                    found.put(id, MemberType.GROUP);
                } else if (users.containsKey(id)) {
                    found.put(id, MemberType.USER);
                }
            }
            return found;
        });
    }
```

`LdapInterruptedSyncScaleTest` 의 `한번만_실패하는_상태저장소` 에 위임 두 줄:

```java
        @Override public Flux<DirectoryUser> findUsers(Set<String> userIds) { return 실제.findUsers(userIds); }
        @Override public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) { return 실제.findMemberTypes(ids); }
```

`WriteDecisionLockInvariantTest` 의 `감시하는_저장소` 에 감시 두 줄:

```java
        @Override public Flux<DirectoryUser> findUsers(Set<String> ids) { return 본다Flux("findUsers " + ids, () -> super.findUsers(ids)); }
        @Override public Mono<Map<String, MemberType>> findMemberTypes(Set<String> ids) { return 본다("findMemberTypes " + ids, () -> super.findMemberTypes(ids)); }
```

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'` 그다음 `./gradlew :core:test --tests '*WriteDecisionLockInvariantTest'`
그다음 `./gradlew :app-ldap:compileTestJava`
Expected: PASS, 컴파일 성공.

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/GetCounter.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java
git commit -F - <<'EOF'
feat: 저장소 묶음 읽기 둘 — 멤버 종류 판정(findMemberTypes)과 직원 묶음 읽기(findUsers), BatchGet 100개씩·강한 일관성

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 2: P1 — `type` 없는 멤버를 요청당 한 번에 판정 (connector-scim)

**Files:**
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/MemberTypeResolver.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/StateMemberTypeResolver.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java`
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java`
- Create: `connector-scim/src/test/java/dev/starryeye/organization/scim/StateMemberTypeResolverTest.java`
- Modify: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimPatchApplierTest.java`
- Modify: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimMapperTest.java`
- Modify: `connector-scim/src/test/java/dev/starryeye/organization/scim/fixture/ScimRequestRendererTest.java`

**Interfaces:**
- Consumes: `findMemberTypes(Set<String>)` (Task 1).
- Produces: `MemberTypeResolver#resolveAll(Set<String> ids) : Mono<Map<String, MemberType>>` — 결과에는 모든 아이디가 있다(모르면 USER). 비어 있으면 저장소를 읽지 않는다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

새 `StateMemberTypeResolverTest`(Logback `ListAppender` 로 경고를 본다 — `core` 의 `IncrementalSyncLeaseTest` 와 같은 방식):

```java
class StateMemberTypeResolverTest {

    private FakeStateRepository state;
    private StateMemberTypeResolver resolver;
    private ListAppender<ILoggingEvent> 로그;
    private Logger logger;

    @BeforeEach
    void 준비한다() {
        state = new FakeStateRepository();
        resolver = new StateMemberTypeResolver(state);
        logger = (Logger) LoggerFactory.getLogger(StateMemberTypeResolver.class);
        로그 = new ListAppender<>();
        로그.start();
        logger.addAppender(로그);
    }

    @AfterEach
    void 정리한다() {
        logger.detachAppender(로그);
    }

    @Test
    @DisplayName("여러 아이디를 저장소 한 번으로 판정한다 — 조직·직원·없음(직원으로 봄)")
    void 한_번에_판정한다() {
        // given
        state.groups.put("DEV", new DirectoryGroup("DEV", "DEV", "개발", Set.of()));
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));

        // when
        var 종류 = resolver.resolveAll(Set.of("DEV", "kim", "ghost")).block();

        // then
        assertThat(종류).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DEV", MemberType.GROUP, "kim", MemberType.USER, "ghost", MemberType.USER));
        assertThat(state.findMemberTypesCalls).containsExactly(Set.of("DEV", "kim", "ghost"));
    }

    @Test
    @DisplayName("있는 직원으로 판정되면 경고하지 않는다 — type 은 선택 필드라 Entra·Okta 의 정상 경로다")
    void 있는_직원이면_경고하지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));

        // when
        resolver.resolveAll(Set.of("kim")).block();

        // then
        assertThat(로그.list).filteredOn(event -> event.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    @DisplayName("조직으로 추정했거나 없는 아이디면 요청당 경고 한 줄을 남긴다")
    void 추정이_위험하면_한_줄로_경고한다() {
        // given
        state.groups.put("DEV", new DirectoryGroup("DEV", "DEV", "개발", Set.of()));

        // when
        resolver.resolveAll(new LinkedHashSet<>(List.of("DEV", "ghost1", "ghost2"))).block();

        // then
        assertThat(로그.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("조직 1명", "DEV", "없음 2명", "ghost1", "ghost2");
    }

    @Test
    @DisplayName("판정할 아이디가 없으면 저장소를 읽지 않는다")
    void 비면_읽지_않는다() {
        // when
        var 종류 = resolver.resolveAll(Set.of()).block();

        // then
        assertThat(종류).isEmpty();
        assertThat(state.findMemberTypesCalls).isEmpty();
    }
}
```

`ScimPatchApplierTest` — `USER_ONLY` 를 새 모양으로 바꾸고(아래 Step 3 과 같은 커밋), 두 테스트를 더한다:

```java
    private static final MemberTypeResolver USER_ONLY = ids -> Mono.just(
            ids.stream().collect(Collectors.toMap(id -> id, id -> MemberType.USER)));

    @Test
    @DisplayName("여러 operation 에 걸친 type 없는 멤버를 한 번에 판정한다 — type 있는 멤버는 묻지 않는다")
    void 요청당_한_번_판정한다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("add", "members", List.of(Map.of("value", "a"), Map.of("value", "b"))),
                new ScimOperation("add", null, Map.of("members", List.of(Map.of("value", "c"), Map.of("value", "d", "type", "User"))))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("a", "b", "c"));
        assertThat(change.applyTo(조직(), id -> false).members()).containsExactlyInAnyOrder(
                MemberRef.user("a"), MemberRef.user("b"), MemberRef.user("c"), MemberRef.user("d"));
    }

    @Test
    @DisplayName("같은 아이디가 여러 operation 에 나와도 한 번만 묻고 멤버도 한 번만 들어간다")
    void 같은_아이디는_한_번만_묻는다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var patch = new ScimPatchOp(List.of(ScimSchemas.PATCH_OP), List.of(
                new ScimOperation("add", "members", List.of(Map.of("value", "a"))),
                new ScimOperation("add", "members", List.of(Map.of("value", "a")))));

        // when
        var change = ScimPatchApplier.toGroupChange(patch, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("a"));
        assertThat(change.applyTo(조직(), id -> false).members()).containsExactly(MemberRef.user("a"));
    }
```

(`ScimPatchOp`·`ScimOperation`·`ScimSchemas.PATCH_OP` 의 실제 이름·생성자는 이 테스트 파일의 기존 `패치(...)` 도우미를 따른다 — 도우미가 operation 하나만 만들면
여러 operation 을 담는 도우미를 같은 모양으로 하나 더 둔다. `조직()` 은 이 파일의 기존 도우미다.)

`ScimMapperTest` — `USER_ONLY` 를 같은 모양으로 바꾸고 하나를 더한다:

```java
    @Test
    @DisplayName("POST·PUT 본문의 type 없는 멤버를 한 번에 판정한다")
    void 본문당_한_번_판정한다() {
        // given
        List<Set<String>> 물은것 = new ArrayList<>();
        MemberTypeResolver resolver = ids -> {
            물은것.add(Set.copyOf(ids));
            return USER_ONLY.resolveAll(ids);
        };
        var scim = new ScimGroup(List.of(ScimSchemas.GROUP), null, "DEV001", "개발본부",
                List.of(new ScimMember("kim", null, null), new ScimMember("lee", null, null), new ScimMember("DEV002", "Group", null)), null);

        // when
        DirectoryGroup group = ScimMapper.toDirectoryGroup(scim, resolver).block();

        // then
        assertThat(물은것).containsExactly(Set.of("kim", "lee"));
        assertThat(group.members()).containsExactlyInAnyOrder(
                MemberRef.user("kim"), MemberRef.user("lee"), MemberRef.group("DEV002"));
    }
```

`ScimRequestRendererTest` 의 `추정금지` 를 새 모양으로 바꾼다(빈 집합이면 통과, 아니면 실패):

```java
    private final MemberTypeResolver 추정금지 = ids -> {
        if (!ids.isEmpty()) {
            fail("멤버 type 이 빠져 현재상태 추정이 일어났습니다: " + ids);
        }
        return Mono.just(Map.of());
    };
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :connector-scim:test --tests '*StateMemberTypeResolverTest' --tests '*ScimPatchApplierTest' --tests '*ScimMapperTest'`
Expected: 컴파일 실패 — `resolveAll` 이 없다.

- [ ] **Step 3: `MemberTypeResolver` 와 `StateMemberTypeResolver`**

```java
@FunctionalInterface
public interface MemberTypeResolver {

    /**
     * {@code ids} 의 종류를 한 번에 판정한다(설계 2026-10-03 §3.2, 점검 P1). 결과에는 모든 아이디가 있다 — 판정하지 못한 아이디는 직원으로 둔다.
     * {@code ids} 는 이미 {@code IdNormalizer} 를 통과한 값이어야 한다. 비어 있으면 저장소를 읽지 않는다.
     */
    Mono<Map<String, MemberType>> resolveAll(Set<String> ids);
}
```

`StateMemberTypeResolver` (클래스 자바독의 "어느 쪽으로 판정하든 경고를 남긴다"를 새 규칙으로 고친다 — 있는 직원이면 정상 경로라 남기지 않고, 조직으로 추정했거나
없는 아이디일 때만 요청당 한 줄):

```java
    private static final int 로그에_남길_아이디 = 10;

    @Override
    public Mono<Map<String, MemberType>> resolveAll(Set<String> ids) {
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return state.findMemberTypes(ids).map(found -> {
            Map<String, MemberType> resolved = new LinkedHashMap<>();
            List<String> 조직 = new ArrayList<>();
            List<String> 없음 = new ArrayList<>();
            for (String id : ids) {
                MemberType type = found.get(id);
                if (type == null) {
                    없음.add(id);
                    resolved.put(id, MemberType.USER);
                    continue;
                }
                if (type == MemberType.GROUP) {
                    조직.add(id);
                }
                resolved.put(id, type);
            }
            if (!조직.isEmpty() || !없음.isEmpty()) {
                log.warn("SCIM 멤버에 type 이 없어 현재상태로 추정했습니다: 조직 {}명 {}, 없음 {}명 {}(직원으로 봄)",
                        조직.size(), 앞부분(조직), 없음.size(), 앞부분(없음));
            }
            return resolved;
        });
    }

    private static List<String> 앞부분(List<String> ids) {
        return ids.subList(0, Math.min(로그에_남길_아이디, ids.size()));
    }
```

- [ ] **Step 4: `ScimPatchApplier` — 아이디를 먼저 모으고 한 번 판정한 뒤 동기로 적용**

`toGroupChange` 를 바꾸고, `applyOne`·`mergeGroupAttributes`·`toMemberRefs`·`memberRef` 를 `Map<String, MemberType> 종류` 를 받는 동기 메서드로 바꾼다
(지금의 검증·예외 문구는 그대로, `Mono.error(...)` 는 `throw` 로):

```java
    public static Mono<GroupChange> toGroupChange(ScimPatchOp patch, MemberTypeResolver resolver) {
        List<ScimOperation> operations = operations(patch);
        return Mono.defer(() -> {
                    Set<String> 모름 = new LinkedHashSet<>();
                    operations.forEach(operation -> 모름.addAll(typeless(operation)));
                    return resolver.resolveAll(모름);
                })
                .map(종류 -> {
                    GroupChange change = GroupChange.delta();
                    for (ScimOperation operation : operations) {
                        change = applyOne(change, operation, 종류);
                    }
                    return change;
                });
    }

    /**
     * 이 연산이 넣는 멤버 중 {@code type} 이 없는 아이디(정규화한 값, 설계 2026-10-03 §3.2). 모양이 틀린 연산은 비워 두고 {@link #applyOne} 이 거절한다 —
     * 여기서는 예외를 던지지 않는다.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> typeless(ScimOperation operation) {
        String op = operation.op() == null ? "" : operation.op().trim().toLowerCase(Locale.ROOT);
        if (!op.equals("add") && !op.equals("replace")) {
            return Set.of();
        }
        String path = operation.path();
        Object members = null;
        if (path == null || path.isBlank()) {
            if (operation.value() instanceof Map<?, ?> map) {
                members = attribute((Map<String, Object>) map, "members");
            }
        } else if (path.trim().equalsIgnoreCase("members")) {
            members = operation.value();
        }
        if (!(members instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) raw;
            Object id = attribute(map, "value");
            Object type = attribute(map, "type");
            if (id != null && !id.toString().isBlank() && (type == null || type.toString().isBlank())) {
                ids.add(IdNormalizer.normalize(id.toString()));
            }
        }
        return ids;
    }

    private static GroupChange applyOne(GroupChange change, ScimOperation operation, Map<String, MemberType> 종류) {
        String op = normalizeOp(operation.op());
        String path = operation.path();

        if (path == null || path.isBlank()) {
            requireReplaceOrAdd(op, operation.op());
            return mergeGroupAttributes(change, op, asAttributeMap(operation.value()), 종류);
        }

        Matcher filter = MEMBER_FILTER.matcher(path.trim());
        if (filter.matches()) {
            if (!op.equals("remove")) {
                throw ScimException.invalidPath(
                        "members 필터는 remove 에만 지원합니다: op=" + operation.op() + ", path=" + path);
            }
            return change.removingId(memberId(filter.group("filter"), path));
        }

        if (path.trim().equalsIgnoreCase("members")) {
            return switch (op) {
                case "add" -> change.adding(toMemberRefs(operation.value(), 종류));
                case "remove" -> {
                    // RFC 7644 §3.5.2.2 — 필터 없는 remove 는 전원 삭제다. remove 의 value 는 RFC 가 정하지 않은 칸이다
                    if (operation.value() != null) {
                        throw ScimException.invalidValue(REMOVE_WITH_VALUE);
                    }
                    yield change.replacing(Set.of());
                }
                case "replace" -> change.replacing(toMemberRefs(operation.value(), 종류));
                default -> throw ScimException.invalidSyntax("알 수 없는 op 입니다: " + operation.op());
            };
        }

        if (path.trim().equalsIgnoreCase("displayName")) {
            requireReplaceOrAdd(op, operation.op());
            return change.renamed(asString(operation.value()));
        }

        throw ScimException.invalidPath("지원하지 않는 path 입니다: " + path);
    }

    // (mergeGroupAttributes 의 자바독은 그대로)
    private static GroupChange mergeGroupAttributes(GroupChange change, String op, Map<String, Object> attributes,
                                                    Map<String, MemberType> 종류) {
        GroupChange renamed = has(attributes, "displayName")
                ? change.renamed(asString(attribute(attributes, "displayName")))
                : change;
        if (!has(attributes, "members")) {
            return renamed;
        }
        Set<MemberRef> members = toMemberRefs(attribute(attributes, "members"), 종류);
        return op.equals("add") ? renamed.adding(members) : renamed.replacing(members);
    }

    // (toMemberRefs 의 자바독 — 정규화 이유 — 은 그대로)
    private static Set<MemberRef> toMemberRefs(Object value, Map<String, MemberType> 종류) {
        if (!(value instanceof List<?> raw)) {
            throw ScimException.invalidSyntax("members 값은 배열이어야 합니다");
        }
        Set<MemberRef> members = new LinkedHashSet<>();
        for (Object element : raw) {
            members.add(memberRef(element, 종류));
        }
        return members;
    }

    @SuppressWarnings("unchecked")
    private static MemberRef memberRef(Object element, Map<String, MemberType> 종류) {
        if (!(element instanceof Map<?, ?> rawMap)) {
            throw ScimException.invalidSyntax("members 원소는 객체여야 합니다");
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        Object rawId = attribute(map, "value");
        if (rawId == null || rawId.toString().isBlank()) {
            throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
        }
        String id = IdNormalizer.normalize(rawId.toString());
        Object type = attribute(map, "type");
        // SCIM 에서 type 은 선택 필드다. 없으면 추측하지 않고, 요청 앞에서 모아 현재상태로 판정한 결과를 쓴다
        if (type == null || type.toString().isBlank()) {
            return new MemberRef(종류.getOrDefault(id, MemberType.USER), id);
        }
        return type.toString().equalsIgnoreCase("Group") ? MemberRef.group(id) : MemberRef.user(id);
    }
```

클래스·`toGroupChange` 자바독의 "`resolver` 로 판정한다"를 "요청의 type 없는 아이디를 먼저 모아 `resolver` 로 한 번에 판정한다(설계 2026-10-03 §3.2)"로 고친다.
`Flux` import 가 더는 쓰이지 않으면 지운다.

- [ ] **Step 5: `ScimMapper.toDirectoryGroup` — 같은 방식**

```java
    public static Mono<DirectoryGroup> toDirectoryGroup(ScimGroup scim, MemberTypeResolver resolver) {
        return Mono.defer(() -> {
            String code = organizationCode(scim);
            List<ScimMember> members = scim.members() == null ? List.of() : scim.members();
            Set<String> 모름 = new LinkedHashSet<>();
            for (ScimMember member : members) {
                String id = memberId(member);
                if (member.type() == null || member.type().isBlank()) {
                    모름.add(id);
                }
            }
            return resolver.resolveAll(모름)
                    .map(종류 -> new DirectoryGroup(code, scim.externalId(), scim.displayName(), toMemberRefs(members, 종류)));
        });
    }

    /** value 가 없으면 {@code invalidSyntax}. 정규화 규칙은 아래 {@link #toMemberRefs} 자바독 참고. */
    private static String memberId(ScimMember member) {
        if (member.value() == null || member.value().isBlank()) {
            throw ScimException.invalidSyntax("members 원소에 value 가 없습니다");
        }
        return IdNormalizer.normalize(member.value());
    }

    private static Set<MemberRef> toMemberRefs(List<ScimMember> members, Map<String, MemberType> 종류) {
        Set<MemberRef> refs = new LinkedHashSet<>();
        for (ScimMember member : members) {
            String id = memberId(member);
            if (member.type() == null || member.type().isBlank()) {
                refs.add(new MemberRef(종류.getOrDefault(id, MemberType.USER), id));
            } else {
                refs.add(member.type().equalsIgnoreCase("Group") ? MemberRef.group(id) : MemberRef.user(id));
            }
        }
        return refs;
    }
```

(지금 `toMemberRefs` 의 자바독 — 정규화 이유 — 은 새 `toMemberRefs` 로 옮긴다. `toDirectoryGroup` 자바독의 "type 이 모두 명시돼 있으면 조회는 일어나지 않는다"는 그대로 참이다.)

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :connector-scim:test`
Expected: PASS. 기존 테스트의 resolver 람다(`id -> Mono.just(...)`)가 남아 있으면 새 모양으로 고친다.

- [ ] **Step 7: 커밋**

```bash
git add connector-scim/src/main/java/dev/starryeye/organization/scim/MemberTypeResolver.java \
  connector-scim/src/main/java/dev/starryeye/organization/scim/StateMemberTypeResolver.java \
  connector-scim/src/main/java/dev/starryeye/organization/scim/ScimPatchApplier.java \
  connector-scim/src/main/java/dev/starryeye/organization/scim/ScimMapper.java \
  connector-scim/src/test/java/dev/starryeye/organization/scim/
git commit -F - <<'EOF'
feat: type 없는 멤버를 요청당 한 번에 판정한다(점검 P1) — 아이디를 먼저 모아 BatchGet, 있는 직원이면 경고하지 않고 추정이 위험할 때만 요청당 한 줄

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 3: 들어오는 멤버 묶음 읽기, 조직 POST 의 상위 조직은 헤더만

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`loadMemberUsers`, `parentsOf` → `상위_조직들`, 클래스·`upsertGroup` 자바독)
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/GroupChangeReadScopeTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`

**Interfaces:**
- Consumes: `findUsers(Set<String>)` (Task 1).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`GroupChangeReadScopeTest` 의 `한명_추가`·`전체_교체` 단언을 바꾼다:

```java
        assertThat(state.findUserCalls).as("직원을 한 명씩 읽지 않는다").isEmpty();
        assertThat(state.findUsersCalls).containsExactly(Set.of("newbie"));
```

그리고 하나를 더한다:

```java
    @Test
    @DisplayName("들어오는 멤버가 많아도 직원을 한 번에 묶어 읽는다")
    void 많이_넣어도_묶어_읽는다() {
        // given — 아직 멤버가 아닌 직원 300명
        Set<MemberRef> 새멤버 = new LinkedHashSet<>();
        IntStream.range(0, 300).forEach(i -> {
            state.users.put("n" + i, 직원("n" + i));
            새멤버.add(MemberRef.user("n" + i));
        });

        // when
        var result = useCase.changeGroup(대형조직, GroupChange.delta().adding(새멤버)).block(Duration.ofSeconds(10));

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findUserCalls).isEmpty();
        assertThat(state.findUsersCalls).hasSize(1);
        assertThat(state.findUsersCalls.get(0)).hasSize(300);
    }
```

`IncrementalSyncReadScopeTest` 에 하나를 더한다(이 클래스의 300명 대형조직 픽스처를 쓴다):

```java
    @Test
    @DisplayName("새 조직 POST 는 그 조직을 먼저 적어 둔 상위 조직을 통째로 읽지 않는다 — 헤더만, 상위 조직의 직원도 읽지 않는다")
    void POST는_상위_조직을_통째로_읽지_않는다() {
        // given — 대형조직이 아직 없는 LATE 를 하위 조직으로 적어 두었다(늦게 도착한 조직)
        DirectoryGroup 상위 = state.groups.get(대형조직);
        Set<MemberRef> 멤버 = new LinkedHashSet<>(상위.members());
        멤버.add(MemberRef.group("LATE"));
        state.groups.put(대형조직, new DirectoryGroup(상위.id(), 상위.externalId(), 상위.displayName(), 멤버));
        state.findGroupCalls.clear();
        state.findUserCalls.clear();
        state.findUsersCalls.clear();

        // when
        var result = useCase.createGroup(new DirectoryGroup("LATE", "ou=late", "늦게 온 조직", Set.of())).block(Duration.ofSeconds(10));

        // then — 늦게 도착한 조직의 상위 연결은 지금처럼 쓰인다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("LATE", 대형조직));
        assertThat(state.findGroupCalls).as("상위 조직 파티션을 읽지 않는다").doesNotContain(대형조직);
        assertThat(state.findUserCalls).isEmpty();
        assertThat(state.findUsersCalls).allSatisfy(ids -> assertThat(ids).isEmpty());
    }
```

(이 클래스의 필드 이름 — `state`, `writer`, `useCase`, `대형조직` — 은 파일의 실제 이름을 따른다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*GroupChangeReadScopeTest' --tests '*IncrementalSyncReadScopeTest'`
Expected: FAIL — `findUserCalls` 가 비어 있지 않고 `findUsersCalls` 가 비어 있다, POST 가 `findGroup(대형조직)` 을 부른다.

- [ ] **Step 3: `loadMemberUsers` 를 묶음 읽기로**

```java
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
```

- [ ] **Step 4: `parentsOf` 를 `상위_조직들` 로**

```java
    /**
     * 이 조직을 하위 조직으로 적어 둔 상위 조직들 — <b>헤더만</b> 읽고 멤버는 이 조직 하나로만 싣는다(설계 2026-10-03 §3.4). 이 연산은 후보·목표·상태 기준선을
     * 모두 이 조직을 언급하는 튜플로 좁히므로({@link #mentioning}) 상위 조직의 다른 멤버는 결과에 기여하지 않는다 — {@link #직원한명_그림} 과 같은 논리다.
     */
    private Mono<Set<DirectoryGroup>> 상위_조직들(String groupId) {
        Set<MemberRef> 이조직만 = Set.of(MemberRef.group(groupId));
        return state.findGroupIdsContaining(MemberRef.group(groupId))
                .flatMap(state::findGroupHeader, LOAD_CONCURRENCY)
                .map(header -> new DirectoryGroup(header.id(), header.externalId(), header.displayName(), 이조직만))
                .collect(LinkedHashSet<DirectoryGroup>::new, Set::add);
    }
```

`upsertGroupInternal` 의 `parentsOf(group.id())` 를 `상위_조직들(group.id())` 로 바꾼다. 클래스 자바독의 "상위 조직들(멤버 목록까지 그대로)"·"`parentsOf` 로 상위 조직들을
멤버 목록째로" 와 `upsertGroup` 자바독의 "`parentsOf` 로 상위 조직들을 <b>멤버 목록 그대로</b>" 를 "헤더만, 멤버는 이 조직 하나로"로 고친다(이유: 이 조직을 언급하는
튜플만 보므로 결과가 같다).

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS. 다른 테스트가 `findUserCalls` 로 멤버 직원 읽기를 단언하면 `findUsersCalls` 로 고친다.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java core/src/test/java/dev/starryeye/organization/core/usecase/
git commit -F - <<'EOF'
feat: 조직 그림의 멤버 직원을 묶어 읽고, 조직 POST 의 상위 조직은 헤더만 읽는다 — 들어오는 멤버·10만 명 상위 조직이 조직 크기만큼 읽지 않는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 4: S28 — 락 안에서 읽은 값을 저장에 넘긴다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`

**Interfaces:**
- Produces: `Mono<Void> saveUser(DirectoryUser before, DirectoryUser after)` (before 는 null 가능), `default Mono<Void> saveUser(DirectoryUser user)`(저장본을 읽어 넘김);
  `Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed)`,
  `default Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed)`(헤더를 읽어 넘김).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`DynamoDbDirectoryStateRepositoryTest`:

```java
    @Test
    @DisplayName("이전 값을 넘기면 META 를 다시 읽지 않고, 같으면 쓰지 않고 다르면 쓴다(점검 S28)")
    void 이전_값을_넘기면_다시_읽지_않는다() {
        // given
        repository.saveUser(직원("kim")).block();
        var 저장본 = repository.findUser("kim").block();
        GetCounter gets = new GetCounter();
        WriteCounter writes = new WriteCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(writes.wrap(gets.wrap(client)), properties, clock);

        // when — 같은 값
        세는.saveUser(저장본, 직원("kim")).block();

        // then
        assertThat(gets.gets()).isZero();
        assertThat(writes.puts()).isZero();

        // when — 바뀐 값
        세는.saveUser(저장본, 직원("kim").withDisplayName("새 이름")).block();

        // then
        assertThat(gets.gets()).isZero();
        assertThat(writes.puts()).isEqualTo(1);
        assertThat(repository.findUser("kim").block().displayName()).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("조직 변경도 이전 헤더를 넘기면 META 를 다시 읽지 않는다 — 멤버만 빼면 줄만 지우고 META 는 바뀐 것으로 찍는다")
    void 조직_변경도_다시_읽지_않는다() {
        // given
        repository.saveGroup(조직("DEV", "개발", MemberRef.user("kim"))).block();
        var 헤더 = repository.findGroupHeader("DEV").block();
        GetCounter gets = new GetCounter();
        var 세는 = new DynamoDbDirectoryStateRepository(gets.wrap(client), properties, clock);

        // when
        세는.saveGroupChange(헤더, 헤더, Set.of(), Set.of(MemberRef.user("kim"))).block();

        // then
        assertThat(gets.gets()).isZero();
        assertThat(repository.findMemberRefs("DEV").collectList().block()).isEmpty();
    }
```

(`WriteCounter.wrap` 과 `GetCounter.wrap` 은 둘 다 `DynamoDbAsyncClient` 를 감싸 돌려주므로 겹쳐 감쌀 수 있다. `DirectoryUser#withDisplayName` 이 없으면 생성자로 만든다.)

`IncrementalSyncReadScopeTest`:

```java
    @Test
    @DisplayName("직원 생성·변경은 직원 META 를 한 번만 읽는다(점검 S28)")
    void 직원_쓰기는_한_번만_읽는다() {
        // given
        state.findUserCalls.clear();

        // when
        useCase.createUser(new DirectoryUser("park", "uid=park", "park", "박", "park@example.com", true)).block(Duration.ofSeconds(10));
        var 생성때 = List.copyOf(state.findUserCalls);
        state.findUserCalls.clear();
        useCase.changeUser("park", user -> user.withDisplayName("박 님")).block(Duration.ofSeconds(10));

        // then
        assertThat(생성때).containsExactly("park");
        assertThat(state.findUserCalls).containsExactly("park");
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'` 그다음 `./gradlew :core:test --tests '*IncrementalSyncReadScopeTest'`
Expected: 컴파일 실패(2인자 `saveUser`·4인자 `saveGroupChange` 없음), core 는 `findUserCalls` 가 두 번.

- [ ] **Step 3: 포트**

```java
    /**
     * 직원 META 를 {@code after} 로 맞춘다. {@code before} 는 부르는 쪽이 락 안에서 강한 일관성으로 읽은 저장본이다(없으면 null) — 저장소가 다시 읽지 않고 이것과
     * 비교해 바뀌었을 때만 쓰고, 그때만 {@code updatedAt} 을 찍는다(설계 2026-10-03 §3.5, 점검 S28).
     */
    Mono<Void> saveUser(DirectoryUser before, DirectoryUser after);

    /** 저장본을 읽어 {@link #saveUser(DirectoryUser, DirectoryUser)} 로 넘긴다 — 저장본을 모르는 쪽(심기·테스트)이 쓴다. */
    default Mono<Void> saveUser(DirectoryUser user) {
        return findUser(user.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(before -> saveUser(before.orElse(null), user));
    }
```

`saveGroupChange` 를 4인자로 바꾸고(자바독에 "`before` 는 락 안에서 읽은 헤더(없으면 null) — 저장소가 META 를 다시 읽지 않는다(점검 S28)" 한 문장), 3인자는 default 로:

```java
    Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed);

    /** 헤더를 읽어 {@link #saveGroupChange(GroupHeader, GroupHeader, Set, Set)} 로 넘긴다 — 저장본을 모르는 쪽이 쓴다. */
    default Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return findGroupHeader(header.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(before -> saveGroupChange(before.orElse(null), header, added, removed));
    }
```

- [ ] **Step 4: DynamoDB 구현**

1인자 `saveUser` 오버라이드를 지우고(포트 default 가 한다) 2인자를 둔다. 비교는 지금처럼 **라운드트립한 이후 값**과 한다:

```java
    /**
     * 넘겨받은 저장본과 다를 때만 쓰고, 그때만 {@code updatedAt} 을 찍는다(GSI 설계 §3). 저장본을 다시 읽지 않는다(설계 2026-10-03 §3.5) — 부르는 쪽이 락 안에서 읽었다.
     * {@link #writeUser} 와 같은 이유로 이후 값을 한 번 인코딩했다가 되읽어 비교한다.
     */
    @Override
    public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
        if (before != null && before.equals(toUser(after.id(), userItem(after)))) {
            return Mono.empty();
        }
        return putItem(stamped(userItem(after)));
    }
```

`saveGroupChange` 를 4인자로 바꾼다:

```java
    @Override
    public Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {
        Stored<GroupHeader> stored = before == null ? null : new Stored<>(before, true);
        return writeMembership(after, stored, List.copyOf(added), List.copyOf(removed));
    }
```

(3인자 오버라이드는 지운다 — 포트 default. 클래스 자바독에 저장본 비교를 설명하는 곳이 있으면 "SCIM 쓰기는 넘겨받은 저장본과 비교한다"를 한 문장 더한다.)

- [ ] **Step 5: 가짜·위임**

`FakeStateRepository` — 1인자는 읽지 않고 그대로 넣는다(시드에 쓰여 읽기 계측을 더럽히지 않게):

```java
    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        users.put(user.id(), user);
        return Mono.empty();
    }

    @Override
    public Mono<Void> saveUser(DirectoryUser before, DirectoryUser after) {
        return saveUser(after);
    }

    @Override
    public Mono<Void> saveGroupChange(GroupHeader header, Set<MemberRef> added, Set<MemberRef> removed) {
        return saveGroupChange(null, header, added, removed);
    }

    @Override
    public Mono<Void> saveGroupChange(GroupHeader before, GroupHeader after, Set<MemberRef> added, Set<MemberRef> removed) {
        return Mono.fromRunnable(() -> {
            Set<MemberRef> members = new LinkedHashSet<>(membersOf(after.id()));
            members.removeAll(removed);
            members.addAll(added);
            groups.put(after.id(), new DirectoryGroup(after.id(), after.externalId(), after.displayName(), members));
        });
    }
```

`LdapInterruptedSyncScaleTest` 위임: `saveUser(DirectoryUser user)` 위임은 지우고(포트 default) `saveUser(before, after)`·4인자 `saveGroupChange` 위임을 둔다
(3인자 위임도 지운다).

- [ ] **Step 6: 유스케이스가 읽은 값을 넘긴다**

```java
    public Mono<IncrementalSyncResult> upsertUser(DirectoryUser user) {
        return withLock(lease -> state.findUser(user.id())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> upsertUserInternal(user, existing, lease)));
    }

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
```

`createUser` 는 `upsertUserInternal(user, Optional.empty(), lease)`, `changeUser` 는 `upsertUserInternal(after, Optional.of(before), lease)`.
`changeGroupInternal` 의 커밋은 `state.saveGroupChange(header, 바뀐헤더, 넣을것, 뺄것)`, `removeUserInternal` 은 `state.saveGroupChange(header, header, Set.of(), Set.of(이직원))`,
`조직_삭제를_커밋한다` 는 `state.saveGroupChange(parent, parent, …)`·`state.saveGroupChange(header, header, Set.of(), 지운멤버)`.

- [ ] **Step 7: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'` 그다음 `./gradlew :core:test` 그다음 `./gradlew :app-ldap:compileTestJava`
Expected: PASS, 컴파일 성공.

- [ ] **Step 8: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java
git commit -F - <<'EOF'
feat: 락 안에서 읽은 저장본을 저장에 넘긴다(점검 S28) — 직원 쓰기 META 읽기 3 → 1, 조직 PATCH 헤더 읽기 2 → 1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 5: `GroupEdge`, `TupleMapper` 가 보류 목록을 받고 버린 연결을 돌려준다

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/GroupEdge.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMappingResult.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java`

**Interfaces:**
- Produces: `record GroupEdge(String parent, String child)` + `RelationTuple tuple()` + `static Optional<GroupEdge> of(RelationTuple)`;
  `TupleMappingResult(Set<RelationTuple> tuples, List<String> warnings, Set<GroupEdge> cutEdges)`;
  `TupleMapper.toTuples(DirectorySnapshot, Set<GroupEdge> 보류)`, `TupleMapper.toTuplesKeepingCycles(DirectorySnapshot)`. 한 인자 `toTuples(snapshot)` 은 빈 목록과 같다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`TupleMapperTest`(파일의 기존 스냅샷 도우미를 쓴다):

```java
    @Test
    @DisplayName("순환으로 버린 연결을 결과에 돌려준다")
    void 버린_연결을_돌려준다() {
        // given — A ⊃ B, B ⊃ A
        var snapshot = new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", "A", "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", "B", "B", Set.of(MemberRef.group("A")))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then — 사전순 DFS 가 A 에서 시작해 B → A 를 버린다
        assertThat(result.tuples()).containsExactly(RelationTuple.child("B", "A"));
        assertThat(result.cutEdges()).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("보류 목록의 연결을 먼저 빼고 계산한다 — 남은 그래프에 순환이 없으면 더 버리지 않는다")
    void 보류_목록을_먼저_뺀다() {
        // given
        var snapshot = new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", "A", "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", "B", "B", Set.of(MemberRef.group("A")))));

        // when — "B 는 A 의 하위"(A ⊃ B)를 보류해 두었다
        var result = TupleMapper.toTuples(snapshot, Set.of(new GroupEdge("A", "B")));

        // then
        assertThat(result.tuples()).containsExactly(RelationTuple.child("A", "B"));
        assertThat(result.cutEdges()).isEmpty();
    }

    @Test
    @DisplayName("순환을 버리지 않는 변환은 모든 하위 조직 연결을 낸다 — SCIM 순증 쓰기용")
    void 순환을_버리지_않는다() {
        // given
        var snapshot = new DirectorySnapshot(Map.of(), Map.of(
                "A", new DirectoryGroup("A", "A", "A", Set.of(MemberRef.group("B"))),
                "B", new DirectoryGroup("B", "B", "B", Set.of(MemberRef.group("A")))));

        // when
        var result = TupleMapper.toTuplesKeepingCycles(snapshot);

        // then
        assertThat(result.tuples()).containsExactlyInAnyOrder(RelationTuple.child("B", "A"), RelationTuple.child("A", "B"));
        assertThat(result.cutEdges()).isEmpty();
    }

    @Test
    @DisplayName("하위 조직 연결 튜플을 연결 값으로 되돌린다 — direct_member 는 연결이 아니다")
    void 튜플을_연결로_되돌린다() {
        assertThat(GroupEdge.of(RelationTuple.child("B", "A"))).contains(new GroupEdge("A", "B"));
        assertThat(new GroupEdge("A", "B").tuple()).isEqualTo(RelationTuple.child("B", "A"));
        assertThat(GroupEdge.of(RelationTuple.directMember("kim", "A"))).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*TupleMapperTest'`
Expected: 컴파일 실패 — `GroupEdge`·`cutEdges`·두 메서드가 없다.

- [ ] **Step 3: `GroupEdge`**

```java
package dev.starryeye.organization.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 하위 조직 연결 — {@code child} 가 {@code parent} 의 하위 조직이다. 튜플 {@code (group:child, child, group:parent)} 와 같다.
 * 순환이라 쓰지 않은 연결의 보류 목록(설계 2026-10-03 §4.1)이 이 값으로 적힌다.
 */
public record GroupEdge(String parent, String child) {

    public GroupEdge {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(child, "child");
    }

    public RelationTuple tuple() {
        return RelationTuple.child(child, parent);
    }

    /** 하위 조직 연결 튜플이면 그 연결, 아니면 빈 값. */
    public static Optional<GroupEdge> of(RelationTuple tuple) {
        if (!RelationTuple.CHILD.equals(tuple.relation())) {
            return Optional.empty();
        }
        return Optional.of(new GroupEdge(idOf(tuple.object()), idOf(tuple.user())));
    }

    private static String idOf(String typedId) {
        int separator = typedId.indexOf(':');
        return separator < 0 ? typedId : typedId.substring(separator + 1);
    }
}
```

- [ ] **Step 4: `TupleMappingResult`·`TupleMapper`**

```java
public record TupleMappingResult(Set<RelationTuple> tuples, List<String> warnings, Set<GroupEdge> cutEdges) {

    public TupleMappingResult {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        cutEdges = cutEdges == null ? Set.of() : Set.copyOf(cutEdges);
    }
}
```

`TupleMapper`:

```java
    public static TupleMappingResult toTuples(DirectorySnapshot snapshot) {
        return toTuples(snapshot, Set.of());
    }

    /**
     * 보류 목록의 연결을 먼저 빼고 변환한다(설계 2026-10-03 §4.6). 남은 순환은 조직코드 사전순 DFS 로 버리고, 버린 연결을 결과의 {@code cutEdges} 에 담는다 —
     * SCIM 재적재는 빈 목록으로 불러 그것으로 보류 목록을 다시 쓰고, 아카이빙은 저장된 목록을 넘긴다. LDAP 은 늘 빈 목록이다.
     */
    public static TupleMappingResult toTuples(DirectorySnapshot snapshot, Set<GroupEdge> 보류) {
        List<String> warnings = new ArrayList<>();

        Map<String, Set<String>> childEdges = collectChildEdges(snapshot, warnings);
        childEdges.forEach((parent, children) -> children.removeIf(child -> 보류.contains(new GroupEdge(parent, child))));
        Set<GroupEdge> cut = new LinkedHashSet<>();
        Set<Edge> acyclic = removeCycles(childEdges, warnings, cut);

        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Edge edge : acyclic) {
            tuples.add(RelationTuple.child(edge.child(), edge.parent()));
        }
        tuples.addAll(collectDirectMembers(snapshot, warnings));

        return new TupleMappingResult(tuples, warnings, cut);
    }

    /**
     * 순환을 버리지 않고 변환한다 — SCIM 순증 쓰기용(설계 2026-10-03 §4.3). 최소 그림 안의 DFS 는 조직코드 순서로 버릴 연결을 골라 한 홉 순환에서 두 연결을
     * 모두 버릴 수 있다(점검 S4). 순증 쓰기는 이것으로 목표를 만들고 순환 판단은 저장소의 튜플 그래프로 "먼저 저장된 연결이 이긴다" 규칙 하나로 한다.
     */
    public static TupleMappingResult toTuplesKeepingCycles(DirectorySnapshot snapshot) {
        List<String> warnings = new ArrayList<>();
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        collectChildEdges(snapshot, warnings).forEach((parent, children) ->
                children.forEach(child -> tuples.add(RelationTuple.child(child, parent))));
        tuples.addAll(collectDirectMembers(snapshot, warnings));
        return new TupleMappingResult(tuples, warnings, Set.of());
    }
```

`removeCycles(edges, warnings, cut)`·`visit(…, cut)` 에 `Set<GroupEdge> cut` 를 넘기고, GRAY 를 만나는 자리에서 경고 옆에 `cut.add(new GroupEdge(node, child));`.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/model/GroupEdge.java \
  core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java \
  core/src/main/java/dev/starryeye/organization/core/tuple/TupleMappingResult.java \
  core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java
git commit -F - <<'EOF'
feat: TupleMapper 가 보류 목록을 먼저 빼고 버린 연결을 돌려준다, 순환을 버리지 않는 변환(SCIM 순증용) — GroupEdge

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 6: 보류 목록 저장소 — `CYCLE_CUT` 파티션

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`, `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`

**Interfaces:**
- Consumes: `GroupEdge` (Task 5).
- Produces: `Flux<GroupEdge> findCutEdges()`, `Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed)`, `Mono<Void> replaceCutEdges(Set<GroupEdge> edges)`.
  가짜: `public final Set<GroupEdge> cutEdges`, `public int findCutEdgesCalls`. `Keys.CYCLE_CUT_PK`, `Keys.cutEdgeSk(GroupEdge)`, `Keys.parseCutEdgeSk(String)`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`KeysTest`:

```java
    @Test
    @DisplayName("보류 연결의 정렬키를 되읽으면 같은 연결이다")
    void 보류_연결_정렬키() {
        var edge = new GroupEdge("개발본부", "백엔드팀");
        assertThat(Keys.cutEdgeSk(edge)).isEqualTo("EDGE#개발본부|백엔드팀");
        assertThat(Keys.parseCutEdgeSk(Keys.cutEdgeSk(edge))).isEqualTo(edge);
    }
```

`DynamoDbDirectoryStateRepositoryTest`:

```java
    @Test
    @DisplayName("보류 목록에 넣고 빼고 통째로 바꾼다")
    void 보류_목록을_다룬다() {
        // given
        var ab = new GroupEdge("A", "B");
        var cd = new GroupEdge("C", "D");
        var ef = new GroupEdge("E", "F");

        // when
        repository.changeCutEdges(Set.of(ab, cd), Set.of()).block();
        var 넣은뒤 = repository.findCutEdges().collectList().block();
        repository.changeCutEdges(Set.of(), Set.of(ab)).block();
        var 뺀뒤 = repository.findCutEdges().collectList().block();
        repository.replaceCutEdges(Set.of(ef)).block();
        var 바꾼뒤 = repository.findCutEdges().collectList().block();

        // then
        assertThat(넣은뒤).containsExactlyInAnyOrder(ab, cd);
        assertThat(뺀뒤).containsExactly(cd);
        assertThat(바꾼뒤).containsExactly(ef);
    }

    @Test
    @DisplayName("보류 목록 줄은 직원·조직 열거에 섞이지 않는다")
    void 보류_줄은_조직도에_섞이지_않는다() {
        // given
        repository.saveGroup(조직("A", "에이", MemberRef.group("B"))).block();
        repository.changeCutEdges(Set.of(new GroupEdge("A", "B")), Set.of()).block();

        // when
        var 전체 = repository.loadAll().block();

        // then
        assertThat(전체.groups()).containsOnlyKeys("A");
        assertThat(전체.users()).isEmpty();
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest' --tests '*KeysTest'`
Expected: 컴파일 실패.

- [ ] **Step 3: 포트**

```java
    /**
     * 보류 목록 — 순환이라 튜플을 쓰지 않은 하위 조직 연결(설계 2026-10-03 §4.1). 멤버 줄은 따로 남아 있다. 보통 비어 있다. 강한 일관성.
     * "튜플 그래프 = 멤버 줄의 하위 조직 연결 − 보류 목록"이다.
     */
    Flux<GroupEdge> findCutEdges();

    /** 보류 목록에 {@code added} 를 넣고 {@code removed} 를 뺀다. 둘 다 비면 아무것도 하지 않는다. */
    Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed);

    /** 보류 목록을 {@code edges} 로 통째로 바꾼다 — SCIM 재적재·wipe 가 쓴다(설계 2026-10-03 §4.6). */
    Mono<Void> replaceCutEdges(Set<GroupEdge> edges);
```

- [ ] **Step 4: `Keys`·DynamoDB 구현**

`Keys`:

```java
    /**
     * 보류 목록 파티션(설계 2026-10-03 §4.1) — 순환이라 쓰지 않은 하위 조직 연결. 앱마다 테이블이 달라 SCIM 테이블에만 생긴다. GSI 키가 없어 직원·조직
     * 열거에 섞이지 않는다.
     */
    public static final String CYCLE_CUT_PK = "CYCLE_CUT";
    public static final String CUT_EDGE_PREFIX = "EDGE#";

    /** {@code EDGE#<부모>|<자식>}. 아이디에는 {@code |} 가 없다({@code IdNormalizer}). */
    public static String cutEdgeSk(GroupEdge edge) {
        return CUT_EDGE_PREFIX + edge.parent() + TUPLE_SEPARATOR + edge.child();
    }

    public static GroupEdge parseCutEdgeSk(String sk) {
        String body = sk.substring(CUT_EDGE_PREFIX.length());
        int separator = body.indexOf(TUPLE_SEPARATOR);
        return new GroupEdge(body.substring(0, separator), body.substring(separator + 1));
    }
```

`DynamoDbDirectoryStateRepository`:

```java
    private static final String CUT_PARENT = "parent";
    private static final String CUT_CHILD = "child";

    @Override
    public Flux<GroupEdge> findCutEdges() {
        return querySortKeys(Keys.CYCLE_CUT_PK, Keys.CUT_EDGE_PREFIX).map(Keys::parseCutEdgeSk);
    }

    @Override
    public Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed) {
        return Flux.fromIterable(added)
                .flatMap(edge -> putItem(cutEdgeItem(edge)), QUERY_CONCURRENCY)
                .thenMany(Flux.fromIterable(removed)
                        .flatMap(edge -> deleteItem(Keys.CYCLE_CUT_PK, Keys.cutEdgeSk(edge)), QUERY_CONCURRENCY))
                .then();
    }

    @Override
    public Mono<Void> replaceCutEdges(Set<GroupEdge> edges) {
        return findCutEdges().collect(Collectors.toSet()).flatMap(지금 -> {
            Set<GroupEdge> 뺄것 = new HashSet<>(지금);
            뺄것.removeAll(edges);
            Set<GroupEdge> 넣을것 = new HashSet<>(edges);
            넣을것.removeAll(지금);
            return changeCutEdges(넣을것, 뺄것);
        });
    }

    private Map<String, AttributeValue> cutEdgeItem(GroupEdge edge) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.CYCLE_CUT_PK));
        item.put(Keys.SK, Attrs.s(Keys.cutEdgeSk(edge)));
        item.put(CUT_PARENT, Attrs.s(edge.parent()));
        item.put(CUT_CHILD, Attrs.s(edge.child()));
        return item;
    }
```

- [ ] **Step 5: 가짜·위임·감시**

`FakeStateRepository`:

```java
    /** 보류 목록(설계 2026-10-03 §4.1). {@link #replaceWith} 는 건드리지 않는다 — 실제 저장소도 그렇다. */
    public final Set<GroupEdge> cutEdges = new LinkedHashSet<>();

    /** {@link #findCutEdges} 가 불린 수 — 보류 목록을 언제 읽는지 단언한다. */
    public int findCutEdgesCalls;

    @Override
    public Flux<GroupEdge> findCutEdges() {
        return Flux.defer(() -> {
            findCutEdgesCalls++;
            return Flux.fromIterable(List.copyOf(cutEdges));
        });
    }

    @Override
    public Mono<Void> changeCutEdges(Set<GroupEdge> added, Set<GroupEdge> removed) {
        return Mono.fromRunnable(() -> {
            cutEdges.addAll(added);
            cutEdges.removeAll(removed);
        });
    }

    @Override
    public Mono<Void> replaceCutEdges(Set<GroupEdge> edges) {
        return Mono.fromRunnable(() -> {
            cutEdges.clear();
            cutEdges.addAll(edges);
        });
    }
```

`LdapInterruptedSyncScaleTest` 위임 셋(`findCutEdges`·`changeCutEdges`·`replaceCutEdges` → `실제`). `WriteDecisionLockInvariantTest` 감시 하나:

```java
        @Override public Flux<GroupEdge> findCutEdges() { return 본다Flux("findCutEdges", super::findCutEdges); }
```

- [ ] **Step 6: 통과를 본다**

Run: `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest' --tests '*KeysTest'` 그다음 `./gradlew :core:test --tests '*WriteDecisionLockInvariantTest'`
그다음 `./gradlew :app-ldap:compileTestJava`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java \
  core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java
git commit -F - <<'EOF'
feat: 보류 목록 저장소 — CYCLE_CUT 파티션에 순환이라 쓰지 않은 하위 조직 연결을 적는다(읽기·넣고 빼기·통째로 바꾸기)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 7: 위로 올라가는 순환 검사, 먼저 저장된 연결이 이김, 보류 목록 커밋, 한도 400 (P2·S4)

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/OrgGraph.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/GroupGraphTooLargeException.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`diffAndApply`, `withoutCycleCreatingEdges`·`CycleScan`·`reaches`·`childIdsOf`·`stripType`·`tuplesOf`·`MAX_GRAPH_EXPANSIONS` 삭제, 클래스 자바독)
- Modify: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java` (`findChildGroupIds` 삭제)
- Modify: `core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java` (`findChildGroupIds` 삭제, `findGroupIdsContainingCalls`·`쓴순서` 추가)
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java` (`findChildGroupIds` 삭제)
- Modify: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java` (`findChildGroupIds` 테스트 삭제)
- Modify: `app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java`, `core/src/test/java/dev/starryeye/organization/core/usecase/WriteDecisionLockInvariantTest.java` (`findChildGroupIds` 줄 삭제)
- Modify: `connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java`
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCycleTest.java`
- Modify: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCaseTest.java`, `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncReadScopeTest.java`
- Test: `connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java`

**Interfaces:**
- Consumes: `GroupEdge`, `TupleMapper.toTuplesKeepingCycles` (Task 5), `findCutEdges`·`changeCutEdges` (Task 6).
- Produces: `final class OrgGraph` (package-private) — `OrgGraph(DirectoryStateRepository)`, `Mono<Set<GroupEdge>> 보류()`,
  `Mono<Set<GroupEdge>> 필요하면_보류(Set<RelationTuple>, Set<RelationTuple>)`, `Mono<Boolean> 순환인가(GroupEdge)`,
  `Mono<거른결과> 거른다(Set<RelationTuple> actual, Set<RelationTuple> after, String focus)`, `void 보류에서_뺀다(GroupEdge)`,
  `static final Comparator<GroupEdge> 아이디순`, `static final int MAX_EXPANSIONS = 10_000`, `record 거른결과(Set<RelationTuple> 남길것, Set<GroupEdge> 새로_보류, Set<GroupEdge> 풀린_보류)`
  + `Set<GroupEdge> 뺄것(Set<RelationTuple> actual, TupleWriteResult result)`.
  `public class GroupGraphTooLargeException extends RuntimeException`. 가짜: `public final List<String> findGroupIdsContainingCalls`, `public final List<String> 쓴순서`.

- [ ] **Step 1: 가짜에 계측을 더한다**

`FakeStateRepository`:

```java
    /** {@link #findGroupIdsContaining} 이 받은 멤버의 아이디 — 순환 검사가 위로 몇 조직을 읽는지 단언한다. */
    public final List<String> findGroupIdsContainingCalls = new ArrayList<>();

    /** 쓰기 순서 — {@code saveGroup:<id>}, {@code saveGroupChange:<id>}, {@code 보류+}, {@code 보류-}. 보류 줄이 멤버 줄보다 먼저인지 단언한다. */
    public final List<String> 쓴순서 = new ArrayList<>();
```

`findGroupIdsContaining` 이 `Flux.defer` 안에서 `findGroupIdsContainingCalls.add(ref.id())` 를 기록하게 하고, `saveGroup` 은 `쓴순서.add("saveGroup:" + group.id())`,
4인자 `saveGroupChange` 는 `쓴순서.add("saveGroupChange:" + after.id())`, `changeCutEdges` 는 `added` 가 비어 있지 않으면 `"보류+"`, `removed` 가 비어 있지 않으면 `"보류-"`
를 더한다. `findChildGroupIds` 와 `findChildGroupIdsCalls` 는 지운다.

- [ ] **Step 2: 실패하는 테스트를 쓴다**

새 `IncrementalSyncCycleTest`(셋업은 `IncrementalSyncUseCaseTest` 와 같은 모양 — `state`·`writer`·`checker`·`lock`·`useCase`, `조직(code, members…)` 도우미):

```java
/** 위로 올라가는 순환 검사와 보류 목록(설계 2026-10-03 §4.2~§4.4, 점검 P2·S4). */
class IncrementalSyncCycleTest {

    // 셋업·도우미는 IncrementalSyncUseCaseTest 와 같다. checker.allowed 에는 각 테스트가 OpenFGA 에 있다고 볼 튜플을 넣는다.

    @Test
    @DisplayName("순환을 닫는 연결은 쓰지 않고 보류 목록에 적는다")
    void 순환을_닫는_연결은_보류한다() {
        // given — A ⊃ B ⊃ C
        state.groups.put("C", 조직("C"));
        state.groups.put("B", 조직("B", MemberRef.group("C")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        checker.allowed.addAll(Set.of(RelationTuple.child("C", "B"), RelationTuple.child("B", "A")));

        // when — C 에 A 를 넣어 A → B → C → A 를 닫으려 한다
        var result = useCase.changeGroup("C", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).doesNotContain(RelationTuple.child("A", "C"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("C", "A"));
        assertThat(state.groups.get("C").members()).contains(MemberRef.group("A"));
    }

    @Test
    @DisplayName("조직이 자기 자신을 하위 조직으로 넣으면 그 연결을 보류한다")
    void 자기_자신을_넣는_연결은_보류한다() {
        // given
        state.groups.put("DEV", 조직("DEV"));

        // when
        var result = useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(MemberRef.group("DEV")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).isEmpty();
        assertThat(state.cutEdges).containsExactly(new GroupEdge("DEV", "DEV"));
    }

    @Test
    @DisplayName("순환 검사는 자손을 읽지 않고 부모에서 위로 올라간다")
    void 자손을_읽지_않는다() {
        // given — HQ 아래 자손 200개, 새 상위 NEWTOP
        Set<MemberRef> 부서 = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            Set<MemberRef> 팀 = new LinkedHashSet<>();
            for (int j = 0; j < 10; j++) {
                state.groups.put("T" + i + "_" + j, 조직("T" + i + "_" + j));
                팀.add(MemberRef.group("T" + i + "_" + j));
            }
            state.groups.put("D" + i, new DirectoryGroup("D" + i, "D" + i, "부서", 팀));
            부서.add(MemberRef.group("D" + i));
        }
        state.groups.put("HQ", new DirectoryGroup("HQ", "HQ", "본부", 부서));
        state.groups.put("NEWTOP", 조직("NEWTOP"));
        state.findGroupIdsContainingCalls.clear();

        // when
        var result = useCase.changeGroup("NEWTOP", GroupChange.delta().adding(Set.of(MemberRef.group("HQ")))).block();

        // then — NEWTOP 의 조상만 본다(없음). 자손 220개를 읽지 않는다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("HQ", "NEWTOP"));
        assertThat(state.findGroupIdsContainingCalls).containsExactly("NEWTOP");
    }

    @Test
    @DisplayName("이미 보류한 연결을 지나는 경로는 순환으로 보지 않는다 — 튜플 그래프로 본다")
    void 보류한_연결을_지나는_경로는_순환이_아니다() {
        // given — 멤버 줄 B ⊃ A 는 보류돼 튜플이 없다
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));

        // when — A 에 B 를 넣는다. 멤버 줄로는 B → A 가 있어 순환처럼 보이지만 튜플 그래프에는 없다
        var result = useCase.changeGroup("A", GroupChange.delta().adding(Set.of(MemberRef.group("B")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("B", "A"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("한 홉 순환이 있는 새 조직 POST 는 먼저 저장된 연결이 남고 새 연결이 보류된다(점검 S4)")
    void 한_홉_순환은_먼저_저장된_연결이_이긴다() {
        // given — B 가 아직 없는 A 를 하위 조직으로 적어 두었다
        state.groups.put("B", 조직("B", MemberRef.group("A")));

        // when — A 를 만들며 B 를 하위 조직으로 넣는다
        var result = useCase.createGroup(조직("A", MemberRef.group("B"))).block();

        // then — "A 는 B 의 하위"가 남고 "B 는 A 의 하위"가 보류된다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B")).doesNotContain(RelationTuple.child("B", "A"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("A", "B"));
    }

    @Test
    @DisplayName("한 홉 순환의 결과는 조직코드 순서와 무관하다")
    void 한_홉_순환은_조직코드_순서와_무관하다() {
        // given — 이름 순서를 뒤집는다: A 가 아직 없는 Z 를 적어 두었다
        state.groups.put("A", 조직("A", MemberRef.group("Z")));

        // when
        var result = useCase.createGroup(조직("Z", MemberRef.group("A"))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("Z", "A")).doesNotContain(RelationTuple.child("A", "Z"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("Z", "A"));
    }

    @Test
    @DisplayName("새로 보류할 줄은 멤버 줄보다 먼저 쓴다")
    void 보류_줄을_먼저_쓴다() {
        // given
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        checker.allowed.add(RelationTuple.child("A", "B"));
        state.쓴순서.clear();

        // when — A 에 B 를 넣어 순환을 닫는다
        useCase.changeGroup("A", GroupChange.delta().adding(Set.of(MemberRef.group("B")))).block();

        // then
        assertThat(state.쓴순서).containsSubsequence("보류+", "saveGroupChange:A");
    }

    @Test
    @DisplayName("보류했던 연결을 요청이 다시 언급하고 이제 순환이 아니면 쓰고 목록에서 뺀다")
    void 다시_언급된_보류_연결은_순환이_풀렸으면_쓴다() {
        // given — 멤버 줄 B ⊃ A 가 보류돼 있고, 순환을 만들던 A ⊃ B 는 이미 없다
        state.groups.put("A", 조직("A"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));

        // when — B 에 A 를 다시 넣는다(이미 멤버)
        var result = useCase.changeGroup("B", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("상태 기준선은 보류한 연결을 뺀다 — 보류 연결이 OpenFGA 에 없는 것을 어긋남으로 세지 않는다")
    void 보류_연결은_어긋남이_아니다() {
        // given
        List<int[]> 관측 = new ArrayList<>();
        useCase = new IncrementalSyncUseCase(state, writer, checker, lock, Duration.ZERO,
                (extra, missing) -> 관측.add(new int[]{extra, missing}), LockObserver.NOOP);
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));

        // when — 같은 멤버로 다시 저장(전체 비교 경로)
        useCase.upsertGroup(조직("A", MemberRef.group("B"))).block();

        // then
        assertThat(관측).isEmpty();
    }

    @Test
    @DisplayName("하위 조직 연결이 없는 요청은 보류 목록을 읽지 않는다")
    void 직원만_바뀌면_보류_목록을_읽지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));
        state.groups.put("DEV", 조직("DEV"));

        // when
        useCase.changeGroup("DEV", GroupChange.delta().adding(Set.of(MemberRef.user("kim")))).block();

        // then
        assertThat(state.findCutEdgesCalls).isZero();
    }

    @Test
    @DisplayName("조상이 한도를 넘으면 추측하지 않고 GroupGraphTooLargeException 으로 끝난다 — 아무것도 쓰지 않는다")
    void 한도를_넘으면_예외다() {
        // given — G1 ⊃ G2 ⊃ … ⊃ G10101 (G10101 의 조상이 10,100개)
        int 깊이 = OrgGraph.MAX_EXPANSIONS + 100;
        for (int i = 1; i <= 깊이; i++) {
            state.groups.put("G" + i, 조직("G" + i, MemberRef.group("G" + (i + 1))));
        }
        state.groups.put("G" + (깊이 + 1), 조직("G" + (깊이 + 1)));
        state.groups.put("NEW", 조직("NEW"));

        // when — 맨 아래에 새 하위 조직을 붙이면 위로 사슬 전체를 올라가야 한다
        var 실행 = useCase.changeGroup("G" + (깊이 + 1), GroupChange.delta().adding(Set.of(MemberRef.group("NEW"))));

        // then
        assertThatThrownBy(실행::block)
                .isInstanceOf(GroupGraphTooLargeException.class)
                .hasMessageContaining("조직 계층이 너무 크다");
        assertThat(writer.appliedDeltas).isEmpty();
    }
}
```

`IncrementalSyncUseCaseTest` 의 `순환_검사는_상한을_넘기면_실패한다` 와 `순환_검사는_같은_조직을_다시_읽지_않는다` 를 지운다(위 `한도를_넘으면_예외다`·아래 캐시 테스트가
대신한다). 캐시 테스트를 새로 둔다:

```java
    @Test
    @DisplayName("한 요청의 여러 새 연결이 같은 조상을 봐도 저장소는 한 번만 읽는다")
    void 순환_검사는_같은_조직을_다시_읽지_않는다() {
        // given
        state.saveGroup(조직("ROOT", MemberRef.group("TOP"))).block();
        state.saveGroup(조직("TOP")).block();
        state.saveGroup(조직("X")).block();
        state.saveGroup(조직("Y")).block();
        openFga를_상태와_맞춘다();
        state.findGroupIdsContainingCalls.clear();

        // when — TOP 에 X, Y 를 넣는다. 두 연결 모두 TOP 에서 위로 올라간다
        var result = useCase.changeGroup("TOP", GroupChange.delta().adding(Set.of(MemberRef.group("X"), MemberRef.group("Y")))).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.findGroupIdsContainingCalls).filteredOn("TOP"::equals).hasSize(1);
        assertThat(state.findGroupIdsContainingCalls).filteredOn("ROOT"::equals).hasSize(1);
    }
```

`IncrementalSyncReadScopeTest.참조한_하위_조직을_통째로_읽지_않는다` 의 마지막 단언을 바꾼다:

```java
        assertThat(state.findGroupIdsContainingCalls).as("순환 검사가 하위 조직 쪽으로 내려가지 않는다").doesNotContain(대형조직);
```

`ScimGroupHandlerTest`:

```java
    @Test
    @DisplayName("조직 계층이 순환 검사 한도를 넘으면 400 invalidValue 다 — 다시 보내도 늘 넘는다")
    void 계층이_너무_크면_400이다() {
        // given — G1 ⊃ … ⊃ G10101
        int 깊이 = 10_100;
        for (int i = 1; i <= 깊이; i++) {
            state.groups.put("G" + i, new DirectoryGroup("G" + i, "G" + i, "G" + i, Set.of(MemberRef.group("G" + (i + 1)))));
        }
        state.groups.put("G" + (깊이 + 1), new DirectoryGroup("G" + (깊이 + 1), "G" + (깊이 + 1), "맨 아래", Set.of()));
        state.groups.put("NEW", new DirectoryGroup("NEW", "NEW", "새 조직", Set.of()));
        String body = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                 "Operations":[{"op":"add","path":"members","value":[{"value":"NEW","type":"Group"}]}]}
                """;

        // when, then
        client.patch().uri("/scim/v2/Groups/G" + (깊이 + 1))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.scimType").isEqualTo("invalidValue");
    }
```

- [ ] **Step 3: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncCycleTest' --tests '*IncrementalSyncUseCaseTest' --tests '*IncrementalSyncReadScopeTest'`
Expected: 컴파일 실패 — `OrgGraph`·`GroupGraphTooLargeException`·`findGroupIdsContainingCalls` 가 없다.

- [ ] **Step 4: `GroupGraphTooLargeException`**

```java
package dev.starryeye.organization.core.usecase;

/**
 * 조직 계층이 순환 검사 한도를 넘었다(설계 2026-10-03 §4.2, 점검 P2). 같은 요청은 다시 보내도 늘 넘으므로 호출자는 400 {@code invalidValue}(영구 거절)로
 * 옮긴다 — 500 이면 IdP 가 같은 실패를 되풀이한다.
 */
public class GroupGraphTooLargeException extends RuntimeException {

    public GroupGraphTooLargeException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: `OrgGraph`**

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.model.GroupEdge;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 요청 하나 동안 보는 <b>튜플 그래프</b> — 하위 조직 멤버 줄에서 보류 목록을 뺀 것(설계 2026-10-03 §4.1). 순환 검사(§4.2)와 보류 판단(§4.3)을 한다.
 *
 * <p><b>왜 튜플 그래프인가.</b> 순환이라 쓰지 않은 연결도 멤버 줄은 남는다 — 멤버십은 IdP 가 보낸 사실이다. 그래서 멤버 줄의 그래프에는 순환이 있고 OpenFGA 에는
 * 없다. 멤버 줄로 순환을 보면 이미 보류한 연결을 지나는 경로 때문에 멀쩡한 새 연결까지 버린다.
 *
 * <p><b>위로 올라간다(점검 P2).</b> 새 연결 P ⊃ c 는 c 가 P 자신이거나 P 의 조상이면 순환이다. 조상은 보통 조직도 깊이 × 다중 부모 수라, 자손 수천 개를
 * 훑던 옛 검사보다 훨씬 적게 읽는다. 펼친 조직 수가 {@value #MAX_EXPANSIONS} 를 넘으면 {@link GroupGraphTooLargeException} 이다.
 *
 * <p>요청 하나에 하나 만든다. 보류 목록과 조직마다의 부모 목록을 요청 안에서 한 번만 읽는다 — 요청 안에서는 락이 상태를 고정한다. 요청 안에서 보류·해제한
 * 연결은 같은 요청의 뒤 검사에 반영된다.
 */
@Slf4j
final class OrgGraph {

    static final int MAX_EXPANSIONS = 10_000;

    static final Comparator<GroupEdge> 아이디순 = Comparator.comparing(GroupEdge::parent).thenComparing(GroupEdge::child);

    private static final String GROUP_PREFIX = RelationTuple.GROUP_TYPE + ":";

    /**
     * @param 남길것   순환을 만드는 새 연결을 뺀 목표
     * @param 새로_보류 이번에 보류 목록에 넣을 연결(이미 목록에 있던 것은 빼고)
     * @param 풀린_보류 목록에 있었지만 이제 목표에 남는 연결 — OpenFGA 에 실제로 있게 된 것만 목록에서 뺀다({@link #뺄것})
     */
    record 거른결과(Set<RelationTuple> 남길것, Set<GroupEdge> 새로_보류, Set<GroupEdge> 풀린_보류) {

        /** 풀린 보류 중 OpenFGA 에 이제 있는 것 — 원래 있었거나 이번에 썼다. 쓰기가 실패한 연결은 목록에 남긴다(설계 §4.4). */
        Set<GroupEdge> 뺄것(Set<RelationTuple> actual, TupleWriteResult result) {
            return 풀린_보류.stream()
                    .filter(edge -> actual.contains(edge.tuple()) || result.written().contains(edge.tuple()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private final DirectoryStateRepository state;
    private final Map<String, Set<String>> 부모들 = new HashMap<>();
    private Mono<Set<GroupEdge>> 보류;
    /** 읽은 보류 목록 — {@link #보류에서_뺀다} 가 고친다. 아직 안 읽었으면 null. */
    private Set<GroupEdge> 읽은보류;
    private int budget = MAX_EXPANSIONS;

    OrgGraph(DirectoryStateRepository state) {
        this.state = state;
    }

    /** 보류 목록. 요청 안에서 한 번만 읽고, 같은 요청의 보류·해제가 이 집합에 반영된다. */
    Mono<Set<GroupEdge>> 보류() {
        if (보류 == null) {
            보류 = state.findCutEdges()
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .<Set<GroupEdge>>map(set -> set)
                    .doOnNext(set -> 읽은보류 = set)
                    .cache();
        }
        return 보류;
    }

    /** 이 튜플들에 하위 조직 연결이 있을 때만 보류 목록을 읽는다 — 직원 연산·직원만 바뀌는 조직 연산은 읽지 않는다. */
    Mono<Set<GroupEdge>> 필요하면_보류(Set<RelationTuple> 앞, Set<RelationTuple> 뒤) {
        boolean 연결있음 = Stream.concat(앞.stream(), 뒤.stream())
                .anyMatch(tuple -> RelationTuple.CHILD.equals(tuple.relation()));
        return 연결있음 ? 보류() : Mono.just(Set.of());
    }

    /** {@code edge} 가 튜플 그래프에 순환을 만드는가 — 자식이 부모 자신이거나 부모의 조상이다. */
    Mono<Boolean> 순환인가(GroupEdge edge) {
        if (edge.parent().equals(edge.child())) {
            return Mono.just(true);
        }
        return 보류().flatMap(cut -> {
            Set<String> visited = new HashSet<>();
            visited.add(edge.parent());
            // 너비 우선 확장을 Flux.expand 에 맡긴다 — 깊은 사슬에서도 스택을 쓰지 않고, any 가 목표를 만나는 즉시 멈춘다.
            return Flux.just(edge.parent())
                    .expand(id -> 튜플_그래프의_부모들(id, cut).flatMapIterable(ids -> ids).filter(visited::add))
                    .any(edge.child()::equals);
        });
    }

    /** 보류 목록에서 뺀다 — 같은 요청의 뒤 검사가 이 연결을 그래프에 있는 것으로 본다. */
    void 보류에서_뺀다(GroupEdge edge) {
        if (읽은보류 != null) {
            읽은보류.remove(edge);
        }
    }

    /**
     * {@code after} 의 하위 조직 연결 중 OpenFGA 에 아직 없는 것을 순서대로 검사한다(설계 2026-10-03 §4.3) — 순환을 만들면 남길 것에서 빼고 보류한다.
     * 이미 OpenFGA 에 있는 연결은 검사하지 않는다(먼저 저장된 연결이 이긴다). 하위 조직 연결이 없으면 보류 목록을 읽지 않는다.
     *
     * <p>순서: 자식이 초점 조직인 연결(상위 조직이 먼저 적어 둔 연결)을 먼저, 그다음 나머지. 각각 아이디 순.
     */
    Mono<거른결과> 거른다(Set<RelationTuple> actual, Set<RelationTuple> after, String focus) {
        List<GroupEdge> 연결 = after.stream().map(GroupEdge::of).flatMap(Optional::stream).toList();
        if (연결.isEmpty()) {
            return Mono.just(new 거른결과(after, Set.of(), Set.of()));
        }
        String 초점조직 = focus.startsWith(GROUP_PREFIX) ? focus.substring(GROUP_PREFIX.length()) : "";
        return 보류().flatMap(cut -> {
            Set<GroupEdge> 풀린 = new LinkedHashSet<>();
            List<GroupEdge> 새연결 = new ArrayList<>();
            for (GroupEdge edge : 연결) {
                if (!actual.contains(edge.tuple())) {
                    새연결.add(edge);
                } else if (cut.contains(edge)) {
                    풀린.add(edge);
                }
            }
            새연결.sort(Comparator.comparing((GroupEdge edge) -> edge.child().equals(초점조직) ? 0 : 1).thenComparing(아이디순));

            Set<RelationTuple> 남길것 = new LinkedHashSet<>(after);
            Set<GroupEdge> 새로_보류 = new LinkedHashSet<>();
            return Flux.fromIterable(새연결)
                    .concatMap(edge -> 순환인가(edge).doOnNext(순환 -> {
                        if (순환) {
                            log.warn("튜플 변환 경고: 조직 '{}' → '{}' 간선이 순환을 만들어 보류합니다(보류 목록에 적음)", edge.parent(), edge.child());
                            남길것.remove(edge.tuple());
                            if (cut.add(edge)) {
                                새로_보류.add(edge);
                            }
                        } else if (cut.remove(edge)) {
                            풀린.add(edge);
                        }
                    }))
                    .then(Mono.fromSupplier(() -> new 거른결과(남길것, 새로_보류, 풀린)));
        });
    }

    /** 튜플 그래프에서 {@code id} 의 부모들 — 소속 줄(강한 일관성)에서 보류 연결을 뺀다. 요청 안에서 조직마다 한 번만 읽고, 읽을 때마다 예산을 쓴다. */
    private Mono<List<String>> 튜플_그래프의_부모들(String id, Set<GroupEdge> cut) {
        Set<String> cached = 부모들.get(id);
        Mono<Set<String>> 멤버_줄의_부모들;
        if (cached != null) {
            멤버_줄의_부모들 = Mono.just(cached);
        } else {
            if (budget-- <= 0) {
                return Mono.error(new GroupGraphTooLargeException(
                        "조직 계층이 너무 크다 — 순환 검사가 %d개 조직을 넘겼습니다: %s".formatted(MAX_EXPANSIONS, id)));
            }
            멤버_줄의_부모들 = state.findGroupIdsContaining(MemberRef.group(id))
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .<Set<String>>map(set -> set)
                    .doOnNext(set -> 부모들.put(id, set));
        }
        return 멤버_줄의_부모들.map(parents -> parents.stream()
                .filter(parent -> !cut.contains(new GroupEdge(parent, id)))
                .toList());
    }
}
```

(`보류에서_뺀다` 는 Task 8 이 쓴다. `TupleWriteResult` 의 실제 패키지는 `core.model` 이다 — import 를 맞춘다.)

- [ ] **Step 6: `diffAndApply` 를 바꾸고 옛 순환 검사를 지운다**

```java
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
            OrgGraph 그래프 = new OrgGraph(state);

            return checker.existing(candidates).flatMap(actual -> 그래프.필요하면_보류(있어야했던것, 원하는것).flatMap(보류 -> {
                // (지금 주석 그대로) 상태 기준선과 Check 기준선을 비교한다 — 보류한 연결은 상태 기준선에서 뺀다
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
                        return 반영하고_커밋한다(계산, lease, result -> 보류와_함께_커밋한다(거름, actual, result, actual, after, commit));
                    }
                    // (지금 주석 그대로) 빠지는 멤버의 줄은 Check 없이 "없으면 무시"로 지운다
                    Set<RelationTuple> 지울것 = new LinkedHashSet<>(계산.toDelete());
                    지울것.addAll(확인없이_지울것);
                    Set<RelationTuple> 있다고_볼것 = new LinkedHashSet<>(actual);
                    있다고_볼것.addAll(확인없이_지울것);
                    return 반영하고_커밋한다(new TupleDelta(계산.toWrite(), 지울것), lease,
                            result -> 보류와_함께_커밋한다(거름, actual, result, 있다고_볼것, after, commit));
                });
            }));
        });
    }

    /**
     * 커밋 앞뒤로 보류 목록을 맞춘다(설계 2026-10-03 §4.4). 새로 보류할 줄을 <b>멤버 줄보다 먼저</b> 쓴다 — 거꾸로면 멈춘 뒤 "멤버 줄은 있는데 튜플도 보류 기록도
     * 없는" 연결이 남아 영영 쓰이지 않는다(점검 M1 과 같은 누락). 풀린 줄은 OpenFGA 에 실제로 있게 된 것만 커밋 뒤에 뺀다.
     */
    private Mono<Void> 보류와_함께_커밋한다(OrgGraph.거른결과 거름, Set<RelationTuple> actual, TupleWriteResult result,
                                    Set<RelationTuple> 기준, Set<RelationTuple> 목표, Commit commit) {
        return state.changeCutEdges(거름.새로_보류(), Set.of())
                .then(Mono.defer(() -> commit.apply(result, 기준, 목표)))
                .then(Mono.defer(() -> state.changeCutEdges(Set.of(), 거름.뺄것(actual, result))));
    }

    /** 순환을 버리지 않은 튜플. 순환이 아닌 경고(없는 멤버 등)는 지금처럼 남긴다. */
    private Set<RelationTuple> 순환을_버리지_않고(DirectorySnapshot snapshot) {
        var mapping = TupleMapper.toTuplesKeepingCycles(snapshot);
        mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));
        return mapping.tuples();
    }
```

`withoutCycleCreatingEdges`·`CycleScan`·`reaches`·`childIdsOf`·`stripType`·`tuplesOf`·`MAX_GRAPH_EXPANSIONS` 를 지운다. 클래스 자바독의 "스냅샷이 볼 수 없는 것 2 — 비순환
보장" 문단을 고친다: 새로 생기는 child 엣지마다 `OrgGraph` 가 저장소의 **튜플 그래프(멤버 줄 − 보류 목록)** 를 위로 올라가 확인하고, 순환을 닫는 엣지는 보류 목록에
적는다. `diffAndApply` 자바독의 "설계 §7.2 와의 의도적 차이" 등은 그대로 두고 "보류 목록은 커밋 앞뒤로 맞춘다(설계 2026-10-03 §4.4)" 한 문장을 더한다.

- [ ] **Step 7: `findChildGroupIds` 를 없앤다**

포트·DynamoDB 구현·저장소 테스트(`findChildGroupIds` 를 부르는 테스트 하나)·`LdapInterruptedSyncScaleTest` 위임·`WriteDecisionLockInvariantTest` 감시에서 지운다.

- [ ] **Step 8: `ScimRouter` 400 번역**

`toScimError` 의 `DirectoryConflictException` 분기 아래:

```java
        // 조직 계층이 순환 검사 한도를 넘었다 — 같은 요청은 다시 보내도 늘 넘으므로 영구 거절(설계 2026-10-03 §4.2, 점검 P2). 500 이면 IdP 가 같은 실패를 되풀이한다.
        if (error instanceof GroupGraphTooLargeException tooLarge) {
            return write(HttpStatus.BAD_REQUEST, "invalidValue", tooLarge.getMessage());
        }
```

- [ ] **Step 9: 통과를 본다**

Run: `./gradlew :core:test` 그다음 `./gradlew :connector-scim:test` 그다음 `./gradlew :storage-dynamodb:test --tests '*DynamoDbDirectoryStateRepositoryTest'`
그다음 `./gradlew :app-ldap:compileTestJava :app-scim:compileTestJava`
Expected: PASS. `GroupChangeEquivalenceTest`·`IncrementalSyncUseCaseTest` 의 기존 순환 테스트(엣지를 쓰지 않는다)는 그대로 통과해야 한다 — 실패하면 단언이 아니라
구현을 의심한다.

- [ ] **Step 10: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/ core/src/testFixtures/java/dev/starryeye/organization/core/fake/FakeStateRepository.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ \
  storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java \
  storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java \
  app-ldap/src/test/java/dev/starryeye/organization/ldap/app/LdapInterruptedSyncScaleTest.java \
  connector-scim/src/main/java/dev/starryeye/organization/scim/ScimRouter.java \
  connector-scim/src/test/java/dev/starryeye/organization/scim/ScimGroupHandlerTest.java
git commit -F - <<'EOF'
feat: 순환 검사는 튜플 그래프를 위로 올라간다(점검 P2·S4) — 보류 목록을 뺀 그래프, 먼저 저장된 연결이 이김, 보류 줄을 멤버 줄보다 먼저, 한도 초과는 400 invalidValue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 8: M1 — 하위 조직 연결을 지운 요청 끝에서 보류 목록을 다시 본다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java` (`보류를_다시_본다`, `changeGroupInternal`·`removeGroupInternal` 끝)
- Create: `core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCycleRestoreTest.java`

**Interfaces:**
- Consumes: `OrgGraph` (`보류()`, `순환인가`, `보류에서_뺀다`, `아이디순`) (Task 7), `findCutEdges`·`changeCutEdges` (Task 6), `findMembers`.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
/** 순환이 풀리면 보류했던 연결을 쓴다(설계 2026-10-03 §4.5, 점검 M1). 셋업·도우미는 IncrementalSyncUseCaseTest 와 같다. */
class IncrementalSyncCycleRestoreTest {

    @Test
    @DisplayName("점검 재현 — 본부 ⊃ A ⊃ B 에서 B 를 A 위로 올리는 순서가 꼬여도 결국 'A 는 B 의 하위'가 쓰인다")
    void 순환이_풀리면_보류했던_연결을_쓴다() {
        // given — 본부 ⊃ A ⊃ B
        state.groups.put("B", 조직("B"));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.groups.put("HQ", 조직("HQ", MemberRef.group("A")));
        checker.allowed.addAll(Set.of(RelationTuple.child("B", "A"), RelationTuple.child("A", "HQ")));

        // when — 개편: B 에 A 를 넣고(순환이라 보류), 그다음 A 에서 B 를 뺀다
        useCase.changeGroup("B", GroupChange.delta().adding(Set.of(MemberRef.group("A")))).block();
        var 보류된것 = Set.copyOf(state.cutEdges);
        var result = useCase.changeGroup("A", GroupChange.delta().removingId("B")).block();

        // then
        assertThat(보류된것).containsExactly(new GroupEdge("B", "A"));
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("멤버 줄이 없는 보류 줄은 다음 지우기 요청 끝에서 지운다")
    void 멤버_줄이_없는_보류_줄은_지운다() {
        // given — 이미 지워진 조직 GONE 의 보류 줄이 남아 있다
        state.cutEdges.add(new GroupEdge("GONE", "X"));
        state.groups.put("X", 조직("X"));
        state.groups.put("P", 조직("P", MemberRef.group("Q")));
        state.groups.put("Q", 조직("Q"));
        checker.allowed.add(RelationTuple.child("Q", "P"));

        // when — 아무 하위 조직 빼기
        useCase.changeGroup("P", GroupChange.delta().removingId("Q")).block();

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("아직 순환이면 보류 줄을 그대로 둔다")
    void 아직_순환이면_그대로_둔다() {
        // given — A ⊃ B ⊃ C 이고 C ⊃ A 가 보류돼 있다. 상관없는 D 를 B 에서 뺀다
        state.groups.put("C", 조직("C", MemberRef.group("A")));
        state.groups.put("D", 조직("D"));
        state.groups.put("B", 조직("B", MemberRef.group("C"), MemberRef.group("D")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("C", "A"));
        checker.allowed.addAll(Set.of(RelationTuple.child("B", "A"), RelationTuple.child("C", "B"), RelationTuple.child("D", "B")));

        // when
        useCase.changeGroup("B", GroupChange.delta().removingId("D")).block();

        // then
        assertThat(writer.written).doesNotContain(RelationTuple.child("A", "C"));
        assertThat(state.cutEdges).containsExactly(new GroupEdge("C", "A"));
    }

    @Test
    @DisplayName("한 요청이 하위 조직 하나를 빼고 다른 하나를 넣어도 끝에서 다시 본다")
    void 빼고_넣는_요청도_끝에서_다시_본다() {
        // given — A ⊃ B, B ⊃ A 는 보류. A 에서 B 를 빼며 E 를 넣는다(전체 교체)
        state.groups.put("E", 조직("E"));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));

        // when
        var result = useCase.changeGroup("A", GroupChange.replacement("A", "A", Set.of(MemberRef.group("E")))).block();

        // then — E 는 지금 그래프로 검사돼 쓰이고, 순환이 풀린 B ⊃ A 도 쓰인다
        assertThat(result.fullyApplied()).isTrue();
        assertThat(writer.written).contains(RelationTuple.child("E", "A"), RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("다시 검사의 쓰기가 실패해도 원래 요청은 성공하고 보류 줄은 남는다")
    void 다시_검사가_실패해도_원래_요청은_성공한다() {
        // given — 첫 테스트와 같은 개편, 되살릴 쓰기만 실패한다
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.groups.put("A", 조직("A", MemberRef.group("B")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.add(RelationTuple.child("B", "A"));
        writer.failFor(tuple -> tuple.equals(RelationTuple.child("A", "B")));

        // when
        var result = useCase.changeGroup("A", GroupChange.delta().removingId("B")).block();

        // then
        assertThat(result.fullyApplied()).isTrue();
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("직원만 빼는 요청은 보류 목록을 읽지 않는다")
    void 직원만_빼면_다시_보지_않는다() {
        // given
        state.users.put("kim", new DirectoryUser("kim", "kim", "kim", "김", null, true));
        state.groups.put("DEV", 조직("DEV", MemberRef.user("kim")));
        checker.allowed.add(RelationTuple.directMember("kim", "DEV"));

        // when
        useCase.changeGroup("DEV", GroupChange.delta().removingId("kim")).block();

        // then
        assertThat(state.findCutEdgesCalls).isZero();
    }

    @Test
    @DisplayName("조직을 지운 뒤에도 다시 본다 — 그 조직이 막던 순환이 풀린다")
    void 조직_삭제_뒤에도_다시_본다() {
        // given — A ⊃ M ⊃ B 이고 B ⊃ A 가 보류돼 있다. M 을 지우면 순환이 풀린다
        state.groups.put("M", 조직("M", MemberRef.group("B")));
        state.groups.put("A", 조직("A", MemberRef.group("M")));
        state.groups.put("B", 조직("B", MemberRef.group("A")));
        state.cutEdges.add(new GroupEdge("B", "A"));
        checker.allowed.addAll(Set.of(RelationTuple.child("M", "A"), RelationTuple.child("B", "M")));

        // when
        useCase.removeGroup("M").block();

        // then
        assertThat(writer.written).contains(RelationTuple.child("A", "B"));
        assertThat(state.cutEdges).isEmpty();
    }
}
```

(`GroupChange.removingId(id)` 는 조직 PATCH 의 `members[value eq …]` 빼기다 — 아이디가 하위 조직이면 하위 조직을 뺀다. `GroupChange.replacement` 의 실제 인자는
`GroupChange` 를 따른다.)

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*IncrementalSyncCycleRestoreTest'`
Expected: FAIL — 보류 줄이 그대로이고 `child(A, B)` 가 쓰이지 않는다.

- [ ] **Step 3: `보류를_다시_본다`**

```java
    /**
     * 하위 조직 연결을 지운 요청 끝에서 보류 목록을 다시 본다(설계 2026-10-03 §4.5, 점검 M1). 순환은 연결을 지울 때만 풀린다. 멤버 줄이 없는 보류 줄은 지우고,
     * 이제 순환이 아닌 연결은 리스를 확인한 뒤 튜플을 쓰고 목록에서 뺀다. 보류 목록은 보통 비어 있어 읽기 1번으로 끝난다.
     *
     * <p><b>원래 요청을 실패시키지 않는다.</b> 원래 연산은 이미 커밋됐다. 여기서 난 오류(OpenFGA·DynamoDB·리스 확인)는 경고만 남기고 목록을 그대로 둔다 — 다음 지우기
     * 요청이나 재적재가 다시 본다.
     */
    private Mono<Void> 보류를_다시_본다(LockLease lease) {
        OrgGraph 그래프 = new OrgGraph(state);
        return 그래프.보류()
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
```

- [ ] **Step 4: 두 입구의 끝에 건다**

`changeGroupInternal` — `diffAndApply(...)` 의 결과에:

```java
                    boolean 하위_조직을_뺀다 = 빠질것.stream().anyMatch(member -> member.type() == MemberType.GROUP);
                    return diffAndApply(snapshotOfGroups(Set.of(그림_전)), snapshotOfGroups(Set.of(후)),
                            RelationTuple.groupRef(groupId), 확인없이_지울것, lease, commit)
                            .flatMap(result -> 하위_조직을_뺀다
                                    ? 보류를_다시_본다(lease).thenReturn(result)
                                    : Mono.just(result));
```

`removeGroupInternal` — 그 조직에 하위 조직 멤버가 있거나 상위 조직이 있었으면(연결을 지웠으면):

```java
                        .flatMap(parentIds -> {
                            boolean 연결을_지운다 = !parentIds.isEmpty()
                                    || group.members().stream().anyMatch(member -> member.type() == MemberType.GROUP);
                            return 반영하고_커밋한다(TupleDelta.deleteOnly(조직을_언급하는_튜플(group, parentIds)), lease,
                                    result -> 조직_삭제를_커밋한다(group, parentIds, result))
                                    .flatMap(result -> 연결을_지운다
                                            ? 보류를_다시_본다(lease).thenReturn(result)
                                            : Mono.just(result));
                        })
```

`changeGroup`·`removeGroup` 자바독에 "하위 조직 연결을 지우면 끝에서 보류 목록을 다시 본다(설계 2026-10-03 §4.5)" 한 문장씩.

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/IncrementalSyncUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/IncrementalSyncCycleRestoreTest.java
git commit -F - <<'EOF'
feat: 하위 조직 연결을 지운 요청 끝에서 보류 목록을 다시 본다(점검 M1) — 순환이 풀린 연결을 쓰고, 멤버 줄이 없는 줄은 지우고, 실패는 원래 요청을 막지 않는다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 9: SCIM 재적재·wipe·아카이빙이 보류 목록을 맞춘다

**Files:**
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java`
- Modify: `core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java`

**Interfaces:**
- Consumes: `TupleMapper.toTuples(snapshot, 보류)`·`cutEdges()` (Task 5), `findCutEdges`·`replaceCutEdges` (Task 6).

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`ScimRebuildUseCaseTest`:

```java
    @Test
    @DisplayName("재적재는 순환으로 버린 연결로 보류 목록을 다시 쓴다 — 묵은 줄은 사라진다")
    void 재적재가_보류_목록을_다시_쓴다() {
        // given — A ⊃ B, B ⊃ A. 보류 목록에는 묵은 줄이 있다
        state.saveGroup(조직("A", MemberRef.group("B"))).block();
        state.saveGroup(조직("B", MemberRef.group("A"))).block();
        state.cutEdges.add(new GroupEdge("OLD", "X"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then — 사전순 DFS 가 B ⊃ A 를 버린다
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(state.cutEdges).containsExactly(new GroupEdge("B", "A"));
    }

    @Test
    @DisplayName("조직도에 순환이 없으면 재적재가 보류 목록을 비운다")
    void 순환이_없으면_보류_목록을_비운다() {
        // given
        조직도를_심는다();
        state.cutEdges.add(new GroupEdge("OLD", "X"));

        // when
        재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("wipe 는 보류 목록도 비운다")
    void wipe는_보류_목록을_비운다() {
        // given
        조직도를_심는다();
        state.cutEdges.add(new GroupEdge("DEV001", "DEV002"));

        // when
        재적재한다(ScimRebuildMode.WIPE);

        // then
        assertThat(state.cutEdges).isEmpty();
    }

    @Test
    @DisplayName("조직도가 비어 재적재가 멈추면 보류 목록을 건드리지 않는다")
    void 빈_조직도로_멈추면_보류_목록을_두고_간다() {
        // given — 조직도는 비었는데 장부에 줄이 있다(②-2 의 빈 조직도 가드)
        writer.stored.add(김_백엔드);
        state.cutEdges.add(new GroupEdge("A", "B"));

        // when
        var run = 재적재한다(ScimRebuildMode.TUPLES);

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(state.cutEdges).containsExactly(new GroupEdge("A", "B"));
    }
```

(`writer.stored` 로 장부에 줄을 넣는 방식·`SyncStatus` 위치는 이 파일의 빈 조직도 가드 테스트를 따른다.)

`SnapshotArchiveUseCaseTest`(Logback `ListAppender` 를 `SnapshotArchiveUseCase` 로거에 붙인다 — `IncrementalSyncLeaseTest` 의 방식):

```java
    @Test
    @DisplayName("아카이빙은 보류 목록을 따른다 — 순증이 고른 쪽을 어긋남으로 세지 않는다")
    void 보류_목록을_따른다() {
        // given — A ⊃ B, B ⊃ A. 순증은 "B 는 A 의 하위"(A ⊃ B)를 보류하고 "A 는 B 의 하위"를 썼다
        state.saveGroup(new DirectoryGroup("A", null, "A", Set.of(MemberRef.group("B")))).block();
        state.saveGroup(new DirectoryGroup("B", null, "B", Set.of(MemberRef.group("A")))).block();
        state.cutEdges.add(new GroupEdge("A", "B"));
        checker.allowed.add(RelationTuple.child("A", "B"));

        // when
        var run = useCase.execute().block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(로그.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(message -> message.contains("어긋남"));
    }
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew :core:test --tests '*ScimRebuildUseCaseTest' --tests '*SnapshotArchiveUseCaseTest'`
Expected: FAIL — 보류 목록이 바뀌지 않는다, 아카이빙이 어긋남을 남긴다.

- [ ] **Step 3: 재적재·wipe**

`reloadTuples` 의 `.map(reconciliation -> …)` 를 `.flatMap` 으로:

```java
            return TupleReconciler.reconcile(writer, scanner, mapping.tuples(), 빈_조직도면_멈춘다)
                    .flatMap(reconciliation -> reconciliation.held()
                            ? Mono.just(Mono.just(SyncOutcome.failed(reconciliation.heldReason())))
                            // 재적재는 전체를 정하는 연산이라 보류 목록도 처음부터 다시 정한다(설계 2026-10-03 §4.6)
                            : state.replaceCutEdges(mapping.cutEdges()).thenReturn(commitTuples(reconciliation)));
```

`wipe` 의 `state.replaceWith(DirectorySnapshot.empty())` 를 `state.replaceWith(DirectorySnapshot.empty()).then(state.replaceCutEdges(Set.of()))` 로.
`ScimRebuildUseCase` 클래스·`reloadTuples` 자바독에 "장부를 맞춘 뒤 보류 목록을 버린 연결로 바꾼다" 한 문장.

- [ ] **Step 4: 아카이빙**

```java
    private Mono<SyncOutcome> archive() {
        return Mono.zip(state.loadAll(), state.findCutEdges().collect(Collectors.toSet())).flatMap(both -> {
            DirectorySnapshot directory = both.getT1();
            // 저장된 보류 목록을 따른다 — 순증이 고른 쪽을 어긋남으로 세지 않는다(설계 2026-10-03 §4.6)
            var mapping = TupleMapper.toTuples(directory, both.getT2());
            // (이하 지금 그대로)
```

- [ ] **Step 5: 통과를 본다**

Run: `./gradlew :core:test`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
git add core/src/main/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCase.java \
  core/src/main/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCase.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/ScimRebuildUseCaseTest.java \
  core/src/test/java/dev/starryeye/organization/core/usecase/SnapshotArchiveUseCaseTest.java
git commit -F - <<'EOF'
feat: SCIM 재적재는 보류 목록을 다시 쓰고 wipe 는 비운다, 아카이빙은 저장된 보류 목록을 따른다

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## Task 10: 규모 테스트, README, 점검 문서

**Files:**
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java`
- Modify: `app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java` (S16 주석만)
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-28-full-audit.md`

- [ ] **Step 1: 규모 테스트를 더한다**

`ScimGroupMemberPatchScaleTest` 의 `큰_조직을_지운다` 를 `@Order(11)` 로 옮기고, 그 앞에 다섯을 더한다. 클래스 자바독에 "③-2 의 읽는 양(점검 P1·P2, 들어오는 멤버,
POST 상위 조직)도 같은 10만 명 조직으로 잰다" 한 문장. 직원 시드는 한 번만 한다:

```java
    private static final int 시드_직원 = 7_000;

    private static String 시드(int i) {
        return "s%05d".formatted(i);
    }

    @Test
    @Order(6)
    @DisplayName("type 없는 멤버 1,000명 추가는 판정·직원 읽기를 묶어서 한다(점검 P1 — 전: GetItem 4,002)")
    void type_없는_멤버를_묶어서_판정한다() {
        // given — 직원 7,000명(다음 테스트도 쓴다), 빈 조직 TYPELESS
        Flux.range(0, 시드_직원)
                .flatMap(i -> state.saveUser(new DirectoryUser(시드(i), "ext-" + 시드(i), 시드(i), "시드 " + i, null, true)), 16)
                .blockLast(Duration.ofMinutes(10));
        state.saveGroup(new DirectoryGroup("TYPELESS", "ext-TYPELESS", "type 없는 조직", Set.of())).block();
        String 멤버들 = IntStream.range(0, 1_000).mapToObj(i -> "{\"value\":\"%s\"}".formatted(시드(i)))
                .collect(Collectors.joining(","));
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/TYPELESS").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[%s]}]}
                        """.formatted(멤버들))
                .exchange().expectStatus().isNoContent();

        // then — 판정 키 2,000 + 멤버 확인 1,000 + 직원 1,000. GetItem 은 헤더·락 몇 번뿐
        읽은양을_찍는다("type 없는 1,000명 추가", 시작);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(4_100);
        assertThat(check("user:" + 시드(0), "member", "group:TYPELESS")).isTrue();
        assertThat(check("user:" + 시드(999), "member", "group:TYPELESS")).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("7,000명 전체 교체는 들어오는 직원을 묶어 읽는다")
    void 큰_교체는_직원을_묶어_읽는다() {
        // given
        state.saveGroup(new DirectoryGroup("BIGPUT", "ext-BIGPUT", "큰 교체", Set.of())).block();
        Set<MemberRef> 목표 = new LinkedHashSet<>();
        for (int i = 0; i < 시드_직원; i++) {
            목표.add(MemberRef.user(시드(i)));
        }
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when — 7,000명 본문은 HTTP 한도 근처라 유스케이스를 직접 부른다
        var result = sync.changeGroup("BIGPUT", GroupChange.replacement("ext-BIGPUT", "큰 교체", 목표)).block(Duration.ofMinutes(5));

        // then
        읽은양을_찍는다("7,000명 교체", 시작);
        assertThat(result.fullyApplied()).isTrue();
        assertThat(counter.getItems.get()).as("직원을 한 명씩 읽지 않는다").isLessThanOrEqualTo(20);
        assertThat(counter.batchGetKeys.get()).isLessThanOrEqualTo(시드_직원 + 100);
        assertThat(check("user:" + 시드(6_999), "member", "group:BIGPUT")).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("10만 명 조직이 먼저 적어 둔 조직을 POST 해도 상위 조직을 통째로 읽지 않는다")
    void 큰_상위_조직_밑의_POST는_헤더만_읽는다() {
        // given — ALL(10만 명)이 아직 없는 LATE 를 하위 조직으로 적어 두었다
        GroupHeader 전사 = state.findGroupHeader(조직).block();
        state.saveGroupChange(전사, 전사, Set.of(MemberRef.group("LATE")), Set.of()).block();
        counter.reset();
        checks.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"LATE","displayName":"늦게 온 조직"}
                        """)
                .exchange().expectStatus().isCreated();

        // then
        읽은양을_찍는다("10만 명 상위 조직 밑 POST", 시작);
        assertThat(counter.scannedItems.get()).as("상위 조직 파티션을 훑지 않는다").isLessThanOrEqualTo(50);
        assertThat(counter.getItems.get()).isLessThanOrEqualTo(20);
        assertThat(check("group:LATE", "child", "group:" + 조직)).isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("자손 2,000개 본부를 새 상위 밑에 붙여도 순환 검사는 위로만 올라간다(점검 P2)")
    void 큰_본부를_붙여도_자손을_읽지_않는다() {
        // given — HQ ⊃ D0..D39 ⊃ 각 50팀(자손 2,040개), 새 상위 NEWTOP
        Set<MemberRef> 부서 = new LinkedHashSet<>();
        for (int d = 0; d < 40; d++) {
            Set<MemberRef> 팀 = new LinkedHashSet<>();
            for (int t = 0; t < 50; t++) {
                String 팀코드 = "T%02d_%02d".formatted(d, t);
                state.saveGroup(new DirectoryGroup(팀코드, "ext-" + 팀코드, 팀코드, Set.of())).block();
                팀.add(MemberRef.group(팀코드));
            }
            String 부서코드 = "D%02d".formatted(d);
            state.saveGroup(new DirectoryGroup(부서코드, "ext-" + 부서코드, 부서코드, 팀)).block();
            부서.add(MemberRef.group(부서코드));
        }
        state.saveGroup(new DirectoryGroup("HQ", "ext-HQ", "본부", 부서)).block();
        state.saveGroup(new DirectoryGroup("NEWTOP", "ext-NEWTOP", "새 상위", Set.of())).block();
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when
        client.mutate().responseTimeout(Duration.ofMinutes(2)).build()
                .patch().uri("/scim/v2/Groups/NEWTOP").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                         "Operations":[{"op":"add","path":"members","value":[{"value":"HQ","type":"Group"}]}]}
                        """)
                .exchange().expectStatus().isNoContent();

        // then — 옛 검사는 자손 2,040개를 Query 했다
        읽은양을_찍는다("자손 2,000개 본부 붙이기", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(10);
        assertThat(check("group:HQ", "child", "group:NEWTOP")).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("맨 위 조직을 마지막에 POST 해도 조직도를 훑지 않는다")
    void 맨_위_조직을_마지막에_만든다() {
        // given
        counter.reset();
        long 시작 = System.currentTimeMillis();

        // when — 본부 HQ 를 하위로 둔 최상위 ROOT 를 만든다
        client.post().uri("/scim/v2/Groups").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"ROOT","displayName":"최상위",
                         "members":[{"value":"HQ","type":"Group"}]}
                        """)
                .exchange().expectStatus().isCreated();

        // then
        읽은양을_찍는다("맨 위 조직 POST", 시작);
        assertThat(counter.queries.get()).isLessThanOrEqualTo(10);
        assertThat(check("group:HQ", "child", "group:ROOT")).isTrue();
    }
```

(필드 `state`·`sync`·`client`·`counter`·`checks`·`조직` 과 도우미 `check`·`읽은양을_찍는다` 는 이 클래스에 이미 있다. `check(...)` 는 모델의 관계 이름으로 묻는다 —
`child` 를 직접 물을 수 없으면 그 조직 밑 직원의 롤업(`member`)으로 대신 단언한다. 이 테스트는 **돌리지 않는다**(컨트롤러가 `scaleTest` 로 돌린다) —
`./gradlew :app-scim:compileTestJava` 로 컴파일만 본다.)

- [ ] **Step 2: S16 주석**

`ScimScaleScenarioTest.S16_순환_참조` 의 then 주석에서 `reaches(조상, 자손)` 으로 "저장소를 타고 내려가" 확인한다는 설명을 고친다: 새 연결마다 `OrgGraph` 가
**튜플 그래프(멤버 줄 − 보류 목록)** 에서 부모부터 위로 올라가 자식이 조상인지 보고, 순환을 닫는 연결은 보류 목록에 적는다(설계 2026-10-03 §4.2).

- [ ] **Step 3: README**

- `## SCIM` 의 `members[].type` 문단: 판정은 요청의 `type` 없는 아이디를 모아 **한 번에**(BatchGet) 한다. 있는 직원으로 판정되면 로그가 없다(Entra·Okta 의 정상 경로).
  조직으로 추정했거나 없는 아이디면 요청당 경고 한 줄.
- 같은 절에 "중첩 조직과 순환" 문단을 새로: 순환을 닫는 연결은 멤버십은 저장하고 튜플은 쓰지 않으며 **보류 목록**(`CYCLE_CUT` 파티션)에 적는다. 먼저 저장된 연결이 이긴다.
  하위 조직을 빼는 요청 끝에서 보류 목록을 다시 보고 순환이 풀린 연결을 쓴다(INFO 로그). SCIM 재적재는 보류 목록을 처음부터 다시 정한다 — 어느 연결이 남을지가 바뀔 수
  있다. 조직 계층이 순환 검사 한도(조직 1만 개)를 넘는 요청은 400 `invalidValue`.
- `## 관리 API` 의 "순환은 드리프트가 아니다" 문단은 그대로 두되, SCIM 이면 버려진 간선이 보류 목록에 남는다는 한 문장을 더한다.
- 오류 응답 표(있으면)에 400 `invalidValue` — 조직 계층이 너무 큼.

- [ ] **Step 4: 점검 문서**

`docs/superpowers/specs/2026-09-28-full-audit.md` 의 P1·P2·M1 행(요약 표)과 S4·S28 행 끝에 `**→ 해결(2026-10-03, 슬라이드 ③-2)**` 를 붙인다(③-1 이 C6 에 붙인 것과
같은 모양).

- [ ] **Step 5: 컴파일을 본다**

Run: `./gradlew :app-scim:compileTestJava`
Expected: 성공.

- [ ] **Step 6: 커밋**

```bash
git add app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimGroupMemberPatchScaleTest.java \
  app-scim/src/test/java/dev/starryeye/organization/scim/app/ScimScaleScenarioTest.java \
  README.md docs/superpowers/specs/2026-09-28-full-audit.md
git commit -F - <<'EOF'
docs: ③-2 규모 테스트(type 없는 1,000명·7,000명 교체·10만 명 상위 밑 POST·자손 2,000 붙이기·맨 위 POST), README 멤버 type·중첩 조직과 순환, 점검 P1·P2·M1·S4·S28 해결 표시

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

## 머지 전 (컨트롤러)

- `./gradlew cleanTest test` 그다음 `./gradlew cleanScaleTest scaleTest` 를 한 번에 하나씩 돌린다.
- 스펙 §9 에 결과(테스트 수·시간, Task 10 규모 항목의 읽은 양)를 적고 커밋한다.
