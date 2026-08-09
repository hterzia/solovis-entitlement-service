package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.order.Generosity;
import com.solovis.entitlement.core.view.EntitlementView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Implements spec §4's rule: the effective value is the most generous of the plan and its
 * GRANTs, then capped by the strictest HOLD. {@link #resolve} is the hot path — no trace is
 * built, so nothing beyond the running winner is allocated. {@link #explain} runs the identical
 * arithmetic and layers a full {@link Trace} on top, so the two can never disagree (c24).
 */
public final class Resolver {

    private Resolver() {}

    public static Decision resolve(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var lookup = lookUp(view, accountExternalId, capabilityKey);

        EntitlementValue afterGrants = lookup.baseline;
        for (var override : lookup.overrides) {
            if (override.kind() == OverrideKind.GRANT) {
                afterGrants = Generosity.mostGenerous(afterGrants, override.value());
            }
        }

        EntitlementValue result = afterGrants;
        for (var override : lookup.overrides) {
            if (override.kind() == OverrideKind.HOLD) {
                result = Generosity.mostRestrictive(result, override.value());
            }
        }

        boolean allowed = computeAllowed(lookup.capability, result);
        return new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
    }

    public static Explanation explain(
        EntitlementView view, String accountExternalId, CapabilityKey capabilityKey, Instant evaluatedAt) {

        var lookup = lookUp(view, accountExternalId, capabilityKey);

        var baselineEntry = new TraceEntry(
            lookup.baselineSource, OptionalLong.empty(), lookup.baselinePlanKey,
            lookup.baseline, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Grants: the top candidate is WON only if it actually beats the plan (c11) — otherwise
        // it is LOST_NOT_MORE_GENEROUS_THAN_PLAN, a reason distinct from losing to another grant.
        var grantCandidates = candidatesOfKind(lookup.overrides, OverrideKind.GRANT);
        var topGrant = pickWinner(grantCandidates, true);
        boolean grantApplied = topGrant.isPresent() && Generosity.compare(topGrant.get().value(), lookup.baseline) > 0;
        EntitlementValue afterGrants = grantApplied ? topGrant.get().value() : lookup.baseline;
        var grants = grantTraceEntries(grantCandidates, topGrant, grantApplied);
        Optional<TraceEntry> grantWinnerEntry = grantApplied
            ? grants.stream().filter(e -> e.overrideId().equals(topGrant.get().id())).findFirst()
            : Optional.empty();

        // Holds: the most restrictive candidate is always marked WON in its own list, even when
        // it does not change the result — holdWinner (below) being empty is what records that it
        // did not apply (decision-api.md, "Ties are deterministic").
        var holdCandidates = candidatesOfKind(lookup.overrides, OverrideKind.HOLD);
        var topHold = pickWinner(holdCandidates, false);
        boolean holdApplied = topHold.isPresent() && Generosity.compare(topHold.get().value(), afterGrants) < 0;
        EntitlementValue result = holdApplied ? topHold.get().value() : afterGrants;
        var holds = holdTraceEntries(holdCandidates, topHold);
        Optional<TraceEntry> holdWinnerEntry = holdApplied
            ? holds.stream().filter(e -> e.overrideId().equals(topHold.get().id())).findFirst()
            : Optional.empty();

        boolean allowed = computeAllowed(lookup.capability, result);
        var trace = new Trace(baselineEntry, grants, grantWinnerEntry, holds, holdWinnerEntry, result, allowed);
        var decision = new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
        return new Explanation(decision, trace);
    }

    private static boolean computeAllowed(Capability capability, EntitlementValue result) {
        return capability.effectiveOffValue().map(off -> !off.equals(result)).orElse(true);
    }

    private static Lookup lookUp(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey) {
        AccountAssignment account = view.account(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        Capability capability = view.capability(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey.value()));
        if (capability.isRetired()) {
            throw new RetiredCapabilityException(capabilityKey.value());
        }

        var planEntitlement = view.planEntitlement(account.planKey(), capabilityKey);
        EntitlementValue baseline = planEntitlement.map(pe -> pe.value()).orElse(capability.defaultValue());
        TraceSource baselineSource = planEntitlement.isPresent() ? TraceSource.PLAN : TraceSource.CAPABILITY_DEFAULT;
        Optional<String> baselinePlanKey = planEntitlement.isPresent() ? Optional.of(account.planKey()) : Optional.empty();

        List<AccountOverride> overrides = view.liveOverrides(accountExternalId, capabilityKey);
        return new Lookup(capability, baseline, baselineSource, baselinePlanKey, overrides);
    }

    private static List<AccountOverride> candidatesOfKind(List<AccountOverride> overrides, OverrideKind kind) {
        var result = new ArrayList<AccountOverride>();
        for (var override : overrides) {
            if (override.kind() == kind) {
                result.add(override);
            }
        }
        return result;
    }

    /**
     * The single candidate that decides its group (most generous for grants, most restrictive
     * for holds), or empty if none exist. Ties on the deciding value are marked won by the
     * highest override id — a presentational rule that never changes the computed value
     * (decision-api.md, "Ties are deterministic"). Whether this candidate actually changes the
     * result (beats the plan, or restricts below the post-grant value) is a separate question,
     * decided by the caller against the baseline it is being compared to.
     */
    private static Optional<AccountOverride> pickWinner(List<AccountOverride> candidates, boolean generous) {
        AccountOverride winner = null;
        for (var candidate : candidates) {
            if (winner == null) {
                winner = candidate;
                continue;
            }
            int cmp = Generosity.compare(candidate.value(), winner.value());
            boolean better = generous ? cmp > 0 : cmp < 0;
            boolean tiedNewer = cmp == 0 && candidate.id().orElse(Long.MIN_VALUE) > winner.id().orElse(Long.MIN_VALUE);
            if (better || tiedNewer) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }

    /**
     * A grant has two distinct ways to lose: to another grant, or to the plan itself. The top
     * candidate gets {@code WON} only if it actually beat the plan ({@code applied}); every other
     * candidate lost to it and is marked accordingly (c23 — denial explained as fully as a grant).
     */
    private static List<TraceEntry> grantTraceEntries(List<AccountOverride> candidates, Optional<AccountOverride> top, boolean applied) {
        var entries = new ArrayList<TraceEntry>();
        for (var candidate : candidates) {
            boolean isTop = top.isPresent() && top.get().id().equals(candidate.id());
            Outcome outcome = isTop
                ? (applied ? Outcome.WON : Outcome.LOST_NOT_MORE_GENEROUS_THAN_PLAN)
                : Outcome.LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT;
            entries.add(toTraceEntry(candidate, outcome));
        }
        return entries;
    }

    /**
     * The most restrictive HOLD is always {@code WON} within its own list, even when it does not
     * change the result — {@code holdWinner} being empty on {@link Trace} is what records that it
     * did not apply (decision-api.md, "Ties are deterministic").
     */
    private static List<TraceEntry> holdTraceEntries(List<AccountOverride> candidates, Optional<AccountOverride> top) {
        var entries = new ArrayList<TraceEntry>();
        for (var candidate : candidates) {
            boolean isTop = top.isPresent() && top.get().id().equals(candidate.id());
            Outcome outcome = isTop ? Outcome.WON : Outcome.LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD;
            entries.add(toTraceEntry(candidate, outcome));
        }
        return entries;
    }

    private static TraceEntry toTraceEntry(AccountOverride candidate, Outcome outcome) {
        return new TraceEntry(
            TraceSource.PLAN, candidate.id(), Optional.empty(), candidate.value(),
            candidate.reason(), candidate.createdBy(), candidate.createdAt(), Optional.of(outcome));
    }

    private record Lookup(
        Capability capability,
        EntitlementValue baseline,
        TraceSource baselineSource,
        Optional<String> baselinePlanKey,
        List<AccountOverride> overrides) {}
}
