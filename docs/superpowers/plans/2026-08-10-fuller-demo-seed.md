# Fuller Demo Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the four-capability, two-account demo seed with a ~60-account institutional dataset held as JSON, applied through the existing admin services across an authored eight-month timeline.

**Architecture:** The dataset becomes data (`demo-seed.json`) bound to records by Jackson and validated before anything is written. A `SeedApplier` walks it in day order, driving `CapabilityAdminService` / `PlanAdminService` / `AccountAdminService` / `OverrideAdminService` — the same services the admin API drives, so the seed can never declare data the validation rules reject. A `SeedClock` (installed only when seeding is enabled) is wound to each authored day so audit timestamps, `created_at` values and plan-assignment dates land where the story says instead of all at container boot. `DemoDataSeeder` becomes an `InitializingBean` gated on a `service_state` marker, so seeding finishes before the web connector opens and a half-finished seed fails the next boot loudly.

**Tech Stack:** Java 21, Spring Boot 4, Jackson (already present), JUnit 5 + AssertJ, SQLite via `JdbcClient`.

**Baseline:** post-002 `main`. 002 merged while this plan was being written, so override windows, the `AsAt` read path, `WindowBoundaryRoller` and the zoned clock are all present. Anything below that contradicts the code you are looking at means the branch has drifted again — read the code, not this file.

## Global Constraints

- Design source of truth: `docs/superpowers/specs/2026-08-10-fuller-demo-seed-design.md`.
- Reactor root is `management/backend`. Service tests need `-am`: `./mvnw -pl entitlement-service -am test`. Any `-Dtest=` run also needs `-Dsurefire.failIfNoSpecifiedTests=false`, because `-am` builds `entitlement-core` too and surefire fails the build when a named test matches nothing there.
- Package root `com.solovis.entitlement.service.seed`.
- No JPA. Timestamps are ISO-8601 UTC with milliseconds, always computed in Java — never `datetime('now')`.
- Never call `Instant.now()` directly; inject `java.time.Clock`. `NoDirectClockAccessTest` scans `src/main/java` and fails the build on `Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()`, `Clock.systemUTC()`, `Clock.systemDefaultZone()` and `new Date()` anywhere except `ClockConfig.java`. `System.nanoTime()` is not banned.
- The service `Clock` carries the service zone (`Clock.system(entitlementZone)`), which is what makes `LocalDate.now(clock)` the operator-facing date. Anything wrapping it must preserve `getZone()`.
- Override windows exist: `OverrideCreateRequest(String capability, String kind, ValueDto value, String reason, String startsOn, String expiresOn)`, dates as `YYYY-MM-DD` in the service zone, expiry inclusive. `WindowRules.validate(startsOn, expiresOn, LocalDate.now(clock))` refuses start-after-expiry, a start before today, and an expiry before today.
- `acct_1177` carries the three seeded standings and is `windows.spec.ts`'s scratch account. `acct_9931`'s resolved state is asserted by `operator-screens.spec.ts`. Neither may gain an override this plan did not already account for.
- `capability.area` is derived from the key, never supplied.
- Values on the wire use `ValueDto(String type, Boolean enabled, Long amount, Boolean unlimited, String tier, Integer ordinal)` — the seed file reuses this exact shape rather than inventing a second encoding.
- Capability keys must match `^[a-z0-9]+(\.[a-z0-9_-]+)+$`.
- These keys are load-bearing for the e2e suite and must not change: capabilities `api.access`, `reports.monthly`, `seats.count`, `support.tier`; tiers `community` / `standard` / `gold`; plans `free`, `pro`; accounts `acct_9931`, `acct_1177`; and `acct_9931`'s GRANT of `reports.monthly` = 200 with the reason `Renewal concession — Q3 pilot`.
- Never `git add -A` — stage only the files each task names. The working tree carries unrelated pending changes under `.specs/**`, `DECISIONS.md` and `README.md`.
- Branch: `feat/fuller-demo-seed`.

## File Structure

| File | Responsibility |
|---|---|
| `seed/SeedClock.java` (create) | A `Clock` with a settable instant; unwound and after release it delegates to the real clock |
| `seed/SeedClockConfig.java` (create) | Contributes `SeedClock` as the `Clock` bean when `entitlement.seed.enabled=true` |
| `time/ClockConfig.java` (modify) | Its `Clock` bean backs off when seeding is enabled |
| `seed/SeedState.java` (create) | The `seed.started` / `seed.completed` marker over `ServiceStateRepository` |
| `seed/SeedDataset.java` (create) | Record shapes for the JSON, plus `validate()` — every reference resolves before any write |
| `seed/SeedApplier.java` (create) | Walks the dataset in day order, winds the clock, drives the four admin services |
| `seed/DemoDataSeeder.java` (modify) | `InitializingBean` that loads the file, consults the marker, delegates to the applier, logs |
| `resources/seed/demo-seed.json` (create) | The authored dataset |
| `test/.../seed/SeedClockTest.java` (create) | Pure unit test of the clock's three states |
| `test/.../seed/SeedDatasetTest.java` (create) | Validation rules, and that the shipped file passes them |
| `test/.../seed/DemoDataSeederIT.java` (create) | Runs the whole seed in its own context and asserts the resulting world |
| `test/.../seed/DemoDataSeederTest.java` (delete) | Superseded by `DemoDataSeederIT` |
| `e2e/operator-screens.spec.ts` (modify) | One display-text assertion: `Free` → `Evaluation` |

---

### Task 1: SeedClock

**Files:**
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedClock.java`
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedClockConfig.java`
- Modify: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/time/ClockConfig.java`
- Test: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedClockTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `SeedClock extends Clock` with `void windTo(Instant)`, `void release()`, `boolean isWound()`. Bean name `clock`, contributed only when `entitlement.seed.enabled=true`.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.service.seed;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SeedClockTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private final SeedClock clock = new SeedClock(ClockConfig.base(EASTERN));

    @Test
    void unwoundItReadsTheRealClock() {
        assertThat(clock.isWound()).isFalse();
        assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void woundItReadsTheAuthoredInstant() {
        Instant authored = Instant.parse("2026-03-14T09:15:00Z");

        clock.windTo(authored);

        assertThat(clock.isWound()).isTrue();
        assertThat(clock.instant()).isEqualTo(authored);
    }

    @Test
    void releasedItReadsTheRealClockAgain() {
        clock.windTo(Instant.parse("2026-03-14T09:15:00Z"));

        clock.release();

        assertThat(clock.isWound()).isFalse();
        assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    /**
     * The zone is not cosmetic: it is what makes LocalDate.now(clock) the operator-facing date,
     * and therefore what every override window means. A SeedClock over UTC would compile and pass
     * every other test here while shifting window boundaries by hours.
     */
    @Test
    void itKeepsTheServiceZoneWoundAndUnwound() {
        assertThat(clock.getZone()).isEqualTo(EASTERN);

        clock.windTo(Instant.parse("2026-03-14T09:15:00Z"));

        assertThat(clock.getZone()).isEqualTo(EASTERN);
        assertThat(java.time.LocalDate.now(clock)).isEqualTo(java.time.LocalDate.of(2026, 3, 14));
    }

    private static org.assertj.core.api.InstantAssert.TemporalUnitOffset within(long amount,
            java.time.temporal.TemporalUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset((int) amount, unit);
    }
}
```

Note: replace the helper with a static import of `org.assertj.core.api.Assertions.within` if that resolves cleanly in this AssertJ version — run the test and use whichever compiles.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedClockTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `SeedClock` does not exist (compilation error).

- [ ] **Step 3: Write the implementation**

`SeedClock.java`:

```java
package com.solovis.entitlement.service.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The service's {@link Clock}, wound to an authored moment while the demo seed is being written and
 * released to real time the instant it finishes.
 *
 * <p>It exists because a demo is judged partly on its change history, and every audit row is stamped
 * from this bean (AuditRecorder). Applied at real time, the whole seed shares one timestamp and the
 * history screen advertises the dataset as generated. Wound, each write lands on the day the story
 * says it happened.
 *
 * <p>This is deliberately not a general-purpose test seam. It is contributed only when
 * {@code entitlement.seed.enabled=true}, it is wound only from {@link SeedApplier}, and the seeder
 * releases it in a {@code finally} before the web connector opens, so no request is ever served
 * against a wound clock.
 */
public final class SeedClock extends Clock {

    private final Clock delegate;
    private volatile Instant wound;

    public SeedClock(Clock delegate) {
        this.delegate = delegate;
    }

    public void windTo(Instant instant) {
        this.wound = instant;
    }

    public void release() {
        this.wound = null;
    }

    public boolean isWound() {
        return wound != null;
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        Instant current = wound;
        return current != null ? current : delegate.instant();
    }
}
```

Modify `time/ClockConfig.java` — extract the wall-clock construction so it has exactly one home, and
condition the bean. `ClockConfig.java` is the only file `NoDirectClockAccessTest` exempts, so the
`Clock.system(...)` call must stay inside it:

