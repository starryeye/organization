# 기반 모듈 + LDAP 동기화 인스턴스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** LDAP 디렉터리를 하루 1회 전체 동기화하여 조직·직원 관계를 OpenFGA 튜플로 반영하고 DynamoDB에 스냅샷과 현재상태를 적재하는 서버를 완성한다.

**Architecture:** Gradle 멀티모듈. `core`가 도메인 모델·포트 인터페이스·유스케이스를 정의하고 어댑터(`storage-dynamodb`, `authz-openfga`, `connector-ldap`)가 이를 구현한다. LDAP에서 읽은 전체 스냅샷을 튜플 집합으로 변환한 뒤 직전 스냅샷과 diff하여 `TupleDelta`를 만들고, OpenFGA에 먼저 적용한 다음 **실제 성공한 튜플만** 새 스냅샷으로 커밋한다. 실패분은 다음 sync의 diff가 자동으로 다시 잡는다.

**Tech Stack:** Java 17, Spring Boot 3.3.x WebFlux, Lombok, AWS SDK v2 `DynamoDbAsyncClient`(저수준), OpenFGA Java SDK, Spring LDAP, Testcontainers, UnboundID LDAP SDK, JUnit 5, AssertJ

**Spec:** [`docs/superpowers/specs/2026-08-14-organization-sync-design.md`](../specs/2026-08-14-organization-sync-design.md)

**범위:** 이 계획은 스펙의 `core` / `storage-dynamodb` / `authz-openfga` / `connector-ldap` / `app-ldap` 을 다룬다. `connector-scim` / `app-scim` 은 **별도 계획**(`2026-08-14-scim-connector.md`)에서 다루며, 이 계획이 만든 포트와 어댑터를 그대로 재사용한다. 이 계획만 완료해도 "LDAP → OpenFGA 동기화 서버"가 온전히 동작한다.

## Global Constraints

- **Java 17** (Corretto 17.0.14 확인됨). 언어 레벨 17을 넘지 않는다.
- **Spring Boot 3.3.5**. `core` 모듈은 스프링 컨텍스트에 의존하지 않는다 — reactor-core와 slf4j-api만 쓴다.
- **OpenFGA 서버 v1.10.0 이상 필수.** 그 미만에는 `on_duplicate`/`on_missing`이 없어 멱등 쓰기가 깨진다.
- **openfga-sdk 0.9.11** (`on_duplicate` 지원은 0.9.2+).
- **AWS SDK v2 2.28.29**, **Testcontainers 1.20.4**, **UnboundID LDAP SDK 7.0.1**, **Lombok 1.18.34**.
  버전 해석에 실패하면 해당 아티팩트의 최신 패치 버전으로 올린다. 메이저 버전은 바꾸지 않는다.
- **패키지 루트: `dev.starryeye.organization`**
- **OpenFGA는 Write/Delete만 호출한다.** 프로덕션 코드에 `read`, `check`, `listObjects`, `listUsers`를 두지 않는다. `Check`는 **테스트 코드에서만** 인가 모델 검증용으로 쓴다.
- **앱은 `storeId`/`modelId`를 다루지 않는다.** 설정에는 `store-name`만 두고, ID는 `authz-openfga` 내부에서 런타임 해석한다. write 호출 시 `authorization_model_id`는 생략한다.
- **조직명은 절대 튜플에 넣지 않는다.** 튜플 식별자는 직원 아이디와 조직코드뿐이다.
- **테스트 규약:** AssertJ, `// given` / `// when` / `// then` 주석, `@DisplayName`에 한글로 검증 내용 서술. 테스트 메서드명도 한글 허용.
- **커밋은 각 태스크 끝에서 반드시 수행하고 즉시 `git push origin main` 한다.**
- 커밋 메시지 말미에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` 를 넣는다.

## File Structure

| 파일 | 책임 |
|---|---|
| `settings.gradle`, `build.gradle`, `gradle/libs.versions.toml` | 멀티모듈 선언, 공통 플러그인·버전 |
| `docker-compose.yml`, `docker/ldap/seed.ldif` | 로컬 OpenFGA / DynamoDB Local / OpenLDAP |
| `core/.../model/*.java` | 불변 도메인·튜플 값 객체 (record) |
| `core/.../port/*.java` | 5개 포트 인터페이스 |
| `core/.../tuple/IdNormalizer.java` | 식별자 정규화 |
| `core/.../tuple/TupleMapper.java` | `DirectorySnapshot` → 튜플 집합 (순환 참조 처리 포함) |
| `core/.../tuple/TupleDiff.java` | 두 튜플 집합의 차집합 |
| `core/.../tuple/SnapshotIds.java` | 스냅샷 ID 생성 |
| `core/.../guard/DeletionGuard.java` | 삭제 임계치 판정 |
| `core/.../usecase/FullSyncUseCase.java` | LDAP 전체 동기화 오케스트레이션 |
| `core/.../usecase/RebuildUseCase.java` | 전체 재적재 (snapshot / store 두 모드) |
| `storage-dynamodb/.../Keys.java` | PK/SK/GSI 키 생성·파싱 |
| `storage-dynamodb/.../DynamoDb*Repository.java` | 3개 저장소 구현 |
| `storage-dynamodb/.../TableInitializer.java` | 테이블·GSI 생성 |
| `authz-openfga/.../StoreBootstrapper.java` | store 해석·생성, 인가 모델 등록 |
| `authz-openfga/.../OpenFgaRelationTupleWriter.java` | 배치 분할, 멱등 옵션, 재시도 |
| `connector-ldap/.../strategy/*.java` | groupOfNames / DIT 두 매핑 전략 |
| `app-ldap/.../SyncScheduler.java`, `AdminSyncController.java` | 스케줄러, 관리 API |

---

## Task 1: Gradle 멀티모듈 뼈대와 로컬 인프라

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle/libs.versions.toml`
- Create: `core/build.gradle`, `storage-dynamodb/build.gradle`, `authz-openfga/build.gradle`, `connector-ldap/build.gradle`, `connector-scim/build.gradle`, `app-ldap/build.gradle`, `app-scim/build.gradle`
- Create: `docker-compose.yml`, `docker/ldap/seed.ldif`
- Create: `.gitignore`

**Interfaces:**
- Consumes: 없음 (최초 태스크)
- Produces: 7개 Gradle 모듈. 이후 모든 태스크가 `dev.starryeye.organization` 패키지 아래에 코드를 넣는다. `connector-scim` / `app-scim` 은 이 계획에서 비워둔 채 두고 별도 계획에서 채운다.

- [ ] **Step 1: 버전 카탈로그 작성**

`gradle/libs.versions.toml`:

```toml
[versions]
springBoot = "3.3.5"
springDependencyManagement = "1.1.6"
awsSdk = "2.28.29"
openfga = "0.9.11"
testcontainers = "1.20.4"
unboundid = "7.0.1"
lombok = "1.18.34"

[libraries]
aws-bom = { module = "software.amazon.awssdk:bom", version.ref = "awsSdk" }
aws-dynamodb = { module = "software.amazon.awssdk:dynamodb" }
openfga-sdk = { module = "dev.openfga:openfga-sdk", version.ref = "openfga" }
spring-ldap-core = { module = "org.springframework.ldap:spring-ldap-core" }
reactor-core = { module = "io.projectreactor:reactor-core" }
slf4j-api = { module = "org.slf4j:slf4j-api" }
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
reactor-test = { module = "io.projectreactor:reactor-test" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter" }
unboundid-ldapsdk = { module = "com.unboundid:unboundid-ldapsdk", version.ref = "unboundid" }

[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "springDependencyManagement" }
```

- [ ] **Step 2: 루트 build.gradle 과 settings.gradle 작성**

`settings.gradle`:

```groovy
rootProject.name = 'organization'

include 'core'
include 'storage-dynamodb'
include 'authz-openfga'
include 'connector-ldap'
include 'connector-scim'
include 'app-ldap'
include 'app-scim'
```

`build.gradle`:

```groovy
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    group = 'dev.starryeye'
    version = '0.0.1-SNAPSHOT'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencyManagement {
        imports {
            mavenBom "org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"
            mavenBom libs.aws.bom.get().toString()
            mavenBom libs.testcontainers.bom.get().toString()
        }
    }

    dependencies {
        compileOnly libs.lombok
        annotationProcessor libs.lombok
        testCompileOnly libs.lombok
        testAnnotationProcessor libs.lombok

        testImplementation 'org.springframework.boot:spring-boot-starter-test'
        testImplementation libs.reactor.test
    }

    tasks.named('test') {
        useJUnitPlatform()
        testLogging {
            events 'passed', 'skipped', 'failed'
        }
    }
}
```

- [ ] **Step 3: 모듈별 build.gradle 작성**

`core/build.gradle` — 스프링 컨텍스트에 의존하지 않는다:

```groovy
dependencies {
    api libs.reactor.core
    api libs.slf4j.api
}
```

`storage-dynamodb/build.gradle`:

```groovy
dependencies {
    api project(':core')
    implementation libs.aws.dynamodb
    implementation 'org.springframework.boot:spring-boot-starter'

    testImplementation libs.testcontainers.junit
}
```

`authz-openfga/build.gradle`:

```groovy
dependencies {
    api project(':core')
    implementation libs.openfga.sdk
    implementation 'org.springframework.boot:spring-boot-starter'

    testImplementation libs.testcontainers.junit
}
```

`connector-ldap/build.gradle`:

```groovy
dependencies {
    api project(':core')
    implementation libs.spring.ldap.core
    implementation 'org.springframework.boot:spring-boot-starter'

    testImplementation libs.unboundid.ldapsdk
}
```

`connector-scim/build.gradle` — 이 계획에서는 비워둔다:

```groovy
dependencies {
    api project(':core')
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
}
```

`app-ldap/build.gradle`:

```groovy
apply plugin: 'org.springframework.boot'

dependencies {
    implementation project(':core')
    implementation project(':storage-dynamodb')
    implementation project(':authz-openfga')
    implementation project(':connector-ldap')

    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'

    testImplementation libs.testcontainers.junit
    testImplementation libs.unboundid.ldapsdk
}
```

`app-scim/build.gradle` — 이 계획에서는 비워둔다:

```groovy
apply plugin: 'org.springframework.boot'

dependencies {
    implementation project(':core')
    implementation project(':storage-dynamodb')
    implementation project(':authz-openfga')
    implementation project(':connector-scim')

    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
}
```

- [ ] **Step 4: app 모듈에 최소 부트 클래스와 설정 추가**

`app-ldap/src/main/java/dev/starryeye/organization/ldap/app/LdapSyncApplication.java`:

```java
package dev.starryeye.organization.ldap.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.starryeye.organization")
public class LdapSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(LdapSyncApplication.class, args);
    }
}
```

`app-scim/src/main/java/dev/starryeye/organization/scim/app/ScimSyncApplication.java` — 같은 형태로 클래스명만 `ScimSyncApplication` 으로 만든다.

`app-ldap/src/main/resources/application.yml` (이후 태스크에서 채워짐):

```yaml
server:
  port: 8081

spring:
  application:
    name: organization-ldap
```

`app-scim/src/main/resources/application.yml`:

```yaml
server:
  port: 8082

spring:
  application:
    name: organization-scim
```

- [ ] **Step 5: docker-compose 와 LDAP 시드 데이터 작성**

`docker-compose.yml`:

```yaml
services:
  openfga:
    image: openfga/openfga:v1.10.2
    command: run
    environment:
      OPENFGA_DATASTORE_ENGINE: memory
      OPENFGA_PLAYGROUND_ENABLED: "true"
    ports:
      - "8080:8080"
      - "3000:3000"

  dynamodb-local:
    image: amazon/dynamodb-local:2.5.3
    command: ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb"]
    ports:
      - "8000:8000"

  openldap:
    image: bitnami/openldap:2.6
    environment:
      LDAP_ROOT: dc=example,dc=com
      LDAP_ADMIN_USERNAME: admin
      LDAP_ADMIN_PASSWORD: adminpassword
      LDAP_SKIP_DEFAULT_TREE: "yes"
      LDAP_CUSTOM_LDIF_DIR: /ldifs
    volumes:
      - ./docker/ldap:/ldifs:ro
    ports:
      - "1389:1389"
```

`docker/ldap/seed.ldif` — `groupOfNames` 전략과 `dit` 전략을 **한 트리에서 모두** 검증할 수 있게 구성한다. 조직코드는 `cn`/`ou`, 조직명은 `description` 에 넣는다:

```ldif
dn: dc=example,dc=com
objectClass: dcObject
objectClass: organization
dc: example
o: Example Corp

dn: ou=people,dc=example,dc=com
objectClass: organizationalUnit
ou: people

dn: ou=groups,dc=example,dc=com
objectClass: organizationalUnit
ou: groups

dn: uid=kim,ou=people,dc=example,dc=com
objectClass: inetOrgPerson
uid: kim
cn: Kim Chulsoo
sn: Kim
displayName: 김철수
mail: kim@example.com

dn: uid=lee,ou=people,dc=example,dc=com
objectClass: inetOrgPerson
uid: lee
cn: Lee Younghee
sn: Lee
displayName: 이영희
mail: lee@example.com

dn: uid=park,ou=people,dc=example,dc=com
objectClass: inetOrgPerson
uid: park
cn: Park Minsu
sn: Park
displayName: 박민수
mail: park@example.com

dn: cn=DEV001,ou=groups,dc=example,dc=com
objectClass: groupOfNames
cn: DEV001
description: 개발본부
member: cn=DEV002,ou=groups,dc=example,dc=com
member: uid=park,ou=people,dc=example,dc=com

dn: cn=DEV002,ou=groups,dc=example,dc=com
objectClass: groupOfNames
cn: DEV002
description: 백엔드팀
member: uid=kim,ou=people,dc=example,dc=com
member: uid=lee,ou=people,dc=example,dc=com

dn: ou=company,dc=example,dc=com
objectClass: organizationalUnit
ou: company
description: 전사

dn: ou=DEV001,ou=company,dc=example,dc=com
objectClass: organizationalUnit
ou: DEV001
description: 개발본부

dn: ou=DEV002,ou=DEV001,ou=company,dc=example,dc=com
objectClass: organizationalUnit
ou: DEV002
description: 백엔드팀

dn: uid=choi,ou=DEV002,ou=DEV001,ou=company,dc=example,dc=com
objectClass: inetOrgPerson
uid: choi
cn: Choi Jiwoo
sn: Choi
displayName: 최지우
mail: choi@example.com
```

`.gitignore`:

```
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.DS_Store
```

- [ ] **Step 6: 빌드가 통과하는지 확인**

Run:

```bash
./gradlew projects && ./gradlew build
```

Expected: 7개 모듈이 모두 나열되고 `BUILD SUCCESSFUL`. Gradle wrapper가 없으면 `gradle wrapper --gradle-version 8.11.1` 을 먼저 실행한다.

- [ ] **Step 7: docker-compose 가 뜨는지 확인**

Run:

```bash
docker compose up -d && sleep 15 && docker compose ps
```

Expected: `openfga`, `dynamodb-local`, `openldap` 세 서비스가 모두 `running`.

LDAP 시드가 들어갔는지 확인:

```bash
docker compose exec openldap ldapsearch -x -H ldap://localhost:1389 -D "cn=admin,dc=example,dc=com" -w adminpassword -b "dc=example,dc=com" "(objectClass=groupOfNames)" cn description
```

Expected: `DEV001`(개발본부)과 `DEV002`(백엔드팀) 두 그룹이 출력된다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
build: Gradle 멀티모듈 뼈대와 로컬 인프라 구성

7개 모듈(core / storage-dynamodb / authz-openfga / connector-ldap /
connector-scim / app-ldap / app-scim)과 버전 카탈로그를 추가하고,
OpenFGA v1.10 / DynamoDB Local / OpenLDAP docker-compose 와
두 매핑 전략을 모두 검증할 수 있는 LDIF 시드를 넣었다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 2: core 도메인 모델과 식별자 정규화

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/model/` 아래 값 객체들
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/IdNormalizer.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/IdNormalizerTest.java`

**Interfaces:**
- Consumes: Task 1의 `core` 모듈
- Produces: 이후 모든 태스크가 쓰는 값 객체 전부.
  - `DirectoryUser(String id, String externalId, String userName, String displayName, String email, boolean active)`
  - `DirectoryGroup(String id, String externalId, String displayName, Set<MemberRef> members)`
  - `MemberRef(MemberType type, String id)` + 팩토리 `MemberRef.user(String)`, `MemberRef.group(String)`
  - `DirectorySnapshot(Map<String,DirectoryUser> users, Map<String,DirectoryGroup> groups)` + `DirectorySnapshot.empty()`
  - `RelationTuple(String user, String relation, String object)` + 팩토리 `RelationTuple.directMember(String userId, String groupId)`, `RelationTuple.child(String childGroupId, String parentGroupId)`
  - `TupleDelta(Set<RelationTuple> toWrite, Set<RelationTuple> toDelete)` + `empty()`, `isEmpty()`
  - `TupleWriteResult(Set<RelationTuple> written, Set<RelationTuple> deleted, List<TupleFailure> failures)` + `hasFailure()`, `empty()`
  - `TupleFailure(RelationTuple tuple, String reason)`
  - `TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples)`
  - `SnapshotMeta(String id, Instant createdAt, SyncSource source, int tupleCount)`
  - `SyncRun(...)` + `finished(SyncOutcome, Instant)`
  - `SyncOutcome(...)` + 팩토리 `noChange()`, `succeeded(TupleWriteResult, String)`, `partial(TupleWriteResult, String)`, `aborted(String)`, `failed(String)`
  - enum `MemberType{USER,GROUP}`, `SyncSource{LDAP,SCIM}`, `SyncTrigger{SCHEDULED,MANUAL,FORCED,REBUILD,ARCHIVE}`, `SyncStatus{RUNNING,SUCCEEDED,PARTIAL,ABORTED,FAILED}`
  - `IdNormalizer.normalize(String) -> String`

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/dev/starryeye/organization/core/tuple/IdNormalizerTest.java`:

```java
package dev.starryeye.organization.core.tuple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdNormalizerTest {

    @Test
    @DisplayName("영문·숫자·허용기호로만 이루어진 식별자는 그대로 유지된다")
    void 정상_식별자는_그대로_유지된다() {
        // given
        String raw = "DEV-001.kim_2@example.com";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("DEV-001.kim_2@example.com");
    }

    @Test
    @DisplayName("한글 조직코드는 훼손되지 않고 그대로 유지된다")
    void 한글_식별자는_보존된다() {
        // given
        String raw = "개발본부";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("개발본부");
    }

    @Test
    @DisplayName("OpenFGA 파싱을 깨는 문자만 밑줄로 치환된다")
    void 금지문자만_치환된다() {
        // given
        String raw = "cn=김철수,ou=백엔드 팀:1#a*b";

        // when
        String normalized = IdNormalizer.normalize(raw);

        // then
        assertThat(normalized).isEqualTo("cn=김철수_ou=백엔드_팀_1_a_b");
    }

    @Test
    @DisplayName("서로 다른 한글 조직코드가 같은 식별자로 뭉개지지 않는다")
    void 서로_다른_한글_조직코드는_충돌하지_않는다() {
        // given
        String 개발본부 = "개발본부";
        String 영업본부 = "영업본부";

        // when
        String a = IdNormalizer.normalize(개발본부);
        String b = IdNormalizer.normalize(영업본부);

        // then
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("비어 있는 식별자는 예외를 던진다")
    void 빈_식별자는_예외를_던진다() {
        // given
        String raw = "   ";

        // when, then
        assertThatThrownBy(() -> IdNormalizer.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("식별자");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*IdNormalizerTest*'
```

Expected: 컴파일 실패 — `IdNormalizer` 클래스가 없다.

- [ ] **Step 3: IdNormalizer 구현**

`core/src/main/java/dev/starryeye/organization/core/tuple/IdNormalizer.java`:

```java
package dev.starryeye.organization.core.tuple;

import java.util.regex.Pattern;

/**
 * OpenFGA object id 로 쓸 수 있게 식별자를 정규화한다.
 *
 * <p>허용 목록이 아니라 금지 목록을 쓰는 이유는 한글 조직코드를 보존하기 위해서다.
 * {@code [A-Za-z0-9._@-]} 허용 목록을 쓰면 "개발본부"가 "____"가 되어
 * 서로 다른 조직이 같은 id 로 뭉개진다.
 */
