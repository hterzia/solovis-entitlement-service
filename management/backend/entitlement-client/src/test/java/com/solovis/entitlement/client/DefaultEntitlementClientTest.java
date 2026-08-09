package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultEntitlementClientTest {

    private static final Instant NOW = Instant.parse("2026-08-09T14:05:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":3,"plans":1,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY",\
        "default":{"type":"QUANTITY","amount":0},"offValue":{"type":"QUANTITY","amount":0},\
        "status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50},\
        "api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",\
        "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""",
        """
        {"kind":"footer","version":48211,"recordCount":8}""");

    private AtomicReference<Replica> holder;
    private DefaultEntitlementClient client;

    @BeforeEach
    void setUp() {
        holder = new AtomicReference<>(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        client = DefaultEntitlementClient.forTesting(holder, CLOCK, ClientMetrics.NO_OP);
    }

    @Test
    void aGrantRaisesThePlanBaselineAndTheDecisionCarriesTheSnapshotItWasResolvedAt() {
        var decision = client.check("acct_9931", "reports.monthly");

        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(200));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.snapshotVersion()).isEqualTo(48211L);
        assertThat(decision.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    void aPlanEntitlementIsTheBaselineWhenNoOverrideTouchesTheCapability() {
        assertThat(client.check("acct_9931", "api.access").value())
            .isEqualTo(new EntitlementValue.Switch(true));
    }

    @Test
    void anUnknownCapabilityIsAnErrorAndNeverADenial() {
        assertThatThrownBy(() -> client.check("acct_9931", "no.such.capability"))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void aRetiredCapabilityIsItsOwnErrorRatherThanASilentNo() {
        assertThatThrownBy(() -> client.check("acct_9931", "legacy.export"))
            .isInstanceOf(RetiredCapabilityException.class);
    }

    @Test
    void checkAllCoversEveryNonRetiredCapabilityAtOneSnapshotVersion() {
        var all = client.checkAll("acct_9931");

        assertThat(all.account()).isEqualTo("acct_9931");
        assertThat(all.planKey()).isEqualTo("pro");
        assertThat(all.snapshotVersion()).isEqualTo(48211L);
        assertThat(all.decisions()).hasSize(2);
        assertThat(all.decisions()).extracting(d -> d.capabilityKey())
            .containsExactly("api.access", "reports.monthly");
    }

    @Test
    void everyDecisionInCheckAllSharesOneEvaluationMomentAndOneVersion() {
        var all = client.checkAll("acct_9931");

        assertThat(all.decisions()).allSatisfy(d -> {
            assertThat(d.snapshotVersion()).isEqualTo(all.snapshotVersion());
            assertThat(d.evaluatedAt()).isEqualTo(all.evaluatedAt());
        });
    }

    @Test
    void checkAllOnAnUnknownAccountIsAnError() {
        assertThatThrownBy(() -> client.checkAll("acct_nope"))
            .isInstanceOf(com.solovis.entitlement.core.error.UnknownAccountException.class);
    }

    @Test
    void theCapabilityRegistryIsReadableSoACallerCanInterpretTierValues() {
        assertThat(client.capability("api.access")).isPresent();
        assertThat(client.capability("no.such.capability")).isEmpty();
        assertThat(client.capabilities()).hasSize(3);   // includes the retired one
    }

    @Test
    void healthReportsTheReplicasVersionAndAgeAgainstTheFeedsPublishedAt() {
        var health = client.health();

        assertThat(health.snapshotVersion()).isEqualTo(48211L);
        assertThat(health.snapshotPublishedAt()).isEqualTo(Instant.parse("2026-08-09T14:03:10.900Z"));
        assertThat(health.snapshotAge()).isEqualTo(Duration.ofSeconds(109).plusMillis(100));
        assertThat(health.stale()).isFalse();
        assertThat(health.lastError()).isEmpty();
    }

    @Test
    void decisionsAreCountedSoAnOperatorCanSeeWhichCapabilitiesActuallyGateAnything() {
        var recorded = new java.util.ArrayList<String>();
        var metrics = new ClientMetrics() {
            @Override public void decision(String capabilityKey, boolean allowed) {
                recorded.add(capabilityKey + "=" + allowed);
            }
        };
        var counting = DefaultEntitlementClient.forTesting(holder, CLOCK, metrics);

        counting.check("acct_9931", "api.access");

        assertThat(recorded).containsExactly("api.access=true");
    }
}
