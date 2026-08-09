package com.solovis.entitlement.core.conformance;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConformanceCheckTest {

    @Test
    void everySpec5WorkedExamplePasses() {
        var vectors = ConformanceVector.spec5WorkedExamples();
        assertThat(vectors).isNotEmpty();

        var result = ConformanceCheck.run(vectors);

        assertThat(result.failures()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    void reportsWhichVectorFailedRatherThanJustThatSomethingDid() {
        var badFixture = new com.solovis.entitlement.core.view.SnapshotBuilder()
            .capability(new com.solovis.entitlement.core.model.Capability(
                new com.solovis.entitlement.core.model.CapabilityKey("api.access"), "API access", null,
                com.solovis.entitlement.core.model.ValueType.SWITCH,
                new com.solovis.entitlement.core.model.EntitlementValue.Switch(false),
                java.util.Optional.empty(), com.solovis.entitlement.core.model.TierOrder.NONE,
                com.solovis.entitlement.core.model.Capability.Status.ACTIVE, null))
            .plan(new com.solovis.entitlement.core.model.Plan("free", "Free",
                com.solovis.entitlement.core.model.Plan.Status.ACTIVE, true))
            .planEntitlement(new com.solovis.entitlement.core.model.PlanEntitlement("free",
                new com.solovis.entitlement.core.model.CapabilityKey("api.access"),
                new com.solovis.entitlement.core.model.EntitlementValue.Switch(false)))
            .account(new com.solovis.entitlement.core.model.AccountAssignment("acct_1", "free"))
            .build(1);

        var deliberatelyWrong = new ConformanceVector(
            "deliberately wrong", badFixture, "acct_1",
            new com.solovis.entitlement.core.model.CapabilityKey("api.access"),
            true, // actual is false — this vector should fail
            new com.solovis.entitlement.core.model.EntitlementValue.Switch(false));

        var result = ConformanceCheck.run(java.util.List.of(deliberatelyWrong));

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).singleElement().asString().contains("deliberately wrong");
    }

    @Test
    void resolverContractIsAPositiveVersion() {
        assertThat(ResolverContract.VERSION).isGreaterThan(0);
    }
}
