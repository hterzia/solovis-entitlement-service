package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.time.ClockConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

/**
 * Replaces the ordinary {@code Clock} bean with {@link SeedClock} when seeding is enabled.
 *
 * <p>It decorates {@link ClockConfig#base(ZoneId)} rather than building its own clock. The zone is
 * load-bearing — it is what makes {@code LocalDate.now(clock)} the operator-facing date, and so what
 * every override window means — and {@code NoDirectClockAccessTest} exempts only
 * {@code ClockConfig.java} from reading the wall clock. A SeedClock over UTC would compile, pass its
 * own tests, and silently shift every window boundary by hours.
 */
@Configuration
@ConditionalOnProperty(name = "entitlement.seed.enabled", havingValue = "true")
public class SeedClockConfig {

    @Bean
    public SeedClock clock(ZoneId entitlementZone) {
        return new SeedClock(ClockConfig.base(entitlementZone));
    }
}