public final class IdNormalizer {

    /** OpenFGA 파싱을 깨는 문자: 공백류, 타입 구분자(:), userset 구분자(#), 와일드카드(*), 쉼표, 역슬래시 */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\s:#*,\\\\]");

    private IdNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("식별자는 비어 있을 수 없습니다");
        }
        return FORBIDDEN.matcher(raw).replaceAll("_");
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*IdNormalizerTest*'
```

Expected: 5개 테스트 모두 PASS.

- [ ] **Step 5: 값 객체 작성 — 디렉터리 모델**

`core/src/main/java/dev/starryeye/organization/core/model/MemberType.java`:

```java
package dev.starryeye.organization.core.model;

public enum MemberType {
    USER,
    GROUP
}
```

`core/src/main/java/dev/starryeye/organization/core/model/MemberRef.java`:

```java
package dev.starryeye.organization.core.model;

import java.util.Objects;

public record MemberRef(MemberType type, String id) {

    public MemberRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static MemberRef user(String id) {
        return new MemberRef(MemberType.USER, id);
    }

    public static MemberRef group(String id) {
        return new MemberRef(MemberType.GROUP, id);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/DirectoryUser.java`:

```java
package dev.starryeye.organization.core.model;

/**
 * @param id 직원 아이디. 튜플에 쓰이는 안정 식별자
 * @param externalId LDAP DN 또는 SCIM externalId (원본 보관)
 */
public record DirectoryUser(
        String id,
        String externalId,
        String userName,
        String displayName,
        String email,
        boolean active
) {
}
```

`core/src/main/java/dev/starryeye/organization/core/model/DirectoryGroup.java`:

```java
package dev.starryeye.organization.core.model;

import java.util.Set;

/**
 * @param id 조직코드. 튜플에 쓰이는 안정 식별자
 * @param displayName 조직명. 개편 때마다 바뀌므로 튜플에 절대 쓰지 않는다
 */
public record DirectoryGroup(
        String id,
        String externalId,
        String displayName,
        Set<MemberRef> members
) {

    public DirectoryGroup {
        members = members == null ? Set.of() : Set.copyOf(members);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/DirectorySnapshot.java`:

```java
package dev.starryeye.organization.core.model;

import java.util.Map;

public record DirectorySnapshot(
        Map<String, DirectoryUser> users,
        Map<String, DirectoryGroup> groups
) {

    public DirectorySnapshot {
        users = users == null ? Map.of() : Map.copyOf(users);
        groups = groups == null ? Map.of() : Map.copyOf(groups);
    }

    public static DirectorySnapshot empty() {
        return new DirectorySnapshot(Map.of(), Map.of());
    }
}
```

- [ ] **Step 6: 값 객체 작성 — 튜플 모델**

`core/src/main/java/dev/starryeye/organization/core/model/RelationTuple.java`:

```java
package dev.starryeye.organization.core.model;

public record RelationTuple(String user, String relation, String object) {

    public static final String DIRECT_MEMBER = "direct_member";
    public static final String CHILD = "child";

    public static final String USER_TYPE = "user";
    public static final String GROUP_TYPE = "group";

    /** 그룹 G 에 직원 U 가 직접 속한다: (user:U, direct_member, group:G) */
    public static RelationTuple directMember(String userId, String groupId) {
        return new RelationTuple(USER_TYPE + ":" + userId, DIRECT_MEMBER, GROUP_TYPE + ":" + groupId);
    }

    /** 그룹 C 가 그룹 P 의 하위 조직이다: (group:C, child, group:P) */
    public static RelationTuple child(String childGroupId, String parentGroupId) {
        return new RelationTuple(GROUP_TYPE + ":" + childGroupId, CHILD, GROUP_TYPE + ":" + parentGroupId);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/TupleDelta.java`:

```java
package dev.starryeye.organization.core.model;

import java.util.Set;

public record TupleDelta(Set<RelationTuple> toWrite, Set<RelationTuple> toDelete) {

    public TupleDelta {
        toWrite = toWrite == null ? Set.of() : Set.copyOf(toWrite);
        toDelete = toDelete == null ? Set.of() : Set.copyOf(toDelete);
    }

    public static TupleDelta empty() {
        return new TupleDelta(Set.of(), Set.of());
    }

    public static TupleDelta writeOnly(Set<RelationTuple> tuples) {
        return new TupleDelta(tuples, Set.of());
    }

    public static TupleDelta deleteOnly(Set<RelationTuple> tuples) {
        return new TupleDelta(Set.of(), tuples);
    }

    public boolean isEmpty() {
        return toWrite.isEmpty() && toDelete.isEmpty();
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/TupleFailure.java`:

```java
package dev.starryeye.organization.core.model;

public record TupleFailure(RelationTuple tuple, String reason) {
}
```

`core/src/main/java/dev/starryeye/organization/core/model/TupleWriteResult.java`:

```java
package dev.starryeye.organization.core.model;

import java.util.List;
import java.util.Set;

/**
 * OpenFGA 에 <b>실제로 반영된</b> 튜플만 담는다.
 * 이 결과로 새 스냅샷을 계산하기 때문에, 실패한 튜플은 다음 동기화의 diff 가 다시 잡는다.
 */
public record TupleWriteResult(
        Set<RelationTuple> written,
        Set<RelationTuple> deleted,
        List<TupleFailure> failures
) {

    public TupleWriteResult {
        written = written == null ? Set.of() : Set.copyOf(written);
        deleted = deleted == null ? Set.of() : Set.copyOf(deleted);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static TupleWriteResult empty() {
        return new TupleWriteResult(Set.of(), Set.of(), List.of());
    }

    public boolean hasFailure() {
        return !failures.isEmpty();
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/TupleSnapshot.java`:

```java
package dev.starryeye.organization.core.model;

import java.time.Instant;
import java.util.Set;

public record TupleSnapshot(String id, Instant createdAt, SyncSource source, Set<RelationTuple> tuples) {

    public TupleSnapshot {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
    }

    public SnapshotMeta meta() {
        return new SnapshotMeta(id, createdAt, source, tuples.size());
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/SnapshotMeta.java`:

```java
package dev.starryeye.organization.core.model;

import java.time.Instant;

public record SnapshotMeta(String id, Instant createdAt, SyncSource source, int tupleCount) {
}
```

- [ ] **Step 7: 값 객체 작성 — 실행 이력 모델**

`core/src/main/java/dev/starryeye/organization/core/model/SyncSource.java`:

```java
package dev.starryeye.organization.core.model;

public enum SyncSource {
    LDAP,
    SCIM
}
```

`core/src/main/java/dev/starryeye/organization/core/model/SyncTrigger.java`:

```java
package dev.starryeye.organization.core.model;

public enum SyncTrigger {
    /** 스케줄러가 기동 */
    SCHEDULED,
    /** 관리 API 수동 실행 */
    MANUAL,
    /** 관리 API 수동 실행 + 삭제 가드 우회 */
    FORCED,
    /** 전체 재적재 */
    REBUILD,
    /** SCIM 인스턴스의 일 1회 스냅샷 아카이빙 */
    ARCHIVE
}
```

`core/src/main/java/dev/starryeye/organization/core/model/SyncStatus.java`:

```java
package dev.starryeye.organization.core.model;

public enum SyncStatus {
    RUNNING,
    SUCCEEDED,
    /** 일부 튜플 적용에 실패. 다음 동기화가 자동으로 다시 잡는다 */
    PARTIAL,
    /** 삭제 가드가 발동해 OpenFGA 를 건드리지 않았다 */
    ABORTED,
    FAILED
}
```

`core/src/main/java/dev/starryeye/organization/core/model/SyncOutcome.java`:

```java
package dev.starryeye.organization.core.model;

public record SyncOutcome(
        SyncStatus status,
        int writtenCount,
        int deletedCount,
        int failureCount,
        String snapshotId,
        String message
) {

    public static SyncOutcome noChange() {
        return new SyncOutcome(SyncStatus.SUCCEEDED, 0, 0, 0, null, "변경 없음");
    }

    public static SyncOutcome succeeded(TupleWriteResult result, String snapshotId) {
        return new SyncOutcome(SyncStatus.SUCCEEDED,
                result.written().size(), result.deleted().size(), 0, snapshotId, null);
    }

    public static SyncOutcome partial(TupleWriteResult result, String snapshotId) {
        return new SyncOutcome(SyncStatus.PARTIAL,
                result.written().size(), result.deleted().size(), result.failures().size(), snapshotId,
                result.failures().size() + "건의 튜플 적용에 실패했습니다. 다음 동기화가 다시 시도합니다");
    }

    public static SyncOutcome aborted(String message) {
        return new SyncOutcome(SyncStatus.ABORTED, 0, 0, 0, null, message);
    }

    public static SyncOutcome failed(String message) {
        return new SyncOutcome(SyncStatus.FAILED, 0, 0, 0, null, message);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/model/SyncRun.java`:

```java
package dev.starryeye.organization.core.model;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record SyncRun(
        String runId,
        SyncSource source,
        SyncTrigger trigger,
        Instant startedAt,
        Instant finishedAt,
        SyncStatus status,
        int writtenCount,
        int deletedCount,
        int failureCount,
        String snapshotId,
        String message
) {

    public static SyncRun started(String runId, SyncSource source, SyncTrigger trigger, Instant at) {
        return SyncRun.builder()
                .runId(runId)
                .source(source)
                .trigger(trigger)
                .startedAt(at)
                .status(SyncStatus.RUNNING)
                .build();
    }

    public SyncRun finished(SyncOutcome outcome, Instant at) {
        return this.toBuilder()
                .finishedAt(at)
                .status(outcome.status())
                .writtenCount(outcome.writtenCount())
                .deletedCount(outcome.deletedCount())
                .failureCount(outcome.failureCount())
                .snapshotId(outcome.snapshotId())
                .message(outcome.message())
                .build();
    }
}
```

- [ ] **Step 8: 전체 빌드 확인**

Run:

```bash
./gradlew :core:build
```

Expected: `BUILD SUCCESSFUL`. Lombok `@Builder` 가 record 에 적용되므로 annotation processor 가 동작하는지 여기서 확인된다. 실패하면 `core/build.gradle` 의 `compileOnly`/`annotationProcessor` 설정을 점검한다.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: core 도메인 모델과 식별자 정규화 추가

디렉터리 모델(DirectoryUser/Group/Snapshot), 튜플 모델(RelationTuple,
TupleDelta, TupleWriteResult, TupleSnapshot), 실행 이력 모델(SyncRun,
SyncOutcome)을 record 로 정의했다.

IdNormalizer 는 허용 목록이 아니라 금지 목록을 쓴다. 허용 목록을 쓰면
한글 조직코드가 전부 밑줄로 뭉개져 서로 다른 조직이 충돌하기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 3: TupleMapper — 디렉터리 스냅샷을 튜플 집합으로

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleMappingResult.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java`

**Interfaces:**
- Consumes: Task 2의 `DirectorySnapshot`, `DirectoryUser`, `DirectoryGroup`, `MemberRef`, `MemberType`, `RelationTuple`
- Produces:
  - `TupleMappingResult(Set<RelationTuple> tuples, List<String> warnings)`
  - `TupleMapper.toTuples(DirectorySnapshot) -> TupleMappingResult` (static)

**변환 규칙** (스펙 §5.1):

| 도메인 사실 | 튜플 |
|---|---|
| 그룹 G의 멤버가 유저 U (그리고 U가 active) | `(user:U, direct_member, group:G)` |
| 그룹 G의 멤버가 그룹 C | `(group:C, child, group:G)` |

스냅샷에 없는 멤버, 비활성 유저, 순환을 만드는 간선은 제외하고 `warnings`에 남긴다. 동기화 전체를 실패시키지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/dev/starryeye/organization/core/tuple/TupleMapperTest.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TupleMapperTest {

    private static DirectoryUser 활성직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    private static DirectoryUser 비활성직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", false);
    }

    private static DirectoryGroup 조직(String code, String name, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, name, Set.of(members));
    }

    private static DirectorySnapshot 스냅샷(Set<DirectoryUser> users, Set<DirectoryGroup> groups) {
        return new DirectorySnapshot(
                users.stream().collect(Collectors.toMap(DirectoryUser::id, Function.identity())),
                groups.stream().collect(Collectors.toMap(DirectoryGroup::id, Function.identity())));
    }

    @Test
    @DisplayName("조직에 직접 속한 직원은 direct_member 튜플이 된다")
    void 직원_멤버는_direct_member_튜플이_된다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("user:kim", "direct_member", "group:DEV002"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("조직에 속한 하위 조직은 child 튜플이 되며 방향은 하위에서 상위로 향한다")
    void 조직_멤버는_child_튜플이_된다() {
        // given
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("DEV001", "개발본부", MemberRef.group("DEV002")),
                       조직("DEV002", "백엔드팀")));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("group:DEV002", "child", "group:DEV001"));
    }

    @Test
    @DisplayName("비활성 직원은 튜플이 생성되지 않아 권한이 남지 않는다")
    void 비활성_직원은_튜플이_생성되지_않는다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim"), 비활성직원("lee")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .containsExactly(new RelationTuple("user:kim", "direct_member", "group:DEV002"));
    }

    @Test
    @DisplayName("스냅샷에 존재하지 않는 멤버는 건너뛰고 경고로 남긴다")
    void 존재하지_않는_멤버는_경고로_남긴다() {
        // given
        var snapshot = 스냅샷(
                Set.of(),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("ghost"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("ghost").contains("DEV002");
    }

    @Test
    @DisplayName("조직명은 조직 개편에 따라 바뀌므로 튜플에 포함되지 않는다")
    void 조직명은_튜플에_포함되지_않는다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples())
                .allSatisfy(tuple -> assertThat(tuple.object()).doesNotContain("백엔드팀"));
    }

    @Test
    @DisplayName("조직 계층에 순환이 있으면 순환을 만드는 간선만 제외하고 나머지는 유지한다")
    void 순환_참조는_간선을_제외하고_동기화를_완주한다() {
        // given — A -> B -> C -> A 로 순환하고, B 아래에 직원이 하나 있다
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("A", "가", MemberRef.group("B")),
                       조직("B", "나", MemberRef.group("C"), MemberRef.user("kim")),
                       조직("C", "다", MemberRef.group("A"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then — child 간선 3개 중 순환을 닫는 1개만 빠지고 2개가 남는다
        var childTuples = result.tuples().stream()
                .filter(t -> t.relation().equals("child"))
                .collect(Collectors.toSet());
        assertThat(childTuples).hasSize(2);
        assertThat(result.tuples())
                .contains(new RelationTuple("user:kim", "direct_member", "group:B"));
        assertThat(result.warnings())
                .anySatisfy(w -> assertThat(w).contains("순환"));
    }

    @Test
    @DisplayName("동일한 스냅샷을 여러 번 변환해도 항상 같은 결과가 나온다")
    void 변환_결과는_결정적이다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim")),
                Set.of(조직("A", "가", MemberRef.group("B")),
                       조직("B", "나", MemberRef.group("C"), MemberRef.user("kim")),
                       조직("C", "다", MemberRef.group("A"))));

        // when
        var first = TupleMapper.toTuples(snapshot);
        var again = Stream.generate(() -> TupleMapper.toTuples(snapshot))
                .limit(5)
                .map(TupleMappingResult::tuples)
                .collect(Collectors.toSet());

        // then
        assertThat(again).containsExactly(first.tuples());
    }

    @Test
    @DisplayName("빈 스냅샷은 빈 튜플 집합을 만든다")
    void 빈_스냅샷은_빈_결과를_만든다() {
        // given
        var snapshot = DirectorySnapshot.empty();

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("중첩 조직과 직원이 섞인 스냅샷을 한 번에 변환한다")

    void 복합_스냅샷을_변환한다() {
        // given
        var snapshot = 스냅샷(
                Set.of(활성직원("kim"), 활성직원("lee"), 활성직원("park")),
                Set.of(조직("DEV001", "개발본부", MemberRef.group("DEV002"), MemberRef.user("park")),
                       조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))));

        // when
        var result = TupleMapper.toTuples(snapshot);

        // then
        assertThat(result.tuples()).containsExactlyInAnyOrder(
                new RelationTuple("group:DEV002", "child", "group:DEV001"),
                new RelationTuple("user:park", "direct_member", "group:DEV001"),
                new RelationTuple("user:kim", "direct_member", "group:DEV002"),
                new RelationTuple("user:lee", "direct_member", "group:DEV002"));
        assertThat(result.warnings()).isEmpty();
    }
}
```

> 임포트는 실제로 쓰는 것만 남긴다. `TupleMapper` 는 `java.util.HashSet` 을 쓰지 않고, 테스트는 `java.util.Map` 을 쓰지 않는다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*TupleMapperTest*'
```

Expected: 컴파일 실패 — `TupleMapper`, `TupleMappingResult` 가 없다.

- [ ] **Step 3: TupleMappingResult 구현**

`core/src/main/java/dev/starryeye/organization/core/tuple/TupleMappingResult.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;

import java.util.List;
import java.util.Set;

public record TupleMappingResult(Set<RelationTuple> tuples, List<String> warnings) {

    public TupleMappingResult {
        tuples = tuples == null ? Set.of() : Set.copyOf(tuples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
```

- [ ] **Step 4: TupleMapper 구현**

순환 검출은 조직코드 사전순으로 시작점을 정하고 DFS 색칠법(WHITE/GRAY/BLACK)을 쓴다. 회색 노드로 향하는 간선(back edge)만 버린다. 시작점 순서를 고정했으므로 같은 입력이면 같은 간선이 버려진다.

`core/src/main/java/dev/starryeye/organization/core/tuple/TupleMapper.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@link DirectorySnapshot} 을 OpenFGA 튜플 집합으로 변환한다.
 *
 * <p>이것이 LDAP 커넥터와 SCIM 커넥터가 공유하는 유일한 변환 규칙이다.
 * 조직명({@link DirectoryGroup#displayName()})은 조직 개편 때마다 바뀌므로 절대 튜플에 넣지 않는다.
 */
public final class TupleMapper {

    private TupleMapper() {
    }

    public static TupleMappingResult toTuples(DirectorySnapshot snapshot) {
        List<String> warnings = new ArrayList<>();

        Map<String, Set<String>> childEdges = collectChildEdges(snapshot, warnings);
        Set<Edge> acyclic = removeCycles(childEdges, warnings);

        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Edge edge : acyclic) {
            tuples.add(RelationTuple.child(edge.child(), edge.parent()));
        }
        tuples.addAll(collectDirectMembers(snapshot, warnings));

        return new TupleMappingResult(tuples, warnings);
    }

    /** 조직코드 사전순으로 부모 → 자식 인접 리스트를 만든다. 순서를 고정해야 결과가 결정적이다. */
    private static Map<String, Set<String>> collectChildEdges(DirectorySnapshot snapshot, List<String> warnings) {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (DirectoryGroup group : sortedGroups(snapshot)) {
            Set<String> children = new TreeSet<>();
            for (MemberRef member : sortedMembers(group)) {
                if (member.type() != MemberType.GROUP) {
                    continue;
                }
                if (!snapshot.groups().containsKey(member.id())) {
                    warnings.add("조직 '%s' 의 하위 조직 '%s' 가 스냅샷에 없어 건너뜁니다"
                            .formatted(group.id(), member.id()));
                    continue;
                }
                children.add(member.id());
            }
            edges.put(group.id(), children);
        }
        return edges;
    }

    private static Set<RelationTuple> collectDirectMembers(DirectorySnapshot snapshot, List<String> warnings) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (DirectoryGroup group : sortedGroups(snapshot)) {
            for (MemberRef member : sortedMembers(group)) {
                if (member.type() != MemberType.USER) {
                    continue;
                }
                DirectoryUser user = snapshot.users().get(member.id());
                if (user == null) {
                    warnings.add("조직 '%s' 의 직원 '%s' 가 스냅샷에 없어 건너뜁니다"
                            .formatted(group.id(), member.id()));
                    continue;
                }
                if (!user.active()) {
                    continue;
                }
                tuples.add(RelationTuple.directMember(user.id(), group.id()));
            }
        }
        return tuples;
    }

    /**
     * DFS 색칠법으로 순환을 찾아 back edge 만 버린다.
     * 시작점을 조직코드 사전순으로 고정했으므로 같은 입력이면 같은 간선이 버려진다.
     */
    private static Set<Edge> removeCycles(Map<String, Set<String>> edges, List<String> warnings) {
        Set<Edge> kept = new LinkedHashSet<>();
        Map<String, Color> colors = new HashMap<>();
        edges.keySet().forEach(node -> colors.put(node, Color.WHITE));

        for (String start : edges.keySet()) {
            if (colors.get(start) == Color.WHITE) {
                visit(start, edges, colors, kept, warnings);
            }
        }
        return kept;
    }

    private static void visit(String node,
                              Map<String, Set<String>> edges,
                              Map<String, Color> colors,
                              Set<Edge> kept,
                              List<String> warnings) {
        colors.put(node, Color.GRAY);
        for (String child : edges.getOrDefault(node, Set.of())) {
            Color childColor = colors.getOrDefault(child, Color.WHITE);
            if (childColor == Color.GRAY) {
                warnings.add("조직 '%s' → '%s' 간선이 순환을 만들어 제외합니다".formatted(node, child));
                continue;
            }
            kept.add(new Edge(child, node));
            if (childColor == Color.WHITE) {
                visit(child, edges, colors, kept, warnings);
            }
        }
        colors.put(node, Color.BLACK);
    }

    private static List<DirectoryGroup> sortedGroups(DirectorySnapshot snapshot) {
        return snapshot.groups().values().stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
    }

    private static List<MemberRef> sortedMembers(DirectoryGroup group) {
        return group.members().stream()
                .sorted((a, b) -> {
                    int byType = a.type().compareTo(b.type());
                    return byType != 0 ? byType : a.id().compareTo(b.id());
                })
                .toList();
    }

    /** child 가 parent 의 하위 조직이다. 튜플 방향과 동일하다. */
    private record Edge(String child, String parent) {
    }

    private enum Color {
        WHITE, GRAY, BLACK
    }
}
```

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*TupleMapperTest*'
```

Expected: 9개 테스트 모두 PASS.

순환 테스트가 실패하면 `removeCycles` 의 시작점 순회 순서(`edges.keySet()` 이 `TreeMap` 이라 사전순)를 확인한다. `A → B → C → A` 에서 A부터 시작하면 `C → A` 간선이 back edge 로 걸린다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: TupleMapper 추가 — 디렉터리 스냅샷을 OpenFGA 튜플로 변환

LDAP 커넥터와 SCIM 커넥터가 공유하는 유일한 변환 규칙이다.
직원 멤버는 direct_member, 하위 조직은 child 튜플이 되며 조직명은
개편 때마다 바뀌므로 튜플에 넣지 않는다.

비활성 직원, 스냅샷에 없는 멤버, 순환을 만드는 간선은 제외하고
경고로 남겨 동기화 전체가 실패하지 않게 했다. 순환 검출은 조직코드
사전순 DFS 색칠법이라 같은 입력이면 항상 같은 간선이 버려진다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 4: TupleDiff — 스냅샷 차집합

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/TupleDiff.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/tuple/SnapshotIds.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/TupleDiffTest.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/tuple/SnapshotIdsTest.java`

**Interfaces:**
- Consumes: Task 2의 `RelationTuple`, `TupleDelta`, `SyncSource`
- Produces:
  - `TupleDiff.between(Set<RelationTuple> baseline, Set<RelationTuple> target) -> TupleDelta` (static)
  - `SnapshotIds.generate(Instant at, SyncSource source) -> String` (static, 형식 `yyyyMMdd'T'HHmmss-SOURCE`)

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/dev/starryeye/organization/core/tuple/TupleDiffTest.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TupleDiffTest {

    private static RelationTuple 소속(String userId, String groupId) {
        return RelationTuple.directMember(userId, groupId);
    }

    @Test
    @DisplayName("직전 스냅샷에 없던 튜플은 생성 대상으로 분류된다")
    void 신규_튜플은_생성_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactly(소속("lee", "DEV002"));
        assertThat(delta.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("목표에서 사라진 튜플은 삭제 대상으로 분류된다")
    void 사라진_튜플은_삭제_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).containsExactly(소속("lee", "DEV002"));
    }

    @Test
    @DisplayName("양쪽에 모두 있는 튜플은 어느 쪽에도 분류되지 않아 불필요한 쓰기가 발생하지 않는다")
    void 변경이_없으면_빈_델타가_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("직전 스냅샷이 비어 있으면 목표 전체가 생성 대상이 된다")
    void 최초_동기화는_전체가_생성_대상이_된다() {
        // given
        var 직전 = Set.<RelationTuple>of();
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactlyInAnyOrderElementsOf(목표);
        assertThat(delta.toDelete()).isEmpty();
    }

    @Test
    @DisplayName("LDAP이 0건을 반환하면 직전 스냅샷 전체가 삭제 대상이 되어 가드가 판단할 수 있게 한다")
    void 목표가_비면_전체가_삭제_대상이_된다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.<RelationTuple>of();

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).isEmpty();
        assertThat(delta.toDelete()).containsExactlyInAnyOrderElementsOf(직전);
    }

    @Test
    @DisplayName("생성과 삭제가 동시에 있는 경우를 한 번에 계산한다")
    void 생성과_삭제를_동시에_계산한다() {
        // given
        var 직전 = Set.of(소속("kim", "DEV002"), 소속("lee", "DEV002"));
        var 목표 = Set.of(소속("kim", "DEV002"), 소속("park", "DEV001"));

        // when
        var delta = TupleDiff.between(직전, 목표);

        // then
        assertThat(delta.toWrite()).containsExactly(소속("park", "DEV001"));
        assertThat(delta.toDelete()).containsExactly(소속("lee", "DEV002"));
    }
}
```

`core/src/test/java/dev/starryeye/organization/core/tuple/SnapshotIdsTest.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.SyncSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotIdsTest {

    @Test
    @DisplayName("스냅샷 아이디는 UTC 기준 시각과 소스를 담아 사전순 정렬이 시간순과 일치한다")
    void 스냅샷_아이디는_시각과_소스를_담는다() {
        // given
        var at = Instant.parse("2026-08-14T03:00:00Z");

        // when
        var id = SnapshotIds.generate(at, SyncSource.LDAP);

        // then
        assertThat(id).isEqualTo("20260814T030000-LDAP");
    }

    @Test
    @DisplayName("시각이 뒤인 스냅샷 아이디가 사전순으로도 뒤에 온다")
    void 아이디_사전순은_시간순과_일치한다() {
        // given
        var 이른시각 = Instant.parse("2026-08-14T03:00:00Z");
        var 늦은시각 = Instant.parse("2026-08-15T03:00:00Z");

        // when
        var 이른아이디 = SnapshotIds.generate(이른시각, SyncSource.LDAP);
        var 늦은아이디 = SnapshotIds.generate(늦은시각, SyncSource.LDAP);

        // then
        assertThat(이른아이디).isLessThan(늦은아이디);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*TupleDiffTest*' --tests '*SnapshotIdsTest*'
```

Expected: 컴파일 실패 — `TupleDiff`, `SnapshotIds` 가 없다.

- [ ] **Step 3: 구현**

`core/src/main/java/dev/starryeye/organization/core/tuple/TupleDiff.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;

import java.util.HashSet;
import java.util.Set;

public final class TupleDiff {

    private TupleDiff() {
    }

    /**
     * @param baseline 직전 스냅샷. OpenFGA 에 반영되어 있다고 믿는 상태
     * @param target   이번에 읽어온 목표 상태
     */
    public static TupleDelta between(Set<RelationTuple> baseline, Set<RelationTuple> target) {
        Set<RelationTuple> safeBaseline = baseline == null ? Set.of() : baseline;
        Set<RelationTuple> safeTarget = target == null ? Set.of() : target;

        Set<RelationTuple> toWrite = new HashSet<>(safeTarget);
        toWrite.removeAll(safeBaseline);

        Set<RelationTuple> toDelete = new HashSet<>(safeBaseline);
        toDelete.removeAll(safeTarget);

        return new TupleDelta(toWrite, toDelete);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/tuple/SnapshotIds.java`:

```java
package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.SyncSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class SnapshotIds {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private SnapshotIds() {
    }

    public static String generate(Instant at, SyncSource source) {
        return FORMAT.format(at) + "-" + source.name();
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*TupleDiffTest*' --tests '*SnapshotIdsTest*'
```

Expected: 8개 테스트 모두 PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: TupleDiff 와 SnapshotIds 추가

직전 스냅샷과 목표 튜플 집합의 차집합으로 TupleDelta 를 만든다.
LDAP 이 0건을 반환하면 직전 스냅샷 전체가 삭제 대상이 되며,
이 결과를 삭제 가드가 판단한다.

스냅샷 아이디는 UTC 시각+소스 형식이라 사전순 정렬이 시간순과 같다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 5: DeletionGuard — 대량 삭제 안전장치

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuardPolicy.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/GuardDecision.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`

**Interfaces:**
- Consumes: Task 2의 `TupleDelta`, `RelationTuple`
- Produces:
  - `DeletionGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline)` + `defaults()` = `(true, 0.3, 10)`
  - `GuardDecision(boolean aborted, String message)` + `proceed()`, `abort(String)`
  - `DeletionGuard(DeletionGuardPolicy)` 생성자, `evaluate(TupleDelta, Set<RelationTuple>) -> GuardDecision`

**판정 규칙** (스펙 §9.2): 비활성화면 통과. 기준 스냅샷이 `minBaseline` 미만이면 비율이 무의미하므로 통과. 그 외에는 `toDelete.size() / baseline.size() > thresholdRatio` 일 때 중단.

- [ ] **Step 1: 실패하는 테스트 작성**

`core/src/test/java/dev/starryeye/organization/core/guard/DeletionGuardTest.java`:

```java
package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionGuardTest {

    private static Set<RelationTuple> 튜플들(int count) {
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        IntStream.range(0, count).forEach(i -> tuples.add(RelationTuple.directMember("user" + i, "DEV002")));
        return tuples;
    }

    private static Set<RelationTuple> 앞에서(Set<RelationTuple> source, int count) {
        return source.stream().limit(count).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("삭제 비율이 임계치 이하면 동기화를 진행한다")
    void 임계치_이하면_진행한다() {
        // given — 기준 100건 중 20건 삭제(20%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 20));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("삭제 비율이 임계치를 넘으면 중단하고 사유를 남긴다")
    void 임계치를_넘으면_중단한다() {
        // given — 기준 100건 중 68건 삭제(68%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 68));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isTrue();
        assertThat(decision.message()).contains("68").contains("30");
    }

    @Test
    @DisplayName("LDAP이 0건을 반환해 전건 삭제가 되면 반드시 중단한다")
    void 전건_삭제는_반드시_중단한다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(baseline);

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isTrue();
    }

    @Test
    @DisplayName("기준 스냅샷이 너무 작으면 비율이 무의미하므로 가드를 적용하지 않는다")
    void 기준이_작으면_가드를_적용하지_않는다() {
        // given — 기준 3건 중 2건 삭제(66%)지만 minBaseline 10 미만
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(3);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 2));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("최초 동기화처럼 기준 스냅샷이 비어 있으면 가드를 적용하지 않는다")
    void 기준이_비면_가드를_적용하지_않는다() {
        // given
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var delta = TupleDelta.writeOnly(튜플들(500));

        // when
        var decision = guard.evaluate(delta, Set.of());

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("가드를 비활성화하면 전건 삭제도 그대로 진행한다")
    void 비활성화하면_통과한다() {
        // given
        var guard = new DeletionGuard(new DeletionGuardPolicy(false, 0.3, 10));
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(baseline);

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }

    @Test
    @DisplayName("임계치와 정확히 같은 비율은 통과시킨다")
    void 임계치와_같으면_통과한다() {
        // given — 기준 100건 중 정확히 30건 삭제(30%), 임계치 30%
        var guard = new DeletionGuard(DeletionGuardPolicy.defaults());
        var baseline = 튜플들(100);
        var delta = TupleDelta.deleteOnly(앞에서(baseline, 30));

        // when
        var decision = guard.evaluate(delta, baseline);

        // then
        assertThat(decision.aborted()).isFalse();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*DeletionGuardTest*'
```

Expected: 컴파일 실패 — `DeletionGuard`, `DeletionGuardPolicy`, `GuardDecision` 이 없다.

- [ ] **Step 3: 구현**

`core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuardPolicy.java`:

```java
package dev.starryeye.organization.core.guard;

/**
 * @param thresholdRatio 삭제 허용 비율. 이 값을 <b>초과</b>하면 중단한다
 * @param minBaseline    기준 스냅샷이 이 크기 미만이면 비율이 무의미하므로 가드를 적용하지 않는다
 */
public record DeletionGuardPolicy(boolean enabled, double thresholdRatio, int minBaseline) {

    public static DeletionGuardPolicy defaults() {
        return new DeletionGuardPolicy(true, 0.3, 10);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/guard/GuardDecision.java`:

```java
package dev.starryeye.organization.core.guard;

public record GuardDecision(boolean aborted, String message) {

    public static GuardDecision proceed() {
        return new GuardDecision(false, null);
    }

    public static GuardDecision abort(String message) {
        return new GuardDecision(true, message);
    }
}
```

`core/src/main/java/dev/starryeye/organization/core/guard/DeletionGuard.java`:

```java
package dev.starryeye.organization.core.guard;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;

import java.util.Set;

/**
 * LDAP 전체 동기화가 잘못된 결과(필터 오류로 0건 응답, 부분 응답)를 가져왔을 때
 * 전직원 권한이 한 번에 날아가는 것을 막는다.
 *
 * <p>SCIM 의 의도된 단건 삭제와 rebuild 의 의도된 전체 삭제에는 적용하지 않는다.
 */
public class DeletionGuard {

    private final DeletionGuardPolicy policy;

    public DeletionGuard(DeletionGuardPolicy policy) {
        this.policy = policy;
    }

    public GuardDecision evaluate(TupleDelta delta, Set<RelationTuple> baseline) {
        if (!policy.enabled()) {
            return GuardDecision.proceed();
        }
        int baselineSize = baseline == null ? 0 : baseline.size();
        if (baselineSize < policy.minBaseline()) {
            return GuardDecision.proceed();
        }

        int deleteCount = delta.toDelete().size();
        double ratio = (double) deleteCount / baselineSize;
        if (ratio <= policy.thresholdRatio()) {
            return GuardDecision.proceed();
        }

        return GuardDecision.abort(
                "삭제 대상 %d건(기준 스냅샷 %d건의 %.1f%%)이 임계치 %.1f%%를 초과했습니다. 강제 실행하려면 force=true 로 재요청하세요"
                        .formatted(deleteCount, baselineSize, ratio * 100, policy.thresholdRatio() * 100));
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*DeletionGuardTest*'
```

Expected: 7개 테스트 모두 PASS. `임계치를_넘으면_중단한다` 는 메시지에 `68`(삭제 건수)과 `30`(임계치 퍼센트)이 들어가는지 본다.

- [ ] **Step 5: core 전체 테스트 확인**

Run:

```bash
./gradlew :core:test
```

Expected: 지금까지 작성한 모든 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: DeletionGuard 추가 — 대량 삭제 안전장치

LDAP 필터 오류로 0건이 오거나 부분 응답이 왔을 때 전직원 권한이
한 번에 날아가는 것을 막는다. 삭제 비율이 임계치(기본 30%)를 넘으면
OpenFGA 를 건드리지 않고 사유를 담아 중단한다.

기준 스냅샷이 minBaseline(기본 10) 미만이면 비율이 무의미하므로
적용하지 않는다. 최초 동기화가 여기에 해당한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 6: 포트 인터페이스와 FullSyncUseCase

**Files:**
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectorySnapshotSource.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/RelationTupleWriter.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/DirectoryStateRepository.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/TupleSnapshotRepository.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/port/SyncRunRepository.java`
- Create: `core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`
- Test: `core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`
- Test: `core/src/testFixtures/...` 대신 `core/src/test/java/dev/starryeye/organization/core/fake/` 아래에 fake 포트 4종

**Interfaces:**
- Consumes: Task 2~5 전부
- Produces:
  - 포트 5종 (아래 Step 1 시그니처 그대로)
  - `FullSyncUseCase(DirectorySnapshotSource, TupleSnapshotRepository, DirectoryStateRepository, RelationTupleWriter, SyncRunRepository, DeletionGuard, Clock)` 생성자
  - `FullSyncUseCase.execute(SyncTrigger) -> Mono<SyncRun>`
  - fake 4종: `FakeSnapshotSource`, `FakeTupleWriter`, `FakeSnapshotRepository`, `FakeStateRepository`, `FakeSyncRunRepository` — Task 16(RebuildUseCase)에서 재사용한다

> **스펙과의 차이:** 스펙 §4.4의 `SyncRunRepository.finish` 는 `Mono<Void>` 였으나, 관리 API가 완료된 `SyncRun` 을 응답으로 돌려줘야 하므로 `Mono<SyncRun>` 으로 바꾼다.

- [ ] **Step 1: 포트 인터페이스 5종 작성**

`DirectorySnapshotSource.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import reactor.core.publisher.Mono;

/**
 * 외부 디렉터리에서 전체 상태를 읽어온다.
 *
 * <p>이 인터페이스에는 증분이라는 개념이 없다. LDAP 이 pull 모델이라는 사실이 여기에 박혀 있다.
 * SCIM 인스턴스에는 이 빈이 존재하지 않는다.
 */
public interface DirectorySnapshotSource {

    Mono<DirectorySnapshot> fetchAll();
}
```

`RelationTupleWriter.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleWriteResult;
import reactor.core.publisher.Mono;

/**
 * 계산된 델타를 인가 시스템에 반영한다. 읽기 메서드는 의도적으로 두지 않는다.
 */
public interface RelationTupleWriter {

    Mono<TupleWriteResult> apply(TupleDelta delta);

    /** rebuild(store 모드) 전용. store 를 지우고 같은 이름으로 다시 만든 뒤 인가 모델을 등록한다. */
    Mono<Void> resetStore();
}
```

`DirectoryStateRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 조직·직원·멤버십의 <b>현재</b> 상태. 튜플이 아니라 도메인 상태를 담는다.
 */
public interface DirectoryStateRepository {

    Mono<DirectoryUser> findUser(String userId);

    Mono<DirectoryGroup> findGroup(String groupId);

    Mono<Void> saveUser(DirectoryUser user);

    /** 멤버십까지 포함해 교체한다. 기존 멤버십 중 사라진 것은 삭제된다. */
    Mono<Void> saveGroup(DirectoryGroup group);

    Mono<Void> deleteUser(String userId);

    Mono<Void> deleteGroup(String groupId);

    /** 역참조. SCIM 이 직원·조직을 삭제할 때 어느 조직의 튜플을 지워야 하는지 찾는다. */
    Flux<String> findGroupIdsContaining(MemberRef ref);

    /** LDAP 전체 동기화용. 스냅샷에 없는 기존 엔트리는 삭제된다. */
    Mono<Void> replaceWith(DirectorySnapshot snapshot);

    Mono<DirectorySnapshot> loadAll();
}
```

`TupleSnapshotRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p>OpenFGA read API 를 쓰지 않으므로 이것이 OpenFGA 상태를 대신하는 유일한 기록이다.
 */
public interface TupleSnapshotRepository {

    /** 없으면 빈 Mono */
    Mono<TupleSnapshot> findLatest();

    /** 튜플 → 메타 → 포인터 순으로 저장한다. 포인터를 마지막에 갱신해야 중간 실패가 안전하다. */
    Mono<Void> save(TupleSnapshot snapshot);

    Flux<SnapshotMeta> listRecent(int days);

    Mono<TupleSnapshot> findById(String snapshotId);

    /** rebuild 전용. 모든 스냅샷과 포인터를 지운다. */
    Mono<Void> reset();

    /** DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 명시적으로 정리한다. 삭제한 스냅샷 수를 반환한다. */
    Mono<Integer> purgeExpired();
}
```

`SyncRunRepository.java`:

```java
package dev.starryeye.organization.core.port;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SyncRunRepository {

    Mono<SyncRun> start(SyncSource source, SyncTrigger trigger);

    /** 완료된 SyncRun 을 반환한다. 관리 API 가 이 값을 응답으로 쓴다. */
    Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome);

    Flux<SyncRun> findRecent(int limit);
}
```

- [ ] **Step 2: fake 포트 구현 작성**

`core/src/test/java/dev/starryeye/organization/core/fake/FakeSnapshotSource.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

public class FakeSnapshotSource implements DirectorySnapshotSource {

    private DirectorySnapshot snapshot = DirectorySnapshot.empty();
    private RuntimeException failure;
    public final AtomicInteger fetchCount = new AtomicInteger();

    public void willReturn(DirectorySnapshot snapshot) {
        this.snapshot = snapshot;
        this.failure = null;
    }

    public void willFail(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public Mono<DirectorySnapshot> fetchAll() {
        fetchCount.incrementAndGet();
        return failure != null ? Mono.error(failure) : Mono.just(snapshot);
    }
}
```

`core/src/test/java/dev/starryeye/organization/core/fake/FakeTupleWriter.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleFailure;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class FakeTupleWriter implements RelationTupleWriter {

    public final List<TupleDelta> appliedDeltas = new ArrayList<>();
    public final AtomicInteger resetStoreCount = new AtomicInteger();

    /** 이 조건에 걸리는 튜플은 적용에 실패한 것으로 처리한다 */
    private Predicate<RelationTuple> failWhen = tuple -> false;

    public void failFor(Predicate<RelationTuple> failWhen) {
        this.failWhen = failWhen;
    }

    @Override
    public Mono<TupleWriteResult> apply(TupleDelta delta) {
        appliedDeltas.add(delta);

        Set<RelationTuple> written = new HashSet<>();
        Set<RelationTuple> deleted = new HashSet<>();
        List<TupleFailure> failures = new ArrayList<>();

        for (RelationTuple tuple : delta.toWrite()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                written.add(tuple);
            }
        }
        for (RelationTuple tuple : delta.toDelete()) {
            if (failWhen.test(tuple)) {
                failures.add(new TupleFailure(tuple, "테스트용 실패"));
            } else {
                deleted.add(tuple);
            }
        }
        return Mono.just(new TupleWriteResult(written, deleted, failures));
    }

    @Override
    public Mono<Void> resetStore() {
        resetStoreCount.incrementAndGet();
        return Mono.empty();
    }
}
```

`core/src/test/java/dev/starryeye/organization/core/fake/FakeSnapshotRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeSnapshotRepository implements TupleSnapshotRepository {

    public final List<TupleSnapshot> saved = new ArrayList<>();
    public final AtomicInteger resetCount = new AtomicInteger();

    @Override
    public Mono<TupleSnapshot> findLatest() {
        return saved.isEmpty() ? Mono.empty() : Mono.just(saved.get(saved.size() - 1));
    }

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        saved.add(snapshot);
        return Mono.empty();
    }

    @Override
    public Flux<SnapshotMeta> listRecent(int days) {
        return Flux.fromIterable(saved).map(TupleSnapshot::meta);
    }

    @Override
    public Mono<TupleSnapshot> findById(String snapshotId) {
        return Flux.fromIterable(saved).filter(s -> s.id().equals(snapshotId)).next();
    }

    @Override
    public Mono<Void> reset() {
        resetCount.incrementAndGet();
        saved.clear();
        return Mono.empty();
    }

    @Override
    public Mono<Integer> purgeExpired() {
        return Mono.just(0);
    }
}
```

`core/src/test/java/dev/starryeye/organization/core/fake/FakeStateRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

public class FakeStateRepository implements DirectoryStateRepository {

    public final Map<String, DirectoryUser> users = new LinkedHashMap<>();
    public final Map<String, DirectoryGroup> groups = new LinkedHashMap<>();

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return Mono.justOrEmpty(users.get(userId));
    }

    @Override
    public Mono<DirectoryGroup> findGroup(String groupId) {
        return Mono.justOrEmpty(groups.get(groupId));
    }

    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        users.put(user.id(), user);
        return Mono.empty();
    }

    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        groups.put(group.id(), group);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteUser(String userId) {
        users.remove(userId);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteGroup(String groupId) {
        groups.remove(groupId);
        return Mono.empty();
    }

    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        return Flux.fromIterable(groups.values())
                .filter(group -> group.members().contains(ref))
                .map(DirectoryGroup::id);
    }

    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        users.clear();
        groups.clear();
        users.putAll(snapshot.users());
        groups.putAll(snapshot.groups());
        return Mono.empty();
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        return Mono.just(new DirectorySnapshot(users, groups));
    }
}
```

`core/src/test/java/dev/starryeye/organization/core/fake/FakeSyncRunRepository.java`:

```java
package dev.starryeye.organization.core.fake;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FakeSyncRunRepository implements SyncRunRepository {

    public final List<SyncRun> finished = new ArrayList<>();
    private final Instant now;

    public FakeSyncRunRepository(Instant now) {
        this.now = now;
    }

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        return Mono.just(SyncRun.started(UUID.randomUUID().toString(), source, trigger, now));
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        SyncRun done = run.finished(outcome, now);
        finished.add(done);
        return Mono.just(done);
    }

    @Override
    public Flux<SyncRun> findRecent(int limit) {
        return Flux.fromIterable(finished).take(limit);
    }
}
```

- [ ] **Step 3: FullSyncUseCase 실패하는 테스트 작성**

`core/src/test/java/dev/starryeye/organization/core/usecase/FullSyncUseCaseTest.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.fake.FakeSnapshotRepository;
import dev.starryeye.organization.core.fake.FakeSnapshotSource;
import dev.starryeye.organization.core.fake.FakeStateRepository;
import dev.starryeye.organization.core.fake.FakeSyncRunRepository;
import dev.starryeye.organization.core.fake.FakeTupleWriter;
import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.DeletionGuardPolicy;
import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FullSyncUseCaseTest {

    private static final Instant 고정시각 = Instant.parse("2026-08-14T03:00:00Z");

    private FakeSnapshotSource source;
    private FakeSnapshotRepository snapshots;
    private FakeStateRepository state;
    private FakeTupleWriter writer;
    private FakeSyncRunRepository runs;
    private FullSyncUseCase useCase;

    @BeforeEach
    void setUp() {
        source = new FakeSnapshotSource();
        snapshots = new FakeSnapshotRepository();
        state = new FakeStateRepository();
        writer = new FakeTupleWriter();
        runs = new FakeSyncRunRepository(고정시각);
        useCase = new FullSyncUseCase(source, snapshots, state, writer, runs,
                new DeletionGuard(DeletionGuardPolicy.defaults()),
                Clock.fixed(고정시각, ZoneOffset.UTC));
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id, id, id, id + "@example.com", true);
    }

    private static DirectorySnapshot 조직도(Set<String> userIds, String groupCode) {
        Map<String, DirectoryUser> users = new LinkedHashMap<>();
        userIds.forEach(id -> users.put(id, 직원(id)));
        Set<MemberRef> members = userIds.stream().map(MemberRef::user).collect(Collectors.toSet());
        return new DirectorySnapshot(users,
                Map.of(groupCode, new DirectoryGroup(groupCode, "cn=" + groupCode, "백엔드팀", members)));
    }

    private static Set<RelationTuple> 소속튜플(int count, String groupCode) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, groupCode))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("최초 동기화는 읽어온 전체를 생성 대상으로 삼아 OpenFGA에 반영한다")
    void 최초_동기화는_전체를_생성한다() {
        // given
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.writtenCount()).isEqualTo(2);
        assertThat(run.deletedCount()).isZero();
        assertThat(writer.appliedDeltas).hasSize(1);
        assertThat(writer.appliedDeltas.get(0).toWrite()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("lee", "DEV002"));
    }

    @Test
    @DisplayName("동기화가 성공하면 새 스냅샷과 현재상태가 모두 저장된다")
    void 성공하면_스냅샷과_현재상태가_저장된다() {
        // given
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(snapshots.saved).hasSize(1);
        assertThat(snapshots.saved.get(0).id()).isEqualTo("20260814T030000-LDAP");
        assertThat(snapshots.saved.get(0).source()).isEqualTo(SyncSource.LDAP);
        assertThat(run.snapshotId()).isEqualTo("20260814T030000-LDAP");
        assertThat(state.users).containsKey("kim");
        assertThat(state.groups).containsKey("DEV002");
    }

    @Test
    @DisplayName("변경이 없으면 OpenFGA를 호출하지 않고 새 스냅샷도 만들지 않는다")
    void 변경이_없으면_아무것도_쓰지_않는다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim"), "DEV002"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.message()).isEqualTo("변경 없음");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("일부 튜플 적용에 실패하면 성공분만 새 스냅샷에 담겨 다음 동기화가 실패분을 다시 잡는다")
    void 부분_실패시_성공분만_스냅샷에_담긴다() {
        // given — 기존 스냅샷 없음, 목표 2건 중 lee 만 실패
        source.willReturn(조직도(Set.of("kim", "lee"), "DEV002"));
        writer.failFor(tuple -> tuple.user().equals("user:lee"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(snapshots.saved.get(0).tuples())
                .containsExactly(RelationTuple.directMember("kim", "DEV002"));
    }

    @Test
    @DisplayName("삭제 가드가 발동하면 OpenFGA를 건드리지 않고 사유와 함께 중단한다")
    void 삭제_가드가_발동하면_중단한다() {
        // given — 기준 20건, LDAP 이 0건을 반환해 전건 삭제 상황
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 소속튜플(20, "DEV002"))).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(run.message()).contains("임계치");
        assertThat(writer.appliedDeltas).isEmpty();
        assertThat(snapshots.saved).hasSize(1);
    }

    @Test
    @DisplayName("FORCED 트리거는 삭제 가드를 건너뛰고 전건 삭제를 진행한다")
    void 강제_실행은_가드를_건너뛴다() {
        // given
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP, 소속튜플(20, "DEV002"))).block();
        source.willReturn(DirectorySnapshot.empty());

        // when
        var run = useCase.execute(SyncTrigger.FORCED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(run.deletedCount()).isEqualTo(20);
        assertThat(snapshots.saved).hasSize(2);
        assertThat(snapshots.saved.get(1).tuples()).isEmpty();
    }

    @Test
    @DisplayName("LDAP 조회가 실패하면 FAILED로 기록하고 스냅샷과 현재상태를 건드리지 않는다")
    void 소스_실패는_FAILED로_기록된다() {
        // given
        source.willFail(new IllegalStateException("LDAP 연결 실패"));

        // when
        var run = useCase.execute(SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.message()).contains("LDAP 연결 실패");
        assertThat(snapshots.saved).isEmpty();
        assertThat(state.users).isEmpty();
    }

    @Test
    @DisplayName("새 스냅샷은 직전 스냅샷에서 삭제 성공분을 빼고 생성 성공분을 더한 결과가 된다")
    void 새_스냅샷은_직전_스냅샷_기준으로_계산된다() {
        // given — 직전 kim, lee / 목표 kim, park
        snapshots.save(new TupleSnapshot("이전", 고정시각, SyncSource.LDAP,
                Set.of(RelationTuple.directMember("kim", "DEV002"),
                       RelationTuple.directMember("lee", "DEV002")))).block();
        source.willReturn(조직도(Set.of("kim", "park"), "DEV002"));

        // when
        useCase.execute(SyncTrigger.FORCED).block();

        // then
        assertThat(snapshots.saved.get(1).tuples()).containsExactlyInAnyOrder(
                RelationTuple.directMember("kim", "DEV002"),
                RelationTuple.directMember("park", "DEV002"));
    }
}
```

- [ ] **Step 4: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*FullSyncUseCaseTest*'
```

Expected: 컴파일 실패 — `FullSyncUseCase` 가 없다.

- [ ] **Step 5: FullSyncUseCase 구현**

`core/src/main/java/dev/starryeye/organization/core/usecase/FullSyncUseCase.java`:

```java
package dev.starryeye.organization.core.usecase;

import dev.starryeye.organization.core.guard.DeletionGuard;
import dev.starryeye.organization.core.guard.GuardDecision;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.model.TupleDelta;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.model.TupleWriteResult;
import dev.starryeye.organization.core.port.DirectorySnapshotSource;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import dev.starryeye.organization.core.port.RelationTupleWriter;
import dev.starryeye.organization.core.port.SyncRunRepository;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import dev.starryeye.organization.core.tuple.SnapshotIds;
import dev.starryeye.organization.core.tuple.TupleDiff;
import dev.starryeye.organization.core.tuple.TupleMapper;
import dev.starryeye.organization.core.tuple.TupleMappingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * LDAP 전체 동기화.
 *
 * <p>핵심은 <b>OpenFGA 에 먼저 쓰고, 실제 성공한 튜플만 새 스냅샷으로 커밋</b>하는 것이다.
 * 실패한 튜플은 새 스냅샷에 들어가지 않으므로 다음 동기화의 diff 가 자동으로 다시 잡는다.
 * 재시도 큐도 상태머신도 필요 없는 이유가 이것이다.
 */
@Slf4j
@RequiredArgsConstructor
public class FullSyncUseCase {

    private final DirectorySnapshotSource source;
    private final TupleSnapshotRepository snapshots;
    private final DirectoryStateRepository state;
    private final RelationTupleWriter writer;
    private final SyncRunRepository runs;
    private final DeletionGuard guard;
    private final Clock clock;

    public Mono<SyncRun> execute(SyncTrigger trigger) {
        return runs.start(SyncSource.LDAP, trigger)
                .flatMap(run -> synchronize(trigger)
                        .onErrorResume(error -> {
                            log.error("[{}] 전체 동기화 실패", run.runId(), error);
                            return Mono.just(SyncOutcome.failed(error.getMessage()));
                        })
                        .flatMap(outcome -> runs.finish(run, outcome)));
    }

    private Mono<SyncOutcome> synchronize(SyncTrigger trigger) {
        return source.fetchAll().flatMap(directory -> {
            TupleMappingResult mapping = TupleMapper.toTuples(directory);
            mapping.warnings().forEach(warning -> log.warn("튜플 변환 경고: {}", warning));

            return baseline().flatMap(baseline -> {
                TupleDelta delta = TupleDiff.between(baseline, mapping.tuples());
                if (delta.isEmpty()) {
                    log.info("변경 없음. OpenFGA 를 호출하지 않는다");
                    return state.replaceWith(directory).thenReturn(SyncOutcome.noChange());
                }
                if (trigger != SyncTrigger.FORCED) {
                    GuardDecision decision = guard.evaluate(delta, baseline);
                    if (decision.aborted()) {
                        log.warn("삭제 가드 발동: {}", decision.message());
                        return Mono.just(SyncOutcome.aborted(decision.message()));
                    }
                }
                return writer.apply(delta)
                        .flatMap(result -> commit(directory, baseline, result));
            });
        });
    }

    private Mono<Set<RelationTuple>> baseline() {
        return snapshots.findLatest()
                .map(TupleSnapshot::tuples)
                .defaultIfEmpty(Set.of());
    }

    /**
     * 튜플 스냅샷과 현재상태는 <b>기준이 다르다</b>.
     * 스냅샷은 OpenFGA 에 실제 반영된 것, 현재상태는 LDAP 에서 읽은 사실 그대로다.
     */
    private Mono<SyncOutcome> commit(DirectorySnapshot directory,
                                     Set<RelationTuple> baseline,
                                     TupleWriteResult result) {
        Set<RelationTuple> committed = new HashSet<>(baseline);
        committed.removeAll(result.deleted());
        committed.addAll(result.written());

        TupleSnapshot snapshot = new TupleSnapshot(
                SnapshotIds.generate(clock.instant(), SyncSource.LDAP),
                clock.instant(),
                SyncSource.LDAP,
                committed);

        return snapshots.save(snapshot)
                .then(state.replaceWith(directory))
                .thenReturn(result.hasFailure()
                        ? SyncOutcome.partial(result, snapshot.id())
                        : SyncOutcome.succeeded(result, snapshot.id()));
    }
}
```

- [ ] **Step 6: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :core:test --tests '*FullSyncUseCaseTest*'
```

Expected: 8개 테스트 모두 PASS.

`강제_실행은_가드를_건너뛴다` 가 실패하면 `synchronize` 의 `trigger != SyncTrigger.FORCED` 조건을 확인한다. `부분_실패시_성공분만_스냅샷에_담긴다` 가 실패하면 `commit` 이 `result.written()` 이 아니라 `delta.toWrite()` 를 쓰고 있지 않은지 본다 — 이 구분이 이 설계의 핵심이다.

- [ ] **Step 7: core 전체 테스트 확인**

Run:

```bash
./gradlew :core:build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: 포트 인터페이스 5종과 FullSyncUseCase 추가

core 가 정의하고 어댑터가 구현할 포트를 확정했다. RelationTupleWriter 에는
읽기 메서드를 두지 않아 OpenFGA read API 미사용 원칙을 타입으로 강제한다.

FullSyncUseCase 는 OpenFGA 에 먼저 쓰고 실제 성공한 튜플만 새 스냅샷으로
커밋한다. 실패분은 새 스냅샷에 들어가지 않으므로 다음 동기화의 diff 가
자동으로 다시 잡는다. 재시도 큐나 상태머신이 필요 없는 이유다.

튜플 스냅샷은 OpenFGA 반영분, 현재상태는 LDAP 에서 읽은 사실 그대로로
기준을 다르게 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 7: DynamoDB 기반 — 키 설계, 클라이언트, 테이블 생성

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Attrs.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbProperties.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java`
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/TableInitializer.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTestSupport.java`
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableInitializerTest.java`

**Interfaces:**
- Consumes: Task 2의 `MemberRef`, `MemberType`, `RelationTuple`, `SyncSource`
- Produces:
  - `Keys` 상수/정적 메서드 (아래 Step 1 목록 전부)
  - `Attrs.s(String)`, `Attrs.n(Number)`, `Attrs.bool(boolean)`, `Attrs.str(Map<String,AttributeValue>, String)`, `Attrs.integer(...)`, `Attrs.flag(...)`, `Attrs.instant(...)`
  - `DynamoDbProperties` (`@ConfigurationProperties("dynamodb")`) — `endpoint`, `region`, `tableName`, `createTableOnStartup`, `snapshotRetentionDays`, `syncrunRetentionDays`
  - `DynamoDbConfig` → `DynamoDbAsyncClient` 빈
  - `TableInitializer.ensureTable() -> Mono<Void>`
  - `DynamoDbTestSupport` — Testcontainers 기반 추상 테스트 베이스. Task 8~10이 상속한다

- [ ] **Step 1: 키 설계 테스트 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/KeysTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.RelationTuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KeysTest {

    @Test
    @DisplayName("직원과 조직은 서로 다른 파티션 접두사를 가져 한 테이블에서 구분된다")
    void 직원과_조직의_파티션키가_구분된다() {
        // given, when
        String userPk = Keys.userPk("kim");
        String groupPk = Keys.groupPk("DEV002");

        // then
        assertThat(userPk).isEqualTo("USER#kim");
        assertThat(groupPk).isEqualTo("GROUP#DEV002");
    }

    @Test
    @DisplayName("멤버십 정렬키는 타입을 포함해 직원 멤버와 하위 조직 멤버를 구분한다")
    void 멤버십_정렬키는_타입을_포함한다() {
        // given, when
        String userMember = Keys.memberSk(MemberRef.user("kim"));
        String groupMember = Keys.memberSk(MemberRef.group("DEV002"));

        // then
        assertThat(userMember).isEqualTo("MEMBER#USER#kim");
        assertThat(groupMember).isEqualTo("MEMBER#GROUP#DEV002");
    }

    @Test
    @DisplayName("멤버십 정렬키를 그대로 GSI 파티션키로 써서 역참조가 가능해진다")
    void 멤버십_역참조_키는_정렬키와_같다() {
        // given
        MemberRef ref = MemberRef.user("kim");

        // when, then
        assertThat(Keys.memberGsi1Pk(ref)).isEqualTo(Keys.memberSk(ref));
    }

    @Test
    @DisplayName("튜플 정렬키는 왕복 변환해도 원래 튜플과 같다")
    void 튜플_정렬키는_왕복_변환된다() {
        // given
        RelationTuple tuple = RelationTuple.directMember("kim", "DEV002");

        // when
        String sk = Keys.tupleSk(tuple);
        RelationTuple parsed = Keys.parseTupleSk(sk);

        // then
        assertThat(sk).isEqualTo("TUPLE#user:kim|direct_member|group:DEV002");
        assertThat(parsed).isEqualTo(tuple);
    }

    @Test
    @DisplayName("한글 조직코드가 담긴 튜플도 왕복 변환된다")
    void 한글_조직코드_튜플도_왕복_변환된다() {
        // given
        RelationTuple tuple = RelationTuple.child("백엔드팀", "개발본부");

        // when
        RelationTuple parsed = Keys.parseTupleSk(Keys.tupleSk(tuple));

        // then
        assertThat(parsed).isEqualTo(tuple);
    }

    @Test
    @DisplayName("실행 이력 파티션키는 월 단위로 나뉘어 한 파티션이 무한히 커지지 않는다")
    void 실행이력_파티션키는_월단위다() {
        // given
        Instant at = Instant.parse("2026-08-14T03:00:00Z");

        // when
        String pk = Keys.syncRunPk(at);

        // then
        assertThat(pk).isEqualTo("SYNCRUN#2026-08");
    }

    @Test
    @DisplayName("실행 이력 정렬키는 시각이 앞에 와서 역순 조회가 최신순이 된다")
    void 실행이력_정렬키는_시각이_앞에_온다() {
        // given
        Instant 이른시각 = Instant.parse("2026-08-14T03:00:00Z");
        Instant 늦은시각 = Instant.parse("2026-08-14T04:00:00Z");

        // when
        String 이른키 = Keys.syncRunSk(이른시각, "run-b");
        String 늦은키 = Keys.syncRunSk(늦은시각, "run-a");

        // then
        assertThat(이른키).isLessThan(늦은키);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*KeysTest*'
```

Expected: 컴파일 실패 — `Keys` 가 없다.

- [ ] **Step 3: Keys 와 Attrs 구현**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Keys.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.model.MemberType;
import dev.starryeye.organization.core.model.RelationTuple;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

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
        return startedAt.toString() + "#" + runId;
    }
}
```

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/Attrs.java`:

