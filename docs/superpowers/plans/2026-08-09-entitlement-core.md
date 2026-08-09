# Entitlement Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `entitlement-core` — the pure Java, dependency-free library holding the domain model, the total order over entitlement values, and the resolution engine (`resolve()`/`explain()`) that both `entitlement-service` and `entitlement-client` will embed.

**Architecture:** Immutable value types (records/sealed interfaces) model capabilities, plans, accounts and overrides. A `Snapshot` holds an entire model version behind a small set of lookup maps and implements `EntitlementView`, the read contract the resolver needs. `Resolver.resolve()` walks §4's rule (baseline → most-generous GRANT → most-restrictive HOLD) against an `EntitlementView` and returns `(allowed, value)` with no trace allocation; `Resolver.explain()` runs the identical arithmetic and layers a `Trace` on top. `SnapshotMutator` produces version N+1 from version N by rebuilding only the maps a change touches (structural sharing), so a concurrent reader is never mid-swap. A `conformance` package encodes the spec's §5 worked-examples table as vectors any engine (including a future replica) can self-check at startup.

**Tech Stack:** Java 21 (records, sealed interfaces, pattern matching on sealed types), JUnit 5, AssertJ, jqwik (property-based order-independence tests). No Spring, no I/O, no third-party runtime dependency — this module must stay embeddable in any JVM service.

**Module:** `management/backend/entitlement-core` (already scaffolded: `pom.xml` and empty `package-info.java` per package — see "Global Constraints"). Run all commands from `management/backend/`.

## Global Constraints

- No Spring, no I/O, no JSON library, no logging framework in `entitlement-core` — it is a pure library (`plan.md` Project Structure; `pom.xml` already carries zero runtime dependencies).
- Java 21 language level (`pom.xml` inherits `java.version=21` from the parent).
- `unlimited` is a distinct `Quantity` variant, never a large number (c2, data-model.md "Value representation").
- Tier order is data that travels with the value: an `EntitlementValue.Tier` carries both `tierKey` and `ordinal` (c3; `java-client-sdk.md` `Decision` comment: `Tier(key, ordinal)`).
- `SWITCH` capabilities never declare an off-value; their off-value is `false`, inherently and always (data-model.md §"Capability" validation rules, spec §5 table).
- A capability's `value_type` is immutable after construction — enforced by there being no mutator, only reconstruction (data-model.md, spec §3.1, c1).
- Tiers may only be **appended** above the current maximum ordinal, never inserted (spec §4 interpretation in `plan.md` "Recorded interpretations"; data-model.md capability_tier validation rules).
- Resolution depends only on what exists at the moment of decision, never on creation order or timestamps, for the **value** (c12, c13, c16). Creation order (highest override id) breaks ties only in the **trace label**, never the value (`plan.md` "Recorded interpretations"; `decision-api.md` "Ties are deterministic").
- `resolve()` and `explain()` must share their arithmetic — every test that asserts a value must also assert `resolve()` and `explain()` agree (research.md §19 "Additional obligation").
- An unknown account, unknown capability, or retired capability is a thrown exception, never `allowed: false` (c19).
- Every test that pins a decision must also be checked with the overrides shuffled into every order, per jqwik (c12, c13, c16; research.md §19).

---

## File Structure

```
entitlement-core/src/main/java/com/solovis/entitlement/core/
├── error/
│   ├── UnknownAccountException.java
│   ├── UnknownCapabilityException.java
│   └── RetiredCapabilityException.java
├── model/
│   ├── CapabilityKey.java
│   ├── ValueType.java
│   ├── EntitlementValue.java        (sealed; nested Switch, Quantity, Tier)
│   ├── TierOrder.java               (nested TierDefinition)
│   ├── OffValue.java
│   ├── Capability.java              (nested Status)
│   ├── Plan.java                    (nested Status)
│   ├── PlanEntitlement.java
│   ├── OverrideKind.java
│   ├── AccountOverride.java
│   └── AccountAssignment.java
├── order/
│   ├── Generosity.java
│   └── ValueComparator.java
├── view/
│   ├── EntitlementView.java
│   ├── Snapshot.java
│   ├── SnapshotBuilder.java
│   └── SnapshotMutator.java
├── engine/
│   ├── Decision.java
│   ├── TraceSource.java
│   ├── Outcome.java
│   ├── TraceEntry.java
│   ├── Trace.java
│   ├── Explanation.java
│   └── Resolver.java
└── conformance/
    ├── ConformanceVector.java
    ├── ConformanceCheck.java
    └── ResolverContract.java        (RESOLVER_CONTRACT constant)

entitlement-core/src/test/java/com/solovis/entitlement/core/
├── model/  (one test class per validated type)
├── order/GenerosityTest.java
├── view/{SnapshotBuilderTest,SnapshotMutatorTest}.java
├── engine/{ResolverResolveTest,ResolverExplainTest,ResolverOrderIndependencePropertyTest}.java
└── conformance/ConformanceCheckTest.java
```

Every package already has a `package-info.java` from scaffolding — do not delete or rewrite those; add classes alongside them.

---

### Task 1: Error types

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/error/UnknownAccountException.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/error/UnknownCapabilityException.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/error/RetiredCapabilityException.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/error/EntitlementExceptionsTest.java`

**Interfaces:**
- Produces: three unchecked exceptions later tasks throw from `Resolver` (c19 — unknown account/capability/retired capability are errors, never a silent denial).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EntitlementExceptionsTest {

    @Test
    void unknownAccountExceptionCarriesTheExternalId() {
        var ex = new UnknownAccountException("acct_9931");
        assertThat(ex.accountExternalId()).isEqualTo("acct_9931");
        assertThat(ex.getMessage()).contains("acct_9931");
    }

    @Test
    void unknownCapabilityExceptionCarriesTheKey() {
        var ex = new UnknownCapabilityException("export.parquet");
        assertThat(ex.capabilityKey()).isEqualTo("export.parquet");
        assertThat(ex.getMessage()).contains("export.parquet");
    }

    @Test
    void retiredCapabilityExceptionCarriesTheKey() {
        var ex = new RetiredCapabilityException("legacy.feature");
        assertThat(ex.capabilityKey()).isEqualTo("legacy.feature");
        assertThat(ex.getMessage()).contains("legacy.feature");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=EntitlementExceptionsTest`
Expected: FAIL to compile — `UnknownAccountException` etc. do not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.error;

/** No account is declared with this external id (c19) — an error, never a denial. */
public final class UnknownAccountException extends RuntimeException {

    private final String accountExternalId;

    public UnknownAccountException(String accountExternalId) {
        super("No account is declared with external id '" + accountExternalId + "'.");
        this.accountExternalId = accountExternalId;
    }

    public String accountExternalId() {
        return accountExternalId;
    }
}
```

```java
package com.solovis.entitlement.core.error;

/** No capability is declared with this key (c19) — an error, never a denial. */
public final class UnknownCapabilityException extends RuntimeException {

    private final String capabilityKey;

    public UnknownCapabilityException(String capabilityKey) {
        super("No capability is declared with key '" + capabilityKey + "'.");
        this.capabilityKey = capabilityKey;
    }

    public String capabilityKey() {
        return capabilityKey;
    }
}
```

```java
package com.solovis.entitlement.core.error;

/** The capability exists but is retired, so it is not evaluable (c8, c19). */
public final class RetiredCapabilityException extends RuntimeException {

    private final String capabilityKey;

    public RetiredCapabilityException(String capabilityKey) {
        super("Capability '" + capabilityKey + "' is retired and is not evaluable.");
        this.capabilityKey = capabilityKey;
    }

