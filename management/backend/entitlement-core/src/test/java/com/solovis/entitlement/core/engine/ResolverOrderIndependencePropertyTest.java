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
        assertThat(explainValueWith(planAmount, overrides))
            .as("resolve() and explain() must agree on the value (c24)")
            .isEqualTo(baseline);

        for (int trial = 0; trial < 5; trial++) {
            var shuffled = new java.util.ArrayList<>(overrides);
            Collections.shuffle(shuffled);
            assertThat(resolveWith(planAmount, shuffled)).isEqualTo(baseline);
            assertThat(explainValueWith(planAmount, shuffled)).isEqualTo(baseline);
        }
    }

    private static AccountOverride override(long id, OverrideKind kind, int amount) {
        return new AccountOverride(OptionalLong.of(id), "acct_1", REPORTS, kind, EntitlementValue.Quantity.of(amount),
            Optional.of("reason"), Optional.of("actor"), Optional.of(Instant.now()));
    }

    private static EntitlementValue resolveWith(long planAmount, List<AccountOverride> overrides) {
        return Resolver.resolve(snapshotWith(planAmount, overrides), "acct_1", REPORTS, Instant.now()).value();
    }

    private static EntitlementValue explainValueWith(long planAmount, List<AccountOverride> overrides) {
        return Resolver.explain(snapshotWith(planAmount, overrides), "acct_1", REPORTS, Instant.now()).decision().value();
    }

    private static com.solovis.entitlement.core.view.Snapshot snapshotWith(long planAmount, List<AccountOverride> overrides) {
        var builder = new SnapshotBuilder()
            .capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
                EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null))
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(planAmount)))
            .account(new AccountAssignment("acct_1", "pro"));
        overrides.forEach(builder::override);
        return builder.build(1);
    }
}
