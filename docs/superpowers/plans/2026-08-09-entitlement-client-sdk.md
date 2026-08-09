# Entitlement Client SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `entitlement-client`, the embeddable Java SDK that keeps a local replica of the entitlement model and answers decisions in-process — microseconds, and still correct while the management service is down.

**Architecture:** The SDK polls `/v1/snapshot/version` every 5s, syncs by delta (falling back to a full NDJSON resync), and rebuilds an immutable `entitlement-core` `Snapshot` held behind an `AtomicReference`. `check()`/`checkAll()` call the *same* `Resolver` the service calls, so the two cannot disagree. A conformance gate evaluates the feed's own vectors with this SDK's engine before any snapshot is allowed to serve. The SDK answers but never explains: no trace data reaches a replica.

**Tech Stack:** Java 21, JDK `HttpClient`, Jackson 3 (`tools.jackson`, version from Boot 4's `jackson-bom`), Micrometer (optional), JUnit 5 + AssertJ, `com.sun.net.httpserver.HttpServer` as the test stub. **No Spring anywhere in this module.**

## Global Constraints

These bind every task. A reviewer rejects any task that violates one.

- **Source of truth is `.specs/001-entitlement-service/contracts/java-client-sdk.md`.** Read it before starting any task. `snapshot-feed.md` and `decision-api.md` define the wire.
- **Where the contract docs and the service code disagree, follow the code** — the SDK must parse what the service actually emits. Four known drifts, all confirmed in the service source:
  1. Override lines carry the GRANT/HOLD kind on **`overrideKind`**, not `kind` (`kind` is the line discriminator). `snapshot-feed.md:47` is stale.
  2. Absent `offValue`/`tiers` are **omitted keys**, not `null` — the service mapper sets `NON_NULL` value *and content* inclusion.
  3. Conformance record `id` is a long English sentence (`"reports.monthly: plan 50, grant 200, hold 0 -> 0, still allowed"`), not `cv_017`.
  4. `conformance.changed` is listed in the contract's delta table but **is not implemented** in `DeltaChange`. Do not depend on it; do not treat its absence as a bug.
- **No Spring dependency, ever.** `entitlement-client` depends on `entitlement-core`, Jackson 3, and optionally Micrometer. Nothing else at compile scope.
- **The SDK never traces.** No `Trace` is ever constructed locally. `Trace` appears only as the deserialised payload of `explain()`, which is a network call. A replica must never hold override `reason`, `createdBy`, or `createdAt`.
- **`check` and `checkAll` never throw for network or service failure.** They throw only the three domain errors. This is the whole point of the module.
- **Timestamps** are ISO-8601 UTC with exactly 3 fractional digits on the wire. Parse with `Instant.parse`. Never call `Instant.now()` in a decision path — take a `Clock`, defaulting to `Clock.systemUTC()`, so tests control time.
- **Package root** `com.solovis.entitlement.client`. Everything except the public API surface is package-private or in a subpackage and not exported in docs.
- **Tests**: JUnit 5 + AssertJ, class named `<ClassUnderTest>Test`, mirroring the main package tree. Test method names are long assertive sentences, matching the repo style (e.g. `truncatedFeedMissingItsFooterIsDiscardedWholeRatherThanPartiallyApplied`).
- **Criterion references** `(cNN)` point at `.specs/001-entitlement-service/spec.md` §10. Carry them into javadoc and test names where a task names one.
- **Never `git add -A`.** The working tree carries long-lived unrelated pending changes. Stage only the files the task names.
- Build from `management/backend`. Verify with `./mvnw -pl entitlement-client -am test`. The `-am` is required — the module depends on `entitlement-core`.

---

## File Structure

```
management/backend/entitlement-client/
├── pom.xml                                             # Task 1 — Jackson 3, Micrometer optional
└── src/
    ├── main/java/com/solovis/entitlement/client/
    │   ├── EntitlementClient.java                      # Task 11 — the public interface
    │   ├── EntitlementClientBuilder.java               # Task 13 — construction + startup gate
    │   ├── DefaultEntitlementClient.java               # Task 11,12,13 — the implementation
    │   ├── AccountEntitlements.java                    # Task 11 — checkAll's return shape
    │   ├── ClientHealth.java                           # Task 11 — replica freshness
    │   ├── StartupMode.java                            # Task 13 — REQUIRE_SNAPSHOT | ALLOW_DISK_CACHE
    │   ├── error/
    │   │   ├── EntitlementClientStartupException.java  # Task 2
    │   │   ├── SnapshotBehindException.java            # Task 2
    │   │   ├── ExplanationUnavailableException.java    # Task 2
    │   │   └── ReplicaUnknownAccountException.java     # Task 2
    │   ├── wire/                                       # Jackson DTOs — mirrors of the service's wire shapes
    │   │   ├── ClientJson.java                         # Task 1 — the one configured ObjectMapper
    │   │   ├── ValueDto.java                           # Task 3
    │   │   ├── FeedDtos.java                           # Task 3 — header/capability/plan/account/override/conformance/footer
    │   │   ├── DeltaDtos.java                          # Task 6 — delta envelope + the nine change kinds
    │   │   ├── DecisionDtos.java                       # Task 12 — decision + trace response
    │   │   ├── ProblemDto.java                         # Task 8 — RFC 9457
    │   │   └── WireMapper.java                         # Task 3 — DTO -> entitlement-core model
    │   ├── replica/
    │   │   ├── Replica.java                            # Task 4 — Snapshot + ref index + feed metadata
    │   │   ├── FullSnapshotReader.java                 # Task 4 — NDJSON -> Replica
    │   │   ├── ConformanceGate.java                    # Task 5
    │   │   ├── DeltaApplier.java                       # Task 6 — Replica + changes -> Replica
    │   │   ├── ReplicaNdjsonWriter.java                # Task 7 — Replica -> NDJSON (feed's own shape)
    │   │   └── DiskCache.java                          # Task 7
    │   ├── transport/
    │   │   ├── FeedHttpClient.java                     # Task 8 — JDK HttpClient, gzip, problem parsing
    │   │   ├── Backoff.java                            # Task 9 — 5/10/30/60s jittered
    │   │   ├── SnapshotTooOldException.java            # Task 8 — internal, triggers full resync
    │   │   └── FeedUnavailableException.java           # Task 8 — internal, triggers backoff
    │   ├── metrics/
    │   │   ├── ClientMetrics.java                      # Task 14 — interface + NO_OP
    │   │   └── MicrometerClientMetrics.java            # Task 14 — only loaded when a registry is supplied
    │   ├── SnapshotPoller.java                         # Task 10 — the daemon sync loop
    │   └── package-info.java                           # exists (stub); rewrite in Task 1
    └── test/java/com/solovis/entitlement/client/
        ├── testing/StubFeedServer.java                 # Task 8 — in-process HttpServer fixture
        └── ...                                         # one *Test per class above
management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/error/
└── UnknownAccountException.java                        # Task 2 — de-final so the SDK can enrich it
management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/client/
└── ClientAgainstRealFeedTest.java                      # Task 15 — the anti-drift test
```

**Two rules this structure encodes.** Transport never parses domain objects (it hands back streams and DTOs); `replica/` never touches HTTP. That seam is what makes outage behaviour testable without a network.

---

## Task 1: Module skeleton, Jackson 3, and the one mapper

**Files:**
- Modify: `management/backend/entitlement-client/pom.xml`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ClientJson.java`
- Modify: `entitlement-client/src/main/java/com/solovis/entitlement/client/package-info.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/wire/ClientJsonTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ClientJson.MAPPER` — a `tools.jackson.databind.ObjectMapper` every other task uses. Never construct a second mapper.

**Context the implementer needs.** The pom today declares Jackson **2** (`com.fasterxml.jackson.core:jackson-databind`). That is wrong for this build and must be replaced. Spring Boot 4.0.7's `spring-boot-dependencies` (the reactor's grandparent) manages `jackson-bom` at 3.1.4, so `tools.jackson.core:jackson-databind` needs no explicit version. Jackson 3 keeps annotations in the old `com.fasterxml.jackson.annotation` package — that is not a mistake when you see it. `jqwik` is managed by the reactor parent at 1.9.2.

- [ ] **Step 1: Rewrite the dependency block in `entitlement-client/pom.xml`**

Replace the existing `<dependencies>` block with:

```xml
	<dependencies>
		<dependency>
			<groupId>com.solovis.entitlement</groupId>
			<artifactId>entitlement-core</artifactId>
			<version>${project.version}</version>
		</dependency>
		<dependency>
			<groupId>tools.jackson.core</groupId>
			<artifactId>jackson-databind</artifactId>
		</dependency>
		<!-- Optional: the SDK emits metrics only when the embedding product supplies a registry.
		     `optional` keeps Micrometer off every consumer's classpath that does not want it. -->
		<dependency>
			<groupId>io.micrometer</groupId>
			<artifactId>micrometer-core</artifactId>
			<optional>true</optional>
		</dependency>

		<dependency>
			<groupId>org.junit.jupiter</groupId>
			<artifactId>junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.assertj</groupId>
			<artifactId>assertj-core</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>net.jqwik</groupId>
			<artifactId>jqwik</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>
```

- [ ] **Step 2: Write the failing test**

`ClientJsonTest.java`:

```java
package com.solovis.entitlement.client.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientJsonTest {

    @Test
    void unknownWirePropertiesAreIgnoredSoAServiceCanAddFieldsWithoutBreakingEveryDeployedReplica() {
        var json = """
            {"version":48211,"publishedAt":"2026-08-09T14:03:10.900Z","format":1,
             "resolverContract":1,"somethingAddedNextYear":"ignored"}""";

        var node = ClientJson.MAPPER.readTree(json);

        assertThat(node.get("version").asLong()).isEqualTo(48211L);
    }

    @Test
    void nullValuedPropertiesAreOmittedOnWriteMatchingTheServicesNonNullInclusion() {
        record Sample(String present, String absent) {}

        var json = ClientJson.MAPPER.writeValueAsString(new Sample("here", null));

        assertThat(json).isEqualTo("{\"present\":\"here\"}");
    }

    @Test
    void mapContentNullsAreOmittedTooBecauseTheFeedOmitsOffValueAndTiersRatherThanNullingThem() {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("kind", "capability");
        map.put("offValue", null);

        var json = ClientJson.MAPPER.writeValueAsString(map);

        assertThat(json).isEqualTo("{\"kind\":\"capability\"}");
    }

    @Test
    void instantsAreWrittenAsIsoTextNotEpochNumbersSoTheDiskCacheMatchesTheFeed() {
        record Stamped(java.time.Instant at) {}

        var json = ClientJson.MAPPER.writeValueAsString(
            new Stamped(java.time.Instant.parse("2026-08-09T14:03:10.900Z")));

        assertThat(json).contains("2026-08-09T14:03:10.900Z");
    }
}
```

- [ ] **Step 3: Run the tests and confirm they fail**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ClientJsonTest
```
Expected: compilation failure — `ClientJson` does not exist.

- [ ] **Step 4: Write `ClientJson`**

```java
package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single JSON mapper for every wire surface this SDK touches, configured to match the
 * service's {@code JacksonConfig} exactly so a round-trip through the disk cache produces the
 * same bytes the feed produced.
 *
 * <p>Unknown properties are ignored on purpose: a replica running an older SDK must keep syncing
 * when the service starts emitting a field it has never heard of. Unknown <em>record kinds</em>
 * are a different matter and are handled per-surface — see {@code FullSnapshotReader} (skip) and
 * {@code DeltaApplier} (stop syncing).
 */
public final class ClientJson {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .changeDefaultPropertyInclusion(incl -> incl
            .withValueInclusion(JsonInclude.Include.NON_NULL)
            .withContentInclusion(JsonInclude.Include.NON_NULL))
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private ClientJson() {}
}
```

- [ ] **Step 5: Rewrite `package-info.java`**

```java
/**
 * The embeddable entitlement SDK: a local replica of the model plus the same {@code
 * entitlement-core} resolver the management service runs, so a decision is an in-process map
 * lookup that keeps working while the service does not.
 *
 * <p>Contract: {@code .specs/001-entitlement-service/contracts/java-client-sdk.md}.
 *
 * <p>The SDK answers; it does not explain. Reason text, authorship and timestamps deliberately
 * never reach a replica — {@code explain()} is a diagnostic network call, not a decision path.
 */
package com.solovis.entitlement.client;
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ClientJsonTest
```
Expected: 4 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-client/pom.xml \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ClientJson.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/package-info.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/wire/ClientJsonTest.java
git commit -m "feat(entitlement-client): bind the SDK to Jackson 3 with the service's exact mapper configuration"
```

---

## Task 2: The typed error surface

**Files:**
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/error/UnknownAccountException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/error/EntitlementClientStartupException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/error/SnapshotBehindException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/error/ExplanationUnavailableException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/error/ReplicaUnknownAccountException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/error/package-info.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/error/ReplicaUnknownAccountExceptionTest.java`

**Interfaces:**
- Consumes: `com.solovis.entitlement.core.error.{UnknownAccountException, UnknownCapabilityException, RetiredCapabilityException}`.
- Produces:
  - `ReplicaUnknownAccountException extends UnknownAccountException` with `Duration snapshotAge()` and `boolean readThroughAttempted()`.
  - `SnapshotBehindException extends RuntimeException` with `long requiredVersion()`, `long currentVersion()`.
  - `EntitlementClientStartupException extends RuntimeException` — `(String message)` and `(String message, Throwable cause)`.
  - `ExplanationUnavailableException extends RuntimeException` — `(String message, Throwable cause)`.

**Why core changes here.** The contract requires the unknown-account error to carry `snapshotAge` and `readThroughAttempted` so a caller can distinguish a genuine 404 from an outage-plus-race — and it requires the type callers catch to be `UnknownAccountException`, the same one the service throws. Core's class is currently `final`, so the SDK can neither extend nor enrich it. De-finalising it is the minimal change: the service keeps throwing the plain form, the SDK throws a subclass, and one `catch (UnknownAccountException e)` handles both. Do **not** add replica fields to the core class — replica freshness is not a domain concept.

- [ ] **Step 1: Write the failing test**

`ReplicaUnknownAccountExceptionTest.java`:

```java
package com.solovis.entitlement.client.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.error.UnknownAccountException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReplicaUnknownAccountExceptionTest {

    @Test
    void isCatchableAsTheCoreUnknownAccountExceptionSoOneHandlerCoversServiceAndSdkCallers() {
        var e = new ReplicaUnknownAccountException("acct_9931", Duration.ofSeconds(3), true);

        assertThat(e).isInstanceOf(UnknownAccountException.class);
        assertThat(e.accountExternalId()).isEqualTo("acct_9931");
    }

    @Test
    void carriesTheEvidenceThatSeparatesAGenuine404FromAnOutagePlusRace() {
        var raced = new ReplicaUnknownAccountException("acct_new", Duration.ofSeconds(120), false);

        assertThat(raced.snapshotAge()).isEqualTo(Duration.ofSeconds(120));
        assertThat(raced.readThroughAttempted()).isFalse();
    }

    @Test
    void snapshotBehindReportsBothVersionsSoACallerCanDecideWhetherToRetryOrProceed() {
        assertThatThrownBy(() -> { throw new SnapshotBehindException(48211L, 48208L); })
            .isInstanceOf(SnapshotBehindException.class)
            .hasMessageContaining("48211")
            .hasMessageContaining("48208");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ReplicaUnknownAccountExceptionTest
```
Expected: compilation failure — the exception classes do not exist.

- [ ] **Step 3: De-finalise the core exception**

In `entitlement-core/.../error/UnknownAccountException.java`, change the declaration from `public final class UnknownAccountException extends RuntimeException` to `public class UnknownAccountException extends RuntimeException`, and add to its javadoc:

```java
/**
 * ...existing javadoc...
 *
 * <p>Not {@code final}: {@code entitlement-client} subclasses this to attach replica freshness
 * evidence, so a consumer catching this one type handles both a service answer and an SDK answer.
 * Replica concerns stay in the subclass — they are not domain state.
 */
```

Leave `UnknownCapabilityException` and `RetiredCapabilityException` `final`. The SDK throws those unmodified; there is no replica-race ambiguity for a capability, because capabilities come from the same snapshot the decision does.

- [ ] **Step 4: Write the four client exception classes**

```java
package com.solovis.entitlement.client.error;

import com.solovis.entitlement.core.error.UnknownAccountException;
import java.time.Duration;

/**
 * The replica holds no account with this external id, and a bounded read-through to the service
 * did not produce one either.
 *
 * <p>Carries the evidence a caller needs to tell a genuine unknown account from a replica that
 * simply has not caught up with a signup three seconds ago.
 */
public final class ReplicaUnknownAccountException extends UnknownAccountException {

    private final Duration snapshotAge;
    private final boolean readThroughAttempted;

    public ReplicaUnknownAccountException(
            String accountExternalId, Duration snapshotAge, boolean readThroughAttempted) {
        super(accountExternalId);
        this.snapshotAge = java.util.Objects.requireNonNull(snapshotAge);
        this.readThroughAttempted = readThroughAttempted;
    }

    /** How stale the replica was when it failed to find the account. */
    public Duration snapshotAge() {
        return snapshotAge;
    }

    /** False means the service was unreachable, so this may be a race rather than a real 404. */
    public boolean readThroughAttempted() {
        return readThroughAttempted;
    }
}
```

```java
package com.solovis.entitlement.client.error;

/** A {@code minSnapshotVersion} was supplied that this replica has not reached (read-your-writes). */
public final class SnapshotBehindException extends RuntimeException {

    private final long requiredVersion;
    private final long currentVersion;

    public SnapshotBehindException(long requiredVersion, long currentVersion) {
        super("Replica is at snapshot version " + currentVersion + " but version " + requiredVersion
            + " was required. Retry, await the version, or accept the older answer.");
        this.requiredVersion = requiredVersion;
        this.currentVersion = currentVersion;
    }

    public long requiredVersion() {
        return requiredVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
```

```java
package com.solovis.entitlement.client.error;

/**
 * No snapshot could be loaded within {@code startupTimeout} and no usable disk cache existed, or
 * the conformance gate failed at construction.
 *
 * <p>The SDK refuses to guess. Inventing entitlements for accounts it has never seen would be
 * exactly the granting that spec §11 forbids.
 */
public final class EntitlementClientStartupException extends RuntimeException {

    public EntitlementClientStartupException(String message) {
        super(message);
    }

    public EntitlementClientStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.solovis.entitlement.client.error;

/**
 * {@code explain()} could not reach the service. Diagnostic path only — never thrown by
 * {@code check} or {@code checkAll}, which answer from the replica regardless of the service.
 */
public final class ExplanationUnavailableException extends RuntimeException {

    public ExplanationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

And `error/package-info.java`:

```java
/**
 * The SDK's typed errors. The three domain distinctions ({@code UnknownAccountException},
 * {@code UnknownCapabilityException}, {@code RetiredCapabilityException}) come from
 * {@code entitlement-core} unchanged, because "we don't know" and "no" are different answers and
 * both surfaces must draw the line in the same place (c19).
 */
package com.solovis.entitlement.client.error;
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ReplicaUnknownAccountExceptionTest
```
Expected: 3 tests, 0 failures.

- [ ] **Step 6: Prove the core change broke nothing**

```bash
cd management/backend && ./mvnw -pl entitlement-core test
```
Expected: BUILD SUCCESS, same test count as before the change.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/error/UnknownAccountException.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/error/ \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/error/
git commit -m "feat(entitlement-client): typed error surface, with unknown-account carrying replica freshness evidence"
```

---

## Task 3: The value and capability wire mapping

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ValueDto.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/FeedDtos.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/WireMapper.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/package-info.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/wire/WireMapperTest.java`

**Interfaces:**
- Consumes: `ClientJson.MAPPER` (Task 1); core `EntitlementValue`, `Capability`, `TierOrder`, `OffValue`, `Plan`, `AccountAssignment`, `AccountOverride`, `CapabilityKey`, `OverrideKind`, `ValueType`.
- Produces:
  - `ValueDto(String type, Boolean enabled, Long amount, Boolean unlimited, String tier, Integer ordinal)`
  - `FeedDtos.HeaderLine`, `.CapabilityLine`, `.PlanLine`, `.AccountLine`, `.OverrideLine`, `.ConformanceLine`, `.FooterLine`, `.TierDto`, `.Counts`
  - `WireMapper.toValue(ValueDto) -> EntitlementValue`
  - `WireMapper.toCapability(FeedDtos.CapabilityLine, Instant feedPublishedAt) -> Capability`
  - `WireMapper.toPlan(FeedDtos.PlanLine) -> Plan`
  - `WireMapper.toOverride(FeedDtos.OverrideLine) -> AccountOverride`
  - `WireMapper.refToId(String ref) -> long` — parses `"ovr_4471"` to `4471`

**The three-variant encoding, verbatim from the service's `ValueMapper`.** Exactly one shape per variant, and `unlimited:false` is never emitted:

- `{"type":"SWITCH","enabled":true}` / `{"type":"SWITCH","enabled":false}`
- `{"type":"QUANTITY","amount":50}` — a non-negative long
- `{"type":"QUANTITY","unlimited":true}` — **never** an amount alongside it. `unlimited` is a distinct variant, never a large number.
- `{"type":"TIER","tier":"gold","ordinal":2}`

**`retiredAt` must be synthesised.** The feed's capability line carries `status` but no `retiredAt`, and core's `Capability` rejects `RETIRED` with a null `retiredAt`. Rule: when `status == "RETIRED"`, use the feed's `publishedAt` as `retiredAt`; when `ACTIVE`, pass `null`. `retiredAt` never affects resolution — only `isRetired()` does — so an approximate value on a replica cannot change an answer. Document this on the method.

**`displayName` and `description` are absent from feed capability lines.** The service deliberately omits them (they are not needed to resolve). Core's `Capability` requires a non-null `displayName` but allows a null `description`. Use the capability key string as `displayName` when the line omits it, and `null` for `description`.

- [ ] **Step 1: Write the failing test**

`WireMapperTest.java`:

```java
package com.solovis.entitlement.client.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OverrideKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WireMapperTest {

    private static final Instant PUBLISHED = Instant.parse("2026-08-09T14:03:10.900Z");

    @Test
    void switchValuesRoundTripThroughTheWireEncoding() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"SWITCH\",\"enabled\":true}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(new EntitlementValue.Switch(true));
    }

    @Test
    void aBoundedQuantityCarriesItsAmount() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"QUANTITY\",\"amount\":50}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void unlimitedIsADistinctVariantAndNeverALargeNumber() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"QUANTITY\",\"unlimited\":true}", ValueDto.class);

        var value = WireMapper.toValue(dto);

        assertThat(value).isEqualTo(EntitlementValue.Quantity.unbounded());
        assertThat(((EntitlementValue.Quantity) value).unlimited()).isTrue();
        assertThat(((EntitlementValue.Quantity) value).amount()).isZero();
    }

    @Test
    void tiersCarryTheirDeclaredOrdinal() {
        var dto = ClientJson.MAPPER.readValue(
            "{\"type\":\"TIER\",\"tier\":\"gold\",\"ordinal\":2}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(new EntitlementValue.Tier("gold", 2));
    }

    @Test
    void aQuantityWithNeitherAmountNorUnlimitedIsAMalformedFeedAndIsRejectedOutright() {
        var dto = new ValueDto("QUANTITY", null, null, null, null, null);

        assertThatThrownBy(() -> WireMapper.toValue(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("QUANTITY");
    }

    @Test
    void aCapabilityLineWithoutOffValueOrTiersParsesBecauseTheServiceOmitsRatherThanNullsThem() {
        var json = """
            {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.key().value()).isEqualTo("api.access");
        assertThat(capability.status()).isEqualTo(Capability.Status.ACTIVE);
        assertThat(capability.tierOrder().tiers()).isEmpty();
    }

    @Test
    void aRetiredCapabilityBorrowsTheFeedsPublishedAtBecauseTheLineCarriesNoRetiredAt() {
        var json = """
            {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.isRetired()).isTrue();
        assertThat(capability.retiredAt()).isEqualTo(PUBLISHED);
    }

    @Test
    void tierCapabilitiesRebuildTheirDeclaredTotalOrder() {
        var json = """
            {"kind":"capability","key":"support.level","area":"support","valueType":"TIER",
             "default":{"type":"TIER","tier":"community","ordinal":0},
             "tiers":[{"tier":"community","ordinal":0,"displayName":"Community"},
                      {"tier":"standard","ordinal":1,"displayName":"Standard"},
                      {"tier":"gold","ordinal":2,"displayName":"Gold"}],
             "status":"ACTIVE"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.tierOrder().ordinalOf("gold")).hasValue(2);
        assertThat(capability.tierOrder().maxOrdinal()).isEqualTo(2);
    }

    @Test
    void anOverrideLineCarriesItsKindOnOverrideKindNotKindWhichIsTheLineDiscriminator() {
        var json = """
            {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",
             "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.OverrideLine.class);

        var override = WireMapper.toOverride(line);

        assertThat(override.kind()).isEqualTo(OverrideKind.GRANT);
        assertThat(override.id()).hasValue(4471L);
        assertThat(override.accountExternalId()).isEqualTo("acct_9931");
    }

    @Test
    void aReplicaOverrideHoldsNoReasonNoAuthorAndNoTimestampBecauseTraceDataNeverReachesAReplica() {
        var line = new FeedDtos.OverrideLine(
            "override", "ovr_1", "acct_1", "seats.limit", "HOLD", new ValueDto("QUANTITY", null, 0L, null, null, null));

        var override = WireMapper.toOverride(line);

        assertThat(override.reason()).isEmpty();
        assertThat(override.createdBy()).isEmpty();
        assertThat(override.createdAt()).isEmpty();
    }

    @Test
    void refsParseToTheNumericIdSoIncrementalRemovalCanFindTheOverrideAgain() {
        assertThat(WireMapper.refToId("ovr_4471")).isEqualTo(4471L);
    }

    @Test
    void aRefInAnUnexpectedShapeIsAMalformedFeedRatherThanASilentZero() {
        assertThatThrownBy(() -> WireMapper.refToId("4471"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=WireMapperTest
```
Expected: compilation failure — `ValueDto`, `FeedDtos`, `WireMapper` do not exist.

- [ ] **Step 3: Write `ValueDto`**

```java
package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The three-variant value encoding, mirroring the service's {@code service.dto.ValueDto} exactly.
 * Exactly one variant's fields are populated; the rest are absent, never null-valued.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueDto(
    String type, Boolean enabled, Long amount, Boolean unlimited, String tier, Integer ordinal) {}
```

- [ ] **Step 4: Write `FeedDtos`**

Note `default` is a Java keyword, so the capability line's `default` property needs `@JsonProperty`.

```java
package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The NDJSON line shapes of {@code GET /v1/snapshot/full}, one record per {@code kind}.
 *
 * <p>These mirror what {@code FullSnapshotWriter} actually emits, which is not identical to the
 * example block in {@code snapshot-feed.md}: the override line's GRANT/HOLD kind lives on
 * {@code overrideKind} (the doc shows {@code kind}), and absent {@code offValue}/{@code tiers}
 * are omitted keys rather than nulls.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FeedDtos {

    private FeedDtos() {}

    public record Counts(long capabilities, long plans, long accounts, long overrides) {}

    public record HeaderLine(
        String kind, long version, int format, int resolverContract, String publishedAt, Counts counts) {}

    public record TierDto(String tier, int ordinal, String displayName) {}

    public record CapabilityLine(
        String kind,
        String key,
        String area,
        String valueType,
        @JsonProperty("default") ValueDto defaultValue,
        ValueDto offValue,
        List<TierDto> tiers,
        String status) {}

    public record PlanLine(
        String kind,
        String key,
        String status,
        boolean isDefaultForNewAccounts,
        Map<String, ValueDto> entitlements) {}

    public record AccountLine(String kind, String external, String planKey) {}

    public record OverrideLine(
        String kind, String ref, String account, String capability, String overrideKind, ValueDto value) {}

    /**
     * A self-contained conformance vector. {@code model} is a nested object carrying its own
     * miniature snapshot, so it is held as a tree and unpacked by {@code ConformanceGate} rather
     * than being bound to a fixed record here.
     */
    public record ConformanceLine(String kind, String id, JsonNode model, JsonNode expect) {}

    public record FooterLine(String kind, long version, long recordCount) {}
}
```

- [ ] **Step 5: Write `WireMapper`**

```java
package com.solovis.entitlement.client.wire;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Wire DTOs to {@code entitlement-core} domain objects. One direction only; the SDK never writes to the feed. */
public final class WireMapper {

    private static final String REF_PREFIX = "ovr_";

    private WireMapper() {}

    public static EntitlementValue toValue(ValueDto dto) {
        if (dto == null || dto.type() == null) {
            throw new IllegalArgumentException("Malformed feed: value has no type.");
        }
        return switch (dto.type()) {
            case "SWITCH" -> {
                if (dto.enabled() == null) {
                    throw new IllegalArgumentException("Malformed feed: SWITCH value has no 'enabled'.");
                }
                yield new EntitlementValue.Switch(dto.enabled());
            }
            case "QUANTITY" -> {
                boolean unlimited = Boolean.TRUE.equals(dto.unlimited());
                if (unlimited && dto.amount() != null) {
                    throw new IllegalArgumentException(
                        "Malformed feed: QUANTITY declares both 'unlimited' and 'amount'.");
                }
                if (!unlimited && dto.amount() == null) {
                    throw new IllegalArgumentException(
                        "Malformed feed: QUANTITY declares neither 'unlimited' nor 'amount'.");
                }
                yield unlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(dto.amount());
            }
            case "TIER" -> {
                if (dto.tier() == null || dto.ordinal() == null) {
                    throw new IllegalArgumentException("Malformed feed: TIER value needs 'tier' and 'ordinal'.");
                }
                yield new EntitlementValue.Tier(dto.tier(), dto.ordinal());
            }
            default -> throw new IllegalArgumentException("Malformed feed: unknown value type '" + dto.type() + "'.");
        };
    }

    /**
     * @param feedPublishedAt stands in for {@code retiredAt}, which the feed's capability line does
     *     not carry. A retirement timestamp never enters resolution — only {@code isRetired()} does —
     *     so an approximation here cannot change an answer.
     */
    public static Capability toCapability(FeedDtos.CapabilityLine line, Instant feedPublishedAt) {
        var key = new CapabilityKey(line.key());
        var valueType = ValueType.valueOf(line.valueType());
        var status = Capability.Status.valueOf(line.status());
        var tierOrder = line.tiers() == null || line.tiers().isEmpty()
            ? TierOrder.NONE
            : new TierOrder(line.tiers().stream()
                .map(t -> new TierOrder.TierDefinition(t.tier(), t.ordinal(), t.displayName()))
                .toList());
        var offValue = line.offValue() == null
            ? Optional.<OffValue>empty()
            : Optional.of(new OffValue(toValue(line.offValue())));

        return new Capability(
            key,
            key.value(),   // the feed omits displayName; a replica does not render, so the key suffices
            null,          // the feed omits description for the same reason
            valueType,
            toValue(line.defaultValue()),
            offValue,
            tierOrder,
            status,
            status == Capability.Status.RETIRED ? feedPublishedAt : null);
    }

    public static Plan toPlan(FeedDtos.PlanLine line) {
        return new Plan(
            line.key(),
            line.key(),   // the feed omits the display name; resolution never reads it
            Plan.Status.valueOf(line.status()),
            line.isDefaultForNewAccounts());
    }

    public static AccountAssignment toAccount(FeedDtos.AccountLine line) {
        return new AccountAssignment(line.external(), line.planKey());
    }

    /** Reason, author and timestamp are deliberately absent — they never reach a replica. */
    public static AccountOverride toOverride(FeedDtos.OverrideLine line) {
        return new AccountOverride(
            OptionalLong.of(refToId(line.ref())),
            line.account(),
            new CapabilityKey(line.capability()),
            OverrideKind.valueOf(line.overrideKind()),
            toValue(line.value()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    }

    /** {@code "ovr_4471"} to {@code 4471}. The ref is opaque to resolution but load-bearing for removal. */
    public static long refToId(String ref) {
        if (ref == null || !ref.startsWith(REF_PREFIX)) {
            throw new IllegalArgumentException("Malformed feed: override ref '" + ref + "' is not 'ovr_<id>'.");
        }
        try {
            return Long.parseLong(ref.substring(REF_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed feed: override ref '" + ref + "' has no numeric id.", e);
        }
    }
}
```

The `java.util.List` import above is used only by `toCapability`'s tier mapping; drop any import your IDE flags as unused rather than leaving it.

And `wire/package-info.java`:

```java
/**
 * Jackson mirrors of the service's wire shapes, plus the one-way mapping into
 * {@code entitlement-core} domain objects.
 *
 * <p>These are mirrors, not shared types: the service's own DTOs live in {@code
 * entitlement-service} and drag in Spring's {@code HttpStatus}, so they cannot be reused here.
 * When the service's wire encoding changes, this package changes with it — {@code
 * ClientAgainstRealFeedTest} in the service module is what catches the two drifting apart.
 */
package com.solovis.entitlement.client.wire;
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=WireMapperTest
```
Expected: 12 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/wire/WireMapperTest.java
git commit -m "feat(entitlement-client): map the feed's value and capability encoding onto the core model"
```

---

## Task 4: Reading a full snapshot into a replica

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/Replica.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/FullSnapshotReader.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/package-info.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/replica/FullSnapshotReaderTest.java`

**Interfaces:**
- Consumes: `WireMapper`, `FeedDtos`, `ClientJson.MAPPER` (Task 3); core `SnapshotBuilder`, `Snapshot`, `ConformanceVector`.
- Produces:
  - `Replica(Snapshot snapshot, Map<Long, AccountOverride> overridesByRef, Instant publishedAt, List<ConformanceVector> vectors, int format, int resolverContract)` with `long version()` delegating to `snapshot.snapshotVersion()`.
  - `FullSnapshotReader.read(InputStream ndjson) -> Replica` — throws `MalformedFeedException` on a missing/mismatched footer.
  - `MalformedFeedException extends RuntimeException` (nested in `FullSnapshotReader`).

**Why `overridesByRef` exists.** `override.removed` deltas carry only a `ref`. Core's `SnapshotMutator.withOverrideRemoved` needs `(account, capabilityKey, id)`. The replica therefore keeps a side index from numeric ref to the override, maintained alongside the snapshot. Without it a removal cannot be applied incrementally.

**Parsing strategy.** Read line by line. Parse each into a `JsonNode`, switch on `kind`, then `treeToValue` into the matching record. An **unknown line kind is skipped with a warning** — a forward-compatible replica must tolerate a service that starts emitting a new record type. (Contrast with deltas in Task 6, where an unknown kind must stop syncing, because silently skipping a change diverges the replica.)

**Discard rules, all load-bearing:**
- `header` must be the first line; `footer` must be the last.
- `footer.version` must equal `header.version`. Mismatch or missing footer means the response was truncated — discard the whole thing. This is the rule that stops a cut-off HTTP response from silently becoming a wrong answer.
- The whole body describes exactly one version; `snapshot.build(header.version)`.

- [ ] **Step 1: Write the failing test**

`FullSnapshotReaderTest.java`:

```java
package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FullSnapshotReaderTest {

    private static final String HEADER = """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":2,"plans":1,"accounts":1,"overrides":1}}""";

    private static final String CAP_SWITCH = """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""";

    private static final String CAP_RETIRED = """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""";

    private static final String PLAN = """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}""";

    private static final String ACCOUNT = """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""";

    private static final String OVERRIDE = """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"api.access",\
        "overrideKind":"HOLD","value":{"type":"SWITCH","enabled":false}}""";

    private static final String FOOTER = """
        {"kind":"footer","version":48211,"recordCount":7}""";

    private static InputStream feed(String... lines) {
        return new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCompleteFeedBecomesAReplicaAtTheHeadersVersion() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.version()).isEqualTo(48211L);
        assertThat(replica.publishedAt()).isEqualTo(Instant.parse("2026-08-09T14:03:10.900Z"));
        assertThat(replica.format()).isEqualTo(1);
        assertThat(replica.resolverContract()).isEqualTo(1);
    }

    @Test
    void everyRecordKindLandsInTheSnapshotItBelongsIn() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));
        var snapshot = replica.snapshot();

        assertThat(snapshot.capabilities()).hasSize(2);
        assertThat(snapshot.plan("pro")).isPresent();
        assertThat(snapshot.planEntitlement("pro", new CapabilityKey("api.access")))
            .get().extracting(pe -> pe.value())
            .isEqualTo(new EntitlementValue.Switch(true));
        assertThat(snapshot.account("acct_9931")).isPresent();
        assertThat(snapshot.liveOverrides("acct_9931", new CapabilityKey("api.access"))).hasSize(1);
    }

    @Test
    void retiredCapabilitiesAreKeptSoTheReplicaCanRaiseTheRetiredErrorRatherThanASilentDenial() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.snapshot().capability(new CapabilityKey("legacy.export")))
            .get().extracting(c -> c.isRetired()).isEqualTo(true);
        assertThat(replica.snapshot().activeCapabilities()).hasSize(1);
    }

    @Test
    void overridesAreIndexedByRefSoALaterRemovalDeltaCanFindThem() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.overridesByRef()).containsKey(4471L);
        assertThat(replica.overridesByRef().get(4471L).capabilityKey().value()).isEqualTo("api.access");
    }

    @Test
    void truncatedFeedMissingItsFooterIsDiscardedWholeRatherThanPartiallyApplied() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, PLAN, ACCOUNT)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("footer");
    }

    @Test
    void aFooterNamingADifferentVersionThanTheHeaderIsDiscarded() {
        var wrongFooter = "{\"kind\":\"footer\",\"version\":48209,\"recordCount\":7}";

        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, wrongFooter)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("48209");
    }

    @Test
    void aFeedThatDoesNotStartWithItsHeaderIsDiscarded() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(CAP_SWITCH, HEADER, FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("header");
    }

    @Test
    void anUnknownLineKindIsSkippedSoAnOlderReplicaKeepsSyncingWhenTheServiceAddsARecordType() {
        var future = "{\"kind\":\"somethingAddedNextYear\",\"data\":1}";

        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, future, FOOTER));

        assertThat(replica.snapshot().capabilities()).hasSize(1);
    }

    @Test
    void blankLinesAreToleratedBecauseTrailingNewlinesAreNormal() {
        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, FOOTER, ""));

        assertThat(replica.version()).isEqualTo(48211L);
    }

    @Test
    void conformanceVectorsAreCollectedForTheGateToEvaluate() {
        var vector = """
            {"kind":"conformance","id":"api.access: plan on, hold off -> off",\
            "model":{"account":"acct_c1","capability":"api.access",\
            "capabilities":[{"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
            "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}],\
            "plans":[{"kind":"plan","key":"p","status":"ACTIVE","isDefaultForNewAccounts":true,\
            "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}],\
            "accounts":[{"kind":"account","external":"acct_c1","planKey":"p"}],\
            "overrides":[{"kind":"override","ref":"ovr_1","account":"acct_c1","capability":"api.access",\
            "overrideKind":"HOLD","value":{"type":"SWITCH","enabled":false}}]},\
            "expect":{"allowed":false,"value":{"type":"SWITCH","enabled":false}}}""";

        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, vector, FOOTER));

        assertThat(replica.vectors()).hasSize(1);
        assertThat(replica.vectors().get(0).name()).isEqualTo("api.access: plan on, hold off -> off");
        assertThat(replica.vectors().get(0).expectedAllowed()).isFalse();
        assertThat(replica.vectors().get(0).accountExternalId()).isEqualTo("acct_c1");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=FullSnapshotReaderTest
