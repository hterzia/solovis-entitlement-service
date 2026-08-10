package com.solovis.entitlement.core.conformance;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A self-contained (model fragment → expected allowed, value) case a replica evaluates against its
 * own engine at startup, refusing to serve on any mismatch (research.md §20, snapshot-feed.md "The
 * conformance gate").
 *
 * <p>This gate is the estate's only defence against two replicas on different SDK versions
 * disagreeing about the same account, and it has to be proactive: with no local traces, a wrong
 * answer in a consuming service leaves nothing to diagnose after the fact. Its strength is therefore
 * exactly the coverage of this list, and nothing else.
 *
 * <p>The set is organised by the drift each group would catch:
 *
 * <ol>
 *   <li><b>§5 worked examples</b> — the specification's own table, transcribed literally, so the
 *       examples are executable rather than illustrative.</li>
 *   <li><b>Tier overrides</b> — the ordinal comparison in the generosity order. Tiers are the only
 *       value type whose order is declared per capability rather than intrinsic, which makes them
 *       the likeliest thing to get wrong.</li>
 *   <li><b>Declared quantity off-values</b> — §5 permits {@code 0} as an off-value, which flips
 *       {@code allowed} for a value that is otherwise always allowed (c18).</li>
 *   <li><b>Unlimited on the override side</b> — {@code unlimited} is a distinct variant, never a
 *       large number (c2); an engine comparing it numerically passes every plan-side case.</li>
 *   <li><b>Competing overrides of one kind</b> — most-generous-GRANT and most-restrictive-HOLD
 *       selection (c12, c13), which a single-override engine answers correctly by accident.</li>
 *   <li><b>Silent plans</b> — the CAPABILITY_DEFAULT baseline (c4, c17).</li>
 * </ol>
 *
 * <p>Deliberately <em>not</em> covered: the three §6.3 errors (unknown account, unknown capability,
 * retired capability). A vector's expectation is a {@code (allowed, value)} pair, which is the shape
 * snapshot-feed.md fixes for the {@code conformance} record's {@code expect} object; expressing a
 * thrown error would change that wire shape and so require a {@code format} bump. The service-side
 * tests cover c19 directly.
 */
public record ConformanceVector(
    String name,
    Snapshot fixture,
    String accountExternalId,
    CapabilityKey capabilityKey,
    boolean expectedAllowed,
    EntitlementValue expectedValue
) {

    private static final String ACCOUNT = "acct_1";
    private static final String PLAN = "pro";

    /** Tiers used by every TIER vector below: none(0) < bronze(1) < gold(2). */
    private static final TierOrder SUPPORT_TIERS = new TierOrder(List.of(
        new TierOrder.TierDefinition("none", 0, "None"),
        new TierOrder.TierDefinition("bronze", 1, "Bronze"),
        new TierOrder.TierDefinition("gold", 2, "Gold")));

    private static final EntitlementValue.Tier NONE = new EntitlementValue.Tier("none", 0);
    private static final EntitlementValue.Tier BRONZE = new EntitlementValue.Tier("bronze", 1);
    private static final EntitlementValue.Tier GOLD = new EntitlementValue.Tier("gold", 2);

    public static List<ConformanceVector> spec5WorkedExamples() {
        var vectors = new ArrayList<ConformanceVector>();
        spec5Table(vectors);
        tierOverrides(vectors);
        declaredQuantityOffValues(vectors);
        unlimitedOnTheOverrideSide(vectors);
        competingOverridesOfOneKind(vectors);
        silentPlans(vectors);
        return List.copyOf(vectors);
    }

    // ---------------------------------------------------------------- 1. spec §5's worked examples

    private static void spec5Table(List<ConformanceVector> vectors) {
        vectors.add(switchVector("api.access: plan false, no overrides -> false",
            Optional.of(new EntitlementValue.Switch(false)), List.of(), false, new EntitlementValue.Switch(false)));

        vectors.add(switchVector("api.access: plan false, grant true -> true",
            Optional.of(new EntitlementValue.Switch(false)),
            List.of(grant("api.access", 1, new EntitlementValue.Switch(true))),
            true, new EntitlementValue.Switch(true)));

        vectors.add(switchVector("api.access: plan true, hold false -> false",
            Optional.of(new EntitlementValue.Switch(true)),
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

        // A hold above the post-grant running value must not raise it — a hold only ever caps,
        // it never replaces (spec §4.3). A "hold replaces the result" engine would wrongly answer
        // 100 here; every other HOLD vector in this table has the hold sitting below the running
        // value, so none of them catch that bug.
        vectors.add(quantityVector("reports.monthly: plan 50, hold 100 -> 50 (hold cannot raise)",
            Optional.of(50L), List.of(hold("reports.monthly", 1, EntitlementValue.Quantity.of(100))),
            true, EntitlementValue.Quantity.of(50)));

        // A GRANT must be able to raise a capability the plan is silent on (the CAPABILITY_DEFAULT
        // baseline, c17) — not just raise a plan-declared value.
        vectors.add(quantityVector("reports.monthly: unmentioned, grant 200 -> 200 (grant raises capability default)",
            Optional.empty(), List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200))),
            true, EntitlementValue.Quantity.of(200)));

        vectors.add(seatsVector("seats: plan unlimited, hold 100 -> 100",
            List.of(hold("seats.count", 1, EntitlementValue.Quantity.of(100))),
            true, EntitlementValue.Quantity.of(100)));

        vectors.add(tierVector("support: tier community, no off-value -> allowed",
            Optional.empty(), Optional.of(BRONZE), List.of(), true, BRONZE));

        vectors.add(tierVector("sla: tier none equals off-value -> disallowed",
            Optional.of(new OffValue(NONE)), Optional.of(NONE), List.of(), false, NONE));
    }

    // ------------------------------------------------------- 2. tier overrides (ordinal ordering)

    private static void tierOverrides(List<ConformanceVector> vectors) {
        vectors.add(tierVector("support: plan bronze, grant gold -> gold",
            Optional.empty(), Optional.of(BRONZE), List.of(grant("support.tier", 1, GOLD)), true, GOLD));

        vectors.add(tierVector("support: plan gold, hold bronze -> bronze",
            Optional.empty(), Optional.of(GOLD), List.of(hold("support.tier", 1, BRONZE)), true, BRONZE));

        // The tier mirror of "plan beats a smaller grant": ordinals must be compared, not tier keys
        // compared as strings, where "bronze" sorts before "gold" and would accidentally agree.
        vectors.add(tierVector("support: plan gold beats grant bronze -> gold",
            Optional.empty(), Optional.of(GOLD), List.of(grant("support.tier", 1, BRONZE)), true, GOLD));

        vectors.add(tierVector("support: plan bronze, hold gold -> bronze (hold cannot raise)",
            Optional.empty(), Optional.of(BRONZE), List.of(hold("support.tier", 1, GOLD)), true, BRONZE));

        vectors.add(tierVector("support: plan none, grants bronze and gold -> gold",
            Optional.empty(), Optional.of(NONE),
            List.of(grant("support.tier", 1, BRONZE), grant("support.tier", 2, GOLD)), true, GOLD));

        vectors.add(tierVector("support: plan gold, holds bronze and none -> none",
            Optional.empty(), Optional.of(GOLD),
            List.of(hold("support.tier", 1, BRONZE), hold("support.tier", 2, NONE)), true, NONE));

        vectors.add(tierVector("support: unmentioned -> capability default none",
            Optional.empty(), Optional.empty(), List.of(), true, NONE));

        // allowed must flip with the off-value comparison, on both sides of it (c18).
        vectors.add(tierVector("sla: off-value none, grant bronze -> bronze, allowed becomes true",
            Optional.of(new OffValue(NONE)), Optional.of(NONE),
            List.of(grant("sla.tier", 1, BRONZE)), true, BRONZE));

        vectors.add(tierVector("sla: off-value none, plan gold, hold none -> none, allowed becomes false",
            Optional.of(new OffValue(NONE)), Optional.of(GOLD),
            List.of(hold("sla.tier", 1, NONE)), false, NONE));

        vectors.add(tierVector("sla: off-value none, plan bronze, grant gold, hold bronze -> bronze, allowed true",
            Optional.of(new OffValue(NONE)), Optional.of(BRONZE),
            List.of(grant("sla.tier", 1, GOLD), hold("sla.tier", 2, BRONZE)), true, BRONZE));
    }

    // ------------------------------------------------- 3. declared quantity off-values (§5, c18)

    private static void declaredQuantityOffValues(List<ConformanceVector> vectors) {
        var offZero = Optional.of(new OffValue(EntitlementValue.Quantity.of(0)));

        // Without a declared off-value a quantity is always allowed; with one, zero means absence.
        // An engine that hard-codes "quantities are always allowed" passes every other vector here.
        vectors.add(quantityVector("reports.monthly (off-value 0): plan 0 -> 0, disallowed",
            offZero, Optional.of(0L), List.of(), false, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly (off-value 0): plan 5 -> 5, allowed",
            offZero, Optional.of(5L), List.of(), true, EntitlementValue.Quantity.of(5)));

        vectors.add(quantityVector("reports.monthly (off-value 0): plan 50, hold 0 -> 0, disallowed",
            offZero, Optional.of(50L), List.of(hold("reports.monthly", 1, EntitlementValue.Quantity.of(0))),
            false, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly (off-value 0): unmentioned -> default 0, disallowed",
            offZero, Optional.empty(), List.of(), false, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly (off-value 0): plan 0, grant 10 -> 10, allowed",
            offZero, Optional.of(0L), List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(10))),
            true, EntitlementValue.Quantity.of(10)));
    }

    // --------------------------------------------- 4. unlimited as a variant, not a large number

    private static void unlimitedOnTheOverrideSide(List<ConformanceVector> vectors) {
        vectors.add(seatsVector("seats: plan 50, grant unlimited -> unlimited",
            Optional.of(EntitlementValue.Quantity.of(50)),
            List.of(grant("seats.count", 1, EntitlementValue.Quantity.unbounded())),
            true, EntitlementValue.Quantity.unbounded()));

        vectors.add(seatsVector("seats: plan unlimited beats grant 100 -> unlimited",
            Optional.of(EntitlementValue.Quantity.unbounded()),
            List.of(grant("seats.count", 1, EntitlementValue.Quantity.of(100))),
            true, EntitlementValue.Quantity.unbounded()));

        vectors.add(seatsVector("seats: grants 100 and unlimited -> unlimited",
            Optional.of(EntitlementValue.Quantity.of(10)),
            List.of(grant("seats.count", 1, EntitlementValue.Quantity.of(100)),
                    grant("seats.count", 2, EntitlementValue.Quantity.unbounded())),
            true, EntitlementValue.Quantity.unbounded()));

        vectors.add(seatsVector("seats: plan unlimited, hold unlimited -> unlimited (hold binds nothing)",
            Optional.of(EntitlementValue.Quantity.unbounded()),
            List.of(hold("seats.count", 1, EntitlementValue.Quantity.unbounded())),
            true, EntitlementValue.Quantity.unbounded()));

        vectors.add(seatsVector("seats: grant unlimited, hold 10 -> 10",
            Optional.of(EntitlementValue.Quantity.of(5)),
            List.of(grant("seats.count", 1, EntitlementValue.Quantity.unbounded()),
                    hold("seats.count", 2, EntitlementValue.Quantity.of(10))),
            true, EntitlementValue.Quantity.of(10)));

        vectors.add(seatsVector("seats: holds unlimited and 25 -> 25 (a bounded hold is stricter)",
            Optional.of(EntitlementValue.Quantity.unbounded()),
            List.of(hold("seats.count", 1, EntitlementValue.Quantity.unbounded()),
                    hold("seats.count", 2, EntitlementValue.Quantity.of(25))),
            true, EntitlementValue.Quantity.of(25)));
    }

    // ------------------------------ 5. selection among several overrides of one kind (c12, c13)

    private static void competingOverridesOfOneKind(List<ConformanceVector> vectors) {
        vectors.add(quantityVector("reports.monthly: grants 100 and 200 -> 200",
            Optional.of(10L),
            List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(100)),
                    grant("reports.monthly", 2, EntitlementValue.Quantity.of(200))),
            true, EntitlementValue.Quantity.of(200)));

        // Same pair, declared in the opposite order: the result must not depend on it (c16).
        vectors.add(quantityVector("reports.monthly: grants 200 then 100 -> 200 (order-independent)",
            Optional.of(10L),
            List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200)),
                    grant("reports.monthly", 2, EntitlementValue.Quantity.of(100))),
            true, EntitlementValue.Quantity.of(200)));

        vectors.add(quantityVector("reports.monthly: holds 10 and 5 -> 5",
            Optional.of(100L),
            List.of(hold("reports.monthly", 1, EntitlementValue.Quantity.of(10)),
                    hold("reports.monthly", 2, EntitlementValue.Quantity.of(5))),
            true, EntitlementValue.Quantity.of(5)));

        vectors.add(quantityVector("reports.monthly: holds 5 then 10 -> 5 (order-independent)",
            Optional.of(100L),
            List.of(hold("reports.monthly", 1, EntitlementValue.Quantity.of(5)),
                    hold("reports.monthly", 2, EntitlementValue.Quantity.of(10))),
            true, EntitlementValue.Quantity.of(5)));

        vectors.add(quantityVector("reports.monthly: grants 200 and 100, holds 150 and 50 -> 50",
            Optional.of(20L),
            List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200)),
                    grant("reports.monthly", 2, EntitlementValue.Quantity.of(100)),
                    hold("reports.monthly", 3, EntitlementValue.Quantity.of(150)),
                    hold("reports.monthly", 4, EntitlementValue.Quantity.of(50))),
            true, EntitlementValue.Quantity.of(50)));

        vectors.add(switchVector("api.access: grants false and true -> true",
            Optional.of(new EntitlementValue.Switch(false)),
            List.of(grant("api.access", 1, new EntitlementValue.Switch(false)),
                    grant("api.access", 2, new EntitlementValue.Switch(true))),
            true, new EntitlementValue.Switch(true)));

        vectors.add(switchVector("api.access: holds true and false -> false",
            Optional.of(new EntitlementValue.Switch(true)),
            List.of(hold("api.access", 1, new EntitlementValue.Switch(true)),
                    hold("api.access", 2, new EntitlementValue.Switch(false))),
            false, new EntitlementValue.Switch(false)));

        // Lifting the hold must restore the grant with no further action (c14). The pair of vectors
        // is the before and after of that promise.
        vectors.add(quantityVector("reports.monthly: grant 200 with hold 0 -> 0",
            Optional.of(50L),
            List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200)),
                    hold("reports.monthly", 2, EntitlementValue.Quantity.of(0))),
            true, EntitlementValue.Quantity.of(0)));

        vectors.add(quantityVector("reports.monthly: grant 200 with the hold lifted -> 200",
            Optional.of(50L), List.of(grant("reports.monthly", 1, EntitlementValue.Quantity.of(200))),
            true, EntitlementValue.Quantity.of(200)));
    }

    // ------------------------------------------------------ 6. partial plans (c4, c17) per type

    private static void silentPlans(List<ConformanceVector> vectors) {
        vectors.add(switchVector("api.access: unmentioned -> capability default false",
            Optional.empty(), List.of(), false, new EntitlementValue.Switch(false)));

        vectors.add(switchVector("api.access: unmentioned, grant true -> true",
            Optional.empty(), List.of(grant("api.access", 1, new EntitlementValue.Switch(true))),
            true, new EntitlementValue.Switch(true)));

        vectors.add(switchVector("api.access: unmentioned, hold false -> false (already at the floor)",
            Optional.empty(), List.of(hold("api.access", 1, new EntitlementValue.Switch(false))),
            false, new EntitlementValue.Switch(false)));

        vectors.add(switchVector("api.access: plan true, no overrides -> true",
            Optional.of(new EntitlementValue.Switch(true)), List.of(), true, new EntitlementValue.Switch(true)));

        vectors.add(seatsVector("seats: unmentioned -> capability default 0",
            Optional.empty(), List.of(), true, EntitlementValue.Quantity.of(0)));

        vectors.add(tierVector("support: unmentioned, grant gold -> gold",
            Optional.empty(), Optional.empty(), List.of(grant("support.tier", 1, GOLD)), true, GOLD));
    }

    // ------------------------------------------------------------------------------- fixtures

    private static AccountOverride grant(String key, long id, EntitlementValue value) {
        return override(key, id, OverrideKind.GRANT, value);
    }

    private static AccountOverride hold(String key, long id, EntitlementValue value) {
        return override(key, id, OverrideKind.HOLD, value);
    }

    private static AccountOverride override(String key, long id, OverrideKind kind, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), ACCOUNT, new CapabilityKey(key), kind, value,
            Optional.of("conformance fixture"), Optional.of("conformance"), Optional.empty());
    }

    /**
     * The one fixture shape every vector is built from: one capability, one plan that either sets
     * the capability or is silent on it, one account, and any number of overrides. Self-contained by
     * construction, which is what snapshot-feed.md requires of a {@code conformance} record.
     */
    private static ConformanceVector vector(String name, Capability capability,
            Optional<EntitlementValue> planValue, List<AccountOverride> overrides,
            boolean expectedAllowed, EntitlementValue expectedValue) {
        var builder = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan(PLAN, "Pro", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment(ACCOUNT, PLAN));
        planValue.ifPresent(value -> builder.planEntitlement(new PlanEntitlement(PLAN, capability.key(), value)));
        overrides.forEach(builder::override);
        return new ConformanceVector(name, builder.build(1), ACCOUNT, capability.key(), expectedAllowed, expectedValue);
    }

    private static ConformanceVector switchVector(String name, Optional<EntitlementValue> planValue,
            List<AccountOverride> overrides, boolean expectedAllowed, EntitlementValue expectedValue) {
        var capability = new Capability(new CapabilityKey("api.access"), "API access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        return vector(name, capability, planValue, overrides, expectedAllowed, expectedValue);
    }

    private static ConformanceVector quantityVector(String name, Optional<Long> planAmount,
            List<AccountOverride> overrides, boolean expectedAllowed, EntitlementValue expectedValue) {
        return quantityVector(name, Optional.empty(), planAmount, overrides, expectedAllowed, expectedValue);
    }

    private static ConformanceVector quantityVector(String name, Optional<OffValue> offValue, Optional<Long> planAmount,
            List<AccountOverride> overrides, boolean expectedAllowed, EntitlementValue expectedValue) {
        var capability = new Capability(new CapabilityKey("reports.monthly"), "Monthly reports", null,
            ValueType.QUANTITY, EntitlementValue.Quantity.of(0), offValue, TierOrder.NONE,
            Capability.Status.ACTIVE, null);
        return vector(name, capability, planAmount.map(a -> (EntitlementValue) EntitlementValue.Quantity.of(a)),
            overrides, expectedAllowed, expectedValue);
    }

    private static ConformanceVector seatsVector(String name, List<AccountOverride> overrides,
            boolean expectedAllowed, EntitlementValue expectedValue) {
        return seatsVector(name, Optional.of(EntitlementValue.Quantity.unbounded()), overrides,
            expectedAllowed, expectedValue);
    }

    private static ConformanceVector seatsVector(String name, Optional<EntitlementValue> planValue,
            List<AccountOverride> overrides, boolean expectedAllowed, EntitlementValue expectedValue) {
        var capability = new Capability(new CapabilityKey("seats.count"), "Seats", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        return vector(name, capability, planValue, overrides, expectedAllowed, expectedValue);
    }

    /**
     * A TIER capability over none(0) &lt; bronze(1) &lt; gold(2). The key varies with whether an
     * off-value is declared, so that "support" and "sla" read as the two different capabilities §5
     * uses them as — a support tier of {@code community} is a real benefit, an SLA of {@code none}
     * is absence.
     */
    private static ConformanceVector tierVector(String name, Optional<OffValue> offValue,
            Optional<EntitlementValue> planValue, List<AccountOverride> overrides,
            boolean expectedAllowed, EntitlementValue expectedValue) {
        String key = offValue.isPresent() ? "sla.tier" : "support.tier";
        var capability = new Capability(new CapabilityKey(key), offValue.isPresent() ? "SLA" : "Support", null,
            ValueType.TIER, NONE, offValue, SUPPORT_TIERS, Capability.Status.ACTIVE, null);
        List<AccountOverride> rekeyed = overrides.stream()
            .map(o -> new AccountOverride(o.id(), o.accountExternalId(), new CapabilityKey(key), o.kind(), o.value(),
                o.reason(), o.createdBy(), o.createdAt()))
            .toList();
        return vector(name, capability, planValue, rekeyed, expectedAllowed, expectedValue);
    }
}
