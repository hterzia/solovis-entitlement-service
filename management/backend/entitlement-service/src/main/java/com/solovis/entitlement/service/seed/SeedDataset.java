package com.solovis.entitlement.service.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.service.dto.ValueDto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The authored demo dataset, checked whole before a single row is written.
 *
 * <p>Validation is not decoration. The applier drives the real admin services, so a dangling
 * reference would surface as a half-written database and a failed startup rather than a parse
 * error — and a demo that will not boot because of a typo in a name is a poor trade. Everything
 * checkable without touching the database is checked here first.
 *
 * <p>Days are relative to the start of the timeline, never absolute dates, so the demo is always
 * "the last eight months" whenever the container boots rather than a system nobody has touched
 * since last year. Window bounds are days too, and may run past {@code timelineDays} — that is how
 * an override still in force, or not yet begun, is expressed.
 */
public record SeedDataset(
    int seedVersion,
    int timelineDays,
    List<Capability> capabilities,
    List<Plan> plans,
    List<Account> accounts,
    List<Event> events,
    String fingerprint
) {

    public record Tier(String tier, String displayName) {}

    public record Capability(int day, String key, String displayName, String description, String valueType,
        ValueDto defaultValue, ValueDto offValue, List<Tier> tiers) {}

    public record Plan(int day, String key, String name, String description, boolean isDefault,
        Map<String, ValueDto> entitlements) {}

    public record Account(int day, String externalId, String name, String plan) {}

    /**
     * Window bounds are timeline day numbers, not dates — like every other date in this file. The
     * applier turns them into service-zone dates relative to the day the override is written.
     */
    public record Event(int day, String type, String account, String capability, String kind, ValueDto value,
        String reason, String ref, String plan, String key, Integer startsOnDay, Integer expiresOnDay) {}

    public static final String OVERRIDE_CREATE = "override.create";
    public static final String OVERRIDE_REMOVE = "override.remove";
    public static final String PLAN_REASSIGN = "plan.reassign";
    public static final String CAPABILITY_RETIRE = "capability.retire";

    public static SeedDataset of(byte[] raw, ObjectMapper mapper) {
        try {
            SeedDataset parsed = mapper.readValue(raw, SeedDataset.class);
            return new SeedDataset(parsed.seedVersion(), parsed.timelineDays(), parsed.capabilities(),
                parsed.plans(), parsed.accounts(), parsed.events(),
                "v" + parsed.seedVersion() + ":" + sha256(raw));
        } catch (IOException e) {
            throw new UncheckedIOException("demo seed dataset is not readable", e);
        }
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM this runs on", e);
        }
    }

    public void validate() {
        Map<String, Integer> capabilityDays = new LinkedHashMap<>();
        for (Capability capability : capabilities) {
            require(capabilityDays.put(capability.key(), capability.day()) == null,
                "duplicate capability '" + capability.key() + "'");
            requireDay(capability.day(), "capability '" + capability.key() + "'");
        }

        Map<String, Integer> planDays = new LinkedHashMap<>();
        int defaults = 0;
        for (Plan plan : plans) {
            require(planDays.put(plan.key(), plan.day()) == null, "duplicate plan '" + plan.key() + "'");
            requireDay(plan.day(), "plan '" + plan.key() + "'");
            if (plan.isDefault()) {
                defaults++;
            }
            for (String key : plan.entitlements().keySet()) {
                requireDeclaredBy(capabilityDays, key, plan.day(), "plan '" + plan.key() + "' entitles");
            }
        }
        require(defaults == 1, "the dataset must declare exactly one default plan, found " + defaults);

        Map<String, Integer> accountDays = new LinkedHashMap<>();
        for (Account account : accounts) {
            require(accountDays.put(account.externalId(), account.day()) == null,
                "duplicate account '" + account.externalId() + "'");
            requireDay(account.day(), "account '" + account.externalId() + "'");
            requireDeclaredBy(planDays, account.plan(), account.day(),
                "account '" + account.externalId() + "' joins");
        }

        Set<String> liveRefs = new LinkedHashSet<>();
        int previousDay = 0;
        for (Event event : events) {
            requireDay(event.day(), "event '" + event.type() + "'");
            // seq order and occurred_at order must agree in audit_event, or a point-in-time question
            // silently returns today's answer. The applier writes in list order, so list order is it.
            require(event.day() >= previousDay, "events must be in day order; '" + event.type()
                + "' on day " + event.day() + " follows day " + previousDay);
            previousDay = event.day();
            validateWindow(event);

            switch (event.type()) {
                case OVERRIDE_CREATE -> {
                    requireDeclaredBy(accountDays, event.account(), event.day(), "override targets");
                    requireDeclaredBy(capabilityDays, event.capability(), event.day(), "override targets");
                    require(event.ref() != null && liveRefs.add(event.ref()),
                        "override ref '" + event.ref() + "' is missing or already in use");
                    require("GRANT".equals(event.kind()) || "HOLD".equals(event.kind()),
                        "override '" + event.ref() + "' must be GRANT or HOLD, was '" + event.kind() + "'");
                    require(event.reason() != null && !event.reason().isBlank(),
                        "override '" + event.ref() + "' needs a reason");
                }
                case OVERRIDE_REMOVE -> require(liveRefs.remove(event.ref()),
                    "override '" + event.ref() + "' is removed but was never created before day " + event.day());
                case PLAN_REASSIGN -> {
                    requireDeclaredBy(accountDays, event.account(), event.day(), "reassignment targets");
                    requireDeclaredBy(planDays, event.plan(), event.day(), "reassignment moves to");
                }
                case CAPABILITY_RETIRE ->
                    requireDeclaredBy(capabilityDays, event.key(), event.day(), "retirement targets");
                default -> throw new IllegalStateException("unknown seed event type '" + event.type() + "'");
            }
        }
    }

    /**
     * The same three refusals {@code WindowRules} applies, checked against the authored day rather
     * than the real one — because the clock is wound to that day when the write happens. Doing it
     * here turns a failed startup into a failed build.
     */
    private void validateWindow(Event event) {
        if (event.startsOnDay() != null) {
            require(event.startsOnDay() >= event.day(), "override '" + event.ref() + "' starts on day "
                + event.startsOnDay() + " but is written on day " + event.day()
                + "; a window cannot begin before the moment it is saved");
        }
        if (event.expiresOnDay() != null) {
            require(event.expiresOnDay() >= event.day(), "override '" + event.ref() + "' expires on day "
                + event.expiresOnDay() + " but is written on day " + event.day()
                + "; a window already ended cannot be saved");
        }
        if (event.startsOnDay() != null && event.expiresOnDay() != null) {
            require(event.startsOnDay() <= event.expiresOnDay(), "override '" + event.ref() + "' starts on day "
                + event.startsOnDay() + " and expires on day " + event.expiresOnDay()
                + ", which describes nothing");
        }
    }

    private void requireDay(int day, String what) {
        require(day >= 0 && day <= timelineDays,
            what + " is on day " + day + ", outside the timeline of " + timelineDays + " days");
    }

    private void requireDeclaredBy(Map<String, Integer> declared, String key, int day, String what) {
        Integer declaredOn = declared.get(key);
        require(declaredOn != null, what + " '" + key + "', which the dataset never declares");
        require(declaredOn <= day, what + " '" + key + "' on day " + day
            + ", but it is not declared until day " + declaredOn);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("demo seed dataset is invalid: " + message);
        }
    }
}