```
Expected: compilation failure — `Replica` and `FullSnapshotReader` do not exist.

- [ ] **Step 3: Write `Replica`**

```java
package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.view.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable local replica of the model: the core {@link Snapshot} decisions resolve against,
 * plus the feed metadata a replica has to carry.
 *
 * <p>{@code overridesByRef} exists because {@code override.removed} deltas carry only an opaque
 * {@code ref}, while core's mutator removes by {@code (account, capability, id)}. The index is the
 * bridge. It holds the same {@link AccountOverride} instances the snapshot holds, so it costs a
 * map, not a copy of the data.
 */
public record Replica(
    Snapshot snapshot,
    Map<Long, AccountOverride> overridesByRef,
    Instant publishedAt,
    List<ConformanceVector> vectors,
    int format,
    int resolverContract) {

    public Replica {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(publishedAt, "publishedAt");
        overridesByRef = Map.copyOf(overridesByRef);
        vectors = List.copyOf(vectors);
    }

    public long version() {
        return snapshot.snapshotVersion();
    }
}
```

- [ ] **Step 4: Write `FullSnapshotReader`**

Structure it as: read all lines, validate the header/footer frame, then dispatch each body line. Build conformance vectors by recursively reusing the same line records — a vector's `model` holds `capabilities[]`, `plans[]`, `accounts[]`, `overrides[]` arrays whose elements are exactly the line shapes, plus scalar `account` and `capability` naming the subject.

```java
package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.FeedDtos;
import com.solovis.entitlement.client.wire.ValueDto;
import com.solovis.entitlement.client.wire.WireMapper;
import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;

