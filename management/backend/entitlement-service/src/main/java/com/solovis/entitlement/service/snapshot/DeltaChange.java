package com.solovis.entitlement.service.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;
import java.util.Map;

/** One row of the snapshot feed's delta stream (contracts/snapshot-feed.md, "Change kinds"). One instance is persisted per {@code snapshot_version} row. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DeltaChange.CapabilityUpserted.class, name = "capability.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.CapabilityRetired.class, name = "capability.retired"),
    @JsonSubTypes.Type(value = DeltaChange.PlanUpserted.class, name = "plan.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.PlanEntitlements.class, name = "plan.entitlements"),
    @JsonSubTypes.Type(value = DeltaChange.PlanArchived.class, name = "plan.archived"),
    @JsonSubTypes.Type(value = DeltaChange.PlanDefaultChanged.class, name = "plan.defaultChanged"),
    @JsonSubTypes.Type(value = DeltaChange.AccountUpserted.class, name = "account.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.OverrideCreated.class, name = "override.created"),
    @JsonSubTypes.Type(value = DeltaChange.OverrideRemoved.class, name = "override.removed"),
    @JsonSubTypes.Type(value = DeltaChange.ConformanceChanged.class, name = "conformance.changed"),
})
public sealed interface DeltaChange {
    record CapabilityUpserted(CapabilityDescriptorDto capability) implements DeltaChange {}
    record CapabilityRetired(String key) implements DeltaChange {}
    record PlanUpserted(String key, String name, String status, boolean isDefaultForNewAccounts) implements DeltaChange {}
    record PlanEntitlements(String planKey, Map<String, ValueDto> set, List<String> unset) implements DeltaChange {}
    record PlanArchived(String key) implements DeltaChange {}
    record PlanDefaultChanged(String key) implements DeltaChange {}
    record AccountUpserted(String external, String planKey) implements DeltaChange {}
    record OverrideCreated(String ref, String account, String capability, String overrideKind, ValueDto value) implements DeltaChange {}
    record OverrideRemoved(String ref) implements DeltaChange {}

    /**
     * A replacement conformance vector set (snapshot-feed.md, "Change kinds"). Carries the whole set
     * rather than a diff: the replica's job on receipt is to re-run its gate against the new set
     * before serving, and a partial set could not be gated at all.
     *
     * <p>Unlike every other change kind here, this one describes the service's <em>build</em> rather
     * than the model. It changes nothing a decision depends on, which is why it can be published
     * without an audit event of its own.
     */
    record ConformanceChanged(List<ConformanceVectorDto> vectors) implements DeltaChange {}
}
