package com.solovis.entitlement.core.model;

import java.util.Objects;

/** A named set of capability values forming the baseline for every account on it (spec §3.2). */
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