/**
 * Turns the NDJSON body of {@code GET /v1/snapshot/full} into a {@link Replica}.
 *
 * <p>A snapshot is applied whole or not at all. A body without a matching footer is a truncated
 * response, and a truncated response that were partially applied would be a wrong answer with
 * nothing to diagnose it by — so it is discarded.
 */
public final class FullSnapshotReader {

    private static final Logger LOG = Logger.getLogger(FullSnapshotReader.class.getName());

    private FullSnapshotReader() {}

    /** A feed body that cannot be trusted as a complete, single-version snapshot. */
    public static final class MalformedFeedException extends RuntimeException {
        public MalformedFeedException(String message) {
            super(message);
        }
        public MalformedFeedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Replica read(InputStream ndjson) {
        // Implementation notes for the engineer:
        //  1. Read lines with a BufferedReader over an InputStreamReader in UTF-8. Skip blank lines.
        //  2. Parse each line with ClientJson.MAPPER.readTree; read the "kind" text node.
        //  3. The first non-blank line MUST be kind=header, else MalformedFeedException("...header...").
        //  4. Collect body lines until kind=footer. Any line after the footer is malformed.
        //  5. If no footer was seen, MalformedFeedException("...footer...").
        //  6. footer.version must equal header.version, else MalformedFeedException naming both.
        //  7. Dispatch body lines into a SnapshotBuilder:
        //       capability  -> builder.capability(WireMapper.toCapability(line, publishedAt))
        //       plan        -> builder.plan(WireMapper.toPlan(line)) and, for each entry of
        //                      line.entitlements(), builder.planEntitlement(
        //                          new PlanEntitlement(line.key(), new CapabilityKey(k), WireMapper.toValue(v)))
        //       account     -> builder.account(WireMapper.toAccount(line))
        //       override    -> builder.override(o) and overridesByRef.put(o.id().getAsLong(), o)
        //       conformance -> vectors.add(toVector(line, publishedAt))
        //       anything else -> LOG.warning(...) and skip; a replica must tolerate a new record type
        //  8. Wrap any JacksonException in MalformedFeedException — a body we cannot parse is a body
        //     we must not apply.
        //  9. Return new Replica(builder.build(header.version()), overridesByRef, publishedAt,
        //                        vectors, header.format(), header.resolverContract()).
        throw new UnsupportedOperationException("implement per the notes above, then delete this line");
    }

    /**
     * Builds one {@link ConformanceVector} from a self-contained {@code conformance} line. The
     * vector's {@code model} carries its own miniature snapshot, so it is assembled through the
     * same {@link SnapshotBuilder} the real feed uses — the gate must exercise the real path.
     */
    private static ConformanceVector toVector(FeedDtos.ConformanceLine line, Instant publishedAt) {
        // Implementation notes:
        //  - model.capabilities[] / plans[] / accounts[] / overrides[] each hold the same line
        //    shapes as the top-level feed. treeToValue each element into its FeedDtos record and
        //    feed a fresh SnapshotBuilder exactly as read() does. Build at version 0 — a fixture's
        //    version is never observed.
        //  - model.account and model.capability are the scalar subject of the vector.
        //  - expect.allowed is a boolean; expect.value is a ValueDto.
        //  - The vector's name is line.id(). Note this is an English sentence, not a cv_NNN id.
        throw new UnsupportedOperationException("implement per the notes above, then delete this line");
    }
}
```

**Note to the implementer:** the two `UnsupportedOperationException` bodies above are the only place in this plan where a method body is described rather than given, because the parse loop is mechanical and long. Every behaviour it must have is pinned by a test in Step 1 — implement until all ten pass, and delete both `throw` lines.

Also write `replica/package-info.java`:

```java
/**
 * The local replica: reading it from the feed, advancing it by delta, gating it for conformance,
 * and caching it to disk. Nothing in this package touches HTTP — that seam is what lets outage
 * behaviour be tested without a network.
 */
package com.solovis.entitlement.client.replica;
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=FullSnapshotReaderTest
```
Expected: 10 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/replica/ \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/replica/FullSnapshotReaderTest.java
git commit -m "feat(entitlement-client): read a full NDJSON snapshot into an immutable replica, discarding truncated bodies whole"
```

---

## Task 5: The conformance gate

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/ConformanceGate.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/replica/ConformanceGateTest.java`

**Interfaces:**
- Consumes: `Replica` (Task 4); core `ConformanceCheck`, `ConformanceVector`, `ResolverContract`.
- Produces:
  - `ConformanceGate.SUPPORTED_FORMAT` (int, `1`)
  - `ConformanceGate.evaluate(Replica) -> GateResult`
  - `record GateResult(boolean passed, String reason)` with `static GateResult ok()`

**What the gate is for.** The resolution rule now runs inside every consuming service, and a consumer that computes a wrong answer produces no trace to diagnose it with. Detection therefore has to happen *before* the first wrong answer. The gate is cheap — a few dozen evaluations of a microsecond rule.

**Three ways to fail, and they are not the same failure.** Report which in `reason`:
1. `format` is not one this SDK knows — the shape of the payload is unrecognised.
2. `resolverContract` is not `ResolverContract.VERSION` — the payload's *meaning* changed. This is the drift that makes two replicas disagree about the same account.
3. A vector's expected `(allowed, value)` does not match what this SDK's engine computes.

**A snapshot with no vectors passes.** The feed is entitled to ship none, and refusing to serve would turn an empty vector set into an outage.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConformanceGateTest {

    private static final Instant PUBLISHED = Instant.parse("2026-08-09T14:03:10.900Z");

    private static Replica replica(int format, int contract, List<ConformanceVector> vectors) {
        var snapshot = new SnapshotBuilder().build(48211L);
        return new Replica(snapshot, Map.of(), PUBLISHED, vectors, format, contract);
    }

    /** A vector whose expectation the real resolver satisfies. */
    private static ConformanceVector satisfiable() {
        // No explicit off-value: core forbids one on a SWITCH and folds in the rule that false is
        // always SWITCH's off-value, so `allowed` still comes out true here for Switch(true).
        var capability = new Capability(
            new CapabilityKey("api.access"), "api.access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false),
            Optional.empty(),
            TierOrder.NONE, Capability.Status.ACTIVE, null);
        var fixture = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("p", "p", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("p", new CapabilityKey("api.access"),
                new EntitlementValue.Switch(true)))
            .account(new AccountAssignment("acct_c1", "p"))
            .build(0L);
        return new ConformanceVector("plan grants the switch", fixture, "acct_c1",
            new CapabilityKey("api.access"), true, new EntitlementValue.Switch(true));
    }

    /** The same fixture, with an expectation the resolver cannot produce. */
    private static ConformanceVector unsatisfiable() {
        var ok = satisfiable();
        return new ConformanceVector("a vector this engine disagrees with", ok.fixture(),
            ok.accountExternalId(), ok.capabilityKey(), false, new EntitlementValue.Switch(false));
    }

    @Test
    void vectorsThisEngineAgreesWithPassTheGate() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(satisfiable())));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void aSnapshotCarryingNoVectorsPassesRatherThanBecomingAnOutage() {
        var result = ConformanceGate.evaluate(replica(1, ResolverContract.VERSION, List.of()));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void aVectorThisEngineComputesDifferentlyFailsTheGateAndNamesTheVector() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(unsatisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("a vector this engine disagrees with");
    }

    @Test
    void aResolverContractThisSdkDoesNotImplementFailsTheGateBecauseTheRuleItselfChanged() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION + 1, List.of(satisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("resolverContract");
    }

    @Test
    void anUnrecognisedWireFormatFailsTheGateSeparatelyFromTheSemanticsCheck() {
        var result = ConformanceGate.evaluate(
            replica(99, ResolverContract.VERSION, List.of(satisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("format");
    }

    @Test
    void aVectorThatThrowsIsAFailureNotAnEscapingException() {
        var capability = new Capability(
            new CapabilityKey("api.access"), "api.access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);
        var fixture = new SnapshotBuilder().capability(capability).build(0L);
        var noSuchAccount = new ConformanceVector("vector naming an account its fixture lacks",
            fixture, "acct_missing", new CapabilityKey("api.access"), true,
            new EntitlementValue.Switch(false));

        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(noSuchAccount)));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("vector naming an account its fixture lacks");
    }

    @Test
    void theRealSpecFiveWorkedExamplesPassAgainstThisSdksEngine() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, ConformanceVector.spec5WorkedExamples()));

        assertThat(result.passed()).isTrue();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ConformanceGateTest
```
Expected: compilation failure — `ConformanceGate` does not exist.

- [ ] **Step 3: Write `ConformanceGate`**

Note core's `ConformanceCheck.run` does **not** catch exceptions from a vector, so the gate must wrap the call — a vector that throws is a gate failure, not a crash on the poller thread.

```java
package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.core.conformance.ConformanceCheck;
import com.solovis.entitlement.core.conformance.ResolverContract;

/**
 * Evaluates a candidate replica's own conformance vectors with this SDK's engine before the
 * replica is allowed to serve.
 *
 * <p>This is the primary defence against two replicas on different SDK versions answering
 * differently for the same account. It has to be proactive: with no local traces, a wrong answer
 * leaves nothing to diagnose after the fact.
 */
public final class ConformanceGate {

    /** The wire format this SDK knows how to read. */
    public static final int SUPPORTED_FORMAT = 1;

    private ConformanceGate() {}

    public record GateResult(boolean passed, String reason) {
        public static GateResult ok() {
            return new GateResult(true, "");
        }
    }

    public static GateResult evaluate(Replica candidate) {
        if (candidate.format() != SUPPORTED_FORMAT) {
            return new GateResult(false, "Unsupported feed format " + candidate.format()
                + "; this SDK reads format " + SUPPORTED_FORMAT + ".");
        }
        if (candidate.resolverContract() != ResolverContract.VERSION) {
            return new GateResult(false, "Unsupported resolverContract " + candidate.resolverContract()
                + "; this SDK implements " + ResolverContract.VERSION
                + ". The resolution rule itself changed — this needs a coordinated rollout.");
        }
        try {
            var result = ConformanceCheck.run(candidate.vectors());
            return result.passed()
                ? GateResult.ok()
                : new GateResult(false, "Conformance vectors failed: " + String.join("; ", result.failures()));
        } catch (RuntimeException e) {
            return new GateResult(false, "A conformance vector could not be evaluated: " + e.getMessage());
        }
    }
}
```

**Careful:** the last test requires the failing vector's *name* to appear in `reason`. Core's `ConformanceCheck` includes `vector.name()` in each failure string, so the mismatch path is covered. The throwing path is not — a `RuntimeException` from `ConformanceCheck.run` carries the account id, not the vector name. Make the throwing path name the vector by evaluating vectors one at a time inside the gate rather than handing the whole list to `ConformanceCheck.run`:

```java
        var failures = new java.util.ArrayList<String>();
        for (var vector : candidate.vectors()) {
            try {
                var single = ConformanceCheck.run(java.util.List.of(vector));
                failures.addAll(single.failures());
            } catch (RuntimeException e) {
                failures.add(vector.name() + ": could not be evaluated — " + e);
            }
        }
        return failures.isEmpty()
            ? GateResult.ok()
            : new GateResult(false, "Conformance vectors failed: " + String.join("; ", failures));
```

Use this loop form. It keeps every failure attributable to a named vector, which is the whole diagnostic value of the gate.

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ConformanceGateTest
```
Expected: 7 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/replica/ConformanceGate.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/replica/ConformanceGateTest.java
git commit -m "feat(entitlement-client): gate every candidate replica on the feed's own conformance vectors"
```

---

## Task 6: Advancing a replica by delta

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/DeltaDtos.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/DeltaApplier.java`
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotMutator.java` — add `withVersion`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/replica/DeltaApplierTest.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotMutatorTest.java` — cover `withVersion`

The core addition, purely additive and sharing all five maps by reference:

```java
    /**
     * The same model at a new version. A delta change that turns out to be a no-op on this replica —
     * a removal it never saw, a redelivered creation — still advances the version, because the
     * replica has genuinely caught up to it. Without this the version stalls and the replica
     * re-requests the same change forever.
     */
    public static Snapshot withVersion(Snapshot base, long newVersion) {
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), base.liveOverridesMap());
    }
