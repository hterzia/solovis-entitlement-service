package com.solovis.entitlement.service.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedDatasetTest {

    private SeedDataset load(String json) {
        return SeedDataset.of(json.getBytes(StandardCharsets.UTF_8));
    }

    private static final String VALID = """
        {
          "seedVersion": 1,
          "timelineDays": 30,
          "capabilities": [
            {"day": 0, "key": "api.access", "displayName": "API access", "valueType": "SWITCH",
             "defaultValue": {"type": "SWITCH", "enabled": false}}
          ],
          "plans": [
            {"day": 1, "key": "free", "name": "Evaluation", "isDefault": true,
             "entitlements": {"api.access": {"type": "SWITCH", "enabled": true}}}
          ],
          "accounts": [{"day": 2, "externalId": "acct_1", "name": "One", "plan": "free"}],
          "events": [
            {"day": 3, "type": "override.create", "account": "acct_1", "capability": "api.access",
             "kind": "HOLD", "value": {"type": "SWITCH", "enabled": false}, "reason": "Suspended", "ref": "h1"},
            {"day": 4, "type": "override.remove", "ref": "h1", "reason": "Cleared"}
          ]
        }
        """;

    private static byte[] shipped() {
        try (var in = SeedDatasetTest.class.getResourceAsStream("/seed/demo-seed.json")) {
            assertThat(in).as("seed/demo-seed.json must be on the classpath").isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aWellFormedDatasetValidates() {
        SeedDataset dataset = load(VALID);

        dataset.validate();

        assertThat(dataset.capabilities()).hasSize(1);
        assertThat(dataset.fingerprint()).startsWith("v1:");
    }

    @Test
    void theFingerprintChangesWithTheContent() {
        assertThat(load(VALID).fingerprint())
            .isNotEqualTo(load(VALID.replace("Evaluation", "Eval")).fingerprint());
    }

    @Test
    void aPlanEntitlementForAnUndeclaredCapabilityIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace(
                "\"api.access\": {\"type\": \"SWITCH\", \"enabled\": true}",
                "\"reports.monthly\": {\"type\": \"QUANTITY\", \"amount\": 5}")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reports.monthly");
    }

    @Test
    void anAccountOnAnUndeclaredPlanIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"plan\": \"free\"", "\"plan\": \"enterprise\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("enterprise");
    }

    @Test
    void removingAnOverrideThatWasNeverCreatedIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace(
                "\"ref\": \"nope\"", "\"ref\": \"nope\"").replace(
                "{\"day\": 4, \"type\": \"override.remove\", \"ref\": \"h1\"",
                "{\"day\": 4, \"type\": \"override.remove\", \"ref\": \"nope\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void exactlyOneDefaultPlanIsRequired() {
        assertThatThrownBy(() -> load(VALID.replace("\"isDefault\": true", "\"isDefault\": false")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly one default plan");
    }

    @Test
    void aDayBeyondTheTimelineIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"day\": 4", "\"day\": 99")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("99");
    }

    @Test
    void referencingACapabilityBeforeItIsDeclaredIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace(
                "{\"day\": 0, \"key\": \"api.access\"", "{\"day\": 5, \"key\": \"api.access\"")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api.access");
    }

    @Test
    void aWindowStartingBeforeTheDayItIsWrittenIsRejected() {
        // WindowRules refuses a start before "today", and on a wound clock today is the authored
        // day. Catching it here turns a failed startup into a failed build.
        assertThatThrownBy(() -> load(VALID.replace(
                "\"reason\": \"Suspended\"", "\"reason\": \"Suspended\", \"startsOnDay\": 1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("starts on day 1");
    }

    @Test
    void aWindowEndingBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace("\"reason\": \"Suspended\"",
                "\"reason\": \"Suspended\", \"startsOnDay\": 10, \"expiresOnDay\": 5")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("describes nothing");
    }

    @Test
    void aWindowMayRunPastTheEndOfTheTimeline() {
        // A pending or still-running override is exactly this: authored inside the timeline,
        // expiring after the demo's present.
        load(VALID.replace("\"reason\": \"Suspended\"",
            "\"reason\": \"Suspended\", \"startsOnDay\": 20, \"expiresOnDay\": 400")).validate();
    }

    @Test
    void aWindowThatEndedBeforeItWasWrittenIsRejected() {
        assertThatThrownBy(() -> load(VALID.replace(
                "\"reason\": \"Suspended\"", "\"reason\": \"Suspended\", \"expiresOnDay\": 1")).validate())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expires on day 1");
    }

    @Test
    void theShippedDatasetIsValid() {
        SeedDataset dataset = SeedDataset.of(shipped());

        dataset.validate();

        assertThat(dataset.capabilities()).hasSize(16);
        assertThat(dataset.plans()).hasSize(5);
        assertThat(dataset.accounts()).hasSizeGreaterThan(55);
        assertThat(dataset.events()).extracting(SeedDataset.Event::kind).contains("HOLD");
    }

    @Test
    void theShippedDatasetKeepsTheFixturesTheEndToEndSuiteLocatesBy() {
        SeedDataset dataset = SeedDataset.of(shipped());

        assertThat(dataset.capabilities()).extracting(SeedDataset.Capability::key)
            .contains("api.access", "reports.monthly", "seats.count", "support.tier");
        assertThat(dataset.plans()).extracting(SeedDataset.Plan::key).contains("free", "pro");
        assertThat(dataset.accounts()).extracting(SeedDataset.Account::externalId)
            .contains("acct_9931", "acct_1177");
        assertThat(dataset.events())
            .filteredOn(e -> "acct_9931".equals(e.account()) && "reports.monthly".equals(e.capability()))
            .anySatisfy(e -> {
                assertThat(e.kind()).isEqualTo("GRANT");
                assertThat(e.value().amount()).isEqualTo(200L);
                assertThat(e.reason()).isEqualTo("Renewal concession — Q3 pilot");
            });
    }

    @Test
    void theWindowsFlagshipCarriesAllFourStandings() {
        SeedDataset dataset = SeedDataset.of(shipped());
        int timeline = dataset.timelineDays();
        var sterling = dataset.events().stream().filter(e -> "acct_2947".equals(e.account())).toList();

        // ENDED — the standing that could not be seeded before the clock could be wound.
        assertThat(sterling).anySatisfy(e ->
            assertThat(e.expiresOnDay()).isNotNull().matches(d -> d < timeline));
        // IN FORCE, with an expiry still ahead of the demo's present.
        assertThat(sterling).anySatisfy(e -> {
            assertThat(e.startsOnDay()).isNotNull().matches(d -> d <= timeline);
            assertThat(e.expiresOnDay()).isNotNull().matches(d -> d > timeline);
        });
        // PENDING — has not begun by the time the demo is served.
        assertThat(sterling).anySatisfy(e ->
            assertThat(e.startsOnDay()).isNotNull().matches(d -> d > timeline));
        // REMOVED.
        assertThat(dataset.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(SeedDataset.OVERRIDE_REMOVE);
            assertThat(e.ref()).isEqualTo("sterling-removed");
        });
    }

    @Test
    void theThreeStandingsSeededByZeroZeroTwoSurviveOnTheirAccount() {
        SeedDataset dataset = SeedDataset.of(shipped());

        // 002 put these on acct_1177 on purpose; screen 3's grouping renders them and windows.spec.ts
        // uses that account. They are carried across verbatim, reasons included.
        assertThat(dataset.events()).filteredOn(e -> "acct_1177".equals(e.account()))
            .extracting(SeedDataset.Event::reason)
            .contains("Trial seats through the end of today", "Reporting pilot agreed for next month",
                "Suspended pending investigation");
    }
}
