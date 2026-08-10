package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.store.ServiceStateRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ordered explicitly: these two share the suite's one SQLite file, so the unseeded case has to be
 * asked before the other writes a marker into it.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedStateTest {

    @Autowired ServiceStateRepository repository;
    @Autowired java.time.Clock clock;

    @Test
    @Order(1)
    void anUnseededDatabaseReportsAbsent() {
        assertThat(new SeedState(repository, clock).status()).isEqualTo(SeedState.Status.ABSENT);
    }

    @Test
    @Order(2)
    void markingStartedThenCompletedMovesThroughBothStates() {
        var state = new SeedState(repository, clock);

        state.markStarted("v1:abc123");
        assertThat(state.status()).isEqualTo(SeedState.Status.STARTED);
        assertThat(state.startedFingerprint()).contains("v1:abc123");

        state.markCompleted("v1:abc123");
        assertThat(state.status()).isEqualTo(SeedState.Status.COMPLETED);
    }
}