```

**Interfaces:**
- Consumes: `Replica` (Task 4), `WireMapper`/`FeedDtos`/`ValueDto` (Task 3); core `SnapshotMutator`.
- Produces:
  - `DeltaDtos.DeltaResponse(int format, long fromVersion, long toVersion, String publishedAt, List<JsonNode> changes)`
  - `DeltaApplier.apply(Replica current, DeltaDtos.DeltaResponse delta) -> Replica`
  - `DeltaApplier.UnknownChangeKindException extends RuntimeException` with `String kind()`
  - `DeltaApplier.OutOfOrderDeltaException extends RuntimeException`

**Why `changes` stays as `JsonNode`.** The service flattens each change to `{"version":N,"kind":"…", …payload}` with no nested `change` object, and an **unknown kind must stop the sync, not be skipped** — skipping a change would silently diverge this replica from every other one. Binding to a sealed type with `@JsonSubTypes` would throw a Jackson-shaped exception at parse time for the whole batch; holding `JsonNode` lets the applier fail precisely, naming the kind, after the earlier changes in the batch have been validated.

**The nine kinds, and how each maps onto core's mutator.** Core's `SnapshotMutator` has seven methods and none of them removes a capability, a plan, or an account — which is correct, because the model never deletes any of those. Mapping:

| `kind` | Action |
|---|---|
| `capability.upserted` | `withCapability` — the payload nests the **full descriptor** under a `capability` key (unlike the flat, trimmed full-snapshot line), so read `change.get("capability")` and map it |
| `capability.retired` | read the current capability by `key`, rebuild it with `Status.RETIRED` and `retiredAt = delta.publishedAt`, `withCapability` |
| `plan.upserted` | `withPlan(new Plan(key, name, Status.valueOf(status), isDefaultForNewAccounts))` |
| `plan.entitlements` | for each entry of `set{}`, `withPlanEntitlement`; for each key in `unset[]`, `withPlanEntitlementRemoved` |
| `plan.archived` | read the current plan, rebuild with `Status.ARCHIVED` and `defaultForNewAccounts=false`, `withPlan` |
| `plan.defaultChanged` | clear `defaultForNewAccounts` on whichever plan currently holds it, then set it on `key` — **two** `withPlan` calls. Exactly one default plan is an invariant |
| `account.upserted` | `withAccount` — covers creation and reassignment alike |
| `override.created` | `withOverrideAdded`, and index the new override by ref |
| `override.removed` | look the ref up in `overridesByRef` to recover `(account, capability, id)`, then `withOverrideRemoved`, and drop the index entry |

**Three traps.**
- `withOverrideAdded` appends blindly with no dedupe by id. A redelivered `override.created` would double-count. Guard: if `overridesByRef` already holds the ref, skip the change — it is already applied.
- `override.removed` for a ref the index does not hold is a no-op, not an error. A replica that full-resynced past the removal never saw the override.
- **A no-op change must still advance the version.** Five branches can be no-ops — a removal for an unknown ref, a redelivered creation, and a retire/archive/default-change naming a target this replica does not hold. Returning the snapshot unchanged leaves the version behind, and because `Replica.version()` reads `snapshot.snapshotVersion()` the replica then re-requests the same change forever, never advancing and never resyncing. Every decision would also report a stale `snapshotVersion`, and `check(..., minSnapshotVersion)` would throw `SnapshotBehindException` permanently. Core has no version-only mutation, so **Task 6 adds `SnapshotMutator.withVersion(base, newVersion)`** — purely additive, sharing all five maps by reference — and the no-op branches yield that.

**Ordering is mandatory.** Changes must be applied in ascending `version`. Reject a batch whose first change is not `current.version() + 1`, or whose versions are not strictly ascending — that is a gap, and the caller must full-resync rather than guess.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.DeltaDtos;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DeltaApplierTest {

    private Replica base;

    private static final String BASE_FEED = String.join("\n",
        """
        {"kind":"header","version":100,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:00:00.000Z",\
        "counts":{"capabilities":1,"plans":2,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY",\
        "default":{"type":"QUANTITY","amount":0},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50}}}""",
        """
        {"kind":"plan","key":"free","status":"ACTIVE","isDefaultForNewAccounts":false,"entitlements":{}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",\
        "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""",
        """
        {"kind":"footer","version":100,"recordCount":7}""");

    @BeforeEach
    void loadBase() {
        base = FullSnapshotReader.read(
            new ByteArrayInputStream(BASE_FEED.getBytes(StandardCharsets.UTF_8)));
    }

    private static DeltaDtos.DeltaResponse delta(long from, long to, String... changeJson) {
        List<JsonNode> changes = java.util.Arrays.stream(changeJson)
            .map(j -> (JsonNode) ClientJson.MAPPER.readTree(j))
            .toList();
        return new DeltaDtos.DeltaResponse(1, from, to, "2026-08-09T14:03:10.900Z", changes);
    }

    @Test
    void planEntitlementChangesSetAndUnsetInOneChange() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.entitlements","planKey":"pro",\
            "set":{"reports.monthly":{"type":"QUANTITY","amount":75}},"unset":["export.parquet"]}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.snapshot().planEntitlement("pro", new CapabilityKey("reports.monthly")))
            .get().extracting(pe -> pe.value()).isEqualTo(EntitlementValue.Quantity.of(75));
    }

    @Test
    void anOverrideCreationIsAddedAndIndexedByItsRef() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.created","ref":"ovr_9002","account":"acct_9931",\
            "capability":"reports.monthly","overrideKind":"HOLD","value":{"type":"QUANTITY","amount":10}}"""));

        assertThat(applied.overridesByRef()).containsKey(9002L);
        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .hasSize(2);
    }

    @Test
    void anOverrideRemovalFindsItsAccountAndCapabilityThroughTheRefIndex() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.removed","ref":"ovr_4471"}"""));

        assertThat(applied.overridesByRef()).doesNotContainKey(4471L);
        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .isEmpty();
    }

    @Test
    void removingARefThisReplicaNeverSawIsANoOpBecauseAFullResyncMayHavePassedIt() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.removed","ref":"ovr_7788"}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.overridesByRef()).containsKey(4471L);
    }

    @Test
    void aRedeliveredOverrideCreationIsNotAppliedTwiceBecauseTheCoreMutatorAppendsBlindly() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.created","ref":"ovr_4471","account":"acct_9931",\
            "capability":"reports.monthly","overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}"""));

        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .hasSize(1);
    }

    @Test
    void accountUpsertCoversReassignmentAsWellAsCreation() {
        var applied = DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":101,"kind":"account.upserted","external":"acct_9931","planKey":"free"}""",
            """
            {"version":102,"kind":"account.upserted","external":"acct_new","planKey":"pro"}"""));

        assertThat(applied.snapshot().account("acct_9931")).get()
            .extracting(a -> a.planKey()).isEqualTo("free");
        assertThat(applied.snapshot().account("acct_new")).isPresent();
    }

    @Test
    void aRetirementMarksTheCapabilityWithoutDroppingItSoTheRetiredErrorStaysAvailable() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"capability.retired","key":"reports.monthly"}"""));

        var capability = applied.snapshot().capability(new CapabilityKey("reports.monthly"));
        assertThat(capability).isPresent();
        assertThat(capability.get().status()).isEqualTo(Capability.Status.RETIRED);
        assertThat(capability.get().retiredAt()).isNotNull();
    }

    @Test
    void capabilityUpsertReadsTheNestedDescriptorNotAFlatLine() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"capability.upserted","capability":{"key":"seats.limit","area":"seats",\
            "displayName":"Seats","valueType":"QUANTITY","default":{"type":"QUANTITY","amount":5},\
            "status":"ACTIVE"}}"""));

        assertThat(applied.snapshot().capability(new CapabilityKey("seats.limit"))).isPresent();
    }

    @Test
    void archivingAPlanMarksItAndStripsAnyDefaultDesignation() {
        var applied = DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":101,"kind":"plan.defaultChanged","key":"free"}""",
            """
            {"version":102,"kind":"plan.archived","key":"pro"}"""));

        var pro = applied.snapshot().plan("pro").orElseThrow();
        assertThat(pro.status()).isEqualTo(com.solovis.entitlement.core.model.Plan.Status.ARCHIVED);
        assertThat(pro.defaultForNewAccounts()).isFalse();
    }

    @Test
    void movingTheDefaultDesignationClearsThePreviousHolderSoExactlyOnePlanEverHoldsIt() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.defaultChanged","key":"free"}"""));

        assertThat(applied.snapshot().plan("pro").orElseThrow().defaultForNewAccounts()).isFalse();
        assertThat(applied.snapshot().plan("free").orElseThrow().defaultForNewAccounts()).isTrue();
        assertThat(applied.snapshot().plans().stream().filter(p -> p.defaultForNewAccounts()).count())
            .isEqualTo(1L);
    }

    @Test
    void anEmptyDeltaLeavesTheReplicaExactlyWhereItWas() {
        var applied = DeltaApplier.apply(base, delta(100, 100));

        assertThat(applied.version()).isEqualTo(100L);
        assertThat(applied.snapshot()).isSameAs(base.snapshot());
    }

    @Test
    void aBatchThatDoesNotStartAtTheNextVersionIsAGapAndIsRejectedRatherThanGuessedAt() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 105, """
            {"version":103,"kind":"plan.archived","key":"free"}""")))
            .isInstanceOf(DeltaApplier.OutOfOrderDeltaException.class);
    }

    @Test
    void changesOutOfAscendingOrderAreRejected() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":102,"kind":"plan.archived","key":"free"}""",
            """
            {"version":101,"kind":"account.upserted","external":"a","planKey":"pro"}""")))
            .isInstanceOf(DeltaApplier.OutOfOrderDeltaException.class);
    }

    @Test
    void anUnknownChangeKindStopsTheSyncRatherThanBeingSkippedIntoSilentDivergence() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"conformance.changed","vectors":[]}""")))
            .isInstanceOf(DeltaApplier.UnknownChangeKindException.class)
            .extracting(e -> ((DeltaApplier.UnknownChangeKindException) e).kind())
            .isEqualTo("conformance.changed");
    }

    @Test
    void theResultingReplicaCarriesTheDeltasPublishedAtSoFreshnessTracksTheFeed() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"account.upserted","external":"acct_x","planKey":"pro"}"""));

        assertThat(applied.publishedAt())
            .isEqualTo(java.time.Instant.parse("2026-08-09T14:03:10.900Z"));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DeltaApplierTest
```
Expected: compilation failure — `DeltaDtos` and `DeltaApplier` do not exist.

- [ ] **Step 3: Write `DeltaDtos`**

```java
package com.solovis.entitlement.client.wire;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The envelope of {@code GET /v1/snapshot?since=N}.
 *
 * <p>Each element of {@code changes} is a flat object — {@code {"version":N,"kind":"…",…payload}}
 * — because the service's {@code ChangeSerializer} splices the payload's properties up alongside
 * {@code version} rather than nesting them. They stay as {@link JsonNode} so an unrecognised
 * {@code kind} can be reported precisely instead of failing the whole batch inside Jackson.
 */
public final class DeltaDtos {

    private DeltaDtos() {}

    public record DeltaResponse(
        int format, long fromVersion, long toVersion, String publishedAt, List<JsonNode> changes) {}
}
```

- [ ] **Step 4: Write `DeltaApplier`**

```java
package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.DeltaDtos;
import com.solovis.entitlement.client.wire.FeedDtos;
import com.solovis.entitlement.client.wire.ValueDto;
import com.solovis.entitlement.client.wire.WireMapper;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * Moves a {@link Replica} forward by one delta batch, applying every change in ascending version
 * order through {@code entitlement-core}'s {@link SnapshotMutator}.
 *
 * <p>Ordering matters here, for replication. It does not matter for resolution — §4's rule is
 * order-independent, so two replicas that reach the same version by different paths hold identical
 * state and return identical decisions (c16).
 */
public final class DeltaApplier {

    private DeltaApplier() {}

    /** A change kind this SDK does not implement. Stop syncing and keep serving the last good replica. */
    public static final class UnknownChangeKindException extends RuntimeException {
        private final String kind;
        public UnknownChangeKindException(String kind) {
            super("Unknown delta change kind '" + kind + "'. Stopping sync rather than diverging silently.");
            this.kind = kind;
        }
        public String kind() {
            return kind;
        }
    }

    /** A gap or a reordering in the batch. The caller must full-resync. */
    public static final class OutOfOrderDeltaException extends RuntimeException {
        public OutOfOrderDeltaException(String message) {
            super(message);
        }
    }

    public static Replica apply(Replica current, DeltaDtos.DeltaResponse delta) {
        var publishedAt = Instant.parse(delta.publishedAt());
        if (delta.changes().isEmpty()) {
            return new Replica(current.snapshot(), current.overridesByRef(), publishedAt,
                current.vectors(), current.format(), current.resolverContract());
        }

        long expected = current.version() + 1;
        var snapshot = current.snapshot();
        var byRef = new HashMap<>(current.overridesByRef());

        for (var change : delta.changes()) {
            long version = change.get("version").asLong();
            if (version < expected) {
                throw new OutOfOrderDeltaException(
                    "Delta change at version " + version + " arrived after version " + (expected - 1)
                        + "; batches must ascend without gaps.");
            }
            if (version != expected) {
                throw new OutOfOrderDeltaException(
                    "Delta batch jumps from version " + (expected - 1) + " to " + version
                        + "; a gap means this replica must full-resync.");
            }
            snapshot = applyOne(snapshot, byRef, change, version, publishedAt);
            expected = version + 1;
        }

        return new Replica(snapshot, byRef, publishedAt, current.vectors(),
            current.format(), current.resolverContract());
    }

    private static Snapshot applyOne(
            Snapshot snapshot,
            HashMap<Long, AccountOverride> byRef,
            JsonNode change,
            long version,
            Instant publishedAt) {

        var kind = change.get("kind").asString();
        return switch (kind) {
            case "capability.upserted" -> {
                var line = ClientJson.MAPPER.treeToValue(change.get("capability"), FeedDtos.CapabilityLine.class);
                yield SnapshotMutator.withCapability(snapshot, version, WireMapper.toCapability(line, publishedAt));
            }
            case "capability.retired" -> {
                var key = new CapabilityKey(change.get("key").asString());
                var existing = snapshot.capability(key).orElse(null);
                yield existing == null
                    ? SnapshotMutator.withVersion(snapshot, version)
                    : SnapshotMutator.withCapability(snapshot, version, retire(existing, publishedAt));
            }
            case "plan.upserted" -> SnapshotMutator.withPlan(snapshot, version, new Plan(
                change.get("key").asString(),
                change.get("name").asString(),
                Plan.Status.valueOf(change.get("status").asString()),
                change.get("isDefaultForNewAccounts").asBoolean()));
            case "plan.entitlements" -> {
                var planKey = change.get("planKey").asString();
                var next = snapshot;
                var set = change.get("set");
                if (set != null) {
                    for (var entry : set.properties()) {
                        var value = WireMapper.toValue(
                            ClientJson.MAPPER.treeToValue(entry.getValue(), ValueDto.class));
                        next = SnapshotMutator.withPlanEntitlement(next, version,
                            new PlanEntitlement(planKey, new CapabilityKey(entry.getKey()), value));
                    }
                }
                var unset = change.get("unset");
                if (unset != null) {
                    for (var node : unset) {
                        next = SnapshotMutator.withPlanEntitlementRemoved(
                            next, version, planKey, new CapabilityKey(node.asString()));
                    }
                }
                yield next;
            }
            case "plan.archived" -> {
                var key = change.get("key").asString();
                var existing = snapshot.plan(key).orElse(null);
                yield existing == null
                    ? SnapshotMutator.withVersion(snapshot, version)
                    : SnapshotMutator.withPlan(snapshot, version,
                        new Plan(existing.key(), existing.name(), Plan.Status.ARCHIVED, false));
            }
            case "plan.defaultChanged" -> {
                var key = change.get("key").asString();
                var next = snapshot;
                for (var plan : snapshot.plans()) {
                    if (plan.defaultForNewAccounts() && !plan.key().equals(key)) {
                        next = SnapshotMutator.withPlan(next, version,
                            new Plan(plan.key(), plan.name(), plan.status(), false));
                    }
                }
                var target = next.plan(key).orElse(null);
                yield target == null
                    ? SnapshotMutator.withVersion(next, version)
                    : SnapshotMutator.withPlan(next, version,
                        new Plan(target.key(), target.name(), target.status(), true));
            }
            case "account.upserted" -> SnapshotMutator.withAccount(snapshot, version,
                new AccountAssignment(change.get("external").asString(), change.get("planKey").asString()));
            case "override.created" -> {
                long id = WireMapper.refToId(change.get("ref").asString());
                if (byRef.containsKey(id)) {
                    // already applied; the core mutator appends without dedupe. Still advance the
                    // version — the replica has genuinely caught up to this change.
                    yield SnapshotMutator.withVersion(snapshot, version);
                }
                var override = new AccountOverride(
                    OptionalLong.of(id),
                    change.get("account").asString(),
                    new CapabilityKey(change.get("capability").asString()),
                    OverrideKind.valueOf(change.get("overrideKind").asString()),
                    WireMapper.toValue(ClientJson.MAPPER.treeToValue(change.get("value"), ValueDto.class)),
                    Optional.empty(), Optional.empty(), Optional.empty());
                byRef.put(id, override);
                yield SnapshotMutator.withOverrideAdded(snapshot, version, override);
            }
            case "override.removed" -> {
                long id = WireMapper.refToId(change.get("ref").asString());
                var known = byRef.remove(id);
                yield known == null
                    ? SnapshotMutator.withVersion(snapshot, version)   // never seen here; a full resync may have passed it
                    : SnapshotMutator.withOverrideRemoved(
                        snapshot, version, known.accountExternalId(), known.capabilityKey(), id);
            }
            default -> throw new UnknownChangeKindException(kind);
        };
    }

    private static Capability retire(Capability existing, Instant retiredAt) {
        return new Capability(
            existing.key(), existing.displayName(), existing.description(), existing.valueType(),
            existing.defaultValue(), existing.offValue(), existing.tierOrder(),
            Capability.Status.RETIRED, retiredAt);
    }
}
```

**Note on the empty-delta case:** the test asserts `applied.snapshot()` is the *same instance* as the base's. The early return above satisfies that. Do not "simplify" it into the loop.

**Note on Jackson 3 tree access:** `asString()`, `asLong()`, `asBoolean()` and `properties()` are the Jackson 3 spellings (Jackson 2 used `asText()` and `fields()`). If a method does not resolve, check you are on `tools.jackson.databind.JsonNode`, not the 2.x class.

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DeltaApplierTest
```
Expected: 15 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/wire/DeltaDtos.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/replica/DeltaApplier.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/replica/DeltaApplierTest.java
git commit -m "feat(entitlement-client): advance a replica by delta, stopping rather than diverging on an unknown change kind"
```

---

## Task 7: Disk cache — surviving a restart during an outage

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/ReplicaNdjsonWriter.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/replica/DiskCache.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/replica/DiskCacheTest.java`

**Interfaces:**
- Consumes: `Replica`, `FullSnapshotReader` (Task 4).
- Produces:
  - `ReplicaNdjsonWriter.write(Replica, OutputStream)` — emits the feed's own line shapes
  - `DiskCache(Path directory)` with `void store(Replica)` and `Optional<Replica> load()`

**The design in one line:** the cache file *is* a full-snapshot feed body. That means one reader for both paths, and the round-trip is a testable identity rather than a second format to keep in sync.

**Write atomically.** Write to `<dir>/snapshot.ndjson.tmp`, then `Files.move` with `ATOMIC_MOVE` onto `<dir>/snapshot.ndjson`. A process killed mid-write must not leave a truncated cache — though note the footer check in `FullSnapshotReader` already makes a truncated cache *safe*, just useless.

**A corrupt or unreadable cache is not an error.** `load()` returns `Optional.empty()` and logs at WARN. The caller decides what that means; for `REQUIRE_SNAPSHOT` it means nothing at all.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":2,"plans":1,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"support.level","area":"support","valueType":"TIER",\
        "default":{"type":"TIER","tier":"community","ordinal":0},\
        "tiers":[{"tier":"community","ordinal":0,"displayName":"Community"},\
        {"tier":"gold","ordinal":1,"displayName":"Gold"}],"status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"support.level":{"type":"TIER","tier":"gold","ordinal":1}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"support.level",\
        "overrideKind":"HOLD","value":{"type":"TIER","tier":"community","ordinal":0}}""",
        """
        {"kind":"footer","version":48211,"recordCount":7}""");

    private static Replica sample() {
        return FullSnapshotReader.read(new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aStoredReplicaReloadsIdenticallySoARestartDuringAnOutageKeepsTheCustomersEntitlements(
            @TempDir Path dir) {
        var original = sample();
        var cache = new DiskCache(dir);

        cache.store(original);
        var reloaded = cache.load().orElseThrow();

        assertThat(reloaded.version()).isEqualTo(original.version());
        assertThat(reloaded.publishedAt()).isEqualTo(original.publishedAt());
        assertThat(reloaded.format()).isEqualTo(original.format());
        assertThat(reloaded.resolverContract()).isEqualTo(original.resolverContract());
        assertThat(reloaded.snapshot().capabilities())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().capabilities());
        assertThat(reloaded.snapshot().plans())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().plans());
        assertThat(reloaded.snapshot().accountAssignments())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().accountAssignments());
        assertThat(reloaded.snapshot().allLiveOverrides())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().allLiveOverrides());
        assertThat(reloaded.overridesByRef()).isEqualTo(original.overridesByRef());
    }

    @Test
    void tierOrdersAndRetirementSurviveTheRoundTripBecauseTheyChangeWhatAnswersAreLegal(
            @TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        var reloaded = cache.load().orElseThrow();
        var support = reloaded.snapshot().capability(new CapabilityKey("support.level")).orElseThrow();
        var legacy = reloaded.snapshot().capability(new CapabilityKey("legacy.export")).orElseThrow();

        assertThat(support.tierOrder().ordinalOf("gold")).hasValue(1);
        assertThat(legacy.isRetired()).isTrue();
    }

    @Test
    void planEntitlementsSurviveTheRoundTripBecauseTheyAreTheBaselineOfEveryDecision(@TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        var reloaded = cache.load().orElseThrow();

        assertThat(reloaded.snapshot().planEntitlement("pro", new CapabilityKey("support.level")))
            .get().extracting(pe -> pe.value())
            .isEqualTo(new EntitlementValue.Tier("gold", 1));
    }

    @Test
    void conformanceVectorsAreNotCachedSoAReloadedReplicaCarriesNone(@TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        assertThat(cache.load().orElseThrow().vectors()).isEmpty();
    }

    @Test
    void anEmptyDirectoryYieldsNoReplicaRatherThanAnError(@TempDir Path dir) {
        assertThat(new DiskCache(dir).load()).isEmpty();
    }

    @Test
    void aCorruptCacheFileYieldsNoReplicaRatherThanPropagatingAParseFailureIntoStartup(
            @TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("snapshot.ndjson"), "this is not ndjson\n");

        assertThat(new DiskCache(dir).load()).isEmpty();
    }

    @Test
    void storingTwiceLeavesExactlyOneCacheFileAndNoTemporaryBehind(@TempDir Path dir) throws Exception {
        var cache = new DiskCache(dir);
        cache.store(sample());
        cache.store(sample());

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("snapshot.ndjson");
        }
    }

    @Test
    void storeCreatesTheCacheDirectoryWhenTheProductPointedAtOneThatDoesNotExistYet(@TempDir Path dir) {
        var nested = dir.resolve("var").resolve("cache").resolve("entitlements");

        new DiskCache(nested).store(sample());

        assertThat(new DiskCache(nested).load()).isPresent();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DiskCacheTest
```
Expected: compilation failure.

- [ ] **Step 3: Write `ReplicaNdjsonWriter`**

Emit exactly the shapes `FullSnapshotReader` reads: `header`, then one `capability` line per capability (with `tiers` and `offValue` **omitted** when empty/absent — use `LinkedHashMap` and simply do not put the key), one `plan` line per plan with its `entitlements` map, one `account` line, one `override` line per live override, then `footer`. Conformance vectors are deliberately **not** written: they are re-fetched with the next successful sync, and a cached replica is gated on load by the caller only when vectors exist.

Serialise each line with `ClientJson.MAPPER.writeValueAsString(map)` and separate with `\n`. Reuse `ValueDto` for values — write a small private `toDto(EntitlementValue)` mirroring the service's `ValueMapper.toDto`:

