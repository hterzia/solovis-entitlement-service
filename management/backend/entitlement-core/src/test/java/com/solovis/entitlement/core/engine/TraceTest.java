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
