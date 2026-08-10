package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanEntitlementEditRequest;
import com.solovis.entitlement.service.admin.dto.PlanReassignRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.audit.AuditSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks an authored dataset in day order, driving the same admin services the admin API drives.
 *
 * <p>Going through the services rather than the repositories is the property this seeder is built
 * on: it cannot declare data the validation rules reject, every write records a real audit event,
 * and every write publishes exactly as an operator's would.
 *
 * <p>The clock is wound to each authored day before the write, so the demo has a history instead of
 * a boot second — and so an override whose window has already ended becomes an ordinary, fully
 * validated write rather than something the API refuses. {@link DemoDataSeeder} owns the
 * {@code finally} that releases it.
 *
 * <p>Writing in day order is not presentation. {@code seq} order and {@code occurred_at} order must
 * agree in {@code audit_event}, or a point-in-time question resolves a date to the wrong {@code seq}
 * and silently returns today's answer.
 */
public class SeedApplier {

    /** The last authored moment lands before now, so the served snapshot is never stale to a replica. */
    private static final Duration LATEST_MARGIN = Duration.ofMinutes(1);

    public record Summary(int capabilities, int plans, int accounts, int overrides, int writes,
        Instant firstEvent, Instant lastEvent) {}

    private final CapabilityAdminService capabilityService;
    private final PlanAdminService planService;
    private final AccountAdminService accountService;
    private final OverrideAdminService overrideService;
    private final AuditSource auditSource;
    private final SeedClock clock;

    public SeedApplier(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService, AuditSource auditSource,
            SeedClock clock) {
        this.capabilityService = capabilityService;
        this.planService = planService;
        this.accountService = accountService;
        this.overrideService = overrideService;
        this.auditSource = auditSource;
        this.clock = clock;
    }

    /** One write, and the day the story says it happened on. */
    private record Step(int day, int kind, Runnable action) {}

    // Tie-break within a day: a capability must exist before a plan entitles it, a plan before an
    // account joins it, an account before an override targets it.
    private static final int CAPABILITY = 0;
    private static final int PLAN = 1;
    private static final int ACCOUNT = 2;
    private static final int EVENT = 3;

    public Summary apply(SeedDataset dataset) {
        Instant realNow = clock.instant();
        Instant latest = realNow.minus(LATEST_MARGIN);
        Instant start = realNow.minus(Duration.ofDays(dataset.timelineDays())).truncatedTo(ChronoUnit.DAYS);
        String defaultPlan = defaultPlanKey(dataset);

        var overrideIds = new HashMap<String, String>();
        var overrideAccounts = new HashMap<String, String>();
        var steps = new ArrayList<Step>();

        for (var capability : dataset.capabilities()) {
            steps.add(new Step(capability.day(), CAPABILITY, () ->
                capabilityService.create(new CapabilityCreateRequest(capability.key(), capability.displayName(),
                    capability.description(), capability.valueType(), capability.defaultValue(),
                    capability.offValue(), tiers(capability)))));
        }

        for (var plan : dataset.plans()) {
            steps.add(new Step(plan.day(), PLAN, () ->
                planService.create(new PlanCreateRequest(plan.key(), plan.name(), plan.description()))));
            if (plan.isDefault()) {
                steps.add(new Step(plan.day(), PLAN, () -> planService.designateDefault(plan.key())));
            }
            if (!plan.entitlements().isEmpty()) {
                steps.add(new Step(plan.day(), PLAN, () -> {
                    // preview then apply, exactly as the console does: apply recomputes the token and
                    // refuses anything previewed against a different snapshot version.
                    var edit = new PlanEntitlementEditRequest(plan.entitlements(), List.of(), null, null);
                    String token = planService.preview(plan.key(), edit).previewToken();
                    planService.apply(plan.key(),
                        new PlanEntitlementEditRequest(plan.entitlements(), List.of(), null, token));
                }));
            }
        }

        for (var account : dataset.accounts()) {
            steps.add(new Step(account.day(), ACCOUNT, () ->
                accountService.create(new AccountCreateRequest(account.externalId(), account.name()))));
            // create always assigns the designated default plan, so anything else is a reassignment —
            // which is also how a real account reaches a paid plan, and reads that way in the history.
            if (!account.plan().equals(defaultPlan)) {
                steps.add(new Step(account.day(), ACCOUNT, () ->
                    accountService.reassignPlan(account.externalId(), new PlanReassignRequest(
                        account.plan(), "PERSON", "dev-operator", "Initial plan on signup"))));
            }
        }

        for (var event : dataset.events()) {
            steps.add(new Step(event.day(), EVENT, () -> {
                switch (event.type()) {
                    case SeedDataset.OVERRIDE_CREATE -> {
                        // The clock is already wound to the authored day, so "today" here *is* that
                        // day — which is also the date WindowRules validates against. Deriving the
                        // window from it keeps the two in step whatever the zone does to an instant.
                        LocalDate authoredToday = LocalDate.now(clock);
                        var created = overrideService.create(event.account(), new OverrideCreateRequest(
                            event.capability(), event.kind(), event.value(), event.reason(),
                            windowDate(authoredToday, event.day(), event.startsOnDay()),
                            windowDate(authoredToday, event.day(), event.expiresOnDay())));
                        overrideIds.put(event.ref(), created.overrideId());
                        overrideAccounts.put(event.ref(), event.account());
                    }
                    case SeedDataset.OVERRIDE_REMOVE -> overrideService.delete(
                        overrideAccounts.get(event.ref()), overrideIds.get(event.ref()), event.reason());
                    case SeedDataset.PLAN_REASSIGN -> accountService.reassignPlan(event.account(),
                        new PlanReassignRequest(event.plan(), "PERSON", "dev-operator", event.reason()));
                    case SeedDataset.CAPABILITY_RETIRE -> capabilityService.retire(event.key());
                    default -> throw new IllegalStateException("unknown seed event type '" + event.type() + "'");
                }
            }));
        }

        // One chronological pass, not four section-major ones. seq order and occurred_at order must
        // agree in audit_event: a row with an early occurred_at and a high seq makes a point-in-time
        // question resolve to the wrong seq and silently return today's answer. Sorting is stable, so
        // declaration order still breaks ties inside a (day, kind).
        steps.sort(java.util.Comparator.comparingInt(Step::day).thenComparingInt(Step::kind));

        var moments = new ArrayList<Instant>(steps.size());
        auditSource.runAs("SEED", () -> {
            for (Step step : steps) {
                moments.add(at(start, step.day(), dataset.timelineDays(), latest));
                step.action().run();
            }
        });

        int overrides = (int) dataset.events().stream()
            .filter(e -> SeedDataset.OVERRIDE_CREATE.equals(e.type())).count();
        return new Summary(dataset.capabilities().size(), dataset.plans().size(), dataset.accounts().size(),
            overrides, steps.size(), moments.isEmpty() ? realNow : moments.get(0),
            moments.isEmpty() ? realNow : moments.get(moments.size() - 1));
    }