```java
    private static ValueDto toDto(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new ValueDto("SWITCH", s.enabled(), null, null, null, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new ValueDto("QUANTITY", null, null, true, null, null)
                : new ValueDto("QUANTITY", null, q.amount(), null, null, null);
            case EntitlementValue.Tier t -> new ValueDto("TIER", null, null, null, t.tierKey(), t.ordinal());
        };
    }
```

The `header` line needs `version`, `format`, `resolverContract`, `publishedAt` and a `counts` object; the `footer` needs `version` and `recordCount` (total lines including header and footer). Order the plan's entitlements map deterministically (sort by capability key) so two stores of the same replica produce identical bytes — that is what makes the round-trip test meaningful rather than accidental.

- [ ] **Step 4: Write `DiskCache`**

```java
package com.solovis.entitlement.client.replica;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An on-disk copy of the last replica, so a caller that restarts during an outage still knows what
 * its customers are entitled to.
 *
 * <p>The file is a full-snapshot feed body, which means one reader serves both the network and the
 * disk path, and the round-trip is an identity rather than a second format to keep in step.
 */
public final class DiskCache {

    private static final Logger LOG = Logger.getLogger(DiskCache.class.getName());
    private static final String FILE = "snapshot.ndjson";
    private static final String TEMP = "snapshot.ndjson.tmp";

    private final Path directory;

    public DiskCache(Path directory) {
        this.directory = java.util.Objects.requireNonNull(directory, "directory");
    }

    /** Writes atomically: a process killed mid-write leaves the previous cache intact. */
    public void store(Replica replica) {
        try {
            Files.createDirectories(directory);
            var temp = directory.resolve(TEMP);
            try (var out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                ReplicaNdjsonWriter.write(replica, out);
            }
            Files.move(temp, directory.resolve(FILE),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the entitlement replica cache; continuing without it.", e);
        }
    }

    /** Empty when there is no cache, or the cache cannot be trusted. Never throws. */
    public Optional<Replica> load() {
        var file = directory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(FullSnapshotReader.read(in));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring an unreadable entitlement replica cache at " + file, e);
            return Optional.empty();
        }
    }
}
```

If `ATOMIC_MOVE` is unsupported on the filesystem, catch `AtomicMoveNotSupportedException` and retry with `REPLACE_EXISTING` alone.

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DiskCacheTest
```
Expected: 8 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/replica/ReplicaNdjsonWriter.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/replica/DiskCache.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/replica/DiskCacheTest.java
git commit -m "feat(entitlement-client): cache the replica to disk in the feed's own format so a restart survives an outage"
```

---

## Task 8: Transport — HTTP, gzip, and the problem model

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ProblemDto.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/FeedHttpClient.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/SnapshotTooOldException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/FeedUnavailableException.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/package-info.java`
- Create: `entitlement-client/src/test/java/com/solovis/entitlement/client/testing/StubFeedServer.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/transport/FeedHttpClientTest.java`

**Interfaces:**
- Consumes: `ClientJson.MAPPER`, `DeltaDtos`, `FullSnapshotReader`, `Replica`.
- Produces:
  - `FeedHttpClient(URI baseUri, HttpClient http, Duration requestTimeout)` implementing `AutoCloseable`
  - `SnapshotVersionDto version()` — `record SnapshotVersionDto(long version, String publishedAt, int format, int resolverContract)` nested in `FeedHttpClient`
  - `Replica full()` — GETs, gunzips, parses
  - `DeltaDtos.DeltaResponse delta(long since)`
  - `String decisionJson(String account, String capability)` — raw body for Task 12
  - `SnapshotTooOldException`, `FeedUnavailableException`
  - `StubFeedServer` — test fixture: `start()`, `URI baseUri()`, `void respondVersion(...)`, `void respondFull(String ndjson)`, `void respondDelta(String json)`, `void failWith(int status, String problemJson)`, `void goOffline()`, `close()`

**Gzip is not automatic.** The JDK `HttpClient` does not decompress; `/v1/snapshot/full` always responds `Content-Encoding: gzip` regardless of `Accept-Encoding`. Wrap the body stream in a `GZIPInputStream`. Send `Accept-Encoding: gzip` anyway — it is what the contract documents.

**Every non-2xx becomes a typed failure, and only two of them mean anything different:**
- 410 with `type == "entitlement/snapshot-too-old"` → `SnapshotTooOldException`; the poller full-resyncs.
- 422 on `?since=` (the service was restored from a backup, so `since` exceeds the current version) → also `SnapshotTooOldException`; same recovery.
- Everything else, including connection failure, timeout, 5xx, and a malformed body → `FeedUnavailableException`. The poller backs off; the replica keeps serving.

**`StubFeedServer` uses `com.sun.net.httpserver.HttpServer`** — in the JDK, so no new dependency. Bind to `new InetSocketAddress("127.0.0.1", 0)` for an ephemeral port. It must be able to gzip a full-snapshot body, return canned deltas, return problem+json with a given status, and simulate an outage by refusing connections (stop the server).

- [ ] **Step 1: Write `StubFeedServer`**

```java
package com.solovis.entitlement.client.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * An in-process stand-in for the management service's feed, so outage, truncation and
 * malformed-response behaviour can be tested without a network or a Spring context.
 */
