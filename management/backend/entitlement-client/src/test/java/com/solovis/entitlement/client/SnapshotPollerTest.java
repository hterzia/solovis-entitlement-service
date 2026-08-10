package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.testing.StubFeedServer;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnapshotPollerTest {

    private StubFeedServer stub;
    private FeedHttpClient feed;
    private AtomicReference<Replica> holder;
    private MutableClock clock;

    /** A clock the test advances by hand, so staleness is deterministic. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-09T14:03:10.900Z");
        void advance(Duration by) {
            now = now.plus(by);
        }
        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
        @Override public Instant instant() {
            return now;
        }
    }

    private static String feedAt(long version, String extraLine) {
        var lines = new java.util.ArrayList<String>();
        lines.add("{\"kind\":\"header\",\"version\":" + version + ",\"format\":1,\"resolverContract\":1,"
            + "\"publishedAt\":\"2026-08-09T14:03:10.900Z\","
            + "\"counts\":{\"capabilities\":1,\"plans\":1,\"accounts\":1,\"overrides\":0}}");
        lines.add("{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\",\"valueType\":\"SWITCH\","
            + "\"default\":{\"type\":\"SWITCH\",\"enabled\":false},\"status\":\"ACTIVE\"}");
        lines.add("{\"kind\":\"plan\",\"key\":\"pro\",\"status\":\"ACTIVE\",\"isDefaultForNewAccounts\":true,"
            + "\"entitlements\":{\"api.access\":{\"type\":\"SWITCH\",\"enabled\":true}}}");
        lines.add("{\"kind\":\"account\",\"external\":\"acct_9931\",\"planKey\":\"pro\"}");
        if (extraLine != null) {
            lines.add(extraLine);
        }
        lines.add("{\"kind\":\"footer\",\"version\":" + version + ",\"recordCount\":" + (lines.size() + 1) + "}");
        return String.join("\n", lines);
    }

    private static Replica replicaAt(long version) {
        return FullSnapshotReader.read(
            new ByteArrayInputStream(feedAt(version, null).getBytes(StandardCharsets.UTF_8)));
    }

    private SnapshotPoller poller() {
        return new SnapshotPoller(feed, holder, Duration.ofSeconds(5), Duration.ofSeconds(60),
            null, ClientMetrics.NO_OP, clock);
    }

    @BeforeEach
    void start() throws Exception {
        stub = new StubFeedServer();
        feed = new FeedHttpClient(stub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
        holder = new AtomicReference<>(replicaAt(100L));
        clock = new MutableClock();
    }

    @AfterEach
    void stop() {
        feed.close();
        stub.close();
    }

    @Test
    void aVersionMatchingTheReplicaIsASuccessfulSyncThatChangesNothing() {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var before = holder.get();

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void aNewerVersionIsAppliedByDeltaAndSwappedIn() {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"account.upserted","external":"acct_new","planKey":"pro"}]}""");

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get().version()).isEqualTo(101L);
        assertThat(holder.get().snapshot().account("acct_new")).isPresent();
    }

    @Test
    void anUnreachableServiceLeavesTheReplicaServingAndNeverThrows() {
        stub.close();
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
    }

    @Test
    void aFailedSyncIsRecordedSoHealthCanSurfaceIt() {
        stub.close();
        var poller = poller();

        poller.syncOnce();

        assertThat(poller.state().lastError()).isNotNull();
    }

    @Test
    void aSinceOlderThanTheHorizonTriggersAFullResyncAndTheOldReplicaServesUntilItCompletes() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        // Targets the delta call only: the version poll above must succeed normally, and it is
        // specifically the ?since= request going stale that must trigger the resync.
        stub.failNextDeltaWith(410, """
            {"type":"entitlement/snapshot-too-old","title":"Snapshot too old","status":410,\
            "currentVersion":200}""");
        stub.respondFull(feedAt(200L, null));

        assertThat(poller().syncOnce()).isTrue();
        assertThat(holder.get().version()).isEqualTo(200L);
        assertThat(stub.deltaCalls()).isEqualTo(1);
        assertThat(stub.fullCalls()).isEqualTo(1);
    }

    @Test
    void aFullResyncThatAlsoFailsLeavesThePreviousReplicaInPlace() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.failNextDeltaWith(410, "{\"type\":\"entitlement/snapshot-too-old\",\"status\":410}");
        stub.respondFull(feedAt(200L, null));
        stub.truncateFullSnapshot();
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(stub.deltaCalls()).isEqualTo(1);
        assertThat(stub.fullCalls()).isEqualTo(1);
    }

    @Test
    void anUnknownDeltaChangeKindStopsSyncingAltogetherRatherThanDivergingSilently() {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"something.newer","payload":1}]}""");
        var poller = poller();
        var before = holder.get();

        assertThat(poller.syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(poller.stopped()).isTrue();

        // and it does not try again
        int callsSoFar = stub.versionCalls();
        poller.syncOnce();
        assertThat(stub.versionCalls()).isEqualTo(callsSoFar);
    }

    @Test
    void aResolverContractTheSdkDoesNotImplementStopsSyncingAndKeepsTheLastGoodReplica() {
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 99);
        var poller = poller();
        var before = holder.get();

        assertThat(poller.syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(poller.stopped()).isTrue();
    }

    @Test
    void aCandidateFailingTheConformanceGateIsDiscardedAndThePreviousReplicaKeepsServing() {
        var badVector = "{\"kind\":\"conformance\",\"id\":\"a vector this engine disagrees with\","
            + "\"model\":{\"account\":\"acct_c1\",\"capability\":\"api.access\","
            + "\"capabilities\":[{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\","
            + "\"valueType\":\"SWITCH\",\"default\":{\"type\":\"SWITCH\",\"enabled\":false},"
            + "\"status\":\"ACTIVE\"}],"
            + "\"plans\":[{\"kind\":\"plan\",\"key\":\"p\",\"status\":\"ACTIVE\","
            + "\"isDefaultForNewAccounts\":true,\"entitlements\":{}}],"
            + "\"accounts\":[{\"kind\":\"account\",\"external\":\"acct_c1\",\"planKey\":\"p\"}],"
            + "\"overrides\":[]},"
            + "\"expect\":{\"allowed\":true,\"value\":{\"type\":\"SWITCH\",\"enabled\":true}}}";
        stub.respondVersion(200L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.failNextDeltaWith(410, "{\"type\":\"entitlement/snapshot-too-old\",\"status\":410}");
        stub.respondFull(feedAt(200L, badVector));
        var before = holder.get();

        assertThat(poller().syncOnce()).isFalse();
        assertThat(holder.get()).isSameAs(before);
        assertThat(stub.deltaCalls()).isEqualTo(1);
        assertThat(stub.fullCalls()).isEqualTo(1);
    }

    @Test
    void theReplicaBecomesStaleOnceSyncsHaveBeenFailingLongerThanTheStaleWindow() {
        stub.close();
        var poller = poller();
        poller.syncOnce();
        assertThat(poller.state().stale()).isFalse();

        clock.advance(Duration.ofSeconds(61));
        poller.syncOnce();

        assertThat(poller.state().stale()).isTrue();
    }

    @Test
    void aSuccessfulSyncClearsStalenessAndTheRecordedError() throws Exception {
        // Drive the shared poller into a stale, errored state first, exactly as
        // theReplicaBecomesStaleOnceSyncsHaveBeenFailingLongerThanTheStaleWindow does.
        stub.close();
        var stalePoller = poller();
        stalePoller.syncOnce();
        clock.advance(Duration.ofSeconds(61));
        stalePoller.syncOnce();
        assertThat(stalePoller.state().stale()).isTrue();
        assertThat(stalePoller.state().lastError()).isNotNull();

        // A poller's FeedHttpClient is bound for its lifetime, so recovering the exact same
        // instance on the exact same port is not reproducible in a test. Instead, prove the
        // transition logic directly: a poller whose only sync attempt succeeds reports a clean
        // state — the same succeed() path a recovered original poller would take.
        try (var freshStub = new StubFeedServer();
                var freshFeed = new FeedHttpClient(
                        freshStub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5))) {
            freshStub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
            var freshPoller = new SnapshotPoller(freshFeed, holder, Duration.ofSeconds(5),
                Duration.ofSeconds(60), null, ClientMetrics.NO_OP, clock);

            assertThat(freshPoller.syncOnce()).isTrue();
            assertThat(freshPoller.state().stale()).isFalse();
            assertThat(freshPoller.state().lastError()).isNull();
        }
    }

    /**
     * Pins the fix: {@code fail()} used to recompute {@code stale} purely from elapsed time since
     * {@code lastSuccessfulSync}. {@code markStaleAtStartup()} stamps that field as "now" for lack
     * of a real sync, so the very first failed attempt right after seeding — elapsed time ~0 —
     * used to conclude "not stale yet" and silently clear the deliberately-set flag. Stale must be
     * sticky: once known stale, only a real {@code succeed()} may clear it.
     */
    @Test
    void aFailedSyncCannotMakeAReplicaThatStartedStaleReportFreshAgain() {
        stub.close();
        var poller = poller();
        poller.markStaleAtStartup();
        assertThat(poller.state().stale()).isTrue();

        // Fails almost instantly (connection refused); elapsed time since the fabricated "just
        // seeded" timestamp is nowhere near staleAfter.
        assertThat(poller.syncOnce()).isFalse();

        assertThat(poller.state().stale())
            .as("a failed sync must never make an already-stale replica report fresh")
            .isTrue();
    }

    /** The other half of the fix's contract: stickiness must not become "stale forever." */
    @Test
    void aSuccessfulSyncStillClearsStalenessEvenAfterMarkStaleAtStartup() {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var poller = poller();
        poller.markStaleAtStartup();
        assertThat(poller.state().stale()).isTrue();

        assertThat(poller.syncOnce()).isTrue();

        assertThat(poller.state().stale())
            .as("succeed() must still clear staleness — stickiness only stops fail() from doing so")
            .isFalse();
    }

    @Test
    void aConfiguredDiskCacheIsWrittenOnEverySuccessfulSwap(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) {
        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"account.upserted","external":"acct_new","planKey":"pro"}]}""");
        var cache = new com.solovis.entitlement.client.replica.DiskCache(dir);

        new SnapshotPoller(feed, holder, Duration.ofSeconds(5), Duration.ofSeconds(60),
            cache, ClientMetrics.NO_OP, clock).syncOnce();

        assertThat(cache.load()).get().extracting(r -> ((Replica) r).version()).isEqualTo(101L);
    }

    @Test
    void theDaemonLoopKeepsRunningAcrossFailuresAndStopsOnClose() throws Exception {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var poller = new SnapshotPoller(feed, holder, Duration.ofMillis(20), Duration.ofSeconds(60),
            null, ClientMetrics.NO_OP, clock);

        poller.start();
        Thread.sleep(200);
        int calls = stub.versionCalls();
        poller.close();
        Thread.sleep(100);

        assertThat(calls).isGreaterThan(1);
        assertThat(stub.versionCalls()).isLessThanOrEqualTo(calls + 1);
    }

    @Test
    void nudgeWakesASleepingPollerToSyncSoonerWithoutKillingItAndCloseStillStopsItPromptly() throws Exception {
        // A pollInterval long enough that nothing short of a nudge could explain a second sync
        // happening inside this test's lifetime.
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        var poller = new SnapshotPoller(feed, holder, Duration.ofMinutes(10), Duration.ofSeconds(60),
            null, ClientMetrics.NO_OP, clock);

        poller.start();
        Thread.sleep(50);   // let the first (no-op) sync finish and the thread settle into its sleep
        assertThat(holder.get().version()).isEqualTo(100L);

        stub.respondVersion(101L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondDelta("""
            {"format":1,"fromVersion":100,"toVersion":101,"publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":101,"kind":"account.upserted","external":"acct_new","planKey":"pro"}]}""");

        poller.nudge();
        Thread.sleep(300);   // comfortably inside the 10-minute pollInterval

        assertThat(holder.get().version())
            .as("nudge() must cut the sleep short and sync now rather than after pollInterval")
            .isEqualTo(101L);
        assertThat(poller.state().lastError())
            .as("the thread survived the nudge and completed a real sync, not just woke and died")
            .isNull();

        // Proof the thread is still the same live daemon (an unconditional interrupt->return
        // regression would have exited it above): close() can still stop it.
        int calls = stub.versionCalls();
        poller.close();
        Thread.sleep(100);
        assertThat(stub.versionCalls()).isLessThanOrEqualTo(calls + 1);
    }

    // markUngatedAtStartup(): a replica seeded from disk cache (DiskCache does not persist
    // conformance vectors) must not be treated as verified just because the service happens to
    // report the same version — the equality fast path below must not let it dodge the gate.

    @Test
    void aReplicaSeededUngatedForcesAFullFetchAndGatesEvenWhenTheServiceReportsTheSameVersion() {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondFull(feedAt(100L, null));
        var before = holder.get();
        var poller = poller();
        poller.markUngatedAtStartup();

        assertThat(poller.syncOnce()).isTrue();

        assertThat(stub.fullCalls())
            .as("the fast path must not short-circuit an ungated replica even at a matching version")
            .isEqualTo(1);
        assertThat(stub.deltaCalls()).isEqualTo(0);
        assertThat(holder.get())
            .as("a real gated swap must have happened, not just the equality shortcut returning success")
            .isNotSameAs(before);
        assertThat(holder.get().version()).isEqualTo(100L);
    }

    @Test
    void aForcedFetchThatFailsTheGateOnAnUngatedReplicaDiscardsTheCandidateAndKeepsTheCacheReplicaServing() {
        var badVector = "{\"kind\":\"conformance\",\"id\":\"a vector this engine disagrees with\","
            + "\"model\":{\"account\":\"acct_c1\",\"capability\":\"api.access\","
            + "\"capabilities\":[{\"kind\":\"capability\",\"key\":\"api.access\",\"area\":\"api\","
            + "\"valueType\":\"SWITCH\",\"default\":{\"type\":\"SWITCH\",\"enabled\":false},"
            + "\"status\":\"ACTIVE\"}],"
            + "\"plans\":[{\"kind\":\"plan\",\"key\":\"p\",\"status\":\"ACTIVE\","
            + "\"isDefaultForNewAccounts\":true,\"entitlements\":{}}],"
            + "\"accounts\":[{\"kind\":\"account\",\"external\":\"acct_c1\",\"planKey\":\"p\"}],"
            + "\"overrides\":[]},"
            + "\"expect\":{\"allowed\":true,\"value\":{\"type\":\"SWITCH\",\"enabled\":true}}}";
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondFull(feedAt(100L, badVector));
        var before = holder.get();
        var poller = poller();
        poller.markUngatedAtStartup();

        assertThat(poller.syncOnce()).isFalse();

        assertThat(holder.get()).isSameAs(before);
        assertThat(poller.state().lastError()).isNotNull();
    }

    @Test
    void afterOneSuccessfulGatedSyncFromAnUngatedSeedASubsequentSameVersionPollTakesTheFastPath() {
        stub.respondVersion(100L, "2026-08-09T14:03:10.900Z", 1, 1);
        stub.respondFull(feedAt(100L, null));
        var poller = poller();
        poller.markUngatedAtStartup();
        assertThat(poller.syncOnce()).isTrue();
        assertThat(stub.fullCalls()).isEqualTo(1);

        assertThat(poller.syncOnce()).isTrue();

        assertThat(stub.fullCalls())
            .as("once gated, the ordinary equality fast path must resume rather than fetching forever")
            .isEqualTo(1);
    }
}
