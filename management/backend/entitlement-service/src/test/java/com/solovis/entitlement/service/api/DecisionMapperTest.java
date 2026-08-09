package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionMapperTest {

    @Test
    void baselineFromPlanNamesThePlanInTheNote() {
        var key = new CapabilityKey("reports.monthly");
        var capability = new Capability(key, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", key, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.parse("2026-08-09T14:03:11.482Z"));
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().baseline().source()).isEqualTo("PLAN");
        assertThat(dto.trace().baseline().planKey()).isEqualTo("pro");
        assertThat(dto.trace().baseline().note()).contains("'pro'");
        assertThat(dto.trace().result().allowedReason()).isEqualTo("NO_OFF_VALUE_DECLARED");
    }

    @Test
    void grantStepNotAppliedWithNoGrantsReportsNoGrants() {
        var key = new CapabilityKey("api.access");
        var capability = new Capability(key, "API", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.now());
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().grantStep().applied()).isFalse();
        assertThat(dto.trace().grantStep().why()).isEqualTo("NO_GRANTS");
        assertThat(dto.trace().result().allowed()).isFalse();
        assertThat(dto.trace().result().allowedReason()).isEqualTo("EQUALS_OFF_VALUE");
    }

    @Test
    void tiedGrantsMarkTheHighestIdWinner() {
        var key = new CapabilityKey("reports.monthly");
        var capability = new Capability(key, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var older = new AccountOverride(java.util.OptionalLong.of(10), "acct_1", key, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("first"), Optional.of("a"), Optional.of(Instant.now()));
        var newer = new AccountOverride(java.util.OptionalLong.of(20), "acct_1", key, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("second"), Optional.of("b"), Optional.of(Instant.now()));
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(older).override(newer)
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.now());
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().grantStep().winner()).isEqualTo("ovr_20");
    }
}
