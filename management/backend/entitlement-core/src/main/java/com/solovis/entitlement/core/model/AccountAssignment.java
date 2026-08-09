package com.solovis.entitlement.core.model;

import java.util.Objects;

/** Which plan one account is currently on (spec §3.3) — an account has exactly one, never zero. */
public record AccountAssignment(String accountExternalId, String planKey) {

    public AccountAssignment {
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(planKey, "planKey");
    }
}
