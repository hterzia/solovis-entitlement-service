package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.store.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Converts a persisted `*Row` into its `entitlement-core` domain equivalent. */
public final class RowMappers {

    private RowMappers() {}

    public static Capability toCapability(CapabilityRow row, List<CapabilityTierRow> tierRows) {
        var key = new CapabilityKey(row.key());
        var valueType = ValueType.valueOf(row.valueType());
        var tierOrder = tierRows.isEmpty() ? TierOrder.NONE : new TierOrder(tierRows.stream()
            .map(t -> new TierOrder.TierDefinition(t.tierKey(), t.ordinal(), t.displayName())).toList());
        var defaultValue = ValueColumnCodec.toValue(valueType, row.defaultBool(), row.defaultQty(),
            row.defaultQtyUnlimited(), row.defaultTier(), tierOrder);
        Optional<OffValue> offValue = row.hasOffValue()
            ? Optional.of(new OffValue(ValueColumnCodec.toValue(valueType, null, row.offQty(), false, row.offTier(), tierOrder)))
            : Optional.empty();
        var status = Capability.Status.valueOf(row.status());
        Instant retiredAt = row.retiredAt() == null ? null : Instant.parse(row.retiredAt());
        return new Capability(key, row.displayName(), row.description(), valueType, defaultValue, offValue, tierOrder, status, retiredAt);
    }

    public static Plan toPlan(PlanRow row) {
        return new Plan(row.key(), row.name(), Plan.Status.valueOf(row.status()), row.defaultForNewAccounts());
    }

    public static PlanEntitlement toPlanEntitlement(PlanEntitlementRow row, String planKey, Capability capability) {
        var value = ValueColumnCodec.toValue(capability.valueType(), row.boolValue(), row.qtyValue(),
            row.qtyUnlimited(), row.tierValue(), capability.tierOrder());
        return new PlanEntitlement(planKey, capability.key(), value);
    }

    public static AccountOverride toOverride(AccountOverrideRow row, String accountExternalId, Capability capability) {
        var value = ValueColumnCodec.toValue(capability.valueType(), row.boolValue(), row.qtyValue(),
            row.qtyUnlimited(), row.tierValue(), capability.tierOrder());
        // The window must travel with the override, not be re-read later: AccountOverride's
        // shorter constructor defaults both dates to empty, so omitting them here would make every
        // record-backed override look permanently in force (002 c2, c3).
        return new AccountOverride(OptionalLong.of(row.id()), accountExternalId, capability.key(),
            OverrideKind.valueOf(row.kind()), value, Optional.of(row.reason()), Optional.of(row.createdBy()),
            Optional.of(Instant.parse(row.createdAt())),
            Optional.ofNullable(row.startsOn()).map(LocalDate::parse),
            Optional.ofNullable(row.expiresOn()).map(LocalDate::parse));
    }
}
