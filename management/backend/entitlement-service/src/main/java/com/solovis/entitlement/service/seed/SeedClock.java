package com.solovis.entitlement.service.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The service's {@link Clock}, wound to an authored moment while the demo seed is written and
 * released to real time the instant it finishes.
 *
 * <p>It exists because a demo is judged partly on its change history, and every audit row is stamped
 * from this bean ({@code AuditRecorder}). Applied at real time the whole seed shares one timestamp,
 * and the history screen advertises the dataset as generated more loudly than any amount of volume
 * would fix. Wound, each write lands on the day the story says it happened.
 *
 * <p>It also buys something the API alone cannot give: 002 c7 refuses a window that has already
 * ended, so a seeder standing in the present cannot manufacture an {@code ENDED} override at all.
 * Wound to the authored day, that same override is an ordinary, fully validated admin write.
 *
 * <p>Deliberately not a general-purpose test seam. It is contributed only when
 * {@code entitlement.seed.enabled=true}, wound only from {@link SeedApplier}, and released by
 * {@code DemoDataSeeder} in a {@code finally} during context refresh — before the web connector
 * opens and before {@code @Scheduled} tasks start, so neither a request nor
 * {@code WindowBoundaryRoller} can ever observe a wound clock.
 */
public final class SeedClock extends Clock {

    private final Clock delegate;
    private volatile Instant wound;

    public SeedClock(Clock delegate) {
        this.delegate = delegate;
    }

    public void windTo(Instant instant) {
        this.wound = instant;
    }

    public void release() {
        this.wound = null;
    }

    public boolean isWound() {
        return wound != null;
    }

    /**
     * The delegate's zone, wound or not. This is what makes {@code LocalDate.now(clock)} the
     * operator-facing date, and therefore what every override window means.
     */
    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        Instant current = wound;
        return current != null ? current : delegate.instant();
    }
}