public final class StubFeedServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> versionBody = new AtomicReference<>(
        "{\"version\":1,\"publishedAt\":\"2026-08-09T14:00:00.000Z\",\"format\":1,\"resolverContract\":1}");
    private final AtomicReference<String> fullBody = new AtomicReference<>("");
    private final AtomicReference<String> deltaBody = new AtomicReference<>(null);
    private final AtomicReference<String> decisionBody = new AtomicReference<>(null);
    private final AtomicReference<int[]> failure = new AtomicReference<>(null);   // {status} for the next call
    private final AtomicReference<String> failureBody = new AtomicReference<>(null);
    private final AtomicInteger versionCalls = new AtomicInteger();
    private final AtomicInteger fullCalls = new AtomicInteger();
    private final AtomicInteger deltaCalls = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile boolean truncateFull = false;

    public StubFeedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/snapshot/version", this::handleVersion);
        server.createContext("/v1/snapshot/full", this::handleFull);
        server.createContext("/v1/snapshot", this::handleDelta);
        server.createContext("/v1/accounts", this::handleDecision);
        server.setExecutor(null);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void respondVersion(long version, String publishedAt, int format, int resolverContract) {
        versionBody.set("{\"version\":" + version + ",\"publishedAt\":\"" + publishedAt
            + "\",\"format\":" + format + ",\"resolverContract\":" + resolverContract + "}");
    }

    public void respondFull(String ndjson) {
        fullBody.set(ndjson);
    }

    public void respondDelta(String json) {
        deltaBody.set(json);
    }

    public void respondDecision(String json) {
        decisionBody.set(json);
    }

    /** The next request to any route answers with this status and problem+json body. */
    public void failWith(int status, String problemJson) {
        failure.set(new int[] {status});
        failureBody.set(problemJson);
    }

    /** Truncate the full-snapshot body before its footer, simulating a cut-off response. */
    public void truncateFullSnapshot() {
        truncateFull = true;
    }

    public int versionCalls() {
        return versionCalls.get();
    }

    public int fullCalls() {
        return fullCalls.get();
    }

    public int deltaCalls() {
        return deltaCalls.get();
    }

    public List<String> requestedPaths() {
        return List.copyOf(paths);
    }

    private boolean servedFailure(HttpExchange exchange) throws IOException {
        var pending = failure.getAndSet(null);
        if (pending == null) {
            return false;
        }
        var body = failureBody.getAndSet(null).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(pending[0], body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return true;
    }

    private void handleVersion(HttpExchange exchange) throws IOException {
        versionCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        respond(exchange, 200, "application/json", versionBody.get().getBytes(StandardCharsets.UTF_8));
    }

    private void handleFull(HttpExchange exchange) throws IOException {
        fullCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var text = fullBody.get();
        if (truncateFull) {
            int cut = text.lastIndexOf("\n{\"kind\":\"footer\"");
            text = cut > 0 ? text.substring(0, cut) : text;
        }
        var gzipped = gzip(text);
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, gzipped.length);
        exchange.getResponseBody().write(gzipped);
        exchange.close();
    }

    private void handleDelta(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        if (path.startsWith("/v1/snapshot/")) {   // /full and /version have their own contexts
            respond(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        deltaCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var body = deltaBody.get();
        if (body == null) {
            respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDecision(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var body = decisionBody.get();
        if (body == null) {
            respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] gzip(String text) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.solovis.entitlement.client.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.testing.StubFeedServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedHttpClientTest {

    private StubFeedServer stub;
    private FeedHttpClient client;

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":1,"plans":1,"accounts":1,"overrides":0}}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"footer","version":48211,"recordCount":5}""");

    @BeforeEach
    void start() throws Exception {
        stub = new StubFeedServer();
        client = new FeedHttpClient(stub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        client.close();
        stub.close();
    }

    @Test
    void theVersionPollReturnsTheFeedsVersionFormatAndResolverContract() {
        stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);

        var version = client.version();

        assertThat(version.version()).isEqualTo(48211L);
        assertThat(version.format()).isEqualTo(1);
        assertThat(version.resolverContract()).isEqualTo(1);
    }

    @Test
    void theFullSnapshotIsGunzippedAndParsedIntoAReplica() {
        stub.respondFull(FEED);

        var replica = client.full();

        assertThat(replica.version()).isEqualTo(48211L);
        assertThat(replica.snapshot().capabilities()).hasSize(1);
    }

    @Test
    void aTruncatedFullSnapshotIsRejectedRatherThanPartiallyApplied() {
        stub.respondFull(FEED);
        stub.truncateFullSnapshot();

        assertThatThrownBy(() -> client.full()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void aDeltaIsFetchedWithTheSinceParameterAndParsedFlatWithoutANestedChangeObject() {
        stub.respondDelta("""
            {"format":1,"fromVersion":48208,"toVersion":48209,\
            "publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":48209,"kind":"plan.archived","key":"free"}]}""");

        var delta = client.delta(48208L);

        assertThat(delta.fromVersion()).isEqualTo(48208L);
        assertThat(delta.toVersion()).isEqualTo(48209L);
        assertThat(delta.changes()).hasSize(1);
        assertThat(delta.changes().get(0).get("kind").asString()).isEqualTo("plan.archived");
        assertThat(stub.requestedPaths()).anyMatch(p -> p.contains("since=48208"));
    }

    @Test
    void aSinceOlderThanTheRetainedHorizonAsksForAFullResyncRatherThanBeingTreatedAsAnOutage() {
        stub.failWith(410, """
            {"type":"entitlement/snapshot-too-old","title":"Snapshot too old","status":410,\
            "detail":"…","currentVersion":48211}""");

        assertThatThrownBy(() -> client.delta(1L)).isInstanceOf(SnapshotTooOldException.class);
    }

    @Test
    void aSinceAheadOfTheServiceMeansItWasRestoredFromBackupAndAlsoTriggersAFullResync() {
        stub.failWith(422, """
            {"type":"entitlement/validation-failed","title":"Validation failed","status":422,\
            "detail":"…","currentVersion":100}""");

        assertThatThrownBy(() -> client.delta(99999L)).isInstanceOf(SnapshotTooOldException.class);
    }

    @Test
    void aServerErrorIsAnUnavailableFeedNotASignalToResync() {
        stub.failWith(503, "{\"type\":\"entitlement/internal-error\",\"status\":503}");

        assertThatThrownBy(() -> client.version()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void anUnreachableServiceIsAnUnavailableFeedAndNeverAnUncheckedIoException() {
        stub.close();

        assertThatThrownBy(() -> client.version()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void aMalformedBodyIsAnUnavailableFeedBecauseAnUnparseableAnswerIsNoAnswer() {
        stub.respondDelta("{ this is not json");

        assertThatThrownBy(() -> client.delta(1L)).isInstanceOf(FeedUnavailableException.class);
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=FeedHttpClientTest
```
Expected: compilation failure.

- [ ] **Step 4: Write `ProblemDto` and the two transport exceptions**

```java
package com.solovis.entitlement.client.wire;

/**
 * RFC 9457 problem details as the service emits them. Note {@code type} is a bare relative slug
 * ({@code "entitlement/unknown-account"}), not an absolute URI, and that extra properties are
 * flattened at the top level rather than nested. Branch on {@code type}, never on {@code detail}.
 */
public record ProblemDto(String type, String title, Integer status, String detail, Long currentVersion) {}
```

```java
package com.solovis.entitlement.client.transport;

/** The delta path is unusable from this version; the caller must fetch a full snapshot. */
public final class SnapshotTooOldException extends RuntimeException {
    public SnapshotTooOldException(String message) {
        super(message);
    }
}
```

```java
package com.solovis.entitlement.client.transport;

/**
 * The service could not be reached, or answered in a way this SDK cannot use. Always recoverable
 * by backing off and retrying — never a reason to change an answer.
 */
public final class FeedUnavailableException extends RuntimeException {
    public FeedUnavailableException(String message) {
        super(message);
    }
    public FeedUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Write `FeedHttpClient`**

Key points: build requests with `HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept-Encoding","gzip").GET()`; send `full()` with `BodyHandlers.ofInputStream()` and everything else with `BodyHandlers.ofString()`; wrap `IOException`/`InterruptedException` in `FeedUnavailableException` (and re-set the interrupt flag on `InterruptedException`); on non-2xx, parse the body as `ProblemDto` and map 410-with-`snapshot-too-old` and 422-with-`currentVersion` to `SnapshotTooOldException`, everything else to `FeedUnavailableException`; wrap `FullSnapshotReader.MalformedFeedException` from `full()` in `FeedUnavailableException` so the poller's single catch handles it.

```java
    public record SnapshotVersionDto(long version, String publishedAt, int format, int resolverContract) {}
```

`close()` on Java 21's `HttpClient` — the class does not implement `AutoCloseable` until Java 21's `close()` was added; call `http.close()` if the client was created by this class, and otherwise leave a caller-supplied client alone. Track that with a boolean set in the constructor.

Add `transport/package-info.java`:

```java
/**
 * Everything that touches the network, and nothing that understands the domain. This package hands
 * back parsed replicas and DTOs; it never resolves a decision, and {@code replica} never opens a
 * socket. That seam is what makes the outage posture testable.
 */
package com.solovis.entitlement.client.transport;
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=FeedHttpClientTest
```
Expected: 9 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/transport/ \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/wire/ProblemDto.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/testing/StubFeedServer.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/transport/FeedHttpClientTest.java
git commit -m "feat(entitlement-client): feed transport over JDK HttpClient, with gzip and the RFC 9457 problem model"
```

---

## Task 9: Backoff

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/Backoff.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/transport/BackoffTest.java`

**Interfaces:**
- Produces: `Backoff(Duration base, LongUnaryOperator jitter)` with `Duration nextDelay()`, `void reset()`, and a convenience `Backoff(Duration base)` using `ThreadLocalRandom`.

**The ladder the contract specifies:** 5 s → 10 s → 30 s → 60 s, jittered, then holding at 60 s. The base is the configured poll interval, so a 5-second poll produces exactly that ladder; the steps are multipliers `1, 2, 6, 12` of the base. Jitter is ±20%, injected as a function so the test is deterministic.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    /** No jitter, so the ladder itself is what is under test. */
    private static Backoff noJitter(Duration base) {
        return new Backoff(base, millis -> millis);
    }

    @Test
    void theLadderClimbsFiveTenThirtySixtySecondsFromAFiveSecondPoll() {
        var backoff = noJitter(Duration.ofSeconds(5));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theLadderHoldsAtItsTopRatherThanClimbingForever() {
        var backoff = noJitter(Duration.ofSeconds(5));
        for (int i = 0; i < 4; i++) {
            backoff.nextDelay();
        }

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aSuccessfulSyncResetsTheLadderToItsFirstRung() {
        var backoff = noJitter(Duration.ofSeconds(5));
        backoff.nextDelay();
        backoff.nextDelay();

        backoff.reset();

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theLadderScalesWithTheConfiguredPollIntervalRatherThanHardCodingSeconds() {
        var backoff = noJitter(Duration.ofSeconds(1));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void jitterIsAppliedToEveryRungSoAFleetOfReplicasDoesNotRetryInLockstep() {
        var backoff = new Backoff(Duration.ofSeconds(5), millis -> millis / 2);

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(2).plusMillis(500));
    }

    @Test
    void theDefaultConstructorJittersWithinTwentyPercentOfEachRung() {
        var backoff = new Backoff(Duration.ofSeconds(5));

        var first = backoff.nextDelay();

        assertThat(first).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=BackoffTest
```
Expected: compilation failure.

- [ ] **Step 3: Write `Backoff`**

```java
package com.solovis.entitlement.client.transport;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * The retry ladder a replica walks while the service is unreachable: 5 s, 10 s, 30 s, 60 s,
 * jittered, then holding. Expressed as multiples of the configured poll interval so the ladder
 * scales with it rather than hard-coding seconds.
 *
 * <p>Not thread-safe: one instance belongs to one poller thread.
 */
public final class Backoff {

    private static final int[] RUNGS = {1, 2, 6, 12};

    private final Duration base;
    private final LongUnaryOperator jitter;
    private int rung;

    public Backoff(Duration base, LongUnaryOperator jitter) {
        this.base = java.util.Objects.requireNonNull(base, "base");
        this.jitter = java.util.Objects.requireNonNull(jitter, "jitter");
    }

    /** Jitters uniformly within ±20% so a fleet of replicas does not retry in lockstep. */
    public Backoff(Duration base) {
        this(base, millis -> {
            long spread = Math.max(1L, millis / 5);
            return millis - spread + ThreadLocalRandom.current().nextLong(2 * spread + 1);
        });
    }

    public Duration nextDelay() {
        long millis = base.toMillis() * RUNGS[Math.min(rung, RUNGS.length - 1)];
        rung = Math.min(rung + 1, RUNGS.length - 1);
        return Duration.ofMillis(Math.max(0L, jitter.applyAsLong(millis)));
    }

    /** Called after a successful sync. */
    public void reset() {
        rung = 0;
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=BackoffTest
```
Expected: 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/transport/Backoff.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/transport/BackoffTest.java
git commit -m "feat(entitlement-client): jittered retry ladder scaled to the configured poll interval"
```

---

## Task 10: The sync loop

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/metrics/ClientMetrics.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/metrics/package-info.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/SnapshotPoller.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/SnapshotPollerTest.java`

**Interfaces:**
- Consumes: `FeedHttpClient`, `SnapshotTooOldException`, `FeedUnavailableException` (Task 8); `Backoff` (Task 9); `Replica`, `DeltaApplier`, `ConformanceGate`, `DiskCache` (Tasks 4–7).
- Produces:
  - `ClientMetrics` — interface with `void snapshotVersion(long)`, `void syncFailed()`, `void fullResync()`, `void conformanceFailed()`, `void decision(String capabilityKey, boolean allowed)`, `void readThrough()`, `void resolverContract(int)`, `void snapshotAge(java.util.function.Supplier<Duration>)`; and `ClientMetrics NO_OP`.
  - `SnapshotPoller(FeedHttpClient feed, AtomicReference<Replica> holder, Duration pollInterval, Duration staleAfter, DiskCache cacheOrNull, ClientMetrics metrics, Clock clock)` with `void start()`, `boolean syncOnce()`, `SyncState state()`, `void close()`.
  - `record SyncState(Instant lastSuccessfulSync, String lastError, boolean stale)`.

**`syncOnce()` is the whole behaviour, and it is synchronous and package-visible so every rule below is testable without a thread.** `start()` merely wraps it in a daemon loop.

**The rules, in the order `syncOnce()` applies them:**
1. Poll `/v1/snapshot/version`. If it fails, record the error, back off, keep serving. Never throw.
2. If the reported `version` equals the replica's, do nothing and count it a success — the replica is current.
3. Otherwise fetch a delta from the replica's version. On `SnapshotTooOldException`, or `OutOfOrderDeltaException`, or `UnknownChangeKindException`, fall back per the table below.
4. Run the candidate through `ConformanceGate`. **If it fails, discard the candidate and keep the previous replica** — a suspect update never displaces a known-good one — count a conformance failure, and log at ERROR.
5. Swap the `AtomicReference`, store to the disk cache if configured, reset the backoff, and record the new version.

**Fallback table — this is the §11 posture, and a reviewer should check each row against a test:**

| Situation | Behaviour |
|---|---|
| Version poll or delta fetch fails | Keep serving. Record `lastError`. Back off. |
| `SnapshotTooOldException` | Full resync. The old replica keeps serving until the new one is complete and gated. |
| A full resync also fails | Keep serving the old replica. Back off. |
| `UnknownChangeKindException` or an unsupported `format`/`resolverContract` | **Stop syncing entirely.** Log at ERROR. Keep serving the last good replica. Fail loudly, not silently. |
| Conformance gate fails on the candidate | Discard the candidate, keep the previous replica, count `conformanceFailed`, log ERROR. |
| No successful sync for longer than `staleAfter` | Still answer. `stale` becomes true; WARN **once per transition**, not per call. |

**Do not let the poller thread die.** Wrap the loop body in a catch-all; an escaping exception would silently stop replication and the replica would age forever while `health()` still looked plausible.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.testing.StubFeedServer;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.core.model.CapabilityKey;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnapshotPollerTest {

    private StubFeedServer stub;
    private FeedHttpClient feed;
    private AtomicReference<Replica> holder;
    private MutableClock clock;

    /** A clock the test advances by hand, so staleness is deterministic. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-09T14:03:10.900Z");
        void advance(Duration by) {
            now = now.plus(by);
        }
        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
        @Override public Instant instant() {
            return now;
        }
    }

    private static String feedAt(long version, String extraLine) {
        var lines = new java.util.ArrayList<String>();
        lines.add("{\"kind\":\"header\",\"version\":" + version + ",\"format\":1,\"resolverContract\":1,"
            + "\"publishedAt\":\"2026-08-09T14:03:10.900Z\","
            + "\"counts\":{\"capabilities\":1,\"plans\":1,\"accounts\":1,\"overrides\":0}}");
        lines.add("{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\",\"valueType\":\"SWITCH\","
            + "\"default\":{\"type\":\"SWITCH\",\"enabled\":false},\"status\":\"ACTIVE\"}");
        lines.add("{\"kind\":\"plan\",\"key\":\"pro\",\"status\":\"ACTIVE\",\"isDefaultForNewAccounts\":true,"
            + "\"entitlements\":{\"api.access\":{\"type\":\"SWITCH\",\"enabled\":true}}}");
        lines.add("{\"kind\":\"account\",\"external\":\"acct_9931\",\"planKey\":\"pro\"}");
        if (extraLine != null) {
            lines.add(extraLine);
        }
        lines.add("{\"kind\":\"footer\",\"version\":" + version + ",\"recordCount\":" + (lines.size() + 1) + "}");
        return String.join("\n", lines);
    }

    private static Replica replicaAt(long version) {
        return FullSnapshotReader.read(
            new ByteArrayInputStream(feedAt(version, null).getBytes(StandardCharsets.UTF_8)));
    }

    private SnapshotPoller poller() {
        return new SnapshotPoller(feed, holder, Duration.ofSeconds(5), Duration.ofSeconds(60),
            null, ClientMetrics.NO_OP, clock);
    }

    @BeforeEach
    void start() throws Exception {
        stub = new StubFeedServer();
        feed = new FeedHttpClient(stub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
        holder = new AtomicReference<>(replicaAt(100L));
        clock = new MutableClock();
    }

    @AfterEach
    void stop() {
        feed.close();
        stub.close();
    }

    @Test
    void aVersionMatchingTheReplicaIsASuccessfulSyncThatChangesNothing() {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var before = holder.get();

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void aNewerVersionIsAppliedByDeltaAndSwappedIn() {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"account.upserted","external":"acct_new","planKey":"pro"}]}""");

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get().version()).isEqualTo(101L);
        assertThat(holder.get().snapshot().account("acct_new")).isPresent();
    }

    @Test
    void anUnreachableServiceLeavesTheReplicaServingAndNeverThrows() {
        stub.close();
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void aFailedSyncIsRecordedSoHealthCanSurfaceIt() {
        stub.close();
        var poller = poller();

        poller.syncOnce();

        assertThat(poller.state().lastError()).isNotNull();
    }

    @Test
    void aSinceOlderThanTheHorizonTriggersAFullResyncAndTheOldReplicaServesUntilItCompletes() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.failWith(410, """
            {"type":"entitlement/snapshot-too-old","title":"Snapshot too old","status":410,\
            "currentVersion":200}""");
        stub.respondFull(feedAt(200L, null));

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get().version()).isEqualTo(200L);
        assertThat(stub.fullCalls()).isEqualTo(1);
    }

    @Test
    void aFullResyncThatAlsoFailsLeavesThePreviousReplicaInPlace() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.failWith(410, "{\"type\":\"entitlement/snapshot-too-old\",\"status\":410}");
        stub.respondFull(feedAt(200L, null));
        stub.truncateFullSnapshot();
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void anUnknownDeltaChangeKindStopsSyncingAltogetherRatherThanDivergingSilently() {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"something.newer","payload":1}]}""");
        var poller = poller();
        var before = holder.get();

        assertThat(poller.syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(poller.stopped()).isTrue();

        // and it does not try again
        int callsSoFar = stub.versionCalls();
        poller.syncOnce();
        assertThat(stub.versionCalls()).isEqualTo(callsSoFar);
    }

    @Test
    void aResolverContractTheSdkDoesNotImplementStopsSyncingAndKeepsTheLastGoodReplica() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 99);
        var poller = poller();
        var before = holder.get();

        assertThat(poller.syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(poller.stopped()).isTrue();
    }

    @Test
    void aCandidateFailingTheConformanceGateIsDiscardedAndThePreviousReplicaKeepsServing() {
        var badVector = "{\"kind\":\"conformance\",\"id\":\"a vector this engine disagrees with\","
            + "\"model\":{\"account\":\"acct_c1\",\"capability\":\"api.access\","
            + "\"capabilities\":[{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\","
            + "\"valueType\":\"SWITCH\",\"default\":{\"type\":\"SWITCH\",\"enabled\":false},"
            + "\"status\":\"ACTIVE\"}],"
            + "\"plans\":[{\"kind\":\"plan\",\"key\":\"p\",\"status\":\"ACTIVE\","
            + "\"isDefaultForNewAccounts\":true,\"entitlements\":{}}],"
            + "\"accounts\":[{\"kind\":\"account\",\"external\":\"acct_c1\",\"planKey\":\"p\"}],"
            + "\"overrides\":[]},"
            + "\"expect\":{\"allowed\":true,\"value\":{\"type\":\"SWITCH\",\"enabled\":true}}}";
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.failWith(410, "{\"type\":\"entitlement/snapshot-too-old\",\"status\":410}");
        stub.respondFull(feedAt(200L, badVector));
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void theReplicaBecomesStaleOnceSyncsHaveBeenFailingLongerThanTheStaleWindow() {
        stub.close();
        var poller = poller();
        poller.syncOnce();
        assertThat(poller.state().stale()).isFalse();

        clock.advance(Duration.ofSeconds(61));
        poller.syncOnce();

        assertThat(poller.state().stale()).isTrue();
    }

    @Test
    void aSuccessfulSyncClearsStalenessAndTheRecordedError() {
        stub.close();
        var poller = poller();
        poller.syncOnce();
        clock.advance(Duration.ofSeconds(61));
        poller.syncOnce();
        assertThat(poller.state().stale()).isTrue();

        // bring the service back on a fresh port is not possible; instead assert the transition
        // logic directly through a successful sync against a new stub.
    }

    @Test
    void aConfiguredDiskCacheIsWrittenOnEverySuccessfulSwap(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"account.upserted","external":"acct_new","planKey":"pro"}]}""");
        var cache = new com.solovis.entitlement.client.replica.DiskCache(dir);

        new SnapshotPoller(feed, holder, Duration.ofSeconds(5), Duration.ofSeconds(60),
            cache, ClientMetrics.NO_OP, clock).syncOnce();

        assertThat(cache.load()).get().extracting(r -> ((Replica) r).version()).isEqualTo(101L);
    }

    @Test
    void theDaemonLoopKeepsRunningAcrossFailuresAndStopsOnClose() throws Exception {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var poller = new SnapshotPoller(feed, holder, Duration.ofMillis(20), Duration.ofSeconds(60),
            null, ClientMetrics.NO_OP, clock);

        poller.start();
        Thread.sleep(200);
        int calls = stub.versionCalls();
        poller.close();
        Thread.sleep(100);

        assertThat(calls).isGreaterThan(1);
        assertThat(stub.versionCalls()).isLessThanOrEqualTo(calls + 1);
    }
}
```

**Remove the incomplete test `aSuccessfulSyncClearsStalenessAndTheRecordedError` before committing** — it is written above only to mark the behaviour. Replace it with a version that constructs a *second* `StubFeedServer`, points a new `FeedHttpClient` at it, and asserts that after one successful `syncOnce()` the state reports `stale=false` and `lastError=null`. A test that asserts nothing is worse than no test.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=SnapshotPollerTest
```
Expected: compilation failure.

- [ ] **Step 3: Write `ClientMetrics`**

```java
package com.solovis.entitlement.client.metrics;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * The metrics seam. The SDK always calls it; whether anything is recorded depends on whether the
 * embedding product supplied a Micrometer registry. Keeping it an interface is what lets Micrometer
 * stay an optional dependency.
 */
public interface ClientMetrics {

    ClientMetrics NO_OP = new ClientMetrics() {};

    /** Convergence across replicas. */
    default void snapshotVersion(long version) {}

    /** Registers a gauge reading the replica's age. Called once, at construction. */
    default void snapshotAge(Supplier<Duration> age) {}

    /** Service reachability from this caller. */
    default void syncFailed() {}

    /** A replica falling behind the delta horizon. */
    default void fullResync() {}

    /** The drift gate firing. */
    default void conformanceFailed() {}

    /** Alert on disagreement across replicas — a straddled rollout, visible before it is a ticket. */
    default void resolverContract(int contract) {}

    /** Which capabilities actually gate anything. */
    default void decision(String capabilityKey, boolean allowed) {}

    /** Unknown-account races; a sustained rise means replicas are lagging. */
    default void readThrough() {}
}
```

And `metrics/package-info.java`:

```java
/**
 * Optional Micrometer instrumentation. {@code ClientMetrics.NO_OP} is the default, so a product
 * that never supplies a registry never loads a Micrometer class.
 */
package com.solovis.entitlement.client.metrics;
```

- [ ] **Step 4: Write `SnapshotPoller`**

```java
package com.solovis.entitlement.client;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.ConformanceGate;
import com.solovis.entitlement.client.replica.DeltaApplier;
import com.solovis.entitlement.client.replica.DiskCache;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.transport.Backoff;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.client.transport.FeedUnavailableException;
import com.solovis.entitlement.client.transport.SnapshotTooOldException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The daemon that keeps one replica current: poll the version, advance by delta, full-resync when
 * the delta path is unusable, gate the candidate, swap.
 *
 * <p>Nothing here ever makes the decision path fail. A sync that cannot complete leaves the last
 * good replica serving, which is the entire §11 posture: not fail-open, not fail-closed, but the
 * last state it knew.
 */
final class SnapshotPoller implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SnapshotPoller.class.getName());

    private final FeedHttpClient feed;
    private final AtomicReference<Replica> holder;
    private final Duration pollInterval;
    private final Duration staleAfter;
    private final DiskCache cache;              // nullable
    private final ClientMetrics metrics;
    private final Clock clock;
    private final Backoff backoff;

    private final AtomicReference<SyncState> state;
    private volatile boolean stopped;           // a contract violation was seen; stop syncing
    private volatile boolean closed;
    private volatile Thread thread;
    private volatile boolean warnedStale;

    record SyncState(Instant lastSuccessfulSync, String lastError, boolean stale) {}

    SnapshotPoller(FeedHttpClient feed, AtomicReference<Replica> holder, Duration pollInterval,
            Duration staleAfter, DiskCache cache, ClientMetrics metrics, Clock clock) {
        this.feed = feed;
        this.holder = holder;
        this.pollInterval = pollInterval;
        this.staleAfter = staleAfter;
        this.cache = cache;
        this.metrics = metrics;
        this.clock = clock;
        this.backoff = new Backoff(pollInterval);
        this.state = new AtomicReference<>(new SyncState(clock.instant(), null, false));
    }

    SyncState state() {
        return state.get();
    }

    /** True once a format or resolverContract mismatch has permanently halted replication. */
    boolean stopped() {
        return stopped;
    }

    void start() {
        var t = new Thread(this::loop, "entitlement-snapshot-poller");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void loop() {
        while (!closed) {
            Duration wait;
            try {
                wait = syncOnce() ? pollInterval : backoff.nextDelay();
            } catch (RuntimeException e) {
                // Belt and braces. A poller thread that dies would age the replica forever while
                // health() still looked plausible.
                LOG.log(Level.SEVERE, "Unexpected failure in the entitlement snapshot poller.", e);
                wait = backoff.nextDelay();
            }
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One sync attempt. Returns true when the replica is current. Never throws. */
    boolean syncOnce() {
        if (stopped) {
            return false;
        }
        try {
            var version = feed.version();
            if (version.format() != ConformanceGate.SUPPORTED_FORMAT
                || version.resolverContract() != com.solovis.entitlement.core.conformance.ResolverContract.VERSION) {
                halt("Feed advertises format " + version.format() + " / resolverContract "
                    + version.resolverContract() + "; this SDK implements format "
                    + ConformanceGate.SUPPORTED_FORMAT + " / resolverContract "
                    + com.solovis.entitlement.core.conformance.ResolverContract.VERSION
                    + ". Replication has stopped; the last good replica keeps serving.");
                return false;
            }
            var current = holder.get();
            if (version.version() == current.version()) {
                return succeed();
            }
            Replica candidate;
            try {
                candidate = DeltaApplier.apply(current, feed.delta(current.version()));
            } catch (SnapshotTooOldException | DeltaApplier.OutOfOrderDeltaException e) {
                metrics.fullResync();
                candidate = feed.full();
            } catch (DeltaApplier.UnknownChangeKindException e) {
                halt("Feed delivered an unknown change kind '" + e.kind()
                    + "'. Replication has stopped; the last good replica keeps serving.");
                return false;
            }
            var gate = ConformanceGate.evaluate(candidate);
            if (!gate.passed()) {
                metrics.conformanceFailed();
                LOG.severe("Discarding a snapshot that failed the conformance gate; keeping version "
                    + current.version() + ". " + gate.reason());
                return fail(gate.reason());
            }
            holder.set(candidate);
            if (cache != null) {
                cache.store(candidate);
            }
            metrics.snapshotVersion(candidate.version());
            metrics.resolverContract(candidate.resolverContract());
            return succeed();
        } catch (FeedUnavailableException e) {
            return fail(e.getMessage());
        } catch (RuntimeException e) {
            return fail(e.toString());
        }
    }

    private void halt(String reason) {
        stopped = true;
        LOG.severe(reason);
        fail(reason);
    }

    private boolean succeed() {
        backoff.reset();
        warnedStale = false;
        state.set(new SyncState(clock.instant(), null, false));
        return true;
    }

    private boolean fail(String error) {
        metrics.syncFailed();
        var previous = state.get();
        var since = Duration.between(previous.lastSuccessfulSync(), clock.instant());
        boolean stale = since.compareTo(staleAfter) > 0;
        if (stale && !warnedStale) {
            warnedStale = true;   // once per transition, not once per call
            LOG.warning("Entitlement replica has not synced for " + since
                + " and is now stale. It keeps answering from the last state it knew.");
        }
        state.set(new SyncState(previous.lastSuccessfulSync(), error, stale));
        return false;
    }

    @Override
    public void close() {
        closed = true;
        var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=SnapshotPollerTest
```
Expected: all tests pass (12 after replacing the placeholder test as instructed).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/metrics/ \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/SnapshotPoller.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/SnapshotPollerTest.java
git commit -m "feat(entitlement-client): the sync loop, holding the last good replica through every failure mode"
```

---

## Task 11: The decision path

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClient.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/AccountEntitlements.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/ClientHealth.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/DefaultEntitlementClientTest.java`

**Interfaces:**
- Consumes: `SnapshotPoller`, `Replica`, `FeedHttpClient`, `ClientMetrics`; core `Resolver`, `Decision`, `Capability`.
- Produces:
  - `EntitlementClient` — the public interface, exactly as the contract declares it, plus `static EntitlementClientBuilder builder()`.
  - `AccountEntitlements(String account, String planKey, List<Decision> decisions, long snapshotVersion, Instant evaluatedAt)`
  - `ClientHealth(long snapshotVersion, Instant snapshotPublishedAt, Duration snapshotAge, boolean stale, Instant lastSuccessfulSync, Optional<String> lastError)`
  - `DefaultEntitlementClient` — package-private class implementing the interface; constructed only by the builder.

**This task implements `check`, `checkAll`, `capability`, `capabilities`, `health` and `close`.** `explain`, read-through, `minSnapshotVersion` and `awaitVersion` land in Tasks 12 and 13; declare them on the interface now and have `DefaultEntitlementClient` throw `UnsupportedOperationException` for them until then — the tests added in those tasks are what remove the throws.

**`checkAll` resolves every non-retired capability against one snapshot read (c31).** Read the `AtomicReference` **once** into a local, take one `Instant` from the clock, and resolve every capability against that pair. Reading the reference per capability would let a sync land mid-loop and produce an answer no single version ever held.

**`check` throws the three domain errors and nothing else.** `Resolver.resolve` already throws all three; let them propagate untouched. Unknown-account gets its read-through treatment in Task 12 — for now the core exception propagates as-is.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultEntitlementClientTest {

    private static final Instant NOW = Instant.parse("2026-08-09T14:05:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":3,"plans":1,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY",\
        "default":{"type":"QUANTITY","amount":0},"offValue":{"type":"QUANTITY","amount":0},\
        "status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50},\
        "api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",\
        "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""",
        """
        {"kind":"footer","version":48211,"recordCount":8}""");

    private AtomicReference<Replica> holder;
    private DefaultEntitlementClient client;

    @BeforeEach
    void setUp() {
        holder = new AtomicReference<>(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        client = DefaultEntitlementClient.forTesting(holder, CLOCK, ClientMetrics.NO_OP);
    }

    @Test
    void aGrantRaisesThePlanBaselineAndTheDecisionCarriesTheSnapshotItWasResolvedAt() {
        var decision = client.check("acct_9931", "reports.monthly");

        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(200));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.snapshotVersion()).isEqualTo(48211L);
        assertThat(decision.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    void aPlanEntitlementIsTheBaselineWhenNoOverrideTouchesTheCapability() {
        assertThat(client.check("acct_9931", "api.access").value())
            .isEqualTo(new EntitlementValue.Switch(true));
    }

    @Test
    void anUnknownCapabilityIsAnErrorAndNeverADenial() {
        assertThatThrownBy(() -> client.check("acct_9931", "no.such.capability"))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void aRetiredCapabilityIsItsOwnErrorRatherThanASilentNo() {
        assertThatThrownBy(() -> client.check("acct_9931", "legacy.export"))
            .isInstanceOf(RetiredCapabilityException.class);
    }

    @Test
    void checkAllCoversEveryNonRetiredCapabilityAtOneSnapshotVersion() {
        var all = client.checkAll("acct_9931");

        assertThat(all.account()).isEqualTo("acct_9931");
        assertThat(all.planKey()).isEqualTo("pro");
        assertThat(all.snapshotVersion()).isEqualTo(48211L);
        assertThat(all.decisions()).hasSize(2);
        assertThat(all.decisions()).extracting(d -> d.capabilityKey())
            .containsExactly("api.access", "reports.monthly");
    }

    @Test
    void everyDecisionInCheckAllSharesOneEvaluationMomentAndOneVersion() {
        var all = client.checkAll("acct_9931");

        assertThat(all.decisions()).allSatisfy(d -> {
            assertThat(d.snapshotVersion()).isEqualTo(all.snapshotVersion());
            assertThat(d.evaluatedAt()).isEqualTo(all.evaluatedAt());
        });
    }

    @Test
    void checkAllOnAnUnknownAccountIsAnError() {
        assertThatThrownBy(() -> client.checkAll("acct_nope"))
            .isInstanceOf(com.solovis.entitlement.core.error.UnknownAccountException.class);
    }

    @Test
    void theCapabilityRegistryIsReadableSoACallerCanInterpretTierValues() {
        assertThat(client.capability("api.access")).isPresent();
        assertThat(client.capability("no.such.capability")).isEmpty();
        assertThat(client.capabilities()).hasSize(3);   // includes the retired one
    }

    @Test
    void healthReportsTheReplicasVersionAndAgeAgainstTheFeedsPublishedAt() {
        var health = client.health();

        assertThat(health.snapshotVersion()).isEqualTo(48211L);
        assertThat(health.snapshotPublishedAt()).isEqualTo(Instant.parse("2026-08-09T14:03:10.900Z"));
        assertThat(health.snapshotAge()).isEqualTo(Duration.ofSeconds(109).plusMillis(100));
        assertThat(health.stale()).isFalse();
        assertThat(health.lastError()).isEmpty();
    }

    @Test
    void decisionsAreCountedSoAnOperatorCanSeeWhichCapabilitiesActuallyGateAnything() {
        var recorded = new java.util.ArrayList<String>();
        var metrics = new ClientMetrics() {
            @Override public void decision(String capabilityKey, boolean allowed) {
                recorded.add(capabilityKey + "=" + allowed);
            }
        };
        var counting = DefaultEntitlementClient.forTesting(holder, CLOCK, metrics);

        counting.check("acct_9931", "api.access");

        assertThat(recorded).containsExactly("api.access=true");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DefaultEntitlementClientTest
```
Expected: compilation failure.

- [ ] **Step 3: Write `AccountEntitlements` and `ClientHealth`**

```java
package com.solovis.entitlement.client;

import com.solovis.entitlement.core.engine.Decision;
import java.time.Instant;
import java.util.List;

/**
 * Every non-retired capability for one account, resolved against a single snapshot version and a
 * single moment (c31). Call this rather than {@code check} in a loop when several capabilities have
 * to agree with each other.
 */
public record AccountEntitlements(
    String account, String planKey, List<Decision> decisions, long snapshotVersion, Instant evaluatedAt) {

    public AccountEntitlements {
        decisions = List.copyOf(decisions);
    }
}
```

```java
package com.solovis.entitlement.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Replica freshness. Surface it on a status page and alert on {@code snapshotAge}; never branch on
 * it for an access decision. Refusing access because a replica is stale would be exactly the
 * taking-away that spec §11 forbids.
 */
public record ClientHealth(
    long snapshotVersion,
    Instant snapshotPublishedAt,
    Duration snapshotAge,
    boolean stale,
    Instant lastSuccessfulSync,
    Optional<String> lastError) {}
```

- [ ] **Step 4: Write `EntitlementClient`**

Declare it exactly as `java-client-sdk.md` specifies — the javadoc on each method is the contract's own wording, and it is load-bearing documentation for consumers:

```java
package com.solovis.entitlement.client;

import com.solovis.entitlement.core.engine.Decision;
import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.model.Capability;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Answers, for one account and one capability, whether they are allowed and what the value is —
 * from a local replica, in microseconds, and while the management service is down.
 *
 * <p>Construct one per process and share it; {@code check} and {@code checkAll} are safe from any
 * thread and lock-free.
 *
 * <h2>Caller obligations</h2>
 * <ol>
 *   <li>Reuse an answer for no longer than 10 seconds, and do not layer a cache in front of
 *       {@code check()} — it is already an in-memory lookup (c29).
 *   <li>Do not persist decisions as durable state. An entitlement is a question you ask, not a
 *       fact you own.
 *   <li>Count usage yourself. This service publishes the limit and never knows consumption; a
 *       {@code QUANTITY} of 0 means none available right now, which is neither an error nor a denial.
 *   <li>Do not infer <em>why</em> from {@code allowed} (c18).
 *   <li>Surface staleness; never act on it.
 *   <li>Handle the three errors as errors, never as "denied" (c19).
 *   <li>Need several capabilities consistently? Call {@code checkAll} once (c31).
 *   <li>Acting on a change you just made? Pass its {@code minSnapshotVersion}.
 *   <li>Never cross a {@code resolverContract} bump without a coordinated rollout.
 * </ol>
 */
public interface EntitlementClient extends AutoCloseable {

    /** One capability. Local, lock-free, microseconds. Never throws on service failure. */
    Decision check(String accountExternalId, String capabilityKey);

    /** Every non-retired capability for one account, resolved at one snapshot version (c31). */
    AccountEntitlements checkAll(String accountExternalId);

    /** As {@link #check}, but resolved at or above {@code minSnapshotVersion}. */
    Decision check(String accountExternalId, String capabilityKey, long minSnapshotVersion);

    /**
     * DIAGNOSTIC ONLY. Always calls the service; never resolves locally. Not for a request path:
     * it is a network call and it fails during an outage.
     */
    Explanation explain(String accountExternalId, String capabilityKey);

    /** The capability registry, including tier orders, for interpreting values. */
    Optional<Capability> capability(String capabilityKey);

    List<Capability> capabilities();

    /** Replica freshness. Surface this; do not branch on it for access decisions. */
    ClientHealth health();

    /** Opt-in: block until the replica reaches a version, or time out. Not a default. */
    boolean awaitVersion(long snapshotVersion, Duration timeout);

    @Override
    void close();

    static EntitlementClientBuilder builder() {
        return new EntitlementClientBuilder();
    }
}
```

- [ ] **Step 5: Write `DefaultEntitlementClient`**

Give it a package-private full constructor `(AtomicReference<Replica> holder, SnapshotPoller poller, FeedHttpClient feed, Clock clock, ClientMetrics metrics)` and a `static DefaultEntitlementClient forTesting(AtomicReference<Replica>, Clock, ClientMetrics)` that passes `null` for the poller and feed — the decision path does not need either, and the test above must not stand up a server to exercise resolution. Guard `close()` and the network paths against those nulls.

```java
    @Override
    public Decision check(String accountExternalId, String capabilityKey) {
        var replica = holder.get();            // one read; a sync mid-call cannot be observed half-applied
        var decision = Resolver.resolve(
            replica.snapshot(), accountExternalId, new CapabilityKey(capabilityKey), clock.instant());
        metrics.decision(capabilityKey, decision.allowed());
        return decision;
    }

    @Override
    public AccountEntitlements checkAll(String accountExternalId) {
        var replica = holder.get();            // one snapshot for every capability (c31)
        var snapshot = replica.snapshot();
        var evaluatedAt = clock.instant();     // one moment for every capability
        var assignment = snapshot.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var decisions = snapshot.activeCapabilities().stream()
            .sorted(java.util.Comparator.comparing(c -> c.key().value()))
            .map(c -> Resolver.resolve(snapshot, accountExternalId, c.key(), evaluatedAt))
            .toList();
        decisions.forEach(d -> metrics.decision(d.capabilityKey(), d.allowed()));
        return new AccountEntitlements(
            accountExternalId, assignment.planKey(), decisions, snapshot.snapshotVersion(), evaluatedAt);
    }
```

`capability(key)` must not throw on a malformed key — `new CapabilityKey(...)` rejects keys that do not match its pattern, so catch `IllegalArgumentException` and return `Optional.empty()`. An unknown key and an unparseable key are the same answer here: no such capability.

`health()` computes `snapshotAge` as `Duration.between(replica.publishedAt(), clock.instant())` and takes `stale`, `lastSuccessfulSync` and `lastError` from `poller.state()` — or, when the poller is null (test construction), reports `stale=false`, `lastSuccessfulSync = clock.instant()`, `lastError = Optional.empty()`.

`close()` stops the poller and closes the feed, both null-guarded. In-flight decisions complete against the snapshot they already hold.

For now: `explain`, the three-argument `check`, and `awaitVersion` throw `new UnsupportedOperationException("Task 12/13")`.

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=DefaultEntitlementClientTest
```
Expected: 10 tests, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClient.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/AccountEntitlements.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/ClientHealth.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/DefaultEntitlementClientTest.java
git commit -m "feat(entitlement-client): resolve decisions locally against the replica, one snapshot per answer"
```

---

## Task 12: Explanation, and the unknown-account read-through

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/wire/DecisionDtos.java`
- Modify: `entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java`
- Modify: `entitlement-client/src/main/java/com/solovis/entitlement/client/transport/FeedHttpClient.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/ExplainAndReadThroughTest.java`

**Interfaces:**
- Produces:
  - `DecisionDtos.DecisionResponse(String account, String capability, boolean allowed, ValueDto value, long snapshotVersion, String evaluatedAt, TraceDto trace)` with the nested trace records mirroring `service.api.dto.DecisionResponseDto`.
  - `DecisionDtos.toExplanation(DecisionResponse) -> Explanation`
  - `DecisionDtos.toDecision(DecisionResponse) -> Decision`
  - `FeedHttpClient.decision(String account, String capability) -> DecisionDtos.DecisionResponse` — throws `UnknownAccountException` on a 404 whose `type` is `entitlement/unknown-account`, `UnknownCapabilityException` on `entitlement/unknown-capability`, `RetiredCapabilityException` on `entitlement/retired-capability`, `FeedUnavailableException` otherwise.

**The unknown-account ambiguity, and the whole point of the read-through.** With a replica, "unknown account" can mean the account does not exist, or that it was created three seconds ago and this replica has not caught up. Throwing on the second case would fail at signup — the worst possible moment. So before raising, the SDK makes **one bounded read-through call** to the service and triggers an out-of-band poll. If the service confirms the account, the answer is served and the replica converges moments later.

If the service is unreachable, it throws. Inventing entitlements for an account it has never seen would be exactly the *granting* §11 forbids, and there is no last answer to carry on with.

**Read-through applies to `check` only, not `checkAll`.** `checkAll` on an unknown account throws immediately: read-through returns one capability's answer, and synthesising a whole-account view from a single-capability route would be a different answer than the service would give.

**Trace mapping.** The service's trace uses string enums and `"ovr_N"` override ids. Map `source` to `TraceSource`, `outcome` to `Outcome`, and parse `overrideId` back to a `long` with `WireMapper.refToId`. Fields the service omits (`planKey` on a non-plan baseline, `winner`/`value` on an unapplied step) arrive absent — map them to empty `Optional`s, never to nulls.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.error.ExplanationUnavailableException;
import com.solovis.entitlement.client.error.ReplicaUnknownAccountException;
import com.solovis.entitlement.client.testing.StubFeedServer;
import com.solovis.entitlement.core.engine.TraceSource;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.model.EntitlementValue;
import org.junit.jupiter.api.Test;

class ExplainAndReadThroughTest {

    // Build the client with EntitlementClient.builder() pointed at a StubFeedServer whose
    // /v1/snapshot/full serves the same FEED string used in DefaultEntitlementClientTest, and
    // whose /v1/accounts route is driven by stub.respondDecision(...). Extract that setup into a
    // private helper in this class; do not copy DefaultEntitlementClientTest's fixture wholesale.

    private static final String EXPLAINED = """
        {"account":"acct_9931","capability":"reports.monthly","allowed":true,
         "value":{"type":"QUANTITY","amount":200},"snapshotVersion":48211,
         "evaluatedAt":"2026-08-09T14:05:00.000Z",
         "trace":{
           "baseline":{"source":"PLAN","planKey":"pro","value":{"type":"QUANTITY","amount":50},
                       "note":"Plan 'pro' sets this capability."},
           "grants":[{"overrideId":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                      "reason":"negotiated uplift","createdBy":"ops@solovis",
                      "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "grantStep":{"applied":true,"winner":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                        "note":"Most generous GRANT (200) beats the plan baseline (50)."},
           "holds":[],
           "holdStep":{"applied":false,"why":"NO_HOLDS"},
           "result":{"value":{"type":"QUANTITY","amount":200},"allowed":true,
                     "allowedReason":"DIFFERS_FROM_OFF_VALUE"}}}""";

    @Test
    void explainFetchesTheServicesRecordBecauseAReplicaHoldsNoTraceData() {
        // stub.respondDecision(EXPLAINED); client.explain("acct_9931", "reports.monthly")
        // assert decision value 200, allowed true, snapshotVersion 48211
        // assert trace.baseline().source() == TraceSource.PLAN and planKey "pro"
        // assert trace.grants() has one entry whose overrideId is OptionalLong.of(4471)
        //        and whose reason is "negotiated uplift" — reason text lives only on this path
        // assert trace.grantWinner() is present and trace.holdWinner() is empty
    }

    @Test
    void explainFailsLoudlyWhenTheServiceIsUnreachableBecauseItIsADiagnosticNotADecision() {
        // stub.close(); assertThatThrownBy(() -> client.explain(...))
        //     .isInstanceOf(ExplanationUnavailableException.class)
    }

    @Test
    void anAccountTheReplicaLacksIsConfirmedByOneBoundedReadThroughRatherThanFailingAtSignup() {
        // stub.respondDecision(a decision body for acct_brand_new)
        // var decision = client.check("acct_brand_new", "reports.monthly");
        // assert the value came from the service, and that exactly one /v1/accounts request was made
    }

    @Test
    void aReadThroughThatConfirmsNothingThrowsCarryingTheEvidenceOfWhichCaseItWas() {
        // stub.failWith(404, {"type":"entitlement/unknown-account",...})
        // assertThatThrownBy(() -> client.check("acct_nope", "reports.monthly"))
        //     .isInstanceOf(ReplicaUnknownAccountException.class)
        //     .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).readThroughAttempted()).isTrue())
    }

    @Test
    void anUnreachableServiceOnAnUnknownAccountStillThrowsBecauseThereIsNoLastAnswerToCarryOn() {
        // stub.close();
        // assertThatThrownBy(() -> client.check("acct_unknown", "reports.monthly"))
        //     .isInstanceOf(ReplicaUnknownAccountException.class)
        //     .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).readThroughAttempted()).isFalse())
        //     .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).snapshotAge()).isPositive())
    }

    @Test
    void aKnownAccountNeverTriggersAReadThroughSoTheDecisionPathStaysLocal() {
        // client.check("acct_9931", "api.access");
        // assertThat(stub.requestedPaths()).noneMatch(p -> p.startsWith("/v1/accounts"))
    }

    @Test
    void checkAllOnAnUnknownAccountThrowsImmediatelyWithoutAReadThrough() {
        // read-through answers one capability; it cannot synthesise a whole-account view
        // assertThatThrownBy(() -> client.checkAll("acct_nope")).isInstanceOf(UnknownAccountException.class)
        // assertThat(stub.requestedPaths()).noneMatch(p -> p.startsWith("/v1/accounts"))
    }

    @Test
    void aReadThroughIsCountedSoASustainedRiseRevealsLaggingReplicas() {
        // supply a counting ClientMetrics through the builder and assert readThrough() fired once
    }
}
```

**Write the bodies.** The comments above state precisely what each test must assert; they are a specification, not a substitute. A test method with a comment and no assertion must not be committed.

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ExplainAndReadThroughTest
```
Expected: failures — `explain` currently throws `UnsupportedOperationException`.

- [ ] **Step 3: Write `DecisionDtos`**

Mirror `service.api.dto.DecisionResponseDto` exactly, including the nested `TraceDto`, `BaselineDto`, `CandidateDto`, `GrantStepDto`, `HoldStepDto` and `ResultDto` shapes, then map to core's `Explanation`. Core's `Trace` is `(baseline, grants, grantWinner, holds, holdWinner, result, allowed)` and each `TraceEntry` is `(source, overrideId, planKey, value, reason, createdBy, createdAt, outcome)`. The grant/hold *winner* is the candidate whose `outcome` is `WON`; when the step reports `applied:false` there is no winner and the `Optional` is empty.

- [ ] **Step 4: Add `decision(...)` to `FeedHttpClient` and implement the two client paths**

`FeedHttpClient.decision` GETs `/v1/accounts/{account}/capabilities/{capability}` with both path segments URL-encoded, and maps the three problem slugs onto the three core exceptions before falling back to `FeedUnavailableException`.

In `DefaultEntitlementClient`:

```java
    @Override
    public Explanation explain(String accountExternalId, String capabilityKey) {
        try {
            return DecisionDtos.toExplanation(feed.decision(accountExternalId, capabilityKey));
        } catch (UnknownAccountException | UnknownCapabilityException | RetiredCapabilityException e) {
            throw e;   // the three distinctions survive the diagnostic path unchanged
        } catch (RuntimeException e) {
            throw new ExplanationUnavailableException(
                "Could not fetch an explanation for " + accountExternalId + "/" + capabilityKey
                    + ". This is a diagnostic path and it fails during an outage — check() does not.", e);
        }
    }
```

and, in `check`, catch the core `UnknownAccountException` and attempt the read-through:

```java
        } catch (UnknownAccountException absent) {
            return readThrough(accountExternalId, capabilityKey, replica);
        }
```

where `readThrough` records the metric, calls `feed.decision(...)`, nudges the poller for an out-of-band sync, and on any transport failure throws
`new ReplicaUnknownAccountException(accountExternalId, age, attempted)` with `attempted=false` when the service was unreachable and `true` when it answered a genuine 404. Add a package-private `void nudge()` to `SnapshotPoller` that interrupts its sleep so the replica converges moments later; make it a no-op when the poller is null or stopped.

- [ ] **Step 5: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=ExplainAndReadThroughTest
```
Expected: 8 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/wire/DecisionDtos.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/SnapshotPoller.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/transport/FeedHttpClient.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/ExplainAndReadThroughTest.java
git commit -m "feat(entitlement-client): service-fetched explanations, and a bounded read-through so signup does not fail on a lagging replica"
```

---

## Task 13: Read-your-writes, and the builder

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/StartupMode.java`
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClientBuilder.java`
- Modify: `entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/ReadYourWritesTest.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/EntitlementClientBuilderTest.java`

**Interfaces:**
- Produces:
  - `enum StartupMode { REQUIRE_SNAPSHOT, ALLOW_DISK_CACHE }`
  - `EntitlementClientBuilder` with `serviceUrl(String)`, `pollInterval(Duration)`, `staleAfter(Duration)`, `diskCache(Path)`, `startupTimeout(Duration)`, `startupMode(StartupMode)`, `meterRegistry(MeterRegistry)`, `httpClient(HttpClient)`, `clock(Clock)`, and `EntitlementClient build()`.
  - `DefaultEntitlementClient.check(account, capability, minSnapshotVersion)` and `awaitVersion(long, Duration)`.

**Defaults, exactly as the contract states them:** `pollInterval` 5 s (keeps answers inside the 10-second reuse bound, c29), `staleAfter` 60 s (matches the §7 promise), `startupTimeout` 30 s, `startupMode` `REQUIRE_SNAPSHOT`, no disk cache, no meter registry, `Clock.systemUTC()`, a fresh `HttpClient`. `serviceUrl` is mandatory. Expose `httpClient` and `clock` so tests can inject; document both as advanced.

**`build()` blocks until the first snapshot is loaded**, and applies the startup gate:
1. Fetch a full snapshot, retrying until `startupTimeout` elapses.
2. Run `ConformanceGate`. A failure is `EntitlementClientStartupException` — not a warning.
3. If the timeout elapses with no snapshot: under `ALLOW_DISK_CACHE` with a loadable cache, start from the cache, immediately `stale`, and keep polling; otherwise throw `EntitlementClientStartupException`.
4. Only then start the poller thread.

A cache loaded at startup is gated too, if it carries vectors — but `DiskCache` deliberately does not persist them, so in practice a cached replica starts ungated and the first successful sync gates it. That is the right trade: the alternative is refusing to start during an outage, which is the failure mode the cache exists to prevent. Say so in the javadoc.

**`check(account, capability, minSnapshotVersion)` throws rather than blocks.** `SnapshotBehindException` carries the replica's current version so the caller decides: retry, wait, or proceed on the older answer. Silently turning a microsecond lookup into a multi-second wait is a bad trade to make on a caller's behalf — which is why `awaitVersion` is opt-in and separate.

- [ ] **Step 1: Write `ReadYourWritesTest`**

```java
package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.error.SnapshotBehindException;
import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadYourWritesTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-09T14:05:00.000Z"), ZoneOffset.UTC);

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":1,"plans":1,"accounts":1,"overrides":0}}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"footer","version":48211,"recordCount":5}""");

    private AtomicReference<Replica> holder;
    private DefaultEntitlementClient client;

    @BeforeEach
    void setUp() {
        holder = new AtomicReference<>(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        client = DefaultEntitlementClient.forTesting(holder, CLOCK, ClientMetrics.NO_OP);
    }

    @Test
    void aVersionTheReplicaHasReachedIsAnsweredNormally() {
        var decision = client.check("acct_9931", "api.access", 48211L);

        assertThat(decision.snapshotVersion()).isEqualTo(48211L);
    }

    @Test
    void aVersionBelowTheReplicasIsAlsoFineBecauseTheReplicaIsAheadOfWhatWasAskedFor() {
        assertThat(client.check("acct_9931", "api.access", 48000L).snapshotVersion())
            .isEqualTo(48211L);
    }

    @Test
    void aVersionTheReplicaHasNotReachedThrowsRatherThanBlockingOnTheCallersBehalf() {
        assertThatThrownBy(() -> client.check("acct_9931", "api.access", 48999L))
            .isInstanceOf(SnapshotBehindException.class)
            .satisfies(e -> {
                assertThat(((SnapshotBehindException) e).requiredVersion()).isEqualTo(48999L);
                assertThat(((SnapshotBehindException) e).currentVersion()).isEqualTo(48211L);
            });
    }

    @Test
    void theVersionIsCheckedBeforeResolutionSoAnUnknownCapabilityDoesNotMaskAStaleReplica() {
        assertThatThrownBy(() -> client.check("acct_9931", "no.such.capability", 48999L))
            .isInstanceOf(SnapshotBehindException.class);
    }

    @Test
    void awaitVersionReturnsImmediatelyWhenTheReplicaIsAlreadyThere() {
        assertThat(client.awaitVersion(48211L, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void awaitVersionReturnsFalseRatherThanThrowingWhenTheTimeoutElapses() {
        assertThat(client.awaitVersion(48999L, Duration.ofMillis(50))).isFalse();
    }

    @Test
    void awaitVersionReturnsTrueOnceAnotherThreadSwapsInTheVersion() throws Exception {
        var swapper = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            holder.set(FullSnapshotReader.read(new ByteArrayInputStream(
                FEED.replace("48211", "48999").getBytes(StandardCharsets.UTF_8))));
        });
        swapper.start();

        assertThat(client.awaitVersion(48999L, Duration.ofSeconds(5))).isTrue();

        swapper.join();
    }
}
```

Implement `awaitVersion` by polling the `AtomicReference` on a short interval (25 ms) against a deadline computed from `System.nanoTime()` — not from the injected `Clock`, which tests freeze. Restore the interrupt flag and return `false` if interrupted.

- [ ] **Step 2: Write `EntitlementClientBuilderTest`**

Cover, each as its own test against a `StubFeedServer`:
- `build()` returns a client already serving, having fetched a full snapshot.
- `build()` without a `serviceUrl` throws `IllegalStateException` naming the missing setting.
- `build()` against an unreachable service under `REQUIRE_SNAPSHOT` throws `EntitlementClientStartupException` once `startupTimeout` elapses (use a 300 ms timeout so the test is fast).
- `build()` against an unreachable service under `ALLOW_DISK_CACHE` with a populated cache directory succeeds, and `health().stale()` is `true` immediately.
- `build()` against an unreachable service under `ALLOW_DISK_CACHE` with an *empty* cache directory still throws.
- `build()` on a snapshot whose vectors fail the gate throws `EntitlementClientStartupException` mentioning conformance.
- `build()` on a feed advertising an unknown `resolverContract` throws `EntitlementClientStartupException` mentioning `resolverContract`.
- Defaults are as documented: assert `pollInterval` 5 s and `staleAfter` 60 s are what the built client uses (expose them on a package-private accessor rather than reflecting).
- `close()` stops the poller: after `close()`, the stub's `versionCalls()` stops rising across a 200 ms window.

- [ ] **Step 3: Run both tests and confirm they fail**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest='ReadYourWritesTest+EntitlementClientBuilderTest'
```
Expected: compilation failure / `UnsupportedOperationException`.

- [ ] **Step 4: Write `StartupMode` and `EntitlementClientBuilder`**

```java
package com.solovis.entitlement.client;

/** What {@code build()} may do when no snapshot can be fetched before {@code startupTimeout}. */
public enum StartupMode {

    /**
     * Refuse to construct. The SDK will not guess: with no replica there is no last answer to carry
     * on with, and inventing one would either take away or grant.
     */
    REQUIRE_SNAPSHOT,

    /**
     * Start from the disk cache if one is readable, immediately {@code stale}, and keep polling.
     * This is what lets a customer's entitlements survive a restart during an outage.
     */
    ALLOW_DISK_CACHE
}
```

The builder holds the settings, validates on `build()`, constructs the `FeedHttpClient`, runs the startup gate described above, then constructs `DefaultEntitlementClient` and starts the poller. `meterRegistry(MeterRegistry)` stores the registry and, when non-null, constructs `MicrometerClientMetrics` — which is Task 14, so until then it stores the registry and still uses `ClientMetrics.NO_OP`, with a `// Task 14` marker.

- [ ] **Step 5: Implement `check(..., minSnapshotVersion)` and `awaitVersion`**

```java
    @Override
    public Decision check(String accountExternalId, String capabilityKey, long minSnapshotVersion) {
        var replica = holder.get();
        if (replica.version() < minSnapshotVersion) {
            // Throw rather than block: turning a microsecond lookup into a multi-second wait is not
            // a trade to make on a caller's behalf. awaitVersion() is the opt-in blocking form.
            throw new SnapshotBehindException(minSnapshotVersion, replica.version());
        }
        return check(accountExternalId, capabilityKey);
    }
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test
```
Expected: the whole module green.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/StartupMode.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClientBuilder.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/DefaultEntitlementClient.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/ReadYourWritesTest.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/EntitlementClientBuilderTest.java
git commit -m "feat(entitlement-client): read-your-writes across services, and a builder that refuses to guess at startup"
```

---

## Task 14: Metrics

**Files:**
- Create: `entitlement-client/src/main/java/com/solovis/entitlement/client/metrics/MicrometerClientMetrics.java`
- Modify: `entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClientBuilder.java`
- Test: `entitlement-client/src/test/java/com/solovis/entitlement/client/metrics/MicrometerClientMetricsTest.java`

**Interfaces:**
- Consumes: `ClientMetrics` (Task 10), `io.micrometer.core.instrument.MeterRegistry`.
- Produces: `MicrometerClientMetrics(MeterRegistry registry)` implementing `ClientMetrics`.

**The eight meters the contract specifies, with these exact names:**

| Name | Type | Notes |
|---|---|---|
| `entitlement.client.snapshot.version` | gauge | convergence across replicas |
| `entitlement.client.snapshot.age` | gauge, seconds | **alert above 60 s** — the §7 promise being breached |
| `entitlement.client.sync.failures` | counter | service reachability from this caller |
| `entitlement.client.decisions` | counter, tags `capability`, `allowed` | which capabilities actually gate anything |
| `entitlement.client.resync.full` | counter | a replica falling behind the delta horizon |
| `entitlement.client.resolver.contract` | gauge | **alert on disagreement across replicas** |
| `entitlement.client.conformance.failures` | counter | the drift gate firing |
| `entitlement.client.readthrough` | counter | unknown-account races |

**Register gauges once, in the constructor**, backed by `AtomicLong` fields — Micrometer gauges hold weak references, so a locally-scoped holder would be collected and the gauge would report NaN. The age gauge takes a `Supplier<Duration>`; register it lazily on the first `snapshotAge(...)` call and ignore subsequent calls.

**Cache the decision counter per `(capability, allowed)` pair** in a `ConcurrentHashMap`. `check()` is a hot path and a registry lookup per call is exactly the sort of overhead an in-process decision must not carry.

- [ ] **Step 1: Write the failing test**

Use Micrometer's `SimpleMeterRegistry` (in `micrometer-core`, already a dependency). Assert:
- each of the eight meters exists under its exact documented name after the corresponding call
- `entitlement.client.decisions` carries `capability` and `allowed` tags, and two different capabilities produce two distinct counters
- the age gauge reports seconds, reads *live* from the supplier (advance the supplier and re-read), and registering twice does not create a second gauge
- `snapshotVersion(48211)` then `snapshotVersion(48212)` leaves one gauge reading 48212
- `ClientMetrics.NO_OP` records nothing and throws nothing for every method — the default path must be free

- [ ] **Step 2: Run it and confirm it fails**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test -Dtest=MicrometerClientMetricsTest
```

- [ ] **Step 3: Write `MicrometerClientMetrics` and wire `meterRegistry(...)` in the builder**

Replace the `// Task 14` marker: when a registry was supplied, `metrics = new MicrometerClientMetrics(registry)`, otherwise `ClientMetrics.NO_OP`. After the first replica is loaded, call `metrics.snapshotAge(() -> Duration.between(holder.get().publishedAt(), clock.instant()))` so the gauge tracks the live replica.

Add a class-level note that this is the only file in the module referencing Micrometer, which is what keeps the dependency `optional` — a product that never calls `meterRegistry(...)` never loads this class.

- [ ] **Step 4: Run the whole module and confirm it is green**

```bash
cd management/backend && ./mvnw -pl entitlement-client -am test
```

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/metrics/MicrometerClientMetrics.java \
        management/backend/entitlement-client/src/main/java/com/solovis/entitlement/client/EntitlementClientBuilder.java \
        management/backend/entitlement-client/src/test/java/com/solovis/entitlement/client/metrics/MicrometerClientMetricsTest.java
git commit -m "feat(entitlement-client): optional Micrometer instrumentation behind a no-op seam"
```

---

## Task 15: The anti-drift test, and the docs

**Files:**
- Modify: `management/backend/entitlement-service/pom.xml`
- Create: `entitlement-service/src/test/java/com/solovis/entitlement/service/client/ClientAgainstRealFeedTest.java`
- Create: `management/backend/entitlement-client/README.md`
- Modify: `/mnt/nvme_fast/workspace/assessments/solovis/CLAUDE.md` (the "Current state" section only)

**Interfaces:**
- Consumes: everything.
- Produces: proof that the SDK parses what the service actually emits.

**Why this test exists and why it lives in the service module.** Every other test in this plan runs against `StubFeedServer` — a fixture written from reading the service's source. If the service's encoding drifts, the stub drifts with the developer's assumptions and every test stays green while production breaks. This test points a real `EntitlementClient` at a real running service. It is the only thing that catches the four documented doc/code drifts silently becoming five.

It goes in `entitlement-service` because `entitlement-service` may depend on `entitlement-client` at **test scope** without a reactor cycle, whereas the reverse would need the whole Spring app on the SDK's classpath.

- [ ] **Step 1: Add the test-scoped dependency**

In `entitlement-service/pom.xml`, inside `<dependencies>`:

```xml
		<dependency>
			<groupId>com.solovis.entitlement</groupId>
			<artifactId>entitlement-client</artifactId>
			<version>${project.version}</version>
			<scope>test</scope>
		</dependency>
```

Confirm the reactor still orders correctly — `entitlement-client` must build before `entitlement-service`:

```bash
cd management/backend && ./mvnw -q validate && ./mvnw help:evaluate -Dexpression=project.modules -q -DforceStdout
```

If Maven reports a cycle, stop and report it rather than working around it: it would mean `entitlement-client` has picked up a dependency on `entitlement-service`, which is a design violation.

- [ ] **Step 2: Write the test**

```java
package com.solovis.entitlement.service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.EntitlementClient;
import com.solovis.entitlement.client.StartupMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The anti-drift test: a real SDK against a real service over a real socket.
 *
 * <p>Every other SDK test runs against a hand-written stub, so every other SDK test would stay
 * green if the service's wire encoding drifted. This one would not. It is the reason the four
 * known doc/code drifts — {@code overrideKind} vs {@code kind}, omitted rather than null
 * {@code offValue}/{@code tiers}, sentence-shaped conformance ids, and the unimplemented
 * {@code conformance.changed} — cannot silently become five.
 *
 * <p>Fixtures are namespaced {@code t15-*} / {@code acct_t15_*} because {@code @SpringBootTest}
 * classes in this module share one SQLite file per JVM fork and this class must not be
 * {@code @Transactional} — it publishes into the shared snapshot holder, and a rollback would
 * leave a phantom behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientAgainstRealFeedTest {

    @LocalServerPort
    int port;

    // Seed, via the repositories or the admin API, a t15 capability of each value type (SWITCH,
    // QUANTITY with an off-value, TIER with at least two tiers), a t15 plan setting some of them,
    // one account on that plan, and one GRANT and one HOLD override.
    //
    // Then build a real client and assert, each as its own test:
    //
    //  1. build() succeeds against the live feed — which means the conformance gate passed against
    //     the service's real vectors. This alone is most of the value of the test.
    //  2. For every seeded capability, client.check(...) equals the service's own answer from
    //     GET /v1/accounts/{id}/capabilities/{key}: same allowed, same value, same snapshotVersion.
    //     Compare against the HTTP response, not against a hand-written expectation — the point is
    //     that the two engines agree, not that either matches a guess.
    //  3. client.checkAll(account) matches GET /v1/accounts/{id}/entitlements capability-for-
    //     capability, and covers exactly the non-retired capabilities.
    //  4. A retired t15 capability raises RetiredCapabilityException, not a denial.
    //  5. An unknown capability raises UnknownCapabilityException.
    //  6. client.explain(...) returns a trace whose override reason text matches what was seeded —
    //     proving the diagnostic path carries what the replica deliberately does not.
    //  7. The replica holds no reason text: assert every override reachable through
    //     client.capability(...) / the decision path has an empty reason, and — the sharper form —
    //     that the gunzipped body of GET /v1/snapshot/full does not contain the seeded reason
    //     string anywhere.
    //  8. After a mutation through the admin API, awaitVersion(newVersion, 10s) returns true and a
    //     subsequent check reflects the change. This is the end-to-end freshness budget (c28).
    //  9. check(account, capability, currentVersion + 1000) throws SnapshotBehindException.
}
```

**Write real bodies for all nine.** Follow the module's existing conventions: `@TestInstance(PER_CLASS)` with `@BeforeAll` seeding, no `@Transactional`, `t15-` namespaced fixtures, and do not claim the single default plan (`SchemaInvariantsTest` owns it). Read `DecisionControllerTest` first — its class javadoc explains exactly why these constraints exist.

- [ ] **Step 3: Run it**

```bash
cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=ClientAgainstRealFeedTest
```
Expected: 9 tests, 0 failures. **If any assertion fails, the SDK is wrong and the service is right** — the service's behaviour is pinned by 248 existing tests. Fix the SDK.

- [ ] **Step 4: Write the module README**

`management/backend/entitlement-client/README.md`, covering: what the SDK is and the one requirement it exists to satisfy (§11); a minimal usage example matching the contract's builder snippet; the nine caller obligations verbatim from `java-client-sdk.md`; the failure-behaviour table; the metrics table; and a pointer to the contract as source of truth. Keep it operator-readable — it is the document a consuming team reads before embedding this.

- [ ] **Step 5: Update `CLAUDE.md`**

In the **Current state** section only, move `entitlement-client` from "Not yet built" to built, and note that `entitlement-service` now carries a test-scoped dependency on it for `ClientAgainstRealFeedTest`. Change nothing else in that file.

- [ ] **Step 6: Run the whole reactor**

```bash
cd management/backend && ./mvnw test
```
Expected: BUILD SUCCESS across all three modules. Report the total test count.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-service/pom.xml \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/client/ClientAgainstRealFeedTest.java \
        management/backend/entitlement-client/README.md \
        CLAUDE.md
git commit -m "test(entitlement-client): prove the SDK against the real feed, and document the module"
```

---

## Appendix: contract coverage

Every section of `java-client-sdk.md`, and the task that discharges it. A reviewer can use this to check nothing was quietly dropped.

| Contract section | Task |
|---|---|
| `EntitlementClient` interface — all eight methods | 11 (decision path), 12 (`explain`), 13 (`minSnapshotVersion`, `awaitVersion`) |
| `Decision`, no trace field | 11 — core's `Decision` reused unchanged |
| `AccountEntitlements` | 11 |
| `Explanation` (service-fetched) | 12 |
| `ClientHealth` | 11 |
| Builder and every documented default | 13 |
| `UnknownAccountException` / `UnknownCapabilityException` / `RetiredCapabilityException` | 2 (types), 11 (thrown from the decision path) |
| `EntitlementClientStartupException` | 2, 13 |
| `SnapshotBehindException` | 2, 13 |
| `ExplanationUnavailableException` | 2, 12 |
| Unknown-account read-through, one bounded call + out-of-band poll | 12 |
| Conformance gate at startup and mid-life | 5, 10, 13 |
| Failure table — all eight rows | 10 (rows 1–6), 13 (rows 7–8) |
| Caller obligations | 11 (javadoc), 15 (README) |
| Metrics — all eight meters | 10 (seam), 14 (implementation) |
| Read-your-writes | 13 |
| Threading and lifecycle — one daemon, lock-free reads, `close()` | 10, 11, 13 |
| Feed format: version / full / delta, all nine change kinds | 4, 6, 8 |
| Truncated-snapshot discard, 410 full-resync, unknown format/contract | 4, 8, 10 |
| Disk cache | 7, 13 |

Two contract items are **deliberately not implemented**, and a reviewer should not flag them as gaps:
- **`conformance.changed`** — listed in `snapshot-feed.md`'s change table but absent from the service's `DeltaChange`. Task 6 treats it, correctly, as an unknown kind that halts syncing. Implementing a kind the service cannot emit would be untestable speculation.
- **Auth on the feed** — `contracts/README.md` defers it with v1's no-authentication decision. When sign-in lands, the builder grows a credential setting; nothing else in this design changes.
