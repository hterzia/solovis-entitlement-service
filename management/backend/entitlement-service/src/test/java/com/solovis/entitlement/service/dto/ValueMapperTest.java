package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.error.EntitlementApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueMapperTest {

    private static final Capability QUANTITY_CAP = new Capability(
        new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
        EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);

    private static final Capability TIER_CAP = new Capability(
        new CapabilityKey("support.tier"), "Support", null, ValueType.TIER,
        new EntitlementValue.Tier("community", 0), Optional.empty(),
        new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold"))),
        Capability.Status.ACTIVE, null);

    @Test
    void switchToDtoOmitsUnrelatedFields() {
        var dto = ValueMapper.toDto(new EntitlementValue.Switch(true));
        assertThat(dto.type()).isEqualTo("SWITCH");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.amount()).isNull();
        assertThat(dto.unlimited()).isNull();
        assertThat(dto.tier()).isNull();
    }

    @Test
    void unlimitedQuantityToDtoCarriesNoAmount() {
        var dto = ValueMapper.toDto(EntitlementValue.Quantity.unbounded());
        assertThat(dto.type()).isEqualTo("QUANTITY");
        assertThat(dto.unlimited()).isTrue();
        assertThat(dto.amount()).isNull();
    }

    @Test
    void tierToDtoCarriesOrdinal() {
        var dto = ValueMapper.toDto(new EntitlementValue.Tier("gold", 1));
        assertThat(dto.type()).isEqualTo("TIER");
        assertThat(dto.tier()).isEqualTo("gold");
        assertThat(dto.ordinal()).isEqualTo(1);
    }

    @Test
    void fromDtoAcceptsAmount() {
        var dto = new ValueDto("QUANTITY", null, 50L, null, null, null);
        var value = ValueMapper.fromDto(dto, QUANTITY_CAP);
        assertThat(value).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void fromDtoAcceptsUnlimited() {
        var dto = new ValueDto("QUANTITY", null, null, true, null, null);
        assertThat(ValueMapper.fromDto(dto, QUANTITY_CAP)).isEqualTo(EntitlementValue.Quantity.unbounded());
    }

    @Test
    void fromDtoRejectsBothAmountAndUnlimited() {
        var dto = new ValueDto("QUANTITY", null, 50L, true, null, null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, QUANTITY_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void fromDtoRejectsTypeMismatch() {
        var dto = new ValueDto("SWITCH", true, null, null, null, null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, QUANTITY_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALUE_TYPE_MISMATCH);
    }

    @Test
    void fromDtoIgnoresRequestOrdinalAndTrustsTierKey() {
        var dto = new ValueDto("TIER", null, null, null, "gold", 999); // wrong ordinal on the wire
        var value = ValueMapper.fromDto(dto, TIER_CAP);
        assertThat(value).isEqualTo(new EntitlementValue.Tier("gold", 1)); // authoritative ordinal from the capability
    }

    @Test
    void fromDtoRejectsNegativeAmount() {
        var dto = new ValueDto("QUANTITY", null, -5L, null, null, null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, QUANTITY_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .satisfies(ex -> {
                var apiEx = (EntitlementApiException) ex;
                assertThat(apiEx.errorCode()).isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
                @SuppressWarnings("unchecked")
                var violations = (List<String>) apiEx.extraProperties().get("violations");
                assertThat(violations).isNotEmpty();
            });
    }

    @Test
    void fromDtoRejectsUndeclaredTier() {
        var dto = new ValueDto("TIER", null, null, null, "platinum", null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, TIER_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.UNKNOWN_TIER);
    }
}
