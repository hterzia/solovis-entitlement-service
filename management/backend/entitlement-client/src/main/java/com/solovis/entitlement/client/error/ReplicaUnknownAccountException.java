package com.solovis.entitlement.client.error;

import com.solovis.entitlement.core.error.UnknownAccountException;
import java.time.Duration;

/**
 * The replica holds no account with this external id, and a bounded read-through to the service
 * did not produce one either.
 *
 * <p>Carries the evidence a caller needs to tell a genuine unknown account from a replica that
 * simply has not caught up with a signup three seconds ago.
 */
public final class ReplicaUnknownAccountException extends UnknownAccountException {

    private final Duration snapshotAge;
    private final boolean readThroughAttempted;

    public ReplicaUnknownAccountException(
            String accountExternalId, Duration snapshotAge, boolean readThroughAttempted) {
        super(accountExternalId);
        this.snapshotAge = java.util.Objects.requireNonNull(snapshotAge);
        this.readThroughAttempted = readThroughAttempted;
    }

    /** How stale the replica was when it failed to find the account. */
    public Duration snapshotAge() {
        return snapshotAge;
    }

    /** False means the service was unreachable, so this may be a race rather than a real 404. */
    public boolean readThroughAttempted() {
        return readThroughAttempted;
    }
}