    public String capabilityKey() {
        return capabilityKey;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=EntitlementExceptionsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/error/ \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/error/
git commit -m "feat(entitlement-core): add unknown/retired error types"
```

---

### Task 2: Value model — `ValueType`, `CapabilityKey`, `EntitlementValue`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/ValueType.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/CapabilityKey.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/EntitlementValue.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/CapabilityKeyTest.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/EntitlementValueTest.java`

**Interfaces:**
- Produces: `ValueType` enum `{SWITCH, QUANTITY, TIER}`; `CapabilityKey(String value)` with `.area()`; sealed `EntitlementValue` with `valueType()` and nested `Switch(boolean enabled)`, `Quantity(long amount, boolean unlimited)` (factories `Quantity.of(long)`, `Quantity.unbounded()` — named `unbounded`, not `unlimited`, because Java rejects a static method sharing a zero-arg name with the record's auto-generated `unlimited()` accessor), `Tier(String tierKey, int ordinal)`. These are consumed by every later task.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityKeyTest {

    @Test
    void derivesAreaFromThePrefixBeforeTheFirstDot() {
        assertThat(new CapabilityKey("export.parquet").area()).isEqualTo("export");
        assertThat(new CapabilityKey("integration.salesforce.write").area()).isEqualTo("integration");
    }

    @Test
    void rejectsAKeyWithNoDot() {
        assertThatThrownBy(() -> new CapabilityKey("apiaccess"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUppercaseAndEmpty() {
        assertThatThrownBy(() -> new CapabilityKey("Export.Parquet"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityKey(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalKeysAreEqual() {
        assertThat(new CapabilityKey("export.parquet")).isEqualTo(new CapabilityKey("export.parquet"));
    }
}
```

```java
package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitlementValueTest {

    @Test
    void switchCarriesAnEnabledFlagAndItsOwnType() {
        var value = new EntitlementValue.Switch(true);
        assertThat(value.enabled()).isTrue();
        assertThat(value.valueType()).isEqualTo(ValueType.SWITCH);
    }

    @Test
    void quantityOfCarriesAnAmount() {
        var value = EntitlementValue.Quantity.of(50);
        assertThat(value.amount()).isEqualTo(50);
        assertThat(value.unlimited()).isFalse();
        assertThat(value.valueType()).isEqualTo(ValueType.QUANTITY);
    }

    @Test
    void quantityUnlimitedIsADistinctVariantNotALargeNumber() {
        var value = EntitlementValue.Quantity.unbounded();
        assertThat(value.unlimited()).isTrue();
        assertThat(value.amount()).isZero(); // amount is not meaningful when unlimited — never Long.MAX_VALUE (c2)
    }

    @Test
    void quantityRejectsNegativeAmounts() {
        assertThatThrownBy(() -> new EntitlementValue.Quantity(-1, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityRejectsAnAmountAlongsideUnlimited() {
        assertThatThrownBy(() -> new EntitlementValue.Quantity(5, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierCarriesItsDeclaredOrdinal() {
        var value = new EntitlementValue.Tier("gold", 2);
        assertThat(value.tierKey()).isEqualTo("gold");
        assertThat(value.ordinal()).isEqualTo(2);
        assertThat(value.valueType()).isEqualTo(ValueType.TIER);
    }

    @Test
    void tierRejectsANegativeOrdinal() {
        assertThatThrownBy(() -> new EntitlementValue.Tier("gold", -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=CapabilityKeyTest,EntitlementValueTest`
Expected: FAIL to compile — none of the three types exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.model;

/** A capability's value shape. Immutable across every plan (c1) — enforced by having no setter, only reconstruction. */
public enum ValueType {
    SWITCH,
    QUANTITY,
    TIER
}
```

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A capability's dotted, unique name. The substring before the first dot is its area (c40),
 * derived here rather than stored separately so it can never drift from the key.
 */
public record CapabilityKey(String value) implements Comparable<CapabilityKey> {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9_-]+)+$");

    public CapabilityKey {
        Objects.requireNonNull(value, "value");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Capability key '" + value + "' must match " + PATTERN.pattern());
        }
    }

    public String area() {
        return value.substring(0, value.indexOf('.'));
    }

    @Override
    public int compareTo(CapabilityKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
```

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;

/**
 * The effective value of a capability. Sealed so the resolver's comparison is exhaustively
 * checked at compile time — a fourth variant cannot be added without every switch expression
 * being revisited.
 */
public sealed interface EntitlementValue
    permits EntitlementValue.Switch, EntitlementValue.Quantity, EntitlementValue.Tier {

    ValueType valueType();

    record Switch(boolean enabled) implements EntitlementValue {
        @Override
        public ValueType valueType() {
            return ValueType.SWITCH;
        }
    }

    /**
     * Either {@code amount} or {@code unlimited}, never both and never neither (c2).
     * {@code unlimited} is a distinct variant, not {@code Long.MAX_VALUE} — a large number would
     * leak into any serialised form the moment it was written down.
     */
    record Quantity(long amount, boolean unlimited) implements EntitlementValue {

        public Quantity {
            if (unlimited && amount != 0) {
                throw new IllegalArgumentException("An unlimited quantity does not carry an amount.");
            }
            if (!unlimited && amount < 0) {
                throw new IllegalArgumentException("A quantity amount must not be negative.");
            }
        }

        public static Quantity of(long amount) {
            return new Quantity(amount, false);
        }

        public static Quantity unbounded() {
            return new Quantity(0, true);
        }

        @Override
        public ValueType valueType() {
            return ValueType.QUANTITY;
        }
    }

    /**
     * {@code ordinal} travels with the value (not just the key) so a caller can answer
     * "at least tier X" without a second call to the registry (c3).
     */
    record Tier(String tierKey, int ordinal) implements EntitlementValue {

        public Tier {
            Objects.requireNonNull(tierKey, "tierKey");
            if (ordinal < 0) {
                throw new IllegalArgumentException("Tier ordinal must not be negative.");
            }
        }

        @Override
        public ValueType valueType() {
            return ValueType.TIER;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=CapabilityKeyTest,EntitlementValueTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/ValueType.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/CapabilityKey.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/EntitlementValue.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/CapabilityKeyTest.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/EntitlementValueTest.java
git commit -m "feat(entitlement-core): add ValueType, CapabilityKey, sealed EntitlementValue"
```

---

### Task 3: `order` package — `Generosity` and `ValueComparator`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/order/Generosity.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/order/ValueComparator.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/order/GenerosityTest.java`

**Interfaces:**
- Consumes: `EntitlementValue`, `ValueType` (Task 2).
- Produces: `Generosity.compare(EntitlementValue, EntitlementValue)`, `Generosity.mostGenerous(a, b)`, `Generosity.mostRestrictive(a, b)`; `ValueComparator.INSTANCE` (a `Comparator<EntitlementValue>`). `Resolver` (Task 10) folds GRANT/HOLD lists with `mostGenerous`/`mostRestrictive`.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerosityTest {

    @Test
    void switchOnIsMoreGenerousThanOff() {
        var off = new EntitlementValue.Switch(false);
        var on = new EntitlementValue.Switch(true);
        assertThat(Generosity.compare(off, on)).isNegative();
        assertThat(Generosity.mostGenerous(off, on)).isEqualTo(on);
        assertThat(Generosity.mostRestrictive(off, on)).isEqualTo(off);
    }

    @Test
    void largerQuantityIsMoreGenerous() {
        var fifty = EntitlementValue.Quantity.of(50);
        var twoHundred = EntitlementValue.Quantity.of(200);
        assertThat(Generosity.compare(fifty, twoHundred)).isNegative();
        assertThat(Generosity.mostGenerous(fifty, twoHundred)).isEqualTo(twoHundred);
    }

    @Test
    void unlimitedIsMoreGenerousThanAnyFiniteAmount() {
        var large = EntitlementValue.Quantity.of(1_000_000_000L);
        var unlimited = EntitlementValue.Quantity.unbounded();
        assertThat(Generosity.compare(large, unlimited)).isNegative();
        assertThat(Generosity.mostGenerous(large, unlimited)).isEqualTo(unlimited);
    }

    @Test
    void twoUnlimitedQuantitiesAreEqual() {
        assertThat(Generosity.compare(EntitlementValue.Quantity.unbounded(), EntitlementValue.Quantity.unbounded()))
            .isZero();
    }

    @Test
    void higherTierOrdinalIsMoreGenerous() {
        var community = new EntitlementValue.Tier("community", 0);
        var gold = new EntitlementValue.Tier("gold", 2);
        assertThat(Generosity.compare(community, gold)).isNegative();
        assertThat(Generosity.mostGenerous(community, gold)).isEqualTo(gold);
    }

    @Test
    void comparingDifferentValueTypesThrows() {
        assertThatThrownBy(() -> Generosity.compare(new EntitlementValue.Switch(true), EntitlementValue.Quantity.of(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueComparatorSortsAscendingByGenerosity() {
        var fifty = EntitlementValue.Quantity.of(50);
        var twoHundred = EntitlementValue.Quantity.of(200);
        assertThat(ValueComparator.INSTANCE.compare(fifty, twoHundred)).isNegative();
        assertThat(ValueComparator.INSTANCE.compare(twoHundred, fifty)).isPositive();
        assertThat(ValueComparator.INSTANCE.compare(fifty, EntitlementValue.Quantity.of(50))).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=GenerosityTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;

/**
 * The total order §4 needs to define "most generous" and "most restrictive". One rule per
 * value type; comparing across types is a caller bug, not a value to be ranked.
 */
public final class Generosity {

    private Generosity() {}

    public static int compare(EntitlementValue a, EntitlementValue b) {
        if (a.valueType() != b.valueType()) {
            throw new IllegalArgumentException(
                "Cannot compare values of different types: " + a.valueType() + " vs " + b.valueType());
        }
        return switch (a) {
            case EntitlementValue.Switch sa ->
                Boolean.compare(sa.enabled(), ((EntitlementValue.Switch) b).enabled());
            case EntitlementValue.Quantity qa -> compareQuantity(qa, (EntitlementValue.Quantity) b);
            case EntitlementValue.Tier ta -> Integer.compare(ta.ordinal(), ((EntitlementValue.Tier) b).ordinal());
        };
    }

    private static int compareQuantity(EntitlementValue.Quantity a, EntitlementValue.Quantity b) {
        if (a.unlimited() && b.unlimited()) {
            return 0;
        }
        if (a.unlimited()) {
            return 1;
        }
        if (b.unlimited()) {
            return -1;
        }
        return Long.compare(a.amount(), b.amount());
    }

    public static EntitlementValue mostGenerous(EntitlementValue a, EntitlementValue b) {
        return compare(a, b) >= 0 ? a : b;
    }

    public static EntitlementValue mostRestrictive(EntitlementValue a, EntitlementValue b) {
        return compare(a, b) <= 0 ? a : b;
    }
}
```

```java
package com.solovis.entitlement.core.order;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.util.Comparator;

/** {@link Generosity#compare} as a reusable {@link Comparator}. */
public final class ValueComparator implements Comparator<EntitlementValue> {

    public static final ValueComparator INSTANCE = new ValueComparator();

    private ValueComparator() {}

    @Override
    public int compare(EntitlementValue a, EntitlementValue b) {
        return Generosity.compare(a, b);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=GenerosityTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/order/ \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/order/
git commit -m "feat(entitlement-core): add Generosity total order and ValueComparator"
```

---

### Task 4: `TierOrder` and `OffValue`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/TierOrder.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/OffValue.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/TierOrderTest.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/OffValueTest.java`

**Interfaces:**
- Consumes: `EntitlementValue` (Task 2).
- Produces: `TierOrder` with nested `TierDefinition(String tierKey, int ordinal, String displayName)`, `TierOrder.NONE`, `.ordinalOf(String)`, `.declares(String)`, `.maxOrdinal()`, `.appending(String tierKey, String displayName)`. `OffValue(EntitlementValue value)`. Consumed by `Capability` (Task 5).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.model;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierOrderTest {

    private final TierOrder support = new TierOrder(List.of(
        new TierOrder.TierDefinition("community", 0, "Community"),
        new TierOrder.TierDefinition("standard", 1, "Standard"),
        new TierOrder.TierDefinition("gold", 2, "Gold")
    ));

    @Test
    void looksUpOrdinalByKey() {
        assertThat(support.ordinalOf("gold")).isEqualTo(OptionalInt.of(2));
        assertThat(support.ordinalOf("unknown")).isEqualTo(OptionalInt.empty());
    }

    @Test
    void declaresReportsMembership() {
        assertThat(support.declares("community")).isTrue();
        assertThat(support.declares("platinum")).isFalse();
    }

    @Test
    void maxOrdinalIsTheHighestDeclared() {
        assertThat(support.maxOrdinal()).isEqualTo(2);
    }

    @Test
    void appendingAddsAboveTheCurrentMaximum() {
        var extended = support.appending("platinum", "Platinum");
        assertThat(extended.ordinalOf("platinum")).isEqualTo(OptionalInt.of(3));
        assertThat(support.declares("platinum")).isFalse(); // original is untouched
    }

    @Test
    void rejectsNonContiguousOrdinals() {
        assertThatThrownBy(() -> new TierOrder(List.of(
            new TierOrder.TierDefinition("a", 0, "A"),
            new TierOrder.TierDefinition("b", 2, "B")
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateOrdinalsOrKeys() {
        assertThatThrownBy(() -> new TierOrder(List.of(
            new TierOrder.TierDefinition("a", 0, "A"),
            new TierOrder.TierDefinition("a", 1, "A again")
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneIsEmptyAndRejectsAppending() {
        assertThat(TierOrder.NONE.declares("anything")).isFalse();
        assertThat(TierOrder.NONE.maxOrdinal()).isEqualTo(-1);
    }
}
```

```java
package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OffValueTest {

    @Test
    void wrapsAValue() {
        var offValue = new OffValue(EntitlementValue.Quantity.of(0));
        assertThat(offValue.value()).isEqualTo(EntitlementValue.Quantity.of(0));
    }

    @Test
    void rejectsANullValue() {
        assertThatThrownBy(() -> new OffValue(null)).isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=TierOrderTest,OffValueTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * The declared, ordered levels of a TIER capability (c3). Ordinals are contiguous from 0.
 * Tiers may only be appended above the current maximum — inserting would renumber existing
 * stored values and silently rewrite what they used to mean (data-model.md capability_tier).
 */
public record TierOrder(List<TierDefinition> tiers) {

    public static final TierOrder NONE = new TierOrder(List.of());

    public record TierDefinition(String tierKey, int ordinal, String displayName) {}

    public TierOrder {
        tiers = List.copyOf(tiers);
        var seenKeys = new java.util.HashSet<String>();
        var seenOrdinals = new java.util.HashSet<Integer>();
        for (var tier : tiers) {
            if (!seenKeys.add(tier.tierKey())) {
                throw new IllegalArgumentException("Duplicate tier key '" + tier.tierKey() + "'.");
            }
            if (!seenOrdinals.add(tier.ordinal())) {
                throw new IllegalArgumentException("Duplicate tier ordinal " + tier.ordinal() + ".");
            }
        }
        var sortedOrdinals = tiers.stream().map(TierDefinition::ordinal).sorted().toList();
        for (int i = 0; i < sortedOrdinals.size(); i++) {
            if (sortedOrdinals.get(i) != i) {
                throw new IllegalArgumentException("Tier ordinals must be contiguous from 0: " + sortedOrdinals);
            }
        }
    }

    public OptionalInt ordinalOf(String tierKey) {
        return tiers.stream()
            .filter(t -> t.tierKey().equals(tierKey))
            .mapToInt(TierDefinition::ordinal)
            .findFirst();
    }

    public boolean declares(String tierKey) {
        return ordinalOf(tierKey).isPresent();
    }

    public int maxOrdinal() {
        return tiers.stream().mapToInt(TierDefinition::ordinal).max().orElse(-1);
    }

    public TierOrder appending(String tierKey, String displayName) {
        var next = new java.util.ArrayList<>(tiers);
        next.add(new TierDefinition(tierKey, maxOrdinal() + 1, displayName));
        return new TierOrder(next);
    }
}
```

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;

/**
 * A capability's declared "not-available" value (spec §5). SWITCH never has one here — its
 * off-value is {@code false}, inherently, and is never stored (see {@link Capability}).
 */
public record OffValue(EntitlementValue value) {

    public OffValue {
        Objects.requireNonNull(value, "value");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=TierOrderTest,OffValueTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/TierOrder.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/OffValue.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/TierOrderTest.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/OffValueTest.java
git commit -m "feat(entitlement-core): add TierOrder and OffValue"
```

---

### Task 5: `Capability`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/Capability.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/CapabilityTest.java`

**Interfaces:**
- Consumes: `CapabilityKey`, `ValueType`, `EntitlementValue`, `OffValue`, `TierOrder` (Tasks 2, 4).
- Produces: `Capability` record with `.area()`, `.isRetired()`, `.effectiveOffValue(): Optional<EntitlementValue>` (folds in the implicit SWITCH-off-is-false rule). Consumed by `Resolver` (Task 10) to compute `allowed` and by `Snapshot`/`SnapshotBuilder` (Task 8).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityTest {

    private static final TierOrder SUPPORT_TIERS = new TierOrder(List.of(
        new TierOrder.TierDefinition("community", 0, "Community"),
        new TierOrder.TierDefinition("gold", 1, "Gold")
    ));

    @Test
    void areaIsDerivedFromTheKey() {
        var capability = switchCapability("api.access");
        assertThat(capability.area()).isEqualTo("api");
    }

    @Test
    void switchDefaultMustMatchDeclaredValueType() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            EntitlementValue.Quantity.of(1), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void switchCapabilityMayNotDeclareAnOffValue() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.of(new OffValue(new EntitlementValue.Switch(false))),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void switchOffValueIsInherentlyFalse() {
        var capability = switchCapability("api.access");
        assertThat(capability.effectiveOffValue()).contains(new EntitlementValue.Switch(false));
    }

    @Test
    void quantityOffValueMustBeZeroWhenDeclared() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.of(new OffValue(EntitlementValue.Quantity.of(1))),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityOffValueMayNeverBeUnlimited() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.of(new OffValue(EntitlementValue.Quantity.unbounded())),
            TierOrder.NONE, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantityWithNoDeclaredOffValueHasNoEffectiveOffValue() {
        var capability = new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        assertThat(capability.effectiveOffValue()).isEmpty();
    }

    @Test
    void quantityOffValueOfZeroIsAccepted() {
        var capability = new Capability(
            new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(50), Optional.of(new OffValue(EntitlementValue.Quantity.of(0))),
            TierOrder.NONE, Capability.Status.ACTIVE, null);
        assertThat(capability.effectiveOffValue()).contains(EntitlementValue.Quantity.of(0));
    }

    @Test
    void tierCapabilityRequiresAtLeastTwoTiers() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0), Optional.empty(),
            new TierOrder(List.of(new TierOrder.TierDefinition("community", 0, "Community"))),
            Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierDefaultMustBeADeclaredTier() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("platinum", 9), Optional.empty(), SUPPORT_TIERS,
            Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierOffValueMustBeADeclaredTier() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0),
            Optional.of(new OffValue(new EntitlementValue.Tier("platinum", 9))),
            SUPPORT_TIERS, Capability.Status.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tierOffValueThatIsDeclaredIsAccepted() {
        var capability = new Capability(
            new CapabilityKey("support.level"), "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0),
            Optional.of(new OffValue(new EntitlementValue.Tier("community", 0))),
            SUPPORT_TIERS, Capability.Status.ACTIVE, null);
        assertThat(capability.effectiveOffValue()).contains(new EntitlementValue.Tier("community", 0));
    }

    @Test
    void retiredRequiresARetiredAtTimestamp() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.RETIRED, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retiredCapabilityWithATimestampIsAccepted() {
        var capability = new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.RETIRED, Instant.parse("2026-08-09T00:00:00Z"));
        assertThat(capability.isRetired()).isTrue();
    }

    @Test
    void activeCapabilityMustNotCarryARetiredAtTimestamp() {
        assertThatThrownBy(() -> new Capability(
            new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, Instant.parse("2026-08-09T00:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isRetiredReflectsStatus() {
        var capability = switchCapability("api.access");
        assertThat(capability.isRetired()).isFalse();
    }

    private static Capability switchCapability(String key) {
        return new Capability(
            new CapabilityKey(key), "Display name", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=CapabilityTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A registered, evaluable thing an account may be allowed to do (spec §3.1). Nothing is
 * evaluable unless declared here — that is what stops ad-hoc checks creeping back in.
 */
public record Capability(
    CapabilityKey key,
    String displayName,
    String description,
    ValueType valueType,
    EntitlementValue defaultValue,
    Optional<OffValue> offValue,
    TierOrder tierOrder,
    Status status,
    Instant retiredAt
) {

    public enum Status { ACTIVE, RETIRED }

    public Capability {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(offValue, "offValue");
        Objects.requireNonNull(tierOrder, "tierOrder");
        Objects.requireNonNull(status, "status");

        if (defaultValue.valueType() != valueType) {
            throw new IllegalArgumentException(
                "Default value type " + defaultValue.valueType() + " does not match declared type " + valueType);
        }
        validateOffValue(valueType, offValue);
        validateTierOrder(valueType, tierOrder, defaultValue, offValue);
        if (status == Status.RETIRED && retiredAt == null) {
            throw new IllegalArgumentException("A retired capability must carry retiredAt.");
        }
        if (status == Status.ACTIVE && retiredAt != null) {
            throw new IllegalArgumentException("An active capability must not carry retiredAt.");
        }
    }

    private static void validateOffValue(ValueType valueType, Optional<OffValue> offValue) {
        if (offValue.isEmpty()) {
            return;
        }
        var declared = offValue.get().value();
        if (valueType == ValueType.SWITCH) {
            throw new IllegalArgumentException("A SWITCH capability may not declare an off-value; it is always false.");
        }
        if (declared.valueType() != valueType) {
            throw new IllegalArgumentException(
                "Off-value type " + declared.valueType() + " does not match declared type " + valueType);
        }
        if (valueType == ValueType.QUANTITY) {
            var quantity = (EntitlementValue.Quantity) declared;
            if (quantity.unlimited() || quantity.amount() != 0) {
                throw new IllegalArgumentException("A QUANTITY off-value, when declared, must be exactly 0.");
            }
        }
    }

    private static void validateTierOrder(
        ValueType valueType, TierOrder tierOrder, EntitlementValue defaultValue, Optional<OffValue> offValue) {
        if (valueType == ValueType.TIER) {
            if (tierOrder.tiers().size() < 2) {
                throw new IllegalArgumentException("A TIER capability must declare at least two tiers.");
            }
            var defaultTier = (EntitlementValue.Tier) defaultValue;
            if (!tierOrder.declares(defaultTier.tierKey())) {
                throw new IllegalArgumentException("Default tier '" + defaultTier.tierKey() + "' is not declared.");
            }
            offValue.ifPresent(off -> {
                var offTier = (EntitlementValue.Tier) off.value();
                if (!tierOrder.declares(offTier.tierKey())) {
                    throw new IllegalArgumentException("Off-value tier '" + offTier.tierKey() + "' is not declared.");
                }
            });
        } else if (!tierOrder.tiers().isEmpty()) {
            throw new IllegalArgumentException("Only a TIER capability may declare tiers.");
        }
    }

    public String area() {
        return key.area();
    }

    public boolean isRetired() {
        return status == Status.RETIRED;
    }

    /**
     * The value meaning "not available", folding in the SWITCH rule that is never stored:
     * {@code false} is always SWITCH's off-value (spec §5 table).
     */
    public Optional<EntitlementValue> effectiveOffValue() {
        if (valueType == ValueType.SWITCH) {
            return Optional.of(new EntitlementValue.Switch(false));
        }
        return offValue.map(OffValue::value);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=CapabilityTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/Capability.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/CapabilityTest.java
git commit -m "feat(entitlement-core): add Capability with §5 validation rules"
```

---

### Task 6: `Plan`, `PlanEntitlement`, `OverrideKind`, `AccountOverride`, `AccountAssignment`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/Plan.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/PlanEntitlement.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/OverrideKind.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/AccountOverride.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/model/AccountAssignment.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/PlanTest.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/model/AccountOverrideTest.java`

**Interfaces:**
- Consumes: `CapabilityKey`, `EntitlementValue` (Task 2).
- Produces: `Plan(String key, String name, Status status, boolean defaultForNewAccounts)`; `PlanEntitlement(String planKey, CapabilityKey capabilityKey, EntitlementValue value)`; `OverrideKind {GRANT, HOLD}`; `AccountOverride(OptionalLong id, String accountExternalId, CapabilityKey capabilityKey, OverrideKind kind, EntitlementValue value, Optional<String> reason, Optional<String> createdBy, Optional<Instant> createdAt)` — the metadata fields are `Optional`/absent for a client-side replica, always present when built by the management service (research.md §2, data-model.md `delta_json` note); `AccountAssignment(String accountExternalId, String planKey)`. Consumed by `EntitlementView`/`Snapshot` (Task 8) and `Resolver` (Tasks 10–11).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    @Test
    void createsAnActivePlan() {
        var plan = new Plan("pro", "Pro", Plan.Status.ACTIVE, false);
        assertThat(plan.status()).isEqualTo(Plan.Status.ACTIVE);
    }

    @Test
    void anArchivedPlanCannotBeTheDefault() {
        assertThatThrownBy(() -> new Plan("legacy", "Legacy", Plan.Status.ARCHIVED, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planEntitlementValueCarriesItsCapabilityAndValue() {
        var entitlement = new PlanEntitlement("pro", new CapabilityKey("reports.monthly"),
            EntitlementValue.Quantity.of(50));
        assertThat(entitlement.planKey()).isEqualTo("pro");
        assertThat(entitlement.value()).isEqualTo(EntitlementValue.Quantity.of(50));
    }
}
```

```java
package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccountOverrideTest {

    @Test
    void fullOverrideAsBuiltByTheManagementServiceCarriesEveryField() {
        var override = new AccountOverride(
            OptionalLong.of(4471), "acct_9931", new CapabilityKey("reports.monthly"),
            OverrideKind.GRANT, EntitlementValue.Quantity.of(200),
            Optional.of("Renewal concession"), Optional.of("j.okafor"), Optional.of(Instant.parse("2026-06-02T09:12:44Z")));

        assertThat(override.id()).isEqualTo(OptionalLong.of(4471));
        assertThat(override.reason()).contains("Renewal concession");
    }

    @Test
    void projectedOverrideAsCarriedByAReplicaOmitsMetadata() {
        var override = new AccountOverride(
            OptionalLong.empty(), "acct_9931", new CapabilityKey("reports.monthly"),
            OverrideKind.HOLD, EntitlementValue.Quantity.of(0),
            Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(override.id()).isEmpty();
        assertThat(override.reason()).isEmpty();
        assertThat(override.value()).isEqualTo(EntitlementValue.Quantity.of(0));
    }

    @Test
    void accountAssignmentCarriesTheExternalIdAndPlanKey() {
        var assignment = new AccountAssignment("acct_9931", "pro");
        assertThat(assignment.accountExternalId()).isEqualTo("acct_9931");
        assertThat(assignment.planKey()).isEqualTo("pro");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=PlanTest,AccountOverrideTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;

/** A named set of capability values forming the baseline for every account on it (spec §3.2). */
public record Plan(String key, String name, Status status, boolean defaultForNewAccounts) {

    public enum Status { ACTIVE, ARCHIVED }

    public Plan {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        if (status == Status.ARCHIVED && defaultForNewAccounts) {
            throw new IllegalArgumentException("An archived plan cannot be the default for new accounts (c7).");
        }
    }
}
```

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;

/** One capability value set by one plan (spec §3.2). Its absence is what makes a plan partial (c4). */
public record PlanEntitlement(String planKey, CapabilityKey capabilityKey, EntitlementValue value) {

    public PlanEntitlement {
        Objects.requireNonNull(planKey, "planKey");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(value, "value");
    }
}
```

```java
package com.solovis.entitlement.core.model;

/** GRANT raises a capability above its plan; HOLD restricts it below (spec §3.4). */
public enum OverrideKind {
    GRANT,
    HOLD
}
```

```java
package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An exception attached to one account and one capability (spec §3.4). {@code reason},
 * {@code createdBy} and {@code createdAt} are present when built by the management service and
 * absent on a replica's answer-only projection (research.md §2) — {@link
 * com.solovis.entitlement.core.engine.Resolver#resolve} never reads them; only {@code explain}
 * does.
 */
public record AccountOverride(
    OptionalLong id,
    String accountExternalId,
    CapabilityKey capabilityKey,
    OverrideKind kind,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt
) {

    public AccountOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
```

```java
package com.solovis.entitlement.core.model;

import java.util.Objects;

/** Which plan one account is currently on (spec §3.3) — an account has exactly one, never zero. */
public record AccountAssignment(String accountExternalId, String planKey) {

    public AccountAssignment {
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(planKey, "planKey");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=PlanTest,AccountOverrideTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/Plan.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/PlanEntitlement.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/OverrideKind.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/AccountOverride.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/model/AccountAssignment.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/PlanTest.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/model/AccountOverrideTest.java
git commit -m "feat(entitlement-core): add Plan, PlanEntitlement, AccountOverride, AccountAssignment"
```

---

### Task 7: `view` package — `EntitlementView` interface

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/EntitlementView.java`

**Interfaces:**
- Consumes: `Capability`, `CapabilityKey`, `AccountAssignment`, `PlanEntitlement`, `AccountOverride` (Tasks 5–6).
- Produces: the read contract `Resolver` (Task 10) and `Snapshot` (Task 8) both depend on. No behaviour to test in isolation — an interface with no default methods; correctness is exercised through `Snapshot` and `Resolver` tests.

- [ ] **Step 1: Write the interface (no test — pure contract, exercised by later tasks)**

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The read contract {@link com.solovis.entitlement.core.engine.Resolver} needs. {@link Snapshot}
 * is the only production implementation; the interface exists so tests can supply a minimal
 * fixture without constructing a full snapshot.
 */
public interface EntitlementView {

    long snapshotVersion();

    Optional<Capability> capability(CapabilityKey key);

    /** Every non-retired capability, for whole-account resolution (c20). */
    Collection<Capability> activeCapabilities();

    Optional<AccountAssignment> account(String accountExternalId);

    Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey);

    /** Every LIVE override of either kind for this account and capability (§4). */
    List<AccountOverride> liveOverrides(String accountExternalId, CapabilityKey capabilityKey);
}
```

- [ ] **Step 2: Verify the module still compiles**

Run: `./mvnw -q -pl entitlement-core -am compile`
Expected: BUILD SUCCESS (no test to run — this is a pure interface).

- [ ] **Step 3: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/view/EntitlementView.java
git commit -m "feat(entitlement-core): add EntitlementView read contract"
```

---

### Task 8: `Snapshot` and `SnapshotBuilder`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/Snapshot.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotBuilder.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java`

**Interfaces:**
- Consumes: `Capability`, `Plan`, `PlanEntitlement`, `AccountAssignment`, `AccountOverride`, `EntitlementView` (Tasks 5–7).
- Produces: `Snapshot implements EntitlementView`, immutable; `SnapshotBuilder` with `.capability(Capability)`, `.plan(Plan)`, `.planEntitlement(PlanEntitlement)`, `.account(AccountAssignment)`, `.override(AccountOverride)`, `.build(long version): Snapshot`. Consumed by `Resolver` tests (Tasks 10–11) and `SnapshotMutator` (Task 12).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotBuilderTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");

    private static Capability reportsCapability() {
        return new Capability(REPORTS, "Monthly reports", null, com.solovis.entitlement.core.model.ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    @Test
    void looksUpEveryEntityItWasGiven() {
        var snapshot = new SnapshotBuilder()
            .capability(reportsCapability())
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_9931", "pro"))
            .override(new AccountOverride(OptionalLong.of(1), "acct_9931", REPORTS, OverrideKind.GRANT,
                EntitlementValue.Quantity.of(200), Optional.of("goodwill"), Optional.of("s.patel"), Optional.empty()))
            .build(1);

        assertThat(snapshot.snapshotVersion()).isEqualTo(1);
        assertThat(snapshot.capability(REPORTS)).isPresent();
        assertThat(snapshot.account("acct_9931")).contains(new AccountAssignment("acct_9931", "pro"));
        assertThat(snapshot.planEntitlement("pro", REPORTS)).contains(
            new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)));
        assertThat(snapshot.liveOverrides("acct_9931", REPORTS)).hasSize(1);
    }

    @Test
    void activeCapabilitiesExcludesRetiredOnes() {
        var retired = new Capability(new CapabilityKey("legacy.feature"), "Legacy", null,
            com.solovis.entitlement.core.model.ValueType.SWITCH, new EntitlementValue.Switch(false),
            Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED, java.time.Instant.now());

        var snapshot = new SnapshotBuilder()
            .capability(reportsCapability())
            .capability(retired)
            .build(1);

        assertThat(snapshot.activeCapabilities()).extracting(Capability::key).containsExactly(REPORTS);
    }

    @Test
    void unknownLookupsReturnEmptyRatherThanThrowing() {
        var snapshot = new SnapshotBuilder().build(1);
        assertThat(snapshot.capability(REPORTS)).isEmpty();
        assertThat(snapshot.account("acct_missing")).isEmpty();
        assertThat(snapshot.liveOverrides("acct_missing", REPORTS)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=SnapshotBuilderTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The published, immutable state of the model at one moment (data-model.md "Snapshot version").
 * Every field is an unmodifiable map so a reader holding this instance can never observe a
 * half-applied change — {@link SnapshotMutator} always produces a new instance rather than
 * mutating this one.
 */
public final class Snapshot implements EntitlementView {

    private final long version;
    private final Map<CapabilityKey, Capability> capabilities;
    private final Map<String, Plan> plans;
    private final Map<PlanCapabilityKey, PlanEntitlement> planEntitlements;
    private final Map<String, AccountAssignment> accounts;
    private final Map<AccountCapabilityKey, List<AccountOverride>> liveOverrides;

    Snapshot(
        long version,
        Map<CapabilityKey, Capability> capabilities,
        Map<String, Plan> plans,
        Map<PlanCapabilityKey, PlanEntitlement> planEntitlements,
        Map<String, AccountAssignment> accounts,
        Map<AccountCapabilityKey, List<AccountOverride>> liveOverrides) {
        this.version = version;
        this.capabilities = capabilities;
        this.plans = plans;
        this.planEntitlements = planEntitlements;
        this.accounts = accounts;
        this.liveOverrides = liveOverrides;
    }

    @Override
    public long snapshotVersion() {
        return version;
    }

    @Override
    public Optional<Capability> capability(CapabilityKey key) {
        return Optional.ofNullable(capabilities.get(key));
    }

    @Override
    public Collection<Capability> activeCapabilities() {
        return capabilities.values().stream().filter(c -> !c.isRetired()).toList();
    }

    @Override
    public Optional<AccountAssignment> account(String accountExternalId) {
        return Optional.ofNullable(accounts.get(accountExternalId));
    }

    @Override
    public Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey capabilityKey) {
        return Optional.ofNullable(planEntitlements.get(new PlanCapabilityKey(planKey, capabilityKey)));
    }

    @Override
    public List<AccountOverride> liveOverrides(String accountExternalId, CapabilityKey capabilityKey) {
        return liveOverrides.getOrDefault(new AccountCapabilityKey(accountExternalId, capabilityKey), List.of());
    }

    Optional<Plan> plan(String planKey) {
        return Optional.ofNullable(plans.get(planKey));
    }

    // Package-visible accessors SnapshotMutator uses to rebuild only the maps a change touches.
    Map<CapabilityKey, Capability> capabilitiesMap() { return capabilities; }
    Map<String, Plan> plansMap() { return plans; }
    Map<PlanCapabilityKey, PlanEntitlement> planEntitlementsMap() { return planEntitlements; }
    Map<String, AccountAssignment> accountsMap() { return accounts; }
    Map<AccountCapabilityKey, List<AccountOverride>> liveOverridesMap() { return liveOverrides; }

    record PlanCapabilityKey(String planKey, CapabilityKey capabilityKey) {}

    record AccountCapabilityKey(String accountExternalId, CapabilityKey capabilityKey) {}
}
```

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assembles a {@link Snapshot} from scratch — a full DB load or a full replica resync. */
public final class SnapshotBuilder {

    private final Map<CapabilityKey, Capability> capabilities = new HashMap<>();
    private final Map<String, Plan> plans = new HashMap<>();
    private final Map<Snapshot.PlanCapabilityKey, PlanEntitlement> planEntitlements = new HashMap<>();
    private final Map<String, AccountAssignment> accounts = new HashMap<>();
    private final Map<Snapshot.AccountCapabilityKey, List<AccountOverride>> liveOverrides = new HashMap<>();

    public SnapshotBuilder capability(Capability capability) {
        capabilities.put(capability.key(), capability);
        return this;
    }

    public SnapshotBuilder plan(Plan plan) {
        plans.put(plan.key(), plan);
        return this;
    }

    public SnapshotBuilder planEntitlement(PlanEntitlement entitlement) {
        planEntitlements.put(
            new Snapshot.PlanCapabilityKey(entitlement.planKey(), entitlement.capabilityKey()), entitlement);
        return this;
    }

    public SnapshotBuilder account(AccountAssignment account) {
        accounts.put(account.accountExternalId(), account);
        return this;
    }

    public SnapshotBuilder override(AccountOverride override) {
        var key = new Snapshot.AccountCapabilityKey(override.accountExternalId(), override.capabilityKey());
        liveOverrides.computeIfAbsent(key, k -> new ArrayList<>()).add(override);
        return this;
    }

    public Snapshot build(long version) {
        var frozenOverrides = new HashMap<Snapshot.AccountCapabilityKey, List<AccountOverride>>();
        liveOverrides.forEach((key, value) -> frozenOverrides.put(key, List.copyOf(value)));
        return new Snapshot(
            version,
            Map.copyOf(capabilities),
            Map.copyOf(plans),
            Map.copyOf(planEntitlements),
            Map.copyOf(accounts),
            Map.copyOf(frozenOverrides));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=SnapshotBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/view/Snapshot.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotBuilder.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java
git commit -m "feat(entitlement-core): add immutable Snapshot and SnapshotBuilder"
```

---

### Task 9: `engine` package — `Decision`, `Trace` types, `Explanation`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Decision.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/TraceSource.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Outcome.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/TraceEntry.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Trace.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Explanation.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/engine/TraceTest.java`

**Interfaces:**
- Consumes: `EntitlementValue` (Task 2).
- Produces: `Decision(String accountExternalId, String capabilityKey, boolean allowed, EntitlementValue value, long snapshotVersion, Instant evaluatedAt)` — the exact shape `java-client-sdk.md` documents as living in `entitlement-core`. `TraceSource {CAPABILITY_DEFAULT, PLAN}`. `Outcome {WON, LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT, LOST_NOT_MORE_GENEROUS_THAN_PLAN, LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD}` — four values, matching `decision-api.md`'s two separate grant-loss reasons (a grant can lose to another grant, or lose to the plan itself; a hold has only one way to lose). `TraceEntry(TraceSource source, OptionalLong overrideId, Optional<String> planKey, EntitlementValue value, Optional<String> reason, Optional<String> createdBy, Optional<Instant> createdAt, Optional<Outcome> outcome)`. `Trace(TraceEntry baseline, List<TraceEntry> grants, Optional<TraceEntry> grantWinner, List<TraceEntry> holds, Optional<TraceEntry> holdWinner, EntitlementValue result, boolean allowed)`. `Explanation(Decision decision, Trace trace)`. Consumed by `Resolver` (Tasks 10–11).

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TraceTest {

    @Test
    void decisionCarriesTheAnsweredQuestionAndItsSnapshotVersion() {
        var decision = new Decision("acct_9931", "reports.monthly", true,
            EntitlementValue.Quantity.of(50), 48211, Instant.parse("2026-08-09T14:03:11.482Z"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.snapshotVersion()).isEqualTo(48211);
    }

    @Test
    void traceEntryDistinguishesADefaultedZeroFromAnExplicitPlanZero() {
        var defaulted = new TraceEntry(TraceSource.CAPABILITY_DEFAULT, OptionalLong.empty(), Optional.empty(),
            EntitlementValue.Quantity.of(0), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var explicit = new TraceEntry(TraceSource.PLAN, OptionalLong.empty(), Optional.of("pro"),
            EntitlementValue.Quantity.of(0), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(defaulted.source()).isEqualTo(TraceSource.CAPABILITY_DEFAULT);
        assertThat(explicit.source()).isEqualTo(TraceSource.PLAN);
        assertThat(explicit.planKey()).contains("pro");
        assertThat(defaulted).isNotEqualTo(explicit); // c22
    }

    @Test
    void traceCarriesWinnersAndLosersForBothCandidateGroups() {
        var baseline = new TraceEntry(TraceSource.PLAN, OptionalLong.empty(), Optional.of("pro"),
            EntitlementValue.Quantity.of(50), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var winningGrant = new TraceEntry(TraceSource.PLAN, OptionalLong.of(4471), Optional.empty(),
            EntitlementValue.Quantity.of(200), Optional.of("renewal"), Optional.of("j.okafor"),
            Optional.of(Instant.now()), Optional.of(Outcome.WON));
        var losingGrant = new TraceEntry(TraceSource.PLAN, OptionalLong.of(2210), Optional.empty(),
            EntitlementValue.Quantity.of(120), Optional.of("migration"), Optional.of("s.patel"),
            Optional.of(Instant.now()), Optional.of(Outcome.LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT));

        var trace = new Trace(baseline, List.of(winningGrant, losingGrant), Optional.of(winningGrant),
            List.of(), Optional.empty(), EntitlementValue.Quantity.of(200), true);

        assertThat(trace.grants()).hasSize(2);
        assertThat(trace.grantWinner()).contains(winningGrant);
        assertThat(trace.holds()).isEmpty();
        assertThat(trace.holdWinner()).isEmpty();
        assertThat(trace.result()).isEqualTo(EntitlementValue.Quantity.of(200));
    }

    @Test
    void explanationPairsADecisionWithItsTrace() {
        var decision = new Decision("acct_9931", "reports.monthly", true,
            EntitlementValue.Quantity.of(0), 1, Instant.now());
        var baseline = new TraceEntry(TraceSource.CAPABILITY_DEFAULT, OptionalLong.empty(), Optional.empty(),
            EntitlementValue.Quantity.of(0), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var trace = new Trace(baseline, List.of(), Optional.empty(), List.of(), Optional.empty(),
            EntitlementValue.Quantity.of(0), true);

        var explanation = new Explanation(decision, trace);

        assertThat(explanation.decision().value()).isEqualTo(explanation.trace().result());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=TraceTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;

/**
 * §6.1's answer with no explanation attached — what {@code resolve()} returns and what
 * {@link com.solovis.entitlement.client.EntitlementClient} (a downstream module) ships over the
 * wire. Matches the shape documented in {@code contracts/java-client-sdk.md}.
 */
public record Decision(
    String accountExternalId,
    String capabilityKey,
    boolean allowed,
    EntitlementValue value,
    long snapshotVersion,
    Instant evaluatedAt
) {}
```

```java
package com.solovis.entitlement.core.engine;

/** Where a trace entry's baseline value came from — distinguishes a defaulted 0 from a plan 0 (c22). */
public enum TraceSource {
    CAPABILITY_DEFAULT,
    PLAN
}
```

```java
package com.solovis.entitlement.core.engine;

/**
 * Why a candidate override did or did not decide the result (c21, c23). Grants have two distinct
 * loss reasons because a grant can lose either to another grant or to the plan itself; a hold has
 * only one, because the most restrictive hold is always marked {@code WON} in its own list even
 * when it does not change the result — {@code Trace.holdWinner} being empty is what records that
 * (decision-api.md, "Ties are deterministic").
 */
public enum Outcome {
    WON,
    LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT,
    LOST_NOT_MORE_GENEROUS_THAN_PLAN,
    LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD
}
```

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One named step in a {@link Trace}: the baseline, or one candidate GRANT/HOLD. {@code planKey}
 * is present only on a PLAN-sourced baseline; {@code overrideId}/{@code reason}/{@code
 * createdBy}/{@code createdAt}/{@code outcome} are present only on a GRANT/HOLD candidate.
 */
public record TraceEntry(
    TraceSource source,
    OptionalLong overrideId,
    Optional<String> planKey,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt,
    Optional<Outcome> outcome
) {}
```

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.util.List;
import java.util.Optional;

/**
 * The full §6.1 explanation: baseline, every candidate GRANT and HOLD with its outcome, which
 * one (if any) won each group, and the result. Empty {@code grants}/{@code holds} together with
 * an empty winner is how "no grants at all" is distinguished from "grants existed but the plan
 * already beat them" — the caller building the wire {@code why} field reads exactly that.
 */
public record Trace(
    TraceEntry baseline,
    List<TraceEntry> grants,
    Optional<TraceEntry> grantWinner,
    List<TraceEntry> holds,
    Optional<TraceEntry> holdWinner,
    EntitlementValue result,
    boolean allowed
) {}
```

```java
package com.solovis.entitlement.core.engine;

/**
 * A decision with its full trace (spec §6.1). Produced only by {@link Resolver#explain}, in the
 * management service — see {@code plan.md}, "Recorded interpretations" (c21, c24).
 */
public record Explanation(Decision decision, Trace trace) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=TraceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Decision.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/TraceSource.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Outcome.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/TraceEntry.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Trace.java \
        management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Explanation.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/engine/TraceTest.java
git commit -m "feat(entitlement-core): add Decision, Trace and Explanation shapes"
```

---

### Task 10: `Resolver.resolve()`

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Resolver.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverResolveTest.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverOrderIndependencePropertyTest.java`

**Interfaces:**
- Consumes: `EntitlementView`, `Snapshot`, `SnapshotBuilder` (Tasks 7–8); `Capability`, `AccountOverride`, `OverrideKind`, `EntitlementValue`, `CapabilityKey` (model); `Generosity` (Task 3); `Decision` (Task 9); `UnknownAccountException`, `UnknownCapabilityException`, `RetiredCapabilityException` (Task 1).
- Produces: `Resolver.resolve(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt): Decision`. Task 11 adds `Resolver.explain(...)` to the same class, sharing this method's arithmetic.

- [ ] **Step 1: Write the failing test — the §5 worked-examples table plus criteria 10–20**

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolverResolveTest {

    private static final CapabilityKey API_ACCESS = new CapabilityKey("api.access");
    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");
    private static final CapabilityKey SEATS = new CapabilityKey("seats.count");
    private static final CapabilityKey SUPPORT = new CapabilityKey("support.tier");
    private static final CapabilityKey SLA = new CapabilityKey("sla.tier");
    private static final Instant NOW = Instant.parse("2026-08-09T14:03:11.482Z");

    private static Capability switchCapability(CapabilityKey key) {
        return new Capability(key, key.value(), null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private static Capability quantityCapability(CapabilityKey key, EntitlementValue defaultValue) {
        return new Capability(key, key.value(), null, ValueType.QUANTITY,
            defaultValue, Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private static AccountOverride grant(CapabilityKey key, long id, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", key, OverrideKind.GRANT, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(NOW));
    }

    private static AccountOverride hold(CapabilityKey key, long id, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", key, OverrideKind.HOLD, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(NOW));
    }

    // §5 worked examples, transcribed literally.

    @Test
    void switchFalsePlanNoOverridesIsDisallowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("free", API_ACCESS, new EntitlementValue.Switch(false)))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.value()).isEqualTo(new EntitlementValue.Switch(false));
    }

    @Test
    void switchFalsePlanWithGrantTrueIsAllowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("free", API_ACCESS, new EntitlementValue.Switch(false)))
            .account(new AccountAssignment("acct_1", "free"))
            .override(grant(API_ACCESS, 1, new EntitlementValue.Switch(true)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW).allowed()).isTrue();
    }

    @Test
    void switchTruePlanWithHoldFalseIsDisallowed() {
        var snapshot = new SnapshotBuilder()
            .capability(switchCapability(API_ACCESS))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("pro", API_ACCESS, new EntitlementValue.Switch(true)))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(hold(API_ACCESS, 1, new EntitlementValue.Switch(false)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", API_ACCESS, NOW).allowed()).isFalse();
    }

    @Test
    void unmentionedQuantityResolvesToTheCapabilityDefault() {
        var snapshot = new SnapshotBuilder()
            .capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", REPORTS, NOW);
        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(decision.allowed()).isTrue(); // no off-value declared (c10)
    }

    @Test
    void grantMoreGenerousThanPlanWins() {
        var snapshot = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(200));
    }

    @Test
    void holdAfterGrantSuppressesTheCapability() {
        var snapshot = reportsSnapshotWithPlanValue(50)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200)))
            .override(hold(REPORTS, 2, EntitlementValue.Quantity.of(0)))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", REPORTS, NOW);
        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(decision.allowed()).isTrue(); // no off-value declared — 0 is a legitimate quantity (§5)
    }

    @Test
    void planBeatsASmallerGrant() {
        var snapshot = reportsSnapshotWithPlanValue(150)
            .override(grant(REPORTS, 1, EntitlementValue.Quantity.of(100)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(150));
    }

    @Test
    void unlimitedPlanCappedByAFiniteHold() {
        var snapshot = new SnapshotBuilder()
            .capability(quantityCapability(SEATS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("enterprise", "Enterprise", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("enterprise", SEATS, EntitlementValue.Quantity.unbounded()))
            .account(new AccountAssignment("acct_1", "enterprise"))
            .override(hold(SEATS, 1, EntitlementValue.Quantity.of(100)))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", SEATS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(100));
    }

    @Test
    void tierWithNoOffValueIsAllowed() {
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var capability = new Capability(SUPPORT, "Support", null, ValueType.TIER,
            new EntitlementValue.Tier("community", 0), Optional.empty(), tiers, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", SUPPORT, NOW);
        assertThat(decision.value()).isEqualTo(new EntitlementValue.Tier("community", 0));
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void tierEqualToItsOffValueIsDisallowed() {
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("none", 0, "None"),
            new TierOrder.TierDefinition("standard", 1, "Standard")));
        var capability = new Capability(SLA, "SLA", null, ValueType.TIER,
            new EntitlementValue.Tier("none", 0),
            Optional.of(new OffValue(new EntitlementValue.Tier("none", 0))),
            tiers, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        assertThat(Resolver.resolve(snapshot, "acct_1", SLA, NOW).allowed()).isFalse();
    }

    // Errors are errors, never denials (c19).

    @Test
    void unknownAccountThrows() {
        var snapshot = new SnapshotBuilder().capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0))).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_missing", REPORTS, NOW))
            .isInstanceOf(UnknownAccountException.class);
    }

    @Test
    void unknownCapabilityThrows() {
        var snapshot = new SnapshotBuilder().account(new AccountAssignment("acct_1", "free")).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_1", REPORTS, NOW))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void retiredCapabilityThrows() {
        var retired = new Capability(REPORTS, "Reports", null, ValueType.QUANTITY, EntitlementValue.Quantity.of(0),
            Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED, NOW);
        var snapshot = new SnapshotBuilder().capability(retired).account(new AccountAssignment("acct_1", "free")).build(1);
        assertThatThrownBy(() -> Resolver.resolve(snapshot, "acct_1", REPORTS, NOW))
            .isInstanceOf(RetiredCapabilityException.class);
    }

    @Test
    void removingAGrantRestoresThePlanValueWithNoFurtherAction() {
        var withGrant = reportsSnapshotWithPlanValue(50).override(grant(REPORTS, 1, EntitlementValue.Quantity.of(200))).build(1);
        var withoutGrant = reportsSnapshotWithPlanValue(50).build(2);

        assertThat(Resolver.resolve(withGrant, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(200));
        assertThat(Resolver.resolve(withoutGrant, "acct_1", REPORTS, NOW).value()).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    private static SnapshotBuilder reportsSnapshotWithPlanValue(long amount) {
        return new SnapshotBuilder()
            .capability(quantityCapability(REPORTS, EntitlementValue.Quantity.of(0)))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(amount)))
            .account(new AccountAssignment("acct_1", "pro"));
    }
}
```

Now write the property test for order independence (c12, c13, c16):

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * c12/c13/c16: evaluating the same state twice, with the overrides shuffled into any order,
 * produces the same result. Grant/hold amounts are randomised too so the property does not
 * accidentally hold only because every generated case ties.
 */
class ResolverOrderIndependencePropertyTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");

    @Property
    void resolutionIsInvariantUnderPermutationOfOverrides(
        @ForAll @LongRange(min = 0, max = 1000) long planAmount,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> grantAmounts,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> holdAmounts) {

        var overrides = new java.util.ArrayList<AccountOverride>();
        long id = 1;
        for (int amount : grantAmounts) {
            overrides.add(override(id++, OverrideKind.GRANT, amount));
        }
        for (int amount : holdAmounts) {
            overrides.add(override(id++, OverrideKind.HOLD, amount));
        }

        var baseline = resolveWith(planAmount, overrides);

        for (int trial = 0; trial < 5; trial++) {
            var shuffled = new java.util.ArrayList<>(overrides);
            Collections.shuffle(shuffled);
            assertThat(resolveWith(planAmount, shuffled)).isEqualTo(baseline);
        }
    }

    private static AccountOverride override(long id, OverrideKind kind, int amount) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", REPORTS, kind, EntitlementValue.Quantity.of(amount),
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static EntitlementValue resolveWith(long planAmount, List<AccountOverride> overrides) {
        var builder = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(planAmount)))
            .account(new AccountAssignment("acct_1", "pro"));
        overrides.forEach(builder::override);
        return Resolver.resolve(builder.build(1), "acct_1", REPORTS, Instant.now()).value();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ResolverResolveTest,ResolverOrderIndependencePropertyTest`
Expected: FAIL to compile — `Resolver` does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.order.Generosity;
import com.solovis.entitlement.core.view.EntitlementView;
import java.time.Instant;
import java.util.List;

/**
 * Implements spec §4's rule: the effective value is the most generous of the plan and its
 * GRANTs, then capped by the strictest HOLD. {@link #resolve} is the hot path — no trace is
 * built, so nothing beyond the running winner is allocated. {@link #explain} runs the identical
 * arithmetic and layers a full {@link Trace} on top (Task 11), so the two can never disagree
 * (c24).
 */
public final class Resolver {

    private Resolver() {}

    public static Decision resolve(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var account = view.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var capability = view.capability(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey.value()));
        if (capability.isRetired()) {
            throw new RetiredCapabilityException(capabilityKey.value());
        }

        EntitlementValue baseline = view.planEntitlement(account.planKey(), capabilityKey)
            .map(pe -> pe.value())
            .orElse(capability.defaultValue());

        List<AccountOverride> overrides = view.liveOverrides(accountExternalId, capabilityKey);

        EntitlementValue afterGrants = baseline;
        for (var override : overrides) {
            if (override.kind() == OverrideKind.GRANT) {
                afterGrants = Generosity.mostGenerous(afterGrants, override.value());
            }
        }

        EntitlementValue result = afterGrants;
        for (var override : overrides) {
            if (override.kind() == OverrideKind.HOLD) {
                result = Generosity.mostRestrictive(result, override.value());
            }
        }

        // capture a final copy — `result` above is reassigned in the loop, and javac rejects a
        // lambda closing over a variable that isn't effectively final.
        EntitlementValue finalResult = result;
        boolean allowed = capability.effectiveOffValue()
            .map(off -> !off.equals(finalResult))
            .orElse(true);

        return new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ResolverResolveTest,ResolverOrderIndependencePropertyTest`
Expected: PASS. jqwik's `@Property` runs 1000 generated cases by default (each with 5 shuffles) — if it fails, jqwik prints the failing seed and sample so it is reproducible.

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Resolver.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverResolveTest.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverOrderIndependencePropertyTest.java
git commit -m "feat(entitlement-core): implement Resolver.resolve() per spec §4/§5"
```

---

### Task 11: `Resolver.explain()`

**Files:**
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Resolver.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverExplainTest.java`

**Interfaces:**
- Consumes: everything Task 10 consumes, plus `Trace`, `TraceEntry`, `TraceSource`, `Outcome`, `Explanation` (Task 9).
- Produces: `Resolver.explain(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt): Explanation`, added to the same `Resolver` class as `resolve()`.

- [ ] **Step 1: Write the failing test — mirrors `decision-api.md`'s worked example and criteria 21–24**

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResolverExplainTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");
    private static final Instant NOW = Instant.parse("2026-08-09T14:03:11.482Z");

    // Transcribes decision-api.md's worked example: plan 50, grants 200 (wins) and 120 (loses),
    // hold 0 (wins) — matching the criteria-21-through-24 trace shape.
    @Test
    void tracesTheFullDecisionApiWorkedExample() {
        var winningGrant = new AccountOverride(OptionalLong.of(4471), "acct_9931", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("Renewal concession — Q3 pilot"),
            Optional.of("j.okafor"), Optional.of(Instant.parse("2026-06-02T09:12:44.000Z")));
        var losingGrant = new AccountOverride(OptionalLong.of(2210), "acct_9931", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(120), Optional.of("Migration goodwill"),
            Optional.of("s.patel"), Optional.of(Instant.parse("2026-03-18T16:40:02.000Z")));
        var winningHold = new AccountOverride(OptionalLong.of(7788), "acct_9931", REPORTS, OverrideKind.HOLD,
            EntitlementValue.Quantity.of(0), Optional.of("Suspended pending billing investigation"),
            Optional.of("billing-bot"), Optional.of(Instant.parse("2026-08-01T02:00:00.000Z")));

        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_9931", "pro"))
            .override(winningGrant)
            .override(losingGrant)
            .override(winningHold)
            .build(48211);

        var explanation = Resolver.explain(snapshot, "acct_9931", REPORTS, NOW);

        assertThat(explanation.trace().baseline().source()).isEqualTo(TraceSource.PLAN);
        assertThat(explanation.trace().baseline().planKey()).contains("pro");
        assertThat(explanation.trace().baseline().value()).isEqualTo(EntitlementValue.Quantity.of(50));

        assertThat(explanation.trace().grants()).hasSize(2);
        assertThat(explanation.trace().grantWinner()).isPresent();
        assertThat(explanation.trace().grantWinner().get().overrideId()).isEqualTo(OptionalLong.of(4471));
        assertThat(explanation.trace().grantWinner().get().outcome()).contains(Outcome.WON);
        var losingEntry = explanation.trace().grants().stream()
            .filter(entry -> entry.overrideId().equals(OptionalLong.of(2210))).findFirst().orElseThrow();
        assertThat(losingEntry.outcome()).contains(Outcome.LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT);

        assertThat(explanation.trace().holds()).hasSize(1);
        assertThat(explanation.trace().holdWinner()).isPresent();
        assertThat(explanation.trace().holdWinner().get().overrideId()).isEqualTo(OptionalLong.of(7788));

        assertThat(explanation.trace().result()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(explanation.decision().value()).isEqualTo(explanation.trace().result()); // resolve()/explain() agree
        assertThat(explanation.decision().allowed()).isEqualTo(explanation.trace().allowed());
    }

    @Test
    void deniesByAbsenceExplicitlyWhenNothingIsMentioned() {
        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", REPORTS, NOW);

        assertThat(explanation.trace().baseline().source()).isEqualTo(TraceSource.CAPABILITY_DEFAULT);
        assertThat(explanation.trace().grants()).isEmpty();
        assertThat(explanation.trace().grantWinner()).isEmpty();
        assertThat(explanation.trace().holds()).isEmpty();
        assertThat(explanation.trace().holdWinner()).isEmpty();
    }

    @Test
    void tiedGrantsAreWonByTheHighestOverrideId() {
        var older = new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("r1"), Optional.of("a1"), Optional.of(NOW));
        var newer = new AccountOverride(OptionalLong.of(2), "acct_1", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("r2"), Optional.of("a2"), Optional.of(NOW));

        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .override(older)
            .override(newer)
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", REPORTS, NOW);
        assertThat(explanation.trace().grantWinner().get().overrideId()).isEqualTo(OptionalLong.of(2)); // newest wins the label
    }

    @Test
    void grantThatDoesNotBeatThePlanIsMarkedLostNotMoreGenerousThanPlan() {
        var onlyGrant = new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(100), Optional.of("r"), Optional.of("a"), Optional.of(NOW));

        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(150)))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(onlyGrant)
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", REPORTS, NOW);

        assertThat(explanation.trace().grantWinner()).isEmpty(); // the plan stands — no grant displaced it (c11)
        assertThat(explanation.trace().grants()).singleElement()
            .extracting(TraceEntry::outcome).isEqualTo(Optional.of(Outcome.LOST_NOT_MORE_GENEROUS_THAN_PLAN));
        assertThat(explanation.trace().result()).isEqualTo(EntitlementValue.Quantity.of(150));
    }

    @Test
    void theMostRestrictiveHoldIsMarkedWonEvenWhenItDoesNotChangeTheResult() {
        var harmlessHold = new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.HOLD,
            EntitlementValue.Quantity.unbounded(), Optional.of("contract floor"), Optional.of("a"), Optional.of(NOW));

        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(harmlessHold)
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", REPORTS, NOW);

        assertThat(explanation.trace().holdWinner()).isEmpty(); // holdStep did not apply — it changed nothing
        assertThat(explanation.trace().holds()).singleElement()
            .extracting(TraceEntry::outcome).isEqualTo(Optional.of(Outcome.WON)); // the entry itself is still WON
        assertThat(explanation.trace().result()).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void resolveAndExplainAlwaysAgreeOnTheValue() {
        var snapshot = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT,
                EntitlementValue.Quantity.of(200), Optional.of("r"), Optional.of("a"), Optional.of(NOW)))
            .build(1);

        var decision = Resolver.resolve(snapshot, "acct_1", REPORTS, NOW);
        var explanation = Resolver.explain(snapshot, "acct_1", REPORTS, NOW);

        assertThat(decision).isEqualTo(explanation.decision());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ResolverExplainTest`
Expected: FAIL to compile — `Resolver.explain` does not exist yet.

- [ ] **Step 3: Extend the implementation**

Refactor `Resolver` so `resolve()` and `explain()` share one internal computation, then add `explain()`:

```java
package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.order.Generosity;
import com.solovis.entitlement.core.view.EntitlementView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Implements spec §4's rule: the effective value is the most generous of the plan and its
 * GRANTs, then capped by the strictest HOLD. {@link #resolve} is the hot path — no trace is
 * built, so nothing beyond the running winner is allocated. {@link #explain} runs the identical
 * arithmetic and layers a full {@link Trace} on top, so the two can never disagree (c24).
 */
public final class Resolver {

    private Resolver() {}

    public static Decision resolve(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var lookup = lookUp(view, accountExternalId, capabilityKey);

        EntitlementValue afterGrants = lookup.baseline;
        for (var override : lookup.overrides) {
            if (override.kind() == OverrideKind.GRANT) {
                afterGrants = Generosity.mostGenerous(afterGrants, override.value());
            }
        }

        EntitlementValue result = afterGrants;
        for (var override : lookup.overrides) {
            if (override.kind() == OverrideKind.HOLD) {
                result = Generosity.mostRestrictive(result, override.value());
            }
        }

        boolean allowed = computeAllowed(lookup.capability, result);
        return new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
    }

    public static Explanation explain(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var lookup = lookUp(view, accountExternalId, capabilityKey);

        var baselineEntry = new TraceEntry(
            lookup.baselineSource, OptionalLong.empty(), lookup.baselinePlanKey,
            lookup.baseline, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Grants: the top candidate is WON only if it actually beats the plan (c11) — otherwise
        // it is LOST_NOT_MORE_GENEROUS_THAN_PLAN, a reason distinct from losing to another grant.
        var grantCandidates = candidatesOfKind(lookup.overrides, OverrideKind.GRANT);
        var topGrant = pickWinner(grantCandidates, true);
        boolean grantApplied = topGrant.isPresent() && Generosity.compare(topGrant.get().value(), lookup.baseline) > 0;
        EntitlementValue afterGrants = grantApplied ? topGrant.get().value() : lookup.baseline;
        var grants = grantTraceEntries(grantCandidates, topGrant, grantApplied);
        Optional<TraceEntry> grantWinnerEntry = grantApplied
            ? grants.stream().filter(e -> e.overrideId().equals(topGrant.get().id())).findFirst()
            : Optional.empty();

        // Holds: the most restrictive candidate is always marked WON in its own list, even when
        // it does not change the result — holdWinner (below) being empty is what records that it
        // did not apply (decision-api.md, "Ties are deterministic").
        var holdCandidates = candidatesOfKind(lookup.overrides, OverrideKind.HOLD);
        var topHold = pickWinner(holdCandidates, false);
        boolean holdApplied = topHold.isPresent() && Generosity.compare(topHold.get().value(), afterGrants) < 0;
        EntitlementValue result = holdApplied ? topHold.get().value() : afterGrants;
        var holds = holdTraceEntries(holdCandidates, topHold);
        Optional<TraceEntry> holdWinnerEntry = holdApplied
            ? holds.stream().filter(e -> e.overrideId().equals(topHold.get().id())).findFirst()
            : Optional.empty();

        boolean allowed = computeAllowed(lookup.capability, result);
        var trace = new Trace(baselineEntry, grants, grantWinnerEntry, holds, holdWinnerEntry, result, allowed);
        var decision = new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
        return new Explanation(decision, trace);
    }

    private static boolean computeAllowed(Capability capability, EntitlementValue result) {
        return capability.effectiveOffValue().map(off -> !off.equals(result)).orElse(true);
    }

    private static Lookup lookUp(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey) {
        AccountAssignment account = view.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        Capability capability = view.capability(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey.value()));
        if (capability.isRetired()) {
            throw new RetiredCapabilityException(capabilityKey.value());
        }

        var planEntitlement = view.planEntitlement(account.planKey(), capabilityKey);
        EntitlementValue baseline = planEntitlement.map(pe -> pe.value()).orElse(capability.defaultValue());
        TraceSource baselineSource = planEntitlement.isPresent() ? TraceSource.PLAN : TraceSource.CAPABILITY_DEFAULT;
        Optional<String> baselinePlanKey = planEntitlement.isPresent() ? Optional.of(account.planKey()) : Optional.empty();

        List<AccountOverride> overrides = view.liveOverrides(accountExternalId, capabilityKey);
        return new Lookup(capability, baseline, baselineSource, baselinePlanKey, overrides);
    }

    private static List<AccountOverride> candidatesOfKind(List<AccountOverride> overrides, OverrideKind kind) {
        var result = new ArrayList<AccountOverride>();
        for (var override : overrides) {
            if (override.kind() == kind) {
                result.add(override);
            }
        }
        return result;
    }

    /**
     * The single candidate that decides its group (most generous for grants, most restrictive
     * for holds), or empty if none exist. Ties on the deciding value are marked won by the
     * highest override id — a presentational rule that never changes the computed value
     * (decision-api.md, "Ties are deterministic"). Whether this candidate actually changes the
     * result (beats the plan, or restricts below the post-grant value) is a separate question,
     * decided by the caller against the baseline it is being compared to.
     */
    private static Optional<AccountOverride> pickWinner(List<AccountOverride> candidates, boolean generous) {
        AccountOverride winner = null;
        for (var candidate : candidates) {
            if (winner == null) {
                winner = candidate;
                continue;
            }
            int cmp = Generosity.compare(candidate.value(), winner.value());
            boolean better = generous ? cmp > 0 : cmp < 0;
            boolean tiedNewer = cmp == 0 && candidate.id().orElse(Long.MIN_VALUE) > winner.id().orElse(Long.MIN_VALUE);
            if (better || tiedNewer) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }

    /**
     * A grant has two distinct ways to lose: to another grant, or to the plan itself. The top
     * candidate gets {@code WON} only if it actually beat the plan ({@code applied}); every other
     * candidate lost to it and is marked accordingly (c23 — denial explained as fully as a grant).
     */
    private static List<TraceEntry> grantTraceEntries(List<AccountOverride> candidates, Optional<AccountOverride> top, boolean applied) {
        var entries = new ArrayList<TraceEntry>();
        for (var candidate : candidates) {
            boolean isTop = top.isPresent() && top.get().id().equals(candidate.id());
            Outcome outcome = isTop
                ? (applied ? Outcome.WON : Outcome.LOST_NOT_MORE_GENEROUS_THAN_PLAN)
                : Outcome.LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT;
            entries.add(toTraceEntry(candidate, outcome));
        }
        return entries;
    }

    /**
     * The most restrictive HOLD is always {@code WON} within its own list, even when it does not
     * change the result — {@code holdWinner} being empty on {@link Trace} is what records that it
     * did not apply (decision-api.md, "Ties are deterministic").
     */
    private static List<TraceEntry> holdTraceEntries(List<AccountOverride> candidates, Optional<AccountOverride> top) {
        var entries = new ArrayList<TraceEntry>();
        for (var candidate : candidates) {
            boolean isTop = top.isPresent() && top.get().id().equals(candidate.id());
            Outcome outcome = isTop ? Outcome.WON : Outcome.LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD;
            entries.add(toTraceEntry(candidate, outcome));
        }
        return entries;
    }

    private static TraceEntry toTraceEntry(AccountOverride candidate, Outcome outcome) {
        return new TraceEntry(
            TraceSource.PLAN, candidate.id(), Optional.empty(), candidate.value(),
            candidate.reason(), candidate.createdBy(), candidate.createdAt(), Optional.of(outcome));
    }

    private record Lookup(
        Capability capability,
        EntitlementValue baseline,
        TraceSource baselineSource,
        Optional<String> baselinePlanKey,
        List<AccountOverride> overrides) {}
}
```

Note on `toTraceEntry`'s `TraceSource.PLAN` argument: `TraceSource` on a GRANT/HOLD entry is not meaningful (it only distinguishes baseline provenance) — leave it `PLAN` as a placeholder value for now; downstream JSON mapping in `entitlement-service` (a later module, out of this plan's scope) reads `overrideId`/`reason`/`outcome` from these entries and does not read `source` off them. If this bothers a reviewer, it is cheap to widen `TraceSource` with a third `OVERRIDE` member in a later task — noted here rather than silently left.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ResolverExplainTest,ResolverResolveTest,ResolverOrderIndependencePropertyTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/engine/Resolver.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/engine/ResolverExplainTest.java
git commit -m "feat(entitlement-core): implement Resolver.explain() sharing resolve()'s arithmetic"
```

---

### Task 12: `SnapshotMutator` — structural sharing

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotMutator.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotMutatorTest.java`

**Interfaces:**
- Consumes: `Snapshot` (Task 8, its package-private map accessors), `Capability`, `PlanEntitlement`, `AccountAssignment`, `AccountOverride`, `CapabilityKey` (model).
- Produces: `SnapshotMutator.withCapability`, `.withPlanEntitlement`, `.withAccount`, `.withOverrideAdded`, `.withOverrideRemoved` — each `(Snapshot base, long newVersion, ...) -> Snapshot`. Not consumed elsewhere in this plan; this is the seam `entitlement-service`'s write path (a later module) uses on every commit.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotMutatorTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");

    private static Capability reportsCapability() {
        return new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    }

    private Snapshot baseSnapshot() {
        return new SnapshotBuilder()
            .capability(reportsCapability())
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .build(1);
    }

    @Test
    void withPlanEntitlementReplacesOnlyTheChangedValueAndBumpsTheVersion() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withPlanEntitlement(
            base, 2, new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));

        assertThat(updated.snapshotVersion()).isEqualTo(2);
        assertThat(updated.planEntitlement("pro", REPORTS)).contains(
            new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));
        // The base snapshot is untouched — readers holding it never see the change (c31).
        assertThat(base.planEntitlement("pro", REPORTS)).contains(
            new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)));
    }

    @Test
    void withPlanEntitlementReusesUnrelatedMapsByReference() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withPlanEntitlement(
            base, 2, new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(999)));

        assertThat(updated.capabilitiesMap()).isSameAs(base.capabilitiesMap());
        assertThat(updated.accountsMap()).isSameAs(base.accountsMap());
        assertThat(updated.liveOverridesMap()).isSameAs(base.liveOverridesMap());
        assertThat(updated.planEntitlementsMap()).isNotSameAs(base.planEntitlementsMap());
    }

    @Test
    void withOverrideAddedAppendsToTheAccountCapabilityBucketOnly() {
        var base = baseSnapshot();
        var override = new AccountOverride(OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("goodwill"), Optional.of("actor"), Optional.empty());

        var updated = SnapshotMutator.withOverrideAdded(base, 2, override);

        assertThat(updated.liveOverrides("acct_1", REPORTS)).containsExactly(override);
        assertThat(base.liveOverrides("acct_1", REPORTS)).isEmpty();
        assertThat(updated.planEntitlementsMap()).isSameAs(base.planEntitlementsMap());
    }

    @Test
    void withOverrideRemovedDropsOnlyTheNamedOverride() {
        var withOverride = SnapshotMutator.withOverrideAdded(baseSnapshot(), 2, new AccountOverride(
            OptionalLong.of(1), "acct_1", REPORTS, OverrideKind.GRANT, EntitlementValue.Quantity.of(200),
            Optional.of("goodwill"), Optional.of("actor"), Optional.empty()));

        var updated = SnapshotMutator.withOverrideRemoved(withOverride, 3, "acct_1", REPORTS, 1);

        assertThat(updated.liveOverrides("acct_1", REPORTS)).isEmpty();
        assertThat(withOverride.liveOverrides("acct_1", REPORTS)).hasSize(1); // prior version untouched
    }

    @Test
    void withAccountReplacesThePlanAssignment() {
        var base = baseSnapshot();
        var updated = SnapshotMutator.withAccount(base, 2, new AccountAssignment("acct_1", "enterprise"));

        assertThat(updated.account("acct_1")).contains(new AccountAssignment("acct_1", "enterprise"));
        assertThat(base.account("acct_1")).contains(new AccountAssignment("acct_1", "pro"));
    }

    @Test
    void withCapabilityReplacesTheRegistryEntry() {
        var base = baseSnapshot();
        var retired = new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED,
            java.time.Instant.now());

        var updated = SnapshotMutator.withCapability(base, 2, retired);

        assertThat(updated.capability(REPORTS)).get().extracting(Capability::isRetired).isEqualTo(true);
        assertThat(base.capability(REPORTS)).get().extracting(Capability::isRetired).isEqualTo(false);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=SnapshotMutatorTest`
Expected: FAIL — `SnapshotMutator` does not exist, and `Snapshot`'s map accessors are package-private (test is in the same `view` package, so that part compiles once the accessors from Task 8 are in place).

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.view;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.PlanEntitlement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces version N+1 of a {@link Snapshot} from version N and one change, rebuilding only the
 * map the change touches and reusing every other map by reference (research.md §8) — readers
 * holding the old {@link Snapshot} instance never observe the new one, and the swap the caller
 * performs on an {@code AtomicReference} is therefore always atomic from a reader's perspective.
 */
public final class SnapshotMutator {

    private SnapshotMutator() {}

    public static Snapshot withCapability(Snapshot base, long newVersion, Capability capability) {
        var capabilities = new HashMap<>(base.capabilitiesMap());
        capabilities.put(capability.key(), capability);
        return new Snapshot(newVersion, Map.copyOf(capabilities), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withPlanEntitlement(Snapshot base, long newVersion, PlanEntitlement entitlement) {
        var planEntitlements = new HashMap<>(base.planEntitlementsMap());
        planEntitlements.put(
            new Snapshot.PlanCapabilityKey(entitlement.planKey(), entitlement.capabilityKey()), entitlement);
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            Map.copyOf(planEntitlements), base.accountsMap(), base.liveOverridesMap());
    }

    public static Snapshot withAccount(Snapshot base, long newVersion, AccountAssignment account) {
        var accounts = new HashMap<>(base.accountsMap());
        accounts.put(account.accountExternalId(), account);
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), Map.copyOf(accounts), base.liveOverridesMap());
    }

    public static Snapshot withOverrideAdded(Snapshot base, long newVersion, AccountOverride override) {
        var key = new Snapshot.AccountCapabilityKey(override.accountExternalId(), override.capabilityKey());
        var overrides = new HashMap<>(base.liveOverridesMap());
        var bucket = new ArrayList<>(overrides.getOrDefault(key, List.of()));
        bucket.add(override);
        overrides.put(key, List.copyOf(bucket));
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), Map.copyOf(overrides));
    }

    public static Snapshot withOverrideRemoved(
        Snapshot base, long newVersion, String accountExternalId, CapabilityKey capabilityKey, long overrideId) {
        var key = new Snapshot.AccountCapabilityKey(accountExternalId, capabilityKey);
        var overrides = new HashMap<>(base.liveOverridesMap());
        var remaining = overrides.getOrDefault(key, List.of()).stream()
            .filter(o -> !o.id().equals(java.util.OptionalLong.of(overrideId)))
            .toList();
        overrides.put(key, remaining);
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            base.planEntitlementsMap(), base.accountsMap(), Map.copyOf(overrides));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=SnapshotMutatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotMutator.java \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotMutatorTest.java
git commit -m "feat(entitlement-core): add SnapshotMutator with structural sharing"
```

---

### Task 13: `conformance` package — worked examples as vectors

**Files:**
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/conformance/ResolverContract.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/conformance/ConformanceVector.java`
- Create: `entitlement-core/src/main/java/com/solovis/entitlement/core/conformance/ConformanceCheck.java`
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/conformance/ConformanceCheckTest.java`

**Interfaces:**
- Consumes: `Snapshot`, `SnapshotBuilder`, `EntitlementView` (Task 8); `Resolver` (Tasks 10–11); model types.
- Produces: `ResolverContract.VERSION` (an `int`, bumped only when §4's rule changes — research.md §20); `ConformanceVector(String name, Snapshot fixture, String accountExternalId, CapabilityKey capabilityKey, boolean expectedAllowed, EntitlementValue expectedValue)`; `ConformanceVector.spec5WorkedExamples(): List<ConformanceVector>` (the §5 table, transcribed); `ConformanceCheck.run(List<ConformanceVector>): ConformanceResult` with `record ConformanceResult(boolean passed, List<String> failures)`. This is what a future `entitlement-client` replica evaluates at startup (research.md §20) — not consumed elsewhere in this plan.

- [ ] **Step 1: Write the failing test**

```java
package com.solovis.entitlement.core.conformance;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConformanceCheckTest {

    @Test
    void everySpec5WorkedExamplePasses() {
        var vectors = ConformanceVector.spec5WorkedExamples();
        assertThat(vectors).isNotEmpty();

        var result = ConformanceCheck.run(vectors);

        assertThat(result.failures()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void reportsWhichVectorFailedRatherThanJustThatSomethingDid() {
        var badFixture = new com.solovis.entitlement.core.view.SnapshotBuilder()
            .capability(new com.solovis.entitlement.core.model.Capability(
                new com.solovis.entitlement.core.model.CapabilityKey("api.access"), "API access", null,
                com.solovis.entitlement.core.model.ValueType.SWITCH,
                new com.solovis.entitlement.core.model.EntitlementValue.Switch(false),
                java.util.Optional.empty(), com.solovis.entitlement.core.model.TierOrder.NONE,
                com.solovis.entitlement.core.model.Capability.Status.ACTIVE, null))
            .plan(new com.solovis.entitlement.core.model.Plan("free", "Free",
                com.solovis.entitlement.core.model.Plan.Status.ACTIVE, true))
            .planEntitlement(new com.solovis.entitlement.core.model.PlanEntitlement("free",
                new com.solovis.entitlement.core.model.CapabilityKey("api.access"),
                new com.solovis.entitlement.core.model.EntitlementValue.Switch(false)))
            .account(new com.solovis.entitlement.core.model.AccountAssignment("acct_1", "free"))
            .build(1);

        var deliberatelyWrong = new ConformanceVector(
            "deliberately wrong", badFixture, "acct_1",
            new com.solovis.entitlement.core.model.CapabilityKey("api.access"),
            true, // actual is false — this vector should fail
            new com.solovis.entitlement.core.model.EntitlementValue.Switch(false));

        var result = ConformanceCheck.run(java.util.List.of(deliberatelyWrong));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).singleElement().asString().contains("deliberately wrong");
    }

    @Test
    void resolverContractIsAPositiveVersion() {
        assertThat(ResolverContract.VERSION).isGreaterThan(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ConformanceCheckTest`
Expected: FAIL to compile.

- [ ] **Step 3: Write minimal implementation**

```java
package com.solovis.entitlement.core.conformance;

/**
 * The resolution semantics of spec §4, versioned separately from the wire format (research.md
 * §20). Bump only when the rule itself changes — {@code future-spec.md} §1 (time-bounded
 * overrides) and §5 (relative grants) both would.
 */
public final class ResolverContract {

    public static final int VERSION = 1;

    private ResolverContract() {}
}
```

```java
package com.solovis.entitlement.core.conformance;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.util.List;
import java.util.Optional;

/**
 * A self-contained (model fragment → expected allowed, value) case a replica evaluates against
 * its own engine at startup, refusing to serve on any mismatch (research.md §20). {@link
 * #spec5WorkedExamples()} transcribes the spec's own worked-examples table literally, so the
 * specification's examples are executable rather than merely illustrative.
 */
public record ConformanceVector(
    String name,
    Snapshot fixture,
    String accountExternalId,
    CapabilityKey capabilityKey,
    boolean expectedAllowed,
    EntitlementValue expectedValue
) {

    public static List<ConformanceVector> spec5WorkedExamples() {
        var vectors = new java.util.ArrayList<ConformanceVector>();

        vectors.add(switchVector("api.access: plan false, no overrides -> false",
            new EntitlementValue.Switch(false), List.of(), false, new EntitlementValue.Switch(false)));

        vectors.add(switchVector("api.access: plan false, grant true -> true",
            new EntitlementValue.Switch(false),
            List.of(grant("api.access", 1, new EntitlementValue.Switch(true))),
            true, new EntitlementValue.Switch(true)));

        vectors.add(switchVector("api.access: plan true, hold false -> false",
            new EntitlementValue.Switch(true),
            List.of(hold("api.access", 1, new EntitlementValue.Switch(false))),
            false, new EntitlementValue.Switch(false)));

        vectors.add(quantityVector("reports.monthly: unmentioned -> capability default 0, allowed",
            Optional.empty(), List.of(), true, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly: plan 50, no overrides -> 50",
            Optional.of(50L), List.of(), true, EntitlementValue.Quantity.of(50)));

        vectors.add(quantityVector("reports.monthly: plan 50, grant 200 -> 200",
            Optional.of(50L), List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200))),
            true, EntitlementValue.Quantity.of(200)));

        vectors.add(quantityVector("reports.monthly: plan 50, grant 200, hold 0 -> 0, still allowed",
            Optional.of(50L),
            List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200)),
                    hold("reports.monthly", 2, EntitlementValue.Quantity.of(0))),
            true, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly: plan 150 beats grant 100 -> 150",
            Optional.of(150L), List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(100))),
            true, EntitlementValue.Quantity.of(150)));

        vectors.add(seatsVector("seats: plan unlimited, hold 100 -> 100"));

        vectors.add(supportTierVector("support: tier community, no off-value -> allowed"));

        vectors.add(slaTierVector("sla: tier none equals off-value -> disallowed"));

        return List.copyOf(vectors);
    }

    private static com.solovis.entitlement.core.model.AccountOverride grant(String key, long id, EntitlementValue value) {
        return new com.solovis.entitlement.core.model.AccountOverride(
            java.util.OptionalLong.of(id), "acct_1", new CapabilityKey(key),
            com.solovis.entitlement.core.model.OverrideKind.GRANT, value,
            Optional.of("conformance fixture"), Optional.of("conformance"), Optional.empty());
    }

    private static com.solovis.entitlement.core.model.AccountOverride hold(String key, long id, EntitlementValue value) {
        return new com.solovis.entitlement.core.model.AccountOverride(
            java.util.OptionalLong.of(id), "acct_1", new CapabilityKey(key),
            com.solovis.entitlement.core.model.OverrideKind.HOLD, value,
            Optional.of("conformance fixture"), Optional.of("conformance"), Optional.empty());
    }

    private static ConformanceVector switchVector(
        String name, EntitlementValue planValue, List<com.solovis.entitlement.core.model.AccountOverride> overrides,
        boolean expectedAllowed, EntitlementValue expectedValue) {
        var key = new CapabilityKey("api.access");
        var builder = new SnapshotBuilder()
            .capability(new Capability(key, "API access", null, ValueType.SWITCH,
                new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("free", key, planValue))
            .account(new AccountAssignment("acct_1", "free"));
        overrides.forEach(builder::override);
        return new ConformanceVector(name, builder.build(1), "acct_1", key, expectedAllowed, expectedValue);
    }

    private static ConformanceVector quantityVector(
        String name, Optional<Long> planAmount, List<com.solovis.entitlement.core.model.AccountOverride> overrides,
        boolean expectedAllowed, EntitlementValue expectedValue) {
        var key = new CapabilityKey("reports.monthly");
        var builder = new SnapshotBuilder()
            .capability(new Capability(key, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"));
        planAmount.ifPresent(amount ->
            builder.planEntitlement(new PlanEntitlement("pro", key, EntitlementValue.Quantity.of(amount))));
        overrides.forEach(builder::override);
        return new ConformanceVector(name, builder.build(1), "acct_1", key, expectedAllowed, expectedValue);
    }

    private static ConformanceVector seatsVector(String name) {
        var key = new CapabilityKey("seats.count");
        var fixture = new SnapshotBuilder()
            .capability(new Capability(key, "Seats", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("enterprise", "Enterprise", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("enterprise", key, EntitlementValue.Quantity.unbounded()))
            .account(new AccountAssignment("acct_1", "enterprise"))
            .override(hold("seats.count", 1, EntitlementValue.Quantity.of(100)))
            .build(1);
        return new ConformanceVector(name, fixture, "acct_1", key, true, EntitlementValue.Quantity.of(100));
    }

    private static ConformanceVector supportTierVector(String name) {
        var key = new CapabilityKey("support.tier");
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var fixture = new SnapshotBuilder()
            .capability(new Capability(key, "Support", null, ValueType.TIER,
                new EntitlementValue.Tier("community", 0), Optional.empty(), tiers, Capability.Status.ACTIVE, null))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);
        return new ConformanceVector(name, fixture, "acct_1", key, true, new EntitlementValue.Tier("community", 0));
    }

    private static ConformanceVector slaTierVector(String name) {
        var key = new CapabilityKey("sla.tier");
        var tiers = new TierOrder(List.of(
            new TierOrder.TierDefinition("none", 0, "None"),
            new TierOrder.TierDefinition("standard", 1, "Standard")));
        var fixture = new SnapshotBuilder()
            .capability(new Capability(key, "SLA", null, ValueType.TIER,
                new EntitlementValue.Tier("none", 0),
                Optional.of(new com.solovis.entitlement.core.model.OffValue(new EntitlementValue.Tier("none", 0))),
                tiers, Capability.Status.ACTIVE, null))
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);
        return new ConformanceVector(name, fixture, "acct_1", key, false, new EntitlementValue.Tier("none", 0));
    }
}
```

```java
package com.solovis.entitlement.core.conformance;

import com.solovis.entitlement.core.engine.Resolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs a set of {@link ConformanceVector}s against this JVM's {@link Resolver} (research.md §20). */
public final class ConformanceCheck {

    private ConformanceCheck() {}

    public static ConformanceResult run(List<ConformanceVector> vectors) {
        var failures = new ArrayList<String>();
        for (var vector : vectors) {
            var decision = Resolver.resolve(vector.fixture(), vector.accountExternalId(), vector.capabilityKey(), Instant.now());
            if (decision.allowed() != vector.expectedAllowed() || !decision.value().equals(vector.expectedValue())) {
                failures.add(vector.name() + ": expected allowed=" + vector.expectedAllowed()
                    + " value=" + vector.expectedValue()
                    + " but got allowed=" + decision.allowed() + " value=" + decision.value());
            }
        }
        return new ConformanceResult(failures.isEmpty(), List.copyOf(failures));
    }

    public record ConformanceResult(boolean passed, List<String> failures) {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl entitlement-core -am test -Dtest=ConformanceCheckTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add management/backend/entitlement-core/src/main/java/com/solovis/entitlement/core/conformance/ \
        management/backend/entitlement-core/src/test/java/com/solovis/entitlement/core/conformance/
git commit -m "feat(entitlement-core): add conformance vectors from the spec §5 worked examples"
```

---

### Task 14: Full module verification

**Files:** none (verification only).

- [ ] **Step 1: Run the entire `entitlement-core` test suite**

Run: `./mvnw -pl entitlement-core -am test`
Expected: `BUILD SUCCESS`, all tests green — every unit test from Tasks 1–13 plus the jqwik property test (1000 generated cases by default).

- [ ] **Step 2: Run a full reactor build to confirm nothing downstream broke**

Run: `./mvnw -q compile`
Expected: `BUILD SUCCESS` for `entitlement-core`, `entitlement-service` and `entitlement-client` (the latter two are still scaffolding-only, so this mainly guards against an accidental signature change breaking the reactor).

- [ ] **Step 3: Confirm no stray files are staged**

Run: `git status --short management/backend/entitlement-core`
Expected: clean (everything from this plan was committed task-by-task in Tasks 1–13). Do not touch the pre-existing `homepage.html` deletion or the untracked `refs/` directory — they predate this plan and are not in scope.

- [ ] **Step 4: No commit needed** — this task is verification-only.

---

## What this plan deliberately does not cover

- `entitlement-service` (Spring Boot: JDBC store, snapshot assembly from SQLite, REST controllers, audit recording) — a separate plan, consuming `entitlement-core` as a dependency.
- `entitlement-client` (the SDK: `SnapshotPoller`, `SnapshotDiskCache`, `ConformanceGate` wiring, `EntitlementClient` implementation) — a separate plan, consuming both `entitlement-core` and this module's `conformance` package.
- `entitlement-ui` and `entitlement-loadtest` — out of scope for a Java-core plan entirely.

`entitlement-core` is deliberately the full dependency surface those modules need: model types, `Resolver.resolve()`/`explain()`, `Snapshot`/`SnapshotBuilder`/`SnapshotMutator`, and the conformance vectors — with no Spring, no I/O, and no JSON library pulling those decisions in prematurely.
