package com.solovis.entitlement.service.api;

/**
 * The response header {@code contracts/README.md} promises on <em>every</em> {@code /v1} response —
 * successes, errors and the replication feed alike:
 *
 * <pre>X-Entitlement-Snapshot-Version: 48211</pre>
 *
 * <p>It is set at each producing site rather than by a filter, deliberately. A filter could only
 * report {@code SnapshotHolder.current()} at the moment it ran, which is a different question from
 * "which version answered this request": a write committing between the two reads would make the
 * header describe a snapshot the body was never resolved against, quietly breaking the one-coherent-
 * moment guarantee (c31) and the {@code minSnapshotVersion} read-your-writes handshake built on it.
 * Each route therefore stamps the version from the very snapshot object it used.
 *
 * <p>Scope is {@code /v1} only. {@code /admin/v1} backs the SPA and may change with it, so the
 * header must not leak there and become an accidental commitment.
 */
public final class SnapshotVersionHeader {

    public static final String NAME = "X-Entitlement-Snapshot-Version";

    private SnapshotVersionHeader() {}
}
