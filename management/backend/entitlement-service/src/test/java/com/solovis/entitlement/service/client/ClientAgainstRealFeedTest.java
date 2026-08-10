package com.solovis.entitlement.service.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.client.EntitlementClient;
import com.solovis.entitlement.client.error.SnapshotBehindException;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.transport.FeedHttpClient;
import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.snapshot.SnapshotAssembler;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The anti-drift test: a real {@link EntitlementClient} against a real, running service, over a
 * real socket — the only test in this build that exercises both sides of the wire at once.
 *
 * <p>Every other SDK test (in {@code entitlement-client}) runs against {@code StubFeedServer}, a
 * fixture hand-written from reading the service's source. If the service's wire encoding drifted,
 * the stub would drift right along with the developer's assumptions and every one of those tests
 * would stay green while production broke. This class is what would have caught it. Five wire-
 * format drifts between the contract docs and the service's actual behaviour were found and fixed
 * during this build: {@code overrideKind} vs {@code kind}, omitted-vs-null {@code offValue}/{@code
 * tiers}, sentence-shaped conformance ids, an unimplemented {@code conformance.changed}, and a bare
 * {@code seats} capability key the validator rejects (a key needs at least one dot). This test is
 * what stops a sixth appearing unnoticed.
 *
 * <p>Fixtures and JVM-fork sharing follow {@code DecisionControllerTest}'s pattern exactly (read
 * its class javadoc for the underlying reasons): every {@code @SpringBootTest} class in this
 * module's JVM fork shares one temp-file SQLite database, so this class is deliberately not
 * {@code @Transactional} — a rollback here would desync the shared {@link SnapshotHolder} singleton
 * from the database it is supposed to mirror — and every fixture is namespaced {@code t15-}/{@code
 * acct_t15_}/{@code t15.*} so it cannot collide with another class's permanent rows. Seeding runs
 * once via {@code @BeforeAll} under {@code @TestInstance(PER_CLASS)}, not {@code @BeforeEach},
 * because nothing rolls the inserts back between test methods. This class does not claim the
 * single default plan — {@code SchemaInvariantsTest} owns that.
 *
 * <p>The real {@link EntitlementClient} under test is built once, here in {@code @BeforeAll}, by
 * pointing it at this Spring Boot test's own random port. Building it is itself most of the test's
 * value: {@code build()} fetches a real full snapshot and runs it through the conformance gate
 * against the service's own vectors, so if that gate fails, {@code @BeforeAll} itself fails and
 * every test method below is reported as an error — exactly the right failure mode for a class
 * whose entire purpose is proving the two engines agree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientAgainstRealFeedTest {

    private static final String GRANT_REASON = "t15 grant reason: negotiated uplift for quantity, not for replicas to see";
    private static final String HOLD_REASON = "t15 hold reason: soft cap pending review, not for replicas to see";
    private static final String ACCOUNT = "acct_t15_1";
    private static final String CAP_SWITCH = "t15.switch";
    private static final String CAP_QUANTITY = "t15.quantity";
    private static final String CAP_TIER = "t15.tier";
    private static final String CAP_RETIRED = "t15.retired";
    private static final String CAP_UNKNOWN = "t15.does-not-exist";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TS = "2026-08-09T00:00:00.000Z";

    @LocalServerPort
    int port;

    @Autowired SnapshotHolder snapshotHolder;
    @Autowired SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired PlanEntitlementRepository planEntitlementRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountOverrideRepository accountOverrideRepository;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired AuditEventRepository auditEventRepository;

    private String baseUrl;
    private EntitlementClient client;
    private HttpClient http;
    private long grantOverrideId;
    private long holdOverrideId;

    @BeforeAll
    void seedPublishAndBuildTheRealClient() {
        long switchCapId = capabilityRepository.insert(new CapabilityRow(null, CAP_SWITCH, "t15", "T15 Switch",
            null, "SWITCH", false, null, false, null, false, null, null, "ACTIVE", null, TS, TS));
        long quantityCapId = capabilityRepository.insert(new CapabilityRow(null, CAP_QUANTITY, "t15", "T15 Quantity",
            null, "QUANTITY", null, 5L, false, null, true, 0L, null, "ACTIVE", null, TS, TS));
        long tierCapId = capabilityRepository.insert(new CapabilityRow(null, CAP_TIER, "t15", "T15 Tier",
            null, "TIER", null, null, false, "bronze", false, null, null, "ACTIVE", null, TS, TS));
        capabilityRepository.insertTier(new CapabilityTierRow(tierCapId, "bronze", 0, "Bronze"));
        capabilityRepository.insertTier(new CapabilityTierRow(tierCapId, "silver", 1, "Silver"));
        capabilityRepository.insertTier(new CapabilityTierRow(tierCapId, "gold", 2, "Gold"));
        // A retired capability so the RetiredCapabilityException path (case 4) has something to
        // exercise. A QUANTITY off-value of exactly 0 satisfies core's validation for a capability
        // that is never actually resolved.
        capabilityRepository.insert(new CapabilityRow(null, CAP_RETIRED, "t15", "T15 Retired",
            null, "QUANTITY", null, 0L, false, null, false, null, null, "RETIRED", TS, TS, TS));

        // Not the default plan: SchemaInvariantsTest legitimately claims the one allowed default,
        // and every non-transactional @SpringBootTest class in this JVM fork shares one SQLite
        // file, so claiming it here would make the pair order-dependent.
        long planId = planRepository.insert(new PlanRow(null, "t15-plan", "T15 Plan", null, "ACTIVE", false, TS, TS));
        planEntitlementRepository.upsert(new PlanEntitlementRow(planId, switchCapId, true, null, false, null, TS));
        planEntitlementRepository.upsert(new PlanEntitlementRow(planId, quantityCapId, null, 20L, false, null, TS));
        planEntitlementRepository.upsert(new PlanEntitlementRow(planId, tierCapId, null, null, false, "gold", TS));

        long accountId = accountRepository.insert(new AccountRow(null, ACCOUNT, null, planId, TS,
            "PERSON", "dev-operator", "ACTIVE", TS, TS));

        // GRANT raises t15.quantity from the plan's 20 to 150 — a candidate check() and the live
        // HTTP decision route must agree exceeded the plan baseline.
        grantOverrideId = accountOverrideRepository.insert(new AccountOverrideRow(null, accountId, quantityCapId,
            "GRANT", null, 150L, false, null, GRANT_REASON, TS, "dev-operator", "PERSON", null, null, null));
        // HOLD caps t15.tier from the plan's "gold" down to "silver" — exercises the restriction
        // path as well as the raise path, in the same fixture set.
        holdOverrideId = accountOverrideRepository.insert(new AccountOverrideRow(null, accountId, tierCapId,
            "HOLD", null, null, false, "silver", HOLD_REASON, TS, "dev-operator", "PERSON", null, null, null));

        long auditSeq = auditEventRepository.insert(new AuditEventRow(null, TS, "PERSON", "dev-operator", "UI",
            "PLAN", "t15-plan", "CREATE", null, null, null, null, null, null, null));
        snapshotVersionRepository.insert(new SnapshotVersionRow(null, TS, auditSeq, "{}"));
        snapshotHolder.set(assembler.assembleFull());

        baseUrl = "http://localhost:" + port;
        http = HttpClient.newHttpClient();

        // Case 1's real value: if this throws, the SDK's engine disagrees with the service's own
        // conformance vectors, and every test below is correctly reported as an error rather than
        // silently skipped.
        client = EntitlementClient.builder()
            .serviceUrl(baseUrl)
            .pollInterval(Duration.ofMillis(200))
            .startupTimeout(Duration.ofSeconds(20))
            .build();
    }

    @AfterAll
    void closeTheClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void buildSucceedsAgainstTheLiveFeedBecauseTheConformanceGatePassedTheServicesOwnVectors() {
        // The gate already ran, uncaught, inside @BeforeAll — a failure there fails this whole
        // class. This assertion just confirms the resulting client is actually serving.
        assertThat(client).isNotNull();
        assertThat(client.health().snapshotVersion()).isPositive();
        assertThat(client.capabilities())
            .extracting(c -> c.key().value())
            .contains(CAP_SWITCH, CAP_QUANTITY, CAP_TIER);
    }

    @Test
    void everySeededCapabilityAgreesWithTheServicesOwnAnswerFromTheDecisionRoute() throws Exception {
        for (String capabilityKey : new String[] {CAP_SWITCH, CAP_QUANTITY, CAP_TIER}) {
            JsonNode serverAnswer = getJson("/v1/accounts/" + ACCOUNT + "/capabilities/" + capabilityKey);
            var decision = client.check(ACCOUNT, capabilityKey);

            assertThat(decision.allowed())
                .as("allowed for %s", capabilityKey)
                .isEqualTo(serverAnswer.get("allowed").asBoolean());
            assertThat(decision.snapshotVersion())
                .as("snapshotVersion for %s", capabilityKey)
                .isEqualTo(serverAnswer.get("snapshotVersion").asLong());
            assertThat(ValueMapper.toDto(decision.value()))
                .as("value for %s", capabilityKey)
                .isEqualTo(valueDtoFromJson(serverAnswer.get("value")));
        }
    }

    @Test
    void checkAllMatchesTheWholeAccountRouteCapabilityForCapabilityAndCoversExactlyTheNonRetiredOnes()
            throws Exception {
        JsonNode serverAnswer = getJson("/v1/accounts/" + ACCOUNT + "/entitlements");
        Map<String, JsonNode> serverByCapability = new HashMap<>();
        for (JsonNode entitlement : serverAnswer.get("entitlements")) {
            serverByCapability.put(entitlement.get("capability").asText(), entitlement);
        }

        var accountEntitlements = client.checkAll(ACCOUNT);
        Set<String> clientCapabilities = new HashSet<>();
        for (var decision : accountEntitlements.decisions()) {
            clientCapabilities.add(decision.capabilityKey());
            JsonNode serverEntitlement = serverByCapability.get(decision.capabilityKey());
            assertThat(serverEntitlement)
                .as("server has no entry for %s that the client resolved", decision.capabilityKey())
                .isNotNull();
            assertThat(decision.allowed()).isEqualTo(serverEntitlement.get("allowed").asBoolean());
            assertThat(ValueMapper.toDto(decision.value())).isEqualTo(valueDtoFromJson(serverEntitlement.get("value")));
        }

        assertThat(clientCapabilities).isEqualTo(serverByCapability.keySet());
        assertThat(clientCapabilities).doesNotContain(CAP_RETIRED);
    }

    @Test
    void aRetiredCapabilityRaisesRetiredCapabilityExceptionNotADenial() {
        assertThatThrownBy(() -> client.check(ACCOUNT, CAP_RETIRED))
            .isInstanceOf(RetiredCapabilityException.class);
    }

    @Test
    void anUnknownCapabilityRaisesUnknownCapabilityExceptionNotADenial() {
        assertThatThrownBy(() -> client.check(ACCOUNT, CAP_UNKNOWN))
            .isInstanceOf(UnknownCapabilityException.class);
    }

    @Test
    void explainCarriesTheSeededOverrideReasonTextTheReplicaDeliberatelyDoesNotHold() {
        var explanation = client.explain(ACCOUNT, CAP_QUANTITY);

        var grantEntry = explanation.trace().grants().stream()
            .filter(g -> g.overrideId().isPresent() && g.overrideId().getAsLong() == grantOverrideId)
            .findFirst();
        assertThat(grantEntry).as("the seeded GRANT candidate must appear in the trace").isPresent();
        assertThat(grantEntry.get().reason()).contains(GRANT_REASON);
        assertThat(grantEntry.get().value()).isEqualTo(EntitlementValue.Quantity.of(150));
    }

    @Test
    void theReplicaHoldsNoReasonTextAnywhereNotInTheOverrideIndexAndNotInTheFullSnapshotBody()
            throws Exception {
        // Sharper form first: an independent feed fetch (not the client under test — its overrides
        // are internal) parsed into a Replica, whose override index every caller could in principle
        // reach through the client's own transport, must carry an empty reason on every entry.
        try (var feed = new FeedHttpClient(URI.create(baseUrl), Duration.ofSeconds(5))) {
            Replica replica = feed.full();
            assertThat(replica.overridesByRef().values())
                .as("a replica's overrides must never carry reason text")
                .allSatisfy(override -> assertThat(override.reason()).isEmpty());
        }

        // Sharpest form: the raw gunzipped bytes of the full snapshot, searched as text. If a
        // reason string ever leaked onto the wire, this is what would catch it even if some future
        // parser silently dropped the field instead of refusing to.
        String gunzippedBody = fetchAndGunzip("/v1/snapshot/full");
        assertThat(gunzippedBody).doesNotContain(GRANT_REASON);
        assertThat(gunzippedBody).doesNotContain(HOLD_REASON);
    }

    @Test
    void aMutationThroughTheAdminApiIsVisibleAfterAwaitVersionReturnsTrueWithinTheFreshnessBudget()
            throws Exception {
        String requestBody = """
            {"capability":"%s","kind":"GRANT","value":{"type":"QUANTITY","amount":500},\
            "reason":"t15 mutation bump for the c28 freshness budget"}""".formatted(CAP_QUANTITY);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/admin/v1/accounts/" + ACCOUNT + "/overrides"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(10))
            .build();
        var response = http.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("admin override create: %s", response.body()).isEqualTo(201);
        long newVersion = MAPPER.readTree(response.body()).get("snapshotVersion").asLong();

        boolean caughtUp = client.awaitVersion(newVersion, Duration.ofSeconds(10));

        assertThat(caughtUp).as("replica must reach version %d within the freshness budget", newVersion).isTrue();
        var decision = client.check(ACCOUNT, CAP_QUANTITY);
        assertThat(decision.snapshotVersion()).isGreaterThanOrEqualTo(newVersion);
        assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(500));
    }

    @Test
    void aMinSnapshotVersionFarAheadOfCurrentThrowsSnapshotBehindRatherThanBlocking() {
        long currentVersion = client.health().snapshotVersion();

        assertThatThrownBy(() -> client.check(ACCOUNT, CAP_QUANTITY, currentVersion + 1000))
            .isInstanceOf(SnapshotBehindException.class)
            .satisfies(e -> assertThat(((SnapshotBehindException) e).requiredVersion())
                .isEqualTo(currentVersion + 1000));
    }

    private JsonNode getJson(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private String fetchAndGunzip(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.send(request, BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var gzip = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body()));
             var out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Mirrors {@link ValueMapper#toDto} from the wire side: a JSON value node back into a {@link ValueDto}. */
    private static ValueDto valueDtoFromJson(JsonNode value) {
        return new ValueDto(
            textOrNull(value, "type"),
            value.has("enabled") ? value.get("enabled").asBoolean() : null,
            value.has("amount") ? value.get("amount").asLong() : null,
            value.has("unlimited") ? value.get("unlimited").asBoolean() : null,
            textOrNull(value, "tier"),
            value.has("ordinal") ? value.get("ordinal").asInt() : null);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }
}
