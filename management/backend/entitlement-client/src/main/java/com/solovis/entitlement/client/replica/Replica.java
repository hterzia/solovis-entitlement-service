package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.view.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable local replica of the model: the core {@link Snapshot} decisions resolve against,
 * plus the feed metadata a replica has to carry.
 *
 * <p>{@code overridesByRef} exists because {@code override.removed} deltas carry only an opaque
 * {@code ref}, while core's mutator removes by {@code (account, capability, id)}. The index is the
 * bridge. It holds the same {@link AccountOverride} instances the snapshot holds, so it costs a
 * map, not a copy of the data.
 */
public record Replica(
    Snapshot snapshot,
    Map<Long, AccountOverride> overridesByRef,
    Instant publishedAt,
    List<ConformanceVector> vectors,
    int format,
    int resolverContract) {

    public Replica {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(publishedAt, "publishedAt");
        overridesByRef = Map.copyOf(overridesByRef);
        vectors = List.copyOf(vectors);
    }

    public long version() {
        return snapshot.snapshotVersion();
    }
}
