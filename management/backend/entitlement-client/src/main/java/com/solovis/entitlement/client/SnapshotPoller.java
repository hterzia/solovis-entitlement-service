package com.solovis.entitlement.client;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.ConformanceGate;
import com.solovis.entitlement.client.replica.DeltaApplier;
import com.solovis.entitlement.client.replica.DiskCache;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.transport.Backoff;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.client.transport.FeedUnavailableException;
import com.solovis.entitlement.client.transport.SnapshotTooOldException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The daemon that keeps one replica current: poll the version, advance by delta, full-resync when
 * the delta path is unusable, gate the candidate, swap.
 *
 * <p>Nothing here ever makes the decision path fail. A sync that cannot complete leaves the last
 * good replica serving, which is the entire §11 posture: not fail-open, not fail-closed, but the
 * last state it knew.
 */
final class SnapshotPoller implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SnapshotPoller.class.getName());

    private final FeedHttpClient feed;
    private final AtomicReference<Replica> holder;
    private final Duration pollInterval;
    private final Duration staleAfter;
    private final DiskCache cache;              // nullable
    private final ClientMetrics metrics;
    private final Clock clock;
    private final Backoff backoff;

    private final AtomicReference<SyncState> state;
    private volatile boolean stopped;           // a contract violation was seen; stop syncing
    private volatile boolean closed;
    private volatile Thread thread;
    private volatile boolean warnedStale;
    private volatile boolean ungated;           // seeded replica has never passed the gate; see markUngatedAtStartup()

    record SyncState(Instant lastSuccessfulSync, String lastError, boolean stale) {}

    SnapshotPoller(FeedHttpClient feed, AtomicReference<Replica> holder, Duration pollInterval,
            Duration staleAfter, DiskCache cache, ClientMetrics metrics, Clock clock) {
        this.feed = feed;
        this.holder = holder;
        this.pollInterval = pollInterval;
        this.staleAfter = staleAfter;
        this.cache = cache;
        this.metrics = metrics;
        this.clock = clock;
        this.backoff = new Backoff(pollInterval);
        this.state = new AtomicReference<>(new SyncState(clock.instant(), null, false));
    }

    SyncState state() {
        return state.get();
    }

    /** True once a format or resolverContract mismatch has permanently halted replication. */
    boolean stopped() {
        return stopped;
    }

    Duration pollInterval() {
        return pollInterval;
    }

    Duration staleAfter() {
        return staleAfter;
    }

    /**
     * Called once, before {@link #start()}, when the replica this poller was constructed with came
     * from disk rather than a successful sync (an {@code ALLOW_DISK_CACHE} startup during an
     * outage). {@code health()} must report stale immediately rather than waiting out {@code
     * staleAfter} from a fabricated "just synced" timestamp — the whole point of surfacing
     * staleness is so a caller can tell a cache-loaded replica from a freshly synced one.
     */
    void markStaleAtStartup() {
        state.set(new SyncState(clock.instant(), null, true));
    }

    /**
     * Called once, before {@link #start()}, alongside {@link #markStaleAtStartup()}, when the
     * replica this poller was constructed with came from disk rather than a gated sync. {@link
     * DiskCache} deliberately does not persist conformance vectors, so a cache-loaded replica has
     * never actually been checked against this SDK's engine.
     *
     * <p>While this flag is set, {@link #syncOnce()} bypasses its equality fast path and forces a
     * full fetch through {@link ConformanceGate} even when the service already reports the cached
     * version — otherwise a same-version poll would call {@code succeed()} and report "current and
     * verified" for a replica whose conformance was never actually run. The flag clears the moment
     * a gated snapshot is swapped in, so ordinary polls do not pay for a full fetch forever.
     */
    void markUngatedAtStartup() {
        ungated = true;
    }

    void start() {
        var t = new Thread(this::loop, "entitlement-snapshot-poller");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Wakes a sleeping poller so a pending sync runs now instead of waiting out {@code
     * pollInterval} — the out-of-band half of the read-through: a caller just proved to the
     * service that an account exists, so there is no reason for this replica to wait its usual
     * interval to find out. A no-op if replication has permanently halted ({@link #stopped}) or
     * the poller was never started; {@code DefaultEntitlementClient} additionally guards the case
     * where no poller exists at all (a client built via {@code forTesting}).
     */
    void nudge() {
        if (stopped) {
            return;
        }
        var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop() {
        while (!closed) {
            Duration wait;
            try {
                wait = syncOnce() ? pollInterval : backoff.nextDelay();
            } catch (RuntimeException e) {
                // Belt and braces. A poller thread that dies would age the replica forever while
                // health() still looked plausible.
                LOG.log(Level.SEVERE, "Unexpected failure in the entitlement snapshot poller.", e);
                wait = backoff.nextDelay();
            }
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                if (closed) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // Not a close: this was nudge() cutting the sleep short. closed is volatile and
                // close() sets it before interrupting, so seeing it false here means the interrupt
                // really was a nudge — loop back around and sync immediately instead of exiting.
            }
        }
    }

    /** One sync attempt. Returns true when the replica is current. Never throws. */
    boolean syncOnce() {
        if (stopped) {
            return false;
        }
        try {
            var version = feed.version();
            if (version.format() != ConformanceGate.SUPPORTED_FORMAT
                || version.resolverContract() != com.solovis.entitlement.core.conformance.ResolverContract.VERSION) {
                halt("Feed advertises format " + version.format() + " / resolverContract "
                    + version.resolverContract() + "; this SDK implements format "
                    + ConformanceGate.SUPPORTED_FORMAT + " / resolverContract "
                    + com.solovis.entitlement.core.conformance.ResolverContract.VERSION
                    + ". Replication has stopped; the last good replica keeps serving.");
                return false;
            }
            var current = holder.get();
            if (!ungated && version.version() == current.version()) {
                return succeed();
            }
            Replica candidate;
            if (ungated) {
                // The seeded replica has never passed the gate (a cache load — DiskCache does not
                // persist conformance vectors). A delta would not give the gate anything complete
                // to evaluate, and would also skip evaluation entirely at a matching version — so
                // go straight to a full snapshot regardless of what the version poll reported.
                candidate = feed.full();
            } else {
                try {
                    // Scoped to the delta path only: the version endpoint is deliberately trivial
                    // and never reports snapshot-too-old (spec: snapshot-feed.md) —
                    // SnapshotTooOldException can only come from feed.delta() below, and
                    // OutOfOrderDeltaException / UnknownChangeKindException only from
                    // DeltaApplier.apply's inspection of the batch it returns, so both are covered
                    // by wrapping this one statement.
                    candidate = DeltaApplier.apply(current, feed.delta(current.version()));
                } catch (SnapshotTooOldException | DeltaApplier.OutOfOrderDeltaException e) {
                    metrics.fullResync();
                    candidate = feed.full();
                } catch (DeltaApplier.UnknownChangeKindException e) {
                    halt("Feed delivered an unknown change kind '" + e.kind()
                        + "'. Replication has stopped; the last good replica keeps serving.");
                    return false;
                }
            }
            var gate = ConformanceGate.evaluate(candidate);
            if (!gate.passed()) {
                metrics.conformanceFailed();
                LOG.severe("Discarding a snapshot that failed the conformance gate; keeping version "
                    + current.version() + ". " + gate.reason());
                // ungated stays set: the replica this poller serves is still unverified, so the
                // next sync must force another full fetch rather than resuming the fast path.
                return fail(gate.reason());
            }
            holder.set(candidate);
            if (cache != null) {
                cache.store(candidate);
            }
            metrics.snapshotVersion(candidate.version());
            metrics.resolverContract(candidate.resolverContract());
            ungated = false;   // a gated snapshot is now serving; the fast path may resume
            return succeed();
        } catch (FeedUnavailableException e) {
            return fail(e.getMessage());
        } catch (RuntimeException e) {
            return fail(e.toString());
        }
    }

    private void halt(String reason) {
        stopped = true;
        LOG.severe(reason);
        fail(reason);
    }

    private boolean succeed() {
        backoff.reset();
        warnedStale = false;
        state.set(new SyncState(clock.instant(), null, false));
        return true;
    }

    private boolean fail(String error) {
        metrics.syncFailed();
        var previous = state.get();
        var since = Duration.between(previous.lastSuccessfulSync(), clock.instant());
        boolean stale = since.compareTo(staleAfter) > 0;
        if (stale && !warnedStale) {
            warnedStale = true;   // once per transition, not once per call
            LOG.warning("Entitlement replica has not synced for " + since
                + " and is now stale. It keeps answering from the last state it knew.");
        }
        state.set(new SyncState(previous.lastSuccessfulSync(), error, stale));
        return false;
    }

    @Override
    public void close() {
        closed = true;
        var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
