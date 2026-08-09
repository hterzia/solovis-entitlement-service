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
 */
class ResolverOrderIndependencePropertyTest {

    private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");

    @Property
    void resolutionIsInvariantUnderPermutationOfOverrides(
        @ForAll @LongRange(min = 0, max = 1000) long planAmount,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> grantAmounts,
        @ForAll @Size(min = 0, max = 6) List<@IntRange(min = 0, max = 1000) Integer> holdAmounts,
        @ForAll boolean includeUnboundedGrant,
        @ForAll boolean includeUnboundedHold) {

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

        var baseline = resolveWith(planAmount, overrides);
        assertThat(explainWith(planAmount, overrides))
            .as("resolve() and explain() must agree on value and allowed (c24)")
            .isEqualTo(baseline);

        for (int trial = 0; trial < 5; trial++) {
            var shuffled = new java.util.ArrayList<>(overrides);
            Collections.shuffle(shuffled);
            assertThat(resolveWith(planAmount, shuffled)).isEqualTo(baseline);
            assertThat(explainWith(planAmount, shuffled)).isEqualTo(baseline);
        }
    }

    private static AccountOverride override(long id, OverrideKind kind, EntitlementValue value) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", REPORTS, kind, value,
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static Answer resolveWith(long planAmount, List<AccountOverride> overrides) {
        var decision = Resolver.resolve(snapshotWith(planAmount, overrides), "acct_1", REPORTS, Instant.now());
        return new Answer(decision.value(), decision.allowed());
    }

    private static Answer explainWith(long planAmount, List<AccountOverride> overrides) {
        var decision = Resolver.explain(snapshotWith(planAmount, overrides), "acct_1", REPORTS, Instant.now()).decision();
        return new Answer(decision.value(), decision.allowed());
    }

    private static Snapshot snapshotWith(long planAmount, List<AccountOverride> overrides) {
        var builder = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.of(new OffValue(EntitlementValue.Quantity.of(0))),
                TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(planAmount)))
            .account(new AccountAssignment("acct_1", "pro"));
        overrides.forEach(builder::override);
        return builder.build(1);
    }

    private record Answer(EntitlementValue value, boolean allowed) {}
}