```java
	/**
	 * The one construction of a wall clock in the service. Extracted so {@code SeedClockConfig} can
	 * decorate it without repeating it — and without putting a second wall-clock call in a file
	 * {@code NoDirectClockAccessTest} does not exempt.
	 *
	 * <p>Ticks in whole milliseconds because every stored and published timestamp is ISO-8601 with
	 * millisecond precision (contracts/README.md).
	 */
	public static Clock base(ZoneId zone) {
		return Clock.tick(Clock.system(zone), Duration.ofMillis(1));
	}

	// Mirrored by SeedClockConfig, which decorates this with a windable Clock while the demo seed
	// runs. The two conditions are exhaustive and mutually exclusive: exactly one Clock bean exists.
	@Bean
	@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "false", matchIfMissing = true)
	public Clock clock(ZoneId entitlementZone) {
		return base(entitlementZone);
	}
```

with `import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;` added.

`SeedClockConfig.java`:

```java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.time.ClockConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * Replaces the ordinary {@code Clock} bean with {@link SeedClock} when seeding is enabled.
 *
 * <p>It decorates {@link ClockConfig#base(ZoneId)} rather than building its own clock. The zone is
 * load-bearing — it is what makes {@code LocalDate.now(clock)} the operator-facing date, and
 * therefore what every override window means — and {@code NoDirectClockAccessTest} exempts only
 * {@code ClockConfig.java} from reading the wall clock.
 */
@Configuration
@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "true")
public class SeedClockConfig {

    @Bean
    public SeedClock clock(ZoneId entitlementZone) {
        return new SeedClock(ClockConfig.base(entitlementZone));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedClockTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 5 tests.

- [ ] **Step 5: Prove the clock guard still holds**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=NoDirectClockAccessTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. This is the test that fails if `SeedClock` or `SeedClockConfig` reads the wall clock directly. If it fails, do not add an exemption — route through `ClockConfig.base`.

- [ ] **Step 6: Prove the wiring both ways**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test`
Expected: PASS. The suite runs with `entitlement.seed.enabled=false`, so it proves `ClockConfig`'s bean still applies and nothing lost its `Clock`.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedClock.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedClockConfig.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/time/ClockConfig.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedClockTest.java
git commit -m "feat(seed): a clock the demo seeder can wind, released before traffic"
```

---

### Task 2: The seed marker

**Files:**
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedState.java`
- Test: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedStateTest.java`

**Interfaces:**
- Consumes: `ServiceStateRepository.find(String): Optional<String>` and `put(String key, String value, String updatedAt)` from `service/store/`.
- Produces: `SeedState` with `Status status()` where `enum Status { ABSENT, STARTED, COMPLETED }`, `void markStarted(String fingerprint)`, `void markCompleted(String fingerprint)`, `Optional<String> startedFingerprint()`.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.store.ServiceStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SeedStateTest {

    @Autowired ServiceStateRepository repository;
    @Autowired java.time.Clock clock;

    @Test
    void anUnseededDatabaseReportsAbsent() {
        assertThat(new SeedState(repository, clock).status()).isEqualTo(SeedState.Status.ABSENT);
    }

    @Test
    void markingStartedThenCompletedMovesThroughBothStates() {
        var state = new SeedState(repository, clock);

        state.markStarted("v1:abc123");
        assertThat(state.status()).isEqualTo(SeedState.Status.STARTED);
        assertThat(state.startedFingerprint()).contains("v1:abc123");

        state.markCompleted("v1:abc123");
        assertThat(state.status()).isEqualTo(SeedState.Status.COMPLETED);
    }
}
```

Note: these two tests share the suite's one SQLite file, so `anUnseededDatabaseReportsAbsent` must run before the other writes a marker. JUnit runs methods in a deterministic but unspecified order — if it proves flaky, annotate the class `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` and add `@Order(1)` / `@Order(2)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedStateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `SeedState` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.time.Timestamps;

import java.time.Clock;
import java.util.Optional;

/**
 * Whether this database has been seeded, recorded rather than inferred.
 *
 * <p>The previous check asked "are there any plans?", which is one question standing in for a
 * hundred and sixty writes: a crash after the first plan left a permanently half-populated demo
 * that every later boot skipped in silence. Two markers make the half-finished case nameable, and
 * {@code DemoDataSeeder} refuses to start on it.
 *
 * <p>Lives in {@code service_state} because that table exists for facts the service remembers about
 * itself and is deliberately never pruned.
 */
public class SeedState {

    static final String STARTED = "seed.started";
    static final String COMPLETED = "seed.completed";

    public enum Status { ABSENT, STARTED, COMPLETED }

    private final ServiceStateRepository repository;
    private final Clock clock;