    private static List<CapabilityCreateRequest.TierRequest> tiers(SeedDataset.Capability capability) {
        return capability.tiers() == null ? List.of() : capability.tiers().stream()
            .map(t -> new CapabilityCreateRequest.TierRequest(t.tier(), t.displayName())).toList();
    }

    /**
     * A timeline day as the service-zone date the admin API expects, relative to the day the
     * override is written. Null stays null — an override with no window, still the ordinary case.
     */
    private static String windowDate(LocalDate authoredToday, int authoredDay, Integer windowDay) {
        return windowDay == null ? null : authoredToday.plusDays((long) windowDay - authoredDay).toString();
    }

    private static String defaultPlanKey(SeedDataset dataset) {
        return dataset.plans().stream().filter(SeedDataset.Plan::isDefault).findFirst()
            .orElseThrow(() -> new IllegalStateException("validated datasets always have a default plan"))
            .key();
    }

    private Instant lastMoment = Instant.EPOCH;
    private int lastDay = -1;
    private int withinDay;

    /**
     * Winds the clock to the authored day, spreading a busy day's writes through working hours so it
     * does not read as one instant.
     *
     * <p>Strictly non-decreasing, which is the point: {@code occurred_at} order has to agree with
     * {@code seq} order. The per-day counter is capped so a very busy day cannot walk past midnight
     * into the next one — that would make {@code LocalDate.now(clock)} disagree with the authored
     * day, and the window dates are derived from it.
     */
    private Instant at(Instant start, int day, int timelineDays, Instant latest) {
        if (day != lastDay) {
            lastDay = day;
            withinDay = 0;
        }
        // The last day of the timeline *is* today, and today's writes should read as having just
        // happened rather than at nine this morning. This is not cosmetic: the feed reads the current
        // snapshot_version's publishedAt on every poll, and the replica projection evaluates window
        // standings before publication — a final version stamped hours ago is a stale feed.
        Instant moment = day >= timelineDays ? latest
            : start.plus(Duration.ofDays(day))
                .plus(Duration.ofHours(9))
                .plus(Duration.ofMinutes(7L * Math.min(withinDay++, 60)));
        if (!moment.isAfter(lastMoment)) {
            moment = lastMoment.plusMillis(1);
        }
        if (moment.isAfter(latest)) {
            moment = latest;
        }
        lastMoment = moment;
        clock.windTo(moment);
        return moment;
    }
}
