package com.solovis.entitlement.client.transport;

/** The delta path is unusable from this version; the caller must fetch a full snapshot. */
public final class SnapshotTooOldException extends RuntimeException {
    public SnapshotTooOldException(String message) {
        super(message);
    }
}
