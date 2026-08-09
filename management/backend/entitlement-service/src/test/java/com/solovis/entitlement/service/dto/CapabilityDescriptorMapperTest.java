package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityDescriptorMapperTest {

    @Test
    void mapsQuantityCapabilityWithNoTiersAndNoOffValue() {
        var capability = new Capability(new CapabilityKey("reports.monthly"), "Monthly reports", "desc",
            ValueType.QUANTITY, EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);

        var dto = CapabilityDescriptorMapper.toDescriptor(capability);

        assertThat(dto.key()).isEqualTo("reports.monthly");
        assertThat(dto.area()).isEqualTo("reports");
        assertThat(dto.valueType()).isEqualTo("QUANTITY");
        assertThat(dto.defaultValue().amount()).isEqualTo(0L);
        assertThat(dto.offValue()).isNull();
        assertThat(dto.tiers()).isNull();
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    @Test
    void mapsTierCapabilityWithAscendingTiers() {
        var tierOrder = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var capability = new Capability(new CapabilityKey("support.tier"), "Support level", null,
            ValueType.TIER, new EntitlementValue.Tier("community", 0), Optional.empty(), tierOrder,
            Capability.Status.ACTIVE, null);

        var dto = CapabilityDescriptorMapper.toDescriptor(capability);

        assertThat(dto.tiers()).extracting(CapabilityDescriptorDto.TierDto::tier)
            .containsExactly("community", "gold");
        assertThat(dto.tiers().get(1).ordinal()).isEqualTo(1);
    }
}