    public SeedState(ServiceStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Status status() {
        if (repository.find(COMPLETED).isPresent()) {
            return Status.COMPLETED;
        }
        return repository.find(STARTED).isPresent() ? Status.STARTED : Status.ABSENT;
    }

    public Optional<String> startedFingerprint() {
        return repository.find(STARTED);
    }

    public void markStarted(String fingerprint) {
        repository.put(STARTED, fingerprint, Timestamps.iso(clock.instant()));
    }

    public void markCompleted(String fingerprint) {
        repository.put(COMPLETED, fingerprint, Timestamps.iso(clock.instant()));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedStateTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedState.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedStateTest.java
git commit -m "feat(seed): record that a database was seeded instead of inferring it"
```

---

### Task 3: The dataset shape and its validation

**Files:**
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedDataset.java`
- Test: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedDatasetTest.java`
- Test fixture: `management/backend/entitlement-service/src/test/resources/seed/tiny-seed.json`

**Interfaces:**
- Consumes: `com.solovis.entitlement.service.dto.ValueDto`.
- Produces:
  - `record SeedDataset(int seedVersion, int timelineDays, List<Capability> capabilities, List<Plan> plans, List<Account> accounts, List<Event> events)`
  - `record Capability(int day, String key, String displayName, String description, String valueType, ValueDto defaultValue, ValueDto offValue, List<Tier> tiers)`
  - `record Tier(String tier, String displayName)`
  - `record Plan(int day, String key, String name, String description, boolean isDefault, Map<String, ValueDto> entitlements)`
  - `record Account(int day, String externalId, String name, String plan)`
  - `record Event(int day, String type, String account, String capability, String kind, ValueDto value, String reason, String ref, String plan, String key, Integer startsOnDay, Integer expiresOnDay)` — `type` is one of `override.create`, `override.remove`, `plan.reassign`, `capability.retire`. Window bounds are **timeline day numbers, not dates**, like every other date in the file; the applier turns them into service-zone dates. They may exceed `timelineDays`, which is how an override still running into the demo's future is expressed.
  - `void validate()` throwing `IllegalStateException` with a message naming the offending entry
  - `String fingerprint()` — `"v" + seedVersion + ":" + sha256 of the raw bytes`, supplied at load time via `SeedDataset.of(byte[], ObjectMapper)`

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.service.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedDatasetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeedDataset load(String json) {
        return SeedDataset.of(json.getBytes(StandardCharsets.UTF_8), MAPPER);
    }

    private static final String VALID = """
        {
          "seedVersion": 1,
          "timelineDays": 30,
          "capabilities": [
            {"day": 0, "key": "api.access", "displayName": "API access", "valueType": "SWITCH",
             "defaultValue": {"type": "SWITCH", "enabled": false}}
          ],
          "plans": [
            {"day": 1, "key": "free", "name": "Evaluation", "isDefault": true,
             "entitlements": {"api.access": {"type": "SWITCH", "enabled": true}}}
          ],
          "accounts": [{"day": 2, "externalId": "acct_1", "name": "One", "plan": "free"}],
          "events": [
            {"day": 3, "type": "override.create", "account": "acct_1", "capability": "api.access",
             "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Suspended", "ref": "h1"},
            {"day": 4, "type": "override.remove", "ref": "h1", "reason": "Cleared"}
          ]
        }
        """;

    @Test
    void aWellFormedDatasetValidates() {
        SeedDataset dataset = load(VALID);

        dataset.validate();

        assertThat(dataset.capabilities()).hasSize(1);
        assertThat(dataset.fingerprint()).startsWith("v1:");
    }

    @Test
    void theFingerprintChangesWithTheContent() {
        assertThat(load(VALID).fingerprint()).isNotEqualTo(load(VALID.replace("Evaluation", "Eval")).fingerprint());
    }

    @Test
    void aPlanEntitlementForAnUndeclaredCapabilityIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"api.access\": {\"type\": \"SWITCH\", \"enabled\": true}",
                "\"reports.monthly\": {\"type\": \"QUANTITY\", \"amount\": 5}")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reports.monthly");
    }

    @Test
    void anAccountOnAnUndeclaredPlanIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"plan\": \"free\"", "\"plan\": \"enterprise\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("enterprise");
    }

    @Test
    void removingAnOverrideThatWasNeverCreatedIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"ref\": \"h1\", \"reason\": \"Cleared\"",
                "\"ref\": \"nope\", \"reason\": \"Cleared\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void exactlyOneDefaultPlanIsRequired() {
        assertThatThrownBy(() -> load(VALID.replace("\"isDefault\": true", "\"isDefault\": false")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly one default plan");
    }

    @Test
    void aDayBeyondTheTimelineIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"day\": 4", "\"day\": 99")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("99");
    }

    @Test
    void aWindowStartingBeforeTheDayItIsWrittenIsRejected() {
        // WindowRules refuses a start before "today", and on a wound clock today is the authored
        // day. Catching it here turns a failed startup into a failed build.
        assertThatThrownBy(() -> load(VALID.replace("\"reason\": \"Suspended\"",
                "\"reason\": \"Suspended\", \"startsOnDay\": 1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("starts on day 1");
    }

    @Test
    void aWindowEndingBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"reason\": \"Suspended\"",
                "\"reason\": \"Suspended\", \"startsOnDay\": 10, \"expiresOnDay\": 5")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expires");
    }

    @Test
    void aWindowMayRunPastTheEndOfTheTimeline() {
        // A pending or still-running override is exactly this: authored inside the timeline,
        // expiring after the demo's present.
        load(VALID.replace("\"reason\": \"Suspended\"",
            "\"reason\": \"Suspended\", \"startsOnDay\": 20, \"expiresOnDay\": 400")).validate();
    }

    @Test
    void aWindowThatEndedBeforeItWasWrittenIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"reason\": \"Suspended\"",
                "\"reason\": \"Suspended\", \"expiresOnDay\": 1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expires on day 1");
    }

    @Test
    void referencingACapabilityBeforeItIsDeclaredIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("{\"day\": 0, \"key\": \"api.access\"",
                "{\"day\": 5, \"key\": \"api.access\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api.access");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedDatasetTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `SeedDataset` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.solovis.entitlement.service.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.service.dto.ValueDto;

import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authored demo dataset, checked whole before a single row is written.
 *
 * <p>Validation is not decoration. The applier drives the real admin services, so a dangling
 * reference would surface as a half-written database and a failed startup rather than a parse
 * error — and a demo that fails to boot on a typo in a name is a poor trade. Everything checkable
 * without touching the database is checked here first.
 *
 * <p>Days are relative to the start of the timeline, never absolute dates, so the demo is always
 * "the last eight months" whenever the container boots.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SeedDataset(
    int seedVersion,
    int timelineDays,
    List<Capability> capabilities,
    List<Plan> plans,
    List<Account> accounts,
    List<Event> events,
    String fingerprint
) {

    public record Tier(String tier, String displayName) {}

    public record Capability(int day, String key, String displayName, String description, String valueType,
        ValueDto defaultValue, ValueDto offValue, List<Tier> tiers) {}

    public record Plan(int day, String key, String name, String description, boolean isDefault,
        Map<String, ValueDto> entitlements) {}

    public record Account(int day, String externalId, String name, String plan) {}

    /**
     * Window bounds are timeline day numbers, not dates — like every other date in this file, so the
     * demo is always "the last eight months" whenever it boots. They may exceed {@code timelineDays},
     * which is how an override still running into the demo's present and future is expressed.
     */
    public record Event(int day, String type, String account, String capability, String kind, ValueDto value,
        String reason, String ref, String plan, String key, Integer startsOnDay, Integer expiresOnDay) {}

    public static final String OVERRIDE_CREATE = "override.create";
    public static final String OVERRIDE_REMOVE = "override.remove";
    public static final String PLAN_REASSIGN = "plan.reassign";
    public static final String CAPABILITY_RETIRE = "capability.retire";

    public static SeedDataset of(byte[] raw, ObjectMapper mapper) {
        try {
            SeedDataset parsed = mapper.readValue(raw, SeedDataset.class);
            return new SeedDataset(parsed.seedVersion(), parsed.timelineDays(), parsed.capabilities(),
                parsed.plans(), parsed.accounts(), parsed.events(), "v" + parsed.seedVersion() + ":" + sha256(raw));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("demo seed dataset is not readable", e);
        }
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM this runs on", e);
        }
    }

    public void validate() {
        Map<String, Integer> capabilityDays = new LinkedHashMap<>();
        for (Capability capability : capabilities) {
            require(capabilityDays.put(capability.key(), capability.day()) == null,
                "duplicate capability '" + capability.key() + "'");
            requireDay(capability.day(), "capability '" + capability.key() + "'");
        }

        Map<String, Integer> planDays = new LinkedHashMap<>();
        int defaults = 0;
        for (Plan plan : plans) {
            require(planDays.put(plan.key(), plan.day()) == null, "duplicate plan '" + plan.key() + "'");
            requireDay(plan.day(), "plan '" + plan.key() + "'");
            if (plan.isDefault()) {
                defaults++;
            }
            for (String key : plan.entitlements().keySet()) {
                requireDeclaredBy(capabilityDays, key, plan.day(), "plan '" + plan.key() + "' entitles");
            }
        }
        require(defaults == 1, "the dataset must declare exactly one default plan, found " + defaults);

        Map<String, Integer> accountDays = new LinkedHashMap<>();
        for (Account account : accounts) {
            require(accountDays.put(account.externalId(), account.day()) == null,
                "duplicate account '" + account.externalId() + "'");
            requireDay(account.day(), "account '" + account.externalId() + "'");
            requireDeclaredBy(planDays, account.plan(), account.day(), "account '" + account.externalId() + "' joins");
        }

        Set<String> liveRefs = new LinkedHashSet<>();
        int previousDay = 0;
        for (Event event : events) {
            requireDay(event.day(), "event '" + event.type() + "'");
            require(event.day() >= previousDay, "events must be in day order; '" + event.type()
                + "' on day " + event.day() + " follows day " + previousDay);
            previousDay = event.day();
            // The same three refusals WindowRules applies, checked against the authored day rather
            // than the real one — because the clock is wound to that day when the write happens.
            // Doing it here turns a failed startup into a failed build.
            if (event.startsOnDay() != null) {
                require(event.startsOnDay() >= event.day(), "override '" + event.ref() + "' starts on day "
                    + event.startsOnDay() + " but is written on day " + event.day()
                    + "; a window cannot begin before the moment it is saved");
            }
            if (event.expiresOnDay() != null) {
                require(event.expiresOnDay() >= event.day(), "override '" + event.ref() + "' expires on day "
                    + event.expiresOnDay() + " but is written on day " + event.day()
                    + "; a window already ended cannot be saved");
            }
            if (event.startsOnDay() != null && event.expiresOnDay() != null) {
                require(event.startsOnDay() <= event.expiresOnDay(), "override '" + event.ref() + "' starts on day "
                    + event.startsOnDay() + " and expires on day " + event.expiresOnDay() + ", which describes nothing");
            }

            switch (event.type()) {
                case OVERRIDE_CREATE -> {
                    requireDeclaredBy(accountDays, event.account(), event.day(), "override targets");
                    requireDeclaredBy(capabilityDays, event.capability(), event.day(), "override targets");
                    require(event.ref() != null && liveRefs.add(event.ref()),
                        "override ref '" + event.ref() + "' is missing or already in use");
                    require("GRANT".equals(event.kind()) || "HOLD".equals(event.kind()),
                        "override '" + event.ref() + "' must be GRANT or HOLD, was '" + event.kind() + "'");
                    require(event.reason() != null && !event.reason().isBlank(),
                        "override '" + event.ref() + "' needs a reason");
                }
                case OVERRIDE_REMOVE -> require(liveRefs.remove(event.ref()),
                    "override '" + event.ref() + "' is removed but was never created before day " + event.day());
                case PLAN_REASSIGN -> {
                    requireDeclaredBy(accountDays, event.account(), event.day(), "reassignment targets");
                    requireDeclaredBy(planDays, event.plan(), event.day(), "reassignment moves to");
                }
                case CAPABILITY_RETIRE -> requireDeclaredBy(capabilityDays, event.key(), event.day(), "retirement targets");
                default -> throw new IllegalStateException("unknown seed event type '" + event.type() + "'");
            }
        }
    }

    private void requireDay(int day, String what) {
        require(day >= 0 && day <= timelineDays,
            what + " is on day " + day + ", outside the timeline of " + timelineDays + " days");
    }

    private void requireDeclaredBy(Map<String, Integer> declared, String key, int day, String what) {
        Integer declaredOn = declared.get(key);
        require(declaredOn != null, what + " '" + key + "', which the dataset never declares");
        require(declaredOn <= day, what + " '" + key + "' on day " + day
            + ", but it is not declared until day " + declaredOn);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("demo seed dataset is invalid: " + message);
        }
    }
}
```

Note on `fingerprint`: it is a component of the record but never present in the JSON, so `of()` reconstructs the record with it. Jackson binds the file to a `SeedDataset` whose `fingerprint` is null; that intermediate value never escapes `of()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedDatasetTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedDataset.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedDatasetTest.java
git commit -m "feat(seed): a dataset shape that is checked whole before anything is written"
```

---

### Task 4: The authored dataset

**Files:**
- Create: `management/backend/entitlement-service/src/main/resources/seed/demo-seed.json`
- Modify: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedDatasetTest.java`

**Interfaces:**
- Consumes: `SeedDataset.of(byte[], ObjectMapper)` and `validate()` from Task 3.
- Produces: the classpath resource `seed/demo-seed.json`, `seedVersion` 1, `timelineDays` 240.

- [ ] **Step 1: Write the failing test**

Append to `SeedDatasetTest`:

```java
    @Test
    void theShippedDatasetIsValid() throws Exception {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/seed/demo-seed.json")) {
            assertThat(in).as("seed/demo-seed.json must be on the classpath").isNotNull();
            raw = in.readAllBytes();
        }

        SeedDataset dataset = SeedDataset.of(raw, MAPPER);
        dataset.validate();

        assertThat(dataset.capabilities()).hasSize(16);
        assertThat(dataset.plans()).hasSize(5);
        assertThat(dataset.accounts()).hasSizeGreaterThan(55);
        assertThat(dataset.accounts()).extracting(SeedDataset.Account::externalId)
            .contains("acct_9931", "acct_1177");
        assertThat(dataset.events()).extracting(SeedDataset.Event::kind).contains("HOLD");
    }

    @Test
    void theWindowsFlagshipCarriesAllFourStandings() {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/seed/demo-seed.json")) {
            raw = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        SeedDataset dataset = SeedDataset.of(raw, MAPPER);
        int timeline = dataset.timelineDays();

        var sterling = dataset.events().stream().filter(e -> "acct_2947".equals(e.account())).toList();

        // ENDED — the standing that could not be seeded before the clock could be wound.
        assertThat(sterling).anySatisfy(e -> {
            assertThat(e.expiresOnDay()).isNotNull();
            assertThat(e.expiresOnDay()).isLessThan(timeline);
        });
        // IN FORCE with an expiry still ahead of the demo's present.
        assertThat(sterling).anySatisfy(e -> {
            assertThat(e.startsOnDay()).isNotNull().matches(d -> d <= timeline);
            assertThat(e.expiresOnDay()).isNotNull().matches(d -> d > timeline);
        });
        // PENDING — has not begun by the time the demo is served.
        assertThat(sterling).anySatisfy(e -> assertThat(e.startsOnDay()).isNotNull().matches(d -> d > timeline));
        // REMOVED.
        assertThat(dataset.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(SeedDataset.OVERRIDE_REMOVE);
            assertThat(e.ref()).isEqualTo("sterling-removed");
        });
    }

