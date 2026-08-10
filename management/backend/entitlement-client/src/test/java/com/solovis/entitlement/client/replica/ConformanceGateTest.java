package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConformanceGateTest {

    private static final Instant PUBLISHED = Instant.parse("2026-08-09T14:03:10.900Z");

    private static Replica replica(int format, int contract, List<ConformanceVector> vectors) {
        var snapshot = new SnapshotBuilder().build(48211L);
        return new Replica(snapshot, Map.of(), PUBLISHED, vectors, format, contract);
    }

    /** A vector whose expectation the real resolver satisfies. */
    private static ConformanceVector satisfiable() {
        // No explicit off-value: core forbids one on a SWITCH and folds in the rule that false is
        // always SWITCH's off-value, so `allowed` still comes out true here for Switch(true).
        var capability = new Capability(
            new CapabilityKey("api.access"), "api.access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false),
            Optional.empty(),
            TierOrder.NONE, Capability.Status.ACTIVE, null);
        var fixture = new SnapshotBuilder()
            .capability(capability)
            .plan(new Plan("p", "p", Plan.Status.ACTIVE, true))
            .planEntitlement(new PlanEntitlement("p", new CapabilityKey("api.access"),
                new EntitlementValue.Switch(true)))
            .account(new AccountAssignment("acct_c1", "p"))
            .build(0L);
        return new ConformanceVector("plan grants the switch", fixture, "acct_c1",
            new CapabilityKey("api.access"), true, new EntitlementValue.Switch(true));
    }

    /** The same fixture, with an expectation the resolver cannot produce. */
    private static ConformanceVector unsatisfiable() {
        var ok = satisfiable();
        return new ConformanceVector("a vector this engine disagrees with", ok.fixture(),
            ok.accountExternalId(), ok.capabilityKey(), false, new EntitlementValue.Switch(false));
    }

    @Test
    void vectorsThisEngineAgreesWithPassTheGate() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(satisfiable())));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void aDeltaDerivedCandidateCarryingNoVectorsPassesRatherThanBecomingAnOutage() {
        var result = ConformanceGate.evaluate(replica(1, ResolverContract.VERSION, List.of()), false);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void theSingleArgumentFormDoesNotRequireVectorsEitherSoExistingCallSitesKeepWorking() {
        var result = ConformanceGate.evaluate(replica(1, ResolverContract.VERSION, List.of()));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void aFullSnapshotCandidateCarryingNoVectorsFailsTheGateRatherThanDisablingTheDriftDefenceSilently() {
        var result = ConformanceGate.evaluate(replica(1, ResolverContract.VERSION, List.of()), true);

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("conformance vectors");
    }

    @Test
    void aVectorThisEngineComputesDifferentlyFailsTheGateAndNamesTheVector() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(unsatisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("a vector this engine disagrees with");
    }

    @Test
    void aResolverContractThisSdkDoesNotImplementFailsTheGateBecauseTheRuleItselfChanged() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION + 1, List.of(satisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("resolverContract");
    }

    @Test
    void anUnrecognisedWireFormatFailsTheGateSeparatelyFromTheSemanticsCheck() {
        var result = ConformanceGate.evaluate(
            replica(99, ResolverContract.VERSION, List.of(satisfiable())));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("format");
    }

    @Test
    void aVectorThatThrowsIsAFailureNotAnEscapingException() {
        var capability = new Capability(
            new CapabilityKey("api.access"), "api.access", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);
        var fixture = new SnapshotBuilder().capability(capability).build(0L);
        var noSuchAccount = new ConformanceVector("vector naming an account its fixture lacks",
            fixture, "acct_missing", new CapabilityKey("api.access"), true,
            new EntitlementValue.Switch(false));

        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, List.of(noSuchAccount)));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("vector naming an account its fixture lacks");
    }

    @Test
    void theRealSpecFiveWorkedExamplesPassAgainstThisSdksEngine() {
        var result = ConformanceGate.evaluate(
            replica(1, ResolverContract.VERSION, ConformanceVector.spec5WorkedExamples()));

        assertThat(result.passed()).isTrue();
    }
}
