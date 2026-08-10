package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FullSnapshotReaderTest {

    private static final String HEADER = """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":2,"plans":1,"accounts":1,"overrides":1}}""";

    private static final String CAP_SWITCH = """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""";

    private static final String CAP_RETIRED = """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""";

    private static final String PLAN = """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}""";

    private static final String ACCOUNT = """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""";

    private static final String OVERRIDE = """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"api.access",\
        "overrideKind":"HOLD","value":{"type":"SWITCH","enabled":false}}""";

    private static final String FOOTER = """
        {"kind":"footer","version":48211,"recordCount":7}""";

    private static InputStream feed(String... lines) {
        return new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCompleteFeedBecomesAReplicaAtTheHeadersVersion() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.version()).isEqualTo(48211L);
        assertThat(replica.publishedAt()).isEqualTo(Instant.parse("2026-08-09T14:03:10.900Z"));
        assertThat(replica.format()).isEqualTo(1);
        assertThat(replica.resolverContract()).isEqualTo(1);
    }

    @Test
    void everyRecordKindLandsInTheSnapshotItBelongsIn() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));
        var snapshot = replica.snapshot();

        assertThat(snapshot.capabilities()).hasSize(2);
        assertThat(snapshot.plan("pro")).isPresent();
        assertThat(snapshot.planEntitlement("pro", new CapabilityKey("api.access")))
            .get().extracting(pe -> pe.value())
            .isEqualTo(new EntitlementValue.Switch(true));
        assertThat(snapshot.account("acct_9931")).isPresent();
        assertThat(snapshot.liveOverrides("acct_9931", new CapabilityKey("api.access"))).hasSize(1);
    }

    @Test
    void retiredCapabilitiesAreKeptSoTheReplicaCanRaiseTheRetiredErrorRatherThanASilentDenial() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.snapshot().capability(new CapabilityKey("legacy.export")))
            .get().extracting(c -> c.isRetired()).isEqualTo(true);
        assertThat(replica.snapshot().activeCapabilities()).hasSize(1);
    }

    @Test
    void overridesAreIndexedByRefSoALaterRemovalDeltaCanFindThem() {
        var replica = FullSnapshotReader.read(
            feed(HEADER, CAP_SWITCH, CAP_RETIRED, PLAN, ACCOUNT, OVERRIDE, FOOTER));

        assertThat(replica.overridesByRef()).containsKey(4471L);
        assertThat(replica.overridesByRef().get(4471L).capabilityKey().value()).isEqualTo("api.access");
    }

    @Test
    void truncatedFeedMissingItsFooterIsDiscardedWholeRatherThanPartiallyApplied() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, PLAN, ACCOUNT)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("footer");
    }

    @Test
    void aFooterNamingADifferentVersionThanTheHeaderIsDiscarded() {
        var wrongFooter = "{\"kind\":\"footer\",\"version\":48209,\"recordCount\":7}";

        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, wrongFooter)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("48209");
    }

    @Test
    void aFeedThatDoesNotStartWithItsHeaderIsDiscarded() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(CAP_SWITCH, HEADER, FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("header");
    }

    @Test
    void anUnknownLineKindIsSkippedSoAnOlderReplicaKeepsSyncingWhenTheServiceAddsARecordType() {
        var future = "{\"kind\":\"somethingAddedNextYear\",\"data\":1}";

        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, future, FOOTER));

        assertThat(replica.snapshot().capabilities()).hasSize(1);
    }

    /**
     * The only untested branch of the whole-or-nothing rule that stops a truncated body becoming a
     * wrong answer: a line after the footer means the feed is not what it claims to be, so it must
     * be discarded rather than silently ignored.
     */
    @Test
    void aLineAppearingAfterTheFooterIsDiscardedRatherThanSilentlyIgnored() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, FOOTER, PLAN)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("footer");
    }

    @Test
    void blankLinesAreToleratedBecauseTrailingNewlinesAreNormal() {
        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, FOOTER, ""));

        assertThat(replica.version()).isEqualTo(48211L);
    }

    @Test
    void conformanceVectorsAreCollectedForTheGateToEvaluate() {
        var vector = """
            {"kind":"conformance","id":"api.access: plan on, hold off -> off",\
            "model":{"account":"acct_c1","capability":"api.access",\
            "capabilities":[{"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
            "default":{"type":"SWITCH","enabled":false},\
            "status":"ACTIVE"}],\
            "plans":[{"kind":"plan","key":"p","status":"ACTIVE","isDefaultForNewAccounts":true,\
            "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}],\
            "accounts":[{"kind":"account","external":"acct_c1","planKey":"p"}],\
            "overrides":[{"kind":"override","ref":"ovr_1","account":"acct_c1","capability":"api.access",\
            "overrideKind":"HOLD","value":{"type":"SWITCH","enabled":false}}]},\
            "expect":{"allowed":false,"value":{"type":"SWITCH","enabled":false}}}""";

        var replica = FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, vector, FOOTER));

        assertThat(replica.vectors()).hasSize(1);
        assertThat(replica.vectors().get(0).name()).isEqualTo("api.access: plan on, hold off -> off");
        assertThat(replica.vectors().get(0).expectedAllowed()).isFalse();
        assertThat(replica.vectors().get(0).accountExternalId()).isEqualTo("acct_c1");
    }

    @Test
    void anOverrideLineWithARefThatIsNotOvrIdIsWrappedAsAMalformedFeedExceptionRatherThanEscapingRaw() {
        var badOverride = """
            {"kind":"override","ref":"not-a-valid-ref","account":"acct_9931","capability":"api.access",\
            "overrideKind":"HOLD","value":{"type":"SWITCH","enabled":false}}""";

        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, badOverride, FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("override");
    }

    @Test
    void aCapabilityLineViolatingACoreModelInvariantIsWrappedAsAMalformedFeedExceptionRatherThanEscapingRaw() {
        var invalidCapability = """
            {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
            "default":{"type":"SWITCH","enabled":false},"offValue":{"type":"SWITCH","enabled":false},\
            "status":"ACTIVE"}""";

        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, invalidCapability, FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("capability");
    }

    @Test
    void aConformanceLineMissingOneOfItsModelKeysIsWrappedAsAMalformedFeedExceptionRatherThanEscapingRaw() {
        var incompleteVector = """
            {"kind":"conformance","id":"missing plans array",\
            "model":{"account":"acct_c1","capability":"api.access",\
            "capabilities":[],"accounts":[],"overrides":[]},\
            "expect":{"allowed":false,"value":{"type":"SWITCH","enabled":false}}}""";

        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, CAP_SWITCH, incompleteVector, FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class)
            .hasMessageContaining("conformance");
    }

    @Test
    void aSyntacticallyInvalidJsonLineIsWrappedAsAMalformedFeedExceptionRatherThanEscapingRaw() {
        assertThatThrownBy(() -> FullSnapshotReader.read(feed(HEADER, "{not valid json", FOOTER)))
            .isInstanceOf(FullSnapshotReader.MalformedFeedException.class);
    }
}
