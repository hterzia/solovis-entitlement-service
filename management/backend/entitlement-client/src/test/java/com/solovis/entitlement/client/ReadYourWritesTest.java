package com.solovis.entitlement.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.client.error.SnapshotBehindException;
import com.solovis.entitlement.client.metrics.ClientMetrics;
import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadYourWritesTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-09T14:05:00.000Z"), ZoneOffset.UTC);

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

    private AtomicReference<Replica> holder;
    private DefaultEntitlementClient client;

    @BeforeEach
    void setUp() {
        holder = new AtomicReference<>(FullSnapshotReader.read(
            new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8))));
        client = DefaultEntitlementClient.forTesting(holder, CLOCK, ClientMetrics.NO_OP);
    }

    @Test
    void aVersionTheReplicaHasReachedIsAnsweredNormally() {
        var decision = client.check("acct_9931", "api.access", 48211L);

        assertThat(decision.snapshotVersion()).isEqualTo(48211L);
    }

    @Test
    void aVersionBelowTheReplicasIsAlsoFineBecauseTheReplicaIsAheadOfWhatWasAskedFor() {
        assertThat(client.check("acct_9931", "api.access", 48000L).snapshotVersion())
            .isEqualTo(48211L);
    }

    @Test
    void aVersionTheReplicaHasNotReachedThrowsRatherThanBlockingOnTheCallersBehalf() {
        assertThatThrownBy(() -> client.check("acct_9931", "api.access", 48999L))
            .isInstanceOf(SnapshotBehindException.class)
            .satisfies(e -> {
                assertThat(((SnapshotBehindException) e).requiredVersion()).isEqualTo(48999L);
                assertThat(((SnapshotBehindException) e).currentVersion()).isEqualTo(48211L);
            });
    }

    @Test
    void theVersionIsCheckedBeforeResolutionSoAnUnknownCapabilityDoesNotMaskAStaleReplica() {
        assertThatThrownBy(() -> client.check("acct_9931", "no.such.capability", 48999L))
            .isInstanceOf(SnapshotBehindException.class);
    }

    @Test
    void awaitVersionReturnsImmediatelyWhenTheReplicaIsAlreadyThere() {
        assertThat(client.awaitVersion(48211L, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void awaitVersionReturnsFalseRatherThanThrowingWhenTheTimeoutElapses() {
        assertThat(client.awaitVersion(48999L, Duration.ofMillis(50))).isFalse();
    }

    @Test
    void awaitVersionReturnsTrueOnceAnotherThreadSwapsInTheVersion() throws Exception {
        var swapper = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            holder.set(FullSnapshotReader.read(new ByteArrayInputStream(
                FEED.replace("48211", "48999").getBytes(StandardCharsets.UTF_8))));
        });
        swapper.start();

        assertThat(client.awaitVersion(48999L, Duration.ofSeconds(5))).isTrue();

        swapper.join();
    }
}
