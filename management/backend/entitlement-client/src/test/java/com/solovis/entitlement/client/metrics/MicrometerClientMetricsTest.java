package com.solovis.entitlement.client.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link MicrometerClientMetrics}: the eight meters exist under their exact contractual
 * names, the decision counter is tagged and cached per (capability, allowed) pair, and the age
 * gauge reads live from its supplier without being re-registered on a second call.
 */
class MicrometerClientMetricsTest {

    @Test
    void snapshotVersionRegistersAGaugeUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.snapshotVersion(48211L);

        assertThat(registry.get("entitlement.client.snapshot.version").gauge().value()).isEqualTo(48211.0);
    }

    @Test
    void snapshotVersionCalledTwiceLeavesOneGaugeReadingTheSecondValue() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.snapshotVersion(48211L);
        metrics.snapshotVersion(48212L);

        assertThat(registry.find("entitlement.client.snapshot.version").gauges()).hasSize(1);
        assertThat(registry.get("entitlement.client.snapshot.version").gauge().value()).isEqualTo(48212.0);
    }

    @Test
    void resolverContractRegistersAGaugeUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.resolverContract(1);

        assertThat(registry.get("entitlement.client.resolver.contract").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void syncFailedIncrementsACounterUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.syncFailed();
        metrics.syncFailed();

        assertThat(registry.get("entitlement.client.sync.failures").counter().count()).isEqualTo(2.0);
    }

    @Test
    void fullResyncIncrementsACounterUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.fullResync();

        assertThat(registry.get("entitlement.client.resync.full").counter().count()).isEqualTo(1.0);
    }

    @Test
    void conformanceFailedIncrementsACounterUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.conformanceFailed();

        assertThat(registry.get("entitlement.client.conformance.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void readThroughIncrementsACounterUnderTheExactContractualName() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.readThrough();

        assertThat(registry.get("entitlement.client.readthrough").counter().count()).isEqualTo(1.0);
    }

    @Test
    void decisionIncrementsACounterUnderTheExactContractualNameTaggedWithCapabilityAndAllowed() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.decision("api.access", true);

        assertThat(registry.get("entitlement.client.decisions")
                .tag("capability", "api.access")
                .tag("allowed", "true")
                .counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void decisionForTwoDifferentCapabilitiesProducesTwoDistinctCounters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.decision("api.access", true);
        metrics.decision("reports.export", false);
        metrics.decision("api.access", true);

        assertThat(registry.get("entitlement.client.decisions")
                .tag("capability", "api.access")
                .tag("allowed", "true")
                .counter().count())
            .isEqualTo(2.0);
        assertThat(registry.get("entitlement.client.decisions")
                .tag("capability", "reports.export")
                .tag("allowed", "false")
                .counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("entitlement.client.decisions").counters()).hasSize(2);
    }

    @Test
    void snapshotAgeRegistersAGaugeUnderTheExactContractualNameReportingSeconds() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);

        metrics.snapshotAge(() -> Duration.ofSeconds(42));

        assertThat(registry.get("entitlement.client.snapshot.age").gauge().value()).isEqualTo(42.0);
    }

    @Test
    void snapshotAgeReadsLiveFromTheSupplierOnEachSample() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);
        var current = new AtomicReference<>(Duration.ofSeconds(5));

        metrics.snapshotAge(current::get);
        assertThat(registry.get("entitlement.client.snapshot.age").gauge().value()).isEqualTo(5.0);

        current.set(Duration.ofSeconds(90));
        assertThat(registry.get("entitlement.client.snapshot.age").gauge().value()).isEqualTo(90.0);
    }

    @Test
    void snapshotAgeCalledASecondTimeDoesNotRegisterASecondGaugeAndKeepsReadingTheFirstSupplier() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerClientMetrics(registry);
        var first = new AtomicReference<>(Duration.ofSeconds(10));

        metrics.snapshotAge(first::get);
        metrics.snapshotAge(() -> Duration.ofSeconds(999));

        assertThat(registry.find("entitlement.client.snapshot.age").gauges()).hasSize(1);
        assertThat(registry.get("entitlement.client.snapshot.age").gauge().value())
            .as("the second registration call must be ignored, not replace the live gauge")
            .isEqualTo(10.0);
    }

    @Test
    void noOpRecordsNothingAndThrowsNothingForEveryMethod() {
        assertThatCode(() -> {
            ClientMetrics.NO_OP.snapshotVersion(1L);
            ClientMetrics.NO_OP.snapshotAge(() -> Duration.ofSeconds(1));
            ClientMetrics.NO_OP.syncFailed();
            ClientMetrics.NO_OP.fullResync();
            ClientMetrics.NO_OP.conformanceFailed();
            ClientMetrics.NO_OP.resolverContract(1);
            ClientMetrics.NO_OP.decision("api.access", true);
            ClientMetrics.NO_OP.readThrough();
        }).doesNotThrowAnyException();
    }
}