```java
package dev.starryeye.organization.storage;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

/** AttributeValue 를 만들고 읽는 잡일을 한 곳에 모은다. */
public final class Attrs {

    private Attrs() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    public static AttributeValue n(Number value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    public static AttributeValue bool(boolean value) {
        return AttributeValue.builder().bool(value).build();
    }

    public static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || Boolean.TRUE.equals(value.nul()) ? null : value.s();
    }

    public static int integer(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0 : Integer.parseInt(value.n());
    }

    public static long longValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0L : Long.parseLong(value.n());
    }

    public static boolean flag(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    public static Instant instant(Map<String, AttributeValue> item, String name) {
        String raw = str(item, name);
        return raw == null ? null : Instant.parse(raw);
    }

    /** null 이면 아예 넣지 않는다. DynamoDB 는 빈 문자열을 허용하지만 null 은 허용하지 않는다. */
    public static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null && !value.isEmpty()) {
            item.put(name, s(value));
        }
    }
}
```

- [ ] **Step 4: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*KeysTest*'
```

Expected: 7개 테스트 모두 PASS.

- [ ] **Step 5: 설정과 클라이언트, 테이블 생성기 작성**

`DynamoDbProperties.java`:

```java
package dev.starryeye.organization.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("dynamodb")
public class DynamoDbProperties {

