package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.view.EntitlementView;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Streams the full-resync NDJSON body — one JSON object per line, header first and footer last (snapshot-feed.md). */
@org.springframework.stereotype.Component
public class FullSnapshotWriter {

    private final ObjectMapper mapper;

    public FullSnapshotWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@code publishedAt} is the version's recorded publish time (the same value {@code GET
     * /v1/snapshot/version} reports for this version), not a fresh clock read — snapshot-feed.md
     * §§1–2 require the two endpoints to agree for a given version.
     */
    public void write(Snapshot snapshot, String publishedAt, OutputStream out) throws IOException {
        var writer = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));

        var lines = new ArrayList<Object>();
        int capabilityCount = 0, planCount = 0, accountCount = 0, overrideCount = 0;

        for (var capability : snapshot.capabilities()) {
            lines.add(capabilityLine(capability));
            capabilityCount++;
        }
        for (var plan : snapshot.plans()) {
            lines.add(planLine(snapshot, plan));
            planCount++;
        }
        for (var account : snapshot.accountAssignments()) {
            lines.add(accountLine(account));
            accountCount++;
        }
        for (var override : snapshot.allLiveOverrides()) {
            lines.add(overrideLine(override));
            overrideCount++;
        }
        for (var vector : ConformanceVector.spec5WorkedExamples()) {
            lines.add(conformanceLine(vector));
        }

        var header = Map.of("kind", "header", "version", snapshot.snapshotVersion(), "format", 1,
            "resolverContract", ResolverContract.VERSION, "publishedAt", publishedAt,
            "counts", Map.of("capabilities", capabilityCount, "plans", planCount,
                "accounts", accountCount, "overrides", overrideCount));
        writer.println(mapper.writeValueAsString(header));
        for (var line : lines) {
            writer.println(mapper.writeValueAsString(line));
        }
        var footer = Map.of("kind", "footer", "version", snapshot.snapshotVersion(), "recordCount", lines.size() + 2);
        writer.println(mapper.writeValueAsString(footer));
        writer.flush();
    }

    private static Map<String, Object> capabilityLine(Capability capability) {
        var descriptor = CapabilityDescriptorMapper.toDescriptor(capability);
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "capability"); line.put("key", descriptor.key()); line.put("area", descriptor.area());
        line.put("valueType", descriptor.valueType()); line.put("default", descriptor.defaultValue());
        line.put("offValue", descriptor.offValue()); line.put("tiers", descriptor.tiers()); line.put("status", descriptor.status());
        return line;
    }

    private static Map<String, Object> planLine(EntitlementView view, Plan plan) {
        Map<String, Object> entitlements = new LinkedHashMap<>();
        for (var capability : view.capabilities()) {
            view.planEntitlement(plan.key(), capability.key())
                .ifPresent(pe -> entitlements.put(capability.key().value(), ValueMapper.toDto(pe.value())));
        }
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "plan"); line.put("key", plan.key()); line.put("status", plan.status().name());
        line.put("isDefaultForNewAccounts", plan.defaultForNewAccounts()); line.put("entitlements", entitlements);
        return line;
    }

    private static Map<String, Object> accountLine(AccountAssignment account) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "account"); line.put("external", account.accountExternalId()); line.put("planKey", account.planKey());
        return line;
    }

    // "kind" is already the record-type discriminator every NDJSON line uses; the override's own
    // GRANT/HOLD kind is a different concept and must not collide on that key. "overrideKind" matches
    // the field name the delta stream already uses for the same concept (DeltaChange.OverrideCreated).
    private static Map<String, Object> overrideLine(AccountOverride override) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "override"); line.put("ref", "ovr_" + override.id().getAsLong());
        line.put("account", override.accountExternalId()); line.put("capability", override.capabilityKey().value());
        line.put("overrideKind", override.kind().name());
        line.put("value", ValueMapper.toDto(override.value()));
        return line;
    }

    // Each conformance vector's fixture is a small, purpose-built Snapshot (one or two capabilities,
    // one plan, one account, a couple of overrides) — self-contained by construction (ConformanceVector
    // javadoc). Projecting it with the same line-shapes used above is what makes the NDJSON record
    // "self-contained": a replica can evaluate it with its own engine without the real snapshot data
    // (snapshot-feed.md §2, "The conformance gate").
    private static ConformanceVectorDto conformanceLine(ConformanceVector vector) {
        var fixture = vector.fixture();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("account", vector.accountExternalId());
        model.put("capability", vector.capabilityKey().value());
        model.put("capabilities", fixture.capabilities().stream().map(FullSnapshotWriter::capabilityLine).toList());
        model.put("plans", fixture.plans().stream().map(p -> planLine(fixture, p)).toList());
        model.put("accounts", fixture.accountAssignments().stream().map(FullSnapshotWriter::accountLine).toList());
        model.put("overrides", fixture.allLiveOverrides().stream().map(FullSnapshotWriter::overrideLine).toList());
        Map<String, Object> expect = Map.of("allowed", vector.expectedAllowed(), "value", ValueMapper.toDto(vector.expectedValue()));
        return new ConformanceVectorDto("conformance", vector.name(), model, expect);
    }
}
