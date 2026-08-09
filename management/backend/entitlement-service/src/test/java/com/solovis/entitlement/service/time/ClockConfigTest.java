package com.solovis.entitlement.service.time;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ClockConfigTest {

    @Autowired Clock clock;

    @Test
    void instantIsTruncatedToMillisecondPrecision() {
        assertThat(clock.instant().getNano() % 1_000_000).isZero();
    }
}
