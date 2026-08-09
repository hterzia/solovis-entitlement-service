package com.solovis.entitlement.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An exception attached to one account and one capability (spec §3.4). {@code reason},
 * {@code createdBy} and {@code createdAt} are present when built by the management service and
 * absent on a replica's answer-only projection (research.md §2) — {@link
 * com.solovis.entitlement.core.engine.Resolver#resolve} never reads them; only {@code explain}
 * does.
 */
public record AccountOverride(
    OptionalLong id,
    String accountExternalId,
    CapabilityKey capabilityKey,
    OverrideKind kind,
    EntitlementValue value,
    Optional<String> reason,
    Optional<String> createdBy,
    Optional<Instant> createdAt
) {

    public AccountOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountExternalId, "accountExternalId");
        Objects.requireNonNull(capabilityKey, "capabilityKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
