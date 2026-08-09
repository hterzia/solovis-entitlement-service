package com.solovis.entitlement.core.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    @Test
    void createsAnActivePlan() {
        var plan = new Plan("pro", "Pro", Plan.Status.ACTIVE, false);
        assertThat(plan.status()).isEqualTo(Plan.Status.ACTIVE);
    }

    @Test
    void anArchivedPlanCannotBeTheDefault() {
        assertThatThrownBy(() -> new Plan("legacy", "Legacy", Plan.Status.ARCHIVED, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planEntitlementValueCarriesItsCapabilityAndValue() {
        var entitlement = new PlanEntitlement("pro", new CapabilityKey("reports.monthly"),
            EntitlementValue.Quantity.of(50));
        assertThat(entitlement.planKey()).isEqualTo("pro");
        assertThat(entitlement.value()).isEqualTo(EntitlementValue.Quantity.of(50));
    }
}
