package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.EntitlementValue;
import java.time.Instant;

/**
 * §6.1's answer with no explanation attached — what {@code resolve()} returns and what
 * {@link com.solovis.entitlement.client.EntitlementClient} (a downstream module) ships over the
 * wire. Matches the shape documented in {@code contracts/java-client-sdk.md}.
 */
public record Decision(
    String accountExternalId,
    String capabilityKey,
    boolean allowed,
    EntitlementValue value,
    long snapshotVersion,
    Instant evaluatedAt
) {}