    @Test
    void theThreeStandingsSeededByZeroZeroTwoSurviveOnTheirAccount() {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/seed/demo-seed.json")) {
            raw = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        SeedDataset dataset = SeedDataset.of(raw, MAPPER);

        // 002 put these on acct_1177 on purpose; screen 3's grouping renders them and windows.spec.ts
        // uses that account. They are carried across verbatim, reasons included.
        assertThat(dataset.events()).filteredOn(e -> "acct_1177".equals(e.account()))
            .extracting(SeedDataset.Event::reason)
            .contains("Trial seats through the end of today", "Reporting pilot agreed for next month",
                "Suspended pending investigation");
    }

    @Test
    void theShippedDatasetKeepsTheFixturesTheEndToEndSuiteLocatesBy() throws Exception {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/seed/demo-seed.json")) {
            raw = in.readAllBytes();
        }
        SeedDataset dataset = SeedDataset.of(raw, MAPPER);

        assertThat(dataset.capabilities()).extracting(SeedDataset.Capability::key)
            .contains("api.access", "reports.monthly", "seats.count", "support.tier");
        assertThat(dataset.plans()).extracting(SeedDataset.Plan::key).contains("free", "pro");
        assertThat(dataset.events())
            .filteredOn(e -> "acct_9931".equals(e.account()) && "reports.monthly".equals(e.capability()))
            .anySatisfy(e -> {
                assertThat(e.kind()).isEqualTo("GRANT");
                assertThat(e.value().amount()).isEqualTo(200L);
                assertThat(e.reason()).isEqualTo("Renewal concession — Q3 pilot");
            });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedDatasetTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — the resource is null.

- [ ] **Step 3: Author the dataset**

Create `src/main/resources/seed/demo-seed.json`. The catalogue below is complete; write it exactly.

```json
{
  "seedVersion": 1,
  "timelineDays": 240,
  "capabilities": [
    {"day": 0, "key": "portfolio.count", "displayName": "Portfolios", "description": "Portfolios an account may maintain.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 3}},
    {"day": 0, "key": "portfolio.lookthrough", "displayName": "Look-through analysis", "description": "Decompose fund holdings to underlying exposures.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 0, "key": "portfolio.private-markets", "displayName": "Private markets", "description": "Private capital cash-flow and commitment tracking.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 0, "key": "reports.monthly", "displayName": "Monthly reports", "description": "Reports an account may generate per month.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 0}},
    {"day": 0, "key": "reports.custom-templates", "displayName": "Custom report templates", "description": "Bespoke templates an account may keep.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 0}},
    {"day": 0, "key": "reports.scheduled-delivery", "displayName": "Scheduled delivery", "description": "Send reports on a schedule without an operator present.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 0, "key": "seats.count", "displayName": "Seats", "description": "Named users who may sign in.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 5}},
    {"day": 1, "key": "data.refresh-frequency", "displayName": "Data refresh", "description": "How often custodian data is refreshed.", "valueType": "TIER", "defaultValue": {"type": "TIER", "tier": "monthly"}, "offValue": {"type": "TIER", "tier": "monthly"}, "tiers": [{"tier": "monthly", "displayName": "Monthly"}, {"tier": "weekly", "displayName": "Weekly"}, {"tier": "daily", "displayName": "Daily"}, {"tier": "intraday", "displayName": "Intraday"}]},
    {"day": 1, "key": "data.custodian-feeds", "displayName": "Custodian feeds", "description": "Distinct custodian connections an account may hold.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 1}},
    {"day": 1, "key": "data.history-years", "displayName": "History retained", "description": "Years of performance history retained.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 3}},
    {"day": 1, "key": "api.access", "displayName": "API access", "description": "Programmatic access to the reporting API.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 1, "key": "api.export-bulk", "displayName": "Bulk export", "description": "Whole-portfolio extracts over the API.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 1, "key": "api.rate-limit", "displayName": "API requests per minute", "description": "Sustained request ceiling.", "valueType": "QUANTITY", "defaultValue": {"type": "QUANTITY", "amount": 60}},
    {"day": 2, "key": "support.tier", "displayName": "Support level", "description": "Service level an account is entitled to.", "valueType": "TIER", "defaultValue": {"type": "TIER", "tier": "community"}, "offValue": {"type": "TIER", "tier": "community"}, "tiers": [{"tier": "community", "displayName": "Community"}, {"tier": "standard", "displayName": "Standard"}, {"tier": "gold", "displayName": "Gold"}]},
    {"day": 2, "key": "support.named-analyst", "displayName": "Named analyst", "description": "A named analyst assigned to the account.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}},
    {"day": 2, "key": "reports.legacy-export", "displayName": "Legacy export (retired)", "description": "Superseded by scheduled delivery.", "valueType": "SWITCH", "defaultValue": {"type": "SWITCH", "enabled": false}}
  ],
  "plans": [
    {"day": 4, "key": "free", "name": "Evaluation", "description": "Sixty-day evaluation for prospective clients.", "isDefault": true, "entitlements": {"seats.count": {"type": "QUANTITY", "amount": 2}, "portfolio.count": {"type": "QUANTITY", "amount": 1}, "reports.monthly": {"type": "QUANTITY", "amount": 3}}},
    {"day": 5, "key": "core", "name": "Core", "description": "Single-office institutions with one custodian.", "isDefault": false, "entitlements": {"seats.count": {"type": "QUANTITY", "amount": 10}, "portfolio.count": {"type": "QUANTITY", "amount": 5}, "reports.monthly": {"type": "QUANTITY", "amount": 25}, "data.refresh-frequency": {"type": "TIER", "tier": "weekly"}, "support.tier": {"type": "TIER", "tier": "standard"}}},
    {"day": 6, "key": "pro", "name": "Professional", "description": "Multi-asset institutions with look-through reporting.", "isDefault": false, "entitlements": {"seats.count": {"type": "QUANTITY", "amount": 25}, "portfolio.count": {"type": "QUANTITY", "amount": 20}, "reports.monthly": {"type": "QUANTITY", "amount": 50}, "reports.custom-templates": {"type": "QUANTITY", "amount": 10}, "reports.scheduled-delivery": {"type": "SWITCH", "enabled": true}, "portfolio.lookthrough": {"type": "SWITCH", "enabled": true}, "data.refresh-frequency": {"type": "TIER", "tier": "daily"}, "data.custodian-feeds": {"type": "QUANTITY", "amount": 4}, "data.history-years": {"type": "QUANTITY", "amount": 7}, "api.access": {"type": "SWITCH", "enabled": true}, "api.rate-limit": {"type": "QUANTITY", "amount": 300}, "support.tier": {"type": "TIER", "tier": "standard"}}},
    {"day": 7, "key": "enterprise", "name": "Enterprise", "description": "Large asset owners with unlimited reporting.", "isDefault": false, "entitlements": {"seats.count": {"type": "QUANTITY", "amount": 150}, "portfolio.count": {"type": "QUANTITY", "amount": 100}, "reports.monthly": {"type": "QUANTITY", "unlimited": true}, "reports.custom-templates": {"type": "QUANTITY", "amount": 50}, "reports.scheduled-delivery": {"type": "SWITCH", "enabled": true}, "portfolio.lookthrough": {"type": "SWITCH", "enabled": true}, "portfolio.private-markets": {"type": "SWITCH", "enabled": true}, "data.refresh-frequency": {"type": "TIER", "tier": "intraday"}, "data.custodian-feeds": {"type": "QUANTITY", "unlimited": true}, "data.history-years": {"type": "QUANTITY", "amount": 20}, "api.access": {"type": "SWITCH", "enabled": true}, "api.export-bulk": {"type": "SWITCH", "enabled": true}, "api.rate-limit": {"type": "QUANTITY", "amount": 1200}, "support.tier": {"type": "TIER", "tier": "gold"}, "support.named-analyst": {"type": "SWITCH", "enabled": true}}},
    {"day": 8, "key": "ocio", "name": "OCIO Partner", "description": "Outsourced CIOs reporting across many underlying clients.", "isDefault": false, "entitlements": {"seats.count": {"type": "QUANTITY", "amount": 60}, "portfolio.count": {"type": "QUANTITY", "amount": 250}, "reports.monthly": {"type": "QUANTITY", "amount": 400}, "reports.custom-templates": {"type": "QUANTITY", "amount": 30}, "reports.scheduled-delivery": {"type": "SWITCH", "enabled": true}, "portfolio.lookthrough": {"type": "SWITCH", "enabled": true}, "portfolio.private-markets": {"type": "SWITCH", "enabled": true}, "data.refresh-frequency": {"type": "TIER", "tier": "daily"}, "data.custodian-feeds": {"type": "QUANTITY", "amount": 25}, "data.history-years": {"type": "QUANTITY", "amount": 12}, "api.access": {"type": "SWITCH", "enabled": true}, "api.export-bulk": {"type": "SWITCH", "enabled": true}, "api.rate-limit": {"type": "QUANTITY", "amount": 600}, "support.tier": {"type": "TIER", "tier": "gold"}}}
  ],
  "accounts": [
    {"day": 12, "externalId": "acct_9931", "name": "Northwind Capital", "plan": "pro"},
    {"day": 14, "externalId": "acct_1177", "name": "Cascadia Endowment", "plan": "free"},
    {"day": 20, "externalId": "acct_2947", "name": "Sterling Provident Fund", "plan": "core"},
    {"day": 16, "externalId": "acct_2043", "name": "Longview Pension Trust", "plan": "enterprise"},
    {"day": 18, "externalId": "acct_2210", "name": "Fairhaven Foundation", "plan": "core"},
    {"day": 21, "externalId": "acct_2384", "name": "Kestrel Family Office", "plan": "pro"},
    {"day": 24, "externalId": "acct_2506", "name": "Meridian OCIO Partners", "plan": "ocio"},
    {"day": 27, "externalId": "acct_2671", "name": "Ardsley Mutual Insurance", "plan": "pro"},
    {"day": 30, "externalId": "acct_2818", "name": "Blackthorn University Endowment", "plan": "enterprise"}
  ],
  "events": []
}
```

Then extend the file with the remaining accounts and the events, per steps 4 and 5.

- [ ] **Step 4: Add the account tail**

Append these to `accounts`, in this order. Every one is an ordinary account with no authored story; together they push the account list past its 50-row page.

| day | externalId | name | plan |
|---|---|---|---|
| 33 | acct_3011 | Harbour Point Retirement System | core |
| 35 | acct_3044 | Silverbrook Charitable Trust | free |
| 37 | acct_3078 | Clearwater Municipal Pension | core |
| 39 | acct_3102 | Alderman Life Assurance | pro |
| 41 | acct_3145 | Rosslyn Family Partners | free |
| 43 | acct_3177 | Thistledown Foundation | core |
| 46 | acct_3209 | Granite Bay Investment Office | pro |
| 48 | acct_3241 | Westmarch Teachers Fund | enterprise |
| 51 | acct_3288 | Ironwood Capital Advisers | core |
| 54 | acct_3310 | Belmont Hospital Trust | core |
| 57 | acct_3352 | Corvus Asset Management | pro |
| 60 | acct_3399 | Lakeshore Community Foundation | free |
| 63 | acct_3421 | Pemberton Group Pension | core |
| 66 | acct_3467 | Sable Ridge Family Office | pro |
| 69 | acct_3490 | Northgate Insurance Group | enterprise |
| 72 | acct_3522 | Wrenfield Endowment | core |
| 75 | acct_3558 | Ashcombe Trustees | free |
| 78 | acct_3601 | Dunmore Public Employees | enterprise |
| 81 | acct_3634 | Halloway Foundation | core |
| 84 | acct_3672 | Tessellate OCIO | ocio |
| 87 | acct_3705 | Brightwater Reinsurance | pro |
| 90 | acct_3748 | Marlowe College Fund | core |
| 93 | acct_3771 | Ellerby Family Holdings | free |
| 96 | acct_3804 | Stonegate Retirement Board | enterprise |
| 99 | acct_3839 | Verdant Health Endowment | core |
| 103 | acct_3877 | Kingsmere Capital | pro |
| 107 | acct_3910 | Auburn Vale Foundation | free |
| 111 | acct_3952 | Redmond Fire & Police Pension | core |
| 115 | acct_3988 | Lyndhurst Assurance | pro |
| 119 | acct_4015 | Cobblestone Family Office | free |
| 123 | acct_4048 | Perrin Institute | core |
| 127 | acct_4082 | Saltmarsh OCIO Group | ocio |
| 131 | acct_4119 | Hartcliffe Pension Scheme | enterprise |
| 135 | acct_4153 | Ravenswood Trust | core |
| 139 | acct_4186 | Oakhurst Charitable Fund | free |
| 143 | acct_4210 | Sinclair Mutual | pro |
| 147 | acct_4244 | Westbourne Endowment | core |
| 151 | acct_4279 | Fenwick Family Capital | free |
| 155 | acct_4312 | Draycott Municipal Fund | core |
| 159 | acct_4350 | Ambrose Life | pro |
| 163 | acct_4383 | Foxglove Foundation | free |
| 167 | acct_4416 | Tannery Row Investment Trust | core |
| 171 | acct_4459 | Whitfield Pension Board | enterprise |
| 175 | acct_4482 | Calder & Vance Advisers | pro |
| 179 | acct_4515 | Brambleton Endowment | core |
| 183 | acct_4548 | Highfield Family Office | free |
| 187 | acct_4581 | Norbury Insurance Mutual | pro |
| 191 | acct_4614 | Sedgewick Foundation | core |
| 195 | acct_4657 | Ridgemont OCIO | ocio |
| 199 | acct_4680 | Ivybridge Retirement Fund | enterprise |
| 203 | acct_4713 | Callender Trust | free |
| 207 | acct_4746 | Waverley Asset Partners | pro |

Each row becomes `{"day": <day>, "externalId": "<id>", "name": "<name>", "plan": "<plan>"}`.

- [ ] **Step 5: Author the events**

Replace `"events": []` with the list below. Each flagship story puts one behaviour of the resolution rule on a screen; the reason text is what an operator reads, so write it as an operator would.

```json
  "events": [
    {"day": 31, "type": "override.create", "account": "acct_9931", "capability": "reports.monthly", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 200}, "reason": "Renewal concession — Q3 pilot", "ref": "northwind-reports"},
    {"day": 44, "type": "override.create", "account": "acct_2384", "capability": "api.access", "kind": "GRANT", "value": {"type": "SWITCH", "enabled": true}, "reason": "Integration pilot with their custodian", "ref": "kestrel-api-grant"},
    {"day": 58, "type": "override.create", "account": "acct_2384", "capability": "api.access", "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Suspended pending security review", "ref": "kestrel-api-hold"},
    {"day": 60, "type": "override.create", "account": "acct_2947", "capability": "reports.monthly", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 150}, "reason": "Quarter-end reporting surge — agreed through Q2", "ref": "sterling-ended", "startsOnDay": 60, "expiresOnDay": 120},
    {"day": 64, "type": "override.create", "account": "acct_2210", "capability": "seats.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 15}, "reason": "Board onboarding — five extra trustees", "ref": "fairhaven-seats-a"},
    {"day": 71, "type": "override.create", "account": "acct_2210", "capability": "seats.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 22}, "reason": "Investment committee expansion agreed with sales", "ref": "fairhaven-seats-b"},
    {"day": 86, "type": "plan.reassign", "account": "acct_3011", "plan": "pro", "reason": "Upgraded after the evaluation period"},
    {"day": 92, "type": "override.create", "account": "acct_2671", "capability": "data.refresh-frequency", "kind": "HOLD", "value": {"type": "TIER", "tier": "weekly"}, "reason": "Custodian feed unstable — capped until resolved", "ref": "ardsley-refresh-hold"},
    {"day": 100, "type": "override.create", "account": "acct_2947", "capability": "support.named-analyst", "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Named analyst reassigned during the merger", "ref": "sterling-removed"},
    {"day": 105, "type": "override.create", "account": "acct_2506", "capability": "portfolio.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 400}, "reason": "Onboarding twelve underlying clients this quarter", "ref": "meridian-portfolios"},
    {"day": 112, "type": "override.remove", "ref": "sterling-removed", "reason": "Cover arranged with the standard desk"},
    {"day": 118, "type": "capability.retire", "key": "reports.legacy-export"},
    {"day": 126, "type": "override.remove", "ref": "ardsley-refresh-hold", "reason": "Custodian feed stable for thirty days"},
    {"day": 134, "type": "override.create", "account": "acct_2818", "capability": "support.named-analyst", "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Analyst on leave — cover arranged through the standard desk", "ref": "blackthorn-analyst-hold"},
    {"day": 149, "type": "plan.reassign", "account": "acct_3011", "plan": "enterprise", "reason": "Second upgrade following the merger"},
    {"day": 162, "type": "override.create", "account": "acct_2043", "capability": "api.rate-limit", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 5000}, "reason": "Nightly extract window agreed with engineering", "ref": "longview-rate"},
    {"day": 178, "type": "override.remove", "ref": "blackthorn-analyst-hold", "reason": "Named analyst returned"},
    {"day": 190, "type": "override.create", "account": "acct_3241", "capability": "reports.custom-templates", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 80}, "reason": "Regulatory reporting programme", "ref": "westmarch-templates"},
    {"day": 200, "type": "override.create", "account": "acct_2947", "capability": "seats.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 40}, "reason": "Secondment cover through the transition", "ref": "sterling-in-force", "startsOnDay": 200, "expiresOnDay": 400},
    {"day": 205, "type": "override.create", "account": "acct_2671", "capability": "seats.count", "kind": "HOLD", "value": {"type": "QUANTITY", "amount": 12}, "reason": "Licence audit — capped pending true-up", "ref": "ardsley-seats-hold"},
    {"day": 221, "type": "override.create", "account": "acct_3705", "capability": "portfolio.private-markets", "kind": "GRANT", "value": {"type": "SWITCH", "enabled": true}, "reason": "Private credit sleeve added mid-year", "ref": "brightwater-privates"},
    {"day": 226, "type": "override.create", "account": "acct_1177", "capability": "api.access", "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Suspended pending investigation", "ref": "cascadia-hold"},
    {"day": 230, "type": "override.remove", "ref": "cascadia-hold", "reason": "Investigation closed, access restored"},
    {"day": 232, "type": "override.create", "account": "acct_2947", "capability": "portfolio.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 40}, "reason": "Private markets rollout scheduled for next quarter", "ref": "sterling-pending", "startsOnDay": 300, "expiresOnDay": 400},
    {"day": 236, "type": "override.create", "account": "acct_3910", "capability": "reports.monthly", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 12}, "reason": "Extended evaluation while procurement completes", "ref": "auburn-reports"},
    {"day": 238, "type": "override.create", "account": "acct_1177", "capability": "seats.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 25}, "reason": "Trial seats through the end of today", "ref": "cascadia-trial-seats", "expiresOnDay": 240},
    {"day": 238, "type": "override.create", "account": "acct_1177", "capability": "reports.monthly", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 500}, "reason": "Reporting pilot agreed for next month", "ref": "cascadia-pilot", "startsOnDay": 270, "expiresOnDay": 360},
    {"day": 240, "type": "override.create", "account": "acct_4746", "capability": "seats.count", "kind": "GRANT", "value": {"type": "QUANTITY", "amount": 40}, "reason": "Team expansion effective today", "ref": "waverley-seats"}
  ]
```

Note the two stories this puts on screen that nothing in the project currently shows: `acct_2384` carries a GRANT and a HOLD on `api.access`, so the account view demonstrates a restriction defeating a concession; `acct_2210` carries two competing GRANTs on `seats.count`, so the explanation names a loser as well as a winner.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=SeedDatasetTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 16 tests. If validation rejects the file, the message names the offending entry — fix the JSON, not the validator.

- [ ] **Step 7: Commit**

```bash
git add management/backend/entitlement-service/src/main/resources/seed/demo-seed.json \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/SeedDatasetTest.java
git commit -m "feat(seed): an institutional dataset, authored as data"
```

---

### Task 5: The applier

**Files:**
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedApplier.java`

**Interfaces:**
- Consumes: `SeedClock` (Task 1), `SeedDataset` (Task 3), and the four admin services with these exact signatures:
  - `CapabilityAdminService.create(CapabilityCreateRequest): CapabilityDescriptorDto`, `retire(String key): CapabilityRetireResponseDto`
  - `PlanAdminService.create(PlanCreateRequest): PlanSummaryDto`, `designateDefault(String key)`, `preview(String key, PlanEntitlementEditRequest): PlanPreviewResponseDto`, `apply(String key, PlanEntitlementEditRequest): PlanApplyResponseDto`
  - `AccountAdminService.create(AccountCreateRequest): AccountSummaryDto`, `reassignPlan(String external, PlanReassignRequest)`
  - `OverrideAdminService.create(String external, OverrideCreateRequest): OverrideMutationResponseDto`, `delete(String external, String overrideRef, String removeReason)`
  - `CapabilityCreateRequest(String key, String displayName, String description, String valueType, ValueDto defaultValue, ValueDto offValue, List<TierRequest> tiers)`, `TierRequest(String tier, String displayName)`
  - `PlanCreateRequest(String key, String name, String description)`
  - `PlanEntitlementEditRequest(Map<String, ValueDto> set, List<String> unset, String previewAccount, String previewToken)`
  - `AccountCreateRequest(String externalId, String name)`
  - `PlanReassignRequest(String planKey, String source, String actor, String reason)`
  - `OverrideCreateRequest(String capability, String kind, ValueDto value, String reason, String startsOn, String expiresOn)` — dates are `YYYY-MM-DD` in the service zone, expiry inclusive; a four-argument convenience constructor exists for the no-window case
- Produces: `SeedApplier.apply(SeedDataset): Summary`, `record Summary(int capabilities, int plans, int accounts, int overrides, int writes, Instant firstEvent, Instant lastEvent)`.

- [ ] **Step 1: Write the implementation**

There is no separate unit test for this task: the applier's only meaningful assertion is the world it produces, which Task 7's integration test makes. Write it, then prove it there.

```java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.audit.AuditSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks an authored dataset in day order, driving the same admin services the admin API drives.
 *
 * <p>Going through the services rather than the repositories is the property this seeder is built
 * on: it cannot declare data the validation rules reject, every write records a real audit event,
 * and every write publishes a snapshot exactly as an operator's would.
 *
 * <p>The clock is wound to each authored day before the write, so the demo has a history instead of
 * a boot second. It is released by {@link DemoDataSeeder}, which owns the {@code finally}.
 */
public class SeedApplier {

