package com.solovis.entitlement.core.conformance;

import com.solovis.entitlement.core.model.OverrideKind;
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

    /**
     * The gate is the only defence against two replicas answering differently, so its breadth is a
     * property worth pinning rather than a convention worth hoping for. snapshot-feed.md states
     * "typically 40–60"; a set that quietly shrinks back to the §5 table would still pass every
     * other test in this class while protecting far less.
     */
    @Test
    void theVectorSetCoversEveryDimensionDriftCouldAppearIn() {
        var vectors = ConformanceVector.spec5WorkedExamples();

        assertThat(vectors)
            .as("snapshot-feed.md documents a set of roughly 40-60 vectors")
            .hasSizeGreaterThanOrEqualTo(40);

        assertThat(vectors.stream().map(ConformanceVector::name).distinct().count())
            .as("names are the on-the-wire ids, so duplicates would make a failure unattributable")
            .isEqualTo(vectors.size());

        assertThat(hasVectorWhere(vectors, v -> isTier(v) && hasOverrides(v)))
            .as("a TIER capability carrying an override — otherwise the per-capability ordinal "
                + "ordering, the likeliest thing for a replica to get wrong, is never exercised")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> isQuantity(v) && declaresOffValue(v)))
            .as("a QUANTITY with a declared off-value of 0, which is what makes `allowed` false for "
                + "a type that is otherwise always allowed (spec §5, c18)")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> !v.expectedAllowed()))
            .as("at least one vector where allowed is false; a gate of only-permitted cases would "
                + "never catch an engine that returns true unconditionally")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> isUnlimited(v.expectedValue())))
            .as("unlimited as a *result*, so an engine treating it as a large number is caught (c2)")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> countOverridesOfKind(v, OverrideKind.GRANT) >= 2))
            .as("two competing GRANTs, so most-generous selection is exercised (c12)")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> countOverridesOfKind(v, OverrideKind.HOLD) >= 2))
            .as("two competing HOLDs, so most-restrictive selection is exercised (c13)")
            .isTrue();

        assertThat(hasVectorWhere(vectors, v -> !hasPlanEntitlement(v)))
            .as("a plan silent on the capability, so the CAPABILITY_DEFAULT baseline is exercised (c4)")
            .isTrue();
    }

    private static boolean hasVectorWhere(java.util.List<ConformanceVector> vectors,
            java.util.function.Predicate<ConformanceVector> predicate) {
        return vectors.stream().anyMatch(predicate);
    }

    private static boolean isTier(ConformanceVector vector) {
        return capabilityOf(vector).valueType() == com.solovis.entitlement.core.model.ValueType.TIER;
    }

    private static boolean isQuantity(ConformanceVector vector) {
        return capabilityOf(vector).valueType() == com.solovis.entitlement.core.model.ValueType.QUANTITY;
    }

    private static boolean declaresOffValue(ConformanceVector vector) {
        return capabilityOf(vector).offValue().isPresent();
    }

    private static boolean isUnlimited(com.solovis.entitlement.core.model.EntitlementValue value) {
        return value instanceof com.solovis.entitlement.core.model.EntitlementValue.Quantity quantity
            && quantity.unlimited();
    }

    private static boolean hasOverrides(ConformanceVector vector) {
        return !vector.fixture().liveOverrides(vector.accountExternalId(), vector.capabilityKey()).isEmpty();
    }

    private static long countOverridesOfKind(ConformanceVector vector, OverrideKind kind) {
        return vector.fixture().liveOverrides(vector.accountExternalId(), vector.capabilityKey()).stream()
            .filter(override -> override.kind() == kind)
            .count();
    }

    private static boolean hasPlanEntitlement(ConformanceVector vector) {
        var account = vector.fixture().account(vector.accountExternalId()).orElseThrow();
        return vector.fixture().planEntitlement(account.planKey(), vector.capabilityKey()).isPresent();
    }

    private static com.solovis.entitlement.core.model.Capability capabilityOf(ConformanceVector vector) {
        return vector.fixture().capability(vector.capabilityKey()).orElseThrow();
    }
}
