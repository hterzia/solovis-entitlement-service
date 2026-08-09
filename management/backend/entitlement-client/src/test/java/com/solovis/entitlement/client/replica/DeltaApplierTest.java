package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.DeltaDtos;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DeltaApplierTest {

    private Replica base;

    private static final String BASE_FEED = String.join("\n",
        """
        {"kind":"header","version":100,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:00:00.000Z",\
        "counts":{"capabilities":1,"plans":2,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY",\
        "default":{"type":"QUANTITY","amount":0},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50}}}""",
        """
        {"kind":"plan","key":"free","status":"ACTIVE","isDefaultForNewAccounts":false,"entitlements":{}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",\
        "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""",
        """
        {"kind":"footer","version":100,"recordCount":7}""");

    @BeforeEach
    void loadBase() {
        base = FullSnapshotReader.read(
            new ByteArrayInputStream(BASE_FEED.getBytes(StandardCharsets.UTF_8)));
    }

    private static DeltaDtos.DeltaResponse delta(long from, long to, String... changeJson) {
        List<JsonNode> changes = java.util.Arrays.stream(changeJson)
            .map(j -> (JsonNode) ClientJson.MAPPER.readTree(j))
            .toList();
        return new DeltaDtos.DeltaResponse(1, from, to, "2026-08-09T14:03:10.900Z", changes);
    }

    @Test
    void planEntitlementChangesSetAndUnsetInOneChange() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.entitlements","planKey":"pro",\
            "set":{"reports.monthly":{"type":"QUANTITY","amount":75}},"unset":["export.parquet"]}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.snapshot().planEntitlement("pro", new CapabilityKey("reports.monthly")))
            .get().extracting(pe -> pe.value()).isEqualTo(EntitlementValue.Quantity.of(75));
    }

    @Test
    void anOverrideCreationIsAddedAndIndexedByItsRef() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.created","ref":"ovr_9002","account":"acct_9931",\
            "capability":"reports.monthly","overrideKind":"HOLD","value":{"type":"QUANTITY","amount":10}}"""));

        assertThat(applied.overridesByRef()).containsKey(9002L);
        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .hasSize(2);
    }

    @Test
    void anOverrideRemovalFindsItsAccountAndCapabilityThroughTheRefIndex() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.removed","ref":"ovr_4471"}"""));

        assertThat(applied.overridesByRef()).doesNotContainKey(4471L);
        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .isEmpty();
    }

    @Test
    void removingARefThisReplicaNeverSawIsANoOpBecauseAFullResyncMayHavePassedIt() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.removed","ref":"ovr_7788"}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.overridesByRef()).containsKey(4471L);
    }

    @Test
    void aRedeliveredOverrideCreationIsNotAppliedTwiceBecauseTheCoreMutatorAppendsBlindly() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"override.created","ref":"ovr_4471","account":"acct_9931",\
            "capability":"reports.monthly","overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}"""));

        assertThat(applied.snapshot().liveOverrides("acct_9931", new CapabilityKey("reports.monthly")))
            .hasSize(1);
        assertThat(applied.version()).isEqualTo(101L);
    }

    @Test
    void retiringACapabilityThisReplicaDoesNotHoldStillAdvancesTheVersion() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"capability.retired","key":"seats.limit"}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.snapshot().capability(new CapabilityKey("seats.limit"))).isEmpty();
        // The capability this replica does hold is untouched by a retirement it wasn't naming.
        assertThat(applied.snapshot().capability(new CapabilityKey("reports.monthly")))
            .get().extracting(Capability::status).isEqualTo(Capability.Status.ACTIVE);
    }

    @Test
    void archivingAPlanThisReplicaDoesNotHoldStillAdvancesTheVersion() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.archived","key":"enterprise"}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.snapshot().plan("enterprise")).isEmpty();
        // Plans this replica does hold are untouched by an archival it wasn't naming.
        assertThat(applied.snapshot().plan("pro")).get()
            .extracting(com.solovis.entitlement.core.model.Plan::status)
            .isEqualTo(com.solovis.entitlement.core.model.Plan.Status.ACTIVE);
    }

    @Test
    void movingTheDefaultToAPlanThisReplicaDoesNotHoldStillAdvancesTheVersionAndClearsThePreviousHolder() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.defaultChanged","key":"enterprise"}"""));

        assertThat(applied.version()).isEqualTo(101L);
        assertThat(applied.snapshot().plan("enterprise")).isEmpty();
        // The code clears the previous holder before discovering the target is absent, so this
        // replica is left with no default plan until the next full resync or plan.upserted catches
        // it up — pinned deliberately rather than left as an accident (see task-6-report.md).
        assertThat(applied.snapshot().plan("pro").orElseThrow().defaultForNewAccounts()).isFalse();
        assertThat(applied.snapshot().plans().stream().filter(p -> p.defaultForNewAccounts()).count())
            .isEqualTo(0L);
    }

    @Test
    void accountUpsertCoversReassignmentAsWellAsCreation() {
        var applied = DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":101,"kind":"account.upserted","external":"acct_9931","planKey":"free"}""",
            """
            {"version":102,"kind":"account.upserted","external":"acct_new","planKey":"pro"}"""));

        assertThat(applied.snapshot().account("acct_9931")).get()
            .extracting(a -> a.planKey()).isEqualTo("free");
        assertThat(applied.snapshot().account("acct_new")).isPresent();
    }

    @Test
    void aRetirementMarksTheCapabilityWithoutDroppingItSoTheRetiredErrorStaysAvailable() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"capability.retired","key":"reports.monthly"}"""));

        var capability = applied.snapshot().capability(new CapabilityKey("reports.monthly"));
        assertThat(capability).isPresent();
        assertThat(capability.get().status()).isEqualTo(Capability.Status.RETIRED);
        assertThat(capability.get().retiredAt()).isNotNull();
    }

    @Test
    void capabilityUpsertReadsTheNestedDescriptorNotAFlatLine() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"capability.upserted","capability":{"key":"seats.limit","area":"seats",\
            "displayName":"Seats","valueType":"QUANTITY","default":{"type":"QUANTITY","amount":5},\
            "status":"ACTIVE"}}"""));

        assertThat(applied.snapshot().capability(new CapabilityKey("seats.limit"))).isPresent();
    }

    @Test
    void archivingAPlanMarksItAndStripsAnyDefaultDesignation() {
        var applied = DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":101,"kind":"plan.defaultChanged","key":"free"}""",
            """
            {"version":102,"kind":"plan.archived","key":"pro"}"""));

        var pro = applied.snapshot().plan("pro").orElseThrow();
        assertThat(pro.status()).isEqualTo(com.solovis.entitlement.core.model.Plan.Status.ARCHIVED);
        assertThat(pro.defaultForNewAccounts()).isFalse();
    }

    @Test
    void movingTheDefaultDesignationClearsThePreviousHolderSoExactlyOnePlanEverHoldsIt() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"plan.defaultChanged","key":"free"}"""));

        assertThat(applied.snapshot().plan("pro").orElseThrow().defaultForNewAccounts()).isFalse();
        assertThat(applied.snapshot().plan("free").orElseThrow().defaultForNewAccounts()).isTrue();
        assertThat(applied.snapshot().plans().stream().filter(p -> p.defaultForNewAccounts()).count())
            .isEqualTo(1L);
    }

    @Test
    void anEmptyDeltaLeavesTheReplicaExactlyWhereItWas() {
        var applied = DeltaApplier.apply(base, delta(100, 100));

        assertThat(applied.version()).isEqualTo(100L);
        assertThat(applied.snapshot()).isSameAs(base.snapshot());
    }

    @Test
    void aBatchThatDoesNotStartAtTheNextVersionIsAGapAndIsRejectedRatherThanGuessedAt() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 105, """
            {"version":103,"kind":"plan.archived","key":"free"}""")))
            .isInstanceOf(DeltaApplier.OutOfOrderDeltaException.class);
    }

    @Test
    void changesOutOfAscendingOrderAreRejected() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 102,
            """
            {"version":102,"kind":"plan.archived","key":"free"}""",
            """
            {"version":101,"kind":"account.upserted","external":"a","planKey":"pro"}""")))
            .isInstanceOf(DeltaApplier.OutOfOrderDeltaException.class);
    }

    @Test
    void anUnknownChangeKindStopsTheSyncRatherThanBeingSkippedIntoSilentDivergence() {
        assertThatThrownBy(() -> DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"conformance.changed","vectors":[]}""")))
            .isInstanceOf(DeltaApplier.UnknownChangeKindException.class)
            .extracting(e -> ((DeltaApplier.UnknownChangeKindException) e).kind())
            .isEqualTo("conformance.changed");
    }

    @Test
    void theResultingReplicaCarriesTheDeltasPublishedAtSoFreshnessTracksTheFeed() {
        var applied = DeltaApplier.apply(base, delta(100, 101, """
            {"version":101,"kind":"account.upserted","external":"acct_x","planKey":"pro"}"""));

        assertThat(applied.publishedAt())
            .isEqualTo(java.time.Instant.parse("2026-08-09T14:03:10.900Z"));
    }
}