    /** The last authored moment must land before now, so the served snapshot is never stale to a replica. */
    private static final Duration LATEST_MARGIN = Duration.ofMinutes(1);

    public record Summary(int capabilities, int plans, int accounts, int overrides, int writes,
        Instant firstEvent, Instant lastEvent) {}

    private final CapabilityAdminService capabilityService;
    private final PlanAdminService planService;
    private final AccountAdminService accountService;
    private final OverrideAdminService overrideService;
    private final AuditSource auditSource;
    private final SeedClock clock;

    public SeedApplier(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            SeedClock clock) {
        this.capabilityService = capabilityService;
        this.planService = planService;
        this.accountService = accountService;
        this.overrideService = overrideService;
        this.auditSource = auditSource;
        this.clock = clock;
    }

    public Summary apply(SeedDataset dataset) {
        Instant realNow = clock.instant();
        Instant latest = realNow.minus(LATEST_MARGIN);
        Instant start = realNow.minus(Duration.ofDays(dataset.timelineDays())).truncatedTo(ChronoUnit.DAYS);

        var overrideIds = new HashMap<String, String>();
        var accountByOverrideRef = new HashMap<String, String>();
        var writes = new int[]{0};
        var moments = new ArrayList<Instant>();

        auditSource.runAs("SEED", () -> {
            for (var capability : dataset.capabilities()) {
                at(start, capability.day(), writes[0]++, latest, moments);
                capabilityService.create(new CapabilityCreateRequest(capability.key(), capability.displayName(),
                    capability.description(), capability.valueType(), capability.defaultValue(), capability.offValue(),
                    capability.tiers() == null ? List.of() : capability.tiers().stream()
                        .map(t -> new CapabilityCreateRequest.TierRequest(t.tier(), t.displayName())).toList()));
            }

            for (var plan : dataset.plans()) {
                at(start, plan.day(), writes[0]++, latest, moments);
                planService.create(new PlanCreateRequest(plan.key(), plan.name(), plan.description()));
                if (plan.isDefault()) {
                    at(start, plan.day(), writes[0]++, latest, moments);
                    planService.designateDefault(plan.key());
                }
                if (!plan.entitlements().isEmpty()) {
                    at(start, plan.day(), writes[0]++, latest, moments);
                    var edit = new PlanEntitlementEditRequest(plan.entitlements(), List.of(), null, null);
                    // preview then apply, exactly as the console does: apply recomputes the token and
                    // refuses anything that was previewed against a different snapshot version.
                    String token = planService.preview(plan.key(), edit).previewToken();
                    planService.apply(plan.key(),
                        new PlanEntitlementEditRequest(plan.entitlements(), List.of(), null, token));
                }
            }

            for (var account : dataset.accounts()) {
                at(start, account.day(), writes[0]++, latest, moments);
                accountService.create(new AccountCreateRequest(account.externalId(), account.name()));
                // create always assigns the designated default plan, so anything else is a reassignment —
                // which is also how a real account reaches a paid plan, and shows up in the history as one.
                if (!account.plan().equals(defaultPlanKey(dataset))) {
                    at(start, account.day(), writes[0]++, latest, moments);
                    accountService.reassignPlan(account.externalId(),
                        new PlanReassignRequest(account.plan(), "PERSON", "dev-operator", "Initial plan on signup"));
                }
            }

            for (var event : dataset.events()) {
                at(start, event.day(), writes[0]++, latest, moments);
                switch (event.type()) {
                    case SeedDataset.OVERRIDE_CREATE -> {
                        // The clock is already wound to the authored day, so "today" here *is* that
                        // day — which is also the date WindowRules will validate against. Deriving
                        // the window from it rather than from the timeline start keeps the two in
                        // step whatever the service zone does to a UTC instant.
                        LocalDate authoredToday = LocalDate.now(clock);
                        var created = overrideService.create(event.account(), new OverrideCreateRequest(
                            event.capability(), event.kind(), event.value(), event.reason(),
                            windowDate(authoredToday, event.day(), event.startsOnDay()),
                            windowDate(authoredToday, event.day(), event.expiresOnDay())));
                        overrideIds.put(event.ref(), created.overrideId());
                        accountByOverrideRef.put(event.ref(), event.account());
                    }
                    case SeedDataset.OVERRIDE_REMOVE -> overrideService.delete(accountByOverrideRef.get(event.ref()),
                        overrideIds.get(event.ref()), event.reason());
                    case SeedDataset.PLAN_REASSIGN -> accountService.reassignPlan(event.account(),
                        new PlanReassignRequest(event.plan(), "PERSON", "dev-operator", event.reason()));
                    case SeedDataset.CAPABILITY_RETIRE -> capabilityService.retire(event.key());
                    default -> throw new IllegalStateException("unknown seed event type '" + event.type() + "'");
                }
            }
        });

        int overrides = (int) dataset.events().stream()
            .filter(e -> SeedDataset.OVERRIDE_CREATE.equals(e.type())).count();
        return new Summary(dataset.capabilities().size(), dataset.plans().size(), dataset.accounts().size(),
            overrides, writes[0], moments.isEmpty() ? realNow : moments.get(0),
            moments.isEmpty() ? realNow : moments.get(moments.size() - 1));
    }

