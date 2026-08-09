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
