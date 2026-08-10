package com.solovis.entitlement.core.model;

import java.util.Objects;

/**
 * A named set of capability values forming the baseline for every account on it (spec §3.2).
 *
 * <p>There is deliberately no parent field here or anywhere downstream of it: plans are flat, and
 * "enterprise includes everything in pro" is not expressible <em>(c5)</em>. §3.2 gives the reason —
 * inheritance makes explanations recursive, and clarity is the point of this service. The absence is
 * the whole implementation of that criterion, so it is recorded here rather than tested for.
 */
public record Plan(String key, String name, Status status, boolean defaultForNewAccounts) {

    public enum Status { ACTIVE, ARCHIVED }

    public Plan {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        if (status == Status.ARCHIVED && defaultForNewAccounts) {
            throw new IllegalArgumentException("An archived plan cannot be the default for new accounts (c7).");
        }
    }
}
