package com.solovis.entitlement.client.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Micrometer-backed {@link ClientMetrics}. This is the only file in the module that
 * references Micrometer, apart from the type of {@code EntitlementClientBuilder#meterRegistry}'s
 * setter — that is what keeps {@code micrometer-core} a genuinely optional dependency: a product
 * that never calls {@code meterRegistry(...)} never loads this class.
 *
 * <p>Gauges are registered exactly once, in the constructor, backed by {@link AtomicLong} fields.
 * Micrometer gauges hold a weak reference to the state object they sample; a locally-scoped
 * holder would be collected and the gauge would silently start reporting {@code NaN}. The age
 * gauge is the one exception — it has no value until the first replica loads — so it is
 * registered lazily on the first {@link #snapshotAge(Supplier)} call, guarded so a second call
 * cannot replace the live gauge with another one.
 */
public final class MicrometerClientMetrics implements ClientMetrics {

    private final MeterRegistry registry;

    private final AtomicLong snapshotVersion = new AtomicLong();
    private final AtomicLong resolverContract = new AtomicLong();
    private final AtomicReference<Supplier<Duration>> ageSupplier = new AtomicReference<>();
    private final AtomicBoolean ageGaugeRegistered = new AtomicBoolean(false);

    private final Counter syncFailures;
    private final Counter fullResync;
    private final Counter conformanceFailures;
    private final Counter readThrough;

    private final ConcurrentHashMap<DecisionKey, Counter> decisionCounters = new ConcurrentHashMap<>();

    public MicrometerClientMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("entitlement.client.snapshot.version", snapshotVersion, AtomicLong::get)
            .description("Snapshot version currently served by this replica")
            .register(registry);
        Gauge.builder("entitlement.client.resolver.contract", resolverContract, AtomicLong::get)
            .description("Resolver contract version this replica evaluated its conformance vectors against")
            .register(registry);

        this.syncFailures = Counter.builder("entitlement.client.sync.failures")
            .description("Failed attempts to reach the management service")
            .register(registry);
        this.fullResync = Counter.builder("entitlement.client.resync.full")
            .description("Times this replica fell behind the delta horizon and re-fetched a full snapshot")
            .register(registry);
        this.conformanceFailures = Counter.builder("entitlement.client.conformance.failures")
            .description("Times the conformance gate refused a replica")
            .register(registry);
        this.readThrough = Counter.builder("entitlement.client.readthrough")
            .description("Decisions for an account unknown to this replica")
            .register(registry);
    }

    @Override
    public void snapshotVersion(long version) {
        snapshotVersion.set(version);
    }

    @Override
    public void snapshotAge(Supplier<Duration> age) {
        ageSupplier.compareAndSet(null, age);
        if (ageGaugeRegistered.compareAndSet(false, true)) {
            Gauge.builder("entitlement.client.snapshot.age", ageSupplier, MicrometerClientMetrics::currentAgeSeconds)
                .description("Age of the replica currently served, in seconds")
                .register(registry);
        }
    }

    private static double currentAgeSeconds(AtomicReference<Supplier<Duration>> ref) {
        var supplier = ref.get();
        if (supplier == null) {
            return 0d;
        }
        return supplier.get().toNanos() / 1_000_000_000d;
    }

    @Override
    public void syncFailed() {
        syncFailures.increment();
    }

    @Override
    public void fullResync() {
        fullResync.increment();
    }

    @Override
    public void conformanceFailed() {
        conformanceFailures.increment();
    }

    @Override
    public void resolverContract(int contract) {
        resolverContract.set(contract);
    }

    @Override
    public void decision(String capabilityKey, boolean allowed) {
        decisionCounters.computeIfAbsent(new DecisionKey(capabilityKey, allowed), key ->
                Counter.builder("entitlement.client.decisions")
                    .tag("capability", key.capabilityKey())
                    .tag("allowed", String.valueOf(key.allowed()))
                    .register(registry))
            .increment();
    }

    @Override
    public void readThrough() {
        readThrough.increment();
    }

    private record DecisionKey(String capabilityKey, boolean allowed) {}
}
