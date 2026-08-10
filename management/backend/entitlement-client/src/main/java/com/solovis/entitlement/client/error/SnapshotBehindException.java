package com.solovis.entitlement.client.error;

/** A {@code minSnapshotVersion} was supplied that this replica has not reached (read-your-writes). */
public final class SnapshotBehindException extends RuntimeException {

    private final long requiredVersion;
    private final long currentVersion;

    public SnapshotBehindException(long requiredVersion, long currentVersion) {
        super("Replica is at snapshot version " + currentVersion + " but version " + requiredVersion
            + " was required. Retry, await the version, or accept the older answer.");
        this.requiredVersion = requiredVersion;
        this.currentVersion = currentVersion;
    }

    public long requiredVersion() {
        return requiredVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
