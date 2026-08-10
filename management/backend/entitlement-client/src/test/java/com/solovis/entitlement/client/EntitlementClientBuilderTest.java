package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.error.EntitlementClientStartupException;
import com.solovis.entitlement.client.replica.DiskCache;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.testing.StubFeedServer;
import com.solovis.entitlement.core.conformance.ResolverContract;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link EntitlementClientBuilder#build()}: the startup gate (fetch, conformance, disk
 * cache fallback), validation, and the documented defaults.
 */
class EntitlementClientBuilderTest {

    /** A conformance vector this engine agrees with, matching {@link #FEED}'s own fixture. Every
     * full snapshot must carry at least one vector or the gate refuses it outright (Finding 3). */
    private static final String GOOD_VECTOR =
        "{\"kind\":\"conformance\",\"id\":\"api.access: plan grants it\","
            + "\"model\":{\"account\":\"acct_9931\",\"capability\":\"api.access\","
            + "\"capabilities\":[{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\","
            + "\"valueType\":\"SWITCH\",\"default\":{\"type\":\"SWITCH\",\"enabled\":false},"
            + "\"status\":\"ACTIVE\"}],"
            + "\"plans\":[{\"kind\":\"plan\",\"key\":\"pro\",\"status\":\"ACTIVE\","
            + "\"isDefaultForNewAccounts\":true,\"entitlements\":{\"api.access\":{\"type\":\"SWITCH\",\"enabled\":true}}}],"
            + "\"accounts\":[{\"kind\":\"account\",\"external\":\"acct_9931\",\"planKey\":\"pro\"}],"
            + "\"overrides\":[]},"
            + "\"expect\":{\"allowed\":true,\"value\":{\"type\":\"SWITCH\",\"enabled\":true}}}";

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":1,"plans":1,"accounts":1,"overrides":0}}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        GOOD_VECTOR,
        """
        {"kind":"footer","version":48211,"recordCount":6}""");

    /**
     * Same fixture as {@code SnapshotPollerTest}'s bad-vector: a conformance line whose expectation
     * the real resolver cannot satisfy, so the startup gate must reject it.
     */
    private static final String BAD_VECTOR =
        "{\"kind\":\"conformance\",\"id\":\"a vector this engine disagrees with\","
            + "\"model\":{\"account\":\"acct_c1\",\"capability\":\"api.access\","
            + "\"capabilities\":[{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\","
            + "\"valueType\":\"SWITCH\",\"default\":{\"type\":\"SWITCH\",\"enabled\":false},"
            + "\"status\":\"ACTIVE\"}],"
            + "\"plans\":[{\"kind\":\"plan\",\"key\":\"p\",\"status\":\"ACTIVE\","
            + "\"isDefaultForNewAccounts\":true,\"entitlements\":{}}],"
            + "\"accounts\":[{\"kind\":\"account\",\"external\":\"acct_c1\",\"planKey\":\"p\"}],"
            + "\"overrides\":[]},"
            + "\"expect\":{\"allowed\":true,\"value\":{\"type\":\"SWITCH\",\"enabled\":true}}}";

    /** {@link #FEED} with one extra line spliced in before the footer, and the record count bumped. */
    private static String feedWithExtraLine(String extraLine) {
        return FEED.replace(
            "{\"kind\":\"footer\",\"version\":48211,\"recordCount\":6}",
            extraLine + "\n{\"kind\":\"footer\",\"version\":48211,\"recordCount\":7}");
    }

    /** A URI nothing is listening on: opened and immediately closed to obtain a free, refusing port. */
    private static URI deadEnd() throws Exception {
        try (var stub = new StubFeedServer()) {
            return stub.baseUri();
        }
    }

    @Test
    void buildReturnsAClientAlreadyServingHavingFetchedAFullSnapshot() throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(FEED);
            stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);

            try (var client = EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .build()) {
                assertThat(stub.fullCalls()).isGreaterThanOrEqualTo(1);
                assertThat(client.health().snapshotVersion()).isEqualTo(48211L);
                assertThat(client.check("acct_9931", "api.access").allowed()).isTrue();
            }
        }
    }

    @Test
    void buildWithoutAServiceUrlThrowsIllegalStateExceptionNamingTheMissingSetting() {
        assertThatThrownBy(() -> EntitlementClient.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("serviceUrl");
    }

    @Test
    void buildAgainstAnUnreachableServiceUnderRequireSnapshotThrowsOnceStartupTimeoutElapses()
            throws Exception {
        var unreachable = deadEnd();

        assertThatThrownBy(() -> EntitlementClient.builder()
                .serviceUrl(unreachable.toString())
                .startupTimeout(Duration.ofMillis(300))
                .build())
            .isInstanceOf(EntitlementClientStartupException.class);
    }

    @Test
    void buildAgainstAnUnreachableServiceUnderAllowDiskCacheWithAPopulatedCacheSucceedsAndStaysStaleAcrossAFailedSync(
            @TempDir Path cacheDir) throws Exception {
        new DiskCache(cacheDir).store(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        var unreachable = deadEnd();

        try (var client = EntitlementClient.builder()
                .serviceUrl(unreachable.toString())
                .startupMode(StartupMode.ALLOW_DISK_CACHE)
                .diskCache(cacheDir)
                .startupTimeout(Duration.ofMillis(300))
                .build()) {
            assertThat(client.health().snapshotVersion()).isEqualTo(48211L);
            assertThat(client.health().stale())
                .as("a cache-loaded replica has not synced with the service yet")
                .isTrue();

            // The poller's first sync attempt fires immediately on start() and fails fast against
            // an unreachable service. This is the assertion that actually proves the fix, not the
            // one above: the one above can pass on a race alone (checked before the background
            // thread's first sync completes). Before the fix, that failed sync recomputed
            // staleness from the fabricated "just seeded" timestamp and reported fresh again
            // within milliseconds.
            Thread.sleep(200);
            assertThat(client.health().stale())
                .as("staleness must survive a failed sync, not just the initial build() race")
                .isTrue();
        }
    }

    @Test
    void buildAgainstAnUnreachableServiceUnderAllowDiskCacheWithAnEmptyCacheStillThrows(
            @TempDir Path cacheDir) throws Exception {
        var unreachable = deadEnd();

        assertThatThrownBy(() -> EntitlementClient.builder()
                .serviceUrl(unreachable.toString())
                .startupMode(StartupMode.ALLOW_DISK_CACHE)
                .diskCache(cacheDir)
                .startupTimeout(Duration.ofMillis(300))
                .build())
            .isInstanceOf(EntitlementClientStartupException.class);
    }

    @Test
    void buildOnASnapshotWhoseVectorsFailTheGateThrowsMentioningConformance() throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(feedWithExtraLine(BAD_VECTOR));

            assertThatThrownBy(() -> EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .startupTimeout(Duration.ofMillis(300))
                    .build())
                .isInstanceOf(EntitlementClientStartupException.class)
                .hasMessageContaining("conformance");
        }
    }

    /**
     * The combination the disk-cache fallback must never paper over: the service is reachable and
     * answers, but this SDK's engine disagrees with what the feed's own worked examples expect. A
     * populated, otherwise-usable cache sits right there — proving it is never consulted for this
     * failure is the point, since the cached replica would be resolved by that same disagreeing
     * engine and would silently serve wrong entitlements instead of refusing to start.
     */
    @Test
    void buildOnASnapshotWhoseVectorsFailTheGateThrowsEvenUnderAllowDiskCacheWithAPopulatedCache(
            @TempDir Path cacheDir) throws Exception {
        new DiskCache(cacheDir).store(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));

        try (var stub = new StubFeedServer()) {
            stub.respondFull(feedWithExtraLine(BAD_VECTOR));

            assertThatThrownBy(() -> EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .startupMode(StartupMode.ALLOW_DISK_CACHE)
                    .diskCache(cacheDir)
                    .startupTimeout(Duration.ofMillis(300))
                    .build())
                .isInstanceOf(EntitlementClientStartupException.class)
                .hasMessageContaining("conformance");
        }
    }

    @Test
    void buildOnAFeedAdvertisingAnUnknownResolverContractThrowsMentioningResolverContract()
            throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(FEED.replace("\"resolverContract\":1", "\"resolverContract\":99"));

            assertThatThrownBy(() -> EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .startupTimeout(Duration.ofMillis(300))
                    .build())
                .isInstanceOf(EntitlementClientStartupException.class)
                .hasMessageContaining("resolverContract");
        }
    }

    @Test
    void defaultsArePollIntervalFiveSecondsAndStaleAfterSixtySeconds() throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(FEED);
            stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);

            try (var client = (DefaultEntitlementClient) EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .build()) {
                assertThat(client.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                assertThat(client.staleAfter()).isEqualTo(Duration.ofSeconds(60));
            }
        }
    }

    @Test
    void closeStopsThePollerSoVersionCallsStopRising() throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(FEED);
            stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);

            var client = EntitlementClient.builder()
                .serviceUrl(stub.baseUri().toString())
                .pollInterval(Duration.ofMillis(20))
                .build();
            Thread.sleep(100);

            var callsAtClose = stub.versionCalls();
            client.close();
            Thread.sleep(200);

            assertThat(stub.versionCalls())
                .as("a sync already in flight when close() runs may still land once")
                .isLessThanOrEqualTo(callsAtClose + 1);
        }
    }

    /**
     * Pins the fix for the finding that {@code entitlement.client.snapshot.version} and {@code
     * entitlement.client.resolver.contract} were only ever updated from {@code SnapshotPoller}'s
     * swap block, which never runs until the replica actually changes — leaving both gauges
     * reading 0 indefinitely on a quiet estate. {@code build()} must now seed them itself from
     * whichever replica it just loaded, before the poller has run a single sync.
     */
    @Test
    void buildSeedsTheSnapshotVersionAndResolverContractGaugesFromTheFreshlyFetchedReplicaBeforeAnySyncSwapsAnything()
            throws Exception {
        try (var stub = new StubFeedServer()) {
            stub.respondFull(FEED);
            stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);
            var registry = new SimpleMeterRegistry();

            try (var client = EntitlementClient.builder()
                    .serviceUrl(stub.baseUri().toString())
                    .meterRegistry(registry)
                    .build()) {
                assertThat(registry.get("entitlement.client.snapshot.version").gauge().value())
                    .as("must be seeded at build() time, not left at 0 until the first swap")
                    .isEqualTo(48211.0);
                assertThat(registry.get("entitlement.client.resolver.contract").gauge().value())
                    .isEqualTo((double) ResolverContract.VERSION);
            }
        }
    }

    /** The other startup path Finding 1 must cover: a replica loaded from the disk cache. */
    @Test
    void buildFromDiskCacheAlsoSeedsTheSnapshotVersionAndResolverContractGauges(@TempDir Path cacheDir)
            throws Exception {
        new DiskCache(cacheDir).store(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        var unreachable = deadEnd();
        var registry = new SimpleMeterRegistry();

        try (var client = EntitlementClient.builder()
                .serviceUrl(unreachable.toString())
                .startupMode(StartupMode.ALLOW_DISK_CACHE)
                .diskCache(cacheDir)
                .startupTimeout(Duration.ofMillis(300))
                .meterRegistry(registry)
                .build()) {
            assertThat(registry.get("entitlement.client.snapshot.version").gauge().value())
                .as("the cache-loaded replica's version, seeded before the first (stale) sync attempt")
                .isEqualTo(48211.0);
            assertThat(registry.get("entitlement.client.resolver.contract").gauge().value())
                .isEqualTo((double) ResolverContract.VERSION);
        }
    }

    /**
     * Pins the fix for the finding that the disk-cache startup path never checked {@code format}
     * or {@code resolverContract} — only {@link com.solovis.entitlement.client.replica.ConformanceGate}
     * did, and that never runs on a cache load. Both integers are persisted and restored by {@link
     * DiskCache} (unlike the vectors, which are deliberately not persisted), so they must be
     * checked directly: a product upgrading its SDK across a {@code resolverContract} bump and
     * restarting while the service is down must not have {@code ALLOW_DISK_CACHE} serve a replica
     * the contract says this engine must refuse.
     */
    @Test
    void buildFromDiskCacheHoldingAResolverContractThisSdkDoesNotImplementThrowsRatherThanServingIt(
            @TempDir Path cacheDir) throws Exception {
        var mismatched = FEED.replace("\"resolverContract\":1", "\"resolverContract\":99");
        new DiskCache(cacheDir).store(FullSnapshotReader.read(
            new ByteArrayInputStream(mismatched.getBytes(StandardCharsets.UTF_8))));
        var unreachable = deadEnd();

        assertThatThrownBy(() -> EntitlementClient.builder()
                .serviceUrl(unreachable.toString())
                .startupMode(StartupMode.ALLOW_DISK_CACHE)
                .diskCache(cacheDir)
                .startupTimeout(Duration.ofMillis(300))
                .build())
            .isInstanceOf(EntitlementClientStartupException.class)
            .hasMessageContaining("resolverContract");
    }
}
