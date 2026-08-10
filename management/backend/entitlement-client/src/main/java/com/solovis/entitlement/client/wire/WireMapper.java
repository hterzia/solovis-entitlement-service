package com.solovis.entitlement.client.wire;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OffValue;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Wire DTOs to {@code entitlement-core} domain objects. One direction only; the SDK never writes to the feed. */
public final class WireMapper {

    private static final String REF_PREFIX = "ovr_";

    private WireMapper() {}

    public static EntitlementValue toValue(ValueDto dto) {
        if (dto == null || dto.type() == null) {
            throw new IllegalArgumentException("Malformed feed: value has no type.");
        }
        return switch (dto.type()) {
            case "SWITCH" -> {
                if (dto.enabled() == null) {
                    throw new IllegalArgumentException("Malformed feed: SWITCH value has no 'enabled'.");
                }
                yield new EntitlementValue.Switch(dto.enabled());
            }
            case "QUANTITY" -> {
                boolean unlimited = Boolean.TRUE.equals(dto.unlimited());
                if (unlimited && dto.amount() != null) {
                    throw new IllegalArgumentException(
                        "Malformed feed: QUANTITY declares both 'unlimited' and 'amount'.");
                }
                if (!unlimited && dto.amount() == null) {
                    throw new IllegalArgumentException(
                        "Malformed feed: QUANTITY declares neither 'unlimited' nor 'amount'.");
                }
                yield unlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(dto.amount());
            }
            case "TIER" -> {
                if (dto.tier() == null || dto.ordinal() == null) {
                    throw new IllegalArgumentException("Malformed feed: TIER value needs 'tier' and 'ordinal'.");
                }
                yield new EntitlementValue.Tier(dto.tier(), dto.ordinal());
            }
            default -> throw new IllegalArgumentException("Malformed feed: unknown value type '" + dto.type() + "'.");
        };
    }

    /**
     * @param feedPublishedAt stands in for {@code retiredAt}, which the feed's capability line does
     *     not carry. A retirement timestamp never enters resolution — only {@code isRetired()} does —
     *     so an approximation here cannot change an answer.
     */
    public static Capability toCapability(FeedDtos.CapabilityLine line, Instant feedPublishedAt) {
        var key = new CapabilityKey(line.key());
        var valueType = ValueType.valueOf(line.valueType());
        var status = Capability.Status.valueOf(line.status());
        var tierOrder = line.tiers() == null || line.tiers().isEmpty()
            ? TierOrder.NONE
            : new TierOrder(line.tiers().stream()
                .map(t -> new TierOrder.TierDefinition(t.tier(), t.ordinal(), t.displayName()))
                .toList());
        var offValue = line.offValue() == null
            ? Optional.<OffValue>empty()
            : Optional.of(new OffValue(toValue(line.offValue())));

        return new Capability(
            key,
            key.value(),   // the feed omits displayName; a replica does not render, so the key suffices
            null,          // the feed omits description for the same reason
            valueType,
            toValue(line.defaultValue()),
            offValue,
            tierOrder,
            status,
            status == Capability.Status.RETIRED ? feedPublishedAt : null);
    }

    public static Plan toPlan(FeedDtos.PlanLine line) {
        return new Plan(
            line.key(),
            line.key(),   // the feed omits the display name; resolution never reads it
            Plan.Status.valueOf(line.status()),
            line.isDefaultForNewAccounts());
    }

    public static AccountAssignment toAccount(FeedDtos.AccountLine line) {
        return new AccountAssignment(line.external(), line.planKey());
    }

    /** Reason, author and timestamp are deliberately absent — they never reach a replica. */
    public static AccountOverride toOverride(FeedDtos.OverrideLine line) {
        return new AccountOverride(
            OptionalLong.of(refToId(line.ref())),
            line.account(),
            new CapabilityKey(line.capability()),
            OverrideKind.valueOf(line.overrideKind()),
            toValue(line.value()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    }

    /** {@code "ovr_4471"} to {@code 4471}. The ref is opaque to resolution but load-bearing for removal. */
    public static long refToId(String ref) {
        if (ref == null || !ref.startsWith(REF_PREFIX)) {
            throw new IllegalArgumentException("Malformed feed: override ref '" + ref + "' is not 'ovr_<id>'.");
        }
        try {
            return Long.parseLong(ref.substring(REF_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed feed: override ref '" + ref + "' has no numeric id.", e);
        }
    }
}
