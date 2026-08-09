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
