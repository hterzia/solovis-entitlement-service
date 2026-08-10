package com.solovis.entitlement.client;

import com.solovis.entitlement.client.error.EntitlementClientStartupException;
import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.ConformanceGate;
import com.solovis.entitlement.client.replica.DiskCache;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configures and constructs an {@link EntitlementClient}. Obtained from {@link
 * EntitlementClient#builder()} — not constructed directly, since that factory is what lets the
 * public interface be extended later without a source-incompatible change here.
 *
 * <p>{@link #build()} blocks: it fetches a full snapshot (retrying until {@code startupTimeout}
 * elapses), runs it through {@link ConformanceGate}, and only then starts the background poller.
 * A client is never handed back half-initialized.
 */
public final class EntitlementClientBuilder {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);   // keeps answers inside the 10s reuse bound (c29)
    private static final Duration DEFAULT_STALE_AFTER = Duration.ofSeconds(60);    // matches the §7 promise
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    // Bounds one HTTP call, not the whole startup attempt: startupTimeout above is the retry
    // budget across as many of these individual requests as fit inside it.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(50);

    private String serviceUrl;
    private Duration pollInterval = DEFAULT_POLL_INTERVAL;
    private Duration staleAfter = DEFAULT_STALE_AFTER;
    private Path diskCache;
    private Duration startupTimeout = DEFAULT_STARTUP_TIMEOUT;
    private StartupMode startupMode = StartupMode.REQUIRE_SNAPSHOT;
    private MeterRegistry meterRegistry;
    private HttpClient httpClient;
    private Clock clock = Clock.systemUTC();

    EntitlementClientBuilder() {}

    /** Mandatory. The management service's base URL, e.g. {@code http://entitlements:8081}. */
    public EntitlementClientBuilder serviceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        return this;
    }

    /** How often the background poller checks for a newer snapshot. Default 5 seconds. */
    public EntitlementClientBuilder pollInterval(Duration pollInterval) {
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        return this;
    }

    /** How long without a successful sync before {@link ClientHealth#stale()} reports true. Default 60 seconds. */
    public EntitlementClientBuilder staleAfter(Duration staleAfter) {
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        return this;
    }

    /** A directory the replica is written to after every successful sync, and read from under {@link StartupMode#ALLOW_DISK_CACHE}. None by default. */
    public EntitlementClientBuilder diskCache(Path diskCache) {
        this.diskCache = diskCache;
        return this;
    }

    /** How long {@link #build()} retries fetching the first snapshot before giving up. Default 30 seconds. */
    public EntitlementClientBuilder startupTimeout(Duration startupTimeout) {
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        return this;
    }

    /** What {@link #build()} may do if no snapshot loads within {@code startupTimeout}. Default {@link StartupMode#REQUIRE_SNAPSHOT}. */
    public EntitlementClientBuilder startupMode(StartupMode startupMode) {
        this.startupMode = Objects.requireNonNull(startupMode, "startupMode");
        return this;
    }

    /**
     * Wires a Micrometer registry so the SDK's metrics are recorded. None by default, meaning
     * {@link ClientMetrics#NO_OP}. The registry is stored but not yet used to build a real {@code
     * ClientMetrics} — that wiring is Task 14.
     */
    public EntitlementClientBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * Advanced: inject an {@link HttpClient} the caller already manages, e.g. to share a connection
     * pool with the rest of the process, or to supply a test double. A fresh, SDK-owned one is used
     * by default.
     */
    public EntitlementClientBuilder httpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }

    /** Advanced: inject a {@link Clock}, e.g. a frozen one in tests. {@link Clock#systemUTC()} by default. */
    public EntitlementClientBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Blocks until the client is ready to serve:
     * <ol>
     *   <li>Fetch a full snapshot, retrying until {@code startupTimeout} elapses.
     *   <li>Run it through {@link ConformanceGate}. A mismatch — including an unsupported {@code
     *       format} or {@code resolverContract} — fails construction with {@link
     *       EntitlementClientStartupException} <b>unconditionally</b>, regardless of {@code
     *       startupMode} or whether a disk cache exists. See below for why.
     *   <li>If {@code startupTimeout} elapses with the service simply unreachable (no candidate to
     *       even gate): under {@link StartupMode#ALLOW_DISK_CACHE} with a loadable cache, start
     *       from the cache — immediately {@code stale} — and keep polling; otherwise throw {@link
     *       EntitlementClientStartupException}.
     *   <li>Only then start the background poller.
     * </ol>
     *
     * <p><b>The disk cache never rescues a conformance-gate failure.</b> An unreachable service
     * and a failed gate look similar (both end in {@link EntitlementClientStartupException}) but
     * mean opposite things. Unreachable means this SDK's engine is fine and simply has no fresh
     * data — the cache's last-known-good replica is a correct, if stale, answer, exactly what
     * spec §11 asks for. A failed gate means this SDK computes different answers than the service
     * does for the feed's own worked examples — the engines disagree. Loading the disk cache does
     * not repair that disagreement; the cached replica would be resolved by the same disagreeing
     * engine, so the result would be serving wrong answers from cache with no trace to diagnose
     * them by, instead of refusing to start. That is precisely the failure the gate exists to
     * catch proactively rather than after the fact.
     *
     * <p>A cache-loaded replica is gated too, if it carries conformance vectors — but {@link
     * DiskCache} deliberately does not persist them, so in practice a cached replica starts
     * ungated and the first successful sync gates it for real. That is the right trade for the
     * unreachable-service case: the alternative is refusing to start during an outage, which is
     * the exact failure the cache exists to prevent.
     *
     * @throws IllegalStateException if {@code serviceUrl} was never set
     * @throws EntitlementClientStartupException if no snapshot could be loaded and trusted
     */
    public EntitlementClient build() {
        if (serviceUrl == null) {
            throw new IllegalStateException("serviceUrl is required to build an EntitlementClient");
        }

        // The registry is only stored for now; Task 14 turns it into a real ClientMetrics.
        ClientMetrics metrics = ClientMetrics.NO_OP;

        var baseUri = URI.create(serviceUrl);
        var feed = httpClient != null
            ? new FeedHttpClient(baseUri, httpClient, REQUEST_TIMEOUT)
            : new FeedHttpClient(baseUri, REQUEST_TIMEOUT);
        var cache = diskCache != null ? new DiskCache(diskCache) : null;
        var holder = new AtomicReference<Replica>();

        boolean startingFromCache;
        try {
            holder.set(fetchFullSnapshot(feed));
            startingFromCache = false;
        } catch (EntitlementClientStartupException gateFailure) {
            // The engine itself disagrees with the service (or an unsupported format /
            // resolverContract). The disk cache is resolved by this same disagreeing engine, so
            // it is never a rescue here — see the build() javadoc. Unconditional, regardless of
            // startupMode or whether a usable cache exists.
            feed.close();
            throw gateFailure;
        } catch (SnapshotUnobtainableException transportExhausted) {
            var cached = startupMode == StartupMode.ALLOW_DISK_CACHE && cache != null
                ? cache.load() : Optional.<Replica>empty();
            if (cached.isEmpty()) {
                feed.close();
                throw new EntitlementClientStartupException(
                    "Could not load an entitlement snapshot from " + serviceUrl + " within "
                        + startupTimeout
                        + (cache != null ? " and no usable disk cache was found." : "."),
                    transportExhausted.getCause());
            }
            holder.set(cached.get());
            startingFromCache = true;
        }

        var poller = new SnapshotPoller(feed, holder, pollInterval, staleAfter, cache, metrics, clock);
        if (startingFromCache) {
            poller.markStaleAtStartup();
        }
        var client = new DefaultEntitlementClient(holder, poller, feed, clock, metrics);
        poller.start();
        return client;
    }

    /**
     * Retries {@code GET /v1/snapshot/full} until {@code startupTimeout} elapses, gating each
     * candidate as it arrives. A gate failure (or an unsupported format/resolverContract) throws
     * {@link EntitlementClientStartupException} immediately — not retried, since a persistently
     * untrustworthy snapshot is not a transient problem retrying fixes. Only a transport failure
     * that never produces a gate-worthy candidate before the deadline throws {@link
     * SnapshotUnobtainableException}, the signal {@link #build()} uses to decide whether the disk
     * cache applies.
     */
    private Replica fetchFullSnapshot(FeedHttpClient feed) {
        var deadline = System.nanoTime() + startupTimeout.toNanos();
        RuntimeException lastFailure = null;
        while (true) {
            try {
                var candidate = feed.full();
                var gate = ConformanceGate.evaluate(candidate);
                if (!gate.passed()) {
                    throw new EntitlementClientStartupException(
                        "Entitlement replica failed the conformance gate at startup: " + gate.reason());
                }
                return candidate;
            } catch (EntitlementClientStartupException gateFailure) {
                throw gateFailure;
            } catch (RuntimeException transportFailure) {
                lastFailure = transportFailure;
            }
            if (System.nanoTime() >= deadline) {
                throw new SnapshotUnobtainableException(lastFailure);
            }
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(RETRY_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Internal marker distinguishing "the service could not be reached in time" from a
     * conformance-gate failure — {@link EntitlementClientStartupException} covers both from the
     * outside, but only this one is eligible for the {@link StartupMode#ALLOW_DISK_CACHE}
     * fallback in {@link #build()}.
     */
    private static final class SnapshotUnobtainableException extends RuntimeException {
        SnapshotUnobtainableException(Throwable cause) {
            super(cause);
        }
    }
}