    /** DynamoDB Local 주소. 비우면 실제 AWS 엔드포인트를 쓴다 */
    private String endpoint;
    private String region = "ap-northeast-2";
    private String tableName = "organization";
    private boolean createTableOnStartup = true;
    private int snapshotRetentionDays = 7;
    private int syncrunRetentionDays = 30;
}
```

`DynamoDbConfig.java`:

```java
package dev.starryeye.organization.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbAsyncClient dynamoDbAsyncClient(DynamoDbProperties properties) {
        var builder = DynamoDbAsyncClient.builder().region(Region.of(properties.getRegion()));
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            // DynamoDB Local 은 자격증명을 검증하지 않지만 SDK 가 존재 자체는 요구한다
            builder.endpointOverride(URI.create(properties.getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    @Bean
    public TableInitializer tableInitializer(DynamoDbAsyncClient client, DynamoDbProperties properties) {
        return new TableInitializer(client, properties);
    }
}
```

`TableInitializer.java`:

```java
package dev.starryeye.organization.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class TableInitializer implements InitializingBean {

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    @Override
    public void afterPropertiesSet() {
        if (properties.isCreateTableOnStartup()) {
            ensureTable().block();
        }
    }

    public Mono<Void> ensureTable() {
        String table = properties.getTableName();
        return Mono.fromFuture(() -> client.describeTable(DescribeTableRequest.builder()
                        .tableName(table).build()))
                .doOnNext(response -> log.info("DynamoDB 테이블 '{}' 이 이미 존재한다", table))
                .then()
                .onErrorResume(ResourceNotFoundException.class, notFound -> createTable(table));
    }

    private Mono<Void> createTable(String table) {
        log.info("DynamoDB 테이블 '{}' 을 생성한다", table);
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        attribute(Keys.PK), attribute(Keys.SK),
                        attribute(Keys.GSI1PK), attribute(Keys.GSI1SK))
                .keySchema(
                        KeySchemaElement.builder().attributeName(Keys.PK).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(Keys.SK).keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(Keys.GSI1)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(Keys.GSI1PK).keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName(Keys.GSI1SK).keyType(KeyType.RANGE).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build();

        return Mono.fromFuture(() -> client.createTable(request)).then();
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }
}
```

> `ResourceNotFoundException` 은 `CompletableFuture` 안에서 `CompletionException` 으로 감싸여 올라온다. `onErrorResume(ResourceNotFoundException.class, ...)` 이 잡지 못하면 `Mono.fromFuture(...).onErrorMap(Exceptions::unwrap)` 을 앞에 붙인다. Step 8의 테스트가 이를 검증한다.

- [ ] **Step 6: Testcontainers 테스트 베이스 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTestSupport.java`:

```java
package dev.starryeye.organization.storage;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.net.URI;
import java.util.concurrent.CompletionException;

/**
 * DynamoDB Local 컨테이너를 한 번 띄우고 테스트마다 테이블을 새로 만든다.
 * 컨테이너 기동이 느리므로 클래스마다 띄우지 않고 static 으로 공유한다.
 */
@Testcontainers
public abstract class DynamoDbTestSupport {

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
            DockerImageName.parse("amazon/dynamodb-local:2.5.3"))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    protected DynamoDbAsyncClient client;
    protected DynamoDbProperties properties;

    @BeforeEach
    void 테이블을_새로_만든다() {
        String endpoint = "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000);

        properties = new DynamoDbProperties();
        properties.setEndpoint(endpoint);
        properties.setTableName("organization-test");
        properties.setCreateTableOnStartup(false);

        client = DynamoDbAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();

        dropTableIfExists();
        new TableInitializer(client, properties).ensureTable().block();
    }

    private void dropTableIfExists() {
        try {
            client.deleteTable(DeleteTableRequest.builder()
                    .tableName(properties.getTableName()).build()).join();
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof ResourceNotFoundException)) {
                throw e;
            }
        }
    }
}
```

- [ ] **Step 7: TableInitializer 테스트 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/TableInitializerTest.java`:

```java
package dev.starryeye.organization.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TableInitializerTest extends DynamoDbTestSupport {

    @Test
    @DisplayName("테이블이 없으면 PK/SK 와 GSI1 을 갖춘 테이블을 생성한다")
    void 테이블과_GSI를_생성한다() {
        // given — DynamoDbTestSupport 가 이미 ensureTable 을 호출했다

        // when
        var described = client.describeTable(DescribeTableRequest.builder()
                .tableName(properties.getTableName()).build()).join().table();

        // then
        assertThat(described.keySchema()).extracting(k -> k.attributeName())
                .containsExactly(Keys.PK, Keys.SK);
        assertThat(described.globalSecondaryIndexes()).hasSize(1);
        assertThat(described.globalSecondaryIndexes().get(0).indexName()).isEqualTo(Keys.GSI1);
        assertThat(described.globalSecondaryIndexes().get(0).keySchema())
                .extracting(k -> k.attributeName())
                .containsExactly(Keys.GSI1PK, Keys.GSI1SK);
    }

    @Test
    @DisplayName("테이블이 이미 있으면 다시 생성하지 않고 조용히 통과한다")
    void 이미_있으면_다시_만들지_않는다() {
        // given, when, then
        assertThatCode(() -> new TableInitializer(client, properties).ensureTable().block())
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 8: 테스트 실행**

Run:

```bash
./gradlew :storage-dynamodb:test
```

Expected: `KeysTest` 7개 + `TableInitializerTest` 2개 PASS. 첫 실행은 도커 이미지를 받느라 시간이 걸린다.

`이미_있으면_다시_만들지_않는다` 가 `ResourceNotFoundException` 관련으로 실패하면 Step 5의 주석대로 `onErrorMap(Exceptions::unwrap)` 을 `ensureTable` 의 `Mono.fromFuture` 뒤에 붙인다.

- [ ] **Step 9: 자동 설정 등록**

`storage-dynamodb/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
dev.starryeye.organization.storage.DynamoDbConfig
```

> `app-ldap` 의 `@SpringBootApplication(scanBasePackages = "dev.starryeye.organization")` 이 이미 이 패키지를 스캔하므로 자동 설정 파일은 없어도 동작한다. 다만 어댑터 모듈이 스스로 설정을 들고 다니는 편이 경계가 분명하므로 등록해 둔다. 중복 등록으로 빈이 두 번 만들어지지 않도록 `DynamoDbConfig` 에는 `@Configuration` 만 두고 `@ComponentScan` 은 두지 않는다.

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: DynamoDB 단일 테이블 키 설계와 테이블 초기화 추가

PK/SK/GSI1 키 규칙을 Keys 한 곳에 모아 저장소 구현들이 어긋나지 않게 했다.
멤버십 정렬키를 그대로 GSI 파티션키로 써서 역참조가 성립한다.
실행 이력은 월 단위 파티션으로 나눠 한 파티션이 무한히 커지지 않게 했다.

Testcontainers 기반 DynamoDbTestSupport 를 두어 이후 저장소 테스트가
실제 DynamoDB Local 위에서 돌아간다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 8: DynamoDbDirectoryStateRepository

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (빈 등록)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`

**Interfaces:**
- Consumes: Task 6의 `DirectoryStateRepository`, Task 7의 `Keys`/`Attrs`/`DynamoDbProperties`/`DynamoDbTestSupport`
- Produces: `DynamoDbDirectoryStateRepository(DynamoDbAsyncClient, DynamoDbProperties)` — `DirectoryStateRepository` 구현체 빈

**아이템 레이아웃** (스펙 §6):

| 아이템 | PK | SK | GSI1PK | GSI1SK |
|---|---|---|---|---|
| 직원 | `USER#<empId>` | `META` | `USER_INDEX` | `<userName>` |
| 조직 | `GROUP#<orgCode>` | `META` | `GROUP_INDEX` | `<displayName>` |
| 멤버십 | `GROUP#<orgCode>` | `MEMBER#<TYPE>#<id>` | `MEMBER#<TYPE>#<id>` | `GROUP#<orgCode>` |

- [ ] **Step 1: 실패하는 테스트 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepositoryTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbDirectoryStateRepositoryTest extends DynamoDbTestSupport {

    private DynamoDbDirectoryStateRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        repository = new DynamoDbDirectoryStateRepository(client, properties);
    }

    private static DirectoryUser 직원(String id) {
        return new DirectoryUser(id, "uid=" + id + ",ou=people", id, id + " 님", id + "@example.com", true);
    }

    private static DirectoryGroup 조직(String code, String name, MemberRef... members) {
        return new DirectoryGroup(code, "cn=" + code, name, Set.of(members));
    }

    @Test
    @DisplayName("저장한 직원을 직원 아이디로 그대로 조회한다")
    void 직원을_저장하고_조회한다() {
        // given
        var kim = 직원("kim");

        // when
        repository.saveUser(kim).block();
        var found = repository.findUser("kim").block();

        // then
        assertThat(found).isEqualTo(kim);
    }

    @Test
    @DisplayName("존재하지 않는 직원을 조회하면 빈 결과가 나온다")
    void 없는_직원은_빈_결과다() {
        // given, when
        var found = repository.findUser("ghost").block();

        // then
        assertThat(found).isNull();
    }

    @Test
    @DisplayName("저장한 조직을 조직코드로 조회하면 멤버십까지 함께 복원된다")
    void 조직을_멤버십까지_복원한다() {
        // given
        var dev002 = 조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"));

        // when
        repository.saveGroup(dev002).block();
        var found = repository.findGroup("DEV002").block();

        // then
        assertThat(found).isEqualTo(dev002);
    }

    @Test
    @DisplayName("조직을 다시 저장하면 사라진 멤버십은 삭제된다")
    void 조직_재저장시_사라진_멤버십은_삭제된다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))).block();

        // when
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        var found = repository.findGroup("DEV002").block();

        // then
        assertThat(found.members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("조직명이 바뀌어도 조직코드는 유지되어 멤버십이 보존된다")
    void 조직명_변경시_멤버십이_보존된다() {
        // given
        repository.saveGroup(조직("DEV001", "개발본부", MemberRef.user("park"))).block();

        // when
        repository.saveGroup(조직("DEV001", "플랫폼본부", MemberRef.user("park"))).block();
        var found = repository.findGroup("DEV001").block();

        // then
        assertThat(found.displayName()).isEqualTo("플랫폼본부");
        assertThat(found.members()).containsExactly(MemberRef.user("park"));
    }

    @Test
    @DisplayName("역참조로 특정 직원이 속한 모든 조직을 찾는다")
    void 직원이_속한_조직을_역참조로_찾는다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("OPS001", "운영팀", MemberRef.user("kim"))).block();
        repository.saveGroup(조직("SALES1", "영업팀", MemberRef.user("lee"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block();

        // then
        assertThat(groupIds).containsExactlyInAnyOrder("DEV002", "OPS001");
    }

    @Test
    @DisplayName("역참조는 하위 조직이 어느 상위 조직에 속하는지도 찾는다")
    void 하위조직의_상위조직을_역참조로_찾는다() {
        // given
        repository.saveGroup(조직("DEV001", "개발본부", MemberRef.group("DEV002"))).block();

        // when
        var groupIds = repository.findGroupIdsContaining(MemberRef.group("DEV002")).collectList().block();

        // then
        assertThat(groupIds).containsExactly("DEV001");
    }

    @Test
    @DisplayName("조직을 삭제하면 조직 자체와 멤버십 아이템이 모두 사라진다")
    void 조직_삭제시_멤버십도_사라진다() {
        // given
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"))).block();

        // when
        repository.deleteGroup("DEV002").block();

        // then
        assertThat(repository.findGroup("DEV002").block()).isNull();
        assertThat(repository.findGroupIdsContaining(MemberRef.user("kim")).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("전체 교체는 스냅샷에 없는 기존 직원과 조직을 삭제한다")
    void 전체_교체는_사라진_엔트리를_삭제한다() {
        // given
        repository.saveUser(직원("kim")).block();
        repository.saveUser(직원("lee")).block();
        repository.saveGroup(조직("DEV002", "백엔드팀", MemberRef.user("kim"), MemberRef.user("lee"))).block();
        repository.saveGroup(조직("OLD001", "폐지된조직")).block();

        var snapshot = new DirectorySnapshot(
                Map.of("kim", 직원("kim")),
                Map.of("DEV002", 조직("DEV002", "백엔드팀", MemberRef.user("kim"))));

        // when
        repository.replaceWith(snapshot).block();
        var loaded = repository.loadAll().block();

        // then
        assertThat(loaded.users()).containsOnlyKeys("kim");
        assertThat(loaded.groups()).containsOnlyKeys("DEV002");
        assertThat(loaded.groups().get("DEV002").members()).containsExactly(MemberRef.user("kim"));
    }

    @Test
    @DisplayName("전체 조회는 저장한 직원과 조직을 멤버십까지 그대로 복원한다")
    void 전체_조회가_스냅샷을_복원한다() {
        // given
        var snapshot = new DirectorySnapshot(
                Map.of("kim", 직원("kim"), "park", 직원("park")),
                Map.of("DEV001", 조직("DEV001", "개발본부", MemberRef.group("DEV002"), MemberRef.user("park")),
                       "DEV002", 조직("DEV002", "백엔드팀", MemberRef.user("kim"))));
        repository.replaceWith(snapshot).block();

        // when
        var loaded = repository.loadAll().block();

        // then
        assertThat(loaded).isEqualTo(snapshot);
    }

    @Test
    @DisplayName("한글 조직명이 담긴 조직도 저장하고 복원한다")
    void 한글_조직명도_왕복한다() {
        // given
        var 조직도 = 조직("개발본부", "개발본부", MemberRef.user("kim"));

        // when
        repository.saveGroup(조직도).block();
        var found = repository.findGroup("개발본부").block();

        // then
        assertThat(found).isEqualTo(조직도);
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*DirectoryStateRepositoryTest*'
```

Expected: 컴파일 실패 — `DynamoDbDirectoryStateRepository` 가 없다.

- [ ] **Step 3: 구현**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbDirectoryStateRepository.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.DirectoryGroup;
import dev.starryeye.organization.core.model.DirectorySnapshot;
import dev.starryeye.organization.core.model.DirectoryUser;
import dev.starryeye.organization.core.model.MemberRef;
import dev.starryeye.organization.core.port.DirectoryStateRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DynamoDbDirectoryStateRepository implements DirectoryStateRepository {

    private static final int QUERY_CONCURRENCY = 8;

    private static final String EXTERNAL_ID = "externalId";
    private static final String USER_NAME = "userName";
    private static final String DISPLAY_NAME = "displayName";
    private static final String EMAIL = "email";
    private static final String ACTIVE = "active";
    private static final String UPDATED_AT = "updatedAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;

    // ---------- 직원 ----------

    @Override
    public Mono<DirectoryUser> findUser(String userId) {
        return queryPartition(Keys.userPk(userId))
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .next()
                .map(item -> toUser(userId, item));
    }

    @Override
    public Mono<Void> saveUser(DirectoryUser user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.userPk(user.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.USER_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(user.userName() == null ? user.id() : user.userName()));
        item.put(ACTIVE, Attrs.bool(user.active()));
        item.put(UPDATED_AT, Attrs.s(Instant.now().toString()));
        Attrs.putIfPresent(item, EXTERNAL_ID, user.externalId());
        Attrs.putIfPresent(item, USER_NAME, user.userName());
        Attrs.putIfPresent(item, DISPLAY_NAME, user.displayName());
        Attrs.putIfPresent(item, EMAIL, user.email());

        return putItem(item);
    }

    @Override
    public Mono<Void> deleteUser(String userId) {
        return deleteItem(Keys.userPk(userId), Keys.META);
    }

    private DirectoryUser toUser(String userId, Map<String, AttributeValue> item) {
        return new DirectoryUser(
                userId,
                Attrs.str(item, EXTERNAL_ID),
                Attrs.str(item, USER_NAME),
                Attrs.str(item, DISPLAY_NAME),
                Attrs.str(item, EMAIL),
                Attrs.flag(item, ACTIVE));
    }

    // ---------- 조직 ----------

    @Override
    public Mono<DirectoryGroup> findGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .collectList()
                .flatMap(items -> Mono.justOrEmpty(toGroup(groupId, items)));
    }

    @Override
    public Mono<Void> saveGroup(DirectoryGroup group) {
        Map<String, AttributeValue> meta = new HashMap<>();
        meta.put(Keys.PK, Attrs.s(Keys.groupPk(group.id())));
        meta.put(Keys.SK, Attrs.s(Keys.META));
        meta.put(Keys.GSI1PK, Attrs.s(Keys.GROUP_INDEX));
        meta.put(Keys.GSI1SK, Attrs.s(group.displayName() == null ? group.id() : group.displayName()));
        meta.put(UPDATED_AT, Attrs.s(Instant.now().toString()));
        Attrs.putIfPresent(meta, EXTERNAL_ID, group.externalId());
        Attrs.putIfPresent(meta, DISPLAY_NAME, group.displayName());

        Set<String> targetSks = group.members().stream().map(Keys::memberSk).collect(Collectors.toSet());

        return existingMemberSks(group.id())
                .filter(sk -> !targetSks.contains(sk))
                .flatMap(sk -> deleteItem(Keys.groupPk(group.id()), sk), QUERY_CONCURRENCY)
                .then(putItem(meta))
                .then(Flux.fromIterable(group.members())
                        .flatMap(member -> putItem(memberItem(group.id(), member)), QUERY_CONCURRENCY)
                        .then());
    }

    @Override
    public Mono<Void> deleteGroup(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .flatMap(sk -> deleteItem(Keys.groupPk(groupId), sk), QUERY_CONCURRENCY)
                .then();
    }

    private Map<String, AttributeValue> memberItem(String groupId, MemberRef member) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.groupPk(groupId)));
        item.put(Keys.SK, Attrs.s(Keys.memberSk(member)));
        item.put(Keys.GSI1PK, Attrs.s(Keys.memberGsi1Pk(member)));
        item.put(Keys.GSI1SK, Attrs.s(Keys.groupPk(groupId)));
        item.put("addedAt", Attrs.s(Instant.now().toString()));
        return item;
    }

    private Flux<String> existingMemberSks(String groupId) {
        return queryPartition(Keys.groupPk(groupId))
                .map(item -> Attrs.str(item, Keys.SK))
                .filter(sk -> sk.startsWith("MEMBER#"));
    }

    private DirectoryGroup toGroup(String groupId, List<Map<String, AttributeValue>> items) {
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return null;
        }
        Set<MemberRef> members = items.stream()
                .map(item -> Attrs.str(item, Keys.SK))
                .filter(sk -> sk.startsWith("MEMBER#"))
                .map(Keys::parseMemberSk)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new DirectoryGroup(groupId, Attrs.str(meta, EXTERNAL_ID), Attrs.str(meta, DISPLAY_NAME), members);
    }

    // ---------- 역참조 ----------

    @Override
    public Flux<String> findGroupIdsContaining(MemberRef ref) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.memberGsi1Pk(ref))))
                .build();

        return paginate(request).map(item -> stripPrefix(Attrs.str(item, Keys.PK), "GROUP#"));
    }

    // ---------- 전체 ----------

    @Override
    public Mono<Void> replaceWith(DirectorySnapshot snapshot) {
        Mono<Void> removeStaleUsers = enumerateIds(Keys.USER_INDEX, "USER#")
                .filter(id -> !snapshot.users().containsKey(id))
                .flatMap(this::deleteUser, QUERY_CONCURRENCY)
                .then();

        Mono<Void> removeStaleGroups = enumerateIds(Keys.GROUP_INDEX, "GROUP#")
                .filter(id -> !snapshot.groups().containsKey(id))
                .flatMap(this::deleteGroup, QUERY_CONCURRENCY)
                .then();

        Mono<Void> upsertUsers = Flux.fromIterable(snapshot.users().values())
                .flatMap(this::saveUser, QUERY_CONCURRENCY)
                .then();

        Mono<Void> upsertGroups = Flux.fromIterable(snapshot.groups().values())
                .flatMap(this::saveGroup, QUERY_CONCURRENCY)
                .then();

        return removeStaleUsers.then(removeStaleGroups).then(upsertUsers).then(upsertGroups);
    }

    @Override
    public Mono<DirectorySnapshot> loadAll() {
        Mono<Map<String, DirectoryUser>> users = enumerateIds(Keys.USER_INDEX, "USER#")
                .flatMap(this::findUser, QUERY_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, user) -> map.put(user.id(), user));

        Mono<Map<String, DirectoryGroup>> groups = enumerateIds(Keys.GROUP_INDEX, "GROUP#")
                .flatMap(this::findGroup, QUERY_CONCURRENCY)
                .collect(LinkedHashMap::new, (map, group) -> map.put(group.id(), group));

        return Mono.zip(users, groups)
                .map(both -> new DirectorySnapshot(both.getT1(), both.getT2()));
    }

    /** GSI1 파티션을 훑어 PK 접두사를 떼고 id 만 뽑는다. Scan 을 쓰지 않는 이유는 스펙 §6.1 참고. */
    private Flux<String> enumerateIds(String indexPartition, String pkPrefix) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(indexPartition)))
                .build();

        return paginate(request).map(item -> stripPrefix(Attrs.str(item, Keys.PK), pkPrefix));
    }

    // ---------- 공통 ----------

    private Flux<Map<String, AttributeValue>> queryPartition(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .build();
        return paginate(request);
    }

    /** LastEvaluatedKey 를 따라가며 전체 페이지를 이어붙인다. */
    private Flux<Map<String, AttributeValue>> paginate(QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> page = Flux.fromIterable(response.items());
                    if (response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()) {
                        return page;
                    }
                    QueryRequest next = request.toBuilder()
                            .exclusiveStartKey(response.lastEvaluatedKey())
                            .build();
                    return page.concatWith(paginate(next));
                });
    }

    private Mono<Void> putItem(Map<String, AttributeValue> item) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    private Mono<Void> deleteItem(String pk, String sk) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build())).then();
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
```

- [ ] **Step 4: 빈 등록**

`DynamoDbConfig.java` 에 추가:

```java
    @Bean
    public DynamoDbDirectoryStateRepository dynamoDbDirectoryStateRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties) {
        return new DynamoDbDirectoryStateRepository(client, properties);
    }
```

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*DirectoryStateRepositoryTest*'
```

Expected: 11개 테스트 모두 PASS.

`전체_조회가_스냅샷을_복원한다` 가 `Map` 순서 때문에 실패하지는 않는다 — `Map.equals` 는 순서를 보지 않는다. 실패한다면 `DirectoryUser`/`DirectoryGroup` 의 필드 하나가 왕복에서 유실된 것이므로, 어떤 필드인지 assertion 메시지에서 확인한다. `externalId` 가 `null` 로 돌아온다면 `Attrs.putIfPresent` 가 빈 문자열을 걸러낸 탓이니 테스트 픽스처의 값을 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: DynamoDbDirectoryStateRepository 추가

조직·직원·멤버십의 현재 상태를 단일 테이블에 저장한다. 조직 파티션을
한 번 Query 하면 META 와 멤버십이 함께 나오고, 멤버십 정렬키를 GSI
파티션키로 재사용해 "이 직원이 속한 조직들" 역참조가 성립한다.

전체 열거는 Scan 대신 GSI1 의 USER_INDEX / GROUP_INDEX 파티션을 쓴다.
Scan 은 같은 테이블의 스냅샷 튜플까지 읽어 열거 용도로 부적절하다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 9: DynamoDbTupleSnapshotRepository

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (빈 등록)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`

**Interfaces:**
- Consumes: Task 6의 `TupleSnapshotRepository`, Task 7의 `Keys`/`Attrs`/`DynamoDbTestSupport`
- Produces: `DynamoDbTupleSnapshotRepository(DynamoDbAsyncClient, DynamoDbProperties, Clock)` — `TupleSnapshotRepository` 구현체 빈

**저장 순서가 중요하다.** 튜플 → 메타 → 포인터. 포인터를 마지막에 갱신해야 중간에 죽어도 다음 동기화가 직전 스냅샷을 정상적으로 읽는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepositoryTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbTupleSnapshotRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private DynamoDbTupleSnapshotRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        properties.setSnapshotRetentionDays(7);
        repository = new DynamoDbTupleSnapshotRepository(client, properties,
                Clock.fixed(지금, ZoneOffset.UTC));
    }

    private static TupleSnapshot 스냅샷(String id, Instant at, Set<RelationTuple> tuples) {
        return new TupleSnapshot(id, at, SyncSource.LDAP, tuples);
    }

    private static Set<RelationTuple> 튜플들(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> RelationTuple.directMember("user" + i, "DEV002"))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("저장한 스냅샷이 최신 스냅샷으로 조회된다")
    void 저장한_스냅샷이_최신으로_조회된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(3));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260814T030000-LDAP");
        assertThat(latest.source()).isEqualTo(SyncSource.LDAP);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("스냅샷이 하나도 없으면 최신 조회는 빈 결과를 준다")
    void 스냅샷이_없으면_빈_결과다() {
        // given, when
        var latest = repository.findLatest().block();

        // then
        assertThat(latest).isNull();
    }

    @Test
    @DisplayName("나중에 저장한 스냅샷이 최신 스냅샷을 덮어쓴다")
    void 나중_스냅샷이_최신이_된다() {
        // given
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(3))).block();

        // when
        repository.save(스냅샷("20260815T030000-LDAP", 지금.plusSeconds(86400), 튜플들(5))).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.id()).isEqualTo("20260815T030000-LDAP");
        assertThat(latest.tuples()).hasSize(5);
    }

    @Test
    @DisplayName("DynamoDB 배치 한계인 25건을 넘는 튜플도 나누어 저장되고 전부 복원된다")
    void 배치_한계를_넘는_튜플도_저장된다() {
        // given
        var snapshot = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(120));

        // when
        repository.save(snapshot).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).hasSize(120);
        assertThat(latest.tuples()).isEqualTo(snapshot.tuples());
    }

    @Test
    @DisplayName("한글 조직코드가 담긴 튜플도 저장 후 그대로 복원된다")
    void 한글_조직코드_튜플도_복원된다() {
        // given
        var tuples = Set.of(RelationTuple.child("백엔드팀", "개발본부"),
                            RelationTuple.directMember("kim", "백엔드팀"));

        // when
        repository.save(스냅샷("20260814T030000-LDAP", 지금, tuples)).block();
        var latest = repository.findLatest().block();

        // then
        assertThat(latest.tuples()).isEqualTo(tuples);
    }

    @Test
    @DisplayName("아이디로 과거 스냅샷을 직접 조회할 수 있다")
    void 아이디로_과거_스냅샷을_조회한다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var old = repository.findById("20260813T030000-LDAP").block();

        // then
        assertThat(old.tuples()).hasSize(2);
    }

    @Test
    @DisplayName("최근 스냅샷 목록은 최신순으로 나온다")
    void 최근_목록은_최신순이다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        var metas = repository.listRecent(7).collectList().block();

        // then
        assertThat(metas).extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP", "20260813T030000-LDAP");
        assertThat(metas.get(0).tupleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("리셋하면 모든 스냅샷과 최신 포인터가 사라진다")
    void 리셋하면_전부_사라진다() {
        // given
        repository.save(스냅샷("20260813T030000-LDAP", 지금.minusSeconds(86400), 튜플들(2))).block();
        repository.save(스냅샷("20260814T030000-LDAP", 지금, 튜플들(4))).block();

        // when
        repository.reset().block();

        // then
        assertThat(repository.findLatest().block()).isNull();
        assertThat(repository.listRecent(30).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("보존 기간이 지난 스냅샷만 정리되고 최근 것은 남는다")
    void 만료된_스냅샷만_정리된다() {
        // given — 보존 7일. 10일 전 것은 만료, 오늘 것은 유효
        var 만료된시각 = 지금.minusSeconds(10 * 86400);
        var 만료스냅샷 = new TupleSnapshot("20260804T030000-LDAP", 만료된시각, SyncSource.LDAP, 튜플들(2));
        var 유효스냅샷 = 스냅샷("20260814T030000-LDAP", 지금, 튜플들(4));

        repository.saveWithCreatedAt(만료스냅샷).block();
        repository.save(유효스냅샷).block();

        // when
        var purged = repository.purgeExpired().block();

        // then
        assertThat(purged).isEqualTo(1);
        assertThat(repository.listRecent(30).collectList().block())
                .extracting(m -> m.id())
                .containsExactly("20260814T030000-LDAP");
        assertThat(repository.findLatest().block().id()).isEqualTo("20260814T030000-LDAP");
    }
}
```

> `saveWithCreatedAt` 은 만료 테스트를 위해 `snapshot.createdAt()` 기준으로 TTL 을 계산하는 변형이다. 일반 `save` 는 `Clock` 의 현재 시각으로 TTL 을 잡으므로 과거 스냅샷을 만들 수 없다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*TupleSnapshotRepositoryTest*'
```

Expected: 컴파일 실패 — `DynamoDbTupleSnapshotRepository` 가 없다.

- [ ] **Step 3: 구현**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbTupleSnapshotRepository.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.RelationTuple;
import dev.starryeye.organization.core.model.SnapshotMeta;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.TupleSnapshot;
import dev.starryeye.organization.core.port.TupleSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenFGA 에 실제로 반영된 튜플의 기록.
 *
 * <p>저장 순서는 튜플 → 메타 → 포인터다. 포인터를 마지막에 갱신해야
 * 중간에 죽어도 다음 동기화가 직전 스냅샷을 정상적으로 읽는다.
 */
@Slf4j
@RequiredArgsConstructor
public class DynamoDbTupleSnapshotRepository implements TupleSnapshotRepository {

    private static final int BATCH_SIZE = 25;
    private static final int DELETE_CONCURRENCY = 4;

    private static final String CREATED_AT = "createdAt";
    private static final String SOURCE = "source";
    private static final String TUPLE_COUNT = "tupleCount";
    private static final String EXPIRES_AT = "expiresAt";
    private static final String SNAPSHOT_ID = "snapshotId";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<Void> save(TupleSnapshot snapshot) {
        return doSave(snapshot, clock.instant());
    }

    /** 테스트에서 과거 시각의 스냅샷을 만들기 위한 변형. TTL 을 snapshot.createdAt 기준으로 잡는다. */
    public Mono<Void> saveWithCreatedAt(TupleSnapshot snapshot) {
        return doSave(snapshot, snapshot.createdAt());
    }

    private Mono<Void> doSave(TupleSnapshot snapshot, Instant ttlBase) {
        long expiresAt = ttlBase.plus(Duration.ofDays(properties.getSnapshotRetentionDays())).getEpochSecond();

        return writeTuples(snapshot, expiresAt)
                .then(writeMeta(snapshot, expiresAt))
                .then(writePointer(snapshot.id()));
    }

    private Mono<Void> writeTuples(TupleSnapshot snapshot, long expiresAt) {
        return Flux.fromIterable(snapshot.tuples())
                .map(tuple -> WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(tupleItem(snapshot.id(), tuple, expiresAt)).build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then();
    }

    private Map<String, AttributeValue> tupleItem(String snapshotId, RelationTuple tuple, long expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)));
        item.put(Keys.SK, Attrs.s(Keys.tupleSk(tuple)));
        item.put(EXPIRES_AT, Attrs.n(expiresAt));
        return item;
    }

    private Mono<Void> writeMeta(TupleSnapshot snapshot, long expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.snapshotPk(snapshot.id())));
        item.put(Keys.SK, Attrs.s(Keys.META));
        item.put(Keys.GSI1PK, Attrs.s(Keys.SNAPSHOT_INDEX));
        item.put(Keys.GSI1SK, Attrs.s(snapshot.createdAt().toString()));
        item.put(CREATED_AT, Attrs.s(snapshot.createdAt().toString()));
        item.put(SOURCE, Attrs.s(snapshot.source().name()));
        item.put(TUPLE_COUNT, Attrs.n(snapshot.tuples().size()));
        item.put(EXPIRES_AT, Attrs.n(expiresAt));
        return putItem(item);
    }

    private Mono<Void> writePointer(String snapshotId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER));
        item.put(Keys.SK, Attrs.s(Keys.LATEST));
        item.put(SNAPSHOT_ID, Attrs.s(snapshotId));
        return putItem(item);
    }

    @Override
    public Mono<TupleSnapshot> findLatest() {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.SNAPSHOT_POINTER), Keys.SK, Attrs.s(Keys.LATEST)))
                        .build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> Attrs.str(response.item(), SNAPSHOT_ID))
                .flatMap(this::findById);
    }

    @Override
    public Mono<TupleSnapshot> findById(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .collectList()
                .flatMap(items -> Mono.justOrEmpty(toSnapshot(snapshotId, items)));
    }

    private TupleSnapshot toSnapshot(String snapshotId, List<Map<String, AttributeValue>> items) {
        Map<String, AttributeValue> meta = items.stream()
                .filter(item -> Keys.META.equals(Attrs.str(item, Keys.SK)))
                .findFirst()
                .orElse(null);
        if (meta == null) {
            return null;
        }
        Set<RelationTuple> tuples = new LinkedHashSet<>();
        for (Map<String, AttributeValue> item : items) {
            String sk = Attrs.str(item, Keys.SK);
            if (sk.startsWith("TUPLE#")) {
                tuples.add(Keys.parseTupleSk(sk));
            }
        }
        return new TupleSnapshot(
                snapshotId,
                Attrs.instant(meta, CREATED_AT),
                SyncSource.valueOf(Attrs.str(meta, SOURCE)),
                tuples);
    }

    @Override
    public Flux<SnapshotMeta> listRecent(int days) {
        Instant from = clock.instant().minus(Duration.ofDays(days));
        return snapshotMetas()
                .filter(meta -> !meta.createdAt().isBefore(from));
    }

    /** GSI1 SNAPSHOT_INDEX 파티션을 createdAt 역순으로 훑는다. */
    private Flux<SnapshotMeta> snapshotMetas() {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .indexName(Keys.GSI1)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.GSI1PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(Keys.SNAPSHOT_INDEX)))
                .scanIndexForward(false)
                .build();

        return paginate(request).map(item -> new SnapshotMeta(
                stripPrefix(Attrs.str(item, Keys.PK), "SNAPSHOT#"),
                Attrs.instant(item, CREATED_AT),
                SyncSource.valueOf(Attrs.str(item, SOURCE)),
                Attrs.integer(item, TUPLE_COUNT)));
    }

    @Override
    public Mono<Void> reset() {
        return snapshotMetas()
                .flatMap(meta -> deleteSnapshot(meta.id()), DELETE_CONCURRENCY)
                .then(deleteItem(Keys.SNAPSHOT_POINTER, Keys.LATEST));
    }

    @Override
    public Mono<Integer> purgeExpired() {
        long now = clock.instant().getEpochSecond();
        return snapshotMetas()
                .filterWhen(meta -> isExpired(meta.id(), now))
                .flatMap(meta -> deleteSnapshot(meta.id()).thenReturn(1), DELETE_CONCURRENCY)
                .reduce(0, Integer::sum)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("만료된 스냅샷 {}건을 정리했다", count);
                    }
                });
    }

    private Mono<Boolean> isExpired(String snapshotId, long nowEpochSecond) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder()
                        .tableName(properties.getTableName())
                        .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)), Keys.SK, Attrs.s(Keys.META)))
                        .build()))
                .map(response -> Attrs.longValue(response.item(), EXPIRES_AT) <= nowEpochSecond);
    }

    private Mono<Void> deleteSnapshot(String snapshotId) {
        return queryPartition(Keys.snapshotPk(snapshotId))
                .map(item -> WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(Keys.PK, Attrs.s(Keys.snapshotPk(snapshotId)),
                                        Keys.SK, Attrs.s(Attrs.str(item, Keys.SK))))
                                .build())
                        .build())
                .buffer(BATCH_SIZE)
                .concatMap(this::batchWrite)
                .then();
    }

    // ---------- 공통 ----------

    /** UnprocessedItems 가 남으면 다시 보낸다. DynamoDB 는 배치 일부를 거절할 수 있다. */
    private Mono<Void> batchWrite(List<WriteRequest> requests) {
        if (requests.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(properties.getTableName(), requests))
                        .build()))
                .flatMap(response -> {
                    List<WriteRequest> unprocessed =
                            response.unprocessedItems().getOrDefault(properties.getTableName(), List.of());
                    return unprocessed.isEmpty() ? Mono.empty() : batchWrite(unprocessed);
                })
                .then();
    }

    private Flux<Map<String, AttributeValue>> queryPartition(String pk) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s(pk)))
                .build();
        return paginate(request);
    }

    private Flux<Map<String, AttributeValue>> paginate(QueryRequest request) {
        return Mono.fromFuture(() -> client.query(request))
                .flatMapMany(response -> {
                    Flux<Map<String, AttributeValue>> page = Flux.fromIterable(response.items());
                    if (response.lastEvaluatedKey() == null || response.lastEvaluatedKey().isEmpty()) {
                        return page;
                    }
                    return page.concatWith(paginate(
                            request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build()));
                });
    }

    private Mono<Void> putItem(Map<String, AttributeValue> item) {
        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    private Mono<Void> deleteItem(String pk, String sk) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder()
                .tableName(properties.getTableName())
                .key(Map.of(Keys.PK, Attrs.s(pk), Keys.SK, Attrs.s(sk)))
                .build())).then();
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }
}
```

- [ ] **Step 4: 빈 등록**

`DynamoDbConfig.java` 에 추가 (`Clock` 빈이 없으면 함께 등록한다):

```java
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DynamoDbTupleSnapshotRepository dynamoDbTupleSnapshotRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbTupleSnapshotRepository(client, properties, clock);
    }
