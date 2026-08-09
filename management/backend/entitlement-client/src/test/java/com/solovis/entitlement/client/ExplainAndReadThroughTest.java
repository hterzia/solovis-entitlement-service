package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.error.ExplanationUnavailableException;
import com.solovis.entitlement.client.error.ReplicaUnknownAccountException;
import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.testing.StubFeedServer;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.core.engine.Outcome;
import com.solovis.entitlement.core.engine.TraceSource;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExplainAndReadThroughTest {

    private static final Instant NOW = Instant.parse("2026-08-09T14:05:00.000Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // Same account/capability/plan/override shape as DefaultEntitlementClientTest's FEED, so
    // "acct_9931" / "reports.monthly" / "api.access" are known to the replica and any other
    // account id used below is genuinely unknown to it.
    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":2,"plans":1,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"reports.monthly","area":"reports","valueType":"QUANTITY",\
        "default":{"type":"QUANTITY","amount":0},"offValue":{"type":"QUANTITY","amount":0},\
        "status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"reports.monthly":{"type":"QUANTITY","amount":50},\
        "api.access":{"type":"SWITCH","enabled":true}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",\
        "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""",
        """
        {"kind":"footer","version":48211,"recordCount":7}""");

    private static final String EXPLAINED = """
        {"account":"acct_9931","capability":"reports.monthly","allowed":true,
         "value":{"type":"QUANTITY","amount":200},"snapshotVersion":48211,
         "evaluatedAt":"2026-08-09T14:05:00.000Z",
         "trace":{
           "baseline":{"source":"PLAN","planKey":"pro","value":{"type":"QUANTITY","amount":50},
                       "note":"Plan 'pro' sets this capability."},
           "grants":[{"overrideId":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                      "reason":"negotiated uplift","createdBy":"ops@solovis",
                      "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "grantStep":{"applied":true,"winner":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                        "note":"Most generous GRANT (200) beats the plan baseline (50)."},
           "holds":[],
           "holdStep":{"applied":false,"why":"NO_HOLDS"},
           "result":{"value":{"type":"QUANTITY","amount":200},"allowed":true,
                     "allowedReason":"DIFFERS_FROM_OFF_VALUE"}}}""";

    // A HOLD candidate can be marked WON in its own list while the step still reports
    // applied:false — Outcome's own contract: the most restrictive hold is always WON among
    // holds, even when it does not cap the result. holdWinner must stay empty here.
    private static final String EXPLAINED_WITH_UNAPPLIED_HOLD = """
        {"account":"acct_9931","capability":"reports.monthly","allowed":true,
         "value":{"type":"QUANTITY","amount":200},"snapshotVersion":48211,
         "evaluatedAt":"2026-08-09T14:05:00.000Z",
         "trace":{
           "baseline":{"source":"PLAN","planKey":"pro","value":{"type":"QUANTITY","amount":50},
                       "note":"Plan 'pro' sets this capability."},
           "grants":[{"overrideId":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                      "reason":"negotiated uplift","createdBy":"ops@solovis",
                      "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "grantStep":{"applied":true,"winner":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                        "note":"Most generous GRANT (200) beats the plan baseline (50)."},
           "holds":[{"overrideId":"ovr_5501","value":{"type":"QUANTITY","amount":500},
                     "reason":"soft cap","createdBy":"ops@solovis",
                     "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "holdStep":{"applied":false,"why":"HOLD_NOT_MORE_RESTRICTIVE",
                       "note":"No HOLD is more restrictive than the post-grant value."},
           "result":{"value":{"type":"QUANTITY","amount":200},"allowed":true,
                     "allowedReason":"DIFFERS_FROM_OFF_VALUE"}}}""";

    // The mirror case: a HOLD that does bind. holdStep.applied:true, and its WON candidate must
    // surface as the winner — pinned separately so the fix above cannot be satisfied by making
    // holdWinner always empty.
    private static final String EXPLAINED_WITH_BINDING_HOLD = """
        {"account":"acct_9931","capability":"reports.monthly","allowed":true,
         "value":{"type":"QUANTITY","amount":100},"snapshotVersion":48211,
         "evaluatedAt":"2026-08-09T14:05:00.000Z",
         "trace":{
           "baseline":{"source":"PLAN","planKey":"pro","value":{"type":"QUANTITY","amount":50},
                       "note":"Plan 'pro' sets this capability."},
           "grants":[{"overrideId":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                      "reason":"negotiated uplift","createdBy":"ops@solovis",
                      "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "grantStep":{"applied":true,"winner":"ovr_4471","value":{"type":"QUANTITY","amount":200},
                        "note":"Most generous GRANT (200) beats the plan baseline (50)."},
           "holds":[{"overrideId":"ovr_5502","value":{"type":"QUANTITY","amount":100},
                     "reason":"hard cap","createdBy":"ops@solovis",
                     "createdAt":"2026-08-01T09:00:00.000Z","outcome":"WON"}],
           "holdStep":{"applied":true,"winner":"ovr_5502","value":{"type":"QUANTITY","amount":100},
                       "note":"Most restrictive HOLD (100) caps the result."},
           "result":{"value":{"type":"QUANTITY","amount":100},"allowed":true,
                     "allowedReason":"DIFFERS_FROM_OFF_VALUE"}}}""";

    private StubFeedServer stub;
    private FeedHttpClient feed;

    /**
     * Wires a client to a fresh {@link StubFeedServer}: the replica is loaded straight from
     * {@link #FEED} (as {@code DefaultEntitlementClientTest} does), and {@code feed} points at the
     * stub's {@code /v1/accounts/...} route, driven per-test by {@code stub.respondDecision(...)}
     * or {@code stub.failWith(...)}. No poller — these tests exercise {@code explain()} and the
     * read-through inside {@code check()}, neither of which needs one.
     */
    private DefaultEntitlementClient client(ClientMetrics metrics) throws Exception {
        stub = new StubFeedServer();
        feed = new FeedHttpClient(stub.baseUri(), HttpClient.newHttpClient(), Duration.ofSeconds(5));
        var holder = new AtomicReference<Replica>(
            FullSnapshotReader.read(new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        return new DefaultEntitlementClient(holder, null, feed, CLOCK, metrics);
    }

    private static String decisionJsonFor(String account, String capability, long amount, long snapshotVersion) {
        return """
            {"account":"%s","capability":"%s","allowed":true,
             "value":{"type":"QUANTITY","amount":%d},"snapshotVersion":%d,
             "evaluatedAt":"2026-08-09T14:05:00.000Z",
             "trace":{
               "baseline":{"source":"CAPABILITY_DEFAULT","value":{"type":"QUANTITY","amount":0},
                           "note":"No plan entitlement is set; the capability default applies."},
               "grants":[],
               "grantStep":{"applied":false,"why":"NO_GRANTS",
                            "note":"No GRANT overrides exist for this capability on this account."},
               "holds":[],
               "holdStep":{"applied":false,"why":"NO_HOLDS",
                           "note":"No HOLD overrides exist for this capability on this account."},
               "result":{"value":{"type":"QUANTITY","amount":%d},"allowed":true,
                         "allowedReason":"DIFFERS_FROM_OFF_VALUE"}}}"""
            .formatted(account, capability, amount, snapshotVersion, amount);
    }

    @AfterEach
    void tearDown() {
        if (feed != null) {
            feed.close();
        }
        if (stub != null) {
            stub.close();
        }
    }

    @Test
    void explainFetchesTheServicesRecordBecauseAReplicaHoldsNoTraceData() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.respondDecision(EXPLAINED);

        var explanation = client.explain("acct_9931", "reports.monthly");

        assertThat(explanation.decision().value()).isEqualTo(EntitlementValue.Quantity.of(200));
        assertThat(explanation.decision().allowed()).isTrue();
        assertThat(explanation.decision().snapshotVersion()).isEqualTo(48211L);

        var trace = explanation.trace();
        assertThat(trace.baseline().source()).isEqualTo(TraceSource.PLAN);
        assertThat(trace.baseline().planKey()).contains("pro");
        assertThat(trace.grants()).hasSize(1);
        var grant = trace.grants().get(0);
        assertThat(grant.overrideId()).isEqualTo(OptionalLong.of(4471L));
        // Reason text lives only on this diagnostic path — a replica never carries it.
        assertThat(grant.reason()).contains("negotiated uplift");
        assertThat(trace.grantWinner()).isPresent();
        assertThat(trace.holdWinner()).isEmpty();
    }

    @Test
    void explainReportsNoHoldWinnerWhenTheStepDidNotApplyEvenThoughTheCandidatesOwnOutcomeIsWon()
            throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.respondDecision(EXPLAINED_WITH_UNAPPLIED_HOLD);

        var trace = client.explain("acct_9931", "reports.monthly").trace();

        assertThat(trace.holds()).hasSize(1);
        assertThat(trace.holds().get(0).outcome()).contains(Outcome.WON);
        assertThat(trace.holdWinner())
            .as("holdStep.applied is false, so there is no winner despite the candidate's own WON outcome")
            .isEmpty();
    }

    @Test
    void explainReportsTheHoldWinnerWhenTheStepReportsItActuallyBound() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.respondDecision(EXPLAINED_WITH_BINDING_HOLD);

        var explanation = client.explain("acct_9931", "reports.monthly");

        assertThat(explanation.decision().value()).isEqualTo(EntitlementValue.Quantity.of(100));
        assertThat(explanation.trace().holdWinner()).isPresent();
        assertThat(explanation.trace().holdWinner().get().overrideId()).isEqualTo(OptionalLong.of(5502L));
    }

    @Test
    void explainFailsLoudlyWhenTheServiceIsUnreachableBecauseItIsADiagnosticNotADecision() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.close();

        assertThatThrownBy(() -> client.explain("acct_9931", "reports.monthly"))
            .isInstanceOf(ExplanationUnavailableException.class);
    }

    @Test
    void anAccountTheReplicaLacksIsConfirmedByOneBoundedReadThroughRatherThanFailingAtSignup() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.respondDecision(decisionJsonFor("acct_brand_new", "reports.monthly", 75, 48300));

        var decision = client.check("acct_brand_new", "reports.monthly");

        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(75));
        assertThat(decision.snapshotVersion()).isEqualTo(48300L);
        assertThat(stub.requestedPaths().stream().filter(p -> p.startsWith("/v1/accounts")).count())
            .isEqualTo(1);
    }

    @Test
    void aReadThroughThatConfirmsNothingThrowsCarryingTheEvidenceOfWhichCaseItWas() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.failWith(404, """
            {"type":"entitlement/unknown-account","title":"Unknown account","status":404,\
            "detail":"No account is declared with external id 'acct_nope'."}""");

        assertThatThrownBy(() -> client.check("acct_nope", "reports.monthly"))
            .isInstanceOf(ReplicaUnknownAccountException.class)
            .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).readThroughAttempted()).isTrue());
    }

    @Test
    void anUnreachableServiceOnAnUnknownAccountStillThrowsBecauseThereIsNoLastAnswerToCarryOn() throws Exception {
        var client = client(ClientMetrics.NO_OP);
        stub.close();

        assertThatThrownBy(() -> client.check("acct_unknown", "reports.monthly"))
            .isInstanceOf(ReplicaUnknownAccountException.class)
            .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).readThroughAttempted()).isFalse())
            .satisfies(e -> assertThat(((ReplicaUnknownAccountException) e).snapshotAge()).isPositive());
    }

    @Test
    void aKnownAccountNeverTriggersAReadThroughSoTheDecisionPathStaysLocal() throws Exception {
        var client = client(ClientMetrics.NO_OP);

        client.check("acct_9931", "api.access");

        assertThat(stub.requestedPaths()).noneMatch(p -> p.startsWith("/v1/accounts"));
    }

    @Test
    void checkAllOnAnUnknownAccountThrowsImmediatelyWithoutAReadThrough() throws Exception {
        var client = client(ClientMetrics.NO_OP);

        assertThatThrownBy(() -> client.checkAll("acct_nope")).isInstanceOf(UnknownAccountException.class);

        assertThat(stub.requestedPaths()).noneMatch(p -> p.startsWith("/v1/accounts"));
    }

    @Test
    void aReadThroughIsCountedSoASustainedRiseRevealsLaggingReplicas() throws Exception {
        var count = new AtomicInteger();
        var metrics = new ClientMetrics() {
            @Override
            public void readThrough() {
                count.incrementAndGet();
            }
        };
        var client = client(metrics);
        stub.respondDecision(decisionJsonFor("acct_brand_new", "reports.monthly", 75, 48300));

        client.check("acct_brand_new", "reports.monthly");

        assertThat(count.get()).isEqualTo(1);
    }
}