    /**
     * A timeline day as the service-zone date the admin API expects, relative to the day the
     * override is written. Null stays null — an override with no window, still the ordinary case.
     */
    private static String windowDate(LocalDate authoredToday, int authoredDay, Integer windowDay) {
        return windowDay == null ? null : authoredToday.plusDays((long) windowDay - authoredDay).toString();
    }

    private static String defaultPlanKey(SeedDataset dataset) {
        return dataset.plans().stream().filter(SeedDataset.Plan::isDefault).findFirst()
            .orElseThrow(() -> new IllegalStateException("validated datasets always have a default plan"))
            .key();
    }

    /**
     * Winds the clock to the authored day, spreading writes through a working day so a busy day does
     * not read as one instant. Clamped so nothing is ever stamped in the future or later than the
     * margin before now.
     */
    private void at(Instant start, int day, int sequence, Instant latest, List<Instant> moments) {
        Instant moment = start.plus(Duration.ofDays(day))
            .plus(Duration.ofHours(9))
            .plus(Duration.ofMinutes((17L * sequence) % 480));
        if (moment.isAfter(latest)) {
            moment = latest;
        }
        clock.windTo(moment);
        moments.add(moment);
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test-compile`
Expected: BUILD SUCCESS. If a signature differs, fix the call — never change an admin service to suit the seeder.

- [ ] **Step 3: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/SeedApplier.java
git commit -m "feat(seed): apply the dataset in day order through the admin services"
```

---

### Task 6: The seeder itself

**Files:**
- Modify: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/DemoDataSeeder.java`
- Delete: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/DemoDataSeederTest.java`

**Interfaces:**
- Consumes: `SeedClock`, `SeedState`, `SeedDataset`, `SeedApplier`, `ServiceStateRepository`, `SnapshotStartup` (bean name `snapshotStartup`).
- Produces: a bean that seeds during context refresh, before the connector opens.

- [ ] **Step 1: Replace the implementation**

```java
package com.solovis.entitlement.service.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.audit.AuditSource;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * Writes the demo dataset into an empty database, before the service accepts traffic.
 *
 * <p>An {@link InitializingBean} rather than an {@code ApplicationRunner} for the reason
 * {@code SnapshotStartup} gives: Boot starts the web connector during context refresh, before any
 * runner fires, so a runner would leave a window where the console is reachable and half populated.
 * {@code @DependsOn} is required as well as ordering — {@code SnapshotPublisher} mutates from the
 * current snapshot, so the snapshot must exist before the first write.
 *
 * <p>Whether a database has been seeded is recorded, not inferred. The previous check asked whether
 * any plan existed, which is one question standing in for the whole sequence: a crash partway
 * through left a permanently half-populated demo that every later boot skipped in silence. A
 * started-but-not-completed marker now fails startup instead, and for a demo database the recovery
 * is to delete the file.
 */
@Component
@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "true")
@DependsOn("snapshotStartup")
public class DemoDataSeeder implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String RESOURCE = "seed/demo-seed.json";

    private final SeedApplier applier;
    private final SeedState seedState;
    private final SeedClock clock;
    private final ObjectMapper mapper;

    public DemoDataSeeder(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            ServiceStateRepository serviceStateRepository, SeedClock clock, ObjectMapper mapper) {
        this.applier = new SeedApplier(capabilityService, planService, accountService, overrideService,
            auditSource, clock);
        this.seedState = new SeedState(serviceStateRepository, clock);
        this.clock = clock;
        this.mapper = mapper;
    }

    @Override
    public void afterPropertiesSet() {
        SeedState.Status status = seedState.status();
        if (status == SeedState.Status.COMPLETED) {
            log.info("Demo seed: already present, nothing to do.");
            return;
        }
        if (status == SeedState.Status.STARTED) {
            throw new IllegalStateException("Demo seed: a previous attempt (" + seedState.startedFingerprint()
                .orElse("unknown") + ") started but never completed, so this database is half populated. "
                + "Refusing to start rather than serve an incomplete demo — delete the database file and restart.");
        }

        SeedDataset dataset = load();
        dataset.validate();

        long began = System.nanoTime();
        try {
            seedState.markStarted(dataset.fingerprint());
            SeedApplier.Summary summary = applier.apply(dataset);
            seedState.markCompleted(dataset.fingerprint());
            log.info("Demo seed: {} capabilities, {} plans, {} accounts, {} overrides — {} writes across {} days, "
                    + "in {} ms.", summary.capabilities(), summary.plans(), summary.accounts(), summary.overrides(),
                summary.writes(), Duration.between(summary.firstEvent(), summary.lastEvent()).toDays(),
                Duration.ofNanos(System.nanoTime() - began).toMillis());
        } finally {
            // Released before the connector opens: no request is ever served against a wound clock.
            clock.release();
        }
    }

    private SeedDataset load() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return SeedDataset.of(in.readAllBytes(), mapper);
        } catch (IOException e) {
            throw new UncheckedIOException("Demo seed: " + RESOURCE + " is not readable", e);
        }
    }
}
```

- [ ] **Step 2: Delete the superseded test**

```bash
git rm management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/DemoDataSeederTest.java
```

It tested only the two skip branches, and its "seeding disabled" branch no longer exists — the bean is conditional. Task 7 replaces it with a test of the path that actually writes.

- [ ] **Step 3: Run the whole backend suite**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test`
Expected: PASS. Seeding is off in the test profile, so `DemoDataSeeder` and `SeedClock` are not even constructed — this run proves the conditional wiring did not break the ordinary context.

- [ ] **Step 4: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/seed/DemoDataSeeder.java
git commit -m "feat(seed): seed before the port opens, on a recorded marker"
```

---

### Task 7: Prove the seed against a real service

**Files:**
- Create: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/DemoDataSeederIT.java`

**Interfaces:**
- Consumes: everything above, plus `SnapshotHolder.current()`, `com.solovis.entitlement.core.engine.Resolver.explain(Snapshot, String account, CapabilityKey, Instant)`, `AuditEventRepository`.

- [ ] **Step 1: Write the test**

This is the first test of the seeder's happy path. It needs its own context (seeding on, its own database file), which is why it is a separate class with its own `@SpringBootTest` properties.

```java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole demo seed against a real service. Until this existed the seeder's writing path was
 * proved only by the end-to-end suite and by the deployment itself.
 *
 * <p>Its own context and its own database file: the shared test context runs with seeding off, and
 * the seed must land in an empty database to run at all.
 */
@SpringBootTest(properties = {
    "entitlement.seed.enabled=true",
    "entitlement.database.path=${java.io.tmpdir}/entitlement-seed-it-${random.uuid}.db"
})
class DemoDataSeederIT {

    @Autowired SnapshotHolder snapshotHolder;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired PlanAdminService planService;
    @Autowired AccountAdminService accountService;
    @Autowired AuditEventRepository auditEvents;
    @Autowired Clock clock;

    @Test
    void itSeedsTheWholeCatalogue() {
        assertThat(capabilityService.list(null, "ACTIVE", null)).hasSize(15);
        assertThat(capabilityService.list(null, "RETIRED", null)).hasSize(1);
        assertThat(planService.list()).hasSize(5);
    }

    @Test
    void theEndToEndFixturesResolveExactlyAsTheSuiteExpects() {
        var explanation = com.solovis.entitlement.core.engine.Resolver.explain(
            snapshotHolder.current(), "acct_9931", new CapabilityKey("reports.monthly"), clock.instant());

        assertThat(explanation.value().toString()).contains("200");
    }

    @Test
    void aHoldDefeatsAGrant() {
        var explanation = com.solovis.entitlement.core.engine.Resolver.explain(
            snapshotHolder.current(), "acct_2384", new CapabilityKey("api.access"), clock.instant());

        assertThat(explanation.allowed()).isFalse();
    }

    @Test
    void anEnterpriseAccountResolvesUnlimited() {
        var explanation = com.solovis.entitlement.core.engine.Resolver.explain(
            snapshotHolder.current(), "acct_2043", new CapabilityKey("reports.monthly"), clock.instant());

        assertThat(explanation.value().toString().toLowerCase()).contains("unlimited");
    }

    @Test
    void theHistorySpansMonthsAndEndsAtThePresent() {
        var events = auditEvents.find(new AuditEventFilter(null, null, null, null, null, null, null, 1000));

        assertThat(events).hasSizeGreaterThan(100);
        var timestamps = events.stream().map(e -> Instant.parse(e.occurredAt())).sorted().toList();
        Instant oldest = timestamps.get(0);
        Instant newest = timestamps.get(timestamps.size() - 1);

        assertThat(Duration.between(oldest, newest).toDays())
            .as("the seeded history must span the authored timeline, not one boot second")
            .isGreaterThan(180);
        assertThat(Duration.between(newest, Instant.now()).toMinutes())
            .as("the last authored event must land at the present, or every replica sees a stale snapshot")
            .isLessThan(10);
    }

    @Test
    void allFourStandingsAreOnTheWindowsFlagship() {
        // The point of the seed clock: ENDED could not be produced before, because c7 refuses a
        // wholly-past window and the seeder writes through the same admin services as everyone else.
        var account = accountService.get("acct_2947");

        assertThat(account.overrides()).extracting(o -> o.standing().toString())
            .contains("ENDED", "IN_FORCE", "PENDING", "REMOVED");
    }

    @Test
    void aPendingOverrideTakesNoPartInTodaysAnswer() {
        // acct_2947's pending GRANT of 40 portfolios has not begun; `core` entitles 5.
        var explanation = com.solovis.entitlement.core.engine.Resolver.explain(
            snapshotHolder.current(), "acct_2947", new CapabilityKey("portfolio.count"), clock.instant());

        assertThat(explanation.value().toString()).contains("5");
    }

    @Test
    void theClockIsRealTimeOnceSeedingHasFinished() {
        assertThat(clock).isInstanceOf(SeedClock.class);
        assertThat(((SeedClock) clock).isWound()).isFalse();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs().toSeconds()).isLessThan(5);
    }
}
```

Note: three shapes must be checked against the source before running, and the call adjusted to match rather than the production code changed — `AuditEventFilter`'s constructor arity, the audit row's timestamp accessor (`occurredAt`), and `AccountDetailDto`'s override collection and its standing accessor, which 002 reshaped.

- [ ] **Step 2: Run it**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test -Dtest=DemoDataSeederIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 8 tests. A failure here is a real finding — read the message before adjusting the dataset.

- [ ] **Step 3: Run the whole backend suite**

Run: `cd management/backend && ./mvnw -pl entitlement-service -am test`
Expected: PASS. Note this now builds a second Spring context, so the service module's tests take noticeably longer.

- [ ] **Step 4: Commit**

```bash
git add management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/seed/DemoDataSeederIT.java
git commit -m "test(seed): prove the whole seed against a real service"
```

---

### Task 8: The end-to-end suite and the docs

**Files:**
- Modify: `management/frontend/management-ui/e2e/operator-screens.spec.ts`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: the seeded fixtures from Task 4.
- Produces: a passing e2e run against the new dataset.

- [ ] **Step 1: Update the one display-text assertion**

In `operator-screens.spec.ts`, the default-plan test asserts on the plan's display name, which is now `Evaluation`:

```ts
    // c7: never without entitlements. `free` is the designated default in the seed.
    await page.getByRole('link', { name: external }).click()
    await expect(page.getByText('Evaluation')).toBeVisible()
```

Also update the fixture comment at the top of the file:

```ts
 * Fixtures come from `DemoDataSeeder`, which applies `seed/demo-seed.json` across an authored
 * 240-day timeline: 16 capabilities over six areas (one retired), plans `free` (Evaluation, the
 * default), `core`, `pro` (Professional), `enterprise` and `ocio`, and ~60 accounts. `acct_9931`
 * (Northwind Capital, on `pro`) still carries the GRANT of 200 monthly reports; `acct_1177`
 * (on `free`) still carries the three standings 002 seeded, with the same reasons. Every key
 * this suite and `windows.spec.ts` locate by is preserved.
```

- [ ] **Step 2: Run the e2e suite**

Run: `cd management/frontend/management-ui && npm run test:e2e`
Expected: all specs passing, `operator-screens.spec.ts` and `windows.spec.ts` alike. 002 unpinned the e2e ports, so read `playwright.config.ts` for the port actually in use before hunting for a stray JVM; a leftover backend serving a dirty database produces failures that look exactly like regressions.

`windows.spec.ts` should need no change at all: it creates its own `e2e.window.*` capability and asserts against `acct_1177`, whose three seeded overrides are carried across verbatim. If it fails, the dataset dropped one of them — fix the dataset.

- [ ] **Step 3: Fix any assertion that the richer catalogue broke**

Locators that match on substrings can now match more rows than they did against four capabilities — for example a `getByText(/reports/)` will find several. Where a test fails for this reason, tighten the locator (exact match, or scope it to a row); do not weaken the assertion.

- [ ] **Step 4: Update the project instructions**

In `CLAUDE.md`, replace the seeding sentence under **End-to-end** and the `DemoDataSeeder` bullet under **Conventions and traps**:

```markdown
- **`SnapshotStartup`, `ConformanceAnnouncementStartup` and `DemoDataSeeder` are all `InitializingBean`s, not `ApplicationRunner`s** — Boot starts the web connector during context refresh, before runners fire, so a runner would leave a window where the port accepts traffic with no snapshot, or with a half-written demo. `DemoDataSeeder` is conditional on `entitlement.seed.enabled`, `@DependsOn("snapshotStartup")` because publishing mutates from the current snapshot, and gated on a `service_state` marker rather than on "are there any plans?" — a started-but-not-completed marker fails startup instead of silently serving half a demo. It applies `resources/seed/demo-seed.json` through the real admin services with the `Clock` bean wound to each authored day, so the change history spans eight months rather than one boot second and every override standing — including `ENDED`, which the API alone cannot produce — is seedable without bypassing validation; the clock is released before the connector opens. **Being an `InitializingBean` is what keeps the wound clock away from `WindowBoundaryRoller`**, which is `@Scheduled(initialDelay = 0)` and reads `LocalDate.now(clock)`: scheduled tasks start with the context lifecycle, after every `InitializingBean`. As an `ApplicationRunner` the roller would fire mid-seed, record `window.rolled-through` in the fictional past, and then publish a flood of boundary transitions for moments nobody observed.
```

- [ ] **Step 5: Commit**

```bash
git add management/frontend/management-ui/e2e/operator-screens.spec.ts CLAUDE.md
git commit -m "test(e2e): follow the seeded catalogue's new display names"
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task: the JSON dataset and applier (Tasks 3–5), the catalogue and flagship stories including the HOLD, the competing GRANTs, the removal, the two plan moves, `unlimited` and the retired capability (Task 4), seed-clock time travel and the end-at-the-present rule (Tasks 1 and 5), ordering before the port opens (Task 6), the marker and loud failure (Tasks 2 and 6), and the three testing commitments (Tasks 3, 4, 7, 8). The design's "Windows, and the fourth standing" is covered by Task 3's window rules, Task 4's `acct_2947` stories and carried-forward `acct_1177` overrides, Task 5's window-date derivation, and Task 7's standings assertions.

**Revised after 002 merged.** This plan was first written against a `main` without override windows; 002 landed mid-authoring. Three tasks changed as a result: Task 1's clock now decorates `ClockConfig.base` rather than building its own (`NoDirectClockAccessTest` bans `Clock.systemUTC()` outside that file, and the service clock is zoned); Task 3's validator now *checks* windows instead of rejecting them; Task 4 carries 002's three seeded standings verbatim and adds the ENDED one that could not previously exist. If any of it contradicts the code, the code wins.

**Placeholders.** None: every code step carries the code, every dataset row is written out, and the two places where a signature must be checked against the codebase (AssertJ's `within`, `AuditEventFilter`'s arity) say exactly what to do rather than leaving it open.

**Type consistency.** `SeedDataset.of(byte[], ObjectMapper)`, `validate()`, `fingerprint()`, `SeedClock.windTo/release/isWound`, `SeedState.Status`, and `SeedApplier.Summary` are used in later tasks exactly as Task 1–3 define them. The admin-service signatures in Task 5's Interfaces block were read from the source, not recalled.

**One risk worth stating.** `SnapshotVersionPruner` is a scheduled task and could in principle fire while the clock is wound. Its cutoff is `now − retention`, so a wound clock makes it prune *less*, never more — harmless, and the first sweep after release restores normal behaviour.
