package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.DeltaDtos;
import com.solovis.entitlement.client.wire.FeedDtos;
import com.solovis.entitlement.client.wire.ValueDto;
import com.solovis.entitlement.client.wire.WireMapper;
import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/**
 * Moves a {@link Replica} forward by one delta batch, applying every change in ascending version
 * order through {@code entitlement-core}'s {@link SnapshotMutator}.
 *
 * <p>Ordering matters here, for replication. It does not matter for resolution — §4's rule is
 * order-independent, so two replicas that reach the same version by different paths hold identical
 * state and return identical decisions (c16).
 */
public final class DeltaApplier {

    private DeltaApplier() {}

    /** A change kind this SDK does not implement. Stop syncing and keep serving the last good replica. */
    public static final class UnknownChangeKindException extends RuntimeException {
        private final String kind;

        public UnknownChangeKindException(String kind) {
            super("Unknown delta change kind '" + kind + "'. Stopping sync rather than diverging silently.");
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }

    /** A gap or a reordering in the batch. The caller must full-resync. */
    public static final class OutOfOrderDeltaException extends RuntimeException {
        public OutOfOrderDeltaException(String message) {
            super(message);
        }
    }

    public static Replica apply(Replica current, DeltaDtos.DeltaResponse delta) {
        var publishedAt = Instant.parse(delta.publishedAt());
        if (delta.changes().isEmpty()) {
            return new Replica(current.snapshot(), current.overridesByRef(), publishedAt,
                current.vectors(), current.format(), current.resolverContract());
        }

        long expected = current.version() + 1;
        var snapshot = current.snapshot();
        var byRef = new HashMap<>(current.overridesByRef());
        var vectors = current.vectors();

        for (var change : delta.changes()) {
            long version = change.get("version").asLong();
            if (version < expected) {
                throw new OutOfOrderDeltaException(
                    "Delta change at version " + version + " arrived after version " + (expected - 1)
                        + "; batches must ascend without gaps.");
            }
            if (version != expected) {
                throw new OutOfOrderDeltaException(
                    "Delta batch jumps from version " + (expected - 1) + " to " + version
                        + "; a gap means this replica must full-resync.");
            }
            // conformance.changed replaces this replica's vector set rather than mutating the model,
            // so it is handled here where the set is in scope. The caller re-runs ConformanceGate
            // over the returned candidate before swapping it in, which is the entire point: a
            // replica that has been up across a service redeploy re-validates its engine against the
            // new checks instead of coasting on the ones it started with.
            if ("conformance.changed".equals(change.get("kind").asString())) {
                vectors = readVectors(change, publishedAt);
                snapshot = SnapshotMutator.withVersion(snapshot, version);
            } else {
                snapshot = applyOne(snapshot, byRef, change, version, publishedAt);
            }
            expected = version + 1;
        }

        return new Replica(snapshot, byRef, publishedAt, vectors,
            current.format(), current.resolverContract());
    }

    private static List<ConformanceVector> readVectors(JsonNode change, Instant publishedAt) {
        var vectors = new ArrayList<ConformanceVector>();
        for (JsonNode vectorNode : change.get("vectors")) {
            vectors.add(FullSnapshotReader.toVector(
                ClientJson.MAPPER.treeToValue(vectorNode, FeedDtos.ConformanceLine.class), publishedAt));
        }
        return List.copyOf(vectors);
    }

