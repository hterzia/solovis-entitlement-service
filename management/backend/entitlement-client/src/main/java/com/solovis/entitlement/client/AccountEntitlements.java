package com.solovis.entitlement.client;

import com.solovis.entitlement.core.engine.Decision;
import java.time.Instant;
import java.util.List;

/**
 * Every non-retired capability for one account, resolved against a single snapshot version and a
 * single moment (c31). Call this rather than {@code check} in a loop when several capabilities have
 * to agree with each other.
 */
public record AccountEntitlements(
    String account, String planKey, List<Decision> decisions, long snapshotVersion, Instant evaluatedAt) {

    public AccountEntitlements {
        decisions = List.copyOf(decisions);
    }
}
