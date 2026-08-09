package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccountOverrideTest {

    @Test
    void fullOverrideAsBuiltByTheManagementServiceCarriesEveryField() {
        var override = new AccountOverride(
            OptionalLong.of(4471), "acct_9931", new CapabilityKey("reports.monthly"),
            OverrideKind.GRANT, EntitlementValue.Quantity.of(200),
            Optional.of("Renewal concession"), Optional.of("j.okafor"), Optional.of(Instant.parse("2026-06-02T09:12:44Z")));

        assertThat(override.id()).isEqualTo(OptionalLong.of(4471));
        assertThat(override.reason()).contains("Renewal concession");
    }

    @Test
    void projectedOverrideAsCarriedByAReplicaOmitsMetadata() {
        var override = new AccountOverride(
            OptionalLong.empty(), "acct_9931", new CapabilityKey("reports.monthly"),
            OverrideKind.HOLD, EntitlementValue.Quantity.of(0),
            Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(override.id()).isEmpty();
        assertThat(override.reason()).isEmpty();
        assertThat(override.value()).isEqualTo(EntitlementValue.Quantity.of(0));
    }

    @Test
    void accountAssignmentCarriesTheExternalIdAndPlanKey() {
        var assignment = new AccountAssignment("acct_9931", "pro");
        assertThat(assignment.accountExternalId()).isEqualTo("acct_9931");
        assertThat(assignment.planKey()).isEqualTo("pro");
    }
}
