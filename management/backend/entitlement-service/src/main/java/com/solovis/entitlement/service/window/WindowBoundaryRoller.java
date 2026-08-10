package com.solovis.entitlement.service.window;

import com.solovis.entitlement.service.audit.Actor;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.snapshot.DeltaChange;
import com.solovis.entitlement.service.snapshot.RowMappers;
import com.solovis.entitlement.service.snapshot.SnapshotPublisher;
import com.solovis.entitlement.service.snapshot.ValueColumnCodec;
import com.solovis.entitlement.service.store.AccountOverrideRepository;
import com.solovis.entitlement.service.store.AccountOverrideRow;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.time.Timestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Tells replicas that a window opened or closed, and records that it happened (002 c11–c13, c30).
 *
 * <h2>It is a publication component, not a correctness one</h2>
 *
 * The management service evaluates windows as a SQL predicate at the moment of asking, so its own
 * answers begin and end at exactly the right instant whether or not this ever runs. What this adds
 * is the half a replica cannot do for itself: a replica holds no windows and no clock that matters,
 * because c14 requires a product cut off from the service to go on honouring an override that has
 * ended. So a boundary reaches products as an ordinary {@code override.created} or
 * {@code override.removed} — the delta kinds that already exist — and if this job runs late,
 * replicas lag while the operator tool stays right.
 *
 * <h2>Why a cron with a zone rather than arithmetic</h2>
 *
 * A day is not always 86,400 seconds. Adding {@code Duration.ofDays(1)} to the last fire drifts an
 * hour on each of the two daylight-saving days and eventually fires on the wrong side of a
 * boundary. Spring's cron trigger computes its next execution <em>in the configured zone</em>, so
 * "midnight" stays midnight across both transitions. Eastern's transitions fall at 02:00, so
 * midnight is never a skipped or doubled hour — {@code ServiceZone} asserts exactly that at boot.
 *
 * <h2>Idempotence and catch-up are the same mechanism</h2>
 *
 * The last date fully rolled is kept in {@code service_state}. Every run rolls forward from there
 * to today, one boundary at a time, and a run with nothing to do does nothing. That makes running
 * twice in a minute harmless — which the safety interval guarantees will happen — and it makes
 * catch-up after an outage a normal path rather than an edge case. Cloud Run replaces instances
 * for revisions and platform maintenance, so being down across a midnight needs no deliberate act.
 *
 * <p>Deliberately day-by-day rather than by net difference. An override that began and ended while
 * the service was down really did both, and c30 asks for both in the history; publishing the pair
 * leaves a replica in the same place a net calculation would, by a route that matches the record.
 */
@Component
public class WindowBoundaryRoller {

    private static final Logger log = LoggerFactory.getLogger(WindowBoundaryRoller.class);

    /** The last date whose opening midnight has been fully rolled. */
    public static final String ROLLED_THROUGH = "window.rolledThrough";

    private static final Actor CLOCK = new Actor("clock", Actor.Kind.SYSTEM);

    /**
     * A catch-up ceiling. Rolling forward from a {@code rolledThrough} that is years stale would
     * emit one transaction per day for no benefit — every one of those overrides is already in its
     * final state, and the full-resync path exists for a replica that far behind. Bounded so a
     * restored-from-backup database cannot turn startup into an unbounded write storm.
     */
    private static final int MAX_CATCH_UP_DAYS = 90;

    private final AccountOverrideRepository accountOverrideRepository;
    private final AccountRepository accountRepository;
    private final CapabilityRepository capabilityRepository;
    private final ServiceStateRepository serviceStateRepository;
    private final AuditRecorder auditRecorder;
    private final SnapshotPublisher snapshotPublisher;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;

