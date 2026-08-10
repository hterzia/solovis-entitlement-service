package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * c12/c13/c16: evaluating the same state twice, with the overrides shuffled into any order,
 * produces the same result. Grant/hold amounts are randomised, unbounded quantities are mixed
 * in, and the fixture capability declares an off-value — so the property does not accidentally
 * hold only because every generated case ties, because the unlimited short-circuit in
 * Generosity.compareQuantity never fires, or because allowed() is trivially always true.
 *
 * <p>Coverage spans all three value types (QUANTITY with unbounded grants/holds, SWITCH, TIER),
 * each also exercised with the plan silent on the capability so the CAPABILITY_DEFAULT baseline
 * is included, not just the PLAN baseline. The shuffle is seeded by a jqwik-supplied {@code seed}
 * so a failing permutation from a reported sample is reproducible on replay, rather than only
 * manifesting on whichever run happened to draw an unlucky {@code new Random()}.
 */
class ResolverOrderIndependencePropertyTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");
    private static final CapabilityKey API_ACCESS = new CapabilityKey("api.access");
    private static final CapabilityKey SUPPORT = new CapabilityKey("support.tier");

    private static final List<TierOrder.TierDefinition> SUPPORT_TIERS = List.of(
        new TierOrder.TierDefinition("none", 0, "None"),
        new TierOrder.TierDefinition("bronze", 1, "Bronze"),
        new TierOrder.TierDefinition("silver", 2, "Silver"),
        new TierOrder.TierDefinition("gold", 3, "Gold"));

    @Property
    void resolutionIsInvariantUnderPermutationOfOverrides(
        @ForAll @LongRange(min = 0, max = 1000) long planAmount,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> grantAmounts,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> holdAmounts,
        @ForAll boolean includeUnboundedGrant,
        @ForAll boolean includeUnboundedHold,
        @ForAll boolean planMentionsCapability,
        @ForAll long seed) {

        var overrides = new java.util.ArrayList<AccountOverride>();
        long id = 1;
        for (int amount : grantAmounts) {
            overrides.add(override(id++, OverrideKind.GRANT, EntitlementValue.Quantity.of(amount)));
        }
        if (includeUnboundedGrant) {
            overrides.add(override(id++, OverrideKind.GRANT, EntitlementValue.Quantity.unbounded()));
        }
        for (int amount : holdAmounts) {
            overrides.add(override(id++, OverrideKind.HOLD, EntitlementValue.Quantity.of(amount)));
        }
        if (includeUnboundedHold) {
            overrides.add(override(id++, OverrideKind.HOLD, EntitlementValue.Quantity.unbounded()));
        }

        var baseline = resolveWith(planAmount, planMentionsCapability, overrides);
        assertThat(explainWith(planAmount, planMentionsCapability, overrides))
            .as("resolve() and explain() must agree on value and allowed (c24)")
            .isEqualTo(baseline);

        assertShuffledPermutationsAgree(overrides, new Random(seed),
            shuffled -> resolveWith(planAmount, planMentionsCapability, shuffled),
            shuffled -> explainWith(planAmount, planMentionsCapability, shuffled),
            baseline);
    }

    private static AccountOverride override(long id, OverrideKind kind, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", REPORTS, kind, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static Answer resolveWith(long planAmount, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.resolve(
            snapshotWith(planAmount, planMentionsCapability, overrides), "acct_1", REPORTS, Instant.now());
        return new Answer(decision.value(), decision.allowed());
    }

    private static Answer explainWith(long planAmount, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.explain(
            snapshotWith(planAmount, planMentionsCapability, overrides), "acct_1", REPORTS, Instant.now()).decision();
        return new Answer(decision.value(), decision.allowed());
    }

    private static Snapshot snapshotWith(long planAmount, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var builder = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.of(new OffValue(EntitlementValue.Quantity.of(0))),
                TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"));
        if (planMentionsCapability) {
            builder.planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(planAmount)));
        }
        overrides.forEach(builder::override);
        return builder.build(1);
    }

    // SWITCH: the same order-independence property over the boolean lattice. Every grant/hold
    // combination here either ties or has a single winner (there is no "bigger" beyond true), so
    // this reliably exercises the tie-break-by-id path (c16) that QUANTITY's continuous range
    // only lands on by chance.

    @Property
    void resolutionIsInvariantUnderPermutationOfOverridesForSwitch(
        @ForAll boolean planValue,
        @ForAll @Size(min = 0, max = 6) List<Boolean> grantValues,
        @ForAll @Size(min = 0, max = 6) List<Boolean> holdValues,
        @ForAll boolean planMentionsCapability,
        @ForAll long seed) {

        var overrides = new java.util.ArrayList<AccountOverride>();
        long id = 1;
        for (boolean value : grantValues) {
            overrides.add(switchOverride(id++, OverrideKind.GRANT, value));
        }
        for (boolean value : holdValues) {
            overrides.add(switchOverride(id++, OverrideKind.HOLD, value));
        }

        var baseline = resolveWithSwitch(planValue, planMentionsCapability, overrides);
        assertThat(explainWithSwitch(planValue, planMentionsCapability, overrides))
            .as("resolve() and explain() must agree on value and allowed (c24)")
            .isEqualTo(baseline);

        assertShuffledPermutationsAgree(overrides, new Random(seed),
            shuffled -> resolveWithSwitch(planValue, planMentionsCapability, shuffled),
            shuffled -> explainWithSwitch(planValue, planMentionsCapability, shuffled),
            baseline);
    }

    private static AccountOverride switchOverride(long id, OverrideKind kind, boolean value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", API_ACCESS, kind, new EntitlementValue.Switch(value),
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static Answer resolveWithSwitch(boolean planValue, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.resolve(
            snapshotWithSwitch(planValue, planMentionsCapability, overrides), "acct_1", API_ACCESS, Instant.now());
        return new Answer(decision.value(), decision.allowed());
    }

    private static Answer explainWithSwitch(boolean planValue, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.explain(
            snapshotWithSwitch(planValue, planMentionsCapability, overrides), "acct_1", API_ACCESS, Instant.now()).decision();
        return new Answer(decision.value(), decision.allowed());
    }

    private static Snapshot snapshotWithSwitch(boolean planValue, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var builder = new SnapshotBuilder()
            .capability(new Capability(API_ACCESS, "API access", null, ValueType.SWITCH,
                new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"));
        if (planMentionsCapability) {
            builder.planEntitlement(new PlanEntitlement("pro", API_ACCESS, new EntitlementValue.Switch(planValue)));
        }
        overrides.forEach(builder::override);
        return builder.build(1);
    }

    // TIER: order-independence over a declared four-level ladder, including ties at the same
    // ordinal (c16's tie-break-by-id) and the CAPABILITY_DEFAULT baseline.

    @Property
    void resolutionIsInvariantUnderPermutationOfOverridesForTier(
        @ForAll @IntRange(min = 0, max = 3) int planOrdinal,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 3) Integer> grantOrdinals,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 3) Integer> holdOrdinals,
        @ForAll boolean planMentionsCapability,
        @ForAll long seed) {

        var overrides = new java.util.ArrayList<AccountOverride>();
        long id = 1;
        for (int ordinal : grantOrdinals) {
            overrides.add(tierOverride(id++, OverrideKind.GRANT, ordinal));
        }
        for (int ordinal : holdOrdinals) {
            overrides.add(tierOverride(id++, OverrideKind.HOLD, ordinal));
        }

        var baseline = resolveWithTier(planOrdinal, planMentionsCapability, overrides);
        assertThat(explainWithTier(planOrdinal, planMentionsCapability, overrides))
            .as("resolve() and explain() must agree on value and allowed (c24)")
            .isEqualTo(baseline);

        assertShuffledPermutationsAgree(overrides, new Random(seed),
            shuffled -> resolveWithTier(planOrdinal, planMentionsCapability, shuffled),
            shuffled -> explainWithTier(planOrdinal, planMentionsCapability, shuffled),
            baseline);
    }

    private static AccountOverride tierOverride(long id, OverrideKind kind, int ordinal) {
        var tier = SUPPORT_TIERS.get(ordinal);
        return new AccountOverride(OptionalLong.of(id), "acct_1", SUPPORT, kind,
            new EntitlementValue.Tier(tier.tierKey(), tier.ordinal()),
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static Answer resolveWithTier(int planOrdinal, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.resolve(
            snapshotWithTier(planOrdinal, planMentionsCapability, overrides), "acct_1", SUPPORT, Instant.now());
        return new Answer(decision.value(), decision.allowed());
    }

    private static Answer explainWithTier(int planOrdinal, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var decision = Resolver.explain(
            snapshotWithTier(planOrdinal, planMentionsCapability, overrides), "acct_1", SUPPORT, Instant.now()).decision();
        return new Answer(decision.value(), decision.allowed());
    }

    private static Snapshot snapshotWithTier(int planOrdinal, boolean planMentionsCapability, List<AccountOverride> overrides) {
        var tiers = new TierOrder(SUPPORT_TIERS);
        var defaultTier = SUPPORT_TIERS.get(0);
        var builder = new SnapshotBuilder()
            .capability(new Capability(SUPPORT, "Support", null, ValueType.TIER,
                new EntitlementValue.Tier(defaultTier.tierKey(), defaultTier.ordinal()),
                Optional.of(new OffValue(new EntitlementValue.Tier(defaultTier.tierKey(), defaultTier.ordinal()))),
                tiers, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"));
        if (planMentionsCapability) {
            var planTier = SUPPORT_TIERS.get(planOrdinal);
            builder.planEntitlement(new PlanEntitlement("pro", SUPPORT, new EntitlementValue.Tier(planTier.tierKey(), planTier.ordinal())));
        }
        overrides.forEach(builder::override);
        return builder.build(1);
    }

    /** Shuffles {@code overrides} five times off the given (seeded) {@link Random} and asserts
     * both resolve() and explain() reproduce {@code baseline} for every permutation. */
    private static void assertShuffledPermutationsAgree(
        List<AccountOverride> overrides, Random random,
        java.util.function.Function<List<AccountOverride>, Answer> resolve,
        java.util.function.Function<List<AccountOverride>, Answer> explain,
        Answer baseline) {
        for (int trial = 0; trial < 5; trial++) {
            var shuffled = new java.util.ArrayList<>(overrides);
            Collections.shuffle(shuffled, random);
            assertThat(resolve.apply(shuffled)).isEqualTo(baseline);
            assertThat(explain.apply(shuffled)).isEqualTo(baseline);
        }
    }

    private record Answer(EntitlementValue value, boolean allowed) {}
}
