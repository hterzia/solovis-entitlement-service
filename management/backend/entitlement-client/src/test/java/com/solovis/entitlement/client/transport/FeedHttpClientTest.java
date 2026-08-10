package com.solovis.entitlement.client.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.testing.StubFeedServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedHttpClientTest {

    private StubFeedServer stub;
    private FeedHttpClient client;

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
        """
        {"kind":"footer","version":48211,"recordCount":5}""");

    @BeforeEach
    void start() throws Exception {
        stub = new StubFeedServer();
        client = new FeedHttpClient(stub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        client.close();
        stub.close();
    }

    @Test
    void theVersionPollReturnsTheFeedsVersionFormatAndResolverContract() {
        stub.respondVersion(48211L, "2026-08-09T14:03:10.900Z", 1, 1);

        var version = client.version();

        assertThat(version.version()).isEqualTo(48211L);
        assertThat(version.format()).isEqualTo(1);
        assertThat(version.resolverContract()).isEqualTo(1);
    }

    @Test
    void theFullSnapshotIsGunzippedAndParsedIntoAReplica() {
        stub.respondFull(FEED);

        var replica = client.full();

        assertThat(replica.version()).isEqualTo(48211L);
        assertThat(replica.snapshot().capabilities()).hasSize(1);
    }

    @Test
    void aTruncatedFullSnapshotIsRejectedRatherThanPartiallyApplied() {
        stub.respondFull(FEED);
        stub.truncateFullSnapshot();

        assertThatThrownBy(() -> client.full()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void aFullSnapshotBodyThatIsNotActuallyGzipIsAnUnavailableFeedNotAnUncaughtIoException() {
        stub.respondFullWithInvalidGzip();

        assertThatThrownBy(() -> client.full()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void aDeltaIsFetchedWithTheSinceParameterAndParsedFlatWithoutANestedChangeObject() {
        stub.respondDelta("""
            {"format":1,"fromVersion":48208,"toVersion":48209,\
            "publishedAt":"2026-08-09T14:03:10.900Z",\
            "changes":[{"version":48209,"kind":"plan.archived","key":"free"}]}""");

        var delta = client.delta(48208L);

        assertThat(delta.fromVersion()).isEqualTo(48208L);
        assertThat(delta.toVersion()).isEqualTo(48209L);
        assertThat(delta.changes()).hasSize(1);
        assertThat(delta.changes().get(0).get("kind").asString()).isEqualTo("plan.archived");
        assertThat(stub.requestedPaths()).anyMatch(p -> p.contains("since=48208"));
    }

    @Test
    void aSinceOlderThanTheRetainedHorizonAsksForAFullResyncRatherThanBeingTreatedAsAnOutage() {
        stub.failWith(410, """
            {"type":"entitlement/snapshot-too-old","title":"Snapshot too old","status":410,\
            "detail":"…","currentVersion":48211}""");

        assertThatThrownBy(() -> client.delta(1L)).isInstanceOf(SnapshotTooOldException.class);
    }

    @Test
    void aSinceAheadOfTheServiceMeansItWasRestoredFromBackupAndAlsoTriggersAFullResync() {
        stub.failWith(422, """
            {"type":"entitlement/validation-failed","title":"Validation failed","status":422,\
            "detail":"…","currentVersion":100}""");

        assertThatThrownBy(() -> client.delta(99999L)).isInstanceOf(SnapshotTooOldException.class);
    }

    @Test
    void aServerErrorIsAnUnavailableFeedNotASignalToResync() {
        stub.failWith(503, "{\"type\":\"entitlement/internal-error\",\"status\":503}");

        assertThatThrownBy(() -> client.version()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void anUnreachableServiceIsAnUnavailableFeedAndNeverAnUncheckedIoException() {
        stub.close();

        assertThatThrownBy(() -> client.version()).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void aMalformedBodyIsAnUnavailableFeedBecauseAnUnparseableAnswerIsNoAnswer() {
        stub.respondDelta("{ this is not json");

        assertThatThrownBy(() -> client.delta(1L)).isInstanceOf(FeedUnavailableException.class);
    }

    @Test
    void decisionJsonPercentEncodesAccountAndCapabilityPathSegmentsRatherThanThrowing() {
        stub.respondDecision("{\"allowed\":true}");

        var body = client.decisionJson("acct 9931", "api access");

        assertThat(body).isEqualTo("{\"allowed\":true}");
        assertThat(stub.requestedPaths())
            .anyMatch(p -> p.contains("/v1/accounts/acct%209931/capabilities/api%20access"));
    }

    @Test
    void closingLeavesACallerSuppliedHttpClientOpenForTheCallerToReuseOrCloseItself() {
        var supplied = HttpClient.newHttpClient();
        var suppliedClientOwner = new FeedHttpClient(stub.baseUri(), supplied, Duration.ofSeconds(5));

        suppliedClientOwner.close();

        assertThat(supplied.isTerminated()).isFalse();
        supplied.close();
    }

    @Test
    void closingShutsDownTheHttpClientTheConvenienceConstructorCreatedForItself() {
        var owner = new FeedHttpClient(stub.baseUri(), Duration.ofSeconds(5));
        stub.respondVersion(1L, "2026-08-09T14:00:00.000Z", 1, 1);
        assertThat(owner.version().version()).isEqualTo(1L);   // works while open

        owner.close();

        // The stub server is still up; only the owned HttpClient was shut down, so the next
        // request fails at the transport layer rather than reaching the server.
        assertThatThrownBy(owner::version).isInstanceOf(FeedUnavailableException.class);
    }
}
