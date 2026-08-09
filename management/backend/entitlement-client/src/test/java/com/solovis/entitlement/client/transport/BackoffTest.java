package com.solovis.entitlement.client.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    /** No jitter, so the ladder itself is what is under test. */
    private static Backoff noJitter(Duration base) {
        return new Backoff(base, millis -> millis);
    }

    @Test
    void theLadderClimbsFiveTenThirtySixtySecondsFromAFiveSecondPoll() {
        var backoff = noJitter(Duration.ofSeconds(5));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void theLadderHoldsAtItsTopRatherThanClimbingForever() {
        var backoff = noJitter(Duration.ofSeconds(5));
        for (int i = 0; i < 4; i++) {
            backoff.nextDelay();
        }

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aSuccessfulSyncResetsTheLadderToItsFirstRung() {
        var backoff = noJitter(Duration.ofSeconds(5));
        backoff.nextDelay();
        backoff.nextDelay();

        backoff.reset();

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void theLadderScalesWithTheConfiguredPollIntervalRatherThanHardCodingSeconds() {
        var backoff = noJitter(Duration.ofSeconds(1));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void jitterIsAppliedToEveryRungSoAFleetOfReplicasDoesNotRetryInLockstep() {
        var backoff = new Backoff(Duration.ofSeconds(5), millis -> millis / 2);

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofSeconds(2).plusMillis(500));
    }

    @Test
    void theDefaultConstructorJittersWithinTwentyPercentOfEachRung() {
        var backoff = new Backoff(Duration.ofSeconds(5));

        var first = backoff.nextDelay();

        assertThat(first).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
    }
}
