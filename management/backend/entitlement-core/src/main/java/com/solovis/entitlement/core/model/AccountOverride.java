package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An exception attached to one account and one capability (spec §3.4). {@code reason},
 * {@code createdBy} and {@code createdAt} are present when built by the management service and
 * absent on a replica's answer-only projection (research.md §2) — {@link
 * com.solovis.entitlement.core.engine.Resolver#resolve} never reads them; only {@code explain}
 * does.
 *
 * <p>{@code startsOn} and {@code expiresOn} are whole dates in the service zone, both optional
 * (002 spec §3.1), and the expiry date is inclusive. They are absent on a replica's projection for
 * the same reason the trace fields are: windows are evaluated before publication, so a replica only
 * ever receives overrides that are already in force.
 */
public record AccountOverride(
    OptionalLong id,
    String accountExternalId,
    CapabilityKey capabilityKey,
    OverrideKind kind,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt,
    Optional<LocalDate> startsOn,
    Optional<LocalDate> expiresOn
) {

    /**
     * An open-ended override — in force from creation until someone removes it. This was the only
     * shape before 002 and is still the ordinary case, so it keeps the shorter constructor rather
     * than making every caller say {@code Optional.empty()} twice.
     */
    public AccountOverride(
        OptionalLong id,
        String accountExternalId,
        CapabilityKey capabilityKey,
        OverrideKind kind,
        EntitlementValue value,
        Optional<String> reason,
        Optional<String> createdBy,
        Optional<Instant> createdAt
    ) {
        this(id, accountExternalId, capabilityKey, kind, value, reason, createdBy, createdAt,
            Optional.empty(), Optional.empty());
    }

    public AccountOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(expiresOn, "expiresOn");
        if (startsOn.isPresent() && expiresOn.isPresent() && startsOn.get().isAfter(expiresOn.get())) {
            throw new IllegalArgumentException(
                "An override cannot start (" + startsOn.get() + ") after it expires (" + expiresOn.get() + ")");
        }
    }
}
