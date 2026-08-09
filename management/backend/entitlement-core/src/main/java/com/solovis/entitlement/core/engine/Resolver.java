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
import com.solovis.entitlement.core.model.OverrideStanding;
import com.solovis.entitlement.core.model.StandingOverride;
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

        // Every override that existed at this moment, in force or not (c19). Only the in-force ones
        // enter the arithmetic below — which is byte-for-byte what resolve() does — so the value can
        // never disagree with resolve()'s. The rest become trace entries and nothing more.
        var known = view.knownOverrides(accountExternalId, capabilityKey);
        var inForce = new ArrayList<AccountOverride>();
        var notInForce = new ArrayList<StandingOverride>();
        for (var standingOverride : known) {
            if (standingOverride.counts()) {
                inForce.add(standingOverride.override());
            } else {
                notInForce.add(standingOverride);
            }
        }
        var lookup = lookUp(view, accountExternalId, capabilityKey, inForce);

        var baselineEntry = new TraceEntry(
            lookup.baselineSource, OptionalLong.empty(), lookup.baselinePlanKey,
            lookup.baseline, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        // Grants: the top candidate is WON only if it actually beats the plan (c11) — otherwise
        // it is LOST_NOT_MORE_GENEROUS_THAN_PLAN, a reason distinct from losing to another grant.
        var grantCandidates = candidatesOfKind(lookup.overrides, OverrideKind.GRANT);
        var topGrant = pickWinner(grantCandidates, true);
        boolean grantApplied = topGrant.isPresent() && Generosity.compare(topGrant.get().value(), lookup.baseline) > 0;
        EntitlementValue afterGrants = grantApplied ? topGrant.get().value() : lookup.baseline;
        var grantResult = groupTraceEntries(grantCandidates, topGrant, grantApplied, TraceSource.GRANT,
            (isTop, applied) -> isTop ? (applied ? Outcome.WON : Outcome.LOST_NOT_MORE_GENEROUS_THAN_PLAN)
                                       : Outcome.LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT);

        // Holds: the most restrictive candidate is always marked WON in its own list, even when
        // it does not change the result — holdWinner (below) being empty is what records that it
        // did not apply (decision-api.md, "Ties are deterministic").
        var holdCandidates = candidatesOfKind(lookup.overrides, OverrideKind.HOLD);
        var topHold = pickWinner(holdCandidates, false);
        boolean holdApplied = topHold.isPresent() && Generosity.compare(topHold.get().value(), afterGrants) < 0;
        EntitlementValue result = holdApplied ? topHold.get().value() : afterGrants;
        var holdResult = groupTraceEntries(holdCandidates, topHold, holdApplied, TraceSource.HOLD,
            (isTop, applied) -> isTop ? Outcome.WON : Outcome.LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD);

        boolean allowed = computeAllowed(lookup.capability, result);
        var trace = new Trace(baselineEntry,
            withNotInForce(grantResult.entries(), notInForce, OverrideKind.GRANT, TraceSource.GRANT),
            grantResult.winnerEntry(),
            withNotInForce(holdResult.entries(), notInForce, OverrideKind.HOLD, TraceSource.HOLD),
            holdResult.winnerEntry(), result, allowed);
        var decision = new Decision(accountExternalId, capabilityKey.value(), allowed, result, view.snapshotVersion(), evaluatedAt);
        return new Explanation(decision, trace);
    }

    private static boolean computeAllowed(Capability capability, EntitlementValue result) {
        return capability.effectiveOffValue().map(off -> !off.equals(result)).orElse(true);
    }

    /**
     * Appends the overrides of this kind that took no part, after the ones that did, so the chain
     * reads as "what counted, then what did not and why" (c19, c20). Winner selection has already
     * happened over the in-force candidates alone, so nothing here can move a value.
     */
    private static List<TraceEntry> withNotInForce(List<TraceEntry> counted,
        List<StandingOverride> notInForce, OverrideKind kind, TraceSource source) {

        if (notInForce.isEmpty()) {
            return counted;
        }
        var entries = new ArrayList<>(counted);
        for (var standingOverride : notInForce) {
            var override = standingOverride.override();
            if (override.kind() != kind) {
                continue;
            }
            entries.add(new TraceEntry(source, override.id(), Optional.empty(), override.value(),
                override.reason(), override.createdBy(), override.createdAt(),
                Optional.of(outcomeOf(standingOverride.standing())),
                override.startsOn(), override.expiresOn(), standingOverride.notInForceSince()));
        }
        return List.copyOf(entries);
    }

    private static Outcome outcomeOf(OverrideStanding standing) {
        return switch (standing) {
            case PENDING -> Outcome.NOT_IN_FORCE_PENDING;
            case ENDED -> Outcome.NOT_IN_FORCE_ENDED;
            case REMOVED -> Outcome.NOT_IN_FORCE_REMOVED;
            case IN_FORCE -> throw new IllegalStateException(
                "An in-force override belongs in the arithmetic, not in the not-in-force list.");
        };
    }

    private static Lookup lookUp(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey) {
        return lookUp(view, accountExternalId, capabilityKey,
            view.liveOverrides(accountExternalId, capabilityKey));
    }

    private static Lookup lookUp(EntitlementView view, String accountExternalId, CapabilityKey capabilityKey,
        List<AccountOverride> overrides) {
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
     * Builds one group's (grants' or holds') trace entries and identifies the winner's entry —
     * in a single pass, using reference equality against {@code top} (not id equality, which
     * breaks when multiple candidates carry an absent id — the model permits this for a
     * replica's answer-only projection). {@code outcomeOf} receives (isTop, applied) and returns
     * the Outcome for that candidate; grants and holds compute this differently (see call sites).
     */
    private static GroupResult groupTraceEntries(
        List<AccountOverride> candidates, Optional<AccountOverride> top, boolean applied,
        TraceSource source, java.util.function.BiFunction<Boolean, Boolean, Outcome> outcomeOf) {
        var entries = new ArrayList<TraceEntry>();
        TraceEntry winnerEntry = null;
        for (var candidate : candidates) {
            boolean isTop = top.isPresent() && top.get() == candidate;
            var entry = toTraceEntry(candidate, source, outcomeOf.apply(isTop, applied));
            entries.add(entry);
            if (isTop) {
                winnerEntry = entry;
            }
        }
        return new GroupResult(entries, applied ? Optional.ofNullable(winnerEntry) : Optional.empty());
    }

    private static TraceEntry toTraceEntry(AccountOverride candidate, TraceSource source, Outcome outcome) {
        // An in-force override may still carry a window — "200 reports, until 31 December" is worth
        // reading in the chain, not only once it has lapsed.
        return new TraceEntry(
            source, candidate.id(), Optional.empty(), candidate.value(),
            candidate.reason(), candidate.createdBy(), candidate.createdAt(), Optional.of(outcome),
            candidate.startsOn(), candidate.expiresOn(), Optional.empty());
    }

    private record Lookup(
        Capability capability,
        EntitlementValue baseline,
        TraceSource baselineSource,
        Optional<String> baselinePlanKey,
        List<AccountOverride> overrides) {}

    private record GroupResult(List<TraceEntry> entries, Optional<TraceEntry> winnerEntry) {}
}
