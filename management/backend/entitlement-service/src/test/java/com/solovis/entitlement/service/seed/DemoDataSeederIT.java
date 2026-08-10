package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.AsAtCheckService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole demo seed against a real service. Until this existed, the seeder's writing path was
 * proved only by the end-to-end suite and by the deployment itself.
 *
 * <p>Its own context and its own database file: the shared test context runs with seeding off, and
 * the seed only runs at all against an empty database. Assertions go through {@link AsAtCheckService},
 * the real read path — 002 removed the in-memory snapshot the service used to answer from.
 */
@SpringBootTest(properties = {
    "entitlement.seed.enabled=true",
    "entitlement.database.path=${java.io.tmpdir}/entitlement-seed-it-${random.uuid}.db"
})
class DemoDataSeederIT {

    @Autowired CapabilityAdminService capabilityService;
    @Autowired PlanAdminService planService;
    @Autowired AccountAdminService accountService;
    @Autowired AsAtCheckService asAtCheckService;
    @Autowired AuditEventRepository auditEvents;
    @Autowired Clock clock;

    private String valueOf(String account, String capability) {
        return asAtCheckService.check(account, capability, LocalDate.now(clock)).decision().value().toString();
    }

    @Test
    void itSeedsTheWholeCatalogue() {
        assertThat(capabilityService.list(null, "ACTIVE", null)).hasSize(15);
        assertThat(capabilityService.list(null, "RETIRED", null)).hasSize(1);
        assertThat(planService.list()).hasSize(5);
        assertThat(accountService.search(null, null, 0, 200).accounts()).hasSizeGreaterThan(55);
    }

    @Test
    void theEndToEndFixturesResolveExactlyAsTheSuiteExpects() {
        assertThat(valueOf("acct_9931", "reports.monthly")).contains("200");
    }

    @Test
    void aHoldDefeatsAGrant() {
        // acct_2384 holds api.access down while a GRANT raises it: a restriction defeats a concession.
        assertThat(asAtCheckService.check("acct_2384", "api.access", LocalDate.now(clock)).decision().allowed())
            .isFalse();
    }

    @Test
    void anEnterpriseAccountResolvesUnlimited() {
        assertThat(valueOf("acct_2043", "reports.monthly").toLowerCase()).contains("unlimited");
    }

    @Test
    void allFourStandingsAreOnTheWindowsFlagship() {
        // The point of the seed clock: ENDED cannot be produced by a caller standing in the present,
        // because c7 refuses a window that has already ended.
        assertThat(accountService.get("acct_2947").overrides())
            .extracting(o -> o.standing())
            .contains("ENDED", "IN_FORCE", "PENDING", "REMOVED");
    }

    @Test
    void aPendingOverrideTakesNoPartInTodaysAnswer() {
        // acct_2947's pending GRANT of 40 portfolios has not begun; `core` entitles 5.
        assertThat(valueOf("acct_2947", "portfolio.count")).contains("5");
    }

    @Test
    void theHistorySpansMonthsAndEndsAtThePresent() {
        var events = auditEvents.find(new AuditEventFilter(null, null, null, null, null, null, null, null, 1000));

        assertThat(events).hasSizeGreaterThan(100);
        var timestamps = events.stream().map(e -> Instant.parse(e.occurredAt())).sorted().toList();
        Instant oldest = timestamps.get(0);
        Instant newest = timestamps.get(timestamps.size() - 1);

        assertThat(Duration.between(oldest, newest).toDays())
            .as("the seeded history must span the authored timeline, not one boot second")
            .isGreaterThan(180);
        assertThat(Duration.between(newest, Instant.now()).toMinutes())
            .as("the last authored event must land at the present, or every replica sees a stale snapshot")
            .isLessThan(10);
    }

    @Test
    void theAuditTrailIsInTimeOrder() {
        // seq order and occurred_at order must agree, or a point-in-time question resolves a date to
        // the wrong seq and silently returns today's answer.
        var events = auditEvents.find(new AuditEventFilter(null, null, null, null, null, null, null, null, 1000));
        var bySeqAscending = events.stream()
            .sorted(java.util.Comparator.comparing(e -> e.seq()))
            .map(e -> e.occurredAt())
            .toList();

        assertThat(bySeqAscending).isSorted();
    }

    @Test
    void theClockIsRealTimeOnceSeedingHasFinished() {
        assertThat(clock).isInstanceOf(SeedClock.class);
        assertThat(((SeedClock) clock).isWound()).isFalse();
        assertThat(Duration.between(clock.instant(), Instant.now()).abs().toSeconds()).isLessThan(5);
    }
}
