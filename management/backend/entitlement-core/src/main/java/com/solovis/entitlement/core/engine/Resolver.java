package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.order.Generosity;
import com.solovis.entitlement.core.view.EntitlementView;
import java.time.Instant;
import java.util.List;

/**
 * Implements spec §4's rule: the effective value is the most generous of the plan and its
 * GRANTs, then capped by the strictest HOLD. {@link #resolve} is the hot path — no trace is
 * built, so nothing beyond the running winner is allocated. {@link #explain} runs the identical
 * arithmetic and layers a full {@link Trace} on top (Task 11), so the two can never disagree
 * (c24).
 */
public final class Resolver {

    private Resolver() {}

    public static Decision resolve(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var account = view.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var capability = view.capability(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey.value()));
        if (capability.isRetired()) {
            throw new RetiredCapabilityException(capabilityKey.value());
        }

        EntitlementValue baseline = view.planEntitlement(account.planKey(), capabilityKey)
            .map(pe -> pe.value())
            .orElse(capability.defaultValue());

        List<AccountOverride> overrides = view.liveOverrides(accountExternalId, capabilityKey);

        EntitlementValue afterGrants = baseline;
        for (var override : overrides) {
            if (override.kind() == OverrideKind.GRANT) {
                afterGrants = Generosity.mostGenerous(afterGrants, override.value());
            }
        }

        EntitlementValue result = afterGrants;
        for (var override : overrides) {
            if (override.kind() == OverrideKind.HOLD) {
                result = Generosity.mostRestrictive(result, override.value());
            }
        }

        EntitlementValue finalResult = result;
        boolean allowed = capability.effectiveOffValue()
            .map(off -> !off.equals(finalResult))
            .orElse(true);

        return new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
    }
}
