package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.ValueDto;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.view.Snapshot;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link Replica} back out in exactly the NDJSON line shapes {@link FullSnapshotReader}
 * reads: {@code header}, one {@code capability} line per capability, one {@code plan} line per
 * plan (with its entitlements map), one {@code account} line per account, one {@code override}
 * line per live override, then {@code footer}.
 *
 * <p>Conformance vectors are deliberately not written — they are re-fetched with the next
 * successful sync, so a replica loaded from disk never carries stale ones.
 *
 * <p>Emission order is sorted by key throughout so that two writes of the same replica produce
 * identical bytes; that determinism is what makes the disk cache's round-trip test an identity
 * rather than something that happens to pass.
 */
public final class ReplicaNdjsonWriter {

    private ReplicaNdjsonWriter() {}

    public static void write(Replica replica, OutputStream out) {
        var snapshot = replica.snapshot();

        var capabilities = snapshot.capabilities().stream()
            .sorted(Comparator.comparing(Capability::key))
            .toList();
        var plans = snapshot.plans().stream()
            .sorted(Comparator.comparing(Plan::key))
            .toList();
        var accounts = snapshot.accountAssignments().stream()
            .sorted(Comparator.comparing(AccountAssignment::accountExternalId))
            .toList();
        var overrides = snapshot.allLiveOverrides().stream()
            .sorted(Comparator.comparingLong(o -> o.id().getAsLong()))
            .toList();

        var lines = new ArrayList<Map<String, Object>>();
        for (var capability : capabilities) {
            lines.add(capabilityLine(capability));
        }
        for (var plan : plans) {
            lines.add(planLine(snapshot, plan, capabilities));
        }
        for (var account : accounts) {
            lines.add(accountLine(account));
        }
        for (var override : overrides) {
            lines.add(overrideLine(override));
        }

        var header = new LinkedHashMap<String, Object>();
        header.put("kind", "header");
        header.put("version", replica.version());
        header.put("format", replica.format());
        header.put("resolverContract", replica.resolverContract());
        header.put("publishedAt", replica.publishedAt().toString());
        header.put("counts", Map.of(
            "capabilities", capabilities.size(),
            "plans", plans.size(),
            "accounts", accounts.size(),
            "overrides", overrides.size()));

        var footer = new LinkedHashMap<String, Object>();
        footer.put("kind", "footer");
        footer.put("version", replica.version());
        footer.put("recordCount", lines.size() + 2);

        var writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.println(ClientJson.MAPPER.writeValueAsString(header));
        for (var line : lines) {
            writer.println(ClientJson.MAPPER.writeValueAsString(line));
        }
        writer.println(ClientJson.MAPPER.writeValueAsString(footer));
        writer.flush();
    }

    private static Map<String, Object> capabilityLine(Capability capability) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "capability");
        line.put("key", capability.key().value());
        line.put("area", capability.area());
        line.put("valueType", capability.valueType().name());
        line.put("default", toDto(capability.defaultValue()));
        capability.offValue().ifPresent(off -> line.put("offValue", toDto(off.value())));
        var tiers = capability.tierOrder().tiers();
        if (!tiers.isEmpty()) {
            line.put("tiers", tiers.stream()
                .sorted(Comparator.comparingInt(TierOrder.TierDefinition::ordinal))
                .map(ReplicaNdjsonWriter::tierLine)
                .toList());
        }
        line.put("status", capability.status().name());
        return line;
    }

    private static Map<String, Object> tierLine(TierOrder.TierDefinition tier) {
        var line = new LinkedHashMap<String, Object>();
        line.put("tier", tier.tierKey());
        line.put("ordinal", tier.ordinal());
        line.put("displayName", tier.displayName());
        return line;
    }

    private static Map<String, Object> planLine(Snapshot snapshot, Plan plan, List<Capability> capabilitiesSorted) {
        var entitlements = new LinkedHashMap<String, Object>();
        for (var capability : capabilitiesSorted) {
            snapshot.planEntitlement(plan.key(), capability.key())
                .ifPresent(pe -> entitlements.put(capability.key().value(), toDto(pe.value())));
        }
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "plan");
        line.put("key", plan.key());
        line.put("status", plan.status().name());
        line.put("isDefaultForNewAccounts", plan.defaultForNewAccounts());
        line.put("entitlements", entitlements);
        return line;
    }

    private static Map<String, Object> accountLine(AccountAssignment account) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "account");
        line.put("external", account.accountExternalId());
        line.put("planKey", account.planKey());
        return line;
    }

    private static Map<String, Object> overrideLine(AccountOverride override) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "override");
        line.put("ref", "ovr_" + override.id().getAsLong());
        line.put("account", override.accountExternalId());
        line.put("capability", override.capabilityKey().value());
        line.put("overrideKind", override.kind().name());
        line.put("value", toDto(override.value()));
        return line;
    }

    private static ValueDto toDto(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new ValueDto("SWITCH", s.enabled(), null, null, null, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new ValueDto("QUANTITY", null, null, true, null, null)
                : new ValueDto("QUANTITY", null, q.amount(), null, null, null);
            case EntitlementValue.Tier t -> new ValueDto("TIER", null, null, null, t.tierKey(), t.ordinal());
        };
    }
}
