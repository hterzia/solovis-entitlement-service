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