    private static Snapshot applyOne(
            Snapshot snapshot,
            HashMap<Long, AccountOverride> byRef,
            JsonNode change,
            long version,
            Instant publishedAt) {

        var kind = change.get("kind").asString();
        return switch (kind) {
            case "capability.upserted" -> {
                var line = ClientJson.MAPPER.treeToValue(change.get("capability"), FeedDtos.CapabilityLine.class);
                yield SnapshotMutator.withCapability(snapshot, version, WireMapper.toCapability(line, publishedAt));
            }
            case "capability.retired" -> {
                var key = new CapabilityKey(change.get("key").asString());
                var existing = snapshot.capability(key).orElse(null);
                yield existing == null
                    // never seen here; a full resync may already have passed the retirement
                    ? SnapshotMutator.withVersion(snapshot, version)
                    : SnapshotMutator.withCapability(snapshot, version, retire(existing, publishedAt));
            }
            case "plan.upserted" -> SnapshotMutator.withPlan(snapshot, version, new Plan(
                change.get("key").asString(),
                change.get("name").asString(),
                Plan.Status.valueOf(change.get("status").asString()),
                change.get("isDefaultForNewAccounts").asBoolean()));
            case "plan.entitlements" -> {
                var planKey = change.get("planKey").asString();
                var next = snapshot;
                var set = change.get("set");
                if (set != null) {
                    for (var entry : set.properties()) {
                        var value = WireMapper.toValue(
                            ClientJson.MAPPER.treeToValue(entry.getValue(), ValueDto.class));
                        next = SnapshotMutator.withPlanEntitlement(next, version,
                            new PlanEntitlement(planKey, new CapabilityKey(entry.getKey()), value));
                    }
                }
                var unset = change.get("unset");
                if (unset != null) {
                    for (var node : unset) {
                        next = SnapshotMutator.withPlanEntitlementRemoved(
                            next, version, planKey, new CapabilityKey(node.asString()));
                    }
                }
                yield next;
            }
            case "plan.archived" -> {
                var key = change.get("key").asString();
                var existing = snapshot.plan(key).orElse(null);
                yield existing == null
                    // never seen here; a full resync may already have passed the archival
                    ? SnapshotMutator.withVersion(snapshot, version)
                    : SnapshotMutator.withPlan(snapshot, version,
                        new Plan(existing.key(), existing.name(), Plan.Status.ARCHIVED, false));
            }
            case "plan.defaultChanged" -> {
                var key = change.get("key").asString();
                var next = snapshot;
                for (var plan : snapshot.plans()) {
                    if (plan.defaultForNewAccounts() && !plan.key().equals(key)) {
                        next = SnapshotMutator.withPlan(next, version,
                            new Plan(plan.key(), plan.name(), plan.status(), false));
                    }
                }
                var target = next.plan(key).orElse(null);
                yield target == null
                    // never seen here; a full resync may already have passed the default change
                    ? SnapshotMutator.withVersion(next, version)
                    : SnapshotMutator.withPlan(next, version,
                        new Plan(target.key(), target.name(), target.status(), true));
            }
            case "account.upserted" -> SnapshotMutator.withAccount(snapshot, version,
                new AccountAssignment(change.get("external").asString(), change.get("planKey").asString()));
            case "override.created" -> {
                long id = WireMapper.refToId(change.get("ref").asString());
                if (byRef.containsKey(id)) {
                    // already applied; the core mutator appends without dedupe
                    yield SnapshotMutator.withVersion(snapshot, version);
                }
                var override = new AccountOverride(
                    OptionalLong.of(id),
                    change.get("account").asString(),
                    new CapabilityKey(change.get("capability").asString()),
                    OverrideKind.valueOf(change.get("overrideKind").asString()),
                    WireMapper.toValue(ClientJson.MAPPER.treeToValue(change.get("value"), ValueDto.class)),
                    Optional.empty(), Optional.empty(), Optional.empty());
                byRef.put(id, override);
                yield SnapshotMutator.withOverrideAdded(snapshot, version, override);
            }
            case "override.removed" -> {
                long id = WireMapper.refToId(change.get("ref").asString());
                var known = byRef.remove(id);
                yield known == null
                    // never seen here; a full resync may already have passed the removal
                    ? SnapshotMutator.withVersion(snapshot, version)
                    : SnapshotMutator.withOverrideRemoved(
                        snapshot, version, known.accountExternalId(), known.capabilityKey(), id);
            }
            default -> throw new UnknownChangeKindException(kind);
        };
    }

    private static Capability retire(Capability existing, Instant retiredAt) {
        return new Capability(
            existing.key(), existing.displayName(), existing.description(), existing.valueType(),
            existing.defaultValue(), existing.offValue(), existing.tierOrder(),
            Capability.Status.RETIRED, retiredAt);
    }
}