```

임포트에 `java.time.Clock` 과 `org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean` 을 추가한다.

- [ ] **Step 5: 테스트가 통과하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*TupleSnapshotRepositoryTest*'
```

Expected: 9개 테스트 모두 PASS.

`만료된_스냅샷만_정리된다` 가 실패하면 `purgeExpired` 가 `expiresAt` 을 초 단위 epoch 로 비교하는지 확인한다. `리셋하면_전부_사라진다` 가 포인터를 남기면 `reset` 의 `then(deleteItem(...))` 순서를 확인한다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: DynamoDbTupleSnapshotRepository 추가

OpenFGA read API 를 쓰지 않으므로 이 스냅샷이 OpenFGA 상태를 대신하는
유일한 기록이다. 저장은 튜플 → 메타 → 포인터 순서로, 포인터를 마지막에
갱신해야 중간 실패에도 다음 동기화가 직전 스냅샷을 정상적으로 읽는다.

DynamoDB Local 은 TTL 자동 삭제를 하지 않으므로 purgeExpired 로 명시
정리한다. 실제 AWS 에서는 TTL 이 처리하고 이 잡은 0건을 반환한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---

## Task 10: DynamoDbSyncRunRepository

**Files:**
- Create: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`
- Modify: `storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbConfig.java` (빈 등록)
- Test: `storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`

**Interfaces:**
- Consumes: Task 6의 `SyncRunRepository`, Task 7의 `Keys`/`Attrs`/`DynamoDbTestSupport`
- Produces: `DynamoDbSyncRunRepository(DynamoDbAsyncClient, DynamoDbProperties, Clock)` — `SyncRunRepository` 구현체 빈

- [ ] **Step 1: 실패하는 테스트 작성**

`storage-dynamodb/src/test/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepositoryTest.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbSyncRunRepositoryTest extends DynamoDbTestSupport {

    private static final Instant 지금 = Instant.parse("2026-08-14T03:00:00Z");

    private DynamoDbSyncRunRepository repository;

    @BeforeEach
    void 저장소를_준비한다() {
        repository = new DynamoDbSyncRunRepository(client, properties, Clock.fixed(지금, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("시작한 실행은 RUNNING 상태로 기록되고 고유한 아이디를 받는다")
    void 시작하면_RUNNING으로_기록된다() {
        // given, when
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();

        // then
        assertThat(run.status()).isEqualTo(SyncStatus.RUNNING);
        assertThat(run.runId()).isNotBlank();
        assertThat(run.source()).isEqualTo(SyncSource.LDAP);
        assertThat(run.trigger()).isEqualTo(SyncTrigger.SCHEDULED);
        assertThat(run.startedAt()).isEqualTo(지금);
    }

    @Test
    @DisplayName("완료 처리하면 상태와 집계값이 반영된 실행 이력이 조회된다")
    void 완료하면_집계값이_반영된다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
        var outcome = new SyncOutcome(SyncStatus.SUCCEEDED, 12, 3, 0, "20260814T030000-LDAP", null);

        // when
        var finished = repository.finish(run, outcome).block();
        var recent = repository.findRecent(10).collectList().block();

        // then
        assertThat(finished.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(finished.writtenCount()).isEqualTo(12);
        assertThat(finished.deletedCount()).isEqualTo(3);
        assertThat(finished.snapshotId()).isEqualTo("20260814T030000-LDAP");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).runId()).isEqualTo(run.runId());
    }

    @Test
    @DisplayName("가드가 발동해 중단된 실행은 ABORTED 상태와 사유가 함께 남는다")
    void 중단된_실행은_사유가_남는다() {
        // given
        var run = repository.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
        var 사유 = "삭제 대상 412건(기준 스냅샷 606건의 68.0%)이 임계치 30.0%를 초과했습니다";

        // when
        repository.finish(run, SyncOutcome.aborted(사유)).block();
        var recent = repository.findRecent(10).collectList().block();

        // then
        assertThat(recent.get(0).status()).isEqualTo(SyncStatus.ABORTED);
        assertThat(recent.get(0).message()).isEqualTo(사유);
    }

    @Test
    @DisplayName("최근 실행 이력은 최신순으로 나오고 limit 만큼만 반환된다")
    void 최근_이력은_최신순이고_개수가_제한된다() {
        // given — 시각을 다르게 해서 3건 기록
        for (int i = 0; i < 3; i++) {
            var repo = new DynamoDbSyncRunRepository(client, properties,
                    Clock.fixed(지금.plusSeconds(i * 60L), ZoneOffset.UTC));
            var run = repo.start(SyncSource.LDAP, SyncTrigger.SCHEDULED).block();
            repo.finish(run, SyncOutcome.noChange()).block();
        }

        // when
        var recent = repository.findRecent(2).collectList().block();

        // then
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).startedAt()).isAfter(recent.get(1).startedAt());
    }

    @Test
    @DisplayName("이력이 없으면 빈 목록을 반환한다")
    void 이력이_없으면_빈_목록이다() {
        // given, when
        var recent = repository.findRecent(10).collectList().block();

        // then
        assertThat(recent).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run:

```bash
./gradlew :storage-dynamodb:test --tests '*SyncRunRepositoryTest*'
```

Expected: 컴파일 실패 — `DynamoDbSyncRunRepository` 가 없다.

- [ ] **Step 3: 구현**

`storage-dynamodb/src/main/java/dev/starryeye/organization/storage/DynamoDbSyncRunRepository.java`:

```java
package dev.starryeye.organization.storage;

import dev.starryeye.organization.core.model.SyncOutcome;
import dev.starryeye.organization.core.model.SyncRun;
import dev.starryeye.organization.core.model.SyncSource;
import dev.starryeye.organization.core.model.SyncStatus;
import dev.starryeye.organization.core.model.SyncTrigger;
import dev.starryeye.organization.core.port.SyncRunRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 동기화 1회의 실행 이력. 관측성의 실체이며, 특히 ABORTED 사유가 남아야
 * 사람이 강제 실행을 승인할지 판단할 수 있다.
 *
 * <p>SCIM push 요청은 여기 기록하지 않는다. 요청 단위 이력이 폭증하기 때문이다.
 */
@RequiredArgsConstructor
public class DynamoDbSyncRunRepository implements SyncRunRepository {

    private static final String RUN_ID = "runId";
    private static final String SOURCE = "source";
    private static final String TRIGGER = "trigger";
    private static final String STARTED_AT = "startedAt";
    private static final String FINISHED_AT = "finishedAt";
    private static final String STATUS = "status";
    private static final String WRITTEN_COUNT = "writtenCount";
    private static final String DELETED_COUNT = "deletedCount";
    private static final String FAILURE_COUNT = "failureCount";
    private static final String SNAPSHOT_ID = "snapshotId";
    private static final String MESSAGE = "message";
    private static final String EXPIRES_AT = "expiresAt";

    private final DynamoDbAsyncClient client;
    private final DynamoDbProperties properties;
    private final Clock clock;

    @Override
    public Mono<SyncRun> start(SyncSource source, SyncTrigger trigger) {
        SyncRun run = SyncRun.started(UUID.randomUUID().toString(), source, trigger, clock.instant());
        return save(run).thenReturn(run);
    }

    @Override
    public Mono<SyncRun> finish(SyncRun run, SyncOutcome outcome) {
        SyncRun finished = run.finished(outcome, clock.instant());
        return save(finished).thenReturn(finished);
    }

    private Mono<Void> save(SyncRun run) {
        long expiresAt = run.startedAt()
                .plus(Duration.ofDays(properties.getSyncrunRetentionDays()))
                .getEpochSecond();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Keys.PK, Attrs.s(Keys.syncRunPk(run.startedAt())));
        item.put(Keys.SK, Attrs.s(Keys.syncRunSk(run.startedAt(), run.runId())));
        item.put(RUN_ID, Attrs.s(run.runId()));
        item.put(SOURCE, Attrs.s(run.source().name()));
        item.put(TRIGGER, Attrs.s(run.trigger().name()));
        item.put(STARTED_AT, Attrs.s(run.startedAt().toString()));
        item.put(STATUS, Attrs.s(run.status().name()));
        item.put(WRITTEN_COUNT, Attrs.n(run.writtenCount()));
        item.put(DELETED_COUNT, Attrs.n(run.deletedCount()));
        item.put(FAILURE_COUNT, Attrs.n(run.failureCount()));
        item.put(EXPIRES_AT, Attrs.n(expiresAt));
        if (run.finishedAt() != null) {
            item.put(FINISHED_AT, Attrs.s(run.finishedAt().toString()));
        }
        Attrs.putIfPresent(item, SNAPSHOT_ID, run.snapshotId());
        Attrs.putIfPresent(item, MESSAGE, run.message());

        return Mono.fromFuture(() -> client.putItem(PutItemRequest.builder()
                .tableName(properties.getTableName())
                .item(item)
                .build())).then();
    }

    /**
     * 이번 달 파티션을 최신순으로 읽고, 모자라면 지난달까지 이어 읽는다.
     * 파티션을 월 단위로 나눈 대가로 조회가 두 번 나뉜다.
     */
    @Override
    public Flux<SyncRun> findRecent(int limit) {
        YearMonth thisMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        YearMonth lastMonth = thisMonth.minusMonths(1);

        return queryMonth(thisMonth)
                .concatWith(Flux.defer(() -> queryMonth(lastMonth)))
                .take(limit);
    }

    private Flux<SyncRun> queryMonth(YearMonth month) {
        QueryRequest request = QueryRequest.builder()
                .tableName(properties.getTableName())
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", Keys.PK))
                .expressionAttributeValues(Map.of(":pk", Attrs.s("SYNCRUN#" + month)))
                .scanIndexForward(false)
                .build();

        return Mono.fromFuture(() -> client.query(request))
                .flatMapMany(response -> Flux.fromIterable(response.items()))
                .map(this::toRun);
    }

    private SyncRun toRun(Map<String, AttributeValue> item) {
        return SyncRun.builder()
                .runId(Attrs.str(item, RUN_ID))
                .source(SyncSource.valueOf(Attrs.str(item, SOURCE)))
                .trigger(SyncTrigger.valueOf(Attrs.str(item, TRIGGER)))
                .startedAt(Attrs.instant(item, STARTED_AT))
                .finishedAt(Attrs.instant(item, FINISHED_AT))
                .status(SyncStatus.valueOf(Attrs.str(item, STATUS)))
                .writtenCount(Attrs.integer(item, WRITTEN_COUNT))
                .deletedCount(Attrs.integer(item, DELETED_COUNT))
                .failureCount(Attrs.integer(item, FAILURE_COUNT))
                .snapshotId(Attrs.str(item, SNAPSHOT_ID))
                .message(Attrs.str(item, MESSAGE))
                .build();
    }
}
```

> `start` 와 `finish` 가 같은 PK/SK 를 만들기 때문에 `finish` 의 `putItem` 이 RUNNING 아이템을 덮어쓴다. 의도한 동작이다 — 실행 1건당 아이템 1개가 남는다.

- [ ] **Step 4: 빈 등록**

`DynamoDbConfig.java` 에 추가:

```java
    @Bean
    public DynamoDbSyncRunRepository dynamoDbSyncRunRepository(
            DynamoDbAsyncClient client, DynamoDbProperties properties, Clock clock) {
        return new DynamoDbSyncRunRepository(client, properties, clock);
    }
```

- [ ] **Step 5: storage 모듈 전체 테스트 확인**

Run:

```bash
./gradlew :storage-dynamodb:build
```

Expected: `BUILD SUCCESSFUL`. `KeysTest` 7 + `TableInitializerTest` 2 + `DirectoryStateRepositoryTest` 11 + `TupleSnapshotRepositoryTest` 9 + `SyncRunRepositoryTest` 5 = 34개 PASS.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: DynamoDbSyncRunRepository 추가

동기화 1회의 실행 이력을 월 단위 파티션에 적재한다. 특히 ABORTED 사유가
남아야 사람이 강제 실행을 승인할지 판단할 수 있다.

start 와 finish 가 같은 키를 쓰므로 완료 시 RUNNING 아이템을 덮어써
실행 1건당 아이템 1개만 남는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

---