    public WindowBoundaryRoller(AccountOverrideRepository accountOverrideRepository,
            AccountRepository accountRepository, CapabilityRepository capabilityRepository,
            ServiceStateRepository serviceStateRepository, AuditRecorder auditRecorder,
            SnapshotPublisher snapshotPublisher,
            @Qualifier("entitlementTransactionManager") PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${entitlement.window.roll-enabled:true}") boolean enabled) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accountOverrideRepository = accountOverrideRepository;
        this.accountRepository = accountRepository;
        this.capabilityRepository = capabilityRepository;
        this.serviceStateRepository = serviceStateRepository;
        this.auditRecorder = auditRecorder;
        this.snapshotPublisher = snapshotPublisher;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** Punctuality: fires at midnight in the service zone, so a boundary is published within seconds. */
    @Scheduled(cron = "${entitlement.window.roll-cron:0 0 0 * * *}", zone = "${entitlement.clock.zone}")
    public void rollAtMidnight() {
        rollSafely();
    }

    /**
     * The safety net, and the startup catch-up. A missed midnight — a throttled instance, a deploy
     * that spanned it — is caught here well inside c13's sixty seconds rather than at the next one.
     */
    @Scheduled(fixedDelayString = "${entitlement.window.roll-interval:30s}", initialDelay = 0)
    public void rollOnSafetyInterval() {
        rollSafely();
    }

    private void rollSafely() {
        if (!enabled) {
            return;
        }
        try {
            roll();
        } catch (RuntimeException e) {
            // A failed roll must never take the service down: its own answers are already correct
            // without it, and the next interval retries. It does mean replicas are lagging, which
            // is what the log line is for.
            log.warn("Window boundary roll failed; replicas may lag until the next attempt.", e);
        }
    }

    /**
     * Rolls every boundary from the last recorded one through today, and returns how many
     * transitions were published. Separate from the scheduled entry points so a test — or an
     * operator — can drive it without waiting for a timer.
     */
    public int roll() {
        LocalDate today = LocalDate.now(clock);
        LocalDate rolledThrough = serviceStateRepository.find(ROLLED_THROUGH)
            .map(LocalDate::parse)
            // First run on this database: adopt today without emitting anything. Every override
            // that exists is already in whatever state it should be, and manufacturing a history of
            // boundaries nobody observed would put fictional moments in the feed.
            .orElseGet(() -> { recordRolledThrough(today); return today; });

        if (!rolledThrough.isBefore(today)) {
            return 0;
        }

        LocalDate from = rolledThrough.plusDays(1);
        if (from.isBefore(today.minusDays(MAX_CATCH_UP_DAYS))) {
            log.warn("Window roll is {} days behind; skipping to {} and leaving far-behind replicas to full resync.",
                today.toEpochDay() - rolledThrough.toEpochDay(), today.minusDays(MAX_CATCH_UP_DAYS));
            from = today.minusDays(MAX_CATCH_UP_DAYS);
        }

        int published = 0;
        for (LocalDate boundary = from; !boundary.isAfter(today); boundary = boundary.plusDays(1)) {
            published += rollOneBoundary(boundary);
        }
        if (published > 0) {
            log.info("Window roll published {} transitions through {}.", published, today);
        }
        return published;
    }

    /**
     * One midnight, one transaction. A night's boundaries either all land or none do, so no replica
     * can see a half-rolled midnight — and {@code rolledThrough} advances with them, which is what
     * makes a re-run a no-op rather than a duplicate.
     *
     * <p>Two publishes in one transaction is fine and was not always: the version is the
     * {@code snapshot_version} row's own autoincrement key, so each insert gets its own number and
     * they commit together.
     *
     * <p>The transaction is opened explicitly rather than with {@code @Transactional}, because
     * {@link #roll()} calls this method on {@code this}. A self-invocation never passes through the
     * proxy, so the annotation would be silently inert and {@code SnapshotPublisher}'s
     * "must be called inside a transaction" guard would be the only thing that noticed.
     */
    public int rollOneBoundary(LocalDate boundary) {
        return transactions.execute(status -> rollOneBoundaryInTransaction(boundary));
    }

    private int rollOneBoundaryInTransaction(LocalDate boundary) {
        int published = 0;
        for (var row : accountOverrideRepository.findStartingOn(boundary)) {
            publishTransition(row, boundary, true);
            published++;
        }
        for (var row : accountOverrideRepository.findExpiringAtStartOf(boundary)) {
            publishTransition(row, boundary, false);
            published++;
        }
        recordRolledThrough(boundary);
        return published;
    }

    private void publishTransition(AccountOverrideRow row, LocalDate boundary, boolean beginning) {
        var accountRow = accountRepository.findById(row.accountId()).orElseThrow();
        var capRow = capabilityRepository.findById(row.capabilityId()).orElseThrow();
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        String ref = "ovr_" + row.id();

        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(CLOCK)
            .source("CLOCK")
            .entityType("OVERRIDE")
            .entityId(ref)
            .action(beginning ? "BEGIN" : "END")
            .windowTransition(beginning ? "START" : "EXPIRY")
            .accountId(row.accountId())
            .capabilityId(row.capabilityId())
            .reason(beginning
                ? "Began on " + boundary + ", as dated when it was created."
                : "Ended after " + boundary.minusDays(1) + ", as dated when it was created.")
            .build());

        // To a replica a beginning is a creation and an ending is a removal: it holds only what
        // counts, so "started existing for me" and "stopped existing for me" is the whole of what
        // it needs. That is why 002 adds no delta kind and does not bump resolverContract.
        DeltaChange delta;
        if (beginning) {
            var value = ValueColumnCodec.toValue(capability.valueType(), row.boolValue(), row.qtyValue(),
                row.qtyUnlimited(), row.tierValue(), capability.tierOrder());
            delta = new DeltaChange.OverrideCreated(ref, accountRow.externalId(), capability.key().value(),
                row.kind(), ValueMapper.toDto(value));
        } else {
            delta = new DeltaChange.OverrideRemoved(ref);
        }
        snapshotPublisher.publish(auditSeq, delta);
    }

    private void recordRolledThrough(LocalDate date) {
        serviceStateRepository.put(ROLLED_THROUGH, date.toString(), Timestamps.iso(clock.instant()));
    }
}
